#!/usr/bin/env python3
"""Evaluate a geometry-constrained work-area quadrilateral model offline."""
from __future__ import annotations

import argparse
import datetime as dt
import json
import random
from pathlib import Path
from typing import Any

import numpy as np

import work_area_roi_corner_experiment as corner
import work_area_roi_experiment as common
import work_area_roi_multitask_experiment as multitask
import work_area_roi_quad_experiment as quad


def build_model(torch, input_channels: int, base_channels: int, depth: int):
    nn = torch.nn
    feature_channels = base_channels * 2

    class ConstrainedRoiModel(nn.Module):
        def __init__(self) -> None:
            super().__init__()
            self.features = common.build_tiny_unet(
                torch, input_channels=input_channels, base_channels=base_channels,
                normalized_blocks=True, unet_depth=depth,
                output_channels=feature_channels,
            )
            self.mask_head = nn.Conv2d(feature_channels, 1, 1)
            self.parameter_head = nn.Sequential(
                nn.AdaptiveAvgPool2d((4, 3)), nn.Flatten(),
                nn.Linear(feature_channels * 12, 128), nn.SiLU(inplace=True),
                nn.Linear(128, 10),
            )

        def forward(self, value):
            features = self.features(value)
            return self.mask_head(features), self.parameter_head(features)

    return ConstrainedRoiModel()


def decode_quadrants(torch, logits):
    """Decode center plus four quadrant-local points in TL, TR, BR, BL order."""
    if logits.ndim != 2 or logits.shape[1] != 10:
        raise ValueError("constrained quad decoder expects B x 10 logits")
    values = torch.sigmoid(logits)
    center = values[:, :2]
    offsets = values[:, 2:].reshape(-1, 4, 2)
    cx, cy = center[:, 0], center[:, 1]
    left = cx[:, None] * offsets[:, :, 0]
    right = cx[:, None] + (1.0 - cx[:, None]) * offsets[:, :, 0]
    top = cy[:, None] * offsets[:, :, 1]
    bottom = cy[:, None] + (1.0 - cy[:, None]) * offsets[:, :, 1]
    points = torch.stack((
        torch.stack((left[:, 0], top[:, 0]), dim=-1),
        torch.stack((right[:, 1], top[:, 1]), dim=-1),
        torch.stack((right[:, 2], bottom[:, 2]), dim=-1),
        torch.stack((left[:, 3], bottom[:, 3]), dim=-1),
    ), dim=1)
    return points


def convexity_loss(torch, points, *, margin: float = 1e-4):
    edges = torch.roll(points, shifts=-1, dims=1) - points
    following = torch.roll(edges, shifts=-1, dims=1)
    cross = edges[:, :, 0] * following[:, :, 1] - edges[:, :, 1] * following[:, :, 0]
    return torch.relu(margin - cross).mean()


def polygon_area(torch, points):
    following = torch.roll(points, shifts=-1, dims=1)
    return 0.5 * torch.abs(
        (points[:, :, 0] * following[:, :, 1]
         - following[:, :, 0] * points[:, :, 1]).sum(dim=1)
    )


