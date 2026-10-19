"""Provedores de LLM com plano gratuito. Mesma ideia do VryBot.

Todos falam a API de Chat Completions da OpenAI, então um único cliente serve
para todos. A ordem vem de [llm] provider_order no character.toml; as chaves
vêm do .env. Provedor sem chave é ignorado.
"""

from __future__ import annotations

import os
from dataclasses import dataclass, field


@dataclass(frozen=True)
class Provider:
    name: str
    base_url: str
    api_key: str
    model: str
    extra_body: dict = field(default_factory=dict)


@dataclass(frozen=True)
class Preset:
    base_url: str
    default_model: str


PRESETS: dict[str, Preset] = {
    "groq": Preset("https://api.groq.com/openai/v1", "openai/gpt-oss-120b"),
    "gemini": Preset("https://generativelanguage.googleapis.com/v1beta/openai/", "gemini-flash-latest"),
    "openrouter": Preset("https://openrouter.ai/api/v1", "meta-llama/llama-3.3-70b-instruct:free"),
    # Qualquer servidor compatível com a API da OpenAI (Ollama, LM Studio...): CUSTOM_BASE_URL
    "custom": Preset("", ""),
}

DEFAULT_ORDER = ("groq", "gemini", "openrouter")


def _extra_body_for(model: str) -> dict:
    # Modelos que "pensam" (gpt-oss) respondem mais rápido com esforço baixo;
    # os outros recusam esse campo.
    if "gpt-oss" in model:
        return {"reasoning_effort": os.getenv("REASONING_EFFORT", "").strip() or "low"}
    return {}


def load_providers(order: list[str] | None = None, model_override: str = "") -> list[Provider]:
    """Monta a lista de provedores na ordem pedida, pulando os que não têm chave.

    model_override vale para todos (ex.: chat_model do character.toml); vazio usa
    <NOME>_MODEL do .env ou o padrão de cada provedor.
    """
    providers: list[Provider] = []
    for name in [n.strip().lower() for n in (order or DEFAULT_ORDER) if n.strip()]:
        preset = PRESETS.get(name)
        if preset is None:
            raise ValueError(f"Provedor desconhecido '{name}'. Conhecidos: {', '.join(PRESETS)}")
        prefix = name.upper()
        key = os.getenv(f"{prefix}_API_KEY", "").strip()
        base_url = os.getenv(f"{prefix}_BASE_URL", "").strip() or preset.base_url
        model = model_override.strip() or os.getenv(f"{prefix}_MODEL", "").strip() or preset.default_model
        if name == "custom" and base_url and not key:
            key = "not-needed"  # servidores locais ignoram a chave
        if key and base_url and model:
            providers.append(Provider(name, base_url, key, model, _extra_body_for(model)))
    return providers
