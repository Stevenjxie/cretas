"""Regression: `_resolve_tenant` must re-point the RLS context to the RESOLVED
tenant, not just rewrite the query's WHERE clause.

Bug (2026-07-11): DEMO_REST is aliased to RES_3101_009 for the query, but the
RLS GUC (app.factory_id) is applied by the pool setup callback from the ContextVar
which still held the un-remapped JWT tenant DEMO_REST. RLS policy
`factory_id = current_setting('app.factory_id')` then filtered out every
RES_3101_009 row -> zero results on kpi-summary / finance-summary / trend-bundle /
daily-trend / data-range / review-*. store-kpi-dashboard escaped only because
compute_store_kpi_dashboard re-set the ContextVar itself. The fix re-points the
RLS context at the single chokepoint `_resolve_tenant`.
"""
from __future__ import annotations

import pytest
from fastapi import HTTPException

import smartbi.api.gold_reads as gr


def _patch_tenant_ctx(monkeypatch, jwt_tenant):
    """Fake tenant_ctx: get_factory_id returns the JWT tenant; set_factory_id
    records the value it was re-pointed to (or stays None if never called)."""
    calls = {"set_to": None}
    monkeypatch.setattr(gr, "get_factory_id", lambda: jwt_tenant)
    monkeypatch.setattr(gr, "set_factory_id", lambda fid: calls.__setitem__("set_to", fid))
    return calls


# 🔴 2026-08-17 晚：生产的那条别名(DEMO_REST -> RES_3101_009)**已删** ——
#    它把免鉴权的 /auth/demo-login 接到了真客户的生产租户上(RES_3101_009 =
#    QHJ_PROD)。owner 裁定「只用 MOCK_REST」。
#
# ⛔ 但这两条用例**不能删**: 它们守的不变量仍然成立且承重 ——
#    **一旦发生重映射, RLS 上下文必须同步指过去**。别名表现在是空的,
#    所以改成**注入一个合成别名**来守它。
#    少了这一步, 将来谁再配一条别名, 2026-07-11 那个「查询改了 WHERE 而 RLS
#    还指着旧租户 ⇒ 每张表都读到 0 行」的事故会原样重演, 而且不报错。
_SYNTHETIC_ALIAS = {"DEMO_REST": "GENERATED_DEMO_TENANT"}


def test_a_remap_repoints_the_rls_context(monkeypatch):
    monkeypatch.setattr(gr, "DEMO_GOLD_TENANT_ALIASES", _SYNTHETIC_ALIAS)
    calls = _patch_tenant_ctx(monkeypatch, "DEMO_REST")
    resolved = gr._resolve_tenant("DEMO_REST")
    assert resolved == "GENERATED_DEMO_TENANT"      # query WHERE 用重映射后的租户
    assert calls["set_to"] == "GENERATED_DEMO_TENANT"  # RLS GUC 同步指过去 —— 就是那个修复


def test_a_remap_repoints_when_query_param_omitted(monkeypatch):
    # factory_id 查询参数省略 -> 落回 JWT 租户 -> 仍然要重映射且同步 RLS。
    monkeypatch.setattr(gr, "DEMO_GOLD_TENANT_ALIASES", _SYNTHETIC_ALIAS)
    calls = _patch_tenant_ctx(monkeypatch, "DEMO_REST")
    resolved = gr._resolve_tenant(None)
    assert resolved == "GENERATED_DEMO_TENANT"
    assert calls["set_to"] == "GENERATED_DEMO_TENANT"


def test_production_alias_table_is_empty(monkeypatch):
    """⛔ 阴性对照 + 裁定的回归守卫：生产的别名表必须是空的。

    ⚠️ 上面两条用的是**注入的**合成别名 —— 少了这一条，它们绿着也说明不了
       生产上没有别名（那正是这次缺陷藏了很久的原因：闸测的是机制，没测配置）。
    """
    assert gr.DEMO_GOLD_TENANT_ALIASES == {}, (
        f"生产别名表又有内容了: {gr.DEMO_GOLD_TENANT_ALIASES} —— "
        f"演示账号只能指向生成数据的租户, 见 "
        f"smartbi/gold/tests/test_demo_never_reads_a_production_tenant.py"
    )


def test_non_demo_tenant_is_noop(monkeypatch):
    # Real tenant: resolved == JWT tenant, so we must NOT re-point (no-op) —
    # zero cross-tenant risk, RLS already matches the query.
    calls = _patch_tenant_ctx(monkeypatch, "F001")
    resolved = gr._resolve_tenant("F001")
    assert resolved == "F001"
    assert calls["set_to"] is None              # never re-set for a non-aliased tenant


def test_foreign_query_factory_id_still_403(monkeypatch):
    # The JWT-match guard must run BEFORE any re-point.
    calls = _patch_tenant_ctx(monkeypatch, "DEMO_REST")
    with pytest.raises(HTTPException) as ei:
        gr._resolve_tenant("F001")
    assert ei.value.status_code == 403
    assert calls["set_to"] is None              # no RLS re-point on a rejected request
