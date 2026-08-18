"""飞轮 B（拒答队列）的闸。

设计卡: docs/decisions/2026-08-18-飞轮B拒答队列-设计卡.md

三件事分开守，⛔ 不要合并 —— 它们各自会以完全不同的方式坏掉：

1. **接上没有**（形态 B，本仓头号缺陷形态）
   `test_java_delegate_entry_records_refusal` 跑在**产品真实入口**
   （`gold_reads.post_restaurant_tiered_answer`，Java 委派端点）上，
   用真的 `RestaurantQuerySpec`、真的返回字典形状，只桩掉外部 IO
   （连接池 / 规划器 / 那一次 INSERT）。
   ⇒ 变异「把 `record_refusal(...)` 那一行从 `gold_reads.py` 删掉」必须让它红。

2. **分类是结构性的**（任务卡第 3 条）
   `test_classification_never_reads_answer_text` 走 **AST**，⛔ 不是字符串计数
   —— 本模块的文档里就写着 `answer_text` 这几个字，用 grep 会把说明当成违例
   （形态 C⁸ 已经因此栽过三次）。它自带阳性对照。

3. **判据不是恒真式**
   维度那条特意配了 `test_dimension_gap_reads_the_single_definition`：
   `supported` 恒为空集时 `extra` 恒等于 `asked`，那时「差非空 ⇒ 没有维度」
   不可能红。所以要有一条断言证明 `supported` 真的非空、真的在参与判定。
"""
from __future__ import annotations

import ast
import asyncio
import datetime as _dt
import inspect
import json
from types import SimpleNamespace
from unittest.mock import AsyncMock

import pytest

from smartbi.gold.restaurant import refusal_queue as rq
from smartbi.gold.restaurant.restaurant_intent import RestaurantQuerySpec


# ─── 假 asyncpg 池（形状抄 tests/test_restaurant_intent_promotion.py） ──────

class _AcquireCtx:
    def __init__(self, conn):
        self._conn = conn

    async def __aenter__(self):
        return self._conn

    async def __aexit__(self, exc_type, exc, tb):
        return False


class _FakeConn:
    def __init__(self, rows=None, exc=None):
        self.rows = rows or []
        self.exc = exc
        self.guc_calls = []
        self.queries = []

    async def execute(self, sql, *args):
        self.guc_calls.append((sql, args))

    async def fetch(self, sql, *args):
        self.queries.append((sql, args))
        if self.exc:
            raise self.exc
        return self.rows


class _FakePool:
    def __init__(self, conn):
        self._conn = conn

    def acquire(self):
        return _AcquireCtx(self._conn)


def _spec(**overrides) -> RestaurantQuerySpec:
    """真的 spec 实例 —— ⛔ 不用 SimpleNamespace 冒充。

    ⚠️ 冒充会让「字段改名了」这一类漂移完全看不见：`getattr` 在假对象上永远拿得到
       我写进去的那个名字，而生产上那个名字可能已经不存在了。
    """
    defaults = dict(
        intent="RESTAURANT_OPS_RECIPE_COST",
        domain="restaurant",
        date_range=(None, None),
        window_label="本月",
        relative_window=True,
        metrics=(),
        wants_margin=False,
        asks_profitability=False,
        dimensions=(),
        comparison=None,
        confidence=0.9,
        source_tier="llm",
    )
    defaults.update(overrides)
    return RestaurantQuerySpec(**defaults)


#: 维度对不上那一支的**真实**组合。
#:
#: 🔑 这两个 resolver 合起来只声明 `{dish}`（`RECIPE_COST` 是 `{dish}`，
#:    `DISCOUNT_SUMMARY` 是空集 —— 它自己的注释写着「两个来源都不带门店/菜品
#:    粒度」）。问句要求按 `store` 拆 ⇒ `extra = {store}`。
#: ⛔ 不写死 supported —— 它由 `_supported_dimensions` 现算，能力表一改这里就跟着变。
_GAP_PLAN = ("RESTAURANT_OPS_RECIPE_COST", "RESTAURANT_OPS_DISCOUNT_SUMMARY")


