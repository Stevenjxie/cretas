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
from typing import Any, List, Optional

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


def overall_ratio_facts(df: Any) -> List[dict]:
    """从 df 的损益行项目算"整体"口径单值比率 (毛利率/净利率), 返回 metrics-shaped dicts
    喂给对账器 —— 这是 Python 自己算的确定性指标 (无需 Java/RPC)。

    安全铁律 (算错比不算更糟): 只在能**唯一**识别营收/成本/净利行时算; 任一关键词匹配到
    0 行或 >1 行 → 视为歧义, 整体放弃返回 []。配合 _number_after 的干净边界 guard,
    只会纠正"毛利率38%"这类整体口径提及, 不碰"门店甲毛利率38%"。
    """
    try:
        import numpy as np
        import pandas as pd
    except Exception:
        return []
    if df is None or getattr(df, "empty", True):
        return []
    try:
        text_cols = df.select_dtypes(include=["object"]).columns
        numeric_cols = df.select_dtypes(include=[np.number]).columns.tolist()
        if len(text_cols) == 0 or not numeric_cols:
            return []
        labels = df[text_cols[0]].astype(str).str.strip()

        def _row_total(keywords: List[str]) -> Optional[float]:
            pat = "|".join(re.escape(k) for k in keywords)
            mask = labels.str.contains(pat, regex=True, na=False)
            idxs = mask[mask].index
            if len(idxs) != 1:            # 0 行 → 无; >1 行 → 歧义 → 放弃
                return None
            row = df.loc[idxs[0], numeric_cols]
            vals = [float(v) for v in row if pd.notna(v) and isinstance(v, (int, float, np.number))]
            return sum(vals) if vals else None

        rev = _row_total(["营业收入", "主营业务收入"])
        if rev is None or rev <= 0:
            return []
        facts: List[dict] = []
        net = _row_total(["净利润", "利润总额"])
        if net is not None:
            facts.append({"name": "净利率", "value": round(net / rev * 100, 2), "unit": "%", "success": True})
        cost = _row_total(["营业成本", "主营业务成本"])
        if cost is not None:
            facts.append({"name": "毛利率", "value": round((rev - cost) / rev * 100, 2), "unit": "%", "success": True})
        return facts
    except Exception:
        return []


