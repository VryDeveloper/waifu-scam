"""Fase 5: necessidades e vontade própria."""

import pytest

from waifuscam_mind import protocol as p
from waifuscam_mind.brain.chat import ChatBrain
from waifuscam_mind.brain.needs import assess, describe_needs, dusk, is_night
from waifuscam_mind.character import load_character
from waifuscam_mind.llm import AllProvidersFailed, Budget

from .test_chat_brain import Clock, FakeLLM, FakeMouth, no_sleep

DAY = 6000
DUSK = 12000
WEIGHTS = {
    "hunger": 1.0,
    "safety": 1.2,
    "energy": 1.0,
    "social": 1.5,
    "progress": 0.8,
    "comfort": 1.3,
    "curiosity": 1.0,
}


def state(**fields):
    base = dict(online=True, health=20.0, max_health=20.0, food=20, players=["Lumi", "Vry"], time_of_day=DAY, sky=True)
    base.update(fields)
    return p.State(**base)


@pytest.fixture
def lumi(characters_dir):
    return load_character(characters_dir, "lumi")


def make(lumi, llm, clock, limit=100):
    return ChatBrain(lumi, llm, Budget(limit), clock=clock, sleep=no_sleep)


# ---- necessidades ----------------------------------------------------------------


def test_hunger_and_food():
    hungry = assess(state(food=6, inventory={"dirt": 3}), WEIGHTS)
    assert hungry.urgency["hunger"] > 0.8 and not hungry.has_food
    fed = assess(state(food=20, inventory={"bread": 2}), WEIGHTS)
    assert fed.urgency["hunger"] == 0 and fed.has_food


def test_night_outdoors_is_unsafe():
    out = assess(state(time_of_day=18000, sky=True), WEIGHTS)
    inside = assess(state(time_of_day=18000, sky=False), WEIGHTS)
    assert out.urgency["safety"] > inside.urgency["safety"]
    assert is_night(18000) and not is_night(DAY) and dusk(DUSK) and not dusk(DAY)


def test_needs_text():
    text = describe_needs(assess(state(food=4, inventory={}, time_of_day=18000), WEIGHTS))
    assert "Com fome, e sem comida" in text and "É noite" in text and "O que você mais quer agora" in text
    assert describe_needs(assess(None, WEIGHTS)) == ""


def test_threats_show_up():
    st = state(threats=[{"type": "zombie", "distance": 4.2}])
    assert "zombie a 4.2 blocos" in describe_needs(assess(st, WEIGHTS))


# ---- vontade ---------------------------------------------------------------------


async def test_shelters_at_dusk_once(lumi):
    clock = Clock()
    mouth = FakeMouth()
    b = make(lumi, FakeLLM(fail=True), clock)
    await b.on_state(mouth, state(time_of_day=DUSK))
    await b.on_state(mouth, state(time_of_day=DUSK + 100))
    assert mouth.started == [("shelter", {})]
    assert mouth.said == ["vou me esconder que tá ficando de noite"]


async def test_does_not_shelter_indoors_or_when_busy(lumi):
    mouth = FakeMouth()
    b = make(lumi, FakeLLM(), Clock())
    await b.on_state(mouth, state(time_of_day=DUSK, sky=False))
    await b.on_state(mouth, state(time_of_day=DUSK, skill={"id": "cmd-1", "name": "follow", "doing": "seguindo Vry"}))
    assert mouth.started == []


async def test_postponed_request_is_done_later(lumi):
    clock = Clock()
    llm = FakeLLM("agora não, tô morrendo de fome. já já eu faço\n[depois: obtain stone_pickaxe 1]", "bora fazer")
    mouth = FakeMouth()
    b = make(lumi, llm, clock)
    await b.on_event(mouth, p.Event(event="chat", **{"from": "Vry", "text": "faz uma picareta de pedra?"}))
    assert mouth.started == [] and mouth.said == ["agora não, tô morrendo de fome. já já eu faço"]
    assert [pr.describe() for pr in b.will.pending()] == ["obtain stone_pickaxe 1 para Vry"]
    # o prompt seguinte lembra a promessa
    await b.on_state(mouth, state())
    assert mouth.started == [("obtain", {"item": "stone_pickaxe", "count": 1})]
    assert b.will.pending() == []
    assert "Você prometeu" in llm.calls[1][0]["content"] or "prometido" in llm.calls[1][-1]["content"]


async def test_idle_too_long_decides_alone(lumi):
    clock = Clock()
    llm = FakeLLM("tô entediada, vou pegar madeira\n[ação: obtain oak_log 8]")
    mouth = FakeMouth()
    b = make(lumi, llm, clock)
    b.will.config.autopilot = False  # só a decisão livre da IA
    await b.on_state(mouth, state())
    assert mouth.started == []  # acabou de chegar: não pensa ainda
    clock.t += 200
    await b.on_state(mouth, state())
    assert mouth.started == [("obtain", {"item": "oak_log", "count": 8})]
    assert "parada há um tempo" in llm.calls[0][-1]["content"]
    assert "O que você mais quer agora" in llm.calls[0][0]["content"]


async def test_hungry_without_food_asks_the_ai(lumi):
    clock = Clock()
    llm = FakeLLM("tô com fome, vou caçar\n[ação: obtain cooked_beef 2]")
    mouth = FakeMouth()
    b = make(lumi, llm, clock)
    await b.on_state(mouth, state(food=4, inventory={"dirt": 5}))
    assert mouth.started == [("obtain", {"item": "cooked_beef", "count": 2})]


async def test_saves_budget_when_low(lumi):
    clock = Clock()
    llm = FakeLLM("x")
    b = make(lumi, llm, clock, limit=10)
    for _ in range(9):
        b.budget.try_spend()
    clock.t += 500
    await b.on_state(FakeMouth(), state())
    assert llm.calls == []


