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

# (name, category, price_cents, cost_cents<已弃用>, groupon_eligible)
# ⚠️ 第 4 列**不再被使用** —— 全成本由 `_full_cost_cents()` 从配方推导。
#    留着只为不改动元组形状; 想知道真值看 `dish` 表或 /menu/dish/list。
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


# ── 折扣活动种子 (2026-08-09) ───────────────────────────────────────
# (code, name, kind, channel, face_value_cents, actual_price_cents)
#
# ⛔ 每个活动**绑死一个渠道**: 团购券只在团购渠道核销, 外卖满减只在外卖发放。
#    跨渠道乱投放会让「哪个渠道的让利来自哪个活动」这个问题失去意义 ——
#    而那正是老板问「团购划不划算」时真正想知道的。
#
# ⚠️ face_value / actual_price 只对**预售型团购券**有意义(卖 88 元的 128 元套餐券)。
#    平台满减是即时立减, 没有票面价 —— 那两列留 0 表示「这个活动没有票面」,
#    不是「不知道」。
_DISCOUNT_CAMPAIGNS = [
    # 团购: 预售套餐券, 有票面价与实售价
    ("GP_DIANPING_2P", "大众点评双人餐券", "groupon_voucher", "groupon", 12800, 8800),
    ("GP_MEITUAN_SET", "美团团购套餐券", "groupon_voucher", "groupon", 9800, 6800),
    ("GP_DOUYIN_4P", "抖音四人聚餐券", "groupon_voucher", "groupon", 25800, 19900),
    # 外卖: 即时立减, 无票面
    ("TA_FULL_CUT", "外卖满50减8", "platform_promo", "takeaway", 0, 0),
    ("TA_NEW_USER", "新客首单立减", "platform_promo", "takeaway", 0, 0),
    ("TA_SUBSIDY", "平台补贴红包", "platform_promo", "takeaway", 0, 0),
]

# ── 后厨供应链种子 (2026-07-29) ─────────────────────────────────────
# (name, category, unit, unit_price_cents, shelf_life_days, storage_type)
# 单价是「每单位」的价, 单位见 unit 列。鸡腿肉 2400 分/kg = 24 元/kg, 合理量级。
_INGREDIENTS = [
    # ── 主料 ──
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
    # ── 配菜与调料 (2026-08-01 补) ──────────────────────────────────
    # 原配方每道菜只列 2-4 种主料, 没有配菜也没有调料 —— 算出来的食材成本率
    # 只有 19%(毛利率 81%), 在餐饮里不可能。真实厨房这些一样要买、要领、要损耗,
    # 缺了它们「食材成本」这个词就名不副实。
    ("豆芽",     "蔬菜", "kg", 600,  3,   "冷藏"),
    ("土豆",     "蔬菜", "kg", 500,  30,  "常温"),
    ("香菜",     "蔬菜", "kg", 2000, 3,   "冷藏"),
    ("大蒜",     "蔬菜", "kg", 1200, 60,  "常温"),
    ("生姜",     "蔬菜", "kg", 1400, 30,  "常温"),
    ("干辣椒",   "调料", "kg", 4500, 365, "常温"),
    ("花椒",     "调料", "kg", 7800, 365, "常温"),
    ("生抽",     "调料", "L",  1200, 540, "常温"),
    ("料酒",     "调料", "L",  800,  540, "常温"),
    ("食盐",     "调料", "kg", 400,  730, "常温"),
    ("白糖",     "调料", "kg", 800,  540, "常温"),
    ("淀粉",     "调料", "kg", 1000, 365, "常温"),
]

