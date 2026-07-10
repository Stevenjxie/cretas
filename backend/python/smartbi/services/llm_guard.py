"""LLM hallucination guard — detects numeric claims in an answer that
exceed the maximum value present in the provided aggregate context.

Primary mode observed in prod (Apr 24 2026, qhj_prod RES_3101_009):
  "Top 5 门店合计 3.4 亿元" on a dataset where real 总营收 ≈ 36M.
  ~9× inflation, pure hallucination from LLM extrapolating the 200-row
  sample rather than citing the authoritative agg block.

Two layers of defense:

  1. NUMERIC_GUARD_CLAUSE — appended to analyst system_role in chat.py.
     Instructs the model not to multiply/extrapolate beyond the aggregate
     context, and bans the "亿" unit unless the context actually contains
     a ≥100M value.

  2. detect_numeric_hallucination() — post-check on the completed answer.
     Extracts the max numeric value from the agg block, scans the answer
     for `\\d+亿` / `\\d+千万` patterns, flags if the claimed value
     exceeds max_agg × overshoot_factor (default 2×).

Callers decide what to do with a violation — log, attach a warning to the
response payload, strip the offending passage, etc. This module only
detects; it never rewrites the answer.
"""
from __future__ import annotations

import re
from dataclasses import dataclass, field
from typing import Any, Dict, Iterable, List, Optional, Tuple


NUMERIC_GUARD_CLAUSE = (
    "\n\n数字引用强制规则（违反将被视为错误答案）：\n"
    "1. 答案中每一个具体数字都必须能从提供的'全量数据聚合'或'数据概览'中直接读到，"
    "严禁乘法、外推、估算或'大约'式的推算。\n"
    "2. 不得在答案中提及超过上下文中最大'总计/合计/总营收'的数字。\n"
    "3. 禁止将万元/千元换算成'亿'，除非聚合段中明确出现 ≥100,000,000（1亿）的数字。\n"
    "4. 若用户问的指标未在上下文中出现，直接回答'此数据未提供'，不要自行估算。"
)


# Apr 25 2026 quality audit C-rec 7: Customers misinterpret numbers because the
# AI doesn't label them. Two specific bugs caught:
#   Q14: AI said "monthly anomaly peak ¥3.19M" but DB net was ¥2.29M (gross
#        vs net = 39% off). Customer made wrong cost decisions.
#   Q12: AI said "65.0%" but the basis was amount; bills% was 55.8%. Customer
#        thought it was bill share and miscalibrated channel investment.
# Fix: force every numeric mention to carry a basis label.
LABELING_GUARD_CLAUSE = (
    "\n\n数字标注强制规则（违反将被视为错误答案）：\n"
    "1. **金额数字必须标注口径**：写到 ¥/元 时必须紧跟 [毛] 或 [净]（毛=折扣前/含税"
    "/应收，净=折后/到账/实收），除非数据上下文已注明该数字是'营业额'/'应收'/'实收'/"
    "'到账'等明确口径词。\n"
    "   反面：'营业额 3,190,000 元'  正面：'营业额 3,190,000 元（毛/应收口径）'\n"
    "2. **百分比必须标注分母基准**：写到 % 时必须紧跟 [按营业额]/[按订单数]/[按笔数]/"
    "[按客流] 等基准说明，除非上下文已经显式说明该比例的分母。\n"
    "   反面：'堂食占 65.0%'  正面：'堂食占 65.0%（按营业额）'\n"
    "3. 当数据 dict 中有 _label / _basis / share_amount_pct / share_bills_pct 等字段时，"
    "必须采用其中文标注，不要省略；当 has_cost_data=false 或 inferred=true 时，必须在"
    "答案中明确说明'未含成本'或'推断分类'。\n"
    "4. 同一个比例如果同时有按金额和按笔数两种基准（例如 channel_analysis.share_pct 是按营业额，"
    "share_bills_pct 是按笔数），请同时陈述两个数字并标注，避免读者误读。"
)


