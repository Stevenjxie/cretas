"""Gold 增量刷新: 分档判定 / 环境变量解析 / 每轮实际打出去的 SQL。

不碰 DB / 不碰网络 —— asyncpg pool 用假实现注入, 断言的是
「便宜的每轮跑、贵的按周期跑、租户上下文进出干净」。

⚠️ 覆盖边界(别高估): 这里证明不了 RLS 真的生效 —— 那要真 PG。
这里能证明的是 `set_factory_id` 在 materializer 取连接**之前**已经设好,
即连接池 setup 回调能读到正确租户(RLS 依赖的正是这一点)。
"""
import datetime

import pytest

from smartbi.gold.live_refresh import (
    DEFAULT_PRODUCT_INTERVAL_SECONDS,
    SlowCadence,
    daily_refresh_range,
    gold_refresh_enabled,
    parse_interval_seconds,
    product_interval_from_env,
    product_refresh_month,
    refresh_gold_incremental,
)


# ── 假 asyncpg pool ────────────────────────────────────────────
class _FakeConn:
    def __init__(self, log, factory_probe):
        self._log = log
        self._factory_probe = factory_probe

    async def execute(self, sql, *args):
        # 记录「哪张表 + 参数」以及执行当刻的租户 ContextVar。
        from smartbi.tenant_ctx import get_factory_id
        target = "?"
        for name in ("agg_daily", "agg_product", "agg_channel", "agg_discount"):
            if f"INTO {name}" in sql:
                target = name
                break
        self._log.append((target, args))
        self._factory_probe.append(get_factory_id())
        return "INSERT 0 3"


class _Acquire:
    def __init__(self, conn):
        self._conn = conn

    async def __aenter__(self):
        return self._conn

    async def __aexit__(self, *exc):
        return False


class _FakePool:
    def __init__(self):
        self.calls = []
        self.factory_at_execute = []

    def acquire(self):
        return _Acquire(_FakeConn(self.calls, self.factory_at_execute))


# ── SlowCadence ────────────────────────────────────────────────
def test_慢档首次一定该跑():
    # 进程刚起来时 Gold 可能陈旧几小时, 不该再等一个周期。
    assert SlowCadence(600).due(now=1000.0) is True


def test_慢档记账后未到周期不跑_到点又跑():
    c = SlowCadence(600)
    c.mark(1000.0)
    assert c.due(1000.0) is False
    assert c.due(1599.9) is False
    assert c.due(1600.0) is True      # 边界: >= interval 即到点
    assert c.due(1600.1) is True


def test_慢档周期必须为正():
    with pytest.raises(ValueError):
        SlowCadence(0)
    with pytest.raises(ValueError):
        SlowCadence(-1)


# ── 环境变量解析 ───────────────────────────────────────────────
@pytest.mark.parametrize("raw", ["abc", "", None, "0", "-5", "600.5"])
def test_非法周期回退默认值(raw):
    assert parse_interval_seconds(raw, 600, var_name="X") == 600


def test_合法周期原样采用():
    assert parse_interval_seconds("900", 600, var_name="X") == 900


def test_product_interval_默认600_可覆盖():
    assert product_interval_from_env({}) == DEFAULT_PRODUCT_INTERVAL_SECONDS == 600
    assert product_interval_from_env(
        {"PLATFORM_GOLD_PRODUCT_INTERVAL_SECONDS": "1800"}) == 1800
    # 非法值不该让常驻循环崩, 回退默认。
    assert product_interval_from_env(
        {"PLATFORM_GOLD_PRODUCT_INTERVAL_SECONDS": "oops"}) == 600


def test_gold开关默认开_显式关才关():
    assert gold_refresh_enabled({}) is True
    assert gold_refresh_enabled({"PLATFORM_GOLD_REFRESH_ENABLED": "TRUE"}) is True
    assert gold_refresh_enabled({"PLATFORM_GOLD_REFRESH_ENABLED": "0"}) is False
    assert gold_refresh_enabled({"PLATFORM_GOLD_REFRESH_ENABLED": "false"}) is False


