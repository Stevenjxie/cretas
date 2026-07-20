"""Deterministic, fail-closed canary policy for AgentOps Runtime Shadow."""

from __future__ import annotations

import hashlib
import os
from dataclasses import dataclass
from typing import Mapping, Optional

from smartbi.agent.eval.contracts import AgentOpsContext


MASTER_ENABLED_ENV = "AGENT_OPS_RUNTIME_SHADOW_ENABLED"
FACTORY_ALLOWLIST_ENV = "AGENT_OPS_RUNTIME_SHADOW_FACTORY_ALLOWLIST"
ROLE_ALLOWLIST_ENV = "AGENT_OPS_RUNTIME_SHADOW_ROLE_ALLOWLIST"
SAMPLE_BPS_ENV = "AGENT_OPS_RUNTIME_SHADOW_SAMPLE_BPS"
ROLLOUT_SALT_ENV = "AGENT_OPS_RUNTIME_SHADOW_ROLLOUT_SALT"

_TRUTHY = frozenset({"1", "true", "yes", "on"})
_BUCKET_COUNT = 10_000
_HASH_NAMESPACE = "agent_ops_runtime_shadow"


@dataclass(frozen=True)
class RuntimeShadowRolloutPolicy:
    """Immutable environment snapshot used for one request decision.

    Invalid or incomplete configuration deliberately becomes a denial instead of
    widening the rollout. Factory identifiers remain case-sensitive; roles are
    normalized to lowercase at both configuration and decision boundaries.
    """

    master_enabled: bool
    factory_allowlist: frozenset[str]
    role_allowlist: frozenset[str]
    sample_bps: Optional[int]
    rollout_salt: str

    @classmethod
    def from_environ(
        cls, environ: Optional[Mapping[str, str]] = None
    ) -> "RuntimeShadowRolloutPolicy":
        values = os.environ if environ is None else environ
        return cls(
            master_enabled=values.get(MASTER_ENABLED_ENV, "").strip().lower()
            in _TRUTHY,
            factory_allowlist=_csv_values(
                values.get(FACTORY_ALLOWLIST_ENV, ""), lowercase=False
            ),
            role_allowlist=_csv_values(
                values.get(ROLE_ALLOWLIST_ENV, ""), lowercase=True
            ),
            sample_bps=_sample_bps(values.get(SAMPLE_BPS_ENV, "")),
            rollout_salt=values.get(ROLLOUT_SALT_ENV, "runtime-shadow-v1").strip(),
        )

    @property
    def is_configured(self) -> bool:
        return (
            bool(self.factory_allowlist)
            and bool(self.role_allowlist)
            and self.sample_bps is not None
            and self.sample_bps > 0
            and bool(self.rollout_salt)
        )

    def bucket_for(self, context: AgentOpsContext) -> int:
        material = "|".join(
            (
                _HASH_NAMESPACE,
                context.factory_id,
                context.user_id,
                context.role.lower(),
                self.rollout_salt,
            )
        )
        digest = hashlib.sha256(material.encode("utf-8")).hexdigest()
        return int(digest[:8], 16) % _BUCKET_COUNT

    def allows(self, context: AgentOpsContext) -> bool:
        if not self.master_enabled or not self.is_configured:
            return False
        if not _matches(self.factory_allowlist, context.factory_id):
            return False
        if not _matches(self.role_allowlist, context.role.lower()):
            return False
        # sample_bps is known to be a positive, bounded integer here.
        return self.bucket_for(context) < int(self.sample_bps)


def _csv_values(raw: str, *, lowercase: bool) -> frozenset[str]:
    values = (item.strip() for item in raw.split(","))
    if lowercase:
        return frozenset(item.lower() for item in values if item)
    return frozenset(item for item in values if item)


def _sample_bps(raw: str) -> Optional[int]:
    try:
        value = int(raw.strip())
    except (TypeError, ValueError):
        return None
    return value if 0 <= value <= _BUCKET_COUNT else None


def _matches(allowlist: frozenset[str], value: str) -> bool:
    return "*" in allowlist or value in allowlist
