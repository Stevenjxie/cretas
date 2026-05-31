"""用真实客户数据列名(大众点评餐饮 + 六扇门工厂)验证字典扩充: 准确 + 覆盖提升。"""
from __future__ import annotations

from smartbi.services.llm_mapper import LLMMapper


def _rule_map(cols):
    m = LLMMapper()
    fields = [{"fieldName": c, "dataType": "string", "semanticType": "unknown",
               "sampleValues": []} for c in cols]
    res = m._rule_based_mapping(fields)
    return {x["sourceField"]: (x["targetField"], x["confidence"]) for x in res["mappings"]}


# 真实列名 (extract_headers 抽出)
RESTAURANT_SALES = [
    "门店名称", "商品分类", "收入分组", "商品编码", "商品名称", "规格", "商品类型", "点单方式",
    "单卖数量(不含套餐子商品)", "退货数量(不含套餐子商品)", "单位", "销售单价", "销售金额",
    "折后金额", "分摊优惠", "实退金额", "实收",
]
PURCHASE = [
    "店铺名称", "单号", "供应商", "原料分类", "原料名称", "规格", "入库仓库", "入库数量",
]
FACTORY = [
    "出库日期", "原料批次", "仓库出库重量", "生产日期", "产出数量", "投料重量", "出成率",
    "人工（分）", "人工（时）", "投入重量/kg", "产出重量/kg", "成品重量/kg", "成品总出成率",
    "入库数量/盒", "批次号",
]


def test_normalize_strips_noise_keeps_meaningful():
    n = LLMMapper._normalize_field_name
    assert n("投入重量/kg") == "投入重量"
    assert n("单卖数量(不含套餐子商品)") == "单卖数量"
    assert n("成品重量/kg") == "成品重量"
    assert n("人工（分）") == "人工（分）"   # 保留有意义的（分）
    assert n("销售金额") == "销售金额"        # 无噪声不动


def test_restaurant_columns_map_accurately():
    mp = _rule_map(RESTAURANT_SALES)
    expect = {
        "门店名称": "store", "商品分类": "category", "商品编码": "product_code",
        "商品名称": "product", "规格": "spec", "点单方式": "order_channel",
        "单卖数量(不含套餐子商品)": "quantity_sold",  # 经规范化命中 单卖数量
        "销售单价": "unit_price", "销售金额": "sales_amount", "折后金额": "discounted_amount",
        "分摊优惠": "discount_amount", "实退金额": "refund_amount", "实收": "revenue",
    }
    for col, target in expect.items():
        assert col in mp, f"{col} 未被规则映射"
        assert mp[col][0] == target, f"{col} → {mp[col][0]} 期望 {target}"
        assert mp[col][1] >= 0.85, f"{col} 置信度 {mp[col][1]} < 0.85"


def test_factory_columns_map_accurately():
    mp = _rule_map(FACTORY)
    expect = {
        "出成率": "yield_rate", "成品总出成率": "yield_rate",
        "投料重量": "input_weight", "投入重量/kg": "input_weight",
        "产出数量": "output_quantity", "产出重量/kg": "output_quantity",
        "成品重量/kg": "output_quantity", "仓库出库重量": "outbound_weight",
        "人工（分）": "labor_minutes", "人工（时）": "labor_hours",
        "原料批次": "batch", "批次号": "batch", "入库数量/盒": "inbound_quantity",
    }
    for col, target in expect.items():
        assert col in mp and mp[col][0] == target, f"{col} → {mp.get(col)} 期望 {target}"
        assert mp[col][1] >= 0.85


def test_coverage_improved_high_confidence():
    # 餐饮销量表: 高置信(≥0.85)覆盖应 >= 12/17 (门店/商品/价/量/金额类都命中)
    mp = _rule_map(RESTAURANT_SALES)
    high = [c for c in RESTAURANT_SALES if c in mp and mp[c][1] >= 0.85]
    assert len(high) >= 12, f"高置信覆盖仅 {len(high)}/17: {high}"
    # 采购表 + 工厂表关键字段都命中
    pm = _rule_map(PURCHASE)
    assert pm.get("供应商", (None,))[0] == "supplier"
    assert pm.get("原料名称", (None,))[0] == "material"


if __name__ == "__main__":
    import pytest
    raise SystemExit(pytest.main([__file__, "-v"]))
