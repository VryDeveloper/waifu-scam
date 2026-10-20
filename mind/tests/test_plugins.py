"""Fase 7: plugins da Mente."""

from waifuscam_mind import protocol as p
from waifuscam_mind.brain.chat import ChatBrain
from waifuscam_mind.character import load_character
from waifuscam_mind.llm import Budget
from waifuscam_mind.plugins import Plugin, Plugins, load_plugins

from .test_chat_brain import Clock, FakeLLM, FakeMouth, no_sleep


class Censura(Plugin):
    name = "censura"

    def __init__(self):
        self.events = []

    async def on_event(self, mouth, event):
        self.events.append(event.event)

    def prompt(self, state):
        return "## Live\n- 42 pessoas assistindo"

    def on_say(self, text):
        return None if "palavrão" in text else text.upper()


class Quebrado(Plugin):
    name = "quebrado"

    def prompt(self, state):
        raise RuntimeError("bug")


async def test_hooks_change_prompt_and_speech(characters_dir):
    lumi = load_character(characters_dir, "lumi")
    plugin = Censura()
    llm = FakeLLM("oi gente\npalavrão aqui")
    b = ChatBrain(lumi, llm, Budget(10), clock=Clock(), sleep=no_sleep, plugins=Plugins([plugin, Quebrado()]))
    mouth = FakeMouth()
    await b.on_event(mouth, p.Event(event="chat", **{"from": "Vry", "text": "oi"}))
    assert mouth.said == ["OI GENTE"]  # a segunda linha foi calada
    assert "42 pessoas assistindo" in llm.calls[0][0]["content"]
    assert plugin.events == ["chat"]
    assert [pl.name for pl in b.plugins.items] == ["censura"]  # o quebrado foi desligado


def test_loads_example_plugin_from_folder(characters_dir):
    lumi = load_character(characters_dir, "lumi")
    [relogio] = load_plugins(["relogio"], lumi)
    assert "Mundo real" in relogio.prompt(None)
    assert load_plugins(["nao_existe"], lumi) == []
