"""Memória de longo prazo (fase 6): o que ela lembra entre uma sessão e outra.

Fica num JSON em mind/data/memory/<personagem>.json (fora do Git):

- lugares: nome → coordenadas ("base", "mina de ferro", "ultima_morte")
- pessoas: quando conheceu, quando viu por último, quantas vezes conversaram, notas
- notas: fatos soltos que ela decidiu guardar ("o Vry tem medo de aranha")
- diário: resumos curtos do que aconteceu, escritos por ela mesma
- promessas pendentes e as últimas falas da conversa, para continuar de onde parou
- recursos: onde ela viu minérios, baús, vilas... (por mundo), vindos dos olhos do corpo

A IA grava com tags no fim da resposta: [lembrar: base] guarda o lugar onde ela
está; [nota: ...] guarda um fato. Ir para um lugar lembrado é [ação: goto base].
"""

from __future__ import annotations

import json
import logging
import os
import re
import time
import unicodedata
from collections.abc import Callable
from dataclasses import asdict, dataclass, field
from pathlib import Path
from typing import Any

log = logging.getLogger(__name__)

MAX_PLACES = 40
MAX_NOTES = 60
MAX_PERSON_NOTES = 12
MAX_DIARY = 14
MAX_HISTORY = 30
MAX_SPOTS_PER_BLOCK = 16
SPOT_CLUSTER = 3  # blocos do mesmo tipo a até 3 de distância contam como um lugar só
SAVE_EVERY = 20.0  # segundos entre gravações quando só mudou "visto por último"


def slug(name: str) -> str:
    """Nome de lugar normalizado: sem acento, minúsculo, com _ ("Mina de Ferro" → mina_de_ferro)."""
    text = unicodedata.normalize("NFKD", name).encode("ascii", "ignore").decode()
    text = re.sub(r"[^a-z0-9]+", "_", text.lower()).strip("_")
    return text[:40]


@dataclass
class Place:
    x: int
    y: int
    z: int
    dimension: str = "minecraft:overworld"
    note: str = ""
    created: float = 0.0

    def coords(self) -> str:
        return f"{self.x} {self.y} {self.z}"


@dataclass
class Person:
    first_met: float = 0.0
    last_seen: float = 0.0
    chats: int = 0
    notes: list[str] = field(default_factory=list)


@dataclass
class DiaryEntry:
    when: float
    text: str


