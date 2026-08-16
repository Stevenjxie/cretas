#!/usr/bin/env python3
"""Create an append-only sidecar for invalid protected label-QC targets."""
from __future__ import annotations

import argparse
import json
from pathlib import Path
from typing import Any

from PIL import Image, ImageOps

from evaluate_candidate import sha256_file


VERSION = "label-qc-protected-truth-adjudication-v1"


def human_crop_disposition(
    scope_row: dict[str, Any], crop_row: dict[str, Any] | None,
) -> tuple[str, str] | None:
    status = str(scope_row.get("status") or "")
    if status == "OCCLUDED_OR_INVALID_LEGACY_TARGET_IGNORE":
        if crop_row is not None and crop_row.get("unjudgeable") is not True:
            raise RuntimeError("invalid legacy target has contradictory judgeable crop truth")
        return "exclude", "occluded_or_invalid_legacy_target"
    if status != "JUDGEABLE_TRAY_LABEL_TRUTH_CONFLICT_PENDING":
        return None
    if crop_row is None:
        raise RuntimeError("judgeable protected-truth conflict lacks human crop truth")
    classes = {int(box["c"]) for box in crop_row.get("boxes") or []}
    if (
        crop_row.get("reviewed") is not True
        or crop_row.get("source") != "human"
        or crop_row.get("unjudgeable") is True
        or crop_row.get("missing_confirmed_by_human") is not False
        or crop_row.get("declared_missing_classes") not in ([], None)
        or classes != {0, 1}
    ):
        raise RuntimeError("protected-truth conflict is not resolved by two-class human presence")
    return "exclude", "human_confirmed_both_labels_present"


def checked_annotation(path: Path, expected_sha256: str | None = None) -> dict[str, Any]:
    if not path.is_file():
        raise FileNotFoundError(path)
    digest = sha256_file(path)
    if expected_sha256 and digest != expected_sha256:
        raise RuntimeError(f"human annotation hash drift: {path}")
    value = json.loads(path.read_text(encoding="utf-8"))
    if value.get("reviewed") is not True or value.get("source") != "human":
        raise RuntimeError(f"annotation is not reviewed human truth: {path}")
    return value


def normal_review_disposition(annotation: dict[str, Any]) -> tuple[str, str] | None:
    """Map reviewed per-tray label truth to a protected photo correction."""
    if annotation.get("reviewed") is not True or annotation.get("source") != "human":
        raise RuntimeError("normal review is not reviewed human truth")
    if annotation.get("unjudgeable") is True:
        return None
    missing = annotation.get("declared_missing_classes")
    confirmed = annotation.get("missing_confirmed_by_human")
    classes = {int(box["c"]) for box in annotation.get("boxes") or []}
    if missing in ([], None) and confirmed is False and classes == {0, 1}:
        return None
    if missing == ["color_label"] and confirmed is True and classes == {0}:
        return "MISSING_COLOR_LABEL", "human_confirmed_missing_color_label"
    if missing == ["white_label"] and confirmed is True and classes == {1}:
        return "MISSING_WHITE_LABEL", "human_confirmed_missing_white_label"
    raise RuntimeError("normal review has contradictory or unsupported label truth")


def normalised_tray_box(source: Path, tray_box_px: Any) -> list[float]:
    if not isinstance(tray_box_px, list) or len(tray_box_px) != 4:
        raise RuntimeError("normal review lacks a four-value tray box")
    with Image.open(source) as opened:
        width, height = ImageOps.exif_transpose(opened).size
    values = [
        float(tray_box_px[0]) / width,
        float(tray_box_px[1]) / height,
        float(tray_box_px[2]) / width,
        float(tray_box_px[3]) / height,
    ]
    if not (0 <= values[0] < values[2] <= 1 and 0 <= values[1] < values[3] <= 1):
        raise RuntimeError("normal review tray box is outside the source image")
    return [round(value, 9) for value in values]


