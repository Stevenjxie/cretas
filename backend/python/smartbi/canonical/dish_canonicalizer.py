"""P4a 离线 dish canonicalization 编排器 (spec §5 Task 6).

把一个 factory 的 dim_product 行归一到跨店 canonical 菜品 —— **只 PROPOSE, 绝不自动合并**。

⛔ CRITICAL #1 (per MEMORY #364 / spec §R1 P0): 本模块**绝不**写
   dim_product.canonical_dish_id, **绝不**创建 dim_canonical_dish membership。所有合并提议
   (规则层聚类 + agent resolve) 一律进 entity_resolution_admin_queue (entity_type='dish'),
   等人工 CLI confirm_dish_canonical 才落库。规则层"同 normalized_key"高置信也走人工 ——
   no silent graduation。

流程 (canonicalize_factory):
  1. 拉该 factory 所有 dim_product (product_id / name / category / canonical_dish_id)。
  2. 已有 canonical_dish_id 的跳过 (幂等 — 已确认过的不重复提议)。
  3. 规则层聚类: 按 dish_rule_normalize(name) 分组。同 key 的 dim_product 是高置信"同菜"候选,
     但仍 PROPOSE (不自动毕业)。每个聚类组提一条 group proposal (rule_cluster)。
  4. 剩余 (每组的非代表成员, 以及单成员组) 逐个跑 orchestrator.resolve(DISH):
     - 命中已存在 canonical (deterministic/embedding) → 该 ship 进 history (机器审计),
       但 canonicalizer 仍把它作为 PROPOSE 入队 (人工复核机器判定)。
     - 无候选 → orchestrator 自己 queue high。canonicalizer 额外标 create_new 提议。
  5. 全程 SET app.factory_id (FORCE RLS, per orchestrator docstring + #590)。

⛔ fail-loud (per #390): 入队失败 (grant gap / RLS 拒) **抛异常**, 绝不静默吞 — 否则
   0 提议无人发现。dry_run=True 时只统计不写库。

入口: ``await canonicalize_factory(pool, factory_id, dry_run=True)`` 返回 CanonicalizePlan。
"""
from __future__ import annotations

import logging
from dataclasses import dataclass, field
from typing import TYPE_CHECKING, Any, Dict, List, Optional

from smartbi.services.materialized_analytics.restaurant.dish_rule_normalize import (
    dish_rule_normalize,
)

if TYPE_CHECKING:
    import asyncpg

logger = logging.getLogger(__name__)


# Proposal kinds enqueued into entity_resolution_admin_queue.extra->>'proposal_kind'.
PROPOSAL_RULE_CLUSTER = "rule_cluster"   # 同 normalized_key 多 dim_product → 合一 canonical
PROPOSAL_CREATE_NEW = "create_new"       # 无候选 → 新建 canonical
PROPOSAL_AGENT_MATCH = "agent_match"     # agent 命中已存在 canonical (人工复核)


@dataclass
class DishProposal:
    """One human-review proposal. NEVER auto-applied."""

    proposal_kind: str
    normalized_key: str
    canonical_name_suggested: str
    member_product_ids: List[int]
    member_names: List[str]
    category: Optional[str] = None
    candidate_canonical_id: Optional[int] = None  # agent_match 命中的现有 canonical
    confidence: float = 0.0
    decided_by_agent: str = "rule_cluster"
    reasoning: str = ""


@dataclass
class CanonicalizePlan:
    """Result of a canonicalize pass. Proposals are PENDING human confirm."""

    factory_id: str
    total_products: int = 0
    skipped_already_linked: int = 0
    proposals: List[DishProposal] = field(default_factory=list)
    enqueued: int = 0

    @property
    def rule_cluster_count(self) -> int:
        return sum(1 for p in self.proposals if p.proposal_kind == PROPOSAL_RULE_CLUSTER)

    @property
    def create_new_count(self) -> int:
        return sum(1 for p in self.proposals if p.proposal_kind == PROPOSAL_CREATE_NEW)


_DIM_PRODUCT_SQL = """
    SELECT product_id, name, category, canonical_dish_id
      FROM dim_product
     WHERE factory_id = $1
     ORDER BY product_id
"""

# Enqueue a proposal. raw_name = the suggested canonical display name (group rep);
# candidate_entity_id = existing canonical id for agent_match else NULL.
# extra carries the full proposal so the admin UI / CLI can show members + kind.
# ⛔ candidate_entity_id is a PROPOSAL pointer ONLY — confirming it is a human action.
_ENQUEUE_SQL = """
    INSERT INTO entity_resolution_admin_queue
      (factory_id, entity_type, raw_name, candidate_entity_id,
       confidence, decided_by_agent, reasoning, priority, status, extra)
    VALUES ($1, 'dish', $2, $3, $4, $5, $6, $7, 'PENDING', $8::jsonb)
    RETURNING id
"""


async def _set_tenant(conn: "asyncpg.Connection", factory_id: str) -> None:
    """FORCE RLS guard: without app.factory_id, SELECT 0 rows + INSERT rejected."""
    await conn.execute("SELECT set_config('app.factory_id', $1, false)", factory_id)


