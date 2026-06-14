"""Generate the gold cockpit aggregates for a factory demo tenant that has NO POS data.

The 经营驾驶舱 (gold_reads.py) reads exclusively from agg_daily / agg_product / dim_store /
dim_product. A manufacturing factory (e.g. F006-cloned DEMO_FACTORY2) has sales_orders but no
fact_pos_transaction, so those gold tables are empty and the cockpit shows "honest empty".

This script synthesizes a coherent FACTORY cockpit from the already-cloned + de-identified
operational sales data (cretas_prod_db): each customer that placed orders becomes a 网点 (store),
each product_type a dim_product, sales_orders aggregate into agg_daily, sales_order_items into
agg_product. Numbers are REAL (from the cloned orders); only the retail-shaped gold envelope is
synthesized. Idempotent: deletes the tenant's rows in the 4 tables first.

Usage (server, venv38):
  PYTHONPATH=. CLONE_SMARTBI_SUPER_DSN=... python -m scripts.demo.gen_factory_cockpit --factory DEMO_FACTORY2
"""
from __future__ import annotations
import argparse, asyncio, datetime
import asyncpg
from scripts.demo import clone_config as cfg

STORE_ID_BASE = 2_100_000_000
PRODUCT_ID_BASE = 2_200_000_000


async def run(factory_id: str):
    cret = await asyncpg.connect(cfg.CRETAS_DSN)
    smart = await asyncpg.connect(cfg.SMARTBI_SUPER_DSN)
    now = datetime.datetime.now()
    try:
        # --- read operational source (already cloned + de-identified) ---
        ptypes = await cret.fetch(
            "SELECT id, name, product_category FROM product_types WHERE factory_id=$1", factory_id)
        # customers that actually placed orders -> become 网点
        custs = await cret.fetch(
            "SELECT DISTINCT c.id, c.name FROM customers c "
            "JOIN sales_orders so ON so.customer_id=c.id AND so.factory_id=$1 "
            "WHERE c.factory_id=$1 ORDER BY c.id", factory_id)
        orders = await cret.fetch(
            "SELECT order_number, customer_id, order_date, total_amount, discount_amount, paid_amount "
            "FROM sales_orders WHERE factory_id=$1 AND order_date IS NOT NULL", factory_id)
        items = await cret.fetch(
            "SELECT i.product_type_id, i.quantity, i.unit_price, so.order_number, so.order_date "
            "FROM sales_order_items i JOIN sales_orders so ON so.id=i.sales_order_id "
            "WHERE so.factory_id=$1 AND so.order_date IS NOT NULL", factory_id)

        store_id = {r["id"]: STORE_ID_BASE + i for i, r in enumerate(custs)}
        # dedup dim_product by normalized_name (uq_dim_product_factory_normname): test-cruft products
        # that collapse to the same cleaned name (e.g. 5×"卤牛腱") share one product_id, so their
        # sales aggregate together instead of violating the unique constraint.
        norm_to_pid: dict = {}
        product_id: dict = {}
        dim_rows = []  # (pid, name, normalized_name, category)
        for r in ptypes:
            nm = (r["name"] or "示范产品").strip()
            norm = nm.lower()
            if norm not in norm_to_pid:
                pid = PRODUCT_ID_BASE + len(norm_to_pid)
                norm_to_pid[norm] = pid
                dim_rows.append((pid, nm, norm, r["product_category"]))
            product_id[r["id"]] = norm_to_pid[norm]
        # fallback store for orders whose customer didn't make the distinct list (defensive)
        default_store = (STORE_ID_BASE + len(custs)) if custs else STORE_ID_BASE

        # --- wipe prior tenant rows (idempotent) ---
        for tbl in ("agg_daily", "agg_product", "dim_store", "dim_product"):
            await smart.execute(f"DELETE FROM {tbl} WHERE factory_id=$1", factory_id)

        # --- dim_store (网点 = customers) ---
        for r in custs:
            await smart.execute(
                "INSERT INTO dim_store (store_id, factory_id, name, city, province, region, created_at, updated_at) "
                "VALUES ($1,$2,$3,$4,$5,$6,$7,$7)",
                store_id[r["id"]], factory_id, r["name"] or "示范客户", "—", "—", "—", now)
        if not custs:
            await smart.execute(
                "INSERT INTO dim_store (store_id, factory_id, name, created_at, updated_at) VALUES ($1,$2,$3,$4,$4)",
                default_store, factory_id, "厂区直销", now)

        # --- dim_product (deduped by normalized_name) ---
        for pid, nm, norm, category in dim_rows:
            await smart.execute(
                "INSERT INTO dim_product (product_id, factory_id, name, normalized_name, category, created_at, updated_at) "
                "VALUES ($1,$2,$3,$4,$5,$6,$6)",
                pid, factory_id, nm, norm, category, now)

        # --- agg_daily: per (date, store) ---
        daily: dict = {}
        for o in orders:
            sid = store_id.get(o["customer_id"], default_store)
            key = (o["order_date"], sid)
            d = daily.setdefault(key, {"gross": 0, "disc": 0, "recv": 0, "bills": 0})
            d["gross"] += float(o["total_amount"] or 0)
            d["disc"] += float(o["discount_amount"] or 0)
            d["recv"] += float(o["paid_amount"] or 0)
            d["bills"] += 1
        # item_count per (date) — spread by order via separate count
        for (date, sid), d in daily.items():
            net = d["gross"] - d["disc"]
            await smart.execute(
                "INSERT INTO agg_daily (factory_id, date, store_id, gross_amount, discount_amount, net_amount, "
                "actual_receive, bill_count, customer_count, item_count, version, computed_at) "
                "VALUES ($1,$2,$3,$4,$5,$6,$7,$8,$9,$10,1,$11)",
                factory_id, date, sid, d["gross"], d["disc"], net,
                d["recv"] if d["recv"] else net, d["bills"], 1, d["bills"], now)

        # --- agg_product: per (month, product) ---
        prod: dict = {}
        for it in items:
            pid = product_id.get(it["product_type_id"])
            if pid is None:
                continue
            month = it["order_date"].replace(day=1)  # DATE column = first day of month
            key = (month, pid)
            p = prod.setdefault(key, {"qty": 0, "rev": 0.0, "bills": set()})
            p["qty"] += float(it["quantity"] or 0)
            p["rev"] += float(it["quantity"] or 0) * float(it["unit_price"] or 0)
            p["bills"].add(it["order_number"])
        for (month, pid), p in prod.items():
            await smart.execute(
                "INSERT INTO agg_product (factory_id, product_id, month, qty_sold, revenue, bill_count, version, computed_at) "
                "VALUES ($1,$2,$3,$4,$5,$6,1,$7)",
                factory_id, pid, month, p["qty"], round(p["rev"], 2), len(p["bills"]), now)

        print(f"[cockpit] {factory_id}: dim_store={len(custs) or 1} dim_product={len(dim_rows)} "
              f"agg_daily={len(daily)} agg_product={len(prod)}")
    finally:
        await cret.close()
        await smart.close()


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--factory", required=True)
    a = ap.parse_args()
    asyncio.run(run(a.factory))


if __name__ == "__main__":
    main()
