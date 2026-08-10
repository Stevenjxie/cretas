#!/usr/bin/env python
"""按**实测能力**给 (账号,模型) 排名 —— `_build_chain` 的排序输入。

## 为什么需要它

`_build_chain` 原本纯按免费额度到期日升序拼链(use-it-or-lose-it)。那条策略
省额度, 但它对「这个模型答不答得对」**一无所知** —— 到期日早的模型排在链头,
拿走全部流量, 哪怕它在真实 prompt 上契约不合格。

⚠️ 更要紧的是 `_expiry_of` 的 docstring 一直写着 "soonest-expiry-first
   **WITHIN a quality tier**" —— 而 `_build_chain` 是**全局**排序, 没有 tier
   这一层。意图在注释里, 约束不存在(与 2026-08-09 那次 "Runtime order is
   authoritative" 同一种病)。本脚本产出的就是那个缺失的 tier, 并且是量出来的。

## 判据(两条, 缺一不可)

1. **prompt 同源** —— 用生产自己的 `_build_t3_prompt`。另写一份评测 prompt
   等于在测「模型对我编的题的表现」, 与链上真正发生的事无关。
2. **评分同源** —— 用生产自己的 `_t3_contract_violation` 当判据, ⛔ 不写答案
   标准答案表。一份手写答案表就是第二张会漂移的镜子(本仓 32 处静默耦合的
   同一种形状), 而且「契约不合格」正是 call_chain 真正会拒绝的那件事 ——
   模型在链上唯一需要通过的考试就是它。

题目也不手挑: 取回归电池 `CASES` 里**每条链的首问 + 所有无上下文依赖的单问**
(chain is None), 去重后按出现顺序取前 N —— 全是真实用户问句, 且选法是算出来的。

## 延迟闸(为什么高分也可能沉底)

2026-08-09 实测: tencent/minimax-m2.7 在真实 T3 prompt 上 3/3 满分, 但 13.4s。
把它放链头只会把「答不出来」换成「等到超时」—— call_chain 的总预算被它吃光,
后面健康的模型一个都轮不上。所以中位延迟超过槽的单跳预算 → 直接沉底,
分数再高也不例外。

用法:
    python -m smartbi.scripts.llm_capability_rank            # REVIEW 槽全池
    python -m smartbi.scripts.llm_capability_rank --slot insights --n 6
    python -m smartbi.scripts.llm_capability_rank --emit-table  # 打印可粘贴的注册表

退出码: 0 = 正常产出; 1 = 池内**没有**任何模型达标(链会退化, 该告警)
"""
from __future__ import annotations

import argparse
import asyncio
import json
import statistics
import sys
import time
from typing import Any, Dict, List, Optional, Sequence, Tuple

import httpx

sys.path.insert(0, ".")

from common import llm_router as r  # noqa: E402

# 达标线: 契约通过率。低于它的模型不该排在任何健康模型前面。
_PASS_FLOOR = 0.5


def _query_set(limit: int) -> List[str]:
    """真实用户问句, 选法算出来而不是手挑。

    每条链的**首问**是自足的(链从那里开始, 没有上文); `chain is None` 的条目
    本来就是单问。两者合起来去重, 按 CASES 里的出现顺序取前 limit ——
    顺序固定, 所以不同模型、不同日期的分数可比。
    """
    from smartbi.scripts.restaurant_ai_eval import CASES

    picked: List[str] = []
    seen_chain = set()
    for case in CASES:
        chain = case.get("chain")
        if chain is not None:
            if chain in seen_chain:
                continue
            seen_chain.add(chain)
        q = case.get("q")
        if q and q not in picked:
            picked.append(q)
    return picked[:limit]


async def _score_one(
    client: httpx.AsyncClient,
    account: str,
    model: str,
    slot: r.SLOT,
    queries: Sequence[str],
) -> Dict[str, Any]:
    """对一个 (账号,模型) 跑完整题集, 返回 {passed, total, latencies, reasons}。"""
    from smartbi.gold.restaurant import restaurant_intent as ri

    base, key = r._provider_config(account)
    passed = 0
    latencies: List[float] = []
    reasons: List[str] = []

    for query in queries:
        prompt = ri._build_t3_prompt(query, None, None, (), None)
        normalized = r._normalize_payload_for_provider({
            "model": model,
            "messages": [
                {"role": "system",
                 "content": "你只输出JSON格式的意图解析结果，不输出任何其他文字。"},
                {"role": "user", "content": prompt},
            ],
            "temperature": 0,
            "max_tokens": ri._SEMANTIC_MAX_TOKENS,
        }, account)
        payload = r._apply_slot_params(slot, account, model, normalized)

        t0 = time.monotonic()
        try:
            resp = await client.post(
                base.rstrip("/") + "/chat/completions",
                json=payload,
                headers={"Authorization": f"Bearer {key}"},
                timeout=60.0,
            )
        except Exception as exc:  # noqa: BLE001 — 网络层任何异常都算不合格
            reasons.append(type(exc).__name__)
            continue
        latencies.append(time.monotonic() - t0)

        if not 200 <= resp.status_code < 300:
            reasons.append(
                "quota" if r._is_quota_exhausted(resp.status_code, resp.text)
                else f"http{resp.status_code}")
            continue
        try:
            content = (json.loads(resp.text)["choices"][0]["message"]
                       .get("content") or "")
        except Exception:  # noqa: BLE001
            reasons.append("unparseable_envelope")
            continue

        # ⛔ 判据就是生产的判据。content_validator=_t3_contract_violation 是
        #    call_chain 真正用来接受/拒绝一次应答的那个函数。
        violation = ri._t3_contract_violation(content)
        if violation is None:
            passed += 1
        else:
            reasons.append(violation)

    return {
        "account": account,
        "model": model,
        "passed": passed,
        "total": len(queries),
        "rate": passed / len(queries) if queries else 0.0,
        "p50": statistics.median(latencies) if latencies else None,
        "reasons": reasons,
    }


