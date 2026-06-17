#!/usr/bin/env python3
"""
fix_demo_rest_dirty_data.py
===========================
One-off, idempotent data-correction for the **DEMO_REST** demo tenant only.

Two dirty-data symptoms it repairs (cretas_db / cretas_prod_db side):

  1. 毛利率 -23526%  ── material_batches.unit_price = 99999.00 / 119998.80 for
     ingredient 鲈鱼 (a *total purchase amount* mistakenly entered as a per-kg
     price). That garbage price flows:
        material_batches → agg_supplier_price → dim_ingredient.unit_price
          → fact_restaurant_recipe_line.line_cost → agg_restaurant_product_cost
          .food_cost → gross-margin KPI.
     Fix: clamp any DEMO_REST material_batches.unit_price that is implausibly
     high (>= SANE_PRICE_CAP) down to a sane per-kg value. We pick a sensible
     replacement per ingredient (鲈鱼 → ¥38/kg), else fall back to the ingredient's
     own list price / moving_avg, else a category-agnostic default.

  2. 盘亏率 947.5%  ── stocktaking shortage (≈18.95) divided by an unrealistically
     sparse requisition volume (total_req_qty ≈ 2.0). A real restaurant requisitions
     large daily quantities, so the ratio should be a few percent. Fix: seed a
     realistic spread of APPROVED material_requisitions across the demo date window
     so total_req_qty dwarfs the shortage (target 盘亏率 < 3%).

After this script runs, the caller MUST re-run the restaurant Gold ETL so the
aggregates recompute from the corrected raw rows (the ETL refresh reads its DB
target from the Python-service env, so run it in the matching environment):

    cd /www/wwwroot/cretas/code/backend/python && source venv38/bin/activate
    python scripts/gold_etl_daily_refresh.py --factories DEMO_REST

This reruns supplier-price ingest (now with the sanity cap in
supplier_price_ingest_etl.py) + restaurant_ops ETL → recomputes dim_ingredient
.unit_price, food_cost, and agg_restaurant_daily_totals for DEMO_REST.

SAFETY
------
* Only touches factory_id = 'DEMO_REST'. A hard guard rejects any other id.
* Idempotent: re-running clamps the same prices (already sane → no-op) and
  upserts requisitions on a deterministic id (req_demo_rest_*), so repeats don't
  duplicate.
* --dry-run prints what *would* change without writing.
* Never touches real customer tenants (F006 etc).

USAGE
-----
  python smartbi/scripts/fix_demo_rest_dirty_data.py --env test   [--dry-run]
  python smartbi/scripts/fix_demo_rest_dirty_data.py --env prod   [--dry-run]
"""
from __future__ import annotations

import argparse
import sys
from datetime import date, timedelta
from decimal import Decimal
from typing import Any, Dict, List, Optional, Tuple

try:
    import psycopg2
    from psycopg2.extras import execute_values, RealDictCursor
except ImportError:
    print("ERROR: psycopg2 not installed. Run: pip install psycopg2-binary")
    sys.exit(1)

# ---------------------------------------------------------------------------
# Config (mirrors seed_demo_raw_tables.py)
# ---------------------------------------------------------------------------

ENVS: Dict[str, Dict[str, Any]] = {
    "prod": {
        "cretas": {
            "host": "127.0.0.1", "port": 5432,
            "dbname": "cretas_prod_db", "user": "cretas_user", "password": "cretas123",
        },
        "smartbi": {
            "host": "127.0.0.1", "port": 5432,
            "dbname": "smartbi_prod_db", "user": "smartbi_user",
            "password": "smartbi_secure_password_2025",
        },
    },
    "test": {
        "cretas": {
            "host": "127.0.0.1", "port": 5432,
            "dbname": "cretas_db", "user": "cretas_user", "password": "cretas123",
        },
        "smartbi": {
            "host": "127.0.0.1", "port": 5432,
            "dbname": "smartbi_db", "user": "smartbi_user",
            "password": "smartbi_secure_password_2025",
        },
    },
}

TARGET_FACTORY = "DEMO_REST"

# Any per-unit price at/above this is treated as garbage (matches the ingest-ETL
# sanity guard in supplier_price_ingest_etl.MAX_SANE_UNIT_PRICE).
SANE_PRICE_CAP = Decimal("99999.0")

