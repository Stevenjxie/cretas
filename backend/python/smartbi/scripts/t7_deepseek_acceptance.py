"""T7 验收 ②③④ —— 真实调用层 + 悬崖模拟 + 付费调用记账。

⛔ NOT_DEPLOYED。⛔ 不改任何生产配置。⛔ key 只从环境读, 绝不落文件。

跑法(key 只在你的 shell 里, ⛔ 不写进任何脚本/文件/PR):
    export LLM_DEEPSEEK_API_KEY=$(cat /root/.deepseek_probe_key)
    cd <部署树>/backend/python
    python -X utf8 -u /tmp/t7_deepseek_acceptance.py

## ② 真实调用层

**走路由器**发一次(⛔ 不许绕过路由器直打端点) —— 绕过去就测不到
「环境变量名写错 ⇒ 空 key ⇒ `if not api_key: continue` 静默跳过」这个缺陷,
而那正是**唯一**能抓到它的一层(单测和 `_refuse_reason` 都看不见)。
用 `call_chain(chain=["deepseek"])` 的账号过滤器把这一次钉死在 deepseek 上。

## ③ 悬崖模拟 —— 推 `today`, ⛔ 不是 monkeypatch 到期日

monkeypatch 到期日**只触发 expired 一道闸**, 而 `_registry_stale` 仍按真实今天
算 ⇒ 会一路绿到 9-13, 把 registry_stale 那个缺陷整个盖住。推 `_today` 能让两道
闸按真实顺序发生。

三个时点:
    2026-09-05  仅 stale (aistore 还没过期)
    2026-09-14  stale + aistore expired  ← 真正的悬崖
    2026-12-01  deepseek 复审日之后

⚠️ 「可用 N/M」不等于「N 个能答」(tencent 402 / ark 429 在闸看来都是"可用")。
   ⇒ 断言必须是**跑通一次**, 不是计数。
"""
import asyncio
import logging
import os
import sys
import time

from common import llm_router as R

SLOTS = ["chat", "insights", "chart", "mapper", "review"]


def _real_t3_payload():
    """⛔ 必须用**真实 T3 prompt**, 不能用玩具 prompt。

    🔴 第一版用的是 `{"user": '返回 {"ok": true}'}` + max_tokens=64。三个时点
       全绿, 而且 2026-12-01 那个时点 `tencent/minimax-m2.7` 报 1.28~3.06s ——
       **与同日在真实 T3 prompt 上实测的 p50=13.37s 差一个数量级**。
       玩具 prompt 量出来的是「端点通不通」, 不是「它在生产负载下来不来得及」。
       ⇒ 变异对照(去掉 deepseek)在玩具 prompt 下**根本红不了**, 整条验收变成恒真式。
    """
    from smartbi.gold.restaurant import restaurant_intent as ri
    prompt = ri._build_t3_prompt("上个月各门店营收怎么样", None, None, (), None)
    return {
        "messages": [
            {"role": "system",
             "content": "你只输出JSON格式的意图解析结果，不输出任何其他文字。"},
            {"role": "user", "content": prompt},
        ],
        "temperature": 0,
        "max_tokens": ri._SEMANTIC_MAX_TOKENS,
    }


PROMPT = None            # 在 main() 里用真实 T3 prompt 填充

PAID_ACCOUNTS = {"deepseek"}
paid_calls = []          # ④ 付费调用逐次记账


class _Capture(logging.Handler):
    """抓 `[llm_router] slot=X OK via account/model` —— 谁真正应答了。"""

    def __init__(self):
        super().__init__()
        self.ok_via = []

    def emit(self, record):
        msg = record.getMessage()
        if "OK via" in msg:
            self.ok_via.append(msg.rsplit("OK via", 1)[1].strip())


async def one_call(slot, chain=None, label=""):
    cap = _Capture()
    lg = logging.getLogger("common.llm_router")
    lg.addHandler(cap)
    lg.setLevel(logging.INFO)
    t0 = time.monotonic()
    try:
        await R.call_chain(slot, dict(PROMPT), chain=chain,
                           timeout=R._SLOT_HOP_BUDGET_SECONDS,
                           total_timeout=12.0)
        served = cap.ok_via[-1] if cap.ok_via else "<未知>"
        err = None
    except Exception as exc:  # noqa: BLE001
        served, err = None, f"{type(exc).__name__}: {str(exc)[:160]}"
    finally:
        lg.removeHandler(cap)
    elapsed = time.monotonic() - t0
    if served and served.split("/")[0] in PAID_ACCOUNTS:
        paid_calls.append((label or slot.value, served, round(elapsed, 2)))
    return served, elapsed, err


