"""Agent orchestrator — the single entry point for /executive/insights.

Composes:
    question → narrative_cache.get → (hit) return
             → budget_tracker.check_budget → (blocked) degraded response
             → Gold queries (finance/products/discount) for prompt data
             → call LLM with 餐饮数据分析师 system prompt (spec §4.3)
             → budget_tracker.consume + narrative_cache.put
             → return answer

Design decisions
----------------
- Narrative-only in v1: no tool_calls yet. Gold query primitives are
  called deterministically by the orchestrator before the LLM sees
  anything. This keeps latency predictable and makes token accounting
  exact. Function-calling tools can be layered on when a question
  genuinely needs multi-step reasoning (e.g. compare 2 periods).
- Prompt data is always CONCRETE: KPIs, Top 3 stores by revenue, top
  5 products, discount breakdown. LLM cannot hallucinate since it
  sees the real numbers.
- Apr 25 2026 (E2a): LLM calls now route through common.llm_router
  (SLOT.INSIGHTS) which provides aliyun_b → aliyun_a → zhipu →
  deepseek fallback on 403/429. Previously raw httpx → DashScope
  meant a DashScope outage broke the agent layer entirely.
  llm_model param is now informational (per-provider model is chosen
  by SLOT mapping); kept in __init__ for backward compatibility with
  api.py construction.

Feature flag
------------
Gated by env SMARTBI_AGENT_LAYER_ENABLED. When False (default), the
FastAPI route does not register the /insights/custom endpoint, so
this module is never imported in the hot path.
"""
from __future__ import annotations

import asyncio
import logging
import time
from dataclasses import dataclass
from datetime import date
from typing import Any, AsyncIterator, Dict, List, Optional, Tuple

import asyncpg
import httpx

from common.llm_router import call_chain, call_chain_stream, SLOT
from common.llm_metrics import llm_caller_context
from common.llm_redactor import (
    redaction_scope,
    register_values_for_egress,
    restore_in_scope,
    begin_redaction_scope_no_reset,
    StreamRestorer,
    restore_text,
)
from smartbi.agent.budget_tracker import AgentBudgetTracker
from smartbi.agent.narrative_cache import (
    NarrativeCacheService,
    compute_question_hash,
)
from smartbi.gold import (
    discount_breakdown,
    finance_summary,
    top_products,
)

logger = logging.getLogger(__name__)


DEGRADED_MESSAGE_BUDGET_EXHAUSTED = (
    "今日 AI 预算已用完，建议明天再问。"
    "如需提前恢复，请联系管理员调整预算上限。"
)
DEGRADED_MESSAGE_LLM_UNAVAILABLE = (
    "AI 服务暂时不可用，请稍后重试。如问题持续，请联系管理员。"
)

# Response shape returned to caller (FastAPI serializes as JSON).
RESULT_SOURCE_CACHE = "cache"
RESULT_SOURCE_LLM = "llm"
RESULT_SOURCE_DEGRADED = "degraded"

# Insight answer token budget. Must be large enough for a reasoning model
# (the INSIGHTS slot now routes to deepseek-v4-pro, which spends completion
# tokens on chain-of-thought before the answer). At 600 the reasoning could
# consume the whole budget, leaving message.content empty -> "LLM returned
# empty content" -> degraded "AI 服务暂时不可用" (intermittent, seen 2026-06-02).
# 1500 is the proven-working value in insights.generator; 2000 adds margin.
INSIGHT_MAX_TOKENS = 2000


