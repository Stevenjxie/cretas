#!/usr/bin/env python3
"""Evaluate proposal-masked template registration for human work-area ROIs.

This is an offline, no-save rejection gate.  Detector proposals are used only
to suppress repeated tray/label features before SIFT matching; they never
define, expand, or replace the human four-point work-area polygon.  A target
without a geometrically valid and sufficiently confident registration fails
closed as ``unknown_work_area``.
"""
from __future__ import annotations

import argparse
import datetime as dt
import json
from dataclasses import dataclass
from pathlib import Path
from typing import Any

import cv2
import numpy as np

import work_area_roi_experiment as common
import work_area_roi_quad_experiment as quad


DEFAULT_WIDTH = 384
DEFAULT_HEIGHT = 512
DEFAULT_CANDIDATES = 35
REQUIRED_TASK_ID = "df1f6029-389d-45b5-995e-be19b2f5b943"


@dataclass(frozen=True)
class RegistrationGate:
    min_inliers: int = 12
    min_inlier_ratio: float = 0.30
    max_median_reprojection_error: float = 2.5
    min_frame_area_scale: float = 0.45
    max_frame_area_scale: float = 1.80


def proposal_mask(
    proposals: list[dict[str, Any]], width: int, height: int,
    *, expand_x: float = 0.025, expand_y: float = 0.020,
) -> np.ndarray:
    """Return a feature mask that suppresses detector proposal rectangles."""
    if width <= 0 or height <= 0:
        raise ValueError("registration feature dimensions must be positive")
    mask = np.full((height, width), 255, dtype=np.uint8)
    for proposal in proposals:
        box = proposal.get("box") if isinstance(proposal, dict) else None
        x0, y0, x1, y1 = common.work_area.validate_box(box)
        left = max(0, int((x0 - expand_x) * width))
        top = max(0, int((y0 - expand_y) * height))
        right = min(width - 1, int((x1 + expand_x) * width))
        bottom = min(height - 1, int((y1 + expand_y) * height))
        cv2.rectangle(mask, (left, top), (right, bottom), 0, -1)
    return cv2.erode(mask, np.ones((5, 5), dtype=np.uint8))


def gate_registration(quality: dict[str, float | int], gate: RegistrationGate) -> bool:
    return (
        int(quality["inliers"]) >= gate.min_inliers
        and float(quality["inlier_ratio"]) >= gate.min_inlier_ratio
        and float(quality["median_reprojection_error"]) <= gate.max_median_reprojection_error
        and gate.min_frame_area_scale <= float(quality["frame_area_scale"])
        <= gate.max_frame_area_scale
    )


def transform_polygon(
    polygon: list[list[float]], homography: np.ndarray, width: int, height: int,
) -> np.ndarray:
    points = quad.canonicalize_polygon(polygon).astype(np.float32)
    pixels = points * np.asarray([width - 1, height - 1], dtype=np.float32)
    transformed = cv2.perspectiveTransform(pixels[None], homography)[0]
    transformed /= np.asarray([width - 1, height - 1], dtype=np.float32)
    if float(transformed.min()) < 0.0 or float(transformed.max()) > 1.0:
        raise ValueError("registered polygon leaves the image")
    return quad.canonicalize_polygon(transformed.tolist())


def load_rows(queues: list[Path]) -> tuple[list[dict[str, Any]], dict[str, dict[str, Any]]]:
    manifests: list[dict[str, Any]] = []
    rows_by_stem: dict[str, dict[str, Any]] = {}
    for queue in queues:
        manifest = common.load_json(queue / "manifest.json")
        if manifest.get("protected_holdout_included"):
            raise RuntimeError(f"protected holdout queue is forbidden: {queue}")
        rows = manifest.get("rows") or []
        if len(rows) != int(manifest.get("queue_count", -1)):
            raise RuntimeError(f"registration queue manifest count drift: {queue}")
        manifests.append(manifest)
        for row in rows:
            stem = str(row.get("packed_stem") or "")
            if not stem or stem in rows_by_stem:
                raise RuntimeError(f"missing or duplicate registration stem: {stem}")
            proposals = row.get("preannotations")
            if not isinstance(proposals, list):
                raise RuntimeError(f"detector proposal list is missing: {stem}")
            for proposal in proposals:
                if not isinstance(proposal, dict):
                    raise RuntimeError(f"invalid detector proposal: {stem}")
                common.work_area.validate_box(proposal.get("box"))
            rows_by_stem[stem] = {**row, "queue": str(queue)}
    return manifests, rows_by_stem


