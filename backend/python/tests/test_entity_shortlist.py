"""`entity_resolution.shortlist` 的三态判据。

每条断言下面写清楚它**守的是什么行为**（⛔ 不是「守某一行代码」），
以及变异要打在哪里才让它红 —— 见 PR 里的变异对照表。

📏 用的门店/菜名是 2026-08-18 prod MOCK_REST 的**真名**（10 家门店 10 道菜），
   简称由真名机械派生。owner 定稿里那两组（山川店 / 青花椒二人套餐）逐字照抄。
"""
from __future__ import annotations

import pytest

from smartbi.canonical.entity_resolution.shortlist import (
    MAX_EMBED_CANDIDATES,
    SIM_FLOOR,
    SIM_MARGIN,
    STAGE_CONTAINMENT,
    STAGE_EMBEDDING,
    STAGE_EMBEDDING_SKIPPED,
    STAGE_EXACT,
    VERDICT_AMBIGUOUS,
    VERDICT_NONE,
    VERDICT_UNIQUE,
    clear_shortlist_cache,
    containment_shortlist,
    decide_from_scores,
    resolve_mention,
    shortlist_cache_size,
    strip_entity_suffix,
)

# prod MOCK_REST 的真实门店（2026-08-18 读出，⛔ 不是编的）
PROD_STORES = (
    "模拟·宝山大场社区店", "模拟·徐汇美罗城店", "模拟·打浦桥日月光店",
    "模拟·普陀真如社区店", "模拟·杨浦五角场店", "模拟·浦东金桥社区店",
    "模拟·长宁龙之梦店", "模拟·闵行莘庄社区店", "模拟·陆家嘴正大店",
    "模拟·静安嘉里中心店",
)


@pytest.fixture(autouse=True)
def _clean_cache():
    """跨样本读数前清缓存, 并让「清了没有」是个**可观测的数**（硬约束 3）。"""
    clear_shortlist_cache()
    assert shortlist_cache_size() == 0
    yield
    clear_shortlist_cache()


def _fake_embed(table):
    """把 {文本: 向量} 变成一个 embed_fn。表里没有的返回 None（= 服务答不上来）。"""

    async def _fn(text):
        return table.get(text)

    return _fn


# ── strip_entity_suffix ────────────────────────────────────────────────


def test_strip_entity_suffix_removes_exactly_one_suffix():
    """守: 「宝山店」去掉尾缀才与「模拟·宝山大场社区店」有子串关系。"""
    assert strip_entity_suffix("宝山店") == "宝山"
    assert strip_entity_suffix("宝山门店") == "宝山"


def test_strip_entity_suffix_keeps_a_bare_suffix_intact():
    """守: 整个词就是尾缀时**不能**削成空串 —— 空串是任何字符串的子串,
    削掉会让「门店」命中全部候选。"""
    assert strip_entity_suffix("门店") == "门店"
    assert strip_entity_suffix("店") == "店"


# ── containment（A 段） ────────────────────────────────────────────────


@pytest.mark.parametrize("mention,expect", [
    ("宝山店", "模拟·宝山大场社区店"),
    ("徐汇店", "模拟·徐汇美罗城店"),
    ("浦东店", "模拟·浦东金桥社区店"),
    ("陆家嘴店", "模拟·陆家嘴正大店"),
])
def test_containment_resolves_the_shorthands_that_sql_like_returns_zero_for(
    mention, expect,
):
    """守: 第 1 步实测 SQL LIKE **零候选**的那一类, A 段必须唯一命中。

    📏 那 20 条零候选全是「头两字/头三字 + 店」, 正是人最常用的说法。
    """
    assert containment_shortlist(mention, PROD_STORES) == (expect,)


def test_containment_normalizes_the_middle_dot():
    """守: 「模拟·」里的「·」不归一掉, 子串比较就永远差一个字符。"""
    assert containment_shortlist(
        "模拟徐汇美罗城", PROD_STORES) == ("模拟·徐汇美罗城店",)


def test_containment_returns_every_match_when_several_stores_share_the_words():
    """守: 「社区店」在 prod 上真的对上 **4** 家 —— 实测的多条命中,
    ⛔ 不是构造出来的（第 1 步探针那行读数就是 `like_n=4`）。
    它必须原样报出来, 由上层去反问。

    ⚠️ 我第一版把它写成 3, 漏了「模拟·闵行莘庄社区店」—— 而**探针日志里
       写的就是 4**。凭印象写期望值, 期望值就成了第二份会漂的真相。
    """
    hits = containment_shortlist("社区店", PROD_STORES)
    assert set(hits) == {
        "模拟·宝山大场社区店", "模拟·普陀真如社区店",
        "模拟·浦东金桥社区店", "模拟·闵行莘庄社区店",
    }


