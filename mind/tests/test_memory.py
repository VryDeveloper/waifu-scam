"""Fase 6: memória que sobrevive a reinícios."""

import json

import pytest

from waifuscam_mind import protocol as p
from waifuscam_mind.brain.chat import ChatBrain
from waifuscam_mind.character import load_character
from waifuscam_mind.llm import Budget
from waifuscam_mind.memory import Memory, slug

from .test_chat_brain import Clock, FakeLLM, FakeMouth, no_sleep


@pytest.fixture
def lumi(characters_dir):
    return load_character(characters_dir, "lumi")


def here(**extra):
    base = dict(
        online=True,
        health=20.0,
        food=20,
        players=["Lumi", "Vry"],
        x=120.7,
        y=64.0,
        z=-33.2,
        dimension="minecraft:overworld",
        time_of_day=6000,
        sky=True,
    )
    base.update(extra)
    return p.State(**base)


def chat(sender, text):
    return p.Event(event="chat", **{"from": sender, "text": text})


def test_slug():
    assert slug("Mina de Ferro!") == "mina_de_ferro"
    assert slug("Báse") == "base"


def test_places_and_people_survive_restart(tmp_path):
    file = tmp_path / "lumi.json"
    m = Memory(file)
    m.remember_place("Base", 10, 64, -5, "minecraft:overworld", "a casa de madeira")
    m.talked("Vry")
    m.note("o Vry tem medo de aranha", ["Vry"])
    m.note("tem uma vila pro norte")
    m.write_diary("hoje fiz minha primeira picareta")
    m.save(force=True)

    again = Memory(file)
    assert again.place("base").coords() == "10 64 -5"
    assert again.place("a base") is not None  # "a base" acha "base"
    assert again.people["Vry"].chats == 1 and "medo de aranha" in again.people["Vry"].notes[0]
    assert again.notes == ["tem uma vila pro norte"]
    text = again.describe(["Lumi", "Vry"], "Lumi")
    assert "base: 10 64 -5 (a casa de madeira)" in text
    assert "Vry: conhece há pouco tempo, conversaram 1 vezes. o Vry tem medo de aranha" in text
    assert "primeira picareta" in text


def test_corrupt_file_starts_fresh(tmp_path):
    file = tmp_path / "lumi.json"
    file.write_text("{ isso não é json", encoding="utf-8")
    assert Memory(file).places == {}


def test_saves_atomically(tmp_path):
    file = tmp_path / "sub" / "lumi.json"
    m = Memory(file)
    m.remember_place("casa", 1, 2, 3, "minecraft:overworld")
    assert json.loads(file.read_text(encoding="utf-8"))["places"]["casa"]["x"] == 1
    assert not (tmp_path / "sub" / "lumi.tmp").exists()


# ---- no cérebro ------------------------------------------------------------------


async def test_remember_and_go_to_the_base_after_restart(lumi, tmp_path):
    file = tmp_path / "lumi.json"
    mouth = FakeMouth()
    mouth.state = here()
    b = ChatBrain(
        lumi,
        FakeLLM("anotado, aqui é a base\n[lembrar: base]"),
        Budget(50),
        clock=Clock(),
        sleep=no_sleep,
        memory=Memory(file),
    )
    await b.on_event(mouth, chat("Vry", "lumi, aqui é a nossa base"))
    assert mouth.said == ["anotado, aqui é a base"]

    # reiniciou a Mente: memória nova lendo o mesmo arquivo
    llm = FakeLLM("bora pra base\n[ação: goto base]")
    b2 = ChatBrain(lumi, llm, Budget(50), clock=Clock(), sleep=no_sleep, memory=Memory(file))
    mouth2 = FakeMouth()
    mouth2.state = here(x=500.0, z=500.0)
    await b2.on_event(mouth2, chat("Vry", "lumi, volta pra base"))
    assert mouth2.started == [("goto", {"x": 120, "y": 64, "z": -33})]
    system = llm.calls[0][0]["content"]
    assert "base: 120 64 -33" in system
    # e lembra da conversa de antes
    assert {"role": "user", "content": "Vry: lumi, aqui é a nossa base"} in llm.calls[0]


