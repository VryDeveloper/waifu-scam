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
from ..items import ItemNames
from ..llm import AllProvidersFailed, Budget, LLMClient
from ..memory import Memory
from ..plugins import Plugins
from .actions import describe_actions, resolve, resolve_later
from .base import Mouth
from .needs import describe_needs
from .recipes import describe_plans
from .vision import ALIASES, compass, describe_resources, describe_seeing, is_rare, label, nearest
from .will import Will, WillConfig

log = logging.getLogger(__name__)

HISTORY_LINES = 16
CONVERSATION_WINDOW = 45.0  # segundos em que a mesma pessoa ainda está "na conversa"
DEATH_COMMENT_COOLDOWN = 20.0
FAILURE_COMMENT_COOLDOWN = 15.0
DANGER_COMMENT_COOLDOWN = 90.0
FIND_COMMENT_COOLDOWN = 120.0  # achado raro (diamante, vila...): no máximo um comentário a cada 2 min
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
# Fala que promete ação ("vou", "tô indo", "coloquei"...): sem a linha da ação, é mentira.
COMMITS = re.compile(
    r"\b(vou|vô|tô indo|to indo|já (vou|faço|fiz|coloquei|peguei|botei|joguei|volto)|coloquei|botei|joguei|"
    r"deixa comigo|pode deixar|indo|chego|tô (fazendo|pegando|saindo|subindo|colocando|atacando)|bora)\b",
    re.IGNORECASE,
)
# Mensagem que pede alguma coisa no jogo (só aí vale conferir a promessa)
REQUEST = re.compile(
    r"\b(pega|pegue|faz|faça|fazer|me\s+d[áaê]|me\s+passa|vem|vai|coloca|bota|joga|larga|solta|quebra|ajuda|"
    r"bate|ataca|mata|segue|traz|guarda|abre|usa|interage|cava|minera|crafta|planta|sobe|sai|volta|dorme|come|"
    r"acende|fecha|aperta|puxa|empilha|deposita|busca|procura|caça)\b",
    re.IGNORECASE,
)
ACTION_LINE_ANY = re.compile(r"\[[^\]]*\]")
# "fica aqui", "me espera": ela não sai sozinha atrás dos objetivos dela por um tempo
STAY = re.compile(
    r"\b(fica\s+(aqui|a[íi]|parada|quieta)|me\s+espera|espera\s+(aqui|a[íi])|n[ãa]o\s+sai)\b", re.IGNORECASE
)
WORK_SKILLS = {"obtain", "craft", "collect", "give", "hunt", "deposit", "withdraw"}
PLAN_SKILLS = {"obtain", "craft", "give"}  # antes de começar, confere com o corpo se dá para fazer
DONE_COMMENT_COOLDOWN = 20.0
MEMORY_TAG = re.compile(r"\[\s*(lembrar|esquecer|nota)\s*:\s*([^\]]+)\]", re.IGNORECASE)
OUT_OF_BUDGET_NOTICE_EVERY = 600.0
MAX_LINE = 240
MAX_TYPING_DELAY = 2.5

