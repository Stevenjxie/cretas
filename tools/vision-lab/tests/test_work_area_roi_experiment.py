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

    def test_center_supervision_mask_marks_each_tray_center(self):
        samples = [{"boxes": [[0.0, 0.0, 0.2, 0.2], [0.8, 0.8, 1.0, 1.0]]}]
        mask = module.center_supervision_mask(samples, width=11, height=11)
        self.assertEqual(mask.shape, (1, 1, 11, 11))
        self.assertEqual(int(mask.sum()), 2)
        self.assertTrue(mask[0, 0, 1, 1])
        self.assertTrue(mask[0, 0, 9, 9])

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

    def test_task_grouped_folds_hold_each_task_exactly_once(self):
        samples = [
            {"task_id": f"task-{index}", "sku_code": f"sku-{index % 4}"}
            for index in range(32)
        ]
        splits = module.task_grouped_splits(samples, 8)
        self.assertEqual(len(splits), 8)
        held = [index for _train, fold_held in splits for index in fold_held]
        self.assertEqual(sorted(held), list(range(32)))
        for train, fold_held in splits:
            self.assertFalse(set(train) & set(fold_held))
            self.assertEqual(len(train) + len(fold_held), 32)

    def test_combined_sample_loader_requires_a_queue(self):
        with self.assertRaisesRegex(ValueError, "at least one"):
            module.load_combined_samples([])

    def test_summary_ignores_absent_class_for_minimum_recall(self):
        rows = [
            {"mask": {"iou": 1.0, "dice": 1.0}, "centers": {
                "accuracy": 1.0, "errors": 0, "inside_total": 1, "inside_recall": 1.0,
                "outside_total": 0, "outside_recall": 0.0,
            }},
            {"mask": {"iou": 0.9, "dice": 0.95}, "centers": {
                "accuracy": 0.9, "errors": 1, "inside_total": 1, "inside_recall": 1.0,
                "outside_total": 2, "outside_recall": 0.5,
            }},
        ]
        self.assertEqual(module._summarise(rows)["min_outside_recall"], 0.5)

    def test_coordinate_channels_append_normalized_xy(self):
        images = np.zeros((2, 3, 4, 3), dtype=np.float32)
        result = module.add_coordinate_channels(images)
        self.assertEqual(result.shape, (2, 3, 4, 5))
        self.assertEqual(float(result[0, 0, 0, 3]), -1.0)
        self.assertEqual(float(result[0, -1, -1, 4]), 1.0)

    def test_dice_loss_averages_each_image_instead_of_foreground_pixels(self):
        import torch

        truth = torch.tensor([
            [[[1.0, 1.0], [1.0, 1.0]]],
            [[[1.0, 0.0], [0.0, 0.0]]],
        ])
        probability = torch.tensor([
            [[[1.0, 1.0], [1.0, 1.0]]],
            [[[0.0, 0.0], [0.0, 0.0]]],
        ])
        loss = module.mean_per_image_dice_loss(probability, truth)
        self.assertAlmostEqual(float(loss), 0.25)

    def test_normalized_unet_preserves_mask_shape(self):
        import torch

        model = module.build_tiny_unet(
            torch, input_channels=5, base_channels=16, normalized_blocks=True,
        )
        output = model(torch.zeros((2, 5, 32, 40)))
        self.assertEqual(tuple(output.shape), (2, 1, 32, 40))

    def test_deep_unet_preserves_mask_shape(self):
        import torch

        model = module.build_tiny_unet(
            torch, input_channels=5, base_channels=8,
            normalized_blocks=True, unet_depth=4,
        )
        output = model(torch.zeros((2, 5, 32, 48)))
        self.assertEqual(tuple(output.shape), (2, 1, 32, 48))


if __name__ == "__main__":
    unittest.main()
