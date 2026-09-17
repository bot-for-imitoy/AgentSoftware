"""
极简 Graphviz DSL —— 用来生成 UML 类图 / 数据流图。

只做三件事：
  1. 把 Python 里的描述变成 .dot 源码（保留源码，便于 review 与 diff）；
  2. 调 `dot` 渲染成 png + svg；
  3. 提供 UML / DFD 两种记法的语法糖。

约定（与一张 UML 图的标准记法一致）：
  comp()    组合    owner ◆──── part        实心菱形指向“整体”
  aggr()    聚合    owner ◇──── part        空心菱形指向“整体”
  assoc()   关联    ──────────▶
  inherit() 继承    ──────────▷            实线空心三角
  realize() 实现    ┄┄┄┄┄┄┄┄▷            虚线空心三角
  depend()  依赖    ┄┄┄┄┄┄┄┄▶            虚线开放箭头
"""

from __future__ import annotations

import html as _html
import pathlib
import shutil
import subprocess
from contextlib import contextmanager

HERE = pathlib.Path(__file__).resolve().parent
OUT = HERE / "out"

# 思源黑体，覆盖中英文；graphviz 通过 fontconfig 找字体
FONT = "Source Han Sans CN"
FS = 11          # 正文字号
FS_TITLE = 12
FS_SMALL = 9
FS_TINY = 8

PAL = {
    "iface":     ("#eaf2fd", "#3f74b5"),   # 接口 / SPI
    "agent":     ("#e8f4ea", "#3f8b4c"),   # agent 包
    "task":      ("#eceffc", "#4b55b0"),   # agent.task 包
    "dialog":    ("#e6f6f3", "#2f8878"),   # agent.dialog 包
    "domain":    ("#fdf6e7", "#b8862c"),   # 值对象 / 领域数据
    "sim":       ("#f3edfb", "#7551b0"),   # sim 包
    "event":     ("#fdeef0", "#b8526a"),   # sim.event 包
    "company":   ("#e8f6f8", "#3a8794"),   # company 包
    "tool":      ("#eff0f4", "#5f6875"),   # tool 包
    "llm":       ("#fdf1e6", "#c07a35"),   # llm 包
    "web":       ("#eef7f0", "#4f8b63"),   # web / transcript
    "boot":      ("#f6eefa", "#8557a3"),   # bootstrap
    "neutral":   ("#f4f5f7", "#8a929c"),
    "ext":       ("#e6e7ec", "#454c57"),   # 外部实体
    "proc":      ("#e9f2fd", "#3a72b0"),   # DFD 处理
    "store":     ("#f2f0e6", "#9a8b3a"),   # DFD 数据存储
    "cluster":   ("#fbfbfd", "#c3c9d2"),   # DFD 分区
}


def esc(text: object) -> str:
    return _html.escape(str(text), quote=False)


def _lines(items) -> str:
    return '<BR ALIGN="LEFT"/>'.join(items)


def _node_attrs(kind: str, **extra) -> dict:
    _, line = PAL[kind]
    attrs = {"shape": "plaintext", "fontname": FONT, "fontsize": FS}
    attrs.update(extra)
    return attrs


# --------------------------------------------------------------------------- #
# UML 类 / 接口
# --------------------------------------------------------------------------- #
def uml(title: str, stereotype=None, attrs=(), methods=(), kind="domain",
        tag=None, title_color=None) -> tuple[str, dict]:
    """返回 (html_label, attrs)，交给 Dot.node()。"""
    fill, line = PAL[kind]
    tc = title_color or line

    head = [f'<TR><TD BGCOLOR="{fill}" ALIGN="CENTER">'
            f'<FONT COLOR="{tc}" POINT-SIZE="{FS_TITLE}"><B>{esc(title)}</B></FONT></TD></TR>']
    if stereotype:
        head.insert(0, f'<TR><TD BGCOLOR="{fill}" ALIGN="CENTER">'
                       f'<FONT COLOR="{tc}" POINT-SIZE="{FS_TINY}">«{esc(stereotype)}»</FONT></TD></TR>')
    if tag:
        head.append(f'<TR><TD BGCOLOR="{fill}" ALIGN="CENTER">'
                    f'<FONT COLOR="#6b7280" POINT-SIZE="{FS_TINY}">{esc(tag)}</FONT></TD></TR>')

    rows = list(head)
    if attrs:
        rows.append('<TR><TD ALIGN="LEFT" BALIGN="LEFT">'
                    + _lines(esc(a) for a in attrs) + '</TD></TR>')
    if methods:
        rows.append('<TR><TD ALIGN="LEFT" BALIGN="LEFT">'
                    + _lines(esc(m) for m in methods) + '</TD></TR>')

    label = (f'<TABLE BORDER="0" CELLBORDER="1" CELLSPACING="0" CELLPADDING="4"'
             f' COLOR="{line}">{"".join(rows)}</TABLE>')
    return f'<{label}>', _node_attrs(kind)


