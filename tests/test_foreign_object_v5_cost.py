"""
异物检测 v5 — 成本优化版
对比 3 个模型在 3x3 zone 扫描下的 检出率/速度/成本：
  A: qwen-vl-max-latest  (贵，精确)
  B: qwen-vl-plus-latest (便宜5x，够用？)
  C: qwen2.5-vl-72b-instruct (中等价位)
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

MAX_CONCURRENT = 8
JPEG_QUALITY = 80

# DashScope VL model pricing (CNY per 1000 tokens)
MODELS = [
    ("qwen-vl-max-latest",        0.020, "MAX (精确)"),
    ("qwen-vl-plus-latest",       0.008, "PLUS (快速)"),
    ("qwen2.5-vl-72b-instruct",   0.020, "72B (开源大)"),
]

IMAGE_DIR = r"d:\xwechat_files\wxid_a2m0bim6zcm212_82ca\temp\RWTemp\2026-03\0d56910e217b694af81d3943df1ff104"
IMAGE_FILES = [
    ("9148fe77352de7d0a07981c9de66cc6e.jpg", True,  "white spot"),
    ("c20892e0fea7f920ea49215e9702ca76.jpg", True,  "blue dot"),
    ("c72cf022eee8794759f0fe51a347a5c0.jpg", True,  "hair/thread"),
    ("05bfda432fa8a35a900277ae162c0ae6.jpg", True,  "discolored spot"),
    ("09ae6c082ac4939159fe84327e6b4368.jpg", True,  "hair between rolls"),
    ("bfeeb0ff1cb6e0b2b08a9ea34b41fb2c.jpg", False, "control clean"),
]

PROMPT = """食品质检：检查这张牛肉卷局部放大图是否有异物。

异物：毛发/线头、塑料碎片、颜色异常点（蓝/绿/黄/黑）、白色碎屑（非脂肪）、变色。
正常不报：红色肉、白色脂肪纹、缝隙阴影、包装膜反光。

