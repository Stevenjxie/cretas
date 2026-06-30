from __future__ import annotations

import logging
import json
import os
from typing import List, Optional

from smartbi.config import get_pg_pool

from .collectors import (
    AmapPoiCollector,
    AmapWeatherCollector,
    CollectorResult,
    ExternalObservation,
    IndustryReportSeedCollector,
    MoaWholesalePriceCollector,
    NbsCateringStatsCollector,
    TencentPlaceCollector,
    TencentWeatherCollector,
    redact_sensitive_url,
)
from .sources import source_rows
from .taxonomy import profile_rows

logger = logging.getLogger(__name__)


SOURCE_UPSERT_SQL = """
INSERT INTO external_benchmark_source (
    source_code, source_name, source_type, access_mode, compliance_level,
    base_url, requires_api_key, refresh_interval_hours, notes,
    raw_review_allowed, robots_respected, enabled, updated_at
) VALUES (
    $1, $2, $3, $4, $5,
    $6, $7, $8, $9,
    $10, $11, $12, now()
)
ON CONFLICT (source_code) DO UPDATE SET
    source_name = EXCLUDED.source_name,
    source_type = EXCLUDED.source_type,
    access_mode = EXCLUDED.access_mode,
    compliance_level = EXCLUDED.compliance_level,
    base_url = EXCLUDED.base_url,
    requires_api_key = EXCLUDED.requires_api_key,
    refresh_interval_hours = EXCLUDED.refresh_interval_hours,
    notes = EXCLUDED.notes,
    raw_review_allowed = EXCLUDED.raw_review_allowed,
    robots_respected = EXCLUDED.robots_respected,
    enabled = EXCLUDED.enabled,
    updated_at = now()
"""


OBSERVATION_UPSERT_SQL = """
INSERT INTO external_benchmark_observation (
    factory_id, source_code, benchmark_domain, metric_code, metric_name,
    metric_value, metric_unit, dimension, dimension_hash,
    geo_scope, category_scope, period_start, period_end,
    confidence_score, confidence_label, source_url, source_title,
    collected_at, expires_at, raw_payload, updated_at
) VALUES (
    $1, $2, $3, $4, $5,
    $6, $7, $8::jsonb, $9,
    $10, $11, $12, $13,
    $14, $15, $16, $17,
    $18, $19, $20::jsonb, now()
)
ON CONFLICT (
    source_code, metric_code, geo_scope, category_scope, period_start, period_end, dimension_hash
) DO UPDATE SET
    factory_id = EXCLUDED.factory_id,
    benchmark_domain = EXCLUDED.benchmark_domain,
    metric_name = EXCLUDED.metric_name,
    metric_value = EXCLUDED.metric_value,
    metric_unit = EXCLUDED.metric_unit,
    dimension = EXCLUDED.dimension,
    confidence_score = EXCLUDED.confidence_score,
    confidence_label = EXCLUDED.confidence_label,
    source_url = EXCLUDED.source_url,
    source_title = EXCLUDED.source_title,
    collected_at = EXCLUDED.collected_at,
    expires_at = EXCLUDED.expires_at,
    raw_payload = EXCLUDED.raw_payload,
    updated_at = now()
"""


PROFILE_UPSERT_SQL = """
INSERT INTO external_benchmark_segment_profile (
    profile_code, profile_type, display_name, description,
    dimension_weights, external_signal_plan, analysis_questions,
    action_templates, source_code, updated_at
) VALUES (
    $1, $2, $3, $4,
    $5::jsonb, $6::jsonb, $7::jsonb,
    $8::jsonb, $9, now()
)
ON CONFLICT (profile_code) DO UPDATE SET
    profile_type = EXCLUDED.profile_type,
    display_name = EXCLUDED.display_name,
    description = EXCLUDED.description,
    dimension_weights = EXCLUDED.dimension_weights,
    external_signal_plan = EXCLUDED.external_signal_plan,
    analysis_questions = EXCLUDED.analysis_questions,
    action_templates = EXCLUDED.action_templates,
    source_code = EXCLUDED.source_code,
    updated_at = now()
"""


