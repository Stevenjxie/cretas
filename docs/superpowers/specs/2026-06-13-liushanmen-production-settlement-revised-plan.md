# 六扇门生产结单闭环修正版实施 Plan

**日期**: 2026-06-13  
**依赖 spec**: `2026-06-13-liushanmen-production-settlement-revised-spec.md`  
**目标**: 对齐 2026-06-09 蓝图与 2026-06-12 走查修正，形成可执行 PR 切片  
**执行原则**: 每个切片独立 worktree off `origin/main`，scope-lock 提交，deep E2E 后再声称完成  

## 1. 最终审计结论

### 1.1 6.9 与 6.12 的关系

6.9 是蓝图会议，定义了完整 ERP-lite 骨架：

- SP1: 同单双产出、半成品库存回挂、WIP 流水账。
- SP2: 二次加工、跨单半成品再投产、整单撤回。
- SP3: 三价成本、移动均价、实际成本。
- SP9: 人效/工时口径。
- SP7/N10 类问题: 仓库/库存责任与中转挂账。

6.12 是 live 系统走查后的修正：

- 计划数量只是参考，不能当硬限制。
- 缺料只做预警，不阻断开工/结单。
- 下达后进未完成列表，不要堆待生产。
- 普通生产单内可以同时领原料和半成品。
- 普通生产单内可以同时产成品和半成品。
- 同单半成品流不要拆成二次加工。
- 结单时补实际产量、实际领用、工时/人效，后续成本和仓库责任按实际闭环。

所以实现方向不是推翻 6.9，而是：

```text
保留 6.9 的库存/成本/撤回地基
收窄 6.9 的二次加工边界
按 6.12 重做生产主路径的 UI/API/结单行为
```

### 1.2 二次加工最终边界

普通半成品领用不是二次加工。

属于正常生产：

- 同一生产计划领原料 + 半成品。
- 同一生产计划产成品 + 半成品。
- 半成品作为本单投入之一。
- 半成品作为本单产出之一。

属于二次加工：

- 独立新开返工/再加工单。
- 来源是已有半成品库存。
- 不属于原生产计划自然继续。
- 需要单独 lineage、成本继承、撤回守卫。

### 1.3 当前代码主要缺口

| 缺口 | 当前证据 | 风险 |
|---|---|---|
| PC 完成只录实际数量 | `ProductionPlanController.completeProduction` 只收 `actualQuantity`; `list.vue` 完成弹窗只有一个字段 | 不能满足结单闭环 |
| 实际产量被计划数量 hard max | `list.vue` 完成弹窗 `:max=plannedQuantity` | 违反“计划只是参考” |
| 正常生产半成品领用被二次加工化 | RN 半成品首道领用依赖 `isSecondaryProcessing` | 用户会走错业务路径 |
| 计划级完成无结构化实际领用 | 后端无 `production_settlement` 等结构 | 成本只能假定或断链 |
| 完工后仍有自动消耗/自动成品可用倾向 | `SupplyChainOrchestrator.onBatchCompleted` 自动 BOM consume + create FG | 与 N10 仓库确认/挂账冲突 |
| 人效完整模块可后置，但结单缺最小工时闭环 | RN 有 segment，Web 结单未接 | 财务成本闭环缺字段 |
| E2E 只有 smoke/局部报工，缺结单 deep readback | 现有 headed 证据多为页面/报工链 | 不能证明业务可用 |

## 2. Claim Matrix

