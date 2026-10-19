"""Orçamento diário de chamadas de IA.

Os planos gratuitos têm limite por dia (no Groq, cerca de 1.000 chamadas). O
orçamento conta as chamadas do dia, grava em disco para sobreviver a reinícios
e avisa quando acabar, para a personagem não ficar muda sem explicação.
"""

from __future__ import annotations

import json
import logging
from datetime import date
from pathlib import Path

log = logging.getLogger(__name__)


class Budget:
    def __init__(self, daily_limit: int, file: Path | None = None, today=date.today):
        self.daily_limit = daily_limit
        self.file = file
        self._today = today
        self._day, self.used = self._load()

    def _load(self) -> tuple[str, int]:
        today = self._today().isoformat()
        if self.file:
            try:
                data = json.loads(self.file.read_text(encoding="utf-8"))
                if data.get("day") == today:
                    return today, int(data.get("used", 0))
            except (OSError, ValueError):
                pass
        return today, 0

    def _roll_day(self) -> None:
        today = self._today().isoformat()
        if today != self._day:
            self._day, self.used = today, 0

    @property
    def remaining(self) -> int:
        self._roll_day()
        return max(0, self.daily_limit - self.used)

    def try_spend(self) -> bool:
        """Reserva uma chamada. False quando o limite do dia acabou."""
        self._roll_day()
        if self.used >= self.daily_limit:
            return False
        self.used += 1
        self._save()
        if self.used in (int(self.daily_limit * 0.8), self.daily_limit):
            log.warning("Orçamento de IA: %d de %d chamadas usadas hoje.", self.used, self.daily_limit)
        return True

    def _save(self) -> None:
        if not self.file:
            return
        try:
            self.file.parent.mkdir(parents=True, exist_ok=True)
            self.file.write_text(json.dumps({"day": self._day, "used": self.used}), encoding="utf-8")
        except OSError as e:
            log.debug("Orçamento não gravado: %s", e)
