# 餐厅经营驾驶舱 8 销售问答接 gold 层 — 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让经营驾驶舱 8 个销售类快捷问答经 Java 意图层 → gold-backed Tool 直接答对(复用 GoldFinanceClient + role-forward),不再 fallthrough 到不可靠的单文件聊天。

**Architecture:** 复用已验证 gold 链路 `Java Tool → GoldFinanceClient(转发 X-User-Role)→ Python /api/smartbi/gold/* → gold 表`。Python gold 层大部分端点已存在(finance-summary/top-products/daily-trend/discount-breakdown);补 3 个(order-type-mix / staff-ranking / top-products asc)。Java 侧:GoldFinanceClient 加方法 + 1 个共享抽象基类 `GoldBackedRestaurantTool` + 8 个 Tool + 意图绑定迁移。

**Tech Stack:** Java 21 / Spring Boot / okhttp(GoldFinanceClient);Python FastAPI / asyncpg(gold_reads.py);PostgreSQL gold 表;flyway 迁移(ai_intent_config)。

**Spec:** `docs/superpowers/specs/2026-06-01-restaurant-dashboard-gold-qa-design.md`

**真值基准 (RES_3101_009, deployed 实测对照):** 畅销=抖音松叶蟹368套餐/招牌青花椒味/米饭;门店=大丸百货店¥10.5M>南方百联¥9.9M;外卖≈27%(按营收)堂食¥57.8M/外卖¥20.97M;峰值月=2026-03(¥11.58M);周末日均¥28.4k>周中¥19.0k;员工 top=收银(操作员非服务员);优惠券=[美团套餐券]¥10.6M。

---

## 关键参考 (实现前必读)

- `backend/java/.../client/GoldFinanceClient.java` — 已有 `fetchFinanceSummary`(→top_stores)、`fetchDailyTrend`(→points)、`fetchTopProducts`(→top_products desc);已转发 `X-User-Role`。新方法照抄此 pattern。
- `backend/python/smartbi/api/gold_reads.py` — 已有 `/finance-summary /daily-trend /top-products /channel-breakdown /discount-breakdown /data-range /kpi-summary`;用 `_resolve_tenant` / `_parse_range` / `_apply_rbac_strip` / `_get_role` helper + `get_pg_pool()`。新端点照抄。
- gold service query 层(top_products/channel_breakdown 等函数所在模块,gold_reads import 处)— 新查询函数加此处。
- `backend/java/.../ai/tool/AbstractBusinessTool.java` — Tool 基类(`getToolName`/`doExecute`/`buildSimpleResult`)。
- `backend/java/.../ai/tool/impl/restaurant/RestaurantBestsellerQueryTool.java` — 现查 ERP(要改 gold)。
- 意图迁移样例 `backend/java/.../resources/db/migration/V20260412_04__restaurant_phase2_intents.sql` — `ai_intent_config` INSERT + `tool_name` 绑定 pattern。
- 测试样例 `backend/java/.../test/.../RestaurantEconomicsAnalysisToolTest`(集成测试 mock + 断言)。
- `.claude/rules/python-java-port.md`(Decimal/HALF_UP/None-check)、`.claude/rules/fool-proof-design.md`、`feedback_java_python_rbac_role_forward`、`feedback_intent_gate_must_cover_all_execution_paths`。

**时间窗铁律:** 默认窗口取 `/api/smartbi/gold/data-range`(该工厂 MIN/MAX date),**不用字面"近7天"**(历史上传会空)。Tool 解析 NL 时间(复用 RestaurantEconomicsAnalysisTool.resolveCompositeMonth 思路)覆盖默认。

---

## File Structure

**Python (新建查询 + 端点):**
- Modify: `backend/python/smartbi/api/gold_reads.py` — 加 `/order-type-mix` `/staff-ranking`,`/top-products` 加 `order` 参数
- Modify: gold service query 模块(top_products 所在) — 加 `order_type_mix()` `staff_ranking()`,`top_products()` 加 `order` 参数
- Test: `backend/python/smartbi/services/<gold>/tests/test_gold_reads_restaurant.py`

