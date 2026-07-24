from __future__ import annotations

from typing import Any, Dict

from fastapi import APIRouter, File, HTTPException, UploadFile

from label_qc.services.analyzer import LabelQcAnalyzer, MAX_IMAGE_BYTES

router = APIRouter()
analyzer = LabelQcAnalyzer()


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
