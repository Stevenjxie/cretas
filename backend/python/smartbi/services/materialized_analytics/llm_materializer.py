"""LLM insight materialization — generate rich natural-language insights at
upload time so cache hits serve LLM-quality content with 0 query-token cost.

Background
----------
Each template's deterministic ``insight_text`` is built by f-string templates
(see ``action_rec_formatter.py``): the benefit ranges are hardcoded constants,
the phrasing is formulaic, and there is no causal attribution. Content-quality
audit found this reads "boilerplate" next to a freshly LLM-generated answer.

This module runs ONCE at materialization time (inside the fire-and-forget
``hooks._trigger_materialization`` background task, NOT on the user-facing
query path). It aggregates all applicable templates' KPIs / key data into a
single prompt, makes ONE LLM call, and fans the rich per-template insights
back into each ``TemplateResult.llm_insight``. ``persistence`` then stores
``llm_insight`` (falling back to the rule ``insight_text`` when absent), so the
hit path (``format_cached_as_sse`` reading ``insight_text``) transparently
serves the LLM-quality text.

Cost model: the LLM token is spent once at upload, amortized over every
subsequent cache hit (each hit = 0 query token, ~230ms).

⛔ Degradation rule (per .claude/rules — 禁止降级处理 + LLM-output-gate):
On LLM failure / timeout / empty / unparseable / partial output, we MUST keep
the existing rule ``insight_text`` untouched. We never store empty or fake
content. The whole call is wrapped so a failure only logs a warning and leaves
``llm_insight=None`` on every result.
"""
from __future__ import annotations

import hashlib
import json
import logging
from typing import Any, Dict, List, Optional

from .templates.base import TemplateResult

logger = logging.getLogger(__name__)

# Single aggregated call cap. The response is N short insight strings + one
# executive summary, so a few thousand tokens is plenty. Keeping this bounded
# also caps the DashScope rate-limit / cost exposure per upload.
_MAX_TOKENS = 3000

# Hard ceiling on how many templates we describe in the prompt. Materialization
# rarely yields more than ~15 applicable templates, but cap defensively so a
# pathological upload can't blow up the prompt size.
_MAX_TEMPLATES_IN_PROMPT = 20

# Per-insight character budget hint passed to the LLM. Keeps each insight
# compact (these render inline in the chat answer) while still allowing
# attribution ("为什么 + 怎么办").
_INSIGHT_CHAR_HINT = 180

_SYSTEM_ROLE = (
    "你是资深的餐饮 / 工厂经营分析顾问, 但要像跟门店老板面对面口头汇报一样说人话。"
    "基于下面每个分析模块已经算好的 KPI 和关键数据, 为每个模块写一句自然、有归因、"
    "可落地的经营洞察 (说清「现状 → 为什么 → 怎么办」), 再写一段跨模块的总览。"
    "必须用大白话、口语化, 普通门店店长一看就懂; 严禁咨询黑话和抽象术语 ("
    "例如「外溢效应」「梯队收敛」「品质趋同」「赋能」「抓手」「协同」「沉淀」「闭环」"
    "等一律不许用), 也不要堆砌形容词。建议要具体到能直接照着做。"
    "不要编造数据里没有的数字, 只解读给定的数字。"
)


def _summarize_result_for_prompt(result: TemplateResult) -> Dict[str, Any]:
    """Compact a single TemplateResult into prompt-friendly material.

    We pass the KPIs (already numeric, small) + a trimmed slice of the primary
    data payload + the rule insight_text as a baseline hint. We deliberately do
    NOT dump the full data (could be thousands of rows) — KPIs + top rows carry
    the signal the LLM needs to attribute.
    """
    data = result.data or {}
    # Pull the most signal-dense slices commonly present across templates.
    data_excerpt: Dict[str, Any] = {}
    for key in (
        "primary_dim", "measure", "top_rows", "ranking", "rows",
        "summary", "totals", "trend", "outliers", "top_total",
    ):
        if key in data and data[key] is not None:
            val = data[key]
            # Trim long lists to first 8 entries to bound prompt size.
            if isinstance(val, list):
                val = val[:8]
            data_excerpt[key] = val

    return {
        "code": result.code,
        "title": result.title,
        "kpis": result.kpis or {},
        "data": data_excerpt,
        # The rule insight is a baseline the LLM can improve on (not copy).
        "baseline": result.insight_text or "",
    }


