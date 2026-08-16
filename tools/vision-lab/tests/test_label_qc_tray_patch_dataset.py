from __future__ import annotations

import importlib.util
from pathlib import Path

import pytest
from PIL import Image


ROOT = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location(
    "label_qc_tray_patch_dataset", ROOT / "label_qc_tray_patch_dataset.py",
)
assert SPEC and SPEC.loader
module = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(module)


def rows():
    return [
        {"photo_id": "a", "task_id": "ta"},
        {"photo_id": "b", "task_id": "tb"},
        {"photo_id": "c", "task_id": "tc"},
    ]


def test_scope_requires_every_mined_candidate_to_be_classified():
    with pytest.raises(ValueError, match="classification incomplete"):
        module.classify_candidates(rows(), ["a", "b"], {})


def test_scope_keeps_occluded_label_value_separate_from_tray_truth():
    accepted, rejected = module.classify_candidates(
        rows(), ["a", "b"], {"c": "multi_tray_group"},
    )

    assert [row["scope_status"] for row in accepted] == ["JUDGEABLE_FULL_TRAY"] * 2
    assert rejected[0]["scope_status"] == "TRAY_BOX_EXCLUDED"
    assert rejected[0]["label_presence_truth_retained_separately"] is True


def test_exclusion_parser_fails_closed_on_unknown_reason():
    with pytest.raises(ValueError, match="invalid exclusion"):
        module.parse_exclusions(["photo=looks_bad"])


def test_context_crop_rebinds_target_box_without_using_whole_image():
    image = Image.new("RGB", (1000, 500), "white")

    crop, target, rect = module.crop_with_context(image, [0.2, 0.2, 0.6, 0.6], 0.1)

    assert crop.size == (480, 240)
    assert rect == [160, 80, 640, 320]
    assert target == pytest.approx([1 / 12, 1 / 12, 11 / 12, 11 / 12])
    assert module.yolo_line(target).startswith("0 0.500000 0.500000")
