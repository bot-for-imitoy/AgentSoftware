# 作息时钟与事件驱动的大规模多智能体"软件公司"仿真框架：AgentSoftware 的设计与创新

**Shift-Clock and Event-Driven Simulation of a Large-Scale Multi-Agent "Software Company": Design and Innovations of AgentSoftware**

> **文档性质说明 / Provenance Note**：本文为**内部技术论文草稿，不用于投稿（not for submission）**。内容基于对仓库 `AgentSoftware`（`https://github.com/imitoy/AgentSoftware`，Java/Maven 实现，最新提交 `68e4b58`）的源码、文档、自动化测试与运行产物的分析整理而成。文中涉及的具体数字（角色数、测试数、参数默认值等）均以该仓库当前状态为准。作者、单位与基金信息留空占位。
>
> This is an internal draft paper **not intended for submission**, distilled from an analysis of the `AgentSoftware` repository (commit `68e4b58`). All quantitative statements refer to the repository state at that commit. Author/affiliation/funding placeholders are left blank.

---

## 摘要

长期运行的多智能体大语言模型（LLM）应用普遍面临四类工程难题：上下文随任务无限膨胀、状态不可恢复、token 成本失控，以及角色间缺乏权限隔离。本文系统介绍并分析 **AgentSoftware**——一个用 Java（JDK 25、虚拟线程、JUnit 5）实现的、面向"软件公司"场景的多角色 LLM 智能体调度与仿真框架。该框架以三项机制为核心：(1) **作息时钟驱动的调度范式**：共享的 `TimeEventBus` 将"模拟日历时钟"与"事件总线"合二为一，以秒级 Tick 推进；团队忙碌时时钟按工作真实耗时同步前进，全员空闲时快速快进至下一个事件点，下班铃（SHIFT_END）后所有角色先写"当日总结"再统一 OFF_DUTY，全员收尾完成后自动翻页到次日 08:00——智能体以"天"为单位获得天然的工作—休息节律与上下文边界，杜绝忙等与死锁；(2) **零 token 三级事件过滤**：每个事件经"状态掩码 → 显著性评分 → 唤醒"三层过滤后才可能消耗 token，无关事件以纯 CPU 代价被丢弃，使 47 角色的常驻团队在经济上可行；(3) **"一人一机"的容器级环境隔离**：每个角色拥有独立 Podman 容器/个人电脑（本地或 SSH 亦可），工具、笔记、MCP 服务器均运行于其中，权限由操作系统边界而非提示词保证。在此之上，框架还提供角色↔LLM 的**有界上下文会话管理**（跨任务连续性、字符预算触发式自动压缩、按工作日开闭）、**单一 JSON 原子存档与断点续跑**、**拟真公司组织通信**（分组群聊、公司邮件、即时招聘、人工客户协同）与**零依赖 Web 观测界面**。文中给出创新点归纳对照表、设计细节、196 项 JUnit 测试概况及运行实录分析，并讨论局限与未来工作。

**关键词**：大语言模型智能体；多智能体系统；事件驱动调度；上下文管理；容器隔离；软件过程仿真

## Abstract

Long-running multi-agent large language model (LLM) applications face four recurring engineering problems: unbounded context growth, unrecoverable runtime state, runaway token cost, and missing permission isolation among agents. This paper presents and analyses **AgentSoftware**, a Java-based (JDK 25, virtual threads, JUnit 5) role-based scheduler and simulator of an LLM-powered "software company". Its design rests on three mechanisms. (1) **Shift-clock-driven scheduling**: a shared `TimeEventBus` merges a simulated calendar clock with an event bus and advances in second-level ticks; the clock flows in real time while the team is busy, fast-forwards to the next scheduled event once everyone is idle, and — after the shift-end bell — waits for every role to write its daily summary and go OFF_DUTY before rolling over to the next day at 08:00. Agents thereby obtain a natural work–rest rhythm and a per-day context boundary without busy-waiting or deadlock. (2) **Zero-token three-layer event filtering**: every event is screened per role by a state mask, a salience score and a wake step before it can spend any token; irrelevant events are dropped at pure CPU cost, which keeps a resident 47-role team affordable. (3) **"One employee = one computer" container isolation**: every role owns an isolated Podman container / personal computer (or a local / SSH equivalent) in which its tools, notes and MCP servers run, so permissions are enforced by OS boundaries rather than by prompting. On top of these, the framework contributes bounded-context role↔LLM conversation management (cross-task continuity, budget-triggered auto-compaction, per-day open/close), atomic single-JSON persistence with calendar-anchored resumption, realistic corporate communication (group chat, company mail, on-the-spot hiring, human-client collaboration) and a zero-dependency Web observability UI. We summarize the innovations in a problem-to-solution table, report the design in detail, survey the 196-test JUnit suite and runtime artifacts, and discuss limitations and future work.

**Keywords**: LLM agents; multi-agent systems; event-driven scheduling; context management; container isolation; software-process simulation

---

## 创新点速览 / Innovation Summary at a Glance

| # | 创新点（中文） | Innovation (EN) | 对应经典难题（中文 / EN） |
|---|---|---|---|
| C1 | 作息时钟 + 事件驱动调度：忙时同步、闲时快进、下班总结、跨日翻页 | Shift-clock + event-driven scheduling with busy-time flow, idle fast-forward, shift-end wrap-up and day rollover | 忙等空转与死锁 / busy-waiting, deadlock, wasted wall-clock time |
| C2 | 零 token 三级事件过滤，把"是否唤醒智能体"变成 0 成本的纯计算 | Zero-token 3-layer event filtering: *whether* to wake an agent is decided at zero LLM cost | token 成本失控 / runaway token cost in large teams |
| C3 | "一人一机"：容器/OS 级环境与权限隔离，个人电脑随作息开关机 | One-employee-one-computer: per-agent OS-level sandbox with schedule-driven power management | 权限与工具环境缺乏隔离 / missing permission isolation |
| C4 | 角色↔LLM 有界上下文会话：跨任务连续 + 预算压缩 + 按天清空 | Bounded role↔LLM conversations: cross-task continuity, budget-triggered compaction, per-day lifecycle | 上下文爆炸 / context explosion on long-running tasks |
| C5 | 状态可恢复：单一 JSON 原子存档、日历锚定断点续跑、容器复用绑定 | Recoverable state: atomic single-JSON archive, calendar-anchored resume, container re-binding | 状态不可恢复 / unrecoverable state, crash restarts from zero |
| C6 | 拟真公司协作拓扑 + 人在环协同 + 动态招聘 | Believable corporate collaboration topology, human-in-the-loop client channel, dynamic hiring | 单回合流水线无法覆盖长期协作 / pipelines lack long-term collaboration |
| C7 | 全链路可观测：角色日志、Web 实时痕迹（思考/工具/结论） | End-to-end observability: per-role journals and a live Web trace (reasoning/tool/answer) | 多智能体行为难调试 / opaque multi-agent behavior |
| C8 | 工程化可扩展：角色即数据、18 家 LLM 提供商目录、一进程多公司实例 | Engineering scalability: roles-as-data, an 18-provider catalog, multi-company instances per JVM | 场景单一、供应商锁定 / one-off demos and vendor lock-in |

---

## 1 引言 / Introduction

**（中文）** 以大语言模型为"大脑"的智能体（LLM agent）已经从"单次问答 + 工具调用"演进为能够承担长期任务的自主系统：它们被编排成多角色团队，模拟软件公司、科研组或游戏小镇中的分工协作。然而，把若干 LLM 角色放进一个长时间运行的循环，工程上远比把它们"逐轮对话"要困难。综合相关实践与本项目的开发经验，长期多智能体系统主要面对四类问题：

1. **上下文爆炸（context explosion）**：任务链越长，塞进 prompt 的历史越多，先出现 token 超限，随后出现"久远信息被淹没"的退化，最后上下文必然失去边界；
2. **状态不可恢复（unrecoverable state）**：运行中的对话、队列、文件与时钟状态散落在内存与临时文件中，进程一旦中断，只能从头再来，而 LLM 运行成本高昂，重跑代价难以接受；
3. **token 成本失控（runaway token cost）**：若每个智能体都持续轮询、或对所有事件都"看一眼"，人数一多成本便随角色数与消息数乘积增长；
4. **权限与工具环境缺乏隔离（missing isolation）**：多个智能体共享一个工作区时，文件互相覆盖、工具互相干扰，权限只能靠提示词约束，既不安全也不可复现。

本文介绍的 **AgentSoftware** 以一个朴素但有效的组织学隐喻回应上述问题——**让智能体像"员工"一样按照"公司作息"工作**：共享时钟给出上下班铃（SHIFT_START / SHIFT_END），事件总线决定"谁该被叫醒"，每个员工拥有**自己的一台电脑**（隔离的文件系统与工具），下班前**必须写当日总结**、第二天从总结继续。系统在"何时唤醒智能体"（事件过滤）、"智能体保留多少记忆"（会话预算与按天生命周期）、"智能体在哪里干活"（容器边界）三个决策点上分别施加了可量化的约束，把多智能体系统的运行期成本与可靠性问题转化为可测试的工程构件。

