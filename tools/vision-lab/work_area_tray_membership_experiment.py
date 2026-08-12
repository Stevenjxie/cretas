#!/usr/bin/env python3
"""Evaluate per-tray work-area membership without predicting an ROI polygon.

Human four-point polygons generate training truth only.  At inference the
candidate combines a geometry classifier with agreement across training-set
human polygons.  A tray is automatic only when both independent signals pass
fixed asymmetric confidence gates; every other tray fails closed as
``unknown_work_area``.  This is an offline, no-save rejection gate.
"""
from __future__ import annotations

import argparse
import datetime as dt
import json
from pathlib import Path
from typing import Any

import numpy as np
from sklearn.ensemble import ExtraTreesClassifier

import work_area_roi_experiment as common


INSIDE = common.work_area.INSIDE_WORK_AREA
OUTSIDE = common.work_area.OUTSIDE_WORK_AREA
UNKNOWN = common.work_area.UNKNOWN_WORK_AREA
REQUIRED_TASK_ID = "df1f6029-389d-45b5-995e-be19b2f5b943"
INSIDE_PROBABILITY = 0.95
OUTSIDE_PROBABILITY = 0.10
INSIDE_CONSENSUS = 0.95
OUTSIDE_CONSENSUS = 0.10


def geometry_features(box: Any) -> np.ndarray:
    x0, y0, x1, y1 = common.work_area.validate_box(box)
    center_x, center_y = (x0 + x1) / 2, (y0 + y1) / 2
    width, height = x1 - x0, y1 - y0
    return np.asarray([
        x0, y0, x1, y1, center_x, center_y, width, height, width * height,
        min(center_x, 1 - center_x), min(center_y, 1 - center_y),
    ], dtype=np.float32)


def arrangement_features(boxes: list[Any], index: int) -> np.ndarray:
    """Describe one tray relative to every tray proposal in the same image."""
    normalized = np.asarray([common.work_area.validate_box(box) for box in boxes])
    if not 0 <= index < len(normalized):
        raise IndexError("tray index is outside the image proposal list")
    centers = np.column_stack((
        (normalized[:, 0] + normalized[:, 2]) / 2,
        (normalized[:, 1] + normalized[:, 3]) / 2,
    ))
    sizes = normalized[:, 2:] - normalized[:, :2]
    center_x, center_y = centers[index]
    width, height = sizes[index]
    distances = np.linalg.norm(centers - centers[index], axis=1)
    neighbors = np.sort(distances[distances > 0]).tolist()
    neighbors = (neighbors + [2.0] * 5)[:5]
    count = len(normalized)
    rank_x = (np.sum(centers[:, 0] < center_x) + 0.5 * np.sum(centers[:, 0] == center_x)) / count
    rank_y = (np.sum(centers[:, 1] < center_y) + 0.5 * np.sum(centers[:, 1] == center_y)) / count
    same_row = np.abs(centers[:, 1] - center_y) < max(0.04, height * 0.6)
    same_column = np.abs(centers[:, 0] - center_x) < max(0.04, width * 0.6)
    contextual = [
        count / 30, rank_x, rank_y, min(rank_x, 1 - rank_x), min(rank_y, 1 - rank_y),
        *neighbors,
        np.sum(same_row & (centers[:, 0] < center_x)) / count,
        np.sum(same_row & (centers[:, 0] > center_x)) / count,
        np.sum(same_column & (centers[:, 1] < center_y)) / count,
        np.sum(same_column & (centers[:, 1] > center_y)) / count,
        width / np.median(sizes[:, 0]), height / np.median(sizes[:, 1]),
        center_x - np.mean(centers[:, 0]), center_y - np.mean(centers[:, 1]),
        center_x - np.min(centers[:, 0]), np.max(centers[:, 0]) - center_x,
        center_y - np.min(centers[:, 1]), np.max(centers[:, 1]) - center_y,
    ]
    return np.concatenate((geometry_features(normalized[index].tolist()), contextual)).astype(np.float32)


def decision(
    inside_probability: float, polygon_consensus: float,
    *, inside_probability_gate: float = INSIDE_PROBABILITY,
    outside_probability_gate: float = OUTSIDE_PROBABILITY,
    inside_consensus_gate: float = INSIDE_CONSENSUS,
    outside_consensus_gate: float = OUTSIDE_CONSENSUS,
) -> str:
    values = (
        inside_probability, polygon_consensus, inside_probability_gate,
        outside_probability_gate, inside_consensus_gate, outside_consensus_gate,
    )
    if not all(np.isfinite(value) and 0 <= value <= 1 for value in values):
        raise ValueError("membership probabilities and gates must be finite in [0, 1]")
    if inside_probability >= inside_probability_gate and polygon_consensus >= inside_consensus_gate:
        return INSIDE
    if inside_probability <= outside_probability_gate and polygon_consensus <= outside_consensus_gate:
        return OUTSIDE
    return UNKNOWN


