"""python -m waifuscam_mind  →  sobe a Mente e espera o mod conectar."""

from __future__ import annotations

import asyncio
import logging
import sys

from .brain import Brain, ChatBrain, EchoBrain
from .character import Character, CharacterError, load_character
from .config import Settings
from .llm import Budget, LLMClient, load_providers
from .server import MindServer
from .skins import SkinResolver

log = logging.getLogger("waifuscam_mind")


def build_brain(character: Character, settings: Settings) -> Brain:
    """ChatBrain se houver alguma chave de IA; senão, o eco da fase 1."""
    cfg = character.section("llm")
    order = cfg.get("provider_order") or None
    providers = load_providers(order, str(cfg.get("chat_model", "")))
    if not providers:
        log.warning("Nenhuma chave de IA no .env (GROQ_API_KEY, GEMINI_API_KEY...). Ela vai só repetir em eco.")
        return EchoBrain(character)
    log.info("IA: %s", ", ".join(f"{pr.name} ({pr.model})" for pr in providers))
    llm = LLMClient(providers, temperature=float(cfg.get("temperature", 0.9)))
    budget = Budget(int(cfg.get("daily_limit", 800)), settings.data_dir / f"budget-{character.id}.json")
    return ChatBrain(character, llm, budget)


def main() -> None:
    settings = Settings.from_env()
    logging.basicConfig(level=settings.log_level, format="%(asctime)s %(levelname)-7s %(name)s: %(message)s")
    try:
        character = load_character(settings.characters_dir, settings.character)
    except CharacterError as e:
        sys.exit(f"Erro na personagem: {e}")
    skins = SkinResolver(settings.data_dir / "skin-cache.json", settings.mineskin_key)
    try:
        brain = build_brain(character, settings)
    except ValueError as e:
        sys.exit(f"Erro na configuração de IA: {e}")
    server = MindServer(character, settings.host, settings.port, skins=skins, brain=brain)
    try:
        asyncio.run(server.serve_forever())
    except KeyboardInterrupt:
        pass
    except OSError as e:
        sys.exit(f"Não consegui abrir ws://{settings.host}:{settings.port}: {e}")


if __name__ == "__main__":
    main()
