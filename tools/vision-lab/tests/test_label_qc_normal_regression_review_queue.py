from __future__ import annotations

import importlib.util
import sys
from pathlib import Path

import pytest


ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
SPEC = importlib.util.spec_from_file_location(
    "label_qc_normal_regression_review_queue",
    ROOT / "label_qc_normal_regression_review_queue.py",
)
assert SPEC and SPEC.loader
module = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(module)


def test_merge_detector_proposals_prefers_production_geometry_and_keeps_sources():
    rows = module.merge_detector_proposals([
        {"box": [0.1, 0.1, 0.4, 0.4], "confidence": 0.8, "source": "candidate", "priority": 1},
        {"box": [0.11, 0.11, 0.41, 0.41], "confidence": 0.7, "source": "production", "priority": 3},
        {"box": [0.6, 0.6, 0.9, 0.9], "confidence": 0.5, "source": "edge", "priority": 1},
    ])

    assert len(rows) == 2
    assert rows[0]["box"] == [0.11, 0.11, 0.41, 0.41]
    assert rows[0]["sources"] == ["candidate", "production"]


def test_select_regressions_requires_exact_bound_count():
    attribution = {
        "summary": {"regressed_photos": 2},
        "rows": [
            {"photo_id": "a", "regression_source": "candidate_model", "baseline_flags": 0, "candidate_edge_flags": 1},
            {"photo_id": "b", "regression_source": "edge_windows", "baseline_flags": 1, "candidate_edge_flags": 2},
            {"photo_id": "c", "regression_source": "not_regressed", "baseline_flags": 1, "candidate_edge_flags": 0},
        ],
    }

    assert [row["photo_id"] for row in module.select_regressions(attribution, 2)] == ["a", "b"]
    with pytest.raises(RuntimeError, match="expected 1"):
        module.select_regressions(attribution, 1)


def test_yolo_line_round_trips_normalised_box_shape():
    assert module.yolo_line([0.1, 0.2, 0.5, 0.8]) == (
        "0 0.30000000 0.50000000 0.40000000 0.60000000\n"
    )


def test_legacy_annotator_metadata_contains_required_angle_field():
    assert module.legacy_annotator_metadata() == {
        "angle": "qc_eligibility_cleanup",
        "source_kind": "independent_normal_regression",
        "human_defect_label": "NO_DEFECT",
    }