def _budget_for(slot: r.SLOT) -> float:
    """槽的单跳预算 —— 中位延迟超过它的模型沉底(见模块 docstring 的延迟闸)。"""
    from smartbi.gold.restaurant import restaurant_intent as ri

    if slot is r.SLOT.REVIEW:
        return float(ri._SEMANTIC_PROVIDER_TIMEOUT_SECONDS)
    return float(ri._T3_PROVIDER_TIMEOUT_SECONDS)


def _rank(rows: List[Dict[str, Any]], budget: float) -> List[Dict[str, Any]]:
    """排序键: (达标? 降序, 在预算内? 降序, 通过率降序, p50 升序, 到期日升序)。

    到期日**仍然是键**, 只是降到最后一位 —— 同能力同速度时依旧优先榨干快到期
    的免费额度(原策略的合理内核), 但它不再能把一个不合格的模型顶到链头。
    """
    def key(row: Dict[str, Any]) -> Tuple:
        p50 = row["p50"]
        within = p50 is not None and p50 <= budget
        return (
            -(1 if row["rate"] >= _PASS_FLOOR else 0),
            -(1 if within else 0),
            -row["rate"],
            p50 if p50 is not None else 9e9,
            r._expiry_of(row["account"], row["model"]),
        )

    return sorted(rows, key=key)


async def _run(slot: r.SLOT, limit: int) -> List[Dict[str, Any]]:
    queries = _query_set(limit)
    pool = list(dict.fromkeys(r._SLOT_POOLS[slot]))
    sem = asyncio.Semaphore(4)
    rows: List[Dict[str, Any]] = []

    async with httpx.AsyncClient() as client:
        async def one(pair: Tuple[str, str]) -> None:
            async with sem:
                rows.append(await _score_one(client, pair[0], pair[1], slot, queries))

        await asyncio.gather(*(one(p) for p in pool))
    return rows


def main(argv: Optional[Sequence[str]] = None) -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--slot", default="review")
    ap.add_argument("--n", type=int, default=6, help="题目数 (真实电池问句)")
    ap.add_argument("--emit-table", action="store_true",
                    help="额外打印可粘贴进 llm_router._CAPABILITY_RANK 的字面量")
    args = ap.parse_args(argv)

    slot = r.SLOT[args.slot.upper()]
    budget = _budget_for(slot)
    rows = asyncio.run(_run(slot, args.n))
    ranked = _rank(rows, budget)

    print(f"[capability] slot={slot.name} 题目={args.n} 单跳预算={budget}s "
          f"达标线={_PASS_FLOOR:.0%}")
    for i, row in enumerate(ranked):
        p50 = f"{row['p50']:.1f}s" if row["p50"] is not None else "  n/a"
        flag = "" if row["rate"] >= _PASS_FLOOR else "  ⚠不达标"
        if row["p50"] is not None and row["p50"] > budget:
            flag += "  ⚠超预算"
        why = ""
        if row["reasons"]:
            why = "  " + ",".join(sorted(set(row["reasons"]))[:3])
        print(f"  {i:2d}. {row['account']:>10}/{row['model']:<32} "
              f"{row['passed']}/{row['total']}  {p50}{flag}{why}")

    qualified = [x for x in ranked
                 if x["rate"] >= _PASS_FLOOR
                 and x["p50"] is not None and x["p50"] <= budget]
    print(f"\n达标且在预算内: {len(qualified)}/{len(ranked)}")

    if args.emit_table:
        print("\n# ── 粘贴进 llm_router._CAPABILITY_RANK ──")
        for i, row in enumerate(ranked):
            # ⚠️ p50 为 None(全程网络失败)的条目**照样要打印** —— 少一行会让
            #    注册表缺项, 而缺项在 _capability_rank_of 里是「排最后」的静默
            #    默认值, 看不出是没测到还是测得差。
            lat = f"{row['p50']:.1f}s" if row["p50"] is not None else "n/a"
            print(f'    ("{row["account"]}", "{row["model"]}"): {i},'
                  f'   # {row["passed"]}/{row["total"]}  {lat}')

    # 池里一个达标的都没有 = 链整体退化, 属于该叫醒人的信号。
    return 0 if qualified else 1


if __name__ == "__main__":
    raise SystemExit(main())
