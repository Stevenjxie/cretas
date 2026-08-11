#!/usr/bin/env python3
"""Build an independent human work-area queue from an approved plan receipt."""
from __future__ import annotations

import argparse
import datetime as dt
import hashlib
import json
import math
import shutil
from collections import Counter
from pathlib import Path
from typing import Any


PLAN_VERSION = "vision-lab-work-area-roi-plan-v1"
RAW_TRAY_PLAN_VERSION = "vision-lab-work-area-raw-tray-plan-v1"
QUEUE_VERSION = "vision-lab-work-area-queue-v1"


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def load_json(path: Path) -> dict[str, Any]:
    value = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(value, dict):
        raise ValueError(f"expected object: {path}")
    return value


def write_json(path: Path, payload: Any) -> None:
    temporary = path.with_suffix(path.suffix + ".tmp")
    temporary.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    temporary.replace(path)


def validate_plan(plan: dict[str, Any]) -> list[dict[str, Any]]:
    selected = plan.get("selected")
    if plan.get("version") != PLAN_VERSION or plan.get("plan_only") is not True:
        raise RuntimeError("unsupported or non-plan ROI receipt")
    if plan.get("queue_created") is not False or plan.get("mark_created") is not False:
        raise RuntimeError("ROI plan was already marked as built")
    if not isinstance(selected, list) or len(selected) != int(plan.get("selected_count", -1)):
        raise RuntimeError("ROI plan selected rows are incomplete")
    if len(selected) != int(plan.get("target_count", -1)):
        raise RuntimeError("ROI plan did not satisfy its target count")
    task_ids = {str(row.get("task_id") or "") for row in selected}
    if "" in task_ids or len(task_ids) != len(selected):
        raise RuntimeError("ROI plan must contain one image per independent task")
    required_passes = (
        "protected_exact_exclusion_passed",
        "protected_phash_exclusion_passed",
        "old_mark_source_exclusion_passed",
    )
    if plan.get("completed_roi_queues") is not None:
        required_passes += ("completed_roi_exclusion_passed",)
    if any(any(row.get(key) is not True for key in required_passes) for row in selected):
        raise RuntimeError("ROI plan contains a row without required exclusion evidence")
    return selected


def verify_bound_file(path_value: str, expected_sha256: str, label: str) -> Path:
    path = Path(path_value)
    if not path.is_file() or sha256(path) != expected_sha256:
        raise RuntimeError(f"{label} hash drift: {path}")
    return path


def validate_tray_box(box: Any) -> None:
    if not isinstance(box, list) or len(box) != 4:
        raise RuntimeError(f"invalid reviewed tray box: {box}")
    if any(isinstance(value, bool) for value in box):
        raise RuntimeError(f"invalid reviewed tray box: {box}")
    try:
        x0, y0, x1, y1 = (float(value) for value in box)
    except (TypeError, ValueError) as exc:
        raise RuntimeError(f"invalid reviewed tray box: {box}") from exc
    if not all(math.isfinite(value) for value in (x0, y0, x1, y1)):
        raise RuntimeError(f"invalid reviewed tray box: {box}")
    if not (0 <= x0 < x1 <= 1 and 0 <= y0 < y1 <= 1):
        raise RuntimeError(f"reviewed tray box is outside the image: {box}")


def validate_reviewed_tray_annotation(path: Path, expected_stem: str) -> None:
    annotation = load_json(path)
    if (annotation.get("reviewed") is not True or annotation.get("source") != "human"
            or annotation.get("format") != "normalised_xyxy"):
        raise RuntimeError(f"tray context is not reviewed human truth: {path}")
    if annotation.get("photo_id") != expected_stem:
        raise RuntimeError(f"reviewed tray annotation photo binding drift: {path}")
    boxes = annotation.get("boxes")
    if not isinstance(boxes, list) or not boxes:
        raise RuntimeError(f"reviewed tray annotation has no boxes: {path}")
    for box in boxes:
        validate_tray_box(box)


