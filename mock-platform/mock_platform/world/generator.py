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


def _next_ops_seq(conn: sqlite3.Connection, table: str) -> int:
    """三张供应链表各有自己的游标序列。表名由本模块的常量拼, 不来自外部输入。"""
    row = conn.execute(f"SELECT COALESCE(MAX(seq), 0) + 1 AS s FROM {table}").fetchone()
    return int(row["s"])


# 领料溢出系数: 门店按预估订货, 总会比实际吃掉的多一点。
_REQUISITION_OVERAGE = (1.04, 1.14)
# 加工损耗率: 择菜、切配、边角料 —— 每天都有, 与用量成正比。
_PREP_LOSS_RATE = (0.015, 0.040)
# 变质率: 只发生在短保食材上, 而且不是每天都有。
_SPOILAGE_RATE = (0.010, 0.035)
_SPOILAGE_SHELF_LIFE_MAX = 5      # 保质期 ≤5 天的算短保
_SPOILAGE_CHANCE = 0.35
# 客诉退菜: 少见, 量也小。
_RETURN_CHANCE = 0.15
_RETURN_RATE = (0.002, 0.008)
# 损耗类型 → 单据号里的固定编码。**不能用 hash(wtype)**: Python 的字符串
# hash 带 PYTHONHASHSEED 随机化, 跨进程不稳定, 而 doc_no 是 UNIQUE ——
# 重启一次同一条损耗就会换单据号, 幂等 UPSERT 会撞上别的行的 doc_no。
_WASTAGE_TYPE_CODE = {"加工损耗": "01", "变质": "02", "客诉退菜": "03"}
# 单据状态。⚠️ **必须与下游 Gold 的过滤口径对上**, 实测(restaurant_ops_etl):
#   领料 agg: WHERE status IN ('APPROVED','SUBMITTED')
#   损耗 agg: WHERE status = 'APPROVED'
#   盘点 agg: WHERE status = 'COMPLETED'
# 写错的后果不是报错而是**静默为 0**: Silver 有行、闸能开、AI 照答,
# 但按食材的领料/损耗 KPI 全空 —— "没数据"看起来就像"是 0"。
# (2026-07-29 审查抓到: 三张表都写成 COMPLETED, 其中两张会被整个过滤掉。)
_STATUS_REQUISITION = "APPROVED"
_STATUS_WASTAGE = "APPROVED"
_STATUS_STOCKTAKING = "COMPLETED"
# 盘点周期: 每周一次(按营业日的 ISO 周内第几天定, 保证同一天全店一起盘)。
_STOCKTAKE_WEEKDAY = 0            # 周一
_STOCKTAKE_VARIANCE = (-0.03, 0.02)   # 实盘相对系统账的偏差, 略偏亏损


def _consumption_by_ingredient(conn, *, store_id: int, biz_date: str):
    """当天该店真实卖出的菜 × 配方 = 各食材实际消耗(毫单位)。

    这是整个供应链数据的锚: 领料/损耗/盘点全都从它派生, 所以模拟出来的
    后厨数字和前厅销量是自洽的 —— 卖得多的日子领得多, 卖鱼多的店鱼消耗大。
    凭空造随机数就没有这个性质, 一分析就露馅。
    """
    return conn.execute(
        "SELECT r.ingredient_id, i.unit_price_cents, i.shelf_life_days, "
        "       SUM(oi.qty * r.qty_milli) AS used_milli "
        "  FROM \"order\" o "
        "  JOIN order_item oi ON oi.order_id = o.id "
        "  JOIN recipe r ON r.dish_id = oi.dish_id "
        "  JOIN ingredient i ON i.id = r.ingredient_id "
        " WHERE o.store_id = ? AND o.biz_date = ? "
        " GROUP BY r.ingredient_id",
        (store_id, biz_date),
    ).fetchall()


def _cost_of(qty_milli: int, unit_price_cents: int) -> int:
    """毫单位用量 × 单价 → 分。四舍五入到整数分, 不留浮点。"""
    return int(round(qty_milli * unit_price_cents / 1000))


def generate_daily_ops(conn, *, store_id: int, biz_date: str,
                       rng: random.Random) -> dict:
    """按当天实际消耗派生该店的领料/损耗/盘点。返回各类新建/更新条数。

    幂等: 三张表都有 (biz_date, store_id, ingredient_id[, type]) 唯一约束,
    重复调用是 UPSERT 而不是翻倍。这一点是刻意的 —— 当天营业还在继续时
    可以反复调用, 数字随消耗增长, 供应链数据因此也是"实时"的。
    """
    owns_txn = not conn.in_transaction
    if owns_txn:
        conn.execute("BEGIN")
    try:
        stats = _generate_daily_ops_inner(
            conn, store_id=store_id, biz_date=biz_date, rng=rng)
    except Exception:
        if owns_txn:
            conn.execute("ROLLBACK")
        raise
    if owns_txn:
        conn.execute("COMMIT")
    return stats


