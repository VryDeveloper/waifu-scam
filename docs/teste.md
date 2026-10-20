# Roteiro de teste

Tudo o que foi feito nas fases 3 a 7, na ordem certa para testar. Cada item diz o que fazer e o que deve acontecer. Quando algo der errado, anote o número do item, o que aconteceu e, se puder, as últimas linhas do log da Mente e do jogo (`logs/latest.log`).

## Preparação

1. Atualize o projeto: `git pull` na pasta `Documents\waifu-scam`.
2. Compile o mod: `cd mod` e `.\gradlew.bat build`. Copie `mod\build\libs\waifuscam-0.7.0.jar` para a pasta `mods` do perfil Fabric 26.3, **apagando o jar antigo**.
3. Suba a Mente: `cd mind`, `.\.venv\Scripts\activate` e `python -m waifuscam_mind`. No log deve aparecer `habilidades: follow, goto, obtain, craft, collect, eat, give, deposit, withdraw, hunt, attack, shelter, sleep, stop`.
4. Crie um mundo novo de **sobrevivência** (normal), com árvores e pedra por perto. Abra para LAN. Ela entra e cumprimenta.
5. Para testar à vontade, use `/gamerule doDaylightCycle false` e `/time set day` (assim a noite não atrapalha). Volte com `true` nos testes de noite.

Os comandos `/waifu ...` obedecem na hora (servem para testar o corpo). O chat passa pela IA, que decide do jeito dela.

## Fase 3 — Movimento

| # | Faça | Esperado |
|---|---|---|
| 3.1 | `/waifu follow` e ande por morro, buraco raso e água | Ela vem atrás, pula degrau, nada, corre quando você se afasta |
| 3.2 | `/waifu goto x y z` num ponto do outro lado de uma parede ou rio | Ela contorna e chega |
| 3.3 | `/waifu stop` | Ela para e fica olhando para você |
| 3.4 | No chat: `lumi me segue`, depois `lumi para` | Ela responde e obedece (se topar) |
| 3.5 | `/waifu status` enquanto ela segue | Mostra "seguindo <você>" |

## Fase 4 — Mãos

| # | Faça | Esperado |
|---|---|---|
| 4.1 | `/waifu collect oak_log 4` perto de árvores | Ela vai até a árvore, quebra 4 troncos (com rachadura aparecendo) e pega os itens |
| 4.2 | `/waifu inv` | Mostra os troncos |
| 4.3 | `/waifu craft crafting_table` | Ela faz tábuas e a bancada |
| 4.4 | **Critério da fase:** com o inventário vazio (`/clear Lumi`), `/waifu obtain stone_pickaxe` | Sozinha: tronco → tábuas → gravetos → bancada (coloca no chão) → picareta de madeira → cava até a pedra → picareta de pedra |
| 4.5 | Coloque blocos de pedregulho e uma casinha de tábua perto, e peça `/waifu collect cobblestone 3` | Ela **não** quebra a sua construção; minera pedra natural |
| 4.6 | Dê carvão e ferro bruto para ela (`/give Lumi raw_iron 3` e `coal 2`) e `/waifu obtain iron_ingot 3` | Ela faz ou usa uma fornalha, espera assar de verdade e pega as barras |
| 4.7 | `/waifu give torch 4` | Ela faz as tochas (se precisar) e joga na sua frente |
| 4.8 | Coloque um baú perto e `/waifu deposit` | Ela guarda tudo menos ferramentas, armas, armadura e comida |
| 4.9 | No chat: `lumi faz uma picareta de pedra pra mim` | Ela topa (ou adia) e faz |
| 4.10 | `/give Lumi bread 3`, deixe ela com fome (`/effect give Lumi hunger 30 50`) | Ela come sozinha quando a fome passa da metade |

## Fase 5 — Vontade própria

| # | Faça | Esperado |
|---|---|---|
| 5.1 | Chame um zumbi perto dela (`/summon zombie ~3 ~ ~`) | Ela pega a melhor arma, revida e avisa no chat |
| 5.2 | `/summon creeper ~4 ~ ~` | Ela corre para longe |
| 5.3 | `/waifu hunt cow` perto de vacas | Ela mata uma vaca adulta e pega a carne |
| 5.4 | `/waifu obtain cooked_beef 2` sem carne | Ela caça, faz fornalha se precisar e assa |
| 5.5 | **Critério da fase:** volte o ciclo de dia, `/time set 11500` e deixe ela sozinha ao ar livre | Ao anoitecer ela avisa, cava um buraco de 3, fecha em cima, espera e de manhã sobe tapando o buraco. Ela não morre durante a noite |
| 5.6 | De noite, peça `lumi pega madeira pra mim` | Ela adia ("de manhã eu pego") e, quando amanhece e ela fica livre, faz |
| 5.7 | Fique uns 3 minutos sem falar com ela, de dia | Ela decide sozinha o que fazer e fala alguma coisa |
| 5.8 | `/waifu reflexes off`, chame um zumbi, depois `/waifu reflexes on` | Desligado ela não reage; ligado volta a revidar |

## Fase 6 — Memória

| # | Faça | Esperado |
|---|---|---|
| 6.1 | Num lugar marcante, diga `lumi, aqui é a nossa base` | Ela confirma (guarda com `[lembrar: base]`, que não aparece no chat) |
| 6.2 | Ande para longe com ela e diga `lumi, volta pra base` | Ela vai até lá |
| 6.3 | **Critério da fase:** feche a Mente (Ctrl+C) e o jogo, abra de novo e diga `lumi, lembra onde fica a base?` / `vai pra base` | Ela lembra e vai |
| 6.4 | Conte algo sobre você (`eu odeio aranha`), reinicie a Mente e pergunte | Ela lembra (se tiver guardado a nota) |
| 6.5 | `/waifu remember mina` num lugar, depois `lumi vai pra mina` | Ela vai |
| 6.6 | Deixe ela morrer (`/kill Lumi`) e depois `lumi, volta onde você morreu` | Ela vai até o lugar da última morte |
| 6.7 | Depois de uma noite com conversa, abra `mind\data\memory\lumi.json` | Tem lugares, pessoas, notas e uma entrada no diário |

## Fase 7 — Polimento

| # | Faça | Esperado |
|---|---|---|
| 7.1 | Coloque uma cama perto e, de noite, `/waifu sleep` | Ela deita e acorda de manhã |
| 7.2 | Com uma cama perto, deixe anoitecer (5.5) | Ela prefere dormir na cama a cavar um buraco |
| 7.3 | `/give Lumi iron_chestplate` e `/give Lumi shield` | Em alguns segundos ela veste o peitoral e põe o escudo na outra mão |
| 7.4 | Ative o plugin de exemplo (`[plugins] enabled = ["relogio"]` no `character.toml`), reinicie a Mente e pergunte `lumi que horas são?` | Ela sabe a hora do mundo real |

## Se algo travar

- Ela parou no meio de uma tarefa: `/waifu status` mostra o que ela acha que está fazendo; `/waifu stop` destrava.
- Ela fala que vai fazer algo e não faz: veja no log da Mente se apareceu `vai fazer ...`. Se não apareceu, a IA não colocou a ação; mande o trecho do log.
- Erro no jogo: copie as linhas com `waifuscam` do `logs/latest.log`.
