"""P3 跨数据集分析: 门店评分 (大众点评评价) × 营收 (POS gold) 关联。

per docs/superpowers/specs/2026-06-02-qhj-deep-analysis-p3-store-review-revenue.md (T5)。

诚实标注是第一约束 (spec §3 / §5):
- 仅 decided_by='admin' 或 confidence>=min_confidence 的 alias 进 join
  (低置信猜测绝不静默并入营收相关性, 否则拿错门店营收污染结论)。
- correlation 仅在 linked_count>=4 时计算 (n<4 散点无统计意义), 否则 null + 解释。
- unlinked_review_stores 必返门店名列表 (不只数字), 让老板知道哪几家没对上。
- honest_note 必带: 评分来自大众点评, 营收来自 POS, X/N linked, 未关联原因。
- 空态 (无 alias / 无评价 / 无营收) → 结构化 + next-action, 不 dead-end。

float() 即可 (本模板非 byte-parity, per python-java-port)。
"""
from __future__ import annotations

import logging
import math
from datetime import date
from typing import Any, Dict, List, Optional, Tuple

import asyncpg

logger = logging.getLogger(__name__)

# 默认门槛: admin 或 conf>=0.90 才进 join (与 store_alias_matcher.AUTO_USABLE_CONFIDENCE 对齐)。
DEFAULT_MIN_CONFIDENCE = 0.90
# correlation 最小样本: n<4 散点无统计意义。
MIN_CORRELATION_N = 4
# n 小于此值时 note 标"样本小"。
SMALL_SAMPLE_N = 8


# 评价聚合: 按评价门店名 (大众点评), 按评价ID去重 (与 review_queries._DEDUP_CTE 同口径)。
_REVIEW_AGG_SQL = """
    WITH r AS (
        SELECT DISTINCT ON (row_data->>'评价ID') row_data
          FROM smart_bi_dynamic_data
         WHERE factory_id = $1
           AND row_data ? '星级分'
           AND row_data->>'评价ID' IS NOT NULL
         ORDER BY row_data->>'评价ID'
    )
    SELECT row_data->>'评价门店'                       AS review_store_name,
           count(*)                                    AS review_count,
           round(avg((row_data->>'星级分')::numeric), 3) AS avg_rating
      FROM r
     WHERE NULLIF(row_data->>'评价门店', '') IS NOT NULL
     GROUP BY review_store_name
"""

# alias 桥: 仅 admin 或 conf>=min_confidence 且 store_id 非空 (可进 join)。
_ALIAS_SQL = """
    SELECT review_store_name, store_id, confidence, decided_by
      FROM dim_store_review_alias
     WHERE factory_id = $1
       AND store_id IS NOT NULL
       AND (decided_by = 'admin' OR confidence >= $2)
"""

# 营收: gold agg_daily by store_id (join 的右半边, 与 finance_summary.top_stores 同源)。
_REVENUE_SQL = """
    SELECT a.store_id,
           s.name                            AS gold_store_name,
           SUM(a.net_amount)::numeric(18,2)  AS revenue,
           SUM(a.bill_count)                 AS bill_count
      FROM agg_daily a
      JOIN dim_store s ON s.store_id = a.store_id
     WHERE a.factory_id = $1
       AND a.date BETWEEN $2 AND $3
     GROUP BY a.store_id, s.name
"""


def _pearson(xs: List[float], ys: List[float]) -> Optional[float]:
    """Pearson 相关系数。零方差 (常量输入) → None (无意义, 不强算)。"""
    n = len(xs)
    if n < 2 or len(ys) != n:
        return None
    mx = sum(xs) / n
    my = sum(ys) / n
    cov = sum((x - mx) * (y - my) for x, y in zip(xs, ys))
    vx = sum((x - mx) ** 2 for x in xs)
    vy = sum((y - my) ** 2 for y in ys)
    if vx <= 0 or vy <= 0:
        return None  # 零方差 → 相关系数无定义。
    return cov / math.sqrt(vx * vy)


def _build_correlation(linked: List[Dict[str, Any]]) -> Optional[Dict[str, Any]]:
    if len(linked) < MIN_CORRELATION_N:
        return None
    ratings = [x["avg_rating"] for x in linked if x["avg_rating"] is not None]
    revenues = [x["revenue"] for x in linked if x["avg_rating"] is not None]
    if len(ratings) < MIN_CORRELATION_N:
        return None
    val = _pearson(ratings, revenues)
    if val is None:
        return {
            "metric": "pearson_rating_vs_revenue",
            "value": None,
            "n": len(ratings),
            "note": "评分或营收方差为零, 相关系数无定义 (仅供参考)。",
        }
    n = len(ratings)
    if n < SMALL_SAMPLE_N:
        note = f"样本量小 (n={n}), 仅供参考"
    else:
        direction = "弱" if abs(val) < 0.3 else ("中等" if abs(val) < 0.6 else "较强")
        sign = "正" if val >= 0 else "负"
        note = f"评分与营收{direction}{sign}相关 (n={n})"
    return {
        "metric": "pearson_rating_vs_revenue",
        "value": round(val, 2),
        "n": n,
        "note": note,
    }


