"""交付定义② 的判据换掉了 —— 旧的那个是坏代理，我亲手用它误报过。

## 📏 旧判据错在哪（prod 逐条读原文，MOCK_REST）

旧判据：**答案里出现的维度 > 他问句里的维度**。

它报的 🔴 里至少两条是**误报**：

```
「按门店看领料趋势」 问=答  → 实际给了食材榜 + 门店榜**两张表** + 3 条建议动作
「折扣力度多大」    问=答  → 实际给了构成表 + 「这是让利的规模，**不代表**
                            折扣带来了同等的营收增长；库里没有反事实数据」
```

▎形态 A：我想知道的 X 是「有没有说一件他没想到的事」，
▎我实际在量的 Y 是「答案里的维度标签比他问的多」。

## 新判据：五个各自独立的标记，逐条贴出来让人读

📏 prod 实测这五个标记有判别力（⛔ 不是近 0 也不是近 100）：

```
a_对比 73.7% · b_口径 57.9% · c_异常 36.8% · d_建议 89.5% · e_缺口 52.6%
命中 5 个 7 条 / 2~3 个 9 条 / ≤1 个 3 条（19 条非豁免）
```

⛔ **不报「达标率」，② 不进 rc 判定** —— 验收方式是逐条读，不是打分。
⛔ 五个标记全是代理判据（词表匹配），⛔ 不靠加词逼近（形态 E）。
"""
from __future__ import annotations

from smartbi.scripts.restaurant_delivery_definitions_probe import (
    _ACTION_WORDS,
    _EXTRA_MARKS,
    _FACT_LOOKUP_INTENTS,
)


def _hits(text: str):
    return {k for k, rx in _EXTRA_MARKS.items() if rx.search(text)}


# ── 🔴 承重：旧判据误报的那两条，新判据必须认出来 ──────────────────────────

def test_the_two_false_negatives_are_now_caught():
    """📏 这两段是 prod 原文片段，旧判据把它们判成「只念了他问的」。"""
    requisition = (
        "各门店领料金额（10 家，从高到低）:\n"
        "建议动作:\n"
        "1. 把领用靠前的食材和畅销菜、损耗榜交叉看，判断是销量驱动还是领用过量。"
    )
    discount = (
        "读的时候注意：这是让利的规模，**不代表**折扣带来了同等的营收增长；"
        "库里没有反事实数据，做不了这个归因。"
    )
    assert _hits(requisition), "领料那条一个标记都没命中"
    assert _hits(discount), "折扣那条一个标记都没命中"


def test_each_mark_can_fire_on_its_own():
    """🔴 五个标记各自要能红 —— 一个从不命中的标记等于不存在。"""
    samples = {
        "a_对比": "最高与最低相差 14.1%",
        "b_口径": "这个数**算不出来**，别把它读成全店毛利",
        "c_异常": "🔴 有 1 道菜的成本比营收还高",
        "d_建议": "⇒ 下一步：先核这几张成本卡",
        "e_缺口": "4 道菜**缺成本卡**，成本和毛利算不出来",
    }
    for mark, text in samples.items():
        assert mark in _hits(text), f"{mark} 在它自己的样本上都不命中"


def test_a_plain_number_restatement_hits_nothing():
    """🔴 阴性对照：只把他问的数念一遍 ⇒ **一个标记都不该命中**。

    ⚠️ 这条守的是「判据没有变成恒真式」—— 五个词表加起来如果什么都能命中，
       那 ② 就再也判不出任何东西，而那种失效**不报错**。
    """
    plain = "最近30天营业额 ¥21,029,616.29，订单 58,518 单，每单平均 ¥359.37。"
    assert _hits(plain) == set(), f"念一遍数字也命中了: {_hits(plain)}"


# ── ② 的豁免：清单/计数类问题「不多说」是合法状态 ─────────────────────────

def test_fact_lookup_intents_are_exempt_by_intent_not_keyword():
    """⛔ 用 **intent** 判豁免，不用问句关键词。

    📏 「一共有多少家店」答「10 家 + 名单」—— 多说一层是画蛇添足。
    ▎与「算『缺了多少』之前先问『这里的空是不是一种合法状态』」同一条纪律。
    """
    assert "RESTAURANT_OPS_STORE_DIRECTORY" in _FACT_LOOKUP_INTENTS
    # 🔴 阴性对照：⛔ 不许把有分析价值的意图也豁免掉
    for must_not in ("RESTAURANT_OPS_SALES_SUMMARY",
                     "RESTAURANT_OPS_GROSS_MARGIN",
                     "RESTAURANT_OPS_STORE_MARGIN",
                     "RESTAURANT_OPS_BUSINESS_OPTIMIZATION"):
        assert must_not not in _FACT_LOOKUP_INTENTS, (
            f"把一个该被 ② 量的意图豁免掉了: {must_not}"
        )
    assert len(_FACT_LOOKUP_INTENTS) <= 3, (
        f"豁免表长出来了，逐条读过再加: {sorted(_FACT_LOOKUP_INTENTS)}"
    )


# ── ⑤ 的动作词表：补的三条**有 prod 原文为证** ───────────────────────────

def test_the_new_action_phrases_come_from_real_output():
    """🔴 这三条是**词表没跟上新上线的文案**，⛔ 不是「加词逼近」。

    📏 prod 原文（部署后真跑）：

        「翻台率怎么样」   n=156 → 「眼下最接近的是订单数。**想看的话**，
                                    **说「订单数」就行**。」   ← #2841
        「哪个供应商报价最贵」n=196 → 「**你要做的**：让采购在录单据时把
                                       供应商填上。」          ← #2831
        「食材成本占营收多少」      → 「这段时间的**头和尾各盘一次库**。」← #2829

    旧词表把第一条判成「给了动作=False」—— 而那句正是**他要干什么**。
    """
    for clause, why in (
        ("眼下最接近的是订单数。想看的话，说「订单数」就行。", "#2841 最接近替代"),
        ("你要做的：让采购在录单据时把供应商填上。", "#2831 供应商三态"),
        ("所以这个数的前提是：这段时间的头和尾各盘一次库。", "#2829 成本率去黑话"),
    ):
        assert any(w in clause for w in _ACTION_WORDS), (
            f"这句有明确动作却没被认出来（{why}）：{clause}"
        )


def test_the_action_wordlist_is_not_a_blanket():
    """🔴 阴性对照：一句**没有任何动作**的话不该命中。

    ⚠️ 补词最容易滑成把判据变成恒真式 —— 那种失效不报错，
       只是从此再也判不出「没给动作」。
    """
    for clause in ("这个数算不出来。",
                   "翻台率缺少桌台和开台时间。",
                   "近 30 天营业额 ¥21,029,616.29。"):
        assert not any(w in clause for w in _ACTION_WORDS), (
            f"一句没有动作的话被判成有动作: {clause}"
        )
