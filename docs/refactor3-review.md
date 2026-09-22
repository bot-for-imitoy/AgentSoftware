# refactor3 重写评审与补全清单

> 评审对象：分支 `refactor3`，HEAD = `2a85ce8` "Refactor3"（工作区干净，无未提交改动）。
> 对照分支：`master`（行为基线）、`refactor`、`refactor2`。
> 结论日期：评审时以仓库当前状态为准。
> 本文只做评审，**未修改任何代码**。

---

## 0. 结论（TL;DR）

`refactor3` 目前**不是一个"重写完成"的版本，而是一个删到一半、尚未接线的骨架**：

- 主代码 **540 个编译错误**，测试代码 **1111 个编译错误**，整个项目编译不过、无法运行。
- 提交 `2a85ce8` 的净变更是 **+787 / −9540 行**：删掉 23 个主代码文件，只新增 8 个小文件，另有 54 个文件被改动，但**调用方没有跟着改完**。
- 相对 master，README 里宣传的能力（时钟/事件、角色运行时、LLM+重试、电脑/MCP、邮件、笔记/待办/状态、Web UI、招聘、技能、输入通道、暂停恢复）**没有一块有可运行实现**。
- 对比之下 `refactor2` 才是那个"架构重写并且真的完成"的分支：main/test 编译 0 错，实测 **39 个测试类 / 314 用例全绿**，并有 `redesign/PLAN.md`、生成的依赖图和 master→refactor2 的逐项映射表。

> 一句话："有没有未实现的接口" —— 有，而且**几乎是整个系统**。

---

## 1. 验证方法与结果

用项目自带的 classpath（`redesign/_scratch/cp.txt`）直接 `javac`，避免 Maven 离线插件问题：

```bash
javac -encoding UTF-8 -nowarn -d target/check \
  -cp "$(cat redesign/_scratch/cp.txt)" $(find src/main/java -name '*.java')
```

| 分支 | main 编译 | test 编译 | 测试 |
|---|---|---|---|
| `master` | 0 错 | 0 错 | 基线（README 称 208 用例 / 29 类） |
| `refactor` | 0 错 | 0 错 | 未跑 |
| `refactor2` | 0 错 | 0 错 | **39 类 / 314 用例，0 失败 0 错误 0 跳过**（`mvn -o -Dmaven.repo.local=.m2-local test`，exit 0） |
| **`refactor3`** | **540 错** | **1111 错** | 编译不过，无法运行 |

`refactor2` 测试统计来自 `target/surefire-reports/*.txt` 聚合：`classes=39 tests=314 failures=0 errors=0 skipped=0`。

---

## 2. 提交 `2a85ce8 Refactor3` 到底做了什么

- 删除主代码文件 **23 个**（详见第 4 节），新增 **8 个**：`computers/MCPServer`、`event/Event`、`event/TimeBus`、`llm/context/Context`、`llm/context/Message`、`role/Role`、`utils/UUIDObject`、`utils/UUIDObjectManager`。
- 修改 **54 个**文件（多数只是改了 import/一两行，**并没有真正重写**）。
- 另有 **23 个文件与 master 逐字节相同**（`core/Types`、`llm/OpenAICompatLLM`、`store/ConfigStore`、`store/PathManager`、`tools/Tool`、`client/ClientCommunicationLock`、`hermes/*`、`note/Delete|Edit|List|Read|Write`、`pc/ComputerStatus|Reboot|RunCommand`、`skill/SkillList|SkillSearch`、`todo/TodoAdd|Delete|List|Update`、`demo/McpDemo`）——这些"看起来还在"，其实还是旧实现。

即：**这是一次"先删旧实现 + 放了几个新的值类型"的中间态提交，不是完整重写。**

---

## 3. 未实现的接口 / 空壳（refactor3 现存文件）

