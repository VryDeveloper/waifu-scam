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
    # Modelo para o que não é conversa (pensar sozinha, diário, comentários). No Groq
    # cada modelo tem a própria cota por minuto, então separar deixa o chat mais rápido.
    background_model: str = ""
    # Outros modelos da mesma chave, para quando o principal bater no limite por minuto.
    # No Groq cada modelo tem a própria cota, então revezar multiplica o que ela aguenta.
    fallback_models: tuple[str, ...] = ()

    def model_for(self, background: bool) -> str:
        return self.background_model if background and self.background_model else self.model

    def models_for(self, background: bool) -> list[str]:
        """O modelo do caso (conversa ou fundo) e depois os reservas, sem repetir."""
        chain = [self.model_for(background), *self.fallback_models]
        if background and self.background_model:
            chain = [m for m in chain if m != self.model]  # o fundo nunca gasta a cota da conversa
        return list(dict.fromkeys(m for m in chain if m))


@dataclass(frozen=True)
class Preset:
    base_url: str
    default_model: str
    background_model: str = ""
    fallback_models: tuple[str, ...] = ()


PRESETS: dict[str, Preset] = {
    "groq": Preset(
        "https://api.groq.com/openai/v1",
        "openai/gpt-oss-120b",
        "openai/gpt-oss-20b",
        ("qwen/qwen3.8-27b", "openai/gpt-oss-20b"),
    ),
    "gemini": Preset(
        "https://generativelanguage.googleapis.com/v1beta/openai/",
        "gemini-flash-latest",
        "gemini-flash-lite-latest",
        ("gemini-flash-lite-latest",),
    ),
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
        background = os.getenv(f"{prefix}_BACKGROUND_MODEL", "").strip() or (
            "" if model_override.strip() or os.getenv(f"{prefix}_MODEL", "").strip() else preset.background_model
        )
        fallbacks = os.getenv(f"{prefix}_FALLBACK_MODELS")
        chain = (
            tuple(m.strip() for m in fallbacks.split(",") if m.strip())
            if fallbacks is not None
            else ("" if model_override.strip() else preset.fallback_models)
        )
        if key and base_url and model:
            providers.append(Provider(name, base_url, key, model, _extra_body_for(model), background, tuple(chain)))
    return providers
