"""Roll DEMO_REST's dish-level POS items forward from its April basket grain.

DEMO_REST's dish answers (gross margin, dish scoping, dish ranking, the whole
Sheet 7/22 菜品链) read ``fact_pos_item`` joined to ``dim_product``.  The seed
wrote item baskets only up to 2026-04-30, while ``fact_pos_transaction`` rows
run months further — so every dish question with a calendar window past April
collapses to a truthful-but-useless no-data decline, even though the same
day's revenue exists at transaction/aggregate grain.

This command clones item baskets from the tenant's own April template
transactions (the last 31 days that DO have product-joined items) onto every
later transaction that has none, scaling qty and amount so the item sum
matches each target transaction's ``net_amount`` — dish-level day revenue
therefore stays consistent with ``agg_daily`` and the sales-summary answers.
``unit_price`` is kept from the template so per-dish margin math stays sane.

Generated rows carry ``source_item_raw = MARKER`` so the operation is
auditable and fully reversible.  Scoped to the demo tenant only; real tenant
data is never read or written.

Dry-run by default.  Applying or rolling back requires both ``--apply`` /
``--rollback`` and the exact tenant confirmation value.
"""
from __future__ import annotations

import argparse
import asyncio
from datetime import date, timedelta
from typing import Any, Dict


FACTORY_ID = "DEMO_REST"
MARKER = "DEMO_ROLL_9000723"


def validate_target_end(target_end: date) -> None:
    latest_allowed = date.today() - timedelta(days=1)
    if target_end > latest_allowed:
        raise ValueError(
            f"target end {target_end} exceeds latest complete day {latest_allowed}"
        )


async def _template_window(conn: Any) -> Dict[str, Any]:
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
        """,
        FACTORY_ID, MARKER,
    )
    tpl_end = row["tpl_end"]
    if tpl_end is None:
        raise RuntimeError("no product-joined template items exist; nothing to clone")
    return {"tpl_start": tpl_end - timedelta(days=30), "tpl_end": tpl_end}


async def build_plan(conn: Any, target_end: date) -> Dict[str, Any]:
    validate_target_end(target_end)
    await conn.execute("SELECT set_config('app.factory_id', $1, false)", FACTORY_ID)
    window = await _template_window(conn)
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
            WHERE t.factory_id = $1 AND t.date <= $4
              AND COALESCE(t.net_amount, 0) > 0) AS tx_max_date
        """,
        FACTORY_ID, window["tpl_start"], window["tpl_end"], target_end, MARKER,
    )
    return {
        "factory_id": FACTORY_ID,
        "target_end": target_end.isoformat(),
        "template_window": f"{window['tpl_start']}..{window['tpl_end']}",
        "template_tx": int(counts["template_tx"]),
        "target_tx_without_items": int(counts["target_tx"]),
        "marker_rows_before": int(counts["marker_rows"]),
        "tx_max_date": str(counts["tx_max_date"]),
    }


async def apply_refresh(conn: Any, target_end: date) -> Dict[str, Any]:
    window = await _template_window(conn)
    tpl_start, tpl_end = window["tpl_start"], window["tpl_end"]

    # Stray post-template items that never joined dim_product would double
    # count against net_amount once cloned baskets land — remove them first
    # (demo tenant only; the marker guard keeps re-runs from eating own rows).
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
        FACTORY_ID, tpl_end, MARKER,
    )
    result = await conn.execute(
        """
        WITH tpl AS (
          SELECT t.id AS tpl_id,
                 SUM(i.amount) AS tpl_amount,
                 ROW_NUMBER() OVER (ORDER BY t.id) AS rn,
                 COUNT(*) OVER () AS cnt
            FROM fact_pos_transaction t
            JOIN fact_pos_item i ON i.transaction_id = t.id
            JOIN dim_product p
              ON p.product_id = i.product_id AND p.factory_id = i.factory_id
           WHERE t.factory_id = $1 AND t.date BETWEEN $2 AND $3
           GROUP BY t.id
          HAVING SUM(i.amount) > 0
        ),
        targets AS (
          SELECT t.id, t.net_amount
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
        SELECT tg.id, $1, i.product_id,
               ROUND(i.qty * (tg.net_amount / tpl.tpl_amount), 2),
               i.unit_price,
               ROUND(i.amount * (tg.net_amount / tpl.tpl_amount), 2),
               $5, NOW(), 0
          FROM targets tg
          JOIN tpl ON tpl.rn = (tg.id % tpl.cnt) + 1
          JOIN fact_pos_item i ON i.transaction_id = tpl.tpl_id
        """,
        FACTORY_ID, tpl_start, tpl_end, target_end, MARKER,
    )
    inserted = int(result.rsplit(" ", 1)[-1])
    if inserted:
        # Fresh rows shift planner estimates; stale stats regressed the
        # margin anchor scans from seconds to minutes before (agg_daily 前科).
        await conn.execute("ANALYZE fact_pos_item")
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
        FACTORY_ID, MARKER,
    )
    return {
        "stray_items_deleted": int(strays.rsplit(" ", 1)[-1]),
        "inserted_items": inserted,
        "joined_max_date_after": str(verification["joined_max_date"]),
        "marker_rows_after": int(verification["marker_rows"]),
    }


async def rollback_refresh(conn: Any) -> Dict[str, int]:
    await conn.execute("SELECT set_config('app.factory_id', $1, false)", FACTORY_ID)
    result = await conn.execute(
        "DELETE FROM fact_pos_item WHERE factory_id=$1 AND source_item_raw=$2",
        FACTORY_ID, MARKER,
    )
    return {"deleted_rows": int(result.rsplit(" ", 1)[-1])}


async def run(args: argparse.Namespace) -> None:
    if args.confirm != FACTORY_ID and (args.apply or args.rollback):
        raise RuntimeError(f"state change requires --confirm {FACTORY_ID}")
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
                result = await rollback_refresh(conn)
                print({"mode": "rollback", **result})
                return
            plan = await build_plan(conn, target_end)
            if not args.apply:
                print({"mode": "dry-run", **plan})
                return
            result = await apply_refresh(conn, target_end)
            print({"mode": "apply", **plan, **result})


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apply", action="store_true", help="write the cloned items")
    parser.add_argument("--rollback", action="store_true",
                        help="delete rows previously written by this command")
    parser.add_argument("--confirm", default="",
                        help=f"must equal {FACTORY_ID} for --apply/--rollback")
    parser.add_argument("--end", default="",
                        help="last transaction date to backfill (default: yesterday)")
    args = parser.parse_args()
    asyncio.run(run(args))


if __name__ == "__main__":
    main()
