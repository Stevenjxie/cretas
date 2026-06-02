"""P4a — dish_rule_normalize pure-function tests (spec §5 Task 3).

Same normalized_key → SAME canonical candidate. Different body → different key
(no over-merge: 烤鱼煲 ≠ 鱼, 牛肉 ≠ 牛腩).
"""
from __future__ import annotations

from smartbi.services.materialized_analytics.restaurant.dish_rule_normalize import (
    dish_rule_normalize,
    strip_dish_suffixes,
)


def test_signature_variants_collapse_to_same_key():
    """39 招牌变体: 份量/口味/吃法/制作/外层# 后缀剥光后同 key。"""
    variants = [
        "招牌青花椒鱼(单人份)",
        "#招牌青花椒鱼(微麻微辣)(一吃)#",
        "招牌青花椒鱼[大份活鱼现做]",
        "招牌青花椒鱼(微麻微辣)[小份小心鱼刺]",
        "招牌青花椒鱼（两吃）",
    ]
    keys = {dish_rule_normalize(v) for v in variants}
    assert len(keys) == 1, f"expected 1 canonical key, got {keys}"
    assert next(iter(keys)) == dish_rule_normalize("招牌青花椒鱼")


def test_different_dish_body_not_merged():
    """烤鱼煲 / 鱼 主体词不同 → 不同 key (规则层不动主体, 不误并)。"""
    assert dish_rule_normalize("招牌青花椒烤鱼煲") != dish_rule_normalize("招牌青花椒鱼")


def test_red_braise_beef_vs_brisket_not_merged():
    """字面相似 ≠ 同菜: 红烧牛肉 ≠ 红烧牛腩 (per spec R1 P0 / #364)。"""
    assert dish_rule_normalize("红烧牛肉") != dish_rule_normalize("红烧牛腩")


def test_rice_variants_collapse():
    """6 米饭变体: '米饭' / '#米饭#' / '米饭#' / '米饭(单人份)#' → 同 key。"""
    variants = ["米饭", "#米饭#", "米饭#", "#米饭", "米饭(单人份)"]
    keys = {dish_rule_normalize(v) for v in variants}
    assert len(keys) == 1, f"expected 1 米饭 key, got {keys}"


def test_traditional_simplified_fold():
    """繁简 + 标点折叠进 normalize_for_dim (key 与 dim 一致)。"""
    assert dish_rule_normalize("招牌青花椒魚(单人份)") == dish_rule_normalize("招牌青花椒鱼")


def test_empty_and_whitespace():
    assert dish_rule_normalize("") == ""
    assert dish_rule_normalize("   ") == ""
    assert strip_dish_suffixes("") == ""


def test_strip_keeps_body_when_no_known_suffix():
    """未知字符不剥 (z 不是已知后缀)。"""
    assert strip_dish_suffixes("红糖冰粉z") == "红糖冰粉z"
