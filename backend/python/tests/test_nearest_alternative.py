"""不支持的指标要给「**最接近的替代**」，⛔ 不是只说缺什么。

缺口清单第 21 项。架构定稿例 17 要的是：

    「翻台率我算不了 —— 缺【台位数】和【每桌就餐时长】。这两项补上来我就能算。
      眼下最接近的是每小时订单数和时段分布，要不要先看这个？」

## 📏 缺陷（prod 实测，MOCK_REST，「翻台率怎么样」原文 155 字）

```
**翻台率（缺少桌台、开台/结账时间、就餐轮次和可用桌数）现在算不出来。**
缺的是：翻台率（…）
我这儿有的是：营收和折扣（比如营收）、成本和毛利（比如食材成本）、…
想看哪一样，直接说它的名字就行（例如「营收」）。
```

三件事都有了（缺什么 / 我有什么 / 说个名字），**没有「最接近的替代」**。

## 🔴 这一轮最容易做错的事

▎排在最前面的那个替代，老板照着去问，**如果答不上来就是一条误发的提示**。
▎一条误发的提示烧掉的是「这东西说的话能信」。

所以判据不是「像不像」，是**这个租户今天真的算得出来吗** ——
由 `computable_metric_keys` 查库（registry ∩ schema ∩ 本租户列非空值）回答。
⛔ 查不到可算的近邻 → 那一条不给替代。

## 📏 prod 实测（2026-08-18，smartbi_prod_db，RLS 策略已核对
##    = `factory_id = current_setting('app.factory_id')`）

| 租户 | POS 事实表 | 给出的替代 |
|---|---|---|
| MOCK_REST | 100,518 单 / 401,818 条明细 | 净利润→毛利、翻台率→订单数 |
| RES_3101_009 / DEMO_REST / R_XMX_CHAIN / R_GML_DEMO | **0 行** | 一个都不给 |

`fact_pos_item.return_qty` 在 5 个租户上非空值都是 **0** ⇒ 退菜率**不给替代**。
"""
from __future__ import annotations

import asyncio
from contextlib import asynccontextmanager

import pytest

from smartbi.gold.restaurant.capability_answer import (
    TenantCapability,
    computable_metric_keys,
    missing_capability_labels,
    nearest_alternatives,
    render_capability_refusal,
    tenant_capability,
)
from smartbi.gold.restaurant.metric_registry import DERIVED, METRICS
from smartbi.gold.restaurant.restaurant_intent import (
    _UNSUPPORTED_REQUIREMENT_LABELS,
    _UNSUPPORTED_REQUIREMENT_NEIGHBOURS,
)

# ── 声明本身：不许漂、不许漏 ────────────────────────────────────────────


def test_每个近邻都是登记表上真实存在的key():
    """🔴 承重：声明的近邻必须是 `METRICS`/`DERIVED` 上真实存在的 key。

    这一条是「构成关系」与「手写联想表」的分界线：联想表写错了没有任何东西会红，
    而它的产物是一句发给老板的建议。

    变异实测: 把 `table_turnover` 的近邻改成 `("orders_typo",)`
      → 红:「近邻 'orders_typo' 不在登记表上」
    """
    known = set(METRICS) | set(DERIVED)
    for code, keys in _UNSUPPORTED_REQUIREMENT_NEIGHBOURS.items():
        for key in keys:
            assert key in known, f"{code} 的近邻 {key!r} 不在登记表上 —— 改名/下线了?"


def test_每个算不出来的量都做过近邻决定():
    """🔴 承重：两张表的键集合必须**逐字相等**（硬约束 8：改共享结构前后要数）。

    ⚠️ 空元组 = 明确登记「没有近邻」，⛔ 不是忘了写。新增一个算不出来的量
       而不做这个决定，就会静默地什么替代都不给 —— 而**沉默是不报错的**。

    变异实测: 从 NEIGHBOURS 里删掉 `process_bottleneck`
      → 红:「这些量登记了标签却没做近邻决定: ['process_bottleneck']」
    """
    labels = set(_UNSUPPORTED_REQUIREMENT_LABELS)
    neighbours = set(_UNSUPPORTED_REQUIREMENT_NEIGHBOURS)
    assert not (labels - neighbours), (
        f"这些量登记了标签却没做近邻决定: {sorted(labels - neighbours)}")
    assert not (neighbours - labels), (
        f"这些近邻声明没有对应的标签(量已下线?): {sorted(neighbours - labels)}")


