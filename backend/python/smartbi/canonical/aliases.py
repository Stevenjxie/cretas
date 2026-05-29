"""Canonical 字段别名表. 单一 source of truth.

数据织网 A v1.4 §3.0 Day 1 任务: 把 _ALIAS_TO_ATTR 从 scripts/backfill_silver.py
搬到这里, 让 capability/* 模块不依赖 scripts/.

v1.0 (2026-04-25): 初始搬家 + Day 0 audit 决策 (option 2) 加 product_summary 字段:
- product_name / product_code / category / revenue / qty_sold / unit_price
- product_unit / refund_qty / refund_amount / service_fee / tax

v2.0 (2026-04-26): B-stage fields added (review/payment/SVC/inventory/period/
dispatch/voucher) — 15 new canonical, 32 new alias entries. 解锁 14 deferred
templates (reviews_sentiment_summary / payment_method_mix /
stored_value_card_consumption / groupon_channel_breakdown /
kitchen_dispatch_heatmap / member_deep_analytics 等).
"""
from __future__ import annotations
from typing import Dict


# 中文/英文别名 → canonical 字段 (snake_case English)
ALIAS_TO_ATTR: Dict[str, str] = {
    # ====== 现有 (来自 backfill_silver.py 原 _ALIAS_TO_ATTR) ======

    # store
    "store_name": "store_name",
    "门店": "store_name",
    "门店名称": "store_name",
    "店铺": "store_name",
    "shop_name": "store_name",

    # bill no — qhj uses 账单号; other customers use 订单号 / 单号
    "source_bill_no": "source_bill_no",
    "bill_no": "source_bill_no",
    "order_no": "source_bill_no",
    "订单号": "source_bill_no",
    "单号": "source_bill_no",
    "账单号": "source_bill_no",
    "结账号": "source_bill_no",
    # NOTE: 外部单号 (external platform order id, e.g. 美团/饿了么 delivery id) is
    # DELIBERATELY *not* mapped to source_bill_no. It only exists for takeout
    # (外卖) bills and is EMPTY for ~53% of dine-in (堂食) bills. Because
    # _build_canonical_row iterates row_data in column order and 外部单号
    # appears at column ~229 (after 账单号 at column 6), mapping it here caused
    # the empty value to overwrite the real 账单号 → bill_no became empty →
    # the whole dine-in row was dropped at the _REQUIRED_ATTRS check. This
    # silently lost ~30% of qhj 2025 revenue (the dine-in half). 账单号 is the
    # canonical, always-present bill key — confirmed 0 rows have empty 账单号 +
    # filled 外部单号 across the full 200k-row 2025 dataset. (2026-05-30)

    # date
    "date": "date",
    "日期": "date",
    "营业日期": "date",
    "transaction_date": "date",
    "交易日期": "date",
    "order_date": "date",
    "开单时间": "date",  # 二维火 详细日报表 — timestamp string e.g. "2025-11-14 18:31:48"
    "结账时间": "date",  # 二维火 alternative

    # staff
    "staff_name": "staff_name",
    "收银员": "staff_name",
    "服务员": "staff_name",
    "销售员": "staff_name",

    # bill-level amounts
    "gross_amount": "gross_amount",
    "应收金额": "gross_amount",
    "营业额": "gross_amount",
    "原价": "gross_amount",
    "商品折前金额": "gross_amount",
    "折前金额": "gross_amount",  # v1.0 加 (RES_GML_001)

    "discount_amount": "discount_amount",
    "优惠金额": "discount_amount",
    "折扣金额": "discount_amount",
    "折扣额": "discount_amount",
    "代金券优惠": "discount_amount",
    "分摊优惠": "discount_amount",  # v1.0 加 (xmx)

    "net_amount": "net_amount",
    "实收金额": "net_amount",
    "实收额": "net_amount",
    "商品折后金额": "net_amount",
    "净额": "net_amount",
    "折后金额": "net_amount",  # v1.0 加 (RES_GML_001 / xmx)

    "actual_receive": "actual_receive",
    "收款金额": "actual_receive",  # noqa: F601 — see TODO at line ~205 (collision with payment_amount; payment_amount wins)
    "实收": "actual_receive",

    # counts
    "customer_count": "customer_count",
    "人数": "customer_count",
    "就餐人数": "customer_count",
    "客流量": "customer_count",

    # metadata
    "table_no": "table_no",
    "桌号": "table_no",
    "桌位": "table_no",

    "order_type": "order_type",
    "订单类型": "order_type",

    # meal_period (Task C4 — QHJ revenue report) — populates fact_pos_transaction.meal_period
    "meal_period": "meal_period",
    "班次": "meal_period",
    "市段": "meal_period",
    "午晚市": "meal_period",

    "channel_origin": "channel_origin",
    "来源": "channel_origin",
    "订单来源": "channel_origin",

    # combo string (qhj 商品信息 是 bill-level blob)
    "combo_string": "combo_string",
    "菜品明细": "combo_string",
    "商品": "combo_string",
    "商品信息": "combo_string",
    "订单明细": "combo_string",

    # ====== v1.0 新增 product_summary 维度 (Day 0 audit option 2) ======

    # 产品名 / 编码 / 分类 (单行 = 单产品 = product_summary shape)
    "product_name": "product_name",
    "商品名称": "product_name",
    "菜品名称": "product_name",
    "菜品名": "product_name",
    "商品名": "product_name",

    "product_code": "product_code",
    "商品编码": "product_code",
    "菜品编号": "product_code",
    "sku_code": "product_code",

    "category": "category",
    "商品分类": "category",
    "菜品分类": "category",
    "分类": "category",
    "商品类型": "category",  # 可能是更细的 "餐饮商品"/"打包类", 但归 category

    # 销售额 (product_summary shape 的金额, 行级 NOT 账单级)
    "revenue": "revenue",
    "销售金额": "revenue",
    "营业收入": "revenue",
    "对应收入": "revenue",

    # 销量 / 单价 / 单位
    "qty_sold": "qty_sold",
    "数量": "qty_sold",
    "销量": "qty_sold",
    "单卖数量": "qty_sold",
    "单卖数量(不含套餐子商品)": "qty_sold",  # xmx 真实列名

    "unit_price": "unit_price",
    "销售单价": "unit_price",
    "单价": "unit_price",
    "销售价": "unit_price",

    "product_unit": "product_unit",
    "单位": "product_unit",
    "unit": "product_unit",

    # 退货 (product_summary 含)
    "refund_qty": "refund_qty",
    "退货数量": "refund_qty",
    "退货数量(不含套餐子商品)": "refund_qty",  # xmx 真实
    "退货量": "refund_qty",

    "refund_amount": "refund_amount",
    "实退金额": "refund_amount",
    "退款金额": "refund_amount",

    # POS bill 扩展 (RES_GML_001 实际有这些)
    "service_fee": "service_fee",
    "服务费": "service_fee",

    "tax": "tax",
    "税费": "tax",

    "tip": "tip",
    "小费": "tip",

    "invoice_amount": "invoice_amount",
    "开发票额": "invoice_amount",

    # ====== v2.0 新增 B-stage 字段 (Apr 26 2026) ======

    # review shape (B v2.0) — reviews_sentiment_summary
    "review_text": "review_text",
    "评论内容": "review_text",
    "评论": "review_text",
    "评价内容": "review_text",
    "comment": "review_text",
    "review": "review_text",

    "rating": "rating",
    "评分": "rating",
    "星级": "rating",
    "打分": "rating",
    "评价星级": "rating",
    "score": "rating",

    "review_date": "review_date",
    "评论时间": "review_date",
    "评论日期": "review_date",
    "comment_date": "review_date",

    # payment shape (B v2.0) — payment_method_mix
    # NOTE: payment_amount 是单笔支付级 (一张账单可拆 N 笔), 与 actual_receive (账单级实收) 区别
    "payment_channel": "payment_channel",
    "支付方式": "payment_channel",
    "付款方式": "payment_channel",
    "收款渠道": "payment_channel",
    "payment_method": "payment_channel",
    "channel": "payment_channel",

    "payment_amount": "payment_amount",
    "支付金额": "payment_amount",
    "付款金额": "payment_amount",
    "收款金额": "payment_amount",  # noqa: F601 — TODO: domain reviewer to disambiguate from line ~78 actual_receive mapping

    # SVC (储值卡) shape (B v2.0) — stored_value_card_consumption
    "card_no": "card_no",
    "卡号": "card_no",
    "会员卡号": "card_no",
    "card_number": "card_no",

    "card_balance": "card_balance",
    "卡余额": "card_balance",
    "余额": "card_balance",
    "储值余额": "card_balance",

    "card_recharge": "card_recharge",
    "储值金额": "card_recharge",
    "充值金额": "card_recharge",
    "储值": "card_recharge",

    # inventory shape (B v2.0)
    "inventory_item": "inventory_item",
    "库存物品": "inventory_item",
    "物料": "inventory_item",
    "物料名称": "inventory_item",
    "sku": "inventory_item",

    "stock_qty": "stock_qty",
    "库存数量": "stock_qty",
    "库存量": "stock_qty",
    "当前库存": "stock_qty",
    "stock": "stock_qty",

    # period (B v2.0) — product_summary 报表的时间窗推断
    "period": "period",
    "时间段": "period",
    "期间": "period",
    "报表期间": "period",

    # 厨房/出餐 shape (B v2.0) — kitchen_dispatch_heatmap
    "dispatch_time": "dispatch_time",
    "出餐时间": "dispatch_time",
    "传菜时间": "dispatch_time",
    "备餐时间": "dispatch_time",

    "kitchen_station": "kitchen_station",
    "出餐档口": "kitchen_station",
    "厨房工位": "kitchen_station",
    "备餐位": "kitchen_station",

    # voucher (B v2.0) — groupon_channel_breakdown 优惠券补充
    # NOTE: voucher_amount 是券面额 (单券 face value), 与 discount_amount (账单 sum 折扣) 区别
    "voucher_code": "voucher_code",
    "券号": "voucher_code",
    "代金券号": "voucher_code",
    "优惠券号": "voucher_code",

    "voucher_amount": "voucher_amount",
    "券面额": "voucher_amount",
    "代金券面值": "voucher_amount",
    "优惠券面值": "voucher_amount",
}


def to_canonical(raw_column: str) -> str | None:
    """中文/英文列名 → canonical. 无映射返 None."""
    return ALIAS_TO_ATTR.get(raw_column)
