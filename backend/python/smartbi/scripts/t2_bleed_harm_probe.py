"""T2 · 串1「伤害读数」—— 一次产品读数，⛔ 不产出发生率样本。

## 问题

「本月订单数多少」在 `store_scope` 被上一轮串成 `'single'` 时，
**正文有没有说清这是哪家店的数**？

    说了   ⇒ 可见错误（用户看得见口径变了，能自己纠正）
    没说   ⇒ 静默错数（店长以为是全店数，实际是单店数）

## ⛔ 为什么这不是「第四轮」

它**不产出发生率样本**：只跑 2 次（A 无 history / B 带 history），
不数分子分母，不碰 `3/13`。被冻结的是「追发生率」，不是「问一次伤害多大」。

## ⚠️ 与 `context_bleed.py:21-25` 的关系

那段「⛔ 为什么不看正文」禁的是**用正文判「串不串」**（正文被模板洗过，
会同时漏报和误报）。这里判的是**伤害有多大** —— 串不串已经由槽位判完了。
不同的问题，不冲突。

## 判据（硬约束 9：验证「X 没发生」必须配一个「Y 一定会发生」的读数）

主读数是阴性的（「正文里没有门店名」），所以必须配阳性对照：

    阳性对照 PC2 · A 的正文**必须**出现「范围：全部」
      ⇒ 证明 ① 正文确实有「范围披露」这条通道 ② 我的文本搜索看得见它
      PC2 不过 ⇒ 主读数作废，⛔ 不许报「正文没说」

## 跑法

    scp backend/python/smartbi/scripts/t2_bleed_harm_probe.py \
        root@<host>:/tmp/cretas-probe/
    ssh <host> 'cd /tmp/cretas-probe && ./run.sh -u t2_bleed_harm_probe.py'
"""
import asyncio
import sys

from smartbi.scripts._probe_bootstrap import (
    bootstrap_probe,
    assert_not_a_probe_artifact,
)

FACTORY = "MOCK_REST"
QUERY = "本月订单数多少"

#: 让这两条 capture 行在人审候选池里一眼可辨 —— ⛔ 探针流量不许冒充用户流量。
CAPTURE_TAG = "t2_harm_probe_20260815"

#: A 的正文里**必然**出现的串（`_store_scope_disclosure` 第 274/276 行）。
DISCLOSURE_MARK = "范围：全部"

ctx = bootstrap_probe(FACTORY)


def clear_caches(ri, label):
    """硬约束 3：开跑前清缓存，并**贴出清了哪几个**。

    ⛔ 用模块自带 helper，不拼属性名 —— 拼错的名字不报错，只会静默 no-op。
    """
    names = ("clear_semantic_plan_cache", "clear_route_cache",
             "clear_tenant_gate_cache", "clear_promoted_routes_cache")
    cleared = []
    for name in names:
        getattr(ri, name)()          # ⛔ 不给 default：不存在就该当场炸
        cleared.append(name)
    print(f"[{label}] 已清缓存: {', '.join(cleared)}")


def show(label, res):
    spec = res.get("spec") if res else None
    text = (res or {}).get("answer_text") or ""
    print(f"\n### [{FACTORY}] {label}")
    if res is None:
        print("  ⛔ tiered_answer 返回 None")
        return None, ""
    print(f"  kind                   {res.get('kind')!r}")
    if spec is None:
        print("  ⛔ spec 为 None")
        return None, text
    # 形态 A¹³：每条读数带来源标记，否则分不清「真编译」还是「缓存复制」
    print(f"  source_tier            {getattr(spec, 'source_tier', None)!r}")
    print(f"  intent                 {getattr(spec, 'intent', None)!r}")
    print(f"  store_scope            {getattr(spec, 'store_scope', None)!r}")
    print(f"  store_slots            {getattr(spec, 'store_slots', None)!r}")
    print(f"  store_scope_defaulted  {getattr(spec, 'store_scope_defaulted', None)!r}")
    print(f"  store_options(前3)     {tuple(getattr(spec, 'store_options', ()) or ())[:3]!r}")
    print(f"  正文字符数             {len(text)}")
    print("  ── 正文原文（⛔ 不转述，不截断）──")
    print(text)
    print("  ── 正文结束 ──")
    return spec, text


