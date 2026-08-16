#!/usr/bin/env python3
"""Evaluate a screening-parameter change on a prospective human-normal batch.

The exact watermark interval is read from the local VisionLab SQLite cache in
read-only mode.  Images and human labels are hash-verified, all supplied
training provenances plus the protected and sealed-final sets must be disjoint,
and the same production ONNX artifacts are used on both sides.  Only screening
parameters differ.
"""
from __future__ import annotations

import argparse
import collections
import json
import shutil
import sys
import tempfile
import time
from pathlib import Path
from typing import Any, Sequence

import numpy as np
from PIL import Image, ImageOps

import label_qc_independent_normal_shadow as normal_shadow
import label_qc_oracle_diagnostics as diagnostics
from label_qc_tray_edge_context_eval import load_and_verify


VERSION = "label-qc-screening-param-normal-shadow-v1"


def assert_final_disjoint(
    records: Sequence[dict[str, Any]], final_receipt: dict[str, Any],
) -> dict[str, Any]:
    final = (final_receipt.get("split_lock") or {})
    if final_receipt.get("final", {}).get("model_inference_started") is not False:
        raise RuntimeError("sealed final set has already started model inference")
    batch_photo_ids = {str(row["photo_id"]) for row in records}
    batch_task_ids = {str(row["task_id"]) for row in records}
    batch_hashes = {str(row["image_sha256"]).lower() for row in records}
    overlaps = {
        "photo_ids": sorted(batch_photo_ids & set(map(str, final.get("final_photo_ids") or []))),
        "task_ids": sorted(batch_task_ids & set(map(str, final.get("final_task_ids") or []))),
        "image_sha256": sorted(
            batch_hashes
            & {str(value).lower() for value in final.get("final_image_sha256") or []}
        ),
    }
    if any(overlaps.values()):
        raise RuntimeError(f"prospective batch overlaps sealed final set: {overlaps}")
    return {"disjoint": True, "overlaps": overlaps}


def assert_final_exact(
    records: Sequence[dict[str, Any]], final_receipt: dict[str, Any],
) -> dict[str, Any]:
    """Require the batch to be exactly the once-sealed final split."""
    final = final_receipt.get("split_lock") or {}
    final_state = final_receipt.get("final") or {}
    if (
        final.get("task_disjoint") is not True
        or final.get("content_disjoint") is not True
        or final.get("final_model_inference_started") is not False
        or final_state.get("evaluated") is not False
        or final_state.get("model_inference_started") is not False
    ):
        raise RuntimeError("sealed final receipt is already consumed or invalid")
    actual = {
        "photo_ids": {str(row["photo_id"]) for row in records},
        "task_ids": {str(row["task_id"]) for row in records},
        "image_sha256": {str(row["image_sha256"]).lower() for row in records},
    }
    expected = {
        "photo_ids": set(map(str, final.get("final_photo_ids") or [])),
        "task_ids": set(map(str, final.get("final_task_ids") or [])),
        "image_sha256": {
            str(value).lower() for value in final.get("final_image_sha256") or []
        },
    }
    mismatches = {
        name: {
            "missing": sorted(expected[name] - actual[name]),
            "unexpected": sorted(actual[name] - expected[name]),
        }
        for name in expected
        if actual[name] != expected[name]
    }
    if (
        mismatches
        or len(records) != int(final.get("final_photos", -1))
        or len(actual["photo_ids"]) != len(records)
        or len(actual["image_sha256"]) != len(records)
        or len(actual["task_ids"]) != int(final.get("final_tasks", -1))
    ):
        raise RuntimeError(f"batch is not the exact sealed final set: {mismatches}")
    return {
        "exact_match": True,
        "photos": len(records),
        "tasks": len(actual["task_ids"]),
        "photo_ids": sorted(actual["photo_ids"]),
        "task_ids": sorted(actual["task_ids"]),
        "image_sha256": sorted(actual["image_sha256"]),
    }


def compact_tray(tray: Any) -> dict[str, Any]:
    return {
        "index": int(tray.index),
        "box": [round(float(value), 3) for value in tray.box],
        "tray_confidence": round(float(tray.confidence), 6),
        "verdict": str(tray.verdict),
        "has_white": bool(tray.has_white),
        "has_color": bool(tray.has_color),
        "own_label_count": int(tray.own_label_count),
    }


