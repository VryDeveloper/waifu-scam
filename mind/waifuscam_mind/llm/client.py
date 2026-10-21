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
MAX_RATE_WAIT = 12.0  # na conversa, vale esperar a cota liberar em vez de ficar muda


class AllProvidersFailed(RuntimeError):
    pass


class LLMClient:
    def __init__(self, providers: list[Provider], *, temperature: float = 0.9, timeout: float = 30):
        if not providers:
            raise ValueError("Nenhum provedor de IA configurado. Coloque pelo menos uma chave no .env.")
        self.providers = providers
        self.temperature = temperature
        self._clients = {
            p.name: AsyncOpenAI(api_key=p.api_key, base_url=p.base_url, timeout=timeout) for p in providers
        }
        self._cooldown_until: dict[str, float] = {}

    def _ordered(self) -> list[Provider]:
        now = time.monotonic()
        # Ordenação estável: quem está em cooldown vai para o fim, mas ainda é tentado.
        return sorted(self.providers, key=lambda p: self._cooldown_until.get(p.name, 0) > now)

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
            try:
                resp = await self._clients[p.name].chat.completions.create(
                    model=p.model_for(background),
                    messages=messages,
                    temperature=self.temperature,
                    max_tokens=max_tokens,
                    extra_body=_extra_body(p, background),
                )
            except openai.RateLimitError as e:
                self._cooldown_until[p.name] = time.monotonic() + RATE_LIMIT_COOLDOWN
                waits.append(_retry_after(e))
                errors.append(f"{p.name}: limite atingido")
                continue
            except _FALLBACK_ERRORS as e:
                errors.append(f"{p.name}: {type(e).__name__}")
                continue
            except openai.APIStatusError as e:
                errors.append(f"{p.name}: HTTP {e.status_code}")
                continue

            text = (resp.choices[0].message.content or "").strip() if resp.choices else ""
            if text:
                log.debug("Resposta de %s (%s)", p.name, p.model_for(background))
                self._cooldown_until.pop(p.name, None)
                return text
            errors.append(f"{p.name}: resposta vazia")

        if waits and len(waits) == len(errors):
            raise _AllRateLimited(" | ".join(errors), min(waits))
        raise AllProvidersFailed(" | ".join(errors))


class _AllRateLimited(Exception):
    def __init__(self, message: str, wait: float):
        super().__init__(message)
        self.wait = wait


def _retry_after(e: openai.RateLimitError) -> float:
    """Quanto o provedor pediu para esperar (cabeçalho retry-after); 5 s se não disse."""
    try:
        value = e.response.headers.get("retry-after") if getattr(e, "response", None) is not None else None
        return max(0.5, min(60.0, float(value))) if value else 5.0
    except (TypeError, ValueError, AttributeError):
        return 5.0


def _extra_body(p: Provider, background: bool) -> dict | None:
    model = p.model_for(background)
    if "gpt-oss" in model:
        import os

        return {"reasoning_effort": os.getenv("REASONING_EFFORT", "").strip() or "low"}
    return p.extra_body or None
