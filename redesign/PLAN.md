# AgentSoftware 重构方案 v3 —— 骨架设计（只定义结构，不写实现）

> 基线：`master` 分支的**行为语义**（这是唯一真实运行过的版本）。
> 反面教材：`refactor` 分支（"分层"了，但依然是一坨；见 §2）。
> 本轮产出：包结构 + 抽象类/接口 + **每个函数做什么**的签名清单（§5），实现留空。
> 待你拍板的问题见文末 §10。

---

## 1. master 架构体检

### 1.1 症状：四个上帝对象

| 类 | 行数 | 一个人干了哪些互不相关的事 |
|---|---|---|
| `AgentRole` | 1173 | 人设/spec、状态、优先队列、LLM 调用、工具注册表、活动日志(journal)、Web trace、个人电脑、笔记、待办、对话历史、`talk` 等待协议、工具循环、System Prompt 拼装、事件过滤 |
| `TimeEventBus` | 920 | 时钟状态、日历换算、定时事件表、**时钟线程**、班次事件、idle/busy/rollover/force 四个回调、任务调度、进程级默认单例 |
| `AgentSystem` | 562 | 组合根 + 运行时 + 策略：clock/pool/dispatcher/config/computers/mail/mcp/skills/clientLock/chatStore/conversations/input/dataDirs/pause，以及班次反应与邮件通知 |
| `RolePool` | 398 | 花名册、**工作线程**、LLM 工厂、工具装配、任务路由、招聘/离职、状态快照 |

证据：`role/AgentRole.java:177-222,330-377,403-457,578-616,624-650,804-908,994-1094`；
`event/TimeEventBus.java:110-136,292-358,696-752,797-824,914-919`；
`AgentSystem.java:48-159,214-260,287-324,334-390,413-437`；`role/RolePool.java:97-155,270-379`。

### 1.2 根因（不是"代码丑"，而是结构性问题）

**(A) 按"对象"切分职责，而不是按"变化原因"切分。**
所以 "角色" 同时等于 人设 + 调度单元 + LLM 客户端宿主 + 文件系统视图 + UI 数据源。任何一个需求变化（换消息总线、换存储、加一种工具、改 Web 协议）都要改同一个类。

**(B) 时序耦合 + 对象环。**
`AgentSystem` 先 `new RolePool(null,null,...)`，再 `new EventDispatcher(pool)`，再 `mailService.setDeliveryListener(this::notifyNewMail)`、`timeManager.setEventSender(this::onTimeEvent)`、`setIdleChecker/setBusyChecker/setRolloverReadyChecker/setRolloverForceHook`（`AgentSystem.java:104-125`）。`AgentRole` 需要 `bindSystem/bindTimeManager/setPool/bindComputer` 之后才算"构造完成"（`role/AgentRole.java:520-527,753-756,958-961,1154`）。构造出的对象在 setter 调用完成前是**非法状态**，没人能在类型上保证这一点。

**(C) 进程级可变单例。**
`ComputerManager.getInstance()`、`MailService.getMailService()`、`ClientCommunicationLock.getInstance()`、`Toolkits.MCP_MANAGER/SKILL_MANAGER`、`TimeEventBus.getDefaultBus()`、`ConversationManager.getDefault()`、`AgentRole.JOURNAL_DIR`、`RoleLoader.TEMPLATES`、`RoleLoader.usedNames`、`Task.seqCounter`、`RetryArbiter.SHARED`。多实例隔离靠"记得传参覆盖默认值"，漏一处就串台；测试之间互相污染。

**(D) 边界全是 `Map<String,Object>`。**
事件 payload、Task context、工具参数、工具 schema、LLM messages、JSON 持久化、Web API 全是裸 Map；`Event.source/eventType`、任务 `status`、消息 `kind` 都是字符串。编译器无法帮你发现 `payload.get("titel")`。

**(E) 领域概念重复建模。**
- 优先级三份：`Types.Priority` 与 `AgentRole.Urgency` 与 `Task.urgency:int`（`core/Types.java:46-68`，`role/AgentRole.java:68-85`）。
- 工具包两份：`tools.Toolkit/Tool`（模板）与 `ToolRegistry.ToolKit/ToolDef`（内部镜像）（`tools/Toolkit.java` vs `role/ToolRegistry.java:72-146`）。
- 失败判定两份：`LLM.LLM_ERROR_MARKERS` 与 `Types.isFailureText`（`llm/LLM.java:17`，`core/Types.java:71-75`）。
- 状态两份：`AgentState.value` 字符串与枚举名。
- 路径基准多份：`Computer.COMPUTERS_ROOT="./data/computers"`、`StateStore.DEFAULT_STATE_FILE`、`PathManager`(XDG)、`AgentSystem.dataDir`、`NoteStore.baseDir`、`SkillManager.skillsDir`。

**(F) 依赖方向倒置 / 泄漏。**
运行时领域模型直接依赖基础设施与表现层：`AgentRole` import `ChatStore/web`、`MailService`、`MCPManager`、`Computer`；`Computer` 又反过来 import `MCPServer/core` 和 `ToolRegistry.ToolDef`（`computers/Computer.java:3-4,44-46`）；适配器反向控制领域：`OpenAICompatLLM.setOnInsufficientBalance`/`setPauseGate` 回调去暂停整个系统（`llm/OpenAICompatLLM.java:157-172`）；Web 服务器直接抓 `AgentSystem.pool`、`RoleLoader.TEMPLATES`、`Toolkits`（`web/ChatWebServer.java:119-133,236-263`）。

**(G) 控制流被切散在对象图上。**
"一个事件该不该投递"分散在 4 处：`EventDispatcher.trigger`（定向 + 定时提醒特例，`event/EventDispatcher.java:41-133`）、`AgentRole.evaluateEvent`（状态掩码 + 显著性，`role/AgentRole.java:330-377`）、`TimeEventBus`（调度与班次）、`RolePool.roleLoop`（下班保留、暂停保留，`role/RolePool.java:270-299`）。
"时钟何时前进"由时钟反向询问 `AgentSystem.allRolesIdle/dayRolloverReady/forceWrapUp`（`AgentSystem.java:122-125`）。

**(H) 线程与生命周期归属模糊。**
`RolePool` 持有 executor + 每角色虚拟线程；`TimeEventBus` 持有自己的线程；`talk wait=true` 在角色线程上阻塞（`role/AgentRole.java:890-908`）；`state` 是普通 public 字段被多线程读写（`role/AgentRole.java:193`）。`start()/stop()` 顺序靠注释约定（`AgentSystem.java:536-547`）。

**(I) 配置与路径分裂。**
`ConfigStore`、`PathManager`、`System.getenv/System.getProperty`（散落在 `AgentSystem`、`ChatWebServer`、`OpenAICompatLLM`、`ProviderManager`、`MailService`）。同一层级出现"sysprop 优先于 env"和"env 优先于 sysprop"两种规则。

---

## 2. 上一轮 `refactor` 为什么"依旧是一坨"

它成功做了一些事（引入 `kernel/domain/ports`、删掉 legacy runtime、加了 ArchGuard），但**把"分层"误当成了"解耦"**：

1. **上帝对象没拆，只是改名**：`app/Application.java` 642 行，同时是组合根、Web state 映射、持久化映射、System Prompt 生成器、招聘门面。`runtime/AgentRuntime.java` 仍同时持有 状态机 + 队列 + `ToolLoop` + `WaitCoordinator` + history + worker 线程。
2. **构造环用代码绕过**：`Application.create` 里用 `final ToolkitCatalog[] catalogHolder = new ToolkitCatalog[1]` / `ClockPort[] clockHolder` 这种"单元素数组 holder"来打破依赖环（`app/Application.java:182-216`）。这正是 master setter 环换了个马甲。
3. **共享可变服务被伪装成端口**：全局唯一 `ToolService`，靠 `tools.bind(spec.id(), catalog.forSpec(spec))` 按 roleId 绑定（`app/Application.java:190`）——一个隐式的 per-role 注册表，比 master 的 `AgentRole.tools` 更难排查。
4. **端口一对一映射实现，等于给实现类改个名**：`MailPort`→只有一个 `MailServiceAdapter`；`StateRepository`↔`TaskSnapshot` 与 `domain/Task` 形成两份 DTO，来回手工映射（`app/Application.java:464-483`）。
5. **领域仍然不是纯的**：`domain/Payload` 仍是 `Map<String,Object>`；`domain/Task` 与 `StateRepository.TaskSnapshot` 概念重复。
6. **遗留物没清理**：`computers/` 旧包被 `adapters` 直接 import（`adapters/computer/ComputerAdapters.java`），新架构里还住着旧世界。
7. **关键策略仍然分散**：生命周期投递（`DeliveryPolicy`）、暂停（`LifecycleCoordinator`）、时钟（`ClockEngine`/`ClockService`）、LLM 余额自动暂停回调，各自为政，没有统一的"决策层"。

补充证据（详见 `audit/refactor-critique.md`）：

