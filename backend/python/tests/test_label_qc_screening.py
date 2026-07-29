"""Tests for the YOLO screening path.

These run without the ONNX model files (CI has none), so they cover the logic
that does not need a session: letterbox geometry, tray ownership filtering,
rule inference, graceful degradation, and the response contract.
"""
from __future__ import annotations

import numpy as np
import pytest

from label_qc.services.screening import (
    ScreeningParams,
    VERDICT_MISSING_BOTH,
    VERDICT_MISSING_COLOR,
    VERDICT_MISSING_WHITE,
    VERDICT_OK,
    _owns,
    _verdict_for,
    screen_image,
)
from label_qc.services.yolo_detector import (
    CLASS_COLOR_LABEL,
    CLASS_WHITE_LABEL,
    Detection,
    LabelQcYoloModels,
    _letterbox,
    crop_with_padding,
    resize_crop,
)


# --------------------------------------------------------------- letterbox
def test_letterbox_preserves_aspect_and_centres_padding():
    image = np.zeros((4096, 3072, 3), dtype=np.uint8)
    canvas, ratio, left, top = _letterbox(image, 960)
    assert canvas.shape == (960, 960, 3)
    assert ratio == pytest.approx(960 / 4096)
    # portrait image -> padded left/right, not top/bottom
    assert top == 0
    assert left == pytest.approx((960 - round(3072 * ratio)) / 2, abs=1)
    # padding uses the 114 grey the model was trained with
    assert canvas[0, 0].tolist() == [114, 114, 114]


def test_letterbox_square_image_has_no_padding():
    image = np.zeros((640, 640, 3), dtype=np.uint8)
    canvas, ratio, left, top = _letterbox(image, 640)
    assert (ratio, left, top) == (1.0, 0, 0)
    assert canvas.shape == (640, 640, 3)


# --------------------------------------------------------------- rule inference
@pytest.mark.parametrize(
    "has_white,has_color,expected",
    [
        (True, True, VERDICT_OK),
        (False, True, VERDICT_MISSING_WHITE),
        (True, False, VERDICT_MISSING_COLOR),
        (False, False, VERDICT_MISSING_BOTH),
    ],
)
def test_verdict_is_inferred_from_present_labels(has_white, has_color, expected):
    assert _verdict_for(has_white, has_color) == expected


# --------------------------------------------------------------- ownership
def _label_at(cx: float, cy: float) -> Detection:
    return Detection(cx - 5, cy - 5, cx + 5, cy + 5, 0.9, CLASS_WHITE_LABEL)


def test_label_inside_tray_box_is_owned():
    tray_box = [100.0, 100.0, 300.0, 200.0]
    crop_rect = (80.0, 90.0, 320.0, 210.0)      # padded crop in source coords
    crop_shape = (120, 240, 3)                   # crop rendered 1:1 here
    # centre of the crop maps back to (200, 150) which is inside the tray box
    assert _owns(tray_box, _label_at(120, 60), crop_rect, crop_shape) is True


def test_neighbour_label_in_padding_is_not_owned():
    tray_box = [100.0, 100.0, 300.0, 200.0]
    crop_rect = (80.0, 90.0, 320.0, 210.0)
    crop_shape = (120, 240, 3)
    # x=5 in crop maps to source x=85, which is in the padding, not the tray
    assert _owns(tray_box, _label_at(5, 60), crop_rect, crop_shape) is False


# --------------------------------------------------------------- cropping
def test_crop_with_padding_expands_and_clamps_to_image():
    image = np.zeros((1000, 1000, 3), dtype=np.uint8)
    crop, rect = crop_with_padding(image, [0.0, 0.0, 100.0, 100.0], 0.14)
    # clamped at the top-left corner, expanded on the other two sides
    assert rect[0] == 0.0 and rect[1] == 0.0
    assert rect[2] == pytest.approx(114.0)
    assert crop.shape[0] > 0 and crop.shape[1] > 0


def test_resize_crop_keeps_aspect_ratio():
    crop = np.zeros((200, 400, 3), dtype=np.uint8)
    out = resize_crop(crop, 640)
    assert out.shape[1] == 640
    assert out.shape[0] == pytest.approx(320, abs=1)


# --------------------------------------------------------------- degradation
def test_models_report_unavailable_when_files_missing(tmp_path):
    models = LabelQcYoloModels(tmp_path)
    assert models.available is False
    assert "missing model files" in (models.load_error or "")


