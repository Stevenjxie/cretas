from datetime import datetime, timezone
from pathlib import Path

import pytest

from smartbi.agent.eval.store import (
    AgentOpsAccessError,
    PostgresAgentOpsStore,
)

from .helpers import context


class _Transaction:
    async def __aenter__(self):
        return self

    async def __aexit__(self, exc_type, exc, tb):
        return None


class _Connection:
    def __init__(self, visible_factory="R001"):
        self.visible_factory = visible_factory
        self.executed = []

    def transaction(self, **options):
        assert options == {"readonly": True}
        return _Transaction()

    async def execute(self, sql, *args):
        self.executed.append((sql, args))

    async def fetchrow(self, sql, run_id, factory_id):
        if factory_id != self.visible_factory:
            return None
        now = datetime.now(timezone.utc)
        return {
            "run_id": run_id,
            "factory_id": factory_id,
            "route_code": "GROSS_MARGIN_DECLINE_ATTRIBUTION",
            "state": "COMPLETED",
            "correlation_id": "corr",
            "sanitized_request": {},
            "outcome_summary": None,
            "failure_code": None,
            "rounds_used": 1,
            "tool_calls_used": 1,
            "facts_used": 1,
            "evidence_bytes_used": 1,
            "created_at": now,
            "updated_at": now,
            "completed_at": now,
        }

    async def fetch(self, sql, run_id, factory_id, after_sequence, limit):
        assert after_sequence == 0
        assert limit == 2
        return []


class _Borrow:
    def __init__(self, connection):
        self.connection = connection

    async def __aenter__(self):
        return self.connection

    async def __aexit__(self, exc_type, exc, tb):
        return None


class _Pool:
    def __init__(self, connection):
        self.connection = connection

    def acquire(self):
        return _Borrow(self.connection)


@pytest.mark.asyncio
async def test_trace_binds_exact_d10b_admin_audit_gucs_and_allows_other_owner_admin():
    connection = _Connection()
    store = PostgresAgentOpsStore(_Pool(connection))
    trace = await store.load_trace(
        context("R001", user="other-admin", role="restaurant_manager"),
        "00000000-0000-0000-0000-000000000001",
        after_sequence=0,
        limit=1,
    )
    assert trace["state"] == "COMPLETED"
    assert connection.executed == [
        ("SELECT set_config('app.factory_id',$1,true)", ("R001",)),
        ("SELECT set_config('app.user_id',$1,true)", ("other-admin",)),
        ("SELECT set_config('app.actor_role',$1,true)", ("restaurant_manager",)),
        ("SELECT set_config('app.agent_ops_audit','true',true)", ()),
    ]


@pytest.mark.asyncio
async def test_trace_denies_non_admin_cross_tenant_and_unset_context():
    connection = _Connection()
    store = PostgresAgentOpsStore(_Pool(connection))
    with pytest.raises(AgentOpsAccessError):
        await store.load_trace(
            context(role="operator"), "00000000-0000-0000-0000-000000000001",
            after_sequence=0, limit=1,
        )
    with pytest.raises(AgentOpsAccessError):
        await store.load_trace(
            context("R002"), "00000000-0000-0000-0000-000000000001",
            after_sequence=0, limit=1,
        )
    with pytest.raises(AgentOpsAccessError):
        await store.load_trace(
            context(user=""), "00000000-0000-0000-0000-000000000001",
            after_sequence=0, limit=1,
        )
    assert connection.executed == [
        ("SELECT set_config('app.factory_id',$1,true)", ("R002",)),
        ("SELECT set_config('app.user_id',$1,true)", ("42",)),
        ("SELECT set_config('app.actor_role',$1,true)", ("platform_admin",)),
        ("SELECT set_config('app.agent_ops_audit','true',true)", ()),
    ]


def test_d10b_v05_uses_the_same_admin_audit_guc_contract_when_present():
    migration = (
        Path(__file__).parents[2]
        / "smartbi/database/migrations/V20261028_05__restaurant_agent_owner_enforcement.sql"
    )
    if not migration.exists():
        pytest.skip("D10B V05 is integrated after this scoped branch")
    sql = migration.read_text(encoding="utf-8")
    assert "current_setting('app.agent_ops_audit', true) = 'true'" in sql
    assert "current_setting('app.actor_role', true) IN" in sql
    assert "current_setting('app.user_id', true)" in sql
