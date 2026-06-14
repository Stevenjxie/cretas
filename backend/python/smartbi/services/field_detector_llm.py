from __future__ import annotations
"""
LLM-Based Field Detector Service

Dynamically detects field types using LLM instead of hardcoded regex patterns.
Implements "LLM detection + cache" pattern for efficiency.

Features:
1. LLM-powered semantic type recognition
2. Structure-based caching to reduce API calls
3. Automatic chart role assignment
4. Fallback to rule-based detection when LLM unavailable
"""
import hashlib
import logging
import time
from dataclasses import dataclass, field
from typing import Any, Dict, List, Optional

import pandas as pd

from services.utils.json_parser import robust_json_parse

logger = logging.getLogger(__name__)


@dataclass
class FieldDetectionResult:
    """
    LLM-based field detection result.

    Attributes:
        field_name: Original column name
        data_type: Detected data type (string, integer, float, date, boolean)
        semantic_type: Business semantic type (amount, quantity, date, category, etc.)
        chart_role: Recommended chart role (dimension, measure, time, series)
        confidence: Detection confidence (0.0-1.0)
        description: LLM's description of the field
        sample_values: Sample values from the column
        statistics: Statistics for numeric fields
    """
    field_name: str
    data_type: str
    semantic_type: str
    chart_role: str
    confidence: float = 0.8
    description: str = ""
    sample_values: List[Any] = field(default_factory=list)
    statistics: Optional[Dict[str, float]] = None
    nullable: bool = False
    null_count: int = 0
    unique_count: int = 0
    unique_values: Optional[List[Any]] = None

    def to_dict(self) -> Dict[str, Any]:
        """Convert to dictionary for JSON serialization"""
        return {
            "fieldName": self.field_name,
            "dataType": self.data_type,
            "semanticType": self.semantic_type,
            "chartRole": self.chart_role,
            "confidence": self.confidence,
            "description": self.description,
            "sampleValues": self.sample_values,
            "statistics": self.statistics,
            "nullable": self.nullable,
            "nullCount": self.null_count,
            "uniqueCount": self.unique_count,
            "uniqueValues": self.unique_values
        }


@dataclass
class FieldCacheEntry:
    """Cache entry for field detection results"""
    cache_key: str
    results: List[FieldDetectionResult]
    created_at: float
    accessed_at: float
    access_count: int = 1


class FieldDetectionCache:
    """
    Cache for field detection results.

    Uses column structure signature for cache keys,
    allowing same-structure files to reuse detection results.
    """

    def __init__(self, ttl_seconds: int = 3600, max_entries: int = 500):
        self._cache: Dict[str, FieldCacheEntry] = {}
        self._ttl_seconds = ttl_seconds
        self._max_entries = max_entries

    def _generate_structure_key(
        self,
        columns: List[str],
        sample_types: Optional[List[str]] = None
    ) -> str:
        """Generate cache key from column names and value types."""
        normalized_cols = [c.lower().strip() for c in columns if c]
        signature = "|".join(normalized_cols[:30])

        if sample_types:
            signature += "||" + "|".join(sample_types[:30])

        return hashlib.md5(signature.encode()).hexdigest()[:16]

    def get(
        self,
        columns: List[str],
        sample_types: Optional[List[str]] = None
    ) -> Optional[List[FieldDetectionResult]]:
        """Get cached detection results if available."""
        cache_key = self._generate_structure_key(columns, sample_types)
        entry = self._cache.get(cache_key)

        if entry is None:
            return None

        if time.time() - entry.created_at > self._ttl_seconds:
            del self._cache[cache_key]
            return None

        entry.accessed_at = time.time()
        entry.access_count += 1

        logger.debug(f"Field detection cache hit: {cache_key}")
        return entry.results

    def set(
        self,
        columns: List[str],
        results: List[FieldDetectionResult],
        sample_types: Optional[List[str]] = None
    ) -> str:
        """Cache field detection results."""
        cache_key = self._generate_structure_key(columns, sample_types)

        entry = FieldCacheEntry(
            cache_key=cache_key,
            results=results,
            created_at=time.time(),
            accessed_at=time.time()
        )

        self._cache[cache_key] = entry

        if len(self._cache) > self._max_entries:
            self._evict_old_entries()

        logger.debug(f"Field detection cached: {cache_key}")
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


