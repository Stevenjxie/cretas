package com.cretas.aims.logistics.service.routing;

import com.cretas.aims.logistics.entity.enums.RouteOptimizeMode;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
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
public class AmapClient implements DrivingRouteProvider {

    private static final String GEOCODE_URL = "https://restapi.amap.com/v3/geocode/geo";
    private static final String DISTANCE_URL = "https://restapi.amap.com/v3/distance";
    private static final String DIRECTION_URL = "https://restapi.amap.com/v3/direction/driving";
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
    // 驾车路线规划 (档1-B, 2026-07-11) — DrivingRouteProvider 实现
    // ============================================================

    @Override
    public String providerName() {
        return "AMAP";
    }

    /**
     * 驾车路线规划 (v3 direction/driving): DEPOT → 途经点 → 终点, 返回全程道路折线 +
     * 里程 + 时长。策略: TIME→0 (速度优先) / DISTANCE→2 (距离最短)。
     *
     * <p>失败 (key 未配置 / 预算耗尽 / HTTP / 解析 / status!=1) → {@link Optional#empty()},
     * 调用方回落既有"缺边诚实降级"路径, 绝不伪造折线。
     */
    @Override
    public Optional<DrivingRoute> drivingRoute(double originLng, double originLat,
            List<double[]> waypoints, double destLng, double destLat, RouteOptimizeMode mode) {
        if (!isEnabled()) {
            return Optional.empty();
        }
        if (!tryConsumeBudget()) {
            return Optional.empty();
        }
        HttpUrl parsedBase = HttpUrl.parse(DIRECTION_URL);
        if (parsedBase == null) {
            return Optional.empty();
        }
        HttpUrl.Builder builder = parsedBase.newBuilder()
                .addQueryParameter("key", apiKey)
                .addQueryParameter("origin", formatLngLat(originLng, originLat))
                .addQueryParameter("destination", formatLngLat(destLng, destLat))
                .addQueryParameter("strategy", String.valueOf(amapStrategy(mode)));
        if (waypoints != null && !waypoints.isEmpty()) {
            StringBuilder wp = new StringBuilder();
            for (double[] point : waypoints) {
                if (wp.length() > 0) {
                    wp.append(';');
                }
                wp.append(formatLngLat(point[0], point[1]));
            }
            builder.addQueryParameter("waypoints", wp.toString());
        }
        Request request = new Request.Builder().url(builder.build()).get().build();

        try (Response response = http.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                log.warn("Amap direction HTTP {} for ({},{})->({},{})", response.code(),
                        originLng, originLat, destLng, destLat);
                return Optional.empty();
            }
            Map<String, Object> parsed = readJsonObject(response.body().string());
            if (parsed == null || !STATUS_OK.equals(String.valueOf(parsed.get("status")))) {
                log.warn("Amap direction non-success: {}", parsed == null ? null : parsed.get("info"));
                return Optional.empty();
            }
            return parseDirectionResponse(parsed);
        } catch (IOException | RuntimeException e) {
            log.warn("Amap direction failed: {}", e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * 解析 v3 direction/driving 成功响应 {@code route.paths[0]}: distance (米, String) /
     * duration (秒, String) / steps[].polyline 顺序拼接为全程折线。任何字段缺失/形状异常 →
     * empty (诚实, 不返回半截结果)。Package-private static — 单测直接喂样例 JSON 验证。
     */
    static Optional<DrivingRoute> parseDirectionResponse(Map<String, Object> parsed) {
        if (!(parsed.get("route") instanceof Map<?, ?> route)
                || !(route.get("paths") instanceof List<?> paths) || paths.isEmpty()
                || !(paths.get(0) instanceof Map<?, ?> path)) {
            return Optional.empty();
        }
        Object distanceObj = path.get("distance");
        Object durationObj = path.get("duration");
        if (distanceObj == null || durationObj == null || !(path.get("steps") instanceof List<?> steps)) {
            return Optional.empty();
        }
        List<double[]> polyline = new ArrayList<>();
        for (Object stepObj : steps) {
            if (!(stepObj instanceof Map<?, ?> step)) {
                return Optional.empty();
            }
            Object stepPolyline = step.get("polyline");
            if (stepPolyline == null) {
                return Optional.empty();
            }
            appendPolyline(polyline, String.valueOf(stepPolyline));
        }
        if (polyline.isEmpty()) {
            return Optional.empty();
        }
        try {
            long meters = Long.parseLong(String.valueOf(distanceObj).trim());
            long seconds = Long.parseLong(String.valueOf(durationObj).trim());
            return Optional.of(new DrivingRoute(metersToKm(meters), secondsToMinutes(seconds), polyline, "AMAP"));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    /**
     * 追加一段 {@code "lng,lat;lng,lat;..."} 折线到累积点串, 跳过与上一点重复的接缝点
     * (相邻 step 首尾共点)。Package-private static — 单测覆盖。
     */
    static void appendPolyline(List<double[]> acc, String segment) {
        if (segment == null || segment.isBlank()) {
            return;
        }
        for (String pair : segment.split(";")) {
            String[] parts = pair.split(",");
            if (parts.length != 2) {
                continue;
            }
            try {
                double lng = Double.parseDouble(parts[0].trim());
                double lat = Double.parseDouble(parts[1].trim());
                if (!acc.isEmpty()) {
                    double[] last = acc.get(acc.size() - 1);
                    if (last[0] == lng && last[1] == lat) {
                        continue;
                    }
                }
                acc.add(new double[] {lng, lat});
            } catch (NumberFormatException e) {
                // 单个坏点跳过, 不让整条折线报废
            }
        }
    }

    /** 优化模式 → 高德 v3 strategy: TIME→0 (速度优先) / DISTANCE (含 null 默认)→2 (距离最短)。 */
    static int amapStrategy(RouteOptimizeMode mode) {
        return mode == RouteOptimizeMode.TIME ? 0 : 2;
    }

    static String formatLngLat(double lng, double lat) {
        return String.format(Locale.ROOT, "%.6f,%.6f", lng, lat);
    }

    static BigDecimal metersToKm(long meters) {
        return BigDecimal.valueOf(meters)
                .divide(BigDecimal.valueOf(1000), 4, RoundingMode.HALF_UP)
                .setScale(2, RoundingMode.HALF_UP);
    }

    static BigDecimal secondsToMinutes(long seconds) {
        return BigDecimal.valueOf(seconds)
                .divide(BigDecimal.valueOf(60), 4, RoundingMode.HALF_UP)
                .setScale(2, RoundingMode.HALF_UP);
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
