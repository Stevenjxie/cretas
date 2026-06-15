# 六扇门 N10 中转仓完整挂账账本 Spec

**日期**: 2026-06-13  
**类型**: spec-only / 不写实现代码  
**状态**: 待 Opus gate 评审  
**范围红线**: 本文只定义库存事务、挂账账本、对账、Web/RN 操作流与验收面；本 PR 不新增 Java/TS/RN/Python 业务实现。

---

## 0. 阅读范围与版本化 Source Gap

本任务要求完整阅读 `docs/meetings/2026-06-12-xinluyin3/handoff-gpt.md` 的 N10 内容，并核对同目录 transcript / 需求分析。当前 `origin/main` worktree 中该目录不存在：

- `git ls-tree -r --name-only origin/main | rg "2026-06-12-xinluyin3|handoff-gpt|需求分析-organizer"` 无结果。
- `git log --all --oneline -- docs/meetings/2026-06-12-xinluyin3/handoff-gpt.md` 无结果。

但主目录 `C:\Users\Steve\my-prototype-logistics` 存在未跟踪的 `docs/meetings/2026-06-12-xinluyin3/`，已只读完整核对 `handoff-gpt.md`，并核对 `transcript.txt` 与 `需求分析-organizer.md` 的 N10 相关原话。因此本文的 source gap 仅指这些 6/12 来源尚未进入 `origin/main`，不是未读。

可核对来源如下：

- `docs/meetings/2026-06-12-xinluyin3/handoff-gpt.md:150-153` 明确 N10：成品结单送仓库需仓库确认收货才掉锅；中转仓挂账区分仓库少掉锅 vs 生产少产；原料同理；10kg 内称重误差不计；先写 spec 不写代码。
- `docs/meetings/2026-06-12-xinluyin3/transcript.txt:560-565` 记录调拨后挂账、生产和仓库都能看见、查过程里挂了多少公斤账。
- `docs/meetings/2026-06-12-xinluyin3/transcript.txt:1166-1171` 记录成品交给仓库后，仓库收到货才可以掉锅。
- `docs/meetings/2026-06-12-xinluyin3/transcript.txt:1199-1212` 记录库存偏差应挂在中转仓，用来区分仓库掉锅少了还是生产少了。
- `docs/meetings/2026-06-12-xinluyin3/transcript.txt:1219-1232` 记录原料同理、中间建库、生产从该库领走、10kg 内按称重误差不算。
- `docs/meetings/2026-06-12-xinluyin3/transcript.txt:1233-1238` 记录仓库称发 2 吨、生产只接 1 吨时，要追问剩余 1 吨是否仍在仓库。
- `docs/meetings/2026-06-12-xinluyin3/transcript.txt:1242-1253` 记录仓库可一次出 5 吨，生产分天领用，余额一直挂在仓里。
- `docs/meetings/2026-06-12-xinluyin3/需求分析-organizer.md:29,79` 记录 Steve 拍板范围是完整中转挂账账本，非轻量 handshake。
- `docs/dispatch/ACTIVE.md:31-37` 明确 N10 范围：中转仓完整挂账账本，收货确认掉锅、仓库/生产偏差、10kg 容差，且 N10 先 spec 不写码。
- `docs/meetings/2026-06-09-liushanmen/requirements-catalog.md:576-583` 记录生产领料调拨、双方独立计数、调拨差异异常。
- `docs/meetings/2026-06-09-liushanmen/需求与现状分析.md:427-429` 记录 F7 调拨现状与缺口。
- `docs/audits/liushanmen/2026-06-12-full-operation-flow.md:245-249` 汇总领料调拨操作流与出处。
- 现有代码证据见第 1 节。

如果 `2026-06-12-xinluyin3` handoff 后续进入 `origin/main` 时内容有变化，Opus gate 需要重新核对本 spec 的 N10 原话引用与容差口径。

## 1. 现状证据

### 1.1 业务/转录证据

