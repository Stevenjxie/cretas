"""Review-analytics read queries — 大众点评 review data aggregations.

Unlike the rest of ``smartbi/gold/queries.py`` (which reads materialized
``agg_*`` Gold tables), review data for restaurant tenants has NOT been
promoted into ``dim_review_summary`` / ``fact_review_event`` (those tables
are empty for qhj / RES_3101_009). The canonical review data lives in the
raw ``smart_bi_dynamic_data`` table as JSONB ``row_data``, ingested from
大众点评 '评价下载' Excel exports.

Re-upload dedup
---------------
The same 评价下载 file is uploaded multiple times (one per refresh), so the
raw table holds 3-4x duplicate rows per review. Every query here dedups by
the stable ``评价ID`` field via ``DISTINCT ON`` — counts/averages are over
UNIQUE reviews, not raw rows.

Tenant scope
------------
``smart_bi_dynamic_data`` has RLS ``tenant_select`` keyed on
``app.factory_id``, which the asyncpg pool sets per-connection from the JWT
tenant context (``smartbi.tenant_ctx``). The explicit ``factory_id = $1``
WHERE is belt-and-suspenders, matching the rest of the Gold read layer.

Honesty notes (per fool-proof-design + relabel lessons)
-------------------------------------------------------
- ``菜品标签`` is a FLAVOR/QUALITY tag list (鲜美/劲道/太软了), NOT dish
  names. ``review_dish_issues()`` reports high-frequency tags in low-star
  reviews; the route/tool labels them honestly as 口味/品质标签, never as
  "差评菜品".
- ``投诉类型`` is the MERCHANT's review-dispute category (商家申诉), a small
  sample (~tens). ``review_complaints()`` labels it honestly and also
  surfaces the real 低星(<=3星) review count as the primary 差评 signal.
"""
from __future__ import annotations

import logging
from decimal import Decimal
from typing import Any, Dict, Optional

import asyncpg

logger = logging.getLogger(__name__)

# Deduped unique-review CTE. ``DISTINCT ON`` requires the ORDER BY to lead
# with the distinct expression. $1 is always factory_id.
_DEDUP_CTE = """
    WITH r AS (
        SELECT DISTINCT ON (row_data->>'评价ID') row_data
          FROM smart_bi_dynamic_data
         WHERE factory_id = $1
           AND row_data ? '星级分'
           AND row_data->>'评价ID' IS NOT NULL
         ORDER BY row_data->>'评价ID'
    )
"""

# Whitelist for the store-ranking sort dimension → aggregate column alias.
# Keeps raw user input out of the interpolated ORDER BY.
_STORE_DIM_EXPR = {
    "star": "avg_star",
    "service": "avg_service",
    "env": "avg_env",
    "low_star": "low_star_count",
}

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


def _f(v: Any) -> Optional[float]:
    """Decimal/None → float/None for JSON serialization."""
    if v is None:
        return None
    if isinstance(v, Decimal):
        return float(v)
    return float(v)


# ---------------------------------------------------------------------------
# Source-existence probe
# ---------------------------------------------------------------------------

_EXISTENCE_SQL = """
    SELECT count(*) AS n
      FROM smart_bi_dynamic_data
     WHERE factory_id = $1
       AND row_data ? '星级分'
       AND row_data->>'评价ID' IS NOT NULL
     LIMIT 1
"""


async def check_review_data_exists(pool: asyncpg.Pool, factory_id: str) -> bool:
    """Return True if the review source table has ANY rows for this tenant.

    Used by every query function to populate the ``connected`` flag:
    - connected=False  → source table empty for this tenant (未接入, upload needed)
    - connected=True   → data present; individual arrays may still be empty
                         when filters produce no results (正常空, not 未接入).
    """
    async with pool.acquire() as conn:
        row = await conn.fetchrow(_EXISTENCE_SQL, factory_id)
    return row is not None and int(row["n"]) > 0


