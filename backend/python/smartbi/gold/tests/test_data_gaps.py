"""数据缺口(G1 的 B 类归宿)的判定测试。

重点全在**让路**的三种情形上 —— 那是这个模块不会变成另一种降级处理的保证。
"""
import pytest

from smartbi.gold.restaurant import data_gaps as G


class _Conn:
    def __init__(self, n):
        self._n = n
        self.sql = None

    async def fetchrow(self, sql, *args):
        self.sql = sql
        if self._n is None:
            raise RuntimeError("relation does not exist")
        return {"n": self._n}


class _Ctx:
    def __init__(self, conn):
        self._conn = conn

    async def __aenter__(self):
        return self._conn

    async def __aexit__(self, *a):
        return False


@pytest.fixture
def with_rows(monkeypatch):
    """把 tenant_conn 换成返回固定行数的假连接。"""
    def _install(n):
        conn = _Conn(n)
        import smartbi.gold.queries as Q
        monkeypatch.setattr(Q, "tenant_conn", lambda pool, fid: _Ctx(conn))
        return conn
    return _install


@pytest.mark.asyncio
async def test_known_gap_with_empty_table_names_the_table(with_rows):
    """命中缺口且表确实为空 -> 点名那张表, 并说清怎么才能有。"""
    conn = with_rows(0)
    got = await G.honest_gap_answer(None, "MOCK_REST", "哪个供应商报价最贵")

    assert got is not None
    # 表名给**工程侧**(交接/排查), 不给客户 —— 见下一条。
    assert got["table"] == "agg_supplier_price"

    # 🔴 客户文案里**绝不能出现表名**。第一版放了 `agg_supplier_price`, prod 上
    #    渲染成「缺的是：``（本店 0 行）」—— customer_text._INTERNAL_IDENTIFIER
    #    会抹掉内部标识符, 那道闸是**刻意的**。加回去不会报错, 只会被抹成空,
    #    于是这条 B 变成一句什么都没说的话。这条断言就是那个静默失败的哨兵。
    assert "agg_supplier_price" not in got["answer_text"]
    assert "_" not in got["answer_text"], "疑似把内部标识符写进了客户文案"

    # goal 要求 B 类别说含糊的「暂无数据」-> 必须说清**是哪件事**和**怎么才能有**。
    assert "供应商报价" in got["answer_text"]
    assert "供应商进货录入" in got["answer_text"]
    assert "凑一个数" in got["answer_text"]
    # 真查了库, 不是硬编码。
    assert conn.sql is not None and "count(*)" in conn.sql


@pytest.mark.asyncio
async def test_table_has_rows_lets_the_original_path_through(with_rows):
    """🔴 客户开始录入之后必须让路 —— 说「没数据」就成了另一种降级处理。"""
    with_rows(7)
    got = await G.honest_gap_answer(None, "MOCK_REST", "哪个供应商报价最贵")
    assert got is None


@pytest.mark.asyncio
async def test_unqueryable_table_lets_the_original_path_through(with_rows):
    """查不动就别说话 —— 说「没数据」可能是假话, 说「有数据」也是。"""
    with_rows(None)
    got = await G.honest_gap_answer(None, "MOCK_REST", "哪个供应商报价最贵")
    assert got is None


@pytest.mark.asyncio
async def test_unrelated_question_is_not_hijacked(with_rows):
    """没登记的问题原样放行 —— 本模块只在已经要走域外拒答的那一步介入。"""
    with_rows(0)
    assert await G.honest_gap_answer(None, "MOCK_REST", "明天天气怎么样") is None
    assert await G.honest_gap_answer(None, "MOCK_REST", "最近30天总营收是多少") is None


@pytest.mark.asyncio
async def test_empty_query_is_not_hijacked(with_rows):
    with_rows(0)
    assert await G.honest_gap_answer(None, "MOCK_REST", "") is None
    assert await G.honest_gap_answer(None, "MOCK_REST", None) is None


def test_every_gap_names_a_table_and_an_action():
    """登记表本身的契约: 每条都要有表名和「怎么才能有」, 否则文案说不出 B。"""
    assert G._GAPS, "登记表空了?"
    for gap in G._GAPS:
        assert gap.terms, gap.subject
        assert gap.table and "_" in gap.table, gap.subject
        assert gap.what_to_do and len(gap.what_to_do) > 8, gap.subject
        # ⛔ 永远不在范围内的东西(天气/新闻)不该登记在这里 —— 它们该继续走域外拒答。
        assert not any(t in ("天气", "新闻", "股票") for t in gap.terms), gap.subject
