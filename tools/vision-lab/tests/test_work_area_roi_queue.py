from __future__ import annotations

import importlib.util
import hashlib
import json
import sys
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
SPEC = importlib.util.spec_from_file_location("work_area_roi_queue", ROOT / "work_area_roi_queue.py")
assert SPEC and SPEC.loader
module = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(module)


def plan_row(index: int) -> dict:
    return {
        "task_id": f"task-{index}",
        "protected_exact_exclusion_passed": True,
        "protected_phash_exclusion_passed": True,
        "old_mark_source_exclusion_passed": True,
    }


class WorkAreaRoiQueueTests(unittest.TestCase):
    def test_validate_plan_accepts_unique_bound_rows(self):
        rows = [plan_row(1), plan_row(2)]
        self.assertEqual(module.validate_plan({
            "version": module.PLAN_VERSION, "plan_only": True,
            "queue_created": False, "mark_created": False,
            "selected_count": 2, "target_count": 2, "selected": rows,
        }), rows)

    def test_validate_plan_rejects_duplicate_tasks(self):
        rows = [plan_row(1), plan_row(1)]
        with self.assertRaisesRegex(RuntimeError, "independent task"):
            module.validate_plan({
                "version": module.PLAN_VERSION, "plan_only": True,
                "queue_created": False, "mark_created": False,
                "selected_count": 2, "target_count": 2, "selected": rows,
            })

    def test_validate_plan_rejects_missing_exclusion_evidence(self):
        row = plan_row(1)
        row["protected_phash_exclusion_passed"] = False
        with self.assertRaisesRegex(RuntimeError, "exclusion evidence"):
            module.validate_plan({
                "version": module.PLAN_VERSION, "plan_only": True,
                "queue_created": False, "mark_created": False,
                "selected_count": 1, "target_count": 1, "selected": [row],
            })

    def test_multiround_plan_requires_completed_roi_exclusion_evidence(self):
        row = plan_row(1)
        with self.assertRaisesRegex(RuntimeError, "exclusion evidence"):
            module.validate_plan({
                "version": module.PLAN_VERSION, "plan_only": True,
                "queue_created": False, "mark_created": False,
                "completed_roi_queues": ["queue-1", "queue-2"],
                "selected_count": 1, "target_count": 1, "selected": [row],
            })

    def test_raw_tray_mode_requires_reviewed_queue(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            plan = root / "plan.json"
            plan.write_text(json.dumps({
                "version": module.RAW_TRAY_PLAN_VERSION,
                "plan_only": True, "queue_created": False, "mark_created": False,
                "selected_count": 0, "selected": [],
            }), encoding="utf-8")
            digest = hashlib.sha256(plan.read_bytes()).hexdigest()
            with self.assertRaisesRegex(RuntimeError, "reviewed tray queue"):
                module.build_queue(plan, digest, root / "queues", root)

    def test_reviewed_tray_annotation_rejects_invalid_box(self):
        with tempfile.TemporaryDirectory() as temporary:
            annotation = Path(temporary) / "sample.json"
            annotation.write_text(json.dumps({
                "photo_id": "sample", "format": "normalised_xyxy",
                "reviewed": True, "source": "human", "boxes": [[0.2, 0.3, 1.2, 0.8]],
            }), encoding="utf-8")
            with self.assertRaisesRegex(RuntimeError, "outside the image"):
                module.validate_reviewed_tray_annotation(annotation, "sample")

    def test_reviewed_tray_annotation_requires_matching_stem(self):
        with tempfile.TemporaryDirectory() as temporary:
            annotation = Path(temporary) / "sample.json"
            annotation.write_text(json.dumps({
                "photo_id": "other", "format": "normalised_xyxy",
                "reviewed": True, "source": "human", "boxes": [[0.2, 0.3, 0.7, 0.8]],
            }), encoding="utf-8")
            with self.assertRaisesRegex(RuntimeError, "photo binding drift"):
                module.validate_reviewed_tray_annotation(annotation, "sample")


if __name__ == "__main__":
    unittest.main()
