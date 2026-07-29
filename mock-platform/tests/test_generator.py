import datetime
import random

from mock_platform.db import connect
from mock_platform.world.generator import backfill, generate_orders
from mock_platform.world.seed import seed_world


def _conn(tmp_path):
    conn = connect(str(tmp_path / "g.db"))
    seed_world(conn, store_count=10)
    return conn


def test_生成的订单金额自洽(tmp_path):
    conn = _conn(tmp_path)
    generate_orders(conn, store_id=1, biz_date="2026-07-29",
                    minute_of_day=12 * 60, count=5, rng=random.Random(42))
    rows = conn.execute(
        'SELECT o.id, o.gross_cents, o.discount_cents, o.net_cents, '
        '(SELECT COALESCE(SUM(amount_cents),0) FROM order_item WHERE order_id=o.id) items '
        'FROM "order" o').fetchall()
    assert len(rows) == 5
    for r in rows:
        assert r["gross_cents"] == r["items"], "订单毛额必须等于明细合计"
        assert r["net_cents"] == r["gross_cents"] - r["discount_cents"]
        assert r["net_cents"] > 0


def test_支付金额等于订单实收(tmp_path):
    conn = _conn(tmp_path)
    generate_orders(conn, store_id=1, biz_date="2026-07-29",
                    minute_of_day=19 * 60, count=8, rng=random.Random(7))
    bad = conn.execute(
        'SELECT o.order_no FROM "order" o WHERE o.net_cents <> '
        '(SELECT COALESCE(SUM(amount_cents),0) FROM payment WHERE order_id=o.id)'
    ).fetchall()
    assert bad == [], f"支付与实收不符: {[r['order_no'] for r in bad]}"


def test_seq严格单调递增(tmp_path):
    conn = _conn(tmp_path)
    generate_orders(conn, store_id=1, biz_date="2026-07-29",
                    minute_of_day=12 * 60, count=3, rng=random.Random(1))
    generate_orders(conn, store_id=2, biz_date="2026-07-29",
                    minute_of_day=12 * 60, count=3, rng=random.Random(2))
    seqs = [r["seq"] for r in conn.execute('SELECT seq FROM "order" ORDER BY id')]
    assert seqs == sorted(seqs)
    assert len(set(seqs)) == len(seqs)


def test_回填按每店日单量产出(tmp_path):
    conn = _conn(tmp_path)
    created = backfill(conn, days=2, orders_per_store=200,
                       today=datetime.date(2026, 7, 29), rng=random.Random(3))
    assert created == 10 * 200 * 2
    dates = {r["biz_date"] for r in conn.execute('SELECT DISTINCT biz_date FROM "order"')}
    assert dates == {"2026-07-27", "2026-07-28"}


def test_回填在事务里跑_不是逐条自动提交(tmp_path):
    """自动提交模式下每条 INSERT 一次 fsync: 实测 2000 条要 95 秒,
    30 天回填(约 36 万条)要 4.8 小时 —— 功能等于不可用。
    包一层显式事务后同样的量是 0.07 秒。这条测试守住那个量级差。
    """
    import time
    conn = _conn(tmp_path)
    started = time.time()
    backfill(conn, days=1, orders_per_store=200,
             today=datetime.date(2026, 7, 29), rng=random.Random(5))
    elapsed = time.time() - started
    assert elapsed < 30, (
        f"回填 2000 单耗时 {elapsed:.1f}s —— 太慢, 说明没跑在显式事务里"
    )


# ── 后厨供应链派生 ──────────────────────────────────────────────────

def _seeded(tmp_path):
    from mock_platform.db import connect
    from mock_platform.world.seed import seed_world
    conn = connect(str(tmp_path / "ops.db"))
    seed_world(conn, store_count=2)
    return conn


def test_没有销量就不造供应链数据(tmp_path):
    """禁降级: 那天没卖东西就是没有领料, 不是造 0 也不是造随机数。"""
    from mock_platform.world.generator import generate_daily_ops
    conn = _seeded(tmp_path)
    stats = generate_daily_ops(conn, store_id=1, biz_date="2026-07-20",
                               rng=random.Random(1))
    assert stats == {"requisition": 0, "wastage": 0, "stocktaking": 0}
    assert conn.execute("SELECT COUNT(*) c FROM requisition").fetchone()["c"] == 0


def test_领料量与当天真实消耗对得上(tmp_path):
    """领料 = 当天卖出的菜 × 配方 × 溢出系数。这条锚断了, 后厨数据就成了
    与前厅无关的随机数, 一分析就露馅。"""
    from mock_platform.world.generator import generate_daily_ops, generate_orders
    conn = _seeded(tmp_path)
    generate_orders(conn, store_id=1, biz_date="2026-07-20", minute_of_day=12 * 60,
                    count=40, rng=random.Random(7))
    generate_daily_ops(conn, store_id=1, biz_date="2026-07-20", rng=random.Random(7))
    rows = conn.execute(
        "SELECT r.ingredient_id, r.qty_milli AS req, "
        "       (SELECT SUM(oi.qty * rc.qty_milli) FROM \"order\" o "
        "          JOIN order_item oi ON oi.order_id = o.id "
        "          JOIN recipe rc ON rc.dish_id = oi.dish_id "
        "         WHERE o.store_id = 1 AND o.biz_date = '2026-07-20' "
        "           AND rc.ingredient_id = r.ingredient_id) AS used "
        "  FROM requisition r WHERE r.biz_date = '2026-07-20'"
    ).fetchall()
    assert rows, "有销量就该有领料"
    for r in rows:
        # 溢出系数 1.04~1.14, 留一点取整余地
        assert r["used"] < r["req"] <= r["used"] * 1.15 + 1, dict(r)


