"""工厂 AI 读路径回归电池 — 与 restaurant_ai_eval 同构 (2026-07-24 验收轮建立)。

覆盖工厂 demo (DEMO_FACTORY2) 的核心只读问法: 库存/设备/生产/质检/财务/
考勤/退货/订单。断言是结构性 marker (含/不含), 不锁具体数字。
本电池同时锁住 2026-07-24 验收修复: legacy 权限误拒全员 (原料查询)、
考勤占位符、退货/销售订单误路由。

    python -X utf8 -m smartbi.scripts.factory_ai_eval --base https://admin.cretaceousfuture.com

Exit 0 = 全过; 1 = 有失败 — cron/CI 可直接接。
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

_FORBIDDEN_EVERYWHERE = [
    "暂未配置执行器",
    "没有获得可展示的结果",
    "操作已完成。",          # 占位符掩盖真答案 (2026-07-23 修)
    "查询成功",              # 考勤占位符 (2026-07-24 修)
    "没有权限",              # 只读问法不该被权限拒 (legacy 误拒, 2026-07-24 修)
]

CASES: List[Dict[str, Any]] = [
    {"q": "库存有哪些预警", "contains": ["库存"]},
    {"q": "有哪些设备", "contains": ["设备"]},
    {"q": "今天的生产情况怎么样", "contains": ["生产批次"]},
    {"q": "最近的质检合格率怎么样", "contains": ["质检"]},
    # 财务: 有数出数, 无数诚实拒答 — 两种形态都必含"营业收入"字样
    {"q": "整体毛利率是多少", "contains": ["营业收入"]},
    # 2026-07-24 修: legacy hasPermission=false 误拒全员 → 修后正常出数据
    {"q": "原材料库存还剩多少", "contains": [], "excludes": []},
    # 2026-07-24 修: 考勤只回"查询成功"四字 → 修后有汇总数字
    {"q": "员工出勤情况", "contains": ["考勤统计"]},
    # 2026-07-24 修: 退货误路由到销售订单列表 → 新建 RETURN_ORDER_LIST 意图
    {"q": "最近的退货情况", "contains": ["退货"], "excludes": ["销售订单"]},
    # 2026-07-24 修: 销售订单误路由到生产仪表盘 → ORDER_LIST 补关键词
    {"q": "销售订单情况怎么样", "contains": ["订单"], "excludes": ["仪表盘总览"]},
    {"q": "销售订单有多少", "contains": ["订单"]},
]


def _rand_sid(prefix: str) -> str:
    tail = "".join(random.choices(string.ascii_lowercase + string.digits, k=8))
    return f"feval-{prefix}-{tail}"


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


def run_eval(base: str, only: Optional[str] = None) -> int:
    login = _post_json(f"{base}/api/mobile/auth/demo-login?tenant=factory", {})
    token = (login.get("data") or {}).get("token") or (login.get("data") or {}).get("accessToken")
    if not token:
        print("FATAL: factory demo login failed", login)
        return 2
    auth = {"Authorization": f"Bearer {token}"}

    passed, failed = 0, 0
    failures: List[str] = []
    latencies: List[float] = []
    for idx, case in enumerate(CASES, 1):
        q = case["q"]
        if only and only not in q:
            continue
        problems: List[str] = []
        started = time.time()
        flat = ""
        for attempt in (1, 2):  # 蓝绿切换窗瞬态失败重试一次
            try:
                resp = _post_json(
                    f"{base}/api/mobile/{FACTORY_ID}/ai-intents/execute",
                    {"userInput": q, "sessionId": _rand_sid(f"c{idx}")},
                    headers=auth,
                )
                data = resp.get("data") or {}
                message = str(data.get("message") or "")
            except Exception as exc:  # noqa: BLE001
                message = f"<TRANSPORT ERROR: {exc}>"
            flat = " ".join(message.split())
            problems = []
            for marker in case.get("contains", []):
                if marker not in flat:
                    problems.append(f"缺少「{marker}」")
            for marker in case.get("excludes", []) + _FORBIDDEN_EVERYWHERE:
                if marker in flat:
                    problems.append(f"不应出现「{marker}」")
            if not problems:
                break
            if attempt == 1:
                time.sleep(20)
        elapsed = time.time() - started
        latencies.append(elapsed)
        if problems:
            failed += 1
            failures.append(f"[{idx:02d}] {q}\n     {'; '.join(problems)}\n     实际: {flat[:160]}")
            print(f"✗ [{idx:02d}] {elapsed:5.1f}s {q} — {'; '.join(problems)}")
        else:
            passed += 1
            print(f"✓ [{idx:02d}] {elapsed:5.1f}s {q}")

    if latencies:
        ordered = sorted(latencies)
        p95 = ordered[max(0, int(len(ordered) * 0.95) - 1)]
        print(f"\n耗时: 平均 {sum(latencies)/len(latencies):.1f}s | 中位 "
              f"{ordered[len(ordered)//2]:.1f}s | p95 {p95:.1f}s | 最慢 {ordered[-1]:.1f}s")
    print(f"== {passed} passed, {failed} failed / {passed + failed} run ==")
    if failures:
        print("\n".join(["", "── 失败明细 ──", *failures]))
    return 1 if failed else 0


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base", default="https://admin.cretaceousfuture.com")
    parser.add_argument("--only", default="")
    args = parser.parse_args()
    sys.exit(run_eval(args.base, args.only or None))


if __name__ == "__main__":
    main()
