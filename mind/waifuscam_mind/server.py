"""Servidor WebSocket da Mente. Cada Corpo conectado vira uma Session.

As mensagens são despachadas por tipo para handlers registrados com
@MindServer.handles("tipo"). O que a personagem faz com os eventos fica no
Brain (pasta brain/), que pode ser trocado sem mexer aqui.
"""

from __future__ import annotations

import asyncio
import itertools
import logging
from collections.abc import Awaitable, Callable
from dataclasses import dataclass, field
from typing import Any

from websockets.asyncio.server import Server, ServerConnection, serve
from websockets.exceptions import ConnectionClosed

from . import protocol as p
from .brain import Brain, EchoBrain
from .character import Character
from .skins import SkinResolver

log = logging.getLogger(__name__)

Handler = Callable[["Session", p.Envelope], Awaitable[None]]

MAX_EVENTS_KEPT = 200


@dataclass(eq=False)  # comparada por identidade, para caber num set
class Session:
    """Um Corpo conectado e a personagem que ele está controlando."""

    conn: ServerConnection
    server: MindServer
    body: str | None = None
    body_version: str | None = None
    minecraft: str | None = None
    ready: bool = False  # true depois do welcome
    state: p.State | None = None
    events: list[p.Event] = field(default_factory=list)
    tasks: set[asyncio.Task] = field(default_factory=set)
    skills: list[p.SkillSpec] = field(default_factory=list)  # catálogo que veio no hello
    _skill_ids: itertools.count = field(default_factory=lambda: itertools.count(1))
    _query_ids: itertools.count = field(default_factory=lambda: itertools.count(1))
    pending: dict[str, asyncio.Future] = field(default_factory=dict)  # query id → resposta

    @property
    def character(self) -> Character:
        return self.server.character

    async def send(self, msg: p.Envelope) -> None:
        if msg.bot is None and self.ready:
            msg.bot = self.character.id
        await self.conn.send(p.dump(msg))

    async def say(self, text: str) -> None:
        log.info("%s diz: %s", self.character.name, text)
        await self.send(p.ChatSay(text=text))

    def skill(self, name: str) -> p.SkillSpec | None:
        return next((s for s in self.skills if s.name == name), None)

    async def start_skill(self, name: str, args: dict[str, Any] | None = None) -> str:
        """Pede para o corpo começar uma habilidade. O resultado chega em skill.done/skill.failed."""
        skill_id = f"mind-{next(self._skill_ids)}"
        log.info("%s vai fazer %s %s", self.character.name, name, args or "")
        await self.send(p.SkillStart(id=skill_id, skill=name, args=args or {}))
        return skill_id

    async def cancel_skill(self) -> None:
        await self.send(p.SkillCancel())

    async def query(self, name: str, args: dict[str, Any] | None = None, timeout: float = 3.0) -> dict | None:
        """Pergunta algo ao corpo e espera a resposta (None se não vier a tempo)."""
        qid = f"q-{next(self._query_ids)}"
        future: asyncio.Future = asyncio.get_running_loop().create_future()
        self.pending[qid] = future
        try:
            await self.send(p.Query(id=qid, query=name, args=args or {}))
            return await asyncio.wait_for(future, timeout)
        except TimeoutError:
            log.warning("O corpo não respondeu a pergunta %s %s", name, args)
            return None
        finally:
            self.pending.pop(qid, None)

    def run_in_background(self, coro) -> None:
        """Roda sem travar a leitura de mensagens (a IA leva segundos para responder)."""
        task = asyncio.create_task(coro)
        self.tasks.add(task)
        task.add_done_callback(self._task_done)

    def _task_done(self, task: asyncio.Task) -> None:
        self.tasks.discard(task)
        if not task.cancelled() and task.exception() is not None:
            log.error("Erro no cérebro", exc_info=task.exception())


@dataclass(frozen=True)
class EventMouth:
    """A boca que o cérebro recebe: fala pela sessão, mas vê o estado do momento
    em que o evento chegou. Assim uma fala antiga não é julgada com o estado de agora."""

    session: Session
    state: p.State | None

    async def say(self, text: str) -> None:
        await self.session.say(text)

    @property
    def skills(self) -> list[p.SkillSpec]:
        return self.session.skills

    async def start_skill(self, name: str, args: dict[str, Any] | None = None) -> str:
        return await self.session.start_skill(name, args)

    async def cancel_skill(self) -> None:
        await self.session.cancel_skill()

    async def send(self, msg: p.Envelope) -> None:
        await self.session.send(msg)

    async def query(self, name: str, args: dict[str, Any] | None = None) -> dict | None:
        return await self.session.query(name, args)


