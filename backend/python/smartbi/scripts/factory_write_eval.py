"""工厂 AI 写路径回归电池 (2026-07-24 验收轮建立)。

锁住读写分块 P1/P1.5 的全部行为契约:
1. 写意图识别命中 (确定性写短语 → 正确 intentCode + WRITE_CONFIRM_REQUIRED + aiMode=WRITE)
2. mode=READ 强制只读 (写请求 → READ_MODE_WRITE_BLOCKED 跳转文案)
3. TCC 预览 (previewOnly → PREVIEW + confirmableAction: token/digest/300s)
4. demo 写闸 (confirm → DEMO_WRITE_BLOCKED, 演示不落库)
5. 读问法 aiMode=READ 标记

全程 DEMO_FACTORY2: demo 闸保证零真实写入 — 本电池天然安全。

    python -X utf8 -m smartbi.scripts.factory_write_eval --base https://admin.cretaceousfuture.com

Exit 0 = 全过; 1 = 有失败。
"""
from __future__ import annotations

import argparse
import json
import random
import string
import sys
import time
import urllib.request
from typing import Any, Dict, List, Optional

FACTORY_ID = "DEMO_FACTORY2"

# (短语, 期望 intentCode) — 关键词确定性命中的写意图
WRITE_INTENT_CASES = [
    ("帮我删除客户张三", "CUSTOMER_DELETE"),
    ("创建采购单", "PURCHASE_ORDER_CREATE"),
    ("重置配置", "CONFIG_RESET"),
    ("禁用功能开关测试", "FACTORY_FEATURE_TOGGLE"),
]

# TCC 全链探针: 需要 supportsPreview 的写工具 (product_create)
TCC_INTENT = "PRODUCT_CREATE_FLYWHEEL"
TCC_QUERY = "飞轮建产品 电池验证产品"


def _rand_sid(prefix: str) -> str:
    tail = "".join(random.choices(string.ascii_lowercase + string.digits, k=8))
    return f"weval-{prefix}-{tail}"


def _post_json(url: str, payload: Dict[str, Any],
               headers: Optional[Dict[str, str]] = None,
               timeout: int = 240) -> Dict[str, Any]:
    req = urllib.request.Request(
        url,
        data=json.dumps(payload).encode("utf-8"),
        headers={"Content-Type": "application/json", **(headers or {})},
        method="POST",
    )
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        return json.loads(resp.read().decode("utf-8"))


def run_eval(base: str) -> int:
    login = _post_json(f"{base}/api/mobile/auth/demo-login?tenant=factory", {})
    token = (login.get("data") or {}).get("token") or (login.get("data") or {}).get("accessToken")
    if not token:
        print("FATAL: factory demo login failed")
        return 2
    auth = {"Authorization": f"Bearer {token}"}

    def execute(payload: Dict[str, Any]) -> Dict[str, Any]:
        resp = _post_json(f"{base}/api/mobile/{FACTORY_ID}/ai-intents/execute",
                          payload, headers=auth)
        return resp.get("data") or {}

    passed, failed = 0, 0
    failures: List[str] = []

    def check(name: str, problems: List[str], detail: str = "") -> None:
        nonlocal passed, failed
        if problems:
            failed += 1
            failures.append(f"{name}: {'; '.join(problems)}\n     实际: {detail[:150]}")
            print(f"✗ {name} — {'; '.join(problems)}")
        else:
            passed += 1
            print(f"✓ {name}")

    # ── 1. 写意图命中 + 确认门 (默认 OPERATE) ──
    for q, expect_intent in WRITE_INTENT_CASES:
        d = execute({"userInput": q, "sessionId": _rand_sid("op")})
        problems = []
        if d.get("intentCode") != expect_intent:
            problems.append(f"意图应为 {expect_intent}, 实为 {d.get('intentCode')}")
        if d.get("status") not in ("WRITE_CONFIRM_REQUIRED", "NEED_MORE_INFO", "PENDING_APPROVAL"):
            problems.append(f"状态应为确认/补参, 实为 {d.get('status')}")
        if d.get("aiMode") != "WRITE":
            problems.append(f"aiMode 应为 WRITE, 实为 {d.get('aiMode')}")
        check(f"写命中: {q}", problems, str(d.get("message") or ""))
        time.sleep(1)

    # ── 2. mode=READ 强制只读 ──
    d = execute({"userInput": "帮我删除客户张三", "mode": "READ", "sessionId": _rand_sid("rd")})
    problems = []
    if d.get("status") != "READ_MODE_WRITE_BLOCKED":
        problems.append(f"状态应为 READ_MODE_WRITE_BLOCKED, 实为 {d.get('status')}")
    if "操作" not in str(d.get("message") or ""):
        problems.append("跳转文案缺失")
    check("READ 拦截: 删除客户", problems, str(d.get("message") or ""))

    # ── 3+4. TCC: 预览 → token → confirm → demo 闸 ──
    d = execute({"userInput": TCC_QUERY, "intentCode": TCC_INTENT,
                 "previewOnly": True, "sessionId": _rand_sid("tcc")})
    ca = d.get("confirmableAction") or {}
    problems = []
    if d.get("status") != "PREVIEW":
        problems.append(f"状态应为 PREVIEW, 实为 {d.get('status')}")
    if not ca.get("confirmToken"):
        problems.append("缺 confirmToken")
    if not ca.get("commandDigest"):
        problems.append("缺 commandDigest")
    # 写操作影响契约 (2026-07-24): 预览必须带影响说明与风险档 —
    # 前端对比表/二次确认弹窗的数据源, 缺失即契约破坏
    if not ca.get("impactSummary"):
        problems.append("缺 impactSummary (影响说明)")
    if not ca.get("actionType"):
        problems.append("缺 actionType")
    check("TCC 预览铸 token", problems, str(d.get("message") or "")[:100])

    if ca.get("confirmToken"):
        body = {"commandDigest": ca["commandDigest"], "expiresAt": ca["expiresAt"],
                "requestId": "weval-" + ca["commandDigest"][:12],
                "idempotencyKey": "wevali-" + ca["commandDigest"][:12]}
        try:
            resp = _post_json(f"{base}/api/mobile/{FACTORY_ID}/ai-intents/confirm", body,
                              headers={**auth, "X-Cretas-Confirmation-Token": ca["confirmToken"]})
            d3 = resp.get("data") or {}
        except Exception as exc:  # noqa: BLE001
            d3 = {"status": f"<ERROR {exc}>"}
        problems = []
        if d3.get("status") != "DEMO_WRITE_BLOCKED":
            problems.append(f"demo 闸应拦截 (DEMO_WRITE_BLOCKED), 实为 {d3.get('status')}")
        check("demo 写闸: confirm 不落库", problems, str(d3.get("message") or ""))
    else:
        check("demo 写闸: confirm 不落库", ["无 token 无法驱动 confirm"], "")

    # ── 5. 读问法 aiMode 标记 ──
    d = execute({"userInput": "库存有哪些预警", "sessionId": _rand_sid("aim")})
    problems = []
    if d.get("aiMode") != "READ":
        problems.append(f"aiMode 应为 READ, 实为 {d.get('aiMode')}")
    check("读问法 aiMode=READ", problems, str(d.get("message") or "")[:80])

    print(f"\n== {passed} passed, {failed} failed / {passed + failed} run ==")
    if failures:
        print("\n".join(["", "── 失败明细 ──", *failures]))
    return 1 if failed else 0


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base", default="https://admin.cretaceousfuture.com")
    args = parser.parse_args()
    sys.exit(run_eval(args.base))


if __name__ == "__main__":
    main()
