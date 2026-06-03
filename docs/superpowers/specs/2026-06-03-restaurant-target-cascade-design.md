# G2 餐饮目标拆分 + 达成率预警 设计 Spec

## 目标

为餐饮业态(RESTAURANT)提供年/月/周/日四级目标录入、每日 POS 实时达成率对标、以及差异预警，完全基于现有 `agg_daily` Gold 层，零外部数据依赖。

---

## 范围

### MVP In

- 2 张新表：`restaurant_target_hierarchy`（目标值）+ `restaurant_alert_config`（预警阈值）
- 3 个 Python Gold 查询函数：`daily_achievement_summary` / `hierarchy_rollup` / `alert_preview`
- 3 个 REST 端点：`POST /restaurant-targets`、`GET /restaurant-targets/achievement`、`GET /restaurant-targets/alerts`
- Java 端 `GoldFinanceClient` 新增 2 个 fetch 方法（`fetchAchievement` / `fetchAlerts`）
- 1 个 AI 意图 `TARGET_ACHIEVEMENT_QUERY`，绑定新 Gold Tool
- 前端：`TargetHierarchyEditor.vue`（目标录入级联编辑）+ `kpi/index.vue` 扩展（餐饮分支增加达成率 KPI 卡 + 7 天预警 timeline + BarChart）
- Flyway migration `V20260604_01__restaurant_target_tables.sql`（Python smartbi migration runner 管理）

### MVP Out

- 2025 历史目标回溯（不做，目标从当前录入起算）
- 供应商/广告/成本维度预警（数据不在 gold，不涉及）
- 渠道×产品二维预警（默认店铺级，后置）
- RN 移动端录入（仅 web-admin）
- 淡旺季加权分配（权重配置字段预留但 MVP 不渲染 UI）
- AI 深度原因诊断（仅达成率，原因诊断后置）

---

## 数据模型

### 复用已有表（不修改）

`agg_daily` — PRIMARY KEY `(factory_id, date, store_id)`，字段 `net_amount NUMERIC(18,2)`, `bill_count INT`，已确认存在，RLS 启用。

`dim_store` — PRIMARY KEY `store_id BIGSERIAL`，字段 `factory_id`, `name`，RLS 启用。

### 新建表 DDL（`V20260604_01__restaurant_target_tables.sql`）