| 来源诉求 | 期望工作流 | 代码/API/页面 Hook | 当前状态 | 测试深度 | 计划切片 |
|---|---|---|---|---|---|
| 6.12: 计划下达后进未完成，文员逐个核对 | 计划列表默认显示未完成，可直接核对结单 | `web-admin/src/views/production/plans/list.vue` | partial | smoke | A |
| 6.12: 计划只是参考 | 实际产量可超计划，预警+原因，不硬拦 | `list.vue` 完成 dialog; 新 settlement API | missing | untested | A/B |
| 6.12: 原料+半成品同时领 | RN/Web 正常生产一次录双领 | `YieldStepReportScreen.tsx`; settlement DTO | partial | untested | C |
| 6.9+6.12: 成品+半成品双产出 | RN 输出阶段支持 BOTH，Web 结单也能核对 | `YieldStepReportScreen.tsx`; settlement output fields | partial | medium | C/D |
| 6.9: 半成品移动均价与流水 | 半成品 IN/OUT 有 Txn，成本按实际 | `WipInventoryServiceImpl`; `SemiFinishedInventoryTransaction` | partial | untested | D |
| 6.9: 二次加工+撤回 | 独立再加工/返工保留，不承载普通双领 | `ProductionPlanService.createSecondaryPlan`; `ReportReversal*` | partial | untested | E |
| 6.12: 结单时填实际领用 | PC 核对结单保存实际原料/半成品/辅料 | 新 `production_settlement*` 表和 API | missing | untested | B |
| 6.9/6.12: 工时/人效参与闭环 | RN segment 或 Web 最小工时字段 | `YieldStepReportScreen.tsx`; new labor table | partial | untested | B/C/D |
| 6.12/N10: 仓库确认才算实物责任闭环 | 结单产生 clearing，仓库确认后可用 | N10 ledger spec/API | spec-only/partial | untested | F |
| Steve: 叮咚抽象为渠道业务 | 不做叮咚专属功能，后续渠道订单抽象 | sales/channel/order docs only | deferred | N/A | non-scope |

## 3. 执行波次

### Wave 0: 文档和 gate

已完成：

- `2026-06-13-liushanmen-production-settlement-revised-spec.md`

本 plan 属于 Wave 0 收尾。

验收：

- PR 只含 docs。
- 明确 6.9/6.12 对齐关系。
- 明确 SP2 收窄边界。
- 后续所有实现切片引用本 spec。

### Wave 1: Web 主路径先改对

目标：把客户每天会用的 PC 生产列表和结单入口改成正确语义。

PR: `feat/liushanmen-production-settlement-web`

主要文件：

- `web-admin/src/views/production/plans/list.vue`
- `web-admin/src/api/production.ts` 或现有 production API client
- `web-admin/src/types/production.ts`
- 需要时新增 `web-admin/src/views/production/plans/SettlementDialog.vue`

任务：

1. `完成` 改为 `核对结单`。
2. 移除实际产量 `:max=plannedQuantity`。
3. 实际产量超过计划时显示预警，不禁提交。
4. 增加差异原因 dropdown。
5. 结单 UI 增加三个区域：
   - 产出: 成品/半成品；
   - 投入: 原料/半成品/辅料；
   - 工时: RN 已报摘要或 Web 最小录入。
6. 列表状态文案和颜色对齐：
   - 未完成；
   - 生产中；
   - 待核对；
   - 已结单；
   - 待仓库确认；
   - 差异待处理。
7. 每行补下一步按钮：
   - APP 报工；
   - 打印工单；
   - 查看缺料参考；
   - 查看销售单；
   - 仓库确认入口占位。

防呆：

- Dialog 标题带品名、计划号、销售单号。
- 原因 dropdown，`其他` 才显示备注。
- 后端错误 message 原样 sticky toast。
- 缺配置时显示去配置/去工单/去库存按钮。

验收：

- Web build 通过。
- Headed E2E 打开计划列表，验证按钮、状态、预警、超计划原因逻辑。
- 由于后端 API 尚未完成，此 PR 可用 UI skeleton + honest disabled submit/feature flag，但不能伪造提交成功。

### Wave 2: 后端结构化结单 API

目标：让结单有真实数据结构，不再只靠 `actualQuantity`。

PR: `feat/liushanmen-production-settlement-api`

主要文件：