def summarize(rows: list[dict[str, Any]]) -> dict[str, Any]:
    truth_counts = {label: sum(row["truth"] == label for row in rows) for label in (INSIDE, OUTSIDE)}
    decision_counts = {
        label: sum(row["decision"] == label for row in rows)
        for label in (INSIDE, OUTSIDE, UNKNOWN)
    }
    automatic = [row for row in rows if row["decision"] != UNKNOWN]
    errors = [row for row in automatic if row["decision"] != row["truth"]]
    return {
        "tray_centers": len(rows),
        "truth_counts": truth_counts,
        "decision_counts": decision_counts,
        "automatic_coverage": len(automatic) / max(len(rows), 1),
        "unknown_count": decision_counts[UNKNOWN],
        "automatic_errors": len(errors),
        "outside_as_inside_errors": sum(
            row["truth"] == OUTSIDE and row["decision"] == INSIDE for row in rows
        ),
        "inside_as_outside_errors": sum(
            row["truth"] == INSIDE and row["decision"] == OUTSIDE for row in rows
        ),
        "inside_recall_over_all_truth": sum(
            row["truth"] == INSIDE and row["decision"] == INSIDE for row in rows
        ) / max(truth_counts[INSIDE], 1),
        "outside_recall_over_all_truth": sum(
            row["truth"] == OUTSIDE and row["decision"] == OUTSIDE for row in rows
        ) / max(truth_counts[OUTSIDE], 1),
    }


def _queue_by_stem(queues: list[Path]) -> dict[str, str]:
    result: dict[str, str] = {}
    for queue in queues:
        manifest = common.load_json(queue / "manifest.json")
        if manifest.get("protected_holdout_included"):
            raise RuntimeError(f"protected holdout queue is forbidden: {queue}")
        rows = manifest.get("rows") or []
        if len(rows) != int(manifest.get("queue_count", -1)):
            raise RuntimeError(f"membership queue manifest count drift: {queue}")
        for row in rows:
            stem = str(row.get("packed_stem") or "")
            if not stem or stem in result:
                raise RuntimeError(f"missing or duplicate membership stem: {stem}")
            result[stem] = str(queue)
    return result


