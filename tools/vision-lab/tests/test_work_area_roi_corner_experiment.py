from __future__ import annotations

import sys
import unittest
from pathlib import Path

import numpy as np


ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
import work_area_roi_corner_experiment as module  # noqa: E402


class WorkAreaRoiCornerExperimentTests(unittest.TestCase):
    def test_gaussian_targets_are_normalized_and_peak_near_corner(self):
        points = np.asarray([[[0.25, 0.5]] * 4], dtype=np.float32)
        targets = module.gaussian_corner_targets(points, width=21, height=11, sigma=1.5)
        self.assertEqual(targets.shape, (1, 4, 11, 21))
        self.assertTrue(np.allclose(targets.sum(axis=(2, 3)), 1.0))
        y, x = np.unravel_index(np.argmax(targets[0, 0]), targets[0, 0].shape)
        self.assertEqual((x, y), (5, 5))

    def test_soft_argmax_recovers_each_peak(self):
        import torch

        logits = torch.full((1, 4, 5, 7), -20.0)
        peaks = [(0, 0), (6, 0), (6, 4), (0, 4)]
        for channel, (x, y) in enumerate(peaks):
            logits[0, channel, y, x] = 20.0
        result = module.spatial_soft_argmax(torch, logits)
        expected = torch.tensor([[[0., 0.], [1., 0.], [1., 1.], [0., 1.]]])
        self.assertTrue(torch.allclose(result, expected, atol=1e-5))

    def test_four_channel_unet_preserves_spatial_shape(self):
        import torch

        model = module.common.build_tiny_unet(
            torch, input_channels=5, base_channels=4, normalized_blocks=True,
            unet_depth=3, output_channels=4,
        )
        output = model(torch.zeros((2, 5, 32, 40)))
        self.assertEqual(tuple(output.shape), (2, 4, 32, 40))


if __name__ == "__main__":
    unittest.main()
