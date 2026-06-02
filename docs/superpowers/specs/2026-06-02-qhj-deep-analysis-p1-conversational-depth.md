# P1 对话深度层 — qhj 评价深度分析 实现 Spec

**日期**: 2026-06-02
**客户**: qhj (青花椒连锁, `factory_id = RES_3101_009`, 28 门店 / 2 城市)
**作者**: 资深架构师 (workflow 产出)
**风格**: superpowers writing-plans (bite-sized task + 完整代码 + TDD + 部署/验证)
**前置**: PR #387/#391 (评价 8 问答接 gold 层) 已 LIVE prod; review_queries.py / gold_reads.py / GoldBackedRestaurantTool / AbstractReviewGoldTool / GoldFinanceClient 已就绪 (`origin/main`)。

---

## 0. 背景与目标

经营驾驶舱 AIQuery 现已能回答销售 8 问 + 评价 8 问 (gold 工具 + 物化模板)，但答案是「一次性终态」：用户看到一张图 + 一段文字后**无路可走**——没有引导下钻的追问、没有字段/图表的通俗解释、跨维度的洞察缺失。P1「对话深度层」把单轮问答升级为**可持续下钻的对话**，全部复用现有 gold-tool 模板，全部接 `smart_bi_dynamic_data` 评价数据 (不新建物化表)。

### P1 四个子能力

| # | 能力 | 落点 |
|---|------|------|
| (1) | **追问 chip** — 每个 gold 工具返回 `suggestedFollowups` (3-4 个上下文相关下钻问题)，AIQuery.vue 渲染可点 chip → 复用 `triggerRelatedFollowup` | Java 所有 gold 工具 + AIQuery.vue |
| (2) | **字段/图表解释** — 每个工具返回 `glossary` + `chartGuide`，AIQuery.vue 加可展开「字段说明 / 怎么看这张图」块 (零额外调用)；自由问「这个字段什么意思」时前端识别 meta 问题 → 用上一条答案 `glossary` 本地精准回答 (0 LLM)，兜底才发 LLM 带 glossary 上下文 | Java 所有 gold 工具 + AIQuery.vue |
| (3) | **4 个 within-review 跨维工具** — VIP×菜品/口味、城市×评价、时段×评价、服务/环境标签×评分 | Python review_queries.py + gold_reads.py + Java 4 tool + GoldFinanceClient + 迁移 |
| (4) | **4 个更多评价问题工具** — 好评高频词、各平台对比、评价趋势(月)、回复率 | 同 (3) |

### 关键约束 (诚实标注铁律)

- **菜品标签 = 口味/品质标签** (味道好/鲜嫩/太软了)，**不是菜名** — 所有 message / glossary 必须如实标注。
- **投诉类型 = 商家申诉小样本** — 标注样本量。
- **time_period ~73% 有值** (5420 空) — 时段类工具必须在 message 注明覆盖率。
- **不编造数字** — 空数据走 fool-proof Rule 5 (next-action，不 dead-end)。
- 字节序列/Decimal: 本模板输出全部用 `float()` (per python-java-port.md 简化路径，评价无货币字段无 RBAC strip)。

### GROUNDTRUTH (已查证 prod RES_3101_009，单元测试与验收必须对齐)

| 维度 | 真值 |
|------|------|
| 去重 | 评价ID DISTINCT: 72438 raw → 19845 unique |
| 平均分 | 星级 4.79 / 服务 4.80 / 环境 4.79 / 口味 4.79 |
| 好评/差评 | 好评(≥4.5星) 18139 / 差评(≤3星) 396 |
| VIP | VIP 2485 (avg 4.50) / 非VIP 17360 (avg 4.83) |
| 平台 | 点评 19189 (4.80) / 美团 656 (4.57) |
| 回复 | 已回复 19452 / 未回复 393 (回复率 98%) |
| 时段 | 午11-14:5989(4.85) / 晚17-21:6810(4.82) / 下午15-16:625(4.58) / 早5-10:522(4.44) / 夜22-4:479(4.31) [~73% 有值, 5420 空] |
| 好评高频菜品标签 | 味道好 5998 / 实惠 1791 / 鲜嫩 1394 / 新鲜 1295 / 香辣 1046 |
| 差评高频菜品标签 | 味道差 79 / 份量太小 54 / 不实惠 39 / 不新鲜 24 |
| 差评最多门店 | 鲜行者X顺德小馆(虹口龙之梦店) 64 条 |
| 城市 | 上海 4.790 / 杭州 4.776 |

---

## 1. 现状与 Gap (必读，避免重做)

### 已存在 (origin/main，**不要**重写)

- `backend/python/smartbi/gold/review_queries.py` — 6 聚合: `review_summary` / `review_store_ranking` (dim=star|service|env|low_star) / `review_city_ranking` / `review_vip` / `review_complaints` / `review_dish_issues`。含 `_DEDUP_CTE` (按 `row_data->>'评价ID'` DISTINCT ON) + `_f()` (Decimal→float) + `_STORE_DIM_EXPR` 白名单。
- `backend/python/smartbi/api/gold_reads.py` — `/review-*` 端点 (无日期、无 RBAC strip、`_resolve_tenant`)。
- `backend/python/smartbi/gold/__init__.py` — re-export 6 review 函数 + `__all__`。
- Java `restaurant/gold/GoldBackedRestaurantTool.java` (基类: `final doExecute` 模板, `barChartConfig`/`pieChartConfig`/`toWan`)。
- Java `restaurant/gold/review/AbstractReviewGoldTool.java` (评价基类: `intOf`/`dbl`/`fmt2`/`listOfMaps`, 无参 schema)。
- Java `GoldFinanceClient.java` — `getReviewJson` helper + `fetchReviewSummary/StoreRanking/CityRanking/Vip/Complaints/DishIssues`。
- Java 8 个评价 tool (`RestaurantReviewSummaryTool` 等)。
- 迁移 `V20260903_01__restaurant_review_intents.sql` (意图 INSERT 形状参考)。
- AIQuery.vue — `tryJavaIntentChat` (gold 工具 chartConfig 在 `res.resultData.chartConfig` 直挂)、`renderChartFromConfig`、`RELATED_FOLLOWUPS` + `relatedFollowups()` + `triggerRelatedFollowup()`。

### Gap (P1 要补)

1. **gold 工具不返回 `suggestedFollowups`** → 现有 `RELATED_FOLLOWUPS` 是前端静态 map **仅认 `templateCode`** (物化模板路径)，gold 答案 (`source` 不是 `materialized_cache`) 完全拿不到追问 chip。
2. **gold 工具不返回 `glossary` / `chartGuide`** → 用户不懂「服务分」「差评率」「为什么这张图这么画」。
3. **缺 4 个 within-review 跨维工具** (VIP×口味、城市×评价、时段×评价、服务/环境标签×评分)。
4. **缺 4 个评价问题工具** (好评高频词、平台对比、评价月趋势、回复率)。
5. **AIQuery.vue 无 meta 问题本地解释** — 「这个字段什么意思」必发 LLM (浪费 + 可能编造)。

---

## 2. 架构决策

### 2.1 `suggestedFollowups` / `glossary` / `chartGuide` 放在 gold 工具的 `format()` 返回 map 顶层

gold 工具的返回 map **直接**就是 `res.resultData` (无 `.data` 包裹，见 `tryJavaIntentChat` 注释 line 695-703)。因此：

```
res.resultData = {
  "评价总数": 19845, ..., "dataAvailable": true,
  "message": "...",
  "chartConfig": {type,title,option},
  "suggestedFollowups": [{label, question}, ...],   // ← P1 新增
  "glossary": {"服务分": "...", "差评率": "..."},     // ← P1 新增
  "chartGuide": "横轴是平均分(5分制)，越靠右口碑越好。"  // ← P1 新增
}
```

字段形如 `suggestedFollowups: [{label, question}]`：`label` 显示在 chip 上 (短)，`question` 是点击后真正发送的查询 (可更完整，命中目标意图关键词)。前端点击 → `triggerRelatedFollowup(question)`。

### 2.2 公共 helper 提到 `AbstractReviewGoldTool`

为避免 12 个工具 (8 现存 + 4 新) 各写一遍 followups/glossary 拼装，在 `AbstractReviewGoldTool` 加：

- `protected static Map<String,Object> followup(String label, String question)` — 构造单个 chip。
- `protected static List<Map<String,Object>> followups(Map<String,Object>... entries)` — 收集 list。
- `protected void attachDepth(Map<String,Object> result, List<Map<String,Object>> followups, Map<String,String> glossary, String chartGuide)` — 统一把三字段挂上 result (null/empty 跳过)。

每个工具的 `format()` 末尾调用 `attachDepth(...)`。**8 个现存工具也要回填** (Task 9)。

### 2.3 时段分桶 (time_period)

`time_period` 是评价 datetime 字符串 (~73% 有值)。Python 端用 `EXTRACT(HOUR FROM (row_data->>'time_period')::timestamp)` 分 5 桶：

| 桶 | 小时区间 | label |
|----|---------|-------|
| 早 | 5-10 | 早(5-10点) |
| 午 | 11-14 | 午(11-14点) |
| 下午 | 15-16 | 下午(15-16点) |
| 晚 | 17-21 | 晚(17-21点) |
| 夜 | 22-4 | 夜(22-4点) |

空 time_period 行进入 `null_period_count`，message 注明覆盖率。解析失败用 `NULLIF(...,'')::timestamp` + 外层 `CASE WHEN ... ~ '^\d{4}-'` 守卫 (脏值不炸)。

### 2.4 迁移版本号

`V20260904_01` — 必须 > prod 已应用 max `20260903.03` (out-of-order=false)。已应用列表查 `flyway_schema_history`；brief 指明 prod 已到 `20260903.03` (本地 main 仅见 `_01`，02/03 可能在并发 session/prod hotfix，保守取 `20260904_01`)。

### 2.5 强关键词 + priority 115 + business_type RESTAURANT

新 4+4 意图 priority **115** (> DISH_SLOW 110 > 默认 85/90)，`business_type='RESTAURANT'` 让业态门控放行，强关键词命中 KEYWORD 层、避开被销售意图抢。

### 2.6 章节导航

§3 Python 层 (review_queries 7 新函数 + gold_reads 7 端点) · §4 Java 层 (GoldFinanceClient 7 fetch + AbstractReviewGoldTool depth helper + 7 新工具) · §5 AIQuery.vue (followup chip + glossary 块 + meta 本地解释) · §6 迁移 V20260904_01 · §7 TDD 任务清单 · §8 部署/验证 · §9 任务汇总+并行 · §10 风险 · §11 验收。

---

## 3. Python 层

### 3.1 `backend/python/smartbi/gold/review_queries.py` — 新增 7 个查询函数

追加到现有文件末尾 (现有 6 函数 + helper 不动)。所有函数复用模块级 `_DEDUP_CTE` 与 `_f()`。

#### 3.1.1 时段分桶 helper (模块级，放在 `_STORE_DIM_EXPR` 之后)

```python
# Time-of-day bucketing for time_period (评价 datetime, ~73% populated).
# A guarded cast — only rows whose text looks like an ISO timestamp are
# parsed; everything else (blank / malformed) falls into the NULL bucket.
# The 5 buckets mirror the GROUNDTRUTH cohorts:
#   早 5-10 / 午 11-14 / 下午 15-16 / 晚 17-21 / 夜 22-4
_TIME_PERIOD_EXPR = """
    CASE
        WHEN NULLIF(row_data->>'time_period', '') !~ '^\\d{4}-\\d{2}-\\d{2}' THEN NULL
        ELSE (
            CASE
                WHEN EXTRACT(HOUR FROM (row_data->>'time_period')::timestamp) BETWEEN 5  AND 10 THEN '早(5-10点)'
                WHEN EXTRACT(HOUR FROM (row_data->>'time_period')::timestamp) BETWEEN 11 AND 14 THEN '午(11-14点)'
                WHEN EXTRACT(HOUR FROM (row_data->>'time_period')::timestamp) BETWEEN 15 AND 16 THEN '下午(15-16点)'
                WHEN EXTRACT(HOUR FROM (row_data->>'time_period')::timestamp) BETWEEN 17 AND 21 THEN '晚(17-21点)'
                ELSE '夜(22-4点)'
            END
        )
    END
"""

# Stable presentation order for the 5 time buckets.
_TIME_PERIOD_ORDER = ['早(5-10点)', '午(11-14点)', '下午(15-16点)', '晚(17-21点)', '夜(22-4点)']
```

#### 3.1.2 `review_vip_tags` — VIP×菜品/口味 (within-review 跨维)

