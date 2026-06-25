# Handoff — F006 双出成率 + 辅料成本对账 (2026-06-25 晚)

**客户**: F006 六膳门食品科技 (真客户; `f006_dept_admin` / `123456`; 产品"叮咚好食光"; 猪舌 `4e345886-52e4-494a-bcb3-3f0ee9e126b2`)
**prod**: main HEAD 含 #1129/#1130/#1131/#1132; Java 蓝绿 **v20260625_202824** (blue 10010); web-admin prod 139:**8086** (index `index-jflHZazl.js`)。**test 环境停用, 部署只 `--env prod`。**

---

## ✅ 本轮已 SHIP + 验证 (prod live)

| PR | 内容 | 验证 |
|---|---|---|
| #1130 | **A修 variance 跨单位 guard** — getSettlementPrefill + settleProduction 裸比 actualFinished(份/盒) vs plannedQuantity(kg) → 误报超产。新增 `isCrossUnitPlan(batches)` 复用 `ProductionBatch.plannedUnit!=unit`, 两处守卫 (prefill 改 INFO `QUANTITY_UNIT_CROSS` / settle 跳 `PRODUCTION_OVER_PLAN_REASON_REQUIRED`) | TDD 4测试(4618 vs 1912=plan 24a0954c 真值)+28回归; jar 含 marker×4 ✓ |
| #1129 | 段1 双出成率后端 + **批次详情卡** (bonus) — `StepYieldDTO.cumulativeYieldRate` 逐工序 cumulative (复用 standardGramsPerUnit 跨单位) | 41测试 |
| #1132 | 段1b 双出成率 → **逐工序录入「双出成率总览」卡** (张权 literal 位置) — 扩展 `ProcessSheetInventoryItem` + `YieldCardTable.vue` + 挂进 `ProcessSheet.vue` | **headed 实测渲染出 对上工序出成/对原料累计 两列** ✓ (空数据计划显"暂无", 有报工显率值) |
| #1131 | docs (转录/spec/plan) | — |

**算法 (双出成率)**: 对上工序 = 本道产出÷本道投入; 对原料(cumulative) = 本道产出(折首道单位)÷首道投入。猪舌焯水道实证 88.89% vs 80%。

---

## 🟡 段2(B) 辅料成本对账 — 进行中 (数据层已建, 未部署)

### 设计 (已定稿, 经 3-agent 审计修正)
**核对真实意图 (Steve 澄清)**: 不是同一辅料成本算两遍对账(那样恒等0无信号——审计②证)。是 **投料端「应投」(标准出成率推算) vs 出成端「实际」 → 抓多投/误差/浪费**。一端标准、一端实际才有信号。
- 标准应投 = 实际末道产出 ÷ Π(标准出成率); 多投 = 实际首道投料 − 标准应投; >阈值预警。
- 辅料: 标准辅料(标准kg×单价) / 实际辅料(实际kg×单价) / 多投辅料(差额)。
- spec: `docs/superpowers/specs/2026-06-25-dual-yield-auxiliary-cost-design.md` (§5 重写为 B, §11 审计处置 9 项)。

### ✅ 已建 (commit `28014ba17` on `feat/f006-aux-cost`, **未部署**)
**数据层** — 关键发现: `product_work_processes` 已是 per-(产品×工序) 成本配置家 (已含 `default_cost_category`/`aux_alloc_method`) → **扩展它, 不建新表**:
- Flyway **V20261027_18** (`db/flyway/`) ALTER ADD: `standard_yield_rate`(NUMERIC 8,4) / `aux_unit_price`(NUMERIC 12,4) / `aux_basis`(VARCHAR 10 INPUT|OUTPUT)。IF NOT EXISTS 幂等。
- `ProductWorkProcess.java` 加 3 字段 (standardYieldRate/auxUnitPrice/auxBasis)。BUILD SUCCESS。

### ⬜ 待建 (下个 session)
1. **reconcile 引擎 `CostReconcileService`** (Opus keystone, 判断密集): 投料标准正推 / 出成实际 + 多投差异 + 辅料标准/实际/多投。BigDecimal HALF_UP 中间步 quantize。**铁律(审计②): 对账一端标准率、一端实际率, 禁两端同源(恒等0)。**
2. **配置 UI**: 产品-工序配置页 (`web-admin/src/views/.../产品-工序配置`) 加 3 列 (标准率/辅料单价/基准)。Sonnet。
3. **核算页**: 成品出厂核算/核对结单显示 标准辅料/实际辅料/多投差异 三栏 + 差异>阈值(默认5%, 工厂可配)预警(防呆四位一体 sticky+next action)。
4. **测试** + 🔒 Opus gate + 从 main 部署 (Flyway 是 🔒)。

### 边界 (审计已钉, §5.5)
- 混批/diamond: 辅料倒推用线性引擎, **本期只支持线性链, 混批 defer** (成本引擎有 path-scoped diamond 遍历, 未来复用)。
- 注射多段工序: 单(产品×工序)单价表达不了, 已知限制。
- 跨单位 份→kg 用 `ProductType.gramsPerUnit`(可空, 空→留空不误报)。
- 主辅料同框架: 主料走 `OrderCostBreakdownService`(实际领用), 辅料走标准单价分摊。

---

## 教训 / gotcha (本轮)
- **deploy-web-admin.sh 默认部 TEST(8097) 非 prod(8086)** — 必 `echo YES-PROD | ./deploy-web-admin.sh --env prod`。第一次"部署成功"但 prod 没变, headed 才暴。(deploy-backend.sh 默认 prod, 两脚本相反!) → memory `feedback_deploy_web_admin_defaults_to_test`。
- **Flyway active dir = `src/main/resources/db/flyway/`** (NOT stale `db/migration` 那个 max V20260429)。真 max V20261027_17。编号防撞必查对 dir。
- **Sonnet 连 2 次把双出成率 UI 放错位置** (#1129 批次详情 / #1132 建 orphan 组件没挂载) → Opus 自己 mount 才对。张权-specific UI 要 Opus 收。
- **3-agent 对抗审计抓出段2核对结构性 no-op** (写码前) → 经客户澄清改 B 方案。审计值回票价。

## 清理 (可随时)
worktree: `cretas-f006-variance` / `cretas-f006-varfix` / `cretas-f006-dyinv` / `cretas-f006-dual-yield` / `cretas-deploy-main` (全已 merge, 可 `git worktree remove`)。`feat/f006-aux-cost` 保留 (段2 在飞)。scratch 截图 `f006-*.png` in 主目录。

关联 memory: `project_2026_06_25_f006_cost_optimization_and_picker`, `feedback_deploy_web_admin_defaults_to_test`。
