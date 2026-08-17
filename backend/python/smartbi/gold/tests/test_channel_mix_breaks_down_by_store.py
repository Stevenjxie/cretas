"""渠道构成按门店再拆一层 —— 「哪家店外卖占比最高」此前撞维度闸被拒。

## 为什么做这一条（2026-08-17）

16 句验收里有 7 句是「按门店看 X」被拒。逐张表量下来，这一片其实分两类：

| | 长相 | 能不能做 |
|---|---|---|
| 损耗 / 领用 / 盘点 | **源表就没有门店列**（有的是 `section_code`/`stall_code`） | ⛔ 做不到，且老板**无法通过行为补**（表单里没这个字段） |
| **渠道构成** | `fact_pos_transaction` **有 `store_id`**，只是没进 GROUP BY | ✅ 数据一直都在 |

prod 实测（逐租户 `set_config`）：

```
RES_3101_009  61,933 单  store_id 空值 0  30 家店  order_type 空值 0  3 种渠道
DEMO_REST     55,376 单  store_id 空值 0  27 家店  order_type 空值 0  3 种渠道
```

⇒ 补的依据是**它真能出**（`_RESOLVER_DIMENSIONS` 自己的纪律），
与 `SALES_SUMMARY`「per-store Top-N table in addition to the chain aggregate」
同一个形状。

## ⚠️ 归一只有一份

门店表的 `order_type` 走**同一个** `_normalize_order_type`。
在 SQL 里再写一份归一 = 形态 D，漂的表现是「总表和门店表对不上」——
不报错，只是两个数。
"""
from datetime import date as _date

import pytest

from smartbi.gold.restaurant import restaurant_ops_router as router
from smartbi.gold.restaurant.restaurant_intent_service import (
    _RESOLVER_DIMENSIONS,
    _supported_dimensions,
)

CHANNEL = ("RESTAURANT_OPS_CHANNEL_MIX",)

# 两家店、中英文混写的 order_type —— 归一必须把它们合并，⛔ 不是各算各的。
# ⚠️ 形状取自真实落库：本函数最初照 DEMO_REST 写中文值，而 MOCK_REST 落
#    dine_in / takeaway（见 resolve_channel_mix 的 docstring）。
# ⚠️ `window_start`/`window_end` 必须是**真日期**: 真 SQL 是 `MIN(t.date)`/
#    `MAX(t.date)`, 非空分组上永远给日期, ⛔ 永远不给 None。
#    第一版桩喂了 None, 产品代码在 `min(...)` 上当场 TypeError ——
#    那不是缺陷, 是我造了一个真实上游产不出的形状(形态 B‴)。
_W0, _W1 = _date(2026, 7, 19), _date(2026, 8, 17)
_CHANNEL_ROWS = [
    {"order_type": "堂食", "bills": 60, "revenue": 6000.0,
     "window_start": _W0, "window_end": _W1},
    {"order_type": "takeaway", "bills": 40, "revenue": 4000.0,
     "window_start": _W0, "window_end": _W1},
]
_STORE_ROWS = [
    {"store_name": "北京三里屯店", "order_type": "堂食", "bills": 50, "revenue": 5000.0},
    {"store_name": "北京三里屯店", "order_type": "takeaway", "bills": 10, "revenue": 1000.0},
    {"store_name": "上海世茂店", "order_type": "dine_in", "bills": 10, "revenue": 1000.0},
    {"store_name": "上海世茂店", "order_type": "外卖", "bills": 30, "revenue": 3000.0},
]


class _FakeConn:
    def __init__(self):
        self._calls = 0

    async def execute(self, *a, **kw):
        return "SET"

    async def fetch(self, *a, **kw):
        self._calls += 1
        return _CHANNEL_ROWS if self._calls == 1 else _STORE_ROWS


class _FakePool:
    def __init__(self):
        self.conn = _FakeConn()

    def acquire(self):
        conn = self.conn

        class _Ctx:
            async def __aenter__(self):
                return conn

            async def __aexit__(self, *exc):
                return False

        return _Ctx()


async def _answer(role):
    a = await router.resolve_channel_mix(
        _FakePool(), "DEMO_REST", days=30, role=role, query="哪家店外卖占比最高")
    return str(getattr(a, "answer_text", None) or a)


@pytest.mark.asyncio
class TestPerStoreChannelTable:
    async def test_each_store_appears(self):
        text = await _answer("restaurant_manager")
        assert "北京三里屯店" in text, text
        assert "上海世茂店" in text, text

    async def test_takeaway_share_is_computed_per_store(self):
        """上海世茂 30/40 = 75.0%，三里屯 10/60 = 16.7%。"""
        text = await _answer("restaurant_manager")
        assert "75.0%" in text, f"上海世茂店的外卖占比算错了：\n{text}"
        assert "16.7%" in text, f"北京三里屯店的外卖占比算错了：\n{text}"

    async def test_sorted_by_takeaway_share_descending(self):
        """老板问的是「哪家店外卖占比最高」—— 第一行就得是答案。"""
        text = await _answer("restaurant_manager")
        assert text.index("上海世茂店") < text.index("北京三里屯店"), (
            f"没有按外卖占比排序，老板得自己找：\n{text}"
        )

    async def test_english_and_chinese_order_types_merge(self):
        """`dine_in`/`takeaway` 与「堂食」/「外卖」必须并进同一个桶。

        ⛔ 若门店表在 SQL 里自己归一了一份，这条会在两份漂开时红。
        """
        text = await _answer("restaurant_manager")
        for raw in ("dine_in", "takeaway"):
            assert raw not in text, f"裸英文码漏给了老板: {raw!r}\n{text}"


@pytest.mark.asyncio
class TestMoneyStaysBehindTheRoleGate:
    async def test_price_blind_role_sees_no_money_in_the_store_table(self):
        """阴性对照：金额是价格权限数据，门店表与总表同一条规则。"""
        text = await _answer("operator")
        assert "北京三里屯店" in text, "非价格角色连门店表都没有了"
        assert "¥" not in text, f"非价格角色看到了金额：\n{text}"

    async def test_price_role_does_see_money(self):
        """阳性对照：有权限时金额**必须**出来 —— 否则上一条可能只是列被删了。"""
        text = await _answer("restaurant_manager")
        assert "¥" in text, f"有价格权限却看不到金额：\n{text}"


class TestTheCapabilityIsDeclared:
    def test_store_is_declared_for_channel_mix(self):
        assert "store" in _RESOLVER_DIMENSIONS[CHANNEL[0]], (
            "渠道构成现在真出各门店的表了，能力表却没登记 —— "
            "「哪家店外卖占比最高」会被维度闸拒答"
        )

    def test_supported_dimensions_reads_the_same_table(self):
        assert _supported_dimensions(CHANNEL) >= {"store", "channel"}
