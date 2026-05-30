"""Query synonym normalization — Apr 26 2026 UX-1 fix.

Background: S4 audit showed cache hit rate only 13% (270 followups). Root cause:
keyword patterns are strict — "走势" doesn't match patterns built around "趋势",
"营收" doesn't match patterns built around "营业额". Users phrase queries
differently than template authors, missing cache repeatedly.

Fix: normalize synonyms before keyword/vector matching. "走势" → "趋势",
"营收" → "营业额", etc. Targets cache hit 13% → 30%+, P50 8s → 3-4s.

Conservative — only normalize when synonym is unambiguous in restaurant/retail
context. Domain-specific terms (e.g. "客单价" stays "客单价" as it's well-known).
"""
from __future__ import annotations

import logging
from typing import Tuple

logger = logging.getLogger(__name__)


# Synonym groups: each entry is (canonical, [synonyms]).
# When a query contains a synonym, replace it with the canonical form for
# matching purposes (the user's original query is logged unchanged).
#
# Order: longer synonyms first to avoid partial replacement (e.g. "营业额"
# must not be partially replaced when matching "营业").
SYNONYM_GROUPS: list[tuple[str, list[str]]] = [
    # === Time / trend ===
    # 走势/动态/变化趋势 → 趋势 (template uses "趋势")
    ('趋势', ['走势', '变化趋势', '发展趋势', '态势', '动向']),
    # 月份/月度/月 → 月 (handled by patterns)
    ('月度', ['月份', '按月']),
    # 周内 → 周 / 每天 → 日
    ('日', ['每天', '每日', '天天']),

    # === Revenue / amount ===
    # 营收/收入/营业额/总营收 → 营业额 (templates use 营业额 most)
    ('营业额', ['营收', '总营收', '总收入', '营业收入', '业绩收入', '营业总额']),
    # 总销售/销售总额/卖了多少钱 → 销售额
    ('销售额', ['总销售', '销售总额', '总销售额', '卖了多少钱', '销售金额']),
    # 涨幅/降幅/变动率 → 变化率
    ('变化率', ['涨幅', '降幅', '变动率', '增幅', '减幅']),

    # === Quantity / count ===
    # 销量/卖了多少/卖了几份 → 销量 (canonical, just normalize variants)
    ('销量', ['卖了多少', '卖了几份', '卖出量', '出售量']),
    # 订单数/单数/点单数 → 订单数
    ('订单数', ['单数', '点单数', '下单数', '订单量']),

    # === Customer / member ===
    # 客流/人流/客流量/到店人数 → 客流量
    ('客流量', ['客流', '人流', '人流量', '到店人数', '进店人数', '到店客户']),
    # 客单价 stays as-is (well-known)

    # === Comparison / ranking ===
    # Top/前 N/最高 N → Top (Latin Top is canonical)
    # NOTE: don't replace 最高/最多 alone — they have other usages
    # 排行/排名/榜单 → 排名
    ('排名', ['排行', '排行榜', '榜单', '排序']),
    # 对比/比较/比起来 → 对比
    ('对比', ['比较', '比起来', '相比', '对照']),

    # === Action verbs ===
    # 是什么/是哪些/有哪些 → 是什么 (no-op, but enables other matches)
    # 怎么样 → 如何 (template uses 如何 sometimes)
    ('如何', ['怎么样', '怎样', '咋样']),

    # === Restaurant-specific ===
    # 菜品/菜式/菜单上的菜 → 菜品 (canonical)
    ('菜品', ['菜式', '菜肴', '菜', '主食', '主菜']),  # 菜单上的菜 太长不归一
    # 门店/店铺/分店/门点 → 门店
    ('门店', ['店铺', '分店', '门点', '店面']),
    # 时段/时间段/段位 → 时段
    ('时段', ['时间段', '段位', '时间区间']),
    # 渠道/平台/线上 (for context like 美团/饿了么) → 渠道 — careful, 平台 too generic
    # 不归一 平台 防误伤

    # === Member / activity ===
    # 流失/沉睡/不活跃/不再来 → 流失
    ('流失', ['沉睡', '不活跃', '不再来', '不常来', '掉客户', '失去']),
    # 召回/挽回/拉回 → 召回
    ('召回', ['挽回', '拉回', '唤醒', '激活']),
    # 复购/二次购买/再消费 → 复购
    ('复购', ['二次购买', '再消费', '回头', '重复消费']),

    # === Light measure② (May 31 2026): obvious best/slow-seller synonyms ===
    # Audit-observed misses where a synonym didn't match the canonical pattern.
    # Conservative — only unambiguous restaurant best/slow-seller phrasings, no
    # "卖得最好"-type strings (those are ambiguous between dish-rank and
    # store-rank and would shift store_performance routing).
    #
    # 热销/最畅销 → 畅销 (top_n_by_dim & dish patterns key on 畅销)
    ('畅销', ['热销', '最畅销', '热卖', '畅销品']),
    # 卖不出去/卖不动/卖得不好/不好卖/慢销/卖不掉 → 滞销
    # (dish_slow_movers patterns key on these; normalize all to 滞销 so the
    #  slow-mover query reliably routes to dish_slow_movers)
    ('滞销', ['卖不出去', '卖不动', '卖得不好', '不好卖', '慢销', '卖不掉']),
    # 收入 → 营业额 (already have 营收/总收入 above; add bare 收入 — but only
    #  as part of the existing 营业额 group to keep one canonical). Handled by
    #  appending below.
]

