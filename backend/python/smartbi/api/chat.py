from __future__ import annotations
"""
Chat API for SmartBI

Provides endpoints for AI-powered conversational analysis:
- Drill-down analysis
- Industry benchmarking
- Root cause analysis
- General queries

These endpoints are called by the Java backend's SmartBIIntentService.

Part of SmartBI Phase 6: AI Chat Deep Integration.
"""
import asyncio
import hashlib
import json as _json
import logging
from datetime import date
import time
from typing import Any, AsyncGenerator, Dict, List, Optional

from fastapi import APIRouter, HTTPException, Request
from fastapi.responses import StreamingResponse
from pydantic import BaseModel, Field
from smartbi.config import coerce_numeric_columns
from smartbi.gold.customer_text import (
    has_displayable_business_result,
    sanitize_customer_ai_text,
)


# Services
from services.cross_analyzer import CrossAnalyzer, DrillDownResult, DimensionHierarchy
from services.industry_benchmark import (
    IndustryBenchmark,
    IndustryCategory
)
from services.insight_dimensions import (
    InsightDimensionAnalyzer,
    InsightDimension,
    InsightReport
)
from services.insight_generator import InsightGenerator

# Cache
from common.insight_cache import get_insight_cache

# P2 guardrail (Apr 24 2026): numeric hallucination detection
# C-rec 7 (Apr 25 2026): numeric labeling enforcement (gross/net + basis)
# C-rec 8+9 (Apr 25 2026): concrete actionable recommendations (spec §4.3)
from smartbi.services.llm_guard import (
    ACTION_REC_GUARD_CLAUSE,
    LABELING_GUARD_CLAUSE,
    NUMERIC_GUARD_CLAUSE,
    USER_FRIENDLY_TONE_CLAUSE,
    detect_numeric_hallucination,
)

logger = logging.getLogger(__name__)
router = APIRouter(tags=["Chat"])


async def _log_template_hit_safe(pool, query, factory_id, upload_id, template_code, answer, wall_ms):
    """Safe wrapper around log_template_hit — swallows exceptions so a DB
    hiccup never breaks the SSE stream."""
    try:
        from smartbi.services.llm_fallback_logger import log_template_hit
        return await log_template_hit(
            pool, query, factory_id, upload_id, template_code, answer, wall_ms,
        )
    except Exception as e:
        logger.warning(f"[template-log] wrapper failed: {e}")
        return None


async def _try_tiered_restaurant_intent(
    query: str, pool, factory_id: str, role: Optional[str],
    *, session_key: Optional[str] = None,
) -> Optional[Dict[str, Any]]:
    """T2 (vector) / T3 (LLM) restaurant intent routing (2026-07-07 Phase 1
    design: docs/superpowers/specs/2026-07-07-restaurant-intent-tiered-routing-design.md).

    Thin wrapper (Phase 2, 2026-07-07:
    docs/superpowers/specs/2026-07-07-restaurant-intent-phase2-java-entry-design.md
    section 4) -- the implementation now lives in
    `smartbi.gold.restaurant_intent_service.tiered_answer`, shared with the
    `POST /api/smartbi/gold/restaurant/tiered-answer` endpoint the Java
    GoldBackedRestaurantTool delegate gate calls. Signature/behavior here are
    byte-for-byte unchanged from before the extraction (the 3 existing
    chat.py call sites and all pre-existing tests are unaffected).

    ONLY call this after the existing T1 keyword fast path
    (`match_restaurant_ops`) has already missed at this call site -- this
    function does not re-check keywords, it goes straight to
    `parse_restaurant_query` (which itself re-tries T1 first, cheaply, before
    T2/T3 -- so calling it unconditionally is safe, just slightly redundant).

    Fail-open: returns None on any miss/exception/business-type-gate-closed,
    so every call site's existing fallback chain is reached exactly as
    before this feature existed (zero regression risk for non-restaurant
    tenants or when anything below throws).

    `session_key` (2026-07-08 clarification-loop v1, additive/optional):
    forwarded to `tiered_answer` / `parse_restaurant_query` so a user's
    answer to a clarification question from a PREVIOUS call at this same
    call site is parsed in context instead of as a brand-new query. Chat
    callers only pass the fixed-length trusted factory/user/session key
    produced below; absent trusted users disable clarification persistence.

    Return shape:
      {"kind": "clarification", "answer_text": str, "spec": spec}
      {"kind": "answer", "answer_text": str, "charts": list, "kpis": list,
       "title": str, "code": str, "contract_pass": bool, "spec": spec}
    """
    from smartbi.gold.restaurant_intent_service import tiered_answer

    return await tiered_answer(query, pool, factory_id, role, session_key=session_key)


def _build_qa_input_text(query: str, data_context: Optional[str]) -> str:
    """Build a self-contained input_text for chat_qa corpus samples.

    The (input_text → teacher_output) pair must be self-contained so a future
    model learns to answer FROM data, not hallucinate numbers.  A bare user
    query gives zero grounding — we mirror the pattern used by
    ``_corpus_input_text`` in chart_insight_service.py: embed the query PLUS
    a summary of the structured data / aggregates that produced the answer.

    data_context: a pre-built string of aggregated facts / stats (ideally
    key-value pairs like "revenue=¥1.2M, top_item=猪舌×531盒").  If None or
    empty, the query is still captured but tagged quality=3 (bare organic).
    """
    header = "chat_qa|general_analysis"
    if data_context:
        return f"{header}|query:{query}|data_context:{data_context}"
    # No data context available — still useful, but lower quality
    return f"{header}|query:{query}"


def _derive_business_type(factory_id: Optional[str], table_type: Optional[str]) -> str:
    """Derive business_type (restaurant/factory/unknown) from available context.

    Uses the same logic convention as chart_insight_service._map_domain:
    - factory_ids prefixed with restaurant-domain knowledge (qhj → restaurant)
    - table_type hint if passed by the Java caller
    - falls back to "unknown" (honest fallback, never pollutes bucket with wrong type)
    """
    if table_type:
        t = table_type.lower()
        if "restaurant" in t or "餐" in t:
            return "restaurant"
        if "factory" in t or "manufacture" in t or "工厂" in t or "制造" in t:
            return "factory"
    # Known restaurant factory prefixes (qhj = 青花椒餐饮)
    if factory_id:
        fid = factory_id.upper()
        if fid.startswith("QHJ") or fid.startswith("RESTAURANT"):
            return "restaurant"
        # Default Cretas factory customers → factory
        if fid.startswith("F") and len(fid) <= 6:
            return "factory"
    return "unknown"


def _qa_lint_has_data_context(data_context: Optional[str]) -> bool:
    """Return True if data_context is substantive enough to justify quality=4.

    Criteria (per spec §4 G1): has data context + passes basic no-fake-data
    lint. We check that data_context is non-empty, reasonably long (>20 chars),
    and contains at least one numeric figure (¥ amount, row count, percentage…).
    """
    if not data_context or len(data_context.strip()) < 20:
        return False
    import re
    # Must contain at least one numeric token (number / ¥ / % / 条/行/条)
    return bool(re.search(r'[\d¥%]', data_context))


def _build_qa_data_context(
    data: Optional[list],
    insights_result: Optional[dict],
) -> Optional[str]:
    """Build a compact data-context string from the structured data and analysis
    aggregates that produced the chat answer.

    Mirrors the spirit of ``_corpus_input_text`` in chart_insight_service.py:
    bake the real underlying figures into the input_text so the (input→output)
    pair is self-contained and a future model learns from data, not from
    memorising the question.

    Returns a compact key=value summary string, or None if no usable facts found.
    """
    try:
        parts: list = []
        if data:
            parts.append(f"rows={len(data)}")
            # Column names give domain context
            if data[0] and isinstance(data[0], dict):
                cols = list(data[0].keys())[:8]
                parts.append(f"cols=[{','.join(str(c) for c in cols)}]")
                # Collect numeric column totals / ranges as grounding figures
                import pandas as pd
                try:
                    df = pd.DataFrame(data)
                    numeric_cols = df.select_dtypes(include=["number"]).columns.tolist()
                    for col in numeric_cols[:4]:
                        col_sum = df[col].sum()
                        col_max = df[col].max()
                        if col_sum != 0:
                            parts.append(f"{col}_sum={col_sum:.2f}")
                        elif col_max != 0:
                            parts.append(f"{col}_max={col_max:.2f}")
                except Exception:
                    pass
        if insights_result and isinstance(insights_result, dict):
            summary = insights_result.get("summary", "")
            if summary and summary not in ("数据分析完成。", ""):
                # First 120 chars of summary as grounding context
                parts.append(f"summary_snippet={summary[:120]}")
        if not parts:
            return None
        return "; ".join(parts)
    except Exception:
        return None


async def _capture_qa_distillation(
    query: str,
    answer: str,
    http_request,
    *,
    data_context: Optional[str] = None,
    table_type: Optional[str] = None,
) -> None:
    """Capture an AI Q&A teacher pair into the distillation corpus.

    **P0-1 fix (2026-06-11)**:
    - input_text now embeds a data-context summary (not just the bare query)
      so the (input→output) pair teaches FROM data, not hallucination.
    - business_type derived from factory_id/table_type instead of hard-coded "unknown".
    - quality assigned: 4 if data context passes lint (substantive numerics), else 3.

    Fire-and-forget — NEVER raises, never affects the response.
    Only called on freshly-generated LLM answers (cache-hit returns upstream).
    """
    try:
        if not query or not answer:
            return
        from smartbi.config import get_pg_pool
        from smartbi.services.distillation_capture import persist_distillation_sample
        factory_id = (
            getattr(http_request.state, 'factory_id', None)
            if hasattr(http_request, 'state') else None
        )
        business_type = _derive_business_type(factory_id, table_type)
        input_text = _build_qa_input_text(query, data_context)
        quality = 4 if _qa_lint_has_data_context(data_context) else 3
        pool = await get_pg_pool()
        await persist_distillation_sample(
            pool,
            source="chat_qa",
            task_type="qa",
            input_text=input_text,
            teacher_output=answer,
            business_type=business_type,
            factory_id=factory_id,
            quality=quality,
            metadata={"has_data_context": data_context is not None and len((data_context or "")) >= 20},
        )
    except Exception as e:  # belt-and-suspenders; helper already swallows
        logger.debug(f"[distill] qa capture skipped (non-blocking): {e}")


# H4 (Apr 27 2026): shared helpers for v2 conv memory across multiple SSE
# endpoints (drill_down_stream / root_cause_stream / benchmark_stream).
# Keeps the lookup + writeback logic DRY across endpoints.
async def _v2_conv_lookup(http_request, session_id: Optional[str]) -> tuple[Optional[Dict], Optional[str], Optional[int]]:  # noqa: E501
    """Phase 0 v2 conv memory lookup. Returns (parent_dict, factory_id, user_id).

    parent_dict is None when session_id absent / no factory / lookup fails.
    factory_id and user_id are extracted regardless (used by writeback).
    """
    factory_id = (
        getattr(http_request.state, 'factory_id', None)
        if hasattr(http_request, 'state') else None
    )
    raw_user_id = (
        getattr(http_request.state, 'user_id', None)
        if hasattr(http_request, 'state') else None
    )
    from smartbi.services.chat_session_service import parse_trusted_user_id
    user_id = parse_trusted_user_id(raw_user_id)

    # A session without an authenticated numeric user must never degrade to a
    # factory-only lookup. Multiple users can share a factory and even a device;
    # factory-only lookup would reintroduce cross-user conversation disclosure.
    if not session_id or not factory_id or user_id is None:
        return (None, factory_id, user_id)
    try:
        from smartbi.services.chat_session_service import ChatSessionService
        from smartbi.config import get_pg_pool as _gp
        pool = await _gp()
        if pool is None:
            return (None, factory_id, user_id)
        parent = await ChatSessionService(pool).lookup(
            session_id, factory_id, user_id=user_id
        )
        return (parent, factory_id, user_id)
    except Exception:
        logger.warning("[v2-conv-lookup] failed (non-fatal): error_code=SESSION_LOOKUP_FAILED")
        return (None, factory_id, user_id)


def _v2_inject_context(parent: Optional[Dict], user_prompt: str) -> str:
    """Prepend v2 conv memory context to user_prompt if parent available."""
    if not parent:
        return user_prompt
    try:
        from smartbi.services.chat_session_service import build_context_block
        ctx = build_context_block(parent)
        if ctx:
            return ctx + user_prompt
    except Exception as e:
        logger.warning(f"[v2-conv-inject] failed (non-fatal): {e}")
    return user_prompt


def _v2_writeback_bg(session_id: Optional[str], factory_id: Optional[str],
                     user_id: Optional[int], query: str, answer: str,
                     template_code: Optional[str] = None,
                     upload_id: Optional[int] = None) -> None:
    """Fire-and-forget v2 writeback. Anchored in _PENDING_BG_TASKS so survives
    generator cancellation. Skipped silently when session_id or factory_id missing.
    """
    if (
        not session_id
        or not factory_id
        or user_id is None
        or user_id <= 0
        or not answer
    ):
        return
    try:
        from smartbi.services.chat_session_service import ChatSessionService
        from smartbi.config import get_pg_pool as _gp
        from smartbi.api.materialized_analytics import _spawn_bg

        async def _do_upsert():
            pool = await _gp()
            if pool is None:
                return
            await ChatSessionService(pool).upsert(
                session_id=session_id,
                factory_id=factory_id,
                parent_query=query,
                parent_answer_summary=answer,
                parent_template_code=template_code,
                parent_upload_id=upload_id,
                user_id=user_id,
            )
        _spawn_bg(_do_upsert())
    except Exception:
        logger.warning("[v2-conv-writeback] failed (non-fatal): error_code=SESSION_WRITEBACK_FAILED")


# ============================================================================
# Chat Cache Helpers
# ============================================================================

def _make_chat_cache_key(query_type: str, **kwargs) -> str:
    """
    Build a cache key for chat endpoints.

    Combines query_type with arbitrary keyword arguments into a stable
    SHA-256 hash (24-char hex). Data lists use a complete, deterministic,
    order-sensitive digest so a change after the first few rows cannot reuse
    a stale analysis. Rows are hashed incrementally to avoid building a second
    copy of the complete dataset in memory.
    """
    parts: Dict[str, Any] = {"t": query_type}
    for k, v in sorted(kwargs.items()):
        if k == "data" and isinstance(v, list):
            digest = hashlib.sha256()
            for row in v:
                encoded = _json.dumps(
                    row,
                    sort_keys=True,
                    ensure_ascii=False,
                    default=str,
                    separators=(",", ":"),
                ).encode("utf-8")
                digest.update(len(encoded).to_bytes(8, "big"))
                digest.update(encoded)
            parts[k] = {"rows": len(v), "sha256": digest.hexdigest()}
        else:
            parts[k] = v
    raw = _json.dumps(parts, sort_keys=True, ensure_ascii=False, default=str)
    return hashlib.sha256(raw.encode()).hexdigest()[:24]


def _chat_cache_get(key: str) -> Optional[Any]:
    """Look up a chat result in InsightCache. Returns payload or None."""
    entry = get_insight_cache().get(key)
    if entry is not None:
        logger.info(f"[ChatCache] HIT key={key[:12]}...")
        return entry.insights  # stored payload
    return None


def _chat_cache_set(key: str, payload: Any) -> None:
    """Store a chat result in InsightCache."""
    get_insight_cache().set(key, payload)
    logger.info(f"[ChatCache] SET key={key[:12]}...")


def _trusted_chat_identity(http_request: Request) -> tuple[Optional[str], str, Optional[int], bool]:
    """Return middleware-authenticated role/user dimensions for chat isolation.

    The JSON body and raw headers are deliberately ignored. Missing/blank roles
    are represented as least privilege, and only a positive numeric trusted user
    may participate in conversation-memory lookup/writeback.
    """
    state = getattr(http_request, "state", None)
    raw_role = getattr(state, "role", None) if state is not None else None
    trusted_role = str(raw_role).strip().lower() if raw_role is not None else ""
    trusted_role = trusted_role or None

    raw_user_id = getattr(state, "user_id", None) if state is not None else None
    from smartbi.services.chat_session_service import parse_trusted_user_id
    session_user_id = parse_trusted_user_id(raw_user_id)
    trusted_user_key = (
        str(session_user_id)
        if session_user_id is not None
        else "__NO_TRUSTED_USER__"
    )

    from smartbi_compat._rbac_strip import PRICE_VIEW_ROLES

    price_view = bool(trusted_role and trusted_role in PRICE_VIEW_ROLES)
    return trusted_role, trusted_user_key, session_user_id, price_view


def _trusted_restaurant_session_key(
    factory_id: str,
    trusted_user_id: Optional[int],
    session_id: Optional[str],
) -> Optional[str]:
    """Build a bounded clarification key from authenticated identity.

    ``restaurant_pending_clarifications`` stores a ``TEXT`` key scoped by
    factory. Hashing factory, the positive middleware-authenticated user, and
    the normalized body session keeps the key fixed at 75 characters, avoids
    persisting the raw session identifier, and prevents shared-device users
    from consuming each other's pending clarification. A body session alone
    never enables clarification persistence.
    """
    from smartbi.services.chat_session_service import (
        build_trusted_restaurant_session_key,
    )
    return build_trusted_restaurant_session_key(
        factory_id, trusted_user_id, session_id,
    )


_RESTAURANT_ANALYSIS_CONTEXT_FIELDS = frozenset({
    "store_id",
    "store_name",
    "start_date",
    "end_date",
    "comparison_start_date",
    "comparison_end_date",
    "time_anchor_date",
})
_RESTAURANT_ANALYSIS_DATE_FIELDS = frozenset({
    "start_date",
    "end_date",
    "comparison_start_date",
    "comparison_end_date",
    "time_anchor_date",
})
_RESTAURANT_ANALYSIS_FIELD_LABELS = {
    "store_id": "门店编号",
    "store_name": "门店名称",
    "start_date": "开始日期",
    "end_date": "结束日期",
    "comparison_start_date": "对比开始日期",
    "comparison_end_date": "对比结束日期",
    "time_anchor_date": "时间基准日期",
}


def _validated_restaurant_analysis_context(
    context: Optional[Dict[str, Any]],
    table_type: Optional[str],
) -> Dict[str, Any]:
    """Validate the only request-body fields restaurant resolvers may consume.

    Tenant identity is deliberately absent from the allow-list. Authorization,
    session partitioning, and cache partitioning continue to use middleware state.
    """
    if table_type != "restaurant_ops" or not context:
        return {}
    unknown = sorted(set(context) - _RESTAURANT_ANALYSIS_CONTEXT_FIELDS)
    if unknown:
        raise HTTPException(status_code=422, detail="餐饮分析上下文字段不受支持")

    validated: Dict[str, Any] = {}
    for key in _RESTAURANT_ANALYSIS_CONTEXT_FIELDS:
        raw_value = context.get(key)
        if raw_value is None:
            continue
        field_label = _RESTAURANT_ANALYSIS_FIELD_LABELS[key]
        if not isinstance(raw_value, str):
            raise HTTPException(status_code=422, detail=f"餐饮分析的{field_label}格式无效")
        value = raw_value.strip()
        if not value:
            continue
        max_length = 160 if key == "store_name" else 64
        if key in _RESTAURANT_ANALYSIS_DATE_FIELDS:
            max_length = 10
        if len(value) > max_length:
            raise HTTPException(status_code=422, detail=f"餐饮分析的{field_label}过长")
        if key in _RESTAURANT_ANALYSIS_DATE_FIELDS:
            try:
                parsed = date.fromisoformat(value)
            except ValueError:
                raise HTTPException(
                    status_code=422,
                    detail=f"餐饮分析的{field_label}必须使用年-月-日格式",
                ) from None
            if parsed.isoformat() != value:
                raise HTTPException(
                    status_code=422,
                    detail=f"餐饮分析的{field_label}必须使用年-月-日格式",
                )
            validated[key] = parsed
        else:
            validated[key] = value

    for start_key, end_key in (
        ("start_date", "end_date"),
        ("comparison_start_date", "comparison_end_date"),
    ):
        has_start = start_key in validated
        has_end = end_key in validated
        if has_start != has_end:
            raise HTTPException(status_code=422, detail="餐饮分析日期范围必须同时提供开始和结束日期")
        if has_start and validated[start_key] > validated[end_key]:
            raise HTTPException(status_code=422, detail="餐饮分析开始日期不能晚于结束日期")
        if has_start and (validated[end_key] - validated[start_key]).days + 1 > 366:
            raise HTTPException(status_code=422, detail="餐饮分析的单个日期范围不能超过366天")
    if "comparison_start_date" in validated and "start_date" not in validated:
        raise HTTPException(status_code=422, detail="对比日期必须与主日期范围同时提供")

    has_comparison = "comparison_start_date" in validated
    anchor = validated.get("time_anchor_date")
    if has_comparison and anchor is None:
        raise HTTPException(status_code=422, detail="日期对比必须提供有效的时间基准日期")
    if has_comparison:
        primary_start = validated["start_date"]
        primary_end = validated["end_date"]
        comparison_start = validated["comparison_start_date"]
        comparison_end = validated["comparison_end_date"]
        ranges_are_disjoint = (
            primary_end < comparison_start
            or comparison_end < primary_start
        )
        if not ranges_are_disjoint:
            raise HTTPException(status_code=422, detail="主日期范围与对比日期范围不能重叠")

    if anchor is not None:
        try:
            earliest_allowed = anchor.replace(year=anchor.year - 2)
        except ValueError:
            # A leap-day anchor maps to the last valid day in February.
            earliest_allowed = anchor.replace(year=anchor.year - 2, day=28)
        ranges = []
        if "start_date" in validated:
            ranges.append((validated["start_date"], validated["end_date"]))
        if has_comparison:
            ranges.append((
                validated["comparison_start_date"],
                validated["comparison_end_date"],
            ))
        if any(end > anchor for _start, end in ranges):
            raise HTTPException(status_code=422, detail="餐饮分析日期不能晚于时间基准日期")
        if any(start < earliest_allowed for start, _end in ranges):
            raise HTTPException(status_code=422, detail="餐饮分析日期不能早于时间基准日期前两年")
    return validated


def _restaurant_analysis_data_factory_id(
    trusted_factory_id: str,
    context: Dict[str, Any],
) -> str:
    """Map demo store reads only; never use this value for auth/session/cache."""
    store_scoped = bool(context.get("store_id") or context.get("store_name"))
    if store_scoped and trusted_factory_id.upper() == "DEMO_REST":
        return "RES_3101_009"
    return trusted_factory_id


# ============================================================================
# Request/Response Models
# ============================================================================

class DrillDownRequest(BaseModel):
    """Request for drill-down analysis"""
    sheet_id: str = Field(..., description="Sheet identifier")
    dimension: str = Field(..., description="Dimension to drill down on")
    filter_value: Optional[str] = Field(None, description="Value to filter on")
    measures: List[str] = Field(default=["amount", "revenue", "profit"], description="Measures to aggregate")
    aggregation: str = Field(default="sum", description="Aggregation method")
    data: Optional[List[Dict[str, Any]]] = Field(None, description="Data to analyze (if not from cache)")
    # P4: Multi-level drill-down fields
    hierarchy_type: Optional[str] = Field(None, description="Hierarchy type: time, geography, organization, product")
    current_level: Optional[int] = Field(None, description="Current level index in hierarchy")
    breadcrumb: Optional[List[Dict[str, str]]] = Field(default=None, description="Breadcrumb trail")
    # H4 (Apr 27 2026): v2 conv memory hook
    session_id: Optional[str] = Field(None, description="v2 conv memory session_id")


class DrillDownResponse(BaseModel):
    """Response for drill-down analysis"""
    success: bool
    error: Optional[str] = None
    result: Optional[Dict[str, Any]] = None
    chart_config: Optional[Dict[str, Any]] = None
    processing_time_ms: int = 0
    # P4: Multi-level drill-down fields
    available_dimensions: List[str] = []
    hierarchy: Optional[Dict[str, Any]] = None
    breadcrumb: List[Dict[str, str]] = []
    current_level: Optional[int] = None
    max_level: Optional[int] = None


class BenchmarkRequest(BaseModel):
    """Request for industry benchmark comparison"""
    sheet_id: str = Field(..., description="Sheet identifier")
    industry: str = Field(..., description="Industry for comparison (food_processing, retail, etc.)")
    metrics: Dict[str, float] = Field(..., description="Company metrics to compare")
    metric_mapping: Optional[Dict[str, str]] = Field(None, description="Optional metric name mapping")
    # H4 (Apr 27 2026): v2 conv memory hook
    session_id: Optional[str] = Field(None, description="v2 conv memory session_id")


class BenchmarkResponse(BaseModel):
    """Response for benchmark comparison"""
    success: bool
    error: Optional[str] = None
    result: Optional[Dict[str, Any]] = None
    sources: List[str] = []
    processing_time_ms: int = 0


