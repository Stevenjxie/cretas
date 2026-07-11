"""Gold read-path queries — downstream modules hit these instead of
recomputing aggregates on every request.

Week 4 Phase B v0 of Unified Data Layer v1 spec (§5).

Scope today
-----------
One query shape per downstream module that will eventually cut over to
Gold. Started with `finance_summary` (the spec's §5 pilot). The actual
Java cutover touches the existing FinanceAnalysisService and is deferred
to a future session — today's deliverable is the Python primitive that
the cutover will use (Python service) or replicate (Java JDBC).

All queries
-----------
- Accept `factory_id` + a date range
- Assume tenant_ctx is set so RLS on agg_* enforces tenant scope (the
  `factory_id` arg is belt-and-suspenders — it's also in the WHERE)
- Return plain dicts (not dataclasses) so they're JSON-serializable
  directly via FastAPI

Why not pydantic models
-----------------------
Keeping this layer dict-based because the shape will evolve rapidly
(v1.1 pilot → v1.2 more modules → v1.3 review adapters) and pydantic
versioning churn would create friction. The FastAPI route layer will
wrap these in response_model schemas as we lock shapes down.
"""
from __future__ import annotations

import logging
import json
from datetime import date
from decimal import ROUND_HALF_UP, Decimal
from typing import Any, Dict, Optional, Tuple

import asyncpg

logger = logging.getLogger(__name__)


def _validate_range(start: Optional[date], end: Optional[date]) -> None:
    """Guard a (start, end) range.

    WS1: dates are optional — a None bound means "open" (= all history on
    that side). The only invalid case is BOTH bounds present AND inverted
    (start > end).
    """
    if start is not None and end is not None and start > end:
        raise ValueError(f"start {start} > end {end}")


# Wrapping bracket pairs we strip for display. Each tuple is (open, close);
# we only strip when the WHOLE name is wrapped by a matching pair.
_BRACKET_PAIRS = (
    ("[", "]"),
    ("【", "】"),
    ("（", "）"),
    ("(", ")"),
)


def _clean_display_name(name):
    """Strip a single matched WRAPPING bracket pair from a display name.

    qhj's POS export wraps payment-channel / coupon / discount names in
    brackets (e.g. ``[微信]``, ``[饿了么]``, ``[美团套餐券]``) — an export
    artifact that surfaces in the dashboard 渠道占比, sales 渠道明细 and the
    AI 洞察/FactBook looking like markup. This normalizes them for display.

    Conservative — only strips when the ENTIRE name is wrapped: it starts
    with an open bracket AND ends with its matching close bracket. Names
    with mid-string or unmatched brackets are returned unchanged:
      ``[微信]``                       → ``微信``
      ``[美团套餐券]``                  → ``美团套餐券``
      ``现金`` / ``招行买单`` / ``银行卡`` → unchanged (no wrapping pair)
      ``[微信]余额``                    → unchanged (close bracket not at end)
      ``招牌青花椒鱼(微麻微辣)[小份]``    → unchanged (not fully wrapped)

    Applied to the NAME field in the RESULT (post-fetch, after GROUP BY) so
    grouping/aggregation is unaffected — only the displayed name is cleaned.
    Non-str / empty / None inputs are returned as-is (safe).
    """
    if not isinstance(name, str):
        return name
    s = name.strip()
    if len(s) < 2:
        return name
    for open_b, close_b in _BRACKET_PAIRS:
        if s.startswith(open_b) and s.endswith(close_b):
            inner = s[len(open_b):len(s) - len(close_b)]
            # Guard against over-stripping: the inner text must not itself
            # contain an unbalanced occurrence of THIS pair's close bracket,
            # which would mean the leading open didn't wrap the whole name
            # (e.g. "[a]b]" — close at end but a mid-string close exists).
            if close_b in inner:
                return name
            return inner
    return name


