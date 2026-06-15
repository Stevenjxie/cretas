# 六扇门生产结单闭环修正版 Spec

**日期**: 2026-06-13
**范围**: F006 六扇门 2026-06-12 走查后的生产主流程修正
**类型**: Superpowers spec / 后续实现合同
**状态**: Draft, 用于拆 PR 和 E2E 验收
**核心原则**: 弱计划、强结单、强对账

这份 spec 是对 2026-06-12 转录记录的二次修正版解释。Steve 已澄清：转录中至少有三类声音，分别是 Steve/系统抽象、厂长/生产现场、财务/账务责任。前后看似矛盾的话，很多不是冲突，而是不同角色在定义不同控制面。系统设计必须同时容纳三条线，不能把所有话压成一个单线程需求。

## 1. 真相来源

### 1.1 转录证据

- 同一个生产上下文可以产出半成品和成品：`docs/meetings/2026-06-12-xinluyin3/transcript.txt:354-394`。
- 领料可以同时包含原料和半成品：`transcript.txt:401-418`。
- 这段讨论中的同单半成品逻辑，不要拆成二次加工：`transcript.txt:427-431`。
- 半成品出库重量现场自己称，原料和半成品可以按现场情况混合投入：`transcript.txt:435-456`。
- 系统不能默认优先消耗半成品，现场决定实际用了原料还是半成品：`transcript.txt:484-512`。
- 计划是参考，实际产量和实际领用按现场情况报：`transcript.txt:601-693`。
- 计划下达后应该进未完成列表，文员逐个勾/核对，不要堆在待生产中间态：`transcript.txt:693-728`。
- 完成时要录实际产量：`transcript.txt:735-741`。
- 完成闭环至少涉及计划产量、实际产量、工时/人效等完成状态：`transcript.txt:808-816`。
- 工单打印展示参考值，实际领用在结单时再填：`transcript.txt:985-1088`。
- 生产送仓库、仓库确认、中转挂账要区分仓库少交和生产少产，10kg 左右称重误差不追责：`transcript.txt:1147-1245`。

### 1.2 已有规划证据

- `docs/meetings/2026-06-12-xinluyin3/handoff-gpt.md` N2 已确认：双产出已建，缺的是原料+半成品一次双领。
- `docs/meetings/2026-06-12-xinluyin3/handoff-gpt.md` N10 已确认：中转仓要完整挂账账本，不是轻量确认按钮。
- `docs/superpowers/specs/2026-06-09-liushanmen/SP1-production-loop-dual-output-spec.md` 定义了同单双产出。
- `docs/superpowers/specs/2026-06-09-liushanmen/SP2-secondary-processing-and-reversal-spec.md` 定义了跨单独立二次加工和撤回。
- `docs/superpowers/specs/2026-06-13-liushanmen-n10-transfer-clearing-ledger-spec.md` 定义了中转挂账账本。本 spec 定义生产结单如何喂给该账本。

### 1.3 当前代码证据

- 后端计划完成接口目前只收 `actualQuantity`：`backend/java/cretas-api/src/main/java/com/cretas/aims/controller/ProductionPlanController.java:327-341`，`backend/java/cretas-api/src/main/java/com/cretas/aims/service/ProductionPlanService.java:117`，`backend/java/cretas-api/src/main/java/com/cretas/aims/service/impl/ProductionPlanServiceImpl.java:1184`。
- 计划级完成只有在有关联批次时才级联批次并发 `BatchCompletedEvent`：`ProductionPlanServiceImpl.java:1210-1234`。
- `BatchCompletedEvent` 当前会触发 BOM 自动消耗和成品批次创建：`backend/java/cretas-api/src/main/java/com/cretas/aims/service/orchestration/SupplyChainOrchestrator.java:244-261`。
- Web 生产列表目前把 PC 主路径描述为录入实际数量完成：`web-admin/src/views/production/plans/list.vue:1088-1111`。
- Web 完成弹窗只录 `actualQuantity`，并用计划数量做 `max`：`web-admin/src/views/production/plans/list.vue:631-683`，`list.vue:1678-1719`。
- Web 二次加工入口比较显眼：`web-admin/src/views/production/plans/list.vue:1156`，`list.vue:1764-1854`。
- RN 首道半成品领用目前绑定 `isSecondaryProcessing`：`frontend/CretasFoodTrace/src/screens/processing/YieldStepReportScreen.tsx:490-497`，`YieldStepReportScreen.tsx:689-709`，`YieldStepReportScreen.tsx:941-982`，`YieldStepReportScreen.tsx:2078-2157`。
- RN 已支持成品+半成品双产出：`YieldStepReportScreen.tsx:1195-1224`，`YieldStepReportScreen.tsx:2518-2543`。
- RN 已有工时段记录：`YieldStepReportScreen.tsx:1086-1095`，`YieldStepReportScreen.tsx:2637-2640`。

