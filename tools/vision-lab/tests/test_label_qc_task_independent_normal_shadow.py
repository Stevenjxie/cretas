import importlib.util
import sys
from pathlib import Path

import pytest


ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))
for name in (
    "label_qc_oracle_diagnostics", "label_qc_tray_edge_context_eval",
    "label_qc_independent_normal_shadow", "label_qc_tray_patch_dataset",
):
    if name not in sys.modules:
        path = ROOT / f"{name}.py"
        spec = importlib.util.spec_from_file_location(name, path)
        module = importlib.util.module_from_spec(spec)
        assert spec.loader is not None
        sys.modules[name] = module
        spec.loader.exec_module(module)
spec = importlib.util.spec_from_file_location(
    "label_qc_task_independent_normal_shadow",
    ROOT / "label_qc_task_independent_normal_shadow.py",
)
module = importlib.util.module_from_spec(spec)
assert spec.loader is not None
spec.loader.exec_module(module)


def source_row(tmp_path: Path, photo: str, task: str):
    image = tmp_path / f"{photo}.jpg"
    image.write_bytes(photo.encode())
    return {
        "photo_id": photo,
        "task_id": task,
        "reviewed_at": "2026-08-16T00:00:00",
        "sku_code": "sku",
        "object_ref": "object",
        "image": str(image),
        "image_sha256": module.diagnostics.sha256_file(image),
        "human_labels": ["NO_DEFECT"],
        "proposal_sources": ["HUMAN"],
    }


def test_select_task_independent_records_uses_exact_locked_ids_and_tasks(tmp_path):
    rows = [
        source_row(tmp_path, "photo-train-related", "task-train"),
        source_row(tmp_path, "photo-holdout-1", "task-holdout-1"),
        source_row(tmp_path, "photo-holdout-2", "task-holdout-2"),
    ]
    dataset = {"append_audit": {"normal_holdout_lock": {
        "remaining_pool_is_future_independent": True,
        "task_independent_photo_ids": ["photo-holdout-1", "photo-holdout-2"],
        "task_independent_photos": 2,
        "task_independent_tasks": 2,
        "consumed_task_ids": ["task-train"],
    }}}
    selected = module.select_task_independent_records({"details": rows}, dataset)
    assert [row["photo_id"] for row in selected] == ["photo-holdout-1", "photo-holdout-2"]


def test_select_task_independent_records_rejects_consumed_task(tmp_path):
    rows = [source_row(tmp_path, "photo-holdout", "task-train")]
    dataset = {"append_audit": {"normal_holdout_lock": {
        "remaining_pool_is_future_independent": True,
        "task_independent_photo_ids": ["photo-holdout"],
        "task_independent_photos": 1,
        "task_independent_tasks": 1,
        "consumed_task_ids": ["task-train"],
    }}}
    with pytest.raises(RuntimeError, match="consumed training task"):
        module.select_task_independent_records({"details": rows}, dataset)


def test_select_secondary_regression_records_is_explicitly_not_future_independent(tmp_path):
    rows = [
        source_row(tmp_path, "photo-secondary-1", "task-secondary-1"),
        source_row(tmp_path, "photo-secondary-2", "task-secondary-2"),
    ]
    dataset = {"append_audit": {"normal_holdout_lock": {
        "remaining_pool_is_future_independent": False,
        "secondary_regression_photo_ids": ["photo-secondary-1", "photo-secondary-2"],
        "secondary_regression_photos": 2,
        "consumed_task_ids": ["task-train"],
    }}}

    selected = module.select_normal_records(
        {"details": rows}, dataset, module.SECONDARY_REGRESSION_POOL,
    )

    assert [row["photo_id"] for row in selected] == ["photo-secondary-1", "photo-secondary-2"]


def test_future_independent_selection_fails_closed_for_consumed_pool(tmp_path):
    rows = [source_row(tmp_path, "photo-secondary", "task-secondary")]
    dataset = {"append_audit": {"normal_holdout_lock": {
        "remaining_pool_is_future_independent": False,
        "task_independent_photo_ids": [],
        "task_independent_photos": 0,
        "task_independent_tasks": 0,
        "consumed_task_ids": [],
    }}}

    with pytest.raises(RuntimeError, match="not declared future-independent"):
        module.select_task_independent_records({"details": rows}, dataset)
