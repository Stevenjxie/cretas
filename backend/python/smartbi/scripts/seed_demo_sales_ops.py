#!/usr/bin/env python3
"""
seed_demo_sales_ops.py
======================
Seeds **recent (last-30-day) sales/delivery/rework data** for the factory demo
tenants so the 3 KPI 看板 cards that read a rolling 30-day window light up with
REAL computed values (PR #987):

  cretas_db (transactional):
    * sales_orders            — KPI orderFulfillmentRate (COMPLETED / 有效订单)
    * sales_delivery_records  — KPI avgLeadTime (交付日 - 下单日, DELIVERED only)
    * rework_records          — KPI reworkRate (返工记录数 / 完成批次数)

Why this is needed: seed_demo_production_ops.py added recent production_batches
(so avgCycleTime lights), but the demo tenants' sales/delivery/rework rows are on
the older ≤2025 timeline — outside the KPI's NOW()-30d window — so the real
computation (correctly) returned 0. This seeds a handful of recent rows.

Tenants: DEMO_FACTORY, DEMO_FACTORY2 only.
  * F004 has 0 customers / 0 sales_orders (retail tenant, no FK source) → SKIPPED.
  * DEMO_REST is a restaurant tenant → not applicable.

FK sources are resolved at runtime from REAL existing rows (customers.id, users.id)
per tenant — no synthetic FK values invented. Tenant skipped if it lacks them.

All @Enumerated(EnumType.STRING) values asserted in-memory against the Java enum
sets before insert:
  * sales_orders.status         ∈ SalesOrderStatus  (COMPLETED / PROCESSING)
  * sales_delivery_records.status ∈ SalesDeliveryStatus (DELIVERED)
  * rework_records.rework_type  ∈ ReworkType
  * rework_records.status       ∈ ReworkStatus  (COMPLETED)

RLS: sales_orders / sales_delivery_records / rework_records have rowsecurity=off
on prod (verified 2026-06-17) → no SET app.factory_id needed.

Idempotent: DELETEs only this script's own rows (order_number / delivery_number
prefix 'KPIDEMO-', rework_records.notes prefix 'KPIDEMO') in FK-safe order, then
re-INSERTs. Never touches existing legitimate demo orders/deliveries/rework.

Date range: orders in [end-28, end-3]; deliveries order_date + 3..12d (≤ end);
rework start_time in [end-28, end-1]. --end default today, never future.

Usage (server):
  cd /www/wwwroot/cretas/code/backend/python && source venv38/bin/activate
  python smartbi/scripts/seed_demo_sales_ops.py --env prod --end 2026-06-17
  python smartbi/scripts/seed_demo_sales_ops.py --env prod --end 2026-06-17 --dry-run

Author: feat/demo-sales-ops-seed worktree
"""
from __future__ import annotations

import argparse
import random
import sys
import uuid
from datetime import date, datetime, timedelta
from decimal import Decimal
from typing import Any, Dict, List, Tuple

try:
    import psycopg2
    from psycopg2.extras import execute_values
except ImportError:
    print("ERROR: psycopg2 not installed. Run: pip install psycopg2-binary")
    sys.exit(1)

# ---------------------------------------------------------------------------
# ENV CONFIG (mirrors seed_demo_production_ops.py)
# ---------------------------------------------------------------------------
ENVS: Dict[str, Dict[str, Any]] = {
    "prod": {
        "host": "127.0.0.1", "port": 5432, "dbname": "cretas_prod_db",
        "user": "cretas_user", "password": "cretas123",
    },
    "test": {
        "host": "127.0.0.1", "port": 5432, "dbname": "cretas_db",
        "user": "cretas_user", "password": "cretas123",
    },
}

MFG_TENANT_IDS = ["DEMO_FACTORY", "DEMO_FACTORY2"]  # F004 has no customers → skip

MARKER = "KPIDEMO-"          # order_number / delivery_number prefix
REWORK_MARKER = "KPIDEMO"    # rework_records.notes prefix

# Legal enum sets (must match Java @Enumerated(STRING))
SALES_ORDER_STATUS = {"DRAFT", "CONFIRMED", "PENDING_FINANCE_REVIEW", "FINANCE_APPROVED",
                      "FINANCE_REJECTED", "PROCESSING", "PARTIAL_DELIVERED", "COMPLETED", "CANCELLED"}
SALES_DELIVERY_STATUS = {"DRAFT", "PENDING_WAREHOUSE_CONFIRM", "PICKED", "SHIPPED", "DELIVERED"}
REWORK_TYPE = {"PRODUCTION_REWORK", "MATERIAL_REWORK", "QUALITY_REWORK",
               "PACKAGING_REWORK", "SPECIFICATION_ADJUSTMENT"}
