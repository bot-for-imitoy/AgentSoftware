# refactor3 框架设计（v3）

> 合并了：公共 API + 需求 A（员工名单/大组）+ 需求 B（甲方客户）+ 全部已定决策。
> 运行时机制与风险见 `docs/refactor3-deep-dive.md`；需求评估与公开成员报备见 `docs/refactor3-requirements-2.md`。
> **只列 public 签名，不含实现。**

## 0. 本次更新（相对上一版）

| 变更 | 说明 |
|---|---|
| **F2 = b** | `talk` 范围 = 同部门，但**管理组豁免**（管理组可 talk 大组内任何人）；`get_role/list_roles` 只显示大组成员；客户寻址豁免部门限制 |
| **P1** | 新增独立文件 `llm/Response.java`；`LLM.request()` 返回 `Response`；**取消 `ToolHandler`**，工具循环放 `Role` |
| P11 | `Event.from/target` 改为 `fromRoleId/targetRoleId`（字符串） |
| A2/A3 | `RoleState` 去掉 `PAUSED`；跨天条件 = 全员 `IDLE` 且 now > 下班时间 |
| A4 | 新增 `Data` 接口（`getData()/loadData()`）+ 类型注册表 |
| A8/A9 | 角色模板与默认工具改为 JSON 配置，经存储类多文件覆盖读取 |
| F1 | 移出 = 关机 + 数据保留 + 名单层 `MembershipState` |
| A10 | 明确线程契约；不懒创建；`UUIDObjectManager` 换并发容器 |

---

## 1. 三个概念层

| 层 | 是什么 | 有没有 `Role` 实例 | 持久化 |
|---|---|---|---|
| **公司员工名单 `CompanyRoster`** | 全部员工（模板数据 + 在组状态） | 否 | 是 |
| **当前大组 `RolePool`** | 被 COO 抽调进来、正在参与本次任务的员工 | 是（含电脑/LLM/Context/工具/队列） | 运行时 |
| **甲方客户 `Client`** | 真人，一组 UI 能力 + 一个邮箱 + 一条当前会话 | 否（不是员工） | 邮箱、会话 |

**"假死"定义**：名单里存在 → 没有 `Role` 实例、不在 `RolePool`、没有电脑/LLM/Context/队列；
不能作为事件 target；不能收邮件；只在 `get_role/list_employees` 这类名单查询里出现（且按 F2 只显示在组者）。

---

## 2. 已定决策汇总

| # | 决策 |
|---|---|
| D1 | `Event.targetTime` = 绝对 tick；`TimeBus` 唯一时间源 |
| D2 | 事件不过滤，按 target 命中 + `Priority` 排序入 Role 队列；下班后普通事件由 EventBus 暂存 |
| D3 | `Task extends Event`，优先级沿用 `Priority` |
| D4 | `RoleState = {IDLE, BUSY, WAIT}`（**无 PAUSED**）；下班后仍是 IDLE |
| D5 | `Toolkit` 是普通容器，不继承 `UUIDObjectManager` |
| D6 | 无进程级单例；全部由 `AgentSystem` 持有，Role 用 `getSystem()` |
| D7 | 删 `core.Types.Event` / `Types.AgentState` |
| D8 | LLM 只有 `request()`，返回 `Response`；工具循环在 `Role` |
| D9 | note/todo/state 持久化暂缓；**角色模板读取不暂缓** |
| D10 | `MailService` 抽象；本轮只实现 `VirtualMailService` |
| D11 | 电脑只留安装/卸载/启动/停止；删 `ensure*`/`hostDir`/`workdir`/`driveRoot`/`describe`/`isAutoMcp` |
| D12 | `Input` 只暴露 `read()` |
| D13 | 员工名单固定 55→54（删 CFO），无招聘 |
| D14 | 默认大组 = 管理组（CEO/COO/HR/CTO/business_analyst） |
| D15 | 只有 COO 能抽调/移出 |
| D16 | 客户同一时间只能和一个角色对话（口头）；客户只是真人 UI 动作 |
| D17 | talk = 同部门 ∧ 在组；**管理组豁免部门限制** |
| D18 | 移出员工：电脑关机、数据保留、名单状态置 `OUT_OF_GROUP`；Role 实例销毁 |
| D19 | `Event` 删 `id`（用 `uuid`）；跨引用一律用 id 字符串 |
| D20 | 新增 `Data` 接口 + 类型注册表，支持 `Map<String,String>` 存取与多态恢复 |
| D21 | 角色模板键改为 `role_templates`；存储类支持多文件读取、保留最近键值 |
| D22 | 默认工具集写入 JSON，从文件读 |
| D23 | 不懒创建；状态不符抛错；其它线程安全暂缓 |

---

## 3. 逐类 API

### 3.1 `utils`

```java
public abstract class UUIDObject {
    public final String uuid;
    public UUIDObject();
    public UUIDObject(String uuid);
    @Override public boolean equals(Object o);       // 按 uuid
    @Override public int hashCode();
    @Override public String toString();
}

public abstract class UUIDObjectManager<T extends UUIDObject> {
    protected final List<T> values;                  // [改] 并发安全容器（CopyOnWriteArrayList 或加锁）
    public void add(T value);
    public boolean remove(T value);
    public T removeByUUID(String uuid);
    public boolean contains(T value);
    public boolean containsUUID(String uuid);
    public T findObjectByUUID(String uuid);          // [改] 返回 T
    public T find(Predicate<T> p);
    public List<T> findAll(Predicate<T> p);
    public List<T> all();
    public int size();
    public boolean isEmpty();
    public void clear();
}

/** [新] 所有需要持久化的类都实现；只用字符串键值，嵌套结构自行序列化成 JSON 字符串。 */
public interface Data {
    Map<String, String> getData();
    void loadData(Map<String, String> data);
}

/** [新] 多态恢复用：type 字符串 → 工厂。Event/Task、Message 三个子类都靠它。 */
public final class DataRegistry {
    public static void register(String type, Supplier<? extends Data> factory);
    public static Data create(String type);
    public static boolean supports(String type);
}

public final class Json {                            // [补] 被删
    public static Map<String,Object> parseObject(String s);
    public static List<Object> parseArray(String s);
    public static String stringify(Object o);
    public static String stringifyPretty(Object o);
    public static Map<String,Object> readFile(Path p);
    public static void writeFile(Path p, Object o);
}
```

### 3.2 `event`

