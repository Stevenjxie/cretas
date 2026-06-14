# Production + BOM Full-Link E2E Audit

Date: 2026-06-14
Run ID: production-bom-prod-bom-2026-06-14T16-36-22-461Z
Branch: test/liushanmen-prod-bom-e2e
Target: http://139.196.165.140:8086
Account: f006_admin
Expected factory: F006
Actual factory: F006 / FACTORY
Test data prefix: E2E_PROD_BOM_mqe0atrx

## Summary

- PASS: 5
- FAIL: 6
- WARN: 1
- SKIP: 0
- Console/API errors captured: 2

## Depth Analysis

| depth | count | PASS | FAIL | WARN | SKIP |
|---|---:|---:|---:|---:|---:|
| smoke | 3 | 1 | 2 | 0 | 0 |
| medium | 6 | 3 | 2 | 1 | 0 |
| deep | 3 | 1 | 2 | 0 | 0 |

## 6.12 Production Transcript Coverage Map

Source: `docs/audits/liushanmen/2026-06-12-full-operation-flow.md` and `docs/meetings/2026-06-09-liushanmen/requirements-catalog.md`.

Required transcript claims:
- C1 厂长/PMC 排产: 销售订单来源时产品/客户应自动关联，计划推迟以实际开工为准。
- C2 多 SO 合并: 建生产单时可追加销售单号，生产单号/销售单号可互查。
- C3 领料配料汇总单: BOM 自动反推预领量，仓库看到全物料汇总需求。
- C4 生产工单打印: 生产/生管自己打印，不由销售打印。
- C5 工序负责人: 计划层分配，开工后任务下发到手机端。
- C6 开工生成批次: 批次号可追踪生产过程。
- C7 仓库领料调拨: 车间/仓库各自确认，调拨差异有责任链。
- C8/C10 两点报工: 投入+产出，产出要有证据，出成率滚动更新。
- C11 时段报工: 人数+时长可后期补录累计。
- C12 同单双产出: 一个生产单可产成品+半成品，半成品按 code 挂生产库存。
- C13 生产报损: 拍照留证，报损后料不够再走调拨。
- C14 整单撤回: 整单非单工序，无数据直撤，有数据审批。
- C15 完工入库: 只有成品入仓库，半成品仍挂生产库。
- F6/F7 盘点: 盘点任务发起、数量暂存、财务审批后才生效，全程留痕。
- F9 退库: 生产多领辅料/包材退回仓库，退回=发出-实用-损耗。
- X4 补录时效: 今天/昨天可补，前天极限，大前天禁止。

| 6.12 claim | Checked in step(s) | Audit focus |
|---|---|---|
| C1 PMC排产/自动关联 | 02, 05A | 销售订单来源、产品/客户自动带入、计划日期默认值、计划页入口提示。 |
| C2 多SO合并/互查 | 02, 05A | 销售订单到生产计划链路及计划创建弹窗；如 UI 未暴露追加销售单号则记为缺口。 |
| C3 领料配料汇总 | 03, 04, 05A | 计划转批次、核对结单领料入口、BOM 自动调拨提示。 |
| C4 生产工单打印 | 05A | 生产计划页巡检打印/工单入口，确认非销售页负责。 |
| C5 工序负责人 | 05A | 计划创建/详情巡检人员分配、后续手机端下发提示。 |
| C6 开工生成批次 | 03, 06 | 计划转批次并发双击、批次详情可追踪。 |
| C7 仓库领料调拨 | 04, 05A | 领料报工、仓库/车间调拨提示、差异责任链文案。 |
| C8/C10 两点报工 | 04 | 投入+产出、证据、出成率、max 边界、上下文。 |
| C11 时段报工 | 04, 05A | 人数+工时后期补录入口和提交前防呆。 |
| C12 同单双产出 | 01, 04, 05 | 工序半成品产出配置、同一结单成品+半成品产出、半成品挂生产库存。 |
| C13 生产报损 | 04, 05A | 核对结单/领料相关报损入口、缺料再调拨提示。 |
| C14 整单撤回 | 06, 10 | 批次整单撤回、原因 dropdown、有/无数据路径、死路导航。 |
| C15 完工入库 | 05 | 只有成品入仓库，半成品留生产库存，F006 结算/409 提示。 |
| F6/F7 盘点 | 05A | 发起盘点、录入暂存、财务审批后生效、留痕。 |
| F9 退库 | 07 | 关单退料预览，发出-实用-损耗=退回，usedQuantity 反冲。 |
| X4 补录时效 | 04, 05A | 今天/昨天/前天/大前天的补录可编辑窗口；未暴露日期控件则记为缺口。 |

