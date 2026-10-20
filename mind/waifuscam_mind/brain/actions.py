"""Ações que a IA pode escolher junto com a fala.

A IA responde com a mensagem do chat e, se quiser agir, uma linha a mais no fim:

    bora, tô indo
    [ação: follow Vry]

Uma linha com colchetes funciona até com modelos pequenos, que costumam errar
JSON. A lista de ações vem do catálogo que o corpo manda no hello, então uma
habilidade nova no mod aparece aqui sem mexer na Mente.
"""

from __future__ import annotations

import re
from dataclasses import dataclass, field
from typing import Any

from .. import protocol as p

ACTION_LINE = re.compile(r"\[\s*a[çc][ãa]o\s*:\s*([a-z_][\w.]*)\s*([^\]]*)\]", re.IGNORECASE)
NUMBER = re.compile(r"^-?\d+(\.\d+)?$")
PLAYER_NAME = re.compile(r"^\w{1,16}$")


@dataclass(frozen=True)
class Action:
    skill: str
    args: dict[str, Any] = field(default_factory=dict)


def usage(spec: p.SkillSpec) -> str:
    """Como a IA escreve essa ação, a partir dos parâmetros do catálogo."""
    names = spec.arg_names
    forms: list[str] = []
    if "player" in names:
        forms.append(f"[ação: {spec.name} <player>]")
    if {"x", "y", "z"} <= set(names):
        forms.append(f"[ação: {spec.name} <x> <y> <z>]")
    if not forms:
        forms.append(f"[ação: {spec.name}]")
    return " ou ".join(forms)


def describe_actions(skills: list[p.SkillSpec]) -> str:
    if not skills:
        return ""
    lines = [
        "## Ações",
        "Além de falar, você consegue agir no jogo. Para agir, coloque UMA linha no fim da resposta só com a ação:",
    ]
    lines += [f"- {usage(s)}: {s.description}" for s in skills]
    lines += [
        "Use no máximo uma ação por resposta e só quando você decidir fazer aquilo agora",
        "(alguém pediu e você topou, ou você quer). Se recusar ou adiar, não coloque ação.",
        "Sem nome de player, a ação vale para quem falou com você.",
        "Nunca escreva a linha da ação no meio da frase, e não explique a ação: só fale normalmente e coloque a linha.",
    ]
    return "\n".join(lines)


def split_action(reply: str) -> tuple[str, str | None, str]:
    """Separa a fala da ação. Devolve (fala, nome da ação ou None, argumentos crus)."""
    matches = list(ACTION_LINE.finditer(reply))
    if not matches:
        return reply, None, ""
    text = ACTION_LINE.sub("", reply)
    last = matches[-1]  # se vier mais de uma, vale a última
    return text, last.group(1).lower(), last.group(2).strip()


def parse_args(spec: p.SkillSpec, raw: str, speaker: str | None, players: list[str]) -> dict[str, Any] | None:
    """Transforma "Vry" ou "10 64 -3" nos argumentos do catálogo. None se não fizer sentido."""
    names = set(spec.arg_names)
    tokens = [t.strip(" ,<>@\"'") for t in raw.replace(",", " ").split()]
    tokens = [t for t in tokens if t]

    if any(t[0] in "~^" for t in tokens):
        return None  # coordenada relativa não dá para resolver daqui
    if {"x", "y", "z"} <= names and len(tokens) == 3 and all(NUMBER.match(t) for t in tokens):
        x, y, z = (float(t) for t in tokens)
        return {"x": x, "y": y, "z": z}

    if "player" in names:
        wanted = tokens[0] if tokens else None
        if wanted and (NUMBER.match(wanted) or not PLAYER_NAME.match(wanted)):
            return None  # coordenada pela metade ou nome que não existe no Minecraft
        if wanted and wanted.lower() not in {"me", "mim", "eu", "você", "voce", "vc", "player"}:
            # Corrige a caixa do nome. Se a pessoa não estiver online, o corpo
            # recusa e ela fica sabendo o motivo.
            for name in players:
                if name.lower() == wanted.lower():
                    return {"player": name}
            return {"player": wanted}
        return {"player": speaker} if speaker else None

    return {}


def resolve(
    reply: str, skills: list[p.SkillSpec], speaker: str | None, players: list[str]
) -> tuple[str, Action | None]:
    """Fala sem a linha da ação, e a ação validada contra o catálogo (ou None)."""
    text, name, raw = split_action(reply)
    if name is None:
        return text, None
    spec = next((s for s in skills if s.name == name), None)
    if spec is None:
        return text, None
    args = parse_args(spec, raw, speaker, players)
    if args is None:
        return text, None
    return text, Action(spec.name, args)
