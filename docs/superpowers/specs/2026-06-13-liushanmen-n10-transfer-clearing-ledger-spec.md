# 六扇门 N10 中转仓完整挂账对账 — 设计 spec

**日期**: 2026-06-13  
**类型**: spec-only / 不写实现代码  
**状态**: 待 Opus gate 评审  
**范围红线**: 本文只定义库存事务、挂账账本、对账与前后端改造面；本 PR 不新增 Java/TS/RN 业务实现。

---

## 1. 背景 / 客户原话

来自 `docs/meetings/2026-06-12-xinluyin3/handoff-gpt.md` N10：

- 成品结单送仓库需仓库确认收货才掉锅，不能完工后自动进入可用成品。
- 中转仓挂账要区分「仓库少掉锅」还是「生产少产」。
- 原料同理。
- 10kg 内称重误差不计。
- Steve 拍板范围是「完整中转挂账账本」，不是轻量 handshake；先写 spec，不直接动代码。

转录依据：

- transcript [20:30]-[20:45]：调拨后「最后我们挂账」「过程里面挂了多少公斤账」「再去找这个账到底是因为什么原因」。
- transcript [24:57]-[25:21]：昨天单子还在途中，生产应可逐个处理未完成项，不应被前置领料/开工门卡死。
- transcript [36:41]-[36:57]：库存偏差如果挂在中转仓，就能找「仓库掉锅少了」还是「生产少了」，两边都要核对。
- transcript [37:00]-[37:12]：原料同理；仓库掉锅给生产，中间建一个库，生产从这个库领走；10kg 内按称重误差不计算。
- transcript [37:23]-[37:41]：仓库可一次出 5 吨，生产分天领用；一直挂在中转仓，双方知道已领多少；成品同样原理。

## 2. 当前实现约束

### 2.1 已有能力

- `SupplyChainOrchestrator.onBatchCompleted` 当前在批次完成后：
  - `batchConsumptionService.autoConsumeForBatch(batch)` 自动扣料；
  - `createFinishedGoodsFromBatch(batch)` 自动创建成品批次；
  - 更新生产计划进度和质检任务。
- `TransferServiceImpl` 已有调拨生命周期：
  - `shipTransfer`: 调出方发货并扣 source warehouse；
  - `receiveTransfer`: 调入方签收并写 `receivedQuantity`；
  - `confirmTransfer`: 调入方确认后创建 target warehouse 库存；
  - `TransferDiffServiceImpl.detectAndGenerateDiffs`: 只在 `received < shipped` 时生成差异单。
- `FactoryWarehouse.WarehouseType.TRANSFER` 已存在，但只是调拨在途仓 marker，不是完整挂账账本。

### 2.2 缺口

- 完工后成品自动入可用仓，缺「生产报完、仓库确认收货、再进入目标仓可用」的中转账本。
- 原料出库给生产时直接扣源仓/自动消费，缺「源仓掉锅进中转仓、生产分批领用、差额挂账」的账本。
- 现有调拨差异只比较 shipped/received，不能表达生产侧应产/实产、仓库侧应交/实交、生产领用节奏、责任归因。
- 没有 10kg 称重误差配置，也没有按物料/成品单位标准化后再判断容差。

## 3. 设计目标

1. 引入中转仓/在途账本，让库存责任从「源仓可用」到「目标仓可用」之间有可查询、可对账、可归因的中间态。
2. 成品和原料都走同一挂账模型：
   - 源头确认出库或生产报工后，数量先进入中转挂账；
   - 目标方确认收货/领用后，才进入目标可用或生产消耗；
   - 未确认差额保持在中转账本，不静默丢失。
3. 责任归因要能清晰区分：
   - 仓库少货/少掉锅；
   - 生产少产/少领/未领；
   - 运输/在途损耗；
   - 称重误差免计。
4. 10kg 容差必须可配置，默认对 F006 生效，支持后续 per-factory/per-item-type 覆盖。
5. API/UI/RN 必须 surface 可操作错误，不允许后端半成品：前端要展示挂账、超差、待确认和责任待判定。

