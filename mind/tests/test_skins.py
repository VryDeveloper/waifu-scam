import json

import httpx
import pytest

from waifuscam_mind.character import Character
from waifuscam_mind.skins import MINESKIN_GENERATE, SkinResolver


def character(tmp_path, skin: dict) -> Character:
    return Character(
        id="t", name="T", language="pt-BR", username="Tester", persona_template="", root=tmp_path, config={"skin": skin}
    )


def mojang_handler(calls: list):
    def handle(request: httpx.Request) -> httpx.Response:
        calls.append(str(request.url))
        if "api.mojang.com" in request.url.host:
            if request.url.path.endswith("/Ninguem"):
                return httpx.Response(404)
            return httpx.Response(200, json={"id": "abc123", "name": "Notch"})
        return httpx.Response(200, json={"properties": [{"name": "textures", "value": "VAL", "signature": "SIG"}]})

    return handle


async def test_username_skin_is_fetched_and_cached(tmp_path):
    calls: list = []
    client = httpx.AsyncClient(transport=httpx.MockTransport(mojang_handler(calls)))
    resolver = SkinResolver(tmp_path / "cache.json", client=client)
    c = character(tmp_path, {"username": "Notch"})

    skin = await resolver.resolve(c)
    assert (skin.value, skin.signature) == ("VAL", "SIG")
    assert len(calls) == 2

    again = await resolver.resolve(c)
    assert again == skin and len(calls) == 2  # veio do cache


async def test_unknown_account_falls_back_to_default(tmp_path):
    client = httpx.AsyncClient(transport=httpx.MockTransport(mojang_handler([])))
    resolver = SkinResolver(tmp_path / "cache.json", client=client)
    assert await resolver.resolve(character(tmp_path, {"username": "Ninguem"})) is None


async def test_file_skin_without_key_is_skipped(tmp_path):
    (tmp_path / "skin.png").write_bytes(b"\x89PNG fake")
    resolver = SkinResolver(tmp_path / "cache.json", mineskin_key="")
    assert await resolver.resolve(character(tmp_path, {"file": "skin.png"})) is None


async def test_file_skin_is_uploaded_once(tmp_path):
    (tmp_path / "skin.png").write_bytes(b"\x89PNG fake")
    seen: list[httpx.Request] = []

    def handle(request: httpx.Request) -> httpx.Response:
        seen.append(request)
        assert str(request.url) == MINESKIN_GENERATE
        assert request.headers["Authorization"] == "Bearer msk_test"
        body = request.read()
        assert b'name="variant"' in body and b"slim" in body and b'name="file"' in body
        return httpx.Response(200, json={"skin": {"texture": {"data": {"value": "V2", "signature": "S2"}}}})

    client = httpx.AsyncClient(transport=httpx.MockTransport(handle))
    resolver = SkinResolver(tmp_path / "cache.json", mineskin_key="msk_test", client=client)
    c = character(tmp_path, {"file": "skin.png", "model": "slim"})

    assert (await resolver.resolve(c)).value == "V2"
    assert (await resolver.resolve(c)).value == "V2"
    assert len(seen) == 1
    assert "V2" in json.dumps(json.loads((tmp_path / "cache.json").read_text()))


@pytest.mark.parametrize("status", [429, 500])
async def test_mineskin_errors_do_not_crash(tmp_path, status):
    (tmp_path / "skin.png").write_bytes(b"x")
    client = httpx.AsyncClient(transport=httpx.MockTransport(lambda r: httpx.Response(status)))
    resolver = SkinResolver(tmp_path / "cache.json", mineskin_key="k", client=client)
    assert await resolver.resolve(character(tmp_path, {"file": "skin.png"})) is None


async def test_no_skin_configured(tmp_path):
    assert await SkinResolver(tmp_path / "c.json").resolve(character(tmp_path, {"model": "slim"})) is None
