"""`all` 是「不分组」，不是一种分组 —— 拿它去查「能按什么分组」的表是范畴错误。

## 被守的行为

`_RESOLVER_DIMENSIONS` 列的是每个 resolver **能按什么分组**。
`Dimension("all").group_expr is None` —— 它不是分组，所以**不在也不可能在**
任何一个 resolver 的集合里。于是 `{'all'} ⊆ {'store'}` 恒不成立，
**任何「全店合计」问句都会被拒**，与指标无关、与 resolver 无关。

prod 实测（2026-08-13）：「今天赚多少」「今天营业额多少」「今天多少单」三题
同一个形状被拒，而日结推送用同一批数字答得好好的 —— 店长会问
「你晚上推给我的那个数，我白天问你你怎么不知道」。

## ⛔ 放行条件是两条，不是一条

1. 差集**恰好**是 `{all}` —— 其余任何一个不被支持的分组照样拦
2. 该聚合形态 `needs_dimension is False` —— 有些聚合在不分组时确实算不出

**本模块最重要的是那条变异对照**：真的 mismatch（差集是 `{store}`）必须照样红。
没有它就分不清「修好了」和「拆了」。
"""
import pytest

from smartbi.gold.restaurant import restaurant_intent_service as ris
from smartbi.gold.restaurant.restaurant_intent import RestaurantQuerySpec
from smartbi.gold.restaurant.metric_registry import AGGREGATIONS, DIMENSIONS


def test_all_is_declared_as_not_a_grouping():
    """前提: `all` 在登记表里就是「不分组」。这条塌了，整个推理就不成立。"""
    assert DIMENSIONS["all"].group_expr is None, (
        "`all` 有了分组表达式 —— 它不再是「不分组」，本模块的推理要重做")


def test_all_appears_in_no_resolver_capability_set():
    """`all` 不在任何 resolver 的能力集里 —— **也不可能在**。

    这条把「范畴错误」这个论断本身钉死: 只要它成立,
    子集判断对 `all` 就恒不成立。
    """
    for code, dims in ris._RESOLVER_DIMENSIONS.items():
        assert "all" not in dims, (
            f"{code} 把 `all` 写进了能力集 —— 那是把「不分组」当成一种分组登记, "
            f"能力表会开始说谎")


def _spec(dimensions, aggregation="summary", metrics=("revenue",)):
    """用**真的** `RestaurantQuerySpec` 构造，不自己捏一个鸭子类型。

    🔴 形态 B‴: 桩的形状要能在生产上出现。第一版我手写了个 `_Spec`，
       缺一个字段就 `AttributeError` 一次(plan_version 就是这么冒出来的)，
       而且它永远不会长得跟真规格一样 —— 真规格加了字段，这个桩不会跟着变，
       于是断言慢慢跑在一个越来越不存在的形状上。
    ⚠️ 必填字段按类型填空值，可选字段用 dataclass 自己的默认值。
    """
    import dataclasses

    kwargs = {}
    for f in dataclasses.fields(RestaurantQuerySpec):
        if f.default is not dataclasses.MISSING or                 f.default_factory is not dataclasses.MISSING:  # type: ignore[misc]
            continue
        ann = str(f.type)
        if "Tuple" in ann or "tuple" in ann:
            kwargs[f.name] = ()
        elif "bool" in ann:
            kwargs[f.name] = False
        elif "float" in ann or "int" in ann:
            kwargs[f.name] = 0
        else:
            kwargs[f.name] = ""
    spec = RestaurantQuerySpec(**kwargs)
    return dataclasses.replace(
        spec,
        intent="RESTAURANT_OPS_SALES_SUMMARY",
        planned_intents=("RESTAURANT_OPS_SALES_SUMMARY",),
        planner_authority=sorted(ris.TRUSTED_PLANNER_AUTHORITIES)[0],
        plan_hash="h",
        # 🔴 少这一行整个函数第一句就 `return None`, 一条断言都到不了被测判断。
        #    实测踩到: 两条变异对照「红」了, 而红的原因是桩没走到闸, 不是闸没守住。
        plan_version="restaurant-query-plan-v2",
        dimensions=tuple(dimensions),
        metrics=tuple(metrics),
        aggregation=aggregation,
    )


def _mismatch(spec):
    return ris._execution_mismatch(
        spec, spec.planned_intents,
        dish_mention=None, store_mention=None, store_dish=None)


def test_the_fixture_actually_reaches_the_dimension_check():
    """🔴 阳性对照: 证明这个桩**能走到**被测的那道判断。

    `_execution_mismatch` 第一句是 `if spec.plan_version != "...": return None` ——
    桩少填这个字段, 下面所有断言都在测一个立刻返回 None 的函数。
    ⛔ 没有这条, 「变异不红」和「桩没到达」分不开(形态 C″)。
    """
    spec = _spec(("ingredient",))
    assert spec.plan_version == "restaurant-query-plan-v2"
    assert _mismatch(spec) is not None, "桩没走到维度判断 —— 后面的断言都没有意义"