def compact_result(result: Any, latency_ms: float) -> dict[str, Any]:
    verdict_counts = collections.Counter(str(tray.verdict) for tray in result.trays)
    return {
        "latency_ms": round(latency_ms, 3),
        "tray_count": len(result.trays),
        "false_flags": len(result.suspects),
        "flagged": bool(result.suspects),
        "review_candidate_count": len(result.review_candidates),
        "unjudgeable_count": sum(
            str(tray.verdict) == "UNJUDGEABLE" for tray in result.review_candidates
        ),
        "verdict_counts": dict(sorted(verdict_counts.items())),
        "suspects": [compact_tray(tray) for tray in result.suspects],
        "review_candidates": [compact_tray(tray) for tray in result.review_candidates],
    }


def box_iou(left: Sequence[float], right: Sequence[float]) -> float:
    intersection = max(0.0, min(left[2], right[2]) - max(left[0], right[0])) * max(
        0.0, min(left[3], right[3]) - max(left[1], right[1]),
    )
    left_area = max(0.0, left[2] - left[0]) * max(0.0, left[3] - left[1])
    right_area = max(0.0, right[2] - right[0]) * max(0.0, right[3] - right[1])
    return intersection / max(1e-9, left_area + right_area - intersection)


def hidden_baseline_claims(
    baseline: dict[str, Any], candidate: dict[str, Any], threshold: float = 0.80,
) -> list[dict[str, Any]]:
    review_boxes = [row["box"] for row in candidate.get("review_candidates") or []]
    return [
        row for row in baseline.get("suspects") or []
        if not any(box_iou(row["box"], other) >= threshold for other in review_boxes)
    ]


def summarize(details: Sequence[dict[str, Any]], key: str) -> dict[str, Any]:
    rows = [row[key] for row in details]
    latencies = [float(row["latency_ms"]) for row in rows]
    verdicts: collections.Counter[str] = collections.Counter()
    for row in rows:
        verdicts.update(row["verdict_counts"])
    return {
        "photos": len(rows),
        "total_trays": sum(int(row["tray_count"]) for row in rows),
        "false_flags": sum(int(row["false_flags"]) for row in rows),
        "flagged_photos": sum(bool(row["flagged"]) for row in rows),
        "review_candidates": sum(int(row["review_candidate_count"]) for row in rows),
        "unjudgeable": sum(int(row["unjudgeable_count"]) for row in rows),
        "verdict_counts": dict(sorted(verdicts.items())),
        "p95_latency_ms": round(diagnostics.percentile(latencies, 0.95), 3),
        "max_latency_ms": round(max(latencies), 3),
    }


def build_gate(
    baseline: dict[str, Any], candidate: dict[str, Any],
    new_flagged_photo_ids: Sequence[str], hidden_claim_count: int,
) -> dict[str, Any]:
    latency_ratio = (
        float(candidate["p95_latency_ms"]) / float(baseline["p95_latency_ms"])
        if float(baseline["p95_latency_ms"]) > 0 else float("inf")
    )
    errors: list[str] = []
    if int(candidate["false_flags"]) > int(baseline["false_flags"]):
        errors.append("total human-normal false flags increased")
    if new_flagged_photo_ids:
        errors.append("one or more human-normal photos gained a false flag")
    if hidden_claim_count:
        errors.append("a baseline missing-label claim was silently hidden from review")
    if latency_ratio > 1.15:
        errors.append("candidate p95 latency exceeds 1.15x baseline")
    return {
        "normal_specificity_passed": not errors,
        "missing_positive_recall_validated": False,
        "missing_positive_photos": 0,
        "latency_ratio": round(latency_ratio, 6),
        "promotion_allowed": False,
        "deployment_allowed": False,
        "errors": errors,
    }


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo-root", required=True, type=Path)
    parser.add_argument("--state-db", required=True, type=Path)
    parser.add_argument("--before-reviewed-at", required=True)
    parser.add_argument("--before-photo-id", required=True)
    parser.add_argument("--after-reviewed-at", required=True)
    parser.add_argument("--after-photo-id", required=True)
    parser.add_argument("--production-tray", required=True, type=Path)
    parser.add_argument("--production-tray-sha256", required=True)
    parser.add_argument("--production-label", required=True, type=Path)
    parser.add_argument("--production-label-sha256", required=True)
    parser.add_argument("--dataset-provenance", required=True, action="append", type=Path)
    parser.add_argument("--protected-manifest", required=True, type=Path)
    parser.add_argument("--final-lock-receipt", required=True, type=Path)
    parser.add_argument("--baseline-min-crop-px", type=int, default=120)
    parser.add_argument("--candidate-min-crop-px", type=int, default=150)
    parser.add_argument("--sealed-final-evaluation", action="store_true")
    parser.add_argument("--output", required=True, type=Path)
    return parser


