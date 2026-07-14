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
    private static final String POI_URL = "https://restapi.amap.com/v3/place/text";
    private static final String DISTANCE_URL = "https://restapi.amap.com/v3/distance";
    private static final String DIRECTION_URL = "https://restapi.amap.com/v3/direction/driving";
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
        // 从地址里抽出「预期城市 + 预期区县」（如「江苏省无锡市梁溪区…」→ 城市=无锡, 区=梁溪区）。
        // 用于杜绝漂移: 高德对含 POI 后缀 / 小区简称 / 模糊门牌的地址会 fuzzy-match 到别处的同名 POI —
        //   跨城市: 「渝八两常州新北万达店」→昆山、「延陵地铁商业街」→常熟 (城市校验拦);
        //   同城跨区: 「无锡市梁溪区凤凰城20-6」→江阴市的「凤凰城」小区 (区级校验拦, 2026-07-14 客户实测)。
        String expectedCity = extractCity(address);
        String expectedDistrict = extractDistrict(address);

        // 第一次：全国范围解析（地址规范即精确，通常直接命中且城市/区匹配）。
        GeoResult first = doGeocode(address, null);
        GeoResult candidate = null;
        if (first != null && (expectedCity == null || cityMatches(expectedCity, first.city()))) {
            candidate = first;
        } else if (first != null) {
            // 城市不一致 → 疑似跨城市漂移。第二次：限定城市重试。
            log.warn("Amap geocode city mismatch: address={} expected={} got={} → retry constrained",
                    address, expectedCity, first.city());
            GeoResult retry = doGeocode(address, expectedCity);
            if (retry != null && cityMatches(expectedCity, retry.city())) {
                candidate = retry;
            }
        }

        // 区级校验: 城市对了但落在别的区/县级市(高德 city 参数拦不住县级市, 如江阴属无锡) → 不能直接用。
        if (candidate != null && areaMatchesLoose(expectedDistrict, candidate.district())) {
            return candidate.coord();
        }
        if (candidate != null) {
            log.warn("Amap geocode district mismatch: address={} expected={} got={} → POI fallback",
                    address, expectedDistrict, candidate.district());
        }

        // POI 兜底 (geocode 无结果 / 城市不符 / 区不符): 用地址核心词(去省市区前缀+门牌尾巴)在预期城市内
        // 搜 POI, 只取落在预期区的第一个 (实测「凤凰城」→ 命中梁溪区「华仁·凤凰城」而非江阴「凤凰城」)。
        if (expectedCity != null && expectedDistrict != null) {
            Optional<double[]> poi = poiSearchInDistrict(address, expectedCity, expectedDistrict);
            if (poi.isPresent()) {
                return poi;
            }
        }
        // 全部失败 → 诚实降级为 UNRESOLVED（宁可让调度员手动补点，也绝不落一个错区/错城市的坐标）。
        log.warn("Amap geocode rejected (no result matching expected area) for address={} city={} district={}",
                address, expectedCity, expectedDistrict);
        return Optional.empty();
    }

    /**
     * POI 关键词搜索兜底 — 限定城市 + 只接受落在预期区县的结果。
     * geocode 对「小区简称/品牌名」类地址常匹配错同名 POI, 而 POI 搜索返回多个候选带各自区县
     * ({@code adname}), 可按预期区过滤出正确的那一个。找不到 → empty (诚实)。
     */
    private Optional<double[]> poiSearchInDistrict(String address, String city, String district) {
        String keywords = deriveKeywords(address);
        if (keywords == null || keywords.length() < 2 || !tryConsumeBudget()) {
            return Optional.empty();
        }
        HttpUrl parsedBase = HttpUrl.parse(POI_URL);
        if (parsedBase == null) {
            return Optional.empty();
        }
        HttpUrl url = parsedBase.newBuilder()
                .addQueryParameter("key", apiKey)
                .addQueryParameter("keywords", keywords)
                .addQueryParameter("city", city)
                .addQueryParameter("citylimit", "true")
                .addQueryParameter("offset", "10")
                .build();
        Request request = new Request.Builder().url(url).get().build();
        try (Response response = http.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                return Optional.empty();
            }
            Map<String, Object> parsed = readJsonObject(response.body().string());
            if (parsed == null || !STATUS_OK.equals(String.valueOf(parsed.get("status")))
                    || !(parsed.get("pois") instanceof List<?> pois)) {
                return Optional.empty();
            }
            for (Object poiObj : pois) {
                if (!(poiObj instanceof Map<?, ?> poi)) {
                    continue;
                }
                String adname = textFieldToString(poi.get("adname"));
                if (!areaMatchesLoose(district, adname) || adname == null) {
                    continue; // adname 为空也跳过 — POI 兜底必须确证落在预期区, 不放行未知
                }
                Optional<double[]> coord = parseLngLat(poi.get("location"));
                if (coord.isPresent()) {
                    log.info("Amap POI fallback hit: address={} keywords={} → poi={} ({})",
                            address, keywords, poi.get("name"), adname);
                    return coord;
                }
            }
            return Optional.empty();
        } catch (IOException e) {
            log.warn("Amap POI search failed for keywords={}: {}", keywords, e.getMessage());
            return Optional.empty();
        }
    }

    /** 单次调用高德地理编码（{@code city} 为 null 则全国搜）。返回坐标 + 高德判定的城市。 */
    private GeoResult doGeocode(String address, String city) {
        if (!tryConsumeBudget()) {
            return null;
        }
        HttpUrl parsedBase = HttpUrl.parse(GEOCODE_URL);
        if (parsedBase == null) {
            return null;
        }
        HttpUrl.Builder b = parsedBase.newBuilder()
                .addQueryParameter("key", apiKey)
                .addQueryParameter("address", address);
        if (city != null && !city.isBlank()) {
            b.addQueryParameter("city", city);
        }
        Request request = new Request.Builder().url(b.build()).get().build();
        try (Response response = http.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                log.warn("Amap geocode HTTP {} for address={}", response.code(), address);
                return null;
            }
            Map<String, Object> parsed = readJsonObject(response.body().string());
            if (parsed == null || !STATUS_OK.equals(String.valueOf(parsed.get("status")))) {
                log.warn("Amap geocode non-success for address={}: {}", address, parsed == null ? null : parsed.get("info"));
                return null;
            }
            Object geocodesObj = parsed.get("geocodes");
            if (!(geocodesObj instanceof List<?> geocodes) || geocodes.isEmpty()
                    || !(geocodes.get(0) instanceof Map<?, ?> geocodeMap)) {
                return null;
            }
            Optional<double[]> coord = parseLngLat(geocodeMap.get("location"));
            if (coord.isEmpty()) {
                return null;
            }
            return new GeoResult(coord, textFieldToString(geocodeMap.get("city")),
                    textFieldToString(geocodeMap.get("district")));
        } catch (IOException e) {
            log.warn("Amap geocode failed for address={}: {}", address, e.getMessage());
            return null;
        }
    }

    private record GeoResult(Optional<double[]> coord, String city, String district) {
    }

    /** 高德返回的文本字段（city/district/adname）：有值是 String，无值时是空数组 [] 或空串。 */
    private static String textFieldToString(Object v) {
        if (v instanceof String s && !s.isBlank()) {
            return s;
        }
        return null; // 空数组/空串（直辖市等）→ 无法据此校验，视为不校验
    }

    /**
     * 从地址抽取地级市名（去「市」后缀，便于宽松比较）。
     * 优先匹配「省XX市」（锚定在「省」之后，避免贪婪把「省」字本身吞进城市名——
     * 曾踩坑：对"江苏省常州市…"贪婪匹配成"苏省常州"而非"常州"，只是被下游 cityMatches
     * 的宽松 contains 判断掩盖，未真正影响生产行为，但破坏本函数自身的精确性）；
     * 地址无「省」（如直辖市地址"上海市…"）→ 回落锚定字符串开头的「^XX市」。
     * 两者都抽不到（无「市」字 / 纯 POI 名）→ null，此时不做城市校验（回落原全国搜行为）。
     */
    static String extractCity(String address) {
        if (address == null) {
            return null;
        }
        java.util.regex.Matcher afterProvince = CITY_AFTER_PROVINCE_PATTERN.matcher(address);
        if (afterProvince.find()) {
            return afterProvince.group(1);
        }
        java.util.regex.Matcher fromStart = CITY_FROM_START_PATTERN.matcher(address);
        if (fromStart.find()) {
            return fromStart.group(1);
        }
        return null;
    }

    private static final java.util.regex.Pattern CITY_AFTER_PROVINCE_PATTERN =
            java.util.regex.Pattern.compile("省([\\u4e00-\\u9fa5]{2,4})市");
    private static final java.util.regex.Pattern CITY_FROM_START_PATTERN =
            java.util.regex.Pattern.compile("^([\\u4e00-\\u9fa5]{2,4})市");

    /**
     * 从地址抽取区/县名（「XX市」后紧跟的「XX区/XX县」，如「无锡市梁溪区…」→「梁溪区」）。
     * 保守：抽不到（无区县 / 县级市结尾「市」）→ null，此时不做区级校验（回落城市级行为）。
     */
    static String extractDistrict(String address) {
        if (address == null) {
            return null;
        }
        java.util.regex.Matcher m = DISTRICT_PATTERN.matcher(address);
        return m.find() ? m.group(1) : null;
    }

    private static final java.util.regex.Pattern DISTRICT_PATTERN =
            java.util.regex.Pattern.compile("市([\\u4e00-\\u9fa5]{1,5}?(?:区|县))");

    /**
     * 地址核心词（POI 兜底搜索用）：去掉省/市/区县前缀 + 门牌号尾巴。
     * 「江苏省无锡市梁溪区凤凰城20-6」→「凤凰城」。
     */
    static String deriveKeywords(String address) {
        if (address == null) {
            return null;
        }
        // 去省市区县前缀（贪婪 → 取最后一个行政区划分隔点之后的部分）
        String core = address.replaceFirst("^.*(?:区|县|市)(?=[^区县市])", "");
        // 去门牌号尾巴（数字/连字符/号栋幢室楼层/括号注释）
        core = core.replaceAll("[0-9０-９\\-－—~～·、,，.。()（）a-zA-Z号栋幢室楼层 ]+$", "");
        return core.isBlank() ? null : core;
    }

    /** 宽松城市匹配：高德返回城市（可能空/带「市」）与预期城市互相包含即算一致。 */
    private static boolean cityMatches(String expected, String returned) {
        if (returned == null || returned.isBlank()) {
            return true; // 高德没给城市（直辖市等）→ 无法否证，放行（不误杀）
        }
        String r = returned.endsWith("市") ? returned.substring(0, returned.length() - 1) : returned;
        return r.contains(expected) || expected.contains(r);
    }

    /** 宽松区县匹配：预期区为 null（地址没写区）或返回区为空 → 无法否证，放行；否则互相包含即一致。 */
    private static boolean areaMatchesLoose(String expectedDistrict, String returned) {
        if (expectedDistrict == null) {
            return true;
        }
        if (returned == null || returned.isBlank()) {
            return true;
        }
        return returned.contains(expectedDistrict) || expectedDistrict.contains(returned);
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
