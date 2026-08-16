"""诊断：多轮里「本月」丢话题，是 LLM 的问题还是我们没传 history？

## 背景

电池里两条真缺陷（[36] / [76]）：链式追问的第二步只说「本月」，
系统回「你这次最想先看哪件事？」—— **上一轮话题全丢**。

🔴 那句话是 T3 prompt 里的**示例 2**（教 LLM「完全没有可判断的对象/指标」
时怎么回，例：「情况怎么样」）。⇒ LLM 把「本月」当成了孤立问句。

## 两种成因，处置完全不同

| | 现象 | 修哪 |
|---|---|---|
| ① history **没传到** prompt | prompt 里看不到上一轮 | 我们的接线 |
| ② 传到了但 **LLM 没用** | prompt 里有，模型仍吐示例 2 | prompt/模型 |

⛔ 不能只看结果就归因 —— 两者的结果长得一模一样。

## 做法

1. 先看**同一句话**在「有 history / 无 history」两种输入下，prompt 差在哪
   （纯字符串比对，不花钱）
2. 再各真调一次 LLM，看返回的 intent 差不差
   （⚠️ 硬约束 3：跑之前清缓存，并贴出清了哪几个 —— 否则第二次命中第一次的
     计划缓存，两侧变成同一个东西，这条线为此作废过四轮读数）
"""
import asyncio
import json
import sys

import smartbi.gold.restaurant.restaurant_intent as ri

FOLLOWUP = "本月"
HISTORY = [{"q": "哪个门店营收最好", "a_summary": "最近30天营收最高的是模拟·闵行莘庄社区店，营收 ¥2,119,731.70"}]


def _clear_caches():
    """硬约束 3：开跑前清缓存，并**贴出清了哪几个**。

    ⛔ 拼错的属性名不会报错，只会让清理静默失效（本仓实测踩过）。
    """
    for fn in ("clear_semantic_plan_cache", "clear_route_cache", "clear_tenant_gate_cache"):
        f = getattr(ri, fn, None)
        print(f"  清缓存 {fn}: {'✅ 已调用' if f else '⛔ 不存在(拼错了?)'}")
        if f:
            f()


async def main() -> int:
    print("=== ① prompt 层：history 到底进没进去 ===")
    p_no = ri._build_t3_prompt(FOLLOWUP, None, None, (), None)
    p_yes = ri._build_t3_prompt(FOLLOWUP, None, HISTORY, (), None)
    n = 0
    for a, b in zip(p_no, p_yes):
        if a != b:
            break
        n += 1
    print(f"  无 history 长度 {len(p_no)} / 有 history 长度 {len(p_yes)}")
    print(f"  分岔于 {n}，多出的那段：")
    print(f"    {p_yes[n:n + 160]!r}")
    carried = "哪个门店营收最好" in p_yes
    print(f"  [判据] 上一轮问句**出现在 prompt 里**: {carried}")
    if not carried:
        print("  ⇒ 成因① history 没进 prompt —— 那是**我们的接线**问题，到此为止。")
        return 1

    print("\n=== ② 模型层：同一个 prompt，它用不用那段 history ===")
    _clear_caches()
    payload_yes = {
        "messages": [
            {"role": "system", "content": "你只输出JSON格式的意图解析结果，不输出任何其他文字。"},
            {"role": "user", "content": p_yes},
        ],
        "temperature": 0, "max_tokens": 400,
    }
    payload_no = dict(payload_yes)
    payload_no["messages"] = [payload_yes["messages"][0],
                              {"role": "user", "content": p_no}]

    import common.llm_router as R
    out = {}
    for tag, payload in (("有history", payload_yes), ("无history", payload_no)):
        try:
            r = await R.call_chain(R.SLOT.CHAT, payload, timeout=40.0)
            txt = r.get("choices", [{}])[0].get("message", {}).get("content", "")
            served = r.get("served")
        except Exception as exc:                    # noqa: BLE001
            txt, served = f"ERR {type(exc).__name__}: {exc}", "-"
        out[tag] = (served, txt)
        # ⚠️ 逐条贴来源标记 —— 两侧若来自同一次缓存, 读数无效
        print(f"\n  [{tag}] served={served}")
        print(f"    {txt[:260]}")

    def _field(txt, key):
        try:
            return json.loads(txt[txt.index("{"):txt.rindex("}") + 1]).get(key)
        except Exception:                            # noqa: BLE001
            return "<解析失败>"

    print("\n" + "=" * 74)
    for key in ("intent", "clarification_needed", "clarification_question"):
        a = _field(out["无history"][1], key)
        b = _field(out["有history"][1], key)
        mark = "→ 有差别" if a != b else "  一样"
        print(f"  {key:<24} 无history={a!r:<28} 有history={b!r} {mark}")

    b_intent = _field(out["有history"][1], "intent")
    if b_intent:
        print("\n⇒ 带 history 时模型**解析出了 intent** ⇒ 模型会用那段上下文，"
              "\n  那么生产上丢话题就是**我们没把 history 传到这一步**（接线）。")
        return 0
    print("\n⇒ 带 history 模型仍不给 intent ⇒ 是**模型/prompt** 这一层，"
          "\n  与我们的接线无关。")
    return 1


if __name__ == "__main__":
    sys.exit(asyncio.run(main()))