```java
public class Event extends UUIDObject implements Data {
    public final String fromRoleId;                  // [改] 用 id，不用 Role 对象；外部事件可为 null
    public final String targetRoleId;                // null = 广播（只广播给大组）
    public final long targetTime;                    // 绝对 tick
    public final String content;
    public EventType type;
    public Priority priority;
    public Map<String,Object> payload;
    public long createdAt;
    public String source;

    public Event(String fromRoleId, String targetRoleId, long targetTime, String content);
    public Event(String uuid, String fromRoleId, String targetRoleId, long targetTime, String content);

    public static Builder builder();
    public static final class Builder {              // [改] 必须 static
        public Builder from(String roleId);
        public Builder to(String roleId);
        public Builder at(long tick);
        public Builder content(String c);
        public Builder type(EventType t);
        public Builder priority(Priority p);
        public Builder payload(Map<String,Object> m);
        public Builder uuid(String uuid);
        public Event build();
    }

    public boolean isBroadcast();                    // targetRoleId == null
    public boolean isDue(long now);
    public boolean targetedAt(String roleId);
    @Override public Map<String,String> getData();
    @Override public void loadData(Map<String,String> data);
    @Override public String toString();
}

public enum EventType {
    SHIFT_START, SHIFT_END, TASK, TALK, NEW_MAIL, REST, TICK, CUSTOM;
    public static EventType from(String s);
}

public enum Priority { LOW(1), NORMAL(3), HIGH(6), EMERGENCY(10);
    public final int value;
    public static Priority from(int v);
}

public class Task extends Event {
    public static final String PENDING="pending", RUNNING="running", DONE="done", FAILED="failed";
    public String status;
    public String result;
    public int tokensConsumed;

    public Task(String fromRoleId, String targetRoleId, long targetTime, String content, Priority priority);
    public void markRunning();
    public void markDone(String result, int tokens);
    public void markFailed(String err);
    public boolean isFinished();
    @Override public Map<String,String> getData();
    @Override public void loadData(Map<String,String> data);
}

public class TimeBus {
    public TimeBus();
    public TimeBus(LocalDate baseDate);

    public synchronized void start();
    public synchronized void stop();
    public boolean isRunning();

    public long now();
    public void setNow(long tick);
    public void advanceTo(long tick);
    public void advance(long ticks);
    public long ticksPerDay();

    public LocalDate currentDate();
    public int getDay();
    public long getTickOfDay();
    public long getShiftStartTick();
    public long getShiftEndTick();
    public String currentTime();
    public String currentDateTime();
    public boolean isWorkingHours();                 // now 在 [shiftStart, shiftEnd)

    public void pause();                             // 系统级暂停（冻结时钟）
    public void resume();
    public boolean isPaused();

    public void addTickListener(Consumer<TimeBus> l);
    public void removeTickListener(Consumer<TimeBus> l);

    // 🔒 setIdleChecker / setBusyChecker / setRolloverHook / setSecondsPerTick —— 由 AgentSystem 注入
}

public class EventBus {
    public EventBus();
    public void bind(AgentSystem system, TimeBus timeBus);

    // 排期
    public String schedule(Event e);
    public String schedule(Event e, long tick);
    public boolean cancel(String eventId);
    public List<Event> scheduled();
    public Event nextDue();

    // 投递（tick 回调驱动）
    public void tick(long now);
    public void post(Event e);                       // 立即投递
    public void deliver(Event e);                    // 解析 target → 入 Role 队列；不在大组则丢弃并回报
    public List<Role> resolveTargets(Event e);       // 广播 = 全部大组成员

    // 下班后暂存（D2：非工作时段不派普通活；控制事件放行）
    public void hold(Event e);
    public void releaseHeld();
    public List<Event> heldEvents();

    public List<Map<String,String>> snapshot();
    public void restore(List<Map<String,String>> r, RolePool pool);
}
```

> **下班规则落点**：`EventBus.deliver` 检查 `timeBus.isWorkingHours()`。非工作时段：控制事件（`SHIFT_START/SHIFT_END`）放行，其余进 `hold`，次日 `SHIFT_START` 时 `releaseHeld()`。
> 这样 `IDLE` 才能保持"全员可跳转"。

### 3.3 `role`

```java
public enum RoleState { IDLE, BUSY, WAIT }           // [改] 去掉 PAUSED/OFF_DUTY

public enum MembershipState { IN_GROUP, OUT_OF_GROUP } // [新] 名单层，持久化在 roster/State

/** [新] 名单条目：轻量数据；假死员工只有它，没有 Role。 */
public final class Employee implements Data {
    public final String roleId;
    public final String name;
    public final String group;
    public MembershipState membership;
    public Map<String,String> template;              // 模板原始字段（JSON 字符串值）
    @Override public Map<String,String> getData();
    @Override public void loadData(Map<String,String> data);
}

/** [新] 公司员工名单：全部员工（默认大组来自管理组）。 */
public final class CompanyRoster implements Data {
    public CompanyRoster(List<Employee> employees);
    public List<Employee> all();
    public Employee find(String roleId);
    public Employee findByName(String name);
    public List<Employee> byGroup(String group);
    public List<Employee> byMembership(MembershipState s);
    public List<Employee> defaultCohort();           // 管理组
    public int size();
    @Override public Map<String,String> getData();
    @Override public void loadData(Map<String,String> data);
}

public final class Role extends UUIDObject implements Data {
    // 配置（从 Employee.template 构建）
    public final String roleId;
    public final String name;
    public final String username;
    public final int uid;                            // [改] 绑定员工，不再按注册顺序
    public final String title;
    public final String group;
    public final String responsibilities;
    public final String personality;
    public final List<String> skills;
    public final String email;

    public Role(Employee employee);
    public Role(String uuid, Employee employee);

    // 状态
    public RoleState getState();
    public void setState(RoleState s);               // 🔒 状态不符时抛 IllegalStateException
    public boolean isBusy();
    public boolean isIdle();
    public boolean isWaiting();

    // 事件队列（优先级 + 同优先级 FIFO）
    public void enqueue(Event e);
    public Event pollEvent();
    public Event peekEvent();
    public int queueDepth();

    // 生命周期
    public void setup();                             // 建 LLM/Context/Computer + 装默认工具；重复调用抛错
    public void start();
    public void stop();
    public boolean isRunning();
    public void onShiftStart();                      // ⚠ 时间线程直调
    public void onShiftEnd();                        // ⚠ 时间线程直调；内部终止 talkTo 等待

    // 协作者（非 Role 专属的一律走 getSystem()）
    public AgentSystem getSystem();
    public Computer getComputer();                   // ⚠ 不懒创建：未 setup 就抛 IllegalStateException
    public boolean hasComputer();
    public Context getContext();
    public LLM getLlm();
    public void setLlm(LLM llm);

    // 工具（工具循环在这里）
    public void addToolkit(Toolkit t);
    public void addTool(Tool t);
    public List<Tool> getTools();
    public ToolResult invokeTool(String name, Map<String,Object> args);   // [新] ok + 文本

    // 对话（唯一入口；同部门 ∧ 在组；管理组豁免；等待/环检测/交接全内置）
    public boolean canTalkTo(Role target);
    public String talkTo(String targetRoleId, String message, Priority urgency);
    public String talkToClient(String message, boolean wait);

    // 日志 / trace
    public void journal(String entry);
    public List<String> readJournal();
    public void recordReasoning(String text, String taskId, Integer round);
    public void recordNote(String content, String taskId, Integer round);
    public void recordToolCall(String tool, String args, String result, String taskId, Integer round);
    public void recordAnswer(String text, String taskId, String status, Integer tokens);

    @Override public Map<String,String> getData();
    @Override public void loadData(Map<String,String> data);
}

public final class RolePool extends UUIDObjectManager<Role> {
    public RolePool();
    public RolePool(AgentSystem system);
    public void addRole(Role r);                     // 只注册；装配/启动是 Role 的事
    public boolean removeRole(String roleId);
    public Role find(String roleId);                 // 找不到返回 null
    public Role findByName(String name);
    public List<Role> byGroup(String group);
    public void start();                             // 批量启动大组
    public void stop();
    public boolean isManagement(Role r);             // 管理组判定（talk 豁免用）
}

/** [新] 抽调/移出（COO 专用能力）。 */
public final class Staffing {
    public Staffing(AgentSystem system);
    public Role draftIn(String roleId);              // 建 Role → bind → setup → start → 入池；电脑开机
    public void draftOut(String roleId);             // 停 worker → Role 出池销毁；电脑关机、数据保留；状态置 OUT_OF_GROUP
    public List<Employee> employees();               // 名单全体
    public List<Role> active();                      // 当前大组
}
```

