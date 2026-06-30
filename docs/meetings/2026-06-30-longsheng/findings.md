# 龙盛 A2 会议 — 库存生产业态需求 + bug/缺口核对

**录音**: `d8dcba66-Longsheng_Building_Block_A_2.m4a` (15:10, faster-whisper large-v3, p=1.00)
**转录**: `transcript.txt` / `transcript.srt`
**日期**: 2026-06-30

---

## 业态澄清:库存生产(存货生产) vs 销售订单生产

客户(张总)明确两种业态,系统当前只贴合后者,本次要补前者:

| 维度 | 销售订单生产(现有) | **库存生产/存货生产(本次要补)** |
|---|---|---|
| 触发 | 来一个销售单 → 建一个计划 → 生产完结单关闭 | **动态补铺存**:不知道做多少,凭经验提前备料生产 |
| 计划数量 | 有意义 | **一点用没有**(line 22-23 "计划数量一点用没有") |
| 计划生命周期 | 一单一计划,结单即关 | **一个计划永久挂着**(line 27-29 "一直挂着,一直录入"),直到不再做这个品才关 |
| 入库 | 结单时一次性 | **滚动**:每次录入小结即入库(半成品/成品),账滚动(line 28 "所有的账都是滚动的") |
| 追溯 | 一单一批清晰 | 滚动零单下"一单一关再开新单"追溯特别麻烦(line 18-21),所以要永久单 |

物料流: 原料(A) → 半成品(B) → 成品(C)。可 领A产B,也可 **B直接产C**(line 1)。

---

## 待核对项(verify-first 对 origin/main,审计中)

> 客户认为这些都是"小改",要求明后天(2026-07-01~02)出。审计结论填入下方各项。

### ⭐ 系统性根因(贯穿 #1/#2/#5):两套互不连通的"半成品"库存模型
- **逐道录入侧**:半成品产出 = `MaterialBatch(batchType=CLERK_WIP, 车间仓 WH-WKS)`,WIP 批 `productionPlanId=null`(`ClerkProcessEntryServiceImpl:197,479-486,546-575`)。逐道录入**从不调** `WipInventoryService`。
- **报工(操作员)侧**:半成品 = `SemiFinishedInventory` 表,只由 `WipInventoryServiceImpl.postApprovedOutput`(审核后入库)写入,调用方仅 `ProcessWorkReportingServiceImpl:595-611`。
- **"半成品库存"页只读** `SemiFinishedInventory`(`SemiFinishedInventoryController:42-47`)→ 逐道录入产的半成品(在 MaterialBatch/WH-WKS)**永远不显示**,也无法被下游选用。
- ⇒ 这一个根因同时解释 #1「半成品库存没显示」、#2「无法入半成品库」、#5「工序选不到半成品库」。**核心架构决策:统一/桥接这两套 WIP 模型。**

### 1. 结单后生产批次看不到 + 半成品库存没显示 — **真 BUG(双重原因)**
- (a) 逐道录入半成品批 = `CLERK_WIP`,被 `ProductionBatchRepository.findByFactoryId`(`:46-48` `batchType <> 'CLERK_WIP'`)**显式过滤**→ 生产批次列表永远看不到;且 CLERK_WIP 批 planId=null。
- (b) `settleProduction`(`:1503-1586`)结单**不创建也不完成任何 ProductionBatch**;成品另存 `FinishedGoodsBatch`(不在 /processing/batches),还需再点"仓库确认入库"。
- (c) 口径矛盾:`findByFactoryIdAndStatus`(带状态过滤)**不**排除 CLERK_WIP → 选了状态过滤 CLERK_WIP 又冒出来。
- (d) 前端列表不传 `productionPlanId`(`batches/list.vue:127-133`)→ 无法按计划看批次。
- **修复方向**:统一 `findByFactoryId` vs `findByFactoryIdAndStatus` 的 CLERK_WIP 口径 + 加 `batchType/includeWip` 或专门"半成品批次"端点 + 列表加 productionPlanId 过滤;若要"结单即出已完成批",在 settle 级联完成关联 REGULAR 批(参考 `completeProduction:1462-1488`)。

