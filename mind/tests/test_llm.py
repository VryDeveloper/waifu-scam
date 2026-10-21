from types import SimpleNamespace

import openai
import pytest

from waifuscam_mind.llm import AllProvidersFailed, LLMClient, Provider, load_providers

KEYS = ["GROQ_API_KEY", "GEMINI_API_KEY", "OPENROUTER_API_KEY", "CUSTOM_API_KEY", "CUSTOM_BASE_URL", "GROQ_MODEL"]


@pytest.fixture(autouse=True)
def clean_env(monkeypatch):
    for k in KEYS:
        monkeypatch.delenv(k, raising=False)


def test_skips_providers_without_key(monkeypatch):
    monkeypatch.setenv("GEMINI_API_KEY", "g")
    assert [p.name for p in load_providers()] == ["gemini"]


def test_order_model_override_and_reasoning_flag(monkeypatch):
    monkeypatch.setenv("GROQ_API_KEY", "a")
    monkeypatch.setenv("GEMINI_API_KEY", "b")
    gem, groq = load_providers(["gemini", "groq"])
    assert (gem.name, groq.name) == ("gemini", "groq")
    assert groq.extra_body == {"reasoning_effort": "low"} and gem.extra_body == {}
    [only] = load_providers(["groq"], model_override="llama-3.1-8b-instant")
    assert only.model == "llama-3.1-8b-instant" and only.extra_body == {}


def test_unknown_provider_is_an_error():
    with pytest.raises(ValueError):
        load_providers(["chatgpt-gratis"])


def _reply(text):
    return SimpleNamespace(choices=[SimpleNamespace(message=SimpleNamespace(content=text))])


class _RateLimited(openai.RateLimitError):
    def __init__(self):
        Exception.__init__(self, "limite")


async def test_falls_back_and_moves_limited_provider_to_the_end():
    llm = LLMClient([Provider(n, "https://x.invalid/v1", "k", "m") for n in ("a", "b")])

    async def limited(**_):
        raise _RateLimited()

    async def ok(**_):
        return _reply("  oi  ")

    llm._clients["a"].chat.completions.create = limited
    llm._clients["b"].chat.completions.create = ok
    assert await llm.chat([{"role": "user", "content": "x"}]) == "oi"
    assert [p.name for p in llm._ordered()] == ["b", "a"]


async def test_all_failing_raises():
    llm = LLMClient([Provider("a", "https://x.invalid/v1", "k", "m")])

    async def empty(**_):
        return _reply("")

    llm._clients["a"].chat.completions.create = empty
    with pytest.raises(AllProvidersFailed):
        await llm.chat([{"role": "user", "content": "x"}])


async def test_waits_for_the_rate_limit_in_chat_but_not_in_background(monkeypatch):
    import waifuscam_mind.llm.client as client

    slept = []

    async def fake_sleep(s):
        slept.append(s)

    monkeypatch.setattr(client.asyncio, "sleep", fake_sleep)
    llm = LLMClient([Provider("a", "https://x.invalid/v1", "k", "big", background_model="small")])
    calls = []

    async def flaky(**kw):
        calls.append(kw["model"])
        if len(calls) == 1:
            raise _RateLimited()
        return _reply("oi")

    llm._clients["a"].chat.completions.create = flaky
    assert await llm.chat([{"role": "user", "content": "x"}]) == "oi"
    assert slept == [5.0] and calls == ["big", "big"]

    calls.clear()

    async def always_limited(**kw):
        calls.append(kw["model"])
        raise _RateLimited()

    llm._clients["a"].chat.completions.create = always_limited
    with pytest.raises(AllProvidersFailed):
        await llm.chat([{"role": "user", "content": "x"}], background=True)
    assert calls == ["small"]  # fundo usa o modelo pequeno e não espera


def test_groq_gets_a_background_model(monkeypatch):
    monkeypatch.setenv("GROQ_API_KEY", "a")
    [groq] = load_providers(["groq"])
    assert groq.model == "openai/gpt-oss-120b" and groq.model_for(True) == "openai/gpt-oss-20b"
