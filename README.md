# Shift & Event-Driven Agent Scheduler

> **当前分支 `java`: 本分支为 Java 重写版** (Maven + JUnit 5)。
> Python 原版保存在 `master` 分支。架构、作息规则、工具语义与 Python 版完全一致,
> 模块一一对应 (见下方「Java 重写说明」)。

> 项目还在开发中，按照 DeepSeek 的说法运行起来是没有问题的。但没有进行广泛测试。
> 项目基于 Hermes + DeepSeek 开发，会优先适配 DeepSeek。现阶段还在搭框架与测试的早期阶段，后续会逐步建立并完善各模块。
> 下面的部分是 Hermes 写的。其中带引用部分是作者补充的

---

## Java 重写说明 (java 分支)

### 构建与测试

```bash
# 前置: JDK 25+, Maven 3.8+ (OpenAI API Key: export OPENAI_API_KEY=sk-...)
mvn compile          # 编译
mvn test             # 运行全部 JUnit 测试 (84 个用例)
mvn package          # 打包 target/agent-company.jar
```

### 运行入口

```bash
mvn exec:java -Dexec.mainClass=com.agent.software.Main          # 主入口: 多日循环
mvn exec:java -Dexec.mainClass=demo.com.agent.software.RoleDemo # 单角色演示
mvn exec:java -Dexec.mainClass=demo.com.agent.software.TalkDemo # talk 协作链演示
mvn exec:java -Dexec.mainClass=demo.com.agent.software.McpDemo  # MCP 工具演示
```

### Python → Java 模块映射

| Python 模块 | Java 类 | 说明 |
|---|---|---|
| `core/types.py` | `core/Types.java` | Event / AgentState / Priority |
| `core/event_bus.py` | `core/EventBus.java` | 定时事件调度表 |
| `core/time_manager.py` | `core/TimeEventBus.java` | 作息时间引擎 + 事件总线 (含 ScheduledTask) |
| `core/dispatcher.py` | `core/EventDispatcher.java` | 事件广播 + 3 层过滤 |
| `core/tools.py` | `core/ToolRegistry.java` | ToolDef / ToolKit / ToolRegistry |
| `core/roles.py` | `core/AgentRole.java` + `core/RolePool.java` | 角色系统 (含 Task / Urgency / ToolLoopError) |
| `core/agent_system.py` | `core/AgentSystem.java` | 统一管理 TimeEventBus + RolePool |
| `core/llm.py` | `core/LLM.java` + `OpenAICompatLLM.java` | OpenAI 兼容客户端 (Java HttpClient), 只读 OPENAI_* 环境变量 |
| `core/computer.py` | `core/Computer.java` + `PodmanComputer.java` + `SSHComputer.java` + `ComputerManager.java` | 个人电脑体系 |
| `core/mcp_client.py` | `core/MCPServer.java` | MCP stdio JSON-RPC 客户端 (newline-delimited) |
| `core/note_store.py` | `core/NoteStore.java` | 笔记 + 每日总结 |
| `core/todo_store.py` | `core/TodoStore.java` | 个人待办 |
| `core/state_store.py` | `core/StateStore.java` | 全量状态持久化 (data/state.json) |
| `core/mail_service.py` | `core/MailService.java` | 公司邮件 (虚拟 / SMTP via jakarta.mail) |
| `core/role_templates.py` | `core/RoleLoader.java` | 55 个角色模板 (JSON 描述: `src/main/resources/role_templates.json`) |
| `core/role_factory.py` | `core/RoleFactory.java` | LLM 驱动招聘 |
| `core/pinyin_map.py` | — (已并入 `role_templates.json` 的 `username` 字段) | 中文名 → 拼音用户名 |
| `core/path_manager.py` | `core/PathManager.java` | 跨平台路径 |
| `core/config_store.py` | `core/ConfigStore.java` | JSON 配置 (点号路径) |
| `python_tools/*.py` | `tools/toolkits/**` (模板风格: 每域一个 Toolkit + 每函数一个 Tool) | 全部工具类 (memory/note/time/todo/task_view/pc/mcp_manager/skill/email/talk/hr/client/hermes), 见 §8.1 |
| `main.py` | `Main.java` | 主入口 |
| `role_demo.py` / `talk_demo.py` / `mcp_demo.py` | `demo/RoleDemo.java` / `TalkDemo.java` / `McpDemo.java` | 演示 |
| `tests/*.py` | `src/test/java/**` (JUnit 5) | 核心测试移植 (84 用例) |

### 与 Python 版的差异说明

- 构建/测试用 Maven (`pom.xml`), 依赖: Jackson (JSON)、slf4j (日志)、jakarta.mail (SMTP)。目标 JDK 25 (`maven.compiler.release=25`)。
- 多线程全部使用 **Java 21+ 虚拟线程**: 角色 worker (`RolePool`)、角色并行装配 (`AgentSystem.addRoles`)、电脑并行恢复 (`StateStore.restoreComputers`) 均改为虚拟线程执行器; 需要限流处 (装配/恢复) 用 `Semaphore` 保持原并发上限语义。每个角色一个常驻虚拟线程, 不再受固定线程池 `max_workers` 约束。
- LLM 请求用 JDK `java.net.http.HttpClient`, 重试语义 (429/5xx/超时重试, 4xx 立即失败) 与 Python 版一致。
- MCP 客户端自行实现 newline-delimited JSON-RPC 2.0 走 stdio (不依赖 MCP SDK), 支持 `npx -y <包>` 与自定义命令 (容器内 `podman exec -i`)。
- 环境变量覆盖路径解析同时支持系统属性 (`-DAGENTSCHEDULER_DATA_DIR=...` 等), 便于测试/容器注入。
- **LLM 配置统一分层解析**: 构造器显式参数 &gt; Java 参数 (`-D` 系统属性) &gt; 环境变量 &gt;
  配置文件 (ConfigStore, 点号键 `llm.api_key` / `llm.base_url` / `llm.model`)
  &gt; 默认值; 各来源键名一致 (见下方环境变量表)。不再区分后端 provider, 统一走
  OpenAI 兼容接口 (`OpenAICompatLLM`), 只读取 OpenAI 格式环境变量
  (`OPENAI_API_KEY` / `OPENAI_BASE_URL` / `OPENAI_MODEL`)。
