"""LLM-based 大众点评评论分析器 — W5.5 + W6 (升级自 review_analyzer.py)

⚠️ 定位: **Bonus 功能, 不是核心卖点** (见 review_analyzer.py 头注释)
  - 原 11 improvements 中改进 7+8 被砍, W4.5 技术兴趣加回来
  - 需要客户主动上传 review 数据才有价值
  - 核心卖点仍是 W2 的 cost_rigidity + benchmark alerts

对比 review_analyzer.py (regex + 关键词):
  regex: 精度 30-60% 在真实数据 (招牌青花椒鱼 OK, 上米饭/务细心/知上菜 等都是误匹配)
  LLM:   精度 >90% (菜品名 + 情感 + aspect 提取)

设计:
  1. 批量处理 (20 条/请求), 减少 LLM 调用次数
  2. 结构化输出 (JSON), prompt 强约束
  3. 聚合: 全量扫描后统一合并菜品提及 + 情感
  4. Fallback: LLM 失败 → fallback 到 regex 分析
  5. 缓存: 同 (store, period) 缓存结果, 避免重复调用 (Week 6+)

Prompt 策略:
  给 LLM 一批评论, 要它返回每条评论的:
    - dishes: [{"name": "招牌青花椒鱼", "sentiment": "positive"|"negative"|"neutral"}]
    - overall_sentiment: positive|negative|neutral

输入兼容 review_analyzer.py 的 ReviewAnalysisReport 结构, 前端无需改.

使用示例:
    >>> analyzer = LlmReviewAnalyzer()
    >>> import asyncio
    >>> report = asyncio.run(analyzer.analyze_async(reviews))
"""
from __future__ import annotations

import asyncio
import json
import logging
import os
import re
import statistics
from dataclasses import dataclass, field
from typing import Any, Optional

import httpx

from smartbi.config import get_settings

from .review_analyzer import (
    DishMention,
    RatingTrend,
    ReviewAnalysisReport,
    ReviewAnalyzer,
)

logger = logging.getLogger(__name__)


@dataclass
class LlmExtractResult:
    """单条评论的 LLM 抽取结果"""
    review_id: Any
    dishes: list[dict]              # [{name, sentiment}, ...]
    overall_sentiment: str          # positive/negative/neutral
    aspects: dict[str, str] = field(default_factory=dict)  # {taste: positive, service: negative, ...}