REWORK_STATUS = {"PENDING", "IN_PROGRESS", "COMPLETED", "FAILED", "CANCELLED"}


def _assert(value: str, allowed: set, label: str) -> str:
    if value not in allowed:
        raise ValueError(f"Illegal {label} enum value: {value!r} not in {sorted(allowed)}")
    return value


def fetch_fk_sources(cur, factory_id: str) -> Tuple[List[str], List[int]]:
    """Resolve REAL existing customer ids + user ids for a tenant."""
    cur.execute(
        "SELECT id FROM customers WHERE factory_id=%s AND deleted_at IS NULL ORDER BY id",
        (factory_id,),
    )
    customer_ids = [r[0] for r in cur.fetchall()]
    cur.execute("SELECT id FROM users WHERE factory_id=%s ORDER BY id", (factory_id,))
    user_ids = [r[0] for r in cur.fetchall()]
    return customer_ids, user_ids


def completed_batch_count_30d(cur, factory_id: str, end: date) -> int:
    cur.execute(
        "SELECT COUNT(*) FROM production_batches WHERE factory_id=%s AND status='COMPLETED' "
        "AND start_time >= %s AND start_time < %s",
        (factory_id, datetime.combine(end - timedelta(days=30), datetime.min.time()),
         datetime.combine(end + timedelta(days=1), datetime.min.time())),
    )
    return int(cur.fetchone()[0] or 0)


def delete_existing(cur, factory_id: str) -> None:
    """Idempotent: remove only this script's own rows (FK-safe order)."""
    cur.execute(
        "DELETE FROM sales_delivery_records WHERE factory_id=%s AND delivery_number LIKE %s",
        (factory_id, MARKER + "%"),
    )
    cur.execute(
        "DELETE FROM sales_orders WHERE factory_id=%s AND order_number LIKE %s",
        (factory_id, MARKER + "%"),
    )
    cur.execute(
        "DELETE FROM rework_records WHERE factory_id=%s AND notes LIKE %s",
        (factory_id, REWORK_MARKER + "%"),
    )


def build_orders(factory_id: str, customer_ids: List[str], user_ids: List[int],
                 end: date, rng: random.Random) -> Tuple[List[tuple], List[tuple]]:
    """Returns (order_rows, delivery_rows). ~28 orders, 80% COMPLETED w/ delivery."""
    n_orders = 28
    order_rows: List[tuple] = []
    delivery_rows: List[tuple] = []
    now = datetime.now()
    for i in range(n_orders):
        oid = str(uuid.uuid4())
        order_dt = end - timedelta(days=rng.randint(3, 28))
        cust = rng.choice(customer_ids)
        creator = rng.choice(user_ids)
        amount = Decimal(rng.randint(8000, 60000))
        # 80% COMPLETED (fulfilled + delivered), 20% PROCESSING (in-flight, not fulfilled)
        completed = rng.random() < 0.8
        status = _assert("COMPLETED" if completed else "PROCESSING", SALES_ORDER_STATUS, "SalesOrderStatus")
        order_no = f"{MARKER}{factory_id[:3]}-{order_dt.strftime('%Y%m%d')}-{i:03d}"
        req_delivery = order_dt + timedelta(days=rng.randint(5, 12))
        order_rows.append((
            oid, factory_id, order_no, cust, order_dt, req_delivery, amount, status,
            creator, now, now, "CREATED", 0,
        ))
        if completed:
            did = str(uuid.uuid4())
            lead_days = rng.randint(3, 12)
            deliv_dt = min(order_dt + timedelta(days=lead_days), end)
            if deliv_dt < order_dt:
                deliv_dt = order_dt
            deliv_no = f"{MARKER}{factory_id[:3]}-D-{deliv_dt.strftime('%Y%m%d')}-{i:03d}"
            dstatus = _assert("DELIVERED", SALES_DELIVERY_STATUS, "SalesDeliveryStatus")
            delivery_rows.append((
                did, factory_id, deliv_no, oid, cust, deliv_dt, dstatus, creator, amount, now, now,
            ))
    return order_rows, delivery_rows


def build_rework(factory_id: str, user_ids: List[int], completed_batches: int,
                 end: date, rng: random.Random) -> List[tuple]:
    """~4% of recent completed batches as rework records (realistic low rate)."""
    n_rework = max(1, round(completed_batches * 0.04))
    rows: List[tuple] = []
    now = datetime.now()
    for i in range(n_rework):
        start_dt = datetime.combine(end - timedelta(days=rng.randint(1, 28)), datetime.min.time()) \
            + timedelta(hours=rng.randint(8, 16))
        end_dt = start_dt + timedelta(hours=rng.randint(1, 6))
        rtype = _assert(rng.choice(["PRODUCTION_REWORK", "QUALITY_REWORK", "PACKAGING_REWORK"]),
                        REWORK_TYPE, "ReworkType")
        rstatus = _assert("COMPLETED", REWORK_STATUS, "ReworkStatus")
        qty = Decimal(rng.randint(5, 40))
        supervisor = rng.choice(user_ids)
        rows.append((
            factory_id, now, now, start_dt, end_dt, qty, rtype, rstatus,
            qty, Decimal(rng.randint(50, 500)), supervisor,
            f"{REWORK_MARKER} 演示返工记录 #{i+1}",
        ))
    return rows


