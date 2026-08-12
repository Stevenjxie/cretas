#!/usr/bin/env python3
"""Compare a tray candidate with production through the real tray+label pipeline."""
from __future__ import annotations

import argparse
import hashlib
import json
import shutil
import sys
import tempfile
import time
from pathlib import Path
from typing import Any

import numpy as np
from PIL import Image, ImageOps

from evaluate_candidate import containment, load_records, percentile, sha256_file
import work_area


def load_work_area_annotations(paths: list[Path]) -> tuple[dict[str, dict[str, Any]], dict[str, Any]]:
    annotations: dict[str, dict[str, Any]] = {}
    audit_rows: list[dict[str, Any]] = []
    for root in paths:
        if not root.is_dir():
            raise FileNotFoundError(root)
        for path in sorted(root.glob("*.json")):
            payload = json.loads(path.read_text(encoding="utf-8"))
            annotation = work_area.validate_human_annotation(payload)
            source_photo_id = str(annotation.get("source_photo_id") or annotation["photo_id"])
            if source_photo_id in annotations:
                raise RuntimeError(f"duplicate work-area truth for source photo: {source_photo_id}")
            annotations[source_photo_id] = annotation
            audit_rows.append({
                "path": str(path), "sha256": sha256_file(path),
                "annotation_photo_id": annotation["photo_id"],
                "source_photo_id": source_photo_id,
                "source_sha256": annotation.get("source_sha256"),
                "judgeable": annotation["judgeable"],
            })
    digest = hashlib.sha256(json.dumps(
        audit_rows, ensure_ascii=False, sort_keys=True, separators=(",", ":"),
    ).encode("utf-8")).hexdigest()
    return annotations, {
        "version": "vision-lab-work-area-evaluation-input-v1",
        "roots": [str(path) for path in paths],
        "file_count": len(audit_rows),
        "annotation_set_sha256": digest,
        "source": "human",
        "rows": audit_rows,
    }


def _scope_metrics() -> dict[str, int]:
    return {
        "defect_total": 0, "defect_hits": 0,
        "tray_target_total": 0, "tray_target_hits": 0,
        "detected_trays": 0, "normal_trays": 0,
        "missing_label_flags": 0, "false_flags": 0,
    }


def _annotation_for_record(
    row: dict[str, Any], annotations: dict[str, dict[str, Any]],
) -> dict[str, Any] | None:
    annotation = annotations.get(str(row["photo_id"]))
    if annotation is None:
        return None
    if not annotation.get("source_sha256") or annotation["source_sha256"] != row.get("image_sha256"):
        raise RuntimeError(f"work-area source image hash mismatch: {row['photo_id']}")
    return annotation


def _truth_scope(
    bbox: Any, annotation: dict[str, Any] | None,
) -> str:
    if annotation is None or annotation.get("judgeable") is not True:
        return work_area.UNKNOWN_WORK_AREA
    if not isinstance(bbox, list) or len(bbox) != 4:
        return work_area.UNKNOWN_WORK_AREA
    return work_area.classify_box_center(bbox, annotation["polygon"])


