"""Momentos de casal: o que ela faz por carinho quando ninguém pediu nada.

- Ele parou (está construindo, olhando o mapa, AFK): ela vem ficar pertinho.
- De vez em quando, de dia, ela traz uma flor de presente.
- Ele foi dormir: ela vai dormir também (a Vontade cuida disso à noite).

Só com quem está em [chat] beloved (ou com quem estiver jogando, se vazio).
"""

from __future__ import annotations

from typing import Any

from .goals import Goal
from .helping import who
from .needs import is_night

COME_CLOSE_AFTER = 25  # segundos parado
FLOWER_EVERY = 45 * 60


def partner(state: dict[str, Any], beloved: str) -> dict[str, Any] | None:
    p = who(state, beloved)
    if p is None or (beloved and str(p.get("name", "")).lower() != beloved.lower()):
        return None
    return p


def bond_goal(state: dict[str, Any], beloved: str) -> Goal | None:
    p = partner(state, beloved)
    if p is None or p.get("sleeping"):
        return None
    name = str(p["name"])
    dist = float(p.get("distance") or 0)
    idle = float(p.get("idle_seconds") or 0)
    if idle >= COME_CLOSE_AFTER and 4 < dist <= 32:
        return Goal(f"bond:close:{name}", "goto", {"player": name}, f"ficar pertinho de {name}", "social", cooldown=240)
    if not is_night(state.get("time_of_day")) and dist <= 20:
        return Goal(
            "bond:flower",
            "give",
            {"player": name, "item": "#small_flowers", "count": 1},
            f"colher uma flor e dar de presente para {name}",
            "social",
            cooldown=FLOWER_EVERY,
        )
    return None


def partner_sleeping(state: dict[str, Any], beloved: str) -> str | None:
    """Nome dele se ele está dormindo agora (ela vai dormir também)."""
    p = partner(state, beloved)
    return str(p["name"]) if p is not None and p.get("sleeping") else None
