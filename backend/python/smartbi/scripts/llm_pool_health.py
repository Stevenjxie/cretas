#!/usr/bin/env python3
"""REVIEW 槽还剩几个能真正被调用的供应商。

🔴 存在的理由（2026-08-09 复盘）：REVIEW 链 20 个 (账号,模型) 全部被
   配额耗尽/熔断/毒丸吃掉，T3 规划器整层 fail-closed，而 **66.5% 的餐饮
   提问走这一层**。日志显示这个状态从 **2026-08-03 起断续出现，已 6 天**，
   期间没有任何告警 —— 直到有人手工去看日志才发现。

⛔ 用**路由器自己的三道闸**判定，不另写一套：
   `_refuse_reason`（白名单/到期/付费黑名单）、`_cb_should_skip`（熔断）、
   `_quota_should_skip`（配额退避）。自己写一套判断迟早会与真实行为漂移，
   那时告警说「池子健康」而用户在收 fail-closed 文案 —— 比没有告警更糟。

⚠️ 这里量的是「够不够得到」，不是「答得对不对」。一个够得到但输出不合契约的
   模型（例如 confidence 恒负的那颗毒丸）在这里算健康 —— 合约质量由
   `restaurant_ai_eval` 负责，两者分工不同，别把它们混成一个数。

用法（capability-watch.sh 每 15 分钟调一次）：
    python -m smartbi.scripts.llm_pool_health [--slot review] [--min 2]
    exit 0 = 可用数 >= --min；exit 1 = 低于阈值（告警）
"""
from __future__ import annotations

import argparse
import sys


def main() -> int:
    parser = argparse.ArgumentParser(description="LLM 供应商池健康检查")
    parser.add_argument("--slot", default="review",
                        help="要检查的槽位名（默认 review —— 餐饮 T3 规划器用它）")
    parser.add_argument("--min", type=int, default=2,
                        help="低于这个可用数就告警。默认 2：只剩 1 个时"
                             "任何一次熔断都会让整层归零，那时再报就晚了")
    args = parser.parse_args()

    from common import llm_router as R

    try:
        slot = getattr(R.SLOT, args.slot.upper())
    except AttributeError:
        print(f"LLM_POOL ALERT slot={args.slot} 不存在（可选: "
              f"{[s.name.lower() for s in R.SLOT]}）")
        return 1

    chain = R.SLOT_MODELS.get(slot) or []
    usable, blocked = [], []
    for account, model in chain:
        key = f"{account}/{model}"
        reason = R._refuse_reason(account, model)
        if reason:
            blocked.append((key, reason))
        elif R._cb_should_skip(key):
            blocked.append((key, "cb_open"))
        elif R._quota_should_skip(key):
            blocked.append((key, "quota_skip"))
        elif not R._provider_config(account)[1]:
            blocked.append((key, "no_key"))
        else:
            usable.append(key)

    state = "OK" if len(usable) >= args.min else "ALERT"
    print(f"LLM_POOL {state} slot={args.slot} usable={len(usable)}/{len(chain)} "
          f"min={args.min} usable_list={','.join(usable) or '-'}")
    if state == "ALERT":
        # 把**为什么**一起打出来: 上一次这个状态持续了 6 天,
        # 只报「不健康」而不报「因为配额还是因为熔断」会让人再查一遍日志。
        from collections import Counter
        why = Counter(reason for _k, reason in blocked)
        print("  阻塞原因分布: " + ", ".join(f"{r}×{n}" for r, n in why.most_common()))
        for key, reason in blocked[:6]:
            print(f"    · {key}: {reason}")
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
