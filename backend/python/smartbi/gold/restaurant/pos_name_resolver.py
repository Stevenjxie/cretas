"""POS dish-name resolution backfill — 餐饮 #61 Phase 1.

Resolves POS-exported dish names (smartbi dim_product.normalized_name) to cretas
product_types, so the finance ETL Stage 3 (COGS) can write COST rows for more
POS lines. Unblocks #57 cost-card accuracy.

Architecture (see docs/superpowers/specs/2026-06-04-restaurant-pos-name-resolution-design.md):
  - dim_product_alias lives in cretas_db (where finance ETL Stage 3 reads it).
    Schema upgraded at runtime via idempotent CREATE/ALTER (no migration path for
    cretas_db — managed by Java/Hibernate + inline DDL).
  - restaurant_pos_unresolved_queue + entity_resolution_history audit live in smartbi_db.

Data contract (load-bearing): the finance ETL keys on dim_product.normalized_name,
matching product_types.name AND dim_product_alias.pos_name. So alias writes + queue
rows MUST be keyed on normalized_name, or the ETL fallback never hits.

5-layer resolution per distinct POS normalized_name:
  L0 dim_product_alias exact (already resolved) → skip
  L1 product_types.name == normalized_name → conf 1.0 (ETL primary path, no alias write)
  L2 _normalize_name(product_types.name) == normalized_name → conf 0.95, auto-write alias
  L3 difflib SequenceMatcher ratio against product_types names:
       >= 0.85 → auto-accept, write alias (source=fuzzy_match)
       0.60-0.85 → unresolved_queue pending + best_candidate
       < 0.60 → unresolved_queue pending, no candidate
  L4 transitive-over-alias: normalized key already maps to a confirmed alias → inherit
     (conf 0.80 * 0.95 = 0.76 < 0.85 → queue, not auto). NOTE: an exact normalized-key
     match is caught by L0; L4 covers near-key matches via difflib over alias keys.
  L5 write unresolved_queue rows sorted by revenue_at_risk DESC.
"""
from __future__ import annotations

import hashlib
import logging
from difflib import SequenceMatcher
from typing import Any, Dict, List

# Reuse verbatim from restaurant_ops_etl (single source of truth).
from smartbi.gold.restaurant.restaurant_ops_etl import _normalize_name, _set_tenant

logger = logging.getLogger(__name__)

# Thresholds (brief defaults).
AUTO_ACCEPT_THRESHOLD = 0.85
QUEUE_LOWER_THRESHOLD = 0.60
L2_AUTO_CONFIDENCE = 0.95
L3_TRANSITIVE_DISCOUNT = 0.95
L4_TRANSITIVE_BASE = 0.80

_BIGINT_SIGNED_MAX = 2 ** 63  # surrogate must be positive and fit signed BIGINT


def _pos_dish_surrogate_bigint(factory_id: str, pos_name: str, product_type_id: str) -> int:
    """Deterministic positive BIGINT surrogate for entity_resolution_history.b_entity_id.

    product_types.id is a VARCHAR UUID but b_entity_id is BIGINT NOT NULL. We hash the
    (factory, pos_name, product_type_id) tuple into a stable positive bigint so the audit
    row is deterministic (idempotent upsert) without colliding the int dim id space used
    by TransitiveAgent. The real string product_type_id is preserved in b_name/reasoning.
    """
    digest = hashlib.md5(
        f"{factory_id}\x1f{pos_name}\x1f{product_type_id}".encode("utf-8")
    ).hexdigest()
    # 60 bits → always positive, always < 2^63.
    return int(digest[:15], 16) % _BIGINT_SIGNED_MAX or 1


