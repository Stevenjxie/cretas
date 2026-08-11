from __future__ import annotations

import importlib.util
import sys
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
SPEC = importlib.util.spec_from_file_location("work_area", ROOT / "work_area.py")
assert SPEC and SPEC.loader
module = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(module)


class WorkAreaContractTests(unittest.TestCase):
    def setUp(self):
        self.polygon = [[0.1, 0.1], [0.8, 0.1], [0.9, 0.9], [0.2, 0.8]]

    def test_polygon_accepts_perspective_quadrilateral(self):
        result = module.validate_polygon(self.polygon)
        self.assertEqual(result, self.polygon)
        self.assertGreater(module.polygon_area(result), 0.5)

    def test_polygon_rejects_crossed_or_tiny_geometry(self):
        with self.assertRaisesRegex(ValueError, "self-intersect"):
            module.validate_polygon([[0.1, 0.1], [0.9, 0.9], [0.9, 0.1], [0.1, 0.9]])
        with self.assertRaisesRegex(ValueError, "area"):
            module.validate_polygon([[0.1, 0.1], [0.11, 0.1], [0.11, 0.11], [0.1, 0.11]])

    def test_tray_center_classification_keeps_outside_samples(self):
        counts = module.classify_boxes(
            [[0.3, 0.3, 0.5, 0.5], [0.91, 0.01, 0.99, 0.08]], self.polygon,
        )
        self.assertEqual(counts, {"inside_work_area": 1, "outside_work_area": 1})

    def test_unjudgeable_is_explicit_unknown_not_inside(self):
        result = module.validate_human_annotation({
            "photo_id": "photo-1", "format": module.FORMAT,
            "reviewed": True, "source": "human", "judgeable": False,
            "polygon": None, "unjudgeable_reason": "work_area_not_visible_or_unjudgeable",
        }, expected_photo_id="photo-1")
        self.assertIsNone(result["polygon"])
        self.assertFalse(result["judgeable"])

    def test_pixel_box_without_human_roi_is_unknown(self):
        self.assertEqual(
            module.classify_pixel_box([10, 10, 20, 20], 100, 100, None),
            module.UNKNOWN_WORK_AREA,
        )
        annotation = module.validate_human_annotation({
            "photo_id": "photo-1", "format": module.FORMAT,
            "reviewed": True, "source": "human", "judgeable": True,
            "polygon": self.polygon,
        })
        self.assertEqual(
            module.classify_pixel_box([30, 30, 50, 50], 100, 100, annotation),
            module.INSIDE_WORK_AREA,
        )

    def test_annotation_rejects_teacher_or_unreviewed_truth(self):
        for source, reviewed in (("teacher", True), ("human", False)):
            with self.assertRaisesRegex(ValueError, "reviewed=true"):
                module.validate_human_annotation({
                    "photo_id": "photo-1", "reviewed": reviewed, "source": source,
                    "judgeable": True, "polygon": self.polygon,
                })


if __name__ == "__main__":
    unittest.main()
