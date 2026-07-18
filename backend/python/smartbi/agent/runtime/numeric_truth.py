"""Numeric truth guard for structured agent outcomes."""

from __future__ import annotations

import re
from typing import Iterable

from .contracts import EvidenceEnvelope
from .run_contracts import NumericClaim, StructuredOutcome


class NumericTruthError(ValueError):
    pass


_NUMERIC_TEXT = re.compile(r"(?<![A-Za-z_])[-+]?\d+(?:\.\d+)?(?:%|\b)")


class NumericTruthGuard:
    """Require every business number to point at an exact evidence fact.

    Human-readable observations intentionally use code-like, number-free text.
    Numeric business content belongs in ``NumericClaim`` only.
    """

    @staticmethod
    def validate_claims(
        claims: Iterable[NumericClaim], evidence: Iterable[EvidenceEnvelope]
    ) -> None:
        fact_index = {
            (envelope.evidence_id, fact.fact_id): fact
            for envelope in evidence
            for fact in envelope.facts
        }
        for claim in claims:
            if not claim.evidence_id or not claim.fact_id:
                raise NumericTruthError("numeric claim requires evidenceId and factId")
            fact = fact_index.get((claim.evidence_id, claim.fact_id))
            if fact is None:
                raise NumericTruthError(
                    "numeric claim references an unknown evidence fact"
                )
            if fact.value is None:
                raise NumericTruthError("numeric claim may not cite a null fact")
            if claim.metric != fact.metric or claim.value != fact.value:
                raise NumericTruthError(
                    "numeric claim must exactly match cited fact metric and value"
                )
            if claim.unit != fact.unit or dict(claim.dimensions) != dict(
                fact.dimensions
            ):
                raise NumericTruthError(
                    "numeric claim unit and dimensions must match cited fact"
                )

    @classmethod
    def validate_outcome(
        cls, outcome: StructuredOutcome, evidence: Iterable[EvidenceEnvelope]
    ) -> None:
        cls.validate_claims(outcome.claims, evidence)
        for text in (*outcome.observations, *outcome.blockers):
            if _NUMERIC_TEXT.search(text):
                raise NumericTruthError(
                    "isolated numeric text is forbidden; use a cited NumericClaim"
                )
