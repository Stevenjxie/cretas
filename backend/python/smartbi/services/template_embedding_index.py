"""Template embedding index service.

populate_all() iterates every registered template, embeds each
sample_query via the local gRPC embedding-service, and upserts into
smart_bi_template_embeddings. Called at startup and from the
/admin/template-embeddings/rebuild endpoint.

cosine_topk(query_embedding, k) performs the hot-path lookup used
by template_rag.hybrid_match.

Design choices:
  - Fire-and-forget philosophy: embedding failures for individual
    sample_queries are logged as warnings; the table ends up with
    partial coverage rather than failing the whole populate.
  - pgvector literal format: `[0.1,0.2,...]` (same as
    llm_fallback_logger).
  - ON CONFLICT DO UPDATE on (template_code, sample_query) so
    re-populate is idempotent and re-embeds on model version change.
"""
from __future__ import annotations

import logging
from typing import Any, Dict, List, Optional, Tuple

logger = logging.getLogger(__name__)

_MODEL = "gte-base-zh"


async def _get_embedding(text: str) -> Optional[List[float]]:
    """Lazy-imported local embedding call; returns None on failure."""
    try:
        from food_kb.services.embedding import get_embedding as _real
        return await _real(text)
    except Exception as e:
        logger.warning(f"[template-emb] embedding call failed for {text[:40]!r}: {e}")
        return None


def _emb_literal(emb: List[float]) -> str:
    return f"[{','.join(str(x) for x in emb)}]"


_UPSERT_SQL = """
    INSERT INTO smart_bi_template_embeddings
      (template_code, sample_query, query_embedding, embedding_model)
    VALUES ($1, $2, $3::vector, $4)
    ON CONFLICT (template_code, sample_query) DO UPDATE
      SET query_embedding = EXCLUDED.query_embedding,
          embedding_model = EXCLUDED.embedding_model,
          created_at      = NOW()
"""


async def _embed_and_upsert_samples(
    pool, code_to_samples: Dict[str, List[str]], *, log_prefix: str = "template-emb",
) -> Dict[str, int]:
    """Shared embed+upsert loop used by both populate_all and
    populate_restaurant_ops -- same fire-and-forget philosophy (embedding
    failures logged per-sample, table ends up partially populated rather than
    failing the whole batch)."""
    summary: Dict[str, int] = {}
    for code, samples in code_to_samples.items():
        if not samples:
            summary[code] = 0
            continue
        n = 0
        for q in samples:
            emb = await _get_embedding(q)
            if emb is None:
                continue
            try:
                async with pool.acquire() as conn:
                    await conn.execute(_UPSERT_SQL, code, q, _emb_literal(emb), _MODEL)
                n += 1
            except Exception as e:
                logger.warning(f"[{log_prefix}] upsert failed for {code}/{q[:40]!r}: {e}")
        summary[code] = n
        logger.info(f"[{log_prefix}] {code}: {n}/{len(samples)} embedded")
    return summary


async def populate_all(pool) -> Dict[str, int]:
    """Embed every template's sample_queries and upsert into the index.

    Returns a dict of {template_code: n_embedded} for observability.
    Cheap to re-run — ON CONFLICT DO UPDATE makes it idempotent.
    """
    from smartbi.services.materialized_analytics.templates.registry import (
        get_registry, load_all_templates,
    )
    try:
        load_all_templates()
    except Exception:
        pass  # registry idempotency — already loaded is fine
    registry = get_registry()

    code_to_samples = {
        template.code: getattr(template, "sample_queries", [])
        for template in registry.all()
    }
    summary = await _embed_and_upsert_samples(pool, code_to_samples)
    total = sum(summary.values())
    logger.info(f"[template-emb] populate_all complete: {total} embeddings across {len(summary)} templates")
    return summary


