from __future__ import annotations

import logging
import os
from typing import Any, Dict

from fastapi import APIRouter, File, HTTPException, UploadFile

from label_qc.services.analyzer import LabelQcAnalyzer, MAX_IMAGE_BYTES

logger = logging.getLogger(__name__)
router = APIRouter()


def _screening_enabled() -> bool:
    return os.getenv("LABEL_QC_SCREENING", "1").strip().lower() in {"1", "true", "yes", "on"}


def _build_analyzer():
    """YOLO screening + VL review when enabled and importable, else VL-only.

    The hybrid analyzer also falls back to VL-only at request time when the ONNX
    model files are absent, so a missing model degrades to today's behaviour
    instead of failing the endpoint.
    """
    if not _screening_enabled():
        logger.info("Label QC: screening disabled by LABEL_QC_SCREENING, using VL-only")
        return LabelQcAnalyzer()
    try:
        from label_qc.services.hybrid_analyzer import HybridLabelQcAnalyzer

        return HybridLabelQcAnalyzer()
    except Exception as exc:  # onnxruntime / opencv missing on this host
        logger.warning("Label QC: hybrid analyzer unavailable (%s: %s), using VL-only",
                       type(exc).__name__, exc)
        return LabelQcAnalyzer()


analyzer = _build_analyzer()


@router.post("/analyze")
async def analyze_label_photo(
    image: UploadFile = File(...),
) -> Dict[str, Any]:
    image_bytes = await image.read(MAX_IMAGE_BYTES + 1)
    if len(image_bytes) > MAX_IMAGE_BYTES:
        raise HTTPException(status_code=413, detail="Image exceeds 10 MB limit")
    try:
        result = await analyzer.analyze(image_bytes)
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc
    return {"success": True, "data": result}


@router.get("/screening-status")
async def screening_status() -> Dict[str, Any]:
    """Whether YOLO screening is actually live -- for post-deploy verification."""
    active = type(analyzer).__name__
    data: Dict[str, Any] = {
        "analyzer": active,
        "screeningEnabledByConfig": _screening_enabled(),
        "screeningActive": active == "HybridLabelQcAnalyzer",
    }
    models = getattr(analyzer, "_models", None)
    if models is not None:
        data["modelDir"] = str(models.model_dir)
        data["modelsAvailable"] = models.available
        data["loadError"] = models.load_error
    return {"success": True, "data": data}
