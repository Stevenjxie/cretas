#!/usr/bin/env python3
"""Evaluate a low-dimensional four-corner work-area model without pretrained weights.

The human contract is a four-point perspective polygon, so this experiment
regresses those eight normalized coordinates directly instead of learning a
full-resolution segmentation mask. It remains an offline, no-save gate.
"""
from __future__ import annotations

import argparse
import datetime as dt
import json
import random
from pathlib import Path
from typing import Any

import numpy as np

import work_area_roi_experiment as common


def canonicalize_polygon(polygon: list[list[float]]) -> np.ndarray:
    points = np.asarray(common.work_area.validate_polygon(polygon), dtype=np.float32)
    center = points.mean(axis=0)
    angles = np.arctan2(points[:, 1] - center[1], points[:, 0] - center[0])
    ordered = points[np.argsort(angles)]
    signed_area = 0.5 * np.sum(
        ordered[:, 0] * np.roll(ordered[:, 1], -1)
        - np.roll(ordered[:, 0], -1) * ordered[:, 1]
    )
    if signed_area < 0:
        ordered = ordered[::-1]
    start = int(np.argmin(ordered[:, 0] + ordered[:, 1]))
    return np.roll(ordered, -start, axis=0)


def build_quad_regressor(torch, input_channels: int = 5, base_channels: int = 16):
    nn = torch.nn

    class DownBlock(nn.Module):
        def __init__(self, source_channels: int, target_channels: int) -> None:
            super().__init__()
            groups = min(8, target_channels)
            while target_channels % groups:
                groups -= 1
            self.layers = nn.Sequential(
                nn.Conv2d(source_channels, target_channels, 3, stride=2, padding=1, bias=False),
                nn.GroupNorm(groups, target_channels), nn.SiLU(inplace=True),
                nn.Conv2d(target_channels, target_channels, 3, padding=1, bias=False),
                nn.GroupNorm(groups, target_channels), nn.SiLU(inplace=True),
            )

        def forward(self, value):
            return self.layers(value)

    class QuadRegressor(nn.Module):
        def __init__(self) -> None:
            super().__init__()
            channels = [
                base_channels, base_channels * 2, base_channels * 4,
                base_channels * 6, base_channels * 8,
            ]
            blocks = []
            source_channels = input_channels
            for target_channels in channels:
                blocks.append(DownBlock(source_channels, target_channels))
                source_channels = target_channels
            self.encoder = nn.Sequential(*blocks)
            self.pool = nn.AdaptiveAvgPool2d((4, 3))
            self.head = nn.Sequential(
                nn.Flatten(), nn.Linear(channels[-1] * 12, 256), nn.SiLU(inplace=True),
                nn.Linear(256, 8), nn.Sigmoid(),
            )

        def forward(self, value):
            return self.head(self.pool(self.encoder(value))).reshape(-1, 4, 2)

    return QuadRegressor()


def train_model(
    torch, model, images, targets, *, epochs: int, seed: int, device, augment: bool,
):
    nn = torch.nn
    optimizer = torch.optim.AdamW(model.parameters(), lr=1e-3, weight_decay=1e-4)
    for epoch in range(epochs):
        model.train()
        model_input = images
        if augment:
            generator = torch.Generator(device=device).manual_seed(seed + epoch)
            brightness = 0.92 + 0.16 * torch.rand(
                (images.shape[0], 1, 1, 1), generator=generator, device=device,
            )
            rgb = images[:, :3]
            noise = 0.01 * torch.randn(rgb.shape, generator=generator, device=device)
            augmented_rgb = (rgb * brightness + noise).clamp(0, 1)
            model_input = torch.cat((augmented_rgb, images[:, 3:]), dim=1)
        predicted = model(model_input)
        coordinate_loss = nn.functional.smooth_l1_loss(predicted, targets, beta=0.02)
        predicted_edges = torch.roll(predicted, shifts=-1, dims=1) - predicted
        target_edges = torch.roll(targets, shifts=-1, dims=1) - targets
        edge_loss = nn.functional.smooth_l1_loss(predicted_edges, target_edges, beta=0.02)
        center_loss = nn.functional.smooth_l1_loss(
            predicted.mean(dim=1), targets.mean(dim=1), beta=0.02,
        )
        loss = coordinate_loss + 0.25 * edge_loss + 0.25 * center_loss
        optimizer.zero_grad(set_to_none=True)
        loss.backward()
        optimizer.step()
    return optimizer


