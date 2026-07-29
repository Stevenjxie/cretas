"""spec §3.1 卡 C1 — 预警计划化 (P1) 测试.

分三层:

A. **取数出口门禁** — 驱动真实 `resolve_sales_summary`, 证明
   `meta.comparison.*` 确实被填成真数值, 且无数据/脱敏/口径不齐时给的是诚实
   标记而不是 0.  这是整个 P1 押注的前提, 所以用真 resolver 而不是构造 dict.

B. **阈值语义** — `extract_metric` / `evaluate_rule` 纯函数, 覆盖禁降级的每一
   条判据和每个比较符的边界.

C. **失败隔离** — `run_plan_alerts` 的逐规则粒度: 一条规则挂掉不能让别的规则
   丢掉 unavailable 豁免 (auto-resolve flap 防护).
"""
from __future__ import annotations

import asyncio
from datetime import date
from typing import Any, Dict, List, Optional

import pytest

from smartbi.gold.restaurant.plan_alert import (
    FAMILY_UNAVAILABLE_PREFIX,
    METRIC_KEY_PREFIX,
    RuleUnavailable,
    build_diagnosis,
    evaluate_rule,
    extract_metric,
    run_plan_alerts,
)
from smartbi.gold.restaurant.restaurant_ops_router import (
    OpsAnswer,
    resolve_sales_summary,
)


# ══════════════════════════════════════════════════════════════════════
# A. 取数出口门禁 — 真 resolver
# ══════════════════════════════════════════════════════════════════════

def _install_sales_fakes(
    monkeypatch,
    *,
    primary: Optional[Dict[str, Any]],
    baseline: Optional[Dict[str, Any]],
    primary_range,
):
    """把 finance_summary 换成按 date_range 分流的假实现.

    `resolve_sales_summary` 对主窗口和基线窗口各调一次 `finance_summary`
    (restaurant_ops_router.py:5961), 所以假实现必须按 date_range 区分.
    """
    import smartbi.gold.queries as _q
    import smartbi.gold.restaurant.restaurant_ops_router as _r

    empty = {
        "total_revenue": 0.0, "bill_count": 0, "avg_bill_value": None,
        "day_count": 0, "store_count": 0, "top_stores": [],
    }

    async def _fake_finance_summary(pool, factory_id, date_range, top_n_stores=5):
        if date_range == primary_range:
            return dict(primary) if primary else dict(empty)
        return dict(baseline) if baseline else dict(empty)

    async def _fake_store_comparison(pool, factory_id, date_range):
        return {"stores": [], "weakStores": []}

    async def _fake_store_margin(
        pool, factory_id, days=30, top_n=5, *, role=None, date_range=None,
    ):
        return OpsAnswer(
            code="RESTAURANT_OPS_STORE_MARGIN", title="门店毛利",
            answer_text="ok", charts=[], kpis=[], meta={},
        )

    monkeypatch.setattr(_q, "finance_summary", _fake_finance_summary)
    monkeypatch.setattr(_q, "store_comparison", _fake_store_comparison)
    monkeypatch.setattr(_r, "resolve_store_margin", _fake_store_margin)


_TODAY = date(2026, 7, 20)
# 「这个月」= 2026-07-01..2026-07-20；「上个月」基线 = 2026-06-01..2026-06-30
_PRIMARY_RANGE = (date(2026, 7, 1), date(2026, 7, 20))


def _run_sales_summary(role: str = "restaurant_owner"):
    return asyncio.run(
        resolve_sales_summary(
            object(),
            "RES_TEST",
            role=role,
            query="这个月营收比上个月怎么样",
            today=_TODAY,
        )
    )


def test_gate_meta_comparison_fills_real_numbers(monkeypatch):
    """门禁 1: 有数据 + price-view 角色 -> revenue_change_pct 是真浮点数.

    P1 的阈值全部读这个字段; 它要是永远 None, 整条预警链是死的.
    """
    _install_sales_fakes(
        monkeypatch,
        primary={
            "total_revenue": 8000.0, "bill_count": 80, "avg_bill_value": 100.0,
            "day_count": 20, "store_count": 2, "top_stores": [],
        },
        baseline={
            "total_revenue": 10000.0, "bill_count": 100, "avg_bill_value": 100.0,
            "day_count": 20, "store_count": 2, "top_stores": [],
        },
        primary_range=_PRIMARY_RANGE,
    )
    ans = _run_sales_summary()
    comparison = ans.meta.get("comparison")

    assert isinstance(comparison, dict), "resolve_sales_summary 没有产出 comparison"
    assert comparison.get("answered") is True
    assert comparison.get("baseline_no_data") is False
    # 8000 vs 10000 -> -20.0%
    assert comparison["revenue_change_pct"] == pytest.approx(-20.0)
    assert comparison["revenue_delta"] == pytest.approx(-2000.0)
    # 80 vs 100 -> -20.0%
    assert comparison["bill_change_pct"] == pytest.approx(-20.0)
    assert comparison["bill_delta"] == -20
    # 阈值判定要用到的诚实标记必须在
    assert comparison.get("coverage_mismatch") is False


