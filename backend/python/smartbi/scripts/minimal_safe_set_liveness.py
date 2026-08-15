"""逐条实测**白名单条目的存活性**。⛔ 只读, 不改任何表。

    --scope minimal   `_MINIMAL_SAFE_SET`(默认, 8 条)
    --scope all       `_SAFE_MODELS` 全部(T8)

## 为什么这件事必须做

`_refuse_reason`: registry 超龄后, **不在 `_MINIMAL_SAFE_SET` 里的一律被拒**。
`_REGISTRY_AUDIT_DATE = 2026-08-13` + `_REGISTRY_MAX_AGE_DAYS = 21`
⇒ **2026-09-04 起, 那个集合就是全世界**。

而它的注释里记着同一个陷阱**已经踩过两次**:
    「旧集合 13 个条目里 8 个已实测死亡 —— fail-safe 退守的目标本身是死的」
    「上一版(08-09 建)的 9 个条目今天实测 6 个已死」
🔴 它**只在 registry 超龄时才被用到**, 所以它坏了**不会有任何日常信号**。

## ⛔ 不重写判据

复用 `llm_capability_rank._score_one` —— 它用的就是生产接受/拒绝一次应答的那个
函数 `_t3_contract_violation`。⛔ 不自己"大致等价地"重写一遍。

## 🔴 两条负载判据(都是付过学费的)

1. **必须用真实 T3 prompt**(9633 字符)。玩具 prompt 下 `minimax` 看着正常,
   真实 prompt 下 p50=13.37s —— 差一个数量级。
   ▎**验收用的负载必须是被守护的那个负载。**
   (`_score_one` 内部就是用 `_build_t3_prompt` 建的, 这一条由复用自动满足。)

2. **必须用它实际所在槽的 profile**, ⛔ 不许一律强制关思考。
   `SLOT.REASONING` 的 profile 是 `{}`(**故意不关思考**), 它的模型清一色
   `_THINKING_ONLY`; 拿关思考去量它们, 会把「按契约正常」误判成「坏了」。
   ⇒ 全仓只有 **3 种** profile, 所以按 **profile** 量而不是按槽量(等价且省 2/3 调用):
       A `{'enable_thinking': False}`                    chat/simple_text/insights/review
       B `{... json:True, temperature:0, seed:1234}`     chart/mapper   ← 严格契约
       C `{}`                                            reasoning      ← 不关思考

## 硬约束 9 —— 主读数是「哪几条死了」, 必须配一个「一定会活」的对照

阳性对照 `aistore/DeepSeek-V4-Flash-A`(同日已实测活着)。
**它要是也报死, 那是仪器坏了, 不是全都死了。** ⇒ rc=2, 整轮作废。

## 三态(硬约束 4)

    rc=0 全活   rc=1 有死条目(读数有效)   rc=2 没量到(阳性对照未通过)
"""
import argparse
import asyncio
import sys
from collections import defaultdict

import httpx

from common import llm_router as R
from smartbi.scripts.llm_capability_rank import _query_set, _score_one

POSITIVE_CONTROL = ("aistore", "DeepSeek-V4-Flash-A")
N_QUERIES = 2

#: profile 指纹 -> (代表槽, 人读的名字)。⛔ 代表槽只用来取 profile, 与槽语义无关。
PROFILE_REPS = [
    (R.SLOT.REVIEW, "A 关思考"),
    (R.SLOT.CHART, "B 关思考+严格JSON"),
    (R.SLOT.REASONING, "C 不关思考"),
]


def _fingerprint(slot):
    return tuple(sorted((R._SLOT_PARAMS.get(slot) or {}).items()))


def build_units():
    """(account, model) -> {profile 指纹: (代表槽, 名字, [槽名...])}"""
    rep_by_fp = {}
    for slot, name in PROFILE_REPS:
        rep_by_fp[_fingerprint(slot)] = (slot, name)

    units = defaultdict(dict)
    slots_of = defaultdict(list)
    for slot, chain in R.SLOT_MODELS.items():
        fp = _fingerprint(slot)
        rep = rep_by_fp.get(fp)
        if rep is None:            # 出现新 profile 就说出来, ⛔ 不静默归并
            rep = (slot, f"? 未登记 profile {R._SLOT_PARAMS.get(slot)}")
            rep_by_fp[fp] = rep
        for pair in chain:
            units[pair][fp] = rep
            slots_of[pair].append(slot.value)
    return units, slots_of


