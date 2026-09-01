# 评估报告: AgentSystem 单实例限制与多实例化改造

> 结论先行: 当前代码库**只能在单个 JVM 进程内安全地运行一个 `AgentSystem`**。
> 根因不是 `AgentSystem` 本身, 而是它依赖的一批**进程级全局单例 / 静态可变状态 /
> 全局文件路径**。若创建第二个 `AgentSystem`, 两个系统会共享这些全局资源, 导致
> 角色与电脑互相覆盖、时钟事件串扰、邮件/笔记/日志等数据文件互相覆盖等
> **不可预知的异常**。本文档评估问题, 并给出按本报告实施的改造方案。

---

## 1. 问题清单: 为什么"只能允许一个 AgentSystem"

### P1. 进程级全局单例 (服务对象跨系统共享)

| 位置 | 全局对象 | 共享内容 | 两个系统同时存在时的后果 |
|---|---|---|---|
| `computers/ComputerManager.java:44` | `INSTANCE` (角色电脑注册表) | `role_id → Computer` 映射、`role_id → 人名` 映射 | ① 同一 `role_id` 在第二个系统注册时**覆盖**第一个系统的电脑对象; ② 一个系统离职/销毁电脑会把另一个系统的容器删掉; ③ `lan_devices` 会把两个系统的角色混在一起列出; ④ 存档恢复时绑定到错误的电脑 |
| `tools/Toolkits.java:42,45` | `MCP_MANAGER` / `SKILL_MANAGER` | MCP 工具安装记录 (`role_id → Set<工具名>`)、技能库安装记录 | 同 `role_id` 的两个系统共享"已安装工具"记账, `mcp_my_tools` / `skill_my_skills` 互相污染; 技能库默认目录 `data/skills` 也全局共享 |
| `services/MailService.java:429` | `mailService` 懒加载单例 | 全部邮箱 (mailboxes 内存表 + `data/mail/mailboxes.json`) | 两个系统的邮件完全互通 (系统 A 发信, 系统 B 的收件箱也能看到), 存档互相覆盖 |
| `tools/toolkits/client/ClientCommunicationLock.java:15` | `INSTANCE` (与甲方对话互斥锁) | 锁持有者 | 系统 A 成员占用锁后, 系统 B 成员无法与甲方对话 (跨系统互相阻塞) |
| `event/TimeEventBus.java:580` | `getDefaultBus()` 进程级默认时钟 | 共享 Tick/天状态与事件调度表 | `AgentRole.timeManager()` 在未显式绑定时**静默回退到进程级默认时钟**; 两个系统的未绑定角色共享同一个时钟, Tick/天互相跳变, 定时事件串扰 |

### P2. 静态可变注册表 / 计数器

| 位置 | 静态状态 | 后果 |
|---|---|---|
| `role/RoleLoader.java:84` | `TEMPLATES` 全局模板注册表 (可变) | `RoleFactory` 招聘 (HR 工具) 会把新角色模板注册进全局表, 系统 A 招聘的角色出现在系统 B 的模板池里 |
| `role/RoleLoader.java:305-306` | `usedNames` / `namePoolInitialized` | 全局名字分配器, 跨系统共享 (影响较小, 但仍是进程级共享状态) |
| `role/AgentRole.java:50` | `JOURNAL_DIR` (static, 可被测试改写) | 同 `role_id` 的两个系统把活动日志追加到**同一个文件** |

### P3. 全局文件路径 (单数据目录)

| 位置 | 路径 | 问题 |
|---|---|---|
| `role/AgentRole.java:50` | `data/journals/` | 同 `role_id` 两个系统写同一日志文件 |
| `store/NoteStore.java:41` | `PathManager` 用户目录 `~/.local/share/AgentCompany/notes` | ① 与项目其余 `./data/*` 布局不一致 (.gitignore 里明确写着 `data/notes/`); ② 两个系统同 `role_id` 共享笔记目录 |
| `store/TodoStore.java:35` | `./data/todos/<role_id>.json` | 两个系统同 `role_id` 共享同一份待办 |
| `services/MailService.java:39` | `data/mail/mailboxes.json` | 全局共享一份邮箱存档 |
| `store/StateStore.java:41` | `./data/state.json` | 两个系统保存/恢复互相覆盖 (后保存的覆盖先保存的) |
| `computers/Computer.java:34-35` | `./data/computers`、`./data/drive` | 本地模拟电脑目录/云盘全局共享 |
| `tools/toolkits/skill/SkillManager.java:90` | `data/skills` | 技能库全局共享 |

