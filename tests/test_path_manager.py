"""PathManager 跨平台路径管理单元测试.

覆盖:
  - Linux 默认 (无 XDG) → ~/.config / ~/.local/share / ~/.cache / ~/.local/state
  - Linux XDG 环境变量覆盖
  - macOS → ~/Library/Application Support|Caches|Logs
  - Windows → %APPDATA% (Roaming) / %LOCALAPPDATA% (Local)
  - env 注入的平台目录覆盖 (MAF_SCHEDULER_CONFIG_DIR 等) 优先于平台约定
  - config_file/data_file 拼接
  - ensure_dirs 创建目录 (tmp_path, 不碰真实 HOME)

运行: cd 项目根 && .venv/bin/python -m pytest tests/test_path_manager.py -v
"""

from __future__ import annotations

from pathlib import Path, PureWindowsPath

import pytest

from src.core.path_manager import PathManager

# 三个平台默认路径的期望值 (home 全部注入, 不依赖真实 HOME)
LINUX_HOME = "/home/testuser"
MAC_HOME = "/Users/testuser"
WIN_HOME = r"C:\Users\testuser"


def win(*parts: str) -> str:
    """在 POSIX 宿主机上构造 Windows 路径的字符串形态 (仅测试断言用)."""
    p = PureWindowsPath(parts[0])
    for part in parts[1:]:
        p = p / part
    return str(p)


def win_eq(actual: Path, *parts: str) -> bool:
    """POSIX 宿主机上比较 Windows 路径: 规整为 PureWindowsPath 再比."""
    return PureWindowsPath(str(actual)) == PureWindowsPath(win(*parts))


def make_pm(platform: str, env: dict[str, str] | None = None, **kw) -> PathManager:
    """构造注入式 PathManager (env/platform 均为测试注入)."""
    return PathManager("maf_scheduler", platform=platform, env=env, **kw)


# ── Linux ──────────────────────────────────────────────────────

def test_linux_default_dirs():
    pm = make_pm("linux", {"HOME": LINUX_HOME})
    assert pm.config_dir() == Path(f"{LINUX_HOME}/.config/maf_scheduler")
    assert pm.data_dir() == Path(f"{LINUX_HOME}/.local/share/maf_scheduler")
    assert pm.cache_dir() == Path(f"{LINUX_HOME}/.cache/maf_scheduler")
    assert pm.log_dir() == Path(f"{LINUX_HOME}/.local/state/maf_scheduler")


def test_linux_xdg_overrides():
    env = {
        "HOME": LINUX_HOME,
        "XDG_CONFIG_HOME": "/etc/xdg",
        "XDG_DATA_HOME": "/var/lib/xdg",
        "XDG_CACHE_HOME": "/var/cache/xdg",
        "XDG_STATE_HOME": "/var/state/xdg",
    }
    pm = make_pm("linux", env)
    assert pm.config_dir() == Path("/etc/xdg/maf_scheduler")
    assert pm.data_dir() == Path("/var/lib/xdg/maf_scheduler")
    assert pm.cache_dir() == Path("/var/cache/xdg/maf_scheduler")
    assert pm.log_dir() == Path("/var/state/xdg/maf_scheduler")


# ── macOS ──────────────────────────────────────────────────────

def test_macos_dirs():
    pm = make_pm("darwin", {"HOME": MAC_HOME})
    assert pm.config_dir() == Path(f"{MAC_HOME}/Library/Application Support/maf_scheduler")
    assert pm.data_dir() == Path(f"{MAC_HOME}/Library/Application Support/maf_scheduler")
    assert pm.cache_dir() == Path(f"{MAC_HOME}/Library/Caches/maf_scheduler")
    assert pm.log_dir() == Path(f"{MAC_HOME}/Library/Logs/maf_scheduler")


# ── Windows ────────────────────────────────────────────────────