### 2. 自动入半成品库 + 成品库(即便需要审核)— **缺失(非 bug)**
- `SemiFinishedInventory` 模型齐全且工作,但**逐道录入没接它**(接的是 MaterialBatch/WH-WKS,见根因)。
- 成品:逐道录入成品批**不建任何成品库存**;`FinishedGoodsBatch` 唯一自动入库点 = `confirmWarehouseReceipt→createFinishedGoodsFromReceipt`(`:2328,2777-2820`),需 settle+手动"仓库确认"两步。结单本身不入成品库(设计推迟)。
- 审核工作流仅存在于操作员报工链路(`postApprovedOutput`),逐道录入侧无"录入→审核→入库"。
- **实现方向**:逐道录入半成品产出分支(`:327-336`)追加写 `SemiFinishedInventory`(或新增 `postClerkOutput` 不依赖 WorkProcessTask);成品在 settle/逐道录入追加建 `FinishedGoodsBatch`(可置待审核态);给两库存实体加待审核状态实现"带审核入库"。

### 3. 库存生产计划"一直挂着" — **缺失**
- 无区分库存生产 vs 销售订单的生命周期模式(`planType`=FUTURE/FROM_INVENTORY 是物料来源,`sourceType.SAFETY_STOCK` 只驱动优先级,都不控生命周期)。
- `settleProduction` 单次性:已结单再 settle 报 409(`:1524-1530`),且强制 `setStatus(COMPLETED)`(`:1568`)。
- **录入侧不被状态门控**(`ProcessSheetServiceImpl:111` 只查工厂)→ 录入已接近永续,卡点纯在 finalize。
- **实现方向**:加 `productionMode(BY_ORDER/BY_STOCK)` 新字段;BY_STOCK 时 settle 允许多次(uniqueness 改 per-session 非 per-plan)+ 保持 IN_PROGRESS;加显式"停产"转换才到 COMPLETED。

### 4. 逐道录入"小结" — **缺失**(半成品 WIP 入库已部分实现)⭐核心
- 无任何 `小结/interim/partialSettle` 端点(grep 0 命中)。`保存`(materializeBatch)只写 `MaterialConsumption` 台账,**不扣源批**;半成品→WIP MaterialBatch;成品不入库;原辅料 `usedQuantity++` 仅 `postMaterialBatchConsumption←settleProduction`。`getCurrentQuantity` 忽略台账 → "实质扣减"确实要到 settle。
- **实现方向**:新增 `POST .../process-sheet/interim-settle`(会话级小结):对本次会话的 `MaterialConsumption` 行驱动现有 `postMaterialBatchConsumption/postSemiFinishedConsumption`(`:2658-2696`)真扣减 + 复用 `createFinishedGoodsFromReceipt`(`:2777`)建成品 + **不** setStatus(COMPLETED)、**不** 触发单结单守卫;给 `MaterialConsumption` 加 `postedToInventory` 标志防 settle 重复扣。

### 5. 半成品直接生产 + 工序选择能选半成品库 — **不通(3 缺口叠加)**
- 逐道录入来源解析 `resolveEdges`(`:1199-1235`)只认 RAW_MATERIAL(materialBatchId)+ SEMI_FINISHED(upstreamSources.sourceBatchNumber,factory 级查 productionBatch);DTO 无 `semiFinishedInventoryId`/`sourceType`;**根本不查** `semi_finished_inventory`。
- 前端来源下拉只喂"同计划上一道 WIP"(`ProcessSheet.vue:246-251` plan 级 inventoryMap);拿不到半成品库。
- B→C 现在**只通过** `SECONDARY 二次加工` 独立入口(`createSecondaryPlan:3940-4009` + 开工整批扣 `deductForSecondaryPlan`),非工序级来源选择;逐道录入工序链恒以原料领料(修油)开头(`ProcessSheet.vue:139-145`)。BOM 的 `SEMI_FINISHED` 组件仅成本用,不驱动来源。
- **实现方向**:前端来源下拉增"半成品库"数据源(调 `/semi-finished/inventory`);`ProcessSheetRowRequest.UpstreamRef` 加 `semiFinishedInventoryId`;`resolveEdges` 加分支从 `semi_finished_inventory` 解析+扣减(复用乐观锁);`resolveProcesses` 加"半成品起步"档(不强制 index0=修油)。依赖根因统一 WIP 模型。

