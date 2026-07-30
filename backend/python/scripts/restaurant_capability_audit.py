#!/usr/bin/env python3
"""餐饮 AI 端到端能力审计 —— 可重复跑的版本。

2026-07-30 手工做过一次(13/16 出真答案), 一次性脚本用完就丢, 下次回归得从头再来。
这一份把那次的方法与踩过的坑固化下来。

════════════════════════════════════════════════════════════════════════════
必须照生产序列走 (gold_reads.py 的调用顺序), 不能只调 planner:

    async with dish_catalogue_scope(pool, fid):
        spec = await parse_restaurant_query(q, pool, factory_id=fid,
                                            history=…, semantic_first=True)
    delegate = should_delegate(spec, None, query=q)
    result   = await tiered_answer(q, pool, fid, role, precomputed_spec=spec)

planner 前面有确定性出口(exact 注册表 / 计划缓存 / 显式槽位 / 可信上下文),
**菜品类问题生产可能压根走不到 T3**。上一版审计直接调 `_t3_llm_parse`, 得出
「模型之间 intent 不一致」的结论 —— 测的是生产不走的路。所以下面每条都记
`source_tier`, 让「路由」和「答案」永远分得开。

════════════════════════════════════════════════════════════════════════════
🔴 离线跑生产代码必须配齐三样, 否则会**造出看起来很真的假缺陷**
(2026-07-30 一晚踩了三次, 其中一次让经营诊断整条报「餐饮执行链暂时不可用」):

  1. 租户 ContextVar —— `set_factory_id(fid)`。asyncpg 池每次 acquire 据此写
     `app.factory_id`; 不设的话 RLS 让每个 tenant-scoped INSERT 失败, 表现和
     真实缺陷一模一样。生产由 JWTAuthMiddleware 设, 离线得自己设。
  2. **正确的角色** —— 餐饮的价格角色是 `restaurant_manager`
     (`PRICE_VIEW_ROLES` 不含 `owner`)。用 owner 跑, 金额全被脱敏成 `***`,
     那是**正确的 RBAC**, 不是能力缺失; 填错角色会让评估看起来比实际差很多。
  3. 整份服务进程环境 —— 见文件末尾的运行方法。

判据: 怀疑是缺陷时先 `grep` 生产日志, 0 次就是探针问题不是线上问题。

════════════════════════════════════════════════════════════════════════════
⛔ 用例**刻意不用**仓库自带的 SAMPLE_QUERIES: 那批话术就是当初用来调关键词的,
拿它评分 = 拿训练集当测试集(实测它得 0 误读, 而真实话术同期 17/20 误读)。

⚠️ LLM 有抖动, 所以默认**只报告不设闸**。要在 CI 里当门禁得显式
`--fail-under N`, 且 N 要留出抖动余量。
"""
from __future__ import annotations

import argparse
import asyncio
import os
import sys
import time
from typing import Any, Dict, List, Optional, Sequence, Tuple

# (family, question, acceptable intents; 空 tuple = 只要求"能答出来", 不限 intent)
CASES: Tuple[Tuple[str, str, Tuple[str, ...]], ...] = (
    ("sales", "全部门店最近30天生意怎么样", ("SALES_SUMMARY",)),
    ("sales", "全部门店最近30天的客单价是多少", ("SALES_SUMMARY",)),
    ("trend", "全部门店最近30天营收走势怎么样", ("TREND_ANALYSIS", "SALES_SUMMARY")),
    ("wastage", "全部门店最近30天损耗金额最高的食材是哪几个", ("WASTAGE_TOP",)),
    ("wastage", "全部门店最近30天报损情况严不严重", ("WASTAGE_TOP",)),
    ("requisition", "全部门店最近30天领料最多的是哪些食材", ("REQUISITION_TREND",)),
    # 2026-07-30 #2043: 「采购花了多少钱」曾被改写成 SALES_SUMMARY 后拒答。
    ("requisition", "全部门店最近30天采购花了多少钱", ("REQUISITION_TREND",)),
    # 菜品销量榜由 SALES_SUMMARY→GROSS_MARGIN 的合法 contract-repair 提供。
    ("dish", "全部门店最近30天卖得最好的几个菜是哪些", ("GROSS_MARGIN",)),
    ("dish", "全部门店最近30天米饭卖了多少", ("GROSS_MARGIN",)),
    ("dish", "全部门店最近30天哪些菜的食材成本最高", ("RECIPE_COST",)),
    ("store", "全部门店最近30天哪家店毛利最好", ("STORE_MARGIN",)),
    ("store", "我一共有几家店", ("STORE_DIRECTORY",)),
    ("stock", "全部门店最近30天盘点亏了多少", ("STOCK_SHORTAGE",)),
    # 2026-07-30 #2045: 曾经只吐裸英文码、一个百分比都没有。
    ("channel", "全部门店最近30天外卖占比多少", ("CHANNEL_MIX",)),
    ("staffing", "全部门店最近30天晚市人手够不够", ("STAFFING_ADVICE",)),
    ("optimize", "全部门店最近30天生意不太好，有什么办法提升",
     ("BUSINESS_OPTIMIZATION",)),
    ("meta", "你能帮我分析哪些东西", ("CAPABILITIES",)),
    ("ood", "明天下雨吗", ("OUT_OF_DOMAIN",)),
)

