"""按**部门**跑餐饮 AI 能力审计 —— 每个部门问它本职会问的业务问题。

与 `restaurant_capability_audit.py` 的区别: 那一份按**话题**分组(sales/wastage/dish…),
用来看整体能力分; 这一份按**四个部门**分组, 回答的是另一个问题:

    「运营/市场/人事/财务 这四个岗位的分析师坐下来用, 各自问得出答案吗?」

四部门驾驶舱的 AI 区给每个部门配了推荐问题, 这份审计就是验证那些推荐问题
(以及同类的真实业务问句)是不是真的答得了 —— 推荐一个答不了的问题, 比不推荐更糟。

额外记录**建议**: 答案里有没有可执行的建议段。只报数字不给动作, 对分析师是半成品。

用法(服务器上):
    PYTHONPATH=<repo>/backend/python:<repo>/backend/python/smartbi \
      venv-current/bin/python -m scripts.restaurant_department_audit --factory MOCK_REST
"""
from __future__ import annotations

import argparse
import asyncio
import os
import re
import sys
import time
from typing import Any, Dict, List, Tuple

# (部门, 问句, 期望落到的 intent 之一)
CASES: Tuple[Tuple[str, str, Tuple[str, ...]], ...] = (
    # ── 运营: 后厨供应链 ──────────────────────────────────────────
    ("运营", "全部门店最近30天损耗金额最高的食材是哪几个", ("WASTAGE_TOP",)),
    ("运营", "全部门店最近30天报损情况严不严重", ("WASTAGE_TOP",)),
    ("运营", "全部门店最近30天领料最多的是哪些食材", ("REQUISITION_TREND",)),
    ("运营", "全部门店最近30天哪些食材经常盘亏", ("STOCK_SHORTAGE",)),
    # 窗口口径 —— #2076/#2081 修的正是这一类, 换个窗口必须换答案
    ("运营", "全部门店上个月损耗金额是多少", ("WASTAGE_TOP",)),
    ("运营", "全部门店上个月领料花了多少钱", ("REQUISITION_TREND",)),

    # ── 市场: 营收与菜品 ──────────────────────────────────────────
    ("市场", "全部门店最近30天生意怎么样", ("SALES_SUMMARY",)),
    ("市场", "全部门店最近30天的客单价是多少", ("SALES_SUMMARY",)),
    ("市场", "全部门店最近30天营收走势怎么样", ("TREND_ANALYSIS", "SALES_SUMMARY")),
    ("市场", "全部门店最近30天堂食和外卖的占比", ("CHANNEL_MIX",)),
    ("市场", "全部门店最近30天卖得最好的几个菜是哪些", ("GROSS_MARGIN",)),
    ("市场", "最近30天哪家店业绩最好", ("STORE_MARGIN", "SALES_SUMMARY")),

    # ── 财务: 毛利与成本 ──────────────────────────────────────────
    ("财务", "全部门店最近30天哪家店毛利最好", ("STORE_MARGIN",)),
    ("财务", "全部门店最近30天哪些菜的食材成本最高", ("RECIPE_COST",)),
    ("财务", "全部门店最近30天毛利最低的菜品有哪些", ("GROSS_MARGIN",)),
    ("财务", "全部门店最近30天采购花了多少钱", ("REQUISITION_TREND",)),

    # ── 人事: 目前无事实数据, 预期答不出 —— 但必须**如实说**而不是编 ──
    ("人事", "哪个时段人手不够", ("STAFFING_ADVICE",)),
    ("人事", "上个月人效怎么样", ("STAFFING_ADVICE",)),
)

# 「有没有给建议」的判据: 答案里出现可执行动作段。
_ADVICE_RE = re.compile(r"建议动作|建议[:：]|优化目标|接下来|下一步|应当|应该先")


def classify(intent: str, expected: Tuple[str, ...], kind: str, answer: str) -> str:
    if not answer.strip():
        return "EMPTY"
    if intent and expected and intent not in expected:
        return "WRONG_INTENT"          # 答出来了, 但答的不是你问的 —— 最危险的一类
    if kind != "answer":
        return "NO_ANSWER"
    return "OK"


