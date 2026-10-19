from pathlib import Path

import pytest

REPO_ROOT = Path(__file__).resolve().parents[2]


@pytest.fixture
def characters_dir() -> Path:
    return REPO_ROOT / "characters"
