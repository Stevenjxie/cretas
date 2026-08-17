"""门店首段在**覆盖不全**时必须带限定语 —— 老板可能只读那一句。

## 缺陷（我自己在 PR #2805 里造的）

#2805 给损耗答案加了一句首段：

```
按门店看：**模拟·长宁龙之梦店** 损耗最多，¥31,436.37；最少的是 … ¥28,985.44。
最高比最低高 7.8%，值得单独看这家：…
```

它排在**表格上面**。而 `_store_breakdown_block` 的覆盖度提示排在**表格下面**，
且那个提示的 docstring 里记着一次真实事故：

```
抬头(30 天全部): ¥317,441.84
门店表合计:      ¥  6,828.07 /  1 天      ← 只有总额的 2%
成因: 存量行 store_id 全是 NULL, 聚合侧 WHERE store_id IS NOT NULL 排除了它们
▎老板会把这张表读成「各店几乎不损耗」——**每个数都对, 合起来是谎**
```

▎**老板可能只读首段。** 那一句现在说的是「X 损耗最多 ¥N」，
▎而在覆盖不全时它的真实含义是「**已记门店的那部分里** X 最多」。

## ⚠️ 为什么它在 MOCK_REST 上验不到

📏 该租户门店损耗覆盖度 = **100.0%**（¥313,122.12 / ¥313,122.12）
⇒ prod 验收对这个风险**完全沉默**。所以这份用例**必须**自己构造覆盖不全的桩。

⚠️ 桩的形状是真实上游会产生的（形态 B‴）：
`_store_breakdown_block` 的 docstring 就记着 2% 那次，成因写得很清楚。

## ⛔ 阈值只此一处

`_store_breakdown_block` 里原来内联着 `all_total * 0.995`。
本 PR 抽成 `_STORE_COVERAGE_COMPLETE_RATIO`，两处读它 ——
⛔ 不新写第二份（与「极差算了两遍」同一形态，那次是被 AST 闸抓出来的）。
"""
from __future__ import annotations

import ast
import inspect

import pytest

from smartbi.gold.restaurant.restaurant_ops_router import (
    _STORE_COVERAGE_COMPLETE_RATIO,
    _store_breakdown_block,
    _store_lead_sentence,
)

#: 📏 取自 `_store_breakdown_block` docstring 记的那次真实事故（覆盖 2%）。
_ROWS_PARTIAL = [
    {"store_name": "模拟·长宁龙之梦店", "cost": 4000.0},
    {"store_name": "模拟·陆家嘴正大店", "cost": 2828.07},
]
_ALL_TOTAL_PARTIAL = 317441.84          # 抬头总额；门店表只占 2%

_ROWS_FULL = [
    {"store_name": "模拟·长宁龙之梦店", "cost": 31436.37},
    {"store_name": "模拟·打浦桥日月光店", "cost": 28985.44},
]
_ALL_TOTAL_FULL = 60421.81              # = 两行之和，覆盖 100%


def _lead(rows, all_total):
    return _store_lead_sentence(rows, noun="损耗", all_total=all_total)


# ── 承重 ────────────────────────────────────────────────────────────────

def test_partial_coverage_is_declared_in_the_lead_itself():
    """🔴 覆盖不全时，**首段**就要说清楚 —— ⛔ 不能只在表格下面说。"""
    text = _lead(_ROWS_PARTIAL, _ALL_TOTAL_PARTIAL)
    assert "已记门店" in text or "分到店" in text, (
        "首段没有限定语 —— 老板只读这一句会把它当成全店结论\n" + text
    )


def test_the_lead_still_names_the_top_store():
    """限定语 ⛔ 不许把点名挤掉 —— 那是 #2805 的收益。"""
    text = _lead(_ROWS_PARTIAL, _ALL_TOTAL_PARTIAL)
    assert "模拟·长宁龙之梦店" in text, text


# ── 阴性对照：⛔ 不许变成一条无条件的噪音 ────────────────────────────────

def test_full_coverage_says_nothing_extra():
    """覆盖完整时**不许**出现限定语 —— 否则它就是一条会误导的提示。

    ▎反目标里最重的一条：一条误发的提示，烧掉的是「这东西说的话能信」。
    """
    text = _lead(_ROWS_FULL, _ALL_TOTAL_FULL)
    assert "已记门店" not in text and "分到店" not in text, (
        "覆盖完整却加了限定语 —— 一条无中生有的提示\n" + text
    )


def test_unknown_total_says_nothing_extra():
    """阴性对照：拿不到总额时 ⛔ 不许猜「可能不全」。"""
    for total in (None, 0, 0.0):
        text = _lead(_ROWS_PARTIAL, total)
        assert "已记门店" not in text and "分到店" not in text, (
            f"all_total={total!r} 时凭空加了限定语\n{text}"
        )