```sql
-- ── restaurant_target_hierarchy: 四级目标值 ─────────────────────────
CREATE TABLE IF NOT EXISTS restaurant_target_hierarchy (
    id               BIGSERIAL PRIMARY KEY,
    factory_id       VARCHAR(50)  NOT NULL,
    kpi_kind         VARCHAR(30)  NOT NULL,   -- 'revenue' | 'bill_count'
    level            VARCHAR(10)  NOT NULL,   -- 'year' | 'month' | 'week' | 'day'
    period_key       VARCHAR(20)  NOT NULL,   -- '2026', '2026-06', '2026-W23', '2026-06-03'
    store_id         BIGINT       REFERENCES dim_store(store_id) ON DELETE SET NULL,
    target_value     NUMERIC(18,2) NOT NULL,
    distribution_weight NUMERIC(5,4) DEFAULT NULL,  -- 预留淡旺季权重(MVP不用)
    reason           VARCHAR(100) DEFAULT NULL, -- 调整原因 dropdown 值
    created_by       VARCHAR(50)  NOT NULL,
    created_at       TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at       TIMESTAMP    NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_target_grain UNIQUE (factory_id, kpi_kind, level, period_key, store_id)
);
ALTER TABLE restaurant_target_hierarchy ENABLE ROW LEVEL SECURITY;
ALTER TABLE restaurant_target_hierarchy FORCE  ROW LEVEL SECURITY;
DROP POLICY IF EXISTS tenant_isolation ON restaurant_target_hierarchy;
CREATE POLICY tenant_isolation ON restaurant_target_hierarchy FOR ALL
    USING  (factory_id = current_setting('app.factory_id', true))
    WITH CHECK (factory_id = current_setting('app.factory_id', true));
CREATE INDEX IF NOT EXISTS idx_rth_factory_level_period
    ON restaurant_target_hierarchy (factory_id, level, period_key);

-- ── restaurant_alert_config: 预警阈值 ───────────────────────────────
CREATE TABLE IF NOT EXISTS restaurant_alert_config (
    id               BIGSERIAL PRIMARY KEY,
    factory_id       VARCHAR(50)  NOT NULL,
    kpi_kind         VARCHAR(30)  NOT NULL,
    level            VARCHAR(10)  NOT NULL,
    warn_threshold   NUMERIC(5,4) NOT NULL DEFAULT 0.80,  -- 达成率低于此 → WARN
    critical_threshold NUMERIC(5,4) NOT NULL DEFAULT 0.60, -- 低于此 → CRITICAL
    store_id         BIGINT       REFERENCES dim_store(store_id) ON DELETE SET NULL,
    created_by       VARCHAR(50)  NOT NULL,
    created_at       TIMESTAMP    NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_alert_config_grain UNIQUE (factory_id, kpi_kind, level, store_id)
);
ALTER TABLE restaurant_alert_config ENABLE ROW LEVEL SECURITY;
ALTER TABLE restaurant_alert_config FORCE  ROW LEVEL SECURITY;
DROP POLICY IF EXISTS tenant_isolation ON restaurant_alert_config;
CREATE POLICY tenant_isolation ON restaurant_alert_config FOR ALL
    USING  (factory_id = current_setting('app.factory_id', true))
    WITH CHECK (factory_id = current_setting('app.factory_id', true));

-- GRANT DML（必须，历史 2 次复发 grant gap）
GRANT INSERT, UPDATE, DELETE ON restaurant_target_hierarchy TO smartbi_user;
GRANT USAGE, SELECT ON SEQUENCE restaurant_target_hierarchy_id_seq TO smartbi_user;
GRANT INSERT, UPDATE, DELETE ON restaurant_alert_config TO smartbi_user;
GRANT USAGE, SELECT ON SEQUENCE restaurant_alert_config_id_seq TO smartbi_user;

-- updated_at 自动触发器
CREATE OR REPLACE FUNCTION rth_touch_updated_at()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN NEW.updated_at = NOW(); RETURN NEW; END;
$$;
CREATE TRIGGER trg_rth_touch BEFORE UPDATE ON restaurant_target_hierarchy
    FOR EACH ROW EXECUTE FUNCTION rth_touch_updated_at();
```

---

## 后端组件

### Python — 新查询函数（`backend/python/smartbi/gold/queries.py` 追加）

#### `daily_achievement_summary`

```python
async def daily_achievement_summary(
    pool: asyncpg.Pool,
    factory_id: str,
    date_range: Tuple[date, date],
    *,
    kpi_kind: str = "revenue",
    level: str = "day",
    store_id: Optional[int] = None,
) -> Dict[str, Any]:
```

逻辑：
1. 从 `restaurant_target_hierarchy` 按 `level` + 匹配的 `period_key` 取目标值（`SET app.factory_id` 激活 RLS）
2. 从 `agg_daily` 取对应时间段 `SUM(net_amount)` 或 `SUM(bill_count)`
3. 计算 `achievement_rate = actual / target`（target 为 0 时返回 `null`，不返回 0%，防止误报）
4. `period_key` 生成规则：`day` → `'2026-06-03'`；`week` → `f"{d.year}-W{d.isocalendar()[1]:02d}"`（遵循 python-java-port.md Rule 2，用 `d.year` 不用 `isocalendar()[0]`）；`month` → `'2026-06'`；`year` → `'2026'`
5. 无数据日（POS 故障）：`agg_daily` 无该日行 → `actual=null, achievement_rate=null`，`data_missing=True`，不当作 0% 计入达成

返回形状：
```json
{
  "factory_id": "RES_3101_009",
  "kpi_kind": "revenue",
  "level": "day",
  "points": [
    {
      "period_key": "2026-06-03",
      "target": 50000.00,
      "actual": 48200.00,
      "achievement_rate": 0.964,
      "data_missing": false
    }
  ],
  "period_without_target": ["2026-06-01"]
}
```

#### `hierarchy_rollup`

```python
async def hierarchy_rollup(
    pool: asyncpg.Pool,
    factory_id: str,
    year: int,
    *,
    kpi_kind: str = "revenue",
) -> Dict[str, Any]:
```

逻辑：读取该年的年/月/周/日四级目标行，及 `agg_daily` 对应聚合实际值，返回级联树结构供编辑器回显。

