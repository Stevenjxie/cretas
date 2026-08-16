import importlib.util
import json
import sys
from pathlib import Path

import pytest


ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))
for name in ("label_qc_tray_patch_dataset", "tray_workflow"):
    if name not in sys.modules:
        path = ROOT / f"{name}.py"
        spec = importlib.util.spec_from_file_location(name, path)
        module = importlib.util.module_from_spec(spec)
        assert spec.loader is not None
        sys.modules[name] = module
        spec.loader.exec_module(module)
spec = importlib.util.spec_from_file_location(
    "label_qc_tray_full_image_dataset", ROOT / "label_qc_tray_full_image_dataset.py",
)
module = importlib.util.module_from_spec(spec)
assert spec.loader is not None
spec.loader.exec_module(module)


def reviewed(stem: str, task: str, sha: str, boxes: int = 2):
    return {
        "source": {"packed_stem": stem, "source_sha256": sha},
        "task_id": task,
        "annotation_sha256": "a" * 64,
        "boxes": [[0.1, 0.1, 0.3, 0.3]] * boxes,
    }


def safe_manifest(rows):
    return {
        "protected_holdout_included": False,
        "all_rows_train_only": True,
        "preannotations_are_not_ground_truth": True,
        "annotation_policy": {
            "occluded_lower_trays": "ignore_not_negative",
            "visible_lower_labels": "separate_label_side_view_truth",
        },
        "rows": [
            {"train_only": True, "protected_target": False} for _ in rows
        ],
    }


def normal_reviewed(stem: str, photo_id: str, task: str, sha: str, box=None):
    truth = box or [0.1, 0.1, 0.3, 0.3]
    return {
        "source": {
            "packed_stem": stem,
            "source_photo_id": photo_id,
            "source_sha256": sha,
            "preannotations": [{"box": truth}],
        },
        "task_id": task,
        "annotation_sha256": "a" * 64,
        "boxes": [truth],
    }


def normal_manifest(tmp_path: Path, rows, extra_details=None):
    details = [
        {
            "photo_id": row["source"]["source_photo_id"],
            "task_id": row["task_id"],
            "image_sha256": row["source"]["source_sha256"],
        }
        for row in rows
    ]
    details.extend(extra_details or [])
    shadow = tmp_path / "shadow.json"
    shadow.write_text(json.dumps({"batch": {"photos": len(details)}, "details": details}), encoding="utf-8")
    return {
        "version": module.NORMAL_REGRESSION_QUEUE_VERSION,
        "protected_holdout_included": False,
        "preannotations_are_not_ground_truth": True,
        "every_image_requires_full_human_review": True,
        "unique_tasks": len({row["task_id"] for row in rows}),
        "source_normal_batch_photos": len(details),
        "remaining_locked_normal_photos": len(details) - len(rows),
        "remaining_pool_is_future_independent": True,
        "source_shadow_receipt": str(shadow),
        "source_shadow_receipt_sha256": module.patch_dataset.sha256_file(shadow),
        "review_gate": {
            "training_allowed_before_complete_review": False,
            "training_use_after_gate": "train_only_hard_cases",
            "promotion_allowed": False,
            "deployment_allowed": False,
        },
        "annotation_policy": {
            "partial_or_occluded_tray": "delete proposal and retain as ignore_not_negative",
            "visible_labels_on_ignored_tray": "separate_label_side_view_truth",
            "deleted_proposals_are_background": False,
        },
        "rows": [
            {
                "train_only_after_complete_human_review": True,
                "evaluation_consumed": True,
                "exclude_from_future_independent_holdout": True,
                "protected_target": False,
                "human_photo_truth": "NO_DEFECT",
            }
            for _ in rows
        ],
    }


