"""RES_3101_009(QHJ)菜品食材成本补估 —— 以**显式标注的估算行**补足主料以外的部分。

## 背景与口径

该租户的配方只列**主料**(实测每菜 1-5 种, 中位 3), 没有配菜、调料、油、损耗。
后果是营收加权食材成本率只有 **14.9%**(毛利率 85.1%) —— 餐饮里不可能, 客户看到
会知道是假的。逐菜分布 2%-45%、中位约 20-25%; 之所以加权更低, 是因为高价套餐
占营收大头而它们的成本率最低。

⚠️ **这是估算, 不是 QHJ 的真实成本。** 该租户没有权威实际成本可用:
`restaurant_target_margin_config` 里只有**目标**毛利率(默认 55%), 拿目标当实际
等于「实际永远等于目标」, 毛利分析就失去意义。

## 为什么用「加一条估算行」而不是「放大既有 line_cost」

放大既有行会**篡改客户自己录入的主料成本**, 而且从界面上完全看不出来 —— 用户
看到「牛肉 ¥12.24」会以为那是他们填的数。

改成给每道菜加**一条独立的配方行**, 食材名就叫「其他辅料(估算)」:
  · 用户在配方明细里**直接看见**它, 名字自带「估算」二字
  · 客户自己录的主料行**一个字节都不动**
  · 回滚只需删掉 `source_pk` 以 `qhj_est_aux_` 开头的行, 不碰别的
  · `dim_ingredient.cost_source = 'estimated'` 再标一层

## 估算方法

目标食材成本率 **30%**(休闲正餐常见 28-35% 的中位)。逐菜:

    补足额 = max(0, 目标成本率 × 该菜实际均价 − 当前主料成本)

只补不减 —— 已经达到或超过目标的菜不动(它们的主料录得比较全)。
明显异常的菜(主料成本 > 售价)**跳过并列出**, 那是既有脏数据, 不该被估算掩盖。

## 用法

    PYTHONPATH=.:smartbi venv-current/bin/python -m smartbi.scripts.seed_qhj_estimated_aux_cost \\
        --apply --confirm RES_3101_009

不带 `--apply` 是干跑。幂等: 估算行按 `(factory_id, source_pk)` UPSERT, 重复跑
不产生重复行; 每次都按当时的均价重算。

## 回滚

    DELETE FROM fact_restaurant_recipe_line
     WHERE factory_id='RES_3101_009' AND source_pk LIKE 'qhj_est_aux_%';
    -- 然后重跑 ETL(materialize_gold_daily_ops)
"""
from __future__ import annotations

import argparse
import asyncio
import logging
import sys
from decimal import Decimal, ROUND_HALF_UP
from pathlib import Path
from typing import Dict

_PYTHON_ROOT = Path(__file__).resolve().parent.parent.parent
for _p in (str(_PYTHON_ROOT), str(_PYTHON_ROOT / "smartbi")):
    if _p not in sys.path:
        sys.path.insert(0, _p)

logging.basicConfig(level=logging.INFO, format="%(message)s")
logger = logging.getLogger("seed_qhj_estimated_aux_cost")

FACTORY_ID = "RES_3101_009"
AUX_INGREDIENT_NAME = "其他辅料(估算)"
TARGET_FOOD_COST_RATE = Decimal("0.30")
_Q4 = Decimal("0.0001")

# 逐菜: 主料成本 / 实际均价 / 该菜的 product_source_pk
_DISH_SQL = """
WITH sold AS (
    SELECT p.normalized_name,
           SUM(i.amount)::numeric / NULLIF(SUM(i.qty), 0) AS avg_price
      FROM fact_pos_item i
      JOIN dim_product p ON p.product_id = i.product_id
     WHERE i.factory_id = $1 AND p.factory_id = $1
     GROUP BY 1
    HAVING SUM(i.qty) > 0
)
SELECT c.product_source_pk,
       m.normalized_name           AS dish,
       c.food_cost::numeric        AS main_cost,
       s.avg_price::numeric        AS avg_price
  FROM agg_restaurant_product_cost c
  JOIN dim_restaurant_cost_product m
    ON m.factory_id = c.factory_id AND m.product_source_pk = c.product_source_pk
  JOIN sold s ON s.normalized_name = m.normalized_name
 WHERE c.factory_id = $1 AND c.food_cost > 0
"""


async def _ensure_aux_ingredient(conn) -> int:
    """取(或建)那条「其他辅料(估算)」食材, 返回 ingredient_id。

    单价固定 1 元/份 —— 估算行的成本直接写在 line_cost 上, 用量恒为 1,
    这样界面上读起来是「其他辅料(估算) × 1 份 = ¥X」而不是一个假的公斤数。
    """
    row = await conn.fetchrow(
        "SELECT ingredient_id FROM dim_ingredient"
        " WHERE factory_id = $1 AND name = $2",
        FACTORY_ID, AUX_INGREDIENT_NAME,
    )
    if row:
        await conn.execute(
            "UPDATE dim_ingredient SET cost_source = 'estimated', updated_at = NOW()"
            " WHERE ingredient_id = $1", row["ingredient_id"],
        )
        return int(row["ingredient_id"])
    new_id = await conn.fetchval(
        "INSERT INTO dim_ingredient"
        " (factory_id, source_pk, name, normalized_name, category, unit,"
        "  unit_price, is_active, cost_source, created_at, updated_at)"
        " VALUES ($1, 'qhj_est_aux_ingredient', $2, $2, '估算', '份',"
        "         1, TRUE, 'estimated', NOW(), NOW())"
        " RETURNING ingredient_id",
        FACTORY_ID, AUX_INGREDIENT_NAME,
    )
    return int(new_id)


