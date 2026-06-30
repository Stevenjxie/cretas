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
from urllib.parse import urlencode, urljoin

import httpx

from .sources import assert_source_allowed_for_collection


def redact_sensitive_url(url: str) -> str:
    return re.sub(r"([?&](?:key|ak|sig)=)[^&]+", r"\1<redacted>", url, flags=re.I)


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


def _clean_cn_number_spacing(text: str) -> str:
    return re.sub(r"(?<=\d)\s+(?=\d)", "", text)


def _parse_change_pct(text: str) -> Optional[float]:
    if "持平" in text:
        return 0.0
    match = re.search(r"(?:上升|增长|上涨|下降)(\d+(?:\.\d+)?)%", text)
    if not match:
        return None
    value = float(match.group(1))
    if "下降" in text:
        return -value
    return value


def _parse_change_points(text: str) -> Optional[float]:
    if "持平" in text:
        return 0.0
    match = re.search(r"(?:上升|增长|上涨|下降)\s*(\d+(?:\.\d+)?)\s*个点", text)
    if not match:
        return None
    value = float(match.group(1))
    if "下降" in text:
        return -value
    return value


def parse_moa_wholesale_text(
    text: str,
    *,
    source_url: str,
    source_title: str,
    fallback_year: int,
) -> List[ExternalObservation]:
    normalized = _clean_cn_number_spacing(re.sub(r"\s+", " ", text)).strip()
    date_match = re.search(r"日期[:：]\s*(20\d{2})-(\d{2})-(\d{2})", normalized)
    if date_match:
        report_date = date(int(date_match.group(1)), int(date_match.group(2)), int(date_match.group(3)))
    else:
        title_date = re.search(r"(\d{1,2})月(\d{1,2})日", source_title)
        if not title_date:
            return []
        report_date = date(fallback_year, int(title_date.group(1)), int(title_date.group(2)))

    observations: List[ExternalObservation] = []
    index_patterns = [
        (
            "agri_wholesale_200_index",
            "Agricultural wholesale price 200 index",
            r"农产品批发价格200指数\s*[”\"]?\s*为\s*(\d+(?:\.\d+)?)",
        ),
        (
            "basket_product_index",
            "Basket product wholesale price index",
            r"菜篮子\s*[”\"]?\s*产品批发价格指数\s*为\s*(\d+(?:\.\d+)?)",
        ),
    ]
    for metric_code, metric_name, pattern in index_patterns:
        match = re.search(pattern, normalized)
        if not match:
            continue
        window = normalized[match.start(): match.start() + 80]
        observations.append(ExternalObservation(
            source_code="moa_wholesale_price_daily",
            metric_code=metric_code,
            metric_name=metric_name,
            metric_value=float(match.group(1)),
            metric_unit="index_point",
            period_start=report_date,
            period_end=report_date,
            confidence_score=0.98,
            confidence_label="official",
            source_url=source_url,
            source_title=source_title,
            category_scope="ingredient_cost",
            dimension={"source_kind": "official_daily_monitor"},
            raw_payload={"daily_change_points": _parse_change_points(window)},
        ))

    ingredient_aliases = {
        "猪肉": ("pork", "Pork wholesale average price"),
        "牛肉": ("beef", "Beef wholesale average price"),
        "羊肉": ("mutton", "Mutton wholesale average price"),
        "鸡蛋": ("egg", "Egg wholesale average price"),
        "白条鸡": ("whole_chicken", "Whole chicken wholesale average price"),
        "重点监测的28种蔬菜平均价格": ("vegetable_28_avg", "28 monitored vegetables average wholesale price"),
        "重点监测的6种水果平均价格": ("fruit_6_avg", "6 monitored fruits average wholesale price"),
        "鲫鱼": ("crucian_carp", "Crucian carp wholesale average price"),
        "鲤鱼": ("carp", "Carp wholesale average price"),
        "白鲢鱼": ("silver_carp", "Silver carp wholesale average price"),
        "大带鱼": ("large_hairtail", "Large hairtail wholesale average price"),
    }
    name_pattern = "|".join(re.escape(name) for name in sorted(ingredient_aliases, key=len, reverse=True))
    for match in re.finditer(rf"({name_pattern})(?:平均价格)?为?(\d+(?:\.\d+)?)元/公斤(?:，([^。；;]*?))?(?:；|。|$)", normalized):
        cn_name = match.group(1)
        ingredient_code, metric_name = ingredient_aliases[cn_name]
        change_text = match.group(3) or ""
        observations.append(ExternalObservation(
            source_code="moa_wholesale_price_daily",
            metric_code="ingredient_wholesale_price",
            metric_name=metric_name,
            metric_value=float(match.group(2)),
            metric_unit="CNY_per_kg",
            period_start=report_date,
            period_end=report_date,
            confidence_score=0.98,
            confidence_label="official",
            source_url=source_url,
            source_title=source_title,
            category_scope="ingredient_cost",
            dimension={
                "ingredient_code": ingredient_code,
                "ingredient_name": cn_name,
                "source_kind": "official_daily_monitor",
            },
            raw_payload={"daily_change_pct": _parse_change_pct(change_text)},
        ))
    return observations


