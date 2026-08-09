"""Gold materializer — Silver fact_pos_* → agg_daily/product/channel.

Week 3 Day 2 of Unified Data Layer v1 spec (§4.1).

Each materialize_* function runs exactly one statement — an
INSERT ... SELECT ... ON CONFLICT DO UPDATE — so the full recompute for
a scope is one round trip, one transaction, atomic. The ON CONFLICT
branch bumps `version` and refreshes `computed_at`.

Scope model
-----------
Callers (Day 3 triggers) decide which scope to materialize:
- `materialize_daily(date_range)` — recomputes every (store, date) in range
- `materialize_product(month)` — recomputes every product for the month
- `materialize_channel(date_range)` — recomputes every (channel, date) in range

The scope is always bounded to this materializer's `factory_id` — cross-
tenant writes are impossible by construction. Tenant context must be set
(via `tenant_ctx.set_factory_id`) because the pool setup callback writes
that to the session's `app.factory_id` which RLS uses for WITH CHECK.

Idempotency + safety
--------------------
- Empty range / no matching fact rows → 0 rows upserted, no exception.
- Parser-miss items (product_id IS NULL) are excluded from agg_product;
  they show up in agg_daily via bill-level totals but not as a fake
  product.
- bill_count uses COUNT(DISTINCT transaction_id) because one bill can
  have many items (fact_pos_item), many payments, many discounts —
  dedup at aggregation time.
"""
from __future__ import annotations

import logging
from dataclasses import dataclass
from datetime import date
from typing import Tuple

import asyncpg

logger = logging.getLogger(__name__)


@dataclass
class MaterializeStats:
    """Return value from each materialize_* call."""
    target: str   # "agg_daily" | "agg_product" | "agg_channel"
    rows_upserted: int
    factory_id: str


# One INSERT...SELECT...ON CONFLICT per target table. Grouping happens in
# the SELECT so nothing materializes in Python memory — all aggregation
# runs inside PG.

_DAILY_UPSERT_SQL = """
INSERT INTO agg_daily (
    factory_id, date, store_id,
    gross_amount, discount_amount, net_amount, actual_receive,
    bill_count, customer_count, item_count, platform_fee_amount,
    version, computed_at
)
SELECT factory_id, date, store_id,
       SUM(gross_amount)    AS gross_amount,
       SUM(discount_amount) AS discount_amount,
       SUM(net_amount)      AS net_amount,
       SUM(actual_receive)  AS actual_receive,
       COUNT(*)             AS bill_count,
       SUM(COALESCE(customer_count, 0))::INT AS customer_count,
       SUM(COALESCE(item_count, 0))::INT     AS item_count,
       -- 渠道侧成本(外卖抽佣/团购券核销费)。堂食为 0 —— 那是真值不是缺失。
       SUM(COALESCE(platform_fee_amount, 0)) AS platform_fee_amount,
       1, NOW()
  FROM fact_pos_transaction
 WHERE factory_id = $1
   AND date BETWEEN $2 AND $3
 GROUP BY factory_id, date, store_id
ON CONFLICT (factory_id, date, store_id) DO UPDATE SET
    gross_amount    = EXCLUDED.gross_amount,
    discount_amount = EXCLUDED.discount_amount,
    net_amount      = EXCLUDED.net_amount,
    actual_receive  = EXCLUDED.actual_receive,
    bill_count      = EXCLUDED.bill_count,
    customer_count  = EXCLUDED.customer_count,
    platform_fee_amount = EXCLUDED.platform_fee_amount,
    item_count      = EXCLUDED.item_count,
    version         = agg_daily.version + 1,
    computed_at     = NOW()
"""

_PRODUCT_UPSERT_SQL = """
INSERT INTO agg_product (
    factory_id, product_id, month,
    qty_sold, revenue, bill_count,
    version, computed_at
)
SELECT i.factory_id,
       i.product_id,
       DATE_TRUNC('month', t.date)::DATE AS month,
       SUM(i.qty)                         AS qty_sold,
       SUM(i.amount)                      AS revenue,
       COUNT(DISTINCT t.id)               AS bill_count,
       1, NOW()
  FROM fact_pos_item i
  JOIN fact_pos_transaction t ON t.id = i.transaction_id
 WHERE i.factory_id = $1
   AND DATE_TRUNC('month', t.date)::DATE = $2
   AND i.product_id IS NOT NULL
 GROUP BY i.factory_id, i.product_id, DATE_TRUNC('month', t.date)::DATE
ON CONFLICT (factory_id, product_id, month) DO UPDATE SET
    qty_sold   = EXCLUDED.qty_sold,
    revenue    = EXCLUDED.revenue,
    bill_count = EXCLUDED.bill_count,
    version    = agg_product.version + 1,
    computed_at = NOW()
"""

