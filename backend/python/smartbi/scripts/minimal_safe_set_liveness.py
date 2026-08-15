"""T7-b3 · 逐条实测 `_MINIMAL_SAFE_SET` 的**存活性**。⛔ 只读, 不改集合内容。

## 为什么这件事必须做

`_refuse_reason`: registry 超龄后, **不在这个集合里的一律被拒**。
`_REGISTRY_AUDIT_DATE = 2026-08-13` + `_REGISTRY_MAX_AGE_DAYS = 21`
⇒ **2026-09-04 起, 这个集合就是全世界**。

而它自己的注释里记着同一个陷阱**已经踩过两次**:

    「旧集合 13 个条目里 8 个已实测死亡 —— fail-safe 退守的目标本身是死的」
    「上一版(08-09 建)的 9 个条目今天实测 6 个已死」

🔴 它**只在 registry 超龄时才被用到**, 所以它坏了**不会有任何日常信号报出来**。
   ⇒ 存活性必须主动量, 而且要和主池一起量, 每轮都要。

## ⛔ 不重写判据

直接复用 `llm_capability_rank._score_one` —— 它用的就是生产接受/拒绝一次应答的
那个函数 (`_t3_contract_violation`)。⛔ 不自己"大致等价地"重写一遍判定
(本仓前科: 探针自己造出过假象, 连续 13 次诊断被读数推翻)。

## 硬约束 9: 主读数是「哪几条死了」—— 必须配一个「一定会活」的对照

阳性对照 = `aistore/DeepSeek-V4-Flash-A`: 2026-08-15 同日实测 1.71s / 契约通过。
**它要是也报死, 那是仪器坏了, 不是 8 条全死。** ⇒ 那种情况整轮读数作废(rc=2)。

## 跑法

    cd <部署树>/backend/python
    python -X utf8 -u -m smartbi.scripts.minimal_safe_set_liveness
"""
import asyncio
import sys

import httpx

from common import llm_router as R
from smartbi.scripts.llm_capability_rank import _query_set, _score_one

#: 阳性对照 —— 同日已实测活着。它死 = 仪器坏。
POSITIVE_CONTROL = ("aistore", "DeepSeek-V4-Flash-A")

N_QUERIES = 2          # ⛔ 只为判「活不活」, 不是重跑能力排名


async def main() -> int:
    entries = sorted(R._MINIMAL_SAFE_SET)
    queries = _query_set(N_QUERIES)
    slot = R.SLOT.REVIEW

    print(f"_REGISTRY_AUDIT_DATE   = {R._REGISTRY_AUDIT_DATE}")
    print(f"_REGISTRY_MAX_AGE_DAYS = {R._REGISTRY_MAX_AGE_DAYS}")
    print(f"⇒ 2026-09-04 起, 下面这 {len(entries)} 条就是全世界\n")
    print(f"题数 {len(queries)} / 条, 共 {len(entries) * len(queries)} 次调用\n")

    rows = []
    async with httpx.AsyncClient() as client:
        for account, model in entries:
            base, key = R._provider_config(account)
            if not key:
                rows.append({"account": account, "model": model, "passed": 0,
                             "total": len(queries), "p50": None,
                             "reasons": ["NO_API_KEY"]})
                print(f"  {account}/{model}: ⛔ 没有 api_key")
                continue
            row = await _score_one(client, account, model, slot, queries)
            rows.append(row)
            p50 = f"{row['p50']:.2f}s" if row["p50"] is not None else "n/a"
            print(f"  {account}/{model}: {row['passed']}/{row['total']} "
                  f"p50={p50} reasons={row['reasons'][:3]}")

    print("\n" + "=" * 74)
    by_pair = {(r["account"], r["model"]): r for r in rows}
    pc = by_pair.get(POSITIVE_CONTROL)
    pc_ok = bool(pc and pc["passed"] > 0)
    print(f"阳性对照 {POSITIVE_CONTROL[0]}/{POSITIVE_CONTROL[1]}: "
          f"{'✅ 活着 ⇒ 仪器可信' if pc_ok else '⛔ 也报死 ⇒ 仪器坏了'}")
    if not pc_ok:
        print("⛔ 阳性对照未通过 —— 本次读数作废, ⛔ 不许据此说集合里有几条死了。")
        return 2

    dead = [r for r in rows if r["passed"] == 0]
    weak = [r for r in rows if 0 < r["passed"] < r["total"]]
    alive = [r for r in rows if r["passed"] == r["total"]]
    budget = R._SLOT_HOP_BUDGET_SECONDS
    slow = [r for r in alive if r["p50"] is not None and r["p50"] > budget]

    print(f"\n全过 {len(alive)} / 部分 {len(weak)} / 全败 {len(dead)}  (共 {len(rows)})")
    print(f"\n🔴 死的(单列, ⛔ 不混进汇总):")
    for r in dead or []:
        print(f"    {r['account']}/{r['model']}  reasons={r['reasons'][:4]}")
    if not dead:
        print("    (无)")
    print(f"\n⚠️ 活着但中位延迟超单跳预算 {budget}s 的:")
    for r in slow or []:
        print(f"    {r['account']}/{r['model']}  p50={r['p50']:.2f}s")
    if not slow:
        print("    (无)")
    print("=" * 74)
    print("⚠️ 这是**今天**的存活性。集合的价值全在 registry 超龄之后, "
          "而那时没有任何日常信号 ⇒ 这个读数要定期重量。")
    return 1 if dead else 0


if __name__ == "__main__":
    sys.exit(asyncio.run(main()))
