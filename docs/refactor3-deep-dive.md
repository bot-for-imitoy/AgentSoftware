# refactor3 关键机制与风险（深度复核）

> 这份文档不解释"要哪些方法"，而是回答"这些决定在运行时会发生什么、哪里会出硬伤"。
> 每条：**你的反馈 → 隐藏问题 → 建议**。
> 配套：`docs/refactor3-framework.md`（公共 API）。

---

## 1. 事件循环

**反馈**：事件不再过滤，只要 `target` 命中就处理，队列按优先级排序。

**隐藏问题**

1. `PriorityQueue` **不保证同优先级 FIFO**，也**不是线程安全的**。同优先级的两个消息谁先处理是随机的；`EventBus` 在时间线程投递、Role worker 在另一线程取，会出并发问题。
2. **广播事件（`target == null`）会命中所有角色**。47 个角色 × 每条低价值广播 = 47 次唤醒。没有过滤层时，这是唯一会失控的入口。
3. 角色 `PAUSED`（下班）时事件还在进队列：要么无限堆积，要么必须定丢弃策略。
4. `EventBus` 用 `List<Event>` 线性扫到期事件，每天 86400 tick，每 tick 扫一遍是 O(n·ticks)。
5. **投递不能阻塞时间线程**。如果 `deliver` 在时间线程上等角色队列锁，时钟会被角色拖住。

**建议**

- 队列用 `PriorityQueue` + 显式序号做同优先级 FIFO：`Comparator.comparing(Priority).thenComparingLong(seq)`，配一把锁（`ReentrantLock` + `Condition`）。**不要**直接用 `PriorityBlockingQueue`（同样不稳定）。
- 广播保留，但加一条极便宜的闸门：`Role.accepts(EventType)`（一个 `Set<EventType>` 字段），不属于自己的类型直接丢。这不是恢复三层过滤，是防止广播放大。
- `PAUSED` 策略二选一，必须写死：**(a)** 仍入队、下个班次处理（会堆积，需要上限）；**(b)** 只收 `SHIFT_START` 等控制事件，其余丢弃。我建议 (b) + 队列上限。
- 到期事件表用 `TreeMap<Long, List<Event>>`（按 targetTime），`nextDue()` 取 firstKey，快进直接跳。
- `deliver` 只做"拿角色队列锁 → offer"，绝不同步等待。

---

## 2. `talkTo` 的等待（最关键的一处）

**反馈**：只留 `talkTo`，回复也用 `talkTo` 完成，删掉 `deliverReply/waitForReply/beginWait/...`。

**隐藏问题**

1. 如果 A 的 worker 线程在 `talkTo(B, wait=true)` 里**阻塞等待**，那 A 的 worker 就**无法再处理自己的事件队列**。B 的回复如果走正常事件队列投给 A，A 永远收不到 → **死锁**。
2. 如果 A、B 互相 `talkTo(wait=true)`，即使回复能直达，也会**环形等待**。
3. 下班时，A 还阻塞在等待里，时间线程要收工——**时间线程不能通过 A 的事件队列通知它**（A 的 worker 正卡着）。
4. "回复也用 `talkTo` 完成"在语义上没问题，但**投递路径**必须和普通事件区分开，否则就是上面的死锁。

**建议**（基本沿用 master 已被验证的机制，只是收进内部）

- `talkTo` 是唯一 public 入口，内部做三件事：
  1. 目标正在等待自己 → **直接交接**：把回复塞进对方的 handoff（`CompletableFuture` 或专用小队列），不走事件队列。`deliverReply` 保留为 **package-private**，不对外。
  2. 否则把消息作为 `TALK` 事件投给对方的事件队列。
  3. 若 `wait=true`，自己阻塞在 handoff 上，但**注册进"等待图"**；`waitForReply` 内部检查等待图，发现环就返回失败而不是死等。
- 等待要有三个出口：**对方回复 / 超时 / 被外部中断**（下班、停机）。中断由时间线程直接调用 `role.onShiftEnd()` 触发（见下节）。
- 收益：public API 还是只有 `talkTo`，但 `WAIT` 状态、超时、环检测、下班唤醒这些机制必须有归属，不能删掉。

> 一句话：你删的是**公开方法**，不能连**机制**一起删。

---

## 3. 上下班 / `PAUSED` / 跨天

