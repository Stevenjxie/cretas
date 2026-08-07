"""缺项有安全默认值就补默认，不反问 —— 但必须披露。

🔴 2026-08-07 二次实测暴露的口径错误（比缺陷本身更值得记）：

  基线那套问句(短问法)    补门店默认后  A=5 / C=1 / D=9
  我改写成「最近30天X」    同一天同一版  A=12 / C=1 / D=2

**同一套代码、同一批意图，只因为问句加了时间前缀，A 就从 5 变成 12。**
差额几乎全是「用户没说时间 → 反问」。

🔑 判据两条：
  1. 报改善必须与基线用**逐字相同**的问句集，否则量的是两件事。
  2. 一批问句的归宿分布**对措辞极其敏感** —— 单看一个数字不能说明系统变好了。

⛔ 只有「有安全默认值 + 会被显式披露」的槽位能进 `_AUTO_DEFAULTABLE`。
   指标/对象不进：它们没有无歧义的默认，补错等于给一个看着像答案的错答案。
"""
import re
from pathlib import Path

import pytest

from smartbi.gold.restaurant import restaurant_intent as RI
from smartbi.gold.restaurant.restaurant_intent_service import (
    _store_scope_disclosure,
    _time_range_disclosure,
)


class _Spec:
    def __init__(self, **kw):
        self.store_scope_defaulted = kw.get("store_scope_defaulted", False)
        self.time_range_defaulted = kw.get("time_range_defaulted", False)
        self.store_options = kw.get("store_options", ())


def test_time_range_default_is_disclosed():
    """时间窗是**选择**不是超集, 所以披露比门店那条更不能省。"""
    text = _time_range_disclosure(_Spec(time_range_defaulted=True))
    assert text.startswith("\n\n"), "披露要另起段, 不要粘在数字后面"
    assert "最近 30 天" in text
    assert "最近 7 天" in text, "要告诉用户怎么改, 否则他不知道可以改"


def test_no_disclosure_when_user_said_it():
    """用户自己说了时间, 再声明一遍是废话。判据是「谁选的」不是「是什么」。"""
    assert _time_range_disclosure(_Spec(time_range_defaulted=False)) == ""


def test_two_disclosures_do_not_collide():
    """门店与时间同时取默认时, 两句都要出现且各自独立。"""
    spec = _Spec(store_scope_defaulted=True, time_range_defaulted=True,
                 store_options=("A店", "B店"))
    combined = _store_scope_disclosure(spec) + _time_range_disclosure(spec)
    assert "最近 30 天" in combined
    assert combined.count("（") >= 2, "两条披露被合并成一句了"


def test_spec_carries_the_flag():
    """字段存在且默认 False —— 不能反过来「默认就披露」。

    ⚠️ 不构造整个 spec: 它有 10 个必填位置参数, 为一条断言凑齐等于把这条测试
    绑死在构造签名上, 签名一动它就假红。直接读 dataclass 的字段定义。
    """
    from dataclasses import fields
    field = {f.name: f for f in fields(RI.RestaurantQuerySpec)}.get("time_range_defaulted")
    assert field is not None, "spec 上没有 time_range_defaulted —— 披露就无从判断"
    assert field.default is False, "默认必须是 False"


class TestAutoDefaultableSet:
    """⛔ 集合本身是个契约, 不是实现细节。"""

    SRC = Path(RI.__file__).read_text(encoding="utf-8")

    def test_set_contains_exactly_the_two_safe_slots(self):
        m = re.search(r"_AUTO_DEFAULTABLE\s*=\s*\{([^}]*)\}", self.SRC)
        assert m, "_AUTO_DEFAULTABLE 不见了或写法变了 —— 这道闸已经空转"
        members = {s.strip().strip('"\'') for s in m.group(1).split(",") if s.strip()}
        assert members == {"store_scope", "time_range"}, (
            f"可自动补默认的槽位集合变成了 {members}。"
            "加一个槽位进来之前先回答两个问题: 它的默认值有没有歧义? 会不会被披露? "
            "指标/对象两条都不满足 —— 补错就是给一个看着像答案的错答案。"
        )

    def test_missing_metric_still_asks(self):
        """🔴 阴性对照: 缺指标时必须照旧反问, 否则上面那条断言等于没约束。"""
        assert "metric" not in re.search(
            r"_AUTO_DEFAULTABLE\s*=\s*\{([^}]*)\}", self.SRC).group(1)

    def test_subset_check_not_equality(self):
        """并缺(门店+时间)也要能补 —— 用 <= 而不是 ==。"""
        assert re.search(r"set\(_missing\)\s*<=\s*_AUTO_DEFAULTABLE", self.SRC), (
            "这里必须是子集判断: 写成 == 就只覆盖单缺, "
            "「哪个菜卖得最好」这种门店和时间同时缺的照样反问"
        )


@pytest.mark.parametrize("query", [
    "哪个菜卖得最好",
    "毛利最低的菜品有哪些",
    "营收趋势怎么样",
])
def test_baseline_phrasings_are_the_ones_that_matter(query):
    """这几条就是基线里因缺时间窗而反问的问句。

    ⚠️ 这里不打 LLM, 只钉住「基线问句长这样」—— 防止下次有人再拿加了
    「最近30天」前缀的改写版去报改善（本轮我自己踩过一次）。
    """
    assert "最近30天" not in query
    assert "门店" not in query
