# -*- coding: utf-8 -*-
"""餐饮读路径全景基线探针 —— **问句集落进仓库**，让基线可复现。

## 为什么它必须在仓库里（2026-08-18 的直接代价）

交接件报了一个基线「24 问句 × 2 轮，答上 41/48」，而**那份逐字问句集没有
落进仓库**。接手时我只能从文档里逐条摘出一份自己的 24 句，于是：

▎**41/48 与我量到的 40/48 不可比** —— 两个数看起来只差 1，其实是两个集合。

⇒ 口径就是仪器的一部分。问句集不落盘，下一个人的「基线」永远是重新造的。

## 用法（在**部署树**上跑，环境变量取自活服务进程）

    PID=$(ss -lntp | grep :8083 | grep -oP 'pid=\\K[0-9]+' | head -1)
    eval "$(tr '\\0' '\\n' < /proc/$PID/environ \\
            | grep -E '^(POSTGRES_|FOOD_KB_POSTGRES_|DASHSCOPE_|LLM_|AISTORE_|DEEPSEEK_)' \\
            | sed 's/^/export /')"
    venv-current/bin/python -m smartbi.scripts.restaurant_panorama_probe

环境变量：

    PROBE_FID          租户，默认 MOCK_REST
    PROBE_ROUNDS       轮数，默认 2
    PROBE_SHARED_KEY=1 24 句共用一个 session_key = **生产入口的形态**

## ⚠️ 两种 session_key 口径量的是两件事（⛔ 别混着比）

生产入口 `gold_reads` 对同一个 sessionId **全程共用一个** key，所以
`PROBE_SHARED_KEY=1` 才是「老板真实会话」的样子；独立 key 量的是
「每一句自己的能力」。

📏 2026-08-18 实测（MOCK_REST，同一份问句集）：

    口径          PR#2805 前      PR#2805 后
    独立 key      40/48 缺口2     40/48 缺口2
    共用 key      33/48 缺口1     40/48 缺口0    ← 「能力拒答不粘会话」的价值

🔴 我第一版探针**只有共用 key 一种**（而且是无意的：24 句写了同一个 key），
读出「翻台率/营收趋势/毛利最低的菜品」三句拿到逐字相同的 129 字，
差点当成产品缺陷写进报告。对照实验（唯一变量 session_key）分开了两件事：
仪器缺陷（量不到每句自己的能力）**和**一个真实的生产缺陷（澄清粘住会话）。

## 阳性对照（⛔ 不许省）

* 菜单目录条数 > 0
* 至少 1 条 A 且至少 1 条非 A —— 全 A 或全非 A 都说明仪器坏了
* 逐条打印 `source_tier`：分不出 `llm` / `plan_cache` 时，「两轮一致」没有意义
"""
from __future__ import annotations

import asyncio
import hashlib
import json
import os
import time

from smartbi.scripts._probe_bootstrap import bootstrap_probe

FID = os.environ.get("PROBE_FID", "MOCK_REST")
ROUNDS = int(os.environ.get("PROBE_ROUNDS", "2"))
SHARED_KEY = os.environ.get("PROBE_SHARED_KEY") == "1"

ctx = bootstrap_probe(FID)

from smartbi.gold.restaurant import restaurant_intent as ri  # noqa: E402
from smartbi.gold.restaurant import restaurant_ops_router as rr  # noqa: E402
from smartbi.gold.restaurant.restaurant_intent_service import (  # noqa: E402
    should_delegate,
    tiered_answer,
)
from smartbi.tenant_ctx import set_factory_id  # noqa: E402

#: ⛔ **改这份清单就等于换了仪器** —— 换了之后的数与换之前的不可比。
#: 要加句子就另起一个清单，并在报数时写明用的是哪一份。
QUESTIONS = (
    "哪家店卖得最好",
    "哪道菜毛利最高",
    "最近损耗怎么样",
    "这个月比上个月好还是差",
    "这周比上周怎么样",
    "最近生意好还是差",
    "哪家店成本最高",
    "米饭卖得怎么样",
    "哪家店缺货最严重",
    "我要不要关掉最差的那家店",
    "哪个时段生意最好",
    "客单价怎么样",
    "按门店看领料趋势",
    "损耗都是哪些类型造成的",
    "折扣力度多大",
    "食材成本占营收多少",
    "外卖和堂食各占多少",
    "哪个供应商报价最贵",
    "员工排班合理吗",
    "一共有多少家店",
    "今天到现在卖了多少",
    "翻台率怎么样",
    "营收趋势怎么样",
    "毛利最低的菜品有哪些",
)

#: ⛔ 用模块自带的 helper，不要拼名字 —— 拼错的属性名不会报错，
#: 只会让清理**静默失效**（本仓踩过：`_PLAN_CACHE` 根本不存在）。
CACHE_CLEARERS = (
    "clear_semantic_plan_cache",
    "clear_route_cache",
    "clear_tenant_gate_cache",
    "clear_promoted_routes_cache",
)


def clear_caches() -> list:
    for name in CACHE_CLEARERS:
        getattr(ri, name)()
    return list(CACHE_CLEARERS)


def _fp(text: str) -> str:
    return hashlib.md5((text or "").encode("utf-8")).hexdigest()[:8]


