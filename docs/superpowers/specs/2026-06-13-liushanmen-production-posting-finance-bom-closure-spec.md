# 六扇门生产结单过账 + 财务/BOM 成本闭环方案

> 生成: 2026-06-13 Codex superpowers audit  
> 范围: 6.9 与 6.12 转录中关于生产、财务、BOM、半成品、中转仓的二次核对与实施方案  
> 输出性质: 方案/spec。本文不改业务代码，不部署。  
> 当前基线: N1a 已完成 PR #793；生产结单事实 API 在 PR #812，web 提交流在 PR #811；N10 中转挂账 spec 在 PR #817。

---

## 1. 结论

当前系统已经补上了“文员结单事实记录”的入口，但还没有完成“结单以后把事实变成库存、成本、财务依据”的过账闭环。

6.12 的关键意思不是“生产计划完成就自动扣库存、自动入成品仓”。客户明确要求：

1. 计划下达后直接进入未完成列表，文员逐单核对。
2. 结单时录入实际产量、实际领用、人效/工时，BOM 只作为参考值。
3. 成本必须按实际领用计算，不能按计划/BOM 伪造。
4. 成品不能由生产直接掉锅给仓库，必须仓库确认收到以后才落仓。
5. 原料和成品都需要中转仓/挂账责任归属，10kg 内称重误差可忽略，超过要明确是仓库少发/少收还是生产少产/少接。
6. 半成品不是“现场滚动扫码领用”。6.9 已明确现场做不到。半成品保留重量库存，结单时由文员按实际单据补录领用，成本按移动均价或当前库存价参与闭环。

因此下一步不是再做一个普通完成按钮，而是补一个 `ProductionSettlementPosting` 过账层：从 #812 的结单事实出发，预览差异，执行原料/半成品出库、成品待收、成本计算、中转挂账，最后给财务和管理层可查的成本/库存依据。

---

## 2. 证据索引

### 2.1 6.12 转录

| 证据 | 文件位置 | 结论 |
|---|---|---|
| “生产领料重量、半成品重量、成品数量、辅料重量都是生产填，根据实际消耗为准” | `docs/meetings/2026-06-12-xinluyin3/transcript.txt:671` | 结单字段必须覆盖原料、半成品、辅料、成品实际数 |
| “BOM 只作为一个参考，生产会根据实际的领量去填” | `transcript.txt:680-688` 附近 | BOM 是计划/参考值，不是实际成本硬扣依据 |
| “计划下达以后直接推过去；去完成里面一个一个勾” | `transcript.txt:688-721` | N1b 的未完成列表流程是正确方向 |
| “人效/工时/计划单量/产量全部完成，这个单子就截掉” | `transcript.txt:761-772` | 系统完成计划的条件是产量、领用、人效全部核对 |
| “实际领用就是... 我实际上可能领 30.15” | `transcript.txt:1001-1009` | 实际领用允许偏离 BOM，偏离进入成本 |
| “结单的时候再去填实际领了多少” | `transcript.txt:1035-1058` | 实际领用录入发生在单据做完后的文员结单阶段 |
| “成本肯定是根据我实际用量” | `transcript.txt:1078-1083` | 财务实际成本口径必须来自结单实际数 |
| “仓库他一人收到货才可以掉锅” | `transcript.txt:1168` | 成品入库必须仓库确认，不能生产完成自动入仓 |
| “挂在中转仓，就可以找是仓库掉锅少了还是生产少了” | `transcript.txt:1202-1211` | 中转仓/挂账用于责任归属 |
| “两边要合对，原料一样” | `transcript.txt:1217-1224` | 原料和成品都要双边确认 |
| “十公斤以内...称重误差...不去计算” | `transcript.txt:1227-1230` | 10kg 误差容忍应配置化 |

### 2.2 6.9 转录