| 证据 | 对 N10 的约束 |
|---|---|
| `handoff-gpt.md:150-153` | N10 必须覆盖成品仓库确认掉锅、仓库/生产偏差、原料同理、10kg 容差；本任务只出 spec。 |
| `transcript.txt:560-565` | 挂账需要让生产和仓库都可见，并能查调拨过程中挂了多少公斤。 |
| `transcript.txt:1166-1171` | 成品不能生产完自动掉锅，必须仓库收到货后才掉锅。 |
| `transcript.txt:1199-1212` | 偏差挂在中转仓，用于定位仓库少掉锅还是生产少产。 |
| `transcript.txt:1219-1232` | 原料同理，中间建库后生产从该库领用；10kg 内作为称重误差不计算。 |
| `transcript.txt:1233-1253` | 仓库声称发出与生产实际接收不一致时，要保留余额并支持分天领用。 |
| `需求分析-organizer.md:29,79` | Steve 拍板范围为完整中转挂账账本，非轻量 handshake。 |
| `ACTIVE.md:31-37` | N10 不是轻量签收，而是新增库存对账模型；必须覆盖收货确认掉锅、仓库/生产偏差、10kg 容差；spec-only。 |
| `requirements-catalog.md:576-579` | 生产领料需要调拨单，把原料从仓库调到工厂/车间，工厂端收到后核对接收，每步有责任人。 |
| `requirements-catalog.md:581-582` | 调拨「车间点车间数、仓库点仓库数，不互相核对」；发 34xx 实到 31xx 时要能找剩余差异。 |
| `需求与现状分析.md:427-429` | 调拨已有三段链，但缺少对独立计数、两仓一人接收卡死、纸质指示单、调拨差异异常的适配。 |
| `full-operation-flow.md:245-249` | 现场单点操作应走手机 App；领料统一批量发生，双方各自确认。 |
| `requirements-catalog.md:520, 601, 613-614` | 库存修改必须申请并经财务审批；系统库存要等于实物；报损用于防止库存永久挂账。 |

### 1.2 当前代码证据

| 模块 | 现状 | N10 缺口 |
|---|---|---|
| `SupplyChainOrchestrator.onBatchCompleted` | `backend/java/cretas-api/src/main/java/com/cretas/aims/service/orchestration/SupplyChainOrchestrator.java:244-261` 在批次完成后触发自动扣料和 `createFinishedGoodsFromBatch`。 | 成品完工后会直接生成可用成品批次，缺少「生产发出 -> 中转挂账 -> 仓库确认 -> 可用」中间态。 |
| `BatchConsumptionService.autoConsumeForBatch` | `BatchConsumptionServiceImpl.java:51-59` 把自动扣料从主事务隔离出来。 | 自动扣料仍表达为批次完成后的消耗，不表达生产分批领用与中转余额。 |
| `TransferServiceImpl` | `TransferServiceImpl.java:304-371` 已有 ship / receive / confirm。 | 只围绕调拨单生命周期，不是统一中转挂账账本；无法表达生产少产、生产少领、仓库少掉锅的责任归因。 |
| `TransferDiffServiceImpl` | `TransferDiffServiceImpl.java:50` 按 transfer 生成差异。 | 当前差异主要来自 shipped vs received，缺少 10kg 容差、单位标准化、分批领用、received > shipped、责任状态。 |
| `FactoryWarehouse.WarehouseType.TRANSFER` | `FactoryWarehouse.java:78-107` 已有 `TRANSFER` 类型。 | 只是仓库类型 marker，不是可查询、可结转、可判责的挂账账本。 |
| `WarehouseInventoryGuardService` | `WarehouseInventoryGuardService.java:91-101` 排除 `TRANSFER` 在途仓发起盘点。 | 已承认 TRANSFER 非实物盘点仓，但还没有独立 clearing/ledger 语义。 |
| `TransferController` | `TransferController.java:115-162` 暴露 ship / receive / confirm 端点。 | 缺少 ledger 列表、详情、部分确认、判责、重算、强关等端点。 |
| Web/RN | `web-admin/src/views/transfer/{list,detail}.vue` 与 `frontend/CretasFoodTrace/src/...WHInventoryTransferScreen.tsx` 已有调拨入口。 | 缺少「中转挂账/在途对账」列表、RN 待收/待领入口、超差显式提示与单位缺失修复入口。 |