class MoaWholesalePriceCollector:
    source_code = "moa_wholesale_price_daily"

    def __init__(self, index_url: str = "https://scs.moa.gov.cn/"):
        self.index_url = index_url

    async def collect(self) -> CollectorResult:
        async with httpx.AsyncClient(timeout=12.0, follow_redirects=True) as client:
            assert_source_allowed_for_collection(self.source_code, self.index_url)
            index_response = await client.get(self.index_url)
            index_response.raise_for_status()
            latest_url = self._find_latest_article_url(index_response.text)
            if not latest_url:
                return CollectorResult(
                    source_code=self.source_code,
                    status="empty",
                    request_url=self.index_url,
                    error_message="No MOA wholesale price article link found.",
                )
            assert_source_allowed_for_collection(self.source_code, latest_url)
            article_response = await client.get(latest_url)
            article_response.raise_for_status()

        title_match = re.search(r"<title[^>]*>(.*?)</title>", article_response.text, flags=re.I | re.S)
        h1_match = re.search(r"<h1[^>]*>(.*?)</h1>", article_response.text, flags=re.I | re.S)
        title = _plain_text_from_html((h1_match or title_match).group(1)) if (h1_match or title_match) else latest_url
        fallback_year_match = re.search(r"/(20\d{2})\d{2}/", latest_url)
        fallback_year = int(fallback_year_match.group(1)) if fallback_year_match else datetime.now().year
        observations = parse_moa_wholesale_text(
            _plain_text_from_html(article_response.text),
            source_url=latest_url,
            source_title=title,
            fallback_year=fallback_year,
        )
        return CollectorResult(
            source_code=self.source_code,
            status="success" if observations else "empty",
            observations=observations,
            request_url=latest_url,
            raw_payload={"observation_count": len(observations)},
        )

    def _find_latest_article_url(self, html: str) -> Optional[str]:
        for match in re.finditer(r"<a\b[^>]*href=[\"'](?P<href>[^\"']+)[\"'][^>]*>(?P<label>.*?)</a>", html, flags=re.I | re.S):
            label = _plain_text_from_html(match.group("label"))
            if "农产品批发价格200指数" in label:
                return urljoin(self.index_url, match.group("href"))
        return None


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
            "extensions": "all",
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
        observations = [ExternalObservation(
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
            raw_payload={
                "status": payload.get("status"),
                "info": payload.get("info"),
                "count": payload.get("count"),
                "returned_poi_count": len(payload.get("pois") or []),
            },
        )]

        ratings: List[float] = []
        costs: List[float] = []
        for poi in payload.get("pois") or []:
            biz_ext = poi.get("biz_ext") if isinstance(poi, dict) else None
            if not isinstance(biz_ext, dict):
                continue
            rating = _safe_float(biz_ext.get("rating"))
            cost = _safe_float(biz_ext.get("cost"))
            if rating is not None and rating > 0:
                ratings.append(rating)
            if cost is not None and cost > 0:
                costs.append(cost)

        if ratings:
            observations.append(ExternalObservation(
                source_code=self.source_code,
                metric_code="poi_avg_public_rating",
                metric_name="Nearby POI average public rating",
                metric_value=round(sum(ratings) / len(ratings), 4),
                metric_unit="score",
                geo_scope=geo_scope,
                category_scope=keywords,
                period_start=date.today(),
                period_end=date.today(),
                confidence_score=0.68,
                confidence_label="public_signal",
                source_url="https://lbs.amap.com/api/webservice/guide/api/search",
                source_title="Amap official POI search API",
                dimension={"location": location, "radius_m": radius, "keywords": keywords, "sample_count": len(ratings)},
                raw_payload={"sample_count": len(ratings)},
            ))
        if costs:
            observations.append(ExternalObservation(
                source_code=self.source_code,
                metric_code="poi_avg_public_cost",
                metric_name="Nearby POI average public cost",
                metric_value=round(sum(costs) / len(costs), 4),
                metric_unit="CNY_per_person",
                geo_scope=geo_scope,
                category_scope=keywords,
                period_start=date.today(),
                period_end=date.today(),
                confidence_score=0.62,
                confidence_label="public_signal",
                source_url="https://lbs.amap.com/api/webservice/guide/api/search",
                source_title="Amap official POI search API",
                dimension={"location": location, "radius_m": radius, "keywords": keywords, "sample_count": len(costs)},
                raw_payload={"sample_count": len(costs)},
            ))
        return CollectorResult(
            source_code=self.source_code,
            status="success",
            observations=observations,
            request_url=redact_sensitive_url(url),
        )


