#!/usr/bin/env python3
"""Replay label QC stages and isolate tray, label, assignment, and truth errors.

This is an offline diagnostic.  It never trains or deploys a model and treats
the supplied manifest as an already-exposed regression set, not a blind gate.
The optional sliced variant applies the production tray ONNX to overlapping
full-resolution tiles, merges duplicate trays, and then reuses the unchanged
production label stage.
"""
from __future__ import annotations

import argparse
import datetime as dt
import hashlib
import json
import os
import shutil
import subprocess
import sys
import tempfile
import time
from collections import Counter
from pathlib import Path
from typing import Any, Sequence

import numpy as np
from PIL import Image, ImageDraw, ImageOps


DEFECT_LABELS = {"MISSING_WHITE_LABEL", "MISSING_COLOR_LABEL"}
VERDICT_CLEAR = "CLEAR"


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def utc_now() -> str:
    return dt.datetime.now(dt.timezone.utc).isoformat()


def percentile(values: Sequence[float], quantile: float) -> float:
    if not values:
        return 0.0
    ordered = sorted(values)
    index = min(len(ordered) - 1, max(0, int(round((len(ordered) - 1) * quantile))))
    return float(ordered[index])


def _round_box(box: Sequence[float], digits: int = 3) -> list[float]:
    return [round(float(value), digits) for value in box]


def box_iou(left: Sequence[float], right: Sequence[float]) -> float:
    x0 = max(float(left[0]), float(right[0]))
    y0 = max(float(left[1]), float(right[1]))
    x1 = min(float(left[2]), float(right[2]))
    y1 = min(float(left[3]), float(right[3]))
    intersection = max(0.0, x1 - x0) * max(0.0, y1 - y0)
    left_area = max(0.0, float(left[2]) - float(left[0])) * max(
        0.0, float(left[3]) - float(left[1]),
    )
    right_area = max(0.0, float(right[2]) - float(right[0])) * max(
        0.0, float(right[3]) - float(right[1]),
    )
    union = left_area + right_area - intersection
    return intersection / union if union > 0 else 0.0


def intersection_over_smaller(left: Sequence[float], right: Sequence[float]) -> float:
    x0 = max(float(left[0]), float(right[0]))
    y0 = max(float(left[1]), float(right[1]))
    x1 = min(float(left[2]), float(right[2]))
    y1 = min(float(left[3]), float(right[3]))
    intersection = max(0.0, x1 - x0) * max(0.0, y1 - y0)
    left_area = max(0.0, float(left[2]) - float(left[0])) * max(
        0.0, float(left[3]) - float(left[1]),
    )
    right_area = max(0.0, float(right[2]) - float(right[0])) * max(
        0.0, float(right[3]) - float(right[1]),
    )
    smaller = min(left_area, right_area)
    return intersection / smaller if smaller > 0 else 0.0


def containment(candidate: Sequence[float], truth: Sequence[float]) -> float:
    """Fraction of the truth box covered by a candidate tray."""
    x0 = max(float(candidate[0]), float(truth[0]))
    y0 = max(float(candidate[1]), float(truth[1]))
    x1 = min(float(candidate[2]), float(truth[2]))
    y1 = min(float(candidate[3]), float(truth[3]))
    intersection = max(0.0, x1 - x0) * max(0.0, y1 - y0)
    truth_area = max(1e-9, (float(truth[2]) - float(truth[0])) * (
        float(truth[3]) - float(truth[1])
    ))
    return intersection / truth_area


def deduplicate_detections(
    detections: Sequence[Any], iou_threshold: float, ios_threshold: float,
) -> list[Any]:
    """Greedy class-aware NMS with an additional nested-box suppression rule."""
    kept: list[Any] = []
    for detection in sorted(detections, key=lambda item: -float(item.confidence)):
        duplicate = False
        for chosen in kept:
            if int(detection.class_id) != int(chosen.class_id):
                continue
            if box_iou(detection.as_xyxy(), chosen.as_xyxy()) >= iou_threshold:
                duplicate = True
                break
            if intersection_over_smaller(
                detection.as_xyxy(), chosen.as_xyxy(),
            ) >= ios_threshold:
                duplicate = True
                break
        if not duplicate:
            kept.append(detection)
    return kept


def tile_starts(length: int, tile_size: int, overlap: float) -> list[int]:
    if tile_size <= 0:
        raise ValueError("tile_size must be positive")
    if not 0 <= overlap < 1:
        raise ValueError("overlap must be in [0, 1)")
    if length <= tile_size:
        return [0]
    stride = max(1, int(round(tile_size * (1.0 - overlap))))
    starts = list(range(0, max(1, length - tile_size + 1), stride))
    final = length - tile_size
    if not starts or starts[-1] != final:
        starts.append(final)
    return sorted(set(starts))