返回形状：
```json
{
  "factory_id": "...", "year": 2026, "kpi_kind": "revenue",
  "year_target": 6000000.00,
  "months": [
    {"period_key": "2026-06", "target": 500000.00, "actual_ytd": 480000.00}
  ]
}
```

#### `alert_preview`

```python
async def alert_preview(
    pool: asyncpg.Pool,
    factory_id: str,
    lookback_days: int = 7,
    *,
    kpi_kind: str = "revenue",
) -> Dict[str, Any]:
```

逻辑：
1. 取最近 `lookback_days` 天的 `daily_achievement_summary`
2. 按 `restaurant_alert_config`（若存在）查阈值；若配置行不存在，**fail-closed 不触发预警**（不用默认阈值，避免误报）
3. 对每天打 `status`：`"OK"` / `"WARN"` / `"CRITICAL"` / `"NO_TARGET"` / `"DATA_MISSING"`
4. 返回 timeline + summary 计数

返回形状：
```json
{
  "factory_id": "...",
  "kpi_kind": "revenue",
  "lookback_days": 7,
  "config_exists": true,
  "timeline": [
    {"date": "2026-06-03", "achievement_rate": 0.964, "status": "OK", "target": 50000, "actual": 48200}
  ],
  "summary": {"OK": 5, "WARN": 1, "CRITICAL": 0, "NO_TARGET": 1, "DATA_MISSING": 0}
}
```

### Python — 新 REST 路由（`backend/python/smartbi/api/restaurant_targets.py` 新建）

路由注册前缀 `/api/smartbi`，挂 `APIRouter(tags=["RestaurantTargets"])`，通过 `_get_factory_id(request)` 获取租户（同 `restaurant_ops_gold.py` 第 32 行模式）。

**端点 1：`POST /restaurant-targets`**

```python
@router.post("/restaurant-targets")
async def upsert_target(request: Request, body: TargetUpsertRequest) -> Dict[str, Any]:
```

- `TargetUpsertRequest`（Pydantic）：`kpi_kind: str`，`level: str`，`period_key: str`，`target_value: float`，`store_id: Optional[int]`，`reason: Optional[str]`
- 逻辑：`INSERT ... ON CONFLICT (factory_id, kpi_kind, level, period_key, store_id) DO UPDATE SET target_value=EXCLUDED.target_value, reason=EXCLUDED.reason, updated_at=NOW()`（幂等 upsert）
- 竞态防护：`SELECT ... FOR UPDATE` 在同一事务内，`asyncpg` transaction 块
- `created_by` 从 `request.state.username`（auth middleware 设置）取，若无则用 `factory_id`
- 返回 `{success: true, data: {id, period_key, target_value, updated_at}}`

**端点 2：`GET /restaurant-targets/achievement`**

```python
@router.get("/restaurant-targets/achievement")
async def get_achievement(
    request: Request,
    start_date: str = Query(...),
    end_date: str = Query(...),
    kpi_kind: str = Query("revenue"),
    level: str = Query("day"),
    store_id: Optional[int] = Query(None),
) -> Dict[str, Any]:
```

- 调用 `daily_achievement_summary`
- RBAC strip：调用 `_apply_rbac_strip(result, _get_role(request))`（同 `gold_reads.py` 第 71-82 行模式）

**端点 3：`GET /restaurant-targets/alerts`**

```python
@router.get("/restaurant-targets/alerts")
async def get_alerts(
    request: Request,
    lookback_days: int = Query(7, ge=1, le=30),
    kpi_kind: str = Query("revenue"),
) -> Dict[str, Any]:
```

- 调用 `alert_preview`
- `config_exists=false` 时返回 `{success: true, data: {config_exists: false, timeline: [], summary: {}}}`，不报错

**端点 4：`POST /restaurant-targets/alert-config`**

```python
@router.post("/restaurant-targets/alert-config")
async def upsert_alert_config(request: Request, body: AlertConfigRequest) -> Dict[str, Any]:
```

- 允许运营配置阈值；同样 upsert on conflict grain

### Python — AI 意图 Gold Tool（`backend/python/smartbi/gold/restaurant_target_tool.py` 新建）

```python
class RestaurantTargetAchievementTool:
    """Gold Tool for TARGET_ACHIEVEMENT_QUERY — 查当前/近期达成率。"""
    async def execute(self, factory_id: str, params: dict) -> dict:
        level = params.get("level", "day")
        kpi_kind = params.get("kpi_kind", "revenue")
        # 取近 7 天达成率
        ...
```

