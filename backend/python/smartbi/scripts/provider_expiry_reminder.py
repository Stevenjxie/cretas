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


def successor_readiness(today):
    """到期那天**接班的那个，今天能不能真的答**。

    🔴 2026-08-16 prod 实测，这条不是理论风险：

        aistore 2026-09-13 到期（还剩 28 天），五个槽的链首都是它。
        把时钟推到 9-14 后逐条问：
          aistore   refuse='expired'  ⇒ 调用时正确跳过 ✅
          deepseek  refuse=None       key **长度 0**  ⇒ 调用即 NO_API_KEY
          zhipu     refuse=None       key 有          ⇒ **唯一还能答的**

    ⇒ 「拒绝机制работает」和「接班人能答」是两件事。台账里写的
      「9-13 自动接手，当天无需人工操作」只有前半句是真的。

    ⚠️ 这个缺口**在到期前一天都不会有任何症状** —— 今天一切正常。
       所以它必须由一条**带倒计时**的闸来喊，⛔ 不能等那天再发现。

    判据：到期后每个槽的链里，**第一个既不被 refuse、又有 key 的**是谁。
    只剩一个能答的 ⇒ 单点，没有兜底。

    ⚠️ **本函数的已知盲区（2026-08-16 实测发现，登记而非豁免）**：
    它只看「key 在不在」，⛔ 看不出「key 在但**已失效**」。
    实测：服务器上那把 DeepSeek key 存在（35 字符，13 份 unit 备份里同一把），
    而官方直接回 `401 Authentication Fails, ... is invalid`。
    ⇒ 只要有人把一把**废 key** 填进配置，这道闸就会变绿而系统照样答不了。
    补位的是同一次跑批里的 **liveness 探针**（它真发请求），
    两个读数必须**一起看** —— 这也是它们挂在同一个 cron 里的原因。
    ⛔ 不在这里调 liveness 去补：那会让一道「读注册表」的闸变成要发网络请求的闸，
       慢十倍且多一个失败源；两个探针各自单一职责、结果并排落台账，更稳。
    """
    import common.llm_router as R

    original = R._today
    try:
        rows = []
        for slot in R.SLOT:
            chain = R._build_chain(slot)
            if not chain:
                continue
            # ⚠️ 推的是**时钟**(`_today`, 仓里自带的注入缝)，
            #    ⛔ 不是改 `_SAFE_MODELS` 里的到期日 —— 那样量的是我改过的东西。
            usable_now, usable_after = [], []
            for when, bucket in ((today, usable_now), (_after_cliff(today), usable_after)):
                R._today = lambda w=when: w
                for account, model in chain:
                    if R._refuse_reason(account, model):
                        continue
                    _, key = R._provider_config(account)
                    if key:
                        bucket.append(account)
            rows.append({
                "slot": slot.name,
                "usable_now": usable_now,
                "usable_after_cliff": usable_after,
            })
        return rows
    finally:
        R._today = original


def _after_cliff(today):
    """最近的一个**真到期**日的次日。没有到期日就用今天(此时两侧应当一致)。"""
    import common.llm_router as R

    dates = [d for (a, _m), d in R._SAFE_MODELS.items()
             if a in HARD_EXPIRY_ACCOUNTS and isinstance(d, datetime.date) and d >= today]
    return (min(dates) + datetime.timedelta(days=1)) if dates else today


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

    # ── 接班人体检 ────────────────────────────────────────────────────
    # ⚠️ 这一段与上面的「还剩几天」是**两个不同的问题**:
    #    上面问「日期到了没」, 这里问「到了那天还有谁能答」。
    #    前者绿不代表后者绿 —— 实测就是这样(日期还早, 而接班人已经空着 key)。
    cliff = _after_cliff(today)
    starving = []
    try:
        ready = successor_readiness(today)
    except Exception as exc:                       # noqa: BLE001
        print(f"PROVIDER REMINDER INSTRUMENT DEAD {today} — 接班人体检跑不起来: {exc}")
        _write({"rc": 2, "reason": "successor_probe_failed"})
        return 2

    print(f"\n=== 到期后({cliff})每个槽还剩几个能答的 ===")
    for r in ready:
        n_now, n_after = len(r["usable_now"]), len(r["usable_after_cliff"])
        mark = "🔴" if n_after <= 1 else "  "
        print(f"{mark} {r['slot']:<12} 现在 {n_now} 个 -> 到期后 {n_after} 个 "
              f"{r['usable_after_cliff']}")
        if n_after <= 1:
            starving.append(r)

    due = [r for r in rows if r["due"]]
    _write({"rc": 1 if (due or starving) else 0, "total": len(rows), "due": len(due),
            "nearest_days": rows[0]["days_left"],
            "slots_single_point_after_cliff": [r["slot"] for r in starving]})

    for r in starving:
        left = r["usable_after_cliff"]
        if not left:
            print(f"PROVIDER SUCCESSOR MISSING — 槽 {r['slot']} 在 {cliff} 之后"
                  f"**一个能答的都不剩**。⇒ 那天该槽全黑。")
        else:
            print(f"PROVIDER SUCCESSOR THIN — 槽 {r['slot']} 在 {cliff} 之后只剩 "
                  f"{left[0]} 一个能答, **没有兜底**。"
                  f"⚠️ 「在链上」不等于「能答」, 两种成因**修法不同**: "
                  f"① key 是空的 ⇒ 填进配置; "
                  f"② key 有但**失效** ⇒ 得去供应商控制台**重新签发**。"
                  f"⇒ 看同一次跑批的 liveness 读数分辨: NO_API_KEY 是①, http401 是②。")

    if not due and not starving:
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