| 位置 | 现状 | 缺什么 |
|---|---|---|
| `role/Role.java`（29 行） | 只有 `state(int)` + `events` 队列 + `getState()` | `roleId/name/title/group/personality/responsibilities/skills/interestKeywords`；`computer()/computerIfCreated()/noteStore()/todoStore()/mcpManager()/skillManager()/mailService()/timeManager()/system()/pool()`；`journal()/isBusy()/isWaiting()/queueDepth()/setState()/waitingReplyFrom()/deliverReply()/abortWait()/talkWait()/pendingTasks()/taskHistory()/addToolkit()/recordReasoning()/recordToolCall()/recordNote()/recordAnswer()/bindSystem()/bindTimeManager()` |
| `role/RolePool.java`（20 行） | 继承 `UUIDObjectManager<Role>` 的空壳 | `allRoles()/listRoles()/getRole()/getRoleByName()/getRoleOrNull()/addRole()/addRoleAndStart()/setupRole()/start()/shutdown()/getStatus()/journalAll()/assignTask()` |
| `tools/Toolkit.java` | **被删除**，但 20+ 个子类仍 `extends Toolkit` | `addTool()/getTools()/getDescription()` 基类（**28 处引用**，影响面最大） |
| `event/EventBus.java` / `event/TimeBus.java` | `EventBus` 只有 `public List<Event> events; public AgentSystem agentSystem;` + `// TODO`；`TimeBus` 完全空类 | 整个时钟/调度/事件分发（对应 master `TimeEventBus` 920 行 + `EventDispatcher` 138 行） |
| `llm/LLM.java` | 新抽象只有 `appendMessage(String)` / `request()` | **没有任何实现**；`OpenAICompatLLM` 实现的仍是旧接口（`LLM.ChatResponse`/`LLM.ToolsResponse` 已被删） |
| `io/Input.java` / `io/WebInput.java` | `StdInput.read()` 可用但忽略 `target`；`WebInput.read()` 是 `// TODO` 返回 `null` | Web 输入通道；旧 `Input.isWebPage()/isError()` 与回执匹配协议被删，但 `TalkToClient` 仍在调用 |
| `computers/PodmanComputer.java`（59 行） | 8 个重写方法全是 `// TODO` | 开机/关机/执行命令/读文件/写文件/列目录/删文件；`SSHComputer` 已删 |
| `computers/MCPServer.java`（5 行） | 空类 | master `core/MCPServer` 310 行的 stdio JSON-RPC 全部丢失；`Computer.mcpServer` 恒为 `null` |
| `computers/Computer.java`（55 行） | 抽象骨架 | `hostDir()/driveRoot()/iterMcpTools()/ensureMcpServers()` 及 MCP 安装/执行 API；`COMPUTERS_ROOT = "./data/computers"` 硬编码，绕过 `AgentSystem.dataDir`，破坏多实例隔离 |
| `llm/context/Context.java` / `Message.java` | 占位实现 | 见第 6 节：实际上**无法实例化** |
| `AgentSystem.java` | **仍是 master 版本**（69 个编译错误） | 引用 7 个已删类型；未按新设计改写 |
| `Main.java` / `web/ChatWebServer.java` / `demo/*` | **仍是 master 版本** | 同样全部编译不过 |
| `UUIDObjectManager.findObjectByUUID()` | 返回 `UUIDObject` 而非 `T` | 调用方处处需要强转；缺公开迭代/查找 API |

---

## 4. 被删掉、但仍有代码引用的类型（编译错误根因）

