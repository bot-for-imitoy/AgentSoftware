"""NoteStore 单元测试 (本地目录, 不依赖 podman).

覆盖 (对应代码审查报告 Low-9 / High-3 / Low-12):
  - _sanitize_title 清洗 shell 元字符 (High-3 回归: 单引号/反引号/$ 不再原样保留)
  - get_latest_summary 按天数值排序 (commit 0facd69 回归: day_10 > day_9)
  - delete_note 真实删除文件 (Low-12 回归: 不再只是置空)

运行: cd 项目根 && .venv/bin/python -m unittest discover -s tests -v
"""

from __future__ import annotations

import tempfile
import unittest
from pathlib import Path

from src.core.note_store import NoteStore


class SanitizeTitleTest(unittest.TestCase):
    """标题清洗: shell 元字符必须被替换 (High-3 注入面)."""

    def test_shell_metachars_stripped(self) -> None:
        dirty = "it's my note $(whoami) `id`"
        clean = NoteStore._sanitize_title(dirty)
        # 单引号/反引号/$/分号/& 全部被替换 — 命令替换无法再执行 (注入面关闭).
        # whoami 作为字面文件名保留无害: 危险的是 $() 执行语义, 不是文本本身.
        for ch in "'`$;&":
            self.assertNotIn(ch, clean)
        self.assertNotIn("$(", clean)
        self.assertEqual(clean, "it_s_my_note_(whoami)_id_")

    def test_illegal_filename_chars_stripped(self) -> None:
        self.assertEqual(NoteStore._sanitize_title('a/b\\c:d*e?f"g<h>i|j'), "a_b_c_d_e_f_g_h_i_j")
        self.assertEqual(NoteStore._sanitize_title("   "), "untitled")

    def test_normal_title_kept(self) -> None:
        self.assertEqual(NoteStore._sanitize_title("周报-2026-08"), "周报-2026-08")


class LatestSummaryTest(unittest.TestCase):
    """总结按天数值排序: day_10 必须排在 day_9 前面 (字典序会排错)."""

    def setUp(self) -> None:
        self._tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self._tmp.cleanup)
        self.store = NoteStore(base_dir=self._tmp.name, role_id="CEO")

    def test_numeric_ordering(self) -> None:
        self.store.save_summary("第九天总结", day=9)
        self.store.save_summary("第十天总结", day=10)
        # 文件名就是天数 (10.md / 9.md); 数值序必须取 day 10
        self.assertEqual(self.store.get_latest_summary(before_day=11), "第十天总结")
        self.assertEqual(self.store.get_latest_summary(before_day=10), "第九天总结")
        self.assertIsNone(self.store.get_latest_summary(before_day=9))

    def test_no_summaries(self) -> None:
        self.assertIsNone(self.store.get_latest_summary())


class DeleteNoteTest(unittest.TestCase):
    """delete_note 真实删除文件 (Low-12): 文件必须消失, 再删返回 False."""

    def setUp(self) -> None:
        self._tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self._tmp.cleanup)

    def test_local_delete_removes_file(self) -> None:
        store = NoteStore(base_dir=self._tmp.name, role_id="CEO")
        store.write_note("备忘录", "内容")
        # 新架构: 笔记落在 <role_id>/notes/ 子目录
        path = Path(self._tmp.name) / "CEO" / "notes" / "备忘录.md"
        self.assertTrue(path.exists())
        self.assertTrue(store.delete_note("备忘录"))
        self.assertFalse(path.exists())
        self.assertFalse(store.delete_note("备忘录"))  # 已删 → False


if __name__ == "__main__":
    unittest.main()
