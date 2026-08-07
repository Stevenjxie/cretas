"""L1 结构解析：用**可证伪的证据**把问句解析成意图，不调 LLM。

## 它在漏斗里的位置

    缺口前置 → L0 整句精确 → **L1 结构解析** → L2 向量(不授权) → L3 LLM

L0 只认整句相等；L3 要 3.2k token / 3-10s。中间这一层是空的 —— 于是
「本月鲜行者打浦桥日月光店的营业额呢？」这类**同一形状换槽位**的问句
（飞轮候选表里一大片）每次都要过 LLM。

## ⛔ 只用可证伪的证据

| 槽位 | 来源 | 为什么可证伪 |
|---|---|---|
| 指标 | `_REQUEST_TEXT_TOKENS`（13 个，闭集） | 我们自己定义的契约，可枚举完 |
| 时间 | `_APPROVED_DIRECT_TIME_PHRASES`（6 个，闭集） | 同上 |
| 维度 | `_SEMANTIC_DIMENSIONS`（6 个，闭集） | 同上 |
| 菜品 | **租户菜单目录** | 「在 dim_product 里」能去查 |

**没有一处是「这个词看起来像 X」。** 那是猜，猜的活交给 L3。

## ⛔ 歧义即拒，不许挑一个

两条规矩，缺一条这层就不能被信任：

1. **多个意图都能服务这组槽位 → 不授权**（`_exact_shape_matches` 早就是这个语义：
   「an ambiguous registry never authorizes execution」）。
2. **一个槽位有多种解释 → 不授权**。例如「青花椒」既是菜名子串
   （招牌青花椒味）又是门店名子串（青花椒南方百联店）。

拒了的代价是「慢一点、贵一点」；挑错的代价是**答非所问且零 token 永久重放**。

## ⛔ 不做的事

- 不做默认值。缺时间/缺门店由既有的优先级链处理，这里只负责**认**。
- 不碰 `dish_slot` 之外的实体目录（门店/食材目录的接入是后续独立的事）。
- 不产出计划。返回的是「能不能确定地认出这个意图」，编译仍走既有那条路。
"""
from __future__ import annotations

import logging
from dataclasses import dataclass
from typing import Optional, Sequence, Tuple

logger = logging.getLogger(__name__)


@dataclass(frozen=True)
class StructuralMatch:
    """L1 的产出：一个可授权执行的意图 + 它凭什么。

    `evidence` 是给日志和排查用的 —— 出了问题要能立刻回答「它凭什么这么判」。
    """

    intent: str
    metrics: Tuple[str, ...]
    dimensions: Tuple[str, ...]
    evidence: str


def _metrics_in(text: str) -> Tuple[str, ...]:
    """闭集指标：问句里出现了哪些指标词。复用契约层那张表，不另建。"""
    from smartbi.gold.restaurant.answer_contract import _REQUEST_TEXT_TOKENS

    hit = [
        metric
        for metric, tokens in _REQUEST_TEXT_TOKENS.items()
        if any(tok in text for tok in tokens)
    ]
    return tuple(sorted(hit))


def _dimension_of_named_dish(text: str) -> Optional[str]:
    """点名了目录里的真实菜 → dish 粒度；否则 None。

    ⛔ 走 `extract_dish_candidate`，它最后一道守卫是「菜单说了算」——
       目录不可用时它 fail-open，那时**本函数也该返回 None**（认不出来），
       而不是拿一个没校验过的候选去授权执行。
    """
    from smartbi.gold.restaurant.restaurant_ops_router import (
        _DISH_CATALOGUE,
        _catalogue_says_not_a_dish,
        extract_dish_candidate,
    )

    if not _DISH_CATALOGUE.get():
        return None  # 目录不可用 = 认不出来 = 交给 L3，不猜
    candidate = extract_dish_candidate(text)
    if not candidate or _catalogue_says_not_a_dish(candidate):
        return None
    return "dish"