def _dimension_gap_spec() -> RestaurantQuerySpec:
    return _spec(dimensions=("store",), planned_intents=_GAP_PLAN)


def _mismatch_result(spec) -> dict:
    """维度闸那一支**逐字**的返回形状（restaurant_intent_service.py:2087）。

    ⚠️ 形状必须跟着生产走。桩里多一个键少一个键都会让这条断言变成在测我自己
       写的字典 —— 写桩之前问一句「真实上游真的会给出这个形状吗」。
    """
    return {
        "kind": "clarification",
        "answer_text": "我不确定你要看的是哪一层的数，所以这次我没敢算。……",
        "contract_pass": False,
        "structured_context": {},
        "spec": spec,
    }


# ═══ 1. 分类：三态在结构上分得开 ═══════════════════════════════════════════


def test_no_data_from_data_gap_marker():
    """「没有数据」信号 ①：`meta.data_gap` —— `data_gaps` **真查了表**且 0 行。

    ⛔ 这个标记不是「我猜没有」：`honest_gap_answer` 在查不动或表里有行时
       都返回 None（那个模块的 docstring 把三种 None 逐条列了出来）。
    """
    got = rq.classify_refusal({
        "kind": "answer",
        "code": "RESTAURANT_OPS_DATA_GAP",
        "meta": {"data_gap": True, "missing_table": "agg_supplier_price"},
    })
    assert got["reason"] == rq.REASON_NO_DATA
    assert got["missing"] == ("agg_supplier_price",), "缺的是什么必须点名那张表"


def test_no_data_from_unsupported_requirements():
    """「没有数据」信号 ②：`spec.unsupported_requirements`。

    `_UNSUPPORTED_REQUIREMENT_LABELS['table_turnover']` 是「翻台率（缺少桌台、
    开台/结账时间、就餐轮次和可用桌数）」—— 缺的是**采集**，所以归「没有数据」，
    ⛔ 不是「没有维度」。
    """
    spec = _spec(clarification_needed=True,
                 unsupported_requirements=("table_turnover",))
    got = rq.classify_refusal({"kind": "clarification", "spec": spec})
    assert got["reason"] == rq.REASON_NO_DATA
    assert got["missing"] == ("table_turnover",)


def test_dimension_gap_reads_the_single_definition():
    """「没有维度」信号：`asked − supported` 非空。

    ⚠️ **阳性对照在第一条断言里**：先证明 `supported` 真的**非空**。
       它若恒为空集，`extra` 恒等于 `asked`，这条判据就是恒真式 ——
       那时下面那条断言绿了也什么都没证明（形态 B⁴：闸没坏，是输入让它不可能红）。
    """
    from smartbi.gold.restaurant.restaurant_intent_service import (
        _supported_dimensions,
    )

    supported = _supported_dimensions(_GAP_PLAN)
    assert supported, "supported 恒为空集的话, 下面那条断言是恒真式"
    assert "store" not in supported, "这个计划要真的拆不出门店, 否则构造无效"

    got = rq.classify_refusal(_mismatch_result(_dimension_gap_spec()))
    assert got["reason"] == rq.REASON_NO_DIMENSION
    assert got["missing"] == ("store",), "缺的是什么 = 那几个拆不出来的维度键"


def test_dimension_gap_loses_to_capability_gap():
    """两个信号同时在时，**能力缺口赢** —— 因为它那道闸在执行之前就开火了。

    ⛔ 顺序不是随意的：能力拒答（`should_use_capability_refusal`）发生在
       `tiered_answer` 的澄清分支，那时维度闸根本没跑到。
    """
    spec = _spec(
        dimensions=("store",), planned_intents=_GAP_PLAN,
        clarification_needed=True,
        unsupported_requirements=("net_profit",),
    )
    assert rq.classify_refusal({"kind": "clarification", "spec": spec})["reason"] == (
        rq.REASON_NO_DATA
    )