def test_containment_refuses_an_empty_needle():
    """守: 归一后为空必须返回空 —— ⛔ 不能因为「空串是任何字符串的子串」
    而命中全部候选。这条不是理论: `'' in '任何'` 为 True。"""
    assert containment_shortlist("　", PROD_STORES) == ()
    assert containment_shortlist("·", PROD_STORES) == ()


# ── decide_from_scores（B 段的纯判据） ─────────────────────────────────


def test_scores_below_the_floor_decide_nothing():
    """守: 「最像的也不够像」必须是 none（走原路）, ⛔ 不是硬挑一个。

    📏 阴性对照实测: 「量子纠缠火箭发射器」对全部门店 top1 sim=0.232。
    """
    d = decide_from_scores("量子纠缠火箭发射器", (("模拟·杨浦五角场店", 0.232),))
    assert d.verdict == VERDICT_NONE
    assert d.canonical is None


def test_a_clear_winner_is_normalized_to_the_catalogue_name():
    """守: 唯一命中要归一成**库里的全名**, ⛔ 不是老板打的字。

    📏 实测值: 「宝山店」top1=0.696 次优=0.463。
    """
    d = decide_from_scores("宝山店", (
        ("模拟·宝山大场社区店", 0.696), ("模拟·陆家嘴正大店", 0.463)))
    assert d.verdict == VERDICT_UNIQUE
    assert d.canonical == "模拟·宝山大场社区店"


def test_two_equally_close_candidates_ask(  # noqa: D103
):
    """守: top1 与次优分不开时**反问**, ⛔ 不静默挑第一个。

    ⚠️ 这条是 `SIM_MARGIN` 唯一能红的地方 —— prod 实测样本里它一次都没被
       触发过（批1 全被 A 段接住, 批2 margin 都 > 0.33）。所以这个阈值是
       **保守侧设定, 没有实测支撑**, 这条断言就是它的对照。
    """
    d = decide_from_scores("浦东店", (
        ("模拟·浦东金桥社区店", 0.682), ("模拟·杨浦五角场店", 0.642)))
    assert d.verdict == VERDICT_AMBIGUOUS
    assert d.canonical is None
    assert d.candidates == ("模拟·浦东金桥社区店", "模拟·杨浦五角场店")


def test_only_candidates_above_the_floor_are_offered():
    """守: 反问里列出来的每一个都必须自己够格 —— ⛔ 不能把一个 0.2 分的
    候选摆到老板面前让他选。一条误发的提示烧掉的是「这东西说的话能信」。"""
    d = decide_from_scores("浦东店", (
        ("模拟·浦东金桥社区店", 0.682),
        ("模拟·杨浦五角场店", 0.642),
        ("模拟·静安嘉里中心店", 0.201),
    ))
    assert d.verdict == VERDICT_AMBIGUOUS
    assert "模拟·静安嘉里中心店" not in d.candidates


def test_the_margin_boundary_is_inclusive_on_the_unique_side():
    """守: 恰好等于间距阈值算「分得开」（`>=` 写成 `>` 这条就红）。

    ⚠️ 阈值和分数都用**二进制可精确表示**的值传进去。第一版写
       `0.90 - SIM_MARGIN`, 浮点算出 0.07999999999999996 < 0.08, 断言红了
       —— 那不是边界写反, 是**我的仪器在量浮点误差**。
    """
    d = decide_from_scores("x", (("A", 0.75), ("B", 0.5)), margin=0.25)
    assert d.verdict == VERDICT_UNIQUE
    just_under = decide_from_scores("x", (("A", 0.75), ("B", 0.625)), margin=0.25)
    assert just_under.verdict == VERDICT_AMBIGUOUS


def test_a_score_exactly_at_the_floor_still_counts():
    """守: 恰好等于下界算「够像」（`>=` 写成 `>` 这条就红）。"""
    d = decide_from_scores("x", (("A", 0.5),), floor=0.5)
    assert d.verdict == VERDICT_UNIQUE
    below = decide_from_scores("x", (("A", 0.5),), floor=0.75)
    assert below.verdict == VERDICT_NONE


