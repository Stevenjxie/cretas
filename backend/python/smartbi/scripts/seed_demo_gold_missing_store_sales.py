"""Seed auditable POS history for active Demo Gold stores that have no sales.

The restaurant demo account reads store-scoped sales from ``RES_3101_009``.
Its directory contains real showcase store names, but some active stores have
neither ``agg_daily`` nor POS-grain rows. Selecting one of those stores from a
clarification button therefore produces a truthful but unusable no-data answer.

This command fills only active stores whose POS and aggregate counts are both
zero. It never changes a store that already has sales and excludes names marked
as closed. Generated transactions, items and aggregates carry reserved markers
so the write is idempotent, auditable and reversible.

Dry-run is the default. Applying or rolling back requires both the explicit
flag and ``--confirm RES_3101_009``.
"""
from __future__ import annotations

import argparse
import asyncio
from datetime import date, timedelta
from typing import Any, Dict


FACTORY_ID = "RES_3101_009"
SOURCE_TYPE = "DEMO_STORE_SEED"
ITEM_MARKER = "DEMO_STORE_SEED_V1"
SEED_VERSION = 9_000_727
BILLS_PER_DAY = 12

_PRODUCT_NAMES = (
    "顺德干蒸鲜排骨",
    "老广腊味煲仔饭",
    "干蒸豉油风爪",
    "干炒牛河",
    "白灼广东菜心",
    "广东丝瓜烧蛏子",
    "豉油蒸海鲈鱼",
    "葱油爆花蛤",
)


def validate_target_end(target_end: date) -> None:
    latest_allowed = date.today() - timedelta(days=1)
    if target_end > latest_allowed:
        raise ValueError(
            f"target end {target_end} exceeds latest complete day {latest_allowed}"
        )


async def build_plan(conn: Any, target_end: date) -> Dict[str, Any]:
    validate_target_end(target_end)
    start = target_end.replace(month=1, day=1)
    await conn.execute(
        "SELECT set_config('app.factory_id', $1, false)",
        FACTORY_ID,
    )
    counts = await conn.fetchrow(
        """
        WITH eligible AS (
          SELECT s.store_id
            FROM dim_store s
           WHERE s.factory_id = $1
             AND s.name NOT LIKE '%闭店%'
             AND NOT EXISTS (
                   SELECT 1 FROM fact_pos_transaction t
                    WHERE t.factory_id = s.factory_id
                      AND t.store_id = s.store_id
             )
             AND NOT EXISTS (
                   SELECT 1 FROM agg_daily a
                    WHERE a.factory_id = s.factory_id
                      AND a.store_id = s.store_id
             )
        )
        SELECT
          (SELECT COUNT(*) FROM dim_store
            WHERE factory_id=$1 AND name NOT LIKE '%闭店%') AS active_stores,
          (SELECT COUNT(*) FROM eligible) AS eligible_stores,
          (SELECT COUNT(DISTINCT store_id) FROM fact_pos_transaction
            WHERE factory_id=$1 AND source_type=$2) AS seeded_stores,
          (SELECT COUNT(*) FROM fact_pos_transaction
            WHERE factory_id=$1 AND source_type=$2) AS seeded_transactions,
          (SELECT COUNT(*) FROM fact_pos_item
            WHERE factory_id=$1 AND source_item_raw=$3) AS seeded_items,
          (SELECT COUNT(*) FROM agg_daily
            WHERE factory_id=$1 AND version=$4) AS seeded_aggregates,
          (SELECT COUNT(DISTINCT name) FROM dim_product
            WHERE factory_id=$1 AND name=ANY($5::text[])) AS catalog_products
        """,
        FACTORY_ID,
        SOURCE_TYPE,
        ITEM_MARKER,
        SEED_VERSION,
        list(_PRODUCT_NAMES),
    )
    return {
        "factory_id": FACTORY_ID,
        "start": start.isoformat(),
        "target_end": target_end.isoformat(),
        "bills_per_day": BILLS_PER_DAY,
        "active_stores": int(counts["active_stores"]),
        "eligible_stores": int(counts["eligible_stores"]),
        "seeded_stores_before": int(counts["seeded_stores"]),
        "seeded_transactions_before": int(counts["seeded_transactions"]),
        "seeded_items_before": int(counts["seeded_items"]),
        "seeded_aggregates_before": int(counts["seeded_aggregates"]),
        "catalog_products": int(counts["catalog_products"]),
    }


