"""手动触发一次 MOCK_REST 菜单主数据同步(菜品 / 食材单价 / 配方) + 重算成本表。

## 它现在是什么

**一个手动扳机, 不是第二条数据通路。** 拉取用 `KeruyunMenuAdapter`、写库用
`menu_writer.write_menu` —— 与 `main.py` 里那个每 6 小时跑一次的常驻同步**同一条
代码路径**。想立刻看到效果(改完平台配方、或新租户刚接上)时跑它, 不必等下一轮。

⚠️ **早期版本在这里内嵌过一份 dish/ingredient/recipe 快照**。那是 139 还没有
`/menu/*` 端点时的权宜之计, 已删除 —— 内嵌快照意味着同一份主数据有两个来源,
平台改了配方而这里不改就会静默分叉, 而两边的数字看起来都合法。
(它还真的咬过一次: 快照用的幂等键与 writer 不同, connector 首跑差点写出第二套
 配方行让食材成本翻倍。详见 `docs/dispatch/handoff-2026-08-01e-*.md`。)

## 为什么需要它

MOCK_REST 的工厂名就是「模拟平台餐饮租户 (假 POS 数据接入验证)」—— 当初按 POS
接入验证建的, 只有交易流水, 从没给过菜单主数据。后果是
`agg_restaurant_product_cost` 恒 0 行, 财务四个问题里三个答成「毛利前 0 名」。

## 用法

    cd /www/wwwroot/cretas/code/backend/python
    PYTHONPATH=.:smartbi venv-current/bin/python -m smartbi.scripts.seed_mock_rest_menu \\
        --apply --confirm MOCK_REST

不带 `--apply` 是干跑: 照常拉取并打印将写入的内容, 但事务回滚。
幂等 —— 三张表全部 UPSERT, 且会剪除平台已不再报的配方行(全量替换语义)。
"""
from __future__ import annotations

import argparse
import asyncio
import logging
import os
import sys
from decimal import Decimal
from pathlib import Path
from typing import Dict

_PYTHON_ROOT = Path(__file__).resolve().parent.parent.parent
for _p in (str(_PYTHON_ROOT), str(_PYTHON_ROOT / "smartbi")):
    if _p not in sys.path:
        sys.path.insert(0, _p)

logging.basicConfig(level=logging.INFO, format="%(message)s")
logger = logging.getLogger("seed_mock_rest_menu")

FACTORY_ID = "MOCK_REST"


def _build_adapter(client):
    from smartbi.ingestion.platforms.menu_keruyun import KeruyunMenuAdapter

    base_url = os.getenv("PLATFORM_MOCK_BASE_URL", "")
    if not base_url:
        # 禁降级: 没有上游地址就别装作同步过了。
        raise RuntimeError("PLATFORM_MOCK_BASE_URL 未配置, 无法拉取菜单主数据")
    return KeruyunMenuAdapter(
        base_url,
        os.getenv("PLATFORM_KERUYUN_APP_KEY", ""),
        os.getenv("PLATFORM_KERUYUN_APP_SECRET", ""),
        client,
    )


def _preview(dishes, ingredients, recipe) -> None:
    """把将要写入的东西按菜打印出来, 顺带把食材成本率算给人看。"""
    from smartbi.ingestion.platforms.menu_writer import compute_line_costs

    lines = compute_line_costs(
        {i.ingredient_code: i.unit_price_cents for i in ingredients},
        [(r.dish_code, r.ingredient_code, r.qty_milli) for r in recipe],
        known_dishes=[d.dish_code for d in dishes],
    )
    per: Dict[str, Decimal] = {}
    n_lines: Dict[str, int] = {}
    for ln in lines:
        per[ln.dish_code] = per.get(ln.dish_code, Decimal(0)) + ln.line_cost
        n_lines[ln.dish_code] = n_lines.get(ln.dish_code, 0) + 1

    logger.info("拉到 %d 道菜 / %d 种食材 / %d 条配方:",
                len(dishes), len(ingredients), len(recipe))
    tot_cost = tot_price = Decimal(0)
    for d in sorted(dishes, key=lambda x: -x.price_cents):
        cost = per.get(d.dish_code, Decimal(0))
        price = Decimal(d.price_cents) / Decimal(100)
        tot_cost += cost
        tot_price += price
        logger.info("  %-14s %-10s 食材成本 ¥%-9s 售价 ¥%-8s 食材成本率 %5.1f%%  (%d 行)",
                    d.dish_code, d.name, cost, price,
                    float(cost / price * 100) if price else 0.0,
                    n_lines.get(d.dish_code, 0))
    if tot_price:
        logger.info("  ── 菜单口径合计: 食材成本率 %.1f%% / 毛利率 %.1f%%",
                    float(tot_cost / tot_price * 100),
                    float((tot_price - tot_cost) / tot_price * 100))


async def _run(apply: bool) -> int:
    import httpx

    from smartbi.config import get_pg_pool
    from smartbi.gold.restaurant.restaurant_ops_etl import materialize_gold_daily_ops
    from smartbi.ingestion.platforms.menu_writer import write_menu

    pool = await get_pg_pool()
    async with httpx.AsyncClient() as client:
        adapter = _build_adapter(client)
        dishes = await adapter.fetch_all("dish")
        ingredients = await adapter.fetch_all("ingredient")
        recipe = await adapter.fetch_all("recipe")

    _preview(dishes, ingredients, recipe)

    async with pool.acquire() as conn:
        tx = conn.transaction()
        await tx.start()
        try:
            stats = await write_menu(conn, FACTORY_ID, dishes, ingredients, recipe)
            if not apply:
                await tx.rollback()
                logger.info("干跑完成(已回滚): %s", stats)
                logger.info("加 --apply --confirm MOCK_REST 才会真正写入。")
                return 0
            await tx.commit()
        except Exception:
            await tx.rollback()
            raise
    logger.info("写入完成: %s", stats)

    etl = await materialize_gold_daily_ops(pool, FACTORY_ID)
    logger.info("ETL 重算 Gold: %s", etl)

    async with pool.acquire() as conn:
        # ⚠️ set_config(..., is_local=true) 只在**事务内**有效。分成两条事务外语句
        # 的话 GUC 当场失效, RLS 把刚写的行全藏起来报「0 行」——与真的没写进去
        # 长得一模一样。
        async with conn.transaction():
            await conn.execute("SELECT set_config('app.factory_id', $1, true)", FACTORY_ID)
            n = await conn.fetchval(
                "SELECT count(*) FROM agg_restaurant_product_cost WHERE factory_id = $1",
                FACTORY_ID,
            )
    logger.info("agg_restaurant_product_cost 现有 %s 行", n)
    return 0


def main() -> int:
    ap = argparse.ArgumentParser(
        description="手动触发一次 MOCK_REST 菜单主数据同步(与常驻 connector 同一路径)")
    ap.add_argument("--apply", action="store_true", help="真正写入; 不加则干跑并回滚")
    ap.add_argument("--confirm", default="", help="写入时必须显式传 MOCK_REST")
    args = ap.parse_args()
    if args.apply and args.confirm != FACTORY_ID:
        logger.error("拒绝执行: --apply 必须配 --confirm %s", FACTORY_ID)
        return 2
    return asyncio.run(_run(args.apply))


if __name__ == "__main__":
    raise SystemExit(main())