def _median(values):
    """Single-value median of a numeric iterable.

    Sorts ascending and returns the lower-middle element (``sorted[(n-1)//2]``)
    so the median is itself one of the observed values — a stable threshold for
    quadrant / weak-store classification rather than an interpolated average.
    Empty input → 0 (honest neutral threshold; callers treat an empty dataset
    as "nothing above/below median").
    """
    vals = sorted(values)
    n = len(vals)
    if n == 0:
        return 0
    return vals[(n - 1) // 2]


async def daily_trend(
    pool: asyncpg.Pool,
    factory_id: str,
    date_range: Tuple[Optional[date], Optional[date]],
) -> Dict[str, Any]:
    """Daily revenue/bill-count trend — feeds 分析概览 trend line chart.

    Returns dict with:
      - `factory_id`, `start_date`, `end_date` — echoes input
      - `points` — list of {date, revenue, bill_count, avg_bill_value},
        one per date that has any activity, ordered ascending. Missing
        dates are omitted (caller fills with zeros in the FE if needed).
    """
    start, end = date_range
    _validate_range(start, end)
    conds = ["factory_id = $1"]
    params: list = [factory_id]
    if start is not None:
        params.append(start)
        conds.append(f"date >= ${len(params)}")
    if end is not None:
        params.append(end)
        conds.append(f"date <= ${len(params)}")
    where = " AND ".join(conds)
    async with pool.acquire() as conn:
        rows = await conn.fetch(
            f"""
            SELECT date,
                   SUM(net_amount)::numeric(18,2) AS revenue,
                   SUM(bill_count)                AS bill_count
              FROM agg_daily
             WHERE {where}
             GROUP BY date
             ORDER BY date
            """,
            *params,
        )
    points = []
    for r in rows:
        rev = Decimal(r["revenue"])
        bc = int(r["bill_count"])
        avg = float((rev / bc).quantize(Decimal("0.01"))) if bc > 0 else None
        points.append({
            "date": r["date"].isoformat(),
            "revenue": float(rev),
            "bill_count": bc,
            "avg_bill_value": avg,
        })
    return {
        "factory_id": factory_id,
        "start_date": start.isoformat() if start is not None else None,
        "end_date": end.isoformat() if end is not None else None,
        "points": points,
    }


async def top_products(
    pool: asyncpg.Pool,
    factory_id: str,
    date_range: Tuple[Optional[date], Optional[date]],
    *,
    top_n: int = 10,
    order: str = "desc",
) -> Dict[str, Any]:
    """Top products by revenue over the date range — feeds 分析概览 pie
    chart + KPI看板 top seller card.

    Note: agg_product is monthly-grained. We roll up month buckets whose
    FIRST-of-month falls within date_range — so a range '2025-04-15 to
    2025-05-10' covers April + May fully, not fractional. This matches
    how the FE uses "period=month" selectors.

    Day 24-25 (Sub-Project C POC): LEFT JOIN field_provenance to expose
    cell-level lineage for the `revenue` field. When provenance is empty
    (prod-OFF state — SMARTBI_ENABLE_PROVENANCE=0) the JOIN returns NULL
    and the row carries confidence=None / source=None. When populated, the
    JOIN picks the highest-confidence active (non-superseded) row matching
    EITHER the bare ``revenue`` field_name OR the per-store-suffixed
    ``revenue@store_<id>`` form (per ProductSummaryWriter Phase B C1
    encoding). LATERAL keeps it 1:1 per group even with multi-store
    provenance fan-out.

    P4b-safe (canonical-dish-aware grouping): LEFT JOIN dim_canonical_dish
    on dim_product.canonical_dish_id. The group key is the canonical dish
    when a product has been human-confirmed-merged (canonical_dish_id NOT
    NULL and the canonical row exists), else the product itself —
    expressed as ``COALESCE('c'||canonical_dish_id, 'p'||product_id)``.
    A confirmed canonical therefore sums qty/revenue/bill across all its
    member products into one ranked row, displayed under canonical_name.

    SAFETY — identical-to-pre-P4b when nothing is merged: any product with
    canonical_dish_id NULL (the prod-default; only human confirmation sets
    it) groups by itself, is the sole member, and yields the same name and
    same sums as the per-product query. dim_canonical_dish empty / no rows
    for a factory likewise leaves every canonical NULL → every product
    groups by itself. The representative product_id (MIN over the group)
    drives an identical per-rep provenance LATERAL, so for a single-member
    group the rep IS the product and provenance is byte-identical to today.
    """
    start, end = date_range
    _validate_range(start, end)
    # Whitelist the order direction to avoid interpolating raw user input into SQL.
    direction = "ASC" if str(order).lower() == "asc" else "DESC"
    # agg_product.month is always first-of-month; pick months where
    # first-of-month ≤ end AND month ≥ first-of-start-month. A None bound
    # means no filter on that side (= all history).
    start_m = start.replace(day=1) if start is not None else None
    end_m = end.replace(day=1) if end is not None else None
    # Build the date filter conditionally; remaining params (top_n) are
    # appended AFTER so $N renumbers correctly.
    month_conds = ["a.factory_id = $1"]
    params: list = [factory_id]
    if start_m is not None:
        params.append(start_m)
        month_conds.append(f"a.month >= ${len(params)}")
    if end_m is not None:
        params.append(end_m)
        month_conds.append(f"a.month <= ${len(params)}")
    month_where = " AND ".join(month_conds)
    params.append(int(top_n))
    limit_ph = f"${len(params)}"
    async with pool.acquire() as conn:
        rows = await conn.fetch(
            f"""
            WITH grouped AS (
                SELECT
                    -- Stable group identity: canonical when confirmed-merged,
                    -- else the product itself. COALESCE means a NULL
                    -- canonical_dish_id falls back to per-product grouping,
                    -- preserving exactly the pre-P4b behavior.
                    COALESCE('c' || p.canonical_dish_id::text,
                             'p' || p.product_id::text)  AS group_key,
                    -- Representative product_id for the group. For a single-
                    -- member (NULL-canonical) group this equals the product's
                    -- own id, so downstream shape + provenance are unchanged.
                    MIN(p.product_id)                    AS rep_product_id,
                    -- Display name: canonical name when merged, else product
                    -- name. For NULL-canonical groups cd.* is all-NULL so this
                    -- is just p.name (identical to today).
                    COALESCE(cd.canonical_name, p.name)  AS display_name,
                    SUM(a.qty_sold)::numeric(18,3)       AS qty,
                    SUM(a.revenue)::numeric(18,2)        AS revenue,
                    SUM(a.bill_count)                    AS bill_count
                  FROM agg_product a
                  JOIN dim_product p ON p.product_id = a.product_id
                  LEFT JOIN dim_canonical_dish cd
                         ON cd.canonical_dish_id = p.canonical_dish_id
                 WHERE {month_where}
                 GROUP BY COALESCE('c' || p.canonical_dish_id::text,
                                   'p' || p.product_id::text),
                          COALESCE(cd.canonical_name, p.name)
                HAVING SUM(a.revenue) > 0
            )
            SELECT g.rep_product_id              AS product_id,
                   g.display_name                AS name,
                   g.qty                          AS qty,
                   g.revenue                      AS revenue,
                   g.bill_count                   AS bill_count,
                   fp.confidence                  AS confidence,
                   fp.source_type                 AS source,
                   fp.source_upload_id            AS source_upload_id,
                   fp.field_name                  AS prov_field_name
              FROM grouped g
              LEFT JOIN LATERAL (
                  -- Provenance driven off the group's representative product.
                  -- For single-member groups the rep is the product itself, so
                  -- this is identical to the pre-P4b per-product lookup. For
                  -- merged groups it returns the representative's lineage
                  -- (NULL-safe — empty field_provenance ⇒ all columns NULL).
                  SELECT fp_inner.confidence,
                         fp_inner.source_type,
                         fp_inner.source_upload_id,
                         fp_inner.field_name
                    FROM field_provenance fp_inner
                   WHERE fp_inner.factory_id  = $1
                     AND fp_inner.entity_type = 'product'
                     AND fp_inner.entity_id   = g.rep_product_id
                     AND (fp_inner.field_name = 'revenue'
                          OR fp_inner.field_name LIKE 'revenue@store\\_%' ESCAPE '\\')
                     AND fp_inner.superseded_by_id IS NULL
                   ORDER BY fp_inner.confidence DESC,
                            fp_inner.valid_from DESC,
                            fp_inner.id DESC
                   LIMIT 1
              ) fp ON TRUE
             ORDER BY g.revenue {direction}
             LIMIT {limit_ph}
            """,
            *params,
        )
    return {
        "factory_id": factory_id,
        "start_month": start_m.isoformat() if start_m is not None else None,
        "end_month": end_m.isoformat() if end_m is not None else None,
        "top_products": [
            {
                # product_id is the group's representative product (MIN over
                # members). For a single-member (NULL-canonical) group this is
                # the product's own id; for a confirmed merge it's a stable
                # representative — the row is conceptually a dish, consumers
                # display product_name. Stays an int for shape compatibility.
                "product_id": int(r["product_id"]),
                # product_name is canonical_name when merged, else the product
                # name — driven by COALESCE(cd.canonical_name, p.name).
                "product_name": r["name"],
                "qty_sold": float(r["qty"]),
                "revenue": float(r["revenue"]),
                "bill_count": int(r["bill_count"]),
                # Sub-Project C Day 24-25 POC: per-row provenance pass-through,
                # now driven off the group's representative product.
                # confidence/source/source_upload_id are None when no field_
                # provenance row matches (prod-OFF empty-table state).
                # field_name is returned from the JOIN when matched (carries
                # the @store_<id> suffix); when NULL, fall back to the
                # deterministic 'revenue' so the FE can still construct the
                # cell-audit URL (Day 26 page lands the lookup).
                "confidence": (
                    float(r["confidence"]) if r["confidence"] is not None else None
                ),
                "source": r["source"],
                "source_upload_id": (
                    int(r["source_upload_id"]) if r["source_upload_id"] is not None else None
                ),
                "entity_id": str(int(r["product_id"])),
                "field_name": r["prov_field_name"] if r["prov_field_name"] else "revenue",
            }
            for r in rows
        ],
    }


async def channel_breakdown(
    pool: asyncpg.Pool,
    factory_id: str,
    date_range: Tuple[Optional[date], Optional[date]],
    *,
    top_n: int = 10,
) -> Dict[str, Any]:
    """Revenue by payment channel — feeds 分析概览 channel breakdown.
    If fact_pos_payment has no rows for the tenant (EAV extraction not
    yet wired for this source), returns empty channels list.
    """
    start, end = date_range
    _validate_range(start, end)
    conds = ["a.factory_id = $1"]
    params: list = [factory_id]
    if start is not None:
        params.append(start)
        conds.append(f"a.date >= ${len(params)}")
    if end is not None:
        params.append(end)
        conds.append(f"a.date <= ${len(params)}")
    where = " AND ".join(conds)
    params.append(int(top_n))
    limit_ph = f"${len(params)}"
    async with pool.acquire() as conn:
        rows = await conn.fetch(
            f"""
            SELECT c.channel_id,
                   c.name,
                   SUM(a.amount)::numeric(18,2) AS amount,
                   SUM(a.bill_count)            AS bill_count
              FROM agg_channel a
              JOIN dim_payment_channel c ON c.channel_id = a.channel_id
             WHERE {where}
             GROUP BY c.channel_id, c.name
             ORDER BY SUM(a.amount) DESC
             LIMIT {limit_ph}
            """,
            *params,
        )
    total = sum(Decimal(r["amount"]) for r in rows)
    channels = []
    for r in rows:
        amt = Decimal(r["amount"])
        share = float((amt / total * 100).quantize(Decimal("0.01"))) if total > 0 else 0.0
        channels.append({
            "channel_id": int(r["channel_id"]),
            # Display normalization: strip qhj POS export wrapping brackets
            # (e.g. [微信]→微信). Done post-fetch so GROUP BY is unaffected.
            "channel_name": _clean_display_name(r["name"]),
            "amount": float(amt),
            "bill_count": int(r["bill_count"]),
            "share_pct": share,
        })
    return {
        "factory_id": factory_id,
        "start_date": start.isoformat() if start is not None else None,
        "end_date": end.isoformat() if end is not None else None,
        "total_amount": float(total),
        "channels": channels,
    }


async def discount_breakdown(
    pool: asyncpg.Pool,
    factory_id: str,
    date_range: Tuple[Optional[date], Optional[date]],
    *,
    top_n: int = 10,
) -> Dict[str, Any]:
    """Discount usage broken down by voucher/coupon type.

    Reads agg_discount (monthly grain) for any month that intersects the
    date range. Rolls up per discount across months. Upgraded from the
    original ad-hoc JOIN-and-GROUP-BY over fact_pos_discount × fact_pos_
    transaction (that version still worked correctly but was O(N) per
    request; this one is O(months × discounts) ≈ a few dozen rows).

    (不计)-suffixed columns are already filtered out upstream by the
    backfill heuristic — those never reach fact_pos_discount.
    """
    start, end = date_range
    _validate_range(start, end)
    start_m = start.replace(day=1) if start is not None else None
    end_m = end.replace(day=1) if end is not None else None
    conds = ["a.factory_id = $1"]
    params: list = [factory_id]
    if start_m is not None:
        params.append(start_m)
        conds.append(f"a.month >= ${len(params)}")
    if end_m is not None:
        params.append(end_m)
        conds.append(f"a.month <= ${len(params)}")
    where = " AND ".join(conds)
    params.append(int(top_n))
    limit_ph = f"${len(params)}"
    async with pool.acquire() as conn:
        rows = await conn.fetch(
            f"""
            SELECT d.discount_id,
                   d.name,
                   SUM(a.amount)::numeric(18,2) AS amount,
                   SUM(a.bill_count)            AS bill_count
              FROM agg_discount a
              JOIN dim_discount d ON d.discount_id = a.discount_id
             WHERE {where}
             GROUP BY d.discount_id, d.name
             ORDER BY SUM(a.amount) DESC
             LIMIT {limit_ph}
            """,
            *params,
        )
    total = sum(Decimal(r["amount"]) for r in rows)
    items = []
    for r in rows:
        amt = Decimal(r["amount"])
        share = float((amt / total * 100).quantize(Decimal("0.01"))) if total > 0 else 0.0
        items.append({
            "discount_id": int(r["discount_id"]),
            # Display normalization: strip qhj POS export wrapping brackets
            # (e.g. [美团套餐券]→美团套餐券). Post-fetch so GROUP BY unaffected.
            "discount_name": _clean_display_name(r["name"]),
            "amount": float(amt),
            "bill_count": int(r["bill_count"]),
            "share_pct": share,
        })
    return {
        "factory_id": factory_id,
        "start_date": start.isoformat() if start is not None else None,
        "end_date": end.isoformat() if end is not None else None,
        "total_amount": float(total),
        "discounts": items,
    }


async def kpi_summary(
    pool: asyncpg.Pool,
    factory_id: str,
    date_range: Tuple[Optional[date], Optional[date]],
) -> Dict[str, Any]:
    """Compact KPI card data for the 分析概览 + KPI看板 headers.

    Combines cheap aggregates from agg_daily (revenue, bills) + one
    extra stat from Silver (items_total). More expensive ranking queries
    (top store, top product) are in separate endpoints so callers can
    pick-and-choose.
    """
    start, end = date_range
    _validate_range(start, end)
    conds = ["factory_id = $1"]
    params: list = [factory_id]
    if start is not None:
        params.append(start)
        conds.append(f"date >= ${len(params)}")
    if end is not None:
        params.append(end)
        conds.append(f"date <= ${len(params)}")
    where = " AND ".join(conds)
    async with pool.acquire() as conn:
        daily = await conn.fetchrow(
            f"""
            SELECT
              COALESCE(SUM(net_amount), 0)::numeric(18,2) AS revenue,
              COALESCE(SUM(bill_count), 0)               AS bills,
              COALESCE(SUM(item_count), 0)               AS items,
              COALESCE(SUM(customer_count), 0)           AS customers,
              COUNT(DISTINCT store_id)                   AS stores,
              COUNT(DISTINCT date)                       AS days
            FROM agg_daily
            WHERE {where}
            """,
            *params,
        )
    revenue = Decimal(daily["revenue"])
    bills = int(daily["bills"])
    items = int(daily["items"])
    customers = int(daily["customers"])
    avg_bill = float((revenue / bills).quantize(Decimal("0.01"))) if bills > 0 else None
    items_per_bill = round(items / bills, 2) if bills > 0 else None
    avg_per_capita = float((revenue / customers).quantize(Decimal("0.01"))) if customers > 0 else None

    return {
        "factory_id": factory_id,
        "start_date": start.isoformat() if start is not None else None,
        "end_date": end.isoformat() if end is not None else None,
        "revenue": float(revenue),
        "bill_count": bills,
        "item_count": items,
        "customer_count": customers,
        "store_count": int(daily["stores"]),
        "day_count": int(daily["days"]),
        "avg_bill_value": avg_bill,
        "items_per_bill": items_per_bill,
        "avg_per_capita": avg_per_capita,
    }


async def data_range(
    pool: asyncpg.Pool,
    factory_id: str,
) -> Dict[str, Any]:
    """Actual date span of a factory's materialized Gold data (agg_daily).

    Lets the dashboard default a restaurant tenant's date picker to its real
    data window (e.g. 2025-01-01 ~ 2025-12-31) instead of the empty current
    month. Returns null dates when the factory has no Gold rows yet — an
    honest empty, never a fabricated range (no-fake-data rule).
    """
    async with pool.acquire() as conn:
        row = await conn.fetchrow(
            """
            SELECT
              MIN(date)            AS min_date,
              MAX(date)            AS max_date,
              COUNT(DISTINCT date) AS day_count
            FROM agg_daily
            WHERE factory_id = $1
            """,
            factory_id,
        )
    min_date = row["min_date"] if row else None
    max_date = row["max_date"] if row else None
    return {
        "factory_id": factory_id,
        "min_date": min_date.isoformat() if min_date else None,
        "max_date": max_date.isoformat() if max_date else None,
        "day_count": int(row["day_count"]) if row and row["day_count"] else 0,
    }


async def order_type_mix(
    pool: asyncpg.Pool,
    factory_id: str,
    date_range: Tuple[Optional[date], Optional[date]],
) -> Dict[str, Any]:
    """Dine-in vs takeout revenue split from agg_daily_order_type_meal.

    Reads agg_daily_order_type_meal.order_type (values: 堂食/外卖).
    NOTE: this is NOT the same as channel-breakdown which shows payment
    channel (微信/美团) — using channel data for delivery-type mix conflates
    payment method with service mode.

    Returns:
      - total_revenue
      - order_types: list of {order_type, revenue, bill_count, revenue_pct}
        sorted by revenue descending.
    """
    start, end = date_range
    _validate_range(start, end)
    conds = ["factory_id = $1"]
    params: list = [factory_id]
    if start is not None:
        params.append(start)
        conds.append(f"date >= ${len(params)}")
    if end is not None:
        params.append(end)
        conds.append(f"date <= ${len(params)}")
    daily_where = " AND ".join(conds)
    conds.append("order_type IS NOT NULL")
    where = " AND ".join(conds)
    async with pool.acquire() as conn:
        rows = await conn.fetch(
            f"""
            SELECT order_type,
                   COALESCE(SUM(actual_receive), 0)::numeric(18,2) AS amt,
                   COALESCE(SUM(bill_count), 0)                    AS bills
              FROM agg_daily_order_type_meal
             WHERE {where}
             GROUP BY order_type
             ORDER BY amt DESC
            """,
            *params,
        )
    total = sum((Decimal(str(r["amt"])) for r in rows), Decimal("0"))
    total_bills = sum((int(r["bills"] or 0) for r in rows), 0)
    avg_ticket: Optional[Decimal] = None
    revenue_estimated = False
    estimation_note: Optional[str] = None
    if total <= 0 and total_bills > 0:
        async with pool.acquire() as conn:
            avg_row = await conn.fetchrow(
                f"""
                SELECT COALESCE(SUM(net_amount), 0)::numeric(18,2) AS revenue,
                       COALESCE(SUM(bill_count), 0)                AS bills
                  FROM agg_daily
                 WHERE {daily_where}
                """,
                *params,
            )
        overall_revenue = Decimal(str(avg_row["revenue"] or 0)) if avg_row else Decimal("0")
        overall_bills = int(avg_row["bills"] or 0) if avg_row else 0
        if overall_revenue > 0 and overall_bills > 0:
            avg_ticket = overall_revenue / Decimal(overall_bills)
            revenue_estimated = True
            estimation_note = "堂食/外卖金额字段缺失，按全店平均客单价估算，仅用于结构参考。"

    if revenue_estimated and avg_ticket is not None:
        total = sum(
            (
                (avg_ticket * Decimal(int(r["bills"] or 0))).quantize(Decimal("0.01"), rounding=ROUND_HALF_UP)
                for r in rows
            ),
            Decimal("0"),
        )

    types = []
    for r in rows:
        amt = Decimal(str(r["amt"]))
        bills = int(r["bills"] or 0)
        if revenue_estimated and avg_ticket is not None:
            amt = (avg_ticket * Decimal(bills)).quantize(Decimal("0.01"), rounding=ROUND_HALF_UP)
        pct = (
            (amt / total * 100).quantize(Decimal("0.1"), rounding=ROUND_HALF_UP)
            if total > 0
            else Decimal("0")
        )
        types.append({
            # Display normalization (consistency with channel/discount): strip
            # any wrapping brackets. 堂食/外卖 are normally unbracketed so this
            # is a no-op for qhj, but guards against bracketed POS export values.
            "order_type": _clean_display_name(r["order_type"]),
            "revenue": float(amt),
            "bill_count": bills,
            "revenue_pct": float(pct),
            "revenue_estimated": revenue_estimated,
        })
    return {
        "factory_id": factory_id,
        "start_date": start.isoformat() if start is not None else None,
        "end_date": end.isoformat() if end is not None else None,
        "total_revenue": float(total),
        "revenue_estimated": revenue_estimated,
        "estimation_note": estimation_note,
        "order_types": types,
    }


# ── Restaurant-analytics dimensions: 渠道(点餐方式) / 时段 / 折扣 ──────────
#
# Follows the dish_margin() pattern (queries.py): deterministic aggregation,
# Decimal + ROUND_HALF_UP (python-java-port.md Rule 10/12), plain dicts. The
# LLM never derives these numbers, only narrates them (synthesis_engine plan
# gates + factbook renders).
#
# NAMING NOTE: this "渠道" dimension is service-mode (堂食/外卖/自提), sourced
# from agg_daily_order_type_meal.order_type — NOT the same concept as the
# existing ``channel_breakdown()`` above, which is PAYMENT channel (微信/美团)
# sourced from agg_channel/dim_payment_channel and already wired into the
# "sales" synthesis dimension. To avoid shadowing that existing function this
# one is named ``order_type_breakdown`` (mirrors ``order_type_mix`` above,
# which powers the dashboard chart but lacks 客单价 and meal_period support).


async def _service_mode_breakdown(
    pool: asyncpg.Pool,
    factory_id: str,
    date_range: Tuple[Optional[date], Optional[date]],
    *,
    group_col: str,
    result_key: str,
) -> Dict[str, Any]:
    """Shared 渠道(order_type) / 时段(meal_period) breakdown over
    agg_daily_order_type_meal, grouped on ``group_col``.

    Honest-null (禁止降级, mirrors ``order_type_mix``): the money columns in
    agg_daily_order_type_meal can be missing/NULL for a tenant that only ever
    populated the bill_count split (real qhj case). When SUM(actual_receive)
    is 0/NULL but there ARE bills, we do NOT emit ¥0 revenue / ¥0.00 客单价 as
    grounded facts. Instead we estimate revenue from the全店 average客单价
    (agg_daily net_amount / bills over the same window) and flag
    ``revenue_estimated=True`` + an estimation note, so the render + facts
    index treat those numbers as estimates (never hard-checked as truth). If
    even the全店 average is unavailable, revenue/客单价 stay None (bill-count
    structure only).
    """
    start, end = date_range
    _validate_range(start, end)
    conds = ["factory_id = $1", f"{group_col} IS NOT NULL"]
    params: list = [factory_id]
    if start is not None:
        params.append(start)
        conds.append(f"date >= ${len(params)}")
    if end is not None:
        params.append(end)
        conds.append(f"date <= ${len(params)}")
    where = " AND ".join(conds)
    # Daily fallback uses the SAME params (the group_col IS NOT NULL clause is
    # static, not a param) minus the group filter.
    daily_where = " AND ".join(c for c in conds if "IS NOT NULL" not in c)
    async with pool.acquire() as conn:
        rows = await conn.fetch(
            f"""
            SELECT {group_col} AS grp,
                   COALESCE(SUM(actual_receive), 0)::numeric(18,2) AS revenue,
                   COALESCE(SUM(bill_count), 0)                    AS bill_count
              FROM agg_daily_order_type_meal
             WHERE {where}
             GROUP BY {group_col}
             ORDER BY SUM(actual_receive) DESC, SUM(bill_count) DESC
            """,
            *params,
        )
        raw_total = sum((Decimal(str(r["revenue"])) for r in rows), Decimal("0"))
        total_bills = sum((int(r["bill_count"] or 0) for r in rows), 0)

        avg_ticket: Optional[Decimal] = None
        revenue_estimated = False
        estimation_note: Optional[str] = None
        if raw_total <= 0 and total_bills > 0:
            avg_row = await conn.fetchrow(
                f"""
                SELECT COALESCE(SUM(net_amount), 0)::numeric(18,2) AS revenue,
                       COALESCE(SUM(bill_count), 0)                AS bills
                  FROM agg_daily
                 WHERE {daily_where}
                """,
                *params,
            )
            overall_rev = Decimal(str(avg_row["revenue"] or 0)) if avg_row else Decimal("0")
            overall_bills = int(avg_row["bills"] or 0) if avg_row else 0
            if overall_rev > 0 and overall_bills > 0:
                avg_ticket = overall_rev / Decimal(overall_bills)
                revenue_estimated = True
                estimation_note = (
                    "分渠道/时段金额字段缺失，营收按全店平均客单价估算，仅供结构参考（非确定性金额）。"
                )

    # Total: real when money present, estimated when we could back it out of
    # 全店 avg ticket, else 0 (honest — carried alongside revenue_estimated /
    # a None-ticket structure so the render never presents ¥0 as a hard fact).
    if revenue_estimated and avg_ticket is not None:
        total_revenue = sum(
            ((avg_ticket * Decimal(int(r["bill_count"] or 0))).quantize(
                Decimal("0.01"), rounding=ROUND_HALF_UP) for r in rows),
            Decimal("0"),
        )
    else:
        total_revenue = raw_total

    items = []
    for r in rows:
        bills = int(r["bill_count"] or 0)
        if revenue_estimated and avg_ticket is not None:
            revenue = (avg_ticket * Decimal(bills)).quantize(
                Decimal("0.01"), rounding=ROUND_HALF_UP)
        else:
            revenue = Decimal(str(r["revenue"]))
        # 客单价: real/estimated only when we have a positive revenue basis;
        # NEVER ¥0.00 for a real bill count with missing money (honest-null).
        if bills > 0 and (revenue > 0 or (revenue_estimated and avg_ticket is not None)):
            avg_t = (revenue / bills).quantize(Decimal("0.01"), rounding=ROUND_HALF_UP)
        else:
            avg_t = None
        share_pct = (
            (revenue / total_revenue * 100).quantize(Decimal("0.1"), rounding=ROUND_HALF_UP)
            if total_revenue > 0 else None
        )
        items.append({
            result_key: _clean_display_name(r["grp"]),
            "revenue": float(revenue),
            "bill_count": bills,
            "avg_ticket": float(avg_t) if avg_t is not None else None,
            "revenue_pct": float(share_pct) if share_pct is not None else None,
            "revenue_estimated": revenue_estimated,
        })
    return {
        "factory_id": factory_id,
        "start_date": start.isoformat() if start is not None else None,
        "end_date": end.isoformat() if end is not None else None,
        "total_revenue": float(total_revenue),
        "revenue_estimated": revenue_estimated,
        "estimation_note": estimation_note,
        f"{result_key}s": items,
    }


async def order_type_breakdown(
    pool: asyncpg.Pool,
    factory_id: str,
    date_range: Tuple[Optional[date], Optional[date]],
) -> Dict[str, Any]:
    """渠道(点餐方式) breakdown — 堂食/外卖/自提 revenue/order/ticket split.

    Source: agg_daily_order_type_meal.order_type. Deterministic; the LLM
    never derives 客单价/占比 — both are computed here. Honest-null aware
    (see ``_service_mode_breakdown``): missing money → estimated + flagged,
    never a confident ¥0. Returns ``order_types`` items.
    """
    return await _service_mode_breakdown(
        pool, factory_id, date_range, group_col="order_type", result_key="order_type",
    )


async def meal_period_breakdown(
    pool: asyncpg.Pool,
    factory_id: str,
    date_range: Tuple[Optional[date], Optional[date]],
) -> Dict[str, Any]:
    """时段 breakdown — 午市/晚市/夜宵 revenue/order/ticket split.

    Source: agg_daily_order_type_meal.meal_period (same table as
    order_type_breakdown, grouped on the OTHER dimension). Honest-null aware.
    Returns ``meal_periods`` items.
    """
    return await _service_mode_breakdown(
        pool, factory_id, date_range, group_col="meal_period", result_key="meal_period",
    )


async def discount_summary(
    pool: asyncpg.Pool,
    factory_id: str,
    date_range: Tuple[Optional[date], Optional[date]],
    *,
    top_n: int = 10,
) -> Dict[str, Any]:
    """折扣 dimension — 折扣总额 / 占营收比 / 按类型构成 (DESCRIPTIVE only).

    GROUNDED TOTAL (C1 fix): the 折扣总额 and 占营收比 come from
    ``agg_daily`` over the EXACT (start, end) day-grain window — the SAME
    table/window/口径 the finance dimension uses:
      折扣总额 = SUM(agg_daily.discount_amount)   over the window
      占营收比 = 折扣总额 / SUM(agg_daily.net_amount)  (营收=应收 net 口径)
    This avoids the earlier month-grain vs day-grain mismatch (a partial-month
    window like "上周" summed a WHOLE month of agg_discount but divided by only
    that week's agg_daily revenue → wildly wrong ratio, and it was indexed as
    truth so FactReconciler enforced the wrong number).

    COMPOSITION: ``agg_discount`` (monthly) JOIN ``dim_discount`` supplies only
    the per-type PROPORTIONS (满减/会员折扣/团购券). Each type amount is SCALED
    to the agg_daily-grounded total so the types sum to 折扣总额 (no
    contradiction between the grounded total and the composition).

    DESCRIPTIVE only — reports how much was discounted and its share of
    revenue; NEVER claims discounts "brought in" incremental revenue (no causal
    claim is groundable from this schema).
    """
    start, end = date_range
    _validate_range(start, end)

    # --- Grounded total + revenue share from agg_daily (day-grain, exact window) ---
    rev_conds = ["factory_id = $1"]
    rev_params: list = [factory_id]
    if start is not None:
        rev_params.append(start)
        rev_conds.append(f"date >= ${len(rev_params)}")
    if end is not None:
        rev_params.append(end)
        rev_conds.append(f"date <= ${len(rev_params)}")
    rev_where = " AND ".join(rev_conds)

    # --- Composition from agg_discount (month grain intersecting the window) ---
    start_m = start.replace(day=1) if start is not None else None
    end_m = end.replace(day=1) if end is not None else None
    comp_conds = ["a.factory_id = $1"]
    comp_params: list = [factory_id]
    if start_m is not None:
        comp_params.append(start_m)
        comp_conds.append(f"a.month >= ${len(comp_params)}")
    if end_m is not None:
        comp_params.append(end_m)
        comp_conds.append(f"a.month <= ${len(comp_params)}")
    comp_where = " AND ".join(comp_conds)

    async with pool.acquire() as conn:
        rev_row = await conn.fetchrow(
            f"""
            SELECT COALESCE(SUM(discount_amount), 0)::numeric(18,2) AS discount,
                   COALESCE(SUM(net_amount), 0)::numeric(18,2)      AS revenue
              FROM agg_daily
             WHERE {rev_where}
            """,
            *rev_params,
        )
        comp_rows = await conn.fetch(
            f"""
            SELECT d.discount_id,
                   d.name,
                   SUM(a.amount)::numeric(18,2) AS amount,
                   SUM(a.bill_count)            AS bill_count
              FROM agg_discount a
              JOIN dim_discount d ON d.discount_id = a.discount_id
             WHERE {comp_where}
             GROUP BY d.discount_id, d.name
             ORDER BY SUM(a.amount) DESC
            """,
            *comp_params,
        )

    total_discount = (
        Decimal(str(rev_row["discount"]))
        if rev_row and rev_row["discount"] is not None else Decimal("0")
    )
    total_revenue = (
        Decimal(str(rev_row["revenue"]))
        if rev_row and rev_row["revenue"] is not None else Decimal("0")
    )
    revenue_share_pct = (
        (total_discount / total_revenue * 100).quantize(Decimal("0.1"), rounding=ROUND_HALF_UP)
        if total_revenue > 0 else None
    )

    # Composition proportions from the (un-limited) grand total of agg_discount
    # amounts, then SCALE each type to the agg_daily-grounded total. Per-type
    # share_pct denominator is that grand comp total (I2 — not the top_n sum).
    comp_total = sum((Decimal(str(r["amount"])) for r in comp_rows), Decimal("0"))
    items = []
    for r in comp_rows[:int(top_n)]:
        raw_amt = Decimal(str(r["amount"]))
        proportion = (raw_amt / comp_total) if comp_total > 0 else Decimal("0")
        scaled = (total_discount * proportion).quantize(
            Decimal("0.01"), rounding=ROUND_HALF_UP)
        share = (proportion * 100).quantize(Decimal("0.1"), rounding=ROUND_HALF_UP)
        items.append({
            "discount_id": int(r["discount_id"]),
            "discount_name": _clean_display_name(r["name"]),
            "amount": float(scaled),
            "bill_count": int(r["bill_count"] or 0),
            "share_pct": float(share),
        })
    return {
        "factory_id": factory_id,
        "start_date": start.isoformat() if start is not None else None,
        "end_date": end.isoformat() if end is not None else None,
        "total_discount_amount": float(total_discount),
        "total_revenue": float(total_revenue),
        "revenue_share_pct": float(revenue_share_pct) if revenue_share_pct is not None else None,
        "discounts": items,
    }


async def staff_ranking(
    pool: asyncpg.Pool,
    factory_id: str,
    date_range: Tuple[Optional[date], Optional[date]],
    *,
    top_n: int = 5,
) -> Dict[str, Any]:
    """POS operator ranking by net revenue handled.

    Data source: fact_pos_transaction.staff_id — this is the POS
    operator (cashier / order-taker), NOT a server/waiter attribution.
    Results MUST be read with the accompanying caveat.

    Returns:
      - staff: list of {name, net_amount, bill_count} sorted by net_amount desc
      - caveat: honest disclaimer about data meaning (required by no-fabrication rule)
    """
    start, end = date_range
    _validate_range(start, end)
    conds = ["t.factory_id = $1"]
    params: list = [factory_id]
    if start is not None:
        params.append(start)
        conds.append(f"t.date >= ${len(params)}")
    if end is not None:
        params.append(end)
        conds.append(f"t.date <= ${len(params)}")
    conds.append("t.staff_id IS NOT NULL")
    where = " AND ".join(conds)
    params.append(int(top_n))
    limit_ph = f"${len(params)}"
    async with pool.acquire() as conn:
        rows = await conn.fetch(
            f"""
            SELECT COALESCE(s.name, t.staff_id::text) AS name,
                   COALESCE(SUM(t.net_amount), 0)::numeric(18,2) AS net,
                   COUNT(*)                                        AS bills
              FROM fact_pos_transaction t
              LEFT JOIN dim_staff s
                     ON s.staff_id = t.staff_id
                    AND s.factory_id = t.factory_id
             WHERE {where}
             GROUP BY COALESCE(s.name, t.staff_id::text)
             ORDER BY net DESC
             LIMIT {limit_ph}
            """,
            *params,
        )
    staff = [
        {
            "name": r["name"],
            "net_amount": float(Decimal(str(r["net"]))),
            "bill_count": int(r["bills"]),
        }
        for r in rows
    ]
    return {
        "factory_id": factory_id,
        "start_date": start.isoformat() if start is not None else None,
        "end_date": end.isoformat() if end is not None else None,
        "staff": staff,
        "caveat": "POS 数据按开单操作员(收银/点菜)记账, 非服务员业绩归因; 仅供操作量参考。",
    }


async def finance_summary(
    pool: asyncpg.Pool,
    factory_id: str,
    date_range: Tuple[Optional[date], Optional[date]],
    *,
    top_n_stores: int = 10,
) -> Dict[str, Any]:
    """Finance KPI summary for the Vue 财务报表 page.

    Returns a dict with:
      - `factory_id`, `start_date`, `end_date` — echoes input
      - `total_revenue` — SUM(net_amount) across range
      - `bill_count` — SUM(bill_count) across range
      - `avg_bill_value` — revenue / bills (None if bills=0)
      - `store_count` — distinct stores with any activity
      - `day_count` — distinct dates with any activity
      - `top_stores` — top N stores by revenue, each with
        {store_id, store_name, revenue, bill_count}

    Current v1.1 scope intentionally omits cost metrics (material/labor/
    overhead) because Silver doesn't yet capture them — those live in
    smart_bi_finance_data legacy records. Week 5+ will add cost Silver
    tables and extend this shape.
    """
    start, end = date_range
    _validate_range(start, end)

    # Shared dynamic date params (None bound = no filter on that side =
    # all history). The two queries use different column aliases (bare
    # `date` vs `a.date`) but the SAME $N param order, so we build the
    # param list once and two parallel WHERE fragments.
    params: list = [factory_id]
    totals_conds = ["a.factory_id = $1"]
    stores_conds = ["a.factory_id = $1"]
    if start is not None:
        params.append(start)
        totals_conds.append(f"a.date >= ${len(params)}")
        stores_conds.append(f"a.date >= ${len(params)}")
    if end is not None:
        params.append(end)
        totals_conds.append(f"a.date <= ${len(params)}")
        stores_conds.append(f"a.date <= ${len(params)}")
    totals_where = " AND ".join(totals_conds)
    stores_where = " AND ".join(stores_conds)
    # top_n_stores comes AFTER the dynamic date params → renumbered $N.
    params.append(int(top_n_stores))
    limit_ph = f"${len(params)}"

    async with pool.acquire() as conn:
        # Grand totals + row counts. Uses only the date params (drops the
        # trailing top_n_stores limit param via the slice).
        totals = await conn.fetchrow(
            f"""
            SELECT
              COALESCE(SUM(a.net_amount), 0)::numeric(18,2)  AS total_revenue,
              COALESCE(SUM(a.bill_count), 0)                 AS bill_count,
              COUNT(DISTINCT a.store_id)                     AS store_count,
              COUNT(DISTINCT a.date)                         AS day_count,
              SUM(c.material_cost)::numeric(18,2)            AS material_cost,
              SUM(c.labor_cost)::numeric(18,2)               AS labor_cost,
              SUM(c.overhead_cost)::numeric(18,2)            AS overhead_cost
            FROM agg_daily a
            LEFT JOIN agg_daily_cost c USING (factory_id, date, store_id)
            WHERE {totals_where}
            """,
            *params[:-1],
        )

        top_stores = await conn.fetch(
            f"""
            SELECT a.store_id,
                   s.name AS store_name,
                   COALESCE(SUM(a.net_amount), 0)::numeric(18,2) AS revenue,
                   COALESCE(SUM(a.bill_count), 0)                AS bill_count
              FROM agg_daily a
              JOIN dim_store s ON s.store_id = a.store_id
             WHERE {stores_where}
             GROUP BY a.store_id, s.name
             ORDER BY COALESCE(SUM(a.net_amount), 0) DESC
             LIMIT {limit_ph}
            """,
            *params,
        )

    total_revenue = Decimal(totals["total_revenue"])
    bill_count = int(totals["bill_count"])
    avg_bill_value: Optional[Decimal] = (
        (total_revenue / bill_count).quantize(Decimal("0.01"))
        if bill_count > 0 else None
    )

    result = {
        "factory_id": factory_id,
        "start_date": start.isoformat() if start is not None else None,
        "end_date": end.isoformat() if end is not None else None,
        "total_revenue": float(total_revenue),
        "bill_count": bill_count,
        "avg_bill_value": float(avg_bill_value) if avg_bill_value is not None else None,
        "store_count": int(totals["store_count"]),
        "day_count": int(totals["day_count"]),
        "top_stores": [
            {
                "store_id": int(r["store_id"]),
                "store_name": r["store_name"],
                "revenue": float(r["revenue"]),
                "bill_count": int(r["bill_count"]),
            }
            for r in top_stores
        ],
    }

    cost_values = {}
    for key in ("material_cost", "labor_cost", "overhead_cost"):
        try:
            cost_values[key] = totals[key]
        except KeyError:
            cost_values[key] = None

    if any(v is not None for v in cost_values.values()):
        material_cost = Decimal(cost_values["material_cost"] or 0)
        labor_cost = Decimal(cost_values["labor_cost"] or 0)
        overhead_cost = Decimal(cost_values["overhead_cost"] or 0)
        total_cost = material_cost + labor_cost + overhead_cost
        net_profit = total_revenue - total_cost
        gross_profit = total_revenue - material_cost
        net_margin_pct: Optional[Decimal] = (
            ((net_profit / total_revenue) * Decimal("100")).quantize(
                Decimal("0.1"),
                rounding=ROUND_HALF_UP,
            )
            if total_revenue != 0 else None
        )
        result.update({
            "material_cost": float(material_cost),
            "labor_cost": float(labor_cost),
            "overhead_cost": float(overhead_cost),
            "total_cost": float(total_cost),
            "net_profit": float(net_profit),
            "gross_profit": float(gross_profit),
            "net_margin_pct": float(net_margin_pct) if net_margin_pct is not None else None,
        })

    return result


async def period_comparison(pool, factory_id, start, end):
    """同比(去年同期)/环比(前一等长周期) for 营收 + 加权毛利率, over agg_daily(+agg_daily_cost).

    加权毛利 = 营收 - 食材成本 (镜像 finance_summary.gross_profit); 加权毛利率 = 该值/营收*100。
    餐饮口径: 这是聚合的加权毛利率, 非单品毛利 (中餐单品用量难固定, 单品不可靠)。

    诚实降级 (禁降级处理): 对比期无营业数据 → available=False, pct=None
    (绝不返回伪造的 0/100%)。毛利率对比额外要求两侧成本非空 (agg_daily_cost 真租户可能 NULL)。

    营收同比/环比 = 增长率 (%); 毛利率同比/环比 = 百分点差 (个百分点, 从30%到34%=+4)。

    返回:
      {"revenue": {"current", "yoy_pct", "mom_pct", "yoy_available", "mom_available"},
       "gross_margin_pct": {同上键, "current" 可能 None}}
    """
    if factory_id is None or factory_id == "":
        raise ValueError(f"period_comparison: factory_id required (got {factory_id!r})")
    if start is None or end is None:
        raise ValueError(f"period_comparison: start/end required (got {start}, {end})")
    from datetime import timedelta

    def _shift_year(d, years):
        try:
            return d.replace(year=d.year - years)
        except ValueError:  # Feb 29 → 365天近似回退
            return d - timedelta(days=365 * years)

    span = end - start
    windows = {
        "current": (start, end),
        "mom": (start - timedelta(days=1) - span, start - timedelta(days=1)),
        "yoy": (_shift_year(start, 1), _shift_year(end, 1)),
    }

    async def _agg(conn, s, e):
        row = await conn.fetchrow(
            """
            SELECT COALESCE(SUM(a.net_amount), 0)::numeric(18,2) AS revenue,
                   SUM(c.material_cost)::numeric(18,2)           AS material_cost,
                   COUNT(c.material_cost)                         AS cost_n,
                   COUNT(*)                                       AS n_rows
              FROM agg_daily a
              LEFT JOIN agg_daily_cost c USING (factory_id, date, store_id)
             WHERE a.factory_id = $1 AND a.date BETWEEN $2 AND $3
            """,
            factory_id, s, e,
        )
        # 领料成本 — 直接查 silver fact_restaurant_requisition 并 status 过滤
        # (Fable F1/F2: gold agg_restaurant_daily_totals 被 wastage/stocktaking 日
        # 污染[req_cost=0 行]且含 DRAFT/REJECTED → 会造 0%-base 假上升+误告; 这里
        # 只取 SUBMITTED/APPROVED 领料行, 天然 requisition-only 无污染)。
        # 按 factory-date 聚合, 不与 agg_daily per-store 行 JOIN (否则按门店翻倍)。
        req_row = await conn.fetchrow(
            """
            SELECT SUM(est_cost)::numeric(18,2)  AS req_cost,
                   COUNT(DISTINCT date)          AS req_n
              FROM fact_restaurant_requisition
             WHERE factory_id = $1 AND date BETWEEN $2 AND $3
               AND status IN ('SUBMITTED', 'APPROVED')
            """,
            factory_id, s, e,
        )
        rev = Decimal(row["revenue"] or 0)
        n = int(row["n_rows"] or 0)
        cost_n = int(row["cost_n"] or 0)
        mat = row["material_cost"]
        gm = None
        # F5: 仅当成本覆盖满窗 (cost_n == n) 才算毛利率 — 全窗营收÷部分窗成本会虚高。
        if n > 0 and mat is not None and rev != 0 and cost_n == n:
            gm = (((rev - Decimal(mat)) / rev) * Decimal("100")).quantize(
                Decimal("0.1"), rounding=ROUND_HALF_UP)
        # 领料成本率 = 领料成本 ÷ 营收 * 100 (真实际用料, 反回扣核心信号; 上升=漏损/回扣)。
        req_cost = req_row["req_cost"] if req_row else None
        req_n = int(req_row["req_n"] or 0) if req_row else 0
        cost_ratio = None
        if n > 0 and req_cost is not None and req_n > 0 and rev != 0:
            cost_ratio = ((Decimal(req_cost) / rev) * Decimal("100")).quantize(
                Decimal("0.1"), rounding=ROUND_HALF_UP)
        return {"n": n, "revenue": rev, "gross_margin_pct": gm, "cost_ratio": cost_ratio}

    async with pool.acquire() as conn:
        await conn.execute("SELECT set_config('app.factory_id', $1, true)", factory_id)
        # 领料数据采集的全局日期范围 → 判断窗口是否被领料完整覆盖 (窗口跨越采集起点会
        # undercount 领料成本 → 假的成本率变化 = 反回扣误告; F5-analog for 领料)。
        req_span = await conn.fetchrow(
            "SELECT MIN(date) AS d0, MAX(date) AS d1 FROM fact_restaurant_requisition "
            "WHERE factory_id = $1 AND status IN ('SUBMITTED', 'APPROVED')", factory_id)
        agg = {k: await _agg(conn, s, e) for k, (s, e) in windows.items()}
    req_d0 = req_span["d0"] if req_span else None
    req_d1 = req_span["d1"] if req_span else None
    # 领料成本率仅在窗口完全落在采集区间内才有效, 否则 None (禁 undercount 误告)。
    for _k, (_ws, _we) in windows.items():
        if not (req_d0 is not None and req_d1 is not None and req_d0 <= _ws and _we <= req_d1):
            agg[_k]["cost_ratio"] = None

    def _growth(cur, base):
        if base is None or base == 0:
            return None
        return float((((cur - base) / abs(base)) * Decimal("100")).quantize(
            Decimal("0.1"), rounding=ROUND_HALF_UP))

    cur = agg["current"]
    cur_available = cur["n"] > 0
    rev_mom = cur_available and agg["mom"]["n"] > 0
    rev_yoy = cur_available and agg["yoy"]["n"] > 0
    cur_gm = cur["gross_margin_pct"]
    gm_mom = cur_gm is not None and agg["mom"]["gross_margin_pct"] is not None
    gm_yoy = cur_gm is not None and agg["yoy"]["gross_margin_pct"] is not None
    cur_cr = cur["cost_ratio"]
    cr_mom = cur_cr is not None and agg["mom"]["cost_ratio"] is not None
    cr_yoy = cur_cr is not None and agg["yoy"]["cost_ratio"] is not None
    return {
        "revenue": {
            # F1: current 窗无数据 → available False + current None (禁伪造 ¥0)
            "current": float(cur["revenue"]) if cur_available else None,
            "available": cur_available,
            "mom_pct": _growth(cur["revenue"], agg["mom"]["revenue"]) if rev_mom else None,
            "yoy_pct": _growth(cur["revenue"], agg["yoy"]["revenue"]) if rev_yoy else None,
            "mom_available": rev_mom,
            "yoy_available": rev_yoy,
        },
        "gross_margin_pct": {
            "current": float(cur_gm) if cur_gm is not None else None,
            "mom_pct": round(float(cur_gm) - float(agg["mom"]["gross_margin_pct"]), 1) if gm_mom else None,
            "yoy_pct": round(float(cur_gm) - float(agg["yoy"]["gross_margin_pct"]), 1) if gm_yoy else None,
            "mom_available": gm_mom,
            "yoy_available": gm_yoy,
        },
        "cost_ratio": {
            # 领料成本率 = 领料成本 ÷ 营收 (真实际用料口径, 独立于 POS×配方理论);
            # 同比/环比 = 百分点差。成本率上升 = 用料/漏损/回扣信号 → 去查 (邓总核心)。
            "current": float(cur_cr) if cur_cr is not None else None,
            "mom_pct": round(float(cur_cr) - float(agg["mom"]["cost_ratio"]), 1) if cr_mom else None,
            "yoy_pct": round(float(cur_cr) - float(agg["yoy"]["cost_ratio"]), 1) if cr_yoy else None,
            "mom_available": cr_mom,
            "yoy_available": cr_yoy,
        },
    }


def _as_decimal(value: Any) -> Optional[Decimal]:
    if value is None:
        return None
    return Decimal(str(value))


def _normalize_ingredients(value: Any) -> list:
    if value is None:
        return []
    if isinstance(value, str):
        try:
            value = json.loads(value)
        except json.JSONDecodeError:
            return []
    if not isinstance(value, list):
        return []
    out = []
    for item in value:
        if not isinstance(item, dict):
            continue
        name = item.get("name")
        if not isinstance(name, str) or not name.strip():
            continue
        entry = {"name": name.strip()}
        for key in ("cost", "weight_g", "unit_price_per_kg"):
            if item.get(key) is not None:
                entry[key] = float(Decimal(str(item[key])))
        out.append(entry)
    return out


async def dish_margin(
    pool: asyncpg.Pool,
    factory_id: str,
    *,
    top_n: int = 10,
) -> Dict[str, Any]:
    """Dish-level selling price, unit cost, gross profit and margin rate.

    Source is restaurant_sku_forms: Layer-2 SKU BOM form with per-dish
    total_cogs_amount, selling_price and ingredients JSON. The function returns
    deterministic numbers for synthesis; the LLM never derives these figures.
    """
    async with pool.acquire() as conn:
        rows = await conn.fetch(
            """
            SELECT sku_name,
                   category,
                   total_cogs_amount::numeric(18,2) AS unit_cost,
                   selling_price::numeric(18,2) AS selling_price,
                   monthly_sales_quantity,
                   ingredients
              FROM restaurant_sku_forms
             WHERE factory_id = $1
               AND selling_price IS NOT NULL
               AND selling_price > 0
               AND total_cogs_amount IS NOT NULL
             ORDER BY (selling_price - total_cogs_amount) DESC, sku_name
             LIMIT $2
            """,
            factory_id,
            max(int(top_n) * 4, int(top_n), 20),
        )

    items = []
    for r in rows:
        selling_price = _as_decimal(r["selling_price"])
        unit_cost = _as_decimal(r["unit_cost"])
        if selling_price is None or unit_cost is None or selling_price <= 0:
            continue
        gross_profit = selling_price - unit_cost
        gross_margin_pct = ((gross_profit / selling_price) * Decimal("100")).quantize(
            Decimal("0.01"),
            rounding=ROUND_HALF_UP,
        )
        items.append({
            "dish_name": r["sku_name"],
            "category": r["category"],
            "selling_price": float(selling_price),
            "unit_cost": float(unit_cost),
            "gross_profit": float(gross_profit),
            "gross_margin_pct": float(gross_margin_pct),
            "monthly_sales_quantity": (
                float(Decimal(str(r["monthly_sales_quantity"])))
                if r["monthly_sales_quantity"] is not None else None
            ),
            "ingredients": _normalize_ingredients(r["ingredients"]),
        })

    top = sorted(items, key=lambda item: item["gross_profit"], reverse=True)[:int(top_n)]
    low = sorted(items, key=lambda item: item["gross_margin_pct"])[:int(top_n)]
    return {
        "factory_id": factory_id,
        "dish_count": len(items),
        "top_margin": top,
        "low_margin": low,
    }


async def weather_daily(
    pool: asyncpg.Pool,
    factory_id: str,
    date_range: Tuple[Optional[date], Optional[date]],
) -> Dict[str, Any]:
    """Daily internal-seed weather observations for restaurant synthesis."""
    start, end = date_range
    _validate_range(start, end)

    params: list = [factory_id]
    conds = [
        "factory_id = $1",
        "source_code = 'internal_seed_weather'",
        "benchmark_domain = 'restaurant'",
        "metric_code IN ('rain_mm', 'temp_c')",
    ]
    if start is not None:
        params.append(start)
        conds.append(f"period_start >= ${len(params)}")
    if end is not None:
        params.append(end)
        conds.append(f"period_start <= ${len(params)}")
    where = " AND ".join(conds)

    async with pool.acquire() as conn:
        rows = await conn.fetch(
            f"""
            SELECT
              period_start AS date,
              MAX(metric_value) FILTER (WHERE metric_code = 'rain_mm')::numeric(18,4) AS rain_mm,
              MAX(metric_value) FILTER (WHERE metric_code = 'temp_c')::numeric(18,4) AS temp_c
            FROM external_benchmark_observation
            WHERE {where}
            GROUP BY period_start
            ORDER BY period_start
            """,
            *params,
        )

    return {
        "factory_id": factory_id,
        "start_date": start.isoformat() if start is not None else None,
        "end_date": end.isoformat() if end is not None else None,
        "days": [
            {
                "date": r["date"].isoformat() if hasattr(r["date"], "isoformat") else str(r["date"]),
                "rain_mm": float(r["rain_mm"]) if r["rain_mm"] is not None else None,
                "temp_c": float(r["temp_c"]) if r["temp_c"] is not None else None,
            }
            for r in rows
        ],
    }


async def menu_quadrant(
    pool: asyncpg.Pool,
    factory_id: str,
    date_range: Tuple[Optional[date], Optional[date]],
) -> Dict[str, Any]:
    """菜品四象限 (收入模式) — classify each dish by sales-volume × revenue.

    Reads agg_product (monthly grain) JOIN dim_product (+ LEFT JOIN
    dim_canonical_dish for a confirmed-merge canonical name). Rolls up per
    dish name across the touched months, keeps only dishes with positive
    revenue (HAVING SUM(revenue) > 0), and orders by revenue DESC.

    Each dish is tagged into one of four quadrants using the per-dish qty and
    revenue medians as thresholds (== threshold counts as the high side):
      - 明星 (star)    : qty >= qtyMedian AND revenue >= revenueMedian
      - 金牛 (cash cow): revenue >= revenueMedian (high revenue, low volume)
      - 潜力 (potential): qty >= qtyMedian (high volume, low revenue)
      - 瘦狗 (dog)     : neither

    Dates optional (None bound = all history on that side). agg_product.month
    is first-of-month, so a bound is normalized to its first-of-month.

    Returns: {items:[{name, qty, revenue, quadrant}], qtyMedian, revenueMedian}.
    Honest empty: no dishes → items=[], medians=0.
    """
    start, end = date_range
    _validate_range(start, end)
    start_m = start.replace(day=1) if start is not None else None
    end_m = end.replace(day=1) if end is not None else None
    conds = ["a.factory_id = $1"]
    params: list = [factory_id]
    if start_m is not None:
        params.append(start_m)
        conds.append(f"a.month >= ${len(params)}")
    if end_m is not None:
        params.append(end_m)
        conds.append(f"a.month <= ${len(params)}")
    where = " AND ".join(conds)
    async with pool.acquire() as conn:
        rows = await conn.fetch(
            f"""
            SELECT COALESCE(cd.canonical_name, p.name) AS name,
                   cd.canonical_name                   AS canonical_name,
                   SUM(a.qty_sold)::numeric(18,3)      AS qty,
                   SUM(a.revenue)::numeric(18,2)       AS revenue
              FROM agg_product a
              JOIN dim_product p ON p.product_id = a.product_id
              LEFT JOIN dim_canonical_dish cd
                     ON cd.canonical_dish_id = p.canonical_dish_id
             WHERE {where}
             GROUP BY COALESCE(cd.canonical_name, p.name), cd.canonical_name
            HAVING SUM(a.revenue) > 0
             ORDER BY SUM(a.revenue) DESC
            """,
            *params,
        )

    qty_vals = [float(r["qty"]) for r in rows]
    rev_vals = [float(r["revenue"]) for r in rows]
    qty_median = _median(qty_vals)
    rev_median = _median(rev_vals)

    items = []
    for r in rows:
        qty = float(r["qty"])
        revenue = float(r["revenue"])
        qty_high = qty >= qty_median
        rev_high = revenue >= rev_median
        if qty_high and rev_high:
            quadrant = "明星"
        elif rev_high:
            quadrant = "金牛"
        elif qty_high:
            quadrant = "潜力"
        else:
            quadrant = "瘦狗"
        items.append({
            "name": r["name"],
            "qty": qty,
            "revenue": revenue,
            "quadrant": quadrant,
        })

    return {
        "factory_id": factory_id,
        "start_month": start_m.isoformat() if start_m is not None else None,
        "end_month": end_m.isoformat() if end_m is not None else None,
        "items": items,
        "qtyMedian": qty_median,
        "revenueMedian": rev_median,
    }


async def store_comparison(
    pool: asyncpg.Pool,
    factory_id: str,
    date_range: Tuple[Optional[date], Optional[date]],
) -> Dict[str, Any]:
    """门店对比 — per-store revenue / order-count / avg-ticket / 折扣率 comparison.

    Reads agg_daily JOIN dim_store, grouped by store: SUM(net_amount) as
    revenue, SUM(bill_count) as order_count, and avg_ticket = revenue /
    NULLIF(order_count, 0) (NULL → 0.0 when a store has no bills).

    Per-store 折扣率 (discountPct) = SUM(discount_amount) / SUM(gross_amount)
    * 100. agg_daily already carries discount_amount AND gross_amount at
    per-(factory, date, store) grain — both materialized straight from
    fact_pos_transaction (see materializer._DAILY_UPSERT_SQL), so the discount
    rate is computable from the SAME table without any extra join. Denominator
    is gross (含折扣前) so the rate reads as "折掉了营业额的百分之几"; gross=0
    (or NULL) → discountPct=0.0 (honest: no sales ⇒ no discount rate).

    Computes the per-store revenue median and flags weak stores (revenue
    strictly below the median) so the FE can highlight underperformers.

    Dates optional (None bound = all history on that side, filtered on
    a.date). Returns: {stores:[{name, revenue, orderCount, avgTicket,
    discountPct}], medianRevenue, weakStores:[name,...]}. Honest empty:
    stores=[], medianRevenue=0, weakStores=[].
    """
    start, end = date_range
    _validate_range(start, end)
    conds = ["a.factory_id = $1"]
    params: list = [factory_id]
    if start is not None:
        params.append(start)
        conds.append(f"a.date >= ${len(params)}")
    if end is not None:
        params.append(end)
        conds.append(f"a.date <= ${len(params)}")
    where = " AND ".join(conds)
    async with pool.acquire() as conn:
        rows = await conn.fetch(
            f"""
            SELECT s.name AS name,
                   SUM(a.net_amount)::numeric(18,2) AS revenue,
                   SUM(a.bill_count)                AS order_count,
                   (SUM(a.net_amount)
                      / NULLIF(SUM(a.bill_count), 0))::numeric(18,2) AS avg_ticket,
                   SUM(a.discount_amount)::numeric(18,2) AS discount_amount,
                   SUM(a.gross_amount)::numeric(18,2)    AS gross_amount
              FROM agg_daily a
              JOIN dim_store s ON s.store_id = a.store_id
             WHERE {where}
             GROUP BY s.name
             ORDER BY SUM(a.net_amount) DESC
            """,
            *params,
        )

    stores = []
    rev_vals = []
    for r in rows:
        revenue = float(Decimal(str(r["revenue"]))) if r["revenue"] is not None else 0.0
        order_count = int(r["order_count"]) if r["order_count"] is not None else 0
        avg_ticket = (
            float(Decimal(str(r["avg_ticket"]))) if r["avg_ticket"] is not None else 0.0
        )
        # 折扣率 = 折扣额 / 折前营业额 * 100. Decimal division then round to 1
        # decimal place (matches the FE `.toFixed(1)` display). gross<=0 → 0.0.
        discount_amt = (
            Decimal(str(r["discount_amount"])) if r["discount_amount"] is not None
            else Decimal("0")
        )
        gross_amt = (
            Decimal(str(r["gross_amount"])) if r["gross_amount"] is not None
            else Decimal("0")
        )
        discount_pct = (
            float((discount_amt / gross_amt * 100).quantize(
                Decimal("0.1"), rounding=ROUND_HALF_UP))
            if gross_amt > 0 else 0.0
        )
        rev_vals.append(revenue)
        stores.append({
            "name": r["name"],
            "revenue": revenue,
            "orderCount": order_count,
            "avgTicket": avg_ticket,
            "discountPct": discount_pct,
        })

    median_revenue = _median(rev_vals)
    weak_stores = [s["name"] for s in stores if s["revenue"] < median_revenue]

    return {
        "factory_id": factory_id,
        "start_date": start.isoformat() if start is not None else None,
        "end_date": end.isoformat() if end is not None else None,
        "stores": stores,
        "medianRevenue": median_revenue,
        "weakStores": weak_stores,
    }


async def trend_bundle(
    pool: asyncpg.Pool,
    factory_id: str,
    date_range: Tuple[Optional[date], Optional[date]],
) -> Dict[str, Any]:
    """趋势分析合一 — one bundle so the FE makes a single round-trip.

    Combines three trend views over agg_daily, all sharing the same optional
    date window (None bound = all history on that side):
      - dailyTrend     : per-day {date, revenue, bill_count} (same shape as
                         daily_trend's points), ascending.
      - weekdayWeekend : per-day-average revenue split into weekday vs weekend
                         (Sun=0 / Sat=6 via EXTRACT(DOW) IN (0,6)). Average =
                         total revenue / DISTINCT day count in each group.
      - monthlyTrend   : SUM(revenue) grouped by date_trunc('month', date),
                         ascending, as [{month: 'YYYY-MM', revenue}].

    Honest empty: no rows → dailyTrend=[], monthlyTrend=[], and the
    weekdayWeekend averages/day-counts are 0.
    """
    start, end = date_range
    _validate_range(start, end)

    # Build a shared date-filter fragment + param list (None bound = no filter).
    conds = ["factory_id = $1"]
    params: list = [factory_id]
    if start is not None:
        params.append(start)
        conds.append(f"date >= ${len(params)}")
    if end is not None:
        params.append(end)
        conds.append(f"date <= ${len(params)}")
    where = " AND ".join(conds)

    async with pool.acquire() as conn:
        daily_rows = await conn.fetch(
            f"""
            SELECT date,
                   SUM(net_amount)::numeric(18,2) AS revenue,
                   SUM(bill_count)                AS bill_count
              FROM agg_daily
             WHERE {where}
             GROUP BY date
             ORDER BY date
            """,
            *params,
        )
        # Weekday vs weekend: aggregate per-day revenue first, then group by the
        # weekend flag so day_count is DISTINCT days (a day with multiple store
        # rows counts once).
        ww_rows = await conn.fetch(
            f"""
            WITH per_day AS (
                SELECT date,
                       SUM(net_amount)::numeric(18,2) AS day_revenue
                  FROM agg_daily
                 WHERE {where}
                 GROUP BY date
            )
            SELECT (EXTRACT(DOW FROM date) IN (0, 6)) AS is_weekend,
                   COALESCE(SUM(day_revenue), 0)::numeric(18,2) AS total_revenue,
                   COUNT(*)                                     AS day_count
              FROM per_day
             GROUP BY (EXTRACT(DOW FROM date) IN (0, 6))
            """,
            *params,
        )
        monthly_rows = await conn.fetch(
            f"""
            SELECT date_trunc('month', date)::date AS month,
                   SUM(net_amount)::numeric(18,2)  AS revenue
              FROM agg_daily
             WHERE {where}
             GROUP BY date_trunc('month', date)
             ORDER BY date_trunc('month', date)
            """,
            *params,
        )

    daily_trend_points = []
    for r in daily_rows:
        rev = Decimal(str(r["revenue"])) if r["revenue"] is not None else Decimal("0")
        bc = int(r["bill_count"]) if r["bill_count"] is not None else 0
        daily_trend_points.append({
            "date": r["date"].isoformat(),
            "revenue": float(rev),
            "bill_count": bc,
        })

    weekday_total = Decimal("0")
    weekday_days = 0
    weekend_total = Decimal("0")
    weekend_days = 0
    for r in ww_rows:
        total = Decimal(str(r["total_revenue"])) if r["total_revenue"] is not None else Decimal("0")
        days = int(r["day_count"]) if r["day_count"] is not None else 0
        if r["is_weekend"]:
            weekend_total += total
            weekend_days += days
        else:
            weekday_total += total
            weekday_days += days
    weekday_avg = float((weekday_total / weekday_days)) if weekday_days > 0 else 0.0
    weekend_avg = float((weekend_total / weekend_days)) if weekend_days > 0 else 0.0

    monthly_trend = [
        {
            "month": r["month"].strftime("%Y-%m"),
            "revenue": float(Decimal(str(r["revenue"]))) if r["revenue"] is not None else 0.0,
        }
        for r in monthly_rows
    ]

    return {
        "factory_id": factory_id,
        "start_date": start.isoformat() if start is not None else None,
        "end_date": end.isoformat() if end is not None else None,
        "dailyTrend": daily_trend_points,
        "weekdayWeekend": {
            "weekdayAvg": round(weekday_avg, 2),
            "weekendAvg": round(weekend_avg, 2),
            "weekdayDays": weekday_days,
            "weekendDays": weekend_days,
        },
        "monthlyTrend": monthly_trend,
    }


# ── G2: Restaurant Target Achievement Queries ─────────────────────────────────


def _period_key_for_target(d: date, level: str) -> str:
    """Generate period_key string matching restaurant_target_hierarchy.period_key.

    Rule 2 (python-java-port.md): WEEK uses calendar year (d.year), not ISO year
    (isocalendar()[0]) to match Java LocalDate.getYear() semantics.
    """
    if level == "day":
        return d.isoformat()          # '2026-06-03'
    if level == "week":
        _, iso_week, _ = d.isocalendar()
        return f"{d.year}-W{iso_week:02d}"   # calendar year, NOT iso_year
    if level == "month":
        return d.strftime("%Y-%m")    # '2026-06'
    if level == "year":
        return str(d.year)            # '2026'
    raise ValueError(f"unknown level: {level!r}")


def _period_bounds(period_key: str, level: str) -> Tuple[date, date]:
    """Return (first_day, last_day) of the calendar period a period_key spans.

    Used to detect in-progress (incomplete) periods: a period is incomplete
    when its last_day is still in the future relative to today, so a week/month
    rate computed from only the elapsed days would otherwise under-count
    against the full-period target (false low-achievement signal).

    WEEK uses calendar year (Rule 2, mirrors _period_key_for_target): the key
    is 'YYYY-Www' where YYYY is the calendar year of any day in that ISO week.
    """
    import calendar
    from datetime import timedelta

    if level == "day":
        d = date.fromisoformat(period_key)
        return d, d
    if level == "month":
        year, month = (int(x) for x in period_key.split("-"))
        last = calendar.monthrange(year, month)[1]
        return date(year, month, 1), date(year, month, last)
    if level == "year":
        year = int(period_key)
        return date(year, 1, 1), date(year, 12, 31)
    if level == "week":
        # key 'YYYY-Www' — YYYY is the calendar year (Rule 2), ww is ISO week.
        year_str, week_str = period_key.split("-W")
        cal_year = int(year_str)
        iso_week = int(week_str)
        # Find the Monday of the ISO week. The ISO year may differ from the
        # calendar year at boundaries, so search around cal_year for the day
        # whose _period_key_for_target round-trips to this key.
        for probe_year in (cal_year, cal_year - 1, cal_year + 1):
            try:
                monday = date.fromisocalendar(probe_year, iso_week, 1)
            except ValueError:
                continue
            if _period_key_for_target(monday, "week") == period_key:
                return monday, monday + timedelta(days=6)
        # Fallback: best-effort Monday of the ISO week in cal_year.
        monday = date.fromisocalendar(cal_year, iso_week, 1)
        return monday, monday + timedelta(days=6)
    raise ValueError(f"unknown level: {level!r}")


def _compute_achievement_rate(
    actual: Optional[Decimal], target: Optional[Decimal]
) -> Optional[float]:
    """Compute achievement rate with ROUND_HALF_UP (Rule 10/12).

    Returns None if target is None, target==0, or actual is None (data_missing).
    Never raises ZeroDivisionError.
    """
    if actual is None or target is None or target == Decimal("0"):
        return None
    # Rule 10: intermediate quantize at 4 dp HALF_UP, then final 3 dp HALF_UP
    intermediate = (actual / target).quantize(Decimal("0.0001"), rounding=ROUND_HALF_UP)
    result = intermediate.quantize(Decimal("0.001"), rounding=ROUND_HALF_UP)
    return float(result)


async def _set_target_tenant(conn: asyncpg.Connection, factory_id: str) -> None:
    """Set RLS tenant context on a borrowed connection (parameterized, injection-safe).

    Mirrors restaurant_finance_etl._set_tenant / restaurant_ops_gold endpoints.
    """
    await conn.execute("SELECT set_config('app.factory_id', $1, true)", factory_id)


async def daily_achievement_summary(
    pool: asyncpg.Pool,
    factory_id: str,
    date_range: Tuple[date, date],
    *,
    kpi_kind: str = "revenue",
    level: str = "day",
    store_id: Optional[int] = None,
) -> Dict[str, Any]:
    """Query actual POS data vs. stored targets for the given date range and level.

    Returns {factory_id, kpi_kind, level, points: [...], period_without_target: [...]}
    - actual=null + data_missing=True when agg_daily has no row (POS fault)
    - achievement_rate=null when target=null or target=0 (prevent false 0% signal)
    """
    from smartbi_compat.api.analysis_finance import _decimal_to_number

    if not factory_id:
        raise ValueError("factory_id required")
    start, end = date_range
    if start is None or end is None:
        raise ValueError(
            f"daily_achievement_summary: start/end required (got {start}, {end})"
        )
    _validate_range(start, end)

    from datetime import timedelta

    # Derive period keys for the range at the given level (preserve order)
    period_keys: list[str] = []
    current = start
    while current <= end:
        pk = _period_key_for_target(current, level)
        if pk not in period_keys:
            period_keys.append(pk)
        current += timedelta(days=1)

    agg_col = "net_amount" if kpi_kind == "revenue" else "bill_count"

    async with pool.acquire() as conn:
        await _set_target_tenant(conn, factory_id)

        # Targets for the period_keys. store_id is a first-class dimension (D4).
        if store_id is None:
            target_rows = await conn.fetch(
                """
                SELECT period_key, target_value, store_id
                  FROM restaurant_target_hierarchy
                 WHERE factory_id = $1
                   AND kpi_kind = $2
                   AND level = $3
                   AND period_key = ANY($4)
                   AND store_id IS NULL
                """,
                factory_id, kpi_kind, level, period_keys,
            )
        else:
            target_rows = await conn.fetch(
                """
                SELECT period_key, target_value, store_id
                  FROM restaurant_target_hierarchy
                 WHERE factory_id = $1
                   AND kpi_kind = $2
                   AND level = $3
                   AND period_key = ANY($4)
                   AND store_id = $5
                """,
                factory_id, kpi_kind, level, period_keys, store_id,
            )
        target_map: dict[str, Decimal] = {
            r["period_key"]: Decimal(str(r["target_value"]))
            for r in target_rows
            if r["target_value"] is not None
        }

        # Actuals from agg_daily grouped by date (then bucketed into period_key)
        if store_id is None:
            actual_rows = await conn.fetch(
                f"""
                SELECT date, SUM({agg_col})::numeric(18,2) AS actual
                  FROM agg_daily
                 WHERE factory_id = $1
                   AND date BETWEEN $2 AND $3
                 GROUP BY date
                 ORDER BY date
                """,
                factory_id, start, end,
            )
        else:
            actual_rows = await conn.fetch(
                f"""
                SELECT date, SUM({agg_col})::numeric(18,2) AS actual
                  FROM agg_daily
                 WHERE factory_id = $1
                   AND date BETWEEN $2 AND $3
                   AND store_id = $4
                 GROUP BY date
                 ORDER BY date
                """,
                factory_id, start, end, store_id,
            )

    actual_map: dict[str, Decimal] = {}
    actual_period_keys: set[str] = set()
    for r in actual_rows:
        pk = _period_key_for_target(r["date"], level)
        if r["actual"] is None:
            continue
        v = Decimal(str(r["actual"]))
        actual_map[pk] = actual_map.get(pk, Decimal("0")) + v
        actual_period_keys.add(pk)

    points = []
    period_without_target: list[str] = []
    today = date.today()

    for pk in period_keys:
        target = target_map.get(pk)
        if target is None:
            period_without_target.append(pk)
            continue

        has_actual = pk in actual_period_keys
        actual_val = actual_map.get(pk) if has_actual else None
        rate = _compute_achievement_rate(actual_val, target)

        # In-progress detection: a week/month/year period whose last calendar
        # day is still in the future is INCOMPLETE — its actuals only cover the
        # elapsed days, so comparing to the full-period target under-counts the
        # rate (false low-achievement alarm). Flag it so the UI shows
        # "进行中 (已过 N/总 M 天)" instead of a bare percentage / alert color.
        p_first, p_last = _period_bounds(pk, level)
        period_complete = p_last <= today
        days_total = (p_last - p_first).days + 1
        if period_complete:
            days_elapsed = days_total
        else:
            # clamp elapsed to [0, days_total]
            elapsed_until = min(today, p_last)
            days_elapsed = max(0, (elapsed_until - p_first).days + 1)
            days_elapsed = min(days_elapsed, days_total)

        points.append({
            "period_key": pk,
            "target": _decimal_to_number(target),
            "actual": _decimal_to_number(actual_val) if actual_val is not None else None,
            "achievement_rate": rate,
            "data_missing": not has_actual,
            "period_complete": period_complete,
            "in_progress": not period_complete,
            "days_elapsed": days_elapsed,
            "days_total": days_total,
        })

    return {
        "factory_id": factory_id,
        "kpi_kind": kpi_kind,
        "level": level,
        "points": points,
        "period_without_target": period_without_target,
    }


async def hierarchy_rollup(
    pool: asyncpg.Pool,
    factory_id: str,
    year: int,
    *,
    kpi_kind: str = "revenue",
) -> Dict[str, Any]:
    """Return the four-level target tree for a given year plus YTD actuals."""
    from smartbi_compat.api.analysis_finance import _decimal_to_number

    if not factory_id:
        raise ValueError("factory_id required")

    async with pool.acquire() as conn:
        await _set_target_tenant(conn, factory_id)

        target_rows = await conn.fetch(
            """
            SELECT level, period_key, target_value, store_id
              FROM restaurant_target_hierarchy
             WHERE factory_id = $1
               AND kpi_kind = $2
               AND (period_key = $3
                    OR period_key LIKE $4
                    OR period_key LIKE $5)
             ORDER BY level, period_key
            """,
            factory_id, kpi_kind,
            str(year),            # year level
            f"{year}-%",          # month / day level: '2026-01' … '2026-06-03'
            f"{year}-W%",         # week level: '2026-W23'
        )

        actual_rows = await conn.fetch(
            """
            SELECT SUM(net_amount)::numeric(18,2) AS actual_ytd
              FROM agg_daily
             WHERE factory_id = $1
               AND EXTRACT(YEAR FROM date) = $2
            """,
            factory_id, year,
        )

    year_target: Optional[Decimal] = None
    months: list[dict] = []
    actual_ytd: Optional[Decimal] = None
    if actual_rows and actual_rows[0]["actual_ytd"] is not None:
        actual_ytd = Decimal(str(actual_rows[0]["actual_ytd"]))

    for r in target_rows:
        if r["target_value"] is None:
            continue
        tv = Decimal(str(r["target_value"]))
        if r["level"] == "year":
            year_target = tv
        elif r["level"] == "month":
            months.append({
                "period_key": r["period_key"],
                "target": _decimal_to_number(tv),
                "actual_ytd": _decimal_to_number(actual_ytd) if actual_ytd is not None else None,
            })

    return {
        "factory_id": factory_id,
        "year": year,
        "kpi_kind": kpi_kind,
        "year_target": _decimal_to_number(year_target) if year_target is not None else None,
        "months": months,
    }


async def alert_preview(
    pool: asyncpg.Pool,
    factory_id: str,
    lookback_days: int = 7,
    *,
    kpi_kind: str = "revenue",
) -> Dict[str, Any]:
    """Return N-day alert timeline. Fail-closed: no alert_config row → config_exists=False."""
    if not factory_id:
        raise ValueError("factory_id required")

    from datetime import timedelta

    end = date.today()
    start = end - timedelta(days=lookback_days - 1)

    async with pool.acquire() as conn:
        await _set_target_tenant(conn, factory_id)
        config_rows = await conn.fetch(
            """
            SELECT warn_threshold, critical_threshold, store_id
              FROM restaurant_alert_config
             WHERE factory_id = $1
               AND kpi_kind = $2
               AND level = 'day'
             LIMIT 1
            """,
            factory_id, kpi_kind,
        )

    if not config_rows:
        return {
            "factory_id": factory_id,
            "kpi_kind": kpi_kind,
            "lookback_days": lookback_days,
            "config_exists": False,
            "timeline": [],
            "summary": {},
        }

    cfg = config_rows[0]
    warn_t = Decimal(str(cfg["warn_threshold"]))
    crit_t = Decimal(str(cfg["critical_threshold"]))

    summary_result = await daily_achievement_summary(
        pool, factory_id, (start, end), kpi_kind=kpi_kind, level="day",
    )

    point_map = {pt["period_key"]: pt for pt in summary_result["points"]}
    no_target_set = set(summary_result["period_without_target"])

    timeline: list[dict] = []
    summary: dict[str, int] = {
        "OK": 0, "WARN": 0, "CRITICAL": 0, "NO_TARGET": 0, "DATA_MISSING": 0
    }

    cur = start
    while cur <= end:
        pk = cur.isoformat()
        if pk in no_target_set or pk not in point_map:
            status = "NO_TARGET"
            entry = {
                "date": pk, "achievement_rate": None, "status": status,
                "target": None, "actual": None,
            }
        else:
            pt = point_map[pk]
            if pt["data_missing"]:
                status = "DATA_MISSING"
            elif pt["achievement_rate"] is None:
                status = "NO_TARGET"
            else:
                rate = Decimal(str(pt["achievement_rate"]))
                if rate < crit_t:
                    status = "CRITICAL"
                elif rate < warn_t:
                    status = "WARN"
                else:
                    status = "OK"
            entry = {
                "date": pk,
                "achievement_rate": pt["achievement_rate"],
                "status": status,
                "target": pt["target"],
                "actual": pt["actual"],
            }
        timeline.append(entry)
        summary[status] = summary.get(status, 0) + 1
        cur += timedelta(days=1)

    return {
        "factory_id": factory_id,
        "kpi_kind": kpi_kind,
        "lookback_days": lookback_days,
        "config_exists": True,
        "timeline": timeline,
        "summary": summary,
    }


# ── void_rate / void_audit (撤单率 / 撤单稽核) ──────────────────────
# New restaurant analytics dimension (greenfield). Source: agg_daily_void
# (materialized from fact_pos_void, see
# smartbi/services/materialized_analytics/daily_void.py). void_rate reads
# agg_daily's bill_count as the denominator (same Gold table finance_summary
# / order_type_mix already read for bill totals).

# Min bills in the window before we'll report a 撤单率. Below this, a couple
# of voids swing the % wildly and could falsely trip "撤单率过高 critical" on a
# partial/tiny bill load. Skip honestly instead. (Mirrored, deliberately
# duplicated, in health_check_metrics.py's _VOID_MIN_BILLS — two independent
# modules, not worth cross-importing a single int.)
_VOID_MIN_BILLS = 50


async def void_rate(
    pool: asyncpg.Pool,
    factory_id: str,
    date_range: Tuple[Optional[date], Optional[date]],
) -> Dict[str, Any]:
    """撤单率 = void_count / bill_count over the given date range.

    Honesty guards (never fabricate a 0% "healthy" for a tenant with no void
    data):
      - data_available=False (tenant has ZERO agg_daily_void rows at all) →
        void_rate=None, note="未上传撤单数据". Distinguishes "never uploaded a
        撤单报表" from a genuine 0 voids in the window.
      - bill_count < _VOID_MIN_BILLS → void_rate=None, note explains the
        sample is too small (avoids a false "撤单率过高" off a couple voids).

    Returns:
      - void_count, bill_count (raw totals)
      - void_rate: percentage (0-100 scale, matches discount_rate's scale),
        None when a guard fires
      - data_available: bool — whether the tenant has any void data at all
      - note: explains why void_rate is None, if applicable
    """
    start, end = date_range
    _validate_range(start, end)
    conds = ["factory_id = $1"]
    params: list = [factory_id]
    if start is not None:
        params.append(start)
        conds.append(f"date >= ${len(params)}")
    if end is not None:
        params.append(end)
        conds.append(f"date <= ${len(params)}")
    where = " AND ".join(conds)

    async with pool.acquire() as conn:
        # All-history availability (NOT date-filtered): does this tenant have
        # ANY void data? Separates "未上传撤单数据" from "0 voids this window".
        avail_row = await conn.fetchrow(
            "SELECT EXISTS(SELECT 1 FROM agg_daily_void WHERE factory_id = $1) AS has_data",
            factory_id,
        )
        void_row = await conn.fetchrow(
            f"SELECT COALESCE(SUM(void_count), 0) AS void_count FROM agg_daily_void WHERE {where}",
            *params,
        )
        bill_row = await conn.fetchrow(
            f"SELECT COALESCE(SUM(bill_count), 0) AS bill_count FROM agg_daily WHERE {where}",
            *params,
        )

    data_available = bool(avail_row["has_data"]) if avail_row else False
    void_count = int(void_row["void_count"] or 0) if void_row else 0
    bill_count = int(bill_row["bill_count"] or 0) if bill_row else 0

    rate: Optional[Decimal] = None
    note: Optional[str] = None
    if not data_available:
        note = "未上传撤单数据"
    elif bill_count < _VOID_MIN_BILLS:
        note = f"订单样本不足(<{_VOID_MIN_BILLS}单)，撤单率不稳定，暂不计算。"
    else:
        rate = (Decimal(void_count) / Decimal(bill_count) * Decimal(100)).quantize(
            Decimal("0.01"), rounding=ROUND_HALF_UP
        )

    return {
        "factory_id": factory_id,
        "start_date": start.isoformat() if start is not None else None,
        "end_date": end.isoformat() if end is not None else None,
        "void_count": void_count,
        "bill_count": bill_count,
        "void_rate": float(rate) if rate is not None else None,
        "data_available": data_available,
        "note": note,
    }


async def void_audit(
    pool: asyncpg.Pool,
    factory_id: str,
    date_range: Tuple[Optional[date], Optional[date]],
    *,
    top_n: int = 10,
) -> Dict[str, Any]:
    """撤单稽核: per-(staff, store) void breakdown, top N by void RATE.

    ⚠️ This NAMES employees to the boss — treated as a red-line surface.
    Two anti-false-accusation designs (per Fable gate SHOULD-FIX 4):

    (a) GROUP BY staff_id (the real surrogate key), NOT the display name.
        Two different 张伟 at two stores have distinct staff_ids; grouping by
        the shared display name would MERGE their voids into one inflated row
        falsely blaming one person. We also surface store_name so a named row
        is anchored to a specific store.

    (b) Rank by voids-per-100-bills-handled (a RATE), not raw 撤单笔数. Raw
        count structurally tops whoever AUTHORIZES the most voids (收银主管/
        店长) — that's authority, not misconduct. Normalizing by each staff's
        own handled-bill volume (fact_pos_transaction bill count for that
        staff_id at that store) makes it a fair rate. Unattributed voids
        (staff_id=0) have no matching bills → rate None → sorted last.

    Data source note: agg_daily_void.staff_id is the POS operator who
    processed the void, NOT necessarily who caused it — hence the caveat.

    Returns:
      - total_void_count: sum across the WHOLE range (not just top N)
      - breakdown: list of {staff_name, store_name, void_count, bills_handled,
        voids_per_100_bills (None if bills_handled=0), top_reason (None when
        the '未标注' sentinel is the dominant reason)} ranked by rate desc.
      - caveat: honest disclaimer about data meaning
    """
    start, end = date_range
    _validate_range(start, end)
    # Bare column WHERE (no table alias) — reused verbatim inside each CTE,
    # each of which selects from a single table, so factory_id/date/staff_id
    # are unambiguous.
    conds: list = ["factory_id = $1"]
    params: list = [factory_id]
    if start is not None:
        params.append(start)
        conds.append(f"date >= ${len(params)}")
    if end is not None:
        params.append(end)
        conds.append(f"date <= ${len(params)}")
    where = " AND ".join(conds)

    params_top = list(params)
    params_top.append(int(top_n))
    limit_ph = f"${len(params_top)}"

    async with pool.acquire() as conn:
        total_row = await conn.fetchrow(
            f"SELECT COALESCE(SUM(void_count), 0) AS total FROM agg_daily_void WHERE {where}",
            *params,
        )
        rows = await conn.fetch(
            f"""
            WITH ssr AS (   -- voids per (staff, store, reason)
                SELECT staff_id, store_id, void_reason, SUM(void_count) AS vc
                  FROM agg_daily_void
                 WHERE {where}
                 GROUP BY staff_id, store_id, void_reason
            ),
            ss AS (         -- collapse to (staff, store): total voids + dominant reason
                SELECT staff_id, store_id,
                       SUM(vc)                                    AS void_count,
                       (array_agg(void_reason ORDER BY vc DESC))[1] AS top_reason
                  FROM ssr
                 GROUP BY staff_id, store_id
            ),
            handled AS (    -- bills each staff handled at each store (rate denominator)
                SELECT staff_id, store_id, COUNT(*) AS bills
                  FROM fact_pos_transaction
                 WHERE {where} AND staff_id IS NOT NULL
                 GROUP BY staff_id, store_id
            )
            SELECT CASE WHEN ss.staff_id = 0 THEN '未知'
                        ELSE COALESCE(st.name, ss.staff_id::text) END AS staff_name,
                   COALESCE(ds.name, ss.store_id::text)               AS store_name,
                   ss.void_count                                       AS void_count,
                   ss.top_reason                                       AS top_reason,
                   COALESCE(h.bills, 0)                                AS bills
              FROM ss
              LEFT JOIN dim_staff st ON st.staff_id = ss.staff_id AND st.factory_id = $1
              LEFT JOIN dim_store ds ON ds.store_id = ss.store_id AND ds.factory_id = $1
              LEFT JOIN handled  h  ON h.staff_id  = ss.staff_id AND h.store_id  = ss.store_id
             ORDER BY CASE WHEN COALESCE(h.bills, 0) > 0
                           THEN ss.void_count::numeric / h.bills
                           ELSE -1 END DESC,
                      ss.void_count DESC
             LIMIT {limit_ph}
            """,
            *params_top,
        )

    breakdown = []
    for r in rows:
        bills = int(r["bills"] or 0)
        vc = int(r["void_count"])
        rate = (
            float((Decimal(vc) / Decimal(bills) * Decimal(100)).quantize(
                Decimal("0.01"), rounding=ROUND_HALF_UP))
            if bills > 0 else None
        )
        breakdown.append({
            "staff_name": r["staff_name"],
            "store_name": r["store_name"],
            "void_count": vc,
            "bills_handled": bills,
            "voids_per_100_bills": rate,
            "top_reason": r["top_reason"] if r["top_reason"] != "未标注" else None,
        })

    return {
        "factory_id": factory_id,
        "start_date": start.isoformat() if start is not None else None,
        "end_date": end.isoformat() if end is not None else None,
        "total_void_count": int(total_row["total"] or 0) if total_row else 0,
        "breakdown": breakdown,
        "caveat": (
            "撤单按 POS 操作人记账(非顾客本人/服务员撤单原因确认); 操作人缺失时归为「未知」。"
            "「每百单撤单」= 撤单次数 ÷ 该操作人经手订单数 × 100，用于剔除授权量差异"
            "(收银主管/店长授权撤单本就多，不等于违规); 应按此率而非撤单总数判断。"
        ),
    }


# ── zone_efficiency (区域坪效) ──────────────────────────────────────
# New restaurant analytics dimension (greenfield). Source: agg_daily_zone
# (materialized from fact_zone_sales, see
# smartbi/services/materialized_analytics/daily_zone.py).


async def zone_efficiency(
    pool: asyncpg.Pool,
    factory_id: str,
    date_range: Tuple[Optional[date], Optional[date]],
    *,
    top_n: int = 10,
) -> Dict[str, Any]:
    """区域坪效 (in-store dining-zone revenue/efficiency) — revenue + item
    quantity ranked by 区域名称 (dining zone: 大厅/小桌/中桌/大桌/... or, in
    the real source data, delivery-channel labels — see caveat below).

    ⚠️ Honesty guard: literal 坪效 (revenue per unit FLOOR AREA, 元/平米) is
    NOT computable — the 二维火 "区域销售报表" export carries no floor-area
    or seat-count column for any zone. This returns revenue + item quantity
    per zone as an EFFICIENCY PROXY (which zones generate the most revenue /
    turn the most items), never a fabricated per-square-meter figure.

    Honesty guards (never fabricate zeros for a tenant with no zone data):
      - data_available=False (tenant has ZERO agg_daily_zone rows at all) →
        note="未上传区域销售数据". Distinguishes "never uploaded a 区域销售
        报表" from a genuine zero-revenue window.

    Returns:
      - data_available: bool — whether the tenant has any zone-sales data
        at all
      - total_revenue / total_item_qty: sums across the WHOLE range (not
        just top N)
      - zones: list of {zone_name, revenue, item_qty, revenue_pct} ranked
        by revenue desc (revenue_pct is null when total_revenue is 0 —
        avoids a divide-by-zero fabricated 0%)
      - note: explains why the tenant has no data, if applicable
      - caveat: honest disclaimer (proxy metric + delivery-channel-labeled
        zone names)
    """
    start, end = date_range
    _validate_range(start, end)
    conds = ["factory_id = $1"]
    params: list = [factory_id]
    if start is not None:
        params.append(start)
        conds.append(f"date >= ${len(params)}")
    if end is not None:
        params.append(end)
        conds.append(f"date <= ${len(params)}")
    where = " AND ".join(conds)

    params_top = list(params)
    params_top.append(int(top_n))
    limit_ph = f"${len(params_top)}"

    async with pool.acquire() as conn:
        # All-history availability (NOT date-filtered): does this tenant have
        # ANY zone-sales data? Separates "未上传区域销售数据" from "0 revenue
        # this window".
        avail_row = await conn.fetchrow(
            "SELECT EXISTS(SELECT 1 FROM agg_daily_zone WHERE factory_id = $1) AS has_data",
            factory_id,
        )
        total_row = await conn.fetchrow(
            f"SELECT COALESCE(SUM(revenue), 0) AS total_revenue, "
            f"COALESCE(SUM(item_qty), 0) AS total_item_qty "
            f"FROM agg_daily_zone WHERE {where}",
            *params,
        )
        rows = await conn.fetch(
            f"""
            SELECT zone_name,
                   SUM(revenue)  AS revenue,
                   SUM(item_qty) AS item_qty
              FROM agg_daily_zone
             WHERE {where}
             GROUP BY zone_name
             ORDER BY SUM(revenue) DESC
             LIMIT {limit_ph}
            """,
            *params_top,
        )

    data_available = bool(avail_row["has_data"]) if avail_row else False
    total_revenue = Decimal(total_row["total_revenue"] or 0) if total_row else Decimal(0)
    total_item_qty = Decimal(total_row["total_item_qty"] or 0) if total_row else Decimal(0)

    zones = []
    for r in rows:
        rev = Decimal(r["revenue"] or 0)
        qty = Decimal(r["item_qty"] or 0)
        pct = (
            (rev / total_revenue * Decimal(100)).quantize(
                Decimal("0.01"), rounding=ROUND_HALF_UP
            )
            if total_revenue > 0
            else None
        )
        zones.append({
            "zone_name": r["zone_name"],
            "revenue": float(rev),
            "item_qty": float(qty),
            "revenue_pct": float(pct) if pct is not None else None,
        })

    note: Optional[str] = None if data_available else "未上传区域销售数据"

    return {
        "factory_id": factory_id,
        "start_date": start.isoformat() if start is not None else None,
        "end_date": end.isoformat() if end is not None else None,
        "data_available": data_available,
        "note": note,
        "total_revenue": float(total_revenue),
        "total_item_qty": float(total_item_qty),
        "zones": zones,
        "caveat": (
            "坪效为营收/数量代理指标：源数据无场地面积字段，无法计算真实的元/平米坪效；"
            "部分区域名称代表外卖渠道（无桌位(美团外卖)/无桌位(饿了么外卖)/无桌位(京东外卖)/"
            "外卖/京东/饿了么等），并非实体大厅/包间，对比时请结合门店实际场地情况参考。"
        ),
    }


# ── member_profile (会员储值 + 画像) ──────────────────────────────
# New restaurant analytics dimension (greenfield). Source: agg_member_tier +
# agg_member_gender + agg_member_birth_month (snapshot, materialized from
# dim_member) + agg_member_recharge_daily (daily, materialized from
# fact_member_recharge). See smartbi/services/materialized_analytics/
# member_profile.py.
#
# ⛔ NOT FULL RFM: the source (二维火 卡详情一览 + 卡充值统计 exports) has no
# per-member consumption event — no per-order timestamp tied to a card_no —
# so recency/frequency cannot be computed. Only stored-value (储值余额/
# 充值趋势) and demographic profile (等级/性别/生日月份分布) are available.
# member_rfm.py (smartbi/services/restaurant/member_rfm.py) is a SEPARATE,
# unrelated analyzer that operates on POS order-level data when a caller
# supplies it directly — this query does not feed it and does not claim RFM.
#
# 🔒 All source tables are aggregate-only by construction (no card_no/name/
# phone/full-birthdate column exists anywhere in dim_member or
# fact_member_recharge — see V20261007_01's header note). On TOP of that,
# this query enforces k-anonymity (k=5): any tier/gender cohort smaller than
# 5 is merged into an 其他 bucket, and no balance is ever attributable to a
# cohort < 5 — so a future tenant's 1-member exclusive-tier VIP can't have
# their exact balance read off the API.

# k-anonymity threshold. Cohorts (tier / gender / birth-month bucket) smaller
# than this never surface an individually-attributable count or balance.
_MEMBER_K_ANON = 5


def _k_anon_merge_categorical(
    rows: list,
    *,
    label_key: str,
    other_label: str = "其他",
    with_balance: bool = False,
) -> list:
    """Merge any categorical bucket with member_count < _MEMBER_K_ANON into a
    single ``其他`` bucket (summing counts, and balances when with_balance).

    If the merged ``其他`` bucket is itself still < k, its total_balance is
    nulled (belt-and-suspenders: a balance is NEVER attributable to a cohort
    smaller than k — the count alone, on a generic ``其他`` label, is not
    individually identifying). Buckets >= k pass through unchanged.
    Returns big buckets (original order) followed by the ``其他`` bucket, if any.
    """
    big: list = []
    small_count = 0
    small_balance = 0.0
    for r in rows:
        cnt = int(r["member_count"] or 0)
        entry: Dict[str, Any] = {label_key: r[label_key], "member_count": cnt}
        if with_balance:
            entry["total_balance"] = float(r["total_balance"] or 0)
        if cnt >= _MEMBER_K_ANON:
            big.append(entry)
        else:
            small_count += cnt
            if with_balance:
                small_balance += float(r["total_balance"] or 0)
    if small_count > 0:
        other: Dict[str, Any] = {label_key: other_label, "member_count": small_count}
        if with_balance:
            # If even the merged 其他 bucket is < k, suppress the balance.
            other["total_balance"] = (
                small_balance if small_count >= _MEMBER_K_ANON else None
            )
        big.append(other)
    return big


async def member_profile(
    pool: asyncpg.Pool,
    factory_id: str,
    date_range: Tuple[Optional[date], Optional[date]],
) -> Dict[str, Any]:
    """会员储值 + 画像: tier / gender / birth-month distribution + stored-value
    totals (snapshot) + recharge trend (date-ranged).

    Honesty guard: data_available=False when the tenant has ZERO
    agg_member_tier rows (never uploaded 卡详情/卡充值 data) → all snapshot
    fields are empty/zero and note explains why, rather than a fabricated
    empty distribution indistinguishable from "uploaded but genuinely no
    members".

    k-anonymity (k=5): tier_distribution / gender_distribution merge sub-5
    cohorts into 其他 (and never expose a balance for a sub-5 cohort);
    birth_month_distribution drops sub-5 month buckets (counted honestly in
    birth_month_suppressed_count) so no tiny cohort is individually exposed.

    Returns:
      - data_available: bool — whether the tenant has any member data at all
      - member_count: snapshot total across all tiers/stores
      - total_balance: snapshot balance total (nulled if member_count < k)
      - tier_distribution: [{tier, member_count, total_balance}, ...] (k-anon)
      - gender_distribution: [{gender, member_count}, ...] (k-anon; 性别画像)
      - birth_month_distribution: [{birth_month (1-12), member_count}, ...]
        (k-anon: only buckets >= 5; 生日营销 targeting list)
      - birth_month_unknown_count: members whose 生日 was blank in the source
      - birth_month_suppressed_count: members in sub-5 month buckets dropped
        from the list (for honest coverage math — see below)
      - birth_month_coverage_pct: % of members with a known birth month
        (0-100, null when there are no members) — F4 honesty disclosure
      - recharge_trend: [{month "YYYY-MM", principal, bonus}, ...] over range
      - recharge_store_count: distinct stores with recharge data in the window
        (the demo source only has recharge for 1 store — surfaced so the
        card can disclose "仅部分门店有充值记录")
      - note: explains why data_available is False, if applicable
    """
    start, end = date_range
    _validate_range(start, end)
    conds = ["factory_id = $1"]
    params: list = [factory_id]
    if start is not None:
        params.append(start)
        conds.append(f"date >= ${len(params)}")
    if end is not None:
        params.append(end)
        conds.append(f"date <= ${len(params)}")
    where = " AND ".join(conds)

    async with pool.acquire() as conn:
        avail_row = await conn.fetchrow(
            "SELECT EXISTS(SELECT 1 FROM agg_member_tier WHERE factory_id = $1) AS has_data",
            factory_id,
        )
        tier_rows = await conn.fetch(
            """
            SELECT tier,
                   SUM(member_count)  AS member_count,
                   SUM(total_balance) AS total_balance
              FROM agg_member_tier
             WHERE factory_id = $1
             GROUP BY tier
             ORDER BY SUM(member_count) DESC
            """,
            factory_id,
        )
        gender_rows = await conn.fetch(
            """
            SELECT gender, member_count
              FROM agg_member_gender
             WHERE factory_id = $1
             ORDER BY member_count DESC
            """,
            factory_id,
        )
        # NO birth_month filter here — we need the 0 (unknown) bucket too, for
        # the coverage disclosure (F4). Split known vs unknown in Python.
        birth_rows = await conn.fetch(
            """
            SELECT birth_month, member_count
              FROM agg_member_birth_month
             WHERE factory_id = $1
             ORDER BY birth_month
            """,
            factory_id,
        )
        recharge_rows = await conn.fetch(
            f"""
            SELECT to_char(date, 'YYYY-MM') AS month,
                   SUM(principal) AS principal,
                   SUM(bonus)     AS bonus
              FROM agg_member_recharge_daily
             WHERE {where}
             GROUP BY to_char(date, 'YYYY-MM')
             ORDER BY month
            """,
            *params,
        )
        recharge_store_row = await conn.fetchrow(
            f"SELECT COUNT(DISTINCT store_id) AS n FROM agg_member_recharge_daily WHERE {where}",
            *params,
        )

    data_available = bool(avail_row["has_data"]) if avail_row else False

    # Tier (with balance) + gender — k-anon merge sub-5 cohorts into 其他.
    tier_distribution = _k_anon_merge_categorical(
        tier_rows, label_key="tier", with_balance=True
    )
    gender_distribution = _k_anon_merge_categorical(
        gender_rows, label_key="gender", with_balance=False
    )

    # Birth month: separate the 0 (unknown) sentinel from real months (1-12),
    # then k-anon-drop sub-5 month buckets (counted in suppressed_count).
    birth_month_unknown_count = 0
    known_month_total = 0
    birth_month_distribution: list = []
    birth_month_suppressed_count = 0
    for r in birth_rows:
        bm = int(r["birth_month"])
        cnt = int(r["member_count"] or 0)
        if bm == 0:
            birth_month_unknown_count += cnt
            continue
        known_month_total += cnt
        if cnt >= _MEMBER_K_ANON:
            birth_month_distribution.append({"birth_month": bm, "member_count": cnt})
        else:
            birth_month_suppressed_count += cnt
    birth_month_distribution.sort(key=lambda x: x["birth_month"])

    total_birth_members = birth_month_unknown_count + known_month_total
    birth_month_coverage_pct: Optional[float] = (
        round(known_month_total / total_birth_members * 100, 1)
        if total_birth_members > 0
        else None
    )

    recharge_trend = [
        {
            "month": r["month"],
            "principal": float(r["principal"] or 0),
            "bonus": float(r["bonus"] or 0),
        }
        for r in recharge_rows
    ]
    recharge_store_count = int(recharge_store_row["n"] or 0) if recharge_store_row else 0

    # Top-level totals: sum from the RAW tier rows (pre-merge) so member_count
    # is the true grand total (the 其他 merge doesn't change the sum).
    member_count = sum(int(r["member_count"] or 0) for r in tier_rows)
    raw_total_balance = sum(float(r["total_balance"] or 0) for r in tier_rows)
    # k-anon on the grand total too: a tenant with < k total members would
    # otherwise expose the aggregate balance of a tiny cohort.
    total_balance: Optional[float] = (
        raw_total_balance if member_count >= _MEMBER_K_ANON else None
    )

    note: Optional[str] = None if data_available else "未上传会员数据"

    return {
        "factory_id": factory_id,
        "start_date": start.isoformat() if start is not None else None,
        "end_date": end.isoformat() if end is not None else None,
        "data_available": data_available,
        "member_count": member_count,
        "total_balance": total_balance,
        "tier_distribution": tier_distribution,
        "gender_distribution": gender_distribution,
        "birth_month_distribution": birth_month_distribution,
        "birth_month_unknown_count": birth_month_unknown_count,
        "birth_month_suppressed_count": birth_month_suppressed_count,
        "birth_month_coverage_pct": birth_month_coverage_pct,
        "recharge_trend": recharge_trend,
        "recharge_store_count": recharge_store_count,
        "note": note,
        "caveat": (
            "本维度为会员储值与画像统计(非完整 RFM): 数据源(卡详情/卡充值报表)不含"
            "逐笔消费记录，无法计算复购间隔(Recency)与消费频次(Frequency)，仅覆盖"
            "储值余额、等级/性别/生日月份分布与充值趋势。为保护会员隐私，人数少于 5 "
            "的分组已合并入「其他」或不单独展示。"
        ),
    }


# ─────────────────────────────────────────────────────────────────────────
# member_rfm — FULL RFM (Recency/Frequency/Monetary), CRM P0.
#
# Unlike member_profile() above (whose source has no per-member consumption
# event — explicitly NOT RFM), this dimension's source (二维火 "卡消费排行")
# IS a per-card cumulative-consumption snapshot, so R/F/M are computed
# directly from fact_member_consumption (V20261008_01) and rolled up by
# smartbi/services/materialized_analytics/member_rfm.py into
# agg_member_rfm_segment / agg_member_rfm_tier / agg_member_lifecycle
# (V20261008_02).
#
# 🔒 k-anonymity (k=5), same threshold/constant as member_profile()
# (_MEMBER_K_ANON, defined above): rfm_tier_distribution and
# lifecycle_distribution merge sub-5 cohorts into 其他 (nulling any money
# field for the merged bucket if it's STILL sub-5); rfm_scatter drops
# (does not merge) sub-5 (r,f,m) buckets entirely — merging a 3D scatter
# point into a generic "其他" location wouldn't correspond to any real
# position on the chart, so those members are counted honestly in
# rfm_scatter_suppressed_count instead (mirrors member_profile()'s
# birth_month_suppressed_count pattern).
# ─────────────────────────────────────────────────────────────────────────


async def member_rfm(pool: asyncpg.Pool, factory_id: str) -> Dict[str, Any]:
    """会员 RFM 分析 — full RFM tier distribution + lifecycle + 3D scatter.

    Honesty guard: data_available=False when the tenant has ZERO
    agg_member_rfm_tier rows (never uploaded 卡消费排行 data) → all fields
    are empty/zero and note explains why, rather than a fabricated empty
    distribution indistinguishable from "uploaded but genuinely no members".

    No date_range parameter: like member_profile()'s tier/gender/
    birth-month dimensions, these three Gold tables are a current-state
    snapshot (fact_member_consumption has no per-row event date to bucket
    by — only cumulative R/F/M fields as of the source export). Recency-
    derived fields (avg_spend_interval, lifecycle_stage) are recomputed
    against CURRENT_DATE at MATERIALIZATION time, not query time — calling
    this query again without re-materializing will NOT re-age members.

    Returns:
      - data_available: bool — whether the tenant has any RFM data at all
      - member_count: snapshot total across all tiers (pre k-anon-merge sum,
        same convention as member_profile()'s grand total)
      - rfm_tier_distribution: [{rfm_tier, member_count, total_cum_spend,
        avg_spend_interval}, ...] (k-anon; avg_spend_interval for a merged
        其他 bucket is a member_count-weighted average across the underlying
        sub-5 tiers, not a naive average-of-averages)
      - lifecycle_distribution: [{lifecycle_stage, member_count,
        total_balance}, ...] (k-anon)
      - rfm_scatter: [{r_score, f_score, m_score, member_count,
        avg_cum_spend}, ...] — only buckets with member_count >= k
      - rfm_scatter_suppressed_count: members in sub-5 (r,f,m) buckets
        dropped from rfm_scatter (honest coverage math, not silently lost)
      - note: explains why data_available is False, if applicable
      - caveat: explains this dimension IS full RFM (contrast with
        member_profile()'s "NOT full RFM" caveat) + the k-anon disclosure
    """
    async with pool.acquire() as conn:
        avail_row = await conn.fetchrow(
            "SELECT EXISTS(SELECT 1 FROM agg_member_rfm_tier WHERE factory_id = $1) AS has_data",
            factory_id,
        )
        # NOTE: unlike member_profile()'s agg_member_tier (PK includes
        # store_id, so a SUM/GROUP BY is needed to roll stores up into a
        # tenant total), all three RFM Gold tables here have NO store
        # dimension in their PK — (factory_id, rfm_tier) /
        # (factory_id, lifecycle_stage) / (factory_id, r_score, f_score,
        # m_score) are each already exactly one row per bucket. Plain
        # SELECTs, no aggregation needed.
        tier_rows = await conn.fetch(
            """
            SELECT rfm_tier, member_count, total_cum_spend, avg_spend_interval
              FROM agg_member_rfm_tier
             WHERE factory_id = $1
             ORDER BY member_count DESC
            """,
            factory_id,
        )
        lifecycle_rows = await conn.fetch(
            """
            SELECT lifecycle_stage, member_count, total_balance
              FROM agg_member_lifecycle
             WHERE factory_id = $1
             ORDER BY member_count DESC
            """,
            factory_id,
        )
        scatter_rows = await conn.fetch(
            """
            SELECT r_score, f_score, m_score, member_count, avg_cum_spend
              FROM agg_member_rfm_segment
             WHERE factory_id = $1
             ORDER BY r_score, f_score, m_score
            """,
            factory_id,
        )

    data_available = bool(avail_row["has_data"]) if avail_row else False

    # rfm_tier_distribution: k-anon merge, weighted-average avg_spend_interval
    # (a naive average-of-averages would misweight tiers of different size).
    rfm_tier_distribution: list = []
    small_tier_count = 0
    small_tier_spend = 0.0
    small_tier_interval_weighted = 0.0
    for r in tier_rows:
        cnt = int(r["member_count"] or 0)
        if cnt >= _MEMBER_K_ANON:
            rfm_tier_distribution.append({
                "rfm_tier": r["rfm_tier"],
                "member_count": cnt,
                "total_cum_spend": float(r["total_cum_spend"] or 0),
                "avg_spend_interval": (
                    round(float(r["avg_spend_interval"]), 1)
                    if r["avg_spend_interval"] is not None else None
                ),
            })
        else:
            small_tier_count += cnt
            small_tier_spend += float(r["total_cum_spend"] or 0)
            if r["avg_spend_interval"] is not None:
                small_tier_interval_weighted += float(r["avg_spend_interval"]) * cnt
    if small_tier_count > 0:
        rfm_tier_distribution.append({
            "rfm_tier": "其他",
            "member_count": small_tier_count,
            "total_cum_spend": (
                small_tier_spend if small_tier_count >= _MEMBER_K_ANON else None
            ),
            "avg_spend_interval": (
                round(small_tier_interval_weighted / small_tier_count, 1)
                if small_tier_count >= _MEMBER_K_ANON else None
            ),
        })

    lifecycle_distribution = _k_anon_merge_categorical(
        lifecycle_rows, label_key="lifecycle_stage", with_balance=True
    )

    # rfm_scatter: DROP (not merge) sub-5 (r,f,m) buckets — no sensible
    # "其他" position exists on a 3D scatter, so suppressed members are
    # counted honestly instead of silently vanishing.
    rfm_scatter: list = []
    rfm_scatter_suppressed_count = 0
    for r in scatter_rows:
        cnt = int(r["member_count"] or 0)
        if cnt >= _MEMBER_K_ANON:
            rfm_scatter.append({
                "r_score": int(r["r_score"]),
                "f_score": int(r["f_score"]),
                "m_score": int(r["m_score"]),
                "member_count": cnt,
                "avg_cum_spend": float(r["avg_cum_spend"] or 0),
            })
        else:
            rfm_scatter_suppressed_count += cnt

    member_count = sum(int(r["member_count"] or 0) for r in tier_rows)
    note: Optional[str] = None if data_available else "未上传会员消费(RFM)数据"

    return {
        "factory_id": factory_id,
        "data_available": data_available,
        "member_count": member_count,
        "rfm_tier_distribution": rfm_tier_distribution,
        "lifecycle_distribution": lifecycle_distribution,
        "rfm_scatter": rfm_scatter,
        "rfm_scatter_suppressed_count": rfm_scatter_suppressed_count,
        "note": note,
        "caveat": (
            "本维度为完整 RFM 分析(Recency/Frequency/Monetary): 数据源(卡消费排行)"
            "为逐卡累计消费快照，R=最近消费距今天数，F=累计消费次数，M=累计消费金额，"
            "按五分位打分(1-5)后映射为 7 大客群标签(Champions/Loyal/Potential/New/"
            "At Risk/Hibernating/Lost)。为保护会员隐私，人数少于 5 的分组已合并入"
            "「其他」(客群/生命周期分布)或不单独展示(RFM 散点分布)。"
        ),
    }
