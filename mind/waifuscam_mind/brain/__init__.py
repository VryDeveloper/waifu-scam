"""Cérebros: decidem o que a personagem faz e fala a partir dos eventos do corpo.

Cada fase troca o cérebro sem mexer no servidor:
- fase 1: EchoBrain (sem IA, prova que a ponte funciona nos dois sentidos)
- fase 2: cérebro com LLM e persona
"""

from .base import Brain, Mouth
from .echo import EchoBrain

__all__ = ["Brain", "EchoBrain", "Mouth"]
