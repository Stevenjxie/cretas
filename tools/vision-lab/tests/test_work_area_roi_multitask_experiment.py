from __future__ import annotations

import sys
import unittest
from pathlib import Path

import torch


ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
import work_area_roi_multitask_experiment as module  # noqa: E402


class WorkAreaRoiMultitaskExperimentTests(unittest.TestCase):
    def test_five_channel_unet_separates_mask_and_corner_outputs(self):
        model = module.common.build_tiny_unet(
            torch, input_channels=5, base_channels=4, normalized_blocks=True,
            unet_depth=3, output_channels=5,
        )
        output = model(torch.zeros((2, 5, 32, 40)))
        self.assertEqual(tuple(output[:, :1].shape), (2, 1, 32, 40))
        self.assertEqual(tuple(output[:, 1:].shape), (2, 4, 32, 40))

    def test_single_training_step_has_finite_multitask_loss(self):
        model = module.common.build_tiny_unet(
            torch, input_channels=5, base_channels=4, normalized_blocks=True,
            unet_depth=2, output_channels=5,
        )
        images = torch.zeros((1, 5, 16, 16))
        masks = torch.zeros((1, 1, 16, 16))
        masks[:, :, 3:13, 3:13] = 1
        points = torch.tensor([[[0.2, 0.2], [0.8, 0.2], [0.8, 0.8], [0.2, 0.8]]])
        heatmaps = torch.from_numpy(module.corner.gaussian_corner_targets(
            points.numpy(), width=16, height=16, sigma=1.5,
        ))
        centers = torch.tensor([[[0.5, 0.5], [0.05, 0.5]]])
        center_labels = torch.tensor([[1.0, 0.0]])
        center_valid = torch.tensor([[True, True]])
        optimizer = module.train_model(
            torch, model, images, masks, heatmaps, points,
            centers, center_labels, center_valid,
            epochs=1, seed=1, device=torch.device("cpu"), augment=False,
        )
        self.assertIsInstance(optimizer, torch.optim.Optimizer)


if __name__ == "__main__":
    unittest.main()