async def _plan(conn):
    await conn.execute("SELECT set_config('app.factory_id', $1, true)", FACTORY_ID)
    rows = await conn.fetch(_DISH_SQL, FACTORY_ID)
    topped, skipped_anomaly, already_ok = [], [], []
    for r in rows:
        main = Decimal(str(r["main_cost"]))
        price = Decimal(str(r["avg_price"]))
        if price <= 0 or main > price:
            # 主料成本高于售价 = 既有脏数据(实测有 1 道 15 倍)。
            # estimated 行不该被用来掩盖它 —— 列出来让人去查。
            skipped_anomaly.append((r["dish"], main, price))
            continue
        target = (price * TARGET_FOOD_COST_RATE).quantize(_Q4, rounding=ROUND_HALF_UP)
        gap = target - main
        if gap <= 0:
            already_ok.append(r["dish"])
            continue
        topped.append({
            "product_source_pk": r["product_source_pk"],
            "dish": r["dish"],
            "main": main, "price": price, "gap": gap,
        })
    return topped, skipped_anomaly, already_ok


async def _apply(conn, topped) -> Dict[str, int]:
    aux_id = await _ensure_aux_ingredient(conn)
    written = 0
    for t in topped:
        await conn.execute(
            """
            INSERT INTO fact_restaurant_recipe_line
                   (factory_id, source_pk, product_id, product_source_pk,
                    ingredient_id, standard_qty, unit, is_main_ingredient,
                    line_cost, is_active, created_at, updated_at)
            VALUES ($1, $2, 0, $3, $4, 1, '份', FALSE, $5, TRUE, NOW(), NOW())
            ON CONFLICT (factory_id, source_pk) DO UPDATE SET
                line_cost  = EXCLUDED.line_cost,
                is_active  = TRUE,
                updated_at = NOW()
            """,
            FACTORY_ID, f"qhj_est_aux_{t['product_source_pk']}",
            t["product_source_pk"], aux_id, t["gap"],
        )
        written += 1
    # 平台不再报的菜(或已达标的菜)残留的估算行要剪掉, 否则成本只增不减。
    keep = [f"qhj_est_aux_{t['product_source_pk']}" for t in topped]
    pruned = await conn.execute(
        "DELETE FROM fact_restaurant_recipe_line"
        " WHERE factory_id = $1 AND source_pk LIKE 'qhj_est_aux_%'"
        "   AND source_pk <> ALL($2::varchar[])",
        FACTORY_ID, keep,
    )
    return {"estimated_lines": written,
            "pruned": int(pruned.split()[-1]) if pruned else 0}


async def _run(apply: bool) -> int:
    from smartbi.config import get_pg_pool
    from smartbi.gold.restaurant.restaurant_ops_etl import materialize_gold_daily_ops

    pool = await get_pg_pool()
    async with pool.acquire() as conn:
        tx = conn.transaction()
        await tx.start()
        try:
            topped, anomalies, already_ok = await _plan(conn)
            tot_gap = sum(t["gap"] for t in topped)
            logger.info("目标食材成本率 %.0f%%", float(TARGET_FOOD_COST_RATE * 100))
            logger.info("  需要补估算行 : %d 道菜, 合计补足 ¥%s", len(topped), tot_gap)
            logger.info("  主料已达标   : %d 道菜(不动)", len(already_ok))
            logger.info("  异常跳过     : %d 道菜(主料成本 > 售价, 属既有脏数据)",
                        len(anomalies))
            for d, m, p in anomalies[:5]:
                logger.info("      ⚠️ %-28s 主料 ¥%-10s 均价 ¥%s", d[:28], m, p)
            for t in sorted(topped, key=lambda x: -x["gap"])[:8]:
                logger.info("      %-28s 主料 ¥%-9s → 补 ¥%-9s (均价 ¥%s)",
                            t["dish"][:28], t["main"], t["gap"], t["price"])

            stats = await _apply(conn, topped)
            if not apply:
                await tx.rollback()
                logger.info("干跑完成(已回滚): %s", stats)
                logger.info("加 --apply --confirm %s 才会真正写入。", FACTORY_ID)
                return 0
            await tx.commit()
        except Exception:
            await tx.rollback()
            raise
    logger.info("写入完成: %s", stats)
    etl = await materialize_gold_daily_ops(pool, FACTORY_ID)
    logger.info("ETL 重算 Gold: product_cost=%s", etl.get("product_cost"))
    return 0


def main() -> int:
    ap = argparse.ArgumentParser(
        description="QHJ 菜品食材成本补估(显式标注的估算行, 不改客户主料数据)")
    ap.add_argument("--apply", action="store_true", help="真正写入; 不加则干跑并回滚")
    ap.add_argument("--confirm", default="", help=f"写入时必须显式传 {FACTORY_ID}")
    args = ap.parse_args()
    if args.apply and args.confirm != FACTORY_ID:
        logger.error("拒绝执行: --apply 必须配 --confirm %s", FACTORY_ID)
        return 2
    return asyncio.run(_run(args.apply))


if __name__ == "__main__":
    raise SystemExit(main())