async def ensure_alias_schema(cretas_pool, factory_id: str = "") -> None:
    """Idempotent CREATE/ALTER of cretas dim_product_alias to the upgraded schema.

    Mirrors the existing inline CREATE TABLE IF NOT EXISTS in restaurant_ops_recipes.py,
    then adds the resolution/audit columns. Safe to call on every backfill run.
    """
    async with cretas_pool.acquire() as conn:
        await conn.execute(
            """
            CREATE TABLE IF NOT EXISTS dim_product_alias (
                id BIGSERIAL PRIMARY KEY,
                factory_id VARCHAR(100) NOT NULL,
                pos_name VARCHAR(500) NOT NULL,
                product_type_id VARCHAR(100) NOT NULL,
                created_at TIMESTAMP DEFAULT NOW(),
                UNIQUE(factory_id, pos_name)
            )
            """
        )
        # Upgrade columns (idempotent). source CHECK enforced at app layer to avoid
        # touching pre-existing rows (NULL source = legacy admin bind via recipes.py).
        await conn.execute(
            """
            ALTER TABLE dim_product_alias
                ADD COLUMN IF NOT EXISTS confidence NUMERIC(3,2),
                ADD COLUMN IF NOT EXISTS source VARCHAR(20),
                ADD COLUMN IF NOT EXISTS decided_by_agent VARCHAR(30),
                ADD COLUMN IF NOT EXISTS admin_user VARCHAR(100),
                ADD COLUMN IF NOT EXISTS admin_at TIMESTAMP,
                ADD COLUMN IF NOT EXISTS updated_at TIMESTAMP DEFAULT NOW()
            """
        )


async def _upsert_alias(
    conn, factory_id: str, pos_name: str, product_type_id: str,
    confidence: float, source: str, decided_by_agent: str,
) -> None:
    """Write a resolved alias to cretas dim_product_alias. pos_name = normalized_name."""
    await conn.execute(
        """
        INSERT INTO dim_product_alias
            (factory_id, pos_name, product_type_id, confidence, source,
             decided_by_agent, updated_at)
        VALUES ($1, $2, $3, $4, $5, $6, NOW())
        ON CONFLICT (factory_id, pos_name) DO UPDATE SET
            product_type_id = EXCLUDED.product_type_id,
            confidence = EXCLUDED.confidence,
            source = EXCLUDED.source,
            decided_by_agent = EXCLUDED.decided_by_agent,
            updated_at = NOW()
        """,
        factory_id, pos_name, product_type_id, round(float(confidence), 2),
        source, decided_by_agent,
    )


async def _upsert_queue_row(
    conn, factory_id: str, pos_name: str, display_name: str,
    occurrence_count: int, revenue_at_risk: float,
    best_candidate_id, best_candidate_name, best_confidence,
) -> None:
    """Upsert a pending unresolved-queue row in smartbi (RLS tenant already set)."""
    await conn.execute(
        """
        INSERT INTO restaurant_pos_unresolved_queue
            (factory_id, pos_name, display_name, occurrence_count, revenue_at_risk,
             best_candidate_id, best_candidate_name, best_confidence, status, updated_at)
        VALUES ($1, $2, $3, $4, $5, $6, $7, $8, 'pending', NOW())
        ON CONFLICT (factory_id, pos_name) DO UPDATE SET
            display_name = EXCLUDED.display_name,
            occurrence_count = EXCLUDED.occurrence_count,
            revenue_at_risk = EXCLUDED.revenue_at_risk,
            best_candidate_id = EXCLUDED.best_candidate_id,
            best_candidate_name = EXCLUDED.best_candidate_name,
            best_confidence = EXCLUDED.best_confidence,
            updated_at = NOW()
        WHERE restaurant_pos_unresolved_queue.status = 'pending'
        """,
        factory_id, pos_name, display_name, int(occurrence_count),
        round(float(revenue_at_risk), 2),
        best_candidate_id, best_candidate_name,
        round(float(best_confidence), 2) if best_confidence is not None else None,
    )


