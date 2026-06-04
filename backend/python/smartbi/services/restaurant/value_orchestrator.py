from __future__ import annotations

"""#56 价值可视化回馈回路 — 价值快照计算编排器。

把 financial_data + shrinkage_report 串成: DiagnosticsEngine.run → diagnoses
→ ValueSignalExtractor → signals + counts。供 hook / cron / API refresh 复用。

不直接 reach into 引擎内部 — 用 DiagnosticsEngine 公共 API + value_signal_extractor。
计算口径与 analyzer._extract_financial_metrics 一致 (food_cost_ratio / labor_cost_ratio
百分比 + cost_rigidity 仅营收下滑 >5% 才算)。
"""

import logging
from typing import Any, Optional

from smartbi.services.restaurant.value_signal_extractor import (
    ValueSignal,
    extract_from_diagnosis,
)
from smartbi.shared.diagnostics_engine import DiagnosticsEngine

logger = logging.getLogger(__name__)


def _safe_float(v: Any) -> Optional[float]:
    if v is None:
        return None
    try:
        return float(v)
    except (TypeError, ValueError):
        return None


def _build_metric_dict(financial_data: dict[str, Any]) -> tuple[dict[str, float], Optional[float]]:
    """从 financial_data 算 DiagnosticsEngine 消费的 snake_case 比率 dict。

    Returns (metric_dict, revenue_current)。镜像 analyzer._extract_financial_metrics
    的比率计算 (但只取诊断引擎需要的 ratio metrics, 不重建整个 FinancialMetrics)。
    """
    current = financial_data.get("current") or financial_data or {}
    previous = financial_data.get("previous")

    revenue = _safe_float(current.get("revenue"))
    food_cost = _safe_float(current.get("food_cost"))
    labor_cost = _safe_float(current.get("labor_cost"))

    metric_dict: dict[str, float] = {}
    if revenue is not None and revenue > 0:
        if food_cost is not None:
            metric_dict["food_cost_ratio"] = food_cost / revenue * 100
        if labor_cost is not None:
            metric_dict["labor_cost_ratio"] = labor_cost / revenue * 100

    # cost_rigidity: 仅营收下滑 >5% 才算 (与 analyzer / cost_rigidity section 一致)。
    if previous and revenue is not None and revenue > 0:
        prev_revenue = _safe_float(previous.get("revenue"))
        prev_labor = _safe_float(previous.get("labor_cost"))
        if prev_revenue and prev_revenue > 0:
            rev_change = (revenue - prev_revenue) / prev_revenue
            if rev_change < -0.05 and rev_change != 0 and prev_labor and prev_labor > 0 and labor_cost is not None:
                labor_change = (labor_cost - prev_labor) / prev_labor
                metric_dict["cost_rigidity"] = labor_change / rev_change

    return metric_dict, revenue


# DiagnosticsEngine 实例按 sub_sector 缓存 (加载 benchmark YAML 有成本)。
_engines: dict[str, DiagnosticsEngine] = {}


def _get_engine(sub_sector: str) -> DiagnosticsEngine:
    engine = _engines.get(sub_sector)
    if engine is None:
        engine = DiagnosticsEngine(domain="restaurant", sub_sector=sub_sector)
        _engines[sub_sector] = engine
    return engine


# higher-is-worse 比率指标 → 与 value_signal_extractor._RATIO_SAVINGS_METRICS 对齐。
# DiagnosticsEngine 仅在指标"突破 benchmark 区间"时返回 Diagnosis (区间内即使高于
# 中位也算"正常"不返回)。但价值回馈口径要的是"降到中位可省多少", 即使仍在区间内。
# 故这里直接用 benchmark median 算改善空间 (delta_pp = actual - median, >0 才报),
# 不依赖引擎的区间过滤。引擎仍用于 critical_count / rx_actions / cost_rigidity 处方。
_RATIO_MEDIAN_METRICS = ("food_cost_ratio", "labor_cost_ratio", "discount_rate")


