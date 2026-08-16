#!/usr/bin/env python3
"""Build a human label-truth queue from remaining prospective normal flags."""
from __future__ import annotations

import argparse
import json
from pathlib import Path
from typing import Any, Sequence

from PIL import Image, ImageOps

from evaluate_candidate import sha256_file


SOURCE_VERSION = "label-qc-screening-param-normal-shadow-v1"
QUEUE_VERSION = "label-qc-prospective-normal-flag-adjudication-v1"


def crop_with_padding(
    image: Image.Image, box: Sequence[float], pad_ratio: float,
) -> tuple[Image.Image, list[int]]:
    width = max(1.0, float(box[2]) - float(box[0]))
    height = max(1.0, float(box[3]) - float(box[1]))
    x0 = max(0, int(float(box[0]) - width * pad_ratio))
    y0 = max(0, int(float(box[1]) - height * pad_ratio))
    x1 = min(image.width, int(float(box[2]) + width * pad_ratio + 0.999999))
    y1 = min(image.height, int(float(box[3]) + height * pad_ratio + 0.999999))
    if x0 >= x1 or y0 >= y1:
        raise RuntimeError("invalid prospective review crop")
    return image.crop((x0, y0, x1, y1)), [x0, y0, x1, y1]


def selected_suspects(receipt: dict[str, Any]) -> list[tuple[dict[str, Any], dict[str, Any]]]:
    selected: list[tuple[dict[str, Any], dict[str, Any]]] = []
    for row in receipt.get("details") or []:
        for suspect in (row.get("candidate") or {}).get("suspects") or []:
            selected.append((row, suspect))
    if len(selected) != int((receipt.get("candidate") or {}).get("false_flags", -1)):
        raise RuntimeError("candidate suspect details do not match summary")
    return selected


def write_json(path: Path, payload: Any) -> None:
    path.write_text(
        json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8",
    )


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--shadow-receipt", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--annotator-url", default="http://127.0.0.1:8799")
    parser.add_argument("--pad-ratio", type=float, default=0.35)
    return parser


