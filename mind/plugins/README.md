# Plugins da Mente

Cada arquivo `.py` aqui pode ser um plugin. Ative pelo `character.toml` da personagem:

```toml
[plugins]
enabled = ["relogio"]
```

Um plugin é uma subclasse de `waifuscam_mind.plugins.Plugin` com os ganchos que quiser:

| Gancho | Quando | Para quê |
|---|---|---|
| `setup(character)` | Ao carregar | Ler configuração, abrir conexões |
| `async on_event(mouth, event)` | Todo evento do jogo | Reagir a chat, morte, perigo; `mouth.say(...)` e `mouth.start_skill(...)` funcionam aqui |
| `prompt(state) -> str` | Antes de cada chamada de IA | Dar contexto novo (clima real, chat da live, notícias) |
| `on_say(text) -> str \| None` | Antes de cada fala | Filtrar ou mudar a fala; `None` cala |

Plugin com erro é desligado e avisado no log, sem derrubar a personagem.

Exemplo pronto: [`relogio.py`](relogio.py), que conta para ela que horas são no mundo real.