def test_近邻不许把那个算不出来的量自己算进去():
    """⛔ 阴性对照：替代不能是它自己（`return_rate` 同时是 DERIVED 的 key）。"""
    for code, keys in _UNSUPPORTED_REQUIREMENT_NEIGHBOURS.items():
        assert code not in keys, f"{code} 把自己列成了自己的替代"


# ── 能不能给出去：由查库决定，⛔ 不由声明决定 ──────────────────────────


def test_替代必须是本租户实测算得出来的():
    """🔴 承重：声明里有，但这个租户算不出来 → **不给**。

    这是本轮唯一测得出「它有没有在承诺一个空头支票」的写法。

    变异实测: 让 `nearest_alternatives` 忽略 `computable`（直接取声明第一项）
      → 红:「这个租户算不出订单数，却把它推荐出去了」
    """
    rich = nearest_alternatives(("table_turnover",), {"orders", "revenue"})
    assert rich and rich[0][0] == "订单数", rich

    poor = nearest_alternatives(("table_turnover",), {"revenue"})
    assert poor == (), f"这个租户算不出订单数，却把它推荐出去了: {poor}"


def test_两个覆盖度不同的租户拿到不同的替代():
    """🔴 承重：它必须随租户变。不变 = 它退回成常量了。"""
    with_cost = nearest_alternatives(("net_profit",), {"revenue", "gross_profit"})
    without_cost = nearest_alternatives(("net_profit",), {"revenue"})
    assert with_cost != without_cost, "两个覆盖度不同的租户拿到同一个替代 —— 它是常量"
    assert with_cost[0][0] == "毛利"
    assert without_cost[0][0] == "营收", "毛利算不出来时应退到营收这一侧"


def test_没有可算近邻的四项一个替代都不给():
    """🔴 承重：查不到近邻就**不提示**。宁可这一类先不说。

    ⚠️ 这四项在**任何**租户上都不给替代 —— 它们的定义式里没有一项是已登记指标。
    """
    everything = set(METRICS) | set(DERIVED)
    for code in ("customer_review", "production_time", "service_speed",
                 "process_bottleneck"):
        assert nearest_alternatives((code,), everything) == (), (
            f"{code} 不该有替代 —— 它的定义式里一个已登记指标都没有")


def test_不退而求其次去同类里挑一个():
    """🔴 承重：⛔ 同类 ≠ 接近。

    销量与退菜率同属「客流和销量」，而销量跟退菜一个字都不沾 ——
    拿它当替代，老板照着去问会发现文不对题，那正是一条误发的提示。

    变异实测: 给 `nearest_alternatives` 加一条「近邻都不可算时按 category 兜底」
      → 红:「退菜率被兜了一个同类指标当替代」
    """
    # 销量/营收/订单数全都算得出来，只有退菜量算不出来
    computable = {"sales_qty", "revenue", "orders", "guests"}
    assert nearest_alternatives(("return_rate",), computable) == (), (
        "退菜率被兜了一个同类指标当替代")
    # 阳性对照：同一份 computable 下，翻台率**是**给得出替代的 ——
    # 证明上面那个空不是「函数整个坏了」
    assert nearest_alternatives(("table_turnover",), computable)[0][0] == "订单数"