- 角色模板 JSON 化: 55 个角色模板的内容不再写死在 Java 里, 统一由 `src/main/resources/role_templates.json`
  (顶层 `role_id → 角色配置` 映射) 描述; `RoleLoader` 类加载时自动载入注册表。
  另提供 `RoleLoader.fromJsonMap / loadFromJson / templatesFromJson / registerFromJson / toJsonMap`
  等方法, 从任意 JSON (字符串/文件, 支持映射/数组/单对象三种形态) 加载或导出 `AgentRole` 角色对象
  (测试见 `RoleLoaderJsonTest`)。原 `PinyinMap` 已删除: 各角色的拼音用户名直接写入 JSON 的
  `username` 字段, 未显式给出时回退 `role_id`。

---

基于**企业作息与事件驱动**理念的多角色 AI Agent 调度框架。

打破传统 Agent `while(true)` 循环，解决"长任务 Context 爆炸、状态不可恢复、Token 成本失控、权限无隔离"问题。

每个角色拥有**一台属于自己的个人电脑**（Podman 容器，同一自定义桥接网络内互通），
文件、任务、笔记、MCP 工具全部落在自己电脑上，权限天然隔离。

---

## 架构概览

```
┌──────────────────────────────────────────────────────────┐
│        TimeEventBus (时间 × 事件 深度绑定)                │
│   (2026-08 起 TimeManager 已并入 EventBus, 统一此名)      │
│   - 时钟/Tick/天 (1 Tick = 10 分钟换算, 上班 0~60 Tick)    │
│   - 3 层过滤管线 (状态掩码 → 显著性 → 唤醒)               │
│   - 事件调度表: register_event(ev, tick) 定时触发          │
│   - Tick 事件驱动: 全角色空闲才快进 (有任务跳任务, 没任务跳下班/次日上班) │
└──────────────┬───────────────────────────────────────────┘
               │  SHIFT_START/SHIFT_END/TASK_DUE 事件
               ▼
┌──────────────────────────────────────────────────────────┐
│              EventDispatcher (事件分发器)                  │
│   trigger(event) → fan-out to ALL roles                  │
│   Each role runs Layer 1-2-3 filter independently        │
└──────────────┬───────────────────────────────────────────┘
               │  PASS events become Tasks
               ▼
┌──────────────────────────────────────────────────────────┐
│              RolePool (角色线程池)                         │
│   ThreadPoolExecutor — 每个角色独立线程                   │
│   Priority Queue (heapq) — CRITICAL > HIGH > NORMAL      │
└──────────────┬───────────────────────────────────────────┘
               │  Task execution (原生 function calling)
               ▼
┌──────────────────────────────────────────────────────────┐
│      AgentRole + 个人电脑 + MCP 工具 + talk               │
│   LLM(Task) → tool_calls → execute → role:tool 回喂       │
│   - 个人电脑: podman 容器 maf-<role> (Ubuntu 24.04, 上班开机/下班关机)  │
│     容器内用户名 = 员工名字拼音 (guoxiaodong), 每员工独立 uid           │
│     自带 sudo/git/node/python + Hermes Agent (hermes_new_conversation)  │
│   - 企业云盘: 共享文件夹挂载 /mnt/drive (Public 777 + 员工目录 755) │
│   talk: inter-role communication                          │
└──────────────────────────────────────────────────────────┘
```

---

## 核心功能

### 1. TimeEventBus — 时间与事件深度绑定 (`src/core/time_manager.py`)

`TimeManager` 已合并进 `EventBus`（`TimeEventBus(EventBus)` 子类），既是时间源又是事件总线：

- **统一注册入口** `register_event(event, tick=None)`：
  - `tick=None` → 立即投递（进 3 层过滤管线）
  - `tick=N` → 存入事件调度表，时间线程到点自动投递
- 作息事件自动触发：每天 Tick 0 → `SHIFT_START`（上班），Tick 60 → `SHIFT_END`（下班）
- **Tick 事件驱动（不随真实时间流逝）**：角色忙碌期间 Tick 冻结（LLM 在 1 Tick 内跑完内容，不会因处理耗时错过未来 Tick 的任务）；全部角色空闲持续 60s 才快进——有任务跳任务 Tick，没任务跳当天下班，已下班跳次日上班
- 笔记与定时任务统一（统称笔记）：`write_note` 填 `remind_tick` = 带提醒的笔记，到点像任务一样发送提醒事件；底层 `schedule_task` 只保存任务列表；当天任务直接注册事件，隔天任务目标天上班时自动加载
- 兼容别名 `TimeManager` 已于 2026-08 移除（commit `2953835`）——统一使用 `TimeEventBus`