# Apr 25 2026 quality audit C-rec 8+9: 9/14 audited template insights had no
# actionable recommendation. Customer reads "Top 5 dishes" but doesn't know
# what to do next. The Apr 23 Week 5 agent layer (Dashboard) scored 5/5 on
# action_rec by following spec §4.3 — concrete店名+数字+收益区间+前置条件.
# Port that pattern to chat-stream / drill-down / general-analysis prompts.
ACTION_REC_GUARD_CLAUSE = (
    "\n\n可执行建议强制规则（违反将被视为不合格回答）：\n"
    "1. 回答必须以可执行的建议结尾，不能仅描述数据。每条建议必须同时满足：\n"
    "   (a) 具体对象 — 指明 [具体店名/品类/菜品/员工/客群/渠道]，禁止笼统的'重点关注'/'加强管理';\n"
    "   (b) 数字化收益 — 含 [收益区间]，例如 '预计提升营业额 3-5%' / '节约成本 5-10万/月' /"
    " '降低退单率 2 个百分点';\n"
    "   (c) 前置条件 — 写明 [需先做什么]，例如 '先完成服务员推销话术培训' /"
    " '需要区域经理确认营销预算上限' / '上传当月折扣明细';\n"
    "   (d) 时间窗口 — 写明 [实施时长]，例如 '本月内' / '下季度' / '14 天试点'。\n"
    "2. 禁止输出空泛建议，下列措辞会被视为无效：\n"
    "   '建议优化经营策略' / '加强营销' / '提高服务质量' / '提升运营效率' /"
    " '扩大客源' / '密切关注' / '建立长效机制'。\n"
    "3. 若数据不足以支撑明确建议，则坦诚说明 '当前数据无法推荐具体行动，建议先 [补充 X 数据来源]'，\n"
    "   并指出补充什么样的字段或维度后即可给出建议。"
)


# Apr 26 2026 phase 5 (UX): tone/voice guidelines. Without this, LLM tail
# answers feel like form-letter "此数据未提供. 当前数据中没有X字段...建议
# 先补充Y来源" — same exact wording across 10+ queries, breaks user trust.
# This clause applies WITH the guards above (which require honest "no
# data" responses) — softens TONE without weakening the constraint.
USER_FRIENDLY_TONE_CLAUSE = (
    "\n\n答案语气要求 (UX 规则)：\n"
    "1. **避免照搬模板词**: 不要硬性使用 '此数据未提供' / '建议先补充 X 数据来源' 等"
    "刻板表达. 数据缺失时用更平易近人的话, 例如 '你的数据里有 X/Y/Z 这些维度, "
    "但缺 W 字段无法直接算 [指标], 我能告诉你的是: ...' \n"
    "2. **优先给现有数据能回答的**: 当无法直接回答时, 主动告知 '基于现有 X 数据, "
    "我可以告诉你 [类似维度回答]' — 不要只说不能做.\n"
    "3. **用 '你 / 我' 不用 '用户 / 系统'**: 增加亲近感, 体现是 '帮你分析' 不是 "
    "'输出报告'.\n"
    "4. **缺失说明 ≤50 字**: 不要长篇大论解释为什么缺数据, 用户已经知道了; 把"
    "篇幅留给'我现在能给你什么'.\n"
    "5. **Markdown 强约束**: 用 ## / ** / 列表分块, 不要纯段落; 关键数字加 **粗体**."
)


_HALLUC_PATTERN = re.compile(r"([-+]?\d+(?:\.\d+)?)\s*(亿|千万)")
_NUMBER_PATTERN = re.compile(r"([-+]?\d+(?:,\d{3})*(?:\.\d+)?)")

_OVERSHOOT_FACTOR = 2.0
_MIN_AGG_BASELINE = 10_000.0


def _unit_multiplier(unit: str) -> float:
    if unit == "亿":
        return 100_000_000.0
    if unit == "千万":
        return 10_000_000.0
    return 1.0


def extract_max_agg_value(agg_lines: Optional[Iterable[str]]) -> float:
    """Return the largest numeric value present in any of the agg lines.

    Commas are treated as thousands separators. Lines are formatted by the
    chat.py stream path like:
        "- 实收金额 总计: 29,498,641.23 (行数=140541)"
        "- Top by 门店: 青花椒=9,500,000.00, 大丸=2,000,000.00"
    Taking the max over every number on every line approximates the
    largest figure the LLM has been given — a safe upper bound for the
    "the answer should not exceed this" sanity check.
    """
    if not agg_lines:
        return 0.0
    max_v = 0.0
    for line in agg_lines:
        if not line:
            continue
        for m in _NUMBER_PATTERN.finditer(line):
            raw = m.group(1).replace(",", "")
            try:
                v = abs(float(raw))
            except ValueError:
                continue
            if v > max_v:
                max_v = v
    return max_v


