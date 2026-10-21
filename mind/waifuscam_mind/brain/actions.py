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


# Dica curta de cada habilidade conhecida (o prompt fica pequeno; a descrição longa do corpo é o padrão).
HINTS = {
    "follow": "seguir alguém",
    "goto": "ir até alguém, um lugar lembrado ou coordenadas",
    "obtain": "ficar com n do item; minera, crafta e funde sozinha (ex.: obtain stone_pickaxe 1)",
    "craft": "craftar mais n do item",
    "collect": "minerar mais n de um recurso (#logs, cobblestone, coal...)",
    "smelt": "fundir/assar até ter mais n do item (iron_ingot, cooked_beef)",
    "mine": "quebrar n blocos de um tipo por perto (inclusive colocados)",
    "place": "colocar um bloco do inventário no chão perto",
    "drop": "jogar itens no chão",
    "pickup": "pegar itens caídos por perto",
    "use": "clicar com o botão direito num bloco perto (porta, alavanca, botão, baú)",
    "eat": "comer",
    "give": "entregar itens para alguém (consegue antes se faltar)",
    "deposit": "guardar no baú (sem item: tudo menos ferramenta, arma, armadura e comida)",
    "withdraw": "pegar do baú",
    "build": "construir uma casinha 5x5 com porta (build house, ou build house_cobblestone); junta o material",
    "bridge": "fazer uma ponte reta para onde quem pediu está olhando, até o outro lado (rio, buraco, lava)",
    "cook": "fazer comida: assa toda a comida crua que tiver (porkchop, beef...); sem nada cru, arruma comida",
    "mine_ores": "ajudar a minerar: quebrar os minérios à vista por perto e o veio deles",
    "farm": "cuidar da plantação: colher o maduro, replantar, plantar (com enxada, ara perto da água)",
    "hunt": "caçar cow, pig, sheep, chicken, rabbit",
    "attack": "atacar: um tipo de bicho (iron_golem, zombie) ou o bicho que um player está atacando",
    "shelter": "passar a noite num abrigo",
    "sleep": "dormir na cama",
    "stop": "parar tudo",
}


def _hint(spec: p.SkillSpec) -> str:
    if spec.name in HINTS:
        return HINTS[spec.name]
    first = spec.description.split(". ")[0]
    return first[:80]


def describe_actions(skills: list[p.SkillSpec]) -> str:
    if not skills:
        return ""
    lines = ["## Ações (no fim da resposta, UMA linha só, e só se for fazer agora)"]
    lines += [f"- {usage(s)}: {_hint(s)}" for s in skills]
    lines += [
        "Sem player, vale para quem falou com você. Itens pelo id em inglês (oak_log, torch); quantidade é número.",
        "Para adiar, troque 'ação' por 'depois': [depois: obtain stone_pickaxe 1].",
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
        # Nome de quem está online em qualquer posição ("attack golem Vry").
        for i, t in enumerate(tokens):
            if i > 0 and t.lower() in online and t.lower() not in PRONOUNS:
                args["player"] = _player(tokens.pop(i), players)
                break
        first = tokens[0] if tokens and "player" not in args else None
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


def resolve_all(
    reply: str, skills: list[p.SkillSpec], speaker: str | None, players: list[str]
) -> tuple[str, list[Action]]:
    """Todas as linhas [ação: ...] válidas, na ordem (pedido grande: "faz a armadura toda")."""
    actions: list[Action] = []
    for m in ACTION_LINE.finditer(reply):
        spec = next((s for s in skills if s.name == m.group(1).lower()), None)
        args = parse_args(spec, m.group(2).strip(), speaker, players) if spec else None
        if spec is not None and args is not None:
            actions.append(Action(spec.name, args))
    return ACTION_LINE.sub("", reply), actions


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
