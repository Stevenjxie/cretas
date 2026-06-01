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
- 对每个 step: `step.workProcessTaskId` → `taskRepo.findByFactoryIdAndId` → `task.getWorkProcessId()` → `processRepo.findById` → `WorkProcess.getProcessName()` → `step.setProcessName(name)`。
- 为避免 N 次查询, 批量收集 `workProcessTaskId`s 一次性查 tasks, 再批量查 work_processes(`processRepo.findAllById(distinctWorkProcessIds)`), 组 map 后 set。
- 查不到名(task/process 已删)→ 留 null, 前端显示 `processOrder` fallback。

### 验证

- 既有 `getYield` 行为不变, 仅 `steps[].processName` 从 null → 真实工序名。
- RN 屏不受影响(它用 processOrder)。
- 单测: 给定 3 个 task 关联 3 个 work_process(processName "处理"/"滚揉"/"末道"), `getYield` 返回的 steps processName 正确。

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

放在**新 controller** `YieldAnalysisController`(`@RequestMapping("/api/mobile/{factoryId}/production/yield")`, factory-scoped), 不放进 batch-scoped 的 `YieldReportController`(它的 mapping 含 `{batchId}`)。`@RequireModule("production")` + `production:read` 权限(与 ProcessIO 页一致, module=production)。

### DTO

`ProcessYieldAggDTO`(字段名对齐前端 `ProcessIORow`, 让前端零转换):

```java
public class ProcessYieldAggDTO {
    private String processName;       // 工序名 (work_processes.process_name)
    private BigDecimal inputQuantity;  // Σ input_quantity, scale 2
    private BigDecimal outputQuantity; // Σ output_quantity, scale 2
    private BigDecimal conversionRate; // 出成率% = Σout/Σin*100, scale 1 (0-100)
    private BigDecimal wastageRate;    // 损耗率% = max(0, (Σin-Σout)/Σin*100), scale 1
    private String unit;               // 投入单位 (取该工序 input_unit)
    private Integer batchCount;        // COUNT(DISTINCT batch_id)
}
```

> **scale 约定差异(有意为之)**: 既有 `/yield` 端点的 `yieldRate`/`cumulativeYieldRate` 是 **0-1**(0.3828, RN 已消费, 不改)。新 `/by-process` 的 `conversionRate` 是 **0-100**(38.28, 显示层端点, 对齐 ProcessIO 前端 `getConversionColor(rate>=90)`)。两端点两消费方两 scale, 不互通。
> BigDecimal 序列化遵循既有 Jackson 行为(本项目非 byte-parity 端点, 无 strict 约束)。

### 聚合查询

新 repository 方法(native SQL, 因跨 3 表 join + GROUP BY):

```sql
SELECT wp.process_name              AS process_name,
       SUM(pr.input_quantity)       AS total_input,
       SUM(pr.output_quantity)      AS total_output,
       MIN(pr.input_unit)           AS unit,
       COUNT(DISTINCT pr.batch_id)  AS batch_count
FROM production_reports pr
JOIN work_process_tasks wpt ON pr.work_process_task_id = wpt.id AND wpt.deleted_at IS NULL
JOIN work_processes wp      ON wpt.work_process_id = wp.id
WHERE pr.factory_id = :factoryId
  AND pr.report_type = 'YIELD'
  AND pr.deleted_at IS NULL
  AND (CAST(:startDate AS date) IS NULL OR pr.report_date >= :startDate)
  AND (CAST(:endDate   AS date) IS NULL OR pr.report_date <= :endDate)
  AND (CAST(:productTypeId AS varchar) IS NULL OR wpt.product_type_id = :productTypeId)
GROUP BY wp.id, wp.process_name
ORDER BY MIN(wpt.process_order)
```

> 按 `wp.id` 分组(不是按 name 字符串)— 同名不同工序定义算两行, 更准。
> `CAST(:param AS type) IS NULL` 是 PG parameter-side null 检查的必需写法(见 `.claude/rules/database-entity-sync.md` 的 PG 类型推断 rule)。
> `report_date` 是 YIELD 报工的业务日期, 与 ProcessIO 现有日期筛选语义一致。

### Service

`YieldAnalysisService.aggregateByProcess(factoryId, startDate, endDate, productTypeId)`:
- 调 repository 拿聚合行(Object[] 或投影接口)
- 每行算 `conversionRate = total_output/total_input*100`(scale 1, HALF_UP, total_input>0 else 0)、`wastageRate = max(0, (in-out)/in*100)`
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
有 yield 时, 在「实际产量」后插入新 KPI 卡「累计出成率」= `formatPercent(yieldData.cumulativeYieldRate * 100)`(DTO 是 0-1, ×100 显示; null 显 `-`)。用 `v-if="yieldData"` 控制该卡显隐。

> `formatPercent` 已存在(`n.toFixed(1) + '%'`)。`cumulativeYieldRate` 0.3828 ×100 = 38.28 → "38.3%"。

### 新卡「出成率·逐道报工」

`v-if="yieldData && yieldData.steps.length"`, 放进 `detail-grid`(2 列卡片区), `el-table :data="yieldData.steps"`:

| 列 | 字段 | 渲染 |
|---|---|---|
| 道 | processOrder | 数字 |
| 工序 | processName | 文本(null → "工序"+processOrder fallback) |
| 投入 | totalInput | `formatNum(totalInput) + ' ' + inputUnit` |
| 产出 | totalOutput | `formatNum(totalOutput) + ' ' + outputUnit` |
| 出成率 | yieldRate | `unitComparable ? formatPercent(yieldRate*100) : '—'`; `yieldAlert` 非空 → 文字标红 + tooltip 显 alert |
| 结转 | carryover | `carryover==null ? '—' : formatNum(carryover)`; `>0` 高亮(琥珀) |

表格下方一行**合计文本**(不用 `el-table show-summary` — 那个按列求和, 不符): `firstStepInput {firstStepInputUnit} → lastStepOutput {lastStepOutputUnit}  累计 {cumulativeYieldRate*100}%`。

> 单位不可比工序(`unitComparable=false`, 如末道 kg→盒)出成率显 "—" 仅展示量, 与后端语义一致。

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

- 删 `aggregateByProcess` 函数 + `ProcessTaskItem` interface(不再用)。
- 新增 `ProcessYieldAgg` interface 匹配 DTO。
- `ProcessIORow` interface 不变, KPI computed / 筛选 / 表格 / 图例 / 样式全不动。
- 错误处理沿用现有(actionHint sticky toast)。

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
| 后端单测 | `getYield` processName enrich 正确; `aggregateByProcess` 跨批求和 + conversionRate 计算 + 日期过滤 + 空数据 |
| 后端 IT | 金标准猪舌(998→382.08)聚合后"处理/滚揉/末道"三工序出成率正确(可 @Disabled pg-only, 复用 Phase A IT 模式) |
| 前端 | 无单测惯例; headed Playwright 实测 (per `.claude/rules/playwright-headed-mode.md`): 批次详情 KPI 回填 + 逐道卡 + ProcessIO 聚合表 |

> 注意 CI 只跑 flake8 + `backend/python/tests/`(per memory `feedback_ci_python_lint_test_does_not_run_unit_tests`), Java 单测靠本地 `mvn test` 真跑(surefire-reports 看 PASS=N FAIL=0), 不能凭 "CI 绿" 当验证。

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

后端(1+2)先于前端(3+4)。每单元完成即可独立测试。
