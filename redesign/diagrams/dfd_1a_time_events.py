"""DFD Level-1 之一：输入从哪来、事件怎么变成队列里的任务。

覆盖 ① 时间与感知 + ② 事件与投递。
"""

from dsl import Dot, dfd_entity, dfd_process, dfd_store, require_dot
from dfd_common import F, FD, FW, legend_node


def build():
    d = Dot("dfd_1a", rankdir="LR", nodesep="0.35", ranksep="0.9")

    # ---- ① 时间与感知 ----
    d.node("e_tick", dfd_entity("E1", "时钟线程", "ClockDriver 自己的工作线程"))
    d.node("p1", dfd_process("P1", "ClockPolicy", "纯决策，无 I/O"))
    d.node("p2", dfd_process("P2", "ClockDriver", "每步：传感器→策略→推进→取事件"))
    d.node("p3", dfd_process("P3", "ShiftDirector", "上下班边界动作"))
    d.node("d2", dfd_store("D2", "ScheduleTable", "定时提醒项"))

    # ---- ② 事件与投递 ----
    d.node("e_client", dfd_entity("E3", "客户 / 用户", "Web GUI · 控制台"))
    d.node("e_smtp", dfd_entity("E6", "SMTP 服务", "新邮件"))
    d.node("d7", dfd_store("D7", "Mailbox", "收件箱"))
    d.node("p4", dfd_process("P4", "EventRouter", "解收件人：广播→全员 / 定向→单人"))
    d.node("p5", dfd_process("P5", "DeliveryPolicy", "纯决策：DELIVER / HOLD / DROP"))
    d.node("p5s", dfd_process("P5.1", "SaliencePolicy", "兴趣得分"))
    d.node("p6", dfd_process("P6", "TaskFactory", "AgentEvent → Task"))
    d.node("d1", dfd_store("D1", "AgentMailbox", "ready / deferred"))
    d.node("d8", dfd_store("D8", "Team / Agent", "花名册 · 状态 · 历史"))

    # ---- 流 ----
    d.edge("e_tick", "p2", dict(F, label=" 每步 "))
    d.edge("p2", "p1", dict(FW, label=" ClockSignals "))
    d.edge("p1", "p2", dict(FW, label=" ClockAction "))
    d.edge("p2", "d2", dict(FD, label=" nextFireTick "))
    d.edge("d2", "p2", dict(F, label=" due(now) "))
    d.edge("p2", "p3", dict(FW, label=" onTick(Tick) "))
    d.edge("p3", "d8", dict(F, label=" 开机 / 提升暂存 / 收尾 / 强制下班 "))
    d.edge("d8", "p1", dict(FD, label=" anyBusy / allIdle / allOffDuty "))

    d.edge("e_client", "p4", dict(FW, label=" 提问 / 回复 / 暂停·恢复 "))
    d.edge("e_smtp", "d7", dict(F, label=" 新邮件 "))
    d.edge("d7", "p4", dict(F, label=" NEW_MAIL "))
    d.edge("d2", "p4", dict(FW, label=" AgentEvent（到期提醒） "))
    d.edge("d8", "p4", dict(FD, label=" 收件人名单 specs() "))
    d.edge("p4", "p5", dict(FW, label=" DeliveryContext（逐收件人） "))
    d.edge("p5s", "p5", dict(FD, label=" 得分 / 阈值 "))
    d.edge("p5", "p6", dict(FW, label=" DELIVER "))
    d.edge("p5", "d1", dict(F, label=" HOLD → deferred=true "))
    d.edge("p6", "d1", dict(FW, label=" Task（含 urgency / context） "))
    d.edge("p6", "d8", dict(FD, label=" assignee "))

    legend_node(d, [
        "P1 / P5 是纯函数：",
        "同样输入必得同样输出，",
        "方便单测，也不碰 I/O。",
        "",
        "HOLD 不是丢弃：任务进",
        "deferred 队列，等 P3 在",
        "上班 / 状态变化时提升。",
        "",
        "P3 是唯一会改 Agent 状态",
        "的班次推手。",
    ])

    require_dot()
    d.render("dfd-1a-time-events")