# 这些问句**应该**得到澄清而不是答案 —— 含糊就该问, 猜一个才是缺陷。
CLARIFY_CASES: Tuple[Tuple[str, str], ...] = (
    ("ambiguous", "帮我看一下数据"),
)


def classify(
    expected: Sequence[str],
    intent: str,
    kind: str,
) -> Tuple[str, str]:
    """把一次执行判成 (结果, 说明)。纯函数, 单测直接打这里。

    - OK          答出来了且 intent 在预期集合内
    - WRONG_INTENT 答出来了但走的是别的 resolver —— 这类最危险: 它**看起来像
                   正常答案**, 只是答的不是你问的那件事
    - CLARIFY     反问(可能合理, 也可能是该答没答, 由人看)
    - NO_ANSWER   既没答也没反问
    """
    if kind == "answer":
        if not expected or intent in expected:
            return "OK", ""
        return "WRONG_INTENT", f"预期 {'/'.join(expected)}, 实际 {intent or '-'}"
    if kind == "clarification":
        return "CLARIFY", ""
    return "NO_ANSWER", f"kind={kind or '-'}"


def _short(text: Optional[str], width: int) -> str:
    return (text or "").replace("\n", " ")[:width]


async def _run_case(pool, fid: str, role: str, question: str) -> Dict[str, Any]:
    from smartbi.gold.restaurant.restaurant_intent import parse_restaurant_query
    from smartbi.gold.restaurant.restaurant_intent_service import (
        should_delegate,
        tiered_answer,
    )
    from smartbi.gold.restaurant.restaurant_ops_router import dish_catalogue_scope

    started = time.time()
    out: Dict[str, Any] = {
        "intent": "", "tier": "", "kind": "", "answer": "", "error": "",
    }
    try:
        async with dish_catalogue_scope(pool, fid):
            spec = await parse_restaurant_query(
                question, pool, factory_id=fid, semantic_first=True,
            )
        if spec is None:
            out["error"] = "spec=None (空请求或非餐饮租户)"
            return out
        out["intent"] = (spec.intent or "").replace("RESTAURANT_OPS_", "")
        out["tier"] = spec.source_tier or "-"
        if should_delegate(spec, None, query=question):
            result = await tiered_answer(
                question, pool, fid, role, precomputed_spec=spec,
            )
            if result:
                out["kind"] = result.get("kind") or "-"
                out["answer"] = result.get("answer_text") or ""
        else:
            out["kind"] = "no-delegate"
    except Exception as exc:  # noqa: BLE001 — 审计只记录, 不因单条炸掉整轮
        out["error"] = f"{type(exc).__name__}: {exc}"[:140]
    finally:
        out["seconds"] = time.time() - started
    return out


async def _audit_one(pool, factory: str, role: str, verbose: bool) -> Dict[str, Any]:
    """跑完一个租户, 返回该租户的判定计数。"""
    from smartbi.tenant_ctx import set_factory_id

    set_factory_id(factory)  # ← 不设的话 RLS 会造出一堆假缺陷, 见文件头
    print("", flush=True)
    print("=" * 68, flush=True)
    print(f"  factory={factory}  role={role}", flush=True)
    print("=" * 68, flush=True)

    rows: List[Dict[str, Any]] = []
    for family, question, expected in CASES:
        result = await _run_case(pool, factory, role, question)
        verdict, note = classify(expected, result["intent"], result["kind"])
        rows.append({"family": family, "q": question, "verdict": verdict,
                     "note": note, **result})
        print(f"[{family:<11}] {verdict:<13} {result['intent'] or '-':<20} "
              f"tier={result['tier']:<12} {result['seconds']:.1f}s  {question[:30]}",
              flush=True)
        if note:
            print(f"    ⚠️ {note}", flush=True)
        if result["error"]:
            print(f"    ERROR {result['error']}", flush=True)
        if verbose and result["answer"]:
            print(f"    → {_short(result['answer'], 110)}", flush=True)

    for family, question in CLARIFY_CASES:
        result = await _run_case(pool, factory, role, question)
        good = result["kind"] == "clarification"
        rows.append({"family": family, "q": question,
                     "verdict": "OK" if good else "SHOULD_CLARIFY",
                     "note": "" if good else f"含糊问句却给了 {result['kind']}",
                     **result})
        print(f"[{family:<11}] {'OK' if good else 'SHOULD_CLARIFY':<13} "
              f"{result['intent'] or '-':<20} tier={result['tier']:<12} "
              f"{result['seconds']:.1f}s  {question[:30]}", flush=True)

    counts: Dict[str, int] = {}
    for row in rows:
        counts[row["verdict"]] = counts.get(row["verdict"], 0) + 1
    ok = counts.get("OK", 0)
    # 「整个变暗」= 一条都没答出来。这是 2026-07-31 修掉的那个回归的形态
    # (餐饮租户闸判错 → 每个问题 0.0 秒返回 None), 与「数据少所以答不出内容」
    # 是两回事 —— 后者仍然会正常走完链路并如实说无数据。
    went_dark = ok == 0
    print(f"  " + "  ".join(f"{k}={v}" for k, v in sorted(counts.items())))
    print(f"  OK {ok}/{len(rows)}" + ("   ⛔ 整个变暗" if went_dark else ""))
    wrong = [r for r in rows if r["verdict"] == "WRONG_INTENT"]
    if wrong:
        print("  ⚠️ 答错轴(最危险: 看起来像正常答案, 答的却不是你问的):")
        for row in wrong:
            print(f"     {row['q'][:34]} — {row['note']}")
    return {"factory": factory, "ok": ok, "total": len(rows),
            "counts": counts, "went_dark": went_dark,
            "wrong": len(wrong)}


