#!/usr/bin/env python3
"""Seal reviewed tray boxes, train a YOLO candidate, gate it, and deploy only on pass."""
from __future__ import annotations

import argparse
import datetime as dt
import hashlib
import json
import math
import os
import shlex
import shutil
import subprocess
import sys
from collections import Counter
from pathlib import Path
from typing import Any

import imagehash
from PIL import Image, ImageOps

import vision_lab
import work_area


PROTECTED_PHASH_DISTANCE = 10
TARGET_DEFECT_PHOTO = "df1f6029-389d-45b5-995e-be19b2f5b943"


def load_json(path: Path) -> dict[str, Any]:
    value = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(value, dict):
        raise ValueError(f"expected object: {path}")
    return value


def sha256(path: Path) -> str:
    return vision_lab.sha256_file(path)


def phash(path: Path) -> str:
    with Image.open(path) as opened:
        return str(imagehash.phash(ImageOps.exif_transpose(opened), hash_size=16))


def phash_distance(left: str, right: str) -> int:
    return imagehash.hex_to_hash(left) - imagehash.hex_to_hash(right)


def annotation_set_digest(rows: list[dict[str, Any]]) -> str:
    identity = [
        {
            "queue": str(row.get("queue") or ""),
            "name": row["annotation_path"].name,
            "sha256": row["annotation_sha256"],
        }
        for row in rows
    ]
    return hashlib.sha256(vision_lab.stable_json(identity)).hexdigest()


def yolo_line(box: list[float]) -> str:
    if len(box) != 4 or any(isinstance(value, bool) or not math.isfinite(float(value)) for value in box):
        raise ValueError(f"invalid tray box: {box}")
    x0, y0, x1, y1 = map(float, box)
    if not (0.0 <= x0 < x1 <= 1.0 and 0.0 <= y0 < y1 <= 1.0):
        raise ValueError(f"tray box outside image: {box}")
    return f"0 {(x0+x1)/2:.6f} {(y0+y1)/2:.6f} {x1-x0:.6f} {y1-y0:.6f}"


def choose_validation_tasks(rows: list[dict[str, Any]], percent: int) -> set[str]:
    tasks = sorted({row["task_id"] for row in rows})
    if len(tasks) < 4:
        raise RuntimeError(f"too few independent tasks for tray validation: {len(tasks)}")
    count = max(1, min(len(tasks) - 1, math.ceil(len(tasks) * percent / 100)))
    ranked = sorted(tasks, key=lambda value: hashlib.sha256(f"tray-val:{value}".encode()).hexdigest())
    return set(ranked[:count])


def validate_reviewed_queue(queue: Path, holdout_path: Path) -> tuple[dict[str, Any], list[dict[str, Any]]]:
    manifest_path = queue / "manifest.json"
    manifest = load_json(manifest_path)
    rows = manifest.get("rows") or []
    if manifest.get("protected_holdout_included") or not manifest.get("preannotations_are_not_ground_truth"):
        raise RuntimeError("tray queue protection flags are unsafe")
    if len(rows) != int(manifest.get("queue_count", -1)) or not rows:
        raise RuntimeError("tray queue manifest count drift")
    holdout = load_json(holdout_path)
    protected = holdout.get("records") or []
    if holdout.get("train_use_allowed") is not False or len(protected) != 27:
        raise RuntimeError("protected 7+20 holdout contract drift")
    protected_hashes: list[tuple[str, str]] = []
    for row in protected:
        image = Path(row["image"])
        if not image.is_file() or sha256(image) != row["image_sha256"]:
            raise RuntimeError(f"protected holdout drift: {image}")
        protected_hashes.append((row["image_sha256"], phash(image)))

    accepted: list[dict[str, Any]] = []
    exact_overlap: list[str] = []
    near_overlap: list[dict[str, Any]] = []
    for source in rows:
        stem = source["packed_stem"]
        image = queue / source["packed_image"]
        annotation_path = queue / "annotations-human" / f"{stem}.json"
        if not image.is_file() or sha256(image) != source["packed_image_sha256"]:
            raise RuntimeError(f"packed tray image drift: {image}")
        original = Path(source["source_path"])
        if not original.is_file() or sha256(original) != source["source_sha256"]:
            raise RuntimeError(f"source tray image drift: {original}")
        if phash(original) != source["source_perceptual_hash"]:
            raise RuntimeError(f"source perceptual hash drift: {original}")
        if not annotation_path.is_file():
            raise RuntimeError(f"missing human tray annotation: {annotation_path}")
        annotation = load_json(annotation_path)
        if not annotation.get("reviewed") or annotation.get("source") != "human":
            raise RuntimeError(f"tray annotation is not fully human-reviewed: {annotation_path}")
        if annotation.get("format") != "normalised_xyxy":
            raise RuntimeError(f"unexpected tray annotation format: {annotation_path}")
        boxes = annotation.get("boxes") or []
        if not boxes:
            raise RuntimeError(f"reviewed tray image has no boxes: {annotation_path}")
        for box in boxes:
            yolo_line(box)
        source_phash, packed_phash = source["source_perceptual_hash"], phash(image)
        for protected_sha, protected_phash in protected_hashes:
            if source["source_sha256"] == protected_sha or source["packed_image_sha256"] == protected_sha:
                exact_overlap.append(stem)
            distance = min(
                phash_distance(source_phash, protected_phash),
                phash_distance(packed_phash, protected_phash),
            )
            if distance <= PROTECTED_PHASH_DISTANCE:
                near_overlap.append({"stem": stem, "distance": distance})
        accepted.append({
            "source": source, "task_id": str(source["task_id"]), "image": image,
            "annotation_path": annotation_path, "annotation_sha256": sha256(annotation_path),
            "boxes": boxes,
        })
    if exact_overlap or near_overlap:
        raise RuntimeError(f"protected holdout leaked into tray training: exact={exact_overlap}, near={near_overlap}")
    if len(accepted) != len(rows):
        raise RuntimeError(f"tray annotation completion gate: {len(accepted)}/{len(rows)}")
    return manifest, accepted


