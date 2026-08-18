"""缺口清单第 17 项 —— 窗口内 0 行时只报「最后一条记录是什么时候 + 请排查」。

背景(`docs/decisions/2026-08-18-餐饮AI架构-完整版-owner定稿.md` 例 13):

    ❌ 三态(合法空 / 没同步 / 没接入) —— AI 判断不了「这家店有没有开张」
    ✅ 「这家店最后一条记录是 8/12，之后没有数据上来。
         麻烦排查一下这家店的 POS 是不是没在传数据。」

⇒ 判据: 报「最后一条记录什么时候」+ 把排查交给老板, ⛔ 不替他判断现场。

本文件测两层:
  A. `_empty_window_last_record_date` / `_empty_window_hint_sentence` 两个
     helper 的纯行为(不经过任何 resolver)。
  B. 三个真实 resolver(`resolve_sales_summary` / `resolve_discount_summary` /
     `resolve_trend_analysis`)的「窗口内 0 行」分支 —— 证明 helper **接上了**
     产品入口, 不是「测了 helper 没测接线」(本仓形态 B 反复记过的坑)。

⚠️ 每条「不给这句话」的分支都要证明: 不是因为异常被吞掉、看不出差别,
   而是**确实没有触发 append**(用不含关键词断言 + meta 字段双重确认)。
"""
from __future__ import annotations

import asyncio
from datetime import date

import pytest

from smartbi.gold.restaurant import restaurant_ops_router as R

HINT_MARKER = "麻烦排查一下 POS 是不是没在同步数据。"


# ══════════════════════════════════════════════════════════════════════
# 测试基础设施 —— 复用 test_discount_summary_resolver.py 的 _Ctx 模式
# ══════════════════════════════════════════════════════════════════════

class _Ctx:
    def __init__(self, v):
        self._v = v

    async def __aenter__(self):
        return self._v

    async def __aexit__(self, *a):
        return False


class _HintConn:
    """假连接: 应答 helper 的 MAX/COUNT FILTER 查询。"""

    def __init__(self, *, last_before=None, rows_at_or_after=0, raise_on_fetchrow=False):
        self._last_before = last_before
        self._rows_at_or_after = rows_at_or_after
        self._raise = raise_on_fetchrow
        self.fetchrow_calls: list = []
        self.execute_calls: list = []

    async def execute(self, sql, *params):
        self.execute_calls.append((sql, params))
        return None

    async def fetchrow(self, sql, *params):
        self.fetchrow_calls.append(params)
        if self._raise:
            raise RuntimeError("simulated query failure")
        return {"last_before": self._last_before, "rows_at_or_after": self._rows_at_or_after}


class _HintPool:
    def __init__(self, conn):
        self.conn = conn

    def acquire(self):
        return _Ctx(self.conn)


class _NoFetchrowConn:
    """模拟本仓既有测试里那种只实现 execute/fetchval 的旧式假连接
    (`test_discount_summary_resolver.py::_Conn`) —— 用来证明 helper 对
    「协作者没实现 fetchrow」这类异常同样优雅降级, 不把主答案炸掉。
    """

    async def execute(self, *a, **k):
        return None


class _NoFetchrowPool:
    def __init__(self):
        self.conn = _NoFetchrowConn()

    def acquire(self):
        return _Ctx(self.conn)


class _PoisonPool:
    """.acquire() 一旦被调用就计数 + 报错。

    🔴 C″ 教训: helper 自己有 `except Exception` 兜底, 如果只在这里
    `raise AssertionError`, 异常会被 helper 悄悄吞掉、返回 None ——
    看起来像「验证通过」, 其实只验证了「查询失败会被吞」(另一条测试已经
    在测这件事), 没有验证「根本没有发起查询」。⇒ 用调用计数做真正的判据,
    `raise` 只是双保险。
    """

    def __init__(self):
        self.acquire_calls = 0

    def acquire(self):
        self.acquire_calls += 1
        raise AssertionError("不应该在这个分支里查询数据库")


def _run(coro):
    return asyncio.run(coro)


# ══════════════════════════════════════════════════════════════════════
# A. helper 纯行为
# ══════════════════════════════════════════════════════════════════════

def test_returns_none_without_querying_when_window_start_is_none():
    """全部历史查询(没有一个窗口起点可比较) —— 连库都不该查。

    判据用**调用计数**, 不是「拿到 None 就算过」—— 否则「查询失败被吞」
    和「根本没查询」会是同一个读数, 分不清 early-return 是不是真的生效。
    """
    pool = _PoisonPool()
    got = _run(R._empty_window_last_record_date(
        pool, "F1", table="agg_daily", window_start=None,
    ))
    assert got is None
    assert pool.acquire_calls == 0, "window_start=None 时仍然发起了查询"


