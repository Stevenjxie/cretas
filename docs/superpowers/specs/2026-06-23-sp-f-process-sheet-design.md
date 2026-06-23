# SP-F 逐工序电子表格录入 — 设计 (Design Spec)

**日期**: 2026-06-23
**作者**: Opus organizer (brainstorm with Steve)
**状态**: Rev 2 (4 路对抗审计后修订) → 待 Steve review
**分支**: `feat/sp-f-process-sheet` (off `origin/main` @ d1b09c9f2)
**前置**: SP-A 配方 (#1066) / SP-B1 物化 (#1067) / SP-B2 抽屉 (#1068) / SP-C 核算 (#1069) / SP-D·E 加固 (#1071·#1076) 全已上线 prod。
**客户 mockup**: `production-cost-app(1).html` (张权「生产成本核算系统 V3.0」, localStorage 原型)。

> **Rev 2 修订摘要 (审计驱动)**: ①切片由 焯水+熟制 → **修油+焯水+熟制 3 道** (让原料领料+成本基准真在 per-row 路径跑通,Steve 拍板)。②65.7:34.3 是**整链比**,改为**切片专属期望值** (种子手算)。③materializeBatch **不是干净抽取**:上游解析两套策略 (内存 map vs 持久 batchNumber),改为**抽取收预解析边的 helper + 反转上游解析**。④re-save 改 **update-in-place 保 id** (删+重建会换 id 致下游 sourceDocId/batchId 悬空)。⑤per-row 熟制 `materialTypeId` **逐行从上游 MaterialBatch 再派生** (否则 SP-E FK 空重现)。⑥跨租户 finder 全 factory-scoped。⑦`saveRow` @Transactional + 并发 UK 冲突映射 409。⑧库存范围经 `process_sheet_rows` join (WIP planId=null 无法按 planId 范围)。⑨张权**成本分析页整页 defer** 随气调/单盒。

---

## 1. 背景与目标

张权 (六膳门) 给了自己做的 mockup —— 一套**逐工序电子表格**:每道工序一页,每页三联表 (数据录入 / 工时 / 半成品库存),靠批次号把上下游串成 DAG,末页气调装盒算单盒成本。

**关键判断**: 这套 mockup 的数据模型 = 我们 SP-A/B/C 后端**已经实现的模型**。mockup 的成本追溯 = 我们的 `OrderCostBreakdownService.traceCost`;出成率 = `getOrderYieldSummary`;调料配方表 = SP-A `product_recipes`。

**所以 SP-F 不重建后端业务逻辑**,但有**一处中度重构** + 两个新端点:
1. **前端**: 一个**配置驱动的通用 `<ProcessSheet>` 组件**,把现在的 `ProcessChainEntryDrawer`(SP-B2 一次性抽屉)**替换**成 mockup 那种逐工序电子表格。
2. **后端**: 一个**增量单行物化端点** + 一个**半成品库存读端点**,**复用 SP-B 物化写逻辑** (需把 recordChain 内循环重构成共享 helper) 和 SP-C 成本引擎。

**核心交互差异 (vs SP-B2 抽屉)**:
- 抽屉 = 一次性提交**整条链**(所有批次+步骤一个 payload),混锅来源用 payload 内 `clientBatchKey` 互指 (内存 map 解析)。
- 电子表格 = **每行存一次**(增量),一行 = 一个批次的一道工序;上下游靠**真实持久化的 batchNumber**(跨请求)串联,批次号系统生成、下游下拉选。

---

## 2. 范围 (Scope)

### 2.1 In scope (本 spec / SP-F)
- 配置驱动 `<ProcessSheet>` Vue 组件 (三联表),替换 `ProcessChainEntryDrawer`。
- 增量单行端点 `POST .../process-sheet/row` (update-in-place upsert)。
- 删除行端点 `DELETE .../process-sheet/row/{clientRowId}`。
- 半成品库存读端点 `GET .../process-sheet/inventory`。
- 已存行回读端点 `GET .../process-sheet/rows`。
- `process_sheet_rows` 行追踪表 + 几个新增 repo 方法 (factory-scoped finder + 按 productionBatchId 删) (Flyway)。
- **物化 helper 重构**: 抽 `materializeBatch(resolvedEdges, resolvedCtx)`,recordChain 改调它 (见 §3、§4.4)。
- **3 个随手 UI 修复** (见 §9):多锅 N>1 必填 (含小 UX) / 防呆 Rule1 提交 :disabled / 配方 validate 老汤豁免价格。
- 复用现有 by-batch yield-summary + cost-breakdown 做**切片成本核对** (kg 级,非张权成本分析页)。

### 2.2 垂直切片 (先跑通) —— **修油 + 焯水 + 熟制 3 道**
**3 道工序** (Steve 拍板,让原料领料+成本基准真在 per-row 路径端到端跑通):

| 工序 | 验证什么 | 上游 (切片折叠接线) |
|---|---|---|
| **修油** | 原料**领料** (rawMaterialInputs → RAW MaterialConsumption) + 原料成本基准入图 + WIP 批生成 | 无 (首道,直接消耗原料 MaterialBatch) |
| **焯水** | 单上游 WIP **库存扣减** + **剩余** + 出成率 | 修油 (切片折叠:真实链有滚揉在中间,后续加配置时恢复) |
| **熟制** | **混锅多来源**分摊 + 调料 (SEASONING) + labor + 桶分类 | 焯水 (切片折叠:真实链有去舌苔在中间) |

> **切片折叠说明**: 真实链是 修油→滚揉→焯水→去舌苔→熟制→气调。切片用 焯水←修油、熟制←焯水 的**折叠接线** (近似和牛牛腱变体再省滚揉),显式简化;滚揉/去舌苔/气调后续纯加配置 + 接线恢复。

**验收 (改 — 不再用 65.7:34.3 整链比)**: 65.7:34.3 是**全 6 道整链**的 material:labor 比 (memory M67),3 道切片**结构上达不到**。切片验收改为:
- (a) 修油写 RAW MaterialConsumption,原料批库存扣减,WIP 批 unitPrice = 原料成本/产出。
- (b) 焯水消耗修油 WIP (SEMI),修油 WIP 剩余正确扣减,焯水 WIP unitPrice = (上游分摊+labor)/产出。
- (c) 熟制混锅选 2+ 焯水批,多 MaterialConsumption 边,SEASONING 不落原料桶,熟制批 `computeByBatch` 桶分类正确。
- (d) **切片专属期望值**: 用种子数据 (原料单价/各道 labor 分钟·人数·工时单价/混锅 feedKg/配方) **手算**出预期 原料桶/labor桶/调料桶/单价,断言后端输出逐桶吻合 (容差 ±0.01)。**不锁 65.7:34.3** (那是全链铺好后的整链核对项)。

### 2.3 Out of scope (defer / 单独)
- **张权「成本分析页」整页 defer** (Steve 拍板): 调料配方表 + 包装成本表 + 单盒成本测算 → 随气调装盒/单盒成本阶段做。该页是 standalone per-box 计算器,单盒需气调装盒步,包装录入 UI 在 3 道切片无处安放。**切片只保留 in-graph 调料成本** (熟制 SEASONING 走 SP-A 配方),通过 by-batch kg 级 breakdown 展示。
- **汇总页 (出成率链 + per-process 成本追溯) defer 随全链**: mockup 汇总按气调成品批驱动,且 per-process **逐道 labor 列**需确认 `computeByBatch` 是否产出 (当前疑为单一人工桶,**建汇总页前先 verify,不假设**)。
- **操作记录 / 系统日志**审计追溯 defer (mockup 有 row-field-level diff 时间线;「复用现有 audit log」是**假设未验** —— 现有 audit 多为 entity 级,mockup 要行-字段级 diff,**建之前先验证**能否产出,否则是隐藏重建)。
- **气调装盒 + 单盒成本 + 留样/料头** (全 6 道配置铺好后)。
- **Q1 (注射成本基重) / Q6 (副产·肥油回收)** 成本引擎修复 → **独立 🔒 PR** (触 traceCost/成本正确性)。注: mockup 自己也不计副产 (xiuyou materialCost=0),切片 DTO **不带** byproduct/留样字段 (避免捕获却不计的语义漂移)。

---

## 3. 架构总览 (复用 + 重构地图)

| 层 | SP-F 改动 | 复用 / 重构 |
|---|---|---|
| 后端写 (核心 🔒) | **重构** `ClerkProcessEntryServiceImpl`: 抽 `materializeBatch(resolvedEdges, resolvedCtx)` —— 收**预解析上游边** `List<(MaterialBatch src, BigDecimal feedKg)>` + 预解析 ctx `(laborRate, warehouseId, rawMaterialTypeId)`。recordChain 内循环 (L139-244) 改调它 (上游边由内存 `wipMbIdByKey` 解析);新 `ProcessSheetService.saveRow` 也调它 (上游边由持久 batchNumber 解析)。**上游解析反转出 helper 外**,write 逻辑共享。 | recordChain 内的 `writeConsumption`/`createWipMaterialBatch`/`writeSeasoningReport`/`computeLaborCost`/`minutesBetween`/`resolveLaborRate`/`resolveWarehouseId` (语义复用;**5/6 现为 private** → 随 materializeBatch 一并提到可调可见性,或 materializeBatch 落在同类内) |
| 后端读 (库存) | `GET .../process-sheet/inventory` (经 `process_sheet_rows` join 限范围) | `MaterialBatchRepository` + `MaterialConsumptionRepository.findByFactoryIdAndBatchId` (factory-scoped) |
| 后端读 (回读) | `GET .../process-sheet/rows` | `process_sheet_rows` 表 |
| 后端读 (切片成本核对) | 无新代码 | `OrderCostBreakdownService.computeByBatch` (by-batch) |
| 前端 | `<ProcessSheet>` 通用组件 + `PROCESS_SHEET_CONFIG` 配置 | 替换 `ProcessChainEntryDrawer.vue`;mount 点 `production/plans/list.vue` |
| 配方 | 无新代码 (切片只用 in-graph SEASONING) | SP-A `product_recipes` (熟制调料成本) |

> **重构成本诚实说明** (审计指正): 这**不是**「抽取一个已经干净的单元」。recordChain 的内循环耦合于 ①内存 `wipMbIdByKey` + topo 预排序 ②hoisted 的 laborRate/warehouseId ③`BatchEntry` DTO。真改 = 反转上游解析 (push 到 helper 外) + 适配新 DTO + 重新穿 3 个 ctx 参数。**中度重构,F1 留 Opus 自做** (§13)。

**成本图铁律 (沿用,不可破)**: traceCost 只递归 `MaterialConsumption` + `MaterialBatch.source_doc_type='PRODUCTION_BATCH'`。调料走 `cost_category='SEASONING'` 报工 (**且必显式 set costCategory**,不靠 idx 启发式 —— per-row `steps.size()` ≠ 整链)。成本边 `totalCost = 投料kg × 上游单价`。WIP 批 `productionPlanId=null` + `batch_type='CLERK_WIP'`。

---

## 4. 后端: 增量单行端点

### 4.1 端点
```
POST /api/mobile/{factoryId}/production-plans/{planId}/process-sheet/row
@RequirePermission({"production:read_write"})
@RequestAttribute("userId") Long userId
@Transactional   // ← 整 saveRow 一事务 (resolve + materialize + 写 process_sheet_rows)
```
**注意**: 与 SP-B 的 `.../process-entry` 并存 (抽屉前端移除后该端点保留作 AI tool 路径;`ProcessEntryIdempotency` 表仍服务 recordChain,**不删**)。

### 4.2 请求 DTO (`ProcessSheetRowRequest`)
一行 = 一个批次的一道工序。上游引用走 **batchNumber** (跨请求):

```java
@Data
public class ProcessSheetRowRequest {
    @NotBlank private String clientRowId;     // 客户端稳定行 id (upsert 键)
    @NotBlank private String processCode;     // "xiuyou" | "chaoshui" | "shuzhi" | ...
    @NotNull  private Integer processOrder;
    private String processName;
    @NotBlank private String productTypeId;
    private String batchNumber;               // 可空: 首存系统生成 (CLK-W-/CLK-B-)
    private boolean finished;                 // 切片内均 false (未到气调成品批)

    private BigDecimal inputQuantity;
    @NotNull private BigDecimal outputQuantity;   // 见 §4.4: 仅 >0 才物化 WIP 批
    private String unit;                      // 默认 "kg"

    private List<LaborSegment> laborSegments; // 多时段, 后端 Σ (新 wrapper, 见 §4.5)
    private List<RawInput> rawMaterialInputs; // 领料 (修油首道): 消耗原料 MaterialBatch
    private List<UpstreamRef> upstreamSources;// 混锅: 按真实 batchNumber 引用
    private Integer potCount;                 // 锅数 N
    private List<BigDecimal> potRawKgs;       // 逐锅原料 (N>1 必填, §9)
    private boolean seasoningStep;            // 触发 RecipeCostCalculator
    private String idempotencyKey;            // 可选防双击 (同 clientRowId 一次保存内)
    // ⚠️ 切片不带 byproducts / sampleRetainQuantity (defer 随 Q6/气调, §2.3)

    @Data public static class LaborSegment { String startTime; String endTime; Integer workerCount; }
    @Data public static class RawInput { @NotBlank String materialBatchId; @NotNull BigDecimal quantity; }
    @Data public static class UpstreamRef { @NotBlank String sourceBatchNumber; @NotNull BigDecimal feedQuantityKg; }
}
```

### 4.3 响应 DTO (`ProcessSheetRowResult`)
```java
@Data
public class ProcessSheetRowResult {
    private String clientRowId;
    private Long batchId;
    private String batchNumber;        // 系统生成/确认 (下游下拉用它)
    private BigDecimal yieldRate;       // outputQty/inputQty ×100
    private BigDecimal rowTotalCost;    // 该行物化成本 (kg 级)
    private BigDecimal unitPrice;       // rowTotalCost / outputQty (= WIP 批单价)
    private boolean updated;            // true=覆盖已有行 (update-in-place), false=新建
    private boolean materialized;       // false = outputQty<=0, 未生成 WIP 批 (非法上游)
    private List<String> warnings;      // 调料配方缺失 / 超量软预警 / labor rate fallback
}
```

### 4.4 物化语义 (update-in-place upsert, @Transactional)

`materializeBatch(resolvedEdges, resolvedCtx)` 是 recordChain 与 saveRow 共享的写核心。saveRow 流程:

1. **解析 planId 归属工厂** (`productionPlanRepository.findByIdAndFactoryId`,跨租户守卫)。
2. **解析上游边 (factory-scoped, 🔒)**: 每个 `upstreamSources[i].sourceBatchNumber`:
   - `productionBatchRepository.findByFactoryIdAndBatchNumber(factoryId, bn)` → ProductionBatch (找不到 → 409「上游批次 X 不存在」)。
   - → 其 WIP MaterialBatch: **新增 factory-scoped 方法** `MaterialBatchRepository.findByFactoryIdAndSourceDocTypeAndSourceDocId(factoryId, "PRODUCTION_BATCH", batchId)` (**不用** 非 factory-scoped 的 `findByBatchNumber`)。
   - 防御: 断言 `srcMb.factoryId == factoryId`。
   - 产出 `resolvedEdges = List<(MaterialBatch src, feedKg)>`。
3. **解析 ctx**: `laborRate=resolveLaborRate(factoryId)`;`warehouseId=resolveWarehouseId(factoryId)`;**`rawMaterialTypeId`** = (有 rawMaterialInputs → 取首个原料的 materialTypeId) **否则从 resolvedEdges 的上游 `src.getMaterialTypeId()` 取** (镜像 recordChain L186-188)。**非空断言** (空 → 400,SP-E FK 防线;见 §8.3 真 PG 测)。
4. **upsert by `(factory, plan, processCode, clientRowId)`** (查 `process_sheet_rows`):
   - **新建**: `outputQty<=0` → 只写 `process_sheet_rows` (status=DRAFT, materialized=false),**不**物化 WIP 批 (非法上游,下游不可选)。`outputQty>0` → 系统生成 batchNumber → `createProductionBatch`(`batchType=CLERK_WIP`,`planId=null`) → `materializeBatch`(写 raw/上游 `MaterialConsumption` `totalCost=qty×上游单价` + SEASONING 报工 [若 seasoningStep,**显式 costCategory=SEASONING**] + labor 报工 [Σ segments]) → `createWipMaterialBatch`(`unitPrice=rowTotalCost/outputQty`,`materialTypeId=rawMaterialTypeId`,`source_doc_type=PRODUCTION_BATCH`,`source_doc_id=batchId`)。
   - **覆盖 (re-save) = update-in-place 保 id**: **先校验该行 WIP 批无下游消耗** (`MaterialConsumptionRepository.findByFactoryIdAndBatchId(factoryId, wipMbId)` 空) → 否则 **409 + actionHint「该批已被下游 N 行消耗,请先删除下游行再改」** (fool-proof Rule5)。无消耗 → **保留 ProductionBatch.id + WIP MaterialBatch UUID 不变**,只: 删该行旧成本边 (新增 `MaterialConsumptionRepository.deleteByFactoryIdAndProductionBatchId`) + 删旧 SEASONING/labor 报工 (新增 `ProductionReportRepository.deleteByFactoryIdAndBatchId`) → 按新值重写边/报工 → **更新** (非重建) WIP 批的 receiptQuantity/unitPrice。**保 id 消除下游 sourceDocId/batchId 悬空风险。**
5. **写/更新 `process_sheet_rows`** (原始 row payload JSON + batch_id/batch_number + 状态)。
6. **并发**: UK `(factory,plan,processCode,clientRowId)` 冲突 (双 POST) → catch `DataIntegrityViolationException` → 409 或幂等读已有行返回 (不留孤儿;整事务回滚 loser 的物化图)。
7. 返回 `ProcessSheetRowResult`。

**为什么用行追踪表**: 电子表格要**原样回读用户录入** (重开/编辑);从图反推有损。`process_sheet_rows` 是表格真相源,物化图是其投影 —— 故 saveRow **必须 @Transactional** 保两者原子提交 (否则图有 WIP 批而表无行 → 库存显示无法解释的批 + re-save 找不到行)。

### 4.5 成本边 / labor 公式 (沿用 + 新 wrapper)
```
MaterialConsumption.totalCost = quantity × unitPrice
  RAW:        quantity=rawInput.quantity,  unitPrice=rawBatch.unitPrice,        sourceType=RAW_MATERIAL
  SEMI(混锅): quantity=feedKg,             unitPrice=上游 WIP 批.unitPrice,      sourceType=SEMI_FINISHED
WIP MaterialBatch.unitPrice = rowTotalCost / outputQuantity
labor_cost = Σ_segment( minutesBetween(start,end) × workerCount / 60 ) × hourlyRate
```
`computeLaborCost` 现为单段读 `StepEntry`;新增 `computeLaborCost(List<LaborSegment>, rate)` wrapper 循环求和 (复用 public `minutesBetween`)。下游 traceCost 按 `consumedQty/upstreamReceiptQty` 比例分摊 (已有,不动;**read-time 重导**,故上游 re-save 改单价在「无下游消耗」guard 下不会污染已物化下游 —— 这是设计稳健点)。

### 4.6 删除行端点
```
DELETE .../process-sheet/row/{clientRowId}    @Transactional
```
先校验无下游消耗 (`findByFactoryIdAndBatchId` 空) → 反向删除该行**全部**物化产物 (按 productionBatchId: MaterialConsumption + ProductionReport;按 sourceDocId: WIP MaterialBatch;ProductionBatch) + `process_sheet_rows` 行。有下游 → 409 + actionHint。

---

## 5. 后端: 半成品库存读端点

```
GET /api/mobile/{factoryId}/production-plans/{planId}/process-sheet/inventory?process={code}
```
**范围必经 `process_sheet_rows` join** (WIP `productionPlanId=null`,无法按 planId 直接过滤):
```
本 plan 链的 WIP 批 = process_sheet_rows WHERE factory_id=? AND plan_id=? [AND process_code=?]
                      → batch_id → ProductionBatch → WIP MaterialBatch (sourceDocId)
对每个 WIP MaterialBatch:
  produced  = MaterialBatch.receiptQuantity
  used      = Σ MaterialConsumption.quantity  (findByFactoryIdAndBatchId(factoryId, wipMbId))  ← factory-scoped 🔒
  remaining = produced - used
  status    = remaining<=0 ? DEPLETED : ACTIVE
  unitPrice = MaterialBatch.unitPrice
```
返回 `[{batchNumber, produced, used, remaining, status, unitPrice}]`。供库存子表 + **下游上游下拉** (只列 `materialized && remaining>0`)。`used` 准确性依赖 §4.4 re-save 边清理 (按 productionBatchId 删旧边),否则旧边残留致 `used` 多计。

---

## 6. 前端: 配置驱动 `<ProcessSheet>` 组件

### 6.1 组件结构
替换 `ProcessChainEntryDrawer.vue`。一个工序页 = 一个 `<ProcessSheet :process="code">`,三联表: 数据录入 / 工时 (多时段 Σ) / 半成品库存 (读 §5)。

### 6.2 配置描述符 (`PROCESS_SHEET_CONFIG`) —— 切片 3 道
```ts
type ColType = 'dropdown'|'number'|'date'|'auto'|'readonly'|'text';
type AutoCalc = 'yield'|'remaining'|'totalHours';   // ⚠️ 见下: 去舌苔/气调需新类型
interface ColDef { key:string; label:string; type:ColType; upstream?:string; autoCalc?:AutoCalc; }

const PROCESS_SHEET_CONFIG: Record<string, ColDef[]> = {
  // 修油 (首道, 原料领料) —— 切片新加
  xiuyou: [ {key:'rawBatch',type:'dropdown',label:'原料批次'},      // 选原料 MaterialBatch (→rawMaterialInputs)
            {key:'outWeight',type:'number',label:'出库重量(kg)'},
            {key:'batch',type:'readonly',label:'修油批次'},          // 系统生成
            {key:'prodDate',type:'date',label:'生产日期'},
            {key:'output',type:'number',label:'产出数量(kg)'},
            {key:'feedWeight',type:'auto',label:'投料重量(kg)'},
            {key:'yieldRate',type:'auto',autoCalc:'yield',label:'出成率(%)'},
            {key:'totalHours',type:'auto',autoCalc:'totalHours',label:'总工时(h)'} ],
            // 注: 肥油(byproduct) 列切片不录 (defer Q6)
  chaoshui: [ {key:'upstreamBatch',type:'dropdown',upstream:'xiuyou',label:'修油批次'}, // 切片折叠 (真实=滚揉)
              {key:'batch',type:'readonly',label:'焯水批次'},
              {key:'date',type:'date',label:'焯水日期'},
              {key:'before',type:'number',label:'焯水前(kg)'},
              {key:'after',type:'number',label:'焯水后(kg)'},
              {key:'yieldRate',type:'auto',autoCalc:'yield',label:'出成率(%)'},
              {key:'remain',type:'auto',autoCalc:'remaining',label:'剩余量(kg)'},   // 派生(只读), 见 §6.3
              {key:'totalHours',type:'auto',autoCalc:'totalHours',label:'总工时(h)'} ],
  shuzhi: [ {key:'upstreamBatch',type:'dropdown',upstream:'chaoshui',label:'焯水批次(混锅)'}, // 切片折叠 (真实=去舌苔)
            {key:'batch',type:'readonly',label:'熟制批次'},
            {key:'date',type:'date',label:'日期'},
            {key:'input',type:'number',label:'投入(kg)'},
            {key:'output',type:'number',label:'产出(kg)'},
            {key:'yieldRate',type:'auto',autoCalc:'yield',label:'出成率(%)'},
            {key:'remain',type:'auto',autoCalc:'remaining',label:'剩余(kg)'},
            {key:'totalHours',type:'auto',autoCalc:'totalHours',label:'总工时(h)'} ],
};
```
> **「纯加配置铺全 6 道」不完全成立** (审计指正): 滚揉可纯加配置;但 **去舌苔** (投入=碎肉+产出,反推) 和 **气调** (实际生产=入库+留样+剩余+领用、总重=成品重+料头、单盒克重/每盒人工费) 需**新增 `AutoCalc` 公式类型**,非纯配置。`AutoCalc` enum 设计成可扩展。

### 6.3 关键交互
- **上游批次下拉**: 列上游工序 `materialized && remaining>0` 批次 (读 §5),显「批次号 · 剩余 Xkg」。选中带默认投入 = remaining (可改)。
- **批次号**: 本行输出批次号**只读**,首存系统生成 (端点返回),作下游下拉项。
- **混锅 (熟制)**: 上游下拉多选 (`+来源批`,每行 batchNumber + feedKg),映射 `upstreamSources[]`。
- **剩余/已用 = 派生权威 (只读)** (审计 F-1 修正): mockup 有手填「下游用量」列 + 派生 used 两套真相,会发散。**去掉手填,`remain` 由后端库存端点 (§5) 派生只读** —— 单一真相。
- **自动字段** (客户端算供即时反馈): 出成率=产出/投入×100;总工时=Σ(时段时长×人数)。**剩余从后端取**;**权威成本来自后端响应**,前端不算成本。
- **超量软预警** (fool-proof Rule1): feedKg/投入 > 上游剩余 → 黄字「超出上游剩余 Xkg」,**不硬阻断**,但提交前显示。
- **保存**: 行级 `+保存` 增量 POST (§4)。乐观更新 + 后端 reconcile。

### 6.4 订单/计划映射
mockup「订单管理」= 存货生产**计划列表** (`production/plans/list.vue`)。SP-F **不做新建计划流** (复用现有创建)。「进入生产」→ 打开该计划 `<ProcessSheet>`。存货生产无订单号,以 batchNumber 贯穿。

---

## 7. 切片成本核对 (非张权成本分析页)

**张权成本分析页 (调料/包装/单盒) 整页 defer** (§2.3)。切片只做 **in-graph 成本核对**: 熟制批号调 `OrderCostBreakdownService.computeByBatch(factoryId, batchNumber)` → 桶分类 (原料/人工/调料) + kg 级单价,前端简表展示并与 §2.2(d) 手算期望值对照。
- **汇总页 (per-process labor 列 + 出成率链) defer**: 建之前**先 verify** `computeByBatch` 是否产出**逐道 labor 桶** (疑为单一人工桶);`getOrderYieldSummary` 是否产出**逐道出成率**。不假设,验证后再决定汇总页形态。

---

## 8. 垂直切片实施 (修油 + 焯水 + 熟制)

### 8.1 种子 (DEMO_FACTORY, df_admin/123456)
- 复用现有计划 `PLAN-1782200389848-55F1F7C7` 或新建 demo 存货生产计划。
- 种原料 MaterialBatch (含 unitPrice,作修油领料源) + 熟制调料 (SP-A 配方已配 DF_pt10)。**修油自身在切片内录** (产出 WIP),不再预种修油/滚揉 WIP。授权 seed,纯 ASCII 避 mojibake,**绝不碰 F006/LIUSHANMEN**。

### 8.2 验证链路
1. **修油**: 选原料批 → 录出库/产出 + 工时 → 保存 → 验 (a) RAW MaterialConsumption + 原料批扣减 + 修油 WIP 批 unitPrice=原料成本/产出。
2. **焯水**: 选修油批 → 录焯水前/后 + 工时 → 保存 → 验 (b) SEMI MaterialConsumption + 修油 WIP 剩余扣减 + 焯水 WIP unitPrice。
3. **熟制**: 选 2+ 焯水批 + 调料 → 录投入/产出 + 工时 → 保存 → 验 (c) 混锅多边 + SEASONING 不落原料桶 + 桶分类。
4. **成本核对**: 熟制批 `computeByBatch` → 逐桶吻合 §2.2(d) **种子手算期望值** (±0.01)。**不锁 65.7:34.3** (整链项)。
5. **edit/delete 验证**: re-save 焯水行 (无下游) 成功 + 修油 WIP `used` 不变 (边清理正确);re-save 修油行 (焯水已消耗) → 409;delete 同理。

### 8.3 真实 PG 验证 (关键, SP-E 回归)
WIP 批 `materialTypeId` FK 撞 `raw_material_types` (SP-E 真踩,H2 漏)。**per-row 熟制行无 rawInputs,materialTypeId 必从上游 WIP 派生** (§4.4 步3) —— 后端集成测**对真 PG schema 验 FK 非空** (rollback-replay 或 test 环境真写),专测「熟制行 materialTypeId 来自上游 WIP」路径。

---

## 9. 随 SP-F 的 3 个 UI 修复

| # | 修复 | 实施 |
|---|---|---|
| 多锅必填 (含 UX) | `potCount>1` 时 `potRawKgs` 必填 | **注: mockup 无锅概念,这是 Cretas/SP-A 扩展** → 不止校验,需小 UX: N>1 时**预先展示 N 个逐锅 kg 输入框** (fool-proof Rule1) + 前端 validate + 后端 `materializeBatch` 守卫 (N>1 缺 → 400) |
| 防呆 Rule1 | 提交按钮超限/非法时 `:disabled` | `<ProcessSheet>` 提交按钮绑 `:disabled="overLimit||invalid"` |
| 配方老汤豁免 | 配方 validate 不强制 `count_in_seasoning=false` 老汤填价格 | validate 跳过老汤行价格必填 |

(Q1/Q6 = 成本引擎,独立 🔒 PR。)

---

## 10. 数据模型 / Flyway

### 10.1 新表 `process_sheet_rows`
```sql
CREATE TABLE process_sheet_rows (
  id BIGSERIAL PRIMARY KEY,
  factory_id VARCHAR NOT NULL,
  plan_id VARCHAR NOT NULL,
  process_code VARCHAR NOT NULL,
  client_row_id VARCHAR NOT NULL,
  batch_id BIGINT,                 -- 物化的 ProductionBatch.id (保 id, re-save 不变)
  batch_number VARCHAR,
  row_payload JSONB NOT NULL,      -- 原始录入 (回读)
  row_status VARCHAR DEFAULT 'SAVED',  -- DRAFT(outputQty<=0未物化) | SAVED
  created_at TIMESTAMP DEFAULT NOW(),
  updated_at TIMESTAMP DEFAULT NOW(),
  deleted_at TIMESTAMP NULL,
  CONSTRAINT uk_sheet_row UNIQUE (factory_id, plan_id, process_code, client_row_id)
);
```
`uk_sheet_row` = upsert 结构性去重 (并发双 POST → DataIntegrityViolation → §4.4 步6 映射 409,**非响应重放**;recordChain 仍用 `ProcessEntryIdempotency` 做响应重放,不受影响)。

### 10.2 新增 repo 方法 (审计补)
- `MaterialBatchRepository.findByFactoryIdAndSourceDocTypeAndSourceDocId(factoryId, type, sourceDocId)` (混锅上游 WIP 查,factory-scoped)。
- `MaterialConsumptionRepository.findByFactoryIdAndBatchId(factoryId, batchId)` (库存 used + re-save guard,**已存在**确认复用;若无则加)。
- `MaterialConsumptionRepository.deleteByFactoryIdAndProductionBatchId(factoryId, productionBatchId)` (re-save/delete 删该行边)。
- `ProductionReportRepository.deleteByFactoryIdAndBatchId(factoryId, batchId)` (re-save/delete 删该行报工)。

### 10.3 Flyway 版本
下一空号 **预检** (并发 session 撞号高发,memory 实证 27_05/27_07 双号阻断):
```bash
git ls-tree -r origin/main --name-only | grep flyway | grep -oE 'V[0-9_]+' | sort | uniq -d   # 查重号
# 当前最新 V20261027_09 → 取 V20261027_10 (或 V20261028_01 跨日)
```
出 PR / 部署前再查一次重号。

---

## 11. 测试

### 11.1 后端集成测 (真 PG)
- 修油单行 → RAW consumption + 原料批扣减 + WIP 批 unitPrice。
- 焯水单行 → SEMI consumption + 上游剩余扣减。
- 熟制混锅多源 → 多边 + SEASONING 报工 (显式 costCategory) + 桶分类。
- **熟制 materialTypeId 来自上游 WIP** + 真 PG FK 非空 (SP-E 回归)。
- re-save (update-in-place 保 id): 无下游成功 + 边/报工正确替换 + `used` 不重复计;有下游 409。
- delete: 无下游全删干净 + 有下游 409。
- 库存派生 (§5, 经 process_sheet_rows join + factory-scoped used)。
- 跨租户: 别工厂 planId/batchNumber/混锅上游 → 403/404。
- 并发双 POST 同 clientRowId → 一成一 409, 无孤儿图 (事务回滚)。

### 11.2 前端组件测
- 配置渲染 (修油/焯水/熟制) 列正确。
- 上游下拉只列 `materialized && remaining>0`。
- 剩余只读派生 (非手填)。
- 超量软预警。
- 多锅 N>1 预展示逐锅输入 + 提交 :disabled 防呆。

### 11.3 E2E (headed, DEMO_FACTORY)
- df_admin headed (`PLAYWRIGHT_PORT`/`CHAT_ID` 隔离,headless:false,zh-CN) 走 修油→焯水→熟制 完整链,截图验三联表 + 成本核对吻合手算期望。
- 绝不碰 F006/LIUSHANMEN。

---

## 12. 隔离 / 红线 / 流程

- **worktree off origin/main**: `feat/sp-f-process-sheet` @ `C:\Users\Steve\cretas-sp-f` (已建;旧 `feat+worktree-session` stale 不用)。
- **scope-locked commit**: `git commit -- F1 F2` 或 `safe-commit.sh`;PR 前 `git diff origin/main...HEAD --stat` 确认 scope 干净。
- **🔒 红线**: materializeBatch 重构 + 增量物化触 traceCost 成本图 + 跨租户守卫 + 新 Flyway → **Opus 终审**,从 main 部署。执行层只做到「实现+自测+PR」。
- **DEMO_FACTORY only**: df_admin/123456,绝不碰 F006/LIUSHANMEN。
- **Flyway 防撞号** (§10.3);**真 PG 验 FK** (§8.3)。

---

## 13. 分发卡 (Multi-Model Dispatch)

| # | 任务 | 模型 | effort | orchestration | 分支/scope | 🔒 |
|---|---|---|---|---|---|---|
| F1 | **materializeBatch 重构** (反转上游解析 + 适配新 DTO + 3 ctx 参数) + `process_sheet_rows` Flyway + 单行/删除端点 (update-in-place 保 id + 跨租户 + 事务) | **Opus 自做** (判断密集 🔒: 物化图/upsert 反向删/跨租户/成本边/SP-E FK 派生) | xhigh | inline 或 Opus subagent | `service/processentry/`, `controller/`, `db/flyway/`, `dto/` | 🔒 |
| F2 | 库存读端点 + 回读端点 + 新增 repo 方法 (factory-scoped finder/delete) | Sonnet in-harness | high | subagent | `service/processentry/`, `repository/` | |
| F3 | `<ProcessSheet>` 通用组件 + `PROCESS_SHEET_CONFIG` (修油/焯水/熟制) + 替换 drawer + 派生剩余只读 + 超量软预警 | Sonnet/Composer | high/default | subagent | `web-admin/src/views/production/` | |
| F4 | 3 个 UI 修复 (§9, 含多锅 UX) | Sonnet/Composer | default | subagent | 对应文件 | |
| F5 | 后端集成测 (真 PG, 含 SP-E FK + re-save + 并发) + E2E headed | Sonnet in-harness | high | subagent | `test/`, e2e spec | |
| 终审 | PR diff 终审 + 从 main 部署 | **Opus** (出货闸) | xhigh | 单 subagent (read-only) | — | 🔒 |

依赖: F3 依赖 F1 端点契约;F2 依赖 F1 (materializeBatch + 表);F5 依赖 F1-F3。F1 (🔒 重构/成本/跨租户/FK) Opus 自做不下压 —— 审计确认这是判断密集的依赖反转,非纯抽取。

---

## 附: 已决假设
- 工时多时段存 `process_sheet_rows.row_payload` JSON;物化只取 Σ 总工时算 labor (个体时段不进成本图)。
- 计划「进入生产」复用 `production/plans/list.vue` 行操作,替换原抽屉触发。
- 切片成品批 `finished=false` (未到气调);单盒成本 + 张权成本分析页 + 汇总页 + 操作记录 全 defer。
- 切片折叠接线 (焯水←修油, 熟制←焯水);滚揉/去舌苔后续加配置 + 恢复接线。