def table(title, columns, rows, kind="tool") -> tuple[str, dict]:
    """一个节点里塞一张普通表格，用来列目录（不是 UML 类框）。"""
    fill, line = PAL[kind]
    head = "".join(f'<TD BGCOLOR="{line}"><FONT COLOR="white" POINT-SIZE="{FS_SMALL}">'
                   f'<B>{esc(c)}</B></FONT></TD>' for c in columns)
    body = []
    for r in rows:
        body.append("<TR>" + "".join(
            f'<TD ALIGN="LEFT" BALIGN="LEFT"><FONT POINT-SIZE="{FS_SMALL}">{esc(c)}</FONT></TD>'
            for c in r) + "</TR>")
    label = (f'<TABLE BORDER="0" CELLBORDER="1" CELLSPACING="0" CELLPADDING="4" COLOR="{line}">'
             f'<TR><TD COLSPAN="{len(columns)}" BGCOLOR="{fill}" ALIGN="CENTER">'
             f'<FONT POINT-SIZE="{FS_TITLE}" COLOR="{line}"><B>{esc(title)}</B></FONT></TD></TR>'
             f'<TR>{head}</TR>{"".join(body)}</TABLE>')
    return f'<{label}>', {"shape": "plaintext", "fontname": FONT, "fontsize": FS}


def note(title: str, lines, kind="neutral", width=None) -> tuple[str, dict]:
    # 注意：同一格内混用不同 POINT-SIZE 会让 graphviz 行高算错导致文字重叠，
    # 所以每行单独一个 <TR>。
    fill, line = PAL[kind]
    rows = [f'<TR><TD BGCOLOR="{fill}" ALIGN="LEFT" BALIGN="LEFT">'
            f'<FONT COLOR="{line}"><B>{esc(title)}</B></FONT></TD></TR>']
    for x in lines:
        txt = esc(x) if str(x).strip() else "&#160;"
        rows.append(f'<TR><TD BGCOLOR="{fill}" ALIGN="LEFT" BALIGN="LEFT">'
                    f'<FONT POINT-SIZE="{FS_SMALL}">{txt}</FONT></TD></TR>')
    body = (f'<TABLE BORDER="0" CELLBORDER="0" CELLSPACING="0" CELLPADDING="3">'
            + "".join(rows) + '</TABLE>')
    a = _node_attrs(kind)
    if width:
        a["width"] = width
        a["fixedsize"] = False
    return f'<{body}>', a


# --------------------------------------------------------------------------- #
# DFD 三件套
# --------------------------------------------------------------------------- #
def dfd_process(num: str, name: str, sub=None) -> tuple[str, dict]:
    fill, line = PAL["proc"]
    second = f'<BR ALIGN="LEFT"/><FONT POINT-SIZE="{FS_SMALL}" COLOR="#5a6472">{esc(sub)}</FONT>' if sub else ''
    label = (f'<TABLE BORDER="0" CELLBORDER="0" CELLSPACING="0" CELLPADDING="5"'
             f' BGCOLOR="{fill}" STYLE="rounded" COLOR="{line}">'
             f'<TR>'
             f'<TD ROWSPAN="1" BGCOLOR="{line}" ALIGN="CENTER">'
             f'<FONT COLOR="white" POINT-SIZE="{FS}"><B>{esc(num)}</B></FONT></TD>'
             f'<TD ALIGN="LEFT" BALIGN="LEFT"><FONT COLOR="#1f2a37">{esc(name)}</FONT>{second}</TD>'
             f'</TR></TABLE>')
    return f'<{label}>', {"shape": "box", "style": "rounded,filled", "fillcolor": fill,
                          "color": line, "penwidth": "1.2",
                          "fontname": FONT, "fontsize": FS, "margin": "0"}


