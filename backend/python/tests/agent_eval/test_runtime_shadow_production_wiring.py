from __future__ import annotations

import re
from contextlib import AbstractAsyncContextManager
from datetime import date
from decimal import Decimal

import pytest

from smartbi.agent.eval import AgentOpsService, InMemoryAgentOpsStore
from smartbi.agent.eval import runtime_shadow as runtime_shadow_module
from smartbi.agent.eval.runtime_shadow import (
    RuntimeShadowBatchRunner,
    RuntimeShadowBounds,
    numeric_truth_ref_key,
)
from smartbi.agent.eval.validation import canonical_digest
from smartbi.agent.runtime.run_store import InMemoryRunStore

from .helpers import config_snapshot, context, request_id


_WRITE_SQL = re.compile(
    r"\b(?:INSERT|UPDATE|DELETE|MERGE|UPSERT|CREATE|ALTER|DROP|TRUNCATE|CALL)\b",
    re.IGNORECASE,
)
_NORMAL_RUNTIME_TABLES = re.compile(
    r"\bsmart_bi_agent_(?:run|event)\b",
    re.IGNORECASE,
)


class _AsyncContext(AbstractAsyncContextManager):
    def __init__(self, value):
        self.value = value

    async def __aenter__(self):
        return self.value

    async def __aexit__(self, exc_type, exc, tb):
        return None


class WriteFailConnection:
    """Small asyncpg-shaped fixture that admits only known Gold reads."""

    def __init__(self):
        self.readonly_transactions = 0
        self.executed_settings: list[tuple[str, tuple[object, ...]]] = []
        self.read_queries: list[tuple[str, str, tuple[object, ...]]] = []
        self.write_attempts: list[str] = []
        self.normal_runtime_table_queries: list[str] = []

    def transaction(self, *, readonly=False):
        if readonly is not True:
            raise AssertionError("runtime shadow must use a read-only transaction")
        self.readonly_transactions += 1
        return _AsyncContext(self)

    def _guard(self, sql: str) -> str:
        normalized = " ".join(sql.split())
        if _NORMAL_RUNTIME_TABLES.search(normalized):
            self.normal_runtime_table_queries.append(normalized)
            raise AssertionError("normal runtime run/event persistence is forbidden")
        if _WRITE_SQL.search(normalized):
            self.write_attempts.append(normalized)
            raise AssertionError("runtime shadow attempted write SQL")
        if not normalized.upper().startswith(("SELECT ", "WITH ")):
            self.write_attempts.append(normalized)
            raise AssertionError("runtime shadow attempted non-read SQL")
        return normalized

    async def execute(self, sql, *args):
        normalized = self._guard(sql)
        if "SET_CONFIG(" not in normalized.upper():
            raise AssertionError(f"unexpected execute SQL: {normalized}")
        self.executed_settings.append((normalized, args))
        return "SELECT 1"

    async def fetchrow(self, sql, *args):
        normalized = self._guard(sql)
        upper = normalized.upper()
        self.read_queries.append(("fetchrow", normalized, args))
        if "SELECT MIN(DATE) AS D0" in upper:
            return {"d0": date(2025, 1, 1), "d1": date(2026, 1, 31)}
        if "COUNT(C.MATERIAL_COST)" in upper:
            start = args[1]
            if start == date(2026, 1, 1):
                return {
                    "revenue": Decimal("1000.00"),
                    "material_cost": Decimal("700.00"),
                    "cost_n": 1,
                    "n_rows": 1,
                }
            if start == date(2025, 12, 1):
                return {
                    "revenue": Decimal("1000.00"),
                    "material_cost": Decimal("600.00"),
                    "cost_n": 1,
                    "n_rows": 1,
                }
            if start == date(2025, 1, 1):
                return {
                    "revenue": Decimal("900.00"),
                    "material_cost": Decimal("540.00"),
                    "cost_n": 1,
                    "n_rows": 1,
                }
        if "SELECT SUM(EST_COST)" in upper:
            return {"req_cost": Decimal("200.00"), "req_n": 1}
        raise AssertionError(f"unexpected fetchrow SQL: {normalized}")

    async def fetch(self, sql, *args):
        normalized = self._guard(sql)
        upper = normalized.upper()
        self.read_queries.append(("fetch", normalized, args))
        if "SELECT S.NAME AS NAME" in upper and "FROM AGG_DAILY A" in upper:
            return [
                {
                    "name": "North",
                    "revenue": Decimal("800.00"),
                    "order_count": 32,
                    "avg_ticket": Decimal("25.00"),
                    "discount_amount": Decimal("40.00"),
                    "gross_amount": Decimal("840.00"),
                }
            ]
        if "WITH GROUPED AS" in upper and "FROM AGG_PRODUCT A" in upper:
            return [
                {
                    "product_id": 7,
                    "name": "Noodles",
                    "qty": Decimal("10.000"),
                    "revenue": Decimal("600.00"),
                    "bill_count": 8,
                    "confidence": None,
                    "source": None,
                    "source_upload_id": None,
                    "prov_field_name": None,
                }
            ]
        raise AssertionError(f"unexpected fetch SQL: {normalized}")


class WriteFailPool:
    def __init__(self):
        self.connection = WriteFailConnection()

    def acquire(self):
        return _AsyncContext(self.connection)