def audit_work_area_queue(queue: Path) -> dict[str, Any]:
    """Verify a complete human ROI sidecar without modifying tray truth or the queue manifest."""
    manifest_path = queue / "manifest.json"
    manifest = load_json(manifest_path)
    rows = manifest.get("rows") or []
    if manifest.get("protected_holdout_included") or not rows:
        raise RuntimeError("work-area audit requires a non-holdout tray queue")
    if len(rows) != int(manifest.get("queue_count", -1)):
        raise RuntimeError("work-area queue manifest count drift")
    totals: Counter[str] = Counter()
    audited: list[dict[str, Any]] = []
    missing: list[str] = []
    unjudgeable: list[str] = []
    for source in rows:
        stem = str(source["packed_stem"])
        packed_image = queue / str(source["packed_image"])
        source_image = Path(str(source["source_path"]))
        tray_path = queue / "annotations-human" / f"{stem}.json"
        roi_path = queue / "work-area-human" / f"{stem}.json"
        if not packed_image.is_file() or sha256(packed_image) != source.get("packed_image_sha256"):
            raise RuntimeError(f"packed image drift during work-area audit: {packed_image}")
        if not source_image.is_file() or sha256(source_image) != source.get("source_sha256"):
            raise RuntimeError(f"source image drift during work-area audit: {source_image}")
        if not tray_path.is_file():
            raise RuntimeError(f"missing human tray annotation for work-area audit: {tray_path}")
        tray = load_json(tray_path)
        if tray.get("reviewed") is not True or tray.get("source") != "human":
            raise RuntimeError(f"tray context is not human-reviewed: {tray_path}")
        boxes = tray.get("boxes") or []
        if not boxes:
            raise RuntimeError(f"tray context has no boxes: {tray_path}")
        for box in boxes:
            yolo_line(box)
        if not roi_path.is_file():
            missing.append(stem)
            continue
        annotation = work_area.validate_human_annotation(load_json(roi_path), expected_photo_id=stem)
        expected = {
            "source_photo_id": str(source.get("source_photo_id") or source.get("photo_id") or stem),
            "source_sha256": str(source.get("source_sha256") or ""),
            "packed_image_sha256": str(source.get("packed_image_sha256") or ""),
        }
        for field, value in expected.items():
            if not value or annotation.get(field) != value:
                raise RuntimeError(f"work-area annotation {field} mismatch: {roi_path}")
        if not annotation["judgeable"]:
            unjudgeable.append(stem)
            counts = {work_area.UNKNOWN_WORK_AREA: len(boxes)}
        else:
            counts = work_area.classify_boxes(boxes, annotation["polygon"])
        if annotation.get("tray_scope_counts") != counts:
            raise RuntimeError(f"work-area saved counts drift: {roi_path}")
        totals.update(counts)
        audited.append({
            "packed_stem": stem,
            "source_photo_id": expected["source_photo_id"],
            "task_id": str(source.get("task_id") or "unknown"),
            "sku_code": str(source.get("sku_code") or "unknown"),
            "annotation": str(roi_path),
            "annotation_sha256": sha256(roi_path),
            "tray_annotation": str(tray_path),
            "tray_annotation_sha256": sha256(tray_path),
            "source_sha256": expected["source_sha256"],
            "packed_image_sha256": expected["packed_image_sha256"],
            "judgeable": annotation["judgeable"],
            "tray_scope_counts": counts,
        })
    if missing or unjudgeable or len(audited) != len(rows):
        raise RuntimeError(
            f"work-area annotation completion gate: reviewed={len(audited)}/{len(rows)}, "
            f"missing={missing}, unjudgeable={unjudgeable}"
        )
    return {
        "version": "vision-lab-work-area-queue-audit-v1",
        "queue": str(queue),
        "queue_manifest_sha256": sha256(manifest_path),
        "reviewed_images": len(audited),
        "task_count": len({row["task_id"] for row in audited}),
        "sku_codes": sorted({row["sku_code"] for row in audited}),
        "tray_scope_counts": {name: int(totals[name]) for name in work_area.WORK_AREA_GROUPS},
        "scope_rule": "tray_center_in_human_polygon",
        "outside_samples_retained": True,
        "protected_holdout_modified": False,
        "production_writes": 0,
        "rows": audited,
    }


