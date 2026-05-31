from __future__ import annotations
"""
Unified Analyzer Service (LLM-Powered Version)

Provides a single entry point for comprehensive Excel data analysis with:
- LLM-powered field detection, scenario recognition, chart recommendation
- Parallel execution of analysis, chart generation, and insight generation
- Dynamic metric inference instead of hardcoded mappings
- One-call complete analysis results

All modules now use LLM + cache pattern for maximum flexibility.
"""
import asyncio
import logging
import time
from dataclasses import dataclass, field
from typing import Any, Dict, List, Optional, Tuple
from enum import Enum

import pandas as pd
import numpy as np
import httpx

from config import get_settings

# Import cache manager
from services.analysis_cache import (
    CachedData,
    CachedAnalysis,
    get_cache_manager,
    generate_markdown_content
)

# Import data cleaner
from services.data_cleaner import DataCleaner

# Import LLM-powered services (new)
from services.raw_exporter import RawExporter, RawSheetData
from services.field_detector_llm import (
    get_field_detector
)
from services.scenario_detector import (
    get_scenario_detector
)
from services.chart_recommender import (
    DataSummary,
    get_chart_recommender
)
from services.metric_calculator import MetricCalculator
from services.chart_builder import ChartBuilder
from services.insight_generator import InsightGenerator
from services.forecast_service import ForecastService
from services.context_extractor import ContextExtractor, ContextInfo
from services.utils.json_parser import robust_json_parse
from services.utils.dataframe_utils import deduplicate_columns

logger = logging.getLogger(__name__)


class AnalysisDepth(str, Enum):
    """Analysis depth levels"""
    QUICK = "quick"        # Fast overview, basic metrics
    STANDARD = "standard"  # Standard analysis with charts and insights
    DEEP = "deep"          # Deep analysis with predictions and recommendations


@dataclass
class ScenarioResult:
    """Scenario detection result"""
    scenario: str                    # Detected scenario type (dynamic, not enum)
    scenario_name: str               # Human-readable name
    confidence: float                # Detection confidence (0-1)
    evidence: List[str]              # Evidence supporting detection
    dimensions: List[str] = field(default_factory=list)  # Identified dimensions
    measures: List[str] = field(default_factory=list)    # Identified measures
    recommended_analyses: List[str] = field(default_factory=list)  # LLM recommendations
    sub_type: Optional[str] = None   # Sub-type if applicable

    def to_dict(self) -> Dict[str, Any]:
        return {
            "scenario": self.scenario,
            "scenarioName": self.scenario_name,
            "confidence": self.confidence,
            "evidence": self.evidence,
            "dimensions": self.dimensions,
            "measures": self.measures,
            "recommendedAnalyses": self.recommended_analyses,
            "subType": self.sub_type
        }


@dataclass
class MetricResult:
    """Single metric calculation result"""
    name: str                        # Metric name
    value: Optional[float]           # Calculated value
    unit: str                        # Unit (%, 元, etc.)
    success: bool                    # Whether calculation succeeded
    formatted: str                   # Human-readable formatted value
    formula: str = ""                # Calculation formula
    breakdown: Optional[Dict] = None  # Breakdown by dimension
    trend: Optional[str] = None      # up, down, stable
    change_rate: Optional[float] = None  # Change rate if applicable
    category: str = "custom"         # Metric category

    def to_dict(self) -> Dict[str, Any]:
        return {
            "name": self.name,
            "value": self.value,
            "unit": self.unit,
            "success": self.success,
            "formatted": self.formatted,
            "formula": self.formula,
            "breakdown": self.breakdown,
            "trend": self.trend,
            "changeRate": self.change_rate,
            "category": self.category
        }


@dataclass
class ChartConfig:
    """ECharts configuration for visualization"""
    chart_type: str                  # Chart type (line, bar, pie, etc.)
    title: str                       # Chart title
    config: Dict[str, Any]           # ECharts configuration
    description: str                 # Chart description
    reason: str = ""                 # LLM's reason for recommendation
    x_axis: Optional[str] = None     # X-axis field
    y_axis: Optional[List[str]] = None  # Y-axis fields
    recommended: bool = True         # Whether this chart is recommended
    priority: int = 1                # Display priority (1 = highest)
    confidence: float = 0.8          # LLM confidence

    def to_dict(self) -> Dict[str, Any]:
        return {
            "chartType": self.chart_type,
            "title": self.title,
            "config": self.config,
            "description": self.description,
            "reason": self.reason,
            "xAxis": self.x_axis,
            "yAxis": self.y_axis,
            "recommended": self.recommended,
            "priority": self.priority,
            "confidence": self.confidence
        }


@dataclass
class Insight:
    """AI-generated business insight"""
    type: str                        # Insight type (trend, anomaly, etc.)
    title: str                       # Short title
    text: str                        # Detailed description
    metric: Optional[str]            # Related metric
    sentiment: str                   # positive, negative, neutral, warning
    importance: float                # Importance score (1-10)
    recommendation: Optional[str] = None  # Action recommendation
    related_data: Optional[Dict] = None   # Supporting data

    def to_dict(self) -> Dict[str, Any]:
        return {
            "type": self.type,
            "title": self.title,
            "text": self.text,
            "metric": self.metric,
            "sentiment": self.sentiment,
            "importance": self.importance,
            "recommendation": self.recommendation,
            "relatedData": self.related_data
        }


@dataclass
class PredictionResult:
    """Time series prediction result"""
    metric: str                      # Metric being predicted
    algorithm: str                   # Algorithm used
    predictions: List[float]         # Predicted values
    lower_bound: List[float]         # Lower confidence bound
    upper_bound: List[float]         # Upper confidence bound
    periods: int                     # Number of periods predicted
    confidence_level: float          # Confidence level used

    def to_dict(self) -> Dict[str, Any]:
        return {
            "metric": self.metric,
            "algorithm": self.algorithm,
            "predictions": self.predictions,
            "lowerBound": self.lower_bound,
            "upperBound": self.upper_bound,
            "periods": self.periods,
            "confidenceLevel": self.confidence_level
        }


@dataclass
class FieldInfo:
    """Detected field information"""
    name: str                        # Field name
    data_type: str                   # Data type
    semantic_type: str               # Semantic type
    chart_role: str                  # Recommended chart role
    description: str = ""            # LLM description
    sample_values: List[Any] = field(default_factory=list)
    statistics: Optional[Dict] = None
    confidence: float = 0.8

    def to_dict(self) -> Dict[str, Any]:
        return {
            "name": self.name,
            "dataType": self.data_type,
            "semanticType": self.semantic_type,
            "chartRole": self.chart_role,
            "description": self.description,
            "sampleValues": self.sample_values,
            "statistics": self.statistics,
            "confidence": self.confidence
        }


@dataclass
class AnalysisOptions:
    """Options for analysis customization"""
    depth: AnalysisDepth = AnalysisDepth.STANDARD
    max_charts: int = 5              # Maximum charts to generate
    max_insights: int = 5            # Maximum insights to generate
    include_predictions: bool = True  # Whether to include predictions
    prediction_periods: int = 3      # Periods to predict
    language: str = "zh"             # Output language (zh, en)
    # Cache options
    use_cache: bool = True           # Whether to use caching
    save_to_cache: bool = True       # Whether to save results to cache
    force_refresh: bool = False      # Force refresh even if cached

    def to_dict(self) -> Dict[str, Any]:
        return {
            "depth": self.depth.value,
            "maxCharts": self.max_charts,
            "maxInsights": self.max_insights,
            "includePredictions": self.include_predictions,
            "predictionPeriods": self.prediction_periods,
            "language": self.language,
            "useCache": self.use_cache,
            "saveToCache": self.save_to_cache,
            "forceRefresh": self.force_refresh
        }


