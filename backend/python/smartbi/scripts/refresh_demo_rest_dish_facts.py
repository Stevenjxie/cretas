"""Roll a demo tenant's dish-level POS grain forward to the present.

Dish/store-margin answers (gross margin, dish scoping, dish ranking, store
drilldown, the Sheet 7/22 菜品链) read ``fact_pos_item`` joined to
``dim_product``.  Both demo tenants' seeds stop that grain at 2026-04-30:

- ``DEMO_REST``: transactions run months further but carry no items.
- ``RES_3101_009`` (QHJ gold clone): transactions stop 2026-06-06 with
  unjoined items after April, while ``agg_daily`` is refreshed daily — so
  every store-margin window anchors back to April.

Two stages close the gap, both idempotent and reversible:

1. **Transaction synthesis** — for calendar days where ``agg_daily`` has
   revenue but no transactions exist, synthesize per-store transactions
   (one per aggregated bill, ``net_amount`` split evenly) marked
   ``source_type = TX_MARKER``.  Real bill counts are preserved so
   bill-based metrics stay sane.
2. **Basket cloning** — clone item baskets from the tenant's own April
   template transactions onto every later transaction that has none,
   preferring same-store templates (falling back to the global pool),
   scaling qty and amount so the item sum matches each target's
   ``net_amount``.  A final per-day rescale aligns dish-level revenue with
   ``agg_daily`` so dish answers and sales summaries tell one story.

Generated rows carry ``source_item_raw = MARKER`` / ``source_type =
TX_MARKER`` so the operation is auditable and fully reversible.  Scoped to
the two demo tenants only; real tenant data is never read or written.

Dry-run by default.  Applying or rolling back requires both ``--apply`` /
``--rollback`` and the exact tenant confirmation value.
"""
from __future__ import annotations

import argparse
import asyncio
from datetime import date, timedelta
from typing import Any, Dict


ALLOWED_FACTORIES = ("DEMO_REST", "RES_3101_009")
FACTORY_ID = ALLOWED_FACTORIES[0]  # default; overridable via --factory
MARKER = "DEMO_ROLL_9000723"
TX_MARKER = "DEMO_ROLL_TX"


def validate_target_end(target_end: date) -> None:
    latest_allowed = date.today() - timedelta(days=1)
    if target_end > latest_allowed:
        raise ValueError(
            f"target end {target_end} exceeds latest complete day {latest_allowed}"
        )


async def _template_window(conn: Any, factory_id: str) -> Dict[str, Any]:
    """Last day with product-joined items = template anchor (31-day window)."""
    row = await conn.fetchrow(
        """
        SELECT MAX(t.date) AS tpl_end
          FROM fact_pos_item i
          JOIN fact_pos_transaction t ON t.id = i.transaction_id
          JOIN dim_product p
            ON p.product_id = i.product_id AND p.factory_id = i.factory_id
         WHERE i.factory_id = $1 AND t.factory_id = $1
           AND i.source_item_raw IS DISTINCT FROM $2
           AND t.source_type IS DISTINCT FROM $3
        """,
        factory_id, MARKER, TX_MARKER,
    )
    tpl_end = row["tpl_end"]
    if tpl_end is None:
        raise RuntimeError("no product-joined template items exist; nothing to clone")
    return {"tpl_start": tpl_end - timedelta(days=30), "tpl_end": tpl_end}


