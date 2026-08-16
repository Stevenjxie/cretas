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


TRUTH_ADJUDICATION_VERSION = "label-qc-protected-truth-adjudication-v1"


def apply_truth_adjudication(
    records: list[dict[str, Any]], adjudication_path: Path,
    manifest_paths: list[Path],
) -> tuple[list[dict[str, Any]], dict[str, Any]]:
    payload = json.loads(adjudication_path.read_text(encoding="utf-8"))
    if payload.get("version") != TRUTH_ADJUDICATION_VERSION:
        raise RuntimeError("unsupported protected-truth adjudication")
    expected_manifests = {
        str(path.resolve()): sha256_file(path.resolve()) for path in manifest_paths
    }
    supplied_manifests = {
        str(Path(str(row.get("path") or "")).resolve()): str(row.get("sha256") or "")
        for row in payload.get("protected_manifests") or []
    }
    if supplied_manifests != expected_manifests:
        raise RuntimeError("truth adjudication protected-manifest binding mismatch")

    by_id = {str(row["photo_id"]): row for row in records}
    source_task_ids = {
        str(row.get("task_id") or "") for row in records if row.get("task_id")
    }
    overrides: dict[str, dict[str, Any]] = {}
    added_task_ids: set[str] = set()
    evidence_rows: list[dict[str, Any]] = []
    for override in payload.get("records") or []:
        photo_id = str(override.get("photo_id") or "")
        action = str(override.get("action") or "")
        if not photo_id or photo_id in overrides:
            raise RuntimeError(f"invalid or duplicate adjudicated photo id: {photo_id}")
        if action not in {"exclude", "replace", "add"}:
            raise RuntimeError(f"unsupported truth adjudication action: {action}")
        if action == "add":
            if photo_id in by_id:
                raise RuntimeError(f"added truth already exists in protected records: {photo_id}")
            task_id = str(override.get("task_id") or "")
            if not task_id:
                raise RuntimeError(f"added truth lacks task id: {photo_id}")
            if task_id in source_task_ids or task_id in added_task_ids:
                raise RuntimeError(f"added truth is not task-independent: {task_id}")
            source_path = Path(str(override.get("image") or "")).resolve()
            if not source_path.is_file():
                raise RuntimeError(f"added truth source image is missing: {source_path}")
            source_sha = sha256_file(source_path)
            added_task_ids.add(task_id)
        else:
            if photo_id not in by_id:
                raise RuntimeError(f"adjudicated photo is absent from protected records: {photo_id}")
            source = by_id[photo_id]
            source_sha = sha256_file(Path(source["image"]))
        if source_sha != str(override.get("source_sha256") or ""):
            raise RuntimeError(f"truth adjudication source hash mismatch: {photo_id}")
        if (
            action != "add"
            and source["human_label"]
            != str(override.get("original_human_label") or "").upper()
        ):
            raise RuntimeError(f"truth adjudication original label mismatch: {photo_id}")
        evidence = override.get("evidence") or []
        if not evidence:
            raise RuntimeError(f"truth adjudication lacks human evidence: {photo_id}")
        checked_evidence = []
        for item in evidence:
            path = Path(str(item.get("path") or ""))
            if not path.is_file() or sha256_file(path) != item.get("sha256"):
                raise RuntimeError(f"truth adjudication evidence missing or drifted: {path}")
            annotation = json.loads(path.read_text(encoding="utf-8"))
            if annotation.get("reviewed") is not True or annotation.get("source") != "human":
                raise RuntimeError(f"truth adjudication evidence is not reviewed human truth: {path}")
            checked_evidence.append({"path": str(path), "sha256": sha256_file(path)})
        if action in {"replace", "add"}:
            human_label = str(override.get("human_label") or "").upper()
            if human_label not in {
                "NO_DEFECT", "MISSING_WHITE_LABEL", "MISSING_COLOR_LABEL",
            }:
                raise RuntimeError(f"unsupported adjudicated label: {human_label}")
            bbox = override.get("bbox")
            if bbox is not None and (
                not isinstance(bbox, list) or len(bbox) != 4
                or not (0 <= float(bbox[0]) < float(bbox[2]) <= 1)
                or not (0 <= float(bbox[1]) < float(bbox[3]) <= 1)
            ):
                raise RuntimeError(f"invalid adjudicated bbox: {photo_id}")
        overrides[photo_id] = override
        evidence_rows.extend(checked_evidence)

    adjusted: list[dict[str, Any]] = []
    excluded: list[str] = []
    replaced: list[str] = []
    added: list[str] = []
    for record in records:
        photo_id = str(record["photo_id"])
        override = overrides.get(photo_id)
        if override is None:
            adjusted.append(record)
            continue
        if override["action"] == "exclude":
            excluded.append(photo_id)
            continue
        updated = dict(record)
        updated["human_label"] = str(override["human_label"]).upper()
        updated["bbox"] = override.get("bbox")
        updated["group"] = str(override.get("group") or record["group"])
        adjusted.append(updated)
        replaced.append(photo_id)

    for photo_id, override in overrides.items():
        if override["action"] != "add":
            continue
        adjusted.append({
            "photo_id": photo_id,
            "task_id": str(override["task_id"]),
            "image": str(Path(str(override["image"])).resolve()),
            "image_sha256": str(override["source_sha256"]),
            "human_label": str(override["human_label"]).upper(),
            "bbox": override.get("bbox"),
            "group": str(override.get("group") or "prospective_human_defect"),
        })
        added.append(photo_id)

    return adjusted, {
        "path": str(adjudication_path),
        "sha256": sha256_file(adjudication_path),
        "version": payload["version"],
        "source": "reviewed_human_sidecar",
        "excluded_photo_ids": excluded,
        "replaced_photo_ids": replaced,
        "added_photo_ids": added,
        "evidence": evidence_rows,
        "protected_manifest_modified": False,
    }


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
    min_crop_px: int = 120,
) -> dict[str, Any]:
    work_area_annotations = work_area_annotations or {}
    params = screening.ScreeningParams(
        tray_conf=0.60,
        label_conf=threshold,
        min_crop_px=min_crop_px,
    )
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
    parser.add_argument("--baseline-min-crop-px", type=int, default=120)
    parser.add_argument("--candidate-min-crop-px", type=int, default=120)
    parser.add_argument("--onnx-parity-mismatches", type=int, required=True)
    parser.add_argument("--production-onnx-parity-mismatches", type=int, required=True)
    parser.add_argument("--work-area-annotations", action="append", type=Path)
    parser.add_argument("--truth-adjudication", type=Path)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    if args.baseline_min_crop_px <= 0 or args.candidate_min_crop_px <= 0:
        raise ValueError("minimum crop pixels must be positive")
    for path in (args.production_tray, args.candidate_tray, args.production_label):
        if not path.is_file():
            raise FileNotFoundError(path)
    sys.path.insert(0, str((args.repo_root / "backend" / "python").resolve()))
    from label_qc.services import screening
    from label_qc.services import yolo_detector as yolo

    records = load_records(args.manifest)
    original_record_count = len(records)
    truth_adjudication = None
    if args.truth_adjudication:
        adjudication_path = args.truth_adjudication.resolve()
        if not adjudication_path.is_file():
            raise FileNotFoundError(adjudication_path)
        records, truth_adjudication = apply_truth_adjudication(
            records, adjudication_path, [path.resolve() for path in args.manifest],
        )
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
            min_crop_px=args.baseline_min_crop_px,
        )
        candidate = evaluate(
            candidate_dir, records, screening, yolo, args.threshold, work_area_annotations,
            min_crop_px=args.candidate_min_crop_px,
        )
    payload = {
        "version": "vision-lab-tray-evaluation-v1",
        "artifact_sha256": sha256_file(args.candidate_tray),
        "production_tray_sha256": sha256_file(args.production_tray),
        "production_label_sha256": sha256_file(args.production_label),
        "production_pipeline_replay": True,
        "onnx_parity_mismatches": args.onnx_parity_mismatches,
        "production_onnx_parity_mismatches": args.production_onnx_parity_mismatches,
        "threshold": args.threshold,
        "records": len(records),
        "records_before_truth_adjudication": original_record_count,
        "truth_adjudication": truth_adjudication,
        "screening_params": {
            "baseline": {"min_crop_px": args.baseline_min_crop_px},
            "candidate": {"min_crop_px": args.candidate_min_crop_px},
        },
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