SYSTEM_PROMPT = """你是一位服务于中国餐饮连锁企业的资深数据分析师。
你将收到用户的一个业务问题，以及该企业真实经营数据的摘要。你的任务是基于这些数据回答问题，并给出可执行的运营建议。

严格遵守以下要求：
1. 回答必须基于数据中的具体数字，禁止凭空杜撰门店名、商品名、百分比。
2. 每条建议必须 4 要素齐全（C-rec 8+9 spec §4.3）：
   (a) 具体对象 — 针对哪家具体门店/菜品/品类/员工/客群（不能是"重点关注"）
   (b) 数字化收益 — 预期收益区间（如"预计提升营业额 3-5%" / "节约成本 5-10万/月"）
   (c) 执行前置条件 — 需先做什么（如"需先完成服务员推销话术培训" / "需区域经理确认营销预算"）
   (d) 时间窗口 — 实施时长（如"本月内" / "下季度" / "14 天试点"）
3. 禁止输出空泛建议，例如：
   - "直接涨价"
   - "加强营销"
   - "提高服务质量"
   - "扩大客源"
   - "建立长效机制"
   - "密切关注"
   这类模糊表述会被视为无效回答。
4. 若提供的数据不足以回答，直接说明"需要 X 数据才能判断"，不要胡编。
5. 用中文回复。使用 Markdown 格式：先 1-2 句核心结论，然后 bullet 列具体建议。
6. **口径说明（C-rec 7 必遵，用自然语言，禁用方括号标记）**：当金额或百分比的
   口径可能引起歧义时，用自然语言简短说明基准（如「应收口径」「按营业额」）。
   ⛔ 严禁在数字后紧跟方括号标记（即用半角中括号包住「毛」「净」「按营业额」
   「按金额」等口径词的写法）——这类标记会让读者觉得像 JSON、严重影响可读性。
   反面（错误，绝不要这样写）：「营业额 3,190,000 元」后面再用方括号包一个「毛/应收」。
   正面（正确）：「应收营业额 3,190,000 元」「堂食占 65.0%（按营业额）」。
   ⛔ 同样严禁用方括号（半角 [] 或全角 【】）包裹任何实体名称——门店名、菜品名、
   折扣名、活动名、渠道名等一律直接书写，不加方括号（方括号会让读者觉得像 JSON/占位符）。
   反面（错误，绝不要这样写）：「[美团套餐券]」「[青花椒徐汇日月光店]」。
   正面（正确）：「美团套餐券」「青花椒徐汇日月光店」。
   当数据中带 share_amount_pct 与 share_bills_pct 两个口径时，必须同时陈述并用
   自然语言区分，避免读者把按金额的占比误读为按笔数。

【语气风格 — 最重要，违反即不合格】
用大白话，像跟店长聊天一样说话，面向文化程度不高的店长或仓管：
- 禁止不解释就直接用：环比/同比/GMV/毛利率/ROI/客单价/坪效/动销率等行话。
  要么换成大白话，要么用了立刻加括号解释。
  例：❌"环比下降5.6%，客单价承压" → ✅"这个月营业额比上月少了5.6%，主要是客人花得少了"
  例：❌"客单价" → ✅"每位客人平均花多少"
- 不用英文术语或英文缩写。
- 短句、口语化，直接说该干什么（可落地），不只罗列数字。
- 数字照样要准确，只是用人话把它包起来说清楚。

回答长度控制在 300 字以内。"""


@dataclass
class InsightResponse:
    """Structured result from answer_insight()."""
    answer: str
    source: str                     # "cache" | "llm" | "degraded"
    tokens: int
    tokens_used_today: int
    tokens_cap: int
    elapsed_ms: int
    chart_config: Optional[Dict[str, Any]] = None
    data_summary: Optional[Dict[str, Any]] = None

    def to_dict(self) -> Dict[str, Any]:
        return {
            "answer": self.answer,
            "source": self.source,
            "tokens": self.tokens,
            "tokens_used_today": self.tokens_used_today,
            "tokens_cap": self.tokens_cap,
            "elapsed_ms": self.elapsed_ms,
            "chart_config": self.chart_config,
            "data_summary": self.data_summary,
        }


