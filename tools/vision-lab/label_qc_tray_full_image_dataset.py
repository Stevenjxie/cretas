#!/usr/bin/env python3
"""Append reviewed full-image tray truth to an immutable base dataset.

The new rows are train-only so the existing validation split stays unchanged.
Occluded lower trays remain ignore regions for tray truth; visible labels on
those trays remain eligible for a separate label-side-view workflow.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import math
import os
import shutil
from pathlib import Path
from typing import Any, Sequence

import label_qc_tray_patch_dataset as patch_dataset
import tray_workflow


NORMAL_REGRESSION_QUEUE_VERSION = "label-qc-normal-regression-review-queue-v1"
FRESH_FACTORY_SHADOW_VERSION = "label-qc-fresh-factory-development-shadow-v1"
PRIMARY_MATCH_IOU = 0.50
REBOX_MATCH_IOU = 0.20
REBOX_MAX_CENTER_DISTANCE = 0.15


def load_json(path: Path) -> dict[str, Any]:
    value = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(value, dict):
        raise ValueError(f"expected JSON object: {path}")
    return value


def queue_identity(queue: Path, reviewed: Sequence[dict[str, Any]]) -> dict[str, Any]:
    return {
        "queue_manifest_sha256": patch_dataset.sha256_file(queue / "manifest.json"),
        "annotations": [
            {
                "packed_stem": str(row["source"]["packed_stem"]),
                "task_id": str(row["task_id"]),
                "annotation_sha256": str(row["annotation_sha256"]),
                "box_count": len(row["boxes"]),
            }
            for row in reviewed
        ],
    }


def box_iou(left: Sequence[float], right: Sequence[float]) -> float:
    left_box = patch_dataset.validate_box(left)
    right_box = patch_dataset.validate_box(right)
    x0, y0 = max(left_box[0], right_box[0]), max(left_box[1], right_box[1])
    x1, y1 = min(left_box[2], right_box[2]), min(left_box[3], right_box[3])
    intersection = max(0.0, x1 - x0) * max(0.0, y1 - y0)
    left_area = (left_box[2] - left_box[0]) * (left_box[3] - left_box[1])
    right_area = (right_box[2] - right_box[0]) * (right_box[3] - right_box[1])
    union = left_area + right_area - intersection
    return intersection / union if union > 0 else 0.0


def center_distance(left: Sequence[float], right: Sequence[float]) -> float:
    left_box = patch_dataset.validate_box(left)
    right_box = patch_dataset.validate_box(right)
    left_center = ((left_box[0] + left_box[2]) / 2.0, (left_box[1] + left_box[3]) / 2.0)
    right_center = ((right_box[0] + right_box[2]) / 2.0, (right_box[1] + right_box[3]) / 2.0)
    return math.dist(left_center, right_center)


def audit_preannotations(source: dict[str, Any], human_boxes: Sequence[Sequence[float]]) -> dict[str, Any]:
    proposals = [
        patch_dataset.validate_box(item["box"])
        for item in source.get("preannotations") or []
    ]
    truth = [patch_dataset.validate_box(box) for box in human_boxes]
    proposal_used: set[int] = set()
    truth_used: set[int] = set()
    matches: list[dict[str, Any]] = []

    ranked = sorted(
        (
            (box_iou(proposal, human), proposal_index, human_index)
            for proposal_index, proposal in enumerate(proposals)
            for human_index, human in enumerate(truth)
        ),
        reverse=True,
    )
    for score, proposal_index, human_index in ranked:
        if score < PRIMARY_MATCH_IOU:
            break
        if proposal_index in proposal_used or human_index in truth_used:
            continue
        proposal_used.add(proposal_index)
        truth_used.add(human_index)
        matches.append({
            "proposal_index": proposal_index,
            "human_index": human_index,
            "iou": round(score, 6),
            "match_kind": "primary_iou",
        })

    rebox_ranked = sorted(
        (
            (box_iou(proposals[i], truth[j]), center_distance(proposals[i], truth[j]), i, j)
            for i in range(len(proposals)) if i not in proposal_used
            for j in range(len(truth)) if j not in truth_used
        ),
        key=lambda item: (-item[0], item[1]),
    )
    for score, distance, proposal_index, human_index in rebox_ranked:
        if score < REBOX_MATCH_IOU or distance > REBOX_MAX_CENTER_DISTANCE:
            continue
        if proposal_index in proposal_used or human_index in truth_used:
            continue
        proposal_used.add(proposal_index)
        truth_used.add(human_index)
        matches.append({
            "proposal_index": proposal_index,
            "human_index": human_index,
            "iou": round(score, 6),
            "center_distance": round(distance, 6),
            "match_kind": "large_rebox",
        })

    unchanged = sum(float(match["iou"]) >= 0.95 for match in matches)
    removed = [
        {"proposal_index": index, "box": proposal}
        for index, proposal in enumerate(proposals) if index not in proposal_used
    ]
    added = [
        {"human_index": index, "box": human}
        for index, human in enumerate(truth) if index not in truth_used
    ]
    return {
        "packed_stem": str(source["packed_stem"]),
        "proposal_count": len(proposals),
        "human_box_count": len(truth),
        "matched_count": len(matches),
        "unchanged_count": unchanged,
        "adjusted_count": len(matches) - unchanged,
        "removed_count": len(removed),
        "added_count": len(added),
        "removed_proposals": removed,
        "added_human_boxes": added,
        "training_eligible": not removed,
        "training_exclusion_reason": None if not removed else "contains_ignore_not_negative_region",
    }


def normal_shadow_details(shadow: dict[str, Any]) -> tuple[int, list[dict[str, Any]]]:
    """Return the evaluated normal batch from either supported receipt schema."""
    batch = shadow.get("batch")
    if isinstance(batch, dict):
        details = shadow.get("details") or []
        return int(batch.get("photos", -1)), details

    if shadow.get("version") != FRESH_FACTORY_SHADOW_VERSION:
        raise RuntimeError("unsupported normal regression source shadow schema")
    split = shadow.get("split_lock") or {}
    development = shadow.get("development") or {}
    final = shadow.get("final") or {}
    if (
        final.get("evaluated") is not False
        or final.get("model_inference_started") is not False
        or final.get("training_use_allowed") is not False
        or split.get("final_model_inference_started") is not False
        or split.get("final_training_use_allowed") is not False
    ):
        raise RuntimeError("fresh factory final split is not sealed")
    details = development.get("details") or []
    photo_ids = [str(row.get("photo_id") or "") for row in details]
    image_hashes = [str(row.get("image_sha256") or "") for row in details]
    if (
        sorted(photo_ids) != sorted(str(value) for value in split.get("development_photo_ids") or [])
        or sorted(image_hashes) != sorted(
            str(value) for value in split.get("development_image_sha256") or []
        )
    ):
        raise RuntimeError("fresh factory development split identity drift")
    return int(split.get("development_photos", -1)), details


def validate_normal_regression_contract(
    queue_manifest: dict[str, Any], reviewed: Sequence[dict[str, Any]],
) -> dict[str, Any]:
    if queue_manifest.get("every_image_requires_full_human_review") is not True:
        raise RuntimeError("normal regression queue human-review gate drift")
    review_gate = queue_manifest.get("review_gate") or {}
    if (
        review_gate.get("training_allowed_before_complete_review") is not False
        or review_gate.get("training_use_after_gate") != "train_only_hard_cases"
        or review_gate.get("promotion_allowed") is not False
        or review_gate.get("deployment_allowed") is not False
    ):
        raise RuntimeError("normal regression queue training boundary drift")
    source_rows = queue_manifest.get("rows") or []
    if any(
        row.get("train_only_after_complete_human_review") is not True
        or row.get("evaluation_consumed") is not True
        or row.get("exclude_from_future_independent_holdout") is not True
        or row.get("protected_target") is not False
        or row.get("human_photo_truth") != "NO_DEFECT"
        for row in source_rows
    ):
        raise RuntimeError("normal regression queue row contract drift")
    policy = queue_manifest.get("annotation_policy") or {}
    if (
        policy.get("partial_or_occluded_tray") != "delete proposal and retain as ignore_not_negative"
        or policy.get("visible_labels_on_ignored_tray") != "separate_label_side_view_truth"
        or policy.get("deleted_proposals_are_background") is not False
    ):
        raise RuntimeError("normal regression queue ignore-region policy drift")

    shadow_path = Path(str(queue_manifest.get("source_shadow_receipt") or "")).resolve()
    expected_shadow_sha = str(queue_manifest.get("source_shadow_receipt_sha256") or "")
    if not shadow_path.is_file() or patch_dataset.sha256_file(shadow_path) != expected_shadow_sha:
        raise RuntimeError("normal regression source shadow receipt drift")
    shadow = load_json(shadow_path)
    source_photo_count, details = normal_shadow_details(shadow)
    if source_photo_count <= 0 or len(details) != source_photo_count:
        raise RuntimeError("normal regression source shadow count drift")
    if source_photo_count != int(queue_manifest.get("source_normal_batch_photos", -1)):
        raise RuntimeError("normal regression queue/source batch count mismatch")
    detail_by_photo = {str(row.get("photo_id") or ""): row for row in details}
    if "" in detail_by_photo or len(detail_by_photo) != len(details):
        raise RuntimeError("normal regression source shadow photo identity drift")

    selected_photo_ids = {str(row["source"]["source_photo_id"]) for row in reviewed}
    selected_tasks = {str(row["task_id"]) for row in reviewed}
    if len(selected_photo_ids) != len(reviewed):
        raise RuntimeError("normal regression reviewed photo identity is not unique")
    if len(selected_tasks) != int(queue_manifest.get("unique_tasks", -1)):
        raise RuntimeError("normal regression reviewed task count drift")
    for item in reviewed:
        source = item["source"]
        detail = detail_by_photo.get(str(source["source_photo_id"]))
        if detail is None:
            raise RuntimeError("normal regression reviewed photo missing from source shadow")
        if (
            str(detail.get("task_id")) != str(item["task_id"])
            or str(detail.get("image_sha256")) != str(source["source_sha256"])
        ):
            raise RuntimeError("normal regression reviewed/source shadow identity mismatch")

    photo_level_remaining = [row for row in details if str(row["photo_id"]) not in selected_photo_ids]
    if len(photo_level_remaining) != int(queue_manifest.get("remaining_locked_normal_photos", -1)):
        raise RuntimeError("normal regression photo-level remainder drift")
    remaining_pool_is_future_independent = queue_manifest.get(
        "remaining_pool_is_future_independent", False,
    )
    if not isinstance(remaining_pool_is_future_independent, bool):
        raise RuntimeError("normal regression remaining-pool independence flag drift")
    same_task_excluded = [row for row in photo_level_remaining if str(row["task_id"]) in selected_tasks]
    task_disjoint = [row for row in photo_level_remaining if str(row["task_id"]) not in selected_tasks]
    if {str(row["task_id"]) for row in task_disjoint} & selected_tasks:
        raise RuntimeError("normal regression task-independent holdout leaked selected task")
    future_independent = task_disjoint if remaining_pool_is_future_independent else []
    secondary_regression = photo_level_remaining if not remaining_pool_is_future_independent else []

    row_audits = [audit_preannotations(item["source"], item["boxes"]) for item in reviewed]
    training_stems = [row["packed_stem"] for row in row_audits if row["training_eligible"]]
    if len(training_stems) < 2:
        raise RuntimeError("too few normal regression rows remain after ignore-region exclusion")
    return {
        "mode": "normal_regression_hard_cases",
        "matching_policy": {
            "primary_iou": PRIMARY_MATCH_IOU,
            "large_rebox_min_iou": REBOX_MATCH_IOU,
            "large_rebox_max_center_distance": REBOX_MAX_CENTER_DISTANCE,
        },
        "human_reviewed_images": len(reviewed),
        "proposal_boxes": sum(row["proposal_count"] for row in row_audits),
        "human_boxes": sum(row["human_box_count"] for row in row_audits),
        "unchanged_boxes": sum(row["unchanged_count"] for row in row_audits),
        "adjusted_boxes": sum(row["adjusted_count"] for row in row_audits),
        "removed_ignore_regions": sum(row["removed_count"] for row in row_audits),
        "added_boxes": sum(row["added_count"] for row in row_audits),
        "training_stems": training_stems,
        "training_images": len(training_stems),
        "excluded_images": len(reviewed) - len(training_stems),
        "row_audits": row_audits,
        "normal_holdout_lock": {
            "source_shadow_receipt": str(shadow_path),
            "source_shadow_receipt_sha256": expected_shadow_sha,
            "source_photos": source_photo_count,
            "consumed_review_photos": len(selected_photo_ids),
            "consumed_task_ids": sorted(selected_tasks),
            "photo_level_remaining": len(photo_level_remaining),
            "same_task_excluded_photos": len(same_task_excluded),
            "same_task_excluded_photo_ids": sorted(str(row["photo_id"]) for row in same_task_excluded),
            "task_disjoint_remaining_photos": len(task_disjoint),
            "remaining_pool_is_future_independent": remaining_pool_is_future_independent,
            "remaining_pool_reason": queue_manifest.get("remaining_pool_reason"),
            "task_independent_photos": len(future_independent),
            "task_independent_tasks": len({str(row["task_id"]) for row in future_independent}),
            "task_independent_photo_ids": sorted(str(row["photo_id"]) for row in future_independent),
            "secondary_regression_photos": len(secondary_regression),
            "secondary_regression_photo_ids": sorted(
                str(row["photo_id"]) for row in secondary_regression
            ),
        },
    }


def validate_append_contract(
    prior: dict[str, Any], queue_manifest: dict[str, Any], reviewed: Sequence[dict[str, Any]],
    prior_provenance: Sequence[dict[str, Any]],
) -> dict[str, Any]:
    if prior.get("protected_holdout_included") is not False:
        raise RuntimeError("base dataset does not prove protected holdout exclusion")
    if queue_manifest.get("protected_holdout_included") is not False:
        raise RuntimeError("review queue contains protected holdout")
    if queue_manifest.get("preannotations_are_not_ground_truth") is not True:
        raise RuntimeError("review queue proposal/truth boundary is unsafe")
    source_rows = queue_manifest.get("rows") or []
    if len(source_rows) != len(reviewed) or len(reviewed) < 2:
        raise RuntimeError("review queue completion/count gate failed")
    existing_hashes = {str(row.get("source_sha256") or "") for row in prior_provenance}
    existing_tasks = {str(row.get("task_id") or "") for row in prior_provenance}
    new_hashes = [str(row["source"]["source_sha256"]) for row in reviewed]
    new_tasks = [str(row["task_id"]) for row in reviewed]
    if len(set(new_hashes)) != len(new_hashes):
        raise RuntimeError("review queue source hashes are not unique")
    if existing_hashes & set(new_hashes):
        raise RuntimeError("reviewed full image already exists in base dataset")
    if existing_tasks & set(new_tasks):
        raise RuntimeError("reviewed task already exists in base dataset")
    if queue_manifest.get("version") == NORMAL_REGRESSION_QUEUE_VERSION:
        return validate_normal_regression_contract(queue_manifest, reviewed)

    if queue_manifest.get("all_rows_train_only") is not True:
        raise RuntimeError("review queue is not entirely train-only")
    if any(row.get("train_only") is not True or row.get("protected_target") is not False for row in source_rows):
        raise RuntimeError("review queue row train/protection contract drift")
    if len(set(new_tasks)) != len(new_tasks):
        raise RuntimeError("legacy review queue is not independent by task")
    policy = queue_manifest.get("annotation_policy") or {}
    if policy.get("occluded_lower_trays") != "ignore_not_negative":
        raise RuntimeError("occluded lower tray policy drift")
    if policy.get("visible_lower_labels") != "separate_label_side_view_truth":
        raise RuntimeError("visible lower label policy drift")
    return {
        "mode": "legacy_all_rows_train_only",
        "training_stems": [str(row["source"]["packed_stem"]) for row in reviewed],
        "training_images": len(reviewed),
        "excluded_images": 0,
    }


def build_dataset(
    prior_manifest_path: Path, reviewed_queue: Path, output_root: Path,
) -> tuple[Path, dict[str, Any]]:
    prior_manifest_path = prior_manifest_path.resolve()
    reviewed_queue = reviewed_queue.resolve()
    output_root = output_root.resolve()
    prior = load_json(prior_manifest_path)
    prior_root = prior_manifest_path.parent
    if prior.get("dataset_sha256") != patch_dataset.dataset_digest(prior_root):
        raise RuntimeError("base dataset content drift")
    queue_manifest, reviewed = tray_workflow.validate_reviewed_queue(
        reviewed_queue, Path(str(prior["protected_holdout"])).resolve(),
    )
    provenance_path = prior_root / "provenance.json"
    provenance = load_json(provenance_path)
    prior_rows = provenance.get("rows") or []
    append_audit = validate_append_contract(prior, queue_manifest, reviewed, prior_rows)
    training_stems = set(append_audit["training_stems"])
    training_reviewed = [
        item for item in reviewed if str(item["source"]["packed_stem"]) in training_stems
    ]
    if len(training_reviewed) != len(training_stems):
        raise RuntimeError("training eligibility stems do not map one-to-one to reviewed rows")
    identity = {
        "version": "label-qc-tray-full-image-dataset-v1",
        "base_dataset_sha256": prior["dataset_sha256"],
        "reviewed_queue": queue_identity(reviewed_queue, reviewed),
        "append_audit": append_audit,
        "split_policy": "append_train_only_preserve_base_validation",
        "tight_patch_rows_included": False,
    }
    dataset_id = "tray-full-" + hashlib.sha256(patch_dataset.stable_json(identity)).hexdigest()[:12]
    output = output_root / dataset_id
    if output.exists():
        existing = load_json(output / "manifest.json")
        if existing.get("identity") != identity or existing.get("dataset_sha256") != patch_dataset.dataset_digest(output):
            raise RuntimeError(f"existing full-image dataset identity/content drift: {output}")
        return output, existing
    temporary = output.with_name(output.name + f".tmp.{os.getpid()}")
    if temporary.exists():
        raise RuntimeError(f"stale temporary dataset directory: {temporary}")
    output_root.mkdir(parents=True, exist_ok=True)
    shutil.copytree(prior_root, temporary)
    copied_provenance_path = temporary / "provenance.json"
    copied_provenance = load_json(copied_provenance_path)
    copied_rows = copied_provenance.get("rows") or []
    appended: list[dict[str, Any]] = []
    counts = dict(prior.get("counts") or {})
    for item in training_reviewed:
        source = item["source"]
        stem = str(source["packed_stem"])
        image_out = temporary / "images" / "train" / f"{stem}.jpg"
        label_out = temporary / "labels" / "train" / f"{stem}.txt"
        annotation_out = temporary / "annotations-source" / f"{stem}.json"
        if any(path.exists() for path in (image_out, label_out, annotation_out)):
            raise RuntimeError(f"full-image dataset name collision: {stem}")
        shutil.copy2(item["image"], image_out)
        shutil.copy2(item["annotation_path"], annotation_out)
        label_out.write_text(
            "\n".join(patch_dataset.yolo_line(box) for box in item["boxes"]) + "\n",
            encoding="utf-8",
        )
        row = {
            "stem": stem,
            "split": "train",
            "task_id": str(item["task_id"]),
            "queue": str(reviewed_queue),
            "source_photo_id": str(source["source_photo_id"]),
            "source_sha256": str(source["source_sha256"]),
            "packed_image_sha256": str(source["packed_image_sha256"]),
            "annotation_sha256": str(item["annotation_sha256"]),
            "box_count": len(item["boxes"]),
            "selection_tags": list(source.get("selection_tags") or []),
            "human_truth": True,
            "full_image": True,
            "train_only": True,
            "occluded_lower_trays": "ignore_not_negative",
            "visible_lower_labels": "separate_label_side_view_truth",
            "source_review_queue_count": len(reviewed),
        }
        copied_rows.append(row)
        appended.append(row | {
            "image": str(output / "images" / "train" / image_out.name),
            "label": str(output / "labels" / "train" / label_out.name),
            "annotation": str(output / "annotations-source" / annotation_out.name),
        })
        counts["train_images"] = int(counts.get("train_images", 0)) + 1
        counts["train_boxes"] = int(counts.get("train_boxes", 0)) + len(item["boxes"])
    copied_provenance["rows"] = copied_rows
    copied_provenance["full_image_append_policy"] = {
        "base_validation_unchanged": True,
        "new_rows_train_only": True,
        "tight_patch_rows_included": False,
        "occluded_lower_trays": "ignore_not_negative",
        "visible_lower_labels": "separate_label_side_view_truth",
    }
    copied_provenance_path.write_text(
        json.dumps(copied_provenance, ensure_ascii=False, indent=2) + "\n", encoding="utf-8",
    )
    (temporary / "data.yaml").write_text(
        f"path: {output.as_posix()}\ntrain: images/train\nval: images/val\nnames:\n  0: tray\n",
        encoding="utf-8",
    )
    manifest = {
        "version": "label-qc-tray-full-image-dataset-v1",
        "dataset_id": dataset_id,
        "identity": identity,
        "base_dataset": str(prior_root),
        "base_dataset_id": prior.get("dataset_id"),
        "base_dataset_sha256": prior.get("dataset_sha256"),
        "reviewed_queue": str(reviewed_queue),
        "reviewed_queue_manifest_sha256": patch_dataset.sha256_file(reviewed_queue / "manifest.json"),
        "append_audit": append_audit,
        "counts": counts,
        "source_human_reviewed_images": len(reviewed),
        "source_human_reviewed_boxes": sum(len(item["boxes"]) for item in reviewed),
        "human_reviewed_images": int(prior.get("human_reviewed_images", 0)) + len(appended),
        "human_boxes": int(prior.get("human_boxes", 0)) + sum(int(row["box_count"]) for row in appended),
        "appended_train_images": len(appended),
        "appended_train_boxes": sum(int(row["box_count"]) for row in appended),
        "excluded_reviewed_images": len(reviewed) - len(appended),
        "excluded_reviewed_boxes": sum(len(item["boxes"]) for item in reviewed) - sum(
            int(row["box_count"]) for row in appended
        ),
        "independent_appended_tasks": len({row["task_id"] for row in appended}),
        "appended_rows": appended,
        "validation_task_ids": prior.get("validation_task_ids") or [],
        "validation_unchanged_from_base_dataset": True,
        "tight_patch_rows_included": False,
        "scope_policy": {
            "tray_truth": "only judgeable complete outer tray boundaries",
            "occluded_lower_trays": "ignore_not_negative",
            "visible_lower_labels": "separate_label_side_view_truth",
            "absence_in_unseen_region": "must_not_be_inferred",
        },
        "all_appended_rows_train_only": True,
        "deleted_proposals_used_as_background": False,
        "images_with_ignore_regions_excluded_from_training": True,
        "protected_holdout": prior.get("protected_holdout"),
        "protected_holdout_included": False,
        "protected_target_used_for_training": False,
        "preannotations_used_as_truth": False,
        "training_allowed": True,
        "promotion_allowed": False,
        "gpu_rental_required": False,
        "production_writes": 0,
        "originals_modified": False,
        "data_yaml": str(output / "data.yaml"),
    }
    manifest["dataset_sha256"] = patch_dataset.dataset_digest(temporary)
    (temporary / "manifest.json").write_text(
        json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8",
    )
    temporary.replace(output)
    return output, manifest


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--prior-dataset-manifest", required=True, type=Path)
    parser.add_argument("--reviewed-queue", required=True, type=Path)
    parser.add_argument("--output-root", required=True, type=Path)
    return parser


def main() -> None:
    args = build_parser().parse_args()
    output, manifest = build_dataset(args.prior_dataset_manifest, args.reviewed_queue, args.output_root)
    print(json.dumps({
        "dataset": str(output),
        "dataset_id": manifest["dataset_id"],
        "dataset_sha256": manifest["dataset_sha256"],
        "train_images": manifest["counts"]["train_images"],
        "train_boxes": manifest["counts"]["train_boxes"],
        "val_images": manifest["counts"]["val_images"],
        "val_boxes": manifest["counts"]["val_boxes"],
        "appended_train_images": manifest["appended_train_images"],
        "appended_train_boxes": manifest["appended_train_boxes"],
        "excluded_reviewed_images": manifest["excluded_reviewed_images"],
        "excluded_reviewed_boxes": manifest["excluded_reviewed_boxes"],
        "task_independent_normal_holdout_photos": (
            (manifest.get("append_audit") or {}).get("normal_holdout_lock") or {}
        ).get("task_independent_photos"),
        "secondary_normal_regression_photos": (
            (manifest.get("append_audit") or {}).get("normal_holdout_lock") or {}
        ).get("secondary_regression_photos"),
        "remaining_pool_is_future_independent": (
            (manifest.get("append_audit") or {}).get("normal_holdout_lock") or {}
        ).get("remaining_pool_is_future_independent"),
        "validation_unchanged": manifest["validation_unchanged_from_base_dataset"],
        "tight_patch_rows_included": manifest["tight_patch_rows_included"],
        "training_allowed": manifest["training_allowed"],
        "production_writes": 0,
        "originals_modified": False,
    }, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
