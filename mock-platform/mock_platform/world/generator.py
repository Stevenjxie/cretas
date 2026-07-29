"""订单生成。生成器是世界模型唯一的写入方，平台 router 只读。

金额一律用「分」为单位的整数，避免浮点累加误差导致三边对账假性不平。
"""
from __future__ import annotations

import datetime
import random
import sqlite3

from .curve import daily_minute_quota

_CHANNELS = ("dine_in", "takeaway", "groupon")
_CHANNEL_WEIGHTS = (0.62, 0.28, 0.10)
_PAY_BY_CHANNEL = {
    "dine_in": ("wechat", "alipay", "cash"),
    "takeaway": ("platform",),
    "groupon": ("platform", "wechat"),
}


def _next_seq(conn: sqlite3.Connection) -> int:
    row = conn.execute('SELECT COALESCE(MAX(seq), 0) + 1 AS s FROM "order"').fetchone()
    return int(row["s"])


def generate_orders(conn, *, store_id: int, biz_date: str,
                    minute_of_day: int, count: int, rng: random.Random) -> int:
    """在指定门店/营业日/分钟生成 count 笔订单。返回实际新建数。

    包一层显式事务：`db.connect()` 用的是 `isolation_level=None`（自动提交），
    不包事务的话每条 INSERT 各自一次 fsync —— 实测 2000 条要 95s，30 天回填
    （约 36 万条）要 4.8 小时。包一层事务后同样的量是 0.07s。用
    `conn.in_transaction` 守卫，这样它既能独立调用、也能被 `backfill` 的大
    事务包住而不触发嵌套 BEGIN 错误。
    """
    if count <= 0:
        return 0
    owns_txn = not conn.in_transaction
    if owns_txn:
        conn.execute("BEGIN")
    try:
        created = _generate_orders_inner(
            conn, store_id=store_id, biz_date=biz_date,
            minute_of_day=minute_of_day, count=count, rng=rng,
        )
    except Exception:
        if owns_txn:
            conn.execute("ROLLBACK")
        raise
    if owns_txn:
        conn.execute("COMMIT")
    return created


def _generate_orders_inner(conn, *, store_id: int, biz_date: str,
                           minute_of_day: int, count: int, rng: random.Random) -> int:
    dishes = conn.execute("SELECT id, price_cents, groupon_eligible FROM dish").fetchall()
    if not dishes:
        raise RuntimeError("菜品表为空，先跑 seed_world")
    seq = _next_seq(conn)
    placed_base = datetime.datetime.fromisoformat(biz_date).replace(
        hour=minute_of_day // 60, minute=minute_of_day % 60
    )
    created = 0
    for i in range(count):
        channel = rng.choices(_CHANNELS, weights=_CHANNEL_WEIGHTS, k=1)[0]
        guest_count = rng.randint(1, 6) if channel == "dine_in" else 1
        pool = [d for d in dishes if not (channel == "groupon" and not d["groupon_eligible"])]
        line_count = rng.randint(2, 6)
        lines = []
        gross = 0
        for _ in range(line_count):
            dish = rng.choice(pool)
            qty = rng.randint(1, 3)
            amount = dish["price_cents"] * qty
            gross += amount
            lines.append((dish["id"], qty, dish["price_cents"], amount))
        discount = 0
        if channel == "groupon":
            discount = int(gross * rng.uniform(0.15, 0.30))
        elif channel == "takeaway":
            discount = int(gross * rng.uniform(0.0, 0.12))
        net = gross - discount
        order_no = f"MK{placed_base:%Y%m%d}{store_id:02d}{seq:08d}"
        placed_at = (placed_base + datetime.timedelta(seconds=rng.randint(0, 59))).isoformat()
        cur = conn.execute(
            'INSERT INTO "order"(order_no, store_id, channel, placed_at, biz_date, '
            "gross_cents, discount_cents, net_cents, guest_count, seq) "
            "VALUES (?,?,?,?,?,?,?,?,?,?)",
            (order_no, store_id, channel, placed_at, biz_date,
             gross, discount, net, guest_count, seq),
        )
        order_id = cur.lastrowid
        conn.executemany(
            "INSERT INTO order_item(order_id, dish_id, qty, price_cents, amount_cents) "
            "VALUES (?,?,?,?,?)",
            [(order_id, d, q, p, a) for d, q, p, a in lines],
        )
        method = rng.choice(_PAY_BY_CHANNEL[channel])
        conn.execute(
            "INSERT INTO payment(order_id, method, amount_cents) VALUES (?,?,?)",
            (order_id, method, net),
        )
        seq += 1
        created += 1
    return created


def backfill(conn, *, days: int, orders_per_store: int,
             today: datetime.date, rng: random.Random) -> int:
    """一次性造过去 days 天的历史订单（不含今天）。返回新建总数。

    看板一开始就要有趋势和环比可看，否则得等一个月。

    整个回填包在一个显式事务里（而不是每分钟调一次带自己事务的
    `generate_orders`）——同理，避免自动提交模式下上千次 fsync。
    """
    stores = conn.execute("SELECT id, format FROM store ORDER BY id").fetchall()
    total = 0
    conn.execute("BEGIN")
    try:
        for day_offset in range(days, 0, -1):
            biz_date = (today - datetime.timedelta(days=day_offset)).isoformat()
            for store in stores:
                quota = daily_minute_quota(store["format"], orders_per_store)
                for minute, count in enumerate(quota):
                    if count:
                        total += _generate_orders_inner(
                            conn, store_id=store["id"], biz_date=biz_date,
                            minute_of_day=minute, count=count, rng=rng,
                        )
    except Exception:
        conn.execute("ROLLBACK")
        raise
    conn.execute("COMMIT")
    return total
