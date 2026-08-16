#!/usr/bin/env python3
"""Build an append-only tray dataset from visually scoped real miss patches.

The protected query is never copied.  Every mined candidate must be explicitly
classified.  Only a judgeable, single full-tray boundary may become a train-only
crop; lower-layer, grouped, misaligned, or image-edge-truncated boxes are kept
out of tray truth.
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

from PIL import Image, ImageOps


MINIMUM_HOLDOUT_PHASH_DISTANCE = 11
EXCLUSION_REASONS = {
    "multi_tray_group",
    "unjudgeable_or_misaligned",
    "image_edge_truncated",
}


def load_json(path: Path) -> dict[str, Any]:
    value = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(value, dict):
        raise ValueError(f"expected JSON object: {path}")
    return value


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def stable_json(value: Any) -> bytes:
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode("utf-8")


def dataset_digest(root: Path) -> str:
    digest = hashlib.sha256()
    for path in sorted(root.rglob("*")):
        if path.is_file() and path.name != "manifest.json" and path.suffix != ".cache":
            digest.update(path.relative_to(root).as_posix().encode())
            digest.update(b"\0")
            digest.update(bytes.fromhex(sha256_file(path)))
    return digest.hexdigest()


def validate_box(box: Sequence[float]) -> list[float]:
    if len(box) != 4:
        raise ValueError(f"invalid box length: {box}")
    values = [float(value) for value in box]
    if any(not math.isfinite(value) for value in values):
        raise ValueError(f"non-finite box: {box}")
    x0, y0, x1, y1 = values
    if not (0.0 <= x0 < x1 <= 1.0 and 0.0 <= y0 < y1 <= 1.0):
        raise ValueError(f"box outside normalised image: {box}")
    return values


def boxes_equal(left: Sequence[float], right: Sequence[float], tolerance: float = 1e-6) -> bool:
    return len(left) == len(right) == 4 and all(
        abs(float(a) - float(b)) <= tolerance for a, b in zip(left, right)
    )


def yolo_line(box: Sequence[float]) -> str:
    x0, y0, x1, y1 = validate_box(box)
    return f"0 {(x0+x1)/2:.6f} {(y0+y1)/2:.6f} {x1-x0:.6f} {y1-y0:.6f}"


def crop_with_context(
    image: Image.Image, box: Sequence[float], context_ratio: float,
) -> tuple[Image.Image, list[float], list[int]]:
    if not (0.0 <= context_ratio <= 1.0):
        raise ValueError("context ratio must be between 0 and 1")
    x0n, y0n, x1n, y1n = validate_box(box)
    width, height = image.size
    x0, y0, x1, y1 = x0n * width, y0n * height, x1n * width, y1n * height
    pad_x, pad_y = (x1 - x0) * context_ratio, (y1 - y0) * context_ratio
    rect = [
        max(0, math.floor(x0 - pad_x)),
        max(0, math.floor(y0 - pad_y)),
        min(width, math.ceil(x1 + pad_x)),
        min(height, math.ceil(y1 + pad_y)),
    ]
    crop_width, crop_height = rect[2] - rect[0], rect[3] - rect[1]
    if crop_width <= 1 or crop_height <= 1:
        raise ValueError(f"empty crop for box: {box}")
    target = validate_box([
        max(0.0, (x0 - rect[0]) / crop_width),
        max(0.0, (y0 - rect[1]) / crop_height),
        min(1.0, (x1 - rect[0]) / crop_width),
        min(1.0, (y1 - rect[1]) / crop_height),
    ])
    return image.crop(tuple(rect)), target, rect


def parse_exclusions(values: Sequence[str]) -> dict[str, str]:
    result: dict[str, str] = {}
    for value in values:
        photo_id, separator, reason = value.partition("=")
        if not separator or not photo_id or reason not in EXCLUSION_REASONS:
            raise ValueError(
                f"invalid exclusion {value!r}; expected PHOTO_ID="
                f"{'|'.join(sorted(EXCLUSION_REASONS))}"
            )
        if photo_id in result:
            raise ValueError(f"duplicate excluded photo: {photo_id}")
        result[photo_id] = reason
    return result


def classify_candidates(
    selected: Sequence[dict[str, Any]], included: Sequence[str], excluded: dict[str, str],
) -> tuple[list[dict[str, Any]], list[dict[str, Any]]]:
    by_photo = {str(row["photo_id"]): row for row in selected}
    if len(by_photo) != len(selected):
        raise ValueError("mining receipt contains duplicate selected photos")
    included_set = set(included)
    if len(included_set) != len(included):
        raise ValueError("duplicate included photo")
    overlap = included_set & set(excluded)
    if overlap:
        raise ValueError(f"photos are both included and excluded: {sorted(overlap)}")
    classified = included_set | set(excluded)
    missing = set(by_photo) - classified
    unknown = classified - set(by_photo)
    if missing or unknown:
        raise ValueError(f"candidate classification incomplete: missing={sorted(missing)}, unknown={sorted(unknown)}")
    accepted = [
        by_photo[photo_id] | {
            "scope_status": "JUDGEABLE_FULL_TRAY",
            "scope_reason": "single complete outer tray boundary is visible",
        }
        for photo_id in included
    ]
    rejected = [
        by_photo[photo_id] | {
            "scope_status": "TRAY_BOX_EXCLUDED",
            "scope_reason": reason,
            "label_presence_truth_retained_separately": True,
        }
        for photo_id, reason in excluded.items()
    ]
    if len({str(row["task_id"]) for row in accepted}) < 2:
        raise ValueError("fewer than two independent judgeable tray tasks")
    return accepted, rejected


def verify_train_only_source(row: dict[str, Any]) -> None:
    queue = Path(str(row["queue"]))
    manifest = load_json(queue / "manifest.json")
    matches = [
        source for source in manifest.get("rows") or []
        if str(source.get("photo_id")) == str(row["photo_id"])
    ]
    if len(matches) != 1 or matches[0].get("train_only") is not True:
        raise RuntimeError(f"source is not uniquely train-only: {row['photo_id']}")
    source = matches[0]
    if str(source.get("source_sha256")) != str(row["source_sha256"]):
        raise RuntimeError(f"source hash binding drift: {row['photo_id']}")
    if int(row.get("nearest_holdout_phash_distance", -1)) < MINIMUM_HOLDOUT_PHASH_DISTANCE:
        raise RuntimeError(f"protected near-overlap blocks patch: {row['photo_id']}")


def build_dataset(
    prior_manifest_path: Path,
    mining_receipt_path: Path,
    output_root: Path,
    included: Sequence[str],
    excluded: dict[str, str],
    context_ratio: float,
) -> tuple[Path, dict[str, Any]]:
    prior_manifest_path = prior_manifest_path.resolve()
    mining_receipt_path = mining_receipt_path.resolve()
    prior = load_json(prior_manifest_path)
    mining = load_json(mining_receipt_path)
    prior_root = prior_manifest_path.parent
    if prior.get("protected_holdout_included") is not False:
        raise RuntimeError("prior dataset does not prove protected holdout exclusion")
    if prior.get("dataset_sha256") != dataset_digest(prior_root):
        raise RuntimeError("prior dataset content drift")
    decision = mining.get("decision") or {}
    if decision.get("visual_scope_review_required") is not True or decision.get("training_allowed") is not False:
        raise RuntimeError("mining receipt did not fail closed before visual review")
    query = mining.get("query") or {}
    if query.get("protected_target_used_for_training") is not False:
        raise RuntimeError("protected query use contract drift")
    selected = (mining.get("results") or {}).get("selected") or []
    accepted, rejected = classify_candidates(selected, included, excluded)
    identity = {
        "version": "label-qc-tray-patch-dataset-v1",
        "prior_dataset_sha256": prior["dataset_sha256"],
        "mining_receipt_sha256": sha256_file(mining_receipt_path),
        "accepted": [
            {
                "photo_id": row["photo_id"],
                "task_id": row["task_id"],
                "human_box_index": row["human_box_index"],
                "box": row["box"],
            }
            for row in accepted
        ],
        "rejected": [
            {"photo_id": row["photo_id"], "reason": row["scope_reason"]}
            for row in rejected
        ],
        "context_ratio": context_ratio,
    }
    dataset_id = "tray-patch-" + hashlib.sha256(stable_json(identity)).hexdigest()[:12]
    output = output_root.resolve() / dataset_id
    if output.exists():
        existing = load_json(output / "manifest.json")
        if existing.get("identity") != identity or existing.get("dataset_sha256") != dataset_digest(output):
            raise RuntimeError(f"existing patch dataset identity/content drift: {output}")
        return output, existing
    temporary = output.with_name(output.name + f".tmp.{os.getpid()}")
    if temporary.exists():
        raise RuntimeError(f"stale temporary dataset directory: {temporary}")
    output_root.mkdir(parents=True, exist_ok=True)
    shutil.copytree(prior_root, temporary)
    provenance_path = temporary / "provenance.json"
    provenance = load_json(provenance_path)
    provenance_rows = provenance.get("rows") or []
    counts = dict(prior.get("counts") or {})
    derived_rows: list[dict[str, Any]] = []
    for row in accepted:
        verify_train_only_source(row)
        image_path = Path(str(row["packed_image"]))
        annotation_path = Path(str(row["annotation"]))
        if sha256_file(image_path) != str(row["packed_image_sha256"]):
            raise RuntimeError(f"packed image drift: {image_path}")
        if sha256_file(annotation_path) != str(row["annotation_sha256"]):
            raise RuntimeError(f"human annotation drift: {annotation_path}")
        annotation = load_json(annotation_path)
        if annotation.get("reviewed") is not True or annotation.get("source") != "human":
            raise RuntimeError(f"annotation is not reviewed human truth: {annotation_path}")
        boxes = annotation.get("boxes") or []
        box_index = int(row["human_box_index"])
        if box_index < 0 or box_index >= len(boxes) or not boxes_equal(boxes[box_index], row["box"]):
            raise RuntimeError(f"selected human box binding drift: {row['photo_id']}")
        with Image.open(image_path) as opened:
            image = ImageOps.exif_transpose(opened).convert("RGB")
            crop, crop_box, crop_rect = crop_with_context(image, row["box"], context_ratio)
        stem = f"analogue_{str(row['photo_id'])[:8]}_{box_index:03d}"
        image_out = temporary / "images" / "train" / f"{stem}.jpg"
        label_out = temporary / "labels" / "train" / f"{stem}.txt"
        annotation_out = temporary / "annotations-source" / f"{stem}.json"
        if any(path.exists() for path in (image_out, label_out, annotation_out)):
            raise RuntimeError(f"derived patch name collision: {stem}")
        crop.save(image_out, format="JPEG", quality=95, optimize=True)
        label_out.write_text(yolo_line(crop_box) + "\n", encoding="utf-8")
        source_record = {
            "version": "label-qc-tray-derived-patch-v1",
            "reviewed": True,
            "source": "human-derived",
            "source_annotation": str(annotation_path),
            "source_annotation_sha256": row["annotation_sha256"],
            "source_photo_id": row["photo_id"],
            "task_id": row["task_id"],
            "human_box_index": box_index,
            "source_box_normalised_xyxy": row["box"],
            "crop_rect_pixels_xyxy": crop_rect,
            "box_normalised_xyxy": crop_box,
            "scope_status": row["scope_status"],
            "train_only": True,
            "protected_target_used": False,
        }
        annotation_out.write_text(
            json.dumps(source_record, ensure_ascii=False, indent=2) + "\n", encoding="utf-8",
        )
        patch_record = {
            "stem": stem,
            "split": "train",
            "task_id": row["task_id"],
            "source_photo_id": row["photo_id"],
            "source_sha256": row["source_sha256"],
            "source_packed_image": str(image_path),
            "source_packed_image_sha256": row["packed_image_sha256"],
            "source_annotation": str(annotation_path),
            "source_annotation_sha256": row["annotation_sha256"],
            "human_box_index": box_index,
            "box_count": 1,
            "human_truth": True,
            "derived_crop": True,
            "train_only": True,
            "scope_status": row["scope_status"],
            "nearest_holdout_phash_distance": row["nearest_holdout_phash_distance"],
        }
        provenance_rows.append(patch_record)
        derived_rows.append(patch_record | {
            "image": str(output / "images" / "train" / image_out.name),
            "label": str(output / "labels" / "train" / label_out.name),
            "derived_annotation": str(output / "annotations-source" / annotation_out.name),
            "derived_image_sha256": sha256_file(image_out),
            "derived_label_sha256": sha256_file(label_out),
        })
        counts["train_images"] = int(counts.get("train_images", 0)) + 1
        counts["train_boxes"] = int(counts.get("train_boxes", 0)) + 1
    provenance["rows"] = provenance_rows
    provenance["derived_patch_policy"] = {
        "only_judgeable_full_trays": True,
        "lower_occluded_trays_are_not_tray_truth": True,
        "visible_lower_labels_remain_eligible_for_separate_label_truth": True,
    }
    provenance_path.write_text(
        json.dumps(provenance, ensure_ascii=False, indent=2) + "\n", encoding="utf-8",
    )
    (temporary / "data.yaml").write_text(
        f"path: {output.as_posix()}\ntrain: images/train\nval: images/val\nnames:\n  0: tray\n",
        encoding="utf-8",
    )
    manifest = {
        "version": "label-qc-tray-patch-dataset-v1",
        "dataset_id": dataset_id,
        "identity": identity,
        "base_dataset": str(prior_root),
        "base_dataset_id": prior.get("dataset_id"),
        "base_dataset_sha256": prior.get("dataset_sha256"),
        "mining_receipt": str(mining_receipt_path),
        "mining_receipt_sha256": sha256_file(mining_receipt_path),
        "counts": counts,
        "base_human_reviewed_images": int(prior.get("human_reviewed_images", 0)),
        "base_human_boxes": int(prior.get("human_boxes", 0)),
        "derived_train_images": len(derived_rows),
        "derived_train_boxes": len(derived_rows),
        "independent_derived_tasks": len({str(row["task_id"]) for row in accepted}),
        "accepted_scope_rows": derived_rows,
        "excluded_scope_rows": [
            {
                "photo_id": row["photo_id"],
                "task_id": row["task_id"],
                "human_box_index": row["human_box_index"],
                "status": row["scope_status"],
                "reason": row["scope_reason"],
                "label_presence_truth_retained_separately": True,
            }
            for row in rejected
        ],
        "scope_policy": {
            "tray_truth": "only a single tray with a judgeable complete outer boundary",
            "occluded_lower_tray": "exclude from tray truth",
            "visible_lower_white_or_color_label": "retain only in separate label-side-view truth",
            "absence_in_unseen_region": "must not be inferred",
        },
        "crop_context_ratio": context_ratio,
        "all_derived_rows_train_only": True,
        "validation_unchanged_from_base_dataset": True,
        "protected_holdout": prior.get("protected_holdout"),
        "protected_holdout_included": False,
        "protected_target_used_for_training": False,
        "minimum_holdout_phash_distance": min(
            int(row["nearest_holdout_phash_distance"]) for row in accepted
        ),
        "visual_scope_review_complete": True,
        "training_allowed": True,
        "promotion_allowed": False,
        "gpu_rental_required": False,
        "production_writes": 0,
        "originals_modified": 0,
        "data_yaml": str(output / "data.yaml"),
    }
    manifest["dataset_sha256"] = dataset_digest(temporary)
    (temporary / "manifest.json").write_text(
        json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8",
    )
    temporary.replace(output)
    return output, manifest


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--prior-dataset-manifest", required=True, type=Path)
    parser.add_argument("--mining-receipt", required=True, type=Path)
    parser.add_argument("--output-root", required=True, type=Path)
    parser.add_argument("--include-photo", action="append", default=[])
    parser.add_argument("--exclude-photo", action="append", default=[])
    parser.add_argument("--context-ratio", type=float, default=0.08)
    return parser


def main() -> None:
    args = build_parser().parse_args()
    output, manifest = build_dataset(
        args.prior_dataset_manifest,
        args.mining_receipt,
        args.output_root,
        args.include_photo,
        parse_exclusions(args.exclude_photo),
        args.context_ratio,
    )
    print(json.dumps({
        "dataset": str(output),
        "dataset_id": manifest["dataset_id"],
        "dataset_sha256": manifest["dataset_sha256"],
        "accepted_judgeable_trays": manifest["derived_train_boxes"],
        "excluded_unjudgeable_trays": len(manifest["excluded_scope_rows"]),
        "independent_tasks": manifest["independent_derived_tasks"],
        "training_allowed": manifest["training_allowed"],
        "gpu_rental_required": manifest["gpu_rental_required"],
        "production_writes": 0,
        "originals_modified": 0,
    }, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
