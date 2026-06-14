"""Phase 2 module population for a factory demo tenant: light up the last empty modules so
the roadshow demo has data EVERYWHERE (Steve: 不要光隐藏, 导入数据).

Covers: 调整审批 (ar_ap AR_ADJUSTMENT/AP_ADJUSTMENT PENDING) / 经营分析hub + 财务报表
(smart_bi_finance_data REVENUE/COST/AR/BUDGET rows) / 异常预警 (alert_rules + alert_events) /
车间实时生产报表 + 备货看板 (shift the latest production reports + a few sales orders to TODAY).

Idempotent per concern. cretas writes need no RLS; smart_bi_finance_data is in smartbi_prod_db
(BYPASSRLS granted for the run, revoke after).
  PYTHONPATH=. CLONE_SMARTBI_SUPER_DSN=... python -m scripts.demo.gen_factory_phase2 --factory DEMO_FACTORY2 --today 2026-06-14
"""
from __future__ import annotations
import argparse, asyncio, datetime, uuid
from decimal import Decimal
import asyncpg
from scripts.demo import clone_config as cfg


def _sid(p, f, n): return f"{p}-{f[-4:]}-{n:04d}"


async def run(factory_id: str, today: datetime.date):
    c = await asyncpg.connect(cfg.CRETAS_DSN)
    s = await asyncpg.connect(cfg.SMARTBI_SUPER_DSN)
    now = datetime.datetime.now()
    try:
        uid = await c.fetchval("SELECT id FROM users WHERE factory_id=$1 ORDER BY id LIMIT 1", factory_id)
        custs = await c.fetch("SELECT id, name FROM customers WHERE factory_id=$1 LIMIT 6", factory_id)
        supps = await c.fetch("SELECT id, name FROM suppliers WHERE factory_id=$1 LIMIT 4", factory_id)

        # ---- 调整审批: AR/AP ADJUSTMENT in PENDING (awaiting finance approval) ----
        await c.execute("DELETE FROM ar_ap_transactions WHERE factory_id=$1 AND transaction_type LIKE '%ADJUSTMENT%'", factory_id)
        n = 50000
        for i, cu in enumerate(custs[:4]):
            n += 1
            await c.execute(
                "INSERT INTO ar_ap_transactions (id, factory_id, transaction_number, transaction_type, "
                "counterparty_type, counterparty_id, counterparty_name, amount, balance_after, transaction_date, "
                "operated_by, approval_status, remark, created_at, updated_at) "
                "VALUES ($1,$2,$3,'AR_ADJUSTMENT','CUSTOMER',$4,$5,$6,$6,$7,$8,'PENDING',$9,$10,$10)",
                _sid("ARADJ", factory_id, i + 1), factory_id, _sid("ADJ", factory_id, n), cu["id"], cu["name"],
                Decimal("1000") + Decimal(i * 350), today - datetime.timedelta(days=i + 1), uid,
                "对账差异调整" if i % 2 == 0 else "折让调整", now)
        for i, su in enumerate(supps[:2]):
            n += 1
            await c.execute(
                "INSERT INTO ar_ap_transactions (id, factory_id, transaction_number, transaction_type, "
                "counterparty_type, counterparty_id, counterparty_name, amount, balance_after, transaction_date, "
                "operated_by, approval_status, remark, created_at, updated_at) "
                "VALUES ($1,$2,$3,'AP_ADJUSTMENT','SUPPLIER',$4,$5,$6,$6,$7,$8,'PENDING',$9,$10,$10)",
                _sid("APADJ", factory_id, i + 1), factory_id, _sid("ADJ", factory_id, n), su["id"], su["name"],
                Decimal("800") + Decimal(i * 200), today - datetime.timedelta(days=i + 2), uid, "采购返利调整", now)

        # ---- smart_bi_finance_data: 经营分析hub 财务 tabs (REVENUE/COST/AR/BUDGET by month) ----
        await s.execute("DELETE FROM smart_bi_finance_data WHERE factory_id=$1", factory_id)
        months = [datetime.date(2026, m, 1) for m in range(1, 7)]
        base_rev = Decimal("1180000")
        fid = 0
        for mi, mon in enumerate(months):
            rev = base_rev * (Decimal("0.85") + Decimal(mi) * Decimal("0.06"))
            mat = rev * Decimal("0.52"); lab = rev * Decimal("0.14"); ovh = rev * Decimal("0.08")
            rows = [
                ("REVENUE", {"actual_amount": rev}),
                ("COST", {"material_cost": mat, "labor_cost": lab, "overhead_cost": ovh, "total_cost": mat + lab + ovh}),
                ("AR", {"receivable_amount": rev * Decimal("0.30"), "collection_amount": rev * Decimal("0.22"), "aging_days": 18}),
                ("BUDGET", {"budget_amount": rev * Decimal("1.05"), "actual_amount": rev, "variance_amount": rev * Decimal("-0.05")}),
            ]
            for rt, vals in rows:
                fid += 1
                # id is bigint serial -> omit, let DB auto-generate
                cols = ["factory_id", "record_date", "record_type", "department", "category", "created_at", "updated_at"]
                args = [factory_id, mon, rt, "生产部", "卤味加工", now, now]
                for k, v in vals.items():
                    cols.append(k); args.append(v if not isinstance(v, Decimal) else v.quantize(Decimal("0.01")))
                ph = ", ".join(f"${i+1}" for i in range(len(cols)))
                await s.execute(f"INSERT INTO smart_bi_finance_data ({', '.join(cols)}) VALUES ({ph})", *args)

        # ---- 异常预警: alert_rules + alert_events ----
        await c.execute("DELETE FROM alert_events WHERE factory_id=$1", factory_id)
        await c.execute("DELETE FROM alert_rules WHERE factory_id=$1", factory_id)
        rules = [
            ("INVENTORY_LOW", "原料库存预警", "HIGH"),
            ("PAYMENT_OVERDUE", "应收逾期预警", "MID"),
            ("WASTAGE_HIGH", "损耗率超标预警", "MID"),
            ("YIELD_LOW", "出成率偏低预警", "LOW"),
        ]
        rule_ids = []
        for i, (atype, rname, sev) in enumerate(rules):
            rid = str(uuid.UUID(int=(0xDF2A1E000000 + i)))
            rule_ids.append((rid, atype, sev))
            await c.execute(
                "INSERT INTO alert_rules (id, factory_id, alert_type, rule_name, severity, enabled, version, created_at, updated_at) "
                "VALUES ($1,$2,$3,$4,$5,true,0,$6,$6)", rid, factory_id, atype, rname, sev, now)
        msgs = {
            "INVENTORY_LOW": "原料「{m}」库存低于安全线, 当前 {q}kg",
            "PAYMENT_OVERDUE": "客户「{m}」应收账款逾期 {q} 天",
            "WASTAGE_HIGH": "「{m}」批次损耗率 {q}% 超过阈值",
            "YIELD_LOW": "工序「{m}」出成率 {q}% 低于标准",
        }
        sample_m = ["猪舌", "掌中宝", "牛腱", "猪蹄"]
        ev = 0
        for i, (rid, atype, sev) in enumerate(rule_ids):
            for j in range(2):  # 2 events per rule
                ev += 1
                msg = msgs[atype].format(m=sample_m[(i + j) % len(sample_m)], q=(12 + i * 3 + j * 5))
                await c.execute(
                    "INSERT INTO alert_events (id, rule_id, factory_id, severity, message, status, created_at, updated_at) "
                    "VALUES ($1,$2,$3,$4,$5,'OPEN',$6,$6)",
                    str(uuid.UUID(int=(0xDF2E0E000000 + ev))), rid, factory_id, sev, msg,
                    now - datetime.timedelta(hours=ev * 5))

        # ---- 车间实时生产报表: shift the latest few production reports + their batches to TODAY ----
        latest = await c.fetch(
            "SELECT id, batch_id FROM production_reports WHERE factory_id=$1 AND report_date IS NOT NULL "
            "ORDER BY report_date DESC LIMIT 6", factory_id)
        shifted = 0
        for r in latest:
            await c.execute("UPDATE production_reports SET report_date=$2 WHERE id=$1", r["id"], today)
            shifted += 1
        # ---- 备货看板: a few sales orders delivering in the next 5 days ----
        recent_orders = await c.fetch(
            "SELECT id FROM sales_orders WHERE factory_id=$1 ORDER BY order_date DESC LIMIT 5", factory_id)
        for k, o in enumerate(recent_orders):
            await c.execute("UPDATE sales_orders SET required_delivery_date=$2 WHERE id=$1",
                            o["id"], today + datetime.timedelta(days=k + 1))

        # report
        adj = await c.fetchval("SELECT count(*) FROM ar_ap_transactions WHERE factory_id=$1 AND transaction_type LIKE '%ADJUSTMENT%'", factory_id)
        fin = await s.fetchval("SELECT count(*) FROM smart_bi_finance_data WHERE factory_id=$1", factory_id)
        alr = await c.fetchval("SELECT count(*) FROM alert_events WHERE factory_id=$1", factory_id)
        print(f"[phase2] {factory_id}: adjustments={adj} smart_bi_finance={fin} alert_events={alr} "
              f"reports_shifted_to_today={shifted} orders_delivery_future={len(recent_orders)}")
    finally:
        await c.close(); await s.close()


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--factory", required=True)
    ap.add_argument("--today", required=True)
    a = ap.parse_args()
    asyncio.run(run(a.factory, datetime.date.fromisoformat(a.today)))


if __name__ == "__main__":
    main()
