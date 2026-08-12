#!/usr/bin/env python3
"""Audit a failed work-area corner CV receipt without training or data writes."""
from __future__ import annotations

import argparse
import collections
import datetime as dt
import json
from pathlib import Path
from typing import Any

import numpy as np

import work_area_roi_experiment as common
import work_area_roi_quad_experiment as quad


COLLAPSE_DISTANCE = 0.03
AREA_COLLAPSE_RATIO = 0.70
AREA_EXPANSION_RATIO = 1.30
CORNER_OUTLIER_DISTANCE = 0.20


def polygon_area(points: np.ndarray) -> float:
    return float(abs(0.5 * np.sum(
        points[:, 0] * np.roll(points[:, 1], -1)
        - np.roll(points[:, 0], -1) * points[:, 1]
    )))


def geometry_diagnostics(predicted: Any, truth: Any) -> dict[str, Any]:
    predicted_points = np.asarray(predicted, dtype=np.float64)
    truth_points = quad.canonicalize_polygon(truth).astype(np.float64)
    if predicted_points.shape != (4, 2) or not np.isfinite(predicted_points).all():
        raise ValueError("predicted polygon must contain four finite x/y points")
    corner_errors = np.linalg.norm(predicted_points - truth_points, axis=1)
    adjacent_distances = np.linalg.norm(
        np.roll(predicted_points, -1, axis=0) - predicted_points, axis=1,
    )
    truth_area = polygon_area(truth_points)
    predicted_area = polygon_area(predicted_points)
    area_ratio = predicted_area / max(truth_area, 1e-12)
    try:
        common.work_area.validate_polygon(predicted_points.tolist())
        valid_polygon = True
    except (TypeError, ValueError):
        valid_polygon = False
    ordered_errors = np.sort(corner_errors)[::-1]
    tags = []
    if not valid_polygon:
        tags.append("invalid_polygon")
    if float(adjacent_distances.min()) < COLLAPSE_DISTANCE:
        tags.append("adjacent_corner_collapse")
    if area_ratio < AREA_COLLAPSE_RATIO:
        tags.append("area_collapse")
    elif area_ratio > AREA_EXPANSION_RATIO:
        tags.append("area_expansion")
    if ordered_errors[0] >= CORNER_OUTLIER_DISTANCE and ordered_errors[1] < CORNER_OUTLIER_DISTANCE:
        tags.append("single_corner_outlier")
    return {
        "valid_polygon": valid_polygon,
        "truth_area": truth_area,
        "predicted_area": predicted_area,
        "area_ratio": area_ratio,
        "corner_errors": corner_errors.tolist(),
        "max_corner_error": float(corner_errors.max()),
        "min_adjacent_corner_distance": float(adjacent_distances.min()),
        "tags": tags,
    }


def grouped_summary(rows: list[dict[str, Any]], key: str) -> dict[str, Any]:
    grouped: dict[str, list[dict[str, Any]]] = collections.defaultdict(list)
    for row in rows:
        grouped[str(row[key])].append(row)
    return {
        group: {
            "samples": len(values),
            "mean_iou": sum(value["iou"] for value in values) / len(values),
            "min_iou": min(value["iou"] for value in values),
            "center_errors": sum(value["center_errors"] for value in values),
            "invalid_polygons": sum(not value["geometry"]["valid_polygon"] for value in values),
            "adjacent_corner_collapses": sum(
                "adjacent_corner_collapse" in value["geometry"]["tags"] for value in values
            ),
            "area_collapses": sum(
                "area_collapse" in value["geometry"]["tags"] for value in values
            ),
        }
        for group, values in sorted(grouped.items())
    }