@dataclass
class UnifiedAnalysisResult:
    """Complete unified analysis result"""
    success: bool
    error: Optional[str] = None

    # Data overview
    sheet_name: str = ""
    total_rows: int = 0
    total_cols: int = 0

    # Detected fields
    fields: List[FieldInfo] = field(default_factory=list)

    # Scenario recognition
    scenario: Optional[ScenarioResult] = None

    # Core results
    metrics: List[MetricResult] = field(default_factory=list)
    charts: List[ChartConfig] = field(default_factory=list)
    insights: List[Insight] = field(default_factory=list)
    predictions: Optional[List[PredictionResult]] = None

    # Context (notes, explanations from Excel)
    context: Optional[Dict[str, Any]] = None

    # Processing info
    processing_time_ms: int = 0
    analysis_depth: str = "standard"
    notes: List[str] = field(default_factory=list)

    # Cache info
    from_cache: bool = False
    cache_key: Optional[str] = None

    def to_dict(self) -> Dict[str, Any]:
        return {
            "success": self.success,
            "error": self.error,
            "sheetName": self.sheet_name,
            "totalRows": self.total_rows,
            "totalCols": self.total_cols,
            "fields": [f.to_dict() for f in self.fields],
            "scenario": self.scenario.to_dict() if self.scenario else None,
            "metrics": [m.to_dict() for m in self.metrics],
            "charts": [c.to_dict() for c in self.charts],
            "insights": [i.to_dict() for i in self.insights],
            "predictions": [p.to_dict() for p in self.predictions] if self.predictions else None,
            "context": self.context,
            "processingTimeMs": self.processing_time_ms,
            "analysisDepth": self.analysis_depth,
            "notes": self.notes,
            "fromCache": self.from_cache,
            "cacheKey": self.cache_key
        }


