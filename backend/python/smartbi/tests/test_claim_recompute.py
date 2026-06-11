"""
Tests for claim_recompute.py — C1.1 pure function: recompute LLM claim from raw series.
TDD: written before implementation. 11 tests covering all stat_type branches + guards.
A6 direction-word tests added for infer_sign_from_prose.
"""
import pytest
from smartbi.services.insights.claim_recompute import recompute_claim, infer_sign_from_prose

# Canonical test series: 堂食=62000, 外卖=38000, total=100000
SERIES = [62000.0, 38000.0]
LABELS = ["堂食", "外卖"]


def test_share():
    # 堂食 share = 62000/100000*100 = 62.0
    result = recompute_claim("堂食", "share", SERIES, LABELS)
    assert result == pytest.approx(62.0, abs=0.01)


def test_complement():
    # complement of 外卖 = 100 - (38000/100000*100) = 62.0
    result = recompute_claim("外卖", "complement", SERIES, LABELS)
    assert result == pytest.approx(62.0, abs=0.01)


def test_ratio():
    # 堂食 ratio = 62000 / min(62000, 38000) = 62000/38000 ≈ 1.6316
    result = recompute_claim("堂食", "ratio", SERIES, LABELS)
    assert result == pytest.approx(62000.0 / 38000.0, abs=0.001)


def test_value():
    # 外卖 value = 38000
    result = recompute_claim("外卖", "value", SERIES, LABELS)
    assert result == pytest.approx(38000.0, abs=0.01)


def test_top2_share():
    # top2_share = (62000+38000)/100000*100 = 100.0 (only 2 elements, entity ignored)
    result = recompute_claim(None, "top2_share", SERIES, LABELS)
    assert result == pytest.approx(100.0, abs=0.01)


def test_top2_share_three_elements():
    # 3 elements: [50, 30, 20], top2 = (50+30)/100 * 100 = 80.0
    result = recompute_claim(None, "top2_share", [50.0, 30.0, 20.0], ["A", "B", "C"])
    assert result == pytest.approx(80.0, abs=0.01)


def test_diff():
    # diff of 堂食 = 堂食 - min = 62000 - 38000 = 24000
    result = recompute_claim("堂食", "diff", SERIES, LABELS)
    assert result == pytest.approx(24000.0, abs=0.01)


def test_growth():
    # growth over time series: (last - first) / abs(first) * 100
    # series=[100, 120, 150], growth = (150-100)/100*100 = 50.0
    result = recompute_claim(None, "growth", [100.0, 120.0, 150.0], ["Jan", "Feb", "Mar"])
    assert result == pytest.approx(50.0, abs=0.01)


def test_count():
    # count = len(series_values) = 2
    result = recompute_claim(None, "count", SERIES, LABELS)
    assert result == pytest.approx(2.0, abs=0.001)


def test_unknown_entity_returns_none():
    # entity not in labels → None
    result = recompute_claim("堂食不存在", "share", SERIES, LABELS)
    assert result is None


def test_unknown_stat_returns_none():
    # unknown stat_type → None
    result = recompute_claim("堂食", "bogus_stat", SERIES, LABELS)
    assert result is None


def test_empty_series_returns_none():
    # empty series → None
    result = recompute_claim("堂食", "share", [], [])
    assert result is None


# ---------------------------------------------------------------------------
# A6 — direction-word helper: infer_sign_from_prose
# ---------------------------------------------------------------------------

def test_direction_positive_word_returns_true():
    # "上涨15%" — 上涨 is a positive direction word → True
    prose = "营业额上涨15%，表现亮眼"
    pos = prose.index("15")
    assert infer_sign_from_prose(prose, pos) is True


def test_direction_negative_word_returns_false():
    # "下降15%" — 下降 is a negative direction word → False
    prose = "营业额下降15%，令人担忧"
    pos = prose.index("15")
    assert infer_sign_from_prose(prose, pos) is False


def test_direction_no_word_returns_none():
    # No direction word anywhere → None
    prose = "营业额为15万元，同期数据一致"
    pos = prose.index("15")
    assert infer_sign_from_prose(prose, pos) is None


def test_direction_ambiguous_both_words_returns_none():
    # Both positive and negative words in window → None (ambiguous)
    prose = "既有上涨也有下降15%的区间"
    pos = prose.index("15")
    assert infer_sign_from_prose(prose, pos) is None


def test_direction_word_outside_window_not_captured():
    # Direction word exists but well outside the scan window → None
    prose = "上涨" + "A" * 100 + "15%"
    pos = prose.index("15")
    assert infer_sign_from_prose(prose, pos, window=20) is None


def test_direction_single_char_positive():
    # Single-char "涨" close to number → True
    prose = "指标涨15个百分点"
    pos = prose.index("15")
    assert infer_sign_from_prose(prose, pos) is True


def test_direction_single_char_negative():
    # Single-char "降" close to number → False
    prose = "成本降8.3%"
    pos = prose.index("8")
    assert infer_sign_from_prose(prose, pos) is False
