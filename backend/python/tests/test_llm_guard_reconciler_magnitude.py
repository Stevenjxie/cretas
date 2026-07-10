"""FactReconciler (llm_guard) — magnitude gate + baseline behavior.

The synthesis path uses `smartbi.services.llm_guard.FactReconciler` (NOT
insights.fact_reconciler). It had no test coverage; these lock the magnitude
gate that stops it from rewriting a different-quantity number (a derived
ratio / percentage) that merely shares a metric's label in a comparative
sentence — the 折扣 live bug where "总营业额是折扣金额合计的 1719%" got
"（数据核对：实际 121,632,343）" injected after the 1719.
"""
from smartbi.services.llm_guard import FactReconciler


def _annot(answer, facts):
    return FactReconciler().reconcile_index(answer, facts).annotated_answer


def test_gate_skips_orders_of_magnitude_off_number():
    # 1719 (a ratio) is ~70,000× off the 总营业额 fact → different quantity → skip.
    facts = {"总营业额": 121632343.0}
    out = _annot("总营业额 1719 只是一个倍数比值。", facts)
    assert "数据核对" not in out
    assert "1719" in out


def test_gate_skips_percentage_near_money_label():
    # "占应收营业额 5.8%" — the 5.8 must not be rewritten to the money fact.
    facts = {"应收营业额": 121632343.0}
    out = _annot("折扣占应收营业额 5.8%。", facts)
    assert "数据核对" not in out


def test_same_magnitude_misstatement_still_annotated():
    # A genuine wrong restatement of the metric (same ballpark) is still caught.
    facts = {"净利润": 2193226.0}
    out = _annot("净利润 900000 元。", facts)
    assert "数据核对" in out


def test_accurate_number_untouched():
    facts = {"净利润": 2193226.0}
    out = _annot("净利润 2193226 元。", facts)
    assert "数据核对" not in out
