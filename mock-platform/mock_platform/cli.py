"""模拟端入口。

serve    — 起 HTTP 服务 + 常驻生成循环（按分钟推进，边生成边回调通知）
backfill — 一次性造历史订单
"""
from __future__ import annotations

import argparse
import asyncio
import datetime
import logging
import random

import httpx
import uvicorn

from .api.app import create_app
from .callback import notify
from .config import get_settings
from .db import connect
from .world.curve import daily_minute_quota
from .world.generator import backfill, generate_orders
from .world.seed import seed_world

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
logger = logging.getLogger("mock-platform")


async def _generate_forever() -> None:
    """每分钟推进一次：给每家店按曲线配额补上这一分钟该出的单。"""
    settings = get_settings()
    rng = random.Random()
    conn = connect(settings.db_path)
    seed_world(conn, settings.store_count)
    stores = conn.execute("SELECT id, format FROM store ORDER BY id").fetchall()
    quotas = {
        s["id"]: daily_minute_quota(s["format"], settings.orders_per_store_per_day)
        for s in stores
    }
    async with httpx.AsyncClient() as client:
        while True:
            try:
                now = datetime.datetime.now()
                minute = now.hour * 60 + now.minute
                biz_date = now.date().isoformat()
                created = 0
                for store in stores:
                    count = quotas[store["id"]][minute]
                    if not count:
                        continue
                    try:
                        created += generate_orders(
                            conn, store_id=store["id"], biz_date=biz_date,
                            minute_of_day=minute, count=count, rng=rng,
                        )
                    except Exception:
                        # 单店失败隔离：不让一家店的问题停掉整个常驻循环。
                        # 生成器是 daemon，停了就再也不产数据且外部难以察觉。
                        logger.exception("[gen] 门店 %s 第 %s 分钟生成失败, 跳过",
                                         store["id"], minute)
                if created:
                    row = conn.execute('SELECT MAX(seq) s FROM "order"').fetchone()
                    logger.info("[gen] 第 %s 分钟生成 %d 单, maxSeq=%s", minute, created, row["s"])
                    await notify(client, settings.callback_url,
                                 settings.callback_secret, max_seq=int(row["s"]))
            except Exception:
                # 兜住本轮内任何其他意外（含 notify 之外的逻辑），
                # 保证下一分钟还能继续跑，而不是让整个协程被异常打死。
                logger.exception("[gen] 本轮异常, 60s 后继续")
            await asyncio.sleep(60)


def _cmd_serve(args: argparse.Namespace) -> None:
    app = create_app()

    @app.on_event("startup")
    async def _arm_generator():
        # ⚠️ 必须持强引用: event loop 只对 task 持弱引用, 丢弃返回值会让
        #    生成器随时被 GC 掉, 而 HTTP 面毫无异样 —— 服务"看着健康但不产数据"。
        app.state.generator_task = asyncio.create_task(_generate_forever())

    uvicorn.run(app, host=args.host, port=args.port, log_level="info")


def _cmd_backfill(args: argparse.Namespace) -> None:
    settings = get_settings()
    conn = connect(settings.db_path)
    seed_world(conn, settings.store_count)
    total = backfill(conn, days=args.days,
                     orders_per_store=settings.orders_per_store_per_day,
                     today=datetime.date.today(), rng=random.Random())
    logger.info("[backfill] 造出 %d 单，覆盖过去 %d 天", total, args.days)


def _positive_int(raw: str) -> int:
    """`--days` 校验：0/负数会让 `range(days, 0, -1)` 静默为空，看起来成功但什么都没造。
    与本项目"禁止降级处理，明确显示错误"的原则相悖，必须在入口挡住。"""
    value = int(raw)
    if value <= 0:
        raise argparse.ArgumentTypeError(f"--days 必须为正整数, 收到 {value}")
    return value


def main() -> None:
    parser = argparse.ArgumentParser(prog="mock_platform")
    sub = parser.add_subparsers(dest="cmd", required=True)
    p_serve = sub.add_parser("serve")
    p_serve.add_argument(
        "--host", default="0.0.0.0",
        help="监听地址。默认 0.0.0.0 方便本地开发；线上部署（139，经 nginx 反代 /mock/ 前缀，"
             "不自开公网端口）应显式传 127.0.0.1。",
    )
    p_serve.add_argument("--port", type=int, default=9200)
    p_serve.set_defaults(func=_cmd_serve)
    p_back = sub.add_parser("backfill")
    p_back.add_argument("--days", type=_positive_int, required=True)
    p_back.set_defaults(func=_cmd_backfill)
    args = parser.parse_args()
    args.func(args)


if __name__ == "__main__":
    main()
