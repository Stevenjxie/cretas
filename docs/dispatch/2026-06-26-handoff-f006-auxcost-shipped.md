# Handoff — F006 段2(B) 辅料标准单价对账 SHIPPED (2026-06-26)

**客户**: F006 六膳门食品科技 (真客户; 产品"叮咚好食光"; 猪舌成品 `叮咚好食光轻卤门腔（猪舌）120g`, productTypeId `4e345886-52e4-494a-bcb3-3f0ee9e126b2`)
**承接**: [[2026-06-25-handoff-f006-dual-yield-auxcost]] 的段2(B) — 已全部完成并 prod live + headed 验。

---

## ⚠️ 账号 (第一步, Steve patch): 配置类 UI 必用 admin

| 账号 | 密码 | 角色 | 用途 |
|---|---|---|---|
| **f006_admin** | 123456 | factory_super_admin | **配置类 UI (产品-工序配置/成本配置) 必用** — `canWrite('system')=true` |
| f006_dept_admin | 123456 | department_admin | 权限受限, 配置页**只读** (`canWrite('system')=false`); 仅日常报工/看核算页 |

**坑**: 前端 `canWrite('system')` 比后端 `@RequireRole` 严 (后端含 department_admin 可 PUT, 前端整页编辑按钮不渲染)。用 dept_admin 测配置会误判"功能缺失"。详见 memory `feedback_config_ui_canwrite_system_gate`。

---

## ✅ 已 SHIP (prod live, main HEAD `9b967870e`)

| 内容 | 文件 | 验证 |
|---|---|---|
| **CostReconcileService** (Opus keystone, 纯函数) | `service/yield/CostReconcileService.java` + `dto/yield/CostReconcileResult.java` | 12 单测 + 334 扫描绿; 真 4 批全分支 headed |
| 端点 `GET /production/batches/{batchNumber}/aux-cost-reconcile` (只读) | `controller/ProductionBatchCostController.java` + `YieldReportService(Impl).getBatchReconcile` | @PriceSensitive 嵌套脱敏 |
| 数据层 Flyway V20261027_18 (3 列 std/aux_unit_price/aux_basis) + DTO/Service 映射 | `entity/ProductWorkProcess` + `ProductWorkProcessDTO` + `ProductWorkProcessServiceImpl` | prod 已 apply |
| 阈值工厂可配 (默认5%) | `FactorySettingsDTO.auxVarianceThreshold` (JSON 无迁移) + `YieldReportServiceImpl.resolveAuxThreshold` | |
| 配置 UI 3 字段 (标准率%/辅料单价/基准 INPUT\|OUTPUT) | `web-admin/.../system/product-processes/index.vue` 成本对话框 | headed: 渲染+保存+持久化 |
| 核算页对账卡 (投料应投vs实际多投 + 辅料三栏 + 逐工序表 + WARN常驻含next-action + INFO诚实留空) | `web-admin/.../production-analytics/M67YieldCost.vue` | headed: 过投788% WARN 渲染 |

**算法 (审计②铁律)**: 标准侧 `standardYieldRate`(配方) vs 实际侧报工(calculateSteps) — 不同源, 不会恒等0。`标准应投 = 实际末道产出(折kg via gramsPerUnit) ÷ Π(标准率)` vs 实际投料 → 多投。辅料 标准/实际/多投 (auxBasis INPUT/OUTPUT)。诚实留空: 标准率不全→standardComplete=false 抑制假超产; 跨单位无系数→留空; 无单价→按0。BigDecimal HALF_UP kg-scale4/钱-scale2 中间步 quantize。

**部署**: Java `v20260625_222137` (blue-green, Flyway 自动 apply) + web-admin (从 main 部署)。

---

## ✅ 连带修 (同 commit `9b967870e`)
1. **成本对话框保存 @Valid 400 老 bug** (pre-existing): `saveCostConfig` partial payload 缺 productTypeId/workProcessId → 后端 `update(@Valid DTO)` 的 `@NotBlank` 拒绝 400。现有 defaultCostCategory/辅料配置 保存**一直坏**, 一并修 (openCostConfig 记 identity, payload 带上)。
2. **工时录入 1 分钟时间选择** (客户反馈"时间没有25分"): `WorkHoursTable` 开始/结束 `el-time-select(step=15min)` → `el-time-picker(HH:mm)`。headed 验: 现 el-date-editor(time-picker), 接受 20:25。

---

## 🟡 Open (Steve 已定: 暂不做)
- **猪舌标准率是占位演示值** (我测试 API 配的 1.0/0.95/1.05/0.9/0.93/0.65/1.0; 辅料单价 滚揉1.91/熟制1.76 是客户 Excel 真值)。Steve: **测试情况不用管**, 张权填真配方率前对账数字仅供机制演示 (占位致某批 788% 过投信号偏大 — 不是bug, 是 GIGO)。
- **文员(dept_admin)配不了标准率** (canWrite 门控)。Steve: **暂不做**。要做=放宽该按钮门控 (小改, 不动 RBAC 主体)。
- 对账只跟数据/配置一样准: 报工数据脏(单位/口径乱)的批次会报夸张警 → WARN 提示人去查 (按设计)。

## 已完成杂项
- worktree 清理: cretas-f006-variance/varfix/dyinv/dual-yield/deploy-main 已 remove。保留 cretas-f006-aux (本任务) + cretas-batchpicker + cretas-f006。

## 域知识 (Steve 问的)
- **去舌苔工序 碎肉 vs 产出**: 碎肉=刮舌苔刮下的碎屑(副产/损耗, kg, 手录); 产出=去苔后干净舌肉(好货, 流下一道); 投入=碎肉+产出(reverseInput 反推, 只读); 出成率=产出÷投入。设计=这道不好直接称投入, 靠"碎肉+产出"反推。配置在 `PROCESS_SHEET_CONFIG.ts qushetou` + Flyway expected_byproducts "舌苔碎肉"。

关联 memory: `project_2026_06_25_f006_cost_optimization_and_picker`, `reference_f006_app_accounts`, `feedback_config_ui_canwrite_system_gate`。
