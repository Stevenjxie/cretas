from __future__ import annotations
"""
Chart Recommender Service

Uses LLM to intelligently recommend charts based on data features and business scenarios.
Implements "LLM detection + result cache" pattern (Mode B) for efficiency.

Features:
1. LLM-powered chart recommendations
2. Structure-based caching to reduce API calls
3. Fallback to minimal rule-based recommendations
"""
import hashlib
import logging
import json
import re
import time
from typing import Any, Optional, List, Dict
from dataclasses import dataclass, asdict, field
from enum import Enum

import httpx

from config import get_settings
from common.utils.json_parser import robust_json_parse

logger = logging.getLogger(__name__)


class ChartCategory(str, Enum):
    """Chart category for grouping recommendations"""
    TREND = "trend"           # Time series analysis
    COMPARISON = "comparison"  # Categorical comparison
    PROPORTION = "proportion"  # Part-to-whole
    DISTRIBUTION = "distribution"  # Data distribution
    RELATIONSHIP = "relationship"  # Correlation
    FLOW = "flow"             # Process/flow analysis
    KPI = "kpi"               # Single value metrics
    HIERARCHY = "hierarchy"   # Hierarchical data


@dataclass
class ChartRecommendation:
    """
    LLM-generated chart recommendation.

    Attributes:
        chart_type: Type of chart (line, bar, pie, etc.)
        title: Suggested chart title
        reason: Why this chart is recommended
        x_axis: Field name for X-axis
        y_axis: Field name(s) for Y-axis
        series: Field name for series grouping (optional)
        priority: Recommendation priority (1=highest)
        category: Chart category for grouping
        confidence: LLM confidence score (0-1)
        config_hints: Additional configuration hints
    """
    chart_type: str
    title: str
    reason: str
    x_axis: Optional[str] = None
    y_axis: Optional[List[str]] = None
    series: Optional[str] = None
    priority: int = 1
    category: str = "comparison"
    confidence: float = 0.8
    config_hints: Dict[str, Any] = field(default_factory=dict)

    def to_dict(self) -> Dict[str, Any]:
        """Convert to dictionary for JSON serialization"""
        return asdict(self)


@dataclass
class DataSummary:
    """
    Summary of data for chart recommendation.

    Attributes:
        columns: List of column definitions with name, type, sample values
        row_count: Number of data rows
        dimensions: Identified dimension fields (time, category, etc.)
        measures: Identified measure fields (numeric values)
        time_columns: Fields identified as time-related
        category_columns: Fields identified as categorical
    """
    columns: List[Dict[str, Any]]
    row_count: int = 0
    dimensions: List[str] = field(default_factory=list)
    measures: List[str] = field(default_factory=list)
    time_columns: List[str] = field(default_factory=list)
    category_columns: List[str] = field(default_factory=list)
    # A4 (Apr 20 2026): per-column unique_count — chart recommender uses this
    # to refuse x-axis with cardinality < 2 (single-store upload like qhj shows
    # only "表E-份" as x-axis because the only dim column collapsed to 1 value).
    cardinality: Dict[str, int] = field(default_factory=dict)

    @classmethod
    def from_feature_results(cls, features: List[Dict], row_count: int = 0) -> "DataSummary":
        """
        Create DataSummary from DataFeatureResult list.

        Args:
            features: List of column feature dictionaries
            row_count: Number of data rows
        """
        dimensions = []
        measures = []
        time_columns = []
        category_columns = []
        cardinality: Dict[str, int] = {}

        for f in features:
            col_name = f.get("columnName", f.get("column_name", ""))
            data_type = f.get("dataType", f.get("data_type", "TEXT"))

            if data_type == "DATE":
                dimensions.append(col_name)
                time_columns.append(col_name)
            elif data_type == "NUMERIC":
                measures.append(col_name)
            elif data_type == "CATEGORICAL":
                dimensions.append(col_name)
                category_columns.append(col_name)
            elif data_type == "ID":
                dimensions.append(col_name)

            # A4: cache unique count (supports camelCase + snake_case + fallback)
            uc = f.get("uniqueCount", f.get("unique_count", f.get("unique_values")))
            if uc is not None and col_name:
                try:
                    cardinality[col_name] = int(uc)
                except (TypeError, ValueError):
                    pass

        return cls(
            columns=features,
            row_count=row_count,
            dimensions=dimensions,
            measures=measures,
            time_columns=time_columns,
            category_columns=category_columns,
            cardinality=cardinality,
        )


@dataclass
class ChartCacheEntry:
    """Cache entry for chart recommendations"""
    cache_key: str
    recommendations: List[ChartRecommendation]
    scenario: str
    created_at: float
    accessed_at: float
    access_count: int = 1