**Java (client + base + 8 Tools + migration):**
- Modify: `client/GoldFinanceClient.java` — 加 `fetchOrderTypeMix` `fetchStaffRanking` `fetchDiscountBreakdown`,`fetchTopProducts` 加 `order`
- Create: `ai/tool/impl/restaurant/gold/GoldBackedRestaurantTool.java` — 共享抽象基类
- Modify/Create: 8 个 Tool(见 Phase C)
- Create: `resources/db/migration/V20260601_10__restaurant_dashboard_gold_intents.sql`
- Test: `test/.../ai/tool/impl/restaurant/gold/*ToolTest.java`

---

## Phase A — Python gold 端点

### Task A1: order-type-mix 端点 (堂食/外卖, 修 65% 误算)

**Files:**
- Modify: gold service module — add `order_type_mix(pool, factory_id, date_range)`
- Modify: `backend/python/smartbi/api/gold_reads.py` — add `GET /order-type-mix`
- Test: `backend/python/smartbi/services/<gold>/tests/test_gold_reads_restaurant.py`

- [ ] **Step 1: Write the failing test** (堂食/外卖 by order_type from agg_daily_order_type_meal)

```python
import pytest
from decimal import Decimal

@pytest.mark.asyncio
async def test_order_type_mix_dine_in_vs_takeout(monkeypatch):
    from smartbi.services.gold import restaurant_reads as r  # actual module per gold_reads import
    rows = [
        {"order_type": "堂食", "amt": Decimal("57798326"), "bills": 214717},
        {"order_type": "外卖", "amt": Decimal("20974125"), "bills": 230045},
    ]
    class FakePool:
        def acquire(self):
            class C:
                async def __aenter__(s): return s
                async def __aexit__(s, *a): return False
                async def fetch(s, *a, **k): return rows
                async def execute(s, *a, **k): return None
            return C()
    out = await r.order_type_mix(FakePool(), "F1", ("2025-01-01", "2026-04-30"))
    # 外卖占比 = 20974125 / (57798326+20974125) ≈ 26.6% by revenue
    assert out["total_revenue"] == 78772451 or abs(out["total_revenue"] - 78772451) <= 1
    takeout = next(c for c in out["order_types"] if c["order_type"] == "外卖")
    assert 26.0 <= float(takeout["revenue_pct"]) <= 27.5
```

- [ ] **Step 2: Run test, verify FAIL** — `cd backend/python && ./venv*/bin/pytest smartbi/services/<gold>/tests/test_gold_reads_restaurant.py::test_order_type_mix_dine_in_vs_takeout -v` → FAIL (no `order_type_mix`)

- [ ] **Step 3: Implement `order_type_mix`** (mirror existing gold query fns; Decimal→number per python-java-port Rule 4; revenue_pct HALF_UP per Rule 12)

```python
from decimal import Decimal, ROUND_HALF_UP

async def order_type_mix(pool, factory_id, date_range):
    start, end = date_range
    sql = """
        SELECT order_type, COALESCE(SUM(actual_receive),0) AS amt, COALESCE(SUM(bill_count),0) AS bills
        FROM agg_daily_order_type_meal
        WHERE factory_id = $1 AND date BETWEEN $2 AND $3 AND order_type IS NOT NULL
        GROUP BY order_type ORDER BY amt DESC
    """
    async with pool.acquire() as conn:
        await conn.execute("SELECT set_config('app.factory_id', $1, false)", factory_id)
        rows = await conn.fetch(sql, factory_id, start, end)
    total = sum((Decimal(str(r["amt"])) for r in rows), Decimal("0"))
    types = []
    for r in rows:
        amt = Decimal(str(r["amt"]))
        pct = (amt / total * 100).quantize(Decimal("0.1"), rounding=ROUND_HALF_UP) if total > 0 else Decimal("0")
        types.append({"order_type": r["order_type"],
                      "revenue": _num(amt), "bill_count": int(r["bills"]),
                      "revenue_pct": _num(pct)})
    return {"total_revenue": _num(total), "order_types": types}
# _num = existing Decimal→int/float helper in this module (e.g. _decimal_to_number)
```

- [ ] **Step 4: Add endpoint in gold_reads.py** (after /discount-breakdown, line ~225)

```python
@router.get("/order-type-mix")
async def get_order_type_mix(
    request: Request,
    start_date: str = Query(...),
    end_date: str = Query(...),
    factory_id: Optional[str] = Query(None),
):
    """堂食 vs 外卖 营收占比 from agg_daily_order_type_meal.order_type
    (NOT payment channel — channel-breakdown conflates 微信/美团 payment with delivery)."""
    fid = _resolve_tenant(factory_id)
    start, end = _parse_range(start_date, end_date)
    pool = await get_pg_pool()
    try:
        from smartbi.services.gold.restaurant_reads import order_type_mix
        result = await order_type_mix(pool, fid, (start, end))
        return _apply_rbac_strip(result, _get_role(request))
    except Exception as e:
        logger.exception("order-type-mix failed: %s", e)
        raise HTTPException(status_code=500, detail=f"Gold query failed: {e}")
```

