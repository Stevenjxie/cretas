#!/usr/bin/env python3
"""Build an immutable effective ROI queue by replacing one unjudgeable row."""
from __future__ import annotations

import argparse
import datetime as dt
import json
import shutil
from collections import Counter
from pathlib import Path
from typing import Any

import work_area
import work_area_roi_queue as queue_lib


VERSION = "vision-lab-work-area-effective-queue-v1"


def _load_queue(queue: Path) -> tuple[dict[str, Any], list[dict[str, Any]], str]:
    manifest_path = queue / "manifest.json"
    manifest = queue_lib.load_json(manifest_path)
    rows = manifest.get("rows")
    if (manifest.get("protected_holdout_included") is not False
            or not isinstance(rows, list)
            or len(rows) != int(manifest.get("queue_count", -1))):
        raise RuntimeError(f"unsafe or incomplete work-area queue: {queue}")
    return manifest, rows, queue_lib.sha256(manifest_path)


def _validate_row(queue: Path, row: dict[str, Any]) -> dict[str, Any]:
    stem = str(row.get("packed_stem") or "")
    if not stem:
        raise RuntimeError(f"work-area row has no packed stem: {queue}")
    source = queue_lib.verify_bound_file(
        str(row["source_path"]), str(row["source_sha256"]), "source image",
    )
    packed = queue_lib.verify_bound_file(
        str(queue / str(row["packed_image"])), str(row["packed_image_sha256"]),
        "packed image",
    )
    tray_path = queue / "annotations-human" / f"{stem}.json"
    queue_lib.validate_reviewed_tray_annotation(tray_path, stem)
    tray = queue_lib.load_json(tray_path)
    roi_path = queue / "work-area-human" / f"{stem}.json"
    annotation = work_area.validate_human_annotation(
        queue_lib.load_json(roi_path), expected_photo_id=stem,
    )
    expected = {
        "source_photo_id": str(row.get("source_photo_id") or row.get("photo_id") or ""),
        "source_sha256": str(row.get("source_sha256") or ""),
        "packed_image_sha256": str(row.get("packed_image_sha256") or ""),
    }
    for field, value in expected.items():
        if not value or annotation.get(field) != value:
            raise RuntimeError(f"work-area annotation {field} mismatch: {roi_path}")
    boxes = tray.get("boxes") or []
    counts = (
        {work_area.UNKNOWN_WORK_AREA: len(boxes)}
        if not annotation["judgeable"]
        else work_area.classify_boxes(boxes, annotation["polygon"])
    )
    if annotation.get("tray_scope_counts") != counts:
        raise RuntimeError(f"work-area saved counts drift: {roi_path}")
    return {
        "stem": stem,
        "row": row,
        "source": source,
        "packed": packed,
        "tray": tray_path,
        "roi": roi_path,
        "annotation": annotation,
        "counts": counts,
    }


def _copy_sample(
    sample: dict[str, Any], source_queue: Path, source_manifest_sha: str,
    target: Path, index: int, role: str, replaced_stem: str | None,
) -> dict[str, Any]:
    stem = sample["stem"]
    packed_target = target / "images" / Path(sample["packed"]).name
    tray_target = target / "annotations-human" / f"{stem}.json"
    roi_target = target / "work-area-human" / f"{stem}.json"
    for source, destination in (
        (sample["packed"], packed_target),
        (sample["tray"], tray_target),
        (sample["roi"], roi_target),
    ):
        if destination.exists():
            raise RuntimeError(f"effective queue filename collision: {destination.name}")
        shutil.copy2(source, destination)
    label_relative = None
    packed_label = sample["row"].get("packed_label")
    if packed_label:
        label_source = source_queue / str(packed_label)
        if not label_source.is_file():
            raise RuntimeError(f"packed label is missing: {label_source}")
        label_target = target / "labels" / label_source.name
        if label_target.exists():
            raise RuntimeError(f"effective queue filename collision: {label_target.name}")
        shutil.copy2(label_source, label_target)
        label_relative = f"labels/{label_target.name}"
    row = dict(sample["row"])
    row.update({
        "queue_index": index,
        "packed_image": f"images/{packed_target.name}",
        "source_queue": str(source_queue),
        "source_queue_manifest_sha256": source_manifest_sha,
        "source_tray_annotation_sha256": queue_lib.sha256(tray_target),
        "source_work_area_annotation_sha256": queue_lib.sha256(roi_target),
        "effective_queue_role": role,
        "replaces_packed_stem": replaced_stem,
        "manual_status": "HUMAN_WORK_AREA_REVIEWED",
    })
    if label_relative:
        row["packed_label"] = label_relative
    return row


