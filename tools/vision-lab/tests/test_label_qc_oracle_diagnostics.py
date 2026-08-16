from __future__ import annotations

import importlib.util
from dataclasses import dataclass
from pathlib import Path

import numpy as np


ROOT = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location(
    "label_qc_oracle_diagnostics", ROOT / "label_qc_oracle_diagnostics.py",
)
assert SPEC and SPEC.loader
module = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(module)


@dataclass(frozen=True)
class _Detection:
    x0: float
    y0: float
    x1: float
    y1: float
    confidence: float
    class_id: int = 0

    def as_xyxy(self):
        return [self.x0, self.y0, self.x1, self.y1]


def test_tile_starts_cover_final_edge_without_duplicate_start():
    assert module.tile_starts(2500, 1000, 0.20) == [0, 800, 1500]
    assert module.tile_starts(800, 1000, 0.20) == [0]


def test_deduplicate_suppresses_nested_duplicate_and_keeps_high_confidence():
    high = _Detection(0, 0, 100, 100, 0.9)
    nested = _Detection(5, 5, 95, 95, 0.8)
    separate = _Detection(150, 0, 250, 100, 0.7)

    kept = module.deduplicate_detections(
        [nested, separate, high], iou_threshold=0.5, ios_threshold=0.8,
    )

    assert kept == [high, separate]


class _BaseModels:
    def __init__(self):
        self.calls = 0

    def detect_trays(self, image, conf):
        self.calls += 1
        if self.calls == 1:  # full frame
            return [_Detection(10, 10, 50, 50, 0.8)]
        if self.calls == 2:  # first tile, duplicate with higher confidence
            return [_Detection(10, 10, 50, 50, 0.9)]
        return [_Detection(0, 10, 40, 50, 0.85)]

    def detect_labels(self, crop, conf):
        return []


def test_sliced_models_map_tile_coordinates_and_report_duplicates():
    wrapper = module.SlicedTrayModels(
        _BaseModels(), _Detection, tile_size=100, overlap=0,
        iou_threshold=0.5, ios_threshold=0.8,
    )

    detections = wrapper.detect_trays(np.zeros((100, 200, 3), dtype=np.uint8), 0.6)

    assert [item.as_xyxy() for item in detections] == [
        [10, 10, 50, 50],
        [100, 10, 140, 50],
    ]
    assert wrapper.last_stats["tiles"] == 2
    assert wrapper.last_stats["raw_detections"] == 3
    assert wrapper.last_stats["duplicates_removed"] == 1


def test_human_annotation_verdict_requires_reviewed_judgeable_human_truth():
    clear = {
        "reviewed": True,
        "source": "human",
        "unjudgeable": False,
        "boxes": [{"c": 0}, {"c": 1}],
    }
    assert module.human_annotation_verdict(clear) == "CLEAR"
    assert module.human_annotation_verdict(clear | {"source": "model"}) is None
    assert module.human_annotation_verdict(clear | {"unjudgeable": True}) is None
    assert module.human_annotation_verdict(clear | {"boxes": [{"c": 1}]}) == (
        "MISSING_WHITE_LABEL"
    )


def test_stage_attribution_prioritises_truth_conflict_but_keeps_other_issues():
    stage, issues = module.classify_defect_stage(
        protected_truth="MISSING_WHITE_LABEL",
        covered=False,
        screen_hit=False,
        truth_crop_verdict="CLEAR",
        human_crop_verdict="CLEAR",
    )

    assert stage == "truth_conflict"
    assert "protected_truth_conflicts_with_human_crop" in issues
    assert "tray_detection_miss" in issues
    assert "truth_tray_current_label_mismatch" in issues


def test_stage_attribution_isolates_tray_then_label_then_assignment():
    assert module.classify_defect_stage(
        "MISSING_WHITE_LABEL", False, False, "MISSING_WHITE_LABEL", None,
    )[0] == "tray_detection_miss"
    assert module.classify_defect_stage(
        "MISSING_WHITE_LABEL", True, False, "CLEAR", None,
    )[0] == "label_detection_or_rule_miss"
    assert module.classify_defect_stage(
        "MISSING_WHITE_LABEL", True, False, "MISSING_WHITE_LABEL", None,
    )[0] == "assignment_or_crop_path_miss"


def _variant(details, p95=1000):
    return {"details": details, "p95_latency_ms": p95}


def test_comparison_needs_two_independent_improvements_and_zero_regressions():
    baseline = _variant([
        {"photo_id": "d1", "task_id": "t1", "kind": "defect", "hit": False,
         "tray_target_covered": False},
        {"photo_id": "d2", "task_id": "t2", "kind": "defect", "hit": False,
         "tray_target_covered": False},
        {"photo_id": "n1", "task_id": "tn1", "kind": "normal", "false_flags": 0},
    ])
    candidate = _variant([
        {"photo_id": "d1", "task_id": "t1", "kind": "defect", "hit": True,
         "tray_target_covered": True},
        {"photo_id": "d2", "task_id": "t2", "kind": "defect", "hit": True,
         "tray_target_covered": True},
        {"photo_id": "n1", "task_id": "tn1", "kind": "normal", "false_flags": 0},
    ], p95=1500)

    comparison = module.compare_variants(baseline, candidate)

    assert comparison["independent_defect_tasks_recovered"] == 2
    assert comparison["development_continue_signal"] is True
    assert comparison["development_signal_is_not_promotion"] is True
    assert comparison["latency_ratio"] == 1.5


def test_comparison_rejects_normal_photo_regression():
    baseline = _variant([
        {"photo_id": "d1", "task_id": "t1", "kind": "defect", "hit": False,
         "tray_target_covered": False},
        {"photo_id": "d2", "task_id": "t2", "kind": "defect", "hit": False,
         "tray_target_covered": False},
        {"photo_id": "n1", "task_id": "tn1", "kind": "normal", "false_flags": 0},
    ])
    candidate = _variant([
        {"photo_id": "d1", "task_id": "t1", "kind": "defect", "hit": True,
         "tray_target_covered": True},
        {"photo_id": "d2", "task_id": "t2", "kind": "defect", "hit": True,
         "tray_target_covered": True},
        {"photo_id": "n1", "task_id": "tn1", "kind": "normal", "false_flags": 1},
    ])

    comparison = module.compare_variants(baseline, candidate)

    assert comparison["normal_flags_increased"] == ["n1"]
    assert comparison["development_continue_signal"] is False
