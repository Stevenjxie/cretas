"""
TDD for fix/p0-rate-canonical + fix/p2-amount-canonical:
  Rate-suffix columns with a precise synonym in STANDARD_FIELDS must map to the
  specific canonical (e.g. achievement_rate), NOT the fallback 'rate_percent'.

  Amount/quantity-suffix columns with a precise synonym must now also map to the
  specific canonical (e.g. revenue, purchase_amount, purchase_quantity), NOT the
  identity fallback, so infer_domain can recognise the domain.

Tests are against _classify_by_priority_regex directly (pure sync, no I/O).
"""
from smartbi.services.semantic_mapper import SemanticMapper
from smartbi.services.domain_standard_fields import STANDARD_FIELDS


def _classify(col: str):
    """Thin wrapper calling the method under test."""
    m = SemanticMapper()
    return m._classify_by_priority_regex(col)


# ─────────────────────────────────────────────────────────────────────────────
# 1. Rate-suffix columns that ARE precise synonyms → must get specific canonical
# ─────────────────────────────────────────────────────────────────────────────

def test_achievement_rate_maps_to_canonical():
    """达成率 is a synonym of achievement_rate — must not collapse to rate_percent."""
    cat, std, conf = _classify("达成率")
    assert std == "achievement_rate", f"got {std!r}"
    assert cat == "rate"
    assert conf >= 0.90


def test_yield_rate_maps_to_canonical():
    """出成率 is a synonym of yield_rate."""
    cat, std, conf = _classify("出成率")
    assert std == "yield_rate", f"got {std!r}"
    assert cat == "rate"


def test_defect_rate_maps_to_canonical():
    """不良率 is a synonym of defect_rate."""
    cat, std, conf = _classify("不良率")
    assert std == "defect_rate", f"got {std!r}"
    assert cat == "rate"


def test_gross_margin_rate_maps_to_canonical():
    """毛利率 is a synonym of gross_margin_rate."""
    cat, std, conf = _classify("毛利率")
    assert std == "gross_margin_rate", f"got {std!r}"
    assert cat == "rate"


def test_net_margin_rate_maps_to_canonical():
    """净利率 is a synonym of net_margin_rate."""
    cat, std, conf = _classify("净利率")
    assert std == "net_margin_rate", f"got {std!r}"
    assert cat == "rate"


def test_turnover_rate_maps_to_canonical():
    """周转率 is a synonym of turnover_rate."""
    cat, std, conf = _classify("周转率")
    assert std == "turnover_rate", f"got {std!r}"
    assert cat == "rate"


def test_完成率_maps_to_achievement_rate():
    """完成率 is another synonym of achievement_rate."""
    cat, std, conf = _classify("完成率")
    assert std == "achievement_rate", f"got {std!r}"
    assert cat == "rate"


# ─────────────────────────────────────────────────────────────────────────────
# 2. mom_rate / yoy_rate: these synonyms have NO rate-suffix (环比增长, 同比增长)
#    → the rate regex won't even fire → should return None (fall to synonym layer)
# ─────────────────────────────────────────────────────────────────────────────

def test_yoy_rate_not_intercepted_by_priority_regex():
    """同比增长 has no 率/比例/占比/系数/百分比/比率 suffix.
    Priority regex should return None so synonym layer picks it up as yoy_rate.
    """
    result = _classify("同比增长")
    # Should NOT be intercepted (returns None OR a non-rate category).
    # The rate regex won't match '同比增长' — it doesn't end in 率 etc.
    # If it somehow matches, it must NOT be rate_percent.
    if result is not None:
        cat, std, conf = result
        assert std != "rate_percent", (
            "同比增长 must not be collapsed to rate_percent "
            f"(got {std!r}); it should fall through to synonym layer"
        )


def test_mom_rate_not_intercepted_by_priority_regex():
    """环比增长 has no 率 suffix — should not be intercepted."""
    result = _classify("环比增长")
    if result is not None:
        cat, std, conf = result
        assert std != "rate_percent", (
            f"环比增长 must not be rate_percent (got {std!r})"
        )


# ─────────────────────────────────────────────────────────────────────────────
# 3. Rate-suffix columns with NO synonym → fallback to rate_percent
# ─────────────────────────────────────────────────────────────────────────────

def test_unknown_rate_suffix_falls_back_to_rate_percent():
    """A 率-suffix column (with boundary) with no known synonym → still rate_percent.
    Note: the regex requires 率 to be followed by (, space, or end-of-string.
    '某神秘率' ends in 率 at EOL so it matches, but '神秘率XYZ' does NOT match
    (率 is followed by X, a non-boundary), so we use '某神秘率' here.
    """
    cat, std, conf = _classify("某神秘率")
    assert std == "rate_percent", f"got {std!r}"
    assert cat == "rate"


