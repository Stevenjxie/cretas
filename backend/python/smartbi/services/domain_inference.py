"""Infer the business domain from the mapped canonical fields (post-mapping).

Replaces the brittle ``detected_table_type`` (prod audit: 94% of uploads were
'general' — the rule classifier doesn't actually discriminate domains). Once the
controlled-vocab mapper has tagged columns with canonical fields, the domain is a
simple vote over which domain those fields are *specific* to (shared dims like
period/category don't discriminate, so they don't vote).
"""
from __future__ import annotations
from collections import defaultdict
from typing import Any, Iterable, Optional

from smartbi.services.domain_standard_fields import DOMAIN_FIELDS, COMMON_FIELDS

# Each domain's *discriminative* fields = its own fields minus the shared dims.
_DISCRIMINATIVE: dict[str, set[str]] = {
    domain: set(fields) - set(COMMON_FIELDS)
    for domain, fields in DOMAIN_FIELDS.items()
}


def infer_domain(field_mappings: Iterable[Any]) -> Optional[str]:
    """Vote the business domain from mapped canonical fields.

    Each mapped ``standard`` field votes for the domain(s) it is specific to;
    the top domain wins. Returns ``None`` when no discriminative field mapped
    (caller falls back to the legacy table_type). ``field_mappings`` items must
    expose a ``standard`` attribute (FieldMapping) or be dicts with 'standard'.
    """
    votes: dict[str, int] = defaultdict(int)
    for m in field_mappings:
        std = m.get("standard") if isinstance(m, dict) else getattr(m, "standard", None)
        if not std:
            continue
        for domain, fields in _DISCRIMINATIVE.items():
            if std in fields:
                votes[domain] += 1
    if not votes:
        return None
    # deterministic tie-break: highest votes, then domain name
    return max(sorted(votes), key=votes.__getitem__)
