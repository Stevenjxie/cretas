import io
import json

import pytest
from PIL import Image

from label_qc.services import analyzer as analyzer_module
from label_qc.services.analyzer import LabelQcAnalyzer, build_tiles, deduplicate_candidates


def _image_bytes(width: int = 800, height: int = 1400) -> bytes:
    image = Image.new("RGB", (width, height), "white")
    buffer = io.BytesIO()
    image.save(buffer, format="JPEG")
    return buffer.getvalue()


def _response(verdict: str, region=None):
    content = {
        "verdict": verdict,
        "confidence": 0.87,
        "region": region or [100, 200, 800, 900],
        "evidence": "疑似缺少标签",
    }
    return {
        "model": "qwen-test-vl",
        "choices": [{"message": {"content": json.dumps(content)}}],
    }


def test_portrait_photos_use_eight_overlapping_tiles():
    image = Image.new("RGB", (800, 1400), "white")
    tiles = build_tiles(image)
    assert len(tiles) == 8
    assert tiles[0].box[0] == 0
    assert tiles[-1].box[2:] == image.size
    assert tiles[0].box[2] > tiles[1].box[0]
    assert tiles[0].box[3] > tiles[2].box[1]


def test_landscape_photos_use_four_overlapping_tiles():
    image = Image.new("RGB", (1400, 800), "white")
    assert len(build_tiles(image)) == 4


def test_candidate_deduplication_keeps_distinct_label_types():
    candidates = [
        {
            "label": "MISSING_WHITE_LABEL",
            "confidence": 0.9,
            "bbox": [0.1, 0.1, 0.5, 0.5],
            "sourceTiles": [1],
        },
        {
            "label": "MISSING_WHITE_LABEL",
            "confidence": 0.8,
            "bbox": [0.12, 0.12, 0.52, 0.52],
            "sourceTiles": [2],
        },
        {
            "label": "MISSING_COLOR_LABEL",
            "confidence": 0.7,
            "bbox": [0.12, 0.12, 0.52, 0.52],
            "sourceTiles": [2],
        },
    ]
    result = deduplicate_candidates(candidates)
    assert len(result) == 2
    assert result[0]["sourceTiles"] == [1, 2]


@pytest.mark.asyncio
async def test_analyzer_aggregates_suspected_tiles(monkeypatch):
    calls = 0

    async def fake_call_chain(*_args, **_kwargs):
        nonlocal calls
        calls += 1
        return _response("MISSING_WHITE_LABEL" if calls == 1 else "CLEAR")

    monkeypatch.setattr(analyzer_module, "call_chain", fake_call_chain)
    result = await LabelQcAnalyzer().analyze(_image_bytes())

    assert calls == 8
    assert result["verdict"] == "SUSPECTED"
    assert result["promptVersion"] == "label-presence-high-recall-v1"
    assert result["candidates"][0]["label"] == "MISSING_WHITE_LABEL"
    assert all(0 <= value <= 1 for value in result["candidates"][0]["bbox"])


@pytest.mark.asyncio
async def test_failed_tile_becomes_unjudgeable_instead_of_clear(monkeypatch):
    async def fake_call_chain(*_args, **_kwargs):
        raise RuntimeError("provider unavailable")

    monkeypatch.setattr(analyzer_module, "call_chain", fake_call_chain)
    result = await LabelQcAnalyzer().analyze(_image_bytes(1400, 800))

    assert result["verdict"] == "SUSPECTED"
    assert len(result["candidates"]) == 4
    assert {item["label"] for item in result["candidates"]} == {"UNJUDGEABLE"}


@pytest.mark.asyncio
async def test_all_clear_returns_no_defect_found(monkeypatch):
    async def fake_call_chain(*_args, **_kwargs):
        return _response("CLEAR")

    monkeypatch.setattr(analyzer_module, "call_chain", fake_call_chain)
    result = await LabelQcAnalyzer().analyze(_image_bytes(1400, 800))
    assert result["verdict"] == "NO_DEFECT_FOUND"
    assert result["candidates"] == []
