package com.cretas.aims.logistics.service.routing;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 高德地图 (Amap/AutoNavi) REST API 客户端 — 地理编码 + 驾车距离查询 (Phase 4, 2026-07-11)。
 *
 * <p><b>诚实降级铁律</b> (对齐 {@code LogisticsDistanceEdge} 类头 "公里数不伪造" +
 * {@code fool-proof-design.md} / "禁止降级处理"): key 未配置 / HTTP 请求失败 / 响应非
 * {@code status=1} / 超出每日查询预算 → 一律返回 {@link Optional#empty()}, 绝不返回
 * 0、猜测值或直线距离近似值。调用方 ({@link LogisticsRoutingService} /
 * {@code LogisticsOrderImportServiceImpl}) 必须把空结果当作"暂不可用", 保持
 * {@code NEEDS_ROUTE_DATA} / {@code UNRESOLVED} 状态, 不得静默降级为假数据。
 *
 * <p>凭证从环境变量 {@code AMAP_API_KEY} 经服务器 {@code .env.prod} 注入
 * {@code amap.api.key} 属性 (见 {@code application-pg.properties} /
 * {@code application-pg-prod.properties})。本类及其调用方绝不硬编码 key 值。
 */
@Slf4j
@Component
public class AmapClient {

    private static final String GEOCODE_URL = "https://restapi.amap.com/v3/geocode/geo";
    private static final String DISTANCE_URL = "https://restapi.amap.com/v3/distance";
    /** 默认查询城市 — 一加物流当前服务范围 (苏州)，缩小地理编码歧义。 */
    private static final String DEFAULT_CITY = "苏州";
    private static final long TIMEOUT_SECONDS = 8;
    private static final String STATUS_OK = "1";

    private final String apiKey;
    private final int dailyQueryBudget;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final OkHttpClient http;

    private final AtomicInteger dailyCallCount = new AtomicInteger(0);
    private volatile LocalDate budgetWindowDate = LocalDate.now();

    public AmapClient(
            @Value("${amap.api.key:}") String apiKey,
            @Value("${amap.daily-query-budget:800}") int dailyQueryBudget) {
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.dailyQueryBudget = dailyQueryBudget;
        this.http = new OkHttpClient.Builder()
                .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .writeTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .build();
        log.info("AmapClient initialized; enabled={} dailyQueryBudget={}", isEnabled(), dailyQueryBudget);
    }

    /** key 未配置 (空字符串) → 功能整体禁用, 所有查询方法立即短路返回 empty, 不发请求。 */
    public boolean isEnabled() {
        return !apiKey.isEmpty();
    }

    /**
     * 地理编码 — 地址 → {@code [lng, lat]}。
     *
     * @return 成功解析到坐标 → {@code Optional.of([lng, lat])}；key 未配置 / 地址为空 /
     *         请求失败 / 无匹配结果 → {@link Optional#empty()} (诚实降级, 调用方保持
     *         {@code UNRESOLVED}，绝不用猜测坐标)。
     */
    public Optional<double[]> geocode(String address) {
        if (!isEnabled() || address == null || address.isBlank()) {
            return Optional.empty();
        }
        if (!tryConsumeBudget()) {
            return Optional.empty();
        }
        HttpUrl parsedBase = HttpUrl.parse(GEOCODE_URL);
        if (parsedBase == null) {
            return Optional.empty();
        }
        HttpUrl url = parsedBase.newBuilder()
                .addQueryParameter("key", apiKey)
                .addQueryParameter("address", address)
                .addQueryParameter("city", DEFAULT_CITY)
                .build();
        Request request = new Request.Builder().url(url).get().build();

        try (Response response = http.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                log.warn("Amap geocode HTTP {} for address={}", response.code(), address);
                return Optional.empty();
            }
            Map<String, Object> parsed = readJsonObject(response.body().string());
            if (parsed == null || !STATUS_OK.equals(String.valueOf(parsed.get("status")))) {
                log.warn("Amap geocode non-success for address={}: {}", address, parsed == null ? null : parsed.get("info"));
                return Optional.empty();
            }
            Object geocodesObj = parsed.get("geocodes");
            if (!(geocodesObj instanceof List<?> geocodes) || geocodes.isEmpty()
                    || !(geocodes.get(0) instanceof Map<?, ?> geocodeMap)) {
                return Optional.empty();
            }
            Object locationObj = geocodeMap.get("location");
            return parseLngLat(locationObj);
        } catch (IOException e) {
            log.warn("Amap geocode failed for address={}: {}", address, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * 驾车距离 (公里, scale=2 HALF_UP) — {@code type=1} 驾车路径规划。
     *
     * @return 成功 → {@code Optional.of(km)}；key 未配置 / 请求失败 / 无结果 →
     *         {@link Optional#empty()} (诚实降级, 调用方保持 {@code NEEDS_ROUTE_DATA},
     *         绝不用直线距离/伪造值填充)。
     */
    public Optional<BigDecimal> drivingDistanceKm(double originLng, double originLat, double destLng, double destLat) {
        if (!isEnabled()) {
            return Optional.empty();
        }
        if (!tryConsumeBudget()) {
            return Optional.empty();
        }
        HttpUrl parsedBase = HttpUrl.parse(DISTANCE_URL);
        if (parsedBase == null) {
            return Optional.empty();
        }
        HttpUrl url = parsedBase.newBuilder()
                .addQueryParameter("key", apiKey)
                .addQueryParameter("origins", originLng + "," + originLat)
                .addQueryParameter("destination", destLng + "," + destLat)
                .addQueryParameter("type", "1")
                .build();
        Request request = new Request.Builder().url(url).get().build();

        try (Response response = http.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                log.warn("Amap distance HTTP {} for ({},{})->({},{})", response.code(), originLng, originLat, destLng, destLat);
                return Optional.empty();
            }
            Map<String, Object> parsed = readJsonObject(response.body().string());
            if (parsed == null || !STATUS_OK.equals(String.valueOf(parsed.get("status")))) {
                log.warn("Amap distance non-success: {}", parsed == null ? null : parsed.get("info"));
                return Optional.empty();
            }
            Object resultsObj = parsed.get("results");
            if (!(resultsObj instanceof List<?> results) || results.isEmpty()
                    || !(results.get(0) instanceof Map<?, ?> resultMap)) {
                return Optional.empty();
            }
            Object distanceObj = resultMap.get("distance");
            if (distanceObj == null) {
                return Optional.empty();
            }
            long meters = Long.parseLong(String.valueOf(distanceObj).trim());
            BigDecimal km = BigDecimal.valueOf(meters)
                    .divide(BigDecimal.valueOf(1000), 4, RoundingMode.HALF_UP)
                    .setScale(2, RoundingMode.HALF_UP);
            return Optional.of(km);
        } catch (IOException | NumberFormatException e) {
            log.warn("Amap distance failed: {}", e.getMessage());
            return Optional.empty();
        }
    }

    // ============================================================
    // helpers
    // ============================================================

    @SuppressWarnings("unchecked")
    private Map<String, Object> readJsonObject(String body) {
        try {
            return objectMapper.readValue(body, Map.class);
        } catch (IOException e) {
            log.warn("Amap response JSON parse failed: {}", e.getMessage());
            return null;
        }
    }

    private static Optional<double[]> parseLngLat(Object locationObj) {
        if (locationObj == null) {
            return Optional.empty();
        }
        String[] parts = String.valueOf(locationObj).split(",");
        if (parts.length != 2) {
            return Optional.empty();
        }
        try {
            double lng = Double.parseDouble(parts[0].trim());
            double lat = Double.parseDouble(parts[1].trim());
            return Optional.of(new double[] {lng, lat});
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    /**
     * 每日查询预算控制 ({@code amap.daily-query-budget}, 默认 800) — 超出后当日剩余调用
     * 直接短路返回 empty, 不再发起 HTTP 请求 (保护高德 key 配额，避免单日突发大量排线
     * 请求耗尽整月额度)。次日 (系统日期变化) 自动重置计数。
     */
    private synchronized boolean tryConsumeBudget() {
        LocalDate today = LocalDate.now();
        if (!today.equals(budgetWindowDate)) {
            budgetWindowDate = today;
            dailyCallCount.set(0);
        }
        if (dailyCallCount.incrementAndGet() > dailyQueryBudget) {
            log.warn("Amap daily query budget ({}) exceeded, skipping call", dailyQueryBudget);
            return false;
        }
        return true;
    }
}
