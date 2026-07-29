"""归一化的后厨单据 → Silver 三张 ops fact 表。

列名与约束全部按 2026-07-29 生产实测校正, 不是凭记忆写的:
  * 三张表的幂等键都是 `uq_*_factory_source` = (factory_id, source_pk),
    source_pk 存平台单号。
  * **三张表都没有 store_id** —— ops Silver 是工厂级而非门店级的。我们的
    模拟器按门店产出, 门店信息只保留在 source_pk 里, 落到 Silver 后
    门店归属就丢了。这是既有 schema 的形状, 不是这里的疏漏; 要按门店看
    后厨得先扩 schema。
  * 列名各表不同, 别串: requisition 是 requested_qty/est_cost,
    wastage 是 quantity/estimated_cost, stocktaking 是
    system_qty/actual_qty/difference_qty/difference_cost。
  * 🔴 `restaurant_ops_etl` 统计领料时带 `WHERE status = 'COMPLETED'` ——
    状态写错这一条就被静默过滤掉, 表里有行但 Gold 是 0。

用量: 归一化模型用「毫单位」整数, Silver 是 numeric(14,4), 这里除 1000。
金额: 模型用「分」, Silver 是 numeric(14,2), 这里除 100。

⚠️ 与 writer.py 同样的整批一事务取舍: 任一条失败整页回滚、游标不推进、
   下轮重拉。禁降级, 代价是一条永久性坏单据会让该页永远卡住。
"""
from __future__ import annotations

import logging
from decimal import Decimal
from typing import List

from smartbi.canonical.entity_resolution.agents.deterministic import normalize_for_dim

logger = logging.getLogger(__name__)

_SOURCE_PREFIX = "mock_keruyun"

# 与 dim_product 的做法一致(见 writer.py 的说明)。
# ⚠️ dim_ingredient 上有**两个**唯一约束: (factory_id, normalized_name) 和
#    (factory_id, source_pk)。ON CONFLICT 只能挂一个, 所以 source_pk 必须由
#    normalized_name 确定性推出来 —— 两个约束才会 1:1 对齐。若 source_pk 另有
#    来源, 同一个归一化名配两个 source_pk 会在第二个约束上撞唯一冲突, 而
#    ON CONFLICT 挂的是第一个, 捕不到, 直接报错。
_INGREDIENT_UPSERT_SQL = """
INSERT INTO dim_ingredient (factory_id, source_pk, name, normalized_name, category, unit)
VALUES ($1, $2, $3, $4, $5, $6)
ON CONFLICT (factory_id, normalized_name)
  DO UPDATE SET updated_at = NOW(),
                category = COALESCE(EXCLUDED.category, dim_ingredient.category),
                unit     = COALESCE(EXCLUDED.unit,     dim_ingredient.unit)
RETURNING ingredient_id
"""


def _qty(milli: int) -> Decimal:
    return (Decimal(milli) / Decimal(1000)).quantize(Decimal("0.0001"))


def _yuan(cents: int) -> Decimal:
    return (Decimal(cents) / Decimal(100)).quantize(Decimal("0.01"))


async def _resolve_ingredient(conn, factory_id: str, ref, cache: dict) -> int:
    """食材名 → dim_ingredient.ingredient_id (没有就建)。

    🔴 必须走 normalize_for_dim: 平台只给名字, 「生菜」「生菜 」「生 菜」
    不归一化就会裂成三个食材, 领料/损耗合计随之碎掉 —— 与 2026-07-29
    fact_pos_item.product_id 那次是同一类事故, 只是换了张表。
    """
    name = (ref.name or "").strip()
    if not name:
        raise RuntimeError(f"食材名为空: factory={factory_id} —— 上游报文有问题, 不静默跳过")
    normalized = normalize_for_dim(name)
    if not normalized:
        raise RuntimeError(
            f"食材名归一化后为空: factory={factory_id} name={name!r} —— 需要人看一眼")
    cached = cache.get(normalized)
    if cached is not None:
        return cached
    ingredient_id = await conn.fetchval(
        _INGREDIENT_UPSERT_SQL, factory_id, f"{_SOURCE_PREFIX}:{normalized}",
        name, normalized, ref.category, ref.unit,
    )
    if ingredient_id is None:
        raise RuntimeError(
            f"dim_ingredient UPSERT 没有返回 ingredient_id: "
            f"factory={factory_id} name={name!r}")
    cache[normalized] = ingredient_id
    return ingredient_id


