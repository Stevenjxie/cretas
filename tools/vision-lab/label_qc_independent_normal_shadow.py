#!/usr/bin/env python3
"""Shadow-test production and a bound tray candidate on unseen human-normal photos.

The batch is selected from the local VisionLab SQLite cache by an exact
``(reviewed_at, photo_id)`` watermark interval.  The database is opened
read-only, every cached object is hash-verified, and any overlap with the tray
training provenance or protected holdout fails closed.
"""
from __future__ import annotations

import argparse
import collections
import json
import shutil
import sqlite3
import sys
import tempfile
import time
from pathlib import Path
from typing import Any, Sequence

import numpy as np
from PIL import Image, ImageOps

import label_qc_oracle_diagnostics as diagnostics
from label_qc_tray_edge_context_eval import EdgeContextTrayModels, load_and_verify


NORMAL_LABEL = "NO_DEFECT"
MISSING_LABELS = {
    "MISSING_WHITE_LABEL",
    "MISSING_COLOR_LABEL",
    "BOTH_MISSING",
}


def _sqlite_read_only_uri(path: Path) -> str:
    return f"file:{path.resolve().as_posix()}?mode=ro"


def _watermark_clause(prefix: str, inclusive: bool) -> tuple[str, str]:
    photo_operator = "<=" if inclusive else ">"
    time_operator = "<" if inclusive else ">"
    return (
        f"(reviewed_at {time_operator} ? OR "
        f"(reviewed_at = ? AND photo_id {photo_operator} ?))",
        prefix,
    )


def load_normal_batch(
    state_db: Path,
    before_reviewed_at: str,
    before_photo_id: str,
    after_reviewed_at: str,
    after_photo_id: str,
    verify_files: bool = True,
) -> list[dict[str, Any]]:
    """Load and validate an exact, human-reviewed normal-only batch."""
    if not state_db.is_file():
        raise FileNotFoundError(state_db)
    lower, _ = _watermark_clause("before", inclusive=False)
    upper, _ = _watermark_clause("after", inclusive=True)
    query = f"""
        SELECT photo_id, task_id, reviewed_at, sku_code, object_ref,
               sha256, local_path, annotations_json
          FROM photos
         WHERE {lower} AND {upper}
         ORDER BY reviewed_at, photo_id
    """
    connection = sqlite3.connect(_sqlite_read_only_uri(state_db), uri=True)
    connection.row_factory = sqlite3.Row
    try:
        rows = connection.execute(
            query,
            (
                before_reviewed_at,
                before_reviewed_at,
                before_photo_id,
                after_reviewed_at,
                after_reviewed_at,
                after_photo_id,
            ),
        ).fetchall()
    finally:
        connection.close()
    if not rows:
        raise RuntimeError("watermark interval contains no cached photos")

    records: list[dict[str, Any]] = []
    seen_photo_ids: set[str] = set()
    seen_hashes: set[str] = set()
    for row in rows:
        photo_id = str(row["photo_id"])
        image_hash = str(row["sha256"]).lower()
        if photo_id in seen_photo_ids:
            raise RuntimeError(f"duplicate photo id in batch: {photo_id}")
        if image_hash in seen_hashes:
            raise RuntimeError(f"duplicate image content in batch: {image_hash}")
        seen_photo_ids.add(photo_id)
        seen_hashes.add(image_hash)

        annotations = json.loads(str(row["annotations_json"]))
        if not isinstance(annotations, list) or not annotations:
            raise RuntimeError(f"photo lacks annotations: {photo_id}")
        human_labels: list[str] = []
        proposal_sources: list[str] = []
        for annotation in annotations:
            if not isinstance(annotation, dict):
                raise RuntimeError(f"malformed annotation for photo: {photo_id}")
            human_label = annotation.get("human_label")
            if human_label is None:
                continue
            # ``source`` records who proposed the annotation (AI or HUMAN).
            # A populated ``human_label`` is the reviewer's final verdict even
            # when it is attached to an AI-proposed box.
            human_labels.append(str(human_label))
            proposal_sources.append(str(annotation.get("source") or "UNKNOWN").upper())
        if not human_labels:
            raise RuntimeError(f"photo lacks human truth: {photo_id}")
        if any(label in MISSING_LABELS for label in human_labels):
            raise RuntimeError(f"positive photo cannot enter normal shadow: {photo_id}")
        if set(human_labels) != {NORMAL_LABEL}:
            raise RuntimeError(
                f"unsupported human truth for photo {photo_id}: {sorted(set(human_labels))}"
            )

        image_path = Path(str(row["local_path"]))
        if verify_files:
            if not image_path.is_file():
                raise FileNotFoundError(image_path)
            actual_hash = diagnostics.sha256_file(image_path)
            if actual_hash != image_hash:
                raise RuntimeError(f"cached object hash drift: {image_path}")
        records.append({
            "photo_id": photo_id,
            "task_id": str(row["task_id"]),
            "reviewed_at": str(row["reviewed_at"]),
            "sku_code": row["sku_code"],
            "object_ref": str(row["object_ref"]),
            "image": str(image_path),
            "image_sha256": image_hash,
            "human_labels": human_labels,
            "proposal_sources": proposal_sources,
        })
    return records


