"""
NL2SQL Generator Service

Translates natural language queries into safe, parameterized SQL for
SmartBI dynamic data stored in PostgreSQL JSONB columns.

Architecture:
1. Intent classification (AGGREGATE, FILTER, RANKING, TREND, COMPARISON)
2. Field matching (fuzzy Chinese name matching against field_definitions)
3. SQL skeleton building with JSONB accessors
4. LLM refinement (qwen3.5-flash) for final SQL
5. Safety validation (SELECT only, LIMIT enforcement, parameterized queries)

Data model:
- Table: smart_bi_dynamic_data
- Columns: id, factory_id, upload_id, sheet_name, row_index, row_data (JSONB),
           period, category, created_at
- Field definitions: smart_bi_pg_field_definitions (original_name, standard_name,
           field_type, is_dimension, is_measure, is_time)
"""
from __future__ import annotations

import json
import logging
import re
import time
from dataclasses import dataclass, field
from difflib import SequenceMatcher
from enum import Enum
from typing import Any, Dict, List, Optional, Tuple

import httpx

from config import get_settings

logger = logging.getLogger(__name__)


# ---------------------------------------------------------------------------
# Data classes
# ---------------------------------------------------------------------------

class QueryIntent(str, Enum):
    """Classified intent of the natural language query."""
    AGGREGATE = "AGGREGATE"    # SUM / AVG / COUNT / MIN / MAX
    FILTER = "FILTER"          # WHERE conditions
    RANKING = "RANKING"        # TOP N / BOTTOM N
    TREND = "TREND"            # Time series analysis
    COMPARISON = "COMPARISON"  # Compare groups / categories
    DETAIL = "DETAIL"          # Raw row retrieval
    UNKNOWN = "UNKNOWN"


@dataclass
class FieldMatch:
    """Result of matching a user-mentioned field to a definition."""
    user_term: str
    matched_name: str          # key used in row_data JSONB
    original_name: str         # display name from field_definitions
    standard_name: str         # normalised name from field_definitions
    field_type: str            # NUMBER, TEXT, DATE, etc.
    is_measure: bool
    is_dimension: bool
    is_time: bool
    score: float               # 0-1 match confidence


@dataclass
class SQLResult:
    """Output of the SQL generation pipeline."""
    sql: str                   # Parameterized SQL (uses :param notation)
    params: Dict[str, Any]     # Bind parameters
    explanation: str           # Human-readable explanation (Chinese)
    intent: QueryIntent
    matched_fields: List[FieldMatch]
    confidence: float          # 0-1 overall confidence
    warnings: List[str] = field(default_factory=list)


# ---------------------------------------------------------------------------
# Intent keywords (Chinese + English)
# ---------------------------------------------------------------------------

_INTENT_KEYWORDS: Dict[QueryIntent, List[str]] = {
    QueryIntent.AGGREGATE: [
        "总计", "合计", "汇总", "总", "总共", "总额", "总量",
        "平均", "平均值", "均值", "均",
        "数量", "计数", "有多少", "多少个", "共有",
        "最大", "最大值", "最高", "最小", "最小值", "最低",
        "sum", "avg", "average", "count", "total", "max", "min",
    ],
    QueryIntent.FILTER: [
        "筛选", "过滤", "查找", "查询", "找出", "哪些", "列出",
        "等于", "大于", "小于", "超过", "低于", "不等于", "包含",
        "是", "为",
        "filter", "where", "find", "search",
    ],
    QueryIntent.RANKING: [
        "排名", "排行", "前", "top", "前几", "前十",
        "最多", "最少", "最高", "最低", "倒数",
        "rank", "ranking", "bottom",
    ],
    QueryIntent.TREND: [
        "趋势", "变化", "走势", "变动", "增长", "下降",
        "按月", "按季", "按年", "按日", "按周",
        "月度", "季度", "年度", "每月", "每年", "每天",
        "时间", "同比", "环比",
        "trend", "over time", "monthly", "yearly", "daily",
    ],
    QueryIntent.COMPARISON: [
        "对比", "比较", "对照", "各", "分别", "每个", "各个",
        "按类", "按区", "按部门", "分组",
        "compare", "comparison", "versus", "vs",
    ],
}

