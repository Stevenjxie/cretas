"""Unit tests for the P3 store-alias matcher (pure functions, no DB).

Run with:
    cd backend/python
    python -m pytest smartbi/gold/tests/test_store_alias_matcher.py -v
"""
from __future__ import annotations

from smartbi.gold.store_alias_matcher import (
    CONF_BRAND_LANDMARK,
    CONF_EXACT,
    CONF_LANDMARK_AMBIGUOUS,
    CONF_LANDMARK_UNIQUE,
    Candidate,
    extract_landmark,
    match_review_store,
)


# ---------------------------------------------------------------------------
# extract_landmark
# ---------------------------------------------------------------------------

def test_extract_landmark_strips_trailing_store():
    assert extract_landmark("青花椒·外卖卫星店(五角场店)") == "五角场"


def test_extract_landmark_fullwidth_bracket():
    assert extract_landmark("鲜行者X顺德小馆（虹口龙之梦店）") == "虹口龙之梦"


def test_extract_landmark_no_bracket_returns_none():
    assert extract_landmark("青花椒徐汇日月光店") is None


def test_extract_landmark_takes_last_bracket():
    # 前置括号 (品牌限定) + 尾部地标括号 → 取地标。
    assert extract_landmark("(集团)青花椒(五角场店)") == "五角场"


def test_extract_landmark_empty():
    assert extract_landmark("") is None
    assert extract_landmark(None) is None  # type: ignore[arg-type]


# ---------------------------------------------------------------------------
# match_review_store
# ---------------------------------------------------------------------------

_DIM = [
    (101, "青花椒徐汇日月光店"),
    (102, "青花椒五角场万达店"),
    (103, "青花椒静安大悦城店"),
]


def test_unique_landmark_match_conf_092():
    # '日月光' 唯一命中 store 101 → conf 0.92, method landmark.
    cands = match_review_store("鲜行者X顺德小馆(日月光店)", _DIM)
    assert len(cands) == 1
    c = cands[0]
    assert c.store_id == 101
    assert c.confidence == CONF_LANDMARK_UNIQUE
    assert c.match_method == "landmark"
    assert c.landmark == "日月光"


def test_ambiguous_landmark_multi_candidate_conf_060():
    # '青花椒' 这个地标会命中全部 3 家 → 歧义 → conf 0.60, 全返。
    cands = match_review_store("某品牌(青花椒店)", _DIM)
    assert len(cands) == 3
    assert all(c.confidence == CONF_LANDMARK_AMBIGUOUS for c in cands)
    assert all(c.match_method == "landmark" for c in cands)


def test_exact_norm_match_conf_10():
    # 归一后完全相等 (标点/连接符差异被 normalize 抹平)。
    cands = match_review_store("青花椒·徐汇日月光店", _DIM)
    assert len(cands) >= 1
    top = [c for c in cands if c.store_id == 101]
    assert top and top[0].confidence == CONF_EXACT
    assert top[0].match_method == "exact_norm"


def test_no_bracket_brand_landmark_or_none():
    # 无括号 → 走 brand_landmark; 与一家 token 重叠高且唯一 → 0.85。
    cands = match_review_store("青花椒五角场万达", _DIM)
    # 应唯一命中 102 (五角场万达 token 与 102 重叠最高)。
    sids = [c.store_id for c in cands]
    assert 102 in sids
    hit = [c for c in cands if c.store_id == 102][0]
    # brand_landmark 唯一 → 0.85; 若多候选歧义 → 0.60。两者都不进 auto-usable join。
    assert hit.match_method == "brand_landmark"
    assert hit.confidence in (CONF_BRAND_LANDMARK, CONF_LANDMARK_AMBIGUOUS)


def test_completely_unrelated_returns_empty():
    cands = match_review_store("肯德基(陆家嘴店)", _DIM)
    # 地标 '陆家嘴' 不在任何 dim 名里; brand token 无重叠 → 空。
    assert cands == []


def test_empty_inputs():
    assert match_review_store("", _DIM) == []
    assert match_review_store("青花椒(五角场店)", []) == []


def test_satellite_store_no_pos_counterpart_low_or_empty():
    # 外卖卫星店地标若不在任何 POS 名 → 不应高置信自动绑定。
    dim_no_landmark = [(201, "青花椒人民广场店")]
    cands = match_review_store("青花椒·外卖卫星店(五角场店)", dim_no_landmark)
    # '五角场' 不在 '人民广场' → landmark 不中; brand token 重叠可能命中但绝不 >= 0.90 auto-usable。
    for c in cands:
        assert c.confidence < 0.90


def test_candidate_is_frozen_dataclass():
    c = Candidate(1, "x", 0.92, "landmark", "y")
    assert c.store_id == 1 and c.confidence == 0.92
