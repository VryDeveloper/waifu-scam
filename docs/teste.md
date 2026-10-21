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

## Correções do primeiro teste

| # | Faça | Esperado |
|---|---|---|
| C.1 | `lumi pega madeira` e observe | Ela vira a cabeça aos poucos, mira, para um instante entre um bloco e outro |
| C.2 | Converse rápido (3 ou 4 mensagens seguidas) | Ela responde em poucos segundos, sem ficar muda |
| C.3 | Bata num iron golem e diga `lumi me ajuda a bater nele` | Ela ataca o mesmo golem |
| C.4 | `lumi ataca aquele zumbi` | Ela ataca zumbis |
| C.5 | `lumi coloca a fornalha no chão` (com fornalha no inventário dela) | Ela coloca de verdade |
| C.6 | `lumi joga uma tocha pra mim` / `lumi pega o que tá no chão` | Ela joga / pega |
| C.7 | `lumi abre a porta` perto de uma porta | Ela vai até a porta e abre |
| C.8 | `lumi quebra a fornalha` | Ela quebra e pega a fornalha |
| C.9 | De noite, com ela no abrigo, diga `lumi vem aqui` | Ela sai do buraco e vem |
| C.10 | Peça algo que ela não sabe fazer (`lumi constrói uma casa`) | Ela diz que ainda não consegue, em vez de fingir que fez |

## Fase 8 — Visão, mineração e jeito de companheira

| # | Faça | Esperado |
|---|---|---|
| 8.1 | Fique na frente dela e pergunte `lumi o que você tá vendo?` | Ela fala de você e do que está à vista. Fique atrás dela e pergunte de novo: ela não diz que está te vendo |
| 8.2 | Leve ela até uma caverna com minério de ferro à vista, saia de lá e depois peça `lumi pega 5 ferro` | Ela volta até onde viu o ferro, em vez de cavar do zero |
| 8.3 | Reinicie a Mente e o jogo, depois pergunte `lumi onde tinha ferro?` | Ela lembra (a memória fica em `mind/data/memory/`) |
| 8.4 | `lumi pega 3 diamantes` (com picareta de ferro) | Ela cava em escada até perto do Y -53 e abre túnel de lá, pondo tocha se tiver |
| 8.5 | Deixe ela ver um diamante ou uma vila | Ela comenta, uma vez |
| 8.6 | Deixe ela sozinha uns minutos | Ela evolui as ferramentas em silêncio; nada de avisar cada picareta ou fornalha |
| 8.7 | A skin | É a skin nova (PNG em `characters/lumi/skin.png`) |

## Fase 9 — Receitas

| # | Faça | Esperado |
|---|---|---|
| 9.1 | `/waifu plan white_bed` | Mostra no chat o passo a passo que ela faria agora (caçar ovelha, tábuas, bancada, cama) |
| 9.2 | `lumi faz uma cama pra mim` | Ela topa e faz: tosquia (se tiver tesoura) ou caça ovelha branca, faz tábuas, bancada e a cama |
| 9.3 | Dê `raw_iron` para ela e diga `lumi faz ferro` perto de uma fornalha | Ela usa a fornalha que já está ali, sem fazer outra |
| 9.4 | `lumi como faz uma bússola?` | Responde curto com a receita certa (4 lingotes de ferro + redstone, na bancada) |
| 9.5 | Sem picareta e sem pedra por perto, `lumi faz um balde` | Ela diz o que falta em vez de prometer |
| 9.6 | No log da Mente | Ao subir: `Nomes de ... itens e blocos, da tradução do jogo`; a cada pedido: `Plano para ...` |

## Fase 10 — Baú e horta

| # | Faça | Esperado |
|---|---|---|
| 10.1 | Ponha 3 lingotes de ferro num baú perto e diga `lumi faz uma picareta de ferro` | Ela tira o ferro do baú em vez de minerar (`/waifu plan iron_pickaxe` mostra "tirar do baú") |
| 10.2 | Faça uma plantação de trigo, espere amadurecer e diga `lumi colhe o trigo` | Ela colhe só o maduro e replanta cada pé |
| 10.3 | `lumi faz pão` com trigo maduro por perto | Colhe, replanta e crafta o pão |
| 10.4 | Dê uma enxada e sementes, perto de água: `lumi planta essas sementes` | Ela ara até 9 blocos perto da água e planta |
| 10.5 | Deixe ela perto da horta, sozinha, por uns minutos | Ela cuida da plantação de vez em quando (no máximo a cada 20 min), sem avisar |
| 10.6 | Ponha `"use_chests": false` em `config/waifuscam.json` e reabra o jogo | Ela não mexe mais nos baús para craftar |

