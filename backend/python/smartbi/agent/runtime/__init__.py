"""Bounded, read-only SmartBI agent runtime contracts.

This package deliberately exposes no HTTP route and no LLM integration.  It is
the trusted data boundary used by a future restaurant analysis runtime.
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
    "ReadonlyToolRegistry",
    "TrustedExecutionContext",
    "build_restaurant_read_registry",
]
