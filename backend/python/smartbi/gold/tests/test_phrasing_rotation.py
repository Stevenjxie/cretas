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


# ─── 收尾建议句的轮换 + 免责义务 ────────────────────────────────────────────

def test_every_variant_in_a_pool_carries_its_required_marker():
    """🔴 承重的一条：**说法可以变，「说了这件事」不可以省**。

    折扣的收尾句背着「不能据此说折扣带来了增量营收」这条边界；
    比价的背着「价差不等于能省」。轮换措辞时如果某一条变体漏了这句话，
    那一天的老板就会读到一段**没有边界声明**的结论 —— 而且因为按天轮换，
    这种漏会**间歇性出现**，最难被发现。
    """
    from smartbi.gold.restaurant import phrasing

    for pool_name, tokens in phrasing.REQUIRED_TOKENS.items():
        pool = getattr(phrasing, pool_name)
        assert pool, f"{pool_name} 是空的"
        for i, variant in enumerate(pool):
            assert any(t in variant for t in tokens), (
                f"{pool_name}[{i}] 缺少必含标记 {tokens}: {variant[:40]}"
            )


def test_closing_pools_rotate_across_days_and_hold_within_a_day():
    """同一天同一问句必须同一句；跨天要真的换过。"""
    import datetime as dt

    from smartbi.gold.restaurant import phrasing

    for pool_name in ("DISH_MARGIN_CLOSING", "CHANNEL_MIX_CLOSING",
                      "DISCOUNT_CLOSING", "SUPPLIER_PRICE_CLOSING"):
        pool = getattr(phrasing, pool_name)
        key = f"{pool_name}|毛利最低的菜品有哪些"
        day = dt.date(2026, 8, 8)
        first = phrasing.pick_variant(pool, key, today=day)
        assert phrasing.pick_variant(pool, key, today=day) == first, "同日必须稳定"

        seen = {phrasing.pick_variant(pool, key, today=day + dt.timedelta(days=d))
                for d in range(30)}
        assert len(seen) > 1, f"{pool_name} 30 天里一次都没换过说法"


def test_closing_pools_never_leak_numbers():
    """⛔ 变体里不许出现裸数字 —— 数字只能来自事实，不能来自措辞。

    唯一允许的是占位符（{n}/{scope}/{window}）和单位示例里的「7天」这类。
    """
    import re

    from smartbi.gold.restaurant import phrasing

    for pool_name in ("DISH_MARGIN_CLOSING", "CHANNEL_MIX_CLOSING",
                      "DISCOUNT_CLOSING", "SUPPLIER_PRICE_CLOSING"):
        for variant in getattr(phrasing, pool_name):
            stripped = re.sub(r"\{[a-z_]+\}", "", variant)
            assert not re.search(r"\d", stripped), (
                f"{pool_name} 变体里出现了数字: {variant[:50]}"
            )


def test_resolver_closing_helper_is_deterministic_and_llm_free():
    """resolver 侧的 `_closing` 必须纯查表：不连库、不调模型。"""
    import inspect

    from smartbi.gold.restaurant import restaurant_ops_router as R

    # ⚠️ 只看**代码体**，不看 docstring —— docstring 里正写着「不调 LLM」，
    #    连它一起搜就是在量措辞而不是量行为（第一版就这么写，当场自己红了）。
    src = inspect.getsource(R._closing)
    body = src.split('"""')[-1] if src.count('"""') >= 2 else src
    for banned in ("call_chain", "llm_router", "await ", "acquire("):
        assert banned not in body.lower(), f"_closing 代码体里出现了 {banned}"
    a = R._closing("DISCOUNT_CLOSING", "折扣力度多大")
    assert a and a == R._closing("DISCOUNT_CLOSING", "折扣力度多大")
    assert R._closing("这个池不存在", "x") == "", "取不到池要 fail-open 返回空串"
