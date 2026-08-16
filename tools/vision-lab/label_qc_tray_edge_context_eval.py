#!/usr/bin/env python3
"""Evaluate a bounded lower-edge tray zoom path with an offline tray candidate.

Unlike full-image SAHI, this adds only two fixed lower-corner context windows,
rejects detections touching internal crop borders, and keeps detections whose
centres remain in the corresponding outer lower band.  It is an offline A/B;
the protected target is never used to choose a window at runtime.
"""
from __future__ import annotations

import argparse
import json
import shutil
import sys
import tempfile
from pathlib import Path
from typing import Any, Sequence

import label_qc_oracle_diagnostics as diagnostics


TARGET_PHOTO_ID = "locked-test-real_9827ccc7-7b30-48eb-bff2-c2493be09660"


def edge_regions(
    width: int, height: int, width_fraction: float, height_fraction: float,
) -> list[tuple[str, int, int, int, int]]:
    if not (0.25 <= width_fraction <= 0.75 and 0.25 <= height_fraction <= 0.75):
        raise ValueError("edge window fractions must be in [0.25, 0.75]")
    window_width = max(1, round(width * width_fraction))
    window_height = max(1, round(height * height_fraction))
    y0 = height - window_height
    return [
        ("lower_left", 0, y0, window_width, height),
        ("lower_right", width - window_width, y0, width, height),
    ]


def belongs_to_outer_band(
    box: Sequence[float], source: str, width: int, height: int,
    maximum_left_center: float, minimum_right_center: float, minimum_y_center: float,
) -> bool:
    center_x = (float(box[0]) + float(box[2])) / 2 / max(width, 1)
    center_y = (float(box[1]) + float(box[3])) / 2 / max(height, 1)
    horizontal = (
        center_x <= maximum_left_center if source == "lower_left"
        else center_x >= minimum_right_center
    )
    return horizontal and center_y >= minimum_y_center


class EdgeContextTrayModels:
    def __init__(
        self,
        base: Any,
        detection_type: type,
        edge_confidence: float = 0.30,
        width_fraction: float = 0.50,
        height_fraction: float = 0.60,
        maximum_left_center: float = 0.40,
        minimum_right_center: float = 0.60,
        minimum_y_center: float = 0.55,
        iou_threshold: float = 0.50,
        ios_threshold: float = 0.80,
    ) -> None:
        self.base = base
        self.detection_type = detection_type
        self.edge_confidence = edge_confidence
        self.width_fraction = width_fraction
        self.height_fraction = height_fraction
        self.maximum_left_center = maximum_left_center
        self.minimum_right_center = minimum_right_center
        self.minimum_y_center = minimum_y_center
        self.iou_threshold = iou_threshold
        self.ios_threshold = ios_threshold
        self.last_stats: dict[str, Any] = {}
        self.last_raw_detections: list[dict[str, Any]] = []

    def detect_trays(self, image, conf: float) -> list[Any]:
        height, width = image.shape[:2]
        full = list(self.base.detect_trays(image, conf))
        all_detections = list(full)
        raw_rows = [diagnostics._detection_dict(row, "full") for row in full]
        edge_raw = edge_accepted = edge_rejected = internal_edge_rejected = 0
        for name, x0, y0, x1, y1 in edge_regions(
            width, height, self.width_fraction, self.height_fraction,
        ):
            tile = image[y0:y1, x0:x1]
            for detection in self.base.detect_trays(tile, self.edge_confidence):
                edge_raw += 1
                touches_internal_edge = (
                    (x0 > 0 and float(detection.x0) <= 2)
                    or (y0 > 0 and float(detection.y0) <= 2)
                    or (x1 < width and float(detection.x1) >= tile.shape[1] - 2)
                )
                if touches_internal_edge:
                    internal_edge_rejected += 1
                    continue
                mapped = self.detection_type(
                    x0=float(detection.x0) + x0,
                    y0=float(detection.y0) + y0,
                    x1=float(detection.x1) + x0,
                    y1=float(detection.y1) + y0,
                    confidence=float(detection.confidence),
                    class_id=int(detection.class_id),
                )
                if not belongs_to_outer_band(
                    mapped.as_xyxy(), name, width, height,
                    self.maximum_left_center, self.minimum_right_center, self.minimum_y_center,
                ):
                    edge_rejected += 1
                    continue
                all_detections.append(mapped)
                raw_rows.append(diagnostics._detection_dict(mapped, name))
                edge_accepted += 1
        kept = diagnostics.deduplicate_detections(
            all_detections, self.iou_threshold, self.ios_threshold,
        )
        self.last_raw_detections = raw_rows
        self.last_stats = {
            "mode": "candidate_full_plus_two_lower_edge_windows",
            "tiles": 2,
            "full_frame_detections": len(full),
            "edge_raw_detections": edge_raw,
            "edge_accepted_before_dedup": edge_accepted,
            "edge_outer_band_rejected": edge_rejected,
            "edge_internal_border_rejected": internal_edge_rejected,
            "raw_detections": len(all_detections),
            "kept_detections": len(kept),
            "duplicates_removed": len(all_detections) - len(kept),
            "tile_edge_detections": internal_edge_rejected,
        }
        return kept

    def detect_labels(self, crop, conf: float) -> list[Any]:
        return self.base.detect_labels(crop, conf)


