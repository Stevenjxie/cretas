from __future__ import annotations
"""
Cross-Sheet Aggregator Service

Aggregates data across multiple uploaded sheets for comprehensive analysis:
1. Fetches data from PostgreSQL for each upload
2. Extracts key numeric KPIs per sheet
3. Builds comparison charts (bar chart of KPIs across sheets)
4. Calls LLM for cross-sheet AI insights
"""
import hashlib
import logging
import time
from typing import Any, Dict, List, Optional

import httpx
import numpy as np
import pandas as pd

from config import get_settings
from common.utils.llm_limiter import llm_rate_limit

logger = logging.getLogger(__name__)


# ---------------------------------------------------------------------------
# AI summary cache (LRU, TTL-based)
# ---------------------------------------------------------------------------

class CrossSheetCache:
    """In-memory cache for cross-sheet AI summaries.

    Key:  MD5(upload_ids + sorted sheet names + sorted KPI keys)[:24]
    TTL:  3600 s (1 hour)
    Max:  200 entries, LRU eviction
    """

    def __init__(self, ttl_seconds: int = 3600, max_entries: int = 200):
        self._cache: Dict[str, Dict[str, Any]] = {}
        self._ttl = ttl_seconds
        self._max = max_entries

    # -- key helpers --------------------------------------------------------

    @staticmethod
    def _build_key(
        kpi_comparison: List[Dict[str, Any]],
        sheet_names: List[str],
    ) -> str:
        sheet_part = "|".join(sorted(sheet_names))
        kpi_keys: List[str] = []
        for item in kpi_comparison:
            kpi_keys.extend(sorted(item.get("kpis", {}).keys()))
        kpi_part = "|".join(sorted(set(kpi_keys)))
        raw = f"{sheet_part}::{kpi_part}"
        return hashlib.md5(raw.encode()).hexdigest()[:24]

    # -- public API ---------------------------------------------------------

    def get(
        self,
        kpi_comparison: List[Dict[str, Any]],
        sheet_names: List[str],
    ) -> Optional[str]:
        key = self._build_key(kpi_comparison, sheet_names)
        entry = self._cache.get(key)
        if entry is None:
            return None
        if time.time() - entry["created_at"] > self._ttl:
            del self._cache[key]
            return None
        entry["accessed_at"] = time.time()
        entry["access_count"] += 1
        logger.info("CrossSheetCache HIT key=%s", key)
        return entry["value"]

    def set(
        self,
        kpi_comparison: List[Dict[str, Any]],
        sheet_names: List[str],
        value: str,
    ) -> None:
        key = self._build_key(kpi_comparison, sheet_names)
        now = time.time()
        self._cache[key] = {
            "value": value,
            "created_at": now,
            "accessed_at": now,
            "access_count": 1,
        }
        if len(self._cache) > self._max:
            self._evict()
        logger.debug("CrossSheetCache SET key=%s", key)

    def _evict(self) -> None:
        """Remove oldest-accessed 10 % of entries."""
        if len(self._cache) <= self._max:
            return
        sorted_keys = sorted(
            self._cache, key=lambda k: self._cache[k]["accessed_at"]
        )
        to_remove = max(1, len(sorted_keys) // 10)
        for k in sorted_keys[:to_remove]:
            self._cache.pop(k, None)
        logger.debug("CrossSheetCache evicted %d entries", to_remove)


# Module-level singleton
_cross_sheet_cache = CrossSheetCache()


class CrossSheetAggregator:
    """Aggregates and analyzes data across multiple sheets"""

    def __init__(self):
        self.settings = get_settings()

    @property
    def client(self) -> httpx.AsyncClient:
        from common.llm_client import get_llm_http_client
        return get_llm_http_client()

    async def aggregate(
        self,
        upload_ids: List[int],
        sheet_names: List[str],
        factory_id: str = "F001"
    ) -> Dict[str, Any]:
        """
        Main aggregation entry point.

        Args:
            upload_ids: List of upload IDs to analyze
            sheet_names: Display names for each sheet
            factory_id: Factory ID for database queries

        Returns:
            Dict with kpiComparison, charts, aiSummary
        """
        try:
            # 1. Fetch data for each upload, tracking failures
            all_sheet_data: List[Dict[str, Any]] = []
            fetch_errors: List[Dict[str, Any]] = []
            for i, upload_id in enumerate(upload_ids):
                name = sheet_names[i] if i < len(sheet_names) else f"Sheet {i + 1}"
                try:
                    sheet_data = await self._fetch_sheet_data(upload_id, factory_id)
                    if sheet_data is not None and len(sheet_data) > 0:
                        all_sheet_data.append({
                            "uploadId": upload_id,
                            "sheetName": name,
                            "data": sheet_data
                        })
                    else:
                        fetch_errors.append({"sheetName": name, "error": "无数据"})
                except Exception as e:
                    fetch_errors.append({"sheetName": name, "error": str(e)})
                    logger.error(f"Failed to process upload {upload_id}: {e}")

            if not all_sheet_data:
                return {
                    "success": False,
                    "error": "无法获取任何 Sheet 数据",
                    "fetchErrors": fetch_errors
                }

            # 2. Extract KPIs per sheet
            kpi_comparison = self._extract_kpis(all_sheet_data)

            # 3. Build comparison charts
            charts = self._build_comparison_charts(kpi_comparison, all_sheet_data)

            # 4. Generate AI summary (cache → LLM → statistical fallback)
            ai_summary = None
            llm_fallback = False
            cache_hit = False

            # Try cache first
            cached = _cross_sheet_cache.get(kpi_comparison, sheet_names)
            if cached is not None:
                ai_summary = cached
                cache_hit = True
            else:
                try:
                    ai_summary = await self._generate_ai_summary(kpi_comparison, all_sheet_data)
                    if ai_summary:
                        _cross_sheet_cache.set(kpi_comparison, sheet_names, ai_summary)
                except Exception as e:
                    logger.error(f"LLM summary failed, using statistical fallback: {e}")
                    ai_summary = self._generate_statistical_summary(kpi_comparison)
                    llm_fallback = True

            result: Dict[str, Any] = {
                "success": True,
                "kpiComparison": kpi_comparison,
                "charts": charts,
                "aiSummary": ai_summary,
            }
            if fetch_errors:
                result["fetchErrors"] = fetch_errors
            if llm_fallback:
                result["llmFallback"] = True
            if cache_hit:
                result["cacheHit"] = True
            return result

        except Exception as e:
            logger.error(f"Cross-sheet aggregation failed: {e}", exc_info=True)
            return {"success": False, "error": str(e)}

    async def _fetch_sheet_data(
        self,
        upload_id: int,
        factory_id: str
    ) -> Optional[List[Dict[str, Any]]]:
        """Fetch data for a single upload from PostgreSQL"""
        try:
            from database.connection import is_postgres_enabled, get_db_context
            from database.repository import DynamicDataRepository

            if not is_postgres_enabled():
                logger.warning("PostgreSQL not enabled, cannot fetch sheet data")
                return None

            with get_db_context() as db:
                repo = DynamicDataRepository(db)
                rows = repo.get_by_upload_id(factory_id, upload_id)
                return rows if rows else None

        except Exception as e:
            logger.error(f"Failed to fetch data for upload {upload_id}: {e}")
            return None

    def _extract_kpis(
        self,
        all_sheet_data: List[Dict[str, Any]]
    ) -> List[Dict[str, Any]]:
        """Extract key numeric KPIs from each sheet"""
        kpi_comparison = []

        # KPI keywords by priority
        kpi_keywords = [
            ("营业收入", "revenue"),
            ("净利润", "net_profit"),
            ("毛利", "gross_profit"),
            ("毛利率", "gross_margin"),
            ("成本", "cost"),
            ("费用", "expense"),
            ("合计", "total"),
            ("收入", "income"),
            ("利润", "profit"),
        ]

        for sheet_info in all_sheet_data:
            df = pd.DataFrame(sheet_info["data"])
            if df.empty:
                continue

            kpis: Dict[str, float] = {}

            # Try to find KPI rows by first column label matching
            first_col = df.columns[0] if len(df.columns) > 0 else None
            numeric_cols = df.select_dtypes(include=[np.number]).columns.tolist()

            if first_col and first_col not in numeric_cols:
                # Financial report style: first column is labels, others are values
                for _, row in df.iterrows():
                    label = str(row.get(first_col, "")).strip()
                    if not label:
                        continue

                    for keyword, _ in kpi_keywords:
                        if keyword in label:
                            # Sum all numeric values for this row
                            row_sum = 0
                            for nc in numeric_cols:
                                val = row.get(nc)
                                if pd.notna(val) and isinstance(val, (int, float)):
                                    row_sum += val
                            if row_sum != 0:
                                kpis[label] = round(row_sum, 2)
                            break

            # Fallback: use column sums for numeric columns
            if not kpis and numeric_cols:
                for col in numeric_cols[:6]:
                    col_sum = df[col].sum()
                    if pd.notna(col_sum) and col_sum != 0:
                        kpis[col] = round(float(col_sum), 2)

            kpi_comparison.append({
                "sheetName": sheet_info["sheetName"],
                "kpis": kpis
            })

        return kpi_comparison

    @staticmethod
    def _infer_dim_label_from_sheet_names(sheet_names: List[str]) -> Optional[str]:
        """
        A5 (Apr 20 2026): Guess whether sheet names represent a business
        dimension (门店/品牌/渠道/区域), so charts can say "各门店核心指标对比"
        instead of the generic "各报表核心指标对比".

        Returns the dim label (e.g. '门店') or None to fall back.
        """
        if not sheet_names:
            return None
        joined = " ".join(sheet_names)
        # Order matters: check the more specific first
        if any(kw in joined for kw in ("分店", "门店", "店铺", "店", "馆", "铺")):
            return "门店"
        if any(kw in joined for kw in ("品牌",)):
            return "品牌"
        if any(kw in joined for kw in ("渠道", "平台")):
            return "渠道"
        if any(kw in joined for kw in ("区域", "大区", "城市", "省")):
            return "区域"
        # Pure literal sheet labels (like "表E", "Sheet1") → fall back
        return None

    def _build_comparison_charts(
        self,
        kpi_comparison: List[Dict[str, Any]],
        all_sheet_data: List[Dict[str, Any]]
    ) -> List[Dict[str, Any]]:
        """Build ECharts configurations for cross-sheet comparison"""
        charts = []

        if not kpi_comparison:
            return charts

        # Collect all KPI keys across sheets
        all_kpi_keys: Dict[str, int] = {}
        for item in kpi_comparison:
            for key in item.get("kpis", {}):
                all_kpi_keys[key] = all_kpi_keys.get(key, 0) + 1

        # Find common KPIs (present in >= 2 sheets)
        common_keys = [k for k, count in all_kpi_keys.items() if count >= 2]
        if not common_keys:
            common_keys = list(all_kpi_keys.keys())[:5]

        sheet_names = [item["sheetName"] for item in kpi_comparison]
        # Truncate long names
        display_names = [n[:12] + "..." if len(n) > 15 else n for n in sheet_names]

        # A5 (Apr 20 2026): 动态标题 — 若 sheet 名看起来是业务维度值
        # (如门店/品牌/渠道), 标题换成 "各{维度} 核心指标对比" 而非僵化的 "各报表".
        dim_label = self._infer_dim_label_from_sheet_names(sheet_names)
        chart_title = f"各{dim_label}核心指标对比" if dim_label else "各报表核心指标对比"

        # Chart 1: Bar chart comparing top KPIs across sheets
        if common_keys:
            top_keys = common_keys[:4]
            series = []
            for key in top_keys:
                data_values = []
                for item in kpi_comparison:
                    val = item.get("kpis", {}).get(key, 0)
                    data_values.append(round(float(val), 2) if val else 0)
                series.append({
                    "name": key,
                    "type": "bar",
                    "data": data_values,
                    "emphasis": {"focus": "series"}
                })

            charts.append({
                "chartType": "bar",
                "title": chart_title,
                "config": {
                    "tooltip": {"trigger": "axis", "axisPointer": {"type": "shadow"}},
                    "legend": {"data": top_keys, "top": 30},
                    "grid": {"left": "3%", "right": "4%", "bottom": "3%", "top": 70, "containLabel": True},
                    "xAxis": {"type": "category", "data": display_names, "axisLabel": {"rotate": 20}},
                    "yAxis": {"type": "value"},
                    "series": series
                }
            })

        # Chart 2: If we have a common "营业收入" or "收入" field, make a pie chart
        revenue_key = None
        for key in common_keys:
            if "收入" in key or "revenue" in key.lower():
                revenue_key = key
                break

        if revenue_key:
            pie_data = []
            for item in kpi_comparison:
                val = item.get("kpis", {}).get(revenue_key, 0)
                if val and float(val) > 0:
                    pie_data.append({
                        "name": item["sheetName"][:12],
                        "value": round(abs(float(val)), 2)
                    })

            if len(pie_data) >= 2:
                pie_dim = dim_label if dim_label else "报表"
                charts.append({
                    "chartType": "pie",
                    "title": f"{revenue_key} 各{pie_dim}占比",
                    "config": {
                        "tooltip": {"trigger": "item", "formatter": "{b}: {c} ({d}%)"},
                        "legend": {"orient": "vertical", "left": "left", "top": 30},
                        "series": [{
                            "name": revenue_key,
                            "type": "pie",
                            "radius": ["35%", "65%"],
                            "center": ["55%", "55%"],
                            "avoidLabelOverlap": True,
                            "itemStyle": {"borderRadius": 6, "borderColor": "#fff", "borderWidth": 2},
                            "label": {"show": True, "formatter": "{b}\n{d}%"},
                            "data": pie_data
                        }]
                    }
                })

        # Chart 3: Contribution waterfall chart (how each sheet contributes to total)
        if revenue_key or common_keys:
            contribution_key = revenue_key or common_keys[0]
            contrib_data = []
            for item in kpi_comparison:
                val = item.get("kpis", {}).get(contribution_key, 0)
                if val:
                    contrib_data.append({
                        "name": item["sheetName"][:12],
                        "value": round(float(val), 2)
                    })

            if len(contrib_data) >= 2:
                # Sort by value descending
                contrib_data.sort(key=lambda d: abs(d["value"]), reverse=True)
                total_val = sum(d["value"] for d in contrib_data)

                # Build waterfall: cumulative offset
                wf_placeholder = []
                wf_positive = []
                wf_negative = []
                cumulative = 0
                wf_categories = []

                for d in contrib_data:
                    wf_categories.append(d["name"])
                    val = d["value"]
                    if val >= 0:
                        wf_placeholder.append(round(cumulative, 2))
                        wf_positive.append(round(val, 2))
                        wf_negative.append(0)
                    else:
                        wf_placeholder.append(round(cumulative + val, 2))
                        wf_positive.append(0)
                        wf_negative.append(round(abs(val), 2))
                    cumulative += val

                # Add total bar
                wf_categories.append("合计")
                wf_placeholder.append(0)
                wf_positive.append(round(total_val, 2) if total_val >= 0 else 0)
                wf_negative.append(round(abs(total_val), 2) if total_val < 0 else 0)

                charts.append({
                    "chartType": "waterfall",
                    "title": f"{contribution_key} 各报表贡献分解",
                    "config": {
                        "tooltip": {"trigger": "axis", "axisPointer": {"type": "shadow"}},
                        "grid": {"left": "3%", "right": "4%", "bottom": "3%", "top": 50, "containLabel": True},
                        "xAxis": {"type": "category", "data": wf_categories, "axisLabel": {"rotate": 20}},
                        "yAxis": {"type": "value"},
                        "series": [
                            {
                                "name": "Placeholder",
                                "type": "bar",
                                "stack": "Total",
                                "itemStyle": {"color": "transparent"},
                                "data": wf_placeholder
                            },
                            {
                                "name": "正向贡献",
                                "type": "bar",
                                "stack": "Total",
                                "itemStyle": {"color": "#36B37E", "borderRadius": [4, 4, 0, 0]},
                                "label": {"show": True, "position": "top", "fontSize": 10},
                                "data": wf_positive
                            },
                            {
                                "name": "负向贡献",
                                "type": "bar",
                                "stack": "Total",
                                "itemStyle": {"color": "#FF5630", "borderRadius": [4, 4, 0, 0]},
                                "label": {"show": True, "position": "top", "fontSize": 10},
                                "data": wf_negative
                            }
                        ]
                    }
                })

        # Chart 4: Quadrant scatter (Revenue x Margin) if both metrics found
        if len(kpi_comparison) >= 2:
            revenue_vals = []
            margin_vals = []
            scatter_names = []

            # Try to find revenue and profit for each sheet to compute margin
            for item in kpi_comparison:
                kpis = item.get("kpis", {})
                rev = None
                profit = None
                for k, v in kpis.items():
                    if "收入" in k and rev is None:
                        rev = float(v)
                    if "净利润" in k or "利润" in k:
                        profit = float(v)
                if rev and profit and abs(rev) > 0:
                    margin = profit / abs(rev) * 100
                    revenue_vals.append(rev)
                    margin_vals.append(round(margin, 1))
                    scatter_names.append(item["sheetName"][:12])

            if len(revenue_vals) >= 2:
                scatter_data = [[revenue_vals[i], margin_vals[i]] for i in range(len(revenue_vals))]
                avg_rev = sum(revenue_vals) / len(revenue_vals)
                avg_margin = sum(margin_vals) / len(margin_vals)

                charts.append({
                    "chartType": "scatter",
                    "title": "收入-利润率四象限分析",
                    "config": {
                        "tooltip": {
                            "trigger": "item",
                            "formatter": "__FMT__quadrant_scatter_tooltip"
                        },
                        "grid": {"left": "3%", "right": "4%", "bottom": "3%", "top": 50, "containLabel": True},
                        "xAxis": {"type": "value", "name": "收入",
                                  "splitLine": {"lineStyle": {"type": "dashed"}}},
                        "yAxis": {"type": "value", "name": "利润率(%)",
                                  "splitLine": {"lineStyle": {"type": "dashed"}}},
                        "series": [{
                            "type": "scatter",
                            "symbolSize": 20,
                            "data": [[scatter_data[i][0], scatter_data[i][1], scatter_names[i]]
                                     for i in range(len(scatter_data))],
                            "label": {"show": True, "formatter": "__FMT__quadrant_scatter_label",
                                      "position": "top", "fontSize": 11},
                            "markLine": {
                                "silent": True,
                                "lineStyle": {"type": "dashed", "color": "#9ca3af"},
                                "data": [
                                    {"xAxis": round(avg_rev, 2), "label": {"formatter": "平均收入"}},
                                    {"yAxis": round(avg_margin, 1), "label": {"formatter": "平均利润率"}}
                                ]
                            }
                        }]
                    }
                })

        # Chart 5: Radar chart for multi-dimensional comparison (if enough common KPIs)
        if len(common_keys) >= 3:
            radar_keys = common_keys[:6]

            # Normalize values to 0-100 for radar
            max_values = {}
            for key in radar_keys:
                vals = [abs(float(item.get("kpis", {}).get(key, 0) or 0)) for item in kpi_comparison]
                max_values[key] = max(vals) if vals else 1

            indicator = [
                {"name": k[:8], "max": round(max_values[k] * 1.2, 2) if max_values[k] > 0 else 100}
                for k in radar_keys
            ]

            radar_series_data = []
            for item in kpi_comparison:
                values = [
                    round(abs(float(item.get("kpis", {}).get(k, 0) or 0)), 2)
                    for k in radar_keys
                ]
                radar_series_data.append({
                    "name": item["sheetName"][:12],
                    "value": values
                })

            # Only show top 5 sheets in radar
            radar_series_data = radar_series_data[:5]

            charts.append({
                "chartType": "radar",
                "title": "多维度综合对比",
                "config": {
                    "tooltip": {"trigger": "item"},
                    "legend": {
                        "data": [d["name"] for d in radar_series_data],
                        "top": 30
                    },
                    "radar": {
                        "indicator": indicator,
                        "center": ["50%", "55%"],
                        "radius": "60%"
                    },
                    "series": [{
                        "type": "radar",
                        "data": radar_series_data,
                        "areaStyle": {"opacity": 0.15}
                    }]
                }
            })

        return charts

    async def _generate_ai_summary(
        self,
        kpi_comparison: List[Dict[str, Any]],
        all_sheet_data: List[Dict[str, Any]]
    ) -> Optional[str]:
        """Generate AI-powered cross-sheet summary using LLM"""
        if not self.settings.llm_api_key:
            return self._generate_statistical_summary(kpi_comparison)

        # P0 出境脱敏: 注册分部/报表名 → 出境占位; 输出叙述还原真名 (叙述引用这些名字).
        from common.llm_redactor import register_values_for_egress, restore_in_scope
        register_values_for_egress({
            "分部": [str(it.get("sheetName")) for it in kpi_comparison if it.get("sheetName")]
        })

        try:
            # Build KPI summary for LLM
            kpi_text_parts = []
            for item in kpi_comparison:
                kpi_lines = [f"  {k}: {v:,.2f}" for k, v in item.get("kpis", {}).items()
                             if isinstance(v, (int, float)) and v == v]  # filter NaN
                kpi_text_parts.append(
                    f"**{item['sheetName']}**:\n" + "\n".join(kpi_lines) if kpi_lines
                    else f"**{item['sheetName']}**: 无数据"
                )

            kpi_overview = "\n\n".join(kpi_text_parts)

            # Compute cross-sheet derived metrics for LLM
            derived_metrics = []
            for item in kpi_comparison:
                kpis = item.get("kpis", {})
                rev = next((v for k, v in kpis.items() if "收入" in k), None)
                profit = next((v for k, v in kpis.items() if "净利润" in k or "利润" in k), None)
                if rev and abs(rev) > 0 and profit is not None:
                    margin = profit / abs(rev) * 100
                    derived_metrics.append(
                        f"  {item['sheetName']}: 利润率={margin:.1f}%"
                    )

            derived_text = ""
            if derived_metrics:
                derived_text = "\n## 派生指标\n" + "\n".join(derived_metrics)

            prompt = f"""你是一位食品加工企业的CFO级分析师，正在为管理层撰写各分部/区域的横向对比分析报告。

## 各报表核心 KPI

{kpi_overview}
{derived_text}

## 参考范围（食品加工通用，子行业差异大）
- 毛利率: 15-35%（禽类6-10%，乳制品10-15%，预制菜15-25%，调味品35-43%）
- 净利率: 3-8%
- 费用率: 15-25%
注意：请根据数据内容判断所属子行业特征，避免机械对标。

## 分析要求（严格遵守）

1. **高管摘要**（1-2句，含关键数字）
2. **分部排名与对标**:
   - 按收入和利润率排序各分部
   - 标注表现最好和最差的分部，差距是多少
   - 与参考范围对比，注明所属子行业
3. **关键发现**（每条含具体数字）:
   - 亏损分部：哪些分部净利润为负？亏损金额是多少？
   - 费用结构：各分部费用率是否合理？
   - 贡献度：各分部对总收入/利润的贡献百分比
4. **风险预警**:
   - 按严重程度排序（红/黄/绿）
   - 每条含影响金额估算
5. **改进建议**（按优先级排序）:
   - 每条建议含预期效果和量化目标
   - 可从表现好的分部借鉴什么

请用中文回答，每个观点必须有具体数字支撑。控制在 800 字以内。"""

            # 经免费链 call_chain — 免费 fallback + 熔断保护。INSIGHTS slot 按
            # provider 注入免费 model, 此处去掉显式 model 字段。
            from common.llm_router import call_chain, SLOT
            from common.llm_metrics import llm_caller_context

            payload = {
                "messages": [
                    {
                        "role": "system",
                        "content": "你是一个资深财务分析师，擅长跨报表综合分析和横向对比。回答要简洁、结构清晰、有数据支撑。"
                    },
                    {
                        "role": "user",
                        "content": prompt
                    }
                ],
                "temperature": 0.5,
                "max_tokens": 2000,
                "enable_thinking": False
            }

            async with llm_rate_limit():
                with llm_caller_context("cross_sheet_ai_summary"):
                    result = await call_chain(SLOT.INSIGHTS, payload, timeout=60.0)
            return restore_in_scope(result["choices"][0]["message"]["content"])  # P0 还原分部真名

        except Exception as e:
            logger.error(f"LLM cross-sheet summary failed: {e}")
            raise

    def _generate_statistical_summary(
        self,
        kpi_comparison: List[Dict[str, Any]]
    ) -> str:
        """Fallback: generate statistical summary without LLM"""
        if not kpi_comparison:
            return "无可用数据进行综合分析"

        parts = [f"跨 {len(kpi_comparison)} 个报表的综合分析：\n"]

        # Find common KPI keys
        all_keys: Dict[str, List[float]] = {}
        for item in kpi_comparison:
            for key, val in item.get("kpis", {}).items():
                if key not in all_keys:
                    all_keys[key] = []
                all_keys[key].append(float(val))

        for key, values in list(all_keys.items())[:5]:
            total = sum(values)
            avg = total / len(values) if values else 0
            max_val = max(values) if values else 0
            min_val = min(values) if values else 0

            parts.append(
                f"**{key}**: 总计 {total:,.2f}, "
                f"平均 {avg:,.2f}, "
                f"最高 {max_val:,.2f}, "
                f"最低 {min_val:,.2f}"
            )

        return "\n".join(parts)

    async def close(self):
        """No-op: shared client lifecycle managed by main.py lifespan"""
