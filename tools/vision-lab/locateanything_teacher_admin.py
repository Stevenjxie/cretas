#!/usr/bin/env python3
"""Seal, verify, and smoke-test the fixed offline LocateAnything teacher."""
from __future__ import annotations

import argparse
import json
import time
from pathlib import Path

from PIL import Image, ImageOps

from mine_tray_queue import (
    BOX_PATTERN,
    DEFAULT_TEACHER_PATH,
    TEACHER_LICENSE,
    TEACHER_MAX_SIDE,
    TEACHER_MAX_NEW_TOKENS,
    TEACHER_MODEL_ID,
    TEACHER_PROMPTS,
    TEACHER_REVISION,
    LocateAnythingTeacher,
    parse_teacher_boxes,
    seal_teacher_model,
    utc_now,
    verify_teacher_model,
    write_json,
)


SMOKE_MAX_SECONDS = 30.0
SMOKE_MAX_RAW_BOXES = 64


def parse_crop(value: str) -> tuple[int, int, int, int]:
    try:
        coordinates = tuple(int(part.strip()) for part in value.split(","))
    except ValueError as exc:
        raise argparse.ArgumentTypeError("crop must be x0,y0,x1,y1 pixels") from exc
    if len(coordinates) != 4:
        raise argparse.ArgumentTypeError("crop must contain four pixel coordinates")
    x0, y0, x1, y1 = coordinates
    if x0 < 0 or y0 < 0 or x1 <= x0 or y1 <= y0:
        raise argparse.ArgumentTypeError("crop must have positive area")
    return coordinates


def smoke(
    model_path: Path, revision: str, image_path: Path,
    crop_box: tuple[int, int, int, int], prompt_name: str, receipt_path: Path,
) -> dict[str, object]:
    integrity = verify_teacher_model(model_path, revision)
    with Image.open(image_path) as opened:
        image = ImageOps.exif_transpose(opened).convert("RGB")
    x0, y0, x1, y1 = crop_box
    if x1 > image.width or y1 > image.height:
        raise RuntimeError(f"crop exceeds image bounds {image.size}: {crop_box}")
    crop = image.crop(crop_box)
    original_crop_size = crop.size
    if max(crop.size) > TEACHER_MAX_SIDE:
        crop.thumbnail((TEACHER_MAX_SIDE, TEACHER_MAX_SIDE), Image.Resampling.LANCZOS)
    teacher = LocateAnythingTeacher(str(model_path))
    allocated_after_load = teacher.torch.cuda.memory_allocated()
    teacher.torch.cuda.reset_peak_memory_stats()
    started = time.perf_counter()
    answer = teacher.predict(crop, TEACHER_PROMPTS[prompt_name])
    elapsed = round(time.perf_counter() - started, 3)
    raw_box_count = len(BOX_PATTERN.findall(answer))
    boxes = parse_teacher_boxes(answer)
    peak_memory = teacher.torch.cuda.max_memory_allocated()
    stable = elapsed <= SMOKE_MAX_SECONDS and raw_box_count <= SMOKE_MAX_RAW_BOXES
    payload: dict[str, object] = {
        "version": "vision-lab-locateanything-smoke-v1", "created_at": utc_now(),
        "status": "passed" if stable else "failed", "model_id": TEACHER_MODEL_ID, "revision": revision,
        "license_scope": TEACHER_LICENSE, "model_integrity": integrity,
        "image": str(image_path), "crop_box_pixels": list(crop_box),
        "prompt": prompt_name,
        "original_crop_size": list(original_crop_size), "inference_size": list(crop.size),
        "crop_max_side": TEACHER_MAX_SIDE, "full_image_inference": False,
        "teacher_is_ground_truth": False, "cloud_calls": 0,
        "max_new_tokens": TEACHER_MAX_NEW_TOKENS,
        "elapsed_seconds": elapsed, "maximum_elapsed_seconds": SMOKE_MAX_SECONDS,
        "raw_box_count": raw_box_count, "maximum_raw_boxes": SMOKE_MAX_RAW_BOXES,
        "deduplicated_box_count": len(boxes), "boxes": boxes,
        "gpu_allocated_after_load_bytes": allocated_after_load,
        "gpu_peak_allocated_bytes": peak_memory, "answer": answer,
    }
    write_json(receipt_path, payload)
    if not stable:
        raise RuntimeError(f"LocateAnything crop smoke unstable; see {receipt_path}")
    return payload


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=("seal", "verify", "smoke"))
    parser.add_argument("--model-path", type=Path, default=DEFAULT_TEACHER_PATH)
    parser.add_argument("--revision", default=TEACHER_REVISION)
    parser.add_argument("--image", type=Path)
    parser.add_argument("--crop", type=parse_crop)
    parser.add_argument("--prompt", choices=tuple(TEACHER_PROMPTS), default="all")
    parser.add_argument("--receipt", type=Path)
    args = parser.parse_args()

    if args.action == "seal":
        result = seal_teacher_model(args.model_path, args.revision)
    elif args.action == "verify":
        result = verify_teacher_model(args.model_path, args.revision)
    else:
        if args.image is None or args.crop is None or args.receipt is None:
            parser.error("smoke requires --image, --crop, and --receipt")
        result = smoke(args.model_path, args.revision, args.image, args.crop, args.prompt, args.receipt)
    print(json.dumps(result, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