```python
async def review_vip_tags(
    pool: asyncpg.Pool, factory_id: str, *, top_n: int = 6
) -> Dict[str, Any]:
    """VIP vs 非VIP 各自的高频好评(>=4.5星)与差评(<=3星)口味/品质标签。

    NOTE: 菜品标签 是口味/品质标签 (味道好/鲜嫩/太软了)，非菜名。
    标签按 [,，、/] 切分单独计数。返回四组列表供前端做双向对比。"""
    sql = _DEDUP_CTE + """
        , tagged AS (
            SELECT
                CASE WHEN row_data->>'是否vip' = '是' THEN 'VIP' ELSE '非VIP' END AS grp,
                CASE WHEN (row_data->>'星级分')::numeric >= 4.5 THEN 'good'
                     WHEN (row_data->>'星级分')::numeric <= 3   THEN 'bad'
                     ELSE 'mid' END                                              AS sentiment,
                trim(t)                                                          AS tag
              FROM r,
                   LATERAL regexp_split_to_table(
                       COALESCE(row_data->>'菜品标签', ''), '[,，、/]') AS t
             WHERE NULLIF(row_data->>'菜品标签', '') IS NOT NULL
        )
        SELECT grp, sentiment, tag, count(*) AS n
          FROM tagged
         WHERE trim(tag) <> '' AND sentiment IN ('good', 'bad')
         GROUP BY grp, sentiment, tag
    """
    async with pool.acquire() as conn:
        rows = await conn.fetch(sql, factory_id)

    buckets: Dict[str, list] = {
        "VIP_good": [], "VIP_bad": [], "非VIP_good": [], "非VIP_bad": [],
    }
    for row in rows:
        key = f"{row['grp']}_{row['sentiment']}"
        if key in buckets:
            buckets[key].append({"tag": row["tag"], "count": int(row["n"])})
    for key in buckets:
        buckets[key].sort(key=lambda x: x["count"], reverse=True)
        buckets[key] = buckets[key][:top_n]

    return {
        "factory_id": factory_id,
        "vip_good_tags": buckets["VIP_good"],
        "vip_bad_tags": buckets["VIP_bad"],
        "normal_good_tags": buckets["非VIP_good"],
        "normal_bad_tags": buckets["非VIP_bad"],
    }
```

#### 3.1.3 `review_time_period` — 时段×评价 (within-review 跨维)

```python
async def review_time_period(pool: asyncpg.Pool, factory_id: str) -> Dict[str, Any]:
    """各时段 (早/午/下午/晚/夜) 评价量与平均星级。time_period ~73% 有值，
    返回 null_period_count 供 message 注明覆盖率。"""
    sql = _DEDUP_CTE + f"""
        , p AS (
            SELECT {_TIME_PERIOD_EXPR} AS period,
                   (row_data->>'星级分')::numeric AS star
              FROM r
        )
        SELECT period,
               count(*)                       AS n,
               round(avg(star), 3)            AS avg_star
          FROM p
         WHERE period IS NOT NULL
         GROUP BY period
    """
    null_sql = _DEDUP_CTE + f"""
        , p AS (SELECT {_TIME_PERIOD_EXPR} AS period FROM r)
        SELECT count(*) FILTER (WHERE period IS NULL)     AS null_count,
               count(*)                                    AS total
          FROM p
    """
    async with pool.acquire() as conn:
        rows = await conn.fetch(sql, factory_id)
        cnt = await conn.fetchrow(null_sql, factory_id)

    by_period = {row["period"]: row for row in rows}
    periods = []
    for label in _TIME_PERIOD_ORDER:
        row = by_period.get(label)
        if row is None:
            continue
        periods.append({
            "period": label,
            "review_count": int(row["n"]),
            "avg_star": _f(row["avg_star"]),
        })
    return {
        "factory_id": factory_id,
        "periods": periods,
        "null_period_count": int(cnt["null_count"]) if cnt else 0,
        "total_reviews": int(cnt["total"]) if cnt else 0,
    }
```

#### 3.1.4 `review_score_tags` — 服务/环境标签×评分 (within-review 跨维)

```python
async def review_score_tags(
    pool: asyncpg.Pool, factory_id: str, *, dim: str = "service", top_n: int = 10
) -> Dict[str, Any]:
    """服务标签 / 环境标签 高频词 + 该维度平均分。

    dim=service → 服务标签 + avg_service；dim=env → 环境标签 + avg_env。
    标签是大众点评预设的评价标签 (如 服务热情/环境优雅)，按 [,，、/] 切分。"""
    tag_col = "服务标签" if dim == "service" else "环境标签"
    score_col = "服务分" if dim == "service" else "环境分"
    sql = _DEDUP_CTE + f"""
        , tags AS (
            SELECT trim(t)                                       AS tag,
                   NULLIF(row_data->>'{score_col}', '')::numeric AS score
              FROM r,
                   LATERAL regexp_split_to_table(
                       COALESCE(row_data->>'{tag_col}', ''), '[,，、/]') AS t
             WHERE NULLIF(row_data->>'{tag_col}', '') IS NOT NULL
        )
        SELECT tag,
               count(*)                AS n,
               round(avg(score), 3)    AS avg_score
          FROM tags
         WHERE trim(tag) <> ''
         GROUP BY tag
         ORDER BY n DESC
         LIMIT $2
    """
    async with pool.acquire() as conn:
        rows = await conn.fetch(sql, factory_id, int(top_n))
    tags = [
        {"tag": row["tag"], "count": int(row["n"]), "avg_score": _f(row["avg_score"])}
        for row in rows
    ]
    return {"factory_id": factory_id, "dim": dim, "score_col": score_col, "tags": tags}
```

#### 3.1.5 `review_good_tags` — 好评高频词 (评价问题)

```python
async def review_good_tags(
    pool: asyncpg.Pool, factory_id: str, *, top_n: int = 10
) -> Dict[str, Any]:
    """好评(>=4.5星)中高频的口味/品质标签。

    NOTE: 菜品标签是口味/品质标签 (味道好/鲜嫩)，非菜名。GROUNDTRUTH:
    味道好 5998 / 实惠 1791 / 鲜嫩 1394 / 新鲜 1295 / 香辣 1046。"""
    tag_sql = _DEDUP_CTE + """
        , high AS (
            SELECT row_data
              FROM r
             WHERE (row_data->>'星级分')::numeric >= 4.5
               AND NULLIF(row_data->>'菜品标签', '') IS NOT NULL
        ),
        tags AS (
            SELECT trim(t) AS tag
              FROM high,
                   LATERAL regexp_split_to_table(high.row_data->>'菜品标签', '[,，、/]') AS t
        )
        SELECT tag, count(*) AS n
          FROM tags
         WHERE trim(tag) <> ''
         GROUP BY tag
         ORDER BY n DESC
         LIMIT $2
    """
    cnt_sql = _DEDUP_CTE + """
        SELECT count(*) FILTER (WHERE (row_data->>'星级分')::numeric >= 4.5) AS high_star_count
          FROM r
    """
    async with pool.acquire() as conn:
        rows = await conn.fetch(tag_sql, factory_id, int(top_n))
        cnt = await conn.fetchrow(cnt_sql, factory_id)
    tags = [{"tag": row["tag"], "count": int(row["n"])} for row in rows]
    return {
        "factory_id": factory_id,
        "high_star_count": int(cnt["high_star_count"]) if cnt else 0,
        "tags": tags,
    }
```

#### 3.1.6 `review_platform` — 各平台对比 (评价问题)

```python
async def review_platform(pool: asyncpg.Pool, factory_id: str) -> Dict[str, Any]:
    """各平台 (点评/美团/...) 评价量与平均星级。GROUNDTRUTH:
    点评 19189 (4.80) / 美团 656 (4.57)。按评价量降序。"""
    sql = _DEDUP_CTE + """
        SELECT COALESCE(NULLIF(row_data->>'平台', ''), '未标注')        AS platform,
               count(*)                                                 AS n,
               round(avg((row_data->>'星级分')::numeric), 3)            AS avg_star,
               round(avg(NULLIF(row_data->>'服务分', '')::numeric), 3)  AS avg_service,
               round(avg(NULLIF(row_data->>'环境分', '')::numeric), 3)  AS avg_env
          FROM r
         GROUP BY platform
         ORDER BY n DESC
    """
    async with pool.acquire() as conn:
        rows = await conn.fetch(sql, factory_id)
    platforms = [
        {
            "platform": row["platform"],
            "review_count": int(row["n"]),
            "avg_star": _f(row["avg_star"]),
            "avg_service": _f(row["avg_service"]),
            "avg_env": _f(row["avg_env"]),
        }
        for row in rows
    ]
    return {"factory_id": factory_id, "platforms": platforms}
```

#### 3.1.7 `review_trend` — 评价趋势 (按 time_period 月聚合)

```python
async def review_trend(pool: asyncpg.Pool, factory_id: str) -> Dict[str, Any]:
    """按 time_period 月份聚合评价量与平均星级 (时间序列)。

    time_period ~73% 有值；无月份的行不计入趋势 (返回 null_period_count)。
    月份键 = calendar year-month (to_char 直接出 YYYY-MM，无 ISO-year 跨年坑)。"""
    sql = _DEDUP_CTE + """
        , m AS (
            SELECT
                CASE
                    WHEN NULLIF(row_data->>'time_period', '') !~ '^\\d{4}-\\d{2}-\\d{2}' THEN NULL
                    ELSE to_char((row_data->>'time_period')::timestamp, 'YYYY-MM')
                END AS month,
                (row_data->>'星级分')::numeric AS star
              FROM r
        )
        SELECT month,
               count(*)            AS n,
               round(avg(star), 3) AS avg_star
          FROM m
         WHERE month IS NOT NULL
         GROUP BY month
         ORDER BY month ASC
    """
    null_sql = _DEDUP_CTE + """
        SELECT count(*) FILTER (
                 WHERE NULLIF(row_data->>'time_period', '') !~ '^\\d{4}-\\d{2}-\\d{2}'
               ) AS null_count
          FROM r
    """
    async with pool.acquire() as conn:
        rows = await conn.fetch(sql, factory_id)
        cnt = await conn.fetchrow(null_sql, factory_id)
    months = [
        {"month": row["month"], "review_count": int(row["n"]), "avg_star": _f(row["avg_star"])}
        for row in rows
    ]
    return {
        "factory_id": factory_id,
        "months": months,
        "null_period_count": int(cnt["null_count"]) if cnt else 0,
    }
```

#### 3.1.8 `review_reply_rate` — 回复率 (评价问题)

```python
async def review_reply_rate(pool: asyncpg.Pool, factory_id: str) -> Dict[str, Any]:
    """商家回复率：已回复/未回复评价数 + 未回复差评数 (优先级处理对象)。

    GROUNDTRUTH: 已回复 19452 / 未回复 393 (回复率 98%)。
    回复状态字段值为 已回复 / 未回复。"""
    sql = _DEDUP_CTE + """
        SELECT
            count(*) FILTER (WHERE row_data->>'回复状态' = '已回复')                       AS replied,
            count(*) FILTER (WHERE row_data->>'回复状态' = '未回复')                       AS not_replied,
            count(*) FILTER (WHERE row_data->>'回复状态' = '未回复'
                             AND (row_data->>'星级分')::numeric <= 3)                      AS not_replied_low_star,
            count(*) FILTER (WHERE NULLIF(row_data->>'回复状态', '') IS NOT NULL)          AS total_with_status
          FROM r
    """
    async with pool.acquire() as conn:
        row = await conn.fetchrow(sql, factory_id)
    if row is None or int(row["total_with_status"]) == 0:
        return {"factory_id": factory_id, "total_with_status": 0,
                "replied": 0, "not_replied": 0, "not_replied_low_star": 0, "reply_rate": None}
    replied = int(row["replied"])
    total = int(row["total_with_status"])
    reply_rate = round(replied / total * 100, 1) if total > 0 else None
    return {
        "factory_id": factory_id,
        "replied": replied,
        "not_replied": int(row["not_replied"]),
        "not_replied_low_star": int(row["not_replied_low_star"]),
        "total_with_status": total,
        "reply_rate": reply_rate,
    }
```

### 3.2 `backend/python/smartbi/gold/__init__.py` — 追加 re-export

review import 块加 7 个新函数并同步 `__all__`:

```python
from smartbi.gold.review_queries import (
    review_city_ranking,
    review_complaints,
    review_dish_issues,
    review_good_tags,        # P1
    review_platform,         # P1
    review_reply_rate,       # P1
    review_score_tags,       # P1
    review_store_ranking,
    review_summary,
    review_time_period,      # P1
    review_trend,            # P1
    review_vip,
    review_vip_tags,         # P1
)
```

`__all__` 追加: `"review_good_tags"`, `"review_platform"`, `"review_reply_rate"`, `"review_score_tags"`, `"review_time_period"`, `"review_trend"`, `"review_vip_tags"`。

### 3.3 `backend/python/smartbi/api/gold_reads.py` — 新增 7 个端点

`from smartbi.gold import (...)` 追加 7 个函数名。在 `/review-dish-issues` 之后、`_query_analysis_results_batch` 之前插入：