class RootCauseRequest(BaseModel):
    """Request for root cause analysis"""
    sheet_id: str = Field(..., description="Sheet identifier")
    kpi: str = Field(..., description="KPI to analyze")
    threshold: float = Field(default=0.1, description="Significance threshold")
    data: Optional[List[Dict[str, Any]]] = Field(None, description="Data to analyze")
    # H4 (Apr 27 2026): v2 conv memory hook
    session_id: Optional[str] = Field(None, description="v2 conv memory session_id")


class RootCauseResponse(BaseModel):
    """Response for root cause analysis"""
    success: bool
    error: Optional[str] = None
    kpi: str = ""
    root_causes: List[Dict[str, Any]] = []
    correlations: List[Dict[str, Any]] = []
    recommendations: List[str] = []
    processing_time_ms: int = 0


class GeneralAnalysisRequest(BaseModel):
    """Request for general analysis (accepts both Python and Java field names)"""
    sheet_id: Optional[str] = Field(None, description="Sheet identifier (optional for standalone queries)")
    query: Optional[str] = Field(None, description="Analysis query/question")
    message: Optional[str] = Field(None, description="Alias for query (Java compat)")
    data: Optional[List[Dict[str, Any]]] = Field(None, description="Data to analyze")
    context: Optional[Dict[str, Any]] = Field(None, description="Additional context")
    fields: Optional[List[Dict[str, str]]] = Field(None, description="Field mappings")
    table_type: Optional[str] = Field(None, description="Table type hint")
    expected_intent: Optional[str] = Field(
        None,
        description="Validated restaurant intent selected by the trusted Java router",
    )
    user_id: Optional[str] = Field(None, description="User ID (Java compat)")
    session_id: Optional[str] = Field(None, description="Session ID (Java compat)")
    enable_thinking: Optional[bool] = Field(None, description="Enable thinking mode (Java compat)")
    thinking_budget: Optional[int] = Field(None, description="Thinking budget (Java compat)")
    allow_tenant_data_fallback: bool = Field(
        True,
        description=(
            "Allow loading tenant-owned upload data when no request data was supplied. "
            "Trusted browser flows default to the historical behavior; internal callers "
            "that provide their own context should set this to false."
        ),
    )

    @property
    def effective_query(self) -> str:
        return self.query or self.message or ""


class GeneralAnalysisResponse(BaseModel):
    """Response for general analysis (includes Java-compat fields)"""
    success: bool
    error: Optional[str] = None
    answer: str = ""
    aiAnalysis: Optional[str] = None
    reasoningContent: Optional[str] = None
    thinkingEnabled: Optional[bool] = None
    sessionId: Optional[str] = None
    messageCount: Optional[int] = None
    insights: List[Dict[str, Any]] = []
    charts: List[Dict[str, Any]] = []
    processing_time_ms: int = 0

class MultiDimensionRequest(BaseModel):
    """Request for multi-dimensional insight analysis"""
    sheet_id: str = Field(..., description="Sheet identifier")
    data: List[Dict[str, Any]] = Field(..., description="Data to analyze")
    dimensions: Optional[List[str]] = Field(None, description="Insight dimensions to focus on")
    context: Optional[Dict[str, Any]] = Field(None, description="Analysis context")


class MultiDimensionResponse(BaseModel):
    """Response for multi-dimensional analysis"""
    success: bool
    error: Optional[str] = None
    executive_summary: str = ""
    insights: List[Dict[str, Any]] = []
    risk_alerts: List[Dict[str, Any]] = []
    opportunities: List[Dict[str, Any]] = []
    processing_time_ms: int = 0


# ============================================================================
# Data Store (In-memory cache for demo, replace with proper storage)
# ============================================================================

from cachetools import TTLCache  # noqa: E402

_sheet_data_cache: TTLCache = TTLCache(maxsize=50, ttl=3600)


def get_sheet_data(
    factory_id: str,
    sheet_id: str,
) -> Optional[List[Dict[str, Any]]]:
    """Get cached sheet data from a tenant-scoped namespace."""
    return _sheet_data_cache.get((factory_id, sheet_id))


def cache_sheet_data(
    factory_id: str,
    sheet_id: str,
    data: List[Dict[str, Any]],
) -> None:
    """Cache sheet data without allowing cross-tenant key collisions."""
    _sheet_data_cache[(factory_id, sheet_id)] = data


def _require_trusted_factory_id(http_request: Request) -> str:
    """Return the middleware-authenticated tenant or fail closed.

    General analysis can read cached and persisted uploads, so a tenant supplied
    in the JSON body is never an acceptable authorization source. The only
    trusted identity is the value written to ``request.state`` by
    ``JWTAuthMiddleware``.
    """
    raw_factory_id = (
        getattr(http_request.state, "factory_id", None)
        if hasattr(http_request, "state")
        else None
    )
    factory_id = str(raw_factory_id).strip() if raw_factory_id is not None else ""
    if not factory_id:
        raise HTTPException(status_code=403, detail="TRUSTED_TENANT_REQUIRED")
    return factory_id


async def _require_owned_upload_id(
    sheet_id: Optional[str],
    factory_id: str,
) -> Optional[int]:
    """Validate an optional upload id without revealing cross-tenant existence."""
    if sheet_id is None:
        return None
    try:
        upload_id = int(sheet_id)
    except (TypeError, ValueError):
        raise HTTPException(status_code=404, detail="UPLOAD_NOT_FOUND") from None
    if upload_id <= 0:
        raise HTTPException(status_code=404, detail="UPLOAD_NOT_FOUND")

    from smartbi.config import get_pg_pool

    pool = await get_pg_pool()
    if pool is None:
        raise HTTPException(status_code=503, detail="TENANT_OWNERSHIP_UNAVAILABLE")
    try:
        async with pool.acquire() as conn:
            row = await conn.fetchrow(
                """
                SELECT id
                FROM smart_bi_pg_excel_uploads
                WHERE id = $1 AND factory_id = $2
                """,
                upload_id,
                factory_id,
            )
    except Exception as exc:
        logger.error(
            "Upload ownership validation failed: error_code=TENANT_OWNERSHIP_DB_ERROR"
        )
        raise HTTPException(
            status_code=503,
            detail="TENANT_OWNERSHIP_UNAVAILABLE",
        ) from exc
    if row is None:
        raise HTTPException(status_code=404, detail="UPLOAD_NOT_FOUND")
    return upload_id


# ============================================================================
# Endpoints
# ============================================================================

@router.post("/drill-down", response_model=DrillDownResponse)
async def drill_down(
    request: DrillDownRequest,
    http_request: Request,
) -> DrillDownResponse:
    """
    Perform drill-down analysis on a dimension.

    Called by Java when user asks questions like:
    - "按区域拆分看看"
    - "华东区具体怎么样"
    - "深入分析产品类别"

    Args:
        request: DrillDownRequest with dimension and filter parameters

    Returns:
        DrillDownResponse with detailed breakdown and chart config
    """
    start_time = time.time()

    trusted_factory_id = _require_trusted_factory_id(http_request)
    await _require_owned_upload_id(request.sheet_id, trusted_factory_id)

    # Cache lookup
    cache_key = _make_chat_cache_key(
        "drill_down",
        factory_id=trusted_factory_id,
        sheet_id=request.sheet_id,
        dimension=request.dimension,
        filter_value=request.filter_value,
        measures=request.measures,
        aggregation=request.aggregation,
        hierarchy_type=request.hierarchy_type,
        current_level=request.current_level,
        data=request.data,
    )
    cached = _chat_cache_get(cache_key)
    if cached is not None:
        cached["processing_time_ms"] = 0
        return DrillDownResponse(**cached)

    try:
        # Get data from request or cache
        data = request.data
        if not data:
            data = get_sheet_data(trusted_factory_id, request.sheet_id)

        if not data:
            return DrillDownResponse(
                success=False,
                error=f"No data found for sheet {request.sheet_id}",
                processing_time_ms=int((time.time() - start_time) * 1000)
            )

        # Convert to DataFrame for analysis
        import pandas as pd
        df = coerce_numeric_columns(pd.DataFrame(data))

        # Validate dimension exists
        if request.dimension not in df.columns:
            available = df.columns.tolist()
            return DrillDownResponse(
                success=False,
                error=f"Dimension '{request.dimension}' not found. Available: {available}",
                processing_time_ms=int((time.time() - start_time) * 1000)
            )

        # Find valid measures
        valid_measures = [m for m in request.measures if m in df.columns]
        if not valid_measures:
            # Use all numeric columns as measures
            valid_measures = df.select_dtypes(include=['number']).columns.tolist()

        if not valid_measures:
            return DrillDownResponse(
                success=False,
                error="未检测到数值型字段，无法进行分析",
                processing_time_ms=int((time.time() - start_time) * 1000)
            )

        # P4: Determine child dimension via hierarchy or auto-detection
        detected_hierarchy = auto_detect_hierarchy(df.columns.tolist())
        child_dimension = None
        hierarchy_info = None
        new_breadcrumb = list(request.breadcrumb or [])

        if request.hierarchy_type and request.hierarchy_type in DimensionHierarchy.HIERARCHIES:
            # Explicit hierarchy provided
            levels = DimensionHierarchy.HIERARCHIES[request.hierarchy_type]["levels"]
            current_lvl = request.current_level or 0
            # Map level names to actual column names in data
            level_columns = _map_hierarchy_to_columns(levels, df.columns.tolist())
            if current_lvl + 1 < len(level_columns):
                child_dimension = level_columns[current_lvl + 1]
            hierarchy_info = {
                "type": request.hierarchy_type,
                "levels": level_columns,
                "current_level": current_lvl + 1,
                "max_level": len(level_columns) - 1
            }
        elif detected_hierarchy and request.filter_value:
            # Auto-detected hierarchy
            h_type, h_levels = detected_hierarchy
            current_dim_idx = -1
            for i, lvl in enumerate(h_levels):
                if lvl == request.dimension:
                    current_dim_idx = i
                    break
            if current_dim_idx >= 0 and current_dim_idx + 1 < len(h_levels):
                child_dimension = h_levels[current_dim_idx + 1]
                hierarchy_info = {
                    "type": h_type,
                    "levels": h_levels,
                    "current_level": current_dim_idx + 1,
                    "max_level": len(h_levels) - 1
                }

        # Perform drill-down
        analyzer = CrossAnalyzer()

        if request.filter_value:
            # Determine the target dimension for breakdown
            drill_child = child_dimension or request.dimension  # noqa: F841
            if child_dimension and child_dimension != request.dimension:
                # True hierarchical drill-down: filter parent, break down by child
                result = await analyzer.drill_down(
                    df=df,
                    parent_dimension=request.dimension,
                    parent_value=request.filter_value,
                    child_dimension=child_dimension,
                    measures=valid_measures,
                    aggregation=request.aggregation
                )
            else:
                # Same dimension filter (original behavior)
                result = await analyzer.drill_down(
                    df=df,
                    parent_dimension=request.dimension,
                    parent_value=request.filter_value,
                    child_dimension=request.dimension,
                    measures=valid_measures,
                    aggregation=request.aggregation
                )

            # Update breadcrumb
            new_breadcrumb.append({
                "dimension": request.dimension,
                "value": request.filter_value
            })
        else:
            # Simple aggregation by dimension
            agg_funcs = {m: request.aggregation for m in valid_measures}
            grouped = df.groupby(request.dimension).agg(agg_funcs).reset_index()

            if valid_measures:
                grouped = grouped.sort_values(valid_measures[0], ascending=False)

            result = DrillDownResult(
                success=True,
                parent_dimension="all",
                parent_value="*",
                child_dimension=request.dimension,
                data=grouped.to_dict(orient="records"),
                summary={
                    "dimension": request.dimension,
                    "unique_values": len(grouped),
                    "total_records": len(df),
                    "measure_totals": {m: float(grouped[m].sum()) for m in valid_measures}
                }
            )

            # Generate chart config
            result.chart_config = _generate_bar_chart_config(
                request.dimension, valid_measures, grouped
            )

        # P4: Detect available dimensions for further drill-down
        available_dims = _find_available_dimensions(df, request.dimension, valid_measures)

        response = DrillDownResponse(
            success=result.success,
            error=result.error,
            result=result.to_dict() if result.success else None,
            chart_config=result.chart_config,
            processing_time_ms=int((time.time() - start_time) * 1000),
            available_dimensions=available_dims,
            hierarchy=hierarchy_info,
            breadcrumb=new_breadcrumb,
            current_level=hierarchy_info["current_level"] if hierarchy_info else None,
            max_level=hierarchy_info["max_level"] if hierarchy_info else None
        )

        # Cache successful result
        if response.success:
            _chat_cache_set(cache_key, response.dict())

        return response

    except Exception as e:
        logger.error(f"Drill-down failed: {e}", exc_info=True)
        return DrillDownResponse(
            success=False,
            error="AI对话处理失败，请稍后重试",
            processing_time_ms=int((time.time() - start_time) * 1000)
        )


@router.post("/benchmark", response_model=BenchmarkResponse)
async def benchmark(
    request: BenchmarkRequest,
    http_request: Request,
) -> BenchmarkResponse:
    """
    Compare metrics with industry benchmarks.

    Called by Java when user asks questions like:
    - "跟行业比怎么样"
    - "我们的毛利率在行业什么水平"
    - "对标同行业"

    Args:
        request: BenchmarkRequest with industry and metrics

    Returns:
        BenchmarkResponse with comparison results and recommendations
    """
    start_time = time.time()

    trusted_factory_id = _require_trusted_factory_id(http_request)
    await _require_owned_upload_id(request.sheet_id, trusted_factory_id)

    # Cache lookup
    cache_key = _make_chat_cache_key(
        "benchmark",
        factory_id=trusted_factory_id,
        sheet_id=request.sheet_id,
        industry=request.industry,
        metrics=request.metrics,
        metric_mapping=request.metric_mapping,
    )
    cached = _chat_cache_get(cache_key)
    if cached is not None:
        cached["processing_time_ms"] = 0
        return BenchmarkResponse(**cached)

    try:
        # Map industry string to enum
        industry_map = {
            "food_processing": IndustryCategory.FOOD_PROCESSING,
            "food": IndustryCategory.FOOD_PROCESSING,
            "食品加工": IndustryCategory.FOOD_PROCESSING,
            "食品": IndustryCategory.FOOD_PROCESSING,
            "retail": IndustryCategory.RETAIL,
            "零售": IndustryCategory.RETAIL,
            "manufacturing": IndustryCategory.MANUFACTURING,
            "制造": IndustryCategory.MANUFACTURING
        }

        industry_enum = industry_map.get(
            request.industry.lower(),
            IndustryCategory.FOOD_PROCESSING
        )

        # Perform benchmark comparison
        benchmark_service = IndustryBenchmark()
        result = await benchmark_service.compare_with_industry(
            company_metrics=request.metrics,
            industry=industry_enum,
            metric_mapping=request.metric_mapping
        )

        response = BenchmarkResponse(
            success=result.success,
            error=result.error,
            result=result.to_dict() if result.success else None,
            sources=result.data_sources,
            processing_time_ms=int((time.time() - start_time) * 1000)
        )

        # Cache successful result
        if response.success:
            _chat_cache_set(cache_key, response.dict())

        return response

    except Exception as e:
        logger.error(f"Benchmark failed: {e}", exc_info=True)
        return BenchmarkResponse(
            success=False,
            error="AI对话处理失败，请稍后重试",
            processing_time_ms=int((time.time() - start_time) * 1000)
        )


@router.post("/root-cause", response_model=RootCauseResponse)
async def root_cause(
    request: RootCauseRequest,
    http_request: Request,
) -> RootCauseResponse:
    """
    Analyze root causes for a KPI change.

    Called by Java when user asks questions like:
    - "为什么利润下降"
    - "分析销售额下滑原因"
    - "利润率降低的原因是什么"

    Args:
        request: RootCauseRequest with KPI and threshold

    Returns:
        RootCauseResponse with identified causes and recommendations
    """
    start_time = time.time()

    trusted_factory_id = _require_trusted_factory_id(http_request)
    await _require_owned_upload_id(request.sheet_id, trusted_factory_id)

    # Cache lookup
    cache_key = _make_chat_cache_key(
        "root_cause",
        factory_id=trusted_factory_id,
        sheet_id=request.sheet_id,
        kpi=request.kpi,
        threshold=request.threshold,
        data=request.data,
    )
    cached = _chat_cache_get(cache_key)
    if cached is not None:
        cached["processing_time_ms"] = 0
        return RootCauseResponse(**cached)

    try:
        # Get data
        data = request.data
        if not data:
            data = get_sheet_data(trusted_factory_id, request.sheet_id)

        if not data:
            return RootCauseResponse(
                success=False,
                error=f"No data found for sheet {request.sheet_id}",
                kpi=request.kpi,
                processing_time_ms=int((time.time() - start_time) * 1000)
            )

        import pandas as pd
        df = coerce_numeric_columns(pd.DataFrame(data))

        # Validate KPI exists
        if request.kpi not in df.columns:
            return RootCauseResponse(
                success=False,
                error=f"KPI '{request.kpi}' not found in data",
                kpi=request.kpi,
                processing_time_ms=int((time.time() - start_time) * 1000)
            )

        # Perform correlation analysis
        numeric_cols = df.select_dtypes(include=['number']).columns.tolist()
        other_cols = [c for c in numeric_cols if c != request.kpi]

        correlations = []
        root_causes = []

        if other_cols:
            # Calculate correlations with KPI
            kpi_values = df[request.kpi]

            for col in other_cols:
                try:
                    corr = kpi_values.corr(df[col])
                    if abs(corr) > request.threshold:
                        correlations.append({
                            "factor": col,
                            "correlation": round(corr, 3),
                            "relationship": "正相关" if corr > 0 else "负相关",
                            "strength": "强" if abs(corr) > 0.7 else "中等" if abs(corr) > 0.4 else "弱"
                        })
                except Exception:
                    continue

            # Sort by correlation strength
            correlations.sort(key=lambda x: abs(x["correlation"]), reverse=True)

            # Convert top correlations to root causes
            for i, corr in enumerate(correlations[:3]):
                direction = "同向变化" if corr["correlation"] > 0 else "反向变化"
                root_causes.append({
                    "rank": i + 1,
                    "factor": corr["factor"],
                    "description": f"{corr['factor']}与{request.kpi}{direction}，相关系数{corr['correlation']:.2f}",
                    "impact": corr["strength"],
                    "correlation": corr["correlation"]
                })

        # Generate recommendations
        recommendations = []
        if root_causes:
            for cause in root_causes[:2]:
                if cause["correlation"] < 0:
                    recommendations.append(f"关注{cause['factor']}的变化，其与{request.kpi}呈负相关")
                else:
                    recommendations.append(f"提升{cause['factor']}可能带动{request.kpi}增长")

        if not recommendations:
            recommendations.append(f"建议进一步收集数据分析{request.kpi}变化原因")

        response = RootCauseResponse(
            success=True,
            kpi=request.kpi,
            root_causes=root_causes,
            correlations=correlations,
            recommendations=recommendations,
            processing_time_ms=int((time.time() - start_time) * 1000)
        )

        # Cache successful result
        _chat_cache_set(cache_key, response.dict())

        return response

    except Exception as e:
        logger.error(f"Root cause analysis failed: {e}", exc_info=True)
        return RootCauseResponse(
            success=False,
            error="AI对话处理失败，请稍后重试",
            kpi=request.kpi,
            processing_time_ms=int((time.time() - start_time) * 1000)
        )