| 证据 | 文件位置 | 结论 |
|---|---|---|
| “报名/BOM 是固定的；人工需要实时修改；出成率主动” | `docs/meetings/2026-06-09-liushanmen/transcript.txt:0-65` | BOM 标准值和实际人效/出成率分层 |
| “现场不是仓库，半成品也领不了” | `transcript.txt:120-145` | 不设计现场滚动半成品领用 |
| “半成品库存模块应该留...半成品库存” | `transcript.txt:146-149` | 需要半成品库存 |
| “半成品本身只做重量库存，不做批次库存” | `transcript.txt:156` | 半成品主流程按重量库存，不强制批次追踪 |
| “操作越少越好” | `docs/meetings/2026-06-09-liushanmen/transcript-2b.txt:3809` | UI 必须少步骤、自动带出、只让关键角色操作 |
| “自动关联...产品类型都可以自动关联上” | `transcript-2b.txt:3830-3849` | BOM/编码/计划应自动带出，减少手填 |
| “最终只会知道每一盒多少钱人工” | `transcript-2b.txt:3894` | 人工可折盒，不追溯到过细环节 |
| “每一盒的包材、土料、BOM 成本” | `transcript-2b.txt:3977` | 财务关注包材、土料、BOM 成本并入每盒口径 |
| “库存数量对的，再盘生产单价是对的，就没问题” | `transcript-2b.txt:3329-3333` | 财务闭环先保证库存数量和生产单价正确 |

### 2.3 已有设计和代码

| 证据 | 位置 | 结论 |
|---|---|---|
| 结单 API 把过账状态置为 `PENDING_POSTING` | `ProductionPlanServiceImpl.java:1326-1327` in PR #812 worktree | 事实记录与过账已被刻意分离 |
| 结单校验要求实际产量、实际领用、人效/延期原因 | `ProductionPlanServiceImpl.java:1364-1389` in PR #812 worktree | #812 已符合“先记录事实”的红线 |
| 6.9 蓝图有 `SemiFinishedInventoryTransaction` 支撑移动均价/撤回/盘点 | `docs/superpowers/specs/2026-06-09-liushanmen/00-master-blueprint.md:43` | 半成品流水账是过账基础 |
| 移动均价成本引擎要求投入成本 = 原料批次价 + 上游半成品 unit_cost + 人工 + 调料 | `00-master-blueprint.md:87-88` | 成本口径已有方向 |
| 仓库无自主改库存权 | `00-master-blueprint.md:101` 和 `SP7-warehouse-control-spec.md:399` | 过账必须基于单据来源和权限 |
| 进销存报表依赖库存流水 | `SP11-finance-voucher-export-spec.md:29` | 财务报表要吃过账结果 |

---

## 3. 业务流程修正版

### 3.1 正常生产主流程

```
销售订单
  -> 财审通过
  -> 生产计划下达
  -> 计划直接进入“未完成”
  -> 打印生产工单，带 BOM 参考用量
  -> 现场按纸单领料/生产/签字
  -> 文员结单录入:
       实际成品产量
       实际半成品产量
       实际原料领用
       实际半成品领用
       实际辅料/包材领用
       人效/工时，或延期原因
  -> 生成结单事实，状态 PENDING_POSTING
  -> 过账预览
  -> 仓库/生产双边确认
  -> 过账:
       原料出库
       半成品出库或入库
       成品待收/收货入仓
       中转差异挂账
       实际成本计算
  -> 财务查看成本拆分和进销存依据
```

### 3.2 BOM 的定位

BOM 是“标准参考值”和“计划领料建议”，不是最终实际成本。

使用方式：

1. 计划下达时按 BOM 展示应领原料、辅料、半成品参考量。
2. 打印工单时显示 BOM 参考值，方便现场领料。
3. 结单时显示“BOM 参考 vs 实际领用”的差异。
4. 实际成本按文员录入的实际领用计算。
5. 差异超阈值时给预警和原因下拉，不强行篡改 BOM。
6. 如果长期差异明显，给“去维护 BOM/工艺配置”的快捷入口。

### 3.3 半成品的定位

半成品主流程不是现场扫码滚动领用，而是：

1. 半成品库存按重量库存管理，允许用 `semiFinishedCode` 区分焯水猪蹄、熟制猪蹄等状态。
2. 生产结单录入“本单实际领用哪个半成品、多少重量”。
3. 如果有明确 `SemiFinishedInventory` 行，则按该行移动均价出库。
4. 如果客户现场只能按汇总重量盘点，则允许从聚合半成品库存出账，但必须记录原因和责任人。
5. 半成品产出也通过结单进入库存，不要求每个现场动作实时入库。

### 3.4 二次加工的定位

6.9 的二次加工/跨单领半成品设计仍有价值，但不是本次生产主流程的默认动作。

