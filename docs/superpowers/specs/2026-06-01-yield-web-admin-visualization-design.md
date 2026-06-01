# 出成率报工 web-admin 管理端可视化 — 设计

**日期**: 2026-06-01
**状态**: 设计已与 Steve 确认, 待写实施计划
**前置**: 报工体系统一 Phase A (PR #350/#354/#358) + Phase D (PR #360) 已 merged main。本设计是 Phase E 的第一块 — 管理端可视化。

---

## 目标

让工厂管理者在 web-admin **看到出成率逐道报工的结果**。目前(2026-06-01 实测)管理端完全看不到:
- 批次详情页 `实际产量` = `-`(YIELD 双写到 `work_process_tasks.actual_quantity`, 没写 `production_batches.actual_quantity`)
- 生产时间线不含 YIELD 报工
- 「工序投入产出对比」页读旧 `plannedQuantity/completedQuantity` 字段, 且 `/process-tasks` 不返回 YIELD 任务

成功标准: 管理者在批次详情页看到猪舌批次 `998kg → 382.08kg / 累计出成率 38.28% / 逐道投入产出`, 在「工序投入产出对比」页看到按工序聚合的 YIELD 出成率。

---

## 范围

**纯读侧功能**, 不碰任何写入链路(报工/结清/库存)。

**In scope**:
1. 后端: `getYield` 回填 `StepYieldDTO.processName`(当前为 null)
2. 后端: 新增厂级聚合端点 `GET /{factoryId}/production/yield/by-process`
3. 前端: 批次详情页加「出成率·逐道报工」卡 + 回填顶部 KPI
4. 前端: ProcessIOComparison 数据源换成新聚合端点

**Out of scope**(后续 Phase, 此前已与 Steve 确认):
- A4 超收软告警 30% 容差(后端无逻辑)
- A5 人工成本按批次/计件(Phase B)
- A3 跨批料归因的专门可视化(数据层已支持, 不做独立 UI)
- SmartBI 出成率看板(独立 spec)
- `production_batches.actual_quantity` 的写入回填(保持 YIELD 事实层与老生产模型分离, 只在读侧 KPI 用 yield 数据覆盖显示)

---

## 架构

```
┌─ 后端 (Java) ────────────────────────────────────────────────┐
│ 改 既有: GET /{f}/production/batches/{id}/yield                │
│          → getYield 回填 step.processName (work_processes 查名) │
│ 新 端点: GET /{f}/production/yield/by-process                  │
│          → List<ProcessYieldAggDTO> (厂级按工序聚合 YIELD)      │
│          新 controller YieldAnalysisController (factory-scoped) │
│          新 DTO ProcessYieldAggDTO                             │
│          新 repository 聚合查询 (production_reports JOIN        │
│            work_process_tasks JOIN work_processes, GROUP BY)    │
└──────────────────────────────────────────────────────────────┘
        ↓ web-admin get() 同 /api/mobile/{factoryId}/... 基址
┌─ 前端 (Vue) ──────────────────────────────────────────────────┐
│ 改 batches/detail.vue                                         │
│   - onMounted 多取 /yield (allSettled 并行)                   │
│   - 有 YIELD 数据时: 顶部「实际产量」KPI = lastStepOutput,      │
│     新增「累计出成率」KPI; 无数据保持原样(不造假)              │
│   - 新卡「出成率·逐道报工」: el-table 渲染 steps[]             │
│ 改 ProcessIOComparison.vue                                    │
│   - loadData 数据源 /process-tasks → /yield/by-process        │
│   - 删前端 aggregateByProcess, 直接渲染后端聚合结果           │
│   - 筛选/KPI/表格/图例 UI 不变                                 │
└──────────────────────────────────────────────────────────────┘
```

各单元边界清晰: 后端两改互不依赖; 前端两改各自独立; 前端只消费后端 DTO, 不含业务计算(聚合下沉后端)。

---

## 单元 1: 后端 — `getYield` 回填 `processName`

### 现状

`YieldCalculationServiceImpl.calculateSteps` 构造 `StepYieldDTO` 时**不设** `processName`(只设 `workProcessTaskId` + `processOrder`)。RN 报工屏用 `processOrder`("道1/道2/道3")显示, 所以没暴露; 但 web 端表格要显示工序名("处理/滚揉/末道")。

### 改法

不动纯函数 `calculateSteps`(保持无 repository 依赖)。在 **caller** `YieldReportServiceImpl.getYield` 里, 拿到 `BatchYieldDTO` 后 enrich 每个 step 的 `processName`:

- `YieldReportServiceImpl` 已注入 `taskRepo`(WorkProcessTaskRepository)+ `processRepo`(WorkProcessRepository), 且已有 `processRepo.findById(workProcessId)` 用法(yieldAlert)。
- 对每个 step: `step.workProcessTaskId` → task → `task.getWorkProcessId()` → work_process → `getProcessName()` → `step.setProcessName(name)`。
- **批量查询 (audit YIELD-4)**: 收集 distinct `workProcessTaskId` 到 Set, 一次查全部 task; 再收集 distinct `workProcessId` 一次查全部 work_process。`WorkProcessTaskRepository` 现只有 `findByFactoryIdAndId`(单条)— **需新增** `List<WorkProcessTask> findByFactoryIdAndIdIn(String factoryId, Collection<Long> ids)`(或用 `findAllById` + factory 过滤)。`processRepo.findAllById(processIdSet)` 已可用。组 Map 后 set, 避免 N+1。
- 查不到名(task/process 已删)→ 留 null。**前端 fallback (audit SCOPE-2/YIELD-4)**: 工序列渲染 `{{ step.processName || ('第' + step.processOrder + '道') }}`。

### 单元 1b: 跨单位 cumulativeYieldRate 防误导 (audit YIELD-1, P0)

**现状缺陷**: `getYield` 调 `calculateBatchYield(reports, null)` 硬编码 `standardGramsPerUnit=null`。当末道产出单位 ≠ 首道投入单位(如末道按 3184 盒报、首道 998kg 投), 折算逻辑(`YieldCalculationServiceImpl:88-96`)因参数 null 被跳过 → `cumulativeYieldRate = lastOutput / firstInput = 3184/998 ≈ 3.19`(无意义的混单位比率), 而非正确的 38.28%。本 spec 让 cumulativeYieldRate **显示在管理端 KPI**, 显示 319% 会严重误导。

> 我此前验证猪舌链用的是末道 kg→kg(382.08kg)同单位路径, 所以累计 0.3828 正确; 一旦真实批次末道按"盒"报工就踩这个坑。

**本 spec 的修法(读侧防误导, 不引入新配置)**: `getYield` 计算 cumulative 时判断首末单位:
- 首末单位**相同** → 现状逻辑正确, 不变。
- 首末单位**不同且无折算系数** → `cumulativeYieldRate = null`(不输出错误数字)。前端 KPI / 合计行显 "—" + tooltip "末道与首道单位不同, 累计出成率需配置标准折算系数"(防呆 Rule 5 next-action 提示)。

**明确 out of scope**: 正确的跨单位累计(末道盒→首道kg 按标准克重折算)需要 `ProductType` / `ProductWorkProcess` 增末道克重系数字段 + 配置 UI — 这是 **Phase A/B 的独立修复**(当前实体无此字段), 不在本读侧 spec。本 spec 只保证"不显示错误数字"。

### 验证

- 既有 `getYield` 行为不变, 仅 `steps[].processName` 从 null → 真实工序名; 同单位批次 cumulativeYieldRate 不变。
- RN 屏不受影响(它用 processOrder)。
- 单测: (a) 给定 3 个 task 关联 3 个 work_process(processName "处理"/"滚揉"/"末道"), steps processName 正确; (b) 同单位批次(998kg→382.08kg)cumulative=0.3828; (c) **跨单位批次(998kg→3184盒)cumulative=null**(不再是错误的 3.19)。

---

## 单元 2: 后端 — 新增 `/yield/by-process` 厂级聚合端点

### 端点

```
GET /api/mobile/{factoryId}/production/yield/by-process
    ?startDate=YYYY-MM-DD   (可选)
    &endDate=YYYY-MM-DD     (可选)
    &productTypeId=xxx      (可选)
返回: ApiResponse<List<ProcessYieldAggDTO>>
```

放在**新 controller** `YieldAnalysisController`(`@RequestMapping("/api/mobile/{factoryId}/production/yield")`, factory-scoped), 不放进 batch-scoped 的 `YieldReportController`(它的 mapping 含 `{batchId}`)。

- **模块 (audit RBAC-2)**: `@RequireModule("production")` — `production` 是权限矩阵已定义的合法模块。**不要**用 `production_report`(YieldReportController 沿用的, 但不在 ALLOWED_MODULES, 是 Phase A 遗留, 本 spec 不改它)。
- **权限 (audit RBAC-1)**: `production:read`。这是**管理端页面**(批次详情 / 工序对比), 由 factory_admin / production_manager(均 `production:read_write`)访问。**operator 角色 (`production:write`, 无 read) 访问不到 — 这是有意为之**: operator 用 RN 报工, 不看 web 厂级聚合页。无需为此加权限。
- **租户校验 (audit RBAC-4)**: controller 入口校验 path `{factoryId}` 与 JWT 用户 factoryId 一致(沿用平台既有 factory-scoped pattern, 如 AlertController), 防 path 参数被改越权读他厂数据。

### DTO

`ProcessYieldAggDTO`(字段名对齐前端 `ProcessIORow`, 让前端零转换):

```java
public class ProcessYieldAggDTO {
    private String processName;        // 工序名 (work_processes.process_name)
    private BigDecimal inputQuantity;  // Σ input_quantity, scale 2
    private BigDecimal outputQuantity; // Σ output_quantity, scale 2
    private BigDecimal conversionRate; // 出成率% = Σout/Σin*100, scale 1 (0-100); 单位不可比时 null
    private BigDecimal wastageRate;    // 损耗率% = max(0, (Σin-Σout)/Σin*100), scale 1; 单位不可比时 null
    private String unit;               // 工序标准单位 (work_processes.unit, 非报工记录单位 — 见 audit SQL-2)
    private Boolean unitComparable;    // wp.unit == wp.output_unit (与 StepYieldDTO 对齐, 见 audit TESTING-2)
    private Integer batchCount;        // COUNT(DISTINCT batch_id)
}
```

> **unitComparable (audit TESTING-2)**: 与单批 `StepYieldDTO.unitComparable` 语义对齐。工序的标准投入单位 `wp.unit` 与产出单位 `wp.output_unit` 不同时(如末道 kg→盒), `conversionRate`/`wastageRate` 置 null, 前端显 "—" 仅展示投入/产出量。否则单批显 "—" 而聚合显一个无意义的混单位比率, 两条路径不一致。

> **scale 约定差异(有意为之)**: 既有 `/yield` 端点的 `yieldRate`/`cumulativeYieldRate` 是 **0-1**(0.3828, RN 已消费, 不改)。新 `/by-process` 的 `conversionRate` 是 **0-100**(38.28, 显示层端点, 对齐 ProcessIO 前端 `getConversionColor(rate>=90)`)。两端点两消费方两 scale, 不互通。
> BigDecimal 序列化遵循既有 Jackson 行为(本项目非 byte-parity 端点, 无 strict 约束)。

### 聚合查询

新 repository 方法(native SQL, 因跨 3 表 join + GROUP BY):

```sql
SELECT wp.process_name              AS process_name,
       SUM(pr.input_quantity)       AS total_input,
       SUM(pr.output_quantity)      AS total_output,
       wp.unit                      AS input_unit,    -- 工序标准单位 (audit SQL-2)
       wp.output_unit               AS output_unit,   -- 判 unitComparable
       COUNT(DISTINCT pr.batch_id)  AS batch_count,
       MIN(wpt.process_order)       AS process_order
FROM production_reports pr
JOIN work_process_tasks wpt ON pr.work_process_task_id = wpt.id AND wpt.deleted_at IS NULL
JOIN work_processes wp      ON wpt.work_process_id = wp.id
WHERE pr.factory_id = :factoryId
  AND wpt.factory_id = :factoryId            -- 显式租户隔离 (audit RBAC-3, belt-and-suspenders)
  AND pr.report_type = 'YIELD'
  AND pr.deleted_at IS NULL
  AND (CAST(:startDate AS string) IS NULL OR pr.report_date >= :startDate)
  AND (CAST(:endDate   AS string) IS NULL OR pr.report_date <= :endDate)
  AND (CAST(:productTypeId AS string) IS NULL OR wpt.product_type_id = :productTypeId)
GROUP BY wp.id, wp.process_name, wp.unit, wp.output_unit
ORDER BY MIN(wpt.process_order)
```

> **CAST 类型 (audit SQL-1, HARD)**: 必须 `CAST(:param AS string)` — **不是** `AS date`/`AS varchar`。Hibernate 6 只认通用类型 `string`, PG 原生类型会 "could not determine data type of parameter" 部署失败(见 `.claude/rules/database-entity-sync.md`)。
> **单位取 `wp.unit`/`wp.output_unit` (audit SQL-2)**: 工序标准单位, 不是 `MIN(pr.input_unit)`(报工记录单位可跨批不一致, MIN 取字典序最小会误导)。`GROUP BY` 须含这两列。Service 据 `wp.unit == wp.output_unit` 算 `unitComparable`; 不可比则 `conversionRate`/`wastageRate` 置 null。
> **`wpt.factory_id = :factoryId` (audit RBAC-3)**: 即使 id 全局唯一, 显式租户过滤是本仓库 SQL 惯例(防御深度)。
> 按 `wp.id` 分组(不是按 name 字符串)— 同名不同工序定义算两行, 更准。
> **日期筛选语义 (audit SCOPE-3)**: 按 `report_date`(YIELD 报工业务日期)。注意旧 `/process-tasks` 端点**根本不接受**日期参数(UI 的 dateRange 是 dead path), 换源后日期筛选**首次真正生效** — 前端日期选择器加 tooltip "按报工日期筛选" 说明语义。

### Service

`YieldAnalysisService.aggregateByProcess(factoryId, startDate, endDate, productTypeId)`:
- 调 repository 拿聚合行(投影接口, 含 inputUnit/outputUnit)
- 每行算 `unitComparable = Objects.equals(inputUnit, outputUnit)`
- `unitComparable && total_input>0` → `conversionRate = total_output/total_input*100`(scale 1, HALF_UP)、`wastageRate = max(0, (in-out)/in*100)`(scale 1); 否则 `conversionRate = wastageRate = null`(不可比工序不算率, 与单批 StepYieldDTO 一致)
- `total_input==0`(理论上 YIELD 报工必有投入, 防御)→ rate 置 0
- 空结果返回**空 list(非 null)** — 守 `禁止降级处理`
- 组装 `ProcessYieldAggDTO` list 返回

### 验证

- 单测: 2 批次都有"处理"工序(批A input100/output90, 批B input200/output170)→ 聚合 1 行 input300/output260/conversionRate86.7/batchCount2。
- 单测: 跨日期过滤 — 范围外的 report 不计入。
- 空数据 → 返回空 list(非 null)。

---

## 单元 3: 前端 — 批次详情页 yield 卡 + KPI 回填

文件: `web-admin/src/views/production/batches/detail.vue`

### 取数

`onMounted → loadData` 现在 `allSettled([batch, timeline])`。加第三个并行请求:

```ts
const [batchRes, timelineRes, yieldRes] = await Promise.allSettled([
  get(`/${factoryId.value}/processing/batches/${batchId.value}`),
  get(`/${factoryId.value}/processing/batches/${batchId.value}/timeline`),
  get(`/${factoryId.value}/production/batches/${batchId.value}/yield`),
]);
```

`yieldRes` 成功且 `data.steps?.length > 0` → 存 `yieldData.value = yieldRes.value.data`; 否则 `yieldData.value = null`。失败不阻塞(allSettled), 不弹错(管理者看老批次本就没 yield)。

### KPI 回填

新增 `computed`:
- `displayActualQuantity`: `yieldData?.steps?.length ? yieldData.lastStepOutput : batch.actualQuantity`
- `displayActualUnit`: `yieldData?.steps?.length ? yieldData.lastStepOutputUnit : batch.unit`

顶部「实际产量」KPI 改用 `displayActualQuantity` + `displayActualUnit`。
有 yield 时, 在「实际产量」后插入新 KPI 卡「累计出成率」。**null 防护 (audit YIELD-1)**: `cumulativeYieldRate == null ? '—' : formatPercent(cumulativeYieldRate * 100)` —— **不能**写 `formatPercent(cumulativeYieldRate * 100)`, 因 JS `null * 100 === 0` 会把跨单位的 null 误显成 "0.0%"。跨单位批次(cumulative=null)显 "—" + tooltip(见单元 1b)。用 `v-if="yieldData"` 控制该卡显隐。

> `formatPercent` 已存在(`n.toFixed(1) + '%'`, 已处理 null→'-')。`cumulativeYieldRate` 0.3828 ×100 = 38.28 → "38.3%"。

> **KPI 网格列数 (audit FE-VUE-3)**: 现 `.kpi-row` 写死 `repeat(5, 1fr)`, 加「累计出成率」后变 6 卡(canViewPrice 时)会折行错位。改 `grid-template-columns: repeat(auto-fit, minmax(150px, 1fr))` 或显式 `repeat(6, 1fr)`, 保留 `@media(max-width:1200px)` 响应式断点。headed Playwright 验证布局不破。

### 新卡「出成率·逐道报工」

`v-if="yieldData && yieldData.steps.length"`, 放进 `detail-grid`(2 列卡片区), `el-table :data="yieldData.steps"`:

| 列 | 字段 | 渲染 |
|---|---|---|
| 道 | processOrder | 数字 |
| 工序 | processName | 文本(null → "工序"+processOrder fallback) |
| 投入 | totalInput | `formatNum(totalInput) + ' ' + (inputUnit || '')` (audit FE-VUE-6: 单位 null 不显 "null") |
| 产出 | totalOutput | `formatNum(totalOutput) + ' ' + (outputUnit || '')` |
| 出成率 | yieldRate | `unitComparable ? formatPercent(yieldRate*100) : '—'`; `yieldAlert` 非空 → 文字标红 + tooltip 显 alert |
| 结转 | carryover | `carryover==null ? '—' : formatNum(carryover)`; `>0` 高亮(琥珀) |

表格下方一行**合计文本**(不用 `el-table show-summary` — 那个按列求和, 不符):
- 首末**同单位**: `firstStepInput {firstStepInputUnit} → lastStepOutput {lastStepOutputUnit}  累计 {cumulativeYieldRate*100}%`
- 首末**不同单位 (audit YIELD-6)**: `firstStepInput {firstStepInputUnit} → lastStepOutput {lastStepOutputUnit}  累计 —`(cumulative=null), 不并排显两个异单位数字再配一个看似矛盾的百分比。tooltip 同单元 1b。

> 单位不可比工序(`unitComparable=false`, 如末道 kg→盒)出成率显 "—" 仅展示量, 与后端语义一致。
> **保水工序 (audit YIELD-5)**: yieldRate>1(产出>投入, 如滚揉 135%)正常显示, 不截断; 是客户认可的保水场景(见 memory 金标准道2)。结转/损耗语义对保水不适用, 表格不显"损耗"列(详情页逐道卡只显出成率+结转, 损耗率仅在单元 4 厂级页)。

### 验证

headed Playwright: 登录 factory_admin1, 访问已报工的猪舌批次详情, 断言:
- 顶部「实际产量」显 382.08kg(非 "-")
- 「累计出成率」KPI 显 38.3%
- 「出成率·逐道报工」卡 3 行, 道2 出成率 135.0% 标保水告警, 合计行 998 → 382.08 累计 38.28%

---

## 单元 4: 前端 — ProcessIOComparison 换源

文件: `web-admin/src/views/production/ProcessIOComparison.vue`

### 改法

`loadData()`:
```ts
const response = await get<ProcessYieldAgg[]>(
  `/${factoryId.value}/production/yield/by-process`,
  { params: { startDate?, endDate?, productTypeId? } }   // 透传现有筛选, 删 page/size
);
if (response.success && response.data) {
  tableData.value = (response.data || []).map(r => ({
    processName: r.processName,
    processCategory: '',
    inputQuantity: r.inputQuantity,
    outputQuantity: r.outputQuantity,
    conversionRate: r.conversionRate,   // 后端已 0-100
    wastageRate: r.wastageRate,
    unit: r.unit,
    batchCount: r.batchCount,
  }));
} else { tableData.value = []; }
```

- 删本页**局部** `aggregateByProcess` 函数 + 本页**局部** `ProcessTaskItem` interface(行 37-47, 不再用)。**不要动 (audit FE-VUE-4)** `src/api/processProduction.ts` 里 export 的全局 `ProcessTaskItem`(其他页面如任务列表仍用它)。
- 新增本页 `ProcessYieldAgg` interface 匹配 DTO。**注释标注 (audit FE-VUE-5)**: `conversionRate` 后端已是 0-100(38.28), 前端直接渲染, **不再 ×100**(旧 aggregateByProcess 是 `*1000/10` 自己算, 删掉后别误把 0-1 当 0-100 再乘)。
- `ProcessIORow` interface 不变, KPI computed / 筛选 / 表格 / 图例 / 样式全不动。`conversionRate`/`wastageRate` 为 null(不可比工序)时表格列显 "—", `getConversionTagType(null)` 需容错。
- **错误处理 (audit RULE-5)**: **删掉** catch 块里的 `ElMessage.error('加载数据失败')` —— `request.ts` 拦截器已对 `success=false` 调 `showRichError`(sticky + actionHint + next-action), catch 再弹一个 3s 非粘性 toast 是重复且违反 4-位一体。catch 块只留 `tableData.value = []`(allSettled 不适用单请求, 这里是单 get, 让拦截器处理错误展示)。

### 非 YIELD 工厂 regression 处理 (audit SCOPE-1)

旧 `/process-tasks` 对**所有**有工序任务的工厂返数据(plannedQuantity/completedQuantity)。换成 `/by-process` 后, **从未做过 YIELD 报工的老制造工厂该页会变空** —— 这是行为变化。

**处理 (防呆 Rule 5, 不做双源 fallback)**: 空结果时显**明确空状态 + next-action**, 不是静默空表:
```
<el-empty description="本厂暂无出成率报工数据">
  <div>出成率报工由车间在 App 端逐道提交后, 此处自动汇总</div>
  <!-- 若有报工入口/文档链接可加 button -->
</el-empty>
```
- **不**回退到旧 `/process-tasks` 聚合(那是任务计划数, 非实际出成率, 语义混淆; 旧页的 plannedQuantity/completedQuantity"投入产出"本就是弱代理)。
- 本页语义明确转为"基于实际 YIELD 报工的出成率", 不再是任务计划对比。
- **此行为变化需在 PR 描述 + 给 Steve 的汇报里点出**(老工厂该页从"有计划数"变"空态引导"), 让产品知晓。

### 验证

headed Playwright: 访问 `/production/process-io`, 断言表格显出工序聚合行(猪舌批次的"处理/滚揉/末道"), 转化率列与批次详情逐道出成率一致(单批时)。

---

## 数据流

```
管理者打开批次详情
  → GET /yield (单元1 已 enrich processName)
  → 详情页渲染 KPI 回填 + 逐道报工卡 (单元3)

管理者打开工序投入产出对比
  → GET /yield/by-process (单元2 聚合)
  → 表格渲染工序聚合 (单元4)
```

两条读路径都只读 `production_reports` (report_type='YIELD') 事实层 + work_processes 名字, 不触碰写入。

---

## 错误处理

- 后端: 聚合查询无数据返回空 list(非 null, 非假数据 — 守 `禁止降级处理`)。`startDate>endDate` 等非法入参由 Controller `@DateTimeFormat` + service 边界检查, 返 4xx + 明确 message。
- 前端: 沿用 `web-admin/src/api/request.ts` 的 sticky error toast(防呆 4 位一体: error duration:0 + showClose + 后端 message 原样显)。
- 批次详情 yield 取数失败不阻塞 batch 主信息(`allSettled` 独立分支)。
- 无 YIELD 数据的老批次: 详情页不显 yield 卡 + KPI 保持老值(不造假)。

---

## 测试

| 层 | 测试 |
|---|---|
| 后端单测 | (a) `getYield` processName enrich 正确(含 task/process 已删→null fallback); (b) 同单位 cumulative=0.3828, **跨单位 cumulative=null**(audit YIELD-1); (c) `aggregateByProcess` 跨批求和(批A 100→90 + 批B 200→170 = 300/260→86.7%)+ 日期过滤(范围外排除)+ 空数据返空 list; (d) **单批 scale 对齐 (audit TESTING-1)**: 同批 `/yield` yieldRate=0.85 ↔ `/by-process` conversionRate=85.0; (e) **单位不可比工序 (audit TESTING-2)**: conversionRate=null |
| 后端 IT | 金标准猪舌(998→382.08)聚合后"处理/滚揉/末道"三工序出成率正确(可 @Disabled pg-only, 复用 Phase A IT 模式) |
| 前端 | 无单测惯例; **headed Playwright**: 批次详情 KPI 回填(实际产量 382.08kg + 累计 38.3%)+ 逐道卡(道2 135% 保水)+ ProcessIO 聚合表 + 非 YIELD 工厂空态引导 |

> **CI 实际行为 (audit RULE-3, 纠正前述认知)**: `.github/workflows/ci.yml` 的 `java-build-test` job **会跑** `mvn -B clean verify`(surefire 跑 `*Test.java` + failsafe 跑 `*IT.java`)。**Java 单测在 CI 真跑**, "CI 绿"含 Java 测试结果。memory `feedback_ci_python_lint_test_does_not_run_unit_tests` 只针对 **Python**(Python 侧 CI 只 flake8 不跑 pytest), 别泛化到 Java。本地 `mvn test` 仍可快速验证。
> **headed 配置 (audit RULE-2, HARD)**: web-admin `playwright.config.ts` 全局默认 `headless: true`。UI E2E 必须 headed(per `.claude/rules/playwright-headed-mode.md`, 否则 PR review 阻 merge)。两法: (1) 仿 config 里 `mealclaw-customer-ui` 项目(headless:false + 1920×1080 + zh-CN + screenshot/video)加 yield E2E 项目; (2) 用 MCP headed 浏览器手验(默认 headed) — 本轮 gap 证据已用 (2) 跑通。

---

## 部署

per HARD RULE `.claude/rules/worktree-and-main-only-deploy.md`:
- 已在 worktree `../cretas-yield-web`(off `origin/main`, 分支 `feat/yield-web-admin`)开发。
- 完成 → PR → 确认 `git diff origin/main...HEAD --stat` scope 干净 → merge main。
- **从 main** 部署: `deploy-backend.sh --env prod`(Java)+ `deploy-web-admin.sh --env prod`(web-admin)。
- 部署后**必须 `systemctl restart cretas-backend`**(deploy 脚本传 jar 不重启活跃 systemd 实例 → flyway/新代码不生效 — Phase A 反复踩, per memory `project_2026_06_01_yield_reporting_phase_a`)。本次无 flyway 迁移(纯读), 但新 Java 类仍需重启进程才加载。
- 部署后核对运行 jar 含新端点(curl `/by-process` 真返聚合)。

---

## 实施顺序

1. 单元 1(processName enrich)— 最小, 解锁单元 3 的工序名显示
2. 单元 2(/by-process 端点)— 解锁单元 4
3. 单元 3(批次详情)— 高价值, 可独立验证
4. 单元 4(ProcessIO 换源)— 依赖单元 2

后端(1+2)先于前端(3+4)。每单元完成即可独立测试。新增 **单元 1b**(跨单位 cumulative 防误导, audit YIELD-1 P0)随单元 1 一起做。

---

## §9 审计修订记录 (2026-06-01 superpowers 对抗性审计, 39 agent)

审计跑 6 维度 finder + 逐条对抗性 verify, raise 33 / confirm 29 / reject 4。**关键甄别**: 多条 confirm 的"P1"实为 verifier 确认"代码尚未实现"(YIELD-2/RULE-1/SCOPE-2 等)—— 这是 **spec 描述待实现工作的本意, 非缺陷**, 不计入需改。下表是**真正影响 spec 的修订**:

### 已修进 spec (genuine defects)

| Finding | 级别 | 问题 | 修订位置 |
|---|---|---|---|
| YIELD-1 | P0 | getYield 硬编码 standardGramsPerUnit=null → 跨单位 cumulative 显错误比率(3.19 而非 0.38) | **单元 1b**: 跨单位 cumulative 置 null 显 "—"; 正确折算配置列 out-of-scope(Phase A/B) |
| SQL-1 | P1 | `CAST(:p AS date/varchar)` PG 部署失败 | SQL 改 `CAST(:p AS string)` |
| SQL-2 / SCHEMA-2 | P1 | `MIN(pr.input_unit)` 跨批单位歧义 | 改用 `wp.unit`/`wp.output_unit` 工序标准单位 |
| TESTING-2 | P1 | 聚合端点缺 unitComparable, 与单批不一致 | ProcessYieldAggDTO 加 `unitComparable`; 不可比 → conversionRate=null |
| RBAC-2 | P1 | `production_report` 非合法 module | 新 controller 用 `@RequireModule("production")` |
| RBAC-3 | P1 | SQL 缺 `wpt.factory_id` 显式隔离 | SQL 加 `wpt.factory_id = :factoryId` |
| RBAC-4 | P1 | path factoryId 未校验(BOLA) | controller 入口校验 path vs JWT factoryId |
| SCOPE-1 | P1 | 换源后非 YIELD 老工厂该页变空(regression) | 空态 + next-action 引导(防呆 Rule 5), 不静默空; PR/汇报点出 |
| SCOPE-3 | P1 | 日期筛选语义变(旧端点根本不接受日期参数, 是 dead path) | 注明日期筛选首次生效, 加 tooltip "按报工日期筛选" |
| YIELD-4 | P1 | processName 批量查询需 `findByFactoryIdAndIdIn`(不存在)+ 前端 fallback | 单元 1 注明新增 repo 方法 + 前端 `processName \|\| '第N道'` |
| RULE-2 | P1 | spec 说 headed 但 config 默认 headless:true | 测试节注明 headed 项目配置 / MCP headed |
| RULE-5 | P1 | ProcessIO catch 重复弹 toast(拦截器已弹) | 删 catch 里 `ElMessage.error` |
| RULE-3 | P2 | spec 误称 CI 不跑 Java 单测 | 纠正: CI `mvn verify` 真跑 Java; memory 那条只针对 Python |
| FE-VUE-3 | P2 | KPI grid repeat(5) 加卡折行 | 改 auto-fit / repeat(6) |
| FE-VUE-4 | P2 | 勿删全局 ProcessTaskItem | 注明只删本页局部 |
| FE-VUE-5 | P2 | conversionRate scale 0-100 别再 ×100 | 注释 + scale 对齐单测 |
| FE-VUE-6 | P2 | 单位 null 显 "null" | `inputUnit \|\| ''` |
| YIELD-5 | P2 | wastageRate max(0) 掩盖保水 | 详情逐道卡不显损耗列; 保水 yieldRate>1 正常显 |
| YIELD-6 | P2 | 跨单位合计行并排异单位数字误导 | 跨单位合计累计显 "—" |
| RULE-6 | P2 | 部署命令未提 bluegreen 默认 | 部署节注明(--mode bluegreen 是默认) |
| RULE-7 | P2 | DTO 勿加 @JsonProperty snake_case | 遵循既有 camelCase 自动序列化(无需 @JsonProperty) |
| TESTING-1 | P2 | 缺单批↔聚合 scale 对齐 / 空数据测试 | 补进测试节 |
| FE-VUE-1 | P1→ | allSettled 解构需同步加 yieldRes | 实施注意项(单元 3 已描述 3 元解构) |

### 实现即解决(verifier 误判"未实现"为缺陷, 非 spec 问题)

YIELD-2 / RULE-1 / SCOPE-2 / RULE-4 / INTERNAL-1 — 均为"4 单元代码尚未写"。这是 spec→实现的正常状态, 按本 spec 实现即覆盖, 无需改 spec。

### 已驳回 (verifier 判 INVALID)

- FE-VUE-2: 误以为 computed 已加但模板未更新 — 实为未开工(pre-implementation), 非遗漏。
- YIELD-3: 声称前后端 conversionRate 精度差 — 数学等价(Math.round vs HALF_UP 同结果), 且单源替换无双值并存。
- SCHEMA-1: 声称 scale 不匹配致前端错 — 两 scale 对应两端点两消费方, 不混淆; null 防护属轻微改进非功能 bug。
- INTERNAL-1: 声称单元 1/2 数据源不同致工序名错 — 前提是代码已实现, 实为 pre-implementation, 设计本身一致。

### 部署补注 (audit RULE-6)

`deploy-backend.sh --env prod` 默认 `--mode bluegreen`(零中断), 无需显式传。部署后 `systemctl restart cretas-backend` 加载新类(纯读无 flyway 迁移, 但新 Java 类仍需重启进程)。验证 `curl .../production/yield/by-process` 返聚合(非 404)。
