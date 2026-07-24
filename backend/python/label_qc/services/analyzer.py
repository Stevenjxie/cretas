from __future__ import annotations

import asyncio
import base64
import io
import json
import logging
import re
from dataclasses import dataclass
from typing import Any, Dict, Iterable, List, Sequence, Tuple

from PIL import Image, ImageOps

from common.llm_metrics import llm_caller_context
from common.llm_router import SLOT, call_chain

logger = logging.getLogger(__name__)

PROMPT_VERSION = "label-presence-high-recall-v1"
MAX_IMAGE_BYTES = 10 * 1024 * 1024
MAX_TILE_EDGE = 1280
MAX_CONCURRENT_VL_CALLS = 2

_ALLOWED_VERDICTS = {
    "CLEAR",
    "MISSING_WHITE_LABEL",
    "MISSING_COLOR_LABEL",
    "BOTH_MISSING",
    "UNJUDGEABLE",
}

_PROMPT = """
你是食品包装标签质检员。当前图片是原照片的一个重叠局部切片，里面可能有多层堆叠的白色肉盒。

每个可见肉盒正常时应同时具有：
1. 彩标：沿盒边的红黑色长条品牌标签；
2. 白标：白色矩形称重/条码标签。

本任务只判断标签是否存在，不识别文字是否正确。请逐盒检查所有能看见的盒盖和露出的边缘，特别注意堆叠层边缘。目标是高召回：宁可把可疑区域交给人工，也不要漏掉缺标。

判断规则：
- 明确看到某盒没有白色矩形标签：MISSING_WHITE_LABEL
- 明确看到某盒没有红黑色长条彩标：MISSING_COLOR_LABEL
- 两种都缺：BOTH_MISSING
- 遮挡、反光、分辨率不足或只露出很窄边缘，无法可靠确认：UNJUDGEABLE
- 只有当切片内所有可核查盒子都明确同时有两种标签时才用 CLEAR

region 是最可疑盒子在当前切片中的位置，坐标范围 0 到 1000，格式 [x_min,y_min,x_max,y_max]。
只返回一行 JSON，不要 Markdown，不要解释：
{"verdict":"CLEAR|MISSING_WHITE_LABEL|MISSING_COLOR_LABEL|BOTH_MISSING|UNJUDGEABLE","confidence":0.0,"region":[0,0,1000,1000],"evidence":"不超过40字"}
""".strip()


@dataclass(frozen=True)
class Tile:
    index: int
    box: Tuple[int, int, int, int]
    image: Image.Image


def _axis_starts(length: int, count: int, overlap_ratio: float) -> List[int]:
    if count <= 1:
        return [0]
    tile_length = min(length, int(round(length / (1 + (count - 1) * (1 - overlap_ratio)))))
    last_start = max(0, length - tile_length)
    return [int(round(i * last_start / (count - 1))) for i in range(count)]


def build_tiles(image: Image.Image) -> List[Tile]:
    """Split a photo into stable overlapping regions while preserving source coordinates."""
    width, height = image.size
    columns = 2
    rows = 4 if height / max(width, 1) >= 1.2 else 2
    overlap = 0.18

    x_starts = _axis_starts(width, columns, overlap)
    y_starts = _axis_starts(height, rows, overlap)
    tile_width = width if columns == 1 else width - x_starts[-1]
    tile_height = height if rows == 1 else height - y_starts[-1]

    tiles: List[Tile] = []
    for y in y_starts:
        for x in x_starts:
            right = min(width, x + tile_width)
            bottom = min(height, y + tile_height)
            tiles.append(Tile(len(tiles), (x, y, right, bottom), image.crop((x, y, right, bottom))))
    return tiles


def _encode_tile(image: Image.Image) -> str:
    tile = image.convert("RGB")
    tile.thumbnail((MAX_TILE_EDGE, MAX_TILE_EDGE), Image.Resampling.LANCZOS)
    buffer = io.BytesIO()
    tile.save(buffer, format="JPEG", quality=88, optimize=True)
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


def _parse_json_object(text: str) -> Dict[str, Any]:
    cleaned = re.sub(r"^```(?:json)?\s*|\s*```$", "", text.strip(), flags=re.IGNORECASE)
    try:
        value = json.loads(cleaned)
    except json.JSONDecodeError:
        match = re.search(r"\{.*\}", cleaned, flags=re.DOTALL)
        if not match:
            raise
        value = json.loads(match.group(0))
    if not isinstance(value, dict):
        raise ValueError("VL response is not a JSON object")
    return value


def _clamp(value: Any, low: float, high: float) -> float:
    try:
        number = float(value)
    except (TypeError, ValueError):
        return low
    return min(high, max(low, number))


def _normalise_result(raw: Dict[str, Any]) -> Dict[str, Any]:
    verdict = str(raw.get("verdict", "")).strip().upper()
    if verdict not in _ALLOWED_VERDICTS:
        raise ValueError(f"unsupported verdict: {verdict or '<empty>'}")

    region = raw.get("region")
    if not isinstance(region, Sequence) or len(region) != 4:
        region = [0, 0, 1000, 1000]
    coords = [_clamp(value, 0, 1000) for value in region]
    x_min, x_max = sorted((coords[0], coords[2]))
    y_min, y_max = sorted((coords[1], coords[3]))
    if x_max - x_min < 20 or y_max - y_min < 20:
        x_min, y_min, x_max, y_max = 0, 0, 1000, 1000

    return {
        "verdict": verdict,
        "confidence": _clamp(raw.get("confidence"), 0, 1),
        "region": [x_min, y_min, x_max, y_max],
        "evidence": str(raw.get("evidence", ""))[:120],
    }


