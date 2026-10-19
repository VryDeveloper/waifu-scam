"""Skin da personagem, assinada pela Mojang, pronta para o mod usar no GameProfile.

Duas fontes, configuradas em [skin] do character.toml:

- username = "Conta"  → copia a skin atual dessa conta (API pública da Mojang, sem chave)
- file = "skin.png"   → envia o PNG ao MineSkin, que devolve a textura assinada
                        (precisa de MINESKIN_API_KEY, grátis em account.mineskin.org/keys)

O resultado fica em cache em disco: o PNG só é enviado de novo se mudar.
Nada aqui derruba a Mente: se der errado, a personagem entra com a skin padrão.
"""

from __future__ import annotations

import hashlib
import json
import logging
import time
from dataclasses import asdict, dataclass
from pathlib import Path

import httpx

from .character import Character

log = logging.getLogger(__name__)

MOJANG_PROFILE = "https://api.mojang.com/users/profiles/minecraft/{name}"
MOJANG_SESSION = "https://sessionserver.mojang.com/session/minecraft/profile/{uuid}?unsigned=false"
MINESKIN_GENERATE = "https://api.mineskin.org/v2/generate"
USER_AGENT = "WaifuScam/0.2 (+https://github.com/VryDeveloper/waifu-scam)"
USERNAME_CACHE_SECONDS = 24 * 3600


@dataclass(frozen=True)
class Skin:
    value: str
    signature: str


class SkinResolver:
    def __init__(self, cache_file: Path, mineskin_key: str = "", client: httpx.AsyncClient | None = None):
        self.cache_file = cache_file
        self.mineskin_key = mineskin_key
        self._client = client

    async def resolve(self, character: Character) -> Skin | None:
        cfg = character.section("skin")
        try:
            if isinstance(cfg.get("file"), str) and cfg["file"].strip():
                return await self._from_file(character.root / cfg["file"].strip(), cfg.get("model", "classic"))
            if isinstance(cfg.get("username"), str) and cfg["username"].strip():
                return await self._from_username(cfg["username"].strip())
        except (httpx.HTTPError, KeyError, ValueError, OSError) as e:
            log.warning("Não consegui preparar a skin (%s). Ela vai entrar com a skin padrão.", e)
        return None

    # ---- fontes ------------------------------------------------------------

    async def _from_username(self, username: str) -> Skin | None:
        key = f"user:{username.lower()}"
        cached = self._cache_get(key, max_age=USERNAME_CACHE_SECONDS)
        if cached:
            return cached
        async with self._http() as http:
            r = await http.get(MOJANG_PROFILE.format(name=username))
            if r.status_code in (204, 404):
                log.warning("A conta '%s' não existe; usando a skin padrão.", username)
                return None
            r.raise_for_status()
            uuid = r.json()["id"]
            r = await http.get(MOJANG_SESSION.format(uuid=uuid))
            r.raise_for_status()
        textures = next((p for p in r.json().get("properties", []) if p.get("name") == "textures"), None)
        if not textures or "signature" not in textures:
            log.warning("A conta '%s' não devolveu uma skin assinada; usando a skin padrão.", username)
            return None
        skin = Skin(textures["value"], textures["signature"])
        self._cache_put(key, skin)
        log.info("Skin copiada da conta %s.", username)
        return skin

    async def _from_file(self, path: Path, model: str) -> Skin | None:
        data = path.read_bytes()
        variant = "slim" if model == "slim" else "classic"
        key = f"file:{hashlib.sha256(data).hexdigest()}:{variant}"
        cached = self._cache_get(key)
        if cached:
            return cached
        if not self.mineskin_key:
            log.warning(
                "Skin em arquivo precisa de MINESKIN_API_KEY no .env (grátis em account.mineskin.org/keys). "
                "Usando a skin padrão por enquanto."
            )
            return None
        async with self._http(timeout=60) as http:
            r = await http.post(
                MINESKIN_GENERATE,
                headers={"Authorization": f"Bearer {self.mineskin_key}"},
                data={"variant": variant, "visibility": "unlisted", "name": path.stem[:20]},
                files={"file": (path.name, data, "image/png")},
            )
            if r.status_code == 429:
                log.warning("MineSkin pediu para esperar (limite de uso). Tente de novo em alguns minutos.")
                return None
            r.raise_for_status()
        tex = r.json()["skin"]["texture"]["data"]
        skin = Skin(tex["value"], tex["signature"])
        self._cache_put(key, skin)
        log.info("Skin gerada a partir de %s.", path.name)
        return skin

    # ---- infraestrutura ----------------------------------------------------

    def _http(self, timeout: float = 15):
        if self._client is not None:
            return _Borrowed(self._client)
        return httpx.AsyncClient(timeout=timeout, headers={"User-Agent": USER_AGENT})

    def _load_cache(self) -> dict:
        try:
            return json.loads(self.cache_file.read_text(encoding="utf-8"))
        except (OSError, ValueError):
            return {}

    def _cache_get(self, key: str, max_age: float | None = None) -> Skin | None:
        entry = self._load_cache().get(key)
        if not entry or (max_age is not None and time.time() - entry.get("at", 0) > max_age):
            return None
        return Skin(entry["value"], entry["signature"])

    def _cache_put(self, key: str, skin: Skin) -> None:
        cache = self._load_cache()
        cache[key] = {**asdict(skin), "at": time.time()}
        try:
            self.cache_file.parent.mkdir(parents=True, exist_ok=True)
            self.cache_file.write_text(json.dumps(cache, indent=2), encoding="utf-8")
        except OSError as e:
            log.debug("Cache de skin não gravado: %s", e)


class _Borrowed:
    """Usa um cliente injetado (testes) sem fechá-lo ao sair do with."""

    def __init__(self, client: httpx.AsyncClient):
        self.client = client

    async def __aenter__(self) -> httpx.AsyncClient:
        return self.client

    async def __aexit__(self, *exc) -> None:
        return None