> `draftIn/draftOut` 只有 COO 的工具集能调（D15）。移出时如果该员工还在某个排队事件里被引用，按 D19 用 `roleId` 查不到就丢弃。

### 3.4 `llm`

```java
public abstract class Message extends UUIDObject implements Data {
    public final String content;
    public boolean remember = true;                  // 只影响是否进 prompt，不影响是否留内存
    public long timestamp;
    public abstract String getRole();
    @Override public Map<String,String> getData();
    @Override public void loadData(Map<String,String> data);
}
public final class UserMessage      extends Message { public UserMessage(String content); }
public final class AssistantMessage extends Message {
    public final List<Map<String,Object>> toolCalls;
    public final String reasoning;
    public AssistantMessage(String content);
    public AssistantMessage(String content, List<Map<String,Object>> toolCalls, String reasoning);
}
public final class ToolMessage extends Message {
    public final String toolCallId, name;
    public ToolMessage(String toolCallId, String name, String content);
}

public final class Context extends UUIDObjectManager<Message> implements Data {
    public Context();
    public List<Message> messages();                 // 只返回 remember=true（喂 LLM）
    public List<Message> history();                  // 全量（含 remember=false）
    public List<Message> find(String pat);
    public Message last();
    public long estimatedTokens();
    public void forget(Message m);                   // 标记 remember=false
    public void forgetAll();                         // [补] 下班/换天时把当前消息全部标记 false
    public void clear();
    public int getDay();
    public void endDay(int day);                     // getDay 前进；旧消息由 forgetAll 处理
    @Override public Map<String,String> getData();
    @Override public void loadData(Map<String,String> data);
}

public abstract class LLM implements Data {
    public LLM();

    public abstract String getModel();
    public abstract String getEndpoint();
    public void setTemperature(double t);   public double getTemperature();
    public void setMaxTokens(Integer n);    public Integer getMaxTokens();

    public Context getContext();            public void setContext(Context c);
    public void setSystemPrompt(String p);  public String getSystemPrompt();

    public void appendMessage(Message m);
    public void appendUserMessage(String text);
    public void appendAssistantMessage(String text);
    public void appendAssistantMessage(String text, List<Map<String,Object>> toolCalls);
    public void appendToolResult(String toolCallId, String name, String result);

    public abstract Response request();               // [定] 返回结构化 Response；工具循环在 Role

    public int getTotalTokens();
    public void resetTokens();
    public void close();
    @Override public Map<String,String> getData();
    @Override public void loadData(Map<String,String> data);
}
```

```java
// 独立文件（你定的）
public final class Response {
    public final String text, reasoning;
    public final List<Map<String,Object>> toolCalls;
    public final int tokens;
    public Response(String text, String reasoning, List<Map<String,Object>> toolCalls, int tokens);
    public boolean hasToolCalls();
    public int getTotalTokens();
}
```

```java
public class OpenAICompatLLM extends LLM {
    public OpenAICompatLLM(String apiKey, String model);
    public OpenAICompatLLM(String apiKey, String model, ConfigStore config);
    // 🔒 重试内置；区分可重试(429/5xx/超时) / 不可重试(400/401)；余额不足触发系统暂停
}
```

> **工具循环（在 Role，不在 LLM）**：Role 取到 `Response` → 若 `hasToolCalls()` → 逐个 `invokeTool` → `appendToolResult(...)` → 再次 `request()`；`ToolResult.ok=false` 时停止。
> 因此 `LLM` 完全不需要认识 `Tool`，也不需要 `ToolHandler`。

### 3.5 `tools`

```java
public abstract class Tool {
    public String getToolName();
    public Map<String,Object> getSchema();
    public Map<String,Object> getInputSchema();
    public String getDescription();
    public abstract String handler(Map<String,Object> args);   // 🔒 由 invokeTool 包成 ToolResult
}

/** [新] OpenAI function 声明放这里。 */
public class OpenAITool extends Tool {
    public Map<String,Object> toSpec();
}

/** [新] 工具执行结果（成功与否 + 文本），失败文本要回喂模型。 */
public final class ToolResult {
    public final boolean ok;
    public final String text;
    public ToolResult(boolean ok, String text);
}

public abstract class Toolkit {
    private final List<Tool> tools;
    public String getName();
    public String getDescription();
    public int size();
    protected void addTool(Tool t);
    public List<Tool> getTools();
    public String trigger(String toolName, Map<String,Object> args);
}
```

```java
/** [改] 纯工厂：默认工具集从配置文件读；无 static 单例。 */
public final class Toolkits {
    public static List<Toolkit> defaults(Role role, ToolkitConfig config,
                                         MailService mail, MCPManager mcp, SkillManager skill);
}
```

> `snakeCase` 移到 `utils.Text.snakeCase`。

### 3.6 `computers`

```java
public abstract class Computer extends UUIDObject implements Data {
    private static final String COMPUTERS_ROOT = "./data/computers";
    private static final String DRIVE_ROOT     = "./data/drive";
    private static final String DEFAULT_IMAGE  = "agentsoftware-base:latest";
    private static final String CONTAINERFILE  = "Containerfile";

    protected boolean ison;
    private final Role role;
    private MCPServer mcpServer;

    protected Computer(Role role);
    protected Computer(Role role, String uuid);

    public Role getRole();
    public boolean isOn();

    public abstract void powerOn();
    public abstract void powerOff();
    public void reboot();
    public abstract void destroy();                  // 销毁是 Computer 自己的事

    public abstract String runCommand(String command, int timeout, int maxChars);
    public abstract String readFile(String path);
    public abstract void writeFile(String path, String content);
    public abstract String listDir(String path);
    public abstract void deleteFile(String path);

    public void installMcpTool(String group, String tool);
    public void uninstallMcpTool(String toolName);
    public void startMcpServers();
    public void stopMcpServers();
    public List<Tool> getMcpTools();
    public String runMcpTool(String name, Map<String,Object> args);

    @Override public Map<String,String> getData();
    @Override public void loadData(Map<String,String> data);
}

public class MCPServer {
    public MCPServer(String packageName, List<String> args);
    public MCPServer(String packageName, List<String> args, String command, List<String> commandArgs);
    public synchronized void connect() throws IOException;
    public synchronized void close();
    public boolean isAlive();
    public List<Map<String,Object>> listTools();
    public String callTool(String name, Map<String,Object> arguments);
}

public class ComputerManager extends UUIDObjectManager<Computer> {
    public ComputerManager();
    public Computer create(String kind, Role role);  // 内部注册
    public void remove(String roleId);               // 内部先 computer.destroy()
    public Computer findByRoleId(String roleId);
    public String nameOf(String roleId, String def);
    public List<Map<String,String>> listLanDevices();
    public boolean hasNetwork();
    public void createNetwork();
    public boolean hasBaseImage();
    public void buildBaseImage();
}

public class PodmanComputer extends Computer { }
public class LocalComputer  extends Computer { }
```

