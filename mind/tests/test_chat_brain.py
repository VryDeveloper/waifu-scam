from datetime import date

import pytest

from waifuscam_mind import protocol as p
from waifuscam_mind.brain.chat import ChatBrain, clean_reply, describe_situation, time_of_day
from waifuscam_mind.character import load_character
from waifuscam_mind.llm import AllProvidersFailed, Budget
from waifuscam_mind.sim.body import SIM_SKILLS


class FakeLLM:
    def __init__(self, *replies: str, fail: bool = False):
        self.replies = list(replies)
        self.fail = fail
        self.calls: list[list[dict]] = []

    async def chat(self, messages, *, max_tokens=200, background=False):
        self.calls.append(messages)
        if self.fail:
            raise AllProvidersFailed("tudo fora")
        return self.replies.pop(0) if self.replies else "ok"


class FakeMouth:
    def __init__(self, players=("Vry",), skills=SIM_SKILLS):
        self.said: list[str] = []
        self.started: list[tuple[str, dict]] = []
        self.skills = list(skills)
        self.state = p.State(online=True, health=20.0, max_health=20.0, food=18, players=["Lumi", *players])

    async def say(self, text):
        self.said.append(text)

    async def start_skill(self, name, args=None):
        self.started.append((name, args or {}))
        return f"mind-{len(self.started)}"

    async def cancel_skill(self):
        self.started.append(("cancel", {}))


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
    assert "## Regras" in system["content"]
    assert rest == [
        {"role": "user", "content": "Vry: oi"},
        {"role": "assistant", "content": "a"},
        {"role": "user", "content": "Vry: tudo bem?"},
    ]


async def test_greets_on_spawn_with_llm_and_falls_back_when_ai_is_down(lumi):
    mouth = FakeMouth()
    await brain(lumi, FakeLLM("cheguei, galera!")).on_event(mouth, p.Event(event="spawned"))
    await brain(lumi, FakeLLM(fail=True)).on_event(mouth, p.Event(event="spawned"))
    assert mouth.said == ["cheguei, galera!", "oii, cheguei hehe"]


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


# ---- fase 3: ações -------------------------------------------------------------


async def test_reply_with_action_starts_skill_and_hides_the_tag(lumi):
    llm = FakeLLM("bora, tô indo\n[ação: follow]")
    mouth = FakeMouth()
    b = brain(lumi, llm)
    await b.on_event(mouth, chat("Vry", "me segue"))
    assert mouth.started == [("follow", {"player": "Vry"})]
    assert mouth.said == ["bora, tô indo"]
    assert b.history[-1] == {"role": "assistant", "content": "bora, tô indo\n[ação: follow Vry]"}
    system = llm.calls[0][0]["content"]
    assert "## Ações" in system and "[ação: follow <player>]" in system


async def test_refusing_means_no_action(lumi):
    mouth = FakeMouth()
    await brain(lumi, FakeLLM("agora não, tô com fome")).on_event(mouth, chat("Vry", "me segue"))
    assert mouth.started == [] and mouth.said == ["agora não, tô com fome"]


async def test_action_without_words_still_acts(lumi):
    mouth = FakeMouth()
    await brain(lumi, FakeLLM("[ação: stop]")).on_event(mouth, chat("Vry", "deixa isso quieto um pouco"))
    assert mouth.started == [("stop", {})] and mouth.said == []


async def test_old_body_without_catalog_gets_no_actions_section(lumi):
    llm = FakeLLM("oi\n[ação: follow]")
    mouth = FakeMouth(skills=())
    await brain(lumi, llm).on_event(mouth, chat("Vry", "me segue"))
    assert mouth.started == [] and mouth.said == ["oi"]
    assert "## Ações" not in llm.calls[0][0]["content"]


async def test_situation_says_what_she_is_doing(lumi):
    state = p.State(online=True, skill={"id": "mind-1", "name": "follow", "doing": "seguindo Vry"})
    assert "Agora você está: seguindo Vry" in describe_situation(state, "Lumi")
    assert "parada" in describe_situation(p.State(online=True), "Lumi")