def train_model(
    torch, model, images, masks, points, centers, center_labels, center_valid,
    *, epochs: int, seed: int, device, augment: bool,
):
    nn = torch.nn
    optimizer = torch.optim.AdamW(model.parameters(), lr=2e-3, weight_decay=1e-4)
    target_area = polygon_area(torch, points)
    for epoch in range(epochs):
        model.train()
        model_input = images
        if augment:
            generator = torch.Generator(device=device).manual_seed(seed + epoch)
            brightness = 0.90 + 0.20 * torch.rand(
                (images.shape[0], 1, 1, 1), generator=generator, device=device,
            )
            noise = 0.015 * torch.randn(
                images[:, :3].shape, generator=generator, device=device,
            )
            rgb = (images[:, :3] * brightness + noise).clamp(0, 1)
            model_input = torch.cat((rgb, images[:, 3:]), dim=1)
        mask_logits, parameter_logits = model(model_input)
        predicted = decode_quadrants(torch, parameter_logits)
        mask_probability = torch.sigmoid(mask_logits)
        mask_bce = nn.functional.binary_cross_entropy_with_logits(mask_logits, masks)
        intersection = (mask_probability * masks).sum(dim=(1, 2, 3))
        denominator = mask_probability.sum(dim=(1, 2, 3)) + masks.sum(dim=(1, 2, 3))
        mask_dice = 1.0 - ((2.0 * intersection + 1.0) / (denominator + 1.0)).mean()
        coordinate_loss = nn.functional.smooth_l1_loss(predicted, points, beta=0.01)
        predicted_edges = torch.roll(predicted, -1, 1) - predicted
        target_edges = torch.roll(points, -1, 1) - points
        edge_loss = nn.functional.smooth_l1_loss(predicted_edges, target_edges, beta=0.01)
        membership_loss = corner.center_membership_loss(
            torch, predicted, centers, center_labels, center_valid,
        )
        area_loss = nn.functional.smooth_l1_loss(
            polygon_area(torch, predicted), target_area, beta=0.01,
        )
        shape_loss = convexity_loss(torch, predicted)
        loss = (
            10.0 * coordinate_loss + 2.5 * edge_loss
            + 10.0 * membership_loss + 2.0 * area_loss + 5.0 * shape_loss
            + 2.0 * (mask_bce + mask_dice)
        )
        optimizer.zero_grad(set_to_none=True)
        loss.backward()
        optimizer.step()
    return optimizer


def prepare_data(queues: list[Path], width: int, height: int):
    _manifests, samples = common.load_combined_samples(queues)
    images = np.stack([common.load_image(sample["image"], width, height) for sample in samples])
    images = common.add_coordinate_channels(images)
    points = np.stack([quad.canonicalize_polygon(sample["polygon"]) for sample in samples])
    masks = np.stack([
        common.rasterize_polygon(polygon.tolist(), width, height) for polygon in points
    ]).astype(np.float32)
    centers, center_labels, center_valid = corner.tray_center_targets(samples)
    return samples, images, points, masks, centers, center_labels, center_valid


