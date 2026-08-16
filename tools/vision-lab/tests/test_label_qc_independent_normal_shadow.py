from __future__ import annotations

import hashlib
import importlib.util
import json
import sqlite3
import sys
from pathlib import Path

import pytest


ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
SPEC = importlib.util.spec_from_file_location(
    "label_qc_independent_normal_shadow",
    ROOT / "label_qc_independent_normal_shadow.py",
)
assert SPEC and SPEC.loader
module = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(module)


def _sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def _state_db(tmp_path: Path, label: str = "NO_DEFECT") -> tuple[Path, Path]:
    image = tmp_path / "photo.jpg"
    image.write_bytes(b"independent-photo")
    db = tmp_path / "vision.db"
    connection = sqlite3.connect(db)
    connection.execute("""
        CREATE TABLE photos (
          photo_id TEXT PRIMARY KEY,
          task_id TEXT NOT NULL,
          reviewed_at TEXT NOT NULL,
          sku_code TEXT,
          object_ref TEXT NOT NULL,
          sha256 TEXT NOT NULL,
          local_path TEXT NOT NULL,
          annotations_json TEXT NOT NULL,
          collected_at TEXT NOT NULL
        )
    """)
    connection.execute(
        "INSERT INTO photos VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
        (
            "photo-1", "task-1", "2026-08-16T08:00:00", "sku-1", "object",
            _sha256(image), str(image),
            json.dumps([{"source": "HUMAN", "human_label": label}]),
            "2026-08-16T08:01:00",
        ),
    )
    connection.commit()
    connection.close()
    return db, image


def test_load_normal_batch_uses_exact_watermarks_and_verifies_human_truth(tmp_path: Path):
    db, image = _state_db(tmp_path)

    rows = module.load_normal_batch(
        db,
        "2026-08-15T20:00:00", "before",
        "2026-08-16T08:00:00", "photo-1",
    )

    assert len(rows) == 1
    assert rows[0]["photo_id"] == "photo-1"
    assert rows[0]["image"] == str(image)
    assert rows[0]["human_labels"] == ["NO_DEFECT"]


def test_load_normal_batch_rejects_positive_truth(tmp_path: Path):
    db, _ = _state_db(tmp_path, label="MISSING_WHITE_LABEL")

    with pytest.raises(RuntimeError, match="positive photo"):
        module.load_normal_batch(
            db,
            "2026-08-15T20:00:00", "before",
            "2026-08-16T08:00:00", "photo-1",
        )


def test_load_normal_batch_accepts_human_verdict_on_ai_proposal(tmp_path: Path):
    db, _ = _state_db(tmp_path)
    connection = sqlite3.connect(db)
    connection.execute(
        "UPDATE photos SET annotations_json = ? WHERE photo_id = ?",
        (
            json.dumps([{
                "source": "AI",
                "ai_label": "MISSING_WHITE_LABEL",
                "human_label": "NO_DEFECT",
            }]),
            "photo-1",
        ),
    )
    connection.commit()
    connection.close()

    rows = module.load_normal_batch(
        db,
        "2026-08-15T20:00:00", "before",
        "2026-08-16T08:00:00", "photo-1",
    )

    assert rows[0]["human_labels"] == ["NO_DEFECT"]
    assert rows[0]["proposal_sources"] == ["AI"]


def test_independence_fails_closed_on_training_hash_overlap(tmp_path: Path):
    provenance = tmp_path / "provenance.json"
    protected = tmp_path / "protected.json"
    provenance.write_text(json.dumps({
        "rows": [{"source_photo_id": "old", "source_sha256": "same"}],
    }), encoding="utf-8")
    protected.write_text(json.dumps({"records": []}), encoding="utf-8")
    records = [{"photo_id": "new", "image_sha256": "same"}]

    with pytest.raises(RuntimeError, match="not independent"):
        module.assert_independent_batch(records, provenance, protected)


def test_independence_fails_closed_on_training_task_overlap(tmp_path: Path):
    provenance = tmp_path / "provenance.json"
    protected = tmp_path / "protected.json"
    provenance.write_text(json.dumps({
        "rows": [{"source_photo_id": "old", "source_sha256": "old-hash", "task_id": "same-task"}],
    }), encoding="utf-8")
    protected.write_text(json.dumps({"records": []}), encoding="utf-8")
    records = [{"photo_id": "new", "image_sha256": "new-hash", "task_id": "same-task"}]

    with pytest.raises(RuntimeError, match="not independent"):
        module.assert_independent_batch(records, provenance, protected)


def test_normal_gate_requires_no_per_photo_regression_and_bounded_latency():
    baseline = {"false_flags": 4, "flagged_photos": 3, "p95_latency_ms": 100.0}
    candidate = {"false_flags": 2, "flagged_photos": 2, "p95_latency_ms": 140.0}
    comparison = {"false_flags_increased": []}

    gate = module.build_normal_gate(baseline, candidate, comparison)

    assert gate["independent_normal_shadow_passed"] is True
    assert gate["normal_specificity_validated"] is True
    assert gate["missing_label_recall_validated"] is False
    assert gate["promotion_allowed"] is False
    assert gate["deployment_allowed"] is False


def test_normal_gate_rejects_even_one_normal_photo_regression():
    baseline = {"false_flags": 4, "flagged_photos": 3, "p95_latency_ms": 100.0}
    candidate = {"false_flags": 4, "flagged_photos": 3, "p95_latency_ms": 110.0}
    comparison = {"false_flags_increased": ["photo-regressed"]}

    gate = module.build_normal_gate(baseline, candidate, comparison)

    assert gate["independent_normal_shadow_passed"] is False
    assert "gained false flags" in gate["errors"][0]
