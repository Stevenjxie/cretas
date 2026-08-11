#!/usr/bin/env python3
"""Build a protected, teacher-assisted tray annotation queue from local copies.

LocateAnything proposals and the current tray detector output are preannotations,
never ground truth. Every selected image requires a complete human box review.
"""
from __future__ import annotations

import argparse
import datetime as dt
import hashlib
import json
import math
import os
import re
import shutil
import sqlite3
from collections import Counter, defaultdict
from pathlib import Path
from typing import Any, Iterable

from PIL import Image, ImageDraw, ImageFont, ImageOps


TEACHER_MODEL_ID = "nvidia/LocateAnything-3B"
TEACHER_REVISION = "c32291ca5e996f5a7a485845b4f57a233936bba0"
TEACHER_LICENSE = "NVIDIA non-commercial research use"
TEACHER_MAX_SIDE = 1024
DEFAULT_TEACHER_PATH = Path(r"B:\AIModels\LocateAnything-3B")
TEACHER_MODEL_RECEIPT = "VISIONLAB_MODEL_RECEIPT.json"
TEACHER_PROMPTS = {
    "all": "each individual foreground sealed plastic food tray package used for quality inspection",
    "edge": "each partially visible sealed food tray at an image edge",
    "isolated": "each isolated sealed food tray, including a tray inside a blue plastic basket",
    "stacked": "each individual sealed food tray in an occluded or stacked group",
}
BOX_PATTERN = re.compile(r"<box><(\d+)><(\d+)><(\d+)><(\d+)></box>")


def utc_now() -> str:
    return dt.datetime.now(dt.timezone.utc).isoformat()


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def write_json(path: Path, payload: Any) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_suffix(path.suffix + ".tmp")
    temporary.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    temporary.replace(path)


def teacher_model_files(model_path: Path) -> list[Path]:
    index_path = model_path / "model.safetensors.index.json"
    required = [
        model_path / "config.json", model_path / "tokenizer_config.json",
        model_path / "processor_config.json", model_path / "preprocessor_config.json",
        index_path,
    ]
    if index_path.is_file():
        payload = json.loads(index_path.read_text(encoding="utf-8"))
        shards = sorted(set(payload.get("weight_map", {}).values()))
        if not shards:
            raise RuntimeError(f"teacher weight index has no shards: {index_path}")
        required.extend(model_path / shard for shard in shards)
    return required


def seal_teacher_model(model_path: Path, revision: str) -> dict[str, Any]:
    if not model_path.is_dir():
        raise FileNotFoundError(model_path)
    incomplete = sorted(str(path.relative_to(model_path)) for path in model_path.rglob("*.incomplete"))
    required = teacher_model_files(model_path)
    missing = [str(path.relative_to(model_path)) for path in required if not path.is_file()]
    if incomplete or missing:
        raise RuntimeError(f"teacher model incomplete: missing={missing}, incomplete={incomplete}")
    critical = {
        str(path.relative_to(model_path)): {"bytes": path.stat().st_size, "sha256": sha256_file(path)}
        for path in required if path.name == "model.safetensors.index.json" or path.suffix == ".safetensors"
    }
    snapshot_files = sorted(
        path for path in model_path.rglob("*") if path.is_file() and path.name != TEACHER_MODEL_RECEIPT
    )
    receipt = {
        "version": "vision-lab-locateanything-model-v1", "created_at": utc_now(),
        "model_id": TEACHER_MODEL_ID, "revision": revision, "model_path": str(model_path),
        "offline_only": True, "full_image_inference_forbidden": True,
        "crop_max_side": TEACHER_MAX_SIDE, "file_count": len(snapshot_files),
        "total_bytes": sum(path.stat().st_size for path in snapshot_files), "critical_files": critical,
    }
    write_json(model_path / TEACHER_MODEL_RECEIPT, receipt)
    return receipt


def verify_teacher_model(model_path: Path, revision: str) -> dict[str, Any]:
    receipt_path = model_path / TEACHER_MODEL_RECEIPT
    if not receipt_path.is_file():
        raise RuntimeError(f"teacher model receipt missing: {receipt_path}")
    receipt = json.loads(receipt_path.read_text(encoding="utf-8"))
    if receipt.get("model_id") != TEACHER_MODEL_ID or receipt.get("revision") != revision:
        raise RuntimeError("teacher model id or fixed revision mismatch")
    required = teacher_model_files(model_path)
    incomplete = sorted(str(path.relative_to(model_path)) for path in model_path.rglob("*.incomplete"))
    missing = [str(path.relative_to(model_path)) for path in required if not path.is_file()]
    if incomplete or missing:
        raise RuntimeError(f"teacher model incomplete: missing={missing}, incomplete={incomplete}")
    snapshot_files = sorted(
        path for path in model_path.rglob("*") if path.is_file() and path.name != TEACHER_MODEL_RECEIPT
    )
    total_bytes = sum(path.stat().st_size for path in snapshot_files)
    critical = receipt.get("critical_files") or {}
    verified: dict[str, dict[str, Any]] = {}
    for path in required:
        relative = str(path.relative_to(model_path))
        if path.name != "model.safetensors.index.json" and path.suffix != ".safetensors":
            continue
        expected = critical.get(relative)
        if not isinstance(expected, dict):
            raise RuntimeError(f"teacher receipt missing critical file: {relative}")
        actual_size = path.stat().st_size
        actual_sha = sha256_file(path)
        if actual_size != int(expected.get("bytes", -1)) or actual_sha != expected.get("sha256"):
            raise RuntimeError(f"teacher critical file mismatch: {relative}")
        verified[relative] = {"bytes": actual_size, "sha256": actual_sha}
    if len(snapshot_files) != int(receipt.get("file_count", -1)) or total_bytes != int(receipt.get("total_bytes", -1)):
        raise RuntimeError("teacher snapshot file count or total size mismatch")
    return {
        "model_id": TEACHER_MODEL_ID, "revision": revision, "model_path": str(model_path),
        "receipt": str(receipt_path), "critical_files": verified,
        "file_count": len(snapshot_files), "total_bytes": total_bytes,
        "offline_only": True, "crop_max_side": TEACHER_MAX_SIDE,
    }