def load_normal_review_records(
    queue: Path, protected_path: Path, protected_by_id: dict[str, dict[str, Any]],
) -> tuple[list[dict[str, Any]], dict[str, str]]:
    manifest_path = queue / "manifest.json"
    if not manifest_path.is_file():
        raise FileNotFoundError(manifest_path)
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    if manifest.get("version") != "label-qc-normal-flag-adjudication-v1":
        raise RuntimeError("unsupported normal-flag review queue")
    metrics_path = Path(str(manifest.get("metrics") or ""))
    if not metrics_path.is_file() or sha256_file(metrics_path) != manifest.get("metrics_sha256"):
        raise RuntimeError("normal review evaluation receipt missing or drifted")
    metrics = json.loads(metrics_path.read_text(encoding="utf-8"))
    bound_manifests = {
        (str(Path(str(item.get("path") or "")).resolve()), str(item.get("sha256") or ""))
        for item in metrics.get("protected_manifests") or []
    }
    protected_binding = (str(protected_path), sha256_file(protected_path))
    if protected_binding not in bound_manifests:
        raise RuntimeError("normal review is not bound to the protected manifest")

    records: list[dict[str, Any]] = []
    seen_photo_ids: set[str] = set()
    for row in manifest.get("rows") or []:
        photo_id = str(row["source_photo_id"])
        if photo_id in seen_photo_ids:
            raise RuntimeError(f"multiple normal-review targets for one protected photo: {photo_id}")
        seen_photo_ids.add(photo_id)
        protected_row = protected_by_id.get(photo_id)
        if protected_row is None:
            raise RuntimeError(f"normal review photo is absent from protected manifest: {photo_id}")
        if str(protected_row.get("human_label") or "").upper() != "NO_DEFECT":
            raise RuntimeError(f"normal review source is not a protected normal: {photo_id}")
        if str(row.get("protected_photo_label") or "").upper() != "NO_DEFECT":
            raise RuntimeError(f"normal review queue label drift: {photo_id}")
        source = Path(str(row.get("source_image") or "")).resolve()
        if (
            not source.is_file()
            or sha256_file(source) != str(row.get("source_sha256") or "")
            or str(row.get("source_sha256") or "") != str(protected_row.get("image_sha256") or "")
            or source != Path(str(protected_row.get("image") or "")).resolve()
        ):
            raise RuntimeError(f"normal review source image missing or drifted: {photo_id}")
        crop_path = Path(str(row.get("image") or "")).resolve()
        if not crop_path.is_file() or sha256_file(crop_path) != str(row.get("image_sha256") or ""):
            raise RuntimeError(f"normal review crop missing or drifted: {photo_id}")
        annotation_path = (queue / "annotations-human" / f"{row['crop_id']}.json").resolve()
        annotation = checked_annotation(annotation_path)
        disposition = normal_review_disposition(annotation)
        if disposition is None:
            continue
        human_label, reason = disposition
        records.append({
            "photo_id": photo_id,
            "source_sha256": str(protected_row["image_sha256"]),
            "original_human_label": "NO_DEFECT",
            "action": "replace",
            "reason": reason,
            "human_label": human_label,
            "bbox": normalised_tray_box(source, row.get("tray_box_px")),
            "group": "human_adjudicated_defect",
            "evidence": [{
                "path": str(annotation_path),
                "sha256": sha256_file(annotation_path),
                "role": "per_tray_human_missing_label_truth",
            }],
        })
    return records, {
        "normal_review_queue": str(queue),
        "normal_review_manifest": str(manifest_path.resolve()),
        "normal_review_manifest_sha256": sha256_file(manifest_path),
        "normal_review_metrics": str(metrics_path.resolve()),
        "normal_review_metrics_sha256": sha256_file(metrics_path),
    }


def _same_box(left: Any, right: Any, tolerance: float = 1e-3) -> bool:
    return (
        isinstance(left, list)
        and isinstance(right, list)
        and len(left) == len(right) == 4
        and all(abs(float(a) - float(b)) <= tolerance for a, b in zip(left, right))
    )


