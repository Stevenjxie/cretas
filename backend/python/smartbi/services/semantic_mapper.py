from __future__ import annotations
"""
Semantic Mapper Service

Maps Excel column names to standard business fields using:
1. Rule-based dictionary matching
2. LLM-based semantic understanding
3. Multi-model consensus for ambiguous cases

Part of the Zero-Code SmartBI architecture.
"""
import json
import logging
import re
from dataclasses import dataclass, field
from typing import Any, Dict, List, Optional, Set, Tuple

logger = logging.getLogger(__name__)


@dataclass
class FieldMapping:
    """A single field mapping result"""
    original: str  # Original column name
    standard: Optional[str]  # Mapped standard field name
    confidence: float  # Mapping confidence (0.0-1.0)
    method: str  # rule, llm, multi_model
    category: Optional[str] = None  # Field category (amount, rate, category, time, etc.)
    description: Optional[str] = None  # Human-readable description
    data_type: Optional[str] = None  # A3: NUMERIC/DATE/TEXT (inferred from sample)

    def to_dict(self) -> Dict[str, Any]:
        return {
            "original": self.original,
            "standard": self.standard,
            "confidence": self.confidence,
            "method": self.method,
            "category": self.category,
            "description": self.description,
            "data_type": self.data_type,
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


# Standard field dictionary — single source of truth lives in
# domain_standard_fields.py (per-domain controlled vocab; finance migrated
# faithfully with synonyms + production/sales/purchase/inventory).
# ALL_CANONICAL_FIELDS is the LLM-output whitelist (unmapped -> review queue).
from smartbi.services.domain_standard_fields import (  # noqa: E402
    STANDARD_FIELDS,
)

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

    async def map_fields(
        self,
        columns: List[str],
        sample_data: Optional[List[List[Any]]] = None,
        factory_id: Optional[str] = None,
        table_context: Optional[str] = None,
        business_type: Optional[str] = None
    ) -> SemanticMappingResult:
        """
        Map column names to standard fields.

        Args:
            columns: List of column names to map
            sample_data: Optional sample data for context
            factory_id: Optional factory ID for custom mappings
            table_context: Optional context about the table (e.g., sheet name)
            business_type: Optional industry (restaurant/factory/unknown) for
                hierarchical promoted-rule consult + learning capture scope

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

        # Layer 0: deterministic pin (per factory + template + column). 0-token;
        # on hit skips rule/embedding/LLM entirely → kills the cross-upload drift
        # the prod audit found (same column → different standard_name per upload).
        # Pin tables are FORCE RLS, so the lookup sets app.factory_id in-txn.
        pin_mappings, cols_to_map = await self._map_with_pin(columns, factory_id)
        mappings.extend(pin_mappings)
        if not cols_to_map:
            self._dedupe_standard_names(mappings)
            self._enrich_data_types(mappings, columns, sample_data)
            result.field_mappings = mappings
            result.unmapped_fields = []
            result.confidence = (
                sum(m.confidence for m in mappings) / len(mappings) if mappings else 0.5
            )
            result.method = "pin"
            return result

        # Layer 1: Rule-based mapping (only on pin-missed columns)
        rule_mappings, rule_unmapped = self._map_with_rules(cols_to_map, factory_id)
        mappings.extend(rule_mappings)

        # Layer 1 (promoted): consult graduated aliases before early-return.
        # Hierarchical: industry-branch first, then global trunk (0-token determinism).
        from smartbi.services.learning_promotion import consult_promoted_db
        try:
            from smartbi.config import get_pg_pool
            _promo_pool = await get_pg_pool()
        except Exception:
            _promo_pool = None
        _still_unmapped = []
        for col in rule_unmapped:
            # DB-realtime promoted consult (graduated mappings take effect without
            # a restart); falls back to JSON consult_promoted when pool is None.
            std, hit_method = await consult_promoted_db(
                _promo_pool, "field_mapping", col, business_type
            )
            if std and std in STANDARD_FIELDS:
                fi = STANDARD_FIELDS.get(std, {})
                mappings.append(FieldMapping(
                    original=col, standard=std, confidence=0.97,
                    method=hit_method, category=fi.get("category"),
                    description="graduated rule (promoted_learnings)",
                ))
            else:
                _still_unmapped.append(col)
        rule_unmapped = _still_unmapped

        # Bug #12 fix (Apr 17 2026): only return early when every field got a
        # rule mapping. Previously `all(... for m in mappings)` returned True
        # when mappings=[] (vacuous truth), which short-circuited Layer 2 LLM
        # fallback on all-unmapped files — the exact scenario LLM is meant for.
        if not rule_unmapped:
            # A1+A3: dedupe standard names + infer data_type before returning
            self._dedupe_standard_names(mappings)
            self._enrich_data_types(mappings, columns, sample_data)
            result.field_mappings = mappings
            result.unmapped_fields = rule_unmapped
            result.confidence = sum(m.confidence for m in mappings) / len(mappings) if mappings else 0.5
            result.method = "rule"
            return result

        # Layer 1.5 (C1 MVP, Apr 20 2026): Embedding-first lookup between
        # rules and LLM. Cheap (~50ms cached + one DashScope batch call for
        # all unmapped). Accept cos>=0.82; borderline falls through to LLM.
        if rule_unmapped:
            try:
                from smartbi.services.standard_field_embedder import (
                    find_best_matches_batch,
                )
                # Build per-column samples
                col_idx = {c: i for i, c in enumerate(columns)}
                samples_per_col: Dict[str, List[object]] = {}
                if sample_data:
                    for col in rule_unmapped:
                        idx = col_idx.get(col)
                        if idx is None:
                            continue
                        samples_per_col[col] = [
                            row[idx] for row in sample_data[:3] if idx < len(row)
                        ]

                matches = await find_best_matches_batch(
                    rule_unmapped, samples_per_col, top_k=2
                )
                emb_mapped: Set[str] = set()
                # DashScope text-embedding-v3 cosine distribution is narrow
                # on Chinese financial terms: good matches are typically
                # 0.60-0.75. We accept top >= 0.65 AND margin(top - 2nd) >= 0.03
                # to reduce false positives. Borderline (0.55-0.65) falls
                # through to LLM which has broader semantic understanding.
                EMB_MIN = 0.65
                EMB_MARGIN = 0.03
                for col, hits in matches.items():
                    if not hits:
                        continue
                    std, cat, score = hits[0]
                    margin = (
                        score - hits[1][2] if len(hits) > 1 else score
                    )
                    if score >= EMB_MIN and margin >= EMB_MARGIN:
                        field_info = STANDARD_FIELDS.get(std, {})
                        mappings.append(FieldMapping(
                            original=col,
                            standard=std,
                            confidence=min(0.95, score),
                            method="embedding",
                            category=cat or field_info.get("category"),
                            description=f"embedding cos={score:.3f} margin={margin:.3f}",
                        ))
                        emb_mapped.add(col)
                        logger.info(
                            f"[embedder] {col!r} → {std} cos={score:.3f} margin={margin:.3f}"
                        )
                    else:
                        logger.debug(
                            f"[embedder] {col!r} too weak: top={std}@{score:.3f} margin={margin:.3f} — fall to LLM"
                        )
                rule_unmapped = [c for c in rule_unmapped if c not in emb_mapped]
            except Exception as e:
                # Embedding layer is opportunistic — never block LLM fallback
                logger.warning(f"[embedder] skipped: {e}")

        # Layer 2: LLM mapping for unmapped fields
        if rule_unmapped:
            llm_mappings = await self._map_with_llm(rule_unmapped, columns, sample_data, table_context)
            mappings.extend(llm_mappings)

            # Update unmapped list
            llm_mapped = {m.original for m in llm_mappings if m.standard}
            unmapped = [c for c in rule_unmapped if c not in llm_mapped]

        # Check overall confidence
        avg_confidence = sum(m.confidence for m in mappings) / len(mappings) if mappings else 0.0

        if (
            avg_confidence < self.settings.semantic_mapping_confidence_threshold
            and unmapped
            and getattr(self.settings, "enable_mapper_multi_model", False)
        ):
            # Layer 3: Multi-model enhancement (2 LLM calls — opt-in via
            # enable_mapper_multi_model, default off since D2 Apr 17 2026)
            enhanced_mappings = await self._map_with_multi_model(
                unmapped, columns, sample_data, table_context
            )
            mappings.extend(enhanced_mappings)
            unmapped = [c for c in unmapped if c not in {m.original for m in enhanced_mappings if m.standard}]

        # A1+A3: dedupe standard names + infer data_type before returning
        self._dedupe_standard_names(mappings)
        self._enrich_data_types(mappings, columns, sample_data)

        result.field_mappings = mappings
        result.unmapped_fields = unmapped
        result.confidence = sum(m.confidence for m in mappings) / len(mappings) if mappings else 0.5
        result.method = "combined"

        # Capture non-rule mappings (embedding/llm) as self-learning candidates.
        # best-effort: wrapped in try/except so any DB/pool error never blocks upload.
        try:
            from smartbi.services.learning_promotion import capture_candidate
            from smartbi.config import get_pg_pool
            _pool = await get_pg_pool()
            _bt = business_type or "unknown"
            for m in mappings:
                if m.method in ("embedding", "llm") and m.standard:
                    await capture_candidate(
                        _pool, "field_mapping", m.original, m.standard,
                        factory_id, m.method, m.confidence, business_type=_bt,
                    )
        except Exception as e:
            logger.warning("field-mapping capture skipped (ignored): %s", e)

        return result

    @staticmethod
    def _compute_template_key(columns: List[str]) -> str:
        """Stable fingerprint of the column SET (order-independent). Same file
        shape → same key → pin scoped per (factory, template). Lets one factory
        carry different mappings for different upload formats (audit C1: same
        column name legitimately means different things across templates)."""
        import hashlib
        normalized = "|".join(sorted(c.strip().lower()[:40] for c in columns if c))
        return hashlib.sha256(normalized.encode("utf-8")).hexdigest()[:16]

    async def _map_with_pin(
        self, columns: List[str], factory_id: Optional[str] = None
    ) -> Tuple[List[FieldMapping], List[str]]:
        """Layer 0: deterministic pin lookup → (pin_mappings, unmapped_columns).

        Reads smart_bi_pin_mappings keyed by (factory_id, template_key, column).
        Tenant-isolated: the table is FORCE RLS, so app.factory_id is set inside
        the transaction (else 0 rows —
        feedback_asyncpg_rls_guc_must_be_in_transaction). One batched query, not
        per-column. Fail-open: no factory_id / no pool / error → all unmapped so
        downstream rule/embedding/LLM still run.
        """
        if not factory_id or not columns:
            return [], list(columns)
        try:
            from smartbi.config import get_pg_pool
            pool = await get_pg_pool()
        except Exception:
            pool = None
        if pool is None:
            return [], list(columns)

        template_key = self._compute_template_key(columns)
        norm = {c: c.strip().lower() for c in columns}
        try:
            async with pool.acquire() as conn:
                async with conn.transaction():
                    await conn.execute(
                        "SELECT set_config('app.factory_id', $1, true)", factory_id
                    )
                    rows = await conn.fetch(
                        """SELECT column_name, standard_name, confidence
                             FROM smart_bi_pin_mappings
                            WHERE factory_id = $1 AND template_key = $2
                              AND column_name = ANY($3::text[])""",
                        factory_id, template_key, list(set(norm.values())),
                    )
        except Exception as e:
            logger.warning("[pin] lookup failed, falling through to rules: %s", e)
            return [], list(columns)

        pinned = {r["column_name"]: r for r in rows}
        pin_mappings: List[FieldMapping] = []
        unmapped: List[str] = []
        for col in columns:
            r = pinned.get(norm[col])
            if r and r["standard_name"] in STANDARD_FIELDS:
                fi = STANDARD_FIELDS.get(r["standard_name"], {})
                pin_mappings.append(FieldMapping(
                    original=col,
                    standard=r["standard_name"],
                    confidence=float(r["confidence"]),
                    method="pin",
                    category=fi.get("category"),
                    description="pinned (deterministic)",
                ))
            else:
                unmapped.append(col)
        if pin_mappings:
            logger.info(
                "[pin] %d/%d columns hit pin (factory=%s template=%s)",
                len(pin_mappings), len(columns), factory_id, template_key,
            )
        return pin_mappings, unmapped

    def _map_with_rules(
        self, columns: List[str], factory_id: Optional[str] = None
    ) -> Tuple[List[FieldMapping], List[str]]:
        """
        Rule-based field mapping (Layer 1).

        D1 enhancement (Apr 16 2026): Priority regex layer classifies columns
        by Chinese suffix patterns BEFORE synonym matching. Fixes the bug where
        "单卖数量(不含套餐子商品)" wrongly matched `product` category because
        the column text happened to contain "商品".
        """
        mappings = []
        unmapped = []

        # Check custom mappings first
        custom_map = self._custom_mappings.get(factory_id, {}) if factory_id else {}

        for col in columns:
            col_cleaned = self._clean_column_name(col)

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

            # D1 Layer 0: Priority regex patterns (Chinese semantic suffixes)
            # High-confidence classification that wins over substring-matching synonyms
            priority = self._classify_by_priority_regex(col)
            if priority is not None:
                category, std_name, confidence = priority
                if category == 'skip':
                    # Unnamed: * columns — don't save field_def at all
                    continue
                mappings.append(FieldMapping(
                    original=col,
                    standard=std_name,
                    confidence=confidence,
                    method="regex_priority",
                    category=category,
                    description=f"Matched priority pattern → {category}"
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

    def _dedupe_standard_names(self, mappings: List[FieldMapping]) -> None:
        """
        A1 (Apr 20 2026): Ensure each `standard` name is unique across mappings.
        N-th occurrence of the same standard gets suffix `_2`, `_3`, ...

        Why: Java saveFieldDefinitions uses (upload_id, field_name=standard) as
        natural key; collisions silently overwrite earlier mappings, leaving
        only the last column's data_type/semantic_type in DB. This breaks
        chart_recommender which reads per-column type to decide x/y axis.

        Mutates mappings in place.
        """
        usage: Dict[str, int] = {}
        for m in mappings:
            if not m.standard:
                continue
            base = m.standard
            count = usage.get(base, 0)
            if count > 0:
                m.standard = f"{base}_{count + 1}"
            usage[base] = count + 1

    def _enrich_data_types(
        self,
        mappings: List[FieldMapping],
        columns: List[str],
        sample_data: Optional[List[List[Any]]],
    ) -> None:
        """
        A3 (Apr 20 2026): Infer `data_type` (NUMERIC/DATE/TEXT) from sample
        values and attach to each FieldMapping.

        Java `saveFieldDefinitions` reads `mapping.getDataType()`; when this is
        null, downstream cross_sheet_aggregator / chart_recommender get
        empty `data_type` columns, so measure vs dimension classification
        collapses to defaults (everything becomes CATEGORICAL).

        Mutates mappings in place.
        """
        if not sample_data or not columns:
            return
        col_idx = {c: i for i, c in enumerate(columns)}
        for m in mappings:
            if m.data_type is not None:
                continue
            idx = col_idx.get(m.original)
            if idx is None:
                continue
            values = [row[idx] for row in sample_data if idx < len(row)]
            m.data_type = self._infer_data_type(values)

    @staticmethod
    def _infer_data_type(values: List[Any]) -> str:
        """Infer NUMERIC / DATE / TEXT from a list of raw sample values."""
        if not values:
            return "TEXT"
        non_null = [
            v for v in values
            if v is not None and str(v).strip() not in ("", "nan", "NaN", "None", "null")
        ]
        if not non_null:
            return "TEXT"

        # NUMERIC: >=80% of values parse as float after stripping ,/%/¥/空白
        numeric_count = 0
        for v in non_null:
            s = str(v).replace(",", "").replace("%", "").replace("¥", "").strip()
            try:
                float(s)
                numeric_count += 1
            except (ValueError, TypeError):
                pass
        if numeric_count / len(non_null) >= 0.8:
            return "NUMERIC"

        # DATE: >=60% match common Chinese/ISO date patterns
        date_pattern = re.compile(
            r"^\d{4}[-/年]\d{1,2}[-/月]\d{1,2}日?$|"
            r"^\d{4}[-/]\d{1,2}月?$|"
            r"^\d{4}年?$|"
            r"^\d{1,2}[-/]\d{1,2}$"
        )
        date_count = sum(1 for v in non_null if date_pattern.match(str(v).strip()))
        if date_count / len(non_null) >= 0.6:
            return "DATE"

        return "TEXT"

    @staticmethod
    def _norm_for_exact(s: str) -> str:
        """Normalise a column/synonym string for exact-match comparison.

        Steps (in order):
        1. Strip trailing parenthesised unit suffixes — both ASCII ``(...)``
           and fullwidth ``（...）`` — e.g. ``(元)`` / ``（kg）`` / ``(元/kg)``.
           This is done first so that ``营业收入(元)`` → ``营业收入`` before
           the subsequent steps run.
        2. Strip underscores, hyphens, and whitespace.
        3. Lowercase.

        Only *exact* equality is tested downstream; substring containment is
        never used (preserves D1 protection).
        """
        # Remove trailing parenthesised unit annotation (greedy last match)
        s = re.sub(r'[\(（][^\)）]*[\)）]\s*$', '', s)
        # Strip separators and normalise case
        s = re.sub(r'[_\-\s]+', '', s)
        return s.lower()

    def _exact_canonical_for(
        self,
        col: str,
        categories: set,
    ) -> Optional[Tuple[str, str]]:
        """Return ``(category, canonical_std_name)`` when *col* is an exact
        synonym of a canonical whose ``category`` ∈ *categories*; else None.

        Exact matching is performed after ``_norm_for_exact`` normalisation on
        both *col* and each synonym (strips trailing unit suffixes + separators +
        lowercases).  No substring containment is ever tested — this preserves
        the D1 protection (e.g. ``单卖数量(不含套餐子商品)`` → stripped to
        ``单卖数量不含套餐子商品``, which is not equal to any synonym for
        ``sales_quantity`` / ``output_quantity`` etc.).
        """
        col_norm = self._norm_for_exact(col)
        for std_name, field_info in STANDARD_FIELDS.items():
            if field_info.get("category") not in categories:
                continue
            for syn in field_info["synonyms"]:
                if col_norm == self._norm_for_exact(syn):
                    return (field_info["category"], std_name)
        return None

    def _classify_by_priority_regex(self, col: str) -> Optional[Tuple[str, str, float]]:
        """
        D1 priority classifier based on Chinese semantic suffix patterns.

        Returns (category, standard_name, confidence) if a priority pattern
        matches; None if caller should fall through to synonym matching.

        Category values mapping to Java is_measure/is_dimension/is_time:
        - 'amount' / 'rate' → is_measure=true
        - 'time'            → is_time=true
        - 'category'        → is_dimension=true
        - 'skip'            → don't save field_def (for Unnamed: * columns)
        """
        # Skip obviously broken columns (Unnamed: 0..N — means header row was wrong)
        if re.match(r'^Unnamed:\s*\d+$', col, re.IGNORECASE):
            return ('skip', '_skip_unnamed', 1.0)

        # A2 (Apr 20 2026): 收入分组/销售类型 等 "金额/数量 词 + 分组/类型" 组合
        # 必须优先于 amount regex, 否则 "收入" 先命中 measure 导致分组字段被当度量.
        if re.search(r'(收入|营业|销售|金额|数量|成本|利润).{0,3}(分组|组别|类型|分类|维度)', col):
            return ('category', 'revenue_group', 0.9)

        # Measure — amount patterns (金额/数量/总额 等后缀)
        # Suffix match at end of token or before (
        if re.search(
            r'(金额|金额合计|数量|件数|份数|人次|人数|销量|产量|出货量|入库量|'
            r'成本|利润|毛利|净利|营收|营业额|销售额|收入|支出|费用|税额|'
            r'单价|均价|总价|总额|总量|合计|总计|小计|'
            r'实收|实退|实付|应收|应付|分摊|折扣|优惠)(\(|（|\s|$)',
            col
        ):
            # Before falling back to the identity (col-preservation) pattern,
            # check for an exact synonym match in the amount/quantity canonical
            # dictionary.  This promotes e.g. "营业收入(元)" → revenue and
            # "采购量(kg)" → purchase_quantity with their specific canonical names,
            # enabling infer_domain to recognise the domain ("finance", "purchase",
            # etc.) instead of receiving an empty string.
            #
            # #291 protection is preserved: the exact-match helper returns the
            # *specific* canonical for each column (not a single shared name), so
            # adjacent [数量, 金额] columns remain distinct after mapping.
            # D1 protection is preserved: unit-suffix stripping in _norm_for_exact
            # + strict equality (no substring) means "单卖数量(不含套餐子商品)"
            # normalises to "单卖数量不含套餐子商品" which equals no synonym →
            # identity fallback is used, not product/sales_quantity.
            m = self._exact_canonical_for(col, {"amount", "quantity"})
            if m:
                canon_cat, canon_std = m
                return (canon_cat, canon_std, 0.92)
            # Identity fallback — isMeasure comes from NUMERIC dtype + keywords.
            return ('amount', col, 0.92)

        # Measure — rate patterns (率/占比/系数)
        if re.search(r'(率|比例|占比|系数|百分比|比率)(\(|（|\s|$)', col):
            # Use the shared exact-canonical helper (same logic as amount branch)
            # so rate canonicals with unit suffixes also normalise correctly.
            # D1's substring-protection is preserved: helper uses _norm_for_exact
            # + strict equality only (no containment).
            m = self._exact_canonical_for(col, {"rate"})
            if m:
                canon_cat, canon_std = m
                return (canon_cat, canon_std, 0.92)
            # No precise synonym found → generic fallback (isMeasure=true via Java .*rate.*)
            return ('rate', 'rate_percent', 0.92)

        # Measure — rating patterns (评分相关, sample val 0-5 or 0-100)
        # Allow optional `分` suffix (e.g. "星级分") and allow any boundary
        if re.search(r'(星级|口味|环境|服务|评)分?(\(|（|\s|$)', col) or \
           re.search(r'(评分|分数|打分|评级)(\(|（|\s|$)', col):
            # Apr 24 2026: preserve original Chinese col name so review templates'
            # applies() can resolve "星级"/"口味分"/etc. Python field_classifier
            # NUMERIC-dtype fallback + explicit measure keywords classify this as
            # measure without needing the "amount_score" rename shim for Java.
            return ('amount', col, 0.88)

        # Time patterns
        if re.search(
            r'(时间|日期|月份|年份|季度|周次|开单|下单|业务日|交易时间|'
            r'创建时间|最新.*时间|投诉时间|更新时间|起始.*时间|结束.*时间)',
            col
        ):
            # Standard name includes "time" → Java isTimeField matches
            return ('time', 'time_period', 0.92)

        # Category — ID/code patterns (numeric but should be dim)
        if re.search(r'(编码|编号|代码|ID|id)$', col):
            # Apr 24 2026: preserve original. field_classifier._ID_LIKE_KEYWORDS
            # already catches this class + marks is_dimension=true.
            return ('category', col, 0.9)

        # Category — known dimensional patterns (名称/分类/类型/门店/商品/标签)
        if re.search(
            r'(名称|分类|类型|类别|门店|店铺|商品名|产品名|品牌|渠道|'
            r'平台|来源|分组|组别|等级|城市|省份|区域|地区|标签)(\(|（|\s|$)',
            col
        ) and not re.search(r'(金额|数量|率|量|额|分)', col):
            # Apr 24 2026: preserve original. field_classifier._DIMENSION_KEYWORDS
            # resolves is_dimension regardless of Chinese vs English name.
            return ('category', col, 0.85)

        return None

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

    async def _map_with_llm(
        self,
        unmapped_columns: List[str],
        all_columns: List[str],
        sample_data: Optional[List[List[Any]]],
        table_context: Optional[str]
    ) -> List[FieldMapping]:
        """
        LLM-based field mapping (Layer 2).

        Bug #13 (Apr 17 2026): cache by (unmapped + all_columns + table_context)
        — deterministic identity across files with same headers. Sample_data
        is in the prompt for LLM context but NOT part of cache key (per-file
        data rows vary).
        """
        if not unmapped_columns:
            return []

        # Bug #13: structural cache key — same headers → same mapping regardless of data
        from common.llm_cache import cache_get, cache_put, cache_key
        mapper_key = cache_key(
            "|".join(sorted(unmapped_columns)) + "||" +
            "|".join(sorted(all_columns[:50])) + "||" +
            (table_context or ""),
            slot="mapper_structural"
        )
        cached_result = cache_get(mapper_key)
        if cached_result is not None:
            logger.info(f"[llm_cache] HIT mapper_structural, hash={mapper_key[:8]}, skipping LLM")
            # Rebuild FieldMapping objects from cached (list of dicts)
            result = []
            for m in cached_result:
                std = m.get("standard")
                field_info = STANDARD_FIELDS.get(std, {}) if std else {}
                result.append(FieldMapping(
                    original=m["original"],
                    standard=std,
                    confidence=m.get("confidence", 0.7),
                    method="llm_cached",
                    category=field_info.get("category"),
                    description=m.get("reasoning"),
                ))
            return result

        try:
            # Prepare context
            available_standards = list(STANDARD_FIELDS.keys())

            # Sample data context
            sample_text = ""
            if sample_data and len(sample_data) > 0:
                sample_text = "\nSample data:\n"
                for i, row in enumerate(sample_data[:3]):
                    row_vals = [str(v) if v else "" for v in row[:10]]
                    sample_text += f"  Row {i}: {' | '.join(row_vals)}\n"

            # Detect wide-format data
            wide_format_instruction = self._build_wide_format_instruction(all_columns)

            prompt = f"""Map these column names to standard business field names.

Columns to map: {unmapped_columns}
All columns in table: {all_columns[:20]}
Available standard fields: {available_standards}
Table context: {table_context or 'Unknown'}
{sample_text}
{wide_format_instruction}

For each column, determine the most appropriate standard field.
If no match, return null for standard.

Return JSON only:
{{
  "mappings": [
    {{"original": "column_name", "standard": "standard_field_or_null", "confidence": 0.0-1.0, "reasoning": "brief reason"}}  # noqa: E501
  ]
}}"""

            response = await self._call_llm(prompt)
            if response:
                parsed = self._parse_json_response(response)
                if parsed and "mappings" in parsed:
                    result = []
                    for m in parsed["mappings"]:
                        std = m.get("standard")
                        if std and std != "null" and std in STANDARD_FIELDS:
                            field_info = STANDARD_FIELDS[std]
                            result.append(FieldMapping(
                                original=m["original"],
                                standard=std,
                                confidence=m.get("confidence", 0.7),
                                method="llm",
                                category=field_info.get("category"),
                                description=m.get("reasoning")
                            ))
                        else:
                            # Reject routing: LLM gave no valid canonical field
                            # (null or off-whitelist free text). Don't write garbage
                            # (the old 数量金额_N problem) — flag for the review queue.
                            result.append(FieldMapping(
                                original=m["original"],
                                standard=None,
                                confidence=m.get("confidence", 0.5),
                                method="review_needed",
                                description=(
                                    f"LLM suggested {std!r} (not in controlled vocab); "
                                    "queued for human review"
                                    if std and std != "null"
                                    else "no canonical match; queued for human review"
                                ),
                            ))
                    # Bug #13: cache structural result (columns-only key)
                    cache_put(mapper_key, parsed["mappings"])
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
        """
        # Chinese month pattern (1月, 2月, ... 12月)
        chinese_month_pattern = re.compile(r'[_]?(1[0-2]|[1-9])月[_]?')
        # English/numeric month pattern (202501, 2025-01)
        numeric_month_pattern = re.compile(r'\d{4}[-_]?\d{2}')

        chinese_month_count = sum(1 for col in columns if chinese_month_pattern.search(col))
        numeric_month_count = sum(1 for col in columns if numeric_month_pattern.search(col))

        return chinese_month_count >= 3 or numeric_month_count >= 3

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

2. **年份识别**:
   - 列名有年份（2025年、25年）则使用该年份
   - 无年份信息默认使用2025

3. **metric_type对照**:
   - 预算/计划/目标 → budget_amount
   - 实际/完成/执行 → actual_amount
   - 同期/去年/24年 → last_year_actual
   - 利润/净利 → net_profit
   - 收入/营收 → revenue
   - 成本 → cost

4. **年度汇总列**: "年度汇总"/"全年"/"累计" → ytd_actual 或 ytd_budget
"""

    async def _call_llm(self, prompt: str, model: str = "default") -> Optional[str]:
        """
        Call LLM via multi-provider router (D2, Apr 17 2026).
        Bug #13 (Apr 17 2026): cache identical prompt → response for 24h.

        Routes through `call_chain(SLOT.MAPPER)` so mapper shares the
        aliyun_b → aliyun_a → zhipu → deepseek free-first chain and every
        request is captured by smart_bi_llm_usage via the response hook.
        """
        try:
            from common.llm_router import call_chain, SLOT
            from common.llm_metrics import llm_caller_context
            from common.llm_cache import cache_get, cache_put, cache_key

            # Bug #13: dedup identical prompts within 24h TTL
            ckey = cache_key(prompt, slot="mapper")
            cached = cache_get(ckey)
            if cached is not None:
                logger.info(f"[llm_cache] HIT mapper, prompt_hash={ckey[:8]}, skipping LLM")
                return cached

            payload = {
                "messages": [{"role": "user", "content": prompt}],
                "temperature": 0.1,
                "max_tokens": 1000,
            }

            with llm_caller_context("semantic_mapper"):
                resp_json = await call_chain(SLOT.MAPPER, payload)

            choices = resp_json.get("choices") or []
            if not choices:
                logger.warning("LLM response has no choices")
                return None
            content = choices[0].get("message", {}).get("content")
            if content:
                cache_put(ckey, content)
            return content

        except Exception as e:
            logger.error(f"LLM call failed: {e}")
            return None

    def _parse_json_response(self, response: str) -> Optional[Dict]:
        """Parse JSON from LLM response"""
        try:
            # Try to extract JSON from response
            json_match = re.search(r'\{[\s\S]*\}', response)
            if json_match:
                return json.loads(json_match.group())
            return None
        except json.JSONDecodeError as e:
            logger.warning(f"Failed to parse LLM response as JSON: {e}")
            return None
