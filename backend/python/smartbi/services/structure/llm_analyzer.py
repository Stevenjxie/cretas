"""
LLM Structure Analyzer - 使用LLM分析Excel结构

设计原则：
1. 输入RawExporter导出的原始数据（JSON/MD格式）
2. 使用LLM识别表头、数据起始行、合并单元格含义
3. 输出结构化的分析结果

工作流程：
1. 将原始数据转为Markdown格式（LLM友好）
2. 构建Prompt让LLM分析结构
3. 解析LLM响应，提取结构信息
4. 返回结构化结果供后续分析使用
"""
from __future__ import annotations

import hashlib
import json
import logging
import time
from dataclasses import dataclass, field
from typing import Any, Dict, List, Optional

import httpx

import pandas as pd

from config import get_settings
from ..excel.raw_exporter import RawExporter, RawSheetData
from .table_classifier import TableClassifier

logger = logging.getLogger(__name__)


# ============================================================
# Structure Analysis Cache
# ============================================================

@dataclass
class StructureCacheEntry:
    """Cache entry for structure analysis results"""
    cache_key: str
    result: Dict[str, Any]  # Serialized FullAnalysisResult
    created_at: float
    accessed_at: float
    access_count: int = 1


class StructureAnalysisCache:
    """
    Cache for structure analysis results.

    Uses column structure signature for cache keys,
    allowing same-structure files to reuse LLM analysis results.
    """

    def __init__(self, ttl_seconds: int = 3600, max_entries: int = 500):
        self._cache: Dict[str, StructureCacheEntry] = {}
        self._ttl_seconds = ttl_seconds
        self._max_entries = max_entries
        self._hits = 0
        self._misses = 0

    def _generate_structure_key(self, raw_data: RawSheetData) -> str:
        """
        Generate a cache key based on column structure.

        Key = MD5(sorted column names + column count + sample data types)[:16]
        """
        # Extract column names from the first non-empty row (likely header)
        col_names = []
        col_types = []

        if raw_data.rows:
            # Use first row as header source
            header_row = raw_data.rows[0]
            for cell in header_row.cells:
                name = str(cell.value).lower().strip() if cell.value else ""
                col_names.append(name)

            # Sample data types from first data rows (rows 1-5)
            for row in raw_data.rows[1:6]:
                for cell in row.cells:
                    col_types.append(cell.value_type)

        # Build signature: sorted column names + count + sample types
        sorted_cols = sorted(col_names[:30])
        signature_parts = [
            "|".join(sorted_cols),
            str(len(col_names)),
            "|".join(col_types[:60])
        ]
        full_signature = "||".join(signature_parts)

        return hashlib.md5(full_signature.encode()).hexdigest()[:16]

    def get(self, raw_data: RawSheetData) -> Optional[Dict[str, Any]]:
        """
        Get cached analysis result if available.

        Returns None if not found or expired.
        """
        cache_key = self._generate_structure_key(raw_data)
        entry = self._cache.get(cache_key)

        if entry is None:
            self._misses += 1
            return None

        # Check TTL
        if time.time() - entry.created_at > self._ttl_seconds:
            del self._cache[cache_key]
            self._misses += 1
            return None

        # Update access stats
        entry.accessed_at = time.time()
        entry.access_count += 1
        self._hits += 1

        logger.info(
            f"Structure cache HIT: key={cache_key}, "
            f"hits/misses={self._hits}/{self._misses}"
        )
        return entry.result

    def set(self, raw_data: RawSheetData, result: Dict[str, Any]) -> str:
        """
        Cache a structure analysis result.

        Returns the cache key.
        """
        cache_key = self._generate_structure_key(raw_data)

        entry = StructureCacheEntry(
            cache_key=cache_key,
            result=result,
            created_at=time.time(),
            accessed_at=time.time()
        )

        self._cache[cache_key] = entry

        # Evict old entries if needed
        if len(self._cache) > self._max_entries:
            self._evict_old_entries()

        logger.debug(f"Structure analysis cached: {cache_key}")
        return cache_key

    def _evict_old_entries(self):
        """Remove oldest entries when cache is full."""
        if len(self._cache) <= self._max_entries:
            return

        sorted_keys = sorted(
            self._cache.keys(),
            key=lambda k: self._cache[k].accessed_at
        )

        # Remove oldest 10%
        to_remove = max(1, len(sorted_keys) // 10)
        for key in sorted_keys[:to_remove]:
            del self._cache[key]

        logger.debug(f"Evicted {to_remove} old structure cache entries")

    def get_stats(self) -> Dict[str, Any]:
        """Get cache statistics."""
        now = time.time()
        active = [e for e in self._cache.values()
                  if now - e.created_at < self._ttl_seconds]

        return {
            "total_entries": len(self._cache),
            "active_entries": len(active),
            "total_hits": self._hits,
            "total_misses": self._misses,
            "hit_rate": self._hits / max(self._hits + self._misses, 1)
        }

    def clear(self):
        """Clear all cache entries."""
        self._cache.clear()
        self._hits = 0
        self._misses = 0
        logger.info("Structure analysis cache cleared")


# Module-level cache instance (shared across all LLMStructureAnalyzer instances)
_structure_cache = StructureAnalysisCache()


@dataclass
class ColumnInfo:
    """列信息"""
    col_letter: str           # 列字母 (A, B, C...)
    col_index: int            # 列索引 (0-based)
    name: str                 # 识别的列名
    data_type: str            # 数据类型: text, number, date, percentage, currency
    meaning: str              # 列含义说明
    role: str                 # 角色: dimension, measure, time, label, empty
    is_key: bool = False      # 是否是关键列
    sample_values: List[Any] = field(default_factory=list)


@dataclass
class StructureAnalysis:
    """结构分析结果"""
    success: bool
    sheet_name: str
    total_rows: int
    total_cols: int

    # 结构检测
    title_rows: List[int]             # 标题行（如"利润表"所在行）
    header_rows: List[int]            # 表头行
    data_start_row: int               # 数据起始行 (0-based)
    data_end_row: Optional[int]       # 数据结束行（None表示到末尾）

    # 列信息
    columns: List[ColumnInfo]

    # 表格类型
    table_type: str                   # profit_statement, sales_detail, budget_report, etc.
    table_type_confidence: float

    # 合并单元格含义
    merged_cells_meaning: Dict[str, str] = field(default_factory=dict)

    # 分析说明
    notes: List[str] = field(default_factory=list)
    error: Optional[str] = None
    method: str = "llm"               # llm, rule_based

    # 宽表处理
    transpose: bool = False           # 是否需要转置
    is_wide_table: bool = False       # 是否是宽表(100+列)


@dataclass
class AnalysisRecommendation:
    """分析推荐"""
    analysis_type: str        # budget_vs_actual, trend, ranking, structure, etc.
    description: str          # 描述
    priority: int             # 优先级 (1=最高)
    chart_types: List[str]    # 推荐图表类型
    required_columns: List[str]  # 需要的列


@dataclass
class FullAnalysisResult:
    """完整分析结果（结构+推荐）"""
    structure: StructureAnalysis
    recommendations: List[AnalysisRecommendation]
    insights: List[str]       # 初步洞察
    warnings: List[str]       # 数据质量警告

    def to_cache_dict(self) -> Dict[str, Any]:
        """Serialize to dict for caching."""
        return {
            "structure": {
                "success": self.structure.success,
                "sheet_name": self.structure.sheet_name,
                "total_rows": self.structure.total_rows,
                "total_cols": self.structure.total_cols,
                "title_rows": self.structure.title_rows,
                "header_rows": self.structure.header_rows,
                "data_start_row": self.structure.data_start_row,
                "data_end_row": self.structure.data_end_row,
                "columns": [
                    {
                        "col_letter": c.col_letter,
                        "col_index": c.col_index,
                        "name": c.name,
                        "data_type": c.data_type,
                        "meaning": c.meaning,
                        "role": c.role,
                        "is_key": c.is_key,
                        "sample_values": c.sample_values,
                    }
                    for c in self.structure.columns
                ],
                "table_type": self.structure.table_type,
                "table_type_confidence": self.structure.table_type_confidence,
                "merged_cells_meaning": self.structure.merged_cells_meaning,
                "notes": self.structure.notes,
                "error": self.structure.error,
                "method": self.structure.method,
                "transpose": self.structure.transpose,
                "is_wide_table": self.structure.is_wide_table,
            },
            "recommendations": [
                {
                    "analysis_type": r.analysis_type,
                    "description": r.description,
                    "priority": r.priority,
                    "chart_types": r.chart_types,
                    "required_columns": r.required_columns,
                }
                for r in self.recommendations
            ],
            "insights": self.insights,
            "warnings": self.warnings,
        }

    @classmethod
    def from_cache_dict(cls, data: Dict[str, Any]) -> "FullAnalysisResult":
        """Deserialize from cached dict."""
        s = data["structure"]
        columns = [
            ColumnInfo(
                col_letter=c["col_letter"],
                col_index=c["col_index"],
                name=c["name"],
                data_type=c["data_type"],
                meaning=c["meaning"],
                role=c["role"],
                is_key=c.get("is_key", False),
                sample_values=c.get("sample_values", []),
            )
            for c in s.get("columns", [])
        ]

        structure = StructureAnalysis(
            success=s["success"],
            sheet_name=s["sheet_name"],
            total_rows=s["total_rows"],
            total_cols=s["total_cols"],
            title_rows=s.get("title_rows", []),
            header_rows=s.get("header_rows", [0]),
            data_start_row=s.get("data_start_row", 1),
            data_end_row=s.get("data_end_row"),
            columns=columns,
            table_type=s.get("table_type", "general_table"),
            table_type_confidence=s.get("table_type_confidence", 0.5),
            merged_cells_meaning=s.get("merged_cells_meaning", {}),
            notes=s.get("notes", []),
            error=s.get("error"),
            method="cache",
            transpose=s.get("transpose", False),
            is_wide_table=s.get("is_wide_table", False),
        )

        recommendations = [
            AnalysisRecommendation(
                analysis_type=r["analysis_type"],
                description=r["description"],
                priority=r.get("priority", 5),
                chart_types=r.get("chart_types", []),
                required_columns=r.get("required_columns", []),
            )
            for r in data.get("recommendations", [])
        ]

        return cls(
            structure=structure,
            recommendations=recommendations,
            insights=data.get("insights", []),
            warnings=data.get("warnings", []),
        )


class LLMStructureAnalyzer:
    """
    使用LLM分析Excel结构

    输入: RawExporter导出的原始数据
    输出: 结构分析结果 + 分析推荐
    """

    # 超时和token配置
    BASE_TIMEOUT = 30.0
    MAX_TIMEOUT = 180.0
    BASE_TOKENS = 2000
    MAX_TOKENS = 8000

    def __init__(self):
        self.settings = get_settings()
        self.raw_exporter = RawExporter()

    @property
    def client(self) -> httpx.AsyncClient:
        from common.llm_client import get_llm_http_client
        return get_llm_http_client()

    def _calculate_timeout(self, row_count: int, col_count: int) -> float:
        """
        根据数据规模计算自适应超时时间

        Args:
            row_count: 行数
            col_count: 列数

        Returns:
            超时时间（秒）
        """
        cells = row_count * col_count
        # 每100个单元格增加5秒，最多增加150秒
        extra_time = min(cells / 100 * 5, 150)
        return min(self.BASE_TIMEOUT + extra_time, self.MAX_TIMEOUT)

    def _calculate_max_tokens(self, col_count: int) -> int:
        """
        根据列数计算LLM输出的max_tokens

        Args:
            col_count: 列数

        Returns:
            max_tokens值
        """
        # 每列约需要50 tokens来描述
        col_tokens = col_count * 50
        return min(self.BASE_TOKENS + col_tokens, self.MAX_TOKENS)

    async def analyze(
        self,
        raw_data: RawSheetData,
        include_recommendations: bool = True
    ) -> FullAnalysisResult:
        """
        分析Excel结构

        Args:
            raw_data: RawExporter导出的原始数据
            include_recommendations: 是否包含分析推荐

        Returns:
            FullAnalysisResult
        """
        # 0. 检查结构缓存
        cached_dict = _structure_cache.get(raw_data)
        if cached_dict is not None:
            try:
                result = FullAnalysisResult.from_cache_dict(cached_dict)
                logger.info(
                    f"Structure analysis from cache: "
                    f"type={result.structure.table_type}, "
                    f"cols={result.structure.total_cols}"
                )
                return result
            except Exception as e:
                logger.warning(f"Failed to restore from cache, re-analyzing: {e}")

        # 1. 将数据转为Markdown（LLM友好格式）
        md_content = self.raw_exporter.to_markdown(
            raw_data,
            max_rows=30,      # 给LLM看前30行
            truncate=False    # 不截断，让LLM看完整内容
        )

        # 2. 构建Prompt
        prompt = self._build_analysis_prompt(raw_data, md_content, include_recommendations)

        # 3. 调用LLM
        try:
            if not self.settings.llm_api_key:
                logger.info("LLM API key not configured, using rule-based analysis")
                result = self._rule_based_analysis(raw_data, include_recommendations)
                _structure_cache.set(raw_data, result.to_cache_dict())
                return result

            response = await self._call_llm(
                prompt,
                row_count=raw_data.total_rows,
                col_count=raw_data.total_cols
            )
            result = self._parse_response(response, raw_data, include_recommendations)

            # 缓存分析结果
            _structure_cache.set(raw_data, result.to_cache_dict())
            return result

        except Exception as e:
            logger.error(f"LLM analysis failed: {e}")
            result = self._rule_based_analysis(raw_data, include_recommendations)
            _structure_cache.set(raw_data, result.to_cache_dict())
            return result

    async def analyze_from_bytes(
        self,
        file_bytes: bytes,
        sheet_index: int = 0,
        include_recommendations: bool = True
    ) -> FullAnalysisResult:
        """
        从Excel文件字节直接分析

        Args:
            file_bytes: Excel文件字节
            sheet_index: Sheet索引
            include_recommendations: 是否包含分析推荐

        Returns:
            FullAnalysisResult
        """
        raw_data = self.raw_exporter.export_sheet(file_bytes, sheet_index)
        return await self.analyze(raw_data, include_recommendations)

    def _build_analysis_prompt(
        self,
        raw_data: RawSheetData,
        md_content: str,
        include_recommendations: bool
    ) -> str:
        """构建分析Prompt"""

        recommendations_section = ""
        if include_recommendations:
            recommendations_section = """
    "recommendations": [
        {
            "analysis_type": "分析类型",
            "description": "分析描述",
            "priority": 1,
            "chart_types": ["推荐图表类型"],
            "required_columns": ["需要的列"]
        }
    ],
    "insights": ["初步洞察"],
    "warnings": ["数据质量警告"],"""

        prompt = f"""请分析以下Excel数据的结构，识别表头、数据行、列含义等信息。

## 数据信息
- Sheet名称: {raw_data.sheet_name}
- 总行数: {raw_data.total_rows}
- 总列数: {raw_data.total_cols}
- 合并单元格数: {len(raw_data.merged_cells)}

## 合并单元格信息
{json.dumps([m.to_dict() for m in raw_data.merged_cells[:10]], ensure_ascii=False, indent=2) if raw_data.merged_cells else "无"}  # noqa: E501

## 原始数据
{md_content}

## 分析要求

请识别：
1. **标题行**: 哪些行是表格标题（如"利润表"、"2025年预算执行表"）？
2. **表头行**: 哪些行是列名/表头？可能是多行合并表头。
3. **数据起始行**: 实际业务数据从第几行开始？(0-based索引)
4. **列含义**: 每列代表什么字段？是维度、度量还是时间？
5. **表格类型**: 这是什么类型的表格？(利润表、销售明细、预算报表、部门报表等)

## 输出格式

请返回JSON格式：
{{
    "structure": {{
        "title_rows": [标题行索引列表],
        "header_rows": [表头行索引列表],
        "data_start_row": 数据起始行索引,
        "data_end_row": null或数据结束行索引,
        "table_type": "表格类型",
        "table_type_confidence": 0.0-1.0,
        "columns": [
            {{
                "col_letter": "A",
                "col_index": 0,
                "name": "识别的列名",
                "data_type": "text/number/date/percentage/currency",
                "meaning": "列含义说明",
                "role": "dimension/measure/time/label/empty",
                "is_key": true/false
            }}
        ],
        "merged_cells_meaning": {{
            "A1:C1": "合并单元格含义说明"
        }},
        "notes": ["分析说明"]
    }},{recommendations_section}
}}

重要提示：
- 行索引从0开始
- 如果有多行表头（如分组表头），都列入header_rows
- role说明: dimension=维度(分类), measure=度量(数值), time=时间, label=标签, empty=空列
- table_type可选: profit_statement, sales_detail, budget_report, department_report, cost_analysis, receivable_aging, inventory_report, general_table"""  # noqa: E501

        return prompt

    async def _call_llm(self, prompt: str, row_count: int = 50, col_count: int = 10) -> str:
        """
        调用LLM API

        Args:
            prompt: 提示词
            row_count: 数据行数（用于计算超时）
            col_count: 数据列数（用于计算max_tokens）
        """
        from common.llm_router import call_chain, SLOT
        from common.llm_metrics import llm_caller_context

        # 动态计算超时和max_tokens
        timeout = self._calculate_timeout(row_count, col_count)
        max_tokens = self._calculate_max_tokens(col_count)

        logger.info(f"LLM call: {row_count}x{col_count} -> timeout={timeout:.1f}s, max_tokens={max_tokens}")

        # 走 call_chain 免费链 (SLOT.MAPPER — 结构分析) + 熔断 + 免费 fallback;
        # model 由 slot 注入 (替代原 llm_mapper_model 直连). 动态 timeout 透传给 call_chain.
        payload = {
            "messages": [
                {
                    "role": "system",
                    "content": "你是一个专业的数据分析师，擅长分析Excel数据结构。请用JSON格式回复，确保JSON格式正确。"
                },
                {
                    "role": "user",
                    "content": prompt
                }
            ],
            "temperature": 0.2,
            "max_tokens": max_tokens,
            "enable_thinking": False
        }

        with llm_caller_context("structure_llm_analyzer"):
            result = await call_chain(SLOT.MAPPER, payload, timeout=timeout)

        return result["choices"][0]["message"]["content"]

    def _parse_response(
        self,
        response: str,
        raw_data: RawSheetData,
        include_recommendations: bool
    ) -> FullAnalysisResult:
        """解析LLM响应"""
        try:
            # 提取JSON
            json_start = response.find('{')
            json_end = response.rfind('}') + 1
            if json_start >= 0 and json_end > json_start:
                json_str = response[json_start:json_end]
                data = json.loads(json_str)
            else:
                raise ValueError("No JSON found in response")

            structure_data = data.get("structure", {})

            # 解析列信息
            columns = []
            for col_data in structure_data.get("columns", []):
                columns.append(ColumnInfo(
                    col_letter=col_data.get("col_letter", ""),
                    col_index=col_data.get("col_index", 0),
                    name=col_data.get("name", ""),
                    data_type=col_data.get("data_type", "text"),
                    meaning=col_data.get("meaning", ""),
                    role=col_data.get("role", "dimension"),
                    is_key=col_data.get("is_key", False),
                    sample_values=col_data.get("sample_values", [])
                ))

            structure = StructureAnalysis(
                success=True,
                sheet_name=raw_data.sheet_name,
                total_rows=raw_data.total_rows,
                total_cols=raw_data.total_cols,
                title_rows=structure_data.get("title_rows", []),
                header_rows=structure_data.get("header_rows", [0]),
                data_start_row=structure_data.get("data_start_row", 1),
                data_end_row=structure_data.get("data_end_row"),
                columns=columns,
                table_type=structure_data.get("table_type", "general_table"),
                table_type_confidence=structure_data.get("table_type_confidence", 0.5),
                merged_cells_meaning=structure_data.get("merged_cells_meaning", {}),
                notes=structure_data.get("notes", []),
                method="llm"
            )

            # 解析推荐
            recommendations = []
            if include_recommendations:
                for rec_data in data.get("recommendations", []):
                    recommendations.append(AnalysisRecommendation(
                        analysis_type=rec_data.get("analysis_type", ""),
                        description=rec_data.get("description", ""),
                        priority=rec_data.get("priority", 5),
                        chart_types=rec_data.get("chart_types", []),
                        required_columns=rec_data.get("required_columns", [])
                    ))

            return FullAnalysisResult(
                structure=structure,
                recommendations=recommendations,
                insights=data.get("insights", []),
                warnings=data.get("warnings", [])
            )

        except Exception as e:
            logger.error(f"Failed to parse LLM response: {e}")
            return self._rule_based_analysis(raw_data, include_recommendations)

    def _rule_based_analysis(
        self,
        raw_data: RawSheetData,
        include_recommendations: bool
    ) -> FullAnalysisResult:
        """基于规则的分析（Fallback）"""

        # 使用统计信息推断结构
        stats = raw_data.stats
        potential_data_start = stats.get("potential_data_start_row", 1)

        # 分析前几行，找出表头
        header_rows = []
        title_rows = []
        columns = []

        if raw_data.rows:
            # 检查第一行是否是标题
            first_row = raw_data.rows[0]
            if first_row.non_empty_count <= 3 and raw_data.total_cols > 3:
                # 可能是标题行
                title_rows.append(0)
                if len(raw_data.rows) > 1:
                    header_rows.append(1)
            else:
                header_rows.append(0)

            # 提取列信息
            header_row_idx = header_rows[0] if header_rows else 0
            if header_row_idx < len(raw_data.rows):
                header_row = raw_data.rows[header_row_idx]
                for cell in header_row.cells:
                    name = str(cell.value) if cell.value else ""
                    data_type = self._infer_column_type(raw_data, cell.col_index)
                    role = self._infer_column_role(name, data_type)

                    columns.append(ColumnInfo(
                        col_letter=cell.col_letter,
                        col_index=cell.col_index,
                        name=name,
                        data_type=data_type,
                        meaning=name,
                        role=role,
                        is_key=(cell.col_index == 0)
                    ))

        # 推断表格类型
        table_type, confidence = self._infer_table_type(raw_data, columns)

        # 检查是否需要转置 (宽表处理)
        transpose_needed, is_wide_table = self._check_transpose_needed(raw_data)

        notes = ["使用规则推断（LLM不可用）"]
        if is_wide_table:
            notes.append(f"检测到宽表 ({raw_data.total_cols}列)，建议转置处理")

        structure = StructureAnalysis(
            success=True,
            sheet_name=raw_data.sheet_name,
            total_rows=raw_data.total_rows,
            total_cols=raw_data.total_cols,
            title_rows=title_rows,
            header_rows=header_rows if header_rows else [0],
            data_start_row=potential_data_start if potential_data_start else (max(header_rows) + 1 if header_rows else 1),  # noqa: E501
            data_end_row=None,
            columns=columns,
            table_type=table_type,
            table_type_confidence=confidence,
            notes=notes,
            method="rule_based",
            transpose=transpose_needed,
            is_wide_table=is_wide_table
        )

        # 生成基本推荐
        recommendations = []
        if include_recommendations:
            recommendations = self._generate_recommendations(columns, table_type)

        return FullAnalysisResult(
            structure=structure,
            recommendations=recommendations,
            insights=[],
            warnings=[]
        )

    def _infer_column_type(self, raw_data: RawSheetData, col_index: int) -> str:
        """推断列数据类型"""
        numeric_count = 0
        text_count = 0
        date_count = 0

        # 检查前10行数据
        for row in raw_data.rows[1:11]:
            if col_index < len(row.cells):
                cell = row.cells[col_index]
                if cell.value_type == "number":
                    numeric_count += 1
                elif cell.value_type == "date":
                    date_count += 1
                elif cell.value_type == "text":
                    text_count += 1

        total = numeric_count + text_count + date_count
        if total == 0:
            return "text"

        if numeric_count / total > 0.7:
            return "number"
        if date_count / total > 0.5:
            return "date"
        return "text"

    def _infer_column_role(self, name: str, data_type: str) -> str:
        """推断列角色"""
        name_lower = name.lower()

        # 时间相关
        if any(kw in name_lower for kw in ["日期", "时间", "月", "年", "date", "time", "period"]):
            return "time"

        # 度量相关
        if data_type == "number":
            if any(kw in name_lower for kw in ["金额", "收入", "成本", "利润", "预算", "实际", "数量", "率"]):
                return "measure"
            return "measure"

        # 维度相关
        if any(kw in name_lower for kw in ["名称", "类别", "部门", "产品", "客户", "区域"]):
            return "dimension"

        return "dimension" if data_type == "text" else "measure"

    def _infer_table_type(
        self,
        raw_data: RawSheetData,
        columns: List[ColumnInfo]
    ) -> tuple:
        """推断表格类型"""
        sheet_name = raw_data.sheet_name.lower()
        col_names = " ".join([c.name.lower() for c in columns])
        all_text = sheet_name + " " + col_names

        # 利润表
        if any(kw in all_text for kw in ["利润", "损益", "profit", "income"]):
            return "profit_statement", 0.8

        # 预算报表
        if any(kw in all_text for kw in ["预算", "budget", "计划", "实际"]):
            return "budget_report", 0.8

        # 销售明细
        if any(kw in all_text for kw in ["销售", "订单", "客户", "sales", "order"]):
            return "sales_detail", 0.7

        # 部门报表
        if any(kw in all_text for kw in ["部门", "团队", "department"]):
            return "department_report", 0.7

        # 成本分析
        if any(kw in all_text for kw in ["成本", "费用", "cost", "expense"]):
            return "cost_analysis", 0.7

        return "general_table", 0.5

    def _generate_recommendations(
        self,
        columns: List[ColumnInfo],
        table_type: str
    ) -> List[AnalysisRecommendation]:
        """生成分析推荐"""
        recommendations = []

        # 找出度量列和维度列
        measure_cols = [c for c in columns if c.role == "measure"]
        dimension_cols = [c for c in columns if c.role == "dimension"]
        time_cols = [c for c in columns if c.role == "time"]

        # 基于表格类型推荐
        if table_type == "budget_report":
            recommendations.append(AnalysisRecommendation(
                analysis_type="budget_vs_actual",
                description="预算实际对比分析",
                priority=1,
                chart_types=["bar_comparison", "waterfall"],
                required_columns=[c.name for c in measure_cols[:2]]
            ))

        elif table_type == "profit_statement":
            recommendations.append(AnalysisRecommendation(
                analysis_type="profit_trend",
                description="利润趋势分析",
                priority=1,
                chart_types=["line", "bar"],
                required_columns=[c.name for c in measure_cols[:3]]
            ))

        elif table_type == "sales_detail":
            recommendations.append(AnalysisRecommendation(
                analysis_type="sales_ranking",
                description="销售排名分析",
                priority=1,
                chart_types=["bar_horizontal", "pie"],
                required_columns=[c.name for c in dimension_cols[:1]] + [c.name for c in measure_cols[:1]]
            ))

        # 通用推荐
        if dimension_cols and measure_cols:
            recommendations.append(AnalysisRecommendation(
                analysis_type="comparison",
                description="对比分析",
                priority=2,
                chart_types=["bar", "pie"],
                required_columns=[dimension_cols[0].name, measure_cols[0].name]
            ))

        if time_cols and measure_cols:
            recommendations.append(AnalysisRecommendation(
                analysis_type="trend",
                description="趋势分析",
                priority=2,
                chart_types=["line", "area"],
                required_columns=[time_cols[0].name, measure_cols[0].name]
            ))

        return recommendations

    def _check_transpose_needed(self, raw_data: RawSheetData) -> tuple:
        """
        检查是否需要转置宽表

        使用 TableClassifier 判断：
        - 列数远大于行数
        - 列标题包含时间序列模式

        Returns:
            (transpose_needed, is_wide_table)
        """
        rows = raw_data.total_rows
        cols = raw_data.total_cols

        # 宽表判断：列数 > 30 且 列数 > 行数 * 2
        is_wide_table = cols > 30 and cols > rows * 2

        if not is_wide_table:
            return False, False

        # 使用 TableClassifier 进行更精确的分类
        try:
            # 构建简单的 DataFrame 用于分类
            if raw_data.rows:
                header_row = raw_data.rows[0] if raw_data.rows else None
                if header_row:
                    headers = [
                        str(c.value) if c.value else f"Col{c.col_index}"
                        for c in header_row.cells
                    ]

                    # 创建简单 DataFrame
                    df_data = []
                    for row in raw_data.rows[1:min(20, len(raw_data.rows))]:
                        row_dict = {}
                        for i, cell in enumerate(row.cells):
                            col_name = headers[i] if i < len(headers) else f"Col{i}"
                            row_dict[col_name] = cell.value
                        df_data.append(row_dict)

                    if df_data:
                        df = pd.DataFrame(df_data)
                        classifier = TableClassifier()
                        result = classifier.classify(raw_data.sheet_name, df)

                        # 如果检测到时间序列或需要转置
                        transpose_needed = result.transpose
                        logger.info(f"TableClassifier: type={result.table_type.value}, transpose={transpose_needed}")
                        return transpose_needed, is_wide_table

        except Exception as e:
            logger.warning(f"TableClassifier failed: {e}")

        # 默认：宽表通常需要转置以便分析
        return is_wide_table, is_wide_table

    def get_cache_stats(self) -> Dict[str, Any]:
        """Get structure analysis cache statistics."""
        return _structure_cache.get_stats()

    def clear_cache(self):
        """Clear the structure analysis cache."""
        _structure_cache.clear()

    async def close(self):
        """No-op: shared client lifecycle managed by main.py lifespan"""


# ============================================================
# 便捷函数
# ============================================================

async def analyze_excel_structure(
    file_bytes: bytes,
    sheet_index: int = 0,
    include_recommendations: bool = True
) -> FullAnalysisResult:
    """
    分析Excel文件结构

    Args:
        file_bytes: Excel文件字节
        sheet_index: Sheet索引
        include_recommendations: 是否包含分析推荐

    Returns:
        FullAnalysisResult
    """
    analyzer = LLMStructureAnalyzer()
    try:
        return await analyzer.analyze_from_bytes(file_bytes, sheet_index, include_recommendations)
    finally:
        await analyzer.close()