def _generate_daily_ops_inner(conn, *, store_id: int, biz_date: str,
                              rng: random.Random) -> dict:
    rows = _consumption_by_ingredient(conn, store_id=store_id, biz_date=biz_date)
    stats = {"requisition": 0, "wastage": 0, "stocktaking": 0}
    if not rows:
        # 那天那家店没卖东西(例如未来日期或还没开始生成) —— 没有消耗就没有
        # 领料, 这是事实而不是异常, 不造数。
        return stats

    req_seq = _next_ops_seq(conn, "requisition")
    waste_seq = _next_ops_seq(conn, "wastage")
    stock_seq = _next_ops_seq(conn, "stocktaking")
    is_stocktake_day = (
        datetime.date.fromisoformat(biz_date).weekday() == _STOCKTAKE_WEEKDAY
    )

    for r in rows:
        ing_id = r["ingredient_id"]
        used = int(r["used_milli"])
        price = int(r["unit_price_cents"])

        # ── 领料: 消耗 × 溢出 ──────────────────────────────────
        req_qty = int(round(used * rng.uniform(*_REQUISITION_OVERAGE)))
        conn.execute(
            "INSERT INTO requisition(doc_no, store_id, ingredient_id, biz_date, "
            " qty_milli, cost_cents, status, seq) VALUES (?,?,?,?,?,?,?,?) "
            "ON CONFLICT(biz_date, store_id, ingredient_id) DO UPDATE SET "
            " qty_milli=excluded.qty_milli, cost_cents=excluded.cost_cents, "
            # seq 也要推进: 分页是 `seq > cursor`, 不推的话这条改动永远
            # 追不上游标, 对端再也看不到 —— 当天营业中反复调用的修订就白改了。
            " seq=excluded.seq",
            (f"RQ{biz_date.replace('-', '')}{store_id:02d}{ing_id:03d}",
             store_id, ing_id, biz_date, req_qty, _cost_of(req_qty, price),
             _STATUS_REQUISITION, req_seq),
        )
        req_seq += 1
        stats["requisition"] += 1

        # ── 损耗: 加工损耗每天都有; 变质只在短保食材上偶发; 退菜少见 ──
        losses = [("加工损耗", rng.uniform(*_PREP_LOSS_RATE))]
        if (int(r["shelf_life_days"]) <= _SPOILAGE_SHELF_LIFE_MAX
                and rng.random() < _SPOILAGE_CHANCE):
            losses.append(("变质", rng.uniform(*_SPOILAGE_RATE)))
        if rng.random() < _RETURN_CHANCE:
            losses.append(("客诉退菜", rng.uniform(*_RETURN_RATE)))
        for wtype, rate in losses:
            qty = int(round(used * rate))
            if qty <= 0:
                continue          # 量太小取整成 0, 就是没有这笔, 不硬造
            conn.execute(
                "INSERT INTO wastage(doc_no, store_id, ingredient_id, biz_date, "
                " wastage_type, status, qty_milli, cost_cents, seq) "
                "VALUES (?,?,?,?,?,?,?,?,?) "
                "ON CONFLICT(biz_date, store_id, ingredient_id, wastage_type) "
                "DO UPDATE SET qty_milli=excluded.qty_milli, "
                " cost_cents=excluded.cost_cents, seq=excluded.seq",
                (f"WS{biz_date.replace('-', '')}{store_id:02d}{ing_id:03d}"
                 f"{_WASTAGE_TYPE_CODE[wtype]}",
                 store_id, ing_id, biz_date, wtype, _STATUS_WASTAGE,
                 qty, _cost_of(qty, price), waste_seq),
            )
            waste_seq += 1
            stats["wastage"] += 1

        # ── 盘点: 每周一次, 实盘 vs 系统账 ──────────────────────
        if is_stocktake_day:
            system_qty = req_qty
            actual_qty = int(round(system_qty * (1 + rng.uniform(*_STOCKTAKE_VARIANCE))))
            diff = actual_qty - system_qty
            conn.execute(
                "INSERT INTO stocktaking(doc_no, store_id, ingredient_id, biz_date, "
                " system_qty_milli, actual_qty_milli, diff_cost_cents, status, seq) "
                "VALUES (?,?,?,?,?,?,?,?,?) "
                "ON CONFLICT(biz_date, store_id, ingredient_id) DO UPDATE SET "
                " system_qty_milli=excluded.system_qty_milli, "
                " actual_qty_milli=excluded.actual_qty_milli, "
                " diff_cost_cents=excluded.diff_cost_cents, seq=excluded.seq",
                (f"ST{biz_date.replace('-', '')}{store_id:02d}{ing_id:03d}",
                 store_id, ing_id, biz_date, system_qty, actual_qty,
                 _cost_of(diff, price), _STATUS_STOCKTAKING, stock_seq),
            )
            stock_seq += 1
            stats["stocktaking"] += 1

    return stats


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
                # 当天订单齐了才派生后厨: 领料/损耗是按当天实际消耗算的,
                # 顺序反了会按空销量算出全 0 的供应链数据。
                _generate_daily_ops_inner(
                    conn, store_id=store["id"], biz_date=biz_date, rng=rng)
    except Exception:
        conn.execute("ROLLBACK")
        raise
    conn.execute("COMMIT")
    return total