class Memory:
    def __init__(self, path: Path | None, *, clock: Callable[[], float] = time.time):
        self.path = path
        self._clock = clock
        self.places: dict[str, Place] = {}
        self.people: dict[str, Person] = {}
        self.notes: list[str] = []
        self.diary: list[DiaryEntry] = []
        self.promises: list[dict[str, Any]] = []
        self.history: list[dict[str, str]] = []
        # mundo → bloco → [[x, y, z, dimensão, quando viu]]
        self.resources: dict[str, dict[str, list[list[Any]]]] = {}
        self._dirty = False
        self._last_save = 0.0
        self.load()

    # ---- disco -----------------------------------------------------------------

    def load(self) -> None:
        if not self.path or not self.path.exists():
            return
        try:
            data = json.loads(self.path.read_text(encoding="utf-8"))
        except (OSError, ValueError) as e:
            log.warning("Memória ilegível em %s (%s); começando do zero.", self.path, e)
            return
        self.places = {k: Place(**v) for k, v in (data.get("places") or {}).items()}
        self.people = {k: Person(**v) for k, v in (data.get("people") or {}).items()}
        self.notes = list(data.get("notes") or [])
        self.diary = [DiaryEntry(**d) for d in (data.get("diary") or [])]
        self.promises = list(data.get("promises") or [])
        self.history = list(data.get("history") or [])
        self.resources = dict(data.get("resources") or {})
        log.info(
            "Memória carregada: %d lugares, %d pessoas, %d notas, %d dias no diário",
            len(self.places),
            len(self.people),
            len(self.notes),
            len(self.diary),
        )

    def save(self, *, force: bool = False) -> None:
        if not self.path or not self._dirty:
            return
        now = self._clock()
        if not force and now - self._last_save < SAVE_EVERY:
            return
        data = {
            "places": {k: asdict(v) for k, v in self.places.items()},
            "people": {k: asdict(v) for k, v in self.people.items()},
            "notes": self.notes,
            "diary": [asdict(d) for d in self.diary],
            "promises": self.promises,
            "history": self.history[-MAX_HISTORY:],
            "resources": self.resources,
        }
        self.path.parent.mkdir(parents=True, exist_ok=True)
        tmp = self.path.with_suffix(".tmp")
        tmp.write_text(json.dumps(data, ensure_ascii=False, indent=1), encoding="utf-8")
        os.replace(tmp, self.path)  # nunca deixa o arquivo pela metade
        self._dirty = False
        self._last_save = now

    def _changed(self, important: bool = True) -> None:
        self._dirty = True
        self.save(force=important)

    # ---- lugares ---------------------------------------------------------------

    def remember_place(self, name: str, x: float, y: float, z: float, dimension: str, note: str = "") -> str | None:
        key = slug(name)
        if not key:
            return None
        self.places[key] = Place(int(x), int(y), int(z), dimension, note, self._clock())
        while len(self.places) > MAX_PLACES:
            oldest = min(self.places, key=lambda k: self.places[k].created)
            del self.places[oldest]
        self._changed()
        log.info("Lugar lembrado: %s em %d %d %d", key, int(x), int(y), int(z))
        return key

    def place(self, name: str) -> Place | None:
        key = slug(name)
        if key in self.places:
            return self.places[key]
        # "a base" / "nossa base" → base
        for k in self.places:
            if key.endswith("_" + k) or key.startswith(k + "_"):
                return self.places[k]
        return None

    def forget_place(self, name: str) -> bool:
        if self.places.pop(slug(name), None) is None:
            return False
        self._changed()
        return True

    # ---- recursos (o que ela viu) -------------------------------------------------

    def saw(self, world: str, block: str, x: int, y: int, z: int, dimension: str) -> bool:
        """Ela viu um bloco que vale lembrar. Devolve True se é um lugar novo."""
        block = block.removeprefix("minecraft:")
        spots = self.resources.setdefault(world, {}).setdefault(block, [])
        now = self._clock()
        for spot in spots:
            if spot[3] == dimension and abs(spot[0] - x) + abs(spot[1] - y) + abs(spot[2] - z) <= SPOT_CLUSTER:
                spot[4] = now
                self._dirty = True
                return False
        spots.append([int(x), int(y), int(z), dimension, now])
        if len(spots) > MAX_SPOTS_PER_BLOCK:
            spots.sort(key=lambda sp: sp[4])
            del spots[0]
        self._changed(important=False)
        return True

    def gone(self, world: str, block: str, x: int, y: int, z: int, dimension: str) -> None:
        """O bloco não está mais lá (minerado)."""
        spots = self.resources.get(world, {}).get(block.removeprefix("minecraft:"))
        if not spots:
            return
        before = len(spots)
        spots[:] = [sp for sp in spots if not (sp[0] == x and sp[1] == y and sp[2] == z and sp[3] == dimension)]
        if len(spots) != before:
            self._changed(important=False)

    def spots(self, world: str) -> list[dict[str, Any]]:
        """Todas as lembranças de um mundo, como dicionários."""
        out = []
        for block, spots in self.resources.get(world, {}).items():
            for x, y, z, dim, when in spots:
                out.append({"block": block, "x": x, "y": y, "z": z, "dimension": dim, "seen": when})
        return out

    # ---- pessoas ---------------------------------------------------------------

    def _person(self, name: str) -> Person:
        person = self.people.get(name)
        if person is None:
            person = self.people[name] = Person(first_met=self._clock())
            self._changed()
        return person

    def seen(self, names: list[str]) -> None:
        now = self._clock()
        for name in names:
            self._person(name).last_seen = now
        self._dirty = True
        self.save()

    def talked(self, name: str) -> None:
        p = self._person(name)
        p.chats += 1
        p.last_seen = self._clock()
        self._dirty = True
        self.save()

    def is_new(self, name: str) -> bool:
        p = self.people.get(name)
        return p is None or p.chats == 0

    # ---- notas e diário --------------------------------------------------------

    def note(self, text: str, about: list[str] | None = None) -> None:
        text = text.strip()
        if not text or text in self.notes:
            return
        targets = [n for n in (about or []) if n in self.people]
        if targets:
            for name in targets:
                notes = self.people[name].notes
                if text not in notes:
                    notes.append(text)
                    del notes[:-MAX_PERSON_NOTES]
        else:
            self.notes.append(text)
            del self.notes[:-MAX_NOTES]
        self._changed()
        log.info("Nota guardada: %s", text)

    def write_diary(self, text: str) -> None:
        text = text.strip()
        if text:
            self.diary.append(DiaryEntry(self._clock(), text))
            del self.diary[:-MAX_DIARY]
            self._changed()

    def set_promises(self, promises: list[dict[str, Any]]) -> None:
        if promises != self.promises:
            self.promises = promises
            self._changed()

    def set_history(self, history: list[dict[str, str]]) -> None:
        self.history = history[-MAX_HISTORY:]
        self._changed()  # só muda quando ela fala: vale gravar sempre

    # ---- prompt ------------------------------------------------------------------

    def describe(self, online: list[str], me: str) -> str:
        lines: list[str] = []
        if self.places:
            lines.append("## Lugares que você lembra (vá com [ação: goto <nome>])")
            for k, p in list(self.places.items())[-12:]:
                extra = f" ({p.note})" if p.note else ""
                dim = "" if p.dimension.endswith("overworld") else f" no {p.dimension.split(':')[-1]}"
                lines.append(f"- {k}: {p.coords()}{dim}{extra}")
        people = [n for n in online if n != me and n in self.people]
        if people:
            lines.append("## Quem está aqui, pelo que você lembra")
            for name in people:
                p = self.people[name]
                since = _ago(self._clock() - p.first_met)
                notes = "; ".join(p.notes[-4:])
                lines.append(
                    f"- {name}: conhece há {since}, conversaram {p.chats} vezes" + (f". {notes}" if notes else "")
                )
        if self.notes:
            lines.append("## Coisas que você anotou")
            lines += [f"- {n}" for n in self.notes[-8:]]
        if self.diary:
            lines.append("## Seu diário (mais recente por último)")
            lines += [f"- {d.text}" for d in self.diary[-3:]]
        return "\n".join(lines)


def _ago(seconds: float) -> str:
    if seconds < 3600:
        return "pouco tempo"
    if seconds < 86400:
        return f"{int(seconds // 3600)} h"
    return f"{int(seconds // 86400)} dias"
