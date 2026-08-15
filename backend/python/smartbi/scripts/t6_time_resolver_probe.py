"""T6(c) + T6-f · 时间 resolver：模型的日期 vs 代码算的，哪个赢；以及确定性。

⛔ 只读。不改任何产品代码。

## 为什么必须真跑（T6c 判据原文）

「产出端有了」≠「消费端收得到」。`_parse_t3_time_range` 的 docstring 说
「real date computation never touches the LLM's output directly」——
那是**代码里的说法**，要看最终 spec 里的 `date_range` 到底是谁的值。

## T6-f 确定性

同一句问话跑 5 次，每次记 `window_label` / `date_range` / `source_tier`。

🔴 **每一次调用前都清缓存**（硬约束 3），⛔ 不是开跑前清一次 ——
   计划缓存键 `(factory_id, 归一化问句, plan_version)` 不含轮次，
   不清的话「跑 5 次」= 1 次真编译 + 4 次缓存复制，会读出「完全确定」这个假结论。
⇒ 每条读数必须带 `source_tier`，**5 条必须全是 `llm`**；
  出现 `plan_cache` ⇒ 那次作废并补跑，⛔ 不拿它凑数。

## 观测点：模型到底走了 time_range 的哪个分支

包一层 `_parse_t3_time_range` 记录**入参与出参**（探针进程内，⛔ 不改产品行为）。
它能直接分辨：
  `{"type":"absolute", ...}`  ⇒ 日期是**模型算的**（prompt 5399 行明令禁止）
  `{"type":"named"/"relative"}` ⇒ 只给了结构，日期由代码算
  返回 ""                      ⇒ 模型给的东西**没被采纳**，落默认窗口
"""
import asyncio
import sys

from smartbi.scripts._probe_bootstrap import bootstrap_probe

FACTORY = "MOCK_REST"
QUERY = "上个季度每周的营业额趋势"
RUNS = 5
MAX_ATTEMPTS = 9

#: 今天 2026-08-15 ⇒ 上个季度正确答案
EXPECTED = ("2026-04-01", "2026-06-30")

ctx = bootstrap_probe(FACTORY)


def clear_caches(ri, tag):
    """硬约束 3：⛔ 用模块自带 helper，不拼属性名（拼错不报错，只会静默 no-op）。"""
    names = ("clear_semantic_plan_cache", "clear_route_cache",
             "clear_tenant_gate_cache")
    for name in names:
        getattr(ri, name)()
    print(f"  [{tag}] 已清缓存: {', '.join(names)}")


async def main():
    pool = await ctx.pool()
    from smartbi.gold.restaurant import restaurant_intent as ri
    from smartbi.gold.restaurant.restaurant_ops_router import _resolve_sales_date_range

    # ── 先把「确定性层单独怎么看这句话」量出来（零 LLM，纯函数）──
    det_range, det_label = _resolve_sales_date_range(QUERY)
    print("=" * 78)
    print(f"问句: {QUERY!r}   今天应为 2026-08-15   上个季度正确答案 {EXPECTED}")
    print(f"[确定性层单独跑] _resolve_sales_date_range -> "
          f"date_range={det_range} window_label={det_label!r}")
    print("  ⇒ 这一步没有 LLM。它是否解出窗口，决定 LLM 的短语会不会被采纳"
          " (_build_spec:2188 只在这里 == '全部历史' 时才拼 time_phrase)")
    print("=" * 78)

    # ── 观测模型走了哪个分支 ──
    seen = []
    orig = ri._parse_t3_time_range

    def spy(time_range):
        out = orig(time_range)
        seen.append((time_range, out))
        return out

    ri._parse_t3_time_range = spy

    rows, voided = [], []
    attempt = 0
    try:
        while len(rows) < RUNS and attempt < MAX_ATTEMPTS:
            attempt += 1
            tag = f"第{attempt}次"
            clear_caches(ri, tag)
            before = len(seen)
            spec = await ri.parse_restaurant_query(
                QUERY, pool, factory_id=FACTORY, semantic_first=True,
            )
            if spec is None:
                voided.append((tag, "spec=None", None))
                print(f"  [{tag}] ⛔ spec=None —— 作废并补跑")
                continue
            tier = getattr(spec, "source_tier", None)
            branch = seen[before:] or [("<未调用>", "<未调用>")]
            row = {
                "tag": tag,
                "source_tier": tier,
                "window_label": getattr(spec, "window_label", None),
                "date_range": getattr(spec, "date_range", None),
                "t3_time_range_in": branch[-1][0],
                "t3_time_phrase_out": branch[-1][1],
            }
            print(f"  [{tag}] source_tier={tier!r} "
                  f"window_label={row['window_label']!r} "
                  f"date_range={row['date_range']!r}")
            print(f"        模型给的 time_range={row['t3_time_range_in']!r} "
                  f"-> 转成短语 {row['t3_time_phrase_out']!r}")
            if tier != "llm":
                voided.append((tag, f"source_tier={tier!r}", row))
                print(f"  [{tag}] ⛔ 不是 llm —— 作废并补跑（⛔ 不拿它凑数）")
                continue
            rows.append(row)
    finally:
        ri._parse_t3_time_range = orig

    print("\n" + "=" * 78)
    print(f"有效读数 {len(rows)}/{RUNS}（尝试 {attempt} 次）")
    # 形态 C′: 失败要计数并逐条贴, ⛔ 不许静默 continue
    print(f"作废 {len(voided)} 条:")
    for tag, why, _ in voided:
        print(f"    {tag}: {why}")
    if len(rows) < RUNS:
        print("⛔ 没凑够 5 条有效读数 —— ⛔ 不下确定性结论。")
        return 2

    print("\n逐条（⛔ 不汇总，来源标记逐条贴 —— 形态 A¹³）:")
    for r in rows:
        print(f"  {r['tag']}  tier={r['source_tier']!r}  "
              f"label={r['window_label']!r}  range={r['date_range']!r}")

    labels = {r["window_label"] for r in rows}
    ranges = {str(r["date_range"]) for r in rows}
    print("\n" + "=" * 78)
    print(f"window_label 去重 = {labels}   ({len(labels)} 种)")
    print(f"date_range   去重 = {ranges}   ({len(ranges)} 种)")
    print(f"source_tier  去重 = {{'llm'}} × {len(rows)}  ← 阳性对照: 全是真编译")
    print("\n判读（owner 的表）:")
    if len(labels) == 1 and len(ranges) > 1:
        print("  ⇒ 标签稳 + 日期飘 ⇒ 日期交给代码之后非确定性一并消失；模型选型收口")
    elif len(labels) > 1:
        print("  ⇒ 标签也飘 ⇒ 真的模型不稳，换模型重新进入考虑（⛔ 本轮不换）")
    else:
        print("  ⇒ 标签稳 + 日期也稳 ⇒ 这一句上没有观测到非确定性")
    print("=" * 78)
    return 0


if __name__ == "__main__":
    sys.exit(asyncio.run(main()))
