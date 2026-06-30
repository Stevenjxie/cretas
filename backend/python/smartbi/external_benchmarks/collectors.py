from __future__ import annotations

import hashlib
import json
import os
import re
import calendar
from dataclasses import dataclass, field
from datetime import date, datetime, timezone
from html import unescape
from typing import Any, Dict, Iterable, List, Optional
from urllib.parse import urlencode

import httpx

from .sources import assert_source_allowed_for_collection


def redact_sensitive_url(url: str) -> str:
    return re.sub(r"([?&](?:key|sig)=)[^&]+", r"\1<redacted>", url, flags=re.I)


@dataclass(frozen=True)
class ExternalObservation:
    source_code: str
    metric_code: str
    metric_name: str
    metric_value: float
    metric_unit: str
    benchmark_domain: str = "restaurant"
    factory_id: str = "GLOBAL"
    dimension: Dict[str, Any] = field(default_factory=dict)
    geo_scope: str = "China"
    category_scope: str = "catering"
    period_start: date = date(1970, 1, 1)
    period_end: date = date(1970, 12, 31)
    confidence_score: float = 0.75
    confidence_label: str = "public_signal"
    source_url: str = ""
    source_title: str = ""
    collected_at: datetime = field(default_factory=lambda: datetime.now(timezone.utc))
    expires_at: Optional[datetime] = None
    raw_payload: Dict[str, Any] = field(default_factory=dict)

    @property
    def dimension_hash(self) -> str:
        payload = json.dumps(self.dimension, sort_keys=True, ensure_ascii=True, separators=(",", ":"))
        return hashlib.sha256(payload.encode("utf-8")).hexdigest()


@dataclass
class CollectorResult:
    source_code: str
    status: str
    observations: List[ExternalObservation] = field(default_factory=list)
    rows_upserted: int = 0
    request_url: str = ""
    error_message: str = ""
    raw_payload: Dict[str, Any] = field(default_factory=dict)


def _plain_text_from_html(html: str) -> str:
    text = re.sub(r"<script\b[^>]*>.*?</script>", " ", html, flags=re.I | re.S)
    text = re.sub(r"<style\b[^>]*>.*?</style>", " ", text, flags=re.I | re.S)
    text = re.sub(r"<[^>]+>", " ", text)
    text = unescape(text)
    return re.sub(r"\s+", " ", text).strip()


def _month_end(year: int, month: int) -> date:
    return date(year, month, calendar.monthrange(year, month)[1])


def _period_from_label(label: str, fallback_year: int) -> tuple[date, date]:
    year_match = re.search(r"(\d{4})\s*\u5e74", label)
    year = int(year_match.group(1)) if year_match else fallback_year
    month_numbers = [int(m) for m in re.findall(r"(\d{1,2})\s*\u6708", label)]
    range_match = re.search(r"1[\u2014\u81f3\-](\d{1,2})\u6708", label)
    if "\u5168\u5e74" in label or not month_numbers:
        return date(year, 1, 1), date(year, 12, 31)
    if range_match:
        return date(year, 1, 1), _month_end(year, int(range_match.group(1)))
    month = month_numbers[-1]
    return date(year, month, 1), _month_end(year, month)


