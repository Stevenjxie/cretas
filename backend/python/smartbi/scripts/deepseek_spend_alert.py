"""DeepSeek 花钱警报 —— 每天数一次「回退到付费的次数」并估成本。

## 为什么是「次数」不是「余额」

正常值是**一天 0～1 次**(实测 4 天 2 次)。它平时几乎不动, 所以「次数突然涨」
是最早、最灵敏的信号: aistore 一旦大面积失败, 回退会在同一天成百倍地放大。
⇒ 这个警报守的不是那三厘钱, 是**「它会不会哪天自己烧起来」**。
仓里有前科: DeepSeek 官方 12 天烧掉 $19.49(见 llm_budget.py 模块 docstring)。

## 数据源: 已经在打的日志, ⛔ 不新加埋点

    [cache] slot=review via aistore/DeepSeek-V4-Flash-A: prompt=4198 cached=0 (0%) completion=132

`_log_cache_and_record_budget` 对**每一次成功调用**都打这一行, 里面已经有
account/model 和三个 token 数 —— 估成本要的全在里面。

## ⛔ 价格表复用 `common.llm_budget`, 不在这里再抄一份

两份价格必漂(形态 D)。`estimate_cost_usd` 就是为此存在的。

## 硬约束 9 / 形态 E′ —— 「没喊」有两种

「今天 0 次付费调用」和「今天日志没写/我没读到」在计数上长得一模一样。
⇒ 阳性对照: **当天所有账号的 `[cache]` 行总数必须 > 0**。为 0 说明服务当天
   没有成功调用过任何模型(或我读错了文件) ⇒ 报 `INSTRUMENT DEAD`(rc=2),
   ⛔ 不许当成「没花钱」。

## 三态退出码(硬约束 4)

    rc=0  正常(次数在阈值内)
    rc=1  超阈值 —— 该喊
    rc=2  **没量到** —— 阳性对照未通过, 本次读数作废

## ⚠️ 阈值取值

`_DAILY_CALL_THRESHOLD` 默认 20 —— 相对实测基线(~0.5 次/天)约 40 倍。
形态 E: **宁可窄而可信, 不要宽而被关掉。** 一个每天误报的警报最终会被 noqa 掉,
那时它的覆盖率归零。要改就改这个具名常量。
"""
from __future__ import annotations

import argparse
import re
import sys
from collections import Counter
from datetime import date

from common.llm_budget import estimate_cost_usd

#: 超过它就喊。⛔ 具名常量, 不要写字面量在判断里。
_DAILY_CALL_THRESHOLD = 20

#: `_log_cache_and_record_budget` 打出来的那一行。
_CACHE_LINE = re.compile(
    r"^(?P<day>\d{4}-\d{2}-\d{2}).*?\[cache\] slot=(?P<slot>\S+) "
    r"via (?P<account>[^/]+)/(?P<model>\S+?): "
    r"prompt=(?P<prompt>\d+) cached=(?P<cached>\d+) \(\d+%\) "
    r"completion=(?P<completion>\d+)"
)


def scan(path: str, day: str, account: str):
    rows, total_lines = [], 0
    with open(path, "r", encoding="utf-8", errors="replace") as fh:
        for line in fh:
            m = _CACHE_LINE.match(line)
            if not m or m.group("day") != day:
                continue
            total_lines += 1               # 阳性对照: 当天**所有账号**的行数
            if m.group("account") == account:
                rows.append(m.groupdict())
    return rows, total_lines


def main(argv=None) -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--log", default="/www/wwwroot/cretas/python-prod.log")
    ap.add_argument("--day", default=None, help="YYYY-MM-DD, 默认今天")
    ap.add_argument("--account", default="deepseek",
                    help="⚠️ 用 --account aistore 可以在**今天**自检解析器: "
                         "格式与 deepseek 完全相同, 而 deepseek 可能还没有任何行")
    args = ap.parse_args(argv)
    day = args.day or date.today().isoformat()

    try:
        rows, total_lines = scan(args.log, day, args.account)
    except OSError as exc:
        print(f"DEEPSEEK SPEND INSTRUMENT DEAD {day} — 读不到日志: {exc}")
        return 2

    # 阳性对照(硬约束 9): 当天必须有人成功调用过**某个**模型
    if total_lines == 0:
        print(f"DEEPSEEK SPEND INSTRUMENT DEAD {day} — 当天 [cache] 行总数为 0, "
              f"分不清「没有付费调用」和「日志没写/读错文件」, 本次读数作废 "
              f"(log={args.log})")
        return 2

    cost = 0.0
    by_model = Counter()
    for r in rows:
        by_model[r["model"]] += 1
        cost += estimate_cost_usd(
            r["model"], int(r["prompt"]), int(r["completion"]), int(r["cached"]))

    print(f"day={day} account={args.account} calls={len(rows)} "
          f"est_cost_usd={cost:.6f} threshold={_DAILY_CALL_THRESHOLD} "
          f"(当天全账号 [cache] 行 {total_lines} —— 阳性对照通过)")
    for model, n in by_model.most_common():
        print(f"  {model}: {n}")

    if len(rows) > _DAILY_CALL_THRESHOLD:
        # ⛔ 标签由**实际值**算出来: 自检模式(--account aistore)下写「付费回退」
        #    就是内容对、标签假 —— aistore 是免费的。
        kind = "付费回退" if args.account == "deepseek" else f"{args.account} 调用"
        print(f"DEEPSEEK SPEND HIGH {day} — {kind} {len(rows)} 次 "
              f"(阈值 {_DAILY_CALL_THRESHOLD}, 基线约 0~1 次/天), "
              f"估算 ${cost:.4f}。⇒ 先查 aistore 是不是在大面积失败。")
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
