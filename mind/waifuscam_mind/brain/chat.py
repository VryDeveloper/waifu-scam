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
from ..memory import Memory
from ..plugins import Plugins
from .actions import describe_actions, resolve, resolve_later
from .base import Mouth
from .needs import describe_needs
from .will import Will, WillConfig

log = logging.getLogger(__name__)

HISTORY_LINES = 30
CONVERSATION_WINDOW = 45.0  # segundos em que a mesma pessoa ainda está "na conversa"
DEATH_COMMENT_COOLDOWN = 20.0
FAILURE_COMMENT_COOLDOWN = 15.0
DANGER_COMMENT_COOLDOWN = 90.0
DIARY_MIN_INTERVAL = 600.0  # segundos reais entre entradas do diário
DIARY_MIN_LINES = 6  # falas novas desde a última entrada
# "aqui é a nossa base", "lembra desse lugar como mina": guarda mesmo se a IA esquecer a tag
PLACE_SAID = re.compile(
    r"\b(?:aqui\s+(?:é|e|eh|vai\s+ser|fica)|lembr[ae]\s+(?:desse|deste|daqui|desse\s+lugar)\s+como|"
    r"esse\s+lugar\s+(?:é|e)|chama\s+(?:aqui|esse\s+lugar)\s+de)\s+(?:(?:a|o|nossa|nosso|minha|meu|tua|sua)\s+)*"
    r"([a-zà-ú][\wà-ú]*(?:\s+de\s+[\wà-ú]+)?)",
    re.IGNORECASE,
)
# "me dá", "me passa": é para entregar, mesmo se a IA escolher só conseguir o item
GIVE_ASKED = re.compile(r"\bme\s+(?:d[áaê]|passa|empresta|entrega|arruma|traz)\b", re.IGNORECASE)
WORK_SKILLS = {"obtain", "craft", "collect", "give", "hunt", "deposit", "withdraw"}
DONE_COMMENT_COOLDOWN = 20.0
MEMORY_TAG = re.compile(r"\[\s*(lembrar|esquecer|nota)\s*:\s*([^\]]+)\]", re.IGNORECASE)
OUT_OF_BUDGET_NOTICE_EVERY = 600.0
MAX_LINE = 240
MAX_TYPING_DELAY = 4.0

