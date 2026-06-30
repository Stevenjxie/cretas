# 库存生产业态 实现 spec (R2-A 地基)

**触发**: 龙盛 A2 会议 + 完整现状审计(`docs/audits/2026-06-30-production-inventory-cost-state.md`)。
**架构决策**: R2 = **方案 A(桥接)** — Steve 已拍板。
**Base**: `backend/java/cretas-api/src/main/java/com/cretas/aims/`
**状态**: 设计稿,待独立 review(🔒 涉成本/库存/多租户),分阶段建。

---

## 核心设计:R2-A — 把"库存账"那座漏写的桥补上

**认知**:两套半成品模型不是冗余,是两个层:
- `MaterialBatch(WIP, WH-WKS)` = **成本溯源图节点**(`traceCost` 靠它递归)。**保留**。
- `SemiFinishedInventory` = **库存账**(重量+移动均价成本+流水+审核+乐观锁)。**逐道录入现在漏写它**。

**A 桥接** = 逐道录入物化半成品/成品时,**追加写库存账**(SemiFinishedInventory / FinishedGoodsBatch),用 `materialBatchRefs`(SemiFinishedInventory 已有 jsonb)互引。MaterialBatch(WIP) 不动(成本图继续靠它)。

---

## ⛔ 已 verify-first 钉死的两个 🔒 陷阱(spec 必须绕开)

1. **WIP 批 `productionPlanId=null` 是 load-bearing**(`ClerkProcessEntryServiceImpl:481-485` 注释):挂 planId 会让 `OrderCostBreakdownService` **双计** WIP 原料成本(成品批 traceCost 已回溯上游)。
   → **B1 计划产出汇总必须用 plan-scoped 源(process_sheet_rows / yield-card)构建,绝不给 WIP 批挂 planId。**
2. **逐道录入消耗只写 MaterialConsumption 不扣 usedQuantity**(`:260`);真扣只在 settle。
   ⚠️ **独立审计纠正(2026-06-30)**:settle 扣减是从**用户提交的 `ProductionSettlementConsumption` 请求行**扣(`:1535,2667-2668`),**根本不读 `MaterialConsumption` 行**。所以"给 MaterialConsumption 加 postedToInventory 标志防 settle 双扣"**是错的**(settle 永不读它)。`postMaterialBatchConsumption` 是加法、无幂等。
   → **正解**:库存生产**根本不走 settleProduction 的扣减**。小结独占扣减(实时,**按会话幂等**);停产=纯状态关闭不扣减。双扣路径从架构上消除(见 Phase 2 重设计)。
3. **"复用"地基不成立(独立审计纠正)**:`upsertProducedWip`(private,需 WorkProcessTask+OUTPUT报工,逐道录入都没有)、`createFinishedGoodsFromReceipt`(private,需 ProductionSettlement,小结不结单没有)、`postMaterialBatchConsumption`(收 `ProductionSettlementConsumption` 非 MaterialConsumption,且有 BOM+仓库前置校验)—— 三个都**不能直接复用**,要**新建**逐道录入/小结专用器(见 Phase 2)。
4. **OrderCostBreakdownService 是订单键的**(`compute` 走 `findByFactoryIdAndSourceOrderId:58`):库存生产无 sourceOrderId → `compute` 返回空。总成本只能逐成品批 `computeByBatch`(`:76`)累加,且 `@PriceSensitive` 必须 maskPrice。
5. **yield-card `remaining` 不能裸 Σ 当剩余半成品**:它含成品(CLK-B)行(`:604`)→ Σ 双计;且是**各道原始余量**未折算(无成品当量字段)。需过滤掉成品行 + 只取 WIP 道 + 按出成率折算。

---

## 阶段化(对齐客户时间序 + 风险递增)

### Phase 1 — B1+G5 计划/产品产出汇总「阅读汇总」(独立,最快,不碰 R2/成本)⭐先发
> **修订(2026-06-30)**:B1「看计划完成后具体情况」与 G5「月度出成率汇总」是**同一需求**,客户在转录 line 33-39 亲口设计。合并为一个交付,**不是 later**。

**目标**:打开一个(长期挂着的)库存生产计划/某产品 → 生成「阅读汇总」(客户原话 line 38-39):
**总投入原料 + 总产出成品 + 剩余半成品(折算成品/原料当量)+ 真实总出成率 + 总成本**,滚动累积。

