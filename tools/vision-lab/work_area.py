"""Fail-closed geometry contract for the Liushanmen metal work-area ROI."""
from __future__ import annotations

import math
from typing import Any, Iterable, Sequence


FORMAT = "normalised_polygon_v1"
INSIDE_WORK_AREA = "inside_work_area"
OUTSIDE_WORK_AREA = "outside_work_area"
UNKNOWN_WORK_AREA = "unknown_work_area"
WORK_AREA_GROUPS = (INSIDE_WORK_AREA, OUTSIDE_WORK_AREA, UNKNOWN_WORK_AREA)
MIN_POLYGON_AREA = 0.02


def _point(value: Any) -> tuple[float, float]:
    if not isinstance(value, (list, tuple)) or len(value) != 2:
        raise ValueError("work-area point must contain exactly two coordinates")
    if any(isinstance(item, bool) or not isinstance(item, (int, float)) for item in value):
        raise ValueError("work-area coordinates must be finite numbers")
    point = float(value[0]), float(value[1])
    if not all(math.isfinite(item) and 0.0 <= item <= 1.0 for item in point):
        raise ValueError("work-area coordinates must be normalised to [0, 1]")
    return point


def polygon_area(polygon: Sequence[Sequence[float]]) -> float:
    points = [_point(value) for value in polygon]
    return abs(sum(
        left[0] * right[1] - right[0] * left[1]
        for left, right in zip(points, points[1:] + points[:1])
    )) / 2.0


def _orientation(a: tuple[float, float], b: tuple[float, float], c: tuple[float, float]) -> float:
    return (b[0] - a[0]) * (c[1] - a[1]) - (b[1] - a[1]) * (c[0] - a[0])


def _segments_cross(
    a: tuple[float, float], b: tuple[float, float],
    c: tuple[float, float], d: tuple[float, float],
) -> bool:
    return (
        _orientation(a, b, c) * _orientation(a, b, d) < 0
        and _orientation(c, d, a) * _orientation(c, d, b) < 0
    )


def validate_polygon(
    polygon: Any, *, minimum_area: float = MIN_POLYGON_AREA,
) -> list[list[float]]:
    """Validate one perspective quadrilateral without silently reordering it."""
    if not isinstance(polygon, list) or len(polygon) != 4:
        raise ValueError("work-area polygon requires exactly four ordered corner points")
    points = [_point(value) for value in polygon]
    if len(set(points)) != 4:
        raise ValueError("work-area polygon corner points must be distinct")
    if _segments_cross(points[0], points[1], points[2], points[3]) or _segments_cross(
        points[1], points[2], points[3], points[0]
    ):
        raise ValueError("work-area polygon must not self-intersect")
    normalised = [[round(x, 6), round(y, 6)] for x, y in points]
    if polygon_area(normalised) < minimum_area:
        raise ValueError(f"work-area polygon area must be at least {minimum_area:.3f}")
    return normalised


def point_in_polygon(point: Sequence[float], polygon: Sequence[Sequence[float]]) -> bool:
    """Return True for points inside or on the boundary of a validated polygon."""
    x, y = _point(point)
    points = [tuple(value) for value in validate_polygon(list(polygon))]
    inside = False
    for left, right in zip(points, points[1:] + points[:1]):
        cross = _orientation(left, right, (x, y))
        if abs(cross) <= 1e-10 and (
            min(left[0], right[0]) - 1e-10 <= x <= max(left[0], right[0]) + 1e-10
            and min(left[1], right[1]) - 1e-10 <= y <= max(left[1], right[1]) + 1e-10
        ):
            return True
        if (left[1] > y) != (right[1] > y):
            crossing_x = left[0] + (y - left[1]) * (right[0] - left[0]) / (right[1] - left[1])
            if x < crossing_x:
                inside = not inside
    return inside


def validate_box(box: Any) -> list[float]:
    if not isinstance(box, (list, tuple)) or len(box) != 4:
        raise ValueError("tray box must contain normalised xyxy coordinates")
    if any(isinstance(value, bool) or not isinstance(value, (int, float)) for value in box):
        raise ValueError("tray box coordinates must be finite numbers")
    values = [float(value) for value in box]
    if not all(math.isfinite(value) and 0.0 <= value <= 1.0 for value in values):
        raise ValueError("tray box coordinates must be normalised to [0, 1]")
    if values[0] >= values[2] or values[1] >= values[3]:
        raise ValueError("tray box must have positive area")
    return values


def classify_box_center(box: Any, polygon: Sequence[Sequence[float]]) -> str:
    x0, y0, x1, y1 = validate_box(box)
    return INSIDE_WORK_AREA if point_in_polygon(((x0 + x1) / 2, (y0 + y1) / 2), polygon) else OUTSIDE_WORK_AREA


def classify_pixel_box(
    box: Any, image_width: int, image_height: int, annotation: dict[str, Any] | None,
) -> str:
    """Classify a pixel-space tray box, failing closed when human ROI truth is absent."""
    if annotation is None or annotation.get("judgeable") is not True:
        return UNKNOWN_WORK_AREA
    if image_width <= 0 or image_height <= 0:
        raise ValueError("work-area classification requires positive image dimensions")
    if not isinstance(box, (list, tuple)) or len(box) != 4:
        raise ValueError("pixel tray box must contain xyxy coordinates")
    if any(isinstance(value, bool) or not isinstance(value, (int, float)) for value in box):
        raise ValueError("pixel tray box coordinates must be finite numbers")
    values = [float(value) for value in box]
    if not all(math.isfinite(value) for value in values):
        raise ValueError("pixel tray box coordinates must be finite numbers")
    if not (0.0 <= values[0] < values[2] <= image_width and 0.0 <= values[1] < values[3] <= image_height):
        raise ValueError("pixel tray box must stay inside the image")
    normalised = [
        values[0] / image_width, values[1] / image_height,
        values[2] / image_width, values[3] / image_height,
    ]
    return classify_box_center(normalised, annotation["polygon"])


def classify_boxes(boxes: Iterable[Any], polygon: Sequence[Sequence[float]]) -> dict[str, int]:
    counts = {INSIDE_WORK_AREA: 0, OUTSIDE_WORK_AREA: 0}
    for box in boxes:
        counts[classify_box_center(box, polygon)] += 1
    return counts


def validate_human_annotation(payload: Any, *, expected_photo_id: str | None = None) -> dict[str, Any]:
    if not isinstance(payload, dict):
        raise ValueError("work-area annotation must be an object")
    if expected_photo_id is not None and payload.get("photo_id") not in (None, expected_photo_id):
        raise ValueError("work-area annotation photo_id mismatch")
    if payload.get("reviewed") is not True or payload.get("source") != "human":
        raise ValueError("work-area annotation requires reviewed=true and source=human")
    if payload.get("format") != FORMAT:
        raise ValueError(f"work-area annotation requires format={FORMAT}")
    if not isinstance(payload.get("judgeable"), bool):
        raise ValueError("work-area annotation requires an explicit judgeable decision")
    result = dict(payload)
    result["format"] = FORMAT
    if payload["judgeable"]:
        result["polygon"] = validate_polygon(payload.get("polygon"))
        result.pop("unjudgeable_reason", None)
    else:
        if payload.get("polygon") not in (None, []):
            raise ValueError("unjudgeable work-area annotation must not contain a polygon")
        if payload.get("unjudgeable_reason") != "work_area_not_visible_or_unjudgeable":
            raise ValueError("unjudgeable work-area annotation requires the canonical reason")
        result["polygon"] = None
    return result
