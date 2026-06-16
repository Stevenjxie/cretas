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
    types = []
    for r in rows:
        amt = Decimal(str(r["amt"]))
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
            "bill_count": int(r["bills"]),
            "revenue_pct": float(pct),
        })
    return {
        "factory_id": factory_id,
        "start_date": start.isoformat() if start is not None else None,
        "end_date": end.isoformat() if end is not None else None,
        "total_revenue": float(total),
        "order_types": types,
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
    totals_conds = ["factory_id = $1"]
    stores_conds = ["a.factory_id = $1"]
    if start is not None:
        params.append(start)
        totals_conds.append(f"date >= ${len(params)}")
        stores_conds.append(f"a.date >= ${len(params)}")
    if end is not None:
        params.append(end)
        totals_conds.append(f"date <= ${len(params)}")
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
              COALESCE(SUM(net_amount), 0)::numeric(18,2)  AS total_revenue,
              COALESCE(SUM(bill_count), 0)                 AS bill_count,
              COUNT(DISTINCT store_id)                     AS store_count,
              COUNT(DISTINCT date)                         AS day_count
            FROM agg_daily
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

    return {
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
