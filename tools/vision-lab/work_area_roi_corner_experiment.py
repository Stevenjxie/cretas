#!/usr/bin/env python3
"""Evaluate four spatial corner heatmaps for human work-area polygons.

This offline no-save experiment keeps image-space evidence through a U-Net and
uses soft-argmax only to convert the four heatmaps into the polygon contract.
It never reads the protected holdout or writes registry/production state.
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
import work_area_roi_quad_experiment as quad


def gaussian_corner_targets(
    points: np.ndarray, width: int, height: int, sigma: float,
) -> np.ndarray:
    """Return normalized N x 4 x H x W Gaussian target distributions."""
    yy, xx = np.mgrid[0:height, 0:width].astype(np.float32)
    targets = []
    for polygon in points:
        channels = []
        for x_norm, y_norm in polygon:
            x = float(x_norm) * (width - 1)
            y = float(y_norm) * (height - 1)
            heatmap = np.exp(-((xx - x) ** 2 + (yy - y) ** 2) / (2 * sigma ** 2))
            heatmap /= max(float(heatmap.sum()), 1e-12)
            channels.append(heatmap)
        targets.append(channels)
    return np.asarray(targets, dtype=np.float32)


def spatial_soft_argmax(torch, logits, temperature: float = 0.1):
    batch, channels, height, width = logits.shape
    probability = torch.softmax(logits.reshape(batch, channels, -1) / temperature, dim=-1)
    xs = torch.linspace(0, 1, width, device=logits.device, dtype=logits.dtype)
    ys = torch.linspace(0, 1, height, device=logits.device, dtype=logits.dtype)
    yy, xx = torch.meshgrid(ys, xs, indexing="ij")
    x = (probability * xx.reshape(1, 1, -1)).sum(dim=-1)
    y = (probability * yy.reshape(1, 1, -1)).sum(dim=-1)
    return torch.stack((x, y), dim=-1)


def tray_center_targets(samples: list[dict[str, Any]]):
    """Return padded normalized tray centers and their exact human ROI labels."""
    max_boxes = max(len(sample["boxes"]) for sample in samples)
    centers = np.zeros((len(samples), max_boxes, 2), dtype=np.float32)
    labels = np.zeros((len(samples), max_boxes), dtype=np.float32)
    valid = np.zeros((len(samples), max_boxes), dtype=bool)
    for sample_index, sample in enumerate(samples):
        polygon = common.work_area.validate_polygon(sample["polygon"])
        for box_index, box in enumerate(sample["boxes"]):
            x0, y0, x1, y1 = common.work_area.validate_box(box)
            centers[sample_index, box_index] = ((x0 + x1) / 2, (y0 + y1) / 2)
            labels[sample_index, box_index] = float(
                common.work_area.classify_box_center(box, polygon)
                == common.work_area.INSIDE_WORK_AREA
            )
            valid[sample_index, box_index] = True
    return centers, labels, valid


def center_membership_loss(
    torch, predicted, centers, labels, valid, *, margin: float = 5e-4,
):
    """Penalize tray centers that cross a predicted convex-quad boundary.

    Canonical polygons have positive signed area in normalized image space, so
    their interior lies on the positive side of every directed edge. The
    minimum signed edge distance is therefore a differentiable classification
    score that directly represents the production center-in-polygon contract.
    """
    edges = torch.roll(predicted, shifts=-1, dims=1) - predicted
    relative = centers[:, :, None, :] - predicted[:, None, :, :]
    signed_cross = (
        edges[:, None, :, 0] * relative[:, :, :, 1]
        - edges[:, None, :, 1] * relative[:, :, :, 0]
    )
    edge_lengths = torch.linalg.vector_norm(edges, dim=-1).clamp_min(1e-6)
    membership_score = (signed_cross / edge_lengths[:, None, :]).amin(dim=-1)
    direction = labels.mul(2).sub(1)
    violations = torch.relu(margin - direction * membership_score)
    return violations[valid].mean()


def train_model(
    torch, model, images, heatmaps, points, centers, center_labels, center_valid,
    *, epochs: int, seed: int, device, augment: bool,
):
    nn = torch.nn
    optimizer = torch.optim.AdamW(model.parameters(), lr=2e-3, weight_decay=1e-4)
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
        logits = model(model_input)
        log_probability = nn.functional.log_softmax(logits.flatten(2), dim=-1)
        heatmap_loss = -(heatmaps.flatten(2) * log_probability).sum(dim=-1).mean()
        predicted = spatial_soft_argmax(torch, logits)
        coordinate_loss = nn.functional.smooth_l1_loss(predicted, points, beta=0.01)
        predicted_edges = torch.roll(predicted, -1, 1) - predicted
        target_edges = torch.roll(points, -1, 1) - points
        edge_loss = nn.functional.smooth_l1_loss(predicted_edges, target_edges, beta=0.01)
        membership_loss = center_membership_loss(
            torch, predicted, centers, center_labels, center_valid,
        )
        loss = heatmap_loss + 10.0 * coordinate_loss + 2.5 * edge_loss + 10.0 * membership_loss
        optimizer.zero_grad(set_to_none=True)
        loss.backward()
        optimizer.step()
    return optimizer


def prepare_data(queues: list[Path], width: int, height: int, sigma: float):
    _manifests, samples = common.load_combined_samples(queues)
    images = np.stack([common.load_image(sample["image"], width, height) for sample in samples])
    images = common.add_coordinate_channels(images)
    points = np.stack([quad.canonicalize_polygon(sample["polygon"]) for sample in samples])
    heatmaps = gaussian_corner_targets(points, width, height, sigma)
    masks = np.stack([
        common.rasterize_polygon(polygon.tolist(), width, height) for polygon in points
    ]).astype(bool)
    centers, center_labels, center_valid = tray_center_targets(samples)
    return samples, images, points, heatmaps, masks, centers, center_labels, center_valid


def evaluate(predicted: np.ndarray, truth_mask: np.ndarray, sample: dict[str, Any], width: int, height: int):
    return quad.evaluate_polygon(predicted, truth_mask, sample, width, height)


def base_receipt(queues, samples, *, epochs, width, height, base_channels, depth, sigma, device):
    return {
        "created_at": dt.datetime.now(dt.timezone.utc).isoformat(),
        "queues": [str(queue) for queue in queues],
        "queue_manifest_sha256s": {
            str(queue): common.sha256(queue / "manifest.json") for queue in queues
        },
        "samples": [{
            "task_id": sample["task_id"], "source_photo_id": sample["source_photo_id"],
            "sku_code": sample["sku_code"], "image_sha256": sample["image_sha256"],
            "source_sha256": sample["source_sha256"], "roi_sha256": sample["roi_sha256"],
        } for sample in samples],
        "sample_count": len(samples), "task_count": len({s["task_id"] for s in samples}),
        "sku_codes": sorted({s["sku_code"] for s in samples}),
        "input_size": [width, height], "epochs": epochs,
        "model": "normalized-unet-four-corner-heatmaps-random-init",
        "base_channels": base_channels, "unet_depth": depth,
        "coordinate_channels": True, "gaussian_sigma_pixels": sigma,
        "corner_decoder": "spatial-soft-argmax-temperature-0.1",
        "canonical_corner_order": "centroid-angle-clockwise-start-min-x-plus-y",
        "device": device,
        "experiment_script_sha256": common.sha256(Path(__file__)),
        "common_script_sha256": common.sha256(Path(common.__file__)),
        "quad_script_sha256": common.sha256(Path(quad.__file__)),
        "pretrained_weights": False, "downloaded_weights": 0, "cloud_calls": 0,
        "production_reads": 0, "production_writes": 0, "registry_writes": 0,
        "protected_holdout_used": False, "originals_modified": False,
        "model_saved": False,
    }


def run(
    queues: list[Path], *, epochs: int, width: int, height: int, device_name: str,
    base_channels: int, depth: int, fold_count: int, sigma: float, fit: bool,
) -> dict[str, Any]:
    import torch

    (samples, images, points, heatmaps, masks, centers, center_labels,
     center_valid) = prepare_data(queues, width, height, sigma)
    if len(samples) < 8:
        raise RuntimeError("corner ROI experiment requires at least 8 independent reviewed images")
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
        model = common.build_tiny_unet(
            torch, input_channels=images.shape[-1], base_channels=base_channels,
            normalized_blocks=True, unet_depth=depth, output_channels=4,
        ).to(device)
        x = torch.from_numpy(images[train_indices].transpose(0, 3, 1, 2)).to(device)
        h = torch.from_numpy(heatmaps[train_indices]).to(device)
        p = torch.from_numpy(points[train_indices]).to(device)
        c = torch.from_numpy(centers[train_indices]).to(device)
        cl = torch.from_numpy(center_labels[train_indices]).to(device)
        cv = torch.from_numpy(center_valid[train_indices]).to(device)
        optimizer = train_model(
            torch, model, x, h, p, c, cl, cv, epochs=epochs,
            seed=20260812 + fold_index * epochs, device=device, augment=not fit,
        )
        held = torch.from_numpy(images[held_indices].transpose(0, 3, 1, 2)).to(device)
        model.eval()
        with torch.inference_mode():
            predictions = spatial_soft_argmax(torch, model(held)).cpu().numpy()
        for position, sample_index in enumerate(held_indices):
            sample = samples[sample_index]
            metrics, valid = evaluate(predictions[position], masks[sample_index], sample, width, height)
            invalid += int(not valid)
            rows.append({
                "fold": fold_index + 1, "held_out_task_id": sample["task_id"],
                "held_out_photo_id": sample["source_photo_id"],
                "sku_code": sample["sku_code"], **metrics,
            })
        del model, optimizer, x, h, p, c, cl, cv, held
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
    receipt = base_receipt(
        queues, samples, epochs=epochs, width=width, height=height,
        base_channels=base_channels, depth=depth, sigma=sigma, device=str(device),
    )
    receipt.update({
        "version": "vision-lab-work-area-roi-corner-v2",
        "mode": "training-fit-diagnostic" if fit else "task-grouped-cross-validation",
        "folds": len(splits), "split": "all-to-all" if fit else "deterministic-sku-stratified-task-k-fold",
        "training_augmentation": False if fit else "rgb-brightness-and-noise-only",
        "loss": {
            "heatmap_distribution": 1.0, "corner_coordinate": 10.0,
            "edge_vector": 2.5, "tray_center_membership": 10.0,
            "tray_center_margin_normalized": 5e-4,
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
    parser.add_argument("--sigma", type=float, default=3.0)
    parser.add_argument("--fit-diagnostic", action="store_true")
    args = parser.parse_args()
    if args.epochs < 1 or args.folds < 2 or args.sigma <= 0:
        parser.error("epochs must be positive, folds >= 2, and sigma > 0")
    receipt = run(
        args.queue, epochs=args.epochs, width=args.width, height=args.height,
        device_name=args.device, base_channels=args.base_channels, depth=args.depth,
        fold_count=args.folds, sigma=args.sigma, fit=args.fit_diagnostic,
    )
    args.runtime_root.mkdir(parents=True, exist_ok=True)
    timestamp = dt.datetime.now(dt.timezone.utc).strftime("%Y%m%dT%H%M%S%fZ")
    kind = "fit" if args.fit_diagnostic else "experiment"
    path = args.runtime_root / f"work-area-roi-corner-{kind}-{timestamp}.json"
    path.write_text(json.dumps(receipt, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"receipt": str(path), "passed": receipt["passed"],
                      "summary": receipt["summary"], "conclusion": receipt["conclusion"]},
                     ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
