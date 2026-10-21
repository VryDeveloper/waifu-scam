"""O passo a passo de como conseguir um item, em texto para o prompt.

Os dados vêm do corpo (query "plan"): a receita do jogo e o plano que ela
seguiria agora, com o inventário dela e o que tem por perto (bancada, fornalha).
É o mesmo plano que o obtain executa, então o que ela diz bate com o que faz.
"""

from __future__ import annotations

from typing import Any

from ..items import ItemNames

MAX_STEPS = 8


def _thing(names: ItemNames, entry: dict[str, Any]) -> str:
    name = names.name(str(entry.get("item", "?")))
    if entry.get("any"):
        name = f"{name} (qualquer tipo)" if "tábuas" in name or "tronco" in name else f"{name} (ou parecido)"
    return f"{entry.get('count', 1)} {name}"


def _recipe(names: ItemNames, recipe: dict[str, Any]) -> str:
    kind = recipe.get("kind")
    if kind == "craft":
        parts = " + ".join(_thing(names, i) for i in recipe.get("inputs") or [])
        where = "na bancada" if recipe.get("table") else "no inventário mesmo (2x2)"
        makes = int(recipe.get("makes") or 1)
        return f"{parts}, {where}" + (f", rende {makes}" if makes > 1 else "")
    if kind == "smelt":
        return f"assar {_thing(names, recipe.get('input') or {})} na fornalha (com combustível)"
    if kind in ("gather", "hunt"):
        source = ", ".join(names.name(str(b)) for b in recipe.get("from") or [])
        tool = f", com {names.name(str(recipe['tool']))} ou melhor" if recipe.get("tool") else ""
        return f"{'caçando' if kind == 'hunt' else 'quebrando'} {source}{tool}"
    return ""


def _step(names: ItemNames, step: dict[str, Any]) -> str:
    what = names.name(str(step.get("item", "?")))
    n = step.get("count", 1)
    match step.get("do"):
        case "hunt":
            source = ", ".join(names.name(str(b)) for b in step.get("from") or [])
            return f"caçar {source} até ter {n} {what}"
        case "gather":
            source = names.name(str((step.get("from") or [step.get("item")])[0]))
            return f"pegar {n} {what}" + (f" (de {source})" if source != what else "")
        case "smelt":
            return f"assar {n} {what}"
        case _:
            where = " na bancada" if step.get("table") else ""
            return f"craftar {n} {what}{where}"


def describe_plan(result: dict[str, Any], names: ItemNames) -> str:
    """Uma linha (ou duas) sobre o item: receita, o que ela tem e o plano de agora."""
    item = str(result.get("item", "?"))
    count = int(result.get("count") or 1)
    lines = [f"- {names.name(item)} ({item})"]
    if isinstance(result.get("recipe"), dict) and (text := _recipe(names, result["recipe"])):
        lines.append(f"  receita: {text}")
    have = int(result.get("have") or 0)
    if not result.get("ok"):
        lines.append(f"  você tem {have}. Agora NÃO dá para conseguir: {result.get('failure', 'não sei como')}")
        return "\n".join(lines)
    steps = [s for s in result.get("steps") or [] if isinstance(s, dict)]
    if not steps:
        lines.append(f"  você já tem {have}.")
        return "\n".join(lines)
    plan = " → ".join(_step(names, s) for s in steps[:MAX_STEPS]) + (" → ..." if len(steps) > MAX_STEPS else "")
    near = []
    if any(s.get("table") for s in steps):
        near.append("tem bancada perto" if result.get("table_nearby") else "sem bancada perto")
    if any(s.get("do") == "smelt" for s in steps):
        near.append("tem fornalha perto" if result.get("furnace_nearby") else "sem fornalha perto")
    lines.append(f"  você tem {have}" + (f" ({', '.join(near)})" if near else "") + f". Seu plano: {plan}")
    lines.append(f"  para fazer: [ação: obtain {item} {count}]")
    return "\n".join(lines)


def describe_plans(results: list[dict[str, Any]], names: ItemNames) -> str:
    if not results:
        return ""
    body = "\n".join(describe_plan(r, names) for r in results)
    return "## Do jogo, sobre o que estão falando (dados reais; use para decidir, não recite o passo a passo)\n" + body