## 2. 设计目标

1. 引入中转仓完整挂账账本，让库存责任从源仓/生产发出到目标仓/生产领用之间有可查询、可对账、可归因的中间态。
2. 成品与原料走同一账本模型：
   - 源头确认出库、掉锅或生产报工后，数量进入 `TRANSFER/clearing`。
   - 目标方确认收货或生产确认领用后，才进入目标可用库存或生产消耗。
   - 未确认差额留在账本中，不静默关单。
3. 明确区分仓库少掉锅、生产少产、生产少领、在途损耗、称重误差。
4. 默认支持 F006 10kg 重量容差，后续可按 factory / SKU / 单位类型覆盖。
5. Web/RN 必须 surface 下一步，不允许只在后端生成半成品状态。

## 3. 业务流程

### 3.1 成品：生产完工到成品仓

1. 生产报工/结单：
   - 生产侧记录良品数量、批次、SKU、单位、操作人、称重方式。
   - 系统创建 `FG_PRODUCTION_TO_WAREHOUSE` ledger。
   - 数量进入中转挂账，不进入成品仓可用库存，不参与销售可发量。
2. 仓库确认收货：
   - 仓库 RN 或 Web 输入实收数量，可附照片/备注。
   - 实收部分进入成品仓可用。
   - 差异绝对值小于等于容差时标记 `WITHIN_TOLERANCE` 并关闭。
   - 差异超过容差时，差额保持 `VARIANCE_PENDING`，需要判责。
3. 判责/处理：
   - 生产发出少于计划且仓库实收等于生产发出：生产少产。
   - 生产发出大于仓库实收：仓库少掉锅或在途损耗，需要仓库/生产主管判定。
   - 处理方式：补发、报损、成本调整、库存调整、强制关闭。

### 3.2 原料：仓库掉锅到生产领用

1. 仓库发料/掉锅：
   - 源仓可用库存扣减。
   - 创建或推进 `RM_WAREHOUSE_TO_PRODUCTION` ledger。
   - 生产未确认领用前，不计入生产消耗，不摊入批次成本。
2. 生产领用：
   - 生产可按天、按批次、按工序分批确认。
   - 领用数量从中转余额转入生产消耗。
   - 批次成本按实际确认领用数量递增。
3. 挂账保持：
   - 仓库一次发 5 吨，生产分 1 + 2 + 2 吨领用，中转余额逐步递减。
   - 生产长期不领或少领，进入超 SLA 待处理，不自动冲掉库存。

### 3.3 跨仓/跨厂调拨

现有 `shipTransfer -> receiveTransfer -> confirmTransfer` 保留，但接入 ledger：

- `shipTransfer`：源仓扣可用，写入 `source_issued_quantity`，状态进入 `SOURCE_POSTED/IN_TRANSIT`。
- `receiveTransfer`：目标侧写 `target_received_quantity`，支持部分签收。
- `confirmTransfer`：目标仓入可用前检查 ledger 状态、单位换算和容差。
- 差异生成从 transfer item 比较升级为 ledger variance 视图。

## 4. 角色 Web/RN 操作流

| 角色 | 端 | 操作 | 需要看到的信息 | 可执行动作 |
|---|---|---|---|---|
| 仓管员 | RN | 成品待收货、原料发料/掉锅、调拨发货/签收 | 单号、SKU、批次、计划/源发数量、容差、目标仓/生产批次、中转余额 | 确认发出、确认实收、填写异常原因、上传照片 |
| 生产操作员/组长 | RN | 原料待领用、分批领用确认 | 生产计划、工序、SKU、源发数量、中转余额、已领数量 | 分批确认领用、异常备注 |
| 仓库主管 | Web | 在途对账、超差初判、补发/报损入口 | 按仓库、SKU、账龄、责任状态过滤的列表与事件时间线 | 判定仓库责任、发起补发/报损/调整 |
| 生产主管 | Web | 生产少产/少领判责 | 批次应产、报工良品、领用记录、未领余额 | 判定生产责任、要求补报工/补领/报损 |
| 财务 | Web | 库存总账、容差内审计、超差财务影响 | 可用库存、在途/clearing、报损/调整结果、金额影响 | 查看、审批报损/库存调整、月结阻断确认 |
| 管理层 | Web | 只读库存与异常看板 | 实物仓库存、在途挂账、超差账龄、责任分布 | 只读，不直接改库存 |

