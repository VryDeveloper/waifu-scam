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
from .goals import Goal, choose
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
    spontaneous: bool = True  # False: só faz o que pedirem (sem objetivos próprios nem ideias soltas)
    think_every: float = 150.0  # segundos parada até pensar livremente (conversa, ideia nova)
    shelter_at_night: bool = True
    hungry_think_every: float = 180.0
    min_budget_fraction: float = 0.15  # abaixo disso, não gasta IA pensando sozinha
    autopilot: bool = True  # seguir a progressão de objetivos quando estiver livre
    goal_idle: float = 15.0  # segundos parada até pegar o próximo objetivo
    announce_every: float = 900.0  # no máximo um comentário sobre o que ela decidiu fazer a cada 15 min

    @classmethod
    def from_section(cls, section: dict) -> WillConfig:
        cfg = cls()
        for key in ("enabled", "shelter_at_night", "autopilot", "spontaneous"):
            if key in section:
                setattr(cfg, key, bool(section[key]))
        for key in ("think_every", "hungry_think_every", "min_budget_fraction", "goal_idle", "announce_every"):
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
    last_chat: float = field(default=float("-inf"))
    last_hungry_think: float = field(default=float("-inf"))
    sheltering: bool = False
    shelter_retry_at: float = field(default=float("-inf"))
    last_announce: float = field(default=float("-inf"))
    _thinking: bool = False
    _goal_by_id: dict[str, Goal] = field(default_factory=dict)
    _blocked_until: dict[str, float] = field(default_factory=dict)
    _shelter_id: str | None = None
    _hold_until: float = field(default=float("-inf"))

    def __post_init__(self) -> None:
        now = self.clock()
        self.last_busy = now
        self.last_social = now

    # ---- o que os outros avisam --------------------------------------------------

    def hold(self, seconds: float) -> None:
        """Pediram para ela ficar/esperar: nada de sair fazendo objetivo próprio por um tempo."""
        self._hold_until = self.clock() + seconds
        log.info("Vontade própria em espera por %.0f s (pediram para ela ficar)", seconds)

    def heard_chat(self) -> None:
        self.last_chat = self.clock()
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

    def skill_ended(self, skill_id: str, failed: bool, cancelled: bool) -> None:
        """O corpo terminou algo que a Vontade começou: objetivo que falhou fica de lado por um tempo."""
        goal = self._goal_by_id.pop(skill_id, None)
        if goal is not None and failed and not cancelled:
            self._blocked_until[goal.key] = self.clock() + 600
            log.info("Objetivo %s falhou; tento outra coisa por 10 min", goal.key)
        elif goal is not None and not failed and goal.cooldown > 0:
            self._blocked_until[goal.key] = self.clock() + goal.cooldown  # cumprido: só daqui a pouco de novo
        if skill_id == self._shelter_id:
            self._shelter_id = None
            if failed and not cancelled:
                self.sheltering = False
                self.shelter_retry_at = self.clock() + 45  # tenta de novo daqui a pouco, em outro lugar
        self.last_busy = self.clock()

    def blocked(self) -> set[str]:
        now = self.clock()
        return {k for k, until in self._blocked_until.items() if until > now}

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
        if (
            self.config.shelter_at_night
            and night_soon
            and s.get("sky", True)
            and not self.sheltering
            and now >= self.shelter_retry_at
        ):
            if "shelter" in skills:
                self.sheltering = True
                self.last_busy = now
                self._shelter_id = await self._run(
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

        if not self.config.spontaneous:
            return  # comportamento espontâneo desligado: só pedidos, promessas e o abrigo da noite
        if self.budget_fraction() < self.config.min_budget_fraction:
            return
        if now - self.last_chat < 15:
            return  # acabaram de falar com ela: espera um pouco antes de sair fazendo outra coisa
        needs = self.needs(state)

        # 3) objetivo próprio: a próxima coisa da progressão (instinto de player)
        if (
            self.config.autopilot
            and now - self.last_busy >= self.config.goal_idle
            and not night_soon
            and now >= self._hold_until
        ):
            goal = choose(s, self.weights, self.blocked(), skills)
            if goal is not None:
                self.last_busy = now
                speak = now - self.last_announce >= self.config.announce_every
                if speak:
                    self.last_announce = now
                skill_id = await self._run(
                    mouth,
                    instruction=f"Ninguém pediu nada e você decidiu sozinha: {goal.why}. Se quiser, solte um "
                    "comentário curtinho e natural (não explique o que vai fazer).",
                    action=(goal.skill, goal.args),
                    quiet=not speak,
                )
                if skill_id:
                    self._goal_by_id[skill_id] = goal
                log.info("Vontade própria: %s", goal.key)
                return

        # 4) fome sem comida (quando nenhum objetivo de comida deu certo)
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

        # 5) parada há muito tempo: vontade própria livre (a IA decide)
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
        quiet: bool = False,
    ) -> str | None:
        """Começa a ação (se houver) e fala sobre ela. Devolve o id da habilidade começada."""
        self._thinking = True
        skill_id: str | None = None
        try:
            if action is not None:
                if self.start is not None:
                    skill_id = await self.start(mouth, action[0], action[1], partner)
                else:
                    skill_id = await mouth.start_skill(*action)
            if not quiet:
                await self.speak(
                    mouth, instruction=instruction, fallback=fallback, partner=partner, allow_action=action is None
                )
        finally:
            self._thinking = False
        return skill_id
