"""LLM tier selector — pick cheap vs expensive LLM for stage 8 (β C2).

NEW feature, not a Java port. Java's ComplexityRouter routes ProcessingMode
for IntentExecutionOrchestrator (Bucket B); this module is purely Python-side
LLM cost optimization for stage 8.

Algorithm: call small LLM (via SLOT.MAPPER) to classify query complexity.
Simple → CHEAP tier. Complex → EXPENSIVE tier. Failures default to EXPENSIVE
(safety: don't undercut quality on hard queries).
"""
from __future__ import annotations

import asyncio
import json
import logging
from enum import Enum

logger = logging.getLogger(__name__)


class LlmTier(str, Enum):
    CHEAP = "cheap"
    EXPENSIVE = "expensive"


CLASSIFIER_PROMPT = """判断以下查询的复杂度. 简单查询: 单一意图, 直接查询. 复杂查询: 多意图, 跨表聚合, 时间对比, 推理.

返回 JSON:
{"complexity": "simple" | "complex", "reasoning": "<简短>"}

不要解释, 只返 JSON."""


async def _call_classifier(query: str, timeout_s: int = 5) -> str:
    """Call small LLM via existing common/llm_router. SLOT.MAPPER routes to
    cheap fast model (qwen-turbo or equivalent)."""
    from common import llm_router  # type: ignore
    from common.llm_metrics import llm_caller_context  # type: ignore

    messages = [
        {"role": "system", "content": CLASSIFIER_PROMPT},
        {"role": "user", "content": query},
    ]
    payload = {"messages": messages, "temperature": 0.0, "max_tokens": 50}

    with llm_caller_context("llm_tier_selector"):
        response = await asyncio.wait_for(
            llm_router.call_chain(llm_router.SLOT.MAPPER, payload),
            timeout=timeout_s,
        )
    return response["choices"][0]["message"]["content"]


class LlmTierSelector:
    """Stage 8 helper: classify query and return tier hint."""

    def __init__(self, timeout_s: int = 5):
        self.timeout_s = timeout_s

    async def select(self, query: str) -> LlmTier:
        """Returns CHEAP or EXPENSIVE. On any failure, returns EXPENSIVE."""
        try:
            raw = await _call_classifier(query, self.timeout_s)
        except (TimeoutError, asyncio.TimeoutError):
            logger.warning("LlmTierSelector: classifier timed out, defaulting to EXPENSIVE")
            return LlmTier.EXPENSIVE
        except Exception:
            logger.exception("LlmTierSelector: classifier failed, defaulting to EXPENSIVE")
            return LlmTier.EXPENSIVE

        try:
            parsed = json.loads(raw.strip())
            complexity = parsed.get("complexity", "complex")
            return LlmTier.CHEAP if complexity == "simple" else LlmTier.EXPENSIVE
        except (json.JSONDecodeError, AttributeError):
            logger.warning("LlmTierSelector: malformed JSON, defaulting to EXPENSIVE: %r",
                           raw[:100] if raw else None)
            return LlmTier.EXPENSIVE
