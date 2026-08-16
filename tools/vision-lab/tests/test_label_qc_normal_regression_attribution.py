from __future__ import annotations

import importlib.util
import sys
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
SPEC = importlib.util.spec_from_file_location(
    "label_qc_normal_regression_attribution",
    ROOT / "label_qc_normal_regression_attribution.py",
)
assert SPEC and SPEC.loader
module = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(module)


def test_classify_regression_separates_model_and_edge_sources():
    assert module.classify_regression(0, 1, 1) == "candidate_model"
    assert module.classify_regression(0, 0, 1) == "edge_windows"
    assert module.classify_regression(0, 1, 2) == "candidate_model_and_edge"
    assert module.classify_regression(1, 0, 0) == "not_regressed"


def test_selected_rows_keeps_all_flagged_and_changed_photos():
    rows = [
        {"photo_id": "clear", "baseline": {"flagged": False}, "candidate": {"flagged": False}, "false_flag_delta": 0},
        {"photo_id": "base", "baseline": {"flagged": True}, "candidate": {"flagged": False}, "false_flag_delta": -1},
        {"photo_id": "candidate", "baseline": {"flagged": False}, "candidate": {"flagged": True}, "false_flag_delta": 1},
    ]

    assert [row["photo_id"] for row in module.selected_rows(rows)] == ["base", "candidate"]
