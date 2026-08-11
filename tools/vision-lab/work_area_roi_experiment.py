#!/usr/bin/env python3
"""Evaluate whether reviewed work-area polygons support an image-conditioned ROI model.

This is an offline research gate. It uses random initialization, task-level
leave-one-out validation, and a position-only average-mask baseline. It never
writes the model registry or production paths.
"""
from __future__ import annotations

import argparse
import datetime as dt
import hashlib
import json
import random
from pathlib import Path
from typing import Any

import numpy as np
from PIL import Image, ImageDraw, ImageOps

import work_area


DEFAULT_WIDTH = 192
DEFAULT_HEIGHT = 256


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


def load_samples(queue: Path) -> tuple[dict[str, Any], list[dict[str, Any]]]:
    manifest_path = queue / "manifest.json"
    manifest = load_json(manifest_path)
    rows = manifest.get("rows") or []
    if manifest.get("protected_holdout_included") or len(rows) != int(manifest.get("queue_count", -1)):
        raise RuntimeError("unsafe or incomplete work-area experiment queue")
    samples: list[dict[str, Any]] = []
    for row in rows:
        stem = str(row["packed_stem"])
        image = queue / str(row["packed_image"])
        tray_path = queue / "annotations-human" / f"{stem}.json"
        roi_path = queue / "work-area-human" / f"{stem}.json"
        if not image.is_file() or sha256(image) != row.get("packed_image_sha256"):
            raise RuntimeError(f"packed image drift: {image}")
        source = Path(str(row["source_path"]))
        if not source.is_file() or sha256(source) != row.get("source_sha256"):
            raise RuntimeError(f"source image drift: {source}")
        tray = load_json(tray_path)
        if tray.get("reviewed") is not True or tray.get("source") != "human":
            raise RuntimeError(f"tray truth is not human-reviewed: {tray_path}")
        boxes = tray.get("boxes") or []
        if not boxes:
            raise RuntimeError(f"tray truth has no boxes: {tray_path}")
        roi = work_area.validate_human_annotation(load_json(roi_path), expected_photo_id=stem)
        expected = {
            "source_photo_id": str(row.get("source_photo_id") or stem),
            "source_sha256": str(row.get("source_sha256") or ""),
            "packed_image_sha256": str(row.get("packed_image_sha256") or ""),
        }
        if not roi["judgeable"] or any(roi.get(key) != value for key, value in expected.items()):
            raise RuntimeError(f"ROI truth is unjudgeable or mismatched: {roi_path}")
        samples.append({
            "stem": stem,
            "source_photo_id": expected["source_photo_id"],
            "task_id": str(row.get("task_id") or "unknown"),
            "sku_code": str(row.get("sku_code") or "unknown"),
            "image": image,
            "image_sha256": sha256(image),
            "source_sha256": expected["source_sha256"],
            "roi_path": roi_path,
            "roi_sha256": sha256(roi_path),
            "polygon": roi["polygon"],
            "boxes": boxes,
        })
    if len({sample["task_id"] for sample in samples}) != len(samples):
        raise RuntimeError("work-area experiment requires one independent task per image")
    return manifest, samples


def load_combined_samples(
    queues: list[Path],
) -> tuple[list[dict[str, Any]], list[dict[str, Any]]]:
    if not queues:
        raise ValueError("at least one work-area queue is required")
    manifests: list[dict[str, Any]] = []
    samples: list[dict[str, Any]] = []
    for queue in queues:
        manifest, queue_samples = load_samples(queue)
        manifests.append(manifest)
        samples.extend(queue_samples)
    for key in ("stem", "source_photo_id", "task_id"):
        values = [str(sample[key]) for sample in samples]
        if len(set(values)) != len(values):
            raise RuntimeError(f"combined work-area queues contain duplicate {key}")
    return manifests, samples