```python
@router.get("/review-vip-tags")
async def get_review_vip_tags(
    request: Request,
    factory_id: Optional[str] = Query(None),
    top_n: int = Query(6, ge=1, le=20),
):
    """VIP vs 非VIP 各自的高频好评/差评口味/品质标签 (非菜名)。"""
    fid = _resolve_tenant(factory_id)
    pool = await get_pg_pool()
    try:
        return await review_vip_tags(pool, fid, top_n=top_n)
    except Exception as e:
        logger.exception("review-vip-tags failed: %s", e)
        raise HTTPException(status_code=500, detail=f"Review query failed: {e}")


@router.get("/review-time-period")
async def get_review_time_period(
    request: Request,
    factory_id: Optional[str] = Query(None),
):
    """各时段 (早/午/下午/晚/夜) 评价量与平均星级 (time_period ~73% 有值)。"""
    fid = _resolve_tenant(factory_id)
    pool = await get_pg_pool()
    try:
        return await review_time_period(pool, fid)
    except Exception as e:
        logger.exception("review-time-period failed: %s", e)
        raise HTTPException(status_code=500, detail=f"Review query failed: {e}")


@router.get("/review-score-tags")
async def get_review_score_tags(
    request: Request,
    factory_id: Optional[str] = Query(None),
    dim: str = Query("service", description="service|env"),
    top_n: int = Query(10, ge=1, le=30),
):
    """服务标签 / 环境标签 高频词 + 该维度平均分。"""
    fid = _resolve_tenant(factory_id)
    if dim not in ("service", "env"):
        raise HTTPException(status_code=400, detail="dim must be service|env")
    pool = await get_pg_pool()
    try:
        return await review_score_tags(pool, fid, dim=dim, top_n=top_n)
    except Exception as e:
        logger.exception("review-score-tags failed: %s", e)
        raise HTTPException(status_code=500, detail=f"Review query failed: {e}")


@router.get("/review-good-tags")
async def get_review_good_tags(
    request: Request,
    factory_id: Optional[str] = Query(None),
    top_n: int = Query(10, ge=1, le=30),
):
    """好评(>=4.5星)高频口味/品质标签 (非菜名)。"""
    fid = _resolve_tenant(factory_id)
    pool = await get_pg_pool()
    try:
        return await review_good_tags(pool, fid, top_n=top_n)
    except Exception as e:
        logger.exception("review-good-tags failed: %s", e)
        raise HTTPException(status_code=500, detail=f"Review query failed: {e}")


@router.get("/review-platform")
async def get_review_platform(
    request: Request,
    factory_id: Optional[str] = Query(None),
):
    """各平台 (点评/美团) 评价量与平均星级对比。"""
    fid = _resolve_tenant(factory_id)
    pool = await get_pg_pool()
    try:
        return await review_platform(pool, fid)
    except Exception as e:
        logger.exception("review-platform failed: %s", e)
        raise HTTPException(status_code=500, detail=f"Review query failed: {e}")


@router.get("/review-trend")
async def get_review_trend(
    request: Request,
    factory_id: Optional[str] = Query(None),
):
    """按 time_period 月份聚合评价量与平均星级 (时间序列)。"""
    fid = _resolve_tenant(factory_id)
    pool = await get_pg_pool()
    try:
        return await review_trend(pool, fid)
    except Exception as e:
        logger.exception("review-trend failed: %s", e)
        raise HTTPException(status_code=500, detail=f"Review query failed: {e}")


@router.get("/review-reply-rate")
async def get_review_reply_rate(
    request: Request,
    factory_id: Optional[str] = Query(None),
):
    """商家回复率 (已/未回复 + 未回复差评数)。"""
    fid = _resolve_tenant(factory_id)
    pool = await get_pg_pool()
    try:
        return await review_reply_rate(pool, fid)
    except Exception as e:
        logger.exception("review-reply-rate failed: %s", e)
        raise HTTPException(status_code=500, detail=f"Review query failed: {e}")
```

---

## 4. Java 层

### 4.1 `GoldFinanceClient.java` — 新增 7 个 fetch 方法

在 review-fetch 区 (`fetchReviewDishIssues` 之后、类闭合 `}` 之前) 追加。全部复用 `getReviewJson` + `requireFactory`。

```java
    /** VIP vs 非VIP 各自高频好评/差评口味标签 (非菜名)。 */
    public Map<String, Object> fetchReviewVipTags(String factoryId, int topN) throws IOException {
        requireFactory(factoryId);
        return getReviewJson("/api/smartbi/gold/review-vip-tags", Map.of(
                "factory_id", factoryId,
                "top_n", String.valueOf(topN)));
    }

    /** 各时段 (早/午/下午/晚/夜) 评价量与平均星级。 */
    public Map<String, Object> fetchReviewTimePeriod(String factoryId) throws IOException {
        requireFactory(factoryId);
        return getReviewJson("/api/smartbi/gold/review-time-period",
                Map.of("factory_id", factoryId));
    }

    /**
     * 服务标签 / 环境标签 高频词 + 平均分。
     * @param dim service|env
     */
    public Map<String, Object> fetchReviewScoreTags(String factoryId, String dim, int topN)
            throws IOException {
        requireFactory(factoryId);
        return getReviewJson("/api/smartbi/gold/review-score-tags", Map.of(
                "factory_id", factoryId,
                "dim", dim,
                "top_n", String.valueOf(topN)));
    }

    /** 好评(>=4.5星)高频口味/品质标签 (非菜名)。 */
    public Map<String, Object> fetchReviewGoodTags(String factoryId, int topN) throws IOException {
        requireFactory(factoryId);
        return getReviewJson("/api/smartbi/gold/review-good-tags", Map.of(
                "factory_id", factoryId,
                "top_n", String.valueOf(topN)));
    }

    /** 各平台 (点评/美团) 评价量与平均星级对比。 */
    public Map<String, Object> fetchReviewPlatform(String factoryId) throws IOException {
        requireFactory(factoryId);
        return getReviewJson("/api/smartbi/gold/review-platform",
                Map.of("factory_id", factoryId));
    }

    /** 按 time_period 月份聚合评价量与平均星级 (时间序列)。 */
    public Map<String, Object> fetchReviewTrend(String factoryId) throws IOException {
        requireFactory(factoryId);
        return getReviewJson("/api/smartbi/gold/review-trend",
                Map.of("factory_id", factoryId));
    }

    /** 商家回复率 (已/未回复 + 未回复差评数)。 */
    public Map<String, Object> fetchReviewReplyRate(String factoryId) throws IOException {
        requireFactory(factoryId);
        return getReviewJson("/api/smartbi/gold/review-reply-rate",
                Map.of("factory_id", factoryId));
    }
```

### 4.2 `AbstractReviewGoldTool.java` — 新增 depth helper

在现有 helper (`listOfMaps`) 之后追加。这些 helper 让 12 个工具统一拼装 `suggestedFollowups` / `glossary` / `chartGuide`。

```java
    // -------------------------------------------------------------------------
    // P1 conversational-depth helpers — suggestedFollowups / glossary / chartGuide
    // -------------------------------------------------------------------------

    /** Build one follow-up chip: {label (短，显示在按钮), question (点击后发送的查询)}. */
    protected static Map<String, Object> followup(String label, String question) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("label", label);
        m.put("question", question);
        return m;
    }

    /** Collect follow-up chips into a list (varargs convenience). */
    @SafeVarargs
    protected static List<Map<String, Object>> followups(Map<String, Object>... entries) {
        List<Map<String, Object>> list = new java.util.ArrayList<>();
        for (Map<String, Object> e : entries) {
            if (e != null) list.add(e);
        }
        return list;
    }

    /** Build an ordered glossary map (term → 通俗定义). Pairs flattened: k1,v1,k2,v2,... */
    protected static Map<String, String> glossary(String... kv) {
        Map<String, String> g = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            g.put(kv[i], kv[i + 1]);
        }
        return g;
    }

    /**
     * Attach the three conversational-depth fields onto a tool result map.
     * Skips null / empty so empty-state results stay clean. Called at the end
     * of each concrete {@code format()}.
     */
    protected void attachDepth(
            Map<String, Object> result,
            List<Map<String, Object>> followups,
            Map<String, String> glossary,
            String chartGuide) {
        if (followups != null && !followups.isEmpty()) {
            result.put("suggestedFollowups", followups);
        }
        if (glossary != null && !glossary.isEmpty()) {
            result.put("glossary", glossary);
        }
        if (chartGuide != null && !chartGuide.isEmpty()) {
            result.put("chartGuide", chartGuide);
        }
    }
```

`AbstractReviewGoldTool` import 头追加 `import java.util.ArrayList;` (或用全限定 `java.util.ArrayList` 如上)。

### 4.3 新建 8 个评价工具 (4 within-review 跨维 + 4 评价问题)

包: `com.cretas.aims.ai.tool.impl.restaurant.gold.review`。全部 `extends AbstractReviewGoldTool`，`@Component`。每个工具 4 抽象方法 (`getToolName`/`getDescription`/`queryGold`/`format`/`isEmpty`/`emptyMessage`)。`format()` 末尾必调 `attachDepth(...)`。

#### 4.3.1 `RestaurantReviewVipTagsTool` (VIP×口味)

```java
package com.cretas.aims.ai.tool.impl.restaurant.gold.review;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * VIP vs 非VIP 高频好评/差评口味标签对比(大众点评 菜品标签=口味/品质标签, 非菜名)。
 * 适用意图: VIP喜欢什么 / VIP差评点 / 会员口味偏好。
 */
@Slf4j
@Component
public class RestaurantReviewVipTagsTool extends AbstractReviewGoldTool {

    @Override
    public String getToolName() {
        return "restaurant_review_vip_tags";
    }

    @Override
    public String getDescription() {
        return "VIP vs 非VIP 各自的高频好评/差评口味标签对比(大众点评菜品标签为口味/品质描述, 非具体菜名)。适用: VIP喜欢什么/VIP差评点/会员口味偏好。";
    }

    @Override
    protected Map<String, Object> queryGold(
            String factoryId, LocalDate start, LocalDate end, Map<String, Object> params)
            throws Exception {
        return gold.fetchReviewVipTags(factoryId, 6);
    }

    @Override
    protected boolean isEmpty(Map<String, Object> g) {
        return listOfMaps(g.get("vip_good_tags")).isEmpty()
                && listOfMaps(g.get("normal_good_tags")).isEmpty();
    }

    @Override
    protected Map<String, Object> format(Map<String, Object> g) {
        List<Map<String, Object>> vipGood = listOfMaps(g.get("vip_good_tags"));
        List<Map<String, Object>> vipBad = listOfMaps(g.get("vip_bad_tags"));
        List<Map<String, Object>> norGood = listOfMaps(g.get("normal_good_tags"));
        List<Map<String, Object>> norBad = listOfMaps(g.get("normal_bad_tags"));

        StringBuilder sb = new StringBuilder();
        sb.append("VIP vs 非VIP 口味/品质标签对比（标签为口味描述，非具体菜名）：\n");
        sb.append("· VIP 好评高频: ").append(joinTags(vipGood)).append("\n");
        sb.append("· VIP 差评高频: ").append(joinTags(vipBad)).append("\n");
        sb.append("· 非VIP 好评高频: ").append(joinTags(norGood)).append("\n");
        sb.append("· 非VIP 差评高频: ").append(joinTags(norBad));

        // 横向对比柱图: VIP 好评 top 标签量
        List<String> names = new ArrayList<>();
        List<Integer> vals = new ArrayList<>();
        for (Map<String, Object> t : vipGood) {
            names.add(String.valueOf(t.get("tag")));
            vals.add(intOf(t.get("count")));
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("VIP好评标签", vipGood);
        result.put("VIP差评标签", vipBad);
        result.put("非VIP好评标签", norGood);
        result.put("非VIP差评标签", norBad);
        result.put("dataAvailable", true);
        result.put("message", sb.toString());
        if (!names.isEmpty()) {
            result.put("chartConfig", barChartConfig("VIP 好评高频口味标签", names, vals, "条"));
        }
        attachDepth(result,
                followups(
                        followup("VIP 评价情况", "VIP评价情况"),
                        followup("整体好评高频词", "好评最多提到什么"),
                        followup("差评集中点", "投诉最集中的问题"),
                        followup("VIP 在哪个时段评价", "各时段评价对比")),
                glossary(
                        "口味/品质标签", "顾客在评价里勾选的口味描述词(如 味道好/鲜嫩/太软了)，不是具体菜名。",
                        "好评", "星级 >= 4.5 星的评价。",
                        "差评", "星级 <= 3 星的评价。"),
                "柱越长代表 VIP 顾客好评里提到该口味的次数越多，反映 VIP 最看重的味觉体验。");
        return result;
    }

    private static String joinTags(List<Map<String, Object>> tags) {
        if (tags.isEmpty()) return "（暂无）";
        StringBuilder s = new StringBuilder();
        for (int i = 0; i < tags.size(); i++) {
            if (i > 0) s.append("、");
            s.append(tags.get(i).get("tag")).append("(").append(intOf(tags.get(i).get("count"))).append(")");
        }
        return s.toString();
    }

    @Override
    protected String emptyMessage() {
        return "本店暂无含口味标签的评价数据。请确认已在「智能分析 - Excel上传」上传大众点评'评价下载'报表(含菜品标签字段)。";
    }
}
```

#### 4.3.2 `RestaurantReviewTimePeriodTool` (时段×评价)

