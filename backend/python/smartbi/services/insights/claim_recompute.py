"""
claim_recompute.py — C1.1 claims-pinning 纯函数

服务端按 raw series 重算 LLM 声明的真值，消灭数值幻觉和实体错位。

Contract:
    recompute_claim(entity, stat_type, series_values, series_labels) -> Optional[float]

    Returns the true value of (entity, stat_type) recomputed from the raw series;
    returns None if entity not in labels, stat_type unknown, or series empty/total<=0.

stat_type semantics:
    value        — entity's raw value
    share        — entity_value / total * 100
    complement   — 100 - entity_share
    ratio        — entity_value / min_value
    top2_share   — (sum of top-2 largest values) / total * 100  (entity ignored)
    diff         — entity_value - min_value
    growth       — (last - first) / abs(first) * 100  (entity ignored, time series)
    count        — len(series_values)  (entity ignored)

No external imports beyond typing — pure function, no I/O, no LLM, no DB.
"""
from typing import Optional


def recompute_claim(
    entity: Optional[str],
    stat_type: str,
    series_values: list,
    series_labels: list,
) -> Optional[float]:
    """Recompute the true value of an LLM claim from raw chart series data.

    Args:
        entity: The entity name (e.g. "堂食"). May be None for entity-agnostic stats
                (growth, count, top2_share).
        stat_type: One of {value, share, top2_share, complement, ratio, diff, growth, count}.
        series_values: Raw numeric values from the chart series.
        series_labels: String labels corresponding 1:1 with series_values.

    Returns:
        Recomputed float value, or None if inputs are invalid / stat_type unknown.
    """
    # Guard: empty series
    if not series_values:
        return None

    n = len(series_values)

    # --- Entity-agnostic stats (entity may be None) ---

    if stat_type == "count":
        return float(n)

    if stat_type == "growth":
        # (last - first) / abs(first) * 100
        first = series_values[0]
        last = series_values[-1]
        if first == 0:
            return None  # division by zero
        return (last - first) / abs(first) * 100.0

    if stat_type == "top2_share":
        total = sum(series_values)
        if total <= 0:
            return None
        top2 = sum(sorted(series_values, reverse=True)[:2])
        return top2 / total * 100.0

    # --- Entity-required stats ---

    # Resolve entity index
    if entity is None or entity not in series_labels:
        return None

    idx = series_labels.index(entity)
    entity_value = series_values[idx]

    if stat_type == "value":
        return float(entity_value)

    total = sum(series_values)
    if total <= 0:
        return None

    if stat_type == "share":
        return entity_value / total * 100.0

    if stat_type == "complement":
        entity_share = entity_value / total * 100.0
        return 100.0 - entity_share

    min_value = min(series_values)

    if stat_type == "ratio":
        if min_value == 0:
            return None  # division by zero
        return entity_value / min_value

    if stat_type == "diff":
        return entity_value - min_value

    # Unknown stat_type
    return None