def rasterize_polygon(polygon: list[list[float]], width: int, height: int) -> np.ndarray:
    mask = Image.new("L", (width, height), 0)
    points = [
        (round(point[0] * (width - 1)), round(point[1] * (height - 1)))
        for point in work_area.validate_polygon(polygon)
    ]
    ImageDraw.Draw(mask).polygon(points, fill=1)
    return np.asarray(mask, dtype=np.uint8)


def load_image(path: Path, width: int, height: int) -> np.ndarray:
    with Image.open(path) as opened:
        image = ImageOps.exif_transpose(opened).convert("RGB").resize(
            (width, height), Image.Resampling.BILINEAR,
        )
    return np.asarray(image, dtype=np.float32) / 255.0


def add_coordinate_channels(images: np.ndarray) -> np.ndarray:
    if images.ndim != 4:
        raise ValueError("expected NHWC image batch")
    count, height, width, _channels = images.shape
    x = np.linspace(-1.0, 1.0, width, dtype=np.float32)
    y = np.linspace(-1.0, 1.0, height, dtype=np.float32)
    xx, yy = np.meshgrid(x, y)
    coordinates = np.stack((xx, yy), axis=-1)[None]
    return np.concatenate((images, np.repeat(coordinates, count, axis=0)), axis=-1)


def mask_metrics(predicted: np.ndarray, truth: np.ndarray) -> dict[str, float]:
    predicted = predicted.astype(bool)
    truth = truth.astype(bool)
    intersection = int(np.logical_and(predicted, truth).sum())
    union = int(np.logical_or(predicted, truth).sum())
    predicted_count, truth_count = int(predicted.sum()), int(truth.sum())
    return {
        "iou": intersection / max(union, 1),
        "dice": 2 * intersection / max(predicted_count + truth_count, 1),
        "area_error": abs(predicted_count - truth_count) / max(truth_count, 1),
    }


def center_metrics(
    predicted: np.ndarray, truth: np.ndarray, boxes: list[list[float]],
) -> dict[str, Any]:
    height, width = truth.shape
    labels: list[tuple[bool, bool]] = []
    for box in boxes:
        x0, y0, x1, y1 = work_area.validate_box(box)
        x = min(width - 1, max(0, round(((x0 + x1) / 2) * (width - 1))))
        y = min(height - 1, max(0, round(((y0 + y1) / 2) * (height - 1))))
        labels.append((bool(predicted[y, x]), bool(truth[y, x])))
    correct = sum(predicted_label == truth_label for predicted_label, truth_label in labels)
    inside = [pair for pair in labels if pair[1]]
    outside = [pair for pair in labels if not pair[1]]
    return {
        "total": len(labels),
        "accuracy": correct / max(len(labels), 1),
        "inside_total": len(inside),
        "inside_recall": sum(pair[0] for pair in inside) / max(len(inside), 1),
        "outside_total": len(outside),
        "outside_recall": sum(not pair[0] for pair in outside) / max(len(outside), 1),
        "errors": len(labels) - correct,
    }


def center_supervision_mask(
    samples: list[dict[str, Any]], width: int, height: int,
) -> np.ndarray:
    mask = np.zeros((len(samples), 1, height, width), dtype=bool)
    for sample_index, sample in enumerate(samples):
        for box in sample["boxes"]:
            x0, y0, x1, y1 = work_area.validate_box(box)
            x = min(width - 1, max(0, round(((x0 + x1) / 2) * (width - 1))))
            y = min(height - 1, max(0, round(((y0 + y1) / 2) * (height - 1))))
            mask[sample_index, 0, y, x] = True
    return mask


def leave_one_out_splits(samples: list[dict[str, Any]]) -> list[tuple[list[int], int]]:
    return [([index for index in range(len(samples)) if index != held_out], held_out)
            for held_out in range(len(samples))]


