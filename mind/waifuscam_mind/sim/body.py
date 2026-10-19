"""Corpo simulado para testes e para brincar com a Mente sem o jogo.

python -m waifuscam_mind.sim.body          # conecta, faz hello e ping
python -m waifuscam_mind.sim.body --chat   # depois manda o que você digitar como chat
"""

from __future__ import annotations

import argparse
import asyncio
import time
import uuid

from websockets.asyncio.client import ClientConnection, connect

from .. import protocol as p


class SimulatedBody:
    def __init__(self, url: str = "ws://127.0.0.1:8765"):
        self.url = url
        self.conn: ClientConnection | None = None
        self.bot: str | None = None
        self.character_name: str | None = None

    async def __aenter__(self) -> SimulatedBody:
        self.conn = await connect(self.url)
        return self

    async def __aexit__(self, *exc) -> None:
        if self.conn:
            await self.conn.close()

    async def send(self, msg: p.Envelope) -> None:
        assert self.conn
        await self.conn.send(p.dump(msg))

    async def recv(self, timeout: float = 5) -> p.Envelope:
        assert self.conn
        return p.parse(await asyncio.wait_for(self.conn.recv(), timeout))

    async def handshake(self) -> p.Welcome:
        await self.send(p.Hello(protocol=p.PROTOCOL_VERSION, body="sim", body_version="0.1.0", minecraft="26.3"))
        msg = await self.recv()
        if not isinstance(msg, p.Welcome):
            raise RuntimeError(f"esperava welcome, recebi {msg.type}")
        self.bot, self.character_name = msg.bot, msg.character.name
        return msg

    async def ping(self) -> float:
        """Latência em ms."""
        ping_id = uuid.uuid4().hex
        start = time.perf_counter()
        await self.send(p.Ping(bot=self.bot, id=ping_id, t=int(time.time() * 1000)))
        msg = await self.recv()
        if not isinstance(msg, p.Pong) or msg.id != ping_id:
            raise RuntimeError(f"pong inesperado: {msg}")
        return (time.perf_counter() - start) * 1000

    async def chat(self, sender: str, text: str) -> None:
        await self.send(p.Event(bot=self.bot, event="chat", **{"from": sender, "text": text}))


async def _run(url: str, chat: bool) -> None:
    async with SimulatedBody(url) as body:
        welcome = await body.handshake()
        print(f"Conectado. Personagem: {welcome.character.name} ({welcome.character.language})")
        print(f"Ping: {await body.ping():.1f} ms")
        if chat:
            print("Digite mensagens de chat (Ctrl+C para sair).")
            while True:
                text = await asyncio.to_thread(input, "> ")
                await body.chat("Vry", text)


def main() -> None:
    parser = argparse.ArgumentParser(description="Corpo simulado do Waifu Scam")
    parser.add_argument("--url", default="ws://127.0.0.1:8765")
    parser.add_argument("--chat", action="store_true", help="enviar mensagens de chat digitadas")
    args = parser.parse_args()
    try:
        asyncio.run(_run(args.url, args.chat))
    except (KeyboardInterrupt, EOFError):
        pass


if __name__ == "__main__":
    main()
