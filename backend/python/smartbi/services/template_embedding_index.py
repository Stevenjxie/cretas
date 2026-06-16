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
from typing import Dict, List, Optional, Tuple

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

    summary: Dict[str, int] = {}
    sql = """
        INSERT INTO smart_bi_template_embeddings
          (template_code, sample_query, query_embedding, embedding_model)
        VALUES ($1, $2, $3::vector, $4)
        ON CONFLICT (template_code, sample_query) DO UPDATE
          SET query_embedding = EXCLUDED.query_embedding,
              embedding_model = EXCLUDED.embedding_model,
              created_at      = NOW()
    """
    for template in registry.all():
        code = template.code
        samples = getattr(template, "sample_queries", [])
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
                    await conn.execute(sql, code, q, _emb_literal(emb), _MODEL)
                n += 1
            except Exception as e:
                logger.warning(f"[template-emb] upsert failed for {code}/{q[:40]!r}: {e}")
        summary[code] = n
        logger.info(f"[template-emb] {code}: {n}/{len(samples)} embedded")
    total = sum(summary.values())
    logger.info(f"[template-emb] populate_all complete: {total} embeddings across {len(summary)} templates")
    return summary


async def cosine_topk(
    pool, query: str, k: int = 3, min_similarity: float = 0.70,
) -> List[Tuple[str, float, str]]:
    """Embed query + fetch top-k nearest (template_code, similarity, matched_sample).

    Returns list sorted by similarity desc. Rows below min_similarity are
    dropped. Returns empty list on embedding failure.
    """
    emb = await _get_embedding(query)
    if emb is None:
        return []
    lit = _emb_literal(emb)
    sql = """
        SELECT template_code, sample_query,
               1 - (query_embedding <=> $1::vector) AS similarity
        FROM smart_bi_template_embeddings
        ORDER BY query_embedding <=> $1::vector ASC
        LIMIT $2
    """
    try:
        async with pool.acquire() as conn:
            rows = await conn.fetch(sql, lit, k)
    except Exception as e:
        logger.warning(f"[template-emb] cosine_topk failed: {e}")
        return []
    return [
        (r["template_code"], float(r["similarity"]), r["sample_query"])
        for r in rows
        if float(r["similarity"]) >= min_similarity
    ]


async def count_embeddings(pool) -> int:
    """Return total number of rows for the current embedding model.

    Used by the lifespan hook to decide whether to run populate_all at
    startup. Counting the current model only prevents old external-model rows
    from suppressing a gte-base-zh rebuild.
    """
    try:
        async with pool.acquire() as conn:
            n = await conn.fetchval(
                "SELECT COUNT(*) FROM smart_bi_template_embeddings "
                "WHERE embedding_model = $1",
                _MODEL,
            )
        return int(n or 0)
    except Exception as e:
        logger.warning(f"[template-emb] count failed: {e}")
        return 0
