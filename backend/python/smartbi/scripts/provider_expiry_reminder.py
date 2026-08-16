"""A4 · 到期/复审提醒 —— 日期**从 `_SAFE_MODELS` 读**，⛔ 不另抄一份。

## 为什么不写一张「提醒日期表」

那就是形态 D：改了注册表的日期而忘了改提醒表，提醒会在错误的日子响
（或者该响的那天不响）。⇒ 唯一的日期源就是注册表本身。

## 两种日期语义**完全不同**，措辞必须分开

| | 含义 | 到期那天会发生什么 |
|---|---|---|
| aistore `2026-09-13` | **真的额度到期** | 那天之后再调就是越界，链首必须已经换掉 |
| deepseek `2026-11-15` | **我们自己设的强制复审点** | 什么都不会发生 —— 它只是「回来看一眼还要不要留」 |

🔴 混为一谈的代价是具体的：把复审日读成到期日，会导致要么提前做一次不必要
的迁移，要么在它「过期」那天以为线上出了故障。⇒ 两种各自一句话。

## rc 三态（硬约束 4）

    rc=0  没有临近的日期
    rc=1  有日期临近（该看了）
    rc=2  **读不到注册表** —— 这次没量到东西，⛔ 不许读成「没有临近的」

⚠️ rc=2 这一态最容易被忽略：一个 import 失败的提醒脚本会**安静地天天说
「没事」**，而它一条记录都没读过。
"""
import datetime
import json
import os
import sys

#: 提前多少天开始提醒。到期日那种要留足迁移时间，复审日不用那么久。
LEAD_DAYS_EXPIRY = 30
LEAD_DAYS_REVIEW = 14

#: 哪些账号的日期是**真到期**（其余按复审点处理）。
#: ⚠️ 这个判断本身是人标的 —— 注册表里存的只是日期，语义分不出来。
#:    ⇒ 它必须留痕，⛔ 不能假装是推导出来的。
HARD_EXPIRY_ACCOUNTS = frozenset({"aistore"})


def collect(today=None):
    today = today or datetime.date.today()
    from common.llm_router import _SAFE_MODELS

    rows = []
    for (account, model), when in _SAFE_MODELS.items():
        if not isinstance(when, datetime.date):
            continue
        days = (when - today).days
        hard = account in HARD_EXPIRY_ACCOUNTS
        lead = LEAD_DAYS_EXPIRY if hard else LEAD_DAYS_REVIEW
        rows.append({
            "account": account, "model": model, "date": when.isoformat(),
            "days_left": days, "kind": "expiry" if hard else "review",
            "due": days <= lead,
        })
    return sorted(rows, key=lambda r: r["days_left"])


def main(argv=None) -> int:
    today = datetime.date.today()
    if argv:
        today = datetime.date.fromisoformat(argv[0])

    try:
        rows = collect(today)
    except Exception as exc:                      # noqa: BLE001
        print(f"PROVIDER REMINDER INSTRUMENT DEAD {today} — 读不到 _SAFE_MODELS: {exc}")
        _write({"rc": 2, "reason": "registry_unreadable"})
        return 2

    # 阳性对照(硬约束 9): 注册表里必须真有条目。0 条不是「没有临近的」,
    # 是「我一条都没读到」—— 两者在 `due` 的计数上长得一模一样。
    if not rows:
        print(f"PROVIDER REMINDER INSTRUMENT DEAD {today} — 注册表 0 条, "
              f"分不清「没有临近的日期」和「压根没读到」, 本次读数作废")
        _write({"rc": 2, "reason": "empty_registry"})
        return 2

    print(f"today={today} 注册表 {len(rows)} 条 —— 阳性对照通过")
    for r in rows:
        mark = "⚠️" if r["due"] else "  "
        print(f"{mark} {r['account']}/{r['model']:<28} {r['date']} "
              f"剩 {r['days_left']:>4} 天  [{r['kind']}]")

    due = [r for r in rows if r["due"]]
    _write({"rc": 1 if due else 0, "total": len(rows), "due": len(due),
            "nearest_days": rows[0]["days_left"]})
    if not due:
        return 0

    # 🔴 三态, ⛔ 不是两态。第一版把「已经过期」和「即将到期」写成同一句,
    #    实测 today=2026-11-05 时打出「剩 **-53** 天…那天之后再调就是越界」——
    #    ① 措辞是**未来时**, 而事情早就发生了
    #    ② 它会**天天喊到永远**, 而永远喊的告警最终会被无视(形态 E)
    #    ⇒ 已过期是另一件事: 不是「该准备了」, 是「白名单里还留着一条过期的」。
    for r in due:
        past = r["days_left"] < 0
        if r["kind"] == "expiry" and past:
            print(f"PROVIDER EXPIRY PAST — {r['account']}/{r['model']} 的额度在 "
                  f"{r['date']} **已经过期 {-r['days_left']} 天**, 而它**还在白名单里**。"
                  f"⇒ 确认链首已经换掉之后, 把这条从 `_SAFE_MODELS` 删掉 ——"
                  f"留着它每天都会再喊一次。")
        elif r["kind"] == "expiry":
            print(f"PROVIDER EXPIRY DUE — {r['account']}/{r['model']} 的额度 "
                  f"{r['date']} **真的到期**（剩 {r['days_left']} 天）。"
                  f"⇒ 那天之后再调就是越界，链首必须已经换掉。")
        elif past:
            print(f"PROVIDER REVIEW OVERDUE — {r['account']}/{r['model']} 的强制复审点 "
                  f"{r['date']} 过去 {-r['days_left']} 天了, 还没人复审。"
                  f"⚠️ 它**没有**断掉(按量付费没有到期日), 只是没人回来看过账。")
        else:
            print(f"PROVIDER REVIEW DUE — {r['account']}/{r['model']} 到了 "
                  f"{r['date']} 的**强制复审点**（剩 {r['days_left']} 天）。"
                  f"⚠️ 它**不会**在那天断掉 —— 按量付费没有到期日。"
                  f"⇒ 回来看一眼实际花了多少、还要不要留。")
    return 1


def _write(payload):
    path = os.environ.get("PROBE_OUT")
    if not path:
        return
    try:
        with open(path, "w", encoding="utf-8", newline="") as f:
            json.dump(payload, f, ensure_ascii=False)
    except OSError:
        pass


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