| 被删类型（master 行数） | 谁还在引用 |
|---|---|
| `role/AgentRole.java` (1173) | 几乎所有 toolkit、`Main`、`demo/*`、`web/*` |
| `event/TimeEventBus.java` (920) | `AgentSystem`、`Main` |
| `llm/provider/*` (ProviderManager 647 / Provider 265 / ModelInfo 63 / ProviderException 39) | 无代码引用，但 `providers.default.json`、`docs/llm-provider-manager.md` 仍在 |
| `services/MailService.java` (491) | `AgentSystem`、`email/*` 全部工具 |
| `conversation/Conversation.java` (403) + `ConversationManager.java` (60) | `AgentSystem`、`conversation/*` 测试 |
| `role/RoleLoader.java` (386) | `Main`、`WebDemo`、`ChatWebServer`、`ListCandidates` |
| `llm/RetryArbiter.java` (377) | `OpenAICompatLLM`（限速重试排序，README 重点宣传的能力） |
| `computers/ComputerManager.java` (360) | `Pc`、`LanDevices`、`AgentSystem` |
| `store/StateStore.java` (359) | `Main`（存档/恢复） |
| `core/MCPServer.java` (310) | `McpDemo` |
| `role/ToolRegistry.java` (303) | `mcp/*`（`ToolRegistry.ToolDef`） |
| `store/NoteStore.java` (273) | `note/*`、`memory/*`、`Main` |
| `web/ChatStore.java` (269) | `ChatWebServer`、`TalkTo`、`TalkToClient`、`WebDemo`、`AgentSystem` |
| `llm/provider` 见上 | 同上 |
| `role/RoleFactory.java` (187) | `PostJobPosting`（招聘即入职） |
| `utils/Json.java` (186) | `ConfigStore`、`OpenAICompatLLM`、`ChatWebServer`、`MCPManager`、HR 工具 |
| `event/EventDispatcher.java` (138) | `AgentSystem` |
| `store/TodoStore.java` (138) | `todo/*` |
| `computers/SSHComputer.java` (136) | 无引用（功能丢失） |
| `tools/Toolkit.java` (68) | 20+ toolkit 子类 |

---

## 5. 功能级缺失（对照 master 行为基线）

以下能力**全部没有可运行实现**：

1. **模拟时钟 / 班次 / 事件调度**：日历时钟、1 Tick = 1 模拟秒、08:00–18:00 班次、忙时流动、全员空闲快进、跨天滚动闸门、定时任务表、暂停冻结。
2. **事件过滤与派发**：三层过滤（状态掩码 → 关键词显著性 → 唤醒）、定向/广播事件、任务工厂、0-token 过滤。
3. **角色运行时**：虚拟线程 worker、任务队列、状态机、`talk wait=true` 同步等待与死锁防护、角色活动日志（journal）、LLM 工具循环、上下文压缩、每日总结、下班/上班生命周期。
4. **角色模板与招聘**：`role_templates.json`（55 个模板）加载、`RoleFactory` 动态生成、`post_job_posting` 招聘即入职。
5. **对话 / 上下文管理**：每角色每日对话延续、自动压缩、班次关闭、持久化。
6. **LLM 客户端**：OpenAI 兼容请求、工具调用（function calling）、限速重试（按重试次数排序）、provider 目录解析。
7. **个人电脑**：Podman/SSH/本地三种实现、基础镜像构建、桥接网络、电脑内 MCP 服务器、文件操作、开关机生命周期、并行装配、`lan_devices`。
8. **MCP**：stdio JSON-RPC 服务器、工具组规则、`mcp_search/add/remove/list/my_tools`。
9. **公司邮件**：虚拟邮箱 / 真实 SMTP、通讯录、投递后定向 `NEW_MAIL` 事件。
10. **持久化**：笔记（含提醒）、待办、统一状态快照（保存/恢复）。
11. **Web UI**：`ChatStore` 消息存储 + `ChatWebServer` + 静态资源联动。
12. **技能库**：`SkillManager`（289 行仍在，但依赖缺失基类，实际不可用）。
13. **输入通道**：控制台 `StdInput` 可用（忽略 target）；网页 `WebInput` 未实现。
14. **暂停 / 恢复**：逻辑还在旧 `AgentSystem` 里，随 `AgentSystem` 一起不可用。
15. **Demo**：`RoleDemo` / `TalkDemo` / `WebDemo` / `McpDemo` 全部编译不过。
16. **测试**：31 个测试类仍是 master 版本，未迁移。

---

## 6. 新设计自身的设计缺陷（补齐前建议先修）