**反馈**：取消 `OFF_DUTY`，只做模拟上下班，下班后暂停角色。

**隐藏问题**

1. `PAUSED` 的 worker 不处理事件，所以 **`SHIFT_START` 也叫不醒它**——必须由**时间线程直接调 `role.onShiftStart()`**，不能走队列。
2. `onShiftStart/onShiftEnd` 会被**非 worker 线程**调用，且可能与 worker 并发 → 必须线程安全。
3. master 的跨天判定依赖"全员 `OFF_DUTY`"。取消 `OFF_DUTY` 后，**闸门条件没了**：什么时候可以滚到明天 08:00？
4. 下班时队列里没跑完的任务怎么办？（master 是留到下个班次。）

**建议**

- 明确分工：**班次切换 = 时间线程 → `AgentSystem`（private）→ 逐角色直调 `onShiftStart/onShiftEnd`**。这两个 hook 在 Role 上，但调用方是时间线程。这也回答了你"Role 的事归 Role"——方法是 Role 的，触发是系统的。
- 新闸门定义（简化版，和你"不要 OFF_DUTY"一致）：
  `shiftEnd 到点 → (可选)等正在跑的任务结束或超时 → 所有角色 PAUSED → 时钟跳到次日 shiftStart → 所有角色 resume`。
  不再等"每日总结"，因为总结已删。
- 下班后队列任务：**保留**（下个班次继续），但要设队列上限，防止下班期间堆积。
- `PAUSED` 只是"不开始新任务"；`stop()` 才是终止线程。两个语义别混。

---

## 4. `Event` 的身份与引用

**反馈（我的初稿）**：`Event extends UUIDObject` 且保留 `id` 字段；`from/target` 是 `Role` 对象。

**隐藏问题**

1. `UUIDObject.uuid` 和 `Event.id` **是两份身份**，序列化/相等性会打架。二者只能留一个。
2. `Event` 持有 `Role` 对象引用，而 `Role` 又持有 `AgentSystem`/`RolePool` → **循环引用图**。你明确说存储以后要用数据库：一个事件要落库时，要么把整棵对象图序列化（包含电脑、上下文、工具……），要么做一层扁平化。多实例同理。
3. 外部事件（邮件、cron、手工注入）**没有 `from` Role**，`from` 不能是必填对象。

**建议**

- `Event extends UUIDObject`，**删掉 `id`，用 `uuid`**。
- 目标/来源改存 **id 字符串**：`String fromRoleId, targetRoleId`；`EventBus.resolveTargets` 用 `RolePool` 解析。广播 = `targetRoleId == null`。这样事件是**纯数据、可落库、可跨实例**。
- 如果坚持 `Role` 对象引用（写起来确实顺手），那至少要保证：`Event` 有 `toRecord()` 只输出 id、`fromRecord()` 由 `RolePool` 回填，且**不把 Event 直接交给数据库层**。我倾向前者。

---

## 5. `Role` 自装配与初始化顺序

**反馈**：装配/启动是 Role 自己的事，`RolePool` 只 `addRole`。

**隐藏问题**

1. Role 要自装配就得拿到 `AgentSystem`（LLM 工厂、电脑工厂、邮件、工具），于是 `AgentSystem → RolePool → Role → AgentSystem` 成环。
2. "addRole 只注册、不启动"后，**招聘进来的新角色谁负责 start()**？如果忘了，新人永远不干活。

**建议**

- 明确三段式，避免构造期成环：
  `RoleLoader` 造纯数据 Role → `role.bind(system)`（只存引用）→ `role.setup()`（建 LLM/Context/Computer、装默认工具）→ `role.start()`。
- `RolePool.addRole(r)` 内部做 `bind + setup`（因为 pool 有 system），但**不 start**；需要即入职的场景由调用方显式 `r.start()`。或者干脆 `addRole` 只注册、把 `bind/setup` 放在 `Role` 的 builder 里——二选一，写进注释，别含糊。
- "非 Role 专属的一律走 `getSystem()`"没问题，但 **`getSystem()` 在 `bind` 之前必须返回 null 且所有调用点判空**，否则 NPE。

---

## 6. `Task extends Event` 的边界

**反馈**：Task 继承 Event，优先级沿用 Priority。

**隐藏问题**

