"""LLMArbitrator: last-resort LLM JSON arbitration over top-K candidates."""
from __future__ import annotations

import json
from typing import TYPE_CHECKING, Any, Awaitable, Callable, Dict, List, Optional

from smartbi.canonical.entity_resolution.agents.base import BaseAgent
from smartbi.canonical.entity_resolution.orchestrator import (
    AgentResult,
    ResolutionInput,
)

if TYPE_CHECKING:
    import asyncpg


LLMCallFn = Callable[..., Awaitable[str]]

_RELEVANT_CONTEXT_KEYS = (
    "city", "district", "category", "store_name", "role",
    # P4a dish context: 品类 (dish_classifier) / 价位区间 / 出现门店数
    "price_range", "store_count",
)


class LLMArbitrator(BaseAgent):
    """Asks LLM to pick a candidate (or null) given raw_name + ctx + top-K list."""

    name = "llm_arbitrator"
    ship_threshold = 0.70

    def __init__(self, llm_fn: Optional[LLMCallFn] = None) -> None:
        self._llm_fn: Optional[LLMCallFn] = llm_fn

    @staticmethod
    def _format_candidates(candidates: List[Dict[str, Any]]) -> str:
        lines: List[str] = []
        for idx, c in enumerate(candidates, start=1):
            eid = c.get("entity_id")
            name = c.get("name", "")
            sim = c.get("sim", 0.0)
            try:
                sim_str = f"{float(sim):.3f}"
            except (TypeError, ValueError):
                sim_str = str(sim)
            lines.append(f"{idx}. id={eid} name='{name}' sim={sim_str}")
        return "\n".join(lines)

    @staticmethod
    def _filter_context(ctx: Dict[str, Any]) -> Dict[str, Any]:
        return {
            k: v
            for k, v in ctx.items()
            if k in _RELEVANT_CONTEXT_KEYS and v not in (None, "")
        }

    @staticmethod
    def _build_prompt(
        raw_name: str,
        entity_type_value: str,
        candidates: List[Dict[str, Any]],
        ctx: Dict[str, Any],
    ) -> str:
        cand_block = LLMArbitrator._format_candidates(candidates)
        ctx_str = json.dumps(
            LLMArbitrator._filter_context(ctx), ensure_ascii=False
        )
        # DISH 仲裁额外铁律 (per spec §R1 P0 / fool-proof / #364): 错合并污染所有按
        # canonical 的销量/营收/评价且难发现 → prompt 明示 "宁可返 null, 字面相似 ≠ 同菜"。
        if entity_type_value == "dish":
            return (
                f"你是餐饮菜品归一专家. 判断 \"{raw_name}\" 是否与下列任一候选是"
                f"**同一道菜** (跨门店同菜不同名, 如 '招牌青花椒鱼' 与 '青花椒味鱼'):\n\n"
                f"候选 (按相似度排序):\n{cand_block}\n\n"
                f"上下文 (品类/价位/门店数): {ctx_str}\n\n"
                "⛔ 铁律:\n"
                "1. 字面相似 ≠ 同菜: '红烧牛肉' 与 '红烧牛腩' 是两道不同的菜, 不可合并; "
                "'青花椒鱼' 与 '青花椒虾' 主料不同, 不可合并; "
                "'青花椒鱼' 与 '青花椒烤鱼煲' 做法不同, 不可合并.\n"
                "2. 只在你**确信是同一道菜**时返回候选 id; 任何疑问一律返 null "
                "(错合并比漏合并代价大得多, 漏合并人工可补).\n\n"
                "返回 JSON: {\"matched_id\": <候选id or null>, "
                "\"confidence\": <0-1>, \"reasoning\": \"<简要原因>\"}"
            )
        return (
            f"你是餐饮数据实体识别专家. 判断 \"{raw_name}\" 是否与下列任一候选指同一 "
            f"{entity_type_value}:\n\n"
            f"候选 (按相似度排序):\n{cand_block}\n\n"
            f"上下文: {ctx_str}\n\n"
            "返回 JSON: {\"matched_id\": <候选id or null>, "
            "\"confidence\": <0-1>, \"reasoning\": \"<简要原因>\"}"
        )

    @staticmethod
    def _extract_json(raw: str) -> Optional[Dict[str, Any]]:
        if not raw:
            return None
        text = raw.strip()
        try:
            return json.loads(text)
        except (json.JSONDecodeError, ValueError):
            pass
        # Tolerate fenced code blocks or leading prose.
        start = text.find("{")
        end = text.rfind("}")
        if start == -1 or end == -1 or end <= start:
            return None
        try:
            return json.loads(text[start: end + 1])
        except (json.JSONDecodeError, ValueError):
            return None

    async def resolve(
        self,
        input: ResolutionInput,
        pool: "asyncpg.Pool",
    ) -> AgentResult:
        ctx = input.context or {}
        candidates = list(ctx.get("top_k_candidates", []) or [])
        if not candidates:
            return AgentResult(
                matched_entity_id=None,
                confidence=0.0,
                reasoning="no candidates to arbitrate",
            )

        if self._llm_fn is None:
            from smartbi.services.insights.llm_client import call_llm

            self._llm_fn = call_llm

        prompt = self._build_prompt(
            input.raw_name, input.entity_type.value, candidates, ctx
        )

        try:
            raw_response = await self._llm_fn(
                prompt,
                system_role="你是餐饮数据实体识别专家.",
                max_tokens=300,
            )
        except Exception as exc:  # noqa: BLE001
            return AgentResult(
                matched_entity_id=None,
                confidence=0.0,
                reasoning=f"LLM error: {exc}",
            )

        if not raw_response:
            return AgentResult(
                matched_entity_id=None,
                confidence=0.0,
                reasoning="LLM error: empty response",
            )

        parsed = self._extract_json(raw_response)
        if parsed is None:
            return AgentResult(
                matched_entity_id=None,
                confidence=0.0,
                reasoning="LLM error: invalid JSON",
            )

        try:
            matched_raw = parsed.get("matched_id", None)
            confidence_raw = parsed.get("confidence", 0.0)
            reason_raw = parsed.get("reasoning", "")
        except AttributeError:
            return AgentResult(
                matched_entity_id=None,
                confidence=0.0,
                reasoning="LLM error: response not a JSON object",
            )

        try:
            confidence = float(confidence_raw)
        except (TypeError, ValueError):
            confidence = 0.0
        confidence = max(0.0, min(1.0, confidence))

        valid_ids = {c.get("entity_id") for c in candidates}
        matched_id: Optional[int]
        if matched_raw is None:
            matched_id = None
        else:
            try:
                coerced = int(matched_raw)
            except (TypeError, ValueError):
                coerced = None
            if coerced is None or coerced not in valid_ids:
                matched_id = None
                reason_raw = (
                    f"LLM hallucinated id={matched_raw} not in candidates; "
                    f"original={reason_raw}"
                )
                confidence = 0.0
            else:
                matched_id = coerced

        return AgentResult(
            matched_entity_id=matched_id,
            confidence=confidence,
            reasoning=f"LLM: {reason_raw}",
        )