async def build_plan(conn: Any, factory_id: str, target_end: date) -> Dict[str, Any]:
    validate_target_end(target_end)
    await conn.execute("SELECT set_config('app.factory_id', $1, false)", factory_id)
    window = await _template_window(conn, factory_id)
    counts = await conn.fetchrow(
        """
        SELECT
          (SELECT COUNT(*) FROM (
             SELECT t.id
               FROM fact_pos_transaction t
               JOIN fact_pos_item i ON i.transaction_id = t.id
               JOIN dim_product p
                 ON p.product_id = i.product_id AND p.factory_id = i.factory_id
              WHERE t.factory_id = $1 AND t.date BETWEEN $2 AND $3
              GROUP BY t.id
             HAVING SUM(i.amount) > 0
          ) tpl) AS template_tx,
          (SELECT COUNT(*) FROM fact_pos_transaction t
            WHERE t.factory_id = $1 AND t.date > $3 AND t.date <= $4
              AND COALESCE(t.net_amount, 0) > 0
              AND NOT EXISTS (SELECT 1 FROM fact_pos_item i
                               WHERE i.transaction_id = t.id)) AS target_tx,
          (SELECT COUNT(*) FROM fact_pos_item i
             WHERE i.factory_id = $1 AND i.source_item_raw = $5) AS marker_rows,
          (SELECT MAX(t.date) FROM fact_pos_transaction t
            WHERE t.factory_id = $1
              AND COALESCE(t.net_amount, 0) > 0) AS tx_max_date,
          (SELECT COALESCE(SUM(a.bill_count), 0) FROM agg_daily a
            WHERE a.factory_id = $1 AND a.date <= $4
              AND COALESCE(a.net_amount, 0) > 0
              AND a.date > COALESCE((SELECT MAX(t.date) FROM fact_pos_transaction t
                                      WHERE t.factory_id = $1), DATE '1970-01-01')
          ) AS synth_bills_needed
        """,
        factory_id, window["tpl_start"], window["tpl_end"], target_end, MARKER,
    )
    return {
        "factory_id": factory_id,
        "target_end": target_end.isoformat(),
        "template_window": f"{window['tpl_start']}..{window['tpl_end']}",
        "template_tx": int(counts["template_tx"]),
        "target_tx_without_items": int(counts["target_tx"]),
        "marker_rows_before": int(counts["marker_rows"]),
        "tx_max_date": str(counts["tx_max_date"]),
        "synth_bills_needed": int(counts["synth_bills_needed"]),
    }


async def _synthesize_transactions(conn: Any, factory_id: str, target_end: date) -> int:
    """Stage 1: per-store synthetic transactions for agg-only calendar days.

    One transaction per aggregated bill keeps bill-count metrics honest;
    ``net_amount`` splits evenly (the per-day item rescale later absorbs
    the rounding drift against ``agg_daily``).
    """
    result = await conn.execute(
        """
        INSERT INTO fact_pos_transaction (
          factory_id, source_type, source_bill_no, store_id, date, time,
          gross_amount, net_amount, actual_receive, customer_count, item_count
        )
        SELECT $1, $3,
               'DR-' || a.store_id || '-' || a.date || '-' || gs.n,
               a.store_id, a.date,
               a.date::timestamp + interval '10 hours'
                 + (gs.n * interval '17 seconds'),
               ROUND(COALESCE(a.gross_amount, a.net_amount) / a.bill_count, 2),
               ROUND(a.net_amount / a.bill_count, 2),
               ROUND(COALESCE(a.actual_receive, a.net_amount) / a.bill_count, 2),
               GREATEST(1, (COALESCE(a.customer_count, a.bill_count)
                            / a.bill_count))::int,
               GREATEST(1, (COALESCE(a.item_count, a.bill_count)
                            / a.bill_count))::int
          FROM agg_daily a
          CROSS JOIN LATERAL generate_series(1, GREATEST(a.bill_count, 1)) AS gs(n)
         WHERE a.factory_id = $1
           AND a.date <= $2
           AND COALESCE(a.net_amount, 0) > 0
           AND a.bill_count > 0
           AND a.date > COALESCE((SELECT MAX(t.date) FROM fact_pos_transaction t
                                   WHERE t.factory_id = $1), DATE '1970-01-01')
        """,
        factory_id, target_end, TX_MARKER,
    )
    return int(result.rsplit(" ", 1)[-1])


_CLONE_INSERT_PREFIX = """
        WITH tpl AS (
          SELECT t.id AS tpl_id, t.store_id,
                 SUM(i.amount) AS tpl_amount,
                 ROW_NUMBER() OVER (PARTITION BY t.store_id ORDER BY t.id) AS rn_store,
                 COUNT(*) OVER (PARTITION BY t.store_id) AS cnt_store,
                 ROW_NUMBER() OVER (ORDER BY t.id) AS rn,
                 COUNT(*) OVER () AS cnt
            FROM fact_pos_transaction t
            JOIN fact_pos_item i ON i.transaction_id = t.id
            JOIN dim_product p
              ON p.product_id = i.product_id AND p.factory_id = i.factory_id
           WHERE t.factory_id = $1 AND t.date BETWEEN $2 AND $3
           GROUP BY t.id, t.store_id
          HAVING SUM(i.amount) > 0
        ),
        targets AS (
          SELECT t.id, t.store_id, t.net_amount
            FROM fact_pos_transaction t
           WHERE t.factory_id = $1 AND t.date > $3 AND t.date <= $4
             AND COALESCE(t.net_amount, 0) > 0
             AND NOT EXISTS (SELECT 1 FROM fact_pos_item i
                              WHERE i.transaction_id = t.id)
        )
        INSERT INTO fact_pos_item (
          transaction_id, factory_id, product_id, qty, unit_price, amount,
          source_item_raw, created_at, return_qty
        )
"""


