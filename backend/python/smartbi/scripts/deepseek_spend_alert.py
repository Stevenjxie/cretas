"""DeepSeek 花钱警报 —— 每天估一次当日**花了多少钱**。

## 🔴 为什么按【金额】而不是【次数】

第一版按次数(20 次/天), **那是错的**: 次数阈值绑在「deepseek 是兜底」这个前提上,
而这个前提 **2026-09-13 就失效** —— 那天 aistore 到期, deepseek 变成五个槽的主力,
一天几百次是正常的, 20 次/天会**每天都响** ⇒ 被人关掉 ⇒ 覆盖率归零。
▎**而这个警报正是为躲开「形态 E: 天天误报的闸最终被关掉」才建的。**

按金额与「它是兜底还是主力」无关, 只与「烧得快不快」有关 ⇒ 跨过 9-13 仍然成立。
它守的不是那三厘钱, 是**「它会不会哪天自己烧起来」**:
仓里有前科 —— DeepSeek 官方 12 天烧掉 $19.49(见 llm_budget.py 模块 docstring)。

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

    rc=0  正常(当日估算花费在阈值内)
    rc=1  超阈值 —— 该喊
    rc=2  **没量到** —— 阳性对照未通过, 本次读数作废

## ⚠️ 阈值取值

`_DAILY_SPEND_USD_THRESHOLD = 1.0`(见该常量上方的算法)。
形态 E: **宁可窄而可信, 不要宽而被关掉。**

⚠️ 月度上限是另一道、更钝的闸: `LLM_DEEPSEEK_MAX_USD_PER_MONTH` 默认 $10
(约 43 倍余量), 在 `common/llm_budget.py`。本脚本只告警, ⛔ 不拒绝调用 ——
9-13 之后 deepseek 是主力, 拒绝它等于五个槽全黑。
"""
from __future__ import annotations

import argparse
import re
import sys
from collections import Counter
from datetime import date

from common.llm_budget import estimate_cost_usd

#: 超过它就喊。⛔ 具名常量, 不要写字面量在判断里。
#:
#: 🔴 2026-08-15 订正: 原来是 `_DAILY_CALL_THRESHOLD = 20`(**按次数**), 那是错的。
#:    次数阈值绑在「deepseek 是兜底」这个前提上, 而**这个前提 9-13 就失效** ——
#:    那天起 aistore 到期, deepseek 变成五个槽的主力, 一天几百次是正常的,
#:    20 次/天会**每天都响** ⇒ 被人关掉 ⇒ 覆盖率归零。
#:    而这个警报正是为躲开「形态 E: 天天误报的闸最终被关掉」才建的。
#:
#: 按金额则与「它是兜底还是主力」无关, 只与「烧得快不快」有关:
#:    全账号实测 308 次/天 ⇒ deepseek 当主力约 $0.008/天
#:    当年事故 $19.49 / 12 天 = $1.62/天
#:    ⇒ $1/天 = 正常的 125 倍, 且**当年那次第一天就会响**。
_DAILY_SPEND_USD_THRESHOLD = 1.0

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



def _write_probe_out(payload):
    """把读数落成 JSON —— 跑批的台账和「仪器还活着」的判据都读它。

    🔴 硬约束 9: 跑批里「今天没告警」是阴性读数, 而**任何一种「根本没跑」
    都长成它的样子**。⇒ 必须有一个**设计上必然出现**的产出配它。
    这个文件就是那个产出: 它在则说明探针跑到了做决定那一步。

    ⛔ 三态都要写: rc=2(没量到)也写, 否则「没量到」和「崩了」又分不开。
    """
    import json, os
    path = os.environ.get("PROBE_OUT")
    if not path:
        return
    try:
        with open(path, "w", encoding="utf-8", newline="") as f:
            json.dump(payload, f, ensure_ascii=False)
    except OSError:
        pass   # ⛔ 写不出产出不许让主判定失败 —— 它是观测, 不是门禁


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
        _write_probe_out({"rc": 2, "day": day, "reason": "log_unreadable"})
        return 2

    # 阳性对照(硬约束 9): 当天必须有人成功调用过**某个**模型
    if total_lines == 0:
        print(f"DEEPSEEK SPEND INSTRUMENT DEAD {day} — 当天 [cache] 行总数为 0, "
              f"分不清「没有付费调用」和「日志没写/读错文件」, 本次读数作废 "
              f"(log={args.log})")
        _write_probe_out({"rc": 2, "day": day, "reason": "no_cache_lines"})
        return 2

    cost = 0.0
    by_model = Counter()
    for r in rows:
        by_model[r["model"]] += 1
        cost += estimate_cost_usd(
            r["model"], int(r["prompt"]), int(r["completion"]), int(r["cached"]))

    print(f"day={day} account={args.account} calls={len(rows)} "
          f"est_cost_usd={cost:.6f} threshold_usd={_DAILY_SPEND_USD_THRESHOLD} "
          f"(当天全账号 [cache] 行 {total_lines} —— 阳性对照通过)")
    for model, n in by_model.most_common():
        print(f"  {model}: {n}")

    if cost > _DAILY_SPEND_USD_THRESHOLD:
        # ⛔ 标签由**实际值**算出来: 自检模式(--account aistore)下写「付费回退」
        #    就是内容对、标签假 —— aistore 是免费的。
        kind = "付费" if args.account == "deepseek" else f"{args.account}"
        print(f"DEEPSEEK SPEND HIGH {day} — {kind}估算 ${cost:.4f} "
              f"超过阈值 ${_DAILY_SPEND_USD_THRESHOLD} ({len(rows)} 次调用)。"
              f"⇒ 先查 aistore 是不是在大面积失败。")
        _write_probe_out({"rc": 1, "day": day, "total_usd": cost, "calls": len(rows)})
        return 1
    _write_probe_out({"rc": 0, "day": day, "total_usd": cost, "calls": len(rows)})
    return 0


if __name__ == "__main__":
    sys.exit(main())
