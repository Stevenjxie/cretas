"""P4a dish canonical 确认服务 (Task 8 — CLI + Admin API 共用核心).

把 confirm_dish_canonical CLI 的核心逻辑抽成一个**无 argparse / 无 pool 依赖**的可复用模块,
让 **CLI 和 web-admin API** 调同一份代码 (per Task 8 brief: refactor so BOTH call the same code)。

⛔ CRITICAL #1 — 这是 P4a 的**唯一人工闸门** (per MEMORY #364 / spec §R1 P0):
   dim_canonical_dish membership + dim_product.canonical_dish_id **只有这里**写。离线
   canonicalizer / agent 高置信全部只 propose 进队列; 必须人工 confirm 才落库。错合并
   (红烧牛肉 ≠ 红烧牛腩) 静默污染所有按 canonical 的分析 → 每条 confirm 都是人为判断。

⛔ fail-loud (per #390): canonical/link 写 (主路径) 失败抛异常; 只有 history 镜像 fail-open。

毕业镜像 (per MEMORY #389): confirm 成功后, 对每个成员 dim_product best-effort upsert 进
entity_resolution_history (entity_type='dish', a_name=成员 SKU 名, b_name=canonical 名,
b_entity_id=canonical_dish_id, conf=1.0, decided_by_agent='admin'), **fail-open BUT
log WARNING** — history 写失败绝不阻塞 confirm, 但绝不静默吞 (per #364)。

注: 调用方必须**先** SET app.factory_id (FORCE RLS) — confirm/reject 内部不 set tenant,
因为不同调用方 (CLI 用 false/session-scoped, API 用 true/txn-scoped) GUC 作用域不同。
``build_dish_review_payload`` 同理由调用方在已设 tenant 的 conn 上调。
"""
from __future__ import annotations

import json
import logging
from typing import Any, Dict, List, Optional, TYPE_CHECKING

if TYPE_CHECKING:
    import asyncpg

logger = logging.getLogger(__name__)


# ── SQL ──────────────────────────────────────────────────────────────────────

_GET_ITEM_SQL = """
    SELECT id, factory_id, status, raw_name, candidate_entity_id, extra
      FROM entity_resolution_admin_queue
     WHERE id = $1 AND factory_id = $2 AND entity_type = 'dish'
"""

# 新建 canonical (ON CONFLICT 同 (factory, normalized_key) → 复用既有, 避免重复建)。
_CREATE_CANONICAL_SQL = """
    INSERT INTO dim_canonical_dish
      (factory_id, canonical_name, normalized_key, category, member_count,
       status, created_by)
    VALUES ($1, $2, $3, $4, 0, 'active', 'admin')
    ON CONFLICT (factory_id, normalized_key) DO UPDATE SET
      canonical_name = EXCLUDED.canonical_name,
      updated_at = NOW()
    RETURNING canonical_dish_id
"""

# 把成员 dim_product 挂到 canonical (只有这里写 canonical_dish_id)。
_LINK_PRODUCT_SQL = """
    UPDATE dim_product
       SET canonical_dish_id = $1
     WHERE factory_id = $2 AND product_id = ANY($3::bigint[])
    RETURNING product_id
"""

# member_count 同步 = 当前挂在该 canonical 的 dim_product 数 (权威重算, 避免漂移)。
_RECOUNT_SQL = """
    UPDATE dim_canonical_dish
       SET member_count = (
           SELECT COUNT(*) FROM dim_product
            WHERE factory_id = $2 AND canonical_dish_id = $1
       )
     WHERE factory_id = $2 AND canonical_dish_id = $1
"""

_MARK_CONFIRMED_SQL = """
    UPDATE entity_resolution_admin_queue
       SET status = 'CONFIRMED',
           admin_action = 'confirm',
           admin_user = $3,
           admin_at = NOW(),
           admin_resolved_to_entity_id = $2
     WHERE id = $1 AND status = 'PENDING'
    RETURNING id
"""