def _detection_dict(detection: Any, source: str) -> dict[str, Any]:
    return {
        "source": source,
        "box": _round_box(detection.as_xyxy()),
        "confidence": round(float(detection.confidence), 6),
        "class_id": int(detection.class_id),
    }


class FullFrameModels:
    """Trace-producing facade for the unchanged full-frame production models."""

    def __init__(self, base: Any) -> None:
        self.base = base
        self.last_stats: dict[str, Any] = {}
        self.last_raw_detections: list[dict[str, Any]] = []

    def detect_trays(self, image: np.ndarray, conf: float) -> list[Any]:
        detections = self.base.detect_trays(image, conf)
        self.last_raw_detections = [
            _detection_dict(detection, "full") for detection in detections
        ]
        self.last_stats = {
            "mode": "full_frame",
            "tiles": 0,
            "full_frame_detections": len(detections),
            "sliced_detections": 0,
            "raw_detections": len(detections),
            "kept_detections": len(detections),
            "duplicates_removed": 0,
            "tile_edge_detections": 0,
        }
        return detections

    def detect_labels(self, crop: np.ndarray, conf: float) -> list[Any]:
        return self.base.detect_labels(crop, conf)


class SlicedTrayModels:
    """Run the production tray detector on overlapping tiles, then deduplicate."""

    def __init__(
        self,
        base: Any,
        detection_type: type,
        tile_size: int,
        overlap: float,
        iou_threshold: float,
        ios_threshold: float,
    ) -> None:
        self.base = base
        self.detection_type = detection_type
        self.tile_size = tile_size
        self.overlap = overlap
        self.iou_threshold = iou_threshold
        self.ios_threshold = ios_threshold
        self.last_stats: dict[str, Any] = {}
        self.last_raw_detections: list[dict[str, Any]] = []

    def detect_trays(self, image: np.ndarray, conf: float) -> list[Any]:
        height, width = image.shape[:2]
        full = list(self.base.detect_trays(image, conf))
        all_detections = list(full)
        raw_rows = [_detection_dict(detection, "full") for detection in full]
        tile_count = 0
        sliced_count = 0
        edge_count = 0
        x_starts = tile_starts(width, self.tile_size, self.overlap)
        y_starts = tile_starts(height, self.tile_size, self.overlap)
        for y0 in y_starts:
            for x0 in x_starts:
                x1 = min(width, x0 + self.tile_size)
                y1 = min(height, y0 + self.tile_size)
                if x0 == 0 and y0 == 0 and x1 == width and y1 == height:
                    continue
                tile = image[y0:y1, x0:x1]
                tile_count += 1
                source = f"tile:{x0},{y0},{x1},{y1}"
                for detection in self.base.detect_trays(tile, conf):
                    mapped = self.detection_type(
                        x0=float(detection.x0) + x0,
                        y0=float(detection.y0) + y0,
                        x1=float(detection.x1) + x0,
                        y1=float(detection.y1) + y0,
                        confidence=float(detection.confidence),
                        class_id=int(detection.class_id),
                    )
                    all_detections.append(mapped)
                    raw_rows.append(_detection_dict(mapped, source))
                    sliced_count += 1
                    touches_internal_edge = (
                        (x0 > 0 and detection.x0 <= 2)
                        or (y0 > 0 and detection.y0 <= 2)
                        or (x1 < width and detection.x1 >= tile.shape[1] - 2)
                        or (y1 < height and detection.y1 >= tile.shape[0] - 2)
                    )
                    edge_count += int(touches_internal_edge)
        kept = deduplicate_detections(
            all_detections, self.iou_threshold, self.ios_threshold,
        )
        self.last_raw_detections = raw_rows
        self.last_stats = {
            "mode": "full_plus_sliced",
            "tile_size": self.tile_size,
            "overlap": self.overlap,
            "iou_threshold": self.iou_threshold,
            "ios_threshold": self.ios_threshold,
            "tiles": tile_count,
            "full_frame_detections": len(full),
            "sliced_detections": sliced_count,
            "raw_detections": len(all_detections),
            "kept_detections": len(kept),
            "duplicates_removed": len(all_detections) - len(kept),
            "tile_edge_detections": edge_count,
        }
        return kept

    def detect_labels(self, crop: np.ndarray, conf: float) -> list[Any]:
        return self.base.detect_labels(crop, conf)