def _source_bbox(tile: Tile, region: Sequence[float], image_size: Tuple[int, int]) -> List[float]:
    left, top, right, bottom = tile.box
    tile_width = right - left
    tile_height = bottom - top
    image_width, image_height = image_size
    x_min = (left + tile_width * region[0] / 1000) / image_width
    y_min = (top + tile_height * region[1] / 1000) / image_height
    x_max = (left + tile_width * region[2] / 1000) / image_width
    y_max = (top + tile_height * region[3] / 1000) / image_height
    return [round(_clamp(v, 0, 1), 6) for v in (x_min, y_min, x_max, y_max)]


def _iou(first: Sequence[float], second: Sequence[float]) -> float:
    x1 = max(first[0], second[0])
    y1 = max(first[1], second[1])
    x2 = min(first[2], second[2])
    y2 = min(first[3], second[3])
    intersection = max(0.0, x2 - x1) * max(0.0, y2 - y1)
    if intersection <= 0:
        return 0.0
    first_area = max(0.0, first[2] - first[0]) * max(0.0, first[3] - first[1])
    second_area = max(0.0, second[2] - second[0]) * max(0.0, second[3] - second[1])
    union = first_area + second_area - intersection
    return intersection / union if union else 0.0


def deduplicate_candidates(candidates: Iterable[Dict[str, Any]]) -> List[Dict[str, Any]]:
    ordered = sorted(candidates, key=lambda item: item["confidence"], reverse=True)
    kept: List[Dict[str, Any]] = []
    for candidate in ordered:
        duplicate = next(
            (
                existing
                for existing in kept
                if existing["label"] == candidate["label"]
                and _iou(existing["bbox"], candidate["bbox"]) >= 0.45
            ),
            None,
        )
        if duplicate is None:
            kept.append(candidate)
        else:
            duplicate["sourceTiles"] = sorted(
                set(duplicate["sourceTiles"] + candidate["sourceTiles"])
            )
    return kept


class LabelQcAnalyzer:
    async def _analyze_tile(
        self,
        tile: Tile,
        image_size: Tuple[int, int],
        semaphore: asyncio.Semaphore,
    ) -> Tuple[Dict[str, Any], str]:
        payload = {
            "messages": [
                {
                    "role": "user",
                    "content": [
                        {
                            "type": "image_url",
                            "image_url": {
                                "url": f"data:image/jpeg;base64,{_encode_tile(tile.image)}"
                            },
                        },
                        {"type": "text", "text": _PROMPT},
                    ],
                }
            ],
            "temperature": 0,
            "max_tokens": 300,
        }
        async with semaphore:
            with llm_caller_context("label_qc.tile"):
                response = await call_chain(SLOT.VL, payload, timeout=35.0)
        parsed = _normalise_result(_parse_json_object(_extract_content(response)))
        parsed["bbox"] = _source_bbox(tile, parsed["region"], image_size)
        return parsed, str(response.get("model", "unknown"))

    async def analyze(self, image_bytes: bytes) -> Dict[str, Any]:
        if not image_bytes:
            raise ValueError("empty image")
        if len(image_bytes) > MAX_IMAGE_BYTES:
            raise ValueError("image exceeds 10 MB limit")

        try:
            source = Image.open(io.BytesIO(image_bytes))
            source = ImageOps.exif_transpose(source)
            source.load()
            source = source.convert("RGB")
        except Exception as exc:
            raise ValueError("invalid or unsupported image") from exc

        tiles = build_tiles(source)
        semaphore = asyncio.Semaphore(MAX_CONCURRENT_VL_CALLS)

        async def safe_analyze(tile: Tile) -> Tuple[Dict[str, Any], str]:
            try:
                return await asyncio.wait_for(
                    self._analyze_tile(tile, source.size, semaphore),
                    timeout=45.0,
                )
            except Exception as exc:
                logger.warning("Label QC tile %s failed: %s", tile.index, exc)
                return (
                    {
                        "verdict": "UNJUDGEABLE",
                        "confidence": 0.0,
                        "region": [0, 0, 1000, 1000],
                        "bbox": _source_bbox(tile, [0, 0, 1000, 1000], source.size),
                        "evidence": f"视觉分析失败：{type(exc).__name__}",
                    },
                    "unknown",
                )

        results = await asyncio.gather(*(safe_analyze(tile) for tile in tiles))
        candidates: List[Dict[str, Any]] = []
        models = set()

        for tile, (result, model) in zip(tiles, results):
            models.add(model)
            verdict = result["verdict"]
            if verdict == "CLEAR":
                continue
            labels = (
                ["MISSING_WHITE_LABEL", "MISSING_COLOR_LABEL"]
                if verdict == "BOTH_MISSING"
                else [verdict]
            )
            for label in labels:
                candidates.append(
                    {
                        "label": label,
                        "confidence": result["confidence"],
                        "bbox": result["bbox"],
                        "evidence": result["evidence"],
                        "sourceTiles": [tile.index],
                    }
                )

        candidates = deduplicate_candidates(candidates)
        for index, candidate in enumerate(candidates, start=1):
            candidate["candidateId"] = f"ai-{index}"

        return {
            "verdict": "SUSPECTED" if candidates else "NO_DEFECT_FOUND",
            "candidates": candidates,
            "model": ",".join(sorted(models)),
            "promptVersion": PROMPT_VERSION,
            "imageWidth": source.width,
            "imageHeight": source.height,
            "tilesAnalyzed": len(tiles),
        }