## 2. 按角色解释转录

### 2.1 厂长/生产线

生产侧定义现场怎么干活：

- 计划是参考，不锁死数量。
- 缺料是预警，不应该卡死开工和结单。
- 一张生产单可以同时领原料和半成品。
- 一张生产单可以同时产成品和半成品。
- 现场决定原料/半成品实际混合比例，系统负责记录。
- 文员需要未完成列表和简单核对动作，不需要多一个待生产中间态。

### 2.2 财务线

财务侧定义怎么算账、谁负责：

- 成本按实际领用算，不按计划/BOM 假定。
- 实际产量、实际原料、实际半成品、工时/人效、仓库确认都会影响闭环。
- 生产完成不等于仓库已收到。
- 中转差异要区分仓库责任、生产责任、在途责任。
- 采购 PDF、税率、销售审批阈值、财务报表是另外的财务面。

### 2.3 Steve/系统抽象线

系统设计负责把业务抽象成模块：

- 叮咚只是渠道/客户业务，不能做成硬编码的叮咚功能。
- 普通半成品领用不能被包装成二次加工。
- 二次加工只保留给独立返工/再加工/跨单半成品再投产。
- Web 侧边栏和页面按钮要按部门流程给下一步，不要让用户猜。

### 2.4 冲突判定规则

1. 生产说“先干活”，财务说“要算清楚”时，系统应实现“先允许做，结单补账”。
2. 生产说“计划只是参考”，系统不能用计划数量硬拦实际产量，只能预警并要求原因。
3. 同一生产计划内的半成品领用/产出，属于正常生产，不是二次加工。
4. 拿已有半成品库存独立开返工/再加工单，才是二次加工。
5. 生产和仓库数量不一致时，两个数都保留，差额进中转挂账，不自动覆盖。

## 3. 产品原则

六扇门生产应是：

```text
弱计划 -> 现场实际 -> 文员结单 -> 成本/库存过账 -> 仓库挂账确认
```

不应该是：

```text
硬计划 -> 料门卡死 -> 自动 BOM 消耗 -> 自动成品可用
```

具体原则：

- 计划数量、BOM 数、缺料数都是参考值。
- 实际产量和实际领用才是结单与成本依据。
- 所有关键限制必须前置显示。
- 写操作必须幂等、可审计。
- 下游责任必须显式，不得静默吞差异。

## 4. 正确端到端流程

### 4.1 销售与计划

1. 销售订单财审通过。现有财审闸门继续保留，和料门无关。
2. 生成/下达生产计划。
3. 计划直接进入“未完成生产”列表。
4. 列表显示：
   - 计划数量；
   - 原料参考值；
   - 半成品参考值；
   - 缺料预警；
   - 销售订单号；
   - 下一步按钮。

### 4.2 现场执行

RN 或后续 Web 结单要能记录：

- 原料批次和实际数量；
- 半成品批次和实际数量；
- 辅料/包材实际数量；
- 工时、人数、人效基础数据；
- 成品产量；
- 半成品产量；
- 必要时的照片证据。

系统不能默认半成品优先、原料优先、FIFO 优先，也不能只按 BOM 自动消耗。系统可以给参考，但最终按现场填报记录。

### 4.3 文员核对结单

PC 的“完成”应改为“核对结单”。

结单有效条件：

- 已录实际成品产量；
- 已录实际半成品产量，或明确为 0/不适用；
- 已录实际原料领用，或明确为 0/不适用；
- 已录实际半成品领用，或明确为 0/不适用；
- 已录工时/人效最小数据，或有权限地延期并填写原因；
- 实际和计划/参考差异超过阈值时，已选择原因；
- 需要送仓库的成品已生成后续中转/仓库确认动作。

### 4.4 仓库挂账

结单后：

- 成品不应默认直接变成销售可用库存，除非 N10 阶段配置允许；
- 仓库确认实收后，才进入成品可用；
- 差异在容差内，按容差关闭但保留审计；
- 差异超容差，保留在中转挂账，直到判定仓库/生产/在途/人工调整责任。

