"""时间词语料的读写。

## 为什么有这张表

确定性层(`_resolve_sales_date_range`)认不出「最近」这类说法时, LLM 会认出来
并给出规范短语。**今天这个知识用完就扔** —— 同一个词每次都要重新花一次 LLM。

⇒ 记下来, 攒够了由人晋升进确定性规则。

## 入库条件

`spec.window_from_llm_phrase` —— 产品**已经算好**了(`restaurant_intent.py:2266`),
⛔ 这里不重算。
"""
from unittest.mock import AsyncMock, patch

import pytest

import smartbi.gold.restaurant.restaurant_intent as RI
from smartbi.gold.restaurant.time_phrase_corpus import (
    corpus_counts,
    list_unpromoted,
    mark_promoted,
    record_time_phrase,
)


class _FakeConn:
    def __init__(self, *, fail=False):
        self.fail = fail
        self.calls = []
        self.rows = []

    async def execute(self, sql, *args):
        if self.fail:
            raise RuntimeError("db down")
        self.calls.append(("execute", sql, args))
        return "INSERT 0 1"

    async def fetch(self, sql, *args):
        if self.fail:
            raise RuntimeError("db down")
        self.calls.append(("fetch", sql, args))
        return self.rows

    async def fetchrow(self, sql, *args):
        if self.fail:
            raise RuntimeError("db down")
        self.calls.append(("fetchrow", sql, args))
        return self.rows[0] if self.rows else None


class _FakePool:
    def __init__(self, conn):
        self._conn = conn

    def acquire(self):
        conn = self._conn

        class _Ctx:
            async def __aenter__(self):
                return conn

            async def __aexit__(self, *a):
                return False

        return _Ctx()


ARGS = dict(domain="restaurant", factory_id="MOCK_REST",
            raw_query="最近损耗怎么样", llm_phrase="最近30天",
            llm_time_range={"type": "relative", "unit": "day", "count": 30})


@pytest.mark.asyncio
async def test_records_a_row():
    conn = _FakeConn()
    assert await record_time_phrase(_FakePool(conn), **ARGS) is True
    assert conn.calls, "一条 SQL 都没发 —— 下面的断言会恒真"


@pytest.mark.asyncio
async def test_write_failure_is_fail_open_but_loud(caplog):
    """🔴 承重: 记语料失败**不许**让问答失败, 但**必须**留下痕迹。

    ⛔ 静默吞掉 ⇒「语料一直是空的」与「没有这类问句」长得一模一样,
    而这张表就是我们唯一的仪器。
    """
    conn = _FakeConn(fail=True)
    with caplog.at_level("WARNING"):
        assert await record_time_phrase(_FakePool(conn), **ARGS) is False
    assert any("time_phrase" in r.message or "语料" in r.message
               for r in caplog.records), [r.message for r in caplog.records]


@pytest.mark.asyncio
async def test_same_query_accumulates_instead_of_duplicating():
    """同一句只一行 —— PK 是 (domain, normalized_phrase), 靠 ON CONFLICT 累加。"""
    conn = _FakeConn()
    await record_time_phrase(_FakePool(conn), **ARGS)
    sql = conn.calls[0][1].upper()
    assert "ON CONFLICT" in sql, sql
    assert "HIT_COUNT" in sql, sql


@pytest.mark.asyncio
async def test_unpromoted_query_filters_on_promoted_at():
    conn = _FakeConn()
    await list_unpromoted(_FakePool(conn))
    sql = conn.calls[0][1].upper()
    assert "PROMOTED_AT IS NULL" in sql, sql


@pytest.mark.asyncio
async def test_counts_separate_total_from_unpromoted():
    """🔴 这两个数**必须分开** —— 跑批要靠它们区分「饱和」和「写入路径没跑」。"""
    conn = _FakeConn()
    conn.rows = [{"total": 7, "unpromoted": 3}]
    got = await corpus_counts(_FakePool(conn))
    assert got == {"total": 7, "unpromoted": 3}, got


@pytest.mark.asyncio
async def test_mark_promoted_writes_who_and_why():
    """⛔ 不许只写时间戳 —— 登记是留痕, 不是打勾。"""
    conn = _FakeConn()
    await mark_promoted(_FakePool(conn), domain="restaurant",
                        normalized_phrase="最近损耗怎么样",
                        reviewed_by="steve", note="加了「最近」分支")
    sql, args = conn.calls[0][1].upper(), conn.calls[0][2]
    assert "PROMOTED_AT" in sql and "REVIEWED_BY" in sql and "PROMOTED_NOTE" in sql
    assert "steve" in args and "加了「最近」分支" in args