def test_gate_meta_comparison_baseline_no_data_is_not_zero(monkeypatch):
    """门禁 2: 基线无数据 -> baseline_no_data=True 且**不给** revenue_change_pct.

    禁降级的核心: 没有数据不能算成 -100%.
    """
    _install_sales_fakes(
        monkeypatch,
        primary={
            "total_revenue": 8000.0, "bill_count": 80, "avg_bill_value": 100.0,
            "day_count": 20, "store_count": 1, "top_stores": [],
        },
        baseline=None,
        primary_range=_PRIMARY_RANGE,
    )
    ans = _run_sales_summary()
    comparison = ans.meta.get("comparison")

    assert isinstance(comparison, dict)
    assert comparison.get("baseline_no_data") is True
    assert "revenue_change_pct" not in comparison
    assert "bill_change_pct" not in comparison


def test_gate_meta_comparison_rbac_masks_revenue_but_keeps_bills(monkeypatch):
    """门禁 3: 非 price-view 角色 -> 营收字段被剥, 订单数仍可判定.

    这解释了 plan_alert.SWEEP_ROLE 为什么不能是 None.
    """
    _install_sales_fakes(
        monkeypatch,
        primary={
            "total_revenue": 8000.0, "bill_count": 80, "avg_bill_value": 100.0,
            "day_count": 20, "store_count": 1, "top_stores": [],
        },
        baseline={
            "total_revenue": 10000.0, "bill_count": 100, "avg_bill_value": 100.0,
            "day_count": 20, "store_count": 1, "top_stores": [],
        },
        primary_range=_PRIMARY_RANGE,
    )
    ans = _run_sales_summary(role="restaurant_waiter")
    comparison = ans.meta.get("comparison")

    assert isinstance(comparison, dict)
    assert comparison.get("primary_revenue") is None
    assert comparison.get("baseline_revenue") is None
    # 金额类字段整块不写 -> 规则会走 unavailable, 不会被当成 0
    assert comparison.get("revenue_change_pct") is None
    # 订单数不受金额门控
    assert comparison["bill_change_pct"] == pytest.approx(-20.0)


def test_gate_sweep_role_is_price_view():
    """SWEEP_ROLE 必须在 PRICE_VIEW_ROLES 里, 否则营收预警永久静默失效."""
    from smartbi_compat._rbac_strip import PRICE_VIEW_ROLES

    from smartbi.gold.restaurant.plan_alert import SWEEP_ROLE

    assert SWEEP_ROLE in PRICE_VIEW_ROLES


# ══════════════════════════════════════════════════════════════════════
# B. 阈值语义 (纯函数)
# ══════════════════════════════════════════════════════════════════════

def _receipt(**comparison_overrides) -> Dict[str, Any]:
    comparison = {
        "answered": True,
        "baseline_no_data": False,
        "coverage_mismatch": False,
        "primary_label": "本月",
        "baseline_label": "上月",
        "primary_start": "2026-07-01", "primary_end": "2026-07-20",
        "baseline_start": "2026-06-01", "baseline_end": "2026-06-30",
        "revenue_change_pct": -20.0,
        "revenue_delta": -2000.0,
        "bill_change_pct": -20.0,
        "bill_delta": -20,
    }
    comparison.update(comparison_overrides)
    return {"comparison": comparison}


def _rule(**overrides) -> Dict[str, Any]:
    rule = {
        "rule_code": "weekly_revenue_drop",
        "rule_name": "营收环比下滑预警",
        "query_text": "这个月营收比上个月怎么样",
        "plan_json": {"intent": "RESTAURANT_OPS_SALES_SUMMARY"},
        "plan_version": "restaurant-query-plan-v2",
        "metric_path": "comparison.revenue_change_pct",
        "threshold_op": "lt",
        "threshold_value": -15.0,
        "severity": "warning",
    }
    rule.update(overrides)
    return rule


def test_extract_metric_returns_float():
    assert extract_metric(_receipt(), "comparison.revenue_change_pct") == pytest.approx(-20.0)