def write_work_area_receipt(runtime_root: Path, audits: list[dict[str, Any]]) -> tuple[Path, dict[str, Any]]:
    totals: Counter[str] = Counter()
    for audit in audits:
        totals.update(audit["tray_scope_counts"])
    payload = {
        "version": "vision-lab-work-area-audit-receipt-v1",
        "created_at": vision_lab.utc_now(),
        "queues": audits,
        "reviewed_images": sum(int(audit["reviewed_images"]) for audit in audits),
        "task_count": len({row["task_id"] for audit in audits for row in audit["rows"]}),
        "sku_codes": sorted({sku for audit in audits for sku in audit["sku_codes"]}),
        "tray_scope_counts": {name: int(totals[name]) for name in work_area.WORK_AREA_GROUPS},
        "unknown_work_area": int(totals[work_area.UNKNOWN_WORK_AREA]),
        "outside_samples_retained": True,
        "protected_holdout_modified": False,
        "production_writes": 0,
    }
    receipts = runtime_root / "receipts"
    receipts.mkdir(parents=True, exist_ok=True)
    stamp = dt.datetime.now(dt.timezone.utc).strftime("%Y%m%dT%H%M%S%fZ")
    path = receipts / f"work-area-audit-{stamp}.json"
    path.write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")
    return path, payload


def dataset_digest(root: Path) -> str:
    digest = hashlib.sha256()
    for path in sorted(root.rglob("*")):
        if path.is_file() and path.name != "manifest.json" and path.suffix != ".cache":
            digest.update(path.relative_to(root).as_posix().encode())
            digest.update(b"\0")
            digest.update(bytes.fromhex(sha256(path)))
    return digest.hexdigest()


