"""时间词语料的读写。

## 为什么有这张表

确定性层(`_resolve_sales_date_range`)认不出「最近」这类说法时, LLM 会认出来
并给出规范短语。**今天这个知识用完就扔** —— 同一个词每次都要重新花一次 LLM。

⇒ 记下来, 攒够了由人晋升进确定性规则。

## 入库条件

`spec.window_from_llm_phrase` —— 产品**已经算好**了(`restaurant_intent.py:2266`),
⛔ 这里不重算。
"""
import pytest

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
