"""Phase 3: light up 异常预警 + 车间实时生产报表 for a factory demo tenant.

- 异常预警 (AlertDashboard) reads ProductionAlert (production_alerts table) — NOT the
  rule-engine alert_events. Generate realistic manufacturing anomalies (yield/cost/quality/OEE).
- 车间实时生产报表 reads production_batches by created_at within [today] (status=COMPLETED),
  grouped by product. Shift the latest completed batches across a few products to TODAY.

Idempotent. cretas writes only (no RLS).
  PYTHONPATH=. python -m scripts.demo.gen_factory_phase3 --factory DEMO_FACTORY2 --today 2026-06-14
"""
from __future__ import annotations
import argparse, asyncio, datetime
import asyncpg
from scripts.demo import clone_config as cfg

# (alert_type, level, status, metric, current, baseline, threshold, deviation%, product, desc)
ALERTS = [
    ("YIELD_DROP", "CRITICAL", "ACTIVE", "出成率", 62.5, 78.0, 70.0, -19.9, "轻卤门腔（猪舌）120g",
     "猪舌滚揉工序出成率 62.5%, 低于基线 78% 与阈值 70%, 建议核查滚揉时间与盐水配比"),
    ("COST_SPIKE", "WARNING", "ACTIVE", "单位成本", 8.6, 7.2, 8.0, 19.4, "椒麻掌中宝 120g",
     "掌中宝单位成本 ¥8.6 较基线 ¥7.2 上涨 19%, 主因原料采购价波动"),
    ("QUALITY_FAIL", "CRITICAL", "ACTIVE", "合格率", 91.0, 99.0, 95.0, -8.1, "卤猪蹄(去大骨) 200g",
     "卤猪蹄批次合格率 91%, 低于 95% 阈值, 抽检发现去骨不净"),
    ("OEE_LOW", "WARNING", "ACTIVE", "设备综合效率", 68.0, 82.0, 75.0, -17.1, "气调包装线",
     "气调包装线 OEE 68%, 低于 75% 阈值, 换型停机偏多"),
    ("YIELD_DROP", "WARNING", "ACKNOWLEDGED", "出成率", 71.0, 76.0, 72.0, -6.6, "纸片牛腱肉 80g",
     "牛腱切片出成率 71%, 略低于阈值, 已通知车间"),
    ("COST_SPIKE", "INFO", "ACTIVE", "能耗成本", 1.3, 1.1, 1.25, 18.2, "卤制工序",
     "卤制工序能耗成本环比上升 18%"),
    ("QUALITY_FAIL", "WARNING", "RESOLVED", "合格率", 94.0, 99.0, 95.0, -5.1, "猪大肠",
     "猪大肠清洗合格率短暂下降, 已调整清洗流程恢复"),
    ("OEE_LOW", "INFO", "ACTIVE", "设备综合效率", 74.0, 80.0, 75.0, -7.5, "真空滚揉机",
     "真空滚揉机 OEE 74%, 接近阈值, 关注轴承磨损"),
]


async def run(factory_id: str, today: datetime.date):
    c = await asyncpg.connect(cfg.CRETAS_DSN)
    now = datetime.datetime.now()
    try:
        # ---- 异常预警: production_alerts (id is bigint serial -> omit) ----
        await c.execute("DELETE FROM production_alerts WHERE factory_id=$1", factory_id)
        for i, a in enumerate(ALERTS):
            atype, level, status, metric, cur, base, thr, dev, prod, desc = a
            await c.execute(
                "INSERT INTO production_alerts (factory_id, alert_type, level, status, metric_name, "
                "current_value, baseline_value, threshold_value, deviation_percent, product_name, description, "
                "created_at, updated_at) VALUES ($1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$11,$12,$13)",
                factory_id, atype, level, status, metric, cur, base, thr, dev, prod, desc,
                now - datetime.timedelta(hours=(i + 1) * 5), now)

        # ---- 车间实时报表: shift one completed batch per product (up to 6) to TODAY ----
        rows = await c.fetch(
            "SELECT id, product_type_id, row_number() OVER (PARTITION BY product_type_id "
            "ORDER BY actual_quantity DESC NULLS LAST) rn, dense_rank() OVER (ORDER BY product_type_id) pr "
            "FROM production_batches WHERE factory_id=$1 AND status='COMPLETED' AND actual_quantity IS NOT NULL",
            factory_id)
        shifted = 0
        for r in rows:
            if r["rn"] == 1 and r["pr"] <= 6:
                ts = datetime.datetime.combine(today, datetime.time(7, 0)) + datetime.timedelta(minutes=75 * r["pr"])
                await c.execute("UPDATE production_batches SET created_at=$2 WHERE id=$1", r["id"], ts)
                shifted += 1

        alerts = await c.fetchval("SELECT count(*) FROM production_alerts WHERE factory_id=$1", factory_id)
        today_b = await c.fetchval(
            "SELECT count(DISTINCT product_type_id) FROM production_batches WHERE factory_id=$1 "
            "AND status='COMPLETED' AND created_at::date=$2", factory_id, today)
        print(f"[phase3] {factory_id}: production_alerts={alerts} today_products={today_b} (shifted {shifted} batches)")
    finally:
        await c.close()


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--factory", required=True)
    ap.add_argument("--today", required=True)
    a = ap.parse_args()
    asyncio.run(run(a.factory, datetime.date.fromisoformat(a.today)))


if __name__ == "__main__":
    main()