def dfd_store(num: str, name: str, sub=None) -> tuple[str, dict]:
    """Gane–Sarson 记法：上下两条横线，左右开口。"""
    fill, line = PAL["store"]
    second = f'<BR ALIGN="LEFT"/><FONT POINT-SIZE="{FS_SMALL}" COLOR="#6b7280">{esc(sub)}</FONT>' if sub else ''
    label = (f'<TABLE BORDER="0" CELLBORDER="0" CELLSPACING="0" CELLPADDING="3">'
             f'<TR><TD BORDER="1" SIDES="T" COLOR="{line}" WIDTH="14"></TD></TR>'
             f'<TR><TD ALIGN="LEFT" BALIGN="LEFT">'
             f'<FONT COLOR="#3b3a2a"><B>{esc(num)}</B> {esc(name)}</FONT>{second}</TD></TR>'
             f'<TR><TD BORDER="1" SIDES="B" COLOR="{line}"></TD></TR>'
             f'</TABLE>')
    return f'<{label}>', {"shape": "plaintext", "fontname": FONT, "fontsize": FS}


def dfd_entity(num: str, name: str, sub=None) -> tuple[str, dict]:
    fill, line = PAL["ext"]
    second = f'<BR ALIGN="LEFT"/><FONT POINT-SIZE="{FS_SMALL}" COLOR="#6b7280">{esc(sub)}</FONT>' if sub else ''
    label = (f'<TABLE BORDER="0" CELLBORDER="0" CELLSPACING="0" CELLPADDING="5"'
             f' BGCOLOR="{fill}" COLOR="{line}">'
             f'<TR><TD ALIGN="LEFT" BALIGN="LEFT">'
             f'<FONT COLOR="#1f2a37"><B>{esc(num)}</B> {esc(name)}</FONT>{second}</TD></TR>'
             f'</TABLE>')
    return f'<{label}>', {"shape": "box", "style": "filled", "fillcolor": fill,
                          "color": line, "penwidth": "1.2",
                          "fontname": FONT, "fontsize": FS, "margin": "0"}


# --------------------------------------------------------------------------- #
# 关系语法糖
# --------------------------------------------------------------------------- #
def comp(d, owner, part, label=None, **kw):
    d.edge(owner, part, _rel("diamond", label, solid=True, **kw))


def aggr(d, owner, part, label=None, **kw):
    d.edge(owner, part, _rel("odiamond", label, solid=True, **kw))


def assoc(d, src, dst, label=None, **kw):
    d.edge(src, dst, _rel(None, label, solid=True, **kw))


def inherit(d, sub, sup, label=None, **kw):
    d.edge(sub, sup, _rel("onormal", label, solid=True, **kw))


def realize(d, impl, iface, label=None, **kw):
    d.edge(impl, iface, _rel("onormal", label, solid=False, **kw))


def depend(d, src, dst, label=None, **kw):
    d.edge(src, dst, _rel("open", label, solid=False, **kw))


def _rel(tail_arrow, label, solid, **kw):
    a = {"dir": "both", "arrowhead": "none", "penwidth": "1.1", "color": "#5b6472",
         "fontname": FONT, "fontsize": FS_SMALL, "fontcolor": "#4b5563"}
    if tail_arrow:
        a["arrowtail"] = tail_arrow
    else:
        a["dir"] = "forward"
        a["arrowhead"] = "vee"
    if not solid:
        a["style"] = "dashed"
    if label:
        a["label"] = f" {label} "
        a["labeldistance"] = "1.4"
    a.update(kw)
    return a


