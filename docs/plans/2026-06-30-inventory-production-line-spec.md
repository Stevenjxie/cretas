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
2. **逐道录入消耗只写 MaterialConsumption 不扣 usedQuantity**(`:260`);真扣只在 settle(`postMaterialBatchConsumption:2668`)。
   → **G3 小结做"实质扣减"时,必须加 `MaterialConsumption.postedToInventory` 标志,防 settle 重复扣**(R3 隐患同源)。

---

## 阶段化(对齐客户时间序 + 风险递增)

### Phase 1 — B1+G5 计划/产品产出汇总「阅读汇总」(独立,最快,不碰 R2/成本)⭐先发
> **修订(2026-06-30)**:B1「看计划完成后具体情况」与 G5「月度出成率汇总」是**同一需求**,客户在转录 line 33-39 亲口设计。合并为一个交付,**不是 later**。

**目标**:打开一个(长期挂着的)库存生产计划/某产品 → 生成「阅读汇总」(客户原话 line 38-39):
**总投入原料 + 总产出成品 + 剩余半成品(折算成品/原料当量)+ 真实总出成率 + 总成本**,滚动累积。

- **真实总出成率口径(line 36-39 客户亲述)**:多批滚动、中间过程不固定,但**总投入原量固定、总产出固定**。
  `真实总出成率 = (总产出成品 + 剩余半成品折算当量) / 总投入原料`。半成品按往期数据折算(复用 `/bom/yield-estimate` 出成率自学习)。
- **盘点不改历史(line 32, 38)**:中间盘点多次,**不回改每批历史成本/数据**;盘盈当收入、盘亏当损耗;真实出成率**只在汇总时从总量算**,不动批级。→ 汇总是**读侧聚合**,不写回历史。
- **数据源(全部 plan-scoped,绕开 🔒 陷阱1 planId,故 Phase 1 完全独立、不依赖 R2-A)**:
  - 各道产出/投入/成本 + **剩余半成品** → 已有 `GET /production-plans/{planId}/process-sheet/inventory/yield-card`(plan-scoped,每个 WIP 批带 `remaining` 余量 = 产出−下游消耗)。剩余半成品 = Σ WIP `remaining`(永续计划下=该计划全部累积 WIP 余量)。**不需 SemiFinishedInventory**。
  - 成品批 → REGULAR(CLK-B)已挂 planId(`:197`),可查。
  - ∴ 真实总出成率 =(Σ成品产出 + Σ剩余半成品折算)/ Σ首道原料投入 —— **三个量都在 yield-card / CLK-B 里,Phase 1 即可算全**。
- **后端**:新增 `GET /production-plans/{planId}/production-summary`(或 `/products/{productTypeId}/yield-summary?period=`)聚合:总投入/总产出/剩余半成品折算/真实总出成率/总成本 + 各批明细。**只读,不改 ProductionBatch.planId,不写回历史**。
- **附带修**:统一 `findByFactoryId` vs `findByFactoryIdAndStatus` 的 CLERK_WIP 口径(`ProductionBatchRepository:46-53`),消除矛盾;`/processing/batches` 的 productionPlanId 过滤修成真生效(按计划看成品批)。
- **前端**:计划列表/详情加"阅读汇总/产出汇总"入口 → 展示总量+真实出成率+各批明细(防呆:数字清晰、批次可点进)。
- **🔒**: 低(只读聚合,不改写库/成本/历史)。可独立先 ship —— **这是客户最想先看到的东西**。

### Phase 2 — R2-A 桥接 + G3 小结(核心,共生)
**G3 小结** = 逐道录入会话级 `POST /production-plans/{planId}/process-sheet/interim-settle`:
1. **入库**(R2-A 桥接):本次会话产出的半成品 → upsert `SemiFinishedInventory`(`WipInventoryServiceImpl.upsertProducedWip` 复用,或新 `postClerkOutput` 不依赖 WorkProcessTask),成本 = 逐道录入算的 WIP 单价;成品 → `createFinishedGoodsFromReceipt`(`:2777` 复用)建 FinishedGoodsBatch。
2. **实质扣减**:对本会话 `MaterialConsumption` 行驱动 `postMaterialBatchConsumption`(`:2668`)真扣 usedQuantity,**打 `postedToInventory` 标志**。
3. **不关计划**:**不** setStatus(COMPLETED)、**不** 触发单结单 409 守卫;`ProductionSettlement`(或新 `ProductionInterimSettlement`)keyed per-session 累积。
- **依赖**:G2 永续模式(否则第 2 次小结撞单结单守卫)。
- **🔒**: 高(写库+扣减+成本,多租户)。必独立 review + 部署后 headed 验证(复用本 session 成本测试模式)。

### Phase 3 — G2 永续计划模式
- `ProductionPlan` 加新字段 `productionMode(BY_ORDER / BY_STOCK)`(**新字段**,不复用已重载的 planType/sourceType/planSourceType)。Flyway migration。
- BY_STOCK:小结(Phase 2)允许多次(uniqueness per-session);settle/小结后保持 `IN_PROGRESS`;加显式"停产"转换(唯一到 COMPLETED 的路径)。
- 前端:建计划时选业态(库存生产/订单生产);库存生产计划列表标识"永续挂起"。
- **🔒**: 中(生命周期+状态机)。

### Phase 4 — G1 审核入库 + G4 半成品起步(后续)
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
