"""ONNX YOLO detectors for label QC screening.

Runs on CPU via onnxruntime so the production server does not need torch.
NMS is baked into the exported graph, so post-processing is only letterbox
inversion.

Model files are not tracked in git; they are uploaded by the deploy script to
``LABEL_QC_MODEL_DIR`` (default ``backend/python/label_qc/models``). If the files
are missing the caller must fall back to the VL-only analyzer.
"""
from __future__ import annotations

import logging
import os
import threading
from dataclasses import dataclass
from pathlib import Path
from typing import List, Optional, Sequence, Tuple

import numpy as np
from PIL import Image

try:  # OpenCV is not a declared dependency; PIL is the portable fallback.
    import cv2

    _CV2_AVAILABLE = True
except ImportError:  # pragma: no cover - depends on host
    cv2 = None  # type: ignore[assignment]
    _CV2_AVAILABLE = False

logger = logging.getLogger(__name__)

DEFAULT_MODEL_DIR = Path(__file__).resolve().parent.parent / "models"
TRAY_MODEL_FILE = "tray.onnx"
LABEL_MODEL_FILE = "label.onnx"

# Class ids inside the label detector
CLASS_WHITE_LABEL = 0
CLASS_COLOR_LABEL = 1


@dataclass(frozen=True)
class Detection:
    """A single detection in ORIGINAL image pixel coordinates."""

    x0: float
    y0: float
    x1: float
    y1: float
    confidence: float
    class_id: int

    @property
    def center(self) -> Tuple[float, float]:
        return (self.x0 + self.x1) / 2, (self.y0 + self.y1) / 2

    def as_xyxy(self) -> List[float]:
        return [self.x0, self.y0, self.x1, self.y1]


def _resize(image: np.ndarray, new_w: int, new_h: int) -> np.ndarray:
    """Resize mirroring ultralytics (cv2.INTER_LINEAR) when OpenCV is present.

    PIL's BILINEAR antialiases on downscale while cv2's INTER_LINEAR does not.
    At the ~4x downscale used for full frames that shifts box corners, so cv2 is
    preferred; PIL is an accepted fallback because the end-to-end verdict was
    measured to be unaffected (see docs/specs/label-qc-yolo-screening.md).
    """
    if _CV2_AVAILABLE:
        return cv2.resize(image, (new_w, new_h), interpolation=cv2.INTER_LINEAR)
    return np.array(Image.fromarray(image).resize((new_w, new_h), Image.Resampling.BILINEAR))


def _letterbox(image: np.ndarray, size: int) -> Tuple[np.ndarray, float, int, int]:
    """Ultralytics-style letterbox: aspect-preserving resize, centred 114 padding."""
    height, width = image.shape[:2]
    ratio = min(size / height, size / width)
    new_w, new_h = int(round(width * ratio)), int(round(height * ratio))
    resized = _resize(image, new_w, new_h)
    canvas = np.full((size, size, 3), 114, dtype=np.uint8)
    pad_w, pad_h = (size - new_w) / 2, (size - new_h) / 2
    left, top = int(round(pad_w - 0.1)), int(round(pad_h - 0.1))
    canvas[top:top + new_h, left:left + new_w] = resized
    return canvas, ratio, left, top


class _OnnxDetector:
    """Thin wrapper around one exported detector."""

    def __init__(self, model_path: Path, imgsz: int) -> None:
        import onnxruntime as ort

        self._imgsz = imgsz
        options = ort.SessionOptions()
        # The FastAPI process already runs many things; keep the detector modest.
        options.intra_op_num_threads = int(os.getenv("LABEL_QC_ONNX_THREADS", "2"))
        options.graph_optimization_level = ort.GraphOptimizationLevel.ORT_ENABLE_ALL
        self._session = ort.InferenceSession(
            str(model_path), sess_options=options, providers=["CPUExecutionProvider"]
        )
        self._input_name = self._session.get_inputs()[0].name
        self._lock = threading.Lock()

    def detect(self, image: np.ndarray, conf: float) -> List[Detection]:
        canvas, ratio, left, top = _letterbox(image, self._imgsz)
        blob = canvas.astype(np.float32).transpose(2, 0, 1)[None] / 255.0
        # onnxruntime sessions are not guaranteed thread-safe for all providers
        with self._lock:
            raw = self._session.run(None, {self._input_name: blob})[0]

        rows = raw[0]
        rows = rows[rows[:, 4] >= conf]
        if not len(rows):
            return []

        height, width = image.shape[:2]
        boxes = rows[:, :4].copy()
        boxes[:, [0, 2]] = (boxes[:, [0, 2]] - left) / ratio
        boxes[:, [1, 3]] = (boxes[:, [1, 3]] - top) / ratio
        boxes[:, [0, 2]] = boxes[:, [0, 2]].clip(0, width)
        boxes[:, [1, 3]] = boxes[:, [1, 3]].clip(0, height)

        return [
            Detection(
                x0=float(b[0]), y0=float(b[1]), x1=float(b[2]), y1=float(b[3]),
                confidence=float(row[4]), class_id=int(row[5]),
            )
            for b, row in zip(boxes, rows)
            if b[2] - b[0] > 1 and b[3] - b[1] > 1
        ]


