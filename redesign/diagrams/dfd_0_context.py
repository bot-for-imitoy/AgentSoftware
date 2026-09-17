"""数据流图 0 层：上下文图 —— 模拟器与外部世界的 6 条边界。"""

from dsl import Dot, dfd_entity, dfd_process, note, require_dot

F = dict(fontname="Source Han Sans CN", fontsize="9", color="#4b5563",
         fontcolor="#374151", penwidth="1.2", dir="forward", arrowhead="vee")


def build():
    d = Dot("dfd_0_context", rankdir="LR", nodesep="0.5", ranksep="1.5")

    d.node("sys", dfd_process("0", "软件公司模拟器",
                              "多角色 LLM Agent · 模拟班次 08:00–18:00"))

    d.node("e_client", dfd_entity("E1", "客户 / 用户", "Web GUI · 控制台"))
    d.node("e_llm", dfd_entity("E2", "LLM 服务", "OpenAI 兼容端点"))
    d.node("e_smtp", dfd_entity("E3", "SMTP 服务", "外发邮件"))
    d.node("e_pc", dfd_entity("E4", "个人电脑", "podman 容器 / 本地 / SSH"))
    d.node("e_conf", dfd_entity("E5", "角色与配置", "role_templates.json · providers.json"))
    d.node("e_disk", dfd_entity("E6", "磁盘存档", "state.json · 笔记 / 待办 / 技能 / 邮箱"))

    d.edge("e_client", "sys", dict(F, label=" 提问 / 回复 / 暂停·恢复 "))
    d.edge("sys", "e_client", dict(F, label=" 聊天流 · 公司状态 · 轨迹 "))

    d.edge("sys", "e_llm", dict(F, label=" system + 消息 + 工具声明 "))
    d.edge("e_llm", "sys", dict(F, label=" 文本 / tool_calls / tokens "))

    d.edge("sys", "e_smtp", dict(F, label=" 外发邮件 "))
    d.edge("e_smtp", "sys", dict(F, label=" 新邮件通知 "))

    d.edge("sys", "e_pc", dict(F, label=" 命令 / 写文件 "))
    d.edge("e_pc", "sys", dict(F, label=" stdout / 文件内容 "))

    d.edge("e_conf", "sys", dict(F, label=" 角色定义 · 模型端点 · MCP 规则 "))
    d.edge("sys", "e_disk", dict(F, label=" 快照 / 总结 / 笔记 "))
    d.edge("e_disk", "sys", dict(F, label=" 上次的队列 / 历史 / 对话 "))

    d.node("legend", note("说明",
                          ["整个模拟器是 1 个处理：Company。",
                           "它只在 6 条边界上与外界交换数据；",
                           "内部的 14 个子处理见 Level-1。",
                           "",
                           "E1 通过 web.ChatWebServer 进出",
                           "E2 通过 llm.OpenAiClient",
                           "E3 通过 tool.mail.SmtpSender",
                           "E4 通过 tool.computer.Shell 三实现",
                           "E5 通过 infra.config.ConfigLoader",
                           "E6 通过 company.store.JsonSnapshotStore"],
                          kind="neutral"))

    require_dot()
    d.render("dfd-0-context")
