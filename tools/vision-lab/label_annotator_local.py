#!/usr/bin/env python3
"""Serve a label-only review queue with the modern missing-class workflow."""
from __future__ import annotations

import argparse
import importlib.util
import json
import os
from pathlib import Path
from types import ModuleType


DEFAULT_ANNOTATOR = Path(
    r"D:\Temp\cretas-liushanmen-qc-synthetic-v2-20260728"
    r"\targeted-v3-20260803\label_hard_negative_annotator.py"
)


def validate_label_queue(queue: Path) -> dict:
    manifest_path = queue / "manifest.json"
    if not manifest_path.is_file():
        raise FileNotFoundError(manifest_path)
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    rows = manifest.get("rows") if isinstance(manifest, dict) else None
    if not isinstance(rows, list) or not rows:
        raise RuntimeError("label queue manifest must contain non-empty rows")
    if all(isinstance(row, dict) and row.get("packed_image") for row in rows):
        raise RuntimeError(
            "tray queue detected; use tools/vision-lab/tray_annotator_local.py"
        )
    expected = manifest.get("queue_count", len(rows))
    if expected != len(rows) or len({row.get("crop_id") for row in rows if isinstance(row, dict)}) != len(rows):
        raise RuntimeError("label queue manifest count or crop ids are invalid")
    if any(
        not isinstance(row, dict)
        or not row.get("crop_id")
        or not row.get("image")
        or row.get("packed_image")
        for row in rows
    ):
        raise RuntimeError("unsupported label queue manifest")
    if manifest.get("preannotations_are_not_ground_truth") is not True:
        raise RuntimeError("label queue must declare preannotations as non-ground-truth")
    if manifest.get("every_image_requires_full_human_review") is not True:
        raise RuntimeError("label queue must require full human review")
    return manifest


def load_module(path: Path) -> ModuleType:
    spec = importlib.util.spec_from_file_location("liushanmen_label_annotator_modern", path)
    if spec is None or spec.loader is None:
        raise RuntimeError(f"cannot load label annotator: {path}")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def validate_modern_annotator(module: ModuleType, script: Path) -> None:
    if "legacy" in script.name.lower():
        raise RuntimeError("legacy label annotator is forbidden")
    if getattr(module, "ALLOW_MISSING_CLASS", False) is not True:
        raise RuntimeError("label annotator did not enable explicit missing-class review")
    page = getattr(module, "PAGE", "")
    required = ("missing_confirmed", "确认这是真的被拔掉了")
    if not isinstance(page, str) or any(marker not in page for marker in required):
        raise RuntimeError("label annotator lacks the audited missing-class confirmation flow")
    if "new Set(classes).size<2" in page:
        raise RuntimeError("legacy two-class hard block detected")
    if getattr(module, "WIREGUARD_HOST", None) != "":
        raise RuntimeError("local label annotator must be loopback-only")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--queue", required=True, type=Path)
    parser.add_argument("--port", type=int, default=8772)
    parser.add_argument("--annotator-script", type=Path, default=DEFAULT_ANNOTATOR)
    args = parser.parse_args()

    queue = args.queue.resolve()
    validate_label_queue(queue)
    script = args.annotator_script.resolve()
    if not script.is_file():
        raise FileNotFoundError(script)
    if "legacy" in script.name.lower():
        raise RuntimeError("legacy label annotator is forbidden")

    os.environ["CRETAS_LABEL_ANNOTATION_ROOT"] = str(queue)
    os.environ["CRETAS_LABEL_ANNOTATION_OUTPUT"] = str(queue / "annotations-human")
    os.environ["CRETAS_LABEL_ANNOTATION_PORT"] = str(args.port)
    os.environ["CRETAS_LABEL_ANNOTATION_WIREGUARD_HOST"] = ""
    os.environ["CRETAS_LABEL_ANNOTATION_ALLOW_MISSING_CLASS"] = "1"
    module = load_module(script)
    validate_modern_annotator(module, script)
    print(f"label annotator: http://127.0.0.1:{args.port}")
    print(f"queue: {queue}", flush=True)
    module.main()


if __name__ == "__main__":
    main()
