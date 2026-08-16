#!/usr/bin/env python3
"""Build a full-task human tray review queue around new normal-shadow regressions."""
from __future__ import annotations

import argparse
import datetime as dt
import json
import shutil
import sys
import tempfile
from pathlib import Path
from typing import Any, Sequence

import numpy as np
from PIL import Image, ImageOps

import label_qc_normal_regression_review_queue as review_queue
import label_qc_oracle_diagnostics as diagnostics
from label_qc_tray_edge_context_eval import load_and_verify


def select_task_expanded_rows(
    shadow: dict[str, Any], expected_regressions: int,
) -> tuple[list[dict[str, Any]], set[str], set[str]]:
    details = shadow.get("details") or []
    regressions = [
        row for row in details
        if bool((row.get("candidate") or {}).get("flagged"))
        and not bool((row.get("baseline") or {}).get("flagged"))
    ]
    if len(regressions) != expected_regressions:
        raise RuntimeError(
            f"expected {expected_regressions} new candidate regressions, found {len(regressions)}"
        )
    regression_photo_ids = {str(row["photo_id"]) for row in regressions}
    regression_task_ids = {str(row["task_id"]) for row in regressions}
    if len(regression_photo_ids) != len(regressions) or len(regression_task_ids) != len(regressions):
        raise RuntimeError("new candidate regressions are not unique by photo and task")
    selected = [row for row in details if str(row["task_id"]) in regression_task_ids]
    selected.sort(key=lambda row: (
        0 if str(row["photo_id"]) in regression_photo_ids else 1,
        str(row["task_id"]), str(row.get("reviewed_at") or ""), str(row["photo_id"]),
    ))
    if not selected or {str(row["task_id"]) for row in selected} != regression_task_ids:
        raise RuntimeError("task-expanded regression selection drift")
    if any(set(str(label) for label in row.get("human_labels") or []) != {"NO_DEFECT"} for row in selected):
        raise RuntimeError("task-expanded queue contains non-normal human truth")
    return selected, regression_photo_ids, regression_task_ids


def _detector_rows(
    detections: Sequence[Any], source: str, priority: int, width: int, height: int,
) -> list[dict[str, Any]]:
    return review_queue._detector_rows(detections, source, priority, width, height)


def normalise_shadow_receipt(payload: dict[str, Any]) -> dict[str, Any]:
    if payload.get("version") == "label-qc-task-independent-normal-shadow-v1":
        return payload
    if payload.get("version") != "label-qc-fresh-factory-development-shadow-v1":
        raise RuntimeError("unsupported task-independent shadow receipt")
    development = payload.get("development") or {}
    inputs = payload.get("inputs") or {}
    config_path = Path(str(inputs.get("config") or ""))
    candidate_path = Path(str(inputs.get("candidate_receipt") or ""))
    if not config_path.is_file() or diagnostics.sha256_file(config_path) != inputs.get("config_sha256"):
        raise RuntimeError("fresh factory source config drift")
    if not candidate_path.is_file() or diagnostics.sha256_file(candidate_path) != inputs.get("candidate_receipt_sha256"):
        raise RuntimeError("fresh factory candidate receipt drift")
    config = load_and_verify(config_path)
    candidate = load_and_verify(candidate_path)
    tray = config["tray_active_learning"]
    split = payload.get("split_lock") or {}
    return {
        "version": "label-qc-task-independent-normal-shadow-v1",
        "deployment_started": payload.get("deployment_started"),
        "production_writes": payload.get("production_writes"),
        "batch": {
            "photos": split.get("development_photos"),
            "missing_positive_photos": 0,
            "independence": development.get("independence"),
        },
        "details": development.get("details") or [],
        "inputs": {
            "production_tray_model": tray["production_tray_onnx"],
            "production_tray_model_sha256": tray["production_tray_sha256"],
            "candidate_tray_model": candidate["artifact"],
            "candidate_tray_model_sha256": candidate["artifact_sha256"],
            "label_model": tray["production_label_onnx"],
            "label_model_sha256": tray["production_label_sha256"],
        },
    }


