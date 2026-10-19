# Waifu Scam

[![CI](https://github.com/VryDeveloper/waifu-scam/actions/workflows/ci.yml/badge.svg)](https://github.com/VryDeveloper/waifu-scam/actions/workflows/ci.yml)
![Minecraft](https://img.shields.io/badge/Minecraft-26.3-62B47A)
![Fabric](https://img.shields.io/badge/Fabric-0.19.5-DBD0B4)
![Python](https://img.shields.io/badge/Python-3.12+-3776AB?logo=python)
![License](https://img.shields.io/badge/License-MIT-yellow)

**Uma player de IA com personalidade de VTuber que entra no seu mundo de Minecraft quando você abre a LAN.**

Ela joga como uma player de verdade: tem vida e fome, vontades próprias, conversa pelo chat com a personalidade que você escrever e decide se aceita, adia ou recusa os seus pedidos. A IA roda em planos gratuitos (Groq, Gemini, OpenRouter) ou localmente com Ollama.

> **Status: fase 1 de 7.** Ela entra no mundo quando você abre a LAN, com skin, vida e fome de verdade, e fala no chat (por enquanto só em eco, sem IA — isso é a fase 2). Veja o [roadmap](#roadmap).

## Como funciona

```text
┌──────────── seu Minecraft 26.3 (servidor da LAN) ────────────┐
│  Corpo: mod Fabric                                           │
│  fake player · percepção · habilidades · reflexos · chat     │
└──────────────────────────┬───────────────────────────────────┘
                           │ WebSocket local (JSON, protocolo v1)
┌──────────────────────────┴───────────────────────────────────┐
│  Mente: Python                                               │
│  persona · necessidades · decisão com LLM · memória          │
└──────────────────────────────────────────────────────────────┘
```

**O LLM decide o quê; o mod decide como.** O modelo escolhe objetivos ("coletar 10 troncos"), e o mod executa com código determinístico a 20 ticks por segundo. Detalhes em [docs/arquitetura.md](docs/arquitetura.md).

## Requisitos

| Para | Precisa de |
|---|---|
| Jogar | Minecraft Java **26.3** com [Fabric Loader](https://fabricmc.net/use/installer/) 0.19.5+ e [Fabric API](https://modrinth.com/mod/fabric-api) |
| Rodar a Mente | Python **3.12+** |
| Desenvolver o mod | JDK **25** e IntelliJ IDEA 2025.3+ (exigido para mixins na 26.x) |

Só quem abre a LAN instala o mod. Amigos entram com o jogo normal.

## Começando

### 1. Suba a Mente

```bash
git clone https://github.com/VryDeveloper/waifu-scam.git
cd waifu-scam/mind
python -m venv venv
```

| Linux / macOS | Windows (PowerShell) |
|---|---|
| `source venv/bin/activate` | `.\venv\Scripts\Activate.ps1` |

```bash
python -m pip install -e ".[dev]"
python -m waifuscam_mind
```

Deve aparecer: `Mente ouvindo em ws://127.0.0.1:8765 com a personagem Lumi`.

Para testar sem o jogo, abra outro terminal (com o venv ativo) e rode o corpo simulado. Com `--chat` ela "entra" num mundo de mentira e você conversa pelo terminal:

```bash
python -m waifuscam_mind.sim.body --chat
```

### 2. Rode o mod

**Pelo código (desenvolvimento):**

```bash
cd mod
./gradlew runClient        # Windows: .\gradlew.bat runClient
```

Isso baixa o Minecraft 26.3, abre o jogo com o mod e a Fabric API. Crie um mundo, abra para LAN (Esc → Abrir para LAN) e ela entra do seu lado.

**No seu jogo de verdade:**

```bash
cd mod
./gradlew build
```

Copie `mod/build/libs/waifuscam-0.2.0.jar` e o jar da Fabric API para a pasta `mods` do perfil Fabric 26.3. Na primeira execução o mod cria `config/waifuscam.json`:

| Campo | Padrão | O que faz |
|---|---|---|
| `mind_url` | `ws://127.0.0.1:8765` | Onde está a Mente |
| `reconnect_seconds` | `5` | Intervalo entre tentativas de reconexão |
| `join_on` | `lan` | Quando ela entra: `lan` (ao abrir a LAN), `world` (ao entrar no mundo) ou `command` (só com `/waifu spawn`) |

### 3. Comandos no jogo

| Comando | O que faz |
|---|---|
| `/waifu status` | Conexão com a Mente, personagem, vida e fome dela |
| `/waifu ping` | Mede a latência até a Mente |
| `/waifu spawn` | Chama a personagem para perto de você |
| `/waifu despawn` | Tira ela do mundo (o inventário fica salvo) |

Ela é um player de verdade: aparece no Tab, sente fome, pode morrer (solta os itens e renasce sozinha) e pode ser expulsa com `/kick`. No chat, fale o nome dela que ela responde em eco.

## Crie a sua personagem

Cada personagem é uma pasta com `character.toml` (nome, skin, pesos das necessidades, comandos, modelos de IA) e `persona.md` (a personalidade, em texto livre). Use a [Lumi](characters/lumi/) como modelo:

```bash
cp -r characters/lumi characters/private/minha-vtuber
```

Edite os dois arquivos e defina `WAIFU_CHARACTER=minha-vtuber` no `.env` (copie de `.env.example`). Tudo em `characters/private/` e `skins/` fica fora do Git.

### Skin

Em `character.toml`, seção `[skin]`, escolha uma forma:

| Opção | Como | Precisa de |
|---|---|---|
| Copiar de uma conta | `username = "NomeDaConta"` | Nada |
| PNG próprio | `file = "skin.png"` (64×64, na pasta da personagem) e `model = "slim"` ou `"classic"` | `MINESKIN_API_KEY` no `.env`, grátis em [account.mineskin.org/keys](https://account.mineskin.org/keys) |

A Mente assina a skin uma vez e guarda em cache (`mind/data/`). Para trocar, mude o arquivo, reinicie a Mente e use `/waifu despawn` e `/waifu spawn`.

## Estrutura

```text
waifu-scam/
├── mod/            # Corpo: mod Fabric (Java 25, Gradle)
│   └── src/main/java/dev/vry/waifuscam/
│       ├── WaifuScamMod.java   # entrada do mod
│       ├── bot/                # a personagem: fake player, conexão falsa, ciclo de vida
│       ├── mixin/              # ajustes no jogo (respawn, física, conexão)
│       ├── bridge/             # cliente WebSocket + protocolo (sem dependência do Minecraft)
│       ├── command/            # /waifu
│       └── config/             # config/waifuscam.json
├── mind/           # Mente: Python
│   ├── waifuscam_mind/
│   │   ├── server.py           # servidor WebSocket + handlers por tipo de mensagem
│   │   ├── protocol.py         # modelos Pydantic do protocolo
│   │   ├── character.py        # carrega o pacote de personagem
│   │   ├── skins.py            # skin por conta (Mojang) ou PNG (MineSkin), com cache
│   │   ├── brain/              # o que ela faz com os eventos (fase 1: eco)
│   │   └── sim/body.py         # corpo simulado para testes
│   └── tests/
├── characters/     # personagens (private/ fica fora do Git)
├── protocol/       # JSON Schema do protocolo v1
└── docs/
```

## Roadmap

| Fase | Entrega | Pronto quando |
|---|---|---|
| 0 · Fundação ✅ | Ambiente Fabric 26.3, esqueleto da Mente, WebSocket | O mod carrega e a Mente responde ao ping |
| **1 · O corpo existe** 🧪 | Fake player entra ao abrir a LAN, skin, eco de chat | Ela aparece no Tab, sente fome e morre de verdade |
| 2 · Conversa | Chat com a persona, fallback de provedores, orçamento | Você conversa com ela pelo chat |
| 3 · Movimento | Andar, olhar, pular, seguir, pathfinding A* | Ela te segue por terreno irregular |
| 4 · Mãos | Quebrar, coletar, craftar, fundir, comer, baús | Faz uma picareta de pedra do zero |
| 5 · Vontade própria | Necessidades, decisão por tool calling, aceitar/adiar/recusar | Passa uma noite sozinha sem morrer |
| 6 · Memória | Lugares, relações, promessas, resumo diário | Lembra onde fica a base depois de reiniciar |
| 7 · Polimento | Combate, dormir, abrigo, várias personagens, plugins | Contínuo |

## Desenvolvimento

```bash
# Mente
cd mind && pytest && ruff check .

# Mod
cd mod && ./gradlew build test
```

O CI roda os dois a cada push.

## Licença e créditos

MIT — veja [LICENSE](LICENSE).

O fake player (`mod/.../bot/` e `mod/.../mixin/`) é adaptado do [Carpet](https://github.com/gnembon/fabric-carpet), de gnembon, também sob licença MIT.

Feito por [VryDeveloper](https://github.com/VryDeveloper).