def detect_numeric_hallucination(
    answer: str,
    agg_lines: Optional[Iterable[str]],
    *,
    overshoot: float = _OVERSHOOT_FACTOR,
) -> Optional[str]:
    """Scan `answer` for 亿/千万 claims that exceed max(agg_lines) × overshoot.

    Returns a human-readable Chinese violation string, or None if the
    answer is clean / the aggregates are too small to judge against.

    Overshoot default 2× catches the observed 9× inflation comfortably
    while staying clear of honest rounding (e.g. 9,500万 stated as 近1亿
    is 1.05× — within tolerance).
    """
    if not answer:
        return None

    max_agg = extract_max_agg_value(agg_lines)
    if max_agg < _MIN_AGG_BASELINE:
        return None

    violations: List[str] = []
    for m in _HALLUC_PATTERN.finditer(answer):
        value_raw, unit = m.group(1), m.group(2)
        try:
            claimed = float(value_raw) * _unit_multiplier(unit)
        except ValueError:
            continue
        if claimed > max_agg * overshoot:
            violations.append(
                f"{m.group(0)}（≈{claimed:,.0f} 元，聚合中最大值仅 {max_agg:,.0f} 元）"
            )

    if not violations:
        return None
    return "疑似数字幻觉（LLM 输出的金额超出聚合上下文范围）：" + "；".join(violations)


# =============================================================================
# FactReconciler — P2 综合分析 grounding hard-check (spec §4.4 / §5.5 / §5.6).
#
# Extends llm_guard (reuses detect_numeric_hallucination + extract_max_agg_value)
# instead of starting a new module. Reconciles the LLM's free-text answer against
# a FactBook's deterministic {fact_name: true_value} index.
#
# Safety design (per memory PR #337/#338 grounding + feedback_rules_first_llm_fallback):
#   - ONLY reconcile "已知指标名 紧跟 数字" explicit references —宁漏不错.
#   - No matching fact → no-op (never punish the LLM for an unknown number).
#   - NO fuzzy matching (no edit-distance, no partial). A metric is reconciled
#     only when its EXACT name substring is present AND a number follows within
#     a short window. Deviation > tol (default 5%) → backfill真值 + annotate +
#     lower confidence.
#   - Fabricated store/dish name detection: flag a name-looking token that the
#     answer presents as a 门店/菜名 but is NOT in the FactBook entity set. We do
#     this conservatively — only names the FactBook KNOWS the universe of (i.e.
#     when known_store_names is non-empty do we judge店-context names).
#
# Honesty labels (§5.6): detect "菜名"/"招牌菜" phrasing applied to 口味标签 → annotate.
# =============================================================================


# A number immediately (within ~12 chars) following a metric name. The window
# tolerates a unit/colon/label between name and number ("平均星级 4.79 星",
# "平均星级为 4.79", "总营业额 ¥2,064 万元").
_FACT_NUMBER_WINDOW = 14

# 宁漏不错 (spec §5.5): a number is only a CLAIM about the metric when it
# IMMEDIATELY follows the metric name with just connective chars between
# (space / colon / ¥ / 为约达是共计有 / brackets). A number separated by real
# text — "差评集中（64", "好评词…味道好（5998" — is NOT about this metric, so
# the loose 14-char window must not match it (that caused false corrections).
_CONNECTIVE_THEN_NUMBER = re.compile(
    r"^[\s:：=＝（()【】《》为约达是共计有的为¥￥$\"']{0,5}"
    r"([-+]?\d+(?:,\d{3})*(?:\.\d+)?)"
)

# Money-scale words the LLM may append; used to normalize 万/亿 back to base.
_SCALE_WORDS: List[Tuple[str, float]] = [
    ("亿", 100_000_000.0),
    ("千万", 10_000_000.0),
    ("万", 10_000.0),
]

# Phrasings that wrongly treat 口味/品质标签 as dish names (§5.6).
_DISH_NAME_MISLABELS = ["招牌菜", "招牌菜品", "明星菜", "这道菜", "该菜品", "畅销菜名"]

_ANNOT_TEMPLATE = "（数据核对：实际 {true}）"


def _pos_inside_longer(text: str, pos: int, name_len: int, longer_names) -> bool:
    """True if text[pos:pos+name_len] falls entirely within an occurrence of a
    longer, more-specific fact name.

    F3 fix: fact names collide as substrings — 平均星级 ⊂ VIP平均星级, and
    VIP平均星级 ⊂ 非VIP平均星级. Without this guard the broader fact's value
    re-matches the number sitting inside the more-specific name and "corrects"
    it falsely (VIP 4.50 → overall 4.79; 非VIP 4.83 → VIP 4.50). The number after
    such an occurrence belongs to the longer fact, so the shorter fact must skip it.
    """
    end = pos + name_len
    for longer in longer_names:
        start = 0
        while True:
            lpos = text.find(longer, start)
            if lpos < 0:
                break
            if lpos <= pos and end <= lpos + len(longer):
                return True
            start = lpos + 1
    return False
