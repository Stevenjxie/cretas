"""P4a — dish_canonicalizer: rule-cluster grouping + PROPOSE-only (never auto-merge).

Spec §5 Task 6 + §R1 P0: same normalized_key dim_products cluster into ONE proposal;
the canonicalizer NEVER writes dim_product.canonical_dish_id nor creates
dim_canonical_dish — it only enqueues admin-queue proposals (and dry_run writes nothing).
"""
from __future__ import annotations

from unittest.mock import AsyncMock, MagicMock

from smartbi.canonical.dish_canonicalizer import (
    PROPOSAL_CREATE_NEW,
    PROPOSAL_RULE_CLUSTER,
    build_proposals,
    canonicalize_factory,
)


def _p(pid, name, category=None, canonical_dish_id=None):
    return {
        "product_id": pid,
        "name": name,
        "category": category,
        "canonical_dish_id": canonical_dish_id,
    }


def test_build_proposals_clusters_same_normalized_key():
    """39 招牌变体样例 → 规则层聚成 1 组 (同 key)。"""
    products = [
        _p(1, "招牌青花椒鱼(单人份)"),
        _p(2, "#招牌青花椒鱼(微麻微辣)(一吃)#"),
        _p(3, "招牌青花椒鱼[大份活鱼现做]"),
        _p(4, "招牌青花椒鱼（两吃）"),
    ]
    proposals = build_proposals(products)
    assert len(proposals) == 1
    p = proposals[0]
    assert p.proposal_kind == PROPOSAL_RULE_CLUSTER
    assert sorted(p.member_product_ids) == [1, 2, 3, 4]
    # suggested canonical = shortest member ("招牌青花椒鱼" body would be shortest if
    # present; here the shortest raw name is the (单人份) one but the KEY collapses all).
    assert p.canonical_name_suggested in {m["name"] for m in products}


def test_build_proposals_different_dishes_separate_groups():
    """烤鱼煲 / 鱼 / 米饭 三道不同菜 → 三组 (不误并)。"""
    products = [
        _p(1, "招牌青花椒鱼(单人份)"),
        _p(2, "招牌青花椒烤鱼煲"),
        _p(3, "米饭"),
    ]
    proposals = build_proposals(products)
    assert len(proposals) == 3
    # all single-member → create_new
    assert all(p.proposal_kind == PROPOSAL_CREATE_NEW for p in proposals)


def test_build_proposals_skips_already_linked():
    """已有 canonical_dish_id 的 dim_product 跳过 (幂等)。"""
    products = [
        _p(1, "招牌青花椒鱼", canonical_dish_id=99),  # linked → skip
        _p(2, "招牌青花椒鱼(单人份)"),                  # unlinked
    ]
    proposals = build_proposals(products)
    # only product 2 considered → 1 single-member group
    assert len(proposals) == 1
    assert proposals[0].member_product_ids == [2]


def test_build_proposals_never_returns_canonical_id():
    """build_proposals 不分配 canonical id (绝不自动建 canonical)。"""
    products = [_p(1, "招牌青花椒鱼(单人份)"), _p(2, "青花椒味鱼")]
    for p in build_proposals(products):
        assert p.candidate_canonical_id is None


def _mock_pool(product_rows):
    conn = AsyncMock()
    conn.execute = AsyncMock(return_value=None)
    conn.fetch = AsyncMock(return_value=product_rows)
    conn.fetchrow = AsyncMock(return_value={"id": 1})
    pool = MagicMock()
    acquire_ctx = MagicMock()
    acquire_ctx.__aenter__ = AsyncMock(return_value=conn)
    acquire_ctx.__aexit__ = AsyncMock(return_value=None)
    pool.acquire = MagicMock(return_value=acquire_ctx)
    return pool, conn


async def test_canonicalize_dry_run_writes_nothing():
    """dry_run: 计算提议但不写库 (无 fetchrow enqueue, 无 canonical_dish_id UPDATE)。"""
    rows = [
        _p(1, "招牌青花椒鱼(单人份)"),
        _p(2, "#招牌青花椒鱼(微麻微辣)#"),
    ]
    pool, conn = _mock_pool(rows)

    plan = await canonicalize_factory(pool, "RES_3101_009", dry_run=True)

    assert plan.total_products == 2
    assert len(plan.proposals) == 1  # clustered
    assert plan.enqueued == 0
    # enqueue uses fetchrow; dry-run must NOT call it.
    conn.fetchrow.assert_not_called()
    # NEVER any UPDATE dim_product SET canonical_dish_id.
    for call in conn.execute.await_args_list:
        sql = call.args[0] if call.args else ""
        assert "canonical_dish_id" not in sql.lower() or "update" not in sql.lower()


async def test_canonicalize_apply_enqueues_never_links():
    """apply: 入队提议 (fetchrow RETURNING id), 但绝不 UPDATE dim_product.canonical_dish_id。"""
    rows = [
        _p(1, "招牌青花椒鱼(单人份)"),
        _p(2, "#招牌青花椒鱼(微麻微辣)#"),
        _p(3, "米饭"),
    ]
    pool, conn = _mock_pool(rows)

    plan = await canonicalize_factory(pool, "RES_3101_009", dry_run=False)

    # 2 proposals: 1 rule_cluster (招牌×2) + 1 create_new (米饭)
    assert len(plan.proposals) == 2
    assert plan.enqueued == 2
    # all enqueue SQL targets the admin queue, none links canonical_dish_id.
    enqueue_sqls = [c.args[0] for c in conn.fetchrow.await_args_list]
    assert all("entity_resolution_admin_queue" in s for s in enqueue_sqls)
    assert all("dim_product" not in s for s in enqueue_sqls)
    # No execute statement updates dim_product.canonical_dish_id.
    for call in conn.execute.await_args_list:
        sql = (call.args[0] if call.args else "").lower()
        assert not ("update" in sql and "dim_product" in sql)
