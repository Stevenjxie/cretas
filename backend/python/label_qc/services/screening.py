"""Two-stage YOLO screening: detect trays, then detect labels inside each tray.

A missing label is inferred by RULE from labels that ARE detected, never by
asking a model to localise an absent object. That inversion is what makes the
approach trainable from real photos: "what a label looks like" has thousands of
real examples, "a missing label" has almost none.

Ownership filtering is essential: the tray crop carries padding, so a
neighbouring tray's label often appears inside it. Labels are attributed to a
tray only when their centre falls inside that tray's box. Measured effect on the
synthetic recall set: 58% -> 85% correct, with target-tray false alarms
unchanged at 0.
"""
from __future__ import annotations

import logging
from dataclasses import dataclass, field
from typing import List, Optional, Sequence

import numpy as np

from label_qc.services.yolo_detector import (
    CLASS_COLOR_LABEL,
    CLASS_WHITE_LABEL,
    Detection,
    LabelQcYoloModels,
    crop_with_padding,
    resize_crop,
)

logger = logging.getLogger(__name__)

VERDICT_OK = "CLEAR"
VERDICT_MISSING_WHITE = "MISSING_WHITE_LABEL"
VERDICT_MISSING_COLOR = "MISSING_COLOR_LABEL"
VERDICT_MISSING_BOTH = "BOTH_MISSING"


@dataclass(frozen=True)
class ScreeningParams:
    """Defaults come from the sweeps on the 60-photo synthetic recall set.

    tray_conf 0.60 gives 100% of photos within the 18-per-board production norm.
    label_conf 0.25 maximises screening recall (90%); the extra false alarms it
    produces are the ones the VL reviewer is there to filter.
    """

    tray_conf: float = 0.60
    label_conf: float = 0.25
    pad_ratio: float = 0.14
    crop_width: int = 640
    min_crop_px: int = 120
    own_labels_only: bool = True
    max_trays: int = 60


@dataclass
class TrayResult:
    index: int
    box: List[float]
    confidence: float
    has_white: bool
    has_color: bool
    verdict: str
    own_label_count: int = 0
    dropped_neighbour_labels: int = 0
    label_confidences: List[float] = field(default_factory=list)

    @property
    def is_suspect(self) -> bool:
        return self.verdict != VERDICT_OK


@dataclass
class ScreeningResult:
    trays: List[TrayResult]
    image_width: int
    image_height: int
    params: ScreeningParams

    @property
    def suspects(self) -> List[TrayResult]:
        return [t for t in self.trays if t.is_suspect]


def _verdict_for(has_white: bool, has_color: bool) -> str:
    if has_white and has_color:
        return VERDICT_OK
    if has_white:
        return VERDICT_MISSING_COLOR
    if has_color:
        return VERDICT_MISSING_WHITE
    return VERDICT_MISSING_BOTH


def _owns(tray_box: Sequence[float], label: Detection,
          crop_rect: Sequence[float], crop_shape: Sequence[int]) -> bool:
    """Is this label's centre inside the tray box (not merely inside the crop)?"""
    crop_h, crop_w = crop_shape[0], crop_shape[1]
    scale_x = (crop_rect[2] - crop_rect[0]) / max(crop_w, 1)
    scale_y = (crop_rect[3] - crop_rect[1]) / max(crop_h, 1)
    cx, cy = label.center
    src_x = crop_rect[0] + cx * scale_x
    src_y = crop_rect[1] + cy * scale_y
    return (tray_box[0] <= src_x <= tray_box[2]) and (tray_box[1] <= src_y <= tray_box[3])


def screen_image(
    image: np.ndarray,
    models: LabelQcYoloModels,
    params: Optional[ScreeningParams] = None,
) -> ScreeningResult:
    """Run tray detection -> per-tray label detection -> rule inference."""
    params = params or ScreeningParams()
    height, width = image.shape[:2]

    trays = models.detect_trays(image, params.tray_conf)
    trays.sort(key=lambda d: -d.confidence)
    if len(trays) > params.max_trays:
        logger.warning("Label QC screening: %d trays exceeds cap %d, truncating",
                       len(trays), params.max_trays)
        trays = trays[: params.max_trays]

    results: List[TrayResult] = []
    for index, tray in enumerate(trays):
        crop, rect = crop_with_padding(image, tray.as_xyxy(), params.pad_ratio)
        if crop.shape[0] < params.min_crop_px or crop.shape[1] < params.min_crop_px:
            # Too small to judge -- do not silently call it OK.
            results.append(TrayResult(
                index=index, box=tray.as_xyxy(), confidence=tray.confidence,
                has_white=False, has_color=False, verdict=VERDICT_MISSING_BOTH,
            ))
            continue

        resized = resize_crop(crop, params.crop_width)
        labels = models.detect_labels(resized, params.label_conf)

        own: List[Detection] = []
        dropped = 0
        for label in labels:
            if params.own_labels_only and not _owns(tray.as_xyxy(), label, rect, resized.shape):
                dropped += 1
                continue
            own.append(label)

        has_white = any(d.class_id == CLASS_WHITE_LABEL for d in own)
        has_color = any(d.class_id == CLASS_COLOR_LABEL for d in own)
        results.append(TrayResult(
            index=index,
            box=tray.as_xyxy(),
            confidence=tray.confidence,
            has_white=has_white,
            has_color=has_color,
            verdict=_verdict_for(has_white, has_color),
            own_label_count=len(own),
            dropped_neighbour_labels=dropped,
            label_confidences=[round(d.confidence, 4) for d in own],
        ))

    return ScreeningResult(trays=results, image_width=width, image_height=height,
                           params=params)
