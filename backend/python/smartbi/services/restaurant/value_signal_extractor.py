from __future__ import annotations

"""#56 价值可视化回馈回路 — 价值信号提取器。

从诊断引擎已算出的结果 (Diagnosis / ShrinkageReport / RxAction) 提取省钱/改善
金额, 归一成 ValueSignal 列表。这是与 DiagnosticsEngine / ShrinkageEngine 交互
的唯一入口 (snapshot service 只调本模块, 不直接 reach into 引擎内部)。

诚实规则 (per spec §核心诚实规则 + .claude/rules/fool-proof-design R1):
  - 金额无法计算 → amount = None (禁用 0 填 null, 保留 missing-vs-zero 区分)。
  - 信号区分 "本月实测" (measured) vs "预估" (estimate, 含年化)。
  - 只在指标真的偏差超标 (delta_pp > 0 且 higher-is-worse) 时产生节省信号;
    指标低于中位 (是好事) 不产生信号。

信号口径 (per spec §价值信号模型):
  | 信号             | 来源                          | 口径                | kind     |
  |------------------|-------------------------------|---------------------|----------|
  | labor_rigidity   | labor_cost_ratio Diagnosis    | revenue*deltaPp/100 | estimate |
  | food_cost_savings| food_cost_ratio Diagnosis     | revenue*deltaPp/100 | estimate |
  | discount_savings | discount_rate Diagnosis       | revenue*deltaPp/100 | estimate |
  | shrinkage_variance| ShrinkageReport.totalVariance| 直接用 (正超标)     | measured |
  | rx_action        | RxAction.expectedImpact 正则  | ¥(K=千/W=万)        | estimate |

WAVE2 依赖 (NOT built now, 留 stub):
  - extract_from_wastage_agg(): 读 agg_restaurant_daily_ops (损耗按人归因),
    依赖 w2-wastage 分支合并 → 见下方 stub。
  - price_deterrence 信号: 依赖 w2-price-deterrence 分支 → 列 + extractor 待后续。
"""

import logging
import re
from dataclasses import dataclass
from typing import Any, Optional

logger = logging.getLogger(__name__)


# ── ValueSignal ───────────────────────────────────────────


@dataclass
class ValueSignal:
    """单个价值信号。

    Attributes:
        signal: 机器标识 (labor_rigidity / food_cost_savings / discount_savings /
            shrinkage_variance / rx_action)。
        label: 给客户看的中文标签。
        amount: 金额 (元)。None = 暂无数据 (禁用 0 填 null)。
        kind: "estimate" (预估, 含年化) | "measured" (本月实测)。
        period: "month" | "annual"。
    """
    signal: str
    label: str
    amount: Optional[float]
    kind: str       # "estimate" | "measured"
    period: str     # "month" | "annual"

    def to_dict(self) -> dict[str, Any]:
        return {
            "signal": self.signal,
            "label": self.label,
            "amount": self.amount,   # 保留 None — 不替换成 0
            "kind": self.kind,
            "period": self.period,
        }


# ── 金额正则 ──────────────────────────────────────────────

# 匹配 ¥/￥ 前缀的金额, 支持千分位逗号 + 小数 + K(千)/W|万(万) 后缀。
# 例: ¥3.2K / ¥1.5W / ¥5万 / ¥18,349 / ￥4K
_AMOUNT_PATTERN = re.compile(
    r"[¥￥]\s*([\d,]+(?:\.\d+)?)\s*([KkWw万]?)"
)


def parse_amount_token(text: Optional[str]) -> Optional[float]:
    """从自然语言里提取第一个 ¥/￥ 金额 → float (元)。

    K/k → ×1000; W/w/万 → ×10000; 无后缀 → 原值。无 ¥ 前缀 → None
    (避免误抓百分比/数量, 如 "BOM 精度 ±15%" / "cost_rigidity 0.561")。

    Returns:
        金额 (元) 或 None (无可识别金额)。
    """
    if not text:
        return None
    m = _AMOUNT_PATTERN.search(text)
    if not m:
        return None
    num_str, suffix = m.group(1), m.group(2)
    try:
        value = float(num_str.replace(",", ""))
    except ValueError:
        return None
    if suffix in ("K", "k"):
        value *= 1000.0
    elif suffix in ("W", "w", "万"):
        value *= 10000.0
    return value


def extract_rx_action_impact(rx: dict[str, Any]) -> Optional[float]:
    """从一个 RxAction dict 的 expectedImpact / expected_impact 提取金额。

    兼容 to_dict() 风格 (expectedImpact camelCase) 和 dataclass 风格
    (expected_impact snake_case)。无金额 → None。
    """
    impact = rx.get("expectedImpact")
    if impact is None:
        impact = rx.get("expected_impact")
    return parse_amount_token(impact if isinstance(impact, str) else None)


# ── 指标改善空间口径 (delta_pp × revenue) ─────────────────

