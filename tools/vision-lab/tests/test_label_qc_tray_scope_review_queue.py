from __future__ import annotations

import importlib.util
import sys
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
SPEC = importlib.util.spec_from_file_location(
    "label_qc_tray_scope_review_queue", ROOT / "label_qc_tray_scope_review_queue.py",
)
assert SPEC and SPEC.loader
module = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(module)


def test_merge_keeps_scoped_target_when_candidate_missed_it():
    proposals = module.merge_proposals(
        [{"box": [0.6, 0.6, 0.9, 0.9], "confidence": 0.8, "source": "candidate"}],
        [0.05, 0.6, 0.3, 0.85],
    )

    assert len(proposals) == 2
    assert proposals[-1]["source"] == "visually_scoped_analogue_target"
    assert proposals[-1]["is_ground_truth"] is False


def test_merge_does_not_duplicate_candidate_that_covers_scoped_target():
    proposals = module.merge_proposals(
        [{"box": [0.04, 0.59, 0.31, 0.86], "confidence": 0.8, "source": "candidate"}],
        [0.05, 0.6, 0.3, 0.85],
    )

    assert len(proposals) == 1


def test_yolo_line_rejects_non_box():
    try:
        module.yolo_line([0.5, 0.5, 0.4, 0.6])
    except ValueError as error:
        assert "invalid proposal box" in str(error)
    else:
        raise AssertionError("invalid proposal box was accepted")
