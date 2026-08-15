"""A3 + A6 · 账号收敛后的验收。⛔ 只读, 不改任何配置。

跑法(key 只在 shell 里, ⛔ 不写进任何文件):
    export LLM_DEEPSEEK_API_KEY=$(cat /root/.deepseek_probe_key)
    cd <部署树>/backend/python && python -X utf8 -u /tmp/a36_provider_acceptance.py

## A3 · 补两格没量的

  · deepseek 在 **profile B(严格 JSON)** 下 —— chart/mapper 靠它
  · aistore 在 **profile C(思考开着)** + **真实 T3 prompt**
    ⚠️ 之前那 8.03–8.95s 是**玩具 prompt** 量的, 本仓已证明玩具负载读数不作数。

## A6 · 悬崖模拟

  · **推 today**(2026-09-05 / 09-14 / 12-01), ⛔ 不是 monkeypatch 到期日 ——
    后者只触发 expired 一道闸, `_registry_stale` 仍按真实今天算。
  · 每个时点断言链首**能真正应答**(跑通一次), ⛔ 不是「链里有它」。
    ⚠️「可用 N/M」≠「N 个能答」—— 计费闸看不见配额状态。
  · 变异: 把 deepseek 从 `_SAFE_MODELS` 去掉重跑。

## ⚠️ 预算按槽取, ⛔ 不是一律 6.0s

`SLOT.REASONING` 的 profile 是 `{}`(思考开着), 它唯一的产品调用点
`detector.py:1496` 不传 timeout ⇒ `call_chain` 默认 **30s**。
拿 6.0s 判它会把「按契约正常」误判成「超预算」。
"""
import asyncio
import datetime
import logging
import os
import sys
import time

from common import llm_router as R

REASONING_BUDGET = 30.0
paid = []


def budget_for(slot):
    return REASONING_BUDGET if slot is R.SLOT.REASONING else R._SLOT_HOP_BUDGET_SECONDS


def real_t3_payload():
    """⛔ 真实 T3 prompt(9633 字符), 不是玩具 —— 玩具负载让验收变成恒真式。"""
    from smartbi.gold.restaurant import restaurant_intent as ri
    return {
        "messages": [
            {"role": "system",
             "content": "你只输出JSON格式的意图解析结果，不输出任何其他文字。"},
            {"role": "user",
             "content": ri._build_t3_prompt("上个月各门店营收怎么样", None, None, (), None)},
        ],
        "temperature": 0,
        "max_tokens": ri._SEMANTIC_MAX_TOKENS,
    }


class _Cap(logging.Handler):
    def __init__(self):
        super().__init__()
        self.ok = []

    def emit(self, rec):
        m = rec.getMessage()
        if "OK via" in m:
            self.ok.append(m.rsplit("OK via", 1)[1].strip())


async def one(slot, payload, chain=None, label=""):
    cap = _Cap()
    lg = logging.getLogger("common.llm_router")
    lg.addHandler(cap)
    lg.setLevel(logging.INFO)
    t0 = time.monotonic()
    try:
        await R.call_chain(slot, dict(payload), chain=chain,
                           timeout=budget_for(slot), total_timeout=30.0)
        served, err = (cap.ok[-1] if cap.ok else "<未知>"), None
    except Exception as exc:                                   # noqa: BLE001
        served, err = None, f"{type(exc).__name__}: {str(exc)[:150]}"
    finally:
        lg.removeHandler(cap)
    el = time.monotonic() - t0
    if served and served.split("/")[0] == "deepseek":
        paid.append((label or slot.value, served, round(el, 2)))
    return served, el, err