```java
package com.cretas.aims.ai.tool.impl.restaurant.gold.review;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 各时段(早/午/下午/晚/夜)评价量与平均星级。time_period ~73% 有值。
 * 适用意图: 哪个时段评价好 / 时段评价分布 / 什么时间段口碑差。
 */
@Slf4j
@Component
public class RestaurantReviewTimePeriodTool extends AbstractReviewGoldTool {

    @Override
    public String getToolName() {
        return "restaurant_review_time_period";
    }

    @Override
    public String getDescription() {
        return "各时段(早/午/下午/晚/夜)评价量与平均星级分布(大众点评评价时间)。适用: 哪个时段评价好/时段评价分布/什么时间段口碑差。";
    }

    @Override
    protected Map<String, Object> queryGold(
            String factoryId, LocalDate start, LocalDate end, Map<String, Object> params)
            throws Exception {
        return gold.fetchReviewTimePeriod(factoryId);
    }

    @Override
    protected boolean isEmpty(Map<String, Object> g) {
        return listOfMaps(g.get("periods")).isEmpty();
    }

    @Override
    protected Map<String, Object> format(Map<String, Object> g) {
        List<Map<String, Object>> periods = listOfMaps(g.get("periods"));
        int nullCount = intOf(g.get("null_period_count"));
        int total = intOf(g.get("total_reviews"));

        StringBuilder sb = new StringBuilder();
        sb.append("各时段评价分布（平均星级满分5分）：\n");
        List<String> names = new ArrayList<>();
        List<Double> vals = new ArrayList<>();
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> p : periods) {
            String period = String.valueOf(p.get("period"));
            int n = intOf(p.get("review_count"));
            double avgStar = dbl(p.get("avg_star"));
            sb.append("· ").append(period).append(" — ").append(n).append(" 条，平均 ")
                    .append(fmt2(avgStar)).append(" 星\n");
            names.add(period);
            vals.add(avgStar);
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("时段", period);
            entry.put("评价数", n);
            entry.put("平均星级", avgStar);
            rows.add(entry);
        }
        if (nullCount > 0 && total > 0) {
            int pct = (int) Math.round(100.0 * (total - nullCount) / total);
            sb.append("（注：约 ").append(pct).append("% 评价含时间信息，其余 ")
                    .append(nullCount).append(" 条无时间未计入）");
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("时段评价分布", rows);
        result.put("无时间评价数", nullCount);
        result.put("dataAvailable", true);
        result.put("message", sb.toString());
        if (!names.isEmpty()) {
            result.put("chartConfig", barChartConfig("各时段平均星级 (分)", names, vals, "分"));
        }
        attachDepth(result,
                followups(
                        followup("各时段销售峰值", "时段销售分布"),
                        followup("差评集中在哪个时段", "什么时间段口碑差"),
                        followup("整体评价总览", "客户评价怎么样"),
                        followup("门店服务分排名", "服务分排名")),
                glossary(
                        "时段划分", "早 5-10点 / 午 11-14点 / 下午 15-16点 / 晚 17-21点 / 夜 22-4点。",
                        "平均星级", "该时段所有评价的星级算术平均(满分5分)。",
                        "覆盖率", "并非所有评价都带评价时间，约 73% 有时间信息，其余不计入时段统计。"),
                "横轴时段、纵轴平均星级，柱越高该时段口碑越好；结合评价数看哪些时段既高峰又口碑稳。");
        return result;
    }

    @Override
    protected String emptyMessage() {
        return "本店评价数据暂无可用的评价时间信息，无法做时段分析。请确认上传的大众点评'评价下载'报表含评价时间字段。";
    }
}
```

#### 4.3.3 `RestaurantReviewScoreTagsTool` (服务/环境标签×评分)

```java
package com.cretas.aims.ai.tool.impl.restaurant.gold.review;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 服务标签/环境标签 高频词 + 平均分。默认服务维度；用户问环境时由意图层传 dim=env。
 * 适用意图: 服务标签 / 顾客怎么评价服务 / 环境评价标签。
 */
@Slf4j
@Component
public class RestaurantReviewScoreTagsTool extends AbstractReviewGoldTool {

    @Override
    public String getToolName() {
        return "restaurant_review_score_tags";
    }

    @Override
    public String getDescription() {
        return "服务/环境评价标签高频词 + 对应平均分(大众点评服务标签/环境标签)。适用: 服务标签/顾客怎么评价服务/环境评价标签。默认服务维度。";
    }

    @Override
    protected Map<String, Object> queryGold(
            String factoryId, LocalDate start, LocalDate end, Map<String, Object> params)
            throws Exception {
        // 意图层可能在 params 注入 dim (env/service)；userInput 含"环境"则取 env。
        String dim = getString(params, "dim");
        if (dim == null || dim.isEmpty()) {
            String ui = getString(params, "userInput");
            dim = (ui != null && ui.contains("环境")) ? "env" : "service";
        }
        return gold.fetchReviewScoreTags(factoryId, dim, 10);
    }

    @Override
    protected boolean isEmpty(Map<String, Object> g) {
        return listOfMaps(g.get("tags")).isEmpty();
    }

    @Override
    protected Map<String, Object> format(Map<String, Object> g) {
        List<Map<String, Object>> tags = listOfMaps(g.get("tags"));
        String dim = String.valueOf(g.getOrDefault("dim", "service"));
        String dimName = "env".equals(dim) ? "环境" : "服务";

        StringBuilder sb = new StringBuilder();
        sb.append(dimName).append("评价标签高频词（标签后括号为提及次数 / 该标签下平均").append(dimName).append("分）：\n");
        List<String> names = new ArrayList<>();
        List<Integer> vals = new ArrayList<>();
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> t : tags) {
            String tag = String.valueOf(t.get("tag"));
            int n = intOf(t.get("count"));
            double avg = dbl(t.get("avg_score"));
            sb.append("· ").append(tag).append("（").append(n).append(" 次");
            if (avg > 0) sb.append(" / 均分 ").append(fmt2(avg));
            sb.append("）\n");
            names.add(tag);
            vals.add(n);
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("标签", tag);
            entry.put("提及次数", n);
            entry.put("平均分", avg);
            rows.add(entry);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put(dimName + "标签分布", rows);
        result.put("维度", dimName);
        result.put("dataAvailable", true);
        result.put("message", sb.toString());
        if (!names.isEmpty()) {
            result.put("chartConfig", barChartConfig(dimName + "评价标签高频词", names, vals, "次"));
        }
        attachDepth(result,
                followups(
                        followup("门店" + dimName + "分排名", "env".equals(dim) ? "环境分对比" : "服务分排名"),
                        followup("差评集中点", "投诉最集中的问题"),
                        followup("VIP 评价情况", "VIP评价情况"),
                        followup("整体评价总览", "客户评价怎么样")),
                glossary(
                        dimName + "标签", "顾客评价时勾选的" + dimName + "相关标签(如 服务热情/环境优雅)，由大众点评预设。",
                        "提及次数", "该标签在所有评价中被勾选的总次数。",
                        "平均分", "勾选该标签的评价对应的" + dimName + "分平均值。"),
                "柱越长代表该" + dimName + "标签被提及越多，反映顾客最常感知到的" + dimName + "特征。");
        return result;
    }

    @Override
    protected String emptyMessage() {
        return "本店暂无服务/环境评价标签数据。请确认已上传大众点评'评价下载'报表(含服务标签/环境标签字段)。";
    }
}
```

#### 4.3.4 `RestaurantReviewGoodTagsTool` (好评高频词)

```java
package com.cretas.aims.ai.tool.impl.restaurant.gold.review;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 好评(>=4.5星)高频口味/品质标签(非菜名)。
 * 适用意图: 好评最多提到什么 / 顾客最满意什么 / 好评高频词。
 */
@Slf4j
@Component
public class RestaurantReviewGoodTagsTool extends AbstractReviewGoldTool {

    @Override
    public String getToolName() {
        return "restaurant_review_good_tags";
    }

    @Override
    public String getDescription() {
        return "好评(>=4.5星)中高频的口味/品质标签(大众点评菜品标签为口味描述, 非具体菜名)。适用: 好评最多提到什么/顾客最满意什么/好评高频词。";
    }

    @Override
    protected Map<String, Object> queryGold(
            String factoryId, LocalDate start, LocalDate end, Map<String, Object> params)
            throws Exception {
        return gold.fetchReviewGoodTags(factoryId, 10);
    }

    @Override
    protected boolean isEmpty(Map<String, Object> g) {
        return listOfMaps(g.get("tags")).isEmpty();
    }

    @Override
    protected Map<String, Object> format(Map<String, Object> g) {
        List<Map<String, Object>> tags = listOfMaps(g.get("tags"));
        int highStar = intOf(g.get("high_star_count"));

        StringBuilder sb = new StringBuilder();
        sb.append("好评(≥4.5星，共 ").append(highStar).append(" 条)高频口味/品质标签（非菜名）：\n");
        List<String> names = new ArrayList<>();
        List<Integer> vals = new ArrayList<>();
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int i = 0; i < tags.size(); i++) {
            Map<String, Object> t = tags.get(i);
            String tag = String.valueOf(t.get("tag"));
            int n = intOf(t.get("count"));
            sb.append(i + 1).append(". ").append(tag).append("（").append(n).append(" 次）\n");
            names.add(tag);
            vals.add(n);
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("标签", tag);
            entry.put("提及次数", n);
            rows.add(entry);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("好评高频标签", rows);
        result.put("好评总数", highStar);
        result.put("dataAvailable", true);
        result.put("message", sb.toString());
        if (!names.isEmpty()) {
            result.put("chartConfig", barChartConfig("好评高频口味/品质标签", names, vals, "次"));
        }
        attachDepth(result,
                followups(
                        followup("差评高频词", "哪些菜品差评多"),
                        followup("VIP 喜欢什么", "VIP喜欢什么口味"),
                        followup("各平台口碑对比", "各平台评价对比"),
                        followup("整体评价总览", "客户评价怎么样")),
                glossary(
                        "口味/品质标签", "顾客在好评里勾选的口味描述词(味道好/鲜嫩/新鲜)，不是具体菜名。",
                        "好评", "星级 >= 4.5 星的评价。",
                        "提及次数", "该标签在好评中被勾选的总次数。"),
                "柱越长代表顾客在好评里提到该口味越多，是门店最受认可的味觉卖点。");
        return result;
    }

    @Override
    protected String emptyMessage() {
        return "本店暂无好评口味标签数据。请确认已上传大众点评'评价下载'报表(含菜品标签字段)。";
    }
}
```

#### 4.3.5 `RestaurantReviewPlatformTool` (各平台对比)

```java
package com.cretas.aims.ai.tool.impl.restaurant.gold.review;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 各平台(点评/美团)评价量与平均星级对比。
 * 适用意图: 各平台评价对比 / 点评和美团哪个评分高 / 平台口碑。
 */
@Slf4j
@Component
public class RestaurantReviewPlatformTool extends AbstractReviewGoldTool {

    @Override
    public String getToolName() {
        return "restaurant_review_platform";
    }

    @Override
    public String getDescription() {
        return "各平台(点评/美团)评价量与平均星级对比。适用: 各平台评价对比/点评和美团哪个评分高/平台口碑。";
    }

    @Override
    protected Map<String, Object> queryGold(
            String factoryId, LocalDate start, LocalDate end, Map<String, Object> params)
            throws Exception {
        return gold.fetchReviewPlatform(factoryId);
    }

    @Override
    protected boolean isEmpty(Map<String, Object> g) {
        return listOfMaps(g.get("platforms")).isEmpty();
    }

    @Override
    protected Map<String, Object> format(Map<String, Object> g) {
        List<Map<String, Object>> platforms = listOfMaps(g.get("platforms"));

        StringBuilder sb = new StringBuilder();
        sb.append("各平台评价对比（平均星级满分5分）：\n");
        List<String> names = new ArrayList<>();
        List<Integer> counts = new ArrayList<>();
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> p : platforms) {
            String name = String.valueOf(p.get("platform"));
            int n = intOf(p.get("review_count"));
            double avgStar = dbl(p.get("avg_star"));
            sb.append("· ").append(name).append(" — ").append(n).append(" 条，平均 ")
                    .append(fmt2(avgStar)).append(" 星\n");
            names.add(name);
            counts.add(n);
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("平台", name);
            entry.put("评价数", n);
            entry.put("平均星级", avgStar);
            rows.add(entry);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("平台评价对比", rows);
        result.put("dataAvailable", true);
        result.put("message", sb.toString());
        // 评价量占比用饼图更直观
        if (!names.isEmpty()) {
            result.put("chartConfig", pieChartConfig("各平台评价量占比", names, counts));
        }
        attachDepth(result,
                followups(
                        followup("整体评价总览", "客户评价怎么样"),
                        followup("回复率情况", "评价回复率"),
                        followup("评价趋势", "评价趋势怎么样"),
                        followup("差评最多门店", "差评最多的门店")),
                glossary(
                        "平台", "评价来源渠道(大众点评 / 美团)。",
                        "评价数", "该平台去重后的有效评价条数。",
                        "平均星级", "该平台所有评价星级的算术平均(满分5分)。"),
                "扇区面积代表各平台评价量占比；结合各平台平均星级看哪个渠道口碑更优。");
        return result;
    }

    @Override
    protected String emptyMessage() {
        return "本店暂无平台来源标注的评价数据。请确认已上传大众点评'评价下载'报表(含平台字段)。";
    }
}
```

#### 4.3.6 `RestaurantReviewTrendTool` (评价趋势)