- [ ] **Step 5: Run test, verify PASS**; **Step 6: Commit** `feat(gold): order-type-mix endpoint (堂食/外卖, fix payment-vs-delivery conflation)`

### Task A2: staff-ranking 端点 (员工/操作员, 诚实 caveat)

- [ ] **Step 1: failing test** — top staff by net_amount from fact_pos_transaction, expects caveat field

```python
@pytest.mark.asyncio
async def test_staff_ranking_includes_operator_caveat(monkeypatch):
    from smartbi.services.gold import restaurant_reads as r
    rows = [{"name": "收银", "net": Decimal("31538423"), "bills": 181040},
            {"name": "杨生", "net": Decimal("3689233"), "bills": 17298}]
    # FakePool as A1
    out = await r.staff_ranking(_fake_pool(rows), "F1", ("2025-01-01","2026-04-30"), top_n=5)
    assert out["caveat"]  # non-empty: POS 仅记开单操作员, 非服务员归因
    assert out["staff"][0]["name"] == "收银"
    assert out["staff"][0]["net_amount"] == 31538423
```

- [ ] **Step 2: verify FAIL**
- [ ] **Step 3: implement `staff_ranking`** (JOIN dim_staff; caveat constant)

```python
async def staff_ranking(pool, factory_id, date_range, top_n=5):
    start, end = date_range
    sql = """
        SELECT COALESCE(s.name, t.staff_id) AS name,
               COALESCE(SUM(t.net_amount),0) AS net, COUNT(*) AS bills
        FROM fact_pos_transaction t
        LEFT JOIN dim_staff s ON s.staff_id = t.staff_id AND s.factory_id = t.factory_id
        WHERE t.factory_id = $1 AND t.date BETWEEN $2 AND $3 AND t.staff_id IS NOT NULL
        GROUP BY name ORDER BY net DESC LIMIT $4
    """
    async with pool.acquire() as conn:
        await conn.execute("SELECT set_config('app.factory_id', $1, false)", factory_id)
        rows = await conn.fetch(sql, factory_id, start, end, top_n)
    staff = [{"name": r["name"], "net_amount": _num(Decimal(str(r["net"]))),
              "bill_count": int(r["bills"])} for r in rows]
    return {"staff": staff,
            "caveat": "POS 数据按开单操作员(收银/点菜)记账, 非服务员业绩归因; 仅供操作量参考。"}
```

- [ ] **Step 4: endpoint `GET /staff-ranking`** (mirror A1 endpoint, calls `staff_ranking`, top_n Query(5,ge=1,le=50))
- [ ] **Step 5: PASS**; **Step 6: Commit** `feat(gold): staff-ranking endpoint (operator-level, honest caveat)`

### Task A3: top-products 加 order 参数 (慢销 = asc)

- [ ] **Step 1: failing test** — `top_products(..., order="asc")` returns bottom sellers
- [ ] **Step 2: verify FAIL**
- [ ] **Step 3:** in existing `top_products()` add `order: str = "desc"` param; SQL `ORDER BY revenue %s` → use `"ASC" if order=="asc" else "DESC"` (whitelist, not interpolated user input). In `gold_reads.py /top-products` add `order: str = Query("desc")` and pass through.
- [ ] **Step 4: PASS**; **Step 5: Commit** `feat(gold): top-products order=asc for slow-sellers`

---

## Phase B — GoldFinanceClient 方法 (Java)

### Task B1: 加 client 方法 (照抄现有 fetchTopProducts pattern, 含 role-forward)

**Files:** Modify `client/GoldFinanceClient.java`

- [ ] **Step 1:** add `fetchOrderTypeMix(factoryId, start, end)`, `fetchStaffRanking(factoryId, start, end, topN)`, `fetchDiscountBreakdown(factoryId, start, end, topN)`, and overload `fetchTopProducts(factoryId, start, end, topN, order)`. Each: build `HttpUrl.parse(config.getUrl() + "/api/smartbi/gold/<path>")`, add query params, `X-Internal-Secret` + `X-Factory-Id` + `X-User-Role` (via `currentUserRole()`), GET, parse Map. **Copy the exact try/Response/parse block from `fetchTopProducts` (lines 273-312).** Example for order-type-mix:

```java
public Map<String, Object> fetchOrderTypeMix(String factoryId, LocalDate startDate, LocalDate endDate) throws IOException {
    if (factoryId == null || factoryId.isEmpty()) throw new IllegalArgumentException("factoryId required");
    if (startDate == null || endDate == null) throw new IllegalArgumentException("startDate and endDate required");
    if (startDate.isAfter(endDate)) throw new IllegalArgumentException("startDate > endDate");
    HttpUrl url = HttpUrl.parse(config.getUrl() + "/api/smartbi/gold/order-type-mix").newBuilder()
            .addQueryParameter("factory_id", factoryId)
            .addQueryParameter("start_date", startDate.toString())
            .addQueryParameter("end_date", endDate.toString()).build();
    Request.Builder rb = new Request.Builder().url(url).get();
    if (!internalSecret.isEmpty()) {
        rb.addHeader("X-Internal-Secret", internalSecret).addHeader("X-Factory-Id", factoryId);
        String role = currentUserRole();
        if (role != null && !role.isEmpty()) rb.addHeader("X-User-Role", role);
    }
    try (Response resp = http.newCall(rb.build()).execute()) {
        if (!resp.isSuccessful()) {
            String b = resp.body() != null ? resp.body().string() : "";
            throw new IOException("Gold order-type-mix HTTP " + resp.code() + ": " + b);
        }
        String b = resp.body() != null ? resp.body().string() : "{}";
        return objectMapper.readValue(b, Map.class);
    }
}
```
(staff-ranking adds `top_n`; discount-breakdown adds `top_n`; top-products order adds `order` query param.)

- [ ] **Step 2: Commit** `feat(gold-client): order-type-mix / staff-ranking / discount-breakdown / top-products-order methods`

---

## Phase C — Java Tools

### Task C0: 共享抽象基类 `GoldBackedRestaurantTool`

**Files:** Create `ai/tool/impl/restaurant/gold/GoldBackedRestaurantTool.java`

- [ ] **Step 1:** abstract base extends `AbstractBusinessTool`. Provides: inject `GoldFinanceClient` + `GoldDataRangeResolver` (helper that calls `/gold/data-range` for default window); a `resolveWindow(factoryId, params)` returning `[start,end]` (NL month via existing parse, else data-range, else last-12-months fallback); a `degrade(reason, actionHint)` helper returning fool-proof no-data result. Subclass implements `getToolName/getDescription` + `Map<String,Object> queryGold(factoryId, start, end, params)` + `Map<String,Object> format(goldResult)`. `doExecute` orchestrates: resolveWindow → queryGold (catch IOException → degrade "gold 暂不可用") → if empty → degrade(honest reason+actionHint) → format.

```java
@Slf4j
public abstract class GoldBackedRestaurantTool extends AbstractBusinessTool {
    @Autowired protected GoldFinanceClient gold;
    @Autowired protected GoldDataRangeResolver ranges;
    protected abstract Map<String,Object> queryGold(String factoryId, java.time.LocalDate s, java.time.LocalDate e, Map<String,Object> p) throws Exception;
    protected abstract Map<String,Object> format(Map<String,Object> gold);
    protected abstract boolean isEmpty(Map<String,Object> gold);
    protected abstract Map<String,Object> emptyHint(); // {message, actionHint}
    @Override protected Map<String,Object> doExecute(String factoryId, Map<String,Object> params, Map<String,Object> context) {
        var win = ranges.resolve(factoryId, params); // [start,end] LocalDate, never null
        Map<String,Object> g;
        try { g = queryGold(factoryId, win[0], win[1], params); }
        catch (Exception ex) {
            log.warn("gold query failed tool={} factory={}: {}", getToolName(), factoryId, ex.getMessage());
            return buildSimpleResult("数据服务暂时不可用, 请稍后重试。", Map.of("dataAvailable", false));
        }
        if (g == null || isEmpty(g)) return buildSimpleResult((String) emptyHint().get("message"), emptyHint());
        return format(g);
    }
}
```