def test_returns_none_without_querying_for_unlisted_table():
    """table 不在 allowlist 里 —— 同样不查库, 防的是任意字符串被拼进 SQL。"""
    pool = _PoisonPool()
    got = _run(R._empty_window_last_record_date(
        pool, "F1", table="fact_pos_transaction_typo",
        window_start=date(2026, 8, 1),
    ))
    assert got is None
    assert pool.acquire_calls == 0, "未登记的 table 仍然发起了查询"


def test_returns_the_date_when_nothing_came_in_since():
    """典型场景: 之前有数据、窗口起点之后再没有一行 —— 给出那个日期。"""
    conn = _HintConn(last_before=date(2026, 8, 12), rows_at_or_after=0)
    got = _run(R._empty_window_last_record_date(
        _HintPool(conn), "F1", table="agg_daily", window_start=date(2026, 8, 15),
    ))
    assert got == date(2026, 8, 12)
    assert conn.fetchrow_calls, "helper 必须真的发出了查询"


def test_returns_none_when_there_was_never_any_record():
    """从来没有过数据(last_before=None) —— 不是「停止上传」, 是另一句话,
    这条判据不覆盖, 宁可不说。"""
    conn = _HintConn(last_before=None, rows_at_or_after=0)
    got = _run(R._empty_window_last_record_date(
        _HintPool(conn), "F1", table="agg_daily", window_start=date(2026, 8, 15),
    ))
    assert got is None


def test_returns_none_when_data_resumed_after_the_window_started():
    """🔴 核心阴性对照: 窗口起点之后其实还有数据(中间是个孤立缺口) ——
    「之后没有数据上来」会是一句假话, 必须闭嘴而不是照样报最后一条。
    """
    conn = _HintConn(last_before=date(2026, 8, 12), rows_at_or_after=3)
    got = _run(R._empty_window_last_record_date(
        _HintPool(conn), "F1", table="agg_daily", window_start=date(2026, 8, 15),
    ))
    assert got is None


def test_query_failure_is_swallowed_not_propagated():
    """协作者查询异常(含没实现 fetchrow 的旧式假连接) —— 这是锦上添花的
    提示, 不能把主答案拖垮; 一律降级为 None。"""
    conn = _HintConn(raise_on_fetchrow=True)
    got = _run(R._empty_window_last_record_date(
        _HintPool(conn), "F1", table="agg_daily", window_start=date(2026, 8, 15),
    ))
    assert got is None

    got2 = _run(R._empty_window_last_record_date(
        _NoFetchrowPool(), "F1", table="agg_daily", window_start=date(2026, 8, 15),
    ))
    assert got2 is None


def test_hint_sentence_shape():
    assert R._empty_window_hint_sentence(None) == ""
    text = R._empty_window_hint_sentence(date(2026, 8, 12))
    assert "2026-08-12" in text
    assert HINT_MARKER in text
    assert "之后没有数据上来" in text


# ══════════════════════════════════════════════════════════════════════
# B. 接线到真实 resolver —— resolve_sales_summary
# ══════════════════════════════════════════════════════════════════════

def _install_empty_finance_summary(monkeypatch):
    async def _fake_finance_summary(pool, factory_id, date_range, top_n_stores=5):
        return {
            "total_revenue": 0.0, "bill_count": 0, "avg_bill_value": None,
            "day_count": 0, "store_count": 0, "top_stores": [],
            "actual_start_date": None, "actual_end_date": None,
        }

    async def _fake_store_comparison(pool, factory_id, date_range):
        return {"stores": [], "weakStores": []}

    import smartbi.gold.queries as Q
    monkeypatch.setattr(Q, "finance_summary", _fake_finance_summary)
    monkeypatch.setattr(Q, "store_comparison", _fake_store_comparison)


@pytest.mark.asyncio
async def test_sales_summary_empty_window_reports_last_record(monkeypatch):
    _install_empty_finance_summary(monkeypatch)
    conn = _HintConn(last_before=date(2026, 8, 12), rows_at_or_after=0)
    pool = _HintPool(conn)

    got = await R.resolve_sales_summary(
        pool, "MOCK_REST", role="restaurant_owner", query="今天营收多少",
        date_range=(date(2026, 8, 18), date(2026, 8, 18)), window_label="今天",
    )

    assert got.meta["no_data"] is True
    assert got.meta["last_record_before_window"] == "2026-08-12"
    assert "没有可用的营收和订单数据" in got.answer_text, "既有的诚实措辞不能被顶掉"
    assert "2026-08-12" in got.answer_text
    assert HINT_MARKER in got.answer_text
    assert conn.fetchrow_calls, "必须真的查询了 —— 不是只在 meta 里编了个字段"