8. **架构护栏本身是失效的**：`ArchGuardTest` 的 `ALLOWED` 只覆盖 `kernel/config/domain/ports/runtime` 五个包（`ArchGuardTest.java:29-34`），`adapters/app/tools/computers` 完全没查，而且只扫 `import` 行（`:65-79`）。所以 `computers/Computer.java:3` import `adapters.mcp.MCPServer` 这种实打实的越层，护栏看不见。**"分层"是写在 README 里的，不是编译器保证的。**
9. **投递策略是装饰性的**：`DispatchService` 算出 `DeliveryMode` 后无条件 `submit`（`:57-59`），真正的"暂存"逻辑在 `AgentRuntime.isHeld` 里另写了一份（`:202-208`）；测试 `TeamAndDispatchTest.java:100-109` 还把"暂存却照样排队"这个假象固化了。
10. **没有析构负责人**：`ComputerManager.destroy`（`:310`）和 `ToolService.unbind`（`:45`）零调用者；`Application.stop()`（`:321-333`）只停 clock/team/web，且 `stopAll` 只置位+interrupt、从不 join（`AgentRuntime.java:88-96`），随后立刻存快照，与仍在写 history 的 worker 竞争。
11. **`AgentState` 有六个写者**：`AgentRuntime.runTask`、`LifecycleCoordinator`、`TeamRuntime.waitForReply/setState`、`Application.restoreState`，外加两个以回调形式注入工具里的写者（`Application.java:231-234`）；`WRAPPING_UP` 从未被赋值。
12. **`RoleSpec`/`Task` 与 Map 的手工映射各出现 4 处 / 2 处**（`RoleSpecLoader:146-174`、`JsonStateRepository:102-141`、`RoleSpecFactory:66-87`、`Application:464-483`），两份 DTO 链。

一句话：**分层解决的是"能不能 import"，解决不了"谁拥有这份状态、谁做这个决定"。**

### 2.1 master 基础设施层的补充发现（详见 `audit/master-infra.md`）

- **两个进程级单例打架**：`PodmanComputer.getLanIp()/ensureContainer()` 绕开注入的 `ComputerManager` 实例，直接调 `ComputerManager.getInstance()`；`networkName` 因此对非默认实例无效。
- **两套路径制度并存**：`PathManager`（XDG/环境变量）只服务于 `ConfigStore`；`StateStore/NoteStore/TodoStore/MailService/Computer` 全部硬编码 `./data/...`。
- **只有一处跨进程锁**：`ComputerManager` 的镜像锁用 `FileLock`；`ConfigStore/StateStore/NoteStore/TodoStore/MailService` 都是无锁 read-modify-write，且 `Json.atomicWrite` 的临时文件名固定（`.<name>.tmp`），并发写会互相覆盖。
- **Web 无鉴权、默认 `0.0.0.0:8787`**，且 `ChatStore` 只有**一个全局客户回复槽**，两个角色同时 `talk_to_client` 会互相拒绝。
- **邮件语义缺陷**：SMTP 失败会直接返回，导致虚拟邮箱副本也不落；而 SMTP 模式下内部副本照落。
- **`NoteStore.sanitizeTitle` 会把不同标题映射到同一文件名**（静默覆盖）；`TodoStore` id 取 UUID 前 8 位。
- **每个 MCP 会话两条平台线程**（`MCPServer`），`Computer.mcpTools/mcpServers` 无同步。

---

## 3. 目标：从"分层"转向「所有权 + 能力」

三条不变量（整个设计围绕它们）：

1. **单一所有者（single writer）**：每一份可变状态只有一个对象拥有，其余人只能通过该对象的方法改它。所有依赖**构造期注入**，没有 `bindXxx`/`setXxx` 晚绑定，没有对象环。
2. **决策与机制分离**：决策是**纯函数**（给事实 → 给决定，不碰 I/O、不起线程），机制是**有副作用的编排**（线程、进程、HTTP、文件）。决策类型跟它所属的功能放同一个包，不再单独设一层。
3. **边界有类型**：跨模块只传不可变值对象/记录；`Map<String,Object>` 只允许出现在 JSON 编解码内部。
4. **一个包 = 一个功能**：功能内的**接口、实现、领域类型、LLM 工具**放在一起，不按"层次"切包（详见 §4 与 §5.9）。

依赖规则（单向，`→` 即"可以 import"）：

```
kernel  ←  所有包                     （Id/Text/Payload/JsonSchema/DomainError/Tick/DayTick，叶子）

tool.spi        → kernel
llm             → kernel, infra.config, tool.spi
transcript      → kernel
infra.json      → kernel          infra.config → （无）
tool.<能力>      → kernel, tool.spi, agent.* 的接口定义, sim.clock / sim.event
sim.event       → kernel            （只剩事件数据 + EventSink）
sim.clock       → kernel, sim.event
agent.*         → kernel, tool.spi, llm, transcript, sim.clock, sim.event, agent.*
company         → kernel, agent.*, sim.*, infra.json, infra.config
web             → company, transcript, infra.config
bootstrap       → 全部（唯一允许认识所有东西的地方）
```

关键单向约束：
- `sim`（时钟与事件）不认识 `agent`：它只对外暴露 `Sensors` / `TickObserver` / `EventSink` 三个端口，实现方在 `agent` / `company`。
  （v3.2 之前不是这样：`DeliveryContext` 里塞了 `AgentState`+`RoleSpec`，把投递决策留在了 `sim.event`，形成 `agent ↔ sim` 环。现在投递策略归 `agent.dispatch`。）
- `tool.spi` 只依赖 `kernel`：工具包不靠"上下文对象"取能力，全部构造期注入。
- `tool.*` 可以依赖 `agent` 的**定义**（`RoleSpec` / `AgentTasks` / `AgentControl` / `AgentDirectory` / `agent.dialog.DailySummary`），但 `agent` 不依赖任何 `tool.*` 实现，只依赖 `tool.spi`。
- 唯一残留的反向三角：`agent` 持有 `tool.computer.Shell`，而 `tool.computer.ShellRegistry` 需要 `agent.role.RoleSpec`（电脑规格定义在角色定义里）。这是领域本身的形状，不再强行拆。
- 还有一处同 feature 内的父子环：`agent ↔ agent.task`（端口 `AgentTasks`/`AgentControl`/`AgentMailbox`/`LifecycleGate` 住在父包）。要么接受，要么把端口下沉到 `agent.task`，见 §11。

---

## 4. 目标架构总览

> **v3.1（按你的 review 重排）**：原来的 `ports` / `adapters` / `model` / `policy` 四个"层次包"
> 已全部废除。现在**一个包 = 一个功能**，功能内的接口、实现、领域类型、LLM 工具同处一包；
> 大类再往下细分。`Shell`（原 `ports`）+ `LocalShell/PodmanShell/SshShell`（原 `adapters/computer`）
> + `PcToolkit`（原 `tools/builtin`）现在都在 `tool.computer`。

```
com.agent.software
├── kernel/                共享值类型：Ids, Text, Payload, JsonSchema, DomainError,
│                          Tick, DayTick                ← v3.2：Tick/DayTick 从 sim.clock 下放
├── infra/                 基础设施
│   ├── config/            AppConfig, AppPaths, ConfigLoader
│   └── json/              JsonCodec, JacksonJsonCodec
├── tool/                  工具与能力（一个能力一个子包：接口 + 实现 + 领域类型 + LLM 工具）
│   ├── spi/               Tool, Toolkit, Toolbox, ToolSpec, ToolResult
│   ├── computer/          Shell, ShellRegistry, PodmanShell, LocalShell, SshShell,
│   │                      PcToolkit, HermesToolkit
│   ├── note/              Note, NoteBook, JsonNoteBook, NoteToolkit, MemoryToolkit
│   ├── todo/              Todo, TodoList, JsonTodoList, TodoToolkit
│   ├── skill/             Skill, SkillLibrary, JsonSkillLibrary, SkillToolkit
│   ├── mail/              MailMessage, Mailbox, FileMailbox, SmtpSender, EmailToolkit
│   ├── mcp/               McpBridge, StdioMcpBridge, McpToolkit
│   ├── talk/              TeamChannel, TalkService, TalkToolkit
│   ├── client/            ClientChannel, ConsoleClientChannel, WebClientChannel, ClientToolkit
│   ├── hr/                Recruiter, HiringService, HrToolkit
│   ├── time/              TimeToolkit
│   └── task/              TaskViewToolkit
├── agent/                 角色运行时
│   ├── （根）              Agent, AgentFactory, AgentMailbox, AgentState, AgentStateMachine,
│   │                      AgentTasks, AgentControl, AgentDirectory, AgentSnapshot,
│   │                      RoleSnapshot←v3.2 从 role/ 搬来, Team,
│   │                      Staffing, LifecycleGate, WaitCoordinator, ToolboxFactory
│   ├── role/              RoleSpec
│   ├── task/              Task, TaskStatus, TaskRunner, ToolLoop, ToolLoopPolicy
│   ├── dialog/            ConversationMemory, ConversationPolicy, SystemPrompt,
│   │                      DailySummary   ← v3.2 新增：提示词侧只需要这一个方法
│   └── dispatch/          EventRouter, TaskFactory, DeliveryPolicy,
│                          DefaultDeliveryPolicy, SaliencePolicy, KeywordSaliencePolicy
│                                                   ← v3.2：投递策略从 sim.event 搬来
├── sim/                   模拟时钟与事件
│   ├── clock/             Clock, SimClock, ShiftCalendar, ClockPolicy,
│   │                      DefaultClockPolicy, ClockDriver, ScheduleTable, ReminderScheduler,
│   │                      Sensors, TickObserver
│   └── event/             AgentEvent, EventKind, Priority, EventSink
├── company/               公司编排与存档
│   ├── （根）              Company, CompanyView, CompanyStatus, ShiftDirector
│   └── store/             CompanySnapshot, SnapshotStore, JsonSnapshotStore
├── llm/                   LlmClient, Message, ToolCallRequest, OpenAiClient,
│                          ProviderCatalog, ProviderResolver, RetryArbiter
├── transcript/            Transcript, ChatFeed
├── web/                   ChatWebServer
└── bootstrap/             CompanyBuilder, Main, ToolkitCatalog
```