async def review_summary(pool: asyncpg.Pool, factory_id: str) -> Dict[str, Any]:
    """Overall review KPIs: average 星级/服务/环境/口味 scores, totals,
    VIP count, low/high-star counts, distinct store/city counts.

    Feeds the "客户评价怎么样" question. ``dimension_scores`` is a ready-made
    list for a 4-bar comparison chart (all on the same 5-point scale).

    ``connected`` flag:
      False → no review source rows for this tenant (未接入，需上传点评数据)
      True  → source data present (total_reviews may still be 0 if all rows
               were filtered out, but that is "正常空" not "未接入").
    """
    sql = _DEDUP_CTE + """
        SELECT
            count(*)                                                       AS total_reviews,
            round(avg((row_data->>'星级分')::numeric), 3)                  AS avg_star,
            round(avg(NULLIF(row_data->>'服务分', '')::numeric), 3)        AS avg_service,
            round(avg(NULLIF(row_data->>'环境分', '')::numeric), 3)        AS avg_env,
            round(avg(NULLIF(row_data->>'口味分', '')::numeric), 3)        AS avg_taste,
            count(*) FILTER (WHERE (row_data->>'星级分')::numeric <= 3)    AS low_star_count,
            count(*) FILTER (WHERE (row_data->>'星级分')::numeric >= 4.5)  AS high_star_count,
            count(*) FILTER (WHERE row_data->>'是否vip' = '是')            AS vip_count,
            count(DISTINCT row_data->>'评价门店')                          AS store_count,
            count(DISTINCT row_data->>'城市')                             AS city_count
          FROM r
    """
    async with pool.acquire() as conn:
        row = await conn.fetchrow(sql, factory_id)
    total = int(row["total_reviews"]) if row is not None else 0
    connected = total > 0
    if not connected:
        return {
            "factory_id": factory_id,
            "connected": False,
            "total_reviews": 0,
            "dimension_scores": [],
        }
    dims = []
    for name, key in (("星级", "avg_star"), ("服务", "avg_service"),
                      ("环境", "avg_env"), ("口味", "avg_taste")):
        val = _f(row[key])
        if val is not None:
            dims.append({"name": name, "value": val})
    return {
        "factory_id": factory_id,
        "connected": True,
        "total_reviews": total,
        "avg_star": _f(row["avg_star"]),
        "avg_service": _f(row["avg_service"]),
        "avg_env": _f(row["avg_env"]),
        "avg_taste": _f(row["avg_taste"]),
        "low_star_count": int(row["low_star_count"]),
        "high_star_count": int(row["high_star_count"]),
        "vip_count": int(row["vip_count"]),
        "store_count": int(row["store_count"]),
        "city_count": int(row["city_count"]),
        "dimension_scores": dims,
    }


async def review_store_ranking(
    pool: asyncpg.Pool,
    factory_id: str,
    *,
    dim: str = "low_star",
    order: str = "desc",
    top_n: int = 10,
    min_reviews: int = 20,
) -> Dict[str, Any]:
    """Per-store review aggregates, sorted by ``dim``.

    Serves three questions via one query:
      - dim=low_star order=desc → 差评最多的门店 (most <=3星 reviews)
      - dim=service order=desc  → 服务分排名
      - dim=env     order=desc  → 环境分对比

    ``min_reviews`` filters out tiny stores whose averages are noise."""
    metric = _STORE_DIM_EXPR.get(dim, "low_star_count")
    direction = "ASC" if str(order).lower() == "asc" else "DESC"
    sql = _DEDUP_CTE + f"""
        SELECT
            row_data->>'评价门店'                                          AS store,
            count(*)                                                       AS n,
            round(avg((row_data->>'星级分')::numeric), 3)                  AS avg_star,
            round(avg(NULLIF(row_data->>'服务分', '')::numeric), 3)        AS avg_service,
            round(avg(NULLIF(row_data->>'环境分', '')::numeric), 3)        AS avg_env,
            round(avg(NULLIF(row_data->>'口味分', '')::numeric), 3)        AS avg_taste,
            count(*) FILTER (WHERE (row_data->>'星级分')::numeric <= 3)    AS low_star_count
          FROM r
         WHERE NULLIF(row_data->>'评价门店', '') IS NOT NULL
         GROUP BY store
        HAVING count(*) >= $2
         ORDER BY {metric} {direction}
         LIMIT $3
    """
    connected = await check_review_data_exists(pool, factory_id)
    if not connected:
        return {
            "factory_id": factory_id,
            "connected": False,
            "dim": dim,
            "order": direction.lower(),
            "stores": [],
        }
    async with pool.acquire() as conn:
        rows = await conn.fetch(sql, factory_id, int(min_reviews), int(top_n))
    stores = [
        {
            "store": row["store"],
            "review_count": int(row["n"]),
            "avg_star": _f(row["avg_star"]),
            "avg_service": _f(row["avg_service"]),
            "avg_env": _f(row["avg_env"]),
            "avg_taste": _f(row["avg_taste"]),
            "low_star_count": int(row["low_star_count"]),
        }
        for row in rows
    ]
    return {
        "factory_id": factory_id,
        "connected": True,
        "dim": dim,
        "order": direction.lower(),
        "stores": stores,
    }