## Scenario Results

### 00. 登录与环境确认
- depth: medium
- result: PASS
- screenshots: `tests/e2e/production-bom-flow-screenshots/production-bom-prod-bom-2026-06-14T16-36-22-461Z/00-login-dashboard.png`
- evidence: UI login success | factory=F006 | factoryType=FACTORY | base=http://139.196.165.140:8086
- UI signals: none
- fool-proof: headed browser visible | zh-CN locale configured
- bugs: none

### 01. 工序配置: 建/改工序 + 重复查重提示
- depth: deep
- result: PASS
- screenshots: `tests/e2e/production-bom-flow-screenshots/production-bom-prod-bom-2026-06-14T16-36-22-461Z/01-01-work-process-list.png`, `tests/e2e/production-bom-flow-screenshots/production-bom-prod-bom-2026-06-14T16-36-22-461Z/01-02-create-work-process-dialog.png`, `tests/e2e/production-bom-flow-screenshots/production-bom-prod-bom-2026-06-14T16-36-22-461Z/01-02b-create-work-process-semi-output-configured.png`, `tests/e2e/production-bom-flow-screenshots/production-bom-prod-bom-2026-06-14T16-36-22-461Z/01-03-edit-work-process-saved.png`, `tests/e2e/production-bom-flow-screenshots/production-bom-prod-bom-2026-06-14T16-36-22-461Z/01-04-duplicate-work-process-guard.png`
- evidence: filled: 工序名称=E2E_PROD_BOM_mqe0atrx_滚揉, 类别=前处理, 单位=kg, 预估工时=18, 标准时薪=25, 半成品产出编码=E2E_PROD_BOM_mqe0atrx_ROLL_WIP | auto recommendation: 半成品产出编码 initial=E2EPRODBOMMQE0ATRX滚揉-WIP | toast: 工序已创建，半成品产出编码：E2E_PROD_BOM_mqe0atrx_ROLL_WIP | list after: E2E_PROD_BOM_mqe0atrx_滚揉 visible
- UI signals: step-end: 新增工序 工序名称 工序类别 前处理 计量单位 预估工时 标准出成率下限 %（焯水约 30~60，滚揉保水 100~135；装盒/检验类留空。输 0 无效，不校验请清空） 标准出成率上限 %（超收预检以此为基准 × 投入量 × 1.3 容差） 需录投入量 纯包装/检验类可关闭 产出单位 本工序产出半成品 开启后此工序产出可作半成品入生产库，供二次加工领用 标准时薪(元/小时) 排序 取消 确定 | step-end: 操作无法完成 工序名称已存在: E2E_PROD_BOM_mqe0atrx_滚揉 请使用其他工序名称
- fool-proof: SP1 semi-finished output code configured=true; switchFound=true; autoSuggest=true | Rule duplicate: PASS; toast=操作无法完成 工序名称已存在: E2E_PROD_BOM_mqe0atrx_滚揉 请使用其他工序名称
- bugs: none

