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
    def test_training_source_forces_offline_without_amp_probe(self):
        source = MODULE_PATH.read_text(encoding="utf-8")
        self.assertIn('os.environ["YOLO_OFFLINE"] = "true"', source)
        self.assertIn("pretrained=False", source)
        self.assertIn("amp=False", source)

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

    def test_work_area_queue_audit_recomputes_inside_outside_counts(self):
        with tempfile.TemporaryDirectory() as temporary:
            queue = Path(temporary) / "queue"
            (queue / "annotations-human").mkdir(parents=True)
            (queue / "work-area-human").mkdir()
            source_sha = "a" * 64
            packed_sha = "b" * 64
            source_image = queue / "source.jpg"
            packed_image = queue / "packed.jpg"
            source_image.write_bytes(b"source")
            packed_image.write_bytes(b"packed")
            source_sha = module.sha256(source_image)
            packed_sha = module.sha256(packed_image)
            row = {
                "packed_stem": "tray-1", "source_photo_id": "source-1",
                "source_sha256": source_sha, "packed_image_sha256": packed_sha,
                "source_path": str(source_image), "packed_image": "packed.jpg",
                "task_id": "task-1", "sku_code": "SKU-1",
            }
            (queue / "manifest.json").write_text(json.dumps({
                "queue_count": 1, "protected_holdout_included": False, "rows": [row],
            }), encoding="utf-8")
            boxes = [[0.2, 0.2, 0.4, 0.4], [0.85, 0.1, 0.95, 0.2]]
            (queue / "annotations-human" / "tray-1.json").write_text(json.dumps({
                "reviewed": True, "source": "human", "format": "normalised_xyxy", "boxes": boxes,
            }), encoding="utf-8")
            (queue / "work-area-human" / "tray-1.json").write_text(json.dumps({
                "photo_id": "tray-1", "source_photo_id": "source-1",
                "source_sha256": source_sha, "packed_image_sha256": packed_sha,
                "format": module.work_area.FORMAT, "reviewed": True, "source": "human",
                "judgeable": True,
                "polygon": [[0.1, 0.1], [0.8, 0.1], [0.8, 0.8], [0.1, 0.8]],
                "tray_scope_counts": {"inside_work_area": 1, "outside_work_area": 1},
            }), encoding="utf-8")
            audit = module.audit_work_area_queue(queue)
            self.assertEqual(audit["reviewed_images"], 1)
            self.assertEqual(audit["tray_scope_counts"], {
                "inside_work_area": 1, "outside_work_area": 1, "unknown_work_area": 0,
            })
            self.assertFalse(audit["protected_holdout_modified"])

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
                "details": [{
                    "photo_id": module.TARGET_DEFECT_PHOTO, "tray_target_covered": True,
                    "hit": True, "work_area": "outside_work_area",
                }],
                "work_area": {
                    "records": {"with_human_roi": 27, "without_human_roi": 0, "unjudgeable": 0},
                    "groups": {
                        "inside_work_area": {
                            "defect_total": 5, "defect_hits": 4,
                            "tray_target_total": 5, "tray_target_hits": 5,
                            "detected_trays": 60, "normal_trays": 50,
                            "missing_label_flags": 8, "false_flags": 4,
                        },
                        "outside_work_area": {
                            "defect_total": 2, "defect_hits": 1,
                            "tray_target_total": 2, "tray_target_hits": 2,
                            "detected_trays": 20, "normal_trays": 15,
                            "missing_label_flags": 3, "false_flags": 5,
                        },
                        "unknown_work_area": {
                            "defect_total": 0, "defect_hits": 0,
                            "tray_target_total": 0, "tray_target_hits": 0,
                            "detected_trays": 0, "normal_trays": 0,
                            "missing_label_flags": 0, "false_flags": 0,
                        },
                    },
                },
            }
            metrics = {
                "artifact_sha256": model["artifact_sha256"], "production_pipeline_replay": True,
                "onnx_parity_mismatches": 1, "production_onnx_parity_mismatches": 1,
                "baseline": {
                    "defect_hits": 4, "tray_target_hits": 6, "false_flags": 10, "p95_latency_ms": 1000,
                    "details": [{
                        "photo_id": module.TARGET_DEFECT_PHOTO, "tray_target_covered": True,
                        "hit": True, "work_area": "outside_work_area",
                    }],
                    "work_area": {
                        "records": {"with_human_roi": 27, "without_human_roi": 0, "unjudgeable": 0},
                        "groups": {
                            "inside_work_area": {
                                "defect_total": 5, "defect_hits": 3,
                                "tray_target_total": 5, "tray_target_hits": 5,
                                "detected_trays": 60, "normal_trays": 50,
                                "missing_label_flags": 9, "false_flags": 5,
                            },
                            "outside_work_area": {
                                "defect_total": 2, "defect_hits": 1,
                                "tray_target_total": 2, "tray_target_hits": 2,
                                "detected_trays": 20, "normal_trays": 15,
                                "missing_label_flags": 3, "false_flags": 5,
                            },
                            "unknown_work_area": {
                                "defect_total": 0, "defect_hits": 0,
                                "tray_target_total": 0, "tray_target_hits": 0,
                                "detected_trays": 0, "normal_trays": 0,
                                "missing_label_flags": 0, "false_flags": 0,
                            },
                        },
                    },
                },
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
            self.assertTrue(any("outside root-cause protected defect regressed" in error for error in rejected["errors"]))
            candidate["details"][0]["hit"] = True
            candidate["work_area"]["records"]["without_human_roi"] = 1
            unknown = module.evaluate_gate(config, model, metrics)
            self.assertFalse(unknown["passed"])
            self.assertTrue(any("unknown_work_area evidence blocks promotion" in error for error in unknown["errors"]))


if __name__ == "__main__":
    unittest.main()