async def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--scope", choices=("minimal", "all"), default="minimal")
    args = ap.parse_args()

    entries = sorted(R._MINIMAL_SAFE_SET if args.scope == "minimal"
                     else R._SAFE_MODELS)
    units, slots_of = build_units()
    queries = _query_set(N_QUERIES)
    budget = R._SLOT_HOP_BUDGET_SECONDS

    orphans = [p for p in entries if p not in units]
    measured = [p for p in entries if p in units]
    n_calls = sum(len(units[p]) for p in measured) * len(queries)

    print(f"scope={args.scope}  白名单 {len(entries)} 条")
    print(f"_REGISTRY_AUDIT_DATE={R._REGISTRY_AUDIT_DATE} "
          f"+{R._REGISTRY_MAX_AGE_DAYS}d ⇒ 2026-09-04 起收缩到 _MINIMAL_SAFE_SET")
    print(f"在池/链上的 {len(measured)} 条, 共 {n_calls} 次调用 "
          f"({len(queries)} 题 × 每条所在的每种 profile)")
    print(f"⛔ 不在任何池/链上的 {len(orphans)} 条 —— **纯死重, 与存活无关**, 不调用\n")

    rows = []
    async with httpx.AsyncClient() as client:
        for pair in measured:
            account, model = pair
            base, key = R._provider_config(account)
            refuse = R._refuse_reason(account, model)
            for fp, (rep_slot, pname) in units[pair].items():
                if not key:
                    rows.append({"account": account, "model": model,
                                 "profile": pname, "passed": 0,
                                 "total": len(queries), "p50": None,
                                 "reasons": ["NO_API_KEY"], "refuse": refuse})
                    print(f"  {account}/{model} [{pname}]: ⛔ 没有 api_key")
                    continue
                r = await _score_one(client, account, model, rep_slot, queries)
                r["profile"] = pname
                r["refuse"] = refuse
                rows.append(r)
                p50 = f"{r['p50']:.2f}s" if r["p50"] is not None else "  n/a"
                over = (r["p50"] is not None and r["p50"] > budget)
                print(f"  {account}/{model} [{pname}]: {r['passed']}/{r['total']} "
                      f"p50={p50}{'  ⚠️超预算' if over else ''} "
                      f"refuse={refuse!r} reasons={r['reasons'][:2]}")

    # ── 阳性对照 ──────────────────────────────────────────────────────
    pc = [r for r in rows if (r["account"], r["model"]) == POSITIVE_CONTROL]
    pc_ok = any(r["passed"] > 0 for r in pc)
    print("\n" + "=" * 76)
    print(f"阳性对照 {POSITIVE_CONTROL[0]}/{POSITIVE_CONTROL[1]}: "
          f"{'✅ 活着 ⇒ 仪器可信' if pc_ok else '⛔ 也报死 ⇒ 仪器坏了'}")
    if not pc_ok:
        print("⛔ 本次读数作废, ⛔ 不许读成「全都死了」。")
        return 2

    # ── 按 (账号,模型) 汇总: 只要有一种 profile 能过就算活 ────────────
    by_pair = defaultdict(list)
    for r in rows:
        by_pair[(r["account"], r["model"])].append(r)
    dead = [p for p, rs in by_pair.items() if all(r["passed"] == 0 for r in rs)]
    partial = [p for p, rs in by_pair.items()
               if any(r["passed"] == 0 for r in rs) and p not in dead]

    print(f"\n在池条目 {len(by_pair)}: 全活 "
          f"{len(by_pair) - len(dead) - len(partial)} / 部分 {len(partial)} / 全死 {len(dead)}")
    print("\n🔴 全死(单列):")
    for a, m in sorted(dead):
        why = sorted({x for r in by_pair[(a, m)] for x in r["reasons"]})
        print(f"    {a}/{m}  slots={sorted(set(slots_of[(a, m)]))}  reasons={why[:3]}")
    print("    (无)" if not dead else "")
    print("⚠️ 部分 profile 不过(⇒ 那个槽的契约它满足不了):")
    for a, m in sorted(partial):
        for r in by_pair[(a, m)]:
            if r["passed"] == 0:
                print(f"    {a}/{m} [{r['profile']}] reasons={r['reasons'][:3]}")
    print("    (无)" if not partial else "")

    print("\n=== 按账号: 还剩几条活的(在池条目) ===")
    acct = defaultdict(lambda: [0, 0])
    for (a, m), rs in by_pair.items():
        acct[a][1] += 1
        if any(r["passed"] > 0 for r in rs):
            acct[a][0] += 1
    for a in sorted(acct):
        alive, tot = acct[a]
        print(f"  {a:<18} {alive}/{tot} 活")

    print(f"\n=== ⛔ 纯死重: 在白名单但不在任何池/链 ({len(orphans)} 条) ===")
    for a, m in orphans:
        print(f"    {a}/{m}")
    print("=" * 76)
    return 1 if dead or partial else 0


if __name__ == "__main__":
    sys.exit(asyncio.run(main()))
