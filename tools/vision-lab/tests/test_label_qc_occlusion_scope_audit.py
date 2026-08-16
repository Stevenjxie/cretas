from __future__ import annotations

import importlib.util
import sys
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
SPEC = importlib.util.spec_from_file_location(
    "label_qc_occlusion_scope_audit", ROOT / "label_qc_occlusion_scope_audit.py",
)
assert SPEC and SPEC.loader
module = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(module)


def test_match_accepts_close_human_instance_and_rejects_group_box():
    close = module.match_legacy_target(
        [0.1, 0.1, 0.3, 0.3], [[0.11, 0.11, 0.29, 0.29]],
    )
    grouped = module.match_legacy_target(
        [0.1, 0.1, 0.9, 0.9], [[0.1, 0.1, 0.3, 0.3]],
    )

    assert close["matched"] is True
    assert grouped["matched"] is False


def test_unmatched_occluded_target_is_ignore_not_negative_or_miss():
    result = module.classify_target({"matched": False}, ["tray_detection_miss"])

    assert result["status"] == "OCCLUDED_OR_INVALID_LEGACY_TARGET_IGNORE"
    assert result["counted_as_tray_detector_miss"] is False
    assert result["counted_as_negative"] is False
    assert result["requires_new_judgeable_human_truth"] is True


def test_matched_target_with_label_truth_conflict_stays_pending():
    result = module.classify_target(
        {"matched": True}, ["protected_truth_conflicts_with_human_crop"],
    )

    assert result["status"] == "JUDGEABLE_TRAY_LABEL_TRUTH_CONFLICT_PENDING"
    assert result["counted_as_tray_detector_miss"] is False
    assert result["eligible_for_training"] is False


def test_decision_does_not_claim_zero_misses_when_one_judgeable_miss_remains():
    decision = module.build_decision(1)

    assert decision["train_new_model"] is False
    assert decision["rent_gpu"] is False
    assert "1 reviewed judgeable tray-detector miss remains" in decision["reason"]
    assert "train-only" in decision["next_gate"]
