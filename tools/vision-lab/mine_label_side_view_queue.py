#!/usr/bin/env python3
"""Plan and build a fresh human-review queue for side-view white-label misses."""
from __future__ import annotations

import argparse
import datetime as dt
import hashlib
import json
import math
import os
import shutil
import sqlite3
import sys
import tempfile
from collections import Counter
from pathlib import Path
from typing import Any, Iterable, Sequence

import imagehash
import numpy as np
from PIL import Image, ImageDraw, ImageFont, ImageOps


PROTECTED_PHASH_DISTANCE = 10
QUEUE_PHASH_DISTANCE = 4
WHITE_MISSING_VERDICTS = {"MISSING_WHITE_LABEL", "BOTH_MISSING"}
CLASS_WHITE_LABEL = 0
SIDE_VIEW_MISSING = "side-view-missing"
WHITE_CONFUSER_DISAGREEMENT = "white-confuser-disagreement"


def load_json(path: Path) -> dict[str, Any]:
    value = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(value, dict):
        raise ValueError(f"expected JSON object: {path}")
    return value


def write_json(path: Path, value: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def stable_digest(value: Any) -> str:
    encoded = json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode()
    return hashlib.sha256(encoded).hexdigest()


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def image_phash(image: Image.Image) -> str:
    return str(imagehash.phash(ImageOps.exif_transpose(image), hash_size=16))


def phash_distance(left: str, right: str) -> int:
    return imagehash.hex_to_hash(left) - imagehash.hex_to_hash(right)


def normal_truth(raw: str) -> bool:
    annotations = json.loads(raw)
    labels = {str(item.get("human_label") or "").upper() for item in annotations}
    defects = {"MISSING_WHITE_LABEL", "MISSING_COLOR_LABEL", "BOTH_MISSING"}
    return "NO_DEFECT" in labels and not labels.intersection(defects)


def box_area(box: Sequence[float]) -> float:
    return max(0.0, float(box[2]) - float(box[0])) * max(0.0, float(box[3]) - float(box[1]))


def overlap_fraction(left: Sequence[float], right: Sequence[float]) -> float:
    intersection = max(0.0, min(left[2], right[2]) - max(left[0], right[0])) * max(
        0.0, min(left[3], right[3]) - max(left[1], right[1])
    )
    return intersection / max(1e-9, min(box_area(left), box_area(right)))


def intersection_over_union(left: Sequence[float], right: Sequence[float]) -> float:
    intersection = max(0.0, min(left[2], right[2]) - max(left[0], right[0])) * max(
        0.0, min(left[3], right[3]) - max(left[1], right[1])
    )
    return intersection / max(1e-9, box_area(left) + box_area(right) - intersection)


def match_tray(target: Any, trays: Sequence[Any], minimum_iou: float) -> tuple[Any | None, float]:
    matches = [(tray, intersection_over_union(target.box, tray.box)) for tray in trays]
    if not matches:
        return None, 0.0
    tray, score = max(matches, key=lambda item: item[1])
    return (tray, score) if score >= minimum_iou else (None, score)


def white_confuser_features(
    production_tray: Any,
    candidate_tray: Any,
    minimum_candidate_confidence: float,
    minimum_confidence_delta: float,
    minimum_label_iou: float,
) -> dict[str, Any] | None:
    production_white = [label for label in production_tray.labels if label.class_id == CLASS_WHITE_LABEL]
    candidate_white = [label for label in candidate_tray.labels if label.class_id == CLASS_WHITE_LABEL]
    ranked: list[tuple[float, float, Any, float]] = []
    for candidate_label in candidate_white:
        matches = [
            (label, intersection_over_union(candidate_label.box, label.box))
            for label in production_white
        ]
        matched_label, matched_iou = max(matches, key=lambda item: item[1], default=(None, 0.0))
        production_confidence = (
            float(matched_label.confidence) if matched_label is not None and matched_iou >= minimum_label_iou else 0.0
        )
        delta = float(candidate_label.confidence) - production_confidence
        ranked.append((delta, float(candidate_label.confidence), candidate_label, matched_iou))
    if not ranked:
        return None
    delta, candidate_confidence, label, matched_iou = max(ranked, key=lambda item: (item[0], item[1]))
    if candidate_confidence < minimum_candidate_confidence or delta < minimum_confidence_delta:
        return None
    production_confidence = candidate_confidence - delta
    is_new = production_confidence == 0.0
    return {
        "tags": ["candidate_white_new" if is_new else "candidate_white_amplified"],
        "score": round(delta * 10.0 + candidate_confidence, 6),
        "candidate_white_confidence": round(candidate_confidence, 6),
        "production_white_confidence": round(production_confidence, 6),
        "white_confidence_delta": round(delta, 6),
        "matched_white_iou": round(matched_iou, 6),
        "candidate_white_box": [round(float(value), 6) for value in label.box],
    }


def side_risk_features(tray: Any, trays: Sequence[Any], image_width: int, image_height: int) -> dict[str, Any]:
    width = max(1.0, tray.box[2] - tray.box[0])
    height = max(1.0, tray.box[3] - tray.box[1])
    aspect = width / height
    maximum_overlap = max(
        (overlap_fraction(tray.box, other.box) for other in trays if other.index != tray.index),
        default=0.0,
    )
    area_fraction = box_area(tray.box) / max(1.0, float(image_width * image_height))
    tags: list[str] = []
    score = 0.0
    if aspect >= 1.9:
        tags.append("wide_side_view")
        score += 3.0
    elif aspect >= 1.6:
        tags.append("wide_oblique")
        score += 1.5
    if maximum_overlap >= 0.08:
        tags.append("stacked_overlap")
        score += min(4.0, 2.0 + maximum_overlap * 4.0)
    if int(getattr(tray, "dropped_neighbour_labels", 0)) > 0:
        tags.append("neighbour_occlusion")
        score += min(3.0, 1.0 + int(tray.dropped_neighbour_labels) * 0.5)
    if area_fraction <= 0.03:
        tags.append("distant_tilt_risk")
        score += 1.0
    return {
        "tags": tags,
        "score": round(score, 6),
        "tray_aspect_ratio": round(aspect, 6),
        "maximum_tray_overlap": round(maximum_overlap, 6),
        "tray_area_fraction": round(area_fraction, 6),
    }


def box_to_crop(box: Sequence[float], crop_rect: Sequence[float]) -> list[float]:
    x0, y0, x1, y1 = map(float, box)
    cx0, cy0, cx1, cy1 = map(float, crop_rect)
    width, height = max(1.0, cx1 - cx0), max(1.0, cy1 - cy0)
    return [
        round(max(0.0, min(1.0, (x0 - cx0) / width)), 6),
        round(max(0.0, min(1.0, (y0 - cy0) / height)), 6),
        round(max(0.0, min(1.0, (x1 - cx0) / width)), 6),
        round(max(0.0, min(1.0, (y1 - cy0) / height)), 6),
    ]


def manifest_source_ids(paths: Iterable[Path]) -> set[str]:
    result: set[str] = set()
    for path in paths:
        if not path.is_file():
            continue
        payload = load_json(path)
        for row in payload.get("rows", payload.get("records", [])):
            value = row.get("source_photo_id") or row.get("photo_id")
            if value:
                result.add(str(value))
    return result


def protected_records(path: Path) -> tuple[set[str], set[str], list[str]]:
    payload = load_json(path)
    records = payload.get("records") or []
    if payload.get("train_use_allowed") is not False or len(records) != 27:
        raise RuntimeError("protected 7+20 holdout contract drift")
    ids, hashes, perceptual = set(), set(), []
    for row in records:
        image = Path(row["image"])
        expected = str(row["image_sha256"]).lower()
        if not image.is_file() or sha256_file(image) != expected:
            raise RuntimeError(f"protected holdout image drift: {image}")
        with Image.open(image) as opened:
            perceptual.append(image_phash(opened))
        ids.add(str(row["photo_id"]))
        hashes.add(expected)
    return ids, hashes, perceptual


def load_candidate_records(database: Path, excluded_ids: set[str], limit: int) -> list[dict[str, Any]]:
    connection = sqlite3.connect(f"file:{database.resolve()}?mode=ro", uri=True)
    connection.row_factory = sqlite3.Row
    try:
        rows = []
        for row in connection.execute("SELECT * FROM photos ORDER BY reviewed_at DESC, photo_id DESC"):
            if str(row["photo_id"]) in excluded_ids or not normal_truth(row["annotations_json"]):
                continue
            rows.append(dict(row))
            if len(rows) >= limit:
                break
        return rows
    finally:
        connection.close()


def existing_source_fingerprints(database: Path, source_ids: set[str]) -> tuple[set[str], list[str]]:
    if not source_ids:
        return set(), []
    connection = sqlite3.connect(f"file:{database.resolve()}?mode=ro", uri=True)
    connection.row_factory = sqlite3.Row
    hashes: set[str] = set()
    perceptual: list[str] = []
    try:
        for row in connection.execute("SELECT photo_id, sha256, local_path FROM photos"):
            if str(row["photo_id"]) not in source_ids:
                continue
            path = Path(row["local_path"])
            expected = str(row["sha256"]).lower()
            if not path.is_file() or sha256_file(path) != expected:
                raise RuntimeError(f"existing label source drift: {path}")
            with Image.open(path) as opened:
                perceptual.append(image_phash(opened))
            hashes.add(expected)
        return hashes, perceptual
    finally:
        connection.close()


def plan_identity(rows: Sequence[dict[str, Any]]) -> list[dict[str, Any]]:
    return [
        {
            "source_photo_id": row["source_photo_id"],
            "source_sha256": row["source_sha256"],
            "tray_index": row["tray_index"],
            "crop_rect": row["crop_rect"],
            "crop_sha256": row["crop_sha256"],
            "crop_perceptual_hash": row["crop_perceptual_hash"],
            "prelabel_boxes": row["prelabel_boxes"],
        }
        for row in rows
    ]


def scan_plan(args: argparse.Namespace) -> dict[str, Any]:
    if sha256_file(args.tray).lower() != args.tray_sha256.lower():
        raise RuntimeError("production tray model hash drift")
    if sha256_file(args.label).lower() != args.label_sha256.lower():
        raise RuntimeError("production label model hash drift")
    if args.selection_mode == WHITE_CONFUSER_DISAGREEMENT:
        if args.candidate_label is None or not args.candidate_label_sha256:
            raise RuntimeError("white-confuser disagreement requires a hash-bound candidate label")
        if sha256_file(args.candidate_label).lower() != args.candidate_label_sha256.lower():
            raise RuntimeError("candidate label model hash drift")
    holdout_ids, holdout_hashes, holdout_phashes = protected_records(args.protected_holdout)
    existing_ids = manifest_source_ids(args.existing_manifest)
    existing_hashes, existing_phashes = existing_source_fingerprints(args.database, existing_ids)
    excluded_ids = set(holdout_ids)
    excluded_ids.update(existing_ids)
    candidates = load_candidate_records(args.database, excluded_ids, args.max_photos_scanned)

    sys.path.insert(0, str((args.repo_root / "backend" / "python").resolve()))
    from label_qc.services.screening import ScreeningParams, screen_image
    from label_qc.services.yolo_detector import LabelQcYoloModels, crop_with_padding

    exclusions = Counter()
    selected: list[dict[str, Any]] = []
    selected_hashes: list[str] = []
    per_sku: Counter[str] = Counter()
    with tempfile.TemporaryDirectory(prefix="visionlab-side-view-plan-") as temporary:
        model_dir = Path(temporary)
        shutil.copy2(args.tray, model_dir / "tray.onnx")
        shutil.copy2(args.label, model_dir / "label.onnx")
        models = LabelQcYoloModels(model_dir=model_dir)
        if not models.available:
            raise RuntimeError(models.load_error or "production models unavailable")
        candidate_models = None
        if args.selection_mode == WHITE_CONFUSER_DISAGREEMENT:
            candidate_dir = model_dir / "candidate"
            candidate_dir.mkdir()
            shutil.copy2(args.tray, candidate_dir / "tray.onnx")
            shutil.copy2(args.candidate_label, candidate_dir / "label.onnx")
            candidate_models = LabelQcYoloModels(model_dir=candidate_dir)
            if not candidate_models.available:
                raise RuntimeError(candidate_models.load_error or "candidate label model unavailable")
        params = ScreeningParams(tray_conf=args.tray_threshold, label_conf=args.label_threshold)
        for photo_number, record in enumerate(candidates, start=1):
            if photo_number == 1 or photo_number % 10 == 0:
                print(json.dumps({
                    "stage": "label-side-view-preflight",
                    "photos_processed": photo_number - 1,
                    "photos_total": len(candidates),
                    "eligible_before_final_cap": len(selected),
                }), flush=True)
            source = Path(record["local_path"])
            expected_sha = str(record["sha256"]).lower()
            if not source.is_file() or sha256_file(source) != expected_sha:
                raise RuntimeError(f"VisionLab source image drift: {source}")
            if expected_sha in holdout_hashes:
                exclusions["protected_exact_sha"] += 1
                continue
            if expected_sha in existing_hashes:
                exclusions["existing_label_exact_sha"] += 1
                continue
            with Image.open(source) as opened:
                source_image = ImageOps.exif_transpose(opened).convert("RGB")
                source_phash = image_phash(source_image)
            nearest_holdout = min(phash_distance(source_phash, value) for value in holdout_phashes)
            if nearest_holdout <= PROTECTED_PHASH_DISTANCE:
                exclusions["protected_near_phash"] += 1
                continue
            nearest_existing = min(
                (phash_distance(source_phash, value) for value in existing_phashes), default=math.inf
            )
            if nearest_existing <= PROTECTED_PHASH_DISTANCE:
                exclusions["existing_label_near_phash"] += 1
                continue
            frame = np.array(source_image)
            screening = screen_image(frame, models, params)
            ranked_trays: list[tuple[Any, list[str], float, dict[str, Any]]] = []
            if args.selection_mode == SIDE_VIEW_MISSING:
                for tray in screening.suspects:
                    if tray.verdict not in WHITE_MISSING_VERDICTS:
                        continue
                    features = side_risk_features(tray, screening.trays, screening.image_width, screening.image_height)
                    if not features["tags"]:
                        exclusions["not_side_risk"] += 1
                        continue
                    ranked_trays.append((tray, features["tags"], features["score"], features))
            else:
                assert candidate_models is not None
                candidate_screening = screen_image(frame, candidate_models, params)
                for candidate_tray in candidate_screening.trays:
                    production_tray, tray_iou = match_tray(
                        candidate_tray, screening.trays, args.minimum_tray_iou
                    )
                    if production_tray is None:
                        exclusions["candidate_tray_unmatched"] += 1
                        continue
                    features = white_confuser_features(
                        production_tray,
                        candidate_tray,
                        args.minimum_candidate_white_confidence,
                        args.minimum_white_confidence_delta,
                        args.minimum_label_iou,
                    )
                    if features is None:
                        exclusions["white_disagreement_below_floor"] += 1
                        continue
                    features["tray_match_iou"] = round(tray_iou, 6)
                    features["production_verdict"] = production_tray.verdict
                    features["candidate_verdict"] = candidate_tray.verdict
                    ranked_trays.append((
                        candidate_tray, features["tags"], features["score"], features
                    ))
            photo_count = 0
            for tray, selection_tags, selection_score, features in ranked_trays:
                sku = str(record.get("sku_code") or "UNKNOWN")
                if per_sku[sku] >= args.max_per_sku or photo_count >= args.max_per_photo:
                    continue
                crop_array, crop_rect = crop_with_padding(frame, tray.box, params.pad_ratio)
                if crop_array.shape[0] < params.min_crop_px or crop_array.shape[1] < params.min_crop_px:
                    exclusions["crop_too_small"] += 1
                    continue
                crop = Image.fromarray(crop_array)
                crop_phash = image_phash(crop)
                if any(phash_distance(crop_phash, prior) <= QUEUE_PHASH_DISTANCE for prior in selected_hashes):
                    exclusions["queue_near_duplicate"] += 1
                    continue
                selected_hashes.append(crop_phash)
                prelabels = []
                for label in tray.labels:
                    normalized = box_to_crop(label.box, crop_rect)
                    if normalized[2] <= normalized[0] or normalized[3] <= normalized[1]:
                        continue
                    prelabels.append({
                        "class_id": int(label.class_id),
                        "bbox_normalized_xyxy": normalized,
                        "confidence": round(float(label.confidence), 6),
                    })
                selected.append({
                    "source_photo_id": str(record["photo_id"]),
                    "source_task_id": str(record["task_id"]),
                    "source_path": str(source),
                    "source_sha256": expected_sha,
                    "source_perceptual_hash": source_phash,
                    "nearest_holdout_phash_distance": nearest_holdout,
                    "nearest_existing_label_phash_distance": nearest_existing,
                    "sku_code": sku,
                    "tray_index": int(tray.index),
                    "tray_box": [round(float(value), 6) for value in tray.box],
                    "tray_confidence": round(float(tray.confidence), 6),
                    "label_v1_verdict": tray.verdict,
                    "has_white": bool(tray.has_white),
                    "has_color": bool(tray.has_color),
                    "crop_rect": [round(float(value), 6) for value in crop_rect],
                    "crop_sha256": hashlib.sha256(crop.tobytes()).hexdigest(),
                    "crop_perceptual_hash": crop_phash,
                    "crop_size": [crop.width, crop.height],
                    "selection_mode": args.selection_mode,
                    "selection_tags": selection_tags,
                    "selection_score": selection_score,
                    "selection_evidence": features,
                    "dropped_neighbour_labels": int(tray.dropped_neighbour_labels),
                    "prelabel_boxes": prelabels,
                    "human_source_truth": "NO_DEFECT",
                    "requires_full_human_review": True,
                })
                per_sku[sku] += 1
                photo_count += 1

    selected.sort(key=lambda row: (-row["selection_score"], row["source_photo_id"], row["tray_index"]))
    selected = selected[:args.max_queue]
    per_sku = Counter(row["sku_code"] for row in selected)
    selected_tasks = {row["source_task_id"] for row in selected}
    selected_photos = {row["source_photo_id"] for row in selected}
    identity = plan_identity(selected)
    receipt = {
        "version": "vision-lab-label-side-view-preflight-v1",
        "created_at": dt.datetime.now(dt.timezone.utc).isoformat(),
        "status": "ready" if selected else "empty",
        "production_tray": str(args.tray),
        "production_tray_sha256": args.tray_sha256.lower(),
        "production_label": str(args.label),
        "production_label_sha256": args.label_sha256.lower(),
        "selection_mode": args.selection_mode,
        "ranking_candidate_label": str(args.candidate_label) if args.candidate_label else None,
        "ranking_candidate_label_sha256": args.candidate_label_sha256.lower() if args.candidate_label_sha256 else None,
        "ranking_candidate_is_ground_truth": False,
        "database": str(args.database),
        "protected_holdout": str(args.protected_holdout),
        "existing_manifests": [str(path) for path in args.existing_manifest],
        "excluded_source_ids": len(excluded_ids),
        "photos_scanned": len(candidates),
        "queue_count": len(selected),
        "candidate_photo_count": len(selected_photos),
        "candidate_task_count": len(selected_tasks),
        "sku_counts": dict(per_sku),
        "exclusions": dict(exclusions),
        "candidate_digest": stable_digest(identity),
        "rows": selected,
        "protected_holdout_included": False,
        "existing_label_sources_included": False,
        "old_29_included": False,
        "preannotations_are_not_ground_truth": True,
        "cloud_calls": 0,
        "production_reads": 0,
        "production_writes": 0,
        "originals_modified": False,
        "training_started": False,
        "deployment": False,
        "queue_created": False,
    }
    write_json(args.output, receipt)
    return receipt


def crop_from_plan(row: dict[str, Any]) -> Image.Image:
    source = Path(row["source_path"])
    if not source.is_file() or sha256_file(source) != row["source_sha256"]:
        raise RuntimeError(f"planned source image drift: {source}")
    with Image.open(source) as opened:
        image = ImageOps.exif_transpose(opened).convert("RGB")
    expected_width, expected_height = (int(value) for value in row["crop_size"])
    coordinate_options = [
        sorted({int(math.floor(float(value))) - 1, int(math.floor(float(value))), int(math.floor(float(value))) + 1})
        for value in row["crop_rect"]
    ]
    crop = None
    for x0 in coordinate_options[0]:
        for y0 in coordinate_options[1]:
            for x1 in coordinate_options[2]:
                for y1 in coordinate_options[3]:
                    if x1 - x0 != expected_width or y1 - y0 != expected_height:
                        continue
                    candidate = image.crop((x0, y0, x1, y1))
                    if hashlib.sha256(candidate.tobytes()).hexdigest() == row["crop_sha256"]:
                        crop = candidate
                        break
                if crop is not None:
                    break
            if crop is not None:
                break
        if crop is not None:
            break
    if crop is None:
        raise RuntimeError(f"planned crop content drift: {row['source_photo_id']}:{row['tray_index']}")
    if image_phash(crop) != row["crop_perceptual_hash"]:
        raise RuntimeError(f"planned crop perceptual hash drift: {row['source_photo_id']}:{row['tray_index']}")
    return crop


def make_contact_sheets(rows: list[dict[str, Any]], image_root: Path, output: Path) -> list[str]:
    output.mkdir(parents=True, exist_ok=False)
    files: list[str] = []
    for page_start in range(0, len(rows), 12):
        page_rows = rows[page_start:page_start + 12]
        canvas = Image.new("RGB", (1600, 1200), "#f4f4f2")
        draw = ImageDraw.Draw(canvas)
        font = ImageFont.load_default()
        for offset, row in enumerate(page_rows):
            column, line = offset % 4, offset // 4
            x, y = column * 400, line * 400
            with Image.open(image_root / f"{row['crop_id']}.jpg") as opened:
                preview = ImageOps.contain(opened.convert("RGB"), (380, 335))
            canvas.paste(preview, (x + 10, y + 10))
            draw.text((x + 10, y + 350), f"{row['queue_index']:02d} {row['crop_id']} {','.join(row['selection_tags'])}", fill="black", font=font)
        path = output / f"label-side-view-{page_start // 12 + 1:02d}.jpg"
        canvas.save(path, quality=92)
        files.append(str(path))
    return files


def build_queue(args: argparse.Namespace) -> dict[str, Any]:
    plan = load_json(args.plan)
    rows = plan.get("rows") or []
    if plan.get("version") != "vision-lab-label-side-view-preflight-v1" or plan.get("status") != "ready":
        raise RuntimeError("label-side-view preflight is not ready")
    if stable_digest(plan_identity(rows)) != plan.get("candidate_digest"):
        raise RuntimeError("label-side-view preflight candidate digest drift")
    if plan.get("protected_holdout_included") or plan.get("existing_label_sources_included"):
        raise RuntimeError("label-side-view preflight protection flags are unsafe")
    selection_mode = plan.get("selection_mode") or SIDE_VIEW_MISSING
    if selection_mode not in {SIDE_VIEW_MISSING, WHITE_CONFUSER_DISAGREEMENT}:
        raise RuntimeError(f"unsupported label queue selection mode: {selection_mode}")
    stamp = dt.datetime.fromisoformat(plan["created_at"]).strftime("%Y%m%dT%H%M%SZ")
    queue = args.output_root / f"label-side-view-active-{stamp}"
    if queue.exists():
        raise RuntimeError(f"refusing to overwrite label-side-view queue: {queue}")
    mark = args.attention_root / "MARK-NEEDS-LABEL-SIDE-VIEW-ANNOTATION.json"
    if mark.exists():
        prior = load_json(mark)
        if prior.get("status") == "NEEDS_ANNOTATION":
            raise RuntimeError(f"existing label-side-view MARK still active: {mark}")
    temporary = queue.with_name(queue.name + f".tmp.{os.getpid()}")
    for name in ("images", "prelabels", "annotations-human"):
        (temporary / name).mkdir(parents=True, exist_ok=False)
    manifest_rows: list[dict[str, Any]] = []
    for index, source in enumerate(rows, start=1):
        crop = crop_from_plan(source)
        crop_id = f"lsv_{source['source_photo_id'][:8]}_t{source['tray_index']:02d}_{index:03d}"
        image_path = temporary / "images" / f"{crop_id}.jpg"
        crop.save(image_path, quality=95)
        prelabel_path = temporary / "prelabels" / f"{crop_id}.json"
        write_json(prelabel_path, {
            "crop_id": crop_id,
            "source": "yolo_proposal_requires_full_human_review",
            "is_ground_truth": False,
            "boxes": source["prelabel_boxes"],
        })
        manifest_rows.append({
            "queue_index": index,
            "crop_id": crop_id,
            "image": str((queue / "images" / image_path.name).resolve()),
            "image_sha256": sha256_file(image_path),
            "perceptual_hash": image_phash(crop),
            "source_photo_id": source["source_photo_id"],
            "source_task_id": source["source_task_id"],
            "source_sha256": source["source_sha256"],
            "sku_code": source["sku_code"],
            "angle": "white_confuser_risk" if selection_mode == WHITE_CONFUSER_DISAGREEMENT else "side_view_or_stacked_risk",
            "selection_mode": selection_mode,
            "selection_tags": source["selection_tags"],
            "selection_score": source["selection_score"],
            "selection_evidence": source.get("selection_evidence") or {},
            "label_v1_verdict": source["label_v1_verdict"],
            "human_qc_verdict": "NO_DEFECT",
            "prelabel_boxes": len(source["prelabel_boxes"]),
            "train_only": True,
        })
    contact_sheets = make_contact_sheets(
        manifest_rows, temporary / "images", temporary / "contact-sheets"
    )
    manifest = {
        "version": f"liushanmen-label-side-view-{stamp}",
        "created_at": dt.datetime.now(dt.timezone.utc).isoformat(),
        "purpose": (
            "human review of white-like confusers amplified by a rejected ranking candidate"
            if selection_mode == WHITE_CONFUSER_DISAGREEMENT
            else "human review of fresh side-view, oblique, or stacked white-label detector misses"
        ),
        "selection_mode": selection_mode,
        "ranking_candidate_label": plan.get("ranking_candidate_label"),
        "ranking_candidate_label_sha256": plan.get("ranking_candidate_label_sha256"),
        "ranking_candidate_is_ground_truth": False,
        "preflight": str(args.plan),
        "preflight_sha256": sha256_file(args.plan),
        "candidate_digest": plan["candidate_digest"],
        "queue_count": len(manifest_rows),
        "candidate_photo_count": len({row["source_photo_id"] for row in manifest_rows}),
        "candidate_task_count": len({row["source_task_id"] for row in manifest_rows}),
        "sku_counts": dict(Counter(row["sku_code"] for row in manifest_rows)),
        "human_review_required_count": len(manifest_rows),
        "rows": manifest_rows,
        "contact_sheets": [str(queue / "contact-sheets" / Path(path).name) for path in contact_sheets],
        "preannotations_are_not_ground_truth": True,
        "every_image_requires_full_human_review": True,
        "protected_holdout_included": False,
        "existing_label_sources_included": False,
        "old_29_included": False,
        "cloud_calls": 0,
        "production_reads": 0,
        "production_writes": 0,
        "originals_modified": False,
        "training_started": False,
        "deployment": False,
    }
    write_json(temporary / "manifest.json", manifest)
    (temporary / "README.md").write_text(
        "Review every crop completely. YOLO boxes are proposals, not truth.\n"
        "A rejected candidate may rank this queue but can never supply ground truth.\n"
        "This queue is label-only and must never be merged with a tray MARK.\n",
        encoding="utf-8",
    )
    temporary.replace(queue)
    mark_value = {
        "status": "NEEDS_ANNOTATION",
        "queue": str(queue),
        "manifest": str(queue / "manifest.json"),
        "queue_count": len(manifest_rows),
        "annotator_url": args.annotator_url,
        "label_mark_only": True,
        "tray_mark_modified": False,
        "created_at": dt.datetime.now(dt.timezone.utc).isoformat(),
    }
    write_json(mark, mark_value)
    receipt = manifest | {
        "stage": "label-side-view-queue-ready",
        "queue": str(queue),
        "manifest": str(queue / "manifest.json"),
        "mark": str(mark),
        "annotator_url": args.annotator_url,
        "queue_created": True,
    }
    receipt_path = args.receipt or args.plan.with_name(args.plan.stem.replace("preflight", "queue") + ".json")
    write_json(receipt_path, receipt)
    return receipt


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    subparsers = parser.add_subparsers(dest="command", required=True)
    plan = subparsers.add_parser("plan")
    plan.add_argument("--repo-root", required=True, type=Path)
    plan.add_argument("--database", required=True, type=Path)
    plan.add_argument("--tray", required=True, type=Path)
    plan.add_argument("--tray-sha256", required=True)
    plan.add_argument("--label", required=True, type=Path)
    plan.add_argument("--label-sha256", required=True)
    plan.add_argument(
        "--selection-mode",
        choices=(SIDE_VIEW_MISSING, WHITE_CONFUSER_DISAGREEMENT),
        default=SIDE_VIEW_MISSING,
    )
    plan.add_argument("--candidate-label", type=Path)
    plan.add_argument("--candidate-label-sha256")
    plan.add_argument("--protected-holdout", required=True, type=Path)
    plan.add_argument("--existing-manifest", action="append", default=[], type=Path)
    plan.add_argument("--output", required=True, type=Path)
    plan.add_argument("--max-photos-scanned", type=int, default=300)
    plan.add_argument("--max-queue", type=int, default=40)
    plan.add_argument("--max-per-sku", type=int, default=20)
    plan.add_argument("--max-per-photo", type=int, default=1)
    plan.add_argument("--tray-threshold", type=float, default=0.60)
    plan.add_argument("--label-threshold", type=float, default=0.20)
    plan.add_argument("--minimum-tray-iou", type=float, default=0.90)
    plan.add_argument("--minimum-label-iou", type=float, default=0.30)
    plan.add_argument("--minimum-candidate-white-confidence", type=float, default=0.35)
    plan.add_argument("--minimum-white-confidence-delta", type=float, default=0.05)
    build = subparsers.add_parser("build")
    build.add_argument("--plan", required=True, type=Path)
    build.add_argument("--output-root", required=True, type=Path)
    build.add_argument("--attention-root", required=True, type=Path)
    build.add_argument("--annotator-url", default="http://127.0.0.1:8792")
    build.add_argument("--receipt", type=Path)
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    result = scan_plan(args) if args.command == "plan" else build_queue(args)
    summary = {
        "status": result.get("status", result.get("stage")),
        "queue_count": result.get("queue_count", 0),
        "candidate_digest": result.get("candidate_digest"),
        "queue": result.get("queue"),
        "mark": result.get("mark"),
        "cloud_calls": result.get("cloud_calls", 0),
        "production_writes": result.get("production_writes", 0),
    }
    print(json.dumps(summary, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
