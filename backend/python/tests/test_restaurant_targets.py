"""Unit tests for restaurant target cascade (G2 spec)."""
from __future__ import annotations
import pathlib
import pytest

MIGRATION_PATH = (
    pathlib.Path(__file__).parent.parent
    / "smartbi/database/migrations/V20260604_01__restaurant_target_tables.sql"
)


def test_migration_file_exists():
    assert MIGRATION_PATH.exists(), "Migration file must be created before other tests"


def test_migration_contains_grant_dml():
    sql = MIGRATION_PATH.read_text(encoding="utf-8")
    assert "GRANT INSERT, UPDATE, DELETE ON restaurant_target_hierarchy TO smartbi_user" in sql
    assert "GRANT INSERT, UPDATE, DELETE ON restaurant_alert_config TO smartbi_user" in sql
    assert "GRANT USAGE, SELECT ON SEQUENCE restaurant_target_hierarchy_id_seq TO smartbi_user" in sql


def test_migration_contains_rls():
    sql = MIGRATION_PATH.read_text(encoding="utf-8")
    assert "ENABLE ROW LEVEL SECURITY" in sql
    assert "tenant_isolation" in sql
    assert "current_setting('app.factory_id', true)" in sql


def test_migration_has_unique_constraint():
    sql = MIGRATION_PATH.read_text(encoding="utf-8")
    assert "uq_target_grain" in sql
    assert "factory_id, kpi_kind, level, period_key, store_id" in sql