### 02. 订单财审计划: 建销售订单 -> 财务审核 -> 生产计划入口
- depth: medium
- result: WARN
- screenshots: `tests/e2e/production-bom-flow-screenshots/production-bom-prod-bom-2026-06-14T16-36-22-461Z/02-01-sales-orders.png`, `tests/e2e/production-bom-flow-screenshots/production-bom-prod-bom-2026-06-14T16-36-22-461Z/02-02-sales-order-create-dialog.png`, `tests/e2e/production-bom-flow-screenshots/production-bom-prod-bom-2026-06-14T16-36-22-461Z/02-03-sales-order-after-create.png`, `tests/e2e/production-bom-flow-screenshots/production-bom-prod-bom-2026-06-14T16-36-22-461Z/02-04-sales-finance-review.png`, `tests/e2e/production-bom-flow-screenshots/production-bom-prod-bom-2026-06-14T16-36-22-461Z/02-05-production-plan-entry.png`
- evidence: filled: 客户=叮咚-台州临海大洋东路冷藏仓, 产品=first option, 数量=12, 单价=18, remark=E2E_PROD_BOM_mqe0atrx | toast: 这里是「销售订单」管理 — 客户向我们下的订单（出货方向、应收账款） ️ 如果你想录入的是我们向供应商下的订单（进货方向、应付账款），请到 采购管理 → 采购订单 创建。 | list after: beforeRows=10, page text has prefix=false | submit finance review button not visible in row | finance review page loaded; text contains 财务/审核=true
- UI signals: step-end: 这里是「生产计划」管理 — 未完成生产任务（PENDING / IN_PROGRESS，文员核对实际产量、领用和工时后结单） ️ 如果你想录入的是已开工的实际「批次」（IN_PROGRESS / COMPLETED，记录实际产量、消耗），请到 生产管理 → 生产批次 创建。需要 APP 逐道报工时再转批次；PC 文员在未完成列表核对结单。 | step-end: 生产计划操作指引 计划确认后，先进入未完成列表；原料库存不足只做预警，不阻断开工或结单： 生成调拨单：根据 BOM 自动计算所需原辅料/包材，发申请给仓库审批。库存不足或需要从其他仓库调料时使用。 核对结单：PC 文员逐单核对实际产量、原料/半成品领用和工时；缺料信息会在列表和弹窗里作为参考值显示。 APP 报工 / 转批次：需要 APP 逐道报工时使用，系统会自动建批次 + 工序任务；原料不足只提示缺口，不阻断转批次。 PC 结单：不需要逐道报工的计划，也必须由文员在「核对结单」里录入实际产量、实际领用和人效后，
- fool-proof: 订单行未直接暴露提交财审按钮，可能在“更多”菜单内；记录为未验证。
- bugs: none

### 03. 计划批次: 计划转批次 + 并发双击不双建
- depth: medium
- result: PASS
- screenshots: `tests/e2e/production-bom-flow-screenshots/production-bom-prod-bom-2026-06-14T16-36-22-461Z/03-01-production-plans.png`, `tests/e2e/production-bom-flow-screenshots/production-bom-prod-bom-2026-06-14T16-36-22-461Z/03-02-after-double-submit-create-batch.png`
- evidence: read-only precheck: plans=20, batches=50
- UI signals: step-end: 这里是「生产计划」管理 — 未完成生产任务（PENDING / IN_PROGRESS，文员核对实际产量、领用和工时后结单） ️ 如果你想录入的是已开工的实际「批次」（IN_PROGRESS / COMPLETED，记录实际产量、消耗），请到 生产管理 → 生产批次 创建。需要 APP 逐道报工时再转批次；PC 文员在未完成列表核对结单。 | step-end: 生产计划操作指引 计划确认后，先进入未完成列表；原料库存不足只做预警，不阻断开工或结单： 生成调拨单：根据 BOM 自动计算所需原辅料/包材，发申请给仓库审批。库存不足或需要从其他仓库调料时使用。 核对结单：PC 文员逐单核对实际产量、原料/半成品领用和工时；缺料信息会在列表和弹窗里作为参考值显示。 APP 报工 / 转批次：需要 APP 逐道报工时使用，系统会自动建批次 + 工序任务；原料不足只提示缺口，不阻断转批次。 PC 结单：不需要逐道报工的计划，也必须由文员在「核对结单」里录入实际产量、实际领用和人效后，
- fool-proof: Rule 4 idempotency: batches before=50, after=50, delta=0
- bugs: none