class AgentOrchestrator:
    def __init__(
        self,
        pool: asyncpg.Pool,
        llm_base_url: str,
        llm_api_key: str,
        llm_model: str,
        *,
        budget_tracker: Optional[AgentBudgetTracker] = None,
        cache: Optional[NarrativeCacheService] = None,
        http_client: Optional[httpx.AsyncClient] = None,
    ):
        self._pool = pool
        # NOTE (Apr 25 2026, E2a): _llm_base_url / _llm_api_key / _llm_model /
        # _http are retained for backward-compat with constructor callers but
        # no longer used at runtime — LLM routing now flows through
        # common.llm_router (SLOT.INSIGHTS chain). The provider/model is
        # selected per-call by the chain, not by these fields.
        self._llm_base_url = llm_base_url.rstrip("/")
        self._llm_api_key = llm_api_key
        self._llm_model = llm_model
        self._budget = budget_tracker or AgentBudgetTracker(pool)
        self._cache = cache or NarrativeCacheService(pool)
        self._http = http_client

    async def answer_insight(
        self,
        factory_id: str,
        question: str,
        date_range: Tuple[date, date],
        *,
        cache_ttl_hours: int = 24,
    ) -> InsightResponse:
        """Main entry point. See module docstring for flow."""
        t0 = time.monotonic()
        start, end = date_range
        start_iso, end_iso = start.isoformat(), end.isoformat()

        # 1. Cache check
        q_hash = compute_question_hash(question, start_iso, end_iso, factory_id)
        hit = await self._cache.get(factory_id, q_hash)
        if hit is not None:
            budget = await self._budget.check_budget(factory_id)
            return InsightResponse(
                answer=hit["answer"],
                source=RESULT_SOURCE_CACHE,
                tokens=0,  # cache hit costs zero new tokens
                tokens_used_today=budget.tokens_used,
                tokens_cap=budget.tokens_cap,
                elapsed_ms=int((time.monotonic() - t0) * 1000),
                chart_config=hit.get("chart_config"),
            )

        # 2. Budget check
        budget = await self._budget.check_budget(factory_id)
        if budget.blocked:
            logger.warning(
                "agent budget exhausted: factory=%s used=%d cap=%d q=%r",
                factory_id, budget.tokens_used, budget.tokens_cap, question,
            )
            return InsightResponse(
                answer=DEGRADED_MESSAGE_BUDGET_EXHAUSTED,
                source=RESULT_SOURCE_DEGRADED,
                tokens=0,
                tokens_used_today=budget.tokens_used,
                tokens_cap=budget.tokens_cap,
                elapsed_ms=int((time.monotonic() - t0) * 1000),
            )

        # 3. Pull Gold summaries (concrete numbers for prompt)
        data = await self._gather_data(factory_id, date_range)

        # 4. Build prompt + call LLM.
        # P0 出境脱敏 (数据主权): 在请求级 RedactionScope 内注册门店/商品/折扣真名,
        # choke point (llm_router) 出境前换成占位 (门店A/商品B), LLM 只看占位+数字;
        # 输出再 restore 还原真名展示给用户 (真名不出境, 数字/分析 0 损失).
        user_prompt = self._build_user_prompt(question, start_iso, end_iso, data)
        try:
            with redaction_scope():
                register_values_for_egress(_collect_sensitive_names(data))
                answer, tokens, resolved_model = await self._call_llm(user_prompt, factory_id)
                answer = restore_in_scope(answer)
        except Exception as e:
            logger.exception("LLM call failed: %s", e)
            return InsightResponse(
                answer=DEGRADED_MESSAGE_LLM_UNAVAILABLE,
                source=RESULT_SOURCE_DEGRADED,
                tokens=0,
                tokens_used_today=budget.tokens_used,
                tokens_cap=budget.tokens_cap,
                elapsed_ms=int((time.monotonic() - t0) * 1000),
                data_summary=data,
            )

        # 5. Post-call bookkeeping
        post_budget = await self._budget.consume(factory_id, tokens)
        await self._cache.put(
            factory_id, q_hash, answer,
            chart_config=None,
            tokens=tokens,
            ttl_hours=cache_ttl_hours,
        )

        # 6. Distillation capture (training corpus). Fire-and-forget; only the
        # freshly-generated LLM answer (cache-hit returns above never reach here).
        await _capture_insight_distillation(
            user_prompt, answer, factory_id, tokens, teacher_model=resolved_model,
        )

        return InsightResponse(
            answer=answer,
            source=RESULT_SOURCE_LLM,
            tokens=tokens,
            tokens_used_today=post_budget.tokens_used,
            tokens_cap=post_budget.tokens_cap,
            elapsed_ms=int((time.monotonic() - t0) * 1000),
            data_summary=data,
        )

    # ---------- Data gathering ----------

    async def _gather_data(
        self, factory_id: str, date_range: Tuple[date, date]
    ) -> Dict[str, Any]:
        """Pull compact summaries from Gold for the prompt.

        Apr 25 2026 perf E1a: 3 independent pg queries run via asyncio.gather
        so total wall ≈ max(query) instead of sum(query). Each query hits a
        different agg table (agg_store_daily / agg_product_daily / fact_pos_discount)
        so there's no contention.

        Limits top_n to keep prompt size bounded:
        - 3 stores (by revenue)
        - 5 products (by revenue)
        - All discount types (usually <10)
        """
        fin, prods, disc = await asyncio.gather(
            finance_summary(self._pool, factory_id, date_range, top_n_stores=3),
            top_products(self._pool, factory_id, date_range, top_n=5),
            discount_breakdown(self._pool, factory_id, date_range),
        )
        return {
            "finance": fin,
            "top_products": prods.get("items") or prods.get("products") or [],
            "discount_breakdown": disc.get("items") or disc.get("discounts") or [],
        }

    def _build_user_prompt(
        self,
        question: str,
        start_iso: str,
        end_iso: str,
        data: Dict[str, Any],
    ) -> str:
        """Format the data as a readable prompt. No JSON dumps — LLMs
        handle pretty text more reliably."""
        fin = data["finance"]
        stores = fin.get("top_stores") or []
        top_prods = data["top_products"] or []
        discounts = data["discount_breakdown"] or []

        revenue = fin.get("total_revenue") or 0
        bills = fin.get("bill_count") or 0
        avg_bill = fin.get("avg_bill_value")
        store_count = fin.get("store_count") or 0
        day_count = fin.get("day_count") or 0

        lines = [
            f"用户问：{question}",
            "",
            f"数据范围：{start_iso} 至 {end_iso}（{day_count} 个营业日，{store_count} 家门店）",
            "",
            "## 核心 KPI",
            f"- 总营业额：¥{_fmt_money(revenue)}",
            f"- 订单数：{bills:,}",
            f"- 客单价：¥{_fmt_money(avg_bill) if avg_bill else '—'}",
        ]

        if stores:
            lines.append("")
            lines.append("## Top 3 门店（按营业额）")
            for i, s in enumerate(stores[:3], 1):
                name = s.get("store_name") or s.get("store_id") or "未知门店"
                rev = s.get("revenue") or 0
                bc = s.get("bill_count") or 0
                lines.append(f"{i}. {name}：¥{_fmt_money(rev)}（{bc:,} 单）")

        if top_prods:
            lines.append("")
            lines.append("## Top 5 商品（按营业额）")
            for i, p in enumerate(top_prods[:5], 1):
                name = p.get("product_name") or p.get("name") or "未知商品"
                rev = p.get("revenue") or 0
                qty = p.get("quantity") or p.get("qty") or 0
                lines.append(f"{i}. {name}：¥{_fmt_money(rev)}（销量 {qty:,}）")

        if discounts:
            lines.append("")
            lines.append("## 折扣结构（各类型金额合计）")
            for d in discounts[:8]:
                name = d.get("discount_name") or d.get("name") or "未知折扣"
                amt = d.get("total_amount") or d.get("amount") or 0
                lines.append(f"- {name}：¥{_fmt_money(amt)}")

        lines.append("")
        lines.append("请基于以上真实数据回答用户问题，并给出具体可执行的建议。")
        return "\n".join(lines)

    # ---------- LLM ----------

    async def _call_llm(
        self, user_prompt: str, factory_id: Optional[str] = None
    ) -> Tuple[str, int, str]:
        """Call LLM via multi-provider router (INSIGHTS slot).

        Returns (text, total_tokens, resolved_model_name).
        ``resolved_model_name`` is the model field from the API response (the
        actual model the router resolved to — rotates by quota/date), falling
        back to "router:INSIGHTS" if the response does not carry a model field.

        Chain: aliyun_b → aliyun_a → zhipu → deepseek with 403/429 fallback.
        Raises on upstream error (including all-providers exhausted).
        Caller handles degraded fallback.
        """
        payload = {
            # `model` is overwritten per-provider by call_chain
            "messages": [
                {"role": "system", "content": SYSTEM_PROMPT},
                {"role": "user", "content": user_prompt},
            ],
            "temperature": 0.3,
            "max_tokens": INSIGHT_MAX_TOKENS,
        }
        # factory_id → _llm_factory ContextVar so the egress audit + usage rows
        # attribute this call to the tenant (else factory_id is NULL).
        with llm_caller_context("agent_orchestrator", factory_id):
            body = await call_chain(SLOT.INSIGHTS, payload, timeout=60.0)

        text = (
            body.get("choices", [{}])[0]
            .get("message", {})
            .get("content", "")
            or ""
        ).strip()
        usage = body.get("usage") or {}
        tokens = int(usage.get("total_tokens") or 0)
        # Capture the actual model the router resolved to for distillation provenance.
        resolved_model: str = body.get("model") or "router:INSIGHTS"
        if not text:
            raise ValueError("LLM returned empty content")
        return text, tokens, resolved_model

    # ---------- Streaming variant (SSE, Phase 9 Apr 24) ----------

    async def stream_insight(
        self,
        factory_id: str,
        question: str,
        date_range: Tuple[date, date],
        *,
        cache_ttl_hours: int = 24,
    ) -> AsyncIterator[Dict[str, Any]]:
        """Streaming variant of answer_insight. Yields event dicts:
          - {"type": "meta", "source": "cache"|"llm"|"degraded", ...}  (first event)
          - {"type": "delta", "text": "..."}  (many events, LLM tokens)
          - {"type": "done", "tokens": N, "elapsed_ms": M}  (final event)

        Cache hit + degraded paths emit meta + a single delta with the
        full text, then done — caller can treat uniformly.
        """
        t0 = time.monotonic()
        start, end = date_range
        start_iso, end_iso = start.isoformat(), end.isoformat()

        # 1. Cache check
        q_hash = compute_question_hash(question, start_iso, end_iso, factory_id)
        hit = await self._cache.get(factory_id, q_hash)
        if hit is not None:
            budget = await self._budget.check_budget(factory_id)
            yield {"type": "meta", "source": RESULT_SOURCE_CACHE,
                   "tokens_used_today": budget.tokens_used, "tokens_cap": budget.tokens_cap}
            yield {"type": "delta", "text": hit["answer"]}
            yield {"type": "done", "tokens": 0,
                   "elapsed_ms": int((time.monotonic() - t0) * 1000)}
            return

        # 2. Budget check
        budget = await self._budget.check_budget(factory_id)
        if budget.blocked:
            yield {"type": "meta", "source": RESULT_SOURCE_DEGRADED,
                   "tokens_used_today": budget.tokens_used, "tokens_cap": budget.tokens_cap}
            yield {"type": "delta", "text": DEGRADED_MESSAGE_BUDGET_EXHAUSTED}
            yield {"type": "done", "tokens": 0,
                   "elapsed_ms": int((time.monotonic() - t0) * 1000)}
            return

        # 3. Gather data + prompt
        data = await self._gather_data(factory_id, date_range)
        user_prompt = self._build_user_prompt(question, start_iso, end_iso, data)

        # P0 出境脱敏 (数据主权, 流式): set scope 不取 reset (镜像 generator 流式路径 —
        # 异步生成器跨 task 边界 reset 会报 'Token created in different Context';
        # 请求级 task 隔离负责清理). 注册门店/商品/折扣真名 → choke point 出境占位.
        # StreamRestorer 实时把回流占位还原成真名 (占位可能被切到两个 chunk, 缓冲尾窗).
        _scope = begin_redaction_scope_no_reset()
        _scope.register_values(_collect_sensitive_names(data))
        _restorer = StreamRestorer(_scope.placeholder_map)

        yield {"type": "meta", "source": RESULT_SOURCE_LLM,
               "tokens_used_today": budget.tokens_used, "tokens_cap": budget.tokens_cap,
               "data_summary_compact": {
                   "finance": {
                       "revenue": data.get("finance", {}).get("total_revenue"),
                       "bills": data.get("finance", {}).get("bill_count"),
                       "stores": data.get("finance", {}).get("store_count"),
                   }
               }}

        # 4. Stream LLM. Accumulate RAW (placeholder) chunks for the cache restore;
        # emit RESTORED (real-name) deltas live via the StreamRestorer.
        full_text_parts = []
        total_tokens = 0
        try:
            async for chunk in self._call_llm_stream(user_prompt, factory_id):
                if chunk.get("text"):
                    full_text_parts.append(chunk["text"])
                    emitted = _restorer.push(chunk["text"])
                    if emitted:
                        yield {"type": "delta", "text": emitted}
                if chunk.get("tokens"):
                    total_tokens = chunk["tokens"]
            tail = _restorer.flush()
            if tail:
                yield {"type": "delta", "text": tail}
        except Exception as e:
            logger.exception("LLM stream failed: %s", e)
            # Emit degraded tail so client gets a valid terminator
            yield {"type": "delta", "text": "\n\n" + DEGRADED_MESSAGE_LLM_UNAVAILABLE}
            yield {"type": "done", "tokens": 0,
                   "elapsed_ms": int((time.monotonic() - t0) * 1000)}
            return

        # Restore placeholders → real names in the cached answer (so future cache
        # hits show real 门店/菜品 names, not 门店A).
        answer = restore_text("".join(full_text_parts), _scope.placeholder_map).strip()
        if answer:
            post_budget = await self._budget.consume(factory_id, total_tokens)  # noqa: F841
            await self._cache.put(
                factory_id, q_hash, answer,
                chart_config=None, tokens=total_tokens, ttl_hours=cache_ttl_hours,
            )
            # Distillation capture (training corpus). Fire-and-forget; only a
            # freshly-streamed LLM answer (cache-hit / degraded paths return
            # above and never reach here).
            # Streaming responses do not carry a top-level model field; record
            # slot provenance so training data is not attributed to a hardcoded
            # model name.
            await _capture_insight_distillation(
                user_prompt, answer, factory_id, total_tokens,
                teacher_model="router:INSIGHTS",
            )
        yield {"type": "done", "tokens": total_tokens,
               "elapsed_ms": int((time.monotonic() - t0) * 1000)}

    async def _call_llm_stream(
        self, user_prompt: str, factory_id: Optional[str] = None
    ) -> AsyncIterator[Dict[str, Any]]:
        """Call LLM via multi-provider router (INSIGHTS slot) with SSE streaming.

        Chain: aliyun_b → aliyun_a → zhipu → deepseek (provider fallback before
        first delta; mid-stream errors propagate without fallback).

        Yields {"text": "..."} for each delta, and finally {"tokens": N}
        if the upstream reports usage on the final [DONE] message.
        """
        payload = {
            # `model` is overwritten per-provider by call_chain_stream
            "messages": [
                {"role": "system", "content": SYSTEM_PROMPT},
                {"role": "user", "content": user_prompt},
            ],
            "temperature": 0.3,
            "max_tokens": INSIGHT_MAX_TOKENS,
            "stream_options": {"include_usage": True},
        }
        # Set caller + factory via direct set() instead of `with llm_caller_context()` —
        # the latter errors on __exit__ ("Token created in different Context")
        # when an async generator crosses task boundaries (e.g. inside httpx
        # aiter_lines). Contextvar is request-scoped via FastAPI task isolation.
        from common.llm_metrics import (
            _llm_caller as _llm_caller_var,
            _llm_factory as _llm_factory_var,
        )
        _llm_caller_var.set("agent_orchestrator_stream")
        _llm_factory_var.set(factory_id)
        async for event in call_chain_stream(SLOT.INSIGHTS, payload, timeout=60.0):
            etype = event.get("type")
            if etype == "delta":
                text = event.get("text")
                if text:
                    yield {"text": text}
            elif etype == "usage":
                tokens = event.get("tokens") or 0
                if tokens:
                    yield {"tokens": int(tokens)}


