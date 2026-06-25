"""
异物检测 v2 — 分区扫描策略
将每张图片切成 2x2 (4块) 局部放大后分别送检，提高微小异物检出率
"""

import base64
import json
import io
import os
import sys
import httpx
from PIL import Image
from pathlib import Path

API_KEY = os.environ.get("DASHSCOPE_API_KEY", "")
BASE_URL = "https://dashscope.aliyuncs.com/compatible-mode/v1"
MODEL = "qwen-vl-max-latest"

IMAGE_DIR = r"d:\xwechat_files\wxid_a2m0bim6zcm212_82ca\temp\RWTemp\2026-03\0d56910e217b694af81d3943df1ff104"
IMAGE_FILES = [
    "9148fe77352de7d0a07981c9de66cc6e.jpg",   # Image 1 - white spot mid-lower-right
    "c20892e0fea7f920ea49215e9702ca76.jpg",   # Image 2 - blue dot (already detected)
    "c72cf022eee8794759f0fe51a347a5c0.jpg",   # Image 3 - hair/thread right side
    "05bfda432fa8a35a900277ae162c0ae6.jpg",   # Image 4 - discolored spot upper-right
    "09ae6c082ac4939159fe84327e6b4368.jpg",   # Image 5 - hair between rolls lower-center
    "bfeeb0ff1cb6e0b2b08a9ea34b41fb2c.jpg",   # Image 6 - (control, previously clean)
]

ZONE_PROMPT = """你是食品质检AI专家，专精异物检测。这是一张牛肉卷包装的**局部放大图**。

请用极其严格的标准检查这个局部区域，寻找以下异物：

1. **毛发/线头**: 极细的深色或浅色丝状物，可能只有1-2像素宽
2. **塑料碎片**: 透明或半透明的小碎片，可能与包装膜混淆
3. **颜色异常点**: 任何不属于红色肉质或白色脂肪的颜色点（蓝、绿、黄、黑斑点）
4. **白色异物**: 不规则的白色碎屑（骨碎片、纸屑等），区别于正常的脂肪纹理——脂肪纹理是平滑连续的，异物是不规则的孤立点
5. **变色/霉变**: 灰绿色、暗褐色异常区域

正常的东西（不要报告）：
- 红色肉质和白色脂肪花纹（大理石纹）
- 肉卷之间的正常缝隙和阴影
- 包装膜的正常反光

以 JSON 返回：
{
    "has_foreign_object": true/false,
    "confidence": 0.0-1.0,
    "foreign_objects": [
        {
            "type": "毛发/塑料/颜色异常/白色碎屑/变色",
            "description": "详细描述异物的外观特征",
            "location_in_zone": "在这个局部图中的位置（上/下/左/右/中间）",
            "size_estimate": "极小/小/中/大",
            "color": "异物颜色",
            "severity": "HIGH/MEDIUM/LOW"
        }
    ],
    "zone_condition": "该区域整体评估"
}

关键：宁可多报（误报）也不要漏报。即使只有轻微怀疑也请报告。仅返回JSON。"""


def encode_image_bytes(img_bytes: bytes) -> str:
    return base64.b64encode(img_bytes).decode("utf-8")


def crop_image_to_zones(image_path: str) -> list:
    """Crop image into 2x2 zones, each overlapping slightly for edge coverage"""
    img = Image.open(image_path)
    w, h = img.size

    # Add 10% overlap to catch objects on zone boundaries
    overlap_x = int(w * 0.1)
    overlap_y = int(h * 0.1)

    mid_x = w // 2
    mid_y = h // 2

    zones = [
        ("top-left",     img.crop((0, 0, mid_x + overlap_x, mid_y + overlap_y))),
        ("top-right",    img.crop((mid_x - overlap_x, 0, w, mid_y + overlap_y))),
        ("bottom-left",  img.crop((0, mid_y - overlap_y, mid_x + overlap_x, h))),
        ("bottom-right", img.crop((mid_x - overlap_x, mid_y - overlap_y, w, h))),
    ]

    result = []
    for name, zone_img in zones:
        buf = io.BytesIO()
        zone_img.save(buf, format="JPEG", quality=95)
        result.append((name, encode_image_bytes(buf.getvalue())))

    return result


