"""Receitas e passo a passo: ela sabe como fazer as coisas, com os dados do jogo."""

import asyncio
import json

from waifuscam_mind import protocol as p
from waifuscam_mind.brain.recipes import describe_plan
from waifuscam_mind.items import ItemNames, load_game_names

from .test_chat_brain import FakeLLM, FakeMouth, brain, chat, lumi  # noqa: F401
from .test_server import server, url  # noqa: F401

BED_PLAN = {
    "ok": True,
    "item": "white_bed",
    "count": 1,
    "have": 0,
    "table_nearby": False,
    "furnace_nearby": False,
    "recipe": {
        "kind": "craft",
        "table": True,
        "makes": 1,
        "inputs": [{"item": "white_wool", "count": 3}, {"item": "oak_planks", "count": 3, "any": True}],
    },
    "steps": [
        {"do": "hunt", "item": "white_wool", "count": 3, "from": ["sheep"]},
        {"do": "gather", "item": "oak_log", "count": 2, "from": ["oak_log"]},
        {"do": "craft", "item": "oak_planks", "count": 8, "table": False},
        {"do": "craft", "item": "crafting_table", "count": 1, "table": False},
        {"do": "craft", "item": "white_bed", "count": 1, "table": True},
    ],
}
IRON_NO = {
    "ok": False,
    "item": "iron_ingot",
    "count": 1,
    "have": 0,
    "failure": "preciso de picareta de pedra e não consigo fazer: não achei pedra",
    "recipe": {"kind": "smelt", "input": {"item": "raw_iron", "count": 1}},
}


class PlanningMouth(FakeMouth):
    def __init__(self, plans, **kw):
        super().__init__(**kw)
        self.plans = plans
        self.queries: list[dict] = []

    async def query(self, name, args=None):
        self.queries.append({"query": name, **(args or {})})
        return self.plans.get(args["item"])


def test_item_names():
    names = ItemNames()
    assert names.find("lumi faz uma cama pra mim") == [("white_bed", 1)]
    assert names.find("me dá 3 tochas") == [("torch", 3)]
    assert names.find("pega dez de madeira") == [("#logs", 10)]
    assert names.find("quero uma picareta de ferro e um escudo") == [("iron_pickaxe", 1), ("shield", 1)]
    assert names.find("que pena") == []
    assert names.resolve("Picareta de Pedra") == "stone_pickaxe" and names.resolve("white_bed") == "white_bed"
    assert names.name("white_bed") == "cama branca" and names.name("#logs") == "tronco"


def test_reads_the_game_translation(tmp_path):
    lang = {"item.minecraft.iron_ingot": "Lingote de Ferro", "block.minecraft.blue_bed": "Cama Azul", "x.y": "z"}
    (tmp_path / "assets" / "indexes").mkdir(parents=True)
    obj = tmp_path / "assets" / "objects" / "ab"
    obj.mkdir(parents=True)
    (obj / "abcd").write_text(json.dumps(lang), encoding="utf-8")
    index = {"objects": {"minecraft/lang/pt_br.json": {"hash": "abcd"}}}
    (tmp_path / "assets" / "indexes" / "34.json").write_text(json.dumps(index), encoding="utf-8")
    names = load_game_names("pt-BR", tmp_path)
    assert names == {"iron_ingot": "Lingote de Ferro", "blue_bed": "Cama Azul"}
    assert ItemNames(names).find("faz uma cama azul") == [("blue_bed", 1)]
    assert load_game_names("pt-BR", tmp_path / "nada") == {}


def test_plan_text():
    text = describe_plan(BED_PLAN, ItemNames())
    assert "receita: 3 lã branca + 3 tábuas de carvalho (qualquer tipo), na bancada" in text
    assert "você tem 0 (sem bancada perto)" in text
    assert "caçar ovelha até ter 3 lã branca → pegar 2 tronco de carvalho" in text
    assert "[ação: obtain white_bed 1]" in text
    no = describe_plan(IRON_NO, ItemNames())
    assert "assar 1 ferro bruto na fornalha" in no and "NÃO dá" in no and "picareta de pedra" in no


async def test_knows_the_steps_before_answering(lumi):  # noqa: F811
    llm = FakeLLM("bora! só preciso caçar umas ovelhas\n[ação: obtain white_bed 1]")
    mouth = PlanningMouth({"white_bed": BED_PLAN})
    await brain(lumi, llm).on_event(mouth, chat("Vry", "lumi faz uma cama pra mim?"))
    assert mouth.queries == [{"query": "plan", "item": "white_bed", "count": 1}]
    assert "## Do jogo" in llm.calls[0][0]["content"] and "lã branca" in llm.calls[0][0]["content"]
    assert mouth.started == [("obtain", {"item": "white_bed", "count": 1})]


async def test_does_not_promise_what_the_game_says_is_impossible(lumi):  # noqa: F811
    llm = FakeLLM("vou fazer!\n[ação: obtain iron_ingot 1]")
    mouth = PlanningMouth({"iron_ingot": IRON_NO})
    await brain(lumi, llm).on_event(mouth, chat("Vry", "lumi faz ferro"))
    assert mouth.started == []
    assert mouth.said == ["hmm, agora não vai dar: preciso de picareta de pedra e não consigo fazer: não achei pedra"]


async def test_portuguese_item_in_the_action_becomes_an_id(lumi):  # noqa: F811
    llm = FakeLLM("bora\n[ação: obtain tocha 4]")
    mouth = PlanningMouth({"torch": {"ok": True, "item": "torch", "steps": []}})
    await brain(lumi, llm).on_event(mouth, chat("Vry", "lumi, bora explorar?"))
    assert mouth.started == [("obtain", {"item": "torch", "count": 4})]


async def test_query_round_trip(server):  # noqa: F811
    from waifuscam_mind.sim.body import SimulatedBody

    async with SimulatedBody(url(server)) as body:
        await body.handshake()
        [session] = server.sessions
        ask = asyncio.create_task(session.query("plan", {"item": "torch", "count": 2}))
        q = await body.next_of(p.Query)
        assert q.query == "plan" and q.args == {"item": "torch", "count": 2}
        await body.send(p.QueryResult(id=q.id, ok=True, steps=[]))
        assert await ask == {"ok": True, "failure": None, "steps": []}


def test_chest_step_text():
    plan = {
        "ok": True,
        "item": "iron_pickaxe",
        "count": 1,
        "have": 0,
        "steps": [
            {"do": "withdraw", "item": "iron_ingot", "count": 3},
            {"do": "craft", "item": "iron_pickaxe", "count": 1, "table": True},
        ],
        "table_nearby": True,
    }
    text = describe_plan(plan, ItemNames())
    assert "tirar 3 lingote de ferro do baú → craftar 1 picareta de ferro na bancada" in text
    assert "tem bancada perto" in text