def extract_features(
    sample: dict[str, Any], row: dict[str, Any], sift,
    width: int, height: int,
) -> dict[str, Any]:
    rgb = (common.load_image(sample["image"], width, height) * 255).astype(np.uint8)
    gray = cv2.cvtColor(rgb, cv2.COLOR_RGB2GRAY)
    gray = cv2.createCLAHE(clipLimit=2.0, tileGridSize=(8, 8)).apply(gray)
    mask = proposal_mask(row.get("preannotations") or [], width, height)
    keypoints, descriptors = sift.detectAndCompute(gray, mask)
    background = gray.copy()
    visible = background[mask > 0]
    background[mask == 0] = int(np.median(visible)) if visible.size else 127
    thumbnail = cv2.resize(background, (32, 40), interpolation=cv2.INTER_AREA).astype(np.float32)
    thumbnail = (thumbnail - float(thumbnail.mean())) / (float(thumbnail.std()) + 1e-6)
    return {
        "keypoints": keypoints,
        "descriptors": descriptors,
        "thumbnail": thumbnail.reshape(-1),
        "visible_fraction": float(np.mean(mask > 0)),
    }


def estimate_registration(
    reference_features: dict[str, Any], target_features: dict[str, Any],
    reference_polygon: list[list[float]], width: int, height: int, matcher,
) -> dict[str, Any] | None:
    left, right = reference_features["descriptors"], target_features["descriptors"]
    if left is None or right is None or len(left) < 8 or len(right) < 8:
        return None
    pairs = matcher.knnMatch(left, right, k=2)
    good = [best for best, second in pairs if best.distance < 0.70 * second.distance]
    if len(good) < 8:
        return None
    reference_keypoints = reference_features["keypoints"]
    target_keypoints = target_features["keypoints"]
    source = np.float32([reference_keypoints[item.queryIdx].pt for item in good])
    target = np.float32([target_keypoints[item.trainIdx].pt for item in good])
    homography, mask = cv2.findHomography(
        source, target, cv2.RANSAC, 3.5, maxIters=4000, confidence=0.995,
    )
    if homography is None or mask is None:
        return None
    inliers = mask.reshape(-1).astype(bool)
    inlier_count = int(inliers.sum())
    if inlier_count < 8:
        return None
    projected = cv2.perspectiveTransform(source[inliers, None], homography)[:, 0]
    errors = np.linalg.norm(projected - target[inliers], axis=1)
    frame = np.float32([[[0, 0], [width - 1, 0], [width - 1, height - 1], [0, height - 1]]])
    transformed_frame = cv2.perspectiveTransform(frame, homography)[0]
    frame_area_scale = abs(float(cv2.contourArea(transformed_frame))) / (
        (width - 1) * (height - 1)
    )
    if not 0.20 <= frame_area_scale <= 3.0:
        return None
    try:
        polygon = transform_polygon(reference_polygon, homography, width, height)
    except (TypeError, ValueError, cv2.error):
        return None
    inlier_ratio = inlier_count / len(good)
    median_error = float(np.median(errors))
    score = inlier_count * inlier_ratio / (median_error + 1.0)
    return {
        "polygon": polygon,
        "quality": {
            "matches_after_ratio_test": len(good),
            "inliers": inlier_count,
            "inlier_ratio": inlier_ratio,
            "median_reprojection_error": median_error,
            "frame_area_scale": frame_area_scale,
            "score": score,
        },
    }


def _automatic_summary(rows: list[dict[str, Any]]) -> dict[str, Any]:
    automatic = [row for row in rows if row["decision"] == "automatic"]
    if not automatic:
        return {
            "mean_iou": 0.0, "min_iou": 0.0, "mean_dice": 0.0,
            "mean_center_accuracy": 0.0, "min_center_accuracy": 0.0,
            "total_center_errors": 0, "min_inside_recall": 0.0,
            "min_outside_recall": 0.0,
        }
    return common._summarise(automatic)


