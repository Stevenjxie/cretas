#!/usr/bin/env python3
"""Build a human label-truth queue for normal flags changed to UNJUDGEABLE.

This is an evaluation-only truth-repair queue.  It never changes the protected
manifest and never promotes a model.  Candidate screening may select a crop,
but only the reviewed human annotation can decide whether the crop is clear,
missing a label, or unjudgeable.
"""
from __future__ import annotations

import argparse
import json
import os
import shutil
import sys
import tempfile
from pathlib import Path
from typing import Any, Sequence

import numpy as np
from PIL import Image, ImageOps

from evaluate_candidate import sha256_file


def box_iou(left: Sequence[float], right: Sequence[float]) -> float:
    intersection = max(0.0, min(left[2], right[2]) - max(left[0], right[0])) * max(
        0.0, min(left[3], right[3]) - max(left[1], right[1]),
    )
    left_area = max(0.0, left[2] - left[0]) * max(0.0, left[3] - left[1])
    right_area = max(0.0, right[2] - right[0]) * max(0.0, right[3] - right[1])
    return intersection / max(1e-9, left_area + right_area - intersection)


def changed_normal_photo_ids(metrics: dict[str, Any]) -> list[str]:
    baseline = {
        str(row["photo_id"]): row
        for row in metrics.get("baseline", {}).get("details") or []
    }
    candidate = {
        str(row["photo_id"]): row
        for row in metrics.get("candidate", {}).get("details") or []
    }
    if set(baseline) != set(candidate):
        raise RuntimeError("baseline/candidate evaluation records differ")
    return sorted(
        photo_id
        for photo_id, before in baseline.items()
        if before.get("kind") == "normal"
        and int(before.get("false_flags", 0)) > int(candidate[photo_id].get("false_flags", 0))
    )


def transitioned_trays(baseline_result: Any, candidate_result: Any) -> list[tuple[Any, Any]]:
    pairs: list[tuple[Any, Any]] = []
    unjudgeable = [
        tray for tray in candidate_result.review_candidates
        if tray.verdict == "UNJUDGEABLE"
    ]
    for suspect in baseline_result.suspects:
        matches = [
            (box_iou(suspect.box, tray.box), tray)
            for tray in unjudgeable
        ]
        score, tray = max(matches, default=(0.0, None), key=lambda item: item[0])
        if tray is not None and score >= 0.80:
            pairs.append((suspect, tray))
    return pairs


def normalise_in_crop(
    box: Sequence[float], crop_rect: Sequence[float],
) -> list[float] | None:
    crop_width = float(crop_rect[2]) - float(crop_rect[0])
    crop_height = float(crop_rect[3]) - float(crop_rect[1])
    values = [
        (float(box[0]) - float(crop_rect[0])) / crop_width,
        (float(box[1]) - float(crop_rect[1])) / crop_height,
        (float(box[2]) - float(crop_rect[0])) / crop_width,
        (float(box[3]) - float(crop_rect[1])) / crop_height,
    ]
    values = [max(0.0, min(1.0, value)) for value in values]
    if values[0] >= values[2] or values[1] >= values[3]:
        return None
    return [round(value, 6) for value in values]


