"""python -m waifuscam_mind  →  sobe a Mente e espera o mod conectar."""

from __future__ import annotations

import asyncio
import logging
import sys

from .character import CharacterError, load_character
from .config import Settings
from .server import MindServer


def main() -> None:
    settings = Settings.from_env()
    logging.basicConfig(level=settings.log_level, format="%(asctime)s %(levelname)-7s %(name)s: %(message)s")
    try:
        character = load_character(settings.characters_dir, settings.character)
    except CharacterError as e:
        sys.exit(f"Erro na personagem: {e}")
    server = MindServer(character, settings.host, settings.port)
    try:
        asyncio.run(server.serve_forever())
    except KeyboardInterrupt:
        pass
    except OSError as e:
        sys.exit(f"Não consegui abrir ws://{settings.host}:{settings.port}: {e}")


if __name__ == "__main__":
    main()