def load_bound_inputs(
    baseline_receipt_path: Path,
    candidate_receipt_path: Path,
    edge_receipt_path: Path,
) -> dict[str, Any]:
    """Validate the exact production, candidate, dataset and edge-policy binding."""
    baseline_path = baseline_receipt_path.resolve()
    candidate_path = candidate_receipt_path.resolve()
    edge_path = edge_receipt_path.resolve()
    baseline = load_and_verify(baseline_path)
    candidate = load_and_verify(candidate_path)
    edge = load_and_verify(edge_path)
    edge_inputs = edge.get("inputs") or {}
    edge_gate = edge.get("gate") or {}

    errors: list[str] = []
    if edge_inputs.get("baseline_receipt_sha256") != diagnostics.sha256_file(baseline_path):
        errors.append("edge receipt is not bound to baseline receipt")
    if edge_inputs.get("candidate_receipt_sha256") != diagnostics.sha256_file(candidate_path):
        errors.append("edge receipt is not bound to candidate receipt")
    if edge_inputs.get("fixed_windows") != "lower_left_and_lower_right":
        errors.append("edge windows are not the approved fixed pair")
    if edge_inputs.get("target_location_used_at_runtime") is not False:
        errors.append("target location leaked into edge runtime")
    for key, expected in (
        ("edge_confidence", 0.30),
        ("edge_width_fraction", 0.50),
        ("edge_height_fraction", 0.60),
    ):
        if float(edge_inputs.get(key, -1)) != expected:
            errors.append(f"unexpected {key}")
    if edge_gate.get("offline_development_passed") is not True:
        errors.append("edge development gate did not pass")
    if edge_gate.get("onnx_parity_mismatches") != 0:
        errors.append("candidate ONNX parity mismatch")
    if edge_gate.get("production_onnx_parity_mismatches") != 0:
        errors.append("production ONNX parity mismatch")
    if edge.get("deployment_started") is not False:
        errors.append("edge receipt reports a deployment")
    if candidate.get("deployment") is not False:
        errors.append("candidate receipt reports a deployment")

    dataset_manifest = Path(str(edge_inputs.get("dataset_manifest") or ""))
    dataset = load_and_verify(dataset_manifest)
    if diagnostics.sha256_file(dataset_manifest) != edge_inputs.get("dataset_manifest_sha256"):
        errors.append("dataset manifest hash drift")
    if candidate.get("dataset_id") != dataset.get("dataset_id"):
        errors.append("candidate/dataset id mismatch")
    if candidate.get("dataset_sha256") != dataset.get("dataset_sha256"):
        errors.append("candidate/dataset content mismatch")

    production_tray = Path(str(baseline["inputs"]["tray_model"]))
    label_model = Path(str(baseline["inputs"]["label_model"]))
    protected_manifest = Path(str(baseline["inputs"]["manifest"]))
    candidate_tray = Path(str(candidate["artifact"]))
    for path, expected_hash in (
        (production_tray, baseline["inputs"]["tray_model_sha256"]),
        (label_model, baseline["inputs"]["label_model_sha256"]),
        (protected_manifest, baseline["inputs"]["manifest_sha256"]),
        (candidate_tray, candidate["artifact_sha256"]),
    ):
        if not path.is_file() or diagnostics.sha256_file(path) != expected_hash:
            errors.append(f"bound input drift: {path}")
    provenance = dataset_manifest.parent / "provenance.json"
    if not provenance.is_file():
        errors.append(f"missing dataset provenance: {provenance}")
    if errors:
        raise RuntimeError("; ".join(errors))
    return {
        "baseline_receipt": baseline,
        "candidate_receipt": candidate,
        "edge_receipt": edge,
        "dataset_manifest": dataset_manifest,
        "dataset_provenance": provenance,
        "protected_manifest": protected_manifest,
        "production_tray": production_tray,
        "candidate_tray": candidate_tray,
        "label_model": label_model,
    }