# ── 区间计算 ───────────────────────────────────────────────────
def test_便宜档只刷当天():
    d = datetime.date(2026, 7, 29)
    assert daily_refresh_range(d) == (d, d)


def test_agg_product_归一到当月一号():
    assert product_refresh_month(datetime.date(2026, 7, 29)) == datetime.date(2026, 7, 1)


# ── refresh_gold_incremental ───────────────────────────────────
@pytest.mark.asyncio
async def test_首轮_便宜档限当天_贵档跑整月():
    pool = _FakePool()
    cadence = SlowCadence(600)
    today = datetime.date(2026, 7, 29)

    out = await refresh_gold_incremental(
        pool, "MOCK_REST", cadence=cadence, today=today, now=1000.0)

    targets = [t for t, _ in pool.calls]
    assert targets == ["agg_daily", "agg_channel", "agg_product"]
    # 便宜档的日期区间必须是 (今天, 今天) —— 放宽就等于每轮全表重算。
    assert pool.calls[0][1] == ("MOCK_REST", today, today)
    assert pool.calls[1][1] == ("MOCK_REST", today, today)
    # 贵档传的是月首 (grain 是月, 一次重算整月)。
    assert pool.calls[2][1] == ("MOCK_REST", datetime.date(2026, 7, 1))
    assert out["agg_daily"] == 3 and out["agg_channel"] == 3 and out["agg_product"] == 3


@pytest.mark.asyncio
async def test_未到周期时只跑便宜档():
    pool = _FakePool()
    cadence = SlowCadence(600)
    today = datetime.date(2026, 7, 29)

    await refresh_gold_incremental(pool, "MOCK_REST", cadence=cadence,
                                   today=today, now=1000.0)
    pool.calls.clear()
    # 60s 后的下一轮: agg_product 不该再跑 (跑了就是每 60s 重算整月)。
    out = await refresh_gold_incremental(pool, "MOCK_REST", cadence=cadence,
                                         today=today, now=1060.0)
    assert [t for t, _ in pool.calls] == ["agg_daily", "agg_channel"]
    assert out["agg_product"] == "skipped"

    # 到点后恢复。
    pool.calls.clear()
    await refresh_gold_incremental(pool, "MOCK_REST", cadence=cadence,
                                   today=today, now=1600.0)
    assert [t for t, _ in pool.calls] == ["agg_daily", "agg_channel", "agg_product"]


@pytest.mark.asyncio
async def test_贵档失败也消耗一个周期(monkeypatch):
    """失败就 60s 重试一次, 正是慢档要避免的雪崩 —— 记账必须在开跑之前。"""
    from smartbi.gold import live_refresh as mod

    class _Boom(mod.GoldMaterializer):
        async def materialize_product(self, month):
            raise RuntimeError("planner 又选了 nested loop")

    monkeypatch.setattr(mod, "GoldMaterializer", _Boom)

    pool = _FakePool()
    cadence = SlowCadence(600)
    with pytest.raises(RuntimeError):
        await refresh_gold_incremental(pool, "MOCK_REST", cadence=cadence,
                                       today=datetime.date(2026, 7, 29), now=1000.0)

    assert cadence.due(1060.0) is False      # 已消耗周期, 下轮不重试
    assert cadence.due(1600.0) is True


@pytest.mark.asyncio
async def test_租户上下文_执行时已设_返回后还原():
    """RLS 靠连接池 setup 回调读 ContextVar; 设晚了 = 0 行, 不还原 = 租户粘住。"""
    from smartbi.tenant_ctx import get_factory_id

    pool = _FakePool()
    before = get_factory_id()
    await refresh_gold_incremental(pool, "MOCK_REST", cadence=SlowCadence(600),
                                   today=datetime.date(2026, 7, 29), now=1000.0)
    assert pool.factory_at_execute == ["MOCK_REST"] * 3
    assert get_factory_id() == before
