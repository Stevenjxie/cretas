"""Stage 8: LLM fallback — ask LLM to pick best intent from visible registry.

Used when stages 5-7 return low confidence (no FUSION strong-signal). Builds a
prompt with intent_code/name/description/example_queries from the visible
intent registry, asks LLM to return strict JSON `{intentCode, confidence,
reasoning}`, then converts to a single CandidateIntentDto with
matchMethod=LLM. On any failure (timeout / malformed JSON / chain exhausted /
LLM declines with intentCode=null) returns an empty list — caller falls
through to "no match" path.

Underlying API:
    backend/python/common/llm_router.py exposes `call_chain(SLOT, payload) ->
    Dict` (OpenAI-compatible chat completions response). We use SLOT.MAPPER
    since intent classification is structurally a mapping/labeling task and
    the MAPPER slot routes to fast cheap models (qwen-turbo / qwen3.5-122b /
    glm-4.5-air / deepseek-v4-flash). Response shape: data["choices"][0]
    ["message"]["content"] is the raw model output we expect to be JSON.

Tests mock `_call_llm` to avoid live LLM calls. Real path inside `_call_llm`
calls `call_chain(SLOT.MAPPER, payload)` and pulls `.choices[0].message
.content` out.
"""
from __future__ import annotations

import json
import logging
from typing import Any, Dict, List, Optional

from ai.config import default_config
from ai.dto import CandidateIntentDto, MatchMethod

logger = logging.getLogger(__name__)


_SYSTEM_PROMPT = (
    "你是一个意图识别助手。根据用户输入和给定的意图列表,选出最匹配的一个意图。\n"
    "严格按 JSON 格式返回:{\"intentCode\": \"<意图编码或 null>\", \"confidence\": "
    "<0.0-1.0>, \"reasoning\": \"<简短理由>\"}。\n"
    "如果没有任何意图匹配,intentCode 必须返回 null,confidence 返回 0.0。\n"
    "不要返回 JSON 之外的任何文字、不要包裹 markdown 代码块。"
)


def build_prompt(
    query: str,
    visible_intents: List[Dict[str, Any]],
    history: Optional[List[Dict[str, Any]]] = None,
) -> str:
    """Build user-side prompt content listing visible intents + example queries.

    Returned string is fed as the `user` message content to the LLM. The
    system instruction (return JSON only) is attached separately by `_call_llm`.

    Args:
        query: User's raw input text.
        visible_intents: Filtered intent registry rows already scoped to the
            caller's factory + business_type. Each dict must contain
            intent_code / intent_name; description and example_queries
            (JSON array string) are optional but strongly recommended.
        history: Recent conversation turns (currently unused — placeholder
            for future multi-turn matching). Each entry: {"role": "user"|
            "assistant", "content": "..."}.
    """
    lines: List[str] = []
    lines.append("可选意图列表:")
    for idx, it in enumerate(visible_intents, start=1):
        code = it.get("intent_code", "?")
        name = it.get("intent_name", "")
        desc = it.get("description") or ""
        examples_raw = it.get("example_queries") or "[]"
        try:
            examples = json.loads(examples_raw) if isinstance(examples_raw, str) else examples_raw
            if not isinstance(examples, list):
                examples = []
        except Exception:
            examples = []
        example_str = " / ".join(str(e) for e in examples[:5]) if examples else ""
        line = f"{idx}. {code} ({name}): {desc}"
        if example_str:
            line += f" [例: {example_str}]"
        lines.append(line)

    if history:
        lines.append("")
        lines.append("最近对话:")
        for turn in history[-5:]:
            role = turn.get("role", "?")
            content = turn.get("content", "")
            lines.append(f"  {role}: {content}")

    lines.append("")
    lines.append(f"用户输入: {query}")
    lines.append("请返回 JSON。")
    return "\n".join(lines)


