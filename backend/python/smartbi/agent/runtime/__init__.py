"""Bounded, read-only SmartBI agent runtime contracts.

This package exposes one internal, bounded HTTP route and no LLM integration.
It remains the trusted data boundary for restaurant analysis runs.
"""

from .contracts import (
    Coverage,
    CoverageStatus,
    DataClassification,
    EvidenceEnvelope,
    EvidenceFact,
    EvidenceStatus,
    Freshness,
    FreshnessStatus,
    ProvenanceReference,
    TrustedExecutionContext,
)
from .gateway import ReadToolGateway
from .bounded_runtime import BoundedRestaurantRuntime
from .run_store import InMemoryRunStore, PostgresRunStore
from .registry import ReadonlyToolRegistry
from .restaurant_read_tools import build_restaurant_read_registry

__all__ = [
    "Coverage",
    "CoverageStatus",
    "DataClassification",
    "EvidenceEnvelope",
    "EvidenceFact",
    "EvidenceStatus",
    "Freshness",
    "FreshnessStatus",
    "ProvenanceReference",
    "ReadToolGateway",
    "BoundedRestaurantRuntime",
    "InMemoryRunStore",
    "PostgresRunStore",
    "ReadonlyToolRegistry",
    "TrustedExecutionContext",
    "build_restaurant_read_registry",
]
