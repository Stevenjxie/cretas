#!/usr/bin/env python3
"""Plan new SKU-balanced tray reviews needed before additional work-area ROI truth."""
from __future__ import annotations

import argparse
import datetime as dt
import json
import sqlite3
from collections import Counter, defaultdict
from pathlib import Path
from typing import Any

import mine_tray_queue as tray
import work_area_roi_plan as roi_plan


VERSION = "vision-lab-work-area-raw-tray-plan-v1"


def load_manifest_rows(paths: list[Path]) -> tuple[list[dict[str, Any]], dict[str, str]]:
    rows: list[dict[str, Any]] = []
    hashes: dict[str, str] = {}
    for path in paths:
        resolved = path.resolve()
        payload = roi_plan.load_json(resolved)
        hashes[str(resolved)] = roi_plan.sha256(resolved)
        rows.extend(payload.get("rows") or payload.get("records") or [])
    return rows, hashes


def build_plan(
    database: Path, existing_manifests: list[Path], protected_path: Path,
    sku_codes: list[str], count_per_sku: int,
) -> dict[str, Any]:
    if not existing_manifests or len(set(sku_codes)) != len(sku_codes):
        raise ValueError("existing manifests and unique SKU codes are required")
    existing_rows, manifest_hashes = load_manifest_rows(existing_manifests)
    used_photos = {
        str(row[key]) for row in existing_rows for key in ("source_photo_id", "photo_id")
        if row.get(key)
    }
    used_tasks = {
        str(row[key]) for row in existing_rows for key in ("source_task_id", "task_id")
        if row.get(key)
    }
    used_shas = {
        str(row[key]) for row in existing_rows for key in ("source_sha256", "image_sha256")
        if row.get(key)
    }
    current_phashes = [
        (str(row.get("source_photo_id") or row.get("photo_id")), str(row["source_perceptual_hash"]))
        for row in existing_rows if row.get("source_perceptual_hash")
    ]
    protected = tray.protected_evidence(protected_path)
    exclusions: Counter[str] = Counter()
    by_task: dict[tuple[str, str], list[dict[str, Any]]] = defaultdict(list)
    connection = sqlite3.connect(f"file:{database.resolve()}?mode=ro", uri=True)
    connection.row_factory = sqlite3.Row
    try:
        placeholders = ",".join("?" for _ in sku_codes)
        query = f"SELECT * FROM photos WHERE sku_code IN ({placeholders}) ORDER BY reviewed_at DESC, photo_id"
        for raw in connection.execute(query, sku_codes):
            row = dict(raw)
            photo_id, task_id = str(row["photo_id"]), str(row["task_id"])
            digest, image = str(row["sha256"]), Path(str(row["local_path"]))
            reasons: set[str] = set()
            if photo_id in used_photos or task_id in used_tasks or digest in used_shas:
                reasons.add("existing_photo_task_or_sha")
            if photo_id in protected["ids"] or digest in protected["hashes"]:
                reasons.add("protected_photo_or_sha")
            if not image.is_file() or roi_plan.sha256(image) != digest:
                reasons.add("missing_or_hash_drift")
            if reasons:
                exclusions.update(reasons)
                continue
            image_phash = roi_plan.image_phash(image)
            nearest_protected = roi_plan.nearest(image_phash, protected["phashes"])
            if nearest_protected and nearest_protected["distance"] <= roi_plan.PROTECTED_PHASH_DISTANCE:
                exclusions["protected_phash_hamming_le_10"] += 1
                continue
            row.update({
                "source_perceptual_hash": image_phash,
                "nearest_protected_phash": nearest_protected,
                "nearest_current_phash": roi_plan.nearest(image_phash, current_phashes),
            })
            by_task[(str(row["sku_code"]), task_id)].append(row)
    finally:
        connection.close()

    pool = [
        max(rows, key=lambda row: (
            (row["nearest_current_phash"] or {"distance": 256})["distance"],
            str(row["reviewed_at"]), str(row["photo_id"]),
        ))
        for rows in by_task.values()
    ]
    availability = Counter(str(row["sku_code"]) for row in pool)
    insufficient = {
        sku: {"available": availability[sku], "required": count_per_sku}
        for sku in sku_codes if availability[sku] < count_per_sku
    }
    if insufficient:
        raise RuntimeError(f"insufficient independent raw tasks: {insufficient}")

    selected: list[dict[str, Any]] = []
    for sku in sku_codes:
        while sum(str(row["sku_code"]) == sku for row in selected) < count_per_sku:
            prior = current_phashes + [
                (str(row["photo_id"]), str(row["source_perceptual_hash"])) for row in selected
            ]
            choices = []
            for row in pool:
                if str(row["sku_code"]) != sku or row in selected:
                    continue
                nearest = roi_plan.nearest(str(row["source_perceptual_hash"]), prior)
                distance = (nearest or {"distance": 256})["distance"]
                if distance <= roi_plan.ROUND_PHASH_DISTANCE:
                    continue
                choices.append((distance, row))
            if not choices:
                raise RuntimeError(f"unable to satisfy pHash diversity for {sku}")
            selected.append(max(
                choices, key=lambda item: (
                    item[0], str(item[1]["reviewed_at"]), str(item[1]["photo_id"]),
                ),
            )[1])

    selected_hashes = [
        (str(row["photo_id"]), str(row["source_perceptual_hash"])) for row in selected
    ]
    output_rows = []
    for row in selected:
        other = [item for item in selected_hashes if item[0] != str(row["photo_id"])]
        output_rows.append({
            "photo_id": str(row["photo_id"]), "task_id": str(row["task_id"]),
            "sku_code": str(row["sku_code"]), "source_path": str(row["local_path"]),
            "source_sha256": str(row["sha256"]),
            "source_perceptual_hash": str(row["source_perceptual_hash"]),
            "nearest_protected_phash": row["nearest_protected_phash"],
            "nearest_current_phash": row["nearest_current_phash"],
            "nearest_other_selected_phash": roi_plan.nearest(
                str(row["source_perceptual_hash"]), other,
            ),
            "existing_photo_task_sha_exclusion_passed": True,
            "protected_exact_exclusion_passed": True,
            "protected_phash_exclusion_passed": True,
            "tray_boxes_status": "requires_full_human_review",
        })
    protected_resolved = protected_path.resolve()
    return {
        "version": VERSION, "created_at": dt.datetime.now(dt.timezone.utc).isoformat(),
        "plan_only": True, "queue_created": False, "mark_created": False,
        "database": str(database.resolve()),
        "existing_manifests": [str(path.resolve()) for path in existing_manifests],
        "existing_manifest_sha256s": manifest_hashes,
        "protected_manifest": str(protected_resolved),
        "protected_manifest_sha256": roi_plan.sha256(protected_resolved),
        "sku_codes": sku_codes, "count_per_sku": count_per_sku,
        "selected_count": len(output_rows), "unique_task_count": len({r["task_id"] for r in output_rows}),
        "sku_counts": dict(Counter(row["sku_code"] for row in output_rows)),
        "pool": {
            "eligible_photos": sum(len(rows) for rows in by_task.values()),
            "eligible_tasks": len(pool), "eligible_tasks_by_sku": dict(availability),
            "exclusions": dict(exclusions),
        },
        "rules": {
            "one_photo_per_task": True, "sku_balanced": True,
            "existing_excluded_by_photo_task_sha": True,
            "protected_excluded_by_id_sha_phash": True,
            "phash_hamming_min_exclusive": roi_plan.ROUND_PHASH_DISTANCE,
            "detector_preboxes_are_not_truth": True,
            "tray_review_required_before_roi": True,
        },
        "selected": output_rows,
        "protected_holdout_modified": False, "originals_modified": False,
        "production_reads": 0, "production_writes": 0,
    }


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--database", required=True, type=Path)
    parser.add_argument("--existing-manifest", required=True, action="append", type=Path)
    parser.add_argument("--protected-holdout", required=True, type=Path)
    parser.add_argument("--sku-code", required=True, action="append")
    parser.add_argument("--count-per-sku", required=True, type=int)
    parser.add_argument("--runtime-root", required=True, type=Path)
    args = parser.parse_args()
    if args.count_per_sku < 1:
        parser.error("count-per-sku must be positive")
    plan = build_plan(
        args.database, args.existing_manifest, args.protected_holdout,
        args.sku_code, args.count_per_sku,
    )
    receipts = args.runtime_root.resolve() / "receipts"
    receipts.mkdir(parents=True, exist_ok=True)
    stamp = dt.datetime.now(dt.timezone.utc).strftime("%Y%m%dT%H%M%S%fZ")
    path = receipts / f"work-area-raw-tray-plan-{stamp}.json"
    path.write_text(json.dumps(plan, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"receipt": str(path), **plan}, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
