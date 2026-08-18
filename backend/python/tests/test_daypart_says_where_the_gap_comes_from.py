"""时段差距来自**人数**还是**每单消费** —— 答案在表里，产品一个字没说。

交付定义②：只把他问的数字念一遍不算。

## 📏 缺陷（prod 实测，MOCK_REST，问「哪个时段生意最好」，n=292）

```
| 时段 | 营收         | 营收占比 | 单量   | 单量占比 | 每单平均消费 |
| 晚市 | ¥14,219,624 | 62.6%   | 37,970 | 62.7%  | ¥374.5     |
| 午市 | ¥8,488,119  | 37.4%   | 22,548 | 37.3%  | ¥376.4     |

生意最好的是**晚市**。时段差距大时先看排班与备货是否跟着时段走；
弱时段适合用套餐或时段价拉客流，不必按全天平均去配人。
```

营收占比 62.6% 与单量占比 62.7% **几乎相同**，客单价午市**还略高** ——
差距完全在「来了多少人」上。这件事他没问，而表里已经有答案了。

## 🔴 顺带修掉一句「没看数据就给的建议」

原来那句收尾在**任何**数据下都说「弱时段适合用套餐或时段价拉客流」。
若差距真的来自每单消费，那句建议是错的。

▎反目标第一条：**一条误发的提示，烧掉的是「这东西说的话能信」。**

## ⛔ 量价分解，不是两个估出来的数

`(Q1-Q2)·P2 + (P1-P2)·Q1 == Q1·P1 - Q2·P2` —— 两项之和**恒等于**营收差，
所以老板拿计算器加一下必然对得上。
"""
from __future__ import annotations

import re
from datetime import date as _date

import pytest

from smartbi.gold.restaurant import restaurant_ops_router as router

_W0, _W1 = _date(2026, 7, 19), _date(2026, 8, 18)


def _rows(pairs):
    """pairs: [(时段, 单量, 营收), ...]"""
    return [{"daypart": d, "bills": b, "revenue": r,
             "window_start": _W0, "window_end": _W1} for d, b, r in pairs]


#: 📏 prod 形状：差距全在人数上（客单价午市还略高）
_VOLUME_DRIVEN = _rows([("晚市", 37970, 14219624.0), ("午市", 22548, 8488119.0)])

#: 🔴 反过来的形状：人数几乎一样，差距在每单消费上。
#    ⚠️ 这一侧存在的全部理由是「让那句建议红一次」—— 一道在两种输入下
#    都给同一句话的收尾，就是没在守任何东西（形态 B⁴）。
_PRICE_DRIVEN = _rows([("晚市", 20000, 12000000.0), ("午市", 19800, 5940000.0)])


class _Ctx:
    def __init__(self, v):
        self._v = v

    async def __aenter__(self):
        return self._v

    async def __aexit__(self, *a):
        return False


class _FakeConn:
    def __init__(self, rows):
        self._rows = rows
        self._calls = 0

    async def execute(self, *a, **kw):
        return None

    async def fetch(self, *a, **kw):
        self._calls += 1
        # 第一发是时段聚合，之后是门店 × 时段（这组用例不看门店那一层）
        return self._rows if self._calls == 1 else []

    async def fetchrow(self, *a, **kw):
        return {"n": 0}

    def transaction(self):
        return _Ctx(self)


class _FakePool:
    def __init__(self, rows):
        self.conn = _FakeConn(rows)

    def acquire(self):
        return _Ctx(self.conn)


async def _ans(rows=None, role="restaurant_manager"):
    return await router.resolve_daypart_performance(
        _FakePool(rows if rows is not None else _VOLUME_DRIVEN),
        "MOCK_REST", days=30, role=role, query="哪个时段生意最好")


async def _text(rows=None, role="restaurant_manager"):
    a = await _ans(rows, role)
    return str(getattr(a, "answer_text", None) or a)


# ── 🔴 承重：分解出来的两项加起来必须等于营收差 ────────────────────────────

