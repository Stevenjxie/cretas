from pathlib import Path


MIGRATION = Path(__file__).parents[2] / "smartbi/database/migrations/V20261028_04__restaurant_agent_eval_experiments.sql"
RUNTIME_SHADOW_MIGRATION = (
    Path(__file__).parents[2]
    / "smartbi/database/migrations/V20261028_07__agentops_runtime_shadow_constraints.sql"
)


def test_forward_migration_has_rls_immutability_and_tenant_uniqueness():
    sql = MIGRATION.read_text(encoding="utf-8")
    assert "FORCE ROW LEVEL SECURITY" in sql
    assert "current_setting('app.factory_id', true)" in sql
    assert "uq_agent_eval_set_tenant_name_version" in sql
    assert "ON smart_bi_agent_eval_set (factory_id, lower(name), version)" in sql
    assert "BEFORE UPDATE OR DELETE" in sql
    assert "GRANT SELECT, INSERT" in sql
    assert "GRANT UPDATE" not in sql
    assert "actual_snapshots JSONB NOT NULL" in sql
    assert "runner_bounds JSONB NOT NULL" in sql
    assert "evaluator_build VARCHAR(64) NOT NULL CHECK (evaluator_build ~ '^[0-9a-f]{64}$')" in sql
    assert "smart_bi_agentops_config_is_safe" in sql
    assert "octet_length(actual_snapshots::text) <= 4194304" in sql
    assert "UNIQUE (eval_set_id, factory_id, name, version, content_digest)" in sql
    assert sql.count("request_id UUID NOT NULL") == 2
    assert sql.count("request_digest CHAR(64) NOT NULL") == 2
    assert sql.count("UNIQUE (factory_id, created_by, request_id)") == 2
    assert "operation_kind IN ('RUN', 'RERUN')" in sql
    assert "operation_kind = 'RUN' AND source_experiment_id IS NULL" in sql
    assert "operation_kind = 'RERUN' AND source_experiment_id IS NOT NULL" in sql
    assert "CONSTRAINT smart_bi_agent_experiment_source_fk" in sql
    assert "FOREIGN KEY (source_experiment_id, factory_id)" in sql
    assert "REFERENCES smart_bi_agent_experiment (experiment_id, factory_id)" in sql
    assert "source_experiment_id <> experiment_id" in sql


def test_migration_does_not_copy_run_or_event_truth():
    sql = MIGRATION.read_text(encoding="utf-8")
    assert "CREATE TABLE IF NOT EXISTS smart_bi_agent_trace" not in sql
    assert "smart_bi_agent_run" not in sql.replace(
        "-- Run traces continue to read smart_bi_agent_run/event as the single truth.", ""
    )


def test_forward_migration_adds_operation_specific_runtime_shadow_constraints():
    sql = RUNTIME_SHADOW_MIGRATION.read_text(encoding="utf-8")
    assert "ALTER COLUMN operation_kind TYPE VARCHAR(32)" in sql
    assert "operation_kind IN ('RUN', 'RERUN', 'RUNTIME_SHADOW')" in sql
    assert "operation_kind = 'RUNTIME_SHADOW'" in sql
    assert "operation_kind IN ('RUN', 'RERUN')" in sql
    assert "smart_bi_agentops_bounds_are_safe(runner_bounds)" in sql
    assert "smart_bi_agentops_shadow_bounds_are_safe(runner_bounds)" in sql
    assert "(value->>'maxCases')::INTEGER BETWEEN 1 AND 20" in sql
    assert "(value->>'maxConcurrency')::INTEGER BETWEEN 1 AND 2" in sql
    assert "(value->>'perCaseTimeoutMs')::INTEGER BETWEEN 1000 AND 75000" in sql
    assert "source_experiment_id IS NULL" in sql
    assert "source_experiment_id IS NOT NULL" in sql
