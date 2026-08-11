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


def leave_one_out_splits(samples: list[dict[str, Any]]) -> list[tuple[list[int], int]]:
    return [([index for index in range(len(samples)) if index != held_out], held_out)
            for held_out in range(len(samples))]


def _mean(values: list[float]) -> float:
    return sum(values) / max(len(values), 1)


def _summarise(rows: list[dict[str, Any]]) -> dict[str, float]:
    return {
        "mean_iou": _mean([row["mask"]["iou"] for row in rows]),
        "min_iou": min(row["mask"]["iou"] for row in rows),
        "mean_dice": _mean([row["mask"]["dice"] for row in rows]),
        "mean_center_accuracy": _mean([row["centers"]["accuracy"] for row in rows]),
        "min_center_accuracy": min(row["centers"]["accuracy"] for row in rows),
        "total_center_errors": sum(int(row["centers"]["errors"]) for row in rows),
        "min_inside_recall": min(row["centers"]["inside_recall"] for row in rows),
        "min_outside_recall": min(row["centers"]["outside_recall"] for row in rows),
    }


def run_experiment(
    queue: Path, *, epochs: int, width: int, height: int, device_name: str,
) -> dict[str, Any]:
    import torch
    from torch import nn

    manifest, samples = load_samples(queue)
    if len(samples) < 8:
        raise RuntimeError("work-area experiment requires at least 8 independent reviewed images")
    torch.manual_seed(20260812)
    np.random.seed(20260812)
    random.seed(20260812)
    if device_name == "cuda" and not torch.cuda.is_available():
        raise RuntimeError("CUDA requested but unavailable")
    device = torch.device(device_name)
    images = np.stack([load_image(sample["image"], width, height) for sample in samples])
    masks = np.stack([rasterize_polygon(sample["polygon"], width, height) for sample in samples])

    class Block(nn.Module):
        def __init__(self, source_channels: int, target_channels: int) -> None:
            super().__init__()
            self.layers = nn.Sequential(
                nn.Conv2d(source_channels, target_channels, 3, padding=1), nn.ReLU(inplace=True),
                nn.Conv2d(target_channels, target_channels, 3, padding=1), nn.ReLU(inplace=True),
            )

        def forward(self, value):
            return self.layers(value)

    class TinyUNet(nn.Module):
        def __init__(self) -> None:
            super().__init__()
            self.encoder1, self.encoder2 = Block(3, 16), Block(16, 32)
            self.bottleneck = Block(32, 64)
            self.up2, self.decoder2 = nn.ConvTranspose2d(64, 32, 2, 2), Block(64, 32)
            self.up1, self.decoder1 = nn.ConvTranspose2d(32, 16, 2, 2), Block(32, 16)
            self.output = nn.Conv2d(16, 1, 1)
            self.pool = nn.MaxPool2d(2)

        def forward(self, value):
            first = self.encoder1(value)
            second = self.encoder2(self.pool(first))
            value = self.bottleneck(self.pool(second))
            value = self.decoder2(torch.cat((self.up2(value), second), dim=1))
            value = self.decoder1(torch.cat((self.up1(value), first), dim=1))
            return self.output(value)

    fold_rows: list[dict[str, Any]] = []
    baseline_rows: list[dict[str, Any]] = []
    for fold_index, (train_indices, held_out) in enumerate(leave_one_out_splits(samples)):
        torch.manual_seed(20260812 + fold_index)
        model = TinyUNet().to(device)
        optimizer = torch.optim.AdamW(model.parameters(), lr=2e-3, weight_decay=1e-4)
        x = torch.from_numpy(images[train_indices].transpose(0, 3, 1, 2)).to(device)
        y = torch.from_numpy(masks[train_indices, None].astype(np.float32)).to(device)
        for epoch in range(epochs):
            model.train()
            generator = torch.Generator(device=device).manual_seed(20260812 + fold_index * epochs + epoch)
            brightness = 0.90 + 0.20 * torch.rand((x.shape[0], 1, 1, 1), generator=generator, device=device)
            noise = 0.015 * torch.randn(x.shape, generator=generator, device=device)
            logits = model((x * brightness + noise).clamp(0, 1))
            bce = nn.functional.binary_cross_entropy_with_logits(logits, y)
            probability = logits.sigmoid()
            dice_loss = 1 - ((2 * (probability * y).sum() + 1) /
                             (probability.sum() + y.sum() + 1))
            loss = bce + dice_loss
            optimizer.zero_grad(set_to_none=True)
            loss.backward()
            optimizer.step()
        model.eval()
        held_tensor = torch.from_numpy(images[held_out].transpose(2, 0, 1)[None]).to(device)
        with torch.inference_mode():
            probability = model(held_tensor).sigmoid()[0, 0].cpu().numpy()
        predicted = probability >= 0.5
        truth = masks[held_out].astype(bool)
        average_mask = masks[train_indices].mean(axis=0) >= 0.5
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
        del model, optimizer, x, y, held_tensor
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
        "queue": str(queue),
        "queue_manifest_sha256": sha256(queue / "manifest.json"),
        "sample_count": len(samples),
        "task_count": len({sample["task_id"] for sample in samples}),
        "sku_codes": sorted({sample["sku_code"] for sample in samples}),
        "input_size": [width, height],
        "epochs_per_fold": epochs,
        "folds": len(fold_rows),
        "split": "leave-one-independent-task-out",
        "model": "tiny-unet-random-init",
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


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--queue", required=True, type=Path)
    parser.add_argument("--runtime-root", required=True, type=Path)
    parser.add_argument("--epochs", type=int, default=120)
    parser.add_argument("--width", type=int, default=DEFAULT_WIDTH)
    parser.add_argument("--height", type=int, default=DEFAULT_HEIGHT)
    parser.add_argument("--device", choices=("cuda", "cpu"), default="cuda")
    args = parser.parse_args()
    if args.epochs <= 0 or args.width % 4 or args.height % 4:
        raise ValueError("epochs must be positive and image dimensions divisible by four")
    receipt = run_experiment(
        args.queue.resolve(), epochs=args.epochs, width=args.width,
        height=args.height, device_name=args.device,
    )
    receipts = args.runtime_root / "receipts"
    receipts.mkdir(parents=True, exist_ok=True)
    stamp = dt.datetime.now(dt.timezone.utc).strftime("%Y%m%dT%H%M%S%fZ")
    path = receipts / f"work-area-roi-experiment-{stamp}.json"
    path.write_text(json.dumps(receipt, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps({"receipt": str(path), **receipt}, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
