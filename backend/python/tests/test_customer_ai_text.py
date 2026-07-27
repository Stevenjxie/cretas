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


def test_rewrites_engineering_jargon_as_store_manager_language():
    raw = "\n".join([
        "请显式指定门店和时间槽位，再执行 QueryPlan。",
        "以下是确定性多维分析和结构化数据。",
        "尚缺但可补充的维度会按可比基线核对，相关≠因果。",
        "叙述模型暂时不可用，但本月青花椒南方百联店营业额为 123,456 元。",
    ])

    cleaned = sanitize_customer_ai_text(raw)

    for jargon in (
        "显式",
        "槽位",
        "QueryPlan",
        "确定性",
        "结构化",
        "维度",
        "可比基线",
        "相关≠因果",
        "叙述模型",
    ):
        assert jargon not in cleaned

    assert "直接告诉我门店和时间条件" in cleaned
    assert "按系统真实数据做的综合分析" in cleaned
    assert "系统里已经整理好的数据" in cleaned
    assert "还可以补充的方面" in cleaned
    assert "一起变化不代表就是原因" in cleaned
    assert "智能分析暂时有点忙" in cleaned
    assert "青花椒南方百联店营业额为 123,456 元" in cleaned


def test_plain_language_rewrite_preserves_markdown_and_business_numbers():
    raw = (
        "### 确定性计算发现\n"
        "- **青花椒大融城店**：本月营业额 88,800 元。\n"
        "- 标记 SIMULATED 的维度只用于 Demo 租户展示。"
    )

    cleaned = sanitize_customer_ai_text(raw)

    assert "### 从系统数据里看到的重点" in cleaned
    assert "- **青花椒大融城店**：本月营业额 88,800 元。" in cleaned
    assert "演示数据" in cleaned
    assert "演示账号" in cleaned
    assert "SIMULATED" not in cleaned
    assert "Demo" not in cleaned