def test_供应链幂等_重复跑不翻倍也不改条数(tmp_path):
    from mock_platform.world.generator import generate_daily_ops, generate_orders
    conn = _seeded(tmp_path)
    generate_orders(conn, store_id=1, biz_date="2026-07-20", minute_of_day=12 * 60,
                    count=30, rng=random.Random(3))
    generate_daily_ops(conn, store_id=1, biz_date="2026-07-20", rng=random.Random(3))
    n1 = conn.execute("SELECT COUNT(*) c FROM requisition").fetchone()["c"]
    generate_daily_ops(conn, store_id=1, biz_date="2026-07-20", rng=random.Random(9))
    assert conn.execute("SELECT COUNT(*) c FROM requisition").fetchone()["c"] == n1


def test_损耗单据号确定_不随进程hash变化(tmp_path):
    """doc_no 是 UNIQUE。用 hash(wtype) 生成会因 PYTHONHASHSEED 随机化,
    重启后同一条损耗换单据号, 幂等 UPSERT 会撞上别的行。"""
    from mock_platform.world.generator import _WASTAGE_TYPE_CODE
    assert set(_WASTAGE_TYPE_CODE) == {"加工损耗", "变质", "客诉退菜"}
    assert len(set(_WASTAGE_TYPE_CODE.values())) == 3


def test_盘点只在周一发生(tmp_path):
    from mock_platform.world.generator import generate_daily_ops, generate_orders
    conn = _seeded(tmp_path)
    for d in ("2026-07-20", "2026-07-21"):    # 20 是周一, 21 是周二
        generate_orders(conn, store_id=1, biz_date=d, minute_of_day=12 * 60,
                        count=20, rng=random.Random(5))
        generate_daily_ops(conn, store_id=1, biz_date=d, rng=random.Random(5))
    dates = [r["biz_date"] for r in
             conn.execute("SELECT DISTINCT biz_date FROM stocktaking").fetchall()]
    assert dates == ["2026-07-20"], f"盘点日期不对: {dates}"


def test_回填顺带产出供应链(tmp_path):
    from mock_platform.world.generator import backfill
    conn = _seeded(tmp_path)
    backfill(conn, days=3, orders_per_store=20,
             today=datetime.date(2026, 7, 23), rng=random.Random(11))
    for t in ("requisition", "wastage"):
        c = conn.execute(f"SELECT COUNT(*) c FROM {t}").fetchone()["c"]
        assert c > 0, f"{t} 回填后应当有数据"


def test_修订会推进seq_对端才看得到(tmp_path):
    """🔴 分页是 `seq > cursor`。UPSERT 时不推进 seq, 这条改动就永远追不上
    游标, 对端再也拿不到 —— 「当天营业中反复调用、数字随消耗增长」这个卖点
    会变成一句空话(审查 I1)。"""
    from mock_platform.world.generator import generate_daily_ops, generate_orders
    conn = _seeded(tmp_path)
    generate_orders(conn, store_id=1, biz_date="2026-07-20", minute_of_day=12 * 60,
                    count=20, rng=random.Random(1))
    generate_daily_ops(conn, store_id=1, biz_date="2026-07-20", rng=random.Random(1))
    before = conn.execute("SELECT MAX(seq) s FROM requisition").fetchone()["s"]
    # 又卖了一批 → 消耗变大 → 领料量该跟着变, 且 seq 要推进
    generate_orders(conn, store_id=1, biz_date="2026-07-20", minute_of_day=13 * 60,
                    count=20, rng=random.Random(2))
    generate_daily_ops(conn, store_id=1, biz_date="2026-07-20", rng=random.Random(2))
    after = conn.execute("SELECT MAX(seq) s FROM requisition").fetchone()["s"]
    assert after > before, "修订必须推进 seq, 否则对端永远看不到这次更新"


def test_三类单据的状态与下游Gold口径一致(tmp_path):
    """🔴 状态写错不会报错, 只会让 Gold 静默为 0(审查 C1)。
    实测 restaurant_ops_etl: 领料 APPROVED/SUBMITTED、损耗 APPROVED、盘点 COMPLETED。"""
    from mock_platform.world.generator import generate_daily_ops, generate_orders
    conn = _seeded(tmp_path)
    generate_orders(conn, store_id=1, biz_date="2026-07-20", minute_of_day=12 * 60,
                    count=20, rng=random.Random(1))
    generate_daily_ops(conn, store_id=1, biz_date="2026-07-20", rng=random.Random(1))
    for table, accepted in (("requisition", {"APPROVED", "SUBMITTED"}),
                            ("wastage", {"APPROVED"}),
                            ("stocktaking", {"COMPLETED"})):
        got = {r["status"] for r in
               conn.execute(f"SELECT DISTINCT status FROM {table}").fetchall()}
        assert got and got <= accepted, f"{table}: {got} 会被 Gold 过滤掉"