def test_限定语跟着替代一起出去():
    """🔴 承重：拿毛利当净利润的替代**必须带上「未扣人工、房租、水电」**。

    不带限定语地拿毛利顶净利润，就是系统承诺不做的「相邻指标顶替」，
    只是换到了建议这一侧。

    ⚠️ 限定语取自 `Derived.caveat_short`（登记表字段），⛔ 不在这里手写 ——
       手写的话新登记一个同类指标不会自动带上，而漏掉不报错。

    变异实测: 把 `gross_profit.caveat_short` 置空
      → 红:「毛利被推荐出去时没带限定语」
    """
    alt = nearest_alternatives(("net_profit",), {"revenue", "gross_profit"})
    assert alt[0][0] == "毛利"
    assert alt[0][1], "毛利被推荐出去时没带限定语"
    text = render_capability_refusal(["净利润（缺少费用、税费及其他收支）"],
                                     [("成本和毛利", "食材成本")], alt)
    line = [ln for ln in text.splitlines() if "最接近" in ln]
    assert line, "没有「最接近」那一句\n" + text
    assert alt[0][1] in line[0], "限定语没和替代待在同一句: " + line[0]


# ── 派生量的闭包 ────────────────────────────────────────────────────────


def test_派生量左右两侧都算得出来才算得出来():
    """🔴 承重：毛利 = 营收 − 食材成本。缺一侧就不该算「能算」。

    变异实测: 把闭包判断从 `left in ok and right in ok` 改成 `or`
      → 红:「只有营收没有成本，毛利不该算得出来」
    """
    schema = {"fact_pos_transaction.net_amount", "fact_pos_item.amount",
              "fact_pos_item.qty", "agg_restaurant_product_cost.food_cost"}
    full = {c: 100 for c in schema}
    assert "gross_profit" in computable_metric_keys(schema, full)
    no_cost = {**full, "agg_restaurant_product_cost.food_cost": 0}
    assert "food_cost" not in computable_metric_keys(schema, no_cost)
    assert "gross_profit" not in computable_metric_keys(schema, no_cost), (
        "只有营收没有成本，毛利不该算得出来")


def test_算不出来的那个量永远不出现在可算集合里():
    """⛔ 阴性对照：`return_rate` 同时是 DERIVED 的 key，绝不能被算成「能算」。"""
    schema = {"fact_pos_item.qty", "fact_pos_item.return_qty"}
    full = {c: 100 for c in schema}
    ok = computable_metric_keys(schema, full, unsupported=("return_rate",))
    assert "return_rate" not in ok
    assert "return_qty" in ok, "只该禁掉那一个，不该整份塌掉"


# ── 📏 真登记表 + prod 实测读数 ─────────────────────────────────────────

#: 📏 prod 实测 MOCK_REST 的列非空值计数（2026-08-18，见探针
#:    `smartbi/scripts/_nearest_alt_probe.py`）。⛔ 不是编的。
_MOCK_REST_COUNTS = {
    "agg_restaurant_product_cost.food_cost": 7,
    "fact_pos_item.amount": 401818,
    "fact_pos_item.qty": 401818,
    "fact_pos_item.return_qty": 0,
    "fact_pos_transaction.actual_receive": 0,
    "fact_pos_transaction.customer_count": 100518,
    "fact_pos_transaction.discount_amount": 100518,
    "fact_pos_transaction.gross_amount": 100518,
    "fact_pos_transaction.has_discount": 100518,
    "fact_pos_transaction.id": 100518,
    "fact_pos_transaction.net_amount": 100518,
    "fact_pos_transaction.platform_fee_amount": 100518,
    "fact_pos_transaction.tax_amount": 0,
    "fact_restaurant_wastage.estimated_cost": 20480,
    "fact_restaurant_wastage.quantity": 20480,
}
_MOCK_REST_SCHEMA = set(_MOCK_REST_COUNTS)


@pytest.mark.parametrize("code,expected", [
    ("net_profit", "毛利"),
    ("table_turnover", "订单数"),
    ("return_rate", None),          # 📏 return_qty 非空值 0
    ("customer_review", None),
    ("production_time", None),
    ("service_speed", None),
    ("process_bottleneck", None),
])
def test_七个量在真登记表上逐条的结果(code, expected):
    """📏 用 prod 实测读数跑**真**登记表，逐条钉住 7 项各自给什么。"""
    ok = computable_metric_keys(_MOCK_REST_COUNTS and _MOCK_REST_SCHEMA,
                                _MOCK_REST_COUNTS, unsupported=(code,))
    alt = nearest_alternatives((code,), ok)
    got = alt[0][0] if alt else None
    assert got == expected, f"{code} 给出的替代是 {got!r}，预期 {expected!r}"


