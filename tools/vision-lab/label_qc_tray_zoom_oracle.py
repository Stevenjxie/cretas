#!/usr/bin/env python3
"""Run a local-crop tray oracle for full-frame misses without changing truth.

The tool replays only defect rows that the baseline receipt says were not
covered by a tray.  It crops progressively wider context around the protected
target, runs the unchanged production tray model, maps detections back to the
source image, and records whether the miss is scale-sensitive, threshold-
sensitive, or still incompatible with a single-tray target.

This is diagnostic evidence only.  A crop derived from the protected target is
an oracle and must never be used as a production detection path or promotion
gate.
"""
from __future__ import annotations

import argparse
import json
import math
import os
import shutil
import sys
import tempfile
from collections import Counter
from pathlib import Path
from typing import Any, Iterable, Sequence

import numpy as np
from PIL import Image, ImageOps

import label_qc_oracle_diagnostics as diagnostics


def parse_float_list(value: str) -> list[float]:
    values = [float(item.strip()) for item in value.split(",") if item.strip()]
    if not values:
        raise argparse.ArgumentTypeError("at least one numeric value is required")
    return values


def expand_box(
    box: Sequence[float], width: int, height: int, pad_ratio: float,
) -> list[float]:
    if pad_ratio < 0:
        raise ValueError("pad_ratio must be non-negative")
    box_width = max(1.0, float(box[2]) - float(box[0]))
    box_height = max(1.0, float(box[3]) - float(box[1]))
    return [
        max(0.0, float(box[0]) - box_width * pad_ratio),
        max(0.0, float(box[1]) - box_height * pad_ratio),
        min(float(width), float(box[2]) + box_width * pad_ratio),
        min(float(height), float(box[3]) + box_height * pad_ratio),
    ]


def integer_crop_rect(box: Sequence[float], width: int, height: int) -> list[int]:
    return [
        max(0, min(width, math.floor(float(box[0])))),
        max(0, min(height, math.floor(float(box[1])))),
        max(0, min(width, math.ceil(float(box[2])))),
        max(0, min(height, math.ceil(float(box[3])))),
    ]


def map_detection(detection: Any, crop_rect: Sequence[int]) -> list[float]:
    return [
        float(detection.x0) + crop_rect[0],
        float(detection.y0) + crop_rect[1],
        float(detection.x1) + crop_rect[0],
        float(detection.y1) + crop_rect[1],
    ]


def _center_inside(box: Sequence[float], target: Sequence[float]) -> bool:
    center_x = (float(box[0]) + float(box[2])) / 2
    center_y = (float(box[1]) + float(box[3])) / 2
    return (
        float(target[0]) <= center_x <= float(target[2])
        and float(target[1]) <= center_y <= float(target[3])
    )


def _intersects(box: Sequence[float], target: Sequence[float]) -> bool:
    return (
        min(float(box[2]), float(target[2])) > max(float(box[0]), float(target[0]))
        and min(float(box[3]), float(target[3])) > max(float(box[1]), float(target[1]))
    )


def measure_detections(
    detections: Iterable[Any], crop_rect: Sequence[int], truth_box: Sequence[float],
) -> dict[str, Any]:
    rows: list[dict[str, Any]] = []
    for detection in detections:
        mapped = map_detection(detection, crop_rect)
        rows.append({
            "box": diagnostics._round_box(mapped),
            "confidence": round(float(detection.confidence), 6),
            "truth_containment": round(
                diagnostics.containment(mapped, truth_box), 6,
            ),
            "truth_iou": round(diagnostics.box_iou(mapped, truth_box), 6),
            "center_inside_truth": _center_inside(mapped, truth_box),
            "intersects_truth": _intersects(mapped, truth_box),
        })
    rows.sort(
        key=lambda row: (
            row["truth_containment"], row["truth_iou"], row["confidence"],
        ),
        reverse=True,
    )
    return {
        "detection_count": len(rows),
        "best_truth_containment": rows[0]["truth_containment"] if rows else 0.0,
        "best_truth_iou": max((row["truth_iou"] for row in rows), default=0.0),
        "center_inside_truth_count": sum(
            int(row["center_inside_truth"]) for row in rows
        ),
        "intersects_truth_count": sum(int(row["intersects_truth"]) for row in rows),
        "detections": rows,
    }


