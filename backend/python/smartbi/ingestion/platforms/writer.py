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
"""
from __future__ import annotations

import logging
from decimal import Decimal
from typing import List

from .models import NormalizedOrder

logger = logging.getLogger(__name__)

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
                await _write_items(conn, factory_id, txn_id, order)
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


async def _write_items(conn, factory_id: str, txn_id: int, order: NormalizedOrder) -> None:
    for item in order.items:
        await conn.execute(
            "INSERT INTO fact_pos_item "
            "(transaction_id, factory_id, source_item_raw, qty, unit_price, amount) "
            "VALUES ($1,$2,$3,$4,$5,$6)",
            txn_id, factory_id, item.dish_name, item.qty,
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
