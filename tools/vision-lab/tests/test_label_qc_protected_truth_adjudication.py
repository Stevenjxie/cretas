from __future__ import annotations

import importlib.util
import json
import sys
import tempfile
from pathlib import Path

import pytest
from PIL import Image


ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
SPEC = importlib.util.spec_from_file_location(
    "label_qc_protected_truth_adjudication",
    ROOT / "label_qc_protected_truth_adjudication.py",
)
assert SPEC and SPEC.loader
module = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(module)


def test_judgeable_conflict_with_both_human_labels_is_excluded():
    disposition = module.human_crop_disposition(
        {"status": "JUDGEABLE_TRAY_LABEL_TRUTH_CONFLICT_PENDING"},
        {
            "reviewed": True,
            "source": "human",
            "unjudgeable": False,
            "missing_confirmed_by_human": False,
            "declared_missing_classes": [],
            "boxes": [{"c": 0}, {"c": 1}],
        },
    )

    assert disposition == ("exclude", "human_confirmed_both_labels_present")


def test_judgeable_conflict_rejects_model_or_single_class_evidence():
    with pytest.raises(RuntimeError, match="not resolved"):
        module.human_crop_disposition(
            {"status": "JUDGEABLE_TRAY_LABEL_TRUTH_CONFLICT_PENDING"},
            {
                "reviewed": True,
                "source": "human",
                "unjudgeable": False,
                "missing_confirmed_by_human": False,
                "declared_missing_classes": [],
                "boxes": [{"c": 1}],
            },
        )


def test_unjudgeable_invalid_legacy_target_is_excluded_not_negative():
    disposition = module.human_crop_disposition(
        {"status": "OCCLUDED_OR_INVALID_LEGACY_TARGET_IGNORE"},
        {"unjudgeable": True},
    )

    assert disposition == ("exclude", "occluded_or_invalid_legacy_target")


def test_reviewed_normal_with_only_white_label_becomes_missing_color():
    disposition = module.normal_review_disposition({
        "reviewed": True,
        "source": "human",
        "unjudgeable": False,
        "missing_confirmed_by_human": True,
        "declared_missing_classes": ["color_label"],
        "boxes": [{"c": 0}],
    })

    assert disposition == (
        "MISSING_COLOR_LABEL", "human_confirmed_missing_color_label",
    )


def test_reviewed_normal_rejects_contradictory_missing_declaration():
    with pytest.raises(RuntimeError, match="contradictory"):
        module.normal_review_disposition({
            "reviewed": True,
            "source": "human",
            "unjudgeable": False,
            "missing_confirmed_by_human": True,
            "declared_missing_classes": ["color_label"],
            "boxes": [{"c": 0}, {"c": 1}],
        })


def test_unjudgeable_normal_review_does_not_rewrite_truth():
    assert module.normal_review_disposition({
        "reviewed": True,
        "source": "human",
        "unjudgeable": True,
    }) is None


def test_prospective_review_selects_only_one_human_defect_per_task():
    with tempfile.TemporaryDirectory() as temporary:
        root = Path(temporary)
        queue = root / "queue"
        annotations = queue / "annotations-human"
        images = queue / "images"
        annotations.mkdir(parents=True)
        images.mkdir()
        protected = root / "protected.json"
        protected.write_text(json.dumps({"records": [{
            "photo_id": "old", "task_id": "old-task",
            "image_sha256": "a" * 64, "human_label": "NO_DEFECT",
        }]}), encoding="utf-8")
        source = root / "source.jpg"
        Image.new("RGB", (200, 100), (20, 30, 40)).save(source)
        source_sha = module.sha256_file(source)
        crop_rows = []
        suspects = []
        for index, box in enumerate(([20.0, 10.0, 100.0, 60.0], [90.0, 20.0, 180.0, 80.0]), start=1):
            crop_id = f"crop_{index}"
            crop = images / f"{crop_id}.jpg"
            Image.new("RGB", (80, 60), (40, 30, 20)).save(crop)
            (annotations / f"{crop_id}.json").write_text(json.dumps({
                "crop_id": crop_id,
                "reviewed": True, "source": "human", "unjudgeable": False,
                "declared_missing_classes": ["white_label"],
                "missing_confirmed_by_human": True,
                "boxes": [{"c": 1}],
            }), encoding="utf-8")
            crop_rows.append({
                "queue_index": index, "crop_id": crop_id,
                "image": str(crop), "image_sha256": module.sha256_file(crop),
                "source_photo_id": "new-photo", "source_task_id": "new-task",
                "source_image": str(source), "source_sha256": source_sha,
                "source_reviewed_at": "2026-08-16T00:00:00",
                "sku_code": "SKU", "tray_box_px": box,
                "prospective_independent_before_shadow": True,
                "evaluation_consumed": True, "training_allowed": False,
            })
            suspects.append({"box": box, "verdict": "MISSING_WHITE_LABEL"})
        shadow = root / "shadow.json"
        shadow.write_text(json.dumps({
            "version": "label-qc-screening-param-normal-shadow-v1",
            "promotion_evidence": True, "evaluation_consumed": True,
            "training_started": False, "deployment_started": False,
            "final_model_inference_started": False,
            "inputs": {
                "protected_manifest": str(protected),
                "protected_manifest_sha256": module.sha256_file(protected),
            },
            "batch": {
                "training_independence": [{"independent": True}],
                "sealed_final_independence": {"disjoint": True},
            },
            "details": [{
                "photo_id": "new-photo", "task_id": "new-task",
                "image": str(source), "image_sha256": source_sha,
                "candidate": {"suspects": suspects},
            }],
        }), encoding="utf-8")
        (queue / "manifest.json").write_text(json.dumps({
            "version": "label-qc-prospective-normal-flag-adjudication-v1",
            "queue_count": 2,
            "shadow_receipt": str(shadow),
            "shadow_receipt_sha256": module.sha256_file(shadow),
            "rows": crop_rows,
        }), encoding="utf-8")

        records, audit = module.load_prospective_review_records(
            queue, protected.resolve(), {
                "old": {
                    "photo_id": "old", "task_id": "old-task",
                    "image_sha256": "a" * 64, "human_label": "NO_DEFECT",
                },
            },
        )

        assert len(records) == 1
        assert records[0]["photo_id"] == "new-photo"
        assert records[0]["task_id"] == "new-task"
        assert records[0]["selection_provenance"]["unbiased_recall_estimate"] is False
        review_audit = audit["prospective_review_audit"]
        assert review_audit["confirmed_defect_crops"] == 2
        assert review_audit["confirmed_defect_tasks"] == 1
        assert len(review_audit["selected_task_representatives"]) == 1
