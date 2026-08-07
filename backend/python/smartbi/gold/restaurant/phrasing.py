"""措辞轮换：同一份数据，换着说法讲。

🔴 Steve 2026-08-07 拍板：
> 「答案一样是指里面的数据是一样的，但是回答的方式可以不一样。这样子的话…
>   让她觉得我真的是在跟一个人说话。」
> 「我们还是用这种多套模板轮换吧…**不能说让 LLM 去做这种措辞的不确定性**，
>   这个有延迟，而且很没必要。」

⛔ 三条硬约束：
  1. **只换措辞，绝不碰数字。** 变体里不许出现任何数值占位以外的数字。
  2. **零 LLM、零延迟。** 纯查表 + 取模。
  3. **可复现。** 同一个问句在**同一天**必须给出同一句措辞 —— 否则用户刷新一次
     发现说法变了，会怀疑数据也变了。跨天才换，既有变化又不制造疑心。

判据：变化的价值在「读起来像人」，而不在「每次都不一样」。
把随机性引进来会让测试变脆、也会让用户困惑；用 (问句, 日期) 做种子两者都避开了。
"""
from __future__ import annotations

import datetime as _dt
import hashlib
from typing import Optional, Sequence


def pick_variant(
    variants: Sequence[str],
    key: str,
    *,
    today: Optional[_dt.date] = None,
) -> str:
    """从若干等价说法里挑一句。

    :param variants: 等价措辞。**必须语义相同**——这里不做校验，因为「意思一样」
                     没法用代码判；靠的是写变体的人和 review。
    :param key:      稳定种子（一般是归一化后的问句）。同 key 同日 → 同一句。
    :param today:    注入日期便于测试；默认当天。

    空列表返回空串（fail-open：宁可没有措辞，也不要抛异常打断问答）。
    """
    if not variants:
        return ""
    day = (today or _dt.date.today()).isoformat()
    digest = hashlib.sha256(f"{key}\x1f{day}".encode("utf-8")).digest()
    return variants[digest[0] % len(variants)]


#: 顺带提示的引子。⚠️ `{n}` 是条数占位，**唯一允许的数字来源**。
HINT_LEAD_IN = (
    "⚠️ 顺带 {n} 件事：",
    "⚠️ 另外有 {n} 件事想提醒你：",
    "⚠️ 还有 {n} 处值得看一眼：",
    "⚠️ 顺手帮你留意到 {n} 件事：",
)

#: 门店范围取了默认值时的披露。⛔ 说法可以变，「说了这件事」不可以省。
STORE_SCOPE_DISCLOSURE = (
    "（范围：{scope}。想看单店直接说门店名即可。）",
    "（以上是{scope}的合计。要看某一家，说门店名就行。）",
    "（这里按{scope}算的。想单独看一家店，报个店名给我。）",
)

#: 时间窗取了默认值时的披露。同上：措辞可变，**披露本身不可省**。
TIME_RANGE_DISCLOSURE = (
    "（你没有指定时间范围，以上按**{window}**计算；想看别的区间可以直接说，例如「最近7天」。）",
    "（时间范围你没说，我按**{window}**算的；要换区间直接讲，比如「最近7天」。）",
    "（默认取了**{window}**这个区间；想看别的时间段，说一声就行，例如「最近7天」。）",
)
