import importlib.util
import sys
from pathlib import Path

import pytest


ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))
for name in (
    "label_qc_tray_patch_dataset", "label_qc_oracle_diagnostics",
    "label_qc_tray_edge_context_eval", "label_qc_independent_normal_shadow",
):
    if name not in sys.modules:
        spec = importlib.util.spec_from_file_location(name, ROOT / f"{name}.py")
        dependency = importlib.util.module_from_spec(spec)
        assert spec.loader is not None
        sys.modules[name] = dependency
        spec.loader.exec_module(dependency)
spec = importlib.util.spec_from_file_location(
    "label_qc_fresh_factory_shadow", ROOT / "label_qc_fresh_factory_shadow.py",
)
module = importlib.util.module_from_spec(spec)
assert spec.loader is not None
spec.loader.exec_module(module)


def row(photo, task, digest):
    return {"photo_id": photo, "task_id": task, "image_sha256": digest}


def test_validate_split_locks_task_and_content_disjoint_sets():
    development = [row("p1", "t1", "1" * 64), row("p2", "t1", "2" * 64)]
    final = [row("p3", "t2", "3" * 64), row("p4", "t3", "4" * 64)]
    lock = module.validate_split(development, final, 2, 2)
    assert lock["task_disjoint"] is True
    assert lock["final_model_inference_started"] is False
    assert lock["final_tasks"] == 2


def test_validate_split_rejects_task_leakage():
    development = [row("p1", "t1", "1" * 64)]
    final = [row("p2", "t1", "2" * 64)]
    with pytest.raises(RuntimeError, match="leaks a task"):
        module.validate_split(development, final, 1, 1)


@pytest.mark.parametrize("value", ["missing-comma", ",photo", "time,"])
def test_watermark_rejects_malformed_value(value):
    with pytest.raises(ValueError):
        module.watermark(value)
