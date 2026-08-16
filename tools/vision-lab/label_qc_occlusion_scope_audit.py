#!/usr/bin/env python3
"""Audit legacy defect targets against judgeable human tray instances.

Unboxed, occluded lower layers are ignore regions: they are neither negatives
nor tray-detector misses.  The audit is append-only and never changes the
protected manifest or the user's annotation files.
"""
from __future__ import annotations

import argparse
import json
from collections import Counter
from pathlib import Path
from typing import Any, Sequence

import label_qc_oracle_diagnostics as diagnostics


def validate_box(box: Sequence[Any]) -> list[float]:
    if len(box) != 4:
        raise ValueError(f"box must have four coordinates: {box}")
    values = [float(value) for value in box]
    if not (
        0.0 <= values[0] < values[2] <= 1.0
        and 0.0 <= values[1] < values[3] <= 1.0
    ):
        raise ValueError(f"invalid normalised box: {box}")
    return values


def normalise_pixel_box(
    box: Sequence[float], width: int, height: int,
) -> list[float]:
    return validate_box([
        float(box[0]) / width,
        float(box[1]) / height,
        float(box[2]) / width,
        float(box[3]) / height,
    ])


def match_legacy_target(
    legacy_target: Sequence[float], human_boxes: Sequence[Sequence[float]],
    iou_threshold: float = 0.50, ios_threshold: float = 0.80,
    minimum_area_ratio: float = 0.50,
) -> dict[str, Any]:
    legacy_area = (
        (float(legacy_target[2]) - float(legacy_target[0]))
        * (float(legacy_target[3]) - float(legacy_target[1]))
    )
    candidates: list[dict[str, Any]] = []
    for index, source_box in enumerate(human_boxes):
        box = validate_box(source_box)
        human_area = (box[2] - box[0]) * (box[3] - box[1])
        candidates.append({
            "human_box_index": index,
            "box": diagnostics._round_box(box, digits=8),
            "iou": round(diagnostics.box_iou(legacy_target, box), 6),
            "intersection_over_smaller": round(
                diagnostics.intersection_over_smaller(legacy_target, box), 6,
            ),
            "area_ratio": round(
                min(legacy_area, human_area) / max(legacy_area, human_area), 6,
            ),
        })
    candidates.sort(
        key=lambda row: (row["iou"], row["intersection_over_smaller"]),
        reverse=True,
    )
    best = candidates[0] if candidates else None
    matched = bool(best and (
        best["iou"] >= iou_threshold
        or (
            best["intersection_over_smaller"] >= ios_threshold
            and best["area_ratio"] >= minimum_area_ratio
        )
    ))
    return {
        "matched": matched,
        "best": best,
        "iou_threshold": iou_threshold,
        "intersection_over_smaller_threshold": ios_threshold,
        "minimum_area_ratio": minimum_area_ratio,
        "candidate_count": len(candidates),
    }


def classify_target(match: dict[str, Any], baseline_issues: Sequence[str]) -> dict[str, Any]:
    if not match["matched"]:
        return {
            "status": "OCCLUDED_OR_INVALID_LEGACY_TARGET_IGNORE",
            "counted_as_tray_detector_miss": False,
            "counted_as_negative": False,
            "eligible_for_training": False,
            "requires_new_judgeable_human_truth": True,
        }
    if "protected_truth_conflicts_with_human_crop" in baseline_issues:
        return {
            "status": "JUDGEABLE_TRAY_LABEL_TRUTH_CONFLICT_PENDING",
            "counted_as_tray_detector_miss": False,
            "counted_as_negative": False,
            "eligible_for_training": False,
            "requires_new_judgeable_human_truth": True,
        }
    return {
        "status": "JUDGEABLE_HUMAN_TRAY_MATCH",
        "counted_as_tray_detector_miss": True,
        "counted_as_negative": False,
        "eligible_for_training": False,
        "requires_new_judgeable_human_truth": False,
    }