_REQUISITION_SQL = """
INSERT INTO fact_restaurant_requisition
    (factory_id, source_pk, requisition_number, date, ingredient_id,
     status, requested_qty, actual_qty, unit, est_cost)
VALUES ($1,$2,$3,$4,$5,$6,$7,$7,$8,$9)
ON CONFLICT (factory_id, source_pk) DO UPDATE SET
    requested_qty = EXCLUDED.requested_qty,
    actual_qty    = EXCLUDED.actual_qty,
    est_cost      = EXCLUDED.est_cost,
    status        = EXCLUDED.status,
    updated_at    = NOW()
"""

_WASTAGE_SQL = """
INSERT INTO fact_restaurant_wastage
    (factory_id, source_pk, wastage_number, date, ingredient_id,
     wastage_type, status, quantity, unit, estimated_cost)
VALUES ($1,$2,$3,$4,$5,$6,'COMPLETED',$7,$8,$9)
ON CONFLICT (factory_id, source_pk) DO UPDATE SET
    quantity       = EXCLUDED.quantity,
    estimated_cost = EXCLUDED.estimated_cost,
    wastage_type   = EXCLUDED.wastage_type,
    updated_at     = NOW()
"""

_STOCKTAKING_SQL = """
INSERT INTO fact_restaurant_stocktaking
    (factory_id, source_pk, stocktaking_number, date, ingredient_id,
     status, system_qty, actual_qty, difference_qty, difference_cost, unit)
VALUES ($1,$2,$3,$4,$5,'COMPLETED',$6,$7,$8,$9,$10)
ON CONFLICT (factory_id, source_pk) DO UPDATE SET
    system_qty      = EXCLUDED.system_qty,
    actual_qty      = EXCLUDED.actual_qty,
    difference_qty  = EXCLUDED.difference_qty,
    difference_cost = EXCLUDED.difference_cost,
    updated_at      = NOW()
"""

_SUPPORTED_KINDS = ("requisition", "wastage", "stocktaking")


async def write_ops(pool, factory_id: str, kind: str, items: List) -> int:
    """写一批后厨单据到 Silver。返回写入条数(UPSERT 也计入)。

    与订单 writer 不同, 这里不区分"新增/已存在": 单据是可修订的
    (盘点复核、损耗补录), 每次都以平台最新值为准覆盖。
    """
    if kind not in _SUPPORTED_KINDS:
        raise ValueError(f"未知单据类型 {kind!r}, 仅支持 {list(_SUPPORTED_KINDS)}")
    if not items:
        return 0
    written = 0
    ingredient_cache: dict = {}
    async with pool.acquire() as conn:
        # set_config(..., true) 是**事务级**的: asyncpg 上不开显式事务从不生效,
        # 而这几张表(含 dim_ingredient)的 RLS 都没有 __internal__ 逃生门。
        async with conn.transaction():
            await conn.execute("SELECT set_config('app.factory_id', $1, true)", factory_id)
            for item in items:
                ing_id = await _resolve_ingredient(
                    conn, factory_id, item.ingredient, ingredient_cache)
                source_pk = f"{_SOURCE_PREFIX}:{item.doc_no}"
                unit = item.ingredient.unit
                if kind == "requisition":
                    await conn.execute(
                        _REQUISITION_SQL, factory_id, source_pk, item.doc_no,
                        item.biz_date, ing_id, item.status,
                        _qty(item.qty_milli), unit, _yuan(item.cost_cents),
                    )
                elif kind == "wastage":
                    await conn.execute(
                        _WASTAGE_SQL, factory_id, source_pk, item.doc_no,
                        item.biz_date, ing_id, item.wastage_type,
                        _qty(item.qty_milli), unit, _yuan(item.cost_cents),
                    )
                else:
                    await conn.execute(
                        _STOCKTAKING_SQL, factory_id, source_pk, item.doc_no,
                        item.biz_date, ing_id,
                        _qty(item.system_qty_milli), _qty(item.actual_qty_milli),
                        _qty(item.diff_qty_milli), _yuan(item.diff_cost_cents), unit,
                    )
                written += 1
    logger.info("[platform-sync] 写入 %d 条 %s 单据 (factory=%s)", written, kind, factory_id)
    return written