- `backend/java/cretas-api/src/main/java/com/cretas/aims/controller/ProductionPlanController.java`
- `backend/java/cretas-api/src/main/java/com/cretas/aims/service/ProductionPlanService.java`
- `backend/java/cretas-api/src/main/java/com/cretas/aims/service/impl/ProductionPlanServiceImpl.java`
- 新 DTO: `dto/production/ProductionSettlementRequest.java`
- 新 DTO: `dto/production/ProductionSettlementResponse.java`
- 新实体/Repo:
  - `ProductionSettlement`
  - `ProductionSettlementConsumption`
  - `ProductionSettlementLabor`
- Flyway: 按 origin/main 最新号分配。

任务：

1. 新增 `POST /api/production-plans/{id}/settle`。
2. 新增结单主表、消耗表、工时表。
3. 实现 idempotency key。
4. 保留财审闸门。
5. 校验：
   - 实际产量可超计划；
   - 超阈值要原因；
   - 批次领用不能超过可用量；
   - 半成品领用不能超过可用量；
   - 原料/半成品/工时缺失时给明确 message 和 hint。
6. 旧 `completeProduction(actualQuantity)`：
   - 对 F006 结构化结单计划返回 409 + next action；
   - 或 feature flag 下仅允许简单产品。
7. API 返回统一 `{ success, data, message }`。

防呆：

- 重复提交同一 idempotency key 返回已有结单或 409 带链接。
- 所有 409 message 包含下一步。
- 不返回 mock，不用 fail-soft 假完成。

验收：

- `mvn -q clean package -DskipTests`
- 单测：
  - 超计划但有原因可结单；
  - 超计划无原因 400/409；
  - 半成品超可用 409；
  - 重复 idempotency 不重复过账；
  - 未财审仍被拦；
  - F006 旧完成接口被受控拒绝。

### Wave 3: RN 正常生产双领

目标：把半成品领用从二次加工语义里拿出来，进入正常生产投入阶段。

PR: `feat/liushanmen-rn-normal-dual-issue`

主要文件：

- `frontend/CretasFoodTrace/src/screens/processing/YieldStepReportScreen.tsx`
- `frontend/CretasFoodTrace/src/api/yieldReportApi.ts`
- `frontend/CretasFoodTrace/src/components/processing/MaterialBatchPicker.tsx`
- 需要时新增 `SemiFinishedBatchPicker.tsx`

任务：

1. 首道正常生产也能显示半成品 picker。
2. 半成品 picker 不再只依赖 `isSecondaryProcessing`。
3. 同一次提交带：
   - `materialBatchRefs`;
   - `semiFinishedConsumptions` 或兼容的 `sourceWipNo/sourceWipQuantity`。
4. 保留独立再加工/返工特殊模式。
5. 无可用半成品时显示空态，不阻断纯原料路径。
6. 原料和半成品都为空时禁止提交。
7. 超可用量禁提交并显示 max。

防呆：

- 顶部显示计划号、品名、当前工序。
- 每个选择器显示可用量、单位、批次。
- 提交后显示下一步：下一工序/返回任务/等待文员结单。

验收：

- `npx tsc --noEmit`
- RN/Expo headed E2E：
  - 正常生产任务内同时选原料+半成品；
  - 超量禁提交；
  - 提交后 readback 摘要显示两类投入。

### Wave 4: 结单过账和成本

目标：结单数据开始驱动真实库存与成本，不再靠 BOM 假定闭环。

PR: `feat/liushanmen-production-settlement-posting`

主要文件：

- `ProductionPlanServiceImpl`
- `BatchConsumptionServiceImpl`
- `WipInventoryServiceImpl`
- `SupplyChainOrchestrator`
- `CostRollupUtil` 或现有成本服务
- 结单实体和 repo

任务：

