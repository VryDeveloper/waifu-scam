"""Plugins da Mente (fase 7): estender a personagem sem mexer no código dela.

Um plugin é um arquivo Python em mind/plugins/ (ou um módulo instalado com
`waifuscam_plugins.<nome>`) que define uma subclasse de Plugin. Ative pelo
character.toml:

    [plugins]
    enabled = ["relogio"]

Ganchos (todos opcionais):
- setup(character): chamado uma vez ao carregar
- on_event(mouth, event): todo evento do jogo (chat, morte, perigo...)
- prompt(state) -> str: texto extra no prompt da IA (contexto novo)
- on_say(text) -> str | None: muda o que ela vai falar; None cala a fala

Um plugin com erro é desligado e avisado no log; nunca derruba a personagem.
"""

from __future__ import annotations

import importlib
import importlib.util
import logging
from pathlib import Path
from types import ModuleType
from typing import TYPE_CHECKING

from . import protocol as p

if TYPE_CHECKING:
    from .brain.base import Mouth
    from .character import Character

log = logging.getLogger(__name__)

DEFAULT_DIR = Path(__file__).resolve().parents[1] / "plugins"


class Plugin:
    name = "plugin"

    def setup(self, character: Character) -> None:  # noqa: B027 - gancho opcional
        pass

    async def on_event(self, mouth: Mouth, event: p.Event) -> None:  # noqa: B027
        pass

    def prompt(self, state: p.State | None) -> str:
        return ""

    def on_say(self, text: str) -> str | None:
        return text


def _module(name: str, directory: Path) -> ModuleType:
    file = directory / f"{name}.py"
    if file.exists():
        spec = importlib.util.spec_from_file_location(f"waifuscam_plugin_{name}", file)
        if spec is None or spec.loader is None:
            raise ImportError(f"não deu para ler {file}")
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        return module
    return importlib.import_module(f"waifuscam_plugins.{name}")


def load_plugins(names: list[str], character: Character, directory: Path = DEFAULT_DIR) -> list[Plugin]:
    plugins: list[Plugin] = []
    for name in names:
        try:
            module = _module(str(name), directory)
            classes = [
                obj
                for obj in vars(module).values()
                if isinstance(obj, type) and issubclass(obj, Plugin) and obj is not Plugin
            ]
            if not classes:
                raise ImportError("nenhuma classe Plugin no arquivo")
            plugin = classes[0]()
            plugin.setup(character)
            plugins.append(plugin)
            log.info("Plugin carregado: %s", plugin.name or name)
        except Exception as e:  # noqa: BLE001 - plugin de terceiro pode quebrar de qualquer jeito
            log.error("Plugin %s não carregou: %s", name, e)
    return plugins


class Plugins:
    """Chama os ganchos protegendo a personagem de plugins com bug."""

    def __init__(self, plugins: list[Plugin] | None = None):
        self.items = list(plugins or [])

    def _broken(self, plugin: Plugin, what: str, e: Exception) -> None:
        log.error("Plugin %s quebrou em %s (%s); desligando.", plugin.name, what, e)
        self.items.remove(plugin)

    async def on_event(self, mouth: Mouth, event: p.Event) -> None:
        for plugin in list(self.items):
            try:
                await plugin.on_event(mouth, event)
            except Exception as e:  # noqa: BLE001
                self._broken(plugin, "on_event", e)

    def prompt(self, state: p.State | None) -> str:
        parts: list[str] = []
        for plugin in list(self.items):
            try:
                extra = plugin.prompt(state)
            except Exception as e:  # noqa: BLE001
                self._broken(plugin, "prompt", e)
                continue
            if extra:
                parts.append(extra.strip())
        return "\n".join(parts)

    def on_say(self, text: str) -> str | None:
        for plugin in list(self.items):
            try:
                result = plugin.on_say(text)
            except Exception as e:  # noqa: BLE001
                self._broken(plugin, "on_say", e)
                continue
            if result is None:
                return None
            text = result
        return text