_AGG_KEYWORDS: Dict[str, str] = {
    "总计": "SUM", "合计": "SUM", "汇总": "SUM", "总额": "SUM",
    "总量": "SUM", "总共": "SUM", "总": "SUM", "sum": "SUM",
    "平均": "AVG", "平均值": "AVG", "均值": "AVG", "均": "AVG",
    "avg": "AVG", "average": "AVG",
    "数量": "COUNT", "计数": "COUNT", "有多少": "COUNT",
    "多少个": "COUNT", "共有": "COUNT", "count": "COUNT",
    "最大": "MAX", "最大值": "MAX", "最高": "MAX", "max": "MAX",
    "最小": "MIN", "最小值": "MIN", "最低": "MIN", "min": "MIN",
}


# ---------------------------------------------------------------------------
# SQL safety
# ---------------------------------------------------------------------------

_FORBIDDEN_KEYWORDS = {
    "INSERT", "UPDATE", "DELETE", "DROP", "ALTER", "TRUNCATE",
    "CREATE", "GRANT", "REVOKE", "EXEC", "EXECUTE",
    "INTO", "SET",  # part of INSERT/UPDATE
    "MERGE", "REPLACE",
    "--", ";",      # comment / statement separator
}

_MAX_LIMIT = 1000
_DEFAULT_LIMIT = 100


# ---------------------------------------------------------------------------
# SQLGenerator
# ---------------------------------------------------------------------------