# ══════════════════════════════════════════════════════════════════
# 接线: 走真实入口 parse_restaurant_query
#
# ⚠️ 下面这套夹具**裁剪自** `tests/test_restaurant_intent_flywheel_reconnect.py`
# 的 `_FakePool`/`_FakeConn`/`_Row`(同一个被测模块, 同一条 semantic_first 路径,
# 已经被那个文件的十几条测试验证过能正确驱动到 `_semantic_spec_from_t3` /
# `_build_spec`)。这里只留「真实入口这条最短路径」用得到的三类 SQL
# (tenant 闸 / 门店目录 / 晋升表读), 未预期的 SQL 一律 AssertionError ——
# 不静默放行, 免得桩自己造出一个真实系统永远不会给出的形状(形态 B‴)。
# ══════════════════════════════════════════════════════════════════

class _Row(dict):
    """asyncpg Record 的替身: 被测代码只用 __getitem__。"""


class _CorpusFakeConn:
    def __init__(self, owner):
        self.owner = owner
        self.in_transaction = False
        self.active_factory = None

    def transaction(self):
        conn = self

        class _Ctx:
            async def __aenter__(self):
                conn.in_transaction = True
                return None

            async def __aexit__(self, *_exc):
                conn.in_transaction = False
                conn.active_factory = None
                return False

        return _Ctx()

    async def execute(self, sql, *args):
        if "set_config('app.factory_id'" in sql:
            assert self.in_transaction, "RLS GUC must be transaction-local"
            self.active_factory = args[0]
            return "SELECT 1"
        raise AssertionError(f"unexpected execute SQL: {sql}")

    async def fetchrow(self, sql, *args):
        if "agg_restaurant_daily_totals" in sql:
            # 命中 = 判为餐饮租户, 放行进 T3。
            return {"?column?": 1}
        raise AssertionError(f"unexpected fetchrow SQL: {sql}")

    async def fetch(self, sql, *args):
        if "ai_promoted_routes" in sql:
            # 没有人审晋升过的行 -> 零 token 回放必然 miss, 一定会走到 T3。
            return []
        if "FROM dim_store" in sql or "fact_pos_item" in sql:
            return [_Row(name=name) for name in self.owner.stores]
        raise AssertionError(f"unexpected fetch SQL: {sql}")


class _CorpusFakePool:
    def __init__(self, *, stores=("模拟·测试门店",)):
        self.stores = list(stores)

    def acquire(self):
        conn = _CorpusFakeConn(self)

        class _Ctx:
            async def __aenter__(self):
                return conn

            async def __aexit__(self, *_exc):
                return False

        return _Ctx()


FACTORY = "MOCK_REST"


def _llm_time_plan() -> dict:
    """T3 给出的完整计划, 时间窗**只来自 `time_range`**(用户原话没有时间词,
    规则层解不出来, 只能靠这个字段补)。`store_scope: all` 让
    `_apply_store_scope_guard` 短路放行, 断言只落在 window_from_llm_phrase 上,
    不被门店范围反问干扰。"""
    return {
        "intent": "RESTAURANT_OPS_SALES_SUMMARY",
        "time_range": {"type": "relative", "unit": "day", "count": 30},
        "wants_margin": False,
        "asks_profitability": False,
        "requested_metrics": ["revenue"],
        "analysis_action": "lookup",
        "dimensions": [],
        "dish": None,
        "store": None,
        "stores": [],
        "store_scope": "all",
        "confidence": 0.95,
        "clarification_needed": False,
        "missing_fields": [],
        "clarification_question": None,
        "clarification_options": [],
    }


def _rule_resolved_plan() -> dict:
    """同一份计划, 唯一的差别是 `time_range=None` —— 模拟规则层自己就从句子
    里解出了窗口, LLM 没有额外贡献时间信息, 所以 `window_from_llm_phrase`
    该恒为 False。"""
    plan = _llm_time_plan()
    plan["time_range"] = None
    return plan


@pytest.fixture(autouse=True)
def _reset_ri_caches():
    """接线测试会真的驱动 `parse_restaurant_query` 的进程内缓存(路由缓存/
    租户闸缓存/语义计划缓存/晋升表缓存)。前后都清, 不让这几条测试之间
    或与其它测试文件之间因为缓存命中而相互影响。"""
    RI.clear_route_cache()
    RI.clear_tenant_gate_cache()
    RI.clear_semantic_plan_cache()
    RI.clear_promoted_routes_cache()
    yield
    RI.clear_route_cache()
    RI.clear_tenant_gate_cache()
    RI.clear_semantic_plan_cache()
    RI.clear_promoted_routes_cache()