def task_grouped_splits(
    samples: list[dict[str, Any]], fold_count: int, *, seed: int = 20260812,
) -> list[tuple[list[int], list[int]]]:
    if fold_count == 0 or fold_count >= len(samples):
        return [(train, [held_out]) for train, held_out in leave_one_out_splits(samples)]
    if fold_count < 2:
        raise ValueError("fold count must be zero or at least two")
    by_sku: dict[str, list[int]] = {}
    for index, sample in enumerate(samples):
        by_sku.setdefault(str(sample["sku_code"]), []).append(index)
    held_by_fold: list[list[int]] = [[] for _ in range(fold_count)]
    offset = 0
    for sku_code in sorted(by_sku):
        indices = sorted(by_sku[sku_code], key=lambda index: str(samples[index]["task_id"]))
        random.Random(f"{seed}:{sku_code}").shuffle(indices)
        for position, index in enumerate(indices):
            held_by_fold[(offset + position) % fold_count].append(index)
        offset = (offset + len(indices)) % fold_count
    all_indices = set(range(len(samples)))
    return [
        (sorted(all_indices - set(held)), sorted(held))
        for held in held_by_fold if held
    ]


def _mean(values: list[float]) -> float:
    return sum(values) / max(len(values), 1)


def _summarise(rows: list[dict[str, Any]]) -> dict[str, float]:
    inside_rows = [row for row in rows if row["centers"]["inside_total"] > 0]
    outside_rows = [row for row in rows if row["centers"]["outside_total"] > 0]
    return {
        "mean_iou": _mean([row["mask"]["iou"] for row in rows]),
        "min_iou": min(row["mask"]["iou"] for row in rows),
        "mean_dice": _mean([row["mask"]["dice"] for row in rows]),
        "mean_center_accuracy": _mean([row["centers"]["accuracy"] for row in rows]),
        "min_center_accuracy": min(row["centers"]["accuracy"] for row in rows),
        "total_center_errors": sum(int(row["centers"]["errors"]) for row in rows),
        "min_inside_recall": min(
            (row["centers"]["inside_recall"] for row in inside_rows), default=1.0,
        ),
        "min_outside_recall": min(
            (row["centers"]["outside_recall"] for row in outside_rows), default=1.0,
        ),
    }


def build_tiny_unet(
    torch, input_channels: int = 3, base_channels: int = 16,
    normalized_blocks: bool = False, unet_depth: int = 2,
):
    nn = torch.nn
    if unet_depth < 2:
        raise ValueError("U-Net depth must be at least two")

    class Block(nn.Module):
        def __init__(self, source_channels: int, target_channels: int) -> None:
            super().__init__()
            if normalized_blocks:
                groups = min(8, target_channels)
                while target_channels % groups:
                    groups -= 1
                self.layers = nn.Sequential(
                    nn.Conv2d(source_channels, target_channels, 3, padding=1, bias=False),
                    nn.GroupNorm(groups, target_channels), nn.SiLU(inplace=True),
                    nn.Conv2d(target_channels, target_channels, 3, padding=1, bias=False),
                    nn.GroupNorm(groups, target_channels), nn.SiLU(inplace=True),
                )
            else:
                self.layers = nn.Sequential(
                    nn.Conv2d(source_channels, target_channels, 3, padding=1),
                    nn.ReLU(inplace=True),
                    nn.Conv2d(target_channels, target_channels, 3, padding=1),
                    nn.ReLU(inplace=True),
                )

        def forward(self, value):
            return self.layers(value)

    class TinyUNet(nn.Module):
        def __init__(self) -> None:
            super().__init__()
            channels = [base_channels * (2 ** index) for index in range(unet_depth)]
            self.encoders = nn.ModuleList()
            source_channels = input_channels
            for target_channels in channels:
                self.encoders.append(Block(source_channels, target_channels))
                source_channels = target_channels
            bottleneck_channels = channels[-1] * 2
            self.bottleneck = Block(channels[-1], bottleneck_channels)
            self.ups = nn.ModuleList()
            self.decoders = nn.ModuleList()
            source_channels = bottleneck_channels
            for target_channels in reversed(channels):
                self.ups.append(nn.ConvTranspose2d(source_channels, target_channels, 2, 2))
                self.decoders.append(Block(target_channels * 2, target_channels))
                source_channels = target_channels
            self.output = nn.Conv2d(base_channels, 1, 1)
            self.pool = nn.MaxPool2d(2)

        def forward(self, value):
            skips = []
            for encoder in self.encoders:
                value = encoder(value)
                skips.append(value)
                value = self.pool(value)
            value = self.bottleneck(value)
            for up, decoder, skip in zip(self.ups, self.decoders, reversed(skips)):
                value = decoder(torch.cat((up(value), skip), dim=1))
            return self.output(value)

    return TinyUNet()


