# -*- coding: utf-8 -*-
"""P1 读写分块 — 识别层目录过滤单测 (2026-07-23 spec §4.3)。

filter_rows_by_rw_mode 是 UX 层过滤 (执行期强制在 Java), 口径:
写意图 = sensitivity HIGH/CRITICAL 或 required_permission action 段为
write/read_write。mode=None 必须与 P1 之前逐字节一致 (零行为变化)。
"""
from __future__ import annotations

from ai.db import filter_rows_by_rw_mode, _is_write_row


def _row(code, sensitivity="LOW", perm=None):
    return {
        "intent_code": code,
        "sensitivity_level": sensitivity,
        "required_permission": perm,
    }


ROWS = [
    _row("SALES_QUERY"),                                        # 读
    _row("REPORT_ANALYZE", sensitivity="MEDIUM"),               # 读 (业务分析)
    _row("BATCH_DELETE", sensitivity="HIGH"),                   # 写 (sensitivity)
    _row("STOCK_IN", perm="inventory:write"),                   # 写 (权限码)
    _row("FIN_APPROVE", sensitivity="CRITICAL", perm="finance:read_write"),  # 写 (双标)
    _row("PRICE_VIEW", perm="finance:read"),                    # 读 (read 码不算写)
]


class TestIsWriteRow:
    def test_sensitivity_high_critical_are_write(self):
        assert _is_write_row(_row("x", sensitivity="HIGH"))
        assert _is_write_row(_row("x", sensitivity="CRITICAL"))
        assert not _is_write_row(_row("x", sensitivity="LOW"))
        assert not _is_write_row(_row("x", sensitivity="MEDIUM"))

    def test_permission_action_segment(self):
        assert _is_write_row(_row("x", perm="inventory:write"))
        assert _is_write_row(_row("x", perm="restaurant:read_write"))
        assert not _is_write_row(_row("x", perm="finance:read"))
        assert not _is_write_row(_row("x", perm=None))
        assert not _is_write_row(_row("x", perm="malformed_no_colon"))


class TestFilterByMode:
    def test_none_mode_is_byte_identical_passthrough(self):
        assert filter_rows_by_rw_mode(ROWS, None, None) is ROWS

    def test_read_mode_drops_all_write_rows(self):
        out = filter_rows_by_rw_mode(ROWS, "READ", None)
        codes = [r["intent_code"] for r in out]
        assert codes == ["SALES_QUERY", "REPORT_ANALYZE", "PRICE_VIEW"]

    def test_read_mode_case_insensitive(self):
        assert len(filter_rows_by_rw_mode(ROWS, "read", None)) == 3

    def test_operate_mode_without_permissions_no_filter(self):
        # userPermissions=None → Java 未传权限集, 不过滤 (执行期兜底)
        assert filter_rows_by_rw_mode(ROWS, "OPERATE", None) is ROWS

    def test_operate_mode_filters_unpermitted_write_intents(self):
        out = filter_rows_by_rw_mode(ROWS, "OPERATE", ["inventory:write"])
        codes = [r["intent_code"] for r in out]
        # STOCK_IN 保留 (有权限); FIN_APPROVE 剔除 (finance:read_write 不在集);
        # BATCH_DELETE 保留 — 写意图但无 required_permission 码 (兼容期未回填),
        # 不能凭空拒 (执行期 Java WriteGuard/RBAC 兜底)。
        assert codes == ["SALES_QUERY", "REPORT_ANALYZE", "BATCH_DELETE",
                         "STOCK_IN", "PRICE_VIEW"]

    def test_operate_mode_empty_permission_set_drops_coded_writes(self):
        out = filter_rows_by_rw_mode(ROWS, "OPERATE", [])
        codes = [r["intent_code"] for r in out]
        assert "STOCK_IN" not in codes
        assert "FIN_APPROVE" not in codes
        assert "BATCH_DELETE" in codes  # 无码写意图保留

    def test_fail_open_on_garbage_rows(self):
        garbage = [None, 42, {"intent_code": "ok"}]
        out = filter_rows_by_rw_mode(garbage, "READ", None)  # type: ignore[arg-type]
        assert out == garbage  # 异常 → fail-open 原样返回