与 master 的对应关系（一句话）：
**把 `AgentSystem` 拆成 `Company`+`EventRouter`+`ShiftDirector`；把 `AgentRole` 拆成 `Agent`+`AgentMailbox`+`AgentStateMachine`+`ConversationMemory`+`WaitCoordinator`+`Toolbox`+`TaskRunner`；把 `TimeEventBus` 拆成 `SimClock`（纯状态）+`ScheduleTable`+`ClockDriver`（线程）+`ClockPolicy`（决策）；把 `RolePool` 拆成 `Team`+`AgentFactory`；把 `StateStore` 变成 `SnapshotStore` + 一个 typed 快照模型。**

---

## 5. 类与函数清单（本轮的核心产出）

> 下面 §5.1–§5.8 的小节标题沿用 v3.0 的分层包名（`kernel`/`model`/`policy`/`ports`/`engine`/`tools`/`adapters`）。
> 类清单本身仍然有效，但**包名以 §4 与 §5.9 为准**：这些类现在按功能散在 `tool.*` / `agent.*` / `sim.*` / `company.*` / `llm` / `transcript` / `web` / `infra.*` 里。

> 下面是**签名级**骨架。`{ ... }` 表示"实现留空"。函数后面用 `//` 说明它**做什么**，不说明怎么做。

### 5.1 `kernel` —— 无业务知识的基础件

```java
// kernel/Id.java
/** 所有领域标识的抽象基类：包装一个非空字符串，值语义（equals/hashCode/compareTo 按 value）。 */
public abstract class Id implements Comparable<Id> {
    protected Id(String value) { ... }          // 校验非空；保存 value
    public final String value() { ... }          // 对外只读值
    protected abstract String typeName();        // 子类返回 "RoleId"/"TaskId"…用于报错与 toString
    // equals/hashCode/toString/compareTo：按 (typeName, value) 值语义
}

// kernel/Ids.java
/** 具体标识类型；集中定义避免十几份重复样板。generate() 用 UUID 短码。 */
public final class RoleId  extends Id { public static RoleId  of(String v); }
public final class TaskId  extends Id { public static TaskId  of(String v); public static TaskId  generate(); }
public final class EventId extends Id { public static EventId of(String v); public static EventId generate(); }
public final class MessageId   extends Id { ... }
public final class NoteId      extends Id { ... }
public final class TodoId      extends Id { ... }
public final class SkillId     extends Id { ... }
public final class MailId      extends Id { ... }

// kernel/Text.java
/** 文本工具：截断/归一化/空白判定；替换散落各处的 truncate(String,int)。 */
public final class Text {
    public static String truncate(String s, int max);
    public static String squashWhitespace(String s);      // 把任意空白折成单空格并 trim
    public static String orEmpty(String s);
    public static boolean isBlank(String s);
    public static String joinNonBlank(String sep, String... parts);
}

// kernel/DomainError.java
/** 领域层统一非受检异常：带机器可读 code，便于上层分类（替代 "[API error:" 之类的字符串嗅探）。 */
public final class DomainError extends RuntimeException {
    public DomainError(String code, String message);
    public DomainError(String code, String message, Throwable cause);
    public String code();
}
```

### 5.2 `model` —— 纯领域数据 + 不变量

```java
// model/RoleSpec.java
/** 一个角色的不可变定义（人设 + 配置）。这是 master AgentRole 里"非运行时"的那一半。 */
public record RoleSpec(
        RoleId id, String name, String username, int uid,
        String title, String responsibilities, String personality,
        List<String> skills, String group, String email, String promptExtra,
        Set<String> interestKeywords, double salienceThreshold,
        ComputerSpec computer, Set<String> toolkits, boolean defaultRole) {
    public record ComputerSpec(String kind, Map<String, String> options) { ... }  // kind: podman|local|ssh
    public boolean hasGroup();
    public static Builder builder();
    public static final class Builder { /* 每个字段一个 withX，build() 校验 id/name 非空 */ }
}

// model/AgentState.java
/** 角色生命周期状态；把"能否接普通活/是否在岗"这类规则内聚，替换散落的 state 判断。 */
public enum AgentState {
    OFF_DUTY, ON_DUTY_IDLE, ON_DUTY_BUSY, WRAPPING_UP, WAITING;
    public boolean onDuty();               // 是否在岗（非 OFF_DUTY）
    public boolean holdsOrdinaryWork();    // OFF_DUTY/WRAPPING_UP/WAITING → true（普通事件暂存）
    public boolean acceptsEmergency();     // 永远 true（EMERGENCY 穿透）
}

// model/Priority.java
/** 事件/任务紧急度唯一来源；消除 Types.Priority / Urgency / int 三份重复。 */
public enum Priority {
    LOW, NORMAL, HIGH, EMERGENCY;
    public int weight();
    public static Priority ofWeight(int w);
}

// model/EventKind.java
/** 事件类型 = (来源, 名称)，带命名常量；替代裸字符串 event.source/eventType。 */
public record EventKind(String source, String name) {
    public static final EventKind SHIFT_START = new EventKind("time", "SHIFT_START");
    public static final EventKind SHIFT_END   = new EventKind("time", "SHIFT_END");
    public static final EventKind TASK_DUE    = new EventKind("task", "TASK_DUE");
    public static final EventKind NEW_MAIL    = new EventKind("email", "NEW_MAIL");
    public String wire();                  // "source/name"，用于日志与持久化
}

// model/Payload.java
/** 不可变、带类型访问器的事件/任务负载；替代裸 Map<String,Object> 跨层传递。 */
public final class Payload {
    public static Payload empty();
    public static Payload of(String key, Object value);
    public Payload with(String key, Object value);
    public Optional<String> string(String key);
    public String stringOr(String key, String fallback);
    public OptionalInt intValue(String key);
    public Map<String, Object> asMap();           // 仅持久化/适配器边界使用
    // 常用键的语义化访问器（避免各处硬编码 key）
    public String title(); public String text(); public String subject();
}

// model/AgentEvent.java
/** 不可变事件：显式收件人集合（空 = 广播）+ 可选触发时刻。替代可变 Types.Event。 */
public record AgentEvent(EventId id, EventKind kind, Priority priority,
                         Set<RoleId> recipients, Payload payload,
                         Optional<Tick> fireAt, Instant occurredAt) {
    public static AgentEvent broadcast(EventKind kind, Priority p, Payload payload);
    public static AgentEvent toRole(RoleId target, EventKind kind, Priority p, Payload payload);
    public static AgentEvent scheduled(EventKind kind, Priority p, Payload payload, Tick at);
    public boolean targeted(); public boolean broadcast();
    public AgentEvent rescheduledTo(Tick at);
}

// kernel/Tick.java, kernel/DayTick.java   ← v3.2 从 sim.clock 下放
/** 绝对 tick 与 (day, tickOfDay) 坐标；把时间算术从 TimeEventBus 里抽出来。 */
public record Tick(long value) { public Tick plus(long ticks); public boolean before(Tick o); }
public record DayTick(int day, int tickOfDay) { }

// model/ShiftCalendar.java
/** 纯日历换算：tick ↔ 时钟文本、班次边界、下一班次起点。无状态、可单测。 */
public record ShiftCalendar(double secondsPerTick, int shiftStartHour, int shiftEndHour) {
    public static ShiftCalendar of(double secondsPerTick, int startHour, int endHour);
    public int ticksPerDay();
    public int shiftEndTick();
    public DayTick locate(Tick absolute);          // 绝对 tick → (第几天, 当天第几 tick)
    public Tick at(int day, int tickOfDay);        // 反向
    public boolean withinShift(DayTick at);
    public String clockTime(DayTick at);           // "HH:MM:SS"
    public Tick nextShiftStart(DayTick at);
    public int ticksUntilShiftEnd(DayTick at);
    public String describe(Tick absolute);         // 给 LLM/日志的一句话
}

// model/TaskStatus.java
public enum TaskStatus { PENDING, RUNNING, DONE, FAILED; public boolean terminal(); }

// model/Task.java
/** 一个待执行任务。定义不可变，执行结果通过显式迁移方法写入（唯一写者=执行它的 Agent）。 */
public final class Task {
    public Task(TaskId id, Priority urgency, String description, EventKind source,
                Payload context, Instant createdAt, RoleId assignee);
    public static Task fromEvent(AgentEvent event, RoleId assignee);
    public TaskId id(); public Priority urgency(); public String description();
    public EventKind source(); public Payload context(); public Instant createdAt();
    public RoleId assignee(); public TaskStatus status(); public String result(); public int tokens();
    public void markRunning();                     // PENDING → RUNNING
    public void complete(String result, int tokens); // RUNNING → DONE
    public void fail(String reason);               // RUNNING → FAILED
    public boolean isOpen();
    public TaskRecord toRecord();                  // 持久化形状（唯一一份 DTO，避免 refactor 的 Task/TaskSnapshot 双份）
    public static Task fromRecord(TaskRecord record);
}

// model/TaskRecord.java
/** Task 的持久化形状（纯数据）。 */
public record TaskRecord(String id, int urgency, String description, String source,
                         Map<String, Object> context, String status, String result,
                         int tokens, double createdAt, String assignee) { }

// model/Message.java
/** 一条 LLM 对话消息（system/user/assistant/tool）。替代 Map messages。 */
public record Message(Role role, String content, List<ToolCallRequest> toolCalls, String toolCallId) {
    public enum Role { SYSTEM, USER, ASSISTANT, TOOL }
    public static Message system(String text); public static Message user(String text);
    public static Message assistant(String text, List<ToolCallRequest> calls);
    public static Message tool(String callId, String content);
}

// model/Note.java, model/Todo.java, model/Skill.java, model/MailMessage.java
/** 领域实体（笔记 / 待办 / 技能 / 邮件）。纯数据，不认识文件系统与 SMTP。 */
public record Note(NoteId id, RoleId owner, String title, String body,
                   Integer remindDay, Integer remindTick, Instant updatedAt) { }
public enum TodoStatus { PENDING, IN_PROGRESS, COMPLETED; }
public record Todo(TodoId id, RoleId owner, String title, String detail, TodoStatus status) { }
public record Skill(SkillId id, String name, String description, String body, List<String> files) { }
public record MailMessage(MailId id, String fromEmail, String fromName,
                          List<String> to, List<String> cc, String subject, String body,
                          Instant sentAt, boolean read) { }

// model/snapshot/*.java
/** 持久化快照（typed），是运行时与磁盘之间唯一的交界。 */
public record CompanySnapshot(int version, Instant savedAt, DayTick clock, LocalDate baseDate,
                              List<RoleSnapshot> roles) { }
public record RoleSnapshot(RoleSpec spec, AgentState state,
                           List<TaskRecord> pending, List<TaskRecord> history,
                           int conversationDay, List<Message> conversation) { }
```