## 4. 概念模型

### 4.1 中转仓不是可用仓

`TRANSFER` 类型仓库只表示「责任暂存/在途挂账」。进入 TRANSFER 后：

- 不计入销售可用库存；
- 不计入生产可用库存，除非生产侧执行领用确认；
- 可计入财务库存总量，但必须单列为 `in_transit` 或 `clearing`；
- 必须有源业务单和责任上下文，不能手工裸增减。

### 4.2 两类账本

建议新增两层账本（命名供实现评审，可调整）：

| 表 | 粒度 | 用途 |
|---|---|---|
| `inventory_transfer_ledger` | 一次中转业务主单 | 记录来源、目标、业务类型、状态、责任结论、容差配置快照 |
| `inventory_transfer_ledger_line` | SKU/批次/单位行 | 记录 planned/sourceIssued/targetReceived/productionConsumed/variance 等数量 |

可选扩展：

| 表 | 粒度 | 用途 |
|---|---|---|
| `inventory_transfer_ledger_event` | 状态变更事件 | 审计 who/when/from/to/reason/payload |
| `inventory_transfer_variance` | 超差差异行 | 责任归因、处理结果、关联报损/调整/补发单 |

### 4.3 业务类型

`ledger_type` 至少覆盖：

- `FG_PRODUCTION_TO_WAREHOUSE`: 生产完工成品 → 成品仓收货。
- `RM_WAREHOUSE_TO_PRODUCTION`: 原料/辅料/包材源仓 → 生产领用。
- `WAREHOUSE_TO_WAREHOUSE`: 现有跨仓/跨厂调拨。
- `RETURN_OR_REVERSAL`: 后续退回/冲销，v1 可只预留。

### 4.4 数量字段

每行至少保留：

- `planned_quantity`: 计划应交/应领/应产数量。
- `source_issued_quantity`: 源头确认发出数量。成品场景为生产报工良品/结单送仓数量；原料场景为仓库掉锅/发料数量。
- `target_received_quantity`: 目标仓确认收货数量。成品场景由仓库确认；跨仓场景由调入仓确认。
- `production_consumed_quantity`: 生产实际领用/消耗数量。原料场景由生产确认。
- `available_posted_quantity`: 已进入目标可用库存或已记入生产消耗的数量。
- `variance_quantity`: 标准化单位后的 `source_issued - target_confirmed_or_consumed`。
- `tolerance_quantity`: 该行使用的容差快照，默认 10kg。
- `variance_effective_quantity`: 超出容差后需要追责的数量。

单位规则：

- 数量判断必须先标准化到基础计量单位，重量类以 kg 判断 10kg 容差。
- 箱/盒/袋等销售单位必须先经现有单位换算关系折算；无法换算时禁止自动判定容差，进入「待人工确认单位」状态。

## 5. 状态机

### 5.1 主单状态

```
DRAFT
  -> SOURCE_POSTED
  -> IN_TRANSIT
  -> TARGET_PARTIAL_CONFIRMED
  -> TARGET_CONFIRMED
  -> RECONCILING
  -> CLOSED
```

异常终态/旁路：

- `CANCELLED`: 源头未 posted 前取消。
- `REVERSED`: 已 posted 后按冲销单关闭，不直接删除库存事务。
- `DISPUTED`: 超差且责任未决。
- `FORCE_CLOSED`: 管理员带原因强制关闭，必须留审计。

### 5.2 行状态

| 状态 | 含义 |
|---|---|
| `PENDING_SOURCE` | 等源头确认发出/完工 |
| `SOURCE_POSTED` | 源头已扣/已报工，数量进入中转挂账 |
| `PARTIAL_TARGET` | 目标方部分确认 |
| `TARGET_CONFIRMED` | 目标方确认完成 |
| `WITHIN_TOLERANCE` | 差异在容差内，免追责但保留审计 |
| `VARIANCE_PENDING` | 超差待判责 |
| `WAREHOUSE_RESPONSIBLE` | 判定仓库少货/少掉锅 |
| `PRODUCTION_RESPONSIBLE` | 判定生产少产/少领 |
| `TRANSIT_RESPONSIBLE` | 判定运输/在途责任 |
| `ADJUSTED` | 已通过报损、补发、库存调整或成本调整处理 |
| `CLOSED` | 该行对账完成 |

