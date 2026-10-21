"""Fase 8: o que ela vê e onde lembra de ter visto as coisas."""

from waifuscam_mind import protocol as p
from waifuscam_mind.brain.chat import describe_situation
from waifuscam_mind.brain.vision import compass, describe_resources, describe_seeing, kind, label, nearest
from waifuscam_mind.memory import Memory

from .test_chat_brain import Clock, FakeLLM, FakeMouth, brain, chat, lumi  # noqa: F401

SEEING = {
    "looking_at": "Vry",
    "biome": "plains",
    "entities": [
        {"type": "player", "name": "Vry", "distance": 4.1, "dir": "na frente"},
        {"type": "zombie", "distance": 12.0, "dir": "à esquerda", "hostile": True},
        {"type": "item:diamond", "distance": 3.0, "dir": "na frente"},
    ],
    "blocks": [{"block": "deepslate_iron_ore", "distance": 6.5, "dir": "na frente, à direita", "count": 5}],
}


class SendingMouth(FakeMouth):
    def __init__(self, **kw):
        super().__init__(**kw)
        self.sent: list[p.Envelope] = []

    async def send(self, msg):
        self.sent.append(msg)


def state(**fields):
    base = dict(online=True, health=20.0, max_health=20.0, food=20, players=["Lumi", "Vry"], x=0.0, y=64.0, z=0.0)
    base.update(fields)
    return p.State(**base)


def test_labels_and_directions():
    assert label("minecraft:deepslate_diamond_ore") == "minério de diamante"
    assert kind("deepslate_iron_ore") == "ferro" and kind("bell") == "sino de vila"
    assert label("red_bed") == "cama"
    assert compass(0, -10) == "norte" and compass(10, 0) == "leste" and compass(-5, 5) == "sudoeste"


def test_describes_what_she_sees():
    text = describe_seeing({"seeing": SEEING, "players_near": [{"name": "Ana", "distance": 9, "dir": "atrás"}]}, "Lumi")
    assert "Olhando para: Vry" in text
    assert "zombie (hostil) a 12.0 blocos, à esquerda" in text
    assert "item no chão (diamond)" in text
    assert "minério de ferro (vários) a 6.5 blocos, na frente, à direita" in text
    assert "Ana está atrás, a 9 blocos (você não está vendo)" in text
    assert "Bioma: plains" in describe_situation(state(seeing=SEEING), "Lumi")


def test_memory_clusters_and_forgets(tmp_path):
    mem = Memory(tmp_path / "m.json", clock=Clock())
    assert mem.saw("mundo", "minecraft:iron_ore", 10, 30, 10, "minecraft:overworld")
    assert not mem.saw("mundo", "iron_ore", 11, 30, 10, "minecraft:overworld")  # mesmo veio
    assert mem.saw("mundo", "iron_ore", 40, 30, 10, "minecraft:overworld")
    assert mem.saw("outro", "iron_ore", 10, 30, 10, "minecraft:overworld")  # cada mundo é um mundo
    mem.gone("mundo", "iron_ore", 40, 30, 10, "minecraft:overworld")
    mem.save(force=True)
    again = Memory(tmp_path / "m.json")
    assert [(s["x"], s["block"]) for s in again.spots("mundo")] == [(10, "iron_ore")]


def test_resources_text_and_nearest():
    spots = [
        {"block": "iron_ore", "x": 30, "y": 20, "z": 0, "dimension": "minecraft:overworld"},
        {"block": "deepslate_iron_ore", "x": 5, "y": -10, "z": 5, "dimension": "minecraft:overworld"},
        {"block": "bell", "x": 0, "y": 64, "z": -200, "dimension": "minecraft:overworld"},
        {"block": "diamond_ore", "x": 0, "y": 20, "z": 0, "dimension": "minecraft:the_nether"},
    ]
    s = {"x": 0, "y": 64, "z": 0}
    text = describe_resources(spots, s)
    assert "- ferro: a 53 blocos, leste, Y 20 (2 lugares)" in text
    assert "- sino de vila: a 200 blocos, norte" in text
    assert "diamante" not in text  # outra dimensão
    assert nearest(spots, "ferro", s)["x"] == 30


async def test_seeds_the_body_and_remembers_what_it_sees(lumi):  # noqa: F811
    mouth = SendingMouth()
    b = brain(lumi, FakeLLM("aaa diamante!!"))
    b.memory.saw("Mundo", "iron_ore", 1, 2, 3, "minecraft:overworld")
    await b.on_state(mouth, state(world="Mundo"))
    assert isinstance(mouth.sent[0], p.MemoryBlocks)
    assert mouth.sent[0].blocks == [{"block": "iron_ore", "x": 1, "y": 2, "z": 3, "dimension": "minecraft:overworld"}]
    await b.on_state(mouth, state(world="Mundo"))
    assert len(mouth.sent) == 1  # só uma vez por mundo

    mouth.state = state(world="Mundo")
    seen = p.Event(event="seen", block="deepslate_diamond_ore", x=0, y=-58, z=20, dimension="minecraft:overworld")
    await b.on_event(mouth, seen)
    assert mouth.said == ["aaa diamante!!"]
    await b.on_event(mouth, seen)  # o mesmo diamante não é novidade
    assert len(mouth.said) == 1
    assert any(s["block"] == "deepslate_diamond_ore" for s in b.memory.spots("Mundo"))


async def test_goto_a_remembered_resource(lumi):  # noqa: F811
    mouth = SendingMouth()
    b = brain(lumi, FakeLLM("bora lá\n[ação: goto ferro]"))
    b.memory.saw("Mundo", "iron_ore", 50, 12, -8, "minecraft:overworld")
    await b.on_state(mouth, state(world="Mundo"))
    mouth.state = state(world="Mundo")
    await b.on_event(mouth, chat("Vry", "lumi, onde tinha ferro mesmo? vamo lá"))
    assert mouth.started == [("goto", {"x": 50, "y": 12, "z": -8})]