### 3.7 `io`

```java
public abstract class Input {
    public abstract String read(String target);      // null = 无输入/通道关闭
}
public class StdInput extends Input { }              // 🔒 持有单个 BufferedReader 字段
public class WebInput extends Input { }              // 🔒 内部按 target 分组的阻塞队列，由 ChatWebServer offer
```

> 呈现/推送归 Web 实时更新与 `TalkToClient` 的打印；`Input` 只负责读。

### 3.8 `client`（需求 B）

```java
public final class Client extends UUIDObject implements Data {
    public final String clientId;                    // 例如 "CLIENT"
    public final String name;                        // 甲方
    public final String email;                       // 甲方邮箱（进地址簿）
    @Override public Map<String,String> getData();
    @Override public void loadData(Map<String,String> data);
}

/** 客户口头通道：同一时间只允许一个角色（D16）。客户是真人的 UI 动作。 */
public final class ClientChannel {
    public ClientChannel(Client client, ChatStore store);
    public Client getClient();
    public boolean isFree();
    public String getCurrentRoleId();                // 当前对话对象，空闲时 null

    public String talk(String roleId, String message, boolean wait); // 客户端发起（UI）
    public String receiveFrom(String roleId, String message);        // 角色发起（独占）；忙则拒绝
    public void reply(String text);                                  // 客户端回复
    public void release();                                           // 结束当前对话
}
```

> 客户只能寻址大组成员，且**豁免部门限制**。角色侧保留 `talk_to_client` 工具。

### 3.9 邮件 / Web

```java
public abstract class MailService {
    public static MailService create(MailConfig config, Path dataDir);
    public void setDeliveryListener(MailDeliveryListener l);         // NEW_MAIL 事件接线
    public abstract String getAddress(String roleId);                // 员工与客户都在地址簿
    public abstract String getClientAddress();
    public abstract String send(String from, List<String> to, List<String> cc, String subject, String body);
    public abstract List<MailMessage> inbox(String address, Integer limit);
    public abstract int unreadCount(String address);
    public abstract MailMessage read(String address, String messageId);
}
public class VirtualMailService extends MailService { }              // [本轮实现]
public class SMTPMailService    extends MailService { }              // [后续]

public class ChatStore {
    public ChatMessage record(String kind, String group, String fromRoleId, String fromName,
                              String targetRoleId, String targetName, String text, String urgency);
    public long lastSeq();
    public List<Map<String,Object>> messagesSince(long sinceSeq);
    public ChatMessage postClientReply(String text);
}

public class ChatWebServer {                          // 保持简洁
    public ChatWebServer(AgentSystem system, String host, int port) throws IOException;
    public void start();
    public void stop();
    public int port();
    public String host();
}
```

### 3.10 `store`（配置读取）

```java
/** [改] 多文件覆盖读取：后读覆盖先读，缺键保留（保留最近接收的键值）。 */
public class JsonStore {
    public JsonStore(List<Path> filesInPriorityOrder);
    public Map<String,Object> get(String key);
    public Map<String,Map<String,Object>> section(String key);
    public void load();
}

/** [改] 角色模板：顶层键 role_templates；默认大组来自管理组。 */
public class RoleTemplateStore {
    public RoleTemplateStore(JsonStore store);
    public List<Employee> employees();
    public Employee find(String roleId);
    public List<Employee> defaultCohort();           // 管理组（CFO 已删）
}

/** [新] 默认工具集配置。 */
public final class ToolkitConfig {
    public ToolkitConfig(JsonStore store);
    public List<String> defaultsFor(String roleId, String group);
}
```

> 角色模板文件形状改为：`{"role_templates": {"CEO": {...}, "COO": {...}}}`。
> 本轮**暂缓**：`NoteStore` / `TodoStore` / `StateStore`（后续数据库）；`note/todo/memory` 工具包一并暂缓。

### 3.11 `AgentSystem`（极简）

```java
public class AgentSystem {
    public AgentSystem();
    public AgentSystem(Path dataDir, Input input);

    // get 前缀（或改为 public final 成员变量）
    public TimeBus getTimeBus();
    public EventBus getEventBus();
    public RolePool getRolePool();
    public ComputerManager getComputerManager();
    public CompanyRoster getRoster();
    public Staffing getStaffing();
    public MailService getMailService();
    public ClientChannel getClientChannel();
    public ChatStore getChatStore();
    public MCPManager getMcpManager();
    public SkillManager getSkillManager();
    public Input getInput();
    public ConfigStore getConfigStore();

    // 外部只关心这四个
    public void start();
    public void stop();
    public void pause();
    public void resume();

    // 🔒 allRolesIdle / rolloverReady / forceEndWaits / onShiftStart / onShiftEnd —— 内部闸门
}
```

> **跨天闸门（private，D4/A3）**：`rolloverReady() = pool.all().stream().allMatch(r -> r.getState()==IDLE) && timeBus.now() > timeBus.getShiftEndTick()`。
> 到点后：`forceEndWaits()` 逐个 `role.onShiftEnd()`（时间线程直调）→ 等全员 IDLE → `timeBus.advanceTo(次日 shiftStart)` → `role.onShiftStart()` 全员 → `eventBus.releaseHeld()`。

### 3.12 业务 toolkit

**保留**：`client`（角色→客户 `talk_to_client`）、`email`、`mcp/McpManager`、`pc/Pc`、`skill/Skill`、`talk/Talk`、`time/Time`。
**新增**：`staffing/Staffing`（COO 专用：`draft_in/draft_out/list_employees/list_active`）、
`task/Task`（原 `taskview/TaskView`，见 §9：`my_tasks` + `create_task/list_tasks/update_task/delete_task` 增删改查）、
`note/Note`（`write_note/read_note/edit_note/delete_note/list_notes`，见 §9）。
**暂缓/删除**：`hr/Hr` 曾被删又回归（见 §9）、`memory/Memory`、`todo/Todo`、`hermes/Hermes`。

---

## 4. 关键机制（细节见 deep-dive）

1. **talkTo 等待**：内部 handoff + 环检测 + 超时 + 下班中断；`deliverReply` 为 package-private。
2. **上下班**：`onShiftStart/onShiftEnd` 由时间线程直调（WAIT 中的 worker 收不到队列事件）。
3. **下班不派活**：`EventBus` 非工作时段 hold 普通事件，次日释放。
4. **跨天**：全员 IDLE ∧ 超过下班时间 → 强制结束等待 → 跳到次日 shiftStart。
5. **事件**：不过滤；广播只到大组；下班后不放行。
6. **工具循环**：在 Role，`Response` → `invokeTool` → `appendToolResult` → 再 request。
7. **持久化**：`Data.getData()/loadData()` + `DataRegistry` 多态恢复。
8. **线程契约**：状态不符直接抛；不懒创建；`UUIDObjectManager`/队列用并发安全实现。

---

## 5. 公开成员变更汇总（P1–P11 现状）

