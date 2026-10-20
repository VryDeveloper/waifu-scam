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
