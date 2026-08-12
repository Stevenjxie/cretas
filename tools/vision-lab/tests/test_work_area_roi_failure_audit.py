from __future__ import annotations

import sys
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
import work_area_roi_failure_audit as module  # noqa: E402


class WorkAreaRoiFailureAuditTests(unittest.TestCase):
    def test_geometry_diagnostics_accepts_matching_convex_quad(self):
        polygon = [[0.2, 0.2], [0.8, 0.2], [0.8, 0.8], [0.2, 0.8]]
        result = module.geometry_diagnostics(polygon, polygon)
        self.assertTrue(result["valid_polygon"])
        self.assertAlmostEqual(result["area_ratio"], 1.0)
        self.assertEqual(result["tags"], [])

    def test_geometry_diagnostics_flags_corner_and_area_collapse(self):
        truth = [[0.2, 0.2], [0.8, 0.2], [0.8, 0.8], [0.2, 0.8]]
        predicted = [[0.2, 0.2], [0.8, 0.2], [0.81, 0.21], [0.2, 0.8]]
        result = module.geometry_diagnostics(predicted, truth)
        self.assertIn("adjacent_corner_collapse", result["tags"])
        self.assertIn("area_collapse", result["tags"])
        self.assertIn("single_corner_outlier", result["tags"])

    def test_grouped_summary_keeps_failure_counts_separate(self):
        rows = [{
            "sku_code": "A", "iou": 0.5, "center_errors": 2,
            "geometry": {"valid_polygon": False, "tags": ["invalid_polygon"]},
        }, {
            "sku_code": "A", "iou": 0.9, "center_errors": 0,
            "geometry": {"valid_polygon": True, "tags": ["area_collapse"]},
        }]
        summary = module.grouped_summary(rows, "sku_code")["A"]
        self.assertEqual(summary["samples"], 2)
        self.assertAlmostEqual(summary["mean_iou"], 0.7)
        self.assertEqual(summary["center_errors"], 2)
        self.assertEqual(summary["invalid_polygons"], 1)
        self.assertEqual(summary["area_collapses"], 1)


if __name__ == "__main__":
    unittest.main()
