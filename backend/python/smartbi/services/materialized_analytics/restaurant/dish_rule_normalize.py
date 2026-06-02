"""Pure-function dish rule normalization for cross-store canonicalization (P4a).

Produces a stable ``normalized_key`` used to deduplicate / exact-match canonical
dishes across the 8 qhj stores. Two raw SKU names that strip to the same key are
candidates for the SAME canonical dish (e.g. "招牌青花椒鱼(单人份)" and
"#招牌青花椒鱼(微麻微辣)(一吃)#" → "招牌青花椒鱼").

Why a NEW pure module (vs reusing RestaurantMenuNormalizer.normalize_by_rules)?
  - ``RestaurantMenuNormalizer`` is a synchronous SQLAlchemy-bound class
    (``__init__(factory_id, db_session)``). The P4a canonicalizer + agents run on
    the async asyncpg path and inside tight loops — they must be DB-free.
  - This module mirrors the SAME 5 suffix classes (份量/口味/吃法/制作方式 + 外层 #...#)
    as ``RestaurantMenuNormalizer.ALL_SUFFIX_PATTERNS`` but as stateless regex, then
    folds in the deterministic dim normalizer (trad→simp + punct strip + lowercase)
    so the key matches dim_canonical_dish.normalized_key character-for-character.
  - Differs from ``dish_name_normalizer.normalize_dish_name`` (single trailing paren,
    top-N display fast path) — here we apply the FULL 5-class strip for canonical key.

⛔ Rule-layer key equality is a CANDIDATE signal only. Same key ≠ auto-merge:
   per MEMORY #364, even a rule-layer cluster goes through human confirm. "字面相似 ≠
   同菜" (红烧牛肉 ≠ 红烧牛腩) — this normalizer only strips KNOWN suffixes, it never
   touches the main dish body, so it cannot conflate two genuinely different dishes.
"""
from __future__ import annotations

import re
from typing import List

from smartbi.canonical.entity_resolution.agents.deterministic import (
    normalize_for_dim,
)

# ── 1. 外层 #...# 包装 (大众点评/美团外卖特征) ──────────────────────
_OUTER_HASH_PATTERN = re.compile(r"^#(.+?)#?$")

# ── 2-5. 后缀模式 (镜像 RestaurantMenuNormalizer.ALL_SUFFIX_PATTERNS) ──
# 2. 份量后缀
_PORTION_SUFFIX_PATTERNS: List[re.Pattern] = [
    re.compile(r"\(单人份\)"),
    re.compile(r"（单人份）"),
    re.compile(r"\(双人份\)"),
    re.compile(r"（双人份）"),
    re.compile(r"\([0-9]+-[0-9]+人份\)"),
    re.compile(r"（[0-9]+-[0-9]+人份）"),
    re.compile(r"\([0-9]+人份\)"),
    re.compile(r"（[0-9]+人份）"),
    re.compile(r"\[小份\]"),
    re.compile(r"\[大份\]"),
    re.compile(r"\[中份\]"),
    re.compile(r"\(小份\)"),
    re.compile(r"\(大份\)"),
    re.compile(r"（小份）"),
    re.compile(r"（大份）"),
    re.compile(r"小份$"),
    re.compile(r"大份$"),
]
# 3. 口味后缀
_FLAVOR_SUFFIX_PATTERNS: List[re.Pattern] = [
    re.compile(r"\(微麻微辣\)"),
    re.compile(r"（微麻微辣）"),
    re.compile(r"\(微辣\)"),
    re.compile(r"（微辣）"),
    re.compile(r"\(中辣\)"),
    re.compile(r"（中辣）"),
    re.compile(r"\(特辣\)"),
    re.compile(r"（特辣）"),
    re.compile(r"\(无辣\)"),
    re.compile(r"（无辣）"),
    re.compile(r"\(变态辣\)"),
    re.compile(r"（变态辣）"),
    re.compile(r"\(不辣\)"),
    re.compile(r"（不辣）"),
]
# 4. 吃法后缀
_EATING_STYLE_PATTERNS: List[re.Pattern] = [
    re.compile(r"（一吃）"),
    re.compile(r"\(一吃\)"),
    re.compile(r"（两吃）"),
    re.compile(r"\(两吃\)"),
    re.compile(r"（双吃）"),
    re.compile(r"\(双吃\)"),
]
# 5. 制作方式后缀
_PREPARATION_PATTERNS: List[re.Pattern] = [
    re.compile(r"\[活鱼现做\]"),
    re.compile(r"\[现做\]"),
    re.compile(r"\[小心鱼刺\]"),
    re.compile(r"\[手工去刺\]"),
    re.compile(r"\[去刺\]"),
    re.compile(r"\[活杀\]"),
]

_ALL_SUFFIX_PATTERNS: List[re.Pattern] = (
    _PORTION_SUFFIX_PATTERNS
    + _FLAVOR_SUFFIX_PATTERNS
    + _EATING_STYLE_PATTERNS
    + _PREPARATION_PATTERNS
)

_TRAILING_EMPTY_PAREN_RE = re.compile(r"[\(\[（【].*?[\)\]）】]\s*$")


def strip_dish_suffixes(name: str) -> str:
    """Strip the 5 known suffix classes + outer #...# wrapper. Pure string.

    Mirrors RestaurantMenuNormalizer.normalize_by_rules step-for-step (no DB).
    Returns "" for empty / whitespace-only input.
    """
    if not name or not name.strip():
        return ""

    result = name.strip()

    # Step 1: 外层 #...# 包装 (成对 + 单边兜底)
    match = _OUTER_HASH_PATTERN.match(result)
    if match:
        result = match.group(1).strip()
    result = result.lstrip("#").rstrip("#").strip()

    # Step 2-5: 移除所有后缀模式 (重复跑直到收敛)
    prev = ""
    max_iter = 10
    while result != prev and max_iter > 0:
        prev = result
        for pattern in _ALL_SUFFIX_PATTERNS:
            result = pattern.sub("", result)
        result = result.strip()
        max_iter -= 1

    # 末尾残留空括号清理
    result = _TRAILING_EMPTY_PAREN_RE.sub("", result).strip()

    return result


def dish_rule_normalize(name: str) -> str:
    """Canonical normalized_key for a dish name.

    Pipeline: strip 5-class suffixes (strip_dish_suffixes) → deterministic dim
    normalize (trad→simp + punctuation strip + ASCII lowercase). The result is the
    exact value stored in dim_canonical_dish.normalized_key, so the deterministic
    agent can do a 1:1 equality compare.

    Examples:
        dish_rule_normalize("招牌青花椒鱼(单人份)")            == "招牌青花椒鱼"
        dish_rule_normalize("#招牌青花椒鱼(微麻微辣)(一吃)#")   == "招牌青花椒鱼"
        dish_rule_normalize("招牌青花椒鱼[大份活鱼现做]")       == "招牌青花椒鱼"
        dish_rule_normalize("招牌青花椒烤鱼煲")                 == "招牌青花椒烤鱼煲" (不误并)
        dish_rule_normalize("")                                == ""
    """
    stripped = strip_dish_suffixes(name)
    if not stripped:
        return ""
    return normalize_for_dim(stripped)
