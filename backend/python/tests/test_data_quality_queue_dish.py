"""P4a Task 8 — dish entity_type path in data_quality_queue_admin.

Unit tests (no live DB) covering:
- 'dish' is a VALID_ENTITY_TYPE (list filter accepted)
- resolve_queue with a dish item routes to dish_confirm (not _update_queue_resolved)
- resolve_queue dish confirm WITHOUT resolvedToEntityId → 400 (must specify canonical)
- resolve_queue dish create_new routes to dish_confirm with canonical_id=None
- reject_queue with a dish item routes to dish_reject
- batch_resolve refuses dish items (one-at-a-time human gate)
- build_dish_review_payload shape: members + stores + qty/revenue

Mirrors test_data_quality_queue_admin.py style (require_admin path, AsyncMock,
patch get_pg_pool). No `as any` / no live DB.
"""
from __future__ import annotations

import pytest
from fastapi import HTTPException
from unittest.mock import patch, AsyncMock


def _req(role="platform_admin", factory_id="RES_3101_009", user_id=999):
    class _S:
        pass
    s = _S()
    s.role = role
    s.auth_method = "jwt"
    s.factory_id = factory_id
    s.user_id = user_id
    s.username = "admin1"

    class _Req:
        state = s
    return _Req()


def test_dish_is_valid_entity_type():
    from smartbi.api.data_quality_queue_admin import VALID_ENTITY_TYPES
    assert "dish" in VALID_ENTITY_TYPES


@pytest.mark.asyncio
async def test_resolve_dish_confirm_routes_to_dish_confirm():
    """dish item + action=confirm + resolvedToEntityId → _resolve_dish called,
    generic _update_queue_resolved NOT called."""
    from smartbi.api.data_quality_queue_admin import resolve_queue, ResolveBody

    dish_item = {
        "id": 50, "factoryId": "RES_3101_009", "status": "PENDING",
        "entityType": "dish", "submitter": "111",
    }
    with patch("smartbi.api.data_quality_queue_admin._get_queue_item",
               new=AsyncMock(return_value=dish_item)):
        with patch("smartbi.api.data_quality_queue_admin._resolve_dish",
                   new=AsyncMock(return_value=0)) as m_dish:
            with patch("smartbi.api.data_quality_queue_admin._update_queue_resolved",
                       new=AsyncMock()) as m_generic:
                with patch("smartbi.api.data_quality_queue_admin.get_pg_pool",
                           new=AsyncMock(return_value=AsyncMock())):
                    result = await resolve_queue(
                        request=_req(),
                        id=50,
                        body=ResolveBody(action="confirm", resolvedToEntityId=7),
                    )

    assert result["resolved"] is True
    m_dish.assert_awaited_once()
    # confirm → canonical_id arg = resolvedToEntityId (7)
    call_args = m_dish.await_args
    # _resolve_dish(pool, item_id, factory_id, action, resolved_to_entity_id,
    #               canonical_name, admin_user)
    assert call_args.args[1] == 50          # item_id
    assert call_args.args[3] == "confirm"   # action
    assert call_args.args[4] == 7           # resolved_to_entity_id
    m_generic.assert_not_called()


@pytest.mark.asyncio
async def test_resolve_dish_confirm_without_canonical_id_400():
    """dish confirm with no resolvedToEntityId → 400 (must pick a canonical)."""
    from smartbi.api.data_quality_queue_admin import resolve_queue, ResolveBody

    dish_item = {
        "id": 51, "factoryId": "RES_3101_009", "status": "PENDING",
        "entityType": "dish", "submitter": "111",
    }
    with patch("smartbi.api.data_quality_queue_admin._get_queue_item",
               new=AsyncMock(return_value=dish_item)):
        with patch("smartbi.api.data_quality_queue_admin.get_pg_pool",
                   new=AsyncMock(return_value=AsyncMock())):
            with pytest.raises(HTTPException) as exc:
                await resolve_queue(
                    request=_req(),
                    id=51,
                    body=ResolveBody(action="confirm"),  # no resolvedToEntityId
                )
    assert exc.value.status_code == 400


@pytest.mark.asyncio
async def test_resolve_dish_create_new_routes_with_canonical_name():
    """dish create_new → _resolve_dish action='create_new', canonical_id None,
    canonicalName forwarded."""
    from smartbi.api.data_quality_queue_admin import resolve_queue, ResolveBody

    dish_item = {
        "id": 52, "factoryId": "RES_3101_009", "status": "PENDING",
        "entityType": "dish", "submitter": "111",
    }
    with patch("smartbi.api.data_quality_queue_admin._get_queue_item",
               new=AsyncMock(return_value=dish_item)):
        with patch("smartbi.api.data_quality_queue_admin._resolve_dish",
                   new=AsyncMock(return_value=0)) as m_dish:
            with patch("smartbi.api.data_quality_queue_admin.get_pg_pool",
                       new=AsyncMock(return_value=AsyncMock())):
                await resolve_queue(
                    request=_req(),
                    id=52,
                    body=ResolveBody(action="create_new",
                                     canonicalName="招牌青花椒鱼"),
                )
    call_args = m_dish.await_args
    assert call_args.args[3] == "create_new"
    assert call_args.args[4] is None        # resolved_to_entity_id
    assert call_args.args[5] == "招牌青花椒鱼"   # canonical_name