CHAT_RULES = """
## Regras do chat do Minecraft
- Responda só com a mensagem que você vai digitar no chat: sem aspas, sem o seu nome na frente.
- No máximo 2 frases curtas (até uns 200 caracteres). O chat do jogo é pequeno.
- As mensagens chegam como "Nome: texto". Linhas entre colchetes são coisas que aconteceram no jogo, não falas.
- Você consegue andar, seguir, minerar, coletar, craftar, fundir, comer, usar baús e entregar itens (veja "Ações").
  Também luta, caça, dorme na cama e se abriga à noite. Ainda não constrói nem usa arco: se pedirem isso, responda
  de um jeito natural, sem prometer.
- Pedido grande ("faz uma picareta de pedra") é uma ação só: o seu corpo sabe todos os passos.
- Se pedirem para você DAR ou PASSAR algo ("me dá 4 tochas"), use give: ele já consegue o item se faltar.
- A ação só começa quando você a coloca. Nunca diga que já terminou ou que já entregou: o seu corpo avisa quando
  acabar, e aí você conta.
- Memória: dizer "vou lembrar" não guarda nada. Para guardar o lugar onde você está, coloque [lembrar: nome]
  (ex.: "aqui é a nossa base" → [lembrar: base]); para esquecer, [esquecer: nome]. Para guardar algo
  importante sobre alguém ou sobre o mundo, [nota: frase curta]. Use pouco, só para o que vale lembrar amanhã.
- Você decide os pedidos: topa (coloque a ação), adia (coloque [depois: ...] e diga quando) ou recusa, do seu jeito.
  Adie quando tiver algo mais urgente (fome, noite, perigo). Seu corpo foge, revida e come sozinho por reflexo.
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
    if s.get("reflex"):
        lines.append(f"- Por reflexo, agora: {s['reflex']}")
    if "sky" in s:
        lines.append(
            "- Você está ao ar livre." if s["sky"] else "- Você está coberta (dentro de algo ou embaixo da terra)."
        )
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
        memory: Memory | None = None,
        plugins: Plugins | None = None,
    ):
        self.character = character
        self.plugins = plugins if plugins is not None else Plugins()
        self.memory = memory if memory is not None else Memory(None)
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
        self._last_danger_comment = float("-inf")
        self._last_done_comment = float("-inf")
        self._started: dict[str, tuple[str, dict, str | None]] = {}  # id → (habilidade, args, para quem)
        limit = max(1, budget.daily_limit)
        self.will = Will(
            weights={k: float(v) for k, v in character.section("needs").items()},
            config=WillConfig.from_section(character.section("will")),
            speak=self._speak,
            budget_fraction=lambda: self.budget.remaining / limit,
            clock=clock,
            start=self._start,
        )
        # Continua de onde parou: últimas falas e promessas da sessão anterior.
        self.history.extend(self.memory.history[-HISTORY_LINES:])
        for pr in self.memory.promises:
            self.will.promise(pr.get("skill", ""), pr.get("args") or {}, pr.get("requester"), pr.get("said", ""))
        self._last_day_time: int | None = None
        self._last_diary = self._clock()
        self._lines_since_diary = 0
        self._last_text: dict[str, str] = {}  # última fala de cada player
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
        await self.plugins.on_event(mouth, event)
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
                st = mouth.state.model_dump() if mouth.state else {}
                if "x" in st:
                    self.memory.remember_place(
                        "ultima_morte", st["x"], st["y"], st["z"], st.get("dimension", ""), "onde seus itens caíram"
                    )
                if self._clock() - self._last_death_comment >= DEATH_COMMENT_COOLDOWN:
                    self._last_death_comment = self._clock()
                    await self._speak(
                        mouth, instruction=f"Você acabou de morrer ({message}). Reaja em uma frase curta."
                    )
            case "remember_place":
                name = str(data.get("name", "")).strip()
                if name and all(k in data for k in ("x", "y", "z")):
                    key = self.memory.remember_place(
                        name, data["x"], data["y"], data["z"], str(data.get("dimension", ""))
                    )
                    self._note(f"{data.get('from', 'alguém')} marcou um lugar para você lembrar: {key}")
            case "respawned":
                self._note("você renasceu")
            case "despawned":
                self._note("você saiu do mundo")
            case "danger":
                kind = str(data.get("message") or "um monstro")
                self._note(f"perigo: {kind} perto de você")
                if self._clock() - self._last_danger_comment >= DANGER_COMMENT_COOLDOWN and mouth.state:
                    self._last_danger_comment = self._clock()
                    await self._speak(
                        mouth,
                        instruction=f"Um {kind} apareceu perto de você. Reaja em poucas palavras.",
                        allow_action=False,
                    )
            case "chat":
                sender, text = str(data.get("from", "?")), str(data.get("text", ""))
                self.will.heard_chat()
                self.memory.talked(sender)
                self._lines_since_diary += 1
                self._place_from_chat(text, mouth)
                self._last_text[sender] = text
                self.history.append({"role": "user", "content": f"{sender}: {text}"})
                self._seq += 1
                online = mouth.state is not None and mouth.state.online
                if online and self.is_addressed(sender, text, mouth.state):
                    await self._speak(mouth, partner=sender, seq=self._seq)

    async def on_state(self, mouth: Mouth, state: p.State) -> None:
        s = state.model_dump()
        if state.online:
            self.memory.seen([n for n in s.get("players", []) if n != self.character.username])
        await self._maybe_write_diary(s.get("time_of_day"))
        await self.will.on_state(mouth, state)
        self._sync_promises()

    def _sync_promises(self) -> None:
        self.memory.set_promises(
            [
                {"skill": pr.skill, "args": pr.args, "requester": pr.requester, "said": pr.said}
                for pr in self.will.promises
            ]
        )

    async def _maybe_write_diary(self, day_time: int | None) -> None:
        """Ao amanhecer no jogo (e com coisa nova para contar), ela escreve no diário."""
        previous, self._last_day_time = self._last_day_time, day_time
        if day_time is None or previous is None:
            return
        morning = previous >= 22000 and day_time < 2000
        if not morning or self._lines_since_diary < DIARY_MIN_LINES:
            return
        if self._clock() - self._last_diary < DIARY_MIN_INTERVAL or not self.budget.try_spend():
            return
        self._last_diary = self._clock()
        self._lines_since_diary = 0
        self._last_text: dict[str, str] = {}  # última fala de cada player
        messages = [
            {"role": "system", "content": self.character.persona()},
            *self.history,
            {
                "role": "user",
                "content": "[Amanheceu. Escreva no seu diário, em primeira pessoa e em 2 ou 3 frases curtas, o que "
                "aconteceu de importante desde a última vez: com quem você esteve, o que fez, o que prometeu. "
                "Sem markdown, sem data.]",
            },
        ]
        try:
            text = await self.llm.chat(messages, max_tokens=300)
        except AllProvidersFailed:
            return
        entry = " ".join(clean_reply(text, self._names) or [text.strip()])[:400]
        self.memory.write_diary(entry)
        log.info("Diário: %s", entry)

    async def _start(self, mouth: Mouth, skill: str, args: dict, partner: str | None = None) -> str:
        skill_id = await mouth.start_skill(skill, args)
        self._started[skill_id] = (skill, args, partner)
        while len(self._started) > 50:
            self._started.pop(next(iter(self._started)))
        return skill_id

    def _place_from_chat(self, text: str, mouth: Mouth) -> None:
        match = PLACE_SAID.search(text)
        st = mouth.state.model_dump() if mouth.state else {}
        if match and "x" in st:
            name = match.group(1).strip()
            if name.lower() not in {"que", "onde", "isso", "perigoso", "bom", "legal", "longe", "perto"}:
                self.memory.remember_place(name, st["x"], st["y"], st["z"], st.get("dimension", ""))

    async def on_skill_ended(self, mouth: Mouth, msg: p.SkillDone | p.SkillFailed) -> None:
        started = self._started.pop(msg.id, None)
        if isinstance(msg, p.SkillDone):
            if msg.skill != "stop":
                self._note(f"você terminou: {msg.skill}")
            if (
                started is not None
                and msg.skill in WORK_SKILLS
                and mouth.state is not None
                and mouth.state.online
                and self._clock() - self._last_done_comment >= DONE_COMMENT_COOLDOWN
            ):
                self._last_done_comment = self._clock()
                skill, args, partner = started
                what = f"{skill} {' '.join(str(v) for v in args.values())}".strip()
                await self._speak(
                    mouth,
                    instruction=f"Você acabou de terminar: {what}. Conte em poucas palavras"
                    + (f" para {partner}." if partner else "."),
                    partner=partner,
                )
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
        allow_action: bool = True,
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
            feelings = describe_needs(self.will.needs(mouth.state))
            online = mouth.state.model_dump().get("players", []) if mouth.state else []
            remembered = self.memory.describe(online, self.character.username)
            if remembered:
                feelings = f"{remembered}\n{feelings}"
            extra = self.plugins.prompt(mouth.state)
            if extra:
                feelings = f"{feelings}\n{extra}"
            promises = self.will.pending()
            if promises:
                feelings += "\n## Você prometeu fazer depois\n" + "\n".join(f"- {pr.describe()}" for pr in promises)
            skills = list(getattr(mouth, "skills", None) or []) if allow_action else []
            actions = describe_actions(skills)
            system = f"{persona}\n\n{situation}\n{feelings}\n{CHAT_RULES}" + (f"\n{actions}\n" if actions else "")
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
            text, later = resolve_later(text, skills, partner, players)
            text = self._memory_tags(text, mouth, players)
            action = self._resolve_places(action, players)
            action = self._give_if_asked(action, partner, skills)
            later = self._resolve_places(later, players)
            if later is not None:
                self.will.promise(later.skill, later.args, partner)
                self._sync_promises()
            lines = [said for line in clean_reply(text, self._names) if (said := self.plugins.on_say(line))]
            if not lines and fallback and action is None:
                lines = [fallback]
            if action is not None:
                # Começa a andar antes de digitar: parece mais vivo.
                await self._start(mouth, action.skill, action.args, partner)
            for line in lines:
                await self._sleep(min(MAX_TYPING_DELAY, len(line) / self.typing_speed))
                await mouth.say(line)
                self.history.append({"role": "assistant", "content": line})
            for kind, chosen in (("ação", action), ("depois", later)):
                if chosen is None:
                    continue
                args = " ".join(str(v) for v in chosen.args.values())
                tag = f"[{kind}: {chosen.skill} {args}]".replace(" ]", "]")
                if self.history and self.history[-1]["role"] == "assistant" and lines:
                    last = self.history[-1]["content"]
                    self.history[-1] = {"role": "assistant", "content": f"{last}\n{tag}"}
                else:
                    self.history.append({"role": "assistant", "content": tag})
            self._lines_since_diary += len(lines)
            self.memory.set_history(list(self.history))
            if lines or action is not None:
                self._last_spoke_at = self._clock()
                if partner:
                    self._last_partner = partner

    def _memory_tags(self, text: str, mouth: Mouth, players: list[str]) -> str:
        """Tira as tags de memória da fala e grava o que elas pedem."""
        st = mouth.state.model_dump() if mouth.state else {}
        for kind, value in MEMORY_TAG.findall(text):
            kind, value = kind.lower(), value.strip()
            if kind == "lembrar" and "x" in st:
                self.memory.remember_place(value, st["x"], st["y"], st["z"], st.get("dimension", ""))
            elif kind == "esquecer":
                self.memory.forget_place(value)
            elif kind == "nota":
                about = [n for n in self.memory.people if re.search(rf"\b{re.escape(n)}\b", value, re.IGNORECASE)]
                self.memory.note(value, about)
        return MEMORY_TAG.sub("", text)

    def _give_if_asked(self, action, partner: str | None, skills: list[p.SkillSpec]):
        """Pediram "me dá X" e a IA escolheu obtain/craft: troca por give (que já consegue o item antes)."""
        if action is None or partner is None or action.skill not in ("obtain", "craft"):
            return action
        if not GIVE_ASKED.search(self._last_text.get(partner, "")) or not any(sk.name == "give" for sk in skills):
            return action
        return type(action)("give", {"player": partner, **action.args})

    def _resolve_places(self, action, players: list[str]):
        """[ação: goto base] → coordenadas da base, se "base" não for um player online."""
        if action is None or action.skill != "goto" or "player" not in action.args:
            return action
        name = str(action.args["player"])
        if name.lower() in {n.lower() for n in players}:
            return action
        place = self.memory.place(name)
        if place is None:
            return action
        return type(action)(action.skill, {"x": place.x, "y": place.y, "z": place.z})

    async def _out_of_budget(self, mouth: Mouth) -> None:
        now = self._clock()
        if now - self._last_budget_notice >= OUT_OF_BUDGET_NOTICE_EVERY:
            self._last_budget_notice = now
            log.warning("Orçamento diário de IA acabou (%d chamadas).", self.budget.daily_limit)
            await mouth.say("tô sem energia pra conversar hoje, amanhã a gente fala mais")
