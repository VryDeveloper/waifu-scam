import json

import pytest

from waifuscam_mind import protocol as p


def test_parses_hello_into_typed_model():
    raw = json.dumps(
        {
            "v": 1,
            "bot": None,
            "type": "hello",
            "protocol": 1,
            "body": "fabric",
            "body_version": "0.1.0",
            "minecraft": "26.3",
        }
    )
    msg = p.parse(raw)
    assert isinstance(msg, p.Hello) and msg.minecraft == "26.3"


def test_unknown_type_is_kept_as_generic_envelope_with_extras():
    msg = p.parse('{"v":1,"bot":"lumi","type":"skill.progress","done":3}')
    assert type(msg) is p.Envelope
    assert msg.model_dump()["done"] == 3


def test_event_keeps_free_fields():
    msg = p.parse('{"v":1,"bot":"lumi","type":"event","event":"chat","from":"Vry","text":"oi"}')
    assert isinstance(msg, p.Event)
    assert msg.model_dump()["from"] == "Vry"


@pytest.mark.parametrize("raw", ["nada", "[1]", '{"v":2,"type":"hello"}', '{"v":1,"type":"ping"}'])
def test_rejects_invalid(raw):
    with pytest.raises(p.ProtocolError):
        p.parse(raw)


def test_dump_roundtrip():
    out = p.dump(p.Pong(bot="lumi", id="x"))
    assert json.loads(out) == {"v": 1, "bot": "lumi", "type": "pong", "id": "x"}
