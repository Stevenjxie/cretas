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

### 1.2 6.9 仍然有效的生产大框架

这次再核 6.9 后，需要明确：6.12 没重复说的，不等于不要。以下仍是生产实现的基础约束：

- 主链路仍是销售/计划驱动生产，再串起领料、生产中、生产完结、成品/半成品库存、仓库确认和成本闭环。6.9 已经明确“工艺/车间细节全部省掉，只保留领料 -> 生产中 -> 生产完结主链”，6.12 只是把这个主链的结单口径补实。
- 工序模板仍有效，但它是“积木”而不是硬流程。产品可以跳过不需要的工序；工序上有负责人、出成率、标准时长、人效/工价等字段，后续用于任务推送、报工和人效分析。
- 人员绑定应在生产计划/批次层，而不是死绑 SKU 模板。6.9 讨论过生病、临时换岗，所以开工前看匹配工序和负责人，开工后把批次工序推到对应人手机 APP。
- 分端原则仍有效：复杂/大数据量的结单、核对、审批、维护放 PC；现场单点动作如报工、领料确认、调拨接收放 RN/手机端。
- 报工不是车间实时 MES，而是在线/批量补录闭环。至少要保留工序任务、实际数量、人数/工时、必要照片/证据、T/T-1/T-2 补录窗口和 T-3 锁死这类防呆规则。
- 整单撤回仍有效，且是整单撤回，不是单工序撤回。有报工/库存/证据数据时走审批；无数据时可直撤；撤回必须保护半成品下游领用、成品状态和移动均价重放。
- 半成品库存仍需要，且不是“每种半成品一个仓”。6.9 已明确一个半成品库下用不同半成品 code/SKU 区分焯水猪蹄、熟制猪蹄等不同成本对象。
- 成本地基仍是实际批次和不含税成本。原料批次价、半成品移动均价、实际领用和工时是闭环依据；完整人效看板可以后置，但结单最小工时/人数数据不能缺。

对应证据主要来自 `docs/meetings/2026-06-09-liushanmen/requirements-catalog.md:243`, `:255`, `:288-295`, `:327`, `:330`, `:778-791`, `:838-839`，以及 `docs/superpowers/specs/2026-06-09-liushanmen/SP1-production-loop-dual-output-spec.md:16-36`, `SP2-secondary-processing-and-reversal-spec.md:13-15`, `R8-dual-stack-merge-design.md:24-32`, `SP9-labor-cost-efficiency-spec.md:20-23`。

### 1.3 6.12 对 6.9 的修正/收窄

6.12 主要修正的是“如何让真实现场好用”，不是推翻 6.9：

- 6.9 的“三状态/同单双产出”口径有摇摆；6.12 后采用“弱计划 + 同单可双领双产出 + 文员结单核对”的主路径。
- 6.9 的“二次加工”曾覆盖较宽；6.12 收窄为跨单、独立返工/再加工。普通同单半成品领用/产出属于正常生产。
- 6.9 的 BOM/参考配方仍是参考；6.12 明确实际领用、实际产量、实际工时才是结单和成本依据。
- 6.9 说完整人效模块可后置；6.12 澄清“系统完成计划”至少要录实际产量、实际领用、最小工时/人效基础数据，才算生产侧完成。
- 6.9 里的 APP 工序任务仍保留；6.12 要求 PC 文员结单补账，两个端的职责要衔接，不要把 RN 报工当作唯一完成动作。

### 1.4 二次加工最终边界

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

### 1.5 当前代码主要缺口

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
| 6.9: 工序模板/负责人/APP 任务推送 | 开工前核对工序和负责人，开工后任务进入对应人员 RN | `WorkProcess*`; `work_process_tasks`; RN task list/quick action | partial | smoke | A/C |
| 6.9: 报工补录和证据 | RN/Web 保留业务日期窗口、T-3 锁、照片/证据字段和用户可见错误 | `BackdateWindowValidator`; `PhotoAnnotation`; `YieldStepReportScreen.tsx` | partial | untested | C |
| 6.9: 半成品单仓多 code | 一个半成品库按 code/SKU 区分不同半成品成本对象 | `SemiFinishedInventory`; `WorkProcess.semiFinishedOutputCode`; WIP picker | partial | untested | C/D |
| 6.9: 整单撤回审批 | 有报工/库存/证据时走审批，无数据直撤，撤回不能拆成单工序 | `ReportReversal*`; reversal approval UI/RN submit | partial | untested | E |
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
8. 开工/下达入口显示工序模板和负责人摘要，不能断掉 6.9 的 APP 任务推送链。
9. 已下发 RN 工序任务的计划，列表行显示“已下发 APP 任务/待报工/部分报工”，并提供查看任务入口。

