"""门店简称消解**接在生产入口上**没有 —— 断言跑 `parse_restaurant_query`。

🏠 「生产上谁保证它被调用」的答案: `parse_restaurant_query` 是餐饮问答唯一的
   规划入口（Web/Java 餐饮聊天只走 `semantic_first=True` 这一支），
   本文件每条断言都从它进去，⛔ 不直接调 `_resolve_store_mentions` /
   `_validated_llm_store_names`。

   ⚠️ 这一条是本仓踩出来的: 直接调被测函数的断言绕过了「谁调它」，
      变异下会全绿（形态 B / B′）。同一个错在隔壁分支犯过第二次。

📏 用的门店名是 2026-08-18 prod MOCK_REST 的**真名**（`dim_store` 全表 10 行，
   ANALYZE 后 reltuples=10）。简称由真名机械派生，⛔ 不是挑出来的。
"""
from __future__ import annotations

import json
from unittest.mock import AsyncMock, patch

import pytest

from smartbi.gold.restaurant import restaurant_intent as ri
from smartbi.gold.restaurant.restaurant_intent import (
    clear_promoted_routes_cache,
    clear_route_cache,
    clear_semantic_plan_cache,
    clear_tenant_gate_cache,
    parse_restaurant_query,
)
from smartbi.canonical.entity_resolution.shortlist import clear_shortlist_cache

FACTORY = "MOCK_REST"

PROD_STORES = (
    "模拟·宝山大场社区店", "模拟·徐汇美罗城店", "模拟·打浦桥日月光店",
    "模拟·普陀真如社区店", "模拟·杨浦五角场店", "模拟·浦东金桥社区店",
    "模拟·长宁龙之梦店", "模拟·闵行莘庄社区店", "模拟·陆家嘴正大店",
    "模拟·静安嘉里中心店",
)


@pytest.fixture(autouse=True)
def _reset_caches():
    """跨样本读数前清缓存, 并**贴出清了哪几个**（硬约束 3）。

    ⛔ 用各模块自己的 helper, ⛔ 不拼属性名 —— 拼错的名字不会报错,
       只会让清理静默失效（形态 C⁵）。
    """
    for clear in (clear_route_cache, clear_tenant_gate_cache,
                  clear_semantic_plan_cache, clear_promoted_routes_cache,
                  clear_shortlist_cache):
        clear()
    yield
    for clear in (clear_route_cache, clear_tenant_gate_cache,
                  clear_semantic_plan_cache, clear_promoted_routes_cache,
                  clear_shortlist_cache):
        clear()


# ─── Fake asyncpg pool（形状抄自 test_restaurant_intent_flywheel_reconnect） ──

class _Row(dict):
    pass


class _FakeConn:
    def __init__(self, owner):
        self.owner = owner
        self.in_transaction = False

    def transaction(self):
        conn = self

        class _Ctx:
            async def __aenter__(self):
                conn.in_transaction = True
                return None

            async def __aexit__(self, *_exc):
                conn.in_transaction = False
                return False

        return _Ctx()

    async def execute(self, sql, *args):
        if "set_config('app.factory_id'" in sql:
            assert self.in_transaction, "RLS GUC 必须是事务级"
            return "SELECT 1"
        if "restaurant_pending_clarifications" in sql:
            return "OK"
        return "OK"

    async def fetchrow(self, sql, *_args):
        if "agg_restaurant_daily_totals" in sql:
            return {"?column?": 1}
        if "restaurant_pending_clarifications" in sql:
            return None
        return None

    async def fetch(self, sql, *_args):
        if "ai_promoted_routes" in sql:
            return []
        if "FROM dim_store" in sql or "fact_pos_item" in sql:
            self.owner.store_reads += 1
            return [_Row(name=n) for n in self.owner.stores]
        return []


class _FakePool:
    def __init__(self, stores=PROD_STORES):
        self.stores = list(stores)
        self.store_reads = 0

    def acquire(self):
        conn = _FakeConn(self)

        class _Ctx:
            async def __aenter__(self):
                return conn

            async def __aexit__(self, *_exc):
                return False

        return _Ctx()


def _plan(store):
    """一份完整的单店营收计划。`store` 就是模型提名的那个名字。

    🔴 **`store` 的取值必须是真模型会产出的形状** —— 这一条是踩出来的:

       我第一版所有用例都喂**老板打的简称**(`store="宝山店"`), 单测全绿,
       而 prod 端到端 **3/3 全红**。2026-08-18 prod 打出的原始 plan 是:

           Q: 宝山店最近30天营收多少
           store = "模拟·宝山大场社区店"   ← 模型看得到门店清单, 自己归一好了

       ▎桩的自由度让我可以构造任何输入, **包括真实上游永远不会给出的输入**
       ▎(形态 B‴)。所以下面的用例按 prod 实测分成两族, 各自标明形状来源。
    """
    return {
        "intent": "RESTAURANT_OPS_SALES_SUMMARY",
        "time_range": {"kind": "relative", "unit": "day", "count": 30},
        "wants_margin": False,
        "asks_profitability": False,
        "requested_metrics": ["revenue"],
        "analysis_action": "lookup",
        "dimensions": [],
        "dish": None,
        "store": store,
        "stores": [],
        "store_scope": "single",
        "confidence": 0.95,
        "clarification_needed": False,
        "missing_fields": [],
        "clarification_question": None,
        "clarification_options": [],
    }