class UnifiedAnalyzer:
    """
    Unified analysis service using all LLM-powered modules.

    Key changes from old version:
    - Uses LLMFieldDetector instead of regex-based FieldDetector
    - Uses LLMScenarioDetector instead of keyword-based ScenarioDetector
    - Uses ChartRecommender (LLM) instead of hardcoded scenario->chart mapping
    - Uses MetricCalculator.infer_and_calculate_all() for dynamic metrics
    - No more hardcoded SCENARIO_NAMES, SCENARIO_METRICS, SCENARIO_CHARTS
    """

    def __init__(self, cache_dir: str = "smartbi_cache"):
        self.settings = get_settings()

        # Initialize cache manager
        self.cache_manager = get_cache_manager(cache_dir=cache_dir)

        # Initialize data cleaner
        self.data_cleaner = DataCleaner()

        # Initialize LLM-powered services
        self.raw_exporter = RawExporter()
        self.field_detector = get_field_detector()
        self.scenario_detector = get_scenario_detector()
        self.chart_recommender = get_chart_recommender()
        self.metric_calculator = MetricCalculator()
        self.chart_builder = ChartBuilder()
        self.insight_generator = InsightGenerator()
        self.forecast_service = ForecastService()
        self.context_extractor = ContextExtractor()

    @property
    def client(self) -> httpx.AsyncClient:
        from common.llm_client import get_llm_http_client
        return get_llm_http_client()

    async def analyze(
        self,
        file_bytes: bytes,
        sheet_index: int = 0,
        question: Optional[str] = None,
        options: Optional[AnalysisOptions] = None
    ) -> UnifiedAnalysisResult:
        """
        Perform comprehensive analysis on Excel data.

        方案C混合模式:
        1. 检查缓存 - 如果命中且不强制刷新，使用缓存数据
        2. 缓存未命中 - 内存处理（主流程）
        3. 异步保存 - 处理完成后异步保存JSON/CSV/MD

        Args:
            file_bytes: Excel file bytes
            sheet_index: Sheet index to analyze (0-based)
            question: Optional natural language question to focus analysis
            options: Analysis options

        Returns:
            UnifiedAnalysisResult with complete analysis
        """
        start_time = time.time()
        options = options or AnalysisOptions()
        result = UnifiedAnalysisResult(
            success=False,
            analysis_depth=options.depth.value
        )

        try:
            # Generate cache key
            cache_key = self.cache_manager.generate_cache_key(file_bytes, sheet_index)
            result.cache_key = cache_key

            # ═══════════════════════════════════════════════════════════════
            # Phase 0: 检查完整缓存 (数据 + 分析结果)
            # ═══════════════════════════════════════════════════════════════
            raw_data = None
            df = None
            context_info = None
            from_cache = False
            from_analysis_cache = False  # noqa: F841

            if options.use_cache and not options.force_refresh:
                cached = self.cache_manager.get_cached(file_bytes, sheet_index, include_analysis=True)

                # 检查是否有完整缓存（数据 + 分析结果）
                if cached and cached.has_complete_cache():
                    # 完整缓存命中！直接返回缓存的分析结果
                    logger.info(f"Complete cache hit: {cache_key}")
                    return self._build_result_from_cache(cached, cache_key, start_time)

                # 只有数据缓存，需要重新执行LLM分析
                if cached and cached.has_data():
                    logger.info(f"Data cache hit (no analysis): {cache_key}")
                    df = cached.dataframe
                    raw_data = self._reconstruct_raw_data(cached)
                    from_cache = True
                    result.from_cache = True
                    result.notes.append(f"Loaded data from cache, re-analyzing: {cache_key}")

            # ═══════════════════════════════════════════════════════════════
            # Phase 1: 数据提取 (缓存未命中时)
            # ═══════════════════════════════════════════════════════════════
            if not from_cache:
                raw_data, df, context_info = await self._extract_and_understand(
                    file_bytes, sheet_index
                )

                if raw_data is None or df is None or df.empty:
                    result.error = "Failed to extract data from Excel"
                    result.processing_time_ms = int((time.time() - start_time) * 1000)
                    return result

                # 数据清洗
                df, clean_changes = await self._clean_data(df)
                if clean_changes:
                    result.notes.append(f"Data cleaned: {len(clean_changes)} changes")

            # Populate basic info
            if raw_data:
                result.sheet_name = raw_data.sheet_name
                result.total_rows = raw_data.total_rows
                result.total_cols = raw_data.total_cols

            if context_info and context_info.has_content():
                result.context = context_info.to_dict()

            # ═══════════════════════════════════════════════════════════════
            # Phase 2: LLM字段检测和场景识别 (并行)
            # ═══════════════════════════════════════════════════════════════
            fields, scenario = await self._detect_fields_and_scenario(
                df, raw_data, question
            )
            result.fields = fields
            result.scenario = scenario
            result.notes.append(f"Detected scenario: {scenario.scenario_name} (LLM)")

            # ═══════════════════════════════════════════════════════════════
            # Phase 3: 并行分析执行
            # ═══════════════════════════════════════════════════════════════
            metrics_task = self._calculate_metrics_llm(df, scenario, options)
            charts_task = self._generate_charts_llm(df, fields, scenario, question, options)
            insights_task = self._generate_insights(df, scenario, question, context_info, options)

            # Optional predictions
            if options.include_predictions and options.depth != AnalysisDepth.QUICK:
                predictions_task = self._generate_predictions(df, fields, options)
                metrics, charts, insights, predictions = await asyncio.gather(
                    metrics_task,
                    charts_task,
                    insights_task,
                    predictions_task
                )
                result.predictions = predictions
            else:
                metrics, charts, insights = await asyncio.gather(
                    metrics_task,
                    charts_task,
                    insights_task
                )

            result.metrics = metrics
            result.charts = charts
            result.insights = insights

            # ═══════════════════════════════════════════════════════════════
            # Phase 3.5: 规则注入 — 分部预实对比图 (department budget vs actual)
            # ═══════════════════════════════════════════════════════════════
            budget_chart = self._try_inject_budget_comparison_chart(df, fields, charts)
            if budget_chart:
                result.charts.append(budget_chart)
                result.notes.append("Injected budget_comparison chart (rule-based)")

            # ═══════════════════════════════════════════════════════════════
            # Phase 4: LLM问答增强 (可选)
            # ═══════════════════════════════════════════════════════════════
            if question and self.settings.llm_api_key:
                enhanced_insights = await self._enhance_with_llm(
                    df, question, scenario, metrics, insights, context_info
                )
                if enhanced_insights:
                    result.insights = enhanced_insights

            result.success = True
            result.notes.append(f"Generated {len(result.metrics)} metrics (LLM-inferred)")
            result.notes.append(f"Generated {len(result.charts)} charts (LLM-recommended)")
            result.notes.append(f"Generated {len(result.insights)} insights")

            # ═══════════════════════════════════════════════════════════════
            # Phase 5: 异步保存完整缓存 (数据 + 分析结果)
            # ═══════════════════════════════════════════════════════════════
            if options.save_to_cache:
                await self._save_complete_cache_async(
                    file_bytes=file_bytes,
                    sheet_index=sheet_index,
                    sheet_name=result.sheet_name,
                    raw_data=raw_data,
                    df=df,
                    context_info=context_info,
                    result=result
                )
                result.notes.append(f"Saved complete cache: {cache_key}")

        except Exception as e:
            logger.error(f"Unified analysis failed: {e}", exc_info=True)
            result.error = str(e)

        result.processing_time_ms = int((time.time() - start_time) * 1000)
        return result

    def _reconstruct_raw_data(self, cached: CachedData) -> RawSheetData:
        """Reconstruct RawSheetData from cached metadata"""
        from services.raw_exporter import RawSheetData
        # Create minimal RawSheetData for compatibility
        return RawSheetData(
            sheet_name=cached.metadata.sheet_name,
            sheet_index=cached.metadata.sheet_index,
            total_rows=cached.metadata.row_count,
            total_cols=cached.metadata.col_count,
            merged_cells=[],
            rows=[],
            stats={}
        )

    def _build_result_from_cache(
        self,
        cached: CachedData,
        cache_key: str,
        start_time: float
    ) -> UnifiedAnalysisResult:
        """Build UnifiedAnalysisResult from complete cache"""
        analysis = cached.analysis

        # Reconstruct fields
        fields = []
        for f in analysis.fields:
            fields.append(FieldInfo(
                name=f.get("name", ""),
                data_type=f.get("dataType", f.get("data_type", "string")),
                semantic_type=f.get("semanticType", f.get("semantic_type", "unknown")),
                chart_role=f.get("chartRole", f.get("chart_role", "dimension")),
                sample_values=f.get("sampleValues", f.get("sample_values", [])),
                statistics=f.get("statistics", {})
            ))

        # Reconstruct scenario
        scenario = None
        if analysis.scenario:
            s = analysis.scenario
            scenario = ScenarioResult(
                scenario=s.get("scenario", "unknown"),
                scenario_name=s.get("scenarioName", s.get("scenario_name", "Unknown")),
                confidence=s.get("confidence", 0.5),
                evidence=s.get("evidence", []),
                dimensions=s.get("dimensions", []),
                measures=s.get("measures", []),
                recommended_analyses=s.get("recommendedAnalyses", s.get("recommended_analyses", [])),
                sub_type=s.get("subType", s.get("sub_type"))
            )

        # Reconstruct metrics
        metrics = []
        for m in analysis.metrics:
            metrics.append(MetricResult(
                name=m.get("name", ""),
                value=m.get("value"),
                unit=m.get("unit", ""),
                success=m.get("success", True),
                formatted=m.get("formatted", ""),
                formula=m.get("formula", ""),
                breakdown=m.get("breakdown"),
                trend=m.get("trend"),
                change_rate=m.get("changeRate", m.get("change_rate")),
                category=m.get("category", "custom")
            ))

        # Reconstruct charts
        charts = []
        for c in analysis.charts:
            charts.append(ChartConfig(
                chart_type=c.get("chartType", c.get("chart_type", "bar")),
                title=c.get("title", ""),
                config=c.get("config", {}),
                description=c.get("description", ""),
                reason=c.get("reason", ""),
                x_axis=c.get("xAxis", c.get("x_axis")),
                y_axis=c.get("yAxis", c.get("y_axis")),
                recommended=c.get("recommended", True),
                priority=c.get("priority", 1),
                confidence=c.get("confidence", 0.8)
            ))

        # Reconstruct insights
        insights = []
        for i in analysis.insights:
            insights.append(Insight(
                type=i.get("type", "general"),
                title=i.get("title", ""),
                text=i.get("text", ""),
                metric=i.get("metric"),
                sentiment=i.get("sentiment", "neutral"),
                importance=float(i.get("importance", 5)),
                recommendation=i.get("recommendation"),
                related_data=i.get("relatedData", i.get("related_data"))
            ))

        # Reconstruct predictions if any
        predictions = None
        if analysis.predictions:
            predictions = []
            for p in analysis.predictions:
                predictions.append(PredictionResult(
                    metric=p.get("metric", ""),
                    algorithm=p.get("algorithm", ""),
                    predictions=p.get("predictions", []),
                    lower_bound=p.get("lowerBound", p.get("lower_bound", [])),
                    upper_bound=p.get("upperBound", p.get("upper_bound", [])),
                    periods=p.get("periods", 0),
                    confidence_level=p.get("confidenceLevel", p.get("confidence_level", 0.95))
                ))

        result = UnifiedAnalysisResult(
            success=True,
            sheet_name=cached.metadata.sheet_name,
            total_rows=cached.metadata.row_count,
            total_cols=cached.metadata.col_count,
            fields=fields,
            scenario=scenario,
            metrics=metrics,
            charts=charts,
            insights=insights,
            predictions=predictions,
            context=analysis.context,
            from_cache=True,
            cache_key=cache_key,
            analysis_depth=analysis.analysis_depth,
            notes=[f"Loaded from complete cache: {cache_key}"],
            processing_time_ms=int((time.time() - start_time) * 1000)
        )

        return result

    async def _clean_data(self, df: pd.DataFrame) -> Tuple[pd.DataFrame, List[Dict]]:
        """
        Clean data - basic cleaning operations.

        Note: Full DataCleaner requires RawSheetData + structure analysis.
        Here we do basic DataFrame-level cleaning.

        Handles duplicate column names by using iloc for column access.
        """
        changes = []
        try:
            # Basic cleaning operations
            original_rows = len(df)

            # 1. Remove completely empty rows
            df = df.dropna(how='all')
            if len(df) < original_rows:
                changes.append({
                    "type": "remove_empty_rows",
                    "count": original_rows - len(df)
                })

            # 2. Strip whitespace from string columns (handle duplicate column names)
            for idx, col in enumerate(df.columns):
                series = df.iloc[:, idx]
                # Handle case where duplicate column names return DataFrame
                if isinstance(series, pd.DataFrame):
                    series = series.iloc[:, 0]

                if series.dtype == 'object':
                    df.iloc[:, idx] = series.apply(
                        lambda x: x.strip() if isinstance(x, str) else x
                    )

            # 3. Normalize numeric columns (remove thousand separators if string)
            for idx, col in enumerate(df.columns):
                series = df.iloc[:, idx]
                # Handle case where duplicate column names return DataFrame
                if isinstance(series, pd.DataFrame):
                    series = series.iloc[:, 0]

                if series.dtype == 'object':
                    # Try to convert string numbers with commas
                    try:
                        sample = series.dropna().head(10)
                        if hasattr(sample, 'str') and sample.str.contains(',').any():
                            df.iloc[:, idx] = series.str.replace(',', '')
                            changes.append({
                                "type": "remove_thousand_separator",
                                "column": col
                            })
                    except Exception:
                        pass

            logger.debug(f"Basic cleaning applied: {len(changes)} changes")

        except Exception as e:
            logger.warning(f"Data cleaning failed: {e}")

        return df, changes

    async def _save_complete_cache_async(
        self,
        file_bytes: bytes,
        sheet_index: int,
        sheet_name: str,
        raw_data: Optional[RawSheetData],
        df: pd.DataFrame,
        context_info: Optional[ContextInfo],
        result: UnifiedAnalysisResult
    ):
        """Save complete cache (data + analysis results) asynchronously"""
        try:
            # Prepare JSON data (handle duplicate column names using iloc)
            data_types = {}
            for idx, col in enumerate(df.columns):
                series = df.iloc[:, idx]
                if isinstance(series, pd.DataFrame):
                    series = series.iloc[:, 0]
                data_types[col] = str(series.dtype)

            json_data = {
                "sheet_name": sheet_name,
                "sheet_index": sheet_index,
                "row_count": len(df),
                "col_count": len(df.columns),
                "columns": df.columns.tolist(),
                "data_types": data_types,
                "statistics": self._calculate_column_stats(df)
            }

            if raw_data:
                json_data["raw_stats"] = raw_data.stats or {}

            if context_info and context_info.has_content():
                json_data["context"] = context_info.to_dict()

            # Prepare Markdown
            headers = df.columns.tolist()
            metadata = {}
            if context_info:
                metadata = context_info.to_dict() if context_info.has_content() else {}

            markdown = generate_markdown_content(
                sheet_name=sheet_name,
                headers=headers,
                dataframe=df,
                metadata=metadata
            )

            # Prepare analysis results for caching
            analysis = CachedAnalysis(
                fields=[f.to_dict() for f in result.fields],
                scenario=result.scenario.to_dict() if result.scenario else None,
                metrics=[m.to_dict() for m in result.metrics],
                charts=[c.to_dict() for c in result.charts],
                insights=[i.to_dict() for i in result.insights],
                predictions=[p.to_dict() for p in result.predictions] if result.predictions else None,
                context=result.context,
                processing_time_ms=result.processing_time_ms,
                analysis_depth=result.analysis_depth,
                notes=result.notes
            )

            # Save complete cache (data + analysis)
            await self.cache_manager.save_complete_async(
                file_bytes=file_bytes,
                sheet_index=sheet_index,
                sheet_name=sheet_name,
                raw_data=json_data,
                dataframe=df,
                markdown=markdown,
                analysis=analysis
            )

        except Exception as e:
            logger.error(f"Failed to save complete cache: {e}")

    def _detect_data_start_row(self, raw_data: RawSheetData) -> int:
        """
        检测数据起始行（改进的规则方法）

        策略：
        1. 如果找到 numeric_rate > 30% 的行，那就是数据行
        2. 如果第一行是纯文本（numeric_rate = 0）且后面有数值行，第一行是表头
        3. 对于只有少量数值列的表（如交易明细），使用"有数值"作为判断标准

        注意：不使用基于总列数的填充率，因为 Excel 合并单元格会导致总列数很大
        """
        rows = raw_data.rows

        # 首先确定"活跃列数"（有数据的列数）
        max_non_empty = 0
        for row in rows[:20]:  # 检查前 20 行
            non_empty = sum(1 for c in row.cells if c.value is not None)
            max_non_empty = max(max_non_empty, non_empty)

        # 动态计算阈值：至少需要 max_non_empty 的 30%，且 >= 3
        min_non_empty = max(3, int(max_non_empty * 0.3))

        # 首先尝试找到 numeric_rate > 30% 的行
        for i, row in enumerate(rows):
            cells = row.cells

            non_empty = sum(1 for c in cells if c.value is not None)
            if non_empty < min_non_empty:
                continue

            numeric_count = sum(
                1 for c in cells
                if c.value is not None and isinstance(c.value, (int, float)) and not isinstance(c.value, bool)
            )
            numeric_rate = numeric_count / non_empty if non_empty > 0 else 0

            if numeric_rate > 0.3:
                logger.debug(f"检测到数据起始行: {i}, non_empty={non_empty}, numeric_rate={numeric_rate:.2f}")
                return i

        # 如果没找到高数值率的行，尝试检测"第一行是表头"的模式
        # 条件：第一行无数值，但后面行有数值
        if len(rows) >= 2:
            first_row_cells = rows[0].cells
            first_row_non_empty = sum(1 for c in first_row_cells if c.value is not None)
            first_row_numeric = sum(
                1 for c in first_row_cells
                if c.value is not None and isinstance(c.value, (int, float)) and not isinstance(c.value, bool)
            )

            if first_row_non_empty >= min_non_empty and first_row_numeric == 0:
                # 第一行有足够数据但无数值，检查第二行
                second_row_cells = rows[1].cells
                second_row_numeric = sum(
                    1 for c in second_row_cells
                    if c.value is not None and isinstance(c.value, (int, float)) and not isinstance(c.value, bool)
                )

                if second_row_numeric > 0:
                    # 第一行是表头，第二行是数据
                    logger.debug("检测到单行表头模式: data_start=1")
                    return 1

        # 如果没有检测到，使用 stats 中的值作为 fallback
        stats_start = raw_data.stats.get("potential_data_start_row", 0)
        return stats_start if stats_start else 0

    def _merge_multirow_headers(self, raw_data: RawSheetData, data_start: int) -> List[str]:
        """
        合并多行表头（规则 C：跳过标题行后合并）

        检查 data_start 前面的行，找出可能的表头行并合并。
        表头行特征：
        1. 非空单元格数 >= 阈值（基于活跃列数）
        2. 数值占比 < 50%（排除数据行）

        注意：不使用基于总列数的填充率，因为 Excel 合并单元格会导致总列数很大
        """
        rows = raw_data.rows
        num_cols = raw_data.total_cols

        if data_start <= 0:
            return [f"Col_{i}" for i in range(num_cols)]

        # 确定"活跃列数"（有数据的列数）- 基于 data_start 前的行
        max_non_empty = 0
        for i in range(max(0, data_start - 6), data_start):
            if i < len(rows):
                non_empty = sum(1 for c in rows[i].cells if c.value is not None)
                max_non_empty = max(max_non_empty, non_empty)

        # 动态阈值：至少需要 max_non_empty 的 30%，且 >= 3
        # 这样可以排除只有单个标题的行
        min_non_empty = max(3, int(max_non_empty * 0.3))

        # 检查 data_start 前面的行，找出可能的表头行（最多检查6行）
        header_rows = []
        for i in range(max(0, data_start - 6), data_start):
            if i >= len(rows):
                continue

            row = rows[i]
            cells = row.cells

            # 计算非空单元格
            non_empty = sum(1 for c in cells if c.value is not None)
            if non_empty == 0:
                continue

            # 表头行需要有足够多的非空单元格（基于动态阈值）
            if non_empty < min_non_empty:
                continue

            # 计算数值占比
            numeric_count = 0
            for c in cells:
                if c.value is not None:
                    if isinstance(c.value, (int, float)) and not isinstance(c.value, bool):
                        numeric_count += 1

            numeric_rate = numeric_count / non_empty

            # 数值占比<50% 认为是表头行
            if numeric_rate < 0.5:
                header_rows.append((i, cells))

        logger.debug(f"检测到 {len(header_rows)} 个表头行: {[r[0] for r in header_rows]}")

        if not header_rows:
            return [f"Col_{i}" for i in range(num_cols)]

        if len(header_rows) == 1:
            # 单行表头
            cells = header_rows[0][1]
            return [
                str(c.value) if c.value is not None else f"Col_{i}"
                for i, c in enumerate(cells)
            ]

        # 多行表头合并
        merged = []
        for col_idx in range(num_cols):
            parts = []
            last_value = None

            for row_idx, cells in header_rows:
                val = cells[col_idx].value if col_idx < len(cells) else None

                if val is not None:
                    val_str = str(val).strip()
                    # 避免重复（与上一行相同的值不再添加）
                    if val_str and val_str != last_value:
                        parts.append(val_str)
                        last_value = val_str

            if parts:
                # 合并多行表头，用下划线连接
                merged_name = "_".join(parts)
                # 清理过长的名称
                if len(merged_name) > 50:
                    merged_name = merged_name[:50]
                merged.append(merged_name)
            else:
                merged.append(f"Col_{col_idx}")

        return merged

    async def _infer_empty_column_names(
        self,
        headers: List[str],
        raw_data: RawSheetData,
        data_start: int
    ) -> List[str]:
        """
        使用 LLM 推断空列的列名（Scheme 1）。

        对于仍然是 Col_X 格式的列名，使用相邻列名和数据样本
        让 LLM 推断其含义。

        Args:
            headers: 当前的列名列表
            raw_data: 原始数据
            data_start: 数据起始行

        Returns:
            推断后的列名列表
        """
        import json

        # 找出空列（Col_X 格式）
        empty_cols = [(i, h) for i, h in enumerate(headers) if h.startswith('Col_')]

        if not empty_cols:
            return headers  # 没有空列，直接返回

        logger.debug(f"发现 {len(empty_cols)} 个空列需要 LLM 推断: {[c[1] for c in empty_cols]}")

        # 构建 LLM prompt 的上下文
        context_lines = []
        rows = raw_data.rows
        num_cols = raw_data.total_cols

        for col_idx, col_name in empty_cols:
            # 获取相邻列名
            left_name = headers[col_idx - 1] if col_idx > 0 else "(无)"
            right_name = headers[col_idx + 1] if col_idx < len(headers) - 1 else "(无)"

            # 获取该列的数据样本
            data_samples = []
            for row in rows[data_start:data_start + 5]:
                if col_idx < len(row.cells):
                    val = row.cells[col_idx].value
                    if val is not None:
                        sample_str = str(val)[:20]
                        data_samples.append(sample_str)

            context_lines.append(
                f"  - {col_name}: 左边列={left_name}, 右边列={right_name}, 数据样本={data_samples[:3]}"
            )

        # 获取表头行信息
        header_row_info = []
        for i in range(max(0, data_start - 4), data_start):
            if i < len(rows):
                row_values = [
                    str(c.value)[:15] if c.value is not None else '(空)'
                    for c in rows[i].cells[:min(15, num_cols)]
                ]
                header_row_info.append(f"Row {i}: {row_values}")

        prompt = f"""分析这个Excel表格中空列的含义，推断合适的列名。

空列信息：
{chr(10).join(context_lines)}

表头行数据：
{chr(10).join(header_row_info[:2])}

根据相邻列名和数据特征，推断每个空列的含义。

返回JSON格式（只返回JSON）：
{{
    "inferred_names": {{
        "Col_28": "推断的列名",
        ...
    }}
}}"""

        try:
            response = await self._call_llm(prompt)

            # 解析 JSON 响应
            json_str = self._robust_json_extract(response)
            if json_str:
                result = json.loads(json_str)
                if result and "inferred_names" in result:
                    inferred = result["inferred_names"]
                    updated_headers = headers.copy()

                    for col_name, new_name in inferred.items():
                        # 找到对应的索引并替换
                        for i, h in enumerate(updated_headers):
                            if h == col_name:
                                updated_headers[i] = str(new_name)
                                logger.debug(f"LLM 推断列名: {col_name} -> {new_name}")

                    return updated_headers

        except Exception as e:
            logger.warning(f"LLM 推断空列名失败: {e}")

        return headers  # 失败时返回原列名

    def _calculate_column_stats(self, df: pd.DataFrame) -> Dict[str, Dict]:
        """Calculate statistics for each column (handles duplicate column names)"""
        stats = {}
        for idx, col in enumerate(df.columns):
            # Use iloc to handle duplicate column names
            series = df.iloc[:, idx]
            if isinstance(series, pd.DataFrame):
                series = series.iloc[:, 0]

            col_stats = {
                "non_null_count": int(series.notna().sum()),
                "null_count": int(series.isna().sum()),
                "unique_count": int(series.nunique())
            }

            if pd.api.types.is_numeric_dtype(series):
                min_val = series.min()
                max_val = series.max()
                mean_val = series.mean()
                sum_val = series.sum()
                col_stats.update({
                    "min": float(min_val) if pd.notna(min_val) else None,
                    "max": float(max_val) if pd.notna(max_val) else None,
                    "mean": float(mean_val) if pd.notna(mean_val) else None,
                    "sum": float(sum_val) if pd.notna(sum_val) else None
                })

            # Use index-based key to avoid overwriting stats for duplicate column names
            col_key = f"{col}" if col not in stats else f"{col}_{idx}"
            stats[col_key] = col_stats

        return stats

    async def _extract_and_understand(
        self,
        file_bytes: bytes,
        sheet_index: int
    ) -> Tuple[Optional[RawSheetData], Optional[pd.DataFrame], Optional[ContextInfo]]:
        """Extract raw data and convert to DataFrame."""
        try:
            # Export raw data
            raw_data = self.raw_exporter.export_sheet(
                file_bytes, sheet_index=sheet_index, max_rows=10000
            )

            if not raw_data or not raw_data.rows:
                return None, None, None

            # Detect data start row using improved logic
            data_start = self._detect_data_start_row(raw_data)

            # Merge multi-row headers
            headers = self._merge_multirow_headers(raw_data, data_start)

            # Use LLM to infer empty column names (Col_X format)
            # Check if there are any Col_X columns that need inference
            has_empty_cols = any(h.startswith('Col_') for h in headers)
            if has_empty_cols:
                try:
                    headers = await self._infer_empty_column_names(
                        headers, raw_data, data_start
                    )
                except Exception as e:
                    logger.warning(f"LLM empty column inference failed: {e}")

            # Extract data rows (starting from data_start)
            data_rows = []
            for row in raw_data.rows[data_start:]:
                row_data = [c.value for c in row.cells]
                data_rows.append(row_data)

            # Create DataFrame with merged headers
            # Ensure headers match data columns
            if data_rows:
                num_cols = len(data_rows[0]) if data_rows else 0
                if len(headers) < num_cols:
                    headers.extend([f"Col_{i}" for i in range(len(headers), num_cols)])
                elif len(headers) > num_cols:
                    headers = headers[:num_cols]

            df = pd.DataFrame(data_rows, columns=headers)

            # Deduplicate column names (handles Excel merged cells)
            if df.columns.duplicated().any():
                logger.info(f"Deduplicating {df.columns.duplicated().sum()} duplicate column names")
                df = deduplicate_columns(df)

            # Clean empty rows and columns
            df = df.dropna(how='all')
            df = df.dropna(axis=1, how='all')

            # Extract context
            context_info = None
            try:
                context_info = await self.context_extractor.extract_context(
                    file_bytes, sheet_index
                )
            except Exception as e:
                logger.warning(f"Context extraction failed: {e}")

            return raw_data, df, context_info

        except Exception as e:
            logger.error(f"Data extraction failed: {e}")
            return None, None, None

    async def _detect_fields_and_scenario(
        self,
        df: pd.DataFrame,
        raw_data: RawSheetData,
        question: Optional[str]
    ) -> Tuple[List[FieldInfo], ScenarioResult]:
        """
        LLM-powered field detection and scenario recognition.

        Both run in parallel for efficiency.
        """
        headers = df.columns.tolist()
        rows = df.head(100).values.tolist()
        sample_rows = df.head(20).to_dict('records')

        # Prepare metadata
        metadata = {
            "title": raw_data.sheet_name,
            "sheet_name": raw_data.sheet_name,
            "row_count": raw_data.total_rows,
            "col_count": raw_data.total_cols
        }

        # Run field detection and scenario detection in parallel
        field_task = self.field_detector.detect_fields(headers, rows)
        scenario_task = self.scenario_detector.detect(
            columns=headers,
            sample_rows=sample_rows,
            metadata=metadata
        )

        field_results, scenario_result = await asyncio.gather(
            field_task, scenario_task
        )

        # Convert field results to FieldInfo
        fields = [
            FieldInfo(
                name=f.field_name,
                data_type=f.data_type,
                semantic_type=f.semantic_type,
                chart_role=f.chart_role,
                description=f.description,
                sample_values=f.sample_values,
                statistics=f.statistics,
                confidence=f.confidence
            )
            for f in field_results
        ]

        # Convert scenario result
        scenario = ScenarioResult(
            scenario=scenario_result.scenario_type,
            scenario_name=scenario_result.scenario_name,
            confidence=scenario_result.confidence,
            evidence=[scenario_result.reasoning] if scenario_result.reasoning else [],
            dimensions=scenario_result.dimensions,
            measures=scenario_result.measures,
            recommended_analyses=scenario_result.recommended_analyses
        )

        return fields, scenario

    async def _calculate_metrics_llm(
        self,
        df: pd.DataFrame,
        scenario: ScenarioResult,
        options: AnalysisOptions
    ) -> List[MetricResult]:
        """
        LLM-powered metric inference and calculation.

        Uses MetricCalculator.infer_and_calculate_all() for dynamic metrics.
        """
        metrics = []

        try:
            # Use LLM to infer and calculate all applicable metrics
            calc_result = await self.metric_calculator.infer_and_calculate_all(
                df=df,
                scenario=scenario.scenario_name
            )

            if calc_result.get("success"):
                for m in calc_result.get("metrics", []):
                    metric_info = m.get("metric", {})
                    result_info = m.get("result", {})

                    if result_info.get("success"):
                        value = result_info.get("value")
                        unit = metric_info.get("unit", "")

                        # Format value
                        if value is not None:
                            if unit == "%":
                                formatted = f"{value:.1f}%"
                            elif abs(value) >= 10000:
                                formatted = f"{value/10000:.2f}万{unit}"
                            else:
                                formatted = f"{value:,.2f}{unit}"
                        else:
                            formatted = "N/A"

                        metrics.append(MetricResult(
                            name=metric_info.get("name", "未知指标"),
                            value=value,
                            unit=unit,
                            success=True,
                            formatted=formatted,
                            formula=metric_info.get("formula", ""),
                            breakdown=result_info.get("breakdown"),
                            category=metric_info.get("category", "custom")
                        ))

        except Exception as e:
            logger.error(f"LLM metrics calculation failed: {e}", exc_info=True)

        return metrics

    async def _generate_charts_llm(
        self,
        df: pd.DataFrame,
        fields: List[FieldInfo],
        scenario: ScenarioResult,
        question: Optional[str],
        options: AnalysisOptions
    ) -> List[ChartConfig]:
        """
        LLM-powered chart recommendation.

        Uses ChartRecommender for dynamic chart suggestions.
        """
        charts = []

        try:
            # Prepare data summary for chart recommender
            data_summary = DataSummary(
                columns=[
                    {
                        "columnName": f.name,
                        "dataType": f.data_type,
                        "semanticType": f.semantic_type,
                        "sampleValues": f.sample_values[:3]
                    }
                    for f in fields
                ],
                row_count=len(df),
                dimensions=[f.name for f in fields if f.chart_role == "dimension"],
                measures=[f.name for f in fields if f.chart_role == "measure"],
                time_columns=[f.name for f in fields if f.chart_role == "time"],
                category_columns=[f.name for f in fields if f.semantic_type == "category"]
            )

            # Get LLM recommendations
            if question:
                recommendations = await self.chart_recommender.recommend_for_question(
                    question=question,
                    data_summary=data_summary,
                    scenario=scenario.scenario
                )
            else:
                recommendations = await self.chart_recommender.recommend(
                    data_summary=data_summary,
                    scenario=scenario.scenario,
                    max_recommendations=options.max_charts
                )

            # Build charts from recommendations
            data = df.to_dict('records')
            priority = 1

            for rec in recommendations[:options.max_charts]:
                try:
                    # Use chart builder to create actual config
                    build_result = self.chart_builder.build(
                        chart_type=rec.chart_type,
                        data=data,
                        x_field=rec.x_axis,
                        y_fields=rec.y_axis,
                        title=rec.title
                    )

                    if build_result.get("success"):
                        charts.append(ChartConfig(
                            chart_type=rec.chart_type,
                            title=rec.title,
                            config=build_result.get("config", {}),
                            description=self._get_chart_description(rec.chart_type),
                            reason=rec.reason,
                            x_axis=rec.x_axis,
                            y_axis=rec.y_axis,
                            recommended=True,
                            priority=priority,
                            confidence=rec.confidence
                        ))
                        priority += 1

                except Exception as e:
                    logger.warning(f"Chart {rec.chart_type} build failed: {e}")

        except Exception as e:
            logger.error(f"LLM chart generation failed: {e}", exc_info=True)

        return charts

    def _get_chart_description(self, chart_type: str) -> str:
        """Get description for chart type."""
        descriptions = {
            "line": "展示数据随时间的变化趋势",
            "bar": "对比不同类别的数据大小",
            "pie": "展示各部分占总体的比例",
            "waterfall": "展示数据的增减变化过程",
            "radar": "多维度数据的综合对比",
            "bar_horizontal": "横向展示排名或对比",
            "pareto": "识别关键因素的80/20分析",
            "area": "展示累积趋势和变化",
            "scatter": "展示两个变量之间的相关性",
            "heatmap": "展示二维数据的分布强度",
            "funnel": "展示转化过程的阶段数据",
            "gauge": "展示单一KPI的达成情况",
            "treemap": "展示层级数据的占比关系",
            "dual_axis": "不同量纲指标的对比分析",
            "budget_comparison": "按分部对比预算与实际执行情况"
        }
        return descriptions.get(chart_type, "数据可视化")

    # ── Column name candidates for budget comparison detection ──
    _DEPT_KEYWORDS = [
        "department", "dept", "division", "branch", "unit",
        "部门", "分部", "事业部", "科室", "组", "区域", "分公司"
    ]
    _BUDGET_KEYWORDS = [
        "budget", "target", "plan", "planned", "goal",
        "预算", "目标", "计划", "指标", "预算数", "预算金额"
    ]
    _ACTUAL_KEYWORDS = [
        "actual", "realized", "completed", "achieved",
        "实际", "完成", "达成", "实际数", "实际金额", "执行"
    ]

    def _find_col_by_keywords(self, df: pd.DataFrame, keywords: list) -> Optional[str]:
        """Find a column matching any keyword (exact then partial)."""
        cols_lower = {c: c.lower().strip() for c in df.columns}
        # Exact match
        for col, cl in cols_lower.items():
            for kw in keywords:
                if cl == kw.lower():
                    return col
        # Partial match (keyword ≥ 2 chars)
        for col, cl in cols_lower.items():
            for kw in keywords:
                if len(kw) >= 2 and kw.lower() in cl:
                    return col
        return None

    def _try_inject_budget_comparison_chart(
        self,
        df: pd.DataFrame,
        fields: List['FieldInfo'],
        existing_charts: List[ChartConfig]
    ) -> Optional[ChartConfig]:
        """
        Detect department + budget + actual columns and inject a budget_comparison chart.
        Returns None if pattern not detected or chart already exists.
        """
        # Skip if a budget_comparison chart already exists
        if any(c.chart_type == "budget_comparison" for c in existing_charts):
            return None

        logger.info(f"[BudgetInjection] DataFrame columns: {list(df.columns)}")
        dept_col = self._find_col_by_keywords(df, self._DEPT_KEYWORDS)
        budget_col = self._find_col_by_keywords(df, self._BUDGET_KEYWORDS)
        actual_col = self._find_col_by_keywords(df, self._ACTUAL_KEYWORDS)
        logger.info(f"[BudgetInjection] dept_col={dept_col}, budget_col={budget_col}, actual_col={actual_col}")

        if not dept_col or not budget_col or not actual_col:
            logger.info("[BudgetInjection] Skipped — missing column(s)")
            return None

        # Verify columns have numeric data
        try:
            budget_vals = pd.to_numeric(df[budget_col], errors='coerce')
            actual_vals = pd.to_numeric(df[actual_col], errors='coerce')
            if budget_vals.notna().sum() < 2 or actual_vals.notna().sum() < 2:
                return None
        except Exception:
            return None

        # Build the chart
        try:
            build_result = self.chart_builder.build(
                chart_type="budget_comparison",
                data=df.to_dict('records'),
                x_field=dept_col,
                y_fields=[budget_col, actual_col],
                title="分部预实对比分析"
            )

            logger.info(f"[BudgetInjection] build_result success={build_result.get('success')}, keys={list(build_result.keys())}")  # noqa: E501
            if build_result.get("success"):
                return ChartConfig(
                    chart_type="budget_comparison",
                    title="分部预实对比分析",
                    config=build_result.get("config", {}),
                    description="按分部/部门对比预算与实际执行情况，叠加达成率趋势",
                    reason="检测到部门+预算+实际列，自动生成分部预实对比图",
                    x_axis=dept_col,
                    y_axis=[budget_col, actual_col],
                    recommended=True,
                    priority=2,
                    confidence=0.95
                )
        except Exception as e:
            logger.warning(f"Budget comparison chart injection failed: {e}")

        return None

    async def _generate_insights(
        self,
        df: pd.DataFrame,
        scenario: ScenarioResult,
        question: Optional[str],
        context_info: Optional[ContextInfo],
        options: AnalysisOptions
    ) -> List[Insight]:
        """Generate business insights from data."""
        insights = []

        try:
            data = df.to_dict('records')

            insight_result = await self.insight_generator.generate_insights(
                data=data,
                analysis_context=f"场景: {scenario.scenario_name}",
                max_insights=options.max_insights,
                context_info=context_info
            )

            if insight_result.get("success"):
                for i in insight_result.get("insights", []):
                    insights.append(Insight(
                        type=i.get("type", "summary"),
                        title=i.get("title", i.get("text", "")[:20]),
                        text=i.get("text", ""),
                        metric=i.get("metric"),
                        sentiment=i.get("sentiment", "neutral"),
                        importance=i.get("importance", 5),
                        recommendation=i.get("recommendation"),
                        related_data=i.get("related_data")
                    ))

        except Exception as e:
            logger.error(f"Insight generation failed: {e}")

        return insights

    async def _generate_predictions(
        self,
        df: pd.DataFrame,
        fields: List[FieldInfo],
        options: AnalysisOptions
    ) -> List[PredictionResult]:
        """Generate time series predictions for numeric fields."""
        predictions = []

        try:
            # Find numeric measure fields
            measure_fields = [
                f for f in fields
                if f.chart_role == "measure" and f.data_type in ["integer", "float"]
            ]

            for measure in measure_fields[:2]:  # Limit to 2 predictions
                try:
                    if measure.name in df.columns:
                        values = pd.to_numeric(df[measure.name], errors='coerce')
                        values = values.dropna().tolist()

                        if len(values) >= 3:
                            forecast_result = self.forecast_service.forecast(
                                data=values,
                                algorithm="auto",
                                periods=options.prediction_periods
                            )

                            if forecast_result.get("success"):
                                predictions.append(PredictionResult(
                                    metric=measure.name,
                                    algorithm=forecast_result.get("algorithm", "auto"),
                                    predictions=forecast_result.get("predictions", []),
                                    lower_bound=forecast_result.get("lowerBound", []),
                                    upper_bound=forecast_result.get("upperBound", []),
                                    periods=options.prediction_periods,
                                    confidence_level=0.95
                                ))

                except Exception as e:
                    logger.warning(f"Prediction for {measure.name} failed: {e}")

        except Exception as e:
            logger.error(f"Predictions generation failed: {e}")

        return predictions

    async def _enhance_with_llm(
        self,
        df: pd.DataFrame,
        question: str,
        scenario: ScenarioResult,
        metrics: List[MetricResult],
        insights: List[Insight],
        context_info: Optional[ContextInfo]
    ) -> Optional[List[Insight]]:
        """Use LLM to enhance insights based on user question."""
        try:
            # Prepare data summary
            data_summary = self._prepare_data_summary(df, metrics)
            existing_insights = "\n".join([f"- {i.text}" for i in insights[:5]])

            # Context from Excel
            excel_context = ""
            if context_info and context_info.has_content():
                excel_context = f"\n## 上下文信息\n{context_info.to_prompt_text()}"

            prompt = f"""基于以下数据和用户问题，生成精准的业务洞察。

## 用户问题
{question}

## 数据场景
{scenario.scenario_name} (置信度: {scenario.confidence:.0%})
维度: {', '.join(scenario.dimensions) if scenario.dimensions else '无'}
指标: {', '.join(scenario.measures) if scenario.measures else '无'}

## 数据概览
{data_summary}

## 已有洞察
{existing_insights}
{excel_context}

请针对用户问题，输出JSON格式的洞察：
{{
    "insights": [
        {{
            "type": "answer|trend|anomaly|recommendation",
            "title": "简短标题",
            "text": "详细回答（包含具体数据）",
            "metric": "相关指标",
            "sentiment": "positive/negative/neutral",
            "importance": 1-10,
            "recommendation": "建议行动"
        }}
    ]
}}

要求：
1. 直接回答用户问题
2. 引用具体数据和百分比
3. 提供可操作的建议
4. 使用中文"""

            response = await self._call_llm(prompt)
            return self._parse_llm_insights(response)

        except Exception as e:
            logger.error(f"LLM enhancement failed: {e}")
            return None

    def _prepare_data_summary(
        self,
        df: pd.DataFrame,
        metrics: List[MetricResult]
    ) -> str:
        """Prepare data summary for LLM."""
        parts = [
            f"- 数据行数: {len(df)}",
            f"- 数据列: {', '.join(df.columns.tolist()[:10])}"
        ]

        # Add metrics
        if metrics:
            parts.append("\n已计算指标:")
            for m in metrics[:5]:
                if m.success and m.value is not None:
                    parts.append(f"- {m.name}: {m.formatted}")

        # Add numeric column stats
        for col in df.select_dtypes(include=[np.number]).columns[:5]:
            parts.append(
                f"- {col}: 总计={df[col].sum():,.2f}, 均值={df[col].mean():,.2f}"
            )

        return "\n".join(parts)

    async def _call_llm(self, prompt: str) -> str:
        """Call LLM API via free-tier router chain (call_chain) — gets free
        fallback + circuit-breaker protection. INSIGHTS slot injects the
        per-provider free model; we drop the explicit `model` field here."""
        from common.llm_router import call_chain, SLOT
        from common.llm_metrics import llm_caller_context

        payload = {
            "messages": [
                {
                    "role": "system",
                    "content": "你是专业的商业数据分析师。请用JSON格式回复。"
                },
                {
                    "role": "user",
                    "content": prompt
                }
            ],
            "temperature": 0.5,
            "max_tokens": 4500,  # 增加到4500以支持完整的中文洞察JSON
            "enable_thinking": False
        }

        with llm_caller_context("unified_analyzer_insight"):
            result = await call_chain(SLOT.INSIGHTS, payload)
        return result["choices"][0]["message"]["content"]

    def _parse_llm_insights(self, response: str) -> List[Insight]:
        """Parse LLM response into insights with robust JSON handling."""
        try:
            # Fix: Use centralized robust JSON parser
            result = robust_json_parse(response, fallback={})
            if not result:
                logger.warning("Could not extract JSON from LLM response")
                return []

            insights = []

            for i in result.get("insights", []):
                if isinstance(i, dict) and i.get("text"):
                    insights.append(Insight(
                        type=i.get("type", "summary"),
                        title=i.get("title", i.get("text", "")[:20]),
                        text=i.get("text"),
                        metric=i.get("metric"),
                        sentiment=i.get("sentiment", "neutral"),
                        importance=i.get("importance", 5),
                        recommendation=i.get("recommendation")
                    ))

            return insights

        except Exception as e:
            logger.error(f"Failed to parse LLM insights: {e}")
            return []

    def _robust_json_extract(self, text: str) -> Optional[str]:
        """
        Robustly extract JSON from LLM response.

        Handles:
        - JSON in code blocks
        - Unterminated strings
        - Extra commas
        """
        import re

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
        import json
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

    def get_sheet_names(self, file_bytes: bytes) -> List[str]:
        """Get all sheet names from Excel file."""
        import openpyxl
        import io

        wb = openpyxl.load_workbook(io.BytesIO(file_bytes), read_only=True, data_only=True)
        names = wb.sheetnames
        wb.close()
        return names

    async def analyze_all_sheets(
        self,
        file_bytes: bytes,
        question: Optional[str] = None,
        options: Optional[AnalysisOptions] = None,
        max_parallel: int = 5
    ) -> "MultiSheetAnalysisResult":
        """
        Analyze all sheets in an Excel file in parallel.

        Each sheet is processed independently:
        - Cache check per sheet
        - Parallel processing for cache misses
        - Async save to cache

        Args:
            file_bytes: Excel file bytes
            question: Optional question (applied to all sheets)
            options: Analysis options
            max_parallel: Maximum parallel sheet processing

        Returns:
            MultiSheetAnalysisResult with all sheet results
        """
        import time
        start_time = time.time()
        options = options or AnalysisOptions()

        # Get sheet names
        sheet_names = self.get_sheet_names(file_bytes)
        total_sheets = len(sheet_names)

        logger.info(f"Analyzing {total_sheets} sheets in parallel (max={max_parallel})")

        # Check cache status for all sheets first
        cache_status = []
        for i, name in enumerate(sheet_names):
            is_cached = self.cache_manager.is_cached(file_bytes, i)
            cache_status.append({
                "index": i,
                "name": name,
                "cached": is_cached
            })

        cached_count = sum(1 for s in cache_status if s["cached"])
        logger.info(f"Cache status: {cached_count}/{total_sheets} sheets cached")

        # Process sheets in parallel with semaphore
        semaphore = asyncio.Semaphore(max_parallel)

        async def analyze_with_semaphore(sheet_index: int) -> UnifiedAnalysisResult:
            async with semaphore:
                return await self.analyze(
                    file_bytes,
                    sheet_index=sheet_index,
                    question=question,
                    options=options
                )

        # Create tasks for all sheets
        tasks = [
            analyze_with_semaphore(i)
            for i in range(total_sheets)
        ]

        # Run all in parallel
        results = await asyncio.gather(*tasks, return_exceptions=True)

        # Process results
        sheet_results = []
        success_count = 0
        cache_hit_count = 0
        error_count = 0

        for i, result in enumerate(results):
            if isinstance(result, Exception):
                # Handle exception
                sheet_results.append(UnifiedAnalysisResult(
                    success=False,
                    error=str(result),
                    sheet_name=sheet_names[i]
                ))
                error_count += 1
            else:
                sheet_results.append(result)
                if result.success:
                    success_count += 1
                if result.from_cache:
                    cache_hit_count += 1
                if result.error:
                    error_count += 1

        processing_time_ms = int((time.time() - start_time) * 1000)

        return MultiSheetAnalysisResult(
            success=error_count == 0,
            total_sheets=total_sheets,
            success_count=success_count,
            cache_hit_count=cache_hit_count,
            error_count=error_count,
            sheet_results=sheet_results,
            sheet_names=sheet_names,
            processing_time_ms=processing_time_ms,
            notes=[
                f"Processed {total_sheets} sheets in parallel",
                f"Cache hits: {cache_hit_count}/{total_sheets}",
                f"Successful: {success_count}/{total_sheets}"
            ]
        )

    async def analyze_all_sheets_stream(
        self,
        file_bytes: bytes,
        question: Optional[str] = None,
        options: Optional[AnalysisOptions] = None,
        max_parallel: int = 5
    ):
        """
        Analyze all sheets, yielding SSE events as each sheet completes.

        Yields dicts with:
          {"event": "sheet", "data": {sheet_index, sheet_name, result}}
          {"event": "done",  "data": {total, success, errors, time_ms}}
        """
        import time
        start_time = time.time()
        options = options or AnalysisOptions()

        sheet_names = self.get_sheet_names(file_bytes)
        total_sheets = len(sheet_names)

        yield {
            "event": "start",
            "data": {"total_sheets": total_sheets, "sheet_names": sheet_names}
        }

        semaphore = asyncio.Semaphore(max_parallel)
        success_count = 0
        error_count = 0

        async def analyze_one(idx: int):
            async with semaphore:
                return idx, await self.analyze(
                    file_bytes, sheet_index=idx, question=question, options=options
                )

        tasks = [asyncio.ensure_future(analyze_one(i)) for i in range(total_sheets)]

        for coro in asyncio.as_completed(tasks):
            try:
                idx, result = await coro
                if result.success:
                    success_count += 1
                if result.error:
                    error_count += 1
                yield {
                    "event": "sheet",
                    "data": {
                        "sheet_index": idx,
                        "sheet_name": sheet_names[idx],
                        "result": result,
                        "progress": f"{success_count + error_count}/{total_sheets}"
                    }
                }
            except Exception as e:
                error_count += 1
                yield {
                    "event": "sheet_error",
                    "data": {"error": str(e)}
                }

        processing_time_ms = int((time.time() - start_time) * 1000)
        yield {
            "event": "done",
            "data": {
                "total_sheets": total_sheets,
                "success_count": success_count,
                "error_count": error_count,
                "processing_time_ms": processing_time_ms
            }
        }

    async def close(self):
        """Close sub-services (shared HTTP client lifecycle managed by main.py)."""
        await self.field_detector.close()
        await self.scenario_detector.close()
        await self.chart_recommender.close()
        await self.metric_calculator.close()


