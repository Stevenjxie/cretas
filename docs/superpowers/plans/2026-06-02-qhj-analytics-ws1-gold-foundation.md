# WS1 — Gold 聚合地基 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让所有分析页能"自动从 gold 层聚合、默认全部历史、缓存秒回",建共享前端 composable + 让 gold 端点 start/end 可空(=全部历史)+ 缓存层 + 补 3 个缺失聚合端点。

**Architecture:** Python gold 端点 (`gold_reads.py` / `restaurant_ops_gold.py`) 的查询函数 `_validate_range` 改成接受 `Optional[date]`(空=不加日期过滤=全部历史);新增 `gold_read_cache` (镜像 `narrative_cache` 的 sha256-key + TTL + RLS-GUC 模式);前端新增 `useGoldAnalytics` composable 复用 `web-admin/src/api/smartbi/gold.ts` + `pythonFetch`,默认 range=null;补 `dish-quadrant` / `multi-store-comparison` / `trend-bundle` 三个端点(复用现有 gold 表)。

**Tech Stack:** Python FastAPI + asyncpg (gold 层), Vue 3 composable + TypeScript, PostgreSQL gold 物化表 (agg_daily / agg_product / agg_daily_order_type_meal / fact_pos*)。

**部署:** Python → `deploy-smartbi-python.sh --env prod`;前端 composable 随 WS2-5 的 web-admin 部署。新 gold 表/列(若有)迁移必带 `GRANT INSERT/UPDATE + sequence` 给 smartbi_user。

---

## File Structure

| 文件 | 职责 | 动作 |
|---|---|---|
| `backend/python/smartbi/gold/queries.py` | gold 查询函数 | 改 `_validate_range` 接受 Optional;`finance_summary`/`daily_trend`/`kpi_summary` 等 date_range 元素可空 |
| `backend/python/smartbi/gold/gold_read_cache.py` | gold 读结果缓存 | **新建** (镜像 narrative_cache) |
| `backend/python/smartbi/api/gold_reads.py` | gold REST 端点 | start/end 改 Optional;包缓存;加 `/trend-bundle` |
| `backend/python/smartbi/api/restaurant_ops_gold.py` | 餐饮 gold 端点 | 加 `/menu-quadrant`、`/store-comparison` |
| `backend/python/smartbi/gold/restaurant_queries.py` (或现有餐饮查询模块) | 餐饮聚合查询 | 加 `menu_quadrant` / `store_comparison` 查询函数 |
| `web-admin/src/composables/useGoldAnalytics.ts` | 前端共享 gold 拉取 | **新建** |
| `backend/python/smartbi/database/migrations/V20260910_01__gold_read_cache.sql` | 缓存表 | **新建** (含 GRANT) |

---

## Task 1: `_validate_range` 接受 Optional[date]

**Files:**
- Modify: `backend/python/smartbi/gold/queries.py:41-43`
- Test: `backend/python/tests/test_gold_validate_range.py` (new)

- [ ] **Step 1: 写失败测试**

```python
# backend/python/tests/test_gold_validate_range.py
from datetime import date
import pytest
from smartbi.gold.queries import _validate_range

def test_both_none_ok():
    # 全部历史: start/end 都空 → 不报错
    _validate_range(None, None)

def test_one_none_ok():
    _validate_range(None, date(2026, 1, 1))
    _validate_range(date(2026, 1, 1), None)

def test_start_after_end_raises():
    with pytest.raises(ValueError):
        _validate_range(date(2026, 2, 1), date(2026, 1, 1))

def test_normal_range_ok():
    _validate_range(date(2026, 1, 1), date(2026, 2, 1))
```

- [ ] **Step 2: 跑测试确认失败**

Run: `cd backend/python && python -m pytest tests/test_gold_validate_range.py -v`
Expected: FAIL (`test_both_none_ok` 抛 TypeError: '>' not supported between NoneType — 当前 `_validate_range(start: date, end: date)` 对 None 不安全)

- [ ] **Step 3: 改实现接受 Optional**

```python
# backend/python/smartbi/gold/queries.py (替换 line 41-43)
from typing import Optional

def _validate_range(start: Optional[date], end: Optional[date]) -> None:
    """校验日期区间。start/end 任一为 None 表示该侧不限 (= 全部历史)。
    只有两侧都非 None 且 start > end 才报错。"""
    if start is not None and end is not None and start > end:
        raise ValueError(f"start {start} > end {end}")
```

- [ ] **Step 4: 跑测试确认通过**