_MARK_REJECTED_SQL = """
    UPDATE entity_resolution_admin_queue
       SET status = 'REJECTED',
           admin_action = 'reject',
           admin_user = $3,
           admin_at = NOW(),
           extra = COALESCE(extra, '{}'::jsonb)
                   || jsonb_build_object('reject_reason', $2::text)
     WHERE id = $1 AND status = 'PENDING'
    RETURNING id
"""

# 毕业镜像 (per #389): dish 走 entity_resolution_history, entity_type='dish' (V04 CHECK 已扩)。
_HISTORY_MIRROR_SQL = """
    INSERT INTO entity_resolution_history
      (factory_id, entity_type, a_name, b_name, b_entity_id,
       confidence, decided_by_agent, reasoning)
    VALUES ($1, 'dish', $2, $3, $4, 1.0, 'admin', $5)
    ON CONFLICT (factory_id, entity_type, a_name, b_entity_id) DO UPDATE SET
      confidence = GREATEST(entity_resolution_history.confidence, EXCLUDED.confidence),
      decided_by_agent = EXCLUDED.decided_by_agent,
      reasoning = EXCLUDED.reasoning
"""

# 销量/营收 sample: 按成员 dim_product 汇总 agg_product (全月份累加, 跨店合计)。
# agg_product = (factory_id, product_id, month, qty_sold, revenue) PK(factory,product,month)。
_SALES_SAMPLE_SQL = """
    SELECT product_id,
           SUM(COALESCE(qty_sold, 0)) AS qty,
           SUM(COALESCE(revenue, 0))  AS revenue
      FROM agg_product
     WHERE factory_id = $1 AND product_id = ANY($2::bigint[])
     GROUP BY product_id
"""

# 哪些门店有这个 (成员) 菜: fact_pos_item.product_id → fact_pos_transaction.store_id →
# dim_store.name。一个成员 dim_product 可能跨多店, 故返回 (product_id, store_name) 多行。
# DISTINCT 折叠同店多笔。LIMIT 防极端宽数据撑爆 payload。
_MEMBER_STORES_SQL = """
    SELECT i.product_id, s.name AS store_name
      FROM fact_pos_item i
      JOIN fact_pos_transaction t ON t.id = i.transaction_id
      JOIN dim_store s ON s.store_id = t.store_id
     WHERE i.factory_id = $1 AND i.product_id = ANY($2::bigint[])
     GROUP BY i.product_id, s.name
     ORDER BY i.product_id, s.name
     LIMIT 500
"""


# ── helpers ──────────────────────────────────────────────────────────────────

def _parse_extra(extra: Any) -> Dict[str, Any]:
    if extra is None:
        return {}
    if isinstance(extra, dict):
        return extra
    try:
        return json.loads(extra)
    except (json.JSONDecodeError, TypeError, ValueError):
        return {}


async def _mirror_history(
    conn: "asyncpg.Connection",
    factory_id: str,
    member_names: List[str],
    canonical_name: str,
    canonical_id: int,
) -> None:
    """best-effort 写 entity_resolution_history (per #389). fail-open + WARNING。

    history 写失败 (grant gap / RLS / CHECK) 绝不阻塞 confirm — canonical + link 已落库。
    但记 WARNING 让 deploy/审计能发现 (per #364: fail-open 必配可观测, 不静默吞)。
    """
    try:
        for a_name in member_names:
            await conn.execute(
                _HISTORY_MIRROR_SQL,
                factory_id, str(a_name), canonical_name, canonical_id,
                "admin confirmed dish canonical (P4a)",
            )
        logger.info(
            "  毕业镜像 → entity_resolution_history OK (%d 行, entity_type=dish)",
            len(member_names),
        )
    except Exception as e:  # noqa: BLE001 — fail-open by design (per #389/#364)
        logger.warning(
            "  毕业镜像写 entity_resolution_history 失败 "
            "(fail-open, 不影响 confirm; 但需关注 — 可能 grant/RLS/CHECK gap): %s",
            e,
        )


# ── core confirm / reject (CLI + API 共用) ────────────────────────────────────

