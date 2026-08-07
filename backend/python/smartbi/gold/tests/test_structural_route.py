"""L1 结构解析：只用可证伪的证据授权，歧义即拒。

漏斗里 L0（整句相等）与 L3（LLM，3.2k token）之间原本是空的。
L1 填的就是这一段：**闭集槽位 + 目录校验**，一份新判断逻辑都不发明。

⛔ 这层一旦授权就没有 LLM 兜底了，所以两条规矩缺一不可：
  1. 多个意图都能服务 → 不授权；
  2. 槽位认不出来（尤其目录不可用）→ 不授权。
拒的代价是慢一点；挑错的代价是答非所问、且可能被晋升成零 token 永久重放。
"""
import pytest

from smartbi.gold.restaurant import restaurant_ops_router as RR
from smartbi.gold.restaurant.restaurant_intent_service import _RESOLVER_DIMENSIONS
from smartbi.gold.restaurant.structural_route import (
    _metrics_in,
    resolve_structurally,
)

#: ⛔ 用**全部**已注册意图，不许挑一个子集。挑子集会人为消掉歧义 ——
#:   那正是 `_METRIC_SERVERS` 第一版的失效方式，测试不能重演同一个错误。
ALL_INTENTS = tuple(_RESOLVER_DIMENSIONS)


@pytest.fixture
def catalogue():
    tokens = []
    yield lambda names: tokens.append(RR.set_dish_catalogue(set(names)))
    for t in reversed(tokens):
        RR.reset_dish_catalogue(t)


def test_single_metric_resolves_without_llm():
    got = resolve_structurally("损耗情况怎么样", candidate_intents=ALL_INTENTS)
    assert got is not None
    assert got.intent == "RESTAURANT_OPS_WASTAGE_TOP"
    assert got.metrics == ("wastage",)
    assert "唯一可服务者" in got.evidence, "必须留下「凭什么这么判」的证据"


#: prod `ai_promoted_routes`(domain=restaurant) 2026-08-07 快照 —— **人审通过**的
#: 问句→意图。这是本轮唯一能证伪 `_METRIC_SERVERS` 的外部基准：那张表的正确性
#: 没法从代码里推出来（仓里没有意图↔指标的既有能力表）。
_AUDITED_PROMOTIONS = (
    ("最近30天总营收是多少", "RESTAURANT_OPS_SALES_SUMMARY"),
    ("加权毛利率是多少", "RESTAURANT_OPS_GROSS_MARGIN"),
    ("外卖和堂食各占多少", "RESTAURANT_OPS_CHANNEL_MIX"),
    ("哪个时段生意最好", "RESTAURANT_OPS_DAYPART_PERFORMANCE"),
    ("库存有什么要注意的", "RESTAURANT_OPS_INVENTORY_WARNING"),
    ("员工人效怎么样", "RESTAURANT_OPS_STAFFING_ADVICE"),
    ("哪个菜卖得好", "RESTAURANT_OPS_GROSS_MARGIN"),
    ("哪个菜卖得最好", "RESTAURANT_OPS_GROSS_MARGIN"),
    ("哪个菜最好卖", "RESTAURANT_OPS_GROSS_MARGIN"),
    ("毛利最低的菜品有哪些", "RESTAURANT_OPS_GROSS_MARGIN"),
    ("最近损耗情况怎么样", "RESTAURANT_OPS_WASTAGE_TOP"),
    ("营收趋势怎么样", "RESTAURANT_OPS_TREND_ANALYSIS"),
)


def test_agrees_with_every_audited_promotion():
    """🔴 对人审过的每一条：**可以沉默，不许分歧**。

    这道闸抓到过一次真缺陷。第一版按「意图 → 主指标」登记，`revenue` 只给了
    SALES_SUMMARY，「营收趋势怎么样」于是成了唯一命中 → L1 授权总览，与人审的
    TREND_ANALYSIS 相反。**漏登记把歧义伪装成了确定。**

    ⛔ 分歧的代价不是「慢一点」：L1 授权的意图会进飞轮候选，被晋升成零 token
       永久重放 —— 一条错的路由会被固化。所以这里的容忍度是 0。
    """
    disagreements = [
        (phrase, got.intent, want)
        for phrase, want in _AUDITED_PROMOTIONS
        if (got := resolve_structurally(phrase, candidate_intents=ALL_INTENTS))
        and got.intent != want
    ]
    assert not disagreements, f"L1 与人审结论相反(必须是 0 条): {disagreements}"


def test_cross_metric_question_is_refused():
    """🔴「食材成本占营收」同时认出两个指标，没有单一终点 → 交给 L3。

    这正是想要的：它是个跨指标的比率问题，不该被硬塞进某一个 resolver。
    本轮 prod 上这句就吃过「被标成食材粒度」的亏。
    """
    metrics = _metrics_in("最近30天食材成本占营收多少")
    assert set(metrics) >= {"recipe_cost", "revenue"}, "前提: 这句确实认出两个指标"

    assert resolve_structurally(
        "最近30天食材成本占营收多少", candidate_intents=ALL_INTENTS
    ) is None