@pytest.mark.parametrize(
    "overrides,fragment",
    [
        ({"primary_no_data": True}, "本期没有可用数据"),
        ({"baseline_no_data": True}, "对比期没有可用数据"),
        ({"coverage_mismatch": True}, "覆盖天数不同"),
        ({"answered": False}, "没有回答对比问题"),
        ({"revenue_change_pct": None}, "不可得"),
    ],
)
def test_extract_metric_honest_unavailable(overrides, fragment):
    """禁降级: 每一种"拿不到数"都必须是 unavailable, 不能是"未触发"."""
    with pytest.raises(RuleUnavailable) as exc:
        extract_metric(_receipt(**overrides), "comparison.revenue_change_pct")
    assert fragment in exc.value.reason


def test_extract_metric_missing_comparison_block():
    with pytest.raises(RuleUnavailable) as exc:
        extract_metric({}, "comparison.revenue_change_pct")
    assert "没有产出环比对比数据" in exc.value.reason


def test_extract_metric_rejects_path_outside_allowlist():
    with pytest.raises(RuleUnavailable) as exc:
        extract_metric(_receipt(), "kpis.0.rawValue")
    assert "白名单" in exc.value.reason


def test_extract_metric_rejects_bool():
    """bool 是 int 的子类 —— 必须显式挡掉, 否则 True 会被当成 1.0."""
    with pytest.raises(RuleUnavailable):
        extract_metric(_receipt(revenue_change_pct=True), "comparison.revenue_change_pct")


@pytest.mark.parametrize(
    "op,threshold,value,fires",
    [
        ("lt", -15.0, -20.0, True),
        ("lt", -15.0, -15.0, False),   # 边界: 严格小于
        ("lte", -15.0, -15.0, True),
        ("gt", 15.0, 20.0, True),
        ("gt", 15.0, 15.0, False),
        ("gte", 15.0, 15.0, True),
        ("lt", -15.0, 5.0, False),
    ],
)
def test_evaluate_rule_threshold_boundaries(op, threshold, value, fires):
    result = evaluate_rule(
        _rule(threshold_op=op, threshold_value=threshold),
        _receipt(revenue_change_pct=value),
    )
    assert (result is not None) is fires


def test_evaluate_rule_unknown_op_is_unavailable():
    with pytest.raises(RuleUnavailable):
        evaluate_rule(_rule(threshold_op="between"), _receipt())


def test_diagnosis_metric_key_is_period_free():
    """standing-alert dedup 的前提: businessEntityId 不含 period.

    metricKey 只由 rule_code 决定 —— 同一条规则连续两个月触发必须是**同一条**
    OPEN 事件在原地刷新, 而不是每月重开一条重复推送.
    """
    d1 = evaluate_rule(_rule(), _receipt(primary_label="7月", primary_start="2026-07-01"))
    d2 = evaluate_rule(_rule(), _receipt(primary_label="8月", primary_start="2026-08-01"))
    assert d1["metricKey"] == d2["metricKey"] == f"{METRIC_KEY_PREFIX}weekly_revenue_drop"
    # 但 message 会随最新一次 sweep 变化 (Bridge 会 in-place 刷新)
    assert d1["descriptionZh"] != d2["descriptionZh"]


def test_diagnosis_shape_matches_diagnostics_engine():
    """Bridge 只认 DiagnosticsEngine 的 dict 形状, 少一个键就静默漏推."""
    d = evaluate_rule(_rule(), _receipt())
    for key in ("metricKey", "metricNameZh", "severity", "status",
                "descriptionZh", "estimated", "rxActions"):
        assert key in d, f"缺少 Bridge 需要的键: {key}"
    # severity 必须是 Bridge#mapSeverity 认识的值 (info 会被静默跳过)
    assert d["severity"] in ("critical", "warning")
    assert d["estimated"] is False
    assert isinstance(d["rxActions"], list) and d["rxActions"]


def test_diagnosis_declares_non_realtime():
    """诚实标注: 不是秒级监控."""
    d = evaluate_rule(_rule(), _receipt())
    assert "非实时监控" in d["descriptionZh"]


def test_diagnosis_reports_actual_and_threshold():
    d = evaluate_rule(_rule(), _receipt(revenue_change_pct=-23.4))
    assert "-23.4%" in d["descriptionZh"]
    assert "-15.0%" in d["descriptionZh"]


# ══════════════════════════════════════════════════════════════════════
# C. 失败隔离 (run_plan_alerts)
# ══════════════════════════════════════════════════════════════════════

class _FakePool:
    """只用于让 run_plan_alerts 走到 load_plan_alert_rules 的桩."""


def _patch_rules(monkeypatch, rules: List[Dict[str, Any]]):
    import smartbi.gold.restaurant.plan_alert as _pa

    async def _fake_load(pool, factory_id, *, domain="restaurant"):
        return list(rules)

    monkeypatch.setattr(_pa, "load_plan_alert_rules", _fake_load)