### 2. 事件 3 层过滤 (`src/core/roles.py` — `AgentRole.evaluate_event`)

0 Token 消耗拦截低价值事件。**过滤是每角色独立的**（角色差异化：各自的
`interest_keywords`/`skills`/状态），由 `EventDispatcher.trigger` 分发时调用；
`EventBus` 只做定时事件调度表，不含过滤管线（2026-08 收敛）。

| 层 | 名称 | 机制 | Token |
|----|------|------|-------|
| Layer 1 | State Mask | OFF_DUTY 状态拦截非 EMERGENCY 事件 | 0 |
| Layer 2 | Salience Evaluator | 角色关键词命中 + 优先级加权（`priority*0.4 + relevance*0.6`） | 0 |
| Layer 3 | Wake | 通过前两层的事件转 Task 入该角色队列 | 按需 |

系统时间事件（`source="time"`，如 SHIFT_START/END）绕过 Layer 2 直接通过。

> 这部分后续可能会训练一个小模型来完成过滤，目前训练貌似没什么价值。

### 3. 快进功能 (`src/core/time_manager.py`)

真实时间模式下不必干等：全部角色空闲（无任务处理/排队）持续 **1 分钟**后，
时钟自动跳到下一个事件 Tick（定时事件 / 定时任务 / 下班；已下班则跳到次日上班）。
`set_idle_checker` / `set_fast_forward(enabled, idle_seconds)` 可配。

### 4. 多角色并发任务调度 (`src/core/roles.py`)

- 每个角色独立线程 + 独立锁 + 独立 LLM 实例
- 优先级任务队列（heapq）：CRITICAL(10) > HIGH(6) > NORMAL(3) > LOW(1)
- `RolePool.add_role_and_start()` 动态入职（HR 招聘即上岗）
- `RolePool.remove_role()` 离职（自动关机 + 移出团队）

### 5. 原生 function calling (`src/core/llm.py` + `src/core/roles.py`)

- 请求带 `tools` 声明 + `tool_choice:"auto"`，判定靠响应 `message.tool_calls` 结构化字段
- 工具结果以 `role:"tool"` + `tool_call_id` 回喂
- 循环有保护上限：最多 20 轮工具调用 / 单任务累计 200K tokens，超限任务标记 failed
  （防止 LLM 陷入反复调工具的退化循环无限烧 Token）；API 超时/错误文本
  （`[API timeout]` / `[API error: ...]`）同样判失败，不当成功结果
