"""YOLO screening + VL review.

YOLO screens every tray cheaply and locally; only the trays it flags are sent to
the vision model, cutting VL call volume by roughly an order of magnitude
(whole-photo tiling: 8 calls per photo; screening: ~1-2).

VL ranks rather than vetoes. Every photo is reviewed by a human regardless, so a
tray the VL clears is still surfaced as a low-confidence candidate instead of
being dropped -- trading a little reviewer attention for a lower chance of a
missed defect. Candidates are ordered by confidence so VL-confirmed defects come
first and screening-only leftovers sit at the bottom.

Known limitation, accepted deliberately: a tray YOLO fails to flag is never seen
by the VL reviewer. Screening recall on the 60-photo synthetic set is 90% and is
an upper bound (synthetic fill is smoother than real bare film). Real-photo
recall cannot be measured yet -- only 5 confirmed real defect photos exist. Every
screening decision is therefore persisted for later evaluation and retraining.

Falls back to the VL-only analyzer whenever the ONNX models are unavailable, so
a missing model file degrades to today's behaviour rather than to no analysis.
"""
from __future__ import annotations

import asyncio
import base64
import io
import json
import logging
import os
import re
from typing import Any, Dict, List, Optional, Tuple

import numpy as np
from PIL import Image, ImageOps

from common.llm_metrics import llm_caller_context
from common.llm_router import SLOT, call_chain
from label_qc.services.analyzer import LabelQcAnalyzer, MAX_IMAGE_BYTES
from label_qc.services.screening import (
    ScreeningParams,
    ScreeningResult,
    TrayResult,
    VERDICT_MISSING_BOTH,
    VERDICT_MISSING_COLOR,
    VERDICT_MISSING_WHITE,
    VERDICT_OK,
    screen_image,
)
from label_qc.services.yolo_detector import (
    LabelQcYoloModels,
    crop_with_padding,
    resize_crop,
)

logger = logging.getLogger(__name__)

SCREENING_VERSION = "yolo-screen-v1+vl-review"
REVIEW_PROMPT_VERSION = "label-tray-review-v1"
MAX_CONCURRENT_REVIEWS = 2
REVIEW_TIMEOUT_SECONDS = 30.0
REVIEW_CROP_WIDTH = 768
REVIEW_PAD_RATIO = 0.25

_REVIEW_PROMPT = """
你是食品包装标签质检员。这张图是一个肉盒的特写（可能带少量相邻盒子的边缘）。

正常的盒子同时具有两种标签：
1. 彩标：红黑色或绿色的长条品牌标签；
2. 白标：白色矩形称重/条码标签。

只判断**画面中央这一个盒子**的标签是否存在，不要根据旁边盒子下结论，也不识别文字内容。

初筛系统怀疑这个盒子是：{suspicion}

请独立判断，不要被初筛结论带偏：
- 中央盒子确实缺白色矩形标签：MISSING_WHITE_LABEL
- 确实缺红黑/绿色长条彩标：MISSING_COLOR_LABEL
- 两种都缺：BOTH_MISSING
- 两种都在：CLEAR
- 遮挡、反光、模糊或只露窄边，无法可靠确认：UNJUDGEABLE

只返回一行 JSON，不要 Markdown，不要解释：
{{"verdict":"CLEAR|MISSING_WHITE_LABEL|MISSING_COLOR_LABEL|BOTH_MISSING|UNJUDGEABLE","confidence":0.0,"evidence":"不超过40字"}}
""".strip()

_ALLOWED = {"CLEAR", "MISSING_WHITE_LABEL", "MISSING_COLOR_LABEL",
            "BOTH_MISSING", "UNJUDGEABLE"}

_SUSPICION_TEXT = {
    VERDICT_MISSING_WHITE: "缺白标",
    VERDICT_MISSING_COLOR: "缺彩标",
    VERDICT_MISSING_BOTH: "白标和彩标都缺",
}


def _env_flag(name: str, default: bool) -> bool:
    raw = os.getenv(name)
    if raw is None:
        return default
    return raw.strip().lower() in {"1", "true", "yes", "on"}


def _env_float(name: str, default: float) -> float:
    try:
        return float(os.getenv(name, ""))
    except (TypeError, ValueError):
        return default


def _encode(image: np.ndarray) -> str:
    buffer = io.BytesIO()
    Image.fromarray(image).save(buffer, format="JPEG", quality=90, optimize=True)
    return base64.b64encode(buffer.getvalue()).decode("ascii")


def _extract_content(response: Dict[str, Any]) -> str:
    try:
        content = response["choices"][0]["message"]["content"]
    except (KeyError, IndexError, TypeError) as exc:
        raise ValueError("VL response has no message content") from exc
    if isinstance(content, str):
        return content
    if isinstance(content, list):
        return "".join(
            item.get("text", "")
            for item in content
            if isinstance(item, dict) and item.get("type") == "text"
        )
    raise ValueError("VL response content has unsupported type")