def test_chain_total_is_allowed():
    """「全店合计」不再被拒。"""
    assert _mismatch(_spec(("all",))) is None


def test_a_real_dimension_mismatch_is_still_blocked():
    """🔴 变异对照 —— 这条比上面那条重要。

    差集是 `{ingredient}`（SALES_SUMMARY 只服务 `store`）时**必须照样红**。
    ⛔ 没有这条就分不清「修好了范畴错误」和「把闸拆了」。
    """
    verdict = _mismatch(_spec(("ingredient",)))
    assert verdict is not None, "真的维度不匹配没被拦住 —— 闸被拆了"


def test_all_plus_an_unsupported_dimension_is_still_blocked():
    """⛔ 差集必须**恰好**是 `{all}`。混着一个不被支持的分组时照样拦 ——
    否则「顺带夹一个 all 就能绕过整道闸」。"""
    verdict = _mismatch(_spec(("all", "ingredient")))
    assert verdict is not None, (
        "夹带一个不被支持的分组就绕过了整道闸 —— 放行条件写得太宽")


def test_supported_grouping_still_passes():
    """阳性对照: 本来就被支持的分组不受影响。"""
    assert _mismatch(_spec(("store",))) is None


def test_grouping_aggregation_with_all_is_not_waved_through():
    """条件②: 聚合形态需要分组时，`all` 不放行。

    ⚠️ 用登记表已有的 `needs_dimension` 判，不新造假设。
    ⛔ 这条防的是「不分组却要排名」这种规格被放进去、执行期再炸。
    """
    need_dim = [k for k, a in AGGREGATIONS.items() if a.needs_dimension]
    assert need_dim, "没有需要分组的聚合形态 —— 这条对照失去意义"

    # ⚠️ `spec_to_cell` 会把「需要分组 + dim=all」回退成 summary(那是它的既有行为,
    #    对用户更友好)。所以这里直接验判据函数本身, 而不是绕一层去验 mismatch ——
    #    绕一层会得到「回退之后当然放行」这个恒真结论。
    for k in need_dim:
        spec = _spec(("product",), aggregation=k)
        assert ris._aggregation_needs_no_grouping(spec) is False, (
            f"{k} 需要分组, 却被判成「不分组也算得出」")


def test_every_capability_comparison_strips_non_grouping_dims():
    """🔴 **同一个比较在三处出现**, 修一处不够。

    2026-08-13 实测: 修好执行前那道闸之后, 拒答只是**挪到了 contract-repair**
    —— 同一个范畴错误的第二处。判据: 凡是拿维度去比 `_RESOLVER_DIMENSIONS`
    的地方, 都必须先减掉「不分组」的那些。

    ⚠️ 这条是**源码闸**(代理判据, 标出来): 它证明每个比较点都调了那个 helper,
       不证明调对了 —— 调对了由上面那些行为断言守。
    """
    import inspect

    from smartbi.gold.restaurant import restaurant_intent as ri

    for mod in (ris, ri):
        src = inspect.getsource(mod)
        for i, line in enumerate(src.splitlines(), 1):
            if "_RESOLVER_DIMENSIONS.get(" not in line:
                continue
            if "issubset" not in line and "issubset" not in src.splitlines()[i - 2]:
                continue  # 不是子集比较(比如只是取出来打日志)
            window = "\n".join(src.splitlines()[max(0, i - 6):i + 2])
            assert "grouping_dimensions" in window or "sorted(" in window, (
                f"{mod.__name__}:{i} 拿维度比能力表却没先减掉「不分组」——\n{window}")


def test_non_grouping_set_is_derived_not_hardcoded():
    """⛔ 「哪些是不分组」从登记表推导, 不写死名字。"""
    from smartbi.gold.restaurant.metric_registry import (
        grouping_dimensions, non_grouping_dimensions)

    assert non_grouping_dimensions() == frozenset(
        k for k, d in DIMENSIONS.items() if d.group_expr is None)
    assert "all" in non_grouping_dimensions()
    # 真正的分组维度不许被减掉 —— 否则这个 helper 会把闸悄悄拆光
    assert grouping_dimensions(("all", "store", "product")) == ("store", "product")


def test_summary_is_the_one_that_needs_no_grouping():
    """判据来源自查: `summary` 确实声明了 `needs_dimension=False`。"""
    assert AGGREGATIONS["summary"].needs_dimension is False


def test_criterion_reuses_the_single_spec_to_cell_definition():
    """⛔ 不许在这里再写一份「规格 → 聚合形态」的推断。

    两份推断迟早对同一个规格给出不同答案，症状是「校验放行了、执行却拒绝」。
    """
    import inspect

    src = inspect.getsource(ris._aggregation_needs_no_grouping)
    assert "spec_aggregation_key" in src, "没有复用唯一那份定义"
    for forbidden in ("ranking_direction", "analysis_action"):
        assert forbidden not in src, (
            f"这里出现了 {forbidden!r} —— 又写了一份聚合形态推断")