def build_effective_queue(
    base_queue: Path, replacement_queue: Path, replace_stem: str,
    queue_parent: Path, runtime_root: Path,
) -> tuple[Path, Path]:
    base_queue, replacement_queue = base_queue.resolve(), replacement_queue.resolve()
    base_manifest, base_rows, base_sha = _load_queue(base_queue)
    replacement_manifest, replacement_rows, replacement_sha = _load_queue(replacement_queue)
    targets = [row for row in base_rows if str(row.get("packed_stem")) == replace_stem]
    if len(targets) != 1:
        raise RuntimeError(f"replacement target is missing or duplicated: {replace_stem}")
    if len(replacement_rows) != 1:
        raise RuntimeError("replacement queue must contain exactly one reviewed row")

    target = _validate_row(base_queue, targets[0])
    if target["annotation"]["judgeable"] is not False:
        raise RuntimeError("replacement target must be an explicit human unjudgeable record")
    retained = [
        _validate_row(base_queue, row) for row in base_rows
        if str(row.get("packed_stem")) != replace_stem
    ]
    if any(sample["annotation"]["judgeable"] is not True for sample in retained):
        raise RuntimeError("base queue contains another non-judgeable ROI row")
    replacement = _validate_row(replacement_queue, replacement_rows[0])
    if replacement["annotation"]["judgeable"] is not True:
        raise RuntimeError("replacement ROI must be reviewed and judgeable")
    if str(target["row"].get("sku_code")) != str(replacement["row"].get("sku_code")):
        raise RuntimeError("replacement ROI must use the same SKU as the unjudgeable row")

    samples = retained + [replacement]
    for key in ("source_photo_id", "task_id", "source_sha256", "packed_stem"):
        values = [
            str(sample["stem"] if key == "packed_stem" else sample["row"].get(key) or "")
            for sample in samples
        ]
        if any(not value for value in values) or len(set(values)) != len(values):
            raise RuntimeError(f"effective queue contains missing or duplicate {key}")

    stamp = dt.datetime.now(dt.timezone.utc).strftime("%Y%m%dT%H%M%S%fZ")
    queue_parent = queue_parent.resolve()
    queue_parent.mkdir(parents=True, exist_ok=True)
    queue = queue_parent / f"work-area-effective-{stamp}"
    temporary = queue_parent / f".{queue.name}.building"
    if queue.exists() or temporary.exists():
        raise FileExistsError(queue)
    for name in ("images", "labels", "annotations-human", "work-area-human"):
        (temporary / name).mkdir(parents=True, exist_ok=False)
    try:
        rows = []
        for index, sample in enumerate(retained, start=1):
            rows.append(_copy_sample(
                sample, base_queue, base_sha, temporary, index, "retained", None,
            ))
        rows.append(_copy_sample(
            replacement, replacement_queue, replacement_sha, temporary, len(rows) + 1,
            "replacement", replace_stem,
        ))
        manifest = {
            "version": VERSION,
            "created_at": dt.datetime.now(dt.timezone.utc).isoformat(),
            "purpose": "auditable_judgeable_roi_set_with_explicit_replacement",
            "queue_count": len(rows),
            "task_count": len({str(row["task_id"]) for row in rows}),
            "sku_counts": dict(Counter(str(row["sku_code"]) for row in rows)),
            "base_queue": str(base_queue),
            "base_queue_manifest_sha256": base_sha,
            "replacement_queue": str(replacement_queue),
            "replacement_queue_manifest_sha256": replacement_sha,
            "replaced_packed_stem": replace_stem,
            "replaced_annotation": str(target["roi"]),
            "replaced_annotation_sha256": queue_lib.sha256(target["roi"]),
            "replaced_annotation_judgeable": False,
            "replaced_record_retained_at_source": True,
            "protected_holdout_included": False,
            "old_mark_included": False,
            "preannotations_used_as_roi_truth": False,
            "manifest_read_only_after_build": True,
            "originals_modified": False,
            "production_reads": 0,
            "production_writes": 0,
            "rows": rows,
        }
        queue_lib.write_json(temporary / "manifest.json", manifest)
        temporary.rename(queue)
    except Exception:
        if temporary.is_dir() and temporary.parent == queue_parent:
            shutil.rmtree(temporary)
        raise

    receipt = {
        "version": "vision-lab-work-area-effective-queue-build-v1",
        "created_at": dt.datetime.now(dt.timezone.utc).isoformat(),
        "queue": str(queue),
        "queue_manifest_sha256": queue_lib.sha256(queue / "manifest.json"),
        "queue_count": len(rows),
        "base_queue": str(base_queue),
        "base_queue_manifest_sha256": base_sha,
        "replacement_queue": str(replacement_queue),
        "replacement_queue_manifest_sha256": replacement_sha,
        "replaced_packed_stem": replace_stem,
        "replaced_annotation_sha256": queue_lib.sha256(target["roi"]),
        "replaced_record_retained_at_source": True,
        "protected_holdout_included": False,
        "originals_modified": False,
        "production_writes": 0,
        "deployment_allowed": False,
    }
    receipts = runtime_root.resolve() / "receipts"
    receipts.mkdir(parents=True, exist_ok=True)
    receipt_path = receipts / f"work-area-effective-queue-{stamp}.json"
    queue_lib.write_json(receipt_path, receipt)
    return queue, receipt_path


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-queue", required=True, type=Path)
    parser.add_argument("--replacement-queue", required=True, type=Path)
    parser.add_argument("--replace-stem", required=True)
    parser.add_argument("--queue-parent", required=True, type=Path)
    parser.add_argument("--runtime-root", required=True, type=Path)
    args = parser.parse_args()
    queue, receipt = build_effective_queue(
        args.base_queue, args.replacement_queue, args.replace_stem,
        args.queue_parent, args.runtime_root,
    )
    print(json.dumps({"queue": str(queue), "receipt": str(receipt)}, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
