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
    # ── 菜品链：缺时间 → 缺门店 → 结果 → 指标/对象切换 ──
    {"q": "米饭的销量是多少", "chain": "dish",
     "contains": ["哪个时间范围"],
     "followup_contains": ["本月", "上个月", "最近7天", "最近30天"],
     "followup_excludes": ["全部门店", "兄弟土菜馆"]},
    {"q": "本月", "chain": "dish",
     "contains": ["哪一组门店"],
     "followup_contains": ["全部门店", "青花椒新世界新丸中心店"],
     "followup_excludes": ["兄弟土菜馆", "有滋有味总部"]},
    {"q": "全部门店", "chain": "dish",
     "contains": ["「米饭」", "销量"]},
    {"q": "那成本呢", "chain": "dish", "contains": ["「米饭」", "成本"]},
    {"q": "那招牌藤椒味(单人份)呢", "chain": "dish",
     "contains": ["「招牌藤椒味(单人份)」"], "excludes": ["「米饭」"]},
    {"q": "那毛利呢", "chain": "dish",
     "contains": ["「招牌藤椒味(单人份)」"], "excludes": ["「米饭」"]},
    {"q": "是否合理", "chain": "dish", "contains": ["「招牌藤椒味(单人份)」"]},
    {"q": "怎么优化", "chain": "dish", "contains": ["「招牌藤椒味(单人份)」"]},
    {"q": "换成上个月呢", "chain": "dish",
     "contains": ["「招牌藤椒味(单人份)」", "2026-06"]},
    # ── 真实前端按钮链：具名门店必须来自当前菜品/时间的实际销售范围 ──
    {"q": "米饭的销量是多少", "chain": "dish_named_store",
     "contains": ["哪个时间范围"],
     "followup_contains": ["本月", "上个月", "最近7天", "最近30天"]},
    {"q": "本月", "chain": "dish_named_store",
     "contains": ["哪一组门店"],
     "followup_contains": ["青花椒新世界新丸中心店"],
     "followup_excludes": ["兄弟土菜馆", "有滋有味总部"]},
    {"q": "青花椒新世界新丸中心店", "chain": "dish_named_store",
     "contains": ["青花椒新世界新丸中心店", "「米饭」", "销量"],
     "excludes": ["没有找到", "毛利或毛利率"]},
    # ── 菜品独立问 ──
    {"q": "本月全部门店米饭赚钱吗", "contains": ["结论", "「米饭」", "毛利率"]},
    {"q": "这周全部门店米饭卖了多少", "contains": ["「米饭」", "销量"]},
    {"q": "本月全部门店米饭的营收和销量分别是多少",
     "contains": ["「米饭」", "营收", "销量"]},
    {"q": "本月全部门店米饭和招牌藤椒味(单人份)哪个赚钱",
     "contains": ["毛利"], "excludes": ["没有找到"]},
    {"q": "本月全部门店不存在的菜ABC的销量",
     "contains": ["没有找到"], "excludes": ["排行"]},
    # ── 排名 / 存在性 ──
    {"q": "本月全部门店哪个菜卖得好", "contains": ["菜品销量排行", "销量"],
     "excludes": ["门店营收", "经营销售概览", "最强门店"]},
    {"q": "本月全部门店哪个菜卖得最好", "contains": ["销量排行", "已剔除"],
     "excludes": [". 打包盒", ". 需要餐具", ". 无需餐具"]},
    {"q": "本月全部门店哪道菜卖得最差", "contains": ["卖得最差"]},
    {"q": "本月全部门店有没有毛利率是负的菜",
     "contains": ["毛利", "成本覆盖率"]},
    {"q": "本月全部门店有没有店在亏损",
     "contains": ["门店", "成本覆盖率"]},
    {"q": "上个月全部门店哪家店亏钱了",
     "contains": ["门店"], "excludes": ["没有找到名为"]},
    {"q": "本月全部门店毛利率最高的菜是哪个", "contains": ["毛利"]},
    # ── 店×菜 ──
    {"q": "本月全部门店哪家店的米饭卖得最好",
     "contains": ["「米饭」", "门店销量排行"]},
    {"q": "本月全部门店哪家店的招牌藤椒味卖得最好",
     "contains": ["「招牌藤椒味」", "门店销量排行"]},
    {"q": "本月鲜行者打浦桥日月光店的米饭卖得怎么样",
     "contains": ["鲜行者打浦桥日月光店", "「米饭」", "销量"]},
    {"q": "本月鲜行者打浦桥日月光店的毛利率",
     "contains": ["鲜行者打浦桥日月光店", "毛利率"]},
    # ── 营收 / 时间窗 ──
    {"q": "这个月全部门店生意怎么样", "contains": ["本月", "总营收"]},
    {"q": "昨天全部门店卖了多少钱", "contains": ["昨天"]},
    {"q": "今天全部门店营业额多少", "contains": ["今天"]},
    {"q": "全部门店今年比去年增长多少", "contains": ["今年", "去年"]},
    {"q": "全部门店上周和上上周营收对比", "contains": ["上周", "上上周"]},
    {"q": "本月全部门店订单量如何", "contains": ["单"],
     "excludes": ["请检查是否上传"]},
    {"q": "哪个门店营收最好", "chain": "store_rank",
     "contains": ["哪个时间范围"],
     "followup_contains": ["本月", "上个月", "最近7天", "最近30天"]},
    {"q": "本月", "chain": "store_rank",
     "contains": ["结论", "营收最高"]},
    {"q": "最近30天各门店的营收排名", "contains": ["门店"]},
    {"q": "本月全部门店晚上生意怎么样", "contains": ["晚市"]},
    {"q": "全部门店3月份的营收多少", "contains": ["2026年3月"],
     "excludes": ["没有找到名为"]},
    {"q": "全部门店去年12月的营收", "contains": ["2025年12月"],
     "excludes": ["全部历史"]},
    {"q": "全部门店2026年3月生意怎么样", "contains": ["2026年3月"]},
    {"q": "全部门店最近三个月营收趋势", "contains": ["营收趋势"],
     "excludes": ["全部历史"]},
    {"q": "全部门店上上个月营收多少",
     "contains": ["2026-05-01 至 2026-05-31"]},
    {"q": "全部门店2025年全年营收多少", "contains": ["2025年"],
     "excludes": ["没有找到名为"]},
    {"q": "日月光店的营收",
     "contains": ["请问您指的是哪家", "日月光店"],
     "followup_contains": ["鲜行者打浦桥日月光店", "青花椒徐汇日月光店"],
     "followup_excludes": ["兄弟土菜馆", "有滋有味总部"]},
    {"q": "本月全部门店米饭和娃娃菜和招牌藤椒味(单人份)的销量",
     "contains": ["米饭", "娃娃菜", "招牌藤椒味(单人份)"],
     "excludes": ["请指定其中一道"]},
    {"q": "本月全部门店哪些菜没人点", "contains": ["卖得最差"]},
    {"q": "本月全部门店外卖占了几成", "contains": ["外卖", "堂食", "单量占"]},
    {"q": "全部门店哪些食材快没了", "contains": ["补货"]},
    {"q": "本月全部门店翻台率怎么样", "contains": ["翻台率"],
     "excludes": ["操作已完成"]},
    {"q": "本月全部门店客单价最高的店是哪家", "contains": ["平均每单"],
     "excludes": ["没有找到名为"]},
    {"q": "本月全部门店米饭的销量、毛利率和成本分别是多少",
     "contains": ["「米饭」"]},
    {"q": "这个月全部门店生意怎么样，另外米饭卖得好不好",
     "contains": ["本月", "「米饭」"]},
    {"q": "全部门店卤炸牛肉串本月销量为什么低",
     "contains": ["判断", "“销量低”的前提"],
     "excludes": ["如果你问的是销量为什么上涨或下降"]},
    {"q": "全部门店卤炸牛肉串本月销量为什么高",
     "contains": ["判断", "“销量高”的前提"],
     "excludes": ["如果你问的是销量为什么上涨或下降"]},
    {"q": "先告诉我昨天全部门店的营收，再告诉我哪家店业绩最好",
     "contains": ["昨天", "鲜行者打浦桥日月光店"]},
    # ── 能力 / 域外 / 方法论 ──
    {"q": "你们能做什么", "contains": ["门店经营数据"]},
    {"q": "今天天气怎么样", "contains": ["不会编造"],
     "excludes": ["库存、生产、质检"]},
    {"q": "毛利率低有什么行业参考做法", "contains": ["行业参考做法"]},
    {"q": "行业参考做法", "contains": ["行业参考做法"]},
    # ── 损耗 / 库存 (Java 工具面) ──
    {"q": "最近损耗怎么样", "contains": ["损耗"]},
    # ── 周初当前周期暂无数据：不泛化补数，主动给可执行的相邻周期 ──
    {"q": "这周全部门店营收怎么提高，给我今天能做的动作",
     "contains": ["本周截至今天", "没有可用的经营数据", "最近7天", "上周"],
     "followup_contains": ["最近7天", "上周", "最近30天"],
     "excludes": ["请先把缺少的数据补齐", "当前能查到的数据还不够"]},
    {"q": "最近7天全部门店营收怎么提高，给我今天能做的动作",
     "contains": ["优化建议", "营收"],
     "excludes": [
         "本次结果没有可靠覆盖",
         "我没太看懂",
         "请先把缺少的数据补齐",
     ]},
    # ── 操作模式：自然说法进入确认；数据筛选批量操作先列候选再逐项确认 ──
    {"q": "下架卤炸牛肉串", "mode": "OPERATE", "preview_only": True,
     "contains": ["确认后将下架菜品"],
     "result_contains": ["卤炸牛肉串", "已下架"],
     "excludes": ["还没有把握直接回答", "请切换到【操作】页"]},
    {"q": "把最近7天全部门店销量最低的5道菜下架", "mode": "OPERATE",
     "contains": ["先查看候选菜品", "具体菜名", "只有确认后才会执行"],
     "followup_contains": ["先查看这5道菜"],
     "excludes": ["未找到菜品「最近7天全部门店销量最低的5道」"]},
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
    latencies: List[float] = []
    for idx, case in enumerate(CASES, 1):
        q = case["q"]
        if only and only not in q:
            continue
        chain = case.get("chain")
        sid = chain_sessions.setdefault(chain, _rand_sid(chain)) if chain else _rand_sid("solo")
        problems: List[str] = []
        case_started = time.time()
        flat = ""
        flat_result = ""
        flat_followups = ""
        # 蓝绿切换/熔断窗口会产生瞬态失败 — 失败自动重试一次再定论。
        for attempt in (1, 2):
            try:
                payload = {"userInput": q, "sessionId": sid}
                if case.get("mode"):
                    payload["mode"] = case["mode"]
                if case.get("preview_only"):
                    payload["previewOnly"] = True
                resp = _post_json(
                    f"{base}/api/mobile/{FACTORY_ID}/ai-intents/execute",
                    payload,
                    headers=auth,
                )
                response_data = resp.get("data") or {}
                message = str(response_data.get("message") or "")
                result_data = response_data.get("resultData") or {}
                flat_result = json.dumps(
                    response_data,
                    ensure_ascii=False,
                    sort_keys=True,
                )
                followups = result_data.get("suggestedFollowups") or []
                flat_followups = " ".join(
                    f"{item.get('label') or ''} {item.get('question') or ''}"
                    for item in followups
                    if isinstance(item, dict)
                )
            except Exception as exc:  # noqa: BLE001 — eval must report, not crash
                message = f"<TRANSPORT ERROR: {exc}>"
                flat_result = ""
                flat_followups = ""
            flat = " ".join(message.split())
            problems = []
            for marker in case.get("contains", []):
                if marker not in flat:
                    problems.append(f"缺少「{marker}」")
            for marker in case.get("excludes", []) + _FORBIDDEN_EVERYWHERE:
                if marker in flat:
                    problems.append(f"不应出现「{marker}」")
            for marker in case.get("result_contains", []):
                if marker not in flat_result:
                    problems.append(f"结构化结果缺少「{marker}」")
            for marker in case.get("followup_contains", []):
                if marker not in flat_followups:
                    problems.append(f"按钮缺少「{marker}」")
            for marker in case.get("followup_excludes", []):
                if marker in flat_followups:
                    problems.append(f"按钮不应出现「{marker}」")
            if not problems or chain:
                break  # 链内不重试 (会污染会话)
            if attempt == 1:
                time.sleep(30)  # 蓝绿/熔断窗冷却后再试
        elapsed = time.time() - case_started
        latencies.append(elapsed)
        if problems:
            failed += 1
            failures.append(
                f"[{idx:02d}] {q}\n"
                f"     {'; '.join(problems)}\n"
                f"     实际: {flat[:160]}\n"
                f"     按钮: {flat_followups[:160]}"
            )
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
    parser.add_argument("--base", default="https://admin.cretaceousfuture.com",
                        help="Java 后端 base URL")
    parser.add_argument("--only", default="",
                        help="only run cases whose query contains this substring")
    args = parser.parse_args()
    sys.exit(run_eval(args.base, args.only or None))


if __name__ == "__main__":
    main()
