"""UML 类图 3/4：公司编排 —— 花名册、人事、装配、Web。

这张图回答：“谁把零件拼起来、谁持有谁、启动顺序是什么”。
"""

from dsl import Dot, uml, note, comp, aggr, assoc, realize, depend, require_dot


def build():
    d = Dot("uml_company", rankdir="TB", nodesep="0.42", ranksep="0.8")

    # ---------------- 公司 ----------------
    d.node("CompanyView", uml("CompanyView", "interface", kind="iface",
                              methods=["+ status() : CompanyStatus",
                                       "+ roster() : List<RoleSpec>",
                                       "+ pause(String) / + resume()"]))
    d.node("Company", uml("Company", kind="company", tag="company · 唯一的组合根",
                          attrs=["- team      : Team             花名册",
                                 "- clock     : SimClock         模拟时钟",
                                 "- driver    : ClockDriver      时钟线程",
                                 "- router    : EventRouter      事件入口",
                                 "- schedule  : ScheduleTable    定时提醒",
                                 "- director  : ShiftDirector    班次边界",
                                 "- gate      : LifecycleGate    暂停门",
                                 "- staffing  : Staffing         人事",
                                 "- snapshots : SnapshotStore    存档"],
                          methods=["+ start() / stop()",
                                   "+ publish(AgentEvent)        外部事件入口",
                                   "+ save() / restore()         快照",
                                   "+ paused() / pauseReason() / pause(String) / resume()",
                                   "+ clock() / team() / schedule()",
                                   "+ status() / roster()"]))
    d.node("CompanyStatus", uml("CompanyStatus", kind="domain", stereotype="record",
                                attrs=["clock : DayTick   dateTime / describe",
                                       "paused / pauseReason",
                                       "agents : List<AgentSnapshot>"]))
    d.node("SnapshotStore", uml("SnapshotStore", "interface", kind="company",
                                methods=["+ load() : Optional<CompanySnapshot>",
                                         "+ save(CompanySnapshot)"]))
    d.node("JsonSnapshotStore", uml("JsonSnapshotStore", kind="company",
                                    tag="company.store",
                                    attrs=["- paths : AppPaths", "- json : JsonCodec"]))
    d.node("CompanySnapshot", uml("CompanySnapshot", kind="domain", stereotype="record",
                                  attrs=["version / savedAt",
                                         "clock : DayTick   baseDate : LocalDate",
                                         "roles : List<RoleSnapshot>"]),
           )

    # ---------------- 只在第 2 张图展开的共享件 ----------------
    for nid, label, kind in [
        ("SimClock", "SimClock", "sim"),
        ("ClockDriver", "ClockDriver", "sim"),
        ("EventRouter", "EventRouter", "task"),
        ("ScheduleTable", "ScheduleTable", "sim"),
        ("ShiftDirector", "ShiftDirector", "company"),
        ("LifecycleGate", "LifecycleGate", "agent"),
    ]:
        d.node(nid, uml(label, kind=kind, tag="◀ 见第 2 张图"))

    # ---------------- 花名册与人事 ----------------
    d.node("AgentDirectory", uml("AgentDirectory", "interface", kind="iface",
                                 methods=["+ spec(RoleId) : Optional<RoleSpec>",
                                          "+ specs() : List<RoleSpec>",
                                          "+ stateOf(RoleId) : Optional<AgentState>"]))
    d.node("Team", uml("Team", kind="agent", tag="agent · 无依赖",
                       attrs=["- byId : Map<RoleId, Agent>"],
                       methods=["+ register(Agent) / + resign(RoleId)",
                                "+ agent(RoleId) / + agents()",
                                "+ startAll() / stopAll()",
                                "+ snapshots() : List<AgentSnapshot>",
                                "+ anyBusy() / allIdle() / allOffDuty()   ◀ Sensors"]))
    d.node("Agent", uml("Agent", kind="agent", tag="agent（详见第 1 张图）",
                        attrs=["spec / shell / mailbox / state …"],
                        methods=["+ start() / stop() / submit(Task, boolean)",
                                 "+ snapshot() : AgentSnapshot"]))
    d.node("AgentSnapshot", uml("AgentSnapshot", kind="domain", stereotype="record",
                                attrs=["id / name / state / busy",
                                       "queueDepth / currentTask"]))
    d.node("AgentFactory", uml("AgentFactory", "interface", kind="iface",
                               methods=["+ create(RoleSpec) : Agent"],
                               tag="bootstrap 提供 lambda"))
    d.node("Staffing", uml("Staffing", kind="agent", tag="agent · 人事",
                           attrs=["- team    : Team",
                                  "- factory : AgentFactory"],
                           methods=["+ hire(RoleSpec) : Agent",
                                    "+ onboard(RoleSpec) : Agent",
                                    "+ resign(RoleId) : boolean",
                                    "+ restore(List<RoleSnapshot>) : int"]))
    d.node("RoleSpec", uml("RoleSpec", kind="domain", tag="agent.role · 不可变",
                           attrs=["id / name / username / uid / title",
                                  "responsibilities / personality / skills",
                                  "group / email / promptExtra",
                                  "interestKeywords / salienceThreshold",
                                  "computer : ComputerSpec",
                                  "toolkits : Set<String>"],
                           methods=["+ builder() : Builder",
                                    "+ hasGroup() : boolean"]))
    d.node("RoleSnapshot", uml("RoleSnapshot", kind="domain", stereotype="record", tag="agent",
                               attrs=["spec : RoleSpec",
                                      "state : AgentState",
                                      "ready / deferred 队列",
                                      "history / conversation 状态"]))

    # ---------------- 装配与 Web ----------------
    d.node("CompanyBuilder", uml("CompanyBuilder", kind="boot", tag="bootstrap",
                                 attrs=["- config / paths / json",
                                        "- llmOverride / clientChannel / transcript"],
                                 methods=["+ withLlm(LlmClient)",
                                          "+ withClientChannel(ClientChannel)",
                                          "+ withTranscript(Transcript.Feed)",
                                          "+ build() : Company"]))
    d.node("ToolkitCatalog", uml("ToolkitCatalog", kind="boot", tag="bootstrap",
                                 attrs=["- factories : Map<String, Function<Deps, Toolkit>>"],
                                 methods=["+ register(String, Function<Deps, Toolkit>)",
                                          "+ build(RoleSpec, Shell, AgentTasks,",
                                          "        AgentControl) : Toolbox",
                                          "record Deps(spec, shell, tasks, control)"]))
    d.node("ShellRegistry", uml("ShellRegistry", kind="tool", tag="tool.computer",
                                attrs=["- paths : AppPaths", "- networkName : String"],
                                methods=["+ create(RoleSpec) : Shell",
                                         "+ destroy(RoleId) / + all() : List<Shell>"]))
    d.node("Main", uml("Main", kind="boot", tag="bootstrap",
                       methods=["+ main(String[])"]))
    d.node("ChatFeed", uml("ChatFeed", kind="web", tag="transcript · 实现 Feed",
                           methods=["+ reasoning / + note / + toolCall / + answer",
                                    "+ talk(Talk) / + client(Client) / + system(String)",
                                    "+ since(long) : List<Entry>",
                                    "+ watermark() : long"]))
    d.node("Transcript", uml("Transcript", "interface", kind="web",
                             methods=["+ reasoning(RoleId, String, TraceMeta)",
                                      "+ toolCall(RoleId, String, String, String, TraceMeta)",
                                      "+ answer(RoleId, String, boolean, int, TraceMeta)",
                                      "+ talk(Talk) / + client(Client) / + system(String)",
                                      "interface Feed extends Transcript",
                                      "  + since(long) : List<Entry>"]))
    d.node("ChatWebServer", uml("ChatWebServer", kind="web", tag="web · JDK HttpServer",
                                attrs=["- view : CompanyView",
                                       "- feed : Transcript.Feed",
                                       "- host / port"],
                                methods=["+ start() / stop()",
                                         "+ port() / host()"]))

    # ---------------- 关系 ----------------
    realize(d, "Company", "CompanyView")
    comp(d, "Company", "Team", "1")
    comp(d, "Company", "SimClock", "1")
    comp(d, "Company", "ClockDriver", "1")
    comp(d, "Company", "EventRouter", "1")
    comp(d, "Company", "ScheduleTable", "1")
    comp(d, "Company", "ShiftDirector", "1")
    comp(d, "Company", "LifecycleGate", "1")
    comp(d, "Company", "Staffing", "1")
    comp(d, "Company", "SnapshotStore", "1")
    depend(d, "Company", "CompanyStatus", "status()")
    realize(d, "JsonSnapshotStore", "SnapshotStore")
    assoc(d, "SnapshotStore", "CompanySnapshot")
    comp(d, "CompanySnapshot", "RoleSnapshot")

    realize(d, "Team", "AgentDirectory")
    aggr(d, "Team", "Agent", "*")
    assoc(d, "Team", "AgentSnapshot", "snapshots()")
    assoc(d, "Agent", "AgentSnapshot", "snapshot()")
    aggr(d, "Staffing", "Team")
    assoc(d, "Staffing", "AgentFactory", "创建 Agent")
    depend(d, "AgentFactory", "Agent")
    depend(d, "AgentFactory", "RoleSpec")
    comp(d, "RoleSnapshot", "RoleSpec")
    depend(d, "Team", "RoleSpec", "specs()")

    assoc(d, "CompanyBuilder", "Company", "build()")
    assoc(d, "CompanyBuilder", "ToolkitCatalog", "注册所有工具包")
    assoc(d, "ToolkitCatalog", "ShellRegistry")
    depend(d, "ToolkitCatalog", "RoleSpec", "按 spec.toolkits 选包")
    assoc(d, "Main", "CompanyBuilder", "装配入口")
    realize(d, "ChatFeed", "Transcript", "Transcript.Feed")
    assoc(d, "ChatWebServer", "Transcript")
    depend(d, "ChatWebServer", "CompanyView", "/api/state")

    d.node("legend", note("图例 · 颜色 = 包",
                          ["青 = company        绿 = agent",
                           "紫 = bootstrap      灰 = tool.computer",
                           "浅绿 = transcript / web",
                           "蓝 = 接口           黄 = record",
                           "◆ 组合  ◇ 聚合  ▶ 关联",
                           "┄▷ 实现  ┄▶ 依赖",
                           "",
                           "Company 是唯一组合根：",
                           "所有共享件在这里被 new 出来"],
                          kind="neutral"))

    require_dot()
    d.render("uml-3-company")
