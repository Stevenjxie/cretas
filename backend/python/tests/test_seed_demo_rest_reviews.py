from __future__ import annotations

import pandas as pd

from smartbi.scripts.seed_demo_rest_reviews import (
    EXPECTED_REVIEW_KEYS,
    normalize_review_dataframe,
)


def test_normalize_review_dataframe_outputs_gold_query_keys():
    df = pd.DataFrame(
        [
            {
                "评价时间": "2025-09-30 23:27:44",
                "评价ID": "2895776500",
                "城市": "上海市",
                "评价门店": "鲜行者X顺德小馆（虹口龙之梦店）",
                "平台": "点评",
                "评价详情": "有小飞虫，上菜比较慢，紫苏啫牛蛙好吃。",
                "星级分": 4.5,
                "口味分": 4.5,
                "环境分": 3.0,
                "服务分": 3.5,
                "菜品标签": "鲜美,香辣",
                "服务标签": "上菜慢",
                "环境标签": "有小虫",
                "用户昵称": "匿名用户",
                "是否vip": "否",
                "回复状态": "已回复",
                "投诉类型": "环境问题-飞虫",
            },
            {
                "评价时间": "2025-10-01 12:00:00",
                "评价ID": "2895776501",
                "城市": "上海市",
                "评价门店": "鲜行者X顺德小馆（虹口龙之梦店）",
                "平台": "美团",
                "星级分": 2.0,
                "口味分": 2.5,
                "环境分": 2.0,
                "服务分": 2.5,
                "是否vip": "是",
                "回复状态": "未回复",
            },
        ]
    )

    rows = normalize_review_dataframe(df, limit=100)

    assert len(rows) == 2
    assert all(key in rows[0] for key in EXPECTED_REVIEW_KEYS)
    assert rows[0]["评价门店"] == "示范门店01"
    assert rows[1]["评价门店"] == "示范门店01"
    assert rows[0]["time_period"] == "2025-09-30 23:27:44"
    assert rows[0]["星级分"] == 4.5
    assert rows[0]["平台"] == "点评"
    assert rows[0]["菜品标签"] == "鲜美,香辣"
    assert rows[1]["回复状态"] == "未回复"
