"""架构图 1：包依赖图 —— 直接从源码 import 里解析出来，不靠手写。

顺带把跨包 implements / extends 的关系标出来（架构评审最关心的那几条）。
"""

from __future__ import annotations

import pathlib
import re
from collections import Counter, defaultdict

from dsl import Dot, note, require_dot

ROOT = pathlib.Path(__file__).resolve().parents[2]          # 仓库根
SRC = ROOT / "src/main/java"
PKG_ROOT = "com.agent.software"

LAYER = [
    ("kernel", "#e9e4f7", 0),
    ("infra.config", "#e6eef8", 1), ("infra.json", "#e6eef8", 1),
    ("tool.spi", "#e6eef8", 1), ("transcript", "#e6eef8", 1),
    ("llm", "#fbeee0", 2), ("sim.event", "#fbe4e8", 2),
    ("sim.clock", "#e9e4f7", 3), ("agent.role", "#e6f4e8", 3),
    ("agent.dialog", "#e6f4e8", 3), ("agent.task", "#eceffc", 3),
    ("agent.dispatch", "#e6f4e8", 3),
    ("tool", "#eef0f4", 3),
    ("agent", "#dcefdf", 4),
    ("company.store", "#e2f1f4", 5), ("company", "#e2f1f4", 5),
    ("web", "#e8f5ea", 6),
    ("bootstrap", "#f3e9fa", 7),
]


def pkg_of(fqcn: str) -> str:
    """com.agent.software.tool.computer.PcToolkit -> tool.computer"""
    rest = fqcn[len(PKG_ROOT) + 1:]
    parts = rest.split(".")
    # 类名（首字母大写）及之后都丢掉
    keep = []
    for p in parts:
        if p[:1].isupper():
            break
        keep.append(p)
    if not keep:                       # com.agent.software.Agent 之类
        return "agent" if parts[0][:1].isupper() else parts[0]
    # tool.* 只保留两级（tool.computer / tool.spi …）
    if keep[0] == "tool":
        return ".".join(keep[:2])
    if keep[0] == "infra":
        return ".".join(keep[:2])
    if keep[0] == "sim":
        return ".".join(keep[:2]) if len(keep) > 1 else "sim"
    if keep[0] == "company":
        return ".".join(keep[:2]) if len(keep) > 1 else "company"
    if keep[0] == "agent":
        return ".".join(keep[:2]) if len(keep) > 1 else "agent"
    return keep[0]


def scan():
    files = sorted(SRC.rglob("*.java"))
    imports_by_pkg: dict[str, Counter] = defaultdict(Counter)
    impl_edges: set[tuple[str, str, str]] = set()
    known: dict[str, str] = {}         # 简单类名 -> 包

    for f in files:
        text = f.read_text(encoding="utf-8")
        src_pkg = re.search(r"^package\s+([\w.]+);", text, re.M).group(1)
        src = pkg_of(src_pkg)
        simple: dict[str, str] = {}
        for m in re.finditer(r"^import\s+(?:static\s+)?(" + re.escape(PKG_ROOT) + r"\.([\w.]+));", text, re.M):
            fq = m.group(1)
            simple[m.group(2).split(".")[-1]] = fq
            dst = pkg_of(fq)
            if dst != src:
                imports_by_pkg[src][dst] += 1
        cls = re.search(r"^public\s+(?:final\s+|abstract\s+|sealed\s+)?(?:class|interface|record|enum)\s+(\w+)", text, re.M)
        if cls:
            known.setdefault(cls.group(1), src)
        for m in re.finditer(r"^public[^\n{]*\b(?:implements|extends)\s+([^\n{]+)", text, re.M):
            for iface in re.findall(r"[A-Z]\w+", m.group(1)):
                fq = simple.get(iface)
                if not fq:
                    continue
                dst = pkg_of(fq)
                if dst != src:
                    impl_edges.add((src, dst, iface))
    return imports_by_pkg, impl_edges


def find_sccs(nodes, edges) -> list[list[str]]:
    """Tarjan 强连通分量；只返回大小 > 1 的。"""
    adj: dict[str, list[str]] = defaultdict(list)
    for u, v in edges:
        adj[u].append(v)
    index: dict[str, int] = {}
    low: dict[str, int] = {}
    on: dict[str, bool] = {}
    stack: list[str] = []
    out: list[list[str]] = []
    counter = [0]

    def strong(v):
        index[v] = low[v] = counter[0]
        counter[0] += 1
        stack.append(v)
        on[v] = True
        for w in adj[v]:
            if w not in index:
                strong(w)
                low[v] = min(low[v], low[w])
            elif on.get(w):
                low[v] = min(low[v], index[w])
        if low[v] == index[v]:
            comp = []
            while True:
                w = stack.pop()
                on[w] = False
                comp.append(w)
                if w == v:
                    break
            if len(comp) > 1:
                out.append(sorted(comp))

    for n in nodes:
        if n not in index:
            strong(n)
    return sorted(out, key=len, reverse=True)


def layer_of(pkg: str) -> tuple[str, int]:
    """颜色 + 层号：优先精确匹配，其次最长前缀匹配。"""
    best = None
    for p, color, layer in LAYER:
        if p == pkg:
            return color, layer
        if pkg.startswith(p + ".") and (best is None or len(p) > len(best[0])):
            best = (p, color, layer)
    return (best[1], best[2]) if best else ("#f4f5f7", 3)