class AmapWeatherCollector:
    source_code = "amap_weather"

    def __init__(self, api_key: Optional[str] = None, client: Optional[httpx.AsyncClient] = None):
        self.api_key = api_key if api_key is not None else os.getenv("AMAP_API_KEY")
        self.client = client

    @staticmethod
    def build_weather_url(*, api_key: str, city_adcode: str, extensions: str = "base") -> str:
        params = {
            "key": api_key,
            "city": city_adcode,
            "extensions": extensions,
            "output": "JSON",
        }
        return f"https://restapi.amap.com/v3/weather/weatherInfo?{urlencode(params)}"

    async def collect_live(self, *, city_adcode: str, geo_scope: str = "local") -> CollectorResult:
        if not self.api_key:
            return CollectorResult(
                source_code=self.source_code,
                status="skipped",
                error_message="AMAP_API_KEY is not configured; official weather collection skipped.",
            )
        url = self.build_weather_url(api_key=self.api_key, city_adcode=city_adcode)
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
                error_message=f"Amap weather API error: {payload.get('info') or payload.get('infocode') or 'unknown'}",
                raw_payload={
                    "status": payload.get("status"),
                    "info": payload.get("info"),
                    "infocode": payload.get("infocode"),
                },
            )
        lives = payload.get("lives") or []
        if not lives:
            return CollectorResult(
                source_code=self.source_code,
                status="empty",
                request_url=redact_sensitive_url(url),
                raw_payload={"status": payload.get("status"), "info": payload.get("info"), "count": payload.get("count")},
            )
        live = lives[0]
        report_dt = _parse_amap_report_date(live.get("reporttime"))
        period = report_dt.date()
        common_dimension = {
            "adcode": live.get("adcode") or city_adcode,
            "province": live.get("province"),
            "city": live.get("city"),
            "weather": live.get("weather"),
            "winddirection": live.get("winddirection"),
            "source_kind": "official_weather",
        }
        observations: List[ExternalObservation] = []
        temperature = _safe_float(live.get("temperature"))
        humidity = _safe_float(live.get("humidity"))
        wind_power = _first_number(live.get("windpower"))
        metric_values = [
            ("weather_temperature", "Live weather temperature", temperature, "celsius"),
            ("weather_humidity", "Live weather humidity", humidity, "pct"),
            ("weather_windpower", "Live weather wind power", wind_power, "level"),
        ]
        for metric_code, metric_name, value, unit in metric_values:
            if value is None:
                continue
            observations.append(ExternalObservation(
                source_code=self.source_code,
                metric_code=metric_code,
                metric_name=metric_name,
                metric_value=value,
                metric_unit=unit,
                geo_scope=geo_scope,
                category_scope="trade_area_context",
                period_start=period,
                period_end=period,
                confidence_score=0.90,
                confidence_label="public_signal",
                source_url="https://lbs.amap.com/api/webservice/guide/api/weatherinfo",
                source_title="Amap official weather API",
                dimension=common_dimension,
                raw_payload={"reporttime": live.get("reporttime")},
            ))
        return CollectorResult(
            source_code=self.source_code,
            status="success" if observations else "empty",
            observations=observations,
            request_url=redact_sensitive_url(url),
            raw_payload={"observation_count": len(observations)},
        )


