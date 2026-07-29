"""10 家门店 + 菜品种子。幂等：重复调用不翻倍。"""
from __future__ import annotations

import sqlite3

# (code, name, format, traffic_factor)
_STORES = [
    ("MK01", "模拟·打浦桥日月光店", "mall", 1.60),
    ("MK02", "模拟·徐汇美罗城店", "mall", 1.35),
    ("MK03", "模拟·静安嘉里中心店", "flagship", 1.50),
    ("MK04", "模拟·陆家嘴正大店", "flagship", 1.40),
    ("MK05", "模拟·长宁龙之梦店", "mall", 1.10),
    ("MK06", "模拟·杨浦五角场店", "mall", 1.00),
    ("MK07", "模拟·普陀真如社区店", "community", 0.72),
    ("MK08", "模拟·闵行莘庄社区店", "community", 0.68),
    ("MK09", "模拟·宝山大场社区店", "community", 0.60),
    ("MK10", "模拟·浦东金桥社区店", "community", 0.65),
]

# (name, category, price_cents, cost_cents, groupon_eligible)
_DISHES = [
    ("藤椒鸡", "热菜", 5800, 2100, 1),
    ("水煮牛肉", "热菜", 6800, 2900, 1),
    ("干锅花菜", "热菜", 3800, 1200, 1),
    ("鲈鱼", "水产", 8800, 4200, 1),
    ("罗氏虾", "水产", 12800, 6800, 0),
    ("娃娃菜", "素菜", 2200, 600, 0),
    ("米饭", "主食", 300, 80, 0),
    ("酸梅汤", "饮品", 1200, 300, 0),
    ("红糖糍粑", "甜品", 2600, 700, 1),
    ("凉拌木耳", "凉菜", 1800, 500, 0),
]


# ── 后厨供应链种子 (2026-07-29) ─────────────────────────────────────
# (name, category, unit, unit_price_cents, shelf_life_days, storage_type)
# 单价是「每单位」的价, 单位见 unit 列。鸡腿肉 2400 分/kg = 24 元/kg, 合理量级。
_INGREDIENTS = [
    ("鸡腿肉",   "肉类", "kg", 2400, 3,   "冷藏"),
    ("牛肉",     "肉类", "kg", 6800, 3,   "冷藏"),
    ("鲈鱼",     "水产", "kg", 3600, 2,   "冷藏"),
    ("罗氏虾",   "水产", "kg", 9800, 2,   "冷藏"),
    ("花菜",     "蔬菜", "kg", 800,  5,   "冷藏"),
    ("娃娃菜",   "蔬菜", "kg", 600,  5,   "冷藏"),
    ("黑木耳",   "干货", "kg", 4200, 365, "常温"),
    ("大米",     "米面", "kg", 620,  180, "常温"),
    ("糯米粉",   "米面", "kg", 900,  180, "常温"),
    ("红糖",     "调料", "kg", 1100, 365, "常温"),
    ("乌梅",     "干货", "kg", 5200, 365, "常温"),
    ("藤椒",     "调料", "kg", 8600, 180, "常温"),
    ("菜籽油",   "调料", "L",  1500, 365, "常温"),
]

# dish_name -> [(ingredient_name, 每份用量×1000)]
# 用量按"一份菜实际吃掉多少"估, 与菜品 cost_cents 大致对得上 —— 不是精确成本核算,
# 但也不是随手编的数: 比如水煮牛肉每份 180g 牛肉 = 0.18kg × 68 元/kg ≈ 12.2 元,
# 加配菜与油料后落在菜品成本 29 元的量级内。
_RECIPES = {
    "藤椒鸡":     [("鸡腿肉", 220), ("藤椒", 8),   ("菜籽油", 25)],
    "水煮牛肉":   [("牛肉", 180),   ("娃娃菜", 80), ("菜籽油", 35), ("藤椒", 6)],
    "干锅花菜":   [("花菜", 260),   ("菜籽油", 20)],
    "鲈鱼":       [("鲈鱼", 550),   ("菜籽油", 15)],
    "罗氏虾":     [("罗氏虾", 400), ("菜籽油", 10)],
    "娃娃菜":     [("娃娃菜", 240)],
    "米饭":       [("大米", 110)],
    "酸梅汤":     [("乌梅", 18),    ("红糖", 12)],
    "红糖糍粑":   [("糯米粉", 90),  ("红糖", 30), ("菜籽油", 12)],
    "凉拌木耳":   [("黑木耳", 22),  ("菜籽油", 8)],
}


def seed_supply_chain(conn: sqlite3.Connection) -> None:
    """食材 + 配方。幂等。

    配方是领料/损耗能"从真实销量推出来"的前提 —— 没有它就只能凭空造供应链数字,
    那和造假没区别。
    """
    for name, cat, unit, price, shelf, storage in _INGREDIENTS:
        conn.execute(
            "INSERT INTO ingredient(name, category, unit, unit_price_cents, "
            "shelf_life_days, storage_type) VALUES (?,?,?,?,?,?) "
            "ON CONFLICT(name) DO UPDATE SET category=excluded.category, "
            "unit=excluded.unit, unit_price_cents=excluded.unit_price_cents, "
            "shelf_life_days=excluded.shelf_life_days, "
            "storage_type=excluded.storage_type",
            (name, cat, unit, price, shelf, storage),
        )
    dish_ids = {r["name"]: r["id"] for r in conn.execute("SELECT id, name FROM dish")}
    ing_ids = {r["name"]: r["id"] for r in conn.execute("SELECT id, name FROM ingredient")}
    for dish_name, lines in _RECIPES.items():
        did = dish_ids.get(dish_name)
        if did is None:
            # 禁降级: 配方指向一道不存在的菜 = 种子写错了, 不静默跳过。
            raise ValueError(f"配方引用了不存在的菜品: {dish_name!r}")
        for ing_name, qty_milli in lines:
            iid = ing_ids.get(ing_name)
            if iid is None:
                raise ValueError(f"配方引用了不存在的食材: {ing_name!r}")
            conn.execute(
                "INSERT INTO recipe(dish_id, ingredient_id, qty_milli) VALUES (?,?,?) "
                "ON CONFLICT(dish_id, ingredient_id) DO UPDATE SET "
                "qty_milli=excluded.qty_milli",
                (did, iid, qty_milli),
            )


def seed_world(conn: sqlite3.Connection, store_count: int) -> None:
    if store_count > len(_STORES):
        raise ValueError(f"最多支持 {len(_STORES)} 家门店，请求了 {store_count}")
    for code, name, fmt, factor in _STORES[:store_count]:
        conn.execute(
            "INSERT INTO store(code, name, format, traffic_factor) VALUES (?,?,?,?) "
            "ON CONFLICT(code) DO UPDATE SET name=excluded.name, "
            "format=excluded.format, traffic_factor=excluded.traffic_factor",
            (code, name, fmt, factor),
        )
    for name, cat, price, cost, groupon in _DISHES:
        conn.execute(
            "INSERT INTO dish(name, category, price_cents, cost_cents, groupon_eligible) "
            "VALUES (?,?,?,?,?) ON CONFLICT(name) DO UPDATE SET "
            "category=excluded.category, price_cents=excluded.price_cents, "
            "cost_cents=excluded.cost_cents, groupon_eligible=excluded.groupon_eligible",
            (name, cat, price, cost, groupon),
        )
    # 菜种完了才能种配方(配方要按菜名查 id)。
    seed_supply_chain(conn)
