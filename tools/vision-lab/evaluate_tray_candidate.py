#!/usr/bin/env python3
"""Compare a tray candidate with production through the real tray+label pipeline."""
from __future__ import annotations

import argparse
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


def evaluate(model_dir: Path, records: list[dict[str, Any]], screening, yolo, threshold: float) -> dict[str, Any]:
    params = screening.ScreeningParams(tray_conf=0.60, label_conf=threshold)
    models = yolo.LabelQcYoloModels(model_dir=model_dir)
    if not models.available:
        raise RuntimeError(models.load_error or "ONNX models unavailable")
    totals = {
        "defect_total": 0, "defect_hits": 0, "tray_target_total": 0,
        "tray_target_hits": 0, "normal_photos": 0, "false_flags": 0,
    }
    groups: dict[str, dict[str, int]] = {}
    latencies: list[float] = []
    details: list[dict[str, Any]] = []
    for row in records:
        with Image.open(row["image"]) as opened:
            frame = np.array(ImageOps.exif_transpose(opened).convert("RGB"))
        started = time.perf_counter()
        result = screening.screen_image(frame, models, params)
        latencies.append((time.perf_counter() - started) * 1000)
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
            details.append({
                "photo_id": row["photo_id"], "group": row["group"], "kind": "defect",
                "tray_target_covered": covered, "hit": hit, "trays": len(result.trays),
                "suspects": len(result.suspects),
            })
        else:
            totals["normal_photos"] += 1
            totals["false_flags"] += len(result.suspects)
            group["normal_photos"] += 1
            group["false_flags"] += len(result.suspects)
            details.append({
                "photo_id": row["photo_id"], "group": row["group"], "kind": "normal",
                "false_flags": len(result.suspects), "trays": len(result.trays),
            })
    return {
        **totals, "p95_latency_ms": round(percentile(latencies, 0.95), 3),
        "groups": groups, "details": details,
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
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    for path in (args.production_tray, args.candidate_tray, args.production_label):
        if not path.is_file():
            raise FileNotFoundError(path)
    sys.path.insert(0, str((args.repo_root / "backend" / "python").resolve()))
    from label_qc.services import screening
    from label_qc.services import yolo_detector as yolo

    records = load_records(args.manifest)
    with tempfile.TemporaryDirectory(prefix="visionlab-tray-eval-") as temporary:
        temp = Path(temporary)
        baseline_dir, candidate_dir = temp / "baseline", temp / "candidate"
        baseline_dir.mkdir()
        candidate_dir.mkdir()
        shutil.copy2(args.production_tray, baseline_dir / "tray.onnx")
        shutil.copy2(args.candidate_tray, candidate_dir / "tray.onnx")
        for directory in (baseline_dir, candidate_dir):
            shutil.copy2(args.production_label, directory / "label.onnx")
        baseline = evaluate(baseline_dir, records, screening, yolo, args.threshold)
        candidate = evaluate(candidate_dir, records, screening, yolo, args.threshold)
    payload = {
        "version": "vision-lab-tray-evaluation-v1",
        "artifact_sha256": sha256_file(args.candidate_tray),
        "production_tray_sha256": sha256_file(args.production_tray),
        "production_label_sha256": sha256_file(args.production_label),
        "production_pipeline_replay": True,
        "onnx_parity_mismatches": args.onnx_parity_mismatches,
        "production_onnx_parity_mismatches": args.production_onnx_parity_mismatches,
        "threshold": args.threshold, "records": len(records),
        "baseline": baseline, "candidate": candidate,
    }
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(payload, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
