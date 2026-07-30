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

⚠️ 失败语义(2026-07-30 修订): 先做**写前只读校验**, 把「重试多少次都不会好」
   的永久坏单据(门店映射查不到/菜名归一化后为空/支付方式未知)隔离到
   platform_ingest_dead_letter 并**显式告警**, 其余照常整批一个事务写入。
   这样一条坏记录不再让该类数据永远卡在同一页。
   **瞬时**故障(DB 抖动/连接断)仍然抛异常让游标停住重试 —— 那才是对的。
   ⚠️ 顺序不可调换: 隔离先落库(独立事务), 成功之后才写事实表; 隔离写失败一律
   抛错。隔离没落库却推进游标 = 把坏记录连同它那一页之后的一切悄悄丢掉。
   将来若要放宽, 必须是"隔离到死信表 + 显式告警", 不能是静默 skip。

   🔴 2026-07-29 加菜品维度后, 这个取舍的爆炸半径变大了, 上面那条理由**不
   完全适用**: 门店/支付渠道是有限且由 migration 定死的闭集合, 菜名不是 ——
   它是外部平台来的自由文本。一道名字里只有标点的菜(normalize_for_dim 会把
   它整个吃空)就能让这一页永远卡住, 而且**后面所有数据都再也进不来**。
   这不是"配置错了本就该卡住", 是上游数据脏。死信表那条路从"将来可以做"
   变成了本模块最该优先补的事。
"""
from __future__ import annotations

import json
import logging
from decimal import Decimal
from typing import List, Optional

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


_DEAD_LETTER_UPSERT_SQL = (
    "INSERT INTO platform_ingest_dead_letter "
    "(factory_id, platform, kind, source_ref, payload, reason) "
    "VALUES ($1,$2,$3,$4,$5::jsonb,$6) "
    "ON CONFLICT (factory_id, platform, kind, source_ref) DO UPDATE SET "
    "  last_seen_at = NOW(), seen_count = platform_ingest_dead_letter.seen_count + 1, "
    "  reason = EXCLUDED.reason, payload = EXCLUDED.payload"
)


async def _permanent_defect(conn, factory_id: str, order: NormalizedOrder) -> Optional[str]:
    """这条单据是不是**永久性**坏? 是就返回人能看懂的原因, 否则 None。

    只做**只读**判定, 且必须在开写事务之前跑 —— 跑在写事务里的话, 事实表回滚
    会把隔离记录一起回滚, 于是既没写成也没隔离成, 下轮原样再来。

    只认「重试多少次都不会好」的那几类。DB 抖动、连接断这类**瞬时**故障不在
    此列: 它们照旧抛异常让游标停住重试, 那才是对的。
    """
    row = await conn.fetchrow(
        "SELECT store_id FROM platform_store_map "
        "WHERE factory_id = $1 AND platform = $2 AND platform_store_code = $3",
        factory_id, order.platform, order.store_code,
    )
    if row is None:
        return (f"门店映射查不到: platform={order.platform} "
                f"code={order.store_code!r}")
    for item in order.items:
        name = (item.dish_name or "").strip()
        if not name:
            return "明细缺 dishName(菜名为空)"
        if not normalize_for_dim(name):
            return f"菜名归一化后为空(纯标点?): {name!r}"
    for pay in order.payments:
        if _CHANNEL_NAME.get(pay.method) is None:
            return f"支付方式未知: {pay.method!r}"
        chan = await conn.fetchrow(
            "SELECT channel_id FROM dim_payment_channel "
            "WHERE factory_id = $1 AND name = $2",
            factory_id, _CHANNEL_NAME[pay.method],
        )
        if chan is None:
            return f"支付渠道查不到: name={_CHANNEL_NAME[pay.method]!r}"
    return None


def _order_payload(order: NormalizedOrder) -> str:
    """原始报文快照 —— 供人工核对与修好后重放。"""
    return json.dumps({
        "platform_order_no": order.platform_order_no,
        "store_code": order.store_code,
        "channel": order.channel,
        "biz_date": order.biz_date.isoformat(),
        "gross_cents": order.gross_cents,
        "net_cents": order.net_cents,
        "items": [{"dish_name": i.dish_name, "qty": i.qty,
                   "amount_cents": i.amount_cents} for i in order.items],
        "payments": [{"method": p.method, "amount_cents": p.amount_cents}
                     for p in order.payments],
    }, ensure_ascii=False)


async def write_orders(pool, factory_id: str, orders: List[NormalizedOrder]) -> int:
    """写一批订单到 Silver。返回实际新增的订单数(已存在的不计)。

    **永久性**坏单据被隔离到 platform_ingest_dead_letter 并显式告警, 其余照常
    写 —— 这样游标能推进, 一条坏记录不再让那类数据永远停在同一页。
    瞬时故障仍然抛异常让游标停住重试。

    ⚠️ 顺序不可调换: 隔离**先**落库(独立事务), 成功之后才写事实表。隔离写失败
    一律抛错 —— 隔离没落库却推进游标, 等于把坏记录连同它那一页之后的一切悄悄
    丢掉, 那比现在卡住严重得多。
    """
    if not orders:
        return 0
    written = 0
    # ① 写前校验(只读)。GUC 是事务级的, 只读查询也要在事务里才有 RLS 上下文。
    good: List[NormalizedOrder] = []
    bad: List[tuple] = []
    async with pool.acquire() as conn:
        async with conn.transaction():
            await conn.execute("SELECT set_config('app.factory_id', $1, true)", factory_id)
            for order in orders:
                reason = await _permanent_defect(conn, factory_id, order)
                (bad.append((order, reason)) if reason else good.append(order))

        # ② 隔离坏单据 —— 独立事务, 失败即抛(游标不推进, 绝不丢数据)。
        if bad:
            async with conn.transaction():
                await conn.execute(
                    "SELECT set_config('app.factory_id', $1, true)", factory_id)
                for order, reason in bad:
                    await conn.execute(
                        _DEAD_LETTER_UPSERT_SQL, factory_id, order.platform, "order",
                        order.platform_order_no, _order_payload(order), reason,
                    )
            # 禁降级: 隔离了就必须显式告警, 不能悄悄少几条。
            logger.error(
                "[platform-sync][DEAD-LETTER] factory=%s 隔离 %d/%d 笔永久坏单据, "
                "游标继续推进。明细见 platform_ingest_dead_letter。首条原因: %s",
                factory_id, len(bad), len(orders), bad[0][1],
            )
        if not good:
            return 0
        orders = good
    # 每批一份菜品缓存: 同一批里重复出现的菜只解析一次。刻意不做成模块级 ——
    # 那会跨租户串 product_id, 也会在 dim_product 被外部改动后发陈旧值。
        product_cache: dict = {}
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