**（English）** LLM-powered agents have evolved from single-shot question answering with tool calls into autonomous systems entrusted with long-horizon tasks — orchestrated as multi-role teams that emulate software companies, research groups or game towns. Yet running many LLM agents inside long-lived loops is far harder than arranging turn-by-turn dialogues. Drawing on prior practice and this project's development experience, long-running multi-agent systems suffer from four recurring problems: (1) **context explosion** — longer task chains accumulate ever larger prompts, overflow token limits and drown out old information, until context loses all bounds; (2) **unrecoverable state** — dialogues, queues, files and clock state scattered in memory and temp files are lost on interruption, and LLM reruns are expensive; (3) **runaway token cost** — agents that poll continuously or glance at every event make cost grow with the product of agent count and message volume; (4) **missing permission isolation** — agents sharing one workspace overwrite each other's files and disturb each other's tools, with permissions enforced only by prompting.

AgentSoftware answers these problems with a deliberately simple organizational metaphor: **agents work like employees on a corporate schedule**. A shared clock rings the shift-start and shift-end bells, an event bus decides whom to wake, every employee owns a **personal computer** (an isolated filesystem and toolset), and each one **must write a daily summary before going off duty**, resuming the next day from that summary. The system imposes measurable discipline at three decision points — *when* to wake an agent (event filtering), *how much* an agent remembers (conversation budget and per-day lifecycle), and *where* an agent works (container boundary) — turning the runtime cost and reliability problems of multi-agent systems into testable engineering components.

## 2 相关工作 / Related Work

**（中文）** 与本文工作相关的既有研究可分为四类。

**多智能体对话与编排框架。** AutoGen [1] 把多智能体协作建模为可编程的对话图，强调对话轮次与人类参与；CrewAI [2] 与 LangGraph 等以"角色 + 任务链"组织 crews，适合结构化流水线。这类框架以"对话/流程"为第一公民，对"常驻式、跨越多日、由外部事件驱动"的长期运行关注较少，且普遍缺乏面向运行期成本的显式控制。

**软件公司类分工仿真。** MetaGPT [3] 将软件公司 SOP（标准作业程序）编码进多角色协作，通过结构化文档（PRD、设计、代码）在角色间传递信息；ChatDev [4] 以"瀑布流聊天链"模拟软件公司的开发过程。它们证明"公司隐喻"能显著提升任务质量，但基本是**有始有终的任务流水线**：角色在单次任务内活跃，事件驱动、持久化、上下文预算与隔离不作为一等问题处理。AgentVerse [5] 与 CAMEL [6] 等探索角色扮演与群体涌现行为，同样偏重于会话层研究。

**社会仿真与"生成式智能体"。** Park 等提出的 Generative Agents [7] 在模拟小镇中让 25 个带记忆流的智能体产生可信的日常行为，其"记忆流 + 反思 + 计划"的机制与本项目"笔记 + 当日总结 + 次日冷启动"的意图相似，但前者面向人类学行为仿真，后者面向**真实的软件交付过程**（代码、测试、邮件、审批），且智能体在本项目中承担真实的工具执行与文件产出。

**上下文管理与 Agent 操作系统。** MemGPT [8] 将 LLM 上下文类比操作系统内存，通过函数调用实现分层存储与自我编辑；AIOS [9] 提出面向 LLM 智能体的操作系统式调度。本项目的会话管理采取了更简单、确定性更强的策略：固定字符预算，越界时用一次 LLM 摘要调用把整段历史压缩为一条消息，失败则丢弃最旧消息并硬截断——"上下文只缩不涨"是硬不变量；同时以"工作日"为生命周期边界，下班即清空。这可以视为 MemGPT 思想在"班次制"约束下的轻量工程化。

**工具与运行时生态。** ReAct [10] 确立了"推理—行动"交替的工具调用范式，本项目的工具循环即其原生函数调用实现；Anthropic 的 Model Context Protocol（MCP）[11] 统一了工具服务器的接入方式，本项目将 MCP 服务器放入每个角色的个人电脑容器内运行；Java 21 虚拟线程 [12] 使得"每个角色一个常驻线程"在 47 角色规模下没有平台线程压力。

**与上述工作的区别。** 本工作把**运行期经济性**（事件过滤的零 token 成本、上下文压缩、作息驱动的空闲快进）、**可靠性**（原子存档、防死锁的收尾与翻页机制）与**环境隔离**（一人一机）提升为与"任务质量"同等重要的设计目标，并把它们做成可单元测试的机制——这正是多数对话/流水线框架尚未系统覆盖的部分。

**（English）** Four strands of prior work are relevant. **(a) Multi-agent dialogue and orchestration frameworks.** AutoGen [1] models collaboration as programmable conversation graphs with human participation; CrewAI [2] and LangGraph organize "roles + task chains" suited to structured pipelines. These frameworks treat *dialogue/flow* as the first-class citizen and pay less attention to resident, multi-day, externally event-driven operation or to explicit runtime-cost control. **(b) Software-company division-of-labor simulations.** MetaGPT [3] encodes software-company SOPs into multi-role collaboration with structured documents (PRDs, designs, code) flowing between roles; ChatDev [4] simulates software development as a waterfall chat chain. They demonstrate that the "company metaphor" improves task quality, but they are essentially start-to-finish pipelines: event-drivenness, persistence, context budgets and isolation are not first-class concerns. AgentVerse [5] and CAMEL [6] explore role-play and emergent group behavior at the conversation layer. **(c) Social simulation / generative agents.** Park et al. [7] make 25 agents with memory streams live credible daily lives in a small town; their memory-stream/reflection/planning machinery resembles this project's notes + daily-summary + cold-start design in spirit, but targets anthropological behavior simulation rather than real software-delivery processes (code, tests, mail, approvals) with real tool execution and file output. **(d) Context management and agent operating systems.** MemGPT [8] treats LLM context as OS memory with function-call-driven tiered storage; AIOS [9] proposes OS-like scheduling for LLM agents. Our conversation manager adopts a simpler, more deterministic strategy — a fixed character budget, one summarizing LLM call that collapses the whole history into a single message when the budget is crossed, with drop-oldest and hard-truncation fallbacks so that "context only shrinks" is a hard invariant — and uses the *work day* as its lifecycle boundary (cleared at shift end). It is a lightweight, shift-disciplined engineering of the MemGPT idea. On the runtime/tooling side, ReAct [10] established the reason–act tool loop (implemented here with native function calling); the Model Context Protocol [11] standardizes tool-server access (MCP servers run *inside* each role's container here); and Java 21 virtual threads [12] make a resident thread-per-role design practical at 47 roles.

The distinguishing point of this work: **runtime economy** (zero-token event filtering, context compaction, idle fast-forwarding), **reliability** (atomic archives, deadlock-free wrap-up and rollover) and **environment isolation** (one employee = one computer) are elevated to first-class design goals on a par with task quality, and are implemented as individually unit-testable mechanisms — coverage that dialogue/pipeline frameworks largely lack.

## 3 系统设计 / System Design

### 3.1 总体架构 / Overall Architecture

**（中文）** `AgentSystem` 是一个**自包含**的"公司"容器：它直接拥有时钟（`TimeEventBus`）、角色池（`RolePool`，每角色一个虚拟线程）、事件分发器（`EventDispatcher`）、电脑注册表（`ComputerManager`）、公司邮箱（`MailService`）、MCP 与技能管理器、客户端通信锁、Web 聊天存储（`ChatStore`）、角色↔LLM 会话管理器（`ConversationManager`）、配置存储与**自己的数据根目录**——因此一个 JVM 进程内可以并行运行多个互不干扰的 `AgentSystem`（见 3.10）。事件流向为：时间线程/外部事件 → `EventDispatcher.trigger` → 逐角色三层过滤 → 被接受的事件转换为**任务**进入该角色的优先级队列（CRITICAL > HIGH > NORMAL > LOW）→ 角色的常驻虚拟线程弹出任务 → `executeWithTools` 以原生函数调用循环执行（LLM → tool_calls → 工具执行 → 结果回填）→ 会话管理器提交本轮对话并控制上下文预算。角色元数据来自 JSON 模板（`role_templates.json`，55 个模板；默认团队 47 人：领导组 5 人、组长/发布 7 人、工程师 12 人、测试 20 人、安全蓝队 3 人），角色即数据，可经代码 Builder 或 HR 招聘动态创建。