async def apply_seed(conn: Any, target_end: date) -> Dict[str, Any]:
    plan = await build_plan(conn, target_end)
    if plan["catalog_products"] != len(_PRODUCT_NAMES):
        raise RuntimeError(
            "demo product catalog is incomplete; refusing to create partial baskets"
        )
    start = date.fromisoformat(plan["start"])

    tx_result = await conn.execute(
        """
        WITH eligible AS (
          SELECT s.store_id
            FROM dim_store s
           WHERE s.factory_id = $1
             AND s.name NOT LIKE '%闭店%'
             AND NOT EXISTS (
                   SELECT 1 FROM fact_pos_transaction t
                    WHERE t.factory_id = s.factory_id
                      AND t.store_id = s.store_id
             )
             AND NOT EXISTS (
                   SELECT 1 FROM agg_daily a
                    WHERE a.factory_id = s.factory_id
                      AND a.store_id = s.store_id
             )
        ),
        seed_grid AS (
          SELECT e.store_id, d::date AS business_date, n
            FROM eligible e
            CROSS JOIN generate_series($3::date, $4::date, interval '1 day') d
            CROSS JOIN generate_series(1, $5::int) n
        )
        INSERT INTO fact_pos_transaction (
          factory_id, source_type, source_bill_no, store_id, date, time,
          gross_amount, discount_amount, net_amount, actual_receive,
          customer_count, avg_per_capita, order_type, channel_origin,
          item_count, has_discount
        )
        SELECT
          $1, $2,
          'DSS-V1-' || store_id || '-' || to_char(business_date, 'YYYYMMDD')
            || '-' || lpad(n::text, 2, '0'),
          store_id, business_date,
          business_date::timestamp
            + interval '10 hours'
            + (n * interval '37 minutes'),
          0, 0, 0, 0,
          CASE WHEN n % 4 = 0 THEN 2 ELSE 1 END,
          0,
          CASE WHEN n % 3 = 0 THEN '外卖' ELSE '堂食' END,
          CASE WHEN n % 3 = 0 THEN '美团' ELSE '门店收银' END,
          3,
          true
        FROM seed_grid
        ON CONFLICT (factory_id, source_type, store_id, source_bill_no)
        DO NOTHING
        """,
        FACTORY_ID,
        SOURCE_TYPE,
        start,
        target_end,
        BILLS_PER_DAY,
    )

    item_result = await conn.execute(
        """
        WITH catalog AS (
          SELECT product_id, name,
                 ROW_NUMBER() OVER (
                   ORDER BY array_position($4::text[], name::text)
                 ) AS rn,
                 CASE name
                   WHEN '顺德干蒸鲜排骨' THEN 46.00
                   WHEN '老广腊味煲仔饭' THEN 42.00
                   WHEN '干蒸豉油风爪' THEN 28.00
                   WHEN '干炒牛河' THEN 28.00
                   WHEN '白灼广东菜心' THEN 22.00
                   WHEN '广东丝瓜烧蛏子' THEN 42.00
                   WHEN '豉油蒸海鲈鱼' THEN 58.00
                   WHEN '葱油爆花蛤' THEN 35.00
                 END::numeric(18,2) AS price
            FROM dim_product
           WHERE factory_id=$1 AND name=ANY($4::text[])
        ),
        targets AS (
          SELECT t.id, t.store_id, t.date,
                 ROW_NUMBER() OVER (
                   PARTITION BY t.store_id, t.date ORDER BY t.id
                 ) AS bill_no
            FROM fact_pos_transaction t
           WHERE t.factory_id=$1 AND t.source_type=$2
             AND t.date BETWEEN $5::date AND $6::date
             AND NOT EXISTS (
                   SELECT 1 FROM fact_pos_item i
                    WHERE i.transaction_id=t.id
             )
        ),
        lines AS (
          SELECT tg.id AS transaction_id, c.product_id, c.price,
                 CASE
                   WHEN (tg.bill_no + line_no) % 7 = 0 THEN 2
                   ELSE 1
                 END::numeric(18,3) AS qty
            FROM targets tg
            CROSS JOIN generate_series(1, 3) line_no
            JOIN catalog c
              ON c.rn = (
                ((tg.store_id + EXTRACT(DOY FROM tg.date)::int
                   + tg.bill_no * 2 + line_no - 1)
                  % (SELECT COUNT(*) FROM catalog)) + 1
              )
        )
        INSERT INTO fact_pos_item (
          transaction_id, factory_id, product_id, qty, unit_price, amount,
          source_item_raw, created_at, return_qty
        )
        SELECT transaction_id, $1, product_id, qty, price,
               ROUND(price * qty, 2), $3, NOW(), 0
          FROM lines
        """,
        FACTORY_ID,
        SOURCE_TYPE,
        ITEM_MARKER,
        list(_PRODUCT_NAMES),
        start,
        target_end,
    )

    update_result = await conn.execute(
        """
        WITH totals AS (
          SELECT t.id,
                 SUM(i.amount)::numeric(18,2) AS net,
                 SUM(i.qty)::int AS item_count
            FROM fact_pos_transaction t
            JOIN fact_pos_item i ON i.transaction_id=t.id
           WHERE t.factory_id=$1 AND t.source_type=$2
           GROUP BY t.id
        )
        UPDATE fact_pos_transaction t
           SET net_amount=totals.net,
               actual_receive=totals.net,
               gross_amount=ROUND(totals.net * 1.04, 2),
               discount_amount=ROUND(totals.net * 0.04, 2),
               avg_per_capita=ROUND(
                 totals.net / GREATEST(t.customer_count, 1), 2
               ),
               item_count=totals.item_count,
               updated_at=NOW()
          FROM totals
         WHERE t.id=totals.id
        """,
        FACTORY_ID,
        SOURCE_TYPE,
    )

    agg_result = await conn.execute(
        """
        INSERT INTO agg_daily (
          factory_id, date, store_id,
          gross_amount, discount_amount, net_amount, actual_receive,
          bill_count, customer_count, item_count, version, computed_at
        )
        SELECT
          t.factory_id, t.date, t.store_id,
          SUM(t.gross_amount), SUM(t.discount_amount), SUM(t.net_amount),
          SUM(t.actual_receive), COUNT(*)::int,
          SUM(t.customer_count)::int, SUM(t.item_count)::int,
          $3, NOW()
        FROM fact_pos_transaction t
        WHERE t.factory_id=$1 AND t.source_type=$2
        GROUP BY t.factory_id,t.date,t.store_id
        ON CONFLICT (factory_id,date,store_id) DO NOTHING
        """,
        FACTORY_ID,
        SOURCE_TYPE,
        SEED_VERSION,
    )
    await conn.execute(
        "ANALYZE fact_pos_transaction; "
        "ANALYZE fact_pos_item; "
        "ANALYZE agg_daily"
    )

    verification = await conn.fetchrow(
        """
        SELECT
          COUNT(DISTINCT t.store_id) AS stores,
          COUNT(DISTINCT t.id) AS transactions,
          COUNT(i.id) AS items,
          MIN(t.date) AS first_date,
          MAX(t.date) AS last_date,
          COALESCE(SUM(i.amount),0)::numeric(18,2) AS revenue,
          (SELECT COUNT(*) FROM agg_daily
            WHERE factory_id=$1 AND version=$4) AS aggregates
        FROM fact_pos_transaction t
        LEFT JOIN fact_pos_item i
          ON i.factory_id=t.factory_id AND i.transaction_id=t.id
        WHERE t.factory_id=$1 AND t.source_type=$2
          AND (i.source_item_raw=$3 OR i.id IS NULL)
        """,
        FACTORY_ID,
        SOURCE_TYPE,
        ITEM_MARKER,
        SEED_VERSION,
    )
    expected_seeded_stores = (
        int(plan["seeded_stores_before"]) + int(plan["eligible_stores"])
    )
    if int(verification["stores"]) != expected_seeded_stores:
        raise RuntimeError("seeded store count does not match the reviewed plan")
    if int(verification["transactions"]) <= 0 or int(verification["items"]) <= 0:
        raise RuntimeError("demo seed verification found no transaction/item rows")
    return {
        **plan,
        "inserted_transactions": int(tx_result.rsplit(" ", 1)[-1]),
        "inserted_items": int(item_result.rsplit(" ", 1)[-1]),
        "updated_transactions": int(update_result.rsplit(" ", 1)[-1]),
        "inserted_aggregates": int(agg_result.rsplit(" ", 1)[-1]),
        "seeded_stores_after": int(verification["stores"]),
        "seeded_transactions_after": int(verification["transactions"]),
        "seeded_items_after": int(verification["items"]),
        "seeded_aggregates_after": int(verification["aggregates"]),
        "first_date": str(verification["first_date"]),
        "last_date": str(verification["last_date"]),
        "revenue_after": str(verification["revenue"]),
    }


