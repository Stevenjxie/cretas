from __future__ import annotations
"""
Semantic Mapper Service (PRIMARY - LLM-Powered)

The primary field mapping service for SmartBI. Maps Excel column names to
standard business fields using:
1. LLM-based semantic understanding with caching (primary)
2. Rule-based dictionary matching (fast fallback)
3. Multi-model consensus for ambiguous cases
4. Persistent learning (saves successful mappings for reuse)

This is the RECOMMENDED service for field mapping in new code.

Related services:
- FieldMappingService (field_mapping.py): Fast dictionary-only, no LLM
- LLMMapper (llm_mapper.py): Sheet analysis & chart recommendations

Usage:
    mapper = SemanticMapper()
    result = await mapper.map_fields_async(columns, context="销售数据")

Part of the Zero-Code SmartBI architecture.
"""
import json
import logging
import re
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Dict, List, Optional, Tuple

from ..utils.json_parser import robust_json_parse

logger = logging.getLogger(__name__)

# Category to Java dataType mapping
# This ensures Python field mappings translate correctly to Java's expected format
CATEGORY_TO_DATA_TYPE = {
    # Numeric categories → NUMERIC
    "amount": "NUMERIC",
    "rate": "NUMERIC",
    "quantity": "NUMERIC",
    "percentage": "NUMERIC",
    "price": "NUMERIC",
    "cost": "NUMERIC",
    "revenue": "NUMERIC",
    "profit": "NUMERIC",
    "measure": "NUMERIC",
    "numeric": "NUMERIC",

    # Categorical categories → CATEGORICAL
    "category": "CATEGORICAL",
    "region": "CATEGORICAL",
    "department": "CATEGORICAL",
    "product": "CATEGORICAL",
    "customer": "CATEGORICAL",
    "name": "CATEGORICAL",
    "dimension": "CATEGORICAL",
    "categorical": "CATEGORICAL",

    # Time categories → DATE
    "time": "DATE",
    "date": "DATE",
    "period": "DATE",
    "year": "DATE",
    "month": "DATE",
}

# Cache file path for learned mappings
LEARNED_MAPPINGS_FILE = Path(__file__).parent.parent / "data" / "learned_field_mappings.json"

# Dynamic field registry path
FIELD_REGISTRY_FILE = Path(__file__).parent.parent / "data" / "standard_fields_registry.json"


@dataclass
class FieldMappingResult:
    """Result of a single field mapping via LLM"""
    standard_field: Optional[str]
    confidence: float
    category: Optional[str] = None
    description: Optional[str] = None
    source: str = "llm"  # llm, cache, rule

    def to_dict(self) -> Dict[str, Any]:
        return {
            "standard_field": self.standard_field,
            "confidence": self.confidence,
            "category": self.category,
            "description": self.description,
            "source": self.source
        }


@dataclass
class FieldMapping:
    """A single field mapping result"""
    original: str  # Original column name
    standard: Optional[str]  # Mapped standard field name
    confidence: float  # Mapping confidence (0.0-1.0)
    method: str  # rule, llm, multi_model
    category: Optional[str] = None  # Field category (amount, rate, category, time, etc.)
    description: Optional[str] = None  # Human-readable description

    def to_dict(self) -> Dict[str, Any]:
        # Infer data_type from category for Java compatibility
        data_type = CATEGORY_TO_DATA_TYPE.get(self.category, "TEXT") if self.category else "TEXT"

        return {
            "original": self.original,
            "standard": self.standard,
            "confidence": self.confidence,
            "method": self.method,
            "category": self.category,
            "data_type": data_type,  # Java expects this for field classification
            "description": self.description
        }


@dataclass
class SemanticMappingResult:
    """Result of semantic mapping"""
    success: bool = True
    confidence: float = 0.0
    method: str = "unknown"
    table_type: Optional[str] = None  # profit_statement, sales_report, budget, etc.
    field_mappings: List[FieldMapping] = field(default_factory=list)
    time_dimension: Optional[Dict[str, Any]] = None  # Time-related columns
    unmapped_fields: List[str] = field(default_factory=list)
    error: Optional[str] = None
    note: Optional[str] = None

    def to_dict(self) -> Dict[str, Any]:
        result = {
            "success": self.success,
            "confidence": self.confidence,
            "method": self.method,
            "table_type": self.table_type,
            "field_mappings": [fm.to_dict() for fm in self.field_mappings],
            "unmapped_fields": self.unmapped_fields
        }
        if self.time_dimension:
            result["time_dimension"] = self.time_dimension
        if self.error:
            result["error"] = self.error
        if self.note:
            result["note"] = self.note
        return result


def _load_field_registry() -> Dict[str, Dict]:
    """
    Load standard fields from external registry file.
    This allows dynamic field extension without code changes.
    """
    try:
        if FIELD_REGISTRY_FILE.exists():
            with open(FIELD_REGISTRY_FILE, 'r', encoding='utf-8') as f:
                registry = json.load(f)
            fields = registry.get("fields", {})
            logger.info(f"Loaded {len(fields)} standard fields from registry: {FIELD_REGISTRY_FILE}")
            return fields
        else:
            logger.warning(f"Field registry not found at {FIELD_REGISTRY_FILE}, using empty registry")
            return {}
    except Exception as e:
        logger.error(f"Failed to load field registry: {e}")
        return {}


