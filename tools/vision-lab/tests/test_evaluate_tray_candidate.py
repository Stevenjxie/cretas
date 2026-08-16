from __future__ import annotations

import importlib.util
import json
import sys
import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace

from PIL import Image


ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
SPEC = importlib.util.spec_from_file_location("evaluate_tray_candidate", ROOT / "evaluate_tray_candidate.py")
assert SPEC and SPEC.loader
module = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(module)


class _Models:
    available = True
    load_error = None

    def __init__(self, model_dir):
        self.model_dir = model_dir


class _Yolo:
    LabelQcYoloModels = _Models


class _Params:
    def __init__(self, **kwargs):
        self.kwargs = kwargs


class _Screening:
    ScreeningParams = _Params

    def __init__(self, results):
        self.results = iter(results)
        self.seen_params = []

    def screen_image(self, frame, models, params):
        self.seen_params.append(params)
        return next(self.results)


def _tray(box, verdict="MISSING_WHITE_LABEL"):
    return SimpleNamespace(box=box, verdict=verdict)


class EvaluateTrayCandidateWorkAreaTests(unittest.TestCase):
    def test_truth_adjudication_excludes_only_hash_bound_human_reviewed_record(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            image = root / "photo.jpg"
            Image.new("RGB", (100, 100), (30, 40, 50)).save(image)
            manifest = root / "protected.json"
            manifest.write_text(json.dumps({"records": [{
                "photo_id": "stale", "image": str(image),
                "image_sha256": module.sha256_file(image),
                "human_label": "MISSING_WHITE_LABEL", "group": "new_blind_defect",
            }]}), encoding="utf-8")
            evidence = root / "human.json"
            evidence.write_text(json.dumps({
                "reviewed": True, "source": "human", "boxes": [],
            }), encoding="utf-8")
            adjudication = root / "adjudication.json"
            adjudication.write_text(json.dumps({
                "version": module.TRUTH_ADJUDICATION_VERSION,
                "protected_manifests": [{
                    "path": str(manifest), "sha256": module.sha256_file(manifest),
                }],
                "records": [{
                    "photo_id": "stale",
                    "source_sha256": module.sha256_file(image),
                    "original_human_label": "MISSING_WHITE_LABEL",
                    "action": "exclude",
                    "evidence": [{
                        "path": str(evidence), "sha256": module.sha256_file(evidence),
                    }],
                }],
            }), encoding="utf-8")
            records = [{
                "photo_id": "stale", "image": image,
                "human_label": "MISSING_WHITE_LABEL", "bbox": None,
                "group": "new_blind_defect",
            }]

            adjusted, audit = module.apply_truth_adjudication(
                records, adjudication, [manifest],
            )

            self.assertEqual(adjusted, [])
            self.assertEqual(audit["excluded_photo_ids"], ["stale"])
            self.assertFalse(audit["protected_manifest_modified"])

    def test_truth_adjudication_rejects_unreviewed_evidence(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            image = root / "photo.jpg"
            Image.new("RGB", (100, 100), (30, 40, 50)).save(image)
            manifest = root / "protected.json"
            manifest.write_text("[]", encoding="utf-8")
            evidence = root / "proposal.json"
            evidence.write_text(json.dumps({
                "reviewed": False, "source": "model",
            }), encoding="utf-8")
            adjudication = root / "adjudication.json"
            adjudication.write_text(json.dumps({
                "version": module.TRUTH_ADJUDICATION_VERSION,
                "protected_manifests": [{
                    "path": str(manifest), "sha256": module.sha256_file(manifest),
                }],
                "records": [{
                    "photo_id": "stale", "source_sha256": module.sha256_file(image),
                    "original_human_label": "MISSING_WHITE_LABEL", "action": "exclude",
                    "evidence": [{
                        "path": str(evidence), "sha256": module.sha256_file(evidence),
                    }],
                }],
            }), encoding="utf-8")

            with self.assertRaisesRegex(RuntimeError, "not reviewed human truth"):
                module.apply_truth_adjudication(
                    [{
                        "photo_id": "stale", "image": image,
                        "human_label": "MISSING_WHITE_LABEL", "bbox": None,
                        "group": "new_blind_defect",
                    }],
                    adjudication, [manifest],
                )

    def test_truth_adjudication_adds_one_hash_bound_task_independent_record(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            protected_image = root / "protected.jpg"
            added_image = root / "prospective.jpg"
            Image.new("RGB", (100, 100), (30, 40, 50)).save(protected_image)
            Image.new("RGB", (120, 100), (50, 40, 30)).save(added_image)
            manifest = root / "protected.json"
            manifest.write_text(json.dumps({"records": [{
                "photo_id": "protected", "task_id": "old-task",
                "image": str(protected_image),
                "image_sha256": module.sha256_file(protected_image),
                "human_label": "NO_DEFECT", "group": "normal",
            }]}), encoding="utf-8")
            evidence = root / "human.json"
            evidence.write_text(json.dumps({
                "reviewed": True, "source": "human", "boxes": [{"c": 1}],
            }), encoding="utf-8")
            adjudication = root / "adjudication.json"
            adjudication.write_text(json.dumps({
                "version": module.TRUTH_ADJUDICATION_VERSION,
                "protected_manifests": [{
                    "path": str(manifest), "sha256": module.sha256_file(manifest),
                }],
                "records": [{
                    "photo_id": "prospective", "task_id": "new-task",
                    "image": str(added_image),
                    "source_sha256": module.sha256_file(added_image),
                    "action": "add", "human_label": "MISSING_WHITE_LABEL",
                    "bbox": [0.1, 0.2, 0.8, 0.9],
                    "group": "prospective_model_flag_human_defect",
                    "evidence": [{
                        "path": str(evidence), "sha256": module.sha256_file(evidence),
                    }],
                }],
            }), encoding="utf-8")

            adjusted, audit = module.apply_truth_adjudication(
                [{
                    "photo_id": "protected", "task_id": "old-task",
                    "image": protected_image, "human_label": "NO_DEFECT",
                    "bbox": None, "group": "normal",
                }],
                adjudication, [manifest],
            )

            self.assertEqual(len(adjusted), 2)
            self.assertEqual(adjusted[-1]["photo_id"], "prospective")
            self.assertEqual(adjusted[-1]["task_id"], "new-task")
            self.assertEqual(adjusted[-1]["human_label"], "MISSING_WHITE_LABEL")
            self.assertEqual(audit["added_photo_ids"], ["prospective"])

    def test_truth_adjudication_rejects_added_record_from_existing_task(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            protected_image = root / "protected.jpg"
            added_image = root / "prospective.jpg"
            Image.new("RGB", (100, 100), (30, 40, 50)).save(protected_image)
            Image.new("RGB", (120, 100), (50, 40, 30)).save(added_image)
            manifest = root / "protected.json"
            manifest.write_text("[]", encoding="utf-8")
            evidence = root / "human.json"
            evidence.write_text(json.dumps({
                "reviewed": True, "source": "human",
            }), encoding="utf-8")
            adjudication = root / "adjudication.json"
            adjudication.write_text(json.dumps({
                "version": module.TRUTH_ADJUDICATION_VERSION,
                "protected_manifests": [{
                    "path": str(manifest), "sha256": module.sha256_file(manifest),
                }],
                "records": [{
                    "photo_id": "prospective", "task_id": "shared-task",
                    "image": str(added_image),
                    "source_sha256": module.sha256_file(added_image),
                    "action": "add", "human_label": "MISSING_WHITE_LABEL",
                    "bbox": [0.1, 0.2, 0.8, 0.9],
                    "evidence": [{
                        "path": str(evidence), "sha256": module.sha256_file(evidence),
                    }],
                }],
            }), encoding="utf-8")

            with self.assertRaisesRegex(RuntimeError, "not task-independent"):
                module.apply_truth_adjudication(
                    [{
                        "photo_id": "protected", "task_id": "shared-task",
                        "image": protected_image, "human_label": "NO_DEFECT",
                        "bbox": None, "group": "normal",
                    }],
                    adjudication, [manifest],
                )

    def test_evaluation_forwards_explicit_minimum_crop_pixels(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            image = root / "normal.jpg"
            Image.new("RGB", (100, 100), (30, 40, 50)).save(image)
            screening = _Screening([
                SimpleNamespace(trays=[], suspects=[]),
            ])

            module.evaluate(
                root,
                [{
                    "photo_id": "normal", "image": str(image),
                    "image_sha256": module.sha256_file(image),
                    "human_label": "NORMAL", "bbox": None, "group": "normal",
                }],
                screening, _Yolo, 0.2, {}, min_crop_px=150,
            )

            self.assertEqual(screening.seen_params[0].kwargs["min_crop_px"], 150)

    def test_evaluation_splits_inside_outside_without_dropping_trays(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            defect_image = root / "defect.jpg"
            normal_image = root / "normal.jpg"
            Image.new("RGB", (100, 100), (30, 40, 50)).save(defect_image)
            Image.new("RGB", (100, 100), (60, 70, 80)).save(normal_image)
            records = [
                {
                    "photo_id": "defect", "image": str(defect_image),
                    "image_sha256": module.sha256_file(defect_image),
                    "human_label": "MISSING_WHITE_LABEL", "bbox": [0.7, 0.2, 0.9, 0.4],
                    "group": "new_blind_defect",
                },
                {
                    "photo_id": "normal", "image": str(normal_image),
                    "image_sha256": module.sha256_file(normal_image),
                    "human_label": "NORMAL", "bbox": None, "group": "normal",
                },
            ]
            annotations = {
                photo_id: module.work_area.validate_human_annotation({
                    "photo_id": photo_id, "source_photo_id": photo_id,
                    "source_sha256": row["image_sha256"],
                    "format": module.work_area.FORMAT, "reviewed": True, "source": "human",
                    "judgeable": True,
                    "polygon": [[0.05, 0.05], [0.6, 0.05], [0.6, 0.9], [0.05, 0.9]],
                })
                for photo_id, row in (("defect", records[0]), ("normal", records[1]))
            }
            outside = _tray([70, 20, 90, 40])
            inside_false = _tray([10, 10, 30, 30])
            screening = _Screening([
                SimpleNamespace(trays=[outside], suspects=[outside]),
                SimpleNamespace(trays=[inside_false], suspects=[inside_false]),
            ])
            result = module.evaluate(root, records, screening, _Yolo, 0.2, annotations)
            self.assertEqual(result["defect_hits"], 1)
            self.assertEqual(result["work_area"]["groups"]["outside_work_area"]["defect_hits"], 1)
            self.assertEqual(result["work_area"]["groups"]["inside_work_area"]["false_flags"], 1)
            self.assertEqual(result["work_area"]["groups"]["unknown_work_area"]["detected_trays"], 0)
            self.assertEqual(result["details"][0]["work_area"], "outside_work_area")

    def test_missing_roi_fails_closed_to_unknown(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            image = root / "defect.jpg"
            Image.new("RGB", (100, 100), (30, 40, 50)).save(image)
            tray = _tray([70, 20, 90, 40])
            result = module.evaluate(
                root,
                [{
                    "photo_id": "defect", "image": str(image),
                    "image_sha256": module.sha256_file(image),
                    "human_label": "MISSING_WHITE_LABEL", "bbox": [0.7, 0.2, 0.9, 0.4],
                    "group": "new_blind_defect",
                }],
                _Screening([SimpleNamespace(trays=[tray], suspects=[tray])]), _Yolo, 0.2, {},
            )
            unknown = result["work_area"]["groups"]["unknown_work_area"]
            self.assertEqual(unknown["defect_total"], 1)
            self.assertEqual(unknown["detected_trays"], 1)
            self.assertEqual(result["work_area"]["records"]["without_human_roi"], 1)


if __name__ == "__main__":
    unittest.main()
