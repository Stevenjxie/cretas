#!/usr/bin/env python3
"""Create a focused full-image tray cleanup queue from accepted analogue tasks.

Candidate detections and the selected analogue box are proposals only.  The
operator must leave boxes only around trays whose complete outer boundary is
judgeable.  Visible labels on occluded lower trays belong to a separate label
truth workflow and do not justify a tray box.
"""
from __future__ import annotations

import argparse
import datetime as dt
import json
import shutil
import sys
import tempfile
from pathlib import Path
from typing import Any, Sequence

import imagehash
import numpy as np
from PIL import Image, ImageOps

import label_qc_oracle_diagnostics as diagnostics


def normalise_detection(detection: Any, width: int, height: int) -> list[float]:
    return [
        float(detection.x0) / width,
        float(detection.y0) / height,
        float(detection.x1) / width,
        float(detection.y1) / height,
    ]


def merge_proposals(
    detections: Sequence[dict[str, Any]], selected_box: Sequence[float],
    iou_threshold: float = 0.50, ios_threshold: float = 0.80,
) -> list[dict[str, Any]]:
    proposals = sorted(
        [dict(row) for row in detections], key=lambda row: -float(row.get("confidence", 0.0)),
    )
    selected = [float(value) for value in selected_box]
    duplicate = any(
        diagnostics.box_iou(row["box"], selected) >= iou_threshold
        or diagnostics.intersection_over_smaller(row["box"], selected) >= ios_threshold
        for row in proposals
    )
    if not duplicate:
        proposals.append({
            "box": selected,
            "confidence": 1.0,
            "source": "visually_scoped_analogue_target",
            "is_ground_truth": False,
        })
    return proposals


def yolo_line(box: Sequence[float]) -> str:
    x0, y0, x1, y1 = [float(value) for value in box]
    if not (0 <= x0 < x1 <= 1 and 0 <= y0 < y1 <= 1):
        raise ValueError(f"invalid proposal box: {box}")
    return f"0 {(x0+x1)/2:.6f} {(y0+y1)/2:.6f} {x1-x0:.6f} {y1-y0:.6f}\n"


def perceptual_hash(path: Path) -> str:
    with Image.open(path) as opened:
        return str(imagehash.phash(ImageOps.exif_transpose(opened), hash_size=16))


