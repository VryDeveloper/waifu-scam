"""Nomes dos itens em português: para ela entender "faz uma cama" e falar "lã branca".

A fonte principal é a tradução oficial do jogo (pt_br.json), lida da instalação
do Minecraft de quem está jogando (.minecraft/assets). Assim ela conhece todos
os itens do jogo com o nome que aparece na tela, sem guardar arquivo da Mojang
no repositório. Sem a instalação (servidor, testes), fica com uma lista básica.

Por cima vêm os apelidos do dia a dia: "ferro" é a barra de ferro, "madeira" é
tronco, "pedra" é pedregulho, "cama" é a cama branca.
"""

from __future__ import annotations

import json
import logging
import os
import re
import sys
import unicodedata
from pathlib import Path

log = logging.getLogger(__name__)

# Lista básica (sem a tradução do jogo) e nomes que valem para os dois casos.
BASIC = {
    "crafting_table": "Bancada de Trabalho",
    "furnace": "Fornalha",
    "chest": "Baú",
    "stick": "Graveto",
    "oak_log": "Tronco de Carvalho",
    "oak_planks": "Tábuas de Carvalho",
    "cobblestone": "Pedregulho",
    "stone": "Pedra",
    "coal": "Carvão",
    "charcoal": "Carvão Vegetal",
    "raw_iron": "Ferro Bruto",
    "iron_ingot": "Lingote de Ferro",
    "raw_gold": "Ouro Bruto",
    "gold_ingot": "Lingote de Ouro",
    "raw_copper": "Cobre Bruto",
    "copper_ingot": "Lingote de Cobre",
    "diamond": "Diamante",
    "emerald": "Esmeralda",
    "redstone": "Pó de Redstone",
    "lapis_lazuli": "Lápis-Lazúli",
    "wooden_pickaxe": "Picareta de Madeira",
    "stone_pickaxe": "Picareta de Pedra",
    "iron_pickaxe": "Picareta de Ferro",
    "diamond_pickaxe": "Picareta de Diamante",
    "wooden_axe": "Machado de Madeira",
    "stone_axe": "Machado de Pedra",
    "iron_axe": "Machado de Ferro",
    "wooden_shovel": "Pá de Madeira",
    "stone_shovel": "Pá de Pedra",
    "iron_shovel": "Pá de Ferro",
    "wooden_sword": "Espada de Madeira",
    "stone_sword": "Espada de Pedra",
    "iron_sword": "Espada de Ferro",
    "diamond_sword": "Espada de Diamante",
    "wooden_hoe": "Enxada de Madeira",
    "stone_hoe": "Enxada de Pedra",
    "iron_hoe": "Enxada de Ferro",
    "iron_helmet": "Capacete de Ferro",
    "iron_chestplate": "Peitoral de Ferro",
    "iron_leggings": "Calças de Ferro",
    "iron_boots": "Botas de Ferro",
    "shield": "Escudo",
    "bow": "Arco",
    "arrow": "Flecha",
    "torch": "Tocha",
    "white_bed": "Cama Branca",
    "white_wool": "Lã Branca",
    "string": "Linha",
    "feather": "Pena",
    "flint": "Sílex",
    "leather": "Couro",
    "bucket": "Balde",
    "water_bucket": "Balde de Água",
    "shears": "Tesoura",
    "fishing_rod": "Vara de Pesca",
    "flint_and_steel": "Isqueiro",
    "bread": "Pão",
    "wheat": "Trigo",
    "wheat_seeds": "Sementes de Trigo",
    "apple": "Maçã",
    "cooked_beef": "Bife",
    "beef": "Carne Crua",
    "porkchop": "Costeleta de Porco Crua",
    "chicken": "Frango Cru",
    "mutton": "Carne de Carneiro Crua",
    "rabbit": "Coelho Cru",
    "cod": "Bacalhau Cru",
    "salmon": "Salmão Cru",
    "cooked_porkchop": "Costeleta de Porco Assada",
    "cooked_chicken": "Frango Assado",
    "cooked_mutton": "Carne de Carneiro Assada",
    "glass": "Vidro",
    "sand": "Areia",
    "gravel": "Cascalho",
    "dirt": "Terra",
    "paper": "Papel",
    "book": "Livro",
    "bookshelf": "Estante de Livros",
    "sugar_cane": "Cana-de-Açúcar",
    "sugar": "Açúcar",
    "ladder": "Escada de Mão",
    "oak_door": "Porta de Carvalho",
    "oak_boat": "Barco de Carvalho",
    "oak_fence": "Cerca de Carvalho",
    "oak_slab": "Laje de Carvalho",
    "oak_stairs": "Escadas de Carvalho",
    "oak_sign": "Placa de Carvalho",
    "barrel": "Barril",
    "smoker": "Defumador",
    "blast_furnace": "Alto-Forno",
    "anvil": "Bigorna",
    "enchanting_table": "Mesa de Encantamento",
    "bone_meal": "Farinha de Osso",
    "bone": "Osso",
    "iron_nugget": "Pepita de Ferro",
    "gold_nugget": "Pepita de Ouro",
    "compass": "Bússola",
    "clock": "Relógio",
    "map": "Mapa",
    "lead": "Laço",
    "saddle": "Sela",
    "cake": "Bolo",
    "obsidian": "Obsidiana",
}

