"""数据流图公用小工具：流的样式 + 图例。"""

from dsl import note

F = dict(fontname="Source Han Sans CN", fontsize="9", color="#4b5563",
         fontcolor="#374151", penwidth="1.2", dir="forward", arrowhead="vee")
FD = dict(F, style="dashed")                 # 查询 / 次要流
FW = dict(F, color="#b8526a", penwidth="1.4")  # 主链路加粗

LEGEND = [
    "矩形 = 外部实体 E",
    "圆角 = 处理 P",
    "横线夹字 = 数据存储 D",
    "实线 = 主数据流",
    "虚线 = 查询 / 次要流",
]


def legend_node(d, extra=(), kind="neutral", title="图例"):
    d.node("legend", note(title, list(LEGEND) + ([""] + list(extra) if extra else []),
                          kind=kind))