def assert_independent_batch(
    records: Sequence[dict[str, Any]], provenance_path: Path, protected_path: Path,
) -> dict[str, Any]:
    provenance = load_and_verify(provenance_path)
    protected = load_and_verify(protected_path)
    training_rows = provenance.get("rows") or []
    protected_rows = protected.get("records") or []
    training_hashes = {
        str(row[key]).lower()
        for row in training_rows
        for key in ("source_sha256", "packed_image_sha256")
        if row.get(key)
    }
    protected_hashes = {
        str(row["image_sha256"]).lower()
        for row in protected_rows
        if row.get("image_sha256")
    }
    training_photo_ids = {
        str(row["source_photo_id"])
        for row in training_rows
        if row.get("source_photo_id")
    }
    training_task_ids = {
        str(row["task_id"])
        for row in training_rows
        if row.get("task_id")
    }
    protected_photo_ids = {
        str(row["photo_id"])
        for row in protected_rows
        if row.get("photo_id")
    }
    batch_hashes = {str(row["image_sha256"]).lower() for row in records}
    batch_photo_ids = {str(row["photo_id"]) for row in records}
    batch_task_ids = {str(row["task_id"]) for row in records if row.get("task_id")}
    overlaps = {
        "training_hashes": sorted(batch_hashes & training_hashes),
        "protected_hashes": sorted(batch_hashes & protected_hashes),
        "training_photo_ids": sorted(batch_photo_ids & training_photo_ids),
        "protected_photo_ids": sorted(batch_photo_ids & protected_photo_ids),
        "training_task_ids": sorted(batch_task_ids & training_task_ids),
    }
    if any(overlaps.values()):
        raise RuntimeError(f"shadow batch is not independent: {overlaps}")
    return {
        "independent": True,
        "training_provenance_rows": len(training_rows),
        "protected_rows": len(protected_rows),
        "overlaps": overlaps,
    }


def _variant_result(result: Any, models: Any, latency_ms: float) -> dict[str, Any]:
    suspects = []
    verdict_counts = collections.Counter(tray.verdict for tray in result.trays)
    for tray in result.suspects:
        suspects.append({
            "index": int(tray.index),
            "box": [round(float(value), 3) for value in tray.box],
            "confidence": round(float(tray.confidence), 6),
            "verdict": str(tray.verdict),
            "has_white": bool(tray.has_white),
            "has_color": bool(tray.has_color),
            "own_label_count": int(tray.own_label_count),
        })
    return {
        "latency_ms": round(latency_ms, 3),
        "tray_count": len(result.trays),
        "false_flags": len(suspects),
        "flagged": bool(suspects),
        "verdict_counts": dict(sorted(verdict_counts.items())),
        "suspects": suspects,
        "tray_detection": dict(models.last_stats),
    }


def evaluate_pair(
    records: Sequence[dict[str, Any]],
    production_models: Any,
    candidate_models: Any,
    screening: Any,
    params: Any,
) -> list[dict[str, Any]]:
    details: list[dict[str, Any]] = []
    with Image.open(records[0]["image"]) as opened:
        warmup = np.asarray(ImageOps.exif_transpose(opened).convert("RGB"))
    screening.screen_image(warmup, production_models, params)
    screening.screen_image(warmup, candidate_models, params)
    print(json.dumps({"stage": "warmup_complete"}), flush=True)

    for index, record in enumerate(records, start=1):
        with Image.open(record["image"]) as opened:
            frame = np.asarray(ImageOps.exif_transpose(opened).convert("RGB"))
        variants: dict[str, dict[str, Any]] = {}
        order = (
            (("baseline", production_models), ("candidate", candidate_models))
            if index % 2 else
            (("candidate", candidate_models), ("baseline", production_models))
        )
        for name, models in order:
            started = time.perf_counter()
            result = screening.screen_image(frame, models, params)
            elapsed = (time.perf_counter() - started) * 1000.0
            variants[name] = _variant_result(result, models, elapsed)
        baseline = variants["baseline"]
        candidate = variants["candidate"]
        details.append({
            **record,
            "image_size": [int(frame.shape[1]), int(frame.shape[0])],
            "baseline": baseline,
            "candidate": candidate,
            "false_flag_delta": candidate["false_flags"] - baseline["false_flags"],
        })
        if index % 5 == 0 or index == len(records):
            print(json.dumps({
                "stage": "shadow_progress",
                "completed": index,
                "total": len(records),
                "baseline_flagged": sum(row["baseline"]["flagged"] for row in details),
                "candidate_flagged": sum(row["candidate"]["flagged"] for row in details),
            }), flush=True)
    return details