- [ ] `event/Event.java:14` 的 `Builder` 是**非静态内部类**，而 `Event` 构造器是 `protected`（`:43`）→ 无法 `new Event.Builder()`；且 `Builder` 有 `content` 字段却没有 setter。
- [ ] `llm/context/Message.java:10,22,34` 的 `UserMessage/ToolMessage/AssistantMessage` 是**抽象类的非静态内部类** → 永远无法实例化，`Context` 收不进任何消息；`remember=false`（`forget`）没有任何地方消费。
- [ ] **状态类型冲突**：`Role.state` 是 `int`（`STATE_IDLE/STATE_BUSY`），而全项目使用 `Types.AgentState` 枚举；`Main.java:173`、`TalkTo.java:123` 直接类型不匹配。
- [ ] **两套事件模型并存**：`core/Types.Event`（source/eventType/priority/payload/targetRole/triggerTick）与新的 `event/Event`（from/target/targetTime/content/id）互不兼容，需二选一。
- [ ] `Event` 持有 `Role` 对象引用、`Role` 又持有 `RolePool/AgentSystem` → 强耦合、难序列化，与 `docs/agent-system-multi-instance.md` 的"每实例独立"目标相冲。
- [ ] `tools/Toolkits.java:36-41` 使用进程级 `static MCP_MANAGER/SKILL_MANAGER`，与同文件"优先用每实例对象"的注释自相矛盾；且调用的 `role.mcpManager()/skillManager()/mailService()/group` 在新 `Role` 上都不存在。
- [ ] `mcp/McpManager.java`(37 行) 与 `mcp/MCPManager.java`(185 行) 是**两个重复类**，需合并。
- [ ] `UUIDObjectManager.findObjectByUUID()` 返回 `UUIDObject` 而非 `T`，缺公开的遍历/按条件查找 API。

---

## 7. 文档 / 资源 / 测试现状

- `README.md` 描述的是 master 架构，其中 **37 处**引用已删除的类；`docs/API.md` **54 处**；`docs/agent-system-multi-instance.md` **35 处**；`docs/llm-provider-manager.md` 整体过期。
- `src/test/java` 的 31 个测试类未迁移（1111 编译错误）。作为对照，`refactor2` 已迁移为 39 类 / 314 绿。
- `src/main/resources`：`providers.default.json` 已**零代码引用**；`role_templates.json` 仅在一句注释里被提到；只有 `mcp_group_rules.json` 仍被 `MCPManager` 使用。

---

## 8. 建议实施顺序（可勾选）

> 若决定继续 refactor3。若改以 refactor2 为基线，则本节大部分无需执行。

**阶段 0：先定方向**
- [ ] 决定继续 refactor3，还是以 `refactor2`（314 绿）为基线，仅择优并入 refactor3 的简化模型。

**阶段 1：修设计缺陷（第 6 节）**
- [ ] 把 `Event.Builder`、`Message` 子类改为 `static`（或改用具名工厂）。
- [ ] 统一状态类型（`int` vs `Types.AgentState`）。
- [ ] 统一事件模型（`Types.Event` vs `event.Event`）。
- [ ] 事件/角色之间改为持有 ID 而非对象引用，保证可序列化与多实例隔离。

**阶段 2：补齐基础构件**
- [ ] 恢复 `tools/Toolkit` 基类（`addTool`/`getTools`/`getDescription`）。
- [ ] 恢复 `utils/Json`（Jackson 帮助类）。
- [ ] 重写 `role/Role`（字段 + 访问器 + 队列 + journal + 状态机钩子）。
- [ ] 重写 `role/RolePool`（注册/查找/启动/停止/状态/派发）。

**阶段 3：按子系统补齐**
- [ ] `event/TimeBus` + `EventBus`：时钟、班次、快进、跨天、调度表、暂停。
- [ ] `llm`：新 `LLM` 的实现 + 限速重试 + provider 目录（或明确砍掉 provider 并同步文档/资源）。
- [ ] `computers`：`PodmanComputer` 全实现 + `MCPServer`（stdio JSON-RPC）+ `ComputerManager` + MCP 工具安装/执行。
- [ ] `services/MailService`（虚拟邮箱 / SMTP / NEW_MAIL）。
- [ ] `store/NoteStore`、`TodoStore`、`StateStore`（含 `Main` 的存档/恢复）。
- [ ] `web/ChatStore` + `ChatWebServer`。
- [ ] `io/WebInput` 与输入回执协议（`isWebPage`/`isError`）。
- [ ] 重写 `AgentSystem`（还是 master 版本）、`Main`、`demo/*`。