async def test_comments_on_real_failures_only(lumi):
    clock = Clock()
    llm = FakeLLM("aff, a Ana nem tá aqui", "travei")
    mouth = FakeMouth()
    b = brain(lumi, llm, clock=clock)
    for i in range(1, 5):  # todas pedidas pelo Vry
        b._started[f"mind-{i}"] = ("follow", {"player": "Ana"}, "Vry")
    b._started["mind-9"] = ("obtain", {"item": "furnace"}, None)  # objetivo dela: falha em silêncio
    await b.on_skill_ended(mouth, p.SkillFailed(id="mind-9", skill="obtain", reason="sem pedra"))
    await b.on_skill_ended(mouth, p.SkillFailed(id="mind-1", skill="follow", reason="Ana não está online"))
    await b.on_skill_ended(mouth, p.SkillFailed(id="mind-2", skill="follow", reason="mandaram parar", cancelled=True))
    await b.on_skill_ended(mouth, p.SkillFailed(id="mind-3", skill="goto", reason="fiquei presa"))  # cooldown
    await b.on_skill_ended(mouth, p.SkillDone(id="mind-4", skill="goto"))
    assert mouth.said == ["aff, a Ana nem tá aqui"]
    assert "Ana não está online" in llm.calls[0][-1]["content"]
    notes = [m["content"] for m in b.history if m["role"] == "user"]
    assert "[follow parou: mandaram parar]" in notes and "[você terminou: goto]" in notes


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


def test_situation_lists_inventory():
    state = p.State(online=True, inventory={"oak_log": 12, "stone_pickaxe": 1}, holding="stone_pickaxe")
    text = describe_situation(state, "Lumi")
    assert "Seu inventário: 12 oak_log, 1 stone_pickaxe" in text and "Na mão: stone_pickaxe" in text
    assert "inventário está vazio" in describe_situation(p.State(online=True, inventory={}), "Lumi")


async def test_me_da_becomes_give(lumi):
    mouth = FakeMouth()
    b = brain(lumi, FakeLLM("vou pegar as tochas\n[ação: obtain torch 4]"))
    await b.on_event(mouth, chat("Vry", "lumi me dá 4 tochas"))
    assert mouth.started == [("give", {"player": "Vry", "item": "torch", "count": 4})]
    mouth2 = FakeMouth()
    b2 = brain(lumi, FakeLLM("bora\n[ação: obtain torch 4]"))
    await b2.on_event(mouth2, chat("Vry", "lumi faz 4 tochas"))
    assert mouth2.started == [("obtain", {"item": "torch", "count": 4})]


async def test_promise_without_action_is_repaired(lumi):
    llm = FakeLLM("bora, tô indo", "[ação: follow Vry]")
    mouth = FakeMouth()
    await brain(lumi, llm).on_event(mouth, chat("Vry", "lumi vem comigo, me segue"))
    assert mouth.started == [("follow", {"player": "Vry"})] and mouth.said == ["bora, tô indo"]
    assert "Sua resposta: bora, tô indo" in llm.calls[1][-1]["content"]


async def test_impossible_promise_becomes_honest(lumi):
    llm = FakeLLM("coloquei a fornalha no chão", "isso eu ainda não sei fazer, foi mal")
    mouth = FakeMouth()
    await brain(lumi, llm).on_event(mouth, chat("Vry", "lumi coloca a fornalha no chão"))
    assert mouth.started == [] and mouth.said == ["isso eu ainda não sei fazer, foi mal"]


async def test_small_talk_is_not_repaired(lumi):
    llm = FakeLLM("vou ficar de boa aqui kkk")
    mouth = FakeMouth()
    await brain(lumi, llm).on_event(mouth, chat("Vry", "tudo bem com vc?"))
    assert mouth.said == ["vou ficar de boa aqui kkk"] and mouth.started == []  # a conferência disse OK


async def test_spontaneous_promise_is_also_checked(lumi):
    llm = FakeLLM("vou montar um abrigo pra gente", "[ação: shelter]")
    mouth = FakeMouth()
    await brain(lumi, llm).on_event(mouth, chat("Vry", "e agora?"))
    assert mouth.started == [("shelter", {})]


def test_glued_repetition_is_cleaned():
    assert clean_reply("não deu, preciso de fornalha.não deu, preciso de fornalha.", ["Lumi"]) == [
        "não deu, preciso de fornalha."
    ]
    assert clean_reply("oi\noi", ["Lumi"]) == ["oi"]


async def test_stop_works_right_away_even_without_ai(lumi):
    mouth = FakeMouth()
    b = brain(lumi, FakeLLM(fail=True))
    await b.on_event(mouth, chat("Vry", "lumi para"))
    assert mouth.started == [("cancel", {})] and mouth.said == ["tá, parei"]


async def test_follow_without_ai_still_follows(lumi):
    mouth = FakeMouth()
    await brain(lumi, FakeLLM(fail=True)).on_event(mouth, chat("Vry", "lumi me segue"))
    assert mouth.started == [("follow", {"player": "Vry"})] and mouth.said == ["bora, tô indo atrás de você"]


async def test_busy_notice_when_ai_is_out(lumi):
    mouth = FakeMouth()
    await brain(lumi, FakeLLM(fail=True)).on_event(mouth, chat("Vry", "lumi, o que você acha dessa casa?"))
    assert mouth.said == ["pera, minha cabeça deu uma travada. me fala de novo daqui a pouquinho?"]