def classify_zoom_result(
    settings: Sequence[dict[str, Any]], baseline_threshold: float,
) -> dict[str, Any]:
    covered = [row for row in settings if row["best_truth_containment"] >= 0.5]
    same_threshold = [
        row for row in covered
        if abs(float(row["threshold"]) - baseline_threshold) < 1e-9
    ]
    lower_threshold = [
        row for row in covered if float(row["threshold"]) < baseline_threshold
    ]
    max_centers = max(
        (int(row["center_inside_truth_count"]) for row in settings), default=0,
    )
    if same_threshold:
        interpretation = "single_tray_scale_sensitive"
    elif lower_threshold:
        interpretation = "single_tray_threshold_sensitive"
    elif max_centers >= 2:
        interpretation = "protected_bbox_may_group_multiple_tray_instances"
    else:
        interpretation = "not_recovered_by_local_scale_or_threshold_oracle"
    best = max(
        settings,
        key=lambda row: (
            float(row["best_truth_containment"]),
            float(row["best_truth_iou"]),
            int(row["center_inside_truth_count"]),
        ),
        default=None,
    )
    return {
        "interpretation": interpretation,
        "heuristic_only": True,
        "requires_full_image_instance_human_review": interpretation in {
            "protected_bbox_may_group_multiple_tray_instances",
            "not_recovered_by_local_scale_or_threshold_oracle",
        },
        "recovered_at_baseline_threshold": bool(same_threshold),
        "recovered_only_below_baseline_threshold": bool(lower_threshold and not same_threshold),
        "maximum_center_inside_truth_count": max_centers,
        "best_setting": best,
    }


def apply_human_review_gate(
    summary: dict[str, Any], baseline_issues: Sequence[str],
) -> dict[str, Any]:
    reasons: list[str] = []
    if summary["requires_full_image_instance_human_review"]:
        reasons.append("local_oracle_did_not_recover_a_single_tray_target")
    if "protected_truth_conflicts_with_human_crop" in baseline_issues:
        reasons.append("protected_truth_conflicts_with_existing_human_crop_review")
    if "truth_tray_current_label_mismatch" in baseline_issues:
        reasons.append("current_label_oracle_disagrees_with_protected_truth")
    return summary | {
        "requires_full_image_instance_human_review": bool(reasons),
        "human_review_reasons": reasons,
    }


def _validate_receipt_inputs(receipt: dict[str, Any]) -> tuple[Path, Path, Path]:
    inputs = receipt.get("inputs") or {}
    manifest = Path(str(inputs.get("manifest") or ""))
    tray_model = Path(str(inputs.get("tray_model") or ""))
    label_model = Path(str(inputs.get("label_model") or ""))
    for key, path in (
        ("manifest", manifest), ("tray_model", tray_model), ("label_model", label_model),
    ):
        if not path.is_file():
            raise FileNotFoundError(f"{key}: {path}")
        expected = str(inputs.get(f"{key}_sha256") or "")
        actual = diagnostics.sha256_file(path)
        if expected and actual != expected:
            raise RuntimeError(f"{key} hash mismatch: {path}")
    return manifest, tray_model, label_model


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo-root", required=True, type=Path)
    parser.add_argument("--baseline-receipt", required=True, type=Path)
    parser.add_argument(
        "--tray-model", type=Path,
        help="optional offline candidate tray model; the baseline receipt still binds records and label model",
    )
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--pad-ratios", type=parse_float_list, default=[0.0, 0.5, 1.0, 2.0])
    parser.add_argument("--thresholds", type=parse_float_list, default=[0.60, 0.30])
    parser.add_argument("--photo-id", action="append", default=[])
    return parser


