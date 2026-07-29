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
from .world.generator import (
    _generate_daily_ops_inner, backfill, generate_daily_ops, generate_orders,
)
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
                else:
                    # 非营业时段(曲线配额为 0)是正常状态, 但生成器**只在
                    # created>0 时打日志**, 于是整个夜里一行都没有 —— 从日志
                    # 上分不清"没到点"和"循环死了"。healthz 的 generator:running
                    # 只能证明协程没退出, 证明不了它还在转。
                    # 每 30 分钟打一条心跳, 频率低到不刷屏, 又足以在排查时
                    # 看出循环是活的。
                    if minute % 30 == 0:
                        logger.info("[gen] 第 %s 分钟配额为 0(非营业时段), 循环存活",
                                    minute)
                if created:
                    # 后厨跟着当天销量走: 有新单就重算这些店今天的领料/损耗/盘点。
                    # 必须排在订单之后 —— 它是按当天实际消耗推的, 顺序反了会
                    # 按尚未写入的销量算出偏小的数。
                    # 幂等 UPSERT 且 seq 会推进, 所以每分钟重算一次是安全的,
                    # 对端也能看到当天数字随营业增长(这正是"实时"的含义)。
                    for store in stores:
                        try:
                            generate_daily_ops(conn, store_id=store["id"],
                                               biz_date=biz_date, rng=rng)
                        except Exception:
                            logger.exception("[gen] 门店 %s 后厨派生失败, 跳过",
                                             store["id"])
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


def _cmd_backfill_ops(args: argparse.Namespace) -> None:
    """只按**已有订单**补后厨数据, 一条订单都不新建。

    为什么需要单独一个命令: `backfill` 会连订单一起造, 而订单的 INSERT 没有
    ON CONFLICT —— 在已经有数据的库上重跑会把订单**翻倍**, 把营收数字搞脏。
    线上那个库已经有 6 万单但零后厨数据(后厨是后来才加的), 只能用这条路补。

    幂等: 后厨三张表都是 (biz_date, store_id, ingredient_id[, type]) UPSERT。
    """
    settings = get_settings()
    conn = connect(settings.db_path)
    seed_world(conn, settings.store_count)   # 补上食材与配方(幂等)
    pairs = conn.execute(
        'SELECT DISTINCT store_id, biz_date FROM "order" ORDER BY biz_date, store_id'
    ).fetchall()
    if not pairs:
        # 禁降级: 没有订单就推不出后厨, 说清楚而不是报"成功 0 条"。
        raise SystemExit("库里没有订单, 无法推导后厨数据 —— 先跑 backfill")
    rng = random.Random()
    totals = {"requisition": 0, "wastage": 0, "stocktaking": 0}
    conn.execute("BEGIN")
    try:
        for row in pairs:
            stats = _generate_daily_ops_inner(
                conn, store_id=row["store_id"], biz_date=row["biz_date"], rng=rng)
            for k, v in stats.items():
                totals[k] += v
    except Exception:
        conn.execute("ROLLBACK")
        raise
    conn.execute("COMMIT")
    logger.info("[backfill-ops] 覆盖 %d 个门店日, 产出 %s", len(pairs), totals)


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
    # 只补后厨, 不碰订单 —— 已有数据的库上唯一安全的补数方式。
    p_ops = sub.add_parser("backfill-ops")
    p_ops.set_defaults(func=_cmd_backfill_ops)
    args = parser.parse_args()
    args.func(args)


if __name__ == "__main__":
    main()
