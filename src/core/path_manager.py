"""PathManager — 跨平台应用路径管理 (配置文件/数据/缓存/日志).

按各平台目录约定返回应用专属目录, 作为全项目路径的唯一入口
(替代散落各处的硬编码相对路径, 如 "./data/state.json").

平台约定:

| 目录  | Linux                                        | macOS                                   | Windows                            |
|-------|----------------------------------------------|-----------------------------------------|------------------------------------|
| 配置  | $XDG_CONFIG_HOME/<app>  (~/.config/<app>)    | ~/Library/Application Support/<app>     | %APPDATA%/<app>  (Roaming)         |
| 数据  | $XDG_DATA_HOME/<app>    (~/.local/share)     | ~/Library/Application Support/<app>     | %LOCALAPPDATA%/<app>               |
| 缓存  | $XDG_CACHE_HOME/<app>   (~/.cache/<app>)     | ~/Library/Caches/<app>                  | %LOCALAPPDATA%/<app>/Cache         |
| 日志  | $XDG_STATE_HOME/<app>   (~/.local/state)     | ~/Library/Logs/<app>                    | %LOCALAPPDATA%/<app>/Logs          |

环境变量显式覆盖 (优先级最高, 便携模式/测试/容器常用):
    <ENV_PREFIX>_CONFIG_DIR / _DATA_DIR / _CACHE_DIR / _LOG_DIR
默认 ENV_PREFIX = app_name 大写化 (下划线), 如 "maf_scheduler" → MAF_SCHEDULER.

接口 (全部返回 pathlib.Path):
    PathManager(app_name, *, env_prefix=None, env=None, platform=None)
        config_dir() -> Path        配置文件目录
        data_dir()   -> Path        数据文件目录
        cache_dir()  -> Path        缓存目录
        log_dir()    -> Path        日志目录
        config_file(*parts) -> Path  config_dir() / parts
        data_file(*parts)   -> Path  data_dir() / parts
        cache_file(*parts)  -> Path  cache_dir() / parts
        log_file(*parts)    -> Path  log_dir() / parts
        ensure_dirs()       -> None  创建四个目录 (parents=True, exist_ok=True)

用法:
    from src.core.path_manager import PathManager
    pm = PathManager("AgentScheduler")
    cfg = pm.config_file("mcp_group_rules.json")   # 平台合适的配置文件路径
    pm.ensure_dirs()                               # 需要写文件前建目录

设计注记:
    - env / platform 参数仅用于注入 (测试与容器场景), 生产代码不必传:
      env=None 时读 os.environ, platform=None 时读 sys.platform.
    - 环境变量覆盖优先于平台约定: 想让配置落在项目本地,
      设 AGENT_SCHEDULER_CONFIG_DIR=/path/to/project/config 即可.
"""
from __future__ import annotations

import os
import sys
from pathlib import Path
from typing import Mapping, Optional


def _is_windows(platform: str) -> bool:
    return platform.startswith("win")


def _is_macos(platform: str) -> bool:
    return platform == "darwin"


