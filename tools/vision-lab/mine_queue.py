#!/usr/bin/env python3
"""Mine human-confirmed normal trays that the production YOLO still flags."""
from __future__ import annotations

import argparse
import datetime as dt
import hashlib
import json
import shutil
import sqlite3
import sys
import tempfile
from collections import Counter
from pathlib import Path
from typing import Any

import imagehash
import numpy as np
from PIL import Image, ImageOps


DEFECTS = {"MISSING_WHITE_LABEL", "MISSING_COLOR_LABEL", "BOTH_MISSING"}


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def existing_photo_ids(paths: list[Path]) -> set[str]:
    ids: set[str] = set()
    for path in paths:
        if not path.is_file():
            continue
        payload = json.loads(path.read_text(encoding="utf-8"))
        for row in payload.get("rows", payload.get("records", [])):
            value = row.get("source_photo_id") or row.get("photo_id")
            if value:
                ids.add(str(value))
    return ids


def normal_truth(raw: str) -> bool:
    annotations = json.loads(raw)
    labels = {str(item.get("human_label") or "").upper() for item in annotations}
    return "NO_DEFECT" in labels and not (labels & DEFECTS)


def box_to_crop(box: list[float], crop_rect: tuple[float, float, float, float]) -> list[float]:
    x0, y0, x1, y1 = box
    cx0, cy0, cx1, cy1 = crop_rect
    width, height = max(1.0, cx1 - cx0), max(1.0, cy1 - cy0)
    return [
        round(max(0.0, min(1.0, (x0 - cx0) / width)), 6),
        round(max(0.0, min(1.0, (y0 - cy0) / height)), 6),
        round(max(0.0, min(1.0, (x1 - cx0) / width)), 6),
        round(max(0.0, min(1.0, (y1 - cy0) / height)), 6),
    ]


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--repo-root", required=True, type=Path)
    parser.add_argument("--database", required=True, type=Path)
    parser.add_argument("--tray", required=True, type=Path)
    parser.add_argument("--label", required=True, type=Path)
    parser.add_argument("--protected-holdout", required=True, type=Path)
    parser.add_argument("--existing-manifest", action="append", default=[], type=Path)
    parser.add_argument("--output-root", required=True, type=Path)
    parser.add_argument("--max-queue", type=int, default=120)
    parser.add_argument("--max-photos-scanned", type=int, default=300)
    parser.add_argument("--max-per-sku", type=int, default=30)
    parser.add_argument("--max-per-photo", type=int, default=2)
    args = parser.parse_args()

    sys.path.insert(0, str((args.repo_root / "backend" / "python").resolve()))
    from label_qc.services.screening import ScreeningParams, screen_image
    from label_qc.services.yolo_detector import LabelQcYoloModels, crop_with_padding

    holdout = json.loads(args.protected_holdout.read_text(encoding="utf-8"))
    excluded = {str(row["photo_id"]) for row in holdout["records"]}
    excluded |= existing_photo_ids(args.existing_manifest)
    connection = sqlite3.connect(args.database)
    connection.row_factory = sqlite3.Row
    try:
        candidates = [
            dict(row) for row in connection.execute(
                "SELECT * FROM photos ORDER BY reviewed_at DESC, photo_id DESC"
            )
            if str(row["photo_id"]) not in excluded and normal_truth(row["annotations_json"])
        ][: args.max_photos_scanned]
    finally:
        connection.close()

    with tempfile.TemporaryDirectory(prefix="visionlab-mine-") as temporary:
        model_dir = Path(temporary)
        shutil.copy2(args.tray, model_dir / "tray.onnx")
        shutil.copy2(args.label, model_dir / "label.onnx")
        models = LabelQcYoloModels(model_dir=model_dir)
        if not models.available:
            raise RuntimeError(models.load_error or "production models unavailable")
        params = ScreeningParams(tray_conf=0.60, label_conf=0.20)
        selected: list[dict[str, Any]] = []
        hashes: list[imagehash.ImageHash] = []
        per_sku: Counter[str] = Counter()
        for record in candidates:
            if len(selected) >= args.max_queue:
                break
            image_path = Path(record["local_path"])
            with Image.open(image_path) as opened:
                frame_pil = ImageOps.exif_transpose(opened).convert("RGB")
            frame = np.array(frame_pil)
            screening = screen_image(frame, models, params)
            photo_count = 0
            for tray in sorted(screening.suspects, key=lambda item: -item.confidence):
                sku = str(record.get("sku_code") or "UNKNOWN")
                if per_sku[sku] >= args.max_per_sku or photo_count >= args.max_per_photo:
                    continue
                crop_array, crop_rect = crop_with_padding(frame, tray.box, params.pad_ratio)
                if crop_array.shape[0] < params.min_crop_px or crop_array.shape[1] < params.min_crop_px:
                    continue
                crop = Image.fromarray(crop_array)
                phash = imagehash.phash(crop, hash_size=16)
                if any(phash - prior <= 4 for prior in hashes):
                    continue
                hashes.append(phash)
                selected.append({
                    "record": record, "tray": tray, "crop": crop, "crop_rect": crop_rect,
                    "phash": str(phash), "sku": sku,
                })
                per_sku[sku] += 1
                photo_count += 1
                if len(selected) >= args.max_queue:
                    break

    if not selected:
        print(json.dumps({"created": False, "photos_scanned": len(candidates), "queued": 0}, ensure_ascii=False))
        return
    stamp = dt.datetime.now(dt.timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    output = args.output_root / f"label-active-{stamp}"
    for name in ("images", "prelabels", "annotations-human"):
        (output / name).mkdir(parents=True)
    rows = []
    for index, item in enumerate(selected, start=1):
        record, tray, crop_rect = item["record"], item["tray"], item["crop_rect"]
        crop_id = f"al_{str(record['photo_id'])[:8]}_t{tray.index:02d}_{index:03d}"
        image_path = output / "images" / f"{crop_id}.jpg"
        item["crop"].save(image_path, quality=95)
        boxes = []
        for label in tray.labels:
            normalized = box_to_crop(label.box, crop_rect)
            if normalized[2] > normalized[0] and normalized[3] > normalized[1]:
                boxes.append({"class_id": 0 if label.is_white else 1, "bbox_normalized_xyxy": normalized,
                              "confidence": round(label.confidence, 6)})
        prelabel = output / "prelabels" / f"{crop_id}.json"
        prelabel.write_text(json.dumps({
            "crop_id": crop_id, "source": "production_yolo_requires_full_human_review",
            "is_ground_truth": False, "boxes": boxes,
        }, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        rows.append({
            "crop_id": crop_id, "image": str(image_path.resolve()),
            "image_sha256": sha256_file(image_path), "perceptual_hash": item["phash"],
            "source_photo_id": str(record["photo_id"]), "source_task_id": str(record["task_id"]),
            "sku_code": item["sku"], "angle": "unclassified",
            "label_v1_verdict": tray.verdict, "human_qc_verdict": "NO_DEFECT",
            "prelabel_boxes": len(boxes), "train_only": True,
        })
    manifest = {
        "version": f"liushanmen-active-learning-{stamp}",
        "created_at_utc": dt.datetime.now(dt.timezone.utc).isoformat(),
        "production_reads": 0, "production_writes": 0, "originals_modified": 0,
        "preannotations_are_not_ground_truth": True, "every_image_requires_full_human_review": True,
        "train_only": True, "evaluation_or_production_use_allowed": False,
        "photos_scanned": len(candidates), "queue_count": len(rows),
        "sku_counts": dict(per_sku), "rows": rows,
    }
    (output / "manifest.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"created": True, "queue": str(output), "photos_scanned": len(candidates), "queued": len(rows)}, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
