"""Tests for smartbi.services.query_normalizer.

UX-1 fix (Apr 26 2026): synonym normalization to lift cache hit rate.
"""
from __future__ import annotations

from smartbi.services.query_normalizer import (
    normalize_query,
    normalize_for_match,
)


# ---------- Trend / time synonyms ----------

def test_zoushi_to_qushi():
    """走势 → 趋势 (most-frequent S4 audit miss)"""
    out, subs = normalize_query("月度销售走势")
    assert out == "月度销售趋势"
    assert "走势→趋势" in subs


def test_yueye_to_yueduo():
    out, _ = normalize_query("按月销量")
    assert "按月" not in out
    assert "月度" in out


# ---------- Revenue synonyms ----------

def test_yingshou_to_yingyee():
    """营收 → 营业额"""
    out, subs = normalize_query("12 月营收多少")
    assert out == "12 月营业额多少"
    assert "营收→营业额" in subs


def test_zongyingshou_to_yingyee():
    """总营收 → 营业额 (longer synonym wins)"""
    out, _ = normalize_query("总营收 1.5 亿")
    assert "营业额" in out
    assert "营收" not in out


def test_yeji_to_yingye():
    out, _ = normalize_query("总销售额是多少")
    # 总销售额 → 销售额
    assert "总销售额" not in out
    assert "销售额" in out


# ---------- Quantity synonyms ----------

def test_xiaoliang_variants():
    out, _ = normalize_query("今天卖了多少")
    assert "卖了多少" not in out
    assert "销量" in out


# ---------- Customer synonyms ----------

def test_kelu_to_kelusliang():
    out, subs = normalize_query("到店人数")
    assert out == "客流量"
    assert "到店人数→客流量" in subs


# ---------- Comparison synonyms ----------

def test_paihang_to_paiming():
    out, _ = normalize_query("门店排行榜")
    assert "排行榜" not in out
    assert "排名" in out


def test_bijiao_to_duibi():
    out, _ = normalize_query("Q1 vs Q2 比较")
    assert "比较" not in out
    assert "对比" in out


# ---------- Member synonyms ----------

def test_liushi_variants():
    out, _ = normalize_query("沉睡会员")
    assert "沉睡" not in out
    assert "流失" in out


def test_zhaohui_variants():
    out, _ = normalize_query("如何挽回客户")
    assert "挽回" not in out
    assert "召回" in out


# ---------- No-op cases ----------

def test_no_synonym_no_change():
    """Query with no synonyms passes through unchanged"""
    q = "Top 10 菜品销量"
    out, subs = normalize_query(q)
    assert out == q
    assert subs == []


def test_empty_query():
    out, subs = normalize_query("")
    assert out == ""
    assert subs == []


# ---------- normalize_for_match ----------

def test_normalize_for_match_returns_string():
    out = normalize_for_match("月度走势")
    assert out == "月度趋势"
    assert isinstance(out, str)


def test_normalize_for_match_unchanged():
    out = normalize_for_match("Top 10 菜品")
    assert out == "Top 10 菜品"


# ---------- Light measure② (May 31 2026): best/slow-seller + 收入 ----------

def test_rexiao_to_changxiao():
    """热销 → 畅销 (best-seller synonym)"""
    out, subs = normalize_query("热销菜品")
    assert out == "畅销菜品"
    assert "热销→畅销" in subs


def test_slow_mover_variants_to_zhixiao():
    """卖不出去 / 卖不动 / 卖得不好 → 滞销 (slow-mover synonyms)"""
    assert normalize_query("哪些菜卖不出去")[0] == "哪些菜滞销"
    assert normalize_query("卖不动的商品")[0] == "滞销的商品"
    assert normalize_query("哪些产品卖得不好")[0] == "哪些产品滞销"


def test_shouru_to_yingyee():
    """收入 → 营业额 (bare 收入 appended to revenue group)"""
    out, _ = normalize_query("12 月收入多少")
    assert "收入" not in out
    assert "营业额" in out


# ---------- Integration: realistic S4 audit queries ----------

def test_s4_audit_misses_now_normalize():
    """5 actual queries from S4 audit that didn't hit cache.
    After normalization, they should match templates."""
    cases = [
        ("月度销售走势", "月度销售趋势"),       # → monthly_trend
        ("Q4 营收多少", "Q4 营业额多少"),       # → revenue templates
        ("末位菜品销量怎么样", "末位菜品销量如何"),  # 怎么样 → 如何
        ("门店排行榜", "门店排名"),             # → store_performance
        ("沉睡会员唤醒", "流失会员召回"),       # → member_deep_analytics
    ]
    for input_q, expected in cases:
        out, _ = normalize_query(input_q)
        assert out == expected, f"{input_q!r} → {out!r}, expected {expected!r}"