def run(
    queues: list[Path], *, epochs: int, width: int, height: int, device_name: str,
    base_channels: int, depth: int, fold_count: int, fit: bool,
) -> dict[str, Any]:
    import torch

    (samples, images, points, masks, centers, center_labels,
     center_valid) = prepare_data(queues, width, height)
    if len(samples) < 8:
        raise RuntimeError("constrained ROI experiment requires at least 8 independent reviewed images")
    if device_name == "cuda" and not torch.cuda.is_available():
        raise RuntimeError("CUDA requested but unavailable")
    device = torch.device(device_name)
    torch.manual_seed(20260812)
    np.random.seed(20260812)
    random.seed(20260812)
    splits = (
        [(list(range(len(samples))), list(range(len(samples))))]
        if fit else common.task_grouped_splits(samples, fold_count)
    )
    rows: list[dict[str, Any]] = []
    invalid = 0
    for fold_index, (train_indices, held_indices) in enumerate(splits):
        torch.manual_seed(20260812 + fold_index)
        model = build_model(torch, images.shape[-1], base_channels, depth).to(device)
        x = torch.from_numpy(images[train_indices].transpose(0, 3, 1, 2)).to(device)
        m = torch.from_numpy(masks[train_indices, None]).to(device)
        p = torch.from_numpy(points[train_indices]).to(device)
        c = torch.from_numpy(centers[train_indices]).to(device)
        cl = torch.from_numpy(center_labels[train_indices]).to(device)
        cv = torch.from_numpy(center_valid[train_indices]).to(device)
        optimizer = train_model(
            torch, model, x, m, p, c, cl, cv, epochs=epochs,
            seed=20260812 + fold_index * epochs, device=device, augment=not fit,
        )
        held = torch.from_numpy(images[held_indices].transpose(0, 3, 1, 2)).to(device)
        model.eval()
        with torch.inference_mode():
            _held_mask, held_parameters = model(held)
            predictions = decode_quadrants(torch, held_parameters).cpu().numpy()
        for position, sample_index in enumerate(held_indices):
            sample = samples[sample_index]
            metrics, valid = quad.evaluate_polygon(
                predictions[position], masks[sample_index].astype(bool), sample, width, height,
            )
            invalid += int(not valid)
            rows.append({
                "fold": fold_index + 1, "held_out_task_id": sample["task_id"],
                "held_out_photo_id": sample["source_photo_id"],
                "sku_code": sample["sku_code"], **metrics,
            })
        del model, optimizer, x, m, p, c, cl, cv, held, _held_mask, held_parameters
        if device.type == "cuda":
            torch.cuda.empty_cache()
    summary = common._summarise(rows)
    thresholds = {
        "min_iou": 0.95 if fit else 0.85,
        "min_center_accuracy": 0.99 if fit else 0.98,
        "min_inside_recall": 0.99 if fit else 0.98,
        "min_outside_recall": 0.99 if fit else 0.95,
    }
    passed = invalid == 0 and all(summary[key] >= value for key, value in thresholds.items())
    receipt = corner.base_receipt(
        queues, samples, epochs=epochs, width=width, height=height,
        base_channels=base_channels, depth=depth, sigma=0.0, device=str(device),
    )
    receipt.update({
        "version": "vision-lab-work-area-roi-constrained-quad-v1",
        "model": "normalized-unet-mask-plus-spatial-center-quadrant-quad-random-init",
        "experiment_script_sha256": common.sha256(Path(__file__)),
        "multitask_script_sha256": common.sha256(Path(multitask.__file__)),
        "mode": "training-fit-diagnostic" if fit else "task-grouped-cross-validation",
        "folds": len(splits),
        "split": "all-to-all" if fit else "deterministic-sku-stratified-task-k-fold",
        "training_augmentation": False if fit else "rgb-brightness-and-noise-only",
        "parameterization": "center-plus-TL-TR-BR-BL-quadrant-local-points",
        "parameter_head": "adaptive-4x3-spatial-grid-mlp",
        "coordinate_bounds_by_construction": True,
        "corner_identity_by_construction": True,
        "self_intersection_prevented_by_angular_order": True,
        "convexity_training_constraint": True,
        "mask_role": "training-only-global-instance-auxiliary-from-human-four-point-polygon",
        "inference_output": "four-point-polygon",
        "loss": {
            "corner_coordinate": 10.0, "edge_vector": 2.5,
            "tray_center_membership": 10.0, "polygon_area": 2.0,
            "convexity": 5.0, "human_polygon_mask_bce_plus_dice": 2.0,
        },
        "invalid_polygon_count": invalid, "thresholds": thresholds,
        "summary": summary, "rows": rows, "passed": passed,
        "sufficient_for_production": False, "deployment_authorized": False,
        "conclusion": (
            ("fit_capacity_confirmed" if passed else "model_or_optimization_bottleneck")
            if fit else ("offline_candidate_signal_only_locked_test_still_required" if passed
                         else "insufficient_image_conditioned_roi_evidence")
        ),
    })
    return receipt


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--queue", required=True, action="append", type=Path)
    parser.add_argument("--runtime-root", required=True, type=Path)
    parser.add_argument("--epochs", type=int, default=1200)
    parser.add_argument("--width", type=int, default=common.DEFAULT_WIDTH)
    parser.add_argument("--height", type=int, default=common.DEFAULT_HEIGHT)
    parser.add_argument("--device", choices=("cuda", "cpu"), default="cuda")
    parser.add_argument("--base-channels", type=int, choices=(4, 8, 16), default=8)
    parser.add_argument("--depth", type=int, choices=(2, 3, 4), default=4)
    parser.add_argument("--folds", type=int, default=8)
    parser.add_argument("--fit-diagnostic", action="store_true")
    args = parser.parse_args()
    if args.epochs < 1 or args.folds < 2:
        parser.error("epochs must be positive and folds >= 2")
    receipt = run(
        args.queue, epochs=args.epochs, width=args.width, height=args.height,
        device_name=args.device, base_channels=args.base_channels, depth=args.depth,
        fold_count=args.folds, fit=args.fit_diagnostic,
    )
    args.runtime_root.mkdir(parents=True, exist_ok=True)
    timestamp = dt.datetime.now(dt.timezone.utc).strftime("%Y%m%dT%H%M%S%fZ")
    kind = "fit" if args.fit_diagnostic else "experiment"
    path = args.runtime_root / f"work-area-roi-constrained-{kind}-{timestamp}.json"
    path.write_text(json.dumps(receipt, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({
        "receipt": str(path), "passed": receipt["passed"],
        "summary": receipt["summary"], "conclusion": receipt["conclusion"],
    }, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
