from __future__ import annotations

from pathlib import Path


MIGRATION = (
    Path(__file__).resolve().parents[1]
    / "smartbi" / "database" / "migrations"
    / "V20261101_08__restaurant_reservation_staffing.sql"
)
SQL = MIGRATION.read_text(encoding="utf-8")


def test_migration_is_atomic_and_tenant_scoped():
    assert SQL.count("BEGIN;") == 1
    assert SQL.count("COMMIT;") == 1
    for table in (
        "fact_restaurant_reservation",
        "restaurant_staffing_policy",
        "restaurant_staffing_adjustment",
        "restaurant_reservation_roll_audit",
    ):
        assert f"ALTER TABLE {table} ENABLE ROW LEVEL SECURITY" in SQL
        assert f"ALTER TABLE {table} FORCE ROW LEVEL SECURITY" in SQL
        assert f"CREATE POLICY tenant_isolation ON {table}" in SQL


def test_reservation_contract_keeps_required_provenance_fields():
    for field in (
        "source", "store_id", "reservation_date", "daypart", "table_count",
        "guest_count", "status", "source_updated_at", "is_simulated",
    ):
        assert field in SQL


def test_adjustment_receipt_keeps_exact_forecast_confirmation():
    for field in (
        "predicted_guests", "policy_version", "recommended_staff",
        "plan_fingerprint", "idempotency_key", "actor_user_id", "actor_role",
    ):
        assert field in SQL


def test_simulation_is_explicit_and_limited_to_requested_tenants():
    assert "MOCK_REST" in SQL
    assert "RES_3101_009" in SQL
    assert "is_simulated" in SQL
    assert "cretas_daily_simulator" in SQL
