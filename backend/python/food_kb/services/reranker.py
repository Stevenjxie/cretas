"""
DashScope Reranker 精排服务
Cross-encoder reranking for food knowledge RAG using DashScope qwen3-rerank.

Strategy: Coarse rank top_k=20 via pgvector → Reranker re-score → Return top_n=5

Usage:
    reranker = get_reranker()
    reranker.configure(api_key="sk-xxx")
    reranked = await reranker.rerank(query, documents, top_n=5)

⚠️ DOES NOT route through common.llm_router.call_chain — rerank (text-rerank
service) is a SEPARATE DashScope API + billing pool from the chat-slot LLMs.
call_chain only injects chat models, which cannot serve the rerank endpoint. So
the chat免费链 (c→b→a→zhipu fallback + circuit breaker) does NOT apply; this
stays a direct DashScope rerank call.

gte-rerank-v2 免费额度确认 (2026-06-01 live probe, prod keys LLM_ALIYUN_C /
LLM_ALIYUN_A):
    POST /api/v1/services/rerank/text-rerank/text-rerank
         {"model":"gte-rerank-v2", "input":{...}, "parameters":{...}}
    → HTTP 200 on both aliyun_C and aliyun_A (working, NOT exhausted;
      usage.total_tokens reported). DashScope grants gte-rerank-v2 its own free
      quota, independent of the chat-LLM 免费清单. When that free quota
      exhausts, DashScope returns 403 AllocationQuota.FreeTierOnly — already
      surfaced LOUD by the httpx.HTTPStatusError handler below (logs status +
      body at ERROR), and the reranker degrades gracefully to the original
      pgvector order (no crash). Re-audit when this handler starts logging 403.
"""

import logging
import time
from typing import List, Dict, Any, Optional

import httpx

logger = logging.getLogger(__name__)

# DashScope Reranker API endpoint
RERANK_URL = "https://dashscope.aliyuncs.com/api/v1/services/rerank/text-rerank/text-rerank"

# Model: qwen3-rerank (cheapest, ¥0.0005/1K tokens, 500 docs, 4K tokens/item)
DEFAULT_MODEL = "gte-rerank-v2"


class DashScopeReranker:
    """
    DashScope Reranker — 交叉注意力精排

    Bi-encoder (embedding) captures shallow semantics.
    Cross-encoder (reranker) captures deep query-document interaction.
    Combining both typically improves recall@5 by 10-15%.
    """

    def __init__(self):
        self._api_key: str = ""
        self._model: str = DEFAULT_MODEL
        self._client: Optional[httpx.AsyncClient] = None
        self._enabled: bool = False
        self._stats = {"calls": 0, "errors": 0, "avg_latency_ms": 0.0}

    def configure(self, api_key: str, model: str = DEFAULT_MODEL, enabled: bool = True):
        """Configure the reranker with DashScope API key."""
        self._api_key = api_key
        self._model = model
        self._enabled = enabled and bool(api_key)
        if self._enabled:
            self._client = httpx.AsyncClient(timeout=15.0)
            logger.info(f"DashScope Reranker configured: model={self._model}")
        else:
            logger.info("DashScope Reranker disabled")

    @property
    def is_enabled(self) -> bool:
        return self._enabled and bool(self._api_key)

    @property
    def stats(self) -> dict:
        return dict(self._stats)

    async def rerank(
        self,
        query: str,
        documents: List[Dict[str, Any]],
        top_n: int = 5,
    ) -> List[Dict[str, Any]]:
        """
        Rerank documents using DashScope cross-encoder.

        Args:
            query: User query text
            documents: List of doc dicts with at least 'title' and 'content'
            top_n: Number of top results to return

        Returns:
            Reranked list of document dicts with added 'rerank_score' field.
            Falls back to original order if reranker fails.
        """
        if not self.is_enabled or not documents:
            return documents[:top_n]

        start = time.time()

        try:
            # Build document texts for reranking
            # Combine title + content snippet for each doc (stay within 4K token limit)
            doc_texts = []
            for doc in documents:
                title = doc.get("title", "")
                content = doc.get("content", "")
                # Limit to ~1500 chars to stay within 4K token limit
                text = f"{title}\n{content[:1500]}"
                doc_texts.append(text)

            # Call DashScope Reranker API
            # gte-rerank-v2 uses input/parameters wrapper; documents are plain strings
            # qwen3-rerank uses flat format
            if self._model.startswith("gte-rerank"):
                payload = {
                    "model": self._model,
                    "input": {
                        "query": query,
                        "documents": doc_texts,  # plain string array for gte-rerank
                    },
                    "parameters": {
                        "top_n": min(top_n, len(documents)),
                        "return_documents": False,
                    },
                }
            else:
                # qwen3-rerank flat format
                payload = {
                    "model": self._model,
                    "query": query,
                    "documents": doc_texts,
                    "top_n": min(top_n, len(documents)),
                    "return_documents": False,
                }

            resp = await self._client.post(
                RERANK_URL,
                headers={
                    "Authorization": f"Bearer {self._api_key}",
                    "Content-Type": "application/json",
                },
                json=payload,
            )
            resp.raise_for_status()
            data = resp.json()

            # Parse results
            rerank_results = data.get("output", {}).get("results", [])
            if not rerank_results:
                logger.warning("Reranker returned empty results, using original order")
                return documents[:top_n]

            # Map reranked indices back to original documents
            reranked = []
            for rr in rerank_results:
                idx = rr.get("index", 0)
                score = rr.get("relevance_score", 0.0)
                if 0 <= idx < len(documents):
                    doc = dict(documents[idx])
                    doc["rerank_score"] = round(score, 4)
                    doc["original_rank"] = idx + 1
                    reranked.append(doc)

            elapsed_ms = (time.time() - start) * 1000
            self._stats["calls"] += 1
            # Running average
            n = self._stats["calls"]
            self._stats["avg_latency_ms"] = (
                self._stats["avg_latency_ms"] * (n - 1) + elapsed_ms
            ) / n

            logger.info(
                f"Reranker: {len(documents)} docs → top {len(reranked)}, "
                f"latency={elapsed_ms:.0f}ms, "
                f"top_score={reranked[0]['rerank_score']:.4f}" if reranked else ""
            )

            return reranked

        except httpx.HTTPStatusError as e:
            elapsed_ms = (time.time() - start) * 1000
            self._stats["errors"] += 1
            logger.error(
                f"Reranker API error: {e.response.status_code} "
                f"({elapsed_ms:.0f}ms): {e.response.text[:200]}"
            )
            return documents[:top_n]

        except Exception as e:
            elapsed_ms = (time.time() - start) * 1000
            self._stats["errors"] += 1
            logger.error(f"Reranker failed ({elapsed_ms:.0f}ms): {e}")
            return documents[:top_n]

    async def close(self):
        """Close HTTP client."""
        if self._client:
            await self._client.aclose()
            self._client = None


# ── Global singleton ──
_reranker_instance: Optional[DashScopeReranker] = None


def get_reranker() -> DashScopeReranker:
    global _reranker_instance
    if _reranker_instance is None:
        _reranker_instance = DashScopeReranker()
    return _reranker_instance