def load_prospective_review_records(
    queue: Path, protected_path: Path, protected_by_id: dict[str, dict[str, Any]],
) -> tuple[list[dict[str, Any]], dict[str, Any]]:
    """Load at most one reviewed model-selected defect per independent task."""
    manifest_path = queue / "manifest.json"
    if not manifest_path.is_file():
        raise FileNotFoundError(manifest_path)
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    if manifest.get("version") != "label-qc-prospective-normal-flag-adjudication-v1":
        raise RuntimeError("unsupported prospective normal-flag review queue")
    shadow_path = Path(str(manifest.get("shadow_receipt") or "")).resolve()
    if not shadow_path.is_file() or sha256_file(shadow_path) != manifest.get("shadow_receipt_sha256"):
        raise RuntimeError("prospective review shadow receipt missing or drifted")
    shadow = json.loads(shadow_path.read_text(encoding="utf-8"))
    if shadow.get("version") != "label-qc-screening-param-normal-shadow-v1":
        raise RuntimeError("unsupported prospective review shadow receipt")
    shadow_inputs = shadow.get("inputs") or {}
    if (
        Path(str(shadow_inputs.get("protected_manifest") or "")).resolve() != protected_path
        or shadow_inputs.get("protected_manifest_sha256") != sha256_file(protected_path)
    ):
        raise RuntimeError("prospective review is not bound to the protected manifest")
    training_audits = (shadow.get("batch") or {}).get("training_independence") or []
    final_audit = (shadow.get("batch") or {}).get("sealed_final_independence") or {}
    if (
        shadow.get("promotion_evidence") is not True
        or shadow.get("evaluation_consumed") is not True
        or shadow.get("training_started") is not False
        or shadow.get("deployment_started") is not False
        or shadow.get("final_model_inference_started") is not False
        or not training_audits
        or not all(item.get("independent") is True for item in training_audits)
        or final_audit.get("disjoint") is not True
    ):
        raise RuntimeError("prospective review lacks sealed independence evidence")

    rows = manifest.get("rows") or []
    if len(rows) != int(manifest.get("queue_count", -1)):
        raise RuntimeError("prospective review queue count mismatch")
    details_by_id = {
        str(row["photo_id"]): row for row in shadow.get("details") or []
    }
    protected_photo_ids = set(protected_by_id)
    protected_task_ids = {
        str(row.get("task_id") or "")
        for row in protected_by_id.values() if row.get("task_id")
    }
    protected_hashes = {
        str(row.get("image_sha256") or "").lower()
        for row in protected_by_id.values() if row.get("image_sha256")
    }
    reviewed_rows: list[dict[str, Any]] = []
    confirmed: list[dict[str, Any]] = []
    for row in sorted(rows, key=lambda item: (int(item["queue_index"]), str(item["crop_id"]))):
        crop_id = str(row["crop_id"])
        photo_id = str(row["source_photo_id"])
        task_id = str(row["source_task_id"])
        source_sha = str(row.get("source_sha256") or "").lower()
        if (
            photo_id in protected_photo_ids
            or task_id in protected_task_ids
            or source_sha in protected_hashes
        ):
            raise RuntimeError(f"prospective review overlaps protected truth: {crop_id}")
        detail = details_by_id.get(photo_id)
        source = Path(str(row.get("source_image") or "")).resolve()
        if (
            detail is None
            or str(detail.get("task_id") or "") != task_id
            or str(detail.get("image_sha256") or "").lower() != source_sha
            or Path(str(detail.get("image") or "")).resolve() != source
            or not source.is_file()
            or sha256_file(source) != source_sha
            or row.get("prospective_independent_before_shadow") is not True
            or row.get("evaluation_consumed") is not True
            or row.get("training_allowed") is not False
        ):
            raise RuntimeError(f"prospective source record missing, overlapping, or drifted: {crop_id}")
        crop = Path(str(row.get("image") or "")).resolve()
        if not crop.is_file() or sha256_file(crop) != str(row.get("image_sha256") or ""):
            raise RuntimeError(f"prospective review crop missing or drifted: {crop_id}")
        annotation_path = (queue / "annotations-human" / f"{crop_id}.json").resolve()
        annotation = checked_annotation(annotation_path)
        if str(annotation.get("crop_id") or "") != crop_id:
            raise RuntimeError(f"prospective review annotation id mismatch: {crop_id}")
        disposition = normal_review_disposition(annotation)
        audit_row = {
            "crop_id": crop_id,
            "source_photo_id": photo_id,
            "source_task_id": task_id,
            "annotation": str(annotation_path),
            "annotation_sha256": sha256_file(annotation_path),
            "disposition": disposition[0] if disposition else "NO_DEFECT_OR_UNJUDGEABLE",
        }
        reviewed_rows.append(audit_row)
        if disposition is None:
            continue
        human_label, reason = disposition
        candidate_suspects = (detail.get("candidate") or {}).get("suspects") or []
        if not any(
            str(suspect.get("verdict") or "") == human_label
            and _same_box(suspect.get("box"), row.get("tray_box_px"))
            for suspect in candidate_suspects
        ):
            raise RuntimeError(f"prospective human defect is not bound to its model-selected box: {crop_id}")
        confirmed.append({
            "queue_index": int(row["queue_index"]),
            "crop_id": crop_id,
            "photo_id": photo_id,
            "task_id": task_id,
            "source": source,
            "source_sha256": source_sha,
            "sku_code": str(row.get("sku_code") or ""),
            "reviewed_at": str(row.get("source_reviewed_at") or ""),
            "human_label": human_label,
            "reason": reason,
            "bbox": normalised_tray_box(source, row.get("tray_box_px")),
            "annotation": annotation_path,
        })
    if not confirmed:
        raise RuntimeError("prospective review produced no human-confirmed defects")

    selected_by_task: dict[str, dict[str, Any]] = {}
    for row in confirmed:
        selected_by_task.setdefault(row["task_id"], row)
    records = []
    for row in selected_by_task.values():
        records.append({
            "photo_id": row["photo_id"],
            "task_id": row["task_id"],
            "image": str(row["source"]),
            "source_sha256": row["source_sha256"],
            "action": "add",
            "reason": row["reason"],
            "human_label": row["human_label"],
            "bbox": row["bbox"],
            "group": "prospective_model_flag_human_defect",
            "sku_code": row["sku_code"],
            "reviewed_at": row["reviewed_at"],
            "selection_provenance": {
                "model_selected_for_review": True,
                "task_independence_unit": True,
                "max_records_per_task": 1,
                "unbiased_recall_estimate": False,
            },
            "evidence": [{
                "path": str(row["annotation"]),
                "sha256": sha256_file(row["annotation"]),
                "role": "prospective_per_tray_human_missing_label_truth",
            }],
        })
    return records, {
        "prospective_review_queue": str(queue),
        "prospective_review_manifest": str(manifest_path.resolve()),
        "prospective_review_manifest_sha256": sha256_file(manifest_path),
        "prospective_shadow_receipt": str(shadow_path),
        "prospective_shadow_receipt_sha256": sha256_file(shadow_path),
        "prospective_review_audit": {
            "reviewed_crops": len(reviewed_rows),
            "confirmed_defect_crops": len(confirmed),
            "confirmed_defect_photos": len({row["photo_id"] for row in confirmed}),
            "confirmed_defect_tasks": len({row["task_id"] for row in confirmed}),
            "selected_task_representatives": [
                {
                    "crop_id": row["crop_id"],
                    "photo_id": row["photo_id"],
                    "task_id": row["task_id"],
                }
                for row in selected_by_task.values()
            ],
            "max_records_per_task": 1,
            "model_selected_for_review": True,
            "unbiased_recall_estimate": False,
            "rows": reviewed_rows,
        },
    }


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--protected-manifest", required=True, type=Path)
    parser.add_argument("--occlusion-audit", required=True, type=Path)
    parser.add_argument("--normal-review-queue", type=Path)
    parser.add_argument("--prospective-review-queue", type=Path)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    protected_path = args.protected_manifest.resolve()
    audit_path = args.occlusion_audit.resolve()
    output = args.output.resolve()
    for path in (protected_path, audit_path):
        if not path.is_file():
            raise FileNotFoundError(path)
    if output.exists():
        raise FileExistsError(f"refusing to overwrite truth adjudication: {output}")

    audit = json.loads(audit_path.read_text(encoding="utf-8"))
    if audit.get("version") != "label-qc-occlusion-scope-audit-v1":
        raise RuntimeError("unsupported occlusion audit")
    baseline_path = Path(str(audit.get("inputs", {}).get("baseline_receipt") or ""))
    if not baseline_path.is_file() or sha256_file(baseline_path) != audit["inputs"]["baseline_receipt_sha256"]:
        raise RuntimeError("occlusion audit baseline receipt missing or drifted")
    baseline = json.loads(baseline_path.read_text(encoding="utf-8"))
    baseline_inputs = baseline.get("inputs") or {}
    if (
        Path(str(baseline_inputs.get("manifest") or "")).resolve() != protected_path
        or baseline_inputs.get("manifest_sha256") != sha256_file(protected_path)
    ):
        raise RuntimeError("baseline receipt does not bind the protected manifest")
    protected = json.loads(protected_path.read_text(encoding="utf-8"))
    protected_by_id = {
        str(row["photo_id"]): row for row in protected.get("records") or []
    }
    human_crop_rows = {
        str(row["source_photo_id"]): row
        for row in (baseline_inputs.get("human_audit") or {}).get("rows") or []
    }

    records: list[dict[str, Any]] = []
    for scope_row in audit.get("rows") or []:
        photo_id = str(scope_row["photo_id"])
        crop_row = human_crop_rows.get(photo_id)
        disposition = human_crop_disposition(scope_row, crop_row)
        if disposition is None:
            continue
        action, reason = disposition
        protected_row = protected_by_id[photo_id]
        full_annotation_path = Path(str(scope_row["annotation"]))
        checked_annotation(full_annotation_path, str(scope_row["annotation_sha256"]))
        evidence = [{
            "path": str(full_annotation_path),
            "sha256": sha256_file(full_annotation_path),
            "role": "full_image_judgeable_tray_truth",
        }]
        if crop_row is not None:
            crop_annotation_path = Path(str(crop_row["annotation_path"]))
            crop_annotation = checked_annotation(
                crop_annotation_path, str(crop_row["annotation_sha256"]),
            )
            # Re-run the pure rule on file contents, not only copied receipt fields.
            file_crop_row = dict(crop_row)
            file_crop_row.update(crop_annotation)
            human_crop_disposition(scope_row, file_crop_row)
            evidence.append({
                "path": str(crop_annotation_path),
                "sha256": sha256_file(crop_annotation_path),
                "role": "per_tray_label_presence_truth",
            })
        records.append({
            "photo_id": photo_id,
            "source_sha256": str(protected_row["image_sha256"]),
            "original_human_label": str(protected_row["human_label"]).upper(),
            "action": action,
            "reason": reason,
            "evidence": evidence,
        })

    normal_review_input: dict[str, Any] = {}
    if args.normal_review_queue:
        normal_records, normal_review_input = load_normal_review_records(
            args.normal_review_queue.resolve(), protected_path, protected_by_id,
        )
        records.extend(normal_records)
    prospective_review_input: dict[str, Any] = {}
    if args.prospective_review_queue:
        prospective_records, prospective_review_input = load_prospective_review_records(
            args.prospective_review_queue.resolve(), protected_path, protected_by_id,
        )
        records.extend(prospective_records)
    if not records:
        raise RuntimeError("occlusion audit produced no protected-truth adjudications")
    photo_ids = [record["photo_id"] for record in records]
    if len(photo_ids) != len(set(photo_ids)):
        raise RuntimeError("duplicate protected-truth adjudication photo id")
    original_defects = sum(
        str(row.get("human_label") or "").upper()
        in {"MISSING_WHITE_LABEL", "MISSING_COLOR_LABEL"}
        for row in protected_by_id.values()
    )
    excluded_defects = sum(
        record["original_human_label"]
        in {"MISSING_WHITE_LABEL", "MISSING_COLOR_LABEL"}
        for record in records
        if record["action"] == "exclude"
    )
    relabelled_defects = sum(
        record["original_human_label"] == "NO_DEFECT"
        and record.get("human_label") in {"MISSING_WHITE_LABEL", "MISSING_COLOR_LABEL"}
        for record in records
        if record["action"] == "replace"
    )
    prospective_defects = sum(
        record.get("human_label") in {"MISSING_WHITE_LABEL", "MISSING_COLOR_LABEL"}
        for record in records
        if record["action"] == "add"
    )
    added_defects = relabelled_defects + prospective_defects
    removed_defects = sum(
        record["original_human_label"] in {"MISSING_WHITE_LABEL", "MISSING_COLOR_LABEL"}
        and record.get("human_label") == "NO_DEFECT"
        for record in records
        if record["action"] == "replace"
    )
    remaining_defects = original_defects - excluded_defects - removed_defects + added_defects
    payload = {
        "version": VERSION,
        "purpose": "append-only repair of invalid or incomplete protected truth",
        "protected_manifests": [{
            "path": str(protected_path), "sha256": sha256_file(protected_path),
        }],
        "inputs": {
            "occlusion_audit": str(audit_path),
            "occlusion_audit_sha256": sha256_file(audit_path),
            "baseline_receipt": str(baseline_path),
            "baseline_receipt_sha256": sha256_file(baseline_path),
            **normal_review_input,
            **prospective_review_input,
        },
        "records": records,
        "aggregate": {
            "original_protected_defects": original_defects,
            "excluded_invalid_defects": excluded_defects,
            "human_adjudicated_defects_added": added_defects,
            "protected_normal_relabels_added": relabelled_defects,
            "prospective_task_independent_defects_added": prospective_defects,
            "human_adjudicated_defects_removed": removed_defects,
            "remaining_valid_defects": remaining_defects,
            "replacement_defects_required_to_restore_original_gate": max(
                0, original_defects - remaining_defects,
            ),
        },
        "promotion_ready": False,
        "protected_manifest_modified": False,
        "annotations_modified": False,
        "production_writes": 0,
        "originals_modified": 0,
    }
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(
        json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8",
    )
    print(json.dumps({
        "output": str(output),
        "aggregate": payload["aggregate"],
        "promotion_ready": False,
        "protected_manifest_modified": False,
        "production_writes": 0,
    }, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
