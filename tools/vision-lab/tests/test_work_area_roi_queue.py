from __future__ import annotations

import importlib.util
import sys
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


if __name__ == "__main__":
    unittest.main()