**（English）** An `AgentSystem` is a **self-contained** "company" object: it directly owns the clock (`TimeEventBus`), the role pool (`RolePool`, one virtual thread per role), the event dispatcher, the computer registry, the mail service, the MCP and skill managers, the client-communication lock, the Web chat store, the per-system conversation manager, a config store, and its **own data root directory** — so several non-interfering systems can run in one JVM (§3.10). Events flow as follows: time thread or external events → `EventDispatcher.trigger` → per-role three-layer filtering → accepted events become **tasks** queued by priority (CRITICAL > HIGH > NORMAL > LOW) → each role's resident virtual thread pops tasks → `executeWithTools` runs a native function-calling loop (LLM → tool_calls → execution → result feedback) → the conversation manager commits the round and enforces the context budget. Role metadata is plain JSON (`role_templates.json`: 55 templates; default team of 47 — 5 leaders, 7 leads/release, 12 engineers, 20 testers, 3 security/blue-team attackers); roles are data and can also be built in code or hired at runtime.

### 3.2 时间与事件深度耦合：模拟日历作息时钟 / Time–Event Coupling: the Simulated Calendar Clock

**（中文）** 系统默认把 **1 Tick = 1 个模拟秒**（`secondsPerTick`，可配置；改值会整体重标定班次/天几何）。每个工作日从**真实日历日期**的 08:00:00 开始（第 1 天 = 启动当天，`baseDate`），10 小时班次到 18:00:00（`shiftEndTick` = 36000），一整天循环 86400 ticks，18:00–次日 08:00 的"非工作窗口"由翻页跳跃跳过。`TimeEventBus` 同时扮演时钟与事件总线（其注释直言"时间和事件深度耦合"），其推进包含三种模式：

1. **忙时同步推进（busy flow）**：只要有角色在工作（LLM 往返、工具调用、群聊等待），时钟按 `simSecondsPerRealSecond`（默认 1.0，即真实时间流速）前进；忙时推进**永不超过当天 18:00**，下班铃后仍排队的普通任务自动顺延到次日；
2. **闲时快进（idle fast-forward）**：当**全体**角色空闲达 `FAST_FORWARD_IDLE_SECONDS`（默认 60 秒真实时间）后，时钟直接跳到下一个调度事件点（笔记提醒 / 18:00 下班 / 满足翻页条件后的次日 08:00）——没有人真实空等，忙碌的 LLM 也不会"错过"截止时间；
3. **收尾与跨日翻页（wrap-up & rollover）**：18:00 触发 SHIFT_END：先唤醒所有同步等待群聊回复（WAIT）的角色（否则他们永远走不到总结任务），随后每个角色调用 `summary` 工具写下当日总结并转为 OFF_DUTY；当**全员** OFF_DUTY 后时钟翻页到次日 08:00 触发 SHIFT_START。若个别角色总结失败导致收尾停滞，时间线程在 `WRAP_UP_GRACE_SECONDS`（600 秒）宽限后调用强制钩子（唤醒 WAIT 者、把空闲未收尾角色置为 OFF_DUTY），**保证日循环不可能死锁**。

时间与任务调度因此统一：`write_note` 携带 `remind_tick` 时，笔记即成为注册在事件时刻表上的定时任务（提醒只能落在班次区间 0–36000 tick 内），次日到班的 SHIFT_START 会自动把"明日任务"装载进事件表。系统时间事件（`source="time"`，EMERGENCY）绕过内容显著性过滤直达角色；OFF_DUTY 角色只可能被 EMERGENCY 唤醒（例如同日晚间紧急任务会以"清空上下文"语义重开会话）。