| # | 变更 | 状态 |
|---|---|---|
| P1 | `LLM.request()` 返回 `Response`；`Response` 独立文件；取消 ToolHandler | ✅ 已定 |
| P2 | 新增 `MembershipState` | ✅ 已纳入 |
| P3 | 新增 `Data`（`getData/loadData`） | ✅ 已纳入 |
| P4 | 新增 `DataRegistry` | ✅ 已纳入 |
| P5 | `RoleTemplateStore` 读配置；`DEFAULT_ROLES` 不再是静态常量 | ✅ 已纳入 |
| P6 | `Role.getComputer()` 不懒创建 + `hasComputer()` | ✅ 已纳入 |
| P7 | `RoleState` 去掉 `PAUSED` | ✅ 已纳入 |
| P8 | `talk`/`get_role`/`list_roles` schema 按 F2=b | ✅ 已纳入 |
| P9 | `UUIDObjectManager.values` 改并发容器 | ✅ 已纳入 |
| P10 | 新增 COO 调度工具集 | ✅ 已纳入 |
| P11 | `Event.from/target` → `fromRoleId/targetRoleId` | ✅ 已纳入 |
| 新增 | `Employee`/`CompanyRoster`/`Staffing`/`Client`/`ClientChannel`/`RoleTemplateStore`/`ToolkitConfig`/`ToolResult`/`OpenAITool`/`Data`/`DataRegistry`/`MembershipState` | ✅ 已纳入 |

---

## 6. 删除 / 暂缓

**删**：`RoleFactory`、HR 工具、`Urgency`、`Types.Event`、`Types.AgentState`、`TimeEventBus`、`EventDispatcher`、`ToolRegistry`、`RetryArbiter`、`provider/*`、`hermes`。
**暂缓**：`NoteStore`/`TodoStore`/`StateStore`、`note/memory/todo` 工具包、`SMTPMailService`。
**保留**：`Tool`/`Toolkit`、`Computer`/`PodmanComputer`/`LocalComputer`、`MCPServer`、`ComputerManager`、`MailService`、`ChatStore`/`ChatWebServer`、`io.*`、`utils.Json`。

---

## 7. 实施批次

**A. 类型打通**：`UUIDObject`/`UUIDObjectManager`、`Data`/`DataRegistry`、`Event`/`Task`、`Message`+3、`Context`、`Response`、`RoleState`/`MembershipState`、`Employee`/`CompanyRoster`、`Role`、`RolePool`、`Staffing`、`RoleTemplateStore`、`Toolkit`、`Json`、`AgentSystem` 骨架。
**B. 事件闭环**：`TimeBus`、`EventBus`、Role worker/上下班/跨天、`LLM`+`OpenAICompatLLM`、工具循环。
**C. 工具与电脑**：`Tool`/`OpenAITool`/`ToolResult`/`Toolkits` 工厂 + 保留的 toolkit、`Computer`/`MCPServer`/`ComputerManager`。
**D. 客户与外围**：`Client`/`ClientChannel`、`VirtualMailService`、`ChatStore`/`ChatWebServer`/`WebInput`、`Main`。
**E. 收尾**：测试、README/docs、模板 JSON 改键。

---

## 8. 实现状态（本轮落地记录）

### 8.1 已实现

- **utils**：`UUIDObject`（含 equals/hashCode/toString）、`UUIDObjectManager`（泛型返回 + `CopyOnWriteArrayList`）`、Data`、`DataRegistry`、`Json`、`Text`
- **event**：`EventType`、`Priority`、`Event`、`Task extends Event`、`TimeBus`、`EventBus`
- **role**：`RoleState`、`MembershipState`、`Employee`、`CompanyRoster`、`Role`、`RolePool`、`Staffing`
- **llm**：`Message` + `UserMessage`/`AssistantMessage`/`ToolMessage`（独立文件）、`Context`、`SemanticMemory`、`Response`（独立文件）、`LLM`、`OpenAICompatLLM`、`Embedding`、`OpenAICompatEmbedding`
- **tools**：`Tool`、`OpenAITool`、`ToolResult`、`Toolkit`、`Toolkits` 工厂 + 12 个工具包（time/task/note/memory/pc/mcp/skill/email/client/talk/staffing/hr）
- **computers**：`Computer`、`MCPServer`（stdio JSON-RPC）、`PodmanComputer`、`LocalComputer`、`ComputerManager`
- **io/client/mail/web**：`Input`/`StdInput`/`WebInput`、`Client`/`ClientChannel`、`MailService`(abstract)/`VirtualMailService`、`ChatStore`/`ChatWebServer`
- **store**：`JsonStore`、`RoleTemplateStore`、`ToolkitConfig`；`ConfigStore` 适配新 `Json`
- **组合根**：`AgentSystem`、`Main`、`Types`（精简为失败文本判定）

### 8.2 公共成员偏差（v3 冻结 API 之外，落地时新增）

| 成员 | 原因 |
|---|---|
| `LLM.setTools(List<Tool>)`（+ `protected toolList()`） | `request()` 无参数，function calling 必须声明 tools |
| `Role.waitForReply(String, long)` | `talkTo` 无 wait 参数，`talk` 工具的 `wait=true` 需要它 |
| `AgentSystem.getToolkitConfig()` | D22/A9 要求默认工具从 JSON 读，Role.setup 只能经 AgentSystem 取 |
| `WebInput.submit(String, String)` | 冻结 API 中无人能把浏览器输入送入输入通道 |
| `ClientChannel.setTalkSink(BiConsumer<String,String>)` | 客户 → 角色的口信要变成给目标角色的 `TALK` 事件；`ClientChannel` 拿不到 `EventBus`，用一个投递口接线（`AgentSystem` 注入） |
| `ClientChannel.isAwaiting()` | `/api/state` 要区分"有角色正在等客户回复"和"客户自己开着会话"，UI 据此提示 |
| `Employee.inGroup/templateString/templateList`、`CompanyRoster.add`、`JsonStore.of`、`VirtualMailService.stats` | 数据访问/构建辅助 |
| `AgentSystem.getDataDir()` | 角色的笔记要落在本实例的 dataDir 下（`<dataDir>/notes/<role_id>`），Role 需要拿到它；原来只有包内可见的 `dataDir()` |
| `Role.noteStore()` | 笔记库按角色持有，note 工具包通过它取（懒创建，测试可注入临时目录） |
| `Role.pendingEvents()` / `Role.taskHistory(int)` | `my_tasks` 要列出整个队列 + 最近任务历史（含状态与 token）；原来只能 `peekEvent()` 看队首，任务"静默失败"时无人可见 |
| `EventBus.schedule/cancel/scheduled` 之上的 task 工具 | 排期任务增删改查：`update_task` 用 `cancel(id)` + `schedule(e)` 重新挂键（`targetTime` 是 TreeMap 的 key，改了必须摘下再挂回），因此 `EventBus` 本身不需要新 API |
| `Toolkits` 配置名 `task`（兼容旧名 `task_view`） | `taskview/TaskView` 按需求更名为 `task/Task`；`data/toolkits.default.json` 同步改键，旧配置仍能加载 |
| `Message.embedding`（`double[]`，随 `getData/loadData` 持久化） | 语义记忆要把向量挂在消息上；空/缺省 = 没算或算不出来 |
| `Context.add(Message)` 覆写 + `Context.memory()/setMemory(...)` | `LLM.append*` 全部经 `Context.add` 落库，这是"角色每次添加内容"的唯一写入口，语义向量的计算与阈值淘汰挂在这里；`loadData` 期间置 `restoring` 跳过（向量已经一起存了） |
| `SemanticMemory`、`Embedding`/`OpenAICompatEmbedding`、`memory` 工具包（`search_memory`） | 需求新增：embedding 请求类 + 语义记忆 + 阈值淘汰 + 记忆检索 |
| `ChatWebServer(AgentSystem)` 便捷构造器 + `resolveHost()`/`resolvePort()`（static） | 需求新增：Web UI 的 host 可配置、端口固定（原来 `Main` 写死 `127.0.0.1:0`）；单测仍用原来的三参构造器 |