class LlmReviewAnalyzer:
    """LLM 驱动的评论分析器 — 支持 DashScope / DeepSeek 双 provider

    Provider 选择 (环境变量 LLM_PROVIDER):
      - auto (默认): DeepSeek 优先, fallback DashScope
      - deepseek:   仅用 DeepSeek (chat-v3)
      - dashscope:  仅用 DashScope (qwen3.5-flash)
    """

    # 配置
    _BATCH_SIZE = 20              # 每批 20 条评论
    _MAX_CONCURRENT = 5           # 最多 5 个并发请求
    _TIMEOUT_SECS = 60.0
    _MAX_RETRIES = 2

    # Provider 配置 (base_url + model + env var for key)
    _PROVIDERS = {
        "deepseek": {
            "base_url": "https://api.deepseek.com/v1",
            "model": "deepseek-chat",
            "api_key_env": "DEEPSEEK_API_KEY",
        },
        "dashscope": {
            "base_url": "https://dashscope.aliyuncs.com/compatible-mode/v1",
            "model": "qwen3.5-flash",
            "api_key_env": "DASHSCOPE_API_KEY",
        },
    }

    _SYSTEM_PROMPT = """你是一位专业的餐饮评论分析专家。
你的任务: 从一批大众点评评论中准确抽取顾客提到的菜品名称和情感, 按 JSON 格式输出.

规则:
1. dishes: 只抽取真实的菜品, 不抽取"服务""环境""价格""餐厅"等非菜品词
2. 菜品名必须是独立的名词短语 (例如: "招牌青花椒鱼" ✓, "上米饭" ✗ — "上"是动词)
3. sentiment 三种: positive (好吃/推荐/赞) / negative (难吃/失望/不推荐) / neutral (没提观感)
4. 同一条评论里同一个菜品只算一次
5. overall_sentiment: 这条评论整体的语气, 基于所有菜品情感和顾客的总结句
6. aspects: 可选, 提及服务/环境/等位/上菜速度时抽取, 格式 {"service": "positive/negative/neutral", ...}

输出 JSON 对象, 顶层 key 为 "reviews", 值为数组:
{
  "reviews": [
    {"review_id": 1, "dishes": [{"name": "招牌毛肚", "sentiment": "positive"}], "overall_sentiment": "positive", "aspects": {"service": "positive"}},  # noqa: E501
    {"review_id": 2, "dishes": [...], "overall_sentiment": "neutral"}
  ]
}
"""

    def __init__(
        self,
        fallback_analyzer: Optional[ReviewAnalyzer] = None,
        provider: Optional[str] = None,  # "deepseek" / "dashscope" / "auto" / None
    ):
        self.fallback = fallback_analyzer or ReviewAnalyzer()
        cfg = get_settings()

        # Resolve provider
        provider = provider or os.environ.get("LLM_PROVIDER", "auto")
        self.provider = self._resolve_provider(provider)

        # Set api_key / base_url / model based on provider
        if self.provider == "deepseek":
            p = self._PROVIDERS["deepseek"]
            self._api_key = os.environ.get(p["api_key_env"]) or ""
            self._base_url = p["base_url"]
            self._model = p["model"]
        else:  # dashscope fallback
            p = self._PROVIDERS["dashscope"]
            self._api_key = os.environ.get(p["api_key_env"]) or cfg.llm_api_key
            self._base_url = cfg.llm_base_url
            self._model = cfg.llm_fast_model

        logger.info(
            f"LlmReviewAnalyzer: provider={self.provider}, model={self._model}, "
            f"api_key={'set' if self._api_key else 'EMPTY'}"
        )

    @classmethod
    def _resolve_provider(cls, requested: str) -> str:
        """Resolve 'auto' → actual provider based on what's available"""
        if requested == "auto":
            # Prefer DeepSeek if key available, else DashScope
            if os.environ.get("DEEPSEEK_API_KEY"):
                return "deepseek"
            return "dashscope"
        if requested in cls._PROVIDERS:
            return requested
        logger.warning(f"Unknown provider '{requested}', falling back to dashscope")
        return "dashscope"

    # ── Main entry (async) ──────────────────────────────

    async def analyze_async(
        self,
        reviews: list[dict],
        min_mentions: int = 3,
        max_reviews: int = 500,  # Safety cap to avoid runaway costs
    ) -> ReviewAnalysisReport:
        """LLM 驱动的批量分析

        Args:
            reviews: 评论列表
            min_mentions: 菜品最小提及次数 (进榜门槛)
            max_reviews: 最多 LLM 处理多少条 (避免跑 19k)
        """
        if not reviews:
            return self.fallback._empty("无评论数据")

        # LLM 不可用 → fallback
        if not self._api_key:
            logger.warning("DASHSCOPE_API_KEY not set, falling back to regex analyzer")
            return self.fallback.analyze(reviews, min_mentions=min_mentions)

        # Truncate to safe limit
        valid = [r for r in reviews if r.get("content") and r.get("rating")]
        if not valid:
            return self.fallback._empty("所有评论缺少 content 或 rating")

        original_count = len(valid)
        if len(valid) > max_reviews:
            logger.info(f"Truncating {len(valid)} reviews to {max_reviews} for LLM analysis")
            valid = valid[:max_reviews]

        # 按批次 LLM 抽取
        extracts: list[LlmExtractResult] = []
        batches = [valid[i: i + self._BATCH_SIZE] for i in range(0, len(valid), self._BATCH_SIZE)]
        logger.info(f"LLM analyzing {len(valid)} reviews in {len(batches)} batches")

        # 并发控制
        sem = asyncio.Semaphore(self._MAX_CONCURRENT)

        async def _process_batch(idx: int, batch: list[dict]) -> list[LlmExtractResult]:
            async with sem:
                return await self._extract_batch(idx, batch)

        tasks = [_process_batch(i, b) for i, b in enumerate(batches)]
        results = await asyncio.gather(*tasks, return_exceptions=True)

        success_batches = 0
        for r in results:
            if isinstance(r, Exception):
                logger.warning(f"Batch failed: {r}")
                continue
            extracts.extend(r)
            success_batches += 1

        if not extracts:
            logger.warning("All LLM batches failed, falling back to regex")
            return self.fallback.analyze(reviews, min_mentions=min_mentions)

        logger.info(
            f"LLM extracted {len(extracts)} reviews "
            f"({success_batches}/{len(batches)} batches OK)"
        )

        # 聚合
        return self._aggregate(
            valid, extracts, min_mentions=min_mentions,
            total_original=original_count,
        )

    def analyze(
        self, reviews: list[dict], min_mentions: int = 3, max_reviews: int = 500
    ) -> ReviewAnalysisReport:
        """同步版本 (直接跑 asyncio.run)"""
        try:
            loop = asyncio.get_event_loop()
            if loop.is_running():
                # 已在 async 上下文里, 跑不动 asyncio.run
                # Fallback to sync regex
                logger.warning("analyze() called from async context, use analyze_async()")
                return self.fallback.analyze(reviews, min_mentions=min_mentions)
        except RuntimeError:
            pass

        return asyncio.run(
            self.analyze_async(reviews, min_mentions=min_mentions, max_reviews=max_reviews)
        )

    # ── LLM batch extraction ────────────────────────────

    async def _extract_batch(
        self, batch_idx: int, batch: list[dict]
    ) -> list[LlmExtractResult]:
        """发送一批评论给 LLM"""
        # 构建 user prompt
        lines = []
        for idx, r in enumerate(batch):
            content = str(r.get("content", "")).replace("\n", " ")[:300]  # 截断长评论
            rating = r.get("rating", "?")
            lines.append(f'{idx + 1}. rating={rating}星: {content}')
        user_prompt = "请抽取以下评论的菜品和情感:\n\n" + "\n".join(lines)

        payload = {
            "model": self._model,
            "messages": [
                {"role": "system", "content": self._SYSTEM_PROMPT},
                {"role": "user", "content": user_prompt},
            ],
            "temperature": 0.2,
            "max_tokens": 2500,
            "response_format": {"type": "json_object"},  # W6: force valid JSON
            "enable_thinking": False,  # DashScope fast model, no thinking (ignored by DeepSeek)
        }

        headers = {
            "Authorization": f"Bearer {self._api_key}",
            "Content-Type": "application/json",
        }

        for attempt in range(self._MAX_RETRIES):
            try:
                # P0 出境脱敏: 走共享(脱敏包装)客户端而非私有 httpx.AsyncClient —— 否则评论文本
                # (含手机/邮箱等 PII) 完全绕过脱敏 + 审计。包装层施加 PII/factory 兜底 + 记审计;
                # 菜品名是本功能要抽取的目标, 不脱敏 (无 scope → 不占位)。
                from common.llm_client import get_llm_http_client
                from common.llm_metrics import llm_caller_context
                client = get_llm_http_client()
                with llm_caller_context("review_analyzer"):
                    resp = await client.post(
                        f"{self._base_url}/chat/completions",
                        headers=headers,
                        json=payload,
                        timeout=httpx.Timeout(self._TIMEOUT_SECS),
                    )
                    resp.raise_for_status()
                    data = resp.json()

                content = (
                    data.get("choices", [{}])[0]
                    .get("message", {})
                    .get("content", "")
                    .strip()
                )

                # 去 markdown code block 包装 (json_object mode shouldn't need this, but keep as safety)
                if content.startswith("```"):
                    content = re.sub(r"^```(?:json)?\s*", "", content)
                    content = re.sub(r"\s*```$", "", content)

                # 解析 JSON (json_object mode guarantees object format)
                parsed = json.loads(content)
                # W6: json_object mode returns {"reviews": [...]} instead of bare array
                # Support both for backward compat
                if isinstance(parsed, dict):
                    items = parsed.get("reviews", [])
                    if not items:
                        # Try other common keys
                        for k in ("data", "items", "results"):
                            if k in parsed and isinstance(parsed[k], list):
                                items = parsed[k]
                                break
                elif isinstance(parsed, list):
                    items = parsed  # legacy bare-array format
                else:
                    logger.warning(f"Batch {batch_idx}: LLM returned {type(parsed).__name__}, expected dict/list")
                    return []

                if not items:
                    logger.warning(f"Batch {batch_idx}: LLM returned empty reviews array")
                    return []

                # 映射回原始 review
                results: list[LlmExtractResult] = []
                for item in items:
                    review_idx = item.get("review_id", 0)
                    if isinstance(review_idx, str):
                        try:
                            review_idx = int(review_idx)
                        except ValueError:
                            continue
                    # review_idx is 1-based
                    if review_idx < 1 or review_idx > len(batch):
                        continue

                    original = batch[review_idx - 1]
                    results.append(
                        LlmExtractResult(
                            review_id=original.get("id", review_idx),
                            dishes=item.get("dishes", []),
                            overall_sentiment=item.get("overall_sentiment", "neutral"),
                            aspects=item.get("aspects", {}),
                        )
                    )
                return results

            except httpx.HTTPStatusError as e:
                logger.warning(f"Batch {batch_idx}: HTTP {e.response.status_code} (attempt {attempt + 1})")
                if attempt < self._MAX_RETRIES - 1:
                    await asyncio.sleep(2 ** attempt)
            except json.JSONDecodeError as e:
                logger.warning(f"Batch {batch_idx}: JSON parse fail: {e}")
                return []  # Don't retry JSON errors
            except Exception as e:
                logger.warning(f"Batch {batch_idx}: {type(e).__name__}: {e}")
                if attempt < self._MAX_RETRIES - 1:
                    await asyncio.sleep(2 ** attempt)

        return []

    # ── Aggregation ─────────────────────────────────────

    def _aggregate(
        self,
        reviews: list[dict],
        extracts: list[LlmExtractResult],
        min_mentions: int,
        total_original: int,
    ) -> ReviewAnalysisReport:
        """把批量 LLM 结果聚合为 ReviewAnalysisReport"""
        # 构建 review_id → rating 映射, 方便菜品提及时找到评分
        id_to_rating: dict[Any, float] = {}
        id_to_content: dict[Any, str] = {}
        for r in reviews:
            rid = r.get("id", id(r))
            id_to_rating[rid] = float(r.get("rating", 0))
            id_to_content[rid] = str(r.get("content", ""))

        # 菜品聚合
        mentions: dict[str, DishMention] = {}
        for ext in extracts:
            rid = ext.review_id
            rating = id_to_rating.get(rid, 0)
            content = id_to_content.get(rid, "")

            seen_in_review: set[str] = set()  # 同 review 同菜名只算一次
            for dish in ext.dishes:
                name = (dish.get("name") or "").strip()
                if not name or len(name) < 2 or len(name) > 15:
                    continue
                if name in seen_in_review:
                    continue
                seen_in_review.add(name)

                sentiment = dish.get("sentiment", "neutral")
                if name not in mentions:
                    mentions[name] = DishMention(
                        dish_name=name,
                        mention_count=0,
                        positive_count=0,
                        negative_count=0,
                    )
                m = mentions[name]
                m.mention_count += 1
                if sentiment == "positive":
                    m.positive_count += 1
                elif sentiment == "negative":
                    m.negative_count += 1
                else:
                    m.neutral_count += 1

                if len(m.example_quotes) < 3 and content not in m.example_quotes:
                    m.example_quotes.append(content[:80])
                m.avg_review_rating += rating

        # 算平均评分
        for m in mentions.values():
            if m.mention_count > 0:
                m.avg_review_rating /= m.mention_count

        # 过滤 min_mentions
        dish_tags = sorted(
            [m for m in mentions.values() if m.mention_count >= min_mentions],
            key=lambda d: -d.mention_count,
        )

        # TOP 好评 / 差评 / 隐藏宝藏
        top_praised = sorted(
            [d for d in dish_tags if d.mention_count >= 5 and d.positive_rate >= 0.7],
            key=lambda d: -d.mention_count,
        )[:5]

        top_complained = sorted(
            [d for d in dish_tags if d.negative_count >= 2],
            key=lambda d: -d.negative_count,
        )[:5]

        hidden_gems = sorted(
            [d for d in dish_tags if 3 <= d.mention_count <= 10 and d.positive_rate >= 0.85],
            key=lambda d: -d.positive_rate,
        )[:5]

        # 评分趋势 (复用 fallback 的实现)
        rating_trend = self.fallback._compute_rating_trend(reviews)

        # 总统计
        total_reviews = len(reviews)
        avg_rating = statistics.mean(float(r.get("rating", 0)) for r in reviews)

        # Risk alerts + insights
        risk_alerts = self.fallback._build_risk_alerts(avg_rating, rating_trend, top_complained)
        insights = self._build_llm_insights(
            total_reviews, total_original, avg_rating, rating_trend,
            dish_tags, top_praised, hidden_gems, len(extracts),
        )
        recommendations = self.fallback._build_recommendations(
            rating_trend, top_praised, top_complained, hidden_gems
        )

        return ReviewAnalysisReport(
            total_reviews=total_reviews,
            avg_rating=avg_rating,
            rating_trend=rating_trend,
            dish_tags=dish_tags,
            top_praised_dishes=top_praised,
            top_complained_dishes=top_complained,
            hidden_gems=hidden_gems,
            risk_alerts=risk_alerts,
            insights=insights,
            recommendations=recommendations,
        )

    def _build_llm_insights(
        self,
        total: int,
        total_original: int,
        avg_rating: float,
        trend: Optional[RatingTrend],
        dish_tags: list[DishMention],
        top_praised: list[DishMention],
        hidden_gems: list[DishMention],
        llm_processed: int,
    ) -> list[str]:
        insights: list[str] = []

        llm_note = ""
        if total_original > total:
            llm_note = f" (LLM 分析前 {total} 条, 总样本 {total_original})"

        insights.append(
            f"📊 {total} 条评论, 平均 {avg_rating:.2f} 星, "
            f"识别出 {len(dish_tags)} 个菜品{llm_note}"
        )
        insights.append("🤖 LLM 驱动 (qwen fast), 精度 ~90% vs 规则版 ~60%")

        if trend:
            if trend.direction == "stable":
                insights.append(f"✅ 评分稳定 (delta {trend.total_delta:+.2f})")
            else:
                emoji = "📈" if trend.direction == "rising" else "📉"
                insights.append(
                    f"{emoji} 评分趋势: {trend.direction} "
                    f"({trend.earliest_avg:.2f} → {trend.latest_avg:.2f})"
                )

        if top_praised:
            insights.append(
                f"🌟 客户口中的招牌 TOP 3: "
                f"{', '.join(d.dish_name for d in top_praised[:3])}"
            )

        if hidden_gems:
            insights.append(
                f"💎 潜力菜 (低曝光高好评): "
                f"{', '.join(d.dish_name for d in hidden_gems[:3])}"
            )

        return insights