class TencentPlaceCollector:
    source_code = "tencent_map_place_search"

    def __init__(self, api_key: Optional[str] = None, client: Optional[httpx.AsyncClient] = None):
        self.api_key = api_key if api_key is not None else os.getenv("TENCENT_MAP_KEY")
        self.client = client

    @staticmethod
    def build_nearby_url(*, api_key: str, location: str, keywords: str, radius: int = 3000) -> str:
        lat_lng = _lonlat_to_tencent_latlng(location)
        params = {
            "key": api_key,
            "keyword": keywords,
            "boundary": f"nearby({lat_lng},{radius})",
            "page_size": "20",
            "page_index": "1",
            "orderby": "_distance",
        }
        return f"https://apis.map.qq.com/ws/place/v1/search?{urlencode(params)}"

    async def collect_density(
        self,
        *,
        location: str,
        keywords: str,
        radius: int = 3000,
        geo_scope: str = "local",
    ) -> CollectorResult:
        if not self.api_key:
            return CollectorResult(
                source_code=self.source_code,
                status="skipped",
                error_message="TENCENT_MAP_KEY is not configured; official Tencent place collection skipped.",
            )
        url = self.build_nearby_url(
            api_key=self.api_key,
            location=location,
            keywords=keywords,
            radius=radius,
        )
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

        if int(payload.get("status", -1)) != 0:
            return CollectorResult(
                source_code=self.source_code,
                status="error",
                request_url=redact_sensitive_url(url),
                error_message=f"Tencent place API error: {payload.get('message') or payload.get('status') or 'unknown'}",
                raw_payload={"status": payload.get("status"), "message": payload.get("message")},
            )

        count = int(payload.get("count") or 0)
        observations = [ExternalObservation(
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
            source_url="https://lbs.qq.com/webservice_v1/guide-search.html",
            source_title="Tencent Location Service place search API",
            dimension={"location": location, "radius_m": radius, "keywords": keywords, "provider": "tencent"},
            raw_payload={
                "status": payload.get("status"),
                "message": payload.get("message"),
                "count": payload.get("count"),
                "returned_poi_count": len(payload.get("data") or []),
            },
        )]

        ratings: List[float] = []
        costs: List[float] = []
        for poi in payload.get("data") or []:
            if not isinstance(poi, dict):
                continue
            rating = _safe_float(poi.get("_distance"))  # kept as distance signal if no rating exists
            avg_price = _safe_float(poi.get("avg_price") or poi.get("price"))
            if avg_price is not None and avg_price > 0:
                costs.append(avg_price)
            if rating is not None and rating >= 0:
                # Tencent place search reliably returns distance; this is not a score.
                pass

        if costs:
            observations.append(ExternalObservation(
                source_code=self.source_code,
                metric_code="poi_avg_public_cost",
                metric_name="Nearby POI average public cost",
                metric_value=round(sum(costs) / len(costs), 4),
                metric_unit="CNY_per_person",
                geo_scope=geo_scope,
                category_scope=keywords,
                period_start=date.today(),
                period_end=date.today(),
                confidence_score=0.60,
                confidence_label="public_signal",
                source_url="https://lbs.qq.com/webservice_v1/guide-search.html",
                source_title="Tencent Location Service place search API",
                dimension={"location": location, "radius_m": radius, "keywords": keywords, "sample_count": len(costs), "provider": "tencent"},
                raw_payload={"sample_count": len(costs)},
            ))

        return CollectorResult(
            source_code=self.source_code,
            status="success",
            observations=observations,
            request_url=redact_sensitive_url(url),
        )


