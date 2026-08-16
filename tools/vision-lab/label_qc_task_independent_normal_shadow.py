#!/usr/bin/env python3
"""Evaluate production versus a full-frame tray candidate on a task-locked normal holdout."""
from __future__ import annotations

import argparse
import json
import shutil
import sys
import tempfile
from pathlib import Path
from typing import Any, Sequence

import label_qc_independent_normal_shadow as normal_shadow
import label_qc_oracle_diagnostics as diagnostics
import label_qc_tray_patch_dataset as patch_dataset
from label_qc_tray_edge_context_eval import load_and_verify


FUTURE_INDEPENDENT_POOL = "future-independent"
SECONDARY_REGRESSION_POOL = "secondary-regression"


def verify_file(path: Path, expected_sha256: str, label: str) -> Path:
    resolved = path.resolve()
    if not resolved.is_file() or diagnostics.sha256_file(resolved) != expected_sha256:
        raise RuntimeError(f"{label} drift: {resolved}")
    return resolved


def select_normal_records(
    source_shadow: dict[str, Any], dataset: dict[str, Any], pool_kind: str,
) -> list[dict[str, Any]]:
    lock = ((dataset.get("append_audit") or {}).get("normal_holdout_lock") or {})
    if pool_kind == FUTURE_INDEPENDENT_POOL:
        id_field, count_field = "task_independent_photo_ids", "task_independent_photos"
        if lock.get("remaining_pool_is_future_independent") is not True:
            raise RuntimeError("remaining pool is not declared future-independent")
    elif pool_kind == SECONDARY_REGRESSION_POOL:
        id_field, count_field = "secondary_regression_photo_ids", "secondary_regression_photos"
        if lock.get("remaining_pool_is_future_independent") is not False:
            raise RuntimeError("secondary regression pool boundary drift")
    else:
        raise ValueError(f"unsupported normal pool kind: {pool_kind}")
    allowed_ids = [str(value) for value in lock.get(id_field) or []]
    if len(allowed_ids) != int(lock.get(count_field, -1)) or len(set(allowed_ids)) != len(allowed_ids):
        raise RuntimeError(f"{pool_kind} photo identity drift")
    details = source_shadow.get("details") or []
    by_photo = {str(row.get("photo_id") or ""): row for row in details}
    if "" in by_photo or len(by_photo) != len(details):
        raise RuntimeError("source shadow photo identity drift")
    records: list[dict[str, Any]] = []
    for photo_id in allowed_ids:
        row = by_photo.get(photo_id)
        if row is None:
            raise RuntimeError(f"{pool_kind} photo missing from source shadow: {photo_id}")
        if set(str(value) for value in row.get("human_labels") or []) != {normal_shadow.NORMAL_LABEL}:
            raise RuntimeError(f"{pool_kind} photo is not human-normal: {photo_id}")
        image = Path(str(row.get("image") or ""))
        verify_file(image, str(row.get("image_sha256") or ""), f"{pool_kind} image")
        records.append({
            key: row[key]
            for key in (
                "photo_id", "task_id", "reviewed_at", "sku_code", "object_ref",
                "image", "image_sha256", "human_labels", "proposal_sources",
            )
        })
    selected_tasks = {str(value) for value in lock.get("consumed_task_ids") or []}
    record_tasks = {str(row["task_id"]) for row in records}
    if selected_tasks & record_tasks:
        raise RuntimeError(f"{pool_kind} contains a consumed training task")
    if pool_kind == FUTURE_INDEPENDENT_POOL and len(record_tasks) != int(lock.get("task_independent_tasks", -1)):
        raise RuntimeError("task-independent holdout task count drift")
    return records


def select_task_independent_records(
    source_shadow: dict[str, Any], dataset: dict[str, Any],
) -> list[dict[str, Any]]:
    return select_normal_records(source_shadow, dataset, FUTURE_INDEPENDENT_POOL)