# ── resolve_mention（三段合起来） ──────────────────────────────────────


@pytest.mark.asyncio
async def test_exact_name_short_circuits_before_any_embedding():
    """守: 库里就叫这个名字时**一次向量都不许调** —— 那是白花的墙钟。

    ⛔ 阴性侧用「调了就炸」的 embed_fn, ⛔ 不是 `verify(never())`:
       本仓踩过 `verify(never()).f(anyString())` 在实参为 null 时不匹配,
       两条路都成立 ⇒ 断言恒真。
    """
    async def _explode(_text):
        raise AssertionError("精确命中不该走到 embedding")

    d = await resolve_mention(
        "模拟·徐汇美罗城店", PROD_STORES, embed_fn=_explode)
    assert d.verdict == VERDICT_UNIQUE
    assert d.stage == STAGE_EXACT
    assert d.canonical == "模拟·徐汇美罗城店"


@pytest.mark.asyncio
async def test_containment_short_circuits_before_any_embedding():
    """守: A 段能定的不许付 B 段的钱（实测 20/20 落在这一段）。"""
    async def _explode(_text):
        raise AssertionError("A 段已经定了, 不该走到 embedding")

    d = await resolve_mention("宝山店", PROD_STORES, embed_fn=_explode)
    assert d.stage == STAGE_CONTAINMENT
    assert d.canonical == "模拟·宝山大场社区店"


@pytest.mark.asyncio
async def test_the_owner_synonym_case_needs_embedding_and_gets_it():
    """守: owner 定稿里那条**子串从原理上做不到**的:

        '青花椒二人套餐'  vs  '鲜花椒大人二人套餐'

    A 段返回 0 条（实测），只有 B 段能。这条断言就是「为什么要向量」本身。
    📏 prod 实测 sim=0.860 / margin=0.375。
    """
    assert containment_shortlist(
        "青花椒二人套餐", ("鲜花椒大人二人套餐", "藤椒鸡")) == ()

    table = {
        "青花椒二人套餐": [1.0, 0.0, 0.0],
        "鲜花椒大人二人套餐": [0.86, 0.51, 0.0],
        "藤椒鸡": [0.2, 0.98, 0.0],
    }
    d = await resolve_mention(
        "青花椒二人套餐", ("鲜花椒大人二人套餐", "藤椒鸡"),
        embed_fn=_fake_embed(table))
    assert d.stage == STAGE_EMBEDDING
    assert d.verdict == VERDICT_UNIQUE
    assert d.canonical == "鲜花椒大人二人套餐"


@pytest.mark.asyncio
async def test_a_dead_embedding_service_decides_nothing_rather_than_guessing():
    """守: embedding 答不上来 → none（走今天的路）, ⛔ 不是 0.0 分硬挑一个。

    ▎兜底的默认值会把「我不知道」翻译成「是 0」, 而这两件事对下游完全不同。
    """
    async def _dead(_text):
        return None

    d = await resolve_mention("青花椒二人套餐", ("鲜花椒大人二人套餐",),
                              embed_fn=_dead)
    assert d.verdict == VERDICT_NONE
    assert d.canonical is None


@pytest.mark.asyncio
async def test_too_many_candidates_skips_the_vector_stage_entirely():
    """守: 候选无界时不做向量 —— 一次问答要为每个候选算一次 embedding。

    ⛔ 超限时是 none, ⛔ 不是「截断到前 N 个再算」: 截断会把
       「他要的那家排在上限之外」变成一个**看不见**的错答。
    """
    async def _explode(_text):
        raise AssertionError("超过候选上限不该做向量")

    many = tuple(f"完全不相干的店{i}" for i in range(MAX_EMBED_CANDIDATES + 1))
    d = await resolve_mention("宝山店", many, embed_fn=_explode)
    assert d.verdict == VERDICT_NONE
    assert d.stage == STAGE_EMBEDDING_SKIPPED


@pytest.mark.asyncio
async def test_containment_ambiguity_reaches_the_caller_as_candidates():
    """守: prod 上真实存在的多条命中（3 家社区店）要走到反问, ⛔ 不被吞掉。"""
    d = await resolve_mention("社区店", PROD_STORES)
    assert d.verdict == VERDICT_AMBIGUOUS
    assert len(d.candidates) == 4
    assert "模拟·闵行莘庄社区店" in d.candidates
