# Arquitetura

Resumo do documento de arquitetura do projeto.

## Duas partes

- **Corpo** — mod Fabric server-side. Cria um fake player (`ServerPlayer` com conexão falsa) dentro do servidor da LAN. Por ser um player de verdade, ganha vida, fome, inventário, morte e respawn do próprio jogo. Percebe o mundo, executa habilidades e reflexos a 20 ticks por segundo e nunca espera a IA.
- **Mente** — processo Python. Guarda a persona, as necessidades e a memória, e usa um LLM para decidir o que fazer e o que falar. Pensa por evento, não por tick.

Por que separadas: trocar persona ou modelo sem reiniciar o jogo, usar o ecossistema de IA do Python, isolar falhas (se a Mente cair, o Corpo segue vivo com reflexos) e testar a Mente sem abrir o Minecraft.

## Camadas de decisão

| Camada | Onde | Frequência | Exemplo |
|---|---|---|---|
| Reflexos | Corpo | Todo tick | Comer quando a fome cai |
| Habilidades | Corpo | Segundos a minutos | Cortar 10 árvores |
| Deliberação | Mente (LLM) | Por evento, 1 a 4 por minuto | Escolher o próximo objetivo |
| Reflexão | Mente (LLM) | Por dia de jogo ou ao morrer | Resumir o dia na memória longa |

## Pontos de extensão

1. **Pacote de personagem** — `characters/<id>/character.toml` + `persona.md` + skin.
2. **Habilidades** — uma classe Java por habilidade (`skill/`), registrada no `SkillRegistry`; o catálogo vai para a Mente no `hello` e vira as ações que a IA pode escolher. Na fase 3 a IA escreve a ação numa linha no fim da fala (`[ação: follow Vry]`), formato que até modelos pequenos acertam; a Mente valida contra o catálogo antes de mandar `skill.start`.
3. **Plugins da Mente** — ganchos `on_event`, `on_chat`, `needs`, `prompt`, `say` (ex.: chat da Twitch, voz por TTS).
4. **Corpos trocáveis** — qualquer programa que fale o [protocolo](../protocol/README.md): o mod, o corpo simulado, no futuro Mineflayer ou plugin Paper.

## Por que não Mineflayer

O Mineflayer, usado pela maioria dos bots de IA de Minecraft, só suporta oficialmente até a 1.21.11. Um mod com fake player funciona na 26.3, não precisa de conta extra e faz a personagem existir de verdade no servidor da LAN.

## Como ela anda (fase 3)

- **`pathing/`** — A* sobre a grade de blocos, sem dependência do Minecraft (testado com um terreno falso). Movimentos: andar e diagonal (sem cortar quina), subir 1 bloco pulando, descer até 3, nadar. Evita lava, fogo, cacto, magma, teia e afins. Se o destino não é alcançável, devolve o caminho até o ponto mais perto.
- **`control/`** — as "teclas" dela (`Controls`: frente, lado, pulo, corrida, agachar, olhar), aplicadas no começo de cada tick do player, e o `PathFollower`, que anda de nó em nó, pula degraus e detecta quando travou.
- **`skill/`** — `follow` replaneja quando o alvo anda e corre quando ele se afasta; `goto` replaneja quando trava ou o caminho era parcial e desiste com um motivo em português, que volta para a Mente em `skill.failed`.

## Como ela usa as mãos (fase 4)

- **`craft/Planner`** — dado o inventário, o que tem perto (bancada, fornalha) e um objetivo, monta a sequência: minerar, craftar, fundir. Trabalha num inventário simulado e escolhe a variante mais barata de cada ingrediente (a tábua do tronco que ela já tem). Ciclos de receita (bloco de ferro ↔ barras) não travam. É puro Java e tem testes próprios.
- **`craft/GameKnowledge`** — tira as receitas do servidor (inclusive de datapacks) e as tags de ferramenta de cada bloco. As fontes naturais são uma lista curada para ela não desmontar construções.
- **`ObtainSkill`** — executa só o primeiro passo do plano e replaneja com o inventário novo. Se ela minerou bétula em vez de carvalho, o próximo plano usa bétula.
- **`hands/`** — `Digger` quebra blocos pelo mesmo caminho de um player (o bloco racha aos poucos e solta o drop normal), `Placer` coloca blocos clicando numa face, `Crafter` monta a grade com os itens dela e confere com a receita do jogo antes de trocar os ingredientes pelo resultado.

## Vontade própria (fase 5)