def fresh_normal_manifest(tmp_path: Path, rows, extra_details=None):
    manifest = normal_manifest(tmp_path, rows, extra_details)
    old_shadow = Path(manifest["source_shadow_receipt"])
    details = json.loads(old_shadow.read_text(encoding="utf-8"))["details"]
    shadow = {
        "version": module.FRESH_FACTORY_SHADOW_VERSION,
        "split_lock": {
            "development_photos": len(details),
            "development_photo_ids": [row["photo_id"] for row in details],
            "development_image_sha256": [row["image_sha256"] for row in details],
            "final_model_inference_started": False,
            "final_training_use_allowed": False,
        },
        "development": {"details": details},
        "final": {
            "evaluated": False,
            "model_inference_started": False,
            "training_use_allowed": False,
        },
    }
    fresh_shadow = tmp_path / "fresh-shadow.json"
    fresh_shadow.write_text(json.dumps(shadow), encoding="utf-8")
    manifest["source_shadow_receipt"] = str(fresh_shadow)
    manifest["source_shadow_receipt_sha256"] = module.patch_dataset.sha256_file(fresh_shadow)
    return manifest


def test_append_contract_accepts_independent_train_only_rows():
    rows = [reviewed("one", "task-1", "1" * 64), reviewed("two", "task-2", "2" * 64)]
    module.validate_append_contract(
        {"protected_holdout_included": False}, safe_manifest(rows), rows,
        [{"source_sha256": "3" * 64, "task_id": "old-task"}],
    )


@pytest.mark.parametrize("field,value", [
    ("all_rows_train_only", False),
    ("preannotations_are_not_ground_truth", False),
    ("protected_holdout_included", True),
])
def test_append_contract_rejects_unsafe_queue_flags(field, value):
    rows = [reviewed("one", "task-1", "1" * 64), reviewed("two", "task-2", "2" * 64)]
    manifest = safe_manifest(rows)
    manifest[field] = value
    with pytest.raises(RuntimeError):
        module.validate_append_contract(
            {"protected_holdout_included": False}, manifest, rows, [],
        )


def test_append_contract_rejects_base_task_overlap():
    rows = [reviewed("one", "task-1", "1" * 64), reviewed("two", "task-2", "2" * 64)]
    with pytest.raises(RuntimeError, match="task already exists"):
        module.validate_append_contract(
            {"protected_holdout_included": False}, safe_manifest(rows), rows,
            [{"source_sha256": "3" * 64, "task_id": "task-2"}],
        )


def test_normal_regression_contract_allows_repeated_selected_task_but_locks_task_holdout(tmp_path):
    rows = [
        normal_reviewed("one", "photo-1", "task-a", "1" * 64),
        normal_reviewed("two", "photo-2", "task-a", "2" * 64),
        normal_reviewed("three", "photo-3", "task-b", "3" * 64),
    ]
    manifest = normal_manifest(tmp_path, rows, [
        {"photo_id": "photo-4", "task_id": "task-a", "image_sha256": "4" * 64},
        {"photo_id": "photo-5", "task_id": "task-c", "image_sha256": "5" * 64},
        {"photo_id": "photo-6", "task_id": "task-d", "image_sha256": "6" * 64},
    ])
    audit = module.validate_append_contract(
        {"protected_holdout_included": False}, manifest, rows, [],
    )
    assert audit["training_images"] == 3
    assert audit["normal_holdout_lock"]["photo_level_remaining"] == 3
    assert audit["normal_holdout_lock"]["same_task_excluded_photos"] == 1
    assert audit["normal_holdout_lock"]["task_disjoint_remaining_photos"] == 2
    assert audit["normal_holdout_lock"]["task_independent_photos"] == 2
    assert audit["normal_holdout_lock"]["secondary_regression_photos"] == 0


def test_normal_regression_contract_keeps_consumed_remainder_out_of_future_holdout(tmp_path):
    rows = [
        normal_reviewed("one", "photo-1", "task-a", "1" * 64),
        normal_reviewed("two", "photo-2", "task-b", "2" * 64),
    ]
    manifest = normal_manifest(tmp_path, rows, [
        {"photo_id": "photo-3", "task_id": "task-c", "image_sha256": "3" * 64},
        {"photo_id": "photo-4", "task_id": "task-d", "image_sha256": "4" * 64},
    ])
    manifest["remaining_pool_is_future_independent"] = False
    manifest["remaining_pool_reason"] = "source batch already consumed by model selection"

    audit = module.validate_append_contract(
        {"protected_holdout_included": False}, manifest, rows, [],
    )

    lock = audit["normal_holdout_lock"]
    assert lock["task_disjoint_remaining_photos"] == 2
    assert lock["remaining_pool_is_future_independent"] is False
    assert lock["task_independent_photos"] == 0
    assert lock["task_independent_photo_ids"] == []
    assert lock["secondary_regression_photos"] == 2
    assert lock["secondary_regression_photo_ids"] == ["photo-3", "photo-4"]


