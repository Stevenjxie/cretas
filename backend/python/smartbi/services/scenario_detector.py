from __future__ import annotations
"""
LLM-Based Scenario Detector Service

Dynamically identifies business scenarios using LLM instead of hardcoded rules.
Implements "LLM detection + cache" pattern for efficiency.

Features:
1. LLM-powered dynamic scenario recognition
2. Structure-based caching to reduce API calls
3. Confidence scoring and reasoning traces
4. Automatic dimension and measure identification
"""
import asyncio
import hashlib
import logging
import time
from dataclasses import dataclass
from typing import Any, Dict, List, Optional

from services.utils.json_parser import robust_json_parse

logger = logging.getLogger(__name__)


@dataclass
class ScenarioResult:
    """
    LLM-based scenario detection result.

    Unlike the old enum-based approach, scenario_type is now dynamic
    and can represent any business scenario the LLM identifies.
    """
    scenario_type: str              # Dynamic scenario type (not enum)
    scenario_name: str              # Human-readable name (Chinese)
    confidence: float               # Detection confidence (0.0-1.0)
    dimensions: List[str]           # Identified dimensions (time/region/product etc.)
    measures: List[str]             # Identified measures (revenue/cost/profit etc.)
    recommended_analyses: List[str]  # Recommended analysis types
    reasoning: str                  # LLM's reasoning process

    # Additional metadata
    method: str = "llm"             # Detection method: "llm", "cache", "rule_fallback"
    cache_key: Optional[str] = None  # Cache key if result was cached
    detection_time_ms: float = 0.0  # Time taken for detection

    def to_dict(self) -> Dict[str, Any]:
        """Convert to dictionary for JSON serialization"""
        return {
            "scenario_type": self.scenario_type,
            "scenario_name": self.scenario_name,
            "confidence": self.confidence,
            "dimensions": self.dimensions,
            "measures": self.measures,
            "recommended_analyses": self.recommended_analyses,
            "reasoning": self.reasoning,
            "method": self.method,
            "cache_key": self.cache_key,
            "detection_time_ms": self.detection_time_ms
        }

    @classmethod
    def from_dict(cls, data: Dict[str, Any]) -> "ScenarioResult":
        """Create from dictionary"""
        return cls(
            scenario_type=data.get("scenario_type", "unknown"),
            scenario_name=data.get("scenario_name", "Unknown"),
            confidence=data.get("confidence", 0.0),
            dimensions=data.get("dimensions", []),
            measures=data.get("measures", []),
            recommended_analyses=data.get("recommended_analyses", []),
            reasoning=data.get("reasoning", ""),
            method=data.get("method", "cache"),
            cache_key=data.get("cache_key"),
            detection_time_ms=data.get("detection_time_ms", 0.0)
        )


@dataclass
class ScenarioCacheEntry:
    """Cache entry for scenario detection results"""
    cache_key: str
    result: ScenarioResult
    created_at: float
    accessed_at: float
    access_count: int = 1

    def to_dict(self) -> Dict[str, Any]:
        return {
            "cache_key": self.cache_key,
            "result": self.result.to_dict(),
            "created_at": self.created_at,
            "accessed_at": self.accessed_at,
            "access_count": self.access_count
        }

    @classmethod
    def from_dict(cls, data: Dict[str, Any]) -> "ScenarioCacheEntry":
        return cls(
            cache_key=data["cache_key"],
            result=ScenarioResult.from_dict(data["result"]),
            created_at=data["created_at"],
            accessed_at=data["accessed_at"],
            access_count=data.get("access_count", 1)
        )