def load_records(manifest_path: Path) -> list[dict[str, Any]]:
    payload = json.loads(manifest_path.read_text(encoding="utf-8"))
    source = payload.get("records") if isinstance(payload, dict) else payload
    if not isinstance(source, list):
        raise ValueError(f"manifest has no records list: {manifest_path}")
    records: list[dict[str, Any]] = []
    for source_row in source:
        row = dict(source_row)
        image = Path(str(row.get("image") or ""))
        if not image.is_file():
            raise FileNotFoundError(image)
        actual_hash = sha256_file(image)
        expected_hash = str(row.get("image_sha256") or "")
        if expected_hash and actual_hash != expected_hash:
            raise RuntimeError(f"source image hash mismatch: {image}")
        row["image"] = image
        row["image_sha256"] = actual_hash
        row["photo_id"] = str(row.get("photo_id") or image.stem)
        row["task_id"] = str(row.get("task_id") or row["photo_id"])
        row["human_label"] = str(row.get("human_label") or "NO_DEFECT").upper()
        row["group"] = str(row.get("group") or "unspecified")
        records.append(row)
    ids = [row["photo_id"] for row in records]
    if len(ids) != len(set(ids)):
        raise ValueError("duplicate photo ids in regression manifest")
    return records


def verdict_from_presence(has_white: bool, has_color: bool) -> str:
    if has_white and has_color:
        return VERDICT_CLEAR
    if has_white:
        return "MISSING_COLOR_LABEL"
    if has_color:
        return "MISSING_WHITE_LABEL"
    return "BOTH_MISSING"


def human_annotation_verdict(annotation: dict[str, Any]) -> str | None:
    if annotation.get("reviewed") is not True or annotation.get("source") != "human":
        return None
    if annotation.get("unjudgeable") is True:
        return None
    boxes = annotation.get("boxes")
    if not isinstance(boxes, list):
        return None
    has_white = any(int(box.get("c", -1)) == 0 for box in boxes if isinstance(box, dict))
    has_color = any(int(box.get("c", -1)) == 1 for box in boxes if isinstance(box, dict))
    return verdict_from_presence(has_white, has_color)


def load_human_audits(audit_manifest: Path | None) -> tuple[dict[str, dict[str, Any]], dict[str, Any]]:
    if audit_manifest is None:
        return {}, {"provided": False, "records": 0}
    payload = json.loads(audit_manifest.read_text(encoding="utf-8"))
    annotation_root = audit_manifest.parent / "annotations-human"
    audits: dict[str, dict[str, Any]] = {}
    rows: list[dict[str, Any]] = []
    for source_row in payload.get("rows") or []:
        crop_id = str(source_row["crop_id"])
        annotation_path = annotation_root / f"{crop_id}.json"
        if not annotation_path.is_file():
            raise FileNotFoundError(annotation_path)
        annotation = json.loads(annotation_path.read_text(encoding="utf-8"))
        source_photo_id = str(source_row["source_photo_id"])
        entry = {
            "crop_id": crop_id,
            "source_photo_id": source_photo_id,
            "source_sha256": source_row.get("source_sha256"),
            "protected_human_label": source_row.get("protected_human_label"),
            "protected_target_bbox": source_row.get("protected_target_bbox"),
            "crop_rect_px": source_row.get("crop_rect_px"),
            "annotation_path": str(annotation_path),
            "annotation_sha256": sha256_file(annotation_path),
            "reviewed": annotation.get("reviewed") is True,
            "source": annotation.get("source"),
            "unjudgeable": annotation.get("unjudgeable") is True,
            "human_crop_verdict": human_annotation_verdict(annotation),
            "missing_confirmed_by_human": annotation.get("missing_confirmed_by_human"),
            "declared_missing_classes": annotation.get("declared_missing_classes") or [],
            "boxes": annotation.get("boxes") or [],
        }
        audits[source_photo_id] = entry
        rows.append(entry)
    return audits, {
        "provided": True,
        "manifest": str(audit_manifest),
        "manifest_sha256": sha256_file(audit_manifest),
        "records": len(rows),
        "rows": rows,
    }


def truth_box_pixels(row: dict[str, Any], width: int, height: int) -> list[float] | None:
    bbox = row.get("bbox")
    if not isinstance(bbox, list) or len(bbox) != 4:
        return None
    return [
        float(bbox[0]) * width,
        float(bbox[1]) * height,
        float(bbox[2]) * width,
        float(bbox[3]) * height,
    ]