此 Tool 注册到 AI 意图数据库，意图代码 `TARGET_ACHIEVEMENT_QUERY`，tool_name `restaurant_target_achievement`。

### Java — `GoldFinanceClient` 新增方法

文件：`backend/java/cretas-api/src/main/java/com/cretas/aims/client/GoldFinanceClient.java`

```java
/**
 * Fetch daily achievement summary from Python /api/smartbi/restaurant-targets/achievement
 */
public Map<String, Object> fetchAchievement(
        String factoryId,
        LocalDate startDate,
        LocalDate endDate,
        String kpiKind,    // "revenue" | "bill_count"
        String level       // "day" | "week" | "month" | "year"
) throws IOException { ... }

/**
 * Fetch 7-day alert preview from /api/smartbi/restaurant-targets/alerts
 */
public Map<String, Object> fetchAlerts(
        String factoryId,
        int lookbackDays,
        String kpiKind
) throws IOException { ... }
```

两个方法均复用已有的 `X-Internal-Secret` + `X-Factory-Id` + `X-User-Role` header 模式（参照 `fetchFinanceSummary` 第 140-150 行）。

---

## 前端组件

### 新建：`web-admin/src/views/restaurant/analytics/target-hierarchy.vue`

`TargetHierarchyEditor`：

- 顶部选年份（`el-date-picker type="year"`）+ kpi_kind 切换（营业额/单量，`el-tabs`）
- 年度输入框：`el-input-number`，输入后显示"月均 = 年度 / 12"的防呆提示（Rule 1 预显边界）；**不自动拆分到月**，仅提示
- 月级别：12 个月卡片，每个有 `el-input-number`；点击"按年度均分"dropdown confirm（不自动填充，需确认）
- 调整原因 `el-select`：`['季节性', '促销活动', '市场变化', '节假日', '其他']`，选"其他"才显 `el-input` 补充（Rule 3）
- 保存按钮：POST `/api/smartbi/restaurant-targets`；成功后 sticky success toast + 自动跳到 `/analytics/kpi`（Rule 5，不让用户卡在编辑页）
- 空状态（无目标）：`<EmptyState>` 组件 + "开始设置今年目标" 按钮，不显假数据

路由：`/restaurant/analytics/targets`，名称 `RestaurantTargets`，meta `title: '目标管理'`

### 修改：`web-admin/src/views/analytics/kpi/index.vue`

在 `isRestaurant` 分支（第 164 行 `v-if="isRestaurant"` 块）增加：

1. **达成率 KPI 卡**（`el-card`）：
   - 时间粒度切换器 `el-radio-group`（日/周/月），调用 `GET /api/smartbi/restaurant-targets/achievement?level=...`
   - `el-progress` 展示达成率（绿/橙/红 = OK/WARN/CRITICAL）
   - 目标值旁边显示实际值（Rule 2 上下文）
   - `data_missing=true` 的日期显示"数据缺失"灰色标记，不显示 0% 达成（Rule 1）
   - 无目标配置时显示"尚未设置目标 → 点此配置"按钮跳 `/restaurant/analytics/targets`（Rule 5）

2. **7 天预警 Timeline**（`el-card`）：
   - 调用 `GET /api/smartbi/restaurant-targets/alerts?lookback_days=7`
   - 以 `el-timeline` 渲染 7 天，每天一个节点，颜色 = status（OK=绿 / WARN=橙 / CRITICAL=红 / DATA_MISSING=灰 / NO_TARGET=浅灰）
   - `config_exists=false` 时显示"预警未配置 → 配置阈值"死端导航（Rule 5）

3. **BarChart 柱状图**（当前周 / 当前月，用 ECharts 通过 `AIQuery` composable 已有 `renderChartFromConfig` 工具方法）：
   - 目标值作横向基准线（ECharts `markLine`）
   - 实际值柱，达成率 % 作数据标签

### 新建：`web-admin/src/api/smartbi/restaurant-targets.ts`

```typescript
export interface TargetUpsertRequest {
  kpiKind: string;
  level: string;
  periodKey: string;
  targetValue: number;
  storeId?: number | null;
  reason?: string | null;
}

export interface AchievementResponse { ... }
export interface AlertResponse { ... }

export async function upsertTarget(req: TargetUpsertRequest): Promise<...>
export async function fetchAchievement(params: {...}): Promise<AchievementResponse>
export async function fetchAlerts(params: {lookbackDays?: number; kpiKind?: string}): Promise<AlertResponse>
export async function upsertAlertConfig(req: AlertConfigRequest): Promise<...>
```