## 5. 范围

### 5.1 P0 必做

- 把 PC “完成生产”改为“核对结单”。
- 去掉实际产量被计划数量硬 max 的限制。
- 正常生产支持原料+半成品一次双领。
- 正常生产支持成品+半成品一次双产出。
- 结构化保存实际产量、实际领用、基础工时。
- Web/RN 显示边界、错误、下一步。
- 避免把普通半成品领用引导到二次加工。

### 5.2 P1 后续

- 侧边栏按部门和流程重整。
- 生产工单 PDF 补参考值和实际领用列。
- 完整人效看板。
- N10 中转挂账账本完整实现。
- 成本对比和财务报表深化。

### 5.3 明确不做

- 叮咚专属导入页面。后续应抽象成渠道订单/大客户订单/交付日需求。
- 物联网/摄像头自动工时。
- 通用审批流引擎大重构。
- 推翻所有 SP1/SP2 历史 spec。本 spec 只是修正边界解释。

## 6. 领域边界

### 6.1 正常生产

只要在同一生产计划/生产批次自然流转内，以下都属于正常生产：

- 消耗原料；
- 消耗半成品；
- 同时消耗原料和半成品；
- 产出成品；
- 产出半成品；
- 同时产出成品和半成品。

转录中的核心场景是：

```text
同一张生产计划
  投入：原料 + 半成品库存
  产出：成品 + 半成品
```

这不是二次加工。

### 6.2 二次加工

只有满足以下条件才叫二次加工：

- 独立新开的返工/再加工单；
- 来源是已有半成品库存；
- 不是同一生产计划的自然继续；
- 需要单独追溯来源、继承成本、支持撤回守卫。

现有 SP2 仍适用于这个收窄后的场景：

- `planSourceType = SECONDARY`；
- 来源是 `SemiFinishedInventory`；
- 消耗写二次加工/返工 lineage；
- 撤回时检查下游使用。

### 6.3 UI 命名边界

面向用户时，普通操作员不应被要求理解“二次加工”。

推荐命名：

- 正常流：`半成品领用`、`厂内半成品投入`、`核对结单`；
- 特殊流：`独立再加工/返工`，不要泛称 `二次加工计划`；
- 状态：`待核对`、`生产中`、`已结单`、`待仓库确认`、`差异待处理`。

## 7. Web 设计合同

### 7.1 生产计划列表

生产列表是部门工作台，不是普通表格。

默认视图：

- 未完成、生产中、待核对优先；
- 行底色：
  - 黄：未完成/待核对；
  - 蓝：生产中/已有部分报工；
  - 绿：已结单/已关闭；
  - 橙/红：缺料、差异、待仓库确认或超限；
- 状态字体要便于文员扫视。

每行按状态给下一步：

- `核对结单`；
- `APP报工`；
- `打印工单`；
- `查看缺料参考`；
- `去仓库确认`；
- `查看成本`；
- `查看销售单`。

如果因为缺配置不能继续，不能 dead-end。必须显示原因，并给跳转配置页或相关模块的按钮。

### 7.2 核对结单页/弹窗

`核对结单` 应是大弹窗或独立页面，一个实际数量字段不够。

头部上下文：

- 品名；
- 生产计划号；
- 销售订单号；
- 责任部门/责任人；
- 计划数量；
- 计划日期；
- 当前状态；
- 缺料预警摘要。

必填区域：

1. 产出：
   - 实际成品产量；
   - 实际半成品产量；
   - 单位；
   - 和计划/参考的差异。
2. 投入：
   - 原料实际领用明细；
   - 半成品实际领用明细；
   - 辅料/包材实际领用明细；
   - 每行显示参考需求、可用量、实际领用、单位、来源仓库/批次。
3. 工时/人效：
   - RN 已报工时段；
   - RN 未报时，Web 录最小字段：人数、分钟/小时、责任人可选；
   - 延期填写必须有权限和原因。
4. 差异：
   - 原因 dropdown；
   - 只有选择 `其他` 才显示备注；
   - 产量差异和领用差异可分别记录原因。
5. 过账预览：
   - 将消耗哪些库存；
   - 将产生哪些半成品库存；
   - 将产生哪些成品中转/仓库确认记录；
   - 哪些事项仍待处理。