class ExternalBenchmarkService:
    def amap_daily_budget(self) -> int:
        raw = os.getenv("AMAP_DAILY_QUERY_BUDGET", "800")
        try:
            return max(0, int(raw))
        except ValueError:
            logger.warning("Invalid AMAP_DAILY_QUERY_BUDGET=%r, using 800", raw)
            return 800

    def tencent_map_daily_budget(self) -> int:
        raw = os.getenv("TENCENT_MAP_DAILY_QUERY_BUDGET", "1600")
        try:
            return max(0, int(raw))
        except ValueError:
            logger.warning("Invalid TENCENT_MAP_DAILY_QUERY_BUDGET=%r, using 1600", raw)
            return 1600

    async def seed_sources(self) -> int:
        pool = await get_pg_pool()
        if pool is None:
            raise RuntimeError("PostgreSQL pool is not available")
        rows = list(source_rows())
        async with pool.acquire() as conn:
            for row in rows:
                await conn.execute(
                    SOURCE_UPSERT_SQL,
                    row["source_code"],
                    row["source_name"],
                    row["source_type"],
                    row["access_mode"],
                    row["compliance_level"],
                    row["base_url"],
                    row["requires_api_key"],
                    row["refresh_interval_hours"],
                    row["notes"],
                    row["raw_review_allowed"],
                    row["robots_respected"],
                    row["enabled"],
                )
        return len(rows)

    async def seed_segment_profiles(self) -> int:
        pool = await get_pg_pool()
        if pool is None:
            raise RuntimeError("PostgreSQL pool is not available")
        rows = list(profile_rows())
        async with pool.acquire() as conn:
            for row in rows:
                await conn.execute(
                    PROFILE_UPSERT_SQL,
                    row["profile_code"],
                    row["profile_type"],
                    row["display_name"],
                    row["description"],
                    json.dumps(row["dimension_weights"], ensure_ascii=False),
                    json.dumps(row["external_signal_plan"], ensure_ascii=False),
                    json.dumps(row["analysis_questions"], ensure_ascii=False),
                    json.dumps(row["action_templates"], ensure_ascii=False),
                    row["source_code"],
                )
        return len(rows)

    async def seed_catalog(self) -> dict:
        source_count = await self.seed_sources()
        profile_count = await self.seed_segment_profiles()
        return {"sources": source_count, "segmentProfiles": profile_count}

    async def upsert_observations(self, observations: List[ExternalObservation]) -> int:
        if not observations:
            return 0
        pool = await get_pg_pool()
        if pool is None:
            raise RuntimeError("PostgreSQL pool is not available")
        async with pool.acquire() as conn:
            for obs in observations:
                await conn.execute(
                    OBSERVATION_UPSERT_SQL,
                    obs.factory_id,
                    obs.source_code,
                    obs.benchmark_domain,
                    obs.metric_code,
                    obs.metric_name,
                    obs.metric_value,
                    obs.metric_unit,
                    json.dumps(obs.dimension, ensure_ascii=False),
                    obs.dimension_hash,
                    obs.geo_scope,
                    obs.category_scope,
                    obs.period_start,
                    obs.period_end,
                    obs.confidence_score,
                    obs.confidence_label,
                    obs.source_url,
                    obs.source_title,
                    obs.collected_at,
                    obs.expires_at,
                    json.dumps(obs.raw_payload, ensure_ascii=False),
                )
        return len(observations)

    async def record_job_run(self, result: CollectorResult) -> None:
        pool = await get_pg_pool()
        if pool is None:
            return
        async with pool.acquire() as conn:
            await conn.execute(
                """
                INSERT INTO external_benchmark_job_run (
                    source_code, status, finished_at, rows_upserted,
                    error_message, request_url, raw_payload
                ) VALUES ($1, $2, now(), $3, $4, $5, $6::jsonb)
                """,
                result.source_code,
                result.status,
                result.rows_upserted,
                result.error_message,
                redact_sensitive_url(result.request_url),
                result.raw_payload,
            )

    async def get_today_source_runs(self, source_code: str) -> int:
        pool = await get_pg_pool()
        if pool is None:
            return 0
        async with pool.acquire() as conn:
            return int(await conn.fetchval(
                """
                SELECT COUNT(*)
                  FROM external_benchmark_job_run
                 WHERE source_code = $1
                   AND started_at >= date_trunc('day', now())
                   AND status IN ('success', 'empty', 'error')
                """,
                source_code,
            ) or 0)

    async def collect_and_store(self, source_code: str) -> CollectorResult:
        await self.seed_catalog()
        if source_code == "nbs_catering_retail":
            result = await NbsCateringStatsCollector().collect()
        elif source_code == "industry_report_seed":
            result = IndustryReportSeedCollector().collect()
        elif source_code == "moa_wholesale_price_daily":
            result = await MoaWholesalePriceCollector().collect()
        else:
            raise ValueError(f"Unsupported collector source_code={source_code}")
        result.rows_upserted = await self.upsert_observations(result.observations)
        await self.record_job_run(result)
        return result

    async def collect_amap_density(self, *, location: str, keywords: str, radius: int = 3000, geo_scope: str = "local") -> CollectorResult:
        await self.seed_catalog()
        daily_budget = self.amap_daily_budget()
        used_today = await self.get_today_source_runs("amap_poi_search")
        if daily_budget <= 0 or used_today >= daily_budget:
            result = CollectorResult(
                source_code="amap_poi_search",
                status="skipped",
                error_message=(
                    f"Amap daily budget exhausted: used={used_today}, "
                    f"budget={daily_budget}. Collection will resume tomorrow."
                ),
                raw_payload={"used_today": used_today, "daily_budget": daily_budget},
            )
            await self.record_job_run(result)
            return result
        try:
            result = await AmapPoiCollector().collect_density(
                location=location,
                keywords=keywords,
                radius=radius,
                geo_scope=geo_scope,
            )
        except Exception as exc:
            result = CollectorResult(
                source_code="amap_poi_search",
                status="error",
                error_message=f"Amap collection failed: {exc}",
            )
        result.rows_upserted = await self.upsert_observations(result.observations)
        await self.record_job_run(result)
        return result

    async def collect_amap_weather(self, *, city_adcode: str, geo_scope: str = "local") -> CollectorResult:
        await self.seed_catalog()
        daily_budget = self.amap_daily_budget()
        used_today = await self.get_today_source_runs("amap_weather")
        if daily_budget <= 0 or used_today >= daily_budget:
            result = CollectorResult(
                source_code="amap_weather",
                status="skipped",
                error_message=(
                    f"Amap daily budget exhausted: used={used_today}, "
                    f"budget={daily_budget}. Weather collection will resume tomorrow."
                ),
                raw_payload={"used_today": used_today, "daily_budget": daily_budget},
            )
            await self.record_job_run(result)
            return result
        try:
            result = await AmapWeatherCollector().collect_live(
                city_adcode=city_adcode,
                geo_scope=geo_scope,
            )
        except Exception as exc:
            result = CollectorResult(
                source_code="amap_weather",
                status="error",
                error_message=f"Amap weather collection failed: {exc}",
            )
        result.rows_upserted = await self.upsert_observations(result.observations)
        await self.record_job_run(result)
        return result

    async def collect_tencent_density(self, *, location: str, keywords: str, radius: int = 3000, geo_scope: str = "local") -> CollectorResult:
        await self.seed_catalog()
        daily_budget = self.tencent_map_daily_budget()
        used_today = await self.get_today_source_runs("tencent_map_place_search")
        if daily_budget <= 0 or used_today >= daily_budget:
            result = CollectorResult(
                source_code="tencent_map_place_search",
                status="skipped",
                error_message=(
                    f"Tencent map daily budget exhausted: used={used_today}, "
                    f"budget={daily_budget}. Place collection will resume tomorrow."
                ),
                raw_payload={"used_today": used_today, "daily_budget": daily_budget},
            )
            await self.record_job_run(result)
            return result
        try:
            result = await TencentPlaceCollector().collect_density(
                location=location,
                keywords=keywords,
                radius=radius,
                geo_scope=geo_scope,
            )
        except Exception as exc:
            result = CollectorResult(
                source_code="tencent_map_place_search",
                status="error",
                error_message=f"Tencent place collection failed: {exc}",
            )
        result.rows_upserted = await self.upsert_observations(result.observations)
        await self.record_job_run(result)
        return result

    async def collect_tencent_weather(self, *, city_adcode: str, geo_scope: str = "local") -> CollectorResult:
        await self.seed_catalog()
        daily_budget = self.tencent_map_daily_budget()
        used_today = await self.get_today_source_runs("tencent_map_weather")
        if daily_budget <= 0 or used_today >= daily_budget:
            result = CollectorResult(
                source_code="tencent_map_weather",
                status="skipped",
                error_message=(
                    f"Tencent map daily budget exhausted: used={used_today}, "
                    f"budget={daily_budget}. Weather collection will resume tomorrow."
                ),
                raw_payload={"used_today": used_today, "daily_budget": daily_budget},
            )
            await self.record_job_run(result)
            return result
        try:
            result = await TencentWeatherCollector().collect_live(
                city_adcode=city_adcode,
                geo_scope=geo_scope,
            )
        except Exception as exc:
            result = CollectorResult(
                source_code="tencent_map_weather",
                status="error",
                error_message=f"Tencent weather collection failed: {exc}",
            )
        result.rows_upserted = await self.upsert_observations(result.observations)
        await self.record_job_run(result)
        return result

    async def list_sources(self) -> List[dict]:
        pool = await get_pg_pool()
        if pool is None:
            raise RuntimeError("PostgreSQL pool is not available")
        async with pool.acquire() as conn:
            rows = await conn.fetch(
                """
                SELECT source_code, source_name, source_type, access_mode,
                       compliance_level, base_url, requires_api_key,
                       refresh_interval_hours, raw_review_allowed, enabled,
                       notes, updated_at
                  FROM external_benchmark_source
                 ORDER BY source_code
                """
            )
        return [dict(row) for row in rows]

    async def list_segment_profiles(
        self,
        *,
        profile_type: Optional[str] = None,
        profile_code: Optional[str] = None,
    ) -> List[dict]:
        pool = await get_pg_pool()
        if pool is None:
            raise RuntimeError("PostgreSQL pool is not available")
        filters = []
        params = []
        if profile_type:
            params.append(profile_type)
            filters.append(f"profile_type = ${len(params)}")
        if profile_code:
            params.append(profile_code)
            filters.append(f"profile_code = ${len(params)}")
        where = f"WHERE {' AND '.join(filters)}" if filters else ""
        sql = f"""
            SELECT profile_code, profile_type, display_name, description,
                   dimension_weights, external_signal_plan, analysis_questions,
                   action_templates, source_code, updated_at
              FROM external_benchmark_segment_profile
              {where}
             ORDER BY profile_type, profile_code
        """
        async with pool.acquire() as conn:
            rows = await conn.fetch(sql, *params)
        return [dict(row) for row in rows]

    async def list_observations(
        self,
        *,
        benchmark_domain: str = "restaurant",
        metric_code: Optional[str] = None,
        geo_scope: Optional[str] = None,
        category_scope: Optional[str] = None,
        limit: int = 200,
    ) -> List[dict]:
        pool = await get_pg_pool()
        if pool is None:
            raise RuntimeError("PostgreSQL pool is not available")
        filters = ["benchmark_domain = $1"]
        params = [benchmark_domain]
        if metric_code:
            params.append(metric_code)
            filters.append(f"metric_code = ${len(params)}")
        if geo_scope:
            params.append(geo_scope)
            filters.append(f"geo_scope = ${len(params)}")
        if category_scope:
            params.append(category_scope)
            filters.append(f"category_scope = ${len(params)}")
        params.append(max(1, min(limit, 500)))
        sql = f"""
            SELECT factory_id, source_code, metric_code, metric_name,
                   metric_value, metric_unit, dimension, geo_scope, category_scope,
                   period_start, period_end, confidence_score, confidence_label,
                   source_url, source_title, collected_at
              FROM external_benchmark_observation
             WHERE {' AND '.join(filters)}
             ORDER BY period_end DESC, confidence_score DESC, source_code
             LIMIT ${len(params)}
        """
        async with pool.acquire() as conn:
            rows = await conn.fetch(sql, *params)
        return [dict(row) for row in rows]


service = ExternalBenchmarkService()
