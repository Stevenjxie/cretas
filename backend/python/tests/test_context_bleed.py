"""「上下文串了」的闸 —— 以及**先证明它能红**。

⛔ 这个文件的第一要务不是「断言通过」，是**证明断言拦得住**。
   我们在这个坑里栽过四次（恒真式 / mock 没打桩 / 环境让它不可能红 / skip），
   所以每条阴性断言都配一条阳性对照，两个「会串」的形状各配一条变异。
"""
from __future__ import annotations

from dataclasses import dataclass, field, replace
from typing import Any, Optional, Tuple

import pytest

from smartbi.gold.restaurant import context_bleed as cb


@dataclass(frozen=True)
class _Spec:
    """只带参与比较的槽位 —— 与 `TRACKED_SLOTS` 同名。

    ⚠️ 用一个**最小的替身**而不是真的 `RestaurantQuerySpec`：后者要 40+ 个
       必填字段，构造它会把这个文件变成一份规格快照，而规格一改测试就红 ——
       那时红的原因与「串」无关。⛔ 但字段名必须与真规格一致，
       下面 `test_tracked_slots_all_exist_on_the_real_spec` 守这一条。
    """
    store_scope: Optional[str] = None
    store_slots: Tuple[str, ...] = ()
    store_slot: Optional[str] = None
    dish_slot: Optional[str] = None
    dimensions: Tuple[str, ...] = ()
    metrics: Tuple[str, ...] = ()
    requested_metrics: Tuple[str, ...] = ()
    date_range: Tuple[Any, Any] = (None, None)
    window_label: str = ""
    comparison: Optional[str] = None
    comparison_label: Optional[str] = None
    excluded_entities: Tuple[str, ...] = ()
    ranking_direction: Optional[str] = None
    analysis_action: str = "lookup"
    compare_stores: bool = False


def test_tracked_slots_all_exist_on_the_real_spec():
    """🔴 上面那个替身的字段名必须**真的**在规格上存在。

    ⛔ 少了这条，替身可以有一个规格里根本没有的槽位，
       于是这套闸在守一个不存在的东西 —— 而它永远是绿的。
    """
    import dataclasses
    from smartbi.gold.restaurant.restaurant_intent import RestaurantQuerySpec

    real = {f.name for f in dataclasses.fields(RestaurantQuerySpec)}
    missing = [s for s in cb.TRACKED_SLOTS if s not in real]
    assert not missing, f"这些槽位规格上没有: {missing}"
    fake = {f.name for f in dataclasses.fields(_Spec)}
    assert set(cb.TRACKED_SLOTS) <= fake, (
        f"替身少了这些槽位: {sorted(set(cb.TRACKED_SLOTS) - fake)}")


# ── 形状一：转录里那个原形 ────────────────────────────────────────────────
#   history 里塞进一堆**不相干**的对象，然后问一个**自足**的问题。
#   串的表现 = spec 里出现了 history 里的东西。
def test_shape_1_self_contained_question_must_not_inherit_anything():
    """🔴 自足的问句（没有指代/省略）**不该继承任何东西**。

    客户原话：「你回到这个问题的时候，他还在扯你前面提到的一些，把它汇总在里面，
    压根跟我问的这些问题没有任何关系。」
    """
    q = "这个月毛利多少"
    assert cb.question_is_self_contained(q), "夹具问句本身含指代 —— 这条会空转"

    clean = _Spec(metrics=("gross_profit",), window_label="本月")
    # 没串: 两边一样
    r = cb.detect(q, clean, clean)
    assert not r["bleed"], r
    assert not r["diff"], "两边完全一样时差异集合该是空的"

    # 🔴 串了: history 里的门店/菜系被掺进来
    bled = replace(clean, store_scope="single", store_slots=("模拟·徐汇美罗城店",),
                   dish_slot="酸菜鱼")
    r2 = cb.detect(q, bled, clean)
    assert r2["bleed"], (
        "自足问句继承了 history 的门店和菜, 判据却说没串 —— 它守不住任何东西")
    assert set(r2["bleed"]) == {"store_scope", "store_slots", "dish_slot"}, r2


def test_shape_1_positive_control_ellipsis_question_may_inherit():
    """✅ 阳性对照：**带指代的问句继承是对的**，不许被判成串。

    ⛔ 少了这条，「什么都不继承」也能让上面那条全绿 ——
       那是把功能删了，不是修好了。
    """
    q = "那上个月呢"
    assert not cb.question_is_self_contained(q)

    plain = _Spec(metrics=("revenue",))
    inherited = replace(plain, store_scope="single",
                        store_slots=("模拟·徐汇美罗城店",))
    r = cb.detect(q, inherited, plain)
    assert r["diff"], "继承确实发生了才有对照可言"
    assert not r["bleed"], (
        f"带「呢」的问句继承门店被判成串了: {r['bleed']} —— "
        f"那会把「能带过去」这个功能一起判死")


