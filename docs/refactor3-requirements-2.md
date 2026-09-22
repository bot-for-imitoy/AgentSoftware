# refactor3 追加需求（先记录，暂不动架构）

> 状态：**待纳入架构设计**。这份文档只记录两条新的功能变更 + 它们对现有设计决策的冲击 + 待确认问题。
> 架构本身（`docs/refactor3-framework.md`）和已发现的 7 个运行时问题（`docs/refactor3-deep-dive.md`）本轮都不动。

---

## 0. 已记住的架构问题（本轮不动，等改架构时一起处理）

来自 `docs/refactor3-deep-dive.md` §12：

1. `Event` 持 `Role` 对象 → 建议改 `roleId`（数据库/多实例/循环引用）。
2. `ToolHandler` 只回 `boolean` → 改 `ToolResult{ok,text}`。
3. 删每日总结 → `compact()` 成承重结构，且必须按"整轮对话"丢。
4. 取消 `OFF_DUTY` → 跨天闸门要重写；`PAUSED` 由时间线程唤醒。
5. 删 `Toolkits` → 建议保留纯工厂。
6. 班次 hook 只靠事件队列 → `PAUSED`/阻塞 worker 收不到，须时间线程直调。
7. `Input` 只留 `read` → 呈现问题、网页入队、`StdInput` reader 复用要有归属。

---

## 需求 A：公司员工名单 ≠ 当前任务大组

### A.1 需求原文（我的理解）

- **默认角色 = 管理层组（Leadership Group）的全部成员**（当前 `role_templates.json` 里是 6 人：
  `CEO / COO / HR / CFO / CTO / business_analyst`）。
- `role_templates.json` 里其余 49 人**算公司员工，但不属于本次任务的大组**。
- COO 调度时可以**抽调（加入）或移除（移出）**员工，改变大组构成。
- 没进大组的人 = **"假死"**：**没有 `Role` 实例、不进 `RolePool`**，只存在于**公司员工名单**里。

### A.2 由此产生的两个概念（需要分开）

| 概念 | 内容 | 生命周期 |
|---|---|---|
| **公司员工名单 `CompanyRoster`** | 全部 55 个员工（模板数据） | 常驻；纯数据，无 Role 实例 |
| **当前任务大组 `Cohort`（现在的 RolePool）** | 被激活的员工子集 | 默认 = 管理层 6 人；COO 可增删 |

**"假死"的精确定义**：名单里有一条记录，但**没有** `Role` 对象、没有 `LLM`、没有 `Context`、
没有 `Computer`、没有事件队列、不能作为事件 `target`。

### A.3 对现有设计决策的冲击

| 位置 | 现在 | 要改成 |
|---|---|---|
| `RolePool` | 语义 = "所有角色" | 语义 = **当前大组**（活跃角色）；可能改名 `Cohort`/`ActiveGroup` |
| 新增 | — | `CompanyRoster`：`all()` / `find(roleId)` / `byGroup(g)` / `leadership()` / `isActive(roleId)` |
| `RoleLoader.DEFAULT_ROLES` | 47 个默认角色 | **只返回管理层 6 人** |
| `RoleLoader` | 之前讨论过"暂缓" | **不能暂缓**，它是名单的唯一来源 |
| COO 工具 | 原来有 HR/RoleFactory（已删） | 新增**调度工具**：`draft_in(roleId)` / `draft_out(roleId)` / `list_employees()` / `list_active()` |
| 事件投递 | 广播命中所有角色 | 只命中**大组成员**；target 指向假死员工 → 丢弃并回报发送方 |
| `talk` 范围 | master 是"同 group 内" | 应改为**大组内**（管理组 + 被抽调者，跨组可聊） |
| `uid` | 按注册顺序 `1100+seq` | 必须**按员工（名单）稳定**，否则抽调/移除后 uid 漂移 |
| 电脑/容器 | 所有角色都建 | 只有大组成员建；**移出时怎么处理**（见问题 1/2） |
| 广播放大风险 | 47 个角色 | 默认只有 6 个，风险大幅下降（deep-dive §1 的顾虑减轻） |

### A.4 待确认

1. **抽调**：加入时是否恢复该员工上次的 `Context`/电脑目录/未完成任务？还是全新实例？
2. **移除**：容器销毁还是保留停机？`data/computers/<role_id>` 数据删还是留？
3. **talk 范围**：改成"大组内"之后，原来的"同部门 group 才能 talk"限制是取消还是两者都满足才能聊？
4. **抽调权限**：只有 COO 能调用，还是管理组都能？
5. **名单是否可增长**：固定 55 人，还是保留"招聘即入职"（此前说删 `RoleFactory`/HR 工具）？
6. **默认大组**是否真的包含 CFO/CTO/business_analyst？"管理层组内所有成员"我按 6 人理解。
7. 假死员工：邮件地址簿里是否出现？（我倾向"不出现"——没有实例就没有 inbox，投递直接失败）

