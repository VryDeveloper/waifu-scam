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
# Promessa: a mesma ação, mas para depois ("[depois: obtain stone_pickaxe 1]").
LATER_LINE = re.compile(r"\[\s*depois\s*:\s*([a-z_][\w.]*)\s*([^\]]*)\]", re.IGNORECASE)
NUMBER = re.compile(r"^-?\d+(\.\d+)?$")
PLAYER_NAME = re.compile(r"^\w{1,16}$")


@dataclass(frozen=True)
class Action:
    skill: str
    args: dict[str, Any] = field(default_factory=dict)


XYZ = ("x", "y", "z")
PRONOUNS = {"me", "mim", "eu", "você", "voce", "vc", "player", "comigo"}


def _kind(spec: p.SkillSpec, name: str) -> str:
    prop = (spec.params.get("properties") or {}).get(name) or {}
    return str(prop.get("type", "string"))


def usage(spec: p.SkillSpec) -> str:
    """Como a IA escreve essa ação, a partir dos parâmetros do catálogo (na ordem deles)."""
    names = spec.arg_names
    rest = [n for n in names if n not in XYZ]
    forms: list[str] = []
    if rest:
        forms.append(" ".join([f"[ação: {spec.name}", *(f"<{n}>" for n in rest)]) + "]")
    if all(c in names for c in XYZ):
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
        "Sem nome de player, a ação vale para quem falou com você. Itens vão pelo id em inglês do Minecraft",
        "(oak_log, cobblestone, stone_pickaxe, torch, bread), e a quantidade é um número.",
        "Nunca escreva a linha da ação no meio da frase, e não explique a ação: só fale normalmente e coloque a linha.",
        "Para ADIAR um pedido (você topa, mas agora não dá: está com fome, é noite, está ocupada), use a mesma",
        "ação com 'depois' no lugar de 'ação', ex.: [depois: obtain stone_pickaxe 1]. Você faz quando ficar livre.",
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


def _player(token: str, players: list[str]) -> str:
    """Corrige a caixa do nome. Se a pessoa não estiver online, o corpo recusa e ela fica sabendo."""
    for name in players:
        if name.lower() == token.lower():
            return name
    return token


def parse_args(spec: p.SkillSpec, raw: str, speaker: str | None, players: list[str]) -> dict[str, Any] | None:
    """Transforma "Vry", "10 64 -3" ou "Vry torch 4" nos argumentos do catálogo. None se não fizer sentido."""
    names = spec.arg_names
    tokens = [t.strip(" ,<>@\"'") for t in raw.replace(",", " ").split()]
    tokens = [t for t in tokens if t]

    if any(t[0] in "~^" for t in tokens):
        return None  # coordenada relativa não dá para resolver daqui
    if all(c in names for c in XYZ) and len(tokens) == 3 and all(NUMBER.match(t) for t in tokens):
        x, y, z = (float(t) for t in tokens)
        return {"x": x, "y": y, "z": z}

    rest = [n for n in names if n not in XYZ]
    required = set(spec.params.get("required") or [])
    online = {n.lower() for n in players}
    args: dict[str, Any] = {}

    # 1) quem: nome de quem está online, "me"/"mim", ou o único texto da ação
    texts = [n for n in rest if _kind(spec, n) == "string" and n != "player"]
    if "player" in rest:
        first = tokens[0] if tokens else None
        if first is not None and first.lower() in PRONOUNS:
            tokens.pop(0)
        elif first is not None and (first.lower() in online or not texts):
            if NUMBER.match(first) or not PLAYER_NAME.match(first):
                return None  # coordenada pela metade ou nome que não existe no Minecraft
            args["player"] = _player(tokens.pop(0), players)
        if "player" not in args and speaker:
            args["player"] = speaker

    # 2) números vão para os parâmetros numéricos, na ordem
    numbers = [n for n in rest if _kind(spec, n) in ("integer", "number")]
    words: list[str] = []
    for t in tokens:
        if NUMBER.match(t) and numbers:
            name = numbers.pop(0)
            args[name] = int(float(t)) if _kind(spec, name) == "integer" else float(t)
        else:
            words.append(t)

    # 3) o resto é o texto (ex.: o item); "picareta de pedra" vira picareta_de_pedra
    if words:
        if not texts:
            return None
        args[texts[0]] = "_".join(words)

    if any(r not in args for r in required):
        return None
    return {n: args[n] for n in names if n in args}  # na ordem do catálogo


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


def resolve_later(
    reply: str, skills: list[p.SkillSpec], speaker: str | None, players: list[str]
) -> tuple[str, Action | None]:
    """Como resolve(), mas para a linha [depois: ...] (promessa)."""
    matches = list(LATER_LINE.finditer(reply))
    if not matches:
        return reply, None
    text = LATER_LINE.sub("", reply)
    last = matches[-1]
    spec = next((s for s in skills if s.name == last.group(1).lower()), None)
    if spec is None:
        return text, None
    args = parse_args(spec, last.group(2).strip(), speaker, players)
    return text, (Action(spec.name, args) if args is not None else None)
