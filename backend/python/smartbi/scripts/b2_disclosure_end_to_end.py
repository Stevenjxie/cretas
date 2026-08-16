"""B-2 · 用**真实产品路径**验 T6② 的披露真的出现在正文里。

## ⛔ 为什么不注入构造的 spec

本仓反复栽在「探针构造了生产上不出现的形状」上（形态 B‴）：
桩的自由度让我可以构造任何输入，**包括真实上游永远不会给出的输入**，
于是断言在一个不存在的世界里全绿。

⇒ 这个探针**从问句开始**，走 `_resolve_sales_query_spec` 拿真实 spec，
再走 `window_scope_text` 拿真实正文片段。⛔ 中间不手工塞任何字段。

## 三档

1. **窗口披露**：请求窗口与实际数据范围**实质不同**时，正文里两个都要有
2. **阴性对照**：ETL 末端滞后 / 铺满时 ⛔ 不许啰嗦（天天出现的提示等于没有）
3. **归因基线披露**：正文里必须写明「跟什么比」——
   ⚠️ 基线选环比还是同比都不是「对的」，**所以它必须被说出来**

## 阳性对照（硬约束 9）

第 2 档是阴性读数。⇒ 第 1 档必须**确实**产出那段话，否则「没啰嗦」
分不清是「判定对了」还是「这条路根本没跑」。
"""
import datetime
import sys

D = datetime.date


def main() -> int:
    from smartbi.gold.restaurant.attribution_baseline import pick_baseline
    from smartbi.gold.restaurant.restaurant_ops_router import (
        _resolve_sales_query_spec,
        window_scope_text,
    )

    bad = []

    def check(name, ok, detail=""):
        print(f"{'✅' if ok else '🔴'} {name}" + (f"  {detail}" if detail else ""))
        if not ok:
            bad.append(name)

    today = D(2026, 8, 16)

    # ── 0 先证明「从问句拿 spec」这条路真的通 ────────────────────────
    print("── 0 真实问句 → 真实 spec（⛔ 不注入构造 spec）──")
    probes = ["上个季度生意怎么样", "这个月营收多少", "今天生意怎么样"]
    specs = {}
    for q in probes:
        spec = _resolve_sales_query_spec(q, today=today)
        specs[q] = spec
        print(f"   {q!r:20} window={spec.window_label!r} range={spec.date_range}")
    alive = all(getattr(s, "date_range", None) for s in specs.values())
    print(f"[阳性对照] 三个问句都解析出了窗口: {alive}")
    if not alive:
        print("⛔ spec 都拿不到 ⇒ 下面的读数全无意义，本轮作废。")
        return 2

    # ── 1 实质缺失 ⇒ 两个口径都出现 ─────────────────────────────────
    q = "上个季度生意怎么样"
    spec = specs[q]
    req = spec.date_range
    actual = (D(2026, 6, 29), D(2026, 6, 30))          # 只有最后两天有数
    text = window_scope_text(spec.window_label, req, actual)
    print(f"\n── 1 实质缺失（请求 {req[0]}~{req[1]}，实际只有 2 天）──\n   {text}")
    check("1 请求窗口与实际范围都出现在正文里",
          str(req[0]) in text and str(actual[0]) in text and "实际有数据的只有" in text)
    check("1b 请求窗口排在实际范围**前面**（否则读成「季度就是那两天」）",
          text.index(str(req[0])) < text.index(str(actual[0])))

    # ── 2 阴性对照：ETL 滞后 / 铺满 ⇒ 不许啰嗦 ──────────────────────
    q2 = "这个月营收多少"
    spec2 = specs[q2]
    r2 = spec2.date_range
    lag = (r2[0], r2[1] - datetime.timedelta(days=1))   # 今天的数据还没入库
    text_lag = window_scope_text(spec2.window_label, r2, lag)
    text_full = window_scope_text(spec2.window_label, r2, r2)
    print(f"\n── 2 阴性对照 ──\n   ETL 滞后: {text_lag}\n   刚好铺满: {text_full}")
    check("2a ETL 末端滞后一天 ⇒ 不啰嗦", "实际有数据的只有" not in text_lag)
    check("2b 刚好铺满 ⇒ 不啰嗦", "实际有数据的只有" not in text_full)

    # ── 3 归因基线必须被说出来 ─────────────────────────────────────
    print("\n── 3 归因基线披露 ──")
    for q3 in probes:
        rng = specs[q3].date_range
        if not (rng and rng[0] and rng[1]):
            continue
        base, label = pick_baseline(rng[0], rng[1])
        got = base is not None
        print(f"   {q3!r:20} 基线={label!r} {base}")
        check(f"3 {specs[q3].window_label} 挑得出基线且有名字", got and bool(label))
        if got:
            check(f"3b {specs[q3].window_label} 基线不与主窗口重叠", base[1] < rng[0],
                  f"{base[1]} vs {rng[0]}")

    print("\n" + "=" * 74)
    print(f"[主断言] 不通过 {len(bad)} 条")
    if bad:
        print(f"🔴 {bad}")
        return 1
    print("✅ 披露在真实路径上都出现了")
    return 0


if __name__ == "__main__":
    sys.exit(main())
