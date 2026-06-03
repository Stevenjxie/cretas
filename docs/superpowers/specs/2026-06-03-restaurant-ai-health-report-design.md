# G4 餐饮 AI 经营体检表 — Feature Spec

## 目标

一键生成餐饮门店经营体检报告：红/黄/绿灯 severity 卡片 + 具体数字诊断描述 + 分时间框 RxAction 处方行动，数据来源 POS gold 层 + 点评 + finance_data，无 finance_data 时降级为 POS-only 营收侧诊断。

---

## 范围

### MVP In

- 新 Python GET endpoint: `GET /api/smartbi/restaurant/{factoryId}/health-check-report?month=`
- 复用 `RestaurantFinancialMetricsFetcher.fetch()` 取财务指标（Java 端现有组件）
- 通过 Python 内部服务调用或直接 asyncpg 查询 gold 层取 POS 指标（翻台率/折扣率/外卖占比）
- 复用 `DiagnosticsEngine(domain="restaurant", sub_sector=...)` 跑诊断
- 返回 `{diagnoses[], summary{criticalCount, warningCount, infoCount, coverageNote}, reportMeta{period, snapshotAt, factoryId, uploadId}}`
- 新增 3 个 playbook YAML：`channel_collection_rate_low.yaml`、`review_score_decline.yaml`、`delivery_dependency_high.yaml`
- `diagnostics_registry.yaml` 已有 `channel_collection_rate`、`review_score_decline`、`delivery_dependency` 三个 metric 定义但无对应 playbook 文件——补全
- 前端新视图 `HealthReportView.vue` at `/smart-bi/health-report`
- 诊断卡片墙（severity 排序，critical 前 3 展开，余折叠）
- RxAction 处方时间框 tabs（立即/本周/本月）
- 数据覆盖率 tooltip（哪些指标因数据缺失 skipped）
- 报告页脚快照时间戳 + 业态 + 周期

### MVP Out

- PDF 导出（H5 在线优先，PDF Phase 2）
- 成本卡 BOM 层面（requires_cogs flag 的 `channel_gross_margin` 指标）
- 行业子品类自动识别（用户手动传 `subSector` 参数）
- 多店对比健康报告
- sparkline 环比图表（Phase 2，优先文字数字）
- `stored_value_dependency_high.yaml` playbook（registry 有 metric 但 playbook 未建，Phase 2）
- Java Tool 层封装（`RestaurantHealthCheckReportTool`，Phase 2）

---

## 数据模型

### 新表：不需要新建表

本 feature 纯读路径——读 `smart_bi_finance_data`（REVENUE/COST）+ `smart_bi_dynamic_data`（点评 JSONB）+ gold 层 `agg_daily`/`fact_pos_transaction`。DiagnosticsEngine 用已有 YAML registry，无新持久化需求。

### 缓存键约定（内存/Redis 未来）

```
health_check:{factoryId}:{period_YYYY-MM}:{upload_id}
```

`upload_id` 取 finance_data 最新 upload_id（`RestaurantFinancialMetricsFetcher.filterToLatestUpload` 已有逻辑），POS 侧 upload_id 取 `MAX(id)` FROM `smart_bi_pg_excel_uploads` WHERE factory_id。两者任一变化缓存失效。Phase 1 用进程内 dict TTL=300s，与现有 `SectionCache` 保持一致。

### 复用 gold 表（已存在）

- `agg_daily`（`date`, `factory_id`, `net_amount`, `bill_count`, `channel`）— 翻台率需 `bill_count` + 固定桌台数（需前端/配置传入 `tableCount`，无则 skip）
- `fact_pos_transaction`（`factory_id`, `transaction_date`, `channel`, `gross_amount`, `discount_amount`）— 折扣率 + 外卖依赖度
- `smart_bi_dynamic_data`（JSONB，大众点评 72K+ 条）— 评分趋势

### 复用 finance 表（已存在）

- `smart_bi_finance_data`（`factory_id`, `record_type REVENUE/COST`, `record_date`, `category`, `actual_amount`, `total_cost`, `upload_id`）— 食材成本率/人力成本率/成本弹性

---

## 后端组件

### 1. Python 指标提取器（新建）

**文件**: `C:\Users\Steve\my-prototype-logistics\backend\python\smartbi\services\restaurant\health_check_metrics.py`

