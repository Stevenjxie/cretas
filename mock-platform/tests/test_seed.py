from mock_platform.db import connect
from mock_platform.world.seed import seed_world


def test_种子建出10家店且业态齐全(tmp_path):
    conn = connect(str(tmp_path / "t.db"))
    seed_world(conn, store_count=10)
    rows = conn.execute("SELECT format, COUNT(*) c FROM store GROUP BY format").fetchall()
    formats = {r["format"]: r["c"] for r in rows}
    assert sum(formats.values()) == 10
    assert set(formats) == {"flagship", "community", "mall"}


def test_种子幂等重复跑不翻倍(tmp_path):
    conn = connect(str(tmp_path / "t.db"))
    seed_world(conn, store_count=10)
    seed_world(conn, store_count=10)
    assert conn.execute("SELECT COUNT(*) c FROM store").fetchone()["c"] == 10


def test_菜品有成本且成本低于售价(tmp_path):
    conn = connect(str(tmp_path / "t.db"))
    seed_world(conn, store_count=10)
    bad = conn.execute(
        "SELECT name FROM dish WHERE cost_cents <= 0 OR cost_cents >= price_cents"
    ).fetchall()
    assert bad == [], f"成本非法的菜品: {[r['name'] for r in bad]}"
