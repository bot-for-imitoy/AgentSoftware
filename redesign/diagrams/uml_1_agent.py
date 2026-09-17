"""UML 类图 1/4：Agent —— 每个角色一台“虚拟机”。

关注点：Agent 由哪些零件组成、它实现哪几个接口、一次任务由谁跑。
"""

from dsl import Dot, uml, note, comp, aggr, assoc, realize, depend, require_dot

PKG = [
    ("agent", "agent 包 · 角色运行时"),
    ("task", "agent.task · 任务与 LLM 循环"),
    ("dialog", "agent.dialog · 对话与提示词"),
    ("iface", "tool.spi / 跨包接口"),
    ("tool", "tool.* · 具体能力"),
    ("llm", "llm · 模型端点"),
    ("sim", "sim.clock · 只读时钟"),
]


def build():
    d = Dot("uml_agent", rankdir="LR", nodesep="0.35", ranksep="1.1")

    # ---------------- 接口 ----------------
    d.node("AgentTasks", uml("AgentTasks", "interface", kind="iface",
                             methods=["+pending() : List<Task>", "+history(int) : List<Task>"]))
    d.node("AgentControl", uml("AgentControl", "interface", kind="iface",
                               methods=["+transitionTo(AgentState)",
                                        "+closeDayConversation(int)",
                                        "+powerOffComputer()"]))
    d.node("Toolbox", uml("Toolbox", "interface", kind="iface",
                          methods=["+specs() : List<ToolSpec>",
                                   "+invoke(String, Payload) : ToolResult"]))
    d.node("ToolboxFactory", uml("ToolboxFactory", "interface", kind="iface",
                                 methods=["+create(RoleSpec, AgentTasks,",
                                          "        AgentControl) : Toolbox"]))
    d.node("LlmClient", uml("LlmClient", "interface", kind="llm",
                            methods=["+chat(ChatRequest) : ChatReply",
                                     "+chatWithTools(ToolChatRequest) : ToolReply",
                                     "+summarize(String, double, int) : ChatReply"]))
    d.node("Shell", uml("Shell", "interface", kind="tool",
                        tag="tool.computer",
                        methods=["+powerOn() / +powerOff()",
                                 "+run(String, Duration, int) : CommandResult",
                                 "+readFile / +writeFile / +listDir / +deleteFile",
                                 "+workdir() / +driveRoot() / +hostDir()"]))
    d.node("NoteBook", uml("NoteBook", "interface", kind="tool",
                           tag="tool.note",
                           methods=["+write(Note) / +edit(Note) / +read(...)",
                                    "+saveSummary(RoleId, int, String)"]))
    d.node("Clock", uml("Clock", "interface", kind="sim",
                        methods=["+now() : Tick", "+nowDay() : DayTick",
                                 "+calendar() : ShiftCalendar",
                                 "+currentDateTime() : String"]))

    # ---------------- Agent ----------------
    d.node("Agent", uml("Agent", kind="agent", tag="每个角色一份",
                        attrs=[
                            "- spec           : RoleSpec          角色定义（不可变）",
                            "- shell          : Shell             个人电脑",
                            "- mailbox        : AgentMailbox  ◆   ready / deferred 双队列",
                            "- state          : AgentStateMachine ◆ 状态唯一写者",
                            "- waits          : WaitCoordinator ◆ talk wait=true 等待协议",
                            "- conversation   : ConversationMemory ◆ 当日对话 + 压缩",
                            "- toolLoop       : ToolLoop      ◆  LLM ↔ 工具 循环",
                            "- prompts        : SystemPrompt  ◆  提示词装配",
                            "- toolboxFactory : ToolboxFactory    装配延迟到 start()",
                            "- toolbox        : Toolbox      0..1 start() 后才有",
                            "- gate           : LifecycleGate ▶  全局暂停门（共享，只读）",
                            "- history        : List<Task>        已结束任务",
                            "- current        : Task         0..1 执行中",
                            "- worker         : Thread       0..1 由 start() 创建",
                        ],
                        methods=[
                            "+ start() / stop() / isRunning()",
                            "+ submit(Task, boolean) / promoteDeferred()",
                            "+ state() / busy() / queueDepth()",
                            "+ beginTask(Task) / finishTask(Task) / snapshot()",
                            "+ pending() / history(int)",
                            "+ transitionTo(AgentState) / closeDayConversation(int) / powerOffComputer()",
                        ]))

    # ---------------- Agent 的零件 ----------------
    d.node("Mailbox", uml("AgentMailbox", kind="agent", tag="agent",
                          attrs=["- ready    : Deque<Task>",
                                 "- deferred : Deque<Task>"],
                          methods=["+ push(Task, boolean)", "+ peek() : Optional<Task>",
                                   "+ pop() : Optional<Task>", "+ promoteDeferred()",
                                   "+ readyDepth() / deferredDepth()"]))
    d.node("State", uml("AgentStateMachine", kind="agent", tag="agent",
                        attrs=["- state : AgentState"],
                        methods=["+ to(AgentState) / toIdle()",
                                 "+ toOffDuty() / toWaiting()",
                                 "+ restore(AgentState)"]))
    d.node("Waits", uml("WaitCoordinator", kind="agent", tag="agent",
                        attrs=["- waitingFor : RoleId",
                               "- reply : String"],
                        methods=["+ begin(RoleId)", "+ await(Duration)",
                                 "+ deliver(String)", "+ abort(String)",
                                 "+ end() / waiting() / waitingFor()"]))
    d.node("Memory", uml("ConversationMemory", kind="dialog", tag="agent.dialog",
                         attrs=["- policy : ConversationPolicy",
                                "- messages : List<Message>",
                                "- day / closedDay : int"],
                         methods=["+ prepare(String, String, int) : List<Message>",
                                  "+ commit(int, String, String, LlmClient)",
                                  "+ closeDay(int) / + snapshot() / + restore(State)"]))
    d.node("ConvPolicy", uml("ConversationPolicy", kind="domain", stereotype="record",
                             attrs=["maxHistoryChars", "maxSummaryChars",
                                    "toolRecapLimit"],
                             methods=["+ shouldCompact(long) : boolean",
                                      "+ keepMessages() : int"]))
    d.node("Prompts", uml("SystemPrompt", kind="dialog", tag="agent.dialog",
                          attrs=["- clock : Clock          ◀ sim.clock",
                                 "- summaries : DailySummary ◀ 本包窄端口",
                                 "  （实现是 tool.note.JsonNoteBook）"],
                          methods=["+ build(RoleSpec) : String"]))
    d.node("DailySummary", uml("DailySummary", "interface", kind="iface",
                               tag="agent.dialog · 只声明要用的那一个方法",
                               methods=["+ latestSummary(RoleId, int)",
                                        "    : Optional<String>"]))
    d.node("ToolLoop", uml("ToolLoop", kind="task", tag="agent.task",
                           attrs=["- llm        : LlmClient",
                                  "- toolbox    : Toolbox",
                                  "- transcript : Transcript",
                                  "- policy     : ToolLoopPolicy"],
                           methods=["+ run(RoleId, String, Task,",
                                    "      ConversationMemory, int) : Outcome",
                                    "record Outcome(answer, tokens, failed)"]))
    d.node("LoopPolicy", uml("ToolLoopPolicy", kind="domain", stereotype="record",
                             attrs=["maxRounds", "maxTotalTokens", "failOnLlmError"],
                             methods=["+ roundBudgetExceeded(int)",
                                      "+ tokenBudgetExceeded(int)"]))
    d.node("Runner", uml("TaskRunner", kind="task", tag="agent.task · Runnable",
                         attrs=["- mailbox : AgentMailbox",
                                "- tasks   : AgentTasks",
                                "- control : AgentControl",
                                "- toolLoop / -transcript / -gate"],
                         methods=["+ run()", "+ requestStop()",
                                  "（不认识 agent.Agent）"]))
    d.node("Task", uml("Task", kind="domain", tag="agent.task",
                       attrs=["id / urgency / description",
                              "source / context / createdAt",
                              "assignee  不可变",
                              "status / result / tokens  可变"],
                       methods=["+ fromEvent(AgentEvent, RoleId)",
                                "+ markRunning() / complete(String,int) / fail(String)",
                                "+ toRecord() / fromRecord(TaskRecord)"]))
    d.node("Gate", uml("LifecycleGate", kind="agent", tag="agent · 共享单例",
                       attrs=["- paused : boolean", "- reason : String"],
                       methods=["+ pause(String) / resume()",
                                "+ paused() / reason()",
                                "+ awaitRunning(Duration)"]))
    d.node("StateEnum", uml("AgentState", kind="domain", stereotype="enum", tag="agent",
                            methods=["OFF_DUTY · ON_DUTY_IDLE · ON_DUTY_BUSY",
                                     "WRAPPING_UP · WAITING",
                                     "+ onDuty() / holdsOrdinaryWork()",
                                     "+ acceptsEmergency()"]))
    d.node("RoleSpec", uml("RoleSpec", kind="domain", tag="agent.role · 不可变的角色定义",
                           attrs=["id / name / username / uid / title",
                                  "responsibilities / personality / skills",
                                  "group / email / promptExtra",
                                  "interestKeywords / salienceThreshold",
                                  "computer : ComputerSpec",
                                  "toolkits : Set<String>"],
                           methods=["+ builder() : Builder"]))

    # ---------------- 关系 ----------------
    realize(d, "Agent", "AgentTasks")
    realize(d, "Agent", "AgentControl")

    comp(d, "Agent", "Mailbox", "1")
    comp(d, "Agent", "State", "1")
    comp(d, "Agent", "Waits", "1")
    comp(d, "Agent", "Memory", "1")
    comp(d, "Agent", "ToolLoop", "1")
    comp(d, "Agent", "Prompts", "1")
    aggr(d, "Agent", "Task", "history / current")
    assoc(d, "Agent", "Toolbox", "toolbox 0..1")
    assoc(d, "Agent", "Shell", "shell 1")
    assoc(d, "Agent", "Gate", "gate（共享，只读）")
    assoc(d, "Agent", "ToolboxFactory", "start() 时装配")
    assoc(d, "Agent", "Runner", "new Thread(new TaskRunner)")

    comp(d, "Memory", "ConvPolicy")
    comp(d, "ToolLoop", "LoopPolicy")
    comp(d, "State", "StateEnum")
    aggr(d, "Mailbox", "Task", "ready / deferred 存 Task")
    assoc(d, "Agent", "RoleSpec", "spec 1（不可变）")
    assoc(d, "ToolLoop", "LlmClient")
    assoc(d, "ToolLoop", "Toolbox")
    assoc(d, "ToolLoop", "Task", "入参")
    depend(d, "ToolLoop", "Memory", "作为入参传入")
    assoc(d, "Runner", "ToolLoop")
    assoc(d, "Runner", "Gate")
    assoc(d, "Runner", "Task", "从队列取")
    assoc(d, "Waits", "Task", "代理出去的任务")
    depend(d, "Prompts", "Clock", "读当前时间")
    comp(d, "Prompts", "DailySummary", "读昨天")
    realize(d, "NoteBook", "DailySummary", "tool.note.JsonNoteBook 实现")
    depend(d, "Prompts", "RoleSpec", "build(spec)")
    depend(d, "Memory", "LlmClient", "压缩时 summarize")
    depend(d, "ToolboxFactory", "RoleSpec", "按 spec.toolkits 装配")

    # 图例
    d.node("legend", note("图例 · 颜色 = 包",
                          ["绿 = agent        靛 = agent.task",
                           "青 = agent.dialog   黄 = 值对象 / record",
                           "蓝 = tool.spi、跨包接口",
                           "灰 = tool.*        橙 = llm",
                           "（NoteBook 在 tool.note，仅作对照）",
                           "紫 = sim.clock",
                           "◆ 组合   ◇ 聚合   ▶ 关联",
                           "┄▷ 实现   ┄▶ 依赖"],
                          kind="neutral"))

    require_dot()
    d.render("uml-1-agent")