本方案建议：

- 正常半成品领用: 放在生产结单实际领用中处理。
- 二次加工/返工/重工: 保留独立生产单，走 `planSourceType=SECONDARY`，用于“把已入半成品库的东西重新加工成另一个产物”的异常/专项流程。
- 不把二次加工混进每张正常生产单，否则 UI 和责任归属会过重。

---

## 4. 角色和端使用逻辑

### 4.1 Web Admin

Web 是主闭环端，适合文员、仓库、财务、管理层。

| 角色 | 主屏 | 可做 | 不应做 |
|---|---|---|---|
| 生产文员 | 生产计划未完成列表、结单页 | 核对纸单，录入实际产量、领用、人效，提交结单 | 直接改库存账 |
| 仓库 | 待收/待发确认台、中转差异台 | 确认实际发料、实际收成品、处理差异责任 | 无单据自主调库存 |
| 财务 | 成本过账预览、成本拆分、进销存报表 | 查看标准/实际/销售三价，审查异常成本，导出凭证/台账 | 手工补假成本 |
| 厂长/管理层 | 异常看板、未完成计划、库存差异 | 看预警，追责，跳转到对应单据 | 承担大量录入 |

必须补齐的快捷按钮：

| 所在界面 | 条件 | 快捷按钮 |
|---|---|---|
| 生产计划未完成列表 | 状态 `PENDING` 或 `IN_PROGRESS` | `核对结单` |
| 结单成功弹窗 | `postingStatus=PENDING_POSTING` | `去过账预览` |
| 过账预览 | 有成品待收 | `通知仓库确认收货` |
| 过账预览 | 有原料/半成品差异 | `查看中转挂账` |
| 成本预警 | BOM 与实际差异超阈值 | `去维护 BOM`、`查看领用明细` |
| 财务成本页 | 成本缺人工或缺价格 | `去补人效`、`去补采购价` |

### 4.2 RN

RN 只做低输入动作，不能让现场背完整 ERP。

建议保留：

1. 查看今日生产任务。
2. 录入或补录简单人效片段。
3. 拍照/签字/留痕。
4. 仓库确认发料/收货。
5. 提交异常原因。

不建议 RN 做：

1. 复杂 BOM 编辑。
2. 复杂成本过账。
3. 多人同时改同一张结单。
4. 现场滚动扣半成品库存。

---

## 5. 后端设计

### 5.1 新服务

```
ProductionSettlementPostingService
  preview(factoryId, settlementId)
  post(factoryId, settlementId, idempotencyKey, actorUserId)
  cancelPosting(factoryId, settlementId, reason, actorUserId)

ProductionSettlementCostService
  calculateStandardCost(planId, bomSnapshot)
  calculateActualMaterialCost(settlementConsumptions)
  calculateActualSemiFinishedCost(settlementConsumptions)
  calculateActualLaborCost(settlementLabor)
  buildCostBreakdown(settlementId)

ProductionTransferClearingService
  createOrUpdateClearingLedger(settlementId, movementType, expectedQty, senderQty, receiverQty)
  autoCloseWithinTolerance(ledgerId)
  resolveLedger(ledgerId, responsibleSide, reason, actorUserId)
```

### 5.2 关键表建议

`production_settlement_postings`

| 字段 | 说明 |
|---|---|
| id | PK |
| factory_id | 工厂 |
| settlement_id | 对应 #812 结单事实 |
| posting_no | 过账单号 |
| status | DRAFT/PREVIEWED/WAITING_WAREHOUSE/POSTED/FAILED/CANCELLED |
| idempotency_key | 幂等 |
| standard_cost_total | BOM 标准总成本 |
| actual_material_cost | 实际原料/辅料/包材成本 |
| actual_semi_finished_cost | 实际半成品成本 |
| actual_labor_cost | 实际人工成本 |
| actual_total_cost | 实际总成本 |
| cost_status | COMPLETE/PENDING_PRICE/PENDING_LABOR/PENDING_WAREHOUSE |
| posting_message | 人读状态 |
| posted_by/posted_at | 过账人/时间 |

`production_settlement_posting_lines`

