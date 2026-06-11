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

Direction-word checking (A6):
    infer_sign_from_prose(prose, num_pos, window) -> Optional[bool]

    Scans a character window around a number in prose for direction words (涨/跌/升/降/增/减).
    Returns True  if prose implies positive direction (涨/升/增/上升/上涨/增长/提升/改善/好转).
    Returns False if prose implies negative direction (跌/降/减/下降/下跌/下滑/减少/萎缩/恶化).
    Returns None  if no direction word found in the window.

No external imports beyond typing and re — pure function, no I/O, no LLM, no DB.
"""
import re
from typing import Optional

# ---------------------------------------------------------------------------
# Direction-word sets (A6) — used for growth/diff claim direction validation
# ---------------------------------------------------------------------------

# Words that imply the associated number is POSITIVE (value > 0)
_POSITIVE_WORDS = frozenset({
    "涨", "升", "增", "增长", "增加", "上升", "上涨", "上调", "提升", "提高",
    "好转", "改善", "回升", "走高", "攀升", "走强", "增幅", "涨幅",
})
# Words that imply the associated number is NEGATIVE (value < 0)
_NEGATIVE_WORDS = frozenset({
    "跌", "降", "减", "下降", "下跌", "下滑", "减少", "萎缩", "恶化",
    "走低", "收窄", "缩减", "亏损", "减幅", "跌幅", "回落", "下调",
})
# Scan window (chars before and after the number position)
_DIRECTION_WINDOW = 30


def infer_sign_from_prose(prose: str, num_pos: int, window: int = _DIRECTION_WINDOW) -> Optional[bool]:
    """Scan a character window around num_pos in prose for Chinese direction words.

    Returns:
        True   — window contains a positive-direction word (implies value > 0)
        False  — window contains a negative-direction word (implies value < 0)
        None   — no direction word found in the window (cannot infer sign)

    When both positive and negative words appear in the window, no determination
    is made (returns None) to avoid false positives from complex constructions.
    """
    start = max(0, num_pos - window)
    end = min(len(prose), num_pos + window)
    snippet = prose[start:end]

    # Check multi-char words first (longer patterns take priority)
    found_pos = any(w in snippet for w in _POSITIVE_WORDS if len(w) > 1)
    found_neg = any(w in snippet for w in _NEGATIVE_WORDS if len(w) > 1)

    # Fall back to single-char keywords only if no multi-char match yet
    if not found_pos:
        found_pos = any(w in snippet for w in _POSITIVE_WORDS if len(w) == 1)
    if not found_neg:
        found_neg = any(w in snippet for w in _NEGATIVE_WORDS if len(w) == 1)

    if found_pos and found_neg:
        return None  # ambiguous — don't flag
    if found_pos:
        return True
    if found_neg:
        return False
    return None  # no direction word found


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
