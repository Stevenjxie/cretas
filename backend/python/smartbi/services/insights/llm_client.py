from __future__ import annotations
"""
LLM call infrastructure: non-streaming / streaming / streaming-text,
retry logic, timeout handling, and model selection.

Apr 25 2026 (E2a): All 4 entry points (call_llm, call_llm_stream,
call_llm_stream_text, generate_text_analysis) route through
common.llm_router which provides aliyun_b → aliyun_a → zhipu →
deepseek provider fallback on 403 FreeTierOnly / 429. Previously
these called raw httpx → DashScope only, breaking entirely on
DashScope outage.

Slot routing (per A-inventory.md):
- call_llm / call_llm_stream / generate_text_analysis → SLOT.INSIGHTS
  (qwen3.5-flash on aliyun_b/_a, glm-4.5-air on zhipu, deepseek-chat fallback)
- call_llm_stream_text → SLOT.CHAT (used by AIQuery — routed via llm_router SLOT.CHAT)
"""
import asyncio
import logging
from typing import Optional, AsyncGenerator

import httpx

from config import get_settings  # noqa: F401  -- kept for _get_active_model legacy callers
from common.llm_router import call_chain, call_chain_stream, SLOT
from common.llm_metrics import llm_caller_context
from common.utils.llm_limiter import llm_rate_limit, get_semaphore
from .prompt_builder import get_scenario_system_role

logger = logging.getLogger(__name__)


# ---------------------------------------------------------------------------
# Timeout / retry constants
# ---------------------------------------------------------------------------

LLM_TIMEOUT_BASE = 60.0       # Base timeout in seconds
LLM_TIMEOUT_INCREMENT = 15.0  # Added per retry attempt
LLM_TIMEOUT_MAX = 120.0       # Hard cap (non-streaming)
# v6 fix (Apr 26 2026): tightened from 180s → 30s based on v6 verification.
# 180s was overkill — analytical answers should complete in ≤25s with the new
# 3-paragraph 300-char prompt format. Tighter timeout makes hung providers
# fail faster + fall through to next provider in chain. Combined with the
# 25s soft-cutoff in chat.py, hard 30s = 5s grace for last-chunk arrival.
LLM_TIMEOUT_STREAM = 30.0     # Streaming timeout (per-chunk read timeout in httpx)
LLM_MAX_RETRIES = 2


# ---------------------------------------------------------------------------
# Shared HTTP client (legacy direct path — still used by upstream warmup)
# ---------------------------------------------------------------------------

def _get_client() -> httpx.AsyncClient:
    from common.llm_client import get_llm_http_client
    return get_llm_http_client()


def _get_active_model(model_override: Optional[str] = None) -> str:
    """Return *model_override* if set, otherwise dedicated insight model from settings."""
    return model_override or get_settings().llm_insight_model


def _set_llm_caller(name: str) -> None:
    """Set the metrics caller contextvar without obtaining a Token for reset.

    Used by streaming codepaths where the async iteration crosses task
    boundaries and a `with llm_caller_context(...)` block would error on
    __exit__ ("Token created in different Context"). The contextvar is
    request-scoped via FastAPI's task isolation so leaking the value past
    function return is harmless.
    """
    from common.llm_metrics import _llm_caller
    _llm_caller.set(name)


# ---------------------------------------------------------------------------
# Non-streaming LLM call with retry
# ---------------------------------------------------------------------------

