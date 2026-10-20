from waifuscam_mind.brain.actions import Action, describe_actions, resolve, split_action, usage
from waifuscam_mind.sim.body import SIM_SKILLS

SKILLS = SIM_SKILLS
FOLLOW, GOTO, STOP = SKILLS


def test_usage_comes_from_params():
    assert usage(FOLLOW) == "[ação: follow <player>]"
    assert usage(GOTO) == "[ação: goto <player>] ou [ação: goto <x> <y> <z>]"
    assert usage(STOP) == "[ação: stop]"


def test_prompt_lists_every_skill_and_is_empty_without_catalog():
    text = describe_actions(SKILLS)
    assert "## Ações" in text and all(f"[ação: {s.name}" in text for s in SKILLS)
    assert describe_actions([]) == ""


def test_split_action_takes_the_last_tag_and_removes_it():
    text, name, raw = split_action("bora, tô indo\n[ação: follow Vry]")
    assert text.strip() == "bora, tô indo" and name == "follow" and raw == "Vry"
    assert split_action("[Acao: STOP]")[1] == "stop"
    assert split_action("só falando")[1] is None


def test_resolve_defaults_to_speaker_and_fixes_name_case():
    online = ["Lumi", "Vry", "Ana"]
    assert resolve("bora\n[ação: follow]", SKILLS, "Vry", online)[1] == Action("follow", {"player": "Vry"})
    assert resolve("[ação: follow me]", SKILLS, "Vry", online)[1] == Action("follow", {"player": "Vry"})
    assert resolve("[ação: follow ana]", SKILLS, "Vry", online)[1] == Action("follow", {"player": "Ana"})
    # quem não está online passa adiante: o corpo recusa e ela comenta o motivo
    assert resolve("[ação: follow Zé]", SKILLS, "Vry", online)[1] == Action("follow", {"player": "Zé"})


def test_resolve_coordinates_and_rejects_bad_ones():
    assert resolve("[ação: goto 10 64 -3.5]", SKILLS, "Vry", [])[1] == Action("goto", {"x": 10, "y": 64, "z": -3.5})
    assert resolve("[ação: goto 10, 64, -3]", SKILLS, "Vry", [])[1] == Action("goto", {"x": 10, "y": 64, "z": -3})
    assert resolve("[ação: goto ~ ~ ~5]", SKILLS, "Vry", [])[1] is None
    assert resolve("[ação: goto 10 64]", SKILLS, "Vry", [])[1] is None


def test_resolve_ignores_unknown_skills_and_missing_speaker():
    text, action = resolve("vou cavar\n[ação: mine diamond]", SKILLS, "Vry", [])
    assert action is None and text.strip() == "vou cavar"
    assert resolve("[ação: follow]", SKILLS, None, [])[1] is None
    assert resolve("[ação: stop]", SKILLS, None, [])[1] == Action("stop", {})
    assert resolve("[ação: follow Vry]", [], "Vry", [])[1] is None  # corpo antigo, sem catálogo