所有函数使用 `pythonFetch`（`common.ts` 第 163 行），入参 camelCase → URL snake_case 手动转换（`pythonFetch` 不自动转 request 参数，只转 response key）。

---

## 数据流

```
运营 web-admin 输入年度目标
  → POST /api/smartbi/restaurant-targets
    → restaurant_targets.py upsert_target()
      → asyncpg transaction SELECT FOR UPDATE + INSERT ON CONFLICT DO UPDATE
        → restaurant_target_hierarchy 行写入（含 GRANT INSERT 已保证）

KPI看板页加载（isRestaurant=true）
  → GET /api/smartbi/restaurant-targets/achievement?level=day&start_date=...&end_date=...
    → queries.daily_achievement_summary(pool, factory_id, date_range, level="day")
      → agg_daily GROUP BY date SUM(net_amount) ← Gold 层，sub-100ms
      → restaurant_target_hierarchy WHERE level='day' AND period_key=...
      → 计算 achievement_rate = actual/target（target=0 返 null）
    → _apply_rbac_strip(result, role)  ← 非价格权限用户金额字段 null
  → FE renderChartFromConfig BarChart + markLine 基准

7 天预警 Timeline 加载
  → GET /api/smartbi/restaurant-targets/alerts?lookback_days=7
    → queries.alert_preview() → daily_achievement_summary(7天) + 查 alert_config
      → config_exists=false → fail-closed 不触发预警，返空 timeline
    → FE el-timeline 渲染 status 颜色节点

AI 意图 "本周达成率多少"
  → IntentExecutorServiceImpl → TARGET_ACHIEVEMENT_QUERY
    → RestaurantTargetAchievementTool.execute()
      → Python /api/smartbi/restaurant-targets/achievement (level=week)
    → 返回 message 含"本周目标 X，实际 Y，达成率 Z%"（含上下文，Rule 2）
```

---

## 防呆设计（对照 `.claude/rules/fool-proof-design.md` 五规则）

**Rule 1 — 预先显示边界**
- 年度目标输入框 `@input` 时实时更新"月均 = 年度 / 12"显示在输入框右侧；页面打开即 fetch 当年现有目标回填（不让用户"猜"已有值）
- KPI 看板达成率 KPI 卡加载时先 fetch 目标值，`data_missing=true` 日期显"数据缺失（POS 未上报）"灰标，不显 0%
- `target=null`（无目标配置）时达成率区域显"尚未设置目标"而非 0%

**Rule 2 — 上下文必带身份信息**
- `TargetHierarchyEditor` 标题栏：`设置目标 — {factoryId} / {year} / {kpiKind 中文}`
- KPI 看板达成率卡显示：`目标 ¥50,000 · 实际 ¥48,200 · 达成率 96.4%`（三值同时显示）
- 预警 timeline 每节点：`2026-06-03 周二 · ¥48,200 / ¥50,000 = 96.4% · WARN`

**Rule 3 — 自由文本改约束选择**
- 调整原因字段：`el-select`（`['季节性', '促销活动', '市场变化', '节假日', '其他']`），选"其他"才 `v-show` `el-input`（必填补充）
- 预警阈值：两个 `el-input-number` 带 `min/max` 约束（warn 必须 > critical，保存前前端校验 + 后端校验）

**Rule 4 — 写操作幂等防重复**
- 目标 upsert：`INSERT ... ON CONFLICT (factory_id, kpi_kind, level, period_key, store_id) DO UPDATE`，不可能重复创建
- 前端保存按钮 `:loading="saving"` + `saving=true` 后 disable 防双击；409 不会发生（upsert 设计），但若后端 500 显示 sticky error toast（4 位一体）

**Rule 5 — Dead-end 改导航**
- 无目标配置时 KPI 看板：`<EmptyState description="尚未设置营业目标" action-text="立即设置" @action="router.push('/restaurant/analytics/targets')" />`
- 预警 `config_exists=false`：`<EmptyState description="预警阈值未配置" action-text="配置预警阈值" @action="openAlertConfigDialog()" />`
- 保存目标成功后自动 `router.push('/analytics/kpi')`（不让用户卡在编辑页）