def serialize_screening(result: Any) -> dict[str, Any]:
    traces_by_index = {trace.index: trace for trace in result.trace}
    trays: list[dict[str, Any]] = []
    for tray in result.trays:
        trace = traces_by_index.get(tray.index)
        trays.append({
            "index": tray.index,
            "box": _round_box(tray.box),
            "confidence": round(float(tray.confidence), 6),
            "has_white": bool(tray.has_white),
            "has_color": bool(tray.has_color),
            "verdict": tray.verdict,
            "own_label_count": tray.own_label_count,
            "dropped_neighbour_labels": tray.dropped_neighbour_labels,
            "labels": [
                {
                    "class_id": int(label.class_id),
                    "confidence": round(float(label.confidence), 6),
                    "source_box": _round_box(label.box),
                }
                for label in tray.labels
            ],
            "crop": None if trace is None else {
                "rect": _round_box(trace.crop_rect),
                "crop_shape": trace.crop_shape,
                "resized_shape": trace.resized_shape,
                "skipped_reason": trace.skipped_reason,
                "raw_labels": [
                    {
                        "class_id": int(label.class_id),
                        "confidence": round(float(label.confidence), 6),
                        "crop_box": _round_box(label.crop_box),
                        "source_box": _round_box(label.source_box),
                        "owned": bool(label.owned),
                    }
                    for label in trace.raw_labels
                ],
            },
        })
    return {"trays": trays, "suspect_indices": [tray.index for tray in result.suspects]}


def truth_tray_label_oracle(
    frame: np.ndarray,
    truth_box: list[float],
    models: Any,
    screening: Any,
    yolo: Any,
    params: Any,
) -> dict[str, Any]:
    crop, rect = yolo.crop_with_padding(frame, truth_box, params.pad_ratio)
    if crop.shape[0] < params.min_crop_px or crop.shape[1] < params.min_crop_px:
        return {
            "available": True,
            "verdict": "BOTH_MISSING",
            "reason": "truth_crop_below_minimum",
            "crop_rect": _round_box(rect),
            "crop_shape": list(crop.shape),
            "raw_labels": [],
        }
    resized = yolo.resize_crop(crop, params.crop_width)
    labels = models.detect_labels(resized, params.label_conf)
    owned: list[Any] = []
    raw_rows: list[dict[str, Any]] = []
    for label in labels:
        belongs = screening._owns(truth_box, label, rect, resized.shape)
        source_box = screening._to_source_box(label, rect, resized.shape)
        raw_rows.append({
            "class_id": int(label.class_id),
            "confidence": round(float(label.confidence), 6),
            "crop_box": _round_box(label.as_xyxy()),
            "source_box": _round_box(source_box),
            "owned": bool(belongs),
        })
        if belongs:
            owned.append(label)
    has_white = any(label.class_id == yolo.CLASS_WHITE_LABEL for label in owned)
    has_color = any(label.class_id == yolo.CLASS_COLOR_LABEL for label in owned)
    return {
        "available": True,
        "verdict": screening._verdict_for(has_white, has_color),
        "crop_rect": _round_box(rect),
        "crop_shape": list(crop.shape),
        "resized_shape": list(resized.shape),
        "raw_labels": raw_rows,
        "owned_label_count": len(owned),
        "dropped_label_count": len(labels) - len(owned),
    }


def classify_defect_stage(
    protected_truth: str,
    covered: bool,
    screen_hit: bool,
    truth_crop_verdict: str | None,
    human_crop_verdict: str | None,
) -> tuple[str, list[str]]:
    issues: list[str] = []
    if human_crop_verdict is not None and human_crop_verdict != protected_truth:
        issues.append("protected_truth_conflicts_with_human_crop")
    if not covered:
        issues.append("tray_detection_miss")
    if truth_crop_verdict is not None and truth_crop_verdict != protected_truth:
        issues.append("truth_tray_current_label_mismatch")
    if covered and truth_crop_verdict == protected_truth and not screen_hit:
        issues.append("assignment_or_crop_path_miss")
    if "protected_truth_conflicts_with_human_crop" in issues:
        return "truth_conflict", issues
    if "tray_detection_miss" in issues:
        return "tray_detection_miss", issues
    if "truth_tray_current_label_mismatch" in issues:
        return "label_detection_or_rule_miss", issues
    if "assignment_or_crop_path_miss" in issues:
        return "assignment_or_crop_path_miss", issues
    return ("hit" if screen_hit else "unresolved"), issues


def build_truth_oracles(
    records: Sequence[dict[str, Any]],
    models: Any,
    screening: Any,
    yolo: Any,
    params: Any,
    audits: dict[str, dict[str, Any]],
) -> dict[str, dict[str, Any]]:
    oracles: dict[str, dict[str, Any]] = {}
    for row in records:
        if row["human_label"] not in DEFECT_LABELS:
            continue
        with Image.open(row["image"]) as opened:
            frame = np.array(ImageOps.exif_transpose(opened).convert("RGB"))
        height, width = frame.shape[:2]
        truth = truth_box_pixels(row, width, height)
        if truth is None:
            oracles[row["photo_id"]] = {
                "truth_tray_current_label": {"available": False, "reason": "missing_truth_bbox"},
                "human_crop": audits.get(row["photo_id"]),
            }
            continue
        oracle = truth_tray_label_oracle(
            frame, truth, models, screening, yolo, params,
        )
        audit = audits.get(row["photo_id"])
        if audit and audit.get("source_sha256") not in (None, row["image_sha256"]):
            raise RuntimeError(f"human audit source hash mismatch: {row['photo_id']}")
        oracles[row["photo_id"]] = {
            "truth_tray_current_label": oracle,
            "human_crop": audit,
            "current_detections_truth_assignment": {
                "available": False,
                "reason": "full_image_human_instance_assignment_required",
            },
        }
    return oracles