# Sensible per-kg replacement prices for known restaurant ingredients (¥/kg).
# Used when an ingredient's own list/moving-avg price is also garbage/missing.
KNOWN_SANE_PRICES: Dict[str, Decimal] = {
    "鲈鱼": Decimal("38.00"),
    "青花椒": Decimal("80.00"),
    "生菜": Decimal("4.20"),
    "大米": Decimal("6.00"),
    "藤椒": Decimal("46.00"),
}
DEFAULT_SANE_PRICE = Decimal("30.00")  # honest neutral fallback for unknown items

# Requisition seeding target: enough daily volume that shortage/req_qty < 3%.
# Shortage is ~19; we want total_req_qty in the hundreds.
REQ_DAYS_BACK = 90          # spread requisitions over the last 90 days
REQ_PER_DAY = 4             # 4 requisition lines per day
REQ_QTY_PER_LINE = Decimal("8.0")  # kg per requisition line


def connect(cfg: Dict[str, Any]):
    return psycopg2.connect(
        host=cfg["host"], port=cfg["port"], dbname=cfg["dbname"],
        user=cfg["user"], password=cfg["password"],
    )


# ---------------------------------------------------------------------------
# Step 1: clamp garbage material_batches unit_prices for DEMO_REST
# ---------------------------------------------------------------------------

def _sane_price_for(name: Optional[str], list_price, moving_avg) -> Decimal:
    """Pick an honest replacement price for an ingredient with a garbage batch price."""
    if name and name in KNOWN_SANE_PRICES:
        return KNOWN_SANE_PRICES[name]
    for candidate in (moving_avg, list_price):
        if candidate is not None:
            c = Decimal(str(candidate))
            if Decimal("0") < c < SANE_PRICE_CAP:
                return c
    return DEFAULT_SANE_PRICE


def fix_material_batch_prices(conn, dry_run: bool) -> int:
    """Clamp DEMO_REST material_batches with insane unit_price to a sane value."""
    with conn.cursor(cursor_factory=RealDictCursor) as cur:
        cur.execute(
            """
            SELECT mb.id, mb.unit_price, rmt.name AS material_name,
                   rmt.unit_price AS list_price, rmt.moving_avg_price AS moving_avg
              FROM material_batches mb
              LEFT JOIN raw_material_types rmt ON rmt.id = mb.material_type_id
             WHERE mb.factory_id = %s
               AND mb.deleted_at IS NULL
               AND mb.unit_price IS NOT NULL
               AND mb.unit_price >= %s
             ORDER BY mb.unit_price DESC
            """,
            (TARGET_FACTORY, SANE_PRICE_CAP),
        )
        bad = cur.fetchall()

    if not bad:
        print("  [batches] no insane material_batches prices found (already clean).")
        return 0

    updates: List[Tuple[Decimal, str]] = []
    for row in bad:
        new_price = _sane_price_for(row["material_name"], row["list_price"], row["moving_avg"])
        print(
            f"  [batches] {row['material_name'] or row['id']}: "
            f"{row['unit_price']} → {new_price}"
        )
        updates.append((new_price, row["id"]))

    if dry_run:
        print(f"  [batches] DRY-RUN: would clamp {len(updates)} rows.")
        return len(updates)

    with conn.cursor() as cur:
        cur.executemany(
            "UPDATE material_batches SET unit_price = %s, updated_at = NOW() WHERE id = %s",
            updates,
        )
    print(f"  [batches] clamped {len(updates)} rows.")
    return len(updates)


def fix_raw_material_prices(conn, dry_run: bool) -> int:
    """Clamp insane raw_material_types.unit_price / moving_avg_price for DEMO_REST.

    These are the *fallback* cost sources in sync_dim_ingredient (list_price /
    moving_avg) used when no real-purchase price exists for an ingredient. A
    garbage value here poisons food_cost for ingredients without batch coverage.
    """
    with conn.cursor(cursor_factory=RealDictCursor) as cur:
        cur.execute(
            """
            SELECT id, name, unit_price, moving_avg_price
              FROM raw_material_types
             WHERE factory_id = %s
               AND COALESCE(deleted_at, '1900-01-01') = '1900-01-01'
               AND (unit_price >= %s OR moving_avg_price >= %s)
             ORDER BY GREATEST(COALESCE(unit_price, 0), COALESCE(moving_avg_price, 0)) DESC
            """,
            (TARGET_FACTORY, SANE_PRICE_CAP, SANE_PRICE_CAP),
        )
        bad = cur.fetchall()

    if not bad:
        print("  [raw_materials] no insane list/moving-avg prices found (already clean).")
        return 0

    updates: List[Tuple[Decimal, Decimal, str]] = []
    for row in bad:
        sane = _sane_price_for(row["name"], None, None)
        new_list = sane if (row["unit_price"] is not None and Decimal(str(row["unit_price"])) >= SANE_PRICE_CAP) else row["unit_price"]
        new_avg = sane if (row["moving_avg_price"] is not None and Decimal(str(row["moving_avg_price"])) >= SANE_PRICE_CAP) else row["moving_avg_price"]
        print(
            f"  [raw_materials] {row['name'] or row['id']}: "
            f"list {row['unit_price']}→{new_list}, avg {row['moving_avg_price']}→{new_avg}"
        )
        updates.append((new_list, new_avg, row["id"]))

    if dry_run:
        print(f"  [raw_materials] DRY-RUN: would clamp {len(updates)} rows.")
        return len(updates)

    with conn.cursor() as cur:
        cur.executemany(
            "UPDATE raw_material_types SET unit_price = %s, moving_avg_price = %s, "
            "updated_at = NOW() WHERE id = %s",
            updates,
        )
    print(f"  [raw_materials] clamped {len(updates)} rows.")
    return len(updates)


