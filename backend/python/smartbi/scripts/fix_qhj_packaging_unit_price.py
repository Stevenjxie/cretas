"""修 QHJ「打包盒(一次性)」单价数量级错误 —— ¥110.00/个 → ¥1.10/个。

## 证据

    米饭 的配方两行:
      打包盒(一次性)  单价 ¥110.0000/个  × 1.5 个 = line_cost ¥165.00   ← 异常
      大米            单价 ¥  6.0000/kg × 0.2 kg = line_cost ¥  1.20   ← 正常

于是「米饭」的食材成本 ¥166.20 而它的实际均价只有 ¥15.20 —— **成本是售价的 11 倍**。
一次性打包盒不可能 110 元。`110` 正是 `1.10 元` 的**分值**, 即某处漏了 ÷100
(分→元)。同菜的大米 ¥6.00/kg 是对的, 说明不是整表的换算问题, 是这一行。

## 同根因扫描: 只有这一个

按单价降序扫了 QHJ 全部食材, 其余高价项全部合理(鲍鱼 200/kg、牛肉吊龙 120/kg、
虾仁 120/kg —— 都是正常的元/kg)。**只有打包盒是「个」为单位却带着 kg 级的价**,
所以这是单点数据错, 不是一类换算缺陷。

## 为什么要同时改 line_cost

`fact_restaurant_recipe_line.line_cost` 是**存储列**, ETL 直接读它汇总成
`agg_restaurant_product_cost.food_cost` —— 只改 `dim_ingredient.unit_price`
不会让成本表变化。两个都要改, 否则「单价看着对了而成本还是错的」。

## 用法

    PYTHONPATH=.:smartbi venv-current/bin/python -m smartbi.scripts.fix_qhj_packaging_unit_price \\
        --apply --confirm RES_3101_009

不带 `--apply` 是干跑。幂等: 已是正确值时不重复改, 并如实报告 0 行。

## 回滚

    UPDATE dim_ingredient SET unit_price = 110 WHERE ingredient_id = <id>;
    UPDATE fact_restaurant_recipe_line SET line_cost = 165
     WHERE factory_id='RES_3101_009' AND ingredient_id = <id>;
    -- 然后重跑 ETL
"""
from __future__ import annotations

import argparse
import asyncio
import logging
import sys
from decimal import Decimal
from pathlib import Path

_PYTHON_ROOT = Path(__file__).resolve().parent.parent.parent
for _p in (str(_PYTHON_ROOT), str(_PYTHON_ROOT / "smartbi")):
    if _p not in sys.path:
        sys.path.insert(0, _p)

logging.basicConfig(level=logging.INFO, format="%(message)s")
logger = logging.getLogger("fix_qhj_packaging_unit_price")

FACTORY_ID = "RES_3101_009"
INGREDIENT_NAME = "打包盒(一次性)"
WRONG_PRICE = Decimal("110")
CORRECT_PRICE = Decimal("1.10")


async def _run(apply: bool) -> int:
    from smartbi.config import get_pg_pool
    from smartbi.gold.restaurant.restaurant_ops_etl import materialize_gold_daily_ops

    pool = await get_pg_pool()
    async with pool.acquire() as conn:
        tx = conn.transaction()
        await tx.start()
        try:
            await conn.execute(
                "SELECT set_config('app.factory_id', $1, true)", FACTORY_ID)
            row = await conn.fetchrow(
                "SELECT ingredient_id, unit, unit_price FROM dim_ingredient"
                " WHERE factory_id = $1 AND name = $2",
                FACTORY_ID, INGREDIENT_NAME,
            )
            if row is None:
                # 禁降级: 找不到目标就明确失败, 别静默"成功"。
                raise RuntimeError(f"{FACTORY_ID} 没有食材 {INGREDIENT_NAME!r}")
            current = Decimal(str(row["unit_price"]))
            logger.info("当前: %s  单位=%s  单价=¥%s (期望改成 ¥%s)",
                        INGREDIENT_NAME, row["unit"], current, CORRECT_PRICE)
            if current == CORRECT_PRICE:
                logger.info("已经是正确值, 无需改动。")
                await tx.rollback()
                return 0
            if current != WRONG_PRICE:
                # 值既不是错的也不是对的 —— 可能有人已经改过或又错成别的数。
                # 这时候盲改会覆盖别人的判断, 停手让人来看。
                raise RuntimeError(
                    f"单价是 ¥{current}, 既不是已知错值 ¥{WRONG_PRICE} 也不是目标值 "
                    f"¥{CORRECT_PRICE} —— 拒绝盲改, 请人工确认")

            lines = await conn.fetch(
                "SELECT r.source_pk, r.standard_qty, r.line_cost,"
                "       m.normalized_name AS dish"
                "  FROM fact_restaurant_recipe_line r"
                "  LEFT JOIN dim_restaurant_cost_product m"
                "    ON m.factory_id = r.factory_id"
                "   AND m.product_source_pk = r.product_source_pk"
                " WHERE r.factory_id = $1 AND r.ingredient_id = $2",
                FACTORY_ID, row["ingredient_id"],
            )
            for ln in lines:
                new_cost = (Decimal(str(ln["standard_qty"])) * CORRECT_PRICE)
                logger.info("  配方行 %-16s (%s): line_cost ¥%s → ¥%s",
                            ln["source_pk"], ln["dish"], ln["line_cost"], new_cost)

            await conn.execute(
                "UPDATE dim_ingredient SET unit_price = $3, updated_at = NOW()"
                " WHERE factory_id = $1 AND ingredient_id = $2",
                FACTORY_ID, row["ingredient_id"], CORRECT_PRICE,
            )
            # line_cost 是存储列, ETL 直接读它 —— 不同步改的话单价看着对了而成本还是错的。
            upd = await conn.execute(
                "UPDATE fact_restaurant_recipe_line"
                "   SET line_cost = standard_qty * $3, updated_at = NOW()"
                " WHERE factory_id = $1 AND ingredient_id = $2",
                FACTORY_ID, row["ingredient_id"], CORRECT_PRICE,
            )
            n = int(upd.split()[-1]) if upd else 0
            if not apply:
                await tx.rollback()
                logger.info("干跑完成(已回滚): 将改 1 个食材单价 + %d 条配方行", n)
                logger.info("加 --apply --confirm %s 才会真正写入。", FACTORY_ID)
                return 0
            await tx.commit()
            logger.info("写入完成: 1 个食材单价 + %d 条配方行", n)
        except Exception:
            await tx.rollback()
            raise

    etl = await materialize_gold_daily_ops(pool, FACTORY_ID)
    logger.info("ETL 重算 Gold: product_cost=%s", etl.get("product_cost"))
    return 0


def main() -> int:
    ap = argparse.ArgumentParser(
        description="修 QHJ 打包盒单价数量级错误(分被当成元)")
    ap.add_argument("--apply", action="store_true", help="真正写入; 不加则干跑并回滚")
    ap.add_argument("--confirm", default="", help=f"写入时必须显式传 {FACTORY_ID}")
    args = ap.parse_args()
    if args.apply and args.confirm != FACTORY_ID:
        logger.error("拒绝执行: --apply 必须配 --confirm %s", FACTORY_ID)
        return 2
    return asyncio.run(_run(args.apply))


if __name__ == "__main__":
    raise SystemExit(main())
