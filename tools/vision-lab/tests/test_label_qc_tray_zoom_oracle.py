from __future__ import annotations

import importlib.util
import sys
from dataclasses import dataclass
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
SPEC = importlib.util.spec_from_file_location(
    "label_qc_tray_zoom_oracle", ROOT / "label_qc_tray_zoom_oracle.py",
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


def test_expand_box_and_integer_crop_rect_clamp_to_source():
    expanded = module.expand_box([10, 20, 30, 60], 100, 80, 1.0)

    assert expanded == [0.0, 0.0, 50.0, 80.0]
    assert module.integer_crop_rect([0.1, 1.9, 50.1, 79.1], 100, 80) == [0, 1, 51, 80]


def test_measure_detections_maps_crop_coordinates_and_counts_instances():
    measured = module.measure_detections(
        [
            _Detection(0, 0, 40, 40, 0.9),
            _Detection(45, 0, 85, 40, 0.8),
        ],
        crop_rect=[100, 200, 200, 300],
        truth_box=[100, 200, 190, 250],
    )

    assert measured["detection_count"] == 2
    assert measured["center_inside_truth_count"] == 2
    assert measured["intersects_truth_count"] == 2
    assert measured["detections"][0]["box"] == [100.0, 200.0, 140.0, 240.0]


def _setting(threshold, containment, centers=1, iou=0.4):
    return {
        "threshold": threshold,
        "pad_ratio": 1.0,
        "best_truth_containment": containment,
        "best_truth_iou": iou,
        "center_inside_truth_count": centers,
    }


def test_classification_distinguishes_scale_threshold_and_multi_instance_signals():
    scale = module.classify_zoom_result(
        [_setting(0.6, 0.7)], baseline_threshold=0.6,
    )
    threshold = module.classify_zoom_result(
        [_setting(0.6, 0.2), _setting(0.3, 0.7)], baseline_threshold=0.6,
    )
    grouped = module.classify_zoom_result(
        [_setting(0.6, 0.2, centers=2)], baseline_threshold=0.6,
    )

    assert scale["interpretation"] == "single_tray_scale_sensitive"
    assert scale["requires_full_image_instance_human_review"] is False
    assert threshold["interpretation"] == "single_tray_threshold_sensitive"
    assert threshold["recovered_only_below_baseline_threshold"] is True
    assert grouped["interpretation"] == "protected_bbox_may_group_multiple_tray_instances"
    assert grouped["requires_full_image_instance_human_review"] is True


def test_unrecovered_single_detection_still_requires_human_instance_review():
    result = module.classify_zoom_result(
        [_setting(0.6, 0.2, centers=1)], baseline_threshold=0.6,
    )

    assert result["interpretation"] == "not_recovered_by_local_scale_or_threshold_oracle"
    assert result["requires_full_image_instance_human_review"] is True


def test_truth_conflict_keeps_human_review_gate_even_when_zoom_recovers():
    zoom = module.classify_zoom_result(
        [_setting(0.3, 0.7)], baseline_threshold=0.6,
    )

    gated = module.apply_human_review_gate(
        zoom,
        ["protected_truth_conflicts_with_human_crop"],
    )

    assert gated["interpretation"] == "single_tray_threshold_sensitive"
    assert gated["requires_full_image_instance_human_review"] is True
    assert gated["human_review_reasons"] == [
        "protected_truth_conflicts_with_existing_human_crop_review",
    ]