def mean_per_image_dice_loss(probability, truth):
    reduce_dimensions = tuple(range(1, probability.ndim))
    intersection = (probability * truth).sum(dim=reduce_dimensions)
    predicted_area = probability.sum(dim=reduce_dimensions)
    truth_area = truth.sum(dim=reduce_dimensions)
    dice = (2 * intersection + 1) / (predicted_area + truth_area + 1)
    return (1 - dice).mean()


def train_model(
    torch, model, x, y, *, epochs: int, seed: int, device,
    center_mask=None, center_loss_weight: float = 1.0, augment: bool = True,
):
    nn = torch.nn
    optimizer = torch.optim.AdamW(model.parameters(), lr=2e-3, weight_decay=1e-4)
    has_center_supervision = (
        center_mask is not None and center_mask.numel() > 0 and bool(center_mask.any())
    )
    for epoch in range(epochs):
        model.train()
        if augment:
            generator = torch.Generator(device=device).manual_seed(seed + epoch)
            brightness = 0.90 + 0.20 * torch.rand(
                (x.shape[0], 1, 1, 1), generator=generator, device=device,
            )
            rgb = x[:, :3]
            noise = 0.015 * torch.randn(rgb.shape, generator=generator, device=device)
            augmented_rgb = (rgb * brightness + noise).clamp(0, 1)
            model_input = (
                torch.cat((augmented_rgb, x[:, 3:]), dim=1)
                if x.shape[1] > 3 else augmented_rgb
            )
        else:
            model_input = x
        logits = model(model_input)
        bce = nn.functional.binary_cross_entropy_with_logits(logits, y)
        probability = logits.sigmoid()
        dice_loss = mean_per_image_dice_loss(probability, y)
        center_loss = (
            nn.functional.binary_cross_entropy_with_logits(logits[center_mask], y[center_mask])
            if has_center_supervision else 0.0
        )
        loss = bce + dice_loss + center_loss_weight * center_loss
        optimizer.zero_grad(set_to_none=True)
        loss.backward()
        optimizer.step()
    return optimizer