def evaluate(
    model_dir: Path, records: list[dict[str, Any]], screening, yolo, threshold: float,
    work_area_annotations: dict[str, dict[str, Any]] | None = None,
) -> dict[str, Any]:
    work_area_annotations = work_area_annotations or {}
    params = screening.ScreeningParams(tray_conf=0.60, label_conf=threshold)
    models = yolo.LabelQcYoloModels(model_dir=model_dir)
    if not models.available:
        raise RuntimeError(models.load_error or "ONNX models unavailable")
    totals = {
        "defect_total": 0, "defect_hits": 0, "tray_target_total": 0,
        "tray_target_hits": 0, "normal_photos": 0, "false_flags": 0,
    }
    groups: dict[str, dict[str, int]] = {}
    scope_groups = {name: _scope_metrics() for name in work_area.WORK_AREA_GROUPS}
    roi_records = {"with_human_roi": 0, "without_human_roi": 0, "unjudgeable": 0}
    latencies: list[float] = []
    details: list[dict[str, Any]] = []
    for row in records:
        with Image.open(row["image"]) as opened:
            frame = np.array(ImageOps.exif_transpose(opened).convert("RGB"))
        started = time.perf_counter()
        result = screening.screen_image(frame, models, params)
        latencies.append((time.perf_counter() - started) * 1000)
        annotation = _annotation_for_record(row, work_area_annotations)
        if annotation is None:
            roi_records["without_human_roi"] += 1
        else:
            roi_records["with_human_roi"] += 1
            roi_records["unjudgeable"] += int(not annotation["judgeable"])
        tray_scopes: dict[str, int] = {name: 0 for name in work_area.WORK_AREA_GROUPS}
        for tray in result.trays:
            scope = work_area.classify_pixel_box(tray.box, frame.shape[1], frame.shape[0], annotation)
            tray_scopes[scope] += 1
            scope_groups[scope]["detected_trays"] += 1
        group = groups.setdefault(row["group"], {
            "defect_total": 0, "defect_hits": 0, "tray_target_total": 0,
            "tray_target_hits": 0, "normal_photos": 0, "false_flags": 0,
        })
        if row["human_label"] in {"MISSING_WHITE_LABEL", "MISSING_COLOR_LABEL"}:
            totals["defect_total"] += 1
            group["defect_total"] += 1
            height, width = frame.shape[:2]
            bbox = row.get("bbox")
            truth = None
            if isinstance(bbox, list) and len(bbox) == 4:
                truth = [float(bbox[0]) * width, float(bbox[1]) * height,
                         float(bbox[2]) * width, float(bbox[3]) * height]
                totals["tray_target_total"] += 1
                group["tray_target_total"] += 1
            scope = _truth_scope(bbox, annotation)
            scope_groups[scope]["defect_total"] += 1
            scope_groups[scope]["tray_target_total"] += int(truth is not None)
            covered = truth is None or any(containment(tray.box, truth) >= 0.5 for tray in result.trays)
            hit = any(
                tray.verdict == row["human_label"]
                and (truth is None or containment(tray.box, truth) >= 0.5)
                for tray in result.suspects
            )
            totals["tray_target_hits"] += int(covered and truth is not None)
            group["tray_target_hits"] += int(covered and truth is not None)
            totals["defect_hits"] += int(hit)
            group["defect_hits"] += int(hit)
            scope_groups[scope]["tray_target_hits"] += int(covered and truth is not None)
            scope_groups[scope]["defect_hits"] += int(hit)
            for suspect in result.suspects:
                suspect_scope = work_area.classify_pixel_box(
                    suspect.box, frame.shape[1], frame.shape[0], annotation,
                )
                scope_groups[suspect_scope]["missing_label_flags"] += 1
            details.append({
                "photo_id": row["photo_id"], "group": row["group"], "kind": "defect",
                "tray_target_covered": covered, "hit": hit, "trays": len(result.trays),
                "suspects": len(result.suspects),
                "work_area": scope, "work_area_trays": tray_scopes,
            })
        else:
            totals["normal_photos"] += 1
            totals["false_flags"] += len(result.suspects)
            group["normal_photos"] += 1
            group["false_flags"] += len(result.suspects)
            for name, count in tray_scopes.items():
                scope_groups[name]["normal_trays"] += count
            false_flag_scopes = {name: 0 for name in work_area.WORK_AREA_GROUPS}
            for suspect in result.suspects:
                scope = work_area.classify_pixel_box(
                    suspect.box, frame.shape[1], frame.shape[0], annotation,
                )
                false_flag_scopes[scope] += 1
                scope_groups[scope]["missing_label_flags"] += 1
                scope_groups[scope]["false_flags"] += 1
            details.append({
                "photo_id": row["photo_id"], "group": row["group"], "kind": "normal",
                "false_flags": len(result.suspects), "trays": len(result.trays),
                "work_area_trays": tray_scopes, "work_area_false_flags": false_flag_scopes,
            })
    return {
        **totals, "p95_latency_ms": round(percentile(latencies, 0.95), 3),
        "groups": groups, "details": details,
        "work_area": {
            "scope_rule": "tray_center_in_human_polygon",
            "outside_samples_retained": True,
            "records": roi_records,
            "groups": scope_groups,
        },
    }


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo-root", required=True, type=Path)
    parser.add_argument("--production-tray", required=True, type=Path)
    parser.add_argument("--candidate-tray", required=True, type=Path)
    parser.add_argument("--production-label", required=True, type=Path)
    parser.add_argument("--manifest", required=True, action="append", type=Path)
    parser.add_argument("--threshold", type=float, default=0.20)
    parser.add_argument("--onnx-parity-mismatches", type=int, required=True)
    parser.add_argument("--production-onnx-parity-mismatches", type=int, required=True)
    parser.add_argument("--work-area-annotations", action="append", type=Path)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    for path in (args.production_tray, args.candidate_tray, args.production_label):
        if not path.is_file():
            raise FileNotFoundError(path)
    sys.path.insert(0, str((args.repo_root / "backend" / "python").resolve()))
    from label_qc.services import screening
    from label_qc.services import yolo_detector as yolo

    records = load_records(args.manifest)
    work_area_annotations, work_area_input = load_work_area_annotations(args.work_area_annotations or [])
    protected_ids = {str(row["photo_id"]) for row in records}
    work_area_input["matched_protected_records"] = len(protected_ids & set(work_area_annotations))
    work_area_input["unmatched_annotation_records"] = sorted(set(work_area_annotations) - protected_ids)
    with tempfile.TemporaryDirectory(prefix="visionlab-tray-eval-") as temporary:
        temp = Path(temporary)
        baseline_dir, candidate_dir = temp / "baseline", temp / "candidate"
        baseline_dir.mkdir()
        candidate_dir.mkdir()
        shutil.copy2(args.production_tray, baseline_dir / "tray.onnx")
        shutil.copy2(args.candidate_tray, candidate_dir / "tray.onnx")
        for directory in (baseline_dir, candidate_dir):
            shutil.copy2(args.production_label, directory / "label.onnx")
        baseline = evaluate(
            baseline_dir, records, screening, yolo, args.threshold, work_area_annotations,
        )
        candidate = evaluate(
            candidate_dir, records, screening, yolo, args.threshold, work_area_annotations,
        )
    payload = {
        "version": "vision-lab-tray-evaluation-v1",
        "artifact_sha256": sha256_file(args.candidate_tray),
        "production_tray_sha256": sha256_file(args.production_tray),
        "production_label_sha256": sha256_file(args.production_label),
        "production_pipeline_replay": True,
        "onnx_parity_mismatches": args.onnx_parity_mismatches,
        "production_onnx_parity_mismatches": args.production_onnx_parity_mismatches,
        "threshold": args.threshold, "records": len(records),
        "protected_manifests": [
            {"path": str(path), "sha256": sha256_file(path)} for path in args.manifest
        ],
        "work_area_input": work_area_input,
        "baseline": baseline, "candidate": candidate,
    }
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(payload, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
