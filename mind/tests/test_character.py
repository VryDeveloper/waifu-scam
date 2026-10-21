import pytest

from waifuscam_mind.character import CharacterError, load_character


def test_loads_example_character(characters_dir):
    c = load_character(characters_dir, "lumi")
    assert c.name == "Lumi" and c.language == "pt-BR"
    assert "Você é a Lumi" in c.persona()
    assert "{bot_name}" not in c.persona()
    assert c.section("world")["fair_play"] is False


def test_private_folder_wins(tmp_path):
    for where, name in ((tmp_path / "ana", "Pública"), (tmp_path / "private" / "ana", "Privada")):
        where.mkdir(parents=True)
        (where / "character.toml").write_text(f'name = "{name}"', encoding="utf-8")
        (where / "persona.md").write_text("Você é a {bot_name}.", encoding="utf-8")
    assert load_character(tmp_path, "ana").name == "Privada"


@pytest.mark.parametrize("bad_id", ["../etc", "Lumi", "a b", ""])
def test_rejects_unsafe_ids(characters_dir, bad_id):
    with pytest.raises(CharacterError):
        load_character(characters_dir, bad_id)


def test_missing_name_is_clear_error(tmp_path):
    (tmp_path / "x").mkdir()
    (tmp_path / "x" / "character.toml").write_text('language = "pt-BR"', encoding="utf-8")
    with pytest.raises(CharacterError, match="name"):
        load_character(tmp_path, "x")


def test_username_defaults_from_name(tmp_path):
    (tmp_path / "x").mkdir()
    (tmp_path / "x" / "character.toml").write_text('name = "Lumi Chan!"', encoding="utf-8")
    (tmp_path / "x" / "persona.md").write_text("oi", encoding="utf-8")
    assert load_character(tmp_path, "x").username == "Lumi_Chan"


def test_invalid_username_is_clear_error(tmp_path):
    (tmp_path / "x").mkdir()
    (tmp_path / "x" / "character.toml").write_text(
        'name = "A"\n[minecraft]\nusername = "nome com espaço"', encoding="utf-8"
    )
    (tmp_path / "x" / "persona.md").write_text("oi", encoding="utf-8")
    with pytest.raises(CharacterError, match="username"):
        load_character(tmp_path, "x")


def test_persona_names_who_she_loves(characters_dir):
    from dataclasses import replace

    lumi = load_character(characters_dir, "lumi")
    text = lumi.persona()
    assert "{beloved}" not in text and "{bot_name}" not in text
    assert f"companheira de {lumi.beloved or 'quem joga com você'}" in text
    nobody = replace(lumi, config={**lumi.config, "chat": {}})
    assert "companheira de quem joga com você" in nobody.persona()