def _fmt_money(v: Any) -> str:
    """Render a currency amount with 2 decimals + thousands separator.

    Handles None / Decimal / float / int gracefully.
    """
    if v is None:
        return "0.00"
    try:
        return f"{float(v):,.2f}"
    except (TypeError, ValueError):
        return str(v)


def _collect_sensitive_names(data: Dict[str, Any]) -> Dict[str, List[str]]:
    """Pull real entity names (门店/商品/折扣活动) out of the gathered Gold
    summaries so the egress redactor can swap them for placeholders before the
    prompt leaves for the public LLM (P0 数据主权).

    The insights generator registers a whole DataFrame's sensitive columns;
    the orchestrator builds its prompt from dict-shaped Gold summaries instead,
    so we extract the same entity names by their known keys here. Names are
    restored on the way back so the user still sees real 门店/菜品 names.
    """
    out: Dict[str, List[str]] = {"门店": [], "商品": [], "活动": []}
    fin = data.get("finance") or {}
    for s in (fin.get("top_stores") or []):
        n = s.get("store_name") if isinstance(s, dict) else None
        if isinstance(n, str) and n.strip():
            out["门店"].append(n.strip())
    for p in (data.get("top_products") or []):
        n = (p.get("product_name") or p.get("name")) if isinstance(p, dict) else None
        if isinstance(n, str) and n.strip():
            out["商品"].append(n.strip())
    for d in (data.get("discount_breakdown") or []):
        n = (d.get("discount_name") or d.get("name")) if isinstance(d, dict) else None
        if isinstance(n, str) and n.strip():
            out["活动"].append(n.strip())
    return {k: v for k, v in out.items() if v}