## 6. 库存记账时点

### 6.1 成品：生产 → 成品仓

现状问题：批次完成后 `SupplyChainOrchestrator.onBatchCompleted` 自动创建成品批次，导致仓库尚未确认时成品已可用。

目标时点：

1. 生产报工/结单：
   - 记录良品数量；
   - 创建 `FG_PRODUCTION_TO_WAREHOUSE` ledger；
   - 数量进入 `TRANSFER` / `clearing`；
   - 不进入成品仓可用，不可销售出库。
2. 仓库收货：
   - 仓库录入实收数量；
   - 若差异 <= 容差，按实收数量入成品仓可用，差异标 `WITHIN_TOLERANCE`；
   - 若差异 > 容差，实收部分入可用，差额保留 `VARIANCE_PENDING`。
3. 判责：
   - 生产报工少于计划且仓库实收等于生产发出：`PRODUCTION_RESPONSIBLE`；
   - 生产发出数量大于仓库实收且仓库未能证明在库/在途：`WAREHOUSE_RESPONSIBLE` 或 `TRANSIT_RESPONSIBLE`，由业务选择；
   - 处理方式可为补发、报损、成本调整、强制关闭。

验收表达：

- 生产报工 119kg，仓库收 115kg，差异 4kg，默认 10kg 内：成品仓可用 115kg，ledger closed with tolerance，不生成追责差异。
- 生产报工 2000kg，仓库收 1000kg：1000kg 入成品仓可用，1000kg 挂账待判责，不能静默关单。

### 6.2 原料：源仓 → 生产

目标时点：

1. 仓库发料/掉锅：
   - 源仓可用库存扣减；
   - 数量进入 `RM_WAREHOUSE_TO_PRODUCTION` ledger 的中转挂账；
   - 生产未确认领用前，不计入生产消耗，不摊入批次成本。
2. 生产领用：
   - 生产可分天/分批确认领用；
   - 领用数量从中转挂账转为生产消耗；
   - 成本按实际领用数量进入批次成本。
3. 对账：
   - 仓库发 5 吨，生产第一天领 1 吨、第二天领 2 吨、第三天领 2 吨：ledger 保持部分挂账，直到领满或判责。
   - 仓库声称发 2 吨，生产只确认 1 吨，差额 1 吨超容差：进入 `VARIANCE_PENDING`。
   - 如果生产少领但源仓仍有实物未发出，判 `WAREHOUSE_RESPONSIBLE` 或执行补发；如果源仓已交付生产但生产未产出，判 `PRODUCTION_RESPONSIBLE`。

### 6.3 跨仓/跨厂调拨

现有 `shipTransfer -> receiveTransfer -> confirmTransfer` 可保留，但应接入 ledger：

- `shipTransfer`: 源仓扣可用，写 `source_issued_quantity`，状态 `SOURCE_POSTED/IN_TRANSIT`。
- `receiveTransfer`: 写 `target_received_quantity`，不立即关闭差异。
- `confirmTransfer`: 创建目标仓可用库存，并根据容差生成/关闭 variance。
- `TransferDiffServiceImpl` 从「只看 received < shipped」升级为 ledger variance 视图，支持容差、责任、部分确认、单位标准化。

## 7. 责任归因规则

### 7.1 判责输入

每条 variance 至少需要：

- 源头发出记录：谁发、何时发、发多少、源仓/生产批次、称重方式。
- 目标确认记录：谁收/领、何时收/领、实收多少、照片/备注可选。
- 计划/应产记录：生产计划、BOM/领料需求、批次报工良品。
- 单位换算快照：判责时不能因后续换算配置变化改变历史结论。

### 7.2 默认自动判定

只做低风险自动判断：