def test_no_store_rows_is_unchanged():
    """阴性对照：一家都没有时，仍然是那句「没有记门店」的缺口说明。"""
    text = _lead([], _ALL_TOTAL_PARTIAL)
    assert "没有记门店" in text or "分不到店" in text, text


# ── 同源：阈值只此一处 ───────────────────────────────────────────────────

def test_the_coverage_threshold_has_exactly_one_home():
    """⛔ 首段和表格下面那句必须读**同一个**阈值。

    ⚠️ 与「极差被算了两遍」同一形态 —— 那次是 AST 闸抓出来的，
    而且第三处是我自己在同一个 PR 里写的。
    """
    from smartbi.gold.restaurant import restaurant_ops_router as rr

    src = inspect.getsource(rr)
    tree = ast.parse(src)
    literals = [
        node.lineno
        for node in ast.walk(tree)
        if isinstance(node, ast.Constant)
        and isinstance(node.value, float)
        and node.value == _STORE_COVERAGE_COMPLETE_RATIO
    ]
    # 常量定义那一行自己算一次，⛔ 其它地方一次都不许有
    assert len(literals) == 1, (
        f"阈值 {_STORE_COVERAGE_COMPLETE_RATIO} 出现在 {literals} 行 —— "
        f"期望只有常量定义那一处"
    )

    for fn in (_store_lead_sentence, _store_breakdown_block):
        fsrc = inspect.getsource(fn)
        assert "_STORE_COVERAGE_COMPLETE_RATIO" in fsrc, (
            f"{fn.__name__} 没有读那个常量"
        )


class TestItIsActuallyWiredIntoTheResolver:
    """🔴 上面全是直接调 helper —— **把调用点的 `all_total` 去掉，它们一条都不红。**

    变异 C4（调用点传 `all_total=None`）实测**全绿** ⇒ 那是「测了 helper，
    没测接线」，本仓反复记过的形态（我在 #2805 就被抓过一次）。
    ⇒ 这一组走**真实 resolver**，只桩掉数据库。
    """

    @staticmethod
    def _pool(store_rows, total_cost):
        class _Conn:
            async def execute(self, sql, *a):
                return "SET"

            async def fetch(self, sql, *a):
                if "wastage_cost_by_store" in sql:
                    return store_rows
                if "JOIN dim_ingredient" in sql:
                    return [{"name": "大米", "category": "米面", "unit": "kg",
                             "qty": 100.0, "cost": 500.0}]
                if "wastage_cost_by_type" in sql:
                    return [{"type": "SPOILED", "cost": 300.0}]
                return []

            async def fetchrow(self, sql, *a):
                if "agg_restaurant_daily_totals" in sql:
                    return {"total_qty": 100.0, "total_cost": total_cost,
                            "total_count": 30}
                return None

            async def fetchval(self, sql, *a):
                return None

        class _Pool:
            def acquire(self):
                class _Ctx:
                    async def __aenter__(self_inner):
                        return _Conn()

                    async def __aexit__(self_inner, *exc):
                        return False

                return _Ctx()

        return _Pool()

    async def _answer(self, store_rows, total_cost):
        from smartbi.gold.restaurant.restaurant_ops_router import (
            resolve_wastage_top,
        )

        ans = await resolve_wastage_top(
            self._pool(store_rows, total_cost), "MOCK_REST", days=30,
            query="哪家店最多", dimensions=("store",))
        return ans.answer_text

    @pytest.mark.asyncio
    async def test_resolver_declares_partial_coverage_in_the_lead(self):
        """🔴 承重接线：真实 resolver 上，覆盖不全时首段带限定语。"""
        text = await self._answer(
            [{"store_name": "A店", "cost": 4000.0},
             {"store_name": "B店", "cost": 2828.07}],
            total_cost=317441.84)
        lead = text.split("各门店损耗金额")[0]
        assert "已记门店" in lead, (
            "首段没带限定语 —— 调用点多半没把 all_total 传下去\n" + lead
        )

    @pytest.mark.asyncio
    async def test_resolver_says_nothing_extra_when_coverage_is_complete(self):
        """阴性对照：真实 resolver 上，覆盖完整时**不许**出现限定语。"""
        text = await self._answer(
            [{"store_name": "A店", "cost": 31436.37},
             {"store_name": "B店", "cost": 28985.44}],
            total_cost=31436.37 + 28985.44)
        lead = text.split("各门店损耗金额")[0]
        assert "已记门店" not in lead, lead


def test_the_table_block_still_declares_coverage():
    """阴性对照：表格下面那句行为逐字不变。"""
    block = _store_breakdown_block(
        _ROWS_PARTIAL, _ALL_TOTAL_PARTIAL,
        title="各门店损耗金额", amount_header="损耗金额", noun="损耗")
    assert "只覆盖了" in block, block
    assert "别拿这张表当全部损耗看" in block, block
