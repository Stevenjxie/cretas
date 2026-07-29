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


# ── 后厨供应链种子 ──────────────────────────────────────────────────

def test_每道菜都有配方(tmp_path):
    """配方是「领料量从真实销量推出来」的前提。缺一道菜, 那道菜卖再多也
    推不出食材消耗, 供应链数据就出现无声的空洞。"""
    conn = connect(str(tmp_path / "t.db"))
    seed_world(conn, store_count=10)
    missing = conn.execute(
        "SELECT d.name FROM dish d LEFT JOIN recipe r ON r.dish_id = d.id "
        "WHERE r.dish_id IS NULL"
    ).fetchall()
    assert [r["name"] for r in missing] == []


def test_配方推出的食材成本落在菜品成本量级内(tmp_path):
    """不要求精确等于(菜品 cost_cents 还含人工水电), 但配方算出来的食材成本
    必须 ≤ 菜品成本 —— 否则说明用量或单价写错了数量级。"""
    conn = connect(str(tmp_path / "t.db"))
    seed_world(conn, store_count=10)
    rows = conn.execute(
        "SELECT d.name, d.cost_cents, "
        "       SUM(r.qty_milli * i.unit_price_cents) / 1000 AS ing_cost "
        "  FROM dish d JOIN recipe r ON r.dish_id = d.id "
        "  JOIN ingredient i ON i.id = r.ingredient_id GROUP BY d.id"
    ).fetchall()
    assert rows
    for r in rows:
        assert 0 < r["ing_cost"] <= r["cost_cents"], (
            f'{r["name"]}: 配方食材成本 {r["ing_cost"]} 分 超出菜品成本 {r["cost_cents"]} 分'
        )


def test_供应链种子幂等(tmp_path):
    conn = connect(str(tmp_path / "t.db"))
    seed_world(conn, store_count=10)
    n1 = conn.execute("SELECT COUNT(*) c FROM recipe").fetchone()["c"]
    seed_world(conn, store_count=10)
    assert conn.execute("SELECT COUNT(*) c FROM recipe").fetchone()["c"] == n1
    assert conn.execute("SELECT COUNT(*) c FROM ingredient").fetchone()["c"] == 13


def test_配方引用不存在的菜就报错():
    """禁降级: 种子写错不能静默跳过, 否则少一道菜的配方没人会发现。"""
    import mock_platform.world.seed as seed_mod
    conn = connect(":memory:")
    seed_world(conn, store_count=1)
    orig = seed_mod._RECIPES
    seed_mod._RECIPES = {**orig, "不存在的菜": [("大米", 1)]}
    try:
        with pytest.raises(ValueError, match="不存在的菜"):
            seed_mod.seed_supply_chain(conn)
    finally:
        seed_mod._RECIPES = orig
