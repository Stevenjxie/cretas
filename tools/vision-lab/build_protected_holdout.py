#!/usr/bin/env python3
"""Build the immutable seven-defect LIUSHANMEN promotion holdout."""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path

LEGACY_VAL_TASKS = {
    "0a4815f0-2e72-42f3-8160-6d5d7016213a",
    "5134b7ef-8276-4a2a-973a-e14a32f9246f",
    "860b522d-3147-4db3-aa16-791ff30001d7",
    "d043753f-18fb-4ec4-8f67-e89d2f4d13cf",
    "e7673955-550a-4cc2-a2a3-0550800938a0",
}


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--legacy-dataset", required=True, type=Path)
    parser.add_argument("--latest-manifest", required=True, type=Path)
    parser.add_argument("--latest-review", required=True, type=Path)
    parser.add_argument("--training-queue", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()

    training = json.loads((args.training_queue / "manifest.json").read_text(encoding="utf-8"))
    training_tasks = {str(row.get("source_task_id")) for row in training["rows"] if row.get("source_task_id")}
    training_photos = {str(row.get("source_photo_id")) for row in training["rows"] if row.get("source_photo_id")}
    records = []

    latest = json.loads(args.latest_manifest.read_text(encoding="utf-8"))["records"]
    for row in latest:
        if str(row["task_id"]) not in LEGACY_VAL_TASKS:
            continue
        image = Path(row["source_path"])
        if not image.is_file():
            raise FileNotFoundError(image)
        records.append({
            "photo_id": str(row["photo_id"]), "task_id": str(row["task_id"]),
            "image": str(image.resolve()), "image_sha256": sha256_file(image),
            "human_label": "NO_DEFECT", "bbox": None, "group": "legacy_latest_normal",
        })

    review = json.loads((args.latest_review / "manifest.json").read_text(encoding="utf-8"))["selected"]
    for row in review:
        # The reviewed false candidates are deliberately present in the 240-image
        # hard-negative training queue.  Only the two independently held-out
        # real defects are eligible for this promotion set.
        if str(row["human_label"]).upper() == "NO_DEFECT":
            continue
        image = Path(row["path"])
        if not image.is_file():
            raise FileNotFoundError(image)
        records.append({
            "photo_id": str(row["photo_id"]), "task_id": str(row["task_id"]),
            "image": str(image.resolve()), "image_sha256": sha256_file(image),
            "human_label": str(row["human_label"]), "bbox": row.get("bbox"),
            "group": "new_blind_defect" if row["human_label"] != "NO_DEFECT" else "new_false_candidate_normal",
        })

    for split in ("val", "test"):
        for label in sorted((args.legacy_dataset / "labels" / split).glob("real_*.txt")):
            image = args.legacy_dataset / "images" / split / f"{label.stem}.jpg"
            values = label.read_text(encoding="utf-8").split()
            if len(values) != 5 or not image.is_file():
                raise RuntimeError(f"invalid locked positive: {label}")
            cls, cx, cy, width, height = map(float, values)
            records.append({
                "photo_id": f"locked-{split}-{label.stem}", "task_id": f"locked-{split}-{label.stem}",
                "image": str(image.resolve()), "image_sha256": sha256_file(image),
                "human_label": "MISSING_WHITE_LABEL" if int(cls) == 0 else "MISSING_COLOR_LABEL",
                "bbox": [cx - width / 2, cy - height / 2, cx + width / 2, cy + height / 2],
                "group": "legacy_locked_defect",
            })

    ids = [row["photo_id"] for row in records]
    hashes = [row["image_sha256"] for row in records]
    if len(ids) != len(set(ids)) or len(hashes) != len(set(hashes)):
        raise RuntimeError("duplicate photo or exact image hash in holdout")
    overlap_tasks = {row["task_id"] for row in records} & training_tasks
    overlap_photos = {row["photo_id"] for row in records} & training_photos
    if overlap_tasks or overlap_photos:
        raise RuntimeError(f"training/holdout leakage: tasks={overlap_tasks} photos={overlap_photos}")
    defects = [row for row in records if row["human_label"] != "NO_DEFECT"]
    normals = [row for row in records if row["human_label"] == "NO_DEFECT"]
    if len(defects) != 7 or len(normals) < 20:
        raise RuntimeError(f"unexpected holdout coverage: defects={len(defects)} normals={len(normals)}")
    payload = {
        "version": "liushanmen-protected-holdout-v1",
        "protected": True, "train_use_allowed": False,
        "production_writes": 0, "originals_modified": 0,
        "counts": {"records": len(records), "defects": len(defects), "normals": len(normals)},
        "source_manifests": {
            "latest": sha256_file(args.latest_manifest),
            "latest_review": sha256_file(args.latest_review / "manifest.json"),
            "training_queue": sha256_file(args.training_queue / "manifest.json"),
        },
        "records": records,
    }
    args.output.parent.mkdir(parents=True, exist_ok=True)
    if args.output.exists():
        existing = json.loads(args.output.read_text(encoding="utf-8"))
        if existing != payload:
            raise RuntimeError(f"refusing to overwrite different holdout: {args.output}")
    else:
        args.output.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(payload["counts"], ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
