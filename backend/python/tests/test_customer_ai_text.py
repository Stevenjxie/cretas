from smartbi.gold.customer_text import (
    has_displayable_business_result,
    sanitize_customer_ai_text,
)


# ── 2026-08-11: 空行是 markdown 的结构, 不是可有可无的留白 ────────────────
#
# 🔴 prod 实测(打真接口读渲染结果): 08-10 起陆续上线的 8 张 markdown 表格,
#    到用户手里**全都少了表头前的空行** ——
#
#        '**本月…菜品销量排行（卖得最好前 5）：**\n| # | 菜品 | 销量（份） | 营收 |'
#
#    resolver 明明拼的是 `[标题, ""] + _markdown_table(...)`(两个空行), Python
#    响应里 `answer_text` 的连续空行数却是 **0**。根因在本模块: 它逐行清洗后用
#    `if line and ...` 把**空行整行丢掉**, 再用单个 `\n` 重拼。
#
#    后果不报错 —— markdown-it 需要空行才把表格当成一个块, 少了它整张表被并进
#    上一段渲染成一坨。`_markdown_table` 的 docstring 第一条警告的就是这个,
#    但那句警告只管到了拼装点, 管不到下游这一层。
#
# 判据: **清洗文本的函数不能顺手改文本的结构。** 它要删的是「实现细节」,
#       空行不是实现细节。
def test_blank_lines_are_preserved_because_markdown_needs_them():
    """🔴 承重: 空行必须留下, 否则每一张表都会被并进上一段。"""
    raw = "\n".join([
        "**本月菜品销量排行：**",
        "",
        "| # | 菜品 | 销量 |",
        "| --- | --- | ---: |",
        "| 1 | 米饭 | 1,345 |",
    ])

    cleaned = sanitize_customer_ai_text(raw)

    assert "\n\n| # |" in cleaned, (
        "表头前的空行被吃掉了 —— markdown-it 会把整张表并进上一段当普通文字")
    assert cleaned.splitlines()[1] == "", "第二行应当仍是空行"


def test_blank_line_between_paragraphs_survives():
    """段落之间的空行同理 —— 少了它两段会连成一段。"""
    cleaned = sanitize_customer_ai_text("第一段。\n\n第二段。")
    assert cleaned == "第一段。\n\n第二段。"


def test_dropping_a_tech_line_still_works_next_to_blank_lines():
    """⛔ 阴性对照: 保留空行不能把「删实现细节」这件正事一起放过。

    没有这条, 把清洗逻辑整个删掉也能让上面两条通过。
    """
    raw = "\n".join([
        "当前整体毛利率为 70.1%。",
        "",
        "内部意图 RESTAURANT_OPS_GROSS_MARGIN。",
        "",
        "| # | 菜品 |",
    ])

    cleaned = sanitize_customer_ai_text(raw)

    assert "RESTAURANT_OPS_" not in cleaned
    assert "70.1%" in cleaned
    assert "| # | 菜品 |" in cleaned


def test_leading_and_trailing_blank_lines_are_still_trimmed():
    """首尾空行没有结构意义, 照旧去掉 —— 保留空行不等于不 strip。"""
    cleaned = sanitize_customer_ai_text("\n\n有效内容。\n\n")
    assert cleaned == "有效内容。"


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