def run(experiment_receipt: Path) -> dict[str, Any]:
    receipt = common.load_json(experiment_receipt)
    if receipt.get("mode") != "task-grouped-cross-validation":
        raise RuntimeError("failure audit requires a task-grouped cross-validation receipt")
    if receipt.get("passed") is not False or receipt.get("sufficient_for_production") is not False:
        raise RuntimeError("failure audit only accepts a failed, production-insufficient receipt")
    queues = [Path(value) for value in receipt.get("queues") or []]
    for queue in queues:
        expected = (receipt.get("queue_manifest_sha256s") or {}).get(str(queue))
        if not expected or common.sha256(queue / "manifest.json") != expected:
            raise RuntimeError(f"queue manifest drift: {queue}")
    _manifests, samples = common.load_combined_samples(queues)
    sample_by_photo = {sample["source_photo_id"]: sample for sample in samples}
    receipt_samples = {sample["source_photo_id"]: sample for sample in receipt.get("samples") or []}
    if set(sample_by_photo) != set(receipt_samples):
        raise RuntimeError("receipt sample set does not match the reviewed queues")
    for photo_id, sample in sample_by_photo.items():
        sealed = receipt_samples[photo_id]
        for key in ("task_id", "sku_code", "image_sha256", "source_sha256", "roi_sha256"):
            if str(sample[key]) != str(sealed.get(key)):
                raise RuntimeError(f"receipt sample binding drift for {photo_id}: {key}")
    source_rows = receipt.get("rows") or []
    held_ids = [str(row.get("held_out_photo_id")) for row in source_rows]
    if len(source_rows) != len(samples) or len(set(held_ids)) != len(samples):
        raise RuntimeError("CV rows must contain every reviewed photo exactly once")
    rows = []
    for source_row in source_rows:
        photo_id = str(source_row["held_out_photo_id"])
        sample = sample_by_photo.get(photo_id)
        if sample is None or str(source_row.get("held_out_task_id")) != sample["task_id"]:
            raise RuntimeError(f"held-out task binding drift: {photo_id}")
        metrics = source_row.get("mask") or {}
        centers = source_row.get("centers") or {}
        rows.append({
            "fold": int(source_row["fold"]),
            "task_id": sample["task_id"],
            "source_photo_id": photo_id,
            "sku_code": sample["sku_code"],
            "iou": float(metrics["iou"]),
            "center_errors": int(centers["errors"]),
            "inside_recall": float(centers["inside_recall"]),
            "outside_recall": float(centers["outside_recall"]),
            "geometry": geometry_diagnostics(
                source_row.get("predicted_polygon"), sample["polygon"],
            ),
        })
    tag_counts = collections.Counter(
        tag for row in rows for tag in row["geometry"]["tags"]
    )
    worst = sorted(rows, key=lambda row: (-row["center_errors"], row["iou"]))[:20]
    return {
        "version": "vision-lab-work-area-roi-failure-audit-v1",
        "created_at": dt.datetime.now(dt.timezone.utc).isoformat(),
        "experiment_receipt": str(experiment_receipt),
        "experiment_receipt_sha256": common.sha256(experiment_receipt),
        "experiment_script_sha256": receipt.get("experiment_script_sha256"),
        "audit_script_sha256": common.sha256(Path(__file__)),
        "queue_manifest_sha256s": receipt["queue_manifest_sha256s"],
        "sample_count": len(rows),
        "thresholds": {
            "adjacent_corner_collapse_distance": COLLAPSE_DISTANCE,
            "area_collapse_ratio": AREA_COLLAPSE_RATIO,
            "area_expansion_ratio": AREA_EXPANSION_RATIO,
            "corner_outlier_distance": CORNER_OUTLIER_DISTANCE,
        },
        "tag_counts": dict(sorted(tag_counts.items())),
        "by_sku": grouped_summary(rows, "sku_code"),
        "by_fold": grouped_summary(rows, "fold"),
        "worst_rows": worst,
        "rows": rows,
        "conclusion": "corner_identity_and_global_instance_selection_failures",
        "training_started": False,
        "model_saved": False,
        "protected_holdout_used": False,
        "originals_modified": False,
        "production_reads": 0,
        "production_writes": 0,
    }


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--experiment-receipt", required=True, type=Path)
    parser.add_argument("--runtime-root", required=True, type=Path)
    args = parser.parse_args()
    audit = run(args.experiment_receipt)
    args.runtime_root.mkdir(parents=True, exist_ok=True)
    timestamp = dt.datetime.now(dt.timezone.utc).strftime("%Y%m%dT%H%M%S%fZ")
    path = args.runtime_root / f"work-area-roi-failure-audit-{timestamp}.json"
    path.write_text(json.dumps(audit, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({
        "receipt": str(path), "tag_counts": audit["tag_counts"],
        "conclusion": audit["conclusion"],
    }, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