### 5.3 `policy` —— 纯决策（可单测，无 I/O、无线程）

```java
// agent/dispatch/DeliveryPolicy.java   ← v3.2 从 sim.event 搬来
/** 统一"事件该不该现在进这个角色的队列"这一个决定（master 里散在 Dispatcher+AgentRole+RolePool 三处）。 */
public interface DeliveryPolicy {
    DeliveryDecision decide(DeliveryContext context);
}
public record DeliveryContext(AgentEvent event, RoleSpec spec, AgentState state, boolean scheduledReminder) { }
public enum DeliveryVerdict { DELIVER, HOLD, DROP }
public record DeliveryDecision(DeliveryVerdict verdict, String reason) {
    public static DeliveryDecision deliver(String why); public static DeliveryDecision hold(String why);
    public static DeliveryDecision drop(String why);
}
/** 默认实现：EMERGENCY 立即；广播走 SaliencePolicy；非紧急且 off-duty/wrapping/waiting → HOLD；定向定时提醒 → DELIVER。 */
public final class DefaultDeliveryPolicy implements DeliveryPolicy {
    public DefaultDeliveryPolicy(SaliencePolicy salience);
    public DeliveryDecision decide(DeliveryContext context);
}

// agent/dispatch/SaliencePolicy.java   ← v3.2 从 sim.event 搬来
/** 内容相关性打分（master AgentRole.evaluateEvent 的 Layer 2）。只对广播事件生效。 */
public interface SaliencePolicy { SalienceDecision score(RoleSpec spec, AgentEvent event); }
public record SalienceDecision(boolean pass, double score, double relevance, String reason) { }
public final class KeywordSaliencePolicy implements SaliencePolicy {
    public KeywordSaliencePolicy(double baseRelevance, double keywordStep, double skillBonus, double urgencyBonus);
    public SalienceDecision score(RoleSpec spec, AgentEvent event);
}

// policy/ClockPolicy.java
/** 把"时钟下一步做什么"从 TimeEventBus 线程里抽成纯决策：推进/快进/等待/跨天/强制收尾。 */
public interface ClockPolicy { ClockAction next(ClockSignals signals, ShiftCalendar calendar, Tick now, ScheduleTable schedule); }
public record ClockSignals(boolean anyBusy, boolean allIdle, boolean allOffDuty,
                           boolean wrapUpOverdue, long idleMillis) { }
public sealed interface ClockAction {
    record Advance(double simulatedSeconds) implements ClockAction { }   // 有人在干活，按时长推进
    record FastForwardTo(Tick target) implements ClockAction { }         // 全员空闲足够久，跳到下一个事件
    record Hold() implements ClockAction { }                             // 无事可做，停在原地
    record ForceWrapUp() implements ClockAction { }                      // 收尾超时兜底
}
public final class DefaultClockPolicy implements ClockPolicy { /* 用 calendar+schedule+signals 决定 */ }

// policy/ToolLoopPolicy.java
/** 工具循环的上限与失败策略（替换被注释掉的 MAX_TOOL_ROUNDS/MAX_TOOL_TOTAL_TOKENS）。 */
public record ToolLoopPolicy(int maxRounds, int maxTotalTokens, boolean failOnLlmError) {
    public static ToolLoopPolicy defaults();
    public boolean roundBudgetExceeded(int round);
    public boolean tokenBudgetExceeded(int tokens);
}

// policy/ConversationPolicy.java
/** 对话历史的压缩策略（master Conversation 里的 24000/12000/6 等魔数）。 */
public record ConversationPolicy(int maxHistoryChars, int maxSummaryChars, int toolRecapLimit) {
    public static ConversationPolicy defaults();
    public boolean shouldCompact(long totalChars);
    public int keepMessages();      // 压缩失败时至少保留的最近消息数
}
```

### 5.4 `ports` —— 能力接口（只依赖 kernel/model）

```java
// ports/Clock.java —— 只读时钟视图，给 tools/prompt 用，防止它们改时间
public interface Clock {
    Tick now(); DayTick nowDay(); ShiftCalendar calendar();
    String currentDateTime(); String describe();
}

// ports/LlmClient.java
/** LLM 能力。失败用 DomainError 或 ChatReply.failed() 表达，不再靠 "[API error:" 前缀嗅探。 */
public interface LlmClient {
    ChatReply chat(ChatRequest request);
    ToolReply chatWithTools(ToolChatRequest request);
    ChatReply summarize(String text, double temperature, int maxTokens);
}
public record ChatRequest(String system, String user, double temperature, Integer maxTokens) { }
public record ChatReply(String text, String reasoning, int tokens) { public boolean failed(); }
public record ToolChatRequest(List<Message> messages, List<ToolSpec> tools,
                              double temperature, Integer maxTokens) { }
public record ToolReply(String content, String reasoning,
                        List<ToolCallRequest> toolCalls, int totalTokens) { }

// ports/ToolSpec.java, ports/ToolCallRequest.java, ports/ToolResult.java
/** 工具的对外声明与调用/结果（typed，消除裸 Map schema 与 args）。 */
public record ToolSpec(String name, String description, JsonSchema schema) { }
public record ToolCallRequest(String callId, String toolName, Payload arguments) { }
public record ToolResult(String text, boolean error) { public static ToolResult ok(String t); public static ToolResult error(String t); }

// ports/Toolbox.java
/** 某个角色装配好的工具集合。工具循环只认识这个接口。 */
public interface Toolbox {
    List<ToolSpec> specs();
    ToolResult invoke(String toolName, Payload arguments);
}

// ports/Shell.java
/** 个人电脑能力（podman 容器 / 本地目录 / SSH 三种实现共用）。不再 import MCP。 */
public interface Shell {
    String powerOn(); String powerOff(); boolean poweredOn();
    CommandResult run(String command, Duration timeout, int maxOutputChars);
    String readFile(String path); void writeFile(String path, String content);
    List<String> listDir(String path); void deleteFile(String path);
    String workdir(); String driveRoot(); String hostDir(); String describe();
}
public record CommandResult(int exitCode, String stdout, String stderr) {
    public boolean ok(); public String combined();
}

// ports/Mailbox.java
public interface Mailbox {
    String addressOf(RoleSpec spec);
    MailId send(OutgoingMail mail);
    List<MailMessage> inbox(String address, int limit);
    int unreadCount(String address);
    Optional<MailMessage> read(String address, MailId id);
    void onDelivery(DeliveryListener listener);      // 投递 → 转成 NEW_MAIL 事件（由 bootstrap 接）
    interface DeliveryListener { void delivered(MailMessage message, String recipientAddress); }
}
public record OutgoingMail(String fromAddress, String fromName, List<String> to,
                           List<String> cc, String subject, String body) { }

// ports/NoteBook.java
public interface NoteBook {
    List<Note> list(RoleId owner); Optional<Note> read(RoleId owner, String title);
    void write(Note note); void edit(Note note); boolean delete(RoleId owner, String title);
    void saveSummary(RoleId owner, int day, String body);
    Optional<String> summary(RoleId owner, int day);
    Optional<String> latestSummary(RoleId owner, int beforeDay);
}

// ports/TodoList.java
public interface TodoList {
    List<Todo> list(RoleId owner, TodoStatus filterOrNull);
    Todo add(RoleId owner, String title, String detail);
    Optional<Todo> update(RoleId owner, TodoId id, TodoStatus status);
    boolean delete(RoleId owner, TodoId id);
}

// ports/SkillLibrary.java
public interface SkillLibrary {
    List<Skill> available(); List<Skill> search(String keyword);
    List<Skill> ownedBy(RoleId owner); void grant(RoleId owner, SkillId id); void revoke(RoleId owner, SkillId id);
}

// ports/ClientChannel.java
/** 与"客户/用户"对话的通道（控制台或 Web 二选一）。 */
public interface ClientChannel {
    boolean interactive();
    ClientReply ask(ClientQuestion question, Duration timeout);
}
public record ClientQuestion(RoleId asker, String askerName, String group, String text) { }
public record ClientReply(boolean delivered, String text, String reason) { }

// ports/Transcript.java + ports/TraceFeed.java
/** 运行轨迹写入（推理/工具调用/最终答案/群聊/客户消息），以及供 Web 增量拉取的读取口。 */
public interface Transcript {
    void reasoning(RoleId agent, String text, TraceMeta meta);
    void note(RoleId agent, String text, TraceMeta meta);
    void toolCall(RoleId agent, String toolName, String argsJson, String result, TraceMeta meta);
    void answer(RoleId agent, String text, boolean failed, int tokens, TraceMeta meta);
    void talk(TalkRecord record); void client(ClientRecord record); void system(String text);
}
public record TraceMeta(TaskId taskId, Integer round) { }
public interface TraceFeed extends Transcript {
    long watermark(); List<TraceEntry> since(long seq);
}

// ports/SnapshotStore.java
public interface SnapshotStore { Optional<CompanySnapshot> load(); void save(CompanySnapshot snapshot); }

// ports/JsonCodec.java
/** JSON 编解码能力；domain 不依赖 Jackson，只有适配器实现它。 */
public interface JsonCodec {
    String write(Object value); Map<String, Object> readMap(String json);
    <T> T read(String json, Class<T> type);
}

// ports/EventSink.java
public interface EventSink { void publish(AgentEvent event); }

// ports/AgentDirectory.java
/** 花名册只读视图（工具/提示词需要看同事，但不能改它们）。 */
public interface AgentDirectory {
    Optional<RoleSpec> find(RoleId id); List<RoleSpec> all();
    Optional<AgentState> stateOf(RoleId id);
}
```

