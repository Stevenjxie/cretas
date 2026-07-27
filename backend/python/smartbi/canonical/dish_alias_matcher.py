"""P4a·卡3 菜品别名 → 标准菜品 ID 语义辅助初匹配 (spec §2.4 Wave 1).

给未归一的别名候选 (original_name) 匹配到已存在的 dim_canonical_dish, 产出 PENDING
候选写入 restaurant_dish_alias (status='pending')。⛔ 绝不自动 confirm (per MEMORY
#364 人工闸门纪律, 类比 dish_canonicalizer.py 对 dim_product 的做法) —— 本模块只
PROPOSE, 由 dish_alias_confirm_service.confirm/reject 做人工终审。

复用既有组件, 不重造轮子 (per 卡3 brief "先读既有实现再决定复用什么"):
  - dish_rule_normalize (smartbi.services.materialized_analytics.restaurant.
    dish_rule_normalize) — 与 DeterministicAgent 对 dim_canonical_dish 完全相同的
    规则层归一 key, "同名"态直接复用它做 normalized_key 相等比较, 不重写一份归一逻辑。
  - difflib.SequenceMatcher — "相似名"态, 与 pos_name_resolver._best_fuzzy_match /
    AliasNormalizer._cluster_by_similarity 同一套阈值语义 (>=0.85 才提议; 更低置信度
    不产出候选, 避免噪声污染人审队列)。

三态匹配结果 (match_candidate, 纯函数无 DB):
  - exact:   dish_rule_normalize(name) == 某 dim_canonical_dish.normalized_key
             → confidence 1.0, review_source='rule_layer'
  - similar: 与某 canonical_name 的 difflib ratio >= SIMILAR_THRESHOLD (但非 exact)
             → confidence = ratio, review_source='levenshtein'
  - none:    都不满足 → 不产出候选 (例: "红烧牛肉" vs "红烧牛腩" ratio=0.75 < 0.85,
             不误判为候选, 字面相似 ≠ 同菜)

写入路径 (propose_dish_alias_candidates) 只 INSERT ... ON CONFLICT (factory_id,
original_name) DO NOTHING —— 已存在的行 (无论 confirmed / pending / rejected) 一律
不覆盖, 机器候选不能撤销既有人工判断。
"""
from __future__ import annotations

import logging
from dataclasses import dataclass
from difflib import SequenceMatcher
from typing import TYPE_CHECKING, Any, Dict, List, Optional

from smartbi.services.materialized_analytics.restaurant.dish_rule_normalize import (
    dish_rule_normalize,
)

if TYPE_CHECKING:
    import asyncpg

logger = logging.getLogger(__name__)

# 与 pos_name_resolver.AUTO_ACCEPT_THRESHOLD / AliasNormalizer 默认 edit_distance_threshold
# 同一套阈值语义 (0.85) —— 高于它才是"高置信候选", 低于它宁可漏配也不误配。
SIMILAR_THRESHOLD = 0.85

REVIEW_SOURCE_RULE = "rule_layer"
REVIEW_SOURCE_LEVENSHTEIN = "levenshtein"


@dataclass(frozen=True)
class DishAliasCandidate:
    """一条待人审的别名→标准菜品候选. 绝不代表已确认."""

    original_name: str
    canonical_dish_id: int
    canonical_name: str
    confidence: float
    review_source: str
    reasoning: str


async def _set_tenant(conn: "asyncpg.Connection", factory_id: str) -> None:
    """FORCE RLS guard for dim_canonical_dish. asyncpg 坑: set_config(...,true) 从不
    生效 (per rule 6 / feedback_asyncpg_local_setconfig_rls_never_applies), 用 false。
    """
    await conn.execute("SELECT set_config('app.factory_id', $1, false)", factory_id)