def evaluate_variant(
    name: str,
    records: Sequence[dict[str, Any]],
    models: Any,
    screening: Any,
    params: Any,
    oracles: dict[str, dict[str, Any]],
) -> dict[str, Any]:
    defect_total = defect_hits = tray_target_total = tray_target_hits = 0
    normal_photos = normal_flagged_photos = false_flags = target_false_clear = 0
    latencies: list[float] = []
    groups: dict[str, Counter[str]] = {}
    stage_counts: Counter[str] = Counter()
    details: list[dict[str, Any]] = []
    duplicate_total = edge_total = tile_total = 0
    for row in records:
        with Image.open(row["image"]) as opened:
            frame = np.array(ImageOps.exif_transpose(opened).convert("RGB"))
        started = time.perf_counter()
        result = screening.screen_image(frame, models, params)
        latency_ms = (time.perf_counter() - started) * 1000
        latencies.append(latency_ms)
        tray_stats = dict(getattr(models, "last_stats", {}))
        duplicate_total += int(tray_stats.get("duplicates_removed", 0))
        edge_total += int(tray_stats.get("tile_edge_detections", 0))
        tile_total += int(tray_stats.get("tiles", 0))
        group = groups.setdefault(row["group"], Counter())
        common = {
            "photo_id": row["photo_id"],
            "task_id": row["task_id"],
            "group": row["group"],
            "image": str(row["image"]),
            "image_sha256": row["image_sha256"],
            "image_size": [int(frame.shape[1]), int(frame.shape[0])],
            "latency_ms": round(latency_ms, 3),
            "tray_detection": {
                "stats": tray_stats,
                "raw": list(getattr(models, "last_raw_detections", [])),
            },
            "screening": serialize_screening(result),
        }
        if row["human_label"] in DEFECT_LABELS:
            defect_total += 1
            group["defect_total"] += 1
            height, width = frame.shape[:2]
            truth = truth_box_pixels(row, width, height)
            coverages = [
                (containment(tray.box, truth), tray)
                for tray in result.trays
            ] if truth is not None else []
            coverages.sort(key=lambda pair: pair[0], reverse=True)
            best_coverage = coverages[0][0] if coverages else 0.0
            covered = truth is None or best_coverage >= 0.5
            if truth is not None:
                tray_target_total += 1
                group["tray_target_total"] += 1
                tray_target_hits += int(covered)
                group["tray_target_hits"] += int(covered)
            covering = [
                tray for coverage, tray in coverages if coverage >= 0.5
            ]
            hit = any(tray.verdict == row["human_label"] for tray in covering)
            defect_hits += int(hit)
            group["defect_hits"] += int(hit)
            false_clear = bool(covering) and any(
                tray.verdict == VERDICT_CLEAR for tray in covering
            ) and not hit
            target_false_clear += int(false_clear)
            oracle = oracles.get(row["photo_id"], {})
            truth_crop = oracle.get("truth_tray_current_label") or {}
            audit = oracle.get("human_crop") or {}
            stage, issues = classify_defect_stage(
                row["human_label"],
                covered,
                hit,
                truth_crop.get("verdict"),
                audit.get("human_crop_verdict"),
            )
            stage_counts[stage] += 1
            details.append(common | {
                "kind": "defect",
                "protected_truth": row["human_label"],
                "truth_box": _round_box(truth) if truth else None,
                "best_truth_containment": round(best_coverage, 6),
                "tray_target_covered": bool(covered),
                "covering_tray_indices": [tray.index for tray in covering],
                "hit": bool(hit),
                "target_false_clear": false_clear,
                "primary_stage": stage,
                "issues": issues,
                "oracles": oracle | {
                    "current_tray_human_presence": {
                        "available": bool(covered and audit.get("human_crop_verdict") is not None),
                        "verdict": audit.get("human_crop_verdict") if covered else None,
                        "reason": None if covered else "current_tray_does_not_cover_truth",
                    },
                },
            })
        else:
            normal_photos += 1
            photo_flags = len(result.suspects)
            false_flags += photo_flags
            normal_flagged_photos += int(photo_flags > 0)
            group["normal_photos"] += 1
            group["false_flags"] += photo_flags
            group["normal_flagged_photos"] += int(photo_flags > 0)
            details.append(common | {
                "kind": "normal",
                "false_flags": photo_flags,
                "flagged": photo_flags > 0,
            })
    return {
        "name": name,
        "records": len(records),
        "defect_total": defect_total,
        "defect_hits": defect_hits,
        "tray_target_total": tray_target_total,
        "tray_target_hits": tray_target_hits,
        "normal_photos": normal_photos,
        "normal_flagged_photos": normal_flagged_photos,
        "normal_flagged_photo_rate": round(
            normal_flagged_photos / normal_photos, 6,
        ) if normal_photos else 0.0,
        "false_flags": false_flags,
        "target_false_clear": target_false_clear,
        "p50_latency_ms": round(percentile(latencies, 0.50), 3),
        "p95_latency_ms": round(percentile(latencies, 0.95), 3),
        "max_latency_ms": round(max(latencies, default=0.0), 3),
        "total_tiles": tile_total,
        "duplicates_removed": duplicate_total,
        "tile_edge_detections": edge_total,
        "cross_class_conflicts": 0,
        "ambiguous_assignment": None,
        "ambiguous_assignment_note": "not evaluated: P0 attributed misses before instance-matching experiment",
        "stage_counts": dict(stage_counts),
        "groups": {key: dict(value) for key, value in groups.items()},
        "details": details,
    }