def resolve_structurally(
    text: str,
    *,
    candidate_intents: Sequence[str],
) -> Optional[StructuralMatch]:
    """能不能只凭结构证据确定一个意图。不能就返回 None（交给 L3）。

    :param candidate_intents: 允许授权的意图集合（调用方给，通常是全部餐饮意图）。

    返回 None 的三种情况都必须让调用方照旧往下走：
      · 一个闭集指标都没认出来 —— 没有证据
      · 没有意图能服务这组维度 —— 认不出终点
      · **多个意图都能服务** —— 歧义，不许挑一个
    """
    if not text:
        return None

    from smartbi.gold.restaurant.restaurant_intent_service import _RESOLVER_DIMENSIONS

    metrics = _metrics_in(text)
    if not metrics:
        return None  # 连问什么指标都认不出来 -> 没有可证伪的证据

    dims: Tuple[str, ...] = ()
    dish_dim = _dimension_of_named_dish(text)
    if dish_dim:
        dims = (dish_dim,)

    # ⛔ 候选来自**按指标声明的完整服务者列表**, 不是「谁没被登记谁就出局」。
    #    维度在这里只能**收窄**候选, 永远不能凭空制造唯一 —— 见 `_candidate_servers`。
    servers = _candidate_servers(metrics, candidate_intents)
    servers = [c for c in servers if set(dims) <= _RESOLVER_DIMENSIONS.get(c, frozenset())]

    if len(servers) != 1:
        if servers:
            logger.info(
                "[structural] 歧义不授权: text=%r metrics=%s dims=%s 候选=%s",
                text[:40], metrics, dims, servers,
            )
        return None

    intent = servers[0]
    return StructuralMatch(
        intent=intent,
        metrics=metrics,
        dimensions=dims,
        evidence=f"metrics={metrics} dims={dims} 唯一可服务者={intent}",
    )


#: 指标 → **能服务它的全部 resolver**（不是「主要那个」）。
#:
#: 🔴 方向是刻意反过来的。第一版按「意图 → 它的主指标」写，`revenue` 只登记给了
#:    SALES_SUMMARY，于是「营收趋势怎么样」在 L1 眼里成了**唯一命中** —— 授权到
#:    总览而不是趋势，与人审晋升结论相反。**漏登记不是保守，漏登记会把歧义伪装成
#:    确定**。改成按指标登记后，同一句话有 SALES_SUMMARY / TREND_ANALYSIS 两个
#:    候选 → 歧义 → 拒 → 交给 L3。
#:
#: ⛔ 这张表的正确性**没有办法从代码里证明**（仓里没有意图↔指标的既有能力表，
#:    resolver 实际产出什么只有读实现才知道）。它的可证伪性来自
#:    `test_structural_route.py::test_agrees_with_every_audited_promotion`：
#:    对人审通过的每一条晋升，L1 可以沉默，但**一条分歧都不许有**。
#:    新增一个指标或 resolver 时，先补这张表再跑那道闸。
#:
#: ⛔ 不登记的指标一律不授权。这是安全的一侧：漏了只会变慢，不会答错。
_METRIC_SERVERS: dict = {
    # ⚠️ GROSS_MARGIN 必须在列: 它产出菜品级营收。漏了它,「罗氏虾的营业额」会被
    #    dish 维度唯一收窄到 STORE_MARGIN(门店毛利) —— 又一次「漏登记造出假唯一」。
    "revenue": frozenset({
        "RESTAURANT_OPS_SALES_SUMMARY",
        "RESTAURANT_OPS_TREND_ANALYSIS",
        "RESTAURANT_OPS_STORE_MARGIN",
        "RESTAURANT_OPS_GROSS_MARGIN",
    }),
    "orders": frozenset({
        "RESTAURANT_OPS_SALES_SUMMARY",
        "RESTAURANT_OPS_TREND_ANALYSIS",
    }),
    "gross_margin": frozenset({
        "RESTAURANT_OPS_GROSS_MARGIN",
        "RESTAURANT_OPS_STORE_MARGIN",
    }),
    "sales_volume": frozenset({
        "RESTAURANT_OPS_GROSS_MARGIN",
        "RESTAURANT_OPS_SALES_SUMMARY",
    }),
    "recipe_cost": frozenset({
        "RESTAURANT_OPS_RECIPE_COST",
        "RESTAURANT_OPS_GROSS_MARGIN",
    }),
    "wastage": frozenset({"RESTAURANT_OPS_WASTAGE_TOP"}),
    "staffing": frozenset({"RESTAURANT_OPS_STAFFING_ADVICE"}),
}


def _candidate_servers(
    metrics: Sequence[str], candidate_intents: Sequence[str]
) -> list:
    """能同时服务**全部**认出来的指标的 resolver。

    🔴 **认出 2 个及以上指标 → 一律拒**，不做交集。

    「食材成本占营收」认出 recipe_cost 与 revenue，而 GROSS_MARGIN 两个都服务 ——
    交集非空，于是第一版把这句话授权成了「菜品毛利」。可它问的是**比率**，
    不是任一单指标。L1 的产出只有一个 intent，**没有表达「A 相对 B」的位置**，
    所以多指标就是超出这层能力，交给 L3 才对。

    任何一个指标没登记 → 空集，绝不用「剩下那些」凑一个唯一。
    """
    if len(metrics) != 1:
        return []
    known = _METRIC_SERVERS.get(metrics[0])
    if not known:
        return []  # 没登记 = 认不出终点 = 交给 L3
    return sorted(set(known) & set(candidate_intents))