def load_bound_inputs(
    source_shadow_path: Path,
    dataset_manifest_path: Path,
    candidate_receipt_path: Path,
    pool_kind: str = FUTURE_INDEPENDENT_POOL,
) -> dict[str, Any]:
    source_path = source_shadow_path.resolve()
    dataset_path = dataset_manifest_path.resolve()
    candidate_path = candidate_receipt_path.resolve()
    source = load_and_verify(source_path)
    dataset = load_and_verify(dataset_path)
    candidate = load_and_verify(candidate_path)
    lock = ((dataset.get("append_audit") or {}).get("normal_holdout_lock") or {})
    if str(Path(str(lock.get("source_shadow_receipt") or "")).resolve()) != str(source_path):
        raise RuntimeError("dataset is not bound to the supplied source shadow receipt")
    if diagnostics.sha256_file(source_path) != lock.get("source_shadow_receipt_sha256"):
        raise RuntimeError("source shadow receipt hash drift")
    if candidate.get("deployment") is not False or candidate.get("status") != "candidate":
        raise RuntimeError("candidate receipt deployment/status boundary drift")
    if candidate.get("dataset_id") != dataset.get("dataset_id"):
        raise RuntimeError("candidate/dataset id mismatch")
    if candidate.get("dataset_sha256") != dataset.get("dataset_sha256"):
        raise RuntimeError("candidate/dataset content mismatch")
    if dataset.get("dataset_sha256") != patch_dataset.dataset_digest(dataset_path.parent):
        raise RuntimeError("candidate dataset content drift")
    candidate_tray = verify_file(
        Path(str(candidate.get("artifact") or "")), str(candidate.get("artifact_sha256") or ""),
        "candidate tray model",
    )
    inputs = source.get("inputs") or {}
    production_tray = verify_file(
        Path(str(inputs.get("production_tray_model") or "")),
        str(inputs.get("production_tray_model_sha256") or ""), "production tray model",
    )
    label_model = verify_file(
        Path(str(inputs.get("label_model") or "")),
        str(inputs.get("label_model_sha256") or ""), "label model",
    )
    protected = verify_file(
        Path(str(inputs.get("protected_manifest") or "")),
        str(inputs.get("protected_manifest_sha256") or ""), "protected manifest",
    )
    provenance = dataset_path.parent / "provenance.json"
    if not provenance.is_file():
        raise RuntimeError(f"candidate dataset provenance missing: {provenance}")
    records = select_normal_records(source, dataset, pool_kind)
    independence = normal_shadow.assert_independent_batch(records, provenance, protected)
    return {
        "source_shadow": source,
        "dataset": dataset,
        "candidate": candidate,
        "records": records,
        "independence": independence,
        "production_tray": production_tray,
        "candidate_tray": candidate_tray,
        "label_model": label_model,
        "protected_manifest": protected,
        "dataset_provenance": provenance,
        "pool_kind": pool_kind,
    }


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo-root", required=True, type=Path)
    parser.add_argument("--source-shadow-receipt", required=True, type=Path)
    parser.add_argument("--dataset-manifest", required=True, type=Path)
    parser.add_argument("--candidate-receipt", required=True, type=Path)
    parser.add_argument(
        "--pool-kind", choices=(FUTURE_INDEPENDENT_POOL, SECONDARY_REGRESSION_POOL),
        default=FUTURE_INDEPENDENT_POOL,
    )
    parser.add_argument("--output", required=True, type=Path)
    return parser


