#!/usr/bin/env python3
"""Build a human tray-scope queue from independent-normal regressions.

Every detector box is a proposal.  Reviewers keep or correct only trays whose
complete outer boundary is judgeable.  Removed proposals remain in the queue
ledger and must later be treated as ``ignore_not_negative``, never as proof
that the image region contains no tray.
"""
from __future__ import annotations

import argparse
import datetime as dt
import json
import shutil
import sys
import tempfile
from pathlib import Path
from typing import Any, Iterable, Sequence

import imagehash
import numpy as np
from PIL import Image, ImageOps

import label_qc_oracle_diagnostics as diagnostics
from label_qc_tray_edge_context_eval import EdgeContextTrayModels, load_and_verify


REGRESSION_SOURCES = {
    "candidate_model",
    "edge_windows",
    "candidate_model_and_edge",
    "candidate_edge_interaction",
}


def normalise_box(box: Sequence[float], width: int, height: int) -> list[float]:
    if width <= 0 or height <= 0:
        raise ValueError("image dimensions must be positive")
    values = [
        max(0.0, min(1.0, float(box[0]) / width)),
        max(0.0, min(1.0, float(box[1]) / height)),
        max(0.0, min(1.0, float(box[2]) / width)),
        max(0.0, min(1.0, float(box[3]) / height)),
    ]
    if values[0] >= values[2] or values[1] >= values[3]:
        raise ValueError(f"invalid normalised box: {box}")
    return [round(value, 8) for value in values]


def yolo_line(box: Sequence[float]) -> str:
    x0, y0, x1, y1 = [float(value) for value in box]
    if not (0 <= x0 < x1 <= 1 and 0 <= y0 < y1 <= 1):
        raise ValueError(f"invalid proposal box: {box}")
    return (
        f"0 {(x0 + x1) / 2:.8f} {(y0 + y1) / 2:.8f} "
        f"{x1 - x0:.8f} {y1 - y0:.8f}\n"
    )


def perceptual_hash(path: Path) -> str:
    with Image.open(path) as opened:
        return str(imagehash.phash(ImageOps.exif_transpose(opened), hash_size=16))


def merge_detector_proposals(
    proposals: Iterable[dict[str, Any]],
    iou_threshold: float = 0.65,
    ios_threshold: float = 0.85,
) -> list[dict[str, Any]]:
    """Prefer production geometry while retaining every supporting source."""
    kept: list[dict[str, Any]] = []
    ordered = sorted(
        (dict(row) for row in proposals),
        key=lambda row: (int(row.get("priority", 0)), float(row.get("confidence", 0.0))),
        reverse=True,
    )
    for proposal in ordered:
        proposal["sources"] = [str(proposal["source"])]
        duplicate: dict[str, Any] | None = None
        for chosen in kept:
            if (
                diagnostics.box_iou(proposal["box"], chosen["box"]) >= iou_threshold
                or diagnostics.intersection_over_smaller(
                    proposal["box"], chosen["box"],
                ) >= ios_threshold
            ):
                duplicate = chosen
                break
        if duplicate is None:
            kept.append(proposal)
            continue
        duplicate["sources"] = sorted(set(
            duplicate.get("sources", []) + proposal.get("sources", []),
        ))
        duplicate.setdefault("support", []).append({
            "source": proposal["source"],
            "confidence": round(float(proposal.get("confidence", 0.0)), 6),
            "box": proposal["box"],
        })
    return kept


def select_regressions(
    attribution: dict[str, Any], expected_count: int,
) -> list[dict[str, Any]]:
    rows = [
        row for row in attribution.get("rows") or []
        if row.get("regression_source") in REGRESSION_SOURCES
        and int(row.get("candidate_edge_flags", 0)) > int(row.get("baseline_flags", 0))
    ]
    summary_count = int(attribution.get("summary", {}).get("regressed_photos", -1))
    if len(rows) != summary_count:
        raise RuntimeError("attribution rows do not match regressed-photo summary")
    if len(rows) != expected_count:
        raise RuntimeError(
            f"expected {expected_count} regression photos, found {len(rows)}"
        )
    photo_ids = [str(row["photo_id"]) for row in rows]
    if len(set(photo_ids)) != len(photo_ids):
        raise RuntimeError("duplicate regression photo ids")
    return rows