def run(queues: list[Path], *, fold_count: int) -> dict[str, Any]:
    _, samples = common.load_combined_samples(queues)
    queue_by_stem = _queue_by_stem(queues)
    features: list[np.ndarray] = []
    labels: list[int] = []
    sample_indices: list[int] = []
    boxes: list[list[float]] = []
    for sample_index, sample in enumerate(samples):
        for box_index, box in enumerate(sample["boxes"]):
            normalized = common.work_area.validate_box(box)
            features.append(arrangement_features(sample["boxes"], box_index))
            boxes.append(normalized)
            labels.append(int(common.work_area.classify_box_center(normalized, sample["polygon"]) == INSIDE))
            sample_indices.append(sample_index)
    x = np.stack(features)
    y = np.asarray(labels, dtype=np.int8)
    groups = np.asarray(sample_indices, dtype=np.int32)
    rows: list[dict[str, Any]] = []
    splits = common.task_grouped_splits(samples, fold_count)
    for fold, (train_samples, held_samples) in enumerate(splits, start=1):
        train = np.isin(groups, train_samples)
        held = np.isin(groups, held_samples)
        classifier = ExtraTreesClassifier(
            n_estimators=500, min_samples_leaf=2, max_features="sqrt",
            class_weight="balanced", n_jobs=-1, random_state=20260813,
        )
        classifier.fit(x[train], y[train])
        held_indices = np.where(held)[0]
        probabilities = classifier.predict_proba(x[held])[:, 1]
        for position, tray_index in enumerate(held_indices):
            sample = samples[int(groups[tray_index])]
            center = (float(x[tray_index, 4]), float(x[tray_index, 5]))
            consensus = float(np.mean([
                common.work_area.point_in_polygon(center, samples[index]["polygon"])
                for index in train_samples
            ]))
            predicted = decision(float(probabilities[position]), consensus)
            truth = INSIDE if y[tray_index] else OUTSIDE
            rows.append({
                "fold": fold,
                "task_id": sample["task_id"],
                "photo_id": sample["source_photo_id"],
                "sku_code": sample["sku_code"],
                "queue": queue_by_stem[sample["stem"]],
                "tray_index": int(np.sum(groups[:tray_index] == groups[tray_index])),
                "box": boxes[tray_index],
                "truth": truth,
                "inside_probability": float(probabilities[position]),
                "training_polygon_consensus": consensus,
                "decision": predicted,
            })
    thresholds = {
        "min_automatic_coverage": 0.80,
        "min_inside_recall_over_all_truth": 0.95,
        "min_outside_recall_over_all_truth": 0.95,
        "max_outside_as_inside_errors": 0,
        "max_inside_as_outside_errors": 0,
    }
    summary = summarize(rows)
    fold_summaries = {
        str(fold): summarize([row for row in rows if row["fold"] == fold])
        for fold in range(1, len(splits) + 1)
    }
    sku_summaries = {
        sku: summarize([row for row in rows if row["sku_code"] == sku])
        for sku in sorted({row["sku_code"] for row in rows})
    }
    queue_summaries = {
        queue: summarize([row for row in rows if row["queue"] == queue])
        for queue in sorted({row["queue"] for row in rows})
    }
    passed = (
        summary["automatic_coverage"] >= thresholds["min_automatic_coverage"]
        and summary["inside_recall_over_all_truth"] >= thresholds["min_inside_recall_over_all_truth"]
        and summary["outside_recall_over_all_truth"] >= thresholds["min_outside_recall_over_all_truth"]
        and summary["outside_as_inside_errors"] <= thresholds["max_outside_as_inside_errors"]
        and summary["inside_as_outside_errors"] <= thresholds["max_inside_as_outside_errors"]
        and all(item["automatic_errors"] == 0 for item in fold_summaries.values())
        and all(item["automatic_errors"] == 0 for item in sku_summaries.values())
    )
    return {
        "version": "vision-lab-work-area-tray-membership-v1",
        "created_at": dt.datetime.now(dt.timezone.utc).isoformat(),
        "experiment_script_sha256": common.sha256(Path(__file__)),
        "common_script_sha256": common.sha256(Path(common.__file__)),
        "sklearn_version": __import__("sklearn").__version__,
        "mode": "deterministic-task-grouped-per-tray-membership",
        "queues": [str(queue) for queue in queues],
        "queue_manifest_sha256s": {
            str(queue): common.sha256(queue / "manifest.json") for queue in queues
        },
        "sample_count": len(samples),
        "task_count": len({sample["task_id"] for sample in samples}),
        "tray_center_count": len(rows),
        "folds": len(splits),
        "split": "deterministic-sku-stratified-task-k-fold",
        "algorithm": "extra-trees-geometry-and-tray-arrangement-plus-training-polygon-consensus",
        "tray_box_source": "human-reviewed-oracle-upper-bound-not-detector-proposals",
        "runtime_roi_prediction": False,
        "human_polygon_role": "training-truth-and-training-fold-consensus-only",
        "evaluation_limitations": [
            "confidence gates were selected during exploratory work on this same 108-image pool",
            "human-reviewed tray boxes make this an optimistic upper bound for detector-time membership",
            "no locked independent test or protected holdout was used because the pre-gate failed",
        ],
        "confidence_gates": {
            "inside_probability_min": INSIDE_PROBABILITY,
            "outside_probability_max": OUTSIDE_PROBABILITY,
            "inside_training_polygon_consensus_min": INSIDE_CONSENSUS,
            "outside_training_polygon_consensus_max": OUTSIDE_CONSENSUS,
            "otherwise": UNKNOWN,
        },
        "thresholds": thresholds,
        "summary": summary,
        "fold_summaries": fold_summaries,
        "sku_summaries": sku_summaries,
        "queue_summaries": queue_summaries,
        "required_df1f_case": [row for row in rows if row["task_id"] == REQUIRED_TASK_ID],
        "rows": rows,
        "passed": passed,
        "sufficient_for_production": False,
        "deployment_authorized": False,
        "conclusion": (
            "offline_candidate_signal_only_locked_test_still_required"
            if passed else "per_tray_membership_insufficient_fail_closed"
        ),
        "pretrained_weights": None,
        "downloaded_weights": False,
        "cloud_calls": 0,
        "production_reads": 0,
        "production_writes": 0,
        "registry_writes": 0,
        "protected_holdout_used": False,
        "originals_modified": False,
        "model_saved": False,
    }


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--queue", required=True, action="append", type=Path)
    parser.add_argument("--runtime-root", required=True, type=Path)
    parser.add_argument("--folds", type=int, default=8)
    args = parser.parse_args()
    if args.folds < 2:
        parser.error("folds must be at least two")
    receipt = run([path.resolve() for path in args.queue], fold_count=args.folds)
    args.runtime_root.mkdir(parents=True, exist_ok=True)
    timestamp = dt.datetime.now(dt.timezone.utc).strftime("%Y%m%dT%H%M%S%fZ")
    path = args.runtime_root / f"work-area-tray-membership-experiment-{timestamp}.json"
    path.write_text(json.dumps(receipt, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({
        "receipt": str(path), "passed": receipt["passed"],
        "summary": receipt["summary"], "conclusion": receipt["conclusion"],
    }, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
