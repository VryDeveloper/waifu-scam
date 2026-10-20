"""Ela conversa no chat com a persona, usando IA, e age quando decide agir.

Quando responde:
- alguém fala o nome dela;
- só existe um outro player online (então qualquer fala é com ela);
- a mesma pessoa continua a conversa logo depois de ela responder.

Também cumprimenta ao entrar no mundo e comenta quando morre.

Fase 3: junto com a fala, a IA pode escolher uma ação do catálogo do corpo
(seguir, ir até, parar). Ela decide se topa o pedido; o pedido não vira ação
sozinho. Se a ação falhar, ela comenta o motivo.
"""

from __future__ import annotations

import asyncio
import logging
import re
import time
from collections import deque
from collections.abc import Awaitable, Callable

from .. import protocol as p
from ..character import Character
from ..llm import AllProvidersFailed, Budget, LLMClient
from .actions import describe_actions, resolve
from .base import Mouth

log = logging.getLogger(__name__)

HISTORY_LINES = 30
CONVERSATION_WINDOW = 45.0  # segundos em que a mesma pessoa ainda está "na conversa"
DEATH_COMMENT_COOLDOWN = 20.0
FAILURE_COMMENT_COOLDOWN = 15.0
OUT_OF_BUDGET_NOTICE_EVERY = 600.0
MAX_LINE = 240
MAX_TYPING_DELAY = 4.0

CHAT_RULES = """
## Regras do chat do Minecraft
- Responda só com a mensagem que você vai digitar no chat: sem aspas, sem o seu nome na frente.
- No máximo 2 frases curtas (até uns 200 caracteres). O chat do jogo é pequeno.
- As mensagens chegam como "Nome: texto". Linhas entre colchetes são coisas que aconteceram no jogo, não falas.
- Você consegue andar, seguir, minerar, coletar, craftar, fundir, comer, usar baús e entregar itens (veja "Ações").
  Ainda não consegue lutar, dormir nem construir. Se pedirem isso, responda de um jeito natural, sem prometer.
- Pedido grande ("faz uma picareta de pedra") é uma ação só: o seu corpo sabe todos os passos.
"""


def time_of_day(ticks: int | None) -> str | None:
    if ticks is None:
        return None
    t = ticks % 24000
    if t < 1000 or t >= 23000:
        return "amanhecendo"
    if t < 6000:
        return "manhã"
    if t < 12000:
        return "tarde"
    if t < 13000:
        return "anoitecendo"
    return "noite"


def describe_situation(state: p.State | None, me: str) -> str:
    if state is None or not state.online:
        return "## Situação agora\n- Você ainda não está dentro do mundo."
    s = state.model_dump()
    lines = ["## Situação agora"]
    if "health" in s:
        lines.append(f"- Sua vida: {s['health']:.0f}/{s.get('max_health', 20):.0f}")
    if "food" in s:
        lines.append(f"- Sua fome: {s['food']}/20 (20 = satisfeita)")
    if part := time_of_day(s.get("time_of_day")):
        lines.append(f"- Horário no jogo: {part}")
    weather = {"rain": "chovendo", "thunder": "tempestade", "clear": "céu limpo"}.get(s.get("weather", ""))
    if weather:
        lines.append(f"- Clima: {weather}")
    dim = {
        "minecraft:overworld": "superfície",
        "minecraft:the_nether": "Nether",
        "minecraft:the_end": "End",
    }.get(s.get("dimension", ""))
    if dim:
        lines.append(f"- Dimensão: {dim}")
    others = [n for n in s.get("players", []) if n != me]
    if others:
        lines.append(f"- Online com você: {', '.join(others)}")
    inventory = s.get("inventory")
    if isinstance(inventory, dict):
        items = ", ".join(f"{n} {item}" for item, n in inventory.items())
        lines.append(f"- Seu inventário: {items}" if items else "- Seu inventário está vazio.")
    if s.get("holding"):
        lines.append(f"- Na mão: {s['holding']}")
    doing = (s.get("skill") or {}).get("doing") if isinstance(s.get("skill"), dict) else None
    lines.append(f"- Agora você está: {doing}" if doing else "- Agora você está parada, sem fazer nada.")
    return "\n".join(lines)