def test_need_more_info_only_when_no_capability_gap():
    """「信息不足(已反问)」= 反问了，但既不缺数据也不缺维度。

    ⛔ 缺时间/缺门店**不是**能力缺口 —— 把它们记成能力缺口，飞轮 B 那张待办表
       会被「说清楚点」这类条目淹掉，而它一条能力都不缺。
    """
    spec = _spec(clarification_needed=True, missing_slot="time")
    got = rq.classify_refusal({"kind": "clarification", "spec": spec})
    assert got["reason"] == rq.REASON_NEED_MORE_INFO
    assert got["missing"] == ("time",)


def test_out_of_domain_is_classified_but_is_not_a_capability_gap():
    """域外**分一类**（缺口清单第 12 项），但它不是能力缺口。

    ⚠️ 它的 `kind` 是 `"answer"` —— 靠 kind 判不出来，必须先看 code。
       这条断言同时钉住「判定顺序里域外排第一」。
    """
    got = rq.classify_refusal({
        "kind": "answer",
        "code": "RESTAURANT_OPS_OUT_OF_DOMAIN",
        "answer_text": "天气、新闻这类外部信息不在我的数据范围内",
    })
    assert got["reason"] == rq.REASON_OUT_OF_DOMAIN
    assert rq.REASON_OUT_OF_DOMAIN in rq._NOT_A_CAPABILITY_GAP


def test_other_when_nothing_structural_says_why():
    """答案契约未过那一支：拒答形状成立，但没有任何结构性信号说得出为什么。

    ⛔ 这时**归「其它」**，不硬凑一个分类 —— 猜错的分类比不分类更糟，
       它会把人工排优先级的那张表指向错的方向。
    """
    spec = _spec(clarification_needed=False)
    got = rq.classify_refusal({"kind": "clarification", "spec": spec})
    assert got["reason"] == rq.REASON_OTHER


def test_system_outage_is_not_a_refusal():
    """`kind == "unavailable"` 是**系统故障**，⛔ 不进飞轮 B。

    把它记成能力缺口会让「故障在指标上看起来像产品行为」——
    `restaurant_intent_service` 那一处的注释自己就是这么写的。
    """
    assert rq.is_refusal({"kind": "unavailable", "answer_text": "餐饮执行链暂时不可用"}) is False
    assert rq.classify_refusal({"kind": "unavailable", "spec": _spec()}) is None


def test_successful_answer_is_not_a_refusal():
    """阴性对照：正常答案一条都不许进队列。

    ⚠️ 少了它，「拒答被记下来了」可能只是因为**每一次**都被记下来了。
    """
    assert rq.is_refusal({
        "kind": "answer", "code": "RESTAURANT_OPS_SALES_SUMMARY",
        "answer_text": "本月全部门店营收合计 ¥6,490,180.61。",
    }) is False
    assert rq.classify_refusal({
        "kind": "answer", "code": "RESTAURANT_OPS_SALES_SUMMARY",
        "answer_text": "本月全部门店营收合计 ¥6,490,180.61。", "spec": _spec(),
    }) is None


def test_no_standard_has_no_producer_yet():
    """🔴 登记：「没有判定标准」今天**没有任何产出点**。

    读路径上不存在「标准没配所以拒答」这个结构性信号
    （`plan_alert.threshold_value` 是预警规则、`generic_executor._threshold`
    是算出来的均值，两个都不是）。按任务卡第 3 条登记为「判不了」，
    ⛔ 不拿文本匹配硬凑。

    ⚠️ 这条闸是**提醒**不是禁令：等 owner 缺口清单第 5 项（能力表登记
       「为什么不能」三态）落地，产出点会出现，那时这条会红 —— 红了就该把
       这条断言换成「产出点在哪里」的正向断言，⛔ 不是删掉它。
    """
    assert rq.REASON_NO_STANDARD in rq.REFUSAL_REASONS, "分类必须在册（owner 定的五类）"
    src = inspect.getsource(rq.classify_refusal)
    assert "REASON_NO_STANDARD" not in src, (
        "classify_refusal 开始产出 no_standard 了 —— 请把这条断言换成"
        "「它的结构性信号是什么」的正向断言, 并更新设计卡第 3 节的登记"
    )


