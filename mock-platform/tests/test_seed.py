import sqlite3

import pytest

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


def test_金额列拒绝浮点写入(tmp_path):
    """SQLite 弱类型: 没有 CHECK 的话 INTEGER 列能存 58.5。

    金额必须是「分」为单位的整数 —— 浮点累加会让跨平台对账出现假性不平,
    而那种不平看起来像真的数据问题, 极难查。
    """
    conn = connect(str(tmp_path / "c.db"))
    with pytest.raises(sqlite3.IntegrityError):
        conn.execute(
            "INSERT INTO dish(name, category, price_cents, cost_cents, groupon_eligible) "
            "VALUES ('浮点菜', '测试', 58.5, 21.3, 0)"
        )
