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
 * 百度地图 (Direction API v2) 驾车路线 client — 多提供商 fallback 链第 3 顺位
 * (档1-B, 2026-07-11)。诚实降级铁律同 {@link AmapClient} 类头: ak 未配置 / 预算耗尽 /
 * HTTP·解析失败 / {@code status!=0} → {@link Optional#empty()}, 绝不伪造路线。
 *
 * <p><b>百度 API quirks</b> (与高德的差异, 实现内消化, 对外契约仍 GCJ-02 {lng,lat}):
 * <ul>
 *   <li>坐标系是 <b>BD-09</b> (与高德/腾讯的 GCJ-02 不同!): 请求前
 *       {@link CoordTransform#gcj02ToBd09}, 返回折线逐点 {@link CoordTransform#bd09ToGcj02}
 *       再返回, 保证持久化/前端高德底图渲染的折线不偏移。</li>
 *   <li>请求坐标是 <b>lat,lng 顺序</b>; waypoints 分隔符是 <b>竖线 |</b> (高德/腾讯是分号)。</li>
 *   <li>{@code result.routes[0].steps[].path} 是 {@code "lng,lat;lng,lat"} 字符串 (BD-09)。</li>
 *   <li>{@code duration} 秒 / {@code distance} 米; 成功 {@code status} 是数字 0。</li>
 *   <li>不传 {@code tactics} (各版本取值定义不一致, 传错可能整个请求报错) — fallback provider
 *       保可用性优先, 模式最优性只在高德主通道保证。</li>
 * </ul>
 *
 * <p>凭证从环境变量 {@code BAIDU_MAP_AK} 注入 {@code baidu.map.ak} 属性, 绝不硬编码
 * (两个 GitHub 仓库均为 public)。ak 为空 → {@link #isEnabled()} false → 链路跳过本 provider。
 */
@Slf4j
@Component
public class BaiduMapClient implements DrivingRouteProvider {

    private static final String DIRECTION_URL = "https://api.map.baidu.com/direction/v2/driving";
    private static final long TIMEOUT_SECONDS = 8;

    private final String apiKey;
    private final int dailyQueryBudget;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final OkHttpClient http;

    private final AtomicInteger dailyCallCount = new AtomicInteger(0);
    private volatile LocalDate budgetWindowDate = LocalDate.now();

    public BaiduMapClient(
            @Value("${baidu.map.ak:}") String apiKey,
            @Value("${baidu.map.daily-query-budget:800}") int dailyQueryBudget) {
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.dailyQueryBudget = dailyQueryBudget;
        this.http = new OkHttpClient.Builder()
                .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .writeTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .build();
        log.info("BaiduMapClient initialized; enabled={} dailyQueryBudget={}", isEnabled(), dailyQueryBudget);
    }

    @Override
    public String providerName() {
        return "BAIDU";
    }

    @Override
    public boolean isEnabled() {
        return !apiKey.isEmpty();
    }

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
                .addQueryParameter("ak", apiKey)
                .addQueryParameter("origin", formatBd09LatLng(originLng, originLat))
                .addQueryParameter("destination", formatBd09LatLng(destLng, destLat));
        if (waypoints != null && !waypoints.isEmpty()) {
            StringBuilder wp = new StringBuilder();
            for (double[] point : waypoints) {
                if (wp.length() > 0) {
                    wp.append('|');
                }
                wp.append(formatBd09LatLng(point[0], point[1]));
            }
            builder.addQueryParameter("waypoints", wp.toString());
        }
        Request request = new Request.Builder().url(builder.build()).get().build();

        try (Response response = http.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                log.warn("Baidu direction HTTP {} for ({},{})->({},{})", response.code(),
                        originLng, originLat, destLng, destLat);
                return Optional.empty();
            }
            Map<String, Object> parsed = readJsonObject(response.body().string());
            if (parsed == null || !"0".equals(String.valueOf(parsed.get("status")))) {
                log.warn("Baidu direction non-success: {}", parsed == null ? null : parsed.get("message"));
                return Optional.empty();
            }
            return parseDirectionResponse(parsed);
        } catch (IOException | RuntimeException e) {
            log.warn("Baidu direction failed: {}", e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * 解析成功响应 {@code result.routes[0]}: distance (米) / duration (秒) /
     * steps[].path ("lng,lat;lng,lat", BD-09) 顺序拼接并逐点转 GCJ-02。
     * Package-private static — 单测直接喂样例 JSON 验证坐标转换。
     */
    static Optional<DrivingRoute> parseDirectionResponse(Map<String, Object> parsed) {
        if (!(parsed.get("result") instanceof Map<?, ?> result)
                || !(result.get("routes") instanceof List<?> routes) || routes.isEmpty()
                || !(routes.get(0) instanceof Map<?, ?> route)) {
            return Optional.empty();
        }
        Object distanceObj = route.get("distance");
        Object durationObj = route.get("duration");
        if (!(distanceObj instanceof Number) || !(durationObj instanceof Number)
                || !(route.get("steps") instanceof List<?> steps)) {
            return Optional.empty();
        }
        List<double[]> bd09Points = new ArrayList<>();
        for (Object stepObj : steps) {
            if (!(stepObj instanceof Map<?, ?> step)) {
                return Optional.empty();
            }
            Object path = step.get("path");
            if (path == null) {
                return Optional.empty();
            }
            AmapClient.appendPolyline(bd09Points, String.valueOf(path)); // 同为 "lng,lat;..." 文本形状, 复用解析+接缝去重
        }
        if (bd09Points.isEmpty()) {
            return Optional.empty();
        }
        List<double[]> gcj02Polyline = new ArrayList<>(bd09Points.size());
        for (double[] p : bd09Points) {
            gcj02Polyline.add(CoordTransform.bd09ToGcj02(p[0], p[1]));
        }
        BigDecimal distanceKm = BigDecimal.valueOf(((Number) distanceObj).doubleValue())
                .divide(BigDecimal.valueOf(1000), 4, RoundingMode.HALF_UP)
                .setScale(2, RoundingMode.HALF_UP);
        BigDecimal durationMin = BigDecimal.valueOf(((Number) durationObj).doubleValue())
                .divide(BigDecimal.valueOf(60), 4, RoundingMode.HALF_UP)
                .setScale(2, RoundingMode.HALF_UP);
        return Optional.of(new DrivingRoute(distanceKm, durationMin, gcj02Polyline, "BAIDU"));
    }

    /** 系统 GCJ-02 lng/lat → 百度请求坐标: 先转 BD-09, 再按 lat,lng 顺序输出。 */
    static String formatBd09LatLng(double gcjLng, double gcjLat) {
        double[] bd = CoordTransform.gcj02ToBd09(gcjLng, gcjLat);
        return String.format(Locale.ROOT, "%.6f,%.6f", bd[1], bd[0]);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readJsonObject(String body) {
        try {
            return objectMapper.readValue(body, Map.class);
        } catch (IOException e) {
            log.warn("Baidu response JSON parse failed: {}", e.getMessage());
            return null;
        }
    }

    /** 每日查询预算 — 同 {@link AmapClient} 模式, 独立计数 (百度自己的配额)。 */
    private synchronized boolean tryConsumeBudget() {
        LocalDate today = LocalDate.now();
        if (!today.equals(budgetWindowDate)) {
            budgetWindowDate = today;
            dailyCallCount.set(0);
        }
        if (dailyCallCount.incrementAndGet() > dailyQueryBudget) {
            log.warn("Baidu daily query budget ({}) exceeded, skipping call", dailyQueryBudget);
            return false;
        }
        return true;
    }
}