BASIC_ENTITIES = {
    "sheep": "Ovelha", "cow": "Vaca", "pig": "Porco", "chicken": "Galinha", "rabbit": "Coelho",
    "mooshroom": "Vacogumelo", "spider": "Aranha", "skeleton": "Esqueleto", "zombie": "Zumbi",
    "creeper": "Creeper", "squid": "Lula", "glow_squid": "Lula Brilhante", "slime": "Slime",
    "enderman": "Enderman", "blaze": "Blaze", "cod": "Bacalhau", "salmon": "Salmão",
}  # fmt: skip

# Jeito de falar → item (vale mais que a tradução).
ALIASES = {
    "ferro": "iron_ingot",
    "barra de ferro": "iron_ingot",
    "barras de ferro": "iron_ingot",
    "ouro": "gold_ingot",
    "barra de ouro": "gold_ingot",
    "cobre": "copper_ingot",
    "diamante": "diamond",
    "diamantes": "diamond",
    "carvao": "coal",
    # qualquer madeira serve (a tag do jogo): pegar bétula conta
    "madeira": "#logs",
    "tronco": "#logs",
    "troncos": "#logs",
    "tabua": "#planks",
    "tabuas": "#planks",
    "pedra": "cobblestone",
    "pedras": "cobblestone",
    "bancada": "crafting_table",
    "mesa de trabalho": "crafting_table",
    "mesa de craft": "crafting_table",
    "cama": "white_bed",
    "camas": "white_bed",
    "la": "white_wool",
    "porta": "oak_door",
    "barco": "oak_boat",
    "cerca": "oak_fence",
    "placa": "oak_sign",
    "escada": "ladder",
    "escadinha": "ladder",
    "isqueiro": "flint_and_steel",
    # "carne" sozinha é ambíguo (de vaca? de porco?): só com o bicho
    "carne de porco": "cooked_porkchop",
    "porco assado": "cooked_porkchop",
    "carne de vaca": "cooked_beef",
    "frango": "cooked_chicken",
}