已批准的调整：`TimeBus.setNextStopProvider`（B3）、持久化字段去 `final`（B1）。

### 8.3 删除

- 测试：旧架构的 31 个测试文件（引用已删除的 AgentRole/TimeEventBus/Conversation/StateStore/RetryArbiter/Provider 等），替换为 11 个新测试类 / 37 用例。
- 类：`demo/*`、`tools/toolkits/hr|hermes|memory|note|todo/*`、`tools/toolkits/client/ClientCommunicationLock`（B8 已批准 + 被 `ClientChannel` 取代）。

### 8.4 资源

- `role_templates.json`：包到 `{"role_templates": {...}}`，删除 CFO（55 → 54）。
- 新增 `toolkits.default.json`。

### 8.5 仍暂缓

`TodoStore`/`StateStore`、`SMTPMailService`、`SSHComputer`、provider 目录（已删）、每日总结（改为 `Context.forgetAll()`）。
（`NoteStore` 与 note 工具包已按需求补回，见 §9；`todo` 仍未做 —— 排期类需求由 `task` 工具包承担。）

---

## 9. 运行行为补充（后续迭代）

- **无任务自动暂停 + 认班次边界**：`AgentSystem` 只有在"确实还有后续工作"时才允许时钟快进；判据
  `hasFutureWork()` = 有排期事件 ∨ 有下班暂存事件 ∨ 有角色队列待处理项 ∨ **当前不在上班时段**
  （前三者缺一不可，否则"事件已投递但 worker 还没取走"会被误判成没活）。
  全员 `IDLE` 且无后续工作时，`onTick` 直接调用 `pause()`（冻结时钟），外部 `resume()` 可恢复。
  ⚠️ 最后那一条是必须的：`TimeBus.nextStop()` 本来就会把时钟跳到下一个班次边界并在那里发
  `SHIFT_START`，但"下一个边界"不算 future work 时，日终（全员 IDLE、没有排期）会被判成
  **永远没活了** → 时钟停在当天，`resume()` 只前进 1 秒就再次暂停，永远走不到第二天
  （实测 2026-09-23 18:09 就这么永久停住，`imitoy` 的日志里能看到连点 4 次 resume 都被弹回）。
  反过来，**上班时段**内空闲又没有排期时不认边界：那种情况说明公司在等外部输入（客户回信/人的操作），
  必须继续暂停，否则时钟会一路快进到 18:00，把当天剩下的半天直接跳过去（10:32 等客户确认那次就是这种合法暂停）。
- **工具循环上下文**：每轮带 `tool_calls` 的 assistant 消息必须先写回 `Context`，再回喂 tool 结果；
  否则下一轮只有 `tool` 结果、没有对应的 `function_call`，部分网关（实测 Hanseq）会因
  `function_call_output requires item_reference ids matching each call_id` 返回 400。
  另外对返回 `call_id` 而非 `id` 的网关做了兜底。
- **配置文件**：顺序解析 `$AGENTSOFTWARE_CONFIG_DIR/config.json` → `$XDG_CONFIG_HOME/AgentSoftware/config.json`
  （默认 `~/.config/AgentSoftware/config.json`）→ `<dataDir>/config.json`；
  密钥优先级 `环境变量 > 配置文件 > 默认值`；`base_url` 只给域名时自动补 `/v1`。
- **电脑默认 podman**：未指定电脑时创建 `agentsoftware-<role_id>` 容器（首次自动用根目录 `Containerfile` 构建基础镜像）；无 podman 环境用 `AGENTSOFTWARE_COMPUTER_KIND=local`。
- **下班打断客户等待**：`ClientChannel.cancelWait(String)` 由时间线程在 `Role.onShiftEnd()` 调用，避免甲方不回消息把跨天卡到超时。

- **Web 接口契约**：静态前端（`src/main/resources/web/app.js`）固定调用
  `GET /api/state`、`GET /api/messages?since=N`、`POST /api/reply {text}`、`POST /api/pause|/api/resume`，
  且要求 `ok/lastSeq` 与 camelCase 字段（`fromRoleId/fromName/...`）；
  服务端必须照此实现，否则页面会一直停在 "Connecting…" 且不渲染消息。
  角色的 `recordReasoning/recordNote/recordToolCall/recordAnswer` 与 `talkTo/talkToClient`
  都会写入 `ChatStore`，供 "All Activity" 实时展示（reason/note/tool/answer/talk/client）。

- **模拟时间速率**：唯一开关是 `TimeBus.setTimeScale(double)`（模拟秒/真实秒，默认 1.0 = 实时），
  环境变量 `AGENTSOFTWARE_TIME_SCALE` / 系统属性 `-Dagentsoftware.timeScale` 由 `AgentSystem` 注入；
  旧入口 `setSecondsPerTick(x)` 变成别名（等价 `setTimeScale(1/x)`），避免两个速率源互相打架。
  空闲快进是瞬时跳转，不受倍率影响。

- **邮件通知去重与跳过**：同一 `(messageId, 收件人)` 只发一条 `NEW_MAIL`；`to`/`cc` 指向同一邮箱只投递一次；
  通知里带 `message_id` 并提示"已读则无需动作"；`Role` 在处理 `NEW_MAIL` 前先查该邮件是否已读，
  已读就直接跳过（不调用 LLM）。这解决了通知积压时"重复通知已处理邮件"的 token 浪费。
- **工具参数容错**：模型给出的 `arguments` 不是合法 JSON 时，按空参数处理并 warn，不再让整个任务抛
  `UncheckedIOException`。
- **read_mail 支持 `unread_only`**：收到 NEW_MAIL 通知后只列未读邮件，避免把整个收件箱（含大量已读回执）重扫一遍；
  默认 `false` 保持原行为，`limit` 依旧作用于过滤后的最近 N 封。
- **HR 招聘工具回归**：`post_job_posting`（按一段招聘要求生成完整员工档案并登记进 `CompanyRoster`；
  `draft_in=true` 时立即由 `Staffing` 入职当前大组）与 `list_candidates`（查名单）。
  生成策略：有 API Key 时用**独立 LLM + 独立 Context** 让模型按固定 JSON 生成（不污染 HR 角色自己的对话上下文），
  无 Key / 解析失败时退回本地确定性生成，保证工具永远产出合法档案。
  重新引入了 `role.RoleFactory`（此前 B8 删除）作为生成器。