async def _ask(query, store_in_plan, pool=None):
    pool = pool or _FakePool()
    with patch.object(
        ri, "_t3_llm_parse", new=AsyncMock(return_value=_plan(store_in_plan))
    ):
        return await parse_restaurant_query(
            query, pool, factory_id=FACTORY, semantic_first=True,
        )


# ══════════════════════════════════════════════════════════════════════
# 1. 唯一命中 → 归一成库里的全名
# ══════════════════════════════════════════════════════════════════════


@pytest.mark.asyncio
@pytest.mark.parametrize("query,llm_store,expect", [
    # 形态 1（**prod 实测的真 LLM 行为**, 2026-08-18 三条问句 3/3 都是这个形状）:
    #   模型看得到租户门店清单, 自己就把简称改写成了全名 —— 而那个全名
    #   不是问句原文子串, 于是被 `_verbatim_entity` 反幻觉守卫丢掉。
    ("宝山店最近30天营收多少", "模拟·宝山大场社区店", "模拟·宝山大场社区店"),
    ("徐汇店最近30天营收多少", "模拟·徐汇美罗城店", "模拟·徐汇美罗城店"),
    ("浦东店最近30天营收多少", "模拟·浦东金桥社区店", "模拟·浦东金桥社区店"),
    # 形态 2: 模型**回声**老板打的简称。回放/晋升表里存着的旧计划是这个形状,
    #   所以两族都要接住。
    ("宝山店最近30天营收多少", "宝山店", "模拟·宝山大场社区店"),
    ("陆家嘴店最近30天营收多少", "陆家嘴店", "模拟·陆家嘴正大店"),
])
async def test_parse_restaurant_query_normalizes_a_store_shorthand_end_to_end(
    query, llm_store, expect,
):
    """🔴 **承重**: 老板说简称, 计划里落的必须是**库里的全名**。

    守的行为: 归一发生在真实入口上, 抬头用的是库里的名字 ——
    ⛔ 不是老板打的那几个字。

    📏 改动前实测两族都挂:
       · 形态 1 被 `_verbatim_entity` 丢掉（prod 端到端 3/3 拿到通用反问）
       · 形态 2 被 `name not in available` 丢掉（50 条简称命中 0 条 = 0.0%）
       两族的失败方式都是**静默丢弃**, 老板看不到任何痕迹。
    """
    spec = await _ask(query, llm_store)

    assert spec.store_slots == (expect,), (
        f"门店简称没有被归一成库里的全名: {spec.store_slots}")
    assert spec.store_slot == expect
    assert spec.store_scope == "single"
    assert not spec.clarification_needed, "唯一命中不该反问"
    # 阴性: 老板打的字本身**不许**留在计划里 —— 它不是库里的名字
    assert llm_store not in spec.store_slots or llm_store == expect


@pytest.mark.asyncio
async def test_a_catalogue_name_the_users_words_do_not_point_to_is_still_rejected():
    """🔴 阴性对照（承重）: 模型吐出一个**库里存在但老板没提**的门店 → 仍然丢弃。

    这条守的是「放行的判据是两个独立来源一致」, ⛔ 不是「在清单里就放行」。
    没有它, 上面那条「认下模型的改写」就等于把反幻觉守卫整个拆了 ——
    老板问「最近30天营收多少」, 模型随手挑一家, 他会拿到一家店的数
    当成全店的看。
    """
    spec = await _ask("最近30天营收多少", "模拟·徐汇美罗城店")
    assert spec.store_slots == (), (
        f"老板一个字都没提门店, 模型挑的那家却进了计划: {spec.store_slots}")


@pytest.mark.asyncio
async def test_the_llm_may_not_pick_one_when_the_users_words_are_ambiguous():
    """🔴 阴性对照（承重）: 老板说「社区店」(对上 4 家), 模型挑了其中一家
    → **不认**, 走确认式反问。

    ▎⛔ 不要替他做判断。产品给依据和判断, 决定是他的。
    """
    spec = await _ask("社区店最近30天营收多少", "模拟·宝山大场社区店")
    assert spec.store_slots == ()
    assert spec.clarification_needed
    assert "你要看哪一家" in (spec.clarification_question or "")


