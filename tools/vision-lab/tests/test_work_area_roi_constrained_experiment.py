from __future__ import annotations

import sys
import unittest
from pathlib import Path

import numpy as np
import torch


ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
import work_area_roi_constrained_experiment as module  # noqa: E402


class WorkAreaRoiConstrainedExperimentTests(unittest.TestCase):
    def test_zero_logits_decode_centered_ordered_quad(self):
        points = module.decode_quadrants(torch, torch.zeros((1, 10)))
        expected = torch.tensor([[[0.25, 0.25], [0.75, 0.25], [0.75, 0.75], [0.25, 0.75]]])
        self.assertTrue(torch.allclose(points, expected))

    def test_random_logits_stay_bounded_simple_and_ordered(self):
        generator = torch.Generator().manual_seed(20260812)
        points = module.decode_quadrants(torch, torch.randn((512, 10), generator=generator))
        self.assertTrue(bool(torch.all((points > 0) & (points < 1))))
        for polygon in points.numpy():
            validated = module.common.work_area.validate_polygon(polygon.tolist())
            self.assertEqual(len(validated), 4)

    def test_convexity_loss_distinguishes_convex_and_concave(self):
        convex = torch.tensor([[[0.2, 0.2], [0.8, 0.2], [0.8, 0.8], [0.2, 0.8]]])
        concave = torch.tensor([[[0.2, 0.2], [0.8, 0.2], [0.45, 0.45], [0.2, 0.8]]])
        self.assertEqual(float(module.convexity_loss(torch, convex)), 0.0)
        self.assertGreater(float(module.convexity_loss(torch, concave)), 0.0)

    def test_polygon_area_matches_unit_square(self):
        square = torch.tensor([[[0., 0.], [1., 0.], [1., 1.], [0., 1.]]])
        self.assertTrue(np.isclose(float(module.polygon_area(torch, square)[0]), 1.0))

    def test_model_preserves_spatial_grid_for_parameter_head(self):
        model = module.build_model(torch, input_channels=5, base_channels=4, depth=2)
        mask, parameters = model(torch.zeros((2, 5, 16, 16)))
        self.assertEqual(tuple(mask.shape), (2, 1, 16, 16))
        self.assertEqual(tuple(parameters.shape), (2, 10))

    def test_single_training_step_runs(self):
        model = module.build_model(torch, input_channels=5, base_channels=4, depth=2)
        images = torch.zeros((1, 5, 16, 16))
        masks = torch.zeros((1, 1, 16, 16))
        masks[:, :, 3:13, 3:13] = 1
        points = torch.tensor([[[0.2, 0.2], [0.8, 0.2], [0.8, 0.8], [0.2, 0.8]]])
        centers = torch.tensor([[[0.5, 0.5], [0.05, 0.5]]])
        labels = torch.tensor([[1.0, 0.0]])
        valid = torch.tensor([[True, True]])
        optimizer = module.train_model(
            torch, model, images, masks, points, centers, labels, valid,
            epochs=1, seed=1, device=torch.device("cpu"), augment=False,
        )
        self.assertIsInstance(optimizer, torch.optim.Optimizer)


if __name__ == "__main__":
    unittest.main()
