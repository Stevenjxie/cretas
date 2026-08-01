"""Unit tests for `_clean_display_name` + bracketed-name normalization in
channel/discount/order-type gold queries.

qhj's POS export wraps payment-channel / coupon / discount names in brackets
(e.g. [微信], [饿了么], [美团套餐券]) — an export artifact that surfaced in the
AI 洞察 / dashboard 渠道占比 / sales 渠道明细 looking like markup. These tests
pin the conservative strip-only-when-fully-wrapped behavior + verify the gold
query result names are cleaned.

Pure tests — fake asyncpg pool, no Postgres.

Run with:
    cd backend/python
    python -m pytest smartbi/gold/tests/test_clean_display_name.py -v
"""
from __future__ import annotations

import asyncio
from datetime import date
from decimal import Decimal

import pytest

from smartbi.gold.queries import (
    _clean_display_name,
    channel_breakdown,
    discount_breakdown,
    order_type_mix,
)


# ---------------------------------------------------------------------------
# Fake pool / connection helpers (mirror test_gold_reads_restaurant.py)
# ---------------------------------------------------------------------------

class _FakeRecord(dict):
    def keys(self):
        return super().keys()


class _FakeConn:
    def __init__(self, rows):
        self._rows = rows
        self.last_sql = None

    async def __aenter__(self):
        return self

    async def __aexit__(self, *a):
        return False

    async def execute(self, *a, **k):
        return None

    async def fetch(self, sql, *a, **k):
        self.last_sql = sql
        return self._rows

    async def fetchrow(self, sql, *a, **k):
        self.last_sql = sql
        return self._rows[0] if self._rows else None


class _FakePool:
    def __init__(self, rows):
        self._rows = [_FakeRecord(r) for r in rows]
        self._conn = _FakeConn(self._rows)

    def acquire(self):
        return self._conn

    @property
    def conn(self):
        return self._conn


# ---------------------------------------------------------------------------
# Pure helper tests
# ---------------------------------------------------------------------------

class TestCleanDisplayName:
    """Pin the conservative strip-only-when-fully-wrapped behavior."""

    def test_strip_square_brackets(self):
        assert _clean_display_name("[微信]") == "微信"

    def test_strip_square_brackets_multichar(self):
        assert _clean_display_name("[美团套餐券]") == "美团套餐券"

    def test_strip_eleme(self):
        assert _clean_display_name("[饿了么]") == "饿了么"

    def test_strip_meituan(self):
        assert _clean_display_name("[美团]") == "美团"

    def test_strip_fullwidth_paren(self):
        assert _clean_display_name("（现金）") == "现金"

    def test_strip_fullwidth_square(self):
        assert _clean_display_name("【支付宝】") == "支付宝"

    def test_strip_halfwidth_paren(self):
        assert _clean_display_name("(银行卡)") == "银行卡"

    # --- names that must be LEFT UNCHANGED (no over-stripping) ---

    def test_plain_name_unchanged(self):
        assert _clean_display_name("现金") == "现金"

    def test_name_with_no_brackets_unchanged(self):
        assert _clean_display_name("招行买单") == "招行买单"

    def test_bankcard_unchanged(self):
        assert _clean_display_name("银行卡") == "银行卡"

    def test_mid_string_bracket_unchanged(self):
        # Not fully wrapped: open bracket exists mid-string, name does not
        # start with an open bracket.
        assert (
            _clean_display_name("招牌青花椒鱼(微麻微辣)[小份]")
            == "招牌青花椒鱼(微麻微辣)[小份]"
        )

    def test_close_bracket_not_at_end_unchanged(self):
        # Starts with [ but does NOT end with ] → not fully wrapped.
        assert _clean_display_name("[微信]余额") == "[微信]余额"

    def test_unbalanced_inner_close_unchanged(self):
        # "[a]b]" — ends with ] and starts with [ but the inner "a]b" has a
        # mid-string close, so the leading [ does not wrap the whole name.
        assert _clean_display_name("[a]b]") == "[a]b]"

    def test_only_open_bracket_unchanged(self):
        assert _clean_display_name("[微信") == "[微信"

    def test_only_close_bracket_unchanged(self):
        assert _clean_display_name("微信]") == "微信]"

    def test_mismatched_pair_unchanged(self):
        # Open square, close fullwidth-paren — not a matching pair.
        assert _clean_display_name("[微信）") == "[微信）"

    # --- safety: non-str / empty / None ---

    def test_none_is_safe(self):
        assert _clean_display_name(None) is None

    def test_empty_string_unchanged(self):
        assert _clean_display_name("") == ""

    def test_single_char_unchanged(self):
        assert _clean_display_name("[") == "["

    def test_just_bracket_pair_empty_inner(self):
        # "[]" — inner is empty; len < 2 guard does not trigger (len==2) but
        # inner "" has no close bracket → strips to "".
        assert _clean_display_name("[]") == ""

    def test_non_str_int_passthrough(self):
        assert _clean_display_name(123) == 123

    def test_whitespace_wrapped_name(self):
        # Leading/trailing whitespace around a wrapped name still strips.
        assert _clean_display_name("  [微信]  ") == "微信"


