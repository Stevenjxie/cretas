from __future__ import annotations

import importlib.util
import sys
from pathlib import Path

from PIL import Image


ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
SPEC = importlib.util.spec_from_file_location(
    "label_qc_param_shadow_review_queue",
    ROOT / "label_qc_param_shadow_review_queue.py",
)
assert SPEC and SPEC.loader
module = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(module)


def test_selected_suspects_matches_candidate_summary():
    receipt = {
        "candidate": {"false_flags": 2},
        "details": [
            {"candidate": {"suspects": [{"box": [1, 2, 3, 4]}]}},
            {"candidate": {"suspects": [{"box": [5, 6, 7, 8]}]}},
        ],
    }

    assert len(module.selected_suspects(receipt)) == 2


def test_crop_with_padding_clamps_to_image():
    image = Image.new("RGB", (100, 80))

    crop, rect = module.crop_with_padding(image, [0, 0, 20, 10], 0.5)

    assert rect == [0, 0, 30, 15]
    assert crop.size == (30, 15)
