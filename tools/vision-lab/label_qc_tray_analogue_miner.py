#!/usr/bin/env python3
"""Mine train-only human tray analogues for a protected regression miss.

Only queues absent from the supplied prior dataset may be scanned.  Protected
images stay regression-only; the target box is used solely as a geometry query.
"""
from __future__ import annotations

import argparse
import json
import math
import shutil
import sys
import tempfile
import time
from pathlib import Path
from typing import Any, Sequence

import numpy as np
from PIL import Image, ImageDraw, ImageOps

import label_qc_occlusion_scope_audit as occlusion
import label_qc_oracle_diagnostics as diagnostics


def box_features(box: Sequence[float]) -> dict[str, float]:
    width = float(box[2]) - float(box[0])
    height = float(box[3]) - float(box[1])
    area = width * height
    return {
        "width": width,
        "height": height,
        "area": area,
        "aspect": width / max(height, 1e-9),
        "center_x": (float(box[0]) + float(box[2])) / 2,
        "center_y": (float(box[1]) + float(box[3])) / 2,
        "horizontal_edge_gap": min(float(box[0]), 1.0 - float(box[2])),
    }


def geometry_distance(target: Sequence[float], candidate: Sequence[float]) -> float:
    left = box_features(target)
    right = box_features(candidate)
    return (
        2.0 * abs(math.log(max(right["area"], 1e-9) / max(left["area"], 1e-9)))
        + abs(math.log(max(right["aspect"], 1e-9) / max(left["aspect"], 1e-9)))
        + 2.0 * abs(right["center_y"] - left["center_y"])
        + 2.0 * abs(right["horizontal_edge_gap"] - left["horizontal_edge_gap"])
    )


def is_target_like(target: Sequence[float], candidate: Sequence[float]) -> bool:
    left = box_features(target)
    right = box_features(candidate)
    area_ratio = right["area"] / max(left["area"], 1e-9)
    aspect_ratio = right["aspect"] / max(left["aspect"], 1e-9)
    return (
        0.35 <= area_ratio <= 2.85
        and 0.50 <= aspect_ratio <= 2.0
        and right["center_y"] >= 0.45
        and right["horizontal_edge_gap"] <= 0.15
    )


def normalise_detection(detection: Any, width: int, height: int) -> list[float]:
    return occlusion.validate_box([
        float(detection.x0) / width,
        float(detection.y0) / height,
        float(detection.x1) / width,
        float(detection.y1) / height,
    ])


def selected_target(audit: dict[str, Any]) -> dict[str, Any]:
    rows = [
        row for row in audit.get("rows") or []
        if row.get("status") == "JUDGEABLE_HUMAN_TRAY_MATCH"
    ]
    if len(rows) != 1:
        raise RuntimeError(f"expected exactly one judgeable miss target, got {len(rows)}")
    row = rows[0]
    best = row.get("match", {}).get("best")
    if not isinstance(best, dict) or not best.get("box"):
        raise RuntimeError("judgeable miss target has no matched human box")
    return row | {"query_human_box": occlusion.validate_box(best["box"])}


def queue_is_unconsumed(queue: Path, prior_dataset: dict[str, Any]) -> bool:
    consumed = {
        str(Path(str(path)).resolve()).casefold()
        for path in prior_dataset.get("queues") or []
    }
    return str(queue.resolve()).casefold() not in consumed


