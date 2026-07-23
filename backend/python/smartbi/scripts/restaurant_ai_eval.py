"""Restaurant AI regression eval — the accumulated probe battery, runnable.

Every phrasing class fixed in the R6→R20 sweep rounds (Sheet 7/22 菜品链,
存在性/排名/比较/能力/域外/单日/同比/店×菜/口语盈亏 …) lives here as an
executable expectation.  Run it after ANY routing/resolver/extractor change:

    python -m smartbi.scripts.restaurant_ai_eval --base https://admin.cretaceousfuture.com

Checks are STRUCTURAL (markers that must / must-not appear), not exact
numbers — demo data rolls daily so numbers drift by design.  A case fails
loudly with the actual answer so triage is one read, not a re-probe.

Chains run in one shared session (multi-turn inheritance / entity switch).
Exit code 0 = all pass; 1 = any fail — safe for CI / cron wiring.
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

FACTORY_ID = "DEMO_REST"

# ── Case schema ──────────────────────────────────────────────────────────
# {q, contains: [...], excludes: [...], chain: str|None}
# chain: cases sharing the same chain id run in ONE session, in file order.
_FORBIDDEN_EVERYWHERE = [
    "暂未配置执行器",           # dead-end (R13e/R14c 修)
    "没有获得可展示的结果",     # generic wrapper swallow (R14b 修)
    "Step1",                    # reasoning leak (R13c 修)
    "实体识别",                 # reasoning leak
    "工厂制造分析不适用",       # G4 factory frame (R20 修)
    "生产统计报告",             # G4 upload frame (R15 修)
]

CASES: List[Dict[str, Any]] = [
    # ── 菜品链 (Sheet 7/22 原始 7 轮) ──
    {"q": "米饭的销量是多少", "chain": "dish",
     "contains": ["「米饭」", "销量"]},
    {"q": "这个过去一个月的销量如何", "chain": "dish",
     "contains": ["「米饭」"]},
    {"q": "成本如何", "chain": "dish", "contains": ["「米饭」"]},
    {"q": "那招牌藤椒味(单人份)呢", "chain": "dish",
     "contains": ["「招牌藤椒味(单人份)」"], "excludes": ["「米饭」"]},
    {"q": "成本如何", "chain": "dish",
     "contains": ["「招牌藤椒味(单人份)」"], "excludes": ["「米饭」"]},
    {"q": "是否合理", "chain": "dish", "contains": ["「招牌藤椒味(单人份)」"]},
    {"q": "怎么优化", "chain": "dish", "contains": ["「招牌藤椒味(单人份)」"]},
    # ── 菜品独立问 ──
    {"q": "米饭赚钱吗", "contains": ["结论", "「米饭」", "毛利率"]},
    {"q": "这周米饭卖了多少", "contains": ["「米饭」", "销量"]},
    {"q": "米饭的营收和销量分别是多少", "contains": ["「米饭」", "营收"]},
    {"q": "米饭和招牌藤椒味(单人份)哪个赚钱",
     "contains": ["毛利"], "excludes": ["没有找到"]},
    {"q": "不存在的菜ABC的销量",
     "contains": ["没有找到"], "excludes": ["排行"]},
    # ── 排名 / 存在性 ──
    {"q": "哪个菜卖得最好", "contains": ["销量排行", "已剔除"],
     "excludes": [". 打包盒", ". 需要餐具", ". 无需餐具"]},
    {"q": "哪道菜卖得最差", "contains": ["卖得最差"]},
    {"q": "有没有毛利率是负的菜", "contains": ["毛利", "成本覆盖率"]},
    {"q": "有没有店在亏损", "contains": ["门店", "成本覆盖率"]},
    {"q": "上个月哪家店亏钱了",
     "contains": ["门店"], "excludes": ["没有找到名为"]},
    {"q": "毛利率最高的菜是哪个", "contains": ["毛利"]},
    # ── 店×菜 ──
    {"q": "哪家店的米饭卖得最好", "contains": ["「米饭」", "门店销量排行"]},
    {"q": "哪家店的招牌藤椒味卖得最好",
     "contains": ["「招牌藤椒味」", "门店销量排行"]},
    {"q": "鲜行者打浦桥日月光店的米饭卖得怎么样",
     "contains": ["鲜行者打浦桥日月光店", "「米饭」", "销量"]},
    {"q": "鲜行者打浦桥日月光店的毛利率",
     "contains": ["鲜行者打浦桥日月光店", "毛利率"]},
    # ── 营收 / 时间窗 ──
    {"q": "这个月生意怎么样", "contains": ["本月", "总营收"]},
    {"q": "昨天卖了多少钱", "contains": ["昨天"]},
    {"q": "今天营业额多少", "contains": ["今天"]},
    {"q": "今年比去年增长多少", "contains": ["今年", "去年"]},
    {"q": "上周和上上周营收对比", "contains": ["上周", "上上周"]},
    {"q": "订单量最近如何", "contains": ["单"], "excludes": ["请检查是否上传"]},
    {"q": "哪家店业绩最好", "contains": ["第一名"]},
    {"q": "最近30天各门店的营收排名", "contains": ["门店"]},
    {"q": "晚上生意怎么样", "contains": ["晚市"]},
    {"q": "3月份的营收多少", "contains": ["2026年3月"],
     "excludes": ["没有找到名为"]},
    {"q": "去年12月的营收", "contains": ["2025年12月"],
     "excludes": ["全部历史"]},
    {"q": "2026年3月生意怎么样", "contains": ["2026年3月"]},
    {"q": "最近三个月营收趋势", "contains": ["营收趋势"],
     "excludes": ["全部历史"]},
    # ── 能力 / 域外 / 方法论 ──
    {"q": "你们能做什么", "contains": ["门店经营数据"]},
    {"q": "今天天气怎么样", "contains": ["不会编造"],
     "excludes": ["库存、生产、质检"]},
    {"q": "毛利率低有什么行业参考做法", "contains": ["行业参考做法"]},
    {"q": "行业参考做法", "contains": ["行业参考做法"]},
    # ── 损耗 / 库存 (Java 工具面) ──
    {"q": "最近损耗怎么样", "contains": ["损耗"]},
]


def _rand_sid(prefix: str) -> str:
    tail = "".join(random.choices(string.ascii_lowercase + string.digits, k=8))
    return f"eval-{prefix}-{tail}"


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
    login = _post_json(f"{base}/api/mobile/auth/demo-login?tenant=rest", {})
    token = (login.get("data") or {}).get("token") or (login.get("data") or {}).get("accessToken")
    if not token:
        print("FATAL: demo login failed", login)
        return 2
    auth = {"Authorization": f"Bearer {token}"}

    chain_sessions: Dict[str, str] = {}
    passed, failed = 0, 0
    failures: List[str] = []
    for idx, case in enumerate(CASES, 1):
        q = case["q"]
        if only and only not in q:
            continue
        chain = case.get("chain")
        sid = chain_sessions.setdefault(chain, _rand_sid(chain)) if chain else _rand_sid("solo")
        problems: List[str] = []
        flat = ""
        # 蓝绿切换/熔断窗口会产生瞬态失败 — 失败自动重试一次再定论。
        for attempt in (1, 2):
            try:
                resp = _post_json(
                    f"{base}/api/mobile/{FACTORY_ID}/ai-intents/execute",
                    {"userInput": q, "sessionId": sid},
                    headers=auth,
                )
                message = str(((resp.get("data") or {}).get("message")) or "")
            except Exception as exc:  # noqa: BLE001 — eval must report, not crash
                message = f"<TRANSPORT ERROR: {exc}>"
            flat = " ".join(message.split())
            problems = []
            for marker in case.get("contains", []):
                if marker not in flat:
                    problems.append(f"缺少「{marker}」")
            for marker in case.get("excludes", []) + _FORBIDDEN_EVERYWHERE:
                if marker in flat:
                    problems.append(f"不应出现「{marker}」")
            if not problems or chain:
                break  # 链内不重试 (会污染会话)
            if attempt == 1:
                time.sleep(30)  # 蓝绿/熔断窗冷却后再试
        if problems:
            failed += 1
            failures.append(f"[{idx:02d}] {q}\n     {'; '.join(problems)}\n     实际: {flat[:160]}")
            print(f"✗ [{idx:02d}] {q} — {'; '.join(problems)}")
        else:
            passed += 1
            print(f"✓ [{idx:02d}] {q}")

    print(f"\n== {passed} passed, {failed} failed / {passed + failed} run ==")
    if failures:
        print("\n".join(["", "── 失败明细 ──", *failures]))
    return 1 if failed else 0


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base", default="https://admin.cretaceousfuture.com",
                        help="Java 后端 base URL")
    parser.add_argument("--only", default="",
                        help="only run cases whose query contains this substring")
    args = parser.parse_args()
    sys.exit(run_eval(args.base, args.only or None))


if __name__ == "__main__":
    main()