def test_windows_dirs():
    env = {
        "USERPROFILE": WIN_HOME,
        "APPDATA": win(WIN_HOME, "AppData", "Roaming"),
        "LOCALAPPDATA": win(WIN_HOME, "AppData", "Local"),
    }
    pm = make_pm("win32", env)
    assert win_eq(pm.config_dir(), WIN_HOME, "AppData", "Roaming", "maf_scheduler")
    assert win_eq(pm.data_dir(), WIN_HOME, "AppData", "Local", "maf_scheduler")
    assert win_eq(pm.cache_dir(), WIN_HOME, "AppData", "Local", "maf_scheduler", "Cache")
    assert win_eq(pm.log_dir(), WIN_HOME, "AppData", "Local", "maf_scheduler", "Logs")


def test_windows_missing_appdata_falls_back_to_home():
    # APPDATA/LOCALAPPDATA 缺失时按惯例回退到 USERPROFILE 下
    pm = make_pm("win32", {"USERPROFILE": WIN_HOME})
    assert win_eq(pm.config_dir(), WIN_HOME, "AppData", "Roaming", "maf_scheduler")
    assert win_eq(pm.data_dir(), WIN_HOME, "AppData", "Local", "maf_scheduler")


# ── 环境变量显式覆盖 (优先级最高) ─────────────────────────────

def test_env_prefix_override_wins_over_platform():
    env = {
        "HOME": LINUX_HOME,
        "MAF_SCHEDULER_CONFIG_DIR": "/project/local/config",
        "MAF_SCHEDULER_DATA_DIR": "/project/local/data",
        "MAF_SCHEDULER_CACHE_DIR": "/project/local/cache",
        "MAF_SCHEDULER_LOG_DIR": "/project/local/logs",
    }
    pm = make_pm("linux", env)
    assert pm.config_dir() == Path("/project/local/config")
    assert pm.data_dir() == Path("/project/local/data")
    assert pm.cache_dir() == Path("/project/local/cache")
    assert pm.log_dir() == Path("/project/local/logs")


def test_custom_env_prefix_and_app_name():
    env = {"MY_APP_CONFIG_DIR": "/custom/cfg"}
    pm = PathManager("myapp", env_prefix="my-app", platform="linux",
                     env={"HOME": LINUX_HOME, **env})
    assert pm.config_dir() == Path("/custom/cfg")


# ── 文件路径拼接 ───────────────────────────────────────────────

def test_file_helpers_join_under_dir():
    pm = make_pm("linux", {"HOME": LINUX_HOME})
    assert pm.config_file("mcp_group_rules.json") == \
        Path(f"{LINUX_HOME}/.config/maf_scheduler/mcp_group_rules.json")
    assert pm.data_file("state", "state.json") == \
        Path(f"{LINUX_HOME}/.local/share/maf_scheduler/state/state.json")
    assert pm.cache_file("llm.bin") == Path(f"{LINUX_HOME}/.cache/maf_scheduler/llm.bin")
    assert pm.log_file("run.log") == Path(f"{LINUX_HOME}/.local/state/maf_scheduler/run.log")


# ── 目录创建 ───────────────────────────────────────────────────

def test_ensure_dirs_creates_all(tmp_path: Path):
    pm = PathManager("maf_scheduler", platform="linux", env={"HOME": str(tmp_path)})
    pm.ensure_dirs()
    for d in (pm.config_dir(), pm.data_dir(), pm.cache_dir(), pm.log_dir()):
        assert d.is_dir(), f"目录未创建: {d}"


def test_ensure_dir_is_idempotent(tmp_path: Path):
    pm = PathManager("maf_scheduler", platform="linux", env={"HOME": str(tmp_path)})
    pm.ensure_dirs()
    pm.ensure_dirs()  # 再次调用不报错


def test_ensure_parents_for_file(tmp_path: Path):
    pm = PathManager("maf_scheduler", platform="linux", env={"HOME": str(tmp_path)})
    target = pm.config_file("sub", "config.json")
    assert pm.ensure(target) == target
    assert target.parent.is_dir()


# ── 参数校验 ───────────────────────────────────────────────────

def test_empty_app_name_rejected():
    with pytest.raises(ValueError):
        PathManager("  ")
