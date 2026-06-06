"""#56 价值可视化回馈回路 — value_notifier 单元测试 (fake pool + mock Java call)。

覆盖 D2 (仅 RESTAURANT_MANAGER + FACTORY_SUPER_ADMIN/FACTORY_ADMIN) + 幂等防重:
  - 已通知过的 (factory, period, role) → 跳过, 不重复调 Java。
  - 通知成功 → 写防重日志。
  - 通知失败 → 不写日志 (下次可重试)。
  - 低权限角色 (非金额角色) 文案用 count, 不含金额。
"""
from __future__ import annotations

import asyncio

from smartbi.services.restaurant import value_notifier as vn


class _FakeRecord(dict):
    def keys(self):
        return super().keys()


class _NotifConn:
    def __init__(self, already_notified: set[tuple] | None = None):
        self.already = already_notified or set()
        self.inserted: list[tuple] = []

    async def __aenter__(self):
        return self

    async def __aexit__(self, *a):
        return False

    async def execute(self, sql, *args, **kwargs):
        if "INSERT INTO restaurant_value_notifications_log" in sql:
            key = (args[0], args[1], args[2])  # factory, period, role
            self.inserted.append(key)
        return None

    async def fetchrow(self, sql, *args, **kwargs):
        key = (args[0], args[1], args[2])
        if key in self.already:
            return _FakeRecord({"id": 1})
        return None


class _FakePool:
    def __init__(self, conn: _NotifConn):
        self._conn = conn

    def acquire(self):
        return self._conn


def _summary(total_month=50849.0, critical=1):
    return {
        "periodMonth": "2026-02",
        "month": {"total": total_month},
        "annual": {"total": 472688.0},
        "criticalCount": critical,
        "rxActionCount": 2,
        "diagnosisCount": 3,
    }


# ── 角色路由 D2 ──────────────────────────────────────────


def test_notify_roles_constant_is_d2():
    assert "restaurant_manager" in vn.NOTIFY_ROLES
    assert "factory_super_admin" in vn.NOTIFY_ROLES
    assert "factory_admin" in vn.NOTIFY_ROLES
    # restaurant_operations NOT pushed (web only)
    assert "restaurant_operations" not in vn.NOTIFY_ROLES


# ── 幂等防重 ─────────────────────────────────────────────


def test_skips_already_notified_role():
    """已通知过的 (factory, period, role) → 不重复调 Java。"""
    conn = _NotifConn(already_notified={("F-DENG", "2026-02", "restaurant_manager")})
    pool = _FakePool(conn)
    calls: list[dict] = []

    async def fake_java_notify(**kwargs):
        calls.append(kwargs)
        return True

    result = asyncio.run(vn.maybe_notify_monthly(
        pool, "F-DENG", "2026-02", _summary(),
        java_notify=fake_java_notify, roles=["restaurant_manager"],
    ))
    assert calls == []  # already notified → no Java call
    assert result["notified"] == []
    assert "restaurant_manager" in result["skipped"]


def test_notifies_new_role_and_writes_log():
    conn = _NotifConn()
    pool = _FakePool(conn)
    calls: list[dict] = []

    async def fake_java_notify(**kwargs):
        calls.append(kwargs)
        return True

    result = asyncio.run(vn.maybe_notify_monthly(
        pool, "F-DENG", "2026-02", _summary(),
        java_notify=fake_java_notify, roles=["restaurant_manager"],
    ))
    assert len(calls) == 1
    assert calls[0]["role"] == "restaurant_manager"
    assert result["notified"] == ["restaurant_manager"]
    assert ("F-DENG", "2026-02", "restaurant_manager") in conn.inserted


def test_java_failure_does_not_write_log():
    """Java 通知失败 → 不写防重日志 (下次重试)。"""
    conn = _NotifConn()
    pool = _FakePool(conn)

    async def fake_java_notify(**kwargs):
        return False  # Java call failed

    result = asyncio.run(vn.maybe_notify_monthly(
        pool, "F-DENG", "2026-02", _summary(),
        java_notify=fake_java_notify, roles=["restaurant_manager"],
    ))
    assert conn.inserted == []  # no log written
    assert "restaurant_manager" in result["failed"]


# ── 文案: 金额 vs count (R2) ──────────────────────────────


def test_amount_role_message_has_amount():
    msg = vn.build_message("restaurant_manager", _summary(total_month=50849.0))
    assert "50,849" in msg or "50849" in msg
    assert "2026-02" in msg


def test_count_role_message_no_amount():
    """低权限 (非金额) 角色: 文案只给 count, 不含金额 (R2 + RBAC)。"""
    msg = vn.build_message("kiosk_lead", _summary(total_month=50849.0, critical=3))
    assert "50,849" not in msg and "50849" not in msg
    assert "3" in msg  # critical count present


def test_message_amount_role_set():
    # restaurant_manager 在金额白名单 (PRICE_VIEW_ROLES) → 看金额
    assert vn._role_sees_amount("restaurant_manager") is True
    assert vn._role_sees_amount("factory_super_admin") is True
    # 非金额角色 → count only
    assert vn._role_sees_amount("kiosk_lead") is False


def test_no_value_no_notify():
    """快照无任何金额且无 critical → 不推送 (没价值可报, 避免噪音)。"""
    conn = _NotifConn()
    pool = _FakePool(conn)
    calls: list[dict] = []

    async def fake_java_notify(**kwargs):
        calls.append(kwargs)
        return True

    empty_summary = {
        "periodMonth": "2026-02",
        "month": {"total": None}, "annual": {"total": None},
        "criticalCount": 0, "rxActionCount": 0, "diagnosisCount": 0,
    }
    result = asyncio.run(vn.maybe_notify_monthly(
        pool, "F-X", "2026-02", empty_summary,
        java_notify=fake_java_notify, roles=["restaurant_manager"],
    ))
    assert calls == []
    assert result.get("reason") == "no_value"