# Nomes curtos demais ou comuns demais na fala para contar sozinhos.
AMBIGUOUS = {"pena", "linha", "terra", "ar", "luz", "fogo", "agua", "lava", "nada", "mapa", "relogio"}
# Itens que não fazem sentido pedir.
TECHNICAL = re.compile(
    r"(^|_)(air|light|barrier|structure|jigsaw|command_block|spawn_egg|potted|wall_|test_|debug|"
    r"moving_piston|piston_head|fire|water|lava|bubble|portal|end_gateway|frosted_ice)"
)
NUMBERS = {
    "um": 1, "uma": 1, "dois": 2, "duas": 2, "tres": 3, "quatro": 4, "cinco": 5, "seis": 6, "sete": 7,
    "oito": 8, "nove": 9, "dez": 10, "doze": 12, "quinze": 15, "vinte": 20, "trinta": 30,
}  # fmt: skip
MAX_WORDS = 6
# "armadura completa de ferro" = as quatro peças; "ferramentas de pedra" = picareta, machado, pá e espada
MATERIALS = {
    "couro": "leather", "ferro": "iron", "ouro": "golden", "diamante": "diamond", "netherite": "netherite",
    "malha": "chainmail", "cobre": "copper", "pedra": "stone", "madeira": "wooden",
}  # fmt: skip
KITS = re.compile(
    r"\b(armaduras?|ferramentas?)(?: completas?| inteiras?| todas?)?(?: de| do| da)? (" + "|".join(MATERIALS) + r")\b"
)
KIT_PIECES = {
    "armadura": ("helmet", "chestplate", "leggings", "boots"),
    "ferramenta": ("pickaxe", "axe", "shovel", "sword"),
}
# "tem ferro no baú": o baú é onde está, não o que pediram
PLACE_WORDS = {"no", "na", "nos", "nas", "do", "da", "dos", "das", "num", "numa", "pro", "pra"}


def normalize(text: str) -> str:
    text = unicodedata.normalize("NFKD", text).encode("ascii", "ignore").decode().lower()
    return re.sub(r"[^a-z0-9]+", " ", text).strip()


class ItemNames:
    def __init__(self, names: dict[str, str] | None = None, entities: dict[str, str] | None = None):
        self.names: dict[str, str] = dict(BASIC)
        if names:
            self.names.update(names)
        self.entities: dict[str, str] = {**BASIC_ENTITIES, **(entities or {})}
        self._has_game_names = bool(names)
        self.index: dict[str, str] = {}
        self._build()

    # ---- montar o índice -----------------------------------------------------------

    def _add(self, alias: str, item: str, *, override: bool = False) -> None:
        key = normalize(alias)
        if len(key) < 2:
            return
        if override or key not in self.index:
            self.index[key] = item

    def _build(self) -> None:
        for item, name in self.names.items():
            if TECHNICAL.search(item):
                continue
            key = normalize(name)
            self._add(key, item)
            words = key.split()
            # "cama branca" → "cama" (a branca é a padrão); "tabuas de carvalho" → "tabuas"
            if item.startswith("white_") and words and words[-1].startswith("branc"):
                self._add(" ".join(words[:-1]), item)
            if item.startswith("oak_") and key.endswith(" de carvalho"):
                self._add(key.removesuffix(" de carvalho"), item)
        # singular/plural da primeira palavra ("tochas", "graveto")
        for key, item in list(self.index.items()):
            first, _, rest = key.partition(" ")
            other = first[:-1] if first.endswith("s") else first + "s"
            self._add(f"{other} {rest}".strip(), item)
        for alias, item in ALIASES.items():
            self._add(alias, item, override=True)

    # ---- usar ------------------------------------------------------------------------

    def name(self, item: str) -> str:
        """'white_bed' → 'cama branca'."""
        item = item.removeprefix("minecraft:").removeprefix("#")
        if item in self.names:
            return self.names[item].lower()
        if item in self.entities:
            return self.entities[item].lower()
        if item in ("logs", "planks"):
            return {"logs": "tronco", "planks": "tábuas"}[item]
        return item.replace("_", " ")

    def resolve(self, text: str) -> str | None:
        """Nome inteiro → id ("cama" → white_bed, "white_bed" → white_bed)."""
        raw = text.strip().removeprefix("minecraft:")
        if raw in self.names:
            return raw
        return self.index.get(normalize(raw))

    def find(self, text: str, limit: int = 4) -> list[tuple[str, int]]:
        """Itens citados numa frase, com a quantidade quando dita ("faz 3 tochas")."""
        norm = normalize(text)
        found: list[tuple[str, int]] = []
        for m in KITS.finditer(norm):
            kind = m.group(1).rstrip("s")
            material = MATERIALS[m.group(2)]
            for piece in KIT_PIECES[kind]:
                item = f"{material}_{piece}"
                if (item in self.names or not self._has_game_names) and item not in (f for f, _ in found):
                    found.append((item, 1))
        norm = KITS.sub(" ", norm)
        words = norm.split()
        i = 0
        while i < len(words) and len(found) < limit:
            for n in range(min(MAX_WORDS, len(words) - i), 0, -1):
                phrase = " ".join(words[i : i + n])
                item = self.index.get(phrase)
                if item is None or (n == 1 and phrase in AMBIGUOUS):
                    continue
                if i > 0 and words[i - 1] in PLACE_WORDS and n == 1:
                    i += n - 1
                    break  # "no baú", "da fornalha": é lugar, não pedido
                if all(item != f for f, _ in found):
                    found.append((item, _count(words, i)))
                i += n - 1
                break
            i += 1
        return found


