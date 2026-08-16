#!/usr/bin/env python3
"""Build a full-image, per-tray human truth-repair queue for label QC misses.

The queue is intentionally evaluation-only.  It copies resized derivatives,
keeps originals untouched, seeds only detector proposals, and does not seed the
legacy protected target box because that box may group multiple physical trays.
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path
from typing import Any, Sequence

from PIL import Image, ImageOps

import label_qc_oracle_diagnostics as diagnostics


def normalise_box(box: Sequence[float], width: int, height: int) -> list[float]:
    if width <= 0 or height <= 0:
        raise ValueError("image dimensions must be positive")
    normalised = [
        max(0.0, min(1.0, float(box[0]) / width)),
        max(0.0, min(1.0, float(box[1]) / height)),
        max(0.0, min(1.0, float(box[2]) / width)),
        max(0.0, min(1.0, float(box[3]) / height)),
    ]
    if normalised[0] >= normalised[2] or normalised[1] >= normalised[3]:
        raise ValueError(f"invalid box after normalisation: {box}")
    return [round(value, 8) for value in normalised]


def deduplicate_proposals(
    proposals: Sequence[dict[str, Any]], iou_threshold: float = 0.80,
) -> list[dict[str, Any]]:
    kept: list[dict[str, Any]] = []
    for proposal in sorted(
        proposals,
        key=lambda row: (int(row["priority"]), float(row["confidence"])),
        reverse=True,
    ):
        if any(
            diagnostics.box_iou(proposal["box"], chosen["box"]) >= iou_threshold
            for chosen in kept
        ):
            continue
        kept.append(proposal)
    return kept


def collect_proposals(
    baseline_row: dict[str, Any], zoom_row: dict[str, Any], width: int, height: int,
) -> list[dict[str, Any]]:
    proposals: list[dict[str, Any]] = []
    for tray in baseline_row.get("screening", {}).get("trays") or []:
        proposals.append({
            "box": normalise_box(tray["box"], width, height),
            "confidence": round(float(tray["confidence"]), 6),
            "source": "production_full_frame_tray_proposal",
            "priority": 2,
            "is_ground_truth": False,
        })
    best_setting = zoom_row.get("summary", {}).get("best_setting") or {}
    for detection in best_setting.get("detections") or []:
        if float(detection.get("truth_containment", 0.0)) < 0.5:
            continue
        proposals.append({
            "box": normalise_box(detection["box"], width, height),
            "confidence": round(float(detection["confidence"]), 6),
            "source": "protected_target_crop_oracle_proposal",
            "priority": 3,
            "is_ground_truth": False,
            "oracle_only": True,
        })
    return deduplicate_proposals(proposals)


def yolo_line(box: Sequence[float]) -> str:
    width = float(box[2]) - float(box[0])
    height = float(box[3]) - float(box[1])
    center_x = float(box[0]) + width / 2
    center_y = float(box[1]) + height / 2
    return f"0 {center_x:.8f} {center_y:.8f} {width:.8f} {height:.8f}\n"


def write_json(path: Path, payload: Any) -> None:
    path.write_text(
        json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8",
    )


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--zoom-receipt", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--annotator-url", default="http://127.0.0.1:8796")
    parser.add_argument("--max-side", type=int, default=2048)
    return parser


def main() -> None:
    args = build_parser().parse_args()
    zoom_receipt = args.zoom_receipt.resolve()
    output = args.output.resolve()
    if not zoom_receipt.is_file():
        raise FileNotFoundError(zoom_receipt)
    if output.exists():
        raise FileExistsError(f"refusing to overwrite instance review queue: {output}")
    if args.max_side < 640:
        raise ValueError("max-side below 640 would discard review detail")

    zoom = json.loads(zoom_receipt.read_text(encoding="utf-8"))
    baseline_receipt_path = Path(str(zoom.get("inputs", {}).get("baseline_receipt") or ""))
    if not baseline_receipt_path.is_file():
        raise FileNotFoundError(baseline_receipt_path)
    expected_baseline_hash = str(
        zoom.get("inputs", {}).get("baseline_receipt_sha256") or ""
    )
    if expected_baseline_hash and diagnostics.sha256_file(
        baseline_receipt_path,
    ) != expected_baseline_hash:
        raise RuntimeError("baseline receipt hash drift")
    baseline = json.loads(baseline_receipt_path.read_text(encoding="utf-8"))
    baseline_by_id = {
        str(row["photo_id"]): row
        for row in baseline.get("baseline", {}).get("details") or []
    }
    selected_zoom_rows = [
        row for row in zoom.get("rows") or []
        if row.get("summary", {}).get("requires_full_image_instance_human_review") is True
    ]
    if not selected_zoom_rows:
        raise ValueError("zoom receipt has no rows gated for human instance review")

    for name in ("images", "labels", "annotations-human"):
        (output / name).mkdir(parents=True, exist_ok=False)
    manifest_rows: list[dict[str, Any]] = []
    for index, zoom_row in enumerate(selected_zoom_rows, 1):
        photo_id = str(zoom_row["photo_id"])
        baseline_row = baseline_by_id[photo_id]
        source = Path(str(zoom_row["image"]))
        if not source.is_file():
            raise FileNotFoundError(source)
        source_hash_before = diagnostics.sha256_file(source)
        if source_hash_before != str(zoom_row["image_sha256"]):
            raise RuntimeError(f"source image hash mismatch: {photo_id}")
        with Image.open(source) as opened:
            image = ImageOps.exif_transpose(opened).convert("RGB")
        original_size = list(image.size)
        proposals = collect_proposals(
            baseline_row, zoom_row, image.width, image.height,
        )
        packed = image.copy()
        if max(packed.size) > args.max_side:
            packed.thumbnail((args.max_side, args.max_side), Image.Resampling.LANCZOS)
        stem = f"labeltruth_{photo_id[:8]}_{index:03d}"
        image_path = output / "images" / f"{stem}.jpg"
        label_path = output / "labels" / f"{stem}.txt"
        annotation_path = output / "annotations-human" / f"{stem}.json"
        packed.save(image_path, format="JPEG", quality=94, optimize=True)
        label_path.write_text(
            "".join(yolo_line(proposal["box"]) for proposal in proposals),
            encoding="utf-8",
        )
        write_json(annotation_path, {
            "photo_id": stem,
            "source_photo_id": photo_id,
            "format": "normalised_xyxy",
            "reviewed": False,
            "source": "model_and_oracle_proposals_require_full_human_review",
            "boxes": [proposal["box"] for proposal in proposals],
        })
        if diagnostics.sha256_file(source) != source_hash_before:
            raise RuntimeError(f"source original changed while packing: {photo_id}")
        manifest_rows.append({
            "queue_index": index,
            "packed_stem": stem,
            "packed_image": f"images/{image_path.name}",
            "packed_label": f"labels/{label_path.name}",
            "photo_id": photo_id,
            "source_photo_id": photo_id,
            "task_id": zoom_row.get("task_id"),
            "angle": "full_image_per_physical_tray_truth_repair",
            "source_kind": "protected_regression_truth_repair",
            "human_defect_label": zoom_row.get("protected_truth"),
            "source_path": str(source),
            "source_sha256": source_hash_before,
            "original_size": original_size,
            "packed_size": list(packed.size),
            "packed_image_sha256": diagnostics.sha256_file(image_path),
            "legacy_protected_truth_box": zoom_row.get("truth_box"),
            "legacy_protected_truth_box_seeded": False,
            "baseline_issues": zoom_row.get("baseline_issues") or [],
            "zoom_interpretation": zoom_row.get("summary", {}).get("interpretation"),
            "human_review_reasons": zoom_row.get("summary", {}).get("human_review_reasons") or [],
            "preannotations": proposals,
            "preannotation_count": len(proposals),
            "preannotations_are_not_ground_truth": True,
            "manual_status": "PENDING_FULL_REVIEW",
            "train_only": False,
            "evaluation_only": True,
        })

    manifest = {
        "version": "liushanmen-label-instance-truth-repair-v1",
        "created_at": diagnostics.utc_now(),
        "purpose": "repair full-image per-physical-tray instance truth before label QC optimisation",
        "queue_count": len(manifest_rows),
        "annotator_url": args.annotator_url,
        "zoom_receipt": str(zoom_receipt),
        "zoom_receipt_sha256": diagnostics.sha256_file(zoom_receipt),
        "baseline_receipt": str(baseline_receipt_path),
        "baseline_receipt_sha256": diagnostics.sha256_file(baseline_receipt_path),
        "preannotations_are_not_ground_truth": True,
        "every_image_requires_full_human_review": True,
        "legacy_protected_truth_boxes_seeded": False,
        "protected_holdout_included": True,
        "protected_holdout_use": "truth_repair_and_regression_only",
        "training_allowed": False,
        "promotion_allowed": False,
        "deployment_allowed": False,
        "production_reads": 0,
        "production_writes": 0,
        "originals_modified": 0,
        "annotation_contract": [
            "Draw one tray box only when its visible outer boundary is judgeable; never infer a hidden full box.",
            "Review the complete image, add every judgeable tray, and delete every false proposal.",
            "Unboxed occluded lower trays are ignore regions for tray detection, never negatives.",
            "Visible labels on an occluded lower tray belong in a separate presence-only label-side-view queue.",
            "Changing to the next image is the review confirmation in the factory workflow; this local compatibility annotator uses its confirm action.",
        ],
        "occluded_lower_layer_policy": {
            "tray_box": "do_not_infer_hidden_boundary",
            "tray_detection_training": "ignore_not_negative",
            "visible_label_boxes": "allowed_in_separate_label_side_view_queue",
            "label_scope": "presence_only",
            "missing_label_verdict": "forbidden_without_judgeable_parent_tray",
        },
        "rows": manifest_rows,
    }
    manifest_path = output / "manifest.json"
    write_json(manifest_path, manifest)
    (output / "README.md").write_text(
        "# 六扇门标签 QC：实例真值修复队列\n\n"
        "请在每张完整照片上，把外轮廓可判断的盒子/托盘分别框出来，不能用一个大框包住多个盒子。"
        "被遮住、无法判断完整外框的下层盒子不要猜框，并按 ignore 处理；它露出的白标和彩标以后进入独立的标签侧视队列。\n\n"
        "所有初始框都只是 AI 提议，不是真值。旧缺标目标大框因可能包含多个实例，故意没有预填。"
        "本队列只用于修复 7+20 回归集真值，禁止加入训练集。\n",
        encoding="utf-8",
    )
    print(json.dumps({
        "status": "instance-truth-review-ready",
        "queue": str(output),
        "manifest": str(manifest_path),
        "queue_count": len(manifest_rows),
        "annotator_url": args.annotator_url,
        "training_allowed": False,
        "production_writes": 0,
        "originals_modified": 0,
    }, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