def evaluate_polygon(
    predicted_points: np.ndarray, truth_mask: np.ndarray, sample: dict[str, Any],
    width: int, height: int,
) -> tuple[dict[str, Any], bool]:
    try:
        predicted_polygon = predicted_points.astype(float).tolist()
        predicted_mask = common.rasterize_polygon(predicted_polygon, width, height).astype(bool)
        centers = common.polygon_center_metrics(
            predicted_polygon, sample["polygon"], sample["boxes"],
        )
        valid = True
    except (TypeError, ValueError):
        predicted_mask = np.zeros((height, width), dtype=bool)
        centers = common.center_metrics(predicted_mask, truth_mask, sample["boxes"])
        valid = False
    return {
        "mask": common.mask_metrics(predicted_mask, truth_mask),
        "centers": centers,
        "predicted_polygon": predicted_points.astype(float).tolist(),
    }, valid


def prepare_data(
    queues: list[Path], width: int, height: int,
) -> tuple[list[dict[str, Any]], np.ndarray, np.ndarray, np.ndarray]:
    _manifests, samples = common.load_combined_samples(queues)
    images = np.stack([common.load_image(sample["image"], width, height) for sample in samples])
    images = common.add_coordinate_channels(images)
    targets = np.stack([canonicalize_polygon(sample["polygon"]) for sample in samples])
    masks = np.stack([
        common.rasterize_polygon(target.tolist(), width, height) for target in targets
    ]).astype(bool)
    return samples, images, targets, masks


def base_receipt(
    queues: list[Path], samples: list[dict[str, Any]], *, epochs: int,
    width: int, height: int, base_channels: int, device: str,
) -> dict[str, Any]:
    return {
        "created_at": dt.datetime.now(dt.timezone.utc).isoformat(),
        "queues": [str(queue) for queue in queues],
        "queue_manifest_sha256s": {
            str(queue): common.sha256(queue / "manifest.json") for queue in queues
        },
        "samples": [{
            "task_id": sample["task_id"],
            "source_photo_id": sample["source_photo_id"],
            "sku_code": sample["sku_code"],
            "image_sha256": sample["image_sha256"],
            "source_sha256": sample["source_sha256"],
            "roi_sha256": sample["roi_sha256"],
        } for sample in samples],
        "sample_count": len(samples),
        "task_count": len({sample["task_id"] for sample in samples}),
        "sku_codes": sorted({sample["sku_code"] for sample in samples}),
        "input_size": [width, height], "epochs": epochs,
        "model": "global-cnn-four-corner-regressor-random-init",
        "base_channels": base_channels, "coordinate_channels": True,
        "canonical_corner_order": "centroid-angle-clockwise-start-min-x-plus-y",
        "device": device,
        "experiment_script_sha256": common.sha256(Path(__file__)),
        "common_script_sha256": common.sha256(Path(common.__file__)),
        "pretrained_weights": False, "downloaded_weights": 0, "cloud_calls": 0,
        "production_reads": 0, "production_writes": 0, "registry_writes": 0,
        "protected_holdout_used": False, "originals_modified": False,
        "model_saved": False,
    }