async def test_will_can_be_disabled(lumi):
    clock = Clock()
    b = make(lumi, FakeLLM("x"), clock)
    b.will.config.enabled = False
    mouth = FakeMouth()
    clock.t += 500
    await b.on_state(mouth, state(time_of_day=DUSK))
    assert mouth.started == [] and mouth.said == []


async def test_danger_comment_has_cooldown(lumi):
    clock = Clock()
    llm = FakeLLM("aaa zumbi", "outro?")
    mouth = FakeMouth()
    b = make(lumi, llm, clock)
    await b.on_event(mouth, p.Event(event="danger", message="zombie"))
    await b.on_event(mouth, p.Event(event="danger", message="zombie"))
    assert mouth.said == ["aaa zumbi"]
    assert "## Ações" not in llm.calls[0][0]["content"]  # reação rápida, sem ação


def test_unused_import_guard():
    assert AllProvidersFailed


# ---- objetivos próprios -----------------------------------------------------------


async def test_follows_its_own_progression_when_idle(lumi):
    clock = Clock()
    llm = FakeLLM("bora fazer uma picareta")
    mouth = FakeMouth()
    b = make(lumi, llm, clock)
    await b.on_state(mouth, state(inventory={"dirt": 3}))
    assert mouth.started == []  # acabou de ficar livre
    clock.t += 20
    await b.on_state(mouth, state(inventory={"dirt": 3}))
    assert mouth.started == [("obtain", {"item": "wooden_pickaxe", "count": 1})]
    assert "decidiu sozinha: fazer uma picareta de madeira" in llm.calls[0][-1]["content"]


async def test_failed_goal_is_skipped_for_a_while(lumi):
    clock = Clock()
    mouth = FakeMouth()
    b = make(lumi, FakeLLM(), clock)
    clock.t += 20
    await b.on_state(mouth, state(inventory={"wooden_pickaxe": 1}))
    assert mouth.started[-1] == ("obtain", {"item": "stone_pickaxe", "count": 1})
    await b.on_skill_ended(mouth, p.SkillFailed(id="mind-1", skill="obtain", reason="sem pedra"))
    clock.t += 20
    await b.on_state(mouth, state(inventory={"wooden_pickaxe": 1}))
    assert mouth.started[-1] == ("obtain", {"item": "stone_sword", "count": 1})  # pulou a picareta


async def test_hungry_without_food_goes_for_food_first(lumi):
    clock = Clock()
    mouth = FakeMouth()
    b = make(lumi, FakeLLM(), clock)
    clock.t += 20
    await b.on_state(mouth, state(food=8, inventory={"stone_pickaxe": 1}))
    assert mouth.started == [("obtain", {"item": "cooked_beef", "count": 3})]


async def test_failed_shelter_is_retried(lumi):
    clock = Clock()
    mouth = FakeMouth()
    b = make(lumi, FakeLLM(), clock)
    await b.on_state(mouth, state(time_of_day=DUSK))
    await b.on_skill_ended(mouth, p.SkillFailed(id="mind-1", skill="shelter", reason="chão ruim"))
    await b.on_state(mouth, state(time_of_day=DUSK + 50))
    assert [s for s, _ in mouth.started] == ["shelter"]  # ainda não
    clock.t += 50
    await b.on_state(mouth, state(time_of_day=DUSK + 100))
    assert [s for s, _ in mouth.started] == ["shelter", "shelter"]


async def test_stay_here_pauses_own_goals(lumi):
    clock = Clock()
    mouth = FakeMouth()
    b = make(lumi, FakeLLM("tá, fico", "ok"), clock)
    await b.on_event(mouth, p.Event(event="chat", **{"from": "Vry", "text": "lumi fica aqui"}))
    clock.t += 60
    await b.on_state(mouth, state(inventory={"dirt": 1}))
    assert mouth.started == []
    clock.t += 200
    await b.on_state(mouth, state(inventory={"dirt": 1}))
    assert mouth.started and mouth.started[0][0] == "obtain"


# ---- fase 10: plantação -------------------------------------------------------------

FARM = p.SkillSpec(name="farm", description="Cuidar da plantação.", params={"type": "object", "properties": {}})
CROPS_IN_VIEW = {"blocks": [{"block": "wheat", "distance": 5.0, "dir": "na frente", "count": 9}]}
GEARED = {"stone_pickaxe": 1, "stone_sword": 1, "stone_axe": 1, "crafting_table": 1, "bread": 6}


async def test_tends_the_farm_it_sees_then_waits(lumi):
    clock = Clock()
    mouth = FakeMouth()
    mouth.skills = [*mouth.skills, FARM]
    b = make(lumi, FakeLLM(), clock)
    clock.t += 20
    await b.on_state(mouth, state(inventory=GEARED, seeing=CROPS_IN_VIEW))
    assert mouth.started[-1] == ("farm", {})
    await b.on_skill_ended(mouth, p.SkillDone(id="mind-1", skill="farm"))
    clock.t += 20
    await b.on_state(mouth, state(inventory=GEARED, seeing=CROPS_IN_VIEW))
    assert mouth.started[-1][0] != "farm"  # acabou de cuidar: só daqui a 20 min


def test_bow_then_arrows_are_goals():
    from waifuscam_mind.brain.goals import candidates

    base = {"stone_pickaxe": 1, "iron_pickaxe": 1, "iron_sword": 1, "shield": 1, "iron_chestplate": 1, "bread": 9}
    keys = [g.key for g in candidates({"inventory": base, "food": 20})]
    assert "bow" in keys and "arrows" not in keys
    keys = [g.key for g in candidates({"inventory": {**base, "bow": 1}, "food": 20})]
    assert "arrows" in keys and "bow" not in keys
