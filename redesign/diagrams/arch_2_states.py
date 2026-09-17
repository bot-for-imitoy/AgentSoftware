"""架构图 2：Agent 生命周期状态机 + 暂停门。

master 的状态是 `AgentRole` 里几个 boolean 的组合（busy / offDuty / waiting…），
这里收敛成一个枚举 + 一个唯一写者 AgentStateMachine。
"""

from dsl import Dot, note, require_dot

FILL = "#e8f4ea"
LINE = "#3f8b4c"


def state(label, sub, fill=FILL, line=LINE):
    html = (f'<TABLE BORDER="0" CELLBORDER="0" CELLSPACING="0" CELLPADDING="7"'
            f' BGCOLOR="{fill}" COLOR="{line}"><TR><TD ALIGN="CENTER">'
            f'<FONT POINT-SIZE="13"><B>{label}</B></FONT>'
            f'<BR ALIGN="CENTER"/><FONT POINT-SIZE="9" COLOR="#5a6472">{sub}</FONT>'
            f'</TD></TR></TABLE>')
    return f'<{html}>', {"shape": "box", "style": "rounded,filled",
                         "fillcolor": fill, "color": line, "penwidth": "1.6",
                         "fontname": "Source Han Sans CN", "margin": "0"}


def arrow(d, a, b, label, color="#3f74b5", dashed=False, **kw):
    d.edge(a, b, dict(dir="forward", arrowhead="vee", color=color, penwidth="1.6",
                      fontname="Source Han Sans CN", fontsize="10", fontcolor="#374151",
                      label=f" {label} ", style="dashed" if dashed else "solid", **kw))


def build():
    d = Dot("arch_states", rankdir="LR", nodesep="0.5", ranksep="1.1")

    d.node("off", state("OFF_DUTY", "下班：电脑已关机")) 
    d.node("idle", state("ON_DUTY_IDLE", "在岗待命：可以接普通任务"))
    d.node("busy", state("ON_DUTY_BUSY", "执行中：持有一个 Task"))
    d.node("waiting", state("WAITING", "等同事回话：talk wait=true"))
    d.node("wrap", state("WRAPPING_UP", "收尾：写总结 / 关对话", "#fdf6e7", "#b8862c"))
    d.node("emerg", state("acceptsEmergency()", "紧急事件在 WAITING / WRAPPING_UP 仍可投递",
                          "#fdeef0", "#b8526a"))

    arrow(d, "off", "idle", "ShiftDirector.onShiftStart\npowerOn + promoteDeferred")
    arrow(d, "idle", "busy", "TaskRunner 取到任务\nAgent.beginTask")
    arrow(d, "busy", "idle", "Agent.finishTask\n（成功 / 失败 / 超限都算）", color="#4f8b63")
    arrow(d, "busy", "waiting", "TalkToolkit 调用\nWaitCoordinator.begin(B)")
    arrow(d, "waiting", "busy", "deliver(reply) / abort(合成回复)", color="#4f8b63")
    arrow(d, "idle", "wrap", "ShiftDirector.onShiftEnd", color="#b8862c")
    arrow(d, "busy", "wrap", "onShiftEnd（跑完手头这轮工具调用）", color="#b8862c")
    arrow(d, "waiting", "wrap", "onShiftEnd → abort 解阻塞", color="#b8862c")
    arrow(d, "wrap", "off", "closeDayConversation\n+ powerOffComputer", color="#6b7280")
    arrow(d, "busy", "off", "forceWrapUp（超时兜底）", color="#b8526a", dashed=True,
          constraint="false")
    arrow(d, "idle", "off", "forceWrapUp（超时兜底）", color="#b8526a", dashed=True,
          constraint="false")

    d.edge("emerg", "waiting", dict(dir="none", style="dotted", color="#b8526a",
                                    penwidth="1.4", constraint="false"))
    d.edge("emerg", "wrap", dict(dir="none", style="dotted", color="#b8526a",
                                 penwidth="1.4", constraint="false"))

    d.node("gate", note("LifecycleGate（Company.pause/reason）", [
        "暂停不是状态机的第 6 个状态，而是",
        "一个独立的只读闸门：",
        "",
        "· TaskRunner 每轮开头 awaitRunning()",
        "  → 没取到任务时原地等，不进 BUSY",
        "· ClockDriver 同时被 pause()，",
        "  模拟时间停住，不会跑到下班",
        "· 已经在跑的一轮工具调用不打断",
        "",
        "所以“暂停中”= gate.paused() 为真，",
        "Agent 仍处在各自的正常状态里。",
    ], kind="company"))

    d.node("who", note("谁改状态（唯一写者）", [
        "AgentStateMachine.to(next) 是唯一入口；",
        "调用它的只有三处：",
        "  · Agent.beginTask / finishTask",
        "  · Agent.transitionTo（AgentControl 接口）",
        "  · Agent.restore（从快照恢复）",
        "",
        "ShiftDirector 不直接写状态，它通过",
        "AgentControl 让 Agent 自己迁移 —— 这就是",
        "把 920 行的 TimeEventBus 拆开的收益：",
        "班次逻辑不再认识状态机的内部字段。",
    ], kind="neutral"))

    require_dot()
    d.render("arch-2-states")