Run: `cd backend/python && python -m pytest tests/test_gold_validate_range.py -v`
Expected: PASS (4 passed)

- [ ] **Step 5: 让查询函数对 None 不加日期过滤**

对 `queries.py` 中每个接收 `date_range: Tuple[date,date]` 的函数 (`finance_summary` 595, `daily_trend` 60, `kpi_summary` 380, `top_products`, `channel_breakdown`, `staff_ranking`, `discount_breakdown`, `order_type_mix`),把 SQL 的日期过滤改成条件式。模式 (以 finance_summary 为例):

```python
# 旧: WHERE a.factory_id = $1 AND a.date BETWEEN $2 AND $3
# 新: 用动态条件 + 参数列表
start, end = date_range
_validate_range(start, end)
conds = ["a.factory_id = $1"]
params: list = [factory_id]
if start is not None:
    params.append(start); conds.append(f"a.date >= ${len(params)}")
if end is not None:
    params.append(end); conds.append(f"a.date <= ${len(params)}")
where = " AND ".join(conds)
sql = f"SELECT ... FROM agg_daily a WHERE {where} ..."
rows = await conn.fetch(sql, *params)
```

对每个函数套用同一模式 (date 列名按各表: agg_daily 用 `date`, agg_product 用 `month`)。**保留** top_n / order 等其它参数不变。

- [ ] **Step 6: 加查询函数 all-history 测试 (mock pool)**

```python
# 追加到 test_gold_validate_range.py 或新 test_gold_queries_allhistory.py
# 用 monkeypatch 替换 conn.fetch, 断言 None range 时 SQL 不含 BETWEEN / 不传日期参数
```
(实现者按 `python-java-port.md` 的 mock pattern 写;断言 `None` range → 生成的 SQL 无 `>=`/`<=` 日期条件)

- [ ] **Step 7: Commit**

```bash
git add backend/python/smartbi/gold/queries.py backend/python/tests/test_gold_validate_range.py
git commit -m "feat(gold): _validate_range + 查询函数支持 Optional 日期 (空=全部历史)"
```

---

## Task 2: `gold_read_cache` 缓存层 + 迁移

**Files:**
- Create: `backend/python/smartbi/gold/gold_read_cache.py`
- Create: `backend/python/smartbi/database/migrations/V20260910_01__gold_read_cache.sql`
- Test: `backend/python/tests/test_gold_read_cache.py`

参考实现: `backend/python/smartbi/agent/narrative_cache.py` (sha256 key + RLS GUC + TTL + ON CONFLICT upsert)。

- [ ] **Step 1: 迁移 (含 GRANT — 否则静默写失败, 见 feedback_smartbi_table_grant_gap)**

```sql
-- V20260910_01__gold_read_cache.sql
CREATE TABLE IF NOT EXISTS gold_read_cache (
    factory_id   varchar(64)  NOT NULL,
    cache_key    varchar(64)  NOT NULL,   -- sha256(endpoint|range|role|params)
    payload      jsonb        NOT NULL,
    created_at   timestamptz  NOT NULL DEFAULT now(),
    expires_at   timestamptz  NOT NULL,
    PRIMARY KEY (factory_id, cache_key)
);
CREATE INDEX IF NOT EXISTS idx_grc_expires ON gold_read_cache (expires_at);
ALTER TABLE gold_read_cache ENABLE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS grc_tenant ON gold_read_cache;
CREATE POLICY grc_tenant ON gold_read_cache
  USING (factory_id = current_setting('app.factory_id', true)
         OR current_setting('app.factory_id', true) = '__internal__');
-- GRANT (smartbi_user 写) — 必须, 否则 fail-open 吞掉静默 0 写
GRANT SELECT, INSERT, UPDATE, DELETE ON gold_read_cache TO smartbi_user;
```

- [ ] **Step 2: 写失败测试**

```python
# test_gold_read_cache.py
import pytest
from smartbi.gold.gold_read_cache import compute_cache_key

def test_cache_key_deterministic():
    k1 = compute_cache_key("finance-summary", None, None, "factory_super_admin", {"top_n": 10})
    k2 = compute_cache_key("finance-summary", None, None, "factory_super_admin", {"top_n": 10})
    assert k1 == k2 and len(k1) == 64

def test_cache_key_varies_by_role():
    # 角色不同 (营收脱敏不同) → key 不同
    a = compute_cache_key("finance-summary", None, None, "factory_super_admin", {})
    b = compute_cache_key("finance-summary", None, None, "viewer", {})
    assert a != b

def test_cache_key_varies_by_range():
    a = compute_cache_key("finance-summary", "2026-01-01", "2026-02-01", "r", {})
    b = compute_cache_key("finance-summary", None, None, "r", {})
    assert a != b
```

