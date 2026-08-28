"""ConfigStore JSON 多级 KV 存储测试."""

from pathlib import Path

import pytest

from src.core.config_store import ConfigStore
from src.core.path_manager import PathManager


def test_nested_crud_and_persistence(tmp_path: Path):
    path_manager = PathManager("app", platform="linux", env={"HOME": str(tmp_path)})
    store = ConfigStore(path_manager=path_manager)

    assert store.get("llm.model") is None
    assert store.set("llm.model", "deepseek-chat") == "deepseek-chat"
    store.update({"llm.temperature": 0.2, "ui.language": "zh-CN"})
    assert store.get("llm") == {"model": "deepseek-chat", "temperature": 0.2}
    assert store.delete("llm.temperature") is True
    assert store.delete("llm.missing") is False

    reloaded = ConfigStore(path_manager=path_manager)
    assert reloaded.data == {
        "llm": {"model": "deepseek-chat"},
        "ui": {"language": "zh-CN"},
    }


def test_explicit_path_and_validation(tmp_path: Path):
    path = tmp_path / "nested" / "config.json"
    store = ConfigStore(path)
    store.set("feature.enabled", True)
    assert path.is_file()

    with pytest.raises(ValueError):
        store.set("feature..name", "invalid")
    with pytest.raises(TypeError):
        store.set("feature.enabled.value", 1)


def test_invalid_root_json_rejected(tmp_path: Path):
    path = tmp_path / "config.json"
    path.write_text("[]", encoding="utf-8")
    with pytest.raises(ValueError, match="根节点"):
        ConfigStore(path)