- `max_tokens` 默认无上限（长内容 JSON 不被截断成非法 JSON；`None` 时不传该字段）
- 文本协议（```tool_call 块 + `_parse_tool_calls` 正则）已随 commit `2953835` 删除，只有原生 function calling

### 6. 个人电脑体系 (`src/core/computer.py`)

每个角色一台独立电脑，默认 **Podman 容器**（镜像 `maf-base:latest`，名 `maf-<role_id>`）：

- 容器挂载宿主机目录 `data/computers/<role>` ↔ 容器内 `/home/agent`（同一份文件，双向可见）
- 默认镜像 `maf-base:latest` 由项目根 `Containerfile` 定义（阿里源 / apt 标配包 / Hermes / MCP 服务器一次构建）；
  初始化电脑时若镜像不存在自动 `podman build` 创建，角色容器从该镜像复制，秒建只补员工用户
- **上班自动开机**（SHIFT_START）、**下班自动关机**（summary 总结后）
- 同一自定义桥接网络 `maf-net`：电脑间可互相通信（`lan_devices` 工具查人名/电脑名/IP）
- `ComputerManager`（全局单例）管理分配/销毁
- **Podman 是硬要求**：本机无 podman 时 `PodmanComputer` 构造直接抛 `RuntimeError`
  （commit `2953835` 移除了自动降级）——需要本地模拟请显式设 `computer_kind="local"`
- 另有 `SSHComputer`（远程主机，需显式指定 host）
- **跨天自动重连 MCP 服务器**：每天下班 `podman stop` 会杀死容器内 MCP 服务器的
  stdio 管道，次日上班开机时自动探测会话存活并重建（否则第 2 天起文件工具全失效）

### 7. MCP 工具 — 服务器跑在角色电脑容器内

- **每台电脑一个独立 MCP filesystem 服务器**，通过 `podman exec -i` 在容器内启动，
  授权目录 = `/home/agent`（与 LLM 看到的工作目录字面一致，无路径空间错位）
- 基础镜像（`Containerfile`）构建时已全局预装 `@modelcontextprotocol/server-filesystem`，容器秒启即用（旧容器由 `_ensure_container` 兜底补装）
- `DEFAULT_MCP_GROUPS = ("file_ops",)`：角色加入/新入职时自动把文件操作工具装到个人电脑
- `MCPManager`（全局共享）：`mcp_search/mcp_list/mcp_add/mcp_remove/mcp_my_tools`
  供 LLM 自助安装其它工具组的工具

### 8. 默认工具（`src/python_tools/`）

| 工具类 | 工具 | 说明 |
|--------|------|------|
| memory | summary / write_note / edit_note / list_notes / read_note | 记忆 + 下班总结（自动关机） |
| time | get_time / take_rest | 作息 |
| task | create_task / list_tasks / edit_task / delete_task | 定时任务（Tick 提醒，持久化到电脑 tasks/） |
| computer | run_command / computer_status / lan_devices / reboot | 个人电脑操作 |
| mcp_manager | mcp_search / mcp_list / mcp_add / mcp_remove / mcp_my_tools | MCP 自助管理 |
| talk / list_roles | 角色间通信（仅同组成员可互发，跨组走邮件） | pool.start() 自动注入 |
| email | send_email / read_mail / open_mail / mail_address_book | 员工邮件（虚拟邮箱，配 SMTP 后真实发送） |
| MCP file_ops | read_file / write_file / edit_file / ... | 默认 MCP 组，自动安装到电脑 |

专属工具：CEO 有 `talk_to_client`（甲方交流），HR 有 `post_job_posting` / `list_candidates`。

### 8.1 模板风格工具类（Java 版 `src/main/java/com/agent/software/tools/toolkits/`）

Java 版默认装配已切换到模板风格实现：每个业务域一个 `Toolkit` 子类 + 每个函数一个
`Tool` 子类（以 `toolkits/note/WriteNote` 为模板）：

| Toolkit (类) | 工具 | 说明 |
|--------|------|------|
| `toolkits.memory.Memory` | `summary` | **只含记忆相关内容**（每日总结，下一天自动注入提示词 + 下班关机） |
| `toolkits.note.Note` | `write_note` / `edit_note` / `list_notes` / `read_note` / `delete_note` | 笔记（**已从 memory 分离**；笔记与定时提醒统一） |
| `toolkits.time.Time` | `get_time` / `take_rest` | 作息 |
| `toolkits.todo.Todo` | `todo_add` / `todo_list` / `todo_update` / `todo_delete` | 待办清单 |
| `toolkits.taskview.TaskView` | `my_tasks` | 任务队列 + 历史 |
| `toolkits.pc.Pc` | `run_command` / `computer_status` / `lan_devices` / `reboot` | **pc = computer 工具**（个人电脑操作） |
| `toolkits.mcp.McpManager` | `mcp_search` / `mcp_list` / `mcp_add` / `mcp_remove` / `mcp_my_tools` | MCP 自助管理 |
| `toolkits.skill.Skill` | `skill_search` / `skill_list` / `skill_add` / `skill_remove` / `skill_my_skills` | SKILL.md 技能管理 |
| `toolkits.email.Email` | `send_email` / `read_mail` / `open_mail` / `mail_address_book` | 员工邮件 |
| `toolkits.hermes.Hermes` | `hermes_new_conversation` / `hermes_send` | 调用电脑上的 Hermes Agent |
| `toolkits.talk.Talk` | `talk` / `list_roles` | 角色间通信（同组互发，跨组走邮件） |
| `toolkits.hr.Hr` | `post_job_posting` / `list_candidates` | 招聘即入职（HR 专属） |
| `toolkits.client.Client` | `talk_to_client` | 甲方交流（CEO 专属） |

基类：`tools.Tool`（getToolName / getSchema / handler）与 `tools.Toolkit`
（addTool / getTools / trigger）。`tools.ToolkitBridge.toLegacy()` 把模板风格
工具类桥接为旧版 `ToolRegistry.ToolKit` 供 LLM 调用
（`AgentRole.addToolkit(Toolkit)` 已支持直接加载）。

### 9. 招聘即入职 (`src/core/hr_toolkit.py` + `src/core/roles.py`)

HR 发布招聘 → 后台 `RoleFactory` 生成新人 → **立即加入运行中团队并启动 worker**
（`add_role_and_start`）→ 新人自动获得全部默认工具 + MCP file_ops + 独立电脑。
无面试环节；入职后 HR 通知 COO。

### 10. 昨日记忆注入 (`src/core/note_store.py` + `src/core/roles.py`)

- 每天下班调 `summary` 保存当日总结（`_summary_day_<N>.md`，落在角色电脑 notes/）
- 第二天 `build_system_prompt()` 自动注入 `[昨日总结]`（严格早于今天的最近一篇）
- 前提是电脑已开机 —— SHIFT_START 上班自动开机保证了这点

> 记忆系统依赖天循环：作为人来说，昨天上班的碎片化记忆就是今天的记忆。所以每天都会有总结。
> 同时，记忆还包括笔记、任务和工作区的文件等。这些是外部记忆，如果不主动翻是不知道的。

### 11. 12 个预定义角色模板 (`src/core/role_templates.py`)

**管理层（默认角色）**：

| 角色 | 姓名 | 职责 |
|------|------|------|
| CEO | 林总 | 接收用户需求→战略目标→汇总报告 |
| COO | 陈总 | 拆解目标→盘点员工→发起招聘 |
| HR | 王人事 | 招聘申请→发布招聘→新人即入职（无面试） |
| CFO | 钱财 | 预算批复→Token 限额→高风险审批（模板保留，暂不启用） |
| 需求分析师 | 徐若楠 | 与甲方（用户）/领导层沟通需求→产出《需求说明书》 |

**技术团队**：architect(王建国) / fullstack_dev(李明) / reviewer(张伟) / qa_engineer(刘洋) / ops_engineer(赵强)

**业务团队**：content_marketer(陈静) / data_analyst(孙晓) / support_agent(周梅)

### 12. 动态角色工厂 (`src/core/role_factory.py`)

用人需求 → LLM 生成角色配置 → 新 AgentRole → 入职上岗。自动分配不重名人名（24 人名字库）。

### 13. 员工分组 + 公司邮件 (`src/core/role_templates.py` + `src/core/mail_service.py` + `src/python_tools/email_toolkit.py`)

**分组**：每位成员都有一个分组（`AgentRole.group`），默认团队划分如下 ——

| 分组 | 成员 |
|------|------|
| 领导组 | CEO 林总 / COO 陈总 / HR 王人事 / CFO 钱财 / CTO 高远 / 需求分析师 徐若楠 |
| 前端开发组 | frontend_lead 陈思远 + frontend_dev_1~3 |
| 后端开发组 | backend_lead 王宇轩 + backend_dev_1~3 |
| 移动开发组 | mobile_lead 张雅婷 + mobile_dev_1~3 |
| 全栈开发组 | fullstack_lead 李俊杰 + fullstack_dev_1~3 |
| 测试组 | test_lead 刘子涵 + tester_1~20 |
| 安全组 | attacker_1~3（红蓝对抗/审计） |
| 架构与版本组 | architect 王建国 + release_manager 方谨言 |
| 运维组 / 市场组 / 数据组 / 客服组 | ops_engineer 赵强 / content_marketer 陈静 / data_analyst 孙晓 / support_agent 周梅 |

- **talk 工具仅限同组成员之间交流**：跨组发送会被拒绝，并提示改用邮件
  （招聘入职的新人未分组，不受限制，可与任何人 talk）。
- 分组会显示在 `list_roles` 花名册、`mail_address_book` 通讯录和系统提示词中。

**公司邮箱**：每位成员一个邮箱 `username@<后缀>`（后缀由用户定义，见下方环境变量），
例如 郭晓东 → `guoxiaodong@company.com`；角色显式 `email` 字段可覆盖。

**员工邮件工具**（每个角色自动装配）：

| 工具 | 说明 |
|------|------|
| send_email | 发邮件给同事（人名/邮箱均可，支持多人、抄送）——跨组沟通的首选 |
| read_mail | 查看收件箱（新到优先，未读标记，可只看未读） |
| open_mail | 打开一封邮件全文（自动标记已读） |
| mail_address_book | 公司通讯录（按分组列出成员姓名/邮箱/职位） |

**虚拟实现（默认）**：邮件投递到内部邮箱，持久化在 `data/mail/mailboxes.json`
（重启后可恢复，已 gitignore）。**配置 SMTP 后自动切换真实发送**：通过 smtplib
发出真实邮件，同时仍把副本投递到内部收件人邮箱（模拟内网阅读）。

---

## 项目结构

```
AgentCompany/
├── src/
│   ├── core/
│   │   ├── types.py           # Event, AgentState, Priority 等数据类型
│   │   ├── event_bus.py       # EventBus: 定时事件调度表 (_tick_schedule)
│   │   ├── time_manager.py    # TimeEventBus: 时间(时钟/Tick/天) + 事件总线 + 快进
│   │   ├── roles.py           # AgentRole + RolePool（多角色线程池 + 动态入职/离职）
│   │   ├── agent_system.py    # 统一管理: TimeEventBus + RolePool + 事件分发
│   │   ├── computer.py        # Computer 基类 + Podman/Local/SSH + ComputerManager
│   │   ├── note_store.py      # 笔记 + 每日总结 (按天存储, 走角色电脑)
│   │   ├── role_templates.py  # 12 个预定义角色模板
│   │   ├── role_factory.py    # LLM 驱动动态创建角色
│   │   ├── dispatcher.py      # 事件广播到所有角色
│   │   ├── tools.py           # ToolRegistry: 工具注册 + to_openai_tools
│   │   └── llm.py             # OpenAI 兼容客户端 (原生 function calling)
│   ├── python_tools/          # Python 工具类 (DEFAULT_TOOLKITS)
│   │   ├── memory_toolkit.py  # summary (总结+下班+关机) / 笔记
│   │   ├── time_toolkit.py    # get_time / take_rest
│   │   ├── task_toolkit.py    # 定时任务 CRUD (持久化到电脑 tasks/)
│   │   ├── computer_toolkit.py# run_command / computer_status / lan_devices / reboot
│   │   ├── mcp_manager.py     # MCPManager: mcp_search/add/remove (安装到个人电脑)
│   │   ├── mcp_toolkit.py     # MCPServer: 服务器连接 (支持自定义启动命令)
│   │   ├── hr_toolkit.py      # post_job_posting (招聘即入职) / list_candidates
│   │   ├── client_toolkit.py  # talk_to_client (甲方交流, CEO 专属)
│   │   └── talk_toolkit.py    # talk / list_roles (角色间通信)
│   ├── config/
│   │   └── mcp_group_rules.json  # MCP 服务器与工具分组配置
│   ├── main.py                # 主入口: 多日循环 (自动进入第二天)
│   └── mcp_demo.py            # MCP 工具调用演示
└── data/
    ├── main_run.log           # 运行日志
    └── computers/<role>/      # 角色电脑 (宿主机侧挂载目录)