_DISCOUNT_UPSERT_SQL = """
INSERT INTO agg_discount (
    factory_id, discount_id, month,
    amount, bill_count,
    version, computed_at
)
SELECT fd.factory_id,
       fd.discount_id,
       DATE_TRUNC('month', t.date)::DATE AS month,
       SUM(fd.amount)                    AS amount,
       COUNT(DISTINCT t.id)              AS bill_count,
       1, NOW()
  FROM fact_pos_discount fd
  JOIN fact_pos_transaction t ON t.id = fd.transaction_id
 WHERE fd.factory_id = $1
   AND DATE_TRUNC('month', t.date)::DATE = $2
 GROUP BY fd.factory_id, fd.discount_id, DATE_TRUNC('month', t.date)::DATE
ON CONFLICT (factory_id, discount_id, month) DO UPDATE SET
    amount      = EXCLUDED.amount,
    bill_count  = EXCLUDED.bill_count,
    version     = agg_discount.version + 1,
    computed_at = NOW()
"""

_CHANNEL_UPSERT_SQL = """
INSERT INTO agg_channel (
    factory_id, channel_id, date,
    amount, bill_count,
    version, computed_at
)
SELECT p.factory_id,
       p.channel_id,
       t.date,
       SUM(p.amount)        AS amount,
       COUNT(DISTINCT t.id) AS bill_count,
       1, NOW()
  FROM fact_pos_payment p
  JOIN fact_pos_transaction t ON t.id = p.transaction_id
 WHERE p.factory_id = $1
   AND t.date BETWEEN $2 AND $3
 GROUP BY p.factory_id, p.channel_id, t.date
ON CONFLICT (factory_id, channel_id, date) DO UPDATE SET
    amount     = EXCLUDED.amount,
    bill_count = EXCLUDED.bill_count,
    version    = agg_channel.version + 1,
    computed_at = NOW()
"""


class GoldMaterializer:
    """One-shot service: call the appropriate materialize_* for the scope
    you want refreshed."""

    def __init__(self, pool: asyncpg.Pool, factory_id: str):
        if not factory_id:
            raise ValueError("factory_id required")
        self.pool = pool
        self.factory_id = factory_id

    async def materialize_daily(
        self, date_range: Tuple[date, date]
    ) -> MaterializeStats:
        start, end = date_range
        if start > end:
            raise ValueError(f"start {start} > end {end}")
        async with self.pool.acquire() as conn:
            # execute returns "INSERT 0 N" where N is affected rows.
            result = await conn.execute(
                _DAILY_UPSERT_SQL, self.factory_id, start, end
            )
        n = _parse_affected_rows(result)
        logger.info("agg_daily upserted=%d factory=%s range=%s..%s",
                    n, self.factory_id, start, end)
        return MaterializeStats("agg_daily", n, self.factory_id)

    async def materialize_product(self, month: date) -> MaterializeStats:
        # Normalize to first-of-month in case caller passed a mid-month date.
        month_first = month.replace(day=1)
        async with self.pool.acquire() as conn:
            result = await conn.execute(
                _PRODUCT_UPSERT_SQL, self.factory_id, month_first
            )
        n = _parse_affected_rows(result)
        logger.info("agg_product upserted=%d factory=%s month=%s",
                    n, self.factory_id, month_first)
        return MaterializeStats("agg_product", n, self.factory_id)

    async def materialize_discount(self, month: date) -> MaterializeStats:
        """Same shape as materialize_product. month normalized to first-of-month."""
        month_first = month.replace(day=1)
        async with self.pool.acquire() as conn:
            result = await conn.execute(
                _DISCOUNT_UPSERT_SQL, self.factory_id, month_first
            )
        n = _parse_affected_rows(result)
        logger.info("agg_discount upserted=%d factory=%s month=%s",
                    n, self.factory_id, month_first)
        return MaterializeStats("agg_discount", n, self.factory_id)

    async def materialize_channel(
        self, date_range: Tuple[date, date]
    ) -> MaterializeStats:
        start, end = date_range
        if start > end:
            raise ValueError(f"start {start} > end {end}")
        async with self.pool.acquire() as conn:
            result = await conn.execute(
                _CHANNEL_UPSERT_SQL, self.factory_id, start, end
            )
        n = _parse_affected_rows(result)
        logger.info("agg_channel upserted=%d factory=%s range=%s..%s",
                    n, self.factory_id, start, end)
        return MaterializeStats("agg_channel", n, self.factory_id)


def _parse_affected_rows(result: str) -> int:
    """asyncpg's execute() returns strings like 'INSERT 0 12'. Third field
    is the affected row count. For ON CONFLICT DO UPDATE, this includes
    both inserts and updates."""
    parts = result.split()
    try:
        return int(parts[-1])
    except (ValueError, IndexError):
        return 0