### 6. (later) 月度出成率汇总 + 人工成本可见 — 功能请求,非紧急
- 盘点修库存时**不改历史成本**(盘盈当收入/盘亏当损耗,line 32),只在月度"阅读汇总"重算整体出成率(line 38-39:总投入原料/总产出/剩余半成品折算 → 真实总出成率)。
- 人工:没有"小结"看不到一盒人工多少钱(line 45-47),"不粘进去人工成本就算零"——依赖 #4 小结。

### 忽略项(客户明确说后面再说)
- NFC / R码 / MC芯片 按员工扫码报工(line 58-59);排班(line 60-62);小组长签到/签退人效(line 64-71)。**本轮不做。**

---

## 审计 → 行动(synthesis)

**全部 verify-first 对 origin/main 核实完毕(3 个独立 Explore agent)。** 客户认为"小改",实际是**一套"库存生产业态"功能** + 1 个真 bug,核心是打通生产↔库存的桥。

### 优先级分层

| # | 项 | 判定 | 规模 | 依赖 |
|---|---|---|---|---|
| **P0-bug** | #1 结单后批次看不到 | **真 bug** | 小(filter 口径 + planId 过滤) | 无 |
| **P1-架构** | 根因:统一/桥接两套 WIP 库存模型(MaterialBatch/WH-WKS ↔ SemiFinishedInventory) | 决策 | 中(keystone) | #2/#5 都依赖 |
| **P1** | #2 自动入半成品库+成品库(可带审核) | 缺失 | 中 | P1-架构 |
| **P1** | #4 逐道录入"小结"(入库+实质扣减+不关计划) | 缺失 | 中 | #2 + #3 |
| **P1** | #3 库存生产永续计划(BY_STOCK 模式) | 缺失 | 中 | 与 #4 共生 |
| **P2** | #5 半成品直接产C + 工序选半成品库 | 不通(3缺口) | 中 | P1-架构 |
| **P3** | #6 月度出成率汇总 / 人工成本view / 盘点不改历史 | 功能请求 | 中 | #4(小结)先有 |
| **忽略** | NFC/R码/排班/小组长签到 | 客户明说后面 | — | — |

### 关键架构决策(需 Steve 拍板,P1-架构)
两套 WIP 模型二选一/桥接:
- **方案A 桥接**:逐道录入物化半成品时同步 upsert `SemiFinishedInventory`(加 `postClerkOutput` 不依赖 WorkProcessTask)。改动局部,两表并存但同步。
- **方案B 统一视图**:`listWipByFactory` 改为 UNION `MaterialBatch(PRODUCTION_BATCH, WH-WKS)`,半成品库页直接读 WIP 批。少写一套,但 SemiFinishedInventory 的审核/成本字段要迁移。
- 决策影响 #2/#5 的实现路径,且涉及成本/库存正确性(🔒红线类),建议 Opus 设计 + 独立 review。

### 建议(给 Steve)
- **今天可立刻修**:#1(真 bug,小)——但要先确认客户期望"结单即出已完成生产批" vs "只是要半成品 WIP 可见",两者实现不同。
- **#2-#5 是一条线**(库存生产业态),建议作为一个小 sprint,先定 P1-架构方案(A/B),再 #3+#4(永续计划+小结)一起做(共生),再 #2(自动入库)、#5(半成品起步)。客户期望明后天——#1 + #3/#4 骨架可能赶得上,#5 较难。
- **未开工**:这是需求framing+审计交付,等 Steve 定方案A/B + 优先级再分发实现。