async def main_async(args: argparse.Namespace) -> int:
    from smartbi.config import get_pg_pool

    factories = [f.strip() for f in args.factory.split(",") if f.strip()]
    pool = await get_pg_pool()
    print(f"租户: {', '.join(factories)}   role={args.role}   "
          f"cases={len(CASES)}+{len(CLARIFY_CASES)}/租户")
    print("序列: dish_catalogue_scope → parse_restaurant_query(semantic_first)"
          " → should_delegate → tiered_answer", flush=True)

    summaries = [
        await _audit_one(pool, factory, args.role, args.verbose)
        for factory in factories
    ]

    print("")
    print("=" * 68)
    print("  汇总")
    for item in summaries:
        flags = []
        if item["went_dark"]:
            flags.append("⛔整个变暗")
        if item["wrong"]:
            flags.append(f"⚠️答错轴×{item['wrong']}")
        print(f"    {item['factory']:<16} OK {item['ok']}/{item['total']}"
              + ("   " + " ".join(flags) if flags else ""))
    print("=" * 68)

    failed = False
    # ① 参考租户(第一个, 数据最全)看**能力分**。
    reference = summaries[0]
    if args.fail_under is not None and reference["ok"] < args.fail_under:
        print(f"FAIL: 参考租户 {reference['factory']} OK {reference['ok']} "
              f"< --fail-under {args.fail_under}")
        failed = True
    # ② 其余租户看**有没有整个变暗** —— 它们数据丰度不同, 大量「无数据」是
    #    正确行为(resolver 如实说没有), 用同一个 OK 门槛只会天天误报。真正
    #    要抓的是「一条都答不出来」, 那是租户闸/路由挂了的形态。
    for item in summaries:
        if item["went_dark"]:
            print(f"FAIL: {item['factory']} 一条都没答出来 —— 整个变暗"
                  f"(2026-07-31 修过一次这种回归)")
            failed = True
    return 1 if failed else 0


def main() -> int:
    parser = argparse.ArgumentParser(
        description="餐饮 AI 端到端能力审计(跑在真实代码 + 真实数据上)",
        epilog=(
            "服务器上运行:\n"
            "  REPO=/www/wwwroot/cretas/code\n"
            "  PID=$(systemctl show cretas-python -p MainPID --value)\n"
            "  tr '\\0' '\\n' < /proc/$PID/environ | grep -E '^(LLM_|POSTGRES_|SMARTBI|DB_)' > /tmp/env\n"
            "  cd $REPO/backend/python && env $(cat /tmp/env | tr '\\n' ' ') \\\n"
            "    PYTHONPATH=$REPO/backend/python:$REPO/backend/python/smartbi \\\n"
            "    ./venv-current/bin/python -u scripts/audit/restaurant_capability_audit.py\n"
            "\n长跑请 nohup 落盘 —— `ssh … | tail` 在 ssh 断开时什么都拿不到。"
        ),
        formatter_class=argparse.RawDescriptionHelpFormatter,
    )
    parser.add_argument(
        "--factory",
        default=os.environ.get("AUDIT_FACTORY_ID", "MOCK_REST"),
        help=(
            "逗号分隔的租户列表。第一个是参考租户(数据最全), --fail-under 只对它生效; "
            "其余租户只查「有没有整个变暗」—— 它们数据丰度不同, 大量「无数据」是正确行为。"
            "多租户覆盖不是可选项: 2026-07-31 一天内两次「换个租户就整体降级」"
            "(#2045 堂食外卖只认中文枚举 / #2060 餐饮租户闸用错判据)。"
        ),
    )
    # PRICE_VIEW_ROLES 不含 owner —— 填错角色金额全脱敏, 评估会假性变差。
    parser.add_argument("--role", default=os.environ.get("AUDIT_ROLE", "restaurant_manager"))
    parser.add_argument("--verbose", action="store_true", help="打印答案正文")
    parser.add_argument(
        "--fail-under", type=int, default=None,
        help="OK 数低于该值时退出码 1。默认不设 —— LLM 有抖动, 当门禁要留余量。",
    )
    args = parser.parse_args()
    return asyncio.run(main_async(args))


if __name__ == "__main__":
    sys.exit(main())
