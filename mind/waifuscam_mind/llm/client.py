"""Cliente de chat que tenta os provedores em ordem até um responder."""

from __future__ import annotations

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

    async def chat(self, messages: list[dict], *, max_tokens: int = 200) -> str:
        errors: list[str] = []
        for p in self._ordered():
            try:
                resp = await self._clients[p.name].chat.completions.create(
                    model=p.model,
                    messages=messages,
                    temperature=self.temperature,
                    max_tokens=max_tokens,
                    extra_body=p.extra_body or None,
                )
            except openai.RateLimitError:
                self._cooldown_until[p.name] = time.monotonic() + RATE_LIMIT_COOLDOWN
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
                log.debug("Resposta de %s (%s)", p.name, p.model)
                return text
            errors.append(f"{p.name}: resposta vazia")

        raise AllProvidersFailed(" | ".join(errors))
