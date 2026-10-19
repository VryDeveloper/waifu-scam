# Protocolo Corpo ↔ Mente

WebSocket em `ws://127.0.0.1:8765` (texto JSON). A Mente é o servidor; o Corpo (mod) é o cliente e reconecta sozinho.

Toda mensagem tem `v` (sempre `1`), `bot` (id da personagem, `null` antes do welcome) e `type`. Quem recebe ignora campos que não conhece. Schema completo: [`v1.schema.json`](v1.schema.json).

## Fase 0

```text
Corpo                                   Mente
  │ {"v":1,"bot":null,"type":"hello",     │
  │  "protocol":1,"body":"fabric",        │
  │  "body_version":"0.1.0",              │
  │  "minecraft":"26.3"}                  │
  │ ────────────────────────────────────▶ │
  │ {"v":1,"bot":"lumi","type":"welcome", │
  │  "character":{"id":"lumi",            │
  │   "name":"Lumi","language":"pt-BR"}}  │
  │ ◀──────────────────────────────────── │
  │ {"v":1,"bot":"lumi","type":"ping",    │
  │  "id":"p1","t":1760900000000}         │
  │ ────────────────────────────────────▶ │
  │ {"v":1,"bot":"lumi","type":"pong",    │
  │  "id":"p1"}                           │
  │ ◀──────────────────────────────────── │
```

Mensagem inválida → a Mente responde `{"type":"error","message":"..."}` e mantém a conexão.

## Próximas fases

`state`, `skill.start` / `skill.progress` / `skill.done` / `skill.failed` / `skill.cancel`, `chat.say`, `query` / `query.result` e `config` entram como tipos novos, sem mudar o envelope.
