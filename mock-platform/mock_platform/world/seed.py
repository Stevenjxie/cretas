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