- [ ] **Step 3: 跑测试确认失败** → `python -m pytest tests/test_gold_read_cache.py -v` (模块不存在)

- [ ] **Step 4: 实现 cache 服务**

```python
# backend/python/smartbi/gold/gold_read_cache.py
import hashlib, json
from datetime import datetime, timedelta, timezone
from typing import Any, Optional

def compute_cache_key(endpoint, start, end, role, params: dict) -> str:
    parts = [endpoint or "", (start or ""), (end or ""), (role or ""),
             json.dumps(params or {}, sort_keys=True, ensure_ascii=False)]
    return hashlib.sha256("|".join(parts).encode("utf-8")).hexdigest()

class GoldReadCache:
    def __init__(self, pool): self._pool = pool
    async def get(self, factory_id: str, cache_key: str) -> Optional[Any]:
        async with self._pool.acquire() as conn:
            async with conn.transaction():
                await conn.execute("SELECT set_config('app.factory_id',$1,true)", factory_id)
                row = await conn.fetchrow(
                    "SELECT payload FROM gold_read_cache WHERE factory_id=$1 AND cache_key=$2 AND expires_at>NOW()",
                    factory_id, cache_key)
        return json.loads(row["payload"]) if row else None
    async def put(self, factory_id, cache_key, payload: Any, ttl_hours: int = 6):
        exp = datetime.now(timezone.utc) + timedelta(hours=ttl_hours)
        async with self._pool.acquire() as conn:
            async with conn.transaction():
                await conn.execute("SELECT set_config('app.factory_id',$1,true)", factory_id)
                await conn.execute(
                    """INSERT INTO gold_read_cache (factory_id,cache_key,payload,expires_at)
                       VALUES ($1,$2,$3::jsonb,$4)
                       ON CONFLICT (factory_id,cache_key) DO UPDATE
                       SET payload=EXCLUDED.payload, expires_at=EXCLUDED.expires_at, created_at=NOW()""",
                    factory_id, cache_key, json.dumps(payload, ensure_ascii=False, default=str), exp)
```

- [ ] **Step 5: 跑测试确认通过** → 3 passed

- [ ] **Step 6: 在 gold_reads.py 包一层缓存** (示例 finance-summary, 其余同模式)

在端点 handler 里: 算 `cache_key` → `cache.get` 命中直接返回 → miss 则查 gold + `cache.put`。role 取 `request.headers.get("X-User-Role")` (脱敏维度)。`__internal__` 路径不缓存或单独 key。

- [ ] **Step 7: Commit**

```bash
git add backend/python/smartbi/gold/gold_read_cache.py backend/python/smartbi/database/migrations/V20260910_01__gold_read_cache.sql backend/python/smartbi/api/gold_reads.py backend/python/tests/test_gold_read_cache.py
git commit -m "feat(gold): gold_read_cache 缓存层 (含 GRANT) + finance-summary 包缓存"
```

---

## Task 3: `useGoldAnalytics` 前端 composable

**Files:**
- Create: `web-admin/src/composables/useGoldAnalytics.ts`
- Test: `web-admin/src/composables/__tests__/useGoldAnalytics.spec.ts`
- Reference: `web-admin/src/api/smartbi/gold.ts` (已有 getFinanceSummary 等), `web-admin/src/api/smartbi/common.ts` (pythonFetch)

- [ ] **Step 1: 写失败测试** (vitest, mock pythonFetch)

```ts
// useGoldAnalytics.spec.ts
import { describe, it, expect, vi } from 'vitest';
import { useGoldAnalytics } from '../useGoldAnalytics';

vi.mock('@/api/smartbi/common', () => ({
  pythonFetch: vi.fn(async (url: string) => ({ success: true, data: { url } })),
}));

describe('useGoldAnalytics', () => {
  it('默认 range=null → 调端点不带日期参数 (全部历史)', async () => {
    const { pythonFetch } = await import('@/api/smartbi/common');
    const { reload } = useGoldAnalytics({ endpoints: ['finance-summary'], autoLoad: false, factoryId: 'F1' });
    await reload();
    const calledUrl = (pythonFetch as any).mock.calls[0][0] as string;
    expect(calledUrl).not.toContain('start_date');
  });
  it('传 range → 带日期参数', async () => {
    const { pythonFetch } = await import('@/api/smartbi/common');
    (pythonFetch as any).mockClear();
    const { reload, range } = useGoldAnalytics({ endpoints: ['finance-summary'], autoLoad: false, factoryId: 'F1' });
    range.value = ['2026-01-01', '2026-02-01'];
    await reload();
    expect((pythonFetch as any).mock.calls[0][0]).toContain('start_date=2026-01-01');
  });
});
```