| 字段 | 说明 |
|---|---|
| posting_id | 过账单 |
| line_type | RAW_OUT/SEMI_OUT/SEMI_IN/FINISHED_PENDING/FINISHED_IN/LABOR/CLEARING |
| source_ref_type/source_ref_id | MaterialBatch/SemiFinishedInventory/FinishedGoodsBatch/ClearingLedger |
| quantity/unit | 数量 |
| unit_cost/line_cost | 单价/金额，缺价为 null |
| cost_status | COMPLETE/MISSING_PRICE/DEFERRED |
| message | 缺价、差异、自动关闭说明 |

`production_transfer_clearing_ledgers`

可对齐 PR #817 N10 spec。核心字段：

| 字段 | 说明 |
|---|---|
| source_doc_type/source_doc_id | PRODUCTION_SETTLEMENT/TRANSFER/RECEIPT |
| movement_type | RAW_TO_PRODUCTION/SEMI_TO_PRODUCTION/FINISHED_TO_WAREHOUSE |
| expected_qty | BOM 或计划参考量 |
| sender_confirmed_qty | 仓库发出或生产交出 |
| receiver_confirmed_qty | 生产接收或仓库收货 |
| tolerance_qty | 默认 10kg，可配置 |
| variance_qty | 差异 |
| responsible_side | WAREHOUSE/PRODUCTION/UNKNOWN/AUTO_TOLERANCE |
| status | OPEN/AUTO_CLOSED/RESOLVED |

### 5.3 状态机

```
ProductionSettlement
  PENDING_POSTING
    -> POSTING_PREVIEWED
    -> WAITING_WAREHOUSE_CONFIRMATION
    -> POSTED
    -> POSTING_FAILED

Posting
  DRAFT
    -> PREVIEWED
    -> WAITING_WAREHOUSE
    -> POSTED
    -> FAILED
    -> CANCELLED
```

### 5.4 过账原则

1. 预览不写库存，只计算边界、差异、缺价、缺人效。
2. 正式过账必须幂等。重复提交返回既有 posting。
3. 原料/辅料实际出库按结单行扣 `MaterialBatch`，不足返回 409，提示可用量。
4. 半成品实际出库按 `SemiFinishedInventory` 扣数量，并写 transaction。
5. 半成品产出按移动均价入 `SemiFinishedInventory`。
6. 成品产出先生成待收，不直接进入仓库可用库存。
7. 仓库确认收货后才写 `FinishedGoodsBatch` 可用库存。
8. 成本缺价格或缺人效时 `costStatus` 不能 COMPLETE，前端显示“待补价/待补人效”，不显示 0。
9. 10kg 内差异可自动关闭，超过写中转挂账，明确责任归属。
10. 所有库存动作写 audit，保留 before/after。

---

## 6. 成本口径

### 6.1 标准成本

来源：

- 当前计划绑定的 BOM 版本或结单时冻结的 BOM snapshot。
- `BomRecipeItem.standardQuantity`、`yieldRate`、`perPortion`、包材规格、半成品引用。
- 未税价优先，含税价必须按税率换算。

用途：

- 计划领料参考。
- 工单打印参考。
- 实际偏差对比。
- 销售毛利红线的稳定基准。

### 6.2 实际成本

来源：

- 原料/辅料/包材: 结单实际领用数量 × 批次未税单价或移动均价。
- 半成品: 结单实际领用数量 × `SemiFinishedInventory.unitCost` 或 transaction 快照成本。
- 人工: 结单 laborSegments 中的 `minutes × headcount × hourlyRate`，或直接录入 laborCost。
- 水电折旧: 当前 deferred，不写假数。未来 D1 再接。

缺失处理：

| 缺失 | 后端状态 | 前端显示 |
|---|---|---|
| 批次单价缺失 | PENDING_PRICE | “缺采购价，无法完成实际成本” |
| 半成品 unitCost 缺失 | PENDING_SEMI_COST | “半成品成本缺失，需先补上游结单” |
| 人效延期 | PENDING_LABOR | “人效稍后补录，成本暂未闭合” |
| 仓库未确认收货 | PENDING_WAREHOUSE | “待仓库确认收货” |

### 6.3 财务可见结果

财务页至少要并排展示：

1. BOM 标准成本。
2. 实际材料成本。
3. 实际半成品成本。
4. 实际人工成本。
5. 实际总成本。
6. 销售价。
7. 差异金额和差异百分比。
8. 缺失项和下一步按钮。

---

## 7. 防呆和便捷性