async def main():
    if not os.getenv("LLM_DEEPSEEK_API_KEY"):
        print("⛔ LLM_DEEPSEEK_API_KEY 没设 —— 本轮作废。")
        print("   export LLM_DEEPSEEK_API_KEY=$(cat /root/.deepseek_probe_key)")
        return 2
    global PROMPT
    PROMPT = _real_t3_payload()
    print(f"真实 T3 prompt: {len(PROMPT['messages'][1]['content'])} 字符, "
          f"max_tokens={PROMPT['max_tokens']}")

    print("=" * 78)
    print("② 真实调用层 —— 走路由器, 账号过滤钉死在 deepseek")
    print("=" * 78)
    served, elapsed, err = await one_call(R.SLOT.REVIEW, chain=["deepseek"],
                                          label="②-review")
    print(f"  served={served!r}  {elapsed:.2f}s  err={err}")
    if not served or not served.startswith("deepseek/"):
        print("⛔ 没有走到 deepseek —— 环境变量名/密钥/池 有一处不对, 本轮作废。")
        print("   (空 key 会被 `if not api_key: continue` **静默跳过**, "
              "单测与 _refuse_reason 都看不见 —— 这一层就是为抓它而存在的)")
        return 2
    print("  ✅ 路由器确实把请求发到了 deepseek 并成功")

    print("\n" + "=" * 78)
    print("③ 悬崖模拟 —— 推 today (⛔ 不是 monkeypatch 到期日)")
    print("=" * 78)
    import datetime
    real_today = R._today
    failures = []
    for day in (datetime.date(2026, 9, 5),
                datetime.date(2026, 9, 14),
                datetime.date(2026, 12, 1)):
        R._today = lambda d=day: d
        R._CB_FAILURES.clear(); R._CB_LAST_FAIL.clear()
        R._QUOTA_EXHAUSTED_UNTIL.clear()
        print(f"\n--- today={day}  registry_stale={R._registry_stale(day)} ---")
        for slot in R.SLOT:
            if slot.value not in SLOTS:
                continue
            allowed = [f"{a}/{m}" for a, m in R.SLOT_MODELS[slot]
                       if R._refuse_reason(a, m, day) is None]
            served, elapsed, err = await one_call(slot, label=f"{day}/{slot.value}")
            budget_ok = served is not None and elapsed <= R._SLOT_HOP_BUDGET_SECONDS
            mark = "✅" if served else "⛔"
            print(f"  {slot.value:<9} 闸放行 {len(allowed)} 条; 实际应答={served!r} "
                  f"{elapsed:.2f}s {mark}"
                  + ("" if budget_ok or not served else "  ⚠️ 超 6.0s 单跳预算"))
            if err:
                print(f"             err={err}")
            if not served:
                failures.append((str(day), slot.value, err))
    R._today = real_today

    print("\n" + "=" * 78)
    print("③ 变异对照 —— 把 deepseek 从 _SAFE_MODELS 去掉, 重跑 2026-09-14")
    print("=" * 78)
    removed = {k: R._SAFE_MODELS.pop(k) for k in list(R._SAFE_MODELS)
               if k[0] == "deepseek"}
    assert removed, "变异没生效 —— 下面的对照无意义(形态 C″)"
    print(f"  已移除 {sorted(removed)}")
    day = datetime.date(2026, 9, 14)
    R._today = lambda d=day: d
    R._CB_FAILURES.clear()
    R._CB_LAST_FAIL.clear()
    R._QUOTA_EXHAUSTED_UNTIL.clear()
    mut = []
    for slot in R.SLOT:
        if slot.value not in SLOTS:
            continue
        served, elapsed, err = await one_call(slot, label=f"MUT/{slot.value}")
        over = served is not None and elapsed > R._SLOT_HOP_BUDGET_SECONDS
        print(f"  {slot.value:<9} 应答={served!r} {elapsed:.2f}s"
              + ("  ⚠️ 超 6.0s 单跳预算" if over else "")
              + (f"  err={err}" if err else ""))
        mut.append((slot.value, served, round(elapsed, 2), over, err))
    R._SAFE_MODELS.update(removed)
    R._today = real_today
    degraded = [m for m in mut if m[1] is None or m[3]]
    print(f"\n  变异下「答不出或超单跳预算」的槽: {len(degraded)}/{len(mut)}")
    if not degraded:
        print("  🔴 变异没红 —— 这轮验收对「deepseek 是否必要」没有区分力，"
              "⛔ 不许拿它当「上线就安全」的证据")
    else:
        print("  ✅ 变异红了 ⇒ 上面那 5 个 ✅ 确实是 deepseek 顶住的")

    print("\n" + "=" * 78)
    print("④ 付费调用记账(逐次)")
    for i, row in enumerate(paid_calls, 1):
        print(f"  {i}. {row}")
    print(f"  合计付费调用 {len(paid_calls)} 次")
    print("=" * 78)
    if failures:
        print(f"⛔ 有 {len(failures)} 个 (时点, 槽) 没有跑通:")
        for row in failures:
            print(f"    {row}")
        return 1
    print("✅ 三个时点 × 五个槽全部**跑通**(不是「链里有它」)")
    return 0


if __name__ == "__main__":
    sys.exit(asyncio.run(main()))