def validate_raw_tray_plan_for_roi(
    plan_path: Path, expected_plan_sha256: str, reviewed_tray_queue: Path,
    queue_parent: Path,
) -> tuple[dict[str, Any], list[dict[str, Any]], str]:
    digest = expected_plan_sha256.lower()
    if sha256(plan_path) != digest:
        raise RuntimeError("raw tray plan receipt SHA does not match the approved digest")
    plan = load_json(plan_path)
    selected = plan.get("selected")
    if (plan.get("version") != RAW_TRAY_PLAN_VERSION or plan.get("plan_only") is not True
            or plan.get("queue_created") is not False or plan.get("mark_created") is not False):
        raise RuntimeError("unsupported or already-used raw tray plan")
    if not isinstance(selected, list) or len(selected) != int(plan.get("selected_count", -1)):
        raise RuntimeError("raw tray plan selected rows are incomplete")
    required = (
        "existing_photo_task_sha_exclusion_passed",
        "protected_exact_exclusion_passed",
        "protected_phash_exclusion_passed",
    )
    if any(any(row.get(key) is not True for key in required) for row in selected):
        raise RuntimeError("raw tray plan contains a row without required exclusion evidence")
    for key in ("photo_id", "task_id", "source_sha256"):
        values = [str(row.get(key) or "") for row in selected]
        if any(not value for value in values) or len(set(values)) != len(selected):
            raise RuntimeError(f"raw tray plan {key} values are missing or duplicated")

    manifest_paths = plan.get("existing_manifests")
    manifest_hashes = plan.get("existing_manifest_sha256s")
    if not isinstance(manifest_paths, list) or not isinstance(manifest_hashes, dict):
        raise RuntimeError("raw tray plan existing manifest bindings are invalid")
    resolved_manifests = [str(Path(str(value)).resolve()) for value in manifest_paths]
    if set(resolved_manifests) != set(manifest_hashes):
        raise RuntimeError("raw tray plan existing manifest set drift")
    for manifest in resolved_manifests:
        verify_bound_file(manifest, str(manifest_hashes[manifest]), "raw tray plan existing manifest")
    verify_bound_file(
        str(plan["protected_manifest"]), str(plan["protected_manifest_sha256"]),
        "raw tray plan protected manifest",
    )

    reviewed_tray_queue = reviewed_tray_queue.resolve()
    manifest_path = reviewed_tray_queue / "manifest.json"
    manifest = load_json(manifest_path)
    rows = manifest.get("rows")
    if not isinstance(rows, list) or len(rows) != int(manifest.get("queue_count", -1)):
        raise RuntimeError("reviewed tray queue manifest count drift")
    if len(rows) != len(selected):
        raise RuntimeError("reviewed tray queue does not contain the complete raw plan batch")
    rows_by_photo = {str(row.get("source_photo_id") or ""): row for row in rows}
    selected_by_photo = {str(row.get("photo_id") or ""): row for row in selected}
    if "" in rows_by_photo or set(rows_by_photo) != set(selected_by_photo):
        raise RuntimeError("reviewed tray queue photo set differs from the raw plan")

    annotation_dir = reviewed_tray_queue / "annotations-human"
    expected_annotations: set[str] = set()
    planned_rows: list[dict[str, Any]] = []
    binding_keys = (
        ("task_id", "task_id"), ("sku_code", "sku_code"),
        ("source_path", "source_path"), ("source_sha256", "source_sha256"),
        ("source_perceptual_hash", "source_perceptual_hash"),
    )
    for planned in selected:
        photo_id = str(planned["photo_id"])
        source_row = rows_by_photo[photo_id]
        for plan_key, row_key in binding_keys:
            if str(planned.get(plan_key)) != str(source_row.get(row_key)):
                raise RuntimeError(f"raw plan to reviewed tray binding drift: {photo_id} {plan_key}")
        source = verify_bound_file(
            str(source_row["source_path"]), str(source_row["source_sha256"]), "source image",
        )
        packed = reviewed_tray_queue / str(source_row["packed_image"])
        verify_bound_file(str(packed), str(source_row["packed_image_sha256"]), "packed image")
        stem = str(source_row["packed_stem"])
        annotation_path = annotation_dir / f"{stem}.json"
        validate_reviewed_tray_annotation(annotation_path, stem)
        expected_annotations.add(annotation_path.name)
        row = dict(source_row)
        row["queue"] = str(reviewed_tray_queue)
        row["source_path"] = str(source)
        planned_rows.append(row)
    actual_annotations = {path.name for path in annotation_dir.glob("*.json") if path.is_file()}
    if actual_annotations != expected_annotations:
        raise RuntimeError("reviewed tray annotation file set differs from the raw plan batch")

    queue_parent = queue_parent.resolve()
    if queue_parent.is_dir():
        for existing_manifest in queue_parent.glob("work-area-active-*/manifest.json"):
            existing = load_json(existing_manifest)
            if existing.get("source_raw_tray_plan_sha256") == digest:
                raise RuntimeError(f"raw tray plan already produced an ROI queue: {existing_manifest.parent}")
    return plan, planned_rows, sha256(manifest_path)