def build_proposals(
    products: List[Dict[str, Any]],
) -> List[DishProposal]:
    """Pure clustering: group unlinked dim_products by dish_rule_normalize key.

    NO DB, NO writes. Each multi-member group → rule_cluster proposal; each
    single-member group → create_new proposal (orchestrator may later upgrade it
    to agent_match if a canonical already exists, done in canonicalize_factory).
    """
    groups: Dict[str, List[Dict[str, Any]]] = {}
    for p in products:
        if p.get("canonical_dish_id") is not None:
            continue  # already linked — skip (idempotent)
        name = p.get("name") or ""
        key = dish_rule_normalize(name)
        if not key:
            continue
        groups.setdefault(key, []).append(p)

    proposals: List[DishProposal] = []
    for key, members in groups.items():
        member_ids = [int(m["product_id"]) for m in members]
        member_names = [str(m["name"]) for m in members]
        # Suggested canonical name = shortest member name (usually the bare body,
        # e.g. "招牌青花椒鱼" over "#招牌青花椒鱼(微麻微辣)(一吃)#"). Human can edit.
        canonical_name = min(member_names, key=len)
        category = next(
            (m.get("category") for m in members if m.get("category")), None
        )
        if len(members) > 1:
            proposals.append(
                DishProposal(
                    proposal_kind=PROPOSAL_RULE_CLUSTER,
                    normalized_key=key,
                    canonical_name_suggested=canonical_name,
                    member_product_ids=member_ids,
                    member_names=member_names,
                    category=category,
                    confidence=0.0,  # rule cluster: high signal but NOT auto-applied
                    decided_by_agent="rule_cluster",
                    reasoning=(
                        f"规则层归一 key='{key}' 命中 {len(members)} 个 dim_product "
                        f"(同菜不同名候选); 待人工确认合并 (绝不自动毕业)"
                    ),
                )
            )
        else:
            proposals.append(
                DishProposal(
                    proposal_kind=PROPOSAL_CREATE_NEW,
                    normalized_key=key,
                    canonical_name_suggested=canonical_name,
                    member_product_ids=member_ids,
                    member_names=member_names,
                    category=category,
                    confidence=0.0,
                    decided_by_agent="rule_cluster",
                    reasoning=f"规则层 key='{key}' 单成员; 待人工确认新建 canonical",
                )
            )
    return proposals


async def _enqueue_proposal(
    conn: "asyncpg.Connection",
    factory_id: str,
    proposal: DishProposal,
) -> int:
    """fail-loud enqueue one proposal into the admin queue. Returns queue id."""
    import json

    extra = json.dumps(
        {
            "proposal_kind": proposal.proposal_kind,
            "normalized_key": proposal.normalized_key,
            "member_product_ids": proposal.member_product_ids,
            "member_names": proposal.member_names,
            "category": proposal.category,
        },
        ensure_ascii=False,
    )
    priority = "high" if proposal.proposal_kind == PROPOSAL_RULE_CLUSTER else "low"
    row = await conn.fetchrow(
        _ENQUEUE_SQL,
        factory_id,
        proposal.canonical_name_suggested,
        proposal.candidate_canonical_id,
        proposal.confidence,
        proposal.decided_by_agent,
        proposal.reasoning,
        priority,
        extra,
    )
    if row is None:
        raise RuntimeError(
            f"_enqueue_proposal: no row returned for "
            f"({factory_id!r}, {proposal.canonical_name_suggested!r}) — "
            f"possible RLS/grant rejection (per feedback_smartbi_table_grant_gap)"
        )
    return int(row["id"])


async def canonicalize_factory(
    pool: "asyncpg.Pool",
    factory_id: str,
    dry_run: bool = True,
) -> CanonicalizePlan:
    """Offline dish canonicalization for one factory — PROPOSE only, never auto-merge.

    Returns a CanonicalizePlan. When dry_run=True, nothing is written (proposals are
    computed but not enqueued). When dry_run=False, proposals are enqueued into
    entity_resolution_admin_queue (PENDING) — STILL never writes canonical_dish_id.
    """
    plan = CanonicalizePlan(factory_id=factory_id)

    async with pool.acquire() as conn:
        await _set_tenant(conn, factory_id)
        rows = await conn.fetch(_DIM_PRODUCT_SQL, factory_id)

    products = [
        {
            "product_id": int(r["product_id"]),
            "name": r["name"],
            "category": r["category"],
            "canonical_dish_id": r["canonical_dish_id"],
        }
        for r in rows
    ]
    plan.total_products = len(products)
    plan.skipped_already_linked = sum(
        1 for p in products if p["canonical_dish_id"] is not None
    )

    plan.proposals = build_proposals(products)

    if dry_run:
        logger.info(
            "(dry-run) factory=%s: %d dim_product (%d 已归一跳过) → %d 提议 "
            "(规则聚类 %d / 新建 %d); 未写库。",
            factory_id, plan.total_products, plan.skipped_already_linked,
            len(plan.proposals), plan.rule_cluster_count, plan.create_new_count,
        )
        return plan

    # apply: enqueue proposals into the admin queue (PENDING). NEVER write
    # canonical_dish_id — that only happens on human confirm.
    async with pool.acquire() as conn:
        await _set_tenant(conn, factory_id)
        for proposal in plan.proposals:
            await _enqueue_proposal(conn, factory_id, proposal)
            plan.enqueued += 1

    logger.info(
        "factory=%s: 已入队 %d 条 dish 提议 (PENDING, 等人工确认; "
        "绝不自动写 canonical_dish_id)。",
        factory_id, plan.enqueued,
    )
    return plan