@router.post("/general-analysis", response_model=GeneralAnalysisResponse)
async def general_analysis(request: GeneralAnalysisRequest, http_request: Request) -> GeneralAnalysisResponse:
    """
    Perform general analysis based on query.

    Called by Java for general questions about the data.

    Args:
        request: GeneralAnalysisRequest with query and data

    Returns:
        GeneralAnalysisResponse with analysis results
    """
    start_time = time.time()

    # Trusted tenant and optional upload ownership are established before any
    # response-cache or sheet-cache lookup. Body identity fields are ignored.
    trusted_factory_id = _require_trusted_factory_id(http_request)
    restaurant_context = _validated_restaurant_analysis_context(
        request.context,
        request.table_type,
    )
    (
        trusted_role,
        trusted_user_key,
        trusted_session_user_id,
        trusted_price_view,
    ) = _trusted_chat_identity(http_request)
    trusted_restaurant_session_key = _trusted_restaurant_session_key(
        trusted_factory_id,
        trusted_session_user_id,
        request.session_id,
    )
    validated_upload_id = await _require_owned_upload_id(
        request.sheet_id,
        trusted_factory_id,
    )

    # Cache lookup (include query text in key for general-analysis).
    # factory_id MUST be part of the key: when request.data is empty this
    # endpoint falls back to loading the tenant's own upload from DB by
    # factory_id, so two tenants sending empty data would otherwise collide
    # on an identical cache key and leak each other's results (cross-tenant
    # cache pollution).
    cache_key = _make_chat_cache_key(
        "general_analysis",
        factory_id=trusted_factory_id,
        trusted_user_id=trusted_user_key,
        trusted_role=trusted_role or "__LEAST_PRIVILEGE__",
        price_view=trusted_price_view,
        allow_tenant_data_fallback=request.allow_tenant_data_fallback,
        sheet_id=request.sheet_id,
        session_id=request.session_id,
        enable_thinking=request.enable_thinking,
        thinking_budget=request.thinking_budget,
        query=request.effective_query,
        data=request.data,
        table_type=request.table_type,
        expected_intent=request.expected_intent,
        context=restaurant_context,
    )
    session_sensitive_restaurant = bool(
        request.table_type == "restaurant_ops" and request.session_id
    )
    cached = None if session_sensitive_restaurant else _chat_cache_get(cache_key)
    if cached is not None:
        cached["processing_time_ms"] = 0
        return GeneralAnalysisResponse(**cached)

    try:
        query = request.effective_query
        factory_id_hdr = trusted_factory_id
        if query and factory_id_hdr:
            try:
                from smartbi.gold.restaurant_ops_router import (
                    extract_store_mention,
                    is_supported_restaurant_ops_code,
                    reconcile_restaurant_ops_code,
                    resolve_by_code,
                )
                from smartbi.gold.restaurant_intent import contextualize_restaurant_followup
                from smartbi.config import get_pg_pool as _get_pool
                expected_ops_code = (
                    request.expected_intent
                    if request.table_type == "restaurant_ops"
                    and is_supported_restaurant_ops_code(request.expected_intent)
                    else None
                )
                pool = await _get_pool()
                effective_ops_query = query
                inherited_context = False
                if (
                    pool
                    and request.session_id
                    and trusted_session_user_id is not None
                    and request.table_type == "restaurant_ops"
                ):
                    from smartbi.services.chat_session_service import ChatSessionService
                    parent = await ChatSessionService(pool).lookup(
                        request.session_id,
                        factory_id_hdr,
                        user_id=trusted_session_user_id,
                    )
                    effective_ops_query, inherited_context = contextualize_restaurant_followup(
                        query, parent,
                    )

                structured_ops_code = reconcile_restaurant_ops_code(
                    effective_ops_query,
                    expected_ops_code,
                )
                structured_margin_scope = bool(
                    restaurant_context
                    and (
                        restaurant_context.get("store_id")
                        or restaurant_context.get("store_name")
                        or restaurant_context.get("comparison_start_date")
                    )
                    and any(token in effective_ops_query for token in ("毛利", "毛利率", "利润"))
                )
                if structured_margin_scope:
                    structured_ops_code = "RESTAURANT_OPS_STORE_MARGIN"

                if pool and restaurant_context and structured_ops_code:
                    primary_range = (
                        (
                            restaurant_context["start_date"],
                            restaurant_context["end_date"],
                        )
                        if restaurant_context.get("start_date")
                        else None
                    )
                    comparison_range = (
                        (
                            restaurant_context["comparison_start_date"],
                            restaurant_context["comparison_end_date"],
                        )
                        if restaurant_context.get("comparison_start_date")
                        else None
                    )
                    store_mention = None
                    if (
                        structured_ops_code == "RESTAURANT_OPS_STORE_MARGIN"
                        and not restaurant_context.get("store_id")
                        and not restaurant_context.get("store_name")
                    ):
                        store_mention = extract_store_mention(effective_ops_query)
                    mapping_context = (
                        {**restaurant_context, "store_name": store_mention}
                        if store_mention else restaurant_context
                    )
                    analysis_factory_id = _restaurant_analysis_data_factory_id(
                        factory_id_hdr,
                        mapping_context,
                    )
                    ops_answer = await resolve_by_code(
                        structured_ops_code,
                        pool,
                        analysis_factory_id,
                        role=trusted_role,
                        query=effective_ops_query,
                        date_range=primary_range,
                        comparison_date_range=comparison_range,
                        store_id=restaurant_context.get("store_id"),
                        store_name=restaurant_context.get("store_name"),
                        store_mention=store_mention,
                        today=restaurant_context.get("time_anchor_date"),
                    )
                    if ops_answer:
                        customer_answer = sanitize_customer_ai_text(ops_answer.answer_text)
                        guard_clarification = any(
                            key in (ops_answer.meta or {})
                            for key in ("missing_reference", "store_not_found",
                                        "store_mention_ambiguous")
                        )
                        displayable_result = (
                            guard_clarification
                            or has_displayable_business_result(customer_answer)
                        )
                        response = GeneralAnalysisResponse(
                            success=displayable_result,
                            error=None if displayable_result else "本次没有获得可展示的业务结果",
                            answer=customer_answer,
                            aiAnalysis=customer_answer,
                            sessionId=request.session_id,
                            thinkingEnabled=request.enable_thinking,
                            insights=[],
                            charts=ops_answer.charts,
                            processing_time_ms=int((time.time() - start_time) * 1000),
                        )
                        if request.session_id and trusted_session_user_id is not None:
                            from smartbi.services.chat_session_service import ChatSessionService
                            await ChatSessionService(pool).upsert(
                                session_id=request.session_id,
                                factory_id=factory_id_hdr,
                                parent_query=(effective_ops_query if inherited_context else query),
                                parent_answer_summary=customer_answer,
                                parent_template_code=structured_ops_code,
                                parent_upload_id=None,
                                user_id=trusted_session_user_id,
                            )
                        if not session_sensitive_restaurant:
                            _chat_cache_set(cache_key, response.dict())
                        return response

                if pool:
                    # Every restaurant tier now goes through the same structured
                    # QuerySpec + Answer Contract.  The old keyword shortcut was
                    # fast but skipped comparison, context, and completeness checks.
                    tiered = await _try_tiered_restaurant_intent(
                        effective_ops_query,
                        pool,
                        factory_id_hdr,
                        trusted_role,
                        session_key=trusted_restaurant_session_key,
                    )
                    if tiered:
                        answer_text = tiered["answer_text"]
                        contract_pass = bool(tiered.get("contract_pass", True))
                        response = GeneralAnalysisResponse(
                            success=(tiered["kind"] == "clarification" or contract_pass),
                            error=None if contract_pass else "本次结果未通过完整性校验",
                            answer=answer_text,
                            aiAnalysis=answer_text,
                            sessionId=request.session_id,
                            thinkingEnabled=request.enable_thinking,
                            insights=[],
                            charts=tiered.get("charts") or [],
                            processing_time_ms=int((time.time() - start_time) * 1000),
                        )
                        if (
                            tiered["kind"] == "answer"
                            and request.session_id
                            and trusted_session_user_id is not None
                        ):
                            from smartbi.services.chat_session_service import ChatSessionService
                            await ChatSessionService(pool).upsert(
                                session_id=request.session_id,
                                factory_id=factory_id_hdr,
                                parent_query=(effective_ops_query if inherited_context else query),
                                parent_answer_summary=answer_text,
                                parent_template_code=tiered.get("code"),
                                parent_upload_id=None,
                                user_id=trusted_session_user_id,
                            )
                        if tiered["kind"] == "answer" and not session_sensitive_restaurant:
                            _chat_cache_set(cache_key, response.dict())
                        return response

                # Fail-open compatibility for a parser/contract infrastructure
                # outage.  Normal restaurant requests return above.
                ops_code = reconcile_restaurant_ops_code(
                    effective_ops_query,
                    expected_ops_code,
                )
                if ops_code and pool:
                    fallback_store_mention = (
                        extract_store_mention(effective_ops_query)
                        if ops_code == "RESTAURANT_OPS_STORE_MARGIN" else None
                    )
                    fallback_factory_id = _restaurant_analysis_data_factory_id(
                        factory_id_hdr,
                        {"store_name": fallback_store_mention}
                        if fallback_store_mention else {},
                    )
                    ops_answer = await resolve_by_code(
                        ops_code, pool, fallback_factory_id, role=trusted_role,
                        query=effective_ops_query,
                        store_mention=fallback_store_mention,
                    )
                    if ops_answer:
                        customer_answer = sanitize_customer_ai_text(ops_answer.answer_text)
                        displayable_result = any(
                            key in (ops_answer.meta or {})
                            for key in ("missing_reference", "store_not_found",
                                        "store_mention_ambiguous")
                        ) or has_displayable_business_result(customer_answer)
                        response = GeneralAnalysisResponse(
                            success=displayable_result,
                            error=None if displayable_result else "本次没有获得可展示的业务结果",
                            answer=customer_answer,
                            aiAnalysis=customer_answer,
                            sessionId=request.session_id,
                            thinkingEnabled=request.enable_thinking,
                            insights=[],
                            charts=ops_answer.charts,
                            processing_time_ms=int((time.time() - start_time) * 1000),
                        )
                        if not session_sensitive_restaurant:
                            _chat_cache_set(cache_key, response.dict())
                        return response
            except Exception as ops_err:
                logger.warning(f"[general_analysis] restaurant ops fast path failed: {ops_err}")

            if any(k in query for k in ("评价", "口碑", "点评", "美团", "平台")):
                try:
                    from smartbi.config import get_pg_pool as _get_pool
                    from smartbi.gold.review_queries import (
                        review_summary, review_platform, review_complaints,
                    )
                    pool = await _get_pool()
                    if pool:
                        summary = await review_summary(pool, factory_id_hdr)
                        platforms = await review_platform(pool, factory_id_hdr)
                        complaints = await review_complaints(pool, factory_id_hdr, top_n=3)
                        if summary.get("connected"):
                            platform_rows = platforms.get("platforms") or []
                            low_count = int(summary.get("low_star_count") or 0)
                            total_reviews = int(summary.get("total_reviews") or 0)
                            avg_star = float(summary.get("avg_star") or 0)
                            worst_platform = min(
                                platform_rows,
                                key=lambda r: float(r.get("avg_star") or 99),
                            ) if platform_rows else None
                            complaint_rows = complaints.get("categories") or []
                            complaint_text = (
                                f"差评里最该先看的类型是{complaint_rows[0].get('category')}。"
                                if complaint_rows else "差评类型样本不多，先看低星原文。"
                            )
                            platform_text = (
                                f"{worst_platform.get('platform')}评分相对低一些，平均{float(worst_platform.get('avg_star') or 0):.2f}分。"
                                if worst_platform else "平台拆分数据暂时不完整。"
                            )
                            answer = (
                                f"平台评价总体不差，去重后共有 {total_reviews:,} 条评价，平均星级 {avg_star:.2f} 分；"
                                f"但低星评价有 {low_count:,} 条，不能只看平均分。"
                                f"{platform_text}{complaint_text}"
                                "建议：先把低星评价按门店和投诉类型分派到负责人，优先处理服务慢、出品不稳、环境类高频问题；"
                                "同时盯住评分相对低的平台，避免单个平台拖累整体口碑。"
                            )
                            charts = [{
                                "chartType": "bar",
                                "title": "平台评价量与评分",
                                "xAxis": {"data": [r.get("platform") for r in platform_rows[:6]]},
                                "series": [
                                    {"name": "评价量", "type": "bar",
                                     "data": [int(r.get("review_count") or 0) for r in platform_rows[:6]]},
                                    {"name": "平均星级", "type": "line",
                                     "data": [float(r.get("avg_star") or 0) for r in platform_rows[:6]]},
                                ],
                            }] if platform_rows else []
                            response = GeneralAnalysisResponse(
                                success=True,
                                answer=answer,
                                aiAnalysis=answer,
                                sessionId=request.session_id,
                                thinkingEnabled=request.enable_thinking,
                                insights=[],
                                charts=charts,
                                processing_time_ms=int((time.time() - start_time) * 1000),
                            )
                            _chat_cache_set(cache_key, response.dict())
                            return response
                except Exception as review_err:
                    logger.warning(f"[general_analysis] review fast path failed: {review_err}")

        # Upload-backed data is an explicit policy choice. Java callers that
        # provide their own context set allow_tenant_data_fallback=false.
        data = request.data
        if request.allow_tenant_data_fallback and not data and request.sheet_id:
            data = get_sheet_data(trusted_factory_id, request.sheet_id)

        if not data and request.allow_tenant_data_fallback:
            # Bug G fix (Apr 26 2026): fallback factory-scoped + largest non-empty upload.
            # Old tenant-null selection leaked across factories and let a 16-row file
            # beats 32K-row file. New: filter by factory_id from JWT, prefer largest upload
            # (most informative for general queries) and skip failed/empty uploads.
            try:
                from smartbi.config import get_pg_pool

                pool = await get_pg_pool()
                if pool:
                    async with pool.acquire() as conn:
                        upload_id = validated_upload_id
                        if not upload_id:
                            row = await conn.fetchrow(
                                """
                                SELECT id FROM smart_bi_pg_excel_uploads
                                WHERE factory_id = $1
                                  AND upload_status = 'COMPLETED'
                                  AND row_count > 0
                                ORDER BY row_count DESC, created_at DESC
                                LIMIT 1
                                """,
                                trusted_factory_id,
                            )
                            if row:
                                upload_id = row['id']
                        if upload_id:
                            rows = await conn.fetch(
                                """
                                SELECT d.row_data
                                FROM smart_bi_dynamic_data d
                                JOIN smart_bi_pg_excel_uploads u ON u.id = d.upload_id
                                WHERE d.upload_id = $1 AND u.factory_id = $2
                                LIMIT 200
                                """,
                                upload_id,
                                trusted_factory_id,
                            )
                            if rows:
                                import json
                                data = [json.loads(r['row_data']) if isinstance(r['row_data'], str) else r['row_data'] for r in rows]  # noqa: E501
                                logger.info(f"[general_analysis] Loaded {len(data)} rows from upload {upload_id}")
            except Exception as e:
                logger.warning(f"Failed to load upload data: {e}")

        if not data:
            # No SmartBI data — but if there's a message/query, use LLM to analyze it directly
            # (Java cost analysis sends formatted cost data as the message text)
            #
            # 死胡同修复 (May 31 2026): the old `len > 20` gate blocked SHORT
            # questions ("退款", "翻台率", "客户分层" …) from ever reaching the
            # LLM — they fell straight to the "暂无可分析的数据 请先上传Excel"
            # dead-end even when bug3's with-data LLM path could answer. The
            # >20 length was a proxy for "this is Java-injected cost text" but
            # it also discarded every genuine short user question. Relax to a
            # 2-char minimum so a real short query gets a real LLM answer; the
            # phrase-shortcut router catches the materialized-template cases
            # upstream, so by the time we reach here it's truly cache-miss.
            query = request.effective_query
            if query and len(query.strip()) >= 2:
                # Use LLM to analyze the text directly
                try:
                    insight_gen = InsightGenerator()
                    llm_result = await insight_gen.generate_text_analysis(query)
                    answer = llm_result if llm_result else "分析完成，暂无更多见解。"
                    response = GeneralAnalysisResponse(
                        success=True,
                        answer=answer,
                        aiAnalysis=answer,
                        sessionId=request.session_id,
                        thinkingEnabled=request.enable_thinking,
                        insights=[],
                        charts=[],
                        processing_time_ms=int((time.time() - start_time) * 1000)
                    )
                    _chat_cache_set(cache_key, response.dict())
                    # Distillation capture (training corpus): freshly-generated
                    # LLM answer (text-only, no-data branch). Cache-hit returns
                    # above this point so this is always a fresh teacher pair.
                    # No structured data available → data_context=None → quality=3.
                    if llm_result:
                        await _capture_qa_distillation(
                            query, answer, http_request,
                            data_context=None,
                            table_type=request.table_type,
                        )
                    return response
                except Exception as e:
                    logger.warning(f"Direct LLM analysis failed: {e}")
                    # Fall through to no-data response

            return GeneralAnalysisResponse(
                success=True,
                answer="暂无可分析的数据。请先上传 Excel 文件或在「智能数据分析」页面选择数据源后，再使用 AI 问答功能。",
                insights=[],
                charts=[],
                processing_time_ms=int((time.time() - start_time) * 1000)
            )

        import pandas as pd
        df = coerce_numeric_columns(pd.DataFrame(data))

        # Filter out index/sequence columns before ANY analysis (affects both insight text and charts)
        _idx_patterns = {'行次', '序号', '编号', '行号', '项目编号', 'index', 'no', 'no.', 'id', 'row_num', 'row_number', 'sn'}
        cols_to_drop = []
        for col in df.columns:
            lower = col.lower().strip()
            if lower in _idx_patterns:
                cols_to_drop.append(col)
            else:
                # Also detect sequential integer columns (1,2,3,...)
                try:
                    vals = pd.to_numeric(df[col].dropna().head(20), errors='coerce').dropna()
                    if len(vals) >= 3:
                        diffs = vals.diff().dropna()
                        if len(diffs) > 0 and all(d == 1 for d in diffs):
                            cols_to_drop.append(col)
                except Exception:
                    pass
        if cols_to_drop:
            logger.info(f"[general_analysis] Dropping index columns: {cols_to_drop}")
            df = df.drop(columns=cols_to_drop, errors='ignore')
            # Also clean the data list for InsightGenerator
            data = [{k: v for k, v in row.items() if k not in cols_to_drop} for row in data]

        # Use insight generator for analysis
        query = request.effective_query
        insight_gen = InsightGenerator()
        # Build analysis context from query + any extra context
        analysis_ctx = _build_analysis_ctx(query, request.context)
        insights_result = await insight_gen.generate_insights(
            data,
            analysis_context=analysis_ctx,
        )

        # Format response — prefer executive_summary from first insight over generic "summary"
        answer = insights_result.get("summary", "数据分析完成。")
        insights = insights_result.get("insights", [])
        if answer == "数据分析完成。" and insights:
            for ins in insights:
                if isinstance(ins, dict):
                    better = ins.get("executive_summary") or ins.get("text")
                    if better and len(better) > 10:
                        answer = better
                        break

        # Generate charts using ChartBuilder for proper ECharts options
        charts = []
        try:
            from services.chart_builder import ChartBuilder
            import re as _re
            builder = ChartBuilder()

            # --- Column name humanization (P1-3 fix) ---
            _COLUMN_NAME_MAP = {
                'actual_amount': '实际金额', 'budget_amount': '预算金额',
                'total_amount': '总金额', 'net_profit': '净利润',
                'gross_profit': '毛利润', 'revenue': '营收',
                'cost': '成本', 'expense': '费用', 'sales': '销售额',
                'quantity': '数量', 'price': '单价', 'margin': '利润率',
                'growth_rate': '增长率', 'total': '合计',
            }

            def _humanize_col(name: str) -> str:
                """Translate raw/English column names to readable Chinese labels."""
                if not name:
                    return name
                # Column_XX pattern → try to provide a descriptive fallback
                if _re.match(r'^[Cc]olumn[_\s]?\d+$', name):
                    idx = name.split('_')[-1] if '_' in name else name[-1]
                    return f"数据列{idx}"
                # Date pattern YYYY-MM-DD → M月
                m = _re.match(r'^(\d{4})-(\d{1,2})-\d{1,2}$', name)
                if m:
                    return f"{int(m.group(2))}月"
                # Compound date pattern: YYYY-MM-DD_suffix → M月suffix
                m = _re.match(r'^(\d{4})-(\d{1,2})-\d{1,2}[_\s](.+)$', name)
                if m:
                    suffix = m.group(3)
                    return f"{int(m.group(2))}月{suffix}"
                # English snake_case → Chinese lookup
                lower = name.lower().replace(' ', '_')
                if lower in _COLUMN_NAME_MAP:
                    return _COLUMN_NAME_MAP[lower]
                # underscores → spaces for readability (only pure ASCII)
                if '_' in name and all(c.isascii() for c in name):
                    return name.replace('_', ' ').title()
                return name

            # --- Filter out index/sequence columns (P1-2 fix) ---
            _INDEX_COL_PATTERNS = {'行次', '序号', '编号', '行号', '项目编号', 'index', 'no', 'no.', 'id', 'row_num', 'row_number', 'sn'}  # noqa: E501
            _ID_NAME_FRAGMENTS = ['订单号', '单号', '编码', '工号', '货号', '票号', '凭证号',
                                  'order_id', 'order_no', 'item_id', 'sku_id', 'batch_no']

            def _is_index_column(col_name: str, series) -> bool:
                """Detect if a column is an index/ID/sequence column (not meaningful for Y-axis)."""
                lower = col_name.lower().strip()
                if lower in _INDEX_COL_PATTERNS:
                    return True
                # Name contains ID-like fragments
                if any(frag in lower for frag in _ID_NAME_FRAGMENTS):
                    return True
                try:
                    import pandas as pd
                    vals = pd.to_numeric(series.dropna().head(20), errors='coerce').dropna()
                    if len(vals) >= 3:
                        # Sequential integers (1,2,3,...)
                        diffs = vals.diff().dropna()
                        if len(diffs) > 0 and all(d == 1 for d in diffs):
                            return True
                        # High-cardinality large integers (likely IDs, e.g., 20240101001)
                        if vals.nunique() == len(vals) and vals.min() > 1000 and all(v == int(v) for v in vals):
                            return True
                except Exception:
                    pass
                return False

            numeric_cols = df.select_dtypes(include=['number']).columns.tolist()
            non_numeric_cols = [c for c in df.columns if c not in numeric_cols]

            # Remove index/sequence columns from both lists
            numeric_cols = [c for c in numeric_cols if not _is_index_column(c, df[c])]
            non_numeric_cols = [c for c in non_numeric_cols if not _is_index_column(c, df[c])]

            # Deprioritize auto-generated Column_XX columns (from merged cells / missing headers)
            # Move them to end so meaningful columns are preferred for chart series
            _column_xx_pat = _re.compile(r'^[Cc]olumn[_\s]?\d+$')
            named_numeric = [c for c in numeric_cols if not _column_xx_pat.match(c)]
            unnamed_numeric = [c for c in numeric_cols if _column_xx_pat.match(c)]
            # Use named columns first; only include up to 2 unnamed columns as fallback
            numeric_cols = named_numeric + unnamed_numeric[:2] if named_numeric else unnamed_numeric[:5]

            # Pick a label field: prefer columns with non-numeric text values
            label_field = None
            for col in non_numeric_cols:
                sample = df[col].dropna().head(10).astype(str)
                has_text = any(len(v) > 1 and not v.replace('.', '').replace('-', '').isdigit() for v in sample)
                if has_text:
                    label_field = col
                    break
            if not label_field and non_numeric_cols:
                label_field = non_numeric_cols[0]

            def _humanize_echart_option(echart_option: dict) -> dict:
                """Humanize column names in ECharts option (legend, series names, axis labels)."""
                if not echart_option:
                    return echart_option
                opt = dict(echart_option)
                # Humanize legend data
                if 'legend' in opt and isinstance(opt['legend'], dict):
                    leg_data = opt['legend'].get('data', [])
                    if isinstance(leg_data, list):
                        opt['legend'] = {**opt['legend'], 'data': [_humanize_col(str(d)) for d in leg_data]}
                # Humanize series names
                if 'series' in opt and isinstance(opt['series'], list):
                    new_series = []
                    for s in opt['series']:
                        if isinstance(s, dict) and 'name' in s:
                            new_series.append({**s, 'name': _humanize_col(str(s['name']))})
                        else:
                            new_series.append(s)
                    opt['series'] = new_series
                # Humanize title text
                if 'title' in opt and isinstance(opt['title'], dict):
                    t = opt['title'].get('text', '')
                    if t:
                        # Replace raw column patterns in title
                        for raw_col in list(df.columns):
                            h = _humanize_col(raw_col)
                            if h != raw_col and raw_col in t:
                                t = t.replace(raw_col, h)
                        opt['title'] = {**opt['title'], 'text': t}
                return opt

            def _extract_echart_option(chart_result: dict, chart_type: str, title: str):
                """Extract ECharts option from ChartBuilder result and wrap for frontend"""
                if not chart_result or not chart_result.get("success"):
                    return None
                echart_option = chart_result.get("config", {})
                if not echart_option:
                    return None
                # Humanize column names in the ECharts config
                echart_option = _humanize_echart_option(echart_option)
                return {
                    "type": chart_type,
                    "title": title,
                    "option": _sanitize_for_json(echart_option)
                }

            # Limit data to first 50 rows for chart building (avoid oversized charts)
            chart_data = data[:50] if len(data) > 50 else data

            if "趋势" in query or "变化" in query:
                if numeric_cols:
                    y_cols = numeric_cols[:3]
                    h_names = '、'.join(_humanize_col(c) for c in y_cols[:2])
                    chart_result = builder.build(
                        "line", chart_data, x_field=label_field, y_fields=y_cols,
                        title=f"{h_names}趋势分析"
                    )
                    chart_entry = _extract_echart_option(chart_result, "line", f"{h_names}趋势")
                    if chart_entry:
                        charts.append(chart_entry)

            elif "对比" in query or "比较" in query or "排名" in query:
                if numeric_cols and label_field:
                    y_cols = numeric_cols[:2]
                    h_names = '、'.join(_humanize_col(c) for c in y_cols[:2])
                    chart_result = builder.build(
                        "bar", chart_data, x_field=label_field, y_fields=y_cols,
                        title=f"{h_names}对比分析"
                    )
                    chart_entry = _extract_echart_option(chart_result, "bar", f"{h_names}对比")
                    if chart_entry:
                        charts.append(chart_entry)

            elif "占比" in query or "构成" in query or "分布" in query:
                if numeric_cols and label_field:
                    h_name = _humanize_col(numeric_cols[0])
                    chart_result = builder.build(
                        "pie", chart_data, x_field=label_field, y_fields=[numeric_cols[0]],
                        title=f"{h_name}占比分析"
                    )
                    chart_entry = _extract_echart_option(chart_result, "pie", f"{h_name}占比")
                    if chart_entry:
                        charts.append(chart_entry)

            # Default: if no specific chart type matched, auto-recommend a bar chart
            if not charts and numeric_cols and label_field:
                y_cols = numeric_cols[:3]
                chart_result = builder.build(
                    "bar", chart_data, x_field=label_field, y_fields=y_cols,
                    title="数据概览"
                )
                chart_entry = _extract_echart_option(chart_result, "bar", "数据概览")
                if chart_entry:
                    charts.append(chart_entry)
        except Exception as chart_err:
            logger.warning(f"Chart generation failed in general_analysis: {chart_err}")

        # Sanitize insights to remove NaN/Infinity before JSON serialization
        insights = _sanitize_for_json(insights)

        response = GeneralAnalysisResponse(
            success=True,
            answer=answer,
            aiAnalysis=answer,
            sessionId=request.session_id,
            thinkingEnabled=request.enable_thinking,
            insights=insights,
            charts=charts,
            processing_time_ms=int((time.time() - start_time) * 1000)
        )

        # Cache successful result
        _chat_cache_set(cache_key, response.dict())

        # Distillation capture (training corpus): freshly-generated LLM answer
        # (with-data insights branch). Cache-hit returns near the top of this
        # function, so reaching here means a fresh teacher pair.
        # Build data_context summary so the corpus pair is self-contained:
        # future model learns to answer FROM data, not hallucinate numbers.
        _qa_data_ctx = _build_qa_data_context(data, insights_result)
        await _capture_qa_distillation(
            query, answer, http_request,
            data_context=_qa_data_ctx,
            table_type=request.table_type,
        )

        return response

    except Exception as e:
        logger.error(f"General analysis failed: {e}", exc_info=True)
        return GeneralAnalysisResponse(
            success=False,
            error="AI对话处理失败，请稍后重试",
            processing_time_ms=int((time.time() - start_time) * 1000)
        )


