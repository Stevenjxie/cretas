"""团购的营收在库里，产出端却打了个「—」—— 那个破折号在说假话。

## 📏 缺陷（prod 实测，MOCK_REST，直接查 `fact_pos_transaction`）

产品给的表：

```
| 渠道 | 营收          | 营收占比 | 单量    | 单量占比 |
| 堂食 | ¥13,373,190  | 63.6%   | 36,393 | 62.2%  |
| 外卖 | ¥5,652,559   | 26.9%   | 16,266 | 27.8%  |
| 团购 | —            | —       | 5,859  | 10.0%  |
```

**63.6% + 26.9% = 90.5%**，少的 9.5% 没有一个字解释，而那个「—」在说
「没有这个数」。查库：

```
groupon  6,085 单  营收 ¥2,078,299.07  net_amount 空值 0
```

金额完完整整在库里。而 `total_rev`（占比的分母）**是含团购的** ——
那 9.5% 既进了分母、又没显示出来。

⚠️ 那段代码的注释写着「堂食/外卖有金额, 其它渠道只有单量」——
**那句话本身就是被实测否掉的那一句**。

设计卡: `docs/decisions/2026-08-18-团购营收被一个破折号抹掉-设计卡.md`
"""
from __future__ import annotations

import re
from datetime import date as _date

import pytest

from smartbi.gold.restaurant import restaurant_ops_router as router

_PCT = re.compile(r"(\d+(?:\.\d+)?)%")


def _channel_table(text: str):
    """把「渠道」那张表的行抠出来 —— ⛔ 不整段 grep（下面还有门店表）。"""
    rows, seen_header = [], False
    for line in (text or "").splitlines():
        s = line.strip()
        if s.startswith("| 渠道 "):
            seen_header = True
            continue
        if seen_header:
            if not s.startswith("|"):
                break
            if re.match(r"\|\s*:?-{3,}", s):
                continue
            rows.append([c.strip() for c in s.strip("|").split("|")])
    return rows


def _revenue_pcts(text: str):
    """渠道表里「营收占比」那一列（第 3 格），只取真是百分数的。"""
    out = []
    for cells in _channel_table(text):
        if len(cells) >= 3:
            m = _PCT.fullmatch(cells[2])
            if m:
                out.append(float(m.group(1)))
    return out


# ── 桩：形状取自 prod 实测 ────────────────────────────────────────────────────
# ⚠️ `window_start`/`window_end` 必须是**真日期** —— 真 SQL 是 MIN/MAX(t.date)，
#    非空分组上永远给日期（形态 B‴：⛔ 别造真实上游产不出的形状）。
_W0, _W1 = _date(2026, 7, 19), _date(2026, 8, 18)

#: 三渠道，团购**有营收** —— 这正是 prod 的形状（groupon 6,085 单 / ¥2,078,299）。
_ROWS_GROUPON_HAS_MONEY = [
    {"order_type": "dine_in", "bills": 60, "revenue": 6000.0,
     "window_start": _W0, "window_end": _W1},
    {"order_type": "takeaway", "bills": 30, "revenue": 3000.0,
     "window_start": _W0, "window_end": _W1},
    {"order_type": "groupon", "bills": 10, "revenue": 1000.0,
     "window_start": _W0, "window_end": _W1},
]

#: 🔴 阴性对照的桩：团购**真的没有金额**（SUM(COALESCE(net_amount,0)) 全 0）。
#    这时 total_rev 也不含它 ⇒ 剩下两行占比自洽，只需要一句说明。
_ROWS_GROUPON_NO_MONEY = [
    dict(r, revenue=0.0) if r["order_type"] == "groupon" else dict(r)
    for r in _ROWS_GROUPON_HAS_MONEY
]