@pytest.mark.asyncio
async def test_a_store_name_that_is_already_exact_is_untouched():
    """阴性对照: 库里就叫这个名字时行为与改动前逐字相同。

    ⚠️ 没有这一条, 上面那条「归一成功」分不清是消解在起作用,
       还是我把什么东西一律改写了。
    """
    spec = await _ask("模拟·徐汇美罗城店最近30天营收多少", "模拟·徐汇美罗城店")
    assert spec.store_slots == ("模拟·徐汇美罗城店",)


@pytest.mark.asyncio
async def test_a_word_that_matches_nothing_still_falls_through_to_todays_path():
    """阴性对照: 对不上任何门店时**什么都不做**, 走今天的路（fail-open）。

    ⛔ 绝不静默挑一个 —— 一条误发的提示烧掉的是「这东西说的话能信」。
    """
    spec = await _ask("量子纠缠火箭发射器最近30天营收多少", "量子纠缠火箭发射器")
    assert spec.store_slots == ()
    assert spec.store_slot is None


@pytest.mark.asyncio
async def test_an_empty_store_catalogue_changes_nothing():
    """阴性对照: `dim_store` 为空的租户上, 这条路整个不生效 ——
    老板打的字**原样透传**给下游 resolver, 与改动前逐字相同。

    📏 这不是保险, 是**今天 prod 的实际覆盖面**: `dim_store` 全表 10 行,
       全属 MOCK_REST; 其余 5 个租户 0 行（ANALYZE 后 reltuples=10, RLS 无关）。
       ⇒ 那些租户走的仍然是改动前逐字相同的路。

    ⚠️ 我第一版把期望写成 `()` —— 那是**我以为的**旧行为, 不是旧行为本身。
       `_validated_llm_store_names` 的丢弃条件是 `if available and ...`,
       候选清单为空时那个 `and` 短路, 名字原样通过。
       ⇒ 写阴性对照之前先读一遍旧行为, ⛔ 不凭印象。
    """
    spec = await _ask("宝山店最近30天营收多少", "宝山店",
                      pool=_FakePool(stores=()))
    assert spec.store_slots == ("宝山店",)
    assert not spec.clarification_needed


# ══════════════════════════════════════════════════════════════════════
# 2. 多条命中 → 确认式反问（⛔ 不静默挑一个、⛔ 不静默补「全部门店」）
# ══════════════════════════════════════════════════════════════════════


@pytest.mark.asyncio
async def test_an_ambiguous_shorthand_asks_which_store_instead_of_dropping_it():
    """🔴 **承重**（缺口 #16）: 「社区店」在 prod 上真的对上 4 家 ——
    必须**列候选反问**, ⛔ 不静默挑一个、⛔ 不静默丢掉。

    ⛔ 尤其不能落到「只缺门店 → 补默认全部门店」那一格: 老板**说了**门店,
       替他换成全店会给出一份全店的数, 而他以为那是那家店的。
       ▎「没解析出 X」不等于「用户没提 X」。
    """
    spec = await _ask("社区店最近30天营收多少", "社区店")

    assert spec.clarification_needed, "多条命中必须反问"
    assert spec.clarification_question and "社区店" in spec.clarification_question
    assert "你要看哪一家" in spec.clarification_question
    # 按钮必须是**库里真实存在**的门店名
    assert len(spec.clarification_options) == 4
    for option in spec.clarification_options:
        assert option in PROD_STORES, f"反问选项不是真门店: {option}"
    # ⛔ 绝不能悄悄按全部门店算
    assert spec.store_scope != "all"
    assert not spec.store_scope_defaulted, (
        "反问这一轮根本没算, 不许打上「按全部门店算」的披露标记")


@pytest.mark.asyncio
async def test_the_ambiguity_question_never_speaks_jargon():
    """守: 发给老板的那句话里⛔ 不许出现黑话。

    一条老板读不懂的提示和一条误发的提示同样烧信任。
    """
    spec = await _ask("社区店最近30天营收多少", "社区店")
    for jargon in ("消解", "实体", "候选集", "匹配度", "resolver",
                   "embedding", "余弦", "向量", "置信度"):
        assert jargon not in (spec.clarification_question or ""), jargon


@pytest.mark.asyncio
async def test_answering_the_ambiguity_with_a_full_name_goes_through():
    """守（追问接得住）: 老板从候选里挑一家、把**全名**打回来, 下一轮认得。

    这是反问之所以有意义的那一半 —— 反问完接不住, 等于换了个说法的死胡同。
    """
    spec = await _ask("模拟·闵行莘庄社区店最近30天营收多少", "模拟·闵行莘庄社区店")
    assert not spec.clarification_needed
    assert spec.store_slots == ("模拟·闵行莘庄社区店",)