### 7.1 写操作必须满足

| 规则 | 本方案落点 |
|---|---|
| Rule 1 预显边界 | 结单/过账页显示可用库存、BOM 参考、已确认数量、最大可扣量 |
| Rule 2 dialog 带上下文 | 过账确认必须展示计划号、产品名、成品数、主要差异 |
| Rule 3 原因 dropdown | 超领、少领、超产、少产、缺人效、缺价都用下拉原因 |
| Rule 4 幂等查重 409 | settlementId + idempotencyKey；postingId 二次提交返回既有结果 |
| Rule 5 dead-end 跳转 | 缺价跳采购批次，缺人效跳人效补录，仓库未收跳待收确认 |

### 7.2 UI 减负设计

1. 进入结单页自动带出 BOM 参考行，不让文员从空白开始。
2. 原料、辅料、半成品分组展示，默认折叠已无差异行。
3. 实际领用默认等于 BOM 参考量，文员只改偏差行。
4. 半成品 picker 只显示有可用量的半成品。
5. 超过库存直接 disable 提交，展示最大可用量。
6. 过账预览用红黄绿状态，不要求财务读日志。
7. 每个状态都给下一步按钮，不出现“完成了但不知道去哪”的 dead-end。

---

## 8. API 草案

所有响应保持 `{ success, data, message }`。

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/mobile/{factoryId}/production-settlements/{settlementId}/posting-preview` | 过账预览 |
| POST | `/api/mobile/{factoryId}/production-settlements/{settlementId}/post` | 正式过账 |
| GET | `/api/mobile/{factoryId}/production-settlements/{settlementId}/cost-breakdown` | 成本拆分 |
| POST | `/api/mobile/{factoryId}/production-settlements/{settlementId}/warehouse-confirm` | 仓库确认成品收货 |
| GET | `/api/mobile/{factoryId}/production-clearing-ledgers` | 中转挂账列表 |
| POST | `/api/mobile/{factoryId}/production-clearing-ledgers/{id}/resolve` | 差异处理 |
| GET | `/api/mobile/{factoryId}/bom/recipes/{recipeId}/issue-reference` | 工单/BOM 推荐领料 |

---

## 9. Web 页面草案

### 9.1 生产计划列表

新增/修正列：

- 状态底色：未完成、待过账、待仓库确认、已完成。
- 产量完成：计划量 vs 实际量。
- 领用完成：是否已录原料/半成品/辅料。
- 人效完成：已录/延期/缺失。
- 过账状态：PENDING_POSTING/POSTED/PENDING_WAREHOUSE。

动作：

- `核对结单`
- `过账预览`
- `去仓库确认`
- `查看成本`
- `查看中转挂账`

### 9.2 结单页

布局：

1. 顶部: 计划号、销售订单号、产品、计划量、生产日期。
2. 左侧: BOM 参考领料。
3. 右侧: 实际领用录入。
4. 底部: 人效/工时、差异原因、提交按钮。

### 9.3 过账预览页

分为四张表：

1. 原料/辅料扣减预览。
2. 半成品扣减/产出预览。
3. 成品待收/入库预览。
4. 成本拆分与缺失项。

### 9.4 财务成本页

展示：

- 三价：标准成本、实际成本、销售价。
- 每盒口径：材料、半成品、人工、包材。
- 差异预警。
- 缺失项快捷跳转。

---

## 10. E2E 验收

### 10.1 生产主链路

1. 创建财审通过的销售订单。
2. 下达生产计划。
3. 计划直接进入未完成列表。
4. 打开结单页，BOM 参考自动带出。
5. 修改实际原料、半成品、辅料领用。
6. 填实际成品数量和人效。
7. 提交结单。
8. 看到 `PENDING_POSTING` 和 `去过账预览`。
9. 打开过账预览，确认库存边界、成本拆分、差异。
10. 仓库确认收货。
11. 正式过账。
12. 回到计划列表，状态为已完成/已过账。
13. 财务成本页能看到实际成本，不是 0 或 mock。

### 10.2 防呆场景

| 场景 | 预期 |
|---|---|
| 实际领用超过可用库存 | 提交 disabled 或后端 409，sticky 显示最大可用量 |
| 没填实际领用 | 后端 400 “必须录入实际领用明细” |
| 没填人效 | 后端 400 或选择延期原因 |
| 结单重复提交 | 返回既有 settlement，不重复写 |
| 过账重复提交 | 返回既有 posting，不重复扣库存 |
| 仓库收货差异 8kg | 自动容差关闭 |
| 仓库收货差异 30kg | 创建中转挂账，要求责任归属 |
| 批次缺采购价 | 成本状态 PENDING_PRICE，不显示假 0 |

### 10.3 headed E2E 重点

必须用 headed browser 验证：

1. 生产文员从计划列表到结单页是否少步骤。
2. BOM 参考和实际输入是否看得清。
3. 错误 toast 是否 sticky 且展示后端 message。
4. 每个页面是否有下一步快捷按钮。
5. 侧边栏入口是否按部门清晰归类。
6. 财务角色和仓库角色看到的动作是否不同。

---

## 11. 实施切片

### PR A: 过账预览只读层

目标：

- 新增 preview API。
- 读取 #812 settlement facts。
- 汇总 BOM 参考、实际领用、库存可用量、成本缺失项。
- 不写库存。

验证：

- 单测覆盖缺价、缺人效、超库存、BOM 差异。
- web 打开结单后可跳过账预览。

### PR B: 正式过账核心

目标：

- 原料/辅料出库。
- 半成品出库/入库。
- 成本拆分落表。
- posting 幂等。

限制：

- 成品仍为待收，不直接入可用仓。

### PR C: 仓库确认 + 中转挂账

目标：

- 成品仓库确认收货后入可用库存。
- 原料/成品双边确认。
- 10kg 容差自动关闭。
- 超差写 clearing ledger。

### PR D: 财务成本页 + 快捷跳转

目标：

- 成本拆分页。
- 差异预警。
- 缺价/缺人效/待仓库确认快捷按钮。
- 侧边栏按部门整理入口。

### PR E: headed E2E + RN 轻录入补齐

目标：

- 生产文员 web 主流程 E2E。
- 仓库确认 E2E。
- 财务查看成本 E2E。
- RN 只补人效/签字/确认，不做复杂过账。

---

## 12. 风险登记

| 风险 | 为什么危险 | 降风险方案 |
|---|---|---|
| 只记录结单事实，不做过账 | 财务看不到实际成本，库存不变，客户以为完成但账没闭 | #812 后必须接 `PENDING_POSTING -> POSTED` 过账层 |
| 过账时直接按 BOM 扣 | 与 6.12 “实际领用为准”冲突，会算错成本 | BOM 只做参考，实际领用是成本依据 |
| 成品生产完成自动入仓 | 与“仓库收到才掉锅”冲突，责任不清 | 成品先待收，仓库确认后入可用库存 |
| 半成品做现场滚动领用 | 6.9 明确现场不是仓库，做不了 | 半成品按重量库存，文员结单补录实际领用 |
| 缺价/缺人工填 0 | 财务成本被污染 | costStatus=PENDING，不显示假 0 |
| 中转差异不挂账 | 仓库少收和生产少产无法追责 | 超容差写 clearing ledger |
| 页面无下一步 | 客户操作卡住，容易漏过账 | 每个状态给快捷按钮 |
| 多人同时改结单 | 账实不一致 | 文员主账号/幂等/状态机锁定 |

---

## 13. 不做和延期

| 项 | 处理 |
|---|---|
| 叮咚导入 | 不做成系统功能，只作为业务样例和测试数据抽象 |
| 水电折旧分摊 | 本期不入实际成本，后续 D1 有数据源再做 |
| IoT 工时 | 本期不做，人工/人效可补录 |
| 现场滚动半成品扫码领用 | 不做，违反现场可用性 |
| 金蝶/用友 API 对接 | 不做 API，只保留导出表头方向 |
| 二次加工主流程化 | 不做。二次加工保留独立异常/返工生产单 |

---

## 14. 下一步建议

立即开 PR A：过账预览只读层。

理由：

1. 不写库存，风险最低。
2. 能立刻验证 6.12 的使用逻辑是否顺手。
3. 可以暴露字段缺口和 API 路径错误。
4. 为 PR B/C 的正式库存过账提供准确输入。

PR A 完成后再做 headed E2E，验证生产文员能否从“未完成列表”顺畅走到“结单事实 + 过账预览 + 下一步仓库确认”。
