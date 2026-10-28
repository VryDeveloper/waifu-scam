# Voz (plano)

Conversar com ela por voz pelo [Simple Voice Chat](https://modrinth.com/plugin/simple-voice-chat) (SVC): você fala no microfone, ela entende e responde em voz alta, saindo da posição dela no mundo (som 3D, como um player).

> Status: arquitetura. Nada disso está implementado ainda.

## Ideia central

**O mod só transporta áudio; a Mente faz a IA.** É a mesma divisão do resto do projeto (o LLM decide o quê; o mod decide como).

```text
 você fala                                                         ela fala
    │                                                                 ▲
    ▼ Opus (SVC)                                                      │ Opus (SVC, canal da entidade dela)
┌────────────────────────── Corpo (mod) ─────────────────────────────────────┐
│ Ears: MicrophonePacketEvent → decodifica → junta a frase                  │
│       → fim da fala = 300 ms sem pacote                                   │
│ Voice: recebe PCM → codifica Opus → manda a cada 20 ms → para se você     │
│        começa a falar por cima                                            │
└────────┬──────────────────────────────────────────────────────▲───────────┘
         │ voice.heard (PCM 16 kHz)                             │ voice.chunk (PCM 48 kHz)
┌────────▼────────────────────────── Mente ─────────────────────┴───────────┐
│ STT (Whisper) → vira um "chat" normal → ChatBrain (persona, memória,      │
│ ações) → LLM em streaming → corta por frase → TTS da frase 1 enquanto     │
│ o LLM escreve a 2 → legenda no chat                                       │
└───────────────────────────────────────────────────────────────────────────┘
```

A fala transcrita entra no `ChatBrain` como um evento `chat` comum (com `via: "voice"`). Persona, memória, `[ação: ...]`, promessas e o resto não mudam: só a saída troca de "digitar no chat" para "falar".

## Por que é rápido

| Etapa | Como | Tempo |
|---|---|---|
| Saber que você parou de falar | O SVC só manda pacote enquanto você fala (push-to-talk ou ativação por voz). Fim da fala = 300 ms sem pacote. Não precisa de VAD | ~300 ms |
| Transcrever | `faster-whisper` local (ou Groq de reserva), com o nome dela e dos players no `initial_prompt` para não errar "Lumi" | ~200–500 ms |
| Pensar | LLM em **streaming**; a primeira frase sai antes de a resposta terminar | ~300–600 ms até a 1ª frase |
| Falar | TTS só da primeira frase; a 2ª é sintetizada enquanto a 1ª toca | ~150–500 ms |
| **Total até ouvir a voz dela** | | **~1,2–1,8 s** |

Truques que somam:

- **Pipeline por frase**: `LLM stream → divisor de frases → fila de TTS → fila de áudio`. As três etapas rodam ao mesmo tempo.
- **Sem a "digitação"**: `typing_speed` não vale para a voz.
- **As tags ficam de fora da fala**: o `[ação: ...]` vem no fim da resposta, então o divisor para de mandar texto para o TTS no primeiro `[` e o resto continua indo para o parser de sempre.
- **Interromper** (barge-in): se você começa a falar enquanto ela fala, o corpo esvazia a fila na hora, como numa conversa de verdade.
- **Opcional**: um "hmm" ou "uhum" curto, já gravado no início, toca quando a resposta passa de 1,5 s.

## Corpo (mod)

O SVC é **opcional**. O plugin entra pelo entrypoint `voicechat` do `fabric.mod.json`, que o SVC só carrega se estiver instalado. Sem SVC, nenhuma classe dele é carregada e o mod funciona igual.

```gradle
repositories { maven { url = 'https://maven.maxhenkel.de/repository/public' } }
dependencies { compileOnly "de.maxhenkel.voicechat:voicechat-api:${voicechat_api_version}" }
```

```json
"entrypoints": { "voicechat": ["dev.vry.waifuscam.voice.WaifuVoicePlugin"] },
"suggests": { "voicechat": "*" }
```

Pacote novo `voice/`:

| Classe | Faz |
|---|---|
| `WaifuVoicePlugin` | `VoicechatPlugin`: guarda o `VoicechatServerApi` no `VoicechatServerStartedEvent`, escuta `MicrophonePacketEvent`, registra a categoria de volume "Lumi" (cada player ajusta o volume dela no menu do SVC) |
| `Ears` | Um `OpusDecoder` por player. Ignora quem está fora do alcance de voz dela e pacotes sussurrados de longe. Junta o PCM de 48 kHz, reduz para 16 kHz (média de 3 amostras) e, depois de 300 ms sem pacote (ou 30 s de fala), manda `voice.heard`. Também avisa "fulano começou a falar" para o `Voice` parar |
| `Voice` | `EntityAudioChannel` na entidade dela (`api.fromEntity(bot)`; `updateEntity` quando ela renasce), `OpusEncoder`, fila de quadros de 20 ms (960 amostras) e uma thread que manda um quadro a cada 20 ms. `stop()` esvazia a fila |
| `VoiceBridge` | Interface que o resto do mod usa, com uma versão vazia quando não tem SVC. Assim só o pacote `voice/` importa classes do SVC |

O `MindClient` passa a mandar e receber **frames binários** do WebSocket (`sendBinary` / `onBinary`), além do texto.

## Protocolo (fase 18)

Mensagens de áudio vão num frame binário: `[u16 tamanho do cabeçalho][cabeçalho JSON UTF-8][PCM s16le mono]`. O cabeçalho tem os mesmos `v`, `bot` e `type` das mensagens de texto.

| Tipo | Direção | Para quê |
|---|---|---|
| `hello` + `voice` | Corpo → Mente | `{"available": true, "distance": 48}` quando o SVC está carregado |
| `voice.heard` (binário) | Corpo → Mente | `from`, `uuid`, `sample_rate` (16000), `ms`, `distance` + PCM da frase |
| `voice.say` | Mente → Corpo | `id`, `sample_rate` (48000): começa uma fala |
| `voice.chunk` (binário) | Mente → Corpo | `id` + um pedaço de PCM (o corpo vai tocando conforme chega) |
| `voice.end` | Mente → Corpo | `id`: não vem mais pedaço dessa fala |
| `voice.stop` | Mente → Corpo | Cala na hora (ex.: `stop`, ela morreu) |
| `voice.played` | Corpo → Mente | `id`, `interrupted_by` (ou `null`): terminou de tocar |

O PCM chega pronto em 48 kHz, para o Java só codificar. Opus no Python exigiria a libopus, que é chata de instalar no Windows; o SVC já traz a dele.

## Mente

Pacote novo `waifuscam_mind/voice/`:

| Arquivo | Faz |
|---|---|
| `stt.py` | `LocalWhisper` (`faster-whisper`, padrão) e `GroqWhisper` (reserva). Modelos carregados uma vez, na thread de fundo. Mesmo esquema de fallback dos provedores de LLM |
| `tts.py` | Provedores que devolvem PCM em pedaços (veja abaixo), com `tts_order` e fallback |
| `speaker.py` | Consome o stream do LLM, corta por frase (`.`, `!`, `?`, `…`, ou vírgula depois de ~80 caracteres), para no primeiro `[`, sintetiza e manda `voice.chunk`. Cancelável |
| `audio.py` | Reamostragem para 48 kHz, cabeçalho do frame binário, WAV para os testes |

Mudanças no que já existe:

- `llm/client.py`: `stream()` além de `chat()`, com o mesmo fallback. Só troca de provedor antes do primeiro token.
- `brain/chat.py`: `_speak` ganha o caminho de voz. Quando a mensagem veio por voz (ou `[voice] spontaneous = true` e tem alguém perto com SVC), usa `stream()` + `Speaker`, e a frase também vai para o chat como legenda.
- `server.py`: aceita frames binários e os entrega a `voice.heard`.
- `sim/body.py`: `--voice arquivo.wav` manda um `voice.heard` e grava a resposta em WAV, para testar sem o jogo.

### Tudo no seu computador (padrão)

Nada de cota nem limite de token: transcrição, IA e voz rodam localmente. Os modelos são baixados uma vez (Hugging Face / Ollama) e depois funcionam sem internet. As APIs de nuvem ficam como reserva opcional, nas mesmas listas de ordem.

| Parte | Local | Na GPU | Observação |
|---|---|---|---|
| Ouvir (STT) | `faster-whisper` `large-v3-turbo` int8 | ~1 GB, ~0,2–0,4 s por frase | Sem GPU sobrando: `small` int8 na CPU, ~1 s |
| Pensar (LLM) | Ollama (já suportado: `custom` no `provider_order`), ex.: Qwen3 8B ou Gemma 3 12B em Q4 | ~5–8 GB | Sem limite diário. Acerta menos as tags `[ação: ...]` que os modelos grandes da nuvem; medir |
| Falar (TTS) | Veja abaixo | ~1–3 GB | |

Vozes locais em pt-BR:

| Modelo | Natural | Velocidade | Observação |
|---|---|---|---|
| **Chatterbox Multilingual** | Muito bom | Rápido na GPU | MIT, tem português, clona voz de ~10 s de áudio e tem controle de "emoção" — combina com VTuber. **Primeira a testar** |
| **XTTS-v2** | Muito bom | Streaming ~200 ms na GPU | Clona voz; licença só para uso não comercial (ok para uso pessoal) |
| **F5-TTS** (versão treinada em pt-BR) | Muito bom | Por frase, sem streaming | Clona voz |
| **Kokoro** | Médio em pt-BR | Muito rápido, roda até na CPU | Reserva leve |
| **Piper** (`pt_BR`) | Robótico | Instantâneo na CPU | Último recurso |

A voz própria da Lumi sai da clonagem: um áudio de referência de 10–30 s em `characters/<id>/voice.wav` (uma voz que você tenha direito de usar, ou uma gerada).

Orçamento de memória de vídeo (Minecraft ~1–2 GB + LLM 8B ~5,5 GB + Whisper ~1 GB + TTS ~2–3 GB):

| GPU | Dá para |
|---|---|
| 12 GB ou mais | Tudo local na GPU |
| 8 GB | LLM 4B, Whisper `small` na CPU, Kokoro; ou tudo local menos o LLM |
| Sem GPU boa | Whisper `small` e Piper/Kokoro na CPU; LLM continua na nuvem |

Se a GPU não aguentar tudo, o meio-termo é **voz e ouvido locais** (são os que seriam chamados a cada frase) e **LLM na nuvem** com o Ollama de reserva: cada fala por voz gasta a mesma chamada de LLM que uma mensagem no chat.

### Reserva na nuvem (opcional)

| Provedor | Observação |
|---|---|
| Groq Whisper | ~2.000 pedidos/dia grátis, mesma chave do LLM |
| Edge TTS (`pt-BR-FranciscaNeural`) | Grátis, sem chave, não oficial |
| Gemini Flash TTS | Expressivo, cota grátis, mesma `GEMINI_API_KEY` |
| ElevenLabs Flash | Mais rápida, grátis bem pequeno |

Os pacotes locais ficam num extra, para quem não quer voz não baixar nada: `pip install -e ".[voice]"`.

### Por que não speech-to-speech (Gemini Live etc.)

Seria o mais rápido, mas a fala não passaria mais pelo `ChatBrain`: persona montada com a situação, memória, `[ação: ...]` conferidas com o catálogo, orçamento e fallback entre provedores. Daria para usar depois só como "voz", ainda decidindo pelo texto.

## Configuração

`character.toml`:

```toml
[voice]
enabled = true
stt_order = ["local"]               # + "groq" para reserva na nuvem
stt_model = "large-v3-turbo"        # "small" para rodar na CPU
tts_order = ["chatterbox", "kokoro"] # + "edge", "gemini", "elevenlabs"
reference = "voice.wav"             # voz dela para clonar (nesta pasta)
device = "cuda"                     # "cpu" sem GPU
style = "doce, animada, um pouco tímida"   # vai para o TTS que aceita instrução
end_of_speech_ms = 300     # silêncio que fecha a sua frase
subtitles = true           # a fala também aparece no chat
spontaneous = true         # falas espontâneas também em voz quando tem alguém perto
filler = false             # "hmm" enquanto pensa
```

`.env`: `ELEVENLABS_API_KEY=` (opcional). Groq e Gemini usam as chaves que já existem.

## Ordem de implementação

1. **Cano de áudio**: plugin do SVC, `Ears` e `Voice`, frames binários. A Mente devolve o mesmo áudio (eco de voz). É a parte mais arriscada; o resto é Python.
2. **Ouvir**: STT → evento `chat` com `via: "voice"`. Ela responde por texto ao que você falou.
3. **Falar**: TTS da resposta inteira, sem streaming.
4. **Rápido**: `stream()` no LLM, pipeline por frase, interromper, legenda.
5. **Expressiva**: emoção da fala ligada aos emotes, "hmm" enquanto pensa, voz própria dela.

Cada passo dá para testar no jogo sozinho.

## Riscos

- **Versão do SVC**: até agora só achei builds de snapshot para a 26.3. Conferir no Modrinth a versão estável e a `voicechat-api` correspondente antes de começar.
- **LAN**: o SVC abre uma porta UDP (24454) no host. Os amigos precisam conseguir chegar nela, como no SVC normal.
- **GPU dividida**: Minecraft, LLM, Whisper e TTS disputam a mesma placa. Medir FPS e latência juntos; o `[voice] device` e os modelos menores ajustam. Fala mais curta que 0,4 s é descartada antes de transcrever.
- **Eco**: quem joga sem fone pode fazer o microfone pegar a voz dela. O corpo ignora o microfone desse player enquanto ela fala (configurável).
