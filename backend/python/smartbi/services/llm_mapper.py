from __future__ import annotations
"""
LLM Field Mapper Service

Uses LLM to intelligently map detected fields to business metrics
and recommend chart configurations.
"""
import logging
import json
from typing import Any, Optional, List, Dict

import httpx

from config import get_settings

logger = logging.getLogger(__name__)


class LLMMapper:
    """LLM-based field mapping and chart recommendation service"""

    # Standard business fields for mapping
    STANDARD_FIELDS = {
        "time_dimensions": [
            {"id": "date", "name": "日期", "description": "日期维度"},
            {"id": "month", "name": "月份", "description": "月份维度"},
            {"id": "quarter", "name": "季度", "description": "季度维度"},
            {"id": "year", "name": "年份", "description": "年份维度"}
        ],
        "category_dimensions": [
            {"id": "product", "name": "产品", "description": "产品维度"},
            {"id": "category", "name": "品类", "description": "产品品类"},
            {"id": "region", "name": "区域", "description": "地区维度"},
            {"id": "channel", "name": "渠道", "description": "销售渠道"},
            {"id": "customer", "name": "客户", "description": "客户维度"},
            {"id": "department", "name": "部门", "description": "部门维度"}
        ],
        "measures": [
            {"id": "sales_amount", "name": "销售额", "description": "销售金额"},
            {"id": "order_count", "name": "订单数", "description": "订单数量"},
            {"id": "quantity", "name": "销量", "description": "销售数量"},
            {"id": "cost", "name": "成本", "description": "成本金额"},
            {"id": "profit", "name": "利润", "description": "利润金额"},
            {"id": "target", "name": "目标", "description": "目标值"},
            {"id": "budget", "name": "预算", "description": "预算金额"},
            {"id": "actual", "name": "实际", "description": "实际值"}
        ]
    }

    CHART_TYPES = [
        {"id": "line", "name": "折线图", "use_case": "趋势分析、时间序列"},
        {"id": "bar", "name": "柱状图", "use_case": "对比分析、排名"},
        {"id": "pie", "name": "饼图", "use_case": "占比分析"},
        {"id": "area", "name": "面积图", "use_case": "累计趋势"},
        {"id": "scatter", "name": "散点图", "use_case": "相关性分析"},
        {"id": "heatmap", "name": "热力图", "use_case": "分布分析"},
        {"id": "waterfall", "name": "瀑布图", "use_case": "增减分析"},
        {"id": "radar", "name": "雷达图", "use_case": "多维对比"},
        {"id": "funnel", "name": "漏斗图", "use_case": "转化分析"},
        {"id": "gauge", "name": "仪表盘", "use_case": "KPI展示"}
    ]

    def __init__(self):
        self.settings = get_settings()

    @property
    def client(self) -> httpx.AsyncClient:
        from common.llm_client import get_llm_http_client
        return get_llm_http_client()

    async def analyze_sheets(
        self,
        sheets_info: List[dict]
    ) -> Dict[str, Any]:
        """
        Use LLM to analyze which sheets contain valid analyzable data

        Args:
            sheets_info: List of sheet information with name, rowCount, columnCount, previewHeaders

        Returns:
            Analysis results with recommended sheets and data types
        """
        if not self.settings.llm_api_key:
            return self._rule_based_sheet_analysis(sheets_info)

        prompt = self._build_sheet_analysis_prompt(sheets_info)

        try:
            response = await self._call_llm(prompt)
            return self._parse_sheet_analysis_response(response, sheets_info)
        except Exception as e:
            logger.error(f"LLM sheet analysis failed, using rule-based: {e}")
            return self._rule_based_sheet_analysis(sheets_info)

    def _build_sheet_analysis_prompt(self, sheets_info: List[dict]) -> str:
        """Build prompt for sheet analysis"""
        sheets_desc = "\n".join([
            f"Sheet {s.get('index')}: {s.get('name')}\n"
            f"  - 行数: {s.get('rowCount')}, 列数: {s.get('columnCount')}\n"
            f"  - 表头预览: {s.get('previewHeaders', [])[:5]}"
            for s in sheets_info
        ])

        prompt = f"""请分析以下Excel工作表，理解每个Sheet的作用和数据内容。

重要原则：
1. **所有有数据的表都应该分析** - 只要有数据，就值得分析
2. **文字说明表同样重要** - 如果某个Sheet主要是文字说明（如"索引"、"说明"等），它通常是帮助理解其他数据表的重要上下文，应该提取其中的业务规则和计算说明
3. **数据表需要识别类型** - 对于包含数值数据的表，识别其数据类型（财务/销售/部门等）

工作表列表：
{sheets_desc}

请分析每个Sheet的用途：
1. **说明类** (documentation): 包含业务规则、计算方法、数据来源说明的文字表格 - 虽然不直接分析，但其内容对理解其他表格至关重要
2. **汇总类** (summary): 包含汇总数据、KPI、关键指标的表格 - 需要分析
3. **明细类** (detail): 包含详细业务数据的表格 - 需要分析
4. **配置类** (config): 包含配置信息、映射关系的表格 - 用于数据转换

请返回JSON格式：
{{
    "sheets": [
        {{
            "index": 0,
            "name": "Sheet名称",
            "category": "documentation/summary/detail/config",
            "dataType": "sales/finance/department/inventory/rebate/unknown",
            "analyzable": true,
            "hasNumericData": true/false,
            "isDocumentation": true/false,
            "priority": 1-5,
            "reason": "该Sheet的作用说明",
            "businessContext": "从该Sheet可以获得的业务上下文（如果是说明类）"
        }}
    ],
    "documentationSheets": [0],
    "dataSheets": [1, 2, 3],
    "summary": "整个Excel的结构说明和各Sheet关系"
}}"""
        return prompt

    def _parse_sheet_analysis_response(
        self,
        response: str,
        sheets_info: List[dict]
    ) -> Dict[str, Any]:
        """Parse LLM response for sheet analysis"""
        try:
            # Extract JSON from response
            json_start = response.find('{')
            json_end = response.rfind('}') + 1
            if json_start >= 0 and json_end > json_start:
                json_str = response[json_start:json_end]
                result = json.loads(json_str)
                result["success"] = True
                result["method"] = "llm"
                return result
        except Exception as e:
            logger.error(f"Failed to parse LLM sheet analysis response: {e}")

        return self._rule_based_sheet_analysis(sheets_info)

    def _rule_based_sheet_analysis(self, sheets_info: List[dict]) -> Dict[str, Any]:
        """Rule-based sheet analysis fallback - all sheets with data are analyzable"""
        results = []
        data_sheets = []
        documentation_sheets = []

        # Keywords for classification
        doc_keywords = ["索引", "目录", "说明", "index", "toc", "readme", "guide"]
        summary_keywords = ["汇总", "简表", "总表", "概览", "summary", "overview"]
        finance_keywords = ["利润", "成本", "费用", "预算", "财务", "应收", "应付", "税"]
        sales_keywords = ["销售", "订单", "客户", "产品", "收入", "revenue", "sales"]
        department_keywords = ["部门", "分部", "区域", "省区", "团队", "中心"]
        rebate_keywords = ["返利", "返点", "折扣", "rebate", "discount"]
        detail_keywords = ["明细", "详细", "detail", "list"]

        for sheet in sheets_info:
            name = sheet.get("name", "")
            name_lower = name.lower()
            row_count = sheet.get("rowCount", 0)
            col_count = sheet.get("columnCount", 0)
            headers = sheet.get("previewHeaders", [])
            all_text = name + " " + " ".join(str(h) for h in headers)

            # All sheets with data are analyzable
            has_data = row_count > 0 and col_count > 0
            has_numeric_data = row_count > 5 and col_count >= 3  # Likely has numeric data
            is_documentation = any(kw in name_lower for kw in doc_keywords) or (col_count <= 3 and row_count < 50)

            # Determine category
            if is_documentation and not has_numeric_data:
                category = "documentation"
                priority = 3
                business_context = "包含业务规则说明，帮助理解其他数据表"
            elif any(kw in name_lower for kw in summary_keywords):
                category = "summary"
                priority = 2
                business_context = "汇总数据表"
            elif any(kw in name_lower for kw in detail_keywords) or has_numeric_data:
                category = "detail"
                priority = 1
                business_context = "详细业务数据"
            else:
                category = "detail" if has_data else "other"
                priority = 2 if has_data else 5
                business_context = "业务数据表" if has_data else "空表或无效数据"

            # Detect data type
            if any(kw in all_text for kw in rebate_keywords):
                data_type = "rebate"
            elif any(kw in all_text for kw in finance_keywords):
                data_type = "finance"
            elif any(kw in all_text for kw in sales_keywords):
                data_type = "sales"
            elif any(kw in all_text for kw in department_keywords):
                data_type = "department"
            else:
                data_type = "unknown"

            result = {
                "index": sheet.get("index"),
                "name": name,
                "category": category,
                "dataType": data_type,
                "analyzable": has_data,  # All sheets with data are analyzable
                "hasNumericData": has_numeric_data,
                "isDocumentation": is_documentation,
                "priority": priority,
                "reason": f"行数={row_count}, 列数={col_count}",
                "businessContext": business_context
            }
            results.append(result)

            if is_documentation:
                documentation_sheets.append(sheet.get("index"))
            if has_numeric_data:
                data_sheets.append(sheet.get("index"))

        return {
            "success": True,
            "sheets": results,
            "documentationSheets": documentation_sheets,
            "dataSheets": data_sheets,
            "recommended": data_sheets,  # All data sheets are recommended
            "summary": f"共{len(sheets_info)}个Sheet: {len(documentation_sheets)}个说明表, {len(data_sheets)}个数据表",
            "method": "rule_based"
        }

    async def map_fields(
        self,
        detected_fields: List[dict],
        context: Optional[str] = None
    ) -> Dict[str, Any]:
        """
        Use LLM to map detected fields to standard business fields

        Args:
            detected_fields: List of detected field information
            context: Optional business context

        Returns:
            Field mapping results
        """
        if not self.settings.llm_api_key:
            # No LLM available — rules only.
            return self._rule_based_mapping(detected_fields)

        # ── Rules-first (规则引擎必须准确): 规则先跑; **只信 exact-alias(0.85, 整名匹配 curated
        # 字典)**。语义猜测(date 0.8 / amount,quantity,category 0.7 / substring 0.65)都不可靠
        # (真实数据深核对发现 date 检测会把"焯水使用重量"误判成 date), 一律 fallback 给 LLM。
        # 全部命中 exact-alias → 跳过 LLM。这样规则信任的每条都是人工核对过的精确别名, 0 误判。──
        rule_result = self._rule_based_mapping(detected_fields)
        CONFIDENT = 0.85
        confident_maps = [
            m for m in rule_result.get("mappings", []) if (m.get("confidence") or 0) >= CONFIDENT
        ]
        confident_sources = {m["sourceField"] for m in confident_maps}
        uncertain_fields = [
            f for f in detected_fields if f.get("fieldName") not in confident_sources
        ]

        if not uncertain_fields:
            logger.info(
                "[map_fields] rules-first: 全部 %d 字段规则高置信映射, 跳过 LLM", len(detected_fields)
            )
            return {"success": True, "mappings": confident_maps, "unmapped": [], "method": "rules"}

        # 只把不确定的字段交给 LLM (更小 prompt = 更少 token); 高置信字段保留规则结果.
        logger.info(
            "[map_fields] rules-first: %d/%d 字段规则高置信, %d 个 → LLM",
            len(confident_sources), len(detected_fields), len(uncertain_fields),
        )
        # P0 出境脱敏: 注册敏感字段样本值真名 → 出境占位 (输出是字段映射元数据, 无需还原).
        from common.llm_redactor import (
            register_values_for_egress, extract_sensitive_values_from_fields,
        )
        register_values_for_egress(extract_sensitive_values_from_fields(uncertain_fields))

        prompt = self._build_mapping_prompt(uncertain_fields, context)

        try:
            response = await self._call_llm(prompt)
            llm_result = self._parse_mapping_response(response, uncertain_fields)
            return {
                "success": True,
                "mappings": confident_maps + (llm_result.get("mappings") or []),
                "unmapped": llm_result.get("unmapped") or [],
                "method": "rules+llm",
            }
        except Exception as e:
            logger.error("LLM mapping failed for residual, using rule-based for all: %s", e)
            return self._rule_based_mapping(detected_fields)

    async def recommend_chart_config(
        self,
        detected_fields: List[dict],
        analysis_goal: Optional[str] = None
    ) -> Dict[str, Any]:
        """
        Use LLM to recommend chart configuration

        Args:
            detected_fields: List of detected field information
            analysis_goal: Optional analysis goal description

        Returns:
            Chart configuration recommendation
        """
        if not self.settings.llm_api_key:
            return self._rule_based_chart_recommendation(detected_fields)

        # P0 出境脱敏: 注册敏感字段样本值真名 → 出境占位 (输出是图表配置元数据, 无需还原).
        from common.llm_redactor import (
            register_values_for_egress, extract_sensitive_values_from_fields,
        )
        register_values_for_egress(extract_sensitive_values_from_fields(detected_fields))

        prompt = self._build_chart_prompt(detected_fields, analysis_goal)

        try:
            response = await self._call_llm(prompt)
            return self._parse_chart_response(response, detected_fields)
        except Exception as e:
            logger.error(f"LLM chart recommendation failed: {e}")
            return self._rule_based_chart_recommendation(detected_fields)

    async def _call_llm(self, prompt: str) -> str:
        """Call LLM via multi-provider router (SLOT.MAPPER — field mapping /
        chart-config recommendation). Shares the free-first chain +
        circuit-breaker + free-tier fallback; the model is injected per-slot
        by call_chain (replaces the prior llm_mapper_model direct call)."""
        from common.llm_router import call_chain, SLOT
        from common.llm_metrics import llm_caller_context

        payload = {
            "messages": [
                {
                    "role": "system",
                    "content": "你是一个专业的数据分析师，擅长识别数据字段的业务含义并推荐可视化方案。请用JSON格式回复。"
                },
                {
                    "role": "user",
                    "content": prompt
                }
            ],
            "temperature": 0.3,
            "max_tokens": 2000,
            "enable_thinking": False
        }

        with llm_caller_context("llm_mapper"):
            result = await call_chain(SLOT.MAPPER, payload)

        return result["choices"][0]["message"]["content"]

    def _build_mapping_prompt(self, detected_fields: List[dict], context: Optional[str]) -> str:
        """Build prompt for field mapping"""
        fields_desc = "\n".join([
            f"- {f['fieldName']}: 类型={f['dataType']}, 语义={f['semanticType']}, 样本值={f.get('sampleValues', [])[:3]}"
            for f in detected_fields
        ])

        standard_fields_desc = json.dumps(self.STANDARD_FIELDS, ensure_ascii=False, indent=2)

        # Detect if this is wide-format data with time columns
        field_names = [f['fieldName'] for f in detected_fields]
        is_wide_format = self._detect_wide_format(field_names)

        wide_format_instruction = ""
        if is_wide_format:
            wide_format_instruction = """
【重要】这是宽格式数据（多个时间列），请遵循以下规则：

1. **保留时间维度**: 当列名包含月份信息（如"1月_预算收入"、"2月_实际金额"）时，必须在targetField中保留时间信息
   - 格式: {metric_type}_{YYYYMM}
   - 例如: "1月_预算收入" → "budget_202501"
   - 例如: "2月_实际收入" → "actual_202502"
   - 例如: "12月_利润" → "profit_202512"

2. **识别年份**:
   - 如果列名中有年份（如"2025年"、"25年"），使用该年份
   - 如果没有明确年份，默认使用2025年

3. **metric_type映射**:
   - 预算、计划、目标 → budget
   - 实际、完成、执行 → actual
   - 同期、去年、24年 → yoy_prior
   - 利润、净利 → profit
   - 收入、营收 → revenue
   - 成本 → cost

4. **年度汇总列**: 如果列名是"年度汇总"、"全年"、"累计"，映射为 annual_total_2025
"""

        prompt = f"""请分析以下数据字段，并将它们映射到标准业务字段。

检测到的字段：
{fields_desc}

标准业务字段：
{standard_fields_desc}

{f'业务背景：{context}' if context else ''}
{wide_format_instruction}

请返回JSON格式的映射结果：
{{
    "mappings": [
        {{
            "sourceField": "原始字段名",
            "targetField": "标准字段id（宽格式数据请带时间后缀如budget_202501）",
            "targetCategory": "time_dimensions/category_dimensions/measures",
            "confidence": 0.9,
            "reason": "映射原因"
        }}
    ],
    "unmapped": ["无法映射的字段列表"]
}}"""
        return prompt

    def _detect_wide_format(self, field_names: List[str]) -> bool:
        """
        Detect if the data is wide-format (multiple time-period columns).

        Wide-format indicators:
        - Multiple columns containing month patterns (1月, 2月, ..., 12月)
        - Multiple columns containing value_YYYYMM patterns
        - Multiple columns containing year-month patterns
        """
        import re

        # Pattern 1: Chinese month format (1月, 2月, ... 12月)
        chinese_month_pattern = re.compile(r'[_]?(1[0-2]|[1-9])月[_]?')

        # Pattern 2: English/numeric month format (value_202501, amount_202502)
        numeric_month_pattern = re.compile(r'(value|amount|profit|budget|actual|revenue|cost)_\d{6}')

        # Pattern 3: Year-month pattern (2025-01, 2025/01)
        year_month_pattern = re.compile(r'\d{4}[-/]\d{2}')

        chinese_month_count = sum(1 for name in field_names if chinese_month_pattern.search(name))
        numeric_month_count = sum(1 for name in field_names if numeric_month_pattern.search(name))
        year_month_count = sum(1 for name in field_names if year_month_pattern.search(name))

        # If 3+ columns match any pattern, it's wide-format
        return chinese_month_count >= 3 or numeric_month_count >= 3 or year_month_count >= 3

    def _build_chart_prompt(self, detected_fields: List[dict], analysis_goal: Optional[str]) -> str:
        """Build prompt for chart recommendation"""
        fields_desc = "\n".join([
            f"- {f['fieldName']}: 角色={f['chartRole']}, 类型={f['dataType']}"
            for f in detected_fields
        ])

        chart_types_desc = json.dumps(self.CHART_TYPES, ensure_ascii=False, indent=2)

        prompt = f"""请根据以下数据字段推荐最佳的图表配置。

数据字段：
{fields_desc}

可用图表类型：
{chart_types_desc}

{f'分析目标：{analysis_goal}' if analysis_goal else ''}

请返回JSON格式的推荐结果：
{{
    "recommendations": [
        {{
            "chartType": "图表类型id",
            "title": "推荐标题",
            "xAxis": "X轴字段名",
            "yAxis": ["Y轴字段名列表"],
            "series": "系列分组字段（可选）",
            "reason": "推荐原因",
            "priority": 1
        }}
    ]
}}"""
        return prompt

    def _parse_mapping_response(self, response: str, detected_fields: List[dict]) -> dict:
        """Parse LLM mapping response"""
        try:
            # Extract JSON from response
            json_match = response
            if "```json" in response:
                json_match = response.split("```json")[1].split("```")[0]
            elif "```" in response:
                json_match = response.split("```")[1].split("```")[0]

            result = json.loads(json_match)
            return {
                "success": True,
                "mappings": result.get("mappings", []),
                "unmapped": result.get("unmapped", []),
                "method": "llm"
            }
        except Exception as e:
            logger.error(f"Failed to parse LLM response: {e}")
            return self._rule_based_mapping(detected_fields)

    def _parse_chart_response(self, response: str, detected_fields: List[dict]) -> dict:
        """Parse LLM chart recommendation response"""
        try:
            json_match = response
            if "```json" in response:
                json_match = response.split("```json")[1].split("```")[0]
            elif "```" in response:
                json_match = response.split("```")[1].split("```")[0]

            result = json.loads(json_match)
            return {
                "success": True,
                "recommendations": result.get("recommendations", []),
                "method": "llm"
            }
        except Exception as e:
            logger.error(f"Failed to parse chart response: {e}")
            return self._rule_based_chart_recommendation(detected_fields)

    # POS field alias mapping table
    # Maps Chinese POS field names (from various POS systems like 客如云, 哗啦啦,
    # 银豹, 美团收银, 二维火) to standard field IDs.
    # Organized as: { "alias_keyword": ("target_field", "target_category") }
    POS_FIELD_ALIASES = {
        # revenue — 实收/实付/到手金额
        "实收金额": ("revenue", "measures"),
        "实付金额": ("revenue", "measures"),
        "到手金额": ("revenue", "measures"),
        "实际收入": ("revenue", "measures"),
        "实收": ("revenue", "measures"),
        "实付": ("revenue", "measures"),
        "净收入": ("revenue", "measures"),
        "实收合计": ("revenue", "measures"),
        # discounted_amount — 折后/优惠后金额
        "折后金额": ("discounted_amount", "measures"),
        "优惠后金额": ("discounted_amount", "measures"),
        "折扣后": ("discounted_amount", "measures"),
        "折后价": ("discounted_amount", "measures"),
        "优惠后价格": ("discounted_amount", "measures"),
        "平台优惠后": ("discounted_amount", "measures"),
        # unit_price — 原价/标价/零售价/门市价
        "原价": ("unit_price", "measures"),
        "标价": ("unit_price", "measures"),
        "零售价": ("unit_price", "measures"),
        "门市价": ("unit_price", "measures"),
        "菜品原价": ("unit_price", "measures"),
        "标准价": ("unit_price", "measures"),
        "挂牌价": ("unit_price", "measures"),
        "菜品单价": ("unit_price", "measures"),
        # quantity_sold — 销售数量/售出份数/点单数
        "单卖数量": ("quantity_sold", "measures"),
        "售出份数": ("quantity_sold", "measures"),
        "销售数量": ("quantity_sold", "measures"),
        "点单数": ("quantity_sold", "measures"),
        "销售份数": ("quantity_sold", "measures"),
        "出品数": ("quantity_sold", "measures"),
        "售出数量": ("quantity_sold", "measures"),
        "点单份数": ("quantity_sold", "measures"),
        "销量": ("quantity_sold", "measures"),
        # refund_amount — 退款/退单金额
        "退款金额": ("refund_amount", "measures"),
        "退单金额": ("refund_amount", "measures"),
        "退款额": ("refund_amount", "measures"),
        "退货金额": ("refund_amount", "measures"),
        "退菜金额": ("refund_amount", "measures"),
        # refund_count — 退款/退单笔数
        "退款笔数": ("refund_count", "measures"),
        "退单数": ("refund_count", "measures"),
        "退款单数": ("refund_count", "measures"),
        "退货笔数": ("refund_count", "measures"),
        "退菜笔数": ("refund_count", "measures"),
        "退菜数": ("refund_count", "measures"),
        # table_number — 桌号/台号/餐台
        "桌号": ("table_number", "category_dimensions"),
        "台号": ("table_number", "category_dimensions"),
        "餐台": ("table_number", "category_dimensions"),
        "桌台号": ("table_number", "category_dimensions"),
        "餐桌号": ("table_number", "category_dimensions"),
        "台位号": ("table_number", "category_dimensions"),
        # diner_count — 就餐/用餐人数
        "就餐人数": ("diner_count", "measures"),
        "用餐人数": ("diner_count", "measures"),
        "客人数": ("diner_count", "measures"),
        "就餐人次": ("diner_count", "measures"),
        # delivery_order_id — 外卖单号
        "外卖单号": ("delivery_order_id", "category_dimensions"),
        "外卖订单": ("delivery_order_id", "category_dimensions"),
        "外卖订单号": ("delivery_order_id", "category_dimensions"),
        "配送单号": ("delivery_order_id", "category_dimensions"),
        # payment_method — 支付/付款/收款方式
        "支付方式": ("payment_method", "category_dimensions"),
        "付款方式": ("payment_method", "category_dimensions"),
        "收款方式": ("payment_method", "category_dimensions"),
        "支付渠道": ("payment_method", "category_dimensions"),
        "付款渠道": ("payment_method", "category_dimensions"),
        "结算方式": ("payment_method", "category_dimensions"),
        # member_id — 会员卡号/会员编号
        "会员卡号": ("member_id", "category_dimensions"),
        "会员编号": ("member_id", "category_dimensions"),
        "会员号": ("member_id", "category_dimensions"),
        # order_time — 下单/点单时间
        "下单时间": ("order_time", "time_dimensions"),
        "点单时间": ("order_time", "time_dimensions"),
        "开单时间": ("order_time", "time_dimensions"),
        "点餐时间": ("order_time", "time_dimensions"),
        # serve_time — 出餐/制作完成时间
        "出餐时间": ("serve_time", "time_dimensions"),
        "制作完成时间": ("serve_time", "time_dimensions"),
        "上菜时间": ("serve_time", "time_dimensions"),
        "完成时间": ("serve_time", "time_dimensions"),
        "出品时间": ("serve_time", "time_dimensions"),
        # POS-specific sales/order fields
        "营业额": ("sales_amount", "measures"),
        "营业收入": ("sales_amount", "measures"),
        "总营业额": ("sales_amount", "measures"),
        "账单金额": ("sales_amount", "measures"),
        "销售金额": ("sales_amount", "measures"),
        "交易金额": ("sales_amount", "measures"),
        "流水金额": ("sales_amount", "measures"),
        "总金额": ("sales_amount", "measures"),
        "订单笔数": ("order_count", "measures"),
        "交易笔数": ("order_count", "measures"),
        "订单量": ("order_count", "measures"),
        "单量": ("order_count", "measures"),
        "开单数": ("order_count", "measures"),
        "单数": ("order_count", "measures"),
        "优惠金额": ("discount_amount", "measures"),
        "折扣金额": ("discount_amount", "measures"),
        # POS product/category fields
        "菜品名称": ("product", "category_dimensions"),
        "商品名称": ("product", "category_dimensions"),
        "菜品": ("product", "category_dimensions"),
        "菜名": ("product", "category_dimensions"),
        "菜品名": ("product", "category_dimensions"),
        "商品名": ("product", "category_dimensions"),
        "商品": ("product", "category_dimensions"),
        "菜品分类": ("category", "category_dimensions"),
        "商品分类": ("category", "category_dimensions"),
        "菜品大类": ("category", "category_dimensions"),
        "菜品类别": ("category", "category_dimensions"),
        "品类": ("category", "category_dimensions"),
        # ── 真实客户数据扩充 (2026-05-31: 大众点评餐饮 + 六扇门卤味工厂) ──
        # 门店/店铺 → store (原字典无 store 目标, 但餐饮每张表都有门店名称)
        "门店名称": ("store", "category_dimensions"),
        "店铺名称": ("store", "category_dimensions"),
        "评价门店": ("store", "category_dimensions"),
        "门店": ("store", "category_dimensions"),
        # 销售单价 → unit_price; 分摊优惠 → discount_amount; 实退金额 → refund_amount
        "销售单价": ("unit_price", "measures"),
        "分摊优惠": ("discount_amount", "measures"),
        "实退金额": ("refund_amount", "measures"),
        "汇总实际收入": ("revenue", "measures"),
        "数量(含套餐子商品)": ("quantity_sold", "measures"),
        "退货数量": ("returned_quantity", "measures"),
        # 维度: 点单方式(堂食/外卖/团购) / 商品编码 / 规格
        "点单方式": ("order_channel", "category_dimensions"),
        "商品编码": ("product_code", "category_dimensions"),
        "规格": ("spec", "category_dimensions"),
        # 采购/原料 (采购入库报表)
        "供应商": ("supplier", "category_dimensions"),
        "供应商名称": ("supplier", "category_dimensions"),
        "原料名称": ("material", "category_dimensions"),
        "原料分类": ("material_category", "category_dimensions"),
        "入库仓库": ("warehouse", "category_dimensions"),
        "入库数量": ("inbound_quantity", "measures"),
        "原料批次": ("batch", "category_dimensions"),
        "原料批次号": ("batch", "category_dimensions"),
        "批次号": ("batch", "category_dimensions"),
        "订单批次": ("batch", "category_dimensions"),
        # ── 工厂/生产 (六扇门卤味加工: 出成率/投料/产出/人工) ──
        "出成率": ("yield_rate", "measures"),
        "成品总出成率": ("yield_rate", "measures"),
        "投料重量": ("input_weight", "measures"),
        "投入重量": ("input_weight", "measures"),
        "产出重量": ("output_quantity", "measures"),
        "产出数量": ("output_quantity", "measures"),
        "成品重量": ("output_quantity", "measures"),
        "仓库出库重量": ("outbound_weight", "measures"),
        "入库数量/盒": ("inbound_quantity", "measures"),
        "实际生产数量": ("output_quantity", "measures"),
        "人工（分）": ("labor_minutes", "measures"),
        "人工(分)": ("labor_minutes", "measures"),
        "人工（时）": ("labor_hours", "measures"),
        "人工(时)": ("labor_hours", "measures"),
    }

    @staticmethod
    def _normalize_field_name(name: str) -> str:
        """去掉列名的"噪声"后缀(单位 /kg /盒、套餐限定),让 '投入重量/kg'、
        '单卖数量(不含套餐子商品)' 命中基础别名。保留 '（分）/（时）' 等有意义的限定。"""
        import re as _re
        s = (name or "").strip()
        for token in ("(不含套餐子商品)", "（不含套餐子商品）", "(含套餐子商品)", "（含套餐子商品）"):
            s = s.replace(token, "")
        s = _re.sub(r"[/／](kg|g|t|吨|盒|个|份|件|台|箱|包|袋|条|只|斤|克)$", "", s)
        s = _re.sub(r"[(（](元|%|千克|公斤|kg|个|份)[)）]$", "", s)
        return s.strip()

    def _rule_based_mapping(self, detected_fields: List[dict]) -> dict:
        """Fallback rule-based field mapping with POS field alias support"""
        mappings = []
        unmapped = []

        for field in detected_fields:
            mapped = False
            field_name = field["fieldName"]
            field_name_lower = field_name.lower()
            semantic_type = field.get("semanticType", "unknown")

            # --- Phase 1: Exact POS alias match (highest priority) ---
            # Check the raw field name against known POS field aliases.
            # Strip whitespace for fuzzy matching but try exact first.
            stripped_name = field_name.strip()
            # 精确匹配: 先原名, 再规范化名 (去单位/套餐噪声后) —— 都算 exact(0.85, 高置信)
            alias_key = None
            if stripped_name in self.POS_FIELD_ALIASES:
                alias_key = stripped_name
            else:
                norm = self._normalize_field_name(stripped_name)
                if norm and norm != stripped_name and norm in self.POS_FIELD_ALIASES:
                    alias_key = norm
            if alias_key:
                target, category = self.POS_FIELD_ALIASES[alias_key]
                mappings.append({
                    "sourceField": field_name,
                    "targetField": target,
                    "targetCategory": category,
                    "confidence": 0.85,
                    "reason": f"POS field alias exact match: {alias_key} → {target}"
                })
                mapped = True

            # --- Phase 2: Semantic type based mapping (original logic) ---
            if not mapped:
                if semantic_type == "date":
                    mappings.append({
                        "sourceField": field_name,
                        "targetField": "date",
                        "targetCategory": "time_dimensions",
                        "confidence": 0.8,
                        "reason": "Detected as date field"
                    })
                    mapped = True
                elif semantic_type == "amount":
                    target = "sales_amount"
                    if "cost" in field_name_lower or "成本" in field_name_lower:
                        target = "cost"
                    elif "profit" in field_name_lower or "利润" in field_name_lower:
                        target = "profit"
                    elif "target" in field_name_lower or "目标" in field_name_lower:
                        target = "target"
                    elif "budget" in field_name_lower or "预算" in field_name_lower:
                        target = "budget"

                    mappings.append({
                        "sourceField": field_name,
                        "targetField": target,
                        "targetCategory": "measures",
                        "confidence": 0.7,
                        "reason": f"Detected as amount field, mapped to {target}"
                    })
                    mapped = True
                elif semantic_type == "quantity":
                    mappings.append({
                        "sourceField": field_name,
                        "targetField": "quantity",
                        "targetCategory": "measures",
                        "confidence": 0.7,
                        "reason": "Detected as quantity field"
                    })
                    mapped = True
                elif semantic_type in ["category", "product", "geography"]:
                    target = "category"
                    if semantic_type == "product":
                        target = "product"
                    elif semantic_type == "geography":
                        target = "region"

                    mappings.append({
                        "sourceField": field_name,
                        "targetField": target,
                        "targetCategory": "category_dimensions",
                        "confidence": 0.7,
                        "reason": f"Detected as {semantic_type} field"
                    })
                    mapped = True

            # --- Phase 3: Substring POS alias match (lower priority) ---
            # If not yet mapped, check if field name contains a known alias.
            if not mapped:
                for alias, (target, category) in self.POS_FIELD_ALIASES.items():
                    if alias in stripped_name and len(alias) >= 2:
                        mappings.append({
                            "sourceField": field_name,
                            "targetField": target,
                            "targetCategory": category,
                            "confidence": 0.65,
                            "reason": f"POS field alias substring match: contains '{alias}' → {target}"
                        })
                        mapped = True
                        break

            if not mapped:
                unmapped.append(field_name)

        return {
            "success": True,
            "mappings": mappings,
            "unmapped": unmapped,
            "method": "rule_based"
        }

    def _rule_based_chart_recommendation(self, detected_fields: List[dict]) -> dict:
        """Fallback rule-based chart recommendation"""
        recommendations = []

        # Find time, dimension, and measure fields
        time_fields = [f for f in detected_fields if f.get("chartRole") == "time"]
        dimension_fields = [f for f in detected_fields if f.get("chartRole") == "dimension"]
        measure_fields = [f for f in detected_fields if f.get("chartRole") == "measure"]

        # Trend analysis with time dimension
        if time_fields and measure_fields:
            recommendations.append({
                "chartType": "line",
                "title": "趋势分析",
                "xAxis": time_fields[0]["fieldName"],
                "yAxis": [f["fieldName"] for f in measure_fields[:3]],
                "series": dimension_fields[0]["fieldName"] if dimension_fields else None,
                "reason": "有时间维度，适合趋势分析",
                "priority": 1
            })

        # Comparison with category dimension
        if dimension_fields and measure_fields:
            recommendations.append({
                "chartType": "bar",
                "title": "对比分析",
                "xAxis": dimension_fields[0]["fieldName"],
                "yAxis": [measure_fields[0]["fieldName"]] if measure_fields else [],
                "series": None,
                "reason": "有分类维度，适合对比分析",
                "priority": 2
            })

        # Proportion analysis
        if dimension_fields and measure_fields:
            recommendations.append({
                "chartType": "pie",
                "title": "占比分析",
                "xAxis": dimension_fields[0]["fieldName"],
                "yAxis": [measure_fields[0]["fieldName"]] if measure_fields else [],
                "series": None,
                "reason": "适合查看各分类占比",
                "priority": 3
            })

        return {
            "success": True,
            "recommendations": recommendations,
            "method": "rule_based"
        }

    async def close(self):
        """No-op: shared client lifecycle managed by main.py lifespan"""