- [ ] **Step 2: 跑测试确认失败** → `cd web-admin && npx vitest run src/composables/__tests__/useGoldAnalytics.spec.ts` (模块不存在)

- [ ] **Step 3: 实现 composable**

```ts
// web-admin/src/composables/useGoldAnalytics.ts
import { ref, type Ref } from 'vue';
import { pythonFetch } from '@/api/smartbi/common';

export interface GoldAnalyticsOpts {
  endpoints: string[];           // 如 ['finance-summary','daily-trend']
  factoryId: string;
  range?: [string, string] | null;
  autoLoad?: boolean;            // 默认 true
}

export function useGoldAnalytics(opts: GoldAnalyticsOpts) {
  const range: Ref<[string, string] | null> = ref(opts.range ?? null);  // null = 全部历史
  const data = ref<Record<string, any>>({});
  const loading = ref(false);
  const error = ref<string | null>(null);

  function buildUrl(ep: string): string {
    const q = new URLSearchParams();
    q.set('factory_id', opts.factoryId);
    if (range.value) { q.set('start_date', range.value[0]); q.set('end_date', range.value[1]); }
    return `/api/smartbi/gold/${ep}?${q.toString()}`;
  }

  async function reload() {
    loading.value = true; error.value = null;
    try {
      const results = await Promise.all(opts.endpoints.map(async ep => {
        const res = await pythonFetch(buildUrl(ep)) as { success: boolean; data?: any; message?: string };
        if (!res.success) throw new Error(res.message || `${ep} 加载失败`);
        return [ep, res.data] as const;
      }));
      data.value = Object.fromEntries(results);
    } catch (e: any) {
      error.value = e?.message || '分析数据加载失败';
    } finally {
      loading.value = false;
    }
  }

  if (opts.autoLoad !== false) reload();
  return { data, loading, error, range, reload };
}
```

- [ ] **Step 4: 跑测试确认通过** → 2 passed

- [ ] **Step 5: Commit**

```bash
git add web-admin/src/composables/useGoldAnalytics.ts web-admin/src/composables/__tests__/useGoldAnalytics.spec.ts
git commit -m "feat(web): useGoldAnalytics composable (默认全部历史, 复用 gold 端点)"
```

---

## Task 4: `/menu-quadrant` 端点 (菜品四象限 — 收入模式)

**Files:**
- Modify: `backend/python/smartbi/api/restaurant_ops_gold.py` (加路由)
- Modify: 餐饮聚合查询模块 (加 `menu_quadrant` 查询)
- Test: `backend/python/tests/test_menu_quadrant.py`

四象限 = 各菜品 (销量 qty vs 单品营收 revenue),按 qty 中位数 / revenue 中位数 分 4 象限。复用 `agg_product` (factory_id, product_id, month, qty_sold, revenue) + `dim_product` (name)。毛利模式已有 `/restaurant-ops/gross-margin`,本端点只做收入模式。

- [ ] **Step 1: 写失败测试** (mock pool, 合成 4 个菜品 → 断言象限分类 + 中位数)
- [ ] **Step 2: 跑确认失败**
- [ ] **Step 3: 实现查询 + 端点**

```python
# 查询函数 (餐饮 gold 查询模块)
async def menu_quadrant(pool, factory_id, date_range):
    start, end = date_range
    _validate_range(start, end)
    conds = ["a.factory_id=$1"]; params=[factory_id]
    if start: params.append(start); conds.append(f"a.month>=${len(params)}")
    if end: params.append(end); conds.append(f"a.month<=${len(params)}")
    where=" AND ".join(conds)
    rows = await (await pool.acquire()).fetch(f"""
        SELECT COALESCE(cd.canonical_name,p.name) AS name,
               SUM(a.qty_sold) AS qty, SUM(a.revenue) AS revenue
        FROM agg_product a JOIN dim_product p ON p.product_id=a.product_id
        LEFT JOIN dim_canonical_dish cd ON cd.canonical_dish_id=p.canonical_dish_id
        WHERE {where} GROUP BY 1 HAVING SUM(a.revenue)>0
        ORDER BY revenue DESC""", *params)
    items=[{"name":r["name"],"qty":int(r["qty"]),"revenue":float(r["revenue"])} for r in rows]
    qmed=_median([i["qty"] for i in items]); rmed=_median([i["revenue"] for i in items])
    for i in items:
        i["quadrant"] = ("明星" if i["qty"]>=qmed and i["revenue"]>=rmed else
                         "金牛" if i["revenue"]>=rmed else
                         "潜力" if i["qty"]>=qmed else "瘦狗")
    return {"items":items,"qtyMedian":qmed,"revenueMedian":rmed}
```
(`_median` helper: 排序取中值;空列表返 0)
端点 `@router.get("/menu-quadrant")`: 解析 start/end (Optional),调 menu_quadrant,返 `{success,data}`。

