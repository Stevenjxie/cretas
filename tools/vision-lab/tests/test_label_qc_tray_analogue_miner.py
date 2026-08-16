from __future__ import annotations

import importlib.util
import sys
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
SPEC = importlib.util.spec_from_file_location(
    "label_qc_tray_analogue_miner", ROOT / "label_qc_tray_analogue_miner.py",
)
assert SPEC and SPEC.loader
module = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(module)


def test_geometry_distance_treats_left_and_right_edge_as_analogous():
    target = [0.02, 0.65, 0.32, 0.84]
    mirrored = [0.68, 0.65, 0.98, 0.84]

    assert module.geometry_distance(target, mirrored) < 1e-12
    assert module.is_target_like(target, mirrored) is True


def test_target_like_filter_rejects_small_occluded_strip_and_central_tray():
    target = [0.02, 0.65, 0.32, 0.84]

    assert module.is_target_like(target, [0.01, 0.70, 0.05, 0.84]) is False
    assert module.is_target_like(target, [0.35, 0.65, 0.65, 0.84]) is False


def test_queue_consumption_check_normalises_paths(tmp_path):
    queue = tmp_path / "queue"
    queue.mkdir()
    prior = {"queues": [str(queue)]}

    assert module.queue_is_unconsumed(queue, prior) is False
    assert module.queue_is_unconsumed(tmp_path / "new-queue", prior) is True