def clean_reply(text: str, names: list[str]) -> list[str]:
    """Deixa a resposta com cara de mensagem de chat: sem prefixo, aspas ou markdown."""
    out: list[str] = []
    for raw in text.splitlines():
        line = raw.strip()
        for name in filter(None, names):
            line = re.sub(rf"^\s*(<\s*{re.escape(name)}\s*>|{re.escape(name)}\s*:)\s*", "", line, flags=re.IGNORECASE)
        line = re.sub(r"[*_`#]+", "", line).strip()
        if len(line) >= 2 and line[0] == line[-1] and line[0] in "\"'“”":
            line = line[1:-1].strip()
        line = line.strip("“”").strip()
        if not line:
            continue
        if len(line) > MAX_LINE:
            line = line[:MAX_LINE].rsplit(" ", 1)[0] + "..."
        out.append(line)
        if len(out) == 2:
            break
    return out


class ChatBrain:
    def __init__(
        self,
        character: Character,
        llm: LLMClient,
        budget: Budget,
        *,
        clock: Callable[[], float] = time.monotonic,
        sleep: Callable[[float], Awaitable[None]] = asyncio.sleep,
    ):
        self.character = character
        self.llm = llm
        self.budget = budget
        self._clock = clock
        self._sleep = sleep
        chat_cfg = character.section("chat")
        self.typing_speed = float(chat_cfg.get("typing_speed", 12)) or 12.0
        self.greeting = str(chat_cfg.get("greeting", "oi! cheguei"))
        names = {character.name, character.username}
        self._names = sorted(names, key=len, reverse=True)
        self._mention = re.compile(r"\b(" + "|".join(re.escape(n) for n in names) + r")\b", re.IGNORECASE)

        self.history: deque[dict] = deque(maxlen=HISTORY_LINES)
        self._lock = asyncio.Lock()
        self._seq = 0  # cresce a cada mensagem de chat recebida
        self._answered_seq = 0  # última mensagem já respondida
        self._last_spoke_at = float("-inf")
        self._last_partner: str | None = None
        self._last_death_comment = float("-inf")
        self._last_failure_comment = float("-inf")
        self._last_budget_notice = float("-inf")

    # ---- quando responder -----------------------------------------------------

    def mentions_me(self, text: str) -> bool:
        return bool(self._mention.search(text))

    def is_addressed(self, sender: str, text: str, state: p.State | None) -> bool:
        if self.mentions_me(text):
            return True
        players = [n for n in (state.model_dump().get("players", []) if state else []) if n != self.character.username]
        if len(players) == 1:
            return True
        recent = self._clock() - self._last_spoke_at < CONVERSATION_WINDOW
        return recent and sender == self._last_partner

    # ---- eventos ----------------------------------------------------------------

    async def on_event(self, mouth: Mouth, event: p.Event) -> None:
        data = event.model_dump()
        match event.event:
            case "spawned":
                self._note("você entrou no mundo")
                await self._speak(
                    mouth,
                    instruction="Você acabou de entrar no mundo. Cumprimente quem está online em uma frase curta.",
                    fallback=self.greeting,
                )
            case "died":
                message = data.get("message") or "você morreu"
                self._note(f"você morreu: {message}")
                if self._clock() - self._last_death_comment >= DEATH_COMMENT_COOLDOWN:
                    self._last_death_comment = self._clock()
                    await self._speak(
                        mouth, instruction=f"Você acabou de morrer ({message}). Reaja em uma frase curta."
                    )
            case "respawned":
                self._note("você renasceu")
            case "despawned":
                self._note("você saiu do mundo")
            case "chat":
                sender, text = str(data.get("from", "?")), str(data.get("text", ""))
                self.history.append({"role": "user", "content": f"{sender}: {text}"})
                self._seq += 1
                online = mouth.state is not None and mouth.state.online
                if online and self.is_addressed(sender, text, mouth.state):
                    await self._speak(mouth, partner=sender, seq=self._seq)

    async def on_skill_ended(self, mouth: Mouth, msg: p.SkillDone | p.SkillFailed) -> None:
        if isinstance(msg, p.SkillDone):
            if msg.skill != "stop":
                self._note(f"você terminou: {msg.skill}")
            return
        self._note(f"{msg.skill} parou: {msg.reason}")
        if msg.cancelled or not (mouth.state and mouth.state.online):
            return
        if self._clock() - self._last_failure_comment < FAILURE_COMMENT_COOLDOWN:
            return
        self._last_failure_comment = self._clock()
        await self._speak(
            mouth,
            instruction=f"Você tentou fazer '{msg.skill}', mas não deu: {msg.reason}. Comente em uma frase curta.",
            partner=self._last_partner,
        )

    def _note(self, what: str) -> None:
        self.history.append({"role": "user", "content": f"[{what}]"})

    # ---- falar ------------------------------------------------------------------

    async def _speak(
        self,
        mouth: Mouth,
        *,
        instruction: str | None = None,
        fallback: str | None = None,
        partner: str | None = None,
        seq: int | None = None,
    ) -> None:
        async with self._lock:
            # Se várias mensagens chegaram enquanto ela pensava, uma resposta cobre todas.
            if seq is not None and seq <= self._answered_seq:
                return
            if seq is not None:
                self._answered_seq = self._seq

            if not self.budget.try_spend():
                await self._out_of_budget(mouth)
                return

            persona = self.character.persona()
            situation = describe_situation(mouth.state, self.character.username)
            skills = list(getattr(mouth, "skills", None) or [])
            actions = describe_actions(skills)
            system = f"{persona}\n\n{situation}\n{CHAT_RULES}" + (f"\n{actions}\n" if actions else "")
            messages = [{"role": "system", "content": system}, *self.history]
            if instruction:
                messages.append({"role": "user", "content": f"[{instruction}]"})

            try:
                reply = await self.llm.chat(messages, max_tokens=500)
            except AllProvidersFailed as e:
                log.warning("Nenhum provedor de IA respondeu: %s", e)
                if fallback:
                    await mouth.say(fallback)
                return

            players = [n for n in (mouth.state.model_dump().get("players", []) if mouth.state else [])]
            text, action = resolve(reply, skills, partner, players)
            lines = clean_reply(text, self._names)
            if not lines and fallback and action is None:
                lines = [fallback]
            if action is not None:
                # Começa a andar antes de digitar: parece mais vivo.
                await mouth.start_skill(action.skill, action.args)
            for line in lines:
                await self._sleep(min(MAX_TYPING_DELAY, len(line) / self.typing_speed))
                await mouth.say(line)
                self.history.append({"role": "assistant", "content": line})
            if action is not None:
                args = " ".join(str(v) for v in action.args.values())
                tag = f"[ação: {action.skill} {args}]".replace(" ]", "]")
                if lines:
                    self.history[-1] = {"role": "assistant", "content": f"{lines[-1]}\n{tag}"}
                else:
                    self.history.append({"role": "assistant", "content": tag})
            if lines or action is not None:
                self._last_spoke_at = self._clock()
                if partner:
                    self._last_partner = partner

    async def _out_of_budget(self, mouth: Mouth) -> None:
        now = self._clock()
        if now - self._last_budget_notice >= OUT_OF_BUDGET_NOTICE_EVERY:
            self._last_budget_notice = now
            log.warning("Orçamento diário de IA acabou (%d chamadas).", self.budget.daily_limit)
            await mouth.say("tô sem energia pra conversar hoje, amanhã a gente fala mais")