class PathManager:
    """跨平台应用路径管理器 (配置/数据/缓存/日志)."""

    def __init__(
        self,
        app_name: str = "AgentScheduler",
        *,
        env_prefix: Optional[str] = None,
        env: Optional[Mapping[str, str]] = None,
        platform: Optional[str] = None,
    ) -> None:
        """
        参数:
            app_name:   应用名 (作为平台目录下的子目录, 如 "AgentScheduler").
            env_prefix: 环境变量覆盖前缀; 默认 app_name 大写化
                        ("AgentScheduler" → "AGENT_SCHEDULER").
            env:        环境变量快照 (测试注入用); None = os.environ.
            platform:   平台名 (测试注入用); None = sys.platform.
                        "win32"/"darwin"/"linux" 等.
        """
        self._app_name = app_name
        self._env: Mapping[str, str] = env if env is not None else os.environ
        self._platform = platform if platform is not None else sys.platform
        self._env_prefix = (env_prefix or app_name).strip().upper().replace("-", "_")

    # ── 平台探测 ──────────────────────────────────────────────

    @property
    def platform(self) -> str:
        """当前生效的平台名 (win32 / darwin / linux 等)."""
        return self._platform

    @property
    def app_name(self) -> str:
        return self._app_name

    # ── 基础目录 (按平台约定 + 环境变量覆盖) ─────────────────

    def config_dir(self) -> Path:
        """配置文件目录 (用户可编辑的配置)."""
        override = self._env.get(f"{self._env_prefix}_CONFIG_DIR")
        if override:
            return Path(override)
        if _is_windows(self._platform):
            return self._appdata("Roaming")
        if _is_macos(self._platform):
            return self._home() / "Library" / "Application Support" / self._app_name
        # Linux / 其它 Unix: XDG
        xdg = self._env.get("XDG_CONFIG_HOME")
        base = Path(xdg) if xdg else self._home() / ".config"
        return base / self._app_name

    def data_dir(self) -> Path:
        """数据文件目录 (应用产生的可持久化数据)."""
        override = self._env.get(f"{self._env_prefix}_DATA_DIR")
        if override:
            return Path(override)
        if _is_windows(self._platform):
            return self._appdata("Local")
        if _is_macos(self._platform):
            return self._home() / "Library" / "Application Support" / self._app_name
        xdg = self._env.get("XDG_DATA_HOME")
        base = Path(xdg) if xdg else self._home() / ".local" / "share"
        return base / self._app_name

    def cache_dir(self) -> Path:
        """缓存目录 (可随时清除的临时数据)."""
        override = self._env.get(f"{self._env_prefix}_CACHE_DIR")
        if override:
            return Path(override)
        if _is_windows(self._platform):
            return self.data_dir() / "Cache"
        if _is_macos(self._platform):
            return self._home() / "Library" / "Caches" / self._app_name
        xdg = self._env.get("XDG_CACHE_HOME")
        base = Path(xdg) if xdg else self._home() / ".cache"
        return base / self._app_name

    def log_dir(self) -> Path:
        """日志目录 (运行日志)."""
        override = self._env.get(f"{self._env_prefix}_LOG_DIR")
        if override:
            return Path(override)
        if _is_windows(self._platform):
            return self.data_dir() / "Logs"
        if _is_macos(self._platform):
            return self._home() / "Library" / "Logs" / self._app_name
        # Linux: XDG_STATE_HOME (无标准 log 目录, 惯例放 state 下)
        xdg = self._env.get("XDG_STATE_HOME")
        base = Path(xdg) if xdg else self._home() / ".local" / "state"
        return base / self._app_name

    # ── 具体文件路径 ──────────────────────────────────────────

    def config_file(self, *parts: str) -> Path:
        """config_dir() 下的具体文件/子路径."""
        return self.config_dir().joinpath(*parts)

    def data_file(self, *parts: str) -> Path:
        return self.data_dir().joinpath(*parts)

    def cache_file(self, *parts: str) -> Path:
        return self.cache_dir().joinpath(*parts)

    def log_file(self, *parts: str) -> Path:
        return self.log_dir().joinpath(*parts)

    # ── 目录创建 ──────────────────────────────────────────────

    def ensure_dirs(self) -> None:
        """创建全部四个目录 (parents=True, exist_ok=True), 幂等."""
        for d in (self.config_dir(), self.data_dir(), self.cache_dir(), self.log_dir()):
            d.mkdir(parents=True, exist_ok=True)

    def ensure(self, path: Path) -> Path:
        """创建 path 的父目录, 返回原 path (写文件前调用)."""
        path.parent.mkdir(parents=True, exist_ok=True)
        return path

    # ── 内部辅助 ──────────────────────────────────────────────

    def _home(self) -> Path:
        home = self._env.get("USERPROFILE") if _is_windows(self._platform) else self._env.get("HOME")
        if home:
            return Path(home)
        return Path.home()

    def _appdata(self, kind: str) -> Path:
        """Windows AppData: kind = "Roaming" (APPDATA) / "Local" (LOCALAPPDATA)."""
        key = "APPDATA" if kind == "Roaming" else "LOCALAPPDATA"
        base = self._env.get(key)
        if base:
            return Path(base) / self._app_name
        # 环境变量缺失时按惯例回退 (Windows 上 APPDATA 一般必存在)
        return self._home() / "AppData" / kind / self._app_name
