"""O que ela vê agora e onde lembra de ter visto as coisas, em texto para o prompt.

O corpo manda, no state, o que está no campo de visão dela ("seeing": bichos,
players, blocos que chamam atenção) e quem está por perto. Achados que valem
lembrar (minérios, baús, sino de vila, spawner...) chegam como eventos "seen" e
ficam na memória, por mundo. Ao conectar, a Mente devolve essas lembranças ao
corpo (memory.blocks), para que ele possa voltar lá quando precisar.
"""

from __future__ import annotations

import math
from typing import Any

# nome do bloco (sem "minecraft:") → como ela chama
_ORES = {
    "diamond": "diamante",
    "iron": "ferro",
    "gold": "ouro",
    "coal": "carvão",
    "copper": "cobre",
    "redstone": "redstone",
    "lapis": "lápis-lazúli",
    "emerald": "esmeralda",
    "nether_quartz": "quartzo",
    "nether_gold": "ouro do nether",
}
_BLOCKS = {
    "ancient_debris": "detrito ancestral",
    "chest": "baú",
    "trapped_chest": "baú",
    "ender_chest": "baú do end",
    "barrel": "barril",
    "spawner": "spawner",
    "trial_spawner": "spawner de desafio",
    "bell": "sino de vila",
    "lava": "lava",
    "crafting_table": "bancada",
    "furnace": "fornalha",
    "obsidian": "obsidiana",
    "budding_amethyst": "geodo de ametista",
    "amethyst_cluster": "ametista",
    "nether_portal": "portal do nether",
    "end_portal_frame": "portal do end",
    "enchanting_table": "mesa de encantamento",
    "anvil": "bigorna",
    "beehive": "colmeia",
    "bee_nest": "colmeia",
    "melon": "melancia",
    "pumpkin": "abóbora",
    "sugar_cane": "cana-de-açúcar",
    "wheat": "plantação de trigo",
    "carrots": "plantação de cenoura",
    "potatoes": "plantação de batata",
    "beetroots": "plantação de beterraba",
}
CROPS = {"wheat", "carrots", "potatoes", "beetroots"}
# palavras que ela (ou o player) usa → tipo de lembrança
ALIASES = {
    "diamante": "diamante",
    "diamantes": "diamante",
    "diamond": "diamante",
    "ferro": "ferro",
    "iron": "ferro",
    "ouro": "ouro",
    "gold": "ouro",
    "carvao": "carvão",
    "carvão": "carvão",
    "coal": "carvão",
    "cobre": "cobre",
    "copper": "cobre",
    "redstone": "redstone",
    "lapis": "lápis-lazúli",
    "esmeralda": "esmeralda",
    "emerald": "esmeralda",
    "bau": "baú",
    "baú": "baú",
    "vila": "sino de vila",
    "vilarejo": "sino de vila",
    "village": "sino de vila",
    "spawner": "spawner",
    "obsidiana": "obsidiana",
    "portal": "portal do nether",
    "ametista": "geodo de ametista",
    "melancia": "melancia",
    "abobora": "abóbora",
    "abóbora": "abóbora",
    "cana": "cana-de-açúcar",
    "colmeia": "colmeia",
    "plantacao": "plantação",
    "plantação": "plantação",
    "fazenda": "plantação",
    "horta": "plantação",
}


def label(block: str) -> str:
    """'deepslate_iron_ore' → 'minério de ferro'."""
    b = block.removeprefix("minecraft:")
    if b.endswith("_ore"):
        base = b.removesuffix("_ore").removeprefix("deepslate_")
        return f"minério de {_ORES.get(base, base.replace('_', ' '))}"
    if b.endswith("_bed"):
        return "cama"
    return _BLOCKS.get(b, b.replace("_", " "))


def kind(block: str) -> str:
    """Tipo de lembrança: o minério vira só o material ('ferro'); o resto, o nome."""
    b = block.removeprefix("minecraft:")
    if b.endswith("_ore"):
        base = b.removesuffix("_ore").removeprefix("deepslate_")
        return _ORES.get(base, base.replace("_", " "))
    if b in CROPS:
        return "plantação"
    return label(b)