# ── 形状二：覆盖式 ───────────────────────────────────────────────────────
#   先问「A 店营收」，再问「**全部门店**毛利」—— 第二问明确覆盖了范围。
def test_shape_2_explicit_scope_must_not_be_overridden_by_inheritance():
    """🔴 问句自己说了「全部门店」，继承值不许把它改掉。

    ⚠️ 这一类比「凭空多出来」更隐蔽：两边**都有值**，只是值不同。
    """
    q = "全部门店的毛利是多少"
    assert cb.question_sets_scope_explicitly(q)

    # 不带 history: 正确解析成全店
    right = _Spec(store_scope="all", metrics=("gross_profit",))
    # 🔴 带 history: 被上一轮的「A 店」覆盖了
    wrong = replace(right, store_scope="single",
                    store_slots=("模拟·徐汇美罗城店",))
    r = cb.detect(q, wrong, right)
    assert "store_scope" in r["bleed"], (
        f"问句写着「全部门店」却被继承改成单店, 判据没抓到: {r}")
    assert r["bleed"]["store_scope"] == ("all", "single"), r


def test_shape_2_positive_control_scope_may_be_filled_when_unspecified():
    """✅ 阳性对照：问句**没说**范围时，继承来的范围是合理的补全。"""
    # ⚠️ 用一个**真的带指代**的问句。第一版写的是「毛利多少」——
    #    它不含任何指代, 按判据原文属于「自足」, 什么都不该继承。
    #    我当时按直觉认为「没说范围就该继承」而写了这条对照, 与判据冲突。
    #    ⇒ 判据没错, **我的对照错了**。那个直觉本身是一条要报回去的发现:
    #      仓里确实有刻意的隐式范围继承(`_inherited_store_scope`), 它会被
    #      这套判据判成串 —— 见报告。
    q = "那再看看毛利呢"
    assert not cb.question_sets_scope_explicitly(q)
    assert not cb.question_is_self_contained(q)

    plain = _Spec(metrics=("gross_profit",))
    filled = replace(plain, store_scope="single",
                     store_slots=("模拟·徐汇美罗城店",))
    r = cb.detect(q, filled, plain)
    assert r["diff"], "没有差异就没有对照"
    assert not r["bleed"], (
        f"带指代的问句继承范围却被判成串: {r['bleed']}")


# ── 🔴 变异：证明这套判据真的能红 ────────────────────────────────────────
@pytest.mark.parametrize("shape,mutate", [
    ("形状一(自足问句被掺入)", lambda: (
        "这个月毛利多少",
        _Spec(metrics=("gross_profit",), store_slots=("别家店",)),
        _Spec(metrics=("gross_profit",)))),
    ("形状二(明确范围被覆盖)", lambda: (
        "全部门店的毛利是多少",
        _Spec(store_scope="single", metrics=("gross_profit",)),
        _Spec(store_scope="all", metrics=("gross_profit",)))),
])
def test_mutation_a_real_bleed_is_detected(shape, mutate):
    """🔴 两个形状各造一次**真的串**，判据必须报出来。

    ⛔ 这不是「断言通过」，是**证明断言拦得住** —— 红不了的判据守不住任何东西。
    """
    q, with_h, without_h = mutate()
    r = cb.detect(q, with_h, without_h)
    assert r["bleed"], f"{shape}: 造了一次真的串, 判据说没串 —— 它是恒真式"


def test_mutation_disabling_the_explanation_makes_everything_a_bleed(monkeypatch):
    """🔴 反向变异：把「解释得通」全部关掉，**合法继承也会被判成串**。

    ⚠️ 它证明的是判据**不是恒假**（不是「什么都说串」）——
       与上面那条一起，两个方向都夹住。
    """
    monkeypatch.setattr(cb, "explain", lambda *a, **k: None)
    q = "那上个月呢"
    plain = _Spec(metrics=("revenue",))
    inherited = replace(plain, store_scope="single")
    assert cb.detect(q, inherited, plain)["bleed"], "变异没生效"


def test_derived_fields_are_not_tracked():
    """⚠️ `plan_hash` / `confidence` 这类派生字段两边必然不同 ——
    放进 `TRACKED_SLOTS` 会让差异集合永远非空, 那就成了噪音。
    """
    for noisy in ("plan_hash", "confidence", "source_tier", "planner_authority",
                  "resolver_query_seed"):
        assert noisy not in cb.TRACKED_SLOTS, (
            f"{noisy} 是派生字段, 不该参与「串」的判定")
