"""Plugin de exemplo: ela sabe que horas são no mundo real.

Útil para a persona de VTuber ("já é meia-noite, amanhã tem live"). Ative em
character.toml:

    [plugins]
    enabled = ["relogio"]
"""

from datetime import datetime

from waifuscam_mind.plugins import Plugin


class Relogio(Plugin):
    name = "relogio"

    def prompt(self, state):
        now = datetime.now()
        periodo = "madrugada" if now.hour < 6 else "manhã" if now.hour < 12 else "tarde" if now.hour < 18 else "noite"
        return f"## Mundo real\n- Agora são {now:%H:%M} ({periodo}) na vida real."
