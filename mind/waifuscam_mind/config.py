"""Configuração da Mente: variáveis de ambiente (ou .env na raiz do repositório)."""

from __future__ import annotations

import os
from dataclasses import dataclass
from pathlib import Path

from dotenv import load_dotenv

REPO_ROOT = Path(__file__).resolve().parents[2]


def _env(name: str, default: str) -> str:
    return os.getenv(name, "").strip() or default


@dataclass(frozen=True)
class Settings:
    host: str
    port: int
    character: str
    characters_dir: Path
    log_level: str

    @classmethod
    def from_env(cls) -> Settings:
        load_dotenv(REPO_ROOT / ".env")
        chars = Path(_env("WAIFU_CHARACTERS_DIR", str(REPO_ROOT / "characters")))
        return cls(
            host=_env("WAIFU_HOST", "127.0.0.1"),
            port=int(_env("WAIFU_PORT", "8765")),
            character=_env("WAIFU_CHARACTER", "lumi"),
            characters_dir=chars,
            log_level=_env("WAIFU_LOG_LEVEL", "INFO").upper(),
        )
