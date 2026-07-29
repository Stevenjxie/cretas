"""CLI + healthz 的回归测试。

覆盖两条 review 发现的真问题：
1. 常驻生成器被 GC / 异常退出后, healthz 必须反映出来, 不能继续报 ok
   (否则部署验收会在一个已经死掉的生成器上通过)。
2. `--days 0` / 负数不能静默空跑, 必须在入口报错退出。
"""
from __future__ import annotations

import subprocess
import sys

from fastapi.testclient import TestClient

from mock_platform.api.app import create_app


def test_healthz未挂载生成器时返回not_armed():
    """backfill / 裸测试场景没有常驻生成器，不该被判定异常。"""
    app = create_app()
    with TestClient(app) as client:
        body = client.get("/healthz").json()
        assert body["status"] == "ok"
        assert body["generator"] == "not_armed"


def test_healthz反映生成器死活():
    """生成器被 GC 或异常退出后, healthz 不能继续报 ok ——
    否则部署验收会在一个已经死掉的生成器上通过。
    """
    class _DoneTask:
        """站住 healthz 检查的接口 (`task.done()`)，不依赖真实 event loop 细节。"""

        def done(self) -> bool:
            return True

    app = create_app()
    with TestClient(app) as client:
        app.state.generator_task = _DoneTask()
        body = client.get("/healthz").json()
        assert body["status"] == "degraded"
        assert body["generator"] == "stopped"


def test_backfill拒绝非正days():
    r = subprocess.run(
        [sys.executable, "-m", "mock_platform.cli", "backfill", "--days", "0"],
        capture_output=True, text=True,
    )
    assert r.returncode != 0, "--days 0 必须报错退出, 不能静默空跑"


def test_backfill拒绝负days():
    r = subprocess.run(
        [sys.executable, "-m", "mock_platform.cli", "backfill", "--days", "-3"],
        capture_output=True, text=True,
    )
    assert r.returncode != 0, "--days 负数必须报错退出, 不能静默空跑"


def test_backfill_ops_只补后厨不新建订单(tmp_path, monkeypatch):
    """🔴 线上那个库已有 6 万单但零后厨数据。重跑 `backfill` 会把订单**翻倍**
    (订单 INSERT 没有 ON CONFLICT), 把刚修好的营收数字重新搞脏 ——
    所以必须有一条只补后厨的路(审查 C2)。"""
    import argparse
    import random

    from mock_platform.config import get_settings
    from mock_platform.db import connect
    from mock_platform.world.generator import generate_orders
    from mock_platform.world.seed import seed_world
    from mock_platform.cli import _cmd_backfill_ops

    db = str(tmp_path / "ops_only.db")
    monkeypatch.setenv("MOCK_DB_PATH", db)
    monkeypatch.setenv("MOCK_KERUYUN_APP_KEY", "k")
    monkeypatch.setenv("MOCK_KERUYUN_APP_SECRET", "s")
    monkeypatch.setenv("MOCK_CALLBACK_SECRET", "c")
    get_settings.cache_clear()
    conn = connect(db)
    seed_world(conn, store_count=2)
    generate_orders(conn, store_id=1, biz_date="2026-07-20", minute_of_day=12 * 60,
                    count=25, rng=random.Random(3))
    orders_before = conn.execute('SELECT COUNT(*) c FROM "order"').fetchone()["c"]
    conn.close()

    _cmd_backfill_ops(argparse.Namespace())
    get_settings.cache_clear()

    conn = connect(db)
    assert conn.execute('SELECT COUNT(*) c FROM "order"').fetchone()["c"] == orders_before, \
        "只补后厨, 一条订单都不该新建"
    assert conn.execute("SELECT COUNT(*) c FROM requisition").fetchone()["c"] > 0
    conn.close()


def test_backfill_ops_没有订单就明确报错(tmp_path, monkeypatch):
    """禁降级: 没订单推不出后厨, 要说清楚而不是报「成功 0 条」。"""
    import argparse

    import pytest as _pytest

    from mock_platform.config import get_settings
    from mock_platform.cli import _cmd_backfill_ops

    monkeypatch.setenv("MOCK_DB_PATH", str(tmp_path / "empty.db"))
    monkeypatch.setenv("MOCK_KERUYUN_APP_KEY", "k")
    monkeypatch.setenv("MOCK_KERUYUN_APP_SECRET", "s")
    monkeypatch.setenv("MOCK_CALLBACK_SECRET", "c")
    get_settings.cache_clear()
    with _pytest.raises(SystemExit, match="没有订单"):
        _cmd_backfill_ops(argparse.Namespace())
    get_settings.cache_clear()


def test_常驻循环真的会派生后厨():
    """🔴 审查 C2: 之前 `_generate_forever` 只调 generate_orders, 后厨代码
    写好了但常驻进程从不执行 —— 部署上去后厨永远 0 行, 整条链的前提落空。
    这是**读代码**才发现的, 所有单测都是绿的。

    诚实说明: 这是结构性断言, 只能挡住"调用被删掉", 挡不住"调用了但参数错"。
    真正的端到端证据是 test_ops_contract_with_mock 那条跨两侧的拉取测试。
    """
    import inspect

    from mock_platform.cli import _generate_forever

    src = inspect.getsource(_generate_forever)
    assert "generate_daily_ops" in src, (
        "常驻循环必须派生后厨, 否则线上后厨数据永远是 0"
    )
    assert src.index("generate_orders") < src.index("generate_daily_ops"), (
        "后厨按当天实际消耗推导, 必须排在订单生成之后"
    )


def test_非营业时段有心跳日志():
    """🔴 生成器只在 created>0 时打日志, 非营业时段整夜静默 —— 日志上
    分不清"没到点"和"循环死了"。healthz 的 generator:running 只证明协程
    没退出, 证明不了它还在转。

    诚实说明: 这是结构性断言(与 test_常驻循环真的会派生后厨 同类), 挡得住
    "心跳被删掉", 挡不住"心跳打了但循环卡在某个 await 上"。
    """
    import inspect

    from mock_platform.cli import _generate_forever

    src = inspect.getsource(_generate_forever)
    assert "循环存活" in src, "非营业时段必须有心跳日志"
    assert "minute % 30" in src, "心跳要低频, 否则刷屏"