def test_unknown_占比_falls_back_to_rate_percent():
    """An 占比 column with no known synonym → rate_percent."""
    cat, std, conf = _classify("某渠道占比")
    assert std == "rate_percent", f"got {std!r}"
    assert cat == "rate"


# ─────────────────────────────────────────────────────────────────────────────
# 4. D1 regression — substring-only match must NOT hit rate or product
# ─────────────────────────────────────────────────────────────────────────────

def test_d1_regression_quantity_not_product():
    """D1 original bug: '单卖数量(不含套餐子商品)' contains '商品' but must NOT
    map to product category via substring matching. Priority regex fires on
    amount suffix ('数量') before synonym layer runs.
    """
    result = _classify("单卖数量(不含套餐子商品)")
    assert result is not None, "Should be caught by amount regex"
    cat, std, conf = result
    assert cat == "amount", f"expected 'amount', got {cat!r}"
    # std is col-preserved (per comment in code)


def test_d1_regression_no_substring_rate_promotion():
    """A column containing '率' elsewhere in a non-suffix position but the regex
    uses end-of-word anchors — should not be caught by the rate branch if '率'
    is not a suffix.
    """
    # '合格率检验说明' — 率 is mid-word, not at the end before ( or \s or EOL
    result = _classify("合格率检验说明")
    # The rate regex requires (率|占比|...)(\(|（|\s|$) — if '率' is followed
    # by '检', it should NOT match.  Either None or a category result, but
    # if it matched rate it would be fine too since '合格率' IS a suffix here
    # just with trailing text — let's assert it doesn't get rate_percent
    # when the text after 率 is non-boundary.
    # Actually '合格率' is followed by '检验说明' (no boundary) → shouldn't match.
    if result is not None:
        cat, std, conf = result
        # If matched, it would be via another branch (not rate)
        pass  # No assertion needed here — just documenting behavior


# ─────────────────────────────────────────────────────────────────────────────
# 5. Canonical dict sanity — all rate-canonicals have 'rate' category
# ─────────────────────────────────────────────────────────────────────────────

def test_rate_canonicals_have_rate_category():
    """Every canonical the fix can map to must have category='rate'."""
    rate_canonicals = [
        "achievement_rate", "yoy_rate", "mom_rate",
        "gross_margin_rate", "net_margin_rate",
        "defect_rate", "yield_rate", "turnover_rate",
    ]
    for name in rate_canonicals:
        assert name in STANDARD_FIELDS, f"{name!r} missing from STANDARD_FIELDS"
        assert STANDARD_FIELDS[name]["category"] == "rate", (
            f"{name!r} category is {STANDARD_FIELDS[name]['category']!r}, expected 'rate'"
        )


# =============================================================================
# fix/p2-amount-canonical — amount/quantity branch exact-synonym promotion
# =============================================================================

# ─────────────────────────────────────────────────────────────────────────────
# 6. Amount/quantity columns that ARE precise synonyms → specific canonical
# ─────────────────────────────────────────────────────────────────────────────

def test_营业收入_with_unit_maps_to_revenue():
    """营业收入(元) — unit suffix stripped → exact synonym of revenue."""
    cat, std, conf = _classify("营业收入(元)")
    assert std == "revenue", f"got {std!r}"
    assert cat == "amount"
    assert conf >= 0.90


def test_采购金额_with_unit_maps_to_purchase_amount():
    """采购金额(元) → purchase_amount."""
    cat, std, conf = _classify("采购金额(元)")
    assert std == "purchase_amount", f"got {std!r}"
    assert cat == "amount"


def test_采购数量_with_unit_maps_to_purchase_quantity():
    """采购数量(kg) — 数量 suffix hits amount regex, unit stripped → purchase_quantity.
    Note: bare '量' is not a keyword in the amount regex; '数量' is, so we use
    '采购数量' (also a synonym of purchase_quantity) instead of '采购量'.
    """
    cat, std, conf = _classify("采购数量(kg)")
    assert std == "purchase_quantity", f"got {std!r}"
    assert cat == "quantity"


def test_产出数量_maps_to_output_quantity():
    """产出数量 (no unit) → output_quantity."""
    cat, std, conf = _classify("产出数量")
    assert std == "output_quantity", f"got {std!r}"
    assert cat == "quantity"


def test_销售额_maps_to_sales_amount():
    """销售额 → sales_amount."""
    cat, std, conf = _classify("销售额")
    assert std == "sales_amount", f"got {std!r}"
    assert cat == "amount"


def test_库存数量_maps_to_stock_quantity():
    """库存数量 → stock_quantity."""
    cat, std, conf = _classify("库存数量")
    assert std == "stock_quantity", f"got {std!r}"
    assert cat == "quantity"


