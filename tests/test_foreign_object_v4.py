"""
异物检测 v4 — 优化并发版
目标：在 Strategy B（全并发zone扫描）基础上同时提升正确率和速度

优化项：
1. MAX_CONCURRENT 4→8
2. 3x3 九宫格（9 zones）+ 20%重叠 → 更高放大倍率，提升微小异物检出
3. JPEG quality 95→80 → zone 图体积减小，传输更快
4. max_tokens 1500→500 → JSON 响应很短
5. Prompt 更精炼，减少模型犹豫
6. 对比 2x2 vs 3x3 检出率
"""

import asyncio
import base64
import json
import io
import os
import sys
import time
import re
import httpx
from PIL import Image

API_KEY = os.environ.get("DASHSCOPE_API_KEY", "")
BASE_URL = "https://dashscope.aliyuncs.com/compatible-mode/v1"
MODEL = "qwen-vl-max-latest"

MAX_CONCURRENT = 8
JPEG_QUALITY = 80

IMAGE_DIR = r"d:\xwechat_files\wxid_a2m0bim6zcm212_82ca\temp\RWTemp\2026-03\0d56910e217b694af81d3943df1ff104"
IMAGE_FILES = [
    ("9148fe77352de7d0a07981c9de66cc6e.jpg", True,  "white spot mid-lower-right"),
    ("c20892e0fea7f920ea49215e9702ca76.jpg", True,  "blue dot"),
    ("c72cf022eee8794759f0fe51a347a5c0.jpg", True,  "hair/thread right side"),
    ("05bfda432fa8a35a900277ae162c0ae6.jpg", True,  "discolored spot upper-right"),
    ("09ae6c082ac4939159fe84327e6b4368.jpg", True,  "hair between rolls"),
    ("bfeeb0ff1cb6e0b2b08a9ea34b41fb2c.jpg", False, "control - clean"),
]

ZONE_PROMPT = """食品质检：检查这张牛肉卷局部放大图是否有异物。

异物类型：
- 毛发/线头（细丝状，任何颜色）
- 塑料碎片（透明/半透明）
- 颜色异常点（蓝/绿/黄/黑点，非肉色非脂肪色）
- 白色碎屑（不规则孤立白点，非脂肪花纹）
- 变色/霉变（灰绿/暗褐）

正常的不报告：红色肉质、白色脂肪大理石纹、缝隙阴影、包装膜反光。

JSON返回：
{"detected":true/false,"objects":[{"type":"类型","desc":"描述","loc":"位置","sev":"HIGH/MED/LOW"}]}

宁可误报不漏报。仅返回JSON。"""


def crop_2x2(img: Image.Image) -> list:
    w, h = img.size
    ox, oy = int(w * 0.1), int(h * 0.1)
    mx, my = w // 2, h // 2
    return [
        ("2x2-TL", img.crop((0, 0, mx + ox, my + oy))),
        ("2x2-TR", img.crop((mx - ox, 0, w, my + oy))),
        ("2x2-BL", img.crop((0, my - oy, mx + ox, h))),
        ("2x2-BR", img.crop((mx - ox, my - oy, w, h))),
    ]


def crop_3x3(img: Image.Image) -> list:
    """3x3 grid with 20% overlap — 9 zones, each ~45% of dimension"""
    w, h = img.size
    # Each zone covers 1/3 + 20% overlap on each side
    sw = w / 3  # step width
    sh = h / 3  # step height
    ow = int(sw * 0.2)  # overlap
    oh = int(sh * 0.2)

    zones = []
    labels = ["TL", "TC", "TR", "ML", "MC", "MR", "BL", "BC", "BR"]
    for row in range(3):
        for col in range(3):
            x1 = max(0, int(col * sw) - ow)
            y1 = max(0, int(row * sh) - oh)
            x2 = min(w, int((col + 1) * sw) + ow)
            y2 = min(h, int((row + 1) * sh) + oh)
            zones.append((f"3x3-{labels[row * 3 + col]}", img.crop((x1, y1, x2, y2))))
    return zones