async def _capture_insight_distillation(
    user_prompt: str,
    answer: str,
    factory_id: Optional[str],
    tokens: int,
    teacher_model: str = "router:INSIGHTS",
) -> None:
    """Capture a 经营驾驶舱 report-insight teacher pair into the distillation
    corpus. Fire-and-forget — wrapped so it NEVER raises and never affects the
    insight response / streaming. Only called on freshly-generated LLM answers
    (cache-hit / degraded early-returns never reach the call sites).

    Args:
        user_prompt: The user-facing prompt sent to the LLM.
        answer: The LLM-generated answer (restored to real entity names).
        factory_id: Tenant factory ID for corpus attribution.
        tokens: Total token count from the response's usage field.
        teacher_model: The actual model name resolved by the router (from the
            response's ``model`` field), or "router:INSIGHTS" when the response
            does not carry a model field (e.g. streaming path).  This replaces
            the former hardcoded "qwen3-max" to ensure correct provenance.
    """
    try:
        from smartbi.config import get_pg_pool
        from smartbi.services.distillation_capture import persist_distillation_sample
        pool = await get_pg_pool()
        await persist_distillation_sample(
            pool,
            source="agent_insight",
            task_type="insight",
            input_text=user_prompt,
            teacher_output=answer,
            business_type="restaurant",
            factory_id=factory_id,
            system_prompt=SYSTEM_PROMPT,
            teacher_model=teacher_model,
            quality=4,  # structural-verified + served to user: high quality
            metadata={
                "slot": "insights",
                "tokens": tokens,
            },
        )
    except Exception as e:  # belt-and-suspenders; helper already swallows
        logger.debug("[distill] insight capture skipped (non-blocking): %s", e)