async def review_city_ranking(pool: asyncpg.Pool, factory_id: str) -> Dict[str, Any]:
    """Per-city review averages, lowest avg-star first (哪个城市评价最低)."""
    sql = _DEDUP_CTE + """
        SELECT row_data->>'城市'                                          AS city,
               count(*)                                                   AS n,
               round(avg((row_data->>'星级分')::numeric), 3)              AS avg_star,
               round(avg(NULLIF(row_data->>'服务分', '')::numeric), 3)    AS avg_service,
               round(avg(NULLIF(row_data->>'环境分', '')::numeric), 3)    AS avg_env
          FROM r
         WHERE NULLIF(row_data->>'城市', '') IS NOT NULL
         GROUP BY city
        HAVING count(*) >= 10
         ORDER BY avg_star ASC
    """
    connected = await check_review_data_exists(pool, factory_id)
    if not connected:
        return {"factory_id": factory_id, "connected": False, "cities": []}
    async with pool.acquire() as conn:
        rows = await conn.fetch(sql, factory_id)
    cities = [
        {
            "city": row["city"],
            "review_count": int(row["n"]),
            "avg_star": _f(row["avg_star"]),
            "avg_service": _f(row["avg_service"]),
            "avg_env": _f(row["avg_env"]),
        }
        for row in rows
    ]
    return {"factory_id": factory_id, "connected": True, "cities": cities}


async def review_vip(pool: asyncpg.Pool, factory_id: str) -> Dict[str, Any]:
    """VIP vs 非VIP review comparison (count + average scores)."""
    sql = _DEDUP_CTE + """
        SELECT CASE WHEN row_data->>'是否vip' = '是' THEN 'VIP' ELSE '非VIP' END AS grp,
               count(*)                                                   AS n,
               round(avg((row_data->>'星级分')::numeric), 3)              AS avg_star,
               round(avg(NULLIF(row_data->>'服务分', '')::numeric), 3)    AS avg_service,
               round(avg(NULLIF(row_data->>'环境分', '')::numeric), 3)    AS avg_env
          FROM r
         GROUP BY grp
    """
    connected = await check_review_data_exists(pool, factory_id)
    if not connected:
        return {"factory_id": factory_id, "connected": False, "groups": []}
    async with pool.acquire() as conn:
        rows = await conn.fetch(sql, factory_id)
    groups = [
        {
            "group": row["grp"],
            "review_count": int(row["n"]),
            "avg_star": _f(row["avg_star"]),
            "avg_service": _f(row["avg_service"]),
            "avg_env": _f(row["avg_env"]),
        }
        for row in rows
    ]
    # VIP first for a stable presentation order.
    groups.sort(key=lambda g: 0 if g["group"] == "VIP" else 1)
    return {"factory_id": factory_id, "connected": True, "groups": groups}


