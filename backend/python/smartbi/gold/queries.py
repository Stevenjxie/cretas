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
            "channel_name": r["name"],
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
            "discount_name": r["name"],
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
            "order_type": r["order_type"],
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
                   SUM(a.net_amount)::numeric(18,2) AS revenue,
                   SUM(a.bill_count)                AS bill_count
              FROM agg_daily a
              JOIN dim_store s ON s.store_id = a.store_id
             WHERE {stores_where}
             GROUP BY a.store_id, s.name
             ORDER BY SUM(a.net_amount) DESC
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