```java
package com.cretas.aims.ai.tool.impl.restaurant.gold.review;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 评价趋势(按月聚合评价量与平均星级)。
 * 适用意图: 评价趋势 / 口碑变化 / 评分走势。
 */
@Slf4j
@Component
public class RestaurantReviewTrendTool extends AbstractReviewGoldTool {

    @Override
    public String getToolName() {
        return "restaurant_review_trend";
    }

    @Override
    public String getDescription() {
        return "评价趋势(按月聚合评价量与平均星级走势)。适用: 评价趋势/口碑变化/评分走势/最近评价好转还是变差。";
    }

    @Override
    protected Map<String, Object> queryGold(
            String factoryId, LocalDate start, LocalDate end, Map<String, Object> params)
            throws Exception {
        return gold.fetchReviewTrend(factoryId);
    }

    @Override
    protected boolean isEmpty(Map<String, Object> g) {
        return listOfMaps(g.get("months")).isEmpty();
    }

    @Override
    protected Map<String, Object> format(Map<String, Object> g) {
        List<Map<String, Object>> months = listOfMaps(g.get("months"));
        int nullCount = intOf(g.get("null_period_count"));

        StringBuilder sb = new StringBuilder();
        sb.append("评价趋势（按月，平均星级满分5分）：\n");
        List<String> xLabels = new ArrayList<>();
        List<Double> starVals = new ArrayList<>();
        List<Integer> countVals = new ArrayList<>();
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> m : months) {
            String month = String.valueOf(m.get("month"));
            int n = intOf(m.get("review_count"));
            double avgStar = dbl(m.get("avg_star"));
            xLabels.add(month);
            starVals.add(avgStar);
            countVals.add(n);
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("月份", month);
            entry.put("评价数", n);
            entry.put("平均星级", avgStar);
            rows.add(entry);
        }
        // 首尾对比结论
        if (months.size() >= 2) {
            double first = dbl(months.get(0).get("avg_star"));
            double last = dbl(months.get(months.size() - 1).get("avg_star"));
            double diff = last - first;
            sb.append("· 区间内 ").append(months.size()).append(" 个月，平均星级从 ")
                    .append(fmt2(first)).append(" 到 ").append(fmt2(last));
            if (diff > 0.05) sb.append("，口碑上升 ").append(fmt2(diff)).append(" 分。");
            else if (diff < -0.05) sb.append("，口碑下降 ").append(fmt2(-diff)).append(" 分，建议排查近期门店运营。");
            else sb.append("，口碑基本平稳。");
        }
        if (nullCount > 0) {
            sb.append("\n（注：").append(nullCount).append(" 条评价无时间未计入趋势）");
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("评价趋势", rows);
        result.put("dataAvailable", true);
        result.put("message", sb.toString());
        // 折线图: 平均星级走势 (line option 手工构造, barChartConfig 不适用)
        if (!xLabels.isEmpty()) {
            result.put("chartConfig", trendLineConfig(xLabels, starVals, countVals));
        }
        attachDepth(result,
                followups(
                        followup("整体评价总览", "客户评价怎么样"),
                        followup("各平台口碑对比", "各平台评价对比"),
                        followup("销售月度趋势", "月度趋势"),
                        followup("差评集中点", "投诉最集中的问题")),
                glossary(
                        "评价月趋势", "按评价时间所在月份聚合的评价量与平均星级，反映口碑随时间的变化。",
                        "平均星级", "该月所有评价星级的算术平均(满分5分)。"),
                "折线为各月平均星级走势(右轴为评价量)；线下行说明近期口碑走弱，需结合该月差评排查。");
        return result;
    }

    /** 双轴折线: 左轴平均星级(line), 右轴评价量(bar)。返回 {type,title,option}. */
    private static Map<String, Object> trendLineConfig(
            List<String> x, List<Double> star, List<Integer> count) {
        Map<String, Object> opt = new LinkedHashMap<>();
        opt.put("tooltip", Map.of("trigger", "axis"));
        opt.put("legend", Map.of("data", List.of("平均星级", "评价量"), "top", "bottom"));
        opt.put("grid", Map.of("left", "3%", "right", "5%", "bottom", "12%", "top", "10%", "containLabel", true));
        opt.put("xAxis", Map.of("type", "category", "data", x,
                "axisLabel", Map.of("rotate", 30, "fontSize", 11)));
        opt.put("yAxis", List.of(
                Map.of("type", "value", "name", "星级", "min", 0, "max", 5),
                Map.of("type", "value", "name", "评价量")));
        Map<String, Object> starSeries = new LinkedHashMap<>();
        starSeries.put("name", "平均星级");
        starSeries.put("type", "line");
        starSeries.put("smooth", true);
        starSeries.put("data", star);
        starSeries.put("itemStyle", Map.of("color", "#5470c6"));
        Map<String, Object> cntSeries = new LinkedHashMap<>();
        cntSeries.put("name", "评价量");
        cntSeries.put("type", "bar");
        cntSeries.put("yAxisIndex", 1);
        cntSeries.put("data", count);
        cntSeries.put("itemStyle", Map.of("color", "#91cc75"));
        opt.put("series", List.of(starSeries, cntSeries));

        Map<String, Object> cfg = new LinkedHashMap<>();
        cfg.put("type", "line");
        cfg.put("title", "评价月趋势 (星级 + 评价量)");
        cfg.put("option", opt);
        return cfg;
    }

    @Override
    protected String emptyMessage() {
        return "本店评价数据暂无可用时间信息，无法做趋势分析。请确认上传的大众点评'评价下载'报表含评价时间字段。";
    }
}
```

#### 4.3.7 `RestaurantReviewReplyRateTool` (回复率)

```java
package com.cretas.aims.ai.tool.impl.restaurant.gold.review;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 商家评价回复率(已/未回复 + 未回复差评数)。
 * 适用意图: 评价回复率 / 有多少评价没回复 / 回复及时吗。
 */
@Slf4j
@Component
public class RestaurantReviewReplyRateTool extends AbstractReviewGoldTool {

    @Override
    public String getToolName() {
        return "restaurant_review_reply_rate";
    }

    @Override
    public String getDescription() {
        return "商家评价回复率(已回复/未回复评价数 + 未回复差评数)。适用: 评价回复率/有多少评价没回复/回复及时吗。";
    }

    @Override
    protected Map<String, Object> queryGold(
            String factoryId, LocalDate start, LocalDate end, Map<String, Object> params)
            throws Exception {
        return gold.fetchReviewReplyRate(factoryId);
    }

    @Override
    protected boolean isEmpty(Map<String, Object> g) {
        return intOf(g.get("total_with_status")) == 0;
    }

    @Override
    protected Map<String, Object> format(Map<String, Object> g) {
        int replied = intOf(g.get("replied"));
        int notReplied = intOf(g.get("not_replied"));
        int notRepliedLow = intOf(g.get("not_replied_low_star"));
        double rate = dbl(g.get("reply_rate"));

        StringBuilder sb = new StringBuilder();
        sb.append("评价回复情况：\n");
        sb.append("· 回复率 ").append(fmt2(rate)).append("%（已回复 ").append(replied)
                .append(" 条 / 未回复 ").append(notReplied).append(" 条）\n");
        if (notRepliedLow > 0) {
            sb.append("· 其中有 ").append(notRepliedLow).append(" 条差评(≤3星)尚未回复，建议优先处理以挽回口碑。");
        } else {
            sb.append("· 差评均已回复，口碑维护到位。");
        }

        List<String> names = new ArrayList<>(List.of("已回复", "未回复"));
        List<Integer> vals = new ArrayList<>(List.of(replied, notReplied));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("回复率", rate);
        result.put("已回复数", replied);
        result.put("未回复数", notReplied);
        result.put("未回复差评数", notRepliedLow);
        result.put("dataAvailable", true);
        result.put("message", sb.toString());
        result.put("chartConfig", pieChartConfig("评价回复占比", names, vals));
        attachDepth(result,
                followups(
                        followup("未回复差评在哪些门店", "差评最多的门店"),
                        followup("差评集中点", "投诉最集中的问题"),
                        followup("整体评价总览", "客户评价怎么样"),
                        followup("评价趋势", "评价趋势怎么样")),
                glossary(
                        "回复率", "已回复评价数 ÷ 含回复状态的评价总数 × 100%。",
                        "未回复差评", "星级 <= 3 星且商家尚未回复的评价，属高优先级处理对象。"),
                "扇区代表已回复 vs 未回复占比；未回复差评是最该优先跟进的部分。");
        return result;
    }

    @Override
    protected String emptyMessage() {
        return "本店暂无评价回复状态数据。请确认已上传大众点评'评价下载'报表(含回复状态字段)。";
    }
}
```

> **第 8 个工具**：4 个跨维 (vip_tags / time_period / score_tags) + 4 个问题 (good_tags / platform / trend / reply_rate) = 7 个新文件。`review_score_tags` 同时覆盖「服务标签」与「环境标签」两个意图 (dim 参数路由)，所以 (3) 的「服务/环境标签×评分」是**一个工具**。共 **7 个新 Java 工具**，对应 **8 个新意图** (score_tags 绑两个意图 SERVICE_TAGS + ENV_TAGS，dim 由 userInput 含「环境」自动判定)。

---

## 5. 前端 `web-admin/src/views/smart-bi/AIQuery.vue`

### 5.1 `ChatMessage` 接口扩展 (line ~67-100 内)

在 `chartConfig?` 之后追加：

```typescript
  // P1 conversational-depth (2026-06-02): gold-tool answers carry these.
  // suggestedFollowups: clickable down-drill chips ({label shown, question sent}).
  // glossary: term → 通俗定义 for the expandable 字段说明 block + local meta-Q answers.
  // chartGuide: one-liner explaining how to read the chart.
  suggestedFollowups?: Array<{ label: string; question: string }>;
  glossary?: Record<string, string>;
  chartGuide?: string;
  // UI local state for the expandable 字段说明/怎么看图 block.
  depthExpanded?: boolean;
```

### 5.2 `tryJavaIntentChat` — 抓取三字段 (在 `_toolChart` 处理之后，line ~703 附近)

gold 工具的 map 直接是 `res.resultData` (无 `.data`)，所以三字段也在 `res.resultData` 顶层。复用同样的 `res.resultData ?? toolData` 双查模式：

```typescript
      // P1 (2026-06-02): conversational-depth fields. Gold tools return their
      // map DIRECTLY as resultData (no .data wrapper) — same as chartConfig.
      // Report-style tools (buildSimpleResult) put them under resultData.data.
      // eslint-disable-next-line @typescript-eslint/no-explicit-any
      const _rd = res.resultData as any;
      // eslint-disable-next-line @typescript-eslint/no-explicit-any
      const _td = toolData as any;
      const _followups = _rd?.suggestedFollowups ?? _td?.suggestedFollowups;
      if (Array.isArray(_followups) && _followups.length > 0) {
        msg.suggestedFollowups = _followups
          .filter((f: unknown) => f && typeof (f as { question?: unknown }).question === 'string')
          .map((f: { label?: string; question: string }) => ({
            label: String(f.label ?? f.question),
            question: String(f.question),
          }));
      }
      const _glossary = _rd?.glossary ?? _td?.glossary;
      if (_glossary && typeof _glossary === 'object' && !Array.isArray(_glossary)) {
        msg.glossary = _glossary as Record<string, string>;
      }
      const _chartGuide = _rd?.chartGuide ?? _td?.chartGuide;
      if (typeof _chartGuide === 'string' && _chartGuide.trim()) {
        msg.chartGuide = _chartGuide;
      }
```

> **缓存路径注意**：CACHED 答案把整个 map JSON.stringify 进 `message` (带「(缓存结果) 」前缀)。现有代码已有「parse JSON out of message」分支 (line ~707-722)。在该分支的 `if (inner?.chartConfig?.option) ...` 之后追加同样的三字段抓取 (从 `inner` 取)：

```typescript
            if (Array.isArray(inner?.suggestedFollowups)) {
              msg.suggestedFollowups = inner.suggestedFollowups
                .filter((f: { question?: unknown }) => f && typeof f.question === 'string')
                .map((f: { label?: string; question: string }) => ({
                  label: String(f.label ?? f.question), question: String(f.question),
                }));
            }
            if (inner?.glossary && typeof inner.glossary === 'object') msg.glossary = inner.glossary;
            if (typeof inner?.chartGuide === 'string') msg.chartGuide = inner.chartGuide;
```

### 5.3 followup chip 渲染块 — 独立于 `materialized_cache` source

现有 `RELATED_FOLLOWUPS` 块只在 `source === 'materialized_cache'` 且 `relatedFollowups(templateCode)` 非空时渲染 (line ~1726)。**新增**一个独立块，键为 `message.suggestedFollowups`，放在 `message-related-followups` 块**之后** (两者互斥：gold 答案有 suggestedFollowups 无 templateCode，物化答案反之)：

```vue
                <!-- P1 (2026-06-02): gold-tool dynamic follow-up chips.
                     Independent of materialized_cache source — gold answers
                     carry suggestedFollowups directly. -->
                <div
                  v-if="message.role === 'assistant' && !message.loading && !message.streaming && (message.suggestedFollowups?.length ?? 0) > 0"
                  class="message-related-followups"
                >
                  <span class="related-label">继续追问:</span>
                  <el-button
                    v-for="(f, i) in message.suggestedFollowups"
                    :key="i"
                    size="small"
                    type="info"
                    plain
                    round
                    @click="triggerRelatedFollowup(f.question)"
                  >
                    {{ f.label }}
                  </el-button>
                </div>
```

`triggerRelatedFollowup(query)` 已存在 (line ~431)，直接复用 (它 set inputQuery + handleSendMessage)。

### 5.4 glossary / chartGuide 可展开块 — 放在图表之后、追问之前

在 `message-chart` 块 (line ~1654) 之后插入：