def build_dataset(config: dict[str, Any], queue: Path | list[Path]) -> dict[str, Any]:
    tray = config["tray_active_learning"]
    holdout_path = Path(os.path.expandvars(tray["protected_holdout"]))
    queues = [queue] if isinstance(queue, Path) else list(queue)
    if not queues:
        raise RuntimeError("at least one reviewed tray queue is required")
    rows: list[dict[str, Any]] = []
    queue_manifests: list[dict[str, str]] = []
    for queue_root in queues:
        _, reviewed = validate_reviewed_queue(queue_root, holdout_path)
        for row in reviewed:
            row["queue"] = queue_root
        rows.extend(reviewed)
        queue_manifests.append({
            "queue": str(queue_root), "manifest_sha256": sha256(queue_root / "manifest.json"),
        })
    annotation_digest = annotation_set_digest(rows)
    dataset_id = "tray-" + hashlib.sha256(vision_lab.stable_json({
        "queue_manifests": queue_manifests, "annotations": annotation_digest,
    })).hexdigest()[:12]
    root = Path(config["runtime_root"])
    out = root / "datasets" / dataset_id
    val_tasks = choose_validation_tasks(rows, int(tray.get("validation_percent", 25)))
    train_rows = [row for row in rows if row["task_id"] not in val_tasks]
    val_rows = [row for row in rows if row["task_id"] in val_tasks]
    train_hashes = {row["source"]["source_sha256"] for row in train_rows}
    val_hashes = {row["source"]["source_sha256"] for row in val_rows}
    cross_exact = train_hashes & val_hashes
    cross_near = [
        (left["source"]["packed_stem"], right["source"]["packed_stem"], phash_distance(
            left["source"]["source_perceptual_hash"], right["source"]["source_perceptual_hash"],
        ))
        for left in train_rows for right in val_rows
        if phash_distance(
            left["source"]["source_perceptual_hash"], right["source"]["source_perceptual_hash"],
        ) <= PROTECTED_PHASH_DISTANCE
    ]
    if cross_exact or cross_near:
        raise RuntimeError(f"tray train/val leakage: exact={sorted(cross_exact)}, near={cross_near}")
    if out.exists():
        existing = load_json(out / "manifest.json")
        if existing.get("annotation_set_sha256") != annotation_digest:
            raise RuntimeError(f"existing tray dataset identity drift: {out}")
        if existing.get("dataset_sha256") != dataset_digest(out):
            raise RuntimeError(f"existing tray dataset content drift: {out}")
        return existing
    temporary = out.with_name(out.name + f".tmp.{os.getpid()}")
    if temporary.exists():
        raise RuntimeError(f"stale tray dataset build directory: {temporary}")
    for split in ("train", "val"):
        (temporary / "images" / split).mkdir(parents=True)
        (temporary / "labels" / split).mkdir(parents=True)
    (temporary / "annotations-source").mkdir()
    counts: Counter[str] = Counter()
    provenance: list[dict[str, Any]] = []
    for item in rows:
        source = item["source"]
        split = "val" if item["task_id"] in val_tasks else "train"
        stem = source["packed_stem"]
        image_out = temporary / "images" / split / f"{stem}.jpg"
        label_out = temporary / "labels" / split / f"{stem}.txt"
        annotation_out = temporary / "annotations-source" / f"{stem}.json"
        if image_out.exists() or label_out.exists() or annotation_out.exists():
            raise RuntimeError(f"duplicate tray dataset stem across queues: {stem}")
        shutil.copy2(item["image"], image_out)
        shutil.copy2(item["annotation_path"], annotation_out)
        label_out.write_text("\n".join(yolo_line(box) for box in item["boxes"]) + "\n", encoding="utf-8")
        counts[f"{split}_images"] += 1
        counts[f"{split}_boxes"] += len(item["boxes"])
        provenance.append({
            "stem": stem, "split": split, "task_id": item["task_id"],
            "queue": str(item["queue"]),
            "source_photo_id": source["source_photo_id"], "source_sha256": source["source_sha256"],
            "packed_image_sha256": source["packed_image_sha256"],
            "annotation_sha256": item["annotation_sha256"], "box_count": len(item["boxes"]),
            "selection_tags": source.get("selection_tags") or [], "human_truth": True,
        })
    if min(counts["train_images"], counts["val_images"], counts["train_boxes"], counts["val_boxes"]) <= 0:
        raise RuntimeError(f"tray dataset coverage failed: {counts}")
    (temporary / "data.yaml").write_text(
        f"path: {out.as_posix()}\ntrain: images/train\nval: images/val\nnames:\n  0: tray\n",
        encoding="utf-8",
    )
    (temporary / "provenance.json").write_text(
        json.dumps({"version": 1, "rows": provenance}, ensure_ascii=False, indent=2), encoding="utf-8",
    )
    manifest = {
        "version": "vision-lab-tray-dataset-v1", "dataset_id": dataset_id,
        "created_at": vision_lab.utc_now(), "queues": [str(value) for value in queues],
        "queue_manifests": queue_manifests,
        "annotation_set_sha256": annotation_digest, "human_reviewed_images": len(rows),
        "human_boxes": sum(len(row["boxes"]) for row in rows), "counts": dict(counts),
        "task_level_split": True, "validation_task_ids": sorted(val_tasks),
        "protected_holdout": str(holdout_path), "protected_holdout_included": False,
        "protected_exact_overlap": 0, "protected_near_overlap_hamming_le_10": 0,
        "preannotations_used_as_truth": False, "production_writes": 0,
        "data_yaml": str(out / "data.yaml"),
    }
    if len(queues) == 1:
        manifest["queue"] = str(queues[0])
        manifest["queue_manifest_sha256"] = queue_manifests[0]["manifest_sha256"]
    manifest["dataset_sha256"] = dataset_digest(temporary)
    (temporary / "manifest.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2), encoding="utf-8")
    temporary.replace(out)
    receipt = root / "receipts" / f"tray-seal-{dataset_id}.json"
    receipt.write_text(json.dumps(manifest, ensure_ascii=False, indent=2), encoding="utf-8")
    return manifest


def boxes_match(left: list[list[float]], right: list[list[float]], minimum_iou: float = 0.90) -> bool:
    if len(left) != len(right):
        return False
    remaining = list(right)
    for box in left:
        best_index, best_iou = -1, 0.0
        for index, other in enumerate(remaining):
            x0, y0 = max(box[0], other[0]), max(box[1], other[1])
            x1, y1 = min(box[2], other[2]), min(box[3], other[3])
            intersection = max(0.0, x1 - x0) * max(0.0, y1 - y0)
            union = ((box[2]-box[0])*(box[3]-box[1])
                     + (other[2]-other[0])*(other[3]-other[1]) - intersection)
            iou = intersection / max(union, 1e-9)
            if iou > best_iou:
                best_index, best_iou = index, iou
        if best_index < 0 or best_iou < minimum_iou:
            return False
        remaining.pop(best_index)
    return True


def verify_export_parity(best: Path, onnx: Path, validation_dir: Path, repo_root: Path) -> int:
    from ultralytics import YOLO
    import numpy as np

    backend = str((repo_root / "backend" / "python").resolve())
    if backend not in sys.path:
        sys.path.insert(0, backend)
    from label_qc.services.yolo_detector import _OnnxDetector

    images = sorted(validation_dir.glob("*.jpg"))[:24]
    if not images:
        raise RuntimeError("no tray validation images for export parity")
    pt_model = YOLO(str(best), task="detect")
    onnx_model = _OnnxDetector(onnx, imgsz=960)
    mismatches = 0
    for image in images:
        # The production ONNX wrapper always letterboxes to a fixed 960x960
        # canvas.  Ultralytics otherwise uses minimal rectangular padding for a
        # single image, which compares two different inputs and creates false
        # parity failures near the confidence threshold.
        result = pt_model.predict(
            str(image), imgsz=960, conf=0.60, iou=0.70, device=0,
            rect=False, verbose=False,
        )[0]
        pt_boxes = result.boxes.xyxy.cpu().tolist() if result.boxes is not None else []
        with Image.open(image) as opened:
            frame = np.array(ImageOps.exif_transpose(opened).convert("RGB"))
        onnx_boxes = [detection.as_xyxy() for detection in onnx_model.detect(frame, 0.60)]
        mismatches += int(not boxes_match(pt_boxes, onnx_boxes))
    return mismatches


def refresh_candidate_parity(
    config: dict[str, Any], dataset: dict[str, Any], model: dict[str, Any], repo_root: Path,
) -> dict[str, Any]:
    tray = config["tray_active_learning"]
    best, artifact = Path(model["best_pt"]), Path(model["artifact"])
    if not best.is_file() or sha256(best) != model["best_pt_sha256"]:
        raise RuntimeError("candidate best.pt drift")
    if not artifact.is_file() or sha256(artifact) != model["artifact_sha256"]:
        raise RuntimeError("candidate tray ONNX drift")
    validation = Path(dataset["data_yaml"]).parent / "images" / "val"
    model["onnx_parity_mismatches"] = verify_export_parity(best, artifact, validation, repo_root)
    production_pt = Path(os.path.expandvars(tray["base_model_pt"]))
    production_onnx = Path(os.path.expandvars(tray["production_tray_onnx"]))
    model["production_onnx_parity_mismatches"] = verify_export_parity(
        production_pt, production_onnx, validation, repo_root,
    )
    receipt = artifact.parent / "training-receipt.json"
    receipt.write_text(json.dumps(model, ensure_ascii=False, indent=2), encoding="utf-8")
    return model


def train_candidate(config: dict[str, Any], dataset: dict[str, Any], repo_root: Path) -> dict[str, Any]:
    os.environ["YOLO_OFFLINE"] = "true"
    from ultralytics import YOLO
    tray = config["tray_active_learning"]
    training = tray["training"]
    base = Path(os.path.expandvars(tray["base_model_pt"]))
    if not base.is_file() or sha256(base).lower() != tray["base_model_sha256"].lower():
        raise RuntimeError("tray base model missing or changed")
    run_id = f"{dataset['dataset_id']}-{dt.datetime.now().strftime('%Y%m%d-%H%M%S')}"
    root = Path(config["runtime_root"])
    result = YOLO(str(base)).train(
        data=dataset["data_yaml"], epochs=int(training.get("epochs", 30)),
        patience=int(training.get("patience", 10)), imgsz=960,
        batch=int(training.get("batch", 4)), device=training.get("device", 0),
        workers=int(training.get("workers", 2)), project=str(root / "runs"), name=run_id,
        exist_ok=False, pretrained=False, optimizer="AdamW", lr0=float(training.get("lr0", 0.00015)),
        lrf=0.10, weight_decay=0.0005, warmup_epochs=1.0,
        freeze=int(training.get("freeze", 10)),
        seed=int(training.get("seed", 20260811)), deterministic=True,
        close_mosaic=5, mosaic=0.10, mixup=0.0, copy_paste=0.0,
        degrees=0.5, translate=0.02, scale=0.10, shear=0.0, perspective=0.0,
        fliplr=0.5, flipud=0.0, hsv_h=0.004, hsv_s=0.15, hsv_v=0.12,
        amp=False, cache=False, save=True, save_period=1, plots=False, verbose=True,
    )
    save_dir = Path(result.save_dir)
    best = save_dir / "weights" / "best.pt"
    if not best.is_file():
        raise RuntimeError("tray training completed without best.pt")
    exported = Path(YOLO(str(best)).export(
        format="onnx", imgsz=960, opset=12, simplify=True, nms=True,
        dynamic=False, conf=float(training.get("export_floor", 0.05)),
    ))
    artifact_dir = root / "models" / "registry" / run_id
    artifact_dir.mkdir(parents=True)
    artifact = artifact_dir / "tray.onnx"
    shutil.copy2(exported, artifact)
    receipt = {
        "version": "vision-lab-tray-training-v1", "model_id": run_id,
        "created_at": vision_lab.utc_now(), "dataset_id": dataset["dataset_id"],
        "dataset_sha256": dataset["dataset_sha256"], "base_model": str(base),
        "base_model_sha256": sha256(base), "best_pt": str(best), "best_pt_sha256": sha256(best),
        "artifact": str(artifact), "artifact_sha256": sha256(artifact),
        "training": {
            key: training.get(key)
            for key in ("epochs", "patience", "batch", "workers", "device", "seed", "lr0", "freeze", "export_floor")
        },
        "status": "candidate",
        "production_writes": 0, "deployment": False,
    }
    return refresh_candidate_parity(config, dataset, receipt, repo_root)


def evaluate_candidate(
    config: dict[str, Any], model: dict[str, Any], repo_root: Path,
    work_area_annotations: list[Path] | None = None,
) -> tuple[Path, dict[str, Any]]:
    tray = config["tray_active_learning"]
    production_tray = Path(os.path.expandvars(tray["production_tray_onnx"]))
    production_label = Path(os.path.expandvars(tray["production_label_onnx"]))
    if sha256(production_tray).lower() != tray["production_tray_sha256"].lower():
        raise RuntimeError("production tray evaluation artifact drift")
    if sha256(production_label).lower() != tray["production_label_sha256"].lower():
        raise RuntimeError("production label evaluation artifact drift")
    output = Path(model["artifact"]).parent / "evaluation-metrics.json"
    command = [
        sys.executable, str(Path(__file__).with_name("evaluate_tray_candidate.py")),
        "--repo-root", str(repo_root.resolve()),
        "--production-tray", str(production_tray),
        "--candidate-tray", model["artifact"],
        "--production-label", str(production_label),
        "--manifest", str(Path(os.path.expandvars(tray["protected_holdout"]))),
        "--threshold", str(float(tray.get("label_threshold", 0.20))),
        "--onnx-parity-mismatches", str(model["onnx_parity_mismatches"]),
        "--production-onnx-parity-mismatches", str(model["production_onnx_parity_mismatches"]),
        "--output", str(output),
    ]
    for annotation_root in work_area_annotations or []:
        command.extend(["--work-area-annotations", str(annotation_root)])
    result = subprocess.run(command, text=True, encoding="utf-8", errors="replace", timeout=7200)
    if result.returncode != 0 or not output.is_file():
        raise RuntimeError(f"tray candidate evaluation failed with exit code {result.returncode}")
    return output, load_json(output)


def evaluate_gate(config: dict[str, Any], model: dict[str, Any], metrics: dict[str, Any]) -> dict[str, Any]:
    gate = config["promotion_gates"]
    baseline, candidate = metrics.get("baseline") or {}, metrics.get("candidate") or {}
    errors: list[str] = []
    if metrics.get("artifact_sha256") != model["artifact_sha256"] or sha256(Path(model["artifact"])) != model["artifact_sha256"]:
        errors.append("evaluated tray artifact hash mismatch")
    if int(candidate.get("defect_total", 0)) < int(gate.get("min_independent_defects", 7)):
        errors.append("insufficient independent defects")
    if int(candidate.get("defect_hits", -1)) < int(baseline.get("defect_hits", 0)):
        errors.append("defect recall regressed")
    if int(candidate.get("defect_hits", 0)) < int(gate.get("min_defect_hits", 4)):
        errors.append("minimum defect hit gate not met")
    if int(candidate.get("tray_target_hits", -1)) < int(baseline.get("tray_target_hits", 0)):
        errors.append("tray target coverage regressed")
    for group_name in gate.get("required_full_recall_groups", []):
        inside_rows = [
            row for row in candidate.get("details", [])
            if row.get("kind") == "defect" and row.get("group") == group_name
            and row.get("work_area") == work_area.INSIDE_WORK_AREA
        ]
        if inside_rows and not all(row.get("hit") for row in inside_rows):
            errors.append(f"required inside defect group did not reach full recall: {group_name}")
        if inside_rows and not all(row.get("tray_target_covered") for row in inside_rows):
            errors.append(f"required inside defect group tray coverage incomplete: {group_name}")
    target = next((row for row in candidate.get("details", []) if row.get("photo_id") == TARGET_DEFECT_PHOTO), None)
    baseline_target = next(
        (row for row in baseline.get("details", []) if row.get("photo_id") == TARGET_DEFECT_PHOTO), None,
    )
    if not target:
        errors.append(f"root-cause protected defect result missing: {TARGET_DEFECT_PHOTO}")
    if not target or target.get("work_area") != work_area.OUTSIDE_WORK_AREA:
        errors.append(f"root-cause protected defect is not audited as outside_work_area: {TARGET_DEFECT_PHOTO}")
    if baseline_target and target and (
        int(bool(target.get("tray_target_covered"))) < int(bool(baseline_target.get("tray_target_covered")))
        or int(bool(target.get("hit"))) < int(bool(baseline_target.get("hit")))
    ):
        errors.append(f"outside root-cause protected defect regressed: {TARGET_DEFECT_PHOTO}")
    baseline_fp, candidate_fp = int(baseline.get("false_flags", 0)), int(candidate.get("false_flags", 10**9))
    improvement = float(gate.get("min_false_flag_improvement", 0.05))
    if baseline_fp <= 0 or candidate_fp > baseline_fp * (1.0 - improvement):
        errors.append("false-flag improvement gate not met")
    parity = int(metrics.get("onnx_parity_mismatches", -1))
    production_parity = int(metrics.get("production_onnx_parity_mismatches", -1))
    maximum_parity = int(config["tray_active_learning"].get("max_onnx_parity_mismatches", 1))
    if parity < 0 or production_parity < 0 or parity > production_parity or parity > maximum_parity:
        errors.append("PT/ONNX parity regressed against production")
    if not metrics.get("production_pipeline_replay"):
        errors.append("production pipeline replay missing")
    baseline_latency = float(baseline.get("p95_latency_ms", 0))
    candidate_latency = float(candidate.get("p95_latency_ms", 10**9))
    if candidate_latency > float(gate.get("max_p95_latency_ms", 8000)):
        errors.append("latency gate failed")
    if baseline_latency and candidate_latency > baseline_latency * (1 + float(gate.get("max_latency_regression", 0.15))):
        errors.append("latency regressed against production")
    baseline_work_area = baseline.get("work_area") or {}
    candidate_work_area = candidate.get("work_area") or {}
    baseline_scope = baseline_work_area.get("groups") or {}
    candidate_scope = candidate_work_area.get("groups") or {}
    roi_records = candidate_work_area.get("records") or {}
    unknown_scope = candidate_scope.get(work_area.UNKNOWN_WORK_AREA) or {}
    unknown_counts = {
        "records_without_human_roi": int(roi_records.get("without_human_roi", 0)),
        "unjudgeable_records": int(roi_records.get("unjudgeable", 0)),
        "detected_trays": int(unknown_scope.get("detected_trays", 0)),
        "defects": int(unknown_scope.get("defect_total", 0)),
        "missing_label_flags": int(unknown_scope.get("missing_label_flags", 0)),
    }
    if not candidate_scope or any(unknown_counts.values()):
        errors.append(f"unknown_work_area evidence blocks promotion: {unknown_counts}")
    inside_baseline = baseline_scope.get(work_area.INSIDE_WORK_AREA) or {}
    inside_candidate = candidate_scope.get(work_area.INSIDE_WORK_AREA) or {}
    inside_defect_total = int(inside_candidate.get("defect_total", 0))
    inside_defect_hits = int(inside_candidate.get("defect_hits", 0))
    if inside_defect_total <= 0:
        errors.append("inside_work_area primary defect coverage is missing")
    required_inside_hits = min(int(gate.get("min_defect_hits", 4)), inside_defect_total)
    if inside_defect_hits < required_inside_hits:
        errors.append("inside_work_area minimum primary defect hit gate not met")
    if int(inside_candidate.get("defect_hits", -1)) < int(inside_baseline.get("defect_hits", 0)):
        errors.append("inside_work_area defect recall regressed")
    if int(inside_candidate.get("tray_target_hits", -1)) < int(inside_baseline.get("tray_target_hits", 0)):
        errors.append("inside_work_area tray coverage regressed")
    if int(inside_candidate.get("tray_target_hits", -1)) != int(inside_candidate.get("tray_target_total", 0)):
        errors.append("inside_work_area primary tray coverage incomplete")
    inside_baseline_fp = int(inside_baseline.get("false_flags", 0))
    inside_candidate_fp = int(inside_candidate.get("false_flags", 10**9))
    allowed_inside_fp = 0 if inside_baseline_fp == 0 else inside_baseline_fp * (1.0 - improvement)
    if inside_candidate_fp > allowed_inside_fp:
        errors.append("inside_work_area primary false-flag gate not met")
    outside_baseline = baseline_scope.get(work_area.OUTSIDE_WORK_AREA) or {}
    outside_candidate = candidate_scope.get(work_area.OUTSIDE_WORK_AREA) or {}
    if int(outside_candidate.get("defect_total", 0)) <= 0:
        errors.append("outside_work_area defect coverage is missing")
    if int(outside_candidate.get("defect_hits", -1)) < int(outside_baseline.get("defect_hits", 0)):
        errors.append("outside_work_area defect recall regressed")
    if int(outside_candidate.get("tray_target_hits", -1)) < int(outside_baseline.get("tray_target_hits", 0)):
        errors.append("outside_work_area tray coverage regressed")
    result = {
        "version": "vision-lab-tray-promotion-gate-v1", "model_id": model["model_id"],
        "evaluated_at": vision_lab.utc_now(), "passed": not errors, "errors": errors,
        "metrics": metrics, "deployment_authorized": not errors,
        "work_area_gate": {
            "inside_primary": inside_candidate,
            "outside_secondary": outside_candidate,
            "unknown_counts": unknown_counts,
        },
    }
    (Path(model["artifact"]).parent / "promotion-gate.json").write_text(
        json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8",
    )
    return result


class TrayStateAdapter:
    def __init__(self, state: vision_lab.State) -> None:
        self.state = state

    def get_meta(self, key: str) -> str | None:
        mapped = "production_tray_model_sha256" if key == "production_model_sha256" else key
        return self.state.get_meta(mapped)

    def set_meta(self, key: str, value: str) -> None:
        mapped = "production_tray_model_sha256" if key == "production_model_sha256" else key
        self.state.set_meta(mapped, value)


def deploy_if_passed(config: dict[str, Any], model: dict[str, Any], gate: dict[str, Any]) -> dict[str, Any]:
    if not gate.get("passed"):
        return {"status": "blocked-by-gate", "production_changed": False}
    tray = config["tray_active_learning"]
    deployment = tray["deployment"]
    state = vision_lab.State(Path(config["runtime_root"]))
    try:
        expected = str(deployment["production_sha256"]).lower()
        local_production = Path(os.path.expandvars(tray["production_tray_onnx"]))
        if sha256(local_production).lower() != expected:
            raise RuntimeError("registered local production tray hash drift")
        if not state.get_meta("production_tray_model_sha256"):
            host, remote = str(deployment["ssh_host"]), str(deployment["remote_model_path"])
            live = vision_lab.ssh_run(host, f"sha256sum {shlex.quote(remote)} | cut -d' ' -f1")
            if live.lower() != expected:
                raise RuntimeError(f"live production tray hash drift: {live}")
            state.set_meta("production_tray_model_sha256", live.lower())
        deploy_config = {"runtime_root": config["runtime_root"], "deployment": deployment}
        return vision_lab.deploy_candidate(deploy_config, TrayStateAdapter(state), model, gate)
    finally:
        state.close()


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--config", required=True, type=Path)
    parser.add_argument("--queue", action="append", type=Path)
    parser.add_argument("--repo-root", type=Path, default=Path(__file__).resolve().parents[2])
    parser.add_argument("--prepare-only", action="store_true")
    parser.add_argument("--candidate-receipt", type=Path)
    parser.add_argument("--work-area-queue", action="append", type=Path)
    parser.add_argument("--work-area-annotations", action="append", type=Path)
    parser.add_argument("--audit-work-area-only", action="store_true")
    args = parser.parse_args()
    config = vision_lab.load_config(args.config)
    runtime_root = Path(config["runtime_root"])
    work_area_audits = [audit_work_area_queue(queue.resolve()) for queue in (args.work_area_queue or [])]
    work_area_receipt: Path | None = None
    if work_area_audits:
        work_area_receipt, payload = write_work_area_receipt(runtime_root, work_area_audits)
        if args.audit_work_area_only:
            print(json.dumps({"receipt": str(work_area_receipt), **payload}, ensure_ascii=False, indent=2))
            return
    elif args.audit_work_area_only:
        raise RuntimeError("--audit-work-area-only requires at least one --work-area-queue")
    queues = args.queue or [Path(os.path.expandvars(config["tray_active_learning"]["queue_root"]))]
    dataset = build_dataset(config, [queue.resolve() for queue in queues])
    if args.prepare_only:
        print(json.dumps(dataset, ensure_ascii=False, indent=2))
        return
    if args.candidate_receipt:
        model = load_json(args.candidate_receipt)
        if model.get("dataset_id") != dataset["dataset_id"] or model.get("dataset_sha256") != dataset["dataset_sha256"]:
            raise RuntimeError("candidate receipt belongs to a different tray dataset")
        model = refresh_candidate_parity(config, dataset, model, args.repo_root)
    else:
        model = train_candidate(config, dataset, args.repo_root)
    configured_annotations = config["tray_active_learning"].get("work_area_evaluation_annotations") or []
    annotation_roots = args.work_area_annotations or [Path(os.path.expandvars(value)) for value in configured_annotations]
    metrics_path, metrics = evaluate_candidate(config, model, args.repo_root, annotation_roots)
    gate = evaluate_gate(config, model, metrics)
    deployment = deploy_if_passed(config, model, gate)
    receipt = {
        "version": "vision-lab-tray-workflow-v1", "completed_at": vision_lab.utc_now(),
        "dataset_id": dataset["dataset_id"], "model_id": model["model_id"],
        "metrics": str(metrics_path), "gate_passed": gate["passed"],
        "gate_errors": gate["errors"], "deployment": deployment,
    }
    if work_area_receipt is not None:
        receipt["work_area_audit_receipt"] = str(work_area_receipt)
    path = Path(config["runtime_root"]) / "receipts" / f"tray-workflow-{model['model_id']}.json"
    path.write_text(json.dumps(receipt, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(receipt, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