### 7.3 Web 防呆规则

- 打开页时先显示可用量、参考量、max。
- 选定批次的实际领用不能超过可用量，除非有主管覆盖并生成差异/挂账。
- 实际产量超过计划数量时不禁提交，只预警并要求原因。
- 实际领用缺失时，必须选择 0/不适用，不能静默空。
- 后端错误 message 原样 sticky toast：`duration:0, showClose:true`。
- 结单提交必须后端幂等，不能只靠前端 loading。

## 8. RN 设计合同

### 8.1 正常生产任务

RN 操作员不应为了普通半成品领用去理解二次加工。

任务详情显示：

- 产品和计划号；
- 计划/参考产量；
- 原料参考；
- 半成品参考；
- 缺料预警；
- 当前工序和下一步。

### 8.2 投入阶段

正常首道投入必须支持：

- 原料 `MaterialBatchPicker`；
- 半成品选择器；
- 同一次提交同时带原料和半成品；
- 不自动按系统优先级选择。

硬边界：

- 原料批次领用量不能超过可用量；
- 半成品领用量不能超过可用量；
- 没有可用半成品时，显示空态，并允许业务允许下的纯原料投入；
- 原料和半成品都没选时，禁止提交并给明确提示。

### 8.3 工时阶段

沿用现有工时段：

- 开始/结束时间；
- 人数；
- 时长；
- 配置需要时上传照片/证据。

如果现场不在 RN 录工时，Web 结单必须补最小工时字段或填写延期原因。

### 8.4 产出阶段

产出支持：

- 只产成品；
- 只产半成品；
- 成品+半成品；
- 累计产出和剩余在制摘要。

如果半成品产出选项缺配置，显示配置缺失和下一步，而不是静默隐藏。

### 8.5 RN 完成后下一步

提交后不能停在无路页面。必须给：

- 继续下一工序；
- 返回未完成任务；
- 等待文员核对结单；
- 查看已提交摘要。

## 9. 后端/API 合同

### 9.1 新结单接口

新增结构化结单端点，不继续用 `completeProduction(actualQuantity)` 承载六扇门生产闭环。

建议：

```http
POST /api/production-plans/{id}/settle
```

请求体示例：

```json
{
  "idempotencyKey": "string",
  "actualFinishedQuantity": 0,
  "actualSemiFinishedQuantity": 0,
  "unit": "kg",
  "rawMaterialConsumptions": [
    {
      "materialBatchId": "string",
      "quantity": 0,
      "unit": "kg",
      "reason": "NORMAL"
    }
  ],
  "semiFinishedConsumptions": [
    {
      "semiFinishedInventoryId": 0,
      "sourceWipNo": "string",
      "quantity": 0,
      "unit": "kg",
      "reason": "NORMAL"
    }
  ],
  "auxiliaryConsumptions": [],
  "laborSegments": [
    {
      "workerCount": 1,
      "minutes": 0,
      "startedAt": "2026-06-13T08:00:00",
      "endedAt": "2026-06-13T09:00:00"
    }
  ],
  "quantityVarianceReason": "ACTUAL_OUTPUT_DIFFERS",
  "materialVarianceReason": "FLOOR_DECISION",
  "note": "string"
}
```

响应：

```json
{
  "success": true,
  "data": {
    "planId": "string",
    "status": "COMPLETED",
    "settlementId": "string",
    "warnings": [],
    "createdClearingLedgerIds": [],
    "createdInventoryTxnIds": []
  },
  "message": "生产结单已完成"
}
```

### 9.2 旧完成接口兼容

现有 `completeProduction(factoryId, planId, actualQuantity)` 是简单路径。

处理策略：

1. 老调用方可保留，但标记为不满足 F006 结构化结单。
2. 对允许简单完成的工厂/产品，可内部映射到结单。
3. 对 F006 需要结构化结单的计划，返回 409，并给下一步 message。

不能让六扇门计划在没有实际领用和工时闭环的情况下静默完成。

### 9.3 校验规则

- `actualFinishedQuantity` 可超过计划数量。超过阈值要原因，不因计划差异本身硬拦。
- 批次领用不能超过选中批次可用量，除非主管覆盖并生成差异/挂账。
- 半成品领用不能超过选中 WIP 可用量。
- 同一计划同一 `idempotencyKey` 重复提交，返回已有结果或 409 带已有结单链接。
- 销售订单未财审通过时，保留现有财审闸门。
- 必填材料/工时缺失时，返回具体 400/409 message 和 `hintTarget`。
- N10 挂账开启时，成品进入 clearing，不直接进入可用库存。

