"""门店表覆盖不全时必须把差额说出来 —— 否则每个数都对，合起来是谎。

## 缺陷（2026-08-17 实测，我自己当天造的）

给损耗答案加了「各门店损耗金额」表，上线后 prod 实测：

```
抬头（30 天全部）: ¥317,441.84   覆盖 31 天
门店表合计:       ¥  6,828.07   覆盖  1 天
```

门店表只有总额的 **2%**，而且**一个字都没说为什么**。成因是存量行在
`store_id` 这一列存在之前写的、全是 NULL，聚合侧 `WHERE store_id IS NOT NULL`
把它们排除了（那个排除是对的 —— 不排会聚成 `dim_value_id = 0` 的幽灵门店）。

▎老板会把这张表读成「各店几乎不损耗」。
▎**这正是「看起来完全正常的错数」：每个数都对，合起来是谎。**

⇒ 覆盖不全就把差额说出来。⛔ 不许默默只显示一部分。

⚠️ 这一条**不会**随回填完成而失效：任何新接入的租户、任何一段没记门店的
历史，都会再次出现覆盖不全。它守的是「不完整就说」，不是「这次差多少」。
"""
from datetime import date as _date

import pytest

from smartbi.gold.restaurant import restaurant_ops_router as router


def _agg_rows(per_store):
    return [{"store_name": n, "cost": c} for n, c in per_store]


class _Conn:
    """只桩数据库。⚠️ 形状取自真实上游：

    · top/type 行有数据（否则走空窗口那一支，与本条无关）
    · totals 那条 SQL 全是 `COALESCE(..., 0)` ⇒ 空集也返回**零值的一行**
    · 门店聚合返回 `(store_name, cost)`
    """

    def __init__(self, total_cost, per_store):
        self._total_cost = total_cost
        self._per_store = per_store
        self._fetches = 0

    async def execute(self, *a, **kw):
        return "SET"

    async def fetch(self, sql, *a, **kw):
        self._fetches += 1
        if "wastage_cost_by_store" in sql:
            return _agg_rows(self._per_store)
        if "dim_value_str" in sql or "wastage_cost_by_type" in sql:
            return [{"type": "变质", "cost": self._total_cost}]
        return [{"name": "罗氏虾", "category": "水产", "unit": "kg",
                 "qty": 10.0, "cost": self._total_cost}]

    async def fetchrow(self, *a, **kw):
        return {"total_qty": 10.0, "total_cost": self._total_cost,
                "total_count": 5}

    async def fetchval(self, *a, **kw):
        return _date(2026, 6, 8)


class _Pool:
    def __init__(self, conn):
        self._conn = conn

    def acquire(self):
        conn = self._conn

        class _Ctx:
            async def __aenter__(self):
                return conn

            async def __aexit__(self, *exc):
                return False

        return _Ctx()


async def _answer(total_cost, per_store):
    a = await router.resolve_wastage_top(
        _Pool(_Conn(total_cost, per_store)), "MOCK_REST", days=30)
    return str(getattr(a, "answer_text", None) or a)


@pytest.mark.asyncio
class TestPartialCoverageIsDeclared:
    async def test_the_prod_shape_says_how_much_it_covers(self):
        """线上那一刻的形状：门店表只占总额 2%。"""
        text = await _answer(317441.84, [("A店", 4000.0), ("B店", 2828.07)])
        assert "各门店损耗金额" in text, text
        assert "只覆盖" in text, f"覆盖只有 2% 却什么都没说:\n{text}"
        assert "2%" in text, f"没说清占比:\n{text}"

    async def test_it_names_the_covered_amount_not_just_a_percentage(self):
        """光给百分比老板还得自己乘 —— 把金额也说出来。"""
        text = await _answer(317441.84, [("A店", 4000.0), ("B店", 2828.07)])
        assert "6,828.07" in text, f"没说覆盖了多少钱:\n{text}"

    async def test_full_coverage_says_nothing(self):
        """阴性对照：覆盖齐了就**不要**加那句话。

        ⛔ 少了这条，「不完整就说」可能只是「对什么都说」——
           那种提示会天天出现，然后被当成噪音忽略（形态 E）。
        """
        text = await _answer(1000.0, [("A店", 600.0), ("B店", 400.0)])
        assert "各门店损耗金额" in text, text
        assert "只覆盖" not in text, f"覆盖齐了还在提示:\n{text}"

    async def test_rounding_slack_does_not_trigger_the_note(self):
        """浮点/四舍五入的零头不算覆盖不全 —— 阈值是 99.5%。"""
        text = await _answer(1000.0, [("A店", 600.0), ("B店", 399.99)])
        assert "只覆盖" not in text, f"0.001% 的零头触发了提示:\n{text}"

    async def test_single_store_still_has_no_table(self):
        """一家店不出表（一行的表格只是多两条竖线）—— 也就没有覆盖度这回事。"""
        text = await _answer(1000.0, [("A店", 100.0)])
        assert "各门店损耗金额" not in text, text
        assert "只覆盖" not in text, text
