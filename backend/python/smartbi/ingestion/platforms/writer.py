"""归一化订单 → Silver 三张 fact 表。

列名与约束全部按 2026-07-29 生产实测校正, 不是凭记忆写的:
  * fact_pos_transaction 没有 transaction_no 列, 是 source_type + source_bill_no;
    幂等挂在**现成的** uq_fact_pos_txn 唯一约束
    (factory_id, source_type, store_id, source_bill_no) 上 —— ON CONFLICT 的
    列清单必须与它完全一致(含 store_id), 少一列 Postgres 匹配不到约束会直接报错。
  * fact_pos_item / fact_pos_payment 靠 transaction_id 外键关联, 不是靠单号
    → 主表 INSERT 必须 RETURNING id。
  * fact_pos_payment 没有 method 文本列, 是 NOT NULL 的 channel_id 外键
    → 按 (factory_id, name) 查 dim_payment_channel。
  * 门店映射走 platform_store_map(dim_store 没有 store_code 列)。

金额: 归一化模型用「分」(整数, 避免浮点累加让跨平台对账假性不平),
Silver 是 NUMERIC(18,2) 元, 这里除 100。

⚠️ 整批一个事务(刻意取舍, 非疏漏): 任一笔失败则整页回滚, 框架不推进游标,
   下轮重拉。好处是禁降级; 代价是一笔**永久性**坏订单会让该页永远卡住
   (每轮重试且游标不前进)。选择保留: 门店映射来自 migration 且覆盖全部
   10 家店, 真出现失配说明配置错了, 本就该卡住而不是静默跳过。
   将来若要放宽, 必须是"隔离到死信表 + 显式告警", 不能是静默 skip。

   🔴 2026-07-29 加菜品维度后, 这个取舍的爆炸半径变大了, 上面那条理由**不
   完全适用**: 门店/支付渠道是有限且由 migration 定死的闭集合, 菜名不是 ——
   它是外部平台来的自由文本。一道名字里只有标点的菜(normalize_for_dim 会把
   它整个吃空)就能让这一页永远卡住, 而且**后面所有数据都再也进不来**。
   这不是"配置错了本就该卡住", 是上游数据脏。死信表那条路从"将来可以做"
   变成了本模块最该优先补的事。
"""
from __future__ import annotations

import logging
from decimal import Decimal
from typing import List

from smartbi.canonical.entity_resolution.agents.deterministic import normalize_for_dim

from .models import NormalizedOrder

logger = logging.getLogger(__name__)

# 与 canonical/dim_resolver.py 的 _PRODUCT_UPSERT_SQL 同形(那里是 Excel 上传
# 通道解析菜品维度的地方)。这里单独写一份是因为必须跑在调用方那条**已设好
# app.factory_id 的事务连接**上, 见 _resolve_product 的说明。
# 只 UPSERT category: name/normalized_name 是唯一键的组成, sub_category 与
# sku_code 平台报文里没有, 留给 canonical 通道去补, 不要在这里写 NULL 覆盖。
_PRODUCT_UPSERT_SQL = """
INSERT INTO dim_product (factory_id, name, normalized_name, category)
VALUES ($1, $2, $3, $4)
ON CONFLICT (factory_id, normalized_name)
  DO UPDATE SET updated_at = NOW(),
                category   = COALESCE(EXCLUDED.category, dim_product.category)
RETURNING product_id
"""

# 模拟端的支付方式 → dim_payment_channel.name。
# 两边必须同时改: 这里加一项, V20261101_01 的 dim_payment_channel 种子也要加。
# 有一条测试从 mock_platform/world/generator.py 真解析支付方式集合来钉住这一点。
_CHANNEL_NAME = {
    "cash": "现金",
    "wechat": "微信",
    "alipay": "支付宝",
    "platform": "平台代收",
}

_SOURCE_TYPE = "mock_keruyun"


def _yuan(cents: int) -> Decimal:
    return (Decimal(cents) / Decimal(100)).quantize(Decimal("0.01"))


async def write_orders(pool, factory_id: str, orders: List[NormalizedOrder]) -> int:
    """写一批订单到 Silver。返回实际新增的订单数(已存在的不计)。

    失败路径一律抛异常 —— 框架据此保持游标不动, 下轮重拉。
    """
    if not orders:
        return 0
    written = 0
    # 每批一份菜品缓存: 同一批里重复出现的菜只解析一次。刻意不做成模块级 ——
    # 那会跨租户串 product_id, 也会在 dim_product 被外部改动后发陈旧值。
    product_cache: dict = {}
    async with pool.acquire() as conn:
        # set_config(..., true) 是**事务级**的: asyncpg 上不开显式事务从不生效,
        # RLS 会靠连接池残留碰运气。这层 transaction 不能省。
        async with conn.transaction():
            await conn.execute("SELECT set_config('app.factory_id', $1, true)", factory_id)
            for order in orders:
                store_id = await _resolve_store(conn, factory_id, order)
                txn_row = await conn.fetchrow(
                    "INSERT INTO fact_pos_transaction "
                    "(factory_id, store_id, source_type, source_bill_no, date, time, "
                    " gross_amount, discount_amount, net_amount, customer_count, "
                    " item_count, order_type) "
                    "VALUES ($1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$11,$12) "
                    "ON CONFLICT (factory_id, source_type, store_id, source_bill_no) "
                    "DO NOTHING "
                    "RETURNING id",
                    factory_id, store_id, _SOURCE_TYPE, order.platform_order_no,
                    order.biz_date, order.placed_at,
                    _yuan(order.gross_cents), _yuan(order.discount_cents),
                    _yuan(order.net_cents), order.guest_count, len(order.items),
                    order.channel,
                )
                if txn_row is None:
                    # 已存在(幂等命中): 明细也不必重写。不计入 written。
                    continue
                txn_id = txn_row["id"]
                written += 1
                await _write_items(conn, factory_id, txn_id, order, product_cache)
                await _write_payments(conn, factory_id, txn_id, order)
    logger.info("[platform-sync] 写入 %d/%d 笔订单 (factory=%s)",
                written, len(orders), factory_id)
    return written