def compare_variants(baseline: dict[str, Any], candidate: dict[str, Any]) -> dict[str, Any]:
    baseline_by_id = {row["photo_id"]: row for row in baseline["details"]}
    candidate_by_id = {row["photo_id"]: row for row in candidate["details"]}
    defect_recovered: list[str] = []
    defect_regressed: list[str] = []
    tray_coverage_recovered: list[str] = []
    tray_coverage_regressed: list[str] = []
    normal_flags_reduced: list[str] = []
    normal_flags_increased: list[str] = []
    for photo_id, before in baseline_by_id.items():
        after = candidate_by_id[photo_id]
        if before["kind"] == "defect":
            if not before["hit"] and after["hit"]:
                defect_recovered.append(photo_id)
            if before["hit"] and not after["hit"]:
                defect_regressed.append(photo_id)
            if not before["tray_target_covered"] and after["tray_target_covered"]:
                tray_coverage_recovered.append(photo_id)
            if before["tray_target_covered"] and not after["tray_target_covered"]:
                tray_coverage_regressed.append(photo_id)
        else:
            if after["false_flags"] < before["false_flags"]:
                normal_flags_reduced.append(photo_id)
            if after["false_flags"] > before["false_flags"]:
                normal_flags_increased.append(photo_id)
    recovered_tasks = {
        candidate_by_id[photo_id]["task_id"] for photo_id in defect_recovered
    }
    reduced_normal_tasks = {
        candidate_by_id[photo_id]["task_id"] for photo_id in normal_flags_reduced
    }
    development_signal = (
        (len(recovered_tasks) >= 2 or len(reduced_normal_tasks) >= 2)
        and not defect_regressed
        and not normal_flags_increased
    )
    return {
        "defect_recovered": defect_recovered,
        "defect_regressed": defect_regressed,
        "tray_coverage_recovered": tray_coverage_recovered,
        "tray_coverage_regressed": tray_coverage_regressed,
        "normal_flags_reduced": normal_flags_reduced,
        "normal_flags_increased": normal_flags_increased,
        "independent_defect_tasks_recovered": len(recovered_tasks),
        "independent_normal_tasks_reduced": len(reduced_normal_tasks),
        "development_continue_signal": development_signal,
        "development_signal_is_not_promotion": True,
        "latency_ratio": round(
            candidate["p95_latency_ms"] / baseline["p95_latency_ms"], 6,
        ) if baseline["p95_latency_ms"] else None,
        "decision": (
            "continue_offline_research" if development_signal
            else "stop_or_redesign_before_more_model_complexity"
        ),
    }


def _scale_box(box: Sequence[float], scale: float) -> tuple[float, float, float, float]:
    return tuple(float(value) * scale for value in box)  # type: ignore[return-value]