@dataclass
class MultiSheetAnalysisResult:
    """Result of analyzing all sheets in an Excel file."""
    success: bool
    total_sheets: int
    success_count: int
    cache_hit_count: int
    error_count: int
    sheet_results: List[UnifiedAnalysisResult]
    sheet_names: List[str]
    processing_time_ms: int
    notes: List[str] = field(default_factory=list)

    def to_dict(self) -> Dict[str, Any]:
        return {
            "success": self.success,
            "totalSheets": self.total_sheets,
            "successCount": self.success_count,
            "cacheHitCount": self.cache_hit_count,
            "errorCount": self.error_count,
            "sheetResults": [r.to_dict() for r in self.sheet_results],
            "sheetNames": self.sheet_names,
            "processingTimeMs": self.processing_time_ms,
            "notes": self.notes
        }

    def get_sheet_result(self, sheet_name: str) -> Optional[UnifiedAnalysisResult]:
        """Get result for a specific sheet by name."""
        for i, name in enumerate(self.sheet_names):
            if name == sheet_name:
                return self.sheet_results[i]
        return None

    def get_all_metrics(self) -> Dict[str, List[Dict]]:
        """Get all metrics grouped by sheet."""
        return {
            name: [m.to_dict() for m in result.metrics]
            for name, result in zip(self.sheet_names, self.sheet_results)
            if result.success
        }

    def get_all_insights(self) -> Dict[str, List[Dict]]:
        """Get all insights grouped by sheet."""
        return {
            name: [i.to_dict() for i in result.insights]
            for name, result in zip(self.sheet_names, self.sheet_results)
            if result.success
        }


