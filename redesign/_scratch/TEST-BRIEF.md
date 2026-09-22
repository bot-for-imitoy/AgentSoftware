# refactor2 测试迁移通用说明（所有测试子任务必读）

工作目录：`/home/openclaw/workspace/AgentSoftware`，分支 `refactor2`。**不要 commit，不要切分支。**

`master` 分支的测试（`src/test/java/**`，31 个文件）是**行为基线**。
主代码已经从骨架重写完成（`src/main/java`，编译 0 错误），现在要把 master 的测试迁移过来、
跑绿。迁移不是照抄：API 变了（`Types.Event` → `AgentEvent`、`AgentRole` → `Agent`+`RoleSpec`、
`AgentSystem` → `Company`、`TimeEventBus` → `ClockDriver`+`ShiftCalendar` …），
断言背后的**行为要求**必须保留。

## 硬性规则

1. **只改分配给你的测试文件**（`src/test/java/**`）+ 分配给你的主代码包（见各任务说明）。
   需要改别人的包时停下来写进报告，不要自己动手。
2. **不许削弱测试**：不能为了让测试通过而删断言、放宽阈值、加 `@Disabled`。
   如果某个 master 行为在新架构里被**有意**取消了，删除对应测试并在报告里说明理由。
3. 中文注释；测试方法名可以用中文（项目里已有先例）。
4. 不引入新的第三方依赖（JUnit 5.10.2 已在 pom 里）。
5. 日志用 slf4j；不要在测试里 `System.exit`。
6. 测试必须是**确定性**的：不要依赖真实时间睡眠太久；需要时钟时用
   `ClockDriver.tickOnce()` 单步驱动（`Company.driver()` 已暴露）；
   需要 LLM 时写一个 `LlmClient` 的假实现（参考
   `src/test/java/com/agent/software/company/CompanyDayCycleTest.java`）。

## 运行测试（必须串行！其他人可能同时在跑 Maven）

`~/.m2` 是只读的，**必须**带 `-Dmaven.repo.local=.m2-local`。用文件锁串行化：

```bash
cd /home/openclaw/workspace/AgentSoftware
LOCK=target/.mvn-test-lock
for i in $(seq 1 120); do mkdir "$LOCK" 2>/dev/null && break; sleep 5; done
timeout 900 mvn -o -Dmaven.repo.local=.m2-local \
  -Dtest='你的测试类1,你的测试类2' -DfailIfNoTests=false test 2>&1 | tail -60
rmdir "$LOCK"
```

- 如果报错来自**别人的测试文件**（并行编辑中），等 30 秒重试；连续两次仍失败就在报告里写明。
- 你的测试必须**真的跑绿**（`Tests run: N, Failures: 0, Errors: 0`）。

## 已实现、可用的主代码速查

- `kernel`：`Ids`(RoleId/TaskId/EventId/ScheduleId/MailId/TodoId/SkillId/NoteId)、`Text`、`Payload`
  （of/empty/ofMap/with/string/stringOr/intValue/boolOr/has/asMap/title/text/subject）、`JsonSchema`、
  `Tick`、`DayTick(day, tickOfDay)`、`DomainError(code,msg)`。
- `sim.event`：`AgentEvent.broadcast/toRole/scheduled`、`targeted()/broadcast()/target()`、`EventKind`、`Priority`(weight/ofWeight)。
- `sim.clock`：`ShiftCalendar.of(secondsPerTick,8,18)`（ticksPerDay/shiftEndTick/locate/at/withinShift/clockTime/
  nextShiftStart/ticksUntilShiftEnd/describe）、`SimClock(calendar, baseDate)`、`ScheduleTable(calendar)`
  （schedule/cancel/reschedule/list/due/nextFireTick/activateDay）、`DefaultClockPolicy(fastForwardIdleMillis)`、
  `ClockDriver(clock,schedule,policy,sink,sensors,observer,options)` + `tickOnce()`。
- `agent`：`Agent`、`Team`、`Staffing`、`AgentState`、`AgentStateMachine`、`AgentMailbox`、
  `WaitCoordinator`、`LifecycleGate`、`RoleSpec.builder()`、`RoleSnapshot`、`AgentSnapshot`。
- `agent.task`：`Task`（id/urgency/description/source/context/createdAt/assignee/status/result/tokens/toRecord/fromRecord/onComplete/notifyComplete）、
  `TaskRunner`、`ToolLoop.run(...)→Outcome(answer,tokens,failed)`、`ToolLoopPolicy`。
- `agent.dialog`：`ConversationMemory(roleId, policy)`（prepare/commit/closeDay/isEmpty/historySize/snapshot/restore，
  `State(day,closedDay,messages)`）、`ConversationPolicy.defaults()`、`SystemPrompt(clock, summaries)`。
- `agent.dispatch`：`DeliveryPolicy`（`DeliveryContext(event,spec,state,scheduledReminder)`、
  `DeliveryDecision.deliver/hold/drop`、`DeliveryVerdict`）、`DefaultDeliveryPolicy(salience)`、
  `KeywordSaliencePolicy(base,step,skill,urgency)`、`TaskFactory(schedule)`、`EventRouter(team,policy,tasks,transcript)`
  （`publish`、`publishAndReport` → `Map<RoleId,Routed(verdict,reason,taskId)>`）。
- `tool.*`：各 `*Toolkit` + `JsonNoteBook`/`JsonTodoList`/`JsonSkillLibrary`/`FileMailbox`/`ShellRegistry`/
  `StdioMcpBridge`/`TalkService`/`HiringService`；`tool.spi.{Tool,Toolkit,Toolbox,ToolSpec,ToolResult}`。
- `company`：`Company`（start/stop/publish/save/restore/paused/pauseReason/clock/team/schedule/staffing/router/driver/
  status/roster/pause/resume）、`ShiftDirector`、`CompanyView`、`CompanyStatus`、`CompanySnapshot`、`JsonSnapshotStore`。
- `bootstrap`：`CompanyBuilder(config, paths, json)`（`withLlm`/`withClientChannel`/`withTranscript`/`build`）、
  `Main.buildCompany(config)`（包级可见）、`Main.defaultTeam(config)`、`ToolkitCatalog`、`RoleTemplates`（包级）。
- `transcript`：`Transcript`、`ChatFeed`（`implements Transcript.Feed`；`watermark/since`、
  `clientAttached/touchAttach/awaitClientReply/submitClientReply/clientDialogue`、`RoleResolver`）。
- `web`：`ChatWebServer(view, feed, host, port)`（start/stop/port/host）。

## 报告格式

```
完成：<一句话>
新增/迁移的测试：<文件> → <覆盖的 master 测试 + 断言了什么>
为通过测试而修的主代码：<文件> → <改了什么、为什么>（没有就写"无"）
删除/放弃的 master 测试：<文件> → <理由>
测试结果：<命令> → Tests run: N, Failures: 0, Errors: 0
遗留：<问题/建议>
```
