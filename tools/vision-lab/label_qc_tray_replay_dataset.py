#!/usr/bin/env python3
"""Build an immutable tray dataset with explicit historical replay weighting.

Physical images and labels stay unchanged.  Training frequency is controlled by
an auditable text manifest: every train image appears once, while human-reviewed
historical rows may appear additional times.  Validation and protected holdout
boundaries are inherited and re-verified from the source dataset.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import shutil
from collections import Counter
from pathlib import Path
from typing import Any

import label_qc_tray_patch_dataset as patch_dataset


def load_json(path: Path) -> dict[str, Any]:
    value = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(value, dict):
        raise ValueError(f"expected JSON object: {path}")
    return value


def canonical(path: str | Path) -> str:
    return str(Path(path).resolve()).casefold()


def validate_source(manifest_path: Path, recent_queue: Path) -> tuple[dict[str, Any], dict[str, Any], list[dict[str, Any]], list[dict[str, Any]]]:
    manifest_path = manifest_path.resolve()
    root = manifest_path.parent
    manifest = load_json(manifest_path)
    if manifest.get("dataset_sha256") != patch_dataset.dataset_digest(root):
        raise RuntimeError("source dataset content drift")
    for field, expected in (
        ("training_allowed", True),
        ("protected_holdout_included", False),
        ("preannotations_used_as_truth", False),
        ("deleted_proposals_used_as_background", False),
        ("images_with_ignore_regions_excluded_from_training", True),
        ("validation_unchanged_from_base_dataset", True),
    ):
        if manifest.get(field) is not expected:
            raise RuntimeError(f"unsafe source dataset contract: {field}")

    provenance_path = root / "provenance.json"
    provenance = load_json(provenance_path)
    rows = provenance.get("rows") or []
    train_rows = [row for row in rows if row.get("split") == "train"]
    if not train_rows or len({str(row.get("stem")) for row in train_rows}) != len(train_rows):
        raise RuntimeError("source train provenance is empty or not unique by stem")
    recent_identity = canonical(recent_queue)
    recent_rows = [row for row in train_rows if canonical(str(row.get("queue") or "")) == recent_identity]
    historical_rows = [row for row in train_rows if canonical(str(row.get("queue") or "")) != recent_identity]
    if not recent_rows:
        raise RuntimeError("recent queue has no train rows in source provenance")
    if not historical_rows:
        raise RuntimeError("historical replay pool is empty")
    return manifest, provenance, historical_rows, recent_rows


def _copy_or_link(source_root: Path):
    def copy(source: str, target: str) -> str:
        source_path = Path(source)
        target_path = Path(target)
        if source_path.parent == source_root:
            return str(shutil.copy2(source_path, target_path))
        try:
            os.link(source_path, target_path)
            return str(target_path)
        except OSError:
            return str(shutil.copy2(source_path, target_path))
    return copy


def _train_image_map(root: Path) -> dict[str, Path]:
    images = [path for path in (root / "images" / "train").iterdir() if path.is_file()]
    mapped = {path.stem: path for path in images}
    if len(mapped) != len(images):
        raise RuntimeError("source train image stems are not unique")
    return mapped


def build_replay_dataset(
    source_manifest_path: Path,
    recent_queue: Path,
    output_root: Path,
    historical_repeats: int = 2,
) -> tuple[Path, dict[str, Any]]:
    if historical_repeats < 1 or historical_repeats > 4:
        raise ValueError("historical_repeats must be between 1 and 4")
    source_manifest_path = source_manifest_path.resolve()
    source_root = source_manifest_path.parent
    recent_queue = recent_queue.resolve()
    output_root = output_root.resolve()
    source, provenance, historical_rows, recent_rows = validate_source(
        source_manifest_path, recent_queue,
    )
    train_rows = historical_rows + recent_rows
    image_map = _train_image_map(source_root)
    stems = {str(row["stem"]) for row in train_rows}
    if stems != set(image_map):
        raise RuntimeError("train provenance/image inventory mismatch")
    for stem, image in image_map.items():
        label = source_root / "labels" / "train" / f"{stem}.txt"
        if not image.is_file() or not label.is_file():
            raise RuntimeError(f"missing train image or label: {stem}")

    identity = {
        "version": "label-qc-tray-replay-dataset-v1",
        "source_dataset_id": source["dataset_id"],
        "source_dataset_sha256": source["dataset_sha256"],
        "recent_queue": str(recent_queue),
        "recent_queue_manifest_sha256": patch_dataset.sha256_file(recent_queue / "manifest.json"),
        "historical_repeats": historical_repeats,
        "recent_repeats": 1,
        "validation_policy": "unchanged_from_source",
    }
    dataset_id = "tray-replay-" + hashlib.sha256(patch_dataset.stable_json(identity)).hexdigest()[:12]
    output = output_root / dataset_id
    if output.exists():
        existing = load_json(output / "manifest.json")
        if existing.get("identity") != identity or existing.get("dataset_sha256") != patch_dataset.dataset_digest(output):
            raise RuntimeError(f"existing replay dataset identity/content drift: {output}")
        return output, existing

    temporary = output.with_name(output.name + f".tmp.{os.getpid()}")
    if temporary.exists():
        raise RuntimeError(f"stale replay dataset temporary directory: {temporary}")
    output_root.mkdir(parents=True, exist_ok=True)
    shutil.copytree(source_root, temporary, copy_function=_copy_or_link(source_root))
    try:
        historical_stems = sorted(str(row["stem"]) for row in historical_rows)
        recent_stems = sorted(str(row["stem"]) for row in recent_rows)
        occurrences = historical_stems * historical_repeats + recent_stems
        lines = [f"./images/train/{image_map[stem].name}" for stem in occurrences]
        (temporary / "train-replay.txt").write_text("\n".join(lines) + "\n", encoding="utf-8")
        (temporary / "data.yaml").write_text(
            f"path: {output.as_posix()}\n"
            "train: train-replay.txt\n"
            "val: images/val\n"
            "names:\n  0: tray\n",
            encoding="utf-8",
        )
        replay_policy = {
            "historical_physical_images": len(historical_stems),
            "recent_physical_images": len(recent_stems),
            "historical_repeats": historical_repeats,
            "recent_repeats": 1,
            "effective_train_occurrences": len(occurrences),
            "unique_train_images": len(stems),
            "occurrence_counts": dict(sorted(Counter(occurrences).items())),
        }
        provenance["replay_policy"] = replay_policy
        (temporary / "provenance.json").write_text(
            json.dumps(provenance, ensure_ascii=False, indent=2), encoding="utf-8",
        )
        manifest = dict(source)
        manifest.update({
            "version": "label-qc-tray-replay-dataset-v1",
            "dataset_id": dataset_id,
            "identity": identity,
            "base_dataset": str(source_manifest_path),
            "base_dataset_id": source["dataset_id"],
            "base_dataset_sha256": source["dataset_sha256"],
            "replay_policy": replay_policy,
            "data_yaml": str(output / "data.yaml"),
            "training_allowed": True,
            "promotion_allowed": False,
            "gpu_rental_required": False,
            "production_writes": 0,
            "originals_modified": 0,
        })
        manifest["dataset_sha256"] = patch_dataset.dataset_digest(temporary)
        (temporary / "manifest.json").write_text(
            json.dumps(manifest, ensure_ascii=False, indent=2), encoding="utf-8",
        )
        temporary.rename(output)
        return output, manifest
    except Exception:
        shutil.rmtree(temporary, ignore_errors=True)
        raise


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source-manifest", required=True, type=Path)
    parser.add_argument("--recent-queue", required=True, type=Path)
    parser.add_argument("--output-root", required=True, type=Path)
    parser.add_argument("--historical-repeats", type=int, default=2)
    return parser


def main() -> None:
    args = build_parser().parse_args()
    root, manifest = build_replay_dataset(
        args.source_manifest, args.recent_queue, args.output_root, args.historical_repeats,
    )
    print(json.dumps({
        "dataset": str(root),
        "dataset_id": manifest["dataset_id"],
        "dataset_sha256": manifest["dataset_sha256"],
        "replay_policy": manifest["replay_policy"],
        "production_writes": 0,
        "originals_modified": 0,
    }, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