def _empty_state(
    factory_id: str, start: date, end: date,
    total_review_stores: int, total_gold_stores: int, reason: str,
) -> Dict[str, Any]:
    """结构化空态 + next-action (不 dead-end, per fool-proof Rule 5)。"""
    return {
        "factory_id": factory_id,
        "start_date": start.isoformat(),
        "end_date": end.isoformat(),
        "linked_stores": [],
        "linked_count": 0,
        "total_review_stores": total_review_stores,
        "total_gold_stores": total_gold_stores,
        "unlinked_review_stores": [],
        "unlinked_count": 0,
        "correlation": None,
        "honest_note": reason,
        "next_action": (
            "请先用 generate_store_review_aliases 生成门店映射候选, "
            "再用 confirm_store_alias 确认 (--list-pending → --confirm) 后重试; "
            "若无评价/营收数据请先上传大众点评评价与 POS 流水。"
        ),
    }


async def store_review_vs_revenue(
    pool: asyncpg.Pool,
    factory_id: str,
    date_range: Tuple[date, date],
    *,
    min_confidence: float = DEFAULT_MIN_CONFIDENCE,
) -> Dict[str, Any]:
    """门店评分 × 营收 关联。仅 confidence>=min_confidence 或 admin 确认的 alias 进 join。"""
    start, end = date_range
    if start is None or end is None:
        raise ValueError("store_review_vs_revenue: start/end dates are required")
    if start > end:
        raise ValueError(f"start {start} > end {end}")

    async with pool.acquire() as conn:
        review_rows = await conn.fetch(_REVIEW_AGG_SQL, factory_id)
        alias_rows = await conn.fetch(_ALIAS_SQL, factory_id, min_confidence)
        revenue_rows = await conn.fetch(_REVENUE_SQL, factory_id, start, end)

    total_review_stores = len(review_rows)
    # total_gold_stores = distinct POS 门店 (有营收的)。
    total_gold_stores = len({int(r["store_id"]) for r in revenue_rows})

    if total_review_stores == 0:
        return _empty_state(
            factory_id, start, end, 0, total_gold_stores,
            "暂无大众点评评价数据 (该工厂未上传评价门店), 无法做评分×营收关联。",
        )

    # review by name; revenue by store_id; alias name -> store_id 桥。
    review_by_name = {
        r["review_store_name"]: {
            "review_count": int(r["review_count"]),
            "avg_rating": float(r["avg_rating"]) if r["avg_rating"] is not None else None,
        }
        for r in review_rows
    }
    revenue_by_store = {
        int(r["store_id"]): {
            "gold_store_name": r["gold_store_name"],
            "revenue": float(r["revenue"]),
            "bill_count": int(r["bill_count"]),
        }
        for r in revenue_rows
    }
    alias_by_name = {
        r["review_store_name"]: {
            "store_id": int(r["store_id"]),
            "confidence": float(r["confidence"]),
            "decided_by": r["decided_by"],
        }
        for r in alias_rows
    }

    linked: List[Dict[str, Any]] = []
    unlinked: List[str] = []
    for review_name, agg in review_by_name.items():
        alias = alias_by_name.get(review_name)
        rev = revenue_by_store.get(alias["store_id"]) if alias else None
        if alias and rev is not None:
            linked.append({
                "store_id": alias["store_id"],
                "gold_store_name": rev["gold_store_name"],
                "review_store_name": review_name,
                "avg_rating": agg["avg_rating"],
                "review_count": agg["review_count"],
                "revenue": rev["revenue"],
                "bill_count": rev["bill_count"],
                "alias_confidence": alias["confidence"],
                "alias_decided_by": alias["decided_by"],
            })
        else:
            # 无 alias, 或 alias 指向的 store 在该期间无营收 → 未关联。
            unlinked.append(review_name)

    # 营收降序, 让老板先看高营收店。
    linked.sort(key=lambda x: x["revenue"], reverse=True)
    unlinked.sort()

    correlation = _build_correlation(linked)
    linked_count = len(linked)
    unlinked_count = len(unlinked)

    honest_note = (
        f"已关联 {linked_count}/{total_review_stores} 评价门店到 {total_gold_stores} 个 POS 门店; "
        f"{unlinked_count} 家评价门店无 POS 营收对应 "
        f"(含外卖卫星店/鲜行者品牌等, 不计入相关性)。"
        f"评分来自大众点评导出, 营收来自 POS 流水, 二者为同店不同来源; "
        f"相关性不等于因果 (高分高营收可能均由地段驱动)。"
    )
    if linked_count == 0:
        honest_note = (
            f"评价门店 {total_review_stores} 家, POS 营收门店 {total_gold_stores} 家, "
            f"但 0 家已确认映射 (评价名与 POS 名 0 精确匹配)。"
            f"请先在确认队列确认门店映射后再做关联分析。"
        )

    result = {
        "factory_id": factory_id,
        "start_date": start.isoformat(),
        "end_date": end.isoformat(),
        "linked_stores": linked,
        "linked_count": linked_count,
        "total_review_stores": total_review_stores,
        "total_gold_stores": total_gold_stores,
        "unlinked_review_stores": unlinked,
        "unlinked_count": unlinked_count,
        "correlation": correlation,
        "honest_note": honest_note,
    }
    if linked_count == 0:
        result["next_action"] = (
            "用 confirm_store_alias --list-pending 查看待确认映射, "
            "--confirm <评价门店名> --store-id <id> 确认后重试。"
        )
    return result