class LLMFieldDetector:
    """
    LLM-based field detector.

    Uses LLM to dynamically understand column semantics instead of
    relying on hardcoded regex patterns.

    Workflow:
    1. Calculate basic data types (numeric check, date check)
    2. Check cache for existing semantic detection
    3. If cache miss, call LLM for semantic detection
    4. Cache the result for future use
    """

    SYSTEM_PROMPT = """你是一个专业的数据分析师，擅长识别数据列的语义类型。

分析每个列的名称和样本数据，判断：
1. 语义类型 (semantic_type): 这列数据在业务中代表什么
2. 图表角色 (chart_role): 这列在图表中应该扮演什么角色
3. 描述: 简短说明这列的含义

语义类型 (semantic_type) 可选值：
- amount: 金额类（销售额、收入、成本、价格等）
- quantity: 数量类（件数、个数、订单数等）
- percentage: 百分比/比率（增长率、完成率、占比等）
- date: 日期/时间维度
- category: 分类维度（产品类别、渠道、部门等）
- geography: 地理维度（地区、城市、省份等）
- product: 产品维度
- customer: 客户维度
- id: 标识符/编码
- name: 名称类（产品名、客户名等）
- text: 其他文本
- unknown: 无法确定

图表角色 (chart_role) 可选值：
- dimension: 维度（用于X轴、分组、筛选）
- measure: 度量（用于Y轴、数值计算）
- time: 时间轴
- series: 系列分组
- tooltip: 辅助信息

请返回JSON格式：
{
    "fields": [
        {
            "field_name": "列名",
            "semantic_type": "语义类型",
            "chart_role": "图表角色",
            "confidence": 0.0-1.0,
            "description": "简短描述"
        }
    ]
}"""

    def __init__(self):
        self._settings = None
        self._client = None
        self._cache = FieldDetectionCache()

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

    async def detect_fields(
        self,
        headers: List[str],
        rows: List[List[Any]],
        use_cache: bool = True
    ) -> List[FieldDetectionResult]:
        """
        Detect field types for all columns.

        Args:
            headers: Column headers
            rows: Data rows
            use_cache: Whether to use caching

        Returns:
            List of FieldDetectionResult
        """
        if not headers:
            return []

        df = pd.DataFrame(rows, columns=headers)

        # P0 出境脱敏: 注册敏感列真名 → 共享客户端包装层在出境时占位 (输出是字段元数据, 无需还原).
        from common.llm_redactor import register_df_for_egress
        register_df_for_egress(df)

        # Step 1: Calculate basic data types and statistics
        basic_info = self._analyze_basic_types(df)
        sample_types = [info["data_type"] for info in basic_info.values()]

        # Step 2: Check cache
        if use_cache:
            cached = self._cache.get(headers, sample_types)
            if cached:
                # Update statistics from current data
                for result in cached:
                    if result.field_name in basic_info:
                        info = basic_info[result.field_name]
                        result.statistics = info.get("statistics")
                        result.sample_values = info.get("sample_values", [])
                        result.null_count = info.get("null_count", 0)
                        result.unique_count = info.get("unique_count", 0)
                logger.info(f"Field detection from cache: {len(cached)} fields")
                return cached

        # Step 3: Try LLM detection
        if self.settings.llm_api_key:
            try:
                results = await self._detect_with_llm(df, headers, basic_info)

                # Cache the results
                if use_cache:
                    self._cache.set(headers, results, sample_types)

                logger.info(f"Field detection by LLM: {len(results)} fields")
                return results

            except Exception as e:
                logger.error(f"LLM field detection failed: {e}")

        # Step 4: Fallback to rule-based detection
        results = self._detect_with_rules(df, headers, basic_info)

        if use_cache:
            self._cache.set(headers, results, sample_types)

        logger.info(f"Field detection by rules: {len(results)} fields")
        return results

    def _analyze_basic_types(self, df: pd.DataFrame) -> Dict[str, Dict[str, Any]]:
        """Analyze basic data types and calculate statistics."""
        result = {}

        # Handle duplicate column names by using iloc
        columns = list(df.columns)
        for idx, col in enumerate(columns):
            # Use iloc to handle duplicate column names
            values = df.iloc[:, idx]

            # Ensure we have a Series, not a DataFrame
            if isinstance(values, pd.DataFrame):
                values = values.iloc[:, 0]

            sample = values.dropna().head(100)

            # Detect basic data type
            data_type = self._detect_data_type(sample)

            # Calculate statistics for numeric columns
            statistics = None
            if data_type in ("integer", "float"):
                numeric = pd.to_numeric(values, errors='coerce')
                if not numeric.isna().all():
                    statistics = {
                        "min": float(numeric.min()) if pd.notna(numeric.min()) else None,
                        "max": float(numeric.max()) if pd.notna(numeric.max()) else None,
                        "mean": float(numeric.mean()) if pd.notna(numeric.mean()) else None,
                        "sum": float(numeric.sum()) if pd.notna(numeric.sum()) else None,
                        "std": float(numeric.std()) if pd.notna(numeric.std()) else None
                    }

            # Get unique values for low-cardinality columns
            unique_values = None
            unique_count = values.nunique()
            if unique_count <= 50 and data_type == "string":
                unique_values = values.dropna().unique().tolist()[:50]

            result[col] = {
                "data_type": data_type,
                "statistics": statistics,
                "sample_values": sample.head(5).tolist(),
                "null_count": int(values.isna().sum()),
                "unique_count": int(unique_count),
                "unique_values": unique_values,
                "nullable": values.isna().any()
            }

        return result

    def _detect_data_type(self, sample: pd.Series) -> str:
        """Detect basic data type from sample values."""
        if sample.empty:
            return "unknown"

        # Check boolean
        unique_lower = set(str(v).lower() for v in sample.dropna())
        if unique_lower.issubset({'true', 'false', '1', '0', 'yes', 'no', '是', '否'}):
            return "boolean"

        # Check numeric
        numeric_sample = pd.to_numeric(sample, errors='coerce')
        if numeric_sample.notna().sum() > len(sample) * 0.8:
            non_null = numeric_sample.dropna()
            if len(non_null) > 0 and (non_null == non_null.astype(int)).all():
                return "integer"
            return "float"

        # Check date
        if self._check_date_patterns(sample):
            return "date"

        try:
            parsed_dates = pd.to_datetime(sample, errors='coerce')
            if parsed_dates.notna().sum() > len(sample) * 0.8:
                valid_dates = parsed_dates.dropna()
                if len(valid_dates) > 0:
                    min_year = valid_dates.min().year
                    max_year = valid_dates.max().year
                    if 1900 <= min_year <= 2100 and 1900 <= max_year <= 2100:
                        return "date"
        except Exception:
            pass

        return "string"

    def _check_date_patterns(self, sample: pd.Series) -> bool:
        """Check if sample values match date patterns."""
        import re

        date_patterns = [
            r'\d{4}年\d{1,2}月',
            r'\d{4}[-/]\d{1,2}[-/]\d{1,2}',
            r'\d{4}[-/]\d{1,2}',
            r'FY\d{4}',
            r'Q[1-4]\s*\d{4}',
            r'\d{4}\s*Q[1-4]',
        ]

        match_count = 0
        total = 0

        for value in sample.dropna():
            str_val = str(value).strip()
            if not str_val:
                continue
            total += 1
            for pattern in date_patterns:
                if re.search(pattern, str_val, re.IGNORECASE):
                    match_count += 1
                    break

        return total > 0 and match_count / total > 0.5

    async def _detect_with_llm(
        self,
        df: pd.DataFrame,
        headers: List[str],
        basic_info: Dict[str, Dict[str, Any]]
    ) -> List[FieldDetectionResult]:
        """Call LLM to detect semantic types."""

        # Build prompt
        columns_desc = []
        for col in headers[:30]:  # Limit columns
            info = basic_info.get(col, {})
            data_type = info.get("data_type", "unknown")
            samples = info.get("sample_values", [])[:3]
            unique_count = info.get("unique_count", 0)

            desc = f"- {col} (类型={data_type}, 唯一值数={unique_count}, 样本={samples})"
            columns_desc.append(desc)

        prompt = f"""请分析以下数据列，识别每列的语义类型和图表角色。

列信息:
{chr(10).join(columns_desc)}

请返回JSON格式的分析结果。"""

        # Call LLM via multi-provider router (SLOT.MAPPER — field semantic
        # mapping). Shares the free-first chain + circuit-breaker + free-tier
        # fallback; the model field is injected per-slot by call_chain.
        from common.llm_router import call_chain, SLOT
        from common.llm_metrics import llm_caller_context

        payload = {
            "messages": [
                {"role": "system", "content": self.SYSTEM_PROMPT},
                {"role": "user", "content": prompt}
            ],
            "temperature": 0.3,
            "max_tokens": 2000,
            "enable_thinking": False
        }

        with llm_caller_context("field_detector_llm"):
            result_json = await call_chain(SLOT.MAPPER, payload)

        content = result_json["choices"][0]["message"]["content"]

        return self._parse_llm_response(content, headers, basic_info)

    def _parse_llm_response(
        self,
        content: str,
        headers: List[str],
        basic_info: Dict[str, Dict[str, Any]]
    ) -> List[FieldDetectionResult]:
        """Parse LLM response into FieldDetectionResult list."""
        try:
            # Fix: Use centralized robust JSON parser
            data = robust_json_parse(content, fallback={})
            if not data:
                logger.warning("Could not parse JSON from LLM field detection response")
                return self._detect_with_rules(
                    pd.DataFrame(columns=headers), headers, basic_info
                )
            llm_fields = {f["field_name"]: f for f in data.get("fields", [])}

            results = []
            for col in headers:
                info = basic_info.get(col, {})
                llm_info = llm_fields.get(col, {})

                semantic_type = llm_info.get("semantic_type", "unknown")
                chart_role = llm_info.get("chart_role", "dimension")

                # Validate chart_role based on data type
                if info.get("data_type") in ("integer", "float") and semantic_type in ("amount", "quantity", "percentage"):  # noqa: E501
                    chart_role = "measure"
                elif info.get("data_type") == "date" or semantic_type == "date":
                    chart_role = "time"

                results.append(FieldDetectionResult(
                    field_name=col,
                    data_type=info.get("data_type", "string"),
                    semantic_type=semantic_type,
                    chart_role=chart_role,
                    confidence=llm_info.get("confidence", 0.8),
                    description=llm_info.get("description", ""),
                    sample_values=info.get("sample_values", []),
                    statistics=info.get("statistics"),
                    nullable=info.get("nullable", False),
                    null_count=info.get("null_count", 0),
                    unique_count=info.get("unique_count", 0),
                    unique_values=info.get("unique_values")
                ))

            return results

        except Exception as e:
            logger.error(f"Failed to parse LLM response: {e}")
            return self._detect_with_rules(
                pd.DataFrame(columns=headers), headers, basic_info
            )

    def _detect_with_rules(
        self,
        df: pd.DataFrame,
        headers: List[str],
        basic_info: Dict[str, Dict[str, Any]]
    ) -> List[FieldDetectionResult]:
        """Fallback rule-based detection."""

        # Simple keyword patterns
        patterns = {
            "amount": ["金额", "销售", "收入", "成本", "价格", "amount", "sales", "revenue", "cost", "price"],
            "quantity": ["数量", "件数", "个数", "qty", "quantity", "count", "num"],
            "percentage": ["率", "比", "percent", "ratio", "rate", "%"],
            "date": ["日期", "时间", "月", "年", "date", "time", "month", "year", "period"],
            "category": ["类别", "分类", "类型", "category", "type", "class"],
            "geography": ["地区", "区域", "城市", "省", "region", "city", "area"],
            "product": ["产品", "商品", "物料", "product", "item", "sku"],
            "customer": ["客户", "顾客", "customer", "client"],
            "id": ["编号", "编码", "id", "code", "no"],
            "name": ["名称", "姓名", "name"]
        }

        results = []
        for col in headers:
            info = basic_info.get(col, {})
            data_type = info.get("data_type", "string")
            col_lower = col.lower()

            # Detect semantic type
            semantic_type = "unknown"
            for sem_type, keywords in patterns.items():
                if any(kw in col_lower for kw in keywords):
                    semantic_type = sem_type
                    break

            # Infer from data type if not matched
            if semantic_type == "unknown":
                if data_type in ("integer", "float"):
                    semantic_type = "amount"
                elif data_type == "date":
                    semantic_type = "date"
                elif data_type == "string":
                    if info.get("unique_count", 0) < 20:
                        semantic_type = "category"
                    else:
                        semantic_type = "name"

            # Determine chart role
            if semantic_type == "date":
                chart_role = "time"
            elif semantic_type in ("amount", "quantity", "percentage"):
                chart_role = "measure"
            elif semantic_type in ("category", "geography", "product", "customer"):
                chart_role = "dimension"
            elif semantic_type == "id":
                chart_role = "series"
            else:
                chart_role = "tooltip"

            results.append(FieldDetectionResult(
                field_name=col,
                data_type=data_type,
                semantic_type=semantic_type,
                chart_role=chart_role,
                confidence=0.6,  # Lower confidence for rule-based
                description=f"Rule-based detection: {semantic_type}",
                sample_values=info.get("sample_values", []),
                statistics=info.get("statistics"),
                nullable=info.get("nullable", False),
                null_count=info.get("null_count", 0),
                unique_count=info.get("unique_count", 0),
                unique_values=info.get("unique_values")
            ))

        return results

    def get_cache_stats(self) -> Dict[str, Any]:
        """Get cache statistics."""
        return self._cache.get_stats()

    def clear_cache(self):
        """Clear the detection cache."""
        self._cache.clear()

    async def close(self):
        """No-op: shared client lifecycle managed by main.py lifespan"""


# Global instance
_detector: Optional[LLMFieldDetector] = None


def get_field_detector() -> LLMFieldDetector:
    """Get or create global field detector instance."""
    global _detector
    if _detector is None:
        _detector = LLMFieldDetector()
    return _detector


async def detect_fields(
    headers: List[str],
    rows: List[List[Any]],
    use_cache: bool = True
) -> List[FieldDetectionResult]:
    """
    Convenience function to detect field types.

    Args:
        headers: Column headers
        rows: Data rows
        use_cache: Whether to use caching

    Returns:
        List of FieldDetectionResult
    """
    detector = get_field_detector()
    return await detector.detect_fields(headers, rows, use_cache)