_STORE_ROWS = [
    {"store_name": "北京三里屯店", "order_type": "dine_in", "bills": 50, "revenue": 5000.0},
    {"store_name": "北京三里屯店", "order_type": "takeaway", "bills": 10, "revenue": 1000.0},
    {"store_name": "上海世茂店", "order_type": "dine_in", "bills": 10, "revenue": 1000.0},
    {"store_name": "上海世茂店", "order_type": "takeaway", "bills": 30, "revenue": 3000.0},
]


class _FakeConn:
    def __init__(self, channel_rows):
        self._channel_rows = channel_rows
        self._calls = 0

    async def execute(self, *a, **kw):
        return "SET"

    async def fetch(self, *a, **kw):
        self._calls += 1
        return self._channel_rows if self._calls == 1 else _STORE_ROWS


class _FakePool:
    def __init__(self, channel_rows):
        self.conn = _FakeConn(channel_rows)

    def acquire(self):
        conn = self.conn

        class _Ctx:
            async def __aenter__(self):
                return conn

            async def __aexit__(self, *exc):
                return False

        return _Ctx()


async def _answer(channel_rows=None, role="restaurant_manager"):
    a = await router.resolve_channel_mix(
        _FakePool(channel_rows or _ROWS_GROUPON_HAS_MONEY), "MOCK_REST",
        days=30, role=role, query="外卖和堂食各占多少")
    return str(getattr(a, "answer_text", None) or a)


# ── 🔴 承重：营收占比必须自洽 ───────────────────────────────────────────────

@pytest.mark.asyncio
class TestRevenueSharesAddUp:
    async def test_the_shares_sum_to_one_hundred(self):
        """🔴 这是本条唯一真正的判据 —— 老板一加就发现对不上。

        📏 缺陷时的读数：63.6% + 26.9% = **90.5%**，少的 9.5% 是团购，
           而团购那一格写着「—」（= 没有这个数），⛔ 那是假话。

        ⚠️ 这道闸能不能红是**先证明过的**：团购行打「—」时它的占比不进和，
           和 = 90.5% ≠ 100% ⇒ 红。⛔ 不是「左右两边由构造相等」的恒真式
           （形态 B⁴）。
        """
        text = await _answer()
        pcts = _revenue_pcts(text)
        assert len(pcts) == 3, f"渠道表没有 3 行营收占比，拿到 {pcts}\n{text}"
        assert abs(sum(pcts) - 100.0) <= 0.3, (
            f"营收占比加起来是 {sum(pcts):.1f}% 而不是 100% —— "
            f"有渠道的钱被抹掉了\n{text}"
        )

    async def test_the_groupon_revenue_is_actually_shown(self):
        """团购 1000/10000 = 10.0%，⛔ 不是「—」。"""
        text = await _answer()
        rows = {c[0]: c for c in _channel_table(text) if c}
        assert "团购" in rows, f"团购整行没了\n{text}"
        assert rows["团购"][1] != "—", f"团购营收仍然是破折号\n{text}"
        assert "¥1,000" in rows["团购"][1], rows["团购"]
        assert rows["团购"][2] == "10.0%", rows["团购"]


# ── 🔴 阴性对照：真没有金额时，⛔ 不许当成 ¥0 ─────────────────────────────

@pytest.mark.asyncio
class TestNoMoneyStaysUnknown:
    async def test_a_channel_without_money_keeps_the_dash(self):
        """形态 A¹⁰：⛔ 不把「不知道」翻译成 0。

        真没金额时 `total_rev` 也不含它 ⇒ 剩下两行占比自洽（100%），
        所以上面那条自洽闸**照样过** —— 这条守的是另一件事。
        """
        text = await _answer(_ROWS_GROUPON_NO_MONEY)
        rows = {c[0]: c for c in _channel_table(text) if c}
        assert rows["团购"][1] == "—", f"没有金额却给了一个数\n{text}"
        # ⛔ 只看**表格那一格**，不整段 `"¥0" not in text` —— 第一版就是那么写的，
        #    结果它数进了下面那句说明自己的措辞（「⛔ 不是把它当成 ¥0 算进去了」）。
        #    本仓同型第五次：**闸把自己的输出也数了进去**。
        assert "¥" not in "".join(rows["团购"]), f"团购行里出现了金额\n{rows['团购']}"

    async def test_it_says_why_the_dash_is_there(self):
        """⛔ 光打「—」不算 —— 要说清那一行为什么没有钱。"""
        text = await _answer(_ROWS_GROUPON_NO_MONEY)
        assert "没有金额数据" in text, f"破折号没有解释\n{text}"
        assert "团购（10 单）" in text, f"没点名是哪个渠道、多少单\n{text}"

    async def test_the_explanation_is_absent_when_money_is_there(self):
        """阴性对照的阴性对照：有钱的时候 ⛔ 不许出现那句说明。"""
        text = await _answer()
        assert "没有金额数据" not in text, f"凭空说了一句「没有金额数据」\n{text}"