def main() -> None:
    args = build_parser().parse_args()
    args.repo_root = args.repo_root.resolve()
    args.baseline_receipt = args.baseline_receipt.resolve()
    output = args.output.resolve()
    if not args.baseline_receipt.is_file():
        raise FileNotFoundError(args.baseline_receipt)
    if output.exists():
        raise FileExistsError(f"refusing to overwrite zoom oracle output: {output}")

    receipt = json.loads(args.baseline_receipt.read_text(encoding="utf-8"))
    manifest, baseline_tray_model, label_model = _validate_receipt_inputs(receipt)
    tray_model = args.tray_model.resolve() if args.tray_model else baseline_tray_model
    if not tray_model.is_file():
        raise FileNotFoundError(tray_model)
    records = diagnostics.load_records(manifest)
    records_by_id = {row["photo_id"]: row for row in records}
    missed = [
        row for row in (receipt.get("baseline", {}).get("details") or [])
        if row.get("kind") == "defect" and row.get("tray_target_covered") is False
    ]
    requested_ids = set(args.photo_id)
    if requested_ids:
        missed = [row for row in missed if row.get("photo_id") in requested_ids]
        absent = requested_ids - {str(row.get("photo_id")) for row in missed}
        if absent:
            raise ValueError(f"requested photo ids are not baseline tray misses: {sorted(absent)}")
    if not missed:
        raise ValueError("baseline receipt has no selected defect tray misses")

    sys.path.insert(0, str((args.repo_root / "backend" / "python").resolve()))
    from label_qc.services import yolo_detector as yolo

    rows: list[dict[str, Any]] = []
    with tempfile.TemporaryDirectory(prefix="label-qc-tray-zoom-models-") as temporary:
        model_dir = Path(temporary)
        shutil.copy2(tray_model, model_dir / "tray.onnx")
        shutil.copy2(label_model, model_dir / "label.onnx")
        models = yolo.LabelQcYoloModels(model_dir=model_dir)
        if not models.available:
            raise RuntimeError(models.load_error or "ONNX models unavailable")
        for baseline_row in missed:
            photo_id = str(baseline_row["photo_id"])
            source = records_by_id[photo_id]
            with Image.open(source["image"]) as opened:
                frame = np.array(ImageOps.exif_transpose(opened).convert("RGB"))
            height, width = frame.shape[:2]
            truth_box = diagnostics.truth_box_pixels(source, width, height)
            if truth_box is None:
                raise ValueError(f"missing truth bbox: {photo_id}")
            settings: list[dict[str, Any]] = []
            for threshold in args.thresholds:
                for pad_ratio in args.pad_ratios:
                    expanded = expand_box(truth_box, width, height, pad_ratio)
                    crop_rect = integer_crop_rect(expanded, width, height)
                    crop = frame[
                        crop_rect[1]:crop_rect[3], crop_rect[0]:crop_rect[2],
                    ]
                    detections = models.detect_trays(crop, float(threshold))
                    measured = measure_detections(detections, crop_rect, truth_box)
                    settings.append({
                        "threshold": float(threshold),
                        "pad_ratio": float(pad_ratio),
                        "crop_rect": crop_rect,
                        "crop_size": [int(crop.shape[1]), int(crop.shape[0])],
                        "linear_zoom_vs_full_frame": round(
                            max(width, height) / max(1, max(crop.shape[1], crop.shape[0])),
                            6,
                        ),
                        **measured,
                    })
            target_width = float(truth_box[2]) - float(truth_box[0])
            target_height = float(truth_box[3]) - float(truth_box[1])
            baseline_threshold = float(
                receipt.get("inputs", {}).get("params", {}).get("tray_threshold", 0.60)
            )
            summary = apply_human_review_gate(
                classify_zoom_result(settings, baseline_threshold),
                baseline_row.get("issues") or [],
            )
            rows.append({
                "photo_id": photo_id,
                "task_id": baseline_row.get("task_id"),
                "image": str(source["image"]),
                "image_sha256": source["image_sha256"],
                "protected_truth": baseline_row.get("protected_truth"),
                "truth_box": diagnostics._round_box(truth_box),
                "truth_box_geometry": {
                    "width": round(target_width, 3),
                    "height": round(target_height, 3),
                    "long_to_short_ratio": round(
                        max(target_width, target_height) / max(1.0, min(target_width, target_height)),
                        6,
                    ),
                },
                "baseline_best_truth_containment": baseline_row.get("best_truth_containment"),
                "baseline_primary_stage": baseline_row.get("primary_stage"),
                "baseline_issues": baseline_row.get("issues") or [],
                "settings": settings,
                "summary": summary,
            })

    output.mkdir(parents=True)
    payload = {
        "version": "label-qc-tray-zoom-oracle-v1",
        "created_at": diagnostics.utc_now(),
        "purpose": "diagnose scale versus target-instance semantics for baseline tray misses",
        "repo_head": diagnostics.git_head(args.repo_root),
        "oracle_uses_protected_target_crop": True,
        "production_path_allowed": False,
        "promotion_allowed": False,
        "training_started": False,
        "deployment_started": False,
        "production_writes": 0,
        "originals_modified": 0,
        "inputs": {
            "baseline_receipt": str(args.baseline_receipt),
            "baseline_receipt_sha256": diagnostics.sha256_file(args.baseline_receipt),
            "manifest": str(manifest),
            "manifest_sha256": diagnostics.sha256_file(manifest),
            "tray_model": str(tray_model),
            "tray_model_sha256": diagnostics.sha256_file(tray_model),
            "tray_model_role": "offline_candidate" if args.tray_model else "baseline_production",
            "baseline_tray_model": str(baseline_tray_model),
            "baseline_tray_model_sha256": diagnostics.sha256_file(baseline_tray_model),
            "label_model": str(label_model),
            "label_model_sha256": diagnostics.sha256_file(label_model),
            "pad_ratios": args.pad_ratios,
            "thresholds": args.thresholds,
            "execution_provider": "CPUExecutionProvider",
            "onnx_threads": int(os.getenv("LABEL_QC_ONNX_THREADS", "2")),
        },
        "rows": rows,
        "aggregate": {
            "selected_misses": len(rows),
            "interpretations": dict(Counter(
                row["summary"]["interpretation"] for row in rows
            )),
            "requires_full_image_instance_human_review": [
                row["photo_id"] for row in rows
                if row["summary"]["requires_full_image_instance_human_review"]
            ],
        },
        "notes": [
            "This oracle knows the protected target location and cannot be deployed.",
            "Multiple detections inside a protected box are only a review signal, not proof of incorrect truth.",
            "Only reviewed=true, source=human full-image per-tray annotations may redefine instance truth.",
        ],
    }
    receipt_path = output / "receipt.json"
    receipt_path.write_text(
        json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8",
    )
    print(json.dumps({
        "receipt": str(receipt_path),
        "aggregate": payload["aggregate"],
        "rows": [
            {
                "photo_id": row["photo_id"],
                "interpretation": row["summary"]["interpretation"],
                "best_setting": row["summary"]["best_setting"],
            }
            for row in rows
        ],
        "production_writes": 0,
        "originals_modified": 0,
    }, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