async def call_llm(
    prompt: str,
    system_role: Optional[str] = None,
    *,
    enable_thinking: bool = False,
    max_tokens: int = 2500,
    model_override: Optional[str] = None,
) -> str:
    """Call LLM API via multi-provider router (INSIGHTS slot).

    Chain: aliyun_b/qwen3.5-flash → aliyun_a/qwen3.5-flash → zhipu/glm-4.5-air → deepseek-chat.
    403 FreeTierOnly / 429 triggers automatic fallback to next provider.

    Args:
        prompt: User message content.
        system_role: System message; defaults to general scenario role.
        enable_thinking: Whether to enable thinking mode (default False for speed).
        max_tokens: Maximum tokens for the response.
        model_override: Ignored (per-provider model is chosen by SLOT mapping).

    Returns:
        The assistant's response text, or ``""`` on failure.
    """
    if not system_role:
        system_role = get_scenario_system_role('general')

    system_content = system_role + " 严格以JSON格式回复，不要附加任何Markdown标记或解释文字。"
    # NOTE: payload's `model` field is OVERWRITTEN by call_chain per provider.
    # Cache_control on system message is preserved for DashScope context cache;
    # other providers (zhipu/deepseek) will silently ignore the field.
    payload = {
        "messages": [
            {
                "role": "system",
                "content": [
                    {
                        "type": "text",
                        "text": system_content,
                        "cache_control": {"type": "ephemeral"},
                    }
                ],
            },
            {"role": "user", "content": prompt},
        ],
        "temperature": 0.4,
        "max_tokens": max_tokens,
        "enable_thinking": enable_thinking,
    }

    for attempt in range(LLM_MAX_RETRIES):
        try:
            timeout_secs = min(
                LLM_TIMEOUT_BASE + attempt * LLM_TIMEOUT_INCREMENT,
                LLM_TIMEOUT_MAX,
            )
            async with llm_rate_limit():
                with llm_caller_context("insight_generator"):
                    result = await call_chain(SLOT.INSIGHTS, payload, timeout=timeout_secs)
            # Log cache hit stats for monitoring (DashScope-specific field)
            usage = result.get("usage", {})
            prompt_details = usage.get("prompt_tokens_details", {})
            cached = prompt_details.get("cached_tokens", 0)
            total_prompt = usage.get("prompt_tokens", 0)
            if cached > 0:
                logger.info(
                    "[ContextCache] HIT cached=%d/%d tokens (%d%%)",
                    cached, total_prompt,
                    cached * 100 // max(total_prompt, 1),
                )
            return (
                result.get("choices", [{}])[0]
                .get("message", {})
                .get("content", "")
            )

        except RuntimeError as e:
            # All-providers-exhausted from call_chain
            logger.warning(
                "LLM call all-providers exhausted (attempt %d/%d): %s",
                attempt + 1, LLM_MAX_RETRIES, e,
            )
            if attempt < LLM_MAX_RETRIES - 1:
                await asyncio.sleep(2 ** attempt * 2)
            else:
                logger.error("LLM call failed after all retry attempts: %s", e)
                return ""
        except Exception as e:
            logger.warning(
                "LLM call exception %s (attempt %d/%d): %s",
                type(e).__name__, attempt + 1, LLM_MAX_RETRIES, e,
            )
            if attempt < LLM_MAX_RETRIES - 1:
                await asyncio.sleep(2 ** attempt * 2)
            else:
                logger.error("LLM call failed after all retry attempts: %s", e)
                return ""
    return ""


# ---------------------------------------------------------------------------
# Streaming LLM call (JSON output)
# ---------------------------------------------------------------------------

async def call_llm_stream(
    prompt: str,
    system_role: Optional[str] = None,
    *,
    max_tokens: int = 2500,
    model_override: Optional[str] = None,
) -> AsyncGenerator[str, None]:
    """Call LLM via multi-provider router (INSIGHTS slot) with SSE streaming.

    Chain: aliyun_b → aliyun_a → zhipu → deepseek (provider fallback before
    first delta; mid-stream errors propagate without fallback to keep partial
    output intact).

    Yields text chunks as they arrive.
    """
    if not system_role:
        system_role = get_scenario_system_role('general')
    system_content = system_role + " 严格以JSON格式回复，不要附加任何Markdown标记或解释文字。"
    payload = {
        "messages": [
            {
                "role": "system",
                "content": [
                    {
                        "type": "text",
                        "text": system_content,
                        "cache_control": {"type": "ephemeral"},
                    }
                ],
            },
            {"role": "user", "content": prompt},
        ],
        "temperature": 0.4,
        "max_tokens": max_tokens,
        "enable_thinking": False,
    }

    sem = get_semaphore()
    await sem.acquire()
    # NOTE: cannot use `with llm_caller_context(...)` around an async generator
    # because contextvars Token reset must happen in the same task that called
    # set(). The async iteration crosses task boundaries (httpx aiter_lines
    # internally), so __exit__ would error with "Token created in different
    # Context". We set the contextvar without reset — request-scoped task
    # cleanup handles isolation between concurrent users.
    _set_llm_caller("insight_generator")
    try:
        async for event in call_chain_stream(
            SLOT.INSIGHTS, payload, timeout=LLM_TIMEOUT_STREAM
        ):
            if event.get("type") == "delta":
                text = event.get("text") or ""
                if text:
                    yield text
            # Ignore "usage" events here — caller of this function
            # only expects text chunks.
    except RuntimeError as e:
        # All providers exhausted before any delta — log + return empty stream
        logger.error("LLM streaming call all providers exhausted: %s", e)
    except Exception as e:
        logger.error("LLM streaming call failed: %s", e)
    finally:
        sem.release()


