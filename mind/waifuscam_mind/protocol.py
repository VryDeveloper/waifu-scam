"""Protocolo Corpo ↔ Mente, versão 1. Espelha protocol/v1.schema.json.

Toda mensagem tem `v`, `bot` (id da personagem; null antes do welcome) e `type`.
Campos extras são aceitos e preservados, para que um Corpo mais novo não quebre
uma Mente mais velha.
"""

from __future__ import annotations

import json
from typing import Any, Literal

from pydantic import BaseModel, ConfigDict, ValidationError

PROTOCOL_VERSION = 1


class ProtocolError(ValueError):
    pass


class Envelope(BaseModel):
    model_config = ConfigDict(extra="allow")

    v: Literal[1] = PROTOCOL_VERSION
    bot: str | None = None
    type: str


# ---- Corpo → Mente ---------------------------------------------------------


class Hello(Envelope):
    type: Literal["hello"] = "hello"
    protocol: int
    body: str
    body_version: str
    minecraft: str | None = None


class Event(Envelope):
    type: Literal["event"] = "event"
    event: str


# ---- Mente → Corpo ---------------------------------------------------------


class CharacterInfo(BaseModel):
    id: str
    name: str
    language: str


class Welcome(Envelope):
    type: Literal["welcome"] = "welcome"
    character: CharacterInfo


class ErrorMsg(Envelope):
    type: Literal["error"] = "error"
    message: str


# ---- ambos os sentidos -----------------------------------------------------


class Ping(Envelope):
    type: Literal["ping"] = "ping"
    id: str
    t: int | None = None


class Pong(Envelope):
    type: Literal["pong"] = "pong"
    id: str


_BY_TYPE: dict[str, type[Envelope]] = {
    "hello": Hello,
    "event": Event,
    "welcome": Welcome,
    "error": ErrorMsg,
    "ping": Ping,
    "pong": Pong,
}


def parse(raw: str | bytes) -> Envelope:
    """Valida o JSON e devolve o modelo do tipo certo (ou um Envelope genérico)."""
    try:
        data: Any = json.loads(raw)
    except json.JSONDecodeError as e:
        raise ProtocolError(f"JSON inválido: {e}") from e
    if not isinstance(data, dict):
        raise ProtocolError("mensagem precisa ser um objeto JSON")
    if data.get("v") != PROTOCOL_VERSION:
        raise ProtocolError(f"versão de protocolo não suportada (esperado v{PROTOCOL_VERSION})")
    model = _BY_TYPE.get(data.get("type"), Envelope)
    try:
        return model.model_validate(data)
    except ValidationError as e:
        raise ProtocolError(str(e)) from e


def dump(msg: Envelope) -> str:
    return msg.model_dump_json()