@pytest.mark.asyncio
class TestTheDecompositionAddsUp:
    async def test_the_two_parts_sum_to_the_revenue_gap(self):
        """🔴 老板拿计算器加一下必然对得上 —— ⛔ 不是两个各自估出来的数。

        📏 晚市 14,219,624 − 午市 8,488,119 = **5,731,505**
           单量效应 (37970−22548)×376.4 ≈ 5,804,741
           价格效应 (374.5−376.4)×37970 ≈ −73,236
        """
        text = await _text()
        nums = [float(x.replace(",", "")) for x in
                re.findall(r"折合 ¥(-?[\d,]+)", text)]
        gapm = re.search(r"多 ¥([\d,]+)。", text)
        assert len(nums) == 2, f"没找到两项折合金额\n{text}"
        assert gapm, f"没说差多少\n{text}"
        gap = float(gapm.group(1).replace(",", ""))
        assert abs(sum(nums) - gap) <= 2.0, (
            f"两项相加 {sum(nums):,.0f} ≠ 营收差 {gap:,.0f} —— 老板一加就发现对不上\n{text}"
        )

    async def test_it_names_which_side_the_gap_comes_from(self):
        text = await _text()
        assert "差距主要来自**来了多少人**" in text, f"没说差距来自哪一侧\n{text}"


# ── 🔴 那句收尾必须跟着数据变 ──────────────────────────────────────────────

@pytest.mark.asyncio
class TestTheClosingAdviceFollowsTheData:
    async def test_volume_driven_says_pull_people(self):
        text = await _text(_VOLUME_DRIVEN)
        assert "先拉客流" in text, text
        assert "⛔ 提价帮不上忙" in text, text

    async def test_price_driven_says_the_opposite(self):
        """🔴 阴性对照：换成「人数几乎一样」的形状，那句建议必须翻过来。

        ⛔ 一道在两种输入下都给同一句话的收尾，就是没在守任何东西。
        """
        text = await _text(_PRICE_DRIVEN)
        assert "差距主要来自**每单花多少钱**" in text, text
        assert "先看弱时段的菜单结构" in text, text
        assert "先拉客流" not in text, f"人数差不多却还在让他拉客流\n{text}"

    async def test_the_old_blanket_advice_is_gone_when_we_know_better(self):
        """⛔ 那句「弱时段适合用套餐或时段价拉客流」是**没看数据就给的**。

        知道差距来自哪一侧之后，⛔ 不许再发它。
        """
        for rows in (_VOLUME_DRIVEN, _PRICE_DRIVEN):
            text = await _text(rows)
            assert "弱时段适合用套餐或时段价拉客流" not in text, (
                f"知道了差距来源，却还在发那句通用建议\n{text}")


# ── 阴性对照：算不出来的时候 ⛔ 不许硬给 ──────────────────────────────────

@pytest.mark.asyncio
class TestItStaysQuietWhenItCannotTell:
    async def test_single_daypart_says_nothing_about_the_gap(self):
        """一个时段没有「差距」可言。"""
        text = await _text(_rows([("全天", 1000, 300000.0)]))
        assert "拆开看" not in text, f"只有一个时段却在讲差距\n{text}"
        assert "弱时段适合用套餐" in text, "退回原句那一支断了"

    async def test_equal_revenue_says_nothing(self):
        """阴性对照：两个时段营收一样时 ⛔ 不许说「多 ¥0」。"""
        text = await _text(_rows([("晚市", 1000, 300000.0),
                                  ("午市", 900, 300000.0)]))
        assert "拆开看" not in text, f"营收相等却在讲差距\n{text}"

    async def test_price_blind_role_gets_no_decomposition(self):
        """阴性对照：分解全是金额 —— 非价格角色一个字都不该看到。"""
        text = await _text(role="operator")
        assert "拆开看" not in text, f"非价格角色看到了金额分解\n{text}"
        assert "¥" not in text, f"非价格角色看到了金额\n{text}"

    async def test_price_role_does_get_it(self):
        """阳性对照：有权限时**必须**出来 —— 否则上一条可能只是整段没了。"""
        text = await _text()
        assert "拆开看" in text, f"有价格权限却没有分解\n{text}"


# ── 机器可读侧 ────────────────────────────────────────────────────────────

@pytest.mark.asyncio
class TestMetaCarriesTheDriver:
    async def test_meta_has_the_driver(self):
        """正文有而 meta 没有 = 形态 B 第 7 例（投影丢失）。"""
        a = await _ans()
        assert (a.meta or {}).get("gap_driver") == "来了多少人", a.meta

    async def test_meta_driver_is_empty_when_undecided(self):
        """⛔ 空串是「这次没分解」的合法状态，与「不知道」不同。"""
        a = await _ans(_rows([("全天", 1000, 300000.0)]))
        assert (a.meta or {}).get("gap_driver") == "", a.meta