- [ ] **Step 2:** Create `GoldDataRangeResolver` (calls `gold` data-range or new client method; parses NL month from `params.get("userInput")` reusing the `NL_ABSOLUTE_MONTH` regex from RestaurantEconomicsAnalysisTool). Default = data-range MIN/MAX; if absent, last 12 months from max(data) — NOT today.
- [ ] **Step 3: Commit** `feat(restaurant-gold): GoldBackedRestaurantTool base + data-range resolver`

### Task C1: 畅销品 (re-source RestaurantBestsellerQueryTool to gold)

- [ ] **Step 1: failing test** `RestaurantBestsellerGoldToolTest` — mock `gold.fetchTopProducts(...,"desc")` returns top_products=[{product_name:抖音松叶蟹368套餐,...}], assert result lists it as #1, no ERP repo call.
- [ ] **Step 2: verify FAIL**
- [ ] **Step 3:** rewrite tool to extend `GoldBackedRestaurantTool`; `queryGold` → `gold.fetchTopProducts(factoryId, s, e, 5, "desc")`; `format` → 畅销TOP5 from `top_products`; `emptyHint` → "本期无销售数据, 请在「智能分析」上传含菜品销量的经营报表" + actionHint. **Remove SalesOrderRepository deps.** Keep `getToolName()="restaurant_bestseller_query"`.
- [ ] **Step 4: PASS**; **Step 5: Commit** `fix(restaurant): bestseller query sources gold top-products (was empty ERP)`

### Task C2: 慢销菜品 (RestaurantSlowSellerQueryTool → gold asc)
- [ ] test → `fetchTopProducts(...,"asc")`; format 滞销TOP5; emptyHint. Same pattern as C1. Commit.

### Task C3: 哪家店业绩最好 (store ranking → finance-summary.top_stores)
- [ ] test → `gold.fetchFinanceSummary(factoryId,s,e,5)`, assert top_stores[0].store_name=大丸百货店; format 门店营收排行; bind tool. Commit. (re-source existing store/margin tool OR new `RestaurantStoreRevenueRankTool`; getToolName `restaurant_store_revenue_rank`.)

### Task C4: 外卖占比 (RestaurantOrderStatisticsTool → order-type-mix)
- [ ] test → `gold.fetchOrderTypeMix`, assert 外卖 revenue_pct ≈ 27 (NOT 65); format 堂食/外卖占比. Commit.

### Task C5: 峰值月份 (→ daily-trend, aggregate by month in Tool)
- [ ] test → `gold.fetchDailyTrend` points → Tool groups by `YYYY-MM` sum revenue → peak month; assert 2026-03. Commit. (new `RestaurantPeakMonthTool`, getToolName `restaurant_peak_month`.)

### Task C6: 周末周中 (→ daily-trend, aggregate by dow in Tool)
- [ ] test → daily-trend points → Tool groups weekday(Mon-Fri) vs weekend(Sat-Sun) avg daily revenue; assert weekend avg > weekday avg. Commit. (new `RestaurantWeekdayWeekendTool`.)

### Task C7: 优惠券 (→ discount-breakdown)
- [ ] test → `gold.fetchDiscountBreakdown(factoryId,s,e,10)`, assert [美团套餐券] top; format 优惠使用排行 + 总优惠占比. Commit. (new `RestaurantDiscountUsageTool`.)

### Task C8: 员工 (→ staff-ranking, 诚实 caveat)
- [ ] test → `gold.fetchStaffRanking`, assert result message includes caveat (操作员非服务员) + lists 收银; format 开单操作员排行 + caveat. Commit. (new `RestaurantStaffRankingTool`.)

---

## Phase D — 意图绑定 + 短语映射

### Task D1: migration V20260601_10__restaurant_dashboard_gold_intents.sql

**Files:** Create `resources/db/migration/V20260601_10__restaurant_dashboard_gold_intents.sql`

- [ ] **Step 1:** version > prod 已应用 max(查 flyway_schema_history 确认)。For each of the 8: ensure intent row exists with `intent_category` restaurant-ish, `business_type='RESTAURANT'`, strong `keywords`, and `tool_name` bound. Pattern (mirror V20260412_04):

