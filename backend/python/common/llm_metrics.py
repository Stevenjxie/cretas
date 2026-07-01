"""
LLM call metrics via httpx event_hook.

Captures per-request (model, provider, tokens, latency, caller) and batches
writes to smart_bi_llm_usage. Queue-based so request path is non-blocking.

Enable by passing event_hooks={'response': [metrics_response_hook]} when
creating the shared httpx.AsyncClient in common/llm_client.py.

Caller attribution: set contextvars.ContextVar 'llm_caller' before making
the call; the hook reads it. Default 'unknown'.
"""
from __future__ import annotations

import asyncio
import contextvars
import json
import logging
import time
from typing import Any, Dict, Optional
from urllib.parse import urlparse

import httpx

logger = logging.getLogger(__name__)

# Caller attribution context var — set this before LLM calls, e.g.
#   with llm_caller_context("semantic_mapper"): await client.post(...)
_llm_caller: contextvars.ContextVar[str] = contextvars.ContextVar("llm_caller", default="unknown")
_llm_factory: contextvars.ContextVar[Optional[str]] = contextvars.ContextVar("llm_factory", default=None)
# Optional data-window label (e.g. "2025-12" / "2025-01..2025-12") for egress audit context.
_llm_data_window: contextvars.ContextVar[Optional[str]] = contextvars.ContextVar("llm_data_window", default=None)
# P0: redaction meta of the most recent egress payload (set by the redacting client wrapper,
# read by the egress-audit branch of metrics_response_hook). common.llm_redactor.RedactionMeta.
_egress_redaction_meta: contextvars.ContextVar[Any] = contextvars.ContextVar("egress_redaction_meta", default=None)


def set_egress_redaction_meta(meta: Any) -> None:
    """Stash the RedactionMeta for the in-flight egress so the response hook can audit it.

    Set without a reset token (request-scoped task isolation handles cleanup), mirroring
    the streaming caller-attribution pattern.
    """
    _egress_redaction_meta.set(meta)


def _payload_message_text(payload: Dict[str, Any]) -> str:
    """Concatenate message content text (str or [{text}] shape) for egress audit sha/chars."""
    parts = []
    for m in (payload.get("messages") or []):
        c = m.get("content")
        if isinstance(c, str):
            parts.append(c)
        elif isinstance(c, list):
            for p in c:
                if isinstance(p, dict) and isinstance(p.get("text"), str):
                    parts.append(p["text"])
    return "".join(parts)


class llm_caller_context:
    """Async-safe context manager for LLM caller attribution."""

    def __init__(self, caller: str, factory_id: Optional[str] = None) -> None:
        self.caller = caller
        self.factory_id = factory_id
        self._caller_token: Optional[contextvars.Token] = None
        self._factory_token: Optional[contextvars.Token] = None

    def __enter__(self):
        self._caller_token = _llm_caller.set(self.caller)
        self._factory_token = _llm_factory.set(self.factory_id)
        return self

    def __exit__(self, *_exc):
        if self._caller_token is not None:
            _llm_caller.reset(self._caller_token)
        if self._factory_token is not None:
            _llm_factory.reset(self._factory_token)


# Queue of (record_dict) for batched DB writes
_usage_queue: asyncio.Queue = asyncio.Queue(maxsize=10000)
_flush_task: Optional[asyncio.Task] = None
_enabled: bool = False


