"""
异物检测 v6 — 稳定性测试
用 qwen-vl-plus 3x3 并发跑 3 轮，看检出率波动范围
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
# 5 张有异物 + 1 张干净
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
                    "max_tokens": 400,
                    "temperature": 0.1,
                    "enable_thinking": False
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
            return parsed
        except Exception as e:
            return {"_err": str(e), "_t": time.time()-t0}


async def run_one_round(client, sem, round_num):
    tasks = []
    meta = []
    for i, (fname, _, _) in enumerate(IMAGE_FILES, 1):
        fpath = os.path.join(IMAGE_DIR, fname)
        if not os.path.exists(fpath):
            continue
        img = Image.open(fpath)
        for zname, zb64 in crop_3x3(img):
            tasks.append(call_api(client, sem, zb64, f"r{round_num}-img{i}/{zname}"))
            meta.append(i)

    t0 = time.time()
    results = await asyncio.gather(*tasks)
    elapsed = time.time() - t0

    img_det = {}
    total_tokens = 0
    for idx, res in zip(meta, results):
        if "_err" in res:
            continue
        total_tokens += res.get("_tokens", 0)
        if idx not in img_det:
            img_det[idx] = []
        if res.get("detected", False):
            img_det[idx].extend(res.get("objects", []))

    # Per-image results
    per_image = {}
    tp = 0  # true positive (has object, detected)
    tn = 0  # true negative (clean, not detected)
    fn = 0  # false negative (has object, missed)
    fp = 0  # false positive (clean, detected)

    for i, (fname, expected, desc) in enumerate(IMAGE_FILES, 1):
        if not os.path.exists(os.path.join(IMAGE_DIR, fname)):
            continue
        found = len(img_det.get(i, [])) > 0
        n_obj = len(img_det.get(i, []))

        if expected and found:
            tp += 1
            tag = "TP"
        elif expected and not found:
            fn += 1
            tag = "FN"
        elif not expected and not found:
            tn += 1
            tag = "TN"
        else:
            fp += 1
            tag = "FP"

        per_image[i] = {"tag": tag, "objects": n_obj, "desc": desc}

    recall = tp / (tp + fn) * 100 if (tp + fn) > 0 else 0
    precision = tp / (tp + fp) * 100 if (tp + fp) > 0 else 0
    accuracy = (tp + tn) / (tp + tn + fp + fn) * 100

    return {
        "round": round_num,
        "time": elapsed,
        "tokens": total_tokens,
        "tp": tp, "tn": tn, "fp": fp, "fn": fn,
        "recall": recall,
        "precision": precision,
        "accuracy": accuracy,
        "per_image": per_image,
    }


async def main():
    sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding='utf-8', errors='replace')

    print("=" * 70)
    print("Foreign Object Detection v6 — Stability Test")
    print(f"Model: {MODEL} | 3x3 grid | concurrent={MAX_CONCURRENT}")
    print(f"3 rounds to measure variance")
    print("=" * 70)

    sem = asyncio.Semaphore(MAX_CONCURRENT)
    rounds = []

    async with httpx.AsyncClient(timeout=120.0) as client:
        for r in range(1, 4):
            print(f"\n--- Round {r} ---")
            result = await run_one_round(client, sem, r)
            rounds.append(result)

            for i in sorted(result["per_image"]):
                p = result["per_image"][i]
                print(f"  [{p['tag']}] Image {i}: {p['objects']} obj — {p['desc']}")

            print(f"  Time: {result['time']:.1f}s | Recall: {result['recall']:.0f}% | Precision: {result['precision']:.0f}% | Acc: {result['accuracy']:.0f}%")
            print(f"  TP={result['tp']} TN={result['tn']} FP={result['fp']} FN={result['fn']}")

            if r < 3:
                await asyncio.sleep(3)

    # Summary
    print(f"\n\n{'='*70}")
    print("STABILITY SUMMARY (3 rounds)")
    print("=" * 70)
    print(f"{'Round':<8} {'Time':>6} {'Recall':>8} {'Precision':>10} {'Accuracy':>10} {'FN (missed)':>12}")
    print("-" * 58)
    for r in rounds:
        print(f"  R{r['round']:<5} {r['time']:>5.1f}s {r['recall']:>7.0f}% {r['precision']:>9.0f}% {r['accuracy']:>9.0f}% {r['fn']:>8}")

    avg_recall = sum(r['recall'] for r in rounds) / len(rounds)
    avg_precision = sum(r['precision'] for r in rounds) / len(rounds)
    avg_acc = sum(r['accuracy'] for r in rounds) / len(rounds)
    avg_time = sum(r['time'] for r in rounds) / len(rounds)
    avg_cost = sum(r['tokens'] for r in rounds) / len(rounds) * 0.008 / 1000

    print(f"\n  Average: {avg_time:.1f}s | Recall {avg_recall:.0f}% | Precision {avg_precision:.0f}% | Acc {avg_acc:.0f}%")
    print(f"  Avg cost/batch: {avg_cost:.3f} CNY | Day(100): {avg_cost*100:.1f} CNY")

    # Which images are consistently missed?
    print(f"\n  Per-image consistency:")
    for i in range(1, 7):
        tags = [r["per_image"].get(i, {}).get("tag", "?") for r in rounds]
        desc = IMAGE_FILES[i-1][2]
        print(f"    Image {i} ({desc}): {' '.join(tags)}")

    out = os.path.join(os.path.dirname(__file__), "foreign_object_v6_results.json")
    with open(out, "w", encoding="utf-8") as f:
        json.dump(rounds, f, ensure_ascii=False, indent=2)
    print(f"\n  Results: {out}")


if __name__ == "__main__":
    asyncio.run(main())