def clamp(value: float, low: float = 0.0, high: float = 1.0) -> float:
    return max(low, min(high, value))


def box_area(box: list[float]) -> float:
    return max(0.0, box[2] - box[0]) * max(0.0, box[3] - box[1])


def box_iou(left: list[float], right: list[float]) -> float:
    x0, y0 = max(left[0], right[0]), max(left[1], right[1])
    x1, y1 = min(left[2], right[2]), min(left[3], right[3])
    intersection = max(0.0, x1 - x0) * max(0.0, y1 - y0)
    union = box_area(left) + box_area(right) - intersection
    return intersection / union if union else 0.0


def phash(path: Path) -> str:
    import imagehash

    with Image.open(path) as opened:
        return str(imagehash.phash(ImageOps.exif_transpose(opened), hash_size=16))


def phash_distance(left: str, right: str) -> int:
    import imagehash

    return imagehash.hex_to_hash(left) - imagehash.hex_to_hash(right)


def valid_box(box: Iterable[float], *, max_area: float = 0.60) -> list[float] | None:
    values = [clamp(float(value)) for value in box]
    if len(values) != 4 or not all(math.isfinite(value) for value in values):
        return None
    x0, y0, x1, y1 = values
    width, height = x1 - x0, y1 - y0
    if width <= 0.01 or height <= 0.01:
        return None
    area = width * height
    aspect = width / height
    if area < 0.001 or area > max_area or not 0.25 <= aspect <= 6.0:
        return None
    return [round(value, 8) for value in values]


def parse_teacher_boxes(answer: str) -> list[list[float]]:
    boxes: list[list[float]] = []
    for match in BOX_PATTERN.finditer(answer):
        values = [int(value) / 1000.0 for value in match.groups()]
        box = valid_box(values, max_area=0.35)
        if box is not None:
            boxes.append(box)
    return boxes


def deduplicate_boxes(boxes: list[dict[str, Any]], threshold: float = 0.60) -> list[dict[str, Any]]:
    retained: list[dict[str, Any]] = []
    for candidate in sorted(boxes, key=lambda row: (row["priority"], row.get("confidence", 0.0)), reverse=True):
        match = next((row for row in retained if box_iou(candidate["box"], row["box"]) >= threshold), None)
        if match is None:
            retained.append(candidate)
        else:
            match.setdefault("support", []).extend(candidate.get("support", [candidate["source"]]))
            match["support"] = sorted(set(match["support"]))
    return retained


def merge_preannotations(
    detector_boxes: list[dict[str, Any]], teacher_boxes: list[dict[str, Any]]
) -> list[dict[str, Any]]:
    proposals: list[dict[str, Any]] = []
    for row in detector_boxes:
        confidence = float(row["confidence"])
        if confidence < 0.05:
            continue
        proposals.append({
            "box": row["box"], "source": "tray_detector", "support": ["tray_detector"],
            "confidence": round(confidence, 6), "priority": 3 if confidence >= 0.20 else 2,
        })
    for row in teacher_boxes:
        support = sorted(set(row.get("support") or [row.get("prompt", "teacher")]))
        overlaps = [box_iou(row["box"], detector["box"]) for detector in detector_boxes]
        detector_support = max(overlaps, default=0.0)
        # A teacher-only proposal needs either two prompt variants or some low-floor
        # detector support. This keeps one-off full-scene hallucinations out.
        if detector_support < 0.10 and len(support) < 2:
            continue
        proposals.append({
            "box": row["box"], "source": "locateanything_teacher",
            "support": support + (["tray_detector_low_iou"] if detector_support >= 0.10 else []),
            "confidence": round(detector_support, 6), "priority": 1,
        })
    return deduplicate_boxes(proposals)


def feature_summary(boxes: list[dict[str, Any]]) -> dict[str, Any]:
    normalized = [row["box"] for row in boxes]
    centers = [((box[0] + box[2]) / 2, (box[1] + box[3]) / 2) for box in normalized]
    edge = sum(1 for box in normalized if min(box[0], box[1], 1 - box[2], 1 - box[3]) <= 0.035)
    small = sum(1 for box in normalized if box_area(box) <= 0.018)
    low_conf = sum(1 for row in boxes if float(row["confidence"]) < 0.35)
    overlapping: set[int] = set()
    isolated = 0
    for index, (center, box) in enumerate(zip(centers, normalized)):
        distances = [math.dist(center, other) for other_index, other in enumerate(centers) if other_index != index]
        if distances and min(distances) >= 0.24:
            isolated += 1
        elif not distances:
            isolated += 1
        for other_index, other in enumerate(normalized):
            if index != other_index and box_iou(box, other) >= 0.04:
                overlapping.add(index)
    tags = []
    if edge:
        tags.append("edge")
    if isolated:
        tags.append("isolated")
    if overlapping or small >= 3:
        tags.append("stacked_occluded")
    score = 2.2 * edge + 2.0 * isolated + 1.4 * len(overlapping) + 0.8 * small + 0.5 * low_conf
    return {
        "count": len(boxes), "edge_count": edge, "isolated_count": isolated,
        "stacked_or_overlap_count": len(overlapping), "small_count": small,
        "low_confidence_count": low_conf, "tags": tags, "base_risk_score": round(score, 6),
    }


