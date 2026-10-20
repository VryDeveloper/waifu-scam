from waifuscam_mind.brain.actions import Action, describe_actions, resolve, split_action, usage
from waifuscam_mind.sim.body import SIM_SKILLS

SKILLS = SIM_SKILLS
BY_NAME = {s.name: s for s in SKILLS}
FOLLOW, GOTO, STOP, OBTAIN, GIVE = (BY_NAME[n] for n in ("follow", "goto", "stop", "obtain", "give"))


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


# ---- fase 4: itens e quantidades ------------------------------------------------


def test_usage_lists_params_in_order():
    assert usage(OBTAIN) == "[ação: obtain <item> <count>]"
    assert usage(GIVE) == "[ação: give <player> <item> <count>]"


def test_item_and_count():
    online = ["Lumi", "Vry"]
    assert resolve("[ação: obtain stone_pickaxe 1]", SKILLS, "Vry", online)[1] == Action(
        "obtain", {"item": "stone_pickaxe", "count": 1}
    )
    assert resolve("[ação: obtain oak_log]", SKILLS, "Vry", online)[1] == Action("obtain", {"item": "oak_log"})
    # nome em várias palavras vira um id
    assert resolve("[ação: obtain stone pickaxe 2]", SKILLS, "Vry", online)[1] == Action(
        "obtain", {"item": "stone_pickaxe", "count": 2}
    )
    assert resolve("[ação: obtain 3]", SKILLS, "Vry", online)[1] is None  # sem item


def test_give_defaults_to_speaker_but_accepts_online_name():
    online = ["Lumi", "Vry", "Ana"]
    assert resolve("[ação: give torch 4]", SKILLS, "Vry", online)[1] == Action(
        "give", {"player": "Vry", "item": "torch", "count": 4}
    )
    assert resolve("[ação: give ana bread]", SKILLS, "Vry", online)[1] == Action(
        "give", {"player": "Ana", "item": "bread"}
    )
    assert resolve("[ação: give me bread 2]", SKILLS, "Vry", online)[1] == Action(
        "give", {"player": "Vry", "item": "bread", "count": 2}
    )