# higher-is-worse 比率指标 (实际 > 中位 = 超标, 降到中位即省钱)。
# metricKey → (signal, label)。
_RATIO_SAVINGS_METRICS: dict[str, tuple[str, str]] = {
    "labor_cost_ratio": ("labor_rigidity", "人工刚性节省"),
    "food_cost_ratio": ("food_cost_savings", "食材成本改善空间"),
    "discount_rate": ("discount_savings", "折扣率改善空间"),
    "discount_rate_high": ("discount_savings", "折扣率改善空间"),
}


def _ratio_savings(
    diagnosis: dict[str, Any], revenue_current: Optional[float]
) -> Optional[float]:
    """比率指标改善空间 = revenue * delta_pp / 100。

    delta_pp 是"实际 - 中位" 百分点 (e.g. 食材 46% vs 中位 42% → delta_pp=4.0)。
    revenue 缺失 → None (禁降级填 0)。delta_pp 已由 caller 校验 > 0。
    """
    if revenue_current is None:
        return None
    try:
        revenue = float(revenue_current)
    except (TypeError, ValueError):
        return None
    delta_pp_raw = diagnosis.get("deltaPp")
    if delta_pp_raw is None:
        delta_pp_raw = diagnosis.get("delta_pp")
    if delta_pp_raw is None:
        return None
    try:
        delta_pp = float(delta_pp_raw)
    except (TypeError, ValueError):
        return None
    return round(revenue * delta_pp / 100.0, 2)


# ── 主入口 ────────────────────────────────────────────────


def extract_from_diagnosis(
    diagnoses: list[dict[str, Any]],
    shrinkage_report: Optional[dict[str, Any]],
    revenue_current: Optional[float],
) -> list[ValueSignal]:
    """从诊断结果 + 损溢报告提取所有价值信号 (月度口径)。

    Args:
        diagnoses: DiagnosticsEngine.run() 的 [d.to_dict() ...] 列表。
        shrinkage_report: ShrinkageReport.to_dict() 或 None。
        revenue_current: 当期营收 (元), 用于比率指标 delta_pp × revenue;
            None → 比率信号 amount=None (禁降级)。

    Returns:
        ValueSignal 列表 (period="month")。年化口径由 snapshot service 折算。
    """
    signals: list[ValueSignal] = []

    for d in diagnoses or []:
        metric_key = d.get("metricKey") or d.get("metric_key")
        if metric_key in _RATIO_SAVINGS_METRICS:
            # 只在指标超标 (delta_pp > 0) 时产生节省信号; 低于中位是好事, 不报。
            delta_pp_raw = d.get("deltaPp")
            if delta_pp_raw is None:
                delta_pp_raw = d.get("delta_pp")
            try:
                delta_pp = float(delta_pp_raw) if delta_pp_raw is not None else 0.0
            except (TypeError, ValueError):
                delta_pp = 0.0
            if delta_pp > 0:
                signal_id, label = _RATIO_SAVINGS_METRICS[metric_key]
                amount = _ratio_savings(d, revenue_current)
                signals.append(ValueSignal(
                    signal=signal_id, label=label, amount=amount,
                    kind="estimate", period="month",
                ))

        # RxAction 金额 (只收有金额的)
        rx_actions = d.get("rxActions") or d.get("rx_actions") or []
        for rx in rx_actions:
            if not isinstance(rx, dict):
                continue
            rx_amount = extract_rx_action_impact(rx)
            if rx_amount is not None:
                title = rx.get("title") or rx.get("id") or "处方行动"
                signals.append(ValueSignal(
                    signal="rx_action", label=f"处方预估·{title}",
                    amount=rx_amount, kind="estimate", period="month",
                ))

    # 档口损溢超标 (本月实测): 只报正超标 (actual > standard)。
    if shrinkage_report:
        variance_raw = (
            shrinkage_report.get("totalVarianceAmount")
            if "totalVarianceAmount" in shrinkage_report
            else shrinkage_report.get("total_variance_amount")
        )
        if variance_raw is not None:
            try:
                variance = float(variance_raw)
            except (TypeError, ValueError):
                variance = None
            if variance is not None and variance > 0:
                signals.append(ValueSignal(
                    signal="shrinkage_variance", label="档口损溢超标 (本月实测)",
                    amount=round(variance, 2), kind="measured", period="month",
                ))

    return signals


# ── WAVE2 依赖 stub (NOT built now) ───────────────────────


def extract_from_wastage_agg(*args: Any, **kwargs: Any) -> list[ValueSignal]:
    """损耗按人归因价值信号 (before/after, 本月实测)。

    # TODO(#54): wire after w2-wastage merges — reads agg_restaurant_daily_ops.
    依赖 w2-wastage 分支合并后才有数据源 (agg_restaurant_daily_ops 损耗按人
    归因列)。当前返回 [] 占位, 不报假数据。
    """
    return []
