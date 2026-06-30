from __future__ import annotations

from dataclasses import dataclass
from typing import Dict, Iterable
from urllib.parse import urlparse


@dataclass(frozen=True)
class SourceDefinition:
    source_code: str
    source_name: str
    source_type: str
    access_mode: str
    compliance_level: str
    base_url: str
    requires_api_key: bool
    refresh_interval_hours: int
    notes: str
    raw_review_allowed: bool = False
    robots_respected: bool = True
    enabled: bool = True


PUBLIC_SOURCE_REGISTRY: Dict[str, SourceDefinition] = {
    "nbs_catering_retail": SourceDefinition(
        source_code="nbs_catering_retail",
        source_name="National Bureau of Statistics catering retail releases",
        source_type="official_stat",
        access_mode="open_web",
        compliance_level="public_aggregate",
        base_url="https://www.stats.gov.cn/",
        requires_api_key=False,
        refresh_interval_hours=24,
        notes="Official monthly/annual aggregate catering income and YoY statistics.",
    ),
    "ccfa_catering_chain_2025": SourceDefinition(
        source_code="ccfa_catering_chain_2025",
        source_name="CCFA / Meituan China catering chain whitepaper 2025",
        source_type="industry_report",
        access_mode="open_web",
        compliance_level="public_aggregate",
        base_url="https://www.ccfa.org.cn/",
        requires_api_key=False,
        refresh_interval_hours=720,
        notes="Public report excerpts used as national/category benchmark seeds.",
    ),
    "meituan_life_service_trends_2025": SourceDefinition(
        source_code="meituan_life_service_trends_2025",
        source_name="Meituan life service trend report 2025",
        source_type="industry_report",
        access_mode="open_web",
        compliance_level="public_aggregate",
        base_url="https://www.meituan.com/",
        requires_api_key=False,
        refresh_interval_hours=720,
        notes="Public aggregate trend signals, not raw shop comments.",
    ),
    "kpmg_fnb_enterprise_2025": SourceDefinition(
        source_code="kpmg_fnb_enterprise_2025",
        source_name="KPMG China food and beverage enterprise development report 2025",
        source_type="industry_report",
        access_mode="open_web",
        compliance_level="public_aggregate",
        base_url="https://assets.kpmg.com/",
        requires_api_key=False,
        refresh_interval_hours=720,
        notes="Third-party public report excerpts for macro and chain-operation benchmarks.",
    ),
    "amap_poi_search": SourceDefinition(
        source_code="amap_poi_search",
        source_name="Amap official POI Search API",
        source_type="public_poi",
        access_mode="official_api",
        compliance_level="public_aggregate",
        base_url="https://restapi.amap.com/",
        requires_api_key=True,
        refresh_interval_hours=24,
        notes="Official POI API used for competitor/store-density aggregates only.",
    ),
    "amap_weather": SourceDefinition(
        source_code="amap_weather",
        source_name="Amap official weather API",
        source_type="public_poi",
        access_mode="official_api",
        compliance_level="public_aggregate",
        base_url="https://restapi.amap.com/",
        requires_api_key=True,
        refresh_interval_hours=6,
        notes="Official weather API for trade-area operating context; no personal data.",
    ),
    "moa_wholesale_price_daily": SourceDefinition(
        source_code="moa_wholesale_price_daily",
        source_name="MOA daily agricultural wholesale price monitor",
        source_type="official_stat",
        access_mode="open_web",
        compliance_level="public_aggregate",
        base_url="https://scs.moa.gov.cn/",
        requires_api_key=False,
        refresh_interval_hours=24,
        notes="Daily wholesale price index and ingredient prices published by MOA market monitor.",
    ),
    "authorized_platform_export": SourceDefinition(
        source_code="authorized_platform_export",
        source_name="Authorized merchant platform export",
        source_type="authorized_export",
        access_mode="authorized_export",
        compliance_level="authorized",
        base_url="",
        requires_api_key=False,
        refresh_interval_hours=24,
        notes="Merchant-owned exports from Dianping/Meituan or other platforms.",
        raw_review_allowed=True,
    ),
    "internal_methodology_seed": SourceDefinition(
        source_code="internal_methodology_seed",
        source_name="Internal benchmark methodology seed",
        source_type="third_party",
        access_mode="manual_upload",
        compliance_level="internal_seed",
        base_url="",
        requires_api_key=False,
        refresh_interval_hours=720,
        notes="Non-sensitive category/channel evaluation methodology maintained by SmartBI.",
    ),
}


RAW_REVIEW_PLATFORM_DOMAINS = (
    "dianping.com",
    "meituan.com",
    "xiaohongshu.com",
    "douyin.com",
)


def source_rows() -> Iterable[dict]:
    for source in PUBLIC_SOURCE_REGISTRY.values():
        yield {
            "source_code": source.source_code,
            "source_name": source.source_name,
            "source_type": source.source_type,
            "access_mode": source.access_mode,
            "compliance_level": source.compliance_level,
            "base_url": source.base_url,
            "requires_api_key": source.requires_api_key,
            "refresh_interval_hours": source.refresh_interval_hours,
            "notes": source.notes,
            "raw_review_allowed": source.raw_review_allowed,
            "robots_respected": source.robots_respected,
            "enabled": source.enabled,
        }


def assert_source_allowed_for_collection(source_code: str, target_url: str, raw_reviews: bool = False) -> None:
    source = PUBLIC_SOURCE_REGISTRY[source_code]
    host = urlparse(target_url).hostname or ""
    host = host.lower()
    is_review_platform = any(host == d or host.endswith("." + d) for d in RAW_REVIEW_PLATFORM_DOMAINS)

    if raw_reviews and not source.raw_review_allowed:
        raise ValueError(f"source {source_code} is not allowed to collect raw platform reviews")

    if is_review_platform and source.access_mode not in {"authorized_export", "licensed_api", "official_api"}:
        raise ValueError(f"source {source_code} cannot crawl platform domain {host} without authorization")
