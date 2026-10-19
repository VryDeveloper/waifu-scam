from __future__ import annotations

from typing import Protocol

from .. import protocol as p


class Mouth(Protocol):
    """O que um cérebro pode fazer com o corpo. Implementado pela Session."""

    async def say(self, text: str) -> None: ...

    @property
    def state(self) -> p.State | None: ...


class Brain(Protocol):
    async def on_event(self, mouth: Mouth, event: p.Event) -> None: ...
