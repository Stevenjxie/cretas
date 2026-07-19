from pathlib import Path


MIGRATION = (
    Path(__file__).resolve().parents[1]
    / "smartbi"
    / "database"
    / "migrations"
    / "V20261028_02__chat_session_user_identity.sql"
)


def _source() -> str:
    return MIGRATION.read_text(encoding="utf-8")


def test_anonymous_rows_are_deleted_before_user_id_becomes_not_null():
    sql = _source()
    lock_table = sql.index("LOCK TABLE smart_bi_chat_session IN ACCESS EXCLUSIVE MODE")
    delete_null = sql.index("DELETE FROM smart_bi_chat_session")
    delete_predicate = sql.index("WHERE user_id IS NULL", delete_null)
    make_not_null = sql.index("ALTER COLUMN user_id SET NOT NULL")

    assert lock_table < delete_null < delete_predicate < make_not_null


def test_legacy_single_session_unique_is_discovered_not_name_assumed():
    sql = _source()

    assert "FROM pg_constraint" in sql
    assert "contype = 'u'" in sql
    assert "conkey = ARRAY[session_attnum]::smallint[]" in sql
    assert "DROP CONSTRAINT %I" in sql
    assert "smart_bi_chat_session_session_id_key" not in sql


def test_composite_identity_unique_is_idempotently_discovered_and_created():
    sql = _source()

    assert "IF NOT EXISTS" in sql
    assert "conkey @> identity_attnums" in sql
    assert "conkey <@ identity_attnums" in sql
    assert "CONSTRAINT uq_chat_session_factory_user_session" in sql
    assert "UNIQUE (factory_id, user_id, session_id)" in sql