**（English）** By default **1 Tick = 1 simulated second** (`secondsPerTick`, configurable; changing it rescales the whole shift/day geometry). Each work day starts at 08:00:00 of a real calendar date (day 1 = the run's start date, `baseDate`); the 10-hour shift ends at 18:00:00 (`shiftEndTick` = 36000), a full day cycle is 86400 ticks, and the after-hours window is skipped by the rollover jump. `TimeEventBus` is at once the clock and the event bus ("time and events are deeply coupled"), advancing in three regimes:

1. **Busy flow** — while any role is working (LLM round-trips, tool calls, talk waits), the clock advances at `simSecondsPerRealSecond` simulated seconds per real second (default 1.0 = real-time pacing); busy work **never pushes the clock past 18:00**, and ordinary tasks still queued at shift end simply carry over to the next day;
2. **Idle fast-forward** — once *all* roles have been idle for `FAST_FORWARD_IDLE_SECONDS` (default 60 real seconds), the clock jumps straight to the next scheduled event tick (a note reminder / 18:00 shift end / next day's 08:00 once the rollover gate opens) — nobody waits in real time, and a busy LLM never "misses" a deadline;
3. **Wrap-up and day rollover** — at 18:00, SHIFT_END fires: roles synchronously blocked in `talk` waits are woken first (otherwise they would never reach their summary task), then every role calls the `summary` tool and goes OFF_DUTY; once *all* roles are OFF_DUTY the clock rolls over to the next day's 08:00 SHIFT_START. If a failed summary stalls the wrap-up, a grace timer (`WRAP_UP_GRACE_SECONDS`, 600 s) forces the rollover via a hook that wakes waiters and marks idle stragglers OFF_DUTY — **the daily loop cannot deadlock**.

Time and task scheduling are thereby unified: a `write_note` carrying a `remind_tick` registers the note as a timed event on the schedule table (reminders are only accepted inside the shift, ticks 0–36000), and the next day's SHIFT_START automatically loads due "tomorrow tasks" into the bus. System-time events (`source="time"`, EMERGENCY) bypass the content-salience layer; an OFF_DUTY role can only be woken by EMERGENCY events, in which case its conversation reopens with flushed context.

### 3.3 零 token 三级事件过滤 / Zero-Token Three-Layer Event Filtering

**（中文）** 广播事件到达每个角色后，由角色独立执行三层过滤，只有第三层通过才产生一次 LLM 调用：

- **第 1 层 · 状态掩码**：OFF_DUTY / WRAPPING_UP / WAIT 的角色忽略一切非 EMERGENCY 事件（代价 0）；
- **第 2 层 · 显著性评分**（对系统时间事件直接放行）：先算相关度 `relevance`，基值 0.25；兴趣关键词命中每个 +0.25（封顶 +0.60）；技能词部分匹配 +0.10；出现 "urgent/critical" +0.15；压到 [0,1] 后按下式合成并对比阈值（默认 `salienceThreshold` = 0.4）：

  `score = 0.4 × (priority/10) + 0.6 × relevance，  若 score < threshold 则丢弃（代价 0）`

- **第 3 层 · 唤醒**：通过的事件 `eventToTask` 转成该角色队列中的任务，按其优先级排队，至此才消耗 token。

此外事件可带 `targetRole` 定向投递（如邮件送达触发 recipient 的 NEW_MAIL）：定向事件跳过内容过滤直接成为该角色任务，但同样遵守"非紧急事件不打扰下班/收尾/等待中的角色"。分发器维护 `roles_notified / roles_activated / roles_skipped / total_tasks_created` 统计，运行时可审计"每个事件省了多少 token"。`Main` 演示中专门向全员广播一条 LOW 级闲聊（"午饭吃什么？"）并打印过滤结果，直观展示该层在 47 人规模下把无关打扰压到 0 成本。

**（English）** Each broadcast event is screened per role through three layers; only a Layer-3 pass triggers an LLM call. **Layer 1 — state mask**: roles that are OFF_DUTY / WRAPPING_UP / WAIT ignore all non-EMERGENCY events (cost 0). **Layer 2 — salience** (system time events pass through directly): relevance starts at 0.25; each interest-keyword hit adds 0.25 (capped at +0.60); a partial skill-word match adds 0.10; the words "urgent/critical" add 0.15; after clamping to [0,1], the score is blended with priority and compared to the per-role threshold (default 0.4):

`score = 0.4 × (priority/10) + 0.6 × relevance, drop the event if score < threshold (cost 0)`

**Layer 3 — wake**: a passed event becomes a queued task via `eventToTask`; only then are tokens spent. Events may also carry a `targetRole` for directed delivery (e.g. a NEW_MAIL event to the recipient after mail delivery): directed events skip content filtering, but still respect "non-urgent events do not disturb off-duty/wrapping-up/waiting roles". The dispatcher keeps `roles_notified / roles_activated / roles_skipped / total_tasks_created` counters for auditing how many tokens each event saved. The `Main` demo deliberately broadcasts a LOW-priority "what's for lunch?" chat and prints the filter verdict — a live demonstration that irrelevant interruptions cost zero tokens at 47-role scale.

### 3.4 "一人一机"：个人电脑与容器级隔离 / One Employee, One Computer: Container-Level Isolation

**（中文）** 每个角色拥有一台"个人电脑"，抽象接口为 `Computer`，实现三种 `kind`：**podman**（默认）、**local**（纯目录模拟）、**ssh**。Podman 实现下，系统为 `maf-<role_id>` 容器（`maf-net` 桥接网络，基础镜像 `maf-base:latest` 由仓库 `Containerfile` 定义并自动构建：Ubuntu 24.04 + git/node/python/sudo，并预装 MCP filesystem 服务器）。关键设计是**宿主目录即容器家目录**：`data/computers/<role_id>` 双向挂载为容器内 `/home/agent`，并给每位员工分配拼音用户名与稳定 uid（1100 + 注册序号），角色的笔记、待办、技能、git 仓库与 MCP 服务器都落在这台"电脑"里。共享的**公司云盘**挂在 `/mnt/drive`：`Public` 全员可读写、每位员工的私有目录他人只读——共享与私有的边界由文件系统权限表达。工具的权限由此是 OS/容器强制的，而不是提示词约定的。

**电源管理直接挂在作息上**：SHIFT_START 全员自动开机（并在开机时探测 MCP 服务器存活，进程随容器停止而消失的服务器会自动重建）；角色写完当日总结的瞬间其电脑立即关机（`summary` 工具在保存后触发 power-off），次日上班再开机——用"关电脑"把一个角色的环境状态冻结在下班点，顺带降低了宿主机资源占用。MCP 服务器以 `podman exec` 运行在容器**内部**，因此"角色能调用哪些工具"天然服从其电脑的边界；无 podman 时切 `local` 即可做纯目录仿真，测试与 CI 不依赖容器。

**（English）** Every role owns a personal computer behind a `Computer` interface with three `kind`s: **podman** (default), **local** (plain directory simulation) and **ssh**. In the podman implementation each role gets a container named `maf-<role_id>` on the `maf-net` bridge network, built from the repository's `Containerfile` base image `maf-base:latest` (Ubuntu 24.04 with git/node/python/sudo and a preinstalled MCP filesystem server). The key design choice is that **the host directory is the container home**: `data/computers/<role_id>` is bind-mounted as `/home/agent` inside the container, each employee has a pinyin username and a stable uid (1100 + registration sequence), and a role's notes, todos, skills, git repositories and MCP servers all live on "this computer". A shared corporate **cloud drive** is mounted at `/mnt/drive`: `Public` is readable/writable by all, each employee's private directory is read-only for others — sharing and privacy are expressed by filesystem permissions, so tool permissions are OS-enforced rather than prompt-enforced.

**Power management is tied to the schedule**: SHIFT_START powers all computers on (probing MCP-server liveness; servers killed with the container are rebuilt automatically), and each computer is powered **off the moment its role's daily summary is saved** (`summary` triggers power-off), back on at the next shift start — "turning off the machine" freezes a role's environment at the close of business and reduces host resource usage. MCP servers run *inside* the container via `podman exec`, so the tools a role may call are naturally bounded by its computer; switching to `local` removes the container dependency entirely, which keeps tests and CI container-free.

### 3.5 角色↔LLM 会话管理：有界上下文 / Role↔LLM Conversation Management: Bounded Context

**（中文）** `Conversation`（由 `ConversationManager` 管理，每角色每系统一个）精确坐落在 `AgentRole` 与 `OpenAICompatLLM` 之间，解决"同一角色在同一工作日内跨任务记不住事"与"上下文无界增长"两个问题：

- **跨任务连续性**：每个任务的请求为 `系统提示 + 已提交的对话历史 + 新任务`；任务完成时，其"用户任务 + 最终回答（附 ≤6 条、每条参数 ≤80 字符 / 结果 ≤200 字符的工具行为回顾）"被提交回当日对话——下一个任务建立在上一个之上；
- **预算触发式自动压缩**：每段对话有字符预算（默认 `MAX_HISTORY_CHARS` = 24 000）。一旦累计历史越界，用一次 LLM 摘要调用（`LLM.summarize`）把**整段历史**压成一条 ≤12 000 字符的摘要消息；若摘要调用失败或不可用，则丢弃最旧消息直至预算内，最后手段是逐条硬截断。因此"**上下文只缩不涨**"是运行时硬不变量（压缩每越界一次才发生一次，额外开销有界）；
- **按工作日开闭**：会话以工作日为 key。角色调用 `summary` 并转 OFF_DUTY 时，`Conversation.closeDay()` 清空当日对话（其内容已沉淀进当日总结文件），并阻止把收尾的"总结已保存"往返追加回去；次日 SHIFT_START（或同日下班后 EMERGENCY 任务）以干净状态重开会话，次日冷启动仍依赖系统提示中的 `[Yesterday's Summary]`；
- **持久化与隔离**：打开中的对话随 `StateStore` 一并存档，中断后可**原位**恢复；每个 `AgentSystem` 拥有自己的 `ConversationManager`，多公司实例间对话互不可见。

**（English）** A `Conversation` (managed per system by `ConversationManager`, one per role) sits exactly between `AgentRole` and `OpenAICompatLLM`, addressing both "a role cannot remember within a day across tasks" and "context grows without bound": **(a) cross-task continuity** — each task request is `system prompt + committed history + new task`; when a task ends, its user-task + final-answer exchange (enriched with a bounded recap of tool activity: ≤6 calls, ≤80-char args / ≤200-char results each) is committed back to the day's dialogue, so the next task builds on it; **(b) budget-triggered auto-compaction** — each dialogue has a character budget (default `MAX_HISTORY_CHARS` = 24 000); crossing it collapses the whole history into a single summary message (≤12 000 chars) via one `LLM.summarize` call; if summarization fails or no LLM is available, the oldest messages are dropped and, as a last resort, hard-truncated — so "context only shrinks" is a hard runtime invariant (compaction happens once per crossing, keeping overhead bounded); **(c) per-day lifecycle** — conversations are keyed by work day; when a role calls `summary` and goes OFF_DUTY, `Conversation.closeDay()` clears the dialogue (its recap already persisted in the day's summary file) and prevents the trailing "summary saved" exchange from lingering; the next shift start — or a same-day EMERGENCY task after off-duty — reopens with a clean slate, while the next day's cold start still comes from `[Yesterday's Summary]` in the system prompt; **(d) persistence and isolation** — open dialogues are archived with `StateStore` and restored *in place* after interruption; each `AgentSystem` owns its own `ConversationManager`, keeping company instances fully isolated.

### 3.6 记忆、总结与按天记忆层次 / Memory, Summaries and the Per-Day Memory Hierarchy

**（中文）** 角色的记忆按"代价—寿命"分三层，与作息节律对齐：(i) **工作日内短记忆**：由 3.5 的当日对话承担，受 24 000 字符预算约束；(ii) **跨日持久记忆**：笔记（`write_note` 等，存放在角色自己的电脑/笔记库中，可携带提醒成为定时事件）与待办清单（`todo_*`）；(iii) **每日总结**：SHIFT_END 时每个角色把当天工作压缩成一条总结写入 `summaries/<day>.md`（随后清空对话、关闭电脑），次日 `buildSystemPrompt()` 把最近一条总结作为 `[Yesterday's Summary]` 注入系统提示——角色以"昨天的自己"为起点继续工作。此外每个角色都有按时间戳追加的**活动日志**（`data/journals/<role_id>.md`：任务、工具调用、消息、WAIT 转换、事件接受/跳过），把全队的所作所为集中到可通读的文本里。

**（English）** A role's memory is organized in three cost/lifetime tiers aligned with the work rhythm: (i) **intra-day short-term memory** — the day dialogue of §3.5, capped at 24 000 chars; (ii) **cross-day persistent memory** — notes (notes live on the role's own computer/note store and, with a `remind_tick`, become timed events) and todo lists (`todo_*`); (iii) **daily summaries** — at SHIFT_END each role compresses the day's work into a summary file (`summaries/<day>.md`), after which the dialogue is cleared and the computer powered off; the next day `buildSystemPrompt()` injects the most recent summary as `[Yesterday's Summary]`, so the role resumes as "yesterday's self". Every role additionally appends to a timestamped **activity journal** (`data/journals/<role_id>.md`: tasks, tool calls, messages, WAIT transitions, accepted/skipped events), which collects the whole team's doings into readable text.

### 3.7 拟真公司协作拓扑 / A Believable Corporate Collaboration Topology

**（中文）** 角色属于**组**（领导组、前端/后端/移动/全栈开发组、测试组、安全组……），通信渠道与组绑定：同组内用 `talk`（即时消息，角色进入 WAIT 同步等待回复，用条件变量唤醒），跨组必须走**公司邮件**（默认虚拟邮箱，可配真实 SMTP，失败显式报错而非静默丢信；送达即向收件人投递定向 NEW_MAIL 事件）。领导组成员额外拥有 `talk_to_client`：通过 `AgentSystem` 注入的 `Input`（`StdInput` 控制台 / `WebInput` 网页输入框，默认 20 分钟回复超时）与**真实客户（人类）** 对话，全公司由 `ClientCommunicationLock` 保证同时只有一人能与客户交谈。**招聘即上线**：HR 调用 `post_job_posting` 后，`RoleFactory` 从模板与姓名池生成新角色，`addRoleAndStart` 动态注册并**立即开工**（配齐默认工具集、MCP 文件工具与个人电脑），新员工无组归属、可与任何人 `talk`。系统还植入两类**防死锁的运行时干预**：SHIFT_END 唤醒所有 WAIT 者（对方已下班，不会回复），收尾宽限超时后强制翻页——同步等待语义与作息纪律相互配合，保证"等回复"不会让全队卡在下班点。

**（English）** Roles belong to **groups** (Leadership, frontend/backend/mobile/full-stack development, testing, security…), and communication channels are group-bound: same-group members chat via `talk` (instant messaging; the caller enters WAIT and blocks on a condition variable until the reply wakes it), while cross-group communication must go through **company email** (a virtual mailbox by default, optional real SMTP where failures surface as errors instead of silently dropping mail; delivery dispatches a directed NEW_MAIL event to the recipient). Leadership members additionally get `talk_to_client`: they converse with the **real human client** through the `Input` channel injected into the `AgentSystem` (`StdInput` console / `WebInput` browser box, 20-minute reply timeout by default), and a global `ClientCommunicationLock` guarantees only one person talks to the client at a time. **Hiring means going live immediately**: after HR calls `post_job_posting`, `RoleFactory` creates a role from templates and a name pool, and `addRoleAndStart` registers it dynamically — fully equipped with default toolkits, MCP file tools and a personal computer, able to work at once (new hires belong to no group and may `talk` to anyone). Two deadlock-preventing runtime interventions complete the design: SHIFT_END wakes every WAIT role (their counterpart is off duty and will not answer), and the wrap-up grace timer forces the rollover — synchronous wait semantics and work-rest discipline cooperate so that "waiting for a reply" can never stall the whole team at the bell.

### 3.8 全链路可观测 / End-to-End Observability

**（中文）** 框架把"多智能体行为不可见"当作一等问题。三层观测：**角色日志**（§3.6 的活动日志，文本可 grep）；**任务级 token 记账**（每个任务记录 `tokensConsumed`）；**零依赖 Web UI**：`ChatWebServer` 只用 JDK `com.sun.net.httpserver` + 静态资源，通过轮询 `GET /api/state`、增量拉取 `GET /api/messages?since=N`、提交 `POST /api/reply` 与心跳 `POST /api/attach` 工作。前端按消息类型渲染：💬 `talk`/`client` 聊天气泡、🧠 `reason`（模型的 `reasoning_content` 思维链）、✎ `note` 叙述、🛠 `tool` 调用卡片（工具名 + 参数 + 结果，含结构化 `extra` 元数据）、✔/✗ `answer` 最终结论（含 token 数）。左栏展示全体动态流与各组成员/未读，右栏按组渲染会话——观察者（人）可以实时看到"谁在想什么、调了什么工具、得出什么结论"，把智能体团队的调试从黑盒变成白盒。前端还直接承载"客户"输入通道。

**（English）** The framework treats "invisible multi-agent behavior" as a first-class problem with three observation tiers: **role journals** (§3.6, greppable text); **per-task token accounting** (each task records `tokensConsumed`); and a **zero-dependency Web UI** — `ChatWebServer` built only on the JDK's `com.sun.net.httpserver` plus static assets, driven by polling `GET /api/state`, incremental `GET /api/messages?since=N`, `POST /api/reply` and the `POST /api/attach` heartbeat. Messages are rendered by kind: 💬 `talk`/`client` bubbles, 🧠 `reason` (the model's `reasoning_content`), ✎ `note` narration, 🛠 `tool` call cards (name + args + result, with structured `extra` metadata) and ✔/✗ `answer` finals (with token counts). The left panel shows the whole-company activity feed and per-group member counts/unread badges; the right panel renders per-group sessions — a human observer watches in real time *who is thinking what, which tools are called, and what conclusions emerge*, turning agent-team debugging from black box to white box. The frontend also carries the human client's input channel.

### 3.9 持久化与断点续跑 / Persistence and Resumable Runs

**（中文）** `StateStore` 把全部可序列化状态聚合进**单个 JSON 文件**（`data/state.json`，原子写）：角色档案、任务历史与未完成（排队）任务、电脑/容器绑定（已有容器**复用绑定**而非重建）、时钟（第几天 / 日内 tick / 第 1 天的日历 `base_date`），以及每角色的打开对话。`Main` 退出时自动存档（Ctrl+C 亦如此）、启动时自动恢复，因此一次仿真可以**从停下的日历日期和 tick 原位继续**，第二天照常从 08:00 开工；运行中断不会把昂贵的 LLM 进度清零。

**（English）** `StateStore` aggregates all serializable state into a **single JSON file** (`data/state.json`, atomic writes): role profiles, task history and queued (incomplete) tasks, computer/container bindings (existing containers are **re-bound, not rebuilt**), the clock (day / tick-of-day / day-1 calendar `base_date`), and each role's open conversation. `Main` auto-saves on exit (Ctrl+C included) and auto-restores on startup, so a simulation resumes *in place* on the same calendar date and tick — the next day simply starts at 08:00, and no expensive LLM progress is lost to interruption.

### 3.10 工程化：角色即数据、供应商目录、一进程多公司 / Engineering: Roles-as-Data, Provider Catalog, Multi-Company per JVM

**（中文）** 若干工程决策使框架可被当作"平台"而非"演示"使用：**角色即数据**——全部 55 个角色模板为 JSON（姓名/拼音用户名/职务/职责/性格/技能/兴趣关键词/显著性阈值/组/邮箱……），新增角色不改代码；**LLM 供应商目录**——`ProviderManager` 内置 18 家提供商（OpenAI、Anthropic、Gemini、DeepSeek、Mistral、Groq、OpenRouter、xAI、Moonshot、智谱、DashScope、SiliconFlow、Ollama、vLLM、LM Studio 等）的 base URL、请求路径与两种线上格式（OpenAI/Anthropic），本地 `providers.json` 可覆盖扩展，`/models` 归一化为 `ModelInfo`；`OpenAICompatLLM` 采用"构造参数 > 系统属性 > 环境变量 > 配置文件 > 默认值"的分层解析，并带 429/5xx/超时重试；**每个角色一个虚拟线程**（JDK 21+），不再受平台线程池上限约束；**一进程多公司**——`AgentSystem` 自包含 + 独立数据目录，可并行运行多个互不干扰的"公司"（角色模板注册表与宿主机 Podman 基建仍为进程级共享，属有意为之）。自动化测试因此覆盖到隔离性与时间语义，而不仅是"LLM 能聊天"。

**（English）** Several engineering decisions turn the framework into a platform rather than a demo: **roles as data** — all 55 role templates are JSON (name/pinyin username/title/responsibilities/personality/skills/interest keywords/salience threshold/group/email…); adding roles needs no code. **LLM provider catalog** — `ProviderManager` ships 18 providers (OpenAI, Anthropic, Gemini, DeepSeek, Mistral, Groq, OpenRouter, xAI, Moonshot, Zhipu, DashScope, SiliconFlow, Ollama, vLLM, LM Studio, …) with base URLs, request paths and one of two wire dialects (OpenAI/Anthropic); a local `providers.json` can override/extend the catalog, and each provider's `/models` endpoint is normalized into `ModelInfo`. `OpenAICompatLLM` resolves configuration through a layered chain (constructor args > system properties > environment > config file > defaults) with retry semantics for 429/5xx/timeouts. **One virtual thread per role** (JDK 21+) removes platform-thread pool caps. **Multiple companies per JVM** — because `AgentSystem` is self-contained with its own data directory, several non-interfering "companies" can run in parallel (the role-template registry and host podman infrastructure stay process-wide by design). Consequently, the automated test suite covers isolation and time semantics, not merely "the LLM can chat".

## 4 主要创新点归纳 / Summary of Main Innovations

**（中文）** 结合第 1 节的四类经典难题，下表将创新点映射到"机制 — 解决什么问题 — 为什么优于常见做法"。表后对每一项给出展开分析。

| 创新点 | 机制 | 解决的难题 | 相对常见做法的差异 |
|---|---|---|---|
| C1 作息时钟调度 | 忙时实时推进 / 闲时快进 / 下班总结 / 翻页门控 + 宽限强推 | 忙等、死锁、真实时间浪费 | 常见框架由"角色主动 poll 或对话轮次"驱动；这里由**全局时钟 + 事件表**驱动，角色空闲即停止消耗，且"天"成为强制的上下文与状态边界 |
| C2 零 token 过滤 | 状态掩码 → 显著性评分 → 唤醒的三层管线 | token 成本随团队规模失控 | 常见做法是把事件文本塞进每个角色的 prompt 判断；这里在**进入 LLM 之前**以纯字符串运算拦截，并把接受率做成可观测统计 |
| C3 一人一机 | 每角色一个容器/个人电脑，OS 权限 + 云盘 + 随作息开关机 | 权限与工具环境混用 | 常见做法是所有角色共享工作目录、靠提示词约定边界；这里把隔离下沉到容器/文件系统，工具（含 MCP）天然服从角色边界 |
| C4 有界上下文会话 | 跨任务连续性 + 字符预算压缩 + 按天开闭 + 工具回顾上限 | 上下文爆炸、跨任务失忆 | 常见做法是任务内塞满历史或依赖模型窗口；这里用**确定性预算 + 摘要兜底**保证上下文只缩不涨，且记忆随"下班"自然分段 |
| C5 状态可恢复 | 单文件原子存档 + 日历锚定 + 容器复用 + 对话原位恢复 | 崩溃即归零 | 常见做法是对话仅存内存；这里把时钟、队列、对话、容器绑定一次性落盘，中断后按同一日历时间继续 |
| C6 拟真协作拓扑 | 组内 talk / 跨组邮件 / 定向 NEW_MAIL / 动态招聘 / 客户在环 | 流水线式协作缺乏长期性 | 常见做法是静态成员 + 固定流水线；这里组织是**动态**的（可招聘、可成长），并带防死锁的 WAIT 语义 |
| C7 全链路可观测 | 角色日志 + token 记账 + Web 痕迹流（reason/note/tool/answer） | 多智能体行为黑盒 | 常见做法只看最终输出；这里暴露思维链、工具调用参数与结果、token 消耗的**结构化增量流** |
| C8 工程可扩展 | 角色即数据、18 家供应商、虚拟线程、多实例自包含 | 演示级、单供应商、单实例 | 常见框架面向单场景；这里把配置数据化、接口标准化，一个 JVM 可并行跑多家公司做对照实验 |

**（English）** Mapping the innovations back to the four classic problems from §1: the table above connects each innovation to its mechanism, the problem it solves, and why it improves on common practice. In brief: **C1** replaces role-driven polling/chat turns with a global clock plus event table, so idle agents cost nothing and "the day" becomes a hard context/state boundary; **C2** intercepts irrelevant events as pure string operations *before* the LLM, making acceptance rates observable statistics; **C3** pushes permission isolation down to containers/filesystems (including MCP servers) instead of prompting conventions; **C4** guarantees context only shrinks via a deterministic budget with summarization fallback and segments memory by shift; **C5** archives clock, queues, dialogues and container bindings in one atomic JSON so runs resume at the same calendar time; **C6** makes the organization dynamic (hiring, growth) with deadlock-free WAIT semantics and a human-in-the-loop client channel; **C7** exposes chain-of-thought, tool calls and token usage as a structured incremental stream; **C8** data-ifies roles, standardizes provider access, and lets multiple self-contained companies run side by side in one JVM for comparative experiments.

### 4.1 C1 展开分析：为什么"作息"是一个调度原语 / Why "Work Shifts" Are a Scheduling Primitive

**（中文）** 常见的长期智能体以 `while(true)` 或消息轮次驱动，隐含三个假设：(a) 智能体应该持续活跃；(b) 会话没有天然断点；(c) 时间只是单调递增的序号。本项目反其道而行：**空闲不是 bug，而是需要被快进跳过的时间**；**下班是上下文与状态的硬边界**；**时间是日历化的**（角色读到的是 "2026-09-07 14:23:10 Day 3"，能理解"下午茶后再联系"这类调度语义）。由此获得三个连锁收益：token 支出与"实际发生的工作量"成正比而不是与"墙钟运行时长"成正比；每个角色的上下文以"天"为上限而非"任务链"为上限；崩溃/重启的恢复粒度是"天/时刻"而不是"从头再来"。作息纪律（同一时间只做一个任务、下班必须总结）事实上扮演了智能体"操作系统"中的**抢占式调度与内存换页**：任务在队列中按优先级等待，对话在日界线上被换出（摘要持久化）与换入（次日冷启动）。

**（English）** Typical long-lived agents are driven by `while(true)` loops or message rounds, which implicitly assume agents should be continuously active, that conversations have no natural breakpoint, and that time is just a monotone counter. This project inverts all three: *idleness is not a bug but time to be fast-forwarded over*; *the bell is a hard context and state boundary*; *time is calendarized* (a role reads "2026-09-07 14:23:10 Day 3" and can honor scheduling semantics such as "contact me after the afternoon break"). Three benefits cascade: token spend is proportional to *work actually done* rather than wall-clock uptime; each role's context is bounded by *a day* rather than by a task chain; and crash/resume granularity is *day/moment* rather than "from scratch". The work-rest discipline (one task at a time; a mandatory summary at close) effectively implements preemptive scheduling and memory paging inside an "agent operating system": tasks queue by priority, and dialogues are paged out at the day boundary (summarized, persisted) and paged back in the next morning.

### 4.2 C4 展开分析：上下文预算的"只缩不涨"不变量 / The "Context Only Shrinks" Invariant

**（中文）** 无论采用多大窗口的模型，长期运行都需要回答"对话历史放多少"。本项目给出的答案是量化而确定性的：历史按字符计费（24 000 默认），越界触发一次压缩——用**一次**摘要调用把 N 条消息换成 1 条（≤12 000 字符）；摘要失败则按"丢弃最旧 → 硬截断"的次序兜底。压缩发生在提交边界（任务完成时）而非请求路径上，避免额外延迟；压缩成本与越界次数成正比，而越界次数随预算增大而稀疏。配合工具回顾上限（每任务 ≤6 条、每条 80/200 字符封顶），"模型记得自己调过什么工具"的成本也有界。该设计可测试：`ConversationTest` 直接验证压缩触发、摘要成功/失败两条路径与字符上限。

**（English）** Whatever the model window, long-running operation must answer "how much conversation history to keep". This project answers with a quantitative, deterministic rule: history is charged in characters (24 000 default); crossing the budget triggers compaction — one summarization call replaces N messages with a single message (≤12 000 chars); on failure the fallback chain drops the oldest messages then hard-truncates. Compaction runs at commit boundaries (task completion), not on the request path, so it adds no latency; its cost is proportional to the number of budget crossings, which grow sparse with the budget size. Combined with the tool-recap caps (≤6 calls per task, 80-char args / 200-char results), even "the model remembers which tools it used" has a bounded cost. The design is directly unit-testable: `ConversationTest` verifies compaction triggers and both the success and failure paths.

### 4.3 C3 展开分析：把隔离当作一等公民 / Isolation as a First-Class Citizen

**（中文）** 在多智能体系统中，"谁有权读写什么"通常靠提示词约定——这在只有一个工作区时既危险（互相覆盖）又不可复现（提示词漂移）。本项目用**一台电脑 = 一个角色**把问题物理化：容器即权限边界，宿主目录双向可见便于人工检查，云盘以文件系统权限表达共享/私有；`local` 与 `ssh` 两种实现保证没有容器环境也能跑。更重要的是**生命周期耦合**：电脑随作息开关机，MCP 服务器在容器内随开机探测自愈——环境不是一次性搭好就放着，而是随"员工"的上下班节奏受管。这让"角色 A 的工具安装影响角色 B"在结构上不可能发生，也让多公司实例（3.10）可以安全共存。

**（English）** In multi-agent systems, "who may read/write what" is usually settled by prompting conventions — dangerous (mutual overwrite) and unreproducible (prompt drift) in a shared workspace. This project physicalizes the problem: **one computer = one role**. The container is the permission boundary; the host directory is bidirectionally visible for human inspection; the cloud drive expresses sharing/privacy with filesystem permissions; `local` and `ssh` implementations keep the system runnable without any container runtime. What matters most is the **lifecycle coupling**: computers are powered by the shift schedule, and MCP servers self-heal inside containers at boot probe — environments are managed resources that follow an "employee's" working hours, not one-shot setups. Structurally, "role A's tool installation affects role B" becomes impossible, which is also what lets multiple company instances (§3.10) coexist safely.

## 5 系统验证与运行案例 / Verification and Runtime Case Study

**（中文）** 本项目尚不以"端到端任务质量基准"为目标（缺少与 MetaGPT/ChatDev 等的标准化多日对照评测），因此本节报告三类已有的、可复现的验证：自动化测试、设计层面成本分析、以及仓库内真实运行产物的观察。作者认为，对以"运行期机制"为创新点的系统，这类验证恰当地覆盖了机制正确性。

**（English）** The project does not yet target end-to-end task-quality benchmarks (no standardized multi-day comparison against MetaGPT/ChatDev et al.), so this section reports three kinds of existing, reproducible verification: the automated test suite, an analytical cost model, and observations from real run artifacts in the repository. For a system whose innovations are *runtime mechanisms*, such verification appropriately targets mechanism correctness.

### 5.1 自动化测试概况 / Automated Test Suite

**（中文）** 仓库包含 **27 个测试类、196 项 JUnit 5 测试**，按主题可归类如下（类名均取自 `src/test/java`，可复现）：

| 主题 | 代表性测试类 | 覆盖的机制 |
|---|---|---|
| 时钟与作息 | `TimeManagerTest`、`AgentSystemTimeTest`、`EventBusTest`、`NoteReminderTest` | 忙时推进、闲时快进、翻页门控、收尾宽限强推、笔记提醒转定时事件、跨日任务装载 |
| 会话与上下文 | `ConversationTest`、`ConversationEndToEndTest`、`ConversationStateStoreTest` | 预算压缩（成功/失败路径）、按天开闭、工具回顾上限、存档恢复 |
| 事件与状态 | `StateStoreTest`、`JournalTest` | 单 JSON 存档往返、角色日志 |
| 通信与并发 | `TalkGroupTest`、`TalkWaitTest`、`AgentSystemMailNotifyTest` | 组内 talk 约束、WAIT/回复唤醒、定向 NEW_MAIL、收尾唤醒 |
| Web 与人机协同 | `ChatStoreTest`、`ChatWebServerTest`、`TalkToClientWebTest`、`WebInputTest`、`AgentRoleTraceTest` | 增量消息流、回复协调、思维链/工具/结论痕迹、网页输入超时 |
| 工具与 LLM 层 | `ToolkitsTest`、`LLMRetryTest`、`ProviderManagerTest`、`RoleLoaderJsonTest`、`MailServiceTest` | 工具注册、重试语义、供应商目录解析、模板加载、虚拟邮箱/SMTP |
| 隔离性 | `AgentSystemIsolationTest` | 同一 JVM 多 AgentSystem 互不干扰（时钟/邮箱/电脑/对话） |

其中 `TimeManagerTest`/`AgentSystemTimeTest` 用"测试钩子直接拨动 tick"的方式验证快进与翻页，`ConversationEndToEndTest` 用可注入的 LLM 桩验证跨任务连续性，`AgentSystemIsolationTest` 验证多实例改造（见 `docs/agent-system-multi-instance.md` 中的问题清单与重构方案）。

**（English）** The repository ships **27 test classes with 196 JUnit 5 tests**. Grouped by theme (class names as in `src/test/java`, reproducible): clock/shift semantics (`TimeManagerTest`, `AgentSystemTimeTest`, `EventBusTest`, `NoteReminderTest` — busy flow, fast-forward, rollover gating, grace-period forcing, note reminders as timed events, next-day task loading); conversations/context (`ConversationTest`, `ConversationEndToEndTest`, `ConversationStateStoreTest` — compaction success/failure paths, per-day open/close, tool-recap caps, archive restore); events/state (`StateStoreTest`, `JournalTest`); communication/concurrency (`TalkGroupTest`, `TalkWaitTest`, `AgentSystemMailNotifyTest` — in-group talk rules, WAIT/reply wake-ups, directed NEW_MAIL, shift-end unstick); Web and human-in-the-loop (`ChatStoreTest`, `ChatWebServerTest`, `TalkToClientWebTest`, `WebInputTest`, `AgentRoleTraceTest` — incremental message stream, reply coordination, reasoning/tool/answer traces, web-input timeout); tools and the LLM layer (`ToolkitsTest`, `LLMRetryTest`, `ProviderManagerTest`, `RoleLoaderJsonTest`, `MailServiceTest`); and isolation (`AgentSystemIsolationTest`). The time tests drive ticks directly through test hooks; the conversation end-to-end test verifies cross-task continuity with an LLM stub; the isolation test validates the multi-instance refactor documented in `docs/agent-system-multi-instance.md`.

### 5.2 设计层面的成本分析 / Analytical Cost Model

**（中文）** 设角色数为 N、广播事件数为 E、每角色兴趣关键词与技能词长度有界，则**过滤阶段**为 O(N·E) 次字符串运算、**零 LLM 调用**（系统时间事件与定向事件另有直通语义）；仅被接受的事件（数量 ≈ `roles_activated`）进入 LLM 阶段。每个活跃角色的日内上下文 ≤ 24 000 字符（+每次越界一次摘要调用，摘要 ≤ 12 000 字符）；每任务工具回顾 ≤ 6 条。空闲快进使"墙钟运行小时数"不转化为 token；下班后普通任务被持有到次日，OFF_DUTY 角色的唤醒只能由 EMERGENCY 事件触发。因此全队的 token 支出上界可由 `(激活任务数 × 每任务往返上界) + (摘要/压缩次数 × 固定开销)` 估算——与常见"全员轮询/全员审视事件"的方案相比，成本主项从 O(N·E·窗口) 降为 O(被接受事件)。框架把这些计数器（`total_events / total_tasks_created / roles_activated / roles_skipped` 及每任务 `tokensConsumed`）直接暴露，成本是**可观测可审计**的。

**（English）** With N roles, E broadcast events, and bounded keyword/skill lists, the **filtering stage** costs O(N·E) string operations and **zero LLM calls** (system-time and directed events have their own pass-through semantics); only accepted events (≈ `roles_activated`) reach the LLM stage. Each active role's intra-day context stays ≤ 24 000 chars (plus one summarizing call per crossing, summaries ≤ 12 000 chars); tool recaps per task ≤ 6 entries. Idle fast-forwarding prevents wall-clock uptime from converting into tokens; ordinary queued work is held after the bell, and OFF_DUTY roles can only be woken by EMERGENCY events. The team's token spend is therefore bounded by roughly `(accepted tasks × per-task round-trip bound) + (summary/compaction calls × fixed overhead)` — versus the common "everyone polls / everyone inspects every event" approach, the dominant cost term drops from O(N·E·window) to O(accepted events). Because the framework exposes these counters (`total_events`, `total_tasks_created`, `roles_activated`, `roles_skipped`, per-task `tokensConsumed`), cost is observable and auditable.

### 5.3 运行实录观察 / Observations from Real Runs

**（中文）** 仓库 `data/` 目录保留了多次真实运行（含早期版本）的产物，可作为机制生效的定性证据：`data/computers/<role_id>/` 下 40+ 个角色个人电脑目录各自独立（覆盖 47 名默认员工并含招聘产生的新员工目录，部分角色已生成笔记）；`CEO/notes/第1天-收集项目需求.md` 对应 Day 1 CEO 定时任务（"上班 1 分钟后向客户收集需求"）；`backend_lead/notes/_summary_day_1.md`、`tester_6/notes/_summary_day_1.md` 是下班总结管线的落盘产物；`attacker_3`（安全蓝队"纪安"）的笔记与日志展示了安全组按"值守日"记录的作业方式；`data/journals/` 下 50+ 个角色日志文件记录了任务/工具调用/WAIT 转换等逐条事件；云盘 `data/drive/` 含 `Public` 与以员工中文名命名的私有目录——印证"共享/私有以文件系统表达"的设计。需要说明：这些产物来自**前期的多次试运行（部分为早期版本与中文运行）**，不是受控基准实验，仅用于说明机制真实生效；对交付质量与成本的定量结论有待专门实验。

**（English）** The `data/` directory preserves artifacts of several real runs (some from earlier versions) as qualitative evidence that the mechanisms work: 40+ roles each have an independent computer directory under `data/computers/<role_id>/` (covering the 47 default employees plus a hired newcomer); `CEO/notes/第1天-收集项目需求.md` corresponds to the CEO's Day-1 timed task (gather client requirements one minute after the morning bell); `backend_lead/notes/_summary_day_1.md` and `tester_6/notes/_summary_day_1.md` are outputs of the shift-end summary pipeline; the security/blue-team role's notes and journal show duty-day-style record keeping; 50+ role journal files under `data/journals/` log per-entry tasks, tool calls and WAIT transitions; and the cloud drive `data/drive/` contains `Public` plus per-employee private directories — confirming the "sharing/privacy via filesystem" design. Caveat: these artifacts come from earlier ad-hoc runs (some in earlier versions and in Chinese), not from a controlled benchmark; quantitative conclusions about delivery quality and cost await dedicated experiments.

## 6 局限性与未来工作 / Limitations and Future Work

**（中文）**

1. **缺少端到端质量基准**：未与 MetaGPT/ChatDev/AutoGen 等在标准软件任务上的多日对照评测；"公司作息"对交付质量的净收益尚无定量证据。未来可定义多日任务集（含事件注入与中途打断）做系统评测。
2. **显著性是启发式的**：关键词 + 技能词 + 紧急词加权在 0 token 前提下高效，但对"语义相关但用词不同"的事件会漏报或误报。未来可用小模型嵌入或缓存打分替代字符串匹配，保持过滤阶段低成本。
3. **工具轮数与 token 上限当前放宽**：代码保留 `MAX_TOOL_ROUNDS`/`MAX_TOOL_TOTAL_TOKENS` 等上限常量，但运行时检查已被注释（README 亦言"持续调优"）。"单任务无界工具循环"的风险控制目前依赖作息边界与预算压缩，值得重新启用分级上限。
4. **基础设施前提**：podman 全仿真需要容器运行时与镜像构建；`ssh`/`local` 虽兜底，但失去真实隔离收益。
5. **单宿主扩展与供应商差异**：一进程多公司、虚拟线程适合中等规模，跨主机编排（多公司分布式）未支持；各供应商工具调用与思维链字段差异需要适配层持续维护。
6. **Web UI 为轮询而非推送/流式**：增量 `since` 轮询对观测够用，大团队高频率下可用 SSE/WebSocket 平滑。
7. **提示词工程依赖**：角色职责/公司文化大量编码在系统提示中（如"主分支必须可用""不要打扰休息的同事"），其质量直接影响行为；把规则迁移为结构化约束（如可执行的代码评审门禁）是自然延伸。

**（English）**

1. **No end-to-end quality benchmark.** No controlled multi-day comparison against MetaGPT/ChatDev/AutoGen on standard software tasks exists; the net quality gain of the shift discipline is unquantified. A multi-day task suite with injected events and mid-run interruptions is the obvious next step.
2. **Heuristic salience.** Keyword/skill/urgency weighting is efficient at zero tokens but misses or misfires on semantically related events phrased differently. Embedding-based or cached scoring could replace string matching while staying cheap.
3. **Tool-round and token caps are currently relaxed.** Constants such as `MAX_TOOL_ROUNDS` / `MAX_TOOL_TOTAL_TOKENS` exist but their runtime checks are commented out (the README admits ongoing tuning). Risk control for long single-task tool loops currently relies on the schedule boundary and compaction; tiered caps should be reinstated.
4. **Infrastructure prerequisites.** Full podman simulation needs a container runtime and image builds; `ssh`/`local` fallbacks lose the real isolation benefits.
5. **Single-host scaling and provider divergence.** Virtual threads and multi-company-per-JVM suit medium scale; cross-host orchestration is unsupported, and tool-calling/reasoning-field differences across providers need ongoing adapter maintenance.
6. **Polling-based Web UI.** Incremental `since` polling suffices for observation; SSE/WebSocket would smooth large-team, high-frequency traffic.
7. **Prompt-engineering dependence.** Company culture is largely encoded in system prompts ("the main branch must stay usable", "do not disturb resting colleagues"); migrating such rules into structural constraints (e.g., executable review gates) is a natural extension.

## 7 结论 / Conclusion

**（中文）** 本文梳理并分析了 AgentSoftware——一个以"公司作息 + 事件驱动"为组织原则的多角色 LLM 智能体仿真框架。其核心判断是：长期多智能体系统的主要瓶颈不在单次对话质量，而在**运行期机制**——何时唤醒（零 token 过滤）、记住多少（有界会话与按天生命周期）、在哪执行（一人一机）、如何恢复（原子存档与日历锚定续跑）、如何观察（日志与 Web 痕迹）。把这些问题转译成可测试的时钟、预算、容器与存档机制后，系统获得了几项可论证的性质：无关事件不消耗 token；角色上下文以天为界且只缩不涨；日循环在宽限机制下不可能死锁；中断可从同一日历时刻原位恢复；角色间权限由容器边界保证。框架以 196 项测试覆盖上述机制，并以真实运行产物佐证其生效。我们期待把"作息纪律"作为通用调度原语推广到更多多智能体场景，并以多日基准评测其收益。

**（English）** This paper analyzed AgentSoftware, a multi-role LLM-agent simulator organized around "corporate shifts + events". Its central claim is that long-running multi-agent systems are bottlenecked less by single-turn dialogue quality than by **runtime mechanisms**: when to wake (zero-token filtering), how much to remember (bounded conversations and per-day lifecycles), where to execute (one employee, one computer), how to recover (atomic archives and calendar-anchored resumption), and how to observe (journals and Web traces). Translated into testable clock, budget, container and archive mechanisms, the system exhibits demonstrable properties: irrelevant events cost no tokens; role contexts are bounded by the day and only ever shrink; the daily loop cannot deadlock thanks to grace-based forcing; interrupted runs resume in place at the same calendar moment; and inter-role permissions are guaranteed by container boundaries. The 196-test suite covers these mechanisms, and real run artifacts corroborate their operation. We look forward to generalizing "shift discipline" as a scheduling primitive to other multi-agent settings and to measuring its benefits with multi-day benchmarks.

---

## 附录 A：术语对照表 / Appendix A: Glossary

| 术语（项目内） | 中文含义 | English meaning |
|---|---|---|
| Tick | 时钟最小推进单位（默认 1 Tick = 1 模拟秒） | smallest clock step (default 1 tick = 1 simulated second) |
| SHIFT_START / SHIFT_END | 上班铃（08:00）/ 下班铃（18:00），EMERGENCY 系统时间事件 | morning / evening bell, system-time EMERGENCY events |
| OFF_DUTY / ON_DUTY_IDLE / ON_DUTY_BUSY / WRAPPING_UP / WAIT | 角色五态：下班 / 在班空闲 / 在班忙碌 / 收尾 / 等待回复 | the five role states |
| fast-forward | 全员空闲 ≥60 s 时时钟跳至下一事件点 | clock jump to the next event once all roles are idle |
| wrap-up / rollover | 下班后的当日总结收尾 / 翻页到次日 08:00 | shift-end summaries / day rollover |
| 3-layer filter | 状态掩码 → 显著性 → 唤醒的事件过滤管线 | state-mask → salience → wake event pipeline |
| salience threshold | 默认 0.4 的唤醒阈值（`score = 0.4×pri + 0.6×rel`） | per-role wake threshold |
| talk / WAIT | 组内即时消息；发送方同步等待回复 | in-group instant message; synchronous reply wait |
| NEW_MAIL | 邮件送达后发给收件人的定向事件 | directed event to a mail recipient |
| conversation / compaction | 角色↔LLM 当日对话；字符预算越界时的摘要压缩 | role↔LLM day dialogue; budget-triggered summarization |
| summary / closeDay | 下班总结工具（保存后关电脑、清对话、OFF_DUTY） | the summary tool (saves, powers off, closes the day) |
| computer / cloud drive | 角色个人电脑（podman/local/ssh）；/mnt/drive 共享云盘 | per-role computer; shared cloud drive |
| StateStore / base_date | 单 JSON 原子存档；第 1 天的日历锚点 | single-JSON archive; calendar anchor of day 1 |
| AgentSystem | 自包含"公司"实例（时钟+角色池+邮箱+…+数据目录） | one self-contained company instance |

---

## 参考文献 / References

**（中文）** 以下文献均为公开发表或公开文档；本项目仓库本身见 [13]。标注 [P] 者为本仓库内部文档，不属外部引用。

1. Wu Q, Bansal G, Zhang J, et al. AutoGen: Enabling Next-Gen LLM Applications via Multi-Agent Conversation. arXiv:2308.08155, 2023.
2. CrewAI. CrewAI: Framework for orchestrating role-playing, autonomous AI agents. https://github.com/crewAIInc/crewAI （开源项目）.
3. Hong S, Zhuge M, Chen J, et al. MetaGPT: Meta Programming for a Multi-Agent Collaborative Framework. ICLR 2024. arXiv:2308.00352.
4. Qian C, Liu W, Liu H, et al. ChatDev: Communicative Agents for Software Development. ACL 2024. arXiv:2307.07924.
5. Chen W, Su Y, Zuo J, et al. AgentVerse: Facilitating Multi-Agent Collaboration and Exploring Emergent Behaviors in Agents. ICML 2024. arXiv:2308.10848.
6. Li G, Hammoud H A A K, Itani H, et al. CAMEL: Communicative Agents for "Mind" Exploration of Large Language Model Society. NeurIPS 2023. arXiv:2303.17760.
7. Park J S, O'Brien J C, Cai C J, et al. Generative Agents: Interactive Simulacra of Human Behavior. UIST 2023. arXiv:2304.03442.
8. Packer C, Wooders S, Lin K, et al. MemGPT: Towards LLMs as Operating Systems. arXiv:2310.08560, 2023.
9. Ge Y, Ren W, Luo J, et al. AIOS: LLM Agent Operating System. arXiv:2403.16971, 2024.
10. Yao S, Zhao J, Yu D, et al. ReAct: Synergizing Reasoning and Acting in Language Models. ICLR 2023. arXiv:2210.03629.
11. Anthropic. Model Context Protocol (MCP). https://modelcontextprotocol.io （2024 年 11 月发布）.
12. Pressler R, Bateman A. JEP 444: Virtual Threads. OpenJDK, JDK 21, 2023. https://openjdk.org/jeps/444.
13. imitoy. AgentSoftware: Shift & Event-Driven Agent Scheduler（本论文分析对象，commit 68e4b58）. https://github.com/imitoy/AgentSoftware
14. [P] AgentSoftware 仓库：README.md、docs/agent-system-multi-instance.md、docs/llm-provider-manager.md、src/main/resources/{role_templates.json, providers.default.json, mcp_group_rules.json}、src/test/java（27 类 / 196 测试）。

---

> **收尾说明 / Closing note**：本文为内部草稿（不投稿）。若需后续投稿，请补充：作者与单位、正式的贡献声明、多日基准实验（建议对照 MetaGPT/ChatDev 的同类任务）、消融实验（分别关闭作息快进 / 过滤 / 会话压缩后的成本与质量变化）、以及图表编号与期刊模板排版。
>
> This is an internal draft (not for submission). Before any future submission, add: authors and affiliations, a formal contributions statement, multi-day benchmark experiments (ideally against MetaGPT/ChatDev on comparable tasks), ablations (cost/quality changes when disabling fast-forward / filtering / conversation compaction), and journal-template formatting with numbered figures/tables.