def load_and_verify(path: Path, expected_hash: str | None = None) -> dict[str, Any]:
    if not path.is_file():
        raise FileNotFoundError(path)
    if expected_hash and diagnostics.sha256_file(path) != expected_hash:
        raise RuntimeError(f"hash drift: {path}")
    payload = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(payload, dict):
        raise ValueError(f"expected object: {path}")
    return payload


def corrected_gate(
    baseline: dict[str, Any], candidate: dict[str, Any], occlusion_audit: dict[str, Any],
    parity_mismatches: int, production_parity_mismatches: int,
) -> dict[str, Any]:
    baseline_rows = {row["photo_id"]: row for row in baseline["details"]}
    candidate_rows = {row["photo_id"]: row for row in candidate["details"]}
    scope_rows = {row["photo_id"]: row for row in occlusion_audit.get("rows") or []}
    judgeable_targets = [
        photo_id for photo_id, row in scope_rows.items()
        if row.get("status") == "JUDGEABLE_HUMAN_TRAY_MATCH"
        and row.get("counted_as_tray_detector_miss") is True
    ]
    ignored_targets = [
        {"photo_id": photo_id, "status": row.get("status")}
        for photo_id, row in scope_rows.items()
        if photo_id not in judgeable_targets
    ]
    target_results = [
        {
            "photo_id": photo_id,
            "baseline_tray_covered": bool(baseline_rows[photo_id]["tray_target_covered"]),
            "candidate_tray_covered": bool(candidate_rows[photo_id]["tray_target_covered"]),
            "baseline_hit": bool(baseline_rows[photo_id]["hit"]),
            "candidate_hit": bool(candidate_rows[photo_id]["hit"]),
        }
        for photo_id in judgeable_targets
    ]
    defect_regressions = [
        photo_id for photo_id, before in baseline_rows.items()
        if before["kind"] == "defect" and before.get("hit")
        and not candidate_rows[photo_id].get("hit")
    ]
    normal_increases = [
        {
            "photo_id": photo_id,
            "before": int(before["false_flags"]),
            "after": int(candidate_rows[photo_id]["false_flags"]),
        }
        for photo_id, before in baseline_rows.items()
        if before["kind"] == "normal"
        and int(candidate_rows[photo_id]["false_flags"]) > int(before["false_flags"])
    ]
    errors: list[str] = []
    if not target_results or not all(row["candidate_tray_covered"] and row["candidate_hit"] for row in target_results):
        errors.append("judgeable protected tray miss was not recovered end-to-end")
    if defect_regressions:
        errors.append(f"protected defect regressions: {defect_regressions}")
    if int(candidate["false_flags"]) > int(baseline["false_flags"]):
        errors.append("total normal false flags increased")
    if float(candidate["p95_latency_ms"]) > float(baseline["p95_latency_ms"]) * 1.50:
        errors.append("CPU p95 latency exceeded 1.50x production")
    if parity_mismatches > production_parity_mismatches or parity_mismatches > 1:
        errors.append("PT/ONNX parity does not meet strict promotion gate")
    return {
        "scope_corrected": True,
        "judgeable_targets": target_results,
        "ignored_legacy_targets": ignored_targets,
        "defect_regressions": defect_regressions,
        "normal_flag_increases": normal_increases,
        "baseline_false_flags": baseline["false_flags"],
        "candidate_false_flags": candidate["false_flags"],
        "baseline_p95_latency_ms": baseline["p95_latency_ms"],
        "candidate_p95_latency_ms": candidate["p95_latency_ms"],
        "onnx_parity_mismatches": parity_mismatches,
        "production_onnx_parity_mismatches": production_parity_mismatches,
        "offline_development_passed": not errors,
        "promotion_allowed": False,
        "deployment_allowed": False,
        "errors": errors,
    }


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo-root", required=True, type=Path)
    parser.add_argument("--baseline-receipt", required=True, type=Path)
    parser.add_argument("--candidate-receipt", required=True, type=Path)
    parser.add_argument("--dataset-manifest", required=True, type=Path)
    parser.add_argument("--occlusion-audit", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--edge-confidence", type=float, default=0.30)
    parser.add_argument("--edge-width-fraction", type=float, default=0.50)
    parser.add_argument("--edge-height-fraction", type=float, default=0.60)
    return parser


def main() -> None:
    args = build_parser().parse_args()
    output = args.output.resolve()
    if output.exists():
        raise FileExistsError(f"refusing to overwrite edge evaluation: {output}")
    baseline_receipt = load_and_verify(args.baseline_receipt.resolve())
    candidate_receipt = load_and_verify(args.candidate_receipt.resolve())
    dataset = load_and_verify(args.dataset_manifest.resolve())
    occlusion_audit = load_and_verify(args.occlusion_audit.resolve())
    if candidate_receipt.get("dataset_id") != dataset.get("dataset_id"):
        raise RuntimeError("candidate/dataset id mismatch")
    if candidate_receipt.get("dataset_sha256") != dataset.get("dataset_sha256"):
        raise RuntimeError("candidate/dataset hash mismatch")
    manifest = Path(str(baseline_receipt["inputs"]["manifest"]))
    production_tray = Path(str(baseline_receipt["inputs"]["tray_model"]))
    label_model = Path(str(baseline_receipt["inputs"]["label_model"]))
    candidate_tray = Path(str(candidate_receipt["artifact"]))
    for path, expected in (
        (manifest, baseline_receipt["inputs"]["manifest_sha256"]),
        (production_tray, baseline_receipt["inputs"]["tray_model_sha256"]),
        (label_model, baseline_receipt["inputs"]["label_model_sha256"]),
        (candidate_tray, candidate_receipt["artifact_sha256"]),
    ):
        if not path.is_file() or diagnostics.sha256_file(path) != expected:
            raise RuntimeError(f"bound input drift: {path}")
    records = diagnostics.load_records(manifest)
    human_manifest = Path(str(baseline_receipt["inputs"]["human_audit"]["manifest"]))
    audits, human_audit = diagnostics.load_human_audits(human_manifest)
    sys.path.insert(0, str((args.repo_root.resolve() / "backend" / "python").resolve()))
    from label_qc.services import screening
    from label_qc.services import yolo_detector as yolo

    params = screening.ScreeningParams(tray_conf=0.60, label_conf=0.20)
    with tempfile.TemporaryDirectory(prefix="label-qc-edge-context-") as temporary:
        root = Path(temporary)
        production_dir, candidate_dir = root / "production", root / "candidate"
        production_dir.mkdir()
        candidate_dir.mkdir()
        shutil.copy2(production_tray, production_dir / "tray.onnx")
        shutil.copy2(candidate_tray, candidate_dir / "tray.onnx")
        shutil.copy2(label_model, production_dir / "label.onnx")
        shutil.copy2(label_model, candidate_dir / "label.onnx")
        production_base = yolo.LabelQcYoloModels(model_dir=production_dir)
        candidate_base = yolo.LabelQcYoloModels(model_dir=candidate_dir)
        if not production_base.available or not candidate_base.available:
            raise RuntimeError(production_base.load_error or candidate_base.load_error or "model unavailable")
        production_models = diagnostics.FullFrameModels(production_base)
        candidate_models = EdgeContextTrayModels(
            candidate_base,
            yolo.Detection,
            edge_confidence=args.edge_confidence,
            width_fraction=args.edge_width_fraction,
            height_fraction=args.edge_height_fraction,
        )
        oracles = diagnostics.build_truth_oracles(
            records, production_base, screening, yolo, params, audits,
        )
        baseline = diagnostics.evaluate_variant(
            "production_full_frame", records, production_models, screening, params, oracles,
        )
        candidate = diagnostics.evaluate_variant(
            "candidate_full_plus_two_lower_edge_windows",
            records, candidate_models, screening, params, oracles,
        )
    gate = corrected_gate(
        baseline,
        candidate,
        occlusion_audit,
        int(candidate_receipt.get("onnx_parity_mismatches", -1)),
        int(candidate_receipt.get("production_onnx_parity_mismatches", -1)),
    )
    payload = {
        "version": "label-qc-tray-edge-context-eval-v1",
        "created_at": diagnostics.utc_now(),
        "purpose": "bounded lower-edge zoom A/B after full-image candidate missed one judgeable tray",
        "inputs": {
            "baseline_receipt": str(args.baseline_receipt.resolve()),
            "baseline_receipt_sha256": diagnostics.sha256_file(args.baseline_receipt),
            "candidate_receipt": str(args.candidate_receipt.resolve()),
            "candidate_receipt_sha256": diagnostics.sha256_file(args.candidate_receipt),
            "dataset_manifest": str(args.dataset_manifest.resolve()),
            "dataset_manifest_sha256": diagnostics.sha256_file(args.dataset_manifest),
            "occlusion_audit": str(args.occlusion_audit.resolve()),
            "occlusion_audit_sha256": diagnostics.sha256_file(args.occlusion_audit),
            "protected_manifest": str(manifest),
            "protected_manifest_sha256": diagnostics.sha256_file(manifest),
            "edge_confidence": args.edge_confidence,
            "edge_width_fraction": args.edge_width_fraction,
            "edge_height_fraction": args.edge_height_fraction,
            "fixed_windows": "lower_left_and_lower_right",
            "target_location_used_at_runtime": False,
        },
        "human_audit": human_audit,
        "baseline": baseline,
        "candidate": candidate,
        "comparison": diagnostics.compare_variants(baseline, candidate),
        "gate": gate,
        "training_started": False,
        "deployment_started": False,
        "production_writes": 0,
        "originals_modified": 0,
    }
    output.mkdir(parents=True)
    receipt = output / "receipt.json"
    receipt.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({
        "receipt": str(receipt),
        "baseline": {key: baseline[key] for key in (
            "defect_hits", "tray_target_hits", "false_flags", "normal_flagged_photos", "p95_latency_ms",
        )},
        "candidate": {key: candidate[key] for key in (
            "defect_hits", "tray_target_hits", "false_flags", "normal_flagged_photos", "p95_latency_ms",
        )},
        "gate": gate,
        "deployment_started": False,
        "production_writes": 0,
    }, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
