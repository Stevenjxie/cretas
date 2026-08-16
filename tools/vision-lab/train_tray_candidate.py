#!/usr/bin/env python3
"""Train and export one tray candidate without evaluation or deployment."""
from __future__ import annotations

import argparse
import copy
import json
from pathlib import Path
from typing import Any

import label_qc_tray_patch_dataset as patch_dataset
import tray_workflow


def load_json(path: Path) -> dict[str, Any]:
    value = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(value, dict):
        raise ValueError(f"expected JSON object: {path}")
    return value


def validate_dataset_manifest(path: Path) -> dict[str, Any]:
    manifest = load_json(path)
    if manifest.get("training_allowed") is not True:
        raise RuntimeError("dataset is not approved for training")
    if manifest.get("protected_holdout_included") is not False:
        raise RuntimeError("dataset does not prove protected holdout exclusion")
    if manifest.get("preannotations_used_as_truth") is not False:
        raise RuntimeError("dataset proposal/truth boundary is unsafe")
    if manifest.get("deleted_proposals_used_as_background") is not False:
        raise RuntimeError("dataset ignore-region/background boundary is unsafe")
    if manifest.get("images_with_ignore_regions_excluded_from_training") is not True:
        raise RuntimeError("dataset does not prove ignore-region image exclusion")
    if manifest.get("validation_unchanged_from_base_dataset") is not True:
        raise RuntimeError("dataset validation split drift")
    root = path.parent.resolve()
    if manifest.get("dataset_sha256") != patch_dataset.dataset_digest(root):
        raise RuntimeError("dataset content drift")
    data_yaml = Path(str(manifest.get("data_yaml") or "")).resolve()
    if not data_yaml.is_file() or data_yaml.parent != root:
        raise RuntimeError("dataset data.yaml boundary drift")
    return manifest


def apply_training_overrides(
    config: dict[str, Any], *, epochs: int | None = None, patience: int | None = None,
    lr0: float | None = None, freeze: int | None = None,
) -> dict[str, Any]:
    updated = copy.deepcopy(config)
    training = updated["tray_active_learning"]["training"]
    values = {
        "epochs": epochs,
        "patience": patience,
        "lr0": lr0,
        "freeze": freeze,
    }
    if epochs is not None and not 1 <= epochs <= 200:
        raise ValueError("epochs override must be between 1 and 200")
    if patience is not None and not 1 <= patience <= 100:
        raise ValueError("patience override must be between 1 and 100")
    if lr0 is not None and not 0.0 < lr0 <= 0.001:
        raise ValueError("lr0 override must be in (0, 0.001]")
    if freeze is not None and not 0 <= freeze <= 22:
        raise ValueError("freeze override must be between 0 and 22")
    for key, value in values.items():
        if value is not None:
            training[key] = value
    return updated


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--config", required=True, type=Path)
    parser.add_argument("--dataset-manifest", required=True, type=Path)
    parser.add_argument("--repo-root", required=True, type=Path)
    parser.add_argument("--epochs", type=int)
    parser.add_argument("--patience", type=int)
    parser.add_argument("--lr0", type=float)
    parser.add_argument("--freeze", type=int)
    return parser


def main() -> None:
    args = build_parser().parse_args()
    config = apply_training_overrides(
        load_json(args.config.resolve()), epochs=args.epochs, patience=args.patience,
        lr0=args.lr0, freeze=args.freeze,
    )
    dataset = validate_dataset_manifest(args.dataset_manifest.resolve())
    model = tray_workflow.train_candidate(config, dataset, args.repo_root.resolve())
    print(json.dumps(model, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
