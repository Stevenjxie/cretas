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

import json
import logging
from dataclasses import dataclass, field
from typing import Any, Dict, List, Optional

import httpx

from config import get_settings
from services.raw_exporter import RawExporter, RawSheetData

logger = logging.getLogger(__name__)


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


class LLMStructureAnalyzer:
    """
    使用LLM分析Excel结构

    输入: RawExporter导出的原始数据
    输出: 结构分析结果 + 分析推荐
    """

    def __init__(self):
        self.settings = get_settings()
        self.raw_exporter = RawExporter()

    @property
    def client(self) -> httpx.AsyncClient:
        from common.llm_client import get_llm_http_client
        return get_llm_http_client()

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
                return self._rule_based_analysis(raw_data, include_recommendations)

            response = await self._call_llm(prompt)
            result = self._parse_response(response, raw_data, include_recommendations)
            return result

        except Exception as e:
            logger.error(f"LLM analysis failed: {e}")
            return self._rule_based_analysis(raw_data, include_recommendations)

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

    async def _call_llm(self, prompt: str) -> str:
        """调用 LLM (多 provider 路由 SLOT.MAPPER — Excel 结构分析).

        走 call_chain 免费链 + 熔断 + 免费 fallback; model 由 slot 注入
        (替代原 llm_mapper_model 直连)."""
        from common.llm_router import call_chain, SLOT
        from common.llm_metrics import llm_caller_context

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
            "max_tokens": 4000,
            "enable_thinking": False
        }

        with llm_caller_context("llm_structure_analyzer"):
            result = await call_chain(SLOT.MAPPER, payload)

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
            notes=["使用规则推断（LLM不可用）"],
            method="rule_based"
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
