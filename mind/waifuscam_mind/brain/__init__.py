"""Cérebros: decidem o que a personagem faz e fala a partir dos eventos do corpo.

Cada fase troca o cérebro sem mexer no servidor:
- fase 1: EchoBrain (sem IA, prova que a ponte funciona nos dois sentidos)
- fase 2: ChatBrain, conversa com LLM e persona
- fase 3: os dois também agem (seguir, ir até, parar) pelo catálogo do corpo;
  o formato da ação está em actions.py
"""

from .base import Brain, Mouth
from .chat import ChatBrain
from .echo import EchoBrain

__all__ = ["Brain", "ChatBrain", "EchoBrain", "Mouth"]