class MindServer:
    _handlers: dict[str, Handler] = {}

    def __init__(
        self,
        character: Character,
        host: str = "127.0.0.1",
        port: int = 8765,
        skins: SkinResolver | None = None,
        brain: Brain | None = None,
    ):
        self.character = character
        self.host = host
        self.port = port
        self.skins = skins
        self.brain: Brain = brain or EchoBrain(character)
        self.sessions: set[Session] = set()
        self._server: Server | None = None

    @classmethod
    def handles(cls, msg_type: str) -> Callable[[Handler], Handler]:
        def register(fn: Handler) -> Handler:
            cls._handlers[msg_type] = fn
            return fn

        return register

    # ---- ciclo de vida -----------------------------------------------------

    async def start(self) -> None:
        self._server = await serve(self._on_connection, self.host, self.port)
        # porta 0 = o sistema escolhe; útil em testes
        self.port = self._server.sockets[0].getsockname()[1] if self._server.sockets else self.port
        log.info(
            "Mente ouvindo em ws://%s:%s com a personagem %s (usuário %s)",
            self.host,
            self.port,
            self.character.name,
            self.character.username,
        )

    async def stop(self) -> None:
        if self._server:
            self._server.close()
            await self._server.wait_closed()

    async def serve_forever(self) -> None:
        await self.start()
        assert self._server is not None
        await self._server.serve_forever()

    # ---- conexão -----------------------------------------------------------

    async def _on_connection(self, conn: ServerConnection) -> None:
        session = Session(conn=conn, server=self)
        self.sessions.add(session)
        log.info("Corpo conectado de %s", conn.remote_address)
        try:
            async for raw in conn:
                try:
                    msg = p.parse(raw)
                except p.ProtocolError as e:
                    log.warning("Mensagem inválida: %s", e)
                    await session.send(p.ErrorMsg(message=str(e)))
                    continue
                handler = self._handlers.get(msg.type)
                if handler is None:
                    log.debug("Sem handler para %s", msg.type)
                    continue
                try:
                    await handler(session, msg)
                except ConnectionClosed:
                    raise
                except Exception:
                    # um bug num handler não pode derrubar a conexão com o jogo
                    log.exception("Erro tratando %s", msg.type)
        except ConnectionClosed:
            pass
        finally:
            for task in list(session.tasks):
                task.cancel()
            self.sessions.discard(session)
            log.info("Corpo desconectado (%s)", session.body or "?")


# ---- handlers --------------------------------------------------------------


@MindServer.handles("hello")
async def on_hello(session: Session, msg: p.Envelope) -> None:
    assert isinstance(msg, p.Hello)
    if msg.protocol != p.PROTOCOL_VERSION:
        await session.send(p.ErrorMsg(message=f"protocolo {msg.protocol} não suportado"))
        await session.conn.close()
        return
    session.body, session.body_version, session.minecraft = msg.body, msg.body_version, msg.minecraft
    session.skills = list(msg.skills)
    c = session.character
    skin = await session.server.skins.resolve(c) if session.server.skins else None
    session.ready = True
    await session.send(
        p.Welcome(
            bot=c.id,
            character=p.CharacterInfo(id=c.id, name=c.name, language=c.language),
            username=c.username,
            skin=p.SkinInfo(value=skin.value, signature=skin.signature) if skin else None,
        )
    )
    log.info(
        "Aperto de mão ok: corpo %s %s, Minecraft %s, habilidades: %s",
        msg.body,
        msg.body_version,
        msg.minecraft,
        ", ".join(s.name for s in msg.skills) or "nenhuma",
    )


@MindServer.handles("ping")
async def on_ping(session: Session, msg: p.Envelope) -> None:
    assert isinstance(msg, p.Ping)
    await session.send(p.Pong(id=msg.id))


@MindServer.handles("state")
async def on_state(session: Session, msg: p.Envelope) -> None:
    assert isinstance(msg, p.State)
    session.state = msg
    handler = getattr(session.server.brain, "on_state", None)
    if handler is not None and session.ready:
        session.run_in_background(handler(EventMouth(session, msg), msg))


@MindServer.handles("event")
async def on_event(session: Session, msg: p.Envelope) -> None:
    assert isinstance(msg, p.Event)
    session.events.append(msg)
    del session.events[:-MAX_EVENTS_KEPT]
    level = logging.DEBUG if msg.event in ("seen", "gone") else logging.INFO
    log.log(level, "Evento: %s", msg.model_dump(exclude={"v", "type", "bot"}))
    mouth = EventMouth(session, session.state)
    session.run_in_background(session.server.brain.on_event(mouth, msg))


@MindServer.handles("query.result")
async def on_query_result(session: Session, msg: p.Envelope) -> None:
    assert isinstance(msg, p.QueryResult)
    future = session.pending.get(msg.id)
    if future is not None and not future.done():
        future.set_result(msg.model_dump(exclude={"v", "type", "bot", "id"}))


@MindServer.handles("skill.done")
@MindServer.handles("skill.failed")
async def on_skill_ended(session: Session, msg: p.Envelope) -> None:
    assert isinstance(msg, p.SkillDone | p.SkillFailed)
    if isinstance(msg, p.SkillFailed):
        log.info("Habilidade %s (%s) não terminou: %s", msg.skill, msg.id, msg.reason)
    else:
        log.info("Habilidade %s (%s) terminou", msg.skill, msg.id)
    handler = getattr(session.server.brain, "on_skill_ended", None)
    if handler is not None:
        session.run_in_background(handler(EventMouth(session, session.state), msg))