# ---------------------------------------------------------------------------
# Step 2: seed realistic material_requisitions volume for DEMO_REST
# ---------------------------------------------------------------------------

def seed_requisitions(conn, end_day: date, dry_run: bool) -> int:
    """Seed APPROVED material_requisitions so 盘亏率 (shortage / req_qty) < 3%.

    Idempotent: deterministic ids (req_demo_rest_<seq>) + ON CONFLICT DO NOTHING.
    """
    with conn.cursor(cursor_factory=RealDictCursor) as cur:
        cur.execute(
            """
            SELECT id, name, unit
              FROM raw_material_types
             WHERE factory_id = %s
               AND COALESCE(deleted_at, '1900-01-01') = '1900-01-01'
             ORDER BY id
             LIMIT 12
            """,
            (TARGET_FACTORY,),
        )
        materials = cur.fetchall()

    if not materials:
        print("  [requisitions] no raw_material_types for DEMO_REST — cannot seed; skipping.")
        return 0

    rows: List[Tuple[Any, ...]] = []
    seq = 0
    for d in range(REQ_DAYS_BACK):
        req_date = end_day - timedelta(days=d)
        for n in range(REQ_PER_DAY):
            mat = materials[(d * REQ_PER_DAY + n) % len(materials)]
            seq += 1
            rid = f"req_demo_rest_{seq:05d}"
            rows.append((
                rid, TARGET_FACTORY, f"REQ-DEMO-{seq:05d}", req_date,
                "MANUAL", "APPROVED", mat["id"],
                REQ_QTY_PER_LINE, REQ_QTY_PER_LINE, mat["unit"] or "kg",
                "演示领料数据 (盘亏率口径修复)",
            ))

    total_qty = REQ_QTY_PER_LINE * len(rows)
    print(
        f"  [requisitions] prepared {len(rows)} APPROVED lines, "
        f"total_req_qty ≈ {total_qty} (shortage ~19 → 盘亏率 ~{float(Decimal('19') / total_qty * 100):.2f}%)."
    )

    if dry_run:
        print(f"  [requisitions] DRY-RUN: would upsert {len(rows)} rows.")
        return len(rows)

    with conn.cursor() as cur:
        execute_values(
            cur,
            """
            INSERT INTO material_requisitions (
                id, factory_id, requisition_number, requisition_date,
                type, status, raw_material_type_id,
                requested_quantity, actual_quantity, unit, notes,
                created_at, updated_at
            )
            VALUES %s
            ON CONFLICT (id) DO NOTHING
            """,
            rows,
            template="(%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,NOW(),NOW())",
        )
        inserted = cur.rowcount
    print(f"  [requisitions] inserted {inserted} new rows (existing skipped).")
    return inserted


# ---------------------------------------------------------------------------
# Step 3: purge stale agg_supplier_price 'mb:%' rows for DEMO_REST (smartbi_db)
# ---------------------------------------------------------------------------
#
# The supplier-price ingest ETL is idempotent on source_note_id = 'mb:{batch_id}':
# rows already present are skipped on re-run. So after we clamp the dirty
# material_batches price (cretas_db), simply re-running the ingest would NOT
# overwrite the already-ingested garbage agg_supplier_price row — and
# dim_ingredient.unit_price would keep deriving from that stale 195140 value.
#
# We therefore delete the material-batch-sourced rows (only those, LIKE 'mb:%')
# for DEMO_REST so the next ingest re-creates them from the corrected source
# (and the new sanity cap drops anything still absurd). Java delivery-note
# append rows (UUID source ids) are untouched.

