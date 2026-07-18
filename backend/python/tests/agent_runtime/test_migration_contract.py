from pathlib import Path


MIGRATION = (
    Path(__file__).parents[2]
    / "smartbi/database/migrations/V20261028_01__smart_bi_agent_run_event.sql"
)


def sql() -> str:
    return MIGRATION.read_text(encoding="utf-8")


def test_migration_has_force_rls_without_cross_tenant_cleanup_policy():
    body = sql()
    assert "ALTER TABLE smart_bi_agent_run ENABLE ROW LEVEL SECURITY" in body
    assert "ALTER TABLE smart_bi_agent_run FORCE ROW LEVEL SECURITY" in body
    assert "ALTER TABLE smart_bi_agent_event ENABLE ROW LEVEL SECURITY" in body
    assert "ALTER TABLE smart_bi_agent_event FORCE ROW LEVEL SECURITY" in body
    assert "allow_prune" not in body.lower()
    assert "__internal__" not in body
    assert "current_setting('app.factory_id', true)" in body
    assert body.count("CREATE POLICY smart_bi_agent_run_tenant_") == 3
    assert body.count("CREATE POLICY smart_bi_agent_event_tenant_") == 2


def test_migration_enforces_append_only_sequence_and_safe_payload():
    body = sql()
    assert (
        "next_event_sequence = next_event_sequence + 1" not in body
    )  # store owns atomic DML
    assert "event sequence is not the current tenant-bound run sequence" in body
    assert "BEFORE UPDATE OR DELETE ON smart_bi_agent_event" in body
    assert "GRANT SELECT, INSERT ON smart_bi_agent_event TO smartbi_user" in body
    assert "GRANT UPDATE ON smart_bi_agent_event" not in body
    assert "GRANT DELETE ON smart_bi_agent_event" not in body
    for sensitive in ("prompt", "review", "member", "secret", "token", "password"):
        assert sensitive in body.lower()
    assert "octet_length(payload::text) <= 32768" in body
    assert "smart_bi_agent_request_payload_is_safe" in body
    assert "smart_bi_agent_event_payload_is_safe" in body
    assert "smart_bi_agent_outcome_payload_is_safe" in body


def test_migration_has_terminal_and_counter_guards():
    body = sql()
    assert "terminal agent run is immutable" in body
    assert "agent run counters are monotonic" in body
    assert "agent run version must advance exactly once" in body
    assert "rounds_used BETWEEN 0 AND 2" in body
    assert "tool_calls_used BETWEEN 0 AND 10" in body