| 条件 | 结论 |
|---|---|
| `abs(variance) <= tolerance` | `WITHIN_TOLERANCE`，免追责 |
| 单位无法标准化 | `VARIANCE_PENDING` + error code `UNIT_CONVERSION_REQUIRED` |
| 目标确认缺失且已超 SLA | `VARIANCE_PENDING` + owner = target side |
| 源头数量缺失 | 阻止进入 `SOURCE_POSTED` |

超出容差的责任归因应由仓库/生产主管选择，系统提供推荐但不强判。

### 7.3 人工判责选项

- `WAREHOUSE_SHORT_ISSUE`: 仓库少发/少掉锅。
- `PRODUCTION_SHORT_OUTPUT`: 生产少产。
- `PRODUCTION_SHORT_CONSUME`: 生产少领/未领。
- `TRANSIT_LOSS`: 在途损耗。
- `COUNTING_ERROR`: 盘点/录入错误，需库存调整。
- `UNIT_CONVERSION_ERROR`: 单位换算错误，需修数据后重算。
- `OTHER`: 必填备注。

## 8. 10kg 容差配置

### 8.1 配置层级

建议配置键：

- `inventory.transfer.tolerance.defaultQuantity = 10`
- `inventory.transfer.tolerance.defaultUnit = kg`
- `inventory.transfer.tolerance.applyTo = WEIGHT_ONLY`
- `inventory.transfer.tolerance.factoryOverrides[F006] = 10kg`
- `inventory.transfer.tolerance.itemOverrides[materialTypeId/productTypeId]` 预留

### 8.2 规则

- 默认只对重量类单位生效。
- 非重量单位必须能换算为 kg 才能套用。
- 容差是每 ledger line 判断，不是整单相互抵消。
- 容差快照写入 ledger line，后续配置变更不重算历史，除非人工触发 re-evaluate。
- 容差内差异仍保留审计：不追责、不生成待处理差异、不影响关单。

## 9. 后端改造点

### 9.1 `SupplyChainOrchestrator`

需要从「完工后自动创建可用成品」改为「完工后创建成品中转挂账」：

- `onBatchCompleted` 保留批次完成、计划进度、质检任务。
- `createFinishedGoodsFromBatch` 不应直接写入可售成品仓可用库存；改为调用 ledger service 创建 `FG_PRODUCTION_TO_WAREHOUSE`。
- 仓库确认收货后再创建/更新 `FinishedGoodsBatch` 可用数量。
- 自动扣料需拆分评审：
  - 如果当前业务仍要求报工后自动按 BOM 消耗，必须明确它是生产已确认领用；
  - 若要满足 N10 原料同理，自动扣料应迁移到 `RM_WAREHOUSE_TO_PRODUCTION` 领用确认后入成本，避免未领先耗。

### 9.2 `TransferServiceImpl`

- `shipTransfer` 写 ledger source posted，不只扣库存。
- `receiveTransfer` 写目标实收，不直接等同差异最终结论。
- `confirmTransfer` 在目标仓入可用前检查 ledger 状态、单位换算和容差。
- 已发货/已签收取消继续禁止直接取消，新增 reversal/adjustment 路径。
- 所有状态变更必须幂等，重复点击返回当前 ledger 状态，不重复扣库存。

### 9.3 `TransferDiffServiceImpl`

- 从 transfer item 比较服务升级为 ledger variance evaluator。
- 处理 `received > shipped`、部分签收、分批领用、容差内免追责、单位不可换算。
- 差异记录应引用 `ledger_id`/`ledger_line_id`，保留现有 `transfer_id` 兼容查询。
- 生成差异不应是 non-blocking 静默失败；至少要将 `varianceEvaluationStatus` surface 到 API，前端提示「签收成功，但差异单生成失败，请重试对账」。

### 9.4 库存事务

实现时必须有不可变库存事务表或事件：

- source available -> transfer clearing
- transfer clearing -> target available
- transfer clearing -> production consumed
- transfer clearing -> scrap/loss
- transfer clearing -> source returned

每笔事务需要 `ledgerLineId`、`businessRefType`、`businessRefId`、`idempotencyKey`、`operatorId`。

## 10. API 设计

建议新增/扩展：

