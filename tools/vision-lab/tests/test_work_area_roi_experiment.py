from __future__ import annotations

import importlib.util
import sys
import unittest
from pathlib import Path

import numpy as np


ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
SPEC = importlib.util.spec_from_file_location(
    "work_area_roi_experiment", ROOT / "work_area_roi_experiment.py",
)
assert SPEC and SPEC.loader
module = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(module)


class WorkAreaRoiExperimentTests(unittest.TestCase):
    def test_polygon_raster_and_center_metrics_follow_human_scope(self):
        polygon = [[0.1, 0.1], [0.7, 0.1], [0.8, 0.9], [0.2, 0.9]]
        truth = module.rasterize_polygon(polygon, 100, 100)
        result = module.center_metrics(
            truth, truth, [[0.2, 0.2, 0.4, 0.4], [0.85, 0.1, 0.95, 0.2]],
        )
        self.assertEqual(result["accuracy"], 1.0)
        self.assertEqual(result["inside_total"], 1)
        self.assertEqual(result["outside_total"], 1)

    def test_mask_metrics_penalise_position_only_drift(self):
        truth = np.zeros((10, 10), dtype=np.uint8)
        truth[2:8, 2:8] = 1
        shifted = np.zeros_like(truth)
        shifted[2:8, 4:10] = 1
        exact = module.mask_metrics(truth, truth)
        drifted = module.mask_metrics(shifted, truth)
        self.assertEqual(exact["iou"], 1.0)
        self.assertLess(drifted["iou"], 0.6)

    def test_leave_one_out_never_leaks_held_task(self):
        samples = [{"task_id": f"task-{index}"} for index in range(8)]
        splits = module.leave_one_out_splits(samples)
        self.assertEqual(len(splits), 8)
        for train, held in splits:
            self.assertNotIn(held, train)
            self.assertEqual(len(train), 7)


if __name__ == "__main__":
    unittest.main()
