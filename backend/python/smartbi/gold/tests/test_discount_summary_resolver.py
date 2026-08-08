"""折扣力度 resolver + 它的路由。

🔴 存在的理由: 「折扣力度多大」在 2026-08-08 之前答不出来, 而**数据和口径一直
   都在** —— `agg_daily.discount_amount` 有数(MOCK_REST 实测 ¥387 万),
   `gold.queries.discount_summary` 早就写好并修过 C1 那次月粒度/日粒度错配。
   缺的只是餐饮问答的 20 个意图里没有折扣这一项, 问句无处可落。

   ⇒ 判据: 说「这个问题答不出来是因为缺数据」之前, 先分清缺的是**数据、口径、
     查询、还是终点**。这次是终点, 一列数据都不缺。
"""
from datetime import date

import pytest

from smartbi.gold.restaurant import restaurant_ops_router as R
from smartbi.gold.restaurant.restaurant_intent_service import _RESOLVER_DIMENSIONS

ALL_INTENTS = tuple(_RESOLVER_DIMENSIONS)

#: 2026-08-08 prod 实测(MOCK_REST 近 30 天): 有总额, **构成表 0 行**。
_PROD_TOTAL = {"total_discount_amount": 3543242.2, "total_revenue": 78124105.0,
               "revenue_share_pct": 4.5, "discounts": []}


class _Ctx:
    def __init__(self, v): self._v = v
    async def __aenter__(self): return self._v
    async def __aexit__(self, *a): return False


class _Conn:
    def __init__(self, anchor): self._anchor = anchor
    async def execute(self, *a, **k): return None
    async def fetchval(self, *a, **k): return self._anchor


class _Pool:
    def __init__(self, anchor=date(2026, 8, 7)): self.conn = _Conn(anchor)
    def acquire(self): return _Ctx(self.conn)


@pytest.fixture
def summary(monkeypatch):
    """替换 `gold.queries.discount_summary` 的返回, 记录它**确实被调用**。"""
    calls = []

    def _install(payload):
        async def _fake(pool, factory_id, date_range, **kw):
            calls.append((factory_id, date_range))
            return dict(payload)

        import smartbi.gold.queries as Q
        monkeypatch.setattr(Q, "discount_summary", _fake)
        return calls

    return _install


@pytest.mark.asyncio
async def test_price_role_sees_amount_and_share(summary):
    calls = summary(_PROD_TOTAL)

    got = await R.resolve_discount_summary(
        _Pool(), "MOCK_REST", 30, role="factory_super_admin", query="折扣力度多大"
    )

    assert got.code == "RESTAURANT_OPS_DISCOUNT_SUMMARY"
    assert "4.5%" in got.answer_text
    assert "¥3,543,242" in got.answer_text
    assert got.meta["revenue_share_pct"] == 4.5
    assert calls, "口径必须走 gold.queries.discount_summary, 不许就地写 SQL"


@pytest.mark.asyncio
async def test_non_price_role_gets_share_but_never_the_amount(summary):
    """金额是价格权限数据 —— 百分比可以给, 绝对额不行(同 resolve_channel_mix)。"""
    summary(_PROD_TOTAL)

    got = await R.resolve_discount_summary(
        _Pool(), "MOCK_REST", 30, role="restaurant_hr", query="折扣力度多大"
    )

    assert "4.5%" in got.answer_text
    assert "3,543,242" not in got.answer_text
    assert "78,124,105" not in got.answer_text, "营收也是金额, 一并不给"
    assert all("¥" not in str(k.get("value", "")) for k in got.kpis)


@pytest.mark.asyncio
async def test_missing_composition_is_disclosed_not_invented(summary):
    """🔴 阴性对照: MOCK_REST 有总额但 `agg_discount` 是 0 行。

    总额与构成是**两个数据源**。缺构成时必须如实说没有，
    ⛔ 不许拿总额编出「满减 60%／会员 40%」这种看起来很合理的拆分。
    """
    summary(_PROD_TOTAL)

    got = await R.resolve_discount_summary(
        _Pool(), "MOCK_REST", 30, role="factory_super_admin", query="折扣力度多大"
    )

    assert got.meta["composition_available"] is False
    assert "没有分类型的构成数据" in got.answer_text
    for invented in ("满减：", "会员折扣：", "团购券："):
        assert invented not in got.answer_text


@pytest.mark.asyncio
async def test_composition_is_rendered_when_it_exists(summary):
    """有构成就要拆 —— 否则这个 resolver 永远只会报一个总数。"""
    summary({**_PROD_TOTAL, "discounts": [
        {"discount_name": "满减", "amount": 2000000.0, "share_pct": 56.4},
        {"discount_name": "会员折扣", "amount": 1543242.2, "share_pct": 43.6},
    ]})

    got = await R.resolve_discount_summary(
        _Pool(), "MOCK_REST", 30, role="factory_super_admin", query="折扣力度多大"
    )

    assert got.meta["composition_available"] is True
    assert "满减" in got.answer_text and "56.4%" in got.answer_text


@pytest.mark.asyncio
async def test_never_claims_discounts_caused_revenue(summary):
    """⛔ DESCRIPTIVE only —— 这个 schema 支撑不了任何因果结论。

    「折扣带来了多少增量营收」需要反事实(没打折会卖多少), 库里没有那个量。
    答案里出现因果措辞就是在编。
    """
    summary(_PROD_TOTAL)

    got = await R.resolve_discount_summary(
        _Pool(), "MOCK_REST", 30, role="factory_super_admin", query="折扣力度多大"
    )

    # ⛔ 按**句**判, 不按词判: 免责声明本身就要引用这些词来否定它们
    #    (「不能据此说折扣『带来了』多少额外营收」)。只搜关键词会把正确的
    #    声明判成违规 —— 那是在量措辞, 不是量主张。
    causal = ("带来了", "拉动了", "带动了", "促成了", "增量营收", "多卖了")
    negation = ("不能", "无法", "不该", "不代表", "不等于")
    offenders = [
        s for s in got.answer_text.replace("\n", "。").split("。")
        if any(c in s for c in causal) and not any(n in s for n in negation)
    ]
    assert not offenders, f"有句子在做因果断言: {offenders}"
    assert "不能据此说" in got.answer_text, "必须主动声明这条边界"


@pytest.mark.asyncio
async def test_no_revenue_means_no_ratio(summary):
    """没有分母就不给比率, 不用 0 顶替。"""
    summary({"total_discount_amount": 0.0, "total_revenue": 0.0,
             "revenue_share_pct": None, "discounts": []})

    got = await R.resolve_discount_summary(
        _Pool(), "MOCK_REST", 30, role="factory_super_admin", query="折扣力度多大"
    )

    assert got.meta.get("no_data") is True
    assert "%" not in got.answer_text


def test_declared_grain_matches_what_the_sources_can_produce():
    """⛔ 声明的是真能出的粒度: 两个来源都不带门店/菜品粒度 -> 空集。

    声明 store 会让「哪家店折扣最多」落到这里, 而它根本算不出按店拆分。
    """
    assert _RESOLVER_DIMENSIONS["RESTAURANT_OPS_DISCOUNT_SUMMARY"] == frozenset()