def test_normal_regression_contract_accepts_sealed_fresh_factory_development_receipt(tmp_path):
    rows = [
        normal_reviewed("one", "photo-1", "task-a", "1" * 64),
        normal_reviewed("two", "photo-2", "task-b", "2" * 64),
    ]
    manifest = fresh_normal_manifest(tmp_path, rows, [
        {"photo_id": "photo-3", "task_id": "task-c", "image_sha256": "3" * 64},
    ])

    audit = module.validate_append_contract(
        {"protected_holdout_included": False}, manifest, rows, [],
    )

    assert audit["training_images"] == 2
    assert audit["normal_holdout_lock"]["source_photos"] == 3


def test_normal_regression_contract_rejects_unsealed_fresh_factory_final(tmp_path):
    rows = [
        normal_reviewed("one", "photo-1", "task-a", "1" * 64),
        normal_reviewed("two", "photo-2", "task-b", "2" * 64),
    ]
    manifest = fresh_normal_manifest(tmp_path, rows)
    shadow_path = Path(manifest["source_shadow_receipt"])
    shadow = json.loads(shadow_path.read_text(encoding="utf-8"))
    shadow["final"]["model_inference_started"] = True
    shadow_path.write_text(json.dumps(shadow), encoding="utf-8")
    manifest["source_shadow_receipt_sha256"] = module.patch_dataset.sha256_file(shadow_path)

    with pytest.raises(RuntimeError, match="final split is not sealed"):
        module.validate_append_contract(
            {"protected_holdout_included": False}, manifest, rows, [],
        )


def test_normal_regression_contract_excludes_image_with_removed_ignore_region(tmp_path):
    rows = [
        normal_reviewed("one", "photo-1", "task-a", "1" * 64),
        normal_reviewed("two", "photo-2", "task-b", "2" * 64),
        normal_reviewed("three", "photo-3", "task-c", "3" * 64),
    ]
    rows[2]["source"]["preannotations"] = [{"box": [0.7, 0.7, 0.9, 0.9]}]
    manifest = normal_manifest(tmp_path, rows)
    audit = module.validate_append_contract(
        {"protected_holdout_included": False}, manifest, rows, [],
    )
    assert audit["removed_ignore_regions"] == 1
    assert audit["training_images"] == 2
    assert audit["excluded_images"] == 1
    assert audit["row_audits"][2]["training_exclusion_reason"] == "contains_ignore_not_negative_region"


def test_normal_regression_contract_rejects_duplicate_source_hash(tmp_path):
    rows = [
        normal_reviewed("one", "photo-1", "task-a", "1" * 64),
        normal_reviewed("two", "photo-2", "task-b", "1" * 64),
    ]
    manifest = normal_manifest(tmp_path, rows)
    with pytest.raises(RuntimeError, match="source hashes are not unique"):
        module.validate_append_contract(
            {"protected_holdout_included": False}, manifest, rows, [],
        )


def test_normal_regression_contract_rejects_prior_task_overlap(tmp_path):
    rows = [
        normal_reviewed("one", "photo-1", "task-a", "1" * 64),
        normal_reviewed("two", "photo-2", "task-b", "2" * 64),
    ]
    manifest = normal_manifest(tmp_path, rows)
    with pytest.raises(RuntimeError, match="task already exists"):
        module.validate_append_contract(
            {"protected_holdout_included": False}, manifest, rows,
            [{"source_sha256": "3" * 64, "task_id": "task-b"}],
        )