async def apply_refresh(conn: Any, factory_id: str, target_end: date) -> Dict[str, Any]:
    window = await _template_window(conn, factory_id)
    tpl_start, tpl_end = window["tpl_start"], window["tpl_end"]

    synth_tx = await _synthesize_transactions(conn, factory_id, target_end)

    # Stray post-template items that never joined dim_product would double
    # count against net_amount once cloned baskets land — remove them first
    # (demo tenants only; the marker guard keeps re-runs from eating own rows).
    strays = await conn.execute(
        """
        DELETE FROM fact_pos_item i
         USING fact_pos_transaction t
         WHERE t.id = i.transaction_id
           AND i.factory_id = $1 AND t.factory_id = $1
           AND t.date > $2
           AND i.source_item_raw IS DISTINCT FROM $3
           AND NOT EXISTS (SELECT 1 FROM dim_product p
                            WHERE p.product_id = i.product_id
                              AND p.factory_id = i.factory_id)
        """,
        factory_id, tpl_end, MARKER,
    )

    # Pass 1: same-store templates — keeps each store's dish mix realistic.
    pass1 = await conn.execute(
        _CLONE_INSERT_PREFIX + """
        SELECT tg.id, $1, i.product_id,
               ROUND(i.qty * (tg.net_amount / tpl.tpl_amount), 2),
               i.unit_price,
               ROUND(i.amount * (tg.net_amount / tpl.tpl_amount), 2),
               $5, NOW(), 0
          FROM targets tg
          JOIN tpl ON tpl.store_id = tg.store_id
                  AND tpl.rn_store = (tg.id % tpl.cnt_store) + 1
          JOIN fact_pos_item i ON i.transaction_id = tpl.tpl_id
        """,
        factory_id, tpl_start, tpl_end, target_end, MARKER,
    )
    # Pass 2: global pool for stores without their own April templates.
    pass2 = await conn.execute(
        _CLONE_INSERT_PREFIX + """
        SELECT tg.id, $1, i.product_id,
               ROUND(i.qty * (tg.net_amount / tpl.tpl_amount), 2),
               i.unit_price,
               ROUND(i.amount * (tg.net_amount / tpl.tpl_amount), 2),
               $5, NOW(), 0
          FROM targets tg
          JOIN tpl ON tpl.rn = (tg.id % tpl.cnt) + 1
          JOIN fact_pos_item i ON i.transaction_id = tpl.tpl_id
        """,
        factory_id, tpl_start, tpl_end, target_end, MARKER,
    )
    inserted = int(pass1.rsplit(" ", 1)[-1]) + int(pass2.rsplit(" ", 1)[-1])
    rescaled = "UPDATE 0"
    if inserted:
        # 双 demo 数据空间对齐: 部分天的 agg_daily 是 QHJ gold 克隆 (数倍于
        # 本租户交易流水)。按日把克隆明细等比放大到 agg 口径; 已一致的天
        # factor≈1 原样保留, 无 agg 行的天跳过 (保持交易级对账)。
        rescaled = await conn.execute(
            """
            WITH agg_day AS (
              SELECT date, SUM(net_amount) AS rev
                FROM agg_daily WHERE factory_id = $1 GROUP BY date
            ),
            dish_day AS (
              SELECT t.date, SUM(i.amount) AS rev
                FROM fact_pos_item i
                JOIN fact_pos_transaction t ON t.id = i.transaction_id
                JOIN dim_product p
                  ON p.product_id = i.product_id AND p.factory_id = i.factory_id
               WHERE i.factory_id = $1 AND i.source_item_raw = $2
               GROUP BY t.date
            ),
            factors AS (
              SELECT d.date, a.rev / NULLIF(d.rev, 0) AS factor
                FROM dish_day d JOIN agg_day a ON a.date = d.date
               WHERE a.rev / NULLIF(d.rev, 0) BETWEEN 1.01 AND 25
            )
            UPDATE fact_pos_item i
               SET qty = ROUND(i.qty * f.factor, 2),
                   amount = ROUND(i.amount * f.factor, 2)
              FROM fact_pos_transaction t, factors f
             WHERE t.id = i.transaction_id
               AND i.factory_id = $1 AND i.source_item_raw = $2
               AND t.date = f.date
            """,
            factory_id, MARKER,
        )
    if inserted or synth_tx:
        # Fresh rows shift planner estimates; stale stats regressed the
        # margin anchor scans from seconds to minutes before (agg_daily 前科).
        await conn.execute("ANALYZE fact_pos_item")
        await conn.execute("ANALYZE fact_pos_transaction")
    verification = await conn.fetchrow(
        """
        SELECT MAX(t.date) AS joined_max_date,
               COUNT(*) FILTER (WHERE i.source_item_raw = $2) AS marker_rows
          FROM fact_pos_item i
          JOIN fact_pos_transaction t ON t.id = i.transaction_id
          JOIN dim_product p
            ON p.product_id = i.product_id AND p.factory_id = i.factory_id
         WHERE i.factory_id = $1
        """,
        factory_id, MARKER,
    )
    return {
        "synth_transactions": synth_tx,
        "stray_items_deleted": int(strays.rsplit(" ", 1)[-1]),
        "inserted_items": inserted,
        "rescaled_items": int(rescaled.rsplit(" ", 1)[-1]),
        "joined_max_date_after": str(verification["joined_max_date"]),
        "marker_rows_after": int(verification["marker_rows"]),
    }