async def review_complaints(
    pool: asyncpg.Pool, factory_id: str, *, top_n: int = 8
) -> Dict[str, Any]:
    """Merchant review-dispute (商家申诉) categories by frequency, PLUS the
    real 低星(<=3星) review count as the primary 差评 signal.

    ``投诉类型`` is the merchant's reason for disputing a review, not a
    customer complaint — the tool labels it honestly. The category is the
    text before the first '-' separator."""
    cat_sql = _DEDUP_CTE + """
        , c AS (
            SELECT split_part(row_data->>'投诉类型', '-', 1) AS category
              FROM r
             WHERE NULLIF(row_data->>'投诉类型', '') IS NOT NULL
        )
        SELECT category, count(*) AS n
          FROM c
         GROUP BY category
         ORDER BY n DESC
         LIMIT $2
    """
    summ_sql = _DEDUP_CTE + """
        SELECT count(*) FILTER (WHERE (row_data->>'星级分')::numeric <= 3)        AS low_star_count,
               count(*) FILTER (WHERE NULLIF(row_data->>'投诉类型', '') IS NOT NULL) AS dispute_count
          FROM r
    """
    connected = await check_review_data_exists(pool, factory_id)
    if not connected:
        return {
            "factory_id": factory_id,
            "connected": False,
            "categories": [],
            "dispute_count": 0,
            "low_star_count": 0,
        }
    async with pool.acquire() as conn:
        rows = await conn.fetch(cat_sql, factory_id, int(top_n))
        summ = await conn.fetchrow(summ_sql, factory_id)
    categories = [{"category": row["category"], "count": int(row["n"])} for row in rows]
    return {
        "factory_id": factory_id,
        "connected": True,
        "categories": categories,
        "dispute_count": int(summ["dispute_count"]) if summ else 0,
        "low_star_count": int(summ["low_star_count"]) if summ else 0,
    }


async def review_dish_issues(
    pool: asyncpg.Pool,
    factory_id: str,
    *,
    top_n: int = 10,
    star_threshold: int = 3,
) -> Dict[str, Any]:
    """High-frequency 菜品标签 in low-star (<=star_threshold) reviews.

    NOTE: ``菜品标签`` are FLAVOR/QUALITY tags (鲜美/劲道/太软了), not dish
    names — the tool labels them honestly as 口味/品质标签. Tags are
    comma/、/／-separated; split and counted individually."""
    tag_sql = _DEDUP_CTE + """
        , low AS (
            SELECT row_data
              FROM r
             WHERE (row_data->>'星级分')::numeric <= $2
               AND NULLIF(row_data->>'菜品标签', '') IS NOT NULL
        ),
        tags AS (
            SELECT trim(t) AS tag
              FROM low,
                   LATERAL regexp_split_to_table(low.row_data->>'菜品标签', '[,，、/]') AS t
        )
        SELECT tag, count(*) AS n
          FROM tags
         WHERE trim(tag) <> ''
         GROUP BY tag
         ORDER BY n DESC
         LIMIT $3
    """
    cnt_sql = _DEDUP_CTE + """
        SELECT count(*) FILTER (WHERE (row_data->>'星级分')::numeric <= $2)  AS low_star_count,
               count(*) FILTER (WHERE (row_data->>'星级分')::numeric <= $2
                                AND NULLIF(row_data->>'菜品标签', '') IS NOT NULL) AS low_with_tag
          FROM r
    """
    connected = await check_review_data_exists(pool, factory_id)
    if not connected:
        return {
            "factory_id": factory_id,
            "connected": False,
            "star_threshold": int(star_threshold),
            "low_star_count": 0,
            "low_with_tag": 0,
            "tags": [],
        }
    async with pool.acquire() as conn:
        rows = await conn.fetch(tag_sql, factory_id, int(star_threshold), int(top_n))
        cnt = await conn.fetchrow(cnt_sql, factory_id, int(star_threshold))
    tags = [{"tag": row["tag"], "count": int(row["n"])} for row in rows]
    return {
        "factory_id": factory_id,
        "connected": True,
        "star_threshold": int(star_threshold),
        "low_star_count": int(cnt["low_star_count"]) if cnt else 0,
        "low_with_tag": int(cnt["low_with_tag"]) if cnt else 0,
        "tags": tags,
    }