def zones_to_b64(zones: list) -> list:
    result = []
    for name, zone_img in zones:
        buf = io.BytesIO()
        zone_img.save(buf, format="JPEG", quality=JPEG_QUALITY)
        result.append((name, base64.b64encode(buf.getvalue()).decode("utf-8"), buf.tell()))
    return result


def parse_json(content: str) -> dict:
    m = re.search(r'\{[\s\S]*\}', content)
    if m:
        try:
            return json.loads(m.group())
        except json.JSONDecodeError:
            pass
    return {}


async def call_api(client: httpx.AsyncClient, sem: asyncio.Semaphore,
                   b64: str, label: str) -> dict:
    async with sem:
        t0 = time.time()
        try:
            resp = await client.post(
                f"{BASE_URL}/chat/completions",
                headers={"Authorization": f"Bearer {API_KEY}", "Content-Type": "application/json"},
                json={
                    "model": MODEL,
                    "messages": [{"role": "user", "content": [
                        {"type": "image_url", "image_url": {"url": f"data:image/jpeg;base64,{b64}"}},
                        {"type": "text", "text": ZONE_PROMPT}
                    ]}],
                    "max_tokens": 500,
                    "temperature": 0.1,
                    "enable_thinking": False
                }
            )
            elapsed = time.time() - t0
            data = resp.json()
            if "error" in data:
                return {"_err": str(data["error"]), "_label": label, "_t": elapsed}
            content = data["choices"][0]["message"]["content"]
            tokens = data.get("usage", {}).get("total_tokens", 0)
            parsed = parse_json(content)
            parsed["_label"] = label
            parsed["_t"] = elapsed
            parsed["_tokens"] = tokens
            return parsed
        except Exception as e:
            return {"_err": str(e), "_label": label, "_t": time.time() - t0}


async def run_scan(client, sem, grid_fn, grid_name, images):
    """Run a full scan with given grid strategy"""
    all_tasks = []
    all_meta = []

    for i, (fname, has_obj, desc) in enumerate(images, 1):
        fpath = os.path.join(IMAGE_DIR, fname)
        if not os.path.exists(fpath):
            continue
        img = Image.open(fpath)
        zones = grid_fn(img)
        b64_zones = zones_to_b64(zones)
        for zname, zb64, zsize in b64_zones:
            all_tasks.append(call_api(client, sem, zb64, f"img{i}/{zname}"))
            all_meta.append((i, zname, zsize))

    t0 = time.time()
    results = await asyncio.gather(*all_tasks)
    total_time = time.time() - t0

    # Aggregate by image
    img_detections = {}
    total_tokens = 0
    total_bytes = 0
    for (idx, zname, zsize), res in zip(all_meta, results):
        total_tokens += res.get("_tokens", 0)
        total_bytes += zsize
        if idx not in img_detections:
            img_detections[idx] = []
        detected = res.get("detected", res.get("has_foreign_object", False))
        objects = res.get("objects", res.get("foreign_objects", []))
        if detected and objects:
            for obj in objects:
                obj["_zone"] = zname
            img_detections[idx].extend(objects)

    # Print results
    correct = 0
    for i, (fname, expected, desc) in enumerate(images, 1):
        if i not in img_detections:
            continue
        found = len(img_detections[i]) > 0
        match = found == expected
        if match:
            correct += 1
        status = "OK" if match else "MISS" if expected else "FALSE+"
        count = len(img_detections[i])
        print(f"  [{status}] Image {i}: {count} objects (expected={'YES' if expected else 'NO'}) - {desc}")
        if found:
            for obj in img_detections[i][:3]:
                print(f"       {obj.get('type','?')} | {obj.get('loc', obj.get('location_in_zone','?'))} | {obj.get('sev', obj.get('severity','?'))}")

    accuracy = correct / len([f for f in images if os.path.exists(os.path.join(IMAGE_DIR, f[0]))]) * 100
    print(f"\n  {grid_name}: {total_time:.1f}s | {len(all_tasks)} calls | {accuracy:.0f}% accuracy | {total_tokens} tokens | {total_bytes/1024:.0f}KB payload")
    return {
        "time": total_time,
        "calls": len(all_tasks),
        "accuracy": accuracy,
        "correct": correct,
        "total": len(images),
        "tokens": total_tokens,
        "payload_kb": total_bytes / 1024,
        "detections": {k: len(v) for k, v in img_detections.items()},
    }