def existing_photo_ids(manifests: Iterable[Path]) -> set[str]:
    values: set[str] = set()
    for path in manifests:
        if not path.is_file():
            continue
        payload = json.loads(path.read_text(encoding="utf-8"))
        rows = payload.get("rows", payload.get("records", [])) if isinstance(payload, dict) else payload
        for row in rows:
            value = row.get("source_photo_id") or row.get("photo_id")
            if value:
                values.add(str(value))
    return values


def protected_evidence(manifest_path: Path) -> dict[str, Any]:
    payload = json.loads(manifest_path.read_text(encoding="utf-8"))
    ids, hashes, perceptual = set(), set(), []
    for row in payload["records"]:
        ids.add(str(row["photo_id"]))
        hashes.add(str(row["image_sha256"]))
        image = Path(row["image"])
        if image.is_file():
            if sha256_file(image) != row["image_sha256"]:
                raise RuntimeError(f"protected holdout hash drift: {image}")
            perceptual.append((str(row["photo_id"]), phash(image)))
    return {"ids": ids, "hashes": hashes, "phashes": perceptual, "count": len(ids)}


def load_candidates(
    database: Path,
    protected: dict[str, Any],
    excluded_ids: set[str],
    max_photos: int,
    near_hamming: int,
) -> tuple[list[dict[str, Any]], Counter[str]]:
    connection = sqlite3.connect(database)
    connection.row_factory = sqlite3.Row
    excluded: Counter[str] = Counter()
    candidates: list[dict[str, Any]] = []
    try:
        rows = connection.execute("SELECT * FROM photos ORDER BY reviewed_at DESC, photo_id DESC")
        for raw in rows:
            row = dict(raw)
            photo_id, digest = str(row["photo_id"]), str(row["sha256"])
            image = Path(row["local_path"])
            if photo_id in protected["ids"]:
                excluded["protected_photo_id"] += 1
                continue
            if digest in protected["hashes"]:
                excluded["protected_sha256"] += 1
                continue
            if photo_id in excluded_ids:
                excluded["existing_queue"] += 1
                continue
            if not image.is_file() or sha256_file(image) != digest:
                excluded["missing_or_hash_drift"] += 1
                continue
            image_phash = phash(image)
            nearest = min(
                ((holdout_id, phash_distance(image_phash, other)) for holdout_id, other in protected["phashes"]),
                key=lambda item: item[1], default=None,
            )
            if nearest and nearest[1] <= near_hamming:
                excluded["protected_near_duplicate"] += 1
                continue
            row["image_phash"] = image_phash
            row["nearest_holdout_phash"] = {"photo_id": nearest[0], "distance": nearest[1]} if nearest else None
            candidates.append(row)
            if len(candidates) >= max_photos:
                break
    finally:
        connection.close()
    return candidates, excluded


def detector_predict(
    model_path: Path, records: list[dict[str, Any]], batch: int, device: str = "cpu"
) -> dict[str, list[dict[str, Any]]]:
    from ultralytics import YOLO

    model = YOLO(str(model_path), task="detect")
    paths = [row["local_path"] for row in records]
    if model_path.suffix.lower() == ".onnx":
        # The deployed export has a static batch dimension of one. Ultralytics
        # still stacks an in-memory path list even when batch=1, so call the
        # persistent session one image at a time.
        results = [
            model.predict(path, imgsz=960, conf=0.05, iou=0.40, device=device, verbose=False)[0]
            for path in paths
        ]
    else:
        results = model.predict(
            paths, imgsz=960, conf=0.05, iou=0.40, device=device,
            batch=batch, workers=0, verbose=False,
        )
    if len(results) != len(records):
        raise RuntimeError("tray detector result count mismatch")
    output: dict[str, list[dict[str, Any]]] = {}
    for record, result in zip(records, results):
        boxes = result.boxes.xyxyn.cpu().tolist() if result.boxes is not None else []
        confidences = result.boxes.conf.cpu().tolist() if result.boxes is not None else []
        output[str(record["photo_id"])] = [
            {"box": [round(float(value), 8) for value in box], "confidence": round(float(confidence), 8)}
            for box, confidence in zip(boxes, confidences)
            if valid_box(box) is not None
        ]
    return output


def detector_disagreement(
    production: list[dict[str, Any]], comparison: list[dict[str, Any]]
) -> dict[str, Any]:
    unmatched_production = sum(
        1 for row in production
        if max((box_iou(row["box"], other["box"]) for other in comparison), default=0.0) < 0.35
    )
    unmatched_comparison = sum(
        1 for row in comparison
        if max((box_iou(row["box"], other["box"]) for other in production), default=0.0) < 0.35
    )
    return {
        "production_count": len(production), "comparison_count": len(comparison),
        "unmatched_production": unmatched_production, "unmatched_comparison": unmatched_comparison,
        "count_delta": len(comparison) - len(production),
        "risk_score": round(1.5 * unmatched_production + 2.0 * unmatched_comparison, 6),
        "comparison_is_ground_truth": False,
    }


