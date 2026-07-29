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
