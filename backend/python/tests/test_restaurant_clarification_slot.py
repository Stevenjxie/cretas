"""反问槽位标记 —— 把「问题字符串当类型系统」换掉的第一步。

## 为什么需要它

改动前, 管线拿**反问问题的字符串本身**做分支: 全仓 10 处
`clarification_question == TIME_CLARIFICATION_QUESTION` /
`== STORE_SCOPE_CLARIFICATION_QUESTION`。

后果是结构性的 —— **一轮只能携带一个缺失槽位**:
时间闸先跑并设 `clarification_needed`, 门店闸开头就
`or spec.clarification_needed → return`。于是 2026-08-01 实测「最近生意咋样」
要**三轮**才拿到答案(问门店 → 问时间 → 答), 口语类问句在 MOCK_REST 和
RES_3101_009 两个租户上都是 0/6。

想合成一句「门店和时间都告诉我」的追问, 那个新字符串会同时匹配不上上述 10 处
判断, **静默穿过全部** —— 所以合并追问不是"加个问句"的事, 得先有结构化判据。

## 这个文件钉的是「地基不许歪」

`missing_slot` 现在与字符串**严格同步**, 行为零变化。之后把 10 处身份比较逐个
换成读它时, 靠的就是这条不变量 —— 它一旦漂了, 换过去的那些判断会静默失效,
而那正是 2026-08-01 RBAC 泄露(声明了却做不到)和 #2076(签名没声明就静默丢弃)
同一族的错误。
"""
from __future__ import annotations

import inspect
import re

from smartbi.gold.restaurant import restaurant_intent as R


def test_slot_marker_and_question_never_disagree():
    """两个设置点必须同时写字符串和槽位, 不能只写一个。"""
    src = inspect.getsource(R)
    for const, slot in (
        ("TIME_CLARIFICATION_QUESTION", "time"),
        ("STORE_SCOPE_CLARIFICATION_QUESTION", "store"),
    ):
        # 只看**赋值**(设置反问), 不看比较(读反问)
        assigns = re.findall(
            rf"clarification_question\s*=\s*{const}[,\s]", src,
        )
        assert assigns, f"{const} 没有任何赋值点了? 表结构变了, 这条要跟着更新"
        marks = re.findall(rf'missing_slot\s*=\s*"{slot}"', src)
        assert len(marks) >= len(assigns), (
            f"{const} 有 {len(assigns)} 处赋值, 但只有 {len(marks)} 处标了 "
            f'missing_slot="{slot}" —— 设了反问却没标槽位, 会让读槽位的判断静默失效。'
        )


def test_spec_carries_the_slot_field():
    """字段必须在 spec 上, 否则设了也传不出去(#2076 的形态: 没声明就静默丢弃)。"""
    fields = R.RestaurantQuerySpec.__dataclass_fields__
    assert "missing_slot" in fields, "RestaurantQuerySpec 缺 missing_slot 字段"
    assert fields["missing_slot"].default is None


def test_string_identity_comparisons_are_counted():
    """把「还剩多少处靠字符串做判据」变成一个会说话的数字。

    基线是 **11 处出现**(分布在 10 行里 —— 有一行含两处比较)。
    ⚠️ 按**出现次数**而不是行数: `grep -c` 数的是行, 会漏掉同一行里的第二处,
    我第一次就是这么数成 10 的。守卫测试的基线必须精确到它真正要防的粒度。

    这条**不是**要求马上清零 —— 是让每次替换都有据可查, 且防止有人在换掉一部分
    之后又新增身份比较(那会让两套判据并存, 正是「一个闸由多处承载」的成因)。
    数字下降时改这里, 上升就红。
    """
    src = inspect.getsource(R)
    identity_checks = re.findall(
        r"clarification_question\s*[!=]=\s*"
        r"(?:TIME_CLARIFICATION_QUESTION|STORE_SCOPE_CLARIFICATION_QUESTION)",
        src,
    )
    assert len(identity_checks) <= 11, (
        f"靠反问字符串做判据的地方涨到了 {len(identity_checks)} 处(基线 11)。\n"
        "新增判断请读 spec.missing_slot, 别再比字符串 —— 两套判据并存时, "
        "合成追问会静默穿过其中一套。"
    )
