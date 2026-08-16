from __future__ import annotations

import importlib.util
import sys
from pathlib import Path

import pytest


ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
SPEC = importlib.util.spec_from_file_location(
    "label_qc_screening_param_shadow",
    ROOT / "label_qc_screening_param_shadow.py",
)
assert SPEC and SPEC.loader
module = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(module)


def test_final_set_overlap_fails_closed():
    rows = [{"photo_id": "p1", "task_id": "t1", "image_sha256": "abc"}]
    receipt = {
        "split_lock": {
            "final_photo_ids": ["p2"],
            "final_task_ids": ["t1"],
            "final_image_sha256": ["def"],
        },
        "final": {"model_inference_started": False},
    }

    with pytest.raises(RuntimeError, match="overlaps sealed final"):
        module.assert_final_disjoint(rows, receipt)


def test_exact_final_set_accepts_only_the_locked_ids_tasks_and_hashes():
    rows = [
        {"photo_id": "p1", "task_id": "t1", "image_sha256": "abc"},
        {"photo_id": "p2", "task_id": "t2", "image_sha256": "def"},
    ]
    receipt = {
        "split_lock": {
            "final_photos": 2, "final_tasks": 2,
            "final_photo_ids": ["p2", "p1"],
            "final_task_ids": ["t2", "t1"],
            "final_image_sha256": ["DEF", "ABC"],
            "task_disjoint": True, "content_disjoint": True,
            "final_model_inference_started": False,
        },
        "final": {"evaluated": False, "model_inference_started": False},
    }

    audit = module.assert_final_exact(rows, receipt)

    assert audit["exact_match"] is True
    assert audit["photos"] == 2
    assert audit["tasks"] == 2


def test_exact_final_set_rejects_subset_or_drift():
    rows = [{"photo_id": "p1", "task_id": "t1", "image_sha256": "abc"}]
    receipt = {
        "split_lock": {
            "final_photos": 2, "final_tasks": 2,
            "final_photo_ids": ["p1", "p2"],
            "final_task_ids": ["t1", "t2"],
            "final_image_sha256": ["abc", "def"],
            "task_disjoint": True, "content_disjoint": True,
            "final_model_inference_started": False,
        },
        "final": {"evaluated": False, "model_inference_started": False},
    }

    with pytest.raises(RuntimeError, match="not the exact sealed final"):
        module.assert_final_exact(rows, receipt)


def test_replaced_missing_claim_must_remain_reviewable():
    baseline = {"suspects": [{"box": [0, 0, 100, 100]}]}
    candidate = {"review_candidates": [{"box": [1, 1, 99, 99]}]}

    assert module.hidden_baseline_claims(baseline, candidate) == []


def test_gate_accepts_zero_false_flags_and_review_preservation():
    gate = module.build_gate(
        {"false_flags": 0, "p95_latency_ms": 1000},
        {"false_flags": 0, "p95_latency_ms": 1010},
        [],
        0,
    )

    assert gate["normal_specificity_passed"] is True
    assert gate["promotion_allowed"] is False


def test_gate_rejects_silent_hiding():
    gate = module.build_gate(
        {"false_flags": 1, "p95_latency_ms": 1000},
        {"false_flags": 0, "p95_latency_ms": 900},
        [],
        1,
    )

    assert gate["normal_specificity_passed"] is False
    assert "silently hidden" in gate["errors"][0]
