# 分析能力 Phase 2 — A 组并行 wave (2026-06-11)

> 独立于六扇门 ACTIVE.md（那是另一 organizer chat）。本 wave = 分析能力成熟化收尾。
> Organizer = 本 chat (Opus)。单一出货闸：只有 organizer merge+从 main 部署。

## Fable 规划审计裁决
- **A1 食材真进价扩覆盖 → 砍**（prod 实证 recipe 食材↔supplier 价不相交，0 可 join = 数据门控非工程）
- **A7 → 降只读核验**（已验：渠道/优惠真接入，无需修，关闭）
- 真工程 = A2/A3/A4/A5/A6/D2，分 4 并行组 + 1 只读

## In-flight 表
| 组 | 任务(组内串行) | 分支 | worktree | 🔒 | 状态 |
|---|---|---|---|---|---|
| A | A6 方向词校验 → A5 工厂reconciler | feat/insights-direction-factory | ../cretas-a-insights | 护城河 | 🔵 在飞 |
| B | A2 工序级成本Silver fact_production_report | (merged) | - | migration | ✅ 部署+验(cost_source 51reported/180null诚实, mig V20261004_01) |
| C | A4 ProductionAnalysis迁gold → A3 接主驾驶舱 | feat/production-gold-ui | ../cretas-c-webadmin | - | 🔵 在飞 |
| D | D2 Java LLM调用usage日志(账单止血) | feat/java-llm-usage-log | ../cretas-d-java | 计量 | 🔵 在飞 |
| E | A7 渠道dashboard核验(只读) | - | - | - | ✅ 真接入无需修 |

## Scope 锁
- insights目录: 组A | gold/factory_production_etl: 组B(已释放) | web-admin production/dashboard: 组C | Java LLM: 组D

## 测试收尾(全组 merge 后)
单测(每PR) + prod set-tenant实查(A2 cost_source诚实✓ / A5A6工厂洞察重算拦瞎编 / A3A4数据对gold) + headed截图(A3驾驶舱+A4, 1920×1080 zh-CN) + D2 Java usage日志落库验证

## restart 恢复(若本chat断)
新 chat: `gh pr list` + `git -C <worktree> log origin/main..HEAD` 查 A/C/D 分支状态; 有 commit→Opus gate(🔒 dry-run migration用postgres/护城河逻辑/Java计量)→merge→从main部署→上面测试收尾。memory: project_2026_06_11_analysis_capability_gold_etl_maturity。