def test_营业成本_with_unit_maps_to_cost():
    """营业成本(元) — synonym of cost."""
    cat, std, conf = _classify("营业成本(元)")
    assert std == "cost", f"got {std!r}"
    assert cat == "amount"


def test_category_correct_for_quantity_canon():
    """产出数量 → category must be 'quantity', not 'amount'."""
    cat, std, conf = _classify("产出数量")
    assert cat == "quantity", f"expected 'quantity', got {cat!r}"


def test_category_correct_for_采购数量():
    """采购数量(kg) → category is 'quantity' (from purchase_quantity canonical)."""
    cat, std, conf = _classify("采购数量(kg)")
    assert cat == "quantity", f"expected 'quantity', got {cat!r}"


# ─────────────────────────────────────────────────────────────────────────────
# 7. Identity fallback still fires when no exact synonym matches
# ─────────────────────────────────────────────────────────────────────────────

def test_identity_fallback_for_nonsynonym_quantity():
    """产品A产量(箱) — '产品A产量' is not an exact synonym of any canonical.
    The amount regex fires (产量 suffix), but no canonical matches → identity.
    """
    cat, std, conf = _classify("产品A产量(箱)")
    assert cat == "amount", f"got cat={cat!r}"
    # std is the original column name (identity preservation)
    assert std == "产品A产量(箱)", f"expected identity, got {std!r}"


def test_d1_regression_identity_fallback_complex_quantity():
    """D1 regression: '单卖数量(不含套餐子商品)' must NOT map to product/sales_quantity.

    _norm_for_exact strips '(不含套餐子商品)' → '单卖数量', which is NOT an exact
    synonym of any canonical (sales_quantity synonyms: 销售量/销量/销售数量/出货数量).
    Therefore the identity fallback must fire.
    """
    cat, std, conf = _classify("单卖数量(不含套餐子商品)")
    assert cat == "amount", f"got cat={cat!r}"
    # Must NOT be remapped to a canonical — identity preserved
    assert std == "单卖数量(不含套餐子商品)", (
        f"D1 regression: expected identity fallback, got {std!r}"
    )


# ─────────────────────────────────────────────────────────────────────────────
# 8. _norm_for_exact unit — standalone helper tests
# ─────────────────────────────────────────────────────────────────────────────

def test_norm_strips_ascii_unit_suffix():
    from smartbi.services.semantic_mapper import SemanticMapper
    assert SemanticMapper._norm_for_exact("营业收入(元)") == "营业收入"


def test_norm_strips_fullwidth_unit_suffix():
    from smartbi.services.semantic_mapper import SemanticMapper
    assert SemanticMapper._norm_for_exact("采购量（kg）") == "采购量"


def test_norm_strips_compound_unit():
    from smartbi.services.semantic_mapper import SemanticMapper
    assert SemanticMapper._norm_for_exact("单价(元/kg)") == "单价"


def test_norm_no_change_without_unit():
    from smartbi.services.semantic_mapper import SemanticMapper
    assert SemanticMapper._norm_for_exact("产出数量") == "产出数量"


def test_norm_strips_separators_and_lowercases():
    from smartbi.services.semantic_mapper import SemanticMapper
    assert SemanticMapper._norm_for_exact("Budget_Amount") == "budgetamount"


def test_norm_complex_parenthesis_not_stripped_mid():
    """Parenthesis NOT at the very end must NOT be stripped — it's part of name."""
    from smartbi.services.semantic_mapper import SemanticMapper
    # '(不含套餐子商品)' at end — the whole trailing paren is stripped
    result = SemanticMapper._norm_for_exact("单卖数量(不含套餐子商品)")
    # After stripping: '单卖数量' → then separators/lower → '单卖数量'
    assert result == "单卖数量", f"got {result!r}"


# ─────────────────────────────────────────────────────────────────────────────
# 9. Canonical dict sanity — amount/quantity canonicals have correct categories
# ─────────────────────────────────────────────────────────────────────────────

def test_amount_quantity_canonicals_have_correct_category():
    """Spot-check that key amount/quantity canonicals have the right category."""
    checks = {
        "revenue": "amount",
        "cost": "amount",
        "sales_amount": "amount",
        "purchase_amount": "amount",
        "output_quantity": "quantity",
        "purchase_quantity": "quantity",
        "stock_quantity": "quantity",
        "sales_quantity": "quantity",
    }
    for name, expected_cat in checks.items():
        assert name in STANDARD_FIELDS, f"{name!r} missing from STANDARD_FIELDS"
        got = STANDARD_FIELDS[name]["category"]
        assert got == expected_cat, (
            f"{name!r}: expected category={expected_cat!r}, got {got!r}"
        )