class SQLGenerator:
    """
    Translates natural language into safe SQL for JSONB dynamic data.

    Usage::

        gen = SQLGenerator()
        result = await gen.generate_sql(
            query="各产品的销售总额是多少",
            upload_id=42,
            factory_id="F001",
            field_definitions=[...],  # list of SmartBiPgFieldDefinition.to_dict()
        )
        print(result.sql, result.params)
    """

    def __init__(self) -> None:
        self.settings = get_settings()

    # ------------------------------------------------------------------
    # Public API
    # ------------------------------------------------------------------

    async def generate_sql(
        self,
        query: str,
        upload_id: int,
        factory_id: str,
        field_definitions: List[Dict[str, Any]],
        limit: int = _DEFAULT_LIMIT,
    ) -> SQLResult:
        """
        End-to-end NL-to-SQL pipeline.

        Args:
            query: Natural language question in Chinese or English.
            upload_id: ID of the uploaded dataset.
            factory_id: Factory scope identifier.
            field_definitions: List of field definition dicts (from DB).
            limit: Max rows to return (capped at 1000).

        Returns:
            SQLResult with parameterized SQL and metadata.
        """
        t0 = time.monotonic()
        limit = min(max(1, limit), _MAX_LIMIT)

        # Step 1 — classify intent
        intent = self._classify_intent(query)
        logger.info(f"NL2SQL intent={intent.value} query={query!r}")

        # Step 2 — match fields
        matched_fields = self._match_fields(query, field_definitions)
        if not matched_fields:
            logger.warning("NL2SQL: no fields matched, will rely on LLM")

        # Step 3 — detect aggregation function
        agg_func = self._detect_agg_func(query, intent)

        # Step 4 — detect top-N
        top_n = self._detect_top_n(query, limit)

        # Step 5 — build skeleton SQL
        skeleton = self._build_skeleton(
            intent=intent,
            matched_fields=matched_fields,
            field_definitions=field_definitions,
            agg_func=agg_func,
            top_n=top_n,
            limit=limit,
        )

        # Step 6 — LLM refinement
        sql, explanation, confidence = await self._llm_refine(
            query=query,
            skeleton=skeleton,
            field_definitions=field_definitions,
            intent=intent,
            matched_fields=matched_fields,
            agg_func=agg_func,
            limit=limit,
        )

        # Step 6.5 — Verify LLM didn't break JSONB patterns
        # If LLM introduced bare column names (not row_data->>), fall back to skeleton
        if sql != skeleton and "row_data" not in sql and matched_fields:
            logger.warning("NL2SQL: LLM removed JSONB accessors, falling back to skeleton SQL")
            sql = skeleton
            confidence = max(0.3, confidence - 0.2)

        # Step 7 — safety validation
        sql, warnings = self._validate_sql(sql, limit)

        # Build params
        params: Dict[str, Any] = {
            "factory_id": factory_id,
            "upload_id": upload_id,
        }

        elapsed = time.monotonic() - t0
        logger.info(f"NL2SQL completed in {elapsed:.2f}s confidence={confidence:.2f}")

        return SQLResult(
            sql=sql,
            params=params,
            explanation=explanation,
            intent=intent,
            matched_fields=matched_fields,
            confidence=confidence,
            warnings=warnings,
        )

    # ------------------------------------------------------------------
    # Step 1: Intent classification
    # ------------------------------------------------------------------

    def _classify_intent(self, query: str) -> QueryIntent:
        """Rule-based intent classification using keyword scoring."""
        q = query.lower()
        scores: Dict[QueryIntent, int] = {intent: 0 for intent in QueryIntent if intent != QueryIntent.UNKNOWN}

        for intent, keywords in _INTENT_KEYWORDS.items():
            for kw in keywords:
                if kw in q:
                    scores[intent] += 1

        if not any(scores.values()):
            return QueryIntent.DETAIL

        best = max(scores, key=lambda k: scores[k])

        # Disambiguation: RANKING trumps AGGREGATE when top-N detected
        if scores[QueryIntent.RANKING] > 0 and re.search(r"前\s*\d+|top\s*\d+", q, re.IGNORECASE):
            return QueryIntent.RANKING

        # TREND trumps others when time keywords are dominant
        if scores[QueryIntent.TREND] >= 2 and scores[QueryIntent.TREND] >= scores.get(best, 0):
            return QueryIntent.TREND

        return best

    # ------------------------------------------------------------------
    # Step 2: Field matching
    # ------------------------------------------------------------------

    def _match_fields(
        self,
        query: str,
        field_definitions: List[Dict[str, Any]],
    ) -> List[FieldMatch]:
        """
        Fuzzy-match user's Chinese field names against definitions.

        Uses SequenceMatcher for similarity, with exact/substring
        match bonuses.
        """
        matches: List[FieldMatch] = []
        seen_fields: set = set()
        q = query.lower()

        for fd in field_definitions:
            original = fd.get("originalName", "") or ""
            standard = fd.get("standardName", "") or ""
            ftype = fd.get("fieldType", "TEXT") or "TEXT"

            # Skip empty definitions
            if not original and not standard:
                continue

            # The JSONB key is original_name (Chinese) — that's what's stored in row_data
            jsonb_key = original or standard

            # Try matching both names against the query
            best_score = 0.0
            best_term = ""

            for candidate in [original, standard]:
                if not candidate:
                    continue
                c_lower = candidate.lower()

                # Exact substring match
                if c_lower in q:
                    score = 0.95 if len(c_lower) >= 2 else 0.7
                    if score > best_score:
                        best_score = score
                        best_term = candidate
                    continue

                # Check if query contains the candidate
                if len(c_lower) >= 2 and c_lower in q:
                    score = 0.85
                    if score > best_score:
                        best_score = score
                        best_term = candidate
                    continue

                # Fuzzy match — only for longer terms to avoid false positives
                if len(c_lower) >= 2:
                    ratio = SequenceMatcher(None, c_lower, q).ratio()
                    # Partial match: check if any word in query is close
                    partial = 0.0
                    for seg in re.split(r'[\s,，、的]+', query):
                        if len(seg) >= 2:
                            r = SequenceMatcher(None, c_lower, seg.lower()).ratio()
                            partial = max(partial, r)
                    score = max(ratio * 0.6, partial * 0.85)
                    if score > best_score:
                        best_score = score
                        best_term = candidate

            # Threshold
            if best_score >= 0.45 and jsonb_key not in seen_fields:
                seen_fields.add(jsonb_key)
                matches.append(FieldMatch(
                    user_term=best_term,
                    matched_name=jsonb_key,
                    original_name=original,
                    standard_name=standard,
                    field_type=ftype.upper(),
                    is_measure=bool(fd.get("isMeasure", False)),
                    is_dimension=bool(fd.get("isDimension", False)),
                    is_time=bool(fd.get("isTime", False)),
                    score=round(best_score, 3),
                ))

        # Sort by score descending
        matches.sort(key=lambda m: m.score, reverse=True)
        return matches

    # ------------------------------------------------------------------
    # Step 3: Aggregation function detection
    # ------------------------------------------------------------------

    def _detect_agg_func(self, query: str, intent: QueryIntent) -> str:
        """Detect which aggregation function the user wants."""
        q = query.lower()
        for kw, func in _AGG_KEYWORDS.items():
            if kw in q:
                return func
        # Default by intent
        if intent == QueryIntent.AGGREGATE:
            return "SUM"
        if intent == QueryIntent.RANKING:
            return "SUM"
        return "SUM"

    # ------------------------------------------------------------------
    # Step 4: Top-N detection
    # ------------------------------------------------------------------

    def _detect_top_n(self, query: str, default_limit: int) -> int:
        """Extract top-N value from query like '前5' or 'top 10'."""
        patterns = [
            r"前\s*(\d+)",
            r"top\s*(\d+)",
            r"(\d+)\s*名",
            r"倒数\s*(\d+)",
        ]
        for pat in patterns:
            m = re.search(pat, query, re.IGNORECASE)
            if m:
                n = int(m.group(1))
                return min(n, _MAX_LIMIT)
        return default_limit

    # ------------------------------------------------------------------
    # Step 5: Skeleton SQL builder
    # ------------------------------------------------------------------

    def _build_skeleton(
        self,
        intent: QueryIntent,
        matched_fields: List[FieldMatch],
        field_definitions: List[Dict[str, Any]],
        agg_func: str,
        top_n: int,
        limit: int,
    ) -> str:
        """
        Build a skeleton SQL string for LLM to refine.

        Uses JSONB accessor syntax: row_data->>'field_name'
        Numeric casts: CAST(NULLIF(row_data->>'field', '') AS DECIMAL(18,2))
        """
        dimensions = [f for f in matched_fields if f.is_dimension]
        measures = [f for f in matched_fields if f.is_measure]
        time_fields = [f for f in matched_fields if f.is_time]

        # If no explicit matches, pick defaults from field_definitions
        if not dimensions and not measures:
            for fd in field_definitions:
                if fd.get("isMeasure") and not measures:
                    name = fd.get("standardName") or fd.get("originalName", "")
                    if name:
                        measures.append(FieldMatch(
                            user_term=name, matched_name=name,
                            original_name=fd.get("originalName", ""),
                            standard_name=fd.get("standardName", ""),
                            field_type=(fd.get("fieldType") or "NUMBER").upper(),
                            is_measure=True, is_dimension=False, is_time=False,
                            score=0.3,
                        ))
                if fd.get("isDimension") and not dimensions:
                    name = fd.get("standardName") or fd.get("originalName", "")
                    if name:
                        dimensions.append(FieldMatch(
                            user_term=name, matched_name=name,
                            original_name=fd.get("originalName", ""),
                            standard_name=fd.get("standardName", ""),
                            field_type=(fd.get("fieldType") or "TEXT").upper(),
                            is_measure=False, is_dimension=True, is_time=False,
                            score=0.3,
                        ))

        base_where = "WHERE factory_id = :factory_id AND upload_id = :upload_id"

        if intent == QueryIntent.AGGREGATE and dimensions and measures:
            dim = dimensions[0]
            meas = measures[0]
            return (
                f"SELECT row_data->>'{dim.matched_name}' AS \"{dim.original_name}\",\n"
                f"       {agg_func}(CAST(NULLIF(row_data->>'{meas.matched_name}', '') AS DECIMAL(18,2))) AS \"{agg_func.lower()}_{meas.original_name}\"\n"  # noqa: E501
                f"FROM smart_bi_dynamic_data\n"
                f"{base_where}\n"
                f"  AND row_data->>'{dim.matched_name}' IS NOT NULL\n"
                f"GROUP BY row_data->>'{dim.matched_name}'\n"
                f"ORDER BY \"{agg_func.lower()}_{meas.original_name}\" DESC\n"
                f"LIMIT {min(top_n, limit)}"
            )

        if intent == QueryIntent.RANKING and measures:
            meas = measures[0]
            dim = dimensions[0] if dimensions else None
            if dim:
                return (
                    f"SELECT row_data->>'{dim.matched_name}' AS \"{dim.original_name}\",\n"
                    f"       {agg_func}(CAST(NULLIF(row_data->>'{meas.matched_name}', '') AS DECIMAL(18,2))) AS \"{meas.original_name}\"\n"  # noqa: E501
                    f"FROM smart_bi_dynamic_data\n"
                    f"{base_where}\n"
                    f"  AND row_data->>'{dim.matched_name}' IS NOT NULL\n"
                    f"GROUP BY row_data->>'{dim.matched_name}'\n"
                    f"ORDER BY \"{meas.original_name}\" DESC\n"
                    f"LIMIT {min(top_n, limit)}"
                )
            return (
                f"SELECT row_data->>'{meas.matched_name}' AS \"{meas.original_name}\",\n"
                f"       row_data\n"
                f"FROM smart_bi_dynamic_data\n"
                f"{base_where}\n"
                f"ORDER BY CAST(NULLIF(row_data->>'{meas.matched_name}', '') AS DECIMAL(18,2)) DESC\n"
                f"LIMIT {min(top_n, limit)}"
            )

        if intent == QueryIntent.TREND and time_fields and measures:
            tf = time_fields[0]
            meas = measures[0]
            return (
                f"SELECT row_data->>'{tf.matched_name}' AS \"{tf.original_name}\",\n"
                f"       {agg_func}(CAST(NULLIF(row_data->>'{meas.matched_name}', '') AS DECIMAL(18,2))) AS \"{meas.original_name}\"\n"  # noqa: E501
                f"FROM smart_bi_dynamic_data\n"
                f"{base_where}\n"
                f"  AND row_data->>'{tf.matched_name}' IS NOT NULL\n"
                f"GROUP BY row_data->>'{tf.matched_name}'\n"
                f"ORDER BY row_data->>'{tf.matched_name}'\n"
                f"LIMIT {limit}"
            )

        if intent == QueryIntent.COMPARISON and dimensions and measures:
            dim = dimensions[0]
            meas_cols = []
            for m in measures[:5]:  # max 5 measures
                meas_cols.append(
                    f"       {agg_func}(CAST(NULLIF(row_data->>'{m.matched_name}', '') AS DECIMAL(18,2))) AS \"{m.original_name}\""  # noqa: E501
                )
            return (
                f"SELECT row_data->>'{dim.matched_name}' AS \"{dim.original_name}\",\n"
                + ",\n".join(meas_cols) + "\n"
                f"FROM smart_bi_dynamic_data\n"
                f"{base_where}\n"
                f"  AND row_data->>'{dim.matched_name}' IS NOT NULL\n"
                f"GROUP BY row_data->>'{dim.matched_name}'\n"
                f"ORDER BY row_data->>'{dim.matched_name}'\n"
                f"LIMIT {limit}"
            )

        # FILTER / DETAIL / fallback — return raw rows
        select_cols = []
        for f in (matched_fields or [])[:10]:
            select_cols.append(f"row_data->>'{f.matched_name}' AS \"{f.original_name}\"")
        if not select_cols:
            select_cols.append("row_data")

        return (
            f"SELECT {', '.join(select_cols)}\n"
            f"FROM smart_bi_dynamic_data\n"
            f"{base_where}\n"
            f"ORDER BY row_index\n"
            f"LIMIT {limit}"
        )

    # ------------------------------------------------------------------
    # Step 6: LLM refinement
    # ------------------------------------------------------------------

    async def _llm_refine(
        self,
        query: str,
        skeleton: str,
        field_definitions: List[Dict[str, Any]],
        intent: QueryIntent,
        matched_fields: List[FieldMatch],
        agg_func: str,
        limit: int,
    ) -> Tuple[str, str, float]:
        """
        Send schema + query + skeleton to LLM for refinement.

        Returns (sql, explanation, confidence).
        Falls back to skeleton if LLM is unavailable.
        """
        if not self.settings.llm_api_key:
            logger.warning("NL2SQL: LLM API key not configured, using skeleton SQL")
            return skeleton, "根据字段匹配自动生成的查询", 0.5

        # Build schema description
        schema_lines = []
        for fd in field_definitions:
            orig = fd.get("originalName", "")
            std = fd.get("standardName", "")
            ftype = fd.get("fieldType", "")
            roles = []
            if fd.get("isMeasure"):
                roles.append("measure")
            if fd.get("isDimension"):
                roles.append("dimension")
            if fd.get("isTime"):
                roles.append("time")
            samples = fd.get("sampleValues", [])
            sample_str = ""
            if samples and isinstance(samples, list):
                sample_str = f", samples: {samples[:3]}"
            schema_lines.append(
                f"  - originalName: {orig}, standardName: {std}, "
                f"type: {ftype}, roles: [{','.join(roles)}]{sample_str}"
            )
        schema_text = "\n".join(schema_lines)

        matched_text = ""
        if matched_fields:
            lines = [f"  - \"{m.user_term}\" -> row_data->>'{m.matched_name}' (score={m.score})" for m in matched_fields[:8]]  # noqa: E501
            matched_text = "Matched fields:\n" + "\n".join(lines)

        system_prompt = (
            "You are an expert SQL generator for PostgreSQL JSONB data.\n"
            "The data table is `smart_bi_dynamic_data` with these columns:\n"
            "  - id (BIGINT), factory_id (VARCHAR), upload_id (BIGINT)\n"
            "  - row_data (JSONB) — ALL business fields are stored inside this JSONB column\n"
            "  - sheet_name (VARCHAR), row_index (INT), period (VARCHAR), category (VARCHAR)\n"
            "  - created_at (TIMESTAMP)\n\n"
            "CRITICAL: Business fields do NOT exist as real table columns.\n"
            "You MUST access them via JSONB operators on the `row_data` column:\n"
            "  - Text:    row_data->>'originalName'\n"
            "  - Numeric: CAST(NULLIF(row_data->>'originalName', '') AS DECIMAL(18,2))\n"
            "  - JSON:    row_data->'nested_key'\n"
            "Use originalName (Chinese) as the JSONB key, NOT standardName.\n\n"
            "RULES:\n"
            "1. Always include WHERE factory_id = :factory_id AND upload_id = :upload_id\n"
            "2. Only generate SELECT statements\n"
            "3. Use :param_name for parameters (not $1 or %s)\n"
            "4. Always include LIMIT (max 1000)\n"
            "5. Use NULLIF(..., '') before CAST to handle empty strings\n"
            "6. Column aliases should use Chinese names from originalName\n"
            "7. For GROUP BY, always use the full expression (not alias)\n"
            "8. NEVER use bare column names for business fields — always use row_data->>'fieldName'\n"
            "9. The skeleton SQL provided is already correct syntax — only refine logic, do not change accessor patterns\n"  # noqa: E501
            "10. Bug #26 fix (Apr 17 2026): ranking/aggregation questions (Top N / 最高 / 最多 / 排名 / 哪个...最) "
            "MUST use GROUP BY on the dimension + SUM/AVG/COUNT on the measure. Never return raw rows for such questions.\n"  # noqa: E501
            "11. ALWAYS filter out meta-rows in WHERE clause: add "
            "`AND row_data->>'<primary_dim>' NOT IN ('合计','总计','小计','汇总','Total','TOTAL')` "
            "when primary dim is text (门店名称/商品/部门/地区 etc). Without this, 合计 row pollutes Top N.\n"
        )

        user_prompt = (
            f"User question: {query}\n\n"
            f"Detected intent: {intent.value}\n"
            f"Detected aggregation: {agg_func}\n\n"
            f"Available fields in row_data:\n{schema_text}\n\n"
            f"{matched_text}\n\n"
            f"Skeleton SQL (may need refinement):\n```sql\n{skeleton}\n```\n\n"
            f"Max rows: {limit}\n\n"
            "Please respond in this exact JSON format (no markdown):\n"
            "{\n"
            '  "sql": "the final SQL query",\n'
            '  "explanation": "Chinese explanation of what this query does",\n'
            '  "confidence": 0.85\n'
            "}\n"
        )

        try:
            # 经免费链 call_chain — 免费 fallback + 熔断保护。INSIGHTS slot 按
            # provider 注入免费 model, 此处去掉显式 model 字段。
            from common.llm_router import call_chain, SLOT
            from common.llm_metrics import llm_caller_context

            payload = {
                "messages": [
                    {"role": "system", "content": system_prompt},
                    {"role": "user", "content": user_prompt},
                ],
                "temperature": 0.1,
                "max_tokens": 1000,
                "enable_thinking": False,
            }

            with llm_caller_context("sql_generator_nl2sql"):
                result = await call_chain(SLOT.INSIGHTS, payload, timeout=30.0)
            content = result["choices"][0]["message"]["content"]

            # Parse LLM response
            parsed = self._parse_llm_response(content)
            if parsed:
                sql = parsed.get("sql", skeleton)
                explanation = parsed.get("explanation", "AI 生成的查询")
                confidence = float(parsed.get("confidence", 0.7))
                confidence = max(0.0, min(1.0, confidence))
                return sql, explanation, confidence

            logger.warning("NL2SQL: failed to parse LLM response, using skeleton")
            return skeleton, "根据字段匹配自动生成的查询", 0.5

        except httpx.TimeoutException:
            logger.warning("NL2SQL: LLM request timed out, using skeleton")
            return skeleton, "查询生成超时，使用模板查询", 0.4

        except Exception as e:
            logger.error(f"NL2SQL: LLM refinement failed: {e}", exc_info=True)
            return skeleton, "LLM 不可用，使用模板查询", 0.4

    def _parse_llm_response(self, content: str) -> Optional[Dict[str, Any]]:
        """Parse JSON from LLM response, handling markdown code blocks."""
        content = content.strip()

        # Remove markdown code block wrapper
        if content.startswith("```"):
            lines = content.split("\n")
            # Remove first line (```json or ```) and last line (```)
            lines = [l for l in lines if not l.strip().startswith("```")]  # noqa: E741
            content = "\n".join(lines).strip()

        try:
            return json.loads(content)
        except json.JSONDecodeError:
            # Try to extract JSON object from text
            m = re.search(r'\{[^{}]*"sql"[^{}]*\}', content, re.DOTALL)
            if m:
                try:
                    return json.loads(m.group(0))
                except json.JSONDecodeError:
                    pass
        return None

    # ------------------------------------------------------------------
    # Step 7: Safety validation
    # ------------------------------------------------------------------

    def _validate_sql(self, sql: str, limit: int) -> Tuple[str, List[str]]:
        """
        Validate SQL for safety.

        Returns (sanitised_sql, warnings).
        Raises ValueError if SQL is fundamentally unsafe.
        """
        warnings: List[str] = []
        sql = sql.strip().rstrip(";")

        # Must be a SELECT statement
        upper = sql.upper().lstrip()
        if not upper.startswith("SELECT"):
            raise ValueError("Only SELECT statements are allowed")

        # Check for forbidden keywords (outside of string literals)
        # Simple approach: check tokens at word boundaries
        sql_no_strings = re.sub(r"'[^']*'", "", sql)  # remove string literals
        for kw in _FORBIDDEN_KEYWORDS:
            if kw == "--" or kw == ";":
                if kw in sql_no_strings:
                    raise ValueError(f"Forbidden SQL pattern detected: {kw}")
            else:
                pattern = r'\b' + re.escape(kw) + r'\b'
                if re.search(pattern, sql_no_strings, re.IGNORECASE):
                    raise ValueError(f"Forbidden SQL keyword: {kw}")

        # Ensure LIMIT exists
        if not re.search(r'\bLIMIT\b', sql, re.IGNORECASE):
            sql += f"\nLIMIT {limit}"
            warnings.append(f"Added LIMIT {limit} for safety")
        else:
            # Verify LIMIT value is not too high
            m = re.search(r'\bLIMIT\s+(\d+)', sql, re.IGNORECASE)
            if m:
                current_limit = int(m.group(1))
                if current_limit > _MAX_LIMIT:
                    sql = re.sub(
                        r'\bLIMIT\s+\d+', f'LIMIT {_MAX_LIMIT}',
                        sql, flags=re.IGNORECASE
                    )
                    warnings.append(f"Reduced LIMIT from {current_limit} to {_MAX_LIMIT}")

        # Ensure parameterized factory/upload filter
        if ":factory_id" not in sql and ":upload_id" not in sql:
            warnings.append("Query missing factory_id/upload_id parameters — data may be unscoped")

        return sql, warnings
