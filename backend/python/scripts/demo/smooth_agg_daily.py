"""Smooth a factory demo tenant's agg_daily into a clean steady-growth daily series.

The cockpit 销售趋势 computes (last_day - first_day)/first_day. With raw per-order-date
aggregation, B2B order lumpiness (one ¥5.6M day vs a ¥14k first day) produces an absurd
"+5086%" trend. This redistributes the SAME total revenue + bill count across every day in the
range with a gentle upward trend + mild daily variation, so the trend reads like a healthy
ramp (~+30%) instead of a spike. Product breakdown (agg_product) is untouched — only the daily
envelope (used for the trend line) is reshaped.

  PYTHONPATH=. CLONE_SMARTBI_SUPER_DSN=... python -m scripts.demo.smooth_agg_daily --factory DEMO_FACTORY2
"""
from __future__ import annotations
import argparse, asyncio, datetime, math
import asyncpg
from scripts.demo import clone_config as cfg


async def run(factory_id: str):
    s = await asyncpg.connect(cfg.SMARTBI_SUPER_DSN)
    now = datetime.datetime.now()
    try:
        agg = await s.fetchrow(
            "SELECT min(date) d0, max(date) d1, sum(gross_amount) g, sum(discount_amount) disc, "
            "sum(actual_receive) recv, sum(bill_count) bills FROM agg_daily WHERE factory_id=$1", factory_id)
        if not agg or not agg["d0"]:
            print(f"[smooth] {factory_id}: no agg_daily, skip"); return
        stores = [r["store_id"] for r in await s.fetch(
            "SELECT DISTINCT store_id FROM agg_daily WHERE factory_id=$1 ORDER BY store_id", factory_id)]
        d0, d1 = agg["d0"], agg["d1"]
        days = (d1 - d0).days + 1
        total_g = float(agg["g"] or 0)
        total_bills = int(agg["bills"] or 0)
        disc_ratio = float(agg["disc"] or 0) / total_g if total_g else 0
        recv_ratio = float(agg["recv"] or 0) / total_g if total_g else 0.9

        # weight each day: linear ramp 0.7 -> 1.3 (steady growth) + mild sine season, all positive.
        weights = []
        for i in range(days):
            t = i / max(days - 1, 1)
            ramp = 0.7 + 0.6 * t                 # +~30% first->last day (healthy trend, not a spike)
            season = 1.0 + 0.12 * math.sin(i * 2 * math.pi / 7)  # weekly wiggle
            weights.append(ramp * season)
        wsum = sum(weights)

        await s.execute("DELETE FROM agg_daily WHERE factory_id=$1", factory_id)
        bills_left = total_bills
        for i in range(days):
            date = d0 + datetime.timedelta(days=i)
            frac = weights[i] / wsum
            gross = round(total_g * frac, 2)
            disc = round(gross * disc_ratio, 2)
            net = round(gross - disc, 2)
            recv = round(gross * recv_ratio, 2)
            # distribute bills proportionally, remainder on last day
            bills = max(1, round(total_bills * frac)) if i < days - 1 else max(1, bills_left)
            bills_left -= bills
            store = stores[i % len(stores)] if stores else 0
            await s.execute(
                "INSERT INTO agg_daily (factory_id, date, store_id, gross_amount, discount_amount, net_amount, "
                "actual_receive, bill_count, customer_count, item_count, version, computed_at) "
                "VALUES ($1,$2,$3,$4,$5,$6,$7,$8,1,$8,1,$9)",
                factory_id, date, store, gross, disc, net, recv, max(bills, 0), now)
        print(f"[smooth] {factory_id}: {days} days, total ¥{total_g:,.0f}, {total_bills} bills, "
              f"trend ~+{(weights[-1]/weights[0]-1)*100:.0f}%")
    finally:
        await s.close()


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--factory", required=True)
    a = ap.parse_args()
    asyncio.run(run(a.factory))


if __name__ == "__main__":
    main()