def verdict(kind, code, text) -> str:
    """A 有答案 / B 诚实缺数据 / C 域外 / D 反问或拒答。"""
    if code == "RESTAURANT_OPS_DATA_GAP" or "还没有数据" in (text or ""):
        return "B-诚实缺数据"
    if code == "RESTAURANT_OPS_OUT_OF_DOMAIN":
        return "C-域外"
    if kind == "answer":
        return "A-有答案"
    if kind == "clarification":
        return "D-反问/拒答"
    return "D-%s" % kind


async def run_turn(pool, query: str, session_key: str) -> dict:
    """完全复刻 `gold_reads.py` 那条生产序列（含 dish_catalogue_scope 的范围）。"""
    set_factory_id(FID)
    async with rr.dish_catalogue_scope(pool, FID):
        catalogue = rr.current_dish_catalogue()
        spec = await ri.parse_restaurant_query(
            query, pool, factory_id=FID, session_key=session_key,
            semantic_first=True,
        )
    row = {"q": query, "catalogue_n": len(catalogue) if catalogue else 0}
    if spec is None:
        return row | {"kind": "spec-None", "verdict": "D-spec-None"}
    row |= {
        "intent": (spec.intent or "∅").replace("RESTAURANT_OPS_", ""),
        "tier": spec.source_tier,
        "plan": [c.replace("RESTAURANT_OPS_", "") for c in spec.planned_intents],
        "dims": list(spec.dimensions),
    }
    if not should_delegate(spec, None, query=query):
        return row | {"kind": "no-delegate", "verdict": "D-no-delegate",
                      "contract": None}
    result = await tiered_answer(
        query, pool, FID, ctx.role, precomputed_spec=spec,
        session_key=session_key,
    )
    if not result:
        return row | {"kind": "None", "verdict": "D-None", "contract": None}
    text = result.get("answer_text") or ""
    code = (result.get("code") or "")
    return row | {
        "kind": result.get("kind") or "-",
        "code": code.replace("RESTAURANT_OPS_", ""),
        "contract": result.get("contract_pass"),
        "n": len(text),
        "fp": _fp(text),
        "verdict": verdict(result.get("kind"), code, text),
    }


def emit(row: dict) -> None:
    print("%-14s %-22s %-14s %-20s tier=%-12s plan=%-26s dims=%-16s "
          "contract=%-5s n=%-5s fp=%s"
          % (row.get("verdict", "?"), row["q"], row.get("kind", "-"),
             row.get("code", row.get("intent", "-")), row.get("tier", "-"),
             ",".join(row.get("plan", [])) or "∅",
             ",".join(row.get("dims", [])) or "∅",
             row.get("contract"), row.get("n"), row.get("fp")))


async def main() -> int:
    pool = await ctx.pool()
    print("PROBE_DB=%s FID=%s ROLE=%s ROUNDS=%d SHARED_KEY=%s TODAY=%s"
          % (ctx.db_name, FID, ctx.role, ROUNDS, SHARED_KEY,
             time.strftime("%Y-%m-%d")))
    print("CLEARED=%s  (只在第一轮前清一次 —— 第二轮就是要量回放)"
          % (clear_caches(),))

    rows, tally, gaps = [], {}, 0
    for rnd in range(1, ROUNDS + 1):
        print("\n---------- 第 %d 轮 ----------" % rnd)
        for qi, q in enumerate(QUESTIONS):
            key = ("pan-shared-r%d" % rnd) if SHARED_KEY else (
                "pan-r%d-q%d" % (rnd, qi))
            row = await run_turn(pool, q, key)
            row["round"] = rnd
            rows.append(row)
            emit(row)
            tally[row["verdict"]] = tally.get(row["verdict"], 0) + 1
            if row.get("contract") is False:
                gaps += 1

    total = len(rows)
    answered = tally.get("A-有答案", 0)
    print("\n### 全景小计: 答上(A) %d / %d" % (answered, total))
    for k in sorted(tally):
        print("    %-14s %d" % (k, tally[k]))
    print("    契约缺口(contract_pass=False) = %d" % gaps)

    # ── 阳性对照（⛔ 不许省）──────────────────────────────────────────
    catalogue_ok = all(r.get("catalogue_n", 0) > 0 for r in rows)
    has_a, has_non_a = answered > 0, answered < total
    print("    阳性对照: 目录非空=%s  有A=%s  有非A=%s"
          % (catalogue_ok, has_a, has_non_a))
    tiers = {}
    for r in rows:
        tiers[r.get("tier", "?")] = tiers.get(r.get("tier", "?"), 0) + 1
    print("    来源标记分布: %s" % json.dumps(tiers, ensure_ascii=False))

    if not (catalogue_ok and has_a and has_non_a):
        # 硬约束 4: 三态。「没量到」⛔ 不许折叠进「没问题」。
        print("\n⛔ rc=2 仪器没活着 —— 本次读数作废, ⛔ 不要拿它做前后对比")
        return 2
    return 0


if __name__ == "__main__":
    t0 = time.time()
    rc = asyncio.run(main())
    print("\nELAPSED=%.1fs  rc=%d" % (time.time() - t0, rc))
    raise SystemExit(rc)