# ── 交付定义②：门店之间差多少，他没问，而表里已经有答案 ──────────────────

@pytest.mark.asyncio
class TestItSaysSomethingHeDidNotAsk:
    async def test_it_reports_the_spread_between_stores(self):
        """上海世茂 30/40 = 75.0%，三里屯 10/60 = 16.7%，相差 58.3 个百分点。"""
        text = await _answer()
        assert "相差 58.3 个百分点" in text, f"没说门店之间差多少\n{text}"
        assert "最高 上海世茂店 75.0%" in text, text
        assert "最低 北京三里屯店 16.7%" in text, text

    async def test_the_spread_line_follows_the_data(self):
        """⛔ 不许写死 —— 换一份桩，名字和数必须跟着变。

        ⚠️ 与「建议词写死成『门店或菜品』」同型：那次写死之后，
           当 extra 恰好是门店时它建议老板「把门店换成门店」。
        """
        flipped = [
            {"store_name": "广州天河店", "order_type": "takeaway",
             "bills": 90, "revenue": 9000.0},
            {"store_name": "广州天河店", "order_type": "dine_in",
             "bills": 10, "revenue": 1000.0},
            {"store_name": "深圳南山店", "order_type": "dine_in",
             "bills": 95, "revenue": 9500.0},
            {"store_name": "深圳南山店", "order_type": "takeaway",
             "bills": 5, "revenue": 500.0},
        ]
        global _STORE_ROWS
        original, _STORE_ROWS = _STORE_ROWS, flipped
        try:
            text = await _answer()
        finally:
            _STORE_ROWS = original
        assert "最高 广州天河店 90.0%" in text, text
        assert "最低 深圳南山店 5.0%" in text, text
        assert "相差 85.0 个百分点" in text, text

    async def test_it_does_not_judge_the_spread_for_him(self):
        """⛔ 只陈述极差，不下「差异很小 / 很大」的判断。

        那要一个我拍脑袋的阈值，而反目标说「宁可这一类先不提示」——
        1.2 个百分点还是 20 个，老板自己看得出来。**判断留给他。**
        """
        text = await _answer()
        for verdict in ("差异很小", "差异很大", "几乎一样", "差距悬殊", "结构性"):
            assert verdict not in text, f"替老板下了判断: {verdict}\n{text}"


@pytest.mark.asyncio
class TestSingleStoreTenantGetsNoSpreadLine:
    async def test_one_store_says_nothing_about_spread(self):
        """阴性对照：一家店没有「之间」可言 —— ⛔ 不许说「相差 0.0 个百分点」。"""
        one = [{"store_name": "唯一店", "order_type": "dine_in",
                "bills": 60, "revenue": 6000.0},
               {"store_name": "唯一店", "order_type": "takeaway",
                "bills": 40, "revenue": 4000.0}]
        global _STORE_ROWS
        original, _STORE_ROWS = _STORE_ROWS, one
        try:
            text = await _answer()
        finally:
            _STORE_ROWS = original
        assert "个百分点" not in text, f"单店租户被告知门店之间的极差\n{text}"
