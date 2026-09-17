"""UML 类图 4/4：工具层 —— 一个能力 = 一个包 = 一个 Toolkit。

上半张是 SPI 与装配链，下半张是 13 个工具包的目录表。
"""

from dsl import Dot, uml, note, table, comp, aggr, assoc, realize, depend, require_dot

# 包名, Toolkit 类, 构造入参（= 它能碰到的能力）, 它实现依赖的接口, 说明
TOOLKITS = [
    ("tool.computer", "PcToolkit",       "Shell",                                 "Shell",              "电脑：命令 / 文件 / 目录"),
    ("tool.computer", "HermesToolkit",   "Shell",                                 "Shell",              "电脑工具的另一套外壳"),
    ("tool.note",     "NoteToolkit",     "NoteBook, ReminderScheduler",           "JsonNoteBook 等",     "笔记 + 定时提醒"),
    ("tool.note",     "MemoryToolkit",   "NoteBook, Clock, AgentControl",         "JsonNoteBook / SimClock", "日记 / 总结 / 关机"),
    ("tool.todo",     "TodoToolkit",     "TodoList",                              "JsonTodoList",       "待办清单"),
    ("tool.skill",    "SkillToolkit",    "SkillLibrary",                         "JsonSkillLibrary",   "技能库"),
    ("tool.mail",     "EmailToolkit",    "Mailbox, AgentDirectory",               "FileMailbox",        "公司邮箱"),
    ("tool.talk",     "TalkToolkit",     "TeamChannel, AgentDirectory, Transcript", "TalkService",      "同事对话（可同步等回复）"),
    ("tool.client",   "ClientToolkit",   "ClientChannel, Transcript",            "Web / Console 两实现", "向客户提问"),
    ("tool.hr",       "HrToolkit",       "Recruiter",                            "HiringService",      "招聘 / 生成岗位"),
    ("tool.time",     "TimeToolkit",     "Clock",                                "SimClock",           "读时钟"),
    ("tool.task",     "TaskViewToolkit", "AgentTasks",                           "Agent 自身",          "看自己的任务队列"),
    ("tool.mcp",      "McpToolkit",      "McpBridge",                            "StdioMcpBridge",     "外部 MCP 工具"),
]