def write_json(path: Path, payload: Any) -> None:
    path.write_text(
        json.dumps(payload, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )


def load_protected_rows(metrics: dict[str, Any]) -> dict[str, dict[str, Any]]:
    rows: dict[str, dict[str, Any]] = {}
    for item in metrics.get("protected_manifests") or []:
        path = Path(str(item.get("path") or ""))
        if not path.is_file() or sha256_file(path) != item.get("sha256"):
            raise RuntimeError(f"protected manifest missing or drifted: {path}")
        payload = json.loads(path.read_text(encoding="utf-8"))
        source = payload.get("records", payload)
        if not isinstance(source, list):
            raise RuntimeError(f"unsupported protected manifest: {path}")
        for row in source:
            photo_id = str(row["photo_id"])
            if photo_id in rows:
                raise RuntimeError(f"duplicate protected photo id: {photo_id}")
            rows[photo_id] = row
    return rows


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo-root", required=True, type=Path)
    parser.add_argument("--metrics", required=True, type=Path)
    parser.add_argument("--tray", required=True, type=Path)
    parser.add_argument("--label", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--annotator-url", default="http://127.0.0.1:8799")
    parser.add_argument("--review-pad-ratio", type=float, default=0.22)
    return parser


def main() -> None:
    args = build_parser().parse_args()
    metrics_path = args.metrics.resolve()
    tray_path = args.tray.resolve()
    label_path = args.label.resolve()
    output = args.output.resolve()
    for path in (metrics_path, tray_path, label_path):
        if not path.is_file():
            raise FileNotFoundError(path)
    if output.exists():
        raise FileExistsError(f"refusing to overwrite review queue: {output}")
    if not 0.0 <= args.review_pad_ratio <= 0.5:
        raise ValueError("review pad ratio must be between zero and 0.5")

    metrics = json.loads(metrics_path.read_text(encoding="utf-8"))
    if metrics.get("version") != "vision-lab-tray-evaluation-v1":
        raise RuntimeError("unsupported evaluation receipt")
    if metrics.get("production_tray_sha256") != sha256_file(tray_path):
        raise RuntimeError("tray model hash does not match evaluation")
    if metrics.get("production_label_sha256") != sha256_file(label_path):
        raise RuntimeError("label model hash does not match evaluation")
    params = metrics.get("screening_params") or {}
    baseline_min = int((params.get("baseline") or {}).get("min_crop_px", 120))
    candidate_min = int((params.get("candidate") or {}).get("min_crop_px", 120))
    if candidate_min <= baseline_min:
        raise RuntimeError("candidate minimum crop must exceed baseline for this queue")

    selected_ids = changed_normal_photo_ids(metrics)
    if not selected_ids:
        raise RuntimeError("evaluation contains no reduced normal flags")
    protected = load_protected_rows(metrics)

    sys.path.insert(0, str((args.repo_root / "backend" / "python").resolve()))
    from label_qc.services.screening import ScreeningParams, screen_image
    from label_qc.services.yolo_detector import LabelQcYoloModels, crop_with_padding

    temporary_output = output.with_name(f"{output.name}.tmp.{os.getpid()}")
    for name in ("images", "prelabels", "annotations-human"):
        (temporary_output / name).mkdir(parents=True, exist_ok=False)

    manifest_rows: list[dict[str, Any]] = []
    with tempfile.TemporaryDirectory(prefix="label-qc-false-flag-review-") as temporary:
        model_dir = Path(temporary)
        shutil.copy2(tray_path, model_dir / "tray.onnx")
        shutil.copy2(label_path, model_dir / "label.onnx")
        models = LabelQcYoloModels(model_dir)
        if not models.available:
            raise RuntimeError(models.load_error or "label QC models unavailable")
        baseline_params = ScreeningParams(
            tray_conf=0.60, label_conf=float(metrics["threshold"]),
            min_crop_px=baseline_min, capture_trace=True,
        )
        candidate_params = ScreeningParams(
            tray_conf=0.60, label_conf=float(metrics["threshold"]),
            min_crop_px=candidate_min, capture_trace=True,
        )
        for photo_id in selected_ids:
            row = protected[photo_id]
            source = Path(str(row.get("image") or row.get("path") or ""))
            if not source.is_file():
                raise FileNotFoundError(source)
            source_hash = sha256_file(source)
            if source_hash != str(row.get("image_sha256") or ""):
                raise RuntimeError(f"protected image hash drift: {photo_id}")
            with Image.open(source) as opened:
                frame = np.array(ImageOps.exif_transpose(opened).convert("RGB"))
            baseline_result = screen_image(frame, models, baseline_params)
            candidate_result = screen_image(frame, models, candidate_params)
            pairs = transitioned_trays(baseline_result, candidate_result)
            if not pairs:
                raise RuntimeError(f"no missing-to-unjudgeable tray transition: {photo_id}")
            for suspect, candidate_tray in pairs:
                crop, crop_rect = crop_with_padding(
                    frame, suspect.box, args.review_pad_ratio,
                )
                crop_id = f"falseflag_{photo_id[:8]}_{len(manifest_rows) + 1:03d}"
                image_path = temporary_output / "images" / f"{crop_id}.jpg"
                Image.fromarray(crop).save(image_path, quality=95)
                prelabels = []
                for label in suspect.labels:
                    box = normalise_in_crop(label.box, crop_rect)
                    if box is not None:
                        prelabels.append({
                            "class_id": int(label.class_id),
                            "bbox_normalized_xyxy": box,
                            "confidence": round(float(label.confidence), 6),
                        })
                write_json(temporary_output / "prelabels" / f"{crop_id}.json", {
                    "crop_id": crop_id,
                    "source": "production_label_yolo_requires_full_human_review",
                    "is_ground_truth": False,
                    "boxes": prelabels,
                })
                manifest_rows.append({
                    "queue_index": len(manifest_rows) + 1,
                    "crop_id": crop_id,
                    "image": str((output / "images" / image_path.name).resolve()),
                    "image_sha256": sha256_file(image_path),
                    "source_photo_id": photo_id,
                    "source_task_id": str(row.get("task_id") or photo_id),
                    "source_image": str(source),
                    "source_sha256": source_hash,
                    "protected_photo_label": str(row.get("human_label") or "NO_DEFECT"),
                    "protected_group": str(row.get("group") or "unspecified"),
                    "angle": "保护集正常图缺标争议裁决",
                    "sku_code": str(row.get("sku_code") or "UNKNOWN_PROTECTED"),
                    "label_v1_verdict": suspect.verdict,
                    "tray_box_px": [round(float(value), 6) for value in suspect.box],
                    "crop_rect_px": [round(float(value), 6) for value in crop_rect],
                    "baseline_verdict": suspect.verdict,
                    "candidate_verdict": candidate_tray.verdict,
                    "baseline_min_crop_px": baseline_min,
                    "candidate_min_crop_px": candidate_min,
                    "human_qc_verdict": "PENDING_INSTANCE_ADJUDICATION",
                    "training_allowed": False,
                    "evaluation_sidecar_only": True,
                })
            if sha256_file(source) != source_hash:
                raise RuntimeError(f"source original changed while packing: {photo_id}")

    manifest = {
        "version": "label-qc-normal-flag-adjudication-v1",
        "purpose": "human adjudication of protected normal flags changed to unjudgeable",
        "queue_count": len(manifest_rows),
        "annotator_url": args.annotator_url,
        "metrics": str(metrics_path),
        "metrics_sha256": sha256_file(metrics_path),
        "tray_model": str(tray_path),
        "tray_model_sha256": sha256_file(tray_path),
        "label_model": str(label_path),
        "label_model_sha256": sha256_file(label_path),
        "preannotations_are_not_ground_truth": True,
        "every_image_requires_full_human_review": True,
        "protected_holdout_included": True,
        "protected_holdout_use": "truth_repair_and_regression_only",
        "training_allowed": False,
        "promotion_allowed": False,
        "deployment_allowed": False,
        "rows": manifest_rows,
        "production_reads": 0,
        "production_writes": 0,
        "originals_modified": 0,
    }
    write_json(temporary_output / "manifest.json", manifest)
    (temporary_output / "README.md").write_text(
        "请完整检查每个裁块：把看得到的白标和彩标分别框出。"
        "如果确实少了一类，使用页面的缺失确认；如果画面无法判断，标记为无法判断。"
        "AI 预框不是真值。本队列只修复评估真值，禁止进入训练。\n",
        encoding="utf-8",
    )
    temporary_output.replace(output)
    print(json.dumps({
        "status": "human-adjudication-required",
        "queue": str(output),
        "queue_count": len(manifest_rows),
        "annotator_url": args.annotator_url,
        "training_allowed": False,
        "production_writes": 0,
        "originals_modified": 0,
    }, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