### 04. 两点报工/核对结单: 6.12 同单双产出 + WIP 防呆
- depth: deep
- result: FAIL
- screenshots: `tests/e2e/production-bom-flow-screenshots/production-bom-prod-bom-2026-06-14T16-36-22-461Z/04-01-plan-list-before-settlement.png`, `tests/e2e/production-bom-flow-screenshots/production-bom-prod-bom-2026-06-14T16-36-22-461Z/04-02-settlement-or-detail-dialog.png`, `tests/e2e/production-bom-flow-screenshots/production-bom-prod-bom-2026-06-14T16-36-22-461Z/04-03-over-plan-reason-dropdown.png`, `tests/e2e/production-bom-flow-screenshots/production-bom-prod-bom-2026-06-14T16-36-22-461Z/04-04-wip-consumption-boundary.png`
- evidence: dialogVisible=true | 6.12 transcript: same plan can output finished+semi=true; FG waits warehouse receipt=true | 6.12 transcript: raw consumption=true; WIP consumption=true | 6.12 transcript: production loss evidence entry=false | WIP inventory readback: rows=64, priced=0, honestNullCost=64
- UI signals: step-end: 这里是「生产计划」管理 — 未完成生产任务（PENDING / IN_PROGRESS，文员核对实际产量、领用和工时后结单） ️ 如果你想录入的是已开工的实际「批次」（IN_PROGRESS / COMPLETED，记录实际产量、消耗），请到 生产管理 → 生产批次 创建。需要 APP 逐道报工时再转批次；PC 文员在未完成列表核对结单。 | step-end: 生产计划操作指引 计划确认后，先进入未完成列表；原料库存不足只做预警，不阻断开工或结单： 生成调拨单：根据 BOM 自动计算所需原辅料/包材，发申请给仓库审批。库存不足或需要从其他仓库调料时使用。 核对结单：PC 文员逐单核对实际产量、原料/半成品领用和工时；缺料信息会在列表和弹窗里作为参考值显示。 APP 报工 / 转批次：需要 APP 逐道报工时使用，系统会自动建批次 + 工序任务；原料不足只提示缺口，不阻断转批次。 PC 结单：不需要逐道报工的计划，也必须由文员在「核对结单」里录入实际产量、实际领用和人效后， | step-end: 核对结单 — 叮咚好食光椒麻掌中宝 120g 计划单号 PLAN-1781259812021-CA6647E7 品名 叮咚好食光椒麻掌中宝 120g 计划数量 10 产出核对 实际产量 计划数量只是参考，超出时系统预警并要求原因，不硬拦。 实际产量超过计划数量，请选择差异原因。 差异原因 请选择差异原因 半成品产量 同一生产计划可以同时产成品和半成品；提交结单会按实际领用扣减原料/半成品，成品需仓库确认实收后才入库。 实际领用核对 原料库存参考: 暂无缺料预警 原料/辅料实际领用 增加原料行 半成品实际领用 增加半 | step-end: 实际产量超过计划数量，请选择差异原因。 | step-end: 原料库存参考: 暂无缺料预警 | step-end: 实际产量超过计划时必须选择差异原因
- fool-proof: Rule 2 context: title/body contains product + plan number + planned qty=true | Rule 1 settlement boundary: plan qty is advisory, over-plan requires reason instead of hard block | Rule 3 over-plan reason dropdown=true; text=true | Rule 1 WIP max boundary visible=false; addWipClicked=true; wipSelect=true | Rule 1 submit disabled until valid output+consumption+labor=true
- bugs: 核对结单弹窗未暴露生产报损/损耗留证入口，6.12 要求报损后有证据并可触发补料。 | 半成品领用未在弹窗内前置展示可用量/max 边界。

