"""DFD Level-1 之三：跨天收尾、快照、以及公司怎么被装配/复员（④ 跨天与人事）。"""

from dsl import Dot, dfd_entity, dfd_process, dfd_store, require_dot
from dfd_common import F, FD, FW, legend_node


def build():
    d = Dot("dfd_1c", rankdir="LR", nodesep="0.35", ranksep="0.9")

    # 装配（启动）
    d.node("e_conf", dfd_entity("E5", "角色与配置", "role_templates.json · providers.json"))
    d.node("p0", dfd_process("P0", "CompanyBuilder + ToolkitCatalog",
                             "唯一知道全部具体类的装配点"))
    d.node("p0b", dfd_process("P0.1", "Company", "组合根：start / stop / pause"))

    # 跨天
    d.node("p3", dfd_process("P3", "ShiftDirector", "onShiftEnd 边界"))
    d.node("p11", dfd_process("P11", "ConversationMemory", "closeDay"))
    d.node("d6", dfd_store("D6", "NoteBook", "当日总结"))
    d.node("d3", dfd_store("D3", "ConversationMemory", "当日对话"))

    # 快照
    d.node("p13", dfd_process("P13", "Company.save / restore", "整公司一份 JSON"))
    d.node("d5", dfd_store("D5", "state.json", "CompanySnapshot"))
    d.node("e_disk", dfd_entity("E4", "宿主磁盘", "角色目录 / workdir / state.json"))

    # 人事
    d.node("p14", dfd_process("P14", "Staffing / HiringService", "招聘 · 上岗 · 离职"))
    d.node("d8", dfd_store("D8", "Team / Agent", "花名册 · 状态 · 队列 · 历史"))
    d.node("d1", dfd_store("D1", "AgentMailbox", "ready / deferred"))
    d.node("d2", dfd_store("D2", "ScheduleTable", "定时提醒"))
    d.node("e_client", dfd_entity("E3", "客户 / 用户", "暂停 · 恢复 · 看状态"))

    # ---- 装配 ----
    d.edge("e_conf", "p0", dict(FW, label=" 角色定义 + 模型端点 "))
    d.edge("p0", "p0b", dict(FW, label=" new Company(...) "))
    d.edge("p0", "d8", dict(FW, label=" 注册全部 Agent "))
    d.edge("p0", "p14", dict(FW, label=" 注入 ShellRegistry / AgentFactory "))
    d.edge("p0", "d2", dict(F))

    # ---- 跨天 ----
    d.edge("p3", "p11", dict(FW, label=" onShiftEnd "))
    d.edge("p3", "d6", dict(FW, label=" 写当日总结 "))
    d.edge("p11", "d3", dict(F, label=" closeDay：压缩 + 归档 "))

    # ---- 快照 ----
    d.edge("d8", "p13", dict(FW, label=" 角色快照 "))
    d.edge("d1", "p13", dict(F, label=" 队列 "))
    d.edge("d3", "p13", dict(F, label=" 对话 "))
    d.edge("d2", "p13", dict(F, label=" 提醒 "))
    d.edge("p13", "d5", dict(FW, label=" save() "))
    d.edge("d5", "p13", dict(FW, label=" load() "))
    d.edge("d5", "e_disk", dict(F, label=" JSON 文件 "))
    d.edge("d6", "e_disk", dict(F))

    # ---- 人事与门 ----
    d.edge("p14", "d8", dict(FW, label=" hire / onboard / resign "))
    d.edge("p14", "d1", dict(F, label=" 新角色的队列 "))
    d.edge("d8", "p14", dict(FD, label=" 候选 / 花名册 "))
    d.edge("e_client", "p0b", dict(FW, label=" pause(reason) / resume() "))
    d.edge("p0b", "d8", dict(F, label=" 暂停门广播（等待中的角色挂起） "))

    legend_node(d, [
        "启动顺序：",
        "P0 造共享件 → P0.1 起线程 →",
        "P14 复原 / 招聘 → P3 上班。",
        "",
        "恢复语义：state.json 里的",
        "队列与历史重新灌回 D1 / D8，",
        "未完成任务不会丢。",
        "",
        "P0.1 的 pause 不影响正在",
        "跑的一轮工具调用，只在",
        "TaskRunner 取下一个任务时",
        "和 onShiftEnd 处生效。",
    ])

    require_dot()
    d.render("dfd-1c-lifecycle")
