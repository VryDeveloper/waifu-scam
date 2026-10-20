"""Ponta a ponta: Mente real + Corpo simulado conversando pelo WebSocket."""

import asyncio

import pytest

from waifuscam_mind import protocol as p
from waifuscam_mind.character import load_character
from waifuscam_mind.server import MindServer
from waifuscam_mind.sim.body import SimulatedBody


@pytest.fixture
async def server(characters_dir):
    srv = MindServer(load_character(characters_dir, "lumi"), port=0)
    await srv.start()
    yield srv
    await srv.stop()


def url(srv: MindServer) -> str:
    return f"ws://127.0.0.1:{srv.port}"


async def test_handshake_returns_character(server):
    async with SimulatedBody(url(server)) as body:
        welcome = await body.handshake()
    assert welcome.bot == "lumi"
    assert welcome.character.name == "Lumi"


async def test_ping_pong(server):
    async with SimulatedBody(url(server)) as body:
        await body.handshake()
        assert await body.ping() < 1000


async def test_chat_event_reaches_session(server):
    async with SimulatedBody(url(server)) as body:
        await body.handshake()
        await body.chat("Vry", "bora pegar madeira?")
        [session] = server.sessions
        for _ in range(100):
            if session.events:
                break
            await asyncio.sleep(0.01)
        event = session.events[0].model_dump()
    assert event["event"] == "chat"
    assert event["from"] == "Vry" and event["text"] == "bora pegar madeira?"


async def test_invalid_message_gets_error_and_connection_survives(server):
    async with SimulatedBody(url(server)) as body:
        assert body.conn
        await body.conn.send("isso não é json")
        err = await body.recv()
        assert isinstance(err, p.ErrorMsg)
        await body.handshake()  # a conexão continua de pé


async def test_wrong_protocol_version_is_refused(server):
    async with SimulatedBody(url(server)) as body:
        await body.send(p.Hello(protocol=99, body="sim", body_version="x"))
        err = await body.recv()
        assert isinstance(err, p.ErrorMsg)


# ---- fase 1 ----------------------------------------------------------------


async def test_welcome_carries_username_and_no_skin_by_default(server):
    async with SimulatedBody(url(server)) as body:
        welcome = await body.handshake()
    assert welcome.username == "Lumi"
    assert welcome.skin is None


async def test_greets_when_spawned(server):
    async with SimulatedBody(url(server)) as body:
        await body.handshake()
        await body.spawn()
        assert await body.next_say() == "oi gente! cheguei"


async def test_echoes_only_when_mentioned_and_online(server):
    async with SimulatedBody(url(server)) as body:
        await body.handshake()
        await body.chat("Vry", "lumi bora pegar madeira?")  # ainda fora do mundo: não responde
        await body.spawn()
        assert await body.next_say() == "oi gente! cheguei"
        await body.chat("Vry", "alguém viu meu cavalo?")  # não falou com ela
        await body.chat("Vry", "LUMI bora pegar madeira?")
        assert await body.next_say() == "(eco) LUMI bora pegar madeira?"


async def test_state_is_stored(server):
    async with SimulatedBody(url(server)) as body:
        await body.handshake()
        await body.state(online=True, health=14.0, food=6)
        [session] = server.sessions
        for _ in range(100):
            if session.state:
                break
            await asyncio.sleep(0.01)
        assert session.state.online and session.state.model_dump()["food"] == 6


async def test_skin_from_resolver_goes_into_welcome(characters_dir):
    from waifuscam_mind.skins import Skin

    class FakeSkins:
        async def resolve(self, character):
            return Skin("VAL", "SIG")

    srv = MindServer(load_character(characters_dir, "lumi"), port=0, skins=FakeSkins())
    await srv.start()
    try:
        async with SimulatedBody(url(srv)) as body:
            welcome = await body.handshake()
        assert welcome.skin and welcome.skin.signature == "SIG"
    finally:
        await srv.stop()


# ---- fase 3 ----------------------------------------------------------------


async def test_hello_catalog_is_stored(server):
    async with SimulatedBody(url(server)) as body:
        await body.handshake()
        [session] = server.sessions
        assert [s.name for s in session.skills] == ["follow", "goto", "stop"]


async def test_echo_brain_understands_fixed_requests_and_reports_failures(server):
    async with SimulatedBody(url(server)) as body:
        await body.handshake()
        await body.spawn()
        await body.next_say()  # saudação
        await body.chat("Vry", "lumi me segue")
        start = await body.next_of(p.SkillStart)
        assert start.skill == "follow" and start.args == {"player": "Vry"} and start.id.startswith("mind-")
        assert await body.next_say() == "bora, tô indo"
        await body.skill_ended(start.id, "follow", reason="Vry foi para outra dimensão")
        assert await body.next_say() == "não deu: Vry foi para outra dimensão"
        await asyncio.sleep(2.1)  # intervalo mínimo entre respostas do eco
        await body.chat("Vry", "lumi, para!")
        assert (await body.next_of(p.SkillStart)).skill == "stop"
        assert await body.next_say() == "tá, parei"
        await asyncio.sleep(2.1)
        await body.chat("Vry", "lumi vamos para a mina")  # "para" como preposição
        assert await body.next_say() == "(eco) lumi vamos para a mina"
