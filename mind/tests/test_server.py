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
