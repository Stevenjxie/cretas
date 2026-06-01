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


def _f(v: Any) -> Optional[float]:
    """Decimal/None → float/None for JSON serialization."""
    if v is None:
        return None
    if isinstance(v, Decimal):
        return float(v)
    return float(v)


async def review_summary(pool: asyncpg.Pool, factory_id: str) -> Dict[str, Any]:
    """Overall review KPIs: average 星级/服务/环境/口味 scores, totals,
    VIP count, low/high-star counts, distinct store/city counts.

    Feeds the "客户评价怎么样" question. ``dimension_scores`` is a ready-made
    list for a 4-bar comparison chart (all on the same 5-point scale)."""
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
    if row is None or int(row["total_reviews"]) == 0:
        return {"factory_id": factory_id, "total_reviews": 0, "dimension_scores": []}
    dims = []
    for name, key in (("星级", "avg_star"), ("服务", "avg_service"),
                      ("环境", "avg_env"), ("口味", "avg_taste")):
        val = _f(row[key])
        if val is not None:
            dims.append({"name": name, "value": val})
    return {
        "factory_id": factory_id,
        "total_reviews": int(row["total_reviews"]),
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
    return {"factory_id": factory_id, "cities": cities}


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
    return {"factory_id": factory_id, "groups": groups}


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
    async with pool.acquire() as conn:
        rows = await conn.fetch(cat_sql, factory_id, int(top_n))
        summ = await conn.fetchrow(summ_sql, factory_id)
    categories = [{"category": row["category"], "count": int(row["n"])} for row in rows]
    return {
        "factory_id": factory_id,
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
    async with pool.acquire() as conn:
        rows = await conn.fetch(tag_sql, factory_id, int(star_threshold), int(top_n))
        cnt = await conn.fetchrow(cnt_sql, factory_id, int(star_threshold))
    tags = [{"tag": row["tag"], "count": int(row["n"])} for row in rows]
    return {
        "factory_id": factory_id,
        "star_threshold": int(star_threshold),
        "low_star_count": int(cnt["low_star_count"]) if cnt else 0,
        "low_with_tag": int(cnt["low_with_tag"]) if cnt else 0,
        "tags": tags,
    }
