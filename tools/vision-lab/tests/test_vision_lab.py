from __future__ import annotations

import importlib.util
import json
import tempfile
import unittest
from pathlib import Path
from unittest import mock


MODULE_PATH = Path(__file__).resolve().parents[1] / "vision_lab.py"
SPEC = importlib.util.spec_from_file_location("vision_lab", MODULE_PATH)
assert SPEC and SPEC.loader
vision_lab = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(vision_lab)


def config(root: Path) -> dict:
    return {
        "version": vision_lab.VERSION,
        "runtime_root": str(root),
        "source": {
            "factory_id": "LIUSHANMEN", "ssh_host": "root@example",
            "database": "db", "initial_watermark": "2026-08-01T00:00:00",
            "max_records_per_run": 10,
        },
        "queue_roots": [], "annotator_url": "http://127.0.0.1:8792",
        "training": {"min_reviewed_images": 2, "validation_percent": 50},
        "promotion_gates": {
            "min_independent_defects": 2,
            "min_defect_hits": 2,
            "required_full_recall_groups": ["new_blind_defect"],
            "min_false_flag_improvement": 0.05,
            "max_p95_latency_ms": 8000,
            "max_latency_regression": 0.15,
        },
        "cloud_vl": {"enabled": False, "max_calls_per_run": 0, "monthly_budget_cny": 0},
    }