- [ ] **Step 4-5: 跑通过 + Commit**

```bash
git commit -m "feat(gold): /menu-quadrant 菜品四象限收入模式端点"
```

---

## Task 5: `/store-comparison` 端点 (门店对比)

**Files:**
- Modify: `backend/python/smartbi/api/restaurant_ops_gold.py`
- 餐饮聚合查询模块: 加 `store_comparison`
- Test: `backend/python/tests/test_store_comparison.py`

复用 `finance_summary` 的门店展开 (已有 store rank) + `agg_daily` 算各店营收/单数/客单。返回 `{stores:[{name,revenue,orderCount,avgTicket}], medianRevenue, weakStores}`。

- [ ] Step 1-2: 失败测试 (mock pool, 多店 → 断言 avgTicket=revenue/orderCount + weakStores 低于中位)
- [ ] Step 3: 实现 (SQL GROUP BY store_id, JOIN dim_store name);end/start Optional
- [ ] Step 4-5: 跑通过 + Commit `feat(gold): /store-comparison 门店对比端点`

---

## Task 6: `/trend-bundle` 端点 (趋势分析合一)

**Files:**
- Modify: `backend/python/smartbi/api/gold_reads.py`
- Test: `backend/python/tests/test_trend_bundle.py`

一次返回趋势页四块: `dailyTrend` (复用 daily_trend) + `weekdayWeekend` (agg_daily `EXTRACT(DOW FROM date)` 分组) + `monthlyTrend` (按月聚合) + `revenueYoY` (同比, 有去年数据才算)。避免前端多次往返 (#8 慢)。

- [ ] Step 1-2: 失败测试 (mock → 断言四块都在 + weekend/weekday 日均正确)
- [ ] Step 3: 实现 (组合现有查询 + DOW 分组);Optional range
- [ ] Step 4-5: 跑通过 + Commit `feat(gold): /trend-bundle 趋势分析合一端点`

---

## Task 7: 部署 + prod 真库验证

- [ ] **Step 1: 部署 Python** (从 main; 先 merge)

```bash
git checkout main && git pull origin main
bash scripts/deploy/deploy-smartbi-python.sh --env prod   # Step 3.5 自动 apply V20260910_01 迁移
```

- [ ] **Step 2: prod 真库验证 (qhj RES_3101_009, 全部历史)**

用 X-Internal-Secret + X-Factory-Id 直连 (绕 JWT, 见 reference):
```bash
ssh root@47.100.235.168 'SECRET=$(grep INTERNAL_API_SECRET /www/wwwroot/cretas/.env.prod|cut -d= -f2-);
for ep in finance-summary menu-quadrant store-comparison trend-bundle; do
  echo "=== $ep (全部历史, 不传日期) ===";
  curl -s -H "X-Internal-Secret: $SECRET" -H "X-Factory-Id: RES_3101_009" \
    "http://localhost:8083/api/smartbi/gold/$ep?factory_id=RES_3101_009" | head -c 300; echo;
done'
```
Expected: 每个端点返真数据 (非空 items/stores), 不传日期=全部历史口径。

- [ ] **Step 3: 验缓存** — 同请求第二次 `gold_read_cache` 命中 (查表行数 >0)。

- [ ] **Step 4: 验 GRANT** — `SELECT privilege_type FROM information_schema.role_table_grants WHERE table_name='gold_read_cache' AND grantee='smartbi_user'` 应含 INSERT/UPDATE。

- [ ] **Step 5: Commit (若验证中有修)**

---

## Self-Review (spec 覆盖)

- ✅ §2.1 composable → Task 3
- ✅ §2.2 缺失端点 → Task 4/5/6
- ✅ §2.3 默认全部历史 → Task 1
- ✅ §2.4 缓存 → Task 2
- ✅ GRANT 约束 → Task 2 Step 1
- ✅ prod 真库验证 → Task 7
- 依赖: 无 (WS1 是地基, 其余 WS 依赖本 WS 的 composable + 端点)
