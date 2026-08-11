from __future__ import annotations

import json
import sqlite3
import sys
import tempfile
import unittest
from pathlib import Path

import numpy as np
from PIL import Image


ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
import mine_tray_queue as tray  # noqa: E402
import work_area_raw_tray_plan as module  # noqa: E402


class WorkAreaRawTrayPlanTests(unittest.TestCase):
    @staticmethod
    def make_image(path: Path, seed: int) -> Path:
        pixels = np.random.default_rng(seed).integers(0, 256, (64, 64, 3), dtype=np.uint8)
        Image.fromarray(pixels).save(path)
        return path

    def test_plan_balances_skus_and_binds_raw_candidates(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            protected_image = self.make_image(root / "protected.png", 1)
            protected = root / "protected.json"
            protected.write_text(json.dumps({"records": [{
                "photo_id": "protected-photo", "task_id": "protected-task",
                "image": str(protected_image),
                "image_sha256": tray.sha256_file(protected_image),
            }]}), encoding="utf-8")
            existing_image = self.make_image(root / "existing.png", 2)
            existing = root / "existing.json"
            existing.write_text(json.dumps({"rows": [{
                "source_photo_id": "existing-photo", "task_id": "used-task",
                "source_task_id": "different-upstream-task",
                "source_sha256": tray.sha256_file(existing_image),
                "source_perceptual_hash": tray.phash(existing_image),
            }]}), encoding="utf-8")
            database = root / "vision.db"
            connection = sqlite3.connect(database)
            connection.execute(
                "CREATE TABLE photos(photo_id TEXT, task_id TEXT, reviewed_at TEXT, "
                "sku_code TEXT, object_ref TEXT, sha256 TEXT, local_path TEXT, "
                "annotations_json TEXT, collected_at TEXT)"
            )
            rows = []
            for index, (sku, task) in enumerate((
                ("SKU13", "used-task"), ("SKU13", "new-13"), ("SKU14", "new-14"),
            ), 3):
                image = self.make_image(root / f"{index}.png", index)
                rows.append((
                    f"photo-{index}", task, f"2026-08-12T00:00:0{index}Z", sku,
                    "local", tray.sha256_file(image), str(image), "[]", "now",
                ))
            connection.executemany("INSERT INTO photos VALUES(?,?,?,?,?,?,?,?,?)", rows)
            connection.commit()
            connection.close()

            plan = module.build_plan(
                database, [existing], protected, ["SKU13", "SKU14"], 1,
            )
            self.assertEqual(plan["selected_count"], 2)
            self.assertEqual(plan["sku_counts"], {"SKU13": 1, "SKU14": 1})
            self.assertNotIn("used-task", {row["task_id"] for row in plan["selected"]})
            self.assertEqual(plan["pool"]["exclusions"]["existing_photo_task_or_sha"], 1)
            self.assertTrue(all(
                row["tray_boxes_status"] == "requires_full_human_review"
                for row in plan["selected"]
            ))

            plan_path = root / "plan.json"
            plan_path.write_text(json.dumps(plan), encoding="utf-8")
            loaded = tray.load_raw_work_area_plan(
                plan_path, tray.sha256_file(plan_path), [existing], protected,
            )
            protected_evidence = tray.protected_evidence(protected)
            candidates = tray.load_planned_candidates(
                database, loaded, protected_evidence, {"existing-photo"}, 10,
            )
            self.assertEqual(
                [row["photo_id"] for row in candidates],
                [row["photo_id"] for row in plan["selected"]],
            )

            with self.assertRaisesRegex(RuntimeError, "manifest set drift"):
                tray.load_raw_work_area_plan(
                    plan_path, tray.sha256_file(plan_path), [], protected,
                )

            duplicate_plan = dict(plan)
            duplicate_plan["selected"] = [dict(row) for row in plan["selected"]]
            duplicate_plan["selected"][1]["source_sha256"] = (
                duplicate_plan["selected"][0]["source_sha256"]
            )
            duplicate_path = root / "duplicate-plan.json"
            duplicate_path.write_text(json.dumps(duplicate_plan), encoding="utf-8")
            with self.assertRaisesRegex(RuntimeError, "source_sha256"):
                tray.load_raw_work_area_plan(
                    duplicate_path, tray.sha256_file(duplicate_path), [existing], protected,
                )

            output_root = root / "queues"
            prior_queue = output_root / "tray-active-prior"
            prior_queue.mkdir(parents=True)
            (prior_queue / "manifest.json").write_text(json.dumps({"rows": [{
                "source_photo_id": plan["selected"][0]["photo_id"],
            }]}), encoding="utf-8")
            self.assertEqual(
                tray.raw_plan_output_overlaps(output_root, plan["selected"]),
                {plan["selected"][0]["photo_id"]},
            )


if __name__ == "__main__":
    unittest.main()
