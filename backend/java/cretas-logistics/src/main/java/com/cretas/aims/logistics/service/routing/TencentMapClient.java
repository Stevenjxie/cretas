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
 * 腾讯位置服务 (WebService API) 驾车路线 client — 多提供商 fallback 链第 2 顺位
 * (档1-B, 2026-07-11)。诚实降级铁律同 {@link AmapClient} 类头: key 未配置 / 预算耗尽 /
 * HTTP·解析失败 / {@code status!=0} → {@link Optional#empty()}, 绝不伪造路线。
 *
 * <p><b>腾讯 API quirks</b> (与高德的差异, 实现内消化, 对外契约仍 GCJ-02 {lng,lat}):
 * <ul>
 *   <li>请求坐标是 <b>lat,lng 顺序</b> (from/to/waypoints), 与高德 lng,lat 相反。</li>
 *   <li>坐标系 GCJ-02 (与高德同源), 无需转换。</li>
 *   <li>{@code result.routes[0].polyline} 是<b>压缩坐标数组</b>: [lat0, lng0, d2, d3, ...],
 *       自下标 2 起是差值, 还原公式 {@code coors[i] = coors[i-2] + coors[i]/1000000}。</li>
 *   <li>{@code duration} 单位是<b>分钟</b> (高德是秒); {@code distance} 米。</li>
 *   <li>成功 {@code status} 是数字 0 (高德是字符串 "1")。</li>
 * </ul>
 *
 * <p>凭证从环境变量 {@code TENCENT_MAP_KEY} 注入 {@code tencent.map.key} 属性, 绝不硬编码
 * (两个 GitHub 仓库均为 public)。key 为空 → {@link #isEnabled()} false → 链路跳过本 provider。
 */
@Slf4j
@Component
public class TencentMapClient implements DrivingRouteProvider {

    private static final String DIRECTION_URL = "https://apis.map.qq.com/ws/direction/v1/driving/";
    private static final long TIMEOUT_SECONDS = 8;

    private final String apiKey;
    private final int dailyQueryBudget;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final OkHttpClient http;

    private final AtomicInteger dailyCallCount = new AtomicInteger(0);
    private volatile LocalDate budgetWindowDate = LocalDate.now();

    public TencentMapClient(
            @Value("${tencent.map.key:}") String apiKey,
            @Value("${tencent.map.daily-query-budget:800}") int dailyQueryBudget) {
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.dailyQueryBudget = dailyQueryBudget;
        this.http = new OkHttpClient.Builder()
                .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .writeTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .build();
        log.info("TencentMapClient initialized; enabled={} dailyQueryBudget={}", isEnabled(), dailyQueryBudget);
    }

    @Override
    public String providerName() {
        return "TENCENT";
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
                .addQueryParameter("key", apiKey)
                .addQueryParameter("from", formatLatLng(originLng, originLat))
                .addQueryParameter("to", formatLatLng(destLng, destLat));
        // 策略: TIME→LEAST_TIME (时间最短)。DISTANCE 腾讯无"距离最短" policy → 不传用默认策略
        // (fallback provider 保可用性优先, 模式最优性只在高德主通道保证, 诚实记录于类头)。
        if (mode == RouteOptimizeMode.TIME) {
            builder.addQueryParameter("policy", "LEAST_TIME");
        }
        if (waypoints != null && !waypoints.isEmpty()) {
            StringBuilder wp = new StringBuilder();
            for (double[] point : waypoints) {
                if (wp.length() > 0) {
                    wp.append(';');
                }
                wp.append(formatLatLng(point[0], point[1]));
            }
            builder.addQueryParameter("waypoints", wp.toString());
        }
        Request request = new Request.Builder().url(builder.build()).get().build();

        try (Response response = http.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                log.warn("Tencent direction HTTP {} for ({},{})->({},{})", response.code(),
                        originLng, originLat, destLng, destLat);
                return Optional.empty();
            }
            Map<String, Object> parsed = readJsonObject(response.body().string());
            if (parsed == null || !"0".equals(String.valueOf(parsed.get("status")))) {
                log.warn("Tencent direction non-success: {}", parsed == null ? null : parsed.get("message"));
                return Optional.empty();
            }
            return parseDirectionResponse(parsed);
        } catch (IOException | RuntimeException e) {
            log.warn("Tencent direction failed: {}", e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * 解析成功响应 {@code result.routes[0]}: distance (米) / duration (分钟) / polyline
     * (压缩坐标数组)。Package-private static — 单测直接喂样例 JSON 验证解压。
     */
    static Optional<DrivingRoute> parseDirectionResponse(Map<String, Object> parsed) {
        if (!(parsed.get("result") instanceof Map<?, ?> result)
                || !(result.get("routes") instanceof List<?> routes) || routes.isEmpty()
                || !(routes.get(0) instanceof Map<?, ?> route)) {
            return Optional.empty();
        }
        Object distanceObj = route.get("distance");
        Object durationObj = route.get("duration");
        Object polylineObj = route.get("polyline");
        if (!(distanceObj instanceof Number) || !(durationObj instanceof Number)
                || !(polylineObj instanceof List<?> compressed) || compressed.size() < 2) {
            return Optional.empty();
        }
        List<double[]> polyline = decompressPolyline(compressed);
        if (polyline.isEmpty()) {
            return Optional.empty();
        }
        BigDecimal distanceKm = BigDecimal.valueOf(((Number) distanceObj).doubleValue())
                .divide(BigDecimal.valueOf(1000), 4, RoundingMode.HALF_UP)
                .setScale(2, RoundingMode.HALF_UP);
        BigDecimal durationMin = BigDecimal.valueOf(((Number) durationObj).doubleValue())
                .setScale(2, RoundingMode.HALF_UP); // 腾讯 duration 已是分钟
        return Optional.of(new DrivingRoute(distanceKm, durationMin, polyline, "TENCENT"));
    }

    /**
     * 腾讯压缩坐标解压: 数组形如 [lat0, lng0, d2, d3, d4, d5, ...], 自下标 2 起为
     * 差值 (放大 1e6 的整数), 还原 {@code coors[i] = coors[i-2] + coors[i]/1000000};
     * 还原后按 (lat, lng) 成对取出并调换为 {@code {lng, lat}}。
     * Package-private static — 单测覆盖。
     */
    static List<double[]> decompressPolyline(List<?> compressed) {
        double[] vals = new double[compressed.size()];
        for (int i = 0; i < compressed.size(); i++) {
            if (!(compressed.get(i) instanceof Number n)) {
                return List.of();
            }
            vals[i] = n.doubleValue();
        }
        for (int i = 2; i < vals.length; i++) {
            vals[i] = vals[i - 2] + vals[i] / 1_000_000.0;
        }
        List<double[]> points = new ArrayList<>(vals.length / 2);
        for (int i = 0; i + 1 < vals.length; i += 2) {
            points.add(new double[] {vals[i + 1], vals[i]}); // (lat,lng) → {lng,lat}
        }
        return points;
    }

    /** 腾讯请求坐标是 lat,lng 顺序 (入参仍是系统统一的 lng/lat)。 */
    static String formatLatLng(double lng, double lat) {
        return String.format(Locale.ROOT, "%.6f,%.6f", lat, lng);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readJsonObject(String body) {
        try {
            return objectMapper.readValue(body, Map.class);
        } catch (IOException e) {
            log.warn("Tencent response JSON parse failed: {}", e.getMessage());
            return null;
        }
    }

    /** 每日查询预算 — 同 {@link AmapClient} 模式, 独立计数 (腾讯自己的配额)。 */
    private synchronized boolean tryConsumeBudget() {
        LocalDate today = LocalDate.now();
        if (!today.equals(budgetWindowDate)) {
            budgetWindowDate = today;
            dailyCallCount.set(0);
        }
        if (dailyCallCount.incrementAndGet() > dailyQueryBudget) {
            log.warn("Tencent daily query budget ({}) exceeded, skipping call", dailyQueryBudget);
            return false;
        }
        return true;
    }
}