# ═══ 2. 分类只用结构性信号 —— AST 闸（⛔ 不是字符串计数） ═══════════════════


def _answer_text_literals(source: str):
    """源码里**代码位置**上出现的 `answer_text` 字面量（⛔ 排除 docstring）。

    🔴 走 AST 而不是 grep：本模块的文档里就写着「⛔ 不读 answer_text」，
       字符串计数会把这句**说明**数成违例。字符串计数量的是文本，
       AST 量的是结构，闸要守的从来是结构（形态 C⁸，同形已栽三次）。
    """
    tree = ast.parse(source)
    docstring_nodes = set()
    for node in ast.walk(tree):
        body = getattr(node, "body", None)
        if not isinstance(body, list) or not body:
            continue
        if not isinstance(node, (ast.Module, ast.FunctionDef,
                                 ast.AsyncFunctionDef, ast.ClassDef)):
            continue
        first = body[0]
        if isinstance(first, ast.Expr) and isinstance(first.value, ast.Constant) \
                and isinstance(first.value.value, str):
            docstring_nodes.add(id(first.value))
    return [
        node for node in ast.walk(tree)
        if isinstance(node, ast.Constant)
        and isinstance(node.value, str)
        and "answer_text" in node.value
        and id(node) not in docstring_nodes
    ]


def test_classification_never_reads_answer_text():
    """任务卡第 3 条：三态必须是结构性信号，⛔ 不是关键词匹配答案文本。

    按文案分类的代价：文案改一个字，整张待办表的口径跟着变，而那种漂移不报错。
    """
    # ── 阳性对照：这把尺子真的能读出违例吗 ──────────────────────────────
    #
    # ⚠️ 会读出「全无」的仪器必须带一条已知应为「有」的探针 —— 否则
    #    「0 条违例」分不清是「真的没有」还是「我的 AST 遍历写错了」。
    poisoned = (
        '"""这个 docstring 里的 answer_text 不该被数。"""\n'
        'def f(result):\n'
        '    return "维度" in result["answer_text"]\n'
    )
    control = _answer_text_literals(poisoned)
    assert len(control) == 1, f"阳性对照没通过, 这把尺子读不出违例: {control}"

    offenders = _answer_text_literals(inspect.getsource(rq))
    assert offenders == [], (
        "refusal_queue 在代码里读 answer_text 了 —— 分类必须走结构性信号。"
        f"行号: {[n.lineno for n in offenders]}"
    )


# ═══ 3. 写入 ═══════════════════════════════════════════════════════════════


@pytest.mark.asyncio
async def test_record_refusal_writes_structured_reason(monkeypatch):
    written = {}

    async def _fake_log(pool, query, factory_id, upload_id, template_code,
                        answer, wall_ms, agg_meta=None):
        written.update({
            "query": query, "factory_id": factory_id,
            "template_code": template_code, "agg_meta": agg_meta,
        })
        return 7

    monkeypatch.setattr(
        "smartbi.services.llm_fallback_logger.log_template_hit", _fake_log)

    spec = _dimension_gap_spec()
    row_id = await rq.record_refusal(
        object(), factory_id="MOCK_REST", query="哪家店折扣最多",
        result=_mismatch_result(spec), spec=spec,
    )

    assert row_id == 7
    assert written["template_code"] == rq.REFUSAL_SENTINEL
    assert written["agg_meta"]["refusal_reason"] == rq.REASON_NO_DIMENSION
    assert written["agg_meta"]["refusal_missing"] == ["store"]
    assert written["agg_meta"]["served"] is False