async def main():
    pool = await ctx.pool()
    from smartbi.gold.restaurant import restaurant_intent as ri
    from smartbi.gold.restaurant.restaurant_intent_service import tiered_answer

    async def ask(label, history):
        clear_caches(ri, label)
        return await tiered_answer(
            QUERY, pool, FACTORY, ctx.role,
            history=history,
            capture_source=CAPTURE_TAG,
        )

    # ── A：不带 history（报告里「不带」那一侧，期望 store_scope='all'）──
    res_a = await ask("A 不带 history", None)
    spec_a, text_a = show("A 不带 history", res_a)

    if spec_a is None:
        print("\n⛔ A 侧没拿到 spec —— 本轮没有伤害读数，⛔ 不猜。")
        return 2

    options = tuple(getattr(spec_a, "store_options", ()) or ())
    if not options:
        print("\n⛔ 拿不到门店名单，构造不出串的 history —— 本轮没有伤害读数。")
        return 2
    target_store = options[0]

    # ── B：带 history，上一轮把范围定在单店（复现串1）──
    history = [{
        "q": f"{target_store}最近30天卖得怎么样",
        "a_summary": f"{target_store}最近30天营收…",
        "context": {"store_scope": "single", "store_names": [target_store]},
    }]
    res_b = await ask("B 带 history(单店)", history)
    spec_b, text_b = show(f"B 带 history(单店={target_store})", res_b)

    assert_not_a_probe_artifact([text_a, text_b])

    # ── 判定 ────────────────────────────────────────────────────────────
    print("\n" + "=" * 72)
    print("阳性对照")
    pc1 = getattr(spec_a, "store_scope", None) == "all"
    print(f"  PC1 A 侧 store_scope == 'all'（复现报告不带侧）: {pc1}")
    pc2 = DISCLOSURE_MARK in text_a
    print(f"  PC2 A 侧正文含 {DISCLOSURE_MARK!r}（披露通道活着 + 搜索看得见）: {pc2}")

    if not pc2:
        print("\n⛔ PC2 不过 —— 主读数作废。**不许**据此报「正文没说」。")
        print("   （可能是 store_scope_defaulted 为假，或披露措辞已改）")
        return 2

    if spec_b is None or getattr(spec_b, "store_scope", None) != "single":
        print(f"\n⛔ B 侧 store_scope = "
              f"{getattr(spec_b, 'store_scope', None)!r}，串1 本次没复现。")
        print("   ⇒ 本轮**没有**伤害读数。⛔ 不拿没复现当「不伤害」。")
        return 2

    print("\n主读数（B 侧串成单店之后，产品做了什么）")
    kind_b = res_b.get("kind")
    named = target_store in text_b
    any_store_named = [s for s in options if s in text_b]
    disclosed = DISCLOSURE_MARK in text_b
    print(f"  B kind                                  : {kind_b!r}")
    print(f"  B 正文含被串进来的门店名 {target_store!r}: {named}")
    print(f"  B 正文含任何一个门店名                  : {any_store_named!r}")
    print(f"  B 正文含 {DISCLOSURE_MARK!r}            : {disclosed}")
    print("=" * 72)

    # 🔴 2026-08-15 订正：第一版只问「正文有没有门店名」，**没有先问 kind**。
    #    实测 B 侧 kind='clarification'（`_execution_mismatch` 拦下了），
    #    而第一版据此打印「静默错数」—— 前提就不成立：它压根没给数。
    #    ⇒ 「有没有说清哪家店」只有在**它真的给了数**时才是个问题。
    #    与本仓那条判据同形：先证明被测行为发生了，再判它对不对。
    if kind_b != "answer":
        print(f"结论: 保守侧 —— 产品 **fail-closed**（kind={kind_b!r}），"
              "没有给出单店数字。")
        print("      ⇒ 串1 在本次读数上**不是**静默错数，也就不是危险侧。")
        print("      ⚠️ 作用域：这一条只说明【问句编译出的计划是全店 resolver】时，"
              "口径不匹配闸接得住。⛔ 不能外推到计划本身就支持单店的那些问句。")
        return 0
    if named or any_store_named:
        print("结论: 可见错误 —— 给了数，且正文点了门店名，用户看得见口径变了。")
    else:
        print("结论: 🔴 静默错数 —— 给了数，而正文一个门店名都没出现，"
              "店长按全店口径读一个单店数字。")
    return 0


if __name__ == "__main__":
    sys.exit(asyncio.run(main()))