# ---------------------------------------------------------------------------
# Streaming LLM call (plain-text / markdown output)
# ---------------------------------------------------------------------------

async def call_llm_stream_text(
    prompt: str,
    system_role: Optional[str] = None,
    *,
    max_tokens: int = 3000,
    temperature: float = 0.4,
    model_override: Optional[str] = None,
) -> AsyncGenerator[str, None]:
    """Call LLM via multi-provider router (CHAT slot) with SSE streaming.

    Used by AIQuery (Surface 1) for plain-text (markdown) output — no JSON constraint.
    Chain: aliyun_b/qwen3-max → aliyun_a/qwen3-max → zhipu/glm-4.5-air → deepseek-chat.

    Yields text chunks as they arrive.

    Apr 25 2026 (C-rec 8+9): action-recommendation guard appended to the
    default system_role so AIQuery answers carry concrete店名/收益区间/
    前置条件/时间窗口 instead of vague '加强营销' / '优化经营策略'.
    Callers that pass an explicit system_role already include the guard
    via chat.py's NUMERIC + LABELING + ACTION_REC concatenation.
    """
    # Local import (avoid hard cycle at module load — llm_guard has no deps).
    from smartbi.services.llm_guard import ACTION_REC_GUARD_CLAUSE
    if not system_role:
        system_role = (
            "你是一位服务于食品加工企业的资深数据分析师。请用中文回复，使用Markdown格式。"
            + ACTION_REC_GUARD_CLAUSE
        )
    # Apr 27 2026 (F3): wrap system_role in array-content with cache_control
    # so DashScope explicit cache (90% off vs implicit 80%) fires on hot
    # chat path. system_role contains stable GUARD_CLAUSE text repeated
    # every query — prime cache target. DeepSeek silently ignores the
    # cache_control field (verified API probe), so safe to send to chain.
    # Zhipu/glm-4-plus: per OpenAI spec arrays are tolerated.
    payload = {
        "messages": [
            {
                "role": "system",
                "content": [
                    {
                        "type": "text",
                        "text": system_role,
                        "cache_control": {"type": "ephemeral"},
                    }
                ],
            },
            {"role": "user", "content": prompt},
        ],
        "temperature": temperature,
        "max_tokens": max_tokens,
        "enable_thinking": False,
    }

    sem = get_semaphore()
    await sem.acquire()
    # See call_llm_stream above for why we don't use a context manager here.
    _set_llm_caller("ai_query_chat")
    try:
        async for event in call_chain_stream(
            SLOT.CHAT, payload, timeout=LLM_TIMEOUT_STREAM
        ):
            if event.get("type") == "delta":
                text = event.get("text") or ""
                if text:
                    yield text
    except RuntimeError as e:
        logger.error("LLM text streaming all providers exhausted: %s", e)
    except Exception as e:
        logger.error("LLM text streaming call failed: %s", e)
    finally:
        sem.release()


# ---------------------------------------------------------------------------
# Plain-text analysis (non-streaming, markdown output)
# ---------------------------------------------------------------------------

async def generate_text_analysis(
    text: str, *, model_override: Optional[str] = None
) -> str:
    """Analyze free-form text via multi-provider router (INSIGHTS slot).

    Returns the analysis as plain text (not JSON). Provider chain provides
    fallback on DashScope outage.
    """
    # F3.1 fix: array-content + cache_control for explicit DashScope context
    # cache (90% off vs implicit 80% off). Sister sweep with call_llm_stream_text.
    system_role = (
        "你是一位服务于食品加工企业的资深分析师。"
        "请根据提供的数据进行深入分析，给出关键发现和可执行建议。"
        "要求：引用具体数字，分析因果关系，给出量化建议。"
        "用中文回复，使用Markdown格式。"
    )
    payload = {
        "messages": [
            {
                "role": "system",
                "content": [
                    {
                        "type": "text",
                        "text": system_role,
                        "cache_control": {"type": "ephemeral"},
                    }
                ],
            },
            {"role": "user", "content": text},
        ],
        "temperature": 0.4,
        "max_tokens": 2000,
        "enable_thinking": False,
    }
    try:
        async with llm_rate_limit():
            with llm_caller_context("insight_text_analysis"):
                result = await call_chain(SLOT.INSIGHTS, payload, timeout=LLM_TIMEOUT_BASE)
        return result["choices"][0]["message"]["content"]
    except RuntimeError as e:
        logger.error("Text analysis all providers exhausted: %s", e)
        return ""
    except Exception as e:
        logger.error("Text analysis LLM call failed: %s", e)
        return ""