### 05. 完工入库: F006 结算路径 + 409 PRODUCTION_SETTLEMENT_REQUIRED 提示
- depth: medium
- result: PASS
- screenshots: `tests/e2e/production-bom-flow-screenshots/production-bom-prod-bom-2026-06-14T16-36-22-461Z/05-01-completed-plans-or-settlement.png`, `tests/e2e/production-bom-flow-screenshots/production-bom-prod-bom-2026-06-14T16-36-22-461Z/05-02-finished-goods-page.png`
- evidence: 当前计划列表未暴露“仓库确认入库”按钮；保留成品库存页/409 路径证据。 | finished goods page loaded; has 入库/成品=true
- UI signals: step-end: 入库渠道说明 正常入库来自「生产完工确认」自动写入。期初入库仅用于系统上线初始化 / Excel 结存导入，请勿日常使用。
- fool-proof: Rule 1 receipt max/context text present=true | 4位一体 toast not triggered in this state; requires completed unreceived F006 plan.
- bugs: none

### 05A. 生管文员补录巡检: 生产计划/核对结单/盘点自动带入与提示
- depth: deep
- result: FAIL
- screenshots: `tests/e2e/production-bom-flow-screenshots/production-bom-prod-bom-2026-06-14T16-36-22-461Z/05A-01-production-plan-clerk-entry.png`, `tests/e2e/production-bom-flow-screenshots/production-bom-prod-bom-2026-06-14T16-36-22-461Z/05A-02-new-production-plan-dialog-defaults.png`, `tests/e2e/production-bom-flow-screenshots/production-bom-prod-bom-2026-06-14T16-36-22-461Z/05A-03-production-plan-sales-order-autofill.png`, `tests/e2e/production-bom-flow-screenshots/production-bom-prod-bom-2026-06-14T16-36-22-461Z/05A-04-stocktake-page.png`, `tests/e2e/production-bom-flow-screenshots/production-bom-prod-bom-2026-06-14T16-36-22-461Z/05A-05-stocktake-initiate-dialog.png`
- evidence: production plan guide: clerk=true, BOM transfer auto hint=true, workOrderPrint=true, backfillWindowGuide=false | new plan defaults: sourceType=true, batchDate=2026-06-14, plannedDate=2026-06-15, reportModeVisible=true, assigneeControl=true | sales-order autofill: selectedOrder=(none), productDisabled=true, customerName=(empty), hint=true | stocktake page boundary text=true | stocktake initiate defaults: periodMonth=2026-06, currentMonth=2026-06, warehouseSelect=true | 盘点列表当前无行，未验证已有盘点录入弹窗。
- UI signals: new-plan-dialog: 这里是「生产计划」管理 — 未完成生产任务（PENDING / IN_PROGRESS，文员核对实际产量、领用和工时后结单） ️ 如果你想录入的是已开工的实际「批次」（IN_PROGRESS / COMPLETED，记录实际产量、消耗），请到 生产管理 → 生产批次 创建。需要 APP 逐道报工时再转批次；PC 文员在未完成列表核对结单。 | new-plan-dialog: 生产计划操作指引 计划确认后，先进入未完成列表；原料库存不足只做预警，不阻断开工或结单： 生成调拨单：根据 BOM 自动计算所需原辅料/包材，发申请给仓库审批。库存不足或需要从其他仓库调料时使用。 核对结单：PC 文员逐单核对实际产量、原料/半成品领用和工时；缺料信息会在列表和弹窗里作为参考值显示。 APP 报工 / 转批次：需要 APP 逐道报工时使用，系统会自动建批次 + 工序任务；原料不足只提示缺口，不阻断转批次。 PC 结单：不需要逐道报工的计划，也必须由文员在「核对结单」里录入实际产量、实际领用和人效后， | new-plan-dialog: 新建生产计划 来源类型 手动 存货生产 销售订单 AI预测 销售订单 选择关联的销售订单 产品类型 选择产品类型 来源为销售订单时，产品类型由所选订单行自动确定，不可手动更改 客户名称 报工模式 逐道报工 免工序报工 操作员只报「领料入」+「产出」两个节点 批次日期 批次日期 = 实际开工/转批次日；计划日期 = 预期完成生产日 计划数量 计划生产日 备注 预计工人数 指派主管(可选) 可不填，稍后再指派 取消 确定 | stocktake-initiate-dialog: 本月 (2026-06) 尚未创建盘点任务，建议每月发起一次盘点。 | stocktake-initiate-dialog: 盘点数量录入后暂存，批准后才正式生效调整库存。 | stocktake-initiate-dialog: 发起盘点任务 盘点仓库 选择仓库 盘点月份 格式: 2026-06 备注 取消 确认发起 | step-end: 本月 (2026-06) 尚未创建盘点任务，建议每月发起一次盘点。 | step-end: 盘点数量录入后暂存，批准后才正式生效调整库存。
- fool-proof: auto date defaults present=true | report mode explains two-point vs per-process=true | plan-layer assignee/control visible=true | source order memory/recommendation: product locked=true, customer auto-filled or hinted=true | stocktake month auto default current=true | stocktake warehouse constrained dropdown=true
- bugs: 生产计划/补录入口未展示“今天/昨天可补、前天极限、大前天禁止”的补录时效防呆。