async def confirm(
    conn: "asyncpg.Connection",
    factory_id: str,
    queue_id: int,
    canonical_name: Optional[str] = None,
    canonical_id: Optional[int] = None,
    admin_user: str = "admin",
) -> int:
    """毕业一条 dish 提议: 建/挂 canonical + 写 dim_product.canonical_dish_id。

    这是唯一写 canonical membership 的路径 (per #364 人工闸门)。

    返回 0 表示成功; 1 表示前置检查失败 (不存在 / 非 PENDING / 无成员)。
    主路径写失败 (RLS/grant) 抛 RuntimeError (fail-loud per #390)。

    ``canonical_id`` 给定 → 挂到既有 active canonical (合并到既有, 对应 action=confirm)。
    否则新建 canonical (对应 action=create_new), 名用 ``canonical_name`` 或提议建议名。
    ``admin_user`` 记进 admin_queue.admin_user (CLI 默认 'admin'; API 传当前用户 id)。
    """
    item = await conn.fetchrow(_GET_ITEM_SQL, queue_id, factory_id)
    if item is None:
        logger.error("提议 id=%d 不存在于 factory=%s 的 dish 队列。", queue_id, factory_id)
        return 1
    if item["status"] != "PENDING":
        logger.error("提议 id=%d 当前状态 %s, 非 PENDING, 无法确认。",
                     queue_id, item["status"])
        return 1

    extra = _parse_extra(item["extra"])
    member_ids: List[int] = [int(x) for x in (extra.get("member_product_ids") or [])]
    member_names: List[str] = [str(x) for x in (extra.get("member_names") or [])]
    normalized_key: str = extra.get("normalized_key") or ""
    category = extra.get("category")
    if not member_ids:
        logger.error("提议 id=%d 无成员 dim_product (extra.member_product_ids 空)。", queue_id)
        return 1

    # 主路径在单一事务内: 建/挂 canonical + link products + recount + mark confirmed。
    # fail-loud — RETURNING 没行 = RLS/grant 拒, 抛异常 (per #390)。
    async with conn.transaction():
        if canonical_id is not None:
            # 挂到既有 canonical: 校验存在 + active。
            exists = await conn.fetchrow(
                "SELECT canonical_name FROM dim_canonical_dish "
                "WHERE factory_id = $1 AND canonical_dish_id = $2 AND status = 'active'",
                factory_id, canonical_id,
            )
            if exists is None:
                raise RuntimeError(
                    f"canonical_id {canonical_id} 不存在 / 非 active "
                    f"(factory={factory_id})"
                )
            resolved_id = canonical_id
            resolved_name = exists["canonical_name"]
        else:
            # 新建 canonical (ON CONFLICT 复用同 key)。canonical_name 优先用入参,
            # 否则用提议建议名 (raw_name)。
            new_name = canonical_name or item["raw_name"]
            row = await conn.fetchrow(
                _CREATE_CANONICAL_SQL,
                factory_id, new_name, normalized_key, category,
            )
            if row is None:
                raise RuntimeError(
                    f"建 canonical 失败 (factory={factory_id}, key={normalized_key!r}) — "
                    f"possible RLS/grant rejection (per feedback_smartbi_table_grant_gap)"
                )
            resolved_id = int(row["canonical_dish_id"])
            resolved_name = new_name

        linked = await conn.fetch(
            _LINK_PRODUCT_SQL, resolved_id, factory_id, member_ids
        )
        if not linked:
            raise RuntimeError(
                f"link dim_product 失败 (factory={factory_id}, members={member_ids}) — "
                f"possible RLS/grant rejection (per feedback_smartbi_table_grant_gap)"
            )
        await conn.execute(_RECOUNT_SQL, resolved_id, factory_id)

        marked = await conn.fetchval(
            _MARK_CONFIRMED_SQL, queue_id, resolved_id, admin_user
        )
        if marked is None:
            raise RuntimeError(
                f"标记队列 CONFIRMED 失败 (id={queue_id}) — 可能已被并发处理 (race)。"
            )

    logger.info(
        "已确认 (毕业 admin=%s): 提议 id=%d → canonical_dish_id=%d '%s', "
        "挂 %d 个 dim_product。",
        admin_user, queue_id, resolved_id, resolved_name, len(linked),
    )

    # 毕业镜像 (best-effort, 独立于主事务; fail-open + WARNING)。
    await _mirror_history(conn, factory_id, member_names, resolved_name, resolved_id)
    return 0