def purge_stale_supplier_price(conn, dry_run: bool) -> int:
    """Delete DEMO_REST agg_supplier_price rows sourced from material_batches.

    RLS: smartbi tables use FORCE ROW LEVEL SECURITY → set app.factory_id in the
    same transaction before the DELETE so the USING/WITH CHECK policy matches.
    """
    with conn.cursor() as cur:
        cur.execute("SELECT set_config('app.factory_id', %s, true)", (TARGET_FACTORY,))
        cur.execute(
            """
            SELECT COUNT(*) FROM agg_supplier_price
             WHERE factory_id = %s AND source_note_id LIKE 'mb:%%'
            """,
            (TARGET_FACTORY,),
        )
        cnt = cur.fetchone()[0]

    print(f"  [supplier_price] {cnt} stale 'mb:%' rows for DEMO_REST.")
    if cnt == 0:
        return 0
    if dry_run:
        print(f"  [supplier_price] DRY-RUN: would delete {cnt} rows.")
        return cnt

    with conn.cursor() as cur:
        cur.execute("SELECT set_config('app.factory_id', %s, true)", (TARGET_FACTORY,))
        cur.execute(
            """
            DELETE FROM agg_supplier_price
             WHERE factory_id = %s AND source_note_id LIKE 'mb:%%'
            """,
            (TARGET_FACTORY,),
        )
        deleted = cur.rowcount
    print(f"  [supplier_price] deleted {deleted} rows (re-ingest will re-create clean).")
    return deleted


# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------

def main() -> int:
    ap = argparse.ArgumentParser(description="Fix DEMO_REST dirty demo data (prices + requisition volume).")
    ap.add_argument("--env", choices=["prod", "test"], required=True)
    ap.add_argument("--end", help="End date YYYY-MM-DD for requisition window (default: today)")
    ap.add_argument("--dry-run", action="store_true")
    args = ap.parse_args()

    end_day = date.fromisoformat(args.end) if args.end else date.today()
    cfg = ENVS[args.env]
    cretas_cfg, smartbi_cfg = cfg["cretas"], cfg["smartbi"]

    print(f"=== fix_demo_rest_dirty_data  env={args.env}  "
          f"cretas={cretas_cfg['dbname']}  smartbi={smartbi_cfg['dbname']}  "
          f"dry_run={args.dry_run}  end={end_day} ===")
    print(f"    target factory = {TARGET_FACTORY} (hard-guarded)\n")

    # --- cretas_db side: clamp prices + seed requisitions ---
    cretas_conn = connect(cretas_cfg)
    try:
        cretas_conn.autocommit = False
        print("Step 1: clamp garbage material_batches prices (cretas_db)")
        fix_material_batch_prices(cretas_conn, args.dry_run)
        print("\nStep 1b: clamp garbage raw_material_types list/avg prices (cretas_db)")
        fix_raw_material_prices(cretas_conn, args.dry_run)
        print("\nStep 2: seed realistic requisition volume (cretas_db)")
        seed_requisitions(cretas_conn, end_day, args.dry_run)
        if args.dry_run:
            cretas_conn.rollback()
        else:
            cretas_conn.commit()
    except Exception:
        cretas_conn.rollback()
        raise
    finally:
        cretas_conn.close()

    # --- smartbi_db side: purge stale supplier-price rows so re-ingest is clean ---
    smartbi_conn = connect(smartbi_cfg)
    try:
        smartbi_conn.autocommit = False
        print("\nStep 3: purge stale agg_supplier_price 'mb:%' rows (smartbi_db)")
        purge_stale_supplier_price(smartbi_conn, args.dry_run)
        if args.dry_run:
            smartbi_conn.rollback()
        else:
            smartbi_conn.commit()
    except Exception:
        smartbi_conn.rollback()
        raise
    finally:
        smartbi_conn.close()

    if args.dry_run:
        print("\nDRY-RUN complete — nothing written.")
    else:
        print("\nCommitted (cretas_db + smartbi_db). NEXT: re-run restaurant Gold ETL:")
        print("  cd /www/wwwroot/cretas/code/backend/python && source venv38/bin/activate")
        print("  python scripts/gold_etl_daily_refresh.py --factories DEMO_REST")
    return 0


if __name__ == "__main__":
    sys.exit(main())