**错误 Toast 4 位一体**
- 后端 message 原样传前端（不吞）
- `duration: 0, showClose: true`（sticky error）
- `actionHint` 字段：如 `"请先设置年度目标，再设置月度目标"`

---

## 错误处理

| 场景 | 处理 |
|------|------|
| `agg_daily` 某日无行（POS 故障） | `actual=null, data_missing=true`，不报错，不当作 0% |
| `restaurant_target_hierarchy` 无该 period_key 行 | `target=null, achievement_rate=null`，status=`NO_TARGET` |
| `restaurant_alert_config` 无行 | `config_exists=false`，不触发预警，不报错（fail-closed） |
| target_value 为 0 | 后端拒绝（`ValueError: target_value must be > 0`），返 422，前端 toast "目标值必须大于 0" |
| level 不合法 | 后端 Enum 校验，返 422，前端 toast 原样 message |
| 非 RESTAURANT 业态 tenant 调用 | 两个新端点均检查 `factory_type` 或依赖 RLS；但前端已有 `isRestaurant` 门控，不渲染入口 |
| RBAC 无 canViewPrice 权限 | `_apply_rbac_strip` 将 `target_value` / `actual` / `achievement_rate` 置 null（金额类 kpi_kind=revenue）；前端 `v-if="canViewPrice"` 控制金额显示 |
| 迁移漏 GRANT | 已在 DDL 中显式包含 `GRANT INSERT, UPDATE, DELETE ON ... TO smartbi_user`，历史复发 2 次，已固化 |

**禁止降级**：以上任何场景均不返回假数据（0 / 编造达成率）。明确区分"无数据"与"达成率 0%"。

---

## 测试计划

### Python 单元测试（`backend/python/tests/test_restaurant_targets.py`）

| 测试 | 覆盖点 |
|------|--------|
| `test_upsert_idempotent` | 同一 grain 两次 upsert，行数仍为 1，值取第二次 |
| `test_achievement_rate_normal` | actual/target 计算正确，Decimal ROUND_HALF_UP（Rule 10/12） |
| `test_achievement_rate_zero_target` | target=0 时返回 `achievement_rate=null`，不抛除零 |
| `test_achievement_data_missing` | agg_daily 无该日行时返回 `data_missing=true, actual=null` |
| `test_alert_preview_no_config` | 无 alert_config 时 `config_exists=false`，timeline 空 |
| `test_alert_preview_critical` | actual < critical_threshold * target → status=CRITICAL |
| `test_hierarchy_rollup_empty` | 无目标行时返回 year_target=null，months 为空列表 |
| `test_period_key_week_boundary` | 跨年 12-30 周一：用 `d.year` 非 `isocalendar()[0]`（Rule 2 week boundary） |
| `test_decimal_serialization` | `_decimal_to_number` 不返回字符串（Rule 4） |
| `test_grant_dml_smoke` | 在真 smartbi_db 中 INSERT 一行不报 permission denied |

### 前端单元测试（Vitest，`web-admin/src/views/restaurant/analytics/__tests__/target-hierarchy.test.ts`）

| 测试 | 覆盖点 |
|------|--------|
| `renders_monthly_preview_on_year_input` | 输入年度值后月均提示文字更新 |
| `reason_dropdown_shows_textarea_only_for_other` | 选"其他"才显 textarea |
| `save_button_disabled_during_request` | loading 态 disable 防双击 |
| `empty_state_shows_navigate_button` | 无目标时渲染 EmptyState + 跳转按钮 |

### E2E 验证（headed Playwright，参照 `playwright-headed-mode.md`）

1. qhj_prod（RES_3101_009）登录 admin → `/restaurant/analytics/targets` 录入月度目标
2. 跳转 `/analytics/kpi` → 达成率 KPI 卡可见，显示目标/实际/达成率三值
3. 7 天预警 timeline 渲染，节点颜色正确
4. 无目标配置状态下 KPI 看板显示 EmptyState 而非 0%

---

## 文件结构

### Create（新建）