def main() -> None:
    args = build_parser().parse_args()
    receipt_path = args.shadow_receipt.resolve()
    output = args.output.resolve()
    if not receipt_path.is_file():
        raise FileNotFoundError(receipt_path)
    if output.exists():
        raise FileExistsError(f"refusing to overwrite prospective review queue: {output}")
    if not 0.0 <= args.pad_ratio <= 1.0:
        raise ValueError("pad ratio must be between zero and one")
    receipt = json.loads(receipt_path.read_text(encoding="utf-8"))
    if receipt.get("version") != SOURCE_VERSION:
        raise RuntimeError("unsupported parameter shadow receipt")
    if receipt.get("gate", {}).get("normal_specificity_passed") is not True:
        raise RuntimeError("parameter shadow gate did not pass")
    if receipt.get("final_model_inference_started") is not False:
        raise RuntimeError("sealed final inference boundary drift")
    if receipt.get("batch", {}).get("sealed_final_independence", {}).get("disjoint") is not True:
        raise RuntimeError("source batch is not disjoint from sealed final")
    if not all(
        row.get("independent") is True
        for row in receipt.get("batch", {}).get("training_independence") or []
    ):
        raise RuntimeError("source batch is not training independent")

    for name in ("images", "prelabels", "annotations-human"):
        (output / name).mkdir(parents=True, exist_ok=False)
    rows: list[dict[str, Any]] = []
    for index, (source_row, suspect) in enumerate(selected_suspects(receipt), start=1):
        source = Path(str(source_row.get("image") or "")).resolve()
        source_sha = str(source_row.get("image_sha256") or "")
        if not source.is_file() or sha256_file(source) != source_sha:
            raise RuntimeError(f"prospective source image missing or drifted: {source}")
        with Image.open(source) as opened:
            image = ImageOps.exif_transpose(opened).convert("RGB")
        box = [float(value) for value in suspect["box"]]
        crop, crop_rect = crop_with_padding(image, box, args.pad_ratio)
        crop_id = f"prospective_{str(source_row['photo_id'])[:8]}_{index:03d}"
        crop_path = output / "images" / f"{crop_id}.jpg"
        crop.save(crop_path, format="JPEG", quality=95, optimize=True)
        write_json(output / "prelabels" / f"{crop_id}.json", {
            "crop_id": crop_id,
            "source": "screening_verdict_only_requires_full_human_label_review",
            "is_ground_truth": False,
            "boxes": [],
        })
        rows.append({
            "queue_index": index,
            "crop_id": crop_id,
            "image": str(crop_path),
            "image_sha256": sha256_file(crop_path),
            "source_photo_id": str(source_row["photo_id"]),
            "source_task_id": str(source_row["task_id"]),
            "source_reviewed_at": str(source_row["reviewed_at"]),
            "sku_code": str(source_row.get("sku_code") or ""),
            "source_image": str(source),
            "source_sha256": source_sha,
            "factory_human_photo_truth": "NO_DEFECT",
            "angle": "prospective_factory_normal_flag_adjudication",
            "label_v1_verdict": str(suspect["verdict"]),
            "tray_box_px": [round(value, 6) for value in box],
            "crop_rect_px": crop_rect,
            "tray_confidence": float(suspect["tray_confidence"]),
            "screen_has_white": bool(suspect["has_white"]),
            "screen_has_color": bool(suspect["has_color"]),
            "human_qc_verdict": "PENDING_INSTANCE_ADJUDICATION",
            "prospective_independent_before_shadow": True,
            "evaluation_consumed": True,
            "training_allowed": False,
        })
        if sha256_file(source) != source_sha:
            raise RuntimeError(f"source original changed while packing: {source}")

    if not rows:
        raise RuntimeError("parameter shadow contains no candidate flags to adjudicate")
    manifest = {
        "version": QUEUE_VERSION,
        "purpose": "human adjudication of remaining flags on prospective factory-normal photos",
        "queue_count": len(rows),
        "unique_photos": len({row["source_photo_id"] for row in rows}),
        "unique_tasks": len({row["source_task_id"] for row in rows}),
        "annotator_url": args.annotator_url,
        "shadow_receipt": str(receipt_path),
        "shadow_receipt_sha256": sha256_file(receipt_path),
        "preannotations_are_not_ground_truth": True,
        "every_image_requires_full_human_review": True,
        "annotation_contract": {
            "draw_every_visible_white_and_color_label": True,
            "confirm_exactly_one_missing_class_only_when_parent_tray_is_judgeable": True,
            "unjudgeable_for_side_view_occlusion_or_incomplete_parent": True,
            "same_photo_counts_as_at_most_one_independent_defect": True,
        },
        "protected_holdout_included": False,
        "sealed_final_included": False,
        "training_allowed": False,
        "promotion_allowed_before_human_review": False,
        "deployment_allowed": False,
        "rows": rows,
        "production_reads": 0,
        "production_writes": 0,
        "originals_modified": 0,
    }
    write_json(output / "manifest.json", manifest)
    (output / "README.md").write_text(
        "请检查中央这一个模型框出的盒子：把看得到的白标和彩标分别框出。"
        "只有父盒完整、确实少一类标签时才确认缺标；如果只是背景叠盒侧面、遮挡或看不到完整贴标面，标记为无法判断。"
        "本队列来自新工厂照片，仅作真值裁决；AI 框不是答案。\n",
        encoding="utf-8",
    )
    print(json.dumps({
        "status": "human-adjudication-required",
        "queue": str(output),
        "queue_count": len(rows),
        "unique_photos": manifest["unique_photos"],
        "unique_tasks": manifest["unique_tasks"],
        "annotator_url": args.annotator_url,
        "production_writes": 0,
        "originals_modified": 0,
    }, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