- **Reflexos (Corpo, `bot/Reflexes`)** — rodam a cada tick antes da habilidade e a pausam enquanto agem: fugir de creeper e de briga com pouca vida, revidar quem está mirando nela, comer quando a fome passa de 15, subir para respirar, pular para sair da lava. A IA só fica sabendo depois (`danger`).
- **Necessidades (Mente, `brain/needs.py`)** — fome, segurança, descanso, social, progresso, conforto e curiosidade viram urgências de 0 a 1, multiplicadas pelos pesos da personagem, e entram no prompt como "o que você mais quer agora".
- **Vontade (Mente, `brain/will.py`)** — quando ela está parada: abrigo ao anoitecer (instinto, sem gastar IA), promessas pendentes, fome sem comida e tédio viram uma decisão da IA. Pedidos adiados com `[depois: ...]` viram promessas e são cumpridos quando ela fica livre.

## Memória (fase 6)

`mind/waifuscam_mind/memory.py` guarda em JSON (gravação atômica, nunca fica pela metade): lugares, pessoas (quando conheceu, quantas conversas, notas), notas soltas, diário, promessas e as últimas 30 falas. A IA escreve com tags no fim da resposta — `[lembrar: base]`, `[esquecer: base]`, `[nota: ...]` — e lê pelo prompt (lugares, quem está online e o que ela sabe da pessoa, notas e as últimas entradas do diário). O diário é escrito por ela ao amanhecer no jogo, se teve conversa desde a última entrada (1 chamada de IA por dia de jogo, no máximo a cada 10 min).

## Como ela sabe fazer as coisas (fase 9)

As receitas vêm do próprio jogo: o corpo lê todas as receitas carregadas no servidor (bancada, inventário 2x2 e fornalha, inclusive de datapacks), então ela sabe fazer tudo que tem receita, na versão exata que está rodando. De onde sai cada matéria-prima (minérios, troncos, areia, flores, lã de cada cor, drops de bicho) é uma lista curada em `GameKnowledge`.

O `Planner` monta o passo a passo num inventário simulado: começa pelo que ela já tem, usa bancada e fornalha que estiverem a até 12 blocos, faz as ferramentas que faltam e calcula combustível. A Mente pergunta esse plano (`query` `plan`) quando alguém cita um item no chat, coloca a receita e o plano no prompt, e confere de novo antes de começar: se o jogo diz que não dá, ela fala o que falta em vez de prometer.

Os nomes em português são a tradução oficial (`pt_br.json`), lida da pasta do Minecraft de quem joga, mais apelidos ("ferro" = lingote, "madeira" = qualquer tronco).

### Aventura (fase 11)

Os reflexos ganharam o arco (`hands/Archery`): com arco e flecha, ela atira em quem está vindo atrás dela ou de um player perto, e em creeper chegando, entre 5 e 24 blocos. Puxa a corda até o arco encher, mira onde o bicho vai estar (velocidade dele × tempo de voo) e compensa a queda da flecha; se um player estiver perto da linha do tiro, espera. De perto continua a espada. Seguindo alguém numa caverna escura, ela deixa tocha a cada ~8 blocos.

### Baú e horta (fase 10)

O planejador também sabe o que tem nos baús e barris a até 16 blocos (`use_chests`): pegar do baú é o passo mais barato, então ela usa o ferro guardado antes de ir minerar. Plantação madura (trigo, cenoura, batata, beterraba) é fonte de comida e de trigo; ela nunca colhe o que está verde e replanta na hora. A habilidade `farm` cuida da horta inteira e, com enxada e sementes, ara terra perto da água. Quando ela vê uma plantação, cuidar dela entra nos objetivos próprios, no máximo a cada 20 minutos.

## Como ela enxerga (fase 8)

Uma vez por segundo, `bot/Eyes` lança 117 raios num cone de ~110° × 76° para onde a cabeça dela está virada, até 48 blocos (com um tremidinho a cada olhar, para cobrir o campo inteiro com o tempo). Ela só vê o que tem linha de visão: minério atrás da pedra continua escondido, água é transparente, e no escuro ela só reconhece o que está a até 4 blocos. Bichos, players e itens no chão entram se estiverem no cone e à vista.

O que ela vê vai no `state` (`seeing`) e vira a seção "O que você está vendo agora" do prompt. Achados que valem lembrar (minérios, baús, sino de vila, spawner, obsidiana...) viram eventos `seen` e ficam na memória da Mente, por mundo. Ao conectar, a Mente devolve essas lembranças ao corpo (`memory.blocks`).

Quando precisa de um bloco que não está à vista, a coleta primeiro tenta lembrar onde viu um (até 160 blocos) e vai lá. Se não lembra e é minério, cava em escada até a altura em que ele é comum (ferro ~16, ouro ~-16, diamante e redstone ~-53, lápis ~0, cobre ~48) e abre um túnel de 2 de altura dali, virando a cada 32 blocos, desviando de água e lava e pondo tocha no escuro.

A procura de blocos para quebrar (`BlockFinder`) ainda olha um cubo em volta e aceita qualquer bloco com uma face no ar, mesmo fora do cone. É um atalho: num player, seria virar a cabeça para olhar em volta.