@pytest.mark.asyncio
async def test_record_refusal_writes_nothing_for_a_real_answer(monkeypatch):
    """阴性对照：成功的回答**一行都不写**。

    ⛔ 少了这条，上面那条断言可能只是「任何东西进来都写一行」。
    """
    fake_log = AsyncMock(return_value=7)
    monkeypatch.setattr(
        "smartbi.services.llm_fallback_logger.log_template_hit", fake_log)

    got = await rq.record_refusal(
        object(), factory_id="MOCK_REST", query="本月营收多少",
        result={"kind": "answer", "code": "RESTAURANT_OPS_SALES_SUMMARY",
                "answer_text": "本月营收 ¥1,234.00", "spec": _spec()},
    )
    assert got is None
    assert fake_log.await_count == 0


@pytest.mark.asyncio
async def test_record_refusal_is_fail_open(monkeypatch):
    """记账挂了绝不连累那次回答 —— 与 `log_intent_miss` 同一条纪律。"""
    async def _boom(*a, **kw):
        raise RuntimeError("库挂了")

    monkeypatch.setattr(
        "smartbi.services.llm_fallback_logger.log_template_hit", _boom)
    assert await rq.record_refusal(
        object(), factory_id="MOCK_REST", query="哪家店折扣最多",
        result=_mismatch_result(_dimension_gap_spec()),
    ) is None


# ═══ 4. 接上没有 —— 跑在产品真实入口上 ═════════════════════════════════════


@pytest.mark.asyncio
async def test_java_delegate_entry_records_refusal(monkeypatch):
    """🔴 「生产上谁保证 `record_refusal` 被调用？」的答案，就是这条断言。

    跑的是**真实入口** `gold_reads.post_restaurant_tiered_answer`
    （Java `GoldBackedRestaurantTool` 委派端点，RN App 的餐饮问答走它）：
    真的 `RestaurantQuerySpec`、真的返回字典形状、真的 `should_delegate`、
    真的 `classify_refusal`。只桩掉外部 IO：连接池、规划器、执行、那一次 INSERT。

    ⇒ 变异「把 `record_refusal(...)` 那一行删掉」必须让它红。
    ⛔ 只测 `record_refusal` 本身是不够的 —— 那是测 helper，测不出「有没有人调它」。
    """
    import smartbi.api.gold_reads as gold_reads_mod
    from smartbi.api.gold_reads import (
        TieredIntentAnswerRequest,
        post_restaurant_tiered_answer,
    )

    spec = _dimension_gap_spec()
    monkeypatch.setattr(gold_reads_mod, "get_factory_id", lambda: "MOCK_REST")
    monkeypatch.setattr(
        gold_reads_mod, "get_pg_pool", AsyncMock(return_value=object()))
    monkeypatch.setattr(
        "smartbi.gold.restaurant.restaurant_intent.parse_restaurant_query",
        AsyncMock(return_value=spec),
    )
    monkeypatch.setattr(
        "smartbi.gold.restaurant.restaurant_intent_service.tiered_answer",
        AsyncMock(return_value=_mismatch_result(spec)),
    )
    writes = []

    async def _fake_log(pool, query, factory_id, upload_id, template_code,
                        answer, wall_ms, agg_meta=None):
        writes.append({"template_code": template_code, "agg_meta": agg_meta,
                       "query": query, "factory_id": factory_id})
        return 1

    monkeypatch.setattr(
        "smartbi.services.llm_fallback_logger.log_template_hit", _fake_log)

    body = TieredIntentAnswerRequest(
        factory_id="MOCK_REST", query="哪家店折扣最多",
        java_tool_name="restaurant_discount_summary_gold",
    )
    response = await post_restaurant_tiered_answer(
        SimpleNamespace(state=SimpleNamespace(role="restaurant_manager",
                                              user_id=None)),
        body,
    )
    # 记账走 asyncio.create_task（对用户零延迟）—— 让 loop 转一圈。
    await asyncio.sleep(0)
    await asyncio.sleep(0)

    assert response["kind"] == "clarification", "构造无效: 这次不是拒答"
    assert writes, (
        "拒答没有进队列 —— 生产入口没有人调 record_refusal（形态 B: 机制在、没接上）")
    assert writes[0]["template_code"] == rq.REFUSAL_SENTINEL
    assert writes[0]["agg_meta"]["refusal_reason"] == rq.REASON_NO_DIMENSION
    assert writes[0]["query"] == "哪家店折扣最多", "原句必须原样落下来"
    assert writes[0]["factory_id"] == "MOCK_REST"


