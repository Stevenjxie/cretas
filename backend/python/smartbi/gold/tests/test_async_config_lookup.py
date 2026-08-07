"""`business_config_overrides` 异步读取：优先级、fail-open、以及**口径不许分叉**。

背景：优先级链第 1 层缺的不是存储 —— 表和 4 层优先级的同步 resolver 早就有了。
缺的是**异步侧的承载**：同步 resolver 要 SQLAlchemy Session，问答链路只有 asyncpg。
`health_check_metrics` 因此已经把工厂级 SQL 抄过一遍（它自己的注释写明了原因）。

⇒ 本模块存在的意义是**不出现第三处**。最后一条测试就是钉这个的。
"""
import re
from pathlib import Path

import pytest

from smartbi.shared import async_config_lookup as cfg


class _Row(dict):
    pass


class _Conn:
    def __init__(self, owner):
        self.owner = owner

    def transaction(self):
        class _Ctx:
            async def __aenter__(_s):
                return None

            async def __aexit__(_s, *_a):
                return False

        return _Ctx()

    async def execute(self, sql, *args):
        assert "set_config('app.factory_id'" in sql, "必须先设 RLS GUC"
        self.owner.guc = args[0]
        return "OK"

    async def fetchrow(self, sql, *args):
        self.owner.queried.append(args[3])  # store_id 参数
        return self.owner.rows.get(args[3])


class _Pool:
    def __init__(self, rows):
        self.rows = rows
        self.queried = []
        self.guc = None

    def acquire(self):
        conn = _Conn(self)

        class _Ctx:
            async def __aenter__(_s):
                return conn

            async def __aexit__(_s, *_a):
                return False

        return _Ctx()


@pytest.mark.asyncio
async def test_store_level_wins_over_factory_level():
    pool = _Pool({"S1": _Row(config_value="门店值"), None: _Row(config_value="工厂值")})

    got = await cfg.resolve_config(
        pool, "F1", "restaurant.output_preference", store_id="S1", default="兜底"
    )

    assert got == "门店值"
    assert pool.queried == ["S1"], "门店级命中后不该再查工厂级"


@pytest.mark.asyncio
async def test_falls_back_to_factory_then_default():
    pool = _Pool({None: _Row(config_value="工厂值")})
    assert await cfg.resolve_config(
        pool, "F1", "restaurant.x", store_id="S1", default="兜底"
    ) == "工厂值"
    assert pool.queried == ["S1", None], "门店级没有 -> 必须再落工厂级"

    empty = _Pool({})
    assert await cfg.resolve_config(empty, "F1", "restaurant.x", default="兜底") == "兜底"


@pytest.mark.asyncio
async def test_rls_guc_is_set_to_the_asking_tenant():
    pool = _Pool({None: _Row(config_value="v")})
    await cfg.resolve_config(pool, "F42", "restaurant.x")
    assert pool.guc == "F42", "不设 GUC 会因 RLS 假性 0 行（本仓踩过多次）"


@pytest.mark.asyncio
async def test_db_failure_fails_open():
    """⛔ 配置服务挂了不该让问答挂。"""

    class _Boom:
        def acquire(self):
            raise RuntimeError("db down")

    assert await cfg.resolve_config(_Boom(), "F1", "restaurant.x", default="兜底") == "兜底"


@pytest.mark.asyncio
async def test_cross_domain_key_is_a_coding_error():
    """🔴 key 前缀不对 -> 直接抛，不是悄悄返回默认值。

    这是**编码错误**（写错了 key），应该在开发时炸出来。
    悄悄兜底会让「取错了配置」变成一个永远没人发现的静默 bug。
    """
    pool = _Pool({})
    with pytest.raises(ValueError, match="前缀"):
        await cfg.resolve_config(pool, "F1", "factory.something", domain="restaurant")
    with pytest.raises(ValueError, match="domain"):
        await cfg.resolve_config(pool, "F1", "sales.x", domain="sales")


def test_the_three_implementations_share_one_caliber():
    """⛔ 同一张表的查找条件已经有三处，**必须逐条同口径**。

    三处：
      1. `shared/dynamic_config_resolver.py`（同步，SQLAlchemy）
      2. `services/restaurant/health_check_metrics.py`（异步，抄的工厂级那段）
      3. 本模块（异步，唯一新承载）

    这条闸不追求把三处合一（合并体检指标那份是另一件事），它保证的是：
    **生效区间与软删条件不许分叉** —— 那才是会导致「同一个配置在两个页面
    读出不同值」的地方。
    """
    root = Path(cfg.__file__).parent.parent
    sources = {
        "async_config_lookup": Path(cfg.__file__).read_text(encoding="utf-8"),
        "health_check_metrics": (
            root / "services" / "restaurant" / "health_check_metrics.py"
        ).read_text(encoding="utf-8"),
        "dynamic_config_resolver": (
            root / "shared" / "dynamic_config_resolver.py"
        ).read_text(encoding="utf-8"),
    }

    for name, src in sources.items():
        assert "deleted_at IS NULL" in src or "deleted_at.is_(None)" in src, (
            f"{name} 没有排除软删行 —— 会读到已删除的配置"
        )
        has_window = (
            re.search(r"effective_from\s*(IS NULL|<=)", src)
            and re.search(r"effective_to\s*(IS NULL|>=)", src)
        )
        assert has_window, f"{name} 缺生效区间条件 —— 会读到未生效/已过期的配置"