@router.post("/general-analysis-stream")
async def general_analysis_stream(request: GeneralAnalysisRequest, http_request: Request):
    """
    SSE streaming version of general_analysis.

    Sends events:
      - {"event": "status", "data": "..."} — progress updates
      - {"event": "chunk", "data": "..."} — LLM text chunks (stream as generated)
      - {"event": "charts", "data": [...]} — chart configs (when ready)
      - {"event": "done", "data": {...}} — final summary with full answer
      - {"event": "error", "data": "..."} — on failure
    """

    # Run security checks before constructing StreamingResponse so failures are
    # real HTTP 403/404 responses, never HTTP 200 followed by an SSE error.
    trusted_factory_id = _require_trusted_factory_id(http_request)
    trusted_role, _, trusted_session_user_id, _ = _trusted_chat_identity(http_request)
    trusted_restaurant_session_key = _trusted_restaurant_session_key(
        trusted_factory_id,
        trusted_session_user_id,
        request.session_id,
    )
    validated_upload_id = await _require_owned_upload_id(
        request.sheet_id,
        trusted_factory_id,
    )

    def _sse_event(event: str, data) -> str:
        """Format a single SSE event. Always JSON-encodes data for consistent frontend parsing."""
        payload = _json.dumps(data, ensure_ascii=False, default=str)
        return f"event: {event}\ndata: {payload}\n\n"

    async def event_stream() -> AsyncGenerator[str, None]:
        start_time = time.time()
        # Apr 24 2026 — C-quality.md C-rec 12 + Direction 1: extract N /
        # frequency / role intent signals once per request, log for
        # observability, and pass to format_cached_as_sse so cached
        # template results honor user's requested top-N (re-slice) and
        # annotate role mismatches. Pure regex, <1ms; safe to run always.
        intent_signals: Dict[str, Any] = {}
        try:
            from smartbi.services.intent.query_intent_extractor import extract_intent
            intent_signals = dict(extract_intent(request.effective_query or ""))
            if intent_signals:
                _parts = []
                if 'n' in intent_signals:
                    _parts.append(f"N={intent_signals['n']}")
                if 'role' in intent_signals:
                    _parts.append(f"role={intent_signals['role']}")
                if 'frequency' in intent_signals:
                    _parts.append(f"freq={intent_signals['frequency']}")
                logger.info(f"[intent] extracted {' '.join(_parts)} from query")
        except Exception as e:
            logger.warning(f"[intent] extraction failed (non-fatal): {e}")
            intent_signals = {}

        # Apr 26 2026 v2 conversation memory: Phase 0 session lookup. If FE sent
        # session_id, fetch parent_query + parent_answer_summary so this turn can
        # reference the previous answer (covers "为什么"/"怎么办" follow-ups
        # measured at 2.98/3.15 in S3 audit, vs main 3.94). Tenant isolated by
        # factory_id. Backward compat: no session_id → standalone path.
        chat_session_parent: Optional[Dict[str, Any]] = None
        _session_factory_id = trusted_factory_id
        # H2 (Apr 26 2026): also extract user_id for session binding so same
        # factory + different user on shared device → no context leak.
        _session_user_id = trusted_session_user_id
        _session_can_persist = bool(
            request.session_id
            and _session_factory_id
            and _session_user_id is not None
        )

        if _session_can_persist:
            try:
                from smartbi.services.chat_session_service import ChatSessionService
                from smartbi.config import get_pg_pool as _get_pool_session
                _session_pool = await _get_pool_session()
                if _session_pool is not None:
                    _svc = ChatSessionService(_session_pool)
                    chat_session_parent = await _svc.lookup(
                        request.session_id, _session_factory_id,
                        user_id=_session_user_id,
                    )
                    if chat_session_parent:
                        logger.info(
                            f"[chat-session] HIT session={request.session_id[:8]}... "
                            f"turn={chat_session_parent.get('turn_count')} "
                            f"parent_template={chat_session_parent.get('parent_template_code')}"
                        )
            except Exception as e:
                logger.warning(f"[chat-session] phase 0 lookup failed (non-fatal): {e}")

        try:
            # ── Jun 2026 WS6 routing fix (#5 趋势不出图) ───────────────────────
            # SURGICAL pre-check: trend / 同比环比 questions MUST reach the gold
            # trend resolver (trend_bundle, full 2025+2026 history → monthly
            # trend + peak/trough + MoM/YoY + line chart) BEFORE the P2 synthesis
            # router below gets a chance. The dashboard 「同比环比分析」 chip sends
            # "进行同比和环比分析，识别增长和下降趋势"; if synthesis serves it first
            # it answers from the FactBook (which lacks monthly time-series) and
            # admits "无法计算同比环比/趋势" — the gold TREND resolver never fires
            # (verified on prod). We divert ONLY the TREND ops code here; every
            # other ops code stays in its normal position in the gold-ops block
            # after synthesis (minimal blast radius — only trend/同比/环比 queries
            # are re-ordered, all other routing is unchanged). A genuine
            # "综合分析评价和经营" question does NOT match the TREND ops code
            # (it has no 同比/环比/趋势/增长/下降/月度变化/走势 keyword) so it still
            # reaches the synthesis engine untouched.
            try:
                user_q = (request.effective_query or "").strip()
                factory_id_hdr = trusted_factory_id
                if user_q and factory_id_hdr:
                    from smartbi.gold.restaurant_ops_router import (
                        is_supported_restaurant_ops_code as _is_supported_ops_trend,
                        reconcile_restaurant_ops_code as _reconcile_ops_trend,
                        resolve_by_code as _resolve_ops_trend,
                    )
                    from smartbi.config import get_pg_pool as _get_pool_trend
                    _expected_trend_code = (
                        request.expected_intent
                        if request.table_type == "restaurant_ops"
                        and _is_supported_ops_trend(request.expected_intent)
                        else None
                    )
                    _t1_trend_code = _reconcile_ops_trend(user_q, _expected_trend_code)
                    if _t1_trend_code == "RESTAURANT_OPS_TREND_ANALYSIS":
                        pool_trend = await _get_pool_trend()
                        if pool_trend:
                            trend_answer = await _resolve_ops_trend(
                                "RESTAURANT_OPS_TREND_ANALYSIS",
                                pool_trend, factory_id_hdr, role=trusted_role,
                            )
                            if trend_answer:
                                customer_answer = sanitize_customer_ai_text(trend_answer.answer_text)
                                yield _sse_event("status", "正在计算营业趋势...")
                                chunk_size = 40
                                for i in range(0, len(customer_answer), chunk_size):
                                    yield _sse_event("chunk", customer_answer[i:i + chunk_size])
                                if trend_answer.charts:
                                    yield _sse_event("charts", trend_answer.charts)
                                wall_ms = int((time.time() - start_time) * 1000)
                                yield _sse_event("done", {
                                    "success": True,
                                    "answer": customer_answer,
                                    "charts": trend_answer.charts,
                                    "kpis": trend_answer.kpis,
                                    "source": "restaurant_ops_gold",
                                    "template_code": "RESTAURANT_OPS_TREND_ANALYSIS",
                                    "processingTimeMs": wall_ms,
                                    "log_id": None,
                                })
                                if _session_can_persist:
                                    try:
                                        from smartbi.services.chat_session_service import (
                                            ChatSessionService as _CSS_TREND,
                                        )
                                        from smartbi.api.materialized_analytics import (
                                            _spawn_bg as _spawn_trend,
                                        )
                                        _spawn_trend(_CSS_TREND(pool_trend).upsert(
                                            session_id=request.session_id,
                                            factory_id=_session_factory_id,
                                            parent_query=user_q,
                                            parent_answer_summary=customer_answer,
                                            parent_template_code="RESTAURANT_OPS_TREND_ANALYSIS",
                                            parent_upload_id=None,
                                            user_id=_session_user_id,
                                        ))
                                    except Exception as _e:
                                        logger.warning(f"[chat-session] writeback (gold trend) failed: {_e}")
                                logger.info(
                                    f"[stream] served via gold trend (pre-synthesis): "
                                    f"template=RESTAURANT_OPS_TREND_ANALYSIS, wall={wall_ms}ms"
                                )
                                return  # early exit — gold trend served the answer
                    elif not _t1_trend_code:
                        # 2026-07-07 tiered intent (T2 vector / T3 LLM), kept
                        # SURGICAL to this pre-check's original scope: only
                        # escalate here when the resolved intent is
                        # specifically TREND_ANALYSIS (a paraphrase of
                        # 同比/环比/趋势 the frozen T1 keyword table missed).
                        # Any OTHER ops code detected by T2/T3 is deliberately
                        # NOT served here -- it falls through to the
                        # synthesis router below exactly as before, and gets
                        # picked up by the general tiered block in the
                        # gold-ops section further down. This preserves the
                        # "minimal blast radius" guarantee of this pre-check.
                        pool_trend = await _get_pool_trend()
                        if pool_trend:
                            from smartbi.gold.restaurant_intent import (
                                parse_restaurant_query as _peek_trend_spec,
                            )
                            _trend_spec = await _peek_trend_spec(
                                user_q, pool_trend, factory_id=factory_id_hdr,
                            )
                            if (
                                _trend_spec is not None
                                and _trend_spec.intent == "RESTAURANT_OPS_TREND_ANALYSIS"
                                and not _trend_spec.clarification_needed
                            ):
                                tiered_trend = await _try_tiered_restaurant_intent(
                                    user_q, pool_trend, factory_id_hdr, trusted_role,
                                    session_key=trusted_restaurant_session_key,
                                )
                                if tiered_trend and tiered_trend["kind"] == "answer":
                                    yield _sse_event(
                                        "status",
                                        f"命中餐饮运营模板:{tiered_trend.get('title') or '营收趋势分析'}",
                                    )
                                    chunk_size = 40
                                    trend_text = tiered_trend["answer_text"]
                                    for i in range(0, len(trend_text), chunk_size):
                                        yield _sse_event("chunk", trend_text[i:i + chunk_size])
                                    if tiered_trend.get("charts"):
                                        yield _sse_event("charts", tiered_trend["charts"])
                                    wall_ms = int((time.time() - start_time) * 1000)
                                    yield _sse_event("done", {
                                        "success": True,
                                        "answer": trend_text,
                                        "charts": tiered_trend.get("charts") or [],
                                        "kpis": tiered_trend.get("kpis") or [],
                                        "source": "restaurant_ops_gold",
                                        "template_code": "RESTAURANT_OPS_TREND_ANALYSIS",
                                        "processingTimeMs": wall_ms,
                                        "log_id": None,
                                    })
                                    if _session_can_persist:
                                        try:
                                            from smartbi.services.chat_session_service import (
                                                ChatSessionService as _CSS_TREND2,
                                            )
                                            from smartbi.api.materialized_analytics import (
                                                _spawn_bg as _spawn_trend2,
                                            )
                                            _spawn_trend2(_CSS_TREND2(pool_trend).upsert(
                                                session_id=request.session_id,
                                                factory_id=_session_factory_id,
                                                parent_query=user_q,
                                                parent_answer_summary=trend_text,
                                                parent_template_code="RESTAURANT_OPS_TREND_ANALYSIS",
                                                parent_upload_id=None,
                                                user_id=_session_user_id,
                                            ))
                                        except Exception as _e2:
                                            logger.warning(
                                                f"[chat-session] writeback (gold trend tiered) failed: {_e2}"
                                            )
                                    logger.info(
                                        f"[stream] served via gold trend tiered (pre-synthesis): "
                                        f"tier={_trend_spec.source_tier}, wall={wall_ms}ms"
                                    )
                                    return  # early exit — tiered gold trend served the answer
            except Exception as e:
                logger.warning(f"[stream] gold trend pre-check failed, falling through: {e}")

            # P2 综合分析 (multi-dim synthesis) router — runs FIRST, BEFORE the
            # single-dataset gold-ops + xlsx template routers, so a free-form
            # "综合分析评价和经营" / "VIP和菜品门店的关系" question is served by the
            # synthesis engine (review + finance/sales → FactBook → grounded LLM +
            # charts) instead of falling through to a single-dataset template.
            # match_comprehensive_synthesis is confident-only: single-dim queries
            # do NOT match here and fall through to the existing accurate routes
            # (per feedback_intent_gate_must_cover_all_execution_paths: this is the
            # Python path B mirror of the Java COMPREHENSIVE_SYNTHESIS intent).
            try:
                user_q = (request.effective_query or "").strip()
                factory_id_hdr = trusted_factory_id
                if user_q and factory_id_hdr:
                    from smartbi.agent.synthesis_router import match_comprehensive_synthesis
                    if match_comprehensive_synthesis(user_q):
                        from smartbi.agent.synthesis_engine import (
                            ComprehensiveSynthesisEngine,
                        )
                        from smartbi.api.synthesis import _resolve_window
                        from smartbi.config import get_pg_pool as _get_pool_syn
                        pool_syn = await _get_pool_syn()
                        if pool_syn:
                            window = await _resolve_window(pool_syn, factory_id_hdr, None, None, question=user_q)
                            engine = ComprehensiveSynthesisEngine(pool_syn)
                            # P2 multi-turn memory (2026-07-09): pass the
                            # already-looked-up (Phase 0, top of this handler)
                            # bounded turns_history so a follow-up like "展开
                            # 第三点"/"那家店呢"/"它呢" can resolve WHAT it
                            # refers to. 🔒 numbers still come solely from this
                            # turn's FactBook inside synthesize() — history is
                            # never a number source (see synthesis_engine.py
                            # docstrings). chat_session_parent is None when no
                            # session_id/no hit → conversation_history=None →
                            # behavior byte-identical to before this change.
                            _syn_history = (
                                chat_session_parent.get("turns_history")
                                if chat_session_parent else None
                            )
                            syn = await engine.synthesize(
                                factory_id_hdr, user_q, window,
                                conversation_history=_syn_history,
                            )
                            yield _sse_event("status", "综合分析：评价+经营多维")
                            answer_syn = syn.answer or ""
                            chunk_size = 40
                            for i in range(0, len(answer_syn), chunk_size):
                                yield _sse_event("chunk", answer_syn[i:i + chunk_size])
                            if syn.charts:
                                yield _sse_event("charts", syn.charts)
                            wall_ms = int((time.time() - start_time) * 1000)
                            yield _sse_event("done", {
                                "success": True,
                                "answer": answer_syn,
                                "charts": syn.charts,
                                "source": "comprehensive_synthesis",
                                "plan": syn.plan,
                                "fact_check": syn.fact_check,
                                "processingTimeMs": wall_ms,
                                "log_id": None,
                            })
                            # v2/v3 conversation memory writeback: so a NEXT
                            # follow-up (chained after this synthesis turn)
                            # also has this turn in its turns_history. Mirrors
                            # the writeback pattern used by the gold ops /
                            # gold trend routes above.
                            if _session_can_persist:
                                try:
                                    from smartbi.services.chat_session_service import (
                                        ChatSessionService as _CSS_SYN,
                                    )
                                    from smartbi.api.materialized_analytics import _spawn_bg as _spawn_syn
                                    _spawn_syn(_CSS_SYN(pool_syn).upsert(
                                        session_id=request.session_id,
                                        factory_id=_session_factory_id,
                                        parent_query=user_q,
                                        parent_answer_summary=answer_syn,
                                        parent_template_code="COMPREHENSIVE_SYNTHESIS",
                                        parent_upload_id=None,
                                        user_id=_session_user_id,
                                    ))
                                except Exception as _e:
                                    logger.warning(f"[chat-session] writeback (synthesis) failed: {_e}")
                            logger.info(
                                f"[stream] served via synthesis engine: source={syn.source}, "
                                f"wall={wall_ms}ms"
                            )
                            return  # early exit — synthesis served the answer
            except Exception as e:
                logger.warning(f"[stream] synthesis router failed, falling through: {e}")

            # Apr 24 2026 Plan C Phase 4: Restaurant daily-ops Gold router (runs
            # BEFORE xlsx template router). Routes queries about 损耗/盘点/领料/
            # 配方成本 to pre-aggregated Gold tables. No upload_id needed.
            try:
                user_q = (request.effective_query or "").strip()
                factory_id_hdr = trusted_factory_id
                if user_q and factory_id_hdr:
                    from smartbi.gold.restaurant_ops_router import (
                        extract_store_mention as _extract_store_mention_stream,
                        is_supported_restaurant_ops_code,
                        reconcile_restaurant_ops_code,
                        resolve_by_code,
                    )
                    from smartbi.config import get_pg_pool as _get_pool
                    expected_ops_code = (
                        request.expected_intent
                        if request.table_type == "restaurant_ops"
                        and is_supported_restaurant_ops_code(request.expected_intent)
                        else None
                    )
                    pool = await _get_pool()
                    if pool:
                        from smartbi.gold.restaurant_intent import contextualize_restaurant_followup
                        effective_user_q, inherited_context = contextualize_restaurant_followup(
                            user_q, chat_session_parent,
                        )
                        ops_code = reconcile_restaurant_ops_code(
                            effective_user_q,
                            expected_ops_code,
                        )
                        tiered_ops = (
                            await _try_tiered_restaurant_intent(
                                effective_user_q, pool, factory_id_hdr, trusted_role,
                                session_key=trusted_restaurant_session_key,
                            )
                            if ops_code is None or is_supported_restaurant_ops_code(ops_code)
                            else None
                        )

                        # A resolver/parsing infrastructure outage still gets the
                        # prior deterministic fallback; normal requests use the
                        # contract-checked tiered result above.
                        if tiered_ops is None:
                            stream_fallback_mention = (
                                _extract_store_mention_stream(effective_user_q)
                                if ops_code in ("RESTAURANT_OPS_STORE_MARGIN",
                                                "RESTAURANT_OPS_GROSS_MARGIN")
                                else None
                            )
                            stream_fallback_code = ops_code
                            stream_fallback_factory = factory_id_hdr
                            if stream_fallback_mention:
                                stream_fallback_code = "RESTAURANT_OPS_STORE_MARGIN"
                                stream_fallback_factory = _restaurant_analysis_data_factory_id(
                                    factory_id_hdr,
                                    {"store_name": stream_fallback_mention},
                                )
                            ops_answer = (
                                await resolve_by_code(
                                    stream_fallback_code, pool, stream_fallback_factory,
                                    role=trusted_role, query=effective_user_q,
                                    store_mention=stream_fallback_mention,
                                )
                                if ops_code else None
                            )
                            if ops_answer:
                                fallback_answer = sanitize_customer_ai_text(ops_answer.answer_text)
                                fallback_guard = any(
                                    key in (ops_answer.meta or {})
                                    for key in ("missing_reference", "store_not_found",
                                                "store_mention_ambiguous")
                                )
                                fallback_contract_pass = (
                                    fallback_guard
                                    or has_displayable_business_result(fallback_answer)
                                )
                                tiered_ops = {
                                    "kind": "clarification" if fallback_guard else "answer",
                                    "answer_text": fallback_answer,
                                    "charts": ops_answer.charts,
                                    "kpis": ops_answer.kpis,
                                    "code": ops_code,
                                    "contract_pass": fallback_contract_pass,
                                }

                        if tiered_ops:
                            yield _sse_event("status", "正在整理餐饮经营数据...")
                            answer_text_ops = tiered_ops["answer_text"]
                            for i in range(0, len(answer_text_ops), 40):
                                yield _sse_event("chunk", answer_text_ops[i:i + 40])
                            charts_ops = tiered_ops.get("charts") or []
                            if charts_ops:
                                yield _sse_event("charts", charts_ops)
                            wall_ms = int((time.time() - start_time) * 1000)
                            contract_pass = bool(tiered_ops.get("contract_pass", True))
                            yield _sse_event("done", {
                                "success": tiered_ops["kind"] == "clarification" or contract_pass,
                                "answer": answer_text_ops,
                                "charts": charts_ops,
                                "kpis": tiered_ops.get("kpis") or [],
                                "source": "restaurant_ops_gold",
                                "template_code": tiered_ops.get("code"),
                                "contractPass": contract_pass,
                                "processingTimeMs": wall_ms,
                                "log_id": None,
                            })
                            if tiered_ops["kind"] == "answer" and _session_can_persist:
                                try:
                                    from smartbi.services.chat_session_service import ChatSessionService as _CSS_OPS
                                    from smartbi.api.materialized_analytics import _spawn_bg as _spawn_ops
                                    _spawn_ops(_CSS_OPS(pool).upsert(
                                        session_id=request.session_id,
                                        factory_id=_session_factory_id,
                                        parent_query=effective_user_q if inherited_context else user_q,
                                        parent_answer_summary=answer_text_ops,
                                        parent_template_code=tiered_ops.get("code") or "RESTAURANT_OPS_UNKNOWN",
                                        parent_upload_id=None,
                                        user_id=_session_user_id,
                                    ))
                                except Exception as _e:
                                    logger.warning(f"[chat-session] writeback (gold ops) failed: {_e}")
                            logger.info(
                                f"[stream] served via contract-checked gold ops: kind={tiered_ops['kind']}, "
                                f"code={tiered_ops.get('code')}, wall={wall_ms}ms"
                            )
                            return
            except Exception as e:
                logger.warning(f"[stream] gold ops router failed, falling through: {e}")

            # W2.2: Try template router first — if user query matches a known analysis,
            # stream cached result (fast, deterministic) instead of invoking LLM.
            try:
                upload_id = (
                    validated_upload_id
                    if request.allow_tenant_data_fallback
                    else None
                )
                user_q = (request.effective_query or "").strip()
                # Bug G phase 2 (Apr 26 2026): template-aware upload selection.
                # Phase 1 picked largest upload — broke for qhj where 32K 卡详情
                # (no menu data) was largest but 6K 营业概况月报 (with menu data)
                # was the right answer. Phase 2 routes by template availability:
                #   query → match_template_hybrid → template_code →
                #   SQL "which factory upload has this template materialized?" →
                #   pick that upload (semantic match).
                # If no template matches OR no upload has it, fall back to phase 1
                # (largest non-empty).
                from smartbi.services.materialized_analytics.query_router import (
                    match_template_hybrid, format_cached_as_sse
                )
                from smartbi.config import get_pg_pool
                pool = await get_pg_pool()
                matched_code = None  # captured here so cache-serve block can reuse
                if (
                    request.allow_tenant_data_fallback
                    and not upload_id
                    and user_q
                    and pool is not None
                ):
                    factory_id_for_select = trusted_factory_id
                    if factory_id_for_select:
                        try:
                            # Step 1: query → template_code
                            matched_code = await match_template_hybrid(user_q, pool)
                            async with pool.acquire() as _conn:
                                # Step 2: template_code → upload_id (semantic match)
                                if matched_code:
                                    # Phase 3 (Apr 26 2026): for top_n_by_dim, prefer
                                    # upload whose all_dims contains a key matching
                                    # the user's intended dim (e.g. "卖得最好的菜" →
                                    # need upload with 商品/菜品 in all_dims, not just
                                    # any upload with top_n_by_dim materialized).
                                    upload_id_dim_aware = None
                                    if matched_code == 'top_n_by_dim':
                                        try:
                                            from smartbi.services.materialized_analytics.query_router import (
                                                pick_dim_from_query as _pick_dim
                                            )
                                            _dim_rows = await _conn.fetch(
                                                """
                                                SELECT a.upload_id, u.row_count,
                                                       jsonb_object_keys(
                                                         a.analysis_result -> 'data' -> 'all_dims'
                                                       ) AS dim_key
                                                FROM smart_bi_pg_analysis_results a
                                                JOIN smart_bi_pg_excel_uploads u
                                                  ON u.id = a.upload_id
                                                WHERE a.factory_id = $1
                                                  AND u.factory_id = $1
                                                  AND a.template_code = 'top_n_by_dim'
                                                  AND u.upload_status = 'COMPLETED'
                                                  AND u.row_count > 0
                                                """,
                                                factory_id_for_select,
                                            )
                                            # Group by upload_id with available dims.
                                            uploads_dims: Dict[int, List[str]] = {}
                                            uploads_rc: Dict[int, int] = {}
                                            for r in _dim_rows:
                                                uid = r['upload_id']
                                                uploads_dims.setdefault(uid, []).append(r['dim_key'])
                                                uploads_rc[uid] = r['row_count']
                                            # Score each upload: does its dim list contain
                                            # something matching user intent?
                                            best_uid = None
                                            best_rc = 0
                                            for uid, dims in uploads_dims.items():
                                                picked = _pick_dim(user_q, dims)
                                                if picked and uploads_rc[uid] > best_rc:
                                                    best_uid = uid
                                                    best_rc = uploads_rc[uid]
                                            if best_uid is not None:
                                                upload_id_dim_aware = best_uid
                                                logger.info(
                                                    f"[stream] dim-aware top_n_by_dim upload "
                                                    f"{best_uid} (factory={factory_id_for_select})"
                                                )
                                        except Exception as _e:
                                            logger.warning(f"[stream] dim-aware select failed: {_e}")

                                    if upload_id_dim_aware is not None:
                                        upload_id = upload_id_dim_aware
                                    elif matched_code == 'top_n_by_dim':
                                        # S4 audit P0 fix (Apr 26 2026): when
                                        # dim-aware search finds no upload with
                                        # a dim matching user intent, AND user
                                        # has clear domain hint, force LLM
                                        # (with v2 parent context) instead of
                                        # falling to "largest upload" with
                                        # mismatched dim. xmx-fu-29-2 case:
                                        # query 'store domain' but xmx data has
                                        # no store dim → fallback gave 订单类型
                                        # Top 2 instead of forcing LLM.
                                        try:
                                            from smartbi.services.cache_intent_classifier import (
                                                classify_query_domain as _cdom,
                                            )
                                            if _cdom(user_q):
                                                logger.info(
                                                    f"[stream] top_n_by_dim dim-aware miss + "
                                                    f"user has domain hint → forcing LLM "
                                                    f"(query={user_q[:30]!r})"
                                                )
                                                matched_code = None  # fall through to LLM
                                        except Exception:
                                            pass
                                    else:
                                        _row = await _conn.fetchrow(
                                            """
                                            SELECT a.upload_id
                                            FROM smart_bi_pg_analysis_results a
                                            JOIN smart_bi_pg_excel_uploads u
                                              ON u.id = a.upload_id
                                            WHERE a.factory_id = $1
                                              AND u.factory_id = $1
                                              AND a.template_code = $2
                                              AND u.upload_status = 'COMPLETED'
                                              AND u.row_count > 0
                                            ORDER BY u.row_count DESC, u.created_at DESC
                                            LIMIT 1
                                            """,
                                            factory_id_for_select, matched_code,
                                        )
                                        if _row:
                                            upload_id = _row['upload_id']
                                            logger.info(
                                                f"[stream] template-matched upload {upload_id} "
                                                f"(factory={factory_id_for_select}, "
                                                f"template={matched_code})"
                                            )
                                # Step 3 (fallback): largest non-empty when no
                                # template match OR matched template has no upload.
                                #
                                # 措施①-bug3 (May 31 2026, LLM-parity critical):
                                # the old fallback picked the absolute largest
                                # upload by row_count — but the largest upload
                                # may have 0 materialized templates / 0 field
                                # definitions (e.g. a raw dump that never finished
                                # analysis). The LLM then gets an upload with no
                                # usable schema → "暂无数据" dead-end, even though
                                # a smaller-but-analyzed upload could answer.
                                #
                                # New: prefer the largest upload that has at least
                                # one materialized template OR field_definitions,
                                # so the LLM lands on data it can actually reason
                                # over (= pure-LLM quality, not "暂无数据").
                                # Risk#2: conservative — fall back to the old
                                # "largest non-empty" if NO upload has any
                                # template/fields, and log WHY either way.
                                if not upload_id:
                                    _row = await _conn.fetchrow(
                                        """
                                        SELECT u.id FROM smart_bi_pg_excel_uploads u
                                        WHERE u.factory_id = $1
                                          AND u.upload_status = 'COMPLETED'
                                          AND u.row_count > 0
                                          AND (
                                            EXISTS (
                                              SELECT 1 FROM smart_bi_pg_analysis_results a
                                              WHERE a.upload_id = u.id
                                                AND a.template_code IS NOT NULL
                                            )
                                            OR EXISTS (
                                              SELECT 1 FROM smart_bi_pg_field_definitions fd
                                              WHERE fd.upload_id = u.id
                                            )
                                          )
                                        ORDER BY u.row_count DESC, u.created_at DESC
                                        LIMIT 1
                                        """,
                                        factory_id_for_select,
                                    )
                                    if _row:
                                        upload_id = _row['id']
                                        logger.info(
                                            f"[stream] phase-1 fallback upload {upload_id} "
                                            f"(factory={factory_id_for_select}, "
                                            f"no template match; picked largest "
                                            f"upload WITH templates/field_definitions)"
                                        )
                                    else:
                                        # Risk#2 conservative fallback: no upload
                                        # has any template/fields — take the
                                        # absolute largest non-empty (old behavior)
                                        # so we never regress to picking nothing.
                                        _row = await _conn.fetchrow(
                                            """
                                            SELECT id FROM smart_bi_pg_excel_uploads
                                            WHERE factory_id = $1
                                              AND upload_status = 'COMPLETED'
                                              AND row_count > 0
                                            ORDER BY row_count DESC, created_at DESC
                                            LIMIT 1
                                            """,
                                            factory_id_for_select,
                                        )
                                        if _row:
                                            upload_id = _row['id']
                                            logger.warning(
                                                f"[stream] phase-1 fallback upload {upload_id} "
                                                f"(factory={factory_id_for_select}, "
                                                f"no template match AND no upload has "
                                                f"templates/field_definitions; "
                                                f"picked absolute largest non-empty)"
                                            )
                        except Exception as _e:
                            logger.warning(f"[stream] auto-select upload failed: {_e}")
                if upload_id and user_q:
                    # Avoid double-call to match_template_hybrid if phase 2 already
                    # resolved it. Still call here when sheet_id was explicit
                    # (matched_code stays None until first call).
                    if matched_code is None:
                        matched_code = await match_template_hybrid(user_q, pool)
                    if matched_code:
                        # Factory-scoped load
                        from smartbi.services.materialized_analytics.persistence import (
                            load_materialization_results
                        )
                        if pool is not None:
                            cached_results = await load_materialization_results(
                                pool, upload_id, factory_id=trusted_factory_id
                            )
                            cached_by_code = {r["code"]: r for r in cached_results}
                            # Phase 6 P1 (Apr 26 2026): reject cache when query
                            # intent diverges from template domain. Catches the
                            # F4-class bug from S3 audit (xmx-fu-29-2 "单店 vs 同业"
                            # mistakenly hitting payment_method_mix). Conservative —
                            # only rejects when both sides are confidently classified
                            # AND different. Falls through to LLM (which has v2
                            # parent context to do better than a mis-routed cache).
                            try:
                                from smartbi.services.cache_intent_classifier import (
                                    should_reject_cache as _intent_reject,
                                )
                                from smartbi.services.smartbi_metrics import (
                                    CACHE_REJECT, CACHE_HIT_KEPT,
                                )
                                if matched_code in cached_by_code and _intent_reject(user_q, matched_code):
                                    reason = ('top_n_unsuitable' if matched_code == 'top_n_by_dim'
                                              else 'domain_mismatch')
                                    CACHE_REJECT.labels(reason=reason).inc()
                                    logger.info(
                                        f"[stream] cache rejected by intent-classifier "
                                        f"(query={user_q[:30]!r} template={matched_code})"
                                    )
                                    cached_by_code = {}  # force fall-through to LLM
                                elif matched_code in cached_by_code:
                                    CACHE_HIT_KEPT.labels(template_code=matched_code).inc()
                            except Exception as _intent_err:
                                from smartbi.services.smartbi_metrics import CACHE_REJECT
                                CACHE_REJECT.labels(reason='classifier_error').inc()
                                logger.warning(f"[stream] intent classifier failed (non-fatal): {_intent_err}")
                            if matched_code in cached_by_code:
                                tpl = cached_by_code[matched_code]
                                payload = format_cached_as_sse(
                                    tpl, user_q, intent_signals=intent_signals
                                )
                                # Stream as SSE
                                yield _sse_event("status", "正在整理已有经营分析...")
                                # Chunk the answer in small pieces for streaming feel
                                answer_text = sanitize_customer_ai_text(payload["answer"])
                                chunk_size = 40
                                for i in range(0, len(answer_text), chunk_size):
                                    yield _sse_event("chunk", answer_text[i:i + chunk_size])
                                if payload["charts"]:
                                    yield _sse_event("charts", payload["charts"])
                                wall_ms = int((time.time() - start_time) * 1000)
                                # Fire-and-forget template hit log so the user can 👍/👎.
                                # Apr 25 2026 D1.C1 fix: previous code wrapped the coroutine
                                # in asyncio.shield(<coro>) BEFORE asyncio.create_task, but
                                # shield() requires a Future/Task not a coroutine — that
                                # raised TypeError every call, the outer except logged
                                # 'template router failed' as WARNING and silently fell
                                # through to the 60s LLM path. Result: cached templates
                                # were NEVER served via AIQuery despite being materialized.
                                # Use _spawn_bg (the proven detach-and-anchor helper) and
                                # shield only the wait_for, mirroring line 1814 idiom.
                                from smartbi.api.materialized_analytics import _spawn_bg
                                _tpl_log_task = _spawn_bg(_log_template_hit_safe(
                                    pool, user_q,
                                    trusted_factory_id,
                                    upload_id, matched_code, answer_text, wall_ms,
                                ))
                                try:
                                    tpl_log_id = await asyncio.wait_for(
                                        asyncio.shield(_tpl_log_task), timeout=1.5
                                    )
                                except (asyncio.TimeoutError, Exception):
                                    tpl_log_id = None
                                yield _sse_event("done", {
                                    "success": True,
                                    "answer": answer_text,
                                    "charts": payload["charts"],
                                    "kpis": payload["kpis"],
                                    "source": "materialized_cache",
                                    "template_code": matched_code,
                                    "processingTimeMs": wall_ms,
                                    "log_id": tpl_log_id,
                                })
                                # Apr 26 2026 v2 conversation memory: write back
                                # parent context for the template cache path.
                                if _session_can_persist:
                                    try:
                                        from smartbi.services.chat_session_service import (
                                            ChatSessionService as _CSS_TPL,
                                        )
                                        from smartbi.api.materialized_analytics import _spawn_bg as _spawn_tpl
                                        _spawn_tpl(_CSS_TPL(pool).upsert(
                                            session_id=request.session_id,
                                            factory_id=_session_factory_id,
                                            parent_query=user_q,
                                            parent_answer_summary=answer_text,
                                            parent_template_code=matched_code,
                                            parent_upload_id=upload_id,
                                            user_id=_session_user_id,  # H2 binding
                                        ))
                                    except Exception as _e:
                                        logger.warning(f"[chat-session] writeback (template) failed: {_e}")
                                logger.info(f"[stream] served upload {upload_id} via cache: template={matched_code}, wall={time.time() - start_time:.2f}s, log_id={tpl_log_id}")  # noqa: E501
                                return  # early exit — don't invoke LLM
            except Exception as e:
                # Router is best-effort; fall through to LLM on any error
                logger.warning(f"[stream] template router failed, falling back to LLM: {e}")

            yield _sse_event("status", "📊 加载你的数据中...")

            # ── Data loading (same as general_analysis) ──
            data = request.data
            if request.allow_tenant_data_fallback and not data and request.sheet_id:
                data = get_sheet_data(trusted_factory_id, request.sheet_id)

            if not data and request.allow_tenant_data_fallback:
                try:
                    from smartbi.config import get_pg_pool as _get_pg_pool

                    pool = await _get_pg_pool()
                    if pool:
                        async with pool.acquire() as conn:
                            upload_id = validated_upload_id
                            if not upload_id:
                                # Bug G fix (Apr 26 2026): factory-scoped + largest non-empty upload.
                                factory_id_for_select = trusted_factory_id
                                row = await conn.fetchrow(
                                    """
                                    SELECT id FROM smart_bi_pg_excel_uploads
                                    WHERE factory_id = $1
                                      AND upload_status = 'COMPLETED'
                                      AND row_count > 0
                                    ORDER BY row_count DESC, created_at DESC
                                    LIMIT 1
                                    """,
                                    factory_id_for_select,
                                )
                                if row:
                                    upload_id = row['id']
                            if upload_id:
                                rows = await conn.fetch(
                                    """
                                    SELECT d.row_data
                                    FROM smart_bi_dynamic_data d
                                    JOIN smart_bi_pg_excel_uploads u ON u.id = d.upload_id
                                    WHERE d.upload_id = $1 AND u.factory_id = $2
                                    LIMIT 200
                                    """,
                                    upload_id,
                                    trusted_factory_id,
                                )
                                if rows:
                                    data = [_json.loads(r['row_data']) if isinstance(r['row_data'], str) else r['row_data'] for r in rows]  # noqa: E501
                                    logger.info(f"[stream] Loaded {len(data)} rows from upload {upload_id}")
                                # Bug #17 fix (Apr 17 2026): load field_definitions for prompt
                                # So LLM knows which columns are measures/dimensions/times
                                try:
                                    field_rows = await conn.fetch(
                                        """SELECT fd.original_name, fd.standard_name,
                                                  fd.is_measure, fd.is_dimension, fd.is_time
                                           FROM smart_bi_pg_field_definitions fd
                                           JOIN smart_bi_pg_excel_uploads u
                                             ON u.id = fd.upload_id
                                           WHERE fd.upload_id = $1
                                             AND u.factory_id = $2
                                           ORDER BY fd.display_order""",
                                        upload_id,
                                        trusted_factory_id,
                                    )
                                    field_meta = [dict(r) for r in field_rows]
                                    logger.info(f"[stream] Loaded {len(field_meta)} field defs for upload {upload_id}")
                                except Exception as fe:
                                    logger.warning(f"[stream] field_defs lookup failed: {fe}")
                                    field_meta = []

                                # Heartbeat: resets FE 15s/30s watchdog while全量聚合 is slow.
                                # Phase 5 UX: friendly tone — confirm sample loaded ✓ before
                                # diving into full aggregation, sets expectation that we have
                                # data and are crunching numbers.
                                yield _sse_event(
                                    "status",
                                    f"✓ 已读 {len(data)} 行样本, 正在聚合全量数据..."
                                )

                                # Bug #19 fix (Apr 17 2026): sample LIMIT 200 is insufficient
                                # for aggregation queries ("Top N by dim", "总销售额"). LLM
                                # was echoing partial sums over 200 rows instead of full data,
                                # producing wrong numbers. Fix: compute authoritative aggregates
                                # over ALL rows at DB level and inject into LLM prompt.
                                try:
                                    # Phase B (Apr 21 2026): cache upload-level aggregates to
                                    # avoid re-running 15+4+4 full JSONB scans on every AI
                                    # question against the same upload. Implementation moved
                                    # to smartbi.services.upload_aggregate_cache for clarity.
                                    # Entity-specific lookups below are NOT cached (depend on user_query).
                                    from smartbi.services.upload_aggregate_cache import (
                                        get_cache as _get_agg_cache,
                                        compute_upload_aggregates as _compute_aggs,
                                        load_bundle_from_db as _load_bundle_db,
                                        save_bundle_to_db as _save_bundle_db,
                                    )
                                    from smartbi.config import get_pg_pool as _get_pg_pool_agg
                                    _agg_cache = _get_agg_cache()
                                    _bundle = _agg_cache.get(upload_id)
                                    _pool_ref = await _get_pg_pool_agg() if _bundle is None else None
                                    _hb_text = None
                                    # L2: persistent DB cache (survives Python restarts). Checked
                                    # only when L1 in-memory is cold.
                                    if _bundle is None:
                                        _bundle = await _load_bundle_db(_pool_ref, upload_id)
                                        if _bundle is not None:
                                            _agg_cache.set(upload_id, _bundle)
                                            logger.info(
                                                f"[stream] agg L2 hit upload={upload_id} "
                                                f"(original compute {_bundle.get('compute_time_s', 0):.1f}s)"
                                            )
                                            _hb_text = (
                                                f"⚡ 已读取 {_bundle['real_total_rows']:,} 行预聚合, 正在排名..."
                                            )
                                    if _bundle is None:
                                        # L1+L2 cold: compute can take 30-70s on a 200K-row upload.
                                        # Stream a status heartbeat every 10s to keep the FE SSE
                                        # watchdog (~30s) alive — otherwise FE shows "网络连接不稳定"
                                        # and drops the stream before the compute finishes. Normally
                                        # this path is rare because γ-1c / upload-time materialization
                                        # pre-warms L2 — only first query against a brand-new upload
                                        # (before materialize completes) will land here.
                                        _compute_task = asyncio.create_task(
                                            _compute_aggs(conn, _pool_ref, upload_id, field_meta, len(data))
                                        )
                                        _compute_start = time.time()
                                        while True:
                                            try:
                                                _bundle = await asyncio.wait_for(
                                                    asyncio.shield(_compute_task), timeout=10.0
                                                )
                                                break
                                            except asyncio.TimeoutError:
                                                _elapsed = int(time.time() - _compute_start)
                                                yield _sse_event(
                                                    "status",
                                                    f"正在汇总全量数据 ({_elapsed}s，首次查询较慢)..."
                                                )
                                        _agg_cache.set(upload_id, _bundle)
                                        # Persist to L2 so the NEXT restart finds it pre-computed.
                                        _factory_id_l2 = (
                                            getattr(http_request.state, 'factory_id', None)
                                            if hasattr(http_request, 'state') else None
                                        )
                                        await _save_bundle_db(
                                            _pool_ref, upload_id, _bundle, factory_id=_factory_id_l2
                                        )
                                        logger.info(
                                            f"[stream] agg cold compute upload={upload_id} in "
                                            f"{_bundle['compute_time_s']:.1f}s (L1+L2 populated)"
                                        )
                                        _hb_text = (
                                            f"✓ 聚合完成 ({_bundle['real_total_rows']:,} 行, "
                                            f"耗时 {_bundle['compute_time_s']:.1f}s), 正在排名..."
                                        )
                                        # Release transient allocations back to OS — cold-compute
                                        # path made 15+4+4 full JSONB scans whose result buffers
                                        # can leave ~1GB in glibc arenas.
                                        try:
                                            from smartbi.services.memory_cleanup import release_and_trim
                                            release_and_trim(label=f"chat_cold_{upload_id}")
                                        except Exception:
                                            pass
                                    if _hb_text is None:
                                        logger.info(f"[stream] agg L1 hit upload={upload_id}")
                                        _hb_text = (
                                            f"⚡ 内存命中 ({_bundle['real_total_rows']:,} 行 / <0.1s), 正在排名..."
                                        )
                                    # Unpack bundle into locals that the entity block + prompt builder use
                                    field_meta = _bundle['field_meta']
                                    measures = _bundle['measures']
                                    dims = _bundle['dims']
                                    real_total_rows = _bundle['real_total_rows']
                                    agg_lines = list(_bundle['agg_lines'])  # mutable copy (entity block appends)
                                    top5_by_dim = dict(_bundle['top5_by_dim'])
                                    primary_measure = _bundle['primary_measure']
                                    # #3: AVG for intensive primary measure (星级分/评分/率) in the
                                    # query-scoped aggregates below — a SUM of star points is
                                    # meaningless. Mirror the bundle path (_measure_is_avg).
                                    from smartbi.services.upload_aggregate_cache import _measure_is_avg
                                    _q_agg = "AVG" if _measure_is_avg(primary_measure) else "SUM"
                                    _q_lbl = "平均" if _q_agg == "AVG" else "总计"
                                    # Heartbeat after aggregate phase (cache hit OR fresh compute)
                                    yield _sse_event("status", _hb_text)

                                    # ── C1/C2/C3 query-scoped aggregates (Apr 21 2026) ──
                                    # The cache above is upload-level (global). User queries
                                    # that mention time windows ("3月"), specific dimensions
                                    # ("商品信息"), or subcategories ("饮品") need query-specific
                                    # aggregates because those filters vary per question.
                                    try:
                                        from smartbi.services.query_filters import (
                                            extract_time_filter,
                                            pick_time_column,
                                            time_where_clause,
                                            hoist_mentioned_dims,
                                            classify_product_info,
                                            user_wants_subcategory,
                                        )
                                        _q = (request.effective_query or "")
                                        _tf = extract_time_filter(_q)
                                        _time_col = pick_time_column(field_meta) if _tf else None
                                        _mentioned_dims = hoist_mentioned_dims(_q, dims)
                                        # Top 4 dims after hoist — these drive the focused top5
                                        _dims_focus = _mentioned_dims[:4] if _mentioned_dims else []

                                        # C1: time-filtered totals + top5 per focused dim
                                        if _tf and _time_col and primary_measure:
                                            agg_lines.append(
                                                f"\n## {_tf['label']} 时间段聚合 (权威, 基于全量)"
                                            )
                                            # Grand total for the time window
                                            where_frag, extra = time_where_clause(_tf, "$2", 2)
                                            q_total = (
                                                f"SELECT {_q_agg}((row_data->>$1)::numeric) AS s, "
                                                f"COUNT((row_data->>$1)::numeric) AS c "
                                                f"FROM smart_bi_dynamic_data "
                                                f"WHERE upload_id = $3 "
                                                f"AND row_data->>$1 ~ '^-?[0-9.,]+$' "
                                                f"{where_frag}"
                                            )
                                            try:
                                                tot_row = await conn.fetchrow(
                                                    q_total, primary_measure, _time_col, upload_id, *extra
                                                )
                                                if tot_row and tot_row['s'] is not None:
                                                    agg_lines.append(
                                                        f"- {_tf['label']} {primary_measure} {_q_lbl}: "
                                                        f"{tot_row['s']:,.2f} (行数={tot_row['c']})"
                                                    )
                                            except Exception as e1:
                                                logger.warning(f"[stream] time-total failed: {e1}")
                                            # Per-dim top5 under the time filter
                                            for _dim in _dims_focus:
                                                try:
                                                    # ── Build SQL with time filter injected ──
                                                    # Params: $1=dim, $2=measure, $3=time_col, $4=upload_id
                                                    where_frag2, extra2 = time_where_clause(_tf, "$3", 4)
                                                    q_top = (
                                                        f"SELECT row_data->>$1 AS label, "
                                                        f"{_q_agg}((row_data->>$2)::numeric) AS total "
                                                        f"FROM smart_bi_dynamic_data "
                                                        f"WHERE upload_id = $4 "
                                                        f"AND row_data->>$2 ~ '^-?[0-9.,]+$' "
                                                        f"AND row_data->>$1 IS NOT NULL "
                                                        f"AND row_data->>$1 NOT IN ('合计','总计','Total','TOTAL','小计') "
                                                        f"{where_frag2} "
                                                        f"GROUP BY row_data->>$1 "
                                                        f"ORDER BY total DESC NULLS LAST LIMIT 5"
                                                    )
                                                    rows = await conn.fetch(
                                                        q_top, _dim, primary_measure, _time_col, upload_id, *extra2
                                                    )
                                                    if rows:
                                                        top_str = ", ".join(
                                                            f"{r['label']}={float(r['total'] or 0):,.2f}"
                                                            for r in rows
                                                        )
                                                        agg_lines.append(
                                                            f"- {_tf['label']} Top5 by {_dim} "
                                                            f"(按 {primary_measure}): {top_str}"
                                                        )
                                                        # Also expose as a chart candidate
                                                        top5_by_dim.setdefault(f"{_dim} @ {_tf['label']}", [
                                                            {"label": r['label'],
                                                             "total": float(r['total'] or 0)}
                                                            for r in rows
                                                        ])
                                                except Exception as e2:
                                                    logger.warning(f"[stream] time-top5 {_dim} failed: {e2}")
                                            logger.info(
                                                f"[stream] time-filtered agg done for {_tf['label']} "
                                                f"col={_time_col} dims={_dims_focus[:2]}"
                                            )

                                        # C2: user-mentioned dims that weren't in cached top5
                                        _mentioned_only = [
                                            d for d in _dims_focus if d not in top5_by_dim
                                        ]
                                        if _mentioned_only and primary_measure and not _tf:
                                            for _dim in _mentioned_only:
                                                try:
                                                    rows = await conn.fetch(
                                                        f"""SELECT row_data->>$1 AS label,
                                                                  {_q_agg}((row_data->>$2)::numeric) AS total
                                                           FROM smart_bi_dynamic_data
                                                           WHERE upload_id = $3
                                                             AND row_data->>$2 ~ '^-?[0-9.,]+$'
                                                             AND row_data->>$1 IS NOT NULL
                                                             AND row_data->>$1 NOT IN ('合计','总计','Total','TOTAL','小计')
                                                           GROUP BY row_data->>$1
                                                           ORDER BY total DESC NULLS LAST LIMIT 10""",
                                                        _dim, primary_measure, upload_id
                                                    )
                                                    if rows:
                                                        top_str = ", ".join(
                                                            f"{r['label']}={float(r['total'] or 0):,.2f}"
                                                            for r in rows[:5]
                                                        )
                                                        agg_lines.append(
                                                            f"- Top by {_dim} (按 {primary_measure}, "
                                                            f"query-mentioned): {top_str}"
                                                        )
                                                        top5_by_dim[_dim] = [
                                                            {"label": r['label'],
                                                             "total": float(r['total'] or 0)}
                                                            for r in rows[:5]
                                                        ]
                                                except Exception as e3:
                                                    logger.warning(f"[stream] mentioned-dim {_dim} failed: {e3}")

                                        # C3: 商品信息 subcategory rollup (qhj POS combos)
                                        _subcats = user_wants_subcategory(_q)
                                        _product_col = None
                                        for f in field_meta:
                                            nm = str(f.get('original_name') or '')
                                            if nm in ('商品信息', '商品名称', '菜品名称', '商品名'):
                                                _product_col = nm
                                                break
                                        if _subcats and _product_col and primary_measure:
                                            try:
                                                # Fetch top 500 products + their revenue, then
                                                # classify + rollup by category in Python.
                                                where_frag3, extra3 = ("", [])
                                                if _tf and _time_col:
                                                    where_frag3, extra3 = time_where_clause(_tf, "$3", 3)
                                                    q_prod = (
                                                        f"SELECT row_data->>$1 AS label, "
                                                        f"{_q_agg}((row_data->>$2)::numeric) AS total, "
                                                        f"COUNT(*) AS cnt "
                                                        f"FROM smart_bi_dynamic_data "
                                                        f"WHERE upload_id = $4 "
                                                        f"AND row_data->>$2 ~ '^-?[0-9.,]+$' "
                                                        f"AND row_data->>$1 IS NOT NULL "
                                                        f"{where_frag3} "
                                                        f"GROUP BY row_data->>$1 "
                                                        f"ORDER BY total DESC NULLS LAST LIMIT 500"
                                                    )
                                                    prod_rows = await conn.fetch(
                                                        q_prod, _product_col, primary_measure, _time_col, upload_id, *extra3  # noqa: E501
                                                    )
                                                else:
                                                    prod_rows = await conn.fetch(
                                                        f"""SELECT row_data->>$1 AS label,
                                                                  {_q_agg}((row_data->>$2)::numeric) AS total,
                                                                  COUNT(*) AS cnt
                                                           FROM smart_bi_dynamic_data
                                                           WHERE upload_id = $3
                                                             AND row_data->>$2 ~ '^-?[0-9.,]+$'
                                                             AND row_data->>$1 IS NOT NULL
                                                           GROUP BY row_data->>$1
                                                           ORDER BY total DESC NULLS LAST LIMIT 500""",
                                                        _product_col, primary_measure, upload_id
                                                    )
                                                cat_totals: Dict[str, Dict[str, float]] = {}
                                                for pr in prod_rows:
                                                    cat = classify_product_info(pr['label'])
                                                    entry = cat_totals.setdefault(cat, {"total": 0.0, "cnt": 0, "items": 0})  # noqa: E501
                                                    entry["total"] += float(pr['total'] or 0)
                                                    entry["cnt"] += int(pr['cnt'] or 0)
                                                    entry["items"] += 1
                                                time_label = _tf['label'] if _tf else '全量'
                                                focus_label = "/".join(_subcats)
                                                agg_lines.append(
                                                    f"\n## 商品子品类聚合 ({time_label}, 用户关注: {focus_label})"
                                                )
                                                # Sort by total desc, emit all matched + top 3 others
                                                sorted_cats = sorted(
                                                    cat_totals.items(),
                                                    key=lambda kv: kv[1]["total"],
                                                    reverse=True,
                                                )
                                                for cat, v in sorted_cats:
                                                    mark = " ★" if cat in _subcats else ""
                                                    agg_lines.append(
                                                        f"- {cat}{mark}: 金额={v['total']:,.2f} "
                                                        f"行次={v['cnt']} 品项数={v['items']}"
                                                    )
                                                logger.info(
                                                    f"[stream] subcategory rollup done: {len(sorted_cats)} cats, "
                                                    f"focus={_subcats}, time={_tf}"
                                                )
                                            except Exception as e4:
                                                logger.warning(f"[stream] subcategory rollup failed: {e4}")
                                    except Exception as eouter:
                                        logger.warning(f"[stream] C1/C2/C3 block failed: {eouter}")
                                    # Bug #23 fix (Apr 17 2026): user may mention specific
                                    # entities that aren't in Top-5 (e.g., asks about 南方百联店
                                    # which ranks #12). Scan the query for distinct labels of
                                    # each dimension and inject targeted per-entity aggregates.
                                    try:
                                        user_query = (request.effective_query or "")
                                        if user_query and primary_measure:
                                            mentioned = []  # (dim, label) tuples
                                            for dim in dims[:3]:
                                                labels_rows = await conn.fetch(
                                                    """SELECT DISTINCT row_data->>$1 AS label
                                                       FROM smart_bi_dynamic_data
                                                       WHERE upload_id = $2
                                                         AND row_data->>$1 IS NOT NULL
                                                         AND length(row_data->>$1) >= 3
                                                       LIMIT 500""",
                                                    dim, upload_id
                                                )
                                                for lr in labels_rows:
                                                    lab = lr['label']
                                                    if lab and lab in user_query:
                                                        mentioned.append((dim, lab))
                                            # Dedupe + cap at 6 entities to keep prompt short
                                            seen = set()
                                            uniq_mentioned = []
                                            for dim, lab in mentioned:
                                                key = (dim, lab)
                                                if key in seen:
                                                    continue
                                                seen.add(key)
                                                uniq_mentioned.append((dim, lab))
                                                if len(uniq_mentioned) >= 6:
                                                    break
                                            if uniq_mentioned:
                                                agg_lines.append("## 用户提到的具体实体聚合 (权威, 基于 DB 全量)")
                                                for dim, lab in uniq_mentioned:
                                                    rr = await conn.fetchrow(
                                                        f"""SELECT {_q_agg}((row_data->>$1)::numeric) AS s,
                                                                  COUNT((row_data->>$1)::numeric) AS c
                                                           FROM smart_bi_dynamic_data
                                                           WHERE upload_id = $2
                                                             AND row_data->>$1 ~ '^-?[0-9.,]+$'
                                                             AND row_data->>$3 = $4""",
                                                        primary_measure, upload_id, dim, lab
                                                    )
                                                    if rr and rr['s'] is not None:
                                                        agg_lines.append(
                                                            f"- {dim}={lab}: {primary_measure} {_q_lbl}={rr['s']:,.2f} (行数={rr['c']})"  # noqa: E501
                                                        )
                                                logger.info(f"[stream] Entity aggregates: {len(uniq_mentioned)} entities")  # noqa: E501
                                    except Exception as ee:
                                        logger.warning(f"[stream] entity lookup failed: {ee}")

                                    real_aggregates_text = "\n".join(agg_lines)
                                    logger.info(f"[stream] Computed real aggregates: {len(agg_lines)} lines")
                                except Exception as ae:
                                    logger.warning(f"[stream] real aggregates failed: {ae}")
                                    real_aggregates_text = ""
                except Exception as e:
                    logger.warning(f"[stream] Failed to load upload data: {e}")

            if not data:
                # No data — try direct LLM text analysis.
                # 死胡同修复 (May 31 2026): relax old `len > 20` gate (which
                # dead-ended every SHORT user question into "暂无数据") to a
                # 2-char minimum so short cache-miss queries still get a real
                # streamed LLM answer instead of the upload-Excel placeholder.
                query = request.effective_query
                if query and len(query.strip()) >= 2:
                    try:
                        insight_gen = InsightGenerator()
                        yield _sse_event("status", "正在分析...")
                        full_text = ""
                        async for chunk in insight_gen._call_llm_stream_text(
                            query, max_tokens=1500, temperature=0.2
                        ):
                            full_text += chunk
                            yield _sse_event("chunk", chunk)
                        yield _sse_event("done", {
                            "success": True,
                            "answer": full_text,
                            "charts": [],
                            "processingTimeMs": int((time.time() - start_time) * 1000)
                        })
                        # Distillation capture (training corpus): freshly-streamed
                        # LLM answer on the no-data direct-LLM branch (mirrors the
                        # non-stream call site). Cache-serve / degraded paths return
                        # earlier, so reaching here means a complete fresh teacher
                        # pair. Guarded on non-empty full_text. Fire-and-forget.
                        if full_text:
                            await _capture_qa_distillation(
                                request.effective_query, full_text, http_request
                            )
                        return
                    except Exception as e:
                        logger.warning(f"[stream] Direct LLM failed: {e}")

                yield _sse_event("done", {
                    "success": True,
                    "answer": "暂无可分析的数据。请先上传 Excel 文件或在「智能数据分析」页面选择数据源后，再使用 AI 问答功能。",
                    "charts": [],
                    "processingTimeMs": int((time.time() - start_time) * 1000)
                })
                return

            import pandas as pd
            df = coerce_numeric_columns(pd.DataFrame(data))

            # ── Filter index columns ──
            _idx_patterns = {'行次', '序号', '编号', '行号', '项目编号', 'index', 'no', 'no.', 'id', 'row_num', 'row_number', 'sn'}
            cols_to_drop = []
            for col in df.columns:
                lower = col.lower().strip()
                if lower in _idx_patterns:
                    cols_to_drop.append(col)
                else:
                    try:
                        vals = pd.to_numeric(df[col].dropna().head(20), errors='coerce').dropna()
                        if len(vals) >= 3:
                            diffs = vals.diff().dropna()
                            if len(diffs) > 0 and all(d == 1 for d in diffs):
                                cols_to_drop.append(col)
                    except Exception:
                        pass
            if cols_to_drop:
                df = df.drop(columns=cols_to_drop, errors='ignore')
                data = [{k: v for k, v in row.items() if k not in cols_to_drop} for row in data]

            # Apr 26 2026 phase 5 (UX): set user expectation for LLM tail wait.
            # Apr 28 2026 (audit): two-stage progress hint reflecting actual
            # SSE behavior. TTFT 3-8s (post prompt-cache optimization), then
            # tokens stream incrementally. Previous "20-30 秒" / "5-8 秒"
            # both misleading — first version too long, second too short for
            # full answer.
            yield _sse_event(
                "status",
                "🤔 AI 正在思考... 首段答案 5-10 秒后开始流出, 完整答案需 15-25 秒"
            )

            # ── Use default qwen-plus but with optimized params ──
            insight_gen = InsightGenerator()

            # ── Build LEAN prompt: only data_summary + financial (skip KB, production, stat_digest) ──
            data_summary = insight_gen._prepare_data_summary(df)
            financial_metrics = insight_gen._compute_financial_context(df)

            query = request.effective_query
            analysis_ctx = _build_analysis_ctx(query, request.context)

            # Bug #17 fix: include field_definitions in prompt so LLM knows
            # which columns are measures/dimensions/times for the selected upload.
            # Apr 28 2026 (post-review P1 follow-up): annotate each dim with its
            # cardinality so LLM avoids ranking on cardinality=1 dims (e.g. multi-
            # period single-store pivot where `_门店或时段` is forward-filled to
            # one store across all rows). Without this, LLM falls back to
            # ranking on a numeric measure col misnamed "_门店名称" → labels
            # rows by index instead of saying "data is single-store".
            field_summary = ""
            if 'field_meta' in locals() and field_meta:
                measures_all = [f['original_name'] for f in field_meta if f.get('is_measure')]
                dim_names_all = [f['original_name'] for f in field_meta if f.get('is_dimension')]
                times_all = [f['original_name'] for f in field_meta if f.get('is_time')]
                try:
                    df_for_card = df if 'df' in locals() else None
                except Exception:
                    df_for_card = None
                # Apr 28 2026 (post-review #2 + #3): row-index heuristic.
                # Check measures AND dims AND times — pivot Excel exports
                # often misclassify the row-index column. Pattern: name has
                # entity tokens (名称/门店/客户/员工) but values are sequential
                # integers 1..N within row count + buffer. Flag and tell LLM
                # NOT to use this as ranking/grouping dim.
                _ENTITY_TOKENS = ("名称", "门店", "客户", "员工", "供应商", "店铺")
                row_index_cols: list[dict] = []

                def _is_row_index(col_name: str) -> bool:
                    # Reviewer round 2 P2: dropped redundant guard (line 2127
                    # already covers None case; the prior `not df_for_card is
                    # not None` was a confusing double-negative).
                    if df_for_card is None or col_name not in df_for_card.columns:
                        return False
                    if not any(tok in col_name for tok in _ENTITY_TOKENS):
                        return False
                    try:
                        col_vals = df_for_card[col_name].dropna()
                        int_vals = []
                        for v in col_vals.unique():
                            try:
                                fv = float(v)
                            except (TypeError, ValueError):
                                return False
                            if fv != int(fv):
                                return False
                            int_vals.append(int(fv))
                        if not int_vals:
                            return False
                        int_vals.sort()
                        n_total = len(df_for_card)
                        # Reviewer round 2 P1 #2: actually verify row-index
                        # pattern, not just min/max bounds. A real row index
                        # has values densely packed in a contiguous range AND
                        # has roughly one unique value per row. Without these,
                        # legit small-integer ID columns ({2, 5, 7} or {3, 7})
                        # would false-positive.
                        if len(int_vals) < 2:
                            return False
                        if int_vals[0] < 0:
                            return False
                        if int_vals[-1] > n_total + 5:
                            return False
                        # Density: ≥50% of the int range must be present.
                        # Catches `{1..10}` (1.0), `{1,2,3,4,5,6,7,10}` (0.8),
                        # but rejects `{2, 5, 7}` (0.5 — borderline; combined
                        # with row-coverage check below it fails).
                        span = int_vals[-1] - int_vals[0] + 1
                        if span > 0 and len(int_vals) / span < 0.5:
                            return False
                        # Row coverage: row-index has ~1 unique value per row.
                        # Real entity-ID columns in a small report typically
                        # cover <50% of rows (3 employees in 10-row report).
                        if len(int_vals) < n_total * 0.5:
                            return False
                        return True
                    except Exception:
                        return False

                def _row_index_info(col_name: str) -> dict:
                    col_vals = df_for_card[col_name].dropna()
                    int_vals = sorted(int(float(v)) for v in col_vals.unique())
                    return {"name": col_name, "sample": int_vals[:5],
                            "min": int_vals[0], "max": int_vals[-1]}

                clean_measures: list[str] = []
                clean_dims: list[str] = []
                clean_times: list[str] = []
                seen_row_idx = set()
                for mn in measures_all:
                    if _is_row_index(mn):
                        if mn not in seen_row_idx:
                            row_index_cols.append(_row_index_info(mn))
                            seen_row_idx.add(mn)
                    else:
                        clean_measures.append(mn)
                for dn in dim_names_all:
                    if _is_row_index(dn):
                        if dn not in seen_row_idx:
                            row_index_cols.append(_row_index_info(dn))
                            seen_row_idx.add(dn)
                    else:
                        clean_dims.append(dn)
                for tn in times_all:
                    if _is_row_index(tn):
                        if tn not in seen_row_idx:
                            row_index_cols.append(_row_index_info(tn))
                            seen_row_idx.add(tn)
                    else:
                        clean_times.append(tn)
                # Enrich dims (after row-index filter) with cardinality + sample.
                # Also enrich times (since time fields like _门店或时段 may have
                # been auto-classified as time but user-facing they're a dim).
                dims_enriched: list[str] = []
                single_value_dims: list[str] = []
                for dn in clean_dims + clean_times:
                    if df_for_card is not None and dn in df_for_card.columns:
                        try:
                            uniq = df_for_card[dn].dropna().unique()
                            n_uniq = len(uniq)
                            if n_uniq == 1:
                                sample = str(uniq[0])[:40]
                                dims_enriched.append(f"{dn} (cardinality=1, 单一值: {sample})")
                                single_value_dims.append(f"{dn}={sample}")
                            elif n_uniq <= 5:
                                samples = ", ".join(str(v)[:20] for v in uniq[:5])
                                dims_enriched.append(f"{dn} (cardinality={n_uniq}: {samples})")
                            else:
                                dims_enriched.append(f"{dn} (cardinality={n_uniq})")
                        except Exception:
                            dims_enriched.append(dn)
                    else:
                        dims_enriched.append(dn)
                lines = ["## 当前数据源字段分类 (权威信息，优先使用)"]
                if clean_measures:
                    lines.append(f"可聚合数值字段 (measures, 用于 sum/avg/count): {', '.join(clean_measures)}")
                if dims_enriched:
                    lines.append(f"分类维度/时间字段 (dimensions, 用于分组): {', '.join(dims_enriched)}")
                # Row-index block: explicitly call out columns where name
                # suggests entity but values are row index 1..N.
                if row_index_cols:
                    lines.append("**禁止使用 — 伪实体列 (row-index, 不是真实体)**:")
                    for ri in row_index_cols:
                        sample_str = ", ".join(str(s) for s in ri["sample"])
                        lines.append(
                            f"  - `{ri['name']}` 字段名暗示实体但值是连续整数 {sample_str}... "
                            f"(范围 {ri['min']}-{ri['max']}). 这是行索引/编号, "
                            f"不是真实的门店/客户/员工标识. 当用户问门店/客户/员工排名时, **绝对不要** "
                            f"使用此字段做分组或排名维度. 真实实体见 dimensions 列表的 `_` 前缀字段."
                        )
                    logger.info(f"[stream] row_index_cols flagged: {[ri['name'] for ri in row_index_cols]}")
                # cardinality=1 short-circuit hint
                if single_value_dims:
                    lines.append(
                        f"**重要 — 单一值维度提醒**: {', '.join(single_value_dims)} 在本数据集只有 1 个唯一值, "
                        f"不存在 '排名/对比/最高最低' 的概念. 当用户问这类维度的排名时, 应直接回答 "
                        f"'本数据集只有 1 个 {single_value_dims[0].split('=')[0]} ({single_value_dims[0].split('=', 1)[1]})', "
                        f"然后给出聚合数字 (sum/avg) 而非排名."
                    )
                # Apr 28 2026 (UX audit): date inference safety. When dataset
                # has no explicit per-row date column (only a time-range string
                # like "10.9-10.13" in a single dim), LLM was inferring
                # "row 2 = 10月10日" without basis. Tell LLM to reference rows
                # by index/sequence number, not invented dates.
                has_explicit_time = bool(clean_times) and any(
                    df_for_card is not None and tn in df_for_card.columns
                    and df_for_card[tn].dropna().nunique() > 1
                    for tn in clean_times
                )
                if not has_explicit_time:
                    lines.append(
                        "**日期推断约束**: 本数据集没有逐行的明确日期字段 (per-row date). "
                        "如时间范围出现在某个 dim 字段 (例如 '10.9-10.13'), 不要把行索引/排名编号"
                        "推断为具体日期 ('第 2 行 ≠ 10月10日'). 用户问'哪一天'时, 回答"
                        "'数据没有明确的日期列, 但行 2 (在 X 时段内) 表现最高 / 最低, 具体日期需要"
                        "查询源数据确认' — 不要编造日期."
                    )
                field_summary = "\n".join(lines) + "\n"

            # Apr 26 2026 UX-2: dataset capability hint. Sparse-data tenants
            # (e.g. xmx member-only) used to get "建议补充数据" non-answers when
            # asked cross-domain. Now we tell LLM upfront what's IN/OUT of
            # scope so it answers within scope or says "本数据无 X, 基于 Y..."
            # in 1 sentence instead of a 11s wait + filler.
            capability_hint = ""
            _caps_obj = None
            if 'field_meta' in locals() and field_meta:
                try:
                    from smartbi.services.dataset_capabilities import (
                        detect_capabilities, build_capability_prompt_hint,
                    )
                    _caps_obj = detect_capabilities(field_meta)
                    capability_hint = build_capability_prompt_hint(_caps_obj)
                except Exception as _ce:
                    logger.warning(f"[stream] capability hint build failed: {_ce}")

            # v7 #2 (Apr 26 2026): hard capability mismatch short-circuit.
            # If user query domain (from cache_intent_classifier) maps to a
            # capability the dataset DEFINITELY lacks (e.g. xmx asked about
            # 销售/营业额 but only has 会员储值 fields), skip LLM and emit
            # explainer directly. Saves ~10s per such query + reduces empty
            # answers on sparse-data tenants. xmx v6 had 14% timeout — much
            # of it was LLM thrashing on impossible queries.
            if _caps_obj is not None and request.effective_query and not chat_session_parent:
                try:
                    from smartbi.services.cache_intent_classifier import (
                        classify_query_domain,
                    )
                    from smartbi.services.dataset_capabilities import (
                        should_short_circuit,
                    )
                    _query_domain = classify_query_domain(request.effective_query)
                    _short_circuit_msg = should_short_circuit(_query_domain, _caps_obj)
                    if _short_circuit_msg:
                        logger.info(
                            f"[stream] capability short-circuit: domain={_query_domain} "
                            f"missing required capability — skipping LLM"
                        )
                        # Stream the message as if generated.
                        for i in range(0, len(_short_circuit_msg), 60):
                            yield _sse_event("chunk", _short_circuit_msg[i:i + 60])
                        yield _sse_event("done", {
                            "success": True,
                            "answer": _short_circuit_msg,
                            "charts": [],
                            "source": "capability_short_circuit",
                            "processingTimeMs": int((time.time() - start_time) * 1000),
                        })
                        return  # early exit — saved the LLM call
                except Exception as _sc_err:
                    logger.warning(f"[stream] capability short-circuit failed: {_sc_err}")

            # Bug #19 fix (Apr 17 2026): inject authoritative full-data aggregates
            # so LLM doesn't guess from 200-row sample.
            real_agg_block = ""
            if 'real_aggregates_text' in locals() and real_aggregates_text:
                real_agg_block = f"\n## 全量数据聚合 (权威，基于 DB 全部行计算，优先引用这些数字)\n{real_aggregates_text}\n"

            # Apr 26 2026 v2 conversation memory: prepend parent (q, a_summary) if
            # session lookup hit in Phase 0. Adds ~500 tokens on follow-up turns
            # but lets the LLM resolve "末位是哪个" / "流失主因" without re-deriving
            # context from scratch (which often timed out at 30s in S3 audit).
            session_context_block = ""
            if chat_session_parent:
                try:
                    from smartbi.services.chat_session_service import build_context_block
                    session_context_block = build_context_block(chat_session_parent)
                except Exception as e:
                    logger.warning(f"[chat-session] context block build failed: {e}")

            # Apr 28 2026 (DeepSeek cache optimization): static format rules +
            # answer-shape constraints moved from per-call user prompt to
            # system_role. DeepSeek's prefix-cache only matches IDENTICAL
            # token sequences, so putting these in user prompt (where data
            # varies every call) bypassed cache entirely. Now they're part
            # of the system_role prefix that every call shares — expected
            # cache hit rate ~23% → ~50%, prompt size 4K → 3K tokens, TTFT
            # 5-15s → 3-8s on cache hit.
            prompt = f"""{session_context_block}用户问题：{analysis_ctx}

{field_summary}
{capability_hint}
## 数据概览 (样本)
{data_summary}
{real_agg_block}
{financial_metrics}"""

            system_role = (
                "你是食品企业的数据分析师。精炼回答，引用数字，给可执行建议。Markdown格式。"
                + NUMERIC_GUARD_CLAUSE
                + LABELING_GUARD_CLAUSE
                + ACTION_REC_GUARD_CLAUSE
                + USER_FRIENDLY_TONE_CLAUSE
                # Apr 28 2026: pulled from user prompt → system_role for
                # DeepSeek prefix-cache. These rules are constant across all
                # calls; previously polluted the variable user prompt.
                + (
                    "\n\n## 回答规则 (基于用户提供的'当前数据源'段)\n"
                    "- 用 measures 做统计 (sum/avg/count)\n"
                    "- 按 dimensions 分组对比\n"
                    "- **不要把 measure 字段的数值当作维度名称引用** — 用户问'门店/品类/客户/员工'"
                    "等实体时, 必须从 dimensions 列表中选 dim 字段名作为分组维度, 即使某个 measure"
                    "字段名含'名称/门店/客户'等字符串也不要选 (它的 values 是数字, 不是真实体名). "
                    "优先使用 `_` 前缀的合成 dim 字段 (例如 `_门店或时段`), 它由 ETL 从段落表头"
                    " forward-fill 而来.\n"
                    "- 涉及总量/排名/占比时, 必须引用'全量数据聚合'段的数字, 不要从样本重新计算\n"
                    "- 不要引用非当前字段列表中的字段名 (避免幻觉)\n"
                    "- 若提供了'上一轮对话'段, 优先延续上一轮的实体和数字, 不要重新介绍\n"
                    "- 遵守'数据集能力边界'段, 不属本数据集的字段不要瞎答\n\n"
                    "## 回答格式 (强制 — 3 段落, 总长 ≤ 200 字)\n"
                    "1. **结论** (1 句话, 含数字): 直接回答 user 的问题\n"
                    "2. **关键发现** (1-2 句, 含数字 + 实体): 解释/支持结论\n"
                    "3. **行动建议** (2 项, 用 - 开头): 具体可做, 含负责人 + 时间 + 预期收益\n\n"
                    "避免: 大段铺陈/套话/'建议补充数据'类废话/超过 200 字. "
                    "严格控制字数 — LLM 输出每多 100 字, 用户多等 3 秒."
                )
            )

            # v4 B2-B (Apr 26 2026): LLM-answer cache lookup BEFORE the LLM
            # call. Skip when v2 conv memory has parent context (different turn
            # → different expected answer). Cache key = (factory_id +
            # normalized_q + upload_id), TTL 24h. ~80% of follow-ups go through
            # LLM costing 10-12s; cache hit returns in 200ms.
            _llm_cache_hit = None
            if (request.allow_tenant_data_fallback
                    and not chat_session_parent and _session_factory_id
                    and request.effective_query):
                try:
                    from smartbi.services.llm_answer_cache import LlmAnswerCache
                    from smartbi.services.query_normalizer import normalize_for_match
                    from smartbi.config import get_pg_pool as _get_pool_lac
                    _normalized_q = normalize_for_match(request.effective_query)
                    _lac_pool = await _get_pool_lac()
                    if _lac_pool is not None:
                        _lac_svc = LlmAnswerCache(_lac_pool)
                        _llm_cache_hit = await _lac_svc.get(
                            _session_factory_id, _normalized_q, upload_id
                        )
                        if _llm_cache_hit:
                            logger.info(
                                f"[llm-cache] HIT factory={_session_factory_id} "
                                f"q='{_normalized_q[:30]}' hits={_llm_cache_hit['hit_count']}"
                            )
                except Exception as _lac_err:
                    logger.warning(f"[llm-cache] lookup failed (non-fatal): {_lac_err}")

            if _llm_cache_hit:
                # Stream cached answer as if it were freshly generated.
                _cached_text = _llm_cache_hit["answer_text"]
                yield _sse_event("status", "正在读取近期分析结果...")
                _chunk_size = 80
                for i in range(0, len(_cached_text), _chunk_size):
                    yield _sse_event("chunk", _cached_text[i:i + _chunk_size])
                if _llm_cache_hit.get("charts"):
                    yield _sse_event("charts", _llm_cache_hit["charts"])
                yield _sse_event("done", {
                    "success": True,
                    "answer": _cached_text,
                    "charts": _llm_cache_hit.get("charts", []),
                    "warning": _llm_cache_hit.get("warning"),
                    "source": "llm_answer_cache",
                    "cache_hit_count": _llm_cache_hit["hit_count"],
                    "processingTimeMs": int((time.time() - start_time) * 1000),
                })
                # Still write back v2 conv-memory parent context if session_id
                # provided (cache hit ≠ skip session writeback).
                if _session_can_persist:
                    try:
                        from smartbi.services.chat_session_service import ChatSessionService
                        from smartbi.api.materialized_analytics import _spawn_bg
                        _session_pool = await get_pg_pool()
                        if _session_pool is not None:
                            _spawn_bg(ChatSessionService(_session_pool).upsert(
                                session_id=request.session_id,
                                factory_id=_session_factory_id,
                                parent_query=request.effective_query or "",
                                parent_answer_summary=_cached_text,
                                parent_template_code=None,
                                parent_upload_id=upload_id,
                                user_id=_session_user_id,  # H2 binding
                            ))
                    except Exception as _e:
                        logger.warning(f"[chat-session] cache-hit writeback failed: {_e}")
                return  # early exit — cache served the answer

            # ── Stream LLM response ──
            # v5 fix (Apr 26 2026): rollback P1-enhanced wait_for(__anext__())
            # pattern which broke stream consumption (v5 verification: 86%
            # empty answers — wait_for somehow consumes chunks but they don't
            # propagate). Back to traditional `async for chunk in stream`.
            # Soft 25s cut-off uses elapsed check between chunks (only fires
            # when LLM produces something then stalls). Pure silent (0-chunk)
            # case is caught by post-loop safety net below.
            full_text = ""
            _llm_start = time.time()
            _LLM_SOFT_TIMEOUT_S = 25.0
            _llm_truncated = False
            _silent_timeout = False

            # Apr 28 2026: max_tokens 400 → 380. Initial reduction to 320
            # truncated real answers mid-sentence (verified prod test cut
            # at "(约 **170"). p95 of well-formed 3-段 answer is ~340 tokens
            # for CJK with action items + numbers. 380 gives 40-token buffer
            # while still capping rambling models at ~50% of prior 800-token
            # ceiling.
            async for chunk in insight_gen._call_llm_stream_text(
                prompt, system_role, max_tokens=380, temperature=0.2
            ):
                if await http_request.is_disconnected():
                    logger.info("[stream] Client disconnected, stopping")
                    return
                _elapsed = time.time() - _llm_start
                if _elapsed > _LLM_SOFT_TIMEOUT_S and full_text:
                    _llm_truncated = True
                    try:
                        from smartbi.services.smartbi_metrics import LLM_SOFT_TIMEOUT
                        LLM_SOFT_TIMEOUT.labels(
                            silent='false',
                            has_parent_ctx='true' if chat_session_parent else 'false',
                        ).inc()
                    except Exception:
                        pass
                    logger.warning(
                        f"[stream] LLM soft timeout {_elapsed:.1f}s > "
                        f"{_LLM_SOFT_TIMEOUT_S}s, truncating with {len(full_text)} chars"
                    )
                    break
                full_text += chunk
                yield _sse_event("chunk", chunk)

            # v5 safety net (Apr 26 2026): detect silent 0-chunk LLM completion
            # — happens when call_chain_stream silently returns due to all
            # providers exhausting their CB threshold mid-iteration without
            # raising. v5 verification showed 230+ queries hitting this path.
            # Force truncated state so cache fallback / silent-msg branch fires.
            if not _llm_truncated and not full_text:
                _llm_truncated = True
                _silent_timeout = True
                logger.warning(
                    f"[stream] LLM produced 0 chunks without truncation "
                    f"(elapsed={time.time()-_llm_start:.1f}s) — provider may "
                    f"have silently exhausted. Treating as silent timeout."
                )

            if _llm_truncated:
                # G2 (Apr 26 2026): try LLM-answer cache fallback BEFORE
                # serving the timeout message. If the same query has a cached
                # answer from a prior successful run, replace the truncated/
                # empty text with the cached answer + note. Avoids forcing
                # users to re-ask (which doubles LLM cost).
                _cache_fallback = None
                if (request.allow_tenant_data_fallback
                        and _session_factory_id and request.effective_query
                        and not chat_session_parent):
                    try:
                        from smartbi.services.llm_answer_cache import LlmAnswerCache as _LAC_FB
                        from smartbi.services.query_normalizer import normalize_for_match
                        _fb_pool = await get_pg_pool()
                        if _fb_pool is not None:
                            _norm_q_fb = normalize_for_match(request.effective_query)
                            _cache_fallback = await _LAC_FB(_fb_pool).get(
                                _session_factory_id, _norm_q_fb, upload_id
                            )
                    except Exception as _fb_err:
                        logger.warning(f"[stream] cache fallback lookup failed: {_fb_err}")

                if _cache_fallback:
                    # Use cached answer instead of timeout message.
                    _fb_text = _cache_fallback["answer_text"]
                    _fb_note = (
                        "\n\n*(本次 AI 思考超时, 已显示 24 小时内对相同问题的历史回答. "
                        "如需最新分析请稍后重试或换种问法.)*"
                    )
                    full_text = _fb_text + _fb_note
                    # FE may have already received a partial chunk — emit a
                    # banner first to indicate the swap, then the cached text.
                    yield _sse_event("chunk", "\n\n---\n\n")  # visual separator
                    yield _sse_event("chunk", _fb_text)
                    yield _sse_event("chunk", _fb_note)
                    logger.info(
                        f"[stream] truncated LLM → cache fallback served "
                        f"(query='{request.effective_query[:30]}', "
                        f"cached_hits={_cache_fallback['hit_count']})"
                    )
                    if _cache_fallback.get("charts"):
                        charts_to_inject = _cache_fallback["charts"]
                    else:
                        charts_to_inject = None
                    # Override charts later in the flow with cached charts.
                    if charts_to_inject:
                        # Stash for use after chart-build block.
                        _cached_charts_override = charts_to_inject
                elif _silent_timeout:
                    # LLM produced 0 chars and no cache fallback — actionable msg.
                    _silent_msg = (
                        "💡 AI 思考超时(>25 秒), 可能问题过于复杂或当前模型负载高.\n\n"
                        "**建议**: 精简问题或换种问法 (例如用具体数字/时间范围限定) 后重试."
                    )
                    full_text = _silent_msg
                    yield _sse_event("chunk", _silent_msg)
                else:
                    # Have partial answer — append truncation note.
                    _trunc_note = "\n\n*(分析超过 25 秒已截断, 可重问获取完整回答)*"
                    full_text += _trunc_note
                    yield _sse_event("chunk", _trunc_note)

            # ── Build charts in parallel (non-blocking) ──
            charts = []
            try:
                # Bug #24 (Apr 17 2026): prepend DB-aggregated Top-5 chart so user
                # sees accurate Top-N even if sample-based chart builder misfires
                # (e.g., label_field picks a column with nulls in first 50 rows).
                if 'top5_by_dim' in locals() and top5_by_dim and 'primary_measure' in locals() and primary_measure:
                    # Bug #346 fix (Apr 20 2026, Layer A4 extension): pick the
                    # first dim with at least 2 distinct labels. Single-store
                    # uploads (cardinality=1 on 门店名称) would otherwise render
                    # a meaningless single-bar "Top 5 门店名称" chart — the exact
                    # case Layer A4 gate blocks in chart_recommender, now also
                    # guarded here in the chat streaming path.
                    primary_dim = None
                    top5 = None
                    for _dim, _t5 in top5_by_dim.items():
                        if len(_t5) >= 2:
                            primary_dim = _dim
                            top5 = _t5
                            break
                    if primary_dim is None or not top5:
                        # All dims have ≤1 distinct value — skip the Top-N chart
                        # entirely. The answer text still carries the analysis;
                        # caller can rely on `charts_extra` below for alt views.
                        logger.info(
                            f"[chart-gate] Skipping Top-5 chart — no dim has "
                            f">=2 distinct values (dims tried: {list(top5_by_dim.keys())})"
                        )
                        primary_dim = None  # skip the append below
                if 'top5_by_dim' in locals() and top5_by_dim and 'primary_measure' in locals() and primary_measure and primary_dim:  # noqa: E501
                    charts.append({
                        "type": "bar",
                        "title": f"Top 5 {primary_dim} (按 {primary_measure})",
                        "option": {
                            "title": {"text": f"Top 5 {primary_dim}", "left": "center"},
                            "xAxis": {
                                "type": "category",
                                "data": [t["label"] for t in top5],
                                "axisLabel": {"rotate": 30, "overflow": "truncate", "width": 120},
                            },
                            "yAxis": {"type": "value", "name": primary_measure},
                            "series": [{
                                "name": primary_measure,
                                "type": "bar",
                                "data": [t["total"] for t in top5],
                                "label": {"show": True, "position": "top"},
                            }],
                            "tooltip": {"trigger": "axis"},
                            "grid": {"left": "3%", "right": "4%", "bottom": "3%", "containLabel": True},
                        }
                    })
                charts_extra = _build_charts_for_query(query, df, data)
                if charts_extra:
                    charts.extend(charts_extra)
                # (debug logging removed after Bug #20/#24 verified)
            except Exception as chart_err:
                logger.warning(f"[stream] Chart generation failed: {chart_err}")

            # G2: if cache fallback was served above, prefer cached charts
            # over freshly built ones (the cached ones matched the cached
            # answer text).
            if '_cached_charts_override' in locals() and _cached_charts_override:
                charts = _cached_charts_override

            if charts:
                yield _sse_event("charts", charts)

            # Phase 1 (Apr 23 2026): fire-and-forget log of this LLM fallback.
            # Captured agg_meta mirrors what went into the LLM prompt so Phase 2
            # clustering can re-run the same slice later. Logging must not block
            # the user's answer — 2s soft timeout, exception swallowed.
            _log_id = None
            try:
                from smartbi.services.llm_fallback_logger import (
                    LlmFallbackLogPayload, log_fallback,
                )
                from smartbi.config import get_pg_pool as _get_pg_pool_log

                _log_pool = await _get_pg_pool_log()
                _history = None
                if request.context and isinstance(request.context, dict):
                    _h = request.context.get("history")
                    if isinstance(_h, list):
                        _history = _h
                _factory_for_log = trusted_factory_id
                _upload_for_log = None
                try:
                    if request.sheet_id:
                        _upload_for_log = int(request.sheet_id)
                except (ValueError, TypeError):
                    pass
                _log_payload = LlmFallbackLogPayload(
                    query=request.effective_query,
                    factory_id=_factory_for_log,
                    upload_id=_upload_for_log,
                    answer=full_text,
                    agg_meta={
                        "agg_lines_count": len(agg_lines) if "agg_lines" in locals() else 0,
                        "primary_measure": primary_measure if "primary_measure" in locals() else None,
                        "has_history": bool(_history),
                    },
                    history=_history,
                    total_wall_ms=int((time.time() - start_time) * 1000),
                    llm_wall_ms=int((time.time() - start_time) * 1000),
                )
                # Anchor task in materialized_analytics._PENDING_BG_TASKS to
                # survive function return. Python's event loop only keeps weak
                # refs to tasks — without this, the task can be GC'd mid-write
                # after the 2s wait_for timeout. Same bug class as Apr 23 2026
                # reclassify warm.
                from smartbi.api.materialized_analytics import _spawn_bg
                _log_task = _spawn_bg(log_fallback(_log_pool, _log_payload))
                try:
                    _log_id = await asyncio.wait_for(asyncio.shield(_log_task), timeout=2.0)
                except Exception:
                    _log_id = None
            except Exception as log_err:
                # WARNING (not DEBUG) because this fires when DashScope or DB
                # is down — we want these in monitoring, not silent.
                logger.warning(f"[stream] fallback log skipped: {log_err}")

            # P2 guardrail: flag numeric hallucinations that slipped past the
            # prompt constraint. E.g., qhj_prod upload 4169 previously got
            # "Top 5 合计 3.4 亿元" on a 36M dataset. We can't unspeak the
            # streamed text, but we can carry a warning in the done payload
            # so FE can surface it and ops sees it in the log.
            _guard_warning = None
            try:
                _agg_for_guard = agg_lines if "agg_lines" in locals() else None
                _guard_warning = detect_numeric_hallucination(full_text, _agg_for_guard)
                if _guard_warning:
                    logger.warning(f"[stream] {_guard_warning}")
            except Exception as guard_err:
                logger.warning(f"[stream] numeric guard check failed: {guard_err}")

            # v4 B2-B (Apr 26 2026): write LLM answer to cache BEFORE done
            # event — async generator gets cancelled by client right after
            # yield done, so any code after yield never runs. _spawn_bg
            # schedules background task which keeps running after this
            # generator closes (anchored in _PENDING_BG_TASKS).
            if (request.allow_tenant_data_fallback
                    and not chat_session_parent and _session_factory_id
                    and full_text and not _llm_truncated):
                try:
                    from smartbi.services.llm_answer_cache import LlmAnswerCache as _LAC
                    from smartbi.services.query_normalizer import normalize_for_match
                    from smartbi.api.materialized_analytics import _spawn_bg as _spawn_lac
                    _lac_w_pool = await get_pg_pool()
                    if _lac_w_pool is not None:
                        _normalized_q_w = normalize_for_match(request.effective_query or "")
                        _spawn_lac(_LAC(_lac_w_pool).set(
                            factory_id=_session_factory_id,
                            normalized_q=_normalized_q_w,
                            upload_id=upload_id,
                            answer_text=full_text,
                            charts=charts,
                            warning=_guard_warning,
                        ))
                except Exception as _lac_w_err:
                    logger.warning(f"[llm-cache] writeback failed (non-fatal): {_lac_w_err}")

            # Apr 26 2026 v2 conversation memory: write back parent context
            # BEFORE done event for the same reason as cache write above.
            if _session_can_persist and full_text:
                try:
                    from smartbi.services.chat_session_service import ChatSessionService
                    from smartbi.config import get_pg_pool as _get_pool_writeback
                    from smartbi.api.materialized_analytics import _spawn_bg
                    _wb_pool = await _get_pool_writeback()
                    if _wb_pool is not None:
                        _wb_svc = ChatSessionService(_wb_pool)
                        _wb_upload = None
                        try:
                            if request.sheet_id:
                                _wb_upload = int(request.sheet_id)
                        except (ValueError, TypeError):
                            pass
                        _spawn_bg(_wb_svc.upsert(
                            session_id=request.session_id,
                            factory_id=_session_factory_id,
                            parent_query=request.effective_query or "",
                            parent_answer_summary=full_text,
                            parent_template_code=None,  # LLM path, no template
                            parent_upload_id=_wb_upload,
                            user_id=_session_user_id,  # H2 binding
                        ))
                except Exception as e:
                    logger.warning(f"[chat-session] writeback (LLM path) failed: {e}")

            # Distillation capture (training corpus): freshly-streamed LLM
            # answer on the with-data main path. Gated by the SAME condition the
            # LLM-answer-cache writeback uses above (full_text and not
            # _llm_truncated) so we ONLY record a genuine complete fresh teacher
            # pair. This excludes every degraded answer: silent-timeout, soft-
            # timeout truncation, and the cache-fallback swap all set
            # _llm_truncated=True. Cache-serve branches (gold-ops /
            # materialized_cache / llm_answer_cache / capability_short_circuit)
            # return earlier and never reach here. Fire-and-forget — the helper
            # swallows all exceptions and never delays the stream.
            if full_text and not _llm_truncated:
                await _capture_qa_distillation(
                    request.effective_query, full_text, http_request
                )

            # Done event MUST be yielded last — async generator gets cancelled
            # by client immediately after the done payload arrives, so any
            # write-back code after this point would never run. Cache writes
            # + chat-session upsert above use _spawn_bg to schedule background
            # tasks anchored in _PENDING_BG_TASKS, so they survive generator
            # cancellation.
            yield _sse_event("done", {
                "success": True,
                "answer": full_text,
                "charts": charts,
                "log_id": _log_id,
                "warning": _guard_warning,
                "processingTimeMs": int((time.time() - start_time) * 1000)
            })

        except Exception as e:
            logger.error(f"[stream] General analysis stream failed: {e}", exc_info=True)
            yield _sse_event("error", "AI对话处理失败，请稍后重试")

    return StreamingResponse(
        event_stream(),
        media_type="text/event-stream",
        headers={
            "Cache-Control": "no-cache",
            "Connection": "keep-alive",
            "X-Accel-Buffering": "no",
            "Content-Encoding": "identity",  # Bypass GZip middleware buffering
        }
    )


