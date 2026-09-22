# 关系图：data/ 目录里那家「AI 软件公司」的人与事

用 **Graphviz (DOT)** 绘制，源码即 `.dot` 文件，可直接改、可重新渲染。

## 四张图

| 图 | 文件 | 画的是什么 |
|---|---|---|
| 图 1 · 人 | `1-org-people.dot` | 47 名角色的组织架构：领导层（CEO 林总 / COO 陈总 / CTO 高远 / CHRO）、幕僚岗（BA 徐若男、架构师王建国、RM 方瑾妍、测试负责人刘子涵）、研发四条线（前端/后端/移动/全栈，各 1 lead + 3 成员）、测试组 20 名 QA（按专长网格排布）、安全组（红队白鹏 / 蓝队纪安 / 审计严东）、外部客户。连线 = 指挥 / 汇报 / 调度 / 审计线。 |
| 图 2 · 事（一） | `2-delivery-timeline.dot` | 可演示版本交付主线的因果链：D1 模糊需求 → 澄清「三端统一纯 Web」→ RS v1.1 定稿 → 阶段1 实现 → AD-03 静态快照裁定 → v0.1.0/1/2 → **v0.1.3 封版（peel=8e3951b，唯一对外基线）** → 阶段4 评审归档 → **客户验收（当前卡点，无回信）** → 《最终交付确认》待发。侧线是贯穿全程的门禁①（release-gate-smoke）/ 门禁③（loop-regression 26/26）。 |
| 图 3 · 事（二） | `3-v014-incident.dot` | v0.1.4 改进轨与安全事故的双轨因果图。左轨：5 项待修 → octopus `75e58a5` → 门禁资产批 `bc35288` → 漂移 tip `ad527dc3`（裁定丢弃）→ **D6 17:30 CEO《窗口收口结论》闸门（待触发）** → 干净重建 C1→C1'→C1''→C3 → 重打 tag。右轨：门禁①第4断言「自指不可满足」(`QA_SIGNOFF_COMMIT=bc35288`) → amend 循环 reflog 43,635 条 → 禁令期误建带病 tag `901febab/peel=ad527dc` → 收敛裁定（口径 A：全 40 位精确比对 + 运行时注入 + 弃用 `ad527dc3`、基点改 `e7828d8`）→ 严东取证归档。 |
| 图 4 · 人 × 事 | `4-owner-matrix.dot` | D6 当前关键路径的责任映射（bipartite）：左侧 19 个角色 → 右侧 14 件待办/事件，连线标注承担关系（主责 / 复核 / 执行 / 提供载荷 / 等回信…）。节点颜色 = todo 状态：黄=当前卡点、橙=待触发、绿=已闭环或守护中、红=事故跟踪、灰=转 v0.1.5。 |

## 重新渲染

```bash
cd diagrams
for f in 1-org-people 2-delivery-timeline 3-v014-incident 4-owner-matrix; do
  dot -Tpng -Gdpi=110 "$f.dot" -o "$f.png"
  dot -Tsvg "$f.dot" -o "$f.svg"   # SVG 可无损放大，推荐看细节
done
```

要求：`graphviz`（含 `dot`）+ 中文字体 `Noto Sans CJK SC`（图中 `fontname` 即用它）。

## 事实来源

- **人 / 组织**：`data/journals/*.md` 每条会话首行 `Role ready: <姓名> — <岗位>`；`data/mail/mailboxes.json` 通讯录（47 人）。
- **事 / 时间线**：`data/notes/CEO/summaries/1-5.md`（D1–D5 复盘）、`data/notes/CEO/notes/Day{1..6}-project-status.md`、`data/notes/CEO/notes/D6-窗口收口结论-17_30.md`。
- **事故链**：`data/drive/yandong/sec-audit-2026-0916/EVIDENCE-INDEX.md`、`incident-evidence.txt`、`incident-reflog-export.txt`；仓库实况 `data/drive/Public/work/sansheng-demo`（tags v0.1.0–v0.1.3，HEAD=ad527dc3）。
- **当前待办 / 状态色**：`data/todos/*.json` 的 `status` 字段（CEO / COO / CTO / release_manager / attacker_2 / tester_* 等）。
