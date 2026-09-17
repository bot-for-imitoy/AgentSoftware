# redesign/diagrams —— 图都从脚本生成，不手写

```
python3 build_all.py              # 全部重画
python3 build_all.py uml_2_sim    # 只重画某一张
```

产物在 `out/`：每张图同时输出 `.png`（170dpi，可放大看字）和 `.svg`（矢量），
并保留中间 `.dot` 源码，方便 diff 和 review。

## 图清单

| 脚本 | 产物 | 画的是 |
|---|---|---|
| `arch_1_packages.py` | `arch-1-packages` | 包依赖图（**直接解析源码 import**，自动标出依赖环） |
| `arch_2_states.py` | `arch-2-states` | Agent 生命周期状态机 + 暂停门 |
| `uml_1_agent.py` | `uml-1-agent` | Agent 及其零件、实现的三接口 |
| `uml_2_sim.py` | `uml-2-sim` | sim 包：时钟、事件、以及四个依赖倒置接缝 |
| `uml_3_company.py` | `uml-3-company` | 公司编排、花名册、人事、Web |
| `uml_4_tools.py` | `uml-4-tools` | 工具 SPI、13 个工具包目录、装配链 |
| `dfd_0_context.py` | `dfd-0-context` | DFD 0 层：与外部世界的 6 条边界 |
| `dfd_1a_time_events.py` | `dfd-1a-time-events` | DFD 1 层：时间推进 → 事件 → 投递 → 入队 |
| `dfd_1b_execution.py` | `dfd-1b-execution` | DFD 1 层：一个任务的执行数据流 |
| `dfd_1c_lifecycle.py` | `dfd-1c-lifecycle` | DFD 1 层：跨天、快照、装配、人事 |
| `seq_1_task_life.py` | `seq-1-task-life` | 时序：一个任务的一生 |
| `seq_2_talk_wait.py` | `seq-2-talk-wait` | 时序：talk wait=true 与下班解阻塞 |

## 依赖

- `graphviz`（`dot`）—— 类图 / 包图 / DFD
- Pillow + 思源黑体（`/usr/share/fonts/adobe-source-han-sans/`）—— 时序图

`dsl.py` 是共用的 Graphviz 小 DSL（UML 类框、DFD 三件套、关系箭头、图例）；
`seq.py` 是自己写的时序图渲染器（Pillow），因为 graphviz 画不好时序图。

## 注意

`arch_1_packages.py` 里的 `LAYER` 只提供**颜色和分层号**，节点与边全部来自
`src/main/java` 的真实 import。也就是说：**改了代码，这张图会自动跟着变**，
不需要手工维护。
