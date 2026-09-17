"""时序图 1：一个任务的一生 —— 从时钟走一步，到结果写回。"""

from seq import Seq


def build():
    s = Seq("时序：一个任务的一生（1 模拟秒 → 完成）", [
        ("cd",  "ClockDriver", "时钟线程"),
        ("cp",  "ClockPolicy", "纯决策"),
        ("sc",  "SimClock", "Tick 推进"),
        ("sd",  "ShiftDirector", "班次边界"),
        ("st",  "ScheduleTable", "定时提醒"),
        ("er",  "EventRouter", "解收件人"),
        ("dp",  "DeliveryPolicy", "投递裁决"),
        ("tf",  "TaskFactory", "造任务"),
        ("ag",  "Agent + Mailbox", "角色"),
        ("tr",  "TaskRunner", "工作线程"),
        ("tl",  "ToolLoop", "LLM 循环"),
        ("llm", "LlmClient", "模型"),
        ("tb",  "Toolbox", "工具"),
        ("cm",  "ConversationMemory", "对话"),
    ], gap=270)

    s.divider("每个模拟步：先问策略，再推进时钟")

    s.call("cd", "cp", "next(ClockSignals, calendar, now, nextFireTick)")
    s.call("cp", "cd", "ClockAction.Advance(1s) | FastForwardTo | Hold | ForceWrapUp", ret=True)
    s.call("cd", "sc", "advanceTicks(n) / jumpTo(t)")
    s.call("cd", "st", "due(now) : List<AgentEvent>", ret=True)
    s.call("cd", "sd", "onTick(now)   ← 只在班次边界真的做事")
    s.self_call("sd", "上班：全员 powerOn + promoteDeferred()；\n下班：写总结 + 强制收尾")

    s.divider("事件 → 任务：投递裁决在这里决定“立刻 / 稍后 / 丢弃”")

    s.frame_begin("loop  每个收件人")
    s.call("er", "dp", "decide(DeliveryContext{event, spec, state})")
    s.call("dp", "er", "DeliveryDecision(DELIVER | HOLD | DROP) + reason", ret=True)
    s.frame_end(["er", "dp"])
    s.call("er", "tf", "from(event, assignee) : Task")
    s.call("er", "ag", "submit(task, deferred = verdict==HOLD)")
    s.call("ag", "ag", "mailbox.push(task, deferred)  ← ready 或 deferred 队列")

    s.divider("执行：工作线程取任务，跑 LLM ↔ 工具循环")

    s.call("tr", "ag", "pending() / pop() : Task")
    s.call("tr", "ag", "beginTask(task)   状态 → ON_DUTY_BUSY", ret=True)
    s.call("tr", "tl", "run(agent, systemPrompt, task, memory, day)")
    s.call("tl", "cm", "prepare(systemPrompt, taskDescription, day)", ret=True)

    s.frame_begin("loop  直到没有 tool_calls（ToolLoopPolicy 限轮次 / 令牌）")
    s.call("tl", "llm", "chatWithTools(messages, toolSpecs)")
    s.call("llm", "tl", "ToolReply(content, toolCalls, tokens)", ret=True)
    s.self_call("tl", "把 assistant 消息写进本轮 messages")
    s.frame_begin("alt  有 tool_calls")
    s.call("tl", "tb", "invoke(toolName, arguments)")
    s.call("tb", "tl", "ToolResult(text, error)   ← error 不抛异常，喂回模型", ret=True)
    s.frame_end(["tl", "tb"])
    s.frame_end(["tl", "llm"])

    s.call("tl", "cm", "commit(day, taskText, answer, llm)")
    s.call("tr", "ag", "finishTask(task)   状态 → ON_DUTY_IDLE")
    s.call("ag", "ag", "promoteDeferred()  ← deferred 里等着的任务此刻补位")

    s.note("关键性质：消息数组只在一处增长（ToolLoop 内部），对话记忆只在 commit 时落库；"
           "工具执行的全部副作用都从 Toolbox 这一个口子出去。", over=["tl", "cm"])

    s.render("seq-1-task-life")