### 9.4 过账模型

结单应在一个受控事务里创建或更新：

- 生产结单主表；
- 原料消耗明细；
- 半成品消耗明细；
- 半成品产出库存/流水；
- 工时汇总；
- 计划状态；
- 成品送仓中转挂账记录。

不要 fail-soft 关键库存/成本写入。只要系统声称结单完成，下游必须已完成或明确进入待处理状态并可见。

## 10. 数据模型建议

最终命名可在实现时调整，但概念不应丢。

### 10.1 生产结单主表

`production_settlement`

- `id`
- `factory_id`
- `production_plan_id`
- `production_batch_id` nullable
- `status`: `DRAFT`, `SUBMITTED`, `POSTED`, `REVERSED`
- `actual_finished_quantity`
- `actual_semi_finished_quantity`
- `unit`
- `quantity_variance_reason`
- `material_variance_reason`
- `note`
- `settled_by`
- `settled_at`
- `idempotency_key`
- BaseEntity timestamps

### 10.2 结单领用明细

`production_settlement_consumption`

- `id`
- `factory_id`
- `settlement_id`
- `consumption_type`: `RAW`, `SEMI_FINISHED`, `AUXILIARY`, `PACKAGING`
- `material_batch_id` nullable
- `semi_finished_inventory_id` nullable
- `source_wip_no` nullable
- `quantity`
- `unit`
- `reference_quantity` nullable
- `source_warehouse_id` nullable
- `reason`
- `note`
- BaseEntity timestamps

### 10.3 结单工时明细

`production_settlement_labor`

- `id`
- `factory_id`
- `settlement_id`
- `worker_count`
- `minutes`
- `started_at`
- `ended_at`
- `source`: `RN_SEGMENT`, `WEB_MANUAL`, `DEFERRED`
- `responsible_user_id` nullable
- `reason` nullable
- BaseEntity timestamps

### 10.4 N10 挂账集成

N10 开启时，结单喂给：

- `inventory_transfer_ledger`
- `inventory_transfer_ledger_line`
- optional `inventory_transfer_ledger_event`

本 spec 不重复 N10 表设计，只定义生产结单是这些挂账记录的生产侧来源。

## 11. 状态模型

生产计划的用户可见状态应比数据库状态更清晰。

| 展示状态 | 含义 | 下一步 |
|---|---|---|
| `未完成` | 计划已下达且可操作 | RN 报工或结单 |
| `生产中` | 已有部分报工/领用/产出 | 继续报工或结单 |
| `待核对` | 现场看似完成但结单未闭环 | 文员核对结单 |
| `已结单` | 生产侧结单已提交 | 仓库确认/成本查看 |
| `待仓库确认` | 已生成成品送仓挂账 | 仓库收货 |
| `差异待处理` | 挂账差异超容差 | 判责 |
| `已关闭` | 生产和挂账均关闭 | 只读 |

对六扇门，不要把 `PENDING` 展示成“等待文员开工”。即使底层仍是 `PENDING`，UI 也应把它当成可操作的未完成任务。

## 12. 权限模型

最小权限边界：

- 生产文员：
  - 查看未完成生产；
  - 打开结单；
  - 填实际值；
  - 提交结单。
- 生产主管：
  - 覆盖差异阈值；
  - 审批工时延期；
  - 按权限撤回/重开结单。
- 仓库：
  - 确认收货；
  - 填实收数量；
  - 判定仓库侧差异原因。
- 财务：
  - 查看成本；
  - 查看挂账差异；
  - 必要时锁定/重开成本过账。
- 管理员：
  - 配置阈值、容差、权限。

所有写操作必须有审计记录。

## 13. 部门导航

侧边栏应按部门工作顺序重整。

生产建议：

1. 生产工作台
   - 未完成生产
   - 核对结单
   - 打印工单
2. 现场报工
   - APP 报工任务
   - 报工审批/历史
3. 半成品库存
   - 可用半成品
   - 半成品流水
4. 独立再加工/返工
   - 再加工计划
   - 撤回申请
5. 中转挂账
   - 待仓库确认
   - 差异处理

每个详情页按单据状态显示下一步快捷按钮。

## 14. E2E 验收

### 14.1 Web headed E2E

