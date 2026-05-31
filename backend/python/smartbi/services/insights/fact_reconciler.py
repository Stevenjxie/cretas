from __future__ import annotations
"""
事实对账器 (阶段2 — 确定性 grounding 的机器强制层).

阶段3 的"数字引用铁律"是提示词层的软约束 (LLM 多数会遵守, 但偶尔会misquote/编造)。
本模块是输出后的硬强制: 拿调用方传入的**确定性 metrics** (name/value/unit, 上游算好的)
当事实表, 对 LLM 输出做对账:
  - 指标无数据 (value None) 但 LLM 给了数字 → 加"需核实"注 + 降 confidence (哨兵)。
  - 指标有确定值 但 LLM 紧跟的数字偏差 >5% → 用确定值回填 (LLM misquote 纠正)。

安全设计 (避免误伤合法推导):
  - 只在指标**名称**作为子串出现、且紧跟 (≤少量分隔符) 一个数字时才动 —— 高精度低召回,
    宁可漏 (不动文本) 不可错 (corrupt 合法数字)。
  - metrics 为空/None → 完全 no-op (大量 ad-hoc 调用无 metrics, 零风险)。
  - 中文 万/亿 归一化 (D5) 再比较, 避免 "320万" vs 3200000 假偏差。
  - 幂等: 回填后数字已等于确定值, 再跑不会重复改; 注脚有 marker 不重复加。

FactBook 当前只吃 metrics; 未来可扩展吃 Java 确定性指标 / 纵向基线 (spec 阶段2 完整版)。
"""
import logging
import re
from dataclasses import dataclass, field
from decimal import Decimal, ROUND_HALF_UP, InvalidOperation
from typing import Any, Dict, List, Optional

logger = logging.getLogger(__name__)

_UNIT_MULT = {"万": Decimal(10000), "亿": Decimal(100000000)}
# 指标名后紧跟的数字: 容忍少量分隔符 (空格/冒号/是/为/约/¥/约等于), 然后一个数 + 可选 万/亿。
_NUM_AFTER = re.compile(r"^[\s:：=≈是为约￥¥]{0,4}(-?\d[\d,]*\.?\d*)\s*(万|亿)?")
_VERIFY_MARK = "需核实"      # 哨兵注脚 marker (幂等)
_REL_TOL = Decimal("0.05")   # 偏差阈值 5%
_TEXT_FIELDS = ("text", "recommendation", "executive_summary")


@dataclass
class Fact:
    name: str
    value: Optional[Decimal]   # None = 指标无数据 (哨兵)
    unit: str = ""


@dataclass
class FactBook:
    facts: List[Fact] = field(default_factory=list)

    def __bool__(self) -> bool:
        return bool(self.facts)


def _to_decimal(v: Any) -> Optional[Decimal]:
    if v is None:
        return None
    try:
        return Decimal(str(v))
    except (InvalidOperation, ValueError, TypeError):
        return None


def build_factbook_from_metrics(metrics: Optional[List[dict]]) -> FactBook:
    """从调用方的结构化 metrics 建事实表。success=False / value 缺失 → value=None (哨兵)。"""
    fb = FactBook()
    for m in (metrics or []):
        if not isinstance(m, dict):
            continue
        name = (m.get("name") or m.get("metric") or "").strip()
        if not name or len(name) < 2:
            continue
        value = _to_decimal(m.get("value")) if m.get("success") else None
        fb.facts.append(Fact(name=name, value=value, unit=str(m.get("unit") or "")))
    return fb


def _fmt(value: Decimal, unit: str) -> str:
    q = value.quantize(Decimal("0.01"), rounding=ROUND_HALF_UP)
    # 整数去小数尾
    s = str(q.to_integral_value()) if q == q.to_integral_value() else f"{q:,}"
    if q == q.to_integral_value():
        s = f"{int(q):,}"
    return f"{s}{unit}"


def _number_after(text: str, name: str, start_from: int = 0):
    """在 name 后紧跟处取数字。返回 (decimal_value, span_start, span_end) 或 (None, -1, -1)。"""
    idx = text.find(name, start_from)
    if idx < 0:
        return None, -1, -1
    after_off = idx + len(name)
    m = _NUM_AFTER.match(text[after_off:])
    if not m:
        return None, -1, -1
    raw = m.group(1).replace(",", "")
    try:
        val = Decimal(raw)
    except InvalidOperation:
        return None, -1, -1
    if m.group(2):
        val *= _UNIT_MULT[m.group(2)]
    num_start = after_off + m.start(1)
    num_end = after_off + (m.end(2) if m.group(2) else m.end(1))
    return val, num_start, num_end


def _rel_diff(a: Decimal, b: Decimal) -> Decimal:
    denom = max(abs(a), abs(b))
    if denom == 0:
        return Decimal(0)
    return abs(a - b) / denom


def _reconcile_text(text: str, fb: FactBook) -> "tuple[str, bool, bool]":
    """返回 (新文本, 是否回填纠正过, 是否命中哨兵)。

    哨兵注脚统一追加到文本末尾 (不内插, 避免把 '8.5%' 切成 '8.5(注)%')。
    回填只替换数字本体, 保留原文单位/符号。
    """
    corrected = False
    flagged: List[str] = []
    for f in fb.facts:
        if not f.name or f.name not in text:
            continue
        llm_num, ns, ne = _number_after(text, f.name)
        if llm_num is None:
            continue
        if f.value is None:
            # 哨兵: 该指标无数据但 LLM 给了数 → 收集, 末尾统一加注
            if f.name not in flagged:
                flagged.append(f.name)
            continue
        # 有确定值: 偏差 >5% → 回填确定值 (幂等: 相等则不动)
        if _rel_diff(llm_num, f.value) > _REL_TOL:
            replacement = _fmt(f.value, "")
            logger.info("[reconcile] backfill %s: LLM=%s → fact=%s", f.name, llm_num, f.value)
            text = text[:ns] + replacement + text[ne:]
            corrected = True

    sentinel_hit = False
    if flagged and _VERIFY_MARK not in text:  # 幂等: 已有注脚则不重复加
        text = f"{text}（注：{'、'.join(flagged)}数据不足，相关数字{_VERIFY_MARK}）"
        sentinel_hit = True
    return text, corrected, sentinel_hit


def reconcile_insights(insights: List[dict], metrics: Optional[List[dict]]) -> List[dict]:
    """对账入口: 用 metrics 事实表强制 LLM 输出的数字。metrics 空 → 原样返回 (no-op)。"""
    fb = build_factbook_from_metrics(metrics)
    if not fb or not insights:
        return insights
    for ins in insights:
        if not isinstance(ins, dict):
            continue
        touched_sentinel = False
        for fld in _TEXT_FIELDS:
            val = ins.get(fld)
            if isinstance(val, str) and val:
                new, corrected, sentinel = _reconcile_text(val, fb)
                if new != val:
                    ins[fld] = new
                touched_sentinel = touched_sentinel or sentinel
        if touched_sentinel:
            try:
                ins["confidence"] = min(float(ins.get("confidence", 0.8) or 0.8), 0.5)
            except (TypeError, ValueError):
                ins["confidence"] = 0.5
    return insights
