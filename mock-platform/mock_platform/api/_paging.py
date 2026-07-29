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
        "status": "COMPLETED",
    }


def _wastage_row(r):
    return {
        "docNo": r["doc_no"], "shopCode": r["shop_code"], "bizDate": r["biz_date"],
        "ingredientName": r["ing_name"], "ingredientCategory": r["ing_category"],
        "unit": r["unit"], "wastageType": r["wastage_type"],
        "qty": r["qty_milli"], "cost": r["cost_cents"],
    }


def _stocktaking_row(r):
    return {
        "docNo": r["doc_no"], "shopCode": r["shop_code"], "bizDate": r["biz_date"],
        "ingredientName": r["ing_name"], "ingredientCategory": r["ing_category"],
        "unit": r["unit"], "systemQty": r["system_qty_milli"],
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