1. 原料按结单实际领用写消耗。
2. 半成品按结单实际领用写 OUT。
3. 半成品产出按实际数量写 IN。
4. 工时数据写 settlement labor，并在 SP9 字段存在时 rollup 实际人工。
5. 若成本字段缺价格，诚实 null/pending，不写 0。
6. 避免 `BatchCompletedEvent` 再对 F006 结构化结单走 BOM 自动消耗。

防呆：

- 关键过账失败则结单不应显示完成。
- 如果某个下游需异步处理，状态必须是 pending 且 UI 可见。

验收：

- 后端单测覆盖：
  - 原料消耗实际量；
  - 半成品 OUT；
  - 半成品 IN；
  - 缺价 null；
  - 不重复过账；
  - 自动 BOM 消耗不污染结构化结单。

### Wave 5: SP2 收窄与二次加工降权

目标：保留真正独立再加工/返工，避免误导普通半成品领用。

PR: `feat/liushanmen-secondary-rework-boundary`

主要文件：

- `web-admin/src/views/production/plans/list.vue`
- `ProductionPlanServiceImpl`
- `ReportReversal*`
- RN 二次加工入口相关文件

任务：

1. Web `二次加工计划` 改名 `独立再加工/返工`。
2. 弹窗文案明确：仅用于跨单已有半成品再加工。
3. 正常生产双领不从此入口走。
4. 保留 SP2 的：
   - source semi-finished;
   - independent plan;
   - reversal guard;
   - WIP insufficient guard。
5. 撤回仍按整单撤回，不改成单工序撤回。

验收：

- Web build。
- 单测/集成测试验证：
  - normal settlement 不创建 secondary plan；
  - independent rework 才写 `planSourceType=SECONDARY`；
  - 下游已用半成品时撤回 409。

### Wave 6: N10 结单挂账集成

目标：生产结单和仓库确认责任闭环。

PR: `feat/liushanmen-settlement-clearing-integration`

主要文件：

- N10 ledger spec 对应实现文件
- `SupplyChainOrchestrator`
- `ProductionPlanServiceImpl`
- warehouse receive/confirm controller/service
- web-admin clearing pages

任务：

1. 结单后成品进入 clearing，而非直接 sales-available。
2. 仓库实收后才创建或激活可用成品库存。
3. 10kg 容差配置生效。
4. 超容差差异进入待判责。
5. 原料仓库发料到生产也进入 clearing，可分批领用。

验收：

- 后端单测：
  - 119kg 生产，115kg 仓库实收，10kg 容差内关闭；
  - 2000kg 生产，1000kg 实收，1000kg 差异待处理；
  - 原料 5 吨发料，生产分三次领用，挂账余额递减。
- Web headed E2E：
  - 结单后显示待仓库确认；
  - 仓库确认后状态变化；
  - 超差显示判责入口。

### Wave 7: 生产工单 PDF 和导航

目标：把文员实际使用面打磨完整。

PR: `feat/liushanmen-production-print-navigation`

主要文件：

- `backend/java/cretas-api/src/main/java/com/cretas/aims/controller/PrintController.java`
- `backend/python/printing/services/pdf_renderer.py`
- `web-admin/src/router`
- `web-admin/src/layout/menu` 或实际侧边栏配置
- `web-admin/src/views/production/*`

任务：

1. 生产工单 PDF 显示：
   - 销售单号；
   - 生产单号；
   - 生产日期；
   - 打印日期；
   - 预计产量；
   - 原料/辅料/半成品参考值；
   - 实际领用列；
   - 打印人/账号；
   - 签字栏。
2. 侧边栏按部门顺序整理：
   - 销售；
   - 采购；
   - 仓库；
   - 生产；
   - 财务；
   - 管理。
3. 每个关键详情页加下一步按钮。

验收：

- Python PDF smoke。
- web-admin build。
- Headed E2E 打开工单 PDF，字段可见。
- 菜单扫描无 404。

## 4. E2E Campaign