1. 不是所有事件都是任务：`SHIFT_START/NEW_MAIL/TALK` 是通知，`Task` 是"要执行的工作"。队列里两种混在一起，worker 必须能分流。
2. `Task` 落库时需要 `status/result/tokens`；`Event` 没有。继承没问题，但**持久化时不能只存基类字段**。
3. `Task` 的 `targetTime` 对"立即执行"没意义（填当前 tick），语义会含糊。

**建议**

- 保留继承，但给 `Event` 加一个 `EventType` 判别，worker 用 `switch(type)` 分流；`Task` 固定 `type = TASK_DUE`（或新增 `TASK`）。
- `Role.enqueue(Event)` 即入队；`Task` 就是可执行事件，不需要单独的 `addTask/pendingTasks` 一套 API。
- 持久化按具体类型 `toRecord()`，不要按 `Event` 基类存。

---

## 7. LLM：`request()` + handler + `Response`

**反馈**：只留 `request()`；工具调用交给 handler，判断 handler 是否成功。

**隐藏问题**

1. **`boolean` 不够**。工具执行失败时，模型需要看到**失败文本**才能自我修正（master 一直把错误串回喂）。只回一个 `false`，模型不知道发生了什么。
2. LLM 要向 API 声明工具列表（function calling 的 `tools` 字段）。如果 `request()` 无参数，工具规格必须挂在 LLM 或 handler 上。
3. 一次 `request` 可能返回多个 tool call；handler 要被调用多次，全部结果回喂后才发下一次请求。
4. 重试要区分**可重试**（429/5xx/超时）和**不可重试**（400/401）；余额不足还要触发自动暂停。全塞进 `OpenAICompatLLM` 可以，但别把这三类混成一个 `catch`。
5. 删掉 `summarize` 后，README 里"每日总结"这条能力就没有了（见 §8）。

**建议**

- 让 handler 同时提供"工具规格"和"执行结果"：

```java
public interface ToolHandler {
    List<Tool> getTools();                                   // LLM 向它要 function 声明
    ToolResult onToolCall(String name, Map<String,Object> args);
}
public final class ToolResult {
    public final boolean ok;
    public final String text;                                // 成功是结果，失败是错误文本
    public ToolResult(boolean ok, String text);
}
```

- `LLM` 完全不需要认识 `Tool`；`request()` 自己跑循环：请求 → 有 tool call 就逐个 `onToolCall` → 把 `ToolResult.text` 作为 tool 消息追加 → 再请求，直到无 tool call 或 handler 返回失败。
- 这样 `requestWithTools` 确实可以删，你的"只留 request"成立。

---

## 8. 删掉每日总结之后，上下文靠什么不爆

**反馈**：删 `summarize`（每日总结）。

**隐藏问题**

1. master 用"每日总结 + 隔天注入摘要"来压上下文。删掉后，`Context` 只增不减，几天就会超过模型窗口。
2. `Context.compact()` 如果只是"删最旧的消息"，会**破坏 API 结构**：OpenAI 要求 `assistant(tool_calls)` 后面必须紧跟对应的 `tool` 消息，单删一半会直接 400。
3. `forget()/remember` 是个弱化的压缩，和 `compact()` 语义重叠。

**建议**

- `compact(keepLastN)` 必须**按整轮对话删除**（一个 assistant + 它的全部 tool 结果算一轮），不能按单条删。
- 上下文预算：`Context.estimatedTokens()` + `needsCompaction(budget)`；超了就丢最旧整轮，直到回到预算内。
- 如果发现丢整轮会丢关键信息，再加一个**轻量摘要**（只摘要被丢弃的那些轮次，追加成一条 system 消息），这比"每日总结"便宜，也保住了连续性。这条可以晚点做，但**位置要留**。
- `remember/forget` 建议删掉，统一由 `compact` 管。

---

## 9. `Input` 只有 `read()` 之后

**反馈**：`Input` 只暴露 `read()`，删 `isWebPage/isError/close` 和 `WebInput.bind`。

**隐藏问题**

1. 一次"问客户端"是**两半**：先"把问题呈现出去"，再"读回答"。你只留下了读。呈现那一半必须有归属——否则控制台不打印问题、网页不显示问题。
2. 读失败/无输入的语义没了。主循环拿到 `null` 不处理就 NPE。
3. `StdInput.read()` 现在每次 `new BufferedReader(new InputStreamReader(System.in))`。第二次调用时前一个 reader 可能已经吞掉了缓冲区里的字节 → **偶发丢输入**。应该持有一个 reader 字段。
4. 网页输入：删了 `bind` 就得有别的地方把"浏览器提交的字符串"送进 `WebInput` 的队列。谁送？

