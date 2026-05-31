"""AnalysisTemplate — base class for every pre-computed analysis.

Each concrete template:
  1. declares applies(schema) to self-filter by domain/field roles
  2. declares code (stable string ID used in DB + FE)
  3. compute(backend, schema) returns TemplateResult

Keep templates pure-functional: same input → same output (deterministic,
testable, cacheable).
"""
from __future__ import annotations

from abc import ABC, abstractmethod
from dataclasses import dataclass, field
from typing import Any, ClassVar, Dict, List, Optional

from ..compute.base import ComputeBackend
from ..schema import DataSchema


@dataclass
class TemplateResult:
    """Output of a template run.

    Stored in DB as smart_bi_pg_analysis_results row with:
      - analysis_type = f"materialized:{code}"
      - template_code = code
      - domain        = schema.domain.value
      - analysis_result = data
      - chart_configs   = [chart_config] (single chart per template in W1)
      - kpi_values      = kpis
      - insights        = [insight_text]
    """
    code: str
    title: str
    data: Dict[str, Any]                              # primary payload (tables/series)
    chart_config: Optional[Dict[str, Any]] = None     # ECharts option
    kpis: Dict[str, Any] = field(default_factory=dict)
    # Keys are template-specific; no shared schema. Task 14 UI renders per-code.
    insight_text: Optional[str] = None
    # Single sentence; stored as insights=[insight_text] in DB.
    # If a template needs multi-line insights, change to List[str] + update persistence.
    llm_insight: Optional[str] = None
    # Optional richer, LLM-generated insight produced at materialization time
    # (see llm_materializer.py). When present, persistence stores this in place
    # of insight_text so cache hits serve LLM-quality content at 0 query-token.
    # When None (LLM unavailable / failed / partial), the rule insight_text is
    # used as the durable fallback — NEVER store empty/fake content.
    applies: bool = True                              # False = schema doesn't match
    skip_reason: Optional[str] = None
    error: bool = False                               # True = compute() raised (skip_reason has details)


class AnalysisTemplate(ABC):
    """Abstract template. Subclasses must define code + applies + compute.

    Subclasses SHOULD also override `sample_queries` — a list of canonical
    Chinese questions this template can answer. Used at startup to populate
    `smart_bi_template_embeddings`, which the Phase 3 hybrid RAG router
    consults when the keyword match misses. More sample_queries per template
    → better semantic coverage of user wording variations.
    """

    # Phase 3 (Apr 23 2026): sample queries for RAG layer. Default [] means
    # the template relies purely on router keywords (Layer 1). Override with
    # 5-8 realistic user phrasings for best coverage.
    sample_queries: ClassVar[List[str]] = []

    @property
    @abstractmethod
    def code(self) -> str:
        """Stable identifier, e.g., 'top_n_by_dim'. Used as template_code column."""

    @property
    @abstractmethod
    def title(self) -> str:
        """Human-readable title shown in UI and insight text."""

    @abstractmethod
    def applies(self, schema: DataSchema) -> bool:
        """Return True if this template can run against this schema."""

    @abstractmethod
    def compute(
        self, backend: ComputeBackend, schema: DataSchema
    ) -> TemplateResult:
        """Run analysis; return TemplateResult."""

    def run(self, backend: ComputeBackend, schema: DataSchema) -> TemplateResult:
        """Entry point: handles applies() gate + exception wrapping."""
        if not self.applies(schema):
            return TemplateResult(
                code=self.code, title=self.title, data={},
                applies=False, skip_reason="schema does not match",
            )
        try:
            return self.compute(backend, schema)
        except Exception as e:
            return TemplateResult(
                code=self.code, title=self.title, data={},
                applies=False, skip_reason=f"compute error: {e}",
                error=True,  # flag for persistence/observability
            )