async def main():
    sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding='utf-8', errors='replace')

    print("=" * 70)
    print("Foreign Object Detection v4 - Optimized Concurrent")
    print("=" * 70)
    print(f"Model: {MODEL} | Concurrent: {MAX_CONCURRENT} | JPEG: {JPEG_QUALITY}%")
    print(f"Images: {len(IMAGE_FILES)} (5 with objects, 1 clean control)")

    sem = asyncio.Semaphore(MAX_CONCURRENT)

    async with httpx.AsyncClient(timeout=120.0) as client:
        # Test 1: 2x2 grid (baseline from v3)
        print(f"\n{'='*60}")
        print("TEST 1: 2x2 Grid (4 zones/image) — optimized params")
        print("=" * 60)
        r_2x2 = await run_scan(client, sem, crop_2x2, "2x2", IMAGE_FILES)

        # Small pause to avoid rate limit
        await asyncio.sleep(2)

        # Test 2: 3x3 grid (higher magnification)
        print(f"\n{'='*60}")
        print("TEST 2: 3x3 Grid (9 zones/image) — higher magnification")
        print("=" * 60)
        r_3x3 = await run_scan(client, sem, crop_3x3, "3x3", IMAGE_FILES)

        # Comparison
        print(f"\n\n{'='*70}")
        print("COMPARISON")
        print("=" * 70)
        print(f"{'Metric':<25} {'2x2 (4 zones)':<20} {'3x3 (9 zones)':<20}")
        print(f"{'-'*65}")
        print(f"{'Total time':<25} {r_2x2['time']:.1f}s{'':<14} {r_3x3['time']:.1f}s")
        print(f"{'API calls':<25} {r_2x2['calls']:<20} {r_3x3['calls']}")
        print(f"{'Accuracy':<25} {r_2x2['accuracy']:.0f}%{'':<17} {r_3x3['accuracy']:.0f}%")
        print(f"{'Correct/Total':<25} {r_2x2['correct']}/{r_2x2['total']}{'':<16} {r_3x3['correct']}/{r_3x3['total']}")
        print(f"{'Total tokens':<25} {r_2x2['tokens']:<20} {r_3x3['tokens']}")
        print(f"{'Payload size':<25} {r_2x2['payload_kb']:.0f}KB{'':<15} {r_3x3['payload_kb']:.0f}KB")

        v2_serial = r_2x2['time'] * (r_2x2['calls'] / MAX_CONCURRENT)
        print(f"\n  v2 serial estimate: ~{v2_serial:.0f}s")
        print(f"  v4 2x2 speedup: {v2_serial/r_2x2['time']:.1f}x")
        print(f"  v4 3x3 speedup: {v2_serial/r_3x3['time']:.1f}x (vs v2 serial)")

        # Production estimate
        print(f"\n  PRODUCTION ESTIMATE (6 images/batch):")
        print(f"    2x2: ~{r_2x2['time']:.0f}s/batch, ~{r_2x2['tokens']*0.00002:.3f} CNY/batch")
        print(f"    3x3: ~{r_3x3['time']:.0f}s/batch, ~{r_3x3['tokens']*0.00002:.3f} CNY/batch")
        print(f"    100 batches/day:")
        print(f"      2x2: {r_2x2['time']*100/60:.0f}min, {r_2x2['tokens']*100*0.00002:.1f} CNY")
        print(f"      3x3: {r_3x3['time']*100/60:.0f}min, {r_3x3['tokens']*100*0.00002:.1f} CNY")

        # Save
        output = {"2x2": r_2x2, "3x3": r_3x3, "config": {
            "model": MODEL, "concurrent": MAX_CONCURRENT,
            "jpeg_quality": JPEG_QUALITY, "max_tokens": 500
        }}
        out_path = os.path.join(os.path.dirname(__file__), "foreign_object_v4_results.json")
        with open(out_path, "w", encoding="utf-8") as f:
            json.dump(output, f, ensure_ascii=False, indent=2)
        print(f"\n  Results: {out_path}")


if __name__ == "__main__":
    asyncio.run(main())