### 5.5 `engine` —— 有状态编排（唯一拥有可变状态的地方）

```java
// engine/SimClock.java
/** 时钟状态唯一所有者。纯内存、无线程；线程在 ClockDriver。 */
public final class SimClock implements Clock {
    public SimClock(ShiftCalendar calendar, LocalDate baseDate);
    public Tick now(); public DayTick nowDay();
    void advanceTicks(long ticks);           // 只给 ClockDriver 用
    void jumpTo(Tick target);
    void resetTo(DayTick position);
    public void setBaseDate(LocalDate date);
    public String currentDateTime(); public String describe();
}

// engine/ScheduleTable.java
/** 定时事件/任务提醒表（master TimeEventBus 的调度那半 + NoteStore 的 reminder）。 */
public final class ScheduleTable {
    public ScheduledEntry schedule(String description, RoleId owner, DayTick at, Payload payload);
    public boolean cancel(String entryId);
    public ScheduledEntry reschedule(String entryId, DayTick at);
    public List<ScheduledEntry> list(RoleId ownerOrNull);
    public List<AgentEvent> due(Tick now);        // 弹出到期项并转成 TASK_DUE 事件
    public Optional<Tick> nextFireTick(Tick now); // 给 ClockPolicy 快进用
    void activateDay(int day);                    // 新班次开始时把当天任务装载进来
}
public record ScheduledEntry(String id, String description, RoleId owner, DayTick at,
                             Payload payload, boolean fired) { }

// engine/Sensors.java
/** ClockDriver 观察 roster 的窄接口，避免时钟反向依赖整个系统。 */
public interface Sensors {
    boolean anyBusy(); boolean allIdle(); boolean allOffDuty();
    boolean wrapUpOverdue(long graceMillis); long idleMillis();
}

// engine/ClockDriver.java
/** 时钟线程唯一所有者：感知 → 问 ClockPolicy → 应用动作 → 投递到期事件。 */
public final class ClockDriver {
    public ClockDriver(SimClock clock, ScheduleTable schedule, ClockPolicy policy,
                       EventSink sink, Sensors sensors, ClockOptions options);
    public void start(); public void stop();
    public void pause(); public void resume(); public boolean paused();
    void tickOnce();                              // 单步（测试可直接驱动，无需真实等待）
}
public record ClockOptions(long busyPollMillis, long idlePollMillis, long wrapUpGraceMillis) { }

// engine/AgentMailbox.java
/** 每角色优先队列（唯一写者=Agent）；按 urgency 降序、同 urgency FIFO。 */
public final class AgentMailbox {
    public void push(Task task); public Optional<Task> peek(); public Optional<Task> pop();
    public int depth(); public List<Task> snapshot();
    public boolean holds(Priority urgency);       // 判断队首是否因 off-duty 被暂存
}

// engine/AgentStateMachine.java
/** 角色状态唯一所有者，只允许合法迁移。 */
public final class AgentStateMachine {
    public AgentState state();
    public void to(AgentState next);              // 校验迁移合法性，非法则抛 DomainError
    public void toIdle(); public void toOffDuty(); public void toWaiting(); public void restore(AgentState s);
}

// engine/WaitCoordinator.java
/** talk wait=true 的同步等待（master AgentRole 的 Condition 协议）。 */
public final class WaitCoordinator {
    public void begin(RoleId target);
    public Optional<String> await(Duration timeoutOrNull);   // 阻塞直到 deliver/abort/超时
    public void deliver(String reply);                        // talk 对方回复
    public void abort(String syntheticReply);                 // 下班/强制收尾时解阻塞
    public void end();
    public boolean waiting(); public Optional<RoleId> waitingFor();
}

// engine/ConversationMemory.java
/** 角色 ↔ LLM 的当日对话：跨任务连续性、超限压缩、下班关闭（master Conversation/ConversationManager）。 */
public final class ConversationMemory {
    public ConversationMemory(RoleId agent, ConversationPolicy policy);
    public List<Message> prepare(String systemPrompt, String taskDescription, int day);
    public void commit(int day, String userText, String assistantText, LlmClient llm);
    public void closeDay(int day);
    public boolean isEmpty(); public int historySize();
    public RoleSnapshot.ConversationState snapshot(); public void restore(RoleSnapshot.ConversationState s);
}

// engine/ToolLoop.java
/** 一轮任务里的 LLM ↔ 工具循环（从 AgentRole.executeWithTools 抽出）。 */
public final class ToolLoop {
    public ToolLoop(LlmClient llm, Toolbox toolbox, Transcript transcript, ToolLoopPolicy policy);
    public Outcome run(RoleId agent, String systemPrompt, Task task, ConversationMemory memory, int day);
}
public record Outcome(String answer, int tokens, boolean failed) { }

// engine/TaskRunner.java
/** 单角色工作循环（从 RolePool.roleLoop 抽出）：等暂停 → 尊重 off-duty 暂存 → pop → 跑 → 落库 → 回调。 */
public final class TaskRunner implements Runnable {
    public TaskRunner(Agent agent, LlmClient llm, Transcript transcript, LifecycleGate gate);
    public void run();                    // 常驻循环，直到 stop()
    public void requestStop();
}

// engine/Agent.java
/** 一个角色的门面：只做组合，不含业务实现；外部只跟它打交道。 */
public final class Agent {
    public Agent(RoleSpec spec, AgentMailbox mailbox, AgentStateMachine state,
                 WaitCoordinator waits, Toolbox toolbox, ConversationMemory conversation,
                 TaskRunner runner);
    public RoleId id(); public RoleSpec spec();
    public void start(); public void stop();
    public void submit(Task task);
    public AgentState state(); public boolean busy(); public int queueDepth();
    public Optional<Task> currentTask(); public List<Task> pendingTasks(); public List<Task> history(int limit);
    public WaitCoordinator waits(); public Toolbox toolbox(); public ConversationMemory conversation();
    public AgentSnapshot snapshot();
}
public record AgentSnapshot(RoleId id, String name, AgentState state, boolean busy,
                            int queueDepth, String currentTask) { }

// engine/AgentFactory.java
/** 由 RoleSpec + 能力端口装配出一个 Agent（bootstrap 里实现，决定用哪些 toolkit/llm）。 */
public interface AgentFactory { Agent create(RoleSpec spec); }

// engine/Team.java
/** 花名册唯一所有者：增删角色、批量启停；不做 LLM/工具装配（那是 AgentFactory 的事）。 */
public final class Team implements AgentDirectory, Sensors {
    public Team(AgentFactory factory);
    public Agent hire(RoleSpec spec);
    public boolean resign(RoleId id);
    public Optional<Agent> find(RoleId id); public List<Agent> all();
    public Optional<RoleSpec> find(RoleId id); public List<RoleSpec> all(); public Optional<AgentState> stateOf(RoleId id);
    public void startAll(); public void stopAll();
    public boolean anyBusy(); public boolean allIdle(); public boolean allOffDuty();
    public List<AgentSnapshot> snapshots();
}

// engine/TaskFactory.java
/** 事件 → 任务 的唯一转换点（urgency 映射、描述、上下文）。 */
public final class TaskFactory {
    public TaskFactory(ScheduleTable schedule);   // 需要它来取消 TASK_DUE 对应的提醒
    public Task from(AgentEvent event, RoleId assignee);
}

// engine/EventRouter.java
/** 事件唯一入口：解出收件人 → 问 DeliveryPolicy → DELIVER 建任务投递/HOLD 暂存/DROP 记日志。 */
public final class EventRouter implements EventSink {
    public EventRouter(Team team, DeliveryPolicy delivery, TaskFactory tasks, Transcript transcript);
    public void publish(AgentEvent event);
    public Map<RoleId, Routed> publishAndReport(AgentEvent event);   // 给 demo/测试看每个角色的取舍
}
public record Routed(DeliveryVerdict verdict, String reason, TaskId taskId) { }

// engine/LifecycleGate.java
/** 全局暂停唯一所有者；worker 与 LLM 都只读它。取代 master 里 setPauseGate 的回调注入。 */
public final class LifecycleGate {
    public void pause(String reason); public void resume();
    public boolean paused(); public String reason();
    public void awaitRunning(Duration poll);   // 被暂停时 worker 在此挂起
}

// engine/ShiftDirector.java
/** 班次反应唯一处：SHIFT_START 开机/上岗、SHIFT_END 解阻塞+要求总结、收尾超时强制 OFF_DUTY。 */
public final class ShiftDirector {
    public ShiftDirector(Team team, Clock clock, LifecycleGate gate);
    public void onTick(Tick now);              // ClockDriver 每步调用；内部只在边界动作
    public void onShiftStart(); public void onShiftEnd();
    public void forceWrapUp();
}

// engine/Company.java
/** 顶层编排门面（薄）。只把上面这些对象组合起来，不实现任何业务规则。 */
public final class Company {
    public Company(Team team, SimClock clock, ClockDriver driver, EventRouter router,
                   ScheduleTable schedule, ShiftDirector director, LifecycleGate gate,
                   SnapshotStore snapshots, Transcript transcript);
    public void start(); public void stop();               // 先起 worker，再起时钟；停时反序
    public void pause(String reason); public void resume(); public boolean paused(); public String pauseReason();
    public CompanyStatus status();                          // 给 Web/控制台
    public void save(); public int restore();
    public void publish(AgentEvent event);                  // 外部事件入口
    public AgentDirectory directory(); public SimClock clock(); public Team team();
}
public record CompanyStatus(DayTick clock, String dateTime, String describe,
                            boolean paused, String pauseReason, List<AgentSnapshot> agents) { }

// engine/HiringService.java
/** LLM 驱动的招聘：招聘需求 → RoleSpec（master RoleFactory）。只产出 spec，不落库、不上岗。 */
public final class HiringService {
    public HiringService(LlmClient llm, List<RoleSpec> templateCatalog);
    public RoleSpec draft(String requirement);
}
```

