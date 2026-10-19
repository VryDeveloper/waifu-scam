"""Fase 1: um cérebro sem IA, só para provar que corpo e mente conversam.

- Quando ela entra no mundo, manda a saudação do character.toml ([chat] greeting).
- Quando alguém fala o nome dela no chat, ela repete a mensagem com "(eco)".
- Quando morre ou renasce, comenta.
"""

from __future__ import annotations

import logging
import re
import time

from .. import protocol as p
from ..character import Character
from .base import Mouth

log = logging.getLogger(__name__)

MIN_SECONDS_BETWEEN_ECHOES = 2.0


class EchoBrain:
    def __init__(self, character: Character):
        self.character = character
        names = {character.name, character.username}
        self._mention = re.compile(r"\b(" + "|".join(re.escape(n) for n in names) + r")\b", re.IGNORECASE)
        self._last_echo = 0.0

    def mentions_me(self, text: str) -> bool:
        return bool(self._mention.search(text))

    async def on_event(self, mouth: Mouth, event: p.Event) -> None:
        data = event.model_dump()
        match event.event:
            case "spawned":
                greeting = self.character.section("chat").get("greeting", "oi! cheguei")
                await mouth.say(greeting)
            case "respawned":
                await mouth.say("voltei. não comenta")
            case "died":
                log.info("%s morreu: %s", self.character.name, data.get("message"))
            case "chat":
                text = str(data.get("text", ""))
                online = mouth.state is not None and mouth.state.online
                now = time.monotonic()
                if online and self.mentions_me(text) and now - self._last_echo >= MIN_SECONDS_BETWEEN_ECHOES:
                    self._last_echo = now
                    await mouth.say(f"(eco) {text}")
