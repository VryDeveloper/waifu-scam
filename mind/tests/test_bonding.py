"""Momentos de casal: pertinho, flor, dormir junto, agachar, datas."""

from waifuscam_mind import protocol as p
from waifuscam_mind.brain.bonding import bond_goal, partner_sleeping
from waifuscam_mind.memory import Memory

from .test_helping import GEARED, vry
from .test_will import Clock, FakeLLM, FakeMouth, lumi, make, state  # noqa: F401

SLEEP = p.SkillSpec(name="sleep", description="Dormir.", params={"type": "object"})


def test_comes_close_when_he_stops_and_brings_flowers():
    g = bond_goal({"players_activity": [vry(distance=12, idle_seconds=40)], "time_of_day": 6000}, "Vry")
    assert (g.skill, g.args) == ("goto", {"player": "Vry"})
    g = bond_goal({"players_activity": [vry(distance=5)], "time_of_day": 6000}, "Vry")
    assert (g.skill, g.args["item"]) == ("give", "#small_flowers")
    assert bond_goal({"players_activity": [vry(distance=5)], "time_of_day": 18000}, "Vry") is None  # de noite não
    assert bond_goal({"players_activity": [vry(name="Ana", distance=5)], "time_of_day": 6000}, "Vry") is None
    assert partner_sleeping({"players_activity": [vry(sleeping=True)]}, "Vry") == "Vry"


async def test_sleeps_when_he_sleeps(lumi):  # noqa: F811
    clock = Clock()
    mouth = FakeMouth()
    mouth.skills = [*mouth.skills, SLEEP]
    b = make(lumi, FakeLLM("boa noite, amor"), clock)
    b.will.beloved = "Vry"
    await b.on_state(mouth, state(time_of_day=18500, sky=False, players_activity=[vry(sleeping=True)]))
    assert mouth.started == [("sleep", {})]


async def test_first_flower_waits_a_while(lumi):  # noqa: F811
    clock = Clock()
    mouth = FakeMouth()
    b = make(lumi, FakeLLM("x"), clock)
    b.will.beloved = "Vry"
    clock.t += 20
    await b.on_state(mouth, state(inventory=GEARED, players_activity=[vry(distance=5)]))
    assert ("give", {"player": "Vry", "item": "#small_flowers", "count": 1}) not in mouth.started
    said = list(mouth.said)
    clock.t += 21 * 60
    await b.on_state(mouth, state(inventory=GEARED, players_activity=[vry(distance=5)]))
    assert mouth.started[-1] == ("give", {"player": "Vry", "item": "#small_flowers", "count": 1})
    assert mouth.said == said  # surpresa: fala só quando entregar


async def test_crouch_is_a_moment_and_gets_a_reaction(lumi, tmp_path):  # noqa: F811
    mouth = FakeMouth()
    b = make(lumi, FakeLLM("own, para kkkk"), Clock())
    b.memory = Memory(tmp_path / "m.json")
    await b.on_event(mouth, p.Event(event="crouch", **{"from": "Vry"}))
    assert mouth.said == ["own, para kkkk"]
    assert any(m["key"] == "first_crouch:Vry" for m in b.memory.moments)
    assert "Momentos especiais" in b.memory.describe(["Vry"], "Lumi")


def test_anniversary_once(lumi, tmp_path):  # noqa: F811
    now = [1_000_000.0]
    b = make(lumi, FakeLLM(), Clock())
    b.memory = Memory(tmp_path / "m.json", clock=lambda: now[0])
    b.memory.talked(lumi.beloved or "Vryx_")
    now[0] += 7 * 86400 + 60
    text = b._anniversary()
    if lumi.beloved:
        assert "uma semana" in text
        assert b._anniversary() == ""  # só uma vez


async def test_emote_tag_moves_the_body(lumi):  # noqa: F811
    from .test_chat_brain import chat
    from .test_vision import SendingMouth

    mouth = SendingMouth()
    b = make(lumi, FakeLLM("own, você é muito fofo\n[emote: coracoes]"), Clock())
    await b.on_event(mouth, chat("Vry", "lumi você é linda"))
    assert mouth.said == ["own, você é muito fofo"]
    assert [(m.name, m.to) for m in mouth.sent if isinstance(m, p.Emote)] == [("coracoes", "Vry")]


async def test_sits_after_coming_close_and_stays_while_he_is_still(lumi):  # noqa: F811
    from .test_vision import SendingMouth

    clock = Clock()
    mouth = SendingMouth()
    b = make(lumi, FakeLLM("x"), clock)
    b.will.beloved = "Vry"
    clock.t += 20
    await b.on_state(mouth, state(inventory=GEARED, players_activity=[vry(distance=12, idle_seconds=40)]))
    assert mouth.started[-1] == ("goto", {"player": "Vry"})
    await b.on_skill_ended(mouth, p.SkillDone(id="mind-1", skill="goto"))
    await b.on_state(mouth, state(inventory=GEARED, players_activity=[vry(distance=2, idle_seconds=60)]))
    assert any(isinstance(m, p.Emote) and m.name == "sit" for m in mouth.sent)
    n = len(mouth.started)
    clock.t += 60
    await b.on_state(mouth, state(inventory=GEARED, sitting=True, players_activity=[vry(distance=2, idle_seconds=120)]))
    assert len(mouth.started) == n  # fica sentadinha