**阶段 4：收尾**
- [ ] 迁移/重写 31 个测试类，跑绿。
- [ ] 同步 `README.md` 与 `docs/*`。
- [ ] 清理死资源（`providers.default.json` / `role_templates.json` 的使用方或删除）。
- [ ] 合并重复的 MCP manager 类。

---

## 附录 A：缺失符号全量清单（来自 javac 输出）

**缺失的类 / 类型（21）**

```
ChatResponse  ChatStore  ComputerManager  ConversationManager  EventDispatcher
Json  MailService  MCPServer  NoteStore  RetryArbiter  RoleFactory  RoleLoader
StateStore  Task  TaskCallback  TimeEventBus  TodoStore  ToolDef  Toolkit
ToolsResponse  Urgency
```

**缺失的字段（20）**

```
ChatStore  ComputerManager  group  interestKeywords  Json  MailService  name
NoteStore  onTaskDone  onTaskStart  personality  responsibilities  RetryArbiter
roleId  RoleLoader  skills  TimeEventBus  title  Urgency
```

**缺失的方法（按被引用次数排序，节选高频）**

```
allRoles()  computer()  computerIfCreated()  getRole()  getRoleByName()
getRoleOrNull()  getStatus()  listRoles()  listInstalledMcpTools()  mcpToolNames()
journal(String)  journalAll(String)  mailService()  mcpManager()  skillManager()
noteStore()  todoStore()  timeManager()  pool()  system()  computerManager()
conversation()  ensureMcpServers()  getMcpTool(String)  removeSingleTool(String)
uninstallMcpTool(String)  addRole(Role)  addRoleAndStart(Role)  setupRole(Role)
start()  shutdown(boolean)  setState(AgentState)  isBusy()  isWaiting()
queueDepth()  waitingReplyFrom()  deliverReply(String)  abortWait(String)
talkTo(String,String,String)  pendingTasks()  taskHistory(int)  addToolkit(...)
addTool(...)  getTools()  isError(String)  isWebPage()  describe()
iterMcpTools()  driveRoot()  bindSystem(AgentSystem)
recordReasoning(...)  recordToolCall(...)  recordNote(...)  recordAnswer(...)
builder()
```

**javac 报 "package does not exist" 的包（3）**

```
com.agent.software.conversation      (Conversation / ConversationManager)
com.agent.software.role.ToolRegistry  (ToolRegistry.ToolDef)
com.agent.software.services           (MailService)
```

（`MailService`、`RetryArbiter`、`RoleLoader`、`TimeEventBus`、`ChatStore` 也有 "package X does not exist" 的报错，但它们是**已删的类**被当作嵌套类型/静态成员引用，归入上面的"缺失的类"清单。）

**错误最集中的文件（Top 10）**

```
69  AgentSystem.java
51  tools/toolkits/talk/TalkTo.java
26  llm/OpenAICompatLLM.java
24  tools/toolkits/mcp/MCPManager.java
23  demo/WebDemo.java
20  web/ChatWebServer.java
20  demo/TalkDemo.java
19  Main.java
17  tools/toolkits/client/TalkToClient.java
15  tools/toolkits/email/MailAddressBook.java
```

---

## 附录 B：复现命令

```bash
# 主代码编译（refactor3 = 540 错误）
javac -encoding UTF-8 -nowarn -d target/check-refactor3 \
  -cp "$(cat redesign/_scratch/cp.txt)" $(find src/main/java -name '*.java')

# 测试编译（refactor3 = 1111 错误）
javac -encoding UTF-8 -nowarn -d target/check-test \
  -cp "$(cat redesign/_scratch/cp.txt):target/check-refactor3" \
  $(find src/test/java -name '*.java')

# refactor2 基线测试（314 绿）
mvn -o -Dmaven.repo.local=.m2-local test
```