class LocateAnythingTeacher:
    def __init__(self, model_path: str) -> None:
        os.environ["HF_HUB_OFFLINE"] = "1"
        os.environ["TRANSFORMERS_OFFLINE"] = "1"
        import torch
        from transformers import AutoModel, AutoProcessor, AutoTokenizer

        options: dict[str, Any] = {"trust_remote_code": True, "local_files_only": True}
        self.device = "cuda"
        self.dtype = torch.bfloat16
        self.tokenizer = AutoTokenizer.from_pretrained(model_path, fix_mistral_regex=True, **options)
        self.processor = AutoProcessor.from_pretrained(model_path, use_fast=False, **options)
        self.model = AutoModel.from_pretrained(model_path, dtype=self.dtype, **options).to(self.device).eval()
        self.torch = torch

    def predict(self, image: Image.Image, phrase: str) -> str:
        prompt = f"Locate all the instances that match the following description: {phrase}."
        messages = [{"role": "user", "content": [
            {"type": "image", "image": image}, {"type": "text", "text": prompt},
        ]}]
        text = self.processor.py_apply_chat_template(messages, tokenize=False, add_generation_prompt=True)
        images, videos = self.processor.process_vision_info(messages)
        inputs = self.processor(text=[text], images=images, videos=videos, return_tensors="pt").to(self.device)
        with self.torch.no_grad():
            response = self.model.generate(
                pixel_values=inputs["pixel_values"].to(self.dtype), input_ids=inputs["input_ids"],
                attention_mask=inputs["attention_mask"], image_grid_hws=inputs.get("image_grid_hws"),
                tokenizer=self.tokenizer, max_new_tokens=2048, use_cache=True,
                generation_mode="hybrid", do_sample=False, repetition_penalty=1.1, verbose=False,
            )
        return str(response[0] if isinstance(response, tuple) else response)


def expand_local_crop(box: list[float], scale: float = 1.8) -> list[float] | None:
    cx, cy = (box[0] + box[2]) / 2, (box[1] + box[3]) / 2
    width, height = (box[2] - box[0]) * scale, (box[3] - box[1]) * scale
    crop = [clamp(cx - width / 2), clamp(cy - height / 2), clamp(cx + width / 2), clamp(cy + height / 2)]
    if box_area(crop) >= 0.72 or (crop[0] == 0 and crop[1] == 0 and crop[2] == 1 and crop[3] == 1):
        return None
    return [round(value, 8) for value in crop]


def blue_basket_features(image: Image.Image) -> dict[str, Any]:
    """Detect blue-basket scene context only; this never creates an object label."""
    import numpy as np

    sample = image.convert("RGB")
    sample.thumbnail((384, 384), Image.Resampling.LANCZOS)
    pixels = np.asarray(sample)
    red, green, blue = pixels[:, :, 0], pixels[:, :, 1], pixels[:, :, 2]
    mask = (blue > 80) & (blue > red * 1.18) & (blue > green * 1.08)
    ys, xs = np.where(mask)
    minimum = max(100, int(sample.width * sample.height * 0.002))
    if len(xs) < minimum:
        return {"present": False, "top_half": False, "pixel_fraction": 0.0, "box": None, "risk_score": 0.0}
    basket = [
        float(xs.min()) / sample.width, float(ys.min()) / sample.height,
        float(xs.max() + 1) / sample.width, float(ys.max() + 1) / sample.height,
    ]
    fraction = float(mask.mean())
    top_half = bool(basket[1] <= 0.45)
    risk = (14.0 if top_half else 6.0) + min(6.0, fraction * 120.0)
    return {
        "present": True, "top_half": top_half, "pixel_fraction": round(fraction, 6),
        "box": [round(value, 8) for value in basket], "risk_score": round(risk, 6),
    }


def reuse_selection_photo_ids(queue: Path) -> list[str]:
    annotations = queue / "annotations-human"
    if not annotations.is_dir():
        raise FileNotFoundError(annotations)
    photo_ids: list[str] = []
    paths = sorted(annotations.glob("*.json"), key=lambda path: int(path.stem.rsplit("_", 1)[-1]))
    for path in paths:
        payload = json.loads(path.read_text(encoding="utf-8"))
        photo_id = str(payload.get("source_photo_id") or "")
        if not photo_id or photo_id in photo_ids:
            raise RuntimeError(f"invalid reusable tray selection: {path}")
        photo_ids.append(photo_id)
    if not photo_ids:
        raise RuntimeError(f"no reusable tray selection: {queue}")
    return photo_ids