```python
class HealthCheckMetricsBuilder:
    """聚合 finance + POS + 点评 三路数据 → 统一指标 dict 供 DiagnosticsEngine.run()"""

    async def build(
        self,
        factory_id: str,
        period: str,            # "YYYY-MM" 或 "上月" 等 RestaurantFinancialMetricsFetcher 可解析格式
        sub_sector: str = "",
        table_count: int | None = None,   # 可选, 无则跳过 table_turnover
        cretas_pool: asyncpg.Pool | None = None,
        smartbi_pool: asyncpg.Pool | None = None,
        finance_metrics: dict | None = None,  # 可注入(Java 端已取), 避免二次查
    ) -> HealthCheckBundle:
        ...

@dataclass
class HealthCheckBundle:
    metrics: dict[str, float]    # 传给 DiagnosticsEngine.run() 的扁平 dict
    coverage: dict[str, str]     # metric_key → "ok"|"skipped:reason"
    period: str                  # resolved "YYYY-MM"
    upload_id: int | None
```

指标提取逻辑：

| 指标 key | 来源 | 提取方式 |
|---|---|---|
| `food_cost_ratio` | `finance_metrics["foodCostRatio"]` (0-100 scale → 除100→0-1) | 直接复用 Java Fetcher 输出 |
| `labor_cost_ratio` | `finance_metrics["laborCostRatio"]` | 同上 |
| `discount_rate` | `agg_daily` 或 `fact_pos_transaction` `discount_amount/gross_amount` | asyncpg gold 层 |
| `table_turnover` | `agg_daily.bill_count` 月合计 / (days × tableCount) | 需 `tableCount` 参数，无则 skip |
| `cost_rigidity` | `finance_metrics["costRigidity"]` | Java Fetcher 已算（None 当 revenue 未下滑 >5%） |
| `channel_collection_rate` | `(revenue - platform_commission) / revenue`，`platform_commission` 从 `fact_pos_transaction channel` 取美团/饿了么佣金估算或 finance_data COST "佣金/平台" category | asyncpg |
| `delivery_dependency` | `fact_pos_transaction` WHERE channel IN ('美团','饿了么','外卖') SUM / total | asyncpg |
| `review_score_decline` | `smart_bi_dynamic_data` JSONB 按月分组取 5 星占比环比差 | asyncpg |

注意：`food_cost_ratio` 等来自 Java Fetcher 是 0-100 scale（ratioPct 返回 `%` 数值）。DiagnosticsEngine benchmark YAML 也是 0-100 scale（`range: [35.0, 45.0]`），所以**不需要转换**，直接传入。

**关键注意**：`cost_rigidity` inline 阈值是 `>= 0.85` 健康（0-1 scale），但 `food_cost_ratio` benchmark 是 35-45（百分比 scale）。确保 `HealthCheckMetricsBuilder` 对每个 metric 保持 DiagnosticsEngine 期望的 scale，不做额外转换。

### 2. 新建 3 个 playbook YAML

**文件路径**（新建）：

```
C:\Users\Steve\my-prototype-logistics\backend\python\smartbi\knowledge\restaurant\playbooks\channel_collection_rate_low.yaml
C:\Users\Steve\my-prototype-logistics\backend\python\smartbi\knowledge\restaurant\playbooks\review_score_decline.yaml
C:\Users\Steve\my-prototype-logistics\backend\python\smartbi\knowledge\restaurant\playbooks\delivery_dependency_high.yaml
```

`channel_collection_rate_low.yaml` 结构（完整含 rx_actions）：