必须做到 deep，不只是打开页面：

1. 生产文员登录。
2. 打开生产计划列表。
3. 验证未完成计划有缺料/参考值和下一步按钮。
4. 打开核对结单。
5. 输入超过计划数量的实际成品产量。
6. 验证系统提示差异并要求原因，但不因超计划直接禁提交。
7. 添加原料实际领用。
8. 添加半成品实际领用。
9. 添加工时最小字段。
10. 提交结单。
11. 重新读取详情，验证：
    - 状态变化；
    - 实际产量持久化；
    - 原料/半成品领用持久化；
    - 工时持久化；
    - 下游挂账/成本待处理状态可见。

### 14.2 RN E2E

RN 或 Expo Web E2E 必须覆盖：

1. 打开生产任务。
2. 同一正常生产任务里选择原料批次和数量。
3. 同一正常生产任务里选择半成品批次和数量。
4. 验证 max/可用量边界。
5. 提交投入。
6. 提交工时段。
7. 提交成品+半成品产出。
8. 完成后看到下一步和已提交摘要。

### 14.3 挂账 E2E

N10 实现后：

1. 结单生成成品送仓挂账。
2. 仓库实收小于生产产出但在容差内。
3. 验证可用库存等于实收数量，挂账按容差关闭。
4. 仓库实收小于生产产出且超容差。
5. 验证差异保持打开，必须判责。

## 15. 实现切片

### Slice A: Web 结单界面

- 新增结单页/大弹窗骨架。
- `完成` 改名 `核对结单`。
- 去掉计划数量 hard max。
- 加差异预警和原因。
- 加原料/半成品/工时区域和诚实空态。

### Slice B: 后端结单 API

- 新 DTO 和 endpoint。
- 新 Flyway 表和 repository。
- 校验、幂等、财审闸门。
- 旧完成接口兼容或受控拒绝。

### Slice C: RN 正常双领

- 半成品 picker 脱离 `isSecondaryProcessing`。
- 二次加工保持特殊模式。
- 一次提交带 `materialBatchRefs` 和半成品来源/数量，或升级为结构化多 WIP payload。
- 加空态、max、错误展示。

### Slice D: 过账和成本

- 按实际原料领用过账。
- 按实际半成品领用过账。
- 按实际半成品产出入半成品库存。
- SP9 可用时 rollup 实际人工；不可用时保留工时数据，成本字段诚实 pending/null。

### Slice E: N10 集成

- 结单时创建成品送仓挂账，而不是直接可用库存。
- 仓库确认后才可用。
- 差异按 N10 spec 判责。

### Slice F: 导航和 E2E

- 重整侧边栏生产相关命名和顺序。
- 给详情页补下一步按钮。
- 补 Web headed Playwright 和 RN/Expo E2E。

## 16. 对旧 SP 的兼容解释

### 16.1 SP1

SP1 双产出仍有效，应解释为正常生产能力。

### 16.2 SP2

SP2 需要收窄：

- 保留跨单独立再加工/返工；
- 保留撤回；
- 不把正常同单半成品领用叫二次加工；
- 用户入口改名为 `独立再加工/返工`。

### 16.3 SP9

完整人效模块可继续 P1/P2，但结单必须先有最小工时闭环。如果 SP9 未完成，结单保存工时实际值，人工成本字段诚实 pending/null，不能假算。

### 16.4 N10

N10 挂账账本仍是仓库/生产责任的最终真相。本 spec 定义生产结单何时生成或喂给挂账记录。

## 17. 待定问题

1. 结构化结单是所有工厂强制，还是 F006 feature flag 开启？
2. v1 是否支持多条半成品来源，还是先支持一条半成品来源 + 多条原料？
3. 需要原因的差异阈值按任意差异、百分比、还是产品级绝对值？
4. 工时延期由文员可选，还是必须主管权限？
5. 结单后是创建 `PENDING_WAREHOUSE_CONFIRMATION` 成品批次，还是只创建 clearing ledger 等仓库确认？

## 18. 最终产品表达

六扇门生产不是严格计划执行系统，而是真实工厂闭环：

```text
计划给参考
现场报实际
文员核对结单
财务按实际算成本
仓库确认实物责任
中转挂账处理差异
```

任何实现如果只放行生产，但没有补齐实际产量、实际原料、实际半成品、工时/人效和仓库挂账，就不满足 2026-06-12 需求。