def _sse_event(event: str, data) -> str:
    """Format a single SSE event. JSON-encodes data for consistent frontend parsing."""
    payload = _json.dumps(data, ensure_ascii=False, default=str)
    return f"event: {event}\ndata: {payload}\n\n"


async def _stream_llm_response(
    system_prompt: str,
    user_prompt: str,
    max_tokens: int = 1500,
    temperature: float = 0.2,
) -> AsyncGenerator[str, None]:
    """
    Shared helper: calls InsightGenerator._call_llm_stream_text and yields SSE-formatted strings.

    Yields:
        SSE chunk events as text chunks arrive from the LLM.
        Caller is responsible for sending the final "done" and "error" events.
    """
    insight_gen = InsightGenerator()
    async for chunk in insight_gen._call_llm_stream_text(
        user_prompt, system_prompt, max_tokens=max_tokens, temperature=temperature
    ):
        yield chunk


def _build_analysis_ctx(query: str, context: Optional[Dict[str, Any]]) -> str:
    """Build the analysis_ctx string that gets injected into the LLM prompt.

    Handles conversation history in context.history (Fix 2, Apr 23 2026):
    FE buffers last 3 Q+A and passes them so LLM can resolve pronouns
    like "这个月" / "它" / "那家" back to specific entities from the
    previous turn.

    Falls back to the original `{query}\\n补充信息: {context}` stringify
    when context is not dict-shaped or has no history key.
    """
    if not context:
        return query
    if isinstance(context, dict):
        history = context.get("history")
        if isinstance(history, list) and history:
            # Keep the last 6 turns (3 Q+A pairs) to balance context vs token cost
            history_lines: List[str] = []
            for msg in history[-6:]:
                if not isinstance(msg, dict):
                    continue
                role = msg.get("role") or msg.get("from")
                content = str(msg.get("content") or msg.get("text") or "").strip()
                if not content:
                    continue
                # Cap each message to 400 chars so a long previous answer
                # doesn't blow out the prompt.
                if len(content) > 400:
                    content = content[:400] + "..."
                if role in ("user", "human"):
                    history_lines.append(f"[前一轮用户问]: {content}")
                elif role in ("assistant", "ai", "bot"):
                    history_lines.append(f"[前一轮我答]: {content}")
            if history_lines:
                return (
                    "之前对话历史 (供指代消解, 请据此理解 '这个月'/'它'/'那家' 等指代):\n"
                    + "\n".join(history_lines)
                    + f"\n\n当前用户问题: {query}"
                )
    # Legacy behavior for non-history context
    return f"{query}\n补充信息: {context}"