def test_no_metric_means_no_evidence():
    """一个闭集指标都没认出来 = 没有可证伪的证据 → 不授权。"""
    assert resolve_structurally("今天天气怎么样", candidate_intents=ALL_INTENTS) is None
    assert resolve_structurally("", candidate_intents=ALL_INTENTS) is None


def test_named_dish_alone_does_not_disambiguate_margin(catalogue):
    """🔴 点名真实菜**不足以**定终点 —— 菜品毛利与门店毛利都声明 dish 粒度。

    这条记录的是实测事实，不是设计愿望：dish 这一维在当前意图集上不构成区分，
    于是「罗氏虾的毛利率」照旧交给 L3。**允许沉默，不允许挑一个。**

    ⚠️ 同理「罗氏虾的营业额」：`revenue` 的服务者里若漏掉 GROSS_MARGIN，
       dish 维度会把它唯一收窄到 STORE_MARGIN(门店毛利) —— 那是错的终点。
    """
    catalogue({"罗氏虾", "鲈鱼"})

    assert resolve_structurally("罗氏虾的毛利率是多少", candidate_intents=ALL_INTENTS) is None
    assert resolve_structurally("罗氏虾的营业额是多少", candidate_intents=ALL_INTENTS) is None


def test_dimension_narrowing_works_when_servers_differ_on_grain(catalogue):
    """维度确实能收窄 —— 前提是候选们在该粒度上**有分歧**。

    构造一个服务者集合：一个声明 ingredient，一个不声明。点名菜品后只剩一个。
    """
    catalogue({"罗氏虾"})
    import smartbi.gold.restaurant.structural_route as sr

    original = dict(sr._METRIC_SERVERS)
    try:
        # RECIPE_COST 声明 {dish}; WASTAGE_TOP 声明 {ingredient} 不含 dish。
        sr._METRIC_SERVERS["recipe_cost"] = frozenset(
            {"RESTAURANT_OPS_RECIPE_COST", "RESTAURANT_OPS_WASTAGE_TOP"}
        )
        assert resolve_structurally(
            "食材成本是多少", candidate_intents=ALL_INTENTS
        ) is None, "没有维度证据时两个候选并存 -> 拒"

        got = resolve_structurally("罗氏虾的食材成本是多少", candidate_intents=ALL_INTENTS)
        assert got is not None and got.intent == "RESTAURANT_OPS_RECIPE_COST"
        assert got.dimensions == ("dish",)
    finally:
        sr._METRIC_SERVERS.clear()
        sr._METRIC_SERVERS.update(original)


def test_catalogue_unavailable_means_no_dish_dimension(catalogue):
    """🔴 阴性对照：目录不可用时**不认** dish 粒度。

    `extract_dish_candidate` 在目录缺席时 fail-open（拿黑名单兜底），
    但 L1 不能拿一个没校验过的候选去授权执行 —— 那就退回猜了。
    """
    catalogue(set())  # 目录不可用

    got = resolve_structurally("罗氏虾的毛利率是多少", candidate_intents=ALL_INTENTS)

    # 仍可能凭指标授权，但**绝不能**声称认出了 dish 粒度。
    assert got is None or got.dimensions == ()


def test_ambiguity_is_refused(catalogue):
    """⛔ 多个意图都能服务 → 不授权，绝不挑一个。

    构造：把两个意图都登记成 wastage 的主终点。
    """
    catalogue({"罗氏虾"})
    import smartbi.gold.restaurant.structural_route as sr

    original = dict(sr._METRIC_SERVERS)
    try:
        sr._METRIC_SERVERS["wastage"] = frozenset(
            {"RESTAURANT_OPS_WASTAGE_TOP", "RESTAURANT_OPS_STOCK_SHORTAGE"}
        )
        assert resolve_structurally(
            "损耗情况怎么样", candidate_intents=ALL_INTENTS
        ) is None, "两个意图都能服务时必须拒绝"
    finally:
        sr._METRIC_SERVERS.clear()
        sr._METRIC_SERVERS.update(original)


def test_unregistered_intent_never_authorizes():
    """没登记主指标的意图不参与授权 —— 不登记就是交给 L3。"""
    got = resolve_structurally("损耗情况怎么样", candidate_intents=("RESTAURANT_OPS_PLAYBOOK",))
    assert got is None


def test_capability_tables_are_the_single_source():
    """⛔ 指标/维度都必须来自既有的闭集表，不许本模块另建一份。"""
    import inspect

    import smartbi.gold.restaurant.structural_route as sr

    src = inspect.getsource(sr)
    assert "_REQUEST_TEXT_TOKENS" in src, "指标词表必须复用契约层那份"
    assert "_RESOLVER_DIMENSIONS" in src, "维度能力必须复用 service 层那份"
    assert "_catalogue_says_not_a_dish" in src, "菜品必须走目录校验，不许自己判"
