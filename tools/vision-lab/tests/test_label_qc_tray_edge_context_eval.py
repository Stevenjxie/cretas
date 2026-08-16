from __future__ import annotations

import importlib.util
import sys
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
SPEC = importlib.util.spec_from_file_location(
    "label_qc_tray_edge_context_eval", ROOT / "label_qc_tray_edge_context_eval.py",
)
assert SPEC and SPEC.loader
module = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(module)


def test_edge_regions_are_fixed_symmetric_lower_windows():
    regions = module.edge_regions(1000, 800, 0.5, 0.6)

    assert regions == [
        ("lower_left", 0, 320, 500, 800),
        ("lower_right", 500, 320, 1000, 800),
    ]


def test_outer_band_filter_keeps_only_corresponding_lower_corner_centres():
    assert module.belongs_to_outer_band(
        [20, 500, 320, 700], "lower_left", 1000, 800, 0.4, 0.6, 0.55,
    ) is True
    assert module.belongs_to_outer_band(
        [680, 500, 980, 700], "lower_right", 1000, 800, 0.4, 0.6, 0.55,
    ) is True
    assert module.belongs_to_outer_band(
        [420, 500, 620, 700], "lower_left", 1000, 800, 0.4, 0.6, 0.55,
    ) is False
    assert module.belongs_to_outer_band(
        [20, 100, 320, 300], "lower_left", 1000, 800, 0.4, 0.6, 0.55,
    ) is False


def test_corrected_gate_ignores_invalid_legacy_targets_but_requires_judgeable_target():
    baseline = {
        "false_flags": 4, "p95_latency_ms": 100,
        "details": [
            {"photo_id": "target", "kind": "defect", "hit": False, "tray_target_covered": False},
            {"photo_id": "invalid", "kind": "defect", "hit": False, "tray_target_covered": False},
            {"photo_id": "normal", "kind": "normal", "false_flags": 4},
        ],
    }
    candidate = {
        "false_flags": 3, "p95_latency_ms": 120,
        "details": [
            {"photo_id": "target", "kind": "defect", "hit": True, "tray_target_covered": True},
            {"photo_id": "invalid", "kind": "defect", "hit": False, "tray_target_covered": False},
            {"photo_id": "normal", "kind": "normal", "false_flags": 3},
        ],
    }
    audit = {"rows": [
        {"photo_id": "target", "status": "JUDGEABLE_HUMAN_TRAY_MATCH", "counted_as_tray_detector_miss": True},
        {"photo_id": "invalid", "status": "OCCLUDED_OR_INVALID_LEGACY_TARGET_IGNORE", "counted_as_tray_detector_miss": False},
    ]}

    gate = module.corrected_gate(baseline, candidate, audit, 0, 0)

    assert gate["offline_development_passed"] is True
    assert gate["judgeable_targets"][0]["candidate_hit"] is True
    assert gate["ignored_legacy_targets"] == [
        {"photo_id": "invalid", "status": "OCCLUDED_OR_INVALID_LEGACY_TARGET_IGNORE"},
    ]
    assert gate["promotion_allowed"] is False
