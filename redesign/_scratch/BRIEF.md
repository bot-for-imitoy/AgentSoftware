# refactor2 重写任务通用说明（所有子任务必读）

工作目录：`/home/openclaw/workspace/AgentSoftware`，git 分支 `refactor2`（**不要 commit，不要切分支，不要动 git**）。

这是在把一个 Java 25 / Maven 项目从 `master` 分支的实现，**重写**进一套已经设计好的新骨架架构。
骨架文件已经存在，每个方法体都是 `throw new UnsupportedOperationException("skeleton");`。

## 硬性规则

1. **只修改分配给你的文件**。允许在你自己负责的包内新增文件；**不要**修改其他包的任何文件。
   如果你认为必须改别的包，**停下来**，在最终报告里写清楚原因和建议，不要自己动手。
2. 骨架里已有的**类名、public 方法签名、record 组件、接口方法不要改**。
   确实有设计缺陷必须调整的，可以做最小改动，但必须在报告里单独说明。
3. 把 `throw new UnsupportedOperationException("skeleton");` 换成真实实现。你负责的文件里
   **不允许**再残留这个骨架异常（除非该方法是抽象钩子，且要在报告里说明）。
4. 注释 / Javadoc 一律用**中文**，风格沿用骨架里已有的写法（简洁、说清"为什么"）。
5. 行为基线是 `master` 分支，用 `git show master:<路径>` 读参考实现，例如
   `git show master:src/main/java/com/agent/software/event/TimeEventBus.java`。
   **不要把 master 的类原样搬过来**：新架构的依赖方向已经变了（见下），要按新接口重新组织。
6. 不引入新的第三方依赖。可用的只有：Jackson 2.17、slf4j、jakarta.mail、JDK 25 标准库。
7. 日志用 `org.slf4j.Logger`，不要用 `System.out.println` 做日志（除非是面向用户的控制台输出）。

## 已经实现好、可直接调用的基础层（先读这些文件了解可用 API）

- `com.agent.software.kernel`：`Ids`（RoleId/TaskId/EventId/ScheduleId/MailId/TodoId/SkillId/NoteId，均校验非空，`*.generate()` 可用）、
  `Text`（truncate/squashWhitespace/orEmpty/isBlank/joinNonBlank）、
  `Payload`（不可变带类型访问器：of/empty/ofMap/with/string/stringOr/intValue/boolOr/has/asMap/title/text/subject）、
  `JsonSchema`（`JsonSchema.object().string(...).required(...)`，`Builder` 继承 `JsonSchema`，可直接放进 `ToolSpec`）、
  `Tick`（plus/before/after）、`DayTick(day, tickOfDay)`、`DomainError(code, message[, cause])`。
- `com.agent.software.sim.event`：`AgentEvent`（broadcast/toRole/scheduled/targeted/broadcast/target/rescheduledTo）、
  `EventKind`（SHIFT_START/SHIFT_END/TASK_DUE/NEW_MAIL + wire()/parse()）、`Priority`（weight/ofWeight/parse）、`EventSink`。
- `com.agent.software.tool.spi`：`Tool`（spec()/invoke(RoleId, Payload)）、`Toolkit`（id()/instantiate()）、
  `Toolbox`（specs()/invoke(String, Payload)）、`ToolSpec(name, description, JsonSchema)`、`ToolResult.ok/error`。
- `com.agent.software.infra.config`：`AppConfig`（defaults() + Llm/Schedule/Storage/Web/Mail/Toolkits）、
  `AppPaths.resolve(Storage)`（dataDir/dataFile/configFile/ensure）、`ConfigLoader.load()`。
- `com.agent.software.infra.json`：`JsonCodec`、`JacksonJsonCodec`（write/writePretty/readMap/read）。

**其余包仍是骨架**（方法体抛 `UnsupportedOperationException`）。你可以放心按骨架里声明的签名去调用它们，
但**不要调用骨架里不存在的方法**——那会导致编译失败。

## 编译校验（必须做）

用**你自己的**输出目录，避免和并行的其他人抢 `target/`：

```bash
cd /home/openclaw/workspace/AgentSoftware && mkdir -p target/check-<你的tag> \
  && javac -encoding UTF-8 -nowarn -d target/check-<你的tag> \
     -cp "$(cat redesign/_scratch/cp.txt)" $(find src/main/java -name '*.java') 2>&1 | head -40
```

必须 **0 错误**。如果有错误来自**不是你负责的文件**（因为别人正在同时改），忽略它，但要在报告里指出。

## 报告格式（最终回答）

```
完成：<一句话>
文件：
- <路径> → <做了什么>
未完成/需配合：
- <问题> → <建议>
编译：<命令> → <结果>
```

不要粘贴大段代码到报告里。
