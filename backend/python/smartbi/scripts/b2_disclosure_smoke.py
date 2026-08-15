"""B2 · 生产冒烟: T6② 的「静默换窗口必须披露」在**正文里**真的印出来了吗。

⛔ 只读。它不产出发生率样本, 与冻结的「上下文串」无关。

## 为什么单测不够(形态 B 的标准检查)

T6② 只验过单测 —— 单测直接调 `_time_window_substitution_disclosure(spec)`,
**绕过了「谁把它拼进正文」这一层**。
▎**「产出端有了」≠「消费端收得到」。**

## 三条读数

1. **触发**: 一个确定性层解不出时间的问句 ⇒ 窗口来自 T3 的近似 ⇒ 正文**必须**出现披露。
   ⚠️ ⛔ 不能再用「上个季度」——T6① 上线后它已经解得出来了, 不再触发替换。
      用「上半年」: `_resolve_sales_date_range` 第 2714 行**刻意**不猜日历半年,
      落「全部历史」(那是已裁定的诚实回退, 裁定 ③ 不改它)。
2. **阳性对照**: 一个自足、且时间词解得出来的问句 ⇒ 正文**不该**出现披露。
   ⛔ 没有这条, 「披露出现了」可能只是因为它对每句话都出现(形态 E: 在所有样本上
      都响的判据不区分好坏)。
3. **正文里的日期**: 「上个季度」要在**正文**里给出 2026-04-01~06-30,
   ⛔ 不是只看 spec.date_range —— 那又是「产出端有了」。
"""
import asyncio
import re
import sys

from smartbi.scripts._probe_bootstrap import bootstrap_probe

FACTORY = "MOCK_REST"
CAPTURE_TAG = "b2_disclosure_smoke_20260815"
MARK = "时间范围"          # `_time_window_substitution_disclosure` 的开头

ctx = bootstrap_probe(FACTORY)


def clear(ri, tag):
    names = ("clear_semantic_plan_cache", "clear_route_cache",
             "clear_tenant_gate_cache")
    for n in names:
        getattr(ri, n)()
    print(f"  [{tag}] 已清缓存: {', '.join(names)}")


async def main():
    pool = await ctx.pool()
    from smartbi.gold.restaurant import restaurant_intent as ri
    from smartbi.gold.restaurant.restaurant_intent_service import tiered_answer

    kinds = {}

    async def ask(tag, q):
        clear(ri, tag)
        res = await tiered_answer(q, pool, FACTORY, ctx.role,
                                  capture_source=CAPTURE_TAG)
        if not res:
            print(f"  [{tag}] ⛔ 返回 None")
            return None, ""
        spec = res.get("spec")
        text = res.get("answer_text") or ""
        kinds[tag] = res.get("kind")
        print(f"\n### [{FACTORY}] {tag}  {q!r}")
        print(f"  kind={res.get('kind')!r} source_tier="
              f"{getattr(spec, 'source_tier', None)!r}")
        print(f"  window_label={getattr(spec, 'window_label', None)!r} "
              f"date_range={getattr(spec, 'date_range', None)!r}")
        print(f"  window_from_llm_phrase="
              f"{getattr(spec, 'window_from_llm_phrase', None)!r} "
              f"time_range_defaulted={getattr(spec, 'time_range_defaulted', None)!r}")
        print("  ── 正文原文(⛔ 不转述) ──")
        print(text)
        print("  ── 正文结束 ──")
        return spec, text

    # ① 触发换窗口
    # ⛔ 不用「上半年」: 实测它被**菜品抽取**当成菜名(dish='上半年'), 于是被
    #    「菜品范围不能由全店汇总 resolver 代答」拦成 clarification, 根本走不到
    #    答案分支 —— 那是另一个缺陷, 会把本条读数变成「没验到」。
    spec1, text1 = await ask("① 触发(无时间词)", "过去两个季度营业额怎么样")
    # ② 阳性对照: 不该披露
    spec2, text2 = await ask("② 阳性对照(本月)", "本月营业额怎么样")
    # ③ 正文里的季度日期
    spec3, text3 = await ask("③ 季度日期在正文里吗", "上个季度营业额怎么样")

    print("\n" + "=" * 76)
    ok = True

    kind1 = kinds.get("① 触发(无时间词)")
    sub1 = bool(getattr(spec1, "window_from_llm_phrase", False))
    has1 = MARK in text1
    print(f"① kind={kind1!r} window_from_llm_phrase={sub1}  正文含「{MARK}」={has1}")
    # 🔴 先问 kind 再问正文: 披露只挂在**答案**分支上(与 _store_scope_disclosure
    #    同一处)。在 clarification 上说「标记置上了正文却没有」是错的诊断 ——
    #    前提就不成立。上一轮 T2 我犯过同一个错, 这次它又回来了。
    if kind1 != "answer":
        print(f"  ⚠️ 这次是 {kind1!r} 不是 answer ⇒ 披露分支根本没执行,"
              " 本条**没验到**(⛔ 不算通过也不算失败)")
        ok = False
    elif sub1 and not has1:
        print("  🔴 **形态 B**: 标记置上了, 正文里却没有 —— 披露没接到消费端")
        ok = False
    elif not sub1:
        print("  ⚠️ 这句没触发替换 ⇒ 本条**没验到**(⛔ 不算通过), 换一个问句再来")
        ok = False
    else:
        print("  ✅ 触发了, 且正文里印出来了")

    sub2 = bool(getattr(spec2, "window_from_llm_phrase", False))
    has2 = MARK in text2
    print(f"② 阳性对照: window_from_llm_phrase={sub2}  正文含「{MARK}」={has2}")
    if has2 and not sub2:
        print("  🔴 不该披露却披露了 —— 它对每句话都响, 不区分好坏")
        ok = False
    else:
        print("  ✅ 自足问句没有多余披露")

    dates = re.findall(r"20\d{2}-\d{2}-\d{2}", text3)
    label3 = getattr(spec3, "window_label", None)
    rng3 = getattr(spec3, "date_range", None)
    print(f"③ window_label={label3!r} spec.date_range={rng3!r}")
    print(f"   正文里的日期: {dates}")
    # 🔴 第一版断言「正文必须出现 2026-04-01」——**那是错的**。
    #    MOCK_REST 的数据从 2026-06-29 才开始(实测 min=2026-06-29), 季度窗口
    #    91 天里只有 2 天有数据, 正文括号里给的是**实际覆盖区间**, 不是请求窗口。
    #    ⇒ 判据改成: 窗口本身解对(spec) + 正文的标签是「上个季度」。
    import datetime as _dt
    if label3 == "上个季度" and rng3 == (_dt.date(2026, 4, 1), _dt.date(2026, 6, 30)):
        print("  ✅ 季度解对了; 正文括号里是**实际覆盖区间**(该租户 06-29 才有数据)")
        if "上个季度" not in text3:
            print("  🔴 正文里连「上个季度」这个标签都没有")
            ok = False
    else:
        print("  🔴 季度没解对")
        ok = False

    print("=" * 76)
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(asyncio.run(main()))