**建议**

- 呈现那一半放**客户端工具**（`TalkToClient`）：控制台 `System.out`，网页写 `ChatStore`。`Input` 只管读。
- 语义写死：`read(target)` **返回 null = 无输入/通道关闭**，其余都是客户端内容。`TalkToClient` 必须处理 null。
- `StdInput` 持有单个 `BufferedReader` 字段。
- `WebInput` 内部一个按 `target` 分组的阻塞队列，由 `ChatWebServer` 在收到提交时 `offer`。这其实就是原来 `bind` 想做的事，只是换了个位置——**位置可以改，管道不能没有**。

---

## 10. 暂缓存储的连锁反应

**反馈**：存储类（Note/Todo/State/RoleLoader）暂时不接入，以后用数据库。

**隐藏问题**

1. `RoleLoader` 也在"存储类"里被暂缓 → **启动时 47 个默认角色从哪来**？没有角色就没有模拟。
2. `StateStore` 暂缓 → 没有存档/恢复，`Main` 的"跨天续跑"只能内存里跑。
3. note/todo/memory 工具包的后端就是这些 store → 这三个工具包也得一起暂缓，否则默认工具集缺后端。

**建议**

- 把 RoleLoader 从"存储"里**摘出来**：它是**模板读取**，不是持久化。至少需要一个纯内存（或直接读 JSON、不落库）的实现，否则起不来。
- StateStore 可以真暂缓；`Main` 去掉 restore，只做内存多日循环。
- note/todo/memory 三个工具包本轮先不装进默认集。

---

## 11. 线程安全清单（现在骨架里会出问题的地方）

| 位置 | 问题 | 建议 |
|---|---|---|
| `UUIDObjectManager.values`（`ArrayList`） | 运行时招人 = 并发 `add`，`all()` 遍历会 `ConcurrentModificationException` | `CopyOnWriteArrayList` 或读写锁 |
| `Role` 事件队列 | 时间线程投递 vs worker 取 | 锁 + `Condition`，同优先级序号 |
| `Role.getComputer()` 懒创建 | 并发下可能建两个电脑 | 双重检查或 setup 阶段创建 |
| `Context` / messages | 目前假设只有 worker 访问；快照会并发 | 明确"仅 worker 线程访问"，快照走锁 |
| `EventBus` 到期表 | 时间线程写、`Main` 读 | 同一把锁或不可变快照 |
| `Toolkit.tools` | 构造后只读 | 构造完冻结，不要运行时增删 |

---

## 12. 对你反馈的 7 处保留意见（汇总）

1. **Event 持 `Role` 对象** → 建议改持 `roleId` 字符串；否则数据库/多实例会被循环引用拖死。（§4）
2. **handler 只回 `boolean`** → 建议回 `ToolResult{ok,text}`，模型需要错误文本。（§7）
3. **删每日总结** → `compact()` 变成承重结构，且必须按整轮对话丢；位置要留摘要扩展点。（§8）
4. **取消 `OFF_DUTY`** → 跨天 rollover 的原判定失效，需要重写闸门；`PAUSED` 必须由时间线程唤醒。（§3）
5. **删 `Toolkits`** → 会让 `Role` 直接 import 全部 9 个工具包。建议保留一个**纯工厂**（无 static 单例），Role 调它即可。（§5 相关）
6. **班次 hook 只靠事件队列** → `PAUSED`/阻塞中的 worker 收不到事件，`onShiftStart/onShiftEnd` 必须由时间线程直调。（§3）
7. **`Input` 只留 `read`** → 可以，但"呈现问题"和"网页入队"两件事要有新归属；`StdInput` 的 reader 要复用。（§9）

其余反馈（不继承 `UUIDObjectManager`、`snakeCase` 移出、`ensure*` 拆开、`destroy` 归 Computer、`connectError` 改抛异常、`MailService` 抽象化、`ChatStore` 精简、`AgentSystem` 极简、常量 private）我都同意，已写进 `refactor3-framework.md`。