@pytest.mark.asyncio
async def test_real_entry_point_records_when_llm_supplied_the_window(monkeypatch):
    """🔴 承重: 走 `parse_restaurant_query`, ⛔ 不直接调 `record_time_phrase`。

    ⚠️ 直接调数据层的断言在「谁调它」忘了接线时**全绿** —— 本仓形态 B,
    这个分支上已经犯过两次。

    夹具桩掉的东西, 逐条写明:
      - `pool`: `_CorpusFakePool`/`_CorpusFakeConn`, 只答三类 SQL(tenant 闸 /
        门店目录 / 晋升表读), 其余一律 AssertionError。
      - `RI._t3_llm_parse`: 桩成直接返回一份构造好的 T3 计划(不打真实 LLM),
        这是「走真实入口, 只桩外部 IO」的标准做法, 与
        `test_restaurant_intent_flywheel_reconnect.py` 同源同一手法。
      - `RI.record_time_phrase`: 打成 spy 记录调用参数(承重断言的观测点)。
    ⛔ 不桩 `record_time_phrase` 之外/`_t3_llm_parse` 之外的任何产品判别逻辑
    (时间窗解析 `_resolve_sales_date_range`、`_build_spec` 的
    `window_from_llm_phrase` 计算全部走真代码)。
    """
    seen = []

    async def _spy(pool, **kw):
        seen.append(kw)
        return True

    monkeypatch.setattr(RI, "record_time_phrase", _spy)

    pool = _CorpusFakePool()
    query = "最近损耗怎么样"  # 用户原话没有时间词 -> 规则层判「全部历史」
    with patch.object(RI, "_t3_llm_parse", new=AsyncMock(return_value=_llm_time_plan())):
        spec = await RI.parse_restaurant_query(
            query, pool, factory_id=FACTORY, semantic_first=True,
        )

    # 阳性对照: 夹具真的搭出了 window_from_llm_phrase=True 的形状 —— 否则
    # 下面「记没记」这条断言测的是夹具会不会给出这个前提, 不是接线本身。
    assert spec is not None and spec.window_from_llm_phrase, (
        f"夹具没搭对: spec.window_from_llm_phrase={getattr(spec, 'window_from_llm_phrase', None)!r}, "
        "这不是有效的 RED/GREEN 起点")

    assert seen, "一次都没记 —— 接线没接上"

    # 🔴 两个入参故意造成不同的串, 分别断言各自落到了哪个键 —— 相同就分不清
    # 代码用的到底是哪一个(本仓吃过这个亏的教训, 见本文件顶部的裁定 C)。
    assert seen[0]["llm_phrase"] == "最近30天", seen[0]
    assert seen[0]["raw_query"] == "最近损耗怎么样", seen[0]
    assert seen[0]["llm_phrase"] != seen[0]["raw_query"]
    assert seen[0]["llm_phrase"] == spec.window_label
    assert seen[0]["raw_query"] == query
    assert seen[0]["factory_id"] == FACTORY
    assert seen[0]["domain"] == "restaurant"
    assert seen[0]["llm_time_range"] is None


@pytest.mark.asyncio
async def test_nothing_is_recorded_when_the_rules_resolved_the_window(monkeypatch):
    """阴性对照: 规则自己解得出窗口时 ⛔ 不记。

    ⛔ 少了它,「记了一条」在「对任何问句都记」时也成立 —— 那会把整张表
    灌成噪音, 而人工晋升靠它排优先级。
    """
    seen = []

    async def _spy(pool, **kw):
        seen.append(kw)
        return True

    monkeypatch.setattr(RI, "record_time_phrase", _spy)

    pool = _CorpusFakePool()
    query = "本月营收多少"  # 用户原话自带时间词 -> 规则层直接解出窗口
    with patch.object(RI, "_t3_llm_parse", new=AsyncMock(return_value=_rule_resolved_plan())):
        spec = await RI.parse_restaurant_query(
            query, pool, factory_id=FACTORY, semantic_first=True,
        )

    # 阳性对照: 解析真的成功了(不是因为报错/反问才没记)。
    assert spec is not None and not spec.clarification_needed, spec
    assert spec.window_from_llm_phrase is False, (
        "夹具没搭对: 这条本该由规则层解出窗口, window_from_llm_phrase 不该是 True")

    assert seen == [], f"规则自己解出窗口时不该记语料, 却记了: {seen}"
