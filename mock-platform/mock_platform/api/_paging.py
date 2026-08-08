"""基于全局单调 seq 的游标分页。seq 单调保证不重不漏。"""
from __future__ import annotations

MAX_LIMIT = 200


# 后厨供应链三类单据的分页。三张表形状不同, 但游标语义与订单完全一致
# (全局单调 seq, 不重不漏), 所以共用一个按表配置的实现, 不各抄一份。
#
# 每项: (表名, 行 → 报文 dict 的映射函数)
def _requisition_row(r):
    return {
        "docNo": r["doc_no"], "shopCode": r["shop_code"], "bizDate": r["biz_date"],
        "ingredientName": r["ing_name"], "ingredientCategory": r["ing_category"],
        "unit": r["unit"], "qty": r["qty_milli"], "cost": r["cost_cents"],
        # 从库里取, **不硬编码** —— 硬编码的话「状态是平台真给的」这句话
        # 技术上成立但实际空心, 而下游 Gold 正是按它过滤的。
        "status": r["status"],
    }


def _wastage_row(r):
    return {
        "docNo": r["doc_no"], "shopCode": r["shop_code"], "bizDate": r["biz_date"],
        "ingredientName": r["ing_name"], "ingredientCategory": r["ing_category"],
        "unit": r["unit"], "wastageType": r["wastage_type"],
        "status": r["status"],
        "qty": r["qty_milli"], "cost": r["cost_cents"],
    }


def _stocktaking_row(r):
    return {
        "docNo": r["doc_no"], "shopCode": r["shop_code"], "bizDate": r["biz_date"],
        "ingredientName": r["ing_name"], "ingredientCategory": r["ing_category"],
        "unit": r["unit"], "status": r["status"],
        "systemQty": r["system_qty_milli"],
        "actualQty": r["actual_qty_milli"], "diffCost": r["diff_cost_cents"],
    }


OPS_KINDS = {
    "requisition": ("requisition", _requisition_row),
    "wastage": ("wastage", _wastage_row),
    "stocktaking": ("stocktaking", _stocktaking_row),
}


def page_ops(conn, kind: str, *, since_seq: int, limit: int):
    """按 seq 游标翻某一类供应链单据。kind 必须是 OPS_KINDS 的键。

    ⚠️ 表名来自 OPS_KINDS 这张本模块常量表, 不是调用方传进来的原始串 ——
    路由层已按白名单校验过 kind, 这里再取一次常量, 避免表名拼接成注入面。
    """
    table, to_payload = OPS_KINDS[kind]
    rows = conn.execute(
        f"SELECT t.*, s.code AS shop_code, i.name AS ing_name, "
        f"       i.category AS ing_category, i.unit AS unit "
        f"  FROM {table} t "
        f"  JOIN store s ON s.id = t.store_id "
        f"  JOIN ingredient i ON i.id = t.ingredient_id "
        f" WHERE t.seq > ? ORDER BY t.seq LIMIT ?",
        (since_seq, limit + 1),
    ).fetchall()
    has_more = len(rows) > limit
    rows = rows[:limit]
    next_cursor = rows[-1]["seq"] if rows else since_seq
    return [to_payload(r) for r in rows], int(next_cursor), has_more


# ── 菜单主数据 (2026-08-01) ─────────────────────────────────────────
# 菜品 / 食材 / 配方是**静态主数据**, 与订单、供应链单据不同: 它们没有 seq
# (不是流水), 也不随生成器每分钟推进。所以游标走各自的主键而不是 seq。
#
# 为什么要暴露它们: MOCK_REST 侧只有 POS 流水, 没有配方与食材单价, 于是
# `agg_restaurant_product_cost` 恒为 0 行 —— 2026-08-01 四部门审计实测, 财务
# 四个问题里三个答成「毛利前 0 名」。数据在这边一直都有(dish.cost_cents /
# ingredient.unit_price_cents / recipe 22 条), 只是没有接口能把它取走。
#
# ⚠️ **这两个 code 是对外契约, 一旦发布就不能改形态** (`mp_dish_001` /
#    `mp_ingr_001`)。下游拿它们当幂等键, 换一种拼法会让同一批菜出现两套行。

def dish_code(dish_id: int) -> str:
    return f"mp_dish_{int(dish_id):03d}"


def ingredient_code(ingredient_id: int) -> str:
    return f"mp_ingr_{int(ingredient_id):03d}"