@pytest.mark.asyncio
async def test_sales_summary_empty_window_stays_silent_when_hint_unavailable(monkeypatch):
    """🔴 反目标第一条: 查不到出处的日期宁可不说。"""
    _install_empty_finance_summary(monkeypatch)
    pool = _NoFetchrowPool()

    got = await R.resolve_sales_summary(
        pool, "MOCK_REST", role="restaurant_owner", query="今天营收多少",
        date_range=(date(2026, 8, 18), date(2026, 8, 18)), window_label="今天",
    )

    assert got.meta["no_data"] is True
    assert got.meta["last_record_before_window"] is None
    assert "没有可用的营收和订单数据" in got.answer_text
    assert "最后一条记录是" not in got.answer_text
    assert HINT_MARKER not in got.answer_text


# ══════════════════════════════════════════════════════════════════════
# B. 接线到真实 resolver —— resolve_discount_summary
# ══════════════════════════════════════════════════════════════════════

def _install_zero_revenue_discount_summary(monkeypatch):
    async def _fake_discount_summary(pool, factory_id, date_range, **kw):
        return {"total_discount_amount": 0.0, "total_revenue": 0.0,
                "revenue_share_pct": None, "discounts": []}

    import smartbi.gold.queries as Q
    monkeypatch.setattr(Q, "discount_summary", _fake_discount_summary)


@pytest.mark.asyncio
async def test_discount_summary_empty_window_reports_last_record(monkeypatch):
    _install_zero_revenue_discount_summary(monkeypatch)
    conn = _HintConn(last_before=date(2026, 7, 30), rows_at_or_after=0)
    pool = _HintPool(conn)

    got = await R.resolve_discount_summary(
        pool, "MOCK_REST", 30, role="factory_super_admin", query="折扣力度多大",
        date_range=(date(2026, 8, 1), date(2026, 8, 18)),
    )

    assert got.meta["no_data"] is True
    assert got.meta["last_record_before_window"] == "2026-07-30"
    assert "折扣占比没有分母" in got.answer_text
    assert "2026-07-30" in got.answer_text
    assert HINT_MARKER in got.answer_text


@pytest.mark.asyncio
async def test_discount_summary_empty_window_stays_silent_when_hint_unavailable(monkeypatch):
    _install_zero_revenue_discount_summary(monkeypatch)
    pool = _NoFetchrowPool()

    got = await R.resolve_discount_summary(
        pool, "MOCK_REST", 30, role="factory_super_admin", query="折扣力度多大",
        date_range=(date(2026, 8, 1), date(2026, 8, 18)),
    )

    assert got.meta["last_record_before_window"] is None
    assert "最后一条记录是" not in got.answer_text


# ══════════════════════════════════════════════════════════════════════
# B. 接线到真实 resolver —— resolve_trend_analysis
# ══════════════════════════════════════════════════════════════════════

def _install_empty_trend_bundle(monkeypatch):
    async def _fake_trend_bundle(pool, factory_id, window):
        return {"monthlyTrend": [], "dailyTrend": [], "weekdayWeekend": {}}

    import smartbi.gold.queries as Q
    monkeypatch.setattr(Q, "trend_bundle", _fake_trend_bundle)


@pytest.mark.asyncio
async def test_trend_analysis_empty_scoped_window_reports_last_record(monkeypatch):
    _install_empty_trend_bundle(monkeypatch)
    conn = _HintConn(last_before=date(2026, 5, 20), rows_at_or_after=0)
    pool = _HintPool(conn)

    got = await R.resolve_trend_analysis(
        pool, "MOCK_REST", role="restaurant_owner", query="营收趋势",
        date_range=(date(2026, 6, 1), date(2026, 6, 30)),
    )

    assert got.meta["no_data"] is True
    assert got.meta["last_record_before_window"] == "2026-05-20"
    assert "暂无按日期拆分的营业数据" in got.answer_text
    assert "2026-05-20" in got.answer_text
    assert HINT_MARKER in got.answer_text


@pytest.mark.asyncio
async def test_trend_analysis_full_history_empty_never_queries_for_a_hint(monkeypatch):
    """window=(None, None) 时(问的是「全部历史」)没有一个可比较的窗口起点
    —— helper 应该连库都不查(用 _PoisonPool 证明), 而不是查到什么算什么。
    """
    _install_empty_trend_bundle(monkeypatch)
    pool = _PoisonPool()

    got = await R.resolve_trend_analysis(
        pool, "MOCK_REST", role="restaurant_owner", query="营收趋势",
        date_range=None,
    )

    assert got.meta["no_data"] is True
    assert got.meta["last_record_before_window"] is None
    assert "最后一条记录是" not in got.answer_text
    assert pool.acquire_calls == 0, "全部历史窗口不该为提示句查询数据库"