@router.post("/drill-down-stream")
async def drill_down_stream(request: DrillDownRequest, http_request: Request):
    """
    SSE streaming version of drill_down.

    Sends events:
      - {"event": "status", "data": "..."} — progress updates
      - {"event": "chunk", "data": "..."} — LLM text chunks
      - {"event": "done", "data": {...}} — final result summary
      - {"event": "error", "data": "..."} — on failure

    Skips InsightCache (streaming responses are not cached).
    """

    trusted_factory_id = _require_trusted_factory_id(http_request)
    await _require_owned_upload_id(request.sheet_id, trusted_factory_id)

    async def event_stream() -> AsyncGenerator[str, None]:
        start_time = time.time()
        # H4: v2 conv memory phase 0
        v2_parent, v2_factory, v2_user = await _v2_conv_lookup(http_request, request.session_id)
        try:
            yield _sse_event("status", "正在加载数据...")

            data = request.data
            if not data:
                data = get_sheet_data(trusted_factory_id, request.sheet_id)

            if not data:
                yield _sse_event("done", {
                    "success": False,
                    "error": f"No data found for sheet {request.sheet_id}",
                    "processingTimeMs": int((time.time() - start_time) * 1000)
                })
                return

            import pandas as pd
            df = coerce_numeric_columns(pd.DataFrame(data))

            if request.dimension not in df.columns:
                available = df.columns.tolist()
                yield _sse_event("done", {
                    "success": False,
                    "error": f"Dimension '{request.dimension}' not found. Available: {available}",
                    "processingTimeMs": int((time.time() - start_time) * 1000)
                })
                return

            valid_measures = [m for m in request.measures if m in df.columns]
            if not valid_measures:
                valid_measures = df.select_dtypes(include=['number']).columns.tolist()

            if not valid_measures:
                yield _sse_event("done", {
                    "success": False,
                    "error": "未检测到数值型字段，无法进行分析",
                    "processingTimeMs": int((time.time() - start_time) * 1000)
                })
                return

            # Build a concise data summary for LLM streaming
            filter_desc = f"筛选条件: {request.dimension}={request.filter_value}" if request.filter_value else f"维度: {request.dimension}"  # noqa: E501
            measures_desc = "、".join(valid_measures[:3])
            sample_rows = data[:10]
            data_preview = _json.dumps(sample_rows, ensure_ascii=False, default=str)[:800]

            system_prompt = (
                "你是食品企业的数据分析师。请用中文Markdown回答，300字以内，引用具体数字，给出可执行建议。"
                + NUMERIC_GUARD_CLAUSE
                + LABELING_GUARD_CLAUSE
                + ACTION_REC_GUARD_CLAUSE
                + USER_FRIENDLY_TONE_CLAUSE
            )
            user_prompt = f"""请对以下维度拆分数据进行分析：
{filter_desc}
指标: {measures_desc}
数据样本（前10行）:
{data_preview}

请总结各维度的表现，找出异常点，并给出业务建议。"""
            # H4: inject v2 parent context if session has prior turn
            user_prompt = _v2_inject_context(v2_parent, user_prompt)

            yield _sse_event("status", "正在分析...")

            full_text = ""
            async for chunk in _stream_llm_response(system_prompt, user_prompt, max_tokens=1200, temperature=0.2):
                if await http_request.is_disconnected():
                    logger.info("[drill-down-stream] Client disconnected, stopping")
                    return
                full_text += chunk
                yield _sse_event("chunk", chunk)

            # H4: write back BEFORE done event (async generator gets cancelled after done)
            _v2_writeback_bg(
                request.session_id, v2_factory, v2_user,
                query=f"drill-down: {filter_desc}",
                answer=full_text,
                upload_id=int(request.sheet_id) if request.sheet_id and request.sheet_id.isdigit() else None,
            )

            yield _sse_event("done", {
                "success": True,
                "answer": full_text,
                "dimension": request.dimension,
                "filter_value": request.filter_value,
                "processingTimeMs": int((time.time() - start_time) * 1000)
            })

        except Exception as e:
            logger.error(f"[drill-down-stream] Failed: {e}", exc_info=True)
            yield _sse_event("error", "AI对话处理失败，请稍后重试")

    return StreamingResponse(
        event_stream(),
        media_type="text/event-stream",
        headers={
            "Cache-Control": "no-cache",
            "Connection": "keep-alive",
            "X-Accel-Buffering": "no",
            "Content-Encoding": "identity",
        }
    )