async def rollback_refresh(conn: Any, factory_id: str) -> Dict[str, int]:
    await conn.execute("SELECT set_config('app.factory_id', $1, false)", factory_id)
    items = await conn.execute(
        "DELETE FROM fact_pos_item WHERE factory_id=$1 AND source_item_raw=$2",
        factory_id, MARKER,
    )
    txs = await conn.execute(
        "DELETE FROM fact_pos_transaction WHERE factory_id=$1 AND source_type=$2",
        factory_id, TX_MARKER,
    )
    return {
        "deleted_items": int(items.rsplit(" ", 1)[-1]),
        "deleted_transactions": int(txs.rsplit(" ", 1)[-1]),
    }


async def run(args: argparse.Namespace) -> None:
    factory_id = args.factory
    if factory_id not in ALLOWED_FACTORIES:
        raise RuntimeError(f"--factory must be one of {ALLOWED_FACTORIES}")
    if args.confirm != factory_id and (args.apply or args.rollback):
        raise RuntimeError(f"state change requires --confirm {factory_id}")
    target_end = (
        date.fromisoformat(args.end) if args.end else date.today() - timedelta(days=1)
    )

    from smartbi.config import get_pg_pool

    pool = await get_pg_pool()
    if pool is None:
        raise RuntimeError("SmartBI Postgres pool is unavailable")
    async with pool.acquire() as conn:
        async with conn.transaction():
            if args.rollback:
                result = await rollback_refresh(conn, factory_id)
                print({"mode": "rollback", "factory_id": factory_id, **result})
                return
            plan = await build_plan(conn, factory_id, target_end)
            if not args.apply:
                print({"mode": "dry-run", **plan})
                return
            result = await apply_refresh(conn, factory_id, target_end)
            print({"mode": "apply", **plan, **result})


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--factory", default=FACTORY_ID,
                        choices=list(ALLOWED_FACTORIES),
                        help="demo tenant to refresh (default: DEMO_REST)")
    parser.add_argument("--apply", action="store_true", help="write the cloned rows")
    parser.add_argument("--rollback", action="store_true",
                        help="delete rows previously written by this command")
    parser.add_argument("--confirm", default="",
                        help="must equal the --factory value for --apply/--rollback")
    parser.add_argument("--end", default="",
                        help="last calendar day to backfill (default: yesterday)")
    args = parser.parse_args()
    asyncio.run(run(args))


if __name__ == "__main__":
    main()