| API | 用途 |
|---|---|
| `GET /inventory/transfer-ledgers` | 按状态、类型、责任、源/目标仓查询挂账 |
| `GET /inventory/transfer-ledgers/{id}` | 主单详情 + 行 + 事件 + 差异 |
| `POST /inventory/transfer-ledgers/{id}/source-post` | 源头确认发出，通常由业务服务调用 |
| `POST /inventory/transfer-ledgers/{id}/target-confirm` | 仓库收货/生产领用确认，支持部分数量 |
| `POST /inventory/transfer-ledgers/{id}/variance/{lineId}/decide` | 人工判责 |
| `POST /inventory/transfer-ledgers/{id}/adjust` | 补发/报损/库存调整/强关 |
| `POST /inventory/transfer-ledgers/{id}/re-evaluate` | 修单位/配置后重算差异 |

错误 surfacing：

- `409 LEDGER_STATE_CONFLICT`: 状态不允许当前动作，返回当前状态和下一步。
- `422 UNIT_CONVERSION_REQUIRED`: 无法换算到 kg，返回缺失换算的 SKU/单位。
- `422 QUANTITY_EXCEEDS_CLEARING_BALANCE`: 确认数量超过中转余额。
- `422 TOLERANCE_CONFIG_MISSING`: 重量类缺容差配置。
- `500 VARIANCE_EVALUATION_FAILED`: 主动作成功但差异评估失败时必须可重试。

## 11. Web UI / RN 防呆

### 11.1 Web Admin

新增「中转挂账/在途对账」列表：

- 默认 tab：待我确认、超差待判责、在途中、已关闭。
- 行展示：业务类型、单号、源仓/生产批次、目标仓/生产批次、SKU、源发、实收/实领、挂账差额、容差、责任状态、已挂天数。
- 详情页展示事件时间线，不只展示最终数量。
- 超差判责必须强制选择责任类型和备注；`OTHER` 必填备注。
- 单位无法换算时，确认按钮禁用并给出修复入口/提示。

### 11.2 RN

仓库/生产移动端需要两个轻入口：

- 仓库「待收货/待确认」：扫单或点单，录入实收数量，显示计划/源发/容差。
- 生产「待领用」：按生产日期和批次过滤，支持分批领用，显示中转余额。

防呆：

- 输入数量默认不能大于中转余额；需要超收必须走异常原因。
- 数量差异超过容差时，提交后明确提示「已确认，差异待判责」，不能只 toast 成功。
- 弱网重复提交用 idempotency key，避免重复入库/重复消耗。

## 12. 迁移计划

1. Schema migration：
   - 新增 ledger/line/event/variance 表；
   - 为 existing transfer diff 加 nullable `ledger_id/ledger_line_id`；
   - 新增容差配置表或接入现有配置中心。
2. 数据 backfill：
   - open `InternalTransfer` 状态为 `SHIPPED/RECEIVED` 的单据生成 ledger；
   - 已 `CONFIRMED` 的历史单默认生成 closed ledger event，仅用于审计，不重开库存；
   - 历史 FG-AUTO 成品不回滚，只从 cutover 时间后走新账本。
3. Cutover：
   - feature flag `inventory.transferLedger.enabled`；
   - 先 F006 staging 开启；
   - 验证后按 factory 打开。
4. 回滚：
   - flag 关闭后新单回旧流程；
   - 已生成 ledger 不删除，只禁止继续推进，需人工处理。

## 13. 验收测试计划

### 13.1 后端单元/集成

- 成品完工后不进入成品仓可用，只生成 `FG_PRODUCTION_TO_WAREHOUSE` ledger。
- 仓库确认收货后才增加成品仓可用。
- 原料源仓发 5 吨，生产分 1+2+2 吨领用，中转余额正确递减。
- 差异 4kg 默认 10kg 内关闭为 `WITHIN_TOLERANCE`。
- 差异 1000kg 生成 `VARIANCE_PENDING`，实收部分仍可用。
- 无单位换算时返回 `UNIT_CONVERSION_REQUIRED`，不自动套 10kg。
- 重复确认同一 idempotency key 不重复入库/扣减。
- `TransferServiceImpl` ship/receive/confirm 的旧测试补 ledger 断言。
- `TransferDiffServiceImpl` 覆盖容差、部分签收、received > shipped、单位不可换算。

