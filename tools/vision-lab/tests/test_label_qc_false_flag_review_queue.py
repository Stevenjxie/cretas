from __future__ import annotations

import importlib.util
import sys
from pathlib import Path
from types import SimpleNamespace


ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
SPEC = importlib.util.spec_from_file_location(
    "label_qc_false_flag_review_queue",
    ROOT / "label_qc_false_flag_review_queue.py",
)
assert SPEC and SPEC.loader
module = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(module)


def test_changed_normal_photo_ids_selects_only_reduced_false_flags():
    metrics = {
        "baseline": {"details": [
            {"photo_id": "normal-a", "kind": "normal", "false_flags": 1},
            {"photo_id": "normal-b", "kind": "normal", "false_flags": 0},
            {"photo_id": "defect", "kind": "defect", "false_flags": 2},
        ]},
        "candidate": {"details": [
            {"photo_id": "normal-a", "kind": "normal", "false_flags": 0},
            {"photo_id": "normal-b", "kind": "normal", "false_flags": 0},
            {"photo_id": "defect", "kind": "defect", "false_flags": 0},
        ]},
    }

    assert module.changed_normal_photo_ids(metrics) == ["normal-a"]


def test_transition_requires_matching_unjudgeable_candidate():
    baseline = SimpleNamespace(suspects=[
        SimpleNamespace(box=[0, 0, 100, 100]),
        SimpleNamespace(box=[200, 200, 300, 300]),
    ])
    candidate = SimpleNamespace(review_candidates=[
        SimpleNamespace(box=[1, 1, 99, 99], verdict="UNJUDGEABLE"),
        SimpleNamespace(box=[200, 200, 300, 300], verdict="MISSING_WHITE_LABEL"),
    ])

    pairs = module.transitioned_trays(baseline, candidate)

    assert len(pairs) == 1
    assert pairs[0][0].box == [0, 0, 100, 100]


def test_normalise_in_crop_clips_and_preserves_label_box():
    assert module.normalise_in_crop(
        [90, 120, 160, 180], [100, 100, 200, 200],
    ) == [0.0, 0.2, 0.6, 0.8]