def run_experiment(
    queues: list[Path], *, epochs: int, width: int, height: int, device_name: str,
    coordinate_channels: bool = False, base_channels: int = 16, fold_count: int = 0,
    normalized_blocks: bool = False, center_loss_weight: float = 0.05,
    unet_depth: int = 2,
) -> dict[str, Any]:
    import torch
    _manifests, samples = load_combined_samples(queues)
    if len(samples) < 8:
        raise RuntimeError("work-area experiment requires at least 8 independent reviewed images")
    torch.manual_seed(20260812)
    np.random.seed(20260812)
    random.seed(20260812)
    if device_name == "cuda" and not torch.cuda.is_available():
        raise RuntimeError("CUDA requested but unavailable")
    device = torch.device(device_name)
    images = np.stack([load_image(sample["image"], width, height) for sample in samples])
    if coordinate_channels:
        images = add_coordinate_channels(images)
    masks = np.stack([rasterize_polygon(sample["polygon"], width, height) for sample in samples])
    center_masks = center_supervision_mask(samples, width, height)

    fold_rows: list[dict[str, Any]] = []
    baseline_rows: list[dict[str, Any]] = []
    splits = task_grouped_splits(samples, fold_count)
    for fold_index, (train_indices, held_out_indices) in enumerate(splits):
        torch.manual_seed(20260812 + fold_index)
        model = build_tiny_unet(
            torch, images.shape[-1], base_channels, normalized_blocks, unet_depth,
        ).to(device)
        x = torch.from_numpy(images[train_indices].transpose(0, 3, 1, 2)).to(device)
        y = torch.from_numpy(masks[train_indices, None].astype(np.float32)).to(device)
        train_center_mask = torch.from_numpy(center_masks[train_indices]).to(device)
        optimizer = train_model(
            torch, model, x, y, epochs=epochs,
            seed=20260812 + fold_index * epochs, device=device,
            center_mask=train_center_mask, center_loss_weight=center_loss_weight,
        )
        model.eval()
        held_tensor = torch.from_numpy(
            images[held_out_indices].transpose(0, 3, 1, 2),
        ).to(device)
        with torch.inference_mode():
            probabilities = model(held_tensor).sigmoid()[:, 0].cpu().numpy()
        average_mask = masks[train_indices].mean(axis=0) >= 0.5
        for held_position, held_out in enumerate(held_out_indices):
            probability = probabilities[held_position]
            predicted = probability >= 0.5
            truth = masks[held_out].astype(bool)
            sample = samples[held_out]
            fold_rows.append({
                "fold": fold_index + 1, "held_out_task_id": sample["task_id"],
                "held_out_photo_id": sample["source_photo_id"], "sku_code": sample["sku_code"],
                "mask": mask_metrics(predicted, truth),
                "centers": center_metrics(predicted, truth, sample["boxes"]),
                "mean_foreground_probability": float(probability.mean()),
            })
            baseline_rows.append({
                "fold": fold_index + 1, "held_out_task_id": sample["task_id"],
                "held_out_photo_id": sample["source_photo_id"], "sku_code": sample["sku_code"],
                "mask": mask_metrics(average_mask, truth),
                "centers": center_metrics(average_mask, truth, sample["boxes"]),
            })
        del model, optimizer, x, y, train_center_mask, held_tensor
        if device.type == "cuda":
            torch.cuda.empty_cache()
    model_summary, baseline_summary = _summarise(fold_rows), _summarise(baseline_rows)
    image_conditioned_gain = model_summary["mean_iou"] - baseline_summary["mean_iou"]
    offline_cv_passed = (
        model_summary["min_iou"] >= 0.85
        and model_summary["min_center_accuracy"] >= 0.98
        and model_summary["min_inside_recall"] >= 0.98
        and model_summary["min_outside_recall"] >= 0.95
        and image_conditioned_gain >= 0.02
    )
    return {
        "version": "vision-lab-work-area-roi-experiment-v1",
        "created_at": dt.datetime.now(dt.timezone.utc).isoformat(),
        "queues": [str(queue) for queue in queues],
        "queue_manifest_sha256s": {
            str(queue): sha256(queue / "manifest.json") for queue in queues
        },
        "sample_count": len(samples),
        "task_count": len({sample["task_id"] for sample in samples}),
        "sku_codes": sorted({sample["sku_code"] for sample in samples}),
        "input_size": [width, height],
        "epochs_per_fold": epochs,
        "folds": len(splits),
        "split": (
            "leave-one-independent-task-out"
            if len(splits) == len(samples) else "deterministic-sku-stratified-task-k-fold"
        ),
        "model": "tiny-unet-coordconv-random-init" if coordinate_channels else "tiny-unet-random-init",
        "coordinate_channels": coordinate_channels,
        "base_channels": base_channels,
        "unet_depth": unet_depth,
        "normalized_blocks": normalized_blocks,
        "tray_center_loss_weight": center_loss_weight,
        "experiment_script_sha256": sha256(Path(__file__)),
        "pretrained_weights": False,
        "downloaded_weights": 0,
        "cloud_calls": 0,
        "production_reads": 0,
        "production_writes": 0,
        "registry_writes": 0,
        "protected_holdout_used": False,
        "originals_modified": False,
        "device": str(device),
        "model_cv": {"summary": model_summary, "folds": fold_rows},
        "position_only_baseline": {"summary": baseline_summary, "folds": baseline_rows},
        "image_conditioned_mean_iou_gain": image_conditioned_gain,
        "offline_cv_thresholds": {
            "min_iou": 0.85, "min_center_accuracy": 0.98,
            "min_inside_recall": 0.98, "min_outside_recall": 0.95,
            "min_iou_gain_over_position_baseline": 0.02,
        },
        "offline_cv_passed": offline_cv_passed,
        "sufficient_for_production": False,
        "deployment_authorized": False,
        "conclusion": (
            "offline_candidate_signal_only_locked_test_still_required"
            if offline_cv_passed else "insufficient_image_conditioned_roi_evidence"
        ),
        "samples": [{
            "source_photo_id": sample["source_photo_id"], "task_id": sample["task_id"],
            "sku_code": sample["sku_code"], "image_sha256": sample["image_sha256"],
            "source_sha256": sample["source_sha256"], "roi_sha256": sample["roi_sha256"],
        } for sample in samples],
    }


