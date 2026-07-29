"""Tests for the YOLO-screening + VL-review orchestration.

Both the ONNX models and the VL call are stubbed, so these cover the decision
logic: what survives review, how confidence is assigned, ordering, contract
shape, and the fallback paths.
"""
from __future__ import annotations

import io
import json

import numpy as np
import pytest
from PIL import Image

from label_qc.services import hybrid_analyzer as hybrid
from label_qc.services.hybrid_analyzer import HybridLabelQcAnalyzer
from label_qc.services.screening import (
    ScreeningParams,
    ScreeningResult,
    TrayResult,
    VERDICT_MISSING_COLOR,
    VERDICT_MISSING_WHITE,
    VERDICT_OK,
)


def _image_bytes(width: int = 800, height: int = 600) -> bytes:
    buffer = io.BytesIO()
    Image.new("RGB", (width, height), (140, 90, 90)).save(buffer, format="JPEG")
    return buffer.getvalue()


class _StubModels:
    def __init__(self, available: bool = True):
        self._available = available
        self.model_dir = "/stub"

    @property
    def available(self) -> bool:
        return self._available

    @property
    def load_error(self):
        return None if self._available else "stubbed as unavailable"


def _screening(trays):
    return ScreeningResult(trays=trays, image_width=800, image_height=600,
                           params=ScreeningParams())


def _tray(index, verdict, box=(100, 100, 300, 300)):
    return TrayResult(index=index, box=list(box), confidence=0.9,
                      has_white=verdict != VERDICT_MISSING_WHITE,
                      has_color=verdict != VERDICT_MISSING_COLOR,
                      verdict=verdict)


def _vl_response(verdict: str, confidence: float, evidence: str = "e"):
    return {
        "choices": [{"message": {"content": json.dumps(
            {"verdict": verdict, "confidence": confidence, "evidence": evidence})}}],
        "model": "stub-vl",
    }


def _install(monkeypatch, trays, vl_verdicts):
    """Stub screening output and a scripted sequence of VL replies."""
    monkeypatch.setattr(hybrid, "screen_image",
                        lambda image, models, params: _screening(trays))
    calls = {"n": 0}

    async def fake_call_chain(slot, payload, timeout=None):
        index = calls["n"]
        calls["n"] += 1
        verdict, confidence = vl_verdicts[index]
        return _vl_response(verdict, confidence)

    monkeypatch.setattr(hybrid, "call_chain", fake_call_chain)
    return calls


@pytest.mark.asyncio
async def test_vl_confirmation_produces_high_confidence_candidate(monkeypatch):
    _install(monkeypatch, [_tray(0, VERDICT_MISSING_WHITE)],
             [(VERDICT_MISSING_WHITE, 0.88)])
    analyzer = HybridLabelQcAnalyzer(models=_StubModels())

    result = await analyzer.analyze(_image_bytes())

    assert result["verdict"] == "SUSPECTED"
    assert len(result["candidates"]) == 1
    candidate = result["candidates"][0]
    assert candidate["label"] == VERDICT_MISSING_WHITE
    assert candidate["confidence"] == pytest.approx(0.88)
    assert result["screening"]["confirmedByVl"] == 1


@pytest.mark.asyncio
async def test_vl_rejection_is_kept_as_low_confidence_not_dropped(monkeypatch):
    """A human reviews every photo, so a VL veto must not silently hide a tray."""
    _install(monkeypatch, [_tray(0, VERDICT_MISSING_WHITE)], [(VERDICT_OK, 0.9)])
    analyzer = HybridLabelQcAnalyzer(models=_StubModels())

    result = await analyzer.analyze(_image_bytes())

    assert len(result["candidates"]) == 1, "VL 否决的候选不应被丢弃"
    candidate = result["candidates"][0]
    assert candidate["confidence"] <= 0.25
    assert "视觉复核认为正常" in candidate["evidence"]
    assert result["screening"]["rejectedByVl"] == 1