async def reject(
    conn: "asyncpg.Connection",
    factory_id: str,
    queue_id: int,
    reason: str,
    admin_user: str = "admin",
) -> int:
    """拒绝一条 dish 提议 (不合并)。

    返回 0 成功; 1 前置失败。标记失败 (并发 race) 抛 RuntimeError。
    """
    item = await conn.fetchrow(_GET_ITEM_SQL, queue_id, factory_id)
    if item is None:
        logger.error("提议 id=%d 不存在于 factory=%s 的 dish 队列。", queue_id, factory_id)
        return 1
    if item["status"] != "PENDING":
        logger.error("提议 id=%d 当前状态 %s, 非 PENDING, 无法拒绝。",
                     queue_id, item["status"])
        return 1
    marked = await conn.fetchval(_MARK_REJECTED_SQL, queue_id, reason, admin_user)
    if marked is None:
        raise RuntimeError(f"标记队列 REJECTED 失败 (id={queue_id}) — 可能已被并发处理。")
    logger.info("已拒绝提议 id=%d (admin=%s, 原因: %s)。", queue_id, admin_user, reason)
    return 0


# ── 审核 payload (UI /list?entity_type=dish 富化, 防呆 Rule 2) ─────────────────

async def build_dish_review_payload(
    conn: "asyncpg.Connection",
    factory_id: str,
    extra: Any,
) -> Dict[str, Any]:
    """从一条 dish 提议的 extra 富化出人工审核所需信息 (防呆 Rule 2)。

    调用方须**先** SET app.factory_id (FORCE RLS) 再传 conn。返回 dict 挂到 list item 的
    ``dishReview`` 字段:
      - proposalKind / normalizedKey / category / suggestedCanonicalName
      - members: [{productId, name, stores:[门店名...], qty, revenue}]  ← 哪些门店有 + 销量
      - confidence (从 extra 没有则 None — 列表层已有 item.confidence)

    每个成员 dim_product 的 stores 来自 fact_pos_item→fact_pos_transaction→dim_store;
    qty/revenue 来自 agg_product 汇总。无销售数据的成员 stores=[] qty/revenue=0 (诚实空)。
    """
    parsed = _parse_extra(extra)
    member_ids: List[int] = [int(x) for x in (parsed.get("member_product_ids") or [])]
    member_names: List[str] = [str(x) for x in (parsed.get("member_names") or [])]

    # 成员 product_id ↔ name 对齐 (两数组同序入队)。
    id_to_name: Dict[int, str] = {}
    for idx, pid in enumerate(member_ids):
        id_to_name[pid] = member_names[idx] if idx < len(member_names) else str(pid)

    sales: Dict[int, Dict[str, float]] = {}
    stores: Dict[int, List[str]] = {pid: [] for pid in member_ids}

    if member_ids:
        sales_rows = await conn.fetch(_SALES_SAMPLE_SQL, factory_id, member_ids)
        for r in sales_rows:
            sales[int(r["product_id"])] = {
                "qty": float(r["qty"]) if r["qty"] is not None else 0.0,
                "revenue": float(r["revenue"]) if r["revenue"] is not None else 0.0,
            }
        store_rows = await conn.fetch(_MEMBER_STORES_SQL, factory_id, member_ids)
        for r in store_rows:
            pid = int(r["product_id"])
            name = r["store_name"]
            if name is not None:
                stores.setdefault(pid, []).append(str(name))

    members: List[Dict[str, Any]] = []
    for pid in member_ids:
        s = sales.get(pid, {"qty": 0.0, "revenue": 0.0})
        members.append({
            "productId": pid,
            "name": id_to_name.get(pid, str(pid)),
            "stores": stores.get(pid, []),
            "qty": s["qty"],
            "revenue": s["revenue"],
        })

    return {
        "proposalKind": parsed.get("proposal_kind"),
        "normalizedKey": parsed.get("normalized_key"),
        "category": parsed.get("category"),
        "suggestedCanonicalName": None,  # raw_name carries it at list level
        "memberCount": len(member_ids),
        "members": members,
    }
