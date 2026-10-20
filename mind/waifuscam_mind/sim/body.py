"""Corpo simulado para testes e para brincar com a Mente sem o jogo.

python -m waifuscam_mind.sim.body          # conecta, faz hello e ping
python -m waifuscam_mind.sim.body --chat   # depois manda o que você digitar como chat

No modo --chat, as ações que ela escolhe aparecem como [ação: ...]. "goto" e
"stop" terminam na hora; "follow" fica rodando até outra ação.
"""

from __future__ import annotations

import argparse
import asyncio
import time
import uuid

from websockets.asyncio.client import ClientConnection, connect

from .. import protocol as p

# Mesmo catálogo que o mod manda no hello (SkillRegistry.catalog()).
SIM_SKILLS = [
    p.SkillSpec(
        name="follow",
        description="Seguir um player, ficando perto dele até alguém pedir para parar.",
        params={"type": "object", "properties": {"player": {"type": "string"}}, "required": ["player"]},
    ),
    p.SkillSpec(
        name="goto",
        description="Ir até um player (ex.: quando pedem 'vem aqui') ou até coordenadas x, y, z, e parar lá.",
        params={
            "type": "object",
            "properties": {
                "player": {"type": "string"},
                "x": {"type": "number"},
                "y": {"type": "number"},
                "z": {"type": "number"},
            },
        },
    ),
    p.SkillSpec(
        name="obtain",
        description="Ficar com pelo menos `count` do item no inventário, fazendo o que precisar.",
        params={
            "type": "object",
            "properties": {"item": {"type": "string"}, "count": {"type": "integer"}},
            "required": ["item"],
        },
    ),
    p.SkillSpec(
        name="give",
        description="Entregar itens para um player.",
        params={
            "type": "object",
            "properties": {"player": {"type": "string"}, "item": {"type": "string"}, "count": {"type": "integer"}},
            "required": ["player", "item"],
        },
    ),
    p.SkillSpec(name="eat", description="Comer.", params={"type": "object", "properties": {}}),
    p.SkillSpec(name="stop", description="Parar o que está fazendo.", params={"type": "object", "properties": {}}),
]


class SimulatedBody:
    def __init__(self, url: str = "ws://127.0.0.1:8765", skills: list[p.SkillSpec] | None = None):
        self.url = url
        self.skills = SIM_SKILLS if skills is None else skills
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
        await self.send(
            p.Hello(protocol=p.PROTOCOL_VERSION, body="sim", body_version="0.1.0", minecraft="26.3", skills=self.skills)
        )
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

    async def event(self, name: str, **fields) -> None:
        await self.send(p.Event(bot=self.bot, event=name, **fields))

    async def state(self, online: bool = True, **fields) -> None:
        await self.send(p.State(bot=self.bot, online=online, **fields))

    async def spawn(self) -> None:
        """Faz de conta que a personagem entrou no mundo, como o mod faria."""
        await self.state(online=True, alive=True, health=20.0, max_health=20.0, food=20)
        await self.event("spawned")

    async def next_say(self, timeout: float = 5) -> str:
        """Espera a próxima fala da personagem, ignorando outras mensagens."""
        return (await self.next_of(p.ChatSay, timeout)).text

    async def next_of(self, kind: type[p.Envelope], timeout: float = 5):
        """Espera a próxima mensagem de um tipo, ignorando as outras."""
        while True:
            msg = await self.recv(timeout)
            if isinstance(msg, kind):
                return msg

    async def skill_ended(self, skill_id: str, skill: str, reason: str | None = None, cancelled: bool = False):
        if reason is None:
            await self.send(p.SkillDone(bot=self.bot, id=skill_id, skill=skill))
        else:
            await self.send(p.SkillFailed(bot=self.bot, id=skill_id, skill=skill, reason=reason, cancelled=cancelled))


async def _print_says(body: SimulatedBody, name: str) -> None:
    running: tuple[str, str] | None = None
    while True:
        try:
            msg = await body.recv(timeout=3600)
        except TimeoutError:
            continue
        if isinstance(msg, p.ChatSay):
            print(f"\n<{name}> {msg.text}\n> ", end="", flush=True)
        elif isinstance(msg, p.SkillStart):
            print(f"\n[ação: {msg.skill} {msg.args}]\n> ", end="", flush=True)
            if running:
                await body.skill_ended(*running, reason="interrompida por outra ação", cancelled=True)
                running = None
            if msg.skill == "follow":
                running = (msg.id, msg.skill)
            else:
                await body.skill_ended(msg.id, msg.skill)


async def _run(url: str, chat: bool) -> None:
    async with SimulatedBody(url) as body:
        welcome = await body.handshake()
        skin = "personalizada" if welcome.skin else "padrão"
        print(f"Conectado. Personagem: {welcome.character.name} (usuário {welcome.username}, skin {skin})")
        print(f"Ping: {await body.ping():.1f} ms")
        if chat:
            await body.spawn()
            printer = asyncio.create_task(_print_says(body, welcome.username))
            print("Ela entrou no mundo simulado. Digite mensagens de chat (Ctrl+C para sair).")
            try:
                while True:
                    text = await asyncio.to_thread(input, "> ")
                    await body.chat("Vry", text)
            finally:
                printer.cancel()


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
