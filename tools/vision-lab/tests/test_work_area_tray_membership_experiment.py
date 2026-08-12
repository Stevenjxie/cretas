from __future__ import annotations

import json
import sys
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
import work_area_tray_membership_experiment as module  # noqa: E402


class WorkAreaTrayMembershipExperimentTests(unittest.TestCase):
    def test_geometry_features_include_center_and_size(self):
        values = module.geometry_features([0.2, 0.3, 0.6, 0.9])
        self.assertEqual(values.shape, (11,))
        self.assertAlmostEqual(float(values[4]), 0.4)
        self.assertAlmostEqual(float(values[5]), 0.6)
        self.assertAlmostEqual(float(values[6]), 0.4)
        self.assertAlmostEqual(float(values[7]), 0.6)

    def test_arrangement_features_include_neighbour_context(self):
        boxes = [[0.1, 0.1, 0.2, 0.2], [0.4, 0.4, 0.6, 0.6]]
        first = module.arrangement_features(boxes, 0)
        second = module.arrangement_features(boxes, 1)
        self.assertEqual(first.shape, second.shape)
        self.assertGreater(len(first), len(module.geometry_features(boxes[0])))
        self.assertFalse((first == second).all())

    def test_decision_requires_both_independent_signals(self):
        self.assertEqual(module.decision(0.99, 0.99), module.INSIDE)
        self.assertEqual(module.decision(0.01, 0.01), module.OUTSIDE)
        self.assertEqual(module.decision(0.99, 0.50), module.UNKNOWN)
        self.assertEqual(module.decision(0.50, 0.01), module.UNKNOWN)

    def test_summary_counts_dangerous_outside_as_inside_separately(self):
        rows = [
            {"truth": module.OUTSIDE, "decision": module.INSIDE},
            {"truth": module.INSIDE, "decision": module.UNKNOWN},
            {"truth": module.INSIDE, "decision": module.INSIDE},
        ]
        summary = module.summarize(rows)
        self.assertEqual(summary["outside_as_inside_errors"], 1)
        self.assertEqual(summary["inside_as_outside_errors"], 0)
        self.assertEqual(summary["unknown_count"], 1)
        self.assertEqual(summary["automatic_coverage"], 2 / 3)
        json.dumps(summary)

    def test_invalid_probability_fails_closed_before_decision(self):
        with self.assertRaisesRegex(ValueError, "finite in"):
            module.decision(float("nan"), 0.5)


if __name__ == "__main__":
    unittest.main()
