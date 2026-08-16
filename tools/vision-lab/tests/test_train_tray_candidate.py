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
spec = importlib.util.spec_from_file_location("train_tray_candidate", ROOT / "train_tray_candidate.py")
module = importlib.util.module_from_spec(spec)
assert spec.loader is not None
spec.loader.exec_module(module)


def write_dataset(tmp_path: Path, **overrides) -> Path:
    (tmp_path / "data.yaml").write_text("path: .\n", encoding="utf-8")
    manifest = {
        "training_allowed": True,
        "protected_holdout_included": False,
        "preannotations_used_as_truth": False,
        "deleted_proposals_used_as_background": False,
        "images_with_ignore_regions_excluded_from_training": True,
        "validation_unchanged_from_base_dataset": True,
        "data_yaml": str(tmp_path / "data.yaml"),
    } | overrides
    manifest["dataset_sha256"] = module.patch_dataset.dataset_digest(tmp_path)
    path = tmp_path / "manifest.json"
    path.write_text(json.dumps(manifest), encoding="utf-8")
    return path


def test_validate_dataset_manifest_accepts_safe_train_only_dataset(tmp_path):
    path = write_dataset(tmp_path)
    assert module.validate_dataset_manifest(path)["training_allowed"] is True


@pytest.mark.parametrize("field,value", [
    ("training_allowed", False),
    ("protected_holdout_included", True),
    ("preannotations_used_as_truth", True),
    ("deleted_proposals_used_as_background", True),
    ("images_with_ignore_regions_excluded_from_training", False),
    ("validation_unchanged_from_base_dataset", False),
])
def test_validate_dataset_manifest_rejects_unsafe_contract(tmp_path, field, value):
    path = write_dataset(tmp_path, **{field: value})
    with pytest.raises(RuntimeError):
        module.validate_dataset_manifest(path)


def test_apply_training_overrides_is_explicit_and_does_not_mutate_source():
    config = {"tray_active_learning": {"training": {"epochs": 40, "lr0": 0.00008, "freeze": 15}}}
    updated = module.apply_training_overrides(
        config, epochs=20, patience=8, lr0=0.00004, freeze=18,
    )
    assert config["tray_active_learning"]["training"]["epochs"] == 40
    assert updated["tray_active_learning"]["training"] == {
        "epochs": 20, "lr0": 0.00004, "freeze": 18, "patience": 8,
    }


@pytest.mark.parametrize("field,value", [
    ("epochs", 0), ("patience", 0), ("lr0", 0.0), ("freeze", 23),
])
def test_apply_training_overrides_rejects_unsafe_ranges(field, value):
    config = {"tray_active_learning": {"training": {}}}
    with pytest.raises(ValueError):
        module.apply_training_overrides(config, **{field: value})