class TencentWeatherCollector:
    source_code = "tencent_map_weather"

    def __init__(self, api_key: Optional[str] = None, client: Optional[httpx.AsyncClient] = None):
        self.api_key = api_key if api_key is not None else os.getenv("TENCENT_MAP_KEY")
        self.client = client

    @staticmethod
    def build_weather_url(*, api_key: str, city_adcode: str) -> str:
        params = {"key": api_key, "adcode": city_adcode}
        return f"https://apis.map.qq.com/ws/weather/v1/?{urlencode(params)}"

    async def collect_live(self, *, city_adcode: str, geo_scope: str = "local") -> CollectorResult:
        if not self.api_key:
            return CollectorResult(
                source_code=self.source_code,
                status="skipped",
                error_message="TENCENT_MAP_KEY is not configured; official Tencent weather collection skipped.",
            )
        url = self.build_weather_url(api_key=self.api_key, city_adcode=city_adcode)
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

        if int(payload.get("status", -1)) != 0:
            return CollectorResult(
                source_code=self.source_code,
                status="error",
                request_url=redact_sensitive_url(url),
                error_message=f"Tencent weather API error: {payload.get('message') or payload.get('status') or 'unknown'}",
                raw_payload={"status": payload.get("status"), "message": payload.get("message")},
            )

        result = payload.get("result") or {}
        realtime_payload = result.get("realtime") or result.get("now") or {}
        if isinstance(realtime_payload, list):
            realtime = next((item for item in realtime_payload if isinstance(item, dict)), {})
        elif isinstance(realtime_payload, dict):
            realtime = realtime_payload
        else:
            realtime = {}
        infos = realtime.get("infos") if isinstance(realtime.get("infos"), dict) else {}
        weather_values = {**realtime, **infos}
        report_dt = _parse_amap_report_date(weather_values.get("update_time") or result.get("update_time"))
        period = report_dt.date()
        common_dimension = {
            "adcode": realtime.get("adcode") or city_adcode,
            "province": realtime.get("province"),
            "city": realtime.get("city"),
            "district": realtime.get("district"),
            "weather": weather_values.get("weather") or weather_values.get("condition"),
            "winddirection": weather_values.get("wind_direction") or weather_values.get("wind_dir"),
            "source_kind": "official_weather",
            "provider": "tencent",
        }
        metric_values = [
            ("weather_temperature", "Live weather temperature", _safe_float(weather_values.get("temperature") or weather_values.get("temp")), "celsius"),
            ("weather_humidity", "Live weather humidity", _safe_float(weather_values.get("humidity")), "pct"),
            ("weather_windpower", "Live weather wind power", _first_number(weather_values.get("wind_power") or weather_values.get("wind_scale")), "level"),
        ]
        observations: List[ExternalObservation] = []
        for metric_code, metric_name, value, unit in metric_values:
            if value is None:
                continue
            observations.append(ExternalObservation(
                source_code=self.source_code,
                metric_code=metric_code,
                metric_name=metric_name,
                metric_value=value,
                metric_unit=unit,
                geo_scope=geo_scope,
                category_scope="trade_area_context",
                period_start=period,
                period_end=period,
                confidence_score=0.88,
                confidence_label="public_signal",
                source_url="https://lbs.qq.com/service/webService/webServiceGuide/weatherinfo",
                source_title="Tencent Location Service weather API",
                dimension=common_dimension,
                raw_payload={"update_time": weather_values.get("update_time") or result.get("update_time")},
            ))
        return CollectorResult(
            source_code=self.source_code,
            status="success" if observations else "empty",
            observations=observations,
            request_url=redact_sensitive_url(url),
            raw_payload={"observation_count": len(observations)},
        )