## Fase 11 — Aventura (arco e escolta)

| # | Faça | Esperado |
|---|---|---|
| 11.1 | `/give Lumi bow` e `/give Lumi arrow 32`, depois deixe um esqueleto ou zumbi vir de longe | Ela puxa o arco, mira um pouco acima e atira antes de ele chegar |
| 11.2 | Fique na frente do bicho, entre ele e ela | Ela segura o tiro enquanto você está no caminho |
| 11.3 | Um creeper vindo a uns 10 blocos | Ela atira; se ele chegar perto, ela foge |
| 11.4 | Deixe um zumbi atacar você (não ela), perto dela | Ela vem te defender |
| 11.5 | `lumi me segue` e entre numa caverna escura, com tochas no inventário dela | Ela deixa tocha no caminho a cada ~8 blocos |
| 11.6 | Deixe ela sozinha com o equipamento de ferro pronto | Ela faz um arco e depois flechas, sem avisar |

## Fase 12 — Construir

| # | Faça | Esperado |
|---|---|---|
| 12.1 | Num campo plano, `lumi constrói uma casinha pra gente` (ou `/waifu build`) | Ela junta tábuas se faltar, limpa o terreno, levanta paredes e teto de dentro, põe porta e tocha e sai pela porta |
| 12.2 | `lumi faz uma casa de pedra` | Mesma casinha, de pedregulho |
| 12.3 | Depois, longe dali: `lumi vamos pra casa` | Ela lembra (a casa vira o lugar "casa") e vai até lá |
| 12.4 | Na beira de um rio, olhando para o outro lado: `lumi faz uma ponte` (ou `/waifu bridge`) | Ela vai agachada pondo bloco na frente até o outro lado |
| 12.5 | Num lugar todo irregular (montanha) | Ela diz que não achou lugar plano em vez de fazer torto |

## Fase 13 — Ajudar no que você faz

Com `/waifu autonomy on`. Ela olha o último minuto do que você fez.

| # | Faça | Esperado |
|---|---|---|
| 13.1 | Corte 3 ou 4 troncos perto dela | Ela comenta algo curto e vem cortar árvore também |
| 13.2 | Construa com pedregulho (coloque uns 5 blocos) | Ela junta pedregulho e te entrega |
| 13.3 | Desça numa caverna (abaixo de Y 50) | Ela vai junto, deixando tocha no escuro; quando você volta para cima, ela para de te seguir em ~30 s |
| 13.4 | Lute com um zumbi | Ela fica do seu lado e ajuda |
| 13.5 | Bata numa vaca | Ela caça junto |
| 13.6 | Fique longe dela (40 blocos) cortando árvore | Primeiro ela vai até você, depois ajuda |
| 13.7 | Pergunte "lumi o que eu tô fazendo?" | Ela sabe (aparece no prompt dela) |
| 13.8 | Não faça nada de especial | Ela segue os objetivos básicos dela (picareta, madeira...), sem repetir o que já tem |

## Fase 16 — Montarias, bichos, pesca e pedidos em partes

| # | Faça | Esperado |
|---|---|---|
| 16.1 | Entre num barco perto dela e diga `lumi entra no barco comigo` (ou `/waifu mount`) | Ela senta no segundo lugar do seu barco e vai junto |
| 16.2 | `lumi me segue`, depois entre num barco | Ela entra junto sozinha; quando você desce, ela desce |
| 16.3 | Cavalo domado e com sela perto: `lumi sobe no cavalo` e depois `lumi me segue` | Ela monta e vem atrás de você a cavalo |
| 16.4 | Carrinho no trilho: `lumi entra no carrinho` | Ela entra; `lumi desce` (ou `/waifu dismount`) tira ela |
| 16.5 | Duas vacas perto: `lumi faz as vacas terem filhote` (ou `/waifu breed cow`) | Ela arruma trigo se não tiver e dá para as duas |
| 16.6 | Lobo selvagem perto: `lumi domestica aquele lobo pra mim` (ou `/waifu tame wolf`) | Ela dá osso até ele ficar de coleira, e o lobo fica seu |
| 16.7 | Perto de água: `lumi pesca uns peixes` (ou `/waifu fish`) | Ela faz a vara se não tiver, joga a linha, espera afundar e puxa |
| 16.8 | Fique conversando com ela dentro d'água | Ela fica boiando com a cabeça fora d'água |
| 16.9 | `lumi faz uma fornalha e coloca no chão` | Ela faz e depois coloca, sem precisar pedir de novo |

