"""WS2 Task 3 (#5) — AI 智能洞察 文本不得含方括号基准标记.

客户反馈: `[净/实收]` / `[毛/应收]` / `[毛]` / `[按营业额]` / `[按金额]` 这类
方括号标记看起来像 JSON / 不可读. Root-fix 在 Python 渲染层 (NOT 前端 strip):
- factbook.py 的 _render_finance / _render_sales 不再拼这些方括号后缀.
- orchestrator.py SYSTEM_PROMPT 不再要求金额紧跟 [毛]/[净]、百分比紧跟 [按营业额].

grounding reconciler (fact_reconciler) 按 指标名+数字 匹配, 不依赖这些方括号,
所以移除安全.

These tests call the pure string-rendering paths (FactBook.to_prompt_text, which
drives _render_finance + _render_sales) with synthetic facts — no DB needed.
"""
import re

from smartbi.agent.factbook import FactBook
from smartbi.agent.orchestrator import SYSTEM_PROMPT

# Matches the bracket basis-tags the customer complained about:
#   [毛] [净] [毛/应收] [净/实收] [按营业额] [按金额] [按订单数] ...
_BRACKET_TAG = re.compile(r"\[(毛|净|毛/应收|净/实收|按[^\]]+)\]")


def _full_finance():
    return {
        "start_date": "2025-01-01", "end_date": "2025-12-31",
        "total_revenue": 20640000.0, "bill_count": 141000,
        "avg_bill_value": 146.4, "store_count": 8, "day_count": 365,
        "top_stores": [
            {"store_id": 1, "store_name": "青花椒大融城店", "revenue": 3500000.0,
             "bill_count": 24000},
            {"store_id": 2, "store_name": "青花椒人民广场店", "revenue": 2800000.0,
             "bill_count": 19000},
        ],
    }


def _full_sales():
    return {
        "top_products": [
            {"product_name": "藤椒鱼", "revenue": 1200000.0, "qty_sold": 30000},
            {"product_name": "黄油鸡", "revenue": 900000.0, "qty_sold": 18000},
        ],
        "channels": [
            {"channel_name": "微信", "amount": 12000000.0, "share_pct": 58.1},
            {"channel_name": "美团", "amount": 5000000.0, "share_pct": 24.2},
        ],
        "discounts": [
            {"discount_name": "满减券", "amount": 800000.0},
        ],
    }


class TestFactbookNoBrackets:
    def test_finance_render_has_no_bracket_tags(self):
        fb = FactBook(finance=_full_finance())
        txt = fb.to_prompt_text()
        m = _BRACKET_TAG.search(txt)
        assert not m, f"finance render still has bracket basis-tag: {m.group(0)!r}\n{txt}"

    def test_sales_render_has_no_bracket_tags(self):
        fb = FactBook(sales=_full_sales())
        txt = fb.to_prompt_text()
        m = _BRACKET_TAG.search(txt)
        assert not m, f"sales render still has bracket basis-tag: {m.group(0)!r}\n{txt}"

    def test_combined_render_has_no_bracket_tags(self):
        fb = FactBook(finance=_full_finance(), sales=_full_sales(),
                      period="2025-01-01~2025-12-31")
        txt = fb.to_prompt_text()
        m = _BRACKET_TAG.search(txt)
        assert not m, f"combined render still has bracket basis-tag: {m.group(0)!r}\n{txt}"

    def test_render_still_contains_the_numbers(self):
        # Sanity: removing tags must NOT drop the actual figures the
        # reconciler matches on (指标名 + 数字).
        fb = FactBook(finance=_full_finance(), sales=_full_sales())
        txt = fb.to_prompt_text()
        assert "总营业额" in txt
        assert "20,640,000.00" in txt
        assert "客单价" in txt
        assert "青花椒大融城店" in txt
        assert "藤椒鱼" in txt
        assert "渠道占比" in txt


class TestSystemPromptNoBrackets:
    def test_system_prompt_has_no_bracket_tags(self):
        m = _BRACKET_TAG.search(SYSTEM_PROMPT)
        assert not m, f"SYSTEM_PROMPT still has bracket basis-tag: {m.group(0)!r}"

    def test_system_prompt_forbids_bracket_tags(self):
        # The prompt should explicitly tell the model NOT to emit bracket tags.
        assert "方括号" in SYSTEM_PROMPT