端边界：

- RN 只做现场单点动作：扫码/点单、数量确认、照片、异常原因。
- Web 做复杂列表、筛选、判责、财务审批、月结阻断。
- 同一动作必须有幂等键，弱网重试不重复扣库或入库。

## 5. 防呆规则

1. 中转仓不是可售/可领可用仓：
   - `TRANSFER/clearing` 数量不进入销售可发量。
   - 原料未由生产确认领用前，不进入批次消耗。
2. 数量输入：
   - 默认不允许确认数量大于中转余额。
   - 如业务允许超收，必须选择异常原因并生成差异事件。
3. 容差：
   - 默认只对重量类单位生效。
   - 先标准化到 kg，再判断 10kg。
   - 容差按 ledger line 判断，不允许整单多行互相抵消。
4. 单位缺失：
   - 单位无法换算时返回 `UNIT_CONVERSION_REQUIRED`。
   - Web/RN 禁用确认按钮，并显示具体 SKU、当前单位、修复入口。
5. 超差提示：
   - 提交成功但超差时，前端显示「已确认，差异待判责」，并给出待办入口。
   - 差异评估失败时返回可重试状态，不允许静默成功。
6. 权限：
   - 仓管只能执行现场确认，不得裸改库存。
   - 判责/强关需要主管权限。
   - 报损、库存调整、月结影响需要财务审批。

## 6. 账务/库存边界

### 6.1 库存口径

| 口径 | 是否包含中转挂账 | 说明 |
|---|---|---|
| 销售可发库存 | 否 | 只看成品仓可用，防止未收货成品被销售出库。 |
| 生产可领库存 | 否 | 生产只能从待领 ledger 领用，不能直接吃源仓可用。 |
| 实物仓盘点 | 否 | `TRANSFER/clearing` 不参与实物仓盘点。 |
| 财务库存资产 | 建议单列包含 | 在途/挂账仍可能是企业资产，但必须单列为 clearing，不混入可用仓。 |
| 月结/进销存 | 单列展示 | 期初/期入/期出/期末要能解释 clearing 余额，避免库存与财务报表断裂。 |

### 6.2 库存事务

所有实现必须落不可变库存事务或事件，不允许只更新余额：

- source available -> transfer clearing
- transfer clearing -> target available
- transfer clearing -> production consumed
- transfer clearing -> scrap/loss
- transfer clearing -> source returned
- transfer clearing -> cost adjustment

每笔事务至少带：

- `ledger_line_id`
- `business_ref_type`
- `business_ref_id`
- `from_warehouse_id`
- `to_warehouse_id`
- `quantity`
- `unit`
- `standard_quantity_kg`
- `idempotency_key`
- `operator_id`
- `occurred_at`

### 6.3 财务影响

- 容差内差异：保留审计，不生成待处理追责，不阻断关单。
- 超差未判责：保留在 `clearing`，阻断该业务单最终关闭；月结时进入异常清单。
- 仓库责任：走仓库报损/库存调整/财务审批。
- 生产责任：进入生产损耗或成本调整，按既有报损/审批链处理。
- 在途责任：进入运输损耗或管理调整，必须有备注与审批。

## 7. API / DB 草案

### 7.1 DB 草案

`inventory_transfer_ledger`

