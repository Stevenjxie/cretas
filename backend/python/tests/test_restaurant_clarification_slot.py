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

    基线现在是 **0** —— 10 处散落的比较已全部收敛:
        2 处读 `spec.clarification_question` → 改读 `spec.missing_slot`
        8 处读**函数参数**(上一轮持久化的字符串, 老会话没有新字段)
            → 改读 `_slot_of_clarification(...)`, 字符串知识只剩那一个边界函数

    ⚠️ 两个计数陷阱, 我都踩过:
      1. 按**出现次数**不是行数 —— `grep -c` 数行会漏掉同一行里的第二处(数成 10);
      2. 必须**排除注释** —— 上一版把我自己写在文档注释里的示例也数了进去(虚高成 11)。
    守卫测试的基线必须精确到它真正要防的粒度, 否则它自己就是个假信号。

    这条防的是「换掉一部分之后又新增身份比较」—— 两套判据并存时, 合成追问会
    静默穿过其中一套, 正是「一个闸由多处承载」的成因。
    """
    src = inspect.getsource(R)
    code = "\n".join(l for l in src.split("\n") if not l.strip().startswith("#"))
    identity_checks = re.findall(
        r"clarification_question\s*[!=]=\s*"
        r"(?:TIME_CLARIFICATION_QUESTION|STORE_SCOPE_CLARIFICATION_QUESTION)",
        code,
    )
    assert not identity_checks, (
        f"又出现了 {len(identity_checks)} 处直接比较反问字符串的判据(基线 0)。\n"
        "读 spec.missing_slot; 只拿得到字符串时(continuation 的持久化状态)走 "
        "_slot_of_clarification() —— 字符串知识必须只有那一处。"
    )


def test_the_boundary_converter_is_the_only_place_that_knows_the_strings():
    """边界函数必须存在且真的在做映射 —— 它是合成追问唯一要改的地方。"""
    assert hasattr(R, "_slot_of_clarification"), "边界转换函数不见了"
    assert R._slot_of_clarification(R.TIME_CLARIFICATION_QUESTION) == "time"
    assert R._slot_of_clarification(R.STORE_SCOPE_CLARIFICATION_QUESTION) == "store"
    assert R._slot_of_clarification(None) is None
    assert R._slot_of_clarification("随便一句别的话") is None


# ── 被替换的 continuation 分支必须有行为覆盖 ─────────────────────────────
#
# ⚠️ 加这一节的原因: 把 10 处字符串判据换成读槽位之后, 我跑了变异检验
# (边界函数改成恒返回 None) —— **312 条既有 intent 用例里只有 1 条红**, 而且那条
# 还是本文件自己的。也就是说那 8 处 continuation 分支**根本没有行为覆盖**:
# 「6083 passed」证明的是导入和类型没坏, **不能证明这几处换对了**。
#
# 这正是本仓反复记的那条 ——「套件绿经常什么都没证明」, 这次落在我自己的重构上。

def test_time_button_continuation_still_routes_after_the_refactor():
    """时间按钮分支: 上一轮问的是时间, 本轮答一个时间按钮 → 路由回原 intent。"""
    query = "哪个菜卖得好"
    assert R._approved_exact_shape(query) is not None, "夹具失效: 该问句不再是已批准路由"

    routed = R._approved_exact_continuation_route(
        query, "最近30天", R.TIME_CLARIFICATION_QUESTION,
    )
    assert routed == "RESTAURANT_OPS_GROSS_MARGIN", routed


def test_continuation_declines_when_there_was_no_clarification():
    """没有上一轮反问时不许路由 —— 边界函数返回 None 的那条路。"""
    assert R._approved_exact_continuation_route("哪个菜卖得好", "最近30天", None) is None


def test_continuation_declines_for_an_unknown_clarification_string():
    """老会话/别处存下来的任意字符串不能被当成时间或门店反问。"""
    assert R._approved_exact_continuation_route(
        "哪个菜卖得好", "最近30天", "随便一句别的反问",
    ) is None


def test_time_branch_rejects_a_non_time_answer():
    """分支选对了还不够 —— 时间反问下只接受四个时间按钮。"""
    assert R._approved_exact_continuation_route(
        "哪个菜卖得好", "全部门店", R.TIME_CLARIFICATION_QUESTION,
    ) is None