def run_fit_diagnostic(
    queues: list[Path], *, epochs: int, width: int, height: int, device_name: str,
    coordinate_channels: bool = False, base_channels: int = 16,
    normalized_blocks: bool = False, center_loss_weight: float = 0.05,
    unet_depth: int = 2,
) -> dict[str, Any]:
    import torch

    _manifests, samples = load_combined_samples(queues)
    torch.manual_seed(20260812)
    np.random.seed(20260812)
    random.seed(20260812)
    if device_name == "cuda" and not torch.cuda.is_available():
        raise RuntimeError("CUDA requested but unavailable")
    device = torch.device(device_name)
    images = np.stack([load_image(sample["image"], width, height) for sample in samples])
    if coordinate_channels:
        images = add_coordinate_channels(images)
    masks = np.stack([rasterize_polygon(sample["polygon"], width, height) for sample in samples])
    center_masks = center_supervision_mask(samples, width, height)
    x = torch.from_numpy(images.transpose(0, 3, 1, 2)).to(device)
    y = torch.from_numpy(masks[:, None].astype(np.float32)).to(device)
    train_center_mask = torch.from_numpy(center_masks).to(device)
    model = build_tiny_unet(
        torch, images.shape[-1], base_channels, normalized_blocks, unet_depth,
    ).to(device)
    optimizer = train_model(
        torch, model, x, y, epochs=epochs, seed=20260812, device=device,
        center_mask=train_center_mask, center_loss_weight=center_loss_weight, augment=False,
    )
    model.eval()
    with torch.inference_mode():
        probabilities = model(x).sigmoid()[:, 0].cpu().numpy()
    rows = []
    for index, sample in enumerate(samples):
        predicted, truth = probabilities[index] >= 0.5, masks[index].astype(bool)
        rows.append({
            "task_id": sample["task_id"], "source_photo_id": sample["source_photo_id"],
            "sku_code": sample["sku_code"], "mask": mask_metrics(predicted, truth),
            "centers": center_metrics(predicted, truth, sample["boxes"]),
        })
    summary = _summarise(rows)
    fit_passed = (
        summary["min_iou"] >= 0.95
        and summary["min_center_accuracy"] >= 0.99
        and summary["min_inside_recall"] >= 0.99
        and summary["min_outside_recall"] >= 0.99
    )
    del model, optimizer, x, y, train_center_mask
    if device.type == "cuda":
        torch.cuda.empty_cache()
    return {
        "version": "vision-lab-work-area-roi-fit-diagnostic-v1",
        "created_at": dt.datetime.now(dt.timezone.utc).isoformat(),
        "queues": [str(queue) for queue in queues],
        "queue_manifest_sha256s": {
            str(queue): sha256(queue / "manifest.json") for queue in queues
        },
        "sample_count": len(samples), "task_count": len(samples),
        "epochs": epochs,
        "model": "tiny-unet-coordconv-random-init" if coordinate_channels else "tiny-unet-random-init",
        "coordinate_channels": coordinate_channels, "device": str(device),
        "base_channels": base_channels,
        "unet_depth": unet_depth,
        "normalized_blocks": normalized_blocks,
        "tray_center_loss_weight": center_loss_weight,
        "experiment_script_sha256": sha256(Path(__file__)),
        "training_augmentation": False,
        "summary": summary, "rows": rows,
        "fit_thresholds": {
            "min_iou": 0.95, "min_center_accuracy": 0.99,
            "min_inside_recall": 0.99, "min_outside_recall": 0.99,
        },
        "training_fit_passed": fit_passed,
        "diagnosis": "generalization_data_bottleneck" if fit_passed else "model_or_optimization_bottleneck",
        "pretrained_weights": False, "downloaded_weights": 0, "cloud_calls": 0,
        "production_reads": 0, "production_writes": 0, "registry_writes": 0,
        "protected_holdout_used": False, "model_saved": False,
    }


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--queue", required=True, action="append", type=Path)
    parser.add_argument("--runtime-root", required=True, type=Path)
    parser.add_argument("--epochs", type=int, default=120)
    parser.add_argument("--width", type=int, default=DEFAULT_WIDTH)
    parser.add_argument("--height", type=int, default=DEFAULT_HEIGHT)
    parser.add_argument("--device", choices=("cuda", "cpu"), default="cuda")
    parser.add_argument("--fit-diagnostic", action="store_true")
    parser.add_argument("--coordinate-channels", action="store_true")
    parser.add_argument("--base-channels", type=int, choices=(8, 16, 24, 32), default=16)
    parser.add_argument("--unet-depth", type=int, choices=(2, 3, 4), default=2)
    parser.add_argument("--normalized-blocks", action="store_true")
    parser.add_argument("--center-loss-weight", type=float, default=0.05)
    parser.add_argument("--folds", type=int, default=0,
                        help="0 means leave-one-task-out; otherwise use task-grouped K-fold")
    args = parser.parse_args()
    if args.epochs <= 0 or args.width % 4 or args.height % 4:
        raise ValueError("epochs must be positive and image dimensions divisible by four")
    if args.folds == 1 or args.folds < 0:
        raise ValueError("folds must be zero or at least two")
    if args.center_loss_weight < 0:
        raise ValueError("center loss weight must be non-negative")
    queues = [queue.resolve() for queue in args.queue]
    runner = run_fit_diagnostic if args.fit_diagnostic else run_experiment
    receipt = runner(
        queues, epochs=args.epochs, width=args.width,
        height=args.height, device_name=args.device,
        coordinate_channels=args.coordinate_channels,
        base_channels=args.base_channels,
        unet_depth=args.unet_depth,
        normalized_blocks=args.normalized_blocks,
        center_loss_weight=args.center_loss_weight,
        **({"fold_count": args.folds} if not args.fit_diagnostic else {}),
    )
    receipts = args.runtime_root / "receipts"
    receipts.mkdir(parents=True, exist_ok=True)
    stamp = dt.datetime.now(dt.timezone.utc).strftime("%Y%m%dT%H%M%S%fZ")
    kind = "fit-diagnostic" if args.fit_diagnostic else "experiment"
    path = receipts / f"work-area-roi-{kind}-{stamp}.json"
    path.write_text(json.dumps(receipt, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps({"receipt": str(path), **receipt}, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