async def _resolve_store(conn, factory_id: str, order: NormalizedOrder) -> int:
    """平台门店 code → dim_store.store_id。查不到就报错, 不建"未知门店"也不丢弃。"""
    row = await conn.fetchrow(
        "SELECT store_id FROM platform_store_map "
        "WHERE factory_id = $1 AND platform = $2 AND platform_store_code = $3",
        factory_id, order.platform, order.store_code,
    )
    if row is None:
        raise RuntimeError(
            f"门店映射失败: factory={factory_id} platform={order.platform} "
            f"code={order.store_code} —— 检查 V20261101_01 的 platform_store_map "
            f"种子是否与模拟端 seed.py 的 _STORES 对齐"
        )
    return row["store_id"]


async def _resolve_product(conn, factory_id: str, item, cache: dict) -> int:
    """菜名 → dim_product.product_id (没有就建)。

    ⚠️ 为什么是 get-or-create, 而不是像门店/支付渠道那样"查不到就报错":
    菜单是会变的 —— 真实平台上新菜是日常操作, 要求每上一道菜先发一版
    migration 不现实。门店和支付渠道是有限且稳定的集合, 菜品不是。
    这也正是 dim_product 自带 (factory_id, normalized_name) 唯一约束 +
    仓库里 canonical/dim_resolver.py 用 UPSERT 解析它的原因, 本函数沿用
    那条 SQL 的形状。

    ⚠️ 不复用 DimResolver 本体: 它内部 `pool.acquire()` 另取一条连接, 而
    app.factory_id 是**事务级**GUC, 只设在本函数外面那条连接上。换连接
    等于把 RLS 交给连接池残留碰运气(本仓已有前科), 所以在同一 conn 上跑。

    cache 是**每批次**的(由调用方传入), 不是模块级: 模块级缓存会跨租户
    串 product_id, 也会在 dim_product 被外部改动后发陈旧值。
    """
    name = (item.dish_name or "").strip()
    if not name:
        # 禁降级: 没有菜名就没法建维度, 不能塞个"未知菜品"混进菜品分析。
        raise RuntimeError(
            f"菜名为空: factory={factory_id} txn 明细缺 dishName —— "
            f"上游报文有问题, 不静默跳过"
        )
    normalized = normalize_for_dim(name)
    if not normalized:
        raise RuntimeError(
            f"菜名归一化后为空: factory={factory_id} name={name!r} —— "
            f"normalize_for_dim 把它整个吃掉了(纯标点?), 需要人看一眼"
        )
    cached = cache.get(normalized)
    if cached is not None:
        return cached
    product_id = await conn.fetchval(
        _PRODUCT_UPSERT_SQL, factory_id, name, normalized, item.category,
    )
    if product_id is None:
        # RETURNING 没给出行。真发生了说明 UPSERT 语义不是我们以为的那样
        # (比如有人把 DO UPDATE 改成 DO NOTHING —— 那时 RETURNING 不返回行)。
        # 放行就会把 NULL 写回 product_id, 正好复现本函数存在的理由。
        raise RuntimeError(
            f"dim_product UPSERT 没有返回 product_id: factory={factory_id} name={name!r}"
        )
    cache[normalized] = product_id
    return product_id


async def _write_items(conn, factory_id: str, txn_id: int, order: NormalizedOrder,
                       product_cache: dict) -> None:
    for item in order.items:
        product_id = await _resolve_product(conn, factory_id, item, product_cache)
        await conn.execute(
            "INSERT INTO fact_pos_item "
            "(transaction_id, factory_id, product_id, source_item_raw, qty, "
            " unit_price, amount) "
            "VALUES ($1,$2,$3,$4,$5,$6,$7)",
            txn_id, factory_id, product_id, item.dish_name, item.qty,
            _yuan(item.price_cents), _yuan(item.amount_cents),
        )


async def _write_payments(conn, factory_id: str, txn_id: int,
                          order: NormalizedOrder) -> None:
    for pay in order.payments:
        channel_name = _CHANNEL_NAME.get(pay.method)
        if channel_name is None:
            raise RuntimeError(
                f"未知支付方式 {pay.method!r} —— 需在 writer 的 _CHANNEL_NAME 与 "
                f"V20261101_01 的 dim_payment_channel 种子里**同时**补上"
            )
        row = await conn.fetchrow(
            "SELECT channel_id FROM dim_payment_channel "
            "WHERE factory_id = $1 AND name = $2",
            factory_id, channel_name,
        )
        if row is None:
            raise RuntimeError(
                f"支付渠道映射失败: factory={factory_id} name={channel_name} "
                f"—— 检查 V20261101_01 的 dim_payment_channel 种子"
            )
        await conn.execute(
            "INSERT INTO fact_pos_payment "
            "(transaction_id, factory_id, channel_id, amount) VALUES ($1,$2,$3,$4)",
            txn_id, factory_id, row["channel_id"], _yuan(pay.amount_cents),
        )