class TrackingInMemoryRunStore(InMemoryRunStore):
    instances: list["TrackingInMemoryRunStore"] = []

    def __init__(self):
        super().__init__()
        self.created_runs = 0
        self.appended_events = 0
        self.terminal_writes = 0
        self.__class__.instances.append(self)

    async def create_run(self, *args, **kwargs):
        self.created_runs += 1
        return await super().create_run(*args, **kwargs)

    async def append_event(self, *args, **kwargs):
        self.appended_events += 1
        return await super().append_event(*args, **kwargs)

    async def compare_and_set_terminal(self, *args, **kwargs):
        self.terminal_writes += 1
        return await super().compare_and_set_terminal(*args, **kwargs)


class TrackingAgentOpsStore(InMemoryAgentOpsStore):
    def __init__(self):
        super().__init__()
        self.allowed_persistence: list[str] = []

    async def create_eval_set(self, context, record):
        self.allowed_persistence.append("agentops_eval_set")
        return await super().create_eval_set(context, record)

    async def save_experiment(self, context, record):
        self.allowed_persistence.append("agentops_experiment")
        return await super().save_experiment(context, record)


def _trusted_runtime_case() -> dict:
    source_run_id = "00000000-0000-4000-8000-000000000042"
    input_snapshot = {
        "startDate": "2026-01-01",
        "endDate": "2026-01-31",
        "storeTopN": 20,
        "dishTopN": 10,
    }
    required_tools = [
        "restaurant_period_comparison_read.v1",
        "restaurant_store_performance_read.v1",
        "restaurant_dish_margin_mix_read.v1",
    ]
    decline_ref = numeric_truth_ref_key(
        tool_name="restaurant_period_comparison_read.v1",
        statement_code="GROSS_MARGIN_DECLINE_OBSERVED",
        metric="gross_marginMomChange",
        dimensions={"comparison": "mom_pct"},
    )
    numeric_refs = {decline_ref: "-10"}
    return {
        "caseId": f"runtime-{source_run_id}",
        "inputSnapshot": input_snapshot,
        "expectedRoute": "GROSS_MARGIN_DECLINE_ATTRIBUTION",
        "requiredTools": required_tools,
        "numericTruthRefs": numeric_refs,
        "maxRounds": 2,
        "maxToolCalls": 3,
        "sourceRunId": source_run_id,
        "evidenceDigests": {
            "inputDigest": canonical_digest(input_snapshot),
            "trajectoryDigest": canonical_digest(required_tools),
            "numericTruthDigest": canonical_digest(numeric_refs),
            "evidenceDigest": "4" * 64,
            "sourceRunDigest": "5" * 64,
        },
    }


@pytest.mark.asyncio
async def test_runtime_shadow_production_wiring_is_server_derived_and_zero_write(
    monkeypatch,
):
    TrackingInMemoryRunStore.instances.clear()
    monkeypatch.setattr(
        runtime_shadow_module, "InMemoryRunStore", TrackingInMemoryRunStore
    )
    pool = WriteFailPool()
    store = TrackingAgentOpsStore()
    trusted_case = _trusted_runtime_case()
    store.seed_runtime_corpus("R001", [trusted_case])
    runner = RuntimeShadowBatchRunner(pool=pool)
    service = AgentOpsService(store, runtime_shadow_runner=runner)
    bounds = RuntimeShadowBounds()

    assert bounds.snapshot() == {
        "maxCases": 20,
        "maxConcurrency": 2,
        "perCaseTimeoutMs": 75_000,
    }

    eval_set = await service.import_runtime_corpus(
        context(),
        request_id=request_id(420),
        name="Production wiring gate",
        version=1,
        description="trusted server runtime corpus",
        max_cases=20,
    )
    experiment = await service.run_runtime_shadow(
        context(),
        request_id=request_id(421),
        eval_set_id=eval_set.eval_set_id,
        config_snapshot=config_snapshot(),
        bounds=bounds,
    )

    actual = experiment.actual_snapshots[trusted_case["caseId"]]
    decline_ref = next(iter(trusted_case["numericTruthRefs"]))
    assert actual["tools"] == trusted_case["requiredTools"]
    assert actual["roundsUsed"] == 2
    assert actual["toolCallsUsed"] == 3
    assert Decimal(actual["numericTruthRefs"][decline_ref]) == Decimal("-10")
    assert experiment.aggregate["passRate"] == "1.000000"

    read_sql = "\n".join(item[1] for item in pool.connection.read_queries)
    assert "agg_daily_cost" in read_sql
    assert "dim_store" in read_sql
    assert "agg_product" in read_sql
    assert pool.connection.readonly_transactions == 3
    assert pool.connection.write_attempts == []
    assert pool.connection.normal_runtime_table_queries == []

    assert len(TrackingInMemoryRunStore.instances) == 1
    runtime_store = TrackingInMemoryRunStore.instances[0]
    assert runtime_store.created_runs == 1
    assert runtime_store.appended_events > 0
    assert runtime_store.terminal_writes == 1
    assert store.allowed_persistence == ["agentops_eval_set", "agentops_experiment"]


@pytest.mark.asyncio
async def test_write_fail_fixture_rejects_erp_mutation_before_execution():
    connection = WriteFailConnection()

    with pytest.raises(AssertionError, match="attempted write SQL"):
        await connection.execute(
            "UPDATE erp_inventory SET quantity = quantity - 1 WHERE id = $1", 7
        )

    assert len(connection.write_attempts) == 1
    assert connection.read_queries == []