def render_overlay(
    detail: dict[str, Any], output: Path, max_side: int,
) -> None:
    with Image.open(detail["image"]) as opened:
        image = ImageOps.exif_transpose(opened).convert("RGB")
    scale = min(1.0, max_side / max(image.size))
    if scale < 1.0:
        image = image.resize(
            (max(1, round(image.width * scale)), max(1, round(image.height * scale))),
            Image.Resampling.LANCZOS,
        )
    draw = ImageDraw.Draw(image)
    truth_box = detail.get("truth_box")
    if truth_box:
        draw.rectangle(_scale_box(truth_box, scale), outline=(255, 215, 0), width=4)
        draw.text((truth_box[0] * scale, max(0, truth_box[1] * scale - 14)), "truth", fill=(255, 215, 0))
    for tray in detail["screening"]["trays"]:
        color = (30, 200, 80) if tray["verdict"] == VERDICT_CLEAR else (240, 55, 55)
        draw.rectangle(_scale_box(tray["box"], scale), outline=color, width=2)
        draw.text(
            (tray["box"][0] * scale, tray["box"][1] * scale),
            f"{tray['index']}:{tray['verdict']}",
            fill=color,
        )
        for label in tray["labels"]:
            label_color = (245, 245, 245) if label["class_id"] == 0 else (20, 210, 255)
            draw.rectangle(_scale_box(label["source_box"], scale), outline=label_color, width=2)
    output.parent.mkdir(parents=True, exist_ok=True)
    image.save(output, format="JPEG", quality=88, optimize=True)


def render_target_crop(
    detail: dict[str, Any], output: Path,
) -> None:
    truth_oracle = detail.get("oracles", {}).get("truth_tray_current_label") or {}
    rect = truth_oracle.get("crop_rect")
    if not rect:
        return
    with Image.open(detail["image"]) as opened:
        source = ImageOps.exif_transpose(opened).convert("RGB")
    crop = source.crop(tuple(int(round(value)) for value in rect))
    draw = ImageDraw.Draw(crop)
    sx = crop.width / max(1, int(truth_oracle.get("resized_shape", [1, 1])[1]))
    sy = crop.height / max(1, int(truth_oracle.get("resized_shape", [1, 1])[0]))
    for label in truth_oracle.get("raw_labels") or []:
        box = label["crop_box"]
        mapped = (box[0] * sx, box[1] * sy, box[2] * sx, box[3] * sy)
        color = (245, 245, 245) if label["class_id"] == 0 else (20, 210, 255)
        draw.rectangle(mapped, outline=color, width=3)
    output.parent.mkdir(parents=True, exist_ok=True)
    crop.save(output, format="JPEG", quality=92)


def git_head(repo_root: Path) -> str | None:
    result = subprocess.run(
        ["git", "-C", str(repo_root), "rev-parse", "HEAD"],
        text=True,
        capture_output=True,
        check=False,
    )
    return result.stdout.strip() if result.returncode == 0 else None


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo-root", required=True, type=Path)
    parser.add_argument("--tray-model", required=True, type=Path)
    parser.add_argument("--label-model", required=True, type=Path)
    parser.add_argument("--manifest", required=True, type=Path)
    parser.add_argument("--human-audit-manifest", type=Path)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--tray-threshold", type=float, default=0.60)
    parser.add_argument("--label-threshold", type=float, default=0.20)
    parser.add_argument("--slice-size", type=int, default=1536)
    parser.add_argument("--slice-overlap", type=float, default=0.20)
    parser.add_argument("--nms-iou", type=float, default=0.50)
    parser.add_argument("--nms-ios", type=float, default=0.80)
    parser.add_argument("--max-visual-side", type=int, default=1600)
    return parser