### P4. 角色/工具直接拉取全局单例

`AgentRole` 与各工具类在**没有依赖注入**的情况下直接调用全局单例:

- `AgentRole.computer()` → `ComputerManager.getInstance().create(...)`
- `AgentRole.mailAddress()` → `MailService.getMailService().emailFor(this)`
- `AgentRole.timeManager()` → 未绑定则回退 `TimeEventBus.getDefaultBus()`
- `RolePool.setupRole()` → `Toolkits.getMcpManager().installGroupDefaults(...)`
- `RolePool.removeRole()` → `ComputerManager.getInstance().destroy(roleId)`
- `toolkits/pc/LanDevices` → `ComputerManager.getInstance().listLanDevices()`
- `toolkits/email/Email` → 默认回退 `MailService.getMailService()`
- `toolkits/client/TalkToClient` → 默认回退 `ClientCommunicationLock.getInstance()`
- `store/StateStore` → `ComputerManager.getInstance().create/nameOf`

### P5. 顺带发现的缺陷 (与多实例无关, 一并修复)

- `NoteStore` 默认目录走 `PathManager` (用户主目录 `~/.local/share/AgentCompany/notes`),
  与项目其余 `./data/*` 布局以及 `.gitignore` 中 `data/notes/` 的约定矛盾; 在只读主目录
  环境下会导致测试直接失败 (本仓库沙箱实测 6 个用例因此报错)。
- 测试与 README 使用的环境变量前缀 `AGENTSCHEDULER_DATA_DIR` 与 `PathManager`
  实际推导的前缀 (`appName="AgentCompany"` → `AGENTCOMPANY_DATA_DIR`) 不一致,
  导致测试里设置的数据目录属性从未生效。

---

## 2. 两个 AgentSystem 共存时的具体异常示例

1. **角色注册冲突**: `RolePool.addRole` 对重复 `role_id` 抛
   `IllegalArgumentException("Role 'xxx' already exists")`; 即使两个系统各自持有
   独立的 `RolePool`, 由于 `ComputerManager` 是全局单例, 电脑注册表仍会互相覆盖。
2. **时钟串扰**: 系统 A 的角色在 Tick 30 上班, 系统 B 的未绑定角色回退到
   `TimeEventBus.getDefaultBus()`, 两个系统的作息事件在同一根时钟线上互相触发。
3. **数据覆盖**: 系统 A 与系统 B 的 `StateStore` 都写 `./data/state.json`, 后保存的
   覆盖先保存的; 两个系统同 `role_id` 的笔记/待办/邮件/日志写同一批文件。
4. **互斥锁跨系统阻塞**: 系统 A 的 CEO 与甲方对话期间, 系统 B 的所有成员
   `talk_to_client` 全部被拒。
5. **容器删除事故**: 系统 A 离职一名员工会通过全局 `ComputerManager` 把系统 B 同名
   角色的容器 `podman rm -f` 掉。

---

## 3. 改造方案 (按本报告实施)

核心思路: **把"进程级全局单例"改为"每 AgentSystem 一个实例", 由 AgentSystem
直接持有**。不引入额外的打包类: `AgentSystem` 在构造时自建并直接持有自己的一套
协作对象与数据根目录, 通过 `RolePool → AgentRole → 工具类` 显式注入, 一套系统
可独立运行, 多套系统互不干扰。

### 3.1 `AgentSystem` 自包含协作对象与数据目录

`AgentSystem` 新增直接字段 (全部每系统独立实例, 多实例互不干扰):

- `timeManager` (TimeEventBus) — 每系统一个时钟 (原有)
- `configStore` (ConfigStore)
- `computerManager` (ComputerManager) — 每系统一份角色电脑注册表
- `mailService` (MailService) — 每系统一份邮箱 (数据落 `dataDir/mail`)
- `mcpManager` (MCPManager) / `skillManager` (SkillManager) — 每系统一份
- `clientLock` (ClientCommunicationLock) — 每系统一把与甲方对话锁
- `chatStore` (ChatStore) — 每系统一份聊天存储 (Web 界面数据源)
- `dataDir` (Path) — 每系统数据根目录, 默认 `./data`

派生的数据目录 (均以 `dataDir` 为根): `journalDir/notesDir/todosDir/mailDir/
computersDir/driveDir/skillsDir/stateFile`。

多实例用法: 每个 `AgentSystem` 传入不同的 `dataDir`, 文件层面完全隔离:
`new AgentSystem(Paths.get("data/company-a"), null, roleIds, 30.0, true)`。

### 3.2 各模块改动

