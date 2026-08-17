"""损耗按类型分：能力表说它不行，而它的输出里就印着那张分布。

## 缺陷（2026-08-17 prod 实测）

老板问「损耗都是哪些类型造成的」（DEMO_REST，`fact_restaurant_wastage`
214 行 / 5 种类型）：

> 你这句要按损耗类型来看，而这次这个数我只能按食材给你，**按损耗类型拿不到**。

而同一个 resolver 回答「损耗最多的食材是哪些」时，答案第二行就是：

> 损耗类型分布: 变质 ¥2491.45、过期 ¥1788.10、其他 ¥1424.00、破损 ¥1413.95、加工损耗 ¥1141.00

**5 种全带金额，与库里 `count(DISTINCT wastage_type)=5` 对得上。**
⇒ `_RESOLVER_DIMENSIONS` 比真实能力**窄**，于是把能算的说成算不出 ——
形态 D（同一件事两份会漂）最贵的一种长相：**产品在对老板说假话**。

## ⚠️ 为什么必须在**有数据**的租户上验

第一次是在 `RES_3101_009` 上量的，那家最近 30 天零损耗，那一栏印的是
「损耗类型分布: 无数据」——「没数据」和「出不来」在那上面长得一模一样。
换到 DEMO_REST 才看得见它真的填得满。
（本仓既有纪律：算得对不对一律在脏样本上验，MOCK_REST 只做冒烟。）
"""
from smartbi.gold.restaurant.metric_registry import DIMENSIONS
from smartbi.gold.restaurant.restaurant_intent import RestaurantQuerySpec
from smartbi.gold.restaurant.restaurant_intent_service import (
    _RESOLVER_DIMENSIONS,
    _dimension_gap_advice,
    _supported_dimensions,
)

WASTAGE = ("RESTAURANT_OPS_WASTAGE_TOP",)


def _spec(dimensions):
    required = {
        "intent": "RESTAURANT_OPS_WASTAGE_TOP", "domain": "restaurant",
        "date_range": (None, None), "window_label": "最近30天",
        "relative_window": None, "metrics": (), "wants_margin": False,
        "asks_profitability": False, "comparison": None,
        "confidence": 1.0, "source_tier": "test",
    }
    return RestaurantQuerySpec(**required, dimensions=tuple(dimensions))


class TestWastageTypeIsDeclared:
    def test_the_resolver_declares_the_breakdown_it_actually_prints(self):
        assert "wastage_type" in _RESOLVER_DIMENSIONS[WASTAGE[0]], (
            "损耗 resolver 的答案里印着「损耗类型分布」，能力表却没登记 —— "
            "于是「损耗都是哪些类型造成的」会被拒答成「按损耗类型拿不到」"
        )

    def test_asking_by_wastage_type_is_no_longer_a_gap(self):
        """阴性对照：撞不上缺口时**必须**返回空串。

        ⛔ 少了这条，上面那条断言可能只是因为 `_dimension_gap_advice`
           对什么都不说话。
        """
        assert _dimension_gap_advice(_spec(("wastage_type",)), WASTAGE) == ""
        assert _dimension_gap_advice(_spec(("ingredient",)), WASTAGE) == ""
        assert _dimension_gap_advice(
            _spec(("ingredient", "wastage_type")), WASTAGE) == ""

    def test_a_dimension_it_really_cannot_do_is_still_a_gap(self):
        """阳性对照：拿一个它**真的**出不来的维度，那条必须照旧拒。

        少了这条，「不再拒答」可能是因为闸被整体打死了。

        ⚠️ 2026-08-17 换了例子：原来用的是「按门店」，而当天门店被做出来了
           （`V20261101_16` + ops_writer 接线 + `wastage_cost_by_store`）。
           ⛔ 不是删掉这条对照 —— 换成损耗**确实**拆不出的那一层（渠道：
           损耗单据里没有堂食/外卖这回事）。
        """
        advice = _dimension_gap_advice(_spec(("channel",)), WASTAGE)
        assert advice, "按渠道看损耗本来就出不来，这条不该被放行"
        assert "渠道" in advice


class TestTheDeclarationStaysHonest:
    def test_wastage_type_is_a_real_registered_dimension(self):
        """⛔ 不许往能力表里塞一个登记表里不存在的维度名。"""
        assert "wastage_type" in DIMENSIONS, (
            "能力表声明了一个 `metric_registry.DIMENSIONS` 里没有的维度 —— "
            "下游按它分组会在运行时才炸"
        )
        assert DIMENSIONS["wastage_type"].group_expr, (
            "`wastage_type` 没有 group_expr，声明「能按它分组」是假的"
        )

    def test_wastage_reason_stays_out_until_there_is_evidence(self):
        """`_WASTAGE_DIMS` 还列着 `wastage_reason`，但输出里没有按原因分的东西。

        ⛔ 宁可窄而真 —— 能力表写宽了，下游会拿它当承诺。
        这条钉的是**克制**：将来要补它，先拿出「它真能出」的实测。
        """
        assert "wastage_reason" not in _RESOLVER_DIMENSIONS[WASTAGE[0]], (
            "补 wastage_reason 之前先在有数据的租户上真跑一次，"
            "证明输出里确实有按损耗原因分的东西"
        )

    def test_supported_dimensions_reads_the_same_table(self):
        """判据与文案必须读同一个集合（`_supported_dimensions` 的注释要求）。"""
        assert _supported_dimensions(WASTAGE) >= {"wastage_type"}