### 5.6 `tools` —— 工具 SPI + 内置工具包

```java
// tools/Tool.java
/** 单个工具：声明 spec + 执行。参数是 typed Payload，不是裸 Map。 */
public interface Tool {
    ToolSpec spec();
    ToolResult invoke(RoleId agent, Payload arguments);
}

// tools/Toolkit.java
/** 工具包：把一组工具绑定到它真正需要的能力端口上（构造期注入，无 role/system 穿透）。 */
public interface Toolkit {
    String id();                              // "note" / "todo" / "talk" …
    List<Tool> instantiate();
}

// tools/ToolCatalog.java
/** 工具包注册表：按 RoleSpec.toolkits 选包并装配成该角色的 Toolbox（数据驱动，无 Java 分支）。 */
public final class ToolCatalog {
    public ToolCatalog register(Toolkit toolkit);
    public List<String> ids();
    public Toolbox build(RoleSpec spec, ToolContext context);
}

// tools/ToolContext.java
/** 装配 Toolbox 时传给工具包的窄上下文（只含工具可能需要的只读能力）。 */
public record ToolContext(Clock clock, AgentDirectory team, ScheduleTable schedule,
                          Transcript transcript) { }

// tools/JsonSchema.java
/** 类型化 JSON Schema 构造器；替代 Tool.getSchema 的 name→描述 map，能表达 required/enum/int/bool。 */
public final class JsonSchema {
    public static JsonSchema object();
    public JsonSchema string(String name, String description);
    public JsonSchema integer(String name, String description);
    public JsonSchema bool(String name, String description);
    public JsonSchema enumeration(String name, String description, List<String> values);
    public JsonSchema required(String... names);
    public Map<String, Object> toMap();
}

// tools/builtin/*.java —— 每个类是一个 Toolkit，只注入它需要的能力
public final class MemoryToolkit  implements Toolkit {                 // summary
    public MemoryToolkit(NoteBook notes, Clock clock, AgentRegistryControl control);
}
public final class NoteToolkit    implements Toolkit {                 // write/edit/list/read/delete_note
    public NoteToolkit(NoteBook notes, ScheduleTable reminders);
}
public final class TodoToolkit    implements Toolkit { }               // todo_add/list/update/delete
public final class TimeToolkit    implements Toolkit { }               // get_time, take_rest
public final class TaskViewToolkit implements Toolkit { }              // my_tasks
public final class PcToolkit      implements Toolkit { }               // run_command/computer_status/lan_devices/reboot
public final class McpToolkit     implements Toolkit { }               // mcp_search/list/add/remove/my_tools
public final class SkillToolkit   implements Toolkit { }               // skill_list/search/add/remove/my_skills
public final class EmailToolkit   implements Toolkit { }               // send_email/read_mail/open_mail/mail_address_book
public final class TalkToolkit    implements Toolkit { }               // talk, list_roles（注入 Team + WaitCoordinator 路由，不注入 pool 内部）
public final class ClientToolkit  implements Toolkit { }               // talk_to_client
public final class HrToolkit      implements Toolkit { }               // post_job_posting, list_candidates
public final class HermesToolkit  implements Toolkit { }               // hermes_send, hermes_new_conversation（默认不装）
```

> 工具包的关键变化：**它们只拿到自己需要的端口**（`NoteBook`、`Shell`、`Team`、`Transcript`…），
> 不再拿到 `AgentRole`/`AgentSystem`，因此 master 里"工具直接改角色 state / 关电脑 / 抓 pool"这类越权消失。

### 5.7 `adapters` —— 端口实现（唯一碰 I/O 的地方）

```java
// adapters/llm
public final class OpenAiClient implements LlmClient { }        // 唯一 chat 实现；内部 RetryArbiter
public final class ProviderCatalog { }                          // providers.default.json + 本地覆盖
public final class ProviderResolver { }                         // provider+model → Endpoint(baseUrl, apiKey, model)
public final class RetryArbiter { }                             // 每 endpoint 的限流排队（保留 master 语义）
public record Endpoint(String baseUrl, String apiKey, String model) { }

// adapters/computer
public final class ShellRegistry { }                            // roleId → Shell，负责创建/销毁/复启
public final class PodmanShell implements Shell { public String mcpEndpoint(); }
public final class LocalShell  implements Shell { }
public final class SshShell    implements Shell { }
public final class ProcessRunner { }                            // 通用子进程执行（从 Computer.runProcess 抽出）

// adapters/mcp
public final class StdioMcpServer { }                           // master MCPServer 的 stdio JSON-RPC
public final class McpToolBridge { }                            // MCP 工具 → tools.Tool（动态注册）

// adapters/mail
public final class FileMailbox implements Mailbox { }           // 虚拟邮箱落盘
public final class SmtpSender { }                               // 可选真实 SMTP

// adapters/persistence
public final class JacksonJsonCodec implements JsonCodec { }
public final class JsonNoteBook implements NoteBook { }
public final class JsonTodoList implements TodoList { }
public final class JsonSkillLibrary implements SkillLibrary { }
public final class JsonSnapshotStore implements SnapshotStore { }   // 原子写 state.json

// adapters/input
public final class ConsoleClientChannel implements ClientChannel { }
public final class WebClientChannel implements ClientChannel { }    // 经 TraceFeed 的等待/回复

// adapters/web
public final class ChatFeed implements TraceFeed { }                // 内存环形缓冲 + 等待客户回复状态
public final class ChatWebServer { }                                // JDK HttpServer：/api/state /messages /reply /pause /resume /attach
public final class WebAssets { }                                    // classpath /web/* 静态资源

// adapters/config
public record AppConfig(Llm llm, Schedule schedule, Storage storage, Web web,
                        Mail mail, Toolkits toolkits) { }
public final class ConfigLoader { }                                 // env > config.json > 代码默认，唯一配置入口
public final class AppPaths { }                                     // 所有路径的唯一来源（XDG/Windows/macOS）
```

### 5.8 `bootstrap` —— 组合根 + 入口

```java
// bootstrap/CompanyBuilder.java
/** 唯一允许"认识所有东西"的地方：显式 new 出依赖图，无 setter、无 holder、无环。 */
public final class CompanyBuilder {
    public CompanyBuilder(AppConfig config, AppPaths paths, JsonCodec json);
    public CompanyBuilder withLlm(LlmClient llm);                   // 测试/嵌入时可替换
    public CompanyBuilder withClientChannel(ClientChannel channel);
    public Company build();                                         // 组装 adapters→tools→engine→Company
}

// bootstrap/Main.java
/** 入口：加载配置 → build → hire 默认团队 → restore → start → 日循环提示 → 退出保存。 */
public final class Main { public static void main(String[] args); }
```

---

### 5.9 落地记录：骨架的两次调整（`refactor2` 分支，121 个文件）

#### A. 写骨架时对方案本身的修正（v3.0）

1. **`ToolSpec` / `ToolCallRequest` / `ToolResult` 不再放端口层**：`Message` 需要引用 `ToolCallRequest`，放接口包会形成反向依赖。今落点：`ToolSpec`/`ToolResult` → `tool.spi`，`ToolCallRequest` → `llm`。
2. **`JsonSchema` 放进 `kernel`**：`ToolSpec` 需要它，且它本身与工具实现无关。
3. **新增 `AgentSnapshot` / `CompanyStatus` + `CompanyView`**：让 Web 适配器只依赖窄接口，而不依赖 `Company`。
4. **`Team` 不再持有 `AgentFactory`，新增 `Staffing`**。原因：原方案会形成构造环
   `Team → AgentFactory → ToolCatalog → TalkToolkit → TeamChannel → Team`。
   拆分后 `Team` 无依赖即可先建，创建 Agent 的动作归 `Staffing`。