```yaml
diagnosis_code: channel_collection_rate_low
trigger_metric: channel_collection_rate
trigger_condition: "value < 0.78"
display_name: 渠道收款率偏低

severity_levels:
  warning:
    condition: "0.70 <= value < 0.78"
    label: 佣金偏高
    color: yellow
  critical:
    condition: "value < 0.70"
    label: 渠道严重失血
    color: red

action_plans:
  - id: 1
    name: 佣金谈判
    priority: P0
    actions:
      - 拉出最近 3 个月各平台流水, 合并提交谈判材料
      - 要求平台代表到店谈判(月流水 >5 万有谈判筹码)
      - 申请"品质商家"认证可享 1-2% 佣金折扣
    expected_impact: 佣金率 -2~3pp, 月净增 ¥3K-8K

  - id: 2
    name: 引流私域
    priority: P1
    actions:
      - 在外卖包装/收据印微信群二维码, 引导复购走微信小程序(0 抽佣)
      - 储值卡/会员积分仅线下/小程序可用, 降低平台依赖
    expected_impact: 私域比例 +10pp, 有效收款率 +2pp

rx_actions:
  - id: CCR-A01
    title: 申请美团/饿了么品质商家认证
    description: 品质商家享受 0.5-1% 佣金减免。提交近 30 天好评率>95%+营业额证明。
    owner: 店长
    timeframe: 本周内
    priority: P0
    effort: low
    expected_impact: 佣金率 -1pp, 月省 ¥2K-4K
  - id: CCR-A02
    title: 拉出平台流水谈商务合同
    description: 月流水达到阶梯协议门槛可重谈年度合同。整理 3 月流水+好评截图给 BD 经理。
    owner: 运营经理
    timeframe: 2 周
    priority: P0
    effort: medium
    expected_impact: 佣金率降 2-3pp
  - id: CCR-A03
    title: 搭建微信社群私域闭环
    description: 在打包袋加贴二维码引流微信群→推小程序复购, 私域订单 0 抽佣。
    owner: 运营经理
    timeframe: 本月
    priority: P1
    effort: medium
    expected_impact: 私域占比+10pp, 综合收款率+2pp
```

`review_score_decline.yaml` 结构要点：触发条件 `value < -0.02`（5 星占比环比下滑），rx_actions 含差评回复/服务改善/主动邀评三条。

`delivery_dependency_high.yaml` 结构要点：触发条件 `value >= 0.50`，rx_actions 含堂食引流/私域复购/平台活动减量三条。

### 3. 新 Python REST Endpoint

**文件**: `C:\Users\Steve\my-prototype-logistics\backend\python\smartbi\api\restaurant_health_check.py`

```python
router = APIRouter(tags=["RestaurantHealthCheck"])

@router.get(
    "/restaurant/{factory_id}/health-check-report",
    summary="餐饮经营体检报告",
)
async def get_health_check_report(
    factory_id: str,
    request: Request,
    month: Optional[str] = Query(None, description="YYYY-MM / 上月 / 本月, 默认上月"),
    sub_sector: str = Query("", description="行业子品类, 留空用通用 benchmark"),
    table_count: Optional[int] = Query(None, description="桌台数, 用于翻台率计算"),
) -> dict:
```

**内部调用链**：

```
request → validate factory_id (JWT tenant == factory_id, 防跨租户)
  → HealthCheckMetricsBuilder.build(factory_id, month, sub_sector, table_count, pools)
    → 并行 gather:
        Task A: RestaurantFinancialMetricsFetcher.fetch() via Java HTTP OR asyncpg direct finance_data query
        Task B: asyncpg gold/POS queries (discount_rate, delivery_dependency, channel_collection_rate)
        Task C: asyncpg smart_bi_dynamic_data review 5-star trend
  → DiagnosticsEngine(domain="restaurant", sub_sector=sub_sector).run(metrics)
  → build report response dict
  → cache result (TTL 300s keyed by factory_id+period+upload_id)
  → return {success: true, data: {...}}
```

**注意**：RestaurantFinancialMetricsFetcher 是 Java Spring Bean，不能直接被 Python 调用。Python 端需要**自己实现同等逻辑**，直接查 `smart_bi_finance_data` 表（asyncpg），复用相同 keyword 分类逻辑。实际上 `backend/python/smartbi/services/restaurant/sections/cost_rigidity.py` 和 `diagnostics.py` 已经走这条路——它们通过 `request.params["financial_data"]` 接收从 Java 端传来的财务数据。对于本 GET endpoint，Python 直接查 smartbi_db 的 `smart_bi_finance_data`（与 Java Fetcher 读同一张表，schema 已知）。

**finance_data 直查 Python helper**（在 `health_check_metrics.py` 内实现）：

```python
async def _fetch_finance_metrics(
    pool: asyncpg.Pool,
    factory_id: str,
    start_date: date,
    end_date: date,
    prev_start: date,
    prev_end: date,
) -> dict:
    """Mirror RestaurantFinancialMetricsFetcher.fetch() logic in Python.
    
    食材/人力/租金 keyword matching 与 Java 端一致:
    FOOD: 食材/原材料/食品/饮料/酒水/菜品
    LABOR: 人工/工资/薪/员工/劳务
    RENT: 租金/房租/店租/场地
    """
```

**响应结构**：