# ══════════════════════════════════════════════════════════════════════
# 3. 零 token 回放路径也要归一（⛔ 不能两条路行为不一致）
# ══════════════════════════════════════════════════════════════════════


@pytest.mark.asyncio
async def test_the_replayed_plan_normalizes_the_shorthand_too():
    """🔴 承重: 同一句话第二次命中计划缓存时, 门店**不许**丢。

    ⚠️ 少接这一条, 症状是「第一次答对、第二次悄悄变成没有门店」,
       而两次都不报错（形态 D: 漂的那一份跑起来完全正常）。
    """
    pool = _FakePool()
    query = "宝山店最近30天营收多少"
    with patch.object(
        ri, "_t3_llm_parse", new=AsyncMock(return_value=_plan("宝山店"))
    ) as planner:
        first = await parse_restaurant_query(
            query, pool, factory_id=FACTORY, semantic_first=True)
        second = await parse_restaurant_query(
            query, pool, factory_id=FACTORY, semantic_first=True)

    assert planner.call_count == 1, "第二次应该命中计划缓存(零 token)"
    assert second.source_tier == "plan_cache", (
        f"第二次没走回放, 这条测试没测到回放: source_tier={second.source_tier}")
    assert first.store_slots == ("模拟·宝山大场社区店",)
    assert second.store_slots == ("模拟·宝山大场社区店",), (
        "回放路径丢了门店 —— 同一句话两条路行为不一致")


# ══════════════════════════════════════════════════════════════════════
# 4. 接线本身（AST）: 三个 `_semantic_spec_from_t3` 出口一个都不许漏
# ══════════════════════════════════════════════════════════════════════


def test_every_semantic_spec_call_site_passes_the_resolution():
    """守: 「同一个东西只改一半调用点」是本仓反复踩的形状（硬约束 8）。

    ⛔ 用 AST 数**真正的调用节点**, ⛔ 不用文本 grep —— grep 会把 docstring
       里提到函数名的行也数上（形态 C⁸）。

    ⚠️ 这道闸挡得住「删掉某个出口的接线」, 挡不住「用 if False 绕过」。
       所以它是兜底, 不是免检 —— 行为断言在上面那几条。
    """
    import ast
    import inspect

    tree = ast.parse(inspect.getsource(ri))
    call_sites = [
        node for node in ast.walk(tree)
        if isinstance(node, ast.Call)
        and isinstance(node.func, ast.Name)
        and node.func.id == "_semantic_spec_from_t3"
    ]
    assert len(call_sites) == 3, (
        f"`_semantic_spec_from_t3` 的调用点从 3 个变成了 {len(call_sites)} 个 —— "
        "新出口也要传 store_resolution, 或在这里显式登记为「故意不传」并写理由")
    for call in call_sites:
        assert any(kw.arg == "store_resolution" for kw in call.keywords), (
            f"第 {call.lineno} 行的 `_semantic_spec_from_t3` 没传 store_resolution")


def test_the_production_entry_actually_computes_the_resolution():
    """守: 生产入口里真的**调**了 `_resolve_store_mentions`。

    上面那条只证明「参数传下去了」, 不证明那个参数不是恒 None。

    ⚠️ 量的是 `_parse_restaurant_query_impl`, ⛔ 不是 `parse_restaurant_query` ——
       后者是个薄壳, 它函数体里只有一句委派。第一版量错了对象, 断言当场红,
       而红的原因不是缺陷（形态 A: 我量的这个数不是我想知道的那个数）。
       阳性对照就在下面那行 assert: 薄壳里确实只找得到委派。
    """
    import ast
    import inspect

    shell = {
        node.func.id for node in ast.walk(
            ast.parse(inspect.getsource(ri.parse_restaurant_query)))
        if isinstance(node, ast.Call) and isinstance(node.func, ast.Name)
    }
    assert "_parse_restaurant_query_impl" in shell, (
        "薄壳的形状变了 —— 下面量的那个函数可能已经不是真入口了")

    tree = ast.parse(inspect.getsource(ri._parse_restaurant_query_impl))
    called = {
        node.func.id for node in ast.walk(tree)
        if isinstance(node, ast.Call) and isinstance(node.func, ast.Name)
    }
    assert "_resolve_store_mentions" in called, (
        "生产入口没有计算门店消解 —— store_resolution 恒为 None, "
        "整条接线是死代码")


def test_the_wiring_survives_a_json_roundtrip_of_the_plan():
    """守: 计划缓存存的是 JSON, 归一后的全名要能原样过一遍序列化。

    （非 ASCII + 中点在 JSON 里踩过坑, 这条是十秒钟的保险。）
    """
    name = "模拟·宝山大场社区店"
    assert json.loads(json.dumps({"store": name}, ensure_ascii=False))["store"] == name