def _guess_provider(host: str, authorization_header: Optional[str] = None) -> str:
    """Guess provider account for usage attribution.

    Layer-5 fix (2026-07-01): previously only aliyun_a/b were keyed → aliyun_c (the
    freshest account, now in the pool) collapsed into the generic 'dashscope' bucket,
    so smart_bi_llm_usage could not tell C's burn/latency/errors apart. tencent
    (TokenHub) shares no host with dashscope but was also unattributed. Both fixed."""
    h = host.lower()
    # Tencent TokenHub — OpenAI-compatible, its own host.
    if "tokenhub" in h or "tencentmaas" in h:
        return "tencent"
    if "dashscope" in h or "aliyuncs" in h or "bailian" in h:
        # Distinguish aliyun_a / _b / _c by API key.
        if authorization_header:
            import os
            key = authorization_header.replace("Bearer ", "").strip()
            if key and key == os.getenv("LLM_ALIYUN_C_API_KEY", "___unset_c___"):
                return "aliyun_c"
            if key and key == os.getenv("LLM_ALIYUN_B_API_KEY", "___unset_b___"):
                return "aliyun_b"
            if key and (key == os.getenv("LLM_ALIYUN_A_API_KEY", "___unset_a___") or
                        key == os.getenv("LLM_API_KEY", "___unset_legacy___")):
                return "aliyun_a"
        return "dashscope"  # default when key doesn't match known accounts
    if "deepseek" in h:
        return "deepseek"
    if "bigmodel" in h or "zhipu" in h:
        return "zhipu"
    if "minimax" in h:
        return "minimax"
    if "moonshot" in h:
        return "moonshot"
    return "other"


async def metrics_response_hook(response: httpx.Response) -> None:
    """httpx event hook — called on every response. Non-blocking: parses +
    enqueues. DB write happens in background flusher."""
    if not _enabled:
        return
    url = str(response.url)
    if "/chat/completions" not in url and "/v1/" not in url:
        return  # only track chat/completions + v1 endpoints

    try:
        request = response.request
        payload: Dict[str, Any] = {}
        try:
            if request.content:
                payload = json.loads(request.content)
        except Exception:
            pass

        model = payload.get("model", "unknown")
        host = urlparse(url).netloc
        auth_header = request.headers.get("Authorization") or request.headers.get("authorization")
        provider = _guess_provider(host, auth_header)

        # Parse usage from response body (OpenAI-compatible schema)
        # In event_hook, body may not yet be read; call .aread() first
        input_tok = output_tok = total_tok = 0
        try:
            if not hasattr(response, "_content") or response._content is None:
                await response.aread()
            body = response.json()
            usage = body.get("usage") or {}
            input_tok = int(usage.get("prompt_tokens") or usage.get("input_tokens") or 0)
            output_tok = int(usage.get("completion_tokens") or usage.get("output_tokens") or 0)
            total_tok = int(usage.get("total_tokens") or (input_tok + output_tok))
        except Exception:
            pass

        # elapsed may raise if called before response is closed; skip quietly
        latency_ms = None
        try:
            elapsed = response.elapsed
            if elapsed is not None:
                latency_ms = int(elapsed.total_seconds() * 1000)
        except Exception:
            pass

        error_snippet = None
        if response.status_code >= 400:
            try:
                if not hasattr(response, "_content") or response._content is None:
                    await response.aread()
                error_snippet = response.text[:200]
            except Exception:
                error_snippet = f"status={response.status_code}"

        record = {
            "provider": provider,
            "model": model,
            "caller": _llm_caller.get(),
            "input_tokens": input_tok,
            "output_tokens": output_tok,
            "total_tokens": total_tok,
            "latency_ms": latency_ms,
            "status_code": response.status_code,
            "factory_id": _llm_factory.get(),
            "error_snippet": error_snippet,
        }
        # 流式响应: 钩子在响应头触发时 usage 还没到 (body 未读完 → 全 0)。这会记一条假
        # 0-token 记录 (ai_query_chat 3972 次记成 0 的根因)。改为: 流式跳过这里, 由
        # call_chain_stream 在流末尾用真实 usage 记账 (record_stream_usage), 避免假 0 + 双记。
        if not payload.get("stream"):
            try:
                _usage_queue.put_nowait(record)
            except asyncio.QueueFull:
                logger.warning("LLM usage queue full, dropping record")

        # P0 数据主权: egress audit (universal — every LLM call through the shared client).
        # Reads the redaction meta the redacting client wrapper stashed for this egress.
        try:
            from common.llm_egress_audit import (
                EgressAuditRecord, record_egress, sha256_of,
            )
            meta = _egress_redaction_meta.get()
            msg_text = _payload_message_text(payload)
            record_egress(EgressAuditRecord(
                call_site=_llm_caller.get(),
                provider=provider,
                model=model if isinstance(model, str) else str(model),
                slot=None,
                factory_id=_llm_factory.get(),
                sanitized=bool(getattr(meta, "sanitized", False)),
                redacted_count=int(getattr(meta, "redacted_count", 0) or 0),
                redacted_fields=list(getattr(meta, "redacted_fields", []) or []),
                data_window=_llm_data_window.get(),
                prompt_chars=len(msg_text),
                prompt_sha256=sha256_of(msg_text),
                status_code=response.status_code,
            ))
        except Exception as e:
            logger.debug("egress audit record failed (non-fatal): %s", e)
    except Exception as e:
        logger.warning(f"metrics_response_hook failed (non-fatal): {e}")