```vue
                <!-- P1 (2026-06-02): expandable 字段说明 / 怎么看这张图 (zero extra call). -->
                <div
                  v-if="message.role === 'assistant' && !message.loading && !message.streaming && ((message.glossary && Object.keys(message.glossary).length > 0) || message.chartGuide)"
                  class="message-depth-block"
                >
                  <el-link
                    type="info"
                    :underline="false"
                    class="depth-toggle"
                    @click="message.depthExpanded = !message.depthExpanded"
                  >
                    <el-icon><QuestionFilled /></el-icon>
                    {{ message.depthExpanded ? '收起说明' : '字段说明 / 怎么看这张图' }}
                  </el-link>
                  <div v-show="message.depthExpanded" class="depth-content">
                    <div v-if="message.chartGuide" class="depth-chart-guide">
                      <strong>怎么看这张图：</strong>{{ message.chartGuide }}
                    </div>
                    <dl v-if="message.glossary && Object.keys(message.glossary).length > 0" class="depth-glossary">
                      <template v-for="(def, term) in message.glossary" :key="term">
                        <dt>{{ term }}</dt>
                        <dd>{{ def }}</dd>
                      </template>
                    </dl>
                  </div>
                </div>
```

import `QuestionFilled` from `@element-plus/icons-vue` (检查 AIQuery.vue 顶部 icon import 是否已含；若无则追加)。

### 5.5 meta 问题本地解释 (0 LLM) — 在 `handleSendMessage` 入口拦截

新增一个本地 meta-问题识别 + 回答函数，在 `handleSendMessage` 把 query 推入 Java/Python 之前调用。它扫描**上一条 assistant 答案**的 `glossary`，若用户问「X 什么意思 / X 是啥 / 这张图说明什么 / 怎么看这张图」且 X 命中 glossary 词条 → 直接本地渲染答案，0 调用。

```typescript
// P1 (2026-06-02): local meta-question resolver. Answers "这个字段什么意思" /
// "这张图说明什么" from the LAST assistant answer's glossary/chartGuide with
// ZERO LLM/API calls. Falls through (returns false) when it can't answer
// locally, so the normal pipeline (which will pass glossary as LLM context)
// runs instead.
const META_FIELD_PATTERNS = [/(.+?)\s*(是什么意思|什么意思|是啥|是什么|怎么算|怎么理解|指的是什么)/];
const META_CHART_PATTERNS = [/(这张图|这个图|图表|图)(说明|表示|代表|怎么看|看什么|什么意思)/];

function lastAssistantAnswer(): ChatMessage | undefined {
  for (let i = chatHistory.value.length - 1; i >= 0; i--) {
    const m = chatHistory.value[i];
    if (m.role === 'assistant' && !m.loading && !m.streaming) return m;
  }
  return undefined;
}

function tryLocalMetaAnswer(query: string): boolean {
  const prev = lastAssistantAnswer();
  if (!prev) return false;
  const q = query.trim();

  // (a) "这张图说明什么 / 怎么看这张图" → chartGuide
  if (META_CHART_PATTERNS.some((re) => re.test(q)) && prev.chartGuide) {
    pushLocalAssistant(`关于上一张图：${prev.chartGuide}`);
    return true;
  }

  // (b) "X 什么意思" → glossary[X] (substring match, longest term wins)
  if (prev.glossary && Object.keys(prev.glossary).length > 0) {
    for (const pat of META_FIELD_PATTERNS) {
      const m = q.match(pat);
      if (m && m[1]) {
        const asked = m[1].trim();
        const terms = Object.keys(prev.glossary).sort((a, b) => b.length - a.length);
        const hit = terms.find((t) => asked.includes(t) || t.includes(asked));
        if (hit) {
          pushLocalAssistant(`「${hit}」：${prev.glossary[hit]}`);
          return true;
        }
      }
    }
    // (c) bare term lookup: query itself IS a glossary term (e.g. user types "差评率")
    const direct = Object.keys(prev.glossary).find((t) => t === q);
    if (direct) {
      pushLocalAssistant(`「${direct}」：${prev.glossary[direct]}`);
      return true;
    }
  }
  return false;
}

// Render a synthetic assistant bubble without any backend call.
function pushLocalAssistant(content: string) {
  // Echo the user's question first (mirror handleSendMessage's user-echo).
  chatHistory.value.push({
    id: `u-${Date.now()}`, role: 'user', content: inputQuery.value.trim(), timestamp: new Date(),
  });
  chatHistory.value.push({
    id: `a-${Date.now()}`, role: 'assistant', content, timestamp: new Date(),
  });
  inputQuery.value = '';
  nextTick(() => scrollToBottom?.());
}
```

在 `handleSendMessage` 开头 (取到 `query` 之后、push user 消息之前) 加：

```typescript
async function handleSendMessage() {
  const query = inputQuery.value.trim();
  if (!query) return;
  // P1: try local glossary/chartGuide answer first (0 LLM).
  if (tryLocalMetaAnswer(query)) return;
  // ... 原有逻辑不变
```

> **兜底 LLM 带 glossary 上下文**：当 `tryLocalMetaAnswer` 返回 false (问的词不在 glossary)，正常管线继续。为了让 LLM 兜底回答 meta 问题时有上下文，在 Python fallback chat 调用前，把上一条答案的 glossary 作为 system/context 注入。**本 spec 不强制改 Python**——前端可在 `handleSendMessage` 的 Python fallback 分支 (chatAnalysis) 的 context 里附带 `lastAssistantAnswer()?.glossary`。这是 lower-priority 增强，作为 Task 8 的可选项 (若 chatAnalysis 已支持 context 透传则加，否则记 backlog)。

### 5.6 样式 (在 `<style scoped>` 内追加)

```css
    .message-depth-block {
      margin-top: 8px;
    }
    .depth-toggle {
      font-size: 12px;
    }
    .depth-content {
      margin-top: 6px;
      padding: 8px 12px;
      background: #f5f7fa;
      border-radius: 6px;
      font-size: 13px;
      line-height: 1.6;
    }
    .depth-chart-guide {
      margin-bottom: 6px;
      color: #303133;
    }
    .depth-glossary dt {
      font-weight: 600;
      color: #303133;
      margin-top: 4px;
    }
    .depth-glossary dd {
      margin: 0 0 4px 0;
      color: #606266;
    }
```

---

## 6. 迁移 `V20260904_01__restaurant_review_deep_intents.sql`

路径: `backend/java/cretas-api/src/main/resources/db/flyway/V20260904_01__restaurant_review_deep_intents.sql`

版本号 `20260904.01` > prod 已应用 max `20260903.03` (out-of-order=false，必须更大)。8 个新意图，priority 115，business_type=RESTAURANT，全部 `ON CONFLICT (intent_code) DO UPDATE` 幂等。

> **score_tags 双意图**：`RESTAURANT_REVIEW_SERVICE_TAGS` + `RESTAURANT_REVIEW_ENV_TAGS` 都绑 `restaurant_review_score_tags`，dim 由工具内 userInput 含「环境」自动判定 (4.3.3)。

```sql
-- 餐厅评价深度分析 8 意图 → P1 对话深度层 within-review 跨维 + 更多评价问题 (qhj, 2026-06-02)
--
-- 背景: P1 在已 LIVE 的评价 8 问 (PR #387/#391) 之上补深度:
--   跨维 3 工具 (VIP×口味 / 时段×评价 / 服务-环境标签×评分) +
--   评价问题 4 工具 (好评高频词 / 平台对比 / 评价趋势 / 回复率)。
--   score_tags 一个工具覆盖 服务标签 + 环境标签 两意图 (dim 自动判定)。
--   全部读 smart_bi_dynamic_data 评价数据 (按 评价ID 去重), 不 fallthrough LLM。
--
-- flyway 版本 20260904.01 > prod 已应用 max 20260903.03 (out-of-order=false, 必须更大)。
-- 幂等: 全部 ON CONFLICT (intent_code) DO UPDATE, 可重复执行。
-- priority 115 (= 评价基础问), business_type='RESTAURANT' 让业态门控放行。

-- (1) VIP×口味
INSERT INTO ai_intent_configs (id, intent_code, intent_name, intent_category, tool_name, sensitivity_level, keywords, description, priority, is_active, business_type, created_at, updated_at)
VALUES (gen_random_uuid(), 'RESTAURANT_REVIEW_VIP_TAGS', 'VIP口味偏好', 'SMARTBI', 'restaurant_review_vip_tags', 'LOW',
        '["vip喜欢什么","vip喜欢什么口味","vip好评点","vip差评点","会员口味偏好","vip在意什么","vip顾客喜欢","会员喜欢什么菜","vip对什么满意","vip吐槽什么"]'::jsonb,
        'VIP vs 非VIP 高频好评/差评口味标签对比 (口味/品质标签, 非菜名)', 115, true, 'RESTAURANT', NOW(), NOW())
ON CONFLICT (intent_code) DO UPDATE SET tool_name = 'restaurant_review_vip_tags', keywords = EXCLUDED.keywords, description = EXCLUDED.description, priority = 115, is_active = true, business_type = 'RESTAURANT', updated_at = NOW();

-- (2) 时段×评价
INSERT INTO ai_intent_configs (id, intent_code, intent_name, intent_category, tool_name, sensitivity_level, keywords, description, priority, is_active, business_type, created_at, updated_at)
VALUES (gen_random_uuid(), 'RESTAURANT_REVIEW_TIME_PERIOD', '时段评价分布', 'SMARTBI', 'restaurant_review_time_period', 'LOW',
        '["哪个时段评价好","时段评价","时段评价分布","什么时间段口碑差","什么时段评价高","各时段评价","时段口碑","哪个时间段差评多","时段评分对比","早午晚评价"]'::jsonb,
        '各时段(早/午/下午/晚/夜)评价量与平均星级 (评价时间 ~73% 有值)', 115, true, 'RESTAURANT', NOW(), NOW())
ON CONFLICT (intent_code) DO UPDATE SET tool_name = 'restaurant_review_time_period', keywords = EXCLUDED.keywords, description = EXCLUDED.description, priority = 115, is_active = true, business_type = 'RESTAURANT', updated_at = NOW();

-- (3a) 服务标签×评分
INSERT INTO ai_intent_configs (id, intent_code, intent_name, intent_category, tool_name, sensitivity_level, keywords, description, priority, is_active, business_type, created_at, updated_at)
VALUES (gen_random_uuid(), 'RESTAURANT_REVIEW_SERVICE_TAGS', '服务评价标签', 'SMARTBI', 'restaurant_review_score_tags', 'LOW',
        '["服务标签","服务评价标签","顾客怎么评价服务","服务评价词","顾客对服务的评价","服务好评词","服务相关评价","服务评价关键词","服务口碑标签"]'::jsonb,
        '服务评价标签高频词 + 平均服务分 (服务标签)', 115, true, 'RESTAURANT', NOW(), NOW())
ON CONFLICT (intent_code) DO UPDATE SET tool_name = 'restaurant_review_score_tags', keywords = EXCLUDED.keywords, description = EXCLUDED.description, priority = 115, is_active = true, business_type = 'RESTAURANT', updated_at = NOW();

-- (3b) 环境标签×评分 (同工具, dim=env 由 userInput 含"环境"判定)
INSERT INTO ai_intent_configs (id, intent_code, intent_name, intent_category, tool_name, sensitivity_level, keywords, description, priority, is_active, business_type, created_at, updated_at)
VALUES (gen_random_uuid(), 'RESTAURANT_REVIEW_ENV_TAGS', '环境评价标签', 'SMARTBI', 'restaurant_review_score_tags', 'LOW',
        '["环境标签","环境评价标签","顾客怎么评价环境","环境评价词","顾客对环境的评价","环境好评词","环境相关评价","环境评价关键词","环境口碑标签"]'::jsonb,
        '环境评价标签高频词 + 平均环境分 (环境标签)', 115, true, 'RESTAURANT', NOW(), NOW())
ON CONFLICT (intent_code) DO UPDATE SET tool_name = 'restaurant_review_score_tags', keywords = EXCLUDED.keywords, description = EXCLUDED.description, priority = 115, is_active = true, business_type = 'RESTAURANT', updated_at = NOW();

-- (4) 好评高频词
INSERT INTO ai_intent_configs (id, intent_code, intent_name, intent_category, tool_name, sensitivity_level, keywords, description, priority, is_active, business_type, created_at, updated_at)
VALUES (gen_random_uuid(), 'RESTAURANT_REVIEW_GOOD_TAGS', '好评高频词', 'SMARTBI', 'restaurant_review_good_tags', 'LOW',
        '["好评最多提到什么","好评高频词","顾客最满意什么","好评关键词","好评里说什么","好评提到的","顾客最认可什么","好评热词","好评最多说什么","好评里最常提到"]'::jsonb,
        '好评(>=4.5星)高频口味/品质标签 (非菜名)', 115, true, 'RESTAURANT', NOW(), NOW())
ON CONFLICT (intent_code) DO UPDATE SET tool_name = 'restaurant_review_good_tags', keywords = EXCLUDED.keywords, description = EXCLUDED.description, priority = 115, is_active = true, business_type = 'RESTAURANT', updated_at = NOW();

-- (5) 平台对比
INSERT INTO ai_intent_configs (id, intent_code, intent_name, intent_category, tool_name, sensitivity_level, keywords, description, priority, is_active, business_type, created_at, updated_at)
VALUES (gen_random_uuid(), 'RESTAURANT_REVIEW_PLATFORM', '平台评价对比', 'SMARTBI', 'restaurant_review_platform', 'LOW',
        '["各平台评价对比","平台评价","点评和美团哪个评分高","平台口碑","各渠道评价","美团点评对比","哪个平台评分高","平台评分对比","渠道口碑对比","不同平台评价"]'::jsonb,
        '各平台(点评/美团)评价量与平均星级对比', 115, true, 'RESTAURANT', NOW(), NOW())
ON CONFLICT (intent_code) DO UPDATE SET tool_name = 'restaurant_review_platform', keywords = EXCLUDED.keywords, description = EXCLUDED.description, priority = 115, is_active = true, business_type = 'RESTAURANT', updated_at = NOW();

-- (6) 评价趋势
INSERT INTO ai_intent_configs (id, intent_code, intent_name, intent_category, tool_name, sensitivity_level, keywords, description, priority, is_active, business_type, created_at, updated_at)
VALUES (gen_random_uuid(), 'RESTAURANT_REVIEW_TREND', '评价趋势', 'SMARTBI', 'restaurant_review_trend', 'LOW',
        '["评价趋势","口碑变化","评分走势","最近评价好转还是变差","评价趋势怎么样","口碑趋势","评分趋势","评价变化趋势","近期口碑","评价走势图"]'::jsonb,
        '按月聚合评价量与平均星级走势 (评价趋势)', 115, true, 'RESTAURANT', NOW(), NOW())
ON CONFLICT (intent_code) DO UPDATE SET tool_name = 'restaurant_review_trend', keywords = EXCLUDED.keywords, description = EXCLUDED.description, priority = 115, is_active = true, business_type = 'RESTAURANT', updated_at = NOW();

-- (7) 回复率
INSERT INTO ai_intent_configs (id, intent_code, intent_name, intent_category, tool_name, sensitivity_level, keywords, description, priority, is_active, business_type, created_at, updated_at)
VALUES (gen_random_uuid(), 'RESTAURANT_REVIEW_REPLY_RATE', '评价回复率', 'SMARTBI', 'restaurant_review_reply_rate', 'LOW',
        '["评价回复率","有多少评价没回复","回复及时吗","商家回复率","回复情况","未回复评价","评价回复情况","多少评价回复了","回复率怎么样","差评回复了吗"]'::jsonb,
        '商家评价回复率 (已/未回复 + 未回复差评数)', 115, true, 'RESTAURANT', NOW(), NOW())
ON CONFLICT (intent_code) DO UPDATE SET tool_name = 'restaurant_review_reply_rate', keywords = EXCLUDED.keywords, description = EXCLUDED.description, priority = 115, is_active = true, business_type = 'RESTAURANT', updated_at = NOW();
```