async def rollback_seed(conn: Any) -> Dict[str, int]:
    await conn.execute(
        "SELECT set_config('app.factory_id', $1, false)",
        FACTORY_ID,
    )
    aggregates = await conn.execute(
        "DELETE FROM agg_daily WHERE factory_id=$1 AND version=$2",
        FACTORY_ID,
        SEED_VERSION,
    )
    transactions = await conn.execute(
        "DELETE FROM fact_pos_transaction "
        "WHERE factory_id=$1 AND source_type=$2",
        FACTORY_ID,
        SOURCE_TYPE,
    )
    return {
        "deleted_aggregates": int(aggregates.rsplit(" ", 1)[-1]),
        "deleted_transactions": int(transactions.rsplit(" ", 1)[-1]),
    }


async def run(args: argparse.Namespace) -> None:
    if (args.apply or args.rollback) and args.confirm != FACTORY_ID:
        raise RuntimeError(
            f"state change requires --confirm {FACTORY_ID}"
        )
    target_end = (
        date.fromisoformat(args.end)
        if args.end
        else date.today() - timedelta(days=1)
    )
    from smartbi.config import get_pg_pool

    pool = await get_pg_pool()
    if pool is None:
        raise RuntimeError("SmartBI Postgres pool is unavailable")
    async with pool.acquire() as conn:
        async with conn.transaction():
            if args.rollback:
                result = await rollback_seed(conn)
                print({"mode": "rollback", **result})
                return
            plan = await build_plan(conn, target_end)
            if not args.apply:
                print({"mode": "dry-run", **plan})
                return
            result = await apply_seed(conn, target_end)
            print({"mode": "apply", **result})


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apply", action="store_true")
    parser.add_argument("--rollback", action="store_true")
    parser.add_argument("--confirm", default="")
    parser.add_argument(
        "--end",
        default="",
        help="last complete sales date (default: yesterday)",
    )
    asyncio.run(run(parser.parse_args()))


if __name__ == "__main__":
    main()
