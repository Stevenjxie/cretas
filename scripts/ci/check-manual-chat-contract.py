#!/usr/bin/env python3
"""CI lint: 守护 manual_chat.py 关键字段不被 merge 误丢。

历史事故 (2026-04-29): commit 928f12eef 在 e2e/v1-framework merge 时误丢了
ManualChatRequest 的 category / image_base64 字段，导致工厂版 RAG 路由
本地代码失效（prod 仍正常因为是从其他分支部署的）。本脚本作为兜底守护。
"""
import re
import sys
from pathlib import Path

MANUAL_CHAT = Path("backend/python/food_kb/api/manual_chat.py")

REQUIRED_FIELDS = [
    # ManualChatRequest 必须含的字段（pattern 是行匹配的子串）
    ('category: Optional[str]', '前端 explicit 域路由参数'),
    ('image_base64: Optional[str]', '截图 OCR 输入字段'),
]

REQUIRED_LOGIC = [
    # 必须含的关键逻辑标记
    ('request.category in ("restaurant", "factory")', '显式 category 路由分支'),
    ('_ocr_extract_text', 'OCR 提取函数'),
    # 2026-06-01: OCR 由直连 LLM_OCR_MODEL 改走 call_chain(SLOT.VL) 免费 VL 链
    # (PR #352, 旁路收口). 守卫从 env 名改为"OCR 仍经免费 VL 链路由"不被误丢.
    ('call_chain(SLOT.VL', 'OCR 经免费 VL 链路由 (取代旧 LLM_OCR_MODEL 直连)'),
]

def main():
    if not MANUAL_CHAT.exists():
        print(f"FAIL: {MANUAL_CHAT} not found")
        sys.exit(1)

    text = MANUAL_CHAT.read_text(encoding="utf-8")
    failures = []

    for pattern, desc in REQUIRED_FIELDS + REQUIRED_LOGIC:
        if pattern not in text:
            failures.append(f"  Missing: {pattern!r} ({desc})")

    if failures:
        print(f"FAIL: manual_chat.py 缺关键字段/逻辑（可能被 merge 误丢）：")
        for f in failures:
            print(f)
        print(f"\n  历史事故: 2026-04-29 commit 928f12eef merge 时丢失这些字段。")
        print(f"  修复参考: 从 prod 47.100.235.168:/www/wwwroot/cretas/code/backend/python/food_kb/api/manual_chat.py 同步")
        sys.exit(1)
    print(f"PASS: manual_chat.py 含全部 {len(REQUIRED_FIELDS) + len(REQUIRED_LOGIC)} 项关键字段/逻辑。")

if __name__ == "__main__":
    main()