def build_queue(
    plan_path: Path, expected_plan_sha256: str, queue_parent: Path, runtime_root: Path,
    reviewed_tray_queue: Path | None = None,
) -> tuple[Path, Path]:
    plan_digest = expected_plan_sha256.lower()
    plan_preview = load_json(plan_path)
    source_tray_manifest_sha: str | None = None
    if plan_preview.get("version") == RAW_TRAY_PLAN_VERSION:
        if reviewed_tray_queue is None:
            raise RuntimeError("raw tray plan requires its reviewed tray queue")
        plan, selected, source_tray_manifest_sha = validate_raw_tray_plan_for_roi(
            plan_path, plan_digest, reviewed_tray_queue, queue_parent,
        )
        plan_mode = "reviewed_raw_tray_same_batch"
    else:
        if reviewed_tray_queue is not None:
            raise RuntimeError("reviewed tray queue is only valid with a raw tray plan")
        if sha256(plan_path) != plan_digest:
            raise RuntimeError("ROI plan receipt SHA does not match the approved digest")
        plan = plan_preview
        selected = validate_plan(plan)
        plan_mode = "selected_roi_round"
        bindings = (
            ("source_dataset", "source_dataset_manifest_sha256", "dataset manifest"),
            ("old_mark_manifest", "old_mark_manifest_sha256", "old MARK manifest"),
            ("protected_manifest", "protected_manifest_sha256", "protected manifest"),
        )
        for path_key, sha_key, label in bindings:
            value = str(plan[path_key])
            verify_bound_file(value, str(plan[sha_key]), label)
        completed_queues = plan.get("completed_roi_queues")
        completed_hashes = plan.get("completed_roi_manifest_sha256s")
        if completed_queues is None:
            completed_queues = [plan["current_roi_queue"]]
            completed_hashes = {
                str(plan["current_roi_queue"]): plan["current_roi_manifest_sha256"],
            }
        if not isinstance(completed_queues, list) or not isinstance(completed_hashes, dict):
            raise RuntimeError("completed ROI queue bindings are invalid")
        for queue_value in completed_queues:
            queue_text = str(queue_value)
            expected_hash = completed_hashes.get(queue_text)
            if not expected_hash:
                raise RuntimeError(f"completed ROI manifest hash is missing: {queue_text}")
            verify_bound_file(
                str(Path(queue_text) / "manifest.json"), str(expected_hash),
                "completed ROI manifest",
            )

    stamp = dt.datetime.now(dt.timezone.utc).strftime("%Y%m%dT%H%M%S%fZ")
    queue_parent = queue_parent.resolve()
    queue_parent.mkdir(parents=True, exist_ok=True)
    queue = queue_parent / f"work-area-active-{stamp}"
    temporary = queue_parent / f".{queue.name}.building"
    if queue.exists() or temporary.exists():
        raise FileExistsError(queue)
    for name in ("images", "labels", "annotations-human", "work-area-human"):
        (temporary / name).mkdir(parents=True, exist_ok=False)

    rows: list[dict[str, Any]] = []
    try:
        for index, planned in enumerate(selected, start=1):
            source_queue = Path(str(planned["queue"]))
            source_manifest_path = source_queue / "manifest.json"
            source_manifest_sha = sha256(source_manifest_path)
            source_manifest = load_json(source_manifest_path)
            stem = str(planned["packed_stem"])
            matches = [row for row in source_manifest.get("rows") or [] if str(row.get("packed_stem")) == stem]
            if len(matches) != 1:
                raise RuntimeError(f"planned source row is missing or duplicated: {stem}")
            source_row = matches[0]
            for key in (
                "source_photo_id", "task_id", "sku_code", "source_sha256",
                "packed_image_sha256", "source_perceptual_hash",
            ):
                if str(source_row.get(key)) != str(planned.get(key)):
                    raise RuntimeError(f"planned row binding drift for {stem}: {key}")

            source = verify_bound_file(
                str(source_row["source_path"]), str(source_row["source_sha256"]), "source image",
            )
            packed_source = source_queue / str(source_row["packed_image"])
            verify_bound_file(
                str(packed_source), str(source_row["packed_image_sha256"]), "packed image",
            )
            tray_source = source_queue / "annotations-human" / f"{stem}.json"
            validate_reviewed_tray_annotation(tray_source, stem)

            packed_target = temporary / "images" / packed_source.name
            tray_target = temporary / "annotations-human" / tray_source.name
            shutil.copy2(packed_source, packed_target)
            shutil.copy2(tray_source, tray_target)
            packed_label = source_row.get("packed_label")
            label_relative = None
            if packed_label:
                label_source = source_queue / str(packed_label)
                if not label_source.is_file():
                    raise RuntimeError(f"packed label is missing: {label_source}")
                label_target = temporary / "labels" / label_source.name
                shutil.copy2(label_source, label_target)
                label_relative = f"labels/{label_target.name}"

            if sha256(packed_target) != source_row["packed_image_sha256"]:
                raise RuntimeError(f"copied packed image drift: {packed_target}")
            row = dict(source_row)
            row.update({
                "queue_index": index,
                "packed_image": f"images/{packed_target.name}",
                "source_queue": str(source_queue),
                "source_queue_manifest_sha256": source_manifest_sha,
                "source_tray_annotation_sha256": sha256(tray_target),
                "roi_plan_receipt": str(plan_path),
                "roi_plan_receipt_sha256": plan_digest,
                "manual_status": "PENDING_WORK_AREA_REVIEW",
            })
            if label_relative:
                row["packed_label"] = label_relative
            rows.append(row)

        manifest = {
            "version": QUEUE_VERSION,
            "created_at": dt.datetime.now(dt.timezone.utc).isoformat(),
            "purpose": "independent_human_work_area_roi",
            "queue_count": len(rows),
            "task_count": len({row["task_id"] for row in rows}),
            "sku_counts": dict(Counter(str(row["sku_code"]) for row in rows)),
            "plan_mode": plan_mode,
            "plan_receipt": str(plan_path),
            "plan_receipt_sha256": plan_digest,
            "source_raw_tray_plan": str(plan_path) if plan_mode == "reviewed_raw_tray_same_batch" else None,
            "source_raw_tray_plan_sha256": plan_digest if plan_mode == "reviewed_raw_tray_same_batch" else None,
            "source_reviewed_tray_queue": (
                str(reviewed_tray_queue.resolve()) if reviewed_tray_queue is not None else None
            ),
            "source_reviewed_tray_manifest_sha256": source_tray_manifest_sha,
            "protected_holdout": str(plan["protected_manifest"]),
            "protected_holdout_manifest_sha256": plan["protected_manifest_sha256"],
            "protected_holdout_included": False,
            "old_mark_included": False,
            "preannotations_used_as_roi_truth": False,
            "annotations_human_read_only": True,
            "labels_read_only": True,
            "manifest_read_only_after_build": True,
            "work_area_human_is_only_annotation_output": True,
            "originals_modified": False,
            "production_reads": 0,
            "production_writes": 0,
            "rows": rows,
        }
        write_json(temporary / "manifest.json", manifest)
        temporary.rename(queue)
    except Exception:
        if temporary.is_dir() and temporary.parent == queue_parent:
            shutil.rmtree(temporary)
        raise

    receipt = {
        "version": "vision-lab-work-area-queue-build-v1",
        "created_at": dt.datetime.now(dt.timezone.utc).isoformat(),
        "queue": str(queue), "queue_manifest": str(queue / "manifest.json"),
        "queue_manifest_sha256": sha256(queue / "manifest.json"),
        "queue_count": len(rows), "task_count": len({row["task_id"] for row in rows}),
        "sku_counts": dict(Counter(str(row["sku_code"]) for row in rows)),
        "plan_mode": plan_mode,
        "plan_receipt": str(plan_path), "plan_receipt_sha256": plan_digest,
        "source_reviewed_tray_queue": (
            str(reviewed_tray_queue.resolve()) if reviewed_tray_queue is not None else None
        ),
        "source_reviewed_tray_manifest_sha256": source_tray_manifest_sha,
        "protected_holdout_included": False, "old_mark_modified": False,
        "originals_modified": False, "production_reads": 0, "production_writes": 0,
        "work_area_reviewed": 0, "deployment_allowed": False,
    }
    receipts = runtime_root.resolve() / "receipts"
    receipts.mkdir(parents=True, exist_ok=True)
    receipt_path = receipts / f"work-area-queue-build-{stamp}.json"
    write_json(receipt_path, receipt)
    return queue, receipt_path


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--plan-receipt", required=True, type=Path)
    parser.add_argument("--plan-sha256", required=True)
    parser.add_argument("--reviewed-tray-queue", type=Path)
    parser.add_argument("--queue-parent", required=True, type=Path)
    parser.add_argument("--runtime-root", required=True, type=Path)
    args = parser.parse_args()
    if len(args.plan_sha256) != 64:
        raise ValueError("plan SHA256 must be a full 64-character digest")
    queue, receipt = build_queue(
        args.plan_receipt.resolve(), args.plan_sha256,
        args.queue_parent.resolve(), args.runtime_root.resolve(),
        args.reviewed_tray_queue.resolve() if args.reviewed_tray_queue else None,
    )
    print(json.dumps({"queue": str(queue), "receipt": str(receipt)}, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