### 13.2 Web/RN E2E

- Web 列表能看到在途挂账、超差待判责、已关闭容差内差异。
- 仓库 RN 确认成品收货前，销售可用库存不包含该批次。
- 仓库 RN 确认后，成品仓可用增加，ledger 关闭或进入待判责。
- 生产 RN 分批领料后，批次成本按实际领用递增。
- 超差提交后前端展示责任待判定，不静默成功。
- 单位缺失时按钮禁用并显示具体 SKU/单位。

### 13.3 回归

- 现有普通调拨 create/approve/ship/receive/confirm 不破坏。
- 现有销售可用库存查询不把 TRANSFER/clearing 数量算入可售。
- 现有生产计划进度和质检创建仍在批次完成后执行。
- 历史 confirmed transfer 不因 backfill 产生二次库存事务。

## 14. N8 / 叮咚 external channel 命名核对

结论：叮咚必须作为 external channel 的样例数据/样例客户名处理，不应新增 Dingdong 专属导入、Dingdong 专属服务或 Dingdong 专属字段。

### 14.1 本轮不做

- 不做「叮咚文件导入 -> 自动 SO」。
- 不做 Dingdong-specific parser/importer。
- 不做客户名等于「叮咚」的硬编码业务分支。

### 14.2 已存在的正确方向

当前代码已经有通用字段：

- `SalesOrder.externalOrderTitle`
- `SalesOrderItem.destinationWarehouse`
- `SalesOrderItem.externalPurchaseOrderId`
- `SalesOrderItem.externalBarcode`
- `SalesOrderItem.appointmentTimeWindow`

这些应继续解释为 external channel purchase order metadata，而不是 Dingdong metadata。

### 14.3 命名缺口

后续实现 PR 应顺手修文档/注释命名，但本 spec-only PR 不改代码：

- `SalesOrder.externalOrderTitle` 的 Java 注释仍写「P3 多仓: 叮咚采购单标题」「普通订单 (非叮咚多仓场景)」。应改成「外部渠道采购单标题」「普通订单 (非外部渠道多仓场景)」。
- N9 交接里「叮咚订单免审」应实现为 external channel/customer policy exemption，例如 `approvalPolicy.externalChannelAutoApprove=true` 或基于客户配置，而不是 `customerName contains 叮咚`。
- 文档中可保留「叮咚」作为 F006 真实样例，但能力命名统一用「外部渠道 / external channel」。

建议命名：

| 不建议 | 建议 |
|---|---|
| `DingdongImportService` | `ExternalChannelOrderImportService` |
| `dingdongPurchaseOrderId` | `externalPurchaseOrderId` |
| `isDingdongOrder` | `isExternalChannelOrder` 或 policy-driven exemption |
| `DINGDONG_AUTO_APPROVE` | `EXTERNAL_CHANNEL_AUTO_APPROVE` |

## 15. 明确不在本 spec PR 实现

- 不改 `SupplyChainOrchestrator`、`TransferServiceImpl`、`TransferDiffServiceImpl` 代码。
- 不新增 migration SQL。
- 不新增 API controller/service。
- 不新增 Web/RN 页面。
- 不实现 N8 外部渠道文件导入。
- 不部署 prod/test。

## 16. Open Questions for Opus Gate

1. 生产报工的 `autoConsumeForBatch` 是否在 N10 v1 中一起改为「生产确认领用后消耗」，还是先只改成品入库挂账？
2. 容差是否只按绝对 10kg，还是需要「10kg 或 0.5% 取小/取大」的二级规则？
3. 成品仓收货少于生产报工时，是否允许实收部分先入可用，差额继续判责？本文建议允许。
4. 财务库存总账是否要把 TRANSFER 单列进资产库存，还是只作为业务挂账？本文建议单列。
5. 历史 FG-AUTO 是否只做 cutover 后新账本？本文建议不重开历史库存。
