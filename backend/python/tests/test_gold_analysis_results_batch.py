"""Tests for batch /api/smartbi/gold/analysis-results endpoint (Week 6)."""
from __future__ import annotations

import os
from datetime import datetime

import pytest
import pytest_asyncio


_TENANT_A = "TEST_BATCH_A"
_TENANT_B = "TEST_BATCH_B"
_INTERNAL_SECRET = os.getenv(
    "INTERNAL_API_SECRET", "test-only-internal-secret-not-for-production",
)


@pytest_asyncio.fixture
async def pool():
    import asyncpg
    from smartbi.config import get_settings
    from smartbi.tenant_ctx import set_pg_connection_tenant
    settings = get_settings()
    if not settings.postgres_url:
        pytest.skip("No Postgres configured")
    p = await asyncpg.create_pool(
        settings.postgres_url, min_size=1, max_size=3,
        setup=set_pg_connection_tenant,
    )
    try:
        yield p
    finally:
        await p.close()


async def _seed(pool, tenant: str, upload_id: int, template_code: str,
                created_at: datetime | None = None):
    """Insert a minimal row into smart_bi_pg_analysis_results."""
    from smartbi.tenant_ctx import set_factory_id
    set_factory_id(tenant)
    async with pool.acquire() as conn:
        # Ensure parent upload row exists (FK to smart_bi_pg_excel_uploads)
        await conn.execute(
            """
            INSERT INTO smart_bi_pg_excel_uploads (id, factory_id, file_name, upload_status)
            VALUES ($1, $2, $3, 'COMPLETED')
            ON CONFLICT (id) DO NOTHING
            """,
            upload_id, tenant, f"test_{upload_id}.csv",
        )
        await conn.execute(
            """
            INSERT INTO smart_bi_pg_analysis_results
                (upload_id, factory_id, template_code, domain, analysis_type,
                 analysis_result, chart_configs, kpi_values, insights, created_at)
            VALUES ($1, $2, $3::varchar, 'test_domain', ('materialized:' || $3::text)::varchar,
                    '{"data": []}'::jsonb, '[]'::jsonb, '{}'::jsonb, '[]'::jsonb,
                    COALESCE($4, NOW()))
            ON CONFLICT (upload_id, template_code)
              WHERE template_code IS NOT NULL
              DO UPDATE SET created_at = EXCLUDED.created_at
            """,
            upload_id, tenant, template_code, created_at,
        )


async def _cleanup(pool, tenants: list):
    from smartbi.tenant_ctx import set_factory_id
    for t in tenants:
        set_factory_id(t)
        async with pool.acquire() as conn:
            await conn.execute(
                "DELETE FROM smart_bi_pg_analysis_results WHERE factory_id=$1", t,
            )
            await conn.execute(
                "DELETE FROM smart_bi_pg_excel_uploads WHERE factory_id=$1", t,
            )


async def test_batch_resolve_latest_per_template(pool):
    """Different codes resolve to different latest uploads."""
    try:
        # code_A: latest upload = 90001, earlier = 90000
        await _seed(pool, _TENANT_A, 90000, "tpl_alpha",
                    created_at=datetime(2025, 1, 1))
        await _seed(pool, _TENANT_A, 90001, "tpl_alpha",
                    created_at=datetime(2025, 6, 1))
        # code_B: only one upload, older than code_A's latest
        await _seed(pool, _TENANT_A, 90000, "tpl_beta",
                    created_at=datetime(2025, 1, 1))

        from smartbi.api.gold_reads import _query_analysis_results_batch
        result = await _query_analysis_results_batch(
            pool, _TENANT_A,
            template_codes=["tpl_alpha", "tpl_beta", "tpl_never"],
            upload_id=None,
        )

        items = {i["template_code"]: i for i in result["items"]}
        assert set(items.keys()) == {"tpl_alpha", "tpl_beta"}
        assert items["tpl_alpha"]["upload_id"] == 90001  # newer wins
        assert items["tpl_beta"]["upload_id"] == 90000
        assert result["never_materialized_codes"] == ["tpl_never"]
        assert result["missing_codes"] == []
    finally:
        await _cleanup(pool, [_TENANT_A])


async def test_batch_with_upload_id_override(pool):
    """Pinning upload_id puts codes not in that upload into missing_codes."""
    try:
        await _seed(pool, _TENANT_A, 90010, "tpl_alpha")
        await _seed(pool, _TENANT_A, 90010, "tpl_beta")
        # tpl_gamma only in another upload
        await _seed(pool, _TENANT_A, 90011, "tpl_gamma")

        from smartbi.api.gold_reads import _query_analysis_results_batch
        result = await _query_analysis_results_batch(
            pool, _TENANT_A,
            template_codes=["tpl_alpha", "tpl_gamma", "tpl_never"],
            upload_id=90010,
        )

        codes_loaded = {i["template_code"] for i in result["items"]}
        assert codes_loaded == {"tpl_alpha"}
        assert result["missing_codes"] == ["tpl_gamma"]
        assert result["never_materialized_codes"] == ["tpl_never"]
    finally:
        await _cleanup(pool, [_TENANT_A])


async def test_tenant_isolation(pool):
    """Factory A's rows invisible to factory B via RLS."""
    try:
        await _seed(pool, _TENANT_A, 90020, "tpl_alpha")

        # Switch tenant context
        from smartbi.tenant_ctx import set_factory_id
        set_factory_id(_TENANT_B)

        from smartbi.api.gold_reads import _query_analysis_results_batch
        result = await _query_analysis_results_batch(
            pool, _TENANT_B,
            template_codes=["tpl_alpha"],
            upload_id=None,
        )
        assert result["items"] == []
        assert result["never_materialized_codes"] == ["tpl_alpha"]
    finally:
        await _cleanup(pool, [_TENANT_A, _TENANT_B])


@pytest.mark.integration
async def test_route_batch_limit_rejected(pool, httpx_client=None):
    """Route returns 400 when >20 codes requested."""
    import httpx
    # 21 codes
    codes = ",".join(f"tpl_{i}" for i in range(21))
    # Use internal-secret + X-Factory-Id shortcut on localhost
    async with httpx.AsyncClient(base_url="http://localhost:8084") as c:
        resp = await c.get(
            "/api/smartbi/gold/analysis-results",
            params={"template_codes": codes},
            headers={
                "X-Internal-Secret": _INTERNAL_SECRET,
                "X-Factory-Id": _TENANT_A,
            },
        )
    assert resp.status_code == 400
    assert "max 20" in resp.text


@pytest.mark.integration
async def test_route_batch_happy_path_via_http(pool):
    """Seed, call HTTP, expect items shape."""
    try:
        await _seed(pool, _TENANT_A, 90050, "tpl_alpha")
        import httpx
        async with httpx.AsyncClient(base_url="http://localhost:8084") as c:
            resp = await c.get(
                "/api/smartbi/gold/analysis-results",
                params={"template_codes": "tpl_alpha,tpl_never"},
                headers={
                    "X-Internal-Secret": _INTERNAL_SECRET,
                    "X-Factory-Id": _TENANT_A,
                },
            )
        assert resp.status_code == 200
        body = resp.json()
        assert len(body["items"]) == 1
        assert body["items"][0]["template_code"] == "tpl_alpha"
        assert body["never_materialized_codes"] == ["tpl_never"]
    finally:
        await _cleanup(pool, [_TENANT_A])
