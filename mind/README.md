# waifuscam-mind

A Mente do [Waifu Scam](../README.md): personalidade, necessidades e decisões da player de IA. Servidor WebSocket que conversa com o mod pelo [protocolo v1](../protocol/README.md).

```bash
python -m pip install -e ".[dev]"
python -m waifuscam_mind            # sobe a Mente
python -m waifuscam_mind.sim.body   # corpo simulado, em outro terminal
pytest
```

Configuração por variáveis de ambiente ou `.env` na raiz do repositório (veja `.env.example`).