```

---

## 快速开始

### 前置条件

- Python 3.10+，`pip install -r requirements.txt`（或使用项目自带 `.venv/`）
- [podman](https://podman.io/)（每角色电脑的容器运行时，**必须安装**；本地模拟请显式使用 `computer_kind="local"`）
- OpenAI API Key（环境变量 `OPENAI_API_KEY`；也可用 `OPENAI_BASE_URL` / `OPENAI_MODEL`
  指向任意 OpenAI 兼容端点，如 DeepSeek / 本地 vLLM）

```bash
cd AgentCompany
source .venv/bin/activate

# 设置 API Key (必填, 源码不再硬编码)
export OPENAI_API_KEY="sk-..."

# 运行完整作息演示 (多日循环, 自动进入第二天)
python src/main.py
```

### 编程式使用

```python
from src.core.agent_system import AgentSystem
from src.core.types import Event, Priority

# 统一管理 TimeEventBus + RolePool + 事件分发
system = AgentSystem(role_ids=["CEO", "COO", "HR"])
system.start()   # 启动角色线程 + 时间线程 (Tick 0 / 第 1 天)

# 投递事件 (SHIFT_START/SHIFT_END 由时间线程自动触发)
system.trigger(Event(source="github", event_type="new_pr",
                     priority=Priority.HIGH, payload={"pr_number": 188}))

