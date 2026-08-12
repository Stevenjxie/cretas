from __future__ import annotations

import argparse
import importlib.util
import json
import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace

from PIL import Image


MODULE_PATH = Path(__file__).resolve().parents[1] / "mine_label_side_view_queue.py"
SPEC = importlib.util.spec_from_file_location("mine_label_side_view_queue", MODULE_PATH)
assert SPEC and SPEC.loader
module = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(module)


class LabelSideViewQueueTests(unittest.TestCase):
    @staticmethod
    def _label(class_id: int, confidence: float, box: list[float]) -> SimpleNamespace:
        return SimpleNamespace(class_id=class_id, confidence=confidence, box=box)

    def test_side_risk_requires_view_or_stack_evidence(self):
        plain = SimpleNamespace(
            index=0, box=[0, 0, 150, 100], dropped_neighbour_labels=0,
        )
        self.assertEqual(module.side_risk_features(plain, [plain], 200, 200)["tags"], [])

        wide = SimpleNamespace(
            index=1, box=[0, 0, 210, 100], dropped_neighbour_labels=0,
        )
        neighbour = SimpleNamespace(
            index=2, box=[100, 20, 260, 120], dropped_neighbour_labels=0,
        )
        result = module.side_risk_features(wide, [wide, neighbour], 300, 200)
        self.assertIn("wide_side_view", result["tags"])
        self.assertIn("stacked_overlap", result["tags"])
        self.assertGreater(result["score"], 5)

    def test_manifest_sources_are_excluded_by_source_photo(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            first = root / "complete.json"
            second = root / "old-29.json"
            first.write_text(json.dumps({"rows": [{"source_photo_id": "p1"}]}), encoding="utf-8")
            second.write_text(json.dumps({"rows": [{"source_photo_id": "p2"}]}), encoding="utf-8")
            self.assertEqual(module.manifest_source_ids([first, second]), {"p1", "p2"})

    def test_white_confuser_ranks_amplified_matched_white_box(self):
        production = SimpleNamespace(labels=[self._label(0, 0.72, [10, 10, 40, 40])])
        candidate = SimpleNamespace(labels=[self._label(0, 0.81, [11, 10, 41, 40])])
        result = module.white_confuser_features(production, candidate, 0.35, 0.05, 0.30)
        self.assertIsNotNone(result)
        assert result is not None
        self.assertEqual(result["tags"], ["candidate_white_amplified"])
        self.assertAlmostEqual(result["white_confidence_delta"], 0.09)
        self.assertGreater(result["matched_white_iou"], 0.9)

    def test_white_confuser_ranks_new_unmatched_white_box(self):
        production = SimpleNamespace(labels=[self._label(0, 0.80, [80, 80, 100, 100])])
        candidate = SimpleNamespace(labels=[self._label(0, 0.55, [10, 10, 30, 30])])
        result = module.white_confuser_features(production, candidate, 0.35, 0.05, 0.30)
        self.assertIsNotNone(result)
        assert result is not None
        self.assertEqual(result["tags"], ["candidate_white_new"])
        self.assertEqual(result["production_white_confidence"], 0.0)

    def test_white_confuser_rejects_small_delta(self):
        production = SimpleNamespace(labels=[self._label(0, 0.72, [10, 10, 40, 40])])
        candidate = SimpleNamespace(labels=[self._label(0, 0.74, [10, 10, 40, 40])])
        self.assertIsNone(module.white_confuser_features(production, candidate, 0.35, 0.05, 0.30))

    def test_match_tray_is_iou_gated(self):
        target = SimpleNamespace(box=[10, 10, 50, 50])
        near = SimpleNamespace(box=[11, 10, 51, 50])
        far = SimpleNamespace(box=[100, 100, 140, 140])
        matched, score = module.match_tray(target, [far, near], 0.90)
        self.assertIs(matched, near)
        self.assertGreater(score, 0.9)
        unmatched, _ = module.match_tray(target, [far], 0.90)
        self.assertIsNone(unmatched)

    def _plan(self, root: Path) -> tuple[Path, Path]:
        source = root / "source.png"
        Image.new("RGB", (120, 80), "white").save(source)
        with Image.open(source) as opened:
            crop = opened.convert("RGB")
            crop_sha = __import__("hashlib").sha256(crop.tobytes()).hexdigest()
            crop_phash = module.image_phash(crop)
        row = {
            "source_photo_id": "photo-new",
            "source_task_id": "task-new",
            "source_path": str(source),
            "source_sha256": module.sha256_file(source),
            "source_perceptual_hash": crop_phash,
            "nearest_holdout_phash_distance": 99,
            "nearest_existing_label_phash_distance": 99,
            "sku_code": "SKU1",
            "tray_index": 2,
            "tray_box": [0, 0, 120, 80],
            "tray_confidence": 0.9,
            "label_v1_verdict": "MISSING_WHITE_LABEL",
            "has_white": False,
            "has_color": True,
            "crop_rect": [0, 0, 120, 80],
            "crop_sha256": crop_sha,
            "crop_perceptual_hash": crop_phash,
            "crop_size": [120, 80],
            "selection_tags": ["wide_oblique"],
            "selection_score": 1.5,
            "tray_aspect_ratio": 1.5,
            "maximum_tray_overlap": 0,
            "tray_area_fraction": 1,
            "dropped_neighbour_labels": 0,
            "prelabel_boxes": [{"class_id": 1, "bbox_normalized_xyxy": [0.1, 0.1, 0.3, 0.4], "confidence": 0.8}],
            "human_source_truth": "NO_DEFECT",
            "requires_full_human_review": True,
        }
        plan = root / "label-side-view-preflight.json"
        payload = {
            "version": "vision-lab-label-side-view-preflight-v1",
            "created_at": "2026-08-11T05:10:00+00:00",
            "status": "ready",
            "candidate_digest": module.stable_digest(module.plan_identity([row])),
            "rows": [row],
            "protected_holdout_included": False,
            "existing_label_sources_included": False,
            "cloud_calls": 0,
            "production_writes": 0,
        }
        module.write_json(plan, payload)
        return plan, source

    def test_build_queue_is_bound_to_plan_and_separate_mark(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            plan, _ = self._plan(root)
            args = argparse.Namespace(
                plan=plan,
                output_root=root / "queues",
                attention_root=root / "attention",
                annotator_url="http://127.0.0.1:8792",
                receipt=root / "receipt.json",
            )
            result = module.build_queue(args)
            queue = Path(result["queue"])
            self.assertTrue((queue / "manifest.json").is_file())
            self.assertTrue((queue / "prelabels" / "lsv_photo-ne_t02_001.json").is_file())
            self.assertTrue((queue / "annotations-human").is_dir())
            mark = root / "attention" / "MARK-NEEDS-LABEL-SIDE-VIEW-ANNOTATION.json"
            self.assertTrue(mark.is_file())
            self.assertNotIn("TRAY", mark.name)
            manifest = module.load_json(queue / "manifest.json")
            self.assertFalse(manifest["protected_holdout_included"])
            self.assertFalse(manifest["preannotations_are_not_ground_truth"] is False)
            self.assertEqual(manifest["queue_count"], 1)

    def test_build_rejects_plan_digest_drift(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            plan, _ = self._plan(root)
            payload = module.load_json(plan)
            payload["rows"][0]["tray_index"] = 99
            module.write_json(plan, payload)
            args = argparse.Namespace(
                plan=plan,
                output_root=root / "queues",
                attention_root=root / "attention",
                annotator_url="http://127.0.0.1:8792",
                receipt=None,
            )
            with self.assertRaisesRegex(RuntimeError, "candidate digest drift"):
                module.build_queue(args)

    def test_crop_rebuild_resolves_serialized_integer_boundary(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            source = root / "source.png"
            pixels = bytes(index % 251 for index in range(121 * 81 * 3))
            Image.frombytes("RGB", (121, 81), pixels).save(source)
            with Image.open(source) as opened:
                expected = opened.convert("RGB").crop((0, 0, 120, 80))
            row = {
                "source_photo_id": "boundary-photo",
                "tray_index": 1,
                "source_path": str(source),
                "source_sha256": module.sha256_file(source),
                "crop_rect": [1.0, 1.0, 121.0, 81.0],
                "crop_size": [120, 80],
                "crop_sha256": __import__("hashlib").sha256(expected.tobytes()).hexdigest(),
                "crop_perceptual_hash": module.image_phash(expected),
            }
            rebuilt = module.crop_from_plan(row)
            self.assertEqual(rebuilt.tobytes(), expected.tobytes())

    def test_normal_truth_rejects_any_confirmed_defect(self):
        self.assertTrue(module.normal_truth(json.dumps([{"human_label": "NO_DEFECT"}])))
        self.assertFalse(module.normal_truth(json.dumps([
            {"human_label": "NO_DEFECT"}, {"human_label": "MISSING_WHITE_LABEL"},
        ])))


if __name__ == "__main__":
    unittest.main()
