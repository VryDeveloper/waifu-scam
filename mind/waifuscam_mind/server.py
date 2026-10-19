"""Servidor WebSocket da Mente. Cada Corpo conectado vira uma Session.

As mensagens são despachadas por tipo para handlers registrados com
@MindServer.handles("tipo"). As próximas fases adicionam handlers novos
(chat, eventos, habilidades) sem mexer no laço de conexão.
"""

from __future__ import annotations

import logging
from collections.abc import Awaitable, Callable
from dataclasses import dataclass, field

from websockets.asyncio.server import Server, ServerConnection, serve
from websockets.exceptions import ConnectionClosed

from . import protocol as p
from .character import Character

log = logging.getLogger(__name__)

Handler = Callable[["Session", p.Envelope], Awaitable[None]]


@dataclass(eq=False)  # comparada por identidade, para caber num set
class Session:
    """Um Corpo conectado e a personagem que ele está controlando."""

    conn: ServerConnection
    character: Character
    body: str | None = None
    body_version: str | None = None
    minecraft: str | None = None
    ready: bool = False  # true depois do welcome
    events: list[p.Envelope] = field(default_factory=list)

    async def send(self, msg: p.Envelope) -> None:
        if msg.bot is None and self.ready:
            msg.bot = self.character.id
        await self.conn.send(p.dump(msg))


class MindServer:
    _handlers: dict[str, Handler] = {}

    def __init__(self, character: Character, host: str = "127.0.0.1", port: int = 8765):
        self.character = character
        self.host = host
        self.port = port
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
        log.info("Mente ouvindo em ws://%s:%s com a personagem %s", self.host, self.port, self.character.name)

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
        session = Session(conn=conn, character=self.character)
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
                await handler(session, msg)
        except ConnectionClosed:
            pass
        finally:
            self.sessions.discard(session)
            log.info("Corpo desconectado (%s)", session.body or "?")


# ---- handlers da fase 0 ----------------------------------------------------


@MindServer.handles("hello")
async def on_hello(session: Session, msg: p.Envelope) -> None:
    assert isinstance(msg, p.Hello)
    if msg.protocol != p.PROTOCOL_VERSION:
        await session.send(p.ErrorMsg(message=f"protocolo {msg.protocol} não suportado"))
        await session.conn.close()
        return
    session.body, session.body_version, session.minecraft = msg.body, msg.body_version, msg.minecraft
    c = session.character
    session.ready = True
    await session.send(p.Welcome(bot=c.id, character=p.CharacterInfo(id=c.id, name=c.name, language=c.language)))
    log.info("Aperto de mão ok: corpo %s %s, Minecraft %s", msg.body, msg.body_version, msg.minecraft)


@MindServer.handles("ping")
async def on_ping(session: Session, msg: p.Envelope) -> None:
    assert isinstance(msg, p.Ping)
    await session.send(p.Pong(id=msg.id))


@MindServer.handles("event")
async def on_event(session: Session, msg: p.Envelope) -> None:
    # Fase 0: só registra. A fase 2 liga isto ao agente de chat.
    session.events.append(msg)
    log.info("Evento: %s", msg.model_dump(exclude={"v", "type"}))