# ---------------------------------------------------------------------------
# Gold-query integration: names come back cleaned (grouping unaffected)
# ---------------------------------------------------------------------------

class TestChannelBreakdownCleansNames:

    def _run(self, rows):
        pool = _FakePool(rows)
        return asyncio.run(
            channel_breakdown(
                pool, "RES_3101_009", (date(2025, 1, 1), date(2025, 12, 31))
            )
        )

    def test_channel_names_are_unbracketed(self):
        rows = [
            {"channel_id": 1, "name": "[微信]",
             "amount": Decimal("6000.00"), "bill_count": 60},
            {"channel_id": 2, "name": "[美团]",
             "amount": Decimal("3000.00"), "bill_count": 40},
            {"channel_id": 3, "name": "现金",
             "amount": Decimal("1000.00"), "bill_count": 20},
        ]
        result = self._run(rows)
        names = [c["channel_name"] for c in result["channels"]]
        assert names == ["微信", "美团", "现金"]

    def test_amounts_and_shares_unaffected(self):
        rows = [
            {"channel_id": 1, "name": "[微信]",
             "amount": Decimal("7500.00"), "bill_count": 60},
            {"channel_id": 2, "name": "[美团]",
             "amount": Decimal("2500.00"), "bill_count": 40},
        ]
        result = self._run(rows)
        assert result["total_amount"] == pytest.approx(10000.0, rel=1e-6)
        wx = result["channels"][0]
        assert wx["channel_name"] == "微信"
        assert wx["amount"] == pytest.approx(7500.0, rel=1e-6)
        assert wx["share_pct"] == pytest.approx(75.0, abs=0.01)

    def test_empty_rows(self):
        result = self._run([])
        assert result["channels"] == []
        assert result["total_amount"] == 0.0


class TestDiscountBreakdownCleansNames:

    def _run(self, rows):
        pool = _FakePool(rows)
        return asyncio.run(
            discount_breakdown(
                pool, "RES_3101_009", (date(2025, 1, 1), date(2025, 12, 31))
            )
        )

    def test_discount_names_are_unbracketed(self):
        rows = [
            {"discount_id": 1, "name": "[美团套餐券]",
             "amount": Decimal("5000.00"), "bill_count": 50},
            {"discount_id": 2, "name": "满减活动",
             "amount": Decimal("2000.00"), "bill_count": 30},
        ]
        result = self._run(rows)
        names = [d["discount_name"] for d in result["discounts"]]
        assert names == ["美团套餐券", "满减活动"]


class TestOrderTypeMixCleansNames:

    def _run(self, rows):
        pool = _FakePool(rows)
        return asyncio.run(
            order_type_mix(
                pool, "RES_3101_009", (date(2025, 1, 1), date(2025, 12, 31))
            )
        )

    def test_unbracketed_order_types_unchanged(self):
        # Normal qhj values are unbracketed → no-op.
        rows = [
            {"amt": Decimal("100.00"), "bills": 5, "order_type": "堂食"},
            {"amt": Decimal("50.00"), "bills": 3, "order_type": "外卖"},
        ]
        result = self._run(rows)
        types = [t["order_type"] for t in result["order_types"]]
        assert types == ["堂食", "外卖"]

    def test_bracketed_order_type_cleaned(self):
        rows = [{"amt": Decimal("100.00"), "bills": 5, "order_type": "[堂食]"}]
        result = self._run(rows)
        assert result["order_types"][0]["order_type"] == "堂食"