def record_stream_usage(
    provider: str,
    model: str,
    input_tokens: int,
    output_tokens: int,
    total_tokens: int,
    status_code: int = 200,
    latency_ms: Optional[int] = None,
) -> None:
    """记录一次**流式** LLM 调用的 usage。

    metrics_response_hook 抓不到流式 usage (在响应头触发, usage 在流末尾的 trailer 才到),
    所以流式调用一直被记成 0-token (ai_query_chat 3972 次记成 0 的根因)。
    call_chain_stream 在解析到最后的 usage 事件时调本函数, 让流式 token 被真实记账。
    复用 _llm_caller / _llm_factory ContextVar (与非流式同源) + 同一 _usage_queue/flush。
    """
    if not _enabled:
        return
    record = {
        "provider": provider,
        "model": model,
        "caller": _llm_caller.get(),
        "input_tokens": int(input_tokens or 0),
        "output_tokens": int(output_tokens or 0),
        "total_tokens": int(total_tokens or 0),
        "latency_ms": latency_ms,
        "status_code": status_code,
        "factory_id": _llm_factory.get(),
        "error_snippet": None,
    }
    try:
        _usage_queue.put_nowait(record)
    except asyncio.QueueFull:
        logger.warning("LLM usage queue full (stream), dropping record")


async def _flush_loop() -> None:
    """Background task: batches up to 100 records every 5s, writes to DB."""
    from smartbi.config import get_pg_pool

    while True:
        try:
            first = await _usage_queue.get()
            batch = [first]
            deadline = time.time() + 5.0
            while len(batch) < 100 and time.time() < deadline:
                try:
                    nxt = await asyncio.wait_for(_usage_queue.get(), timeout=deadline - time.time())
                    batch.append(nxt)
                except asyncio.TimeoutError:
                    break

            pool = await get_pg_pool()
            if pool is None:
                logger.warning("No pg_pool available, dropping %d LLM usage records", len(batch))
                continue

            async with pool.acquire() as conn:
                await conn.executemany(
                    """INSERT INTO smart_bi_llm_usage
                       (provider, model, caller, input_tokens, output_tokens, total_tokens,
                        latency_ms, status_code, factory_id, error_snippet)
                       VALUES ($1,$2,$3,$4,$5,$6,$7,$8,$9,$10)""",
                    [
                        (r["provider"], r["model"], r["caller"],
                         r["input_tokens"], r["output_tokens"], r["total_tokens"],
                         r["latency_ms"], r["status_code"], r["factory_id"], r["error_snippet"])
                        for r in batch
                    ],
                )
        except asyncio.CancelledError:
            raise
        except Exception as e:
            logger.warning(f"LLM usage flush failed: {e}")
            await asyncio.sleep(1.0)


def enable_llm_metrics() -> None:
    """Call during app startup (lifespan) AFTER pg pool is ready."""
    global _enabled, _flush_task
    if _enabled:
        return
    _enabled = True
    _flush_task = asyncio.create_task(_flush_loop(), name="llm_metrics_flusher")
    logger.info("LLM metrics enabled (httpx event_hook + async DB flush)")


async def disable_llm_metrics() -> None:
    global _enabled, _flush_task
    _enabled = False
    if _flush_task is not None:
        _flush_task.cancel()
        try:
            await _flush_task
        except (asyncio.CancelledError, Exception):
            pass
        _flush_task = None
