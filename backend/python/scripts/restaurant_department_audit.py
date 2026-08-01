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

    # ── 人事 ──────────────────────────────────────────────────────
    # 2026-08-01 补上人效配置后「哪个时段人手不够」已能实质作答(日均订单取自
    # 真实 POS, 在岗人数/目标人效是配置)。「上个月人效怎么样」仍答不出 ——
    # LLM 会问「你想查看哪家门店」, 而 resolve_staffing_advice 收不到 store_id,
    # 与「问时间范围」是同一族缺陷(向用户要一个 resolver 消费不了的槽位), 未修。
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


# 「答出来了」和「真答出了东西」是两件事。
#
# classify() 只看 intent 对不对 / 非空 / kind=='answer' —— 于是一句
# 「毛利前 0 名菜品: 暂无成本完整的菜品」也算 OK。它**诚实**, 所以不该算失败;
# 但把它算成成功, 分数就会系统性高估能力。
#
# 2026-08-01 prod 实拍(MOCK_REST): 审计 16/18, 逐条看答案正文只有 13/18 真排出了
# 东西 —— 差的 3 条全在财务, 且是同一根因(agg_restaurant_product_cost 0 行)。
# 换到 RES_3101_009 那 3 条全部变 ✅, 证明是**数据缺口不是能力缺口**。
#
# ⛔ 刻意**不改 verdict**, 只加一列: 改口径会让历史分数不可比, 而这条信息的价值
#    恰恰在于和 verdict 并排看 —— 「OK 但不实质」正是需要被看见的那一格。
_ZERO_RESULT_RE = re.compile(
    r"前\s*0\s*名|暂无.{0,12}(?:排名|菜品|门店)|尚未录入|无法给出|不能生成|暂不可计算"
)
# 建议段里的 "1. 2. 3." 是行动项不是榜单 —— 不砍掉会把空答案误判成实质。
_ADVICE_SPLIT_RE = re.compile(r"建议动作[:：]|\n建议[:：]")


def is_substantive(answer: str) -> bool:
    """答案里是否真有内容(而不仅仅是诚实地说没有数据)。

    三种「有内容」的形态, 缺一不可 —— 判据自己漏掉一种就会误判成空壳:
      1. 排名行  `1. 罗氏虾 …`
      2. 多个非零金额  `¥21,280,945`
      3. **多条带数字的要点行**  `- 午市: 日均 1315 单, 95 人在岗, 人效 13.8/人`

    ⚠️ 第 3 种是 2026-08-01 补的, 补之前排班建议被误判成空壳 —— 它既没有编号榜单
    也没有金额(单位是「单/人」不是钱), 但它明明给出了 4 个时段的真实数据与可执行
    建议。**这是本判据第三次假阴性**(前两次: 建议段的 1./2./3. 被当成榜单;
    `¥21,280,945` 无小数位被当成没金额)。凡是「写个判据自动判」, 都要拿几条已知
    答案对一遍再信它。
    """
    if not answer.strip():
        return False
    body = _ADVICE_SPLIT_RE.split(answer)[0]
    if _ZERO_RESULT_RE.search(body):
        return False
    if len(re.findall(r"(?m)^\s*\d+\.\s", body)) > 0:
        return True
    amounts = [
        m for m in re.findall(r"¥\s*([\d,]+(?:\.\d+)?)", body)
        if float(m.replace(",", "")) > 0
    ]
    if len(amounts) >= 2:
        return True
    # 非金额的数据要点: 至少两行, 每行至少两个数 —— 一行一个数多半是叙述而非数据。
    data_bullets = [
        ln for ln in body.splitlines()
        if ln.strip().startswith(("-", "·", "•"))
        and len(re.findall(r"\d[\d,]*(?:\.\d+)?", ln)) >= 2
    ]
    return len(data_bullets) >= 2


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
        res["substantive"] = is_substantive(res["answer"])
        res["question"] = question
        res["expected"] = expected
        by_dept.setdefault(dept, []).append((question, res))
        print(
            "[%s] %-6s %-34s intent=%-18s advice=%s 实质=%s  %s"
            % (res["verdict"], dept, question[:34], res["intent"] or "-",
               "Y" if res["advice"] else "n",
               "Y" if res["substantive"] else "n", res["error"][:60]),
            flush=True,
        )

    print("\n" + "=" * 78, flush=True)
    print("按部门汇总  (租户 %s / 角色 %s)" % (args.factory, args.role), flush=True)
    print("=" * 78, flush=True)
    total_ok = total = total_real = 0
    for dept in ("运营", "市场", "财务", "人事"):
        rows = by_dept.get(dept, [])
        if not rows:
            continue
        ok = sum(1 for _, r in rows if r["verdict"] == "OK")
        adv = sum(1 for _, r in rows if r["advice"])
        real = sum(1 for _, r in rows if r["substantive"])
        total_ok += ok
        total_real += real
        total += len(rows)
        bad = [r["verdict"] + "←" + q[:16] for q, r in rows if r["verdict"] != "OK"]
        # 「OK 但不实质」= 诚实地说没数据。不是失败, 但也不该算成能力。
        hollow = [q[:16] for q, r in rows
                  if r["verdict"] == "OK" and not r["substantive"]]
        notes = []
        if bad:
            notes.append("问题: " + "; ".join(bad))
        if hollow:
            notes.append("OK但无实质(诚实说没数据): " + "; ".join(hollow))
        print("%-4s 能答 %d/%d   实质 %d/%d   带建议 %d/%d   %s"
              % (dept, ok, len(rows), real, len(rows), adv, len(rows),
                 ("  " + " | ".join(notes)) if notes else ""),
              flush=True)
    print("-" * 78, flush=True)
    print("合计 能答 %d/%d   实质 %d/%d" % (total_ok, total, total_real, total), flush=True)
    if total_ok != total_real:
        print(
            "> 注: 「能答」计入了诚实说没数据的回答, 「实质」只计真排出了排名/金额的。"
            "两者差 %d 条 —— 差值通常指向**数据缺口**而非能力缺口, "
            "换一个数据齐的租户复跑即可分辨。" % (total_ok - total_real),
            flush=True,
        )
    return 0


def main() -> int:
    p = argparse.ArgumentParser()
    p.add_argument("--factory", default=os.environ.get("AUDIT_FACTORY", "MOCK_REST"))
    p.add_argument("--role", default=os.environ.get("AUDIT_ROLE", "restaurant_manager"))
    return asyncio.run(main_async(p.parse_args()))


if __name__ == "__main__":
    sys.exit(main())