async def main():
    if not os.getenv("LLM_DEEPSEEK_API_KEY"):
        print("⛔ LLM_DEEPSEEK_API_KEY 没设 —— 本轮作废")
        return 2
    payload = real_t3_payload()
    print(f"真实 T3 prompt {len(payload['messages'][1]['content'])} 字符, "
          f"max_tokens={payload['max_tokens']}\n")

    # ── A3 ────────────────────────────────────────────────────────────
    print("=" * 74)
    print("A3 · 补两格")
    print("=" * 74)
    a3 = []
    for slot, acct, label in ((R.SLOT.CHART, "deepseek", "deepseek × profile B(严格JSON)"),
                              (R.SLOT.REASONING, "aistore", "aistore × profile C(思考开着)")):
        prof = R._SLOT_PARAMS.get(slot)
        served, el, err = await one(slot, payload, chain=[acct], label=f"A3/{acct}")
        ok = served is not None and served.startswith(acct + "/")
        within = el <= budget_for(slot)
        print(f"  {label}")
        print(f"    profile={prof}")
        print(f"    served={served!r}  {el:.2f}s  预算={budget_for(slot)}s  "
              f"{'✅ 在预算内' if within else '⚠️ 超预算'}  err={err}")
        a3.append(ok)

    # ── A6 悬崖模拟 ───────────────────────────────────────────────────
    print("\n" + "=" * 74)
    print("A6 · 悬崖模拟 —— 推 today (⛔ 不是 monkeypatch 到期日)")
    print("=" * 74)
    real_today = R._today
    fails = []
    for day in (datetime.date(2026, 9, 5), datetime.date(2026, 9, 14),
                datetime.date(2026, 12, 1)):
        R._today = lambda d=day: d
        R._CB_FAILURES.clear(); R._CB_LAST_FAIL.clear()
        R._QUOTA_EXHAUSTED_UNTIL.clear()
        print(f"\n--- today={day}  registry_stale={R._registry_stale(day)} ---")
        for slot in R.SLOT:
            chain = R.SLOT_MODELS.get(slot, [])
            if not chain:
                print(f"  {slot.value:<12} (空链, 已裁定)")
                continue
            allowed = [f"{a}/{m}" for a, m in chain
                       if R._refuse_reason(a, m, day) is None]
            served, el, err = await one(slot, payload, label=f"{day}/{slot.value}")
            within = served is not None and el <= budget_for(slot)
            print(f"  {slot.value:<12} 闸放行 {len(allowed)}/{len(chain)}  "
                  f"应答={served!r} {el:.2f}s (预算 {budget_for(slot)}s)"
                  f"{'' if within or not served else '  ⚠️超预算'}"
                  f"{'  ⛔ ' + err if err else ''}")
            if not served:
                fails.append((str(day), slot.value, err))
    R._today = real_today

    # ── 变异 ──────────────────────────────────────────────────────────
    print("\n" + "=" * 74)
    print("变异 · 把 deepseek 从 _SAFE_MODELS 去掉, 重跑 2026-09-14")
    print("=" * 74)
    removed = {k: R._SAFE_MODELS.pop(k) for k in list(R._SAFE_MODELS) if k[0] == "deepseek"}
    assert removed, "变异没生效 —— 下面的对照无意义(形态 C″)"
    print(f"  已移除 {sorted(removed)}")
    day = datetime.date(2026, 9, 14)
    R._today = lambda d=day: d
    R._CB_FAILURES.clear(); R._CB_LAST_FAIL.clear(); R._QUOTA_EXHAUSTED_UNTIL.clear()
    degraded = []
    for slot in R.SLOT:
        if not R.SLOT_MODELS.get(slot):
            continue
        served, el, err = await one(slot, payload, label=f"MUT/{slot.value}")
        over = served is not None and el > budget_for(slot)
        print(f"  {slot.value:<12} 应答={served!r} {el:.2f}s"
              f"{'  ⚠️超预算' if over else ''}{'  ⛔ ' + err if err else ''}")
        if served is None or over:
            degraded.append(slot.value)
    R._SAFE_MODELS.update(removed)
    R._today = real_today
    print(f"\n  变异下「答不出或超预算」的槽: {len(degraded)} —— {degraded}")
    print("  " + ("✅ 变异红了 ⇒ 上面那些 ✅ 确实是 deepseek 顶住的"
                  if degraded else
                  "🔴 变异没红 ⇒ 本轮验收对「deepseek 是否必要」没有区分力"))

    print("\n" + "=" * 74)
    print("付费调用记账(逐次)")
    for i, r in enumerate(paid, 1):
        print(f"  {i}. {r}")
    print(f"  合计 {len(paid)} 次")
    print("=" * 74)
    if fails:
        print(f"⛔ {len(fails)} 个 (时点,槽) 没跑通: {fails}")
        return 1
    print("✅ 三时点 × 全部非空槽**跑通**(不是「链里有它」)")
    return 0 if all(a3) else 1


if __name__ == "__main__":
    sys.exit(asyncio.run(main()))