def parse_nbs_catering_text(text: str, *, source_url: str, source_title: str, fallback_year: int) -> List[ExternalObservation]:
    """Extract official catering-income metrics from NBS release text.

    The parser intentionally emits nothing if the expected aggregate wording is
    not present; it never invents fallback values.
    """
    normalized = re.sub(r"\s+", "", text.replace(",", ""))
    label = r"(?P<label>(?:\d{4}\u5e74)?(?:1[\u2014\u81f3\-]\d{1,2}\u6708|\d{1,2}\u6708\u4efd|\d{1,2}\u6708|\u5168\u5e74))"
    value = r"\u9910\u996e\u6536\u5165(?P<amount>\d+(?:\.\d+)?)\u4ebf\u5143"
    yoy = r"(?P<direction>\u589e\u957f|\u4e0b\u964d)(?P<yoy>\d+(?:\.\d+)?)%"
    pattern = re.compile(label + r".{0,40}?" + value + r".{0,24}?" + yoy)

    observations: List[ExternalObservation] = []
    seen = set()
    for match in pattern.finditer(normalized):
        period_start, period_end = _period_from_label(match.group("label"), fallback_year)
        key = (period_start, period_end, match.group("amount"), match.group("yoy"))
        if key in seen:
            continue
        seen.add(key)

        amount = float(match.group("amount"))
        yoy_value = float(match.group("yoy"))
        if match.group("direction") == "\u4e0b\u964d":
            yoy_value = -yoy_value
        dimension = {"period_label": match.group("label"), "source_kind": "official_release"}

        observations.append(ExternalObservation(
            source_code="nbs_catering_retail",
            metric_code="catering_revenue",
            metric_name="Catering income",
            metric_value=amount,
            metric_unit="100m_CNY",
            period_start=period_start,
            period_end=period_end,
            confidence_score=0.98,
            confidence_label="official",
            source_url=source_url,
            source_title=source_title,
            dimension=dimension,
            raw_payload={"label": match.group("label")},
        ))
        observations.append(ExternalObservation(
            source_code="nbs_catering_retail",
            metric_code="catering_revenue_yoy",
            metric_name="Catering income YoY",
            metric_value=yoy_value,
            metric_unit="pct",
            period_start=period_start,
            period_end=period_end,
            confidence_score=0.98,
            confidence_label="official",
            source_url=source_url,
            source_title=source_title,
            dimension=dimension,
            raw_payload={"label": match.group("label")},
        ))
    return observations


class NbsCateringStatsCollector:
    source_code = "nbs_catering_retail"

    def __init__(self, urls: Optional[Iterable[str]] = None):
        self.urls = list(urls or [
            "https://www.stats.gov.cn/sj/zxfb/202606/t20260616_1963949.html",
            "https://www.stats.gov.cn/xxgk/sjfb/zxfb2020/202601/t20260119_1962323.html",
        ])

    async def collect(self) -> CollectorResult:
        observations: List[ExternalObservation] = []
        async with httpx.AsyncClient(timeout=12.0, follow_redirects=True) as client:
            for url in self.urls:
                assert_source_allowed_for_collection(self.source_code, url)
                response = await client.get(url)
                response.raise_for_status()
                title_match = re.search(r"<title[^>]*>(.*?)</title>", response.text, flags=re.I | re.S)
                title = _plain_text_from_html(title_match.group(1)) if title_match else url
                text = _plain_text_from_html(response.text)
                year_match = re.search(r"(20\d{2})", title + " " + url)
                fallback_year = int(year_match.group(1)) if year_match else datetime.now().year
                observations.extend(parse_nbs_catering_text(
                    text,
                    source_url=url,
                    source_title=title,
                    fallback_year=fallback_year,
                ))
        return CollectorResult(
            source_code=self.source_code,
            status="success" if observations else "empty",
            observations=observations,
            request_url=",".join(self.urls),
            raw_payload={"url_count": len(self.urls)},
        )