@router.post("/root-cause-stream")
async def root_cause_stream(request: RootCauseRequest, http_request: Request):
    """
    SSE streaming version of root_cause.

    Sends events:
      - {"event": "status", "data": "..."} — progress updates
      - {"event": "chunk", "data": "..."} — LLM text chunks
      - {"event": "done", "data": {...}} — final result summary
      - {"event": "error", "data": "..."} — on failure

    Skips InsightCache (streaming responses are not cached).
    """

    trusted_factory_id = _require_trusted_factory_id(http_request)
    await _require_owned_upload_id(request.sheet_id, trusted_factory_id)

    async def event_stream() -> AsyncGenerator[str, None]:
        start_time = time.time()
        # H4: v2 conv memory phase 0
        v2_parent, v2_factory, v2_user = await _v2_conv_lookup(http_request, request.session_id)
        try:
            yield _sse_event("status", "正在加载数据...")

            data = request.data
            if not data:
                data = get_sheet_data(trusted_factory_id, request.sheet_id)

            if not data:
                yield _sse_event("done", {
                    "success": False,
                    "error": f"No data found for sheet {request.sheet_id}",
                    "kpi": request.kpi,
                    "processingTimeMs": int((time.time() - start_time) * 1000)
                })
                return

            import pandas as pd
            df = coerce_numeric_columns(pd.DataFrame(data))

            if request.kpi not in df.columns:
                yield _sse_event("done", {
                    "success": False,
                    "error": f"KPI '{request.kpi}' not found in data",
                    "kpi": request.kpi,
                    "processingTimeMs": int((time.time() - start_time) * 1000)
                })
                return

            # Compute basic correlations to enrich the LLM prompt
            numeric_cols = df.select_dtypes(include=['number']).columns.tolist()
            other_cols = [c for c in numeric_cols if c != request.kpi]
            corr_summary_lines = []
            kpi_values = df[request.kpi]
            for col in other_cols[:6]:
                try:
                    corr = kpi_values.corr(df[col])
                    if abs(corr) > request.threshold:
                        direction = "正相关" if corr > 0 else "负相关"
                        corr_summary_lines.append(f"- {col}: 相关系数 {corr:.3f}（{direction}）")
                except Exception:
                    continue
            corr_text = "\n".join(corr_summary_lines) if corr_summary_lines else "未发现显著相关因素"

            kpi_stats = df[request.kpi].describe()
            stats_text = (
                f"均值={kpi_stats.get('mean', 0):.2f}, "
                f"最大={kpi_stats.get('max', 0):.2f}, "
                f"最小={kpi_stats.get('min', 0):.2f}, "
                f"标准差={kpi_stats.get('std', 0):.2f}"
            )

            system_prompt = (
                "你是食品企业的数据分析师。请用中文Markdown分析KPI变动的根本原因，300字以内，给出可执行建议。"
                + NUMERIC_GUARD_CLAUSE
                + LABELING_GUARD_CLAUSE
                + ACTION_REC_GUARD_CLAUSE
                + USER_FRIENDLY_TONE_CLAUSE
            )
            user_prompt = f"""请分析 KPI「{request.kpi}」变动的根本原因：

KPI统计: {stats_text}

与{request.kpi}的相关因素:
{corr_text}

请结合以上数据，给出根因分析和改进建议。"""
            # H4: inject v2 parent context if session has prior turn
            user_prompt = _v2_inject_context(v2_parent, user_prompt)

            yield _sse_event("status", "正在分析根本原因...")

            full_text = ""
            async for chunk in _stream_llm_response(system_prompt, user_prompt, max_tokens=1200, temperature=0.2):
                if await http_request.is_disconnected():
                    logger.info("[root-cause-stream] Client disconnected, stopping")
                    return
                full_text += chunk
                yield _sse_event("chunk", chunk)

            # H4: write back BEFORE done event
            _v2_writeback_bg(
                request.session_id, v2_factory, v2_user,
                query=f"root-cause: {request.kpi}",
                answer=full_text,
                upload_id=int(request.sheet_id) if request.sheet_id and request.sheet_id.isdigit() else None,
            )

            yield _sse_event("done", {
                "success": True,
                "kpi": request.kpi,
                "answer": full_text,
                "processingTimeMs": int((time.time() - start_time) * 1000)
            })

        except Exception as e:
            logger.error(f"[root-cause-stream] Failed: {e}", exc_info=True)
            yield _sse_event("error", "AI对话处理失败，请稍后重试")

    return StreamingResponse(
        event_stream(),
        media_type="text/event-stream",
        headers={
            "Cache-Control": "no-cache",
            "Connection": "keep-alive",
            "X-Accel-Buffering": "no",
            "Content-Encoding": "identity",
        }
    )