def build():
    imports_by_pkg, impl_edges = scan()

    # 节点集合 = 源码里真实存在的包（LAYER 只负责着色与分层）
    all_pkgs = set(imports_by_pkg) | {t for c in imports_by_pkg.values() for t in c}
    edges = {(s, t) for s, c in imports_by_pkg.items() for t in c}

    d = Dot("arch_packages", rankdir="TB", nodesep="0.32", ranksep="0.55")

    sccs = find_sccs(sorted(all_pkgs), edges)
    in_cycle = {p: i for i, comp in enumerate(sccs) for p in comp}
    cyclic_edges = {(s, t) for s, t in edges
                    if s in in_cycle and in_cycle.get(s) == in_cycle.get(t)}

    for pkg in sorted(all_pkgs):
        color, layer = layer_of(pkg)
        uses = sum(imports_by_pkg.get(pkg, {}).values())
        used_by = sum(1 for s, c in imports_by_pkg.items() if pkg in c)
        badge = (f'<BR ALIGN="CENTER"/><FONT POINT-SIZE="8" COLOR="#d13438">'
                 f'⚠ 环 #{in_cycle[pkg] + 1}</FONT>') if pkg in in_cycle else ''
        html = (f'<TABLE BORDER="0" CELLBORDER="1" CELLSPACING="0" CELLPADDING="5"'
                f' COLOR="{"#d13438" if pkg in in_cycle else "#5f6875"}">'
                f'<TR><TD BGCOLOR="{color}">'
                f'<FONT POINT-SIZE="12"><B>{pkg}</B></FONT>{badge}</TD></TR>'
                f'<TR><TD><FONT POINT-SIZE="9" COLOR="#6b7280">'
                f'L{layer} · 出 {uses} / 入 {used_by}</FONT></TD></TR></TABLE>')
        d.node(pkg, (f'<{html}>',
                     {"shape": "plaintext", "fontname": "Source Han Sans CN"}))

    levels: dict[int, list[str]] = defaultdict(list)
    for pkg in sorted(all_pkgs):
        levels[layer_of(pkg)[1]].append(pkg)
    for layer, pkgs_at in sorted(levels.items()):
        if len(pkgs_at) > 1:
            d.raw("{ rank=same; " + "; ".join(f'"{p}"' for p in pkgs_at) + "; }")

    impl_pairs = {(s, t) for s, t, _ in impl_edges}
    for s, t in sorted(edges):
        n = imports_by_pkg[s][t]
        kw = dict(dir="forward", arrowhead="vee", color="#9aa2ad",
                  penwidth=str(min(1.0 + 0.45 * n, 2.8)), fontname="Source Han Sans CN",
                  fontsize="8", fontcolor="#6b7280")
        if (s, t) in impl_pairs:
            kw.update(color="#8557a3", penwidth="2.2", style="dashed")
        if (s, t) in cyclic_edges:
            kw.update(color="#d13438", penwidth="2.4")
        d.edge(s, t, kw)

    # feature 粒度（把 agent.task 折回 agent）再算一次
    feat = lambda p: p.split(".")[0]                                   # noqa: E731
    feat_nodes = sorted({feat(p) for p in all_pkgs})
    feat_edges = {(feat(s), feat(t)) for s, t in edges if feat(s) != feat(t)}
    feat_sccs = find_sccs(feat_nodes, feat_edges)
    feat_pairs = sorted({(s, t) for s, t in feat_edges
                         if any(s in c and t in c for c in feat_sccs)})

    d.node("legend", note("图例 · 这张图应该是 DAG", [
        "实线 = import 方向（被依赖方通常在下）",
        "线粗 ∝ import 次数",
        "紫色虚线 = 跨包 implements（SPI 接缝，正常）",
        "红色 + ⚠ = 处在同一个强连通分量里",
        "",
        f"包粒度：{len(sccs)} 个环，{len(in_cycle)} 个包、{len(cyclic_edges)} 条边",
        *[f"  环 #{i + 1}（{len(c)} 包）：" + "、".join(c) for i, c in enumerate(sccs)],
        "",
        f"feature 粒度：{len(feat_sccs)} 个环",
        *[f"  " + "、".join(c) for c in feat_sccs],
        *[f"    · {s} → {t}" for s, t in feat_pairs],
        "",
        "★ 这一轮已经拆掉的 5 处：",
        "① sim.event→agent：投递策略（DeliveryPolicy /",
        "   SaliencePolicy 家族）已移到 agent.dispatch，",
        "   sim.event 只剩 AgentEvent / EventKind /",
        "   Priority / EventSink，只依赖 kernel",
        "② sim.clock↔sim.event：Tick、DayTick 下放到 kernel",
        "③ agent↔agent.task：TaskRunner 不再持有 Agent，",
        "   改持 AgentMailbox + AgentTasks + AgentControl",
        "④ agent↔agent.role：RoleSnapshot 由 agent.role",
        "   搬到 agent（它本来就是 Agent 的快照）",
        "⑤ agent↔tool.note：新增 agent.dialog.DailySummary，",
        "   由 tool.note.JsonNoteBook 实现，",
        "   agent.dialog 不再 import 具体工具包",
        "",
        "★ 还剩两个环，都不是手滑，是选择题：",
        "· agent ↔ agent.task：端口（AgentTasks /",
        "  AgentControl / AgentMailbox / LifecycleGate）",
        "  住在父包 agent 里，而 agent.task 要用它们。",
        "  要拆就把这些端口下沉到 agent.task（或再开",
        "  agent 的 contract 子包）；不拆则是标准的",
        "  “父包持契约、子包实现”形态。",
        "· agent ↔ tool（feature 粒度）：agent 持有",
        "  Shell，而 tool.computer 需要 RoleSpec.ComputerSpec",
        "  → 即 PLAN §5.9-B.8 那条已知反向边。",
        "  另一条 tool ↔ llm 来自 ToolSpec 放在 tool.spi；",
        "  把 ToolSpec 下放到 kernel 即可消掉。",
    ], kind="neutral"))

    require_dot()
    d.render("arch-1-packages")
