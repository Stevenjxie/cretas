"""
食品知识库向量嵌入服务
Embedding service for food knowledge base using DashScope text-embedding-v3.

Uses OpenAI-compatible API (DashScope) to generate 768-dim vectors.
Reuses llm_api_key + llm_base_url from SmartBI config.

⚠️ DOES NOT route through common.llm_router.call_chain — embedding is a
SEPARATE DashScope API + billing pool from the chat-slot LLMs (CHAT/INSIGHTS/
CHART/...). call_chain only injects chat models (qwen-flash, glm-5, ...), which
cannot serve /embeddings. So the chat免费链 (c→b→a→zhipu fallback + circuit
breaker) does NOT apply here; this stays a direct DashScope embeddings call.

text-embedding-v3 免费额度确认 (2026-06-01 live probe, prod keys LLM_ALIYUN_C /
LLM_ALIYUN_A):
    POST /compatible-mode/v1/embeddings {"model":"text-embedding-v3", ...}
    → HTTP 200 on both aliyun_C and aliyun_A (working, NOT exhausted).
DashScope grants text-embedding-v3 its own free quota (independent of the
chat-LLM 免费清单 in memory reference_dashscope_free_model_allowlist). When that
free quota exhausts, DashScope returns 403 AllocationQuota.FreeTierOnly — which
we now log at WARNING (see get_embedding error handler) so exhaustion is loud,
not buried in a generic ERROR. Re-audit when food-KB ingest/search RAG starts
emitting 403 quota WARNINGs.
"""

import logging
from typing import List, Optional

import httpx

logger = logging.getLogger(__name__)

# Module-level config
_api_key: str = ""
_base_url: str = "https://dashscope.aliyuncs.com/compatible-mode/v1"
_model: str = "text-embedding-v3"
_dims: int = 768
_client: Optional[httpx.AsyncClient] = None


def configure(api_key: str, base_url: str, model: str = "text-embedding-v3", dims: int = 768):
    """Configure the embedding service."""
    global _api_key, _base_url, _model, _dims, _client
    _api_key = api_key
    _base_url = base_url.rstrip("/")
    _model = model
    _dims = dims
    _client = httpx.AsyncClient(timeout=30.0)
    logger.info(f"Embedding service configured: model={_model}, dims={_dims}")


async def get_embedding(text: str) -> Optional[List[float]]:
    """
    Generate embedding vector for a single text.

    Uses DashScope text-embedding-v3 via OpenAI-compatible endpoint.
    Returns 768-dim float list, or None on error.
    """
    global _client
    if not _api_key:
        logger.warning("Embedding service not configured (no API key)")
        return None

    if _client is None:
        _client = httpx.AsyncClient(timeout=30.0)

    try:
        resp = await _client.post(
            f"{_base_url}/embeddings",
            headers={
                "Authorization": f"Bearer {_api_key}",
                "Content-Type": "application/json",
            },
            json={
                "model": _model,
                "input": text,
                "dimensions": _dims,
            },
        )
        resp.raise_for_status()
        data = resp.json()
        embedding = data["data"][0]["embedding"]
        return embedding

    except httpx.HTTPStatusError as e:
        # Surface 403 AllocationQuota.FreeTierOnly distinctly so free-quota
        # exhaustion is loud (not buried in a generic ERROR). Still degrade
        # gracefully (return None) — caller (RAG retriever) handles a missing
        # vector by falling back to BM25 / keyword search.
        body = e.response.text[:200] if e.response is not None else ""
        status = e.response.status_code if e.response is not None else "?"
        if status == 403 and ("FreeTierOnly" in body or "AllocationQuota" in body):
            logger.warning(
                f"Embedding text-embedding-v3 FREE-TIER EXHAUSTED "
                f"(403 AllocationQuota.FreeTierOnly): {body} — "
                f"re-audit DashScope embedding free quota"
            )
        else:
            logger.error(f"Embedding generation failed (HTTP {status}): {body}")
        return None
    except Exception as e:
        logger.error(f"Embedding generation failed: {e}")
        return None


async def get_embeddings_batch(texts: List[str], batch_size: int = 20) -> List[Optional[List[float]]]:
    """
    Generate embeddings for multiple texts in batches.

    DashScope supports batch input. Returns list of embeddings (None for failures).
    """
    global _client
    if not _api_key:
        return [None] * len(texts)

    if _client is None:
        _client = httpx.AsyncClient(timeout=60.0)

    results: List[Optional[List[float]]] = [None] * len(texts)

    for i in range(0, len(texts), batch_size):
        batch = texts[i:i + batch_size]
        try:
            resp = await _client.post(
                f"{_base_url}/embeddings",
                headers={
                    "Authorization": f"Bearer {_api_key}",
                    "Content-Type": "application/json",
                },
                json={
                    "model": _model,
                    "input": batch,
                    "dimensions": _dims,
                },
            )
            resp.raise_for_status()
            data = resp.json()
            for item in data["data"]:
                idx = i + item["index"]
                results[idx] = item["embedding"]
        except httpx.HTTPStatusError as e:
            # Distinct WARNING on 403 free-tier exhaustion (see get_embedding).
            body = e.response.text[:200] if e.response is not None else ""
            status = e.response.status_code if e.response is not None else "?"
            if status == 403 and ("FreeTierOnly" in body or "AllocationQuota" in body):
                logger.warning(
                    f"Batch embedding text-embedding-v3 FREE-TIER EXHAUSTED "
                    f"(403 AllocationQuota.FreeTierOnly) at batch {i}: {body} — "
                    f"re-audit DashScope embedding free quota"
                )
            else:
                logger.error(
                    f"Batch embedding failed for batch starting at {i} "
                    f"(HTTP {status}): {body}"
                )
        except Exception as e:
            logger.error(f"Batch embedding failed for batch starting at {i}: {e}")

    return results


async def close():
    """Close the HTTP client."""
    global _client
    if _client:
        await _client.aclose()
        _client = None
