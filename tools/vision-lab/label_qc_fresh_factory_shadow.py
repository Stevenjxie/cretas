#!/usr/bin/env python3
"""Lock a task-disjoint fresh factory split and evaluate only its development side."""
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


def verify_file(path: Path, expected_sha256: str, label: str) -> Path:
    resolved = path.resolve()
    if not resolved.is_file() or diagnostics.sha256_file(resolved) != expected_sha256:
        raise RuntimeError(f"{label} drift: {resolved}")
    return resolved


def validate_split(
    development: Sequence[dict[str, Any]], final: Sequence[dict[str, Any]],
    expected_development: int, expected_final: int,
) -> dict[str, Any]:
    if len(development) != expected_development or len(final) != expected_final:
        raise RuntimeError("fresh factory split photo count drift")
    development_tasks = {str(row["task_id"]) for row in development}
    final_tasks = {str(row["task_id"]) for row in final}
    development_hashes = {str(row["image_sha256"]) for row in development}
    final_hashes = {str(row["image_sha256"]) for row in final}
    development_ids = {str(row["photo_id"]) for row in development}
    final_ids = {str(row["photo_id"]) for row in final}
    if development_tasks & final_tasks:
        raise RuntimeError("fresh factory split leaks a task across development/final")
    if development_hashes & final_hashes or development_ids & final_ids:
        raise RuntimeError("fresh factory split leaks a photo across development/final")
    if len(development_hashes) != len(development) or len(final_hashes) != len(final):
        raise RuntimeError("fresh factory split contains duplicate image content")
    return {
        "development_photos": len(development),
        "development_tasks": len(development_tasks),
        "development_task_ids": sorted(development_tasks),
        "development_photo_ids": sorted(development_ids),
        "development_image_sha256": sorted(development_hashes),
        "final_photos": len(final),
        "final_tasks": len(final_tasks),
        "final_task_ids": sorted(final_tasks),
        "final_photo_ids": sorted(final_ids),
        "final_image_sha256": sorted(final_hashes),
        "task_disjoint": True,
        "content_disjoint": True,
        "final_model_inference_started": False,
        "final_training_use_allowed": False,
    }


def load_bound_inputs(
    config_path: Path, dataset_path: Path, candidate_path: Path,
) -> dict[str, Any]:
    config = load_and_verify(config_path.resolve())
    dataset = load_and_verify(dataset_path.resolve())
    candidate = load_and_verify(candidate_path.resolve())
    if candidate.get("deployment") is not False or candidate.get("status") != "candidate":
        raise RuntimeError("candidate deployment/status boundary drift")
    if candidate.get("dataset_id") != dataset.get("dataset_id") or candidate.get("dataset_sha256") != dataset.get("dataset_sha256"):
        raise RuntimeError("candidate/dataset identity mismatch")
    if dataset.get("dataset_sha256") != patch_dataset.dataset_digest(dataset_path.resolve().parent):
        raise RuntimeError("candidate dataset content drift")
    tray = config["tray_active_learning"]
    production_tray = verify_file(
        Path(tray["production_tray_onnx"]), tray["production_tray_sha256"], "production tray model",
    )
    label_model = verify_file(
        Path(tray["production_label_onnx"]), tray["production_label_sha256"], "production label model",
    )
    candidate_tray = verify_file(
        Path(candidate["artifact"]), candidate["artifact_sha256"], "candidate tray model",
    )
    protected = Path(tray["protected_holdout"]).resolve()
    if not protected.is_file():
        raise FileNotFoundError(protected)
    provenance = dataset_path.resolve().parent / "provenance.json"
    if not provenance.is_file():
        raise FileNotFoundError(provenance)
    return {
        "config": config,
        "dataset": dataset,
        "candidate": candidate,
        "production_tray": production_tray,
        "candidate_tray": candidate_tray,
        "label_model": label_model,
        "protected": protected,
        "provenance": provenance,
    }


def watermark(value: str) -> tuple[str, str]:
    parts = value.split(",", 1)
    if len(parts) != 2 or not all(parts):
        raise ValueError("watermark must be reviewed_at,photo_id")
    return parts[0], parts[1]


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo-root", required=True, type=Path)
    parser.add_argument("--config", required=True, type=Path)
    parser.add_argument("--state-db", required=True, type=Path)
    parser.add_argument("--dataset-manifest", required=True, type=Path)
    parser.add_argument("--candidate-receipt", required=True, type=Path)
    parser.add_argument("--collection-before", required=True)
    parser.add_argument("--development-after", required=True)
    parser.add_argument("--collection-after", required=True)
    parser.add_argument("--expected-development-photos", required=True, type=int)
    parser.add_argument("--expected-final-photos", required=True, type=int)
    parser.add_argument("--output", required=True, type=Path)
    return parser