def _build_prompt(
    payload: List[Dict[str, Any]],
    domain: str,
) -> str:
    """Build the single aggregated user prompt."""
    blocks = json.dumps(payload, ensure_ascii=False, default=str, indent=None)
    return (
        f"业态领域: {domain}\n"
        f"下面是 {len(payload)} 个分析模块的预计算结果 (JSON 数组, 每个含 code / title / "
        f"kpis / data / baseline):\n\n{blocks}\n\n"
        f"请只返回如下 JSON 对象 (不要任何额外文字):\n"
        f'{{\n'
        f'  "executive_summary": "<一段跨模块的经营总览, 120-200 字>",\n'
        f'  "insights": {{\n'
        f'    "<模块 code>": "<针对该模块的洞察, ≤{_INSIGHT_CHAR_HINT} 字, '
        f'必须说清现状/原因/可落地建议, 只用给定数字>",\n'
        f'    ...\n'
        f'  }}\n'
        f'}}\n'
        f"要求: insights 的 key 必须严格用上面给定的 code; 每条都要有具体归因和下一步动作; "
        f"必须用大白话口语化、店长一看就懂, 严禁咨询黑话/抽象术语 (如「外溢效应」"
        f"「梯队收敛」「趋同」「赋能」「抓手」「协同」「沉淀」); "
        f"禁止编造数据中不存在的数字; 禁止泛泛而谈 (如「建议优化经营策略」)。"
    )


async def generate_llm_insights(
    results: List[TemplateResult],
    domain: str,
    factory_id: Optional[str] = None,
) -> Optional[str]:
    """Generate rich LLM insights for all applicable templates (single call).

    Mutates ``results`` in place: sets ``result.llm_insight`` for each template
    the LLM produced an insight for. Returns the executive summary string (or
    ``None`` if generation failed / produced nothing).

    ``factory_id`` (optional) only tags the distillation training sample
    captured on success — it does not affect insight generation.

    NEVER raises — all failures are swallowed and leave ``llm_insight=None`` so
    the caller's persistence falls back to the deterministic ``insight_text``.
    """
    applicable = [r for r in results if r.applies and not r.error]
    if not applicable:
        logger.info("[llm-mat] no applicable templates — skipping LLM insight gen")
        return None

    # Gate on API key — if unconfigured, leave rule insights as-is (no fake).
    try:
        from config import get_settings
        if not get_settings().llm_api_key:
            logger.info("[llm-mat] no llm_api_key configured — keeping rule insights")
            return None
    except Exception as e:
        logger.warning(f"[llm-mat] settings unavailable, skipping LLM gen: {e}")
        return None

    prompt_payload = [
        _summarize_result_for_prompt(r)
        for r in applicable[:_MAX_TEMPLATES_IN_PROMPT]
    ]
    prompt = _build_prompt(prompt_payload, domain)

    # 内容未变跳过 LLM (榨干持久缓存): prompt 与上次 byte-identical (模板 KPI 没变) → 复用
    # 上次 LLM 输出, 跳过调用 (0 token)。可证明正确: 同一 prompt → 同一解析结果。复用已持久的
    # smart_bi_distillation_samples (它按 input_hash 存了 prompt→teacher_output)。
    input_hash = hashlib.sha256(prompt.encode("utf-8")).hexdigest()
    raw = await _get_cached_teacher_output(input_hash)
    from_cache = bool(raw)
    if from_cache:
        logger.info("[llm-mat] content-hash cache HIT — 复用上次洞察, 跳过 LLM (0 token)")
    else:
        try:
            from smartbi.services.insights import llm_client as llm
            raw = await llm.call_llm(
                prompt,
                system_role=_SYSTEM_ROLE,
                enable_thinking=False,
                max_tokens=_MAX_TOKENS,
            )
        except Exception as e:
            logger.warning(
                f"[llm-mat] LLM call raised ({type(e).__name__}: {e}) — "
                f"keeping rule insights for {len(applicable)} templates"
            )
            return None

    if not raw or not raw.strip():
        logger.warning(
            f"[llm-mat] LLM returned empty — keeping rule insights "
            f"for {len(applicable)} templates"
        )
        return None

    try:
        from common.utils.json_parser import robust_json_parse
        parsed = robust_json_parse(raw, fallback=None)
    except Exception as e:
        logger.warning(f"[llm-mat] JSON parse raised ({e}) — keeping rule insights")
        return None

    if not isinstance(parsed, dict):
        logger.warning("[llm-mat] LLM output not a JSON object — keeping rule insights")
        return None

    insights_map = parsed.get("insights")
    if not isinstance(insights_map, dict):
        insights_map = {}

    applied = 0
    for r in applicable:
        rich = insights_map.get(r.code)
        # Only accept a non-empty string that actually differs in substance.
        # An empty / non-string value MUST NOT clobber the rule insight.
        if isinstance(rich, str) and rich.strip():
            r.llm_insight = rich.strip()
            applied += 1

    exec_summary = parsed.get("executive_summary")
    if not (isinstance(exec_summary, str) and exec_summary.strip()):
        exec_summary = None
    else:
        exec_summary = exec_summary.strip()

    logger.info(
        f"[llm-mat] LLM insights applied to {applied}/{len(applicable)} templates"
        f"{' + executive summary' if exec_summary else ''}"
    )

    # Distillation data pipeline: capture the (structured input → teacher output)
    # pair for future vertical-model distillation. Only when the teacher produced
    # usable output (applied > 0). Fire-and-forget, fully swallowed — NEVER let
    # training-data capture affect insight generation or materialization.
    if applied > 0 and not from_cache:
        try:
            await _persist_distillation_sample(
                prompt=prompt,
                system_prompt=_SYSTEM_ROLE,
                teacher_output=raw,
                business_type=domain,
                factory_id=factory_id,
                template_codes=[r.code for r in applicable],
                applied=applied,
                total=len(applicable),
            )
        except Exception as e:  # belt-and-suspenders; helper already swallows
            logger.debug(f"[llm-mat] distillation capture skipped: {e}")

    # If the LLM produced nothing usable, signal nothing applied (rule survives).
    return exec_summary


