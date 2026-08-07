"""时段经营问句的识别。

🔴 2026-08-07 prod 实测: 「最近30天哪个时段生意最好」没被识别 -> planner 给了
   `SALES_SUMMARY + dimensions=('time',)`, 而 `_RESOLVER_DIMENSIONS[SALES_SUMMARY]`
   只声明 `{store}` -> 执行前被拦成「查询维度超出计划 resolver 的能力范围」,
   用户拿到一句反问(G1 里的 D)。

   两处缺口: 日段词表只有具体的午市/晚市…没有泛指的「时段」; 疑问词表没有
   最高级问法「最好/最忙/最高」。
"""
import pytest

from smartbi.gold.restaurant.restaurant_intent import _is_daypart_business_query


@pytest.mark.parametrize("query", [
    "最近30天哪个时段生意最好",      # 🔴 prod 实测那一条
    "哪个时段生意最好",
    "哪个时间段客流最高",
    "晚市生意怎么样",                # 原来就认得, 回归
    "午市忙不忙",
    "夜宵营收多少",
])
def test_daypart_business_questions_are_recognised(query):
    assert _is_daypart_business_query(query) is True, query


@pytest.mark.parametrize("query", [
    # ⛔ 点名菜品 -> 问的是菜不是时段。路由到排班 resolver 会用错粒度回答,
    #    比反问更糟。这条是「加词」最容易带出的副作用的阴性对照。
    "晚市生意最好的菜是什么",     # 「的菜」-> 问的是菜不是时段
    "午市卖得最好的是罗氏虾吗",
    "哪个时段的菜品毛利最高",     # 「菜品」同上
    # 与时段无关的问句不该被这批新词带进来。
    "最近30天总营收是多少",
    "哪个菜卖得最好",
    "各门店对比如何",
    "库存有什么要注意的",
])
def test_non_daypart_questions_stay_out(query):
    assert _is_daypart_business_query(query) is False, query