def run_fit_diagnostic(
    queues: list[Path], *, epochs: int, width: int, height: int,
    device_name: str, base_channels: int,
) -> dict[str, Any]:
    import torch

    samples, images, targets, masks = prepare_data(queues, width, height)
    torch.manual_seed(20260812)
    np.random.seed(20260812)
    random.seed(20260812)
    if device_name == "cuda" and not torch.cuda.is_available():
        raise RuntimeError("CUDA requested but unavailable")
    device = torch.device(device_name)
    x = torch.from_numpy(images.transpose(0, 3, 1, 2)).to(device)
    y = torch.from_numpy(targets).to(device)
    model = build_quad_regressor(torch, images.shape[-1], base_channels).to(device)
    optimizer = train_model(
        torch, model, x, y, epochs=epochs, seed=20260812, device=device, augment=False,
    )
    model.eval()
    with torch.inference_mode():
        predictions = model(x).cpu().numpy()
    rows, invalid = [], 0
    for index, sample in enumerate(samples):
        metrics, valid = evaluate_polygon(predictions[index], masks[index], sample, width, height)
        invalid += int(not valid)
        rows.append({
            "task_id": sample["task_id"], "source_photo_id": sample["source_photo_id"],
            "sku_code": sample["sku_code"], **metrics,
        })
    summary = common._summarise(rows)
    fit_passed = (
        invalid == 0 and summary["min_iou"] >= 0.95
        and summary["min_center_accuracy"] >= 0.99
        and summary["min_inside_recall"] >= 0.99
        and summary["min_outside_recall"] >= 0.99
    )
    receipt = base_receipt(
        queues, samples, epochs=epochs, width=width, height=height,
        base_channels=base_channels, device=str(device),
    )
    receipt.update({
        "version": "vision-lab-work-area-roi-quad-fit-v1",
        "training_augmentation": False, "invalid_polygon_count": invalid,
        "summary": summary, "rows": rows, "training_fit_passed": fit_passed,
        "diagnosis": "generalization_data_bottleneck" if fit_passed else "model_or_optimization_bottleneck",
    })
    del model, optimizer, x, y
    if device.type == "cuda":
        torch.cuda.empty_cache()
    return receipt