async def _persist_distillation_sample(
    *,
    prompt: str,
    system_prompt: str,
    teacher_output: str,
    business_type: str,
    factory_id: Optional[str],
    template_codes: List[str],
    applied: int,
    total: int,
) -> None:
    """Write one materialization distillation sample. Fire-and-forget.

    Thin wrapper around the shared ``persist_distillation_sample`` helper
    (``smartbi.services.distillation_capture``) — kept for the materializer's
    call site + metadata shaping. Behavior is IDENTICAL to before the
    extraction: same ``input_hash = sha256(prompt)``, same INSERT columns, same
    ON CONFLICT (input_hash) refresh, same env kill-switch
    ``SMARTBI_DISTILL_CAPTURE``. Persists the (structured-input → strong-teacher
    -output) pair into ``smart_bi_distillation_samples`` so it can later be
    exported (bucketed by business_type) for vertical-model distillation.
    """
    # INSIGHTS slot primary model is qwen3-max on all 3 aliyun accounts
    # (post PR #331/#333); a small fraction may fall back to zhipu/glm-4.5-air
    # — recorded in metadata, verifiable against llm_router logs by timestamp.
    teacher_model = "qwen3-max"
    metadata = {
        "slot": "insights",
        "applied": applied,
        "total_applicable": total,
        "teacher_model_note": "INSIGHTS-slot primary qwen3-max; rare zhipu fallback possible",
    }
    try:
        from smartbi.config import get_pg_pool
        from smartbi.services.distillation_capture import persist_distillation_sample
        pool = await get_pg_pool()
        await persist_distillation_sample(
            pool,
            source="materialization",
            task_type="insights",
            input_text=prompt,
            teacher_output=teacher_output,
            business_type=business_type,
            factory_id=factory_id,
            system_prompt=system_prompt,
            teacher_model=teacher_model,
            template_codes=template_codes,
            quality=4,  # structural-verified + normal served: high quality
            metadata=metadata,
        )
    except Exception as e:  # belt-and-suspenders; helper already swallows
        logger.warning(f"[distill] sample capture failed (non-blocking): {e}")


async def _get_cached_teacher_output(input_hash: str) -> Optional[str]:
    """复用对同一 prompt (byte-identical) 的上次 LLM 输出 —— 物化时模板 KPI 没变则 prompt
    完全一致, 直接复用跳过 LLM (0 token)。可证明正确: 同输入 → 解析结果完全相同。

    数据源: smart_bi_distillation_samples (已按 input_hash 持久存 prompt→teacher_output,
    且只在 applied>0 即上次产出可用时才写) —— 天然是一个"内容哈希→可用洞察"持久缓存。
    只读, 永不抛错; 表空/缺失 → 返回 None → 正常调 LLM (退化为原行为)。
    """
    try:
        from smartbi.config import get_pg_pool
        pool = await get_pg_pool()
        if pool is None:
            return None
        async with pool.acquire() as conn:
            row = await conn.fetchrow(
                "SELECT teacher_output FROM smart_bi_distillation_samples WHERE input_hash = $1",
                input_hash,
            )
        if row and row["teacher_output"] and str(row["teacher_output"]).strip():
            return row["teacher_output"]
    except Exception as e:
        logger.debug(f"[llm-mat] content-hash cache lookup skipped (non-blocking): {e}")
    return None