```json
{
  "success": true,
  "data": {
    "reportMeta": {
      "factoryId": "RES_3101_009",
      "period": "2026-05",
      "snapshotAt": "2026-06-03T10:30:00",
      "subSector": "川菜",
      "uploadId": 1024,
      "cacheHit": false
    },
    "summary": {
      "criticalCount": 2,
      "warningCount": 3,
      "infoCount": 0,
      "coverageNote": "成本弹性指数因环比数据不足跳过; 翻台率因未提供桌台数跳过"
    },
    "diagnoses": [
      {
        "metricKey": "food_cost_ratio",
        "metricNameZh": "食材成本率",
        "actualValue": 48.3,
        "benchmarkMedian": 40.0,
        "benchmarkRange": [35.0, 45.0],
        "status": "偏高",
        "severity": "warning",
        "deltaPp": 8.3,
        "deltaPct": 20.75,
        "descriptionZh": "食材采购成本占营业收入的比例",
        "rxActions": [...],
        "playbookId": "food_cost_ratio_high"
      }
    ]
  },
  "message": "体检报告生成成功"
}
```

**注册到 main.py**：`app.include_router(restaurant_health_check.router, prefix="/api/smartbi")`

### 4. main.py 注册

**文件**: `C:\Users\Steve\my-prototype-logistics\backend\python\main.py`

修改内容：增加一行 import + include_router，与现有 `restaurant_sections`、`restaurant_ops_gold` 等路由同等位置。

---

## 前端组件

### 1. 路由注册

**文件**: `C:\Users\Steve\my-prototype-logistics\web-admin\src\router\modules\smartbi.ts`

在 `restaurant-v2` 路由后追加：

```typescript
{
  path: 'health-report',
  name: 'SmartBIHealthReport',
  component: () => import('@/views/smart-bi/HealthReportView.vue'),
  meta: {
    requiresAuth: true,
    title: 'AI 经营体检',
    icon: 'FirstAidKit',
    module: 'analytics',
    hideForFactoryTypes: ['FACTORY'],  // 只对餐饮租户显示
  },
},
```

### 2. 主视图

**文件**: `C:\Users\Steve\my-prototype-logistics\web-admin\src\views\smart-bi\HealthReportView.vue` (新建)

组件结构：

```
HealthReportView.vue
  ├── ReportHeaderBar.vue (新建, inline subcomponent 或独立)
  │     period picker + sub_sector select + table_count input + "生成报告" button
  ├── SummaryBadgeRow.vue (新建 or inline)
  │     critical/warning/info 计数徽章 + 覆盖率 tooltip
  ├── DiagnosisCardWall.vue (新建)
  │     v-for diagnoses，渲染 DiagnosisCard
  │     前 3 critical 默认展开, 其余折叠
  └── ReportFooter.vue (inline)
        snapshot 时间戳 + 业态 + 报告说明
```

**DiagnosisCard.vue**（新建）:

```
- severity 左边栏色（critical=red, warning=amber, info=blue）
- 标题行: metricNameZh + severity badge
- 数字行: 当前 actualValue | 行业中位 benchmarkMedian | 偏差 ±deltaPp pp
- 折叠内容:
  - descriptionZh (具体诊断文案)
  - RxAction tabs: 立即(本周P0) | 本周(P1) | 本月(P2)
    每个 tab 内 RxPrescriptionCard 相同渲染逻辑（直接复用 RxPrescriptionCard.vue 组件）
  - subSectorNotes (if any)
- 覆盖率 hint: 某 metric skipped 时显示 el-tooltip 原因而非灰显整卡
```

**状态管理**（Composition API，不需要新 Pinia store）：

```typescript
// HealthReportView.vue setup()
const factoryId = computed(() => authStore.factoryId)
const month = ref('')          // 默认空→上月
const subSector = ref('')      // 用户选填
const tableCount = ref<number|null>(null)
const loading = ref(false)
const report = ref<HealthCheckReport | null>(null)
const error = ref('')

async function generateReport() {
  loading.value = true
  error.value = ''
  try {
    const res = await fetchHealthCheckReport(factoryId.value, {
      month: month.value || undefined,
      subSector: subSector.value || undefined,
      tableCount: tableCount.value || undefined,
    })
    if (!res.success) throw new Error(res.message)
    report.value = res.data
  } catch (e) {
    error.value = isAxiosError(e) ? e.response?.data?.message ?? '请求失败' : String(e)
    ElMessage({ message: error.value, type: 'error', duration: 0, showClose: true })
  } finally {
    loading.value = false
  }
}
```

