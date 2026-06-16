"""
C1 MVP (Apr 20 2026): Embedding-first semantic mapping layer.

Pre-computes local gte-base-zh (768-dim) vectors for every
STANDARD_FIELDS entry at first use, caches in module-level dict, and
provides cosine similarity lookup for rule-fail columns BEFORE falling
through to LLM.

Why this layer (between rules and LLM):
- Rules miss on synonyms we never added ("主营产品收入" / "门店名称" / "卖场编号").
- LLM is slow (~1-3s/call) and expensive. Embedding lookup is ~50ms + cached.
- Local embedding-service is already configured by main.py startup (shared
  module state in food_kb.services.embedding).
- MVP uses in-memory numpy storage (~25 fields fits easily; can upgrade to
  pgvector when we hit >1K fields).

Usage:
    from smartbi.services.standard_field_embedder import find_best_matches
    matches = await find_best_matches("门店销售金额", sample_values=["100", "200"])
    # → [("revenue", "amount", 0.88), ("category", "category", 0.62), ...]
"""

from __future__ import annotations

import asyncio
import logging
from typing import Dict, List, Optional, Tuple

import numpy as np

logger = logging.getLogger(__name__)

# Module state: standard_name → (vector, category)
_index_vectors: Dict[str, np.ndarray] = {}
_index_categories: Dict[str, str] = {}
_index_ready: bool = False
_build_lock = asyncio.Lock()


def _build_embedding_text(standard_name: str, info: dict) -> str:
    """Generate the canonical text to embed for one STANDARD_FIELDS entry."""
    parts = [standard_name]
    if info.get("description"):
        parts.append(info["description"])
    synonyms = info.get("synonyms") or []
    if synonyms:
        parts.append("synonyms: " + " | ".join(synonyms))
    if info.get("category"):
        parts.append(f"category: {info['category']}")
    return " · ".join(parts)


async def build_index(force: bool = False) -> int:
    """
    Build in-memory embedding index. Returns count of successfully embedded
    fields. Safe to call multiple times — memoized after first success.
    """
    global _index_ready
    # Late import to avoid circular dep. Look up STANDARD_FIELDS from
    # sys.modules first (standalone verify scripts pre-load it under a
    # different module alias), then fall through to the canonical package
    # path used at production runtime.
    import sys as _sys
    STANDARD_FIELDS = None
    for _alias in ("sm", "sm_layer_a", "sm_c1", "smartbi.services.semantic_mapper"):
        _m = _sys.modules.get(_alias)
        if _m is not None and hasattr(_m, "STANDARD_FIELDS"):
            STANDARD_FIELDS = getattr(_m, "STANDARD_FIELDS")
            break
    if STANDARD_FIELDS is None:
        from smartbi.services.semantic_mapper import STANDARD_FIELDS  # noqa: F811
    from food_kb.services import embedding as embedding_service

    async with _build_lock:
        if _index_ready and not force:
            return len(_index_vectors)

        names = list(STANDARD_FIELDS.keys())
        texts = [_build_embedding_text(n, STANDARD_FIELDS[n]) for n in names]

        vectors = await embedding_service.get_embeddings_batch(texts, batch_size=20)

        count = 0
        for name, vec in zip(names, vectors):
            if vec is None:
                logger.warning(f"[embedder] Failed to embed STANDARD_FIELD {name!r}")
                continue
            _index_vectors[name] = np.asarray(vec, dtype=np.float32)
            _index_categories[name] = STANDARD_FIELDS[name].get("category", "unknown")
            count += 1

        _index_ready = count > 0
        logger.info(
            f"[embedder] Built {count}/{len(names)} STANDARD_FIELDS vectors "
            f"(ready={_index_ready})"
        )
        return count


def _cosine(a: np.ndarray, b: np.ndarray) -> float:
    denom = float(np.linalg.norm(a) * np.linalg.norm(b))
    if denom < 1e-9:
        return 0.0
    return float(np.dot(a, b) / denom)


async def find_best_matches(
    col_name: str,
    sample_values: Optional[List[object]] = None,
    top_k: int = 3,
) -> List[Tuple[str, str, float]]:
    """
    Cosine lookup for a single column. Returns top_k matches sorted desc by
    similarity. Each tuple: (standard_name, category, cosine).

    Returns [] if the index is empty OR if the query embedding fails.
    """
    from food_kb.services import embedding as embedding_service

    if not _index_ready:
        await build_index()
    if not _index_ready:
        return []

    query_parts = [col_name]
    if sample_values:
        joined = " | ".join(
            str(v) for v in sample_values[:3]
            if v is not None and str(v).strip() not in ("", "nan", "None")
        )
        if joined:
            query_parts.append(f"samples: {joined}")
    query_text = " · ".join(query_parts)

    query_vec_raw = await embedding_service.get_embedding(query_text)
    if query_vec_raw is None:
        return []
    query_vec = np.asarray(query_vec_raw, dtype=np.float32)

    scored = [
        (name, _index_categories[name], _cosine(query_vec, vec))
        for name, vec in _index_vectors.items()
    ]
    scored.sort(key=lambda x: x[2], reverse=True)
    return scored[:top_k]


async def find_best_matches_batch(
    columns: List[str],
    samples_per_column: Optional[Dict[str, List[object]]] = None,
    top_k: int = 3,
) -> Dict[str, List[Tuple[str, str, float]]]:
    """
    Batch version — embeds all query columns in a single DashScope call to
    keep latency low when many columns are unmapped.
    """
    from food_kb.services import embedding as embedding_service

    if not _index_ready:
        await build_index()
    if not _index_ready:
        return {c: [] for c in columns}

    # Build query texts
    samples_per_column = samples_per_column or {}
    query_texts = []
    for col in columns:
        parts = [col]
        sv = samples_per_column.get(col) or []
        joined = " | ".join(
            str(v) for v in sv[:3]
            if v is not None and str(v).strip() not in ("", "nan", "None")
        )
        if joined:
            parts.append(f"samples: {joined}")
        query_texts.append(" · ".join(parts))

    query_vecs = await embedding_service.get_embeddings_batch(query_texts, batch_size=20)

    results: Dict[str, List[Tuple[str, str, float]]] = {}
    for col, qvec_raw in zip(columns, query_vecs):
        if qvec_raw is None:
            results[col] = []
            continue
        qvec = np.asarray(qvec_raw, dtype=np.float32)
        scored = [
            (name, _index_categories[name], _cosine(qvec, vec))
            for name, vec in _index_vectors.items()
        ]
        scored.sort(key=lambda x: x[2], reverse=True)
        results[col] = scored[:top_k]
    return results


def index_size() -> int:
    return len(_index_vectors)


def is_ready() -> bool:
    return _index_ready