CHAT_RULES = """
## Regras
- Responda só com o que vai digitar no chat: no máximo 2 frases curtas, sem aspas, sem seu nome na frente.
- Mensagens chegam como "Nome: texto"; linhas [entre colchetes] são coisas que aconteceram no jogo.
- Você só faz no jogo o que estiver em "Ações". Se não tiver ação para o pedido, diga que isso você ainda não
  consegue. A ação só começa quando você coloca a linha; nunca diga que já fez ou terminou: o corpo avisa.
- Você decide: topa (coloque a ação), adia ([depois: ...] e diga quando) ou recusa. Adie se tiver algo urgente.
- "Me dá/passa X" = give. Pedido grande ("faz uma picareta de pedra") é uma ação só.
- Memória: [lembrar: nome] guarda o lugar onde você está, [esquecer: nome] apaga, [nota: frase] guarda um fato.
- Você só enxerga o que está em "O que você está vendo agora". Do resto, só sabe o que lembra.
- "Do jogo" é a verdade sobre receitas e sobre o que dá para fazer agora: se dá, coloque a ação com o id que
  aparece lá; se não dá, diga em poucas palavras o que falta. Explique a receita só se perguntarem como faz.
- Não narre o que está fazendo, não fale do seu inventário e não explique como se faz as coisas (só se perguntarem).
  Se não tiver nada que valha dizer, responda só com uma reação curta.

## Experiência (use, não explique)
- Ferro: cavernas e montanhas, mais entre Y 0 e 64. Carvão: qualquer pedra exposta. Diamante: Y -58, no deepslate,
  cuidado com lava. Ouro: abaixo de Y 0 ou em badlands. Vila: comida, cama e baús. Lava + água = obsidiana.
- Noite: abrigo ou cama. Creeper: se afastar. Comida boa: carne assada e pão.
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
    seeing = s.get("seeing") if isinstance(s.get("seeing"), dict) else {}
    if seeing.get("biome"):
        lines.append(f"- Bioma: {seeing['biome']}" + (f", Y {s['y']:.0f}" if "y" in s else ""))
    sight = describe_seeing(s, me)
    if sight:
        lines.append(sight)
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
        half = len(line) // 2
        if len(line) > 10 and line[:half].strip() == line[half:].strip():
            line = line[:half].strip()  # modelo repetiu a frase grudada ("oi.oi.")
        if out and line == out[-1]:
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
        items: ItemNames | None = None,
    ):
        self.character = character
        self.plugins = plugins if plugins is not None else Plugins()
        self.items = items if items is not None else ItemNames()
        self.memory = memory if memory is not None else Memory(None)
        self.llm = llm
        self.budget = budget
        self._clock = clock
        self._sleep = sleep
        chat_cfg = character.section("chat")
        self.typing_speed = float(chat_cfg.get("typing_speed", 25)) or 25.0
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
        self._last_find_comment = float("-inf")
        self._world: str | None = None  # mundo do último state
        self._seeded = False  # o corpo já recebeu as lembranças de blocos deste mundo?
        self._state: p.State | None = None

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
                self._seeded = False  # corpo novo: manda as lembranças de novo
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
            case "seen":
                await self._on_seen(mouth, data)
            case "gone":
                if self._world is not None and all(k in data for k in ("block", "x", "y", "z")):
                    self.memory.gone(
                        self._world,
                        str(data["block"]),
                        int(data["x"]),
                        int(data["y"]),
                        int(data["z"]),
                        str(data.get("dimension", "minecraft:overworld")),
                    )
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
                if STAY.search(text) and (self.is_addressed(sender, text, mouth.state)):
                    self.will.hold(180)
                self.history.append({"role": "user", "content": f"{sender}: {text}"})
                self._seq += 1
                online = mouth.state is not None and mouth.state.online
                if online and self.is_addressed(sender, text, mouth.state):
                    plans = await self._plans_for(mouth, text)
                    await self._speak(mouth, partner=sender, seq=self._seq, plans=plans)

    async def on_state(self, mouth: Mouth, state: p.State) -> None:
        s = state.model_dump()
        self._state = state
        world = s.get("world")
        if isinstance(world, str) and (world != self._world or not self._seeded):
            self._world = world
            await self._seed_blocks(mouth)
        doing = (s.get("skill") or {}).get("doing") or s.get("reflex") or "parada"
        if doing != getattr(self, "_last_doing", None):
            self._last_doing = doing
            log.info("Corpo agora: %s", doing)
        if state.online:
            self.memory.seen([n for n in s.get("players", []) if n != self.character.username])
        await self._maybe_write_diary(s.get("time_of_day"))
        await self.will.on_state(mouth, state)
        self._sync_promises()

    async def _seed_blocks(self, mouth: Mouth) -> None:
        """Devolve ao corpo onde ela lembra de ter visto as coisas neste mundo."""
        send = getattr(mouth, "send", None)
        if send is None or self._world is None:
            return
        spots = self.memory.spots(self._world)
        blocks = [{k: sp[k] for k in ("block", "x", "y", "z", "dimension")} for sp in spots]
        await send(p.MemoryBlocks(world=self._world, blocks=blocks))
        self._seeded = True
        log.info("Mundo %s: %d lembranças de blocos mandadas ao corpo", self._world, len(blocks))

    async def _on_seen(self, mouth: Mouth, data: dict) -> None:
        """Ela viu algo que vale lembrar. Achado raro e novo dá vontade de comentar."""
        if self._world is None or not all(k in data for k in ("block", "x", "y", "z")):
            return
        block = str(data["block"])
        x, y, z = int(data["x"]), int(data["y"]), int(data["z"])
        new = self.memory.saw(self._world, block, x, y, z, str(data.get("dimension", "minecraft:overworld")))
        if not new or not is_rare(block) or not (mouth.state and mouth.state.online):
            return
        st = mouth.state.model_dump()
        where = ""
        if "x" in st:
            dist = ((x - st["x"]) ** 2 + (y - st["y"]) ** 2 + (z - st["z"]) ** 2) ** 0.5
            where = f" a uns {dist:.0f} blocos, para o {compass(x - st['x'], z - st['z'])}"
        self._note(f"você viu {label(block)}{where}")
        if self._clock() - self._last_find_comment < FIND_COMMENT_COOLDOWN:
            return
        self._last_find_comment = self._clock()
        busy = bool(st.get("skill"))
        await self._speak(
            mouth,
            instruction=f"Você acabou de ver {label(block)}{where}. Reaja do seu jeito, em poucas palavras"
            + ("." if busy else "; se quiser ir lá agora, coloque a ação."),
            allow_action=not busy,
        )

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
            text = await self.llm.chat(messages, max_tokens=300, background=True)
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
        failed = isinstance(msg, p.SkillFailed)
        self.will.skill_ended(msg.id, failed, failed and msg.cancelled)
        started = self._started.pop(msg.id, None)
        if isinstance(msg, p.SkillDone):
            if msg.skill != "stop":
                self._note(f"você terminou: {msg.skill}")
            # Só conta que terminou quando foi alguém que pediu; o que ela faz sozinha, faz em silêncio.
            if (
                started is not None
                and started[2] is not None
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
                    instruction=f"Você acabou de terminar o que {partner} pediu: {what}. Avise em poucas palavras, "
                    "sem listar itens nem dizer onde guardou.",
                    partner=partner,
                )
            return
        self._note(f"{msg.skill} parou: {msg.reason}")
        if msg.cancelled or not (mouth.state and mouth.state.online):
            return
        if started is None or started[2] is None:
            return  # objetivo dela mesma: tenta outra coisa sem fazer drama no chat
        if self._clock() - self._last_failure_comment < FAILURE_COMMENT_COOLDOWN:
            return
        self._last_failure_comment = self._clock()
        await self._speak(
            mouth,
            instruction=f"Você tentou fazer '{msg.skill}', mas não deu: {msg.reason}. Comente em uma frase curta.",
            partner=started[2],
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
        plans: dict[str, dict] | None = None,
    ) -> None:
        # Fala que não é resposta (comentário, vontade própria) nunca entra na frente da conversa:
        # se ela já está pensando em outra coisa, essa fala é deixada para lá.
        background = instruction is not None
        if background and self._lock.locked():
            return
        async with self._lock:
            # Se várias mensagens chegaram enquanto ela pensava, uma resposta cobre todas.
            if seq is not None and seq <= self._answered_seq:
                return
            if seq is not None:
                self._answered_seq = self._seq

            if not self.budget.try_spend():
                if not background:
                    await self._out_of_budget(mouth)
                return

            persona = self.character.persona()
            situation = describe_situation(mouth.state, self.character.username)
            feelings = describe_needs(self.will.needs(mouth.state))
            online = mouth.state.model_dump().get("players", []) if mouth.state else []
            remembered = self.memory.describe(online, self.character.username)
            if self._world is not None and mouth.state is not None:
                found = describe_resources(self.memory.spots(self._world), mouth.state.model_dump())
                if found:
                    remembered = f"{remembered}\n{found}" if remembered else found
            if remembered:
                feelings = f"{remembered}\n{feelings}"
            extra = self.plugins.prompt(mouth.state)
            if extra:
                feelings = f"{feelings}\n{extra}"
            promises = self.will.pending()
            if promises:
                feelings += "\n## Você prometeu fazer depois\n" + "\n".join(f"- {pr.describe()}" for pr in promises)
            if plans:
                feelings += "\n" + describe_plans(list(plans.values()), self.items)
            skills = list(getattr(mouth, "skills", None) or []) if allow_action else []
            actions = describe_actions(skills)
            system = f"{persona}\n\n{situation}\n{feelings}\n{CHAT_RULES}" + (f"\n{actions}\n" if actions else "")
            history = list(self.history)[-8:] if background else list(self.history)
            messages = [{"role": "system", "content": system}, *history]
            if instruction:
                messages.append({"role": "user", "content": f"[{instruction}]"})

            try:
                reply = await self.llm.chat(messages, max_tokens=400, background=background)
            except AllProvidersFailed as e:
                log.warning("Nenhum provedor de IA respondeu: %s", e)
                if fallback:
                    await mouth.say(fallback)
                return

            log.info("IA (%s): %s", "fundo" if background else "chat", reply.replace("\n", " | ")[:300])
            players = [n for n in (mouth.state.model_dump().get("players", []) if mouth.state else [])]
            text, action = resolve(reply, skills, partner, players)
            text, later = resolve_later(text, skills, partner, players)
            text = self._memory_tags(text, mouth, players)
            action = self._resolve_places(action, players)
            action = self._give_if_asked(action, partner, skills)
            later = self._resolve_places(later, players)
            action, later = self._item_ids(action), self._item_ids(later)
            checked = False
            if action is not None and action.skill in PLAN_SKILLS:
                why = await self._cannot(mouth, action, plans or {})
                if why:
                    # Ela ia prometer algo que o jogo diz que não dá agora: fala a verdade.
                    log.info("Não dá para %s %s: %s", action.skill, action.args, why)
                    text, action, checked = f"hmm, agora não vai dar: {why}", None, True
            if (
                not checked
                and action is None
                and later is None
                and allow_action
                and skills
                and not MEMORY_TAG.search(reply)
                and COMMITS.search(text)
            ):
                # Ela disse que vai fazer, mas não colocou a ação: confere antes de falar.
                text, action = await self._repair(text, partner, skills, players, instruction)
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

    async def _repair(
        self, text: str, partner: str | None, skills: list[p.SkillSpec], players: list[str], instruction: str | None
    ):
        """Pergunta (com o modelo de fundo, prompt curto) qual ação cumpre o que ela disse.
        Se não existir ação para aquilo, troca a fala por uma que não mente."""
        if not self.budget.try_spend():
            return text, None
        request = self._last_text.get(partner, "") if partner else (instruction or "")
        messages = [
            {
                "role": "system",
                "content": f"{describe_actions(skills)}\n\nVocê é {self.character.name}, uma player de Minecraft. "
                "Leia a sua resposta. Se ela promete fazer agora algo no jogo, responda SÓ com a linha [ação: ...] "
                "que faz isso. Se ela não promete nada no jogo (é só conversa), responda exatamente: OK. Se promete "
                "algo que nenhuma ação da lista faz, responda com uma frase curta e casual, em português, dizendo "
                "que isso você ainda não consegue fazer.",
            },
            {
                "role": "user",
                "content": (f"{partner} disse: {request}" if partner else f"Contexto: {request}")
                + f"\nSua resposta: {text.strip()}",
            },
        ]
        try:
            fixed = await self.llm.chat(messages, max_tokens=300, background=True)
        except AllProvidersFailed:
            return text, None
        _, action = resolve(fixed, skills, partner, players)
        if action is not None:
            log.info("Ação que ela tinha esquecido de colocar: %s %s", action.skill, action.args)
            return text, self._give_if_asked(self._resolve_places(action, players), partner, skills)
        honest = ACTION_LINE_ANY.sub("", fixed).strip()
        if not honest or honest.strip(" .!").upper() == "OK":
            return text, None  # era só conversa
        log.info("Ela prometeu algo que não sabe fazer; fala trocada por: %s", honest)
        return honest, None

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

    async def _plans_for(self, mouth: Mouth, text: str) -> dict[str, dict]:
        """Itens citados na mensagem → como consegui-los agora (perguntando ao corpo)."""
        query = getattr(mouth, "query", None)
        if query is None:
            return {}
        plans: dict[str, dict] = {}
        for item, count in self.items.find(text):
            result = await query("plan", {"item": item, "count": count})
            if result:
                plans[item] = {**result, "item": item, "count": count}
                log.info("Plano para %d %s: %s", count, item, result.get("failure") or result.get("steps"))
        return plans

    def _item_ids(self, action):
        """A IA às vezes escreve o item em português ("cama"): troca pelo id do jogo."""
        if action is None or "item" not in action.args:
            return action
        raw = str(action.args["item"])
        if raw.startswith("#") or raw in self.items.names:
            return action
        item = self.items.resolve(raw)
        return action if item is None else type(action)(action.skill, {**action.args, "item": item})

    async def _cannot(self, mouth: Mouth, action, plans: dict[str, dict]) -> str | None:
        """Motivo de não dar para conseguir o item agora, ou None se dá (ou se não deu para saber)."""
        item = str(action.args.get("item", ""))
        if not item or item.startswith("#"):
            return None
        result = plans.get(item)
        if result is None:
            query = getattr(mouth, "query", None)
            if query is None:
                return None
            result = await query("plan", {"item": item, "count": int(action.args.get("count") or 1)})
        if not result or result.get("ok", True):
            return None
        return str(result.get("failure") or "não sei como")

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
        if place is not None:
            return type(action)(action.skill, {"x": place.x, "y": place.y, "z": place.z})
        # "goto ferro", "goto vila": o lugar mais perto onde ela viu isso
        wanted = ALIASES.get(name.lower().strip())
        if wanted and self._world is not None and self._state is not None:
            spot = nearest(self.memory.spots(self._world), wanted, self._state.model_dump())
            if spot is not None:
                return type(action)(action.skill, {"x": spot["x"], "y": spot["y"], "z": spot["z"]})
        return action

    async def _out_of_budget(self, mouth: Mouth) -> None:
        now = self._clock()
        if now - self._last_budget_notice >= OUT_OF_BUDGET_NOTICE_EVERY:
            self._last_budget_notice = now
            log.warning("Orçamento diário de IA acabou (%d chamadas).", self.budget.daily_limit)
            await mouth.say("tô sem energia pra conversar hoje, amanhã a gente fala mais")