def _parse_verdict(text: str) -> Dict[str, Any]:
    cleaned = re.sub(r"^```(?:json)?\s*|\s*```$", "", text.strip(), flags=re.IGNORECASE)
    try:
        value = json.loads(cleaned)
    except json.JSONDecodeError:
        match = re.search(r"\{.*\}", cleaned, flags=re.DOTALL)
        if not match:
            raise
        value = json.loads(match.group(0))
    if not isinstance(value, dict):
        raise ValueError("VL review response is not a JSON object")
    verdict = str(value.get("verdict", "")).strip().upper()
    if verdict not in _ALLOWED:
        raise ValueError(f"unsupported verdict: {verdict or '<empty>'}")
    try:
        confidence = min(1.0, max(0.0, float(value.get("confidence", 0))))
    except (TypeError, ValueError):
        confidence = 0.0
    return {"verdict": verdict, "confidence": confidence,
            "evidence": str(value.get("evidence", ""))[:120]}


class HybridLabelQcAnalyzer:
    """Screen with YOLO, confirm with VL, keep the existing response contract."""

    def __init__(
        self,
        models: Optional[LabelQcYoloModels] = None,
        vl_analyzer: Optional[LabelQcAnalyzer] = None,
        params: Optional[ScreeningParams] = None,
    ) -> None:
        self._models = models or LabelQcYoloModels()
        self._vl = vl_analyzer or LabelQcAnalyzer()
        self._params = params or ScreeningParams(
            tray_conf=_env_float("LABEL_QC_TRAY_CONF", 0.60),
            label_conf=_env_float("LABEL_QC_LABEL_CONF", 0.25),
        )
        self._review_enabled = _env_flag("LABEL_QC_VL_REVIEW", True)

    # ------------------------------------------------------------------ VL review
    async def _review_tray(
        self, image: np.ndarray, tray: TrayResult, semaphore: asyncio.Semaphore
    ) -> Tuple[TrayResult, Optional[Dict[str, Any]], str]:
        crop, _ = crop_with_padding(image, tray.box, REVIEW_PAD_RATIO)
        if crop.size == 0:
            return tray, None, "unknown"
        crop = resize_crop(crop, REVIEW_CROP_WIDTH)
        prompt = _REVIEW_PROMPT.format(
            suspicion=_SUSPICION_TEXT.get(tray.verdict, tray.verdict)
        )
        payload = {
            "messages": [
                {
                    "role": "user",
                    "content": [
                        {"type": "image_url",
                         "image_url": {"url": f"data:image/jpeg;base64,{_encode(crop)}"}},
                        {"type": "text", "text": prompt},
                    ],
                }
            ],
            "temperature": 0,
            "max_tokens": 200,
        }
        try:
            async with semaphore:
                with llm_caller_context("label_qc.tray_review"):
                    response = await asyncio.wait_for(
                        call_chain(SLOT.VL, payload, timeout=REVIEW_TIMEOUT_SECONDS),
                        timeout=REVIEW_TIMEOUT_SECONDS + 5,
                    )
            parsed = _parse_verdict(_extract_content(response))
            return tray, parsed, str(response.get("model", "unknown"))
        except Exception as exc:
            # Never downgrade a suspect to OK because review failed -- keep the
            # screening verdict and say so in the evidence.
            logger.warning("Label QC tray review failed (tray %s): %s", tray.index, exc)
            return tray, None, "unknown"

    # ------------------------------------------------------------------ assembly
    @staticmethod
    def _candidate(
        tray: TrayResult, label: str, confidence: float, evidence: str,
        width: int, height: int, index: int,
    ) -> Dict[str, Any]:
        x0, y0, x1, y1 = tray.box
        return {
            "candidateId": f"ai-{index}",
            "label": label,
            "confidence": round(float(confidence), 4),
            "bbox": [
                round(max(0.0, min(1.0, x0 / max(width, 1))), 6),
                round(max(0.0, min(1.0, y0 / max(height, 1))), 6),
                round(max(0.0, min(1.0, x1 / max(width, 1))), 6),
                round(max(0.0, min(1.0, y1 / max(height, 1))), 6),
            ],
            "evidence": evidence[:120],
            "sourceTiles": [tray.index],
        }

    def _screening_payload(self, screening: ScreeningResult) -> Dict[str, Any]:
        """Persisted verbatim so corrections can be tied back to a specific tray."""
        return {
            "version": SCREENING_VERSION,
            "params": {
                "trayConf": screening.params.tray_conf,
                "labelConf": screening.params.label_conf,
                "padRatio": screening.params.pad_ratio,
                "ownLabelsOnly": screening.params.own_labels_only,
            },
            "trayCount": len(screening.trays),
            "suspectCount": len(screening.suspects),
            "trays": [
                {
                    "index": t.index,
                    "bbox": [
                        round(t.box[0] / max(screening.image_width, 1), 6),
                        round(t.box[1] / max(screening.image_height, 1), 6),
                        round(t.box[2] / max(screening.image_width, 1), 6),
                        round(t.box[3] / max(screening.image_height, 1), 6),
                    ],
                    "trayConfidence": round(t.confidence, 4),
                    "hasWhite": t.has_white,
                    "hasColor": t.has_color,
                    "screenVerdict": t.verdict,
                    "ownLabels": t.own_label_count,
                    "droppedNeighbourLabels": t.dropped_neighbour_labels,
                }
                for t in screening.trays
            ],
        }

    async def analyze(self, image_bytes: bytes) -> Dict[str, Any]:
        if not image_bytes:
            raise ValueError("empty image")
        if len(image_bytes) > MAX_IMAGE_BYTES:
            raise ValueError("image exceeds 10 MB limit")

        if not self._models.available:
            logger.info("Label QC: YOLO unavailable (%s), using VL-only analyzer",
                        self._models.load_error)
            result = await self._vl.analyze(image_bytes)
            result["screeningMode"] = "vl-only-fallback"
            result["screeningUnavailableReason"] = self._models.load_error
            return result

        try:
            source = Image.open(io.BytesIO(image_bytes))
            source = ImageOps.exif_transpose(source)
            source.load()
            source = source.convert("RGB")
        except Exception as exc:
            raise ValueError("invalid or unsupported image") from exc

        image = np.array(source)
        screening = await asyncio.to_thread(screen_image, image, self._models, self._params)
        suspects = screening.suspects

        reviews: List[Tuple[TrayResult, Optional[Dict[str, Any]], str]] = []
        if suspects and self._review_enabled:
            semaphore = asyncio.Semaphore(MAX_CONCURRENT_REVIEWS)
            reviews = list(await asyncio.gather(
                *(self._review_tray(image, tray, semaphore) for tray in suspects)
            ))
        else:
            reviews = [(tray, None, "screen-only") for tray in suspects]

        candidates: List[Dict[str, Any]] = []
        models_used = {f"yolo:{SCREENING_VERSION}"}
        confirmed = rejected = unreviewed = 0

        for tray, review, model_name in reviews:
            models_used.add(model_name)
            if review is None:
                # Review unavailable: keep the screening verdict rather than
                # silently clearing a suspect tray.
                unreviewed += 1
                verdict, confidence = tray.verdict, 0.5
                evidence = "初筛判定，视觉复核未完成"
            else:
                verdict, confidence = review["verdict"], review["confidence"]
                evidence = review["evidence"] or "视觉复核"
                if verdict == "CLEAR":
                    # Every photo is reviewed by a human anyway, so a VL veto is
                    # not worth trading against a missed defect: keep the tray as
                    # a low-confidence candidate instead of dropping it.
                    rejected += 1
                    verdict = tray.verdict
                    confidence = round(min(0.25, max(0.05, 1.0 - confidence)), 4)
                    evidence = f"初筛可疑，视觉复核认为正常（{evidence}）"
                elif verdict == "UNJUDGEABLE":
                    # Not a defect claim, but must not be treated as clean.
                    unreviewed += 1
                    verdict, confidence = tray.verdict, min(confidence, 0.4)
                    evidence = evidence or "视觉复核判定不可判定"
                else:
                    confirmed += 1

            labels = ([VERDICT_MISSING_WHITE, VERDICT_MISSING_COLOR]
                      if verdict == VERDICT_MISSING_BOTH else [verdict])
            for label in labels:
                if label == VERDICT_OK:
                    continue
                candidates.append(self._candidate(
                    tray, label, confidence, evidence,
                    screening.image_width, screening.image_height, len(candidates) + 1,
                ))

        # Highest confidence first so the reviewer sees VL-confirmed defects
        # before the screening-only leftovers.
        candidates.sort(key=lambda c: -c["confidence"])
        for index, candidate in enumerate(candidates, start=1):
            candidate["candidateId"] = f"ai-{index}"

        payload = self._screening_payload(screening)
        payload["reviewed"] = len(reviews)
        payload["confirmedByVl"] = confirmed
        payload["rejectedByVl"] = rejected
        payload["unreviewed"] = unreviewed

        return {
            "verdict": "SUSPECTED" if candidates else "NO_DEFECT_FOUND",
            "candidates": candidates,
            "model": ",".join(sorted(models_used)),
            "promptVersion": REVIEW_PROMPT_VERSION,
            "imageWidth": screening.image_width,
            "imageHeight": screening.image_height,
            # Kept for contract compatibility: downstream reads this as "how many
            # model calls backed this result".
            "tilesAnalyzed": len(reviews),
            "screeningMode": "yolo-screen+vl-review" if self._review_enabled else "yolo-screen-only",
            "screening": payload,
        }