async def _run_case(pool, fid: str, role: str, question: str) -> Dict[str, Any]:
    from smartbi.gold.restaurant.restaurant_intent import parse_restaurant_query
    from smartbi.gold.restaurant.restaurant_intent_service import (
        should_delegate, tiered_answer,
    )
    from smartbi.gold.restaurant.restaurant_ops_router import dish_catalogue_scope

    started = time.time()
    out: Dict[str, Any] = {"intent": "", "kind": "", "answer": "", "error": "", "advice": False}
    try:
        async with dish_catalogue_scope(pool, fid):
            spec = await parse_restaurant_query(
                question, pool, factory_id=fid, semantic_first=True,
            )
        if spec is None:
            out["error"] = "spec=None"
            return out
        out["intent"] = (spec.intent or "").replace("RESTAURANT_OPS_", "")
        if should_delegate(spec, None, query=question):
            result = await tiered_answer(question, pool, fid, role, precomputed_spec=spec)
            if result:
                out["kind"] = result.get("kind") or "-"
                out["answer"] = result.get("answer_text") or ""
                out["advice"] = bool(_ADVICE_RE.search(out["answer"]))
                out["followups"] = [
                    f.get("label") for f in (result.get("suggested_followups") or [])
                ]
        else:
            out["kind"] = "no-delegate"
    except Exception as exc:  # noqa: BLE001 — 审计只记录, 不因单条炸掉整轮
        out["error"] = f"{type(exc).__name__}: {exc}"[:160]
    finally:
        out["seconds"] = time.time() - started
    return out


async def main_async(args: argparse.Namespace) -> int:
    from smartbi.config import get_pg_pool
    from smartbi.tenant_ctx import set_factory_id

    set_factory_id(args.factory)          # 不设 RLS 会造出一堆假缺陷
    pool = await get_pg_pool()

    by_dept: Dict[str, List[Tuple[str, Dict[str, Any]]]] = {}
    for dept, question, expected in CASES:
        res = await _run_case(pool, args.factory, args.role, question)
        res["verdict"] = classify(res["intent"], expected, res["kind"], res["answer"])
        res["question"] = question
        res["expected"] = expected
        by_dept.setdefault(dept, []).append((question, res))
        print(
            "[%s] %-6s %-34s intent=%-18s advice=%s  %s"
            % (res["verdict"], dept, question[:34], res["intent"] or "-",
               "Y" if res["advice"] else "n", res["error"][:60]),
            flush=True,
        )

    print("\n" + "=" * 78, flush=True)
    print("按部门汇总  (租户 %s / 角色 %s)" % (args.factory, args.role), flush=True)
    print("=" * 78, flush=True)
    total_ok = total = 0
    for dept in ("运营", "市场", "财务", "人事"):
        rows = by_dept.get(dept, [])
        if not rows:
            continue
        ok = sum(1 for _, r in rows if r["verdict"] == "OK")
        adv = sum(1 for _, r in rows if r["advice"])
        total_ok += ok
        total += len(rows)
        bad = [r["verdict"] + "←" + q[:16] for q, r in rows if r["verdict"] != "OK"]
        print("%-4s 能答 %d/%d   带建议 %d/%d   %s"
              % (dept, ok, len(rows), adv, len(rows), ("  问题: " + "; ".join(bad)) if bad else ""),
              flush=True)
    print("-" * 78, flush=True)
    print("合计 能答 %d/%d" % (total_ok, total), flush=True)
    return 0


def main() -> int:
    p = argparse.ArgumentParser()
    p.add_argument("--factory", default=os.environ.get("AUDIT_FACTORY", "MOCK_REST"))
    p.add_argument("--role", default=os.environ.get("AUDIT_ROLE", "restaurant_manager"))
    return asyncio.run(main_async(p.parse_args()))


if __name__ == "__main__":
    sys.exit(main())