def summarize(rows: list[dict[str, Any]]) -> dict[str, Any]:
    automatic = [row for row in rows if row["decision"] == "automatic"]
    total_centers = sum(int(row["tray_count"]) for row in rows)
    automatic_centers = sum(int(row["tray_count"]) for row in automatic)
    return {
        "images": len(rows),
        "automatic_images": len(automatic),
        "unknown_images": len(rows) - len(automatic),
        "automatic_image_coverage": len(automatic) / max(len(rows), 1),
        "tray_centers": total_centers,
        "automatic_tray_centers": automatic_centers,
        "unknown_tray_centers": total_centers - automatic_centers,
        "automatic_tray_center_coverage": automatic_centers / max(total_centers, 1),
        "automatic": _automatic_summary(rows),
    }


def run(
    queues: list[Path], *, width: int, height: int, fold_count: int,
    candidate_count: int, gate: RegistrationGate,
) -> dict[str, Any]:
    manifests, samples = common.load_combined_samples(queues)
    bound_manifests, rows_by_stem = load_rows(queues)
    if len(manifests) != len(bound_manifests):
        raise RuntimeError("registration queue binding count drift")
    if len(samples) < 8:
        raise RuntimeError("registration experiment requires at least 8 independent images")
    cv2.setRNGSeed(20260813)
    sift = cv2.SIFT_create(nfeatures=1600, contrastThreshold=0.015, edgeThreshold=12)
    features = [
        extract_features(sample, rows_by_stem[sample["stem"]], sift, width, height)
        for sample in samples
    ]
    thumbnails = np.stack([item["thumbnail"] for item in features])
    matcher = cv2.BFMatcher(cv2.NORM_L2)
    rows: list[dict[str, Any]] = []
    invalid_polygons = 0
    for fold_index, (train_indices, held_indices) in enumerate(
        common.task_grouped_splits(samples, fold_count), start=1,
    ):
        for sample_index in held_indices:
            sample = samples[sample_index]
            distances = np.mean(
                (thumbnails[train_indices] - thumbnails[sample_index]) ** 2, axis=1,
            )
            candidates = np.asarray(train_indices)[np.argsort(distances)[:candidate_count]]
            matches: list[tuple[int, dict[str, Any]]] = []
            for reference_index in candidates:
                estimated = estimate_registration(
                    features[int(reference_index)], features[sample_index],
                    samples[int(reference_index)]["polygon"], width, height, matcher,
                )
                if estimated is not None:
                    matches.append((int(reference_index), estimated))
            base = {
                "fold": fold_index,
                "held_out_task_id": sample["task_id"],
                "held_out_photo_id": sample["source_photo_id"],
                "sku_code": sample["sku_code"],
                "queue": rows_by_stem[sample["stem"]]["queue"],
                "tray_count": len(sample["boxes"]),
                "proposal_count": len(rows_by_stem[sample["stem"]].get("preannotations") or []),
                "visible_feature_fraction": features[sample_index]["visible_fraction"],
            }
            if not matches:
                rows.append({
                    **base, "decision": "unknown", "unknown_reason": "no_valid_registration",
                    "unknown_work_area": len(sample["boxes"]),
                })
                continue
            reference_index, best = max(matches, key=lambda value: value[1]["quality"]["score"])
            quality = best["quality"]
            reference = samples[reference_index]
            match_fields = {
                "reference_task_id": reference["task_id"],
                "reference_photo_id": reference["source_photo_id"],
                "reference_sku_code": reference["sku_code"],
                "registration_quality": quality,
            }
            if not gate_registration(quality, gate):
                rows.append({
                    **base, **match_fields, "decision": "unknown",
                    "unknown_reason": "registration_below_confidence_gate",
                    "unknown_work_area": len(sample["boxes"]),
                })
                continue
            truth_mask = common.rasterize_polygon(sample["polygon"], 192, 256).astype(bool)
            metrics, valid = quad.evaluate_polygon(
                best["polygon"], truth_mask, sample, 192, 256,
            )
            invalid_polygons += int(not valid)
            rows.append({
                **base, **match_fields, "decision": "automatic",
                **metrics,
            })
    summary = summarize(rows)
    thresholds = {
        "min_automatic_image_coverage": 0.80,
        "min_iou": 0.85,
        "min_center_accuracy": 0.98,
        "min_inside_recall": 0.98,
        "min_outside_recall": 0.95,
    }
    automatic = summary["automatic"]
    passed = (
        invalid_polygons == 0
        and summary["automatic_image_coverage"] >= thresholds["min_automatic_image_coverage"]
        and automatic["min_iou"] >= thresholds["min_iou"]
        and automatic["min_center_accuracy"] >= thresholds["min_center_accuracy"]
        and automatic["min_inside_recall"] >= thresholds["min_inside_recall"]
        and automatic["min_outside_recall"] >= thresholds["min_outside_recall"]
    )
    queue_summaries = {
        queue: summarize([row for row in rows if row["queue"] == queue])
        for queue in sorted({row["queue"] for row in rows})
    }
    required_rows = [row for row in rows if row["held_out_task_id"] == REQUIRED_TASK_ID]
    return {
        "version": "vision-lab-work-area-roi-registration-v1",
        "created_at": dt.datetime.now(dt.timezone.utc).isoformat(),
        "experiment_script_sha256": common.sha256(Path(__file__)),
        "common_script_sha256": common.sha256(Path(common.__file__)),
        "quad_script_sha256": common.sha256(Path(quad.__file__)),
        "opencv_version": cv2.__version__,
        "mode": "deterministic-task-grouped-template-registration",
        "queues": [str(queue) for queue in queues],
        "queue_manifest_sha256s": {
            str(queue): common.sha256(queue / "manifest.json") for queue in queues
        },
        "sample_count": len(samples),
        "task_count": len({sample["task_id"] for sample in samples}),
        "folds": len(common.task_grouped_splits(samples, fold_count)),
        "split": "deterministic-sku-stratified-task-k-fold",
        "algorithm": "proposal-masked-SIFT-thumbnail-retrieval-RANSAC-homography",
        "detector_proposals_role": "feature-suppression-only-never-ROI-truth",
        "input_size": [width, height],
        "candidate_count": candidate_count,
        "registration_gate": gate.__dict__,
        "thresholds": thresholds,
        "invalid_polygon_count": invalid_polygons,
        "summary": summary,
        "queue_summaries": queue_summaries,
        "required_df1f_case": required_rows,
        "rows": rows,
        "passed": passed,
        "sufficient_for_production": False,
        "deployment_authorized": False,
        "conclusion": (
            "offline_candidate_signal_only_locked_test_still_required"
            if passed else "template_registration_insufficient_fail_closed"
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
    parser.add_argument("--width", type=int, default=DEFAULT_WIDTH)
    parser.add_argument("--height", type=int, default=DEFAULT_HEIGHT)
    parser.add_argument("--folds", type=int, default=8)
    parser.add_argument("--candidate-count", type=int, default=DEFAULT_CANDIDATES)
    args = parser.parse_args()
    if args.width < 64 or args.height < 64 or args.folds < 2 or args.candidate_count < 1:
        parser.error("width/height must be >=64, folds >=2, and candidate-count positive")
    receipt = run(
        [path.resolve() for path in args.queue], width=args.width, height=args.height,
        fold_count=args.folds, candidate_count=args.candidate_count,
        gate=RegistrationGate(),
    )
    args.runtime_root.mkdir(parents=True, exist_ok=True)
    timestamp = dt.datetime.now(dt.timezone.utc).strftime("%Y%m%dT%H%M%S%fZ")
    path = args.runtime_root / f"work-area-roi-registration-experiment-{timestamp}.json"
    path.write_text(json.dumps(receipt, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({
        "receipt": str(path), "passed": receipt["passed"],
        "summary": receipt["summary"], "conclusion": receipt["conclusion"],
    }, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
