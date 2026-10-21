"""Cliente de chat que tenta os provedores em ordem até um responder."""

from __future__ import annotations

import asyncio
import logging
import time

import openai
from openai import AsyncOpenAI

from .providers import Provider

log = logging.getLogger(__name__)

# Erros em que vale tentar o próximo provedor.
_FALLBACK_ERRORS = (
    openai.RateLimitError,
    openai.APIConnectionError,
    openai.APITimeoutError,
    openai.InternalServerError,
    openai.NotFoundError,
    openai.AuthenticationError,
    openai.PermissionDeniedError,
)

RATE_LIMIT_COOLDOWN = 60  # segundos que um provedor limitado vai para o fim da fila
MAX_RATE_WAIT = 25.0  # na conversa, vale esperar a cota liberar em vez de ficar muda


class AllProvidersFailed(RuntimeError):
    pass


class LLMClient:
    def __init__(self, providers: list[Provider], *, temperature: float = 0.9, timeout: float = 20):
        if not providers:
            raise ValueError("Nenhum provedor de IA configurado. Coloque pelo menos uma chave no .env.")
        self.providers = providers
        self.temperature = temperature
        self._clients = {
            # Sem novas tentativas escondidas do SDK: quem decide tentar outro modelo é este cliente.
            p.name: AsyncOpenAI(api_key=p.api_key, base_url=p.base_url, timeout=timeout, max_retries=0)
            for p in providers
        }
        self._cooldown_until: dict[str, float] = {}

    def _ordered(self) -> list[Provider]:
        now = time.monotonic()
        # Ordenação estável: quem está em cooldown vai para o fim, mas ainda é tentado.
        return sorted(self.providers, key=lambda p: self._cooldown_until.get(p.name, 0) > now)

    def _models(self, p: Provider, background: bool) -> list[str]:
        """Modelos do provedor nesta vez: os que bateram no limite há pouco vão para o fim."""
        now = time.monotonic()
        models = p.models_for(background)
        ready = [m for m in models if self._cooldown_until.get(f"{p.name}:{m}", 0) <= now]
        return ready or models  # todos no limite: tenta mesmo assim (a cota por minuto volta rápido)

    async def chat(self, messages: list[dict], *, max_tokens: int = 200, background: bool = False) -> str:
        """background=True usa o modelo de fundo (cota separada) e nunca espera o limite liberar."""
        try:
            return await self._try_all(messages, max_tokens, background)
        except _AllRateLimited as e:
            if background or e.wait > MAX_RATE_WAIT:
                raise AllProvidersFailed(str(e)) from None
            log.info("Cota de IA no limite; esperando %.1f s para responder.", e.wait)
            await asyncio.sleep(e.wait)
            try:
                return await self._try_all(messages, max_tokens, background)
            except _AllRateLimited as again:
                raise AllProvidersFailed(str(again)) from None

    async def _try_all(self, messages: list[dict], max_tokens: int, background: bool) -> str:
        errors: list[str] = []
        waits: list[float] = []
        for p in self._ordered():
            limited = 0
            models = self._models(p, background)
            for model in models:
                try:
                    resp = await self._clients[p.name].chat.completions.create(
                        model=model,
                        messages=messages,
                        temperature=self.temperature,
                        max_tokens=max_tokens,
                        extra_body=_extra_body_model(p, model),
                    )
                except openai.RateLimitError as e:
                    # Limite do dia (TPD) pede para esperar muitos minutos: respeita, para não
                    # gastar tempo batendo de novo; o de minuto volta em segundos.
                    asked = _retry_after(e, cap=6 * 3600)
                    self._cooldown_until[f"{p.name}:{model}"] = time.monotonic() + max(RATE_LIMIT_COOLDOWN, asked)
                    if asked > 120:
                        log.warning("%s/%s bateu no limite do dia; volta em %.0f min", p.name, model, asked / 60)
                    waits.append(min(asked, 60.0))
                    errors.append(f"{p.name}/{model}: limite atingido")
                    limited += 1
                    continue
                except _FALLBACK_ERRORS as e:
                    errors.append(f"{p.name}/{model}: {type(e).__name__}")
                    continue
                except openai.APIStatusError as e:
                    errors.append(f"{p.name}/{model}: HTTP {e.status_code}")
                    continue

                text = (resp.choices[0].message.content or "").strip() if resp.choices else ""
                if text:
                    if model != models[0]:
                        log.info("Resposta veio do modelo reserva %s (%s)", model, p.name)
                    self._cooldown_until.pop(p.name, None)
                    self._cooldown_until.pop(f"{p.name}:{model}", None)
                    return text
                errors.append(f"{p.name}/{model}: resposta vazia")
            if limited == len(models):
                self._cooldown_until[p.name] = time.monotonic() + RATE_LIMIT_COOLDOWN

        if waits and len(waits) == len(errors):
            raise _AllRateLimited(" | ".join(errors), min(waits))
        raise AllProvidersFailed(" | ".join(errors))


class _AllRateLimited(Exception):
    def __init__(self, message: str, wait: float):
        super().__init__(message)
        self.wait = wait


def _retry_after(e: openai.RateLimitError, cap: float = 60.0) -> float:
    """Quanto o provedor pediu para esperar (cabeçalho retry-after); 5 s se não disse."""
    try:
        value = e.response.headers.get("retry-after") if getattr(e, "response", None) is not None else None
        return max(0.5, min(cap, float(value))) if value else 5.0
    except (TypeError, ValueError, AttributeError):
        return 5.0


def _extra_body(p: Provider, background: bool) -> dict | None:
    return _extra_body_model(p, p.model_for(background))


def _extra_body_model(p: Provider, model: str) -> dict | None:
    if "gpt-oss" in model:
        import os

        return {"reasoning_effort": os.getenv("REASONING_EFFORT", "").strip() or "low"}
    return None  # os outros modelos recusam esse campo