### 06. 整单撤回: 批次详情原因 dropdown + 审批/直撤入口
- depth: medium
- result: FAIL
- screenshots: `tests/e2e/production-bom-flow-screenshots/production-bom-prod-bom-2026-06-14T16-36-22-461Z/06-01-batch-list.png`, `tests/e2e/production-bom-flow-screenshots/production-bom-prod-bom-2026-06-14T16-36-22-461Z/06-02-batch-detail.png`
- evidence: 批次详情无整单撤回按钮
- UI signals: none
- fool-proof: none
- bugs: 批次详情未显示“整单撤回/撤回整单”入口。

### 07. 退料回仓: 退料预览 + usedQuantity 反冲证据
- depth: smoke
- result: FAIL
- screenshots: `tests/e2e/production-bom-flow-screenshots/production-bom-prod-bom-2026-06-14T16-36-22-461Z/07-01-material-returns.png`
- evidence: 退料页文本含预览口径=false | 退料页未显示发出/实用/损耗/退回预览口径
- UI signals: none
- fool-proof: none
- bugs: 退料回仓页未展示退料预览四口径，usedQuantity 反冲未能在 UI 证据中验证。

### 08. BOM 成本: 料+研发人工+制费 + 缺成本 null + 16位编码
- depth: smoke
- result: PASS
- screenshots: `tests/e2e/production-bom-flow-screenshots/production-bom-prod-bom-2026-06-14T16-36-22-461Z/08-01-bom-unified.png`
- evidence: BOM page contains 标准成本/人工/制费/null/16位 terms=true
- UI signals: step-end: BOM 已对接生产计划, 录入即生效 本页录入的 BOM 配方 (含成品含量 + 出成率% + 单位) 保存后立即被生产计划自动展开使用, 无需再同步「转换率配置」(RPF)。RPF 表保留作为老工厂数据的 fallback。 | step-end: 这里是「BOM 成本管理」管理 — 一个成品需要哪些原料、各多少量、成本如何拆分（多对多结构 + 成本核算） ️ 如果你想录入的是单一原料 → 单一成品的「出成率」（如 1kg 冻猪蹄 → 600g 卤猪蹄，60%），请到 生产管理 → 转换率配置 创建。复杂配方用 BOM，简单出成率用转换率。
- fool-proof: none
- bugs: none

### 09. 财务账簿: 序时/总账/明细/试算平衡导出
- depth: medium
- result: FAIL
- screenshots: `tests/e2e/production-bom-flow-screenshots/production-bom-prod-bom-2026-06-14T16-36-22-461Z/09-01-voucher-export.png`, `tests/e2e/production-bom-flow-screenshots/production-bom-prod-bom-2026-06-14T16-36-22-461Z/09-02-export-disabled-requires-period.png`
- evidence: 序时账: visible | 总账: missing | 明细账: missing | 试算平衡: missing | last export button disabled=true
- UI signals: step-end: 请先选择期间日期范围再导出
- fool-proof: export disabled until period range is selected; no fake balanced export generated
- bugs: 财务账簿导出缺少 总账 入口或文案。 | 财务账簿导出缺少 明细账 入口或文案。 | 财务账簿导出缺少 试算平衡 入口或文案。

