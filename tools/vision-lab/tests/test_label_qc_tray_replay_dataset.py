import importlib.util
import json
import sys
from collections import Counter
from pathlib import Path

import pytest


ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))
for name in ("label_qc_tray_patch_dataset",):
    if name not in sys.modules:
        spec = importlib.util.spec_from_file_location(name, ROOT / f"{name}.py")
        dependency = importlib.util.module_from_spec(spec)
        assert spec.loader is not None
        sys.modules[name] = dependency
        spec.loader.exec_module(dependency)
spec = importlib.util.spec_from_file_location(
    "label_qc_tray_replay_dataset", ROOT / "label_qc_tray_replay_dataset.py",
)
module = importlib.util.module_from_spec(spec)
assert spec.loader is not None
spec.loader.exec_module(module)


def source_dataset(tmp_path: Path, protected=False):
    root = tmp_path / "source"
    for split in ("train", "val"):
        (root / "images" / split).mkdir(parents=True)
        (root / "labels" / split).mkdir(parents=True)
    recent_queue = tmp_path / "recent"
    recent_queue.mkdir()
    (recent_queue / "manifest.json").write_text("{}", encoding="utf-8")
    rows = []
    for stem, queue in (("old-a", tmp_path / "old"), ("old-b", tmp_path / "old"), ("new-a", recent_queue)):
        (root / "images" / "train" / f"{stem}.jpg").write_bytes(stem.encode())
        (root / "labels" / "train" / f"{stem}.txt").write_text("0 0.5 0.5 0.2 0.2\n", encoding="utf-8")
        rows.append({"stem": stem, "split": "train", "queue": str(queue), "task_id": stem})
    (root / "images" / "val" / "val-a.jpg").write_bytes(b"val")
    (root / "labels" / "val" / "val-a.txt").write_text("0 0.5 0.5 0.2 0.2\n", encoding="utf-8")
    rows.append({"stem": "val-a", "split": "val", "queue": str(tmp_path / "old"), "task_id": "val-a"})
    (root / "data.yaml").write_text("train: images/train\nval: images/val\n", encoding="utf-8")
    (root / "provenance.json").write_text(json.dumps({"rows": rows}), encoding="utf-8")
    manifest = {
        "dataset_id": "source-dataset",
        "training_allowed": True,
        "protected_holdout_included": protected,
        "preannotations_used_as_truth": False,
        "deleted_proposals_used_as_background": False,
        "images_with_ignore_regions_excluded_from_training": True,
        "validation_unchanged_from_base_dataset": True,
        "data_yaml": str(root / "data.yaml"),
    }
    manifest["dataset_sha256"] = module.patch_dataset.dataset_digest(root)
    path = root / "manifest.json"
    path.write_text(json.dumps(manifest), encoding="utf-8")
    return path, recent_queue


def test_build_replay_dataset_repeats_only_historical_rows(tmp_path):
    source, recent = source_dataset(tmp_path)
    output, manifest = module.build_replay_dataset(source, recent, tmp_path / "out", 2)
    lines = (output / "train-replay.txt").read_text(encoding="utf-8").splitlines()
    counts = Counter(Path(line).stem for line in lines)
    assert counts == {"old-a": 2, "old-b": 2, "new-a": 1}
    assert manifest["replay_policy"]["effective_train_occurrences"] == 5
    assert manifest["validation_unchanged_from_base_dataset"] is True
    assert manifest["protected_holdout_included"] is False
    assert manifest["dataset_sha256"] == module.patch_dataset.dataset_digest(output)


def test_build_replay_dataset_is_content_addressed_and_idempotent(tmp_path):
    source, recent = source_dataset(tmp_path)
    first, first_manifest = module.build_replay_dataset(source, recent, tmp_path / "out", 2)
    second, second_manifest = module.build_replay_dataset(source, recent, tmp_path / "out", 2)
    assert first == second
    assert first_manifest["identity"] == second_manifest["identity"]


def test_build_replay_dataset_rejects_protected_source(tmp_path):
    source, recent = source_dataset(tmp_path, protected=True)
    with pytest.raises(RuntimeError, match="protected_holdout_included"):
        module.build_replay_dataset(source, recent, tmp_path / "out", 2)
