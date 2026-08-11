#!/usr/bin/env python3
"""Plan a diverse, protected next round of human work-area ROI annotations.

The command is intentionally plan-only: it writes an auditable receipt but does
not create a queue, copy an image, alter MARK files, or touch protected records.
"""
from __future__ import annotations

import argparse
import datetime as dt
import hashlib
import json
from collections import Counter
from pathlib import Path
from typing import Any

from PIL import Image, ImageOps

import work_area


PROTECTED_PHASH_DISTANCE = 10
ROUND_PHASH_DISTANCE = 10


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


def image_phash(path: Path) -> str:
    import imagehash

    with Image.open(path) as opened:
        return str(imagehash.phash(ImageOps.exif_transpose(opened), hash_size=16))


def phash_distance(left: str, right: str) -> int:
    if len(left) != 64 or len(right) != 64:
        raise ValueError("expected 256-bit perceptual hashes")
    return (int(left, 16) ^ int(right, 16)).bit_count()


def allocate_sku_quotas(rows: list[dict[str, Any]], target_count: int) -> dict[str, int]:
    availability = Counter(str(row["sku_code"]) for row in rows)
    quotas = {sku: 0 for sku in sorted(availability)}
    for _ in range(target_count):
        choices = [sku for sku in quotas if quotas[sku] < availability[sku]]
        if not choices:
            raise RuntimeError(f"only {sum(availability.values())} eligible rows for {target_count}")
        sku = min(choices, key=lambda value: (quotas[value], value))
        quotas[sku] += 1
    return quotas


def select_diverse(
    rows: list[dict[str, Any]], current_hashes: list[str], target_count: int,
) -> tuple[list[dict[str, Any]], dict[str, int]]:
    quotas = allocate_sku_quotas(rows, target_count)
    selected: list[dict[str, Any]] = []
    sku_counts: Counter[str] = Counter()
    queue_counts: Counter[str] = Counter()
    used_tasks: set[str] = set()
    used_photos: set[str] = set()
    while len(selected) < target_count:
        prior_hashes = current_hashes + [row["source_perceptual_hash"] for row in selected]
        choices = []
        for row in rows:
            sku = str(row["sku_code"])
            if sku_counts[sku] >= quotas[sku]:
                continue
            if row["task_id"] in used_tasks or row["source_photo_id"] in used_photos:
                continue
            nearest = min(
                (phash_distance(row["source_perceptual_hash"], value) for value in prior_hashes),
                default=256,
            )
            if nearest <= ROUND_PHASH_DISTANCE:
                continue
            choices.append((row, nearest))
        if not choices:
            raise RuntimeError("unable to satisfy task, SKU, and pHash diversity constraints")
        row, _ = max(
            choices,
            key=lambda item: (
                queue_counts[item[0]["queue_name"]] == 0,
                -queue_counts[item[0]["queue_name"]],
                item[1],
                float(item[0].get("selection_score") or 0),
                str(item[0]["source_photo_id"]),
            ),
        )
        selected.append(row)
        sku_counts[str(row["sku_code"])] += 1
        queue_counts[str(row["queue_name"])] += 1
        used_tasks.add(str(row["task_id"]))
        used_photos.add(str(row["source_photo_id"]))
    return selected, quotas


def nearest(value: str, rows: list[tuple[str, str]]) -> dict[str, Any] | None:
    if not rows:
        return None
    identity, distance = min(
        ((identity, phash_distance(value, other)) for identity, other in rows),
        key=lambda item: item[1],
    )
    return {"photo_id": identity, "distance": distance}