def summarize_variant(details: Sequence[dict[str, Any]], key: str) -> dict[str, Any]:
    rows = [row[key] for row in details]
    latencies = [float(row["latency_ms"]) for row in rows]
    verdicts: collections.Counter[str] = collections.Counter()
    for row in rows:
        verdicts.update(row["verdict_counts"])
    flagged = sum(bool(row["flagged"]) for row in rows)
    return {
        "photos": len(rows),
        "total_trays": sum(int(row["tray_count"]) for row in rows),
        "false_flags": sum(int(row["false_flags"]) for row in rows),
        "flagged_photos": flagged,
        "flagged_photo_rate": round(flagged / len(rows), 6) if rows else 0.0,
        "verdict_counts": dict(sorted(verdicts.items())),
        "p50_latency_ms": round(diagnostics.percentile(latencies, 0.50), 3),
        "p95_latency_ms": round(diagnostics.percentile(latencies, 0.95), 3),
        "max_latency_ms": round(max(latencies), 3) if latencies else 0.0,
    }


def compare_details(details: Sequence[dict[str, Any]]) -> dict[str, Any]:
    increased = [row["photo_id"] for row in details if row["false_flag_delta"] > 0]
    reduced = [row["photo_id"] for row in details if row["false_flag_delta"] < 0]
    flagged_increased = [
        row["photo_id"] for row in details
        if row["candidate"]["flagged"] and not row["baseline"]["flagged"]
    ]
    flagged_reduced = [
        row["photo_id"] for row in details
        if row["baseline"]["flagged"] and not row["candidate"]["flagged"]
    ]
    return {
        "false_flags_increased": increased,
        "false_flags_reduced": reduced,
        "flagged_photos_increased": flagged_increased,
        "flagged_photos_reduced": flagged_reduced,
        "tasks_with_false_flag_increase": sorted({
            row["task_id"] for row in details if row["false_flag_delta"] > 0
        }),
        "tasks_with_false_flag_reduction": sorted({
            row["task_id"] for row in details if row["false_flag_delta"] < 0
        }),
    }


