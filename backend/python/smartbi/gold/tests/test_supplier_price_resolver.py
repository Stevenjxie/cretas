"""供应商比价 resolver + 它的路由。

🔴 存在的理由: 「哪个供应商报价最贵」此前落到 `OUT_OF_DOMAIN` —— 与天气、股票
   同一档, 意思是「这不属于餐饮经营数据」。**那是错的**: 供应商报价当然属于经营
   数据, 缺的只是终点。2026-08-08 的飞轮候选里这句出现 10 次, 差一点被人审晋升
   成 OUT_OF_DOMAIN, 那会**对所有租户永久关门**。

   ⇒ 判据: 「没有数据」和「不归我管」是两回事。前者要说清缺哪份数据,
     后者是把问题挡在门外。**别拿后者去表达前者。**

⚠️ **上面这句 2026-08-08 的读数已经过期** —— 2026-08-18 实测 MOCK_REST:

       obs 24 | ingredient 24 | **supplier 0** | delivery_date 全是 2026-08-08

   表里有数据了, 所以走的**不是**「没有数据」那条路, 而是「有数据但比不了价」。
   而供应商这一列**全是 NULL** —— 那一态的措辞见
   `test_no_supplier_recorded_says_the_column_is_empty`。
   ⚠️ 「全库 0 行」这一半**没有重测**, 只测了 MOCK_REST。
   ▎描述过期比没有描述更糟 —— 它让下一个人（包括我自己）跳过核实。
"""
from datetime import date

import pytest

from smartbi.gold.restaurant import restaurant_ops_router as R
from smartbi.gold.restaurant.restaurant_intent_service import _RESOLVER_DIMENSIONS

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

    def _install(observation_count, spread=None, *,
                 supplier_count=None, ingredient_count=None):
        # ⚠️ 2026-08-18: 桩原来只返回 `observation_count` —— **真实上游永远不会
        #    产出这个形状**（真 SQL 一次返回全部五个字段）。形态 B‴。
        #    后果是 resolver 读 `supplier_count` 恒为 0, 于是文案落到
        #    「一条都没记是哪家供应商」这一态, 而用例想说的是另一态。
        #    ⇒ 缺省值按 observation_count 推一个**自洽**的形状: 有观测就至少
        #      有 1 家供应商、1 种食材。要别的态就显式传。
        n_sup = (supplier_count if supplier_count is not None
                 else (1 if observation_count else 0))
        n_ing = (ingredient_count if ingredient_count is not None
                 else (1 if observation_count else 0))

        async def _cov(pool, factory_id, date_range, **kw):
            calls["coverage"] += 1
            return {"observation_count": observation_count,
                    "supplier_count": n_sup, "ingredient_count": n_ing,
                    "first_date": None, "last_date": None}

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
    sources(observation_count=120, spread={"items": []},
            supplier_count=3, ingredient_count=40)

    got = await R.resolve_supplier_price(
        _Pool(), "MOCK_REST", 90, role="factory_super_admin", query="哪个供应商报价最贵"
    )

    assert got.meta["comparable_items"] == 0
    assert "没有" in got.answer_text
    assert "不会拿不同食材的单价互相比" in got.answer_text
    assert "3 家供应商" in got.answer_text, got.answer_text
    assert "40 种食材" in got.answer_text, got.answer_text
    # 🔴 交付定义⑤ 第三件事: 他自己要干什么
    assert "你要做的" in got.answer_text, got.answer_text


@pytest.mark.asyncio
async def test_the_counts_follow_coverage_not_a_hardcoded_pair(sources):
    """⛔ 那两个数必须从 `coverage` 来 —— 换一份桩，数要跟着变。

    ⚠️ 补写的：变异「把数写死成 3 家/40 种」第一轮**全绿**，
       因为上一条用例的桩**恰好就是 3/40** —— 形态 C″
       「变异到达了，但打在断言看不见的地方」。
       ⇒ 判据必须是「换一份桩，数跟着变」，⛔ 不是「数对不对」。
    """
    sources(observation_count=500, spread={"items": []},
            supplier_count=7, ingredient_count=91)

    got = await R.resolve_supplier_price(
        _Pool(), "MOCK_REST", 90, role="factory_super_admin", query="哪个供应商报价最贵"
    )

    assert "7 家供应商" in got.answer_text, got.answer_text
    assert "91 种食材" in got.answer_text, got.answer_text
    assert got.meta["supplier_count"] == 7
    assert got.meta["ingredient_count"] == 91