class BaiduPlaceCollector:
    source_code = "baidu_map_place_search"

    def __init__(self, api_key: Optional[str] = None, client: Optional[httpx.AsyncClient] = None):
        self.api_key = api_key if api_key is not None else os.getenv("BAIDU_MAP_AK")
        self.client = client

    @staticmethod
    def build_nearby_url(*, api_key: str, location: str, keywords: str, radius: int = 3000) -> str:
        params = {
            "ak": api_key,
            "query": keywords,
            "location": _lonlat_to_baidu_latlng(location),
            "radius": str(radius),
            "output": "json",
            "scope": "2",
            "page_size": "20",
            "page_num": "0",
        }
        return f"https://api.map.baidu.com/place/v2/search?{urlencode(params)}"

    async def collect_density(
        self,
        *,
        location: str,
        keywords: str,
        radius: int = 3000,
        geo_scope: str = "local",
    ) -> CollectorResult:
        if not self.api_key:
            return CollectorResult(
                source_code=self.source_code,
                status="skipped",
                error_message="BAIDU_MAP_AK is not configured; official Baidu place collection skipped.",
            )
        url = self.build_nearby_url(
            api_key=self.api_key,
            location=location,
            keywords=keywords,
            radius=radius,
        )
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

        if int(payload.get("status", -1)) != 0:
            return CollectorResult(
                source_code=self.source_code,
                status="error",
                request_url=redact_sensitive_url(url),
                error_message=f"Baidu place API error: {payload.get('message') or payload.get('status') or 'unknown'}",
                raw_payload={"status": payload.get("status"), "message": payload.get("message")},
            )

        count = int(payload.get("total") or len(payload.get("results") or []))
        observations = [ExternalObservation(
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
            source_url="https://lbsyun.baidu.com/docs/webapi?title=placev2/guide/webservice-placeapi",
            source_title="Baidu Maps official Place API",
            dimension={"location": location, "radius_m": radius, "keywords": keywords, "provider": "baidu"},
            raw_payload={
                "status": payload.get("status"),
                "message": payload.get("message"),
                "total": payload.get("total"),
                "returned_poi_count": len(payload.get("results") or []),
            },
        )]

        ratings: List[float] = []
        costs: List[float] = []
        for poi in payload.get("results") or []:
            if not isinstance(poi, dict):
                continue
            detail = poi.get("detail_info") if isinstance(poi.get("detail_info"), dict) else {}
            rating = _safe_float(detail.get("overall_rating") or detail.get("overall_score"))
            cost = _safe_float(detail.get("price") or detail.get("avg_price") or detail.get("cost"))
            if rating is not None and rating > 0:
                ratings.append(rating)
            if cost is not None and cost > 0:
                costs.append(cost)

        if ratings:
            observations.append(ExternalObservation(
                source_code=self.source_code,
                metric_code="poi_avg_public_rating",
                metric_name="Nearby POI average public rating",
                metric_value=round(sum(ratings) / len(ratings), 4),
                metric_unit="score",
                geo_scope=geo_scope,
                category_scope=keywords,
                period_start=date.today(),
                period_end=date.today(),
                confidence_score=0.68,
                confidence_label="public_signal",
                source_url="https://lbsyun.baidu.com/docs/webapi?title=placev2/guide/webservice-placeapi",
                source_title="Baidu Maps official Place API",
                dimension={"location": location, "radius_m": radius, "keywords": keywords, "sample_count": len(ratings), "provider": "baidu"},
                raw_payload={"sample_count": len(ratings)},
            ))
        if costs:
            observations.append(ExternalObservation(
                source_code=self.source_code,
                metric_code="poi_avg_public_cost",
                metric_name="Nearby POI average public cost",
                metric_value=round(sum(costs) / len(costs), 4),
                metric_unit="CNY_per_person",
                geo_scope=geo_scope,
                category_scope=keywords,
                period_start=date.today(),
                period_end=date.today(),
                confidence_score=0.62,
                confidence_label="public_signal",
                source_url="https://lbsyun.baidu.com/docs/webapi?title=placev2/guide/webservice-placeapi",
                source_title="Baidu Maps official Place API",
                dimension={"location": location, "radius_m": radius, "keywords": keywords, "sample_count": len(costs), "provider": "baidu"},
                raw_payload={"sample_count": len(costs)},
            ))

        return CollectorResult(
            source_code=self.source_code,
            status="success",
            observations=observations,
            request_url=redact_sensitive_url(url),
        )