@pytest.mark.asyncio
async def test_java_delegate_entry_writes_nothing_for_a_real_answer(monkeypatch):
    """同一个真实入口上的阴性对照：正常答案不进队列。

    ⚠️ 少了它，上面那条只证明了「这个入口会写一行」，证明不了「它写的是拒答」。
    """
    import smartbi.api.gold_reads as gold_reads_mod
    from smartbi.api.gold_reads import (
        TieredIntentAnswerRequest,
        post_restaurant_tiered_answer,
    )

    spec = _spec(intent="RESTAURANT_OPS_SALES_SUMMARY",
                 planned_intents=("RESTAURANT_OPS_SALES_SUMMARY",
                                  "RESTAURANT_OPS_GROSS_MARGIN"))
    monkeypatch.setattr(gold_reads_mod, "get_factory_id", lambda: "MOCK_REST")
    monkeypatch.setattr(
        gold_reads_mod, "get_pg_pool", AsyncMock(return_value=object()))
    monkeypatch.setattr(
        "smartbi.gold.restaurant.restaurant_intent.parse_restaurant_query",
        AsyncMock(return_value=spec),
    )
    monkeypatch.setattr(
        "smartbi.gold.restaurant.restaurant_intent_service.tiered_answer",
        AsyncMock(return_value={
            "kind": "answer",
            "answer_text": "本月全部门店营收合计 ¥6,490,180.61。",
            "charts": [], "kpis": [], "title": "经营分析",
            "code": "RESTAURANT_OPS_SALES_SUMMARY",
            "contract_pass": True, "spec": spec,
        }),
    )
    fake_log = AsyncMock(return_value=1)
    monkeypatch.setattr(
        "smartbi.services.llm_fallback_logger.log_template_hit", fake_log)

    body = TieredIntentAnswerRequest(
        factory_id="MOCK_REST", query="本月营收多少",
        java_tool_name="restaurant_sales_summary_gold",
    )
    response = await post_restaurant_tiered_answer(
        SimpleNamespace(state=SimpleNamespace(role="restaurant_manager",
                                              user_id=None)),
        body,
    )
    await asyncio.sleep(0)
    await asyncio.sleep(0)

    assert response["kind"] == "answer", "构造无效: 这次不是成功回答"
    assert fake_log.await_count == 0, "成功的回答被记进了拒答队列"


# ═══ 5. 聚合：人工看的那张表 ═══════════════════════════════════════════════


def _agg_row(reason, query, count, *, first="2026-08-01", last="2026-08-18",
             missing=("store",), tenants=1):
    """假的聚合行。形状必须跟着 SQL 的 SELECT 列表走。"""
    return {
        "reason": reason,
        "norm_query": query,
        "occurrence_count": count,
        "first_seen": _dt.datetime.fromisoformat(first),
        "last_seen": _dt.datetime.fromisoformat(last),
        "tenant_count": tenants,
        "missing_raw": [json.dumps(list(missing))],
    }


@pytest.mark.asyncio
async def test_aggregate_refusals_reports_owner_required_columns():
    """owner 定稿要求每条落: 原句 / 原因分类 / 缺的是什么 / 频次 / 首次与最近。"""
    conn = _FakeConn(rows=[
        _agg_row(rq.REASON_NO_DIMENSION, "哪家店折扣最多", 12),
    ])
    got = await rq.aggregate_refusals(_FakePool(conn), factory_id="MOCK_REST")

    assert len(got) == 1
    row = got[0]
    assert row["query"] == "哪家店折扣最多"
    assert row["reason"] == rq.REASON_NO_DIMENSION
    assert row["reason_label"] == "没有维度"
    assert row["missing"] == ["store"]
    assert row["occurrence_count"] == 12
    assert row["first_seen"] < row["last_seen"]
    assert row["is_capability_gap"] is True