@pytest.mark.asyncio
async def test_no_table_name_reaches_the_owner(sources):
    """🔴 表名是给工程师的 —— 三态 + 无数据那一支都不许出现。

    ⚠️ 补写的：变异「表名又给店长」第一轮**全绿** —— 那是**真的没守住**
       （⛔ 不是变异没到达）。机器可读侧留在 `meta.missing_source` 里，不丢。
    """
    cases = [
        dict(observation_count=0),
        dict(observation_count=24, spread={"items": []},
             supplier_count=0, ingredient_count=24),
        dict(observation_count=80, spread={"items": []},
             supplier_count=1, ingredient_count=12),
        dict(observation_count=120, spread={"items": []},
             supplier_count=3, ingredient_count=40),
    ]
    seen_meta = False
    for kw in cases:
        sources(**kw)
        got = await R.resolve_supplier_price(
            _Pool(), "MOCK_REST", 90, role="factory_super_admin",
            query="哪个供应商报价最贵")
        for tech in ("agg_supplier_price", "数据表", "字段", "normalized_name"):
            assert tech not in got.answer_text, (
                f"技术词漏给了店长: {tech}\n{got.answer_text}")
        seen_meta = seen_meta or (got.meta.get("missing_source") == "agg_supplier_price")
    # 阳性对照：表名没消失，只是挪到了机器可读侧
    assert seen_meta, "表名连 meta 里也没有了 —— 那是丢了，不是收好了"


@pytest.mark.asyncio
async def test_no_supplier_recorded_says_the_column_is_empty(sources):
    """🔴🔴 prod 上真正命中的那一态 —— 而原文在这一态上是**误导的**。

    📏 2026-08-18 实测（MOCK_REST，`agg_supplier_price`）:

        obs 24 | ingredient 24 | **supplier 0** | 2026-08-08

    24 条报价、24 种食材，而**供应商这一列全是 NULL**。
    原文说「没有任何一种食材同时来自两家以上供应商」——那暗示
    「每种食材有一家供应商」，真相是**一条都没记是哪家**。

    ⇒ 老板照它去做（「让两家都报价」）会**一无所获**：录进去照样没有那一栏。
      ▎反目标第一条：排在最前面的那个命中，他去查会不会一无所获。
    """
    sources(observation_count=24, spread={"items": []},
            supplier_count=0, ingredient_count=24)

    got = await R.resolve_supplier_price(
        _Pool(), "MOCK_REST", 90, role="factory_super_admin", query="哪个供应商报价最贵"
    )

    text = got.answer_text
    assert "每一条都没记是哪家供应商" in text, text
    assert "24 条采购报价" in text, text
    # ⛔ 阴性对照: 这一态**不许**说「没有任何一种食材同时来自两家以上供应商」
    assert "两家以上供应商" not in text, f"在这一态上那句话是误导的\n{text}"
    assert "你要做的：让采购在录单据时把供应商填上" in text, text
    assert got.meta["supplier_count"] == 0


@pytest.mark.asyncio
async def test_exactly_one_supplier_says_so(sources):
    """三态的中间那一态：真的只有一家供应商。

    ⛔ 它与「一条都没记」是两回事，措辞不能一样 —— 前者要他去找第二家，
       后者要他去填那一栏。
    """
    sources(observation_count=80, spread={"items": []},
            supplier_count=1, ingredient_count=12)

    got = await R.resolve_supplier_price(
        _Pool(), "MOCK_REST", 90, role="factory_super_admin", query="哪个供应商报价最贵"
    )

    text = got.answer_text
    assert "只有 1 家供应商" in text, text
    assert "每一条都没记" not in text, text
    assert "两家以上供应商" not in text, text
    assert "你要做的" in text, text


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


def test_bare_purchase_wording_stays_with_requisition():
    """🔴「采购花了多少钱」是**领料花费**(#2043 修过的口径), 不是供应商报价。

    这张词表现在只喂给契约校验与 LLM 提示, **不再授权任何执行**
    (关键词层已于 2026-08-08 整体撤除)。但收进裸「采购」仍然有害:
    它会让契约校验以为用户问的是供应商报价, 从而把一个已经修对的口径判成不符。
    """
    from smartbi.gold.restaurant.answer_contract import _REQUEST_TEXT_TOKENS

    assert "采购" not in _REQUEST_TEXT_TOKENS["supplier_price"]


def test_declared_grain_is_ingredient_only():
    """比价按食材粒度出, 不带门店/时段 —— 声明的是真能出的粒度。"""
    assert _RESOLVER_DIMENSIONS["RESTAURANT_OPS_SUPPLIER_PRICE"] == frozenset({"ingredient"})