- **电脑 = 容器：云盘全公司共享 + 每人在容器内有自己的账号**：
  `data/computers/<role_id>` 挂到容器内 `/home/<username>`（`username` 取模板里的姓名拼音，缺省用 `role_id`）；
  **一份**共享云盘 `data/drive` 挂到所有容器的 `/mnt/drive`。旧实现误按 `data/drive/<role_id>` 每角色挂一份，
  于是 `/mnt/drive/Public` 根本不存在、各人看到的也不是同一个云盘。每次上电幂等地执行：
  建用户（固定 uid = `1100 + 入组顺序`，见 `RolePool.addRole`）、加入 `sudo` 组并写
  `/etc/sudoers.d/<username>`（`ALL=(ALL) NOPASSWD:ALL` 免密 sudo）、建好个人主目录并 chown、
  建 `/mnt/drive/Public`（777）与 `/mnt/drive/<username>`（归属本人；CEO 额外接管 `Public`，对齐 master）。
  `podman exec` 一律带 `--user <username>`（不再以 root 跑角色的命令）。挂载对不上的旧容器由
  `podman inspect` 判定后在上电时自动重建（挂载无法就地修改）。
  `AGENTSOFTWARE_COMPUTER_KIND=local` 时没有容器可挂载，改为把 `/mnt/drive` 路径映射到宿主机的
  `data/drive` 并建出 `Public` + 本人目录；本地模式不动宿主机 sudoers。

- **角色输出全部进日志**：任务输入、每轮 assistant 文本、`reasoning`、`tool`（含 args 与 result）、`answer`
  都按 INFO 打印且**不截断**（`journal` 走 DEBUG，默认看不到）；模型给出非法 JSON 参数时也完整打印原文。
  `run_command` 回喂给模型的结果仍受工具自己的 `maxChars` 预算约束（默认 20000），这是上下文预算而非日志截断。

- **COO 是唯一能把人拉进组的角色（D15），所以模板文案必须按大组模型写**：
  COO 模板的 `system_prompt_extra` 原本抄自 master，而 master 一开局就把整个工程团队放进池子，
  于是那句"Only interact with the CEO, HR, business analyst and CTO; do not directly command frontline
  staff: do not assign specific developers"在那边没有副作用。refactor3 只准入管理组，其余 49 人是
  `OUT_OF_GROUP` 的"假死"状态（无 Role/电脑/工具，收不到邮件也收不到 talk），且**只有 COO 有 `draft_in`**。
  保留那句话会让 COO 把派工推给 CTO（没有该工具）→ 全公司没人进组、没人干活。现在的分工是：
  模板写工作流（拆解 → `draft_in` 拉人 → 逐个派活 → 有缺口才找 HR），运行时提示词再加一段
  `[Staffing]` 说明"只有你有 draft_in"这条系统事实。
  ⚠️ 运行时读的是 `<dataDir>/role_templates.json`，**只有在它缺失时才从 resources 复制**，
  所以改模板要同时改 `src/main/resources/role_templates.json` 和 `data/role_templates.json`。

- **容器内 uid 按员工（名单）稳定**：`RolePool.addRole` 用员工在 `CompanyRoster` 里的固定位置派生
  `uid = 1101 + index`（requirements-2 §A.3），不再是 master 的"按入组顺序 `1100+seq`" ——
  否则 COO 每抽调/移出一次，别人的 uid 就漂一次，容器内的文件归属跟着变。

- **`take_rest` 结束当前任务**：`take_rest` 的语义是"这件事到此为止，我去休息"。工具循环必须就此收尾，
  否则模型会被反复追问同一件事，继续 `take_rest`/`read_mail` 空转到 `MAX_TOOL_ROUNDS`（实测 20 轮、
  18 万 token 仍无产出）。

- **需求 B：甲方可以主动找任意大组成员（口头 / 邮件）**。两条通路都要**真的唤醒角色**：
  - 口头：`ClientChannel.talk(roleId, msg, false)` 记一条客户消息（进活动流）**并投一条 `TALK` 事件**，
    `Role.dispatch` 把它变成 `[talk] …` 任务；接线是 `AgentSystem` 注入的 `ClientChannel.setTalkSink`
    （`ClientChannel` 本身拿不到 `EventBus`）。
  - 邮件：`POST /api/client_mail` 以 `client@` 身份走 `MailService.send`，由既有的 `onMailDelivered`
    投 `NEW_MAIL`，角色被唤醒后自己 `read_mail`。
  - 只允许寻址**当前大组成员**（`RolePool.find` 校验）；没进组的人是"假死"的，直接拒收。
  - 会话（F7 同一时间一个对话）：客户可以**改找别人**（等于结束上一段）；目标角色正阻塞在
    `talk_to_client` 上等回复时，客户再发给该角色会被当作**回复**直接交付，而不是另排一条事件；
    `POST /api/client_end` 显式结束会话（否则通道会一直挂在那个角色身上）。
  - 客户的任何动作都会 `resume()` 时钟 —— 否则仿真停在 auto-pause 上，"客户来了"也没人处理。
  - 角色寄给客户的邮件会记进活动流（客户不是 Role，以前这类信直接丢弃，UI 上看不到回信）。
  - UI：输入框上方新增 `#clientBar`（选人下拉 + 口头/邮件切换 + End chat），由 `/api/talk`、
    `/api/client_mail`、`/api/client_end` 支撑；`/api/state.clientTalk.waiting` 让界面区分
    "有人在等你回复" 和 "你开着会话"。

- **note 工具包（笔记 = 角色的长期记忆）**：`store/NoteStore` 把每条笔记落成
  `<dataDir>/notes/<role_id>/notes/<title>.md`（标题会被清洗成合法文件名：非法字符→`_`、
  去掉首尾点/下划线、去掉多写的 `.md`，所以 `../../etc/passwd` 只会变成一条普通笔记）。
  工具是 `write_note / read_note / edit_note / delete_note / list_notes`（增删改查，同名覆盖）。
  为什么必须有它：`Context.forgetAll()` 每天下班把对话移出 prompt，跨天要记住的事只能落到笔记。
  笔记**不做提醒**——"未来某时刻要做什么"是 `task` 工具包的事，两者职责不重叠。

- **task 工具包（原 `taskview`，排期任务的增删改查）**：包名 `tools/toolkits/task`，
  工具包名 `task`（配置名同步改；旧名 `task_view` 仍被 `Toolkits` 接受），工具：
  - `create_task(content, in_minutes | day+tick, target?, priority?)`：把一条 `Task` 排到未来某刻
    （`EventBus.schedule`）。到点后正常投递到目标角色队列，把对方唤醒。tick 是班次内秒数
    （0 = 08:00，36000 = 18:00）；只给 `tick` 且今天已过就顺延到明天，显式给了 `day` 而时间已过则报错。
  - `list_tasks(scope)`：`mine`（默认，派给我的）/ `created`（我建的）/ `all` —— 只看**还没到点**的排期表。
  - `update_task(task_id, content?/priority?/target?/时间?)`、`delete_task(task_id)`：只对"还没到点"的任务生效，
    权限是创建者 / 被指派者 / 管理组。改时间用 `cancel(id)` + `schedule(e)` 重新挂键。
  - `my_tasks(scope)`：队列里已投递的（`Role.pendingEvents()`）+ 最近完成/失败历史
    （`Role.taskHistory(int)`，含状态与 token）。
  跨组派活沿用 talk 规则（同组可以，跨组只有管理组可以），未进组的"假死"员工不能派活。
  这是给"角色自己安排未来工作"补上的缺环：以前没有任何工具能造出未来事件，于是全员
  `take_rest` 之后 `hasFutureWork()` 必然为假、公司只能靠人手动 resume。