class BaiduWeatherCollector:
    source_code = "baidu_map_weather"

    def __init__(self, api_key: Optional[str] = None, client: Optional[httpx.AsyncClient] = None):
        self.api_key = api_key if api_key is not None else os.getenv("BAIDU_MAP_AK")
        self.client = client

    @staticmethod
    def build_weather_url(*, api_key: str, district_id: Optional[str] = None, location: Optional[str] = None) -> str:
        params = {
            "ak": api_key,
            "data_type": "all",
            "output": "json",
        }
        if district_id:
            params["district_id"] = district_id
        elif location:
            params["location"] = location
            params["coordtype"] = "gcj02"
        else:
            raise ValueError("district_id or location is required")
        return f"https://api.map.baidu.com/weather/v1/?{urlencode(params)}"

    async def collect_live(
        self,
        *,
        district_id: Optional[str] = None,
        location: Optional[str] = None,
        geo_scope: str = "local",
    ) -> CollectorResult:
        if not self.api_key:
            return CollectorResult(
                source_code=self.source_code,
                status="skipped",
                error_message="BAIDU_MAP_AK is not configured; official Baidu weather collection skipped.",
            )
        url = self.build_weather_url(api_key=self.api_key, district_id=district_id, location=location)
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

        status = int(payload.get("status", -1))
        if status not in {0, 200}:
            return CollectorResult(
                source_code=self.source_code,
                status="error",
                request_url=redact_sensitive_url(url),
                error_message=f"Baidu weather API error: {payload.get('message') or payload.get('status') or 'unknown'}",
                raw_payload={"status": payload.get("status"), "message": payload.get("message")},
            )

        result = payload.get("result") or {}
        now = result.get("now") or {}
        if not isinstance(now, dict) or not now:
            return CollectorResult(
                source_code=self.source_code,
                status="empty",
                request_url=redact_sensitive_url(url),
                raw_payload={"status": payload.get("status"), "message": payload.get("message")},
            )
        address = result.get("address") if isinstance(result.get("address"), dict) else {}
        report_dt = _parse_baidu_report_date(now.get("uptime"))
        period = report_dt.date()
        common_dimension = {
            "adcode": address.get("id") or district_id,
            "province": address.get("province"),
            "city": address.get("city"),
            "district": address.get("name"),
            "weather": now.get("text"),
            "winddirection": now.get("wind_dir"),
            "source_kind": "official_weather",
            "provider": "baidu",
        }
        metric_values = [
            ("weather_temperature", "Live weather temperature", _safe_float(now.get("temp")), "celsius"),
            ("weather_humidity", "Live weather humidity", _safe_float(now.get("rh")), "pct"),
            ("weather_windpower", "Live weather wind power", _first_number(now.get("wind_class")), "level"),
            ("weather_precipitation_1h", "Live one-hour precipitation", _safe_float(now.get("prec_1h")), "mm"),
            ("weather_aqi", "Live weather AQI", _safe_float(now.get("aqi")), "index"),
        ]
        observations: List[ExternalObservation] = []
        for metric_code, metric_name, value, unit in metric_values:
            if value is None or value == 999999:
                continue
            observations.append(ExternalObservation(
                source_code=self.source_code,
                metric_code=metric_code,
                metric_name=metric_name,
                metric_value=value,
                metric_unit=unit,
                geo_scope=geo_scope,
                category_scope="trade_area_context",
                period_start=period,
                period_end=period,
                confidence_score=0.88,
                confidence_label="public_signal",
                source_url="https://lbsyun.baidu.com/docs/webapi?title=weatherinquiry/weather/base",
                source_title="Baidu Maps official weather API",
                dimension=common_dimension,
                raw_payload={"uptime": now.get("uptime")},
            ))
        return CollectorResult(
            source_code=self.source_code,
            status="success" if observations else "empty",
            observations=observations,
            request_url=redact_sensitive_url(url),
            raw_payload={"observation_count": len(observations)},
        )


def _safe_float(value: Any) -> Optional[float]:
    if value in (None, "", []):
        return None
    try:
        return float(value)
    except (TypeError, ValueError):
        return None


def _first_number(value: Any) -> Optional[float]:
    if value in (None, "", []):
        return None
    if isinstance(value, str) and value.strip() in {"\u65e0\u98ce", "\u65e0\u98ce\u7ea7"}:
        return 0.0
    match = re.search(r"\d+(?:\.\d+)?", str(value))
    return float(match.group(0)) if match else None


def _parse_amap_report_date(value: Any) -> datetime:
    if isinstance(value, str):
        for fmt in ("%Y-%m-%d %H:%M:%S", "%Y-%m-%d %H:%M", "%Y%m%d%H%M%S"):
            try:
                return datetime.strptime(value, fmt).replace(tzinfo=timezone.utc)
            except ValueError:
                pass
    return datetime.now(timezone.utc)


def _parse_baidu_report_date(value: Any) -> datetime:
    return _parse_amap_report_date(value)


def _lonlat_to_tencent_latlng(location: str) -> str:
    parts = [p.strip() for p in location.split(",")]
    if len(parts) != 2:
        raise ValueError("location must be 'longitude,latitude'")
    lng = float(parts[0])
    lat = float(parts[1])
    return f"{lat:.6f},{lng:.6f}"


def _lonlat_to_baidu_latlng(location: str) -> str:
    return _lonlat_to_tencent_latlng(location)
