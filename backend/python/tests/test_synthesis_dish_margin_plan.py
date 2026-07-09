from __future__ import annotations

from smartbi.agent.synthesis_engine import ComprehensiveSynthesisEngine


def test_dish_margin_question_enables_dish_margin_dimension():
    engine = ComprehensiveSynthesisEngine(pool=object())

    plan = engine.plan_dimensions("哪道菜毛利最高？这道菜成本构成是什么？")

    assert plan["dish_margin"] is True
    assert plan["sales"] is True