def run_experiment(
    queues: list[Path], *, epochs: int, width: int, height: int,
    device_name: str, base_channels: int, fold_count: int,
) -> dict[str, Any]:
    import torch

    samples, images, targets, masks = prepare_data(queues, width, height)
    if len(samples) < 8:
        raise RuntimeError("quad ROI experiment requires at least 8 independent reviewed images")
    torch.manual_seed(20260812)
    np.random.seed(20260812)
    random.seed(20260812)
    if device_name == "cuda" and not torch.cuda.is_available():
        raise RuntimeError("CUDA requested but unavailable")
    device = torch.device(device_name)
    rows, baseline_rows, invalid = [], [], 0
    splits = common.task_grouped_splits(samples, fold_count)
    for fold_index, (train_indices, held_out_indices) in enumerate(splits):
        torch.manual_seed(20260812 + fold_index)
        model = build_quad_regressor(torch, images.shape[-1], base_channels).to(device)
        x = torch.from_numpy(images[train_indices].transpose(0, 3, 1, 2)).to(device)
        y = torch.from_numpy(targets[train_indices]).to(device)
        optimizer = train_model(
            torch, model, x, y, epochs=epochs,
            seed=20260812 + fold_index * epochs, device=device, augment=True,
        )
        held_tensor = torch.from_numpy(
            images[held_out_indices].transpose(0, 3, 1, 2),
        ).to(device)
        model.eval()
        with torch.inference_mode():
            predictions = model(held_tensor).cpu().numpy()
        baseline_polygon = targets[train_indices].mean(axis=0)
        for held_position, held_out in enumerate(held_out_indices):
            sample = samples[held_out]
            metrics, valid = evaluate_polygon(
                predictions[held_position], masks[held_out], sample, width, height,
            )
            invalid += int(not valid)
            rows.append({
                "fold": fold_index + 1, "held_out_task_id": sample["task_id"],
                "held_out_photo_id": sample["source_photo_id"],
                "sku_code": sample["sku_code"], **metrics,
            })
            baseline_metrics, _valid = evaluate_polygon(
                baseline_polygon, masks[held_out], sample, width, height,
            )
            baseline_rows.append({
                "fold": fold_index + 1, "held_out_task_id": sample["task_id"],
                "held_out_photo_id": sample["source_photo_id"],
                "sku_code": sample["sku_code"], **baseline_metrics,
            })
        del model, optimizer, x, y, held_tensor
        if device.type == "cuda":
            torch.cuda.empty_cache()
    summary = common._summarise(rows)
    baseline_summary = common._summarise(baseline_rows)
    gain = summary["mean_iou"] - baseline_summary["mean_iou"]
    passed = (
        invalid == 0 and summary["min_iou"] >= 0.85
        and summary["min_center_accuracy"] >= 0.98
        and summary["min_inside_recall"] >= 0.98
        and summary["min_outside_recall"] >= 0.95 and gain >= 0.02
    )
    thresholds = {
        "min_iou": 0.85, "min_center_accuracy": 0.98,
        "min_inside_recall": 0.98, "min_outside_recall": 0.95,
        "min_mean_iou_gain_over_position_baseline": 0.02,
    }
    receipt = base_receipt(
        queues, samples, epochs=epochs, width=width, height=height,
        base_channels=base_channels, device=str(device),
    )
    receipt.update({
        "version": "vision-lab-work-area-roi-quad-experiment-v1",
        "folds": len(splits),
        "split": "deterministic-sku-stratified-task-k-fold",
        "training_augmentation": "rgb-brightness-and-noise-only",
        "invalid_polygon_count": invalid,
        "thresholds": thresholds,
        "model_cv": {"summary": summary, "folds": rows},
        "position_only_baseline": {"summary": baseline_summary, "folds": baseline_rows},
        "image_conditioned_mean_iou_gain": gain,
        "offline_cv_passed": passed,
        "sufficient_for_production": False, "deployment_authorized": False,
        "conclusion": (
            "offline_candidate_signal_only_locked_test_still_required"
            if passed else "insufficient_image_conditioned_roi_evidence"
        ),
    })
    return receipt


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--queue", required=True, action="append", type=Path)
    parser.add_argument("--runtime-root", required=True, type=Path)
    parser.add_argument("--epochs", type=int, default=1500)
    parser.add_argument("--width", type=int, default=common.DEFAULT_WIDTH)
    parser.add_argument("--height", type=int, default=common.DEFAULT_HEIGHT)
    parser.add_argument("--device", choices=("cuda", "cpu"), default="cuda")
    parser.add_argument("--base-channels", type=int, choices=(8, 16, 24), default=16)
    parser.add_argument("--folds", type=int, default=8)
    parser.add_argument("--fit-diagnostic", action="store_true")
    args = parser.parse_args()
    if args.epochs <= 0 or args.width % 32 or args.height % 32:
        raise ValueError("epochs must be positive and image dimensions divisible by 32")
    if args.folds < 2:
        raise ValueError("folds must be at least two")
    queues = [queue.resolve() for queue in args.queue]
    runner = run_fit_diagnostic if args.fit_diagnostic else run_experiment
    runner_args = {
        "epochs": args.epochs, "width": args.width, "height": args.height,
        "device_name": args.device, "base_channels": args.base_channels,
    }
    if not args.fit_diagnostic:
        runner_args["fold_count"] = args.folds
    receipt = runner(queues, **runner_args)
    receipts = args.runtime_root.resolve() / "receipts"
    receipts.mkdir(parents=True, exist_ok=True)
    stamp = dt.datetime.now(dt.timezone.utc).strftime("%Y%m%dT%H%M%S%fZ")
    kind = "fit" if args.fit_diagnostic else "experiment"
    path = receipts / f"work-area-roi-quad-{kind}-{stamp}.json"
    path.write_text(json.dumps(receipt, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps({"receipt": str(path), **receipt}, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