> **列名核对铁律**：本迁移与 V20260903_01 同表 `ai_intent_configs`，列集 (id/intent_code/intent_name/intent_category/tool_name/sensitivity_level/keywords/description/priority/is_active/business_type/created_at/updated_at) 严格对齐 V20260903_01 实际列。impl 时**先**对照 V20260903_01 字面，不增删列。

---

## 7. TDD 任务清单 (bite-sized)

每个任务遵循 superpowers TDD：先写失败测试 → 实现 → 测试通过 → commit (`safe-commit.sh` 锁定文件范围)。Python 单测用 monkeypatch 替换 `pool.acquire().fetch/fetchrow` 返回合成 row (per python-java-port.md mock pattern)；Java 单测用 Mockito mock `GoldFinanceClient` (per `RestaurantWeekdayWeekendGoldToolTest` pattern)。

> **CI 提醒** (per memory `feedback_ci_python_lint_test_does_not_run`)：CI 只跑 flake8 (gating) + `backend/python/tests/` pytest (非 gating, `|| true`)。smartbi/gold 单测**不被 CI 执行**，必须本地 server venv 手动跑 + 真库 E2E 验证。flake8 扫全目录 — 新代码必须无 lint error (尤其 E402 import 顺序)。

### Task 1 — Python review_queries 7 函数 + helper [TDD]

- **测试** `backend/python/smartbi/gold/tests/test_review_queries_deep.py` (新建)：每函数一个 test，monkeypatch `conn.fetch`/`conn.fetchrow` 返合成 row，断言返回 dict 形状 + 数值。覆盖：
  - `review_vip_tags`: 合成 VIP good/bad + 非VIP good/bad → 四桶各自 top_n 截断 + 降序。
  - `review_time_period`: 合成 5 桶 + null → periods 按 `_TIME_PERIOD_ORDER`，null_period_count 正确。
  - `review_score_tags`: dim=service → 服务标签；dim=env → 环境标签 (score_col 切换)。
  - `review_good_tags`: top_n 截断 + high_star_count。
  - `review_platform`: 按 n 降序，未标注 fallback。
  - `review_trend`: months ASC + null_period_count。
  - `review_reply_rate`: reply_rate = replied/total*100 round 1; total=0 → reply_rate=None。
- **实现** §3.1 七函数 + `_TIME_PERIOD_EXPR`/`_TIME_PERIOD_ORDER` helper 追加到 `review_queries.py`。
- **验证** `cd backend/python && python -m pytest smartbi/gold/tests/test_review_queries_deep.py -q` 全绿。
- commit: `./scripts/safe-commit.sh "feat(review): P1 7 deep review_queries + time-bucket helper" backend/python/smartbi/gold/review_queries.py backend/python/smartbi/gold/tests/test_review_queries_deep.py`

### Task 2 — Python __init__ re-export + gold_reads 7 端点 [TDD]

- **测试** `backend/python/smartbi/api/tests/test_gold_reads_review_deep.py` (新建)：用 FastAPI TestClient + monkeypatch 各 query 函数为 fake async，断言每端点 200 + 形状；`review-score-tags?dim=bad` → 400。
- **实现** §3.2 (`__init__.py` re-export + `__all__`) + §3.3 (gold_reads 7 端点 + import)。
- **验证** pytest 该文件全绿；`python -c "from smartbi.gold import review_vip_tags, review_platform, review_trend, review_reply_rate, review_score_tags, review_time_period, review_good_tags; print('ok')"`。
- commit: `safe-commit.sh "feat(review): P1 7 gold_reads endpoints + exports" backend/python/smartbi/gold/__init__.py backend/python/smartbi/api/gold_reads.py backend/python/smartbi/api/tests/test_gold_reads_review_deep.py`

### Task 3 — GoldFinanceClient 7 fetch 方法 [no new test，编译验证]

- **实现** §4.1 七方法追加 (`fetchReviewDishIssues` 之后)。
- **验证** `cd backend/java/cretas-api && mvn -q -o compile` 通过 (无需 spring-boot:run)。
- commit: `safe-commit.sh "feat(review): P1 GoldFinanceClient 7 review fetch methods" backend/java/cretas-api/src/main/java/com/cretas/aims/client/GoldFinanceClient.java`

### Task 4 — AbstractReviewGoldTool depth helper [TDD]

- **测试** `backend/java/cretas-api/src/test/java/com/cretas/aims/ai/tool/impl/restaurant/gold/review/AbstractReviewGoldToolHelperTest.java` (新建)：测一个匿名子类调 `attachDepth` → result 含/不含三字段 (empty 跳过)；`followup`/`glossary` 形状。
- **实现** §4.2 helper 追加。
- **验证** `mvn -q -o test -Dtest=AbstractReviewGoldToolHelperTest`。
- commit: `safe-commit.sh "feat(review): P1 depth helpers on AbstractReviewGoldTool" backend/java/.../review/AbstractReviewGoldTool.java backend/java/.../review/AbstractReviewGoldToolHelperTest.java`

### Task 5 — 4 个评价问题工具 (good_tags / platform / trend / reply_rate) [TDD]

- **测试** 各一个 ToolTest (Mockito mock `gold.fetchReviewX`)，断言 `format()` message 含 GROUNDTRUTH 关键数字 + chartConfig.option 非空 + suggestedFollowups/glossary 挂上 + isEmpty/emptyMessage。trend 测双轴 line option。reply_rate 测 reply_rate=98% + 未回复差评数。
- **实现** §4.3.4 / 4.3.5 / 4.3.6 / 4.3.7。
- **验证** `mvn -q -o test -Dtest='RestaurantReviewGoodTagsToolTest,RestaurantReviewPlatformToolTest,RestaurantReviewTrendToolTest,RestaurantReviewReplyRateToolTest'`。
- commit: `safe-commit.sh "feat(review): P1 4 review-question gold tools" <4 tool files> <4 test files>`

### Task 6 — 3 个跨维工具 (vip_tags / time_period / score_tags) [TDD]

- **测试** 各一个 ToolTest。score_tags 测 userInput 含「环境」→ dim=env 调用 (验 `gold.fetchReviewScoreTags(any, "env", ...)`)；不含 → service。time_period 测覆盖率注释。vip_tags 测四桶拼装 + joinTags。
- **实现** §4.3.1 / 4.3.2 / 4.3.3。
- **验证** `mvn -q -o test -Dtest='RestaurantReviewVipTagsToolTest,RestaurantReviewTimePeriodToolTest,RestaurantReviewScoreTagsToolTest'`。
- commit: `safe-commit.sh "feat(review): P1 3 within-review cross-dim gold tools" <3 tool files> <3 test files>`

### Task 7 — 8 个工具回填 suggestedFollowups/glossary/chartGuide (现存 8 评价工具 + 销售工具可选) [TDD-lite]

- **目标**：现存 8 个评价工具 (`RestaurantReviewSummaryTool` 等) `format()` 末尾加 `attachDepth(...)`，让追问 chip / 字段说明对**所有**评价答案生效 (不止新 7 个)。每个工具的 followups/glossary 内容按其语境定制 (参考新工具 attachDepth 写法)。
  - summary → followups: 差评门店/VIP评价/好评高频词/各平台对比; glossary: 星级分/服务分/环境分/口味分/好评/差评。
  - store_rank(差评门店) → followups: 哪些菜品差评多/投诉集中点/差评趋势/城市评价对比。
  - dish(差评标签) → glossary 标注「标签非菜名」; followups: 好评高频词/VIP差评点/差评门店。
  - complaint → glossary 标注「投诉类型=商家申诉小样本」。
  - city / service_score / env_score / vip → 各自相关 followups。
- **(可选) 销售 8 工具**：若工时允许，给销售 gold 工具 (RestaurantStoreRevenueRankGoldTool 等) 也加 `attachDepth`，把销售→评价交叉引导 (如「这家店评价怎么样」)。**销售工具不继承 AbstractReviewGoldTool**，需在 `GoldBackedRestaurantTool` 加同名 helper 或在各 format 内联。本任务**仅做评价 8 工具**，销售工具记 backlog (P1 不阻塞)。
- **测试**：扩展现有 review ToolTest (若无则补一个 smoke test) 断言 summary 等含 suggestedFollowups。
- **验证** `mvn -q -o test -Dtest='Restaurant*ToolTest'`。
- commit: `safe-commit.sh "feat(review): backfill depth fields on existing 8 review tools" <8 tool files> <test files>`

### Task 8 — AIQuery.vue 前端 (followup chip + glossary 块 + meta 本地解释) [TDD]

- **测试** `web-admin/src/views/smart-bi/__tests__/AIQuery.depth.spec.ts` (新建, vitest)：
  - mount AIQuery，mock `executeIntent` 返回带 `resultData.suggestedFollowups/glossary/chartGuide` → 断言渲染追问 chip + 「字段说明」toggle。
  - 点击 chip → `inputQuery` 被设为 question + handleSendMessage 触发 (mock)。
  - `tryLocalMetaAnswer`：先 push 一条带 glossary 的 assistant，再输入「服务分什么意思」→ 断言新增本地 assistant 答案且**未调用** executeIntent/chatAnalysis。
- **实现** §5.1-5.6 全部改动。
- **验证** `cd web-admin && npx vitest run src/views/smart-bi/__tests__/AIQuery.depth.spec.ts` + `npx vue-tsc --noEmit` (类型) + `npm run lint`。
- commit: `safe-commit.sh "feat(ai-chat): P1 followup chips + glossary block + local meta-answer" web-admin/src/views/smart-bi/AIQuery.vue web-admin/src/api/smartbi/intent-chat.ts web-admin/src/views/smart-bi/__tests__/AIQuery.depth.spec.ts`

> intent-chat.ts 的 `IntentExecuteResponse.resultData` 类型已是 `{data?, [key]: unknown} | null`，顶层 `[key: string]: unknown` 已容纳 suggestedFollowups/glossary/chartGuide，**无需改类型**（前端用 `as any` 局部读取，与现有 `_toolChart` 同模式）。若要更强类型可选地加可选字段，非必须。

### Task 9 — 迁移文件 [no unit test，部署验证]

- **实现** §6 `V20260904_01__restaurant_review_deep_intents.sql`。
- **验证** 本地不跑 (无 prod flyway)；部署时验 (§8)。
- commit: `safe-commit.sh "feat(review): P1 8 deep review intents migration V20260904_01" backend/java/.../db/flyway/V20260904_01__restaurant_review_deep_intents.sql`

---

## 8. 部署 / 验证

> **铁律** (per memory `feedback_worktree_main_only_deploy` + 报工 Phase A 教训)：worktree off `origin/main` 干活 → merge main → **从 main 部署 prod**。绝不从 feature 分支部署 prod。部署脚本传 jar **不重启 systemd 活跃实例 → flyway 永不跑** → 必须 `systemctl restart cretas-backend`。判 deploy 真生效靠 flyway_schema_history 版本 + 运行 jar 含修复，**绝不信「部署完成」日志**。

