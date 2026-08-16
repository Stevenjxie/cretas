import importlib.util
import json
import sys
from pathlib import Path

import pytest


ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))
for name in (
    "label_qc_oracle_diagnostics", "label_qc_tray_edge_context_eval",
    "label_qc_normal_regression_review_queue",
):
    if name not in sys.modules:
        path = ROOT / f"{name}.py"
        spec = importlib.util.spec_from_file_location(name, path)
        module = importlib.util.module_from_spec(spec)
        assert spec.loader is not None
        sys.modules[name] = module
        spec.loader.exec_module(module)
spec = importlib.util.spec_from_file_location(
    "label_qc_task_regression_review_queue",
    ROOT / "label_qc_task_regression_review_queue.py",
)
module = importlib.util.module_from_spec(spec)
assert spec.loader is not None
spec.loader.exec_module(module)


def row(photo: str, task: str, baseline: bool = False, candidate: bool = False):
    return {
        "photo_id": photo,
        "task_id": task,
        "reviewed_at": photo,
        "human_labels": ["NO_DEFECT"],
        "baseline": {"flagged": baseline},
        "candidate": {"flagged": candidate},
    }


def test_select_task_expanded_rows_places_regressions_first_and_keeps_companions():
    shadow = {"details": [
        row("a-companion", "task-a"),
        row("b-regression", "task-b", candidate=True),
        row("a-regression", "task-a", candidate=True),
        row("b-companion", "task-b"),
        row("unrelated", "task-c"),
    ]}
    selected, photos, tasks = module.select_task_expanded_rows(shadow, 2)
    assert [item["photo_id"] for item in selected[:2]] == ["a-regression", "b-regression"]
    assert {item["photo_id"] for item in selected} == {
        "a-regression", "a-companion", "b-regression", "b-companion",
    }
    assert photos == {"a-regression", "b-regression"}
    assert tasks == {"task-a", "task-b"}


def test_select_task_expanded_rows_rejects_wrong_expected_count():
    shadow = {"details": [row("regression", "task", candidate=True)]}
    with pytest.raises(RuntimeError, match="expected 2"):
        module.select_task_expanded_rows(shadow, 2)


def test_normalise_fresh_factory_receipt_binds_models(tmp_path):
    production = tmp_path / "production.onnx"
    candidate_model = tmp_path / "candidate.onnx"
    label = tmp_path / "label.onnx"
    for path, content in ((production, b"prod"), (candidate_model, b"candidate"), (label, b"label")):
        path.write_bytes(content)
    config = tmp_path / "config.json"
    config.write_text(json.dumps({"tray_active_learning": {
        "production_tray_onnx": str(production),
        "production_tray_sha256": module.diagnostics.sha256_file(production),
        "production_label_onnx": str(label),
        "production_label_sha256": module.diagnostics.sha256_file(label),
    }}), encoding="utf-8")
    candidate = tmp_path / "candidate.json"
    candidate.write_text(json.dumps({
        "artifact": str(candidate_model),
        "artifact_sha256": module.diagnostics.sha256_file(candidate_model),
    }), encoding="utf-8")
    payload = {
        "version": "label-qc-fresh-factory-development-shadow-v1",
        "deployment_started": False,
        "production_writes": 0,
        "inputs": {
            "config": str(config),
            "config_sha256": module.diagnostics.sha256_file(config),
            "candidate_receipt": str(candidate),
            "candidate_receipt_sha256": module.diagnostics.sha256_file(candidate),
        },
        "split_lock": {"development_photos": 2},
        "development": {"independence": {"independent": True}, "details": [row("p", "t")]},
    }
    normalised = module.normalise_shadow_receipt(payload)
    assert normalised["batch"]["photos"] == 2
    assert normalised["inputs"]["candidate_tray_model"] == str(candidate_model)
