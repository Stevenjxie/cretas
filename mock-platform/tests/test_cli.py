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