async def test_goto_online_player_is_not_a_place(lumi, tmp_path):
    m = Memory(tmp_path / "m.json")
    m.remember_place("vry", 0, 0, 0, "minecraft:overworld")
    mouth = FakeMouth()
    mouth.state = here()
    b = ChatBrain(lumi, FakeLLM("indo\n[ação: goto Vry]"), Budget(50), clock=Clock(), sleep=no_sleep, memory=m)
    await b.on_event(mouth, chat("Vry", "vem aqui"))
    assert mouth.started == [("goto", {"player": "Vry"})]


async def test_notes_about_people(lumi, tmp_path):
    m = Memory(tmp_path / "m.json")
    mouth = FakeMouth()
    mouth.state = here()
    b = ChatBrain(
        lumi, FakeLLM("kkkk vou lembrar\n[nota: Vry odeia aranha]"), Budget(50), clock=Clock(), sleep=no_sleep, memory=m
    )
    await b.on_event(mouth, chat("Vry", "eu odeio aranha"))
    assert mouth.said == ["kkkk vou lembrar"]
    assert m.people["Vry"].notes == ["Vry odeia aranha"]


async def test_death_place_is_remembered(lumi, tmp_path):
    m = Memory(tmp_path / "m.json")
    mouth = FakeMouth()
    mouth.state = here(x=7.0, y=12.0, z=8.0)
    b = ChatBrain(lumi, FakeLLM("aff"), Budget(50), clock=Clock(), sleep=no_sleep, memory=m)
    await b.on_event(mouth, p.Event(event="died", message="Lumi tried to swim in lava"))
    assert m.place("ultima_morte").coords() == "7 12 8"


async def test_promises_survive_restart(lumi, tmp_path):
    file = tmp_path / "m.json"
    mouth = FakeMouth()
    mouth.state = here()
    b = ChatBrain(
        lumi,
        FakeLLM("depois eu faço\n[depois: obtain torch 8]"),
        Budget(50),
        clock=Clock(),
        sleep=no_sleep,
        memory=Memory(file),
    )
    await b.on_event(mouth, chat("Vry", "faz umas tochas?"))
    b.memory.save(force=True)
    b2 = ChatBrain(lumi, FakeLLM("bora"), Budget(50), clock=Clock(), sleep=no_sleep, memory=Memory(file))
    assert [pr.describe() for pr in b2.will.pending()] == ["obtain torch 8 para Vry"]


async def test_diary_is_written_at_dawn(lumi, tmp_path):
    clock = Clock()
    m = Memory(tmp_path / "m.json")
    llm = FakeLLM(*["ok"] * 4, "hoje ajudei o Vry a minerar e fiz uma picareta.")
    mouth = FakeMouth()
    mouth.state = here()
    b = ChatBrain(lumi, llm, Budget(50), clock=clock, sleep=no_sleep, memory=m)
    b.will.config.enabled = False
    for i in range(4):
        await b.on_event(mouth, chat("Vry", f"msg {i}"))
    clock.t += 700
    await b.on_state(mouth, here(time_of_day=23500))
    await b.on_state(mouth, here(time_of_day=100))
    assert m.diary and "picareta" in m.diary[-1].text


async def test_remember_command_from_the_game(lumi, tmp_path):
    m = Memory(tmp_path / "m.json")
    b = ChatBrain(lumi, FakeLLM(), Budget(50), clock=Clock(), sleep=no_sleep, memory=m)
    event = p.Event(
        event="remember_place", name="Mina", x=1.5, y=-40.0, z=3.9, dimension="minecraft:overworld", **{"from": "Vry"}
    )
    await b.on_event(FakeMouth(), event)
    assert m.place("mina").coords() == "1 -40 3"
