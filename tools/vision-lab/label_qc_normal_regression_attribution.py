#!/usr/bin/env python3
"""Attribute independent-normal regressions to the tray model or edge windows."""
from __future__ import annotations

import argparse
import json
import shutil
import sys
import tempfile
import time
from pathlib import Path
from typing import Any, Sequence

import numpy as np
from PIL import Image, ImageOps

import label_qc_independent_normal_shadow as shadow
import label_qc_oracle_diagnostics as diagnostics
from label_qc_tray_edge_context_eval import load_and_verify


def classify_regression(baseline_flags: int, full_flags: int, edge_flags: int) -> str:
    """Classify a candidate+edge photo-level regression by bounded A/B counts."""
    if edge_flags <= baseline_flags:
        return "not_regressed"
    model_added = full_flags > baseline_flags
    edge_added = edge_flags > full_flags
    if model_added and edge_added:
        return "candidate_model_and_edge"
    if model_added:
        return "candidate_model"
    if edge_added:
        return "edge_windows"
    return "candidate_edge_interaction"


def selected_rows(details: Sequence[dict[str, Any]]) -> list[dict[str, Any]]:
    return [
        row for row in details
        if row["baseline"]["flagged"]
        or row["candidate"]["flagged"]
        or int(row["false_flag_delta"]) != 0
    ]


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo-root", required=True, type=Path)
    parser.add_argument("--shadow-receipt", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    return parser


def main() -> None:
    args = build_parser().parse_args()
    output = args.output.resolve()
    if output.exists():
        raise FileExistsError(f"refusing to overwrite attribution: {output}")
    shadow_path = args.shadow_receipt.resolve()
    receipt = load_and_verify(shadow_path)
    if receipt.get("version") != "label-qc-independent-normal-shadow-v1":
        raise RuntimeError("unsupported shadow receipt")
    if receipt.get("deployment_started") is not False:
        raise RuntimeError("shadow receipt reports deployment")
    inputs = receipt["inputs"]
    candidate_model = Path(str(inputs["candidate_tray_model"]))
    label_model = Path(str(inputs["label_model"]))
    for path, expected_hash in (
        (candidate_model, inputs["candidate_tray_model_sha256"]),
        (label_model, inputs["label_model_sha256"]),
    ):
        if not path.is_file() or diagnostics.sha256_file(path) != expected_hash:
            raise RuntimeError(f"bound input drift: {path}")
    rows = selected_rows(receipt["details"])
    if not rows:
        raise RuntimeError("shadow receipt has no flagged or changed photos")
    for row in rows:
        image = Path(str(row["image"]))
        if not image.is_file() or diagnostics.sha256_file(image) != row["image_sha256"]:
            raise RuntimeError(f"image hash drift: {image}")

    backend_python = (args.repo_root.resolve() / "backend" / "python").resolve()
    sys.path.insert(0, str(backend_python))
    from label_qc.services import screening
    from label_qc.services import yolo_detector as yolo

    params = screening.ScreeningParams(
        tray_conf=0.60, label_conf=0.20, capture_trace=False,
    )
    attributed: list[dict[str, Any]] = []
    with tempfile.TemporaryDirectory(prefix="label-qc-normal-attribution-") as temporary:
        model_dir = Path(temporary)
        shutil.copy2(candidate_model, model_dir / "tray.onnx")
        shutil.copy2(label_model, model_dir / "label.onnx")
        base = yolo.LabelQcYoloModels(model_dir=model_dir)
        if not base.available:
            raise RuntimeError(base.load_error or "candidate model unavailable")
        full_models = diagnostics.FullFrameModels(base)
        with Image.open(rows[0]["image"]) as opened:
            warmup = np.asarray(ImageOps.exif_transpose(opened).convert("RGB"))
        screening.screen_image(warmup, full_models, params)
        print(json.dumps({"stage": "warmup_complete", "selected": len(rows)}), flush=True)
        for index, row in enumerate(rows, start=1):
            with Image.open(row["image"]) as opened:
                frame = np.asarray(ImageOps.exif_transpose(opened).convert("RGB"))
            started = time.perf_counter()
            result = screening.screen_image(frame, full_models, params)
            latency_ms = (time.perf_counter() - started) * 1000.0
            full = shadow._variant_result(result, full_models, latency_ms)
            baseline_flags = int(row["baseline"]["false_flags"])
            edge_flags = int(row["candidate"]["false_flags"])
            full_flags = int(full["false_flags"])
            attributed.append({
                "photo_id": row["photo_id"],
                "task_id": row["task_id"],
                "sku_code": row["sku_code"],
                "image": row["image"],
                "image_sha256": row["image_sha256"],
                "baseline": row["baseline"],
                "candidate_full": full,
                "candidate_edge": row["candidate"],
                "baseline_flags": baseline_flags,
                "candidate_full_flags": full_flags,
                "candidate_edge_flags": edge_flags,
                "candidate_model_delta": full_flags - baseline_flags,
                "edge_incremental_delta": edge_flags - full_flags,
                "regression_source": classify_regression(
                    baseline_flags, full_flags, edge_flags,
                ),
            })
            print(json.dumps({
                "stage": "attribution_progress",
                "completed": index,
                "total": len(rows),
            }), flush=True)

    regressions = [row for row in attributed if row["candidate_edge_flags"] > row["baseline_flags"]]
    sources: dict[str, int] = {}
    for row in regressions:
        source = row["regression_source"]
        sources[source] = sources.get(source, 0) + 1
    payload = {
        "version": "label-qc-normal-regression-attribution-v1",
        "created_at": diagnostics.utc_now(),
        "purpose": "separate candidate full-frame errors from fixed edge-window errors",
        "inputs": {
            "shadow_receipt": str(shadow_path),
            "shadow_receipt_sha256": diagnostics.sha256_file(shadow_path),
            "candidate_tray_model": str(candidate_model),
            "candidate_tray_model_sha256": diagnostics.sha256_file(candidate_model),
            "label_model": str(label_model),
            "label_model_sha256": diagnostics.sha256_file(label_model),
            "selected_policy": "all baseline-flagged, candidate-flagged, or changed photos",
        },
        "summary": {
            "batch_photos": receipt["batch"]["photos"],
            "selected_photos": len(rows),
            "regressed_photos": len(regressions),
            "regression_sources": dict(sorted(sources.items())),
            "candidate_model_positive_delta_photos": sum(
                row["candidate_model_delta"] > 0 for row in regressions
            ),
            "edge_incremental_positive_delta_photos": sum(
                row["edge_incremental_delta"] > 0 for row in regressions
            ),
            "candidate_model_added_flags": sum(
                max(0, row["candidate_model_delta"]) for row in regressions
            ),
            "edge_incremental_added_flags": sum(
                max(0, row["edge_incremental_delta"]) for row in regressions
            ),
        },
        "rows": attributed,
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
        "summary": payload["summary"],
        "deployment_started": False,
        "production_writes": 0,
    }, ensure_ascii=False, indent=2), flush=True)


if __name__ == "__main__":
    main()
