from __future__ import annotations

import sys
import unittest
from pathlib import Path

import numpy as np


ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
import work_area_roi_registration_experiment as module  # noqa: E402


class WorkAreaRoiRegistrationExperimentTests(unittest.TestCase):
    def test_proposal_mask_suppresses_detector_box_without_becoming_roi(self):
        mask = module.proposal_mask([{"box": [0.25, 0.25, 0.75, 0.75]}], 100, 80)
        self.assertEqual(mask.shape, (80, 100))
        self.assertEqual(int(mask[40, 50]), 0)
        self.assertEqual(int(mask[2, 2]), 255)

    def test_registration_gate_requires_every_quality_dimension(self):
        gate = module.RegistrationGate()
        quality = {
            "inliers": 12, "inlier_ratio": 0.3,
            "median_reprojection_error": 2.5, "frame_area_scale": 1.0,
        }
        self.assertTrue(module.gate_registration(quality, gate))
        for key, value in (
            ("inliers", 11), ("inlier_ratio", 0.29),
            ("median_reprojection_error", 2.51), ("frame_area_scale", 1.81),
        ):
            changed = dict(quality)
            changed[key] = value
            self.assertFalse(module.gate_registration(changed, gate), key)

    def test_identity_homography_preserves_canonical_polygon(self):
        polygon = [[0.2, 0.2], [0.2, 0.8], [0.8, 0.8], [0.8, 0.2]]
        transformed = module.transform_polygon(polygon, np.eye(3), 384, 512)
        expected = module.quad.canonicalize_polygon(polygon)
        self.assertTrue(np.allclose(transformed, expected))

    def test_polygon_leaving_image_fails_closed(self):
        polygon = [[0.2, 0.2], [0.2, 0.8], [0.8, 0.8], [0.8, 0.2]]
        translated = np.asarray([[1.0, 0.0, 100.0], [0.0, 1.0, 0.0], [0.0, 0.0, 1.0]])
        with self.assertRaisesRegex(ValueError, "leaves the image"):
            module.transform_polygon(polygon, translated, 384, 512)

    def test_summary_counts_unknown_as_fail_closed_coverage(self):
        rows = [{
            "decision": "unknown", "tray_count": 5,
        }, {
            "decision": "automatic", "tray_count": 3,
            "mask": {"iou": 0.9, "dice": 0.95},
            "centers": {
                "accuracy": 1.0, "errors": 0, "inside_total": 2,
                "inside_recall": 1.0, "outside_total": 1, "outside_recall": 1.0,
            },
        }]
        summary = module.summarize(rows)
        self.assertEqual(summary["unknown_images"], 1)
        self.assertEqual(summary["unknown_tray_centers"], 5)
        self.assertEqual(summary["automatic_image_coverage"], 0.5)
        self.assertEqual(summary["automatic_tray_center_coverage"], 3 / 8)


if __name__ == "__main__":
    unittest.main()
