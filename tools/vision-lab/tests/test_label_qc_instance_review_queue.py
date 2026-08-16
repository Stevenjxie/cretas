from __future__ import annotations

import importlib.util
import sys
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
SPEC = importlib.util.spec_from_file_location(
    "label_qc_instance_review_queue", ROOT / "label_qc_instance_review_queue.py",
)
assert SPEC and SPEC.loader
module = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(module)


def test_normalise_box_and_yolo_line_preserve_geometry():
    box = module.normalise_box([100, 50, 300, 250], 400, 400)

    assert box == [0.25, 0.125, 0.75, 0.625]
    assert module.yolo_line(box) == "0 0.50000000 0.37500000 0.50000000 0.50000000\n"


def test_collect_proposals_adds_only_recovered_zoom_target_and_never_legacy_truth():
    baseline = {
        "screening": {
            "trays": [{"box": [0, 0, 100, 100], "confidence": 0.8}],
        },
    }
    zoom = {
        "truth_box": [100, 100, 200, 200],
        "summary": {
            "best_setting": {
                "detections": [
                    {"box": [100, 100, 200, 200], "confidence": 0.35,
                     "truth_containment": 0.9},
                    {"box": [250, 250, 350, 350], "confidence": 0.9,
                     "truth_containment": 0.0},
                ],
            },
        },
    }

    proposals = module.collect_proposals(baseline, zoom, 400, 400)

    assert [proposal["source"] for proposal in proposals] == [
        "protected_target_crop_oracle_proposal",
        "production_full_frame_tray_proposal",
    ]
    assert [0.25, 0.25, 0.5, 0.5] in [proposal["box"] for proposal in proposals]
    assert [0.625, 0.625, 0.875, 0.875] not in [
        proposal["box"] for proposal in proposals
    ]


def test_deduplicate_keeps_higher_priority_oracle_proposal():
    proposals = [
        {"box": [0, 0, 0.5, 0.5], "confidence": 0.9, "priority": 2},
        {"box": [0, 0, 0.5, 0.5], "confidence": 0.3, "priority": 3},
    ]

    kept = module.deduplicate_proposals(proposals)

    assert kept == [proposals[1]]
