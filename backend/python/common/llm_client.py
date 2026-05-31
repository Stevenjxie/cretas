"""
Shared LLM HTTP Client

Provides a process-wide httpx.AsyncClient singleton with connection pooling
for all DashScope LLM API calls. Eliminates per-request DNS+TLS handshake
overhead (~200ms savings on subsequent calls).

Usage:
    from common.llm_client import get_llm_http_client
    client = get_llm_http_client()
    resp = await client.post(url, json=payload, timeout=httpx.Timeout(30.0))
"""
from __future__ import annotations

import logging
from typing import Any, Optional

import httpx

logger = logging.getLogger(__name__)

_client: Optional[httpx.AsyncClient] = None
_wrapper: "Optional[_RedactingLLMClient]" = None


class _RedactingLLMClient:
    """P0 数据主权 — 共享 httpx 客户端的脱敏包装层。

    所有 LLM 调用 (insights / Java 意图 / Excel 字段识别/映射/清洗/结构分析/评论分析
    / 跨表聚合 等) 都经 get_llm_http_client() 拿到这个 client。在 post/stream 出境前对
    `json` payload **字典级**脱敏 (无 httpx 内部 hack), 再委托给真实客户端。

    脱敏 scope-aware: insights generator 设了 RedactionScope 时, 客户名/门店名/菜品名
    用 known-values 占位 + 输出还原; 无 scope 时对所有出境施加 PII + factory 兜底。

    Fail-open: 任何脱敏异常 → CRITICAL 日志 + 发送原 payload (可用性优先, 错误可见)。
    审计在 metrics_response_hook 统一记录 (universal, 含真实 status_code)。
    """

    def __init__(self, inner: httpx.AsyncClient) -> None:
        self._inner = inner

    def _maybe_redact(self, url: Any, json_payload: Any) -> Any:
        if json_payload is None or "/chat/completions" not in str(url):
            return json_payload
        try:
            from common.llm_redactor import redact_payload_for_egress
            from common.llm_metrics import set_egress_redaction_meta
            redacted, meta = redact_payload_for_egress(json_payload)
            set_egress_redaction_meta(meta)
            return redacted
        except Exception as e:  # fail-open: availability over a single missed redaction
            logger.critical(
                "[P0-redact] egress redaction FAILED, sending ORIGINAL payload "
                "(LEAK RISK — investigate): %s", e,
            )
            return json_payload

    async def post(self, url: Any, *, json: Any = None, **kwargs: Any):  # noqa: A002
        json = self._maybe_redact(url, json)
        return await self._inner.post(url, json=json, **kwargs)

    def stream(self, method: Any, url: Any, *, json: Any = None, **kwargs: Any):  # noqa: A002
        json = self._maybe_redact(url, json)
        return self._inner.stream(method, url, json=json, **kwargs)

    async def aclose(self):
        return await self._inner.aclose()

    def __getattr__(self, name: str):
        # Delegate everything else (headers, is_closed, build_request, ...) to the inner client.
        return getattr(self._inner, name)


def _ensure_inner_client() -> httpx.AsyncClient:
    global _client
    if _client is None:
        logger.warning("Shared LLM client not initialized, creating fallback")
        _client = httpx.AsyncClient(
            limits=httpx.Limits(
                max_connections=20,
                max_keepalive_connections=10,
                keepalive_expiry=30,
            ),
            timeout=httpx.Timeout(120.0),
        )
    return _client


def get_llm_http_client() -> "_RedactingLLMClient":
    """
    Return the shared LLM HTTP client wrapped in the P0 redaction layer.

    If init_llm_client() hasn't been called yet (e.g. during testing or
    lazy startup), creates a fallback inner client with default pool settings.
    The wrapper redacts every /chat/completions payload before egress.
    """
    global _wrapper
    inner = _ensure_inner_client()
    if _wrapper is None or _wrapper._inner is not inner:
        _wrapper = _RedactingLLMClient(inner)
    return _wrapper


async def init_llm_client(base_url: str, api_key: str) -> None:
    """
    Initialize the shared LLM HTTP client with connection pool.

    Call once during application startup (lifespan).

    Args:
        base_url: DashScope API base URL (e.g. https://dashscope.aliyuncs.com/compatible-mode/v1)
        api_key: DashScope API key
    """
    global _client
    if _client is not None:
        await _client.aclose()

    # Attach metrics hook — captures model/tokens/latency per call into
    # smart_bi_llm_usage (async, non-blocking). Enabled after pool ready
    # via enable_llm_metrics() in the lifespan; hook itself is a no-op
    # until then.
    from common.llm_metrics import metrics_response_hook
    _client = httpx.AsyncClient(
        limits=httpx.Limits(
            max_connections=20,
            max_keepalive_connections=10,
            keepalive_expiry=30,
        ),
        timeout=httpx.Timeout(120.0),
        event_hooks={"response": [metrics_response_hook]},
    )
    logger.info("Shared LLM HTTP client created (pool: 20 max, 10 keepalive, metrics hook)")

    # Warmup: send a minimal request to establish connection + TLS
    if api_key:
        try:
            resp = await _client.post(
                f"{base_url}/chat/completions",
                headers={
                    "Authorization": f"Bearer {api_key}",
                    "Content-Type": "application/json",
                },
                json={
                    "model": "qwen3.5-flash",
                    "messages": [{"role": "user", "content": "hi"}],
                    "max_tokens": 1,
                    "enable_thinking": False,
                },
                timeout=httpx.Timeout(10.0),
            )
            logger.info(f"LLM client warmup request: status={resp.status_code}")
        except Exception as e:
            logger.warning(f"LLM client warmup failed (non-fatal): {e}")


async def close_llm_client() -> None:
    """Close the shared LLM HTTP client. Call during application shutdown."""
    global _client, _wrapper
    if _client is not None:
        await _client.aclose()
        _client = None
        _wrapper = None
        logger.info("Shared LLM HTTP client closed")
