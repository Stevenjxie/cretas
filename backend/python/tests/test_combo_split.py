"""Combo split: decompose set menu sales into constituent dishes."""
from smartbi.services.restaurant.sections.base import SectionRequest
from smartbi.services.restaurant.sections.combo_split import ComboSplitHandler


def _req(params):
    return SectionRequest(factory_id="F-TEST", upload_id=None, sub_sector="火锅",
                          store_id=None, store_name=None, params=params)


def test_basic_split():
    resp = ComboSplitHandler().compute(
        _req({"combos": [{"combo_name": "双人套餐A", "combo_sales": 80,
                          "dishes": [{"dish": "麻辣牛肉", "qty_per_combo": 1},
                                     {"dish": "酸菜鱼", "qty_per_combo": 1},
                                     {"dish": "米饭", "qty_per_combo": 2}]}],
              "single_sales": {"麻辣牛肉": 120, "酸菜鱼": 50, "米饭": 300}}), {})
    assert resp.status.value == "ok"
    beef = next(d for d in resp.data["dish_breakdown"] if d["dish"] == "麻辣牛肉")
    assert beef["single_sales"] == 120
    assert beef["combo_sales"] == 80
    assert beef["total_sales"] == 200
    assert beef["combo_pct"] == 40.0


def test_multi_combo():
    resp = ComboSplitHandler().compute(
        _req({"combos": [
            {"combo_name": "A", "combo_sales": 50, "dishes": [{"dish": "鱼头", "qty_per_combo": 1}]},
            {"combo_name": "B", "combo_sales": 30, "dishes": [{"dish": "鱼头", "qty_per_combo": 2}]}],
            "single_sales": {"鱼头": 100}}), {})
    fish = next(d for d in resp.data["dish_breakdown"] if d["dish"] == "鱼头")
    assert fish["combo_sales"] == 110
    assert fish["total_sales"] == 210


def test_skipped_no_combos():
    resp = ComboSplitHandler().compute(_req({}), {})
    assert resp.status.value == "skipped"
