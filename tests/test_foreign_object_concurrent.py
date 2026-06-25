"""
异物检测 v3 — 并发优化版
两级筛选策略：
  Level 1: 全图快扫（并发6张，用 qwen-vl-plus 快速模型）
  Level 2: 可疑图分区精检（并发4区域，用 qwen-vl-max 精确模型）

对比 v2 串行版速度提升。
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
from pathlib import Path

API_KEY = os.environ.get("DASHSCOPE_API_KEY", "")
BASE_URL = "https://dashscope.aliyuncs.com/compatible-mode/v1"

MODEL_FAST = "qwen-vl-plus-latest"    # Level 1 快扫
MODEL_PRECISE = "qwen-vl-max-latest"  # Level 2 精检

# Concurrency limit to avoid API rate limiting
MAX_CONCURRENT = 4

IMAGE_DIR = r"d:\xwechat_files\wxid_a2m0bim6zcm212_82ca\temp\RWTemp\2026-03\0d56910e217b694af81d3943df1ff104"
IMAGE_FILES = [
    "9148fe77352de7d0a07981c9de66cc6e.jpg",   # Image 1 - white spot
    "c20892e0fea7f920ea49215e9702ca76.jpg",   # Image 2 - blue dot
    "c72cf022eee8794759f0fe51a347a5c0.jpg",   # Image 3 - hair/thread
    "05bfda432fa8a35a900277ae162c0ae6.jpg",   # Image 4 - discolored spot
    "09ae6c082ac4939159fe84327e6b4368.jpg",   # Image 5 - hair between rolls
    "bfeeb0ff1cb6e0b2b08a9ea34b41fb2c.jpg",   # Image 6 - control (clean)
]

LEVEL1_PROMPT = """你是食品质检AI。快速扫描这张牛肉卷包装图，判断是否存在异物。

异物类型：毛发/线头、塑料碎片、颜色异常点（蓝/绿/黄/黑）、白色碎屑（非脂肪）、变色/霉变。
正常的：红色肉质、白色脂肪花纹、肉卷缝隙阴影、包装膜反光。

以 JSON 返回：
{"suspicious": true/false, "confidence": 0.0-1.0, "reason": "简述可疑原因或'无异常'"}

宁可误报不要漏报。仅返回JSON。"""

LEVEL2_PROMPT = """你是食品质检AI专家，专精异物检测。这是一张牛肉卷包装的**局部放大图**。

请用极其严格的标准检查这个局部区域，寻找以下异物：
1. **毛发/线头**: 极细的深色或浅色丝状物
2. **塑料碎片**: 透明或半透明的小碎片
3. **颜色异常点**: 不属于红色肉质或白色脂肪的颜色点
4. **白色异物**: 不规则的白色碎屑，区别于平滑连续的脂肪纹理
5. **变色/霉变**: 灰绿色、暗褐色异常区域

以 JSON 返回：
{
    "has_foreign_object": true/false,
    "confidence": 0.0-1.0,
    "foreign_objects": [
        {"type": "类型", "description": "描述", "location_in_zone": "位置", "severity": "HIGH/MEDIUM/LOW"}
    ]
}