| 字段 | 说明 |
|---|---|
| `id` | UUID |
| `factory_id` | 工厂 |
| `ledger_no` | 账本单号 |
| `ledger_type` | `FG_PRODUCTION_TO_WAREHOUSE` / `RM_WAREHOUSE_TO_PRODUCTION` / `WAREHOUSE_TO_WAREHOUSE` / `RETURN_OR_REVERSAL` |
| `source_ref_type`, `source_ref_id` | 来源业务，如 production_batch / internal_transfer |
| `target_ref_type`, `target_ref_id` | 目标业务，如 warehouse_receive / production_consumption |
| `source_warehouse_id`, `target_warehouse_id` | 源/目标仓，可空但须由业务类型约束 |
| `status` | 主单状态 |
| `responsibility_status` | `NONE` / `PENDING` / `WAREHOUSE` / `PRODUCTION` / `TRANSIT` / `MIXED` |
| `tolerance_quantity`, `tolerance_unit` | 容差快照 |
| `created_by`, `created_at`, `updated_at`, `closed_at` | 审计 |

`inventory_transfer_ledger_line`

| 字段 | 说明 |
|---|---|
| `id`, `ledger_id` | 主键与主单 |
| `material_type_id` / `product_type_id` | 原料或成品 |
| `batch_id` | 源批次或生产批次 |
| `planned_quantity` | 计划应交/应领 |
| `source_issued_quantity` | 源头已发/已报工 |
| `target_received_quantity` | 目标仓实收 |
| `production_consumed_quantity` | 生产实领/消耗 |
| `available_posted_quantity` | 已进可用或已进消耗 |
| `clearing_balance_quantity` | 当前中转余额 |
| `variance_quantity` | 标准化后的差异 |
| `variance_effective_quantity` | 超容差后的差异 |
| `unit`, `standard_unit`, `standard_quantity` | 单位快照 |
| `line_status` | 行状态 |

`inventory_transfer_ledger_event`

- `ledger_id`, `ledger_line_id`, `event_type`, `from_status`, `to_status`, `payload_json`, `operator_id`, `occurred_at`, `idempotency_key`

`inventory_transfer_variance`

- `ledger_id`, `ledger_line_id`, `variance_type`, `responsibility`, `decision`, `decision_reason`, `evidence_urls`, `related_adjustment_id`, `approved_by`, `approved_at`

迁移命名由 Opus gate 按当前 Flyway 最高版本重新分配，本文不预占版本号。

### 7.2 API 草案

| API | 用途 |
|---|---|
| `GET /inventory/transfer-ledgers` | 按状态、类型、责任、源/目标仓、账龄查询挂账 |
| `GET /inventory/transfer-ledgers/{id}` | 主单详情、行、事件、差异、下一步动作 |
| `POST /inventory/transfer-ledgers/{id}/source-post` | 源头确认发出，通常由业务服务调用 |
| `POST /inventory/transfer-ledgers/{id}/target-confirm` | 仓库收货或生产领用确认，支持部分数量 |
| `POST /inventory/transfer-ledgers/{id}/variance/{lineId}/decide` | 人工判责 |
| `POST /inventory/transfer-ledgers/{id}/adjust` | 补发、报损、库存调整、强关 |
| `POST /inventory/transfer-ledgers/{id}/re-evaluate` | 修单位/配置后重算差异 |
| `GET /inventory/transfer-ledgers/summary` | Web 看板：待确认、超差、超 SLA、容差内关闭 |

错误码：

| Code | 场景 |
|---|---|
| `409 LEDGER_STATE_CONFLICT` | 当前状态不允许动作，返回当前状态和下一步 |
| `422 UNIT_CONVERSION_REQUIRED` | 无法换算到 kg |
| `422 QUANTITY_EXCEEDS_CLEARING_BALANCE` | 确认数量超过中转余额 |
| `422 TOLERANCE_CONFIG_MISSING` | 重量类缺容差配置 |
| `422 RESPONSIBILITY_REQUIRED` | 超差强关/调整缺责任类型 |
| `500 VARIANCE_EVALUATION_FAILED` | 主动作成功但差异评估失败，需要可重试 |

## 8. 迁移注意事项

1. Feature flag：
   - `inventory.transferLedger.enabled=false` 默认关闭。
   - 先在 F006 staging 开启，再按 factory 灰度。
2. 历史数据：
   - 已 `CONFIRMED` 的历史调拨只 backfill closed ledger event，用于审计，不重开库存事务。
   - `SHIPPED/RECEIVED` 未确认调拨可 backfill active ledger。
   - 历史自动成品入库不回滚，从 cutover 后新批次开始走 ledger。