JSON返回：{"detected":true/false,"objects":[{"type":"类型","desc":"描述","loc":"位置"}]}
宁可误报不漏报。仅返回JSON。"""


def crop_3x3(img):
    w, h = img.size
    sw, sh = w / 3, h / 3
    ow, oh = int(sw * 0.2), int(sh * 0.2)
    zones = []
    labels = ["TL","TC","TR","ML","MC","MR","BL","BC","BR"]
    for r in range(3):
        for c in range(3):
            x1 = max(0, int(c * sw) - ow)
            y1 = max(0, int(r * sh) - oh)
            x2 = min(w, int((c+1) * sw) + ow)
            y2 = min(h, int((r+1) * sh) + oh)
            buf = io.BytesIO()
            img.crop((x1,y1,x2,y2)).save(buf, format="JPEG", quality=JPEG_QUALITY)
            zones.append((labels[r*3+c], base64.b64encode(buf.getvalue()).decode("utf-8")))
    return zones


async def call_api(client, sem, b64, model, label):
    async with sem:
        t0 = time.time()
        try:
            resp = await client.post(
                f"{BASE_URL}/chat/completions",
                headers={"Authorization": f"Bearer {API_KEY}", "Content-Type": "application/json"},
                json={
                    "model": model,
                    "messages": [{"role":"user","content":[
                        {"type":"image_url","image_url":{"url":f"data:image/jpeg;base64,{b64}"}},
                        {"type":"text","text":PROMPT}
                    ]}],
                    "max_tokens": 400,
                    "temperature": 0.1,
                    "enable_thinking": False
                }
            )
            elapsed = time.time() - t0
            data = resp.json()
            if "error" in data:
                return {"_err": str(data["error"]), "_label": label, "_t": elapsed}
            content = data["choices"][0]["message"]["content"]
            tokens = data.get("usage",{}).get("total_tokens",0)
            m = re.search(r'\{[\s\S]*\}', content)
            parsed = json.loads(m.group()) if m else {}
            parsed.update({"_label":label, "_t":elapsed, "_tokens":tokens})
            return parsed
        except Exception as e:
            return {"_err":str(e), "_label":label, "_t":time.time()-t0}


async def test_model(client, sem, model_id, price_per_1k, model_label):
    print(f"\n{'='*60}")
    print(f"  {model_label} ({model_id})")
    print(f"  Price: {price_per_1k} CNY/1k tokens")
    print(f"{'='*60}")

    # Prepare all zones (include crop time in total)
    t_total_start = time.time()

    tasks = []
    meta = []
    t_crop_start = time.time()
    for i, (fname, _, _) in enumerate(IMAGE_FILES, 1):
        fpath = os.path.join(IMAGE_DIR, fname)
        if not os.path.exists(fpath):
            continue
        img = Image.open(fpath)
        for zname, zb64 in crop_3x3(img):
            tasks.append(call_api(client, sem, zb64, model_id, f"img{i}/{zname}"))
            meta.append(i)
    crop_time = time.time() - t_crop_start

    t0 = time.time()
    results = await asyncio.gather(*tasks)
    api_time = time.time() - t0
    total_time = time.time() - t_total_start

    # Aggregate
    img_det = {}
    total_tokens = 0
    errors = 0
    for idx, res in zip(meta, results):
        if "_err" in res:
            errors += 1
            continue
        total_tokens += res.get("_tokens", 0)
        if idx not in img_det:
            img_det[idx] = []
        if res.get("detected", False):
            for obj in res.get("objects", []):
                obj["_zone"] = res["_label"].split("/")[1]
            img_det[idx].extend(res.get("objects", []))

    # Score
    correct = 0
    for i, (fname, expected, desc) in enumerate(IMAGE_FILES, 1):
        if not os.path.exists(os.path.join(IMAGE_DIR, fname)):
            continue
        found = len(img_det.get(i, [])) > 0
        match = found == expected
        if match:
            correct += 1
        tag = "OK" if match else ("MISS" if expected else "FALSE+")
        n = len(img_det.get(i, []))
        print(f"  [{tag}] Image {i}: {n} obj — {desc}")

    total_imgs = len([f for f in IMAGE_FILES if os.path.exists(os.path.join(IMAGE_DIR, f[0]))])
    acc = correct / total_imgs * 100
    cost = total_tokens * price_per_1k / 1000

    print(f"\n  Crop: {crop_time:.2f}s | API: {api_time:.1f}s | Total: {total_time:.1f}s")
    print(f"  Acc: {acc:.0f}% ({correct}/{total_imgs}) | Tokens: {total_tokens} | Cost: {cost:.3f} CNY")
    if errors:
        print(f"  Errors: {errors}")

    return {
        "model": model_id,
        "label": model_label,
        "crop_time": crop_time,
        "api_time": api_time,
        "time": total_time,
        "accuracy": acc,
        "correct": correct,
        "tokens": total_tokens,
        "cost_per_batch": cost,
        "cost_per_day_100": cost * 100,
        "errors": errors,
    }


async def main():
    sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding='utf-8', errors='replace')

    print("="*70)
    print("Foreign Object Detection v5 — Cost Optimization")
    print(f"3x3 grid | concurrent={MAX_CONCURRENT} | JPEG {JPEG_QUALITY}%")
    print("="*70)

    sem = asyncio.Semaphore(MAX_CONCURRENT)
    all_results = []

    async with httpx.AsyncClient(timeout=120.0) as client:
        for model_id, price, label in MODELS:
            r = await test_model(client, sem, model_id, price, label)
            all_results.append(r)
            await asyncio.sleep(2)  # rate limit buffer

    # Final comparison
    print(f"\n\n{'='*70}")
    print("COST vs ACCURACY COMPARISON (3x3, 6 images)")
    print("="*70)
    print(f"{'Model':<15} {'Crop':>5} {'API':>5} {'Total':>6} {'Acc':>5} {'Cost/batch':>11} {'Cost/day':>10}")
    print("-"*65)
    for r in all_results:
        print(f"{r['label']:<15} {r['crop_time']:>4.1f}s {r['api_time']:>4.1f}s {r['time']:>5.1f}s {r['accuracy']:>4.0f}% {r['cost_per_batch']:>9.3f}Y {r['cost_per_day_100']:>8.1f}Y")

    # Recommendation
    best = max(all_results, key=lambda x: x['accuracy'] / max(x['cost_per_batch'], 0.001))
    print(f"\n  Best value: {best['label']} — {best['accuracy']:.0f}% accuracy at {best['cost_per_batch']:.3f} CNY/batch")

    out = os.path.join(os.path.dirname(__file__), "foreign_object_v5_results.json")
    with open(out, "w", encoding="utf-8") as f:
        json.dump(all_results, f, ensure_ascii=False, indent=2)
    print(f"  Results: {out}")


if __name__ == "__main__":
    asyncio.run(main())
