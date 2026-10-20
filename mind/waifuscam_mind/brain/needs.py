"""Necessidades: o quanto cada vontade dela está apertando agora (fase 5).

Cada necessidade vira uma urgência de 0 a 1 calculada do estado do corpo, e é
multiplicada pelo peso que a personagem dá a ela ([needs] no character.toml).
O resultado vai para o prompt ("o que você mais quer agora") e para a Vontade,
que decide quando agir sozinha.
"""

from __future__ import annotations

from dataclasses import dataclass, field

from .. import protocol as p

NEEDS = ("hunger", "safety", "energy", "social", "progress", "comfort", "curiosity")

LABELS = {
    "hunger": "comer",
    "safety": "ficar em segurança",
    "energy": "descansar",
    "social": "conversar com alguém",
    "progress": "fazer algo útil (juntar recursos, melhorar as ferramentas)",
    "comfort": "sair do desconforto (chuva, água, escuro)",
    "curiosity": "explorar e ver coisas novas",
}

FOODS = {
    "bread",
    "apple",
    "cooked_beef",
    "cooked_porkchop",
    "cooked_chicken",
    "cooked_mutton",
    "cooked_rabbit",
    "cooked_cod",
    "cooked_salmon",
    "baked_potato",
    "carrot",
    "potato",
    "beetroot",
    "melon_slice",
    "sweet_berries",
    "glow_berries",
    "cookie",
    "pumpkin_pie",
    "mushroom_stew",
    "rabbit_stew",
    "beetroot_soup",
    "golden_carrot",
    "beef",
    "porkchop",
    "chicken",
    "mutton",
    "rabbit",
    "cod",
    "salmon",
    "dried_kelp",
}


def is_night(ticks: int | None) -> bool:
    if ticks is None:
        return False
    t = ticks % 24000
    return 12500 <= t < 23400


def dusk(ticks: int | None) -> bool:
    """Falta pouco para a noite: hora de se preparar."""
    if ticks is None:
        return False
    t = ticks % 24000
    return 11800 <= t < 23400


@dataclass
class Needs:
    urgency: dict[str, float] = field(default_factory=dict)  # 0 a 1
    weighted: dict[str, float] = field(default_factory=dict)  # urgência x peso
    has_food: bool = False
    night: bool = False
    outdoors: bool = True
    threats: list[dict] = field(default_factory=list)

    def top(self, n: int = 2, minimum: float = 0.35) -> list[str]:
        ranked = sorted(self.weighted.items(), key=lambda kv: kv[1], reverse=True)
        return [k for k, v in ranked[:n] if v >= minimum]


def _clamp(x: float) -> float:
    return max(0.0, min(1.0, x))


def assess(
    state: p.State | None,
    weights: dict[str, float],
    *,
    idle_seconds: float = 0.0,
    since_social: float = 0.0,
) -> Needs:
    needs = Needs()
    if state is None or not state.online:
        return needs
    s = state.model_dump()
    food = float(s.get("food", 20))
    health = float(s.get("health", 20))
    max_health = float(s.get("max_health", 20)) or 20.0
    inventory = s.get("inventory") or {}
    needs.has_food = any(item in FOODS for item in inventory)
    needs.night = is_night(s.get("time_of_day"))
    needs.outdoors = bool(s.get("sky", True))
    needs.threats = list(s.get("threats") or [])
    others = [n for n in s.get("players", []) if n]
    close = [t for t in needs.threats if float(t.get("distance", 99)) < 8]

    u = needs.urgency
    u["hunger"] = _clamp((18 - food) / 14)
    u["safety"] = _clamp((1 - health / max_health) + 0.25 * len(close) + (0.5 if needs.night and needs.outdoors else 0))
    u["energy"] = 0.6 if needs.night else 0.0
    u["social"] = _clamp(since_social / 600) if len(others) > 1 else 0.0
    u["progress"] = _clamp(idle_seconds / 300)
    rain = s.get("weather") in ("rain", "thunder") and needs.outdoors
    u["comfort"] = _clamp((0.4 if rain else 0) + (0.5 if s.get("in_water") else 0) + (0.8 if s.get("on_fire") else 0))
    u["curiosity"] = _clamp(0.2 + idle_seconds / 600) if not needs.night else 0.0
    for k in NEEDS:
        needs.weighted[k] = round(u.get(k, 0.0) * float(weights.get(k, 1.0)), 3)
    return needs


def describe_needs(needs: Needs) -> str:
    """Bloco do prompt: como ela se sente agora."""
    if not needs.urgency:
        return ""
    lines = ["## Como você está"]
    if needs.urgency.get("hunger", 0) >= 0.5:
        lines.append("- Com fome" + ("" if needs.has_food else ", e sem comida no inventário"))
    if needs.night:
        lines.append("- É noite: monstros aparecem" + (" e você está ao ar livre" if needs.outdoors else ""))
    if needs.threats:
        near = ", ".join(f"{t.get('type')} a {t.get('distance')} blocos" for t in needs.threats[:3])
        lines.append(f"- Perigo por perto: {near}")
    top = needs.top()
    if top:
        lines.append("- O que você mais quer agora: " + "; ".join(LABELS[k] for k in top))
    return "\n".join(lines) if len(lines) > 1 else ""