@pytest.mark.asyncio
async def test_resolve_dish_service_precondition_failure_409():
    """_resolve_dish returns 1 (race / no members) → 409 to caller."""
    from smartbi.api.data_quality_queue_admin import resolve_queue, ResolveBody

    dish_item = {
        "id": 53, "factoryId": "RES_3101_009", "status": "PENDING",
        "entityType": "dish", "submitter": "111",
    }
    with patch("smartbi.api.data_quality_queue_admin._get_queue_item",
               new=AsyncMock(return_value=dish_item)):
        with patch("smartbi.api.data_quality_queue_admin._resolve_dish",
                   new=AsyncMock(return_value=1)):
            with patch("smartbi.api.data_quality_queue_admin.get_pg_pool",
                       new=AsyncMock(return_value=AsyncMock())):
                with pytest.raises(HTTPException) as exc:
                    await resolve_queue(
                        request=_req(),
                        id=53,
                        body=ResolveBody(action="create_new"),
                    )
    assert exc.value.status_code == 409


@pytest.mark.asyncio
async def test_reject_dish_routes_to_dish_reject():
    """dish item reject → _reject_dish called, returns rejected True."""
    from smartbi.api.data_quality_queue_admin import reject_queue, RejectBody

    dish_item = {
        "id": 54, "factoryId": "RES_3101_009", "status": "PENDING",
        "entityType": "dish", "submitter": "111",
    }
    with patch("smartbi.api.data_quality_queue_admin._get_queue_item",
               new=AsyncMock(return_value=dish_item)):
        with patch("smartbi.api.data_quality_queue_admin._reject_dish",
                   new=AsyncMock(return_value=0)) as m_reject:
            with patch("smartbi.api.data_quality_queue_admin.get_pg_pool",
                       new=AsyncMock(return_value=AsyncMock())):
                result = await reject_queue(
                    request=_req(),
                    id=54,
                    body=RejectBody(reason="青花椒鱼 与 青花椒虾 主料不同"),
                )
    assert result["rejected"] is True
    m_reject.assert_awaited_once()
    # _reject_dish(pool, item_id, factory_id, reason, admin_user)
    assert m_reject.await_args.args[1] == 54
    assert "主料不同" in m_reject.await_args.args[3]


@pytest.mark.asyncio
async def test_batch_resolve_refuses_dish():
    """batch confirm must NOT touch dish (one-at-a-time canonical gate)."""
    from smartbi.api.data_quality_queue_admin import (
        batch_resolve_queue, BatchResolveBody,
    )

    dish_item = {
        "id": 55, "factoryId": "RES_3101_009", "status": "PENDING",
        "entityType": "dish", "submitter": "111",
    }
    with patch("smartbi.api.data_quality_queue_admin._get_queue_item",
               new=AsyncMock(return_value=dish_item)):
        with patch("smartbi.api.data_quality_queue_admin._update_queue_resolved",
                   new=AsyncMock()) as m_generic:
            with patch("smartbi.api.data_quality_queue_admin.get_pg_pool",
                       new=AsyncMock(return_value=AsyncMock())):
                result = await batch_resolve_queue(
                    request=_req(),
                    body=BatchResolveBody(ids=[55], action="confirm"),
                )
    assert result["successCount"] == 0
    assert len(result["failedItems"]) == 1
    assert result["failedItems"][0]["id"] == 55
    assert "批量" in result["failedItems"][0]["reason"]
    m_generic.assert_not_called()


@pytest.mark.asyncio
async def test_build_dish_review_payload_shape():
    """build_dish_review_payload returns members with stores + qty/revenue."""
    from smartbi.canonical.dish_confirm_service import build_dish_review_payload

    extra = {
        "proposal_kind": "rule_cluster",
        "normalized_key": "招牌青花椒鱼",
        "category": "主菜",
        "member_product_ids": [101, 102],
        "member_names": ["招牌青花椒鱼(单人份)", "青花椒味鱼"],
    }

    class _Conn:
        async def fetch(self, sql, *args):
            if "agg_product" in sql:
                return [
                    {"product_id": 101, "qty": 30.0, "revenue": 1740.0},
                    {"product_id": 102, "qty": 12.0, "revenue": 720.0},
                ]
            # member stores SQL
            return [
                {"product_id": 101, "store_name": "大融城店"},
                {"product_id": 101, "store_name": "龙湖店"},
                {"product_id": 102, "store_name": "颛桥店"},
            ]

    payload = await build_dish_review_payload(_Conn(), "RES_3101_009", extra)

    assert payload["proposalKind"] == "rule_cluster"
    assert payload["normalizedKey"] == "招牌青花椒鱼"
    assert payload["category"] == "主菜"
    assert payload["memberCount"] == 2

    members = {m["productId"]: m for m in payload["members"]}
    assert members[101]["name"] == "招牌青花椒鱼(单人份)"
    assert members[101]["qty"] == 30.0
    assert members[101]["revenue"] == 1740.0
    assert set(members[101]["stores"]) == {"大融城店", "龙湖店"}
    assert members[102]["stores"] == ["颛桥店"]


@pytest.mark.asyncio
async def test_build_dish_review_payload_no_sales_data():
    """Member with no agg_product / no stores → qty/revenue 0, stores []."""
    from smartbi.canonical.dish_confirm_service import build_dish_review_payload

    extra = {
        "proposal_kind": "create_new",
        "normalized_key": "冰粉",
        "member_product_ids": [200],
        "member_names": ["红糖冰粉"],
    }

    class _Conn:
        async def fetch(self, sql, *args):
            return []  # no sales, no stores

    payload = await build_dish_review_payload(_Conn(), "RES_3101_009", extra)
    m = payload["members"][0]
    assert m["productId"] == 200
    assert m["qty"] == 0.0
    assert m["revenue"] == 0.0
    assert m["stores"] == []