async def _call_llm(prompt: str, *, timeout_s: int, tier=None) -> str:
    """Call the LLM via shared multi-provider router.

    Adapts `common.llm_router.call_chain` (OpenAI-compatible chat completions)
    to a flat string return — extract `choices[0].message.content`. Mocked in
    tests; live path requires LLM_*_API_KEY env vars set.

    β C2: `tier` hint guides slot selection. CHEAP → SLOT.MAPPER (cheap fast
    model), EXPENSIVE → SLOT.CHAT (full reasoning model). When tier is None
    (α path or selector failure), defaults to SLOT.MAPPER for backward compat.

    Raises whatever the router raises (RuntimeError on chain exhausted,
    TimeoutError on overall timeout) so caller's try/except can return [].
    """
    from common.llm_router import SLOT, call_chain  # type: ignore
    from common.llm_metrics import llm_caller_context  # type: ignore
    from ai.router.llm_tier_selector import LlmTier

    # Tier-driven slot selection. Default (no tier) preserves α behavior:
    # SLOT.MAPPER (cheap mapping-style model). EXPENSIVE upgrades to SLOT.CHAT.
    slot = SLOT.MAPPER
    if tier == LlmTier.EXPENSIVE:
        slot = SLOT.CHAT

    payload: Dict[str, Any] = {
        "messages": [
            {"role": "system", "content": _SYSTEM_PROMPT},
            {"role": "user", "content": prompt},
        ],
        "temperature": 0.0,
        "max_tokens": 200,
        "response_format": {"type": "json_object"},
    }
    with llm_caller_context("ai_matcher"):
        resp = await call_chain(slot, payload, timeout=float(timeout_s))
    try:
        return resp["choices"][0]["message"]["content"] or ""
    except (KeyError, IndexError, TypeError) as e:
        logger.warning("LLM stage 8: unexpected response shape: %s", e)
        return ""


class LlmMatcher:
    """Stage 8 LLM fallback wrap.

    Stateless — each match() call independently builds prompt + invokes LLM.
    Failure modes (timeout, malformed JSON, no-match) all collapse to []
    so orchestrator can cleanly compute "no match" path.
    """

    def __init__(self, timeout_s: Optional[int] = None):
        self.timeout_s = timeout_s if timeout_s is not None else default_config.llm_timeout_s

    async def match(
        self,
        query: str,
        visible_intents: List[Dict[str, Any]],
        history: Optional[List[Dict[str, Any]]] = None,
        tier=None,  # β C2: LlmTier hint (None = default SLOT.MAPPER)
        rag_cases=None,  # β C5: list of RAGCase from retrieval (None/[] = no enrichment)
    ) -> List[CandidateIntentDto]:
        """Pick best intent via LLM. Returns [] on any failure or no-match.

        β C2: `tier` hint chooses cheap (CHEAP → SLOT.MAPPER) vs expensive
        (EXPENSIVE → SLOT.CHAT). None preserves α default (SLOT.MAPPER).
        β C5: `rag_cases` (when present and non-empty) prepended to prompt as
        "参考历史" block — top-3 historical cases by similarity. None/[]
        preserves α default (no RAG enrichment).
        """
        if not visible_intents:
            return []

        # β C5: prepend RAG context if cases were retrieved + judged HIGH/MEDIUM
        rag_block = ""
        if rag_cases:
            rag_lines = [
                f"- 历史案例: {c.query} → {c.intent_code} (相似度 {c.similarity:.2f})"
                for c in rag_cases[:3]
            ]
            rag_block = "参考历史:\n" + "\n".join(rag_lines) + "\n\n"

        prompt = rag_block + build_prompt(query, visible_intents, history or [])

        if tier is not None:
            logger.debug("Stage 8 LLM with tier hint: %s", tier)

        try:
            raw = await _call_llm(prompt, timeout_s=self.timeout_s, tier=tier)
        except TimeoutError:
            logger.warning("Stage 8 LLM: timeout after %ss", self.timeout_s)
            return []
        except Exception:
            logger.exception("Stage 8 LLM: call failed")
            return []

        if not raw:
            return []

        try:
            parsed = json.loads(raw)
        except json.JSONDecodeError:
            logger.warning("Stage 8 LLM: non-JSON response: %s", raw[:200])
            return []

        intent_code = parsed.get("intentCode")
        if not intent_code:
            return []

        # Look up intent_name from visible registry (LLM may shorten/skip it).
        intent_name = intent_code
        intent_category = None
        description = None
        for it in visible_intents:
            if it.get("intent_code") == intent_code:
                intent_name = it.get("intent_name") or intent_code
                intent_category = it.get("intent_category")
                description = it.get("description")
                break

        try:
            confidence = float(parsed.get("confidence", 0.0))
        except (TypeError, ValueError):
            confidence = 0.0

        return [
            CandidateIntentDto(
                intentCode=intent_code,
                intentName=intent_name,
                intentCategory=intent_category,
                confidence=confidence,
                matchMethod=MatchMethod.LLM,
                description=description,
            )
        ]