### 3. API 客户端函数

**文件**: `C:\Users\Steve\my-prototype-logistics\web-admin\src\api\smartbi\healthCheck.ts` (新建)

```typescript
export interface RxAction {
  id: string; title: string; description: string;
  owner: string; timeframe: string;
  priority: 'P0' | 'P1' | 'P2'; effort: 'low' | 'medium' | 'high';
  expectedImpact: string;
}

export interface DiagnosisItem {
  metricKey: string; metricNameZh: string;
  actualValue: number; benchmarkMedian: number | null;
  benchmarkRange: [number, number] | null;
  status: string; severity: 'critical' | 'warning' | 'info';
  deltaPp: number; deltaPct: number;
  descriptionZh: string; suggestionZh: string[];
  rxActions: RxAction[];
  subSectorNotes: string[];
  playbookId: string | null;
}

export interface HealthCheckReport {
  reportMeta: {
    factoryId: string; period: string; snapshotAt: string;
    subSector: string; uploadId: number | null; cacheHit: boolean;
  };
  summary: {
    criticalCount: number; warningCount: number; infoCount: number;
    coverageNote: string;
  };
  diagnoses: DiagnosisItem[];
}

export async function fetchHealthCheckReport(
  factoryId: string,
  params: { month?: string; subSector?: string; tableCount?: number }
): Promise<{ success: boolean; data: HealthCheckReport; message: string }> {
  const res = await apiClient.get(`/smartbi/restaurant/${factoryId}/health-check-report`, {
    params: {
      month: params.month,
      sub_sector: params.subSector,
      table_count: params.tableCount,
    },
  })
  return res.data
}
```

---

## 数据流

```
用户点击"生成报告"
  ↓
HealthReportView.vue → fetchHealthCheckReport(factoryId, {month, subSector, tableCount})
  ↓ GET /api/smartbi/restaurant/{factoryId}/health-check-report?month=&sub_sector=&table_count=
  ↓
restaurant_health_check.py:get_health_check_report()
  ↓ validate tenant (JWT factory_id == path factory_id)
  ↓ check cache (factory_id + period + upload_id)
  ↓ HealthCheckMetricsBuilder.build()
      ├── asyncpg smart_bi_finance_data → _fetch_finance_metrics()
      │   Python mirror of Java RestaurantFinancialMetricsFetcher:
      │   food_cost_ratio(0-100) / labor_cost_ratio / cost_rigidity / revenue_change_pct
      ├── asyncpg agg_daily/fact_pos_transaction → discount_rate / delivery_dependency / channel_collection_rate
      └── asyncpg smart_bi_dynamic_data JSONB → review_score_decline (5星占比环比)
  ↓ metrics dict (partial OK — skipped keys recorded in coverage)
  ↓ DiagnosticsEngine(domain="restaurant", sub_sector=sub_sector).run(metrics)
      → per metric: evaluate_one() → benchmark/inline threshold → load_playbook() → extract_rx_actions()
      → sorted: critical → warning → info (healthy skipped)
  ↓ build response: {reportMeta, summary{criticalCount...coverageNote}, diagnoses[]}
  ↓ cache result
  ↓ return JSON
  ↓
HealthReportView.vue renders:
  SummaryBadgeRow (critical/warning/info count + coverageNote tooltip)
  DiagnosisCardWall:
    [critical 前3 auto-expanded]
    [remaining collapsed, expand on click]
    each DiagnosisCard:
      severity bar + actualValue vs benchmark
      RxAction tabs 立即/本周/本月
      (复用 RxPrescriptionCard.vue 渲染逻辑)
  ReportFooter (snapshotAt + period + subSector)
```

---

## 防呆设计（对照 fool-proof-design.md 五规则）

**Rule 1 — 预先显示边界，不事后报错**

首屏自动加载上月报告（不需用户主动触发选时间），如无 finance 数据则显示"仅营收侧诊断（food/labor 成本缺失，请上传财务 Excel）"而非空页面。month picker 边界：不允许选未来月份（`:max="currentMonth"`）。

**Rule 2 — 上下文必带身份信息**

报告头部固定显示 `{storeName} · {period} · {subSector || "通用行业"}` 三元素。每张诊断卡标题行含 `metricNameZh + actualValue` 具体数字，不只说"成本率偏高"。描述文案含具体百分点偏差：`"食材成本率 48.3%, 行业中位 40.0%, 偏高 +8.3pp"`。