def seed_tenant(cur, factory_id: str, end: date, rng: random.Random, dry_run: bool) -> Dict[str, int]:
    customer_ids, user_ids = fetch_fk_sources(cur, factory_id)
    if not customer_ids or not user_ids:
        print(f"  [{factory_id}] SKIP — no customers ({len(customer_ids)}) or users ({len(user_ids)})")
        return {"orders": 0, "deliveries": 0, "rework": 0}
    completed_batches = completed_batch_count_30d(cur, factory_id, end)

    order_rows, delivery_rows = build_orders(factory_id, customer_ids, user_ids, end, rng)
    rework_rows = build_rework(factory_id, user_ids, completed_batches, end, rng)

    if dry_run:
        print(f"  [{factory_id}] DRY-RUN: {len(order_rows)} orders "
              f"({sum(1 for r in order_rows if r[7]=='COMPLETED')} COMPLETED), "
              f"{len(delivery_rows)} deliveries, {len(rework_rows)} rework "
              f"(recent completed batches={completed_batches})")
        return {"orders": len(order_rows), "deliveries": len(delivery_rows), "rework": len(rework_rows)}

    delete_existing(cur, factory_id)

    execute_values(
        cur,
        "INSERT INTO sales_orders (id, factory_id, order_number, customer_id, order_date, "
        "required_delivery_date, total_amount, status, created_by, created_at, updated_at, vflag, version) "
        "VALUES %s",
        order_rows,
    )
    if delivery_rows:
        execute_values(
            cur,
            "INSERT INTO sales_delivery_records (id, factory_id, delivery_number, sales_order_id, "
            "customer_id, delivery_date, status, shipped_by, total_amount, created_at, updated_at) "
            "VALUES %s",
            delivery_rows,
        )
    if rework_rows:
        execute_values(
            cur,
            "INSERT INTO rework_records (factory_id, created_at, updated_at, start_time, end_time, "
            "rework_quantity, rework_type, status, success_quantity, rework_cost, supervisor_id, notes) "
            "VALUES %s",
            rework_rows,
        )
    print(f"  [{factory_id}] inserted {len(order_rows)} orders "
          f"({sum(1 for r in order_rows if r[7]=='COMPLETED')} COMPLETED + delivered), "
          f"{len(delivery_rows)} deliveries, {len(rework_rows)} rework "
          f"(recent completed batches={completed_batches})")
    return {"orders": len(order_rows), "deliveries": len(delivery_rows), "rework": len(rework_rows)}


def main() -> int:
    ap = argparse.ArgumentParser(description="Seed recent demo sales/delivery/rework for KPI cards")
    ap.add_argument("--env", choices=["prod", "test"], default="prod")
    ap.add_argument("--end", default=date.today().isoformat(),
                    help="window end date (YYYY-MM-DD), never future; default today")
    ap.add_argument("--dry-run", action="store_true")
    ap.add_argument("--seed", type=int, default=20260617, help="RNG seed for reproducibility")
    args = ap.parse_args()

    end = date.fromisoformat(args.end)
    if end > date.today():
        print(f"ERROR: --end {end} is in the future; refusing.")
        return 2
    rng = random.Random(args.seed)
    cfg = ENVS[args.env]

    print(f"=== seed_demo_sales_ops env={args.env} end={end} dry_run={args.dry_run} ===")
    conn = psycopg2.connect(host=cfg["host"], port=cfg["port"], dbname=cfg["dbname"],
                            user=cfg["user"], password=cfg["password"])
    try:
        conn.autocommit = False
        totals = {"orders": 0, "deliveries": 0, "rework": 0}
        with conn.cursor() as cur:
            for fid in MFG_TENANT_IDS:
                r = seed_tenant(cur, fid, end, rng, args.dry_run)
                for k in totals:
                    totals[k] += r[k]
        if args.dry_run:
            conn.rollback()
            print("DRY-RUN: rolled back, no writes.")
        else:
            conn.commit()
            print(f"COMMITTED. Totals: {totals}")
        return 0
    except Exception as e:
        conn.rollback()
        print(f"ERROR (rolled back): {type(e).__name__}: {e}")
        return 1
    finally:
        conn.close()


if __name__ == "__main__":
    sys.exit(main())
