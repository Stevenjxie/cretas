from __future__ import annotations

import importlib.util
import hashlib
import json
import sqlite3
import tempfile
import unittest
from pathlib import Path

from PIL import Image, ImageDraw


MODULE_PATH = Path(__file__).resolve().parents[1] / "mine_tray_queue.py"
SPEC = importlib.util.spec_from_file_location("mine_tray_queue", MODULE_PATH)
assert SPEC and SPEC.loader
module = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(module)


class MineTrayQueueTests(unittest.TestCase):
    def test_teacher_default_is_stable_offline_b_drive_path(self):
        self.assertEqual(module.DEFAULT_TEACHER_PATH, Path(r"B:\AIModels\LocateAnything-3B"))

    def test_teacher_regions_are_local_crops_and_map_back_to_full_image(self):
        image = Image.new("RGB", (400, 300), (30, 30, 30))
        ImageDraw.Draw(image).rectangle((300, 20, 390, 140), fill=(20, 70, 210))
        record = {
            "detector_boxes": [
                {"box": [0.0, 0.1, 0.2, 0.3], "confidence": 0.2},
                {"box": [0.65, 0.1, 0.85, 0.3], "confidence": 0.9},
            ],
            "comparison_boxes": [{"box": [0.7, 0.5, 0.9, 0.7], "confidence": 0.8}],
        }
        regions = module.teacher_crop_regions(record, image)
        self.assertTrue(regions)
        self.assertTrue(any("blue_basket" in row["reasons"] for row in regions))
        self.assertTrue(all(module.box_area(row["box"]) < 0.72 for row in regions))
        self.assertNotIn([0.0, 0.0, 1.0, 1.0], [row["box"] for row in regions])
        self.assertEqual(
            module.map_crop_box([0.25, 0.25, 0.75, 0.75], [0.2, 0.4, 0.6, 0.8]),
            [0.3, 0.5, 0.5, 0.7],
        )

    def test_blue_basket_context_detects_top_scene_without_creating_label(self):
        image = Image.new("RGB", (400, 300), (50, 50, 50))
        ImageDraw.Draw(image).rectangle((250, 10, 390, 90), fill=(10, 80, 210))
        result = module.blue_basket_features(image)
        self.assertTrue(result["present"])
        self.assertTrue(result["top_half"])
        self.assertIs(type(result["top_half"]), bool)
        self.assertGreater(result["risk_score"], 10)
        self.assertNotIn("label", result)
        json.dumps(result)

    def test_reuse_selection_reads_full_source_ids_in_queue_order(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            annotations = root / "annotations-human"
            annotations.mkdir()
            for index, photo_id in enumerate(("full-photo-a", "full-photo-b"), 1):
                (annotations / f"trayal_{index:03d}.json").write_text(
                    json.dumps({"source_photo_id": photo_id}), encoding="utf-8",
                )
            self.assertEqual(module.reuse_selection_photo_ids(root), ["full-photo-a", "full-photo-b"])

    def test_blue_basket_priority_fills_two_thirds_before_generic_risk(self):
        rows = []
        for index in range(6):
            rows.append({
                "photo_id": f"photo-{index}", "task_id": f"task-{index}",
                "image_phash": hashlib.sha256(f"photo-{index}".encode()).hexdigest(),
                "selection_score": float(100 - index),
                "selection_tags": ["top_blue_basket"] if index in {4, 5} else ["edge"],
            })
        selected = module.select_queue(rows, queue_size=3, task_cap=1, priority_tag="top_blue_basket")
        self.assertEqual([row["photo_id"] for row in selected[:2]], ["photo-4", "photo-5"])

    def test_teacher_parser_rejects_full_frame_and_keeps_tray_box(self):
        answer = (
            "<ref>tray</ref><box><0><0><1000><1000></box>"
            "<ref>tray</ref><box><100><200><500><500></box>"
        )
        self.assertEqual(module.parse_teacher_boxes(answer), [[0.1, 0.2, 0.5, 0.5]])

    def test_teacher_only_box_needs_multi_prompt_support(self):
        detector = [{"box": [0.1, 0.1, 0.3, 0.3], "confidence": 0.8}]
        teacher = [
            {"box": [0.6, 0.6, 0.8, 0.8], "support": ["all"]},
            {"box": [0.4, 0.4, 0.55, 0.55], "support": ["isolated", "edge"]},
        ]
        merged = module.merge_preannotations(detector, teacher)
        boxes = [row["box"] for row in merged]
        self.assertIn(detector[0]["box"], boxes)
        self.assertIn(teacher[1]["box"], boxes)
        self.assertNotIn(teacher[0]["box"], boxes)
        self.assertTrue(all(row["source"] != "ground_truth" for row in merged))

    def test_feature_summary_tags_edge_isolated_and_overlap(self):
        boxes = [
            {"box": [0.0, 0.1, 0.2, 0.3], "confidence": 0.9},
            {"box": [0.65, 0.6, 0.9, 0.9], "confidence": 0.2},
            {"box": [0.7, 0.65, 0.92, 0.92], "confidence": 0.8},
        ]
        result = module.feature_summary(boxes)
        self.assertIn("edge", result["tags"])
        self.assertIn("isolated", result["tags"])
        self.assertIn("stacked_occluded", result["tags"])
        self.assertEqual(result["low_confidence_count"], 1)

    def test_rejected_comparison_model_only_contributes_disagreement_risk(self):
        production = [{"box": [0.1, 0.1, 0.3, 0.3], "confidence": 0.9}]
        comparison = [
            {"box": [0.11, 0.11, 0.31, 0.31], "confidence": 0.9},
            {"box": [0.7, 0.7, 0.9, 0.9], "confidence": 0.8},
        ]
        result = module.detector_disagreement(production, comparison)
        self.assertEqual(result["unmatched_comparison"], 1)
        self.assertGreater(result["risk_score"], 0)
        self.assertFalse(result["comparison_is_ground_truth"])
        merged = module.merge_preannotations(production, [])
        self.assertEqual([row["box"] for row in merged], [production[0]["box"]])

    def test_load_candidates_excludes_holdout_id_hash_near_duplicate_and_existing_queue(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            images = root / "images"
            images.mkdir()

            def make_image(name: str, color: tuple[int, int, int], diagonal: bool = False) -> Path:
                path = images / name
                image = Image.new("RGB", (64, 64), color)
                if diagonal:
                    ImageDraw.Draw(image).line((0, 0, 63, 63), fill=(255, 255, 255), width=5)
                image.save(path)
                return path

            holdout = make_image("holdout.png", (10, 20, 30), True)
            exact = make_image("exact.png", (10, 20, 30), True)
            near = make_image("near.png", (10, 20, 30), True)
            with Image.open(near) as opened:
                near_image = opened.convert("RGB")
            near_image.putpixel((63, 0), (11, 20, 30))
            near_image.save(near)
            existing = make_image("existing.png", (100, 20, 30), False)
            eligible = make_image("eligible.png", (20, 100, 200), False)
            with Image.open(eligible) as opened:
                eligible_image = opened.convert("RGB")
            ImageDraw.Draw(eligible_image).ellipse((8, 20, 56, 44), fill=(250, 210, 30))
            eligible_image.save(eligible)
            database = root / "vision.db"
            connection = sqlite3.connect(database)
            connection.execute(
                "CREATE TABLE photos(photo_id TEXT, task_id TEXT, reviewed_at TEXT, sku_code TEXT, object_ref TEXT, sha256 TEXT, local_path TEXT, annotations_json TEXT, collected_at TEXT)"
            )
            for index, (photo_id, path) in enumerate((
                ("holdout-id", eligible), ("exact-id", exact), ("near-id", near),
                ("existing-id", existing), ("eligible-id", eligible),
            )):
                digest = module.sha256_file(path)
                if photo_id == "holdout-id":
                    digest = "f" * 64
                connection.execute(
                    "INSERT INTO photos VALUES(?,?,?,?,?,?,?,?,?)",
                    (photo_id, f"t{index}", f"2026-08-10T00:00:0{index}", "SKU", "local", digest, str(path), "[]", "now"),
                )
            connection.commit()
            connection.close()
            protected = {
                "ids": {"holdout-id"}, "hashes": {module.sha256_file(holdout)},
                "phashes": [("protected", module.phash(holdout))], "count": 1,
            }
            rows, excluded = module.load_candidates(database, protected, {"existing-id"}, 10, 10)
            self.assertEqual([row["photo_id"] for row in rows], ["eligible-id"])
            self.assertEqual(excluded["protected_photo_id"], 1)
            self.assertEqual(excluded["protected_sha256"], 1)
            self.assertEqual(excluded["protected_near_duplicate"], 1)
            self.assertEqual(excluded["existing_queue"], 1)

    def test_existing_queue_manifest_supports_label_and_tray_identifiers(self):
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "manifest.json"
            path.write_text(json.dumps({"rows": [
                {"source_photo_id": "label-photo"}, {"photo_id": "tray-photo"},
            ]}), encoding="utf-8")
            self.assertEqual(module.existing_photo_ids([path]), {"label-photo", "tray-photo"})


if __name__ == "__main__":
    unittest.main()
