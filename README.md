# Waifu Scam

[![CI](https://github.com/VryDeveloper/waifu-scam/actions/workflows/ci.yml/badge.svg)](https://github.com/VryDeveloper/waifu-scam/actions/workflows/ci.yml)
![Minecraft](https://img.shields.io/badge/Minecraft-26.3-62B47A)
![Fabric](https://img.shields.io/badge/Fabric-0.19.5-DBD0B4)
![Python](https://img.shields.io/badge/Python-3.12+-3776AB?logo=python)
![License](https://img.shields.io/badge/License-MIT-yellow)

**Uma player de IA com personalidade de VTuber que entra no seu mundo de Minecraft quando você abre a LAN.**

Ela joga como uma player de verdade: tem vida e fome, vontades próprias, conversa pelo chat com a personalidade que você escrever e decide se aceita, adia ou recusa os seus pedidos. A IA roda em planos gratuitos (Groq, Gemini, OpenRouter) ou localmente com Ollama.

> **Status: fases 1 a 7 feitas, em teste.** Ela entra no mundo quando você abre a LAN, com skin, vida e fome de verdade, conversa com a personalidade dela usando IA gratuita, anda, minera, crafta, funde, caça, come, usa baús e te entrega itens ("faz uma picareta de pedra" ela resolve do zero). E tem vontade própria: foge de creeper, revida, come quando tem fome, cava um abrigo quando anoitece, adia pedidos quando tem algo mais urgente e, parada, decide sozinha o que fazer. E lembra: lugares (a base, onde morreu), quem é quem, o que prometeu e um diário do que aconteceu, mesmo depois de reiniciar. Veja o [roadmap](#roadmap).

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

**Para ela conversar com IA**, copie `.env.example` para `.env` na raiz do repositório e preencha pelo menos uma chave grátis:

| Provedor | Onde pegar a chave |
|---|---|
| Groq (recomendado) | [console.groq.com/keys](https://console.groq.com/keys) |
| Google Gemini | [aistudio.google.com/apikey](https://aistudio.google.com/apikey) |
| OpenRouter | [openrouter.ai/keys](https://openrouter.ai/keys) |

Com mais de uma chave, se um provedor bater o limite grátis o próximo assume. Sem nenhuma, ela só responde em eco. Também dá para usar um modelo local com Ollama (veja `.env.example`).

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

Copie `mod/build/libs/waifuscam-0.7.0.jar` e o jar da Fabric API para a pasta `mods` do perfil Fabric 26.3. Na primeira execução o mod cria `config/waifuscam.json`:

| Campo | Padrão | O que faz |
|---|---|---|
| `mind_url` | `ws://127.0.0.1:8765` | Onde está a Mente |
| `reconnect_seconds` | `5` | Intervalo entre tentativas de reconexão |
| `join_on` | `lan` | Quando ela entra: `lan` (ao abrir a LAN), `world` (ao entrar no mundo) ou `command` (só com `/waifu spawn`) |
| `use_chests` | `true` | Ela pode pegar ingredientes dos baús a até 16 blocos quando precisa fazer algo |

### 3. Comandos no jogo

| Comando | O que faz |
|---|---|
| `/waifu status` | Conexão com a Mente, personagem, vida, fome e o que ela está fazendo |
| `/waifu ping` | Mede a latência até a Mente |
| `/waifu spawn` | Chama a personagem para perto de você |
| `/waifu despawn` | Tira ela do mundo (o inventário fica salvo) |
| `/waifu follow [player]` | Ela segue você (ou outro player) até mandarem parar |
| `/waifu come` | Ela vem até você |
| `/waifu goto <x y z>` | Ela vai até o ponto (aceita `~`) |
| `/waifu stop` | Ela para o que está fazendo |
| `/waifu obtain <item> [n]` | Ela fica com pelo menos n do item, fazendo tudo o que precisar (ex.: `/waifu obtain stone_pickaxe`) |
| `/waifu craft <item> [n]` | Ela crafta mais n do item |
| `/waifu collect <item> [n]` | Ela minera mais n de um recurso (`oak_log`, `cobblestone`, `coal`...) |
| `/waifu give <item> [n]` | Ela consegue o item (se não tiver) e te entrega |
| `/waifu eat` | Ela come a melhor comida que tiver |
| `/waifu deposit` | Ela guarda no baú mais perto tudo menos ferramentas, armas, armadura e comida |
| `/waifu inv` | Mostra o inventário dela |
| `/waifu shelter` | Ela cava um abrigo e passa a noite nele |
| `/waifu hunt <bicho>` | Ela caça `cow`, `pig`, `sheep`, `chicken` ou `rabbit` |
| `/waifu attack` | Ela luta contra os monstros por perto |
| `/waifu reflexes on\|off` | Liga ou desliga os reflexos (fugir, revidar, comer sozinha) |
| `/waifu autonomy on\|off` | Liga ou desliga o que ela faz por conta própria (objetivos, ideias soltas). Fica salvo; o abrigo da noite continua |
| `/waifu sleep` | Ela dorme na cama livre mais perto até amanhecer |
| `/waifu remember <nome>` | Ela guarda na memória o lugar onde você está (ex.: `/waifu remember base`) |

Os comandos obedecem na hora, para testar. Pelo chat é diferente: você pede ("lumi, me segue", "faz uma picareta de pedra", "me dá 10 tochas", "guarda as coisas no baú") e ela decide se topa, do jeito da persona. Sem chave de IA, ela entende só "me segue", "vem aqui" e "para".

Sem ninguém pedindo nada, ela vive: por reflexo foge de creeper, revida quem bate nela e come quando a fome aperta; quando anoitece ao ar livre, cava um buraco, fecha em cima e sai de manhã tapando o buraco. Parada há um tempo (`[will] think_every`, padrão 3 min), ela decide sozinha o que fazer, pesando as necessidades do `character.toml` (`[needs]`). Pedido que ela topa mas não pode fazer agora vira promessa, e ela cumpre quando ficar livre.

Ela lembra das coisas entre uma sessão e outra (`mind/data/memory/<personagem>.json`, fora do Git): lugares ("lumi, aqui é a nossa base" e depois "volta pra base"), o lugar onde morreu pela última vez, quem ela conhece e há quanto tempo, notas que ela decide guardar, promessas pendentes, as últimas falas da conversa e um diário que ela escreve ao amanhecer.

Ela não desmonta construção: tronco ela só tira de árvore (com folha natural por perto), pedregulho só de pedra, e nunca quebra tábua, vidro ou bloco colocado por player para conseguir material. Não mexe em fornalha de outra pessoa.

Ela é um player de verdade: aparece no Tab, sente fome, pode morrer (solta os itens e renasce sozinha) e pode ser expulsa com `/kick`. No chat, ela responde quando você fala o nome dela, quando só tem você online com ela, ou quando você continua uma conversa que ela acabou de responder. Ela também cumprimenta ao entrar e comenta quando morre.

Para não estourar a cota grátis, existe um limite diário de chamadas (`[llm] daily_limit` no `character.toml`, padrão 800). Quando acaba, ela avisa no chat e volta a falar no dia seguinte.

## Crie a sua personagem

Cada personagem é uma pasta com `character.toml` (nome, skin, pesos das necessidades, comandos, modelos de IA) e `persona.md` (a personalidade, em texto livre). Use a [Lumi](characters/lumi/) como modelo:

```bash
cp -r characters/lumi characters/private/minha-vtuber
```

Edite os dois arquivos e defina `WAIFU_CHARACTER=minha-vtuber` no `.env` (copie de `.env.example`). Tudo em `characters/private/` e `skins/` fica fora do Git.

Além da persona, o `character.toml` controla o jeito dela viver: `[needs]` (o quanto cada necessidade pesa), `[will]` (de quanto em quanto tempo ela decide sozinha, se abriga à noite) e `[plugins]` (extensões da Mente, veja [mind/plugins](mind/plugins/)).

### Skin

Em `character.toml`, seção `[skin]`, escolha uma forma:

| Opção | Como | Precisa de |
|---|---|---|
| Copiar de uma conta | `username = "NomeDaConta"` | Nada |
| PNG próprio | `file = "skin.png"` (64×64, na pasta da personagem) e `model = "slim"` ou `"classic"` | Nada (opcional: `MINESKIN_API_KEY` no `.env`, grátis em [account.mineskin.org/keys](https://account.mineskin.org/keys), para não cair na fila pública) |

A Mente assina a skin uma vez e guarda em cache (`mind/data/`). Para trocar, mude o arquivo, reinicie a Mente e use `/waifu despawn` e `/waifu spawn`.

## Estrutura

```text
waifu-scam/
├── mod/            # Corpo: mod Fabric (Java 25, Gradle)
│   └── src/main/java/dev/vry/waifuscam/
│       ├── WaifuScamMod.java   # entrada do mod
│       ├── bot/                # a personagem: fake player, ciclo de vida, reflexos, olhos (Eyes)
│       ├── mixin/              # ajustes no jogo (respawn, física, conexão)
│       ├── bridge/             # cliente WebSocket + protocolo (sem dependência do Minecraft)
│       ├── pathing/            # A* em grade de blocos (testado sem o jogo)
│       ├── control/            # "teclas" dela (andar, pular, olhar) e seguidor de caminho
│       ├── craft/              # planejador de receitas (testado sem o jogo) + receitas do servidor
│       ├── hands/              # minerar, colocar bloco, craftar, comida, achar blocos
│       ├── skill/              # habilidades (seguir, minerar, craftar, fundir, baú...) e o catálogo
│       ├── command/            # /waifu
│       └── config/             # config/waifuscam.json
├── mind/           # Mente: Python
│   ├── waifuscam_mind/
│   │   ├── server.py           # servidor WebSocket + handlers por tipo de mensagem
│   │   ├── protocol.py         # modelos Pydantic do protocolo
│   │   ├── character.py        # carrega o pacote de personagem
│   │   ├── skins.py            # skin por conta (Mojang) ou PNG (MineSkin), com cache
│   │   ├── memory.py           # memória de longo prazo: lugares, pessoas, notas, diário, o que ela viu
│   │   ├── items.py            # nomes dos itens em português (tradução oficial do jogo + apelidos)
│   │   ├── plugins.py          # carregador de plugins (ganchos on_event, prompt, on_say)
│   │   ├── brain/              # o que ela faz com os eventos (eco ou IA) e as ações que ela escolhe
│   │   ├── llm/                # provedores gratuitos com fallback e orçamento diário
│   │   └── sim/body.py         # corpo simulado para testes
│   ├── plugins/            # plugins da Mente (exemplo: relogio.py)
│   └── tests/
├── characters/     # personagens (private/ fica fora do Git)
├── protocol/       # JSON Schema do protocolo v1
└── docs/
```

## Roadmap

| Fase | Entrega | Pronto quando |
|---|---|---|
| 0 · Fundação ✅ | Ambiente Fabric 26.3, esqueleto da Mente, WebSocket | O mod carrega e a Mente responde ao ping |
| 1 · O corpo existe ✅ | Fake player entra ao abrir a LAN, skin, eco de chat | Ela aparece no Tab, sente fome e morre de verdade |
| 2 · Conversa ✅ | Chat com a persona, fallback de provedores, orçamento | Você conversa com ela pelo chat |
| 3 · Movimento 🧪 | Andar, olhar, pular, seguir, pathfinding A* | Ela te segue por terreno irregular |
| 4 · Mãos 🧪 | Quebrar, coletar, craftar, fundir, comer, baús | Faz uma picareta de pedra do zero |
| 5 · Vontade própria 🧪 | Necessidades, reflexos, abrigo, caça, aceitar/adiar/recusar | Passa uma noite sozinha sem morrer |
| 6 · Memória 🧪 | Lugares, relações, promessas, resumo diário | Lembra onde fica a base depois de reiniciar |
| 7 · Polimento 🧪 | Dormir na cama, armadura e escudo automáticos, plugins da Mente | Contínuo |
| 8 · Visão 🧪 | Campo de visão de verdade, lembrança de onde viu minérios/baús/vilas, mineração na altura certa | Volta sozinha ao ferro que viu ontem |
| 9 · Receitas 🧪 | Todas as receitas do jogo, passo a passo conferido antes de prometer, nomes em português | "Faz uma cama" sai do zero |
| 10 · Baú e horta 🧪 | Usa o que está nos baús da base, colhe, replanta e ara terra | Faz pão com o trigo da horta |
| 11 · Aventura 🧪 | Arco e flecha com mira de verdade, defende quem ela acompanha, tocha nas cavernas | Te protege numa caverna |
| 12 · Construir 🧪 | Casinha 5x5 com porta e tocha, ponte até o outro lado | "Constrói uma casa pra gente" vira casa de verdade |

🧪 = feito e aguardando teste no jogo. O roteiro completo está em [docs/teste.md](docs/teste.md).

Próximos passos depois dos testes: várias personagens ao mesmo tempo, construir, combate à distância (arco), voz.

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