# --------------------------------------------------------------------------- #
# 图
# --------------------------------------------------------------------------- #
class Dot:
    def __init__(self, name, rankdir="TB", nodesep="0.35", ranksep="0.55",
                 splines="spline", bgcolor="white", pad="0.3", compound="true"):
        self.name = name
        self.stmts: list[str] = []
        self.graph_attrs = {
            "rankdir": rankdir, "nodesep": nodesep, "ranksep": ranksep,
            "splines": splines, "bgcolor": bgcolor, "pad": pad,
            "compound": compound, "fontname": FONT, "fontsize": FS,
            "newrank": "true",
        }
        self.node_defaults = {"shape": "plaintext", "fontname": FONT, "fontsize": FS}
        self.edge_defaults = {"fontname": FONT, "fontsize": FS_SMALL,
                              "color": "#5b6472", "penwidth": "1.1"}

    # -- 基本写入 ---------------------------------------------------------- #
    def raw(self, line: str):
        self.stmts.append(line.rstrip())

    def node(self, nid, spec, **attrs):
        if isinstance(spec, tuple):
            label, base = spec
            base = dict(base)
            base.update(attrs)
            attrs = base
            spec = label
        all_attrs = {"label": spec, **attrs}
        self.raw(f'"{nid}" [{_attrs(all_attrs)}];')

    def edge(self, tail, head, attrs: dict | None = None, **kw):
        a = dict(attrs or {})
        a.update(kw)
        self.raw(f'"{tail}" -> "{head}" [{_attrs(a)}];')

    @contextmanager
    def cluster(self, name, label=None, kind="cluster", **attrs):
        outer = self.stmts
        self.stmts = []
        yield
        body = self.stmts
        self.stmts = outer
        fill, line = PAL.get(kind, PAL["cluster"])
        a = {"style": "rounded,filled", "fillcolor": fill, "color": line,
             "penwidth": "1.1", "fontname": FONT, "fontsize": FS,
             "labeljust": "l", "margin": "10"}
        if label:
            a["label"] = label
        a.update(attrs)
        self.raw(f'subgraph "cluster_{name}" {{')
        for k, v in a.items():
            self.raw(f'  {k}="{v}";')
        self.stmts.extend("  " + s for s in body)
        self.raw("}")

    def same_rank(self, *nodes):
        body = "; ".join(f'"{n}"' for n in nodes)
        self.raw(f"{{ rank=same; {body}; }}")

    # -- 渲染 -------------------------------------------------------------- #
    def source(self) -> str:
        head = [f'digraph "{self.name}" {{']
        head += [f'  {k}="{v}";' for k, v in self.graph_attrs.items()]
        head.append(f'  node [{_attrs(self.node_defaults)}];')
        head.append(f'  edge [{_attrs(self.edge_defaults)}];')
        return "\n".join(head + ["  " + s for s in self.stmts] + ["}", ""])

    def render(self, stem, formats=("png", "svg"), dpi=170):
        OUT.mkdir(parents=True, exist_ok=True)
        src = OUT / f"{stem}.dot"
        src.write_text(self.source(), encoding="utf-8")
        outs = []
        for fmt in formats:
            target = OUT / f"{stem}.{fmt}"
            cmd = ["dot", f"-T{fmt}"]
            if fmt == "png":
                cmd.append(f"-Gdpi={dpi}")
            cmd += [str(src), "-o", str(target)]
            subprocess.run(cmd, check=True)
            outs.append(target)
        print(f"  ✔ {stem:<24} " + "  ".join(f"{p.name} ({p.stat().st_size // 1024}KB)" for p in outs))
        return outs


def _attrs(d: dict) -> str:
    out = []
    for k, v in d.items():
        v = str(v)
        if v.startswith("<") and v.endswith(">"):
            out.append(f"{k}={v}")
        else:
            out.append(f'{k}="{v}"')
    return ", ".join(out)


def require_dot():
    if not shutil.which("dot"):
        raise SystemExit("需要 graphviz 的 dot 命令")