```sql
-- (A) 意图已存在、只缺执行器 → 绑 tool_name
UPDATE ai_intent_config SET tool_name='restaurant_bestseller_query' WHERE intent_code='RESTAURANT_BESTSELLER_QUERY';
UPDATE ai_intent_config SET tool_name='restaurant_store_revenue_rank' WHERE intent_code='RESTAURANT_OPS_STORE_MARGIN';
UPDATE ai_intent_config SET tool_name='restaurant_order_type_mix' WHERE intent_code='RESTAURANT_ORDER_STATISTICS';
UPDATE ai_intent_config SET tool_name='restaurant_slow_seller_query' WHERE intent_code='RESTAURANT_DISH_LIST'; -- or new DISH_SLOW

-- (B) 误判的 → 新建强关键词餐厅意图 (出 manufacturing 意图分)
INSERT INTO ai_intent_config (id, intent_code, intent_name, intent_category, tool_name, keywords, is_active, sensitivity_level, business_type)
VALUES
 (gen_random_uuid(),'RESTAURANT_PEAK_MONTH','峰值月份','RESTAURANT_ANALYSIS','restaurant_peak_month','["峰值月份","营收最高的月份","哪个月营业额最高","月度峰值"]',true,'LOW','RESTAURANT'),
 (gen_random_uuid(),'RESTAURANT_WEEKDAY_WEEKEND','周末周中对比','RESTAURANT_ANALYSIS','restaurant_weekday_weekend','["周末周中","周末周中对比","周末生意好还是平日","礼拜几卖得最好"]',true,'LOW','RESTAURANT'),
 (gen_random_uuid(),'RESTAURANT_DISCOUNT_USAGE','优惠券使用','RESTAURANT_ANALYSIS','restaurant_discount_usage','["优惠券使用情况","优惠券","折扣率","代金券","满减"]',true,'LOW','RESTAURANT'),
 (gen_random_uuid(),'RESTAURANT_STAFF_RANKING','员工开单排行','RESTAURANT_ANALYSIS','restaurant_staff_ranking','["员工里谁最厉害","员工绩效","开单排行","谁开单最多"]',true,'LOW','RESTAURANT')
ON CONFLICT (intent_code) DO UPDATE SET tool_name=EXCLUDED.tool_name, keywords=EXCLUDED.keywords, business_type='RESTAURANT';
```

- [ ] **Step 2:** business_type gate — confirm shared `BusinessTypeGate` (per feedback_intent_gate_must_cover_all_execution_paths) lets these RESTAURANT intents pass for restaurant factories and the mis-routed manufacturing intents (HR_LEAVE/SHIPMENT/PROCESS_TASK/CUSTOMER_ACTIVE) lose on a restaurant tenant. If keyword scoring alone insufficient, add the 8 exact dashboard phrases to the phrase-shortcut/exact-intent map (find existing mechanism via grep `phrase_shortcut` / `PHRASE_MATCH`).
- [ ] **Step 3: Commit** `feat(intent): bind 8 dashboard restaurant intents to gold tools + fix mis-routes`

---

## Phase E — 部署 + 验收

### Task E1: deploy + headed UI verify
- [ ] **Step 1:** merge to main (PR; `git diff origin/main...HEAD --stat` scope clean).
- [ ] **Step 2:** `git checkout main && git pull`; `./scripts/deploy/deploy-smartbi-python.sh --env prod` (gold 端点 + runner).
- [ ] **Step 3:** `./scripts/deploy/deploy-backend.sh --env prod`; **systemctl restart cretas-backend** (flyway 迁移靠 restart 触发, per memory); javap 核对活跃 jar 含新 Tool; flyway_schema_history 含 V20260601_10。
- [ ] **Step 4:** headed Playwright (node script, per playwright-headed-mode rule, chromium.launch headless:false zh-CN 1920x1080) — login qhj_prod, 跑驾驶舱 8 问, 截图, 对照 §真值基准。
- [ ] **Step 5:** grep prod log 确认走 gold Tool(`restaurant_bestseller_query` 等 SUCCESS),**非** fallthrough 到 `general-analysis-stream`。
- [ ] **Step 6: Commit** verify doc `docs/audits/2026-06-01-restaurant-dashboard-gold-qa-verify.md` (8 问 before/after + 截图 + 真值对照)。

---

## 并行建议
- Phase A (3 Python 端点) 互相独立 → 可并行 subagent。
- Phase C (C1-C8 Tools) 在 C0 基类 + B1 client 完成后互相独立 → 并行 subagent(各自 Tool+test)。
- A / B / C0 是 C1-C8 的前置;D 依赖 C(tool 名);E 依赖全部。
- 顺序:A‖(B→C0)→ C1..C8‖ → D → E。