def build_plan(
    dataset_manifest_path: Path, current_queue: Path, old_mark_path: Path,
    protected_path: Path, target_count: int,
) -> dict[str, Any]:
    dataset = load_json(dataset_manifest_path)
    current_manifest_path = current_queue / "manifest.json"
    current = load_json(current_manifest_path)
    old_mark = load_json(old_mark_path)
    protected = load_json(protected_path)

    current_rows = current.get("rows") or []
    if len(current_rows) != int(current.get("queue_count", -1)):
        raise RuntimeError("current ROI queue manifest is incomplete")
    current_ids = {str(row["source_photo_id"]) for row in current_rows}
    current_tasks = {str(row["task_id"]) for row in current_rows}
    current_phashes = [
        (str(row["source_photo_id"]), str(row["source_perceptual_hash"]))
        for row in current_rows
    ]
    for row in current_rows:
        stem = str(row["packed_stem"])
        annotation = work_area.validate_human_annotation(
            load_json(current_queue / "work-area-human" / f"{stem}.json"),
            expected_photo_id=stem,
        )
        if not annotation["judgeable"] or annotation["source"] != "human":
            raise RuntimeError(f"current ROI is not reviewed human truth: {stem}")

    old_rows = old_mark.get("rows") or []
    old_ids = {str(row["source_photo_id"]) for row in old_rows}
    old_tasks = {str(row["source_task_id"]) for row in old_rows}

    protected_rows = protected.get("records") or []
    protected_ids, protected_tasks, protected_shas = set(), set(), set()
    protected_phashes: list[tuple[str, str]] = []
    for row in protected_rows:
        image = Path(str(row["image"]))
        if not image.is_file() or sha256(image) != row["image_sha256"]:
            raise RuntimeError(f"protected image hash drift: {image}")
        protected_ids.add(str(row["photo_id"]))
        protected_tasks.add(str(row["task_id"]))
        protected_shas.add(str(row["image_sha256"]))
        protected_phashes.append((str(row["photo_id"]), image_phash(image)))

    rows: list[dict[str, Any]] = []
    for queue_entry in dataset.get("queue_manifests") or []:
        queue = Path(str(queue_entry["queue"]))
        manifest_path = queue / "manifest.json"
        if sha256(manifest_path) != queue_entry["manifest_sha256"]:
            raise RuntimeError(f"dataset queue manifest drift: {manifest_path}")
        for original in load_json(manifest_path).get("rows") or []:
            row = dict(original)
            row["queue"] = str(queue)
            row["queue_name"] = queue.name
            rows.append(row)

    exclusions: Counter[str] = Counter()
    eligible: list[dict[str, Any]] = []
    for row in rows:
        reasons: set[str] = set()
        if row["source_photo_id"] in current_ids or row["task_id"] in current_tasks:
            reasons.add("current_roi_photo_or_task")
        if row["source_photo_id"] in old_ids or row["task_id"] in old_tasks:
            reasons.add("old_label_mark_source_photo_or_task")
        if (row["source_photo_id"] in protected_ids or row["task_id"] in protected_tasks
                or row["source_sha256"] in protected_shas):
            reasons.add("protected_exact_id_task_or_sha")
        nearest_protected = nearest(row["source_perceptual_hash"], protected_phashes)
        if nearest_protected and nearest_protected["distance"] <= PROTECTED_PHASH_DISTANCE:
            reasons.add("protected_phash_hamming_le_10")
        if reasons:
            for reason in reasons:
                exclusions[reason] += 1
            continue
        eligible.append(row)

    selected, quotas = select_diverse(
        eligible, [value for _, value in current_phashes], target_count,
    )
    selected_hashes = [
        (str(row["source_photo_id"]), str(row["source_perceptual_hash"]))
        for row in selected
    ]
    selected_rows = []
    for row in selected:
        queue = Path(row["queue"])
        stem = str(row["packed_stem"])
        source, packed = Path(str(row["source_path"])), queue / str(row["packed_image"])
        tray_path = queue / "annotations-human" / f"{stem}.json"
        tray = load_json(tray_path)
        if tray.get("reviewed") is not True or tray.get("source") != "human":
            raise RuntimeError(f"candidate tray truth is not human-reviewed: {tray_path}")
        if sha256(source) != row["source_sha256"] or sha256(packed) != row["packed_image_sha256"]:
            raise RuntimeError(f"candidate image hash drift: {stem}")
        other_selected = [item for item in selected_hashes if item[0] != row["source_photo_id"]]
        selected_rows.append({
            "source_photo_id": row["source_photo_id"], "task_id": row["task_id"],
            "sku_code": row["sku_code"], "queue": row["queue"], "packed_stem": stem,
            "source_path": str(source), "source_sha256": row["source_sha256"],
            "packed_image_sha256": row["packed_image_sha256"],
            "source_perceptual_hash": row["source_perceptual_hash"],
            "nearest_protected_phash": nearest(row["source_perceptual_hash"], protected_phashes),
            "nearest_current_roi_phash": nearest(row["source_perceptual_hash"], current_phashes),
            "nearest_other_selected_phash": nearest(row["source_perceptual_hash"], other_selected),
            "protected_exact_exclusion_passed": True,
            "protected_phash_exclusion_passed": True,
            "old_mark_source_exclusion_passed": True,
        })

    return {
        "version": "vision-lab-work-area-roi-plan-v1",
        "created_at": dt.datetime.now(dt.timezone.utc).isoformat(),
        "plan_only": True, "queue_created": False, "mark_created": False,
        "target_count": target_count, "selected_count": len(selected_rows),
        "unique_task_count": len({row["task_id"] for row in selected_rows}),
        "sku_quotas": quotas,
        "queue_counts": dict(Counter(Path(row["queue"]).name for row in selected_rows)),
        "source_dataset": str(dataset_manifest_path),
        "source_dataset_manifest_sha256": sha256(dataset_manifest_path),
        "current_roi_queue": str(current_queue),
        "current_roi_manifest_sha256": sha256(current_manifest_path),
        "old_mark_manifest": str(old_mark_path),
        "old_mark_manifest_sha256": sha256(old_mark_path),
        "protected_manifest": str(protected_path),
        "protected_manifest_sha256": sha256(protected_path),
        "pool": {
            "dataset_rows": len(rows), "eligible_rows": len(eligible),
            "eligible_tasks": len({row["task_id"] for row in eligible}),
            "exclusions": dict(exclusions),
        },
        "rules": {
            "one_photo_per_task": True, "balanced_sku": True,
            "current_roi_excluded_by_photo_and_task": True,
            "old_label_mark_excluded_by_source_photo_and_task": True,
            "protected_excluded_by_id_task_sha": True,
            "protected_phash_hamming_max": PROTECTED_PHASH_DISTANCE,
            "current_and_round_phash_hamming_min_exclusive": ROUND_PHASH_DISTANCE,
        },
        "selected": selected_rows,
        "protected_holdout_modified": False, "old_mark_modified": False,
        "originals_modified": False, "production_reads": 0, "production_writes": 0,
    }


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--dataset-manifest", required=True, type=Path)
    parser.add_argument("--current-queue", required=True, type=Path)
    parser.add_argument("--old-mark-manifest", required=True, type=Path)
    parser.add_argument("--protected-holdout", required=True, type=Path)
    parser.add_argument("--runtime-root", required=True, type=Path)
    parser.add_argument("--count", type=int, default=24)
    args = parser.parse_args()
    if args.count <= 0:
        raise ValueError("count must be positive")
    plan = build_plan(
        args.dataset_manifest.resolve(), args.current_queue.resolve(),
        args.old_mark_manifest.resolve(), args.protected_holdout.resolve(), args.count,
    )
    receipts = args.runtime_root.resolve() / "receipts"
    receipts.mkdir(parents=True, exist_ok=True)
    stamp = dt.datetime.now(dt.timezone.utc).strftime("%Y%m%dT%H%M%S%fZ")
    path = receipts / f"work-area-roi-plan-{stamp}.json"
    path.write_text(json.dumps(plan, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps({"receipt": str(path), **plan}, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