async def populate_restaurant_ops(pool) -> Dict[str, int]:
    """Embed restaurant_ops_router.SAMPLE_QUERIES and upsert into the index.

    2026-07-07 restaurant intent tiered routing design section 3.3: these
    sample queries existed since Plan C ("used both for keyword matching AND
    for RAG semantic routing (Phase 3)") but were never actually embedded
    before this. template_code = the RESTAURANT_OPS_* code, which already
    carries the namespace prefix cosine_topk's code_prefix/exclude_prefix
    filters on -- no separate namespacing needed.

    Idempotent (ON CONFLICT DO UPDATE, same as populate_all) -- safe to call
    on every boot.
    """
    from smartbi.gold.restaurant_ops_router import SAMPLE_QUERIES

    summary = await _embed_and_upsert_samples(
        pool, SAMPLE_QUERIES, log_prefix="restaurant-ops-emb",
    )
    total = sum(summary.values())
    logger.info(
        f"[restaurant-ops-emb] populate_restaurant_ops complete: "
        f"{total} embeddings across {len(summary)} templates"
    )
    return summary


async def cosine_topk(
    pool, query: str, k: int = 3, min_similarity: float = 0.70,
    code_prefix: Optional[str] = None, exclude_prefix: Optional[str] = None,
) -> List[Tuple[str, float, str]]:
    """Embed query + fetch top-k nearest (template_code, similarity, matched_sample).

    Returns list sorted by similarity desc. Rows below min_similarity are
    dropped. Returns empty list on embedding failure.

    `code_prefix` / `exclude_prefix` (2026-07-07, restaurant intent tiered
    routing design section 3.3): both default to None, which is the exact
    prior behavior (zero regression for existing callers). Passing
    `code_prefix='RESTAURANT_OPS_'` scopes the search to restaurant ops
    templates only (used by the restaurant intent T2 vector tier).
    `exclude_prefix='RESTAURANT_OPS_'` is the mirror -- used by
    template_rag.hybrid_match (the FACTORY-side path) so a factory query can
    never vector-match a restaurant-only template, giving bidirectional
    business-type isolation. The two params are mutually exclusive; passing
    both raises ValueError (caller error, not a runtime data condition).
    """
    if code_prefix and exclude_prefix:
        raise ValueError("cosine_topk: code_prefix and exclude_prefix are mutually exclusive")
    emb = await _get_embedding(query)
    if emb is None:
        return []
    lit = _emb_literal(emb)
    where_clause = ""
    params: List[Any] = [lit]
    if code_prefix:
        where_clause = "WHERE template_code LIKE $3"
        params.append(f"{code_prefix}%")
    elif exclude_prefix:
        where_clause = "WHERE template_code NOT LIKE $3"
        params.append(f"{exclude_prefix}%")
    sql = f"""
        SELECT template_code, sample_query,
               1 - (query_embedding <=> $1::vector) AS similarity
        FROM smart_bi_template_embeddings
        {where_clause}
        ORDER BY query_embedding <=> $1::vector ASC
        LIMIT $2
    """
    # k is bound as $2 regardless of where_clause presence -- insert it in
    # the middle rather than appending, so the prefix param (if any) can stay
    # at a stable $3 position matching the f-string above.
    params.insert(1, k)
    try:
        async with pool.acquire() as conn:
            rows = await conn.fetch(sql, *params)
    except Exception as e:
        logger.warning(f"[template-emb] cosine_topk failed: {e}")
        return []
    return [
        (r["template_code"], float(r["similarity"]), r["sample_query"])
        for r in rows
        if float(r["similarity"]) >= min_similarity
    ]


async def count_embeddings(pool, code_prefix: Optional[str] = None) -> int:
    """Return total number of rows for the current embedding model.

    Used by the lifespan hook to decide whether to run populate_all at
    startup. Counting the current model only prevents old external-model rows
    from suppressing a gte-base-zh rebuild.

    `code_prefix` (2026-07-07, restaurant intent tiered routing design
    section 3.3): optional scope to just one template_code namespace, e.g.
    'RESTAURANT_OPS_'. Defaults to None (all templates -- prior behavior,
    zero regression for existing callers).
    """
    try:
        async with pool.acquire() as conn:
            if code_prefix:
                n = await conn.fetchval(
                    "SELECT COUNT(*) FROM smart_bi_template_embeddings "
                    "WHERE embedding_model = $1 AND template_code LIKE $2",
                    _MODEL, f"{code_prefix}%",
                )
            else:
                n = await conn.fetchval(
                    "SELECT COUNT(*) FROM smart_bi_template_embeddings "
                    "WHERE embedding_model = $1",
                    _MODEL,
                )
        return int(n or 0)
    except Exception as e:
        logger.warning(f"[template-emb] count failed: {e}")
        return 0