5. **`ClockPolicy.next` 接收 `Optional<Tick> nextFireTick` 而不是 `ScheduleTable`**：否则决策层反向依赖时钟实现。
6. **`AgentMailbox` 拆成 `ready` / `deferred` 两个队列**，让投递策略的 `HOLD` 真正生效（master 的 `DeliveryMode` 是装饰性的）。状态允许时由 `promoteDeferred()` 提升。
7. **新增窄端口**：`ReminderScheduler`、`TeamChannel`、`AgentTasks`、`AgentControl`、`McpBridge`、`Recruiter`；以及 `SystemPrompt`（从 `AgentRole` 抽出）。
8. **`TaskRunner` 由 `Agent.start()` 创建但不被 `Agent` 持有**，避免对象环。
9. **`AgentControl.closeDayConversation(int day)` 带 day 参数**，避免 `Agent` 依赖 `Clock`。

#### B. 按功能重排包结构（v3.1，你 review 后的修改）

原则：**一个包 = 一个功能；功能内的接口、实现、领域类型、LLM 工具放在同一个包里**，不再按"层次"切。
`ports/`、`adapters/`、`model/`、`policy/` 四个包**全部废除**。

1. **带 LLM 工具的能力整体并入 `tool/`**：`tool.computer`、`tool.note`、`tool.todo`、`tool.skill`、
   `tool.mail`、`tool.mcp`、`tool.talk`、`tool.client`、`tool.hr`、`tool.time`、`tool.task`；
   工具 SPI 在 `tool.spi`。
   例：`Shell`（原 `ports`）+ `LocalShell`/`PodmanShell`/`SshShell`（原 `adapters/computer`）
   + `PcToolkit`（原 `tools/builtin`）现在同属 `tool.computer`。
2. **`policy` 解散，策略回到各自功能**：`ClockPolicy`/`DefaultClockPolicy` → `sim.clock`；
   `DeliveryPolicy`/`SaliencePolicy` + 默认实现 → `sim.event`；`ConversationPolicy` → `agent.dialog`；
   `ToolLoopPolicy` → `agent.task`。
3. **`model` 解散**：`Task`/`TaskStatus` → `agent.task`；`RoleSpec`/`RoleSnapshot` → `agent.role`；
   `AgentEvent`/`EventKind`/`Priority` → `sim.event`；`Tick`/`DayTick`/`ShiftCalendar` → `sim.clock`；
   `Note`/`Todo`/`Skill`/`MailMessage` → 各自 `tool.*`；`Message`/`ToolCallRequest` → `llm`；
   `ToolSpec`/`ToolResult` → `tool.spi`；`Payload` → `kernel`；`CompanyStatus`/`CompanySnapshot` → `company`。
4. **`ports` 解散**：接口跟它的实现同包（`SimClock implements Clock`、`JsonTodoList implements TodoList`、
   `ConsoleClientChannel implements ClientChannel`、`FileMailbox implements Mailbox` …）。
5. **`adapters` 解散**：实现回到能力包（`tool.computer`/`tool.mail`/`tool.mcp`/…），
   纯基础设施留在 `infra.config`（配置/路径）与 `infra.json`（JSON 编解码）。
6. **`ToolContext` 删除**：`tool.spi` 不再依赖 `agent`/`tool.computer`，环被切断。
   改为**每角色装配**：`Toolkit.instantiate()` 无参，所需能力一律构造期注入
   （`PcToolkit(Shell)`、`TaskViewToolkit(AgentTasks)`、`MemoryToolkit(NoteBook, Clock, AgentControl)`）。
   装配入口是 `bootstrap.ToolkitCatalog`；`Agent` 通过 `agent.ToolboxFactory` 在 `start()` 时取自己的
   `Toolbox`（因为 `AgentTasks`/`AgentControl` 由 `Agent` 自己实现，只能"先有 Agent 再装配工具"）。
7. **跨包 `implements` 保留（你说可以）**：它们都是消费方定义的 DIP 端口 ——
   `tool.*.*Toolkit implements tool.spi.Toolkit`、`agent.Team implements sim.clock.Sensors`、
   `agent.dispatch.EventRouter implements sim.event.EventSink`、
   `company.ShiftDirector implements sim.clock.TickObserver`（新增，避免 `sim → company`）。
   除这些之外，其余接口与实现都在同一包内。
8. **唯一残留的反向依赖**：`tool.computer.ShellRegistry` 需要 `agent.role.RoleSpec`
   （电脑规格定义在角色定义里），而 `agent` 持有 `tool.computer.Shell`。这是领域本身的三角，不再强行拆。

#### C. 分支与范围

`refactor2` 基于 `master` 建立（`refactor` 分支已有 `kernel`/`ports`/`tools` 等同名包，会撞车）。
master 的 98 个旧实现与 31 个测试**已在本分支删除**（`master` 仍完整保留，作为"语义不许变"的参照）；
当前 `src/main/java` 只剩这份骨架的 121 个文件。
验证：`rm -rf target/classes && mvn -o -q -Dmaven.repo.local=.m2-local -DskipTests compile` 通过（179 个 `.class`）。

## 6. master → 新架构 映射表

| master | 新架构 | 说明 |
|---|---|---|
| `AgentSystem` | `Company` + `EventRouter` + `ShiftDirector` + `bootstrap/CompanyBuilder` | 组合、路由、班次反应、装配四件事分开 |
| `AgentRole` | `Agent` + `AgentMailbox` + `AgentStateMachine` + `ConversationMemory` + `WaitCoordinator` + `TaskRunner` + `RoleSpec` | 人设/队列/状态/对话/等待/循环各自单一所有者 |
| `RolePool` | `Team` + `AgentFactory` | 花名册与装配分离；worker 线程归 `Agent`/`TaskRunner` |
| `TimeEventBus` | `SimClock` + `ScheduleTable` + `ClockDriver` + `ClockPolicy` + `ShiftCalendar` | 状态/表/线程/决策/算术四分离 |
| `EventDispatcher` + `AgentRole.evaluateEvent` | `EventRouter` + `DefaultDeliveryPolicy` + `KeywordSaliencePolicy` | 投递决策收成一处 |
| `Types.Event` / `Payload` | `AgentEvent` + `EventKind` + `Payload` | 不可变、带类型收件人 |
| `Types.Priority` / `Urgency` / `int` | `Priority` | 唯一来源 |
| `Task`（可变 public 字段） | `Task` + `TaskRecord` + `TaskStatus` | 显式迁移方法；持久化形状唯一 |
| `LLM` / `OpenAICompatLLM` | `llm/LlmClient` / `llm/OpenAiClient` | 失败用类型而非字符串前缀 |
| `Conversation` + `ConversationManager` | `ConversationMemory` + `ConversationPolicy` | 每 Agent 一份，无全局 manager |
| `ToolRegistry` + `tools.Toolkit` | `tool/spi/Tool` + `Toolkit` + `Toolbox` + `ToolSpec` + `bootstrap/ToolkitCatalog` + `kernel/JsonSchema` | 消除双份 toolkit |
| `Store`（State/Note/Todo/Config/PathManager） | `tool.note.NoteBook`/`tool.todo.TodoList`/`tool.skill.SkillLibrary`/`company.store.SnapshotStore` + 各自包内的 JSON 实现 + `infra.config.AppPaths` | 能力接口与磁盘实现同包 |
| `Computer`/`ComputerManager` | `tool.computer.Shell` + `ShellRegistry` + 三种 Shell 实现 | 去掉 Computer 对 MCP/ToolRegistry 的依赖 |
| `MCPServer`/`MCPManager` | `tool.mcp`（`McpBridge` + `StdioMcpBridge` + `McpToolkit`） | MCP 收在一个能力包里 |
| `MailService` | `tool.mail`（`Mailbox` + `FileMailbox`/`SmtpSender`） | 去掉 per-role store 泄漏与单例 |
| `ChatStore`/`ChatWebServer` | `transcript.Transcript`/`transcript.ChatFeed` + `web.ChatWebServer` | Web 只读 feed，不抓 runtime 内部 |
| `Input`/`StdInput`/`WebInput` | `tool.client`（`ClientChannel` + 控制台/Web 实现） | 去掉 bind(store,lock) |
| `RoleLoader`/`RoleFactory` | 角色模板加载（bootstrap）+ `tool.hr.HiringService` | 静态模板表 → 实例化加载器 |
| `ClientCommunicationLock` | `tool.client.ClientToolkit` 内部互斥（或 `Company` 持有的一个小对象） | 不再全局单例 |

---

## 7. 关键机制在新架构中的落点