@pytest.mark.asyncio
async def test_aggregate_refusals_sets_the_rls_guc():
    """⛔ 不设 `app.factory_id` 会**静默返回零行**（假 0），不是报错。

    连接池的 setup 回调把它设成 `'__internal__'`，那个值一条 RLS 分支都匹配
    不上 —— 飞轮 A 的 `aggregate_candidates` 为这件事红过一次，这里复用它的
    `_set_rls_guc`，这条断言钉住「确实调了」。
    """
    conn = _FakeConn(rows=[])
    await rq.aggregate_refusals(_FakePool(conn), factory_id=None)
    assert conn.guc_calls, "查询前没有设 RLS GUC —— 跨租户读会假性 0 行"
    assert "set_config" in conn.guc_calls[0][0]
    assert conn.guc_calls[0][1] == ("",), "管理员通道必须把 GUC 重置成空串"


@pytest.mark.asyncio
async def test_aggregate_refusals_excludes_out_of_domain_by_default():
    """缺口清单第 12 项：⛔ 域外不混进飞轮 B。

    ⚠️ 排除发生在 **SQL 里**（一处），⛔ 不是又在 Python 侧过滤一遍 ——
       两处各判一次就会漂。这条断言读的是发出去的那条 SQL。
    """
    conn = _FakeConn(rows=[])
    await rq.aggregate_refusals(_FakePool(conn), factory_id="MOCK_REST")
    sql_default = conn.queries[0][0]
    assert f"<> '{rq.REASON_OUT_OF_DOMAIN}'" in sql_default

    conn2 = _FakeConn(rows=[])
    await rq.aggregate_refusals(
        _FakePool(conn2), factory_id="MOCK_REST", include_out_of_domain=True)
    assert f"<> '{rq.REASON_OUT_OF_DOMAIN}'" not in conn2.queries[0][0], (
        "阳性对照: 参数打开时那条谓词必须消失, 否则上面那条断言是恒真式")


@pytest.mark.asyncio
async def test_aggregate_refusals_orders_by_frequency():
    """「按频次排」——「频次涨上去就是下一个该开发的」（定稿例 17）。

    ⚠️ 排序发生在 SQL 里, 所以这条断言读 SQL；⛔ 不在 Python 侧再排一次。
    """
    conn = _FakeConn(rows=[])
    await rq.aggregate_refusals(_FakePool(conn), factory_id="MOCK_REST")
    assert "ORDER BY COUNT(*) DESC" in conn.queries[0][0]


@pytest.mark.asyncio
async def test_aggregate_refusals_drops_queries_that_ever_succeeded():
    """飞轮 A 花一轮学到的那条：**从来没成功过**才算能力缺口。

    不加它时 `aggregate_misses` 的清单是 66 组，其中 46 组曾经成功过 ——
    那些失败是瞬时抖动/供应商池干了，不是能力缺口。
    """
    conn = _FakeConn(rows=[])
    await rq.aggregate_refusals(_FakePool(conn), factory_id="MOCK_REST")
    assert "NOT EXISTS" in conn.queries[0][0]

    conn2 = _FakeConn(rows=[])
    await rq.aggregate_refusals(
        _FakePool(conn2), factory_id="MOCK_REST", only_never_answered=False)
    assert "NOT EXISTS" not in conn2.queries[0][0], "阳性对照: 关掉时它必须消失"


@pytest.mark.asyncio
async def test_aggregate_refusals_is_fail_open():
    conn = _FakeConn(exc=RuntimeError("库挂了"))
    assert await rq.aggregate_refusals(_FakePool(conn), factory_id="X") == []


