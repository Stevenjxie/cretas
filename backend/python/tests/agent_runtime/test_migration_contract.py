from pathlib import Path


MIGRATION = (
    Path(__file__).parents[2]
    / "smartbi/database/migrations/V20261028_01__smart_bi_agent_run_event.sql"
)
ADAPTIVE_MIGRATION = (
    Path(__file__).parents[2]
    / "smartbi/database/migrations/V20261028_03__restaurant_agent_adaptive_events.sql"
)
OWNER_CONTRACT_MIGRATION = (
    Path(__file__).parents[2]
    / "smartbi/database/migrations/V20261028_05__restaurant_agent_owner_enforcement.sql"
)
PG_HARNESS = Path(__file__).with_name("test_postgres_run_store_integration.py")


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


def test_adaptive_migration_keeps_legacy_outcomes_and_adds_only_safe_read_events():
    body = ADAPTIVE_MIGRATION.read_text(encoding="utf-8")
    for event_type in (
        "EVIDENCE_RECORDED",
        "EVIDENCE_GAP",
        "REPLAN",
        "CLARIFICATION",
        "CANCEL_REQUESTED",
    ):
        assert event_type in body
    assert "READ_ONLY_PROPOSAL" in body
    assert "drilldownTruncated" in body
    assert "jsonb_each(item->'dimensions')" in body
    assert "dimension_value #>> '{}'" in body
    assert "IF value ? 'actionProposals'" in body
    assert "owner_user_id" in body
    assert "NULLIF(current_setting('app.user_id', true), '')" in body
    assert "Existing rows intentionally remain NULL" in body
    assert "route gate OFF -> V03 -> new Java -> new Python -> RN" in body
    assert "V05 owner contract -> route gate ON" in body
    assert "owner_user_id IS NULL" in body
    assert "NULLIF(current_setting('app.user_id', true), '') IS NULL" in body
    assert "NULLIF(current_setting('app.user_id', true), '') IS NOT NULL" in body
    assert "octet_length" not in body or "32768" in body
    assert "INSERT INTO" not in body
    assert "UPDATE smart_bi_agent_run" not in body


def test_owner_contract_is_strict_forward_only_and_audit_is_select_only():
    body = OWNER_CONTRACT_MIGRATION.read_text(encoding="utf-8")
    assert "CHECK (owner_user_id IS NOT NULL) NOT VALID" in body
    assert "historical NULL owners remain unvalidated and invisible" in body
    assert "old code that omits app.user_id is intentionally incompatible" in body
    assert "route gate OFF plus roll-forward" in body
    assert "SET owner_user_id" not in body
    assert "UPDATE smart_bi_agent_run" not in body
    assert "INSERT INTO smart_bi_agent_run" not in body
    assert "owner_user_id = NULLIF(current_setting('app.user_id', true), '')" in body
    assert "owner_user_id IS NULL" not in body
    assert "smart_bi_agent_run_tenant_admin_audit_select" in body
    assert "smart_bi_agent_event_tenant_admin_audit_select" in body
    assert body.count("current_setting('app.agent_ops_audit', true) = 'true'") == 2
    for role in (
        "factory_super_admin",
        "platform_admin",
        "permission_admin",
        "restaurant_manager",
        "restaurant_owner",
    ):
        assert role in body
    assert body.count("tenant_admin_audit_select") == 4  # DROP + CREATE for two tables
    assert "tenant_admin_audit_insert" not in body
    assert "tenant_admin_audit_update" not in body


def test_postgres_gate_is_explicitly_disposable_and_schema_isolated():
    body = PG_HARNESS.read_text(encoding="utf-8")
    assert "AGENT_RUNTIME_PG_DISPOSABLE_CONFIRM" in body
    assert 'parsed.hostname not in {"127.0.0.1", "localhost", "::1"}' in body
    assert 'candidate_schema = f"agent_runtime_test_{isolation_suffix}"' in body
    assert 'candidate_role = f"smartbi_test_{isolation_suffix}"' in body
    assert "SELECT 1 FROM pg_namespace WHERE nspname = $1" in body
    assert "SELECT 1 FROM pg_roles WHERE rolname = $2" in body
    assert "schema_created = False" in body
    assert "role_created = False" in body
    assert "if schema_created:" in body
    assert "if role_created:" in body
    assert 'server_settings={"search_path": TEST_SCHEMA}' in body
    assert 'DROP SCHEMA "{TEST_SCHEMA}" CASCADE' in body
    assert 'DROP ROLE "{APP_ROLE}"' in body
    assert "JOIN pg_namespace namespace ON namespace.oid = relation.relnamespace" in body
    assert "TRUNCATE" not in body.upper()
    assert "DROP SCHEMA public" not in body
    assert "DROP OWNED" not in body
