"""Ajudar no que o player está fazendo, em vez de seguir uma lista.

O corpo manda, no state, o último minuto de cada player perto dela
("players_activity"): o que ele quebrou, com que bloco está construindo, o que
atacou e se está embaixo da terra. Daqui sai o que ela faria se fosse uma amiga
jogando junto: ele corta árvore, ela corta também; ele constrói de pedra, ela
traz pedra; ele desce na caverna, ela vai junto iluminando e protegendo.
"""

from __future__ import annotations

from typing import Any

from .goals import Goal

LOGS = ("_log", "_stem")
STONE = {"stone", "deepslate", "cobblestone", "andesite", "diorite", "granite", "tuff", "cobbled_deepslate"}
CROPS = {"wheat", "carrots", "potatoes", "beetroots"}
ANIMALS = {"cow", "pig", "sheep", "chicken", "rabbit", "mooshroom"}
HOSTILES = {
    "zombie", "skeleton", "creeper", "spider", "cave_spider", "enderman", "witch", "husk", "stray", "drowned",
    "slime", "phantom", "pillager", "vindicator", "zombie_villager", "bogged", "breeze", "blaze",
}  # fmt: skip
# Blocos que não faz sentido ela "trazer" para a construção (raros, de baú, decorativos frágeis)
NOT_MATERIAL = ("_ore", "chest", "barrel", "bed", "door", "torch", "sign", "furnace", "crafting_table", "spawner")
NEAR = 16  # mais longe que isso, primeiro ela vai até ele


def _sum(counts: dict[str, int], pred) -> int:
    return sum(n for k, n in counts.items() if pred(k))


def who(state: dict[str, Any], beloved: str) -> dict[str, Any] | None:
    """O player que ela acompanha: quem ela ama, se estiver perto; senão o mais perto."""
    people = [p for p in state.get("players_activity") or [] if isinstance(p, dict) and p.get("name")]
    if not people:
        return None
    for p in people:
        if beloved and p["name"].lower() == beloved.lower():
            return p
    return min(people, key=lambda p: float(p.get("distance") or 99))


def activity(p: dict[str, Any]) -> str | None:
    """O que ele está fazendo, em poucas palavras (para o prompt)."""
    broke = p.get("broke") or {}
    placed = p.get("placed") or {}
    attacked = p.get("attacked") or {}
    if _sum(attacked, lambda k: k in HOSTILES):
        return "lutando com monstros"
    if _sum(broke, lambda k: k.endswith("_ore")):
        ores = sorted({k.removeprefix("deepslate_").removesuffix("_ore") for k in broke if k.endswith("_ore")})
        return "minerando minério (" + ", ".join(ores) + ")"
    if p.get("underground") and _sum(broke, lambda k: k in STONE):
        return "minerando numa caverna"
    if sum(placed.values()) >= 4:
        top = max(placed, key=placed.get)
        return f"construindo (com {top})"
    if _sum(broke, lambda k: k.endswith(LOGS)) >= 2:
        return "cortando árvore"
    if _sum(broke, lambda k: k in CROPS):
        return "colhendo a plantação"
    if _sum(attacked, lambda k: k in ANIMALS):
        return "caçando"
    if _sum(broke, lambda k: k in STONE) >= 4:
        return "minerando pedra"
    if p.get("underground"):
        return "andando numa caverna"
    return None


def wants_company(p: dict[str, Any]) -> bool:
    """Lutando ou fundo numa caverna: hora de ficar do lado dele."""
    attacked = p.get("attacked") or {}
    return bool(_sum(attacked, lambda k: k in HOSTILES)) or bool(p.get("underground") and float(p.get("y") or 64) < 50)


def help_goal(state: dict[str, Any], beloved: str) -> Goal | None:
    """Como ajudar agora no que ele está fazendo (None = ele não está fazendo nada em especial)."""
    p = who(state, beloved)
    if p is None:
        return None
    name = str(p["name"])
    broke = p.get("broke") or {}
    placed = p.get("placed") or {}
    attacked = p.get("attacked") or {}
    far = float(p.get("distance") or 0) > NEAR

    goal: Goal | None = None
    fighting = bool(_sum(attacked, lambda k: k in HOSTILES))
    if not fighting and _sum(broke, lambda k: k.endswith("_ore") or k == "ancient_debris"):
        # Ele está minerando minério: ela quebra os outros minérios em volta (e o veio).
        goal = Goal(f"help:ores:{name}", "mine_ores", {}, f"ajudar {name} a minerar os minérios", "social", 45)
        if far:
            return Goal(f"help:approach:{name}", "goto", {"player": name}, f"ir para perto de {name}", "social", 20)
        return goal
    if wants_company(p):
        # Luta ou caverna: ficar do lado dele (os reflexos protegem; seguindo, ela deixa tocha no escuro).
        why = "ajudar na luta" if _sum(attacked, lambda k: k in HOSTILES) else "ir junto na caverna, iluminando"
        return Goal(f"help:escort:{name}", "follow", {"player": name}, f"{why} com {name}", "social", cooldown=60)
    materials = {k: n for k, n in placed.items() if not any(w in k for w in NOT_MATERIAL)}
    if sum(materials.values()) >= 4:
        top = max(materials, key=materials.get)
        goal = Goal(
            f"help:build:{top}",
            "give",
            {"player": name, "item": top, "count": 16},
            f"levar {top} para {name} continuar a construção",
            "social",
            cooldown=180,
        )
    elif _sum(broke, lambda k: k.endswith(LOGS)) >= 2:
        goal = Goal(
            "help:logs", "collect", {"item": "#logs", "count": 12}, f"cortar árvore junto com {name}", "social", 120
        )
    elif _sum(broke, lambda k: k in CROPS):
        goal = Goal("help:farm", "farm", {}, f"ajudar {name} na colheita", "social", cooldown=300)
    elif animals := {k: n for k, n in attacked.items() if k in ANIMALS}:
        animal = max(animals, key=animals.get)
        goal = Goal(f"help:hunt:{animal}", "hunt", {"animal": animal, "count": 2}, f"caçar com {name}", "social", 180)
    elif _sum(broke, lambda k: k in STONE) >= 4:
        goal = Goal(
            "help:stone", "collect", {"item": "cobblestone", "count": 16}, f"minerar junto com {name}", "social", 120
        )
    if goal is not None and far:
        # Longe dele: primeiro chega perto (a ajuda vem no próximo instante livre).
        return Goal(f"help:approach:{name}", "goto", {"player": name}, f"ir para perto de {name}", "social", 20)
    return goal


def describe_activity(state: dict[str, Any], me: str) -> str:
    """Linhas do prompt: o que cada player perto está fazendo."""
    lines = []
    for p in state.get("players_activity") or []:
        if not isinstance(p, dict) or p.get("name") == me:
            continue
        what = activity(p)
        if what:
            lines.append(f"- {p['name']} está {what}")
    return "## O que estão fazendo\n" + "\n".join(lines) if lines else ""
