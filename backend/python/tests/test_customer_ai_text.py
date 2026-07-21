from smartbi.gold.customer_text import (
    has_displayable_business_result,
    sanitize_customer_ai_text,
)


def test_removes_tool_intent_api_and_table_identifiers():
    raw = "\n".join([
        "通过调用 income_statement_query 工具获取利润表数据。",
        "内部意图 RESTAURANT_OPS_GROSS_MARGIN。",
        "来源 fact_pos_item 与 /api/smartbi/gold/finance-summary。",
        "Gold 数据由 POS 完成 materialize，再交给 LLM。",
        "当前整体毛利率为 70.1%。",
    ])

    cleaned = sanitize_customer_ai_text(raw)

    assert "income_statement_query" not in cleaned
    assert "RESTAURANT_OPS_" not in cleaned
    assert "fact_pos_item" not in cleaned
    assert "/api/" not in cleaned
    assert "Gold" not in cleaned
    assert "POS" not in cleaned
    assert "materialize" not in cleaned
    assert "LLM" not in cleaned
    assert "来源  与" not in cleaned
    assert "调用" not in cleaned
    assert "当前整体毛利率为 70.1%" in cleaned


def test_keeps_legitimate_english_business_name():
    cleaned = sanitize_customer_ai_text("Black Pepper Beef 本月毛利率为 62.5%。")
    assert "Black Pepper Beef" in cleaned


def test_internal_only_text_never_becomes_fake_completion():
    cleaned = sanitize_customer_ai_text(
        "通过调用 income_statement_query 工具获取数据表。"
    )

    assert cleaned == "没有获得可展示的业务结果，本次不生成结论。"
    assert "已完成" not in cleaned
    assert has_displayable_business_result(cleaned) is False
