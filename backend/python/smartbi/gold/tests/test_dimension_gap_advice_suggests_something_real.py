"""维度缺口文案：建议必须**由能力算出来**，⛔ 不能硬编码。

## 缺陷（2026-08-17 prod 实测，我自己当天写的回归）

    「哪家店损耗控制得最差」
    → 换个问法我大概率能答，例如把**门店**换成**门店**或菜品。

`extra={store}` 时建议词却写死成「门店或菜品」⇒ **把门店换成门店**。
老板照着做等于原地转圈。

## 这一类断言守的是「建议指向一个真能算的东西」

⛔ 不是守字面措辞 —— 措辞会改。守的是**性质**：
建议里出现的维度，必须是 `_supported_dimensions(plan)` 里真有的，
且**绝不能**是老板刚问的那个（那正是算不出来的那一层）。
"""
import pytest

from smartbi.gold.restaurant.restaurant_intent import RestaurantQuerySpec
from smartbi.gold.restaurant.restaurant_intent_service import (
    _DIMENSION_LABEL,
    _dimension_gap_advice,
    _supported_dimensions,
)


def _spec(dimensions):
    """⛔ 不手写字段清单 —— 直接实例化真类, 其余字段交给 dataclass 默认值。

    必填槽位这一份抄自 `test_ambiguous_store_gets_candidates._spec`,
    理由同那里: spec 加一个必填槽位时, 手抄表会静默少一个属性。
    """
    required = {
        "intent": "RESTAURANT_OPS_WASTAGE_TOP", "domain": "restaurant",
        "date_range": (None, None), "window_label": "最近30天",
        "relative_window": None, "metrics": (), "wants_margin": False,
        "asks_profitability": False, "comparison": None,
        "confidence": 1.0, "source_tier": "test",
    }
    return RestaurantQuerySpec(**required, dimensions=tuple(dimensions))


# (计划, 老板问的维度) —— 都是 prod 上真撞到过的组合
CASES = [
    pytest.param(("RESTAURANT_OPS_WASTAGE_TOP",), ("store",), id="损耗按门店"),
    pytest.param(("RESTAURANT_OPS_RECIPE_COST",), ("store",), id="成本按门店"),
    pytest.param(("RESTAURANT_OPS_TREND_ANALYSIS",), ("dish",), id="趋势按菜"),
]


class TestTheAdviceNeverPointsBackAtWhatCannotBeDone:
    @pytest.mark.parametrize("plan,asked", CASES)
    def test_advice_does_not_tell_the_boss_to_swap_x_for_x(self, plan, asked):
        """核心缺陷：建议里不能出现老板刚问的那个维度作为**替代项**。

        ⚠️ 这条断言的第一版守的是**新措辞的字面**(`换成问「按X」`),
           变异回旧措辞(`把X换成X或菜品`)时**一个字都不红** ——
           形状不同就漏。⇒ 改成守**性质**:
           「换成」**之后**的那段文字里, 不许再出现老板刚问的那个维度。
           两种措辞都逃不掉。
        """
        advice = _dimension_gap_advice(_spec(asked), plan)
        assert advice, "撞了维度缺口却一个字都没说"
        asked_label = _DIMENSION_LABEL.get(asked[0], asked[0])
        assert "换成" in advice, f"没给出替代问法: {advice!r}"
        suggestion = advice.rsplit("换成", 1)[1]
        assert asked_label not in suggestion, (
            f"建议老板把{asked_label}换成{asked_label}——"
            f"「换成」之后是 {suggestion!r}，整句 {advice!r}"
        )

    @pytest.mark.parametrize("plan,asked", CASES)
    def test_the_suggested_dimension_is_one_the_plan_can_actually_do(
            self, plan, asked):
        """建议指向的那一层, 必须是这个计划**真能拆**的。"""
        advice = _dimension_gap_advice(_spec(asked), plan)
        supported = _supported_dimensions(plan)
        if not supported:
            assert "不按维度拆" in advice, f"无维度可拆却给了建议: {advice!r}"
            return
        labels = [_DIMENSION_LABEL.get(k, k) for k in supported]
        assert any(lbl in advice for lbl in labels), (
            f"建议里没提到任何真能拆的维度 {labels}: {advice!r}"
        )


class TestItStillSaysSomethingWhenThereIsAGap:
    def test_no_gap_means_no_text(self):
        """阳性对照(反向): 维度对得上时**必须**返回空串 ——

        否则上面「撞了缺口就有话说」的断言可能只是因为它对什么都有话说。
        """
        assert _dimension_gap_advice(
            _spec(("store",)), ("RESTAURANT_OPS_STORE_MARGIN",)) == ""
        assert _dimension_gap_advice(
            _spec(("dish",)), ("RESTAURANT_OPS_RECIPE_COST",)) == ""
        # 2026-08-17: 「渠道按门店」原本列在上面的 CASES 里当缺口用例，
        # 而渠道构成现在真出各门店的表了 ⇒ 它**不再是缺口**。
        # ⛔ 不是删掉那条用例了事 —— 挪到这里当**回归守卫**:
        #    哪天门店表被拿掉、能力表被改窄，这一行会红。
        assert _dimension_gap_advice(
            _spec(("store",)), ("RESTAURANT_OPS_CHANNEL_MIX",)) == "", (
            "渠道按门店又变成缺口了 —— 各门店渠道表或能力表声明被改没了"
        )
        assert _dimension_gap_advice(
            _spec(("channel", "store")), ("RESTAURANT_OPS_CHANNEL_MIX",)) == ""

    def test_partial_overlap_keeps_the_split_advice(self):
        """asked ∩ supported 非空时走另一支(先问能算的那层), 不受本次改动影响。"""
        advice = _dimension_gap_advice(
            _spec(("store", "ingredient")), ("RESTAURANT_OPS_STORE_MARGIN",))
        assert "分开问" in advice, advice
