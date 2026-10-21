"""Ajudar no que o player está fazendo."""

from waifuscam_mind import protocol as p
from waifuscam_mind.brain.helping import activity, describe_activity, help_goal

from .test_will import FARM, Clock, FakeLLM, FakeMouth, lumi, make, state  # noqa: F401

GEARED = {"stone_pickaxe": 1, "stone_sword": 1, "stone_axe": 1, "crafting_table": 1, "bread": 6, "furnace": 1}


def vry(**fields):
    base = {"name": "Vry", "distance": 6.0, "underground": False, "y": 70, "broke": {}, "placed": {}, "attacked": {}}
    base.update(fields)
    return base


def test_reads_what_he_is_doing():
    assert activity(vry(broke={"oak_log": 3})) == "cortando árvore"
    assert activity(vry(placed={"cobblestone": 9})) == "construindo (com cobblestone)"
    assert activity(vry(underground=True, y=20, broke={"stone": 5, "iron_ore": 1})) == "minerando minério (iron)"
    assert activity(vry(underground=True, y=20, broke={"stone": 5})) == "minerando numa caverna"
    assert activity(vry(attacked={"zombie": 2})) == "lutando com monstros"
    assert activity(vry()) is None
    text = describe_activity({"players_activity": [vry(broke={"birch_log": 4})]}, "Lumi")
    assert "Vry está cortando árvore" in text


def test_help_goals():
    g = help_goal({"players_activity": [vry(broke={"oak_log": 3})]}, "Vry")
    assert (g.skill, g.args) == ("collect", {"item": "#logs", "count": 12})
    g = help_goal({"players_activity": [vry(placed={"cobblestone": 9, "torch": 1})]}, "Vry")
    assert (g.skill, g.args) == ("give", {"player": "Vry", "item": "cobblestone", "count": 16})
    g = help_goal({"players_activity": [vry(underground=True, y=12)]}, "Vry")
    assert (g.skill, g.args) == ("follow", {"player": "Vry"})
    g = help_goal({"players_activity": [vry(distance=40, broke={"oak_log": 3})]}, "Vry")
    assert (g.skill, g.args) == ("goto", {"player": "Vry"})  # longe: primeiro chega perto
    assert help_goal({"players_activity": [vry()]}, "Vry") is None


def test_prefers_who_she_loves():
    people = [vry(name="Ana", distance=2, broke={"oak_log": 3}), vry(distance=10, attacked={"cow": 1})]
    assert help_goal({"players_activity": people}, "Vry").skill == "hunt"


async def test_will_helps_before_its_own_list(lumi):  # noqa: F811
    clock = Clock()
    mouth = FakeMouth()
    mouth.skills = [*mouth.skills, p.SkillSpec(name="collect", description="Coletar.", params={"type": "object"})]
    b = make(lumi, FakeLLM("bora juntos, meu bem"), clock)
    b.will.beloved = "Vry"
    clock.t += 20
    await b.on_state(mouth, state(inventory=GEARED, players_activity=[vry(broke={"oak_log": 4})]))
    assert mouth.started == [("collect", {"item": "#logs", "count": 12})]
    assert mouth.said == ["bora juntos, meu bem"]


async def test_escort_ends_when_he_leaves_the_cave(lumi):  # noqa: F811
    clock = Clock()
    mouth = FakeMouth()
    b = make(lumi, FakeLLM("vou com você"), clock)
    b.will.beloved = "Vry"
    clock.t += 20
    cave = [vry(underground=True, y=10)]
    await b.on_state(mouth, state(inventory=GEARED, players_activity=cave))
    assert mouth.started == [("follow", {"player": "Vry"})]
    following = {"id": "mind-1", "name": "follow", "doing": "seguindo Vry"}
    clock.t += 10
    await b.on_state(mouth, state(inventory=GEARED, players_activity=cave, skill=following))
    out = [vry(y=70)]
    clock.t += 10
    await b.on_state(mouth, state(inventory=GEARED, players_activity=out, skill=following))
    assert ("cancel", {}) not in mouth.started
    clock.t += 40
    await b.on_state(mouth, state(inventory=GEARED, players_activity=out, skill=following))
    assert mouth.started[-1] == ("cancel", {})


async def test_nothing_when_spontaneous_is_off(lumi):  # noqa: F811
    clock = Clock()
    mouth = FakeMouth()
    b = make(lumi, FakeLLM("x"), clock)
    b.will.config.spontaneous = False
    clock.t += 20
    await b.on_state(mouth, state(inventory=GEARED, players_activity=[vry(broke={"oak_log": 4})]))
    assert mouth.started == []


def test_unused_imports():
    assert p and FARM


def test_helps_mine_the_ores_around():
    g = help_goal({"players_activity": [vry(broke={"copper_ore": 2, "stone": 3})]}, "Vry")
    assert (g.skill, g.args) == ("mine_ores", {})
    g = help_goal({"players_activity": [vry(underground=True, y=10, broke={"deepslate_iron_ore": 1})]}, "Vry")
    assert g.skill == "mine_ores"  # minério vem antes de só acompanhar
    g = help_goal({"players_activity": [vry(distance=30, broke={"coal_ore": 1})]}, "Vry")
    assert g.skill == "goto"