class VisionLabTests(unittest.TestCase):
    def test_training_source_forces_offline_without_amp_probe(self):
        source = MODULE_PATH.read_text(encoding="utf-8")
        self.assertIn('os.environ["YOLO_OFFLINE"] = "true"', source)
        self.assertIn("pretrained=False, amp=False", source)

    def test_explicit_queue_roots_disable_globs_and_fail_closed(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            queue = root / "queue"
            queue.mkdir()
            (queue / "manifest.json").write_text("{}", encoding="utf-8")
            config = {"queue_roots": ["old"], "queue_globs": ["*"]}
            overridden = vision_lab.config_with_queue_roots(config, [queue])
            self.assertEqual(overridden["queue_roots"], [str(queue.resolve())])
            self.assertEqual(overridden["queue_globs"], [])
            self.assertEqual(config["queue_globs"], ["*"])
            with self.assertRaisesRegex(RuntimeError, "missing manifest"):
                vision_lab.config_with_queue_roots(config, [root / "missing"])
            with self.assertRaisesRegex(RuntimeError, "duplicates"):
                vision_lab.config_with_queue_roots(config, [queue, queue])

    def test_collect_is_content_addressed_and_advances_watermark(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            cfg = config(root)
            state = vision_lab.State(root)
            try:
                vision_lab.init_layout(cfg, state)
                rows = [
                    {
                        "photo_id": "p1", "task_id": "t1", "reviewed_at": "2026-08-02T01:02:03",
                        "sku_code": "SKU1", "file_url": "https://objects.example/photo.jpg?secret=ignored",
                        "annotations": [],
                    },
                    {
                        "photo_id": "p2", "task_id": "t2", "reviewed_at": "2026-08-02T01:02:04",
                        "sku_code": "SKU1", "file_url": "https://objects.example/photo-copy.jpg",
                        "annotations": [],
                    },
                ]
                with mock.patch.object(vision_lab, "download_bytes", return_value=b"image-bytes") as download:
                    result = vision_lab.collect(cfg, state, rows)
                self.assertEqual(result["records_prepared"], 2)
                self.assertEqual(result["object_downloads"], 2)
                self.assertEqual(result["object_cache_hits"], 0)
                self.assertEqual(download.call_count, 2)
                self.assertEqual(state.get_meta("watermark"), "2026-08-02T01:02:04")
                self.assertEqual(state.db.execute("SELECT COUNT(*) FROM photos").fetchone()[0], 2)
                self.assertEqual(len(list((root / "raw" / "sha256").rglob("*.jpg"))), 1)
                row = state.db.execute("SELECT * FROM photos WHERE photo_id='p1'").fetchone()
                self.assertNotIn("secret", row["object_ref"])
                self.assertTrue(Path(row["local_path"]).is_file())
                self.assertTrue(Path(result["receipt"]).is_file())
            finally:
                state.close()

    def test_collect_reuses_completed_object_download_after_failure(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            cfg = config(root)
            state = vision_lab.State(root)
            try:
                vision_lab.init_layout(cfg, state)
                rows = [
                    {
                        "photo_id": "p1", "task_id": "t1", "reviewed_at": "2026-08-02T01:02:03",
                        "file_url": "https://objects.example/photo.jpg?signature=first", "annotations": [],
                    },
                    {
                        "photo_id": "p2", "task_id": "t2", "reviewed_at": "2026-08-02T01:02:04",
                        "file_url": "https://objects.example/photo-2.jpg", "annotations": [],
                    },
                ]
                with mock.patch.object(
                    vision_lab, "download_bytes", side_effect=[b"first-image", RuntimeError("interrupted")],
                ):
                    with self.assertRaisesRegex(RuntimeError, "interrupted"):
                        vision_lab.collect(cfg, state, rows)
                self.assertEqual(state.get_meta("watermark"), "2026-08-01T00:00:00")

                rows[0]["file_url"] = "https://objects.example/photo.jpg?signature=refreshed"
                with mock.patch.object(vision_lab, "download_bytes", return_value=b"second-image") as download:
                    result = vision_lab.collect(cfg, state, rows)
                self.assertEqual(download.call_count, 1)
                self.assertEqual(result["object_cache_hits"], 1)
                self.assertEqual(result["object_downloads"], 1)
                self.assertEqual(state.get_meta("watermark"), "2026-08-02T01:02:04")
            finally:
                state.close()

    def test_pending_queue_creates_mark(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            queue = root / "source-queue"
            (queue / "annotations-human").mkdir(parents=True)
            manifest = {"version": "queue-v1", "rows": [{"crop_id": "a"}, {"crop_id": "b"}]}
            (queue / "manifest.json").write_text(json.dumps(manifest), encoding="utf-8")
            (queue / "annotations-human" / "a.json").write_text(
                json.dumps({"reviewed": True, "unjudgeable": False}), encoding="utf-8"
            )
            cfg = config(root / "runtime")
            cfg["queue_roots"] = [str(queue)]
            state = vision_lab.State(Path(cfg["runtime_root"]))
            try:
                vision_lab.init_layout(cfg, state)
                result = vision_lab.scan_queues(cfg, state)
                self.assertEqual(result["pending_queues"], 1)
                mark = Path(result["mark"])
                self.assertTrue(mark.is_file())
                self.assertEqual(json.loads(mark.read_text(encoding="utf-8"))["status"], "NEEDS_ANNOTATION")
            finally:
                state.close()

    def test_dataset_uses_task_level_split_and_human_reviews_only(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            queue = root / "queue"
            (queue / "images").mkdir(parents=True)
            (queue / "annotations-human").mkdir()
            rows = []
            for index, task in enumerate(("task-a", "task-b", "task-c", "task-d")):
                image = queue / "images" / f"c{index}.jpg"
                image.write_bytes(f"image-{index}".encode())
                rows.append({
                    "crop_id": f"c{index}", "image": str(image),
                    "image_sha256": vision_lab.sha256_file(image), "source_task_id": task,
                })
                (queue / "annotations-human" / f"c{index}.json").write_text(json.dumps({
                    "reviewed": True, "unjudgeable": False,
                    "boxes": [
                        {"c": 0, "b": [0.1, 0.1, 0.4, 0.4]},
                        {"c": 1, "b": [0.5, 0.5, 0.9, 0.9]},
                    ],
                }), encoding="utf-8")
            (queue / "manifest.json").write_text(json.dumps({"version": "q", "rows": rows}), encoding="utf-8")
            cfg = config(root / "runtime")
            cfg["queue_roots"] = [str(queue)]
            dataset = vision_lab.build_dataset(cfg)
            self.assertTrue(dataset["task_level_split"])
            self.assertFalse(dataset["protected_holdout_included"])
            self.assertEqual(dataset["counts"]["white_boxes"], 4)
            self.assertEqual(dataset["counts"]["color_boxes"], 4)
            by_task = {}
            for row in dataset["rows"]:
                by_task.setdefault(row["task_id"], set()).add(row["split"])
            self.assertTrue(all(len(splits) == 1 for splits in by_task.values()))

    def test_promotion_gate_fails_closed_on_recall_regression(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            artifact = root / "label.onnx"
            artifact.write_bytes(b"candidate")
            model = {
                "model_id": "m1", "artifact": str(artifact),
                "artifact_sha256": vision_lab.sha256_file(artifact),
            }
            metrics = {
                "artifact_sha256": model["artifact_sha256"],
                "production_pipeline_replay": True, "onnx_parity_mismatches": 0,
                "baseline": {"defect_hits": 2, "false_flags": 100},
                "candidate": {"defect_total": 2, "defect_hits": 1, "false_flags": 50, "p95_latency_ms": 100,
                              "groups": {"new_blind_defect": {"defect_total": 2, "defect_hits": 1}}},
            }
            metrics_path = root / "metrics.json"
            metrics_path.write_text(json.dumps(metrics), encoding="utf-8")
            result = vision_lab.evaluate_gate(config(root), model, metrics_path)
            self.assertFalse(result["passed"])
            self.assertIn("real defect recall regressed", result["errors"])
            self.assertIn("minimum protected-defect hit gate not met", result["errors"])
            self.assertIn("required defect group did not reach full recall: new_blind_defect", result["errors"])

    def test_promotion_gate_accepts_measured_improvement(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            artifact = root / "label.onnx"
            artifact.write_bytes(b"candidate")
            digest = vision_lab.sha256_file(artifact)
            model = {"model_id": "m2", "artifact": str(artifact), "artifact_sha256": digest}
            metrics = {
                "artifact_sha256": digest, "production_pipeline_replay": True,
                "onnx_parity_mismatches": 0,
                "baseline": {"defect_hits": 3, "false_flags": 100, "p95_latency_ms": 1000},
                "candidate": {
                    "defect_total": 7, "defect_hits": 4, "false_flags": 80, "p95_latency_ms": 1050,
                    "groups": {"new_blind_defect": {"defect_total": 2, "defect_hits": 2}},
                },
            }
            metrics_path = root / "metrics.json"
            metrics_path.write_text(json.dumps(metrics), encoding="utf-8")
            result = vision_lab.evaluate_gate(config(root), model, metrics_path)
            self.assertTrue(result["passed"], result["errors"])

    def test_promotion_gate_accepts_preserved_zero_false_flag_baseline(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            artifact = root / "label.onnx"
            artifact.write_bytes(b"candidate")
            digest = vision_lab.sha256_file(artifact)
            model = {"model_id": "zero-fp", "artifact": str(artifact), "artifact_sha256": digest}
            metrics = {
                "artifact_sha256": digest,
                "production_pipeline_replay": True,
                "onnx_parity_mismatches": 0,
                "baseline": {
                    "defect_hits": 2, "false_flags": 0, "p95_latency_ms": 1000,
                },
                "candidate": {
                    "defect_total": 2, "defect_hits": 2,
                    "false_flags": 0, "p95_latency_ms": 1000,
                    "groups": {
                        "new_blind_defect": {"defect_total": 1, "defect_hits": 1},
                    },
                },
            }
            metrics_path = root / "metrics.json"
            metrics_path.write_text(json.dumps(metrics), encoding="utf-8")

            result = vision_lab.evaluate_gate(config(root), model, metrics_path)

            self.assertTrue(result["passed"], result["errors"])

    def test_promotion_gate_can_write_an_evaluation_scoped_receipt(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            artifact = root / "label.onnx"
            artifact.write_bytes(b"candidate")
            digest = vision_lab.sha256_file(artifact)
            model = {"model_id": "scoped", "artifact": str(artifact), "artifact_sha256": digest}
            metrics = {
                "artifact_sha256": digest,
                "production_pipeline_replay": True,
                "onnx_parity_mismatches": 0,
                "baseline": {"defect_hits": 2, "false_flags": 0, "p95_latency_ms": 1000},
                "candidate": {
                    "defect_total": 2, "defect_hits": 2,
                    "false_flags": 0, "p95_latency_ms": 1000,
                    "groups": {"new_blind_defect": {"defect_total": 1, "defect_hits": 1}},
                },
            }
            metrics_path = root / "metrics.json"
            metrics_path.write_text(json.dumps(metrics), encoding="utf-8")
            receipt = root / "evaluation" / "gate.json"

            result = vision_lab.evaluate_gate(
                config(root), model, metrics_path, output_path=receipt,
            )

            self.assertTrue(result["passed"], result["errors"])
            self.assertTrue(receipt.is_file())
            self.assertFalse((root / "promotion-gate.json").exists())

    def test_failed_gate_still_refuses_deployment_without_explicit_override(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            artifact = root / "label.onnx"
            artifact.write_bytes(b"candidate")
            cfg = config(root)
            cfg["deployment"] = {"auto_deploy": True}
            state = vision_lab.State(root)
            try:
                with self.assertRaisesRegex(RuntimeError, "promotion gate did not pass"):
                    vision_lab.deploy_candidate(
                        cfg, state,
                        {"model_id": "m3", "artifact": str(artifact),
                         "artifact_sha256": vision_lab.sha256_file(artifact)},
                        {"passed": False, "errors": [
                            "required defect group did not reach full recall: new_blind_defect"
                        ]},
                    )
            finally:
                state.close()

    def test_operator_override_cannot_waive_non_recall_gate_errors(self):
        with self.assertRaisesRegex(RuntimeError, "cannot waive non-recall"):
            vision_lab.validate_operator_recall_override(
                {"passed": False, "errors": ["latency regressed against production"]},
                vision_lab.OPERATOR_RECALL_OVERRIDE_TOKEN,
                "User accepted the measured candidate tradeoff for this release.",
            )

    def test_operator_override_rejects_a_gate_receipt_for_another_artifact(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            artifact = root / "label.onnx"
            artifact.write_bytes(b"candidate")
            model = {
                "model_id": "mismatch", "artifact": str(artifact),
                "artifact_sha256": vision_lab.sha256_file(artifact),
            }
            state = vision_lab.State(root)
            try:
                with self.assertRaisesRegex(RuntimeError, "artifact hash does not match"):
                    vision_lab.deploy_candidate(
                        config(root) | {"deployment": {"auto_deploy": True}}, state, model,
                        {
                            "passed": False,
                            "errors": ["required defect group did not reach full recall: new_blind_defect"],
                            "model_id": model["model_id"],
                            "metrics": {"artifact_sha256": "b" * 64},
                        },
                        operator_override_token=vision_lab.OPERATOR_RECALL_OVERRIDE_TOKEN,
                        operator_override_reason=(
                            "User accepts incomplete new-blind recall because total defect hits improved."
                        ),
                    )
            finally:
                state.close()

    def test_operator_recall_override_is_recorded_in_deployment_receipt(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            (root / "receipts").mkdir()
            artifact = root / "label.onnx"
            artifact.write_bytes(b"candidate")
            candidate_sha = vision_lab.sha256_file(artifact)
            previous_sha = "a" * 64
            cfg = config(root)
            cfg["deployment"] = {
                "auto_deploy": True,
                "confirm_token": "YES-PROD",
                "ssh_host": "root@example",
                "remote_model_path": "/models/label.onnx",
                "service": "cretas-python",
                "health_url": "http://127.0.0.1:8083/health",
            }
            model = {"model_id": "m4", "artifact": str(artifact), "artifact_sha256": candidate_sha}
            gate_error = "required defect group did not reach full recall: new_blind_defect"
            gate = {
                "passed": False,
                "errors": [gate_error],
                "model_id": model["model_id"],
                "metrics": {"artifact_sha256": candidate_sha},
            }
            state = vision_lab.State(root)
            state.set_meta("production_model_sha256", previous_sha)
            try:
                with (
                    mock.patch.object(vision_lab, "ssh_run", side_effect=[
                        previous_sha, "", candidate_sha, "", "active", "200", candidate_sha,
                    ]),
                    mock.patch.object(
                        vision_lab.subprocess, "run", return_value=mock.Mock(returncode=0, stdout="")
                    ),
                ):
                    result = vision_lab.deploy_candidate(
                        cfg, state, model, gate,
                        operator_override_token=vision_lab.OPERATOR_RECALL_OVERRIDE_TOKEN,
                        operator_override_reason=(
                            "User accepts incomplete new-blind recall because total defect hits improved."
                        ),
                    )
                self.assertTrue(result["succeeded"])
                self.assertFalse(result["promotion_gate_passed"])
                self.assertEqual(result["promotion_gate_errors"], [gate_error])
                self.assertEqual(result["operator_override"]["waived_errors"], [gate_error])
                self.assertEqual(state.get_meta("production_model_sha256"), candidate_sha)
                self.assertTrue(Path(result["receipt"]).is_file())
            finally:
                state.close()

    def test_queue_globs_are_discovered_without_duplicates(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            queue = root / "queues" / "q1"
            queue.mkdir(parents=True)
            (queue / "manifest.json").write_text("{}", encoding="utf-8")
            cfg = config(root / "runtime")
            cfg["queue_roots"] = [str(queue)]
            cfg["queue_globs"] = [str(root / "queues" / "*")]
            self.assertEqual(vision_lab.discover_queue_roots(cfg), [queue.resolve()])

    def test_cloud_vl_cannot_be_enabled_without_budget(self):
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "config.json"
            cfg = config(Path(temporary) / "runtime")
            cfg["cloud_vl"]["enabled"] = True
            path.write_text(json.dumps(cfg), encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "budget caps"):
                vision_lab.load_config(path)


if __name__ == "__main__":
    unittest.main()