@pytest.mark.asyncio
async def test_candidates_are_ordered_confirmed_first(monkeypatch):
    trays = [_tray(0, VERDICT_MISSING_WHITE, (10, 10, 100, 100)),
             _tray(1, VERDICT_MISSING_COLOR, (200, 200, 300, 300))]
    # tray 0 rejected by VL, tray 1 confirmed -> confirmed must sort first
    _install(monkeypatch, trays, [(VERDICT_OK, 0.9), (VERDICT_MISSING_COLOR, 0.8)])
    analyzer = HybridLabelQcAnalyzer(models=_StubModels())

    result = await analyzer.analyze(_image_bytes())

    confidences = [c["confidence"] for c in result["candidates"]]
    assert confidences == sorted(confidences, reverse=True)
    assert result["candidates"][0]["label"] == VERDICT_MISSING_COLOR
    assert [c["candidateId"] for c in result["candidates"]] == ["ai-1", "ai-2"]


@pytest.mark.asyncio
async def test_review_failure_keeps_screening_verdict(monkeypatch):
    monkeypatch.setattr(hybrid, "screen_image",
                        lambda image, models, params: _screening([_tray(0, VERDICT_MISSING_WHITE)]))

    async def boom(slot, payload, timeout=None):
        raise TimeoutError("vl down")

    monkeypatch.setattr(hybrid, "call_chain", boom)
    analyzer = HybridLabelQcAnalyzer(models=_StubModels())

    result = await analyzer.analyze(_image_bytes())

    assert len(result["candidates"]) == 1, "复核失败不得把可疑托盘判为正常"
    assert "视觉复核未完成" in result["candidates"][0]["evidence"]
    assert result["screening"]["unreviewed"] == 1


@pytest.mark.asyncio
async def test_clean_photo_reports_no_defect(monkeypatch):
    monkeypatch.setattr(hybrid, "screen_image",
                        lambda image, models, params: _screening([_tray(0, VERDICT_OK)]))
    analyzer = HybridLabelQcAnalyzer(models=_StubModels())

    result = await analyzer.analyze(_image_bytes())

    assert result["verdict"] == "NO_DEFECT_FOUND"
    assert result["candidates"] == []
    assert result["screening"]["suspectCount"] == 0


@pytest.mark.asyncio
async def test_falls_back_to_vl_only_when_models_missing():
    class _StubVl:
        async def analyze(self, image_bytes):
            return {"verdict": "NO_DEFECT_FOUND", "candidates": [], "model": "vl",
                    "promptVersion": "p", "imageWidth": 1, "imageHeight": 1,
                    "tilesAnalyzed": 8}

    analyzer = HybridLabelQcAnalyzer(models=_StubModels(available=False),
                                     vl_analyzer=_StubVl())
    result = await analyzer.analyze(_image_bytes())

    assert result["screeningMode"] == "vl-only-fallback"
    assert result["tilesAnalyzed"] == 8


@pytest.mark.asyncio
async def test_contract_fields_present(monkeypatch):
    _install(monkeypatch, [_tray(0, VERDICT_MISSING_WHITE)],
             [(VERDICT_MISSING_WHITE, 0.7)])
    analyzer = HybridLabelQcAnalyzer(models=_StubModels())

    result = await analyzer.analyze(_image_bytes(1024, 768))

    for key in ("verdict", "candidates", "model", "promptVersion",
                "imageWidth", "imageHeight", "tilesAnalyzed"):
        assert key in result, f"缺少契约字段 {key}"
    assert result["imageWidth"] == 800   # comes from screening result stub
    candidate = result["candidates"][0]
    for key in ("candidateId", "label", "confidence", "bbox", "evidence", "sourceTiles"):
        assert key in candidate, f"候选缺少契约字段 {key}"
    assert len(candidate["bbox"]) == 4
    assert all(0.0 <= v <= 1.0 for v in candidate["bbox"]), "bbox 必须归一化"


@pytest.mark.asyncio
async def test_rejects_oversized_image():
    analyzer = HybridLabelQcAnalyzer(models=_StubModels())
    with pytest.raises(ValueError):
        await analyzer.analyze(b"x" * (hybrid.MAX_IMAGE_BYTES + 1))