- **真实总出成率口径(line 36-39)**:`真实总出成率 = (总产出成品 + 剩余半成品折算当量) / 总投入原料`。
- **盘点不改历史(line 32, 38)**:汇总是**读侧聚合,不写回历史**;盘盈当收入/盘亏当损耗。
- ⚠️ **独立审计纠正:这不是"薄读现成字段",是真聚合+折算+脱敏任务**(🔒 陷阱 4/5)。逐量构建:
  - **总投入原料** = Σ 计划内**所有批次的首道原料投入行**(不是单批首道)。yield-card 的 `firstInputByProductType` 只取单批首条(`ProcessSheetServiceImpl:571`),**不够**;新端点要自己跨批 Σ,用 `hasUpstreamSources(req)`(`:644`)区分"首道原料投入"vs"WIP 喂入"行。
  - **总产出成品** = Σ 该计划 CLK-B(REGULAR,挂 planId `:197`)批产出。
  - **剩余半成品折算** = 只取 **WIP 道(非成品行)** 的 `remaining`(过滤掉 `status=COMPLETED` 成品行 `:604`)→ 按各道出成率**折算成成品当量**(复用 `cumulativeYieldRate`/`convertToFirstStepUnit` 的转换逻辑 `:647`,但现仅转 `produced`,需扩展转 `remaining`)。
  - **总成本** = 逐 CLK-B 批 `computeByBatch`(`OrderCostBreakdownService:76`)累加(`compute` 订单键对库存生产返回空,不能用);**必须 honor `@PriceSensitive`/maskPrice**(`MaterialConsumption:49` / `OrderCostBreakdownService:41`);WIP 批 planId=null 留意不双计(陷阱 1)。
- **后端**:新增 `GET /production-plans/{planId}/production-summary` 聚合上述五量 + 各批明细。**只读,不改 planId,不写回历史**。
- **附带修**:统一 `findByFactoryId` vs `findByFactoryIdAndStatus` 的 CLERK_WIP 口径(`ProductionBatchRepository:46-53`);`/processing/batches` 的 productionPlanId 过滤修真生效。
- **前端**:计划列表/详情加"阅读汇总"入口,展示五量+各批明细(防呆:数字清晰、批次可点进、成本脱敏遵 RBAC)。
- **🔒**: 中(只读不写库/历史,但跨批聚合+出成率折算+成本脱敏是实打实的算法+RBAC 任务,非薄读)。仍独立、不依赖 R2-A,可先 ship。

### Phase 2 — 库存生产核心:G2 模式 + R2-A 桥接 + G3 小结/停产(共生,一起发)
> G2(productionMode)、R2-A(桥接)、G3(小结)三者**互相依赖必须同发**:小结只对 BY_STOCK,BY_STOCK 永续靠小结分批入库,小结入库靠 R2-A 桥接。

**G2 模式(前置)**:`ProductionPlan` 加新字段 `productionMode(BY_ORDER / BY_STOCK)`(**新字段**,不复用已重载的 planType/sourceType/planSourceType)。Flyway migration。建计划时选业态;BY_STOCK 计划列表标"永续挂起",小结/停产按钮仅 BY_STOCK 出现。


#### 🆕 UX 按钮模型(Steve 2026-06-30 细化)—— 库存生产专属,替代结单
**小结只有库存生产(BY_STOCK)才有**;销售订单生产(BY_ORDER)保持现有结单不变。
- **库存生产:把现有"结单"按钮换成"小结"**。点"小结"**不关闭计划/批次**,而是走小结录入 + **实时扣减**(每次小结即时扣消耗),计划保持挂起。可重复。
- **库存生产另加一个单独"停产/结单"按钮**:最后不做这个品了才点 → 计划真正 COMPLETED(库存已被各次小结实时扣完,这步主要是关闭+最终对账)。
- 位置:小结放逐道录入抽屉内的保存/收尾处,**或**直接替换计划列表上库存生产计划的"结单"按钮(客户倾向后者:"直接代替结单")。两者择一/并存,以 productionMode 门控。

