from __future__ import annotations

import importlib.util
import json
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock

from PIL import Image


MODULE_PATH = Path(__file__).resolve().parents[1] / "tray_workflow.py"
sys.path.insert(0, str(MODULE_PATH.parent))
SPEC = importlib.util.spec_from_file_location("tray_workflow", MODULE_PATH)
assert SPEC and SPEC.loader
module = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(module)


class TrayWorkflowTests(unittest.TestCase):
    def test_build_dataset_accumulates_multiple_reviewed_queues(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            queues = [root / "queue-a", root / "queue-b"]
            reviewed_by_queue = {}
            for queue_index, queue in enumerate(queues):
                queue.mkdir()
                (queue / "manifest.json").write_text(
                    json.dumps({"queue": queue_index}), encoding="utf-8",
                )
                reviewed = []
                for item_index in range(4):
                    index = queue_index * 4 + item_index
                    image = root / f"image-{index}.jpg"
                    annotation = root / f"annotation-{index}.json"
                    Image.new("RGB", (32, 24), (index * 30, 80, 160)).save(image)
                    annotation.write_text(json.dumps({"boxes": [[0.1, 0.1, 0.8, 0.8]]}), encoding="utf-8")
                    digest = module.sha256(image)
                    reviewed.append({
                        "task_id": f"task-{index}", "image": image,
                        "annotation_path": annotation, "annotation_sha256": module.sha256(annotation),
                        "boxes": [[0.1, 0.1, 0.8, 0.8]],
                        "source": {
                            "packed_stem": f"trayal_{index:03d}",
                            "source_photo_id": f"photo-{index}", "source_sha256": digest,
                            "packed_image_sha256": digest,
                            "source_perceptual_hash": module.hashlib.sha256(f"phash-{index}".encode()).hexdigest(),
                            "selection_tags": ["edge"],
                        },
                    })
                reviewed_by_queue[queue] = reviewed
            config = {
                "runtime_root": str(root / "runtime"),
                "tray_active_learning": {
                    "protected_holdout": str(root / "holdout.json"), "validation_percent": 25,
                },
            }
            (root / "runtime" / "receipts").mkdir(parents=True)
            with mock.patch.object(
                module, "validate_reviewed_queue",
                side_effect=lambda queue, _: ({}, reviewed_by_queue[queue]),
            ):
                dataset = module.build_dataset(config, queues)
                single_queue_dataset = module.build_dataset(config, queues[0])
            self.assertEqual(dataset["human_reviewed_images"], 8)
            self.assertEqual(dataset["human_boxes"], 8)
            self.assertEqual(dataset["queues"], [str(queue) for queue in queues])
            provenance = module.load_json(Path(dataset["data_yaml"]).parent / "provenance.json")
            self.assertEqual({row["queue"] for row in provenance["rows"]}, {str(queue) for queue in queues})
            self.assertNotIn("queue", dataset)
            self.assertNotIn("queue_manifest_sha256", dataset)
            self.assertEqual(single_queue_dataset["queue"], str(queues[0]))
            self.assertEqual(
                single_queue_dataset["queue_manifest_sha256"], module.sha256(queues[0] / "manifest.json"),
            )

    def test_validation_tasks_are_task_level_and_nonempty(self):
        rows = [{"task_id": f"task-{index}"} for index in range(8)]
        selected = module.choose_validation_tasks(rows, 25)
        self.assertEqual(len(selected), 2)
        self.assertTrue(selected < {row["task_id"] for row in rows})

    def test_boxes_match_tolerates_export_rounding_but_not_missing_box(self):
        left = [[10.0, 10.0, 110.0, 90.0], [200.0, 20.0, 300.0, 100.0]]
        rounded = [[10.1, 10.0, 110.1, 90.0], [199.9, 20.0, 299.9, 100.0]]
        self.assertTrue(module.boxes_match(left, rounded))
        self.assertFalse(module.boxes_match(left, rounded[:1]))

    def test_queue_refuses_unreviewed_preannotation(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            queue = root / "queue"
            (queue / "images").mkdir(parents=True)
            (queue / "annotations-human").mkdir()
            source = root / "source.jpg"
            packed = queue / "images" / "trayal_001.jpg"
            Image.new("RGB", (80, 60), (20, 80, 180)).save(source)
            Image.new("RGB", (40, 30), (20, 80, 180)).save(packed)
            row = {
                "packed_stem": "trayal_001", "packed_image": "images/trayal_001.jpg",
                "packed_image_sha256": module.sha256(packed), "source_path": str(source),
                "source_sha256": module.sha256(source), "source_perceptual_hash": "f" * 64,
                "task_id": "task-1", "source_photo_id": "photo-1",
            }
            (queue / "manifest.json").write_text(json.dumps({
                "queue_count": 1, "rows": [row], "protected_holdout_included": False,
                "preannotations_are_not_ground_truth": True,
            }), encoding="utf-8")
            (queue / "annotations-human" / "trayal_001.json").write_text(json.dumps({
                "reviewed": False, "source": "v3_preannotation_requires_human_review",
                "format": "normalised_xyxy", "boxes": [[0.1, 0.1, 0.8, 0.8]],
            }), encoding="utf-8")
            holdout_rows = []
            for index in range(27):
                image = root / f"holdout-{index}.jpg"
                Image.new("RGB", (16, 16), (index, index, index)).save(image)
                holdout_rows.append({"image": str(image), "image_sha256": module.sha256(image)})
            holdout = root / "holdout.json"
            holdout.write_text(json.dumps({
                "train_use_allowed": False, "records": holdout_rows,
            }), encoding="utf-8")
            with mock.patch.object(module, "phash", side_effect=lambda path: "0" * 64 if "holdout" in path.name else "f" * 64):
                with self.assertRaisesRegex(RuntimeError, "not fully human-reviewed"):
                    module.validate_reviewed_queue(queue, holdout)

    def test_gate_requires_root_cause_hit_and_accepts_complete_result(self):
        with tempfile.TemporaryDirectory() as temporary:
            artifact = Path(temporary) / "tray.onnx"
            artifact.write_bytes(b"candidate")
            model = {"model_id": "tray-1", "artifact": str(artifact), "artifact_sha256": module.sha256(artifact)}
            candidate = {
                "defect_total": 7, "defect_hits": 5, "tray_target_hits": 7,
                "false_flags": 9, "p95_latency_ms": 1100,
                "groups": {"new_blind_defect": {
                    "defect_total": 2, "defect_hits": 2, "tray_target_total": 2, "tray_target_hits": 2,
                }},
                "details": [{"photo_id": module.TARGET_DEFECT_PHOTO, "tray_target_covered": True, "hit": True}],
            }
            metrics = {
                "artifact_sha256": model["artifact_sha256"], "production_pipeline_replay": True,
                "onnx_parity_mismatches": 1, "production_onnx_parity_mismatches": 1,
                "baseline": {"defect_hits": 4, "tray_target_hits": 6, "false_flags": 10, "p95_latency_ms": 1000},
                "candidate": candidate,
            }
            config = {"tray_active_learning": {"max_onnx_parity_mismatches": 1}, "promotion_gates": {
                "min_independent_defects": 7, "min_defect_hits": 4,
                "required_full_recall_groups": ["new_blind_defect"],
                "min_false_flag_improvement": 0.05, "max_p95_latency_ms": 8000,
                "max_latency_regression": 0.15,
            }}
            self.assertTrue(module.evaluate_gate(config, model, metrics)["passed"])
            candidate["details"][0]["hit"] = False
            rejected = module.evaluate_gate(config, model, metrics)
            self.assertFalse(rejected["passed"])
            self.assertTrue(any("root-cause protected defect" in error for error in rejected["errors"]))


if __name__ == "__main__":
    unittest.main()