class LabelQcYoloModels:
    """Lazily-loaded pair of detectors. Absent model files disable screening."""

    def __init__(self, model_dir: Optional[Path] = None) -> None:
        self._model_dir = Path(
            model_dir or os.getenv("LABEL_QC_MODEL_DIR") or DEFAULT_MODEL_DIR
        )
        self._tray: Optional[_OnnxDetector] = None
        self._label: Optional[_OnnxDetector] = None
        self._loaded = False
        self._load_error: Optional[str] = None
        self._lock = threading.Lock()

    @property
    def model_dir(self) -> Path:
        return self._model_dir

    def _load(self) -> None:
        if self._loaded:
            return
        with self._lock:
            if self._loaded:
                return
            tray_path = self._model_dir / TRAY_MODEL_FILE
            label_path = self._model_dir / LABEL_MODEL_FILE
            missing = [p.name for p in (tray_path, label_path) if not p.is_file()]
            if missing:
                self._load_error = f"missing model files in {self._model_dir}: {missing}"
                logger.warning("Label QC YOLO screening disabled: %s", self._load_error)
            else:
                try:
                    self._tray = _OnnxDetector(tray_path, imgsz=960)
                    self._label = _OnnxDetector(label_path, imgsz=640)
                    logger.info("Label QC YOLO models loaded from %s", self._model_dir)
                except Exception as exc:  # pragma: no cover - environment dependent
                    self._tray = self._label = None
                    self._load_error = f"{type(exc).__name__}: {exc}"
                    logger.warning("Label QC YOLO load failed: %s", self._load_error)
            self._loaded = True

    @property
    def available(self) -> bool:
        self._load()
        return self._tray is not None and self._label is not None

    @property
    def load_error(self) -> Optional[str]:
        self._load()
        return self._load_error

    def detect_trays(self, image: np.ndarray, conf: float, ) -> List[Detection]:
        self._load()
        if self._tray is None:
            raise RuntimeError(self._load_error or "tray model unavailable")
        return self._tray.detect(image, conf)

    def detect_labels(self, crop: np.ndarray, conf: float) -> List[Detection]:
        self._load()
        if self._label is None:
            raise RuntimeError(self._load_error or "label model unavailable")
        return self._label.detect(crop, conf)


def crop_with_padding(
    image: np.ndarray, box: Sequence[float], pad_ratio: float
) -> Tuple[np.ndarray, Tuple[float, float, float, float]]:
    """Crop a tray box with padding; returns the crop and its source rectangle.

    The padding keeps surrounding context (which measurably helps the label
    detector) at the cost of pulling in neighbouring trays' labels -- those are
    filtered out later by ownership, not by tightening the crop.
    """
    height, width = image.shape[:2]
    x0, y0, x1, y1 = box
    box_w, box_h = x1 - x0, y1 - y0
    cx0 = max(0.0, x0 - box_w * pad_ratio)
    cy0 = max(0.0, y0 - box_h * pad_ratio)
    cx1 = min(float(width), x1 + box_w * pad_ratio)
    cy1 = min(float(height), y1 + box_h * pad_ratio)
    crop = image[int(cy0):int(cy1), int(cx0):int(cx1)]
    return crop, (cx0, cy0, cx1, cy1)


def resize_crop(crop: np.ndarray, target_width: int) -> np.ndarray:
    height, width = crop.shape[:2]
    if width == target_width:
        return crop
    new_h = max(1, int(round(height * target_width / width)))
    return _resize(crop, target_width, new_h)