@router.post("/benchmark-stream")
async def benchmark_stream(request: BenchmarkRequest, http_request: Request):
    """
    SSE streaming version of benchmark.

    Sends events:
      - {"event": "status", "data": "..."} — progress updates
      - {"event": "chunk", "data": "..."} — LLM text chunks
      - {"event": "done", "data": {...}} — final result summary
      - {"event": "error", "data": "..."} — on failure

    Skips InsightCache (streaming responses are not cached).
    """

    trusted_factory_id = _require_trusted_factory_id(http_request)
    await _require_owned_upload_id(request.sheet_id, trusted_factory_id)

    async def event_stream() -> AsyncGenerator[str, None]:
        start_time = time.time()
        # H4: v2 conv memory phase 0
        v2_parent, v2_factory, v2_user = await _v2_conv_lookup(http_request, request.session_id)
        try:
            yield _sse_event("status", "正在加载行业基准数据...")

            # Resolve industry name for display
            industry_display_map = {
                "food_processing": "食品加工",
                "food": "食品加工",
                "食品加工": "食品加工",
                "食品": "食品加工",
                "retail": "零售",
                "零售": "零售",
                "manufacturing": "制造",
                "制造": "制造",
            }
            industry_label = industry_display_map.get(request.industry.lower(), request.industry)

            # Apply optional metric mapping for display
            metrics_display = {}
            for k, v in request.metrics.items():
                display_key = (request.metric_mapping or {}).get(k, k)
                metrics_display[display_key] = v

            metrics_text = "\n".join(f"- {k}: {v}" for k, v in metrics_display.items())

            system_prompt = (
                "你是食品企业的数据分析师。请用中文Markdown对比企业指标与行业基准，300字以内，指出差距并给出改进建议。"
                + NUMERIC_GUARD_CLAUSE
                + LABELING_GUARD_CLAUSE
                + ACTION_REC_GUARD_CLAUSE
                + USER_FRIENDLY_TONE_CLAUSE
            )
            user_prompt = f"""请分析企业指标与{industry_label}行业基准的差距：

企业当前指标:
{metrics_text}

请根据行业通行标准，评估各指标所处水平（优秀/良好/一般/偏低），并给出针对性的改进建议。"""
            # H4: inject v2 parent context if session has prior turn
            user_prompt = _v2_inject_context(v2_parent, user_prompt)

            yield _sse_event("status", "正在对标分析...")

            full_text = ""
            async for chunk in _stream_llm_response(system_prompt, user_prompt, max_tokens=1200, temperature=0.2):
                if await http_request.is_disconnected():
                    logger.info("[benchmark-stream] Client disconnected, stopping")
                    return
                full_text += chunk
                yield _sse_event("chunk", chunk)

            # H4: write back BEFORE done event
            _v2_writeback_bg(
                request.session_id, v2_factory, v2_user,
                query=f"benchmark: {industry_label}",
                answer=full_text,
                upload_id=int(request.sheet_id) if request.sheet_id and request.sheet_id.isdigit() else None,
            )

            yield _sse_event("done", {
                "success": True,
                "industry": request.industry,
                "answer": full_text,
                "processingTimeMs": int((time.time() - start_time) * 1000)
            })

        except Exception as e:
            logger.error(f"[benchmark-stream] Failed: {e}", exc_info=True)
            yield _sse_event("error", "AI对话处理失败，请稍后重试")

    return StreamingResponse(
        event_stream(),
        media_type="text/event-stream",
        headers={
            "Cache-Control": "no-cache",
            "Connection": "keep-alive",
            "X-Accel-Buffering": "no",
            "Content-Encoding": "identity",
        }
    )


@router.post("/multi-dimension-stream")
async def multi_dimension_analysis_stream(request: MultiDimensionRequest, http_request: Request):
    """
    SSE streaming version of multi_dimension_analysis.

    Sends events:
      - {"event": "status", "data": "..."} — progress updates
      - {"event": "chunk", "data": "..."} — LLM text chunks
      - {"event": "done", "data": {...}} — final result summary
      - {"event": "error", "data": "..."} — on failure

    Skips InsightCache (streaming responses are not cached).
    """

    trusted_factory_id = _require_trusted_factory_id(http_request)
    await _require_owned_upload_id(request.sheet_id, trusted_factory_id)

    async def event_stream() -> AsyncGenerator[str, None]:
        start_time = time.time()
        try:
            yield _sse_event("status", "正在分析多维度数据...")

            import pandas as pd
            df = pd.DataFrame(request.data)

            # Summarise data for the LLM prompt
            numeric_cols = df.select_dtypes(include=['number']).columns.tolist()
            non_numeric_cols = [c for c in df.columns if c not in numeric_cols]  # noqa: F841

            stats_lines = []
            for col in numeric_cols[:5]:
                try:
                    s = df[col].describe()
                    stats_lines.append(
                        f"- {col}: 均值={s.get('mean', 0):.2f}, 最大={s.get('max', 0):.2f}, 最小={s.get('min', 0):.2f}"
                    )
                except Exception:
                    continue
            stats_text = "\n".join(stats_lines) if stats_lines else "（无数值列统计）"

            sample_rows = request.data[:8]
            data_preview = _json.dumps(sample_rows, ensure_ascii=False, default=str)[:600]

            # Focus dimensions hint
            dims_hint = ""
            if request.dimensions:
                dim_label_map = {
                    "what_happened": "发生了什么（描述性）",
                    "why_happened": "为什么发生（诊断性）",
                    "forecast": "预测走势",
                    "recommendation": "建议行动",
                    "anomaly": "异常检测",
                }
                dim_labels = [dim_label_map.get(d, d) for d in request.dimensions]
                dims_hint = f"\n请重点分析以下维度: {', '.join(dim_labels)}"

            context_hint = ""
            if request.context:
                context_hint = f"\n背景信息: {_json.dumps(request.context, ensure_ascii=False, default=str)}"

            system_prompt = (
                "你是食品企业的数据分析师。请用中文Markdown进行多维度分析，400字以内，结构清晰，引用数字，给出可执行建议。"
                + NUMERIC_GUARD_CLAUSE
                + LABELING_GUARD_CLAUSE
                + ACTION_REC_GUARD_CLAUSE
                + USER_FRIENDLY_TONE_CLAUSE
            )
            user_prompt = f"""请对以下数据进行多维度分析：{dims_hint}{context_hint}

数值列统计:
{stats_text}

数据样本（前8行）:
{data_preview}

请按照「发生了什么 → 为什么 → 预测 → 建议」结构输出分析。"""

            yield _sse_event("status", "正在生成多维度洞察...")

            full_text = ""
            async for chunk in _stream_llm_response(system_prompt, user_prompt, max_tokens=1500, temperature=0.2):
                if await http_request.is_disconnected():
                    logger.info("[multi-dimension-stream] Client disconnected, stopping")
                    return
                full_text += chunk
                yield _sse_event("chunk", chunk)

            yield _sse_event("done", {
                "success": True,
                "answer": full_text,
                "dimensions": request.dimensions,
                "processingTimeMs": int((time.time() - start_time) * 1000)
            })

        except Exception as e:
            logger.error(f"[multi-dimension-stream] Failed: {e}", exc_info=True)
            yield _sse_event("error", "AI对话处理失败，请稍后重试")

    return StreamingResponse(
        event_stream(),
        media_type="text/event-stream",
        headers={
            "Cache-Control": "no-cache",
            "Connection": "keep-alive",
            "X-Accel-Buffering": "no",
            "Content-Encoding": "identity",
        }
    )


def _build_charts_for_query(query: str, df, data: list) -> List[Dict[str, Any]]:
    """Build charts based on the query keywords — extracted to share between stream and non-stream endpoints."""
    import re as _re
    from services.chart_builder import ChartBuilder
    builder = ChartBuilder()

    _COLUMN_NAME_MAP = {
        'actual_amount': '实际金额', 'budget_amount': '预算金额',
        'total_amount': '总金额', 'net_profit': '净利润',
        'gross_profit': '毛利润', 'revenue': '营收',
        'cost': '成本', 'expense': '费用', 'sales': '销售额',
        'quantity': '数量', 'price': '单价', 'margin': '利润率',
        'growth_rate': '增长率', 'total': '合计',
    }

    def _humanize_col(name: str) -> str:
        if not name:
            return name
        if _re.match(r'^[Cc]olumn[_\s]?\d+$', name):
            idx = name.split('_')[-1] if '_' in name else name[-1]
            return f"数据列{idx}"
        m = _re.match(r'^(\d{4})-(\d{1,2})-\d{1,2}$', name)
        if m:
            return f"{int(m.group(2))}月"
        m = _re.match(r'^(\d{4})-(\d{1,2})-\d{1,2}[_\s](.+)$', name)
        if m:
            return f"{int(m.group(2))}月{m.group(3)}"
        lower = name.lower().replace(' ', '_')
        if lower in _COLUMN_NAME_MAP:
            return _COLUMN_NAME_MAP[lower]
        if '_' in name and all(c.isascii() for c in name):
            return name.replace('_', ' ').title()
        return name

    _INDEX_COL_PATTERNS = {'行次', '序号', '编号', '行号', '项目编号', 'index', 'no', 'no.', 'id', 'row_num', 'row_number', 'sn'}
    _ID_NAME_FRAGMENTS = ['订单号', '单号', '编码', '工号', '货号', '票号', '凭证号',
                          'order_id', 'order_no', 'item_id', 'sku_id', 'batch_no']

    def _is_index_column(col_name: str, series) -> bool:
        import pandas as pd
        lower = col_name.lower().strip()
        if lower in _INDEX_COL_PATTERNS:
            return True
        if any(frag in lower for frag in _ID_NAME_FRAGMENTS):
            return True
        try:
            vals = pd.to_numeric(series.dropna().head(20), errors='coerce').dropna()
            if len(vals) >= 3:
                diffs = vals.diff().dropna()
                if len(diffs) > 0 and all(d == 1 for d in diffs):
                    return True
                if vals.nunique() == len(vals) and vals.min() > 1000 and all(v == int(v) for v in vals):
                    return True
        except Exception:
            pass
        return False

    def _humanize_echart_option(echart_option: dict) -> dict:
        if not echart_option:
            return echart_option
        opt = dict(echart_option)
        if 'legend' in opt and isinstance(opt['legend'], dict):
            leg_data = opt['legend'].get('data', [])
            if isinstance(leg_data, list):
                opt['legend'] = {**opt['legend'], 'data': [_humanize_col(str(d)) for d in leg_data]}
        if 'series' in opt and isinstance(opt['series'], list):
            new_series = []
            for s in opt['series']:
                if isinstance(s, dict) and 'name' in s:
                    new_series.append({**s, 'name': _humanize_col(str(s['name']))})
                else:
                    new_series.append(s)
            opt['series'] = new_series
        if 'title' in opt and isinstance(opt['title'], dict):
            t = opt['title'].get('text', '')
            if t:
                for raw_col in list(df.columns):
                    h = _humanize_col(raw_col)
                    if h != raw_col and raw_col in t:
                        t = t.replace(raw_col, h)
                opt['title'] = {**opt['title'], 'text': t}
        return opt

    def _extract_echart_option(chart_result: dict, chart_type: str, title: str):
        if not chart_result or not chart_result.get("success"):
            return None
        echart_option = chart_result.get("config", {})
        if not echart_option:
            return None
        echart_option = _humanize_echart_option(echart_option)
        return {
            "type": chart_type,
            "title": title,
            "option": _sanitize_for_json(echart_option)
        }

    numeric_cols = df.select_dtypes(include=['number']).columns.tolist()
    non_numeric_cols = [c for c in df.columns if c not in numeric_cols]

    numeric_cols = [c for c in numeric_cols if not _is_index_column(c, df[c])]
    non_numeric_cols = [c for c in non_numeric_cols if not _is_index_column(c, df[c])]

    _column_xx_pat = _re.compile(r'^[Cc]olumn[_\s]?\d+$')
    named_numeric = [c for c in numeric_cols if not _column_xx_pat.match(c)]
    unnamed_numeric = [c for c in numeric_cols if _column_xx_pat.match(c)]
    numeric_cols = named_numeric + unnamed_numeric[:2] if named_numeric else unnamed_numeric[:5]

    label_field = None
    for col in non_numeric_cols:
        sample = df[col].dropna().head(10).astype(str)
        has_text = any(len(v) > 1 and not v.replace('.', '').replace('-', '').isdigit() for v in sample)
        if has_text:
            label_field = col
            break
    if not label_field and non_numeric_cols:
        label_field = non_numeric_cols[0]

    chart_data = data[:50] if len(data) > 50 else data
    charts: List[Dict[str, Any]] = []

    if "趋势" in query or "变化" in query:
        if numeric_cols:
            y_cols = numeric_cols[:3]
            h_names = '、'.join(_humanize_col(c) for c in y_cols[:2])
            chart_result = builder.build("line", chart_data, x_field=label_field, y_fields=y_cols, title=f"{h_names}趋势分析")  # noqa: E501
            chart_entry = _extract_echart_option(chart_result, "line", f"{h_names}趋势")
            if chart_entry:
                charts.append(chart_entry)
    elif "对比" in query or "比较" in query or "排名" in query:
        if numeric_cols and label_field:
            y_cols = numeric_cols[:2]
            h_names = '、'.join(_humanize_col(c) for c in y_cols[:2])
            chart_result = builder.build("bar", chart_data, x_field=label_field, y_fields=y_cols, title=f"{h_names}对比分析")  # noqa: E501
            chart_entry = _extract_echart_option(chart_result, "bar", f"{h_names}对比")
            if chart_entry:
                charts.append(chart_entry)
    elif "占比" in query or "构成" in query or "分布" in query:
        if numeric_cols and label_field:
            h_name = _humanize_col(numeric_cols[0])
            chart_result = builder.build("pie", chart_data, x_field=label_field, y_fields=[numeric_cols[0]], title=f"{h_name}占比分析")  # noqa: E501
            chart_entry = _extract_echart_option(chart_result, "pie", f"{h_name}占比")
            if chart_entry:
                charts.append(chart_entry)

    if not charts and numeric_cols and label_field:
        y_cols = numeric_cols[:3]
        chart_result = builder.build("bar", chart_data, x_field=label_field, y_fields=y_cols, title="数据概览")
        chart_entry = _extract_echart_option(chart_result, "bar", "数据概览")
        if chart_entry:
            charts.append(chart_entry)

    return charts


@router.post("/multi-dimension", response_model=MultiDimensionResponse)
async def multi_dimension_analysis(
    request: MultiDimensionRequest,
    http_request: Request,
) -> MultiDimensionResponse:
    """
    Perform multi-dimensional insight analysis.

    Generates insights across multiple dimensions:
    - What happened (descriptive)
    - Why it happened (diagnostic)
    - What will happen (predictive)
    - What to do (prescriptive)

    Args:
        request: MultiDimensionRequest with data and optional dimensions

    Returns:
        MultiDimensionResponse with comprehensive insights
    """
    start_time = time.time()

    trusted_factory_id = _require_trusted_factory_id(http_request)
    await _require_owned_upload_id(request.sheet_id, trusted_factory_id)

    # Cache lookup
    cache_key = _make_chat_cache_key(
        "multi_dimension",
        factory_id=trusted_factory_id,
        sheet_id=request.sheet_id,
        dimensions=request.dimensions,
        data=request.data,
    )
    cached = _chat_cache_get(cache_key)
    if cached is not None:
        cached["processing_time_ms"] = 0
        return MultiDimensionResponse(**cached)

    try:
        import pandas as pd
        df = pd.DataFrame(request.data)

        # Parse dimensions
        focus_dims = None
        if request.dimensions:
            focus_dims = []
            dim_map = {
                "what_happened": InsightDimension.WHAT_HAPPENED,
                "why_happened": InsightDimension.WHY_HAPPENED,
                "forecast": InsightDimension.FORECAST,
                "recommendation": InsightDimension.RECOMMENDATION,
                "anomaly": InsightDimension.ANOMALY
            }
            for d in request.dimensions:
                if d in dim_map:
                    focus_dims.append(dim_map[d])

        # Perform analysis
        analyzer = InsightDimensionAnalyzer()
        report: InsightReport = analyzer.analyze(
            df,
            context=request.context,
            focus_dimensions=focus_dims
        )

        response = MultiDimensionResponse(
            success=True,
            executive_summary=report.executive_summary,
            insights=[i.to_dict() for i in report.insights],
            risk_alerts=[i.to_dict() for i in report.risk_alerts],
            opportunities=[i.to_dict() for i in report.opportunities],
            processing_time_ms=int((time.time() - start_time) * 1000)
        )

        # Cache successful result
        _chat_cache_set(cache_key, response.dict())

        return response

    except Exception as e:
        logger.error(f"Multi-dimension analysis failed: {e}", exc_info=True)
        return MultiDimensionResponse(
            success=False,
            error="AI对话处理失败，请稍后重试",
            processing_time_ms=int((time.time() - start_time) * 1000)
        )


@router.get("/health")
async def health_check():
    """Health check endpoint"""
    return {"status": "healthy", "service": "smartbi-chat"}


# ============================================================================
# Helper Functions
# ============================================================================

import math  # noqa: E402


def _sanitize_for_json(obj):
    """Recursively replace NaN/Infinity with None to prevent JSON serialization errors."""
    if isinstance(obj, float):
        if math.isnan(obj) or math.isinf(obj):
            return None
        return obj
    if isinstance(obj, dict):
        return {k: _sanitize_for_json(v) for k, v in obj.items()}
    if isinstance(obj, (list, tuple)):
        return [_sanitize_for_json(v) for v in obj]
    return obj


def _generate_bar_chart_config(
    dimension: str,
    measures: List[str],
    data: 'pd.DataFrame'  # noqa: F821
) -> Dict[str, Any]:
    """Generate bar chart configuration for drill-down results"""

    # Determine chart orientation based on data size
    chart_type = "bar" if len(data) <= 10 else "bar_horizontal"

    series = []
    for measure in measures:
        if measure in data.columns:
            series.append({
                "name": measure,
                "type": "bar",
                "data": data[measure].tolist()
            })

    return {
        "type": chart_type,
        "title": f"按{dimension}分析",
        "xAxis": {
            "type": "category",
            "data": data[dimension].tolist()
        },
        "yAxis": {
            "type": "value"
        },
        "series": series,
        "tooltip": {
            "trigger": "axis"
        }
    }


# P4: Hierarchy keyword mappings for auto-detection
_HIERARCHY_KEYWORDS = {
    "time": {
        "年": 0, "年度": 0, "year": 0,
        "季": 1, "季度": 1, "quarter": 1,
        "月": 2, "月份": 2, "month": 2,
        "周": 3, "week": 3,
        "日": 4, "日期": 4, "天": 4, "day": 4, "date": 4,
    },
    "geography": {
        "国家": 0, "country": 0,
        "区域": 1, "大区": 1, "region": 1,
        "省": 2, "省份": 2, "province": 2,
        "市": 3, "城市": 3, "city": 3,
        "区": 4, "区县": 4, "district": 4,
    },
    "organization": {
        "公司": 0, "company": 0,
        "事业部": 1, "division": 1,
        "部门": 2, "department": 2, "dept": 2,
        "团队": 3, "team": 3, "组": 3,
    },
    "product": {
        "大类": 0, "品类": 0, "category": 0,
        "小类": 1, "子类": 1, "subcategory": 1,
        "产品": 2, "product": 2, "商品": 2,
        "SKU": 3, "sku": 3, "规格": 3,
    },
    "financial": {
        "项目": 0, "会计科目": 0, "科目": 0,
        "明细": 1, "子项目": 1, "子科目": 1,
        "行次": 2,
    },
}


def auto_detect_hierarchy(columns: List[str]) -> Optional[tuple]:
    """
    Scan column names to detect which hierarchy they belong to.
    Returns (hierarchy_type, matched_columns_sorted_by_level) or None.
    """
    best_match = None
    best_count = 0

    for h_type, keyword_map in _HIERARCHY_KEYWORDS.items():
        matched = []
        # Sort by keyword length descending to avoid ambiguous substring matches
        sorted_keywords = sorted(keyword_map.items(), key=lambda x: len(x[0]), reverse=True)
        for col in columns:
            col_lower = col.lower().strip()
            for keyword, level in sorted_keywords:
                if keyword in col_lower or col_lower == keyword:
                    matched.append((level, col))
                    break

        if len(matched) >= 2 and len(matched) > best_count:
            best_count = len(matched)
            # Sort by level, extract column names
            matched.sort(key=lambda x: x[0])
            best_match = (h_type, [m[1] for m in matched])

    return best_match


def _map_hierarchy_to_columns(levels: List[str], columns: List[str]) -> List[str]:
    """Map hierarchy level names to actual DataFrame column names"""
    result = []
    col_lower_map = {c.lower(): c for c in columns}
    for level in levels:
        if level in columns:
            result.append(level)
        elif level.lower() in col_lower_map:
            result.append(col_lower_map[level.lower()])
        else:
            # Try keyword matching
            for col in columns:
                if level.lower() in col.lower():
                    result.append(col)
                    break
    return result


def _find_available_dimensions(
    df: 'pd.DataFrame',  # noqa: F821
    current_dimension: str,
    current_measures: List[str]
) -> List[str]:
    """
    Find other categorical columns that could be drilled into.
    Excludes current dimension and numeric measures.
    """
    available = []
    for col in df.columns:
        if col == current_dimension or col in current_measures:
            continue
        # Check if column is categorical (non-numeric with reasonable cardinality)
        if df[col].dtype == 'object' or str(df[col].dtype) == 'category':
            nunique = df[col].nunique()
            if 2 <= nunique <= 50:
                available.append(col)
    return available[:8]  # Limit to 8 suggestions