### 4.1 Deep Web E2E

必须覆盖：

1. 登录生产文员。
2. 打开未完成生产列表。
3. 验证缺料参考、计划数量、下一步按钮。
4. 打开核对结单。
5. 输入超计划实际产量，选择原因。
6. 输入原料实际领用。
7. 输入半成品实际领用。
8. 输入工时。
9. 提交。
10. fresh readback 验证结单、领用、工时、状态。
11. 进入仓库确认/挂账页面验证下游状态。

### 4.2 Deep RN E2E

必须覆盖：

1. 登录操作员。
2. 进入正常生产任务。
3. 同屏选择原料批次。
4. 同屏选择半成品批次。
5. 验证可用量 max。
6. 提交投入。
7. 提交工时段。
8. 提交成品+半成品产出。
9. 查看提交摘要和下一步。

### 4.3 Regression

每个 wave 合并前跑：

- backend package + targeted tests。
- web-admin build。
- RN tsc。
- 至少一条 headed smoke。
- 生产主流程完成后跑 deep Web + deep RN + clearing deep。

## 5. 文件和 scope-lock

### 5.1 串行文件

以下文件不能并发改：

- `ProductionPlanServiceImpl`: Wave 2 -> Wave 4 -> Wave 6。
- `YieldStepReportScreen.tsx`: Wave 3 -> Wave 5。
- `WipInventoryServiceImpl`: Wave 4 -> Wave 5。
- `SupplyChainOrchestrator`: Wave 4 -> Wave 6。
- `web-admin/src/views/production/plans/list.vue`: Wave 1 -> Wave 5 -> Wave 7。

### 5.2 可并行切片

可在 Wave 2 后并行：

- Wave 3 RN 双领。
- Wave 7 PDF 的 Java/Python payload 初步字段准备。
- 导航整理的只读/菜单部分。

不可并行：

- Wave 4 和 Wave 6 都涉及库存过账，必须串行。
- Wave 3 和 Wave 5 都涉及 RN/二次加工边界，必须串行或严格 scope。

## 6. PR 顺序建议

1. `docs: add liushanmen production settlement revised spec` - PR #810。
2. `docs: add liushanmen production settlement revised plan` - 本 plan，加入 PR #810。
3. `feat: add liushanmen production settlement web flow`。
4. `feat: add liushanmen production settlement api`。
5. `feat: support normal production raw and semi-finished dual issue in RN`。
6. `feat: post liushanmen settlement actual consumption and labor`。
7. `feat: narrow secondary processing to independent rework`。
8. `feat: integrate production settlement with transfer clearing ledger`。
9. `feat: polish production print and department navigation`。
10. `test: add liushanmen production settlement deep e2e`。

## 7. Stop Conditions

不要继续实现，先回报 Steve 的情况：

- 6.9/6.12 证据与当前 spec 冲突，且无法按角色解释。
- 结单数据模型需要改已上线核心库存表主键或单位口径。
- N10 clearing ledger 与现有 `FinishedGoodsBatch` 状态模型冲突，无法兼容。
- RN payload 需要破坏现有报工 API 兼容。
- 财务要求实际成本立即出值，但原料/半成品缺价。

## 8. Done Definition

这个生产主流程只有在以下全部成立时才算完成：

- Web 文员能从未完成列表进入核对结单。
- 实际产量可不同于计划，并有原因和预警。
- 正常生产可一次录原料+半成品。
- 正常生产可一次产成品+半成品。
- 结单保存实际产量、实际领用、工时。
- 成本按实际数据进入 pending/posted 状态，不伪造。
- 仓库确认/中转挂账可见。
- 二次加工入口不再误导普通半成品领用。
- Web/RN 都有 deep E2E 证据。

一句话：

```text
6.9 给库存成本地基，6.12 修正生产操作路径；实现时先保证文员和现场能按真实流程结单，再接成本和挂账。
```