# 注册定时事件 (指定 Tick 触发; 不传 tick 则立即投递)
system.time_manager.register_event(
    Event(source="meeting", event_type="standup", priority=Priority.HIGH),
    tick=30,
)

print(system.describe())   # 第 X 天, Tick Y (上班中/已下班)
system.stop()
```

### 手动组合

```python
from src.core.dispatcher import EventDispatcher
from src.core.roles import RolePool
from src.core.role_templates import get_template
from src.core.time_manager import TimeEventBus

pool = RolePool()
pool.add_role(get_template("CEO"))

tm = TimeEventBus()
tm.set_event_sender(lambda ev: EventDispatcher(pool).trigger(ev))
tm.start()
pool.start()
```

---

## 使用示例

### 角色间通信

```python
coo = pool.get_role("COO")
coo.talk_to("HR", "请发布招聘: 需要一位精通 Rust 的后端工程师", "HIGH")
# → HR 队列收到: [FROM COO(陈总)] 请发布招聘...
```

> talk 仅限同组成员；CEO/COO/HR 同属「领导组」所以可以互发。跨组沟通请用邮件。

### 员工邮件（虚拟邮箱 / SMTP 真实发送）

```python
# 发件人: 郭晓东 (测试组) → 收件人: 王建国 (架构与版本组, 跨组)
tester = pool.get_role("tester_1")
tester._tools.call_tool("send_email", {
    "to": "王建国",
    "subject": "架构问题咨询",
    "body": "登录模块的权限校验想请教一下最佳实践。",
})
# → 邮件已发送给 wangjianguo@company.com, 主题「架构问题咨询」, 虚拟邮箱投递.

# 收件人查看收件箱
architect = pool.get_role("architect")
inbox = architect._tools.call_tool("read_mail", {"limit": 5})
mid = inbox.split("id=")[-1].split(")")[0].strip()
print(architect._tools.call_tool("open_mail", {"message_id": mid}).content[0].text)
```

### HR 招聘即入职

```python
hr = pool.get_role("HR")
result = hr._tools.call_tool("post_job_posting", {
    "requirement": "需要一位精通 Rust 的后端工程师, 熟悉 gRPC 和 PostgreSQL",
})
# → 后台生成新人 → 立即加入团队并上岗 (add_role_and_start)
# → 返回新人完整档案: role_id/姓名/技能, 状态 "已加入团队并上岗"
```

### 查看内网电脑设备

```python
dev = pool.get_role("CEO")
print(dev._tools.call_tool("lan_devices", {}))
# → 内网电脑设备:
#   - 林总 (CEO) | 电脑 maf-CEO | 10.89.0.2
#   - 陈总 (COO) | 电脑 maf-COO | 10.89.0.3
```

---

## 环境变量

下表所有 LLM 变量均可改用 Java 参数 (`-D<同名>`, 优先级最高) 或配置文件
(ConfigStore, 键 `llm.api_key` / `llm.base_url` / `llm.model`,
优先级最低) 提供; 三处都不设置时才使用默认值。统一走 OpenAI 兼容接口,
不区分后端: 把 `OPENAI_BASE_URL` / `OPENAI_MODEL` 指向任意 OpenAI 兼容服务即可。

| 变量 | 默认值 | 说明 |
|------|--------|------|
| OPENAI_API_KEY | (空) | OpenAI 兼容 API 密钥 (未设置则不携带 Authorization 头, 适合免 Key 的本地端点) |
| OPENAI_BASE_URL | https://api.openai.com | OpenAI 兼容 API 地址 (如 DeepSeek / vLLM / Ollama 的 OpenAI 端点) |
| OPENAI_MODEL | gpt-4o-mini | 模型名称 |
| MAIL_SUFFIX | company.com | 公司邮箱域名后缀（用户自定义，如 `mycorp.com` → `guoxiaodong@mycorp.com`） |
| SMTP_HOST | (空) | SMTP 服务器地址；**设置后邮件从虚拟实现切换为真实发送** |
| SMTP_PORT | 587 | SMTP 端口（465 自动走 SSL） |
| SMTP_USER / SMTP_PASSWORD | (空) | SMTP 登录凭据（可选） |
| SMTP_FROM | SMTP_USER 或发送者本人 | 真实邮件发件人地址（可选） |
| SMTP_USE_SSL | 按端口 | `true`/`false` 强制 SSL 或非 SSL 连接 |
| MAIL_DATA_DIR | data/mail | 内部邮箱数据目录（虚拟模式下邮件持久化位置） |

### 公司邮件：虚拟实现 → SMTP 真实发送

默认**虚拟实现**：邮件只在模拟内部投递（收件人 = 团队成员邮箱），并持久化到
`data/mail/mailboxes.json`，重启后可恢复。配置 SMTP 后自动切换为**真实发送**
（smtplib），同时保留内部邮箱副本供角色继续阅读：

```bash
# 虚拟实现 (默认): 什么都不用配, 员工之间邮件直接投递内部邮箱
export MAIL_SUFFIX="mycompany.com"