---

## 需求 B：甲方客户是一等通信方

### B.1 需求原文（我的理解）

- 用户（甲方客户）**有自己的邮箱**。
- 客户可以**指定当前大组内成员**（管理组或被 COO 抽调进来的人）进行**邮件**或**口头**沟通。
- 相当于给客户配了 **`email` 工具**和 **`talk_to` 工具**（`talk_to` 可选择**等待对方回复**）。

### B.2 模型

- 新增**非员工实体 `Client`**（`UUIDObject`）：`clientId` / `name` / `email` / 输入通道。
- 客户**只能寻址大组成员**（需求明确限定）。
- 两个通道，语义不同：
  - **邮件**：异步、落邮箱、可离线查看。
  - **口头 `talk_to`**：即时，走 Web/控制台；可选 `wait=true` 等回复。
- 角色侧原本就有 `talk_to_client`（反向通道），现在**双向**。

### B.3 对现有设计决策的冲击

| 位置 | 变化 |
|---|---|
| `MailService` | 地址簿要包含客户；客户有 inbox；角色 `send_email` 可以发给客户 |
| `ChatStore`/Web UI | 需要"客户 → 指定角色"的发件/聊天入口 + 客户收件箱视图 |
| `ClientCommunicationLock` | master 的"同一时刻只有一个角色和对方面谈"要重新定义：客户可主动找任意大组成员 → 可能变成**每个"客户↔角色"一条会话**，而不是全局一条 |
| `TalkToClient` | 仍存在；新增反向的客户发起路径 |
| 事件 | 客户消息 → 目标角色一条 `TALK` 或 `NEW_MAIL` 事件；不在大组 → 不允许发 |
| 输入通道 `Input` | 客户在网页"发请求"就进对应角色的会话；控制台模式同理（`read(target)` 的 `target` 可以用来区分会话） |
| 等待语义 | 客户 `talk_to(wait=true)` 时角色可能忙/下班 → UI 要能挂起并稍后呈现回复（与 deep-dive §2 的 handoff 机制同源） |

### B.4 待确认

8. **客户邮箱**：虚拟邮箱（Web UI 里看）还是真实 SMTP？真实**入站**邮件怎么进系统（收信服务 vs 只支持网页发件）？
9. **会话模型**：客户↔角色是"每角色一条会话"还是"全局单一甲方会话"？并行多人（客户同时找 CTO 和业务分析师）允许吗？
10. **客户身份**是否也进"大组"名单（作为外部成员）？还是独立于 `Cohort` 之外单独维护？
11. 客户的 `talk_to` 工具是**给 LLM 用**，还是**给人用**（UI 动作）？我理解是后者：客户是真人，工具是 UI 能力的抽象；但如果是前者（客户由 LLM 扮演），架构会不同。
12. 客户能不能同时用邮件和口头找同一个人/不同人？有没有"当前对话对象"的约束？

---

## 决策记录（你的答复）

| # | 决定 |
|---|---|
| F1 | 移出员工：数据/容器保留，只做离线（电脑关机）；State 里加一个"不在组内"状态 |
| F2 | 同部门限制**保留并叠加**；`get_role` 只显示被 COO 抽调进来的人 → **最终选 b：管理组豁免同部门限制**；客户寻址也豁免 |
| F3 | 只有 COO 能抽调 |
| F4 | 名单暂时固定，去掉招聘 |
| F5 | 默认大组 = 管理组；暂时删掉 CFO |
| F6 | 先只做虚拟邮箱，SMTP 以后接 |
| F7 | 客户同一时间只能和一个角色对话 |
| F8 | 客户只是真人的 UI 动作（不是 LLM agent） |
| A1 | `talkTo` 的等待在 `talkTo` 内部实现 |
| A2 | 新架构没有 `PAUSED`；角色平时就是 `IDLE`，下班也是 `IDLE` |
| A3 | 跨天条件 = 全员 `IDLE` 且 now > 下班时间 → 立即跳转 |
| A4 | `Event` 删 `id`；后续做 `getData()` 接口返回 `Map<String,String>`，需要存储的类都实现 |
| A5 | ToolHandler 那条是我提的提案，不是现有代码 → **最终选：新增独立文件 `llm/Response.java`，`LLM.request()` 返回 `Response`；取消 ToolHandler，工具循环放 Role** |
| A6 | 删每日总结；把之前的每日总结标记 `remember = false` |
| A7 | 通知归通知、输入归输入；`Input` 只负责输入 |
| A8 | 模板 JSON 外层键改为 `role_templates`；用现有存储类读；存储类支持多文件读取并保留最近键值 |
| A9 | 默认工具也放 JSON，从文件读 |
| A10 | 明确函数契约，条件不符就抛错；不懒创建；其它线程安全先放 |