def build_normal_gate(
    baseline: dict[str, Any], candidate: dict[str, Any], comparison: dict[str, Any],
) -> dict[str, Any]:
    latency_ratio = (
        candidate["p95_latency_ms"] / baseline["p95_latency_ms"]
        if baseline["p95_latency_ms"] > 0 else float("inf")
    )
    errors: list[str] = []
    if comparison["false_flags_increased"]:
        errors.append("one or more human-normal photos gained false flags")
    if candidate["false_flags"] > baseline["false_flags"]:
        errors.append("total false flags increased")
    if candidate["flagged_photos"] > baseline["flagged_photos"]:
        errors.append("flagged-photo count increased")
    if latency_ratio > 1.50:
        errors.append("candidate p95 latency exceeds 1.50x baseline")
    return {
        "independent_normal_shadow_passed": not errors,
        "normal_specificity_validated": not errors,
        "missing_label_recall_validated": False,
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
    parser.add_argument("--baseline-receipt", required=True, type=Path)
    parser.add_argument("--candidate-receipt", required=True, type=Path)
    parser.add_argument("--edge-receipt", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    return parser


def main() -> None:
    args = build_parser().parse_args()
    output = args.output.resolve()
    if output.exists():
        raise FileExistsError(f"refusing to overwrite shadow evaluation: {output}")
    bound = load_bound_inputs(
        args.baseline_receipt, args.candidate_receipt, args.edge_receipt,
    )
    records = load_normal_batch(
        args.state_db.resolve(),
        args.before_reviewed_at,
        args.before_photo_id,
        args.after_reviewed_at,
        args.after_photo_id,
    )
    independence = assert_independent_batch(
        records, bound["dataset_provenance"], bound["protected_manifest"],
    )
    print(json.dumps({
        "stage": "inputs_verified",
        "photos": len(records),
        "tasks": len({row["task_id"] for row in records}),
        "skus": len({row["sku_code"] for row in records}),
        "independent": independence["independent"],
    }), flush=True)

    backend_python = (args.repo_root.resolve() / "backend" / "python").resolve()
    sys.path.insert(0, str(backend_python))
    from label_qc.services import screening
    from label_qc.services import yolo_detector as yolo

    params = screening.ScreeningParams(
        tray_conf=0.60,
        label_conf=0.20,
        capture_trace=False,
    )
    with tempfile.TemporaryDirectory(prefix="label-qc-independent-normal-") as temporary:
        root = Path(temporary)
        production_dir = root / "production"
        candidate_dir = root / "candidate"
        production_dir.mkdir()
        candidate_dir.mkdir()
        shutil.copy2(bound["production_tray"], production_dir / "tray.onnx")
        shutil.copy2(bound["candidate_tray"], candidate_dir / "tray.onnx")
        shutil.copy2(bound["label_model"], production_dir / "label.onnx")
        shutil.copy2(bound["label_model"], candidate_dir / "label.onnx")
        production_base = yolo.LabelQcYoloModels(model_dir=production_dir)
        candidate_base = yolo.LabelQcYoloModels(model_dir=candidate_dir)
        if not production_base.available or not candidate_base.available:
            raise RuntimeError(
                production_base.load_error or candidate_base.load_error or "model unavailable"
            )
        production_models = diagnostics.FullFrameModels(production_base)
        candidate_models = EdgeContextTrayModels(
            candidate_base,
            yolo.Detection,
            edge_confidence=0.30,
            width_fraction=0.50,
            height_fraction=0.60,
        )
        details = evaluate_pair(
            records, production_models, candidate_models, screening, params,
        )

    baseline = summarize_variant(details, "baseline")
    candidate = summarize_variant(details, "candidate")
    comparison = compare_details(details)
    gate = build_normal_gate(baseline, candidate, comparison)
    payload = {
        "version": "label-qc-independent-normal-shadow-v1",
        "created_at": diagnostics.utc_now(),
        "purpose": "independent human-normal false-positive shadow after tray candidate development gate",
        "inputs": {
            "state_db": str(args.state_db.resolve()),
            "state_db_open_mode": "read_only",
            "watermark_before": {
                "reviewed_at": args.before_reviewed_at,
                "photo_id": args.before_photo_id,
            },
            "watermark_after": {
                "reviewed_at": args.after_reviewed_at,
                "photo_id": args.after_photo_id,
            },
            "baseline_receipt": str(args.baseline_receipt.resolve()),
            "baseline_receipt_sha256": diagnostics.sha256_file(args.baseline_receipt),
            "candidate_receipt": str(args.candidate_receipt.resolve()),
            "candidate_receipt_sha256": diagnostics.sha256_file(args.candidate_receipt),
            "edge_receipt": str(args.edge_receipt.resolve()),
            "edge_receipt_sha256": diagnostics.sha256_file(args.edge_receipt),
            "dataset_provenance": str(bound["dataset_provenance"]),
            "dataset_provenance_sha256": diagnostics.sha256_file(bound["dataset_provenance"]),
            "protected_manifest": str(bound["protected_manifest"]),
            "protected_manifest_sha256": diagnostics.sha256_file(bound["protected_manifest"]),
            "production_tray_model": str(bound["production_tray"]),
            "production_tray_model_sha256": diagnostics.sha256_file(bound["production_tray"]),
            "candidate_tray_model": str(bound["candidate_tray"]),
            "candidate_tray_model_sha256": diagnostics.sha256_file(bound["candidate_tray"]),
            "label_model": str(bound["label_model"]),
            "label_model_sha256": diagnostics.sha256_file(bound["label_model"]),
            "params": {
                "tray_threshold": 0.60,
                "label_threshold": 0.20,
                "edge_confidence": 0.30,
                "edge_width_fraction": 0.50,
                "edge_height_fraction": 0.60,
                "execution_provider": "CPUExecutionProvider",
            },
        },
        "batch": {
            "photos": len(records),
            "tasks": len({row["task_id"] for row in records}),
            "skus": len({row["sku_code"] for row in records}),
            "human_annotations": sum(len(row["human_labels"]) for row in records),
            "human_labels": {NORMAL_LABEL: sum(len(row["human_labels"]) for row in records)},
            "missing_positive_photos": 0,
            "independence": independence,
        },
        "baseline": baseline,
        "candidate": candidate,
        "comparison": comparison,
        "gate": gate,
        "details": details,
        "training_started": False,
        "deployment_started": False,
        "production_reads": 0,
        "production_writes": 0,
        "originals_modified": 0,
    }
    output.mkdir(parents=True)
    receipt_path = output / "receipt.json"
    receipt_path.write_text(
        json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8",
    )
    print(json.dumps({
        "receipt": str(receipt_path),
        "baseline": baseline,
        "candidate": candidate,
        "comparison": {
            "false_flags_increased": len(comparison["false_flags_increased"]),
            "false_flags_reduced": len(comparison["false_flags_reduced"]),
            "flagged_photos_increased": len(comparison["flagged_photos_increased"]),
            "flagged_photos_reduced": len(comparison["flagged_photos_reduced"]),
        },
        "gate": gate,
        "training_started": False,
        "deployment_started": False,
        "production_writes": 0,
    }, ensure_ascii=False, indent=2), flush=True)


if __name__ == "__main__":
    main()
