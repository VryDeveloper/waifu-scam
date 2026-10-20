"""Vontade própria (fase 5): o que ela faz quando ninguém está pedindo nada.

A cada state (~2 s) a Vontade olha as necessidades e decide:

1. Noite chegando e ela ao ar livre, parada: vai se abrigar (sem gastar IA).
2. Promessa pendente ("depois eu faço") e ela livre: cumpre agora.
3. Com fome e sem comida, ou parada há muito tempo: pede para a IA decidir,
   com a persona e as necessidades no prompt. A IA pode agir, falar ou as duas.

Ela nunca interrompe o que está fazendo por vontade própria, só quando está
parada. Pedido de player tem prioridade (passa pelo chat normal).
"""

from __future__ import annotations

import logging
import time
from collections import deque
from collections.abc import Awaitable, Callable
from dataclasses import dataclass, field
from typing import TYPE_CHECKING, Any

from .. import protocol as p
from .needs import Needs, assess, dusk

if TYPE_CHECKING:
    from .base import Mouth

log = logging.getLogger(__name__)

MAX_PROMISES = 8
PROMISE_TTL = 30 * 60  # depois de meia hora a promessa expira


@dataclass
class Promise:
    """Algo que ela topou fazer depois."""

    skill: str
    args: dict[str, Any]
    requester: str | None
    created: float
    said: str = ""

    def describe(self) -> str:
        args = " ".join(str(v) for v in self.args.values())
        who = f" para {self.requester}" if self.requester else ""
        return f"{self.skill} {args}".strip() + who


@dataclass
class WillConfig:
    enabled: bool = True
    think_every: float = 180.0  # segundos parada até pensar sozinha
    shelter_at_night: bool = True
    hungry_think_every: float = 180.0
    min_budget_fraction: float = 0.15  # abaixo disso, não gasta IA pensando sozinha

    @classmethod
    def from_section(cls, section: dict) -> WillConfig:
        cfg = cls()
        for key in ("enabled", "shelter_at_night"):
            if key in section:
                setattr(cfg, key, bool(section[key]))
        for key in ("think_every", "hungry_think_every", "min_budget_fraction"):
            if key in section:
                setattr(cfg, key, float(section[key]))
        return cfg


# Funções que a Vontade recebe do ChatBrain (para falar com a persona e com o orçamento dele).
Speak = Callable[..., Awaitable[None]]


@dataclass
class Will:
    weights: dict[str, float]
    config: WillConfig
    speak: Speak  # ChatBrain._speak
    budget_fraction: Callable[[], float]
    clock: Callable[[], float] = time.monotonic
    start: Callable[..., Awaitable[str]] | None = None  # (mouth, skill, args, partner) → id

    promises: deque[Promise] = field(default_factory=lambda: deque(maxlen=MAX_PROMISES))
    last_busy: float = field(default=0.0)
    last_social: float = field(default=0.0)
    last_think: float = field(default=float("-inf"))
    last_hungry_think: float = field(default=float("-inf"))
    sheltering: bool = False
    _thinking: bool = False

    def __post_init__(self) -> None:
        now = self.clock()
        self.last_busy = now
        self.last_social = now

    # ---- o que os outros avisam --------------------------------------------------

    def heard_chat(self) -> None:
        self.last_social = self.clock()
        self.last_busy = self.clock()

    def promise(self, skill: str, args: dict[str, Any], requester: str | None, said: str = "") -> None:
        self.promises.append(Promise(skill, args, requester, self.clock(), said))
        log.info("Promessa guardada: %s", self.promises[-1].describe())

    def pending(self) -> list[Promise]:
        now = self.clock()
        while self.promises and now - self.promises[0].created > PROMISE_TTL:
            log.info("Promessa expirou: %s", self.promises.popleft().describe())
        return list(self.promises)

    def needs(self, state: p.State | None) -> Needs:
        now = self.clock()
        return assess(state, self.weights, idle_seconds=now - self.last_busy, since_social=now - self.last_social)

    # ---- decidir -------------------------------------------------------------------

    async def on_state(self, mouth: Mouth, state: p.State) -> None:
        if not self.config.enabled or not state.online or self._thinking:
            return
        s = state.model_dump()
        now = self.clock()
        busy = bool(s.get("skill")) or bool(s.get("reflex"))
        if busy:
            self.last_busy = now
            return
        skills = {sk.name for sk in (getattr(mouth, "skills", None) or [])}

        # 1) noite chegando: abrigo, sem pensar (é instinto)
        night_soon = dusk(s.get("time_of_day")) and s.get("dimension", "minecraft:overworld") == "minecraft:overworld"
        if not night_soon:
            self.sheltering = False
        if self.config.shelter_at_night and night_soon and s.get("sky", True) and not self.sheltering:
            if "shelter" in skills:
                self.sheltering = True
                self.last_busy = now
                await self._run(
                    mouth,
                    instruction="Está anoitecendo e você vai cavar um abrigo para passar a noite. "
                    "Avise em uma frase curta.",
                    fallback="vou me esconder que tá ficando de noite",
                    action=("shelter", {}),
                )
                return

        # 2) promessa pendente
        pending = self.pending()
        if pending:
            promise = self.promises.popleft()
            self.last_busy = now
            await self._run(
                mouth,
                instruction=f"Agora você está livre e está COMEÇANDO o que tinha prometido: {promise.describe()}. "
                "Avise em uma frase curta que vai fazer agora (ainda não terminou).",
                fallback="bora fazer aquilo agora",
                action=(promise.skill, promise.args),
                partner=promise.requester,
            )
            return

        if self.budget_fraction() < self.config.min_budget_fraction:
            return
        needs = self.needs(state)

        # 3) fome sem comida
        if (
            needs.urgency.get("hunger", 0) >= 0.7
            and not needs.has_food
            and now - self.last_hungry_think >= self.config.hungry_think_every
        ):
            self.last_hungry_think = now
            self.last_think = now
            await self._run(
                mouth,
                instruction="Você está com fome e não tem comida. Decida o que fazer: caçar e assar carne, "
                "pedir comida para alguém, ou outra ideia sua. Se for agir, coloque a ação.",
            )
            return

        # 4) parada há muito tempo: vontade própria
        if now - self.last_busy >= self.config.think_every and now - self.last_think >= self.config.think_every:
            self.last_think = now
            self.last_busy = now
            await self._run(
                mouth,
                instruction="Você está parada há um tempo, sem ninguém pedindo nada. Decida sozinha o que fazer "
                "agora, do seu jeito, pensando no que você mais quer. Pode agir (coloque a ação), puxar assunto "
                "ou as duas coisas. Se não quiser fazer nada agora, responda só com um comentário curto.",
            )

    async def _run(
        self,
        mouth: Mouth,
        *,
        instruction: str,
        fallback: str | None = None,
        action: tuple[str, dict] | None = None,
        partner: str | None = None,
    ) -> None:
        self._thinking = True
        try:
            if action is not None:
                if self.start is not None:
                    await self.start(mouth, action[0], action[1], partner)
                else:
                    await mouth.start_skill(*action)
            await self.speak(
                mouth, instruction=instruction, fallback=fallback, partner=partner, allow_action=action is None
            )
        finally:
            self._thinking = False