def compass(dx: float, dz: float) -> str:
    """Direção no mapa (norte = -Z)."""
    angle = (math.degrees(math.atan2(dx, -dz)) + 360) % 360
    names = ["norte", "nordeste", "leste", "sudeste", "sul", "sudoeste", "oeste", "noroeste"]
    return names[int((angle + 22.5) // 45) % 8]


def _entity(e: dict[str, Any]) -> str:
    kind_ = str(e.get("type", "?"))
    name = e.get("name")
    if kind_.startswith("item:"):
        what = f"item no chão ({kind_[5:]})"
    elif name and kind_ == "player":
        what = str(name)
    elif name:
        what = f"{kind_} chamado {name}"
    else:
        what = kind_
    hostile = " (hostil)" if e.get("hostile") else ""
    return f"{what}{hostile} a {e.get('distance', '?')} blocos, {e.get('dir', '')}".rstrip(", ")


def describe_seeing(s: dict[str, Any], me: str) -> str:
    """Seção do prompt com o que ela está vendo e ouvindo agora."""
    seeing = s.get("seeing")
    near = s.get("players_near") or []
    if not isinstance(seeing, dict) and not near:
        return ""
    seeing = seeing if isinstance(seeing, dict) else {}
    lines = ["## O que você está vendo agora"]
    if seeing.get("looking_at"):
        looking = str(seeing["looking_at"])
        lines.append(f"- Olhando para: {label(looking) if '_' in looking else looking}")
    entities = [e for e in seeing.get("entities") or [] if isinstance(e, dict) and e.get("name") != me]
    for e in entities[:6]:
        lines.append(f"- {_entity(e)}")
    for b in (seeing.get("blocks") or [])[:6]:
        if not isinstance(b, dict):
            continue
        count = int(b.get("count") or 1)
        many = " (vários)" if count >= 4 else ""
        lines.append(f"- {label(str(b.get('block', '?')))}{many} a {b.get('distance', '?')} blocos, {b.get('dir', '')}")
    seen_names = {e.get("name") for e in entities}
    for pl in near:
        if not isinstance(pl, dict) or pl.get("name") in seen_names or pl.get("name") == me:
            continue
        where = "você não está vendo" if not pl.get("visible") else "à vista"
        lines.append(f"- {pl.get('name')} está {pl.get('dir', 'perto')}, a {pl.get('distance', '?')} blocos ({where})")
    if len(lines) == 1:
        lines.append("- Nada de especial.")
    return "\n".join(lines)


def describe_resources(spots: list[dict[str, Any]], s: dict[str, Any], limit: int = 10) -> str:
    """Onde ela lembra de ter visto as coisas, do mais perto para o mais longe."""
    if not spots or "x" not in s:
        return ""
    x, y, z = s["x"], s["y"], s["z"]
    dim = s.get("dimension", "minecraft:overworld")
    best: dict[str, tuple[float, dict[str, Any], int]] = {}
    for spot in spots:
        if spot.get("dimension", dim) != dim:
            continue
        k = kind(str(spot.get("block", "")))
        d = math.dist((x, y, z), (spot["x"], spot["y"], spot["z"]))
        count = best.get(k, (0, spot, 0))[2] + 1
        if k not in best or d < best[k][0]:
            best[k] = (d, spot, count)
        else:
            best[k] = (best[k][0], best[k][1], count)
    if not best:
        return ""
    lines = ["## Onde você lembra de ter visto coisas (para ir: [ação: goto ferro], [ação: goto vila]...)"]
    for k, (d, spot, count) in sorted(best.items(), key=lambda kv: kv[1][0])[:limit]:
        if d < 4:
            where = "bem aqui"
        else:
            where = f"a {d:.0f} blocos, {compass(spot['x'] - x, spot['z'] - z)}"
        more = f" ({count} lugares)" if count > 1 else ""
        lines.append(f"- {k}: {where}, Y {spot['y']}{more}")
    return "\n".join(lines)


def nearest(spots: list[dict[str, Any]], wanted_kind: str, s: dict[str, Any]) -> dict[str, Any] | None:
    """A lembrança mais perto desse tipo, na dimensão em que ela está."""
    if "x" not in s:
        return None
    dim = s.get("dimension", "minecraft:overworld")
    pos = (s["x"], s["y"], s["z"])
    options = [sp for sp in spots if sp.get("dimension", dim) == dim and kind(str(sp.get("block", ""))) == wanted_kind]
    if not options:
        return None
    return min(options, key=lambda sp: math.dist(pos, (sp["x"], sp["y"], sp["z"])))


def is_rare(block: str) -> bool:
    b = block.removeprefix("minecraft:")
    return any(w in b for w in ("diamond", "emerald", "ancient_debris", "spawner")) or b in {
        "bell",
        "end_portal_frame",
        "budding_amethyst",
    }