def teacher_crop_regions(record: dict[str, Any], image: Image.Image, limit: int = 8) -> list[dict[str, Any]]:
    detector = record.get("detector_boxes", [])
    comparison = record.get("comparison_boxes", [])
    centers = [((row["box"][0] + row["box"][2]) / 2, (row["box"][1] + row["box"][3]) / 2) for row in detector]
    candidates: list[dict[str, Any]] = []
    for index, row in enumerate(detector):
        box = row["box"]
        nearest = min((math.dist(centers[index], other) for other_index, other in enumerate(centers) if other_index != index), default=1.0)
        reasons = []
        if min(box[0], box[1], 1 - box[2], 1 - box[3]) <= 0.05:
            reasons.append("edge")
        if float(row["confidence"]) < 0.35:
            reasons.append("low_confidence")
        if nearest >= 0.24:
            reasons.append("isolated")
        if any(box_iou(box, other["box"]) >= 0.04 for other_index, other in enumerate(detector) if other_index != index):
            reasons.append("stacked")
        if reasons:
            crop = expand_local_crop(box)
            if crop:
                candidates.append({"box": crop, "reasons": reasons, "priority": 2 + len(reasons)})
    for row in comparison:
        if max((box_iou(row["box"], other["box"]) for other in detector), default=0.0) < 0.35:
            crop = expand_local_crop(row["box"], 2.1)
            if crop:
                candidates.append({"box": crop, "reasons": ["model_disagreement"], "priority": 5})

    # Blue baskets are a known miss pattern. This is a deterministic crop proposal,
    # not an object label and never enters training without human review.
    blue_context = blue_basket_features(image)
    if blue_context["present"]:
        crop = expand_local_crop(blue_context["box"], 1.25)
        if crop:
            candidates.append({"box": crop, "reasons": ["blue_basket"], "priority": 6})

    retained: list[dict[str, Any]] = []
    for candidate in sorted(candidates, key=lambda row: row["priority"], reverse=True):
        match = next((row for row in retained if box_iou(candidate["box"], row["box"]) >= 0.65), None)
        if match:
            match["reasons"] = sorted(set(match["reasons"] + candidate["reasons"]))
            match["priority"] = max(match["priority"], candidate["priority"])
        else:
            retained.append(candidate)
        if len(retained) >= limit:
            break
    return retained


def map_crop_box(box: list[float], crop: list[float]) -> list[float]:
    x0, y0, x1, y1 = crop
    width, height = x1 - x0, y1 - y0
    return [
        round(x0 + box[0] * width, 8), round(y0 + box[1] * height, 8),
        round(x0 + box[2] * width, 8), round(y0 + box[3] * height, 8),
    ]


def run_teacher(
    records: list[dict[str, Any]], model_path: str, revision: str,
    model_integrity: dict[str, Any], receipt_path: Path,
) -> dict[str, Any]:
    teacher = LocateAnythingTeacher(model_path)
    output: dict[str, Any] = {}
    calls = 0
    for record in records:
        with Image.open(record["local_path"]) as opened:
            image = ImageOps.exif_transpose(opened).convert("RGB")
        prompt_results = []
        grouped: list[dict[str, Any]] = []
        for crop_index, crop_spec in enumerate(teacher_crop_regions(record, image)):
            x0, y0, x1, y1 = crop_spec["box"]
            crop = image.crop((round(x0 * image.width), round(y0 * image.height), round(x1 * image.width), round(y1 * image.height)))
            if max(crop.size) > TEACHER_MAX_SIDE:
                crop.thumbnail((TEACHER_MAX_SIDE, TEACHER_MAX_SIDE), Image.Resampling.LANCZOS)
            prompt_names = ["all"]
            if "blue_basket" in crop_spec["reasons"] or "isolated" in crop_spec["reasons"]:
                prompt_names.append("isolated")
            elif "edge" in crop_spec["reasons"]:
                prompt_names.append("edge")
            elif "stacked" in crop_spec["reasons"]:
                prompt_names.append("stacked")
            for prompt_name in prompt_names:
                answer = teacher.predict(crop, TEACHER_PROMPTS[prompt_name])
                local_boxes = parse_teacher_boxes(answer)
                mapped_boxes = [map_crop_box(box, crop_spec["box"]) for box in local_boxes]
                support = f"crop{crop_index}:{prompt_name}"
                prompt_results.append({
                    "crop": crop_spec["box"], "crop_reasons": crop_spec["reasons"],
                    "prompt": prompt_name, "answer": answer, "local_boxes": local_boxes,
                    "mapped_boxes": mapped_boxes,
                })
                for box in mapped_boxes:
                    match = next((row for row in grouped if box_iou(box, row["box"]) >= 0.55), None)
                    if match is None:
                        grouped.append({"box": box, "support": [support], "prompt": prompt_name})
                    else:
                        match["support"] = sorted(set(match["support"] + [support]))
                calls += 1
        output[str(record["photo_id"])] = {"crop_prompts": prompt_results, "proposals": grouped}
        write_json(receipt_path, {
            "version": "locateanything-tray-teacher-v1", "created_at": utc_now(),
            "model_id": TEACHER_MODEL_ID, "model_revision": revision,
            "license_scope": TEACHER_LICENSE, "teacher_is_ground_truth": False,
            "teacher_scope": "local_crops_only", "full_image_inference": False,
            "teacher_max_side": TEACHER_MAX_SIDE, "cloud_calls": 0,
            "local_teacher_calls": calls, "model_integrity": model_integrity, "records": output,
        })
    return output