**Rule 3 — 自由文本改约束选择**

sub_sector 用 el-select 有限选项（从 `benchmarks/*.yaml` 文件名枚举：火锅/快餐/西餐/日料/烧烤/奶茶/咖啡/中式海鲜/牛肉面/鱼类餐饮/餐饮连锁）+ "通用"选项（空 sub_sector 走 _common.yaml benchmark）。不允许用户自由输入。

**Rule 4 — 写操作幂等防重复**

本 feature 纯读，无写操作。缓存 TTL 300s 防止重复计算同一 upload_id 的报告。

**Rule 5 — Dead-end 改导航**

- coverage_note 中 "成本弹性因环比数据不足跳过" 附链接按钮 `→ 上传财务 Excel` (`router.push('/smart-bi/upload')`)
- 翻台率 skip 时显示 `el-tooltip content="请在上方输入桌台数以启用翻台率诊断"` + 输入框高亮 outline
- 整体无 finance_data 时 SummaryBadgeRow 下显示 `<el-alert>当前仅基于 POS 数据生成营收侧诊断（5/9 指标），上传财务 Excel 可解锁 4 项成本诊断</el-alert>` + `上传` 按钮

**4 位一体错误处理**

- 后端 business error（如 factory_id 不匹配）返回 `{success: false, message: "无权访问 RES_XXXX 的体检报告"}`
- 前端 catch → `ElMessage({ message: e.response.data.message, type: 'error', duration: 0, showClose: true })`
- toast sticky + showClose（duration:0）
- message 含 nextAction 提示（如"请先上传该月 finance 数据"）

---

## 错误处理（诚实失败，禁降级假数据）

1. `factory_id` 路径参数与 JWT tenant 不匹配 → 403 `{"success": false, "message": "无权访问 {factoryId} 的体检报告"}`
2. 该 factoryId 无任何 POS/finance 数据 → 200 with `diagnoses: []` + `summary.coverageNote: "本月无数据，请先上传 POS 或财务 Excel"` — **不返回模拟诊断**
3. asyncpg pool unavailable → 503 `{"success": false, "message": "数据库连接不可用，请稍后重试"}`
4. DiagnosticsEngine YAML 加载失败（如 playbook 文件缺失）→ 该 metric 的 Diagnosis 被 skip，在 coverage 中记录 `"playbook_missing"` — 其他 metric 正常返回
5. finance_data 只有 1 个月（无环比）→ `cost_rigidity` coverage = `"skipped:环比数据不足(当前仅1个月)"` — 不报错，其他指标正常
6. `review_score_decline` 点评数据 0 条 → coverage = `"skipped:暂无点评数据"` — 不报错
7. 所有指标均 healthy → `diagnoses: []`，`summary.coverageNote` 注明已检查的指标数，前端显示"本月经营体检通过，无异常指标"绿色状态

---

## 测试计划

### Python 单元测试

**文件**: `C:\Users\Steve\my-prototype-logistics\backend\python\smartbi\services\restaurant\tests\test_health_check_metrics.py`

```
TestHealthCheckMetricsBuilder:
  - test_build_finance_only_no_pos  # 只有 finance_data，POS metric skip
  - test_build_pos_only_no_finance  # 只有 POS，成本 metric skip
  - test_build_full_all_metrics     # 两路数据全齐
  - test_cost_rigidity_skip_when_revenue_not_declining  # revenue 增长时 skip
  - test_review_score_decline_skip_no_data
  - test_table_turnover_skip_no_table_count
  - test_scale_food_cost_ratio_100_scale  # 确认传入 DiagnosticsEngine 是 0-100 不是 0-1
```

**文件**: `C:\Users\Steve\my-prototype-logistics\backend\python\smartbi\services\restaurant\tests\test_health_check_playbooks.py`

```
TestNewPlaybooks:
  - test_channel_collection_rate_low_playbook_loads     # YAML 可解析
  - test_channel_collection_rate_low_has_rx_actions     # rx_actions 8 字段完整
  - test_review_score_decline_playbook_loads
  - test_delivery_dependency_high_playbook_loads
  - test_diagnostics_engine_channel_collection_rate_below_threshold  # 0.65 → critical
  - test_diagnostics_engine_review_score_decline_warning             # -0.03 → warning
```

**文件**: `C:\Users\Steve\my-prototype-logistics\backend\python\smartbi\api\test_restaurant_health_check.py` (或 tests/ 子目录)