def build_queue(
    dataset_manifest_path: Path,
    candidate_receipt_path: Path,
    baseline_receipt_path: Path,
    output_root: Path,
    repo_root: Path,
    tray_threshold: float,
) -> tuple[Path, dict[str, Any]]:
    dataset_manifest_path = dataset_manifest_path.resolve()
    candidate_receipt_path = candidate_receipt_path.resolve()
    baseline_receipt_path = baseline_receipt_path.resolve()
    dataset = json.loads(dataset_manifest_path.read_text(encoding="utf-8"))
    candidate = json.loads(candidate_receipt_path.read_text(encoding="utf-8"))
    baseline = json.loads(baseline_receipt_path.read_text(encoding="utf-8"))
    if dataset.get("visual_scope_review_complete") is not True:
        raise RuntimeError("patch dataset has no completed candidate-level scope review")
    if candidate.get("dataset_id") != dataset.get("dataset_id"):
        raise RuntimeError("candidate receipt belongs to another dataset")
    candidate_model = Path(str(candidate["artifact"]))
    label_model = Path(str(baseline["inputs"]["label_model"]))
    for path, expected in (
        (candidate_model, candidate["artifact_sha256"]),
        (label_model, baseline["inputs"]["label_model_sha256"]),
    ):
        if not path.is_file() or diagnostics.sha256_file(path) != expected:
            raise RuntimeError(f"model binding drift: {path}")
    accepted = dataset.get("accepted_scope_rows") or []
    if len(accepted) < 2 or len({row["task_id"] for row in accepted}) != len(accepted):
        raise RuntimeError("focused queue requires independent accepted analogue tasks")
    stamp = dt.datetime.now(dt.timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    queue = output_root.resolve() / f"tray-scope-review-{stamp}"
    if queue.exists():
        raise FileExistsError(f"refusing to overwrite queue: {queue}")
    for name in ("images", "labels", "annotations-human"):
        (queue / name).mkdir(parents=True, exist_ok=False)
    sys.path.insert(0, str((repo_root.resolve() / "backend" / "python").resolve()))
    from label_qc.services import yolo_detector as yolo

    rows: list[dict[str, Any]] = []
    with tempfile.TemporaryDirectory(prefix="tray-scope-review-model-") as temporary:
        model_root = Path(temporary)
        shutil.copy2(candidate_model, model_root / "tray.onnx")
        shutil.copy2(label_model, model_root / "label.onnx")
        models = yolo.LabelQcYoloModels(model_dir=model_root)
        if not models.available:
            raise RuntimeError(models.load_error or "candidate model unavailable")
        for index, source in enumerate(accepted, 1):
            source_image = Path(str(source["source_packed_image"]))
            if diagnostics.sha256_file(source_image) != source["source_packed_image_sha256"]:
                raise RuntimeError(f"accepted source image drift: {source_image}")
            with Image.open(source_image) as opened:
                image = ImageOps.exif_transpose(opened).convert("RGB")
            frame = np.array(image)
            detections = models.detect_trays(frame, tray_threshold)
            detector_rows = [
                {
                    "box": normalise_detection(row, image.width, image.height),
                    "confidence": round(float(row.confidence), 6),
                    "source": "offline_candidate_full_frame",
                    "is_ground_truth": False,
                }
                for row in detections
            ]
            source_annotation = json.loads(Path(str(source["source_annotation"])).read_text(encoding="utf-8"))
            source_box = source_annotation["boxes"][int(source["human_box_index"])]
            proposals = merge_proposals(detector_rows, source_box)
            stem = f"trayscope_{str(source['source_photo_id'])[:8]}_{index:03d}"
            packed_image = queue / "images" / f"{stem}.jpg"
            shutil.copy2(source_image, packed_image)
            packed_hash = diagnostics.sha256_file(packed_image)
            label_path = queue / "labels" / f"{stem}.txt"
            label_path.write_text("".join(yolo_line(row["box"]) for row in proposals), encoding="utf-8")
            annotation_path = queue / "annotations-human" / f"{stem}.json"
            annotation_path.write_text(json.dumps({
                "photo_id": stem,
                "source_photo_id": source["source_photo_id"],
                "format": "normalised_xyxy",
                "reviewed": False,
                "source": "candidate_and_scoped_target_proposals_require_full_human_review",
                "boxes": [row["box"] for row in proposals],
                "annotation_policy": {
                    "tray_box": "only a single tray with a judgeable complete outer boundary",
                    "occluded_lower_tray": "do not box",
                    "visible_lower_label": "retain for separate label-side-view annotation",
                    "switching_photo_after_review": "confirms current photo",
                },
            }, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
            rows.append({
                "queue_index": index,
                "packed_stem": stem,
                "packed_image": f"images/{packed_image.name}",
                "packed_label": f"labels/{label_path.name}",
                "photo_id": source["source_photo_id"],
                "source_photo_id": source["source_photo_id"],
                "task_id": source["task_id"],
                "sku_code": "",
                "angle": "judgeable_tray_scope_cleanup",
                "source_path": str(source_image),
                "source_sha256": source["source_packed_image_sha256"],
                "source_perceptual_hash": perceptual_hash(source_image),
                "original_size": list(image.size),
                "packed_size": list(image.size),
                "packed_image_sha256": packed_hash,
                "selection_tags": ["target_like_tray_miss", "full_image_scope_cleanup"],
                "preannotations": proposals,
                "preannotation_count": len(proposals),
                "preannotations_are_not_ground_truth": True,
                "manual_status": "PENDING_FULL_REVIEW",
                "nearest_holdout_phash": {
                    "distance": int(source["nearest_holdout_phash_distance"]),
                },
                "train_only": True,
                "protected_target": False,
            })
    manifest = {
        "version": "label-qc-tray-scope-review-queue-v1",
        "created_at": diagnostics.utc_now(),
        "purpose": "full-image cleanup of tray truth after tight analogue patches failed protected full-frame scale",
        "source_dataset": str(dataset_manifest_path),
        "source_dataset_sha256": diagnostics.sha256_file(dataset_manifest_path),
        "candidate_receipt": str(candidate_receipt_path),
        "candidate_receipt_sha256": diagnostics.sha256_file(candidate_receipt_path),
        "detector_model": str(candidate_model),
        "detector_sha256": diagnostics.sha256_file(candidate_model),
        "detector_is_ground_truth": False,
        "preannotations_are_not_ground_truth": True,
        "every_image_requires_full_human_review": True,
        "annotation_policy": {
            "tray_truth": "only judgeable complete outer tray boundaries",
            "occluded_lower_trays": "ignore_not_negative",
            "visible_lower_labels": "separate_label_side_view_truth",
        },
        "queue_count": len(rows),
        "rows": rows,
        "protected_holdout_included": False,
        "all_rows_train_only": True,
        "production_reads": 0,
        "production_writes": 0,
        "originals_modified": False,
    }
    (queue / "manifest.json").write_text(
        json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8",
    )
    return queue, manifest


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--dataset-manifest", required=True, type=Path)
    parser.add_argument("--candidate-receipt", required=True, type=Path)
    parser.add_argument("--baseline-receipt", required=True, type=Path)
    parser.add_argument("--output-root", required=True, type=Path)
    parser.add_argument("--repo-root", required=True, type=Path)
    parser.add_argument("--tray-threshold", type=float, default=0.60)
    return parser


def main() -> None:
    args = build_parser().parse_args()
    queue, manifest = build_queue(
        args.dataset_manifest,
        args.candidate_receipt,
        args.baseline_receipt,
        args.output_root,
        args.repo_root,
        args.tray_threshold,
    )
    print(json.dumps({
        "queue": str(queue),
        "queue_count": manifest["queue_count"],
        "policy": manifest["annotation_policy"],
        "protected_holdout_included": False,
        "production_writes": 0,
        "originals_modified": False,
    }, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