_ANNOT_FABRICATED = "[未在数据中找到该名称]"
_ANNOT_DISH_LABEL = "（提示：上述为口味/品质标签，非菜名）"


@dataclass
class ReconcileResult:
    annotated_answer: str
    violations: List[str] = field(default_factory=list)
    reconciled: bool = False
    confidence_adj: float = 0.0  # negative = lower confidence

    def to_dict(self) -> Dict[str, Any]:
        return {
            "violations": self.violations,
            "reconciled": self.reconciled,
            "confidence_adj": self.confidence_adj,
        }


def _normalize_claimed(num_str: str, trailing: str) -> Optional[float]:
    """Parse a claimed number + optional 万/亿 scale word to a base float."""
    try:
        base = float(num_str.replace(",", ""))
    except ValueError:
        return None
    for word, mult in _SCALE_WORDS:
        if trailing.startswith(word):
            return base * mult
    return base


class FactReconciler:
    """Reconcile LLM free-text output against a FactBook (grounding hard-check).

    Usage::

        rec = FactReconciler()
        answer, meta = rec.reconcile(llm_text, factbook)

    ``factbook`` is any object exposing ``to_facts_index() -> {name: value}``
    and ``known_store_names() -> [str]`` (FactBook satisfies this; a plain
    dict/list can be passed via the lower-level ``reconcile_index``).
    """

    def reconcile(
        self,
        answer: str,
        factbook: Any,
        *,
        tol: float = 0.05,
        agg_lines: Optional[Iterable[str]] = None,
    ) -> Tuple[str, Dict[str, Any]]:
        """Reconcile ``answer`` against ``factbook``. Returns (annotated_answer, meta)."""
        facts = {}
        known_stores: List[str] = []
        known_tags: List[str] = []
        try:
            facts = factbook.to_facts_index() or {}
        except Exception:
            facts = {}
        try:
            known_stores = factbook.known_store_names() or []
        except Exception:
            known_stores = []
        try:
            known_tags = factbook.known_tags() or []
        except Exception:
            known_tags = []

        res = self.reconcile_index(
            answer, facts,
            known_store_names=known_stores,
            known_tags=known_tags,
            tol=tol,
            agg_lines=agg_lines,
        )
        return res.annotated_answer, res.to_dict()

    def reconcile_index(
        self,
        answer: str,
        facts: Dict[str, float],
        *,
        known_store_names: Optional[List[str]] = None,
        known_tags: Optional[List[str]] = None,
        tol: float = 0.05,
        agg_lines: Optional[Iterable[str]] = None,
    ) -> ReconcileResult:
        """Lower-level reconcile against an explicit facts dict + name universe."""
        if not answer:
            return ReconcileResult(annotated_answer=answer or "")

        out = answer
        violations: List[str] = []
        confidence_adj = 0.0

        # 1. Per-fact exact-name + 紧跟数字 reconciliation (宁漏不错).
        #    Longest fact name first so "VIP平均星级" matches before "平均星级".
        for name in sorted(facts.keys(), key=len, reverse=True):
            true_v = facts[name]
            # Longer fact keys that contain this name as a substring (e.g.
            # "VIP平均星级" ⊃ "平均星级"). A number inside such a longer name is the
            # more-specific fact's claim; reconciling it here is a false correction.
            longer_names = [n for n in facts if n != name and name in n]
            out, hit, dev = self._reconcile_one_fact(
                out, name, true_v, tol, longer_names=longer_names)
            if hit and dev is not None:
                violations.append(
                    f"{name}: LLM 偏离真值 (实际 {self._fmt(true_v)}, 偏差 {dev * 100:.0f}%)"
                )
                confidence_adj -= 0.1

        # 2. Reuse the 亿/千万 hallucination sentinel against the agg context.
        #    agg_lines defaults to the rendered fact values so the upper-bound
        #    check still fires even without an explicit agg block.
        eff_agg = agg_lines if agg_lines is not None else [
            f"{k}: {v}" for k, v in facts.items()
        ]
        halluc = detect_numeric_hallucination(answer, eff_agg)
        if halluc:
            violations.append(halluc)
            confidence_adj -= 0.2

        # 3. Fabricated store-name detection — METADATA ONLY (宁漏不错).
        # Inline annotating store names on free-form prose is unreliable: a regex
        # can't robustly tell a real branch name from generic 店-words
        # (该店/跨门店/"64条（该店") across all LLM phrasings, and a wrong
        # "[未在数据中找到]" in the customer answer is worse than none. So we
        # record the suspicion as a violation (for monitoring / confidence) but
        # do NOT mutate the answer text. The numeric reconciliation above stays
        # inline (it is now precise — connective-adjacent + sentence-scoped).
        if known_store_names:
            _annotated, fab = self._flag_fabricated_stores(out, known_store_names)
            if fab:
                violations.extend(f"疑似编造门店名: {n}" for n in fab)
                confidence_adj -= 0.15

        # 4. Honesty-label: 口味标签 mislabeled as 菜名 (§5.6).
        out, mislabel = self._flag_dish_mislabel(out, known_tags or [])
        if mislabel:
            violations.append("检测到将口味/品质标签当作菜名的措辞，已加注。")
            confidence_adj -= 0.05

        return ReconcileResult(
            annotated_answer=out,
            violations=violations,
            reconciled=bool(violations),
            confidence_adj=confidence_adj,
        )

    # ---------------- internals ----------------

    def _reconcile_one_fact(
        self, text: str, name: str, true_v: float, tol: float,
        *, longer_names: Iterable[str] = (),
    ) -> Tuple[str, bool, Optional[float]]:
        """If ``name`` appears in text followed (within window) by a number that
        deviates from ``true_v`` by > tol, annotate it. Returns
        (new_text, name_was_present, deviation_or_None).

        Exact-name substring only. No fuzzy matching. The annotation is appended
        immediately after the claimed number so the真值 travels with the claim.
        """
        idx = text.find(name)
        if idx < 0:
            return text, False, None
        # Scan windows after each occurrence of the name. Only the FIRST claimed
        # number per occurrence is judged (宁漏不错 — avoid grabbing an unrelated
        # later number).
        search_from = 0
        worst_dev: Optional[float] = None
        result = text
        offset_shift = 0
        while True:
            pos = result.find(name, search_from)
            if pos < 0:
                break
            # F3: skip an occurrence sitting INSIDE a longer, more-specific fact
            # name (平均星级 within VIP平均星级; VIP平均星级 within 非VIP平均星级) —
            # that number is the longer fact's claim, not this one's.
            if _pos_inside_longer(result, pos, len(name), longer_names):
                search_from = pos + len(name)
                continue
            after = result[pos + len(name): pos + len(name) + _FACT_NUMBER_WINDOW]
            # 宁漏不错: only treat a number as this metric's claim if it
            # IMMEDIATELY follows the name (connective chars only). Numbers with
            # real text in between are about something else — skip them.
            m = _CONNECTIVE_THEN_NUMBER.match(after)
            if not m:
                search_from = pos + len(name)
                continue
            # trailing chars right after the number → possible 万/亿 scale word
            trailing = after[m.end(): m.end() + 2]
            claimed = _normalize_claimed(m.group(1), trailing)
            if claimed is None:
                search_from = pos + len(name)
                continue
            if true_v == 0:
                dev = 0.0 if claimed == 0 else 1.0
            else:
                dev = abs(claimed - true_v) / abs(true_v)
            # Magnitude gate (宁漏不错): a genuine mis-statement of this metric stays
            # in the same ballpark; a number ORDERS OF MAGNITUDE off is a DIFFERENT
            # quantity — a derived ratio ("总营业额是折扣金额合计的 1719%"), a
            # percentage, a count — that merely shares the metric's label inside a
            # comparative sentence. Rewriting it to the metric value is a category
            # error (张冠李戴), so skip. Real digit/rounding errors stay < 100×.
            if (true_v != 0 and claimed != 0
                    and max(abs(claimed), abs(true_v))
                    / min(abs(claimed), abs(true_v)) > 100):
                search_from = pos + len(name)
                continue
            if dev > tol:
                # 宁漏不错: only annotate when the number is a CLAIM about the
                # GLOBAL metric. Skip when the same sentence (before the metric)
                # carries a store/branch qualifier — a store-scoped number, e.g.
                # "…（虹口龙之梦店）差评集中（64" / "…日月光店…客单价694" — OR a
                # projection verb — a forecast delta, e.g. "约减少差评45条".
                # Rewriting either to the global fact is a FALSE correction.
                sb = max((result.rfind(c, 0, pos) for c in "。；！？!?\n"), default=-1)
                sentence = result[sb + 1: pos]
                if any(t in sentence for t in ("店", "馆", "门店")):
                    search_from = pos + len(name)
                    continue
                if any(v in sentence for v in (
                        "减少", "降低", "下降", "提升", "提高", "回升", "增长",
                        "预计", "可降", "可减", "将降", "下调", "上调", "目标")):
                    search_from = pos + len(name)
                    continue
                # Insert annotation right after the matched number.
                insert_at = pos + len(name) + m.end()
                annot = _ANNOT_TEMPLATE.format(true=self._fmt(true_v))
                result = result[:insert_at] + annot + result[insert_at:]
                worst_dev = dev if worst_dev is None else max(worst_dev, dev)
                search_from = insert_at + len(annot)
                offset_shift += len(annot)
            else:
                search_from = pos + len(name)
        return result, True, worst_dev

    def _flag_fabricated_stores(
        self, text: str, known: List[str]
    ) -> Tuple[str, List[str]]:
        """Flag store-like names in 门店-context that aren't in the known set.

        Conservative: we only look at tokens the answer EXPLICITLY frames as a
        store ("XXX店" / "门店 XXX") and annotate those NOT in ``known``. A name
        already in ``known`` is never flagged. We never invent名字 — only
        annotate the model's own text.
        """
        fabricated: List[str] = []
        known_set = set(known)
        # Brand tokens from known store names (first 3 chars before any paren).
        # A real store NAME carries a brand (青花椒…/鲜行者…); generic references
        # (该店/门店/跨门店) and regex noise ("64条（该店") do NOT — gate on this.
        brands = {
            re.split(r"[（(]", k)[0].strip()[:3]
            for k in known_set
            if len(re.split(r"[（(]", k)[0].strip()) >= 2
        }
        brands.discard("")
        # Match "<name>店" tokens (CJK run ending in 店, 2-20 chars before 店).
        store_pat = re.compile(r"([一-龥A-Za-z0-9·\-（）()]{2,20}店)")
        seen = set()
        for m in store_pat.finditer(text):
            cand = m.group(1)
            if cand in seen:
                continue
            seen.add(cand)
            # 宁漏不错: only consider candidates that carry a known brand prefix.
            # Real store NAMES do (青花椒…/鲜行者…); generic references
            # (该店/门店/其他门店/跨门店/个别门店) and regex noise ("64条（该店")
            # do NOT — never flag those as fabricated names.
            if brands and not any(b in cand for b in brands):
                continue
            # If the candidate is a substring of, or contains, a known store →
            # treat as known (handles "大融城店" vs "青花椒大融城店").
            if cand in known_set:
                continue
            if any(cand in k or k in cand for k in known_set):
                continue
            # 宁漏不错: 大众点评 review store names ("鲜行者X顺德小馆（虹口龙之梦店）")
            # and POS/gold names ("青花椒徐汇日月光店") are different datasets with
            # different conventions. Cross-check the parenthetical landmark against
            # known names before flagging, so a real store named differently across
            # datasets isn't falsely marked fabricated.
            mp = re.search(r"[（(]([^）)]{2,})[）)]?", cand)
            landmark = mp.group(1) if mp else None
            if landmark and any(landmark in k or k in landmark for k in known_set):
                continue
            fabricated.append(cand)
        # Annotate each fabricated name once (first occurrence).
        out = text
        for name in fabricated:
            pos = out.find(name)
            if pos >= 0 and _ANNOT_FABRICATED not in out[pos:pos + len(name) + 20]:
                insert_at = pos + len(name)
                out = out[:insert_at] + _ANNOT_FABRICATED + out[insert_at:]
        return out, fabricated

    def _flag_dish_mislabel(
        self, text: str, known_tags: List[str]
    ) -> Tuple[str, bool]:
        """If the answer uses 招牌菜/明星菜-style phrasing AND references one of the
        口味/品质标签 strings, append a one-time honesty annotation."""
        if not any(w in text for w in _DISH_NAME_MISLABELS):
            return text, False
        # Only annotate if a tag is also present (otherwise the 招牌菜 might be
        # about a real product, which is fine).
        if known_tags and not any(t in text for t in known_tags):
            return text, False
        if _ANNOT_DISH_LABEL in text:
            return text, False
        return text + "\n\n" + _ANNOT_DISH_LABEL, True

    @staticmethod
    def _fmt(v: float) -> str:
        if v == int(v):
            return f"{int(v):,}"
        return f"{v:,.2f}"
