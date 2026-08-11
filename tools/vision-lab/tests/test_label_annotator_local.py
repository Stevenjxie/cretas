from __future__ import annotations

import importlib.util
import json
import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace


MODULE_PATH = Path(__file__).resolve().parents[1] / "label_annotator_local.py"
SPEC = importlib.util.spec_from_file_location("label_annotator_local", MODULE_PATH)
assert SPEC and SPEC.loader
module = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(module)


class LabelAnnotatorLocalTests(unittest.TestCase):
    def label_manifest(self) -> dict:
        return {
            "queue_count": 1,
            "rows": [{"crop_id": "crop-1", "image": "images/crop-1.jpg"}],
            "preannotations_are_not_ground_truth": True,
            "every_image_requires_full_human_review": True,
        }

    def test_label_manifest_is_accepted(self):
        with tempfile.TemporaryDirectory() as temporary:
            queue = Path(temporary)
            (queue / "manifest.json").write_text(json.dumps(self.label_manifest()), encoding="utf-8")
            result = module.validate_label_queue(queue)
        self.assertEqual(1, result["queue_count"])

    def test_tray_manifest_is_rejected(self):
        manifest = self.label_manifest()
        manifest["rows"] = [{"packed_image": "packed.jpg"}]
        with tempfile.TemporaryDirectory() as temporary:
            queue = Path(temporary)
            (queue / "manifest.json").write_text(json.dumps(manifest), encoding="utf-8")
            with self.assertRaisesRegex(RuntimeError, "tray_annotator_local.py"):
                module.validate_label_queue(queue)

    def test_modern_missing_class_flow_is_required(self):
        modern = SimpleNamespace(
            ALLOW_MISSING_CLASS=True,
            WIREGUARD_HOST="",
            PAGE="missing_confirmed 确认这是真的被拔掉了",
        )
        module.validate_modern_annotator(modern, Path("label_hard_negative_annotator.py"))
        legacy = SimpleNamespace(
            ALLOW_MISSING_CLASS=True,
            WIREGUARD_HOST="",
            PAGE="missing_confirmed 确认这是真的被拔掉了 new Set(classes).size<2",
        )
        with self.assertRaisesRegex(RuntimeError, "legacy two-class hard block"):
            module.validate_modern_annotator(legacy, Path("label_hard_negative_annotator.py"))

    def test_legacy_script_name_is_forbidden(self):
        with self.assertRaisesRegex(RuntimeError, "legacy label annotator is forbidden"):
            module.validate_modern_annotator(SimpleNamespace(), Path("label_legacy_adapter.py"))


if __name__ == "__main__":
    unittest.main()