def main() -> None:
    args = build_parser().parse_args()
    output = args.output.resolve()
    if output.exists():
        raise FileExistsError(f"refusing to overwrite normal shadow evaluation: {output}")
    bound = load_bound_inputs(
        args.source_shadow_receipt, args.dataset_manifest, args.candidate_receipt,
        args.pool_kind,
    )
    records: Sequence[dict[str, Any]] = bound["records"]
    print(json.dumps({
        "stage": "inputs_verified",
        "photos": len(records),
        "tasks": len({row["task_id"] for row in records}),
        "skus": len({row["sku_code"] for row in records}),
        "pool_kind": args.pool_kind,
        "overlap_independent": bound["independence"]["independent"],
        "fresh_model_selection_independent": args.pool_kind == FUTURE_INDEPENDENT_POOL,
        "candidate_mode": "full_frame",
    }), flush=True)

    backend_python = (args.repo_root.resolve() / "backend" / "python").resolve()
    sys.path.insert(0, str(backend_python))
    from label_qc.services import screening
    from label_qc.services import yolo_detector as yolo

    params = screening.ScreeningParams(tray_conf=0.60, label_conf=0.20, capture_trace=False)
    with tempfile.TemporaryDirectory(prefix="label-qc-task-independent-normal-") as temporary:
        root = Path(temporary)
        production_dir, candidate_dir = root / "production", root / "candidate"
        production_dir.mkdir()
        candidate_dir.mkdir()
        shutil.copy2(bound["production_tray"], production_dir / "tray.onnx")
        shutil.copy2(bound["candidate_tray"], candidate_dir / "tray.onnx")
        shutil.copy2(bound["label_model"], production_dir / "label.onnx")
        shutil.copy2(bound["label_model"], candidate_dir / "label.onnx")
        production_base = yolo.LabelQcYoloModels(model_dir=production_dir)
        candidate_base = yolo.LabelQcYoloModels(model_dir=candidate_dir)
        if not production_base.available or not candidate_base.available:
            raise RuntimeError(production_base.load_error or candidate_base.load_error or "model unavailable")
        details = normal_shadow.evaluate_pair(
            records,
            diagnostics.FullFrameModels(production_base),
            diagnostics.FullFrameModels(candidate_base),
            screening,
            params,
        )

    baseline = normal_shadow.summarize_variant(details, "baseline")
    candidate = normal_shadow.summarize_variant(details, "candidate")
    comparison = normal_shadow.compare_details(details)
    gate = normal_shadow.build_normal_gate(baseline, candidate, comparison)
    is_future_independent = args.pool_kind == FUTURE_INDEPENDENT_POOL
    payload = {
        "version": (
            "label-qc-task-independent-normal-shadow-v1" if is_future_independent
            else "label-qc-secondary-normal-regression-shadow-v1"
        ),
        "created_at": diagnostics.utc_now(),
        "purpose": (
            "task-independent human-normal specificity check for full-frame tray candidate"
            if is_future_independent else
            "evaluation-consumed secondary human-normal regression check; not fresh promotion evidence"
        ),
        "pool_kind": args.pool_kind,
        "promotion_evidence": is_future_independent,
        "inputs": {
            "source_shadow_receipt": str(args.source_shadow_receipt.resolve()),
            "source_shadow_receipt_sha256": diagnostics.sha256_file(args.source_shadow_receipt),
            "dataset_manifest": str(args.dataset_manifest.resolve()),
            "dataset_manifest_sha256": diagnostics.sha256_file(args.dataset_manifest),
            "candidate_receipt": str(args.candidate_receipt.resolve()),
            "candidate_receipt_sha256": diagnostics.sha256_file(args.candidate_receipt),
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
            "candidate_mode": "full_frame",
            "params": {
                "tray_threshold": 0.60,
                "label_threshold": 0.20,
                "execution_provider": "CPUExecutionProvider",
            },
        },
        "batch": {
            "photos": len(records),
            "tasks": len({row["task_id"] for row in records}),
            "skus": len({row["sku_code"] for row in records}),
            "human_annotations": sum(len(row["human_labels"]) for row in records),
            "human_labels": {normal_shadow.NORMAL_LABEL: sum(len(row["human_labels"]) for row in records)},
            "missing_positive_photos": 0,
            "independence": bound["independence"],
            "fresh_model_selection_independent": is_future_independent,
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
    receipt = output / "receipt.json"
    receipt.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({
        "receipt": str(receipt),
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