宁可多报也不要漏报。仅返回JSON。"""


def encode_image_file(path: str) -> str:
    with open(path, "rb") as f:
        return base64.b64encode(f.read()).decode("utf-8")


def crop_image_to_zones(image_path: str) -> list:
    img = Image.open(image_path)
    w, h = img.size
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
        result.append((name, base64.b64encode(buf.getvalue()).decode("utf-8")))
    return result


def parse_json_response(content: str) -> dict:
    json_match = re.search(r'\{[\s\S]*\}', content)
    if json_match:
        try:
            return json.loads(json_match.group())
        except json.JSONDecodeError:
            pass
    return {}


async def call_vl_api(client: httpx.AsyncClient, sem: asyncio.Semaphore,
                      image_b64: str, prompt: str, model: str,
                      label: str) -> dict:
    """Single VL API call with semaphore-controlled concurrency"""
    async with sem:
        t0 = time.time()
        try:
            response = await client.post(
                f"{BASE_URL}/chat/completions",
                headers={
                    "Authorization": f"Bearer {API_KEY}",
                    "Content-Type": "application/json"
                },
                json={
                    "model": model,
                    "messages": [{
                        "role": "user",
                        "content": [
                            {"type": "image_url", "image_url": {"url": f"data:image/jpeg;base64,{image_b64}"}},
                            {"type": "text", "text": prompt}
                        ]
                    }],
                    "max_tokens": 1500,
                    "temperature": 0.1,
                    "enable_thinking": False
                }
            )
            elapsed = time.time() - t0
            result = response.json()

            if "error" in result:
                return {"_error": str(result["error"]), "_label": label, "_elapsed": elapsed}

            content = result["choices"][0]["message"]["content"]
            tokens = result.get("usage", {}).get("total_tokens", 0)
            parsed = parse_json_response(content)
            parsed["_label"] = label
            parsed["_elapsed"] = elapsed
            parsed["_tokens"] = tokens
            parsed["_model"] = model
            return parsed

        except Exception as e:
            elapsed = time.time() - t0
            return {"_error": str(e), "_label": label, "_elapsed": elapsed}


async def run_level1(client: httpx.AsyncClient, sem: asyncio.Semaphore) -> dict:
    """Level 1: Full-image fast scan, all images concurrent"""
    print("\n" + "=" * 60)
    print("LEVEL 1: Full-image fast scan (concurrent)")
    print(f"Model: {MODEL_FAST}")
    print("=" * 60)

    tasks = []
    valid_indices = []
    for i, fname in enumerate(IMAGE_FILES, 1):
        fpath = os.path.join(IMAGE_DIR, fname)
        if not os.path.exists(fpath):
            print(f"  [Image {i}] SKIP - not found")
            continue
        b64 = encode_image_file(fpath)
        tasks.append(call_vl_api(client, sem, b64, LEVEL1_PROMPT, MODEL_FAST, f"img{i}"))
        valid_indices.append(i)

    t0 = time.time()
    results = await asyncio.gather(*tasks)
    l1_elapsed = time.time() - t0

    suspicious = {}
    for idx, res in zip(valid_indices, results):
        is_suspicious = res.get("suspicious", False)
        confidence = res.get("confidence", 0)
        reason = res.get("reason", "")
        elapsed = res.get("_elapsed", 0)
        status = "[SUSPICIOUS]" if is_suspicious else "[CLEAN]"
        print(f"  [Image {idx}] {status} conf={confidence} ({elapsed:.1f}s) - {reason}")
        if is_suspicious:
            suspicious[idx] = {"confidence": confidence, "reason": reason}

    print(f"\n  Level 1 total: {l1_elapsed:.1f}s | {len(suspicious)}/{len(valid_indices)} suspicious")
    return suspicious, l1_elapsed


async def run_level2(client: httpx.AsyncClient, sem: asyncio.Semaphore,
                     suspicious_indices: dict) -> dict:
    """Level 2: Zone scan only suspicious images, zones concurrent"""
    print("\n" + "=" * 60)
    print("LEVEL 2: Zone scan on suspicious images (concurrent)")
    print(f"Model: {MODEL_PRECISE}")
    print(f"Images to scan: {list(suspicious_indices.keys())}")
    print("=" * 60)

    all_tasks = []
    task_meta = []  # (image_idx, zone_name)

    for idx in suspicious_indices:
        fname = IMAGE_FILES[idx - 1]
        fpath = os.path.join(IMAGE_DIR, fname)
        zones = crop_image_to_zones(fpath)
        for zone_name, zone_b64 in zones:
            all_tasks.append(
                call_vl_api(client, sem, zone_b64, LEVEL2_PROMPT, MODEL_PRECISE, f"img{idx}/{zone_name}")
            )
            task_meta.append((idx, zone_name))

    t0 = time.time()
    results = await asyncio.gather(*all_tasks)
    l2_elapsed = time.time() - t0

    # Group results by image
    image_results = {}
    for (idx, zone_name), res in zip(task_meta, results):
        if idx not in image_results:
            image_results[idx] = {"detections": [], "zone_results": []}

        has_obj = res.get("has_foreign_object", False)
        elapsed = res.get("_elapsed", 0)
        objects = res.get("foreign_objects", [])

        status = f"[DETECTED] {len(objects)} obj" if has_obj else "[clean]"
        print(f"  [Image {idx}/{zone_name}] {status} ({elapsed:.1f}s)")

        if has_obj:
            for obj in objects:
                obj["_zone"] = zone_name
                print(f"    >>> {obj.get('type','?')} | {obj.get('location_in_zone','?')} | {obj.get('severity','?')}")
            image_results[idx]["detections"].extend(objects)

        image_results[idx]["zone_results"].append({
            "zone": zone_name,
            "has_foreign_object": has_obj,
            "objects": objects
        })

    print(f"\n  Level 2 total: {l2_elapsed:.1f}s | {len(all_tasks)} zone API calls")
    return image_results, l2_elapsed


async def run_baseline_serial(client: httpx.AsyncClient, sem: asyncio.Semaphore) -> float:
    """Baseline: v2-style serial zone scan for comparison"""
    print("\n" + "=" * 60)
    print("BASELINE: Serial zone scan (v2 style, for timing comparison)")
    print(f"Model: {MODEL_PRECISE}")
    print("=" * 60)

    t0 = time.time()
    total_calls = 0

    for i, fname in enumerate(IMAGE_FILES, 1):
        fpath = os.path.join(IMAGE_DIR, fname)
        if not os.path.exists(fpath):
            continue

        zones = crop_image_to_zones(fpath)
        for zone_name, zone_b64 in zones:
            result = await call_vl_api(client, sem, zone_b64, LEVEL2_PROMPT, MODEL_PRECISE, f"serial-img{i}/{zone_name}")
            has_obj = result.get("has_foreign_object", False)
            elapsed = result.get("_elapsed", 0)
            status = "[DETECTED]" if has_obj else "[clean]"
            print(f"  [Image {i}/{zone_name}] {status} ({elapsed:.1f}s)")
            total_calls += 1

    baseline_elapsed = time.time() - t0
    print(f"\n  Baseline total: {baseline_elapsed:.1f}s | {total_calls} serial API calls")
    return baseline_elapsed


async def main():
    sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding='utf-8', errors='replace')

    print("=" * 70)
    print("Foreign Object Detection v3 - Concurrent + Two-Level")
    print("=" * 70)
    print(f"Fast model:    {MODEL_FAST}")
    print(f"Precise model: {MODEL_PRECISE}")
    print(f"Max concurrent: {MAX_CONCURRENT}")
    print(f"Images: {len(IMAGE_FILES)}")

    sem = asyncio.Semaphore(MAX_CONCURRENT)

    async with httpx.AsyncClient(timeout=120.0) as client:
        # === Strategy A: Two-level concurrent ===
        print("\n\n>>> STRATEGY A: Two-Level Concurrent <<<")
        strategy_a_start = time.time()

        suspicious, l1_time = await run_level1(client, sem)
        if suspicious:
            l2_results, l2_time = await run_level2(client, sem, suspicious)
        else:
            l2_results, l2_time = {}, 0.0
            print("\n  No suspicious images - skipping Level 2")

        strategy_a_total = time.time() - strategy_a_start

        # === Strategy B: All-concurrent zone scan (no pre-filter) ===
        print("\n\n>>> STRATEGY B: All-Concurrent Zone Scan (no pre-filter) <<<")
        print("=" * 60)
        strategy_b_start = time.time()

        all_zone_tasks = []
        all_zone_meta = []
        for i, fname in enumerate(IMAGE_FILES, 1):
            fpath = os.path.join(IMAGE_DIR, fname)
            if not os.path.exists(fpath):
                continue
            zones = crop_image_to_zones(fpath)
            for zone_name, zone_b64 in zones:
                all_zone_tasks.append(
                    call_vl_api(client, sem, zone_b64, LEVEL2_PROMPT, MODEL_PRECISE, f"all-img{i}/{zone_name}")
                )
                all_zone_meta.append((i, zone_name))

        b_results = await asyncio.gather(*all_zone_tasks)
        strategy_b_total = time.time() - strategy_b_start

        b_detections = 0
        b_images_with_detections = set()
        for (idx, zone), res in zip(all_zone_meta, b_results):
            if res.get("has_foreign_object"):
                b_images_with_detections.add(idx)
                b_detections += len(res.get("foreign_objects", []))

        print(f"  Strategy B total: {strategy_b_total:.1f}s | {len(all_zone_tasks)} concurrent calls")
        print(f"  Images with detections: {len(b_images_with_detections)}")

        # === Summary ===
        print("\n\n" + "=" * 70)
        print("PERFORMANCE COMPARISON")
        print("=" * 70)

        a_l1_calls = len([f for f in IMAGE_FILES if os.path.exists(os.path.join(IMAGE_DIR, f))])
        a_l2_calls = len(suspicious) * 4 if suspicious else 0

        print(f"\n  Strategy A (Two-Level Concurrent):")
        print(f"    Level 1: {l1_time:.1f}s ({a_l1_calls} calls, {MODEL_FAST})")
        print(f"    Level 2: {l2_time:.1f}s ({a_l2_calls} calls, {MODEL_PRECISE})")
        print(f"    TOTAL:   {strategy_a_total:.1f}s")
        print(f"    API calls: {a_l1_calls + a_l2_calls}")

        print(f"\n  Strategy B (All-Concurrent Zone Scan):")
        print(f"    TOTAL:   {strategy_b_total:.1f}s ({len(all_zone_tasks)} calls, {MODEL_PRECISE})")
        print(f"    Detections: {b_detections} objects in {len(b_images_with_detections)} images")

        print(f"\n  v2 Baseline (serial, estimated):")
        per_call_avg = strategy_b_total / max(len(all_zone_tasks), 1) * MAX_CONCURRENT
        serial_estimate = per_call_avg * len(all_zone_tasks)
        print(f"    Estimated: {serial_estimate:.0f}s (based on avg call time x {len(all_zone_tasks)} serial)")

        if strategy_a_total > 0:
            speedup_a = serial_estimate / strategy_a_total
            print(f"\n  Speedup A vs serial: {speedup_a:.1f}x")
        if strategy_b_total > 0:
            speedup_b = serial_estimate / strategy_b_total
            print(f"  Speedup B vs serial: {speedup_b:.1f}x")

        # Cost estimate
        print(f"\n  COST ESTIMATE (per batch of 6 images):")
        print(f"    Strategy A: ~{a_l1_calls} fast + ~{a_l2_calls} precise calls")
        print(f"    Strategy B: ~{len(all_zone_tasks)} precise calls")
        print(f"    Production (100 batches/day):")
        print(f"      A: ~{(a_l1_calls + a_l2_calls) * 100} calls/day")
        print(f"      B: ~{len(all_zone_tasks) * 100} calls/day")

        # Detection results
        print(f"\n  DETECTION RESULTS:")
        a_detected = set()
        for idx in suspicious:
            if idx in l2_results and l2_results[idx]["detections"]:
                a_detected.add(idx)
        print(f"    Strategy A: {len(a_detected)} images with confirmed detections")
        print(f"    Strategy B: {len(b_images_with_detections)} images with detections")

        # Save results
        output = {
            "strategy_a": {
                "total_time": strategy_a_total,
                "l1_time": l1_time,
                "l2_time": l2_time,
                "l1_calls": a_l1_calls,
                "l2_calls": a_l2_calls,
                "suspicious_images": list(suspicious.keys()),
                "detected_images": list(a_detected),
            },
            "strategy_b": {
                "total_time": strategy_b_total,
                "total_calls": len(all_zone_tasks),
                "detected_images": list(b_images_with_detections),
            },
            "serial_estimate": serial_estimate,
        }
        output_path = os.path.join(os.path.dirname(__file__), "foreign_object_concurrent_results.json")
        with open(output_path, "w", encoding="utf-8") as f:
            json.dump(output, f, ensure_ascii=False, indent=2)
        print(f"\n  Results saved: {output_path}")


if __name__ == "__main__":
    asyncio.run(main())
