"""供应商比价 resolver + 它的路由。

🔴 存在的理由: 「哪个供应商报价最贵」此前落到 `OUT_OF_DOMAIN` —— 与天气、股票
   同一档, 意思是「这不属于餐饮经营数据」。**那是错的**: 供应商报价当然属于经营
   数据, 缺的只是终点。2026-08-08 的飞轮候选里这句出现 10 次, 差一点被人审晋升
   成 OUT_OF_DOMAIN, 那会**对所有租户永久关门**。

   ⇒ 判据: 「没有数据」和「不归我管」是两回事。前者要说清缺哪份数据,
     后者是把问题挡在门外。**别拿后者去表达前者。**

⚠️ 2026-08-08 实测 `agg_supplier_price` **全库 0 行**(0 个租户接入过), 所以今天
   这个 resolver 对任何租户都会走「没有数据」那条路。它的价值不是产出数字,
   而是把一个错误答案换成正确答案。有数据那条路由 mock 覆盖。
"""
from datetime import date

import pytest

from smartbi.gold.restaurant import restaurant_ops_router as R
from smartbi.gold.restaurant.restaurant_intent_service import _RESOLVER_DIMENSIONS
from smartbi.gold.restaurant.structural_route import resolve_structurally

ALL_INTENTS = tuple(_RESOLVER_DIMENSIONS)

_SPREAD = {
    "items": [
        {"ingredient_name": "牛腩", "unit": "kg", "supplier_count": 3,
         "highest_price": 68.0, "highest_supplier": "甲供应商",
         "lowest_price": 52.0, "lowest_supplier": "乙供应商", "spread_pct": 30.8},
        {"ingredient_name": "土豆", "unit": "kg", "supplier_count": 2,
         "highest_price": 4.2, "highest_supplier": "丙供应商",
         "lowest_price": 3.6, "lowest_supplier": "丁供应商", "spread_pct": 16.7},
    ]
}


class _Pool:
    def acquire(self):
        raise AssertionError("resolver 必须走 gold.queries, 不该自己开连接")


@pytest.fixture
def sources(monkeypatch):
    """替换两个 gold 查询, 并记录调用 —— 口径必须复用, 不许就地写 SQL。"""
    calls = {"coverage": 0, "spread": 0}

    def _install(observation_count, spread=None):
        async def _cov(pool, factory_id, date_range, **kw):
            calls["coverage"] += 1
            return {"observation_count": observation_count}

        async def _spread(pool, factory_id, date_range, **kw):
            calls["spread"] += 1
            return dict(spread or {"items": []})

        import smartbi.gold.queries as Q
        monkeypatch.setattr(Q, "supplier_price_coverage", _cov)
        monkeypatch.setattr(Q, "supplier_price_spread", _spread)
        return calls

    return _install


@pytest.mark.asyncio
async def test_no_data_says_what_is_missing_not_out_of_domain(sources):
    """🔴 全库 0 行时的答案必须说「缺哪份数据」, ⛔ 不能表达成「不归我管」。"""
    calls = sources(observation_count=0)

    got = await R.resolve_supplier_price(
        _Pool(), "MOCK_REST", 90, role="factory_super_admin", query="哪个供应商报价最贵"
    )

    assert got.code == "RESTAURANT_OPS_SUPPLIER_PRICE"
    assert got.meta["no_data"] is True
    assert got.meta["missing_source"] == "agg_supplier_price"
    assert "不是「不归我管」" in got.answer_text, "必须显式否定域外这个说法"
    assert "接入" in got.answer_text, "必须说清缺的是什么"
    assert calls["spread"] == 0, "没有数据时不该再去跑比价查询"


@pytest.mark.asyncio
async def test_price_role_sees_prices_and_spread(sources):
    sources(observation_count=120, spread=_SPREAD)

    got = await R.resolve_supplier_price(
        _Pool(), "MOCK_REST", 90, role="factory_super_admin", query="哪个供应商报价最贵"
    )

    assert "牛腩" in got.answer_text and "甲供应商" in got.answer_text
    assert "¥68.00" in got.answer_text
    assert got.meta["comparable_items"] == 2


@pytest.mark.asyncio
async def test_non_price_role_gets_who_not_how_much(sources):
    """金额是价格权限数据 —— 谁最贵可以说, 具体单价不行。"""
    sources(observation_count=120, spread=_SPREAD)

    got = await R.resolve_supplier_price(
        _Pool(), "MOCK_REST", 90, role="restaurant_hr", query="哪个供应商报价最贵"
    )

    assert "甲供应商" in got.answer_text and "30.8%" in got.answer_text
    assert "68.00" not in got.answer_text and "52.00" not in got.answer_text


@pytest.mark.asyncio
async def test_single_supplier_ingredients_are_not_compared(sources):
    """🔴 阴性对照: 有数据但没有任何食材有 2 家以上供应商 -> 没有可比对象。

    ⛔ 绝不拿不同食材的单价互相比 —— 牛肉比米贵不是「供应商贵」。
    """
    sources(observation_count=120, spread={"items": []})

    got = await R.resolve_supplier_price(
        _Pool(), "MOCK_REST", 90, role="factory_super_admin", query="哪个供应商报价最贵"
    )

    assert got.meta["comparable_items"] == 0
    assert "没有" in got.answer_text
    assert "不会拿不同食材的单价互相比" in got.answer_text


@pytest.mark.asyncio
async def test_never_promises_savings(sources):
    """⛔ 价差大 ≠ 换供应商就能省下这个比例 —— 用量/供货能力/品质都不在这张表里。"""
    sources(observation_count=120, spread=_SPREAD)

    got = await R.resolve_supplier_price(
        _Pool(), "MOCK_REST", 90, role="factory_super_admin", query="哪个供应商报价最贵"
    )

    negation = ("不等于", "不能", "无法", "还要看")
    for claim in ("能省", "可省", "节省", "省下"):
        offenders = [
            s for s in got.answer_text.replace("\n", "。").split("。")
            if claim in s and not any(n in s for n in negation)
        ]
        assert not offenders, f"承诺了节省: {offenders}"


def test_l1_routes_supplier_price_wording():
    for phrase in ("哪个供应商报价最贵", "最近30天哪个供应商报价最贵", "进价涨了吗"):
        got = resolve_structurally(phrase, candidate_intents=ALL_INTENTS)
        assert got is not None, phrase
        assert got.intent == "RESTAURANT_OPS_SUPPLIER_PRICE", phrase


def test_bare_purchase_wording_stays_with_requisition():
    """🔴 阴性对照:「采购花了多少钱」是**领料花费**(#2043 修过的口径), 不是供应商报价。

    所以词表刻意不收裸「采购」。收了就会把一个已经修对的口径重新抢走 ——
    那次事故的形态是「同一个问题换个说法就时灵时不灵」。
    """
    from smartbi.gold.restaurant.answer_contract import _REQUEST_TEXT_TOKENS

    assert "采购" not in _REQUEST_TEXT_TOKENS["supplier_price"]
    assert resolve_structurally("采购花了多少钱", candidate_intents=ALL_INTENTS) is None


def test_declared_grain_is_ingredient_only():
    """比价按食材粒度出, 不带门店/时段 —— 声明的是真能出的粒度。"""
    assert _RESOLVER_DIMENSIONS["RESTAURANT_OPS_SUPPLIER_PRICE"] == frozenset({"ingredient"})
