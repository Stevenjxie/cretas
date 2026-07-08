"""The reseed script's cost 口径 must stay byte-identical to the migration."""
import re
from pathlib import Path

from smartbi.scripts.reseed_demo_rest_cost import _RESEED_SQL


def _migration_sql() -> str:
    p = (Path(__file__).resolve().parents[1]
         / "smartbi" / "database" / "migrations"
         / "V20260709_02__demo_rest_cost_seed.sql")
    return p.read_text(encoding="utf-8")


def test_reseed_ratios_identical_to_migration():
    mig = _migration_sql()
    for ratio in (
        "ROUND(a.net_amount * (0.30 + (a.store_id % 9) * 0.01), 2)",
        "ROUND(a.net_amount * (0.22 + (a.store_id % 9) * 0.01), 2)",
        "ROUND(a.net_amount * (0.14 + (a.store_id % 7) * 0.01), 2)",
    ):
        assert ratio in _RESEED_SQL, f"reseed missing {ratio}"
        assert ratio in mig, f"migration missing {ratio}"


def test_reseed_is_idempotent_and_scoped():
    assert "ON CONFLICT (factory_id, date, store_id) DO NOTHING" in _RESEED_SQL
    assert "WHERE a.factory_id = 'DEMO_REST' AND a.net_amount > 0" in _RESEED_SQL
    # only the demo tenant is ever touched
    assert len(re.findall(r"factory_id\s*=\s*'([^']+)'", _RESEED_SQL)) >= 1
    assert all(f == "DEMO_REST" for f in re.findall(r"factory_id\s*=\s*'([^']+)'", _RESEED_SQL))