def match_candidate(
    name: str,
    canonical_dishes: List[Dict[str, Any]],
) -> Optional[DishAliasCandidate]:
    """Pure matching: name vs 一个 factory 的 dim_canonical_dish 行. 无 DB 访问.

    Args:
        name: 待匹配的原始菜名 (original_name)。
        canonical_dishes: [{"canonical_dish_id", "canonical_name", "normalized_key"},
            ...] —— 调用方负责只传 status='active' 的行。

    Returns:
        三态之一: exact/similar 命中返回 DishAliasCandidate, 无匹配返回 None。
    """
    if not name or not name.strip() or not canonical_dishes:
        return None

    key = dish_rule_normalize(name)

    # 同名态: 规则层归一 key 完全相等 (复用 dish_rule_normalize, 与
    # DeterministicAgent 对 dim_canonical_dish 的判定标准一致)。
    if key:
        for c in canonical_dishes:
            if c.get("normalized_key") == key:
                return DishAliasCandidate(
                    original_name=name,
                    canonical_dish_id=int(c["canonical_dish_id"]),
                    canonical_name=str(c["canonical_name"]),
                    confidence=1.0,
                    review_source=REVIEW_SOURCE_RULE,
                    reasoning=(
                        f"规则层归一 key='{key}' 与既有 canonical "
                        f"'{c['canonical_name']}' 完全一致"
                    ),
                )

    # 相似名态: difflib ratio 对 canonical_name 取最优, 达阈值才提议。
    best: Optional[Dict[str, Any]] = None
    best_ratio = 0.0
    for c in canonical_dishes:
        ratio = SequenceMatcher(None, name, str(c["canonical_name"])).ratio()
        if ratio > best_ratio:
            best_ratio = ratio
            best = c
    if best is not None and best_ratio >= SIMILAR_THRESHOLD:
        return DishAliasCandidate(
            original_name=name,
            canonical_dish_id=int(best["canonical_dish_id"]),
            canonical_name=str(best["canonical_name"]),
            confidence=round(best_ratio, 2),
            review_source=REVIEW_SOURCE_LEVENSHTEIN,
            reasoning=(
                f"'{name}' 与既有 canonical '{best['canonical_name']}' "
                f"相似度 {best_ratio:.2f} (>= {SIMILAR_THRESHOLD})"
            ),
        )

    # 无匹配态 (含"字面相似但低于阈值", 例如 红烧牛肉 vs 红烧牛腩 ratio=0.75):
    # 不产出候选, 宁可漏配也不误配 —— 错合并静默污染所有按 canonical 的分析。
    return None


_ACTIVE_CANONICAL_SQL = """
    SELECT canonical_dish_id, canonical_name, normalized_key
      FROM dim_canonical_dish
     WHERE factory_id = $1 AND status = 'active'
"""

_EXISTING_ALIAS_NAMES_SQL = """
    SELECT original_name FROM restaurant_dish_alias WHERE factory_id = $1
"""

_INSERT_PENDING_SQL = """
    INSERT INTO restaurant_dish_alias
      (factory_id, original_name, canonical_name, canonical_dish_id, store_id,
       confidence, review_source, status, created_at)
    VALUES ($1, $2, $3, $4, $5, $6, $7, 'pending', NOW())
    ON CONFLICT (factory_id, original_name) DO NOTHING
    RETURNING id
"""


async def propose_dish_alias_candidates(
    pool: "asyncpg.Pool",
    factory_id: str,
    names: List[str],
    store_id: Optional[str] = None,
    dry_run: bool = True,
) -> List[DishAliasCandidate]:
    """匹配 `names` 对该 factory 的 active dim_canonical_dish, 产出 PENDING 别名候选.

    Args:
        store_id: 门店级候选的作用域。⚠️ Wave 1 建议保持 None (per 卡3 fable 终审 5)：
            写入侧唯一性仍是 (factory_id, original_name)（migration V20260728_02 头注
            §2 明确的 Wave 1 边界），所以传非 None 值只会写出一条别人（租户级查询,
            store_id IS NULL）永远查不到的行 —— 不会返回错答案, 但会静默失效 (候选
            白提, 人也审不到)。要用店级候选必须等 Wave 2 把唯一性改成
            (factory_id, store_id, original_name) 且同步改 alias_normalizer 的
            ON CONFLICT 子句之后。

    dry_run=True (默认): 只计算 + 返回候选, 不写库。
    dry_run=False: 额外 INSERT ... ON CONFLICT DO NOTHING (status='pending')。已存在
      的行 (任意 status) 保持不动 —— 机器提议绝不覆盖既有人工判断 (confirmed/
      pending/rejected 都不覆盖)。

    ⛔ 绝不写 status='confirmed' —— 匹配本身不是人工审核 (per MEMORY #364)。
    """
    if not factory_id:
        raise ValueError("propose_dish_alias_candidates: factory_id required")

    unique_names = list(dict.fromkeys(n for n in names if n and n.strip()))
    if not unique_names:
        return []

    async with pool.acquire() as conn:
        await _set_tenant(conn, factory_id)
        canonical_rows = await conn.fetch(_ACTIVE_CANONICAL_SQL, factory_id)
        existing_rows = await conn.fetch(_EXISTING_ALIAS_NAMES_SQL, factory_id)

    canonical_dishes = [dict(r) for r in canonical_rows]
    existing_names = {r["original_name"] for r in existing_rows}

    candidates: List[DishAliasCandidate] = []
    for name in unique_names:
        if name in existing_names:
            continue  # 已有行 (任意 status) — 不重复提议, 不覆盖既有判断
        match = match_candidate(name, canonical_dishes)
        if match is not None:
            candidates.append(match)

    if dry_run or not candidates:
        return candidates

    async with pool.acquire() as conn:
        await _set_tenant(conn, factory_id)
        for c in candidates:
            await conn.fetchrow(
                _INSERT_PENDING_SQL,
                factory_id, c.original_name, c.canonical_name, c.canonical_dish_id,
                store_id, c.confidence, c.review_source,
            )

    logger.info(
        "factory=%s: %d 名候选匹配 %d 条 (pending, 待人审确认; 绝不自动 confirm)。",
        factory_id, len(unique_names), len(candidates),
    )
    return candidates