## Fase 15 — Expressões

Todas funcionam sem mod no cliente (seu amigo também vê). Para testar uma por uma: `/waifu emote <nome>` (hearts, wave, jump, nod, shake, shy, spin, crouch, sit, stand, happy, music).

| # | Faça | Esperado |
|---|---|---|
| 15.1 | `lumi você é linda` | Ela responde e solta coraçõezinhos (ou fica tímida, olhando para baixo) |
| 15.2 | Entre no mundo | Ela acena para você no "oi" |
| 15.3 | `lumi senta aqui comigo` | Ela senta no chão do seu lado; levanta sozinha quando precisa andar ou aparece monstro |
| 15.4 | Fique parado uns 30 s | Ela vem pertinho e senta do seu lado, e fica sentada enquanto você está parado |
| 15.5 | Agache 3 vezes olhando para ela | Ela agacha de volta com coraçõezinhos |
| 15.6 | Quando ela te der uma flor | Ela fica tímida |

## Fase 14 — Momentos juntos

| # | Faça | Esperado |
|---|---|---|
| 14.1 | Olhando para ela, agache e levante 3 vezes rápido | Ela agacha de volta e reage com carinho (ou tímida) |
| 14.2 | Fique parado uns 30 s a uns 10 blocos dela | Ela vem ficar pertinho de você |
| 14.3 | Jogue de dia uns 20 min com ela perto | Em algum momento ela traz uma flor de presente e fala algo fofo |
| 14.4 | Deite numa cama de noite (com outra cama do lado) | Ela vai dormir também |
| 14.5 | Construam a primeira casa (`/waifu build`) e depois pergunte "lumi lembra da nossa casa?" | Ela lembra (vira um momento especial) |
| 14.6 | Uma semana depois de vocês se conhecerem (no tempo real), entre no mundo | Ela lembra a data no "oi" |

## Correções do teste da fase 13

| # | Faça | Esperado |
|---|---|---|
| D.1 | Com carne crua no inventário dela: `lumi faz comida` (ou `/waifu cook`) | Ela assa toda a carne crua que tem |
| D.2 | Sem nada cru: `lumi cozinha alguma coisa` | Ela arruma comida (caça e assa, ou faz pão) |
| D.3 | Dê um escudo e deixe um zumbi vir | Bate, levanta o escudo enquanto a espada recarrega, bate de novo |
| D.4 | Esqueleto atirando de longe | Ela vai até ele com o escudo na frente |
| D.5 | Creeper chegando | Ela bate e recua, bate e recua; se ele começar a inchar, corre |
| D.6 | Mine um minério perto dela (ou `lumi me ajuda a minerar`) | Ela quebra os outros minérios à vista em volta e segue o veio |

## Desligar o comportamento espontâneo

| # | Faça | Esperado |
|---|---|---|
| A.1 | `/waifu autonomy off` | Ela para o que inventou de fazer e passa a só fazer o que pedirem (o abrigo da noite continua) |
| A.2 | Reinicie a Mente | Continua desligado (fica salvo na memória dela) |
| A.3 | `/waifu autonomy on` | Ela volta a seguir os objetivos dela |

## Se algo travar

- Ela parou no meio de uma tarefa: `/waifu status` mostra o que ela acha que está fazendo; `/waifu stop` destrava.
- Ela fala que vai fazer algo e não faz: veja no log da Mente se apareceu `vai fazer ...`. Se não apareceu, a IA não colocou a ação; mande o trecho do log.
- Erro no jogo: copie as linhas com `waifuscam` do `logs/latest.log`.