def _dish_row(r):
    return {
        "dishCode": dish_code(r["id"]),
        "dishName": r["name"],
        "category": r["category"],
        "price": r["price_cents"],
        # 成本是这三个端点存在的**唯一理由** —— 不给它, 对端仍然算不出毛利。
        "cost": r["cost_cents"],
        "grouponEligible": bool(r["groupon_eligible"]),
    }


def _ingredient_row(r):
    return {
        "ingredientCode": ingredient_code(r["id"]),
        "ingredientName": r["name"],
        "category": r["category"],
        "unit": r["unit"],
        "unitPrice": r["unit_price_cents"],
        "shelfLifeDays": r["shelf_life_days"],
        "storageType": r["storage_type"],
    }


def _recipe_row(r):
    return {
        "dishCode": dish_code(r["dish_id"]),
        "ingredientCode": ingredient_code(r["ingredient_id"]),
        # 千分之一 kg / L, 与供应链单据的 qty 同口径(整数, 不用浮点)
        "qty": r["qty_milli"],
    }


# 每项: (表名, 游标列, 行 → 报文映射)
# recipe 是复合主键没有单列 id, 用 SQLite 隐式 rowid 作游标 —— 它在本表上
# 单调且稳定, 而 (dish_id, ingredient_id) 复合游标会让契约复杂一倍却无收益。
MENU_KINDS = {
    "dish": ("dish", "id", _dish_row),
    "ingredient": ("ingredient", "id", _ingredient_row),
    "recipe": ("recipe", "rowid", _recipe_row),
}


def page_menu(conn, kind: str, *, since_id: int, limit: int):
    """按主键游标翻菜单主数据。kind 必须是 MENU_KINDS 的键。

    与 page_ops 同样的理由: 表名与游标列都取自本模块常量表, 不接受调用方的原始串。
    """
    table, cursor_col, to_payload = MENU_KINDS[kind]
    rows = conn.execute(
        f"SELECT t.*, t.{cursor_col} AS _cursor FROM {table} t "
        f" WHERE t.{cursor_col} > ? ORDER BY t.{cursor_col} LIMIT ?",
        (since_id, limit + 1),
    ).fetchall()
    has_more = len(rows) > limit
    rows = rows[:limit]
    next_cursor = rows[-1]["_cursor"] if rows else since_id
    return [to_payload(r) for r in rows], int(next_cursor), has_more


def page_orders(conn, *, since_seq: int, limit: int):
    rows = conn.execute(
        'SELECT o.*, s.code AS shop_code FROM "order" o '
        "JOIN store s ON s.id = o.store_id "
        "WHERE o.seq > ? ORDER BY o.seq LIMIT ?",
        (since_seq, limit + 1),
    ).fetchall()
    has_more = len(rows) > limit
    rows = rows[:limit]
    orders = []
    for r in rows:
        items = conn.execute(
            # category 也一并给出: 真实平台(客如云/美团)的订单明细都带菜品分类,
            # 下游要靠它把菜品维度分组(热菜/凉菜/主食...)。不给的话对端只能拿到
            # 一个光秃秃的菜名, 菜品分析就只剩名字。
            "SELECT d.name, d.category, oi.qty, oi.price_cents, oi.amount_cents "
            "FROM order_item oi JOIN dish d ON d.id = oi.dish_id WHERE oi.order_id = ?",
            (r["id"],),
        ).fetchall()
        payments = conn.execute(
            "SELECT method, amount_cents FROM payment WHERE order_id = ?", (r["id"],)
        ).fetchall()
        orders.append({
            "orderNo": r["order_no"],
            "shopCode": r["shop_code"],
            "channel": r["channel"],
            "placedAt": r["placed_at"],
            "bizDate": r["biz_date"],
            "grossAmount": r["gross_cents"],
            "discountAmount": r["discount_cents"],
            "netAmount": r["net_cents"],
            # 渠道侧成本(平台抽佣/券核销费)。堂食恒为 0。
            "platformFee": r["platform_fee_cents"],
            "guestCount": r["guest_count"],
            "items": [
                {"dishName": i["name"], "dishCategory": i["category"],
                 "qty": i["qty"],
                 "price": i["price_cents"], "amount": i["amount_cents"]}
                for i in items
            ],
            "payments": [
                {"method": p["method"], "amount": p["amount_cents"]} for p in payments
            ],
        })
    next_cursor = rows[-1]["seq"] if rows else since_seq
    return orders, int(next_cursor), has_more