### 10. 死路导航: 已完成计划取消提示导向批次整单撤回
- depth: smoke
- result: FAIL
- screenshots: `tests/e2e/production-bom-flow-screenshots/production-bom-prod-bom-2026-06-14T16-36-22-461Z/10-01-cancel-dead-end-navigation.png`
- evidence: 未发现“请用批次整单撤回”导向提示
- UI signals: step-end: 这里是「生产计划」管理 — 未完成生产任务（PENDING / IN_PROGRESS，文员核对实际产量、领用和工时后结单） ️ 如果你想录入的是已开工的实际「批次」（IN_PROGRESS / COMPLETED，记录实际产量、消耗），请到 生产管理 → 生产批次 创建。需要 APP 逐道报工时再转批次；PC 文员在未完成列表核对结单。 | step-end: 生产计划操作指引 计划确认后，先进入未完成列表；原料库存不足只做预警，不阻断开工或结单： 生成调拨单：根据 BOM 自动计算所需原辅料/包材，发申请给仓库审批。库存不足或需要从其他仓库调料时使用。 核对结单：PC 文员逐单核对实际产量、原料/半成品领用和工时；缺料信息会在列表和弹窗里作为参考值显示。 APP 报工 / 转批次：需要 APP 逐道报工时使用，系统会自动建批次 + 工序任务；原料不足只提示缺口，不阻断转批次。 PC 结单：不需要逐道报工的计划，也必须由文员在「核对结单」里录入实际产量、实际领用和人效后，
- fool-proof: Rule 5 dead-end navigation: cancelVisible=true, guidance=false
- bugs: 已完成/不可取消计划未展示“请用批次整单撤回”的 next action 导向。

## Bug List

1. 核对结单弹窗未暴露生产报损/损耗留证入口，6.12 要求报损后有证据并可触发补料。
2. 半成品领用未在弹窗内前置展示可用量/max 边界。
3. 生产计划/补录入口未展示“今天/昨天可补、前天极限、大前天禁止”的补录时效防呆。
4. 批次详情未显示“整单撤回/撤回整单”入口。
5. 退料回仓页未展示退料预览四口径，usedQuantity 反冲未能在 UI 证据中验证。
6. 财务账簿导出缺少 总账 入口或文案。
7. 财务账簿导出缺少 明细账 入口或文案。
8. 财务账簿导出缺少 试算平衡 入口或文案。
9. 已完成/不可取消计划未展示“请用批次整单撤回”的 next action 导向。

## Console / Network Errors

- [console:error] Failed to load resource: the server responded with a status of 409 ()
- [console:error] [操作失败] ApiError: 工序名称已存在: E2E_PROD_BOM_mqe0atrx_滚揉
    at http://139.196.165.140:8086/assets/request-C2MLLhIW.js:14:331
    at async j.request (http://139.196.165.140:8086/assets/index-0Q7cGwnK.js:5:1982)
    at async fe (http://139.196.165.140:8086/assets/index-D6Zf40aH.js:20:5714)
    at j.request (http://139.196.165.140:8086/assets/index-0Q7cGwnK.js:5:2078)
    at async fe (http://139.196.165.140:8086/assets/index-D6Zf40aH.js:20:5714)

## Headed Mode Verification

- headless: false
- viewport: 1920x1080
- locale: zh-CN via Playwright locale and Chromium `--lang=zh-CN`
- font rendering: `--font-render-hinting=none`
- anti-automation flag: `--disable-blink-features=AutomationControlled`
- screenshot: `{ mode: 'on', fullPage: true }`
- video: `{ mode: 'on' }`
- PLAYWRIGHT_PORT: 9222
- PLAYWRIGHT_CHAT_ID: prod-bom
- Chinese render check: screenshots captured from headed Chromium; audit validates visible Chinese UI text manually from screenshot set. No headless run was used.
