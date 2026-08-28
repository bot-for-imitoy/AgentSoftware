"""JSON 配置存储.

配置以 JSON 对象保存, 支持通过点号路径访问多级键, 例如
``store.set("llm.model", "deepseek-chat")``.
"""
from __future__ import annotations

import json
from pathlib import Path
from typing import Any, Mapping, Optional

from src.core.path_manager import PathManager


class ConfigStore:
    """基于 JSON 文件的简单多级 KV 配置存储."""

    def __init__(self, path: str | Path | None = None,
                 *, path_manager: Optional[PathManager] = None,
                 filename: str = "config.json") -> None:
        if path is not None and path_manager is not None:
            raise ValueError("path 与 path_manager 只能指定一个")
        self._path_manager = path_manager or PathManager()
        self._path = Path(path) if path is not None else self._path_manager.config_file(filename)
        self._data: dict[str, Any] = {}
        self.load()

    @property
    def path(self) -> Path:
        """配置文件路径."""
        return self._path

    @property
    def data(self) -> dict[str, Any]:
        """当前配置的副本, 修改后请通过 set/update 保存."""
        return _copy_json_value(self._data)

    def exists(self) -> bool:
        """配置文件是否存在."""
        return self._path.exists()

    def load(self) -> dict[str, Any]:
        """从文件加载配置; 文件不存在时使用空配置."""
        if not self._path.exists():
            self._data = {}
            return self.data
        try:
            loaded = json.loads(self._path.read_text(encoding="utf-8"))
        except json.JSONDecodeError as exc:
            raise ValueError(f"配置文件不是有效 JSON: {self._path}") from exc
        except OSError as exc:
            raise OSError(f"读取配置文件失败: {self._path}") from exc
        if not isinstance(loaded, dict):
            raise ValueError(f"配置文件根节点必须是 JSON 对象: {self._path}")
        self._data = loaded
        return self.data

    def save(self) -> Path:
        """将当前配置原子写入文件."""
        self._path.parent.mkdir(parents=True, exist_ok=True)
        temporary_path = self._path.with_name(f".{self._path.name}.tmp")
        temporary_path.write_text(
            json.dumps(self._data, ensure_ascii=False, indent=2), encoding="utf-8")
        temporary_path.replace(self._path)
        return self._path

    def get(self, key: str, default: Any = None) -> Any:
        """读取点号路径对应的值, 不存在时返回 default."""
        current: Any = self._data
        for part in _parts(key):
            if not isinstance(current, dict) or part not in current:
                return default
            current = current[part]
        return _copy_json_value(current)

    def set(self, key: str, value: Any) -> Any:
        """创建或覆盖一个键, 并立即保存."""
        parts = _parts(key)
        _ensure_json_value(value)
        current = self._data
        for part in parts[:-1]:
            child = current.get(part)
            if child is None:
                child = {}
                current[part] = child
            elif not isinstance(child, dict):
                raise TypeError(f"键路径中间节点不是对象: {key}")
            current = child
        current[parts[-1]] = _copy_json_value(value)
        self.save()
        return _copy_json_value(value)

    def update(self, values: Mapping[str, Any]) -> dict[str, Any]:
        """批量创建或覆盖键; values 的键同样支持点号路径."""
        for key, value in values.items():
            parts = _parts(key)
            _ensure_json_value(value)
            current = self._data
            for part in parts[:-1]:
                child = current.get(part)
                if child is None:
                    child = {}
                    current[part] = child
                elif not isinstance(child, dict):
                    raise TypeError(f"键路径中间节点不是对象: {key}")
                current = child
            current[parts[-1]] = _copy_json_value(value)
        self.save()
        return self.data

    def delete(self, key: str) -> bool:
        """删除键; 键存在且删除成功时返回 True."""
        parts = _parts(key)
        current: Any = self._data
        for part in parts[:-1]:
            if not isinstance(current, dict) or part not in current:
                return False
            current = current[part]
        if not isinstance(current, dict) or parts[-1] not in current:
            return False
        del current[parts[-1]]
        self.save()
        return True


def _parts(key: str) -> list[str]:
    if not isinstance(key, str) or not key or any(not part for part in key.split(".")):
        raise ValueError("键必须是非空字符串, 多级键使用点号分隔")
    return key.split(".")


def _ensure_json_value(value: Any) -> None:
    try:
        json.dumps(value, ensure_ascii=False)
    except (TypeError, ValueError) as exc:
        raise TypeError("配置值必须可序列化为 JSON") from exc


def _copy_json_value(value: Any) -> Any:
    return json.loads(json.dumps(value, ensure_ascii=False))