---

## 评估：哪里合适、哪里要补

### ✅ 直接成立

- **F3（只有 COO）**：调度工具只注册给 COO 即可，无公开成员变化。
- **F4（固定名单）**：与之前"删 RoleFactory/HR"一致。
- **F6（先虚拟邮箱）**：`MailService` 抽象 + `VirtualMailService`，签名不变。
- **F8（客户是 UI 动作）**：`Client` 不需要 `Context`/`LLM`，只是一组 UI 能力 + 一个地址 + 一条当前会话。
- **F7（客户一次一个对话）**：`ClientCommunicationLock` 的语义得以保留（全局唯一客户会话）。
- **A1（talkTo 内部等待）**：同意。
- **A5**：ToolHandler 确实是我上一版在 `docs/refactor3-framework.md` §2.4 提的提案，不是已有代码。
- **A7（通知/输入分离）**：清晰，只需补 `StdInput` 复用 reader、`WebInput` 的入队管道。
- **A10（契约 + 抛错 + 不懒创建）**：方向完全同意。

### ⚠️ 需要补机制 / 有冲突

**F1（移出 = 数据保留 + 关机 + State 状态）**
- "电脑保留"要区分**磁盘数据/容器**和**内存 `Computer` 对象**。若对象也留，"没有 Role 实例"就不彻底，`ComputerManager` 里会堆积离线电脑。
  建议：移出时 `powerOff()` → 从 `ComputerManager` 移除对象；数据与容器留盘；再抽调时重建对象再 `powerOn()`。
- **"不在组内"不能塞进 `RoleState`**：`RoleState` 是 `Role` 的字段，而假死员工**没有 `Role` 实例**，状态无处可放。
  需要独立的名单层状态，例如 `MembershipState { IN_GROUP, OUT_OF_GROUP }`（持久化在 State 里）。
- 移出时其队列里的未完成任务怎么处理，需要定（建议随实例丢弃，不支持跨移出续跑）。

**F2（同部门限制叠加 + get_role 只显示抽调者）—— 这条有真实冲突**
- 规则 = 可见性（在大组）∧ 通信（同部门）。那么 COO 从前端组抽调 1 人进来后，该员工**谁也看不见、谁也聊不了**，包括抽调他的 COO（管理组 ≠ 前端组）。
- 于是 COO 只能靠**任务**给他派活、他靠**任务结果**汇报，`talk` 退化为同部门内部闲聊。
- 三个选项，需要你选一个：
  (a) 接受：talk 仅同部门，跨部门一律走任务；
  (b) **管理组豁免同部门限制**（COO/管理组可 talk 任何人）——我倾向这个；
  (c) talk 范围 = 大组内（放弃同部门）。
- 另外：客户没有部门，客户 talk 到抽调者应豁免该限制，需要明确写进规则。

**A2（没有 PAUSED，所以班次 hook 没问题）—— 不成立，问题换了个名字**
- 真正的阻塞点是 **`WAIT`**，不是 `PAUSED`：角色卡在 `talkTo(wait=true)` 时，worker **不处理自己的事件队列**，"用事件通知它下班"依然不通。
- 更严重：按 A3"全员 `IDLE` 才跳转"，一个 `WAIT` 角色不算 `IDLE` → **跨天条件永远不满足 → 时钟卡死**。而 `WAIT` 只等对方回复，对方可能已经下班不回。
- 结论：下班/停机时必须有**线程外**的动作打断等待（超时、对方下班、系统停机三个出口），由时间线程直调角色（`onShiftEnd`/`abortWait`）。方法归 Role，触发归系统。
- 第二层问题："下班也是 `IDLE`"让 `IDLE` 无法区分"可派活/不可派活"。**"下班不再接新活"必须落在事件投递层**（EventBus/TimeBus 在非工作时段 hold 或 drop 普通事件），否则 18:00 后投来的任务照干，跑到半夜。

**A3（全员 IDLE ∧ now > shiftEnd → 立即跳）**
方向对，4 个边界要补：
1. `WAIT` 必须先被强制结束（或不计入 IDLE 判定并中断）；
2. `BUSY` 跨过下班点的长任务：时钟要继续走，直到它结束，否则仿真停住；
3. "跳到"的锚点是**次日 shiftStart**，不是简单 +1 天；
4. 下班后到达的事件必须在投递层挡住，否则永远凑不齐"全员 IDLE"。

