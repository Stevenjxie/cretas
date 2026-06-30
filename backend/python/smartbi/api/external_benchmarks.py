from __future__ import annotations

from fastapi import APIRouter, HTTPException, Request
from pydantic import BaseModel, Field

from smartbi.external_benchmarks.service import service


router = APIRouter()


class AmapDensityRequest(BaseModel):
    location: str = Field(..., description="longitude,latitude")
    keywords: str = Field(..., description="restaurant category or keyword")
    radius: int = Field(default=3000, ge=100, le=50000)
    geo_scope: str = "local"


class AmapWeatherRequest(BaseModel):
    city_adcode: str = Field(..., description="Amap city/district adcode")
    geo_scope: str = "local"


class TencentDensityRequest(BaseModel):
    location: str = Field(..., description="longitude,latitude")
    keywords: str = Field(..., description="restaurant category or keyword")
    radius: int = Field(default=3000, ge=100, le=50000)
    geo_scope: str = "local"


class TencentWeatherRequest(BaseModel):
    city_adcode: str = Field(..., description="Tencent district/city adcode")
    geo_scope: str = "local"


def _ok(data, message: str = "OK"):
    return {"success": True, "data": data, "message": message}


def _internal_only(request: Request) -> None:
    if getattr(request.state, "auth_method", None) != "internal":
        raise HTTPException(status_code=401, detail="X-Internal-Secret header missing or invalid")


@router.post("/external-benchmarks/sources/seed")
async def seed_sources(request: Request):
    _internal_only(request)
    counts = await service.seed_catalog()
    return _ok(counts)


@router.get("/external-benchmarks/sources")
async def list_sources():
    return _ok(await service.list_sources())


@router.get("/external-benchmarks/segment-profiles")
async def list_segment_profiles(
    profile_type: str | None = None,
    profile_code: str | None = None,
):
    return _ok(await service.list_segment_profiles(
        profile_type=profile_type,
        profile_code=profile_code,
    ))


@router.get("/external-benchmarks/observations")
async def list_observations(
    benchmark_domain: str = "restaurant",
    metric_code: str | None = None,
    geo_scope: str | None = None,
    category_scope: str | None = None,
    limit: int = 200,
):
    return _ok(await service.list_observations(
        benchmark_domain=benchmark_domain,
        metric_code=metric_code,
        geo_scope=geo_scope,
        category_scope=category_scope,
        limit=limit,
    ))


@router.post("/external-benchmarks/collect/amap-density")
async def collect_amap_density(body: AmapDensityRequest, request: Request):
    _internal_only(request)
    result = await service.collect_amap_density(
        location=body.location,
        keywords=body.keywords,
        radius=body.radius,
        geo_scope=body.geo_scope,
    )
    return _ok({
        "sourceCode": result.source_code,
        "status": result.status,
        "rowsUpserted": result.rows_upserted,
        "errorMessage": result.error_message,
    })


@router.post("/external-benchmarks/collect/amap-weather")
async def collect_amap_weather(body: AmapWeatherRequest, request: Request):
    _internal_only(request)
    result = await service.collect_amap_weather(
        city_adcode=body.city_adcode,
        geo_scope=body.geo_scope,
    )
    return _ok({
        "sourceCode": result.source_code,
        "status": result.status,
        "rowsUpserted": result.rows_upserted,
        "errorMessage": result.error_message,
    })


@router.post("/external-benchmarks/collect/tencent-density")
async def collect_tencent_density(body: TencentDensityRequest, request: Request):
    _internal_only(request)
    result = await service.collect_tencent_density(
        location=body.location,
        keywords=body.keywords,
        radius=body.radius,
        geo_scope=body.geo_scope,
    )
    return _ok({
        "sourceCode": result.source_code,
        "status": result.status,
        "rowsUpserted": result.rows_upserted,
        "errorMessage": result.error_message,
    })


@router.post("/external-benchmarks/collect/tencent-weather")
async def collect_tencent_weather(body: TencentWeatherRequest, request: Request):
    _internal_only(request)
    result = await service.collect_tencent_weather(
        city_adcode=body.city_adcode,
        geo_scope=body.geo_scope,
    )
    return _ok({
        "sourceCode": result.source_code,
        "status": result.status,
        "rowsUpserted": result.rows_upserted,
        "errorMessage": result.error_message,
    })


@router.post("/external-benchmarks/collect/{source_code}")
async def collect_source(source_code: str, request: Request):
    _internal_only(request)
    try:
        result = await service.collect_and_store(source_code)
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc
    return _ok({
        "sourceCode": result.source_code,
        "status": result.status,
        "rowsUpserted": result.rows_upserted,
        "errorMessage": result.error_message,
    })