def main() -> None:
    args = build_parser().parse_args()
    output = args.output.resolve()
    if output.exists():
        raise FileExistsError(f"refusing to overwrite fresh factory shadow: {output}")
    bound = load_bound_inputs(args.config, args.dataset_manifest, args.candidate_receipt)
    before_at, before_id = watermark(args.collection_before)
    split_at, split_id = watermark(args.development_after)
    after_at, after_id = watermark(args.collection_after)
    development = normal_shadow.load_normal_batch(
        args.state_db.resolve(), before_at, before_id, split_at, split_id,
    )
    final = normal_shadow.load_normal_batch(
        args.state_db.resolve(), split_at, split_id, after_at, after_id,
    )
    split = validate_split(
        development, final, args.expected_development_photos, args.expected_final_photos,
    )
    development_independence = normal_shadow.assert_independent_batch(
        development, bound["provenance"], bound["protected"],
    )
    final_independence = normal_shadow.assert_independent_batch(
        final, bound["provenance"], bound["protected"],
    )
    print(json.dumps({
        "stage": "fresh_split_verified",
        "development_photos": len(development),
        "final_photos": len(final),
        "task_disjoint": split["task_disjoint"],
        "final_model_inference_started": False,
    }), flush=True)

    backend_python = (args.repo_root.resolve() / "backend" / "python").resolve()
    sys.path.insert(0, str(backend_python))
    from label_qc.services import screening
    from label_qc.services import yolo_detector as yolo

    params = screening.ScreeningParams(tray_conf=0.60, label_conf=0.20, capture_trace=False)
    with tempfile.TemporaryDirectory(prefix="label-qc-fresh-factory-") as temporary:
        root = Path(temporary)
        production_dir, candidate_dir = root / "production", root / "candidate"
        production_dir.mkdir()
        candidate_dir.mkdir()
        shutil.copy2(bound["production_tray"], production_dir / "tray.onnx")
        shutil.copy2(bound["candidate_tray"], candidate_dir / "tray.onnx")
        shutil.copy2(bound["label_model"], production_dir / "label.onnx")
        shutil.copy2(bound["label_model"], candidate_dir / "label.onnx")
        production_models = yolo.LabelQcYoloModels(model_dir=production_dir)
        candidate_models = yolo.LabelQcYoloModels(model_dir=candidate_dir)
        if not production_models.available or not candidate_models.available:
            raise RuntimeError(production_models.load_error or candidate_models.load_error or "model unavailable")
        details = normal_shadow.evaluate_pair(
            development,
            diagnostics.FullFrameModels(production_models),
            diagnostics.FullFrameModels(candidate_models),
            screening,
            params,
        )

    baseline = normal_shadow.summarize_variant(details, "baseline")
    candidate = normal_shadow.summarize_variant(details, "candidate")
    comparison = normal_shadow.compare_details(details)
    gate = normal_shadow.build_normal_gate(baseline, candidate, comparison)
    payload = {
        "version": "label-qc-fresh-factory-development-shadow-v1",
        "created_at": diagnostics.utc_now(),
        "purpose": "fresh task-disjoint factory development check; final split remains sealed",
        "promotion_evidence": False,
        "inputs": {
            "config": str(args.config.resolve()),
            "config_sha256": diagnostics.sha256_file(args.config),
            "state_db": str(args.state_db.resolve()),
            "state_db_open_mode": "read_only",
            "dataset_manifest": str(args.dataset_manifest.resolve()),
            "dataset_manifest_sha256": diagnostics.sha256_file(args.dataset_manifest),
            "candidate_receipt": str(args.candidate_receipt.resolve()),
            "candidate_receipt_sha256": diagnostics.sha256_file(args.candidate_receipt),
            "protected_manifest": str(bound["protected"]),
            "protected_manifest_sha256": diagnostics.sha256_file(bound["protected"]),
            "watermarks": {
                "collection_before": {"reviewed_at": before_at, "photo_id": before_id},
                "development_after": {"reviewed_at": split_at, "photo_id": split_id},
                "collection_after": {"reviewed_at": after_at, "photo_id": after_id},
            },
        },
        "split_lock": split,
        "development": {
            "independence": development_independence,
            "baseline": baseline,
            "candidate": candidate,
            "comparison": comparison,
            "gate": gate,
            "details": details,
        },
        "final": {
            "independence": final_independence,
            "evaluated": False,
            "model_inference_started": False,
            "training_use_allowed": False,
        },
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
        "gate": gate,
        "final_photos_sealed": split["final_photos"],
        "deployment_started": False,
        "production_writes": 0,
    }, ensure_ascii=False, indent=2), flush=True)


if __name__ == "__main__":
    main()
