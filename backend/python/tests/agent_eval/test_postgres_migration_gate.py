"""Opt-in disposable PostgreSQL gate for the D10C migration.

Run only against a loopback disposable database::

    AGENT_OPS_PG_DSN=postgresql://postgres:...@127.0.0.1:55432/postgres \
    AGENT_OPS_PG_CONFIRM=YES python -m pytest -q \
      tests/agent_eval/test_postgres_migration_gate.py
"""

from __future__ import annotations

import asyncio
import json
import os
import uuid
from pathlib import Path
from urllib.parse import quote, urlsplit, urlunsplit

import pytest

from smartbi.agent.eval import (
    AgentOpsService,
    OfflineBatchRunner,
    PostgresAgentOpsStore,
    RunnerBounds,
    RuntimeShadowBounds,
)
from smartbi.agent.eval.runner import aggregate_case_results
from smartbi.agent.eval.store import AgentOpsConflictError
from smartbi.agent.eval.validation import canonical_digest, validate_cases

from .helpers import actual, case, config_snapshot, context, request_id


asyncpg = pytest.importorskip("asyncpg")

PG_DSN = os.getenv("AGENT_OPS_PG_DSN")
PG_CONFIRM = os.getenv("AGENT_OPS_PG_CONFIRM")
pytestmark = pytest.mark.skipif(
    not PG_DSN or PG_CONFIRM != "YES",
    reason="set loopback AGENT_OPS_PG_DSN and AGENT_OPS_PG_CONFIRM=YES",
)
MIGRATIONS = Path(__file__).parents[2] / "smartbi/database/migrations"
V04 = MIGRATIONS / "V20261028_04__restaurant_agent_eval_experiments.sql"
V07 = MIGRATIONS / "V20261028_07__agentops_runtime_shadow_constraints.sql"


def _runtime_case():
    refs = {"ref:" + "1" * 64: "12.5"}
    tools = ["restaurant_period_comparison_read.v1"]
    input_snapshot = {
        "startDate": "2026-01-01",
        "endDate": "2026-01-31",
        "storeTopN": 20,
        "dishTopN": 10,
    }
    return {
        "caseId": "runtime-00000000-0000-4000-8000-000000000010",
        "inputSnapshot": input_snapshot,
        "expectedRoute": "GROSS_MARGIN_DECLINE_ATTRIBUTION",
        "requiredTools": tools,
        "numericTruthRefs": refs,
        "maxRounds": 1,
        "maxToolCalls": 1,
        "sourceRunId": "00000000-0000-4000-8000-000000000010",
        "evidenceDigests": {
            "inputDigest": canonical_digest(input_snapshot),
            "trajectoryDigest": canonical_digest(tools),
            "numericTruthDigest": canonical_digest(refs),
            "evidenceDigest": "4" * 64,
            "sourceRunDigest": "5" * 64,
        },
    }


class _RuntimeShadowRunner:
    evaluator_version = "restaurant-runtime-shadow-v1"
    evaluator_build = "a" * 64

    async def run(self, eval_set, trusted_context, *, bounds):
        actuals = {}
        results = []
        for eval_case in eval_set.cases:
            actual_snapshot = {
                "routeCode": eval_case["expectedRoute"],
                "tools": list(eval_case["requiredTools"]),
                "numericTruthRefs": dict(eval_case["numericTruthRefs"]),
                "roundsUsed": 1,
                "toolCallsUsed": 1,
            }
            actuals[eval_case["caseId"]] = actual_snapshot
            results.append(OfflineBatchRunner._evaluate(eval_case, actual_snapshot))
        return aggregate_case_results(results), tuple(results), actuals


def _app_dsn(admin_dsn: str, role: str, password: str) -> str:
    parsed = urlsplit(admin_dsn)
    host = parsed.hostname or "127.0.0.1"
    port = f":{parsed.port}" if parsed.port else ""
    return urlunsplit((
        parsed.scheme,
        f"{role}:{quote(password)}@{host}{port}",
        parsed.path,
        parsed.query,
        parsed.fragment,
    ))


def _render(sql: str, role: str) -> str:
    return sql.replace("smartbi_user", role)


async def _apply(admin, schema: str, sql: str) -> None:
    await admin.execute(f'SET search_path TO "{schema}", public')
    await admin.execute(sql)