```
TestHealthCheckEndpoint:
  - test_get_report_returns_200_with_diagnoses
  - test_get_report_wrong_factory_id_returns_403
  - test_get_report_no_data_returns_empty_diagnoses_not_error
  - test_get_report_cached_returns_cache_hit_flag
  - test_coverage_note_includes_skipped_reasons
```

### 前端单元测试（Vitest）

**文件**: `C:\Users\Steve\my-prototype-logistics\web-admin\src\views\smart-bi\__tests__\HealthReportView.spec.ts`

```
- renders SummaryBadgeRow with correct criticalCount
- DiagnosisCard auto-expands first 3 critical items
- DiagnosisCard collapses remaining items
- coverage tooltip shows skip reason
- error toast displays with sticky duration=0
- sub_sector select shows correct 11 options
- table_count input triggers report refresh
```

### E2E smoke（Playwright headed）

手动 headed verify（非 CI gate）：
1. qhj_prod (RES_3101_009) 登录 → `/smart-bi/health-report` → 选"上月"生成 → 有至少 1 张 DiagnosisCard（qhj 有 finance_data + 点评数据）
2. 点击 DiagnosisCard 展开 → RxAction tab 显示"立即/本周/本月"三 tab
3. 无 finance_data 工厂 → 显示 POS-only 降级 alert + "上传" 按钮可跳转

---

## 文件结构（Create/Modify 精确清单）

### 新建文件

```
backend/python/smartbi/services/restaurant/health_check_metrics.py
backend/python/smartbi/api/restaurant_health_check.py
backend/python/smartbi/knowledge/restaurant/playbooks/channel_collection_rate_low.yaml
backend/python/smartbi/knowledge/restaurant/playbooks/review_score_decline.yaml
backend/python/smartbi/knowledge/restaurant/playbooks/delivery_dependency_high.yaml
web-admin/src/views/smart-bi/HealthReportView.vue
web-admin/src/views/smart-bi/components/health/DiagnosisCard.vue
web-admin/src/api/smartbi/healthCheck.ts
backend/python/smartbi/services/restaurant/tests/test_health_check_metrics.py
backend/python/smartbi/services/restaurant/tests/test_health_check_playbooks.py
```

### 修改文件

```
backend/python/main.py
  → 追加 from smartbi.api.restaurant_health_check import router as health_check_router
  → app.include_router(health_check_router, prefix="/api/smartbi")

web-admin/src/router/modules/smartbi.ts
  → health-report 路由追加到 restaurant-v2 之后

web-admin/src/views/smart-bi/analysis/IndexPageView.vue 或侧边栏 menuConfig
  → 追加"AI 经营体检"入口（根据 hideForFactoryTypes=['FACTORY'] 门控，restaurantOnly）
```

### 无需改动（复用现有）

```
backend/python/smartbi/shared/diagnostics_engine.py          ← 直接实例化使用
backend/python/smartbi/knowledge/restaurant/diagnostics_registry.yaml  ← 已有所有 metric 定义，无需改动
backend/python/smartbi/knowledge/restaurant/benchmarks/_common.yaml    ← 现有 benchmark
backend/python/smartbi/services/restaurant/sections/base.py            ← SectionRequest/SectionResponse 不改
web-admin/src/views/smart-bi/components/chat/cards/RxPrescriptionCard.vue ← 直接复用
```

---

## 待 Steve 拍板的决策

**D1: finance_data 查询路径**

默认假设：Python 直接查 `smart_bi_finance_data`（asyncpg），完全 mirror Java `RestaurantFinancialMetricsFetcher` 的 keyword 分类逻辑。

选项 A（默认）: Python asyncpg 直查，keyword 分类在 Python 端实现（代码自洽，无 Java 依赖）。

选项 B: 前端先调 Java `RestaurantStorePnlOnePagerTool` 取 financial_metrics，再传给 Python endpoint。需要前端两次请求，耦合度高。

推荐选 A。

**D2: channel_collection_rate 数据源**

默认假设：从 `fact_pos_transaction.channel` 分组，平台渠道（美团/饿了么）用行业标准抽佣率（20-23%）估算 commission，没有精确佣金数据时估算。

选项 A（默认）: 估算（`commission ≈ delivery_revenue × 0.21`），coverage 标注"估算值"。

选项 B: 只有当 finance_data 有"佣金"/"平台费"类目时才计算，否则 skip。

