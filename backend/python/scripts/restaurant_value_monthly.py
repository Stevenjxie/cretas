"""#56 价值可视化回馈回路 — 月度价值快照 cron 脚本 (D1-a 兜底)。

每月1日跑: 遍历所有 RESTAURANT 业态工厂, 计算上月价值快照并 upsert + 通知。
是 D1 双触发的兜底路径 (上传触发 D1-b 在 hooks.py); 两路径走同一幂等
compute_and_upsert_snapshot + maybe_notify_monthly, 重复触发不产生重复行/重复推送。

工厂清单: 复用 RESTAURANT_FACTORY_BACKFILL_LIST (F001 + RES_3101_009 + R_GML_DEMO
+ R_XMX_CHAIN; F006 排除 — 无 POS 源)。可经 --factory-ids 覆盖。

用法 (服务器 cron, 每月1日 03:00):
    cd /www/wwwroot/cretas/code/backend/python
    source venv38/bin/activate
    python -m scripts.restaurant_value_monthly                 # 上月, 默认餐饮工厂
    python -m scripts.restaurant_value_monthly --period 2026-02
    python -m scripts.restaurant_value_monthly --factory-ids RES_3101_009 --no-notify

建议 crontab:
    0 3 1 * * cd /www/wwwroot/cretas/code/backend/python && \\
      source venv38/bin/activate && \\
      python -m scripts.restaurant_value_monthly >> /www/wwwroot/cretas/logs/value-monthly.log 2>&1

幂等: ON CONFLICT DO UPDATE; 防重日志表防重复通知。安全可重跑。
"""
from __future__ import annotations

import argparse
import asyncio
import logging
import sys

logger = logging.getLogger(__name__)


async def _run(period_month, factory_ids, notify: bool) -> int:
    from smartbi.config import get_pg_pool
    from smartbi.services.restaurant.value_refresh_pipeline import (
        refresh_snapshot_for_factory,
        _default_last_month,
    )
    from smartbi.services.restaurant.value_snapshot_service import get_value_summary
    from smartbi.services.restaurant.value_notifier import maybe_notify_monthly

    if factory_ids is None:
        from smartbi.gold.restaurant.restaurant_finance_etl import RESTAURANT_FACTORY_BACKFILL_LIST
        factory_ids = list(RESTAURANT_FACTORY_BACKFILL_LIST)

    period = period_month or _default_last_month()
    pool = await get_pg_pool()
    if pool is None:
        logger.error("[value-monthly] smartbi_db pool unavailable")
        return 1

    ok_count = 0
    skip_count = 0
    fail_count = 0
    for factory_id in factory_ids:
        try:
            result = await refresh_snapshot_for_factory(
                factory_id, period_month=period, store_id=None, pool=pool,
            )
            if result.get("success"):
                ok_count += 1
                logger.info(
                    "[value-monthly] factory=%s period=%s month=%s annual=%s",
                    factory_id, period, result.get("totalMonth"), result.get("totalAnnual"),
                )
                if notify:
                    summary = await get_value_summary(
                        pool, factory_id, period_month=period, store_id=None,
                        set_tenant_guc=True,
                    )
                    if summary is not None:
                        notif = await maybe_notify_monthly(
                            pool, factory_id, period_month=period, summary=summary,
                        )
                        logger.info("[value-monthly] notify factory=%s → %s", factory_id, notif)
            else:
                skip_count += 1
                logger.info(
                    "[value-monthly] factory=%s period=%s skipped: %s",
                    factory_id, period, result.get("message"),
                )
        except Exception as e:  # noqa: BLE001 — one factory failing must not block others
            fail_count += 1
            logger.error("[value-monthly] factory=%s failed: %s", factory_id, e, exc_info=True)

    logger.info(
        "[value-monthly] done period=%s — ok=%d skipped=%d failed=%d (of %d)",
        period, ok_count, skip_count, fail_count, len(factory_ids),
    )
    # Exit non-zero only if EVERY factory failed (cron alerting); partial = 0.
    return 1 if (fail_count and ok_count == 0 and skip_count == 0) else 0


def main() -> None:
    logging.basicConfig(
        level=logging.INFO,
        format="%(asctime)s %(levelname)s %(name)s %(message)s",
    )
    parser = argparse.ArgumentParser(description="#56 月度价值快照 cron")
    parser.add_argument("--period", default=None, help="YYYY-MM (默认上一个完整月)")
    parser.add_argument(
        "--factory-ids", default=None,
        help="逗号分隔 factory_id (默认 RESTAURANT_FACTORY_BACKFILL_LIST)",
    )
    parser.add_argument("--no-notify", action="store_true", help="只算快照, 不推送通知")
    args = parser.parse_args()

    factory_ids = None
    if args.factory_ids:
        factory_ids = [f.strip() for f in args.factory_ids.split(",") if f.strip()]

    rc = asyncio.run(_run(args.period, factory_ids, notify=not args.no_notify))
    sys.exit(rc)


if __name__ == "__main__":
    main()
