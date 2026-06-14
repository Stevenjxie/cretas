from __future__ import annotations
"""
InsightGenerator -- slim orchestrator.

Coordinates the pipeline:
  data_summarizer -> scenario_context -> prompt_builder -> llm_client -> statistical

All heavy logic lives in the sibling modules.
"""
import json
import logging
from typing import Any, Optional, List, Dict

import pandas as pd

from config import get_settings
from common.utils.json_parser import robust_json_parse
from services.context_extractor import ContextInfo

from . import data_summarizer as ds
from . import scenario_context as sc
from . import prompt_builder as pb
from . import llm_client as llm
from .statistical import generate_statistical_insights

logger = logging.getLogger(__name__)


class InsightGenerator:
    """AI-powered insight generation service."""

    # Re-export constants so existing code that reads them still works
    LLM_TIMEOUT_BASE = llm.LLM_TIMEOUT_BASE
    LLM_TIMEOUT_INCREMENT = llm.LLM_TIMEOUT_INCREMENT
    LLM_TIMEOUT_MAX = llm.LLM_TIMEOUT_MAX
    LLM_MAX_RETRIES = llm.LLM_MAX_RETRIES

    # Keep INSIGHT_TEMPLATES on the class for backward compat
    from .statistical import INSIGHT_TEMPLATES
    INSIGHT_TEMPLATES = INSIGHT_TEMPLATES  # noqa: F811

    def __init__(self, model_override: Optional[str] = None):
        self.settings = get_settings()
        self.model_override = model_override

    # ------------------------------------------------------------------
    # Properties kept for backward compatibility (used by chat.py etc.)
    # ------------------------------------------------------------------

    @property
    def client(self):
        from common.llm_client import get_llm_http_client
        return get_llm_http_client()

    @property
    def _active_model(self) -> str:
        return self.model_override or self.settings.llm_insight_model

    # ------------------------------------------------------------------
    # Main entry point (non-streaming)
    # ------------------------------------------------------------------

    async def generate_insights(
        self,
        data: List[dict],
        metrics: Optional[List[dict]] = None,
        analysis_context: Optional[str] = None,
        insight_types: Optional[List[str]] = None,
        max_insights: int = 5,
        context_info: Optional[ContextInfo] = None,
        upload_id: Optional[Any] = None,
        sheet_index: Optional[int] = None,
    ) -> Dict[str, Any]:
        """Generate business insights from *data*."""
        try:
            df = pd.DataFrame(data)

            if df.empty:
                return {
                    "success": True,
                    "insights": [],
                    "message": "No data available for analysis",
                }

            # Check insight result cache
            cache_key = None
            if upload_id is not None:
                from common.insight_cache import get_insight_cache
                cache = get_insight_cache()
                cache_key = cache.make_key(upload_id, sheet_index or 0, data)
                entry = cache.get(cache_key)
                if entry is not None:
                    logger.info(f"[InsightCache] HIT key={cache_key[:12]}...")
                    cached_result = entry.insights
                    if isinstance(cached_result, dict):
                        cached_result["cached"] = True
                        return cached_result
                    return {
                        "success": True,
                        "insights": cached_result if isinstance(cached_result, list) else [],
                        "method": "cached",
                        "cached": True,
                    }

            # Statistical insights (rule-based)
            stat_insights = generate_statistical_insights(df, metrics)

            # LLM-enhanced insights
            if self.settings.llm_api_key:
                ai_insights = await self._generate_llm_insights(
                    df, metrics, stat_insights, analysis_context, context_info,
                )
                insights = ai_insights
            else:
                insights = stat_insights

            # Filter by requested types
            if insight_types:
                insights = [i for i in insights if i.get("type") in insight_types]

            insights = insights[:max_insights]

            result = {
                "success": True,
                "insights": insights,
                "totalGenerated": len(insights),
                "method": "llm" if self.settings.llm_api_key else "statistical",
            }

            # Store in cache
            if cache_key is not None:
                from common.insight_cache import get_insight_cache
                get_insight_cache().set(cache_key, result)
                logger.info(f"[InsightCache] SET key={cache_key[:12]}...")

            return result

        except Exception as e:
            logger.error("Insight generation failed: %s", e, exc_info=True)
            return {"success": False, "error": str(e), "insights": []}

    # ------------------------------------------------------------------
    # Streaming entry point
    # ------------------------------------------------------------------

    async def generate_insights_stream(
        self,
        data: List[dict],
        metrics: Optional[List[dict]] = None,
        analysis_context: Optional[str] = None,
        max_insights: int = 5,
        context_info: Optional[ContextInfo] = None,
        upload_id: Optional[Any] = None,
        sheet_index: Optional[int] = None,
    ):
        """Stream AI insights as SSE events.

        Yields ``'chunk'`` events with raw LLM text, then a final ``'done'``
        event with parsed JSON.
        """
        if not self.settings.llm_api_key:
            yield {
                "event": "done",
                "data": json.dumps({"success": False, "error": "No LLM API key"}),
            }
            return

        try:
            df = pd.DataFrame(data)
            if df.empty:
                yield {
                    "event": "done",
                    "data": json.dumps({"success": True, "insights": []}),
                }
                return

            # P0 出境脱敏: 设置请求级脱敏 scope (流式: set 不取 reset, 镜像 _set_llm_caller —
            # 异步生成器跨 task 边界, reset 会报错; 请求级 task 隔离负责清理), 注册 df 敏感列
            # 真名 → choke point 出境占位; 输出再 restore 还原 (真名不出境, 数字/分析 0 损失).
            from common.llm_redactor import (
                begin_redaction_scope_no_reset, StreamRestorer, restore_obj,
            )
            _scope = begin_redaction_scope_no_reset()
            _scope.register_df(df)

            # Build prompt (same pipeline as non-streaming)
            data_summary = ds.prepare_data_summary(df)
            financial_metrics = sc.compute_financial_context(df)
            production_metrics = sc.compute_production_context(df)
            stat_digest = ds.compute_statistical_digest(df)
            kb_context = await sc.get_food_kb_context_with_timeout(df, timeout_secs=3.0)
            metrics_summary = ds.prepare_metrics_summary(metrics) if metrics else ""

            scenario = sc.detect_analysis_scenario(df)
            benchmark_text = pb.get_scenario_benchmarks(scenario)

            if scenario == 'restaurant_operations':
                try:
                    production_metrics = sc.compute_restaurant_context(df)
                except Exception as e:
                    logger.warning(
                        "_compute_restaurant_context failed (non-blocking): %s", e,
                    )

            excel_context = ""
            if context_info and context_info.has_content():
                excel_context = (
                    f"\n## 报表上下文信息（来自原始Excel）\n{context_info.to_prompt_text()}"
                )

            row_count = len(df)
            tier_cfg = pb.get_tiered_config(row_count)
            tier = tier_cfg["tier"]
            max_tokens = tier_cfg["max_tokens"]
            logger.info(
                "Streaming insight tier: %s (rows=%d, max_tokens=%d)",
                tier, row_count, max_tokens,
            )

            query_block = ""
            if analysis_context:
                query_block = (
                    f"\n## 核心任务（最高优先级）\n"
                    f"用户提出了具体问题：「{analysis_context}」\n"
                    f"你的所有分析必须围绕这个问题展开。\n"
                )
            prompt = pb.build_tiered_prompt(
                tier=tier,
                scenario=scenario,
                benchmark_text=benchmark_text,
                query_block=query_block,
                data_summary=data_summary,
                financial_metrics=financial_metrics,
                production_metrics=production_metrics,
                stat_digest=stat_digest,
                metrics_summary=metrics_summary,
                excel_context=excel_context,
                kb_context=kb_context,
            )

            system_role = pb.build_cacheable_system_prompt(tier, scenario)
            full_response = ""
            _restorer = StreamRestorer(_scope.placeholder_map)  # P0: 流式占位实时还原
            async for chunk in llm.call_llm_stream(
                prompt, system_role, max_tokens=max_tokens,
                model_override=self.model_override,
            ):
                full_response += chunk
                out = _restorer.push(chunk)
                if out:
                    yield {"event": "chunk", "data": out}
            tail = _restorer.flush()
            if tail:
                yield {"event": "chunk", "data": tail}

            stat_insights = generate_statistical_insights(df, metrics)
            parsed = restore_obj(
                self._parse_llm_insights(full_response, stat_insights),
                _scope.placeholder_map,
            )  # P0: 还原 done 事件里的真名
            from .fact_reconciler import reconcile_insights, overall_ratio_facts, overall_factory_facts
            # 阶段2 grounding: 调用方 metrics + Python 自算的整体口径比率 + 工厂生产指标
            parsed = reconcile_insights(
                parsed,
                (metrics or []) + overall_ratio_facts(df) + overall_factory_facts(df),
            )
            done_result = {
                "success": True,
                "insights": parsed,
                "totalGenerated": len(parsed),
                "method": "llm",
            }

            # Cache the streaming result
            if upload_id is not None:
                try:
                    from common.insight_cache import get_insight_cache
                    cache = get_insight_cache()
                    ck = cache.make_key(upload_id, sheet_index or 0, data)
                    cache.set(ck, done_result)
                    logger.info(f"[InsightCache] SET (stream) key={ck[:12]}...")
                except Exception as cache_err:
                    logger.debug(
                        "[InsightCache] stream cache store failed: %s", cache_err,
                    )

            yield {
                "event": "done",
                "data": json.dumps(done_result, ensure_ascii=False, default=str),
            }

        except Exception as e:
            logger.error("Streaming insight generation failed: %s", e, exc_info=True)
            yield {
                "event": "done",
                "data": json.dumps({"success": False, "error": str(e)}),
            }

    # ------------------------------------------------------------------
    # Backward-compat delegates (used by chat.py)
    # ------------------------------------------------------------------

    async def generate_text_analysis(self, text: str) -> str:
        return await llm.generate_text_analysis(
            text, model_override=self.model_override,
        )

    async def _call_llm_stream_text(
        self, prompt: str, system_role: Optional[str] = None,
        max_tokens: int = 3000, temperature: float = 0.4,
    ):
        async for chunk in llm.call_llm_stream_text(
            prompt, system_role,
            max_tokens=max_tokens, temperature=temperature,
            model_override=self.model_override,
        ):
            yield chunk

    async def close(self):
        """No-op: shared client lifecycle managed by main.py lifespan."""

    # ------------------------------------------------------------------
    # Internal orchestration
    # ------------------------------------------------------------------

    async def _generate_llm_insights(
        self,
        df: pd.DataFrame,
        metrics: Optional[List[dict]],
        stat_insights: List[dict],
        context: Optional[str],
        context_info: Optional[ContextInfo] = None,
    ) -> List[dict]:
        """Generate AI-powered insights using LLM, with tier-scaled prompt."""
        # P0 出境脱敏: 进入请求级脱敏 scope, 注册 df 敏感列真名 → choke point 出境时占位,
        # LLM 只看占位+数字; 输出再 restore 还原真名 (数字/分析 0 损失, 真名不出境).
        from common.llm_redactor import (
            redaction_scope, register_df_in_scope, restore_in_scope,
        )
        _redaction = redaction_scope()
        _redaction.__enter__()
        try:
            register_df_in_scope(df)
            row_count = len(df)
            tier_cfg = pb.get_tiered_config(row_count)
            tier = tier_cfg["tier"]
            max_tokens = tier_cfg["max_tokens"]
            logger.info(
                "Insight generation tier: %s (rows=%d, max_tokens=%d)",
                tier, row_count, max_tokens,
            )

            data_summary = ds.prepare_data_summary(df)
            financial_metrics = sc.compute_financial_context(df)
            production_metrics = sc.compute_production_context(df)
            metrics_summary = ds.prepare_metrics_summary(metrics) if metrics else ""

            scenario = sc.detect_analysis_scenario(df)
            logger.info("Detected analysis scenario: %s", scenario)

            if scenario == 'restaurant_operations':
                try:
                    production_metrics = sc.compute_restaurant_context(df)
                except Exception as e:
                    logger.warning(
                        "_compute_restaurant_context failed (non-blocking): %s", e,
                    )

            stat_digest = ds.compute_statistical_digest(df)
            kb_context = await sc.get_food_kb_context_with_timeout(df, timeout_secs=3.0)

            excel_context = ""
            if context_info and context_info.has_content():
                excel_context = (
                    f"\n## 报表上下文信息（来自原始Excel）\n{context_info.to_prompt_text()}\n"
                )

            benchmark_text = pb.get_scenario_benchmarks(scenario)

            query_block = ""
            if context:
                query_block = (
                    f"\n## 核心任务（最高优先级）\n"
                    f"用户提出了具体问题：「{context}」\n"
                    f"你的所有分析必须围绕这个问题展开。executive_summary 必须直接回答这个问题，"
                    f"insights 的每一条都必须与用户问题主题相关。\n"
                    f"禁止忽略用户问题而给出泛泛的财务综述。\n"
                )

            prompt = pb.build_tiered_prompt(
                tier=tier,
                scenario=scenario,
                benchmark_text=benchmark_text,
                query_block=query_block,
                data_summary=data_summary,
                financial_metrics=financial_metrics,
                production_metrics=production_metrics,
                stat_digest=stat_digest,
                metrics_summary=metrics_summary,
                excel_context=excel_context,
                kb_context=kb_context,
            )

            system_role = pb.build_cacheable_system_prompt(tier, scenario)
            response = await llm.call_llm(
                prompt, system_role,
                enable_thinking=False,
                max_tokens=max_tokens,
                model_override=self.model_override,
            )
            parsed = restore_in_scope(self._parse_llm_insights(response, stat_insights))
            # 阶段2 grounding: 调用方 metrics + Python 自算的整体口径比率 (毛利率/净利率) 对账
            from .fact_reconciler import reconcile_insights, overall_ratio_facts, overall_factory_facts
            return reconcile_insights(
                parsed,
                (metrics or []) + overall_ratio_facts(df) + overall_factory_facts(df),
            )

        except Exception as e:
            logger.error("LLM insight generation failed: %s", e, exc_info=True)
            return stat_insights
        finally:
            _redaction.__exit__(None, None, None)

    # ------------------------------------------------------------------
    # Response parsing
    # ------------------------------------------------------------------

    @staticmethod
    def _parse_llm_insights(
        response: str,
        fallback_insights: List[dict],
    ) -> List[dict]:
        """Parse LLM response into structured insights with _meta envelope."""
        try:
            logger.debug("LLM raw response (first 200 chars): %s", response[:200])
            result = robust_json_parse(response, fallback=None)
            if result is None:
                logger.warning("Failed to parse LLM response as JSON")
                return fallback_insights

            insights = result.get("insights", [])

            valid_insights: List[dict] = []
            for insight in insights:
                if isinstance(insight, dict) and insight.get("text"):
                    parsed = {
                        "type": insight.get("type", "summary"),
                        "text": insight.get("text"),
                        "metric": insight.get("metric"),
                        "sentiment": insight.get("sentiment", "neutral"),
                        "importance": insight.get("importance", 5),
                        "recommendation": insight.get("recommendation"),
                        "source": "llm",
                    }
                    if insight.get("action_items"):
                        parsed["action_items"] = insight["action_items"]
                    if insight.get("title"):
                        parsed["title"] = insight["title"]
                    if insight.get("dimension"):
                        parsed["dimension"] = insight["dimension"]
                    if insight.get("confidence"):
                        parsed["confidence"] = insight["confidence"]
                    valid_insights.append(parsed)

            # Inject _meta insight
            executive_summary = result.get("executive_summary", "")
            risk_alerts = result.get("risk_alerts", [])
            opportunities = result.get("opportunities", [])
            sensitivity_analysis = result.get("sensitivity_analysis", [])

            if executive_summary or risk_alerts or opportunities:
                meta_insight = {
                    "type": "_meta",
                    "text": executive_summary,
                    "executive_summary": executive_summary,
                    "risk_alerts": risk_alerts,
                    "opportunities": opportunities,
                    "sensitivity_analysis": sensitivity_analysis,
                    "importance": 10,
                    "source": "llm",
                }
                valid_insights.insert(0, meta_insight)

            return valid_insights if valid_insights else fallback_insights

        except Exception as e:
            logger.error("Failed to parse LLM insights: %s", e)
            return fallback_insights

    # ------------------------------------------------------------------
    # Backward-compat: methods formerly accessed directly on the class
    # ------------------------------------------------------------------

    def _generate_statistical_insights(self, df, metrics):
        return generate_statistical_insights(df, metrics)

    def _detect_analysis_scenario(self, df):
        return sc.detect_analysis_scenario(df)

    def _get_scenario_system_role(self, scenario):
        return pb.get_scenario_system_role(scenario)

    def _get_scenario_benchmarks(self, scenario):
        return pb.get_scenario_benchmarks(scenario)

    def _compute_financial_context(self, df):
        return sc.compute_financial_context(df)

    def _compute_production_context(self, df):
        return sc.compute_production_context(df)

    def _compute_restaurant_context(self, df):
        return sc.compute_restaurant_context(df)

    def _build_cacheable_system_prompt(self, tier, scenario):
        return pb.build_cacheable_system_prompt(tier, scenario)

    @staticmethod
    def _get_tiered_config(row_count):
        return pb.get_tiered_config(row_count)

    def _build_tiered_prompt(self, *, tier, scenario, benchmark_text,
                             query_block, data_summary, financial_metrics,
                             production_metrics, stat_digest, metrics_summary,
                             excel_context, kb_context):
        return pb.build_tiered_prompt(
            tier=tier, scenario=scenario, benchmark_text=benchmark_text,
            query_block=query_block, data_summary=data_summary,
            financial_metrics=financial_metrics,
            production_metrics=production_metrics,
            stat_digest=stat_digest, metrics_summary=metrics_summary,
            excel_context=excel_context, kb_context=kb_context,
        )

    def _prepare_data_summary(self, df):
        return ds.prepare_data_summary(df)

    def _prepare_metrics_summary(self, metrics):
        return ds.prepare_metrics_summary(metrics)

    def _compute_statistical_digest(self, df):
        return ds.compute_statistical_digest(df)

    @staticmethod
    def _humanize_col(name):
        return ds.humanize_col(name)

    def _humanize_df_columns(self, df):
        return ds.humanize_df_columns(df)

    @staticmethod
    def _is_placeholder_col(name):
        return ds.is_placeholder_col(name)

    async def _call_llm(self, prompt, system_role=None, enable_thinking=False,
                        max_tokens=2500):
        return await llm.call_llm(
            prompt, system_role, enable_thinking=enable_thinking,
            max_tokens=max_tokens, model_override=self.model_override,
        )

    async def _call_llm_stream(self, prompt, system_role=None, max_tokens=2500):
        async for chunk in llm.call_llm_stream(
            prompt, system_role, max_tokens=max_tokens,
            model_override=self.model_override,
        ):
            yield chunk

    async def _get_food_kb_context_with_timeout(self, df, timeout_secs=3.0):
        return await sc.get_food_kb_context_with_timeout(df, timeout_secs)

    @staticmethod
    def _has_time_column(df):
        from .statistical import _has_time_column
        return _has_time_column(df)