# dish_name -> [(ingredient_name, 每份用量×1000)]
#
# 🔴 2026-08-01 重做。原配方每道菜只列 2-4 种**主料**, 用量也普遍偏小一半以上,
# 算出来的食材成本率只有 19%(毛利率 81%) —— 餐饮里不可能。原注释自称「与菜品
# cost_cents 大致对得上」同样是错的: 水煮牛肉原配方算出 ¥13.76 而 cost_cents 是 ¥29。
#
# 现在按「一份菜真实吃掉多少」重估主料用量并补齐配菜/调料/油。按 MOCK_REST 的真实
# 销量加权, 食材成本率 **32.3%** / 毛利率 **67.7%**, 落在休闲餐饮正常区间(28-38%)。
# 逐菜看荤菜水产 33-41%、素菜甜品饮品 10-27%, 各自符合品类特征。
#
# ⚠️ `dish.cost_cents` 是**全成本**(还含人工水电, 见 test_seed 的断言注释), 与这里
# 的食材成本不是一回事 —— 下游 `agg_restaurant_product_cost.food_cost` 要的是**食材**,
# 取的是本表逐行 qty×单价 的和, 不是 cost_cents。
_RECIPES = {
    "藤椒鸡":     [("鸡腿肉", 600), ("藤椒", 18), ("菜籽油", 70), ("豆芽", 120),
                   ("干辣椒", 12), ("大蒜", 25), ("生姜", 18), ("料酒", 25),
                   ("生抽", 15), ("食盐", 6), ("淀粉", 12)],
    "水煮牛肉":   [("牛肉", 320), ("娃娃菜", 150), ("豆芽", 120), ("菜籽油", 80),
                   ("藤椒", 12), ("干辣椒", 18), ("花椒", 8), ("大蒜", 25),
                   ("生姜", 15), ("料酒", 25), ("生抽", 18), ("淀粉", 15),
                   ("食盐", 6)],
    "干锅花菜":   [("花菜", 400), ("土豆", 120), ("菜籽油", 60), ("干辣椒", 12),
                   ("大蒜", 25), ("生姜", 12), ("生抽", 15), ("食盐", 5),
                   ("香菜", 15)],
    "鲈鱼":       [("鲈鱼", 850), ("菜籽油", 45), ("生姜", 30), ("大蒜", 20),
                   ("料酒", 35), ("生抽", 25), ("香菜", 20), ("食盐", 6)],
    "罗氏虾":     [("罗氏虾", 480), ("菜籽油", 40), ("大蒜", 40), ("生姜", 20),
                   ("干辣椒", 10), ("料酒", 30), ("生抽", 20), ("白糖", 10),
                   ("食盐", 5)],
    "娃娃菜":     [("娃娃菜", 400), ("菜籽油", 25), ("大蒜", 15), ("生抽", 12),
                   ("食盐", 4), ("淀粉", 8)],
    "米饭":       [("大米", 130), ("食盐", 1)],
    "酸梅汤":     [("乌梅", 45), ("红糖", 30), ("白糖", 15)],
    "红糖糍粑":   [("糯米粉", 150), ("红糖", 60), ("菜籽油", 45), ("白糖", 12)],
    "凉拌木耳":   [("黑木耳", 55), ("香菜", 20), ("大蒜", 18), ("生抽", 15),
                   ("菜籽油", 12), ("白糖", 6), ("食盐", 3)],
}



def food_cost_cents(dish_name: str, unit_price: dict) -> int:
    """一份菜的**食材**成本(分) = 配方逐行 用量/1000 × 单价。"""
    return round(sum(
        qty * unit_price[ing] / 1000 for ing, qty in _RECIPES[dish_name]
    ))


# 人工 + 水电 + 房租摊到每份菜的比例。餐饮里这块通常占营收 30-40%,
# 这里取 30% —— 它只影响模拟端自报的「全成本」, 我们这边算毛利用的是**食材**成本。
_OVERHEAD_RATE = 0.30


def _full_cost_cents(dish_name: str, price_cents: int, unit_price: dict) -> int:
    """菜品**全成本**(分) = 食材成本 + 人工水电分摊。

    🔴 由配方推导而不是写死。写死的后果 2026-08-01 实测过一次: 配方补完整之后
    食材成本追上来, 而写死的 cost_cents 还停在旧配方的量级 —— 米饭出现
    「食材成本 81 分 > 全成本 80 分」这种不可能的值, 人工水电变成负数。
    推导之后 `食材成本 ≤ 全成本` 结构性成立, 不会再破。
    """
    return food_cost_cents(dish_name, unit_price) + round(price_cents * _OVERHEAD_RATE)


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
    unit_price = {n: up for n, _c, _u, up, _s, _st in _INGREDIENTS}
    for name, cat, price, _legacy_cost, groupon in _DISHES:
        cost = _full_cost_cents(name, price, unit_price)
        conn.execute(
            "INSERT INTO dish(name, category, price_cents, cost_cents, groupon_eligible) "
            "VALUES (?,?,?,?,?) ON CONFLICT(name) DO UPDATE SET "
            "category=excluded.category, price_cents=excluded.price_cents, "
            "cost_cents=excluded.cost_cents, groupon_eligible=excluded.groupon_eligible",
            (name, cat, price, cost, groupon),
        )
    for code, name, kind, channel, face, actual in _DISCOUNT_CAMPAIGNS:
        conn.execute(
            "INSERT INTO discount_campaign(code, name, kind, channel, "
            "face_value_cents, actual_price_cents) VALUES (?,?,?,?,?,?) "
            "ON CONFLICT(code) DO UPDATE SET name=excluded.name, kind=excluded.kind, "
            "channel=excluded.channel, face_value_cents=excluded.face_value_cents, "
            "actual_price_cents=excluded.actual_price_cents",
            (code, name, kind, channel, face, actual),
        )
    # 菜种完了才能种配方(配方要按菜名查 id)。
    seed_supply_chain(conn)
