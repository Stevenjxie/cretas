"""
异物检测测试脚本
使用 DashScope Qwen VL 模型分析食品图片中的异物
"""

import base64
import json
import os
import sys
import httpx
from pathlib import Path

API_KEY = os.environ.get("DASHSCOPE_API_KEY", "")
BASE_URL = "https://dashscope.aliyuncs.com/compatible-mode/v1"
# Use a proper VL (vision-language) model
MODEL = "qwen-vl-max-latest"

IMAGE_DIR = r"d:\xwechat_files\wxid_a2m0bim6zcm212_82ca\temp\RWTemp\2026-03\0d56910e217b694af81d3943df1ff104"
IMAGE_FILES = [
    "9148fe77352de7d0a07981c9de66cc6e.jpg",
    "c20892e0fea7f920ea49215e9702ca76.jpg",
    "c72cf022eee8794759f0fe51a347a5c0.jpg",
    "05bfda432fa8a35a900277ae162c0ae6.jpg",
    "09ae6c082ac4939159fe84327e6b4368.jpg",
    "bfeeb0ff1cb6e0b2b08a9ea34b41fb2c.jpg",
]

FOREIGN_OBJECT_PROMPT = """你是一个食品质检AI专家，专门检测食品成品中的异物。

请仔细分析这张食品图片，检测是否存在以下异物或缺陷：
1. **非食品异物**: 毛发、塑料碎片、金属碎片、玻璃碎片、纸屑、线头、橡皮筋、标签碎片等
2. **颜色异常点**: 不属于正常食品颜色的点或区域（如蓝色、绿色、黑色斑点）
3. **虫害痕迹**: 虫体、虫卵、虫咬痕迹
4. **霉变/变质**: 霉斑、异常变色、腐败迹象
5. **包装问题**: 包装破损导致的污染、包装材料混入食品

请以 JSON 格式返回检测结果：
{
    "has_foreign_object": true/false,
    "confidence": 0.0-1.0,
    "foreign_objects": [
        {
            "type": "异物类型",
            "description": "详细描述",
            "location": "在图片中的位置（如：左上/中间偏下/右下角等，尽量精确描述在食品的哪个位置）",
            "severity": "HIGH/MEDIUM/LOW",
            "color": "异物的颜色"
        }
    ],
    "food_condition": "食品整体状态评估",
    "packaging_condition": "包装状态评估",
    "overall_verdict": "PASS/REVIEW/REJECT",
    "notes": "其他观察"
}

分析要点：
- 这是一盒澳洲和牛翼板卷（牛肉卷），正常颜色是红色/粉色肉质+白色脂肪纹理
- 透明塑料包装覆盖在上方
- 仔细检查每一块肉卷表面和肉卷之间的缝隙
- 注意区分正常的脂肪纹理和异物
- 如果没有发现异物，请如实报告

仅返回 JSON，不要包含其他文字。"""


def encode_image(image_path: str) -> str:
    """Read and base64 encode an image file"""
    with open(image_path, "rb") as f:
        return base64.b64encode(f.read()).decode("utf-8")


def analyze_image(image_base64: str, image_name: str) -> dict:
    """Call DashScope VL API to analyze a single image"""
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
                                "url": f"data:image/jpeg;base64,{image_base64}"
                            }
                        },
                        {
                            "type": "text",
                            "text": FOREIGN_OBJECT_PROMPT
                        }
                    ]
                }
            ],
            "max_tokens": 2000,
            "temperature": 0.2,
            "enable_thinking": False
        }
    )

    result = response.json()

    if "error" in result:
        return {"error": result["error"], "image": image_name}

    content = result["choices"][0]["message"]["content"]
    tokens = result.get("usage", {}).get("total_tokens", 0)

    # Try to parse JSON from response
    import re
    json_match = re.search(r'\{[\s\S]*\}', content)
    if json_match:
        try:
            parsed = json.loads(json_match.group())
            parsed["_image"] = image_name
            parsed["_tokens"] = tokens
            parsed["_model"] = MODEL
            return parsed
        except json.JSONDecodeError:
            pass

    return {"raw_content": content, "_image": image_name, "_tokens": tokens}


def main():
    import io
    sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding='utf-8', errors='replace')

    print("=" * 70)
    print(f"[Foreign Object Detection] DashScope VL ({MODEL})")
    print("=" * 70)

    results = []

    for i, fname in enumerate(IMAGE_FILES, 1):
        fpath = os.path.join(IMAGE_DIR, fname)
        if not os.path.exists(fpath):
            print(f"\n[{i}/6] SKIP - file not found: {fname}")
            continue

        print(f"\n[{i}/6] Analyzing: {fname[:20]}...")

        image_b64 = encode_image(fpath)
        result = analyze_image(image_b64, fname)
        results.append(result)

        # Print result summary
        if "error" in result:
            print(f"  ERROR: {result['error']}")
        elif "has_foreign_object" in result:
            has_obj = result["has_foreign_object"]
            confidence = result.get("confidence", 0)
            verdict = result.get("overall_verdict", "?")
            food_cond = result.get("food_condition", "")

            status = "[DETECTED]" if has_obj else "[CLEAN]"
            print(f"  {status}")
            print(f"  Confidence: {confidence}")
            print(f"  Verdict: {verdict}")
            print(f"  Food condition: {food_cond}")

            if has_obj and result.get("foreign_objects"):
                for obj in result["foreign_objects"]:
                    print(f"  >>> Type: {obj.get('type', '?')} | Location: {obj.get('location', '?')} | Severity: {obj.get('severity', '?')}")
                    print(f"      Desc: {obj.get('description', '')}")
                    print(f"      Color: {obj.get('color', '')}")

            print(f"  Packaging: {result.get('packaging_condition', '')}")
            if result.get("notes"):
                print(f"  Notes: {result['notes']}")
        else:
            print(f"  RAW: {result.get('raw_content', '')[:200]}")

        print(f"  Tokens: {result.get('_tokens', 0)}")

    # Summary
    print(f"\n{'=' * 70}")
    print("SUMMARY")
    print("=" * 70)
    detected_count = sum(1 for r in results if r.get("has_foreign_object"))
    print(f"Total images: {len(results)}")
    print(f"Foreign objects detected: {detected_count}")
    print(f"Clean: {len(results) - detected_count}")

    # Save full results
    output_path = os.path.join(os.path.dirname(__file__), "foreign_object_detection_results.json")
    with open(output_path, "w", encoding="utf-8") as f:
        json.dump(results, f, ensure_ascii=False, indent=2)
    print(f"\nFull results saved: {output_path}")


if __name__ == "__main__":
    main()
