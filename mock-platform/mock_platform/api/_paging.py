"""基于全局单调 seq 的游标分页。seq 单调保证不重不漏。"""
from __future__ import annotations

MAX_LIMIT = 200


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
            "SELECT d.name, oi.qty, oi.price_cents, oi.amount_cents "
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
                {"dishName": i["name"], "qty": i["qty"],
                 "price": i["price_cents"], "amount": i["amount_cents"]}
                for i in items
            ],
            "payments": [
                {"method": p["method"], "amount": p["amount_cents"]} for p in payments
            ],
        })
    next_cursor = rows[-1]["seq"] if rows else since_seq
    return orders, int(next_cursor), has_more
