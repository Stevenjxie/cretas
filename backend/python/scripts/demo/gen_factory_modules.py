"""Populate the empty operational/finance modules for a factory demo tenant with realistic
data derived from its already-cloned sales/purchase orders + materials + production batches.

The roadshow demo should have data in EVERY visible module (Steve: "don't just hide empty
modules — import data"). A manufacturing clone (DEMO_FACTORY2 from F006) has rich production
data but no finance ledger / requisitions / wastage / samples / stocktakes (those weren't in
the source). This synthesizes coherent records keyed to the real cloned entities, so 应收应付/
开票/收款/物料需求/报损/研发样品/盘点 all light up. Numbers track the real order amounts.

Idempotent: deletes the tenant's rows in each table first. Run on the server (venv38).
  PYTHONPATH=. CLONE_CRETAS_DSN=... python -m scripts.demo.gen_factory_modules --factory DEMO_FACTORY2
"""
from __future__ import annotations
import argparse, asyncio, datetime
from decimal import Decimal
import asyncpg
from scripts.demo import clone_config as cfg


def _sid(prefix: str, factory: str, n: int) -> str:
    return f"{prefix}-{factory[-4:]}-{n:04d}"


async def run(factory_id: str):
    c = await asyncpg.connect(cfg.CRETAS_DSN)
    now = datetime.datetime.now()
    try:
        wh = await c.fetchval("SELECT id FROM factory_warehouses WHERE factory_id=$1 LIMIT 1", factory_id)
        uid = await c.fetchval("SELECT id FROM users WHERE factory_id=$1 ORDER BY id LIMIT 1", factory_id)
        orders = await c.fetch(
            "SELECT id, order_number, customer_id, order_date, total_amount, tax_amount "
            "FROM sales_orders WHERE factory_id=$1 AND order_date IS NOT NULL ORDER BY order_date", factory_id)
        cust = {r["id"]: r["name"] for r in await c.fetch(
            "SELECT id, name FROM customers WHERE factory_id=$1", factory_id)}
        pos = await c.fetch(
            "SELECT id, order_number, supplier_id, order_date, total_amount "
            "FROM purchase_orders WHERE factory_id=$1 AND order_date IS NOT NULL ORDER BY order_date", factory_id)
        supp = {r["id"]: r["name"] for r in await c.fetch(
            "SELECT id, name FROM suppliers WHERE factory_id=$1", factory_id)}
        mats = await c.fetch(
            "SELECT id, name FROM raw_material_types WHERE factory_id=$1 ORDER BY id", factory_id)
        prods = await c.fetch(
            "SELECT id, name FROM product_types WHERE factory_id=$1 ORDER BY id", factory_id)
        batches = await c.fetch(
            "SELECT id, product_type_id, created_at FROM production_batches WHERE factory_id=$1 ORDER BY id LIMIT 40", factory_id)
        plans = await c.fetch("SELECT id FROM production_plans WHERE factory_id=$1 ORDER BY id LIMIT 40", factory_id)
        mbatches = await c.fetch(
            "SELECT id, material_type_id FROM material_batches WHERE factory_id=$1 ORDER BY id LIMIT 30", factory_id)
        warehouses = [r["id"] for r in await c.fetch(
            "SELECT id FROM factory_warehouses WHERE factory_id=$1 ORDER BY id", factory_id)]

        # wipe prior (right tables the factory pages read + clean the wrong-table junk from earlier run)
        for t in ("ar_ap_transactions", "invoice_records", "payment_records",
                  "factory_material_requisitions", "wastage_reports", "rd_requests",
                  "product_samples", "factory_stocktakes",
                  "material_requisitions", "wastage_records"):
            await c.execute(f"DELETE FROM {t} WHERE factory_id=$1", factory_id)

        # ---- ar_ap_transactions: AR per sales order, AP per purchase order, ~60% collected ----
        bal: dict = {}
        n = 0
        for i, o in enumerate(orders):
            amt = Decimal(str(o["total_amount"] or 0))
            cname = cust.get(o["customer_id"], "示范客户")
            bal[o["customer_id"]] = bal.get(o["customer_id"], Decimal(0)) + amt
            n += 1
            await c.execute(
                "INSERT INTO ar_ap_transactions (id, factory_id, transaction_number, transaction_type, "
                "counterparty_type, counterparty_id, counterparty_name, sales_order_id, amount, balance_after, "
                "transaction_date, due_date, operated_by, approval_status, created_at, updated_at) "
                "VALUES ($1,$2,$3,'AR_INVOICE','CUSTOMER',$4,$5,$6,$7,$8,$9,$10,$11,'APPROVED',$12,$12)",
                _sid("AR", factory_id, n), factory_id, _sid("ARTX", factory_id, n), o["customer_id"], cname,
                o["id"], amt, bal[o["customer_id"]], o["order_date"], o["order_date"] + datetime.timedelta(days=30), uid, now)
            if i % 5 != 0:  # ~80% collected -> AR_PAYMENT
                n += 1
                bal[o["customer_id"]] -= amt
                await c.execute(
                    "INSERT INTO ar_ap_transactions (id, factory_id, transaction_number, transaction_type, "
                    "counterparty_type, counterparty_id, counterparty_name, sales_order_id, amount, balance_after, "
                    "transaction_date, payment_method, operated_by, approval_status, created_at, updated_at) "
                    "VALUES ($1,$2,$3,'AR_PAYMENT','CUSTOMER',$4,$5,$6,$7,$8,$9,'BANK_TRANSFER',$10,'APPROVED',$11,$11)",
                    _sid("AR", factory_id, n), factory_id, _sid("ARTX", factory_id, n), o["customer_id"], cname,
                    o["id"], amt, bal[o["customer_id"]], o["order_date"] + datetime.timedelta(days=10), uid, now)
        for j, p in enumerate(pos):
            amt = Decimal(str(p["total_amount"] or 0))
            sname = supp.get(p["supplier_id"], "示范供应商")
            bal[p["supplier_id"]] = bal.get(p["supplier_id"], Decimal(0)) + amt
            n += 1
            await c.execute(
                "INSERT INTO ar_ap_transactions (id, factory_id, transaction_number, transaction_type, "
                "counterparty_type, counterparty_id, counterparty_name, purchase_order_id, amount, balance_after, "
                "transaction_date, due_date, operated_by, approval_status, created_at, updated_at) "
                "VALUES ($1,$2,$3,'AP_INVOICE','SUPPLIER',$4,$5,$6,$7,$8,$9,$10,$11,'APPROVED',$12,$12)",
                _sid("AP", factory_id, n), factory_id, _sid("APTX", factory_id, n), p["supplier_id"], sname,
                p["id"], amt, bal[p["supplier_id"]], p["order_date"], p["order_date"] + datetime.timedelta(days=30), uid, now)

        # ---- invoice_records: per sales order, mixed status ----
        inv_status = ["ISSUED", "ISSUED", "APPROVED", "REQUESTED"]
        for i, o in enumerate(orders):
            amt = Decimal(str(o["total_amount"] or 0))
            tax = Decimal(str(o["tax_amount"] or 0)) or (amt * Decimal("0.06")).quantize(Decimal("0.01"))
            st = inv_status[i % len(inv_status)]
            await c.execute(
                "INSERT INTO invoice_records (id, factory_id, invoice_number, sales_order_id, customer_id, "
                "customer_name, amount, tax_amount, total_amount, invoice_type, status, requested_by, requested_at, "
                "issued_at, created_at, updated_at) "
                "VALUES ($1,$2,$3,$4,$5,$6,$7,$8,$9,'NORMAL',$10,$11,$12,$13,$12,$12)",
                _sid("INV", factory_id, i + 1), factory_id, _sid("FP", factory_id, i + 1), o["id"], o["customer_id"],
                cust.get(o["customer_id"], "示范客户"), amt, tax, amt + tax, st, uid, o["order_date"],
                (o["order_date"] if st == "ISSUED" else None))

        # ---- payment_records: per sales order, mostly VERIFIED ----
        for i, o in enumerate(orders):
            amt = Decimal(str(o["total_amount"] or 0))
            st = "VERIFIED" if i % 6 != 0 else "PENDING"
            await c.execute(
                "INSERT INTO payment_records (id, factory_id, payment_number, sales_order_id, customer_id, "
                "customer_name, amount, payment_method, payment_date, status, recorded_by, verified_by, verified_at, "
                "created_at, updated_at) "
                "VALUES ($1,$2,$3,$4,$5,$6,$7,'BANK_TRANSFER',$8,$9,$10,$11,$12,$13,$13)",
                _sid("PAY", factory_id, i + 1), factory_id, _sid("SK", factory_id, i + 1), o["id"], o["customer_id"],
                cust.get(o["customer_id"], "示范客户"), amt, o["order_date"] + datetime.timedelta(days=8), st, uid,
                (uid if st == "VERIFIED" else None), (o["order_date"] + datetime.timedelta(days=9) if st == "VERIFIED" else None), now)

        # ---- factory_material_requisitions: one per production plan (BOM 领料, 工厂物料需求单) ----
        fmr_status = ["ISSUED", "ISSUED", "PICKING", "IN_USE", "CLOSED", "PENDING"]
        wh_src = warehouses[0] if warehouses else None
        wh_tgt = warehouses[1] if len(warehouses) > 1 else wh_src
        for i, pl in enumerate(plans):
            await c.execute(
                "INSERT INTO factory_material_requisitions (id, factory_id, requisition_no, production_plan_id, "
                "source_warehouse_id, target_warehouse_id, status, required_date, requested_by, created_at, updated_at) "
                "VALUES ($1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$10)",
                _sid("FMR", factory_id, i + 1), factory_id, _sid("LL", factory_id, i + 1), pl["id"],
                wh_src, wh_tgt, fmr_status[i % len(fmr_status)], now.date(), uid, now)

        # ---- wastage_reports: a dozen FACTORY-track 报损 (报损管理 page) ----
        wreasons = ["加工损耗", "保质期临近", "包装破损", "质检不合格", "运输破损"]
        for i, mb in enumerate(mbatches[:12]):
            await c.execute(
                "INSERT INTO wastage_reports (id, factory_id, report_no, track_type, warehouse_id, material_batch_id, "
                "raw_material_type_id, wastage_qty, wastage_reason, reason_detail, photo_urls, status, submitted_by, "
                "submitted_at, approved_by, approved_at, applied_at, created_at, updated_at) "
                "VALUES ($1,$2,$3,'FACTORY',$4,$5,$6,$7,$8,$9,$10,'APPLIED',$11,$12,$11,$12,$12,$12,$12)",
                _sid("WR", factory_id, i + 1), factory_id, _sid("BS", factory_id, i + 1), wh_src, mb["id"],
                mb["material_type_id"], Decimal("2") + Decimal(i % 8), wreasons[i % len(wreasons)], "示例报损说明",
                f'["https://demo.cretas/wastage_{i + 1}.jpg"]', uid, now)

        # ---- rd_requests: 研发请求 (研发样品 page 主列表) ----
        rd_status = ["IN_PROGRESS", "COMPLETED", "ASSIGNED", "SUBMITTED", "COMPLETED", "IN_PROGRESS"]
        for i, p in enumerate(prods[:6]):
            await c.execute(
                "INSERT INTO rd_requests (id, factory_id, request_number, status, customer_name, requirements, "
                "urgency, created_at, updated_at) VALUES ($1,$2,$3,$4,$5,$6,$7,$8,$8)",
                _sid("RDR", factory_id, i + 1), factory_id, _sid("YF", factory_id, i + 1),
                rd_status[i % len(rd_status)], "示范客户", (p["name"] or "新产品") + " 打样需求 (规格/口味/包装确认)",
                "HIGH" if i % 2 == 0 else "MEDIUM", now)

        # ---- product_samples: a handful of R&D samples ----
        sstatus = ["APPROVED", "IN_PROGRESS", "SUBMITTED", "DRAFT"]
        for i, p in enumerate(prods[:8]):
            await c.execute(
                "INSERT INTO product_samples (id, factory_id, sample_code, name, specification, status, "
                "product_type_id, main_material, submitted_by, created_at, updated_at) "
                "VALUES ($1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$10)",
                _sid("SMP", factory_id, i + 1), factory_id, _sid("YP", factory_id, i + 1),
                (p["name"] or "示范样品") + " 研发样", "袋装 200g", sstatus[i % len(sstatus)], p["id"],
                (p["name"] or "原料"), uid, now)

        # ---- factory_stocktakes: 2 monthly stocktakes ----
        if wh:
            for i, mon in enumerate(["2026-05", "2026-06"]):
                await c.execute(
                    "INSERT INTO factory_stocktakes (id, factory_id, stocktake_no, warehouse_id, period_month, "
                    "status, initiated_by, initiated_at, submitted_by, submitted_at, approved_by, approved_at, "
                    "created_at, updated_at) "
                    "VALUES ($1,$2,$3,$4,$5,'APPLIED',$6,$7,$6,$7,$6,$7,$7,$7)",
                    _sid("STK", factory_id, i + 1), factory_id, _sid("PD", factory_id, i + 1), wh, mon, uid, now)

        # report
        counts = {}
        for t in ("ar_ap_transactions", "invoice_records", "payment_records", "factory_material_requisitions",
                  "wastage_reports", "rd_requests", "product_samples", "factory_stocktakes"):
            counts[t] = await c.fetchval(f"SELECT count(*) FROM {t} WHERE factory_id=$1", factory_id)
        print(f"[modules] {factory_id}: " + " ".join(f"{k}={v}" for k, v in counts.items()))
    finally:
        await c.close()


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--factory", required=True)
    a = ap.parse_args()
    asyncio.run(run(a.factory))


if __name__ == "__main__":
    main()