def _best_fuzzy_match(normalized_name: str, candidates: List[Dict[str, Any]]):
    """Return (product_type_id, product_type_name, ratio) of the best difflib match.

    candidates: [{id, name, _norm}] where _norm = _normalize_name(name).
    Matches against the normalized candidate name (POS normalized vs product normalized).
    """
    best_id = best_name = None
    best_ratio = 0.0
    for c in candidates:
        ratio = SequenceMatcher(None, normalized_name, c["_norm"]).ratio()
        if ratio > best_ratio:
            best_ratio = ratio
            best_id = c["id"]
            best_name = c["name"]
    return best_id, best_name, best_ratio


async def resolve_factory_pos_names(cretas_pool, smartbi_pool, factory_id: str) -> Dict[str, Any]:
    """Run the 5-layer resolver for one factory. Returns real counts.

    Args:
        cretas_pool: cretas_db pool (product_types + dim_product_alias)
        smartbi_pool: smartbi_db pool (fact_pos_item/dim_product + unresolved_queue)
        factory_id: tenant
    """
    if not factory_id:
        raise ValueError("resolve_factory_pos_names: factory_id required")

    await ensure_alias_schema(cretas_pool, factory_id)

    # 1. POS dish names + revenue from smartbi (same source as finance ETL Stage 3).
    async with smartbi_pool.acquire() as conn:
        await _set_tenant(conn, factory_id)
        pos_rows = await conn.fetch(
            """
            SELECT p.name AS display_name, p.normalized_name,
                   SUM(i.qty)::float AS qty,
                   SUM(i.amount)::float AS revenue,
                   COUNT(DISTINCT i.transaction_id)::int AS bills
              FROM fact_pos_item i
              JOIN dim_product p ON p.product_id = i.product_id
             WHERE i.factory_id = $1
               AND p.factory_id = $1
               AND i.product_id IS NOT NULL
             GROUP BY p.name, p.normalized_name
            """,
            factory_id,
        )

    result = {
        "totalPosNames": 0,
        "alreadyResolved": 0,
        "resolvedAuto": 0,
        "queued": 0,
    }
    if not pos_rows:
        logger.info("[pos-name-resolver] no POS rows for factory=%s", factory_id)
        return result

    # De-dup by the RAW normalized_name — this is the EXACT key the finance ETL
    # Stage 3 alias fallback looks up (WHERE pos_name = ANY(dim_product.normalized_name)).
    # The persisted alias/queue key MUST be byte-identical to dim_product.normalized_name
    # or the ETL lookup misses and the cost card stays ¥0. _normalize_name is used ONLY
    # for the fuzzy comparison side (L2/L3/L4), never for the persisted key.
    by_raw: Dict[str, Dict[str, Any]] = {}
    for r in pos_rows:
        raw_key = r["normalized_name"] or r["display_name"]
        if not raw_key:
            continue
        agg = by_raw.setdefault(raw_key, {
            "raw_key": raw_key,
            "norm_key": _normalize_name(raw_key),
            "display_name": r["display_name"] or raw_key,
            "revenue": 0.0, "qty": 0.0, "bills": 0,
        })
        agg["revenue"] += float(r["revenue"] or 0)
        agg["qty"] += float(r["qty"] or 0)
        agg["bills"] += int(r["bills"] or 0)

    distinct = list(by_raw.values())
    result["totalPosNames"] = len(distinct)

    # 2. Load product_types + existing aliases from cretas.
    async with cretas_pool.acquire() as cretas:
        pt_rows = await cretas.fetch(
            "SELECT id, name FROM product_types "
            "WHERE factory_id = $1 AND deleted_at IS NULL",
            factory_id,
        )
        alias_rows = await cretas.fetch(
            "SELECT pos_name, product_type_id FROM dim_product_alias WHERE factory_id = $1",
            factory_id,
        )

    # Exact-name set (L1: ETL compares product_types.name against normalized_name).
    pt_exact = {r["name"]: r["id"] for r in pt_rows}
    # Normalized product candidates (L2/L3).
    candidates = [
        {"id": r["id"], "name": r["name"], "_norm": _normalize_name(r["name"])}
        for r in pt_rows
    ]
    pt_norm_exact = {c["_norm"]: c for c in candidates}
    # Existing alias keys (L0) + normalized alias keys (L4 transitive).
    alias_exact = {r["pos_name"] for r in alias_rows}
    alias_norm = {}
    for r in alias_rows:
        alias_norm[_normalize_name(r["pos_name"])] = r["product_type_id"]

    # 3. Classify each distinct POS name.
    to_write_alias: List[tuple] = []   # (norm, pt_id, conf, source)
    to_queue: List[Dict[str, Any]] = []

    for d in distinct:
        raw_key = d["raw_key"]      # persisted alias/queue key (== dim_product.normalized_name)
        norm = d["norm_key"]        # fuzzy-comparison form only, never persisted

        # L0: already has an alias (exact RAW key — same form the alias table stores).
        if raw_key in alias_exact:
            result["alreadyResolved"] += 1
            continue
        # L1: product_types exact name == raw normalized_name (ETL primary path).
        if raw_key in pt_exact:
            result["alreadyResolved"] += 1
            continue
        # L2: normalized product name equals normalized key — persist the RAW key.
        if norm in pt_norm_exact:
            c = pt_norm_exact[norm]
            to_write_alias.append((raw_key, c["id"], L2_AUTO_CONFIDENCE, "fuzzy_match"))
            continue

        # L3: difflib best fuzzy match against normalized product candidates.
        best_id, best_name, ratio = _best_fuzzy_match(norm, candidates)
        if best_id is not None and ratio >= AUTO_ACCEPT_THRESHOLD:
            to_write_alias.append((raw_key, best_id, round(ratio, 2), "fuzzy_match"))
            continue

        # L4: transitive-over-alias (near-key match to a confirmed alias, normalized).
        t_id, t_ratio = None, 0.0
        for akey, apt in alias_norm.items():
            r2 = SequenceMatcher(None, norm, akey).ratio()
            if r2 > t_ratio:
                t_ratio, t_id = r2, apt
        transitive_conf = (
            round(L4_TRANSITIVE_BASE * L3_TRANSITIVE_DISCOUNT, 2)
            if (t_id is not None and t_ratio >= AUTO_ACCEPT_THRESHOLD) else None
        )

        # Pick the better signal (L3 candidate vs L4 transitive) for the queue hint.
        if best_id is not None and ratio >= QUEUE_LOWER_THRESHOLD:
            cand_id, cand_name, cand_conf = best_id, best_name, round(ratio, 2)
        elif transitive_conf is not None:
            cand_id, cand_name, cand_conf = t_id, None, transitive_conf
        else:
            cand_id, cand_name, cand_conf = None, None, None

        to_queue.append({
            **d,
            "best_candidate_id": cand_id,
            "best_candidate_name": cand_name,
            "best_confidence": cand_conf,
        })

    # 4. Write aliases (cretas).
    if to_write_alias:
        async with cretas_pool.acquire() as cretas:
            for raw_key, pt_id, conf, source in to_write_alias:
                await _upsert_alias(
                    cretas, factory_id, raw_key, pt_id, conf, source, "resolver",
                )
        result["resolvedAuto"] = len(to_write_alias)

    # 5. Write queue rows sorted by revenue_at_risk DESC (smartbi, RLS tenant set).
    if to_queue:
        to_queue.sort(key=lambda x: x["revenue"], reverse=True)
        async with smartbi_pool.acquire() as conn:
            async with conn.transaction():
                await _set_tenant(conn, factory_id)
                for q in to_queue:
                    await _upsert_queue_row(
                        conn, factory_id, q["raw_key"], q["display_name"],
                        q["bills"], q["revenue"],
                        q["best_candidate_id"], q["best_candidate_name"],
                        q["best_confidence"],
                    )
        result["queued"] = len(to_queue)

    logger.info(
        "[pos-name-resolver] factory=%s total=%d alreadyResolved=%d resolvedAuto=%d queued=%d",
        factory_id, result["totalPosNames"], result["alreadyResolved"],
        result["resolvedAuto"], result["queued"],
    )
    return result