# =============================================================================
# P1 conversational-depth queries (2026-06-02) — within-review cross-dim +
# more review questions. All reuse _DEDUP_CTE / _f(); all output float().
# =============================================================================


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
    connected = await check_review_data_exists(pool, factory_id)
    if not connected:
        return {
            "factory_id": factory_id,
            "connected": False,
            "vip_good_tags": [],
            "vip_bad_tags": [],
            "normal_good_tags": [],
            "normal_bad_tags": [],
        }
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
        "connected": True,
        "vip_good_tags": buckets["VIP_good"],
        "vip_bad_tags": buckets["VIP_bad"],
        "normal_good_tags": buckets["非VIP_good"],
        "normal_bad_tags": buckets["非VIP_bad"],
    }


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
    connected = await check_review_data_exists(pool, factory_id)
    if not connected:
        return {
            "factory_id": factory_id,
            "connected": False,
            "periods": [],
            "null_period_count": 0,
            "total_reviews": 0,
        }
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
        "connected": True,
        "periods": periods,
        "null_period_count": int(cnt["null_count"]) if cnt else 0,
        "total_reviews": int(cnt["total"]) if cnt else 0,
    }


async def review_score_tags(
    pool: asyncpg.Pool, factory_id: str, *, dim: str = "service", top_n: int = 10
) -> Dict[str, Any]:
    """服务/环境标签×评分 高频词 + 该维度平均分。

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
    connected = await check_review_data_exists(pool, factory_id)
    if not connected:
        return {
            "factory_id": factory_id,
            "connected": False,
            "dim": dim,
            "score_col": score_col,
            "tags": [],
        }
    async with pool.acquire() as conn:
        rows = await conn.fetch(sql, factory_id, int(top_n))
    tags = [
        {"tag": row["tag"], "count": int(row["n"]), "avg_score": _f(row["avg_score"])}
        for row in rows
    ]
    return {
        "factory_id": factory_id,
        "connected": True,
        "dim": dim,
        "score_col": score_col,
        "tags": tags,
    }


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
    connected = await check_review_data_exists(pool, factory_id)
    if not connected:
        return {
            "factory_id": factory_id,
            "connected": False,
            "high_star_count": 0,
            "tags": [],
        }
    async with pool.acquire() as conn:
        rows = await conn.fetch(tag_sql, factory_id, int(top_n))
        cnt = await conn.fetchrow(cnt_sql, factory_id)
    tags = [{"tag": row["tag"], "count": int(row["n"])} for row in rows]
    return {
        "factory_id": factory_id,
        "connected": True,
        "high_star_count": int(cnt["high_star_count"]) if cnt else 0,
        "tags": tags,
    }


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
    connected = await check_review_data_exists(pool, factory_id)
    if not connected:
        return {"factory_id": factory_id, "connected": False, "platforms": []}
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
    return {"factory_id": factory_id, "connected": True, "platforms": platforms}


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
    connected = await check_review_data_exists(pool, factory_id)
    if not connected:
        return {
            "factory_id": factory_id,
            "connected": False,
            "months": [],
            "null_period_count": 0,
        }
    async with pool.acquire() as conn:
        rows = await conn.fetch(sql, factory_id)
        cnt = await conn.fetchrow(null_sql, factory_id)
    months = [
        {"month": row["month"], "review_count": int(row["n"]), "avg_star": _f(row["avg_star"])}
        for row in rows
    ]
    return {
        "factory_id": factory_id,
        "connected": True,
        "months": months,
        "null_period_count": int(cnt["null_count"]) if cnt else 0,
    }


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
    connected = await check_review_data_exists(pool, factory_id)
    if not connected:
        return {
            "factory_id": factory_id,
            "connected": False,
            "replied": 0,
            "not_replied": 0,
            "not_replied_low_star": 0,
            "total_with_status": 0,
            "reply_rate": None,
        }
    async with pool.acquire() as conn:
        row = await conn.fetchrow(sql, factory_id)
    if row is None or int(row["total_with_status"]) == 0:
        return {
            "factory_id": factory_id,
            "connected": True,
            "total_with_status": 0,
            "replied": 0,
            "not_replied": 0,
            "not_replied_low_star": 0,
            "reply_rate": None,
        }
    replied = int(row["replied"])
    total = int(row["total_with_status"])
    reply_rate = round(replied / total * 100, 1) if total > 0 else None
    return {
        "factory_id": factory_id,
        "connected": True,
        "replied": replied,
        "not_replied": int(row["not_replied"]),
        "not_replied_low_star": int(row["not_replied_low_star"]),
        "total_with_status": total,
        "reply_rate": reply_rate,
    }
