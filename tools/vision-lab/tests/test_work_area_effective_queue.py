from __future__ import annotations

import hashlib
import importlib.util
import json
import sys
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
SPEC = importlib.util.spec_from_file_location(
    "work_area_effective_queue", ROOT / "work_area_effective_queue.py",
)
assert SPEC and SPEC.loader
module = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(module)


def digest(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def write_json(path: Path, value: dict) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value), encoding="utf-8")


def build_queue(root: Path, name: str, records: list[tuple[str, str, str, bool]]) -> Path:
    queue = root / name
    for folder in ("images", "annotations-human", "work-area-human"):
        (queue / folder).mkdir(parents=True)
    rows = []
    for stem, task_id, sku, judgeable in records:
        source = root / "sources" / f"{stem}.jpg"
        source.parent.mkdir(exist_ok=True)
        source.write_bytes(f"source-{stem}".encode())
        packed = queue / "images" / f"{stem}.jpg"
        packed.write_bytes(f"packed-{stem}".encode())
        box = [0.3, 0.3, 0.5, 0.5]
        row = {
            "packed_stem": stem,
            "packed_image": f"images/{stem}.jpg",
            "packed_image_sha256": digest(packed),
            "source_path": str(source),
            "source_sha256": digest(source),
            "source_photo_id": f"photo-{stem}",
            "task_id": task_id,
            "sku_code": sku,
        }
        rows.append(row)
        write_json(queue / "annotations-human" / f"{stem}.json", {
            "photo_id": stem, "format": "normalised_xyxy",
            "reviewed": True, "source": "human", "boxes": [box],
        })
        roi = {
            "photo_id": stem,
            "source_photo_id": row["source_photo_id"],
            "source_sha256": row["source_sha256"],
            "packed_image_sha256": row["packed_image_sha256"],
            "format": "normalised_polygon_v1",
            "reviewed": True,
            "source": "human",
            "judgeable": judgeable,
            "polygon": [[0.1, 0.1], [0.1, 0.8], [0.8, 0.8], [0.8, 0.1]] if judgeable else None,
            "tray_scope_counts": {"inside_work_area": 1, "outside_work_area": 0}
            if judgeable else {"unknown_work_area": 1},
        }
        if not judgeable:
            roi["unjudgeable_reason"] = "work_area_not_visible_or_unjudgeable"
        write_json(queue / "work-area-human" / f"{stem}.json", roi)
    write_json(queue / "manifest.json", {
        "version": "vision-lab-work-area-queue-v1",
        "queue_count": len(rows),
        "protected_holdout_included": False,
        "rows": rows,
    })
    return queue


class WorkAreaEffectiveQueueTests(unittest.TestCase):
    def test_replaces_one_unjudgeable_row_without_modifying_sources(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            base = build_queue(root, "base", [
                ("keep", "task-keep", "SKU-1", True),
                ("drop", "task-drop", "SKU-2", False),
            ])
            replacement = build_queue(root, "replacement", [
                ("new", "task-new", "SKU-2", True),
            ])
            base_sha, replacement_sha = digest(base / "manifest.json"), digest(replacement / "manifest.json")
            queue, receipt = module.build_effective_queue(
                base, replacement, "drop", root / "queues", root,
            )
            manifest = json.loads((queue / "manifest.json").read_text())
            self.assertEqual({row["packed_stem"] for row in manifest["rows"]}, {"keep", "new"})
            self.assertEqual(manifest["replaced_packed_stem"], "drop")
            self.assertTrue(manifest["replaced_record_retained_at_source"])
            self.assertEqual(digest(base / "manifest.json"), base_sha)
            self.assertEqual(digest(replacement / "manifest.json"), replacement_sha)
            self.assertTrue(receipt.is_file())

    def test_rejects_replacement_with_different_sku(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            base = build_queue(root, "base", [("drop", "task-drop", "SKU-1", False)])
            replacement = build_queue(root, "replacement", [("new", "task-new", "SKU-2", True)])
            with self.assertRaisesRegex(RuntimeError, "same SKU"):
                module.build_effective_queue(
                    base, replacement, "drop", root / "queues", root,
                )


if __name__ == "__main__":
    unittest.main()