def test_detect_raises_when_unavailable(tmp_path):
    models = LabelQcYoloModels(tmp_path)
    with pytest.raises(RuntimeError):
        models.detect_trays(np.zeros((10, 10, 3), dtype=np.uint8), 0.6)


# --------------------------------------------------------------- screening flow
class _StubModels:
    """Stands in for the ONNX pair so the flow can be tested without weights."""

    def __init__(self, trays, labels_by_tray):
        self._trays = trays
        self._labels = labels_by_tray
        self.calls = 0

    def detect_trays(self, image, conf):
        return list(self._trays)

    def detect_labels(self, crop, conf):
        result = self._labels[self.calls] if self.calls < len(self._labels) else []
        self.calls += 1
        return list(result)


def test_screen_image_flags_only_the_tray_missing_a_label():
    image = np.zeros((1000, 1000, 3), dtype=np.uint8)
    trays = [
        Detection(100, 100, 300, 300, 0.95, 0),   # complete
        Detection(600, 100, 800, 300, 0.93, 0),   # missing white
    ]
    # crop is 640 wide; both labels sit near the centre so ownership holds
    complete = [Detection(300, 200, 360, 260, 0.9, CLASS_WHITE_LABEL),
                Detection(300, 300, 360, 360, 0.9, CLASS_COLOR_LABEL)]
    only_colour = [Detection(300, 300, 360, 360, 0.9, CLASS_COLOR_LABEL)]
    models = _StubModels(trays, [complete, only_colour])

    result = screen_image(image, models, ScreeningParams(own_labels_only=False))

    assert [t.verdict for t in result.trays] == [VERDICT_OK, VERDICT_MISSING_WHITE]
    assert len(result.suspects) == 1
    assert result.suspects[0].index == 1


def test_screen_image_never_silently_clears_an_unjudgeably_small_tray():
    image = np.zeros((1000, 1000, 3), dtype=np.uint8)
    tiny = [Detection(10, 10, 30, 30, 0.9, 0)]
    models = _StubModels(tiny, [[]])
    result = screen_image(image, models, ScreeningParams(min_crop_px=200))
    assert result.trays[0].verdict == VERDICT_MISSING_BOTH
    assert result.trays[0].is_suspect is True


def test_screen_image_respects_tray_cap():
    image = np.zeros((2000, 2000, 3), dtype=np.uint8)
    trays = [Detection(i * 10, 0, i * 10 + 50, 50, 0.9 - i * 0.001, 0) for i in range(80)]
    models = _StubModels(trays, [[] for _ in range(80)])
    result = screen_image(image, models, ScreeningParams(max_trays=25, min_crop_px=1))
    assert len(result.trays) == 25


# --------------------------------------------------------------- label geometry
def test_detected_labels_are_mapped_back_to_source_pixels():
    """The review UI draws these on the ORIGINAL photo, so coordinates must be
    in source pixels, not crop pixels."""
    image = np.zeros((1000, 1000, 3), dtype=np.uint8)
    tray = Detection(200, 200, 600, 400, 0.95, 0)
    # crop is tray +14% padding, then resized to 640 wide
    white = Detection(320, 40, 380, 80, 0.9, CLASS_WHITE_LABEL)
    models = _StubModels([tray], [[white]])

    result = screen_image(image, models, ScreeningParams(own_labels_only=False))

    labels = result.trays[0].labels
    assert len(labels) == 1
    box = labels[0].box
    # must land inside the padded crop region of the source image, not near 320,40
    assert 150 <= box[0] <= 700, f"x 未映射回原图坐标: {box}"
    assert 150 <= box[1] <= 500, f"y 未映射回原图坐标: {box}"
    assert labels[0].is_white is True


def test_labels_dropped_by_ownership_are_not_reported():
    """A neighbour's label must not be drawn on this tray."""
    image = np.zeros((1000, 1000, 3), dtype=np.uint8)
    tray = Detection(200, 200, 600, 400, 0.95, 0)
    far_left = Detection(2, 2, 20, 20, 0.9, CLASS_WHITE_LABEL)   # in padding
    models = _StubModels([tray], [[far_left]])

    result = screen_image(image, models, ScreeningParams(own_labels_only=True))

    assert result.trays[0].labels == []
    assert result.trays[0].dropped_neighbour_labels == 1