@pytest.mark.asyncio
async def test_v04_fresh_and_v01_v02_upgrade_security_contract():
    parsed = urlsplit(PG_DSN)
    if parsed.hostname not in {"127.0.0.1", "localhost", "::1"}:
        pytest.fail("AGENT_OPS_PG_DSN must use a loopback host")

    suffix = uuid.uuid4().hex
    role = f"agentops_app_{suffix}"
    password = uuid.uuid4().hex
    fresh_schema = f"agentops_fresh_{suffix}"
    upgrade_schema = f"agentops_upgrade_{suffix}"
    role_created = False
    fresh_created = False
    upgrade_created = False
    admin = await asyncpg.connect(PG_DSN)
    app = None
    app_pool = None
    try:
        database = str(await admin.fetchval("SELECT current_database()"))
        if database.lower() != "postgres" and "test" not in database.lower():
            pytest.fail("disposable PostgreSQL gate refuses this database name")
        await admin.execute(f'CREATE ROLE "{role}" LOGIN PASSWORD \'{password}\'')
        role_created = True
        await admin.execute(f'CREATE SCHEMA "{fresh_schema}"')
        fresh_created = True
        await admin.execute(f'CREATE SCHEMA "{upgrade_schema}"')
        upgrade_created = True
        await admin.execute(f'GRANT USAGE ON SCHEMA "{fresh_schema}", "{upgrade_schema}" TO "{role}"')

        rendered_v04 = _render(V04.read_text(encoding="utf-8"), role)
        rendered_v07 = _render(V07.read_text(encoding="utf-8"), role)
        await _apply(admin, fresh_schema, rendered_v04)
        await _apply(admin, fresh_schema, rendered_v07)

        await admin.execute(f'SET search_path TO "{upgrade_schema}", public')
        for filename in (
            "V20260426_02__chat_session.sql",
            "V20260427_01__chat_session_v3_history.sql",
            "V20261028_01__smart_bi_agent_run_event.sql",
            "V20261028_02__chat_session_user_identity.sql",
        ):
            await admin.execute(_render((MIGRATIONS / filename).read_text(encoding="utf-8"), role))
        v03 = MIGRATIONS / "V20261028_03__restaurant_agent_adaptive_events.sql"
        v05 = MIGRATIONS / "V20261028_05__restaurant_agent_owner_enforcement.sql"
        trace_contract_applied = v03.exists() and v05.exists()
        if trace_contract_applied:
            await admin.execute(_render(v03.read_text(encoding="utf-8"), role))
        await admin.execute(rendered_v04)
        if trace_contract_applied:
            await admin.execute(_render(v05.read_text(encoding="utf-8"), role))
        await admin.execute(rendered_v07)
        assert await admin.fetchval(
            "SELECT to_regclass('smart_bi_agent_eval_set') IS NOT NULL"
        )

        for schema in (fresh_schema, upgrade_schema):
            columns = {
                (row["table_name"], row["column_name"], row["is_nullable"])
                for row in await admin.fetch(
                    """SELECT table_name,column_name,is_nullable
                       FROM information_schema.columns
                       WHERE table_schema=$1
                         AND table_name IN (
                           'smart_bi_agent_eval_set','smart_bi_agent_experiment'
                         )""",
                    schema,
                )
            }
            assert (
                "smart_bi_agent_eval_set", "request_id", "NO"
            ) in columns
            assert (
                "smart_bi_agent_eval_set", "request_digest", "NO"
            ) in columns
            assert (
                "smart_bi_agent_experiment", "request_id", "NO"
            ) in columns
            assert (
                "smart_bi_agent_experiment", "request_digest", "NO"
            ) in columns
            assert (
                "smart_bi_agent_experiment", "operation_kind", "NO"
            ) in columns
            assert (
                "smart_bi_agent_experiment", "source_experiment_id", "YES"
            ) in columns
            operation_kind_width = await admin.fetchval(
                """SELECT character_maximum_length
                   FROM information_schema.columns
                   WHERE table_schema=$1
                     AND table_name='smart_bi_agent_experiment'
                     AND column_name='operation_kind'""",
                schema,
            )
            assert operation_kind_width == 32

        await admin.execute(f'SET search_path TO "{fresh_schema}", public')
        constraint_defs = "\n".join(
            row["definition"]
            for row in await admin.fetch(
                """SELECT pg_get_constraintdef(oid) AS definition
                   FROM pg_constraint
                   WHERE connamespace = current_schema()::regnamespace
                     AND conrelid IN (
                       'smart_bi_agent_eval_set'::regclass,
                       'smart_bi_agent_experiment'::regclass
                     )"""
            )
        )
        assert "UNIQUE (factory_id, created_by, request_id)" in constraint_defs
        assert "operation_kind" in constraint_defs
        assert "source_experiment_id IS NULL" in constraint_defs
        assert "source_experiment_id IS NOT NULL" in constraint_defs
        assert "FOREIGN KEY (source_experiment_id, factory_id)" in constraint_defs
        assert (
            "REFERENCES smart_bi_agent_experiment(experiment_id, factory_id)"
            in constraint_defs
        )
        assert "source_experiment_id <> experiment_id" in constraint_defs
        assert "RUNTIME_SHADOW" in constraint_defs
        assert "smart_bi_agentops_shadow_bounds_are_safe(runner_bounds)" in constraint_defs

        await admin.execute(f'SET search_path TO "{fresh_schema}", public')
        flags = await admin.fetch(
            """
            SELECT c.relname, c.relrowsecurity, c.relforcerowsecurity
            FROM pg_class c
            JOIN pg_namespace n ON n.oid = c.relnamespace
            WHERE n.nspname = current_schema()
              AND c.relname IN ('smart_bi_agent_eval_set','smart_bi_agent_experiment')
            ORDER BY c.relname
            """
        )
        assert len(flags) == 2
        assert all(row["relrowsecurity"] and row["relforcerowsecurity"] for row in flags)
        assert await admin.fetchval(
            "SELECT has_table_privilege($1, 'smart_bi_agent_eval_set', 'SELECT,INSERT')",
            role,
        )
        assert not await admin.fetchval(
            "SELECT has_table_privilege($1, 'smart_bi_agent_eval_set', 'UPDATE')",
            role,
        )
        upgrade_flags = await admin.fetch(
            """
            SELECT c.relname, c.relrowsecurity, c.relforcerowsecurity
            FROM pg_class c
            JOIN pg_namespace n ON n.oid = c.relnamespace
            WHERE n.nspname = $1
              AND c.relname IN ('smart_bi_agent_eval_set','smart_bi_agent_experiment')
            ORDER BY c.relname
            """,
            upgrade_schema,
        )
        assert len(upgrade_flags) == 2
        assert all(
            row["relrowsecurity"] and row["relforcerowsecurity"]
            for row in upgrade_flags
        )
        await admin.execute(f'SET search_path TO "{upgrade_schema}", public')
        assert await admin.fetchval(
            "SELECT has_table_privilege($1, 'smart_bi_agent_eval_set', 'SELECT,INSERT')",
            role,
        )
        await admin.execute(f'SET search_path TO "{fresh_schema}", public')

        app = await asyncpg.connect(_app_dsn(PG_DSN, role, password))
        await app.execute(f'SET search_path TO "{fresh_schema}", public')
        assert await app.fetchval("SELECT COUNT(*) FROM smart_bi_agent_eval_set") == 0
        cases = [{
            "caseId": "margin-1",
            "expectedRoute": "GROSS_MARGIN_DECLINE_ATTRIBUTION",
            "requiredTools": ["restaurant_margin_read"],
            "numericTruthRefs": {"e1:f1": "1"},
            "maxRounds": 2,
            "maxToolCalls": 2,
        }]
        eval_set_id = str(uuid.uuid4())
        eval_request_id = str(uuid.uuid4())
        normalized_cases = validate_cases(cases)
        digest = canonical_digest(normalized_cases)
        eval_request_digest = canonical_digest({
            "schemaVersion": "1.0",
            "operationKind": "CREATE_EVAL_SET",
            "name": "Margin",
            "version": 1,
            "description": "",
            "cases": normalized_cases,
        })
        async with app.transaction():
            await app.execute("SELECT set_config('app.factory_id','R001',true)")
            await app.execute(
                """
                INSERT INTO smart_bi_agent_eval_set (
                    eval_set_id,factory_id,name,version,cases,content_digest,
                    request_id,request_digest,created_by
                ) VALUES (
                    $1::uuid,'R001','Margin',1,$2::jsonb,$3,$4::uuid,$5,'owner'
                )
                """,
                eval_set_id,
                json.dumps(cases),
                digest,
                eval_request_id,
                eval_request_digest,
            )
            with pytest.raises(asyncpg.UniqueViolationError):
                async with app.transaction():
                    await app.execute(
                        """
                        INSERT INTO smart_bi_agent_eval_set (
                            eval_set_id,factory_id,name,version,cases,content_digest,
                            request_id,request_digest,created_by
                        ) VALUES (
                            $1::uuid,'R001','margin',1,$2::jsonb,$3,$4::uuid,$5,'owner'
                        )
                        """,
                        str(uuid.uuid4()), json.dumps(cases), "2" * 64,
                        str(uuid.uuid4()), "b" * 64,
                    )
            with pytest.raises(asyncpg.UniqueViolationError):
                async with app.transaction():
                    await app.execute(
                        """
                        INSERT INTO smart_bi_agent_eval_set (
                            eval_set_id,factory_id,name,version,cases,content_digest,
                            request_id,request_digest,created_by
                        ) VALUES (
                            $1::uuid,'R001','Different',1,$2::jsonb,$3,$4::uuid,$5,'owner'
                        )
                        """,
                        str(uuid.uuid4()), json.dumps(cases), "3" * 64,
                        eval_request_id, "c" * 64,
                    )
        async with app.transaction():
            await app.execute("SELECT set_config('app.factory_id','R002',true)")
            assert await app.fetchval(
                "SELECT COUNT(*) FROM smart_bi_agent_eval_set WHERE eval_set_id=$1::uuid",
                eval_set_id,
            ) == 0
        async with app.transaction():
            await app.execute("SELECT set_config('app.factory_id','R001',true)")
            with pytest.raises(asyncpg.InsufficientPrivilegeError):
                await app.execute(
                    "UPDATE smart_bi_agent_eval_set SET description='changed' WHERE eval_set_id=$1::uuid",
                    eval_set_id,
                )
        await admin.execute(f'SET search_path TO "{fresh_schema}", public')
        with pytest.raises(asyncpg.RaiseError, match="immutable"):
            await admin.execute(
                "UPDATE smart_bi_agent_eval_set SET description='changed' WHERE eval_set_id=$1::uuid",
                eval_set_id,
            )

        async def initialize_agentops_connection(connection):
            await connection.execute(
                f'SET search_path TO "{fresh_schema}", public'
            )

        app_pool = await asyncpg.create_pool(
            _app_dsn(PG_DSN, role, password),
            min_size=1,
            max_size=4,
            setup=initialize_agentops_connection,
        )
        store = PostgresAgentOpsStore(app_pool)
        service = AgentOpsService(store)

        async def create_atomic(
            ctx=context(), *, name="Atomic", description="same"
        ):
            return await service.create_eval_set(
                ctx,
                request_id=request_id(200),
                name=name,
                version=1,
                description=description,
                cases=[case()],
            )

        atomic, atomic_retry = await asyncio.gather(
            create_atomic(), create_atomic()
        )
        assert atomic_retry.eval_set_id == atomic.eval_set_id
        with pytest.raises(AgentOpsConflictError, match="IDEMPOTENCY_KEY_REUSED"):
            await create_atomic(description="changed")
        with pytest.raises(AgentOpsConflictError, match="EVAL_SET_VERSION_EXISTS"):
            await service.create_eval_set(
                context(), request_id=request_id(201), name="atomic", version=1,
                description="same", cases=[case()],
            )

        actor_scoped = await create_atomic(
            context(user="other-owner"), name="Other actor", description="same"
        )
        tenant_scoped = await create_atomic(context("R002"), description="same")
        assert actor_scoped.eval_set_id != atomic.eval_set_id
        assert tenant_scoped.eval_set_id != atomic.eval_set_id
        r001_ids = {
            item.eval_set_id for item in await service.list_eval_sets(context())
        }
        r002_ids = {
            item.eval_set_id for item in await service.list_eval_sets(context("R002"))
        }
        assert atomic.eval_set_id in r001_ids
        assert tenant_scoped.eval_set_id not in r001_ids
        assert tenant_scoped.eval_set_id in r002_ids
        assert atomic.eval_set_id not in r002_ids

        async def run_atomic(snapshot=actual()):
            return await service.run_experiment(
                context(),
                request_id=request_id(202),
                eval_set_id=atomic.eval_set_id,
                config_snapshot=config_snapshot(),
                actual_by_case={"margin-1": snapshot},
                bounds=RunnerBounds(),
            )

        experiment, experiment_retry = await asyncio.gather(
            run_atomic(), run_atomic()
        )
        assert experiment_retry.experiment_id == experiment.experiment_id
        with pytest.raises(AgentOpsConflictError, match="IDEMPOTENCY_KEY_REUSED"):
            await run_atomic(actual(value="11"))
        with pytest.raises(AgentOpsConflictError, match="IDEMPOTENCY_KEY_REUSED"):
            await service.rerun_experiment(
                context(), experiment.experiment_id,
                request_id=request_id(202),
            )

        async def rerun_atomic():
            return await service.rerun_experiment(
                context(), experiment.experiment_id,
                request_id=request_id(203),
            )

        rerun, rerun_retry = await asyncio.gather(
            rerun_atomic(), rerun_atomic()
        )
        assert rerun_retry.experiment_id == rerun.experiment_id
        assert rerun.snapshot_digest == experiment.snapshot_digest
        assert rerun.operation_kind == "RERUN"
        assert rerun.source_experiment_id == experiment.experiment_id

        shadow_service = AgentOpsService(
            store, runtime_shadow_runner=_RuntimeShadowRunner()
        )
        shadow_eval_set = await shadow_service.create_eval_set(
            context(),
            request_id=request_id(205),
            name="Runtime shadow",
            version=1,
            description="PostgreSQL runtime shadow gate",
            cases=[_runtime_case()],
        )
        shadow_experiment = await shadow_service.run_runtime_shadow(
            context(),
            request_id=request_id(206),
            eval_set_id=shadow_eval_set.eval_set_id,
            config_snapshot=config_snapshot(),
            bounds=RuntimeShadowBounds(
                max_cases=20,
                max_concurrency=2,
                per_case_timeout_seconds=75.0,
            ),
        )
        assert shadow_experiment.operation_kind == "RUNTIME_SHADOW"
        assert shadow_experiment.source_experiment_id is None
        persisted_shadow = await store.get_experiment(
            context(), shadow_experiment.experiment_id
        )
        assert persisted_shadow.experiment_id == shadow_experiment.experiment_id
        assert persisted_shadow.runner_bounds == {
            "maxCases": 20,
            "maxConcurrency": 2,
            "perCaseTimeoutMs": 75000,
        }

        tenant_experiment = await service.run_experiment(
            context("R002"),
            request_id=request_id(204),
            eval_set_id=tenant_scoped.eval_set_id,
            config_snapshot=config_snapshot(),
            actual_by_case={"margin-1": actual()},
            bounds=RunnerBounds(),
        )

        await admin.execute(f'SET search_path TO "{fresh_schema}", public')
        with pytest.raises(asyncpg.CheckViolationError):
            await admin.execute(
                """
                INSERT INTO smart_bi_agent_experiment (
                    experiment_id,factory_id,eval_set_id,eval_set_name,
                    eval_set_version,eval_set_digest,evaluator_version,
                    evaluator_build,snapshot_digest,config_snapshot,
                    actual_snapshots,runner_bounds,aggregate,case_results,
                    request_id,request_digest,operation_kind,
                    source_experiment_id,created_by
                )
                SELECT $1::uuid,factory_id,eval_set_id,eval_set_name,
                       eval_set_version,eval_set_digest,evaluator_version,
                       evaluator_build,snapshot_digest,config_snapshot,
                       actual_snapshots,runner_bounds,aggregate,case_results,
                       $2::uuid,$3,'RUN',experiment_id,created_by
                FROM smart_bi_agent_experiment
                WHERE experiment_id=$4::uuid
                """,
                str(uuid.uuid4()), str(uuid.uuid4()), "d" * 64,
                experiment.experiment_id,
            )

        await admin.execute(f'SET search_path TO "{fresh_schema}", public')

        async def insert_with_operation_and_bounds(
            *, operation_kind, source_experiment_id, runner_bounds
        ):
            await admin.execute(
                """
                INSERT INTO smart_bi_agent_experiment (
                    experiment_id,factory_id,eval_set_id,eval_set_name,
                    eval_set_version,eval_set_digest,evaluator_version,
                    evaluator_build,snapshot_digest,config_snapshot,
                    actual_snapshots,runner_bounds,aggregate,case_results,
                    request_id,request_digest,operation_kind,
                    source_experiment_id,created_by
                )
                SELECT $1::uuid,factory_id,eval_set_id,eval_set_name,
                       eval_set_version,eval_set_digest,evaluator_version,
                       evaluator_build,snapshot_digest,config_snapshot,
                       actual_snapshots,$2::jsonb,aggregate,case_results,
                       $3::uuid,$4,$5,$6::uuid,created_by
                FROM smart_bi_agent_experiment
                WHERE experiment_id=$7::uuid
                """,
                str(uuid.uuid4()),
                json.dumps(runner_bounds),
                str(uuid.uuid4()),
                uuid.uuid4().hex + uuid.uuid4().hex,
                operation_kind,
                source_experiment_id,
                shadow_experiment.experiment_id,
            )

        invalid_rows = (
            {
                "operation_kind": "RUNTIME_SHADOW",
                "source_experiment_id": experiment.experiment_id,
                "runner_bounds": {
                    "maxCases": 20, "maxConcurrency": 2,
                    "perCaseTimeoutMs": 75000,
                },
            },
            {
                "operation_kind": "RUN",
                "source_experiment_id": None,
                "runner_bounds": {
                    "maxCases": 100, "maxConcurrency": 4,
                    "perCaseTimeoutMs": 5001,
                },
            },
            {
                "operation_kind": "RUNTIME_SHADOW",
                "source_experiment_id": None,
                "runner_bounds": {
                    "maxCases": 20, "maxConcurrency": 2,
                    "perCaseTimeoutMs": 999,
                },
            },
            {
                "operation_kind": "RUNTIME_SHADOW",
                "source_experiment_id": None,
                "runner_bounds": {
                    "maxCases": 21, "maxConcurrency": 2,
                    "perCaseTimeoutMs": 1000,
                },
            },
            {
                "operation_kind": "RUNTIME_SHADOW",
                "source_experiment_id": None,
                "runner_bounds": {
                    "maxCases": 20, "maxConcurrency": 3,
                    "perCaseTimeoutMs": 1000,
                },
            },
        )
        for invalid_row in invalid_rows:
            with pytest.raises(asyncpg.CheckViolationError):
                await insert_with_operation_and_bounds(**invalid_row)

        with pytest.raises(asyncpg.ForeignKeyViolationError):
            await admin.execute(
                """
                INSERT INTO smart_bi_agent_experiment (
                    experiment_id,factory_id,eval_set_id,eval_set_name,
                    eval_set_version,eval_set_digest,evaluator_version,
                    evaluator_build,snapshot_digest,config_snapshot,
                    actual_snapshots,runner_bounds,aggregate,case_results,
                    request_id,request_digest,operation_kind,
                    source_experiment_id,created_by
                )
                SELECT $1::uuid,factory_id,eval_set_id,eval_set_name,
                       eval_set_version,eval_set_digest,evaluator_version,
                       evaluator_build,snapshot_digest,config_snapshot,
                       actual_snapshots,runner_bounds,aggregate,case_results,
                       $2::uuid,$3,'RERUN',$4::uuid,created_by
                FROM smart_bi_agent_experiment
                WHERE experiment_id=$5::uuid
                """,
                str(uuid.uuid4()), str(uuid.uuid4()), "e" * 64,
                str(uuid.uuid4()), experiment.experiment_id,
            )

        with pytest.raises(asyncpg.ForeignKeyViolationError):
            await admin.execute(
                """
                INSERT INTO smart_bi_agent_experiment (
                    experiment_id,factory_id,eval_set_id,eval_set_name,
                    eval_set_version,eval_set_digest,evaluator_version,
                    evaluator_build,snapshot_digest,config_snapshot,
                    actual_snapshots,runner_bounds,aggregate,case_results,
                    request_id,request_digest,operation_kind,
                    source_experiment_id,created_by
                )
                SELECT $1::uuid,factory_id,eval_set_id,eval_set_name,
                       eval_set_version,eval_set_digest,evaluator_version,
                       evaluator_build,snapshot_digest,config_snapshot,
                       actual_snapshots,runner_bounds,aggregate,case_results,
                       $2::uuid,$3,'RERUN',$4::uuid,created_by
                FROM smart_bi_agent_experiment
                WHERE experiment_id=$5::uuid
                """,
                str(uuid.uuid4()), str(uuid.uuid4()), "f" * 64,
                tenant_experiment.experiment_id, experiment.experiment_id,
            )

        self_source_id = str(uuid.uuid4())
        with pytest.raises(asyncpg.CheckViolationError):
            await admin.execute(
                """
                INSERT INTO smart_bi_agent_experiment (
                    experiment_id,factory_id,eval_set_id,eval_set_name,
                    eval_set_version,eval_set_digest,evaluator_version,
                    evaluator_build,snapshot_digest,config_snapshot,
                    actual_snapshots,runner_bounds,aggregate,case_results,
                    request_id,request_digest,operation_kind,
                    source_experiment_id,created_by
                )
                SELECT $1::uuid,factory_id,eval_set_id,eval_set_name,
                       eval_set_version,eval_set_digest,evaluator_version,
                       evaluator_build,snapshot_digest,config_snapshot,
                       actual_snapshots,runner_bounds,aggregate,case_results,
                       $2::uuid,$3,'RERUN',$1::uuid,created_by
                FROM smart_bi_agent_experiment
                WHERE experiment_id=$4::uuid
                """,
                self_source_id, str(uuid.uuid4()), "a" * 64,
                experiment.experiment_id,
            )

        if trace_contract_applied:
            await admin.execute(f'SET search_path TO "{upgrade_schema}", public')
            run_id = str(uuid.uuid4())
            await admin.execute(
                """
                INSERT INTO smart_bi_agent_run (
                    run_id,factory_id,owner_user_id,business_type,correlation_id,
                    route_code,sanitized_request,next_event_sequence
                ) VALUES (
                    $1::uuid,'R001','run-owner','RESTAURANT','corr',
                    'GROSS_MARGIN_DECLINE_ATTRIBUTION',
                    '{"routeCode":"GROSS_MARGIN_DECLINE_ATTRIBUTION","startDate":"2026-01-01","endDate":"2026-01-31","storeTopN":20,"dishTopN":10}'::jsonb,
                    1
                )
                """,
                run_id,
            )
            await admin.execute(
                """
                INSERT INTO smart_bi_agent_event (
                    run_id,factory_id,event_sequence,event_type,payload
                ) VALUES (
                    $1::uuid,'R001',1,'RUN_STARTED',
                    '{"routeCode":"GROSS_MARGIN_DECLINE_ATTRIBUTION"}'::jsonb
                )
                """,
                run_id,
            )
            await app.execute(f'SET search_path TO "{upgrade_schema}", public')

            async def visible(user: str, actor_role: str, audit: str, factory: str) -> tuple[int, int]:
                async with app.transaction():
                    await app.execute("SELECT set_config('app.factory_id',$1,true)", factory)
                    await app.execute("SELECT set_config('app.user_id',$1,true)", user)
                    await app.execute("SELECT set_config('app.actor_role',$1,true)", actor_role)
                    await app.execute("SELECT set_config('app.agent_ops_audit',$1,true)", audit)
                    return (
                        await app.fetchval("SELECT COUNT(*) FROM smart_bi_agent_run WHERE run_id=$1::uuid", run_id),
                        await app.fetchval("SELECT COUNT(*) FROM smart_bi_agent_event WHERE run_id=$1::uuid", run_id),
                    )

            assert await visible("other-admin", "platform_admin", "true", "R001") == (1, 1)
            assert await visible("viewer", "viewer", "true", "R001") == (0, 0)
            assert await visible("other-admin", "platform_admin", "false", "R001") == (0, 0)
            assert await visible("", "platform_admin", "true", "R001") == (0, 0)
            assert await visible("other-admin", "platform_admin", "true", "R002") == (0, 0)
    finally:
        if app_pool is not None:
            await app_pool.close()
        if app is not None:
            await app.close()
        await admin.execute("RESET search_path")
        if fresh_created:
            await admin.execute(f'DROP SCHEMA IF EXISTS "{fresh_schema}" CASCADE')
        if upgrade_created:
            await admin.execute(f'DROP SCHEMA IF EXISTS "{upgrade_schema}" CASCADE')
        if role_created:
            await admin.execute(f'DROP ROLE IF EXISTS "{role}"')
        await admin.close()