**A4（删 id + `getData(): Map<String,String>`）**
- 删 `id` 用 `uuid`：同意。
- `Map<String,String>` 意味着嵌套结构（payload、tool_calls）要序列化成 JSON 字符串塞进去，**需要约定哪些键是 JSON**。
- **需要反向接口**：`getData()` 只能存；恢复要 `loadData(Map<String,String>)`（你没提，建议补）。
- **多态恢复问题**：`Event`/`Task`、`Message` 的 3 个子类，恢复时必须先读 `type` 再决定 new 哪个 → 需要一个**类型注册表（string → factory）**。
- **Role 对象引用仍建议改 `roleId`**：`getData()` 只在落库时扁平化，运行时仍持对象引用；角色被移出销毁后，排队事件/未完成任务里会留**悬空引用**。用 `roleId` 则解析失败可直接丢弃。若坚持对象引用，必须补"移出时清理所有指向它的引用"。

**A5（工具循环）**
更干净的替代：**不要 ToolHandler**，把工具循环放在 `Role`（它有 `getTools()`/`invokeTool()`）；`LLM` 只发一次请求、返回结构化 `Response`，Role 看到 `toolCalls` 就执行、append 结果、再 request。
代价：`LLM.request()` 的返回类型要从 `String` 改成 `Response` —— **这就是需要你拍板的公开签名变更**。
无论哪种，工具结果必须是"文本 + 成功与否"，只有 boolean 不够（失败原因要回喂模型）。

**A6（每日总结标记 remember=false）**
- 需要澄清：**谁生成"每日总结"？** 我上一版已把 `LLM.summarize` 删掉。若不再生成总结，能标记的只是**前一天的普通消息**，效果是每天上班上下文只剩 system + 当天消息，**跨天记忆为零**（包括未完成约定）。
- 若接受这个效果：`remember=false` 就够，**`compact()` 可以删**；但内存里的消息会一直涨。
- 若想保留连续性：至少保留"每天一条摘要"的入口。

**A8（`role_templates` 键 + 多文件存储）**
- 现状：`role_templates.json` 是**扁平的** `{role_id: {...}}`（55 项，无外层键）。改成 `{"role_templates": {...}}` 可行。
- 多文件覆盖（后读覆盖先读、缺键保留）与现有 `providers.default.json` + `providers.local.json` 先例一致。需要定：**优先级顺序**、**合并粒度**（顶层键覆盖还是按 role 深合并）、**缺失/字段缺失的校验**。
- `RoleLoader` 从"静态常量"改为"读存储"，会有签名/取值变化。

**A9（默认工具放 JSON）**
合适，和 A8 同一套存储。需要定 schema：按 `role_id` 还是按 `group`，以及是否允许模板内直接写 `toolkits`。

**A10（契约 + 抛错）**
- `Role.getComputer()` 不懒创建后，调用方在"可能没电脑"时只能 catch 异常，很别扭 → 需要配套 `hasComputer()` 布尔查询。
- **契约替代不了数据结构**：`UUIDObjectManager.values` 是 `ArrayList`，运行时抽调/招人就是并发写。要么加锁，要么换 `CopyOnWriteArrayList`。"先放一下"在抽调功能上线时就会命中，建议这条不要缓。

---

## 需要改动的公开成员（报备）

| # | 变更 | 说明 |
|---|---|---|
| P1 | `LLM.request()` 返回 `String` → `Response`（或保留 String + 注入工具处理器） | **必须你选**，见 A5 |
| P2 | 新增 `MembershipState`（或在 Roster 上用 `boolean active`） | F1，名单层状态，不能放进 `RoleState` |
| P3 | 新增 `Data`/`Storable` 接口：`Map<String,String> getData()` + `void loadData(Map<String,String>)` | A4；一批类要实现 |
| P4 | 新增类型注册表（type → factory） | A4；`Event`/`Task`、`Message` 子类的多态恢复 |
| P5 | `RoleLoader` / `DEFAULT_ROLES` 由静态常量改为读存储 | A8；签名与取值都会变 |
| P6 | `Role.getComputer()` 不懒创建 → 需新增 `hasComputer()`（`computerOrNull` 去留待定） | A10 |
| P7 | `RoleState` 去掉 `PAUSED` | A2 |
| P8 | `talk` / `get_role` / `list_roles` 工具 schema 按 F2 的选择变 | F2 |
| P9 | `UUIDObjectManager.values`（protected 字段）改并发容器或加锁 | A10；不改 public 签名但改受保护成员 |
| P10 | 新增 COO 调度工具集（抽调/移出/查名单/查在组） | A/F |
| P11 | `Event.from/target` 是否改 `roleId`（我建议改） | A4；未定 |

---

## 下一步

建议在改架构那一轮里，把这两条需求、上面的 P1–P11、以及 `docs/refactor3-deep-dive.md` 的 7 个问题**一起**处理。
动手前先切回 `refactor3`（当前工作区停在 `master`）或另开分支。