防呆：

- Dialog 标题带品名、计划号、销售单号。
- 原因 dropdown，`其他` 才显示备注。
- 后端错误 message 原样 sticky toast。
- 缺配置时显示去配置/去工单/去库存按钮。

验收：

- Web build 通过。
- Headed E2E 打开计划列表，验证按钮、状态、预警、超计划原因逻辑。
- Headed E2E 验证有工序任务的计划能看到 APP 报工入口和下一步，而不是 dead-end。
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
8. 保留业务日期补录窗口校验：T/T-1/T-2 按配置处理，T-3 起后端拒绝且 RN 原样显示阻断错误。
9. 保留照片/证据入口；如果当前工序配置要求证据，提交前必须显示缺证据原因和下一步。
10. 快捷操作入口不得绕过三阶段报工链；旧任务无法关联 `WorkProcessTask` 时显示“请联系主管重新创建任务/返回任务列表”，不能静默走旧栈。

防呆：

- 顶部显示计划号、品名、当前工序。
- 每个选择器显示可用量、单位、批次。
- 提交后显示下一步：下一工序/返回任务/等待文员结单。

验收：

- `npx tsc --noEmit`
- RN/Expo headed E2E：
  - 正常生产任务内同时选原料+半成品；
  - 超量禁提交；
  - T-3 补录返回明确后端 message；
  - 缺必需照片/证据时提交禁用或后端 409 原样展示；
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
4. 半成品成本按同一半成品库的 code/SKU 和移动均价流水计算，支持“旧半成品 + 新原料/新半成品”混合投入，不新建一堆半成品仓。
5. 成本使用不含税批次价；缺价时成本字段诚实 pending/null，并在 UI 提示“待补价/待成本过账”，不能假算。
6. 工时数据写 settlement labor，并在 SP9 字段存在时 rollup 实际人工。
7. 避免 `BatchCompletedEvent` 再对 F006 结构化结单走 BOM 自动消耗。

防呆：

- 关键过账失败则结单不应显示完成。
- 如果某个下游需异步处理，状态必须是 pending 且 UI 可见。

验收：

- 后端单测覆盖：
  - 原料消耗实际量；
  - 半成品 OUT；
  - 半成品 IN；
  - 半成品混合投入移动均价；
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
6. 有报工/库存/证据的撤回必须进审批；无数据允许直撤。
7. 审批页必须显示批次、品名、报工摘要、半成品下游使用情况。
8. 下游已领用半成品时，撤回必须 409 并给“先处理下游单”的跳转/提示。

验收：

- Web build。
- 单测/集成测试验证：
  - normal settlement 不创建 secondary plan；
  - independent rework 才写 `planSourceType=SECONDARY`；
  - 下游已用半成品时撤回 409；
  - 有数据撤回进入审批，无数据直撤；
  - 重复撤回请求幂等返回已有申请。

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
4. 验证工序模板、负责人、APP 任务入口可见。
5. 打开核对结单。
6. 输入超计划实际产量，选择原因。
7. 输入原料实际领用。
8. 输入半成品实际领用。
9. 输入工时。
10. 提交。
11. fresh readback 验证结单、领用、工时、状态。
12. 进入仓库确认/挂账页面验证下游状态。
13. 对有报工/库存数据的批次提交整单撤回，验证进入审批；无数据批次验证直撤。
14. 对下游已领用半成品的批次撤回，验证 409 message 和下一步提示。

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
10. 选择 T-3 业务日期补录，验证被拒绝且错误具体。
11. 缺必需照片/证据时验证提交禁用或后端 409 原样展示。
12. 从快捷操作入口进入同一任务，验证不会绕过三阶段报工。

### 4.3 Regression

每个 wave 合并前跑：

- backend package + targeted tests。
- web-admin build。
- RN tsc。
- 至少一条 headed smoke。
- 生产主流程完成后跑 deep Web + deep RN + clearing deep。
- 增加一条撤回 deep：有数据审批、无数据直撤、下游占用 409。

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
- 工序模板、负责人、APP 下发任务链不断。
- RN 报工保留补录时效、证据、max 边界和下一步提示。
- 成本按实际数据进入 pending/posted 状态，不伪造。
- 半成品库存按同一半成品库 + code/SKU + 移动均价处理，不新增一堆仓库。
- 整单撤回保留审批/直撤/下游占用 409 三条路径。
- 仓库确认/中转挂账可见。
- 二次加工入口不再误导普通半成品领用。
- Web/RN 都有 deep E2E 证据。

一句话：

```text
6.9 给库存成本地基，6.12 修正生产操作路径；实现时先保证文员和现场能按真实流程结单，再接成本和挂账。
```