def build():
    d = Dot("uml_tools", rankdir="LR", nodesep="0.35", ranksep="0.95")

    # ---------------- SPI ----------------
    d.node("Toolkit", uml("Toolkit", "interface", kind="iface", tag="tool.spi · 插件 SPI",
                          methods=["+ id() : String",
                                   "+ instantiate() : List<Tool>"]))
    d.node("Tool", uml("Tool", "interface", kind="iface", tag="tool.spi",
                       methods=["+ spec() : ToolSpec",
                                "+ invoke(RoleId, Payload) : ToolResult"]))
    d.node("Toolbox", uml("Toolbox", "interface", kind="iface", tag="tool.spi",
                          methods=["+ specs() : List<ToolSpec>",
                                   "+ invoke(String, Payload) : ToolResult"]))
    d.node("ToolSpec", uml("ToolSpec", kind="domain", stereotype="record",
                           attrs=["name / description", "schema : JsonSchema"]))
    d.node("ToolResult", uml("ToolResult", kind="domain", stereotype="record",
                             attrs=["text / error"],
                             methods=["+ ok(String) / + error(String)"]))
    d.node("ToolboxFactory", uml("ToolboxFactory", "interface", kind="iface", tag="agent",
                                 methods=["+ create(RoleSpec, AgentTasks,",
                                          "        AgentControl) : Toolbox"]))

    # ---------------- 装配 ----------------
    d.node("ToolkitCatalog", uml("ToolkitCatalog", kind="boot",
                                 tag="bootstrap · 唯一知道全部具体工具包的地方",
                                 attrs=["- factories : Map<String, Function<Deps, Toolkit>>"],
                                 methods=["+ register(String, Function<Deps, Toolkit>)",
                                          "+ build(RoleSpec, Shell, AgentTasks,",
                                          "        AgentControl) : Toolbox",
                                          "record Deps(spec, shell, tasks, control)"]))
    d.node("Agent", uml("Agent", kind="agent", tag="agent",
                        attrs=["- toolkits : Set<String>   来自 RoleSpec",
                               "- toolbox : Toolbox  0..1"],
                        methods=["+ start()   此刻才装配",
                                 "+ submit / + promoteDeferred"]))
    d.node("RoleSpec", uml("RoleSpec", kind="domain", tag="agent.role",
                           attrs=["toolkits : Set<String>",
                                  "computer : ComputerSpec"]))
    d.node("ShellRegistry", uml("ShellRegistry", kind="tool", tag="tool.computer",
                                attrs=["- paths / - networkName"],
                                methods=["+ create(RoleSpec) : Shell"]))

    # ---------------- 工具包目录 ----------------
    d.node("catalog", table("tool.* —— 13 个工具包（一个包一个能力，各自 implements Toolkit）",
                            ["包", "Toolkit 实现", "构造入参（决定它能碰什么）", "被注入的实现", "能力"],
                            TOOLKITS, kind="tool"))

    # ---------------- Shell 家族 ----------------
    d.node("Shell", uml("Shell", "interface", kind="tool", tag="tool.computer",
                        methods=["+ powerOn() / + powerOff() / + poweredOn()",
                                 "+ run(String, Duration, int) : CommandResult",
                                 "+ readFile / + writeFile / + listDir / + deleteFile",
                                 "+ workdir() / driveRoot() / hostDir() / describe()"]))
    for cid, cls, note_ in [("local", "LocalShell", "本地目录"),
                            ("podman", "PodmanShell", "podman 容器"),
                            ("ssh", "SshShell", "远程主机")]:
        d.node(f"sh_{cid}", uml(cls, kind="tool", tag="tool.computer",
                                attrs=[f"ctor(RoleId, ComputerSpec, …)", note_]))

    # ---------------- 关系 ----------------
    depend(d, "Toolkit", "Tool", "instantiate() 产出一批 Tool")
    depend(d, "Tool", "ToolSpec")
    depend(d, "Tool", "ToolResult")
    depend(d, "Toolbox", "ToolSpec")
    depend(d, "Toolbox", "ToolResult")

    for cid in ("local", "podman", "ssh"):
        realize(d, f"sh_{cid}", "Shell")

    depend(d, "catalog", "Toolkit", "每一行都是它的实现", style="invis")
    assoc(d, "ToolkitCatalog", "Toolkit", "注册 13 个工厂 lambda")
    assoc(d, "ToolkitCatalog", "Shell", "构造期注入")
    assoc(d, "ToolkitCatalog", "RoleSpec", "按 spec.toolkits 过滤")
    realize(d, "Agent", "ToolboxFactory", "lambda: catalog::build")
    assoc(d, "Agent", "RoleSpec")
    assoc(d, "Agent", "Shell", "1")
    assoc(d, "Agent", "ToolkitCatalog", "start() 时回调")
    assoc(d, "ShellRegistry", "Shell", "按 ComputerSpec 造一个")

    d.node("legend", note("装配顺序（为什么可以没有环）",
                          ["1. ConfigLoader 读 role_templates.json",
                           "   → RoleSpec（含 toolkits 名单）",
                           "2. CompanyBuilder 造共享件：SimClock /",
                           "   Team / ShellRegistry / ScheduleTable …",
                           "3. Team.register(Agent)：Agent 拿到 spec +",
                           "   shell + AgentTasks / AgentControl 三个接口",
                           "4. Agent.start() → ToolboxFactory.create(...)",
                           "   → ToolkitCatalog.build(spec, shell, this, this)",
                           "5. catalog 按 spec.toolkits 逐个 new Toolkit，",
                           "   再 collect 它们的 Tool 拼成 Toolbox",
                           "",
                           "结果：agent 包不认识任何具体工具包；",
                           "tool.* 只认识 agent 的接口定义。",
                           "",
                           "■ 唯一反向边：ShellRegistry 需要",
                           "  RoleSpec.ComputerSpec（电脑规格写在角色",
                           "  定义里），已记在 PLAN §5.9-B.8"],
                          kind="neutral"))

    require_dot()
    d.render("uml-4-tools")