| 文件 | 改动 |
|---|---|
| `AgentSystem` | 新增 `computerManager/mailService/mcpManager/skillManager/clientLock/chatStore/dataDir` 直接字段并在构造时自建; 新增 `AgentSystem(Path dataDir, ...)` 重载 (原签名保持兼容); `addRoles` 无条件绑定 `bindTimeManager` + `bindSystem(this)`; 新增各数据目录访问方法 |
| `RolePool` | 新增携带 `AgentSystem owner` 的构造重载 (可为 null = 独立角色池); `setupRole` 绑定 `bindSystem`; 默认 MCP 组用 `role.mcpManager()`; `removeRole` 用 `role.computerManager()`; `newLlm` 用 `owner.configStore` |
| `AgentRole` | 新增 `system` 字段与 `bindSystem()/system()`; 新增 `computerManager()/mailService()/clientLock()/mcpManager()/skillManager()/chatStore()` 解析助手 (有所属系统用系统实例, 否则回退全局默认, 保证旧用法兼容); `computer()/mailAddress()/noteStore()/todoStore()/journal()` 全部改走所属系统 |
| `Toolkits.defaultToolkits` | 用 `role.mcpManager()/skillManager()/mailService()` 构造工具类 (系统内角色即每系统实例) |
| `toolkits/client/Client` + `TalkToClient` | 用 `role.system().clientLock` / `.chatStore` (未绑定系统回退全局默认/无聊天存储) |
| `toolkits/talk/TalkTo` | `recordTalk` 用 `role.system().chatStore` |
| `web/ChatWebServer` + `WebDemo` | 改用 `system.chatStore` |
| `store/NoteStore` | 默认基础目录改为 `data/notes` (与 .gitignore 及全项目布局一致; 显式传参行为不变) |
| `store/StateStore` | `collect/restoreComputers` 改用 `system.computerManager` |
| `computers/ComputerManager` | 构造器改为 public (允许每系统 new 实例); `getInstance()` 保留为默认回退 |

### 3.3 保留的进程级共享 (有意为之, 文档化)

- **`RoleLoader.TEMPLATES` / 名字池**: 角色模板是 classpath 上的"公司组织架构定义",
  属于进程级共享目录; HR 招聘注册的新模板进程内可见 (与单系统语义一致)。如需隔离,
  可在 `AgentSystem` 上扩展每系统模板表 (本期不做)。
- **podman 网络 / 基础镜像 / 容器名**: `maf-net` 网络、`maf-base:latest` 镜像、
  `maf-<role_id>` 容器名是宿主机级基础设施, 跨系统共享 (幂等创建)。
  **多实例部署约束**: 同一宿主机上两个 AgentSystem 若使用相同 `role_id` 的
  podman 电脑, 容器名会冲突; 多实例场景应使用 `local` 电脑 (配合每系统
  `base_dir`/`drive_dir`) 或互不重叠的角色集。
- **`ConfigStore` 默认配置路径**: LLM 等进程级配置 (`~/.config/AgentCompany/config.json`)
  保持共享 (环境变量优先, 配置为只读性质); 数据类状态全部按系统隔离。

### 3.4 兼容性

- `AgentSystem` / `RolePool` / `AgentRole` 的既有公开构造器与字段全部保留;
  `Main.java`、演示程序、既有测试无需结构性改动 (仅 Web 相关测试改为直接访问
  `system.chatStore`)。
- 单系统用法行为不变 (数据仍在 `./data/*`), 仅 `NoteStore` 默认目录从用户主目录
  统一到 `data/notes`。
- 未绑定系统的独立角色/池 (演示、单元测试) 继续回退到全局默认, 与改造前一致。

---

## 4. 验证

- `mvn test` 全绿 (含多实例隔离测试 `AgentSystemIsolationTest` 与 Web 测试)。
- 新增测试覆盖: 两套系统角色/时钟/电脑注册表/邮箱/互斥锁/聊天存储/数据目录互不
  干扰; 同 `role_id` 在两套系统中各自独立; 未绑定系统的独立角色回退全局默认。

## 5. 后续建议 (本期范围外)

- 若需要"同进程多公司"级别的完全隔离, 可将 `RoleLoader.TEMPLATES`、`ConfigStore`
  也改为 `AgentSystem` 自持。
- podman 容器名支持 per-system 前缀 (如 `maf-<system>-<role_id>`), 消除同宿主机
  多实例的容器名冲突。
- 为 `AgentSystem` 增加 `close()`/资源回收语义, 使多个系统的生命周期可独立管理。
