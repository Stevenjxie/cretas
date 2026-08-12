from __future__ import annotations

import importlib.util
import sys
import unittest
from pathlib import Path

import numpy as np


ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
SPEC = importlib.util.spec_from_file_location(
    "work_area_roi_quad_experiment", ROOT / "work_area_roi_quad_experiment.py",
)
assert SPEC and SPEC.loader
module = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(module)


class WorkAreaRoiQuadExperimentTests(unittest.TestCase):
    def test_canonical_polygon_is_stable_across_direction_and_start(self):
        expected = np.asarray([
            [0.1, 0.1], [0.8, 0.2], [0.9, 0.9], [0.2, 0.8],
        ], dtype=np.float32)
        shifted_reverse = expected[[2, 1, 0, 3]].tolist()
        result = module.canonicalize_polygon(shifted_reverse)
        np.testing.assert_allclose(result, expected)

    def test_quad_regressor_outputs_normalized_four_points(self):
        import torch

        model = module.build_quad_regressor(torch, input_channels=5, base_channels=8)
        output = model(torch.zeros((2, 5, 256, 192)))
        self.assertEqual(tuple(output.shape), (2, 4, 2))
        self.assertTrue(bool(((output >= 0) & (output <= 1)).all()))

    def test_invalid_predicted_polygon_fails_closed(self):
        truth = np.ones((32, 32), dtype=bool)
        sample = {"boxes": [[0.1, 0.1, 0.2, 0.2]]}
        metrics, valid = module.evaluate_polygon(
            np.asarray([[0.5, 0.5]] * 4, dtype=np.float32), truth, sample, 32, 32,
        )
        self.assertFalse(valid)
        self.assertEqual(metrics["centers"]["inside_recall"], 0.0)


if __name__ == "__main__":
    unittest.main()