def render_crop(
    image: Image.Image, box: Sequence[float], output: Path, context_ratio: float = 0.20,
) -> list[int]:
    width, height = image.size
    x0, y0, x1, y1 = (
        float(box[0]) * width,
        float(box[1]) * height,
        float(box[2]) * width,
        float(box[3]) * height,
    )
    pad_x = (x1 - x0) * context_ratio
    pad_y = (y1 - y0) * context_ratio
    rect = (
        max(0, math.floor(x0 - pad_x)),
        max(0, math.floor(y0 - pad_y)),
        min(width, math.ceil(x1 + pad_x)),
        min(height, math.ceil(y1 + pad_y)),
    )
    crop = image.crop(rect)
    local_box = [
        max(0, round(x0 - rect[0])),
        max(0, round(y0 - rect[1])),
        min(crop.width - 1, round(x1 - rect[0])),
        min(crop.height - 1, round(y1 - rect[1])),
    ]
    draw = ImageDraw.Draw(crop)
    stroke = max(3, round(min(crop.size) * 0.012))
    draw.rectangle(local_box, outline=(0, 255, 255), width=stroke)
    output.parent.mkdir(parents=True, exist_ok=True)
    crop.save(output, format="JPEG", quality=94, optimize=True)
    return local_box


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--queue", action="append", required=True, type=Path)
    parser.add_argument("--prior-dataset-manifest", required=True, type=Path)
    parser.add_argument("--protected-manifest", required=True, type=Path)
    parser.add_argument("--baseline-receipt", required=True, type=Path)
    parser.add_argument("--occlusion-audit", required=True, type=Path)
    parser.add_argument("--repo-root", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--tray-threshold", type=float, default=0.60)
    parser.add_argument("--minimum-holdout-phash-distance", type=int, default=11)
    parser.add_argument("--top", type=int, default=24)
    return parser


def main() -> None:
    args = build_parser().parse_args()
    output = args.output.resolve()
    if output.exists():
        raise FileExistsError(f"refusing to overwrite analogue mining output: {output}")
    for path in (
        args.prior_dataset_manifest, args.protected_manifest,
        args.baseline_receipt, args.occlusion_audit,
    ):
        if not path.is_file():
            raise FileNotFoundError(path)
    prior_dataset = json.loads(args.prior_dataset_manifest.read_text(encoding="utf-8"))
    protected = diagnostics.load_records(args.protected_manifest)
    protected_hashes = {row["image_sha256"] for row in protected}
    baseline = json.loads(args.baseline_receipt.read_text(encoding="utf-8"))
    audit = json.loads(args.occlusion_audit.read_text(encoding="utf-8"))
    target = selected_target(audit)
    target_box = target["query_human_box"]

    tray_model = Path(str(baseline["inputs"]["tray_model"]))
    label_model = Path(str(baseline["inputs"]["label_model"]))
    for key, path in (("tray_model", tray_model), ("label_model", label_model)):
        if not path.is_file():
            raise FileNotFoundError(path)
        if diagnostics.sha256_file(path) != str(baseline["inputs"][f"{key}_sha256"]):
            raise RuntimeError(f"{key} hash drift")

    candidates_by_photo: dict[str, dict[str, Any]] = {}
    queue_inputs: list[dict[str, Any]] = []
    seen_source_hashes: set[str] = set()
    for queue_source in args.queue:
        queue = queue_source.resolve()
        manifest_path = queue / "manifest.json"
        if not manifest_path.is_file():
            raise FileNotFoundError(manifest_path)
        if not queue_is_unconsumed(queue, prior_dataset):
            raise RuntimeError(f"queue already consumed by prior dataset: {queue}")
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
        if manifest.get("protected_holdout_included") is not False:
            raise RuntimeError(f"queue does not prove protected holdout exclusion: {queue}")
        queue_inputs.append({
            "queue": str(queue),
            "manifest": str(manifest_path),
            "manifest_sha256": diagnostics.sha256_file(manifest_path),
            "rows": len(manifest.get("rows") or []),
        })
        for queue_row in manifest.get("rows") or []:
            if queue_row.get("train_only") is not True:
                raise RuntimeError(f"queue row is not train-only: {queue_row.get('photo_id')}")
            source_hash = str(queue_row.get("source_sha256") or "")
            if source_hash in protected_hashes:
                raise RuntimeError(f"protected exact overlap: {queue_row.get('photo_id')}")
            distance = int(queue_row.get("nearest_holdout_phash", {}).get("distance", -1))
            if distance < args.minimum_holdout_phash_distance:
                raise RuntimeError(f"protected near overlap: {queue_row.get('photo_id')} distance={distance}")
            if source_hash in seen_source_hashes:
                continue
            seen_source_hashes.add(source_hash)
            annotation_path = queue / "annotations-human" / f"{queue_row['packed_stem']}.json"
            annotation = json.loads(annotation_path.read_text(encoding="utf-8"))
            if annotation.get("reviewed") is not True or annotation.get("source") != "human":
                raise RuntimeError(f"annotation is not reviewed human truth: {annotation_path}")
            human_boxes = [occlusion.validate_box(box) for box in annotation.get("boxes") or []]
            target_like = [
                {
                    "human_box_index": index,
                    "box": box,
                    "geometry_distance": round(geometry_distance(target_box, box), 6),
                    "features": box_features(box),
                }
                for index, box in enumerate(human_boxes)
                if is_target_like(target_box, box)
            ]
            if not target_like:
                continue
            photo_id = str(queue_row["photo_id"])
            if photo_id in candidates_by_photo:
                raise RuntimeError(f"duplicate photo id across selected queues: {photo_id}")
            candidates_by_photo[photo_id] = {
                "queue": str(queue),
                "queue_manifest": str(manifest_path),
                "photo_id": photo_id,
                "task_id": str(queue_row["task_id"]),
                "sku_code": str(queue_row.get("sku_code") or ""),
                "source_sha256": source_hash,
                "nearest_holdout_phash_distance": distance,
                "packed_image": str(queue / queue_row["packed_image"]),
                "packed_image_sha256": str(queue_row["packed_image_sha256"]),
                "annotation": str(annotation_path),
                "annotation_sha256": diagnostics.sha256_file(annotation_path),
                "human_box_count": len(human_boxes),
                "target_like_boxes": target_like,
            }

    sys.path.insert(0, str((args.repo_root.resolve() / "backend" / "python").resolve()))
    from label_qc.services import yolo_detector as yolo

    evaluated: list[dict[str, Any]] = []
    latencies: list[float] = []
    with tempfile.TemporaryDirectory(prefix="label-qc-analogue-models-") as temporary:
        model_dir = Path(temporary)
        shutil.copy2(tray_model, model_dir / "tray.onnx")
        shutil.copy2(label_model, model_dir / "label.onnx")
        models = yolo.LabelQcYoloModels(model_dir=model_dir)
        if not models.available:
            raise RuntimeError(models.load_error or "ONNX models unavailable")
        for row in candidates_by_photo.values():
            image_path = Path(row["packed_image"])
            if diagnostics.sha256_file(image_path) != row["packed_image_sha256"]:
                raise RuntimeError(f"packed image hash drift: {image_path}")
            with Image.open(image_path) as opened:
                image = ImageOps.exif_transpose(opened).convert("RGB")
                frame = np.array(image)
            started = time.perf_counter()
            detections = models.detect_trays(frame, args.tray_threshold)
            latencies.append((time.perf_counter() - started) * 1000)
            detection_boxes = [
                normalise_detection(detection, image.width, image.height)
                for detection in detections
            ]
            misses: list[dict[str, Any]] = []
            for candidate in row["target_like_boxes"]:
                match = occlusion.match_legacy_target(candidate["box"], detection_boxes)
                if not match["matched"]:
                    misses.append(candidate | {"production_match": match})
            if misses:
                evaluated.append(row | {
                    "production_detection_count": len(detections),
                    "target_like_miss_count": len(misses),
                    "target_like_misses": misses,
                })

    flattened = [
        row | miss
        for row in evaluated
        for miss in row["target_like_misses"]
    ]
    flattened.sort(key=lambda row: row["geometry_distance"])
    selected: list[dict[str, Any]] = []
    selected_tasks: set[str] = set()
    for row in flattened:
        if row["task_id"] in selected_tasks:
            continue
        selected.append(row)
        selected_tasks.add(row["task_id"])
        if len(selected) >= args.top:
            break

    output.mkdir(parents=True)
    visual_rows: list[dict[str, Any]] = []
    for index, row in enumerate(selected, 1):
        image_path = Path(row["packed_image"])
        with Image.open(image_path) as opened:
            image = ImageOps.exif_transpose(opened).convert("RGB")
        crop_path = output / "crops" / f"{index:03d}_{row['photo_id'][:8]}.jpg"
        rendered_box = render_crop(image, row["box"], crop_path)
        visual_rows.append({
            "photo_id": row["photo_id"],
            "task_id": row["task_id"],
            "human_box_index": row["human_box_index"],
            "crop": str(crop_path),
            "crop_sha256": diagnostics.sha256_file(crop_path),
            "rendered_box_xyxy": rendered_box,
        })

    payload = {
        "version": "label-qc-tray-analogue-miner-v1",
        "created_at": diagnostics.utc_now(),
        "purpose": "find unconsumed train-only analogues for one protected judgeable tray miss",
        "query": {
            "photo_id": target["photo_id"],
            "task_id": target.get("task_id"),
            "human_box": target_box,
            "features": box_features(target_box),
            "protected_target_used_for_training": False,
        },
        "inputs": {
            "queues": queue_inputs,
            "prior_dataset_manifest": str(args.prior_dataset_manifest.resolve()),
            "prior_dataset_manifest_sha256": diagnostics.sha256_file(args.prior_dataset_manifest),
            "protected_manifest": str(args.protected_manifest.resolve()),
            "protected_manifest_sha256": diagnostics.sha256_file(args.protected_manifest),
            "baseline_receipt": str(args.baseline_receipt.resolve()),
            "baseline_receipt_sha256": diagnostics.sha256_file(args.baseline_receipt),
            "occlusion_audit": str(args.occlusion_audit.resolve()),
            "occlusion_audit_sha256": diagnostics.sha256_file(args.occlusion_audit),
            "tray_model": str(tray_model),
            "tray_model_sha256": diagnostics.sha256_file(tray_model),
            "tray_threshold": args.tray_threshold,
        },
        "prefilter": {
            "unique_train_only_source_images": len(seen_source_hashes),
            "target_like_images_evaluated": len(candidates_by_photo),
        },
        "results": {
            "images_with_target_like_production_misses": len(evaluated),
            "target_like_miss_boxes": len(flattened),
            "selected_independent_tasks": len(selected_tasks),
            "selected": selected,
            "visuals": visual_rows,
            "p95_cpu_latency_ms": round(diagnostics.percentile(latencies, 0.95), 3),
        },
        "decision": {
            "minimum_independent_tasks": 2,
            "development_data_signal": len(selected_tasks) >= 2,
            "visual_scope_review_required": True,
            "training_allowed": False,
            "promotion_allowed": False,
            "gpu_rental_required": False,
            "next_gate": (
                "review candidate crops and exclude unjudgeable lower-layer tray boxes"
                if len(selected_tasks) >= 2
                else "collect more judgeable train-only tray misses"
            ),
        },
        "production_writes": 0,
        "originals_modified": 0,
    }
    receipt = output / "receipt.json"
    receipt.write_text(
        json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8",
    )
    print(json.dumps({
        "receipt": str(receipt),
        "prefilter": payload["prefilter"],
        "results": {
            key: payload["results"][key]
            for key in (
                "images_with_target_like_production_misses",
                "target_like_miss_boxes",
                "selected_independent_tasks",
                "p95_cpu_latency_ms",
            )
        },
        "decision": payload["decision"],
        "production_writes": 0,
        "originals_modified": 0,
    }, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
