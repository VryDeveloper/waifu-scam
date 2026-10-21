"""Objetivos próprios (vontade própria de verdade): o que ela quer ir conquistando.

Uma progressão de sobrevivência, como um player faz: picareta, machado, espada,
fornalha, comida, tochas, ferro. Quando ela está livre, a Vontade pega o próximo
objetivo que ainda não foi cumprido e que não falhou há pouco tempo.

A personalidade entra no tempo e no jeito: os pesos de [needs] mudam a ordem
(fome pesa mais = comida antes) e a IA anuncia com a voz dela.
"""

from __future__ import annotations

from dataclasses import dataclass

from .needs import FOODS

PICKAXES = (
    "wooden_pickaxe",
    "stone_pickaxe",
    "copper_pickaxe",
    "iron_pickaxe",
    "golden_pickaxe",
    "diamond_pickaxe",
    "netherite_pickaxe",
)
GOOD_PICKAXES = PICKAXES[1:]
AXES = ("stone_axe", "copper_axe", "iron_axe", "diamond_axe", "netherite_axe")
SWORDS = ("stone_sword", "copper_sword", "iron_sword", "diamond_sword", "netherite_sword")
IRON_PICKAXES = ("iron_pickaxe", "diamond_pickaxe", "netherite_pickaxe")
COOKED = ("cooked_beef", "cooked_porkchop", "cooked_mutton", "cooked_chicken")


@dataclass(frozen=True)
class Goal:
    key: str  # identifica o objetivo (para não insistir no que falhou)
    skill: str
    args: dict
    why: str  # para a IA anunciar ("quero uma picareta de pedra")
    need: str  # necessidade que ele atende (hunger, safety, progress...)


def _has(inv: dict, names: tuple[str, ...] | str, n: int = 1) -> bool:
    if isinstance(names, str):
        names = (names,)
    return sum(int(inv.get(x, 0)) for x in names) >= n


def _food_count(inv: dict) -> int:
    return sum(int(v) for k, v in inv.items() if k in FOODS)


def candidates(state: dict) -> list[Goal]:
    """Objetivos ainda não cumpridos, na ordem natural da progressão."""
    inv = {str(k).replace("minecraft:", ""): v for k, v in (state.get("inventory") or {}).items()}
    goals: list[Goal] = []
    food = int(state.get("food", 20))

    if _food_count(inv) == 0 and food <= 16:
        for cooked in COOKED:
            goals.append(
                Goal(
                    f"food:{cooked}", "obtain", {"item": cooked, "count": 3}, "arrumar comida (caçar e assar)", "hunger"
                )
            )
    if not _has(inv, PICKAXES):
        goals.append(
            Goal(
                "wooden_pickaxe",
                "obtain",
                {"item": "wooden_pickaxe", "count": 1},
                "fazer uma picareta de madeira",
                "progress",
            )
        )
    if not _has(inv, GOOD_PICKAXES):
        goals.append(
            Goal(
                "stone_pickaxe",
                "obtain",
                {"item": "stone_pickaxe", "count": 1},
                "fazer uma picareta de pedra",
                "progress",
            )
        )
    if not _has(inv, SWORDS):
        goals.append(
            Goal(
                "stone_sword",
                "obtain",
                {"item": "stone_sword", "count": 1},
                "fazer uma espada para se defender",
                "safety",
            )
        )
    if not _has(inv, AXES):
        goals.append(Goal("stone_axe", "obtain", {"item": "stone_axe", "count": 1}, "fazer um machado", "progress"))
    if not _has(inv, "crafting_table"):
        goals.append(
            Goal(
                "crafting_table",
                "obtain",
                {"item": "crafting_table", "count": 1},
                "levar uma bancada na mochila",
                "progress",
            )
        )
    if _food_count(inv) < 4:
        for cooked in COOKED:
            goals.append(
                Goal(f"stock:{cooked}", "obtain", {"item": cooked, "count": 5}, "guardar comida para depois", "hunger")
            )
    if not _has(inv, "furnace"):
        goals.append(Goal("furnace", "obtain", {"item": "furnace", "count": 1}, "fazer uma fornalha", "progress"))
    if not _has(inv, "torch", 8):
        goals.append(
            Goal("torch", "obtain", {"item": "torch", "count": 16}, "fazer tochas para a noite e as cavernas", "safety")
        )
    if not _has(inv, IRON_PICKAXES):
        goals.append(
            Goal(
                "iron_pickaxe",
                "obtain",
                {"item": "iron_pickaxe", "count": 1},
                "minerar ferro e fazer uma picareta de ferro",
                "progress",
            )
        )
    if not _has(inv, ("iron_sword", "diamond_sword", "netherite_sword")):
        goals.append(
            Goal("iron_sword", "obtain", {"item": "iron_sword", "count": 1}, "fazer uma espada de ferro", "safety")
        )
    if not _has(inv, "shield"):
        goals.append(Goal("shield", "obtain", {"item": "shield", "count": 1}, "fazer um escudo", "safety"))
    if not _has(inv, "iron_chestplate"):
        goals.append(
            Goal(
                "iron_chestplate",
                "obtain",
                {"item": "iron_chestplate", "count": 1},
                "fazer um peitoral de ferro",
                "safety",
            )
        )
    if not _has(
        inv,
        (
            "oak_log",
            "birch_log",
            "spruce_log",
            "jungle_log",
            "acacia_log",
            "dark_oak_log",
            "mangrove_log",
            "cherry_log",
            "pale_oak_log",
        ),
        16,
    ):
        goals.append(Goal("logs", "collect", {"item": "#logs", "count": 16}, "juntar madeira de reserva", "progress"))
    if not _has(inv, "cobblestone", 32):
        goals.append(
            Goal("cobble", "collect", {"item": "cobblestone", "count": 32}, "juntar pedra de reserva", "progress")
        )
    return goals


def choose(state: dict, weights: dict[str, float], blocked: set[str], available: set[str]) -> Goal | None:
    """O próximo objetivo: o primeiro da progressão, adiantando o que a personagem mais valoriza."""
    options = [g for g in candidates(state) if g.key not in blocked and g.skill in available]
    if not options:
        return None
    # Ordem da progressão, mas uma necessidade com peso alto pula na frente de uma com peso baixo.
    ranked = sorted(enumerate(options), key=lambda ig: ig[0] - 2.0 * (float(weights.get(ig[1].need, 1.0)) - 1.0))
    return ranked[0][1]