def _patch_tiered(monkeypatch, by_query):
    """按 query_text 分流 tiered_answer 的返回 (或抛异常)."""
    import smartbi.gold.restaurant.restaurant_intent_service as _svc

    async def _fake_tiered(query, pool, factory_id, role, **kwargs):
        outcome = by_query[query]
        if isinstance(outcome, Exception):
            raise outcome
        return outcome

    monkeypatch.setattr(_svc, "tiered_answer", _fake_tiered)


def _answer(receipt: Dict[str, Any]) -> Dict[str, Any]:
    return {"kind": "answer", "result_meta": receipt}


def test_run_plan_alerts_isolates_a_broken_rule(monkeypatch):
    """一条规则炸掉, 另一条照常判定 —— 逐规则失败隔离.

    这是 auto-resolve flap 防护的关键粒度: 坏规则只让自己进 unavailable,
    好规则该触发触发、该恢复恢复.
    """
    monkeypatch.setattr(
        "smartbi.gold.restaurant.plan_alert.compile_rule_spec",
        lambda rule: object(),
    )
    _patch_rules(monkeypatch, [
        _rule(rule_code="broken", query_text="Q_BROKEN"),
        _rule(rule_code="healthy_fires", query_text="Q_FIRES"),
        _rule(rule_code="healthy_quiet", query_text="Q_QUIET"),
    ])
    _patch_tiered(monkeypatch, {
        "Q_BROKEN": RuntimeError("resolver exploded"),
        "Q_FIRES": _answer(_receipt(revenue_change_pct=-30.0)),
        "Q_QUIET": _answer(_receipt(revenue_change_pct=+5.0)),
    })

    diagnoses, unavailable = asyncio.run(run_plan_alerts(_FakePool(), "RES_TEST"))

    # 坏规则: 只有它进 unavailable
    assert unavailable == [f"{METRIC_KEY_PREFIX}broken"]
    # 触发的规则照常产诊断
    assert [d["metricKey"] for d in diagnoses] == [f"{METRIC_KEY_PREFIX}healthy_fires"]
    # 未触发的规则既不产诊断也不进 unavailable -> 缺席即恢复, 由 Bridge auto-resolve
    assert f"{METRIC_KEY_PREFIX}healthy_quiet" not in unavailable


def test_run_plan_alerts_table_unavailable_exempts_whole_family(monkeypatch):
    """规则表读不到 = 不知道任何一条规则的死活 -> 整族豁免 auto-resolve.

    若这里 fail-open 成"没有规则", Bridge 会把所有 OPEN 的 plan_alert 事件
    当成已恢复清掉, 下一轮又重建 = flap.
    """
    import smartbi.gold.restaurant.plan_alert as _pa

    async def _boom(pool, factory_id, *, domain="restaurant"):
        raise RuntimeError("permission denied for table restaurant_plan_alert_rules")

    monkeypatch.setattr(_pa, "load_plan_alert_rules", _boom)

    diagnoses, unavailable = asyncio.run(run_plan_alerts(_FakePool(), "RES_TEST"))
    assert diagnoses == []
    assert unavailable == [FAMILY_UNAVAILABLE_PREFIX]


def test_run_plan_alerts_no_rules_is_not_unavailable(monkeypatch):
    """租户没配规则 != 读不到规则.  空列表必须让遗留事件正常 auto-resolve."""
    _patch_rules(monkeypatch, [])
    diagnoses, unavailable = asyncio.run(run_plan_alerts(_FakePool(), "RES_TEST"))
    assert diagnoses == []
    assert unavailable == []


def test_run_plan_alerts_unavailable_metric_is_not_silent_recovery(monkeypatch):
    """取数不可得 (RBAC/无数据) -> 进 unavailable, 不产诊断, 不算恢复."""
    monkeypatch.setattr(
        "smartbi.gold.restaurant.plan_alert.compile_rule_spec",
        lambda rule: object(),
    )
    _patch_rules(monkeypatch, [_rule(rule_code="masked", query_text="Q")])
    _patch_tiered(monkeypatch, {"Q": _answer(_receipt(baseline_no_data=True))})

    diagnoses, unavailable = asyncio.run(run_plan_alerts(_FakePool(), "RES_TEST"))
    assert diagnoses == []
    assert unavailable == [f"{METRIC_KEY_PREFIX}masked"]