```
backend/python/smartbi/database/migrations/
  V20260604_01__restaurant_target_tables.sql         # 两张新表 + GRANT + 触发器

backend/python/smartbi/api/
  restaurant_targets.py                              # 4 个 REST 端点

backend/python/smartbi/gold/
  restaurant_target_tool.py                          # AI Gold Tool (TARGET_ACHIEVEMENT_QUERY)

backend/python/tests/
  test_restaurant_targets.py                         # 10 个单元测试

web-admin/src/views/restaurant/analytics/
  target-hierarchy.vue                               # TargetHierarchyEditor 组件

web-admin/src/api/smartbi/
  restaurant-targets.ts                              # API client (pythonFetch 封装)

web-admin/src/views/restaurant/analytics/__tests__/
  target-hierarchy.test.ts                           # 4 个前端单测
```

### Modify（修改）

```
backend/python/smartbi/gold/queries.py
  → 追加 daily_achievement_summary() / hierarchy_rollup() / alert_preview() 三函数

backend/python/main.py
  → 在第 977 行附近 include_router(restaurant_targets.router, prefix="/api/smartbi", ...)

backend/java/cretas-api/src/main/java/com/cretas/aims/client/GoldFinanceClient.java
  → 追加 fetchAchievement() / fetchAlerts() 两方法（复用 OkHttpClient + header 模式）

web-admin/src/views/analytics/kpi/index.vue
  → isRestaurant 分支增加达成率 KPI 卡 + 7天预警 Timeline + BarChart（约 +180 行）
  → loadRestaurantKpi() 增加两个并行 fetch（achievement + alerts）
  → 引入 restaurant-targets.ts API client

web-admin/src/router/index.ts
  → restaurant children 增加 { path: 'analytics/targets', component: target-hierarchy.vue }
```

---

## 待 Steve 拍板的决策

**D1：年度均分策略**
默认假设：年度目标 / 12 = 每月均摊，前端只显示提示不自动拆（运营手动填月度）。
选项 B：提供"一键均分"按钮，点击后自动用 12 份填充月度格（不可逆，需 confirm）。
选项 C：预留 `distribution_weight` 列，后续实现淡旺季加权（权重 CSV 上传）。
推荐：默认 A，B 作为后续快捷功能。

**D2：RBAC — 谁能录入目标**
默认假设：`factory_super_admin` + `operations_manager` 可写，`analytics:read` 只读。后端 `created_by` 用 `request.state.username`。需确认 `operations_manager` 角色是否已存在于 `PRICE_VIEW_ROLES` 或需单独白名单。

**D3：`kpi_kind` 初始范围**
默认假设：仅 `revenue`（净营业额）。`bill_count`（单量）作为可选第二维度，UI 通过 `el-tabs` 切换，但首次只强制测试 `revenue`。如果 Steve 想同时测两维度需扩展 UI。

**D4：store_id=null 语义**
默认假设：`store_id IS NULL` 表示"全品牌/所有门店汇总目标"；有 `store_id` 表示"单店目标"。两者同时存在时，查询优先使用 `store_id` 精确匹配，回退到 `NULL` 汇总。如果 qhj 只需要全品牌目标，`store_id` 始终 NULL，不需前端门店选择器。

**D5：Flyway 版本号碰撞防护**
当前 origin/main 最新 Python migration 为 `V20260531_01__smart_bi_distillation_samples.sql`，无 V20260604 文件。但实施前需执行：
```bash
git ls-tree origin/main backend/python/smartbi/database/migrations | grep V20260604
```
若有碰撞改为 `V20260604_02__...`。

---

## 依赖与并行

**依赖顺序**（必须串行）：
1. 迁移文件先写并 apply（两张表建成）→ 才能写 Python 查询函数和端点
2. Python 端点部署 → 才能接前端 API 调用
3. Java `GoldFinanceClient` 新方法仅在 AI Tool 或未来 SmartBIDashboardController 扩展时调用，可与前端并行

**可并行的工作**：
- Task A：Python migration DDL + queries.py 三函数（无前端依赖）
- Task B：Java GoldFinanceClient 两方法（无 Python 部署依赖，只依赖接口约定）
- Task C：前端 `target-hierarchy.vue` + `restaurant-targets.ts`（可 mock API 开发）
- Task D：`kpi/index.vue` 修改（可 mock API 开发）
- Task E：AI Tool + 意图数据库 INSERT（可与前端并行，只依赖 Python 端点合约）

Task A 是关键路径（阻塞 Task C/D 的真实对接，但不阻塞开发）。Tasks B/C/D/E 可三个 subagent 并行。