**G3 小结** = 库存生产会话级 `POST /production-plans/{planId}/process-sheet/interim-settle`(仅 BY_STOCK)。
> ⚠️ **独立审计纠正:不是"复用"现有结单器,是新建逐道录入专用器**(三个复用目标都有阻塞性前置,见 🔒 陷阱 3)。
1. **半成品入库** = 新建 `WipInventoryService.postClerkOutput(factoryId, productTypeId, qty, unitCost, materialBatchRefs)` —— 直接 upsert `SemiFinishedInventory`,**不要 WorkProcessTask/OUTPUT 报工**(`upsertProducedWip` 私有且强依赖二者,不能复用)。成本=逐道录入 WIP 单价。
2. **成品入库** = 新建 `createFinishedGoodsForInterim(plan, qty, unit, 小结序号)` —— **不依赖 ProductionSettlement**(`createFinishedGoodsFromReceipt` 私有且批号/幂等从 settlement 派生,不能复用)。批号方案:`FG-{planNumber}-S{小结序号}`(永续计划多小结各成一批,可追溯)。
3. **实时扣减**(客户"实时去扣取")= 新建 `小结Deduct`:对本会话 `MaterialConsumption` 行,**直接** `sourceBatch.usedQuantity += qty`(`findByIdAndFactoryIdForUpdate` 悲观锁),**按会话幂等**(小结记录已 post 的 session,重复点不重复扣)。**不要** `postMaterialBatchConsumption`(收 SettlementConsumption + 有 BOM/仓库前置校验,不适配)。
4. **不关计划**:**不** setStatus(COMPLETED);per-session 累积。

**G3b 停产/最终结单** = `POST /production-plans/{planId}/stop-production`(仅 BY_STOCK):**纯状态关闭** → COMPLETED。**不扣减任何库存**(库存已被各次小结实时扣完)。**绝不路由 settleProduction 的扣减**。

> ⭐ **双扣根治(审计 #1 修正)**:库存生产**全程不碰 settleProduction**。扣减只由小结做(实时+会话幂等),停产只关状态不扣 → **架构上不存在双扣路径**,无需任何"防双扣标志"。
- **依赖**:G2 模式门控(小结/停产仅 BY_STOCK)。
- **防呆**:小结 dialog 带 context(本次产出半成品/成品 + 将扣原料明细)+ 会话幂等 + 实时反馈;停产二次确认("停产后该品计划关闭,不可再录入")。
- **SAFETY_STOCK 注**:`sourceType` 已有 `SAFETY_STOCK` 枚举值 —— 决定它与新 `productionMode=BY_STOCK` 的关系(建议:productionMode 管生命周期,sourceType 管来源/优先级,两者正交,建库存生产计划时可同设)。
- **🔒**: 高(写库+扣减+成本+生命周期,多租户)。必独立 review + 部署后 headed 验证(小结入库/扣减/汇总三方对账 + 会话幂等重点测)。

### Phase 3 — G1 审核入库 + G4 半成品起步(后续)
- **G1 审核**:SemiFinishedInventory/FinishedGoodsBatch 加待审核态;小结入库置 PENDING,审核通过置 AVAILABLE。客户要"即便需要审核"。
- **G4 半成品起步**:`ProcessSheetRowRequest.UpstreamRef` 加 `semiFinishedInventoryId`;`resolveEdges`(`:1199`)加分支从 `semi_finished_inventory` 解析+扣减(复用乐观锁);前端来源下拉增"半成品库"数据源;`resolveProcesses` 加"半成品起步"档(不强制 index0=修油)。让 SECONDARY 计划进逐道录入时预置 WIP 来源。
- **🔒**: 中-高。

---

## 不在本线(客户明说 later / 忽略)
- ~~G5 月度出成率汇总~~ → **已并入 Phase 1**(B1=G5,转录 line 33-39 同一需求)。
- 人工成本 per-盒 view → 依赖 G3 小结(小结后才知道一盒摊多少人工,line 45-47);汇总(Phase 1)出总成本,per-盒待 G3。
- 菜单位置调整(#7,协作走查)。
- 分开录录 / NFC / 排班 / 小组长签到。

---

## 交付纪律
- 每 Phase 独立 worktree off origin/main → PR → Opus 终审(🔒 项)→ 从 main 部署 prod。
- Phase 2/3 写库/成本/生命周期 → 独立 review + 部署后 headed 验证(三方对账,复用 `tests/e2e-yield-mixed-sku/` 模式)。
- 防呆 5 规则(`fool-proof-design.md`):小结/入库/停产 dialog 必带 context + max + 幂等 + 审核去向。