def overall_factory_facts(df: Any) -> List[dict]:
    """从工厂生产数据 DataFrame 计算确定性指标 (出成率/成本结构/产量), 返回 metrics-shaped dicts。

    供 reconcile_insights 使用 —— 防止 LLM 在工厂洞察中瞎编成本/出成率数字。

    安全铁律 (与 overall_ratio_facts 一致): 只在能唯一识别对应行/列时算;
    任一关键词匹配到 0 行或 >1 行 → 视为歧义, 跳过该指标 (不整体放弃)。
    配合 _number_after 的干净边界 guard, 只纠正整体口径提及, 不动产品级局部值。

    适用 DataFrame 形态:
      - 工厂生产汇总表 (行=产品或批次, 数值列=投入产出/成本)
      - 成本结构表 (行=成本项, 列=金额)
    """
    try:
        import numpy as np
        import pandas as pd
    except Exception:
        return []
    if df is None or getattr(df, "empty", True):
        return []
    try:
        text_cols = df.select_dtypes(include=["object"]).columns
        numeric_cols = df.select_dtypes(include=[np.number]).columns.tolist()
        if len(text_cols) == 0 or not numeric_cols:
            return []
        labels = df[text_cols[0]].astype(str).str.strip()
        facts: List[dict] = []

        def _row_total(keywords: List[str]) -> Optional[float]:
            """按关键词找唯一行, 返回该行数值列之和。0行/多行→None(歧义)。"""
            pat = "|".join(re.escape(k) for k in keywords)
            mask = labels.str.contains(pat, regex=True, na=False)
            idxs = mask[mask].index
            if len(idxs) != 1:
                return None
            row = df.loc[idxs[0], numeric_cols]
            vals = [float(v) for v in row if pd.notna(v) and isinstance(v, (int, float, np.number))]
            return sum(vals) if vals else None

        def _col_sum(keywords: List[str]) -> Optional[float]:
            """按关键词找唯一列, 返回该列数值之和 (跳过 NaN)。0列/多列→None。"""
            matched = [c for c in numeric_cols if any(k in str(c) for k in keywords)]
            if len(matched) != 1:
                return None
            col_vals = pd.to_numeric(df[matched[0]], errors="coerce").dropna()
            return float(col_vals.sum()) if not col_vals.empty else None

        # ── 出成率 (yield rate): 产出 / 投入 * 100 ──────────────────────────
        # 只在行标签同时含投入和产出时算; 否则尝试列名
        input_total = _row_total(["投入", "原料投入", "领料"]) or _col_sum(["投入", "input"])
        output_total = _row_total(["产出", "实际产出", "产量"]) or _col_sum(["产出", "output"])
        if input_total is not None and input_total > 0 and output_total is not None and output_total >= 0:
            yield_rate = round(output_total / input_total * 100, 2)
            facts.append({"name": "出成率", "value": yield_rate, "unit": "%", "success": True})

        # ── 材料成本占比 ────────────────────────────────────────────────────
        total_cost = _row_total(["总成本", "生产成本", "合计成本"]) or _col_sum(["total_cost", "总成本"])
        material_cost = _row_total(["材料成本", "物料成本", "原料成本"]) or _col_sum(["material_cost", "材料"])
        if total_cost is not None and total_cost > 0 and material_cost is not None:
            mat_ratio = round(material_cost / total_cost * 100, 2)
            facts.append({"name": "材料成本占比", "value": mat_ratio, "unit": "%", "success": True})

        # ── 人工成本占比 ────────────────────────────────────────────────────
        labor_cost = _row_total(["人工成本", "劳动力成本", "工时成本"]) or _col_sum(["labor_cost", "人工"])
        if total_cost is not None and total_cost > 0 and labor_cost is not None:
            labor_ratio = round(labor_cost / total_cost * 100, 2)
            facts.append({"name": "人工成本占比", "value": labor_ratio, "unit": "%", "success": True})

        # ── 良品率 ──────────────────────────────────────────────────────────
        good_qty = _row_total(["良品", "合格品", "合格数量"]) or _col_sum(["good_qty", "合格"])
        total_qty = _row_total(["总产量", "生产数量", "产量合计"]) or _col_sum(["total_qty", "总产"])
        if total_qty is not None and total_qty > 0 and good_qty is not None:
            good_rate = round(good_qty / total_qty * 100, 2)
            facts.append({"name": "良品率", "value": good_rate, "unit": "%", "success": True})

        return facts
    except Exception:
        return []


def _fmt(value: Decimal, unit: str) -> str:
    q = value.quantize(Decimal("0.01"), rounding=ROUND_HALF_UP)
    # 整数去小数尾
    s = str(q.to_integral_value()) if q == q.to_integral_value() else f"{q:,}"
    if q == q.to_integral_value():
        s = f"{int(q):,}"
    return f"{s}{unit}"


# 指标名前若紧跟非边界字 (尤其 CJK), 说明被限定了 (如"门店甲毛利率"=某店的, 非整体口径),
# 整体事实表不该改它 → 只在干净边界处的指标名才算"整体口径"命中, 避免误改某实体局部值。
_CLEAN_PREFIX = set(" 　\t\r\n。，、；：（）()[]【】{}/|<>《》\"'`-—~,.:;!?")


def _is_clean_prefix(ch: str) -> bool:
    return ch in _CLEAN_PREFIX or ch.isdigit()


def _number_after(text: str, name: str, start_from: int = 0):
    """在 name 后紧跟处取数字 (且 name 处于干净边界=整体口径)。

    返回 (decimal_value, span_start, span_end) 或 (None, -1, -1)。
    被前缀限定的 name (如 '门店甲毛利率' 里的 '毛利率') 跳过, 防误改某实体局部值。
    """
    search = start_from
    while True:
        idx = text.find(name, search)
        if idx < 0:
            return None, -1, -1
        if idx == 0 or _is_clean_prefix(text[idx - 1]):
            after_off = idx + len(name)
            m = _NUM_AFTER.match(text[after_off:])
            if m:
                raw = m.group(1).replace(",", "")
                try:
                    val = Decimal(raw)
                    if m.group(2):
                        val *= _UNIT_MULT[m.group(2)]
                    num_start = after_off + m.start(1)
                    num_end = after_off + (m.end(2) if m.group(2) else m.end(1))
                    return val, num_start, num_end
                except InvalidOperation:
                    pass
        search = idx + 1  # 本次出现被限定或无数字 → 试下一次出现


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
