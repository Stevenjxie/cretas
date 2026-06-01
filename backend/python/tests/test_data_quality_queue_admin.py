"""TDD tests for /api/smartbi/admin/data-quality-queue/list (餐饮 Phase A Task 3.2).

Two focused unit tests that do NOT require a live DB or running app:
- test_list_rejects_non_admin       → 403 when role is 'operator'
- test_list_returns_paginated       → paginated response for platform_admin + factoryId

Pattern mirrors test_restaurant_etl_admin.py: use require_admin directly (not
via TestClient) to keep tests minimal and avoid HTTP transport overhead.
"""
from __future__ import annotations

import pytest
from fastapi import HTTPException
from unittest.mock import patch, AsyncMock


@pytest.mark.asyncio
async def test_list_rejects_non_admin():
    """role='operator' → HTTPException 403 with '管理员' in detail."""
    from smartbi.api.data_quality_queue_admin import list_queue

    class _S:
        role = "operator"
        auth_method = "jwt"
        factory_id = "F001"

    class _Req:
        state = _S()

    with pytest.raises(HTTPException) as exc:
        await list_queue(
            request=_Req(),
            factoryId=None,
            entityType=None,
            status=None,
            page=1,
            pageSize=50,
        )
    assert exc.value.status_code == 403
    assert "管理员" in (exc.value.detail or "")


@pytest.mark.asyncio
async def test_list_returns_paginated():
    """role='platform_admin' + factoryId='F001' → paginated response with 1 item."""
    from smartbi.api.data_quality_queue_admin import list_queue

    class _S:
        role = "platform_admin"
        auth_method = "jwt"
        factory_id = "F001"

    class _Req:
        state = _S()

    fake_items = [{"id": 1, "rawName": "x"}]
    fake_total = 1

    with patch(
        "smartbi.api.data_quality_queue_admin._fetch_queue_items",
        new=AsyncMock(return_value=(fake_items, fake_total)),
    ):
        with patch(
            "smartbi.api.data_quality_queue_admin.get_pg_pool",
            new=AsyncMock(return_value=AsyncMock()),
        ):
            result = await list_queue(
                request=_Req(),
                factoryId="F001",
                entityType=None,
                status=None,
                page=1,
                pageSize=50,
            )

    assert "items" in result
    assert result["total"] == 1
    assert len(result["items"]) == 1
    assert result["page"] == 1
    assert result["pageSize"] == 50


@pytest.mark.asyncio
async def test_resolve_rejects_self_when_multi_admin():
    """Submitter == current_user, admin_count > 1 → 403 中文."""
    from smartbi.api.data_quality_queue_admin import resolve_queue, ResolveBody

    class _S:
        role = "factory_super_admin"
        auth_method = "jwt"
        factory_id = "F001"
        user_id = 1
        username = "admin1"

    class _Req:
        state = _S()

    with patch('smartbi.api.data_quality_queue_admin._get_queue_item',
               new=AsyncMock(return_value={"id": 1, "factoryId": "F001", "status": "PENDING", "submitter": "1"})):
        with patch('smartbi.api.data_quality_queue_admin._get_admin_count_for_factory',
                   new=AsyncMock(return_value=2)):
            with patch('smartbi.api.data_quality_queue_admin.get_pg_pool',
                       new=AsyncMock(return_value=AsyncMock())):
                with pytest.raises(HTTPException) as exc:
                    await resolve_queue(request=_Req(), id=1, body=ResolveBody(action="confirm", resolvedToEntityId=99))

    assert exc.value.status_code == 403
    assert "提交" in exc.value.detail or "审核" in exc.value.detail


@pytest.mark.asyncio
async def test_resolve_allows_self_when_single_admin():
    """Submitter == current_user but admin_count == 1 → allows (degradation)."""
    from smartbi.api.data_quality_queue_admin import resolve_queue, ResolveBody

    class _S:
        role = "factory_super_admin"
        auth_method = "jwt"
        factory_id = "F001"
        user_id = 1
        username = "admin1"

    class _Req:
        state = _S()

    # _update_queue_resolved now returns the updated row dict (id/raw_name/
    # entity_type) instead of a bool — see graduation feature.
    with patch('smartbi.api.data_quality_queue_admin._get_queue_item',
               new=AsyncMock(return_value={"id": 1, "factoryId": "F001", "status": "PENDING", "submitter": "1"})):
        with patch('smartbi.api.data_quality_queue_admin._get_admin_count_for_factory',
                   new=AsyncMock(return_value=1)):
            with patch('smartbi.api.data_quality_queue_admin._update_queue_resolved',
                       new=AsyncMock(return_value={"id": 1, "raw_name": "x", "entity_type": "store"})):
                with patch('smartbi.api.data_quality_queue_admin._record_admin_confirm_history',
                           new=AsyncMock(return_value=None)):
                    with patch('smartbi.api.data_quality_queue_admin.get_pg_pool',
                               new=AsyncMock(return_value=AsyncMock())):
                        result = await resolve_queue(
                            request=_Req(), id=1,
                            body=ResolveBody(action="confirm", resolvedToEntityId=99),
                        )

    assert result["resolved"] is True
    assert result.get("singleAdminDegraded") is True


@pytest.mark.asyncio
async def test_batch_resolve_partial_success():
    """3 IDs: 1 ok / 1 self-blocked (4-eye) / 1 not found → successCount=1, 2 failures."""
    from smartbi.api.data_quality_queue_admin import batch_resolve_queue, BatchResolveBody

    class _S:
        role = "platform_admin"
        auth_method = "jwt"
        factory_id = "F001"
        user_id = 1
        username = "admin1"

    class _Req:
        state = _S()

    items = {
        1: {"id": 1, "factoryId": "F001", "status": "PENDING", "submitter": "2"},  # other admin → ok
        2: {"id": 2, "factoryId": "F001", "status": "PENDING", "submitter": "1"},  # self → 4-eye blocked
        3: None,  # not found
    }

    async def fake_get_item(pool, item_id):
        return items.get(item_id)

    # _update_queue_resolved now returns the updated row dict instead of a bool.
    with patch('smartbi.api.data_quality_queue_admin._get_queue_item', side_effect=fake_get_item):
        with patch('smartbi.api.data_quality_queue_admin._get_admin_count_for_factory',
                   new=AsyncMock(return_value=2)):
            with patch('smartbi.api.data_quality_queue_admin._update_queue_resolved',
                       new=AsyncMock(return_value={"id": 1, "raw_name": "x", "entity_type": "store"})):
                with patch('smartbi.api.data_quality_queue_admin._record_admin_confirm_history',
                           new=AsyncMock(return_value=None)):
                    with patch('smartbi.api.data_quality_queue_admin.get_pg_pool',
                               new=AsyncMock(return_value=AsyncMock())):
                        result = await batch_resolve_queue(
                            request=_Req(),
                            body=BatchResolveBody(ids=[1, 2, 3], action="confirm", resolvedToEntityId=99),
                        )

    assert result["successCount"] == 1
    assert len(result["failedItems"]) == 2
    assert any(f["id"] == 2 and ("提交" in f["reason"] or "审核" in f["reason"]) for f in result["failedItems"])
    assert any(f["id"] == 3 for f in result["failedItems"])