def main() -> None:
    args = build_parser().parse_args()
    output = args.output.resolve()
    if output.exists():
        raise FileExistsError(f"refusing to overwrite parameter shadow: {output}")
    if args.candidate_min_crop_px <= args.baseline_min_crop_px:
        raise ValueError("candidate minimum crop must exceed baseline")
    tray = args.production_tray.resolve()
    label = args.production_label.resolve()
    if not tray.is_file() or diagnostics.sha256_file(tray) != args.production_tray_sha256:
        raise RuntimeError("production tray model missing or drifted")
    if not label.is_file() or diagnostics.sha256_file(label) != args.production_label_sha256:
        raise RuntimeError("production label model missing or drifted")

    records = normal_shadow.load_normal_batch(
        args.state_db.resolve(), args.before_reviewed_at, args.before_photo_id,
        args.after_reviewed_at, args.after_photo_id,
    )
    protected = args.protected_manifest.resolve()
    provenance_audits = [
        normal_shadow.assert_independent_batch(records, path.resolve(), protected)
        for path in args.dataset_provenance
    ]
    final_path = args.final_lock_receipt.resolve()
    final_receipt = load_and_verify(final_path)
    final_audit = (
        assert_final_exact(records, final_receipt)
        if args.sealed_final_evaluation
        else assert_final_disjoint(records, final_receipt)
    )
    final_consumption: dict[str, Any] | None = None
    if args.sealed_final_evaluation:
        marker_path = final_path.parent / "sealed-final-consumption.json"
        final_consumption = {
            "version": "label-qc-sealed-final-consumption-v1",
            "started_at": diagnostics.utc_now(),
            "status": "model_inference_started",
            "final_lock_receipt": str(final_path),
            "final_lock_receipt_sha256": diagnostics.sha256_file(final_path),
            "output": str(output),
            "production_tray_sha256": diagnostics.sha256_file(tray),
            "production_label_sha256": diagnostics.sha256_file(label),
            "baseline_min_crop_px": args.baseline_min_crop_px,
            "candidate_min_crop_px": args.candidate_min_crop_px,
            "photos": len(records),
            "tasks": len({row["task_id"] for row in records}),
            "training_allowed": False,
            "production_writes": 0,
        }
        try:
            with marker_path.open("x", encoding="utf-8") as handle:
                json.dump(final_consumption, handle, ensure_ascii=False, indent=2)
                handle.write("\n")
        except FileExistsError as exc:
            raise RuntimeError(f"sealed final set has already been consumed: {marker_path}") from exc
        final_consumption["path"] = str(marker_path)
        final_consumption["sha256"] = diagnostics.sha256_file(marker_path)
    print(json.dumps({
        "stage": "inputs_verified",
        "photos": len(records),
        "tasks": len({row["task_id"] for row in records}),
        "skus": len({row["sku_code"] for row in records}),
        "training_provenances": len(provenance_audits),
        "sealed_final_mode": args.sealed_final_evaluation,
        "sealed_final_verified": (
            final_audit["exact_match"]
            if args.sealed_final_evaluation else final_audit["disjoint"]
        ),
    }), flush=True)

    sys.path.insert(0, str((args.repo_root.resolve() / "backend" / "python").resolve()))
    from label_qc.services import screening
    from label_qc.services import yolo_detector as yolo

    baseline_params = screening.ScreeningParams(
        tray_conf=0.60, label_conf=0.20,
        min_crop_px=args.baseline_min_crop_px, capture_trace=False,
    )
    candidate_params = screening.ScreeningParams(
        tray_conf=0.60, label_conf=0.20,
        min_crop_px=args.candidate_min_crop_px, capture_trace=False,
    )
    with tempfile.TemporaryDirectory(prefix="label-qc-screening-param-shadow-") as temporary:
        model_dir = Path(temporary)
        shutil.copy2(tray, model_dir / "tray.onnx")
        shutil.copy2(label, model_dir / "label.onnx")
        models = yolo.LabelQcYoloModels(model_dir=model_dir)
        if not models.available:
            raise RuntimeError(models.load_error or "production label QC models unavailable")
        with Image.open(records[0]["image"]) as opened:
            warmup = np.asarray(ImageOps.exif_transpose(opened).convert("RGB"))
        screening.screen_image(warmup, models, baseline_params)
        screening.screen_image(warmup, models, candidate_params)
        details: list[dict[str, Any]] = []
        for index, record in enumerate(records, start=1):
            with Image.open(record["image"]) as opened:
                frame = np.asarray(ImageOps.exif_transpose(opened).convert("RGB"))
            variants: dict[str, dict[str, Any]] = {}
            ordered = (
                (("baseline", baseline_params), ("candidate", candidate_params))
                if index % 2 else
                (("candidate", candidate_params), ("baseline", baseline_params))
            )
            for name, params in ordered:
                started = time.perf_counter()
                result = screening.screen_image(frame, models, params)
                variants[name] = compact_result(
                    result, (time.perf_counter() - started) * 1000.0,
                )
            hidden = hidden_baseline_claims(variants["baseline"], variants["candidate"])
            details.append({
                **record,
                "image_size": [int(frame.shape[1]), int(frame.shape[0])],
                "baseline": variants["baseline"],
                "candidate": variants["candidate"],
                "hidden_baseline_claims": hidden,
                "evaluation_consumed": True,
            })
            print(json.dumps({
                "stage": "shadow_progress", "completed": index, "total": len(records),
            }), flush=True)

    baseline = summarize(details, "baseline")
    candidate = summarize(details, "candidate")
    new_flags = [
        row["photo_id"] for row in details
        if row["candidate"]["flagged"] and not row["baseline"]["flagged"]
    ]
    reduced_flags = [
        row["photo_id"] for row in details
        if row["baseline"]["flagged"] and not row["candidate"]["flagged"]
    ]
    hidden_count = sum(len(row["hidden_baseline_claims"]) for row in details)
    gate = build_gate(baseline, candidate, new_flags, hidden_count)
    payload = {
        "version": VERSION,
        "created_at": diagnostics.utc_now(),
        "purpose": (
            "single-use sealed-final task-disjoint human-normal check of min_crop_px semantics"
            if args.sealed_final_evaluation else
            "prospective task-disjoint human-normal check of min_crop_px semantics"
        ),
        "promotion_evidence": True,
        "evaluation_consumed": True,
        "inputs": {
            "state_db": str(args.state_db.resolve()),
            "state_db_open_mode": "read_only",
            "watermark_before": {
                "reviewed_at": args.before_reviewed_at, "photo_id": args.before_photo_id,
            },
            "watermark_after": {
                "reviewed_at": args.after_reviewed_at, "photo_id": args.after_photo_id,
            },
            "production_tray": str(tray),
            "production_tray_sha256": diagnostics.sha256_file(tray),
            "production_label": str(label),
            "production_label_sha256": diagnostics.sha256_file(label),
            "dataset_provenances": [
                {"path": str(path.resolve()), "sha256": diagnostics.sha256_file(path)}
                for path in args.dataset_provenance
            ],
            "protected_manifest": str(protected),
            "protected_manifest_sha256": diagnostics.sha256_file(protected),
            "final_lock_receipt": str(final_path),
            "final_lock_receipt_sha256": diagnostics.sha256_file(final_path),
            "params": {
                "baseline_min_crop_px": args.baseline_min_crop_px,
                "candidate_min_crop_px": args.candidate_min_crop_px,
                "tray_conf": 0.60,
                "label_conf": 0.20,
                "execution_provider": "CPUExecutionProvider",
            },
        },
        "batch": {
            "photos": len(records),
            "tasks": len({row["task_id"] for row in records}),
            "skus": len({row["sku_code"] for row in records}),
            "human_truth": "NO_DEFECT",
            "missing_positive_photos": 0,
            "training_independence": provenance_audits,
            "sealed_final_exact_match" if args.sealed_final_evaluation
            else "sealed_final_independence": final_audit,
            "future_independent_after_evaluation": False,
        },
        "baseline": baseline,
        "candidate": candidate,
        "comparison": {
            "new_flagged_photo_ids": new_flags,
            "reduced_flagged_photo_ids": reduced_flags,
            "hidden_baseline_claims": hidden_count,
        },
        "gate": gate,
        "details": details,
        "sealed_final_evaluation": args.sealed_final_evaluation,
        "final_consumption": final_consumption,
        "final_model_inference_started": args.sealed_final_evaluation,
        "training_started": False,
        "deployment_started": False,
        "production_reads": 0,
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
        "baseline": baseline,
        "candidate": candidate,
        "comparison": payload["comparison"],
        "gate": gate,
        "sealed_final_evaluation": args.sealed_final_evaluation,
        "final_model_inference_started": args.sealed_final_evaluation,
        "production_writes": 0,
    }, ensure_ascii=False, indent=2), flush=True)


if __name__ == "__main__":
    main()
