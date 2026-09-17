"""DFD Level-1 之二：一个任务从出队到写回结果的完整数据流（③ 任务执行）。"""

from dsl import Dot, dfd_entity, dfd_process, dfd_store, require_dot
from dfd_common import F, FD, FW, legend_node


def build():
    d = Dot("dfd_1b", rankdir="LR", nodesep="0.35", ranksep="0.85")

    d.node("d1", dfd_store("D1", "AgentMailbox", "ready / deferred"))
    d.node("p7", dfd_process("P7", "TaskRunner", "出队 → 过 LifecycleGate → 收尾"))
    d.node("p10", dfd_process("P10", "SystemPrompt", "人设 + 时钟 + 昨日总结"))
    d.node("p8", dfd_process("P8", "ToolLoop", "LLM ↔ 工具循环，受 ToolLoopPolicy 限"))
    d.node("e_llm", dfd_entity("E2", "LLM 服务", "OpenAI 兼容端点"))
    d.node("p9", dfd_process("P9", "Toolbox", "按工具名分派到某个 Tool"))
    d.node("e_pc", dfd_entity("E5", "个人电脑", "podman / 本地 / SSH"))
    d.node("d6", dfd_store("D6", "笔记 / 待办 / 技能 / 提醒", "角色私有资料"))
    d.node("d7", dfd_store("D7", "Mailbox", "公司邮箱"))
    d.node("d8", dfd_store("D8", "Team / Agent", "同事 · 花名册"))
    d.node("e_client", dfd_entity("E3", "客户 / 用户", "ask_client"))
    d.node("p11", dfd_process("P11", "ConversationMemory", "prepare / commit / closeDay"))
    d.node("d3", dfd_store("D3", "ConversationMemory", "当日消息 + 压缩摘要"))
    d.node("p12", dfd_process("P12", "Transcript", "推理 / 工具 / 答案 / 群聊"))
    d.node("d4", dfd_store("D4", "Transcript", "Entry(seq, …)"))

    d.edge("d1", "p7", dict(FW, label=" Task（ready 队列，先到先做） "))
    d.edge("p7", "d8", dict(F, label=" 状态 / 当前任务 / 历史 "))
    d.edge("d6", "p10", dict(FD, label=" 昨日总结 "))
    d.edge("p10", "p8", dict(FW, label=" system prompt "))
    d.edge("p7", "p8", dict(FW, label=" 任务描述 + 本角色工具清单 "))
    d.edge("d3", "p8", dict(FD, label=" 历史消息 "))
    d.edge("p8", "e_llm", dict(FW, label=" 消息 + 工具声明 "))
    d.edge("e_llm", "p8", dict(FW, label=" 文本 / tool_calls / tokens "))
    d.edge("p8", "p9", dict(FW, label=" invoke(name, Payload) "))
    d.edge("p9", "p8", dict(FW, label=" ToolResult(ok | error) "))
    d.edge("p9", "e_pc", dict(F, label=" 命令 / 写文件 "))
    d.edge("e_pc", "p9", dict(F, label=" stdout / 文件内容 "))
    d.edge("p9", "d6", dict(F, label=" 记笔记 · 建待办 · 定提醒 "))
    d.edge("d6", "p9", dict(F, label=" 读笔记 / 技能正文 "))
    d.edge("p9", "d7", dict(F, label=" 发信 "))
    d.edge("d7", "p9", dict(F, label=" 收件箱 / 未读 "))
    d.edge("p9", "d8", dict(F, label=" talk / 招聘 / 查花名册 "))
    d.edge("d8", "p9", dict(F, label=" 同事回复 / 代理任务结果 "))
    d.edge("p9", "e_client", dict(F, label=" ask_client 提问 "))
    d.edge("e_client", "p9", dict(F, label=" 客户回复 "))
    d.edge("d3", "p11", dict(F, label=" 已有对话 "))
    d.edge("p8", "p11", dict(FW, label=" 本轮问答 "))
    d.edge("p11", "d3", dict(FW, label=" commit / closeDay "))
    d.edge("p7", "p12", dict(F, label=" 推理 / 答案 / 失败原因 "))
    d.edge("p9", "p12", dict(F, label=" 工具轨迹 "))
    d.edge("p12", "d4", dict(FW, label=" Entry(seq, kind, …) "))
    d.edge("d4", "e_client", dict(FD, label=" /api/feed?since=seq 增量拉取 "))
    d.edge("p7", "d1", dict(FD, label=" 出队 "))

    legend_node(d, [
        "P8 的循环最多跑",
        "ToolLoopPolicy.maxRounds 轮，",
        "超过就带失败结论收尾。",
        "",
        "工具返回 error 不抛异常，",
        "而是当成一条 tool 消息",
        "喂回模型 —— 让模型自己纠错。",
        "",
        "P9 是唯一的“外向”出口：",
        "电脑 / 邮箱 / 同事 / 客户",
        "四类副作用都从这里出。",
    ])

    require_dot()
    d.render("dfd-1b-execution")
