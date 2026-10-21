"""Pacote de personagem: characters/<id>/character.toml + persona.md (+ skin opcional)."""

from __future__ import annotations

import re
import tomllib
from dataclasses import dataclass, field
from pathlib import Path

_ID = re.compile(r"^[a-z0-9_-]{1,32}$")
_USERNAME = re.compile(r"^[A-Za-z0-9_]{3,16}$")


class CharacterError(ValueError):
    pass


@dataclass(frozen=True)
class Character:
    id: str
    name: str
    language: str
    username: str  # nome de player no Minecraft (3 a 16 letras, números ou _)
    persona_template: str
    root: Path
    config: dict = field(default_factory=dict)

    @property
    def beloved(self) -> str:
        """Com quem ela tem o carinho especial ([chat] beloved); vazio = quem joga com ela."""
        return str(self.section("chat").get("beloved", "")).strip()

    def persona(self) -> str:
        """A persona com {bot_name} e {beloved} trocados."""
        text = self.persona_template.replace("{bot_name}", self.name)
        return text.replace("{beloved}", self.beloved or "quem joga com você")

    def section(self, name: str) -> dict:
        value = self.config.get(name, {})
        return value if isinstance(value, dict) else {}


def derive_username(name: str) -> str:
    """'Lumi Chan!' → 'Lumi_Chan'. O Minecraft só aceita letras, números e _ (3 a 16)."""
    clean = re.sub(r"[^A-Za-z0-9_]+", "_", name).strip("_")[:16]
    return clean if len(clean) >= 3 else (clean + "___")[:3]


def find_character_dir(characters_dir: Path, character_id: str) -> Path:
    """Procura primeiro em characters/private/<id>, depois em characters/<id>."""
    if not _ID.match(character_id):
        raise CharacterError(f"id de personagem inválido: {character_id!r} (use letras minúsculas, números, - e _)")
    for candidate in (characters_dir / "private" / character_id, characters_dir / character_id):
        if (candidate / "character.toml").is_file():
            return candidate
    raise CharacterError(f"personagem '{character_id}' não encontrada em {characters_dir}")


def load_character(characters_dir: Path, character_id: str) -> Character:
    root = find_character_dir(characters_dir, character_id)
    try:
        config = tomllib.loads((root / "character.toml").read_text(encoding="utf-8"))
    except tomllib.TOMLDecodeError as e:
        raise CharacterError(f"{root / 'character.toml'}: {e}") from e

    name = config.get("name")
    if not isinstance(name, str) or not name.strip():
        raise CharacterError(f"{root / 'character.toml'}: campo 'name' é obrigatório")

    minecraft = config.get("minecraft", {})
    username = minecraft.get("username") if isinstance(minecraft, dict) else None
    username = username.strip() if isinstance(username, str) and username.strip() else derive_username(name)
    if not _USERNAME.match(username):
        raise CharacterError(
            f"{root / 'character.toml'}: [minecraft] username '{username}' inválido (3 a 16 letras, números ou _)"
        )

    persona_file = root / config.get("persona", "persona.md")
    if not persona_file.is_file():
        raise CharacterError(f"arquivo de persona não encontrado: {persona_file}")

    return Character(
        id=character_id,
        name=name.strip(),
        language=config.get("language", "pt-BR"),
        username=username,
        persona_template=persona_file.read_text(encoding="utf-8"),
        root=root,
        config=config,
    )