def test_merge_missing_drops_unparseable_instead_of_passing_it_through():
    """⚠️ 解析不出来的**丢掉**，⛔ 不塞原文。

    队列里混进一条 `'[not json'`，「缺的是什么」这一栏就从结构化数据退化成
    自由文本，而按它归类正是这张表的全部用处。
    """
    assert rq._merge_missing([json.dumps(["a", "b"]), "[not json", None,
                              json.dumps(["b"])]) == ["a", "b"]


def test_reason_breakdown_counts_occurrences_not_distinct_queries():
    """⚠️ 数的是**被问过多少次**，⛔ 不是「有多少条不同问法」。

    后者会把一条被问了 40 次的缺口和一条只问过一次的排成同一权重。
    """
    rows = [
        {"reason": rq.REASON_NO_DIMENSION, "occurrence_count": 40},
        {"reason": rq.REASON_NO_DIMENSION, "occurrence_count": 1},
        {"reason": rq.REASON_NO_DATA, "occurrence_count": 3},
    ]
    got = rq.reason_breakdown(rows)
    assert got[rq.REASON_NO_DIMENSION] == 41
    assert got[rq.REASON_NO_DATA] == 3
    assert got[rq.REASON_NO_STANDARD] == 0


# ═══ 6. 闭环：补完能力后批量晋升进飞轮 A ═══════════════════════════════════


def test_promotion_entries_are_accepted_by_flywheel_a(tmp_path, monkeypatch):
    """闭环的判据：产出的形状**飞轮 A 真的收得下**。

    ⛔ 不新造一套晋升通道（形态 D）—— 断言方式是把产出直接喂给
       `apply_promotions`，让它自己说收不收。
    ⚠️ 断言 `added` 而不是「没报错」：`apply_promotions` 对不认识的形状是
       **skip 而不是抛**，只看没报错等于什么都没测。
    """
    from smartbi.gold.restaurant import restaurant_intent_promotion as promo

    monkeypatch.setattr(promo, "LEDGER_FILE", tmp_path / "ledger.json")
    monkeypatch.setattr(promo, "REJECTED_FILE", tmp_path / "rejected.json")

    rows = [
        {"query": "哪家店折扣最多", "reason": rq.REASON_NO_DIMENSION},
        {"query": "今天天气怎么样", "reason": rq.REASON_OUT_OF_DOMAIN},
    ]
    entries = rq.promotion_entries(
        rows, code="RESTAURANT_OPS_DISCOUNT_SUMMARY")

    assert entries == [{"query": "哪家店折扣最多",
                        "code": "RESTAURANT_OPS_DISCOUNT_SUMMARY"}], (
        "⛔ 域外必须被剔掉: 「补完能力」这个前提对它根本不成立")

    out = promo.apply_promotions(entries)
    assert out["added"] == entries, f"飞轮 A 不收这个形状: {out['skipped']}"


def test_promotion_entries_writes_nothing_by_itself(tmp_path, monkeypatch):
    """⛔ 「绝不静默自动毕业」——本函数是纯的，写只发生在人跑 `--apply` 时。"""
    from smartbi.gold.restaurant import restaurant_intent_promotion as promo

    ledger = tmp_path / "ledger.json"
    monkeypatch.setattr(promo, "LEDGER_FILE", ledger)
    rq.promotion_entries(
        [{"query": "哪家店折扣最多", "reason": rq.REASON_NO_DIMENSION}],
        code="RESTAURANT_OPS_DISCOUNT_SUMMARY")
    assert not ledger.exists(), "promotion_entries 自己写盘了 —— 那就是自动毕业"


def test_promotion_entries_can_filter_by_reason():
    rows = [
        {"query": "a", "reason": rq.REASON_NO_DIMENSION},
        {"query": "b", "reason": rq.REASON_NO_DATA},
    ]
    got = rq.promotion_entries(rows, code="X", reason=rq.REASON_NO_DATA)
    assert got == [{"query": "b", "code": "X"}]