- **语义记忆：Embedding 请求类 + 阈值淘汰 + `search_memory`**（需求新增）。
  - **请求类**：`llm/Embedding`（抽象，和 `LLM` 对称）+ `llm/OpenAICompatEmbedding`
    （`POST {base_url}/embeddings`，body `{"model":…,"input":…}`，取 `data[0].embedding`）。
    配置解析口径与 `OpenAICompatLLM` 一致（环境变量 &gt; 配置文件 &gt; 默认值）：
    - `embedding.model`（或 `OPENAI_EMBEDDING_MODEL`）—— **必填，没配就整个功能空转**；
    - `embedding.api_key` / `embedding.base_url`（或 `OPENAI_EMBEDDING_API_KEY/_BASE_URL`）
      **回落到 `llm.api_key` / `llm.base_url`**，同一个网关跑两种模型时不用重复填；
    - `base_url` 只给域名时自动补 `/v1`。

    ```json
    { "llm": { "base_url": "https://…", "api_key": "sk-…", "model": "deepseek-v4.1-flash" },
      "embedding": { "model": "text-embedding-3-small" } }
    ```
  - **每次添加内容都算向量**：`Context.add` 是唯一写入口（`LLM.appendUserMessage/appendAssistantMessage/
    appendToolResult` 全走它），所以钩子挂在 `Context.add` 上就覆盖了"角色每次添加内容"。
    算出来的向量存在 `Message.embedding`，并随 `getData/loadData` 一起持久化。
  - **阈值淘汰**：remember=true 的消息条数超过 `memory.threshold`
    （或 `AGENTSOFTWARE_MEMORY_THRESHOLD`，默认 **40**）时，把其中与**刚加入的那条**余弦相似度
    最低（距离最远）的一条 `remember` 置 false —— 只是移出 prompt，消息和向量都还在内存里。
    - **成组淘汰**：只丢一条 `assistant(tool_calls)` 或一条 `tool` 结果会让 prompt 出现
      "tool 结果没有对应 tool_call"（或反之），网关会 400。所以淘汰时连带把配对的
      tool_call / tool 结果一起移出（`SemanticMemory.evictionGroup`）。
    - 没有向量（未配置/调用失败/维度不一致/零向量）时不淘汰，行为与加此功能前一致。
  - **熔断与降级**：embedding 调用失败重试 3 次；4xx（模型不存在/未授权）**立即熔断**，连续失败
    3 次也熔断，之后本进程不再尝试。没有这两道闸，模型名写错会让**每条消息**都去撞墙，把任务循环拖死。
    没配 `embedding.model` 时 `Role.setup` 打印一行 `semantic memory off`，不做任何 HTTP。
  - **`search_memory(query, limit?)`**（`memory` 工具包）：把 query 向量化，对**全部**历史消息
    （含 `remember=false` 的）算余弦相似度，返回最近的若干条，并标出每条是 `in-prompt` 还是
    `forgotten`、在原上下文里的序号、时间与相似度。**淘汰 ≠ 遗忘**，这是该工具存在的意义。
  - 提示词只在 `SemanticMemory.enabled()` 时才介绍 `search_memory`，不宣传一个必然报错的工具。
  - 实测（真模型）：把"客户截止日期 2026-09-30"的消息 `forgetAll()` 移出 prompt 后，
    模型自己调用 `search_memory {query=client deadline date}` 找回，并答出正确日期。

- **Web UI 监听地址可配置 + 端口固定**：`Main` 不再写死 `127.0.0.1:0`（局域网根本连不上，
  端口还随机到只能从日志里抠），改为 `new ChatWebServer(system)`，解析顺序
  **系统属性 `agentsoftware.webHost`/`agentsoftware.webPort` &gt; 环境变量
  `AGENTSOFTWARE_WEB_HOST`/`AGENTSOFTWARE_WEB_PORT` &gt; 默认 `0.0.0.0:8787`**
  （README 早就写了这两个环境变量，但代码里一直没实现）。要点：
  - `port=0` 仍表示"随机空闲端口"；固定端口被占用时**不让系统起不来**：
    `ChatWebServer.start()` 打一条 warn 后改用随机端口，`port()` 返回真实端口。
  - 启动时打印 `Web UI: http://localhost:PORT/`，绑 `0.0.0.0` 时再逐个打印非回环 IPv4 的
    `http://<lan-ip>:PORT/ (LAN)` —— 只打 `0.0.0.0` 对使用者没有意义。
  - ⚠️ 这只是"应用愿意被访问"；**宿主机防火墙是另一道**：本机 firewalld 默认 `public` 区
    只放行 `ssh`/`dhcpv6-client`，不放行自定义端口的话局域网仍然连不上
    （`firewall-cmd --add-port=8787/tcp --permanent && firewall-cmd --reload`，需要 root）。
  - ⚠️ 该界面**没有鉴权**：能连上的人可以 pause/resume、以"客户"身份发言/发信、结束会话。

- **工具结果的"额外选项"：把"高于 HIGH"的事件带进工具循环**。队列里存在优先级**严格高于 HIGH**
  （即 `EMERGENCY`）的事件时，`Role.runTask` 会把**最近那一条**工具结果后面附加一段：

  ```
  [urgent event waiting in your queue — priority above HIGH]
  - EMERGENCY / TALK / from COO
    EMERGENCY: the client is calling, drop everything and reply now
  Wrap up the current step, then deal with this.
  ```

  没有这样的事件就什么都不加（"这个参数"不存在）。要点：
  - **只附一次**：同一条事件按 uuid 去重（`announcedUrgentEventId`，每条任务开头重置），
    否则每轮工具调用都会重复塞同一段文字；一条任务里若出现新的紧急事件会再附一次。
  - **不消费事件**：它仍然留在队列里，等当前任务结束后照常被处理 —— 提醒 ≠ 插队执行。
  - 判定只看队首：队列本来就按优先级排序，真有 EMERGENCY 一定在最前面。
  - 附的是"给模型看的文本"，所以工具成功/失败的判定不受影响（`isToolFailure` 在附加之前就已判完）。
  - `SHIFT_START` / `SHIFT_END` 固定 **HIGH**：压得住 NORMAL 的邮件/任务，但**不高于 HIGH**，
    所以班次信号不会插进模型正跑着的工具结果里（`AgentSystem.shiftEvent` 里有注释）。
  - 目前 EMERGENCY 的现实来源只有两个：`talk(urgency=EMERGENCY)` 与 `create_task(priority=EMERGENCY)`
    （客户端口信走的是 HIGH，不会触发）。实测（真模型）：任务跑第一轮时塞入一条 EMERGENCY TALK，
    模型在 `my_tasks` 的工具结果里看到了这段附加文本，并在最终答复里主动提到"COO 的紧急请求"。