# 真实发送: 补上 SMTP 配置即自动切换
export SMTP_HOST="smtp.mycompany.com"
export SMTP_PORT=587
export SMTP_USER="agent@mycompany.com"
export SMTP_PASSWORD="********"
```

> SMTP 发送失败会返回错误（不投递），便于发现配置问题；发送成功的邮件会以
> 「已通过 SMTP 真实发送」标记投递进内部收件人邮箱。

### 使用其它 OpenAI 兼容后端

项目默认走 OpenAI 官方 API。要改用任意 OpenAI 兼容端点 (DeepSeek / 本地 vLLM、
LM Studio、Ollama 等, 免 API Key 的服务可不设 `OPENAI_API_KEY`), 设置
环境变量 (或 `-DOPENAI_BASE_URL=...` / 配置文件 `llm.base_url`) 后照常运行即可,
角色线程与招聘流程 (`RolePool`/`RoleFactory`) 自动使用该端点
(`OpenAICompatLLM` 统一走 OpenAI 接口):

```bash
export OPENAI_BASE_URL="https://api.deepseek.com"   # 或 http://localhost:11434/v1 (Ollama)
export OPENAI_MODEL="deepseek-v4-flash"             # 或本地模型标签
export OPENAI_API_KEY="sk-..."                      # 免 Key 的本地端点可省略
ollama serve                                        # 确保本地服务在跑 (如用 Ollama)
python src/role_demo.py                             # 示例: 多角色系统全走该端点
```

也可代码级指定: `RolePool(llm_api_key=..., llm_model=...)` /
`RoleFactory(api_key=..., model=...)` 传显式参数, 或
`new OpenAICompatLLM(apiKey, baseUrl, model, label, configStore)` 直接创建客户端;
构造器参数优先级最高 (高于环境变量与配置文件)。

### 状态持久化 (StateStore)

`main.py` 运行期间所有可序列化状态统一保存到 `data/state.json` (JSON 原子写, 不入库):

- **角色档案** — 名称/职位/职责/性格/技能/状态
- **任务历史** — 每个角色已完成/失败的任务 (含 talk 消息与结果 = 对话/工作记录)
- **未完成任务** — 队列待办, 重启后继续处理
- **电脑/容器信息** — podman 容器类型/人名映射, 重启后绑定已存在的容器, 不重建
- **时间进度** — 第几天/Tick, 恢复后作息继续

**退出时自动保存** (Ctrl+C 或正常结束), **启动时自动加载上次进度** (从上次的天数继续)。

```python
from src.core.state_store import StateStore
store = StateStore()
if store.exists():
    store.restore(system)   # 启动加载