推荐选 A 估算但在 descriptionZh 说明"基于 21% 平台佣金估算"。如果 Steve 认为估算误导性强，选 B 更诚实。

**D3: 子行业 sub_sector 默认值**

默认假设：前端默认空（走 `_common.yaml` 通用 benchmark），用户手动选才用子行业 benchmark。

选项 A（默认）: 空 = 通用，用户选填。

选项 B: 根据 factory `business_type` 标签（如 qhj=川菜，邓总=火锅）自动预填。需要 Java 端 `/api/mobile/auth/me` 返回子行业字段（当前不返回）。

推荐选 A，Phase 2 再做自动识别。

**D4: DiagnosisCard 中 sparkline 环比趋势图**

默认假设：Phase 1 纯文字数字（`deltaPp / deltaPct`），不做 sparkline。

选项 A（默认）: 文字数字，Phase 2 加图。

选项 B: 立即加简单 ECharts sparkline（4 点 mini 折线），复用现有 `echarts` import。

推荐选 A，MVP 先验证诊断质量。

**D5: 健康指标是否显示**

默认假设：`DiagnosticsEngine.run()` 已 skip severity=info+status=健康的 metric，前端只展示有异常的。健康项通过 SummaryBadgeRow "已检查 X 项，无异常" 文案体现。

选项 A（默认）: 不显示健康项卡片，通过 summary 文案体现。

选项 B: 折叠组底部追加"健康指标 (X)" 可展开列表。

推荐选 A，减少认知负荷（防呆原则）。

---

## 依赖与并行

### Phase 1（阻塞，必须顺序）

1. 新建 3 个 playbook YAML（`channel_collection_rate_low` / `review_score_decline` / `delivery_dependency_high`）— 前置所有 DiagnosticsEngine 测试
2. `health_check_metrics.py` — 依赖 asyncpg pool 接口（已存在于 `smartbi.config`），独立开发
3. `restaurant_health_check.py` endpoint — 依赖 (1) + (2)
4. `main.py` 注册

### Phase 2（可并行，无文件冲突）

- 前端 `healthCheck.ts` API 客户端 ← 不依赖后端完成（可 mock 开发）
- `DiagnosisCard.vue` + `HealthReportView.vue` ← 可与后端并行

### 并行工作建议

**Subagent 并行: 可以**

- Subagent A: Python 后端（health_check_metrics.py + endpoint + 3 playbook YAML + 测试）
- Subagent B: Vue 前端（HealthReportView + DiagnosisCard + healthCheck.ts + router 修改）

文件无重叠（A 改 python/ 和 knowledge/，B 改 web-admin/），无冲突风险。

**多 Chat 并行: 不建议**（与当前 feat/restaurant-dashboard-default-allgold 分支有 main.py 和 smartbi.ts 潜在冲突，建议 merge main 后再开 worktree 按 `worktree-and-main-only-deploy.md` 规则操作）。

### Flyway 迁移

本 feature 无新数据库表，不需要 migration。如 Phase 2 加缓存 Redis key 或报告快照表再补迁移。


---

## ✅ 决策已拍板 (2026-06-03, Steve)

- **D2 = 佣金估算 + 标注 (DECIDED)**: 外卖平台佣金用 `backend/python/smartbi/knowledge/restaurant/pos/commission_rates.yaml` 的费率(不硬编码 21%); `descriptionZh` 必标注"基于平台佣金估算"。无 finance_data 佣金类目时走此估算路径。
- **D3 = sub_sector benchmark 映射 (DECIDED)**: 子品类 benchmark 均已存在, 无需新建 YAML —
  - 邓总(海鲜正餐) → `knowledge/restaurant/benchmarks/中式海鲜.yaml`
  - qhj `RES_3101_009`(青花椒酸菜鱼) → `knowledge/restaurant/benchmarks/鱼类餐饮.yaml`
  - 需建 factory_id → sub_sector 映射(配置/表), qhj 固定映射到 鱼类餐饮; 未配置工厂回退 `_common` 通用 benchmark。
- D1 = 默认 A: Python asyncpg 直查 `smart_bi_finance_data`, keyword 分类 mirror Java `RestaurantFinancialMetricsFetcher`。
- D4 = 默认 A: Phase 1 纯数字(deltaPp/deltaPct), 不做 sparkline。
- D5 = 默认 A: 健康项不单独显示, 通过 summary "已检查 X 项无异常" 文案体现。