def main() -> None:
    args = build_parser().parse_args()
    args.repo_root = args.repo_root.resolve()
    for path in (args.tray_model, args.label_model, args.manifest):
        if not path.is_file():
            raise FileNotFoundError(path)
    if args.human_audit_manifest and not args.human_audit_manifest.is_file():
        raise FileNotFoundError(args.human_audit_manifest)
    output = args.output.resolve()
    if output.exists():
        raise FileExistsError(f"refusing to overwrite diagnostic output: {output}")
    output.mkdir(parents=True)

    sys.path.insert(0, str((args.repo_root / "backend" / "python").resolve()))
    from label_qc.services import screening
    from label_qc.services import yolo_detector as yolo

    records = load_records(args.manifest)
    audits, audit_input = load_human_audits(args.human_audit_manifest)
    params = screening.ScreeningParams(
        tray_conf=args.tray_threshold,
        label_conf=args.label_threshold,
        capture_trace=True,
    )
    with tempfile.TemporaryDirectory(prefix="label-qc-oracle-models-") as temporary:
        model_dir = Path(temporary)
        shutil.copy2(args.tray_model, model_dir / "tray.onnx")
        shutil.copy2(args.label_model, model_dir / "label.onnx")
        base_models = yolo.LabelQcYoloModels(model_dir=model_dir)
        if not base_models.available:
            raise RuntimeError(base_models.load_error or "ONNX models unavailable")
        baseline_models = FullFrameModels(base_models)
        sliced_models = SlicedTrayModels(
            base_models,
            yolo.Detection,
            tile_size=args.slice_size,
            overlap=args.slice_overlap,
            iou_threshold=args.nms_iou,
            ios_threshold=args.nms_ios,
        )
        oracles = build_truth_oracles(
            records, base_models, screening, yolo, params, audits,
        )
        baseline = evaluate_variant(
            "production_full_frame", records, baseline_models, screening, params, oracles,
        )
        sliced = evaluate_variant(
            "full_plus_sliced_tray", records, sliced_models, screening, params, oracles,
        )

    comparison = compare_variants(baseline, sliced)
    baseline_by_id = {row["photo_id"]: row for row in baseline["details"]}
    sliced_by_id = {row["photo_id"]: row for row in sliced["details"]}
    visual_ids = {
        row["photo_id"] for row in baseline["details"]
        if row["kind"] == "defect" or row.get("flagged")
    } | {
        row["photo_id"] for row in sliced["details"]
        if row["kind"] == "defect" or row.get("flagged")
    }
    visual_rows: list[dict[str, Any]] = []
    for photo_id in sorted(visual_ids):
        for variant, detail in (
            ("baseline", baseline_by_id[photo_id]),
            ("sliced", sliced_by_id[photo_id]),
        ):
            path = output / "visuals" / variant / f"{photo_id}.jpg"
            render_overlay(detail, path, args.max_visual_side)
            visual_rows.append({
                "photo_id": photo_id,
                "variant": variant,
                "path": str(path),
                "sha256": sha256_file(path),
            })
        if baseline_by_id[photo_id]["kind"] == "defect":
            crop_path = output / "visuals" / "truth-crops" / f"{photo_id}.jpg"
            render_target_crop(baseline_by_id[photo_id], crop_path)
            if crop_path.is_file():
                visual_rows.append({
                    "photo_id": photo_id,
                    "variant": "truth_crop_current_label",
                    "path": str(crop_path),
                    "sha256": sha256_file(crop_path),
                })

    payload = {
        "version": "label-qc-oracle-diagnostics-v1",
        "created_at": utc_now(),
        "purpose": "offline responsibility attribution before model complexity",
        "repo_head": git_head(args.repo_root),
        "regression_set_only": True,
        "blind_set": False,
        "promotion_allowed": False,
        "training_started": False,
        "deployment_started": False,
        "production_writes": 0,
        "originals_modified": 0,
        "inputs": {
            "manifest": str(args.manifest.resolve()),
            "manifest_sha256": sha256_file(args.manifest),
            "records": len(records),
            "tray_model": str(args.tray_model.resolve()),
            "tray_model_sha256": sha256_file(args.tray_model),
            "label_model": str(args.label_model.resolve()),
            "label_model_sha256": sha256_file(args.label_model),
            "human_audit": audit_input,
            "params": {
                "tray_threshold": args.tray_threshold,
                "label_threshold": args.label_threshold,
                "slice_size": args.slice_size,
                "slice_overlap": args.slice_overlap,
                "nms_iou": args.nms_iou,
                "nms_ios": args.nms_ios,
                "onnx_threads": int(os.getenv("LABEL_QC_ONNX_THREADS", "2")),
                "execution_provider": "CPUExecutionProvider",
            },
        },
        "baseline": baseline,
        "sliced_candidate": sliced,
        "comparison": comparison,
        "visuals": visual_rows,
        "next_gate": {
            "instance_matching_allowed": (
                baseline["stage_counts"].get("assignment_or_crop_path_miss", 0) > 0
            ),
            "sliced_tray_followup_allowed": comparison["development_continue_signal"],
            "gpu_rental_required": False,
            "notes": [
                "Resolve every truth_conflict before any promotion decision.",
                "The exposed 7+20 set can only reject regressions; it cannot prove generalisation.",
                "A fresh prospective task/day/SKU-isolated blind set is required after thresholds freeze.",
            ],
        },
    }
    receipt = output / "receipt.json"
    receipt.write_text(
        json.dumps(payload, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )
    print(json.dumps({
        "receipt": str(receipt),
        "baseline": {
            key: baseline[key] for key in (
                "defect_hits", "defect_total", "tray_target_hits", "tray_target_total",
                "normal_flagged_photos", "false_flags", "p95_latency_ms", "stage_counts",
            )
        },
        "sliced_candidate": {
            key: sliced[key] for key in (
                "defect_hits", "defect_total", "tray_target_hits", "tray_target_total",
                "normal_flagged_photos", "false_flags", "p95_latency_ms", "stage_counts",
            )
        },
        "comparison": comparison,
        "production_writes": 0,
        "originals_modified": 0,
    }, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
