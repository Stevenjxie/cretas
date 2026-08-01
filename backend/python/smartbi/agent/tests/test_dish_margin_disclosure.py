"""单品毛利必须自带口径披露 —— 「单份成本只算配方里已登记的食材」。

## 为什么要这条

菜品成本有两个说法, 差 2.2 倍:
  - 菜品表自己写的 cost
  - 配方逐行相加的钱
因为**配方只列主料**, 没登记的辅料/调料/损耗不在成本里, 于是毛利率虚高。
prod 实测客户租户 RES_3101_009 显示 85.1% 毛利率, 而食材成本率只有 7-15.7%。

## ⛔ 刻意不改数

没有权威实际成本可分摊 (``restaurant_target_margin_config`` 里只有**目标**不是实际),
且那是客户生产数据 —— 没权威就编一个"估算成本"等于**伪造财务数据**。
所以修法是**把口径和可核查的覆盖度摆出来**, 让读的人自己判断, 而不是动那个百分比。

## 判据

1. 渲染出来的事实底稿必须写明成本口径, 且说明这是**上限**而非真实毛利率;
2. 每道菜带「配方登记 N 种食材」, 登记 ≤3 种时额外标「登记过少」;
3. ``cost_basis_complete`` 这个键**不能**被当成"成本口径完整"的证据 ——
   它只是信任标记, 对配方有没有列全一无所知 (这个误解正是本缺陷长期没被发现的原因)。
"""
from smartbi.agent.factbook import FactBook, _ingredient_note


def _dish(name: str, price: float, cost: float, ingredient_count: int) -> dict:
    gross = price - cost
    return {
        "dish_name": name,
        "selling_price": price,
        "unit_cost": cost,
        "gross_profit": gross,
        "gross_margin_pct": round(gross / price * 100, 2),
        "ingredient_count": ingredient_count,
        "ingredients": [{"name": f"料{i}", "cost": 1.0} for i in range(ingredient_count)],
    }


def _dish_margin(*, min_count: int, median_count: int, dishes: list) -> dict:
    return {
        "factory_id": "RES_3101_009",
        "dish_count": len(dishes),
        # 信任标记 —— 不是"成本口径完整"的断言
        "cost_basis_complete": True,
        "cost_basis": "restaurant_sku_forms",
        "ingredient_count_min": min_count,
        "ingredient_count_median": median_count,
        "top_margin": dishes,
        "low_margin": [],
    }


def _render(dm: dict) -> str:
    return "\n".join(FactBook(dish_margin=dm).to_prompt_lines())


class TestCostBasisDisclosure:

    def test_states_cost_basis_and_that_margin_is_an_upper_bound(self):
        text = _render(_dish_margin(
            min_count=2, median_count=3,
            dishes=[_dish("红烧肉", 88.0, 13.0, 2)],
        ))

        assert "已登记食材" in text, "没说明单份成本只算了配方登记的料"
        assert "上限" in text, (
            "没说明这是毛利率上限 —— 只说'口径'读者仍会当成真实毛利率"
        )

    def test_每道菜带登记食材数(self):
        text = _render(_dish_margin(
            min_count=2, median_count=3,
            dishes=[_dish("红烧肉", 88.0, 13.0, 2)],
        ))

        assert "配方登记 2 种食材" in text
        # 阳性对照: 毛利率本身没被改动, 我们只是加了披露
        assert "85.23%" in text or "85.2" in text, "毛利率数字不该被改"

    def test_登记过少时额外点名(self):
        text = _render(_dish_margin(
            min_count=2, median_count=2,
            dishes=[_dish("红烧肉", 88.0, 13.0, 2)],
        ))
        assert "登记过少" in text

    def test_登记充分时不误报(self):
        """反向断言: 配方列得全的菜不该被贴「登记过少」, 否则披露变成噪音。"""
        text = _render(_dish_margin(
            min_count=9, median_count=11,
            dishes=[_dish("佛跳墙", 288.0, 150.0, 12)],
        ))
        assert "配方登记 12 种食材" in text
        assert "登记过少" not in text


class TestIngredientNote:
    """``_ingredient_note`` 单独钉一遍 —— 它是渲染两处列表共用的。"""

    def test_缺字段时返回空串而不是崩(self):
        assert _ingredient_note({}) == ""
        assert _ingredient_note({"ingredient_count": None}) == ""
        assert _ingredient_note({"ingredient_count": 0}) == ""
        assert _ingredient_note("not a dict") == ""

    def test_边界_3种是过少_4种不是(self):
        assert "登记过少" in _ingredient_note({"ingredient_count": 3})
        assert "登记过少" not in _ingredient_note({"ingredient_count": 4})


class TestTrustMarkerIsNotACompletenessClaim:

    def test_cost_basis_complete_只是信任标记(self):
        """``cost_basis_complete=True`` 不得让披露消失。

        这个键的名字读起来像「成本口径完整」, 但它只表示这批事实来自确定性 Gold
        生产者。把它当成完整性证据, 正是 85.1% 毛利率长期没人质疑的原因。
        """
        text = _render(_dish_margin(
            min_count=1, median_count=2,
            dishes=[_dish("白灼虾", 128.0, 12.0, 1)],
        ))

        assert "已登记食材" in text, (
            "cost_basis_complete=True 时披露消失了 —— 那等于用信任标记冒充完整性证据"
        )
        assert "配方登记 1 种食材" in text