# ============================================================
# Convenience Functions
# ============================================================

async def unified_analyze(
    file_bytes: bytes,
    sheet_index: int = 0,
    question: Optional[str] = None,
    options: Optional[AnalysisOptions] = None
) -> UnifiedAnalysisResult:
    """
    Convenience function for unified analysis.

    Args:
        file_bytes: Excel file bytes
        sheet_index: Sheet index
        question: Optional natural language question
        options: Analysis options

    Returns:
        UnifiedAnalysisResult
    """
    analyzer = UnifiedAnalyzer()
    try:
        return await analyzer.analyze(
            file_bytes,
            sheet_index=sheet_index,
            question=question,
            options=options
        )
    finally:
        await analyzer.close()


async def quick_analyze(
    file_bytes: bytes,
    sheet_index: int = 0
) -> Dict[str, Any]:
    """
    Quick analysis returning a simple dictionary.

    Args:
        file_bytes: Excel file bytes
        sheet_index: Sheet index

    Returns:
        Analysis results as dictionary
    """
    options = AnalysisOptions(
        depth=AnalysisDepth.QUICK,
        max_charts=3,
        max_insights=3,
        include_predictions=False
    )

    result = await unified_analyze(
        file_bytes,
        sheet_index=sheet_index,
        options=options
    )

    return result.to_dict()


async def analyze_all_sheets(
    file_bytes: bytes,
    question: Optional[str] = None,
    options: Optional[AnalysisOptions] = None,
    max_parallel: int = 5
) -> MultiSheetAnalysisResult:
    """
    Convenience function to analyze all sheets in parallel.

    Args:
        file_bytes: Excel file bytes
        question: Optional question (applied to all sheets)
        options: Analysis options
        max_parallel: Maximum parallel sheet processing

    Returns:
        MultiSheetAnalysisResult
    """
    analyzer = UnifiedAnalyzer()
    try:
        return await analyzer.analyze_all_sheets(
            file_bytes,
            question=question,
            options=options,
            max_parallel=max_parallel
        )
    finally:
        await analyzer.close()
