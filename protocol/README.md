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

## Fase 1

| Tipo | Direção | Para quê |
|---|---|---|
| `welcome` + `username`, `skin` | Mente → Corpo | Nome de player e textura assinada (ou `null`) |
| `state` | Corpo → Mente, a cada 2 s | `online`, `alive`, `health`, `food`, posição, dimensão |
| `event` `spawned` / `despawned` / `died` / `respawned` | Corpo → Mente | Ciclo de vida; `died` traz `message` com a mensagem de morte |
| `event` `chat` | Corpo → Mente | Chat de players: `from`, `text` |
| `chat.say` | Mente → Corpo | Ela fala no chat |

## Fase 2

`state` ganha `players` (quem está online), `time_of_day` (0 a 23999 ticks) e `weather` (`clear`, `rain`, `thunder`), usados pela Mente para dar contexto à conversa.

## Fase 3

O corpo manda no `hello` o catálogo `skills` (`name`, `description`, `params` em JSON Schema). A Mente só pede o que está nele, então uma habilidade nova no mod aparece para a IA sem mexer na Mente.

| Tipo | Direção | Para quê |
|---|---|---|
| `skill.start` | Mente → Corpo | `id`, `skill`, `args`. Interrompe a habilidade atual. `stop` para tudo |
| `skill.cancel` | Mente → Corpo | Para a habilidade atual |
| `skill.done` | Corpo → Mente | `id`, `skill`: terminou bem |
| `skill.failed` | Corpo → Mente | `id`, `skill`, `reason` em português; `cancelled: true` quando não foi falha (trocada, mandaram parar, ela morreu ou saiu) |

`state` ganha `skill` (`id`, `name`, `doing`) com o que ela está fazendo agora.

```text
Corpo                                    Mente
  │ hello {"skills":[{"name":"follow",…}]} │
  │ ─────────────────────────────────────▶ │
  │  … "Vry: lumi me segue" (event chat) … │
  │ ─────────────────────────────────────▶ │
  │ {"type":"skill.start","id":"mind-1",   │
  │  "skill":"follow","args":{"player":"Vry"}}
  │ ◀───────────────────────────────────── │
  │ {"type":"skill.failed","id":"mind-1",  │
  │  "reason":"Vry foi para outra dimensão",│
  │  "cancelled":false}                    │
  │ ─────────────────────────────────────▶ │
```

Habilidades da fase 3: `follow` (`player`), `goto` (`player` ou `x`, `y`, `z`) e `stop`.

## Fase 4

Habilidades novas no catálogo: `obtain` (`item`, `count`: ficar com pelo menos count), `craft` (mais count), `collect` (minerar mais count), `eat`, `give` (`player`, `item`, `count`), `deposit` e `withdraw` (`item`, `count`). Itens vão pelo id do Minecraft (`stone_pickaxe`, `minecraft:torch`) ou por tag (`#logs`).

`state` ganha `inventory` (até 20 itens, `{"oak_log": 12, ...}`) e `holding` (o item na mão).

## Fase 5

Habilidades novas: `hunt` (`animal`, `count`), `attack` (`count`) e `shelter`. `obtain` passa a caçar quando o item vem de bicho (carne crua, couro, lã).

`state` ganha `threats` (`[{"type": "zombie", "distance": 4.2}]`), `sky` (ao ar livre?), `light`, `in_water`, `on_fire`, `air` e `reflex` (o que o reflexo está fazendo, ex.: `comendo`). Evento novo: `event` `danger` com `message` = tipo do bicho (no máximo um a cada 20 s por tipo).

## Fase 6

Evento novo do Corpo: `event` `remember_place` (`name`, `x`, `y`, `z`, `dimension`, `from`), mandado pelo `/waifu remember`. A memória fica toda na Mente; `goto` com o nome de um lugar lembrado vira coordenadas antes de sair para o Corpo.

## Fase 7

Habilidade nova: `sleep` (dormir na cama livre mais perto). `shelter` passa a preferir cama quando tem uma por perto. Armadura e escudo são vestidos sozinhos pelo corpo, sem mensagem nova.

## Fase 8: visão

`state` ganha `world` (pasta do mundo), `seeing` e `players_near`:

- `seeing`: o que está no campo de visão dela agora. O corpo lança raios num cone de ~110° para onde a cabeça está virada, uma vez por segundo, e só vê o que tem linha de visão (no escuro, só de perto). Traz `looking_at`, `biome`, `entities` (`type`, `name`, `distance`, `dir`, `hostile`) e `blocks` (`block`, `distance`, `dir`, `count`).
- `players_near`: players a até 64 blocos, com `dir` relativa a ela e `visible`.

Eventos novos do Corpo: `event` `seen` (`block`, `x`, `y`, `z`, `dimension`, `rare`) quando ela vê pela primeira vez algo que vale lembrar (minério, baú, sino de vila, spawner...), e `event` `gone` quando uma lembrança deixou de valer (minerado).

Mensagem nova da Mente: `memory.blocks` (`world`, `blocks`), mandada no primeiro `state` de cada mundo, com tudo que ela lembra de ter visto ali. O corpo usa isso para voltar a um minério que viu antes quando precisa dele.

## Fase 9: receitas

Mensagens novas: `query` (Mente → Corpo: `id`, `query`, `args`) e `query.result` (Corpo → Mente: o mesmo `id`, `ok`, `failure` e os campos da resposta). O corpo responde na hora, sem mexer no mundo.

`query` `plan` (`item`, `count`): como conseguir o item agora. A resposta traz a receita do jogo (`recipe`: `kind` craft/smelt/gather/hunt, ingredientes, se precisa de bancada) e o passo a passo que ela faria com o inventário dela e com o que tem por perto (`steps`, `have`, `table_nearby`, `furnace_nearby`). É o mesmo planejador do `obtain`. Quando não dá, `ok` é false e `failure` diz o que falta.

## Fase 13: o que os players fazem

`state` ganha `players_activity`: para cada player a até 48 blocos, o último minuto do que ele fez — `broke` (blocos quebrados, `{"oak_log": 3}`), `placed` (bloco na mão ao clicar num bloco, isto é, construindo), `attacked` (bichos atacados), mais `distance`, `y` e `underground`.

## Fase 14: momentos juntos

Evento novo do Corpo: `event` `crouch` (`from`) quando um player agacha e levanta 3 vezes em 2 s olhando para ela (ela agacha de volta sozinha). Em `players_activity`, cada player ganha `sleeping` e `idle_seconds` (há quanto tempo não sai do lugar).

## Próximas fases

`skill.progress`, `query` / `query.result` e `config` entram como tipos novos, sem mudar o envelope.