# Dynamically loaded standard fields (from external JSON config)
STANDARD_FIELDS = _load_field_registry()

# Table type patterns
TABLE_TYPE_PATTERNS = {
    "profit_statement": {
        "keywords": ["利润表", "损益表", "Profit", "Income Statement", "P&L"],
        "required_fields": ["revenue", "cost", "gross_profit"]
    },
    "sales_report": {
        "keywords": ["销售报表", "Sales Report", "销售分析", "销售统计"],
        "required_fields": ["revenue", "category"]
    },
    "budget_report": {
        "keywords": ["预算", "Budget", "预算执行", "预算分析"],
        "required_fields": ["budget_amount", "actual_amount"]
    },
    "expense_report": {
        "keywords": ["费用报表", "Expense Report", "费用分析"],
        "required_fields": ["expense", "category"]
    },
    "sales_detail": {
        "keywords": ["销售明细", "Sales Detail", "订单明细", "销售流水", "销售记录"],
        "required_fields": ["amount"]
    }
}


class SemanticMapper:
    """
    Maps column names to standard fields using layered approach:

    Layer 1: Rule-based dictionary matching (fast, accurate for known terms)
    Layer 2: LLM-based semantic matching (for unknown terms)
    Layer 3: Multi-model consensus (for ambiguous cases)
    """

    def __init__(self):
        self._settings = None
        self._custom_mappings: Dict[str, Dict[str, str]] = {}  # factory_id -> {original: standard}
        self.learned_mappings: Dict[str, Dict[str, Any]] = {}  # column_name -> {standard_field, confidence, ...}
        self._load_learned_mappings()

    @property
    def settings(self):
        if self._settings is None:
            from config import get_settings
            self._settings = get_settings()
        return self._settings

    def add_custom_mapping(self, factory_id: str, original: str, standard: str):
        """Add a custom mapping for a specific factory"""
        if factory_id not in self._custom_mappings:
            self._custom_mappings[factory_id] = {}
        self._custom_mappings[factory_id][original] = standard

    def _load_learned_mappings(self):
        """Load learned mappings from cache file"""
        try:
            if LEARNED_MAPPINGS_FILE.exists():
                with open(LEARNED_MAPPINGS_FILE, 'r', encoding='utf-8') as f:
                    self.learned_mappings = json.load(f)
                logger.info(f"Loaded {len(self.learned_mappings)} learned mappings from cache")
            else:
                self.learned_mappings = {}
                logger.info("No learned mappings cache found, starting fresh")
        except Exception as e:
            logger.warning(f"Failed to load learned mappings: {e}")
            self.learned_mappings = {}

    def _save_learned_mappings(self):
        """Save learned mappings to cache file"""
        try:
            # Ensure directory exists
            LEARNED_MAPPINGS_FILE.parent.mkdir(parents=True, exist_ok=True)
            with open(LEARNED_MAPPINGS_FILE, 'w', encoding='utf-8') as f:
                json.dump(self.learned_mappings, f, ensure_ascii=False, indent=2)
            logger.debug(f"Saved {len(self.learned_mappings)} learned mappings to cache")
        except Exception as e:
            logger.warning(f"Failed to save learned mappings: {e}")

    async def map_field_with_llm(
        self,
        column_name: str,
        sample_values: List[Any] = None,
        context: str = None
    ) -> FieldMappingResult:
        """
        Map a single field using LLM with caching.

        This is the primary mapping method that:
        1. Checks the learned_mappings cache first
        2. If cache miss, calls LLM to infer the mapping
        3. Saves the result to cache for future use

        Args:
            column_name: The column name to map
            sample_values: Optional sample values from the column
            context: Optional table/sheet context

        Returns:
            FieldMappingResult with the mapping result
        """
        # Normalize column name for cache lookup
        cache_key = column_name.strip()

        # 1. Check cache first
        if cache_key in self.learned_mappings:
            cached = self.learned_mappings[cache_key]
            logger.debug(f"Cache hit for '{column_name}': {cached.get('standard_field')}")
            return FieldMappingResult(
                standard_field=cached.get("standard_field"),
                confidence=cached.get("confidence", 0.9),
                category=cached.get("category"),
                description=cached.get("description"),
                source="cache"
            )

        # 2. LLM inference
        result = await self._llm_infer_mapping(column_name, sample_values, context)

        # 3. Save to cache if successful
        if result.standard_field is not None and result.confidence >= 0.7:
            self.learned_mappings[cache_key] = {
                "standard_field": result.standard_field,
                "confidence": result.confidence,
                "category": result.category,
                "description": result.description
            }
            self._save_learned_mappings()
            logger.info(f"Learned new mapping: '{column_name}' -> '{result.standard_field}' (confidence: {result.confidence})")  # noqa: E501

        return result

    async def _llm_infer_mapping(
        self,
        column_name: str,
        sample_values: List[Any] = None,
        context: str = None
    ) -> FieldMappingResult:
        """
        Use LLM to infer the mapping for a column name.

        Args:
            column_name: The column name to map
            sample_values: Optional sample values
            context: Optional table context

        Returns:
            FieldMappingResult with LLM inference
        """
        try:
            # Build available standard fields list with descriptions
            available_fields = []
            for field_name, field_info in STANDARD_FIELDS.items():
                available_fields.append(f"{field_name}: {field_info.get('description', '')} ({field_info.get('category', '')})")  # noqa: E501

            # Build sample values text
            sample_text = ""
            if sample_values:
                # Take up to 5 non-null samples
                samples = [str(v) for v in sample_values[:5] if v is not None and str(v).strip()]
                if samples:
                    sample_text = f"\n数据示例: {', '.join(samples)}"

            prompt = f"""你是一个数据字段映射专家。请分析以下Excel列名，将其映射到标准财务/业务字段。

列名: "{column_name}"
表格上下文: {context or '未知'}{sample_text}

可选的标准字段:
{chr(10).join(available_fields)}

请分析这个列名最可能对应哪个标准字段。

返回JSON格式:
{{
  "standard_field": "字段名或null",
  "confidence": 0.0-1.0,
  "category": "amount/rate/category/time",
  "reasoning": "简短的推理说明"
}}

注意:
- 如果列名明显不属于任何标准字段，返回standard_field为null
- confidence表示你的确信程度
- 考虑中文同义词、缩写、英文对应等"""

            response = await self._call_llm(prompt)
            if response:
                parsed = self._parse_json_response(response)
                if parsed:
                    std_field = parsed.get("standard_field")
                    # Validate that the field exists in STANDARD_FIELDS or is a dynamic field
                    if std_field and std_field != "null":
                        # Check if it's a known standard field
                        if std_field in STANDARD_FIELDS:
                            category = STANDARD_FIELDS[std_field].get("category")
                        else:
                            category = parsed.get("category")

                        return FieldMappingResult(
                            standard_field=std_field,
                            confidence=parsed.get("confidence", 0.7),
                            category=category,
                            description=parsed.get("reasoning"),
                            source="llm"
                        )

            # LLM returned null or failed to parse
            return FieldMappingResult(
                standard_field=None,
                confidence=0.3,
                description="LLM could not determine mapping",
                source="llm"
            )

        except Exception as e:
            logger.warning(f"LLM inference failed for '{column_name}': {e}")
            return FieldMappingResult(
                standard_field=None,
                confidence=0.0,
                description=f"LLM inference error: {str(e)}",
                source="llm_error"
            )

    async def map_fields_with_llm(
        self,
        columns: List[str],
        sample_data: Optional[List[List[Any]]] = None,
        context: str = None
    ) -> Dict[str, FieldMappingResult]:
        """
        Map multiple fields using LLM with caching.

        This is a batch version of map_field_with_llm that processes
        multiple columns efficiently.

        Args:
            columns: List of column names to map
            sample_data: Optional sample data (rows of values)
            context: Optional table context

        Returns:
            Dict mapping column names to FieldMappingResult
        """
        results = {}

        # Separate cached and uncached columns
        cached_columns = []
        uncached_columns = []

        for col in columns:
            cache_key = col.strip()
            if cache_key in self.learned_mappings:
                cached_columns.append(col)
            else:
                uncached_columns.append(col)

        # Process cached columns (fast path)
        for col in cached_columns:
            cached = self.learned_mappings[col.strip()]
            results[col] = FieldMappingResult(
                standard_field=cached.get("standard_field"),
                confidence=cached.get("confidence", 0.9),
                category=cached.get("category"),
                description=cached.get("description"),
                source="cache"
            )

        # Batch process uncached columns with LLM
        if uncached_columns:
            # Extract sample values for each column
            col_samples = {}
            if sample_data:
                for i, col in enumerate(columns):
                    if col in uncached_columns and i < len(columns):
                        samples = []
                        for row in sample_data[:5]:
                            if i < len(row) and row[i] is not None:
                                samples.append(row[i])
                        col_samples[col] = samples

            # Process uncached columns
            for col in uncached_columns:
                sample_values = col_samples.get(col, [])
                result = await self.map_field_with_llm(col, sample_values, context)
                results[col] = result

        return results

    async def map_fields(
        self,
        columns: List[str],
        sample_data: Optional[List[List[Any]]] = None,
        factory_id: Optional[str] = None,
        table_context: Optional[str] = None
    ) -> SemanticMappingResult:
        """
        Map column names to standard fields.

        Args:
            columns: List of column names to map
            sample_data: Optional sample data for context
            factory_id: Optional factory ID for custom mappings
            table_context: Optional context about the table (e.g., sheet name)

        Returns:
            SemanticMappingResult with all mappings
        """
        result = SemanticMappingResult()
        mappings: List[FieldMapping] = []
        unmapped: List[str] = []

        # Detect table type from context
        if table_context:
            result.table_type = self._detect_table_type(table_context, columns)

        # Detect time dimension columns
        time_columns = self._detect_time_columns(columns)
        if time_columns:
            result.time_dimension = {
                "type": time_columns["type"],
                "columns": time_columns["columns"]
            }

        # Layer 1: Rule-based mapping
        rule_mappings, rule_unmapped = self._map_with_rules(columns, factory_id)
        mappings.extend(rule_mappings)

        # If all mapped with high confidence, return
        if not rule_unmapped or all(m.confidence >= self.settings.semantic_mapping_confidence_threshold for m in mappings):  # noqa: E501
            result.field_mappings = mappings
            result.unmapped_fields = rule_unmapped
            result.confidence = sum(m.confidence for m in mappings) / len(mappings) if mappings else 0.5
            result.method = "rule"
            return result

        # Layer 2: LLM mapping for unmapped fields
        if rule_unmapped:
            llm_mappings = await self._map_with_llm(rule_unmapped, columns, sample_data, table_context)
            mappings.extend(llm_mappings)

            # Update unmapped list
            llm_mapped = {m.original for m in llm_mappings if m.standard}
            unmapped = [c for c in rule_unmapped if c not in llm_mapped]

        # Check overall confidence
        avg_confidence = sum(m.confidence for m in mappings) / len(mappings) if mappings else 0.0

        if avg_confidence < self.settings.semantic_mapping_confidence_threshold and unmapped:
            # Layer 3: Multi-model enhancement
            enhanced_mappings = await self._map_with_multi_model(
                unmapped, columns, sample_data, table_context
            )
            mappings.extend(enhanced_mappings)
            unmapped = [c for c in unmapped if c not in {m.original for m in enhanced_mappings if m.standard}]

        result.field_mappings = mappings
        result.unmapped_fields = unmapped
        result.confidence = sum(m.confidence for m in mappings) / len(mappings) if mappings else 0.5
        result.method = "combined"

        return result

    def _map_with_rules(
        self, columns: List[str], factory_id: Optional[str] = None
    ) -> Tuple[List[FieldMapping], List[str]]:
        """
        Rule-based field mapping (Layer 1).

        Wide-format time-period columns (e.g., "2025年1月_预算数") are deliberately
        sent to the LLM layer to get proper time-suffixed mappings like "budget_amount_202501".
        """
        mappings = []
        unmapped = []

        # Detect if this is wide-format data
        is_wide = self._is_wide_format(columns)

        # Check custom mappings first
        custom_map = self._custom_mappings.get(factory_id, {}) if factory_id else {}

        # Pattern to detect time-period columns that need LLM handling
        time_period_pattern = re.compile(
            r'(\d{4}年)?(1[0-2]|[1-9])月|'   # 2025年1月, 1月
            r'\d{4}[-_]\d{2}'                   # 2025-01, 2025_01
        )

        for col in columns:
            col_lower = col.lower().strip()  # noqa: F841
            col_cleaned = self._clean_column_name(col)

            # For wide-format data, skip time-period columns from rule matching
            # They need LLM to generate proper time-suffixed field names
            if is_wide and time_period_pattern.search(col):
                unmapped.append(col)
                continue

            # Check custom mapping
            if col in custom_map:
                mappings.append(FieldMapping(
                    original=col,
                    standard=custom_map[col],
                    confidence=0.95,
                    method="custom",
                    description="Custom factory mapping"
                ))
                continue

            # Check LLM-learned mapping rules (previously inferred by LLM, cached for reuse)
            cache_key = col.strip()
            if cache_key in self.learned_mappings:
                cached = self.learned_mappings[cache_key]
                mappings.append(FieldMapping(
                    original=col,
                    standard=cached.get("standard_field"),
                    confidence=cached.get("confidence", 0.85),
                    method="learned_rule",
                    category=cached.get("category"),
                    description=cached.get("description", "LLM-learned mapping rule")
                ))
                continue

            # Check standard field dictionary
            matched = False
            for standard_name, field_info in STANDARD_FIELDS.items():
                for synonym in field_info["synonyms"]:
                    if self._match_synonym(col_cleaned, synonym):
                        mappings.append(FieldMapping(
                            original=col,
                            standard=standard_name,
                            confidence=0.9,
                            method="rule",
                            category=field_info.get("category"),
                            description=field_info.get("description")
                        ))
                        matched = True
                        break
                if matched:
                    break

            if not matched:
                unmapped.append(col)

        return mappings, unmapped

    def _match_synonym(self, column: str, synonym: str) -> bool:
        """Check if column matches a synonym"""
        col_lower = column.lower()
        syn_lower = synonym.lower()

        # Exact match
        if col_lower == syn_lower:
            return True

        # Contains match (for composite columns)
        if syn_lower in col_lower or col_lower in syn_lower:
            return True

        # Partial match after cleaning
        col_cleaned = re.sub(r'[_\-\s]+', '', col_lower)
        syn_cleaned = re.sub(r'[_\-\s]+', '', syn_lower)
        if col_cleaned == syn_cleaned:
            return True

        return False

    def _clean_column_name(self, name: str) -> str:
        """Clean column name for matching"""
        # Remove common prefixes/suffixes
        cleaned = name.strip()
        # Remove merged header artifacts
        cleaned = re.sub(r'^[\d\s月年Q]+[_\-\s]*', '', cleaned)
        # Remove trailing numbers
        cleaned = re.sub(r'[_\-\s]*\d+$', '', cleaned)
        return cleaned

    def _normalize_for_match(self, name: str) -> str:
        """Normalize column name for fuzzy matching"""
        if not name:
            return ""
        # Remove all whitespace and special characters, lowercase
        normalized = re.sub(r'[\s_\-&]+', '', name.lower())
        return normalized

    async def _map_with_llm(
        self,
        unmapped_columns: List[str],
        all_columns: List[str],
        sample_data: Optional[List[List[Any]]],
        table_context: Optional[str]
    ) -> List[FieldMapping]:
        """
        LLM-based field mapping (Layer 2).
        """
        if not unmapped_columns:
            return []

        try:
            # Prepare context - include field descriptions for better LLM understanding
            fields_with_desc = {
                name: info.get("description", "")
                for name, info in STANDARD_FIELDS.items()
            }

            # Sample data context
            sample_text = ""
            if sample_data and len(sample_data) > 0:
                sample_text = "\nSample data:\n"
                for i, row in enumerate(sample_data[:3]):
                    row_vals = [str(v) if v else "" for v in row[:10]]
                    sample_text += f"  Row {i}: {' | '.join(row_vals)}\n"

            # Detect wide-format data
            wide_format_instruction = self._build_wide_format_instruction(all_columns)

            # Build a numbered list for precise matching
            # This helps LLM return exact column references
            numbered_columns = [f"{i}: {col}" for i, col in enumerate(unmapped_columns)]

            prompt = f"""Map these Excel column names to standard business field names.

Columns to map (with index):
{chr(10).join(numbered_columns)}

All columns in table: {all_columns[:20]}
Table context: {table_context or 'Unknown'}
{sample_text}
{wide_format_instruction}

Available standard fields (name → description):
{json.dumps(fields_with_desc, ensure_ascii=False, indent=2)}

Rules:
1. For each column, find the best matching standard field from the list above.
2. If a column clearly represents a known business concept but doesn't match any listed field, you may suggest a new snake_case field name (e.g., "delivery_date", "contact_phone").  # noqa: E501
3. Use the sample data to verify your mapping makes sense.
4. Return null for columns that are purely structural (like serial numbers or empty headers).
5. CRITICAL: In "original" field, return the EXACT full column name as shown above, NOT a simplified version.

Return JSON only:
{{
  "mappings": [
    {{"index": 0, "original": "exact_column_name_from_list", "standard": "standard_field_or_null", "confidence": 0.0-1.0, "reasoning": "brief reason"}}  # noqa: E501
  ]
}}"""

            response = await self._call_llm(prompt)
            if response:
                parsed = self._parse_json_response(response)
                if parsed and "mappings" in parsed:
                    result = []
                    new_learned = False

                    # Build lookup for original column names by index
                    col_by_index = {i: col for i, col in enumerate(unmapped_columns)}
                    # Also build fuzzy lookup for when LLM doesn't return exact name
                    col_normalized = {self._normalize_for_match(col): col for col in unmapped_columns}

                    # Track which columns have been mapped to avoid duplicates
                    mapped_columns = set()

                    for m in parsed["mappings"]:
                        std = m.get("standard")
                        llm_original = m.get("original", "")

                        # CRITICAL FIX: Get the ACTUAL original column name(s)
                        # For group mappings (e.g. "24年同期实际" matching multiple columns),
                        # we need to find ALL matching columns
                        actual_originals = []

                        # Try index first (most reliable, single match)
                        if "index" in m and m["index"] is not None:
                            try:
                                idx = int(m["index"])
                                if idx in col_by_index and col_by_index[idx] not in mapped_columns:
                                    actual_originals = [col_by_index[idx]]
                            except (ValueError, TypeError):
                                pass

                        # If no index or invalid, try exact match
                        if not actual_originals and llm_original in unmapped_columns:
                            if llm_original not in mapped_columns:
                                actual_originals = [llm_original]

                        # If still no match, try fuzzy match
                        if not actual_originals:
                            normalized = self._normalize_for_match(llm_original)
                            if normalized in col_normalized:
                                col = col_normalized[normalized]
                                if col not in mapped_columns:
                                    actual_originals = [col]

                        # Last resort: find ALL columns that contain this pattern
                        # This handles cases where LLM returns simplified names like "24年同期实际"
                        # that should match "1月_24年同期实际", "2月_24年同期实际", etc.
                        if not actual_originals and llm_original:
                            for col in unmapped_columns:
                                if col not in mapped_columns:
                                    # Check if the LLM's simplified name is a suffix of the column
                                    if col.endswith(llm_original) or llm_original in col:
                                        actual_originals.append(col)

                        # Skip if we can't find any original column
                        if not actual_originals:
                            logger.warning(f"LLM returned unrecognized column: '{llm_original}'")
                            continue

                        # Create mappings for all matching columns
                        for actual_original in actual_originals:
                            if actual_original in mapped_columns:
                                continue
                            mapped_columns.add(actual_original)

                            if std and std != "null":
                                # Accept both registry fields and LLM-suggested new fields
                                field_info = STANDARD_FIELDS.get(std, {})
                                confidence = m.get("confidence", 0.7)
                                # Lower confidence for fields not in registry
                                if std not in STANDARD_FIELDS:
                                    confidence = min(confidence, 0.6)
                                    logger.debug(f"LLM suggested new field '{std}' for column '{actual_original}' (not in registry)")  # noqa: E501
                                result.append(FieldMapping(
                                    original=actual_original,  # Use ACTUAL original, not LLM's version
                                    standard=std,
                                    confidence=confidence,
                                    method="llm",
                                    category=field_info.get("category", "unknown"),
                                    description=m.get("reasoning")
                                ))

                                # Save LLM-created mapping as a learned rule for future use
                                if confidence >= 0.6:
                                    cache_key = actual_original.strip()
                                    self.learned_mappings[cache_key] = {
                                        "standard_field": std,
                                        "confidence": confidence,
                                        "category": field_info.get("category", "unknown"),
                                        "description": m.get("reasoning", "LLM-learned mapping")
                                    }
                                    new_learned = True
                            else:
                                result.append(FieldMapping(
                                    original=actual_original,  # Use ACTUAL original, not LLM's version
                                    standard=None,
                                    confidence=m.get("confidence", 0.5),
                                    method="llm",
                                    description="No matching standard field"
                                ))

                    # Persist newly learned rules
                    if new_learned:
                        self._save_learned_mappings()
                        logger.info(f"Saved {sum(1 for m in result if m.standard)} new LLM-learned mapping rules")

                    return result

        except Exception as e:
            logger.warning(f"LLM mapping failed: {e}")

        # Return low-confidence unmapped results
        return [
            FieldMapping(
                original=col,
                standard=None,
                confidence=0.3,
                method="llm_failed",
                description="LLM mapping failed"
            )
            for col in unmapped_columns
        ]

    async def _map_with_multi_model(
        self,
        unmapped_columns: List[str],
        all_columns: List[str],
        sample_data: Optional[List[List[Any]]],
        table_context: Optional[str]
    ) -> List[FieldMapping]:
        """
        Multi-model consensus mapping (Layer 3).
        """
        if not unmapped_columns:
            return []

        try:
            # Get multiple LLM opinions
            results = []

            # Call with different prompting strategies
            prompt1 = self._create_mapping_prompt(unmapped_columns, all_columns, "semantic")
            prompt2 = self._create_mapping_prompt(unmapped_columns, all_columns, "contextual")

            response1 = await self._call_llm(prompt1)
            response2 = await self._call_llm(prompt2, model="fast")

            if response1:
                parsed1 = self._parse_json_response(response1)
                if parsed1:
                    results.append(parsed1.get("mappings", []))

            if response2:
                parsed2 = self._parse_json_response(response2)
                if parsed2:
                    results.append(parsed2.get("mappings", []))

            # Vote on mappings
            if results:
                return self._vote_on_mappings(unmapped_columns, results)

        except Exception as e:
            logger.warning(f"Multi-model mapping failed: {e}")

        # Fallback
        return [
            FieldMapping(
                original=col,
                standard=None,
                confidence=0.4,
                method="multi_model_failed",
                description="Could not determine mapping"
            )
            for col in unmapped_columns
        ]

    def _create_mapping_prompt(
        self, columns: List[str], all_columns: List[str], strategy: str
    ) -> str:
        """Create mapping prompt based on strategy"""
        available_standards = list(STANDARD_FIELDS.keys())

        if strategy == "semantic":
            return f"""Analyze the semantic meaning of these column names and map to standard fields.

Columns: {columns}
Context columns: {all_columns[:15]}
Standard fields: {available_standards}

Focus on the meaning of each column name.
Return JSON: {{"mappings": [{{"original": "col", "standard": "field_or_null", "confidence": 0.0-1.0}}]}}"""

        else:  # contextual
            return f"""Given the context of a business data table, map these columns.

Columns to map: {columns}
All columns (for context): {all_columns[:15]}
Available standard fields: {available_standards}

Consider how these columns relate to each other.
Return JSON: {{"mappings": [{{"original": "col", "standard": "field_or_null", "confidence": 0.0-1.0}}]}}"""

    def _vote_on_mappings(
        self, columns: List[str], all_results: List[List[Dict]]
    ) -> List[FieldMapping]:
        """Vote on mappings from multiple model results"""
        mappings = []

        for col in columns:
            # Collect all mappings for this column
            col_mappings = []
            for result in all_results:
                for m in result:
                    if m.get("original") == col:
                        col_mappings.append(m)

            if not col_mappings:
                mappings.append(FieldMapping(
                    original=col,
                    standard=None,
                    confidence=0.3,
                    method="multi_model"
                ))
                continue

            # Vote on standard field
            standards = [m.get("standard") for m in col_mappings if m.get("standard")]
            if standards:
                # Most common standard
                voted_standard = max(set(standards), key=standards.count)
                agreement = standards.count(voted_standard) / len(col_mappings)

                field_info = STANDARD_FIELDS.get(voted_standard, {})
                mappings.append(FieldMapping(
                    original=col,
                    standard=voted_standard if voted_standard in STANDARD_FIELDS else None,
                    confidence=min(0.85, 0.6 + agreement * 0.25),
                    method="multi_model",
                    category=field_info.get("category"),
                    description=field_info.get("description")
                ))
            else:
                mappings.append(FieldMapping(
                    original=col,
                    standard=None,
                    confidence=0.4,
                    method="multi_model"
                ))

        return mappings

    def _detect_table_type(self, context: str, columns: List[str]) -> Optional[str]:
        """Detect table type from context and columns"""
        context_lower = context.lower()
        columns_lower = [c.lower() for c in columns]  # noqa: F841

        for table_type, info in TABLE_TYPE_PATTERNS.items():
            # Check keywords in context
            for keyword in info["keywords"]:
                if keyword.lower() in context_lower:
                    return table_type

        return None

    def _detect_time_columns(self, columns: List[str]) -> Optional[Dict[str, Any]]:
        """Detect time-related columns"""
        time_patterns = {
            "monthly": [r'\d{1,2}月', r'[Jj]an|[Ff]eb|[Mm]ar|[Aa]pr|[Mm]ay|[Jj]un|[Jj]ul|[Aa]ug|[Ss]ep|[Oo]ct|[Nn]ov|[Dd]ec'],  # noqa: E501
            "quarterly": [r'Q[1-4]', r'第[一二三四]季度'],
            "yearly": [r'\d{4}年', r'20\d{2}']
        }

        for time_type, patterns in time_patterns.items():
            matched_columns = []
            for col in columns:
                for pattern in patterns:
                    if re.search(pattern, col):
                        matched_columns.append(col)
                        break

            if len(matched_columns) >= 2:
                return {
                    "type": time_type,
                    "columns": matched_columns
                }

        return None

    def _is_wide_format(self, columns: List[str]) -> bool:
        """
        Detect if data is wide-format (multiple time-period columns).
        Supports patterns like:
        - "1月_预算数", "2月_预算数"
        - "2025年1月_预算数", "2025年2月_本月实际"
        - "202501", "2025-01"
        """
        # Chinese month pattern (1月, 2月, ... 12月) - also inside year patterns like "2025年1月"
        chinese_month_pattern = re.compile(r'(1[0-2]|[1-9])月')
        # English/numeric month pattern (202501, 2025-01)
        numeric_month_pattern = re.compile(r'\d{4}[-_]?\d{2}')
        # Chinese year+month pattern (2025年1月)
        year_month_pattern = re.compile(r'\d{4}年(1[0-2]|[1-9])月')

        chinese_month_count = sum(1 for col in columns if chinese_month_pattern.search(col))
        numeric_month_count = sum(1 for col in columns if numeric_month_pattern.search(col))
        year_month_count = sum(1 for col in columns if year_month_pattern.search(col))

        return chinese_month_count >= 3 or numeric_month_count >= 3 or year_month_count >= 3

    def _build_wide_format_instruction(self, columns: List[str]) -> str:
        """
        Build instruction for LLM to handle wide-format data with time dimensions.
        """
        if not self._is_wide_format(columns):
            return ""

        return """
【重要】这是宽格式数据（多个时间列），请遵循以下映射规则：

1. **保留时间维度**: 列名包含月份时，standard字段必须带时间后缀
   - 格式: {metric_type}_{YYYYMM}
   - 例: "1月_预算收入" → "budget_amount_202501"
   - 例: "2月_实际金额" → "actual_amount_202502"
   - 例: "12月_利润" → "net_profit_202512"
   - 例: "2025年1月_预算数" → "budget_amount_202501"
   - 例: "2025年1月_本月实际" → "actual_amount_202501"
   - 例: "2025年1月_去年同期" → "last_year_actual_202501"
   - 例: "2025年2月_预算数" → "budget_amount_202502"

2. **年份识别**:
   - 列名有年份（"2025年"、"25年"）则使用该年份
   - 无年份信息默认使用2025
   - "去年同期"/"24年" 仍使用列名中标注的月份，仅metric_type改为 last_year_actual

3. **metric_type对照**:
   - 预算/预算数/计划/目标 → budget_amount
   - 实际/本月实际/完成/执行/实际数 → actual_amount
   - 同期/去年同期/去年/上年同期/24年 → last_year_actual
   - 利润/净利/净利润 → net_profit
   - 收入/营收/营业收入 → revenue
   - 成本/营业成本 → cost
   - 预算收入 → budget_amount (revenue context)
   - 实际收入 → actual_amount (revenue context)

4. **年度汇总列**:
   - "累计预算"/"年度预算" → ytd_budget
   - "累计实际"/"年度实际"/"累计完成" → ytd_actual
   - "年度汇总"/"全年" → annual_total

5. **维度列（非时间列）**:
   - "项目"/"科目" → category
   - "行次" → serial_number
   - "部门"/"中心" → department
"""

    async def _call_llm(self, prompt: str, model: str = "default") -> Optional[str]:
        """Call LLM via multi-provider router (SLOT.MAPPER — semantic field
        mapping). Shares the free-first chain + circuit-breaker + free-tier
        fallback; the model is injected per-slot by call_chain (replaces the
        prior default/fast direct call)."""
        try:
            from common.llm_router import call_chain, SLOT
            from common.llm_metrics import llm_caller_context

            payload = {
                "messages": [{"role": "user", "content": prompt}],
                "temperature": 0.1,
                "max_tokens": 1000,
            }

            with llm_caller_context("semantic_mapper"):
                resp_json = await call_chain(SLOT.MAPPER, payload)

            choices = resp_json.get("choices") or []
            if not choices:
                return None
            return choices[0].get("message", {}).get("content")

        except Exception as e:
            logger.error(f"LLM call failed: {e}")
            return None

    def _parse_json_response(self, response: str) -> Optional[Dict]:
        """Parse JSON from LLM response"""
        # Fix: Use centralized robust JSON parser
        result = robust_json_parse(response, fallback=None)
        if result is None:
            logger.warning("Failed to parse LLM response as JSON")
        return result

    # ========== Learned Mappings Management ==========

    def add_learned_mapping(
        self,
        column_name: str,
        standard_field: str,
        confidence: float = 0.95,
        category: Optional[str] = None,
        description: Optional[str] = None
    ):
        """
        Manually add or update a learned mapping.

        Use this to correct LLM mistakes or add domain-specific mappings.

        Args:
            column_name: The column name to map
            standard_field: The standard field to map to
            confidence: Confidence score (default 0.95 for manual entries)
            category: Field category (amount, rate, category, time)
            description: Optional description
        """
        cache_key = column_name.strip()

        # Get category from STANDARD_FIELDS if not provided
        if not category and standard_field in STANDARD_FIELDS:
            category = STANDARD_FIELDS[standard_field].get("category")

        self.learned_mappings[cache_key] = {
            "standard_field": standard_field,
            "confidence": confidence,
            "category": category,
            "description": description or "Manually added mapping"
        }
        self._save_learned_mappings()
        logger.info(f"Added manual mapping: '{column_name}' -> '{standard_field}'")

    def remove_learned_mapping(self, column_name: str) -> bool:
        """
        Remove a learned mapping from the cache.

        Args:
            column_name: The column name to remove

        Returns:
            True if removed, False if not found
        """
        cache_key = column_name.strip()
        if cache_key in self.learned_mappings:
            del self.learned_mappings[cache_key]
            self._save_learned_mappings()
            logger.info(f"Removed learned mapping for: '{column_name}'")
            return True
        return False

    def clear_learned_mappings(self):
        """Clear all learned mappings (reset cache)"""
        self.learned_mappings = {}
        self._save_learned_mappings()
        logger.info("Cleared all learned mappings")

    def get_learned_mappings_stats(self) -> Dict[str, Any]:
        """
        Get statistics about learned mappings.

        Returns:
            Dict with cache statistics
        """
        if not self.learned_mappings:
            return {
                "total_mappings": 0,
                "cache_file": str(LEARNED_MAPPINGS_FILE),
                "cache_exists": LEARNED_MAPPINGS_FILE.exists()
            }

        # Count by category
        by_category = {}
        for col, mapping in self.learned_mappings.items():
            category = mapping.get("category", "unknown")
            by_category[category] = by_category.get(category, 0) + 1

        # Average confidence
        confidences = [m.get("confidence", 0) for m in self.learned_mappings.values()]
        avg_confidence = sum(confidences) / len(confidences) if confidences else 0

        return {
            "total_mappings": len(self.learned_mappings),
            "by_category": by_category,
            "average_confidence": round(avg_confidence, 3),
            "cache_file": str(LEARNED_MAPPINGS_FILE),
            "cache_exists": LEARNED_MAPPINGS_FILE.exists()
        }

    def export_learned_mappings(self) -> Dict[str, Dict[str, Any]]:
        """
        Export all learned mappings for backup or transfer.

        Returns:
            Copy of the learned mappings dictionary
        """
        return dict(self.learned_mappings)

    def import_learned_mappings(
        self,
        mappings: Dict[str, Dict[str, Any]],
        overwrite: bool = False
    ) -> int:
        """
        Import learned mappings from an external source.

        Args:
            mappings: Dict of column_name -> mapping info
            overwrite: If True, overwrite existing mappings

        Returns:
            Number of mappings imported
        """
        imported = 0
        for col, mapping in mappings.items():
            cache_key = col.strip()
            if overwrite or cache_key not in self.learned_mappings:
                self.learned_mappings[cache_key] = mapping
                imported += 1

        if imported > 0:
            self._save_learned_mappings()
            logger.info(f"Imported {imported} learned mappings")

        return imported