def test_一行数据都没有的租户一个替代都不给():
    """📏 prod 实测：另外 4 个租户 POS 事实表 0 行 ⇒ 一个替代都不给。

    ⚠️ 这条同时是上一条的**阴性对照**：同一份声明、不同的租户读数，
       结果必须不同。都给替代 = 它没在读租户。
    """
    empty = {c: 0 for c in _MOCK_REST_SCHEMA}
    ok = computable_metric_keys(_MOCK_REST_SCHEMA, empty)
    for code in _UNSUPPORTED_REQUIREMENT_NEIGHBOURS:
        assert nearest_alternatives((code,), ok) == (), (
            f"POS 一行都没有的租户，{code} 还是给出了替代")


# ── 渲染：三件事都在，动作只有一个 ──────────────────────────────────────

_GROUPS = (("营收和折扣", "营收"), ("成本和毛利", "食材成本"))
_ALT = (("订单数", ""),)


def test_最接近的那一句真的在正文里():
    """🔴 承重：这一轮要补的就是它。"""
    text = render_capability_refusal(["翻台率（缺少桌台…）"], _GROUPS, _ALT)
    assert "眼下最接近的是订单数" in text, "没有「最接近的替代」\n" + text


def test_有替代时动作句只有一个():
    """⛔ 两句「你可以说个名字」并排出现，老板要先读懂它们是不是同一件事。

    变异实测: 把 `if not alternatives:` 那个守卫去掉
      → 红:「同时出现了两个动作句」
    """
    text = render_capability_refusal(["翻台率"], _GROUPS, _ALT)
    assert "想看哪一样" not in text, "同时出现了两个动作句\n" + text
    assert text.count("就行") == 1


def test_没有替代时退回原来那句通用动作():
    """⛔ 阴性对照：没有替代时**不许**把老动作一起弄丢（那是净损失）。"""
    text = render_capability_refusal(["顾客评价"], _GROUPS, ())
    assert "眼下最接近" not in text
    assert "直接说它的名字" in text, "没有替代时连原来那句动作也没了\n" + text


def test_替代不许挤掉前两件事():
    """⛔ 阴性对照：加第三件不许把「算不出来」「缺的是」挤掉，且顺序不许反。"""
    text = render_capability_refusal(["翻台率（缺少桌台…）"], _GROUPS, _ALT)
    assert text.index("算不出来") < text.index("缺的是") < text.index("最接近"), text


def test_替代不是可照抄的引号问句():
    """⛔ 引号里必须是**一个名字**（短），不是一句问话（📏 那一类 4/4 兑现不了）。"""
    import re
    text = render_capability_refusal(["翻台率"], _GROUPS, _ALT)
    for quoted in re.findall(r"[「『]([^」』]*)[」』]", text):
        assert len(quoted) <= 8, f"引号里是一句问话而不是一个名字: {quoted!r}"
        assert "怎么样" not in quoted and "？" not in quoted


@pytest.mark.parametrize("jargon", ["可靠覆盖", "相邻指标", "维度", "可验证结果"])
def test_最接近那一句不带黑话(jargon):
    text = render_capability_refusal(["净利润（缺少费用…）"], _GROUPS,
                                     (("毛利", "未扣人工、房租、水电"),))
    assert jargon not in text


# ── 🔴 接上没有：按调用点的**原样两行**跑一遍 ──────────────────────────


class _FakeConn:
    def __init__(self, counts):
        self._counts = counts

    async def fetchrow(self, sql, factory_id):
        # 只回答「这张表里我要的那几列各有多少非空值」——
        # 列名从 SELECT 里那几个 AS "表.列" 抠出来。
        import re
        cols = re.findall(r'AS "([^"]+)"', sql)
        return {c: self._counts.get(c, 0) for c in cols}


