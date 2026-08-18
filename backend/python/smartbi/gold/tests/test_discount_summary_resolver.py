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


#: 📏 2026-08-18 prod 实测原文(MOCK_REST,「折扣力度多大」n=448) —— 前三种
#: 都是平台团购券, 合计 61.7%。用真实数字而不是随手编的百分比, 免得测试
#: 通过了但跟实际数据的形状对不上(形态 B‴)。
_PROD_SIX_TYPE_DISCOUNTS = [
    {"discount_name": "美团团购套餐券", "amount": 198423.0, "share_pct": 21.1},
    {"discount_name": "大众点评双人餐券", "amount": 190955.0, "share_pct": 20.3},
    {"discount_name": "抖音四人聚餐券", "amount": 190559.0, "share_pct": 20.3},
    {"discount_name": "外卖满50减8", "amount": 121430.0, "share_pct": 12.9},
    {"discount_name": "平台补贴红包", "amount": 120323.0, "share_pct": 12.8},
    {"discount_name": "新客首单立减", "amount": 119131.0, "share_pct": 12.7},
]


@pytest.mark.asyncio
async def test_concentration_sentence_names_the_top_three_and_sums_them(summary):
    """🔴 交付定义② 缺口: prod 实测「折扣力度多大」只念了他问的数 ——
    表里其实已经有答案(前三种合计 61.7%), 没人会替他把表格前三行的
    百分比加起来。这句话替他做。
    """
    summary({**_PROD_TOTAL, "discounts": _PROD_SIX_TYPE_DISCOUNTS})

    got = await R.resolve_discount_summary(
        _Pool(), "MOCK_REST", 30, role="factory_super_admin", query="折扣力度多大"
    )

    assert "折扣没有平均分布在各种促销上" in got.answer_text
    assert "美团团购套餐券、大众点评双人餐券、抖音四人聚餐券" in got.answer_text
    assert "61.7%" in got.answer_text, "前三种合计应为 21.1+20.3+20.3=61.7"
    assert "38.3%" in got.answer_text, "其余应为 100-61.7=38.3"


@pytest.mark.asyncio
async def test_concentration_sentence_is_silent_with_a_single_discount_type(summary):
    """🔴 阴性对照(反目标「排在最前面的命中要经得起他去查」): 只有一种折扣
    类型时,「前三种」等于「全部」——「前三种占了 100%」不提供任何信息，
    是一句经不起查的废话，必须不出现。
    """
    summary({**_PROD_TOTAL, "discounts": [
        {"discount_name": "满减", "amount": 3543242.2, "share_pct": 100.0},
    ]})

    got = await R.resolve_discount_summary(
        _Pool(), "MOCK_REST", 30, role="factory_super_admin", query="折扣力度多大"
    )

    assert "折扣没有平均分布在各种促销上" not in got.answer_text


@pytest.mark.asyncio
async def test_concentration_sentence_is_silent_at_the_trivial_boundary(summary):
    """🔴 边界: 正好 3 种时同样是「全部」，同样不该说——与「只有 1 种」是
    同一条纪律，单独钉住防止阈值被手滑从 `<=` 改成 `<`。
    """
    summary({**_PROD_TOTAL, "discounts": [
        {"discount_name": "满减", "amount": 2000000.0, "share_pct": 56.4},
        {"discount_name": "会员折扣", "amount": 1000000.0, "share_pct": 28.2},
        {"discount_name": "团购券", "amount": 543242.2, "share_pct": 15.4},
    ]})

    got = await R.resolve_discount_summary(
        _Pool(), "MOCK_REST", 30, role="factory_super_admin", query="折扣力度多大"
    )

    assert "折扣没有平均分布在各种促销上" not in got.answer_text


@pytest.mark.asyncio
async def test_concentration_sentence_is_not_gated_by_price_role(summary):
    """百分比不是价格权限数据(与「折扣占营收」同一条契约) —— 非价格角色也
    该看到「集中度」这句话，只是跟其余正文一样看不到 ¥ 绝对金额。
    """
    summary({**_PROD_TOTAL, "discounts": _PROD_SIX_TYPE_DISCOUNTS})

    got = await R.resolve_discount_summary(
        _Pool(), "MOCK_REST", 30, role="restaurant_hr", query="折扣力度多大"
    )

    assert "折扣没有平均分布在各种促销上" in got.answer_text
    assert "61.7%" in got.answer_text
    assert "¥" not in got.answer_text, "非价格角色的整份答案都不该出现绝对金额"


@pytest.mark.asyncio
async def test_concentration_sentence_closes_the_delivery_definition_two_gap(summary):
    """🔴 直接对着交付定义② 的判据验收 —— 这句话上线前,「折扣力度多大」只
    命中过 `b_口径`(收尾句的免责声明)一个标记；上线后要再命中 `a_对比`。

    ⚠️ 判据用**产品自己的探针词表**(`restaurant_delivery_definitions_probe`)，
       ⛔ 不是本文件另起一份关键词 —— 同一个判据两处各写一遍就是形态 D。

    ⚠️ 不在这里断言 `b_口径` 也命中：`_closing("DISCOUNT_CLOSING", ...)` 按天
       在三条等价措辞里轮换(`phrasing.pick_variant`)，其中两条只含「不能」，
       而 `b_口径` 的正则不认「不能」——只认「不代表/做不了」等 —— 那三条
       是否条条都够格命中 `b_口径`，是 `phrasing.py` 自己的口径问题，不在
       本次改动范围内(硬约束: 只碰 `restaurant_ops_router.py` + 本测试)。
       「边界被声明了」这件事已经由 `test_never_claims_discounts_caused_revenue`
       按 `REQUIRED_TOKENS` 逐日守住，不需要在这里重复一份、还绑死到某一天。
    """
    from smartbi.scripts.restaurant_delivery_definitions_probe import _EXTRA_MARKS

    summary({**_PROD_TOTAL, "discounts": _PROD_SIX_TYPE_DISCOUNTS})

    got = await R.resolve_discount_summary(
        _Pool(), "MOCK_REST", 30, role="factory_super_admin", query="折扣力度多大"
    )

    hits = {k for k, rx in _EXTRA_MARKS.items() if rx.search(got.answer_text)}
    assert "a_对比" in hits, f"「折扣力度多大」应该命中 a_对比, 实际命中: {hits}"


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

    # ⛔ 按**登记的必含标记**判，不搜某一句字面。
    #    收尾句 2026-08-08 起按天轮换措辞；第一版这里写死「不能据此说」，
    #    换成另一条同样带边界的变体后当场红 —— 那是在量措辞不是量行为。
    #    真正要保证的是「边界被声明了」, 而不是「用哪句话声明的」。
    from smartbi.gold.restaurant.phrasing import REQUIRED_TOKENS

    markers = REQUIRED_TOKENS["DISCOUNT_CLOSING"]
    assert any(m in got.answer_text for m in markers), (
        f"必须主动声明这条边界(需含 {markers} 之一)"
    )


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
