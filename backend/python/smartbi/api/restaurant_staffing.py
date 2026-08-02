"""Tenant-scoped restaurant reservation, forecast and staffing APIs."""
from __future__ import annotations

from datetime import date, datetime
from typing import Any, Dict, List, Literal, Optional

from fastapi import APIRouter, HTTPException, Query, Request
from pydantic import BaseModel, Field, field_validator

from smartbi.config import get_pg_pool
from smartbi.services.restaurant.staffing_forecast import (
    DAYPARTS,
    RestaurantStaffingService,
)

router = APIRouter(prefix="/restaurant/staffing", tags=["RestaurantStaffing"])

WRITE_ROLES = {
    "restaurant_owner", "restaurant_manager", "hr_admin",
    "factory_super_admin", "platform_admin", "permission_admin",
}
ADMIN_ROLES = {"factory_super_admin", "platform_admin", "permission_admin"}


def _context(request: Request) -> tuple[str, str, str]:
    factory_id = str(getattr(request.state, "factory_id", "") or "").strip()
    role = str(getattr(request.state, "role", "") or "").strip().lower()
    user_id = str(getattr(request.state, "user_id", "") or "").strip()
    if not factory_id:
        raise HTTPException(status_code=401, detail="missing factory context")
    return factory_id, role, user_id or "unknown"


async def _service() -> RestaurantStaffingService:
    pool = await get_pg_pool()
    if pool is None:
        raise HTTPException(status_code=503, detail="SmartBI database unavailable")
    return RestaurantStaffingService(pool)


class ReservationEvent(BaseModel):
    source: str = Field(min_length=2, max_length=80)
    external_ref: str = Field(min_length=1, max_length=160)
    store_id: int = Field(gt=0)
    reservation_date: date
    daypart: Literal["午市", "下午茶", "晚市", "夜宵"]
    table_count: int = Field(ge=0, le=10000)
    guest_count: int = Field(ge=0, le=100000)
    status: Literal["PENDING", "CONFIRMED", "SEATED", "COMPLETED", "CANCELLED", "NO_SHOW"]
    source_updated_at: datetime
    is_simulated: bool = False

    @field_validator("source", "external_ref")
    @classmethod
    def strip_text(cls, value: str) -> str:
        return value.strip()


class ReservationImportRequest(BaseModel):
    records: List[ReservationEvent] = Field(min_length=1, max_length=5000)


class AdjustmentRequest(BaseModel):
    store_id: int = Field(gt=0)
    target_date: date
    daypart: Literal["午市", "下午茶", "晚市", "夜宵"]
    role_code: str = Field(min_length=1, max_length=40)
    predicted_guests: int = Field(ge=0, le=1000000)
    policy_version: int = Field(gt=0)
    prior_staff: int = Field(ge=0, le=1000)
    recommended_staff: int = Field(ge=0, le=1000)
    adjusted_staff: int = Field(ge=0, le=1000)
    plan_fingerprint: str = Field(pattern=r"^[a-f0-9]{64}$")
    reason: str = Field(min_length=2, max_length=500)
    idempotency_key: str = Field(min_length=8, max_length=100)


@router.get("/dashboard")
async def get_staffing_dashboard(
    request: Request,
    horizon: Literal["tomorrow", "week", "month"] = Query("tomorrow"),
    store_id: Optional[int] = Query(None, gt=0),
) -> Dict[str, Any]:
    factory_id, _, _ = _context(request)
    service = await _service()
    try:
        data = await service.build_dashboard(factory_id, horizon, store_id=store_id)
        return {"success": True, "data": data, "message": "预测排班 FactBook 已生成"}
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc


@router.post("/reservations/import")
async def import_reservations(
    request: Request,
    body: ReservationImportRequest,
) -> Dict[str, Any]:
    factory_id, role, _ = _context(request)
    if role not in WRITE_ROLES:
        raise HTTPException(status_code=403, detail="current role cannot import reservations")
    service = await _service()
    try:
        result = await service.import_reservations(
            factory_id,
            [record.model_dump() for record in body.records],
        )
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc
    return {
        "success": True,
        "data": {**result, "factory_id": factory_id, "business_write": True},
        "message": "预订平台事件已按来源幂等写入",
    }


@router.post("/reservations/roll")
async def roll_simulated_reservations(
    request: Request,
    days_ahead: int = Query(45, ge=7, le=90),
    force: bool = Query(False),
) -> Dict[str, Any]:
    factory_id, role, _ = _context(request)
    if role not in ADMIN_ROLES:
        raise HTTPException(status_code=403, detail="simulation roll requires an admin role")
    service = await _service()
    try:
        result = await service.roll_simulated_reservations(
            factory_id, days_ahead=days_ahead, force=force,
        )
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc
    return {
        "success": True,
        "data": {**result, "business_write": not result.get("skipped", False)},
        "message": "当日模拟预订已存在" if result.get("skipped") else "模拟预订已滚动并记录审计",
    }


@router.post("/adjustments")
async def apply_staffing_adjustment(
    request: Request,
    body: AdjustmentRequest,
) -> Dict[str, Any]:
    factory_id, role, user_id = _context(request)
    if role not in WRITE_ROLES:
        raise HTTPException(status_code=403, detail="current role cannot adjust staffing")
    service = await _service()
    try:
        result = await service.apply_adjustment(
            factory_id,
            body.model_dump(),
            actor_user_id=user_id,
            actor_role=role,
        )
    except PermissionError as exc:
        raise HTTPException(status_code=403, detail=str(exc)) from exc
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc
    return {"success": True, "data": result, "message": "排班调整已确认并写入审计"}


@router.get("/contract")
async def staffing_contract(request: Request) -> Dict[str, Any]:
    _context(request)
    return {
        "success": True,
        "data": {
            "dayparts": list(DAYPARTS),
            "horizons": ["tomorrow", "week", "month"],
            "numeric_source": "forecast_factbook_only",
            "llm_role": "question_understanding_explanation_adjustable_advice",
            "historical_productivity_rule": "evidence_only_not_staff_gap_direction",
            "adjustment_flow": "preview_confirm_audited_write",
        },
    }