3. 兼容：
   - 保留现有 `InternalTransfer` 状态机和 API。
   - `TransferDiffRecord` 可加 nullable `ledger_id/ledger_line_id`，旧查询继续可用。
   - 财务凭证生成要明确是基于旧 `INTERNAL_TRANSFER` 还是新 ledger event，避免双记。
4. 回滚：
   - flag 关闭后新单回旧流程。
   - 已生成 ledger 不删除，只停止自动推进，人工处理余额。
5. 性能：
   - ledger 列表默认按 status + factory + updated_at 分页。
   - line 表按 `ledger_id`、`factory_id + line_status`、`source_ref` 建索引。
6. 权限与审计：
   - 所有判责、强关、调整必须有原因、操作者、时间、前后状态。
   - 低权限角色不得查看金额字段，遵守现有财务 RBAC fail-closed。

## 9. E2E 验收用例

| ID | 用例 | 验收点 |
|---|---|---|
| N10-E2E-01 | 成品报工 119kg，仓库收 115kg | 4kg 差异在 10kg 容差内；成品仓可用只增加 115kg；ledger 关闭且保留容差事件。 |
| N10-E2E-02 | 成品报工 2000kg，仓库收 1000kg | 1000kg 入可用，1000kg 留 `VARIANCE_PENDING`；Web 超差待判责可见；销售可发量不含未收部分。 |
| N10-E2E-03 | 原料仓发 5 吨，生产分 1+2+2 吨领用 | 中转余额 5 -> 4 -> 2 -> 0；批次成本按实领递增；未领期间不进生产消耗。 |
| N10-E2E-04 | 原料仓发 2 吨，生产只确认 1 吨 | 差额 1 吨超容差；进入待判责；RN 显示「差异待判责」而非单纯成功。 |
| N10-E2E-05 | 单位无法从箱换算 kg | 确认按钮禁用或 API 返回 `UNIT_CONVERSION_REQUIRED`；Web/RN 显示 SKU 与修复入口。 |
| N10-E2E-06 | 弱网重复提交同一确认 | 同一 idempotency key 不重复入库、不重复扣库；事件只记录一次有效状态推进。 |
| N10-E2E-07 | 现有普通调拨发货、签收、确认 | 旧流程仍可完成；ledger 同步生成；旧 transfer 列表不破坏。 |
| N10-E2E-08 | 超差人工判责为仓库责任并报损 | variance 关联报损/调整；财务审批后 closing；库存总账 clearing 余额归零。 |
| N10-E2E-09 | 月结前存在超 SLA 未判责挂账 | 财务看板显示异常清单；月结/进销存能单列 clearing，不混入可用仓。 |
| N10-E2E-10 | 低权限仓管访问金额字段 | 数量可见，金额隐藏；判责/强关按钮不可见或 403。 |

## 10. 非目标

- 不在本 PR 修改 `SupplyChainOrchestrator`、`TransferServiceImpl`、`TransferDiffServiceImpl` 或任何 Java 代码。
- 不新增 Flyway migration、Entity、Repository、Controller、Service。
- 不新增 Web/RN 页面或路由。
- 不部署 prod/test。
- 不实现 N8 叮咚/外部渠道文件导入。
- 不重算历史自动成品入库成本。
- 不替代现有报损、盘点、财务审批模块；N10 只定义它们与中转挂账的衔接边界。

## 11. Open Questions for Opus Gate

1. `autoConsumeForBatch` 是否在 N10 v1 一起迁移为「生产确认领用后消耗」，还是先只改成品入库挂账？
2. 容差是否固定 10kg，还是使用「10kg 或百分比阈值」的组合规则？
3. 成品收货少于生产报工时，是否允许实收部分先入可用，差额继续判责？本文建议允许。
4. 财务库存总账是否把 `TRANSFER/clearing` 单列进资产库存？本文建议单列。
5. 两仓一人场景如何避免接收卡死：是否允许同人双动作但强制二次确认与审计？