class ScenarioCache:
    """
    Cache for scenario detection results.

    Uses column structure signature for cache keys,
    allowing same-structure files to reuse detection results.
    """

    def __init__(self, ttl_seconds: int = 3600, max_entries: int = 500):
        self._cache: Dict[str, ScenarioCacheEntry] = {}
        self._ttl_seconds = ttl_seconds
        self._max_entries = max_entries
        self._lock = asyncio.Lock()

    def _generate_structure_key(
        self,
        columns: List[str],
        sample_values: Optional[List[Dict[str, Any]]] = None
    ) -> str:
        """
        Generate a cache key based on column structure.

        Uses column names and optionally sample value patterns
        to create a unique signature for the data structure.
        """
        # Normalize column names
        normalized_cols = sorted([c.lower().strip() for c in columns if c])

        # Create signature from column names
        col_signature = "|".join(normalized_cols[:30])  # Limit to 30 columns

        # Add value type patterns if available
        type_signature = ""
        if sample_values and len(sample_values) > 0:
            first_row = sample_values[0]
            types = []
            for col in columns[:20]:  # Limit to 20 columns
                val = first_row.get(col)
                if val is None:
                    types.append("null")
                elif isinstance(val, (int, float)):
                    types.append("num")
                elif isinstance(val, str):
                    # Check if it looks like a date
                    if any(c in val for c in ["年", "月", "日", "-", "/"]) and len(val) < 20:
                        types.append("date")
                    else:
                        types.append("str")
                else:
                    types.append("other")
            type_signature = "|" + "|".join(types)

        full_signature = col_signature + type_signature
        return hashlib.md5(full_signature.encode()).hexdigest()[:16]

    def get(
        self,
        columns: List[str],
        sample_values: Optional[List[Dict[str, Any]]] = None
    ) -> Optional[ScenarioResult]:
        """
        Get cached scenario result if available.

        Returns None if not found or expired.
        """
        cache_key = self._generate_structure_key(columns, sample_values)

        entry = self._cache.get(cache_key)
        if entry is None:
            return None

        # Check TTL
        if time.time() - entry.created_at > self._ttl_seconds:
            self._cache.pop(cache_key, None)
            return None

        # Update access stats
        entry.accessed_at = time.time()
        entry.access_count += 1

        # Return copy with cache info
        result = ScenarioResult.from_dict(entry.result.to_dict())
        result.method = "cache"
        result.cache_key = cache_key

        logger.debug(f"Scenario cache hit: {cache_key}")
        return result

    def set(
        self,
        columns: List[str],
        result: ScenarioResult,
        sample_values: Optional[List[Dict[str, Any]]] = None
    ) -> str:
        """
        Cache a scenario detection result.

        Returns the cache key.
        """
        cache_key = self._generate_structure_key(columns, sample_values)

        # Store result copy with cache key
        result_copy = ScenarioResult.from_dict(result.to_dict())
        result_copy.cache_key = cache_key

        entry = ScenarioCacheEntry(
            cache_key=cache_key,
            result=result_copy,
            created_at=time.time(),
            accessed_at=time.time()
        )

        self._cache[cache_key] = entry

        # Evict old entries if needed
        if len(self._cache) > self._max_entries:
            self._evict_old_entries()

        logger.debug(f"Scenario cached: {cache_key}")
        return cache_key

    def _evict_old_entries(self):
        """Remove oldest entries when cache is full.
        Safe: operates on a snapshot of keys to avoid RuntimeError during iteration.
        """
        if len(self._cache) <= self._max_entries:
            return

        # Snapshot keys to avoid dict-changed-size-during-iteration
        sorted_keys = sorted(
            list(self._cache.keys()),
            key=lambda k: self._cache.get(k, ScenarioCacheEntry(
                cache_key=k, result=None, created_at=0, accessed_at=0
            )).accessed_at
        )

        # Remove oldest 10%
        to_remove = max(1, len(sorted_keys) // 10)
        for key in sorted_keys[:to_remove]:
            self._cache.pop(key, None)

        logger.debug(f"Evicted {to_remove} old scenario cache entries")

    def get_stats(self) -> Dict[str, Any]:
        """Get cache statistics"""
        now = time.time()
        active = [e for e in self._cache.values() if now - e.created_at < self._ttl_seconds]

        return {
            "total_entries": len(self._cache),
            "active_entries": len(active),
            "total_hits": sum(e.access_count - 1 for e in self._cache.values()),
            "avg_access_count": sum(e.access_count for e in self._cache.values()) / max(len(self._cache), 1)
        }

    def clear(self):
        """Clear all cache entries"""
        self._cache.clear()
        logger.info("Scenario cache cleared")


class LLMScenarioDetector:
    """
    LLM-based scenario detector.

    Uses LLM to dynamically understand data structure and identify
    business scenarios without relying on hardcoded keywords.

    Workflow:
    1. Check cache for existing result
    2. If cache miss, call LLM for detection
    3. Cache the result for future use
    """

    # System prompt for scenario detection
    SYSTEM_PROMPT = """你是一个专业的数据分析师，擅长识别业务数据的场景类型。
你需要分析数据的列名和样本数据，判断这是什么类型的业务数据。

你的输出必须是严格的JSON格式，包含以下字段：
- scenario_type: 场景类型标识符（英文，snake_case）
- scenario_name: 场景名称（中文）
- confidence: 置信度（0.0-1.0）
- dimensions: 识别到的维度列表（如时间、地区、产品、部门等）
- measures: 识别到的指标列表（如收入、成本、利润、数量等）
- recommended_analyses: 推荐的分析类型列表
- reasoning: 你的推理过程（中文，简短说明）

常见的业务场景类型包括（但不限于）：
- profit_statement: 利润表/损益表
- budget_report: 预算执行报表
- sales_detail: 销售明细
- sales_summary: 销售汇总
- department_performance: 部门绩效
- cost_analysis: 成本分析
- receivable_aging: 应收账龄
- inventory_report: 库存报表
- financial_statement: 财务报表
- operational_report: 运营报表
- hr_report: 人力资源报表
- production_report: 生产报表

注意：
1. 如果数据不属于上述任何类型，你可以创建新的场景类型
2. 维度是用于分组/筛选的字段（如日期、产品、地区）
3. 指标是用于计算/汇总的数值字段（如金额、数量、比率）
4. 推荐分析应该具体且可操作（如"按月趋势分析"、"部门对比"等）"""

    def __init__(self):
        self._settings = None
        self._client = None
        self._cache = ScenarioCache()

    @property
    def settings(self):
        if self._settings is None:
            from config import get_settings
            self._settings = get_settings()
        return self._settings

    @property
    def client(self):
        from common.llm_client import get_llm_http_client
        return get_llm_http_client()

    async def detect(
        self,
        columns: List[str],
        sample_rows: List[Dict[str, Any]],
        metadata: Optional[Dict[str, Any]] = None,
        use_cache: bool = True
    ) -> ScenarioResult:
        """
        Detect business scenario from data structure.

        Args:
            columns: List of column names
            sample_rows: Sample data rows (first 5-10 rows)
            metadata: Optional metadata (title, sheet name, etc.)
            use_cache: Whether to use caching

        Returns:
            ScenarioResult with detected scenario info
        """
        start_time = time.time()

        # Step 1: Check cache
        if use_cache:
            cached = self._cache.get(columns, sample_rows)
            if cached:
                cached.detection_time_ms = (time.time() - start_time) * 1000
                logger.info(f"Scenario detected from cache: {cached.scenario_type}")
                return cached

        # Step 2: Try LLM detection
        if self.settings.llm_api_key:
            try:
                result = await self._detect_with_llm(columns, sample_rows, metadata)
                result.detection_time_ms = (time.time() - start_time) * 1000

                # Cache the result
                if use_cache:
                    self._cache.set(columns, result, sample_rows)

                logger.info(f"Scenario detected by LLM: {result.scenario_type} (conf={result.confidence:.2f})")
                return result

            except Exception as e:
                logger.error(f"LLM scenario detection failed: {e}")

        # Step 3: Fallback to rule-based detection
        result = self._detect_with_rules(columns, sample_rows, metadata)
        result.detection_time_ms = (time.time() - start_time) * 1000

        # Cache even rule-based results
        if use_cache:
            self._cache.set(columns, result, sample_rows)

        logger.info(f"Scenario detected by rules: {result.scenario_type}")
        return result

    async def _detect_with_llm(
        self,
        columns: List[str],
        sample_rows: List[Dict[str, Any]],
        metadata: Optional[Dict[str, Any]] = None
    ) -> ScenarioResult:
        """Call LLM to detect scenario"""

        # Build user prompt
        prompt = self._build_detection_prompt(columns, sample_rows, metadata)

        # Call LLM via free-tier router chain (call_chain) — 免费 fallback + 熔断保护。
        # INSIGHTS slot 按 provider 注入免费 model, 此处去掉显式 model 字段。
        from common.llm_router import call_chain, SLOT
        from common.llm_metrics import llm_caller_context

        payload = {
            "messages": [
                {"role": "system", "content": self.SYSTEM_PROMPT},
                {"role": "user", "content": prompt}
            ],
            "temperature": 0.3,
            "max_tokens": 1500,
            "enable_thinking": False
        }

        with llm_caller_context("scenario_detector"):
            result_json = await call_chain(SLOT.INSIGHTS, payload, timeout=30.0)
        content = result_json.get("choices", [{}])[0].get("message", {}).get("content", "")

        # Parse LLM response
        return self._parse_llm_response(content)

    def _build_detection_prompt(
        self,
        columns: List[str],
        sample_rows: List[Dict[str, Any]],
        metadata: Optional[Dict[str, Any]] = None
    ) -> str:
        """Build the detection prompt for LLM"""

        # Format columns
        columns_str = ", ".join(f'"{c}"' for c in columns[:30])

        # Format sample rows (limit to 5 rows)
        sample_str = ""
        for i, row in enumerate(sample_rows[:5]):
            row_values = []
            for col in columns[:15]:  # Limit columns for readability
                val = row.get(col, "")
                if val is None:
                    row_values.append("null")
                elif isinstance(val, (int, float)):
                    row_values.append(str(val))
                else:
                    row_values.append(f'"{str(val)[:30]}"')
            sample_str += f"  Row {i+1}: [{', '.join(row_values)}]\n"

        # Format metadata if available
        meta_str = ""
        if metadata:
            if metadata.get("title"):
                meta_str += f"\n标题: {metadata['title']}"
            if metadata.get("sheet_name"):
                meta_str += f"\n工作表: {metadata['sheet_name']}"
            if metadata.get("unit"):
                meta_str += f"\n单位: {metadata['unit']}"
            if metadata.get("period"):
                meta_str += f"\n时间范围: {metadata['period']}"

        prompt = f"""请分析以下数据结构，判断业务场景：

列名: [{columns_str}]

数据示例:
{sample_str}
{meta_str}

请返回JSON格式的分析结果。"""

        return prompt

    def _parse_llm_response(self, content: str) -> ScenarioResult:
        """Parse LLM response to ScenarioResult"""

        try:
            # Fix: Use centralized robust JSON parser
            data = robust_json_parse(content, fallback={})

            if not data:
                logger.warning("Could not parse JSON from LLM scenario detection response")
                return ScenarioResult(
                    scenario_type="unknown",
                    scenario_name="Unknown",
                    confidence=0.3,
                    dimensions=[],
                    measures=[],
                    recommended_analyses=[],
                    reasoning="LLM response JSON parsing failed",
                    method="llm_parse_error"
                )

            return ScenarioResult(
                scenario_type=data.get("scenario_type", "unknown"),
                scenario_name=data.get("scenario_name", "Unknown"),
                confidence=float(data.get("confidence", 0.7)),
                dimensions=data.get("dimensions", []),
                measures=data.get("measures", []),
                recommended_analyses=data.get("recommended_analyses", []),
                reasoning=data.get("reasoning", ""),
                method="llm"
            )

        except Exception as e:
            logger.error(f"Failed to parse LLM response: {e}")
            logger.debug(f"Raw content: {content}")

            # Return a default result
            return ScenarioResult(
                scenario_type="unknown",
                scenario_name="Unknown",
                confidence=0.3,
                dimensions=[],
                measures=[],
                recommended_analyses=[],
                reasoning=f"LLM response parsing failed: {str(e)}",
                method="llm_parse_error"
            )

    def _detect_with_rules(
        self,
        columns: List[str],
        sample_rows: List[Dict[str, Any]],
        metadata: Optional[Dict[str, Any]] = None
    ) -> ScenarioResult:
        """
        Fallback rule-based detection.

        This is a simplified version used when LLM is unavailable.
        It uses basic keyword matching but still provides the new output format.
        """

        # Normalize column names for matching
        col_lower = [c.lower() for c in columns]
        col_text = " ".join(col_lower)

        # Collect all text for analysis
        all_text = col_text
        if metadata:
            all_text += " " + str(metadata.get("title", ""))
            all_text += " " + str(metadata.get("sheet_name", ""))

        # Sample data text
        sample_text = ""
        for row in sample_rows[:5]:
            for v in row.values():
                if isinstance(v, str):
                    sample_text += " " + v.lower()
        all_text += " " + sample_text

        # Detection rules (simplified)
        scenarios = [
            {
                "type": "profit_statement",
                "name": "利润表分析",
                "keywords": ["profit", "loss", "income statement", "revenue", "cost"],
                "cn_keywords": ["利润", "收入", "成本", "毛利", "净利", "营业", "费用",
                                "毛利率", "净利率", "利润表", "损益"]
            },
            {
                "type": "budget_report",
                "name": "预算执行报告",
                "keywords": ["budget", "actual", "variance", "plan", "achievement"],
                "cn_keywords": ["预算", "实际", "达成率", "完成率", "目标", "执行",
                                "偏差", "计划"]
            },
            {
                "type": "sales_detail",
                "name": "销售明细分析",
                "keywords": ["sales", "order", "customer", "product", "amount", "quantity"],
                "cn_keywords": ["销售", "订单", "客户", "产品", "金额", "数量",
                                "销量", "单价", "门店", "商品", "交易"]
            },
            {
                "type": "department_performance",
                "name": "部门绩效分析",
                "keywords": ["department", "team", "division", "headcount", "performance"],
                "cn_keywords": ["部门", "团队", "人数", "绩效", "考核", "组织",
                                "人效", "人员"]
            },
            {
                "type": "cost_analysis",
                "name": "成本分析",
                "keywords": ["cost", "expense", "material", "labor", "overhead"],
                "cn_keywords": ["成本", "费用", "材料", "人工", "制造费用",
                                "直接成本", "间接成本", "折旧", "原料", "采购"]
            },
            {
                "type": "production_report",
                "name": "生产报表分析",
                "keywords": ["production", "output", "yield", "batch", "line"],
                "cn_keywords": ["产量", "产能", "良品率", "批次", "产线", "车间",
                                "工序", "班次", "在制品", "报废"]
            },
            {
                "type": "inventory_report",
                "name": "库存报表分析",
                "keywords": ["inventory", "stock", "warehouse", "inbound", "outbound"],
                "cn_keywords": ["库存", "仓库", "入库", "出库", "盘点", "周转",
                                "安全库存", "库龄"]
            }
        ]

        # Score each scenario
        best_score = 0
        best_scenario = None

        for scenario in scenarios:
            score = 0
            for kw in scenario["keywords"]:
                if kw in all_text:
                    score += 2
            for kw in scenario["cn_keywords"]:
                if kw in all_text:
                    score += 2

            if score > best_score:
                best_score = score
                best_scenario = scenario

        # Identify dimensions and measures from columns
        dimensions = []
        measures = []

        dimension_keywords = ["date", "time", "month", "year", "region", "product",
                              "customer", "department", "category", "name", "type",
                              "日期", "时间", "月", "年", "地区", "区域", "产品",
                              "客户", "部门", "分类", "类别", "名称", "类型",
                              "门店", "品牌", "渠道", "供应商"]
        measure_keywords = ["amount", "quantity", "price", "cost", "profit", "rate",
                            "total", "sum", "count", "revenue", "budget", "actual",
                            "金额", "数量", "单价", "价格", "成本", "利润", "率",
                            "合计", "总计", "收入", "预算", "实际", "销量",
                            "产量", "库存", "费用", "毛利", "净利"]

        for col in columns:
            col_l = col.lower()
            if any(kw in col_l for kw in dimension_keywords):
                dimensions.append(col)
            elif any(kw in col_l for kw in measure_keywords):
                measures.append(col)
            else:
                # Check sample data type
                for row in sample_rows[:3]:
                    val = row.get(col)
                    if isinstance(val, (int, float)):
                        measures.append(col)
                        break
                    elif isinstance(val, str) and val:
                        dimensions.append(col)
                        break

        # Deduplicate
        dimensions = list(dict.fromkeys(dimensions))[:5]
        measures = list(dict.fromkeys(measures))[:5]

        if best_scenario and best_score >= 2:
            # Generate basic recommendations
            recommendations = []
            if dimensions:
                recommendations.append(f"Analyze by {dimensions[0]}")
            if len(measures) >= 2:
                recommendations.append(f"Compare {measures[0]} and {measures[1]}")
            if "date" in col_text or "time" in col_text or "month" in col_text:
                recommendations.append("Analyze trend over time")

            return ScenarioResult(
                scenario_type=best_scenario["type"],
                scenario_name=best_scenario["name"],
                confidence=min(best_score / 10, 0.8),
                dimensions=dimensions,
                measures=measures,
                recommended_analyses=recommendations,
                reasoning=f"Rule-based detection: matched {best_score} keywords for {best_scenario['name']}",
                method="rule_fallback"
            )

        # Default: general table
        return ScenarioResult(
            scenario_type="general_table",
            scenario_name="General Table",
            confidence=0.5,
            dimensions=dimensions,
            measures=measures,
            recommended_analyses=["Basic statistics", "Data distribution"],
            reasoning="No specific scenario matched, treating as general table",
            method="rule_fallback"
        )

    def get_cache_stats(self) -> Dict[str, Any]:
        """Get cache statistics"""
        return self._cache.get_stats()

    def clear_cache(self):
        """Clear the scenario cache"""
        self._cache.clear()

    async def close(self):
        """No-op: shared client lifecycle managed by main.py lifespan"""


# Global instance
_detector: Optional[LLMScenarioDetector] = None


def get_scenario_detector() -> LLMScenarioDetector:
    """Get or create global scenario detector instance"""
    global _detector
    if _detector is None:
        _detector = LLMScenarioDetector()
    return _detector


async def detect_scenario(
    columns: List[str],
    sample_rows: List[Dict[str, Any]],
    metadata: Optional[Dict[str, Any]] = None,
    use_cache: bool = True
) -> ScenarioResult:
    """
    Convenience function to detect scenario.

    Args:
        columns: List of column names
        sample_rows: Sample data rows
        metadata: Optional metadata (title, sheet_name, etc.)
        use_cache: Whether to use caching (default True)

    Returns:
        ScenarioResult with detected scenario info

    Example:
        >>> result = await detect_scenario(
        ...     columns=["Month", "Department", "Budget", "Actual", "Variance"],
        ...     sample_rows=[
        ...         {"Month": "Jan", "Department": "Sales", "Budget": 100000, "Actual": 95000, "Variance": -5000},
        ...         {"Month": "Jan", "Department": "IT", "Budget": 50000, "Actual": 52000, "Variance": 2000}
        ...     ],
        ...     metadata={"title": "Department Budget 2025"}
        ... )
        >>> print(result.scenario_type)  # "budget_report"
        >>> print(result.dimensions)     # ["Month", "Department"]
        >>> print(result.measures)       # ["Budget", "Actual", "Variance"]
    """
    detector = get_scenario_detector()
    return await detector.detect(columns, sample_rows, metadata, use_cache)


def detect_scenario_sync(
    columns: List[str],
    sample_rows: List[Dict[str, Any]],
    metadata: Optional[Dict[str, Any]] = None
) -> ScenarioResult:
    """
    Synchronous version using rule-based detection only.

    Use this when async is not available or LLM is not needed.
    """
    detector = get_scenario_detector()
    return detector._detect_with_rules(columns, sample_rows, metadata)
