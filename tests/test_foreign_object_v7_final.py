"""
异物检测 v7 — 只测 5 张有异物的图
PLUS模型 + 3x3并发 + 3轮稳定性
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
MODEL = "qwen-vl-plus-latest"
MAX_CONCURRENT = 8
JPEG_QUALITY = 80

IMAGE_DIR = r"d:\xwechat_files\wxid_a2m0bim6zcm212_82ca\temp\RWTemp\2026-03\0d56910e217b694af81d3943df1ff104"

# 5 张全部有异物
IMAGES = [
    ("9148fe77352de7d0a07981c9de66cc6e.jpg", "img1: white spot lower-right"),
    ("c20892e0fea7f920ea49215e9702ca76.jpg", "img2: blue dot"),
    ("c72cf022eee8794759f0fe51a347a5c0.jpg", "img3: hair/thread right"),
    ("05bfda432fa8a35a900277ae162c0ae6.jpg", "img4: discolored spot"),
    ("09ae6c082ac4939159fe84327e6b4368.jpg", "img5: hair between rolls"),
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
            x1, y1 = max(0, int(c*sw)-ow), max(0, int(r*sh)-oh)
            x2, y2 = min(w, int((c+1)*sw)+ow), min(h, int((r+1)*sh)+oh)
            buf = io.BytesIO()
            img.crop((x1,y1,x2,y2)).save(buf, format="JPEG", quality=JPEG_QUALITY)
            zones.append((labels[r*3+c], base64.b64encode(buf.getvalue()).decode("utf-8")))
    return zones


async def call_api(client, sem, b64, label):
    async with sem:
        t0 = time.time()
        try:
            resp = await client.post(
                f"{BASE_URL}/chat/completions",
                headers={"Authorization": f"Bearer {API_KEY}", "Content-Type": "application/json"},
                json={
                    "model": MODEL,
                    "messages": [{"role":"user","content":[
                        {"type":"image_url","image_url":{"url":f"data:image/jpeg;base64,{b64}"}},
                        {"type":"text","text":PROMPT}
                    ]}],
                    "max_tokens": 400, "temperature": 0.1, "enable_thinking": False
                }
            )
            data = resp.json()
            if "error" in data:
                return {"_err": str(data["error"]), "_t": time.time()-t0}
            content = data["choices"][0]["message"]["content"]
            tokens = data.get("usage",{}).get("total_tokens",0)
            m = re.search(r'\{[\s\S]*\}', content)
            parsed = json.loads(m.group()) if m else {}
            parsed["_tokens"] = tokens
            parsed["_t"] = time.time()-t0
            return parsed
        except Exception as e:
            return {"_err": str(e), "_t": time.time()-t0}


async def run_round(client, sem, round_num):
    tasks, meta = [], []
    for i, (fname, _) in enumerate(IMAGES, 1):
        fpath = os.path.join(IMAGE_DIR, fname)
        if not os.path.exists(fpath):
            print(f"  [SKIP] {fname} not found")
            continue
        img = Image.open(fpath)
        for zname, zb64 in crop_3x3(img):
            tasks.append(call_api(client, sem, zb64, f"r{round_num}-img{i}/{zname}"))
            meta.append(i)

    t0 = time.time()
    results = await asyncio.gather(*tasks)
    elapsed = time.time() - t0

    img_det = {}
    tokens = 0
    for idx, res in zip(meta, results):
        if "_err" in res:
            continue
        tokens += res.get("_tokens", 0)
        if idx not in img_det:
            img_det[idx] = []
        if res.get("detected", False):
            img_det[idx].extend(res.get("objects", []))

    detected = 0
    for i, (fname, desc) in enumerate(IMAGES, 1):
        n = len(img_det.get(i, []))
        found = n > 0
        if found:
            detected += 1
        tag = "OK" if found else "MISS"
        print(f"  [{tag}] {desc}: {n} objects", end="")
        if found and n <= 3:
            types = [o.get("type","?") for o in img_det[i][:3]]
            print(f" ({', '.join(types)})", end="")
        print()

    rate = detected / len(IMAGES) * 100
    cost = tokens * 0.008 / 1000
    print(f"  >> {detected}/5 detected ({rate:.0f}%) | {elapsed:.1f}s | {tokens} tokens | {cost:.3f} CNY")
    return {"detected": detected, "time": elapsed, "tokens": tokens, "cost": cost, "rate": rate}


async def main():
    sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding='utf-8', errors='replace')

    print("=" * 60)
    print("v7: PLUS + 3x3 + concurrent 8 | 5 images (all have objects)")
    print("=" * 60)

    sem = asyncio.Semaphore(MAX_CONCURRENT)
    rounds = []

    async with httpx.AsyncClient(timeout=120.0) as client:
        for r in range(1, 4):
            print(f"\n--- Round {r} ---")
            result = await run_round(client, sem, r)
            rounds.append(result)
            if r < 3:
                await asyncio.sleep(3)

    print(f"\n{'='*60}")
    print("STABILITY (3 rounds, 5 images each)")
    print("=" * 60)
    for i, r in enumerate(rounds, 1):
        print(f"  R{i}: {r['detected']}/5 ({r['rate']:.0f}%) | {r['time']:.1f}s | {r['cost']:.3f} CNY")

    avg_rate = sum(r['rate'] for r in rounds) / 3
    avg_time = sum(r['time'] for r in rounds) / 3
    avg_cost = sum(r['cost'] for r in rounds) / 3
    min_det = min(r['detected'] for r in rounds)
    max_det = max(r['detected'] for r in rounds)

    print(f"\n  Avg: {avg_rate:.0f}% | {avg_time:.1f}s | {avg_cost:.3f} CNY/batch")
    print(f"  Range: {min_det}-{max_det}/5 detected")
    print(f"  Day(100 batches): {avg_cost*100:.1f} CNY")


if __name__ == "__main__":
    asyncio.run(main())
