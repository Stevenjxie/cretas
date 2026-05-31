"""阶段3 — 垂直分析 prompt 增强单测: 诊断顺序 + 数字引用铁律 + 纵向自比 + 推导链 few-shot。"""
from __future__ import annotations

from smartbi.services.insights.prompt_builder import (
    build_cacheable_system_prompt,
    get_derivation_fewshot,
)


def test_numeric_grounding_in_all_scenarios():
    for scenario in ("restaurant_operations", "production", "financial", "general"):
        sp = build_cacheable_system_prompt("large", scenario)
        assert "数字引用铁律" in sp, scenario
        assert "禁止自行编造" in sp, scenario
        assert "需补充" in sp, scenario


def test_benchmark_ordering_vertical_self_compare():
    sp = build_cacheable_system_prompt("large", "restaurant_operations")
    assert "对标顺序" in sp
    assert "纵向对比" in sp
    assert "不是达标线" in sp  # 行业范围降级为方向参考


def test_restaurant_diagnostic_order():
    sp = build_cacheable_system_prompt("large", "restaurant_operations")
    assert "诊断顺序" in sp
    assert "四象限" in sp
    assert "高销低毛" in sp and "低销高毛" in sp  # menu engineering 行动方向


def test_production_diagnostic_order_and_attribution():
    sp = build_cacheable_system_prompt("large", "production")
    assert "诊断顺序" in sp
    assert "出成率" in sp
    # 出成率异常先定性: 加工浪费(车间) vs 原料出成低(采购)
    assert "加工浪费" in sp and "原料出成低" in sp


def test_derivation_fewshot_anti_fabrication():
    r = build_cacheable_system_prompt("large", "restaurant_operations")
    p = build_cacheable_system_prompt("large", "production")
    assert "推导示范" in r and "凭空编造" in r
    assert "不可溯源" in r  # 反面示范点名"凭空数字不可溯源"
    assert "解冻失水" in p  # 工厂推导示范
    # financial/sales/supply_chain 复用 general 推导示范
    assert get_derivation_fewshot("financial") == get_derivation_fewshot("general")
    assert get_derivation_fewshot("sales") == get_derivation_fewshot("general")


def test_grounding_placed_after_rules_highest_priority():
    sp = build_cacheable_system_prompt("large", "general")
    # 数字引用铁律 置于写作铁律之后 = 最后/最强指令
    assert sp.index("数字引用铁律") > sp.index("写作铁律")


def test_no_regression_existing_requirements_preserved():
    sp = build_cacheable_system_prompt("large", "restaurant_operations")
    # 既有口径标注 + 可执行建议要求仍在
    assert "口径标注" in sp
    assert "可执行建议" in sp
    assert "严格" in sp and "JSON" in sp


if __name__ == "__main__":
    import pytest
    raise SystemExit(pytest.main([__file__, "-v"]))