- **时钟 / 班次 / 日循环**：`SimClock`（状态）→ `ClockDriver`（线程，感知 `Sensors`）→ `ClockPolicy`（推进/快进/等待/兜底）→ `ShiftDirector`（边界反应）。master 里"时钟反调 `AgentSystem.allRolesIdle`"变成"驱动读 `Team` 暴露的只读感知"。
- **事件投递**：外部/时钟/邮件都走 `sim.event.EventSink.publish` → `agent.dispatch.EventRouter` 解收件人 → `sim.event.DeliveryPolicy` 决定 DELIVER/HOLD/DROP → `agent.dispatch.TaskFactory` 建 `Task` → `Agent.submit`。显著性过滤只在广播事件上生效，且是纯函数。
- **工具循环**：`TaskRunner` → `ToolLoop.run`，上下文来自 `ConversationMemory.prepare`，工具来自 `Agent.toolbox()`，轨迹写 `Transcript`。上限与失败策略来自 `ToolLoopPolicy`。
- **talk wait=true**：`TalkToolkit` 通过 `WaitCoordinator` 路由（`Agent.waits()`），不再直接操作对方 `AgentRole`。下班解阻塞由 `ShiftDirector.onShiftEnd` 调 `abort`。
- **暂停**：`LifecycleGate` 唯一持有；`TaskRunner` 与 `OpenAiClient` 都只读它；解决 master 回调注入暂停的问题。
- **Web**：`transcript.ChatFeed` 实现 `transcript.Transcript.Feed`；`web.ChatWebServer` 只依赖 `company.CompanyView` + `Transcript.Feed`，不再 import 运行时内部。
- **持久化**：`CompanySnapshot` 由 `Company.save()` 组装 → `company.store.SnapshotStore`；notes/todos/skills 走各自能力包的 JSON 实现。`Map` 只在 `infra.json.JacksonJsonCodec` 内部出现。

---

## 8. 迁移路线（每一步都可编译、可回滚）

1. **P0 立骨架**：按 §4 建功能包（`kernel`/`tool.*`/`agent.*`/`sim.*`/`company`/`llm`/…），只放签名不放实现。加一条 ArchGuard 规则（按包白名单校验依赖方向）。
2. **P1 时间线**：`ShiftCalendar`/`SimClock`/`ScheduleTable`/`ClockPolicy` 落地，用 master 的时间测试对拍（differential test）。
3. **P2 领域事件**：`AgentEvent`/`Task`/`DeliveryPolicy`/`SaliencePolicy` 落地，喂 master 的事件过滤测试。
4. **P3 角色内核**：`Agent`/`AgentMailbox`/`AgentStateMachine`/`WaitCoordinator`/`ToolLoop`/`ConversationMemory`，用假端口（fakes）跑通工具循环与等待。
5. **P4 工具与能力**：`tool.spi` + `bootstrap.ToolkitCatalog` + 13 个工具包；先接 `tool.note`/`tool.todo`/`tool.time` 这类低耦合的。
6. **P5 适配器**：LLM/电脑/MCP/邮件/持久化/Web/输入逐个实现端口；每个都保留 master 的对外行为（尤其 `state.json` 与 `/api/*` 契约）。
7. **P6 组合与切换**：`bootstrap/CompanyBuilder` + 新 `Main`，端到端跑一天；再删旧包。
8. **P7 收尾**：删 master 遗留类、清理全局单例、把 Web 契约与 `state.json` 版本化。

（我建议**不要**在当前 `refactor` 分支上继续：它的 `Application`/`ToolService.bind` 结构会持续把新设计往回拽。P0 可以在 `master` 上开新分支，或直接在 `refactor` 之上只保留其测试资产。）

---

## 9. 测试策略

- 纯决策：单测（时间算术、投递决策、压缩阈值、优先级排序）——分别在 `sim.clock`/`sim.event`/`agent.*` 里。
- 运行时编排：用 fakes（假 LlmClient/假 Toolbox/假 Clock 感知）驱动 `ClockDriver.tickOnce()` 与 `TaskRunner`，不需要真实线程/网络/容器。
- 行为对拍：master 的关键测试（`TimeManagerTest`、`EventBusTest`、`TalkWaitTest`、`AgentSystemPauseTest`、`ConversationEndToEndTest`…）先原样保留，作为"语义不许变"的护栏。
- 契约测试：`state.json` 能读 master 的旧档；`/api/state`、`/api/messages` 字段名不许变（master 前端用 camelCase，注意别像上一轮那样改坏）。

---

## 10. 待你拍板的问题

1. ~~**基线**~~ **已定**：`refactor2` 从 `master` 重开，master 的旧实现与测试在本分支已删除，master 保留作参照。
2. ~~**包名/落点**~~ **已定**：直接放 `com.agent.software.*`（见 §4）。
3. ~~**`tools` 是否独立成层**~~ **已定**：带工具的能力全部并入 `tool/`，SPI 在 `tool.spi`（见 §5.9 B）。
4. **`HOLD` 的语义**：master 对 off-duty 的普通事件是"直接丢/暂存到下一班"混着来。新设计里 `HOLD` 是"放进该角色队列但 worker 不取"，还是"放进一个待下一班重投的 holding area"？我默认前者（更接近 master）。
5. **`Map` 允许范围**：是否接受"只在 adapters/持久化内部出现 `Map<String,Object>`"，其余全部 typed？
6. **Web/`state.json` 兼容**：要不要保证与 master 100% 兼容（意味着保留 camelCase 与旧字段）？
7. **是否需要 `Hermes`/`Anthropic` 原生 chat**：master 的 `Provider.ApiFormat.ANTHROPIC` 只有 `/models`，没有 chat 实现。新架构要不要补 `AnthropicClient`？

---

## 附：当前状态

- **只写了骨架**：`refactor2` 上 121 个 `.java` 全部只有包、类型与函数签名，具体实现一律 `throw new UnsupportedOperationException("skeleton")`。
- 包结构已按 §4 / §5.9-B 的功能分组落地；`mvn -o -Dmaven.repo.local=.m2-local -DskipTests compile` 通过。
- `master` 的旧实现与测试已从本分支删除，`master` 分支完整保留作为行为参照。
- 下一步：等你对包结构/类职责拍板后，按 §8 的 P0→P7 逐块填实现（以 master 的 31 个测试作为"语义不许变"的护栏）。

## 附：审计原始材料

本方案的两份体检依据（自动生成、含逐类逐函数清单与 file:line 证据）：

- `redesign/audit/master-infra.md` —— master 的 `computers/store/services/web/io/utils/core` 全量清单 + 15 条横切问题。
- `redesign/audit/master-tools-llm-role.md` —— master 的 `llm/role/tools` 全量清单 + 38 个工具的行为表 + 12 条耦合问题。
- `redesign/audit/refactor-critique.md` —— `refactor` 分支的设计批判（按严重度排序，附 top-10）。


---

## 11. v3.2 变更记录（由依赖图扫出来之后改的）

起因：`redesign/diagrams/arch_1_packages.py` 直接从源码 import 生成包依赖图，
并用 Tarjan 算了强连通分量 —— 第一次跑出来 **8 个包处在同一个环里**
（`agent`、`agent.dialog`、`agent.role`、`agent.task`、`sim.clock`、`sim.event`、
`tool.computer`、`tool.note`）。逐条查了根因后改了 5 处：

| # | 原来 | 现在 | 为什么 |
|---|---|---|---|
| ① | `sim.event` 里有 `DeliveryPolicy` / `SaliencePolicy` 家族 | 移到 `agent.dispatch` | `DeliveryContext` 里塞了 `AgentState` + `RoleSpec`，造成 `sim.event → agent`；投递裁决本来就是 agent 侧的策略，`sim.event` 只该留事件数据 |
| ② | `Tick` / `DayTick` 在 `sim.clock` | 下放到 `kernel` | `AgentEvent.fireAt` 要用 `Tick`，造成 `sim.event → sim.clock`，而 `sim.clock` 又要 `EventSink`，两个子包互指 |
| ③ | `TaskRunner(Agent agent, …)` | `TaskRunner(AgentMailbox, AgentTasks, AgentControl, ToolLoop, Transcript, LifecycleGate)` | 执行器不该拿到整个角色对象；master 的 `RolePool` 就是因为拿到整个角色才能顺手改状态 |
| ④ | `RoleSnapshot` 在 `agent.role` | 搬到 `agent` | 它是 **Agent** 的快照（含队列/历史/对话），放在 role 包里却引用 `Task`，造成 `agent.role → agent.task` |
| ⑤ | `SystemPrompt(Clock, NoteBook)` | `SystemPrompt(Clock, DailySummary)`，`tool.note.JsonNoteBook implements DailySummary` | 原来 `agent.dialog → tool.note`，而 `tool.note → agent`（`MemoryToolkit` 要 `AgentControl`），变成两个 feature 互指；这是"agent 不依赖具体工具包"规则的例外，补上窄端口即可 |

顺带清掉了 5 行 javadoc-only 的残留 import（`ToolSpec→llm.*`、`ConversationMemory`/`SystemPrompt→agent.Agent`、`Shell→tool.mcp.McpBridge`），
它们之前制造了 `tool.spi ↔ llm`、`agent ↔ agent.dialog`、`tool.computer → tool.mcp` 三条**假环**。

改完之后：

- **包粒度只剩 1 个环**：`agent ↔ agent.task`。原因是端口接口
  （`AgentTasks` / `AgentControl` / `AgentMailbox` / `LifecycleGate`）住在父包 `agent`，
  而 `agent.task` 要用它们。两条路：接受（"父包持契约、子包实现"是常见形态），
  或把端口下沉到 `agent.task`。
- **feature 粒度还有 1 个环**：`{agent, llm, tool}`。两条边：
  - `agent → tool.computer`（`Shell`）↔ `tool.computer → agent.role`（`ComputerSpec`）—— 即 §3 里那条已知反向边；
  - `tool → llm`（`HiringService` 用 LLM 起草岗位）↔ `llm → tool.spi`（`ToolChatRequest` 带 `ToolSpec`）。
    把 `ToolSpec` 下放到 `kernel` 就能消掉这一条。

这两条要不要继续拆，等你定；图随时可以重跑（`python3 redesign/diagrams/build_all.py`）。