store.save(system)          # 退出保存
```

### 角色活动日志

每个角色一份活动日志 (`data/journals/<role_id>.md`, 已 gitignore 不入库), 记录该角色
的上下文更新: 收到任务 / 开始执行 / 工具调用 / 笔记写入 / 消息收发 / WAIT 状态变化 /
事件接受与跳过。**全局通知 (SHIFT_START/SHIFT_END 作息事件、广播事件) 会写入每个角色
的日志**, 方便统一查看团队活动。

每行格式: `[D<第几天> T<Tick> HH:MM:SS] 内容`

```python
role.journal("任意活动记录")   # 写入该角色单独的文件
pool.journal_all("全局通知")   # 每个角色的日志都写一条
```

---

## 模块结构 (各 py 文件说明)

> 每个 `.py` 文件头部都有完整接口文档 (模块说明 + 类 + 方法清单)。

### 核心层 `src/core/` — 角色 / 电脑 / 时间 / LLM / 持久化

| 文件 | 模块说明 | 主要类 / 函数 |
|---|---|---|
| `roles.py` | 角色系统核心: AgentRole(单个角色: 任务队列/状态机/工具装配/分组 group/邮箱 mail_address/talk/WAIT 同步等待/journal) + RolePool(线程池调度, 注册即建日志) | `AgentRole`, `RolePool`, `Task`, `Urgency`, `ToolLoopError` |
| `computer.py` | 个人电脑抽象: LocalComputer(本地降级) / PodmanComputer(maf-base 容器: 拼音用户 + /mnt/drive 云盘挂载 + Hermes, 镜像由项目根 Containerfile 构建) / SSHComputer; ComputerManager 管理 + 自定义镜像 `maf-base` 构建/复用 | `Computer`, `LocalComputer`, `PodmanComputer`, `SSHComputer`, `ComputerManager`, `create_computer` |
| `time_manager.py` | 作息时间引擎 + 事件总线: Tick/天/上下班事件, 定时任务, 全角色空闲才快进 Tick | `TimeEventBus`, `ScheduledTask` |
| `event_bus.py` | 事件总线基类: 注册/取消/调度事件 | `EventBus` |
| `dispatcher.py` | 事件分发器: 广播事件到所有角色 | `EventDispatcher` |
| `llm.py` | LLM 后端: OpenAICompatLLM 具体类 (chat/summarize/工具调用/重试), 统一 OpenAI 接口, 只读 OPENAI_* 环境变量 | `OpenAICompatLLM` |
| `role_templates.py` | 55 个角色模板 (47 默认): CEO/COO/HR/CTO/需求分析师/技术负责人/前端后端移动全栈/测试/攻击者等 | `ceo`, `coo`, `frontend_dev`, `create_all_roles`, `get_template`, `TEMPLATES` |
| `role_factory.py` | 角色工厂: 按模板创建角色 (指定 api_key/model) | `RoleFactory` |
| `agent_system.py` | 团队系统: 装配多角色 + 时间引擎 + 事件分发, 并行开机 | `AgentSystem` |
| `tools.py` | 工具注册表: ToolDef(工具定义) / ToolKit(工具类) / ToolRegistry(统一注册/调用/OpenAI schema 导出) | `ToolDef`, `ToolKit`, `ToolRegistry` |
| `types.py` | 公共类型: AgentState(角色状态机)/ Priority / Event | `AgentState`, `Priority`, `Event` |
| `note_store.py` | 笔记 + 每日总结存储 (笔记带 remind_tick = 定时任务) | `NoteStore` |
| `todo_store.py` | 个人 Todo 清单 (data/todos/<role_id>.json) | `TodoStore` |
| `state_store.py` | 全量状态持久化 (data/state.json 原子写, 重启恢复) | `StateStore` |
| `mail_service.py` | 公司邮件核心: 邮箱地址分配 (username@用户后缀) + 虚拟/SMTP 投递 + 邮箱持久化 (data/mail) | `MailService`, `MailConfig`, `MailMessage` |
| `pinyin_map.py` | 角色中文名 → 汉语拼音映射 (容器用户名, 云盘权限) | `NAME_PINYIN`, `to_pinyin` |

### 工具类层 `src/python_tools/` — LLM 可调用的工具 (ToolKit)

| 文件 | 模块说明 | 工具 (LLM 调用名) |
|---|---|---|
| `__init__.py` | 工具类注册中心: DEFAULT_TOOLKITS 默认装配清单 + 各工厂导入 | `DEFAULT_TOOLKITS` |
| `memory_toolkit.py` | 笔记/每日总结 | `write_note` `edit_note` `list_notes` `read_note` `summary` |
| `time_toolkit.py` | 作息 | `get_time` `take_rest` |
| `todo_toolkit.py` | 个人待办 | `todo_add` `todo_list` `todo_update` `todo_delete` |
| `task_view_toolkit.py` | 任务列表视图 | `my_tasks` |
| `hermes_toolkit.py` | 调用电脑上的 Hermes Agent (新建对话/发送对话, 同步等结果) | `hermes_new_conversation` `hermes_send` |
| `computer_toolkit.py` | 电脑操作 | `run_command` `computer_status` `reboot` |
| `mcp_manager.py` | MCP 工具自助管理 | `mcp_search` `mcp_list` `mcp_add` `mcp_remove` `mcp_my_tools` |
| `mcp_toolkit.py` | MCP 客户端: 服务器连接/工具加载 (mcp-server-filesystem) | `MCPServer`, `MCPToolLoader` |
| `skill_toolkit.py` | 技能库 (共享 data/skills) | `skill_search` `skill_add` `skill_list` 等 |
| `talk_toolkit.py` | 角色通信 (人名寻址, **仅限同组成员**, wait 同步等待, 死锁检测) | `talk` `list_roles` |
| `email_toolkit.py` | 公司邮件 (员工之间邮件交流, 跨组沟通首选) | `send_email` `read_mail` `open_mail` `mail_address_book` |
| `client_toolkit.py` | 与甲方交流 | `talk_to_client` |
| `hr_toolkit.py` | 人事 (招聘) | `hire` 等 |

### 入口与演示 `src/`

| 文件 | 模块说明 |
|---|---|
| `main.py` | 系统主入口: 46 角色完整团队模拟 (恢复进度 → 循环跑日 → 保存状态) |
| `role_demo.py` | 演示: 单角色创建 + 工具装配 + 任务执行 |
| `talk_demo.py` | 演示: 4 角色 talk 协作链 |
| `mcp_demo.py` | 演示: MCP filesystem 安装 + 工具调用 |

### 测试 `tests/`

| 文件 | 覆盖 |
|---|---|
| `test_event_bus.py` | 事件总线注册/触发/取消 |
| `test_time_manager.py` | Tick 推进/作息事件/定时任务/快进 |
| `test_journal.py` | 角色活动日志 |
| `test_talk_wait.py` | talk 通信 / WAIT 同步等待 / 死锁环检测 / 附件 |
| `test_talk_group.py` | talk 组内交流限制 (同组放行/跨组拒绝/未分组不受限) |
| `test_mail.py` | 邮箱分配 / 虚拟投递 / SMTP 真实发送 / 持久化 / 通讯录 |
| `test_state_store.py` | 状态持久化 保存/恢复 |
| `test_llm_retry.py` | LLM 重试语义 (mock requests) |
| `test_note_store.py` / `test_note_reminder.py` | 笔记存储 / 笔记提醒 = 定时任务 |
| `test_todo_taskview.py` | Todo 清单 + 任务列表 |
| `test_pinyin.py` | 拼音映射 / uid 分配 / 提示词含云盘与 Git 规范 |
| `test_hermes_toolkit.py` | Hermes 对话工具 (建会话/发送/错误提示) |