def _install_fake_db(monkeypatch, counts, schema=None):
    """把查库那两个协作者换掉。

    ⚠️ 两个都是 `tenant_capability` **函数体内** import 的，所以打在模块属性上
       够得着（形态 B⁶：模块级 from-import 会让 `monkeypatch.setattr` 打空）。
    """
    from smartbi.gold import queries
    from smartbi.gold.restaurant import generic_executor

    @asynccontextmanager
    async def fake_tenant_conn(pool, factory_id):
        yield _FakeConn(counts)

    async def fake_existing_columns(conn):
        return set(schema if schema is not None else counts)

    monkeypatch.setattr(queries, "tenant_conn", fake_tenant_conn)
    monkeypatch.setattr(generic_executor, "existing_columns", fake_existing_columns)


def _no_swallowed_error(monkeypatch):
    """`tenant_capability` 把异常吞成空元组 —— 空结果和「查不动」长得一样。

    ⚠️ 所以每条 wiring 断言都要先证明**没有异常被吞掉**，
       否则「没有替代」这个读数分不清是产品的决定还是我的桩坏了。
    """
    from smartbi.gold.restaurant import capability_answer
    seen = []
    monkeypatch.setattr(capability_answer.logger, "warning",
                        lambda *a, **k: seen.append(a))
    return seen


def test_两行调用点原样接得上(monkeypatch):
    """🔴🔴 承重：这一条是「机制在、没接上」的唯一判据。

    下面两行是 `restaurant_intent_service.py:1917-1920` 的**原样**：
    加一个 `alternatives=` 参数而没人传它 —— 单测会全绿，线上一个字不变。

    变异实测: 让 `tenant_capability` 返回裸 `tuple`（不挂 alternatives）
      → 红:「查库那一步没把替代带出来 —— 加了参数没人传」
    """
    swallowed = _no_swallowed_error(monkeypatch)
    _install_fake_db(monkeypatch, _MOCK_REST_COUNTS)
    unsupported = ("table_turnover",)

    # ↓↓ 调用点原样两行 ↓↓
    available = asyncio.run(tenant_capability(None, "MOCK_REST", unsupported))
    text = render_capability_refusal(missing_capability_labels(unsupported), available)
    # ↑↑ 调用点原样两行 ↑↑

    assert not swallowed, f"查库那一步抛了异常被吞掉，这条读数无效: {swallowed}"
    assert available, "阳性对照没过：连「我这儿有的是」都空了，桩坏了"
    assert "眼下最接近的是订单数" in text, (
        "查库那一步没把替代带出来 —— 加了参数没人传\n" + text)


def test_同一条调用链上租户没有数据就不给替代(monkeypatch):
    """🔴 阴性对照：同一条链、同一份声明，换个租户读数就该没有替代。

    ⚠️ 与上一条**必须成对**：只有上一条时，把替代写死成「订单数」也全绿。
    """
    swallowed = _no_swallowed_error(monkeypatch)
    no_pos = {**_MOCK_REST_COUNTS, "fact_pos_transaction.id": 0}
    _install_fake_db(monkeypatch, no_pos)
    unsupported = ("table_turnover",)

    available = asyncio.run(tenant_capability(None, "T2", unsupported))
    text = render_capability_refusal(missing_capability_labels(unsupported), available)

    assert not swallowed, f"查库那一步抛了异常被吞掉，这条读数无效: {swallowed}"
    assert available, "阳性对照没过：整份清单都空了，说明是桩坏了不是没数据"
    assert "眼下最接近" not in text, "这个租户算不出订单数，却还是推荐了它\n" + text


def test_查不动时替代也是空的():
    """⛔ 查不动 → 裸元组 → `getattr(..., 'alternatives', ())` 拿到空，不猜。"""
    assert render_capability_refusal(["翻台率"], ()) .count("最接近") == 0
    assert TenantCapability((), ()).alternatives == ()