def _benchmark_median(engine: DiagnosticsEngine, metric_key: str) -> Optional[float]:
    median = (
        engine._benchmarks.get("metrics", {})  # noqa: SLF001 — read-only median lookup
        .get(metric_key, {})
        .get("median")
    )
    return _safe_float(median)


def compute_signals_from_inputs(
    financial_data: dict[str, Any],
    sub_sector: str = "火锅",
    shrinkage_report: Optional[dict[str, Any]] = None,
) -> dict[str, Any]:
    """计算价值信号 + 元计数。

    Args:
        financial_data: {current:{revenue, food_cost, labor_cost, ...}, previous:{...}}。
        sub_sector: 餐饮子行业 (benchmark 选择)。
        shrinkage_report: ShrinkageReport.to_dict() 或 None。

    Returns:
        {signals: [ValueSignal], diagnosisCount, criticalCount, rxActionCount}。
    """
    metric_dict, revenue_current = _build_metric_dict(financial_data or {})

    engine_diagnoses_dicts: list[dict[str, Any]] = []  # 引擎返回的突破诊断 (含 rxActions)
    savings_diagnoses_dicts: list[dict[str, Any]] = []  # 比率改善空间 (median 口径)
    critical_count = 0
    rx_action_count = 0
    engine: Optional[DiagnosticsEngine] = None

    if metric_dict:
        try:
            engine = _get_engine(sub_sector)
            diagnoses = engine.run(metric_dict)
            engine_diagnoses_dicts = [d.to_dict() for d in diagnoses]
            critical_count = sum(1 for d in diagnoses if d.severity == "critical")
            rx_action_count = sum(len(d.rx_actions) for d in diagnoses)
        except Exception as e:  # noqa: BLE001
            logger.warning("[value-orchestrator] diagnostics run failed: %s", e)
            engine = None

    # 比率改善空间: 用 benchmark median 算 delta_pp (区间内也算), 不依赖引擎过滤。
    if engine is not None and metric_dict:
        for metric_key in _RATIO_MEDIAN_METRICS:
            actual = metric_dict.get(metric_key)
            if actual is None:
                continue
            median = _benchmark_median(engine, metric_key)
            if median is None:
                continue
            delta_pp = actual - median
            if delta_pp > 0:  # 高于中位 = 有改善空间
                savings_diagnoses_dicts.append({
                    "metricKey": metric_key,
                    "actualValue": actual,
                    "benchmarkMedian": median,
                    "deltaPp": round(delta_pp, 4),
                    "severity": "info",
                    "rxActions": [],  # rxActions 已由引擎诊断携带, 这里不重复
                })

    # signals = 比率改善空间 (median 口径) + 引擎诊断的 rxActions/损溢。
    # 注意 engine_diagnoses_dicts 里 food/labor 即便返回也走 deltaPp×revenue,
    # 但引擎对区间内的不返回 → 用 savings_diagnoses_dicts 覆盖; 引擎诊断只贡献
    # rxActions (cost_rigidity 等)。合并时避免比率信号重复: engine 诊断里若含
    # _RATIO_SAVINGS 指标则跳过其比率信号 (用 median 口径), 只取其 rxActions。
    rx_only_dicts = []
    for d in engine_diagnoses_dicts:
        mk = d.get("metricKey") or d.get("metric_key")
        if mk in _RATIO_MEDIAN_METRICS:
            # 比率信号交给 savings_diagnoses_dicts; 这里仅保留 rxActions
            rx_only_dicts.append({"metricKey": mk, "deltaPp": 0.0, "severity": "info",
                                  "rxActions": d.get("rxActions") or []})
        else:
            rx_only_dicts.append(d)

    signals: list[ValueSignal] = extract_from_diagnosis(
        savings_diagnoses_dicts + rx_only_dicts,
        shrinkage_report=shrinkage_report,
        revenue_current=revenue_current,
    )

    return {
        "signals": signals,
        "diagnosisCount": len(engine_diagnoses_dicts),
        "criticalCount": critical_count,
        "rxActionCount": rx_action_count,
    }