def _count(words: list[str], i: int) -> int:
    for j in (i - 1, i - 2):  # "3 tochas", "3 de ferro"
        if j < 0:
            break
        w = words[j]
        if w.isdigit():
            return max(1, min(576, int(w)))
        if w in NUMBERS:
            return NUMBERS[w]
        if w != "de":
            break
    return 1


# ---- tradução do jogo ----------------------------------------------------------------


def minecraft_dir() -> Path | None:
    env = os.environ.get("MINECRAFT_DIR")
    if env:
        return Path(env)
    if sys.platform == "win32" and os.environ.get("APPDATA"):
        return Path(os.environ["APPDATA"]) / ".minecraft"
    if sys.platform == "darwin":
        return Path.home() / "Library" / "Application Support" / "minecraft"
    return Path.home() / ".minecraft"


def load_game_names(
    language: str = "pt_br", base: Path | None = None, prefixes: tuple[str, ...] = ()
) -> dict[str, str]:
    """Nomes de item/bloco da tradução oficial, da instalação local. Vazio se não achar."""
    prefixes = prefixes or ("item.minecraft.", "block.minecraft.")
    base = base or minecraft_dir()
    lang = language.lower().replace("-", "_")
    if base is None or not (base / "assets" / "indexes").is_dir():
        return {}
    indexes = sorted((base / "assets" / "indexes").glob("*.json"), key=lambda f: f.stat().st_mtime, reverse=True)
    for index in indexes:
        try:
            entry = json.loads(index.read_text(encoding="utf-8")).get("objects", {}).get(f"minecraft/lang/{lang}.json")
            if not entry:
                continue
            h = entry["hash"]
            data = json.loads((base / "assets" / "objects" / h[:2] / h).read_text(encoding="utf-8"))
        except (OSError, ValueError, KeyError):
            continue
        names: dict[str, str] = {}
        for prefix in prefixes:  # o nome do item vale mais que o do bloco
            for key, value in data.items():
                if key.startswith(prefix) and key.count(".") == 2:
                    names.setdefault(key[len(prefix) :], value)
        log.debug("%d nomes (%s) da tradução %s do jogo (%s)", len(names), prefixes[0], lang, index.name)
        return names
    return {}


def load(language: str = "pt-BR") -> ItemNames:
    names = load_game_names(language)
    if not names:
        log.info("Tradução do jogo não encontrada; usando a lista básica de itens (defina MINECRAFT_DIR).")
    else:
        log.info("Nomes de %d itens e blocos, da tradução do jogo", len(names))
    return ItemNames(names, load_game_names(language, prefixes=("entity.minecraft.",)))