### 8.1 worktree

```bash
git worktree add -b feat/qhj-review-deep-p1 ../cretas-review-p1 origin/main
cd ../cretas-review-p1
cd web-admin && npm install --prefer-offline --legacy-peer-deps   # 不用 mklink /J
```

### 8.2 部署顺序 (Python 先，Java 后 — 工具调 Python 端点)

```bash
# 1) Python (gold_reads 新端点) — 先 test 后 prod
./scripts/deploy/deploy-smartbi-python.sh --env test
./scripts/deploy/deploy-smartbi-python.sh --env prod

# 2) Java (jar + flyway 迁移 + 新工具)
./scripts/deploy/deploy-backend.sh --env test
ssh root@47.100.235.168 "systemctl restart cretas-backend-test"    # ← 触发 flyway
./scripts/deploy/deploy-backend.sh --env prod
ssh root@47.100.235.168 "systemctl restart cretas-backend"         # ← 触发 flyway

# 3) web-admin (139)
git checkout main && git pull origin main   # 确认 main 已含本 PR
./scripts/deploy/deploy-web-admin.sh --env prod
```

### 8.3 后端验证 (prod 47)

```bash
# (a) flyway 版本到 20260904.01
ssh root@47.100.235.168 "PGPASSWORD=... psql -h 127.0.0.1 -U cretas_user -d cretas_prod_db -c \
  \"SELECT version, success FROM flyway_schema_history WHERE version='20260904.01';\""
# 期望: 20260904.01 | t

# (b) 8 个意图行已 active
ssh root@47.100.235.168 "PGPASSWORD=... psql -h 127.0.0.1 -U cretas_user -d cretas_prod_db -c \
  \"SELECT intent_code, tool_name, priority, is_active, business_type FROM ai_intent_configs \
    WHERE intent_code LIKE 'RESTAURANT_REVIEW_%TAGS' OR intent_code IN \
    ('RESTAURANT_REVIEW_TIME_PERIOD','RESTAURANT_REVIEW_PLATFORM','RESTAURANT_REVIEW_TREND','RESTAURANT_REVIEW_REPLY_RATE') \
    ORDER BY intent_code;\""
# 期望: 8 行, priority=115, is_active=t, business_type=RESTAURANT

# (c) Python 端点直测 (内部 secret + X-Factory-Id) — 验 GROUNDTRUTH
ssh root@47.100.235.168 "curl -s 'http://localhost:8083/api/smartbi/gold/review-platform?factory_id=RES_3101_009' \
  -H 'X-Internal-Secret: <secret>' -H 'X-Factory-Id: RES_3101_009'"
# 期望: 点评 ~19189 (4.80) / 美团 ~656 (4.57)
ssh root@47.100.235.168 "curl -s 'http://localhost:8083/api/smartbi/gold/review-reply-rate?factory_id=RES_3101_009' ..."
# 期望: replied ~19452 / not_replied ~393 / reply_rate ~98
ssh root@47.100.235.168 "curl -s 'http://localhost:8083/api/smartbi/gold/review-good-tags?factory_id=RES_3101_009' ..."
# 期望: 味道好 ~5998 / 实惠 ~1791 / 鲜嫩 ~1394
ssh root@47.100.235.168 "curl -s 'http://localhost:8083/api/smartbi/gold/review-time-period?factory_id=RES_3101_009' ..."
# 期望: 午(11-14) ~5989 (4.85) / 晚(17-21) ~6810 (4.82) / null_period_count ~5420

# (d) 运行中 jar 含新工具类 (确认部署真生效)
ssh root@47.100.235.168 "unzip -l /www/wwwroot/cretas/aims-0.0.1-SNAPSHOT.jar | grep -c RestaurantReviewPlatformTool"
# 期望: >= 1

# (e) Java 意图执行端到端 (中文 body 用 python requests, git-bash curl -d 会 GBK→400)
# 在 47 上跑 python: requests.post('http://localhost:10010/api/mobile/RES_3101_009/ai-intents/execute',
#   json={'userInput':'各平台评价对比'}, headers={JWT})
# 期望: intentCode=RESTAURANT_REVIEW_PLATFORM, status=SUCCESS, resultData.chartConfig.option 非空
```

### 8.4 前端 headed E2E (per playwright-headed-mode.md)

`PLAYWRIGHT_PORT=9224 PLAYWRIGHT_CHAT_ID=mealclaw npx playwright test ...` (headed, viewport 1920×1080, lang zh-CN)。用 qhj_prod 登录 web-admin prod 8086 (cretas_prod_db, RES_3101_009 真数据)。

验收脚本逐条问 (经营驾驶舱 AIChat / SmartBI AIQuery)：

| 问句 | 期望 |
|------|------|
| 各平台评价对比 | 点评/美团 + 饼图 + 追问 chip (整体评价总览/回复率/评价趋势/差评门店) + 「字段说明」可展开 |
| 好评最多提到什么 | 味道好/实惠/鲜嫩 + 柱图 + message 标「口味/品质标签非菜名」 |
| VIP喜欢什么口味 | VIP/非VIP 四组标签 + 柱图 |
| 哪个时段评价好 | 午/晚最高 + 覆盖率注释 (~73%) |
| 评价回复率 | 98% + 饼图 + 未回复差评数 |
| 评价趋势怎么样 | 月折线 (双轴) |
| 服务标签 | 服务标签高频词 + 柱图 |
| 环境标签 | 环境标签高频词 (dim=env 生效) |
| (点完追问 chip 后) 服务分什么意思 | **本地** glossary 答案，无 network 请求 (network tab 验 0 调用) |

截图存 audit doc + 必含 headed verification block (per rule)。

### 8.5 验证 checklist (claim 完成前必逐条亲见)

- [ ] flyway 20260904.01 success=t (亲见行)
- [ ] 8 意图 active priority=115 (亲见 8 行)
- [ ] 4 个 Python 端点 GROUNDTRUTH 对齐 (亲见数字)
- [ ] 运行 jar 含 RestaurantReviewPlatformTool (亲见 count>=1)
- [ ] Java 意图执行返 SUCCESS + chartConfig (亲见 resultData)
- [ ] headed UI: 追问 chip 可点 + 跳转新问 (亲见截图)
- [ ] headed UI: 「字段说明」展开显 glossary + chartGuide (亲见截图)
- [ ] headed UI: meta 问「X什么意思」0 network 调用 (亲见 network tab)
- [ ] 诚实标注: 菜品标签处显「口味/品质标签非菜名」、时段显覆盖率、投诉显小样本 (亲见文案)

---

## 9. 任务清单汇总 + 并行建议

### 9.1 任务清单 (9 个)

| # | 任务 | 文件 | 依赖 | 类型 |
|---|------|------|------|------|
| 1 | Python review_queries 7 函数 + time helper | review_queries.py + test | — | TDD |
| 2 | Python __init__ export + gold_reads 7 端点 | __init__.py / gold_reads.py + test | 1 | TDD |
| 3 | GoldFinanceClient 7 fetch 方法 | GoldFinanceClient.java | — | 编译 |
| 4 | AbstractReviewGoldTool depth helper | AbstractReviewGoldTool.java + test | — | TDD |
| 5 | 4 评价问题工具 (good/platform/trend/reply) | 4 tool + 4 test | 3,4 | TDD |
| 6 | 3 跨维工具 (vip_tags/time_period/score_tags) | 3 tool + 3 test | 3,4 | TDD |
| 7 | 8 现存评价工具回填 depth 字段 | 8 tool + test | 4 | TDD-lite |
| 8 | AIQuery.vue (chip + glossary + meta 本地) | AIQuery.vue + test | — (mock) | TDD |
| 9 | 迁移 V20260904_01 | flyway sql | 5,6 (tool_name 对齐) | 部署验证 |

### 9.2 并行工作建议

**Subagent 并行 (单 chat 内)** — ✅ 强推荐，三条独立链：

- **Lane A (Python)**: Task 1 → Task 2。独立目录 `backend/python/smartbi/gold|api`，无 Java 依赖。
- **Lane B (Java backend)**: Task 3 → Task 4 → (Task 5 ∥ Task 6) → Task 7 → Task 9。Task 5/6 互相独立可再并行 (不同 tool 文件)。
- **Lane C (前端)**: Task 8。完全独立 (mock executeIntent，不依赖真后端)。

Lane A / B / C 三者**无共享文件**，可同时跑。Task 9 (迁移) 的 `tool_name` 必须与 Task 5/6 的 `getToolName()` 字面一致 — 收口时核对。

**多 Chat 窗口并行** — ⚠️ 谨慎：Lane B 内 Task 5/6/7 都碰 `restaurant/gold/review/` 目录但**不同文件**，可不同 chat；但 Task 7 回填碰 8 个**现存**文件，与 Task 5/6 新建文件无重叠，安全。**冲突风险**：`GoldFinanceClient.java` (Task 3) 与 `AbstractReviewGoldTool.java` (Task 4) 各一个文件，不同 chat 改不同文件 OK。**`__init__.py` / `gold_reads.py` / `AIQuery.vue` 各只一个 task 碰**，无并发写风险。

> 用 worktree 隔离 (§8.1)。commit 用 `safe-commit.sh <files>` 锁定范围 (per concurrent-edit-safety Rule 5b)，防并发 session 文件串入。

### 9.3 收口合并

所有 lane 完成 → 单 PR (或按 lane 分 3 PR) → `git diff origin/main...HEAD --stat` 确认 scope 干净 (无 sister 文件) → merge main → 从 main 部署 (§8)。

---

## 10. 风险与缓解

| # | 风险 | 缓解 |
|---|------|------|
| R1 | **flyway 迁移不跑** (传 jar 不重启 systemd → 迁移永不 apply) | §8.2 部署后**必须** `systemctl restart cretas-backend[-test]`；§8.3(a) 验 flyway_schema_history 版本，绝不信「部署完成」日志 |
| R2 | **意图被销售/旧意图抢路由** (差评/服务/环境关键词与销售 gold 工具撞) | priority 115 > 110/90/85；强长短语关键词 (「各平台评价对比」非「平台」)；V20260903_01 已收窄 OPS_STORE_MARGIN。部署后逐条验意图命中 (§8.3 e) |
| R3 | **time_period 脏值/格式异常炸 SQL** | `_TIME_PERIOD_EXPR` 用 `!~ '^\d{4}-\d{2}-\d{2}'` 守卫，非 ISO 前缀进 NULL 桶不 cast；trend 同守卫 |
| R4 | **菜品标签被误当菜名** (违反诚实铁律) | 所有 message + glossary 显式标「口味/品质标签，非菜名」；Task 5/6 单测断言文案含此标注 |
| R5 | **score_tags dim 路由错** (问「环境」却返服务) | 工具内 `userInput.contains("环境") → env`；Task 6 单测验 env/service 双路径；迁移两意图各自关键词不含对方维度 |
| R6 | **gold 工具 source 不是 materialized_cache → 现有 chip 块不渲染** | §5.3 新增独立 chip 块键 `suggestedFollowups` (不依赖 source/templateCode)，与旧块互斥共存 |
| R7 | **缓存路径丢三字段** (CACHED 把 map stringify 进 message) | §5.2 在 JSON-parse 分支同步抓 suggestedFollowups/glossary/chartGuide |
| R8 | **smartbi 表写权限/RLS** (不适用：评价读路径只 SELECT，RLS tenant_select 已就绪) | 仅读，无 GRANT DML 风险；`_resolve_tenant` + app.factory_id RLS 已验 (PR #387 LIVE) |
| R9 | **prod 已应用版本 > 20260903.03** (并发 session 抢版本号) | 部署前查 `SELECT max(version) FROM flyway_schema_history`；若已超 20260904.01，bump 到下一可用 (filename-PK 去重无害，但版本须更大) |
| R10 | **meta 本地解释误判** (把正常业务问句当 meta) | META 正则严格 (需含「什么意思/怎么看图」等明确 meta 词)；命中 glossary 词才答，否则 return false 走正常管线 |
| R11 | **byte-parity** (本模板不参与 Java→Python parity gate) | 评价无货币、无 Java 对照端点，全 float() 输出 (per python-java-port.md 简化路径)，不适用 dict-eq gate |
| R12 | **CI 不跑 smartbi 单测** | 本地 server venv 手动跑 + 真库 E2E (§8) 为唯一验证途径；flake8 必过 (扫全目录) |

---

## 11. 验收标准 (Definition of Done)

1. 9 任务全 commit，单测全绿 (Python pytest + Java mvn test + vitest)。
2. flyway 20260904.01 success=t on prod (亲见)。
3. 8 意图 active/priority 115/business_type RESTAURANT on prod (亲见 8 行)。
4. 4 个 Python 端点 GROUNDTRUTH 数字对齐 (亲见)。
5. 运行 jar 含 7 新工具类 (亲见)。
6. headed UI: 追问 chip 可点跳转 + 字段说明可展开 + meta 问 0 network 调用 (亲见截图 + network tab)。
7. 诚实标注全到位 (菜品标签非菜名 / 时段覆盖率 / 投诉小样本)。
8. audit doc 含 headed verification block。

> **claim 完成前必亲见每条证据** (per memory `feedback_self_evidence_disqualified_cross_verify_required` + 报工 Phase A 误报 4 次教训)。不信「应该好了」，跑命令看真实输出。
