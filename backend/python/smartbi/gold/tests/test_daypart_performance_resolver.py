"""历史时段表现 resolver 的关键约束。

🔴 存在的理由: 「最近30天哪个时段生意最好」在 2026-08-07 之前拿不到答案 ——
   T3 给 `SALES_SUMMARY + dimensions=('time',)` 被维度闸拦下; 把路由修到排班
   resolver 之后它又**正确地**拒绝(预测排班只做未来)。缺的一直是这个终点。
"""
import pytest

from smartbi.gold.restaurant import restaurant_ops_router as R
from smartbi.gold.restaurant.daypart import DAYPART_CASE_SQL, DAYPART_ORDER


class _Conn:
    def __init__(self, rows, untimed=0):
        self._rows, self._untimed = rows, untimed
        self.sql = []

    async def execute(self, *a, **k):
        return None

    async def fetch(self, sql, *a):
        self.sql.append(sql)
        return self._rows

    async def fetchrow(self, sql, *a):
        self.sql.append(sql)
        return {"n": self._untimed}

    def transaction(self):
        return _Ctx(self)


class _Ctx:
    def __init__(self, v): self._v = v
    async def __aenter__(self): return self._v
    async def __aexit__(self, *a): return False


class _Pool:
    def __init__(self, conn): self.conn = conn
    def acquire(self): return _Ctx(self.conn)


def _row(dp, bills, rev, guests=0):
    from datetime import date
    return {"daypart": dp, "bills": bills, "revenue": rev, "guests": guests,
            "window_start": date(2026, 7, 8), "window_end": date(2026, 8, 7)}


#: 2026-08-07 prod 实测(MOCK_REST 近 30 天, 按 POS 时间戳现切)。
_PROD = [_row("晚市", 109772, 41319090.0), _row("午市", 72716, 27319788.0),
         _row("下午茶", 18194, 6853738.0)]


@pytest.mark.asyncio
async def test_price_role_sees_money_and_top_daypart():
    conn = _Conn(_PROD)
    got = await R.resolve_daypart_performance(_Pool(conn), "MOCK_REST", role="factory_super_admin")

    assert got.code == "RESTAURANT_OPS_DAYPART_PERFORMANCE"
    assert got.meta["top_daypart"] == "晚市"
    assert "¥41,319,090" in got.answer_text
    assert "客单价" in got.answer_text


@pytest.mark.asyncio
async def test_non_price_role_gets_counts_not_money():
    """金额是价格权限数据 —— 非价格角色只出单量与占比(同 resolve_channel_mix)。"""
    conn = _Conn(_PROD)
    got = await R.resolve_daypart_performance(_Pool(conn), "MOCK_REST", role="restaurant_chef")

    assert "¥" not in got.answer_text, got.answer_text
    assert "109,772 单" in got.answer_text
    # 🔴 排序也要跟着换: 否则非价格角色看到的「最好」是按一个他看不见的量排的。
    assert got.meta["top_daypart"] == "晚市"


@pytest.mark.asyncio
async def test_untimed_bills_are_disclosed_not_spread():
    """没有下单时间的单**如实披露, 不摊派** —— 也不能让它们混进「夜宵」。"""
    conn = _Conn(_PROD, untimed=1234)
    got = await R.resolve_daypart_performance(_Pool(conn), "MOCK_REST", role="factory_super_admin")

    assert "1,234 单没有下单时间" in got.answer_text
    assert got.meta["untimed_bills"] == 1234
    # SQL 必须把无时间戳的排除掉, 否则 EXTRACT(HOUR FROM NULL) 会整批落进 ELSE
    # 被算成「夜宵」—— 那不是夜宵, 是没时间戳。
    assert any("time IS NOT NULL" in s for s in conn.sql)


@pytest.mark.asyncio
async def test_no_timed_rows_says_so_instead_of_faking_a_total():
    conn = _Conn([], untimed=99)
    got = await R.resolve_daypart_performance(_Pool(conn), "MOCK_REST", role="factory_super_admin")

    assert got.meta["no_data"] is True
    assert "不会用全天合计替代" in got.answer_text
    assert "99 单没有下单时间" in got.answer_text


def test_daypart_boundaries_have_a_single_definition():
    """⛔ 时段边界只能有一处定义。

    预测排班(staffing_forecast)与本 resolver 必须用同一段 —— 各写一份会让
    「晚市」在两个页面上是两段不同的时间, 而用户看到的是同一个词。
    """
    import io
    from pathlib import Path
    src = Path(R.__file__).parent.parent.parent / "services" / "restaurant" / "staffing_forecast.py"
    staffing = io.open(src, encoding="utf-8").read()
    router = io.open(R.__file__, encoding="utf-8").read()

    # router 侧不得再出现裸的小时判断 —— 必须走共用片段。
    assert "EXTRACT(HOUR FROM time) BETWEEN 10" not in router, (
        "restaurant_ops_router 里出现了第二份时段切分, 应改用 daypart.DAYPART_CASE_SQL"
    )
    assert "DAYPART_CASE_SQL" in router
    # 共用片段与 staffing 现有边界逐字一致(抽取时保留原口径, 不是新定的)。
    for boundary in ("BETWEEN 10 AND 13", "BETWEEN 14 AND 16", "BETWEEN 17 AND 20"):
        assert boundary in DAYPART_CASE_SQL, boundary
        assert boundary in staffing, f"staffing 的边界变了而共用片段没跟上: {boundary}"
    assert DAYPART_ORDER == ("午市", "下午茶", "晚市", "夜宵")