def build_decision(remaining_attributed_misses: int) -> dict[str, Any]:
    if remaining_attributed_misses:
        return {
            "train_new_model": False,
            "rent_gpu": False,
            "change_production_threshold": False,
            "reason": (
                f"{remaining_attributed_misses} reviewed judgeable tray-detector miss remains, "
                "but protected targets cannot enter training and the scale/threshold oracle did not recover it"
            ),
            "next_gate": (
                "mine at least two independent train-only human-reviewed analogues from existing queues "
                "before any candidate training"
            ),
        }
    return {
        "train_new_model": False,
        "rent_gpu": False,
        "change_production_threshold": False,
        "reason": "no reviewed judgeable tray-detector miss remains after occlusion scope audit",
        "next_gate": "collect fresh judgeable per-tray real defects with both label positions visible",
    }


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--queue", required=True, type=Path)
    parser.add_argument("--baseline-receipt", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--iou-threshold", type=float, default=0.50)
    parser.add_argument("--ios-threshold", type=float, default=0.80)
    parser.add_argument("--supersedes", action="append", default=[], type=Path)
    return parser


def main() -> None:
    args = build_parser().parse_args()
    queue = args.queue.resolve()
    baseline_path = args.baseline_receipt.resolve()
    output = args.output.resolve()
    if output.exists():
        raise FileExistsError(f"refusing to overwrite occlusion audit: {output}")
    for previous in args.supersedes:
        if not previous.is_file():
            raise FileNotFoundError(previous)
    manifest_path = queue / "manifest.json"
    if not manifest_path.is_file() or not baseline_path.is_file():
        raise FileNotFoundError(manifest_path if not manifest_path.is_file() else baseline_path)
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    baseline = json.loads(baseline_path.read_text(encoding="utf-8"))
    baseline_by_id = {
        str(row["photo_id"]): row
        for row in baseline.get("baseline", {}).get("details") or []
    }

    rows: list[dict[str, Any]] = []
    for queue_row in manifest.get("rows") or []:
        photo_id = str(queue_row["photo_id"])
        baseline_row = baseline_by_id[photo_id]
        annotation_path = queue / "annotations-human" / f"{queue_row['packed_stem']}.json"
        annotation = json.loads(annotation_path.read_text(encoding="utf-8"))
        if annotation.get("reviewed") is not True or annotation.get("source") != "human":
            raise RuntimeError(f"annotation is not reviewed human truth: {annotation_path}")
        boxes = annotation.get("boxes")
        if not isinstance(boxes, list):
            raise RuntimeError(f"annotation boxes are invalid: {annotation_path}")
        width, height = (int(value) for value in queue_row["original_size"])
        target = normalise_pixel_box(
            queue_row["legacy_protected_truth_box"], width, height,
        )
        match = match_legacy_target(
            target,
            boxes,
            iou_threshold=args.iou_threshold,
            ios_threshold=args.ios_threshold,
        )
        classification = classify_target(
            match, baseline_row.get("issues") or [],
        )
        rows.append({
            "photo_id": photo_id,
            "task_id": queue_row.get("task_id"),
            "protected_truth": baseline_row.get("protected_truth"),
            "legacy_target_normalised": diagnostics._round_box(target, digits=8),
            "annotation": str(annotation_path),
            "annotation_sha256": diagnostics.sha256_file(annotation_path),
            "human_box_count": len(boxes),
            "human_annotation_scope": "judgeable_visible_top_layer_instances",
            "unboxed_occluded_layers": "ignore_not_negative",
            "match": match,
            "baseline_primary_stage": baseline_row.get("primary_stage"),
            "baseline_issues": baseline_row.get("issues") or [],
            **classification,
        })

    status_counts = Counter(row["status"] for row in rows)
    baseline_defects = [
        row for row in baseline.get("baseline", {}).get("details") or []
        if row.get("kind") == "defect"
    ]
    unresolved_ids = [
        row["photo_id"] for row in rows if row["requires_new_judgeable_human_truth"]
    ]
    remaining_attributed_misses = sum(
        int(row["counted_as_tray_detector_miss"]) for row in rows
    )
    payload = {
        "version": "label-qc-occlusion-scope-audit-v1",
        "created_at": diagnostics.utc_now(),
        "purpose": "prevent occluded unboxed trays from becoming negatives or detector misses",
        "human_scope_decision": {
            "source": "human_current_review_session",
            "decision": "annotate judgeable visible top-layer trays; do not infer hidden full boxes",
            "occluded_policy": "ignore_or_unjudgeable",
            "visible_lower_layer_labels": {
                "annotation_allowed": True,
                "dataset": "separate_label_side_view_presence_only",
                "parent_tray_id": None,
                "occluded_parent": True,
                "tray_training_allowed": False,
                "missing_verdict_allowed": False,
            },
        },
        "inputs": {
            "queue_manifest": str(manifest_path),
            "queue_manifest_sha256": diagnostics.sha256_file(manifest_path),
            "baseline_receipt": str(baseline_path),
            "baseline_receipt_sha256": diagnostics.sha256_file(baseline_path),
            "iou_threshold": args.iou_threshold,
            "intersection_over_smaller_threshold": args.ios_threshold,
        },
        "rows": rows,
        "aggregate": {
            "protected_defects_in_baseline": len(baseline_defects),
            "baseline_hits": sum(int(row.get("hit") is True) for row in baseline_defects),
            "reviewed_miss_targets": len(rows),
            "status_counts": dict(status_counts),
            "remaining_attributed_tray_detector_misses": remaining_attributed_misses,
            "unresolved_truth_photo_ids": unresolved_ids,
        },
        "decision": build_decision(remaining_attributed_misses),
        "supersedes_receipts": [
            {
                "path": str(path.resolve()),
                "sha256": diagnostics.sha256_file(path),
                "reason": "decision text contradicted remaining_attributed_tray_detector_misses",
            }
            for path in args.supersedes
        ],
        "protected_manifest_modified": False,
        "annotations_modified": False,
        "production_writes": 0,
        "originals_modified": 0,
    }
    output.mkdir(parents=True)
    receipt = output / "receipt.json"
    receipt.write_text(
        json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8",
    )
    print(json.dumps({
        "receipt": str(receipt),
        "aggregate": payload["aggregate"],
        "decision": payload["decision"],
        "annotations_modified": False,
        "production_writes": 0,
        "originals_modified": 0,
    }, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
