# SP-F 逐工序电子表格录入 — 设计 (Design Spec)

**日期**: 2026-06-23
**作者**: Opus organizer (brainstorm with Steve)
**状态**: Draft → 待 Steve review
**分支**: `feat/sp-f-process-sheet` (off `origin/main` @ d1b09c9f2)
**前置**: SP-A 配方 (#1066) / SP-B1 物化 (#1067) / SP-B2 抽屉 (#1068) / SP-C 核算 (#1069) / SP-D·E 加固 (#1071·#1076) 全已上线 prod。
**客户 mockup**: `production-cost-app(1).html` (张权「生产成本核算系统 V3.0」, localStorage 原型)。

---

## 1. 背景与目标

张权 (六膳门) 给了自己做的 mockup —— 一套**逐工序电子表格**:每道工序一页,每页三联表 (数据录入 / 工时 / 半成品库存),靠批次号把上下游串成 DAG,末页气调装盒算单盒成本。

**关键判断**: 这套 mockup 的数据模型 = 我们 SP-A/B/C 后端**已经实现的模型**。mockup 的成本追溯 = 我们的 `OrderCostBreakdownService.traceCost`;出成率 = `getOrderYieldSummary`;调料配方表 = SP-A `product_recipes`。

**所以 SP-F 不重建后端逻辑**,只做两件事:
1. **前端**: 一个**配置驱动的通用 `<ProcessSheet>` 组件**,把现在的 `ProcessChainEntryDrawer`(SP-B2 一次性抽屉)**替换**成 mockup 那种逐工序电子表格。
2. **后端**: 一个**增量单行物化端点** + 一个**半成品库存读端点**,复用 SP-B 的物化 helper 和 SP-C 的成本引擎。

**核心交互差异 (vs SP-B2 抽屉)**:
- 抽屉 = 一次性提交**整条链**(所有批次+步骤一个 payload),混锅来源用 payload 内 `clientBatchKey` 互指。
- 电子表格 = **每行存一次**(增量),一行 = 一个批次的一道工序;上下游靠**真实持久化的 batchNumber**(跨请求)串联,批次号系统生成、下游下拉选。

---

## 2. 范围 (Scope)

### 2.1 In scope (本 spec / SP-F)
- 配置驱动 `<ProcessSheet>` Vue 组件 (三联表),替换 `ProcessChainEntryDrawer`。
- 增量单行端点 `POST .../process-sheet/row` (upsert 语义)。
- 半成品库存读端点 `GET .../process-sheet/inventory`。
- 已存行回读端点 `GET .../process-sheet/rows`。
- `process_sheet_rows` 行追踪表 (Flyway)。
- **3 个随手 UI 修复** (见 §9):多锅 N>1 必填 / 防呆 Rule1 提交 :disabled / 配方 validate 老汤豁免价格。
- 复用现有 by-batch yield-summary + cost-breakdown 做汇总/成本视图。

### 2.2 垂直切片 (先跑通)
**焯水 + 熟制 2 道工序**,验证: 领料 / 半成品库存扣减 / 混锅多来源 / 剩余量 / 成本链路。验收锁 **kg 级**成本 material:labor ≈ **65.7 : 34.3** (memory M67 参考)。跑通后**纯加配置**铺全 6 道。

### 2.3 Out of scope (defer / 单独)
- **操作记录 / 系统日志**审计追溯 (mockup 有,Steve 确认 defer;数据模型稳定后单独加,可复用现有 audit log)。
- **气调装盒 + 单盒成本** (单盒需气调装盒步,全 6 道配置铺好后落地;切片只到 kg 级)。
- **Q1 (注射成本基重) / Q6 (副产·肥油回收)** 成本引擎修复 → **独立 🔒 PR** (触 traceCost/成本正确性,不混进前端切片 diff)。

---

## 3. 架构总览 (复用地图)

| 层 | SP-F 改动 | 复用现有 |
|---|---|---|
| 后端写 | `ProcessSheetController.saveRow` + `ProcessSheetService` (新) | `ClerkProcessEntryServiceImpl` 的 `writeConsumption` / `createWipMaterialBatch` / `writeSeasoningReport` / `computeLaborCost` / `resolveLaborRate` / `resolveWarehouseId` (全 stateless,可单行调用) |
| 后端读 (库存) | `GET .../process-sheet/inventory` | `MaterialBatchRepository` + `MaterialConsumptionRepository.findByBatchId` |
| 后端读 (回读) | `GET .../process-sheet/rows` | `process_sheet_rows` 表 |
| 后端读 (汇总/成本) | 无新代码 | `OrderCostBreakdownService.computeByBatch` + `getOrderYieldSummary` (by-batch 端点) |
| 前端 | `<ProcessSheet>` 通用组件 + `PROCESS_SHEET_CONFIG` 配置 | 替换 `ProcessChainEntryDrawer.vue`;mount 点 `production/plans/list.vue` |
| 配方 | 无新代码 | SP-A `product_recipes` (成本分析页调料表读它) |

**成本图铁律 (沿用,不可破)**: traceCost 只递归 `MaterialConsumption` + `MaterialBatch.source_doc_type='PRODUCTION_BATCH'`。调料走 `cost_category='SEASONING'` 报工 (不写 MaterialConsumption,否则落原料桶)。成本边 `totalCost = 投料kg × 上游单价`。WIP 批 `productionPlanId=null` + `batch_type='CLERK_WIP'`。

---

## 4. 后端: 增量单行端点

### 4.1 端点
```
POST /api/mobile/{factoryId}/production-plans/{planId}/process-sheet/row
@RequirePermission({"production:read_write"})
@RequestAttribute("userId") Long userId
```
**注意**: 与 SP-B 的 `.../process-entry` 并存 (抽屉前端移除后端点可留作 AI tool 路径);SP-F 走新 `.../process-sheet/row`。

### 4.2 请求 DTO (`ProcessSheetRowRequest`)
一行 = 一个批次的一道工序。新 DTO,复用 `RawInput`/`Byproduct` 概念但**上游引用走 batchNumber**:

```java
@Data
public class ProcessSheetRowRequest {
    @NotBlank private String clientRowId;     // 客户端稳定行 id (upsert 键)
    @NotBlank private String processCode;     // "chaoshui" | "shuzhi" | ...
    @NotNull  private Integer processOrder;   // 工序序号 (来自 ProductWorkProcess)
    private String processName;
    @NotBlank private String productTypeId;
    private String batchNumber;               // 可空: 首存系统生成 (CLK-W-/CLK-B-)
    private boolean finished;                 // true=成品批 (气调装盒);切片内均 false

    private BigDecimal inputQuantity;
    @NotNull private BigDecimal outputQuantity;
    private String unit;                      // 默认 "kg"

    // 工时: 多时段 (mockup 工时表) → 后端 Σ
    private List<LaborSegment> laborSegments;

    // 领料 (首道): 消耗原料 MaterialBatch
    private List<RawInput> rawMaterialInputs;

    // 混锅 (熟制): 上游来源, 按真实 batchNumber 引用 (跨请求)
    private List<UpstreamRef> upstreamSources;
    private Integer potCount;                 // 锅数 N
    private List<BigDecimal> potRawKgs;       // 逐锅原料 (N>1 必填, §9)

    // 调料 (SEASONING 工序): 触发 RecipeCostCalculator
    private boolean seasoningStep;

    // 产出附加
    private List<Byproduct> byproducts;
    private BigDecimal wasteQuantity;
    private Integer sampleRetainQuantity;
    private String idempotencyKey;            // 防双击 (可选, 同 clientRowId 一次保存内)

    @Data public static class LaborSegment {
        private String startTime; private String endTime; private Integer workerCount;
    }
    @Data public static class RawInput {
        @NotBlank private String materialBatchId; @NotNull private BigDecimal quantity;
    }
    @Data public static class UpstreamRef {
        @NotBlank private String sourceBatchNumber;   // 真实持久化批次号
        @NotNull  private BigDecimal feedQuantityKg;
    }
    @Data public static class Byproduct {
        private String name; private BigDecimal quantity; private String unit; private BigDecimal unitPrice;
    }
}
```

### 4.3 响应 DTO (`ProcessSheetRowResult`)
```java
@Data
public class ProcessSheetRowResult {
    private String clientRowId;
    private Long batchId;
    private String batchNumber;        // 系统生成/确认 (下游下拉用它)
    private BigDecimal yieldRate;       // 派生 outputQty/inputQty ×100
    private BigDecimal rowTotalCost;    // 该行物化成本 (kg 级)
    private BigDecimal unitPrice;       // rowTotalCost / outputQty (= WIP 批单价)
    private boolean updated;            // true=覆盖已有行, false=新建
    private List<String> warnings;      // 调料配方缺失 / 超量软预警 / labor rate fallback
}
```

### 4.4 物化语义 (upsert)

**单行物化 = recordChain 的「单 BatchEntry」内循环**,抽取成 `materializeBatch(...)` helper 供两边共用:

1. **解析 planId 归属工厂** (跨租户守卫,`findByIdAndFactoryId`)。
2. **解析上游** (混锅): 每个 `upstreamSources[i].sourceBatchNumber` → `productionBatchRepository.findByFactoryIdAndBatchNumber` → 取其 WIP MaterialBatch (`source_doc_id`)。找不到 → 409「上游批次 X 不存在」。
3. **upsert by `(factory, plan, processCode, clientRowId)`** (查 `process_sheet_rows`):
   - **新建**: 系统生成 batchNumber (沿用 CLK-W-/CLK-B- 前缀) → 创建 `ProductionBatch`(`batchType=CLERK_WIP`,`planId=null` for WIP) → 写 raw/上游 `MaterialConsumption` 边 (`totalCost=qty×上游单价`) → 写 SEASONING 报工 (若 seasoningStep) → 写 labor 报工 (Σ segments) → 创建 WIP `MaterialBatch`(`unitPrice=rowTotalCost/outputQty`,`materialTypeId`=链上原料类型 FK,见 SP-E bug)。
   - **覆盖 (re-save)**: **先校验该行 WIP 批无下游消耗** (`MaterialConsumptionRepository.findByBatchId(wipMbId)` 空) → 否则 **409 + actionHint「该批已被下游 N 行消耗,请先删除下游行再改」** (fool-proof Rule5)。无消耗 → 反向删除该行旧 `MaterialConsumption` + 旧报工 + 旧 WIP 批 + 旧 ProductionBatch → 按新值重新物化 (batchNumber 保持不变)。
4. **写/更新 `process_sheet_rows`** (存原始 row payload JSON + 物化 batch_id/batch_number + 状态)。
5. 返回 `ProcessSheetRowResult`。

**为什么用行追踪表而非纯靠 batchNumber 反推**: 电子表格要**原样回读用户录入** (重开页面/编辑);从 ProductionBatch+reports+consumptions 反推是有损且复杂。`process_sheet_rows` 是表格的真相源,物化图是它向成本引擎的投影。

### 4.5 成本边公式 (沿用,不变)
```
MaterialConsumption.totalCost = quantity × unitPrice
  RAW:           quantity=rawInput.quantity,    unitPrice=rawBatch.unitPrice
  SEMI(混锅):    quantity=upstreamRef.feedKg,   unitPrice=上游 WIP 批.unitPrice
WIP MaterialBatch.unitPrice = rowTotalCost / outputQuantity
labor_cost = Σ_segment( minutesBetween(start,end) × workerCount / 60 ) × hourlyRate
```
下游 traceCost 按 `consumedQty/upstreamReceiptQty` 比例分摊 (已有,不动)。

### 4.6 删除行端点
```
DELETE .../process-sheet/row/{clientRowId}
```
同样先校验无下游消耗 → 反向删除物化图 + `process_sheet_rows` 行。有下游 → 409。

---

## 5. 后端: 半成品库存读端点

```
GET /api/mobile/{factoryId}/production-plans/{planId}/process-sheet/inventory?process={code}
```
派生自 WIP MaterialBatch (不新建库存表):

```
对每个 batch_type=CLERK_WIP 且属本 plan 链的 WIP MaterialBatch:
  produced  = MaterialBatch.receiptQuantity
  used      = Σ MaterialConsumption.quantity WHERE batchId = wipMbId   (findByBatchId)
  remaining = produced - used
  status    = remaining<=0 ? DEPLETED : ACTIVE
  unitPrice = MaterialBatch.unitPrice
```
返回 `[{batchNumber, produced, used, remaining, status, unitPrice}]`。供:
- 库存子表渲染。
- 下游工序的**上游批次下拉** (只列 remaining>0)。

**factory-scoped** 查询,跨租户守卫。**range**: 限定本 planId 链 (不串其它计划/工厂的 WIP)。

---

## 6. 前端: 配置驱动 `<ProcessSheet>` 组件

### 6.1 组件结构
替换 `ProcessChainEntryDrawer.vue`。一个工序页 = 一个 `<ProcessSheet :process="code">`,内含三联表:
1. **数据录入表** (`ProcessDataTable`): 每行一个批次。列由配置驱动。
2. **工时表** (`WorkHoursTable`): 多时段 (开始/结束/人数 → 时长×人数=小计),Σ=总工时,回填数据行。
3. **半成品库存表** (`InventoryTable`): 批次/产出/已用/剩余/状态 (读 §5 端点)。

### 6.2 配置描述符 (`PROCESS_SHEET_CONFIG`)
前端 TS const,镜像 mockup `getFields`。**切片只配 `chaoshui` + `shuzhi`**:

```ts
type ColType = 'dropdown'|'number'|'date'|'auto'|'readonly'|'text';
interface ColDef {
  key: string; label: string; type: ColType;
  upstream?: string;          // dropdown: 上游 process code
  autoCalc?: 'yield'|'remaining'|'totalHours';  // auto 字段公式
}
const PROCESS_SHEET_CONFIG: Record<string, ColDef[]> = {
  chaoshui: [ {key:'upstreamBatch',type:'dropdown',upstream:'gunrou',label:'滚揉批次'},
              {key:'batch',type:'readonly',label:'焯水批次'},  // 系统生成
              {key:'date',type:'date',label:'焯水日期'},
              {key:'before',type:'number',label:'焯水前(kg)'},
              {key:'after',type:'number',label:'焯水后(kg)'},
              {key:'yieldRate',type:'auto',autoCalc:'yield',label:'出成率(%)'},
              {key:'downstreamUse',type:'number',label:'下游用量(kg)'},
              {key:'remain',type:'auto',autoCalc:'remaining',label:'剩余量(kg)'},
              {key:'totalHours',type:'auto',autoCalc:'totalHours',label:'总工时(h)'} ],
  shuzhi: [ {key:'upstreamBatch',type:'dropdown',upstream:'chaoshui',label:'焯水批次(混锅)'},
            {key:'batch',type:'readonly',label:'熟制批次'},
            {key:'date',type:'date',label:'日期'},
            {key:'input',type:'number',label:'投入(kg)'},
            {key:'output',type:'number',label:'产出(kg)'},
            {key:'yieldRate',type:'auto',autoCalc:'yield',label:'出成率(%)'},
            {key:'downstreamUse',type:'number',label:'已使用(kg)'},
            {key:'remain',type:'auto',autoCalc:'remaining',label:'剩余(kg)'},
            {key:'totalHours',type:'auto',autoCalc:'totalHours',label:'总工时(h)'} ],
  // 其余 4 道 (xiuyou/gunrou/qushetou/qidiao) 跑通后纯加配置
};
```

### 6.3 关键交互
- **上游批次下拉**: 列出上游工序 remaining>0 的批次 (读 §5),显示「批次号 · 剩余 Xkg」。选中带出默认投入量 = remaining (可改)。
- **批次号**: 本行输出批次号**只读**,首存系统生成 (端点返回),作为下游下拉项。
- **混锅 (熟制)**: 上游下拉支持多选 (`+ 来源批` 行,每行 batchNumber + feedKg),映射 `upstreamSources[]`。
- **自动字段** (客户端算,镜像 mockup): 出成率=产出/投入×100;剩余=产出−下游用量;总工时=Σ(时段时长×人数)。**权威成本来自后端响应**,不在前端算成本。
- **超量软预警** (fool-proof Rule1): 下游用量 / 投入量 > 上游剩余 → 黄字提示「超出上游剩余 Xkg」,**不硬阻断** (软预警),但提交前显示。
- **保存**: 行级 `+保存` 或失焦增量 POST (§4)。乐观更新 + 后端 reconcile。

### 6.4 订单/计划映射
mockup 的「订单管理」= 我们存货生产**计划列表** (`production/plans/list.vue`)。SP-F **不做新建计划流** (复用现有计划创建)。「进入生产」→ 打开该计划的 `<ProcessSheet>` 工序页。存货生产**无订单号**,以 batchNumber 贯穿 (沿用 SP-C 双模式)。

---

## 7. 汇总 / 成本视图

**无新后端代码**。复用 by-batch 端点:
- 出成率: `getOrderYieldSummary` (by-batch)。
- 成本追溯: `OrderCostBreakdownService.computeByBatch(factoryId, batchNumber)` → 桶分类 (原料/人工/调料/包装) + kg 级单价。
- 前端汇总页对成品批号调这两端点,渲染 mockup 的「出成率链」+「成本追溯明细」表。切片阶段成品批未到气调,汇总展示到熟制批的 kg 级成本。

---

## 8. 垂直切片实施 (焯水 + 熟制)

### 8.1 种子 (DEMO_FACTORY, df_admin/123456)
- 复用现有计划 `PLAN-1782200389848-55F1F7C7` (存货生产逐道,memory 记载已建可重录) 或新建 demo 计划。
- 种 1 个上游 WIP 批 (滚揉产出) 当焯水起始库存 + 原料/调料 MaterialBatch (走授权 seed,纯 ASCII 避 mojibake,绝不碰 F006/LIUSHANMEN)。

### 8.2 验证链路
1. **焯水** (单上游): 选滚揉批 → 录焯水前/后 + 工时 → 保存 → 验 (a) 领料 MaterialConsumption 写入 (b) 滚揉批库存扣减 (c) 焯水批 WIP 生成 + 剩余正确 (d) 出成率派生。
2. **熟制** (混锅多源): 选 2+ 焯水批 + 调料 → 录投入/产出 + 工时 → 保存 → 验 (e) 混锅多 MaterialConsumption 边 (f) SEASONING 报工不落原料桶 (g) 熟制批成本 = Σ上游分摊 + 调料 + 人工。
3. **成本核对**: 熟制批 `computeByBatch` → material:labor ≈ **65.7:34.3** (kg 级,对齐 memory M67 参考)。

### 8.3 真实 PG 验证 (关键)
WIP 批 `materialTypeId` FK 撞 `raw_material_types` (SP-E 真踩,H2 漏)。切片后端集成测**必须对真 PG schema 验 FK** (memory: mock repo 漏 DB 约束)。用 rollback-replay 或 test 环境真写。

---

## 9. 随 SP-F 的 3 个 UI 修复 (纯 UI/校验)

| # | 修复 | 实施 |
|---|---|---|
| 多锅必填 | `potCount>1` 时 `potRawKgs` 必填 (否则静默等分高估) | 前端 validate + 后端 `materializeBatch` 守卫 (N>1 缺 potRawKgs → 400) |
| 防呆 Rule1 | 提交按钮在超限/非法时 `:disabled` | 前端 `<ProcessSheet>` 提交按钮绑 `:disabled="overLimit||invalid"` |
| 配方老汤豁免 | 配方 validate 不强制 `count_in_seasoning=false` 的老汤填价格 | 后端/前端 validate 跳过 `count_in_seasoning=false` 行的价格必填 |

(Q1 注射基重 / Q6 副产回收 = 成本引擎,**不在此**,独立 🔒 PR。)

---

## 10. 数据模型 / Flyway

### 10.1 新表 `process_sheet_rows`
```sql
CREATE TABLE process_sheet_rows (
  id            BIGSERIAL PRIMARY KEY,
  factory_id    VARCHAR NOT NULL,
  plan_id       VARCHAR NOT NULL,
  process_code  VARCHAR NOT NULL,
  client_row_id VARCHAR NOT NULL,
  batch_id      BIGINT,                 -- 物化的 ProductionBatch.id
  batch_number  VARCHAR,
  row_payload   JSONB NOT NULL,         -- 原始录入 (回读用)
  row_status    VARCHAR DEFAULT 'SAVED',
  created_at    TIMESTAMP DEFAULT NOW(),
  updated_at    TIMESTAMP DEFAULT NOW(),
  deleted_at    TIMESTAMP NULL,
  CONSTRAINT uk_sheet_row UNIQUE (factory_id, plan_id, process_code, client_row_id)
);
```
- 继承 BaseEntity audit 字段 (created/updated/deleted_at)。
- `uk_sheet_row` = upsert 结构性幂等 (天然防重复,不靠 idempotency key 表)。

### 10.2 Flyway 版本
下一个空号 **预检** (并发 session 撞号高发,memory 实证 27_05/27_07 双号阻断部署):
```bash
git ls-tree -r origin/main --name-only | grep flyway | grep -oE 'V[0-9_]+' | sort | uniq -d   # 查重号
# 当前最新 V20261027_09 → 取 V20261027_10 (或 V20261028_01 跨日)
```
出 PR / 部署前再查一次重号。

---

## 11. 测试

### 11.1 后端集成测 (真 PG)
- 单行新建 → 物化 ProductionBatch + MaterialConsumption + WIP 批 + 报工正确。
- 重存 (re-save) → upsert 更新不重复;**有下游消耗时 409**。
- 混锅多源 → 多 consumption 边 + 成本聚合。
- 库存派生 (§5) → produced/used/remaining 正确。
- 跨租户守卫 → 别工厂 planId/batchNumber → 403/404。
- **WIP 批 FK 真 PG 验** (SP-E bug 回归)。
- 删除行无/有下游 → 成功/409。

### 11.2 前端组件测
- 配置渲染列正确 (chaoshui/shuzhi)。
- 上游下拉只列 remaining>0。
- 超量软预警显示。
- 自动字段 (yield/remaining/totalHours) 计算。
- 提交 :disabled 防呆。

### 11.3 E2E (headed, DEMO_FACTORY)
- df_admin headed (`PLAYWRIGHT_PORT`/`CHAT_ID` 隔离,headless:false,zh-CN) 走焯水→熟制完整链,截图验三联表 + 成本核对。
- 绝不碰 F006/LIUSHANMEN。

---

## 12. 隔离 / 红线 / 流程

- **worktree off origin/main**: `feat/sp-f-process-sheet` @ `C:\Users\Steve\cretas-sp-f` (已建;旧 `feat+worktree-session` stale 不用)。
- **scope-locked commit**: `git commit -- F1 F2` 或 `safe-commit.sh`;出 PR 前 `git diff origin/main...HEAD --stat` 确认 scope 干净。
- **🔒 红线**: 增量物化触 traceCost 成本图 + 跨租户守卫 + 新 Flyway → **Opus 终审**,从 main 部署。执行层 (Sonnet subagent) 只做到「实现+自测+PR」。
- **DEMO_FACTORY only**: df_admin/123456,绝不碰 F006/LIUSHANMEN 真客户。
- **Flyway 防撞号**: PR/部署前查重号 (§10.2)。
- **真 PG 验 FK**: 后端写库红线对真 schema 验证 (§8.3)。

---

## 13. 分发卡 (Multi-Model Dispatch)

| # | 任务 | 模型 | effort | orchestration | 分支/scope | 🔒 |
|---|---|---|---|---|---|---|
| F1 | `process_sheet_rows` Flyway + `ProcessSheetService.materializeBatch` 抽取 + 单行端点 | **Opus 自做** (判断密集: 物化图/upsert 反向删/跨租户/成本边) | high | inline 或 Opus subagent | `service/processentry/`, `controller/`, `db/flyway/` | 🔒 |
| F2 | 半成品库存读端点 + 回读端点 | Sonnet in-harness | high | subagent | `service/processentry/`, `repository/` | |
| F3 | `<ProcessSheet>` 通用组件 + `PROCESS_SHEET_CONFIG` (焯水/熟制) + 替换 drawer | Sonnet/Composer | high/default | subagent | `web-admin/src/views/production/` | |
| F4 | 3 个 UI 修复 (§9) | Sonnet/Composer | default | subagent | 对应文件 | |
| F5 | 后端集成测 (真 PG) + E2E headed | Sonnet in-harness | high | subagent | `test/`, e2e spec | |
| 终审 | PR diff 终审 + 从 main 部署 | **Opus** (出货闸) | xhigh | 单 subagent (read-only) | — | 🔒 |

依赖: F3 依赖 F1 端点契约;F2 可与 F1 并行;F5 依赖 F1-F3。F1 (🔒 物化/成本/跨租户) Opus 自做不下压。

---

## 附: 未决/假设 (实施时确认)
- 工时表多时段持久化粒度: `process_sheet_rows.row_payload` JSON 存原始时段;物化只取 Σ 总工时算 labor_cost (个体时段不进成本图)。
- 计划「进入生产」入口位置: 复用 `production/plans/list.vue` 行操作,替换原抽屉触发。
- 切片成品批 `finished=false` (未到气调);单盒成本待全配置。
