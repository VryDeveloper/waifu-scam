from datetime import date

import pytest

from waifuscam_mind import protocol as p
from waifuscam_mind.brain.chat import ChatBrain, clean_reply, describe_situation, time_of_day
from waifuscam_mind.character import load_character
from waifuscam_mind.llm import AllProvidersFailed, Budget


class FakeLLM:
    def __init__(self, *replies: str, fail: bool = False):
        self.replies = list(replies)
        self.fail = fail
        self.calls: list[list[dict]] = []

    async def chat(self, messages, *, max_tokens=200):
        self.calls.append(messages)
        if self.fail:
            raise AllProvidersFailed("tudo fora")
        return self.replies.pop(0) if self.replies else "ok"


class FakeMouth:
    def __init__(self, players=("Vry",)):
        self.said: list[str] = []
        self.state = p.State(online=True, health=20.0, max_health=20.0, food=18, players=["Lumi", *players])

    async def say(self, text):
        self.said.append(text)


class Clock:
    def __init__(self):
        self.t = 1000.0

    def __call__(self):
        return self.t


async def no_sleep(_):
    return None


@pytest.fixture
def lumi(characters_dir):
    return load_character(characters_dir, "lumi")


def brain(lumi, llm, limit=100, clock=None):
    return ChatBrain(lumi, llm, Budget(limit), clock=clock or Clock(), sleep=no_sleep)


def chat(sender, text):
    return p.Event(event="chat", **{"from": sender, "text": text})


# ---- quando responder -----------------------------------------------------------


async def test_answers_when_alone_with_one_player(lumi):
    llm = FakeLLM("eae vry")
    mouth = FakeMouth(players=("Vry",))
    await brain(lumi, llm).on_event(mouth, chat("Vry", "bora minerar?"))
    assert mouth.said == ["eae vry"]


async def test_in_a_group_only_answers_when_mentioned(lumi):
    llm = FakeLLM("oi!")
    mouth = FakeMouth(players=("Vry", "Ana"))
    b = brain(lumi, llm)
    await b.on_event(mouth, chat("Ana", "vry, cadê o cavalo?"))
    assert mouth.said == [] and llm.calls == []
    await b.on_event(mouth, chat("Ana", "lumi, viu o cavalo?"))
    assert mouth.said == ["oi!"]


async def test_conversation_continues_without_name_then_times_out(lumi):
    clock = Clock()
    llm = FakeLLM("1", "2", "3")
    mouth = FakeMouth(players=("Vry", "Ana"))
    b = brain(lumi, llm, clock=clock)
    await b.on_event(mouth, chat("Ana", "lumi tudo bem?"))
    clock.t += 10
    await b.on_event(mouth, chat("Ana", "e aí, gostou do mundo?"))  # mesma pessoa, logo depois
    await b.on_event(mouth, chat("Vry", "e eu?"))  # outra pessoa, sem citar
    clock.t += 120
    await b.on_event(mouth, chat("Ana", "oi de novo"))  # passou da janela
    assert mouth.said == ["1", "2"]


async def test_does_not_answer_while_out_of_the_world(lumi):
    llm = FakeLLM("x")
    mouth = FakeMouth()
    mouth.state = p.State(online=False)
    await brain(lumi, llm).on_event(mouth, chat("Vry", "lumi?"))
    assert mouth.said == []


# ---- o que ela recebe e devolve -------------------------------------------------


async def test_prompt_has_persona_situation_rules_and_history(lumi):
    llm = FakeLLM("a", "b")
    mouth = FakeMouth()
    b = brain(lumi, llm)
    await b.on_event(mouth, chat("Vry", "oi"))
    await b.on_event(mouth, chat("Vry", "tudo bem?"))
    system, *rest = llm.calls[-1]
    assert system["role"] == "system"
    assert "Você é a Lumi" in system["content"]
    assert "Sua fome: 18/20" in system["content"]
    assert "Online com você: Vry" in system["content"]
    assert "Regras do chat" in system["content"]
    assert rest == [
        {"role": "user", "content": "Vry: oi"},
        {"role": "assistant", "content": "a"},
        {"role": "user", "content": "Vry: tudo bem?"},
    ]


async def test_greets_on_spawn_with_llm_and_falls_back_when_ai_is_down(lumi):
    mouth = FakeMouth()
    await brain(lumi, FakeLLM("cheguei, galera!")).on_event(mouth, p.Event(event="spawned"))
    await brain(lumi, FakeLLM(fail=True)).on_event(mouth, p.Event(event="spawned"))
    assert mouth.said == ["cheguei, galera!", "oi gente! cheguei"]


async def test_death_comment_has_cooldown(lumi):
    clock = Clock()
    llm = FakeLLM("aff", "de novo?!")
    mouth = FakeMouth()
    b = brain(lumi, llm, clock=clock)
    await b.on_event(mouth, p.Event(event="died", message="Lumi was blown up by Creeper"))
    await b.on_event(mouth, p.Event(event="died", message="Lumi drowned"))
    clock.t += 30
    await b.on_event(mouth, p.Event(event="died", message="Lumi fell"))
    assert mouth.said == ["aff", "de novo?!"]
    assert "blown up by Creeper" in llm.calls[0][-1]["content"]


async def test_budget_runs_out_with_one_notice(lumi):
    llm = FakeLLM("um")
    mouth = FakeMouth()
    b = brain(lumi, llm, limit=1)
    for text in ("oi", "oi?", "alô?"):
        await b.on_event(mouth, chat("Vry", text))
    assert mouth.said[0] == "um"
    assert len(mouth.said) == 2 and "energia" in mouth.said[1]
    assert len(llm.calls) == 1


# ---- utilitários ---------------------------------------------------------------


def test_clean_reply():
    assert clean_reply("Lumi: oi gente", ["Lumi"]) == ["oi gente"]
    assert clean_reply("<Lumi> **bora!**", ["Lumi"]) == ["bora!"]
    assert clean_reply('"vamo nessa"\n\n\nlinha 2\nlinha 3', ["Lumi"]) == ["vamo nessa", "linha 2"]
    long = clean_reply("palavra " * 100, ["Lumi"])[0]
    assert len(long) <= 243 and long.endswith("...")


def test_time_and_situation():
    assert time_of_day(None) is None
    assert time_of_day(3000) == "manhã" and time_of_day(18000) == "noite" and time_of_day(24000 + 9000) == "tarde"
    text = describe_situation(
        p.State(online=True, health=7.0, time_of_day=12500, weather="rain", dimension="minecraft:the_nether"), "Lumi"
    )
    assert "7/20" in text and "anoitecendo" in text and "chovendo" in text and "Nether" in text
    assert "ainda não está" in describe_situation(None, "Lumi")


def test_budget_resets_next_day_and_persists(tmp_path):
    day = [date(2026, 10, 19)]
    b = Budget(2, tmp_path / "b.json", today=lambda: day[0])
    assert b.try_spend() and b.try_spend() and not b.try_spend()
    assert Budget(2, tmp_path / "b.json", today=lambda: day[0]).remaining == 0  # sobreviveu ao reinício
    day[0] = date(2026, 10, 20)
    assert b.try_spend() and b.remaining == 1
