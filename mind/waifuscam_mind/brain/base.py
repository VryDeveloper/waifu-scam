from __future__ import annotations

from typing import Any, Protocol

from .. import protocol as p


class Mouth(Protocol):
    """O que um cérebro pode fazer com o corpo. Implementado pela Session."""

    async def say(self, text: str) -> None: ...

    @property
    def state(self) -> p.State | None: ...

    @property
    def skills(self) -> list[p.SkillSpec]:
        """Catálogo de habilidades que o corpo mandou no hello (vazio num corpo antigo)."""
        ...

    async def start_skill(self, name: str, args: dict[str, Any] | None = None) -> str: ...

    async def cancel_skill(self) -> None: ...


class Brain(Protocol):
    async def on_event(self, mouth: Mouth, event: p.Event) -> None: ...

    # Opcionais:
    # async def on_skill_ended(self, mouth: Mouth, msg: p.SkillDone | p.SkillFailed) -> None
    # async def on_state(self, mouth: Mouth, state: p.State) -> None   (a cada ~2 s)