def _detector_rows(
    detections: Sequence[Any], source: str, priority: int,
    width: int, height: int,
) -> list[dict[str, Any]]:
    return [
        {
            "box": normalise_box(row.as_xyxy(), width, height),
            "confidence": round(float(row.confidence), 6),
            "source": source,
            "priority": priority,
            "is_ground_truth": False,
        }
        for row in detections
    ]


def _suspect_rows(
    suspects: Sequence[dict[str, Any]], width: int, height: int,
) -> list[dict[str, Any]]:
    return [
        {
            "box": normalise_box(row["box"], width, height),
            "confidence": round(float(row["confidence"]), 6),
            "source": "normal_shadow_regression_suspect",
            "priority": 2,
            "is_ground_truth": False,
            "suspect_verdict": row.get("verdict"),
        }
        for row in suspects
    ]


def legacy_annotator_metadata() -> dict[str, str]:
    """Fields required by the existing local tray annotator compatibility UI."""
    return {
        "angle": "qc_eligibility_cleanup",
        "source_kind": "independent_normal_regression",
        "human_defect_label": "NO_DEFECT",
    }


def build_queue(
    repo_root: Path,
    attribution_receipt_path: Path,
    output_root: Path,
    expected_count: int,
) -> tuple[Path, dict[str, Any], dict[str, Any]]:
    attribution_path = attribution_receipt_path.resolve()
    attribution = load_and_verify(attribution_path)
    if attribution.get("version") != "label-qc-normal-regression-attribution-v1":
        raise RuntimeError("unsupported attribution receipt")
    if attribution.get("deployment_started") is not False:
        raise RuntimeError("attribution receipt reports deployment")
    shadow_path = Path(str(attribution["inputs"]["shadow_receipt"]))
    if diagnostics.sha256_file(shadow_path) != attribution["inputs"]["shadow_receipt_sha256"]:
        raise RuntimeError("shadow receipt hash drift")
    shadow = load_and_verify(shadow_path)
    if shadow.get("batch", {}).get("independence", {}).get("independent") is not True:
        raise RuntimeError("source normal batch was not independent")
    if int(shadow.get("batch", {}).get("missing_positive_photos", -1)) != 0:
        raise RuntimeError("normal regression queue unexpectedly contains positives")
    rows = select_regressions(attribution, expected_count)
    shadow_by_id = {
        str(row["photo_id"]): row for row in shadow.get("details") or []
    }

    inputs = shadow["inputs"]
    production_tray = Path(str(inputs["production_tray_model"]))
    candidate_tray = Path(str(inputs["candidate_tray_model"]))
    label_model = Path(str(inputs["label_model"]))
    for path, expected_hash in (
        (production_tray, inputs["production_tray_model_sha256"]),
        (candidate_tray, inputs["candidate_tray_model_sha256"]),
        (label_model, inputs["label_model_sha256"]),
    ):
        if not path.is_file() or diagnostics.sha256_file(path) != expected_hash:
            raise RuntimeError(f"bound model drift: {path}")

    stamp = dt.datetime.now(dt.timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    queue = output_root.resolve() / f"tray-normal-regression-review-{stamp}"
    if queue.exists():
        raise FileExistsError(f"refusing to overwrite queue: {queue}")
    for name in ("images", "labels", "annotations-human"):
        (queue / name).mkdir(parents=True, exist_ok=False)

    sys.path.insert(0, str((repo_root.resolve() / "backend" / "python").resolve()))
    from label_qc.services import yolo_detector as yolo

    manifest_rows: list[dict[str, Any]] = []
    with tempfile.TemporaryDirectory(prefix="tray-normal-regression-models-") as temporary:
        temporary_root = Path(temporary)
        production_dir = temporary_root / "production"
        candidate_dir = temporary_root / "candidate"
        production_dir.mkdir()
        candidate_dir.mkdir()
        shutil.copy2(production_tray, production_dir / "tray.onnx")
        shutil.copy2(candidate_tray, candidate_dir / "tray.onnx")
        shutil.copy2(label_model, production_dir / "label.onnx")
        shutil.copy2(label_model, candidate_dir / "label.onnx")
        production_base = yolo.LabelQcYoloModels(model_dir=production_dir)
        candidate_base = yolo.LabelQcYoloModels(model_dir=candidate_dir)
        if not production_base.available or not candidate_base.available:
            raise RuntimeError(
                production_base.load_error or candidate_base.load_error
                or "bound models unavailable"
            )
        edge_models = EdgeContextTrayModels(
            candidate_base,
            yolo.Detection,
            edge_confidence=0.30,
            width_fraction=0.50,
            height_fraction=0.60,
        )
        for index, attribution_row in enumerate(rows, start=1):
            photo_id = str(attribution_row["photo_id"])
            shadow_row = shadow_by_id[photo_id]
            source_image = Path(str(shadow_row["image"]))
            source_hash = str(shadow_row["image_sha256"])
            if not source_image.is_file() or diagnostics.sha256_file(source_image) != source_hash:
                raise RuntimeError(f"source image drift: {source_image}")
            with Image.open(source_image) as opened:
                image = ImageOps.exif_transpose(opened).convert("RGB")
            frame = np.asarray(image)
            production_detections = production_base.detect_trays(frame, 0.60)
            candidate_detections = candidate_base.detect_trays(frame, 0.60)
            edge_detections = edge_models.detect_trays(frame, 0.60)
            proposals = merge_detector_proposals([
                *_detector_rows(
                    production_detections, "production_full_frame", 3,
                    image.width, image.height,
                ),
                *_detector_rows(
                    candidate_detections, "candidate_full_frame", 2,
                    image.width, image.height,
                ),
                *_detector_rows(
                    edge_detections, "candidate_full_plus_lower_edges", 1,
                    image.width, image.height,
                ),
                *_suspect_rows(
                    attribution_row.get("candidate_edge", {}).get("suspects") or [],
                    image.width,
                    image.height,
                ),
            ])
            stem = f"trayhard_{photo_id[:8]}_{index:03d}"
            packed_image = queue / "images" / f"{stem}.jpg"
            packed_label = queue / "labels" / f"{stem}.txt"
            annotation_path = queue / "annotations-human" / f"{stem}.json"
            shutil.copy2(source_image, packed_image)
            packed_label.write_text(
                "".join(yolo_line(row["box"]) for row in proposals),
                encoding="utf-8",
            )
            annotation = {
                "photo_id": stem,
                "source_photo_id": photo_id,
                "format": "normalised_xyxy",
                "reviewed": False,
                "source": "production_candidate_and_edge_proposals_require_full_human_review",
                "boxes": [row["box"] for row in proposals],
                "annotation_policy": {
                    "keep_or_correct": "one complete judgeable QC-eligible tray",
                    "delete": "partial, occluded, truncated, duplicate, or false proposal",
                    "deleted_proposal_semantics": "ignore_not_negative",
                    "visible_labels_on_deleted_tray": "separate_label_side_view_truth",
                    "switch_after_review": "confirms current photo in factory workflow",
                },
            }
            annotation_path.write_text(
                json.dumps(annotation, ensure_ascii=False, indent=2) + "\n",
                encoding="utf-8",
            )
            if diagnostics.sha256_file(source_image) != source_hash:
                raise RuntimeError(f"source original changed: {source_image}")
            manifest_rows.append({
                "queue_index": index,
                "packed_stem": stem,
                "packed_image": f"images/{packed_image.name}",
                "packed_label": f"labels/{packed_label.name}",
                "photo_id": photo_id,
                "source_photo_id": photo_id,
                "task_id": attribution_row["task_id"],
                "sku_code": attribution_row["sku_code"],
                "reviewed_at": shadow_row["reviewed_at"],
                "human_photo_truth": "NO_DEFECT",
                **legacy_annotator_metadata(),
                "regression_source": attribution_row["regression_source"],
                "baseline_false_flags": attribution_row["baseline_flags"],
                "candidate_full_false_flags": attribution_row["candidate_full_flags"],
                "candidate_edge_false_flags": attribution_row["candidate_edge_flags"],
                "source_path": str(source_image),
                "source_sha256": source_hash,
                "source_perceptual_hash": perceptual_hash(source_image),
                "original_size": list(image.size),
                "packed_size": list(image.size),
                "packed_image_sha256": diagnostics.sha256_file(packed_image),
                "preannotations": proposals,
                "preannotation_count": len(proposals),
                "preannotations_are_not_ground_truth": True,
                "manual_status": "PENDING_FULL_REVIEW",
                "selection_tags": [
                    "independent_normal_regression",
                    attribution_row["regression_source"],
                    "qc_eligibility_cleanup",
                ],
                "train_only_after_complete_human_review": True,
                "evaluation_consumed": True,
                "exclude_from_future_independent_holdout": True,
                "protected_target": False,
            })

    queue_count = len(manifest_rows)
    manifest = {
        "version": "label-qc-normal-regression-review-queue-v1",
        "created_at": diagnostics.utc_now(),
        "purpose": "human QC-eligibility cleanup of independent-normal candidate regressions",
        "source_attribution_receipt": str(attribution_path),
        "source_attribution_receipt_sha256": diagnostics.sha256_file(attribution_path),
        "source_shadow_receipt": str(shadow_path),
        "source_shadow_receipt_sha256": diagnostics.sha256_file(shadow_path),
        "queue_count": queue_count,
        "unique_tasks": len({row["task_id"] for row in manifest_rows}),
        "unique_skus": len({row["sku_code"] for row in manifest_rows}),
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
        "source_normal_batch_photos": int(shadow["batch"]["photos"]),
        "consumed_regression_photos": queue_count,
        "remaining_locked_normal_photos": int(shadow["batch"]["photos"]) - queue_count,
        "rows": manifest_rows,
        "protected_holdout_included": False,
        "production_reads": 0,
        "production_writes": 0,
        "originals_modified": False,
    }
    manifest_path = queue / "manifest.json"
    manifest_path.write_text(
        json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8",
    )
    (queue / "README.md").write_text(
        "# 六扇门正常图回归复核队列\n\n"
        "每张图请检查整张照片。只保留或修正外轮廓完整、可用于 QC 的单个盒子框；"
        "删掉重复框、假框，以及边缘截断或被上层遮住而无法判断完整外框的盒子框。\n\n"
        "删掉的 AI 框不会被当成背景，而会记作 `ignore_not_negative`。"
        "这些下层盒子上可见的白标和彩标仍保留给独立标签侧视流程。"
        "所有初始框都只是提议，不是真值。\n",
        encoding="utf-8",
    )
    build_receipt = {
        "version": "label-qc-normal-regression-review-build-v1",
        "created_at": diagnostics.utc_now(),
        "queue": str(queue),
        "manifest": str(manifest_path),
        "manifest_sha256": diagnostics.sha256_file(manifest_path),
        "queue_count": queue_count,
        "unique_tasks": manifest["unique_tasks"],
        "unique_skus": manifest["unique_skus"],
        "preannotation_total": sum(row["preannotation_count"] for row in manifest_rows),
        "remaining_locked_normal_photos": manifest["remaining_locked_normal_photos"],
        "production_writes": 0,
        "originals_modified": 0,
        "training_started": False,
        "deployment_started": False,
    }
    receipt_path = queue / "build-receipt.json"
    receipt_path.write_text(
        json.dumps(build_receipt, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )
    return queue, manifest, build_receipt


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo-root", required=True, type=Path)
    parser.add_argument("--attribution-receipt", required=True, type=Path)
    parser.add_argument("--output-root", required=True, type=Path)
    parser.add_argument("--expected-count", required=True, type=int)
    return parser


def main() -> None:
    args = build_parser().parse_args()
    queue, manifest, receipt = build_queue(
        args.repo_root,
        args.attribution_receipt,
        args.output_root,
        args.expected_count,
    )
    print(json.dumps({
        "status": "normal-regression-review-ready",
        "queue": str(queue),
        "queue_count": manifest["queue_count"],
        "unique_tasks": manifest["unique_tasks"],
        "unique_skus": manifest["unique_skus"],
        "preannotation_total": receipt["preannotation_total"],
        "remaining_locked_normal_photos": receipt["remaining_locked_normal_photos"],
        "policy": manifest["annotation_policy"],
        "training_started": False,
        "deployment_started": False,
        "production_writes": 0,
        "originals_modified": 0,
    }, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