def test_run_plan_alerts_clarification_answer_is_unavailable(monkeypatch):
    """计划退化成澄清 (而非答案) 也是"无法判定", 不能当成恢复."""
    monkeypatch.setattr(
        "smartbi.gold.restaurant.plan_alert.compile_rule_spec",
        lambda rule: object(),
    )
    _patch_rules(monkeypatch, [_rule(rule_code="needs_clarify", query_text="Q")])
    _patch_tiered(monkeypatch, {"Q": {"kind": "clarification", "answer_text": "?"}})

    diagnoses, unavailable = asyncio.run(run_plan_alerts(_FakePool(), "RES_TEST"))
    assert diagnoses == []
    assert unavailable == [f"{METRIC_KEY_PREFIX}needs_clarify"]


# ══════════════════════════════════════════════════════════════════════
# D. 计划编译 (compile_rule_spec)
# ══════════════════════════════════════════════════════════════════════

def test_compile_rule_spec_rejects_stale_plan_version():
    from smartbi.gold.restaurant.plan_alert import compile_rule_spec

    with pytest.raises(RuleUnavailable) as exc:
        compile_rule_spec(_rule(plan_version="restaurant-query-plan-v1"))
    assert "计划版本" in exc.value.reason


def test_compile_rule_spec_rejects_unsupported_intent():
    from smartbi.gold.restaurant.plan_alert import compile_rule_spec

    with pytest.raises(RuleUnavailable) as exc:
        compile_rule_spec(_rule(plan_json={"intent": "RESTAURANT_OPS_WASTAGE_TOP"}))
    assert "P1 预警只支持" in exc.value.reason


def test_compile_rule_spec_rejects_unparsable_plan():
    from smartbi.gold.restaurant.plan_alert import compile_rule_spec

    with pytest.raises(RuleUnavailable) as exc:
        compile_rule_spec(_rule(plan_json="{not json"))
    assert "无法解析" in exc.value.reason


# ══════════════════════════════════════════════════════════════════════
# E. seed CLI 校验闸 (人工审核路径上的最后一道防线)
# ══════════════════════════════════════════════════════════════════════

def _load_seed_cli():
    import importlib.util
    from pathlib import Path

    here = Path(__file__).resolve()
    repo = here.parents[3]
    path = repo / "scripts" / "restaurant-plan-alert-seed.py"
    spec = importlib.util.spec_from_file_location("_seed_cli", path)
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


_GOOD_ENTRY = {
    "rule_code": "monthly_revenue_drop",
    "rule_name": "营收环比下滑预警",
    "query_text": "这个月营收比上个月怎么样",
    "code": "RESTAURANT_OPS_SALES_SUMMARY",
    "metric_path": "comparison.revenue_change_pct",
    "threshold_op": "lt",
    "threshold_value": -15,
    "severity": "warning",
}


def test_seed_cli_accepts_a_well_formed_rule():
    cli = _load_seed_cli()
    row, reason = cli._validate(dict(_GOOD_ENTRY), "RES_TEST")
    assert reason is None, reason
    assert row["rule_code"] == "monthly_revenue_drop"
    assert row["plan_version"] == "restaurant-query-plan-v2"
    # code -> default_seed_plan, 时间必须留空 (相对/待澄清), 不能是具体日期
    assert row["plan_json"]["time_range"] is None


def test_seed_cli_rejects_resolved_dates_in_plan():
    """硬约束 1: 存了具体日期就会在第二天对着旧窗口发预警."""
    cli = _load_seed_cli()
    entry = dict(_GOOD_ENTRY)
    entry.pop("code")
    entry["plan"] = {
        "intent": "RESTAURANT_OPS_SALES_SUMMARY",
        "date_range": ["2026-07-01", "2026-07-20"],
    }
    row, reason = cli._validate(entry, "RES_TEST")
    assert row is None
    assert "已解析日期" in reason


@pytest.mark.parametrize(
    "mutation,fragment",
    [
        ({"rule_code": ""}, "缺少 rule_code"),
        ({"query_text": ""}, "缺少 query_text"),
        ({"threshold_op": "between"}, "threshold_op"),
        ({"severity": "info"}, "severity"),          # info 会被 Java 桥接跳过
        ({"threshold_value": "很多"}, "threshold_value"),
        ({"metric_path": "kpis.0.rawValue"}, "白名单"),
        ({"code": "RESTAURANT_OPS_WASTAGE_TOP"}, "P1 预警只支持"),
    ],
)
def test_seed_cli_rejects_bad_entries(mutation, fragment):
    cli = _load_seed_cli()
    entry = dict(_GOOD_ENTRY)
    entry.update(mutation)
    row, reason = cli._validate(entry, "RES_TEST")
    assert row is None, f"应当拒收: {mutation}"
    assert fragment in reason
