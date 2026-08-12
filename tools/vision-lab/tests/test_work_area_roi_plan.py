from __future__ import annotations

import importlib.util
import hashlib
import sys
import unittest
from collections import Counter
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
SPEC = importlib.util.spec_from_file_location("work_area_roi_plan", ROOT / "work_area_roi_plan.py")
assert SPEC and SPEC.loader
module = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(module)


def row(index: int, sku: str, queue: str) -> dict:
    return {
        "source_photo_id": f"photo-{index}", "task_id": f"task-{index}",
        "sku_code": sku, "queue_name": queue,
        "source_perceptual_hash": f"{index + 1:064x}", "selection_score": index,
    }


class WorkAreaRoiPlanTests(unittest.TestCase):
    def test_plan_requires_at_least_one_completed_queue(self):
        with self.assertRaisesRegex(ValueError, "at least one"):
            module.build_plan(Path("dataset"), [], Path("mark"), Path("protected"), 1)

    def test_phash_distance_is_bit_hamming_distance(self):
        self.assertEqual(module.phash_distance("0" * 64, "f" * 64), 256)
        self.assertEqual(module.phash_distance("0" * 63 + "1", "0" * 64), 1)

    def test_sku_quotas_are_balanced(self):
        rows = [row(index, f"sku-{index % 4}", f"queue-{index % 3}") for index in range(32)]
        self.assertEqual(module.allocate_sku_quotas(rows, 24), {
            "sku-0": 6, "sku-1": 6, "sku-2": 6, "sku-3": 6,
        })

    def test_sku_quotas_balance_the_cumulative_human_set(self):
        rows = [row(index, f"sku-{index % 4}", "queue") for index in range(40)]
        quotas = module.allocate_sku_quotas(
            rows, 8, Counter({"sku-0": 10, "sku-1": 2, "sku-2": 2, "sku-3": 2}),
        )
        self.assertEqual(quotas["sku-0"], 0)
        self.assertEqual(sum(quotas.values()), 8)

    def test_selection_uses_unique_tasks_and_sku_quotas(self):
        rows = []
        for index in range(24):
            value = row(index, f"sku-{index % 4}", f"queue-{index % 3}")
            value["source_perceptual_hash"] = hashlib.sha256(str(index).encode()).hexdigest()
            rows.append(value)
        selected, quotas = module.select_diverse(rows, [], 12)
        self.assertEqual(len(selected), 12)
        self.assertEqual(len({value["task_id"] for value in selected}), 12)
        self.assertEqual(Counter(value["sku_code"] for value in selected), Counter(quotas))

    def test_focus_selection_prefers_nearest_eligible_phash(self):
        rows = [row(0, "sku", "queue"), row(1, "sku", "queue")]
        rows[0]["source_perceptual_hash"] = "0" * 63 + "3"
        rows[1]["source_perceptual_hash"] = "f" * 64
        selected, _quotas = module.select_diverse(
            rows, [], 1, ["0" * 64],
        )
        self.assertEqual(selected[0]["source_photo_id"], "photo-0")


if __name__ == "__main__":
    unittest.main()