def build_queue(
    repo_root: Path,
    shadow_receipt_path: Path,
    output_root: Path,
    expected_regressions: int,
) -> tuple[Path, dict[str, Any], dict[str, Any]]:
    shadow_path = shadow_receipt_path.resolve()
    shadow = normalise_shadow_receipt(load_and_verify(shadow_path))
    if shadow.get("deployment_started") is not False or int(shadow.get("production_writes", -1)) != 0:
        raise RuntimeError("task-independent shadow reports deployment or production writes")
    if int((shadow.get("batch") or {}).get("missing_positive_photos", -1)) != 0:
        raise RuntimeError("task regression queue unexpectedly contains positives")
    if (shadow.get("batch") or {}).get("independence", {}).get("independent") is not True:
        raise RuntimeError("task regression source batch was not independent")
    selected, regression_photo_ids, regression_task_ids = select_task_expanded_rows(
        shadow, expected_regressions,
    )
    inputs = shadow.get("inputs") or {}
    production_tray = Path(str(inputs.get("production_tray_model") or ""))
    candidate_tray = Path(str(inputs.get("candidate_tray_model") or ""))
    label_model = Path(str(inputs.get("label_model") or ""))
    for path, expected_hash in (
        (production_tray, inputs.get("production_tray_model_sha256")),
        (candidate_tray, inputs.get("candidate_tray_model_sha256")),
        (label_model, inputs.get("label_model_sha256")),
    ):
        if not path.is_file() or diagnostics.sha256_file(path) != expected_hash:
            raise RuntimeError(f"bound task-regression model drift: {path}")

    stamp = dt.datetime.now(dt.timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    queue = output_root.resolve() / f"tray-normal-task-regression-review-{stamp}"
    if queue.exists():
        raise FileExistsError(f"refusing to overwrite queue: {queue}")
    for name in ("images", "labels", "annotations-human"):
        (queue / name).mkdir(parents=True, exist_ok=False)

    sys.path.insert(0, str((repo_root.resolve() / "backend" / "python").resolve()))
    from label_qc.services import yolo_detector as yolo

    manifest_rows: list[dict[str, Any]] = []
    with tempfile.TemporaryDirectory(prefix="tray-task-regression-models-") as temporary:
        temporary_root = Path(temporary)
        production_dir, candidate_dir = temporary_root / "production", temporary_root / "candidate"
        production_dir.mkdir()
        candidate_dir.mkdir()
        shutil.copy2(production_tray, production_dir / "tray.onnx")
        shutil.copy2(candidate_tray, candidate_dir / "tray.onnx")
        shutil.copy2(label_model, production_dir / "label.onnx")
        shutil.copy2(label_model, candidate_dir / "label.onnx")
        production_models = yolo.LabelQcYoloModels(model_dir=production_dir)
        candidate_models = yolo.LabelQcYoloModels(model_dir=candidate_dir)
        if not production_models.available or not candidate_models.available:
            raise RuntimeError(
                production_models.load_error or candidate_models.load_error or "bound models unavailable"
            )
        for index, source_row in enumerate(selected, start=1):
            photo_id = str(source_row["photo_id"])
            source_image = Path(str(source_row["image"]))
            source_hash = str(source_row["image_sha256"])
            if not source_image.is_file() or diagnostics.sha256_file(source_image) != source_hash:
                raise RuntimeError(f"task-regression source image drift: {source_image}")
            with Image.open(source_image) as opened:
                image = ImageOps.exif_transpose(opened).convert("RGB")
            frame = np.asarray(image)
            production_detections = production_models.detect_trays(frame, 0.60)
            candidate_detections = candidate_models.detect_trays(frame, 0.60)
            proposals = review_queue.merge_detector_proposals([
                *_detector_rows(
                    production_detections, "production_full_frame", 3, image.width, image.height,
                ),
                *_detector_rows(
                    candidate_detections, "candidate_full_frame", 2, image.width, image.height,
                ),
                *review_queue._suspect_rows(
                    (source_row.get("candidate") or {}).get("suspects") or [],
                    image.width, image.height,
                ),
            ])
            stem = f"traytask_{photo_id[:8]}_{index:03d}"
            packed_image = queue / "images" / f"{stem}.jpg"
            packed_label = queue / "labels" / f"{stem}.txt"
            annotation_path = queue / "annotations-human" / f"{stem}.json"
            shutil.copy2(source_image, packed_image)
            packed_label.write_text(
                "".join(review_queue.yolo_line(row["box"]) for row in proposals),
                encoding="utf-8",
            )
            annotation_path.write_text(json.dumps({
                "photo_id": stem,
                "source_photo_id": photo_id,
                "format": "normalised_xyxy",
                "reviewed": False,
                "source": "production_and_candidate_proposals_require_full_human_review",
                "boxes": [row["box"] for row in proposals],
                "annotation_policy": {
                    "keep_or_correct": "one complete judgeable QC-eligible tray",
                    "delete": "partial, occluded, truncated, duplicate, or false proposal",
                    "deleted_proposal_semantics": "ignore_not_negative",
                    "visible_labels_on_deleted_tray": "separate_label_side_view_truth",
                    "switch_after_review": "confirms current photo in factory workflow",
                },
            }, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
            regression = photo_id in regression_photo_ids
            manifest_rows.append({
                "queue_index": index,
                "packed_stem": stem,
                "packed_image": f"images/{packed_image.name}",
                "packed_label": f"labels/{packed_label.name}",
                "photo_id": photo_id,
                "source_photo_id": photo_id,
                "task_id": str(source_row["task_id"]),
                "sku_code": source_row["sku_code"],
                "reviewed_at": source_row["reviewed_at"],
                "human_photo_truth": "NO_DEFECT",
                **review_queue.legacy_annotator_metadata(),
                "regression_source": "candidate_full_frame" if regression else "same_task_companion",
                "baseline_false_flags": int((source_row.get("baseline") or {}).get("false_flags", 0)),
                "candidate_full_false_flags": int((source_row.get("candidate") or {}).get("false_flags", 0)),
                "candidate_edge_false_flags": int((source_row.get("candidate") or {}).get("false_flags", 0)),
                "source_path": str(source_image),
                "source_sha256": source_hash,
                "source_perceptual_hash": review_queue.perceptual_hash(source_image),
                "original_size": list(image.size),
                "packed_size": list(image.size),
                "packed_image_sha256": diagnostics.sha256_file(packed_image),
                "preannotations": proposals,
                "preannotation_count": len(proposals),
                "preannotations_are_not_ground_truth": True,
                "manual_status": "PENDING_FULL_REVIEW",
                "selection_tags": [
                    "task_expanded_normal_regression",
                    "candidate_full_frame" if regression else "same_task_companion",
                    "qc_eligibility_cleanup",
                ],
                "train_only_after_complete_human_review": True,
                "evaluation_consumed": True,
                "exclude_from_future_independent_holdout": True,
                "protected_target": False,
            })
            if diagnostics.sha256_file(source_image) != source_hash:
                raise RuntimeError(f"task-regression source original changed: {source_image}")

    queue_count = len(manifest_rows)
    manifest = {
        "version": "label-qc-normal-regression-review-queue-v1",
        "created_at": diagnostics.utc_now(),
        "purpose": "full-task human tray cleanup around task-independent normal regressions",
        "source_shadow_receipt": str(shadow_path),
        "source_shadow_receipt_sha256": diagnostics.sha256_file(shadow_path),
        "queue_count": queue_count,
        "unique_tasks": len(regression_task_ids),
        "unique_skus": len({row["sku_code"] for row in manifest_rows}),
        "regression_photos": len(regression_photo_ids),
        "same_task_companion_photos": queue_count - len(regression_photo_ids),
        "preannotations_are_not_ground_truth": True,
        "every_image_requires_full_human_review": True,
        "annotation_policy": {
            "qc_eligible_tray": "keep or correct only when complete outer boundary is judgeable",
            "partial_or_occluded_tray": "delete proposal and retain as ignore_not_negative",
            "visible_labels_on_ignored_tray": "separate_label_side_view_truth",
            "deleted_proposals_are_background": False,
        },
        "review_gate": {
            "reviewed_required": queue_count,
            "source_required": "human",
            "training_allowed_before_complete_review": False,
            "training_use_after_gate": "train_only_hard_cases",
            "promotion_allowed": False,
            "deployment_allowed": False,
        },
        "source_normal_batch_photos": int((shadow.get("batch") or {})["photos"]),
        "consumed_regression_photos": queue_count,
        "remaining_locked_normal_photos": int((shadow.get("batch") or {})["photos"]) - queue_count,
        "remaining_pool_is_future_independent": False,
        "remaining_pool_reason": "entire source batch already consumed by model selection",
        "rows": manifest_rows,
        "protected_holdout_included": False,
        "production_reads": 0,
        "production_writes": 0,
        "originals_modified": False,
    }
    manifest_path = queue / "manifest.json"
    manifest_path.write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    (queue / "README.md").write_text(
        "# 六扇门任务级回归复核队列\n\n"
        "前 4 张是新候选新增误报，后 12 张是同任务配套照片。每张图都检查整张照片："
        "只保留或修正外轮廓完整、可用于 QC 的单个盒子框；删除重复框、假框、边缘截断或"
        "被上层遮住而无法判断完整外框的盒子框。删除框仍按 `ignore_not_negative` 处理。\n",
        encoding="utf-8",
    )
    receipt = {
        "version": "label-qc-task-regression-review-build-v1",
        "created_at": diagnostics.utc_now(),
        "queue": str(queue),
        "manifest": str(manifest_path),
        "manifest_sha256": diagnostics.sha256_file(manifest_path),
        "queue_count": queue_count,
        "regression_photos": len(regression_photo_ids),
        "same_task_companion_photos": queue_count - len(regression_photo_ids),
        "unique_tasks": len(regression_task_ids),
        "preannotation_total": sum(row["preannotation_count"] for row in manifest_rows),
        "remaining_pool_photos": manifest["remaining_locked_normal_photos"],
        "remaining_pool_is_future_independent": False,
        "production_writes": 0,
        "originals_modified": 0,
        "training_started": False,
        "deployment_started": False,
    }
    receipt_path = queue / "build-receipt.json"
    receipt_path.write_text(json.dumps(receipt, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    return queue, manifest, receipt


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo-root", required=True, type=Path)
    parser.add_argument("--shadow-receipt", required=True, type=Path)
    parser.add_argument("--output-root", required=True, type=Path)
    parser.add_argument("--expected-regressions", required=True, type=int)
    return parser


def main() -> None:
    args = build_parser().parse_args()
    queue, manifest, receipt = build_queue(
        args.repo_root, args.shadow_receipt, args.output_root, args.expected_regressions,
    )
    print(json.dumps({
        "queue": str(queue),
        "queue_count": manifest["queue_count"],
        "regression_photos": manifest["regression_photos"],
        "same_task_companion_photos": manifest["same_task_companion_photos"],
        "unique_tasks": manifest["unique_tasks"],
        "preannotation_total": receipt["preannotation_total"],
        "remaining_pool_photos": receipt["remaining_pool_photos"],
        "remaining_pool_is_future_independent": False,
        "training_started": False,
        "deployment_started": False,
        "production_writes": 0,
    }, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