# Append bare 收入 → 营业额 to the existing revenue group (kept separate to
# avoid touching the literal above). 收入 alone is a common phrasing
# ("12月收入多少") that didn't normalize. Safe: '收入' is not a substring of
# '营业额'.
for _i, (_canon, _syns) in enumerate(SYNONYM_GROUPS):
    if _canon == '营业额' and '收入' not in _syns:
        _syns.append('收入')
        break


# Pre-compute reverse index: synonym → canonical.
# Sorted by synonym length desc to avoid partial-match issues.
def _build_index() -> list[tuple[str, str]]:
    """Build flat list of (synonym_pattern, canonical) sorted by length desc.

    Skips synonyms that are substrings of their canonical form — those would
    cause phase-2 placeholder restoration to glue extra chars (e.g. '菜' ⊂
    '菜品': replacing '菜' in '菜品' leaves '品', then placeholder→'菜品'
    yields '菜品品').
    """
    pairs: list[tuple[str, str]] = []
    for canonical, synonyms in SYNONYM_GROUPS:
        for syn in synonyms:
            if syn == canonical:
                continue  # don't self-replace
            if syn in canonical:
                logger.warning(
                    f"[query-normalizer] skipped substring synonym: "
                    f"'{syn}' is substring of canonical '{canonical}' "
                    f"(would corrupt phase-2 restoration)"
                )
                continue
            pairs.append((syn, canonical))
    # Sort by synonym length descending so "营业总额" replaces before "营业"
    pairs.sort(key=lambda p: len(p[0]), reverse=True)
    return pairs


_REPLACEMENT_INDEX = _build_index()


def normalize_query(query: str) -> Tuple[str, list[str]]:
    """Return (normalized_query, list_of_substitutions).

    Two-phase replacement to avoid synonym-canonical mutual eating
    (e.g. "菜" is synonym for "菜品" but "菜品" contains "菜" — naive
    replace_all on raw text would explode). Phase 1: replace each synonym
    with a unique placeholder token. Phase 2: replace placeholders with
    canonical forms.

    Substitutions are recorded for logging/debugging. Empty list = no change.
    """
    if not query or not isinstance(query, str):
        return query or "", []
    out = query
    subs: list[str] = []
    # Phase 1: synonym → placeholder. Use \x00CANON\x00 markers (NUL char
    # never appears in user queries, won't collide with anything).
    placeholders: dict[str, str] = {}  # placeholder → canonical
    for idx, (syn, canonical) in enumerate(_REPLACEMENT_INDEX):
        if syn in out:
            placeholder = f"\x00P{idx}\x00"
            out = out.replace(syn, placeholder)
            placeholders[placeholder] = canonical
            subs.append(f"{syn}→{canonical}")
    # Phase 2: placeholder → canonical. Order doesn't matter (placeholders
    # are unique non-overlapping tokens).
    for placeholder, canonical in placeholders.items():
        out = out.replace(placeholder, canonical)
    return out, subs


def normalize_for_match(query: str) -> str:
    """Convenience: return only the normalized query string.

    Logs at INFO level when a substitution happened.
    """
    normalized, subs = normalize_query(query)
    if subs:
        logger.info(
            f"[query-normalizer] '{query[:30]}' → '{normalized[:30]}' "
            f"(subs: {', '.join(subs[:3])})"
        )
    return normalized
