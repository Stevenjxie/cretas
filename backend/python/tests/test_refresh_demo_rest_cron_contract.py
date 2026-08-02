"""Source contracts for the production restaurant-demo cron wrapper."""
from __future__ import annotations

from pathlib import Path


CRON_PATH = (
    Path(__file__).resolve().parents[3]
    / "scripts"
    / "cron"
    / "refresh-demo-rest.sh"
)


def _source() -> str:
    return CRON_PATH.read_text(encoding="utf-8")


def test_demo_rest_pipeline_orders_aggregate_before_pos_before_verification():
    source = _source()
    aggregate = source.index(
        'run_step "refresh DEMO_REST sales aggregate from own fixed template'
    )
    pos = source.index('run_step "refresh DEMO_REST dish-level POS items')
    verification = source.index(
        'run_step "verify DEMO_REST agg_daily from own POS grain'
    )
    assert aggregate < pos < verification


def test_every_demo_state_change_has_factory_apply_and_exact_confirmation():
    source = _source()
    assert "TOTAL_STEPS=6" in source
    assert source.count('run_step "') == 6
    assert (
        "python -m smartbi.scripts.refresh_qhj_demo_recent_agg \\\n"
        "      --factory RES_3101_009 \\\n"
        "      --apply --confirm RES_3101_009"
    ) in source
    assert (
        "python -m smartbi.scripts.refresh_qhj_demo_recent_agg \\\n"
        "      --factory DEMO_REST \\\n"
        "      --apply --confirm DEMO_REST"
    ) in source
    assert (
        "python -m smartbi.scripts.refresh_demo_rest_dish_facts \\\n"
        "      --apply --confirm DEMO_REST"
    ) in source
    assert (
        "python -m smartbi.scripts.refresh_demo_rest_agg_daily \\\n"
        "      --apply --confirm DEMO_REST"
    ) in source
    assert (
        "python -m smartbi.scripts.refresh_demo_rest_dish_facts \\\n"
        "      --factory RES_3101_009 --apply --confirm RES_3101_009"
    ) in source


def test_step_failure_is_recorded_without_short_circuiting_later_tenants():
    source = _source()
    assert '"$@" || rc=$?' in source
    assert 'FAILED_STEPS+=("$label")' in source
    assert "return 0" in source
    assert 'done (rc=1, ${#FAILED_STEPS[@]}/$TOTAL_STEPS 步失败)' in source


def test_primary_demo_policy_stays_mock_plus_res():
    python_root = Path(__file__).resolve().parents[1]
    capability_audit = (
        python_root / "scripts" / "restaurant_capability_audit.py"
    ).read_text(encoding="utf-8")
    adversarial_audit = (
        python_root / "scripts" / "restaurant_adversarial_audit.py"
    ).read_text(encoding="utf-8")
    gold_reads = (
        python_root / "smartbi" / "api" / "gold_reads.py"
    ).read_text(encoding="utf-8")

    assert 'default=os.environ.get("AUDIT_FACTORY_ID", "MOCK_REST")' in capability_audit
    assert 'ap.add_argument("--factory", default="MOCK_REST")' in adversarial_audit
    assert '"DEMO_REST": "RES_3101_009"' in gold_reads