class ChartRecommendationCache:
    """
    Cache for chart recommendation results (Mode B).

    Uses data structure signature for cache keys,
    allowing same-structure data to reuse recommendation results.
    """

    def __init__(self, ttl_seconds: int = 3600, max_entries: int = 500):
        self._cache: Dict[str, ChartCacheEntry] = {}
        self._ttl_seconds = ttl_seconds
        self._max_entries = max_entries

    def _generate_cache_key(
        self,
        data_summary: DataSummary,
        scenario: str,
        user_intent: Optional[str] = None
    ) -> str:
        """
        Generate cache key from data structure and scenario.

        Apr 25 (D2.B1): Cache key dropped column NAMES — keeps column COUNT
        + sorted dtype signature only. The previous key included sorted
        column names, so two customers' POS exports that both have 232
        columns but use slightly different headers (e.g. 「门店」 vs
        「门店名称」) would never share a cache entry. Production stats
        showed only 1 entry / 1 hit total. Dropping names lets similar-
        shape Excels hit cache regardless of header naming.

        Key is now based on:
        - Column count (cardinality) + sorted dtype histogram
        - Row-count bucket (so tiny vs large files don't share charts)
        - Counts of time/category/measure dimensions
        - Business scenario
        - User intent (if provided)
        """
        # Apr 25 (D2.B1): drop names, keep dtype histogram + counts.
        col_types = sorted([
            c.get("dataType", c.get("data_type", ""))
            for c in data_summary.columns
        ])

        # Bucket row_count so small variations don't bust cache. Buckets
        # mirror the LLM-skip threshold (50) and common dataset sizes.
        rc = int(getattr(data_summary, 'row_count', 0))
        if rc < 50:
            rc_bucket = "tiny"
        elif rc < 1000:
            rc_bucket = "small"
        elif rc < 50_000:
            rc_bucket = "med"
        else:
            rc_bucket = "large"

        signature_parts = [
            f"cols={len(col_types)}",
            "types=" + "|".join(col_types),
            f"rc={rc_bucket}",
            f"time={len(data_summary.time_columns)}",
            f"cat={len(data_summary.category_columns)}",
            f"meas={len(data_summary.measures)}",
            scenario,
            user_intent or ""
        ]

        signature = "||".join(signature_parts)
        return hashlib.md5(signature.encode()).hexdigest()[:16]

    def get(
        self,
        data_summary: DataSummary,
        scenario: str,
        user_intent: Optional[str] = None
    ) -> Optional[List[ChartRecommendation]]:
        """Get cached recommendations if available."""
        cache_key = self._generate_cache_key(data_summary, scenario, user_intent)
        entry = self._cache.get(cache_key)

        if entry is None:
            return None

        # Check TTL
        if time.time() - entry.created_at > self._ttl_seconds:
            del self._cache[cache_key]
            return None

        # Update access stats
        entry.accessed_at = time.time()
        entry.access_count += 1

        logger.debug(f"Chart recommendation cache hit: {cache_key}")
        return entry.recommendations

    def set(
        self,
        data_summary: DataSummary,
        scenario: str,
        recommendations: List[ChartRecommendation],
        user_intent: Optional[str] = None
    ) -> str:
        """Cache chart recommendations."""
        cache_key = self._generate_cache_key(data_summary, scenario, user_intent)

        entry = ChartCacheEntry(
            cache_key=cache_key,
            recommendations=recommendations,
            scenario=scenario,
            created_at=time.time(),
            accessed_at=time.time()
        )

        self._cache[cache_key] = entry

        # Evict old entries if needed
        if len(self._cache) > self._max_entries:
            self._evict_old_entries()

        logger.debug(f"Chart recommendations cached: {cache_key}")
        return cache_key

    def _evict_old_entries(self):
        """Remove oldest entries when cache is full."""
        if len(self._cache) <= self._max_entries:
            return

        sorted_keys = sorted(
            self._cache.keys(),
            key=lambda k: self._cache[k].accessed_at
        )

        to_remove = max(1, len(sorted_keys) // 10)
        for key in sorted_keys[:to_remove]:
            del self._cache[key]

        logger.debug(f"Evicted {to_remove} old chart cache entries")

    def get_stats(self) -> Dict[str, Any]:
        """Get cache statistics."""
        now = time.time()
        active = [e for e in self._cache.values()
                  if now - e.created_at < self._ttl_seconds]

        return {
            "total_entries": len(self._cache),
            "active_entries": len(active),
            "total_hits": sum(e.access_count - 1 for e in self._cache.values())
        }

    def clear(self):
        """Clear all cache entries."""
        self._cache.clear()
        logger.info("Chart recommendation cache cleared")


class ChartRecommender:
    """
    LLM-based chart recommender service.

    Uses LLM to analyze data features and business scenarios to recommend
    the most suitable chart types. Does not use hardcoded rule mappings.
    """

    # Available chart types with descriptions for LLM context
    CHART_TYPES = {
        "line": {
            "name": "折线图",
            "description": "展示数据随时间或顺序的变化趋势",
            "best_for": ["时间序列", "趋势分析", "多系列对比"],
            "requires": {"x_axis": True, "y_axis": True}
        },
        "bar": {
            "name": "柱状图",
            "description": "对比不同类别的数值大小",
            "best_for": ["分类对比", "排名", "分组比较"],
            "requires": {"x_axis": True, "y_axis": True}
        },
        "bar_horizontal": {
            "name": "水平柱状图",
            "description": "适合长标签的分类对比",
            "best_for": ["长标签对比", "排名展示"],
            "requires": {"x_axis": True, "y_axis": True}
        },
        "pie": {
            "name": "饼图",
            "description": "展示各部分占总体的比例",
            "best_for": ["占比分析", "构成分析"],
            "requires": {"x_axis": True, "y_axis": True},
            "constraints": "类别数建议不超过8个"
        },
        "donut": {
            "name": "环形图",
            "description": "饼图变体，中心可显示汇总信息",
            "best_for": ["占比分析", "KPI展示"],
            "requires": {"x_axis": True, "y_axis": True}
        },
        "area": {
            "name": "面积图",
            "description": "展示累计趋势和整体变化",
            "best_for": ["累计趋势", "堆叠对比"],
            "requires": {"x_axis": True, "y_axis": True}
        },
        "scatter": {
            "name": "散点图",
            "description": "展示两个变量之间的相关性",
            "best_for": ["相关性分析", "异常检测", "分布分析"],
            "requires": {"x_axis": True, "y_axis": True}
        },
        "heatmap": {
            "name": "热力图",
            "description": "展示二维数据的分布强度",
            "best_for": ["交叉分析", "密度分布", "时间热力"],
            "requires": {"x_axis": True, "y_axis": True, "series": True}
        },
        "waterfall": {
            "name": "瀑布图",
            "description": "展示数据的增减变化过程",
            "best_for": ["财务分析", "增减分析", "成本构成"],
            "requires": {"x_axis": True, "y_axis": True}
        },
        "radar": {
            "name": "雷达图",
            "description": "多维度对比分析",
            "best_for": ["多维对比", "能力评估", "均衡分析"],
            "requires": {"y_axis": True},
            "constraints": "维度数建议3-8个"
        },
        "funnel": {
            "name": "漏斗图",
            "description": "展示转化过程的阶段数据",
            "best_for": ["转化分析", "流程效率", "销售漏斗"],
            "requires": {"x_axis": True, "y_axis": True}
        },
        "gauge": {
            "name": "仪表盘",
            "description": "展示单一KPI的达成情况",
            "best_for": ["KPI展示", "目标达成", "进度监控"],
            "requires": {"y_axis": True}
        },
        "treemap": {
            "name": "矩阵树图",
            "description": "展示层级数据的面积占比",
            "best_for": ["层级占比", "分类面积对比", "部门预算分布"],
            "requires": {"x_axis": True, "y_axis": True}
        },
        "sunburst": {
            "name": "旭日图",
            "description": "展示层级数据的嵌套结构",
            "best_for": ["层级结构", "多级分类", "组织架构"],
            "requires": {"x_axis": True, "y_axis": True}
        },
        "pareto": {
            "name": "帕累托图",
            "description": "80/20分析，找出主要因素",
            "best_for": ["80/20分析", "问题诊断", "优先级排序"],
            "requires": {"x_axis": True, "y_axis": True}
        },
        "dual_axis": {
            "name": "双Y轴图",
            "description": "不同量纲指标的对比分析",
            "best_for": ["多指标对比", "不同量纲", "趋势关联"],
            "requires": {"x_axis": True, "y_axis": True}
        },
        "bullet": {
            "name": "子弹图",
            "description": "目标与实际值对比",
            "best_for": ["目标达成", "KPI对比", "进度条"],
            "requires": {"x_axis": True, "y_axis": True}
        },
        "sankey": {
            "name": "桑基图",
            "description": "展示数据的流向和转化关系",
            "best_for": ["流向分析", "转化路径", "资金流向", "成本分解"],
            "requires": {"x_axis": True, "y_axis": True}
        },
        "wordcloud": {
            "name": "词云图",
            "description": "展示文本关键词的频率分布",
            "best_for": ["关键词分析", "文本频率", "标签云", "热门词汇"],
            "requires": {"x_axis": True, "y_axis": True},
            "constraints": "需要文本列和频率/权重列"
        },
        "slope": {
            "name": "斜率图",
            "description": "对比两个时间点之间的变化",
            "best_for": ["前后对比", "两期对比", "排名变化"],
            "requires": {"x_axis": True, "y_axis": True},
            "constraints": "适合两列数值的对比"
        },
        "matrix_heatmap": {
            "name": "矩阵热力图",
            "description": "展示交叉分析的密度和强度",
            "best_for": ["交叉分析", "二维分布", "关联矩阵"],
            "requires": {"x_axis": True, "y_axis": True, "series": True}
        },
        "combination": {
            "name": "组合图",
            "description": "柱状图和折线图的组合",
            "best_for": ["多指标趋势", "对比与趋势结合"],
            "requires": {"x_axis": True, "y_axis": True}
        },
        "nested_donut": {
            "name": "嵌套环形图",
            "description": "多层级的环形图",
            "best_for": ["多层级占比", "分类嵌套"],
            "requires": {"x_axis": True, "y_axis": True, "series": True}
        }
    }

    # Business scenarios for context
    SCENARIOS = {
        "sales": "销售分析 - 关注收入、订单、客户、产品、地区等",
        "finance": "财务分析 - 关注利润、成本、预算、现金流等",
        "marketing": "营销分析 - 关注转化、获客、ROI、渠道效果等",
        "operations": "运营分析 - 关注效率、产能、库存、周转等",
        "hr": "人力资源 - 关注人员、绩效、成本、流动等",
        "general": "通用分析 - 数据探索和趋势分析"
    }

    def __init__(self):
        self.settings = get_settings()
        self._cache = ChartRecommendationCache(ttl_seconds=3600, max_entries=500)

    @property
    def client(self) -> httpx.AsyncClient:
        from common.llm_client import get_llm_http_client
        return get_llm_http_client()

    async def recommend(
        self,
        data_summary: DataSummary | Dict[str, Any],
        scenario: str = "general",
        user_intent: Optional[str] = None,
        max_recommendations: int = 5,
        use_cache: bool = True
    ) -> List[ChartRecommendation]:
        """
        Use LLM to recommend the best chart types for the given data.

        Implements Mode B: LLM detection + result cache
        - Check cache first (same structure → same recommendations)
        - If no cache, call LLM and cache the result

        Args:
            data_summary: Summary of data features (DataSummary or dict)
            scenario: Business scenario (sales, finance, marketing, etc.)
            user_intent: Optional user-specified analysis intent
            max_recommendations: Maximum number of recommendations to return
            use_cache: Whether to use cached results (default: True)

        Returns:
            List of ChartRecommendation sorted by priority
        """
        # Convert dict to DataSummary if needed
        if isinstance(data_summary, dict):
            data_summary = DataSummary(
                columns=data_summary.get("columns", []),
                row_count=data_summary.get("row_count", 0),
                dimensions=data_summary.get("dimensions", []),
                measures=data_summary.get("measures", []),
                time_columns=data_summary.get("time_columns", []),
                category_columns=data_summary.get("category_columns", [])
            )

        # 1. Check cache (Mode B: result cache)
        if use_cache:
            cached = self._cache.get(data_summary, scenario, user_intent)
            if cached:
                # Apr 25 (D2.B1): cache key now drops column names. The cached
                # recommendation may carry x_axis/y_axis values that don't exist
                # in the current data_summary's columns — remap them to the
                # actual columns using dtype/role before returning, so the
                # frontend chart builder doesn't render an empty chart.
                remapped = self._remap_cached_recommendations(cached, data_summary)
                logger.info("Using cached chart recommendations (remapped to current columns)")
                return remapped[:max_recommendations]

        # 1b. Apr 25 (D2.B1): skip LLM entirely for tiny uploads. The 24s
        # qwen-plus round-trip is wasted on 5–50 row test/synthetic data —
        # the rule-based fallback produces a perfectly fine default chart
        # (column 1 = x-axis, first numeric = y-axis). Saves ~24s per
        # tiny-upload sheet analysis.
        TINY_ROW_THRESHOLD = 50
        if data_summary.row_count > 0 and data_summary.row_count < TINY_ROW_THRESHOLD:
            logger.info(
                f"Tiny upload ({data_summary.row_count} rows < {TINY_ROW_THRESHOLD}) — "
                f"using rule-based fallback, skipping LLM"
            )
            recommendations = self._minimal_fallback(data_summary, scenario)
            if use_cache and recommendations:
                self._cache.set(data_summary, scenario, recommendations, user_intent)
            return recommendations[:max_recommendations]

        # 2. Fallback if no LLM configured
        if not self.settings.llm_api_key:
            logger.warning("LLM API key not configured, using minimal fallback")
            recommendations = self._minimal_fallback(data_summary, scenario)
            if use_cache:
                self._cache.set(data_summary, scenario, recommendations, user_intent)
            return recommendations

        # 3. Call LLM for new recommendations
        prompt = self._build_recommendation_prompt(
            data_summary, scenario, user_intent, max_recommendations
        )

        try:
            response = await self._call_llm(prompt)
            recommendations = self._parse_llm_response(response, data_summary)

            # Sort by priority and limit
            recommendations.sort(key=lambda x: x.priority)
            recommendations = recommendations[:max_recommendations]

            # 4. Cache the result
            if use_cache and recommendations:
                self._cache.set(data_summary, scenario, recommendations, user_intent)
                logger.info(f"Cached {len(recommendations)} chart recommendations")

            return recommendations

        except Exception as e:
            logger.error(f"LLM recommendation failed: {e}", exc_info=True)
            return self._minimal_fallback(data_summary, scenario)

    def get_cache_stats(self) -> Dict[str, Any]:
        """Get cache statistics."""
        return self._cache.get_stats()

    def clear_cache(self):
        """Clear the recommendation cache."""
        self._cache.clear()

    async def recommend_for_question(
        self,
        question: str,
        data_summary: DataSummary | Dict[str, Any],
        scenario: str = "general"
    ) -> List[ChartRecommendation]:
        """
        Recommend charts based on a natural language question.

        Args:
            question: User's analysis question
            data_summary: Summary of available data
            scenario: Business scenario

        Returns:
            List of ChartRecommendation tailored to the question
        """
        return await self.recommend(
            data_summary=data_summary,
            scenario=scenario,
            user_intent=question
        )

    async def recommend_combination(
        self,
        data_summary: DataSummary | Dict[str, Any],
        scenario: str = "general",
        dashboard_layout: str = "2x2"
    ) -> Dict[str, Any]:
        """
        Recommend a combination of charts for a dashboard.

        Args:
            data_summary: Summary of data features
            scenario: Business scenario
            dashboard_layout: Dashboard layout (e.g., "2x2", "1+3", "3x2")

        Returns:
            Dashboard recommendation with layout and charts
        """
        if isinstance(data_summary, dict):
            data_summary = DataSummary(
                columns=data_summary.get("columns", []),
                row_count=data_summary.get("row_count", 0),
                dimensions=data_summary.get("dimensions", []),
                measures=data_summary.get("measures", []),
                time_columns=data_summary.get("time_columns", []),
                category_columns=data_summary.get("category_columns", [])
            )

        if not self.settings.llm_api_key:
            return self._minimal_dashboard_fallback(data_summary, dashboard_layout)

        prompt = self._build_dashboard_prompt(data_summary, scenario, dashboard_layout)

        try:
            response = await self._call_llm(prompt)
            return self._parse_dashboard_response(response, data_summary, dashboard_layout)
        except Exception as e:
            logger.error(f"Dashboard recommendation failed: {e}")
            return self._minimal_dashboard_fallback(data_summary, dashboard_layout)

    def _build_recommendation_prompt(
        self,
        data_summary: DataSummary,
        scenario: str,
        user_intent: Optional[str],
        max_recommendations: int
    ) -> str:
        """Build the LLM prompt for chart recommendation"""

        # Build column descriptions
        columns_desc = []
        for col in data_summary.columns:
            col_name = col.get("columnName", col.get("column_name", "unknown"))
            data_type = col.get("dataType", col.get("data_type", "TEXT"))
            sub_type = col.get("numericSubType", col.get("numeric_sub_type", ""))
            samples = col.get("sampleValues", col.get("sample_values", []))[:3]

            desc = f"  - {col_name}: 类型={data_type}"
            if sub_type:
                desc += f", 子类型={sub_type}"
            if samples:
                desc += f", 示例={samples}"
            columns_desc.append(desc)

        columns_text = "\n".join(columns_desc) if columns_desc else "  (无列信息)"

        # Build chart types reference
        chart_types_ref = []
        for chart_id, info in self.CHART_TYPES.items():
            chart_types_ref.append(f"  - {chart_id}: {info['name']} - {info['description']}")
        chart_types_text = "\n".join(chart_types_ref)

        # Get scenario description
        scenario_desc = self.SCENARIOS.get(scenario, self.SCENARIOS["general"])

        prompt = f"""作为专业数据分析师，请根据以下数据特征推荐最合适的图表类型。

## 数据特征

**列信息:**
{columns_text}

**数据概览:**
- 数据行数: {data_summary.row_count} 行
- 时间维度: {data_summary.time_columns or '无'}
- 分类维度: {self._format_cat_cols_with_cardinality(data_summary) or '无'}
- 度量指标: {data_summary.measures or '无'}

**业务场景:** {scenario_desc}

{f'**用户需求:** {user_intent}' if user_intent else ''}

## 可用图表类型

{chart_types_text}

## 任务

请推荐最多 {max_recommendations} 个最适合的图表类型。对于每个推荐：
1. 选择最能展示数据价值的图表类型
2. 明确指定 X轴、Y轴 和系列字段
3. 给出具体的推荐理由
4. 设置优先级 (1=最推荐)

## 输出格式

请返回 JSON 格式：
```json
{{
    "recommendations": [
        {{
            "chart_type": "图表类型ID",
            "title": "推荐的图表标题",
            "reason": "推荐理由（说明为什么这个图表类型最适合展示这些数据）",
            "x_axis": "X轴字段名",
            "y_axis": ["Y轴字段名列表"],
            "series": "系列分组字段（可选，null表示无）",
            "priority": 1,
            "category": "trend/comparison/proportion/distribution/relationship/flow/kpi/hierarchy",
            "confidence": 0.9,
            "config_hints": {{}}
        }}
    ],
    "analysis_summary": "数据分析总结和图表组合建议"
}}
```

注意：
- 不要推荐数据不支持的图表类型（如没有时间维度就不推荐折线图）
- 优先推荐能直观展示业务洞察的图表
- 考虑数据量和类别数量的限制
- **多样性要求**: 推荐的图表类型必须尽可能多样化，不允许连续2个相同类型
- **高级类型**: 如果数据有层级结构推荐 sunburst，如果需要80/20分析推荐 pareto，如果有目标/实际对比推荐 bullet，如果有不同量纲指标推荐 dual_axis
- **最少4种不同类型**: 你的推荐中至少包含4种不同的图表类型
- **A4 cardinality 守卫**: 若某分类维度 unique_count<2（即全部行只有1个取值），绝对不要把它作为 x_axis 推荐对比/占比图；这种情况应优先推 kpi/scorecard 单值卡片或依靠时间维度作趋势图。  # noqa: E501
"""
        return prompt

    @staticmethod
    def _format_cat_cols_with_cardinality(data_summary: DataSummary) -> str:
        """Render category_columns with their unique counts for LLM context (A4)."""
        if not data_summary.category_columns:
            return ""
        parts = []
        for c in data_summary.category_columns:
            uc = data_summary.cardinality.get(c)
            parts.append(f"{c}(unique={uc})" if uc is not None else c)
        return ", ".join(parts)

    def _build_dashboard_prompt(
        self,
        data_summary: DataSummary,
        scenario: str,
        layout: str
    ) -> str:
        """Build prompt for dashboard recommendation"""

        # Parse layout to get chart count
        if layout == "2x2":
            chart_count = 4
        elif layout == "1+3":
            chart_count = 4
        elif layout == "3x2":
            chart_count = 6
        else:
            chart_count = 4

        base_prompt = self._build_recommendation_prompt(
            data_summary, scenario, None, chart_count
        )

        dashboard_instruction = f"""
## 附加要求：仪表板布局

请为 {layout} 布局的仪表板推荐图表组合：
- 图表之间应该互补，覆盖不同的分析角度
- 第一个图表应该展示最重要的 KPI 或趋势
- 避免重复使用相同类型的图表
- 考虑视觉平衡和信息层次

在 JSON 输出中增加:
```json
{{
    "dashboard": {{
        "layout": "{layout}",
        "positions": [
            {{"chart_index": 0, "position": "左上/main", "size": "large/medium/small"}}
        ],
        "theme_suggestion": "推荐的配色主题"
    }}
}}
```
"""
        return base_prompt + dashboard_instruction

    async def _call_llm(self, prompt: str) -> str:
        """Call LLM via multi-provider router (chart slot).

        Chain: aliyun_b (glm-5) → aliyun_a (glm-5) → zhipu (glm-4.5-air) → deepseek.
        403 FreeTierOnly / 429 triggers automatic fallback to next provider.
        Caller context ("chart") + factory_id (from JWT middleware) are
        recorded in smart_bi_llm_usage automatically.
        """
        from common.llm_router import call_chain, SLOT
        from common.llm_metrics import llm_caller_context

        payload = {
            "messages": [
                {"role": "system", "content": (
                    "你是专业的数据可视化分析师，擅长根据数据特征推荐最佳图表类型。"
                    "你的推荐应该基于数据的实际特征，而不是固定规则。"
                    "请用 JSON 格式回复，确保字段名和值都正确。"
                )},
                {"role": "user", "content": prompt},
            ],
            "temperature": 0.5,
            "max_tokens": 3500,
            "enable_thinking": False,
        }

        with llm_caller_context("chart"):
            result = await call_chain(SLOT.CHART, payload)
        return result["choices"][0]["message"]["content"]

    def _remap_cached_recommendations(
        self,
        cached: List[ChartRecommendation],
        data_summary: DataSummary,
    ) -> List[ChartRecommendation]:
        """
        Remap cached recommendation x/y/series fields to current data columns.

        Apr 25 (D2.B1): cache key drops column NAMES so similar-shape data
        hits cache. But the cached ChartRecommendation still carries the
        original x_axis/y_axis names — which may not exist in the new data.
        Map them by role/dtype:
          - x_axis: prefer time_columns[0], else category_columns[0], else dimensions[0]
          - y_axis: prefer first N measures matching original count
          - series: prefer second category_column if any
        Returns a fresh list of ChartRecommendation (does not mutate cache).
        """
        actual_columns = [
            c.get("columnName", c.get("column_name", ""))
            for c in data_summary.columns
        ]
        remapped: List[ChartRecommendation] = []
        for rec in cached:
            # If x_axis still exists, keep it (rare — but common when same
            # tenant re-uploads identical-schema file).
            new_x = rec.x_axis if rec.x_axis in actual_columns else None
            if new_x is None:
                if data_summary.time_columns:
                    new_x = data_summary.time_columns[0]
                elif data_summary.category_columns:
                    new_x = data_summary.category_columns[0]
                elif data_summary.dimensions:
                    new_x = data_summary.dimensions[0]

            # Y-axis: keep ones that still exist, fill from measures
            new_y: List[str] = []
            if rec.y_axis:
                for y in rec.y_axis:
                    if y in actual_columns:
                        new_y.append(y)
            if not new_y and data_summary.measures:
                # Cached y count → take same many from measures (capped at 3)
                want = max(1, min(len(rec.y_axis or []) or 1, 3))
                new_y = data_summary.measures[:want]

            # Series: try to keep, else use 2nd category column if cached one was set
            new_series = rec.series if rec.series in actual_columns else None
            if rec.series is not None and new_series is None:
                if len(data_summary.category_columns) >= 2:
                    new_series = data_summary.category_columns[1]

            remapped.append(ChartRecommendation(
                chart_type=rec.chart_type,
                title=rec.title,
                reason=rec.reason,
                x_axis=new_x,
                y_axis=new_y if new_y else None,
                series=new_series,
                priority=rec.priority,
                category=rec.category,
                confidence=rec.confidence,
                config_hints=dict(rec.config_hints) if rec.config_hints else {},
            ))
        return remapped

    def _validate_column_name(
        self,
        column: Optional[str],
        actual_columns: List[str],
        fallback_columns: List[str]
    ) -> Optional[str]:
        """
        Validate and resolve column name from LLM response.

        Args:
            column: Column name from LLM
            actual_columns: Actual columns in the data
            fallback_columns: Fallback columns to use if not found

        Returns:
            Valid column name or None
        """
        if column is None:
            return fallback_columns[0] if fallback_columns else None

        # LLM occasionally returns x_axis/series as ["商品名称"] instead of "商品名称".
        # Unwrap singletons; for lists with multiple values keep the first (caller
        # only handles one column here — multi-column case lives on the y_axis path).
        if isinstance(column, list):
            column = column[0] if column else None
            if column is None:
                return fallback_columns[0] if fallback_columns else None

        if not isinstance(column, str):
            return fallback_columns[0] if fallback_columns else None

        # 1. Exact match
        if column in actual_columns:
            return column

        # 2. Case-insensitive match
        column_lower = column.lower()
        for col in actual_columns:
            if col.lower() == column_lower:
                return col

        # 3. Partial match
        for col in actual_columns:
            if column_lower in col.lower() or col.lower() in column_lower:
                return col

        # 4. Return fallback
        logger.warning(f"Column '{column}' not found, using fallback")
        return fallback_columns[0] if fallback_columns else None

    def _parse_llm_response(
        self,
        response: str,
        data_summary: DataSummary
    ) -> List[ChartRecommendation]:
        """Parse LLM response into ChartRecommendation objects"""
        try:
            # Fix: Use centralized robust JSON parser
            result = robust_json_parse(response, fallback={})
            if not result:
                logger.warning("Could not extract JSON from LLM response")
                return self._minimal_fallback(data_summary, "general")
            recommendations = []

            # Get actual column names for validation
            actual_columns = [
                c.get("columnName", c.get("column_name", ""))
                for c in data_summary.columns
            ]

            raw_recs = result.get("recommendations") or []
            for rec in raw_recs:
                if not isinstance(rec, dict):
                    continue
                # Validate chart type
                chart_type = rec.get("chart_type", "bar")
                if chart_type not in self.CHART_TYPES:
                    logger.warning(f"Unknown chart type: {chart_type}, using 'bar'")
                    chart_type = "bar"

                # Validate and resolve x_axis
                x_axis_raw = rec.get("x_axis", rec.get("xAxis"))
                x_axis = self._validate_column_name(
                    x_axis_raw,
                    actual_columns,
                    data_summary.dimensions or data_summary.category_columns
                )

                # Validate and resolve y_axis (LLM 返 null 时 rec.get 返 None, 非 [])
                y_axis_raw = rec.get("y_axis", rec.get("yAxis"))
                if y_axis_raw is None:
                    y_axis_raw = []
                if isinstance(y_axis_raw, str):
                    y_axis_raw = [y_axis_raw]

                y_axis = []
                for y in y_axis_raw:
                    resolved = self._validate_column_name(y, actual_columns, data_summary.measures)
                    if resolved:
                        y_axis.append(resolved)

                # If no y_axis resolved, use measures as fallback
                if not y_axis and data_summary.measures:
                    y_axis = data_summary.measures[:2]

                # Validate series field
                series_raw = rec.get("series")
                series = self._validate_column_name(
                    series_raw,
                    actual_columns,
                    []  # No fallback for series
                ) if series_raw else None

                recommendation = ChartRecommendation(
                    chart_type=chart_type,
                    title=rec.get("title", "数据分析"),
                    reason=rec.get("reason", "LLM 推荐"),
                    x_axis=x_axis,
                    y_axis=y_axis if y_axis else None,
                    series=series,
                    priority=rec.get("priority", 1),
                    category=rec.get("category", "comparison"),
                    confidence=rec.get("confidence", 0.8),
                    config_hints=rec.get("config_hints", {})
                )
                recommendations.append(recommendation)

            if not recommendations:
                logger.warning("LLM returned no recommendations, using fallback")
                return self._minimal_fallback(data_summary, "general")

            return recommendations

        except json.JSONDecodeError as e:
            logger.error(f"JSON decode error: {e}")
            logger.debug(f"Response was: {response[:500]}...")
            return self._minimal_fallback(data_summary, "general")
        except Exception as e:
            logger.error(f"Failed to parse LLM response: {e}")
            logger.debug(f"Response was: {response[:500]}...")
            return self._minimal_fallback(data_summary, "general")

    def _robust_json_extract(self, text: str) -> Optional[str]:
        """
        Robustly extract JSON from LLM response.

        Handles:
        - JSON in code blocks
        - Unterminated strings
        - Extra commas
        - Missing quotes

        Args:
            text: Raw LLM response text

        Returns:
            Cleaned JSON string or None if extraction fails
        """
        # 1. Try to extract from code blocks
        if "```json" in text:
            text = text.split("```json")[1].split("```")[0]
        elif "```" in text:
            parts = text.split("```")
            if len(parts) >= 2:
                text = parts[1]

        # 2. Find JSON object boundaries
        json_start = text.find('{')
        json_end = text.rfind('}')

        if json_start < 0 or json_end < json_start:
            return None

        json_str = text[json_start:json_end + 1]

        # 3. Try to fix common issues
        # Remove trailing commas before } or ]
        json_str = re.sub(r',\s*([\}\]])', r'\1', json_str)

        # Try to validate the JSON
        try:
            json.loads(json_str)
            return json_str
        except json.JSONDecodeError:
            pass

        # 4. Try truncating at last valid closing brace
        depth = 0
        last_valid_pos = -1
        in_string = False
        escape_next = False

        for i, char in enumerate(json_str):
            if escape_next:
                escape_next = False
                continue
            if char == '\\':
                escape_next = True
                continue
            if char == '"' and not escape_next:
                in_string = not in_string
                continue
            if in_string:
                continue

            if char == '{':
                depth += 1
            elif char == '}':
                depth -= 1
                if depth == 0:
                    last_valid_pos = i
                    break

        if last_valid_pos > 0:
            truncated = json_str[:last_valid_pos + 1]
            try:
                json.loads(truncated)
                return truncated
            except json.JSONDecodeError:
                pass

        return json_str  # Return original attempt

    def _parse_dashboard_response(
        self,
        response: str,
        data_summary: DataSummary,
        layout: str
    ) -> Dict[str, Any]:
        """Parse LLM response for dashboard recommendation"""
        try:
            recommendations = self._parse_llm_response(response, data_summary)

            # Try to extract dashboard-specific info
            json_str = response
            if "```json" in response:
                json_str = response.split("```json")[1].split("```")[0]

            json_start = json_str.find('{')
            json_end = json_str.rfind('}') + 1
            if json_start >= 0 and json_end > json_start:
                result = json.loads(json_str[json_start:json_end])
                dashboard_info = result.get("dashboard", {})
            else:
                dashboard_info = {}

            return {
                "success": True,
                "layout": layout,
                "recommendations": [r.to_dict() for r in recommendations],
                "dashboard": dashboard_info,
                "method": "llm"
            }

        except Exception as e:
            logger.error(f"Dashboard parsing failed: {e}")
            return self._minimal_dashboard_fallback(data_summary, layout)

    def _minimal_fallback(
        self,
        data_summary: DataSummary,
        scenario: str
    ) -> List[ChartRecommendation]:
        """
        Rule-based fallback when LLM is unavailable.
        Uses data features to recommend diverse chart types.
        """
        recommendations = []

        has_time = len(data_summary.time_columns) > 0
        has_categories = len(data_summary.category_columns) > 0
        has_measures = len(data_summary.measures) > 0
        num_categories = len(data_summary.category_columns)  # noqa: F841
        num_measures = len(data_summary.measures)
        row_count = data_summary.row_count
        cat_col = data_summary.category_columns[0] if has_categories else None
        time_col = data_summary.time_columns[0] if has_time else None

        # A4 (Apr 20 2026): cardinality gate — a category is only useful as an
        # x-axis when it has >=2 distinct values. Without this gate, single-store
        # uploads (or any file where the "店名" column collapses to 1 value)
        # produce empty/misleading bar/pie charts.
        def _cat_has_variety(col: Optional[str]) -> bool:
            if not col:
                return False
            # Default to True when unknown (backwards-compat: no cardinality
            # data → trust existing behavior rather than hide charts)
            uc = data_summary.cardinality.get(col)
            return uc is None or uc >= 2

        cat_has_variety = _cat_has_variety(cat_col)

        # 1. Time series → line chart
        if has_time and has_measures:
            recommendations.append(ChartRecommendation(
                chart_type="line",
                title="趋势分析",
                reason="检测到时间维度和度量指标",
                x_axis=time_col,
                y_axis=data_summary.measures[:3],
                priority=1,
                category="trend"
            ))

        # 2. Category comparison → bar chart
        if has_categories and has_measures and cat_has_variety:
            recommendations.append(ChartRecommendation(
                chart_type="bar",
                title="分类对比",
                reason="检测到分类维度和度量指标",
                x_axis=cat_col,
                y_axis=[data_summary.measures[0]],
                priority=2,
                category="comparison"
            ))

        # 3. Small category count (<=8) → pie chart for composition
        if has_categories and has_measures and row_count <= 15 and cat_has_variety:
            recommendations.append(ChartRecommendation(
                chart_type="pie",
                title="占比分析",
                reason="分类数量适合饼图展示",
                x_axis=cat_col,
                y_axis=[data_summary.measures[0]],
                priority=3,
                category="proportion"
            ))

        # 4. Financial/hierarchical data → waterfall
        if has_categories and has_measures and 3 <= row_count <= 30 and cat_has_variety:
            # Detect financial keywords in column names
            financial_keywords = ['收入', '支出', '费用', '利润', '成本', '净利', 'revenue', 'cost', 'profit', 'expense']
            col_names_lower = ' '.join([c.get('columnName', c.get('column_name', '')) for c in data_summary.columns]).lower()  # noqa: E501
            if any(kw in col_names_lower for kw in financial_keywords):
                recommendations.append(ChartRecommendation(
                    chart_type="waterfall",
                    title="增减分析",
                    reason="检测到财务增减数据，适合瀑布图",
                    x_axis=cat_col,
                    y_axis=[data_summary.measures[0]],
                    priority=4,
                    category="flow"
                ))

        # 5. Multiple measures (>=3) → radar chart
        if has_categories and num_measures >= 3 and row_count <= 10 and cat_has_variety:
            recommendations.append(ChartRecommendation(
                chart_type="radar",
                title="多维对比",
                reason="多个度量指标适合雷达图对比",
                x_axis=cat_col,
                y_axis=data_summary.measures[:6],
                priority=5,
                category="comparison"
            ))

        # 6. Two numeric columns → scatter
        if num_measures >= 2:
            recommendations.append(ChartRecommendation(
                chart_type="scatter",
                title="相关性分析",
                reason="两个度量指标可分析相关性",
                x_axis=data_summary.measures[0],
                y_axis=[data_summary.measures[1]],
                priority=6,
                category="relationship"
            ))

        # 7. Pareto chart — when categories have skewed distribution
        if has_categories and has_measures and row_count >= 5 and cat_has_variety:
            recommendations.append(ChartRecommendation(
                chart_type="pareto",
                title="帕累托分析",
                reason="分类数据适合80/20帕累托分析",
                x_axis=cat_col,
                y_axis=[data_summary.measures[0]],
                priority=7,
                category="distribution"
            ))

        # 8. Boxplot — sufficient rows for statistical distribution
        if has_measures and row_count >= 20:
            recommendations.append(ChartRecommendation(
                chart_type="boxplot",
                title="数据分布",
                reason="数据量充足，适合箱线图展示分布",
                x_axis=cat_col if cat_col else data_summary.measures[0],
                y_axis=data_summary.measures[:4],
                priority=8,
                category="distribution"
            ))

        # 9. Horizontal bar — long category labels
        if has_categories and has_measures and cat_has_variety:
            recommendations.append(ChartRecommendation(
                chart_type="bar_horizontal",
                title="横向排行",
                reason="适合长名称分类的横向对比",
                x_axis=cat_col,
                y_axis=[data_summary.measures[0]],
                priority=9,
                category="comparison"
            ))

        # 10. Treemap — hierarchical or categorical data with values
        if has_categories and has_measures and 3 <= row_count <= 50 and cat_has_variety:
            recommendations.append(ChartRecommendation(
                chart_type="treemap",
                title="面积占比分析",
                reason="分类数据适合矩阵树图展示面积占比",
                x_axis=cat_col,
                y_axis=[data_summary.measures[0]],
                priority=10,
                category="hierarchy"
            ))

        # 11. Wordcloud — text column with frequency/count data
        if has_categories and has_measures and cat_has_variety:
            # Detect text-heavy columns (many unique values, short text)
            col_names_lower = ' '.join([
                c.get('columnName', c.get('column_name', ''))
                for c in data_summary.columns
            ]).lower()
            text_keywords = ['关键词', '标签', 'keyword', 'tag', 'word', '词',
                             '名称', 'name', '品类', '类型', '品牌', 'brand']
            if any(kw in col_names_lower for kw in text_keywords):
                recommendations.append(ChartRecommendation(
                    chart_type="wordcloud",
                    title="关键词云",
                    reason="检测到文本标签列，适合词云展示频率分布",
                    x_axis=cat_col,
                    y_axis=[data_summary.measures[0]],
                    priority=11,
                    category="distribution"
                ))

        # 12. Slope — two numeric columns for before/after comparison
        if num_measures >= 2 and has_categories and row_count <= 20:
            recommendations.append(ChartRecommendation(
                chart_type="slope",
                title="前后对比",
                reason="两个度量指标适合斜率图展示变化",
                x_axis=cat_col,
                y_axis=data_summary.measures[:2],
                priority=12,
                category="comparison"
            ))

        if not recommendations:
            # Ultimate fallback
            recommendations.append(ChartRecommendation(
                chart_type="bar",
                title="数据概览",
                reason="基础数据展示（建议配置 LLM 获取更精准的推荐）",
                priority=1,
                category="comparison"
            ))

        return recommendations

    def _minimal_dashboard_fallback(
        self,
        data_summary: DataSummary,
        layout: str
    ) -> Dict[str, Any]:
        """Minimal dashboard fallback"""
        recommendations = self._minimal_fallback(data_summary, "general")

        return {
            "success": True,
            "layout": layout,
            "recommendations": [r.to_dict() for r in recommendations],
            "dashboard": {"layout": layout},
            "method": "fallback",
            "message": "LLM 不可用，返回基础推荐"
        }

    def get_chart_type_info(self, chart_type: str) -> Optional[Dict[str, Any]]:
        """Get information about a specific chart type"""
        return self.CHART_TYPES.get(chart_type)

    def list_chart_types(self) -> List[Dict[str, Any]]:
        """List all available chart types with descriptions"""
        return [
            {"id": k, **v}
            for k, v in self.CHART_TYPES.items()
        ]

    async def close(self):
        """No-op: shared client lifecycle managed by main.py lifespan"""


# Singleton instance
_recommender_instance: Optional[ChartRecommender] = None


def get_chart_recommender() -> ChartRecommender:
    """Get the global ChartRecommender instance"""
    global _recommender_instance
    if _recommender_instance is None:
        _recommender_instance = ChartRecommender()
    return _recommender_instance