def select_queue(
    records: list[dict[str, Any]], queue_size: int, task_cap: int, priority_tag: str | None = None,
) -> list[dict[str, Any]]:
    selected: list[dict[str, Any]] = []
    selected_ids: set[str] = set()
    task_counts: Counter[str] = Counter()
    selected_phashes: list[str] = []

    def add(pool: list[dict[str, Any]], quota: int) -> None:
        for row in pool:
            if len(selected) >= queue_size or quota <= 0:
                return
            photo_id, task_id = str(row["photo_id"]), str(row["task_id"])
            if photo_id in selected_ids or task_counts[task_id] >= task_cap:
                continue
            if any(phash_distance(row["image_phash"], prior) <= 10 for prior in selected_phashes):
                continue
            selected.append(row)
            selected_ids.add(photo_id)
            selected_phashes.append(row["image_phash"])
            task_counts[task_id] += 1
            quota -= 1

    ordered = sorted(records, key=lambda row: row["selection_score"], reverse=True)
    # Reserve one third for human review of teacher-only additions. This is a
    # sampling quota, never permission to treat a proposal as truth.
    add([row for row in ordered if "teacher_added" in row["selection_tags"]], math.ceil(queue_size / 3))
    if priority_tag:
        add([row for row in ordered if priority_tag in row["selection_tags"]], math.ceil(queue_size * 2 / 3))
    quotas = {"edge": math.ceil(queue_size / 3), "isolated": math.ceil(queue_size / 3), "stacked_occluded": queue_size // 3}
    for tag, quota in quotas.items():
        add([row for row in ordered if tag in row["selection_tags"]], quota)
    add(ordered, queue_size - len(selected))
    if len(selected) < queue_size:
        # Relax only task diversity, never holdout or near-duplicate protection.
        task_cap += 1
        add(ordered, queue_size - len(selected))
    return selected


def yolo_line(box: list[float]) -> str:
    x0, y0, x1, y1 = box
    return f"0 {(x0+x1)/2:.8f} {(y0+y1)/2:.8f} {x1-x0:.8f} {y1-y0:.8f}\n"


def font(size: int) -> ImageFont.ImageFont:
    for path in (Path(r"C:\Windows\Fonts\msyh.ttc"), Path(r"C:\Windows\Fonts\segoeui.ttf")):
        if path.is_file():
            return ImageFont.truetype(str(path), size=size)
    return ImageFont.load_default()


def make_contact_sheets(rows: list[dict[str, Any]], output: Path) -> list[str]:
    output.mkdir(parents=True, exist_ok=True)
    paths = []
    for page, start in enumerate(range(0, len(rows), 8), 1):
        subset = rows[start:start + 8]
        canvas = Image.new("RGB", (1600, math.ceil(len(subset) / 2) * 560), (24, 24, 24))
        draw = ImageDraw.Draw(canvas)
        for index, row in enumerate(subset):
            x, y = (index % 2) * 800, (index // 2) * 560
            with Image.open(row["packed_image_absolute"]) as opened:
                image = opened.convert("RGB")
            overlay = ImageDraw.Draw(image)
            for proposal in row["preannotations"]:
                box = proposal["box"]
                color = (30, 220, 255) if proposal["source"] == "tray_detector" else (255, 180, 30)
                overlay.rectangle([box[0]*image.width, box[1]*image.height, box[2]*image.width, box[3]*image.height], outline=color, width=max(3, image.width // 500))
            image.thumbnail((780, 470), Image.Resampling.LANCZOS)
            canvas.paste(image, (x + 10, y + 10))
            draw.text((x + 10, y + 490), f"{row['photo_id'][:8]}  score={row['selection_score']:.1f}  pre={row['preannotation_count']}", fill=(245,245,245), font=font(19))
            draw.text((x + 10, y + 520), " / ".join(row["selection_tags"]), fill=(255,195,70), font=font(17))
        path = output / f"tray-queue-{page:02d}.jpg"
        canvas.save(path, quality=90, optimize=True)
        paths.append(str(path))
    return paths


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--database", required=True, type=Path)
    parser.add_argument("--tray-model", required=True, type=Path)
    parser.add_argument("--comparison-model", type=Path)
    parser.add_argument("--protected-holdout", required=True, type=Path)
    parser.add_argument("--existing-manifest", action="append", default=[], type=Path)
    parser.add_argument("--output-root", required=True, type=Path)
    parser.add_argument("--attention-root", required=True, type=Path)
    parser.add_argument("--teacher-model", default=str(DEFAULT_TEACHER_PATH))
    parser.add_argument("--teacher-revision", default=TEACHER_REVISION)
    parser.add_argument("--teacher-proposals", type=Path)
    parser.add_argument("--skip-teacher", action="store_true")
    parser.add_argument("--max-photos-scanned", type=int, default=320)
    parser.add_argument("--teacher-shortlist", type=int, default=48)
    parser.add_argument("--queue-size", type=int, default=24)
    parser.add_argument("--max-per-task", type=int, default=2)
    parser.add_argument("--detector-batch", type=int, default=8)
    parser.add_argument("--near-holdout-hamming", type=int, default=10)
    parser.add_argument("--annotator-url", default="http://127.0.0.1:8765")
    parser.add_argument("--prefer-blue-basket", action="store_true")
    parser.add_argument("--reuse-selection-from", type=Path)
    args = parser.parse_args()

    if args.queue_size <= 0 or args.teacher_shortlist < args.queue_size:
        raise ValueError("teacher-shortlist must be at least queue-size")
    manifests = list(args.existing_manifest)
    excluded_ids = existing_photo_ids(manifests)
    protected = protected_evidence(args.protected_holdout)
    candidates, excluded = load_candidates(
        args.database, protected, excluded_ids, args.max_photos_scanned, args.near_holdout_hamming,
    )
    if args.reuse_selection_from:
        reuse_ids = reuse_selection_photo_ids(args.reuse_selection_from)
        available = {str(row["photo_id"]): row for row in candidates}
        missing = [photo_id for photo_id in reuse_ids if photo_id not in available]
        if missing:
            raise RuntimeError(f"reusable tray selection is no longer eligible: {missing}")
        candidates = [available[photo_id] for photo_id in reuse_ids]
        if len(candidates) != args.queue_size:
            raise RuntimeError(
                f"reusable tray selection count {len(candidates)} does not match queue-size {args.queue_size}"
            )
    detector = detector_predict(args.tray_model, candidates, args.detector_batch, "cpu")
    comparison = (
        detector_predict(args.comparison_model, candidates, args.detector_batch, "0")
        if args.comparison_model else {}
    )
    for row in candidates:
        row["detector_boxes"] = detector[str(row["photo_id"])]
        row["detector_features"] = feature_summary(row["detector_boxes"])
        row["comparison_disagreement"] = (
            detector_disagreement(row["detector_boxes"], comparison[str(row["photo_id"])])
            if args.comparison_model else None
        )
        row["comparison_boxes"] = comparison.get(str(row["photo_id"]), [])
        if row["comparison_disagreement"]:
            row["detector_features"]["base_risk_score"] = round(
                row["detector_features"]["base_risk_score"]
                + row["comparison_disagreement"]["risk_score"], 6,
            )
        row["blue_basket_features"] = {
            "present": False, "top_half": False, "pixel_fraction": 0.0,
            "box": None, "risk_score": 0.0,
        }
        if args.prefer_blue_basket:
            with Image.open(row["local_path"]) as opened:
                row["blue_basket_features"] = blue_basket_features(ImageOps.exif_transpose(opened))
            row["detector_features"]["base_risk_score"] = round(
                row["detector_features"]["base_risk_score"]
                + row["blue_basket_features"]["risk_score"], 6,
            )
    shortlist = sorted(candidates, key=lambda row: row["detector_features"]["base_risk_score"], reverse=True)[:args.teacher_shortlist]

    stamp = dt.datetime.now(dt.timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    teacher_receipt = args.output_root.parent / "receipts" / f"tray-teacher-{stamp}.json"
    teacher_used = False
    teacher_skip_reason: str | None = None
    teacher_integrity: dict[str, Any] | None = None
    if args.skip_teacher:
        teacher_results = {}
        teacher_skip_reason = "optional teacher explicitly skipped"
    elif args.teacher_proposals:
        teacher_payload = json.loads(args.teacher_proposals.read_text(encoding="utf-8"))
        teacher_results = teacher_payload.get("records", teacher_payload)
        teacher_used = True
    else:
        teacher_path = Path(args.teacher_model)
        if not teacher_path.is_dir():
            teacher_results = {}
            teacher_skip_reason = f"offline teacher path unavailable: {teacher_path}"
        else:
            try:
                teacher_integrity = verify_teacher_model(teacher_path, args.teacher_revision)
                teacher_results = run_teacher(
                    shortlist, str(teacher_path), args.teacher_revision, teacher_integrity, teacher_receipt,
                )
                teacher_used = True
            except Exception as exc:  # teacher is explicitly non-blocking
                teacher_results = {}
                teacher_skip_reason = f"optional teacher failed: {type(exc).__name__}: {exc}"
                write_json(teacher_receipt, {
                    "version": "locateanything-tray-teacher-v1", "status": "skipped",
                    "created_at": utc_now(), "model_path": str(teacher_path),
                    "model_revision": args.teacher_revision, "offline_only": True,
                    "teacher_is_ground_truth": False, "cloud_calls": 0,
                    "reason": teacher_skip_reason,
                })

    for row in shortlist:
        teacher_row = teacher_results.get(str(row["photo_id"]), {})
        teacher_boxes = teacher_row.get("proposals", [])
        preannotations = merge_preannotations(row["detector_boxes"], teacher_boxes)
        teacher_only = sum(1 for proposal in preannotations if proposal["source"] == "locateanything_teacher")
        supported_prompts = sum(max(0, len(set(proposal.get("support", []))) - 1) for proposal in teacher_boxes)
        features = row["detector_features"]
        tags = list(features["tags"])
        if row["comparison_disagreement"] and row["comparison_disagreement"]["risk_score"] > 0:
            tags.append("model_disagreement")
        if teacher_only:
            tags.append("teacher_added")
        if row["blue_basket_features"]["present"]:
            tags.append("top_blue_basket" if row["blue_basket_features"]["top_half"] else "blue_basket")
        row["teacher"] = teacher_row
        row["preannotations"] = preannotations
        row["selection_tags"] = sorted(set(tags)) or ["low_confidence"]
        row["selection_score"] = round(features["base_risk_score"] + 5.0 * teacher_only + 0.8 * supported_prompts, 6)

    selected = select_queue(
        shortlist, args.queue_size, args.max_per_task,
        priority_tag="top_blue_basket" if args.prefer_blue_basket else None,
    )
    queue_root = args.output_root / f"tray-active-{stamp}"
    if queue_root.exists():
        raise RuntimeError(f"refusing to overwrite queue: {queue_root}")
    for name in ("images", "labels", "annotations-human", "contact-sheets"):
        (queue_root / name).mkdir(parents=True, exist_ok=False)

    rows = []
    for index, row in enumerate(selected, 1):
        stem = f"trayal_{str(row['photo_id'])[:8]}_{index:03d}"
        image_path = queue_root / "images" / f"{stem}.jpg"
        source = Path(row["local_path"])
        before_hash = sha256_file(source)
        with Image.open(source) as opened:
            image = ImageOps.exif_transpose(opened).convert("RGB")
        original_size = list(image.size)
        if max(image.size) > 2048:
            image.thumbnail((2048, 2048), Image.Resampling.LANCZOS)
        image.save(image_path, quality=94, optimize=True)
        if sha256_file(source) != before_hash:
            raise RuntimeError(f"source original changed while packing: {source}")
        label_path = queue_root / "labels" / f"{stem}.txt"
        label_path.write_text("".join(yolo_line(item["box"]) for item in row["preannotations"]), encoding="utf-8")
        annotation_path = queue_root / "annotations-human" / f"{stem}.json"
        write_json(annotation_path, {
            "photo_id": stem, "source_photo_id": str(row["photo_id"]),
            "format": "normalised_xyxy", "reviewed": False,
            "source": "tray_detector_and_locateanything_proposals_require_full_human_review",
            "boxes": [item["box"] for item in row["preannotations"]],
        })
        packed = {
            "queue_index": index, "packed_stem": stem, "packed_image": f"images/{image_path.name}",
            "packed_label": f"labels/{label_path.name}", "packed_image_absolute": str(image_path),
            "photo_id": str(row["photo_id"]), "source_photo_id": str(row["photo_id"]),
            "task_id": str(row["task_id"]), "sku_code": str(row.get("sku_code") or ""),
            "angle": "tray_risk", "source_path": str(source), "source_sha256": before_hash,
            "source_perceptual_hash": row["image_phash"], "original_size": original_size,
            "packed_size": list(image.size), "packed_image_sha256": sha256_file(image_path),
            "selection_score": row["selection_score"], "selection_tags": row["selection_tags"],
            "detector_features": row["detector_features"],
            "blue_basket_features": row["blue_basket_features"],
            "comparison_disagreement": row["comparison_disagreement"],
            "preannotations": row["preannotations"],
            "preannotation_count": len(row["preannotations"]),
            "preannotations_are_not_ground_truth": True, "manual_status": "PENDING_FULL_REVIEW",
            "nearest_holdout_phash": row["nearest_holdout_phash"], "train_only": True,
        }
        rows.append(packed)

    sheets = make_contact_sheets(rows, queue_root / "contact-sheets")
    manifest_rows = [{key: value for key, value in row.items() if key != "packed_image_absolute"} for row in rows]
    manifest = {
        "version": f"liushanmen-tray-active-{stamp}", "created_at": utc_now(),
        "purpose": "tray detector active learning with human-complete box review",
        "teacher": {
            "enabled": teacher_used, "model_id": TEACHER_MODEL_ID,
            "revision": args.teacher_revision, "license_scope": TEACHER_LICENSE,
            "is_ground_truth": False,
            "offline_only": True, "scope": "local_crops_only",
            "full_image_inference": False, "crop_max_side": TEACHER_MAX_SIDE,
            "model_path": str(args.teacher_model), "model_integrity": teacher_integrity,
            "skipped_reason": teacher_skip_reason,
        },
        "detector_model": str(args.tray_model), "detector_sha256": sha256_file(args.tray_model),
        "comparison_model": str(args.comparison_model) if args.comparison_model else None,
        "comparison_model_sha256": sha256_file(args.comparison_model) if args.comparison_model else None,
        "comparison_model_is_ground_truth": False,
        "production_reads": 0, "production_writes": 0, "cloud_calls": 0,
        "originals_modified": False, "protected_holdout_included": False,
        "protected_holdout_count": protected["count"], "near_holdout_hamming_threshold": args.near_holdout_hamming,
        "existing_queue_manifests": [str(path) for path in manifests],
        "preannotations_are_not_ground_truth": True, "every_image_requires_full_human_review": True,
        "queue_count": len(rows), "selection_counts": dict(Counter(tag for row in rows for tag in row["selection_tags"])),
        "excluded": dict(excluded), "contact_sheets": sheets, "rows": manifest_rows,
    }
    write_json(queue_root / "manifest.json", manifest)
    (queue_root / "README.md").write_text(
        "# 六扇门 tray 主动学习补框队列\n\n"
        "青色/黄色框来自现有 tray detector 与本地 LocateAnything-3B，只是预标注，不是真值。"
        "请逐张完整复核，重点补画面边缘、蓝筐内孤立、遮挡和堆叠托盘；删除背景空托盘、筐、标签纸等误框。"
        "无法判断时不要猜。所有图片都是只读原图的缩放副本，受保护 7+20 holdout 已排除。\n",
        encoding="utf-8",
    )
    mark = {
        "version": "vision-lab-tray-active-v1", "created_at": utc_now(), "status": "NEEDS_TRAY_ANNOTATION",
        "message": "tray detector 难例已预标注；请逐张完整复核，重点只需补漏框并删除明显误框。",
        "annotator_url": args.annotator_url, "queue_root": str(queue_root),
        "manifest": str(queue_root / "manifest.json"), "queue_count": len(rows),
        "teacher_is_ground_truth": False, "protected_holdout_included": False,
        "cloud_calls": 0, "production_writes": 0, "deployment_allowed": False,
    }
    write_json(args.attention_root / "MARK-NEEDS-TRAY-ANNOTATION.json", mark)
    receipt = manifest | {"stage": "tray-queue-ready", "queue_root": str(queue_root), "mark": str(args.attention_root / "MARK-NEEDS-TRAY-ANNOTATION.json")}
    receipt_path = args.output_root.parent / "receipts" / f"tray-queue-{stamp}.json"
    write_json(receipt_path, receipt)
    print(json.dumps({"status": "tray-queue-ready", "queue": str(queue_root), "mark": mark, "receipt": str(receipt_path)}, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
