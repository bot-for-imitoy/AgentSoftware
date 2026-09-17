"""时序图 2：talk wait=true —— A 让 B 干活并同步等回复，以及班次结束时怎么解阻塞。"""

from seq import Seq


def build():
    s = Seq("时序：talk wait=true（甲等乙回话）", [
        ("a",   "Agent(A) / ToolLoop", "提问方"),
        ("tt",  "TalkToolkit", "工具"),
        ("tc",  "TalkService", "TeamChannel"),
        ("wa",  "WaitCoordinator(A)", "等待协议"),
        ("b",   "Agent(B)", "同事"),
        ("mb",  "AgentMailbox(B)", "队列"),
        ("trb", "TaskRunner(B)", "B 的工作线程"),
        ("sd",  "ShiftDirector", "班次边界"),
    ], gap=280)

    s.divider("A 侧的 LLM 调用了一个会阻塞的工具")

    s.call("a", "tt", "talk(to=B, text, wait=true, timeout)")
    s.call("tt", "tc", "sendAndWait(message, delegatedTask, timeout)")
    s.call("tc", "wa", "begin(B)   ← 登记“我在等 B”")
    s.note("begin() 与 deliver() 之间用一把锁 + 条件变量；await() 有超时，"
           "默认 10 分钟（模拟时间），超时不会死等。", over=["a", "wa"])

    s.divider("委托给 B：一条普通任务进 B 的队列")

    s.call("tc", "b", "submit(delegatedTask, deferred=false)")
    s.call("b", "mb", "push(task, false)")
    s.call("trb", "mb", "pop() : Task", ret=True)
    s.call("trb", "b", "beginTask(task) → ON_DUTY_BUSY")
    s.self_call("b", "B 跑自己的 ToolLoop；最终调 talk(to=A, reply)")
    s.call("b", "tc", "send(reply)")

    s.divider("回到 A：释放等待者")

    s.call("tc", "wa", "deliver(reply)")
    s.call("wa", "tc", "await() 返回 Optional[reply]", ret=True)
    s.call("tc", "tt", "Optional[reply]", ret=True)
    s.call("tt", "a", "ToolResult(text=reply)   ← 作为一条 tool 消息回给 LLM", ret=True)
    s.call("wa", "wa", "end()  清空 waitingFor")

    s.divider("班次结束时的兜底：绝不让 A 永久卡住")

    s.frame_begin("opt  下班时仍在等")
    s.call("sd", "a", "onShiftEnd → 收尾：状态改 WRAPPING_UP")
    s.call("a", "wa", "abort(\"(对方已下班，未回复)\")")
    s.call("wa", "tt", "await() 被唤醒，返回合成回复", ret=True)
    s.frame_end(["sd", "wa"])

    s.note("这就是 WaitCoordinator 存在的理由：master 里“等人”是散在 Tool 与 Role 里的"
           "一把锁，下班时会漏掉；现在解阻塞只有一个入口 abort()。", over=["wa", "sd"])

    s.render("seq-2-talk-wait")