def analyze_zone(image_b64: str, zone_name: str, image_name: str) -> dict:
    """Call VL API for a single zone"""
    client = httpx.Client(timeout=120.0)

    response = client.post(
        f"{BASE_URL}/chat/completions",
        headers={
            "Authorization": f"Bearer {API_KEY}",
            "Content-Type": "application/json"
        },
        json={
            "model": MODEL,
            "messages": [
                {
                    "role": "user",
                    "content": [
                        {
                            "type": "image_url",
                            "image_url": {
                                "url": f"data:image/jpeg;base64,{image_b64}"
                            }
                        },
                        {
                            "type": "text",
                            "text": ZONE_PROMPT
                        }
                    ]
                }
            ],
            "max_tokens": 1500,
            "temperature": 0.1,
            "enable_thinking": False
        }
    )

    result = response.json()

    if "error" in result:
        return {"error": result["error"], "zone": zone_name, "image": image_name}

    content = result["choices"][0]["message"]["content"]
    tokens = result.get("usage", {}).get("total_tokens", 0)

    import re
    json_match = re.search(r'\{[\s\S]*\}', content)
    if json_match:
        try:
            parsed = json.loads(json_match.group())
            parsed["_zone"] = zone_name
            parsed["_image"] = image_name
            parsed["_tokens"] = tokens
            return parsed
        except json.JSONDecodeError:
            pass

    return {"raw_content": content, "_zone": zone_name, "_image": image_name, "_tokens": tokens}


def main():
    sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding='utf-8', errors='replace')

    print("=" * 70)
    print(f"Foreign Object Zone Scan v2 ({MODEL})")
    print(f"Strategy: 2x2 crop with 10% overlap per image")
    print("=" * 70)

    all_results = {}
    total_detections = 0

    for i, fname in enumerate(IMAGE_FILES, 1):
        fpath = os.path.join(IMAGE_DIR, fname)
        if not os.path.exists(fpath):
            print(f"\n[Image {i}/6] SKIP - not found: {fname}")
            continue

        print(f"\n[Image {i}/6] {fname[:20]}...")
        print(f"  Cropping into 4 zones...")

        zones = crop_image_to_zones(fpath)
        image_detections = []

        for zone_name, zone_b64 in zones:
            print(f"    Zone {zone_name}...", end=" ", flush=True)
            result = analyze_zone(zone_b64, zone_name, fname)

            if result.get("has_foreign_object"):
                objects = result.get("foreign_objects", [])
                print(f"[DETECTED] {len(objects)} object(s)")
                for obj in objects:
                    print(f"      >>> {obj.get('type','?')} | {obj.get('location_in_zone','?')} | color={obj.get('color','?')} | {obj.get('severity','?')}")
                    print(f"          {obj.get('description','')}")
                image_detections.extend(objects)
            else:
                print("[clean]")

        if image_detections:
            total_detections += 1
            print(f"  === Image {i} TOTAL: {len(image_detections)} foreign object(s) detected ===")
        else:
            print(f"  === Image {i}: CLEAN ===")

        all_results[f"image_{i}"] = {
            "filename": fname,
            "detections": image_detections,
            "has_foreign_object": len(image_detections) > 0
        }

    # Summary
    print(f"\n{'=' * 70}")
    print("ZONE SCAN SUMMARY")
    print("=" * 70)
    print(f"Total images: {len(all_results)}")
    print(f"Images with detections: {total_detections}")
    print(f"Images clean: {len(all_results) - total_detections}")

    for key, val in all_results.items():
        status = "DETECTED" if val["has_foreign_object"] else "CLEAN"
        count = len(val["detections"])
        print(f"  {key}: [{status}] {count} object(s) - {val['filename'][:25]}")

    # Save
    output_path = os.path.join(os.path.dirname(__file__), "foreign_object_zone_scan_results.json")
    with open(output_path, "w", encoding="utf-8") as f:
        json.dump(all_results, f, ensure_ascii=False, indent=2)
    print(f"\nFull results: {output_path}")


if __name__ == "__main__":
    main()