class IndustryReportSeedCollector:
    source_code = "industry_report_seed"

    def collect(self) -> CollectorResult:
        observations = [
            ExternalObservation(
                source_code="nbs_catering_retail",
                metric_code="catering_revenue",
                metric_name="National catering income 2025",
                metric_value=57982.0,
                metric_unit="100m_CNY",
                period_start=date(2025, 1, 1),
                period_end=date(2025, 12, 31),
                confidence_score=0.98,
                confidence_label="official",
                source_url="https://www.stats.gov.cn/xxgk/sjfb/zxfb2020/202601/t20260119_1962323.html",
                source_title="2025 retail sales of consumer goods release",
                dimension={"source_kind": "official_release"},
            ),
            ExternalObservation(
                source_code="nbs_catering_retail",
                metric_code="catering_revenue_yoy",
                metric_name="National catering income YoY 2025",
                metric_value=3.2,
                metric_unit="pct",
                period_start=date(2025, 1, 1),
                period_end=date(2025, 12, 31),
                confidence_score=0.98,
                confidence_label="official",
                source_url="https://www.stats.gov.cn/xxgk/sjfb/zxfb2020/202601/t20260119_1962323.html",
                source_title="2025 retail sales of consumer goods release",
                dimension={"source_kind": "official_release"},
            ),
            ExternalObservation(
                source_code="nbs_catering_retail",
                metric_code="catering_revenue",
                metric_name="National catering income Jan-May 2026",
                metric_value=23488.0,
                metric_unit="100m_CNY",
                period_start=date(2026, 1, 1),
                period_end=date(2026, 5, 31),
                confidence_score=0.98,
                confidence_label="official",
                source_url="https://www.stats.gov.cn/sj/zxfb/202606/t20260616_1963949.html",
                source_title="May 2026 retail sales of consumer goods release",
                dimension={"source_kind": "official_release"},
            ),
            ExternalObservation(
                source_code="nbs_catering_retail",
                metric_code="catering_revenue_yoy",
                metric_name="National catering income YoY Jan-May 2026",
                metric_value=3.1,
                metric_unit="pct",
                period_start=date(2026, 1, 1),
                period_end=date(2026, 5, 31),
                confidence_score=0.98,
                confidence_label="official",
                source_url="https://www.stats.gov.cn/sj/zxfb/202606/t20260616_1963949.html",
                source_title="May 2026 retail sales of consumer goods release",
                dimension={"source_kind": "official_release"},
            ),
            ExternalObservation(
                source_code="ccfa_catering_chain_2025",
                metric_code="restaurant_chain_rate",
                metric_name="China restaurant chain rate",
                metric_value=23.0,
                metric_unit="pct",
                period_start=date(2024, 1, 1),
                period_end=date(2024, 12, 31),
                confidence_score=0.86,
                confidence_label="report_excerpt",
                source_url="https://www.ccfa.org.cn/portal/cn/xiangxi.jsp?id=446601&sharetype=1&type=33",
                source_title="2025 China catering chain development whitepaper",
                dimension={"source_kind": "industry_report", "benchmark_use": "chain_operation"},
            ),
        ]
        return CollectorResult(source_code=self.source_code, status="success", observations=observations)


class AmapPoiCollector:
    source_code = "amap_poi_search"

    def __init__(self, api_key: Optional[str] = None, client: Optional[httpx.AsyncClient] = None):
        self.api_key = api_key if api_key is not None else os.getenv("AMAP_API_KEY")
        self.client = client

    @staticmethod
    def build_around_url(*, api_key: str, location: str, keywords: str, radius: int = 3000) -> str:
        params = {
            "key": api_key,
            "location": location,
            "keywords": keywords,
            "radius": str(radius),
            "offset": "25",
            "page": "1",
            "extensions": "base",
        }
        query = urlencode(params)
        return f"https://restapi.amap.com/v3/place/around?{query}"

    async def collect_density(self, *, location: str, keywords: str, radius: int = 3000, geo_scope: str = "local") -> CollectorResult:
        if not self.api_key:
            return CollectorResult(
                source_code=self.source_code,
                status="skipped",
                error_message="AMAP_API_KEY is not configured; official API collection skipped.",
            )
        url = self.build_around_url(api_key=self.api_key, location=location, keywords=keywords, radius=radius)
        assert_source_allowed_for_collection(self.source_code, url)
        close_client = False
        client = self.client
        if client is None:
            client = httpx.AsyncClient(timeout=10.0)
            close_client = True
        try:
            response = await client.get(url)
            response.raise_for_status()
            payload = response.json()
        finally:
            if close_client:
                await client.aclose()

        if str(payload.get("status")) != "1":
            return CollectorResult(
                source_code=self.source_code,
                status="error",
                request_url=redact_sensitive_url(url),
                error_message=f"Amap API error: {payload.get('info') or payload.get('infocode') or 'unknown'}",
                raw_payload={
                    "status": payload.get("status"),
                    "info": payload.get("info"),
                    "infocode": payload.get("infocode"),
                },
            )

        count = int(payload.get("count") or 0)
        observation = ExternalObservation(
            source_code=self.source_code,
            metric_code="poi_competitor_count",
            metric_name="Nearby competitor POI count",
            metric_value=float(count),
            metric_unit="count",
            geo_scope=geo_scope,
            category_scope=keywords,
            period_start=date.today(),
            period_end=date.today(),
            confidence_score=0.72,
            confidence_label="public_signal",
            source_url="https://lbs.amap.com/api/webservice/guide/api/search",
            source_title="Amap official POI search API",
            dimension={"location": location, "radius_m": radius, "keywords": keywords},
            raw_payload={"status": payload.get("status"), "info": payload.get("info"), "count": payload.get("count")},
        )
        return CollectorResult(
            source_code=self.source_code,
            status="success",
            observations=[observation],
            request_url=redact_sensitive_url(url),
        )
