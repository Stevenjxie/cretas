from __future__ import annotations

import importlib.util
import json
import sys
import tempfile
import unittest
from pathlib import Path

from PIL import Image


ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
SPEC = importlib.util.spec_from_file_location("work_area_annotator_local", ROOT / "work_area_annotator_local.py")
assert SPEC and SPEC.loader
module = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(module)


class WorkAreaAnnotatorTests(unittest.TestCase):
    def _queue(self, root: Path) -> tuple[Path, dict]:
        queue = root / "queue"
        (queue / "images").mkdir(parents=True)
        (queue / "annotations-human").mkdir()
        Image.new("RGB", (64, 48), (80, 100, 120)).save(queue / "images" / "tray-1.jpg")
        (queue / "annotations-human" / "tray-1.json").write_text(json.dumps({
            "photo_id": "tray-1", "reviewed": True, "source": "human",
            "format": "normalised_xyxy", "boxes": [[0.2, 0.2, 0.5, 0.5], [0.85, 0.0, 0.99, 0.1]],
        }), encoding="utf-8")
        manifest = {
            "queue_count": 1, "protected_holdout_included": False,
            "rows": [{
                "packed_stem": "tray-1", "packed_image": "images/tray-1.jpg",
                "source_photo_id": "source-1", "task_id": "task-1", "sku_code": "SKU-1",
            }],
        }
        (queue / "manifest.json").write_text(json.dumps(manifest), encoding="utf-8")
        return queue, manifest

    def test_build_items_requires_reviewed_tray_context_and_never_edits_it(self):
        with tempfile.TemporaryDirectory() as temporary:
            queue, manifest = self._queue(Path(temporary))
            before = (queue / "annotations-human" / "tray-1.json").read_bytes()
            items = module.build_items(queue, manifest)
            self.assertEqual(items[0]["tray_boxes"][0], [0.2, 0.2, 0.5, 0.5])
            self.assertEqual(before, (queue / "annotations-human" / "tray-1.json").read_bytes())
            self.assertTrue((queue / "work-area-human").is_dir())

    def test_build_annotation_reports_inside_and_outside(self):
        item = {
            "id": "tray-1", "source_photo_id": "source-1", "task_id": "task-1", "sku_code": "SKU-1",
            "tray_boxes": [[0.2, 0.2, 0.5, 0.5], [0.85, 0.0, 0.99, 0.1]],
        }
        result = module.build_annotation(item, {
            "judgeable": True, "polygon": [[0.1, 0.1], [0.8, 0.1], [0.8, 0.9], [0.1, 0.9]],
        })
        self.assertEqual(result["tray_scope_counts"], {"inside_work_area": 1, "outside_work_area": 1})
        self.assertTrue(result["outside_samples_retained"])
        self.assertEqual(result["scope_rule"], "tray_center_in_polygon")

    def test_training_queue_rejects_protected_holdout(self):
        with tempfile.TemporaryDirectory() as temporary:
            queue = Path(temporary)
            (queue / "manifest.json").write_text(json.dumps({
                "protected_holdout_included": True,
                "rows": [{"packed_stem": "x", "packed_image": "images/x.jpg"}],
            }), encoding="utf-8")
            with self.assertRaisesRegex(RuntimeError, "protected holdout"):
                module.validate_queue(queue)


if __name__ == "__main__":
    unittest.main()
