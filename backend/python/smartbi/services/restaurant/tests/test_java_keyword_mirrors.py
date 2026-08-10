"""Python 侧的成本分类关键词必须逐字等于 Java 权威。

## 为什么需要这条闸

`RestaurantFinancialMetricsFetcher.java` 里的 FOOD/LABOR/RENT_KEYWORDS 是「什么算
食材成本 / 人工成本 / 房租」的**唯一权威**。Python 侧有**两份**手抄镜像:

    smartbi/services/restaurant/health_check_metrics.py
    smartbi/services/restaurant/value_refresh_pipeline.py

两个文件的注释**都写着「镜像同一个 Java 类」**。2026-08-10 实测, 它们**互相矛盾**:

    Java(权威)   FOOD  = 食材 原材料 食品 饮料 酒水 菜品
    health_...   FOOD  = 食材 原材料 食品 饮料 酒水 菜品      ✅
    value_...    FOOD  = 食材 原料 采购 食品                  🔴 少 4 个 多 2 个

    Java(权威)   LABOR = 人工 工资 薪 员工 劳务
    value_...    LABOR = 人工 人力 工资 薪 劳务                🔴 少「员工」多「人力」

后果是真的: 一条写着「酒水」/「菜品」的成本, 在 Java 与 health 里算食材成本, 在
value_refresh 里**不算**; 写「员工」的人工成本同理 —— **同一个数, 两条路算出来
不一样**, 而且没有任何东西会报警。

判据: **注释说「镜像 X」不构成它真的等于 X。** 手工镜像漂移时不会响 —— 它安静地
      错, 然后以另一个模块的症状出现(这里会表现为「两个页面的成本率对不上」)。

## 这条闸怎么写才有意义

期望值**从 .java 源码解析**, 不写死在这里 —— 写死就只是把手抄的位置从生产代码
挪到测试里, 漂移照样发生, 只是换个地方漂。
"""
from __future__ import annotations

import re
from pathlib import Path

import pytest

_JAVA = (
    Path(__file__).resolve().parents[6]
    / "backend/java/cretas-api/src/main/java/com/cretas/aims/ai/tool/impl"
    / "restaurant/diagnostic/RestaurantFinancialMetricsFetcher.java"
)


def test_java_authority_is_actually_reachable():
    """⛔ 阴性对照: 路径写错时上面那些会**整片 skip**, 而 skip 不是通过。

    2026-08-10 实测: 第一版把 parents 写成 [5](= backend/), 5 条断言全部 skip,
    输出是「1 passed, 5 skipped」—— 看起来没红, 实际一条都没执行。
    判据: **凡是「找不到就 skip」的闸, 必须再加一条「找得到」的断言**,
          否则路径一错整道闸静默消失。
    """
    assert _JAVA.exists(), (
        f"Java 权威文件找不到: {_JAVA}\n"
        f"这不是环境问题就是路径写错 —— 不修的话下面 5 条断言会整片 skip。")


def _java_keywords(name: str) -> tuple:
    """从 Java 源码里读出 `private static final String[] NAME = {...}`。"""
    src = _JAVA.read_text(encoding="utf-8")
    m = re.search(rf"{name}\s*=\s*\{{(.*?)\}}", src, re.S)
    assert m, f"Java 里找不到 {name} —— 结构变了, 先修这条解析"
    kws = tuple(re.findall(r'"([^"]+)"', m.group(1)))
    assert kws, f"{name} 解析出 0 个词 —— 正则与 Java 写法脱节了"
    return kws


_MIRRORS = [
    # (python 模块, python 常量名, java 常量名)
    ("smartbi.services.restaurant.health_check_metrics", "_FOOD_KEYWORDS", "FOOD_KEYWORDS"),
    ("smartbi.services.restaurant.health_check_metrics", "_LABOR_KEYWORDS", "LABOR_KEYWORDS"),
    ("smartbi.services.restaurant.health_check_metrics", "_RENT_KEYWORDS", "RENT_KEYWORDS"),
    ("smartbi.services.restaurant.value_refresh_pipeline", "_FOOD_KEYWORDS", "FOOD_KEYWORDS"),
    ("smartbi.services.restaurant.value_refresh_pipeline", "_LABOR_KEYWORDS", "LABOR_KEYWORDS"),
]


@pytest.mark.parametrize("module_path,py_name,java_name", _MIRRORS)
def test_java_keyword_mirrors_match_authority(module_path, py_name, java_name):
    if not _JAVA.exists():          # 纯 python 检出时跳过, 但要说清为什么
        pytest.skip(f"Java 源码不在此检出中, 无法比对权威: {_JAVA}")

    import importlib

    mod = importlib.import_module(module_path)
    actual = tuple(getattr(mod, py_name))
    expected = _java_keywords(java_name)
    assert actual == expected, (
        f"{module_path}.{py_name} 与 Java {java_name} 不一致。\n"
        f"  Java   : {expected}\n"
        f"  Python : {actual}\n"
        f"  只在 Java  : {sorted(set(expected) - set(actual))}\n"
        f"  只在 Python: {sorted(set(actual) - set(expected))}\n"
        f"⚠️ 这不是「测试期望过时了」—— Java 是权威, 改 Python 去对齐它。")


def test_the_two_python_mirrors_agree_with_each_other():
    """两份镜像之间也要一致 —— 它们镜像的是同一个东西。

    ⚠️ 这条**不能替代**上面那条: 两份都错成同一个样子时它照样绿。
       但它能在「只改了一处」时更早报警, 且报得更好懂。
    """
    from smartbi.services.restaurant import health_check_metrics as hc
    from smartbi.services.restaurant import value_refresh_pipeline as vr

    for name in ("_FOOD_KEYWORDS", "_LABOR_KEYWORDS"):
        assert tuple(getattr(hc, name)) == tuple(getattr(vr, name)), (
            f"{name} 两处不一致: health={getattr(hc, name)} vs "
            f"value_refresh={getattr(vr, name)}")
