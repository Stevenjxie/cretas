"""措辞可以换，数字不许变。

🔴 Steve 2026-08-07：「答案一样是指里面的**数据**是一样的，但是回答的方式可以不一样…
让她觉得我真的是在跟一个人说话。」并明确：**不让 LLM 碰措辞**（有延迟、没必要）。

本文件钉住三件事：
  1. 同一问句**同一天**说法固定 —— 刷新一次说法就变会让用户怀疑数据也变了；
  2. 跨天会换 —— 否则「轮换」等于没做；
  3. **变体里不含任何硬编码数字** —— 数字只能来自占位符填充。
"""
import datetime as dt
import re

import pytest

from smartbi.gold.restaurant import phrasing
from smartbi.gold.restaurant.phrasing import (
    HINT_LEAD_IN,
    STORE_SCOPE_DISCLOSURE,
    TIME_RANGE_DISCLOSURE,
    pick_variant,
)

ALL_POOLS = {
    "HINT_LEAD_IN": HINT_LEAD_IN,
    "STORE_SCOPE_DISCLOSURE": STORE_SCOPE_DISCLOSURE,
    "TIME_RANGE_DISCLOSURE": TIME_RANGE_DISCLOSURE,
}


def test_same_query_same_day_is_stable():
    """同一天同一问句 -> 逐字相同。用户刷新看到说法变了会怀疑数据也变了。"""
    day = dt.date(2026, 8, 7)
    picks = {pick_variant(TIME_RANGE_DISCLOSURE, "最近损耗情况怎么样", today=day)
             for _ in range(20)}
    assert len(picks) == 1


def test_it_actually_rotates_across_days():
    """🔴 阴性对照：跨天必须真的会换，否则「轮换」是个空壳。

    没有这条，`pick_variant` 直接 `return variants[0]` 也能让上面那条通过。
    """
    q = "最近损耗情况怎么样"
    seen = {
        pick_variant(TIME_RANGE_DISCLOSURE, q, today=dt.date(2026, 8, d))
        for d in range(1, 29)
    }
    assert len(seen) > 1, "一个月内一句都没换过 —— 轮换没生效"


def test_different_queries_get_different_wording():
    """不同问句之间也应有差异，否则一次对话里三句话措辞完全一样。"""
    day = dt.date(2026, 8, 7)
    seen = {
        pick_variant(TIME_RANGE_DISCLOSURE, q, today=day)
        for q in ("营收趋势怎么样", "哪个菜卖得最好", "最近损耗情况怎么样",
                  "加权毛利率是多少", "库存有什么要注意的")
    }
    assert len(seen) > 1


@pytest.mark.parametrize("name", sorted(ALL_POOLS))
def test_no_hardcoded_numbers_in_any_variant(name):
    """⛔ 变体里不许出现硬编码数字 —— 数字只能由占位符填。

    这是「措辞可变、数字不可变」的机械保证：只要变体里没有数字，
    换措辞就**不可能**换掉数字。
    """
    for variant in ALL_POOLS[name]:
        # 先挖掉占位符（`{n}` / `{window}` / `{scope}`），再找残留数字。
        stripped = re.sub(r"\{[a-z_]+\}", "", variant)
        digits = re.findall(r"\d+", stripped)
        allowed = {"7"}  # 「例如「最近7天」」是给用户的示例，不是本轮数据
        assert set(digits) <= allowed, (
            f"{name} 的变体里有硬编码数字 {digits}: {variant!r} —— "
            "数字必须来自占位符，否则换措辞就会换掉数字"
        )


@pytest.mark.parametrize("name", sorted(ALL_POOLS))
def test_every_variant_keeps_the_placeholders(name):
    """所有变体必须带同一组占位符 —— 少一个就会漏掉披露里的关键信息。"""
    pool = ALL_POOLS[name]
    keys = [set(re.findall(r"\{([a-z_]+)\}", v)) for v in pool]
    assert all(k == keys[0] for k in keys), (
        f"{name} 的变体占位符不一致: {keys} —— "
        "有的变体会漏掉窗口/范围/条数, 那就不是等价说法了"
    )


def test_empty_pool_fails_open():
    """空池返回空串，不抛异常 —— 措辞是锦上添花，不该打断问答。"""
    assert pick_variant((), "任意") == ""


def test_disclosure_still_names_the_window(monkeypatch):
    """端到端：不论轮到哪一句，**窗口值**都必须出现且只有一个来源。"""
    from smartbi.gold.restaurant.restaurant_intent import DEFAULT_TIME_PHRASE
    from smartbi.gold.restaurant.restaurant_intent_service import (
        _time_range_disclosure,
    )

    class _Spec:
        time_range_defaulted = True
        resolver_query_seed = "最近损耗情况怎么样"

    for idx in range(len(TIME_RANGE_DISCLOSURE)):
        monkeypatch.setattr(
            phrasing, "pick_variant",
            lambda variants, key, today=None, _i=idx: variants[_i],
        )
        text = _time_range_disclosure(_Spec())
        assert DEFAULT_TIME_PHRASE in text, f"第 {idx} 个变体漏掉了窗口值"
        assert text.startswith("\n\n")
