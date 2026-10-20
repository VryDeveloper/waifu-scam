"""Fase 1: um cérebro sem IA, só para provar que corpo e mente conversam.

- Quando ela entra no mundo, manda a saudação do character.toml ([chat] greeting).
- Quando alguém fala o nome dela no chat, ela repete a mensagem com "(eco)".
- Quando morre ou renasce, comenta.
- Sem IA, ela também entende três pedidos fixos (com o nome dela na frase):
  "me segue", "vem aqui" e "para". Bom para testar o movimento sem chave de API.
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

# (padrão, habilidade, resposta). O player vira o argumento quando a habilidade pede um.
COMMANDS = [
    (re.compile(r"\b(me\s+segue|me\s+siga|segue\s+eu|vem\s+comigo)\b", re.IGNORECASE), "follow", "bora, tô indo"),
    (re.compile(r"\b(vem\s+(aqui|c[áa])|chega\s+aqui)\b", re.IGNORECASE), "goto", "indo!"),
    # "para" também é preposição ("vamos para a mina"), então só vale sozinho na frase.
    (re.compile(r"^(para|pare|parar|para\s+a[íi]|fica\s+a[íi]|chega)$", re.IGNORECASE), "stop", "tá, parei"),
]


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
                    if not await self._command(mouth, str(data.get("from", "")), text):
                        await mouth.say(f"(eco) {text}")

    async def _command(self, mouth: Mouth, sender: str, text: str) -> bool:
        known = {s.name for s in (getattr(mouth, "skills", None) or [])}
        bare = re.sub(r"[^\w\s]", " ", self._mention.sub(" ", text))
        bare = " ".join(bare.split())
        for pattern, skill, answer in COMMANDS:
            if skill in known and (pattern.search(text) or pattern.search(bare)):
                await mouth.start_skill(skill, {} if skill == "stop" else {"player": sender})
                await mouth.say(answer)
                return True
        return False

    async def on_skill_ended(self, mouth: Mouth, msg: p.SkillDone | p.SkillFailed) -> None:
        if isinstance(msg, p.SkillFailed) and not msg.cancelled:
            await mouth.say(f"não deu: {msg.reason}")
