package com.cretas.aims.client;

import com.cretas.aims.config.smartbi.PythonSmartBIConfig;
import com.cretas.aims.dto.python.PythonGeneralAnalysisResponse;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import lombok.extern.slf4j.Slf4j;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Gold finance-summary HTTP client.
 *
 * Reads from the Python Gold layer's /api/smartbi/gold/finance-summary
 * endpoint — the Silver+Gold pipeline landed in v1 Phase B v0. This
 * client is the building block for the future Java-side READ_FROM=GOLD
 * cutover in FinanceAnalysisServiceImpl: legacy path stays, Java also
 * calls Gold, logs divergence, and eventually flips.
 *
 * This class today is WIRED BUT NOT CALLED — FinanceAnalysisServiceImpl
 * hasn't been modified. Keeping it as a standalone Spring bean means
 * enabling shadow-read later is a small wire-up change, not a new
 * infrastructure task.
 *
 * Auth: reuses INTERNAL_API_SECRET + X-Factory-Id pattern that
 * PythonSmartBIClient already uses (see auth_middleware.py line 116-144).
 * Gold routes enforce tenant scope via JWT OR this internal-secret path.
 *
 * @since 2026-04-22
 */
@Slf4j
@Component
public class GoldFinanceClient {

    private final PythonSmartBIConfig config;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final OkHttpClient http;
    private final PythonSmartBIClient pythonSmartBIClient;

    /**
     * Read the same env var that JwtAuthInterceptor + PythonSmartBIClient use
     * so there's exactly one secret to rotate. Empty = run without the header
     * (Gold route will then require JWT instead).
     */
    private String internalSecret = System.getenv("INTERNAL_API_SECRET") != null
            ? System.getenv("INTERNAL_API_SECRET") : "";

    public GoldFinanceClient(PythonSmartBIConfig config) {
        this(config, null);
    }

    @Autowired
    public GoldFinanceClient(
            PythonSmartBIConfig config,
            PythonSmartBIClient pythonSmartBIClient) {
        this.config = config;
        this.pythonSmartBIClient = pythonSmartBIClient;
        this.http = new OkHttpClient.Builder()
                .connectTimeout(config.getConnectTimeout(), TimeUnit.MILLISECONDS)
                .readTimeout(config.getTimeout(), TimeUnit.MILLISECONDS)
                .build();
    }

    /**
     * Resolve the current request user's role so the internal Java→Python call
     * can forward it as X-User-Role. Python's RBAC money-strip
     * (_apply_rbac_strip) needs the real role — price-view roles
     * (factory_super_admin / finance_manager / sales_manager / ...) see revenue;
     * everyone else gets money fields nulled.
     *
     * Reads the {@code role} request attribute set by
     * {@link com.cretas.aims.config.JwtAuthInterceptor} from the JWT claim
     * (Cretas auth is request-attribute based, NOT Spring SecurityContext — see
     * AttachmentPermissionResolver). Returns null when there is no request
     * context (e.g. a scheduled job) — Python then strips money, the safe
     * default. No DB lookup: the role is already on the request.
     */
    private String currentUserRole() {
        return currentRequestAttribute("role");
    }

    private String currentUserId() {
        return currentRequestAttribute("userId");
    }

    private String currentRequestAttribute(String attributeName) {
        try {
            var attrs = RequestContextHolder.getRequestAttributes();
            if (attrs instanceof ServletRequestAttributes servletAttrs) {
                HttpServletRequest req = servletAttrs.getRequest();
                Object value = req.getAttribute(attributeName);
                return value != null ? value.toString() : null;
            }
            return null;
        } catch (Exception e) {
            log.debug("current request identity resolve failed");
            return null;
        }
    }

    @PostConstruct
    public void init() {
        log.info("GoldFinanceClient initialized; python_url={} enabled={} has_secret={}",
                config.getUrl(), config.isEnabled(), !internalSecret.isEmpty());
    }

    /**
     * Fetch the finance_summary dict from Python Gold layer.
     *
     * @param factoryId factory (tenant) id — must match the caller's auth scope
     * @param startDate inclusive
     * @param endDate   inclusive; must be ≥ startDate
     * @param topNStores max top-stores ranking to include (1..100)
     * @return parsed JSON as a Map keyed by Python's snake_case field names:
     *         total_revenue, bill_count, avg_bill_value, store_count, day_count,
     *         top_stores (List of {store_id, store_name, revenue, bill_count})
     * @throws IOException if Gold is unreachable, returns non-2xx, or the body
     *                    doesn't parse. Caller should log + fall through to legacy.
     */
    public Map<String, Object> fetchFinanceSummary(
            String factoryId,
            LocalDate startDate,
            LocalDate endDate,
            int topNStores
    ) throws IOException {
        if (factoryId == null || factoryId.isEmpty()) {
            throw new IllegalArgumentException("factoryId required");
        }
        if (startDate == null || endDate == null) {
            throw new IllegalArgumentException("startDate and endDate required");
        }
        if (startDate.isAfter(endDate)) {
            throw new IllegalArgumentException("startDate > endDate");
        }
        if (topNStores < 1 || topNStores > 100) {
            throw new IllegalArgumentException("topNStores must be 1..100");
        }

        HttpUrl url = HttpUrl.parse(config.getUrl() + "/api/smartbi/gold/finance-summary")
                .newBuilder()
                .addQueryParameter("factory_id", factoryId)
                .addQueryParameter("start_date", startDate.toString())
                .addQueryParameter("end_date", endDate.toString())
                .addQueryParameter("top_n_stores", String.valueOf(topNStores))
                .build();

        Request.Builder reqBuilder = new Request.Builder().url(url).get();
        if (!internalSecret.isEmpty()) {
            reqBuilder.addHeader("X-Internal-Secret", internalSecret);
            reqBuilder.addHeader("X-Factory-Id", factoryId);
            // Forward the request user's role so Python RBAC money-strip respects
            // price-view permission (else total_revenue/avg_bill_value get nulled).
            String userRole = currentUserRole();
            if (userRole != null && !userRole.isEmpty()) {
                reqBuilder.addHeader("X-User-Role", userRole);
            }
        }
        Request req = reqBuilder.build();

        long t0 = System.currentTimeMillis();
        try (Response resp = http.newCall(req).execute()) {
            long elapsed = System.currentTimeMillis() - t0;
            if (!resp.isSuccessful()) {
                String body = resp.body() != null ? resp.body().string() : "";
                throw new IOException(
                        "Gold finance-summary HTTP " + resp.code() + " in " + elapsed
                                + "ms: " + body);
            }
            String body = resp.body() != null ? resp.body().string() : "{}";
            @SuppressWarnings("unchecked")
            Map<String, Object> parsed = objectMapper.readValue(body, Map.class);
            log.debug("Gold finance-summary factory={} range={}..{} rev={} bills={} in {}ms",
                    factoryId, startDate, endDate,
                    parsed.get("total_revenue"), parsed.get("bill_count"), elapsed);
            return parsed;
        }
    }

    /**
     * Fetch daily revenue/bill-count trend from Python Gold layer.
     *
     * Used to populate the 销售趋势 line chart on Dashboard 经营驾驶舱 + 分析概览.
     * Apr 22 Gold cutover only included KPI cards; charts stayed empty until
     * this method (Apr 25 Phase B4) wired the trend through.
     *
     * @return parsed JSON; key fields:
     *         - factory_id, start_date, end_date (echoes input)
     *         - points: List of {date, revenue, bill_count, avg_bill_value}
     *           one per date with activity, ordered ascending
     * @throws IOException on transport / non-2xx / parse failure
     */
    public Map<String, Object> fetchDailyTrend(
            String factoryId,
            LocalDate startDate,
            LocalDate endDate
    ) throws IOException {
        if (factoryId == null || factoryId.isEmpty()) {
            throw new IllegalArgumentException("factoryId required");
        }
        if (startDate == null || endDate == null) {
            throw new IllegalArgumentException("startDate and endDate required");
        }
        if (startDate.isAfter(endDate)) {
            throw new IllegalArgumentException("startDate > endDate");
        }

        HttpUrl url = HttpUrl.parse(config.getUrl() + "/api/smartbi/gold/daily-trend")
                .newBuilder()
                .addQueryParameter("factory_id", factoryId)
                .addQueryParameter("start_date", startDate.toString())
                .addQueryParameter("end_date", endDate.toString())
                .build();

        Request.Builder reqBuilder = new Request.Builder().url(url).get();
        if (!internalSecret.isEmpty()) {
            reqBuilder.addHeader("X-Internal-Secret", internalSecret);
            reqBuilder.addHeader("X-Factory-Id", factoryId);
            // Forward the request user's role so Python RBAC money-strip respects
            // price-view permission (else total_revenue/avg_bill_value get nulled).
            String userRole = currentUserRole();
            if (userRole != null && !userRole.isEmpty()) {
                reqBuilder.addHeader("X-User-Role", userRole);
            }
        }
        Request req = reqBuilder.build();

        long t0 = System.currentTimeMillis();
        try (Response resp = http.newCall(req).execute()) {
            long elapsed = System.currentTimeMillis() - t0;
            if (!resp.isSuccessful()) {
                String body = resp.body() != null ? resp.body().string() : "";
                throw new IOException(
                        "Gold daily-trend HTTP " + resp.code() + " in " + elapsed
                                + "ms: " + body);
            }
            String body = resp.body() != null ? resp.body().string() : "{}";
            @SuppressWarnings("unchecked")
            Map<String, Object> parsed = objectMapper.readValue(body, Map.class);
            Object pts = parsed.get("points");
            int count = (pts instanceof java.util.List) ? ((java.util.List<?>) pts).size() : 0;
            log.debug("Gold daily-trend factory={} range={}..{} points={} in {}ms",
                    factoryId, startDate, endDate, count, elapsed);
            return parsed;
        }
    }

    /**
     * Fetch the trend bundle (dailyTrend + weekdayWeekend + monthlyTrend) from
     * Python Gold layer's {@code /api/smartbi/gold/trend-bundle} endpoint.
     *
     * <p>One round-trip for all three trend views over agg_daily. Used by
     * {@code RestaurantRevenueTrendGoldTool} to answer 营收趋势 / 同比环比 / 月度趋势
     * questions in AI问答 — the COMPREHENSIVE_SYNTHESIS FactBook lacks a monthly
     * series so it cannot compute trend; this gold path can.
     *
     * <p><b>All-history</b>: when {@code startDate}/{@code endDate} are null the
     * date query params are omitted, so Python defaults to the factory's full
     * Gold span (mirrors the endpoint's "省略=全部历史" contract). Unlike
     * {@link #fetchDailyTrend} this method tolerates null bounds.
     *
     * <p><b>RBAC</b>: revenue fields (dailyTrend[].revenue, monthlyTrend[].revenue,
     * weekdayWeekend.weekdayAvg/weekendAvg) are monetary → Python money-strip nulls
     * them for non price-view roles. We forward X-User-Role so price-view roles see
     * revenue (per feedback_java_python_rbac_role_forward).
     *
     * @param factoryId tenant id
     * @param startDate inclusive window start; nullable (null → all history)
     * @param endDate   inclusive window end; nullable (null → all history)
     * @return parsed JSON; key fields:
     *         - factory_id, start_date, end_date (echoes input; null when all-history)
     *         - dailyTrend: List of {date, revenue, bill_count}, ascending
     *         - weekdayWeekend: {weekdayAvg, weekendAvg, weekdayDays, weekendDays}
     *         - monthlyTrend: List of {month: "YYYY-MM", revenue}, ascending
     * @throws IOException on transport / non-2xx / parse failure
     */
    public Map<String, Object> fetchTrendBundle(
            String factoryId,
            LocalDate startDate,
            LocalDate endDate
    ) throws IOException {
        if (factoryId == null || factoryId.isEmpty()) {
            throw new IllegalArgumentException("factoryId required");
        }
        if (startDate != null && endDate != null && startDate.isAfter(endDate)) {
            throw new IllegalArgumentException("startDate > endDate");
        }

        HttpUrl.Builder ub = HttpUrl.parse(config.getUrl() + "/api/smartbi/gold/trend-bundle")
                .newBuilder()
                .addQueryParameter("factory_id", factoryId);
        // Omit date params when null so Python defaults to all history.
        if (startDate != null) {
            ub.addQueryParameter("start_date", startDate.toString());
        }
        if (endDate != null) {
            ub.addQueryParameter("end_date", endDate.toString());
        }
        HttpUrl url = ub.build();

        Request.Builder reqBuilder = new Request.Builder().url(url).get();
        if (!internalSecret.isEmpty()) {
            reqBuilder.addHeader("X-Internal-Secret", internalSecret);
            reqBuilder.addHeader("X-Factory-Id", factoryId);
            // Forward the request user's role so Python RBAC money-strip respects
            // price-view permission (else revenue fields get nulled).
            String userRole = currentUserRole();
            if (userRole != null && !userRole.isEmpty()) {
                reqBuilder.addHeader("X-User-Role", userRole);
            }
        }
        Request req = reqBuilder.build();

        long t0 = System.currentTimeMillis();
        try (Response resp = http.newCall(req).execute()) {
            long elapsed = System.currentTimeMillis() - t0;
            if (!resp.isSuccessful()) {
                String body = resp.body() != null ? resp.body().string() : "";
                throw new IOException(
                        "Gold trend-bundle HTTP " + resp.code() + " in " + elapsed
                                + "ms: " + body);
            }
            String body = resp.body() != null ? resp.body().string() : "{}";
            @SuppressWarnings("unchecked")
            Map<String, Object> parsed = objectMapper.readValue(body, Map.class);
            Object monthly = parsed.get("monthlyTrend");
            int months = (monthly instanceof java.util.List) ? ((java.util.List<?>) monthly).size() : 0;
            log.debug("Gold trend-bundle factory={} range={}..{} months={} in {}ms",
                    factoryId, startDate, endDate, months, elapsed);
            return parsed;
        }
    }

    /**
     * Fetch top products by revenue from Python Gold layer.
     *
     * Delegates to the 5-arg overload with order="desc" for backward compatibility.
     * Existing callers (e.g. GoldDashboardBuilder) are unaffected.
     *
     * @return parsed JSON; key fields:
     *         - factory_id, start_month, end_month (rolled up to month grain)
     *         - top_products: List of {product_id, product_name, qty_sold,
     *           revenue, bill_count}, ordered by revenue DESC, max top_n
     * @throws IOException on transport / non-2xx / parse failure
     */
    public Map<String, Object> fetchTopProducts(
            String factoryId,
            LocalDate startDate,
            LocalDate endDate,
            int topN
    ) throws IOException {
        return fetchTopProducts(factoryId, startDate, endDate, topN, "desc");
    }

    /**
     * Fetch top products by revenue from Python Gold layer, with explicit sort order.
     *
     * Used to populate the 产品类别占比 pie chart on Dashboard. Note that
     * dim_product.category is NULL for restaurant tenants ingested before
     * v1.3, so we fall back to top product names as the breakdown — still
     * useful as a "热销产品 Top N" donut.
     *
     * @param order sort direction: "asc" or "desc" (default "desc"); any other
     *              value is silently normalized to "desc"
     * @return parsed JSON; key fields:
     *         - factory_id, start_month, end_month (rolled up to month grain)
     *         - top_products: List of {product_id, product_name, qty_sold,
     *           revenue, bill_count}, ordered by revenue per {@code order}, max top_n
     * @throws IOException on transport / non-2xx / parse failure
     */
    public Map<String, Object> fetchTopProducts(
            String factoryId,
            LocalDate startDate,
            LocalDate endDate,
            int topN,
            String order
    ) throws IOException {
        if (factoryId == null || factoryId.isEmpty()) {
            throw new IllegalArgumentException("factoryId required");
        }
        if (startDate == null || endDate == null) {
            throw new IllegalArgumentException("startDate and endDate required");
        }
        if (startDate.isAfter(endDate)) {
            throw new IllegalArgumentException("startDate > endDate");
        }
        if (topN < 1 || topN > 100) {
            throw new IllegalArgumentException("topN must be 1..100");
        }
        String normalizedOrder = ("asc".equals(order)) ? "asc" : "desc";

        HttpUrl url = HttpUrl.parse(config.getUrl() + "/api/smartbi/gold/top-products")
                .newBuilder()
                .addQueryParameter("factory_id", factoryId)
                .addQueryParameter("start_date", startDate.toString())
                .addQueryParameter("end_date", endDate.toString())
                .addQueryParameter("top_n", String.valueOf(topN))
                .addQueryParameter("order", normalizedOrder)
                .build();

        Request.Builder reqBuilder = new Request.Builder().url(url).get();
        if (!internalSecret.isEmpty()) {
            reqBuilder.addHeader("X-Internal-Secret", internalSecret);
            reqBuilder.addHeader("X-Factory-Id", factoryId);
            // Forward the request user's role so Python RBAC money-strip respects
            // price-view permission (else total_revenue/avg_bill_value get nulled).
            String userRole = currentUserRole();
            if (userRole != null && !userRole.isEmpty()) {
                reqBuilder.addHeader("X-User-Role", userRole);
            }
        }
        Request req = reqBuilder.build();

        long t0 = System.currentTimeMillis();
        try (Response resp = http.newCall(req).execute()) {
            long elapsed = System.currentTimeMillis() - t0;
            if (!resp.isSuccessful()) {
                String body = resp.body() != null ? resp.body().string() : "";
                throw new IOException(
                        "Gold top-products HTTP " + resp.code() + " in " + elapsed
                                + "ms: " + body);
            }
            String body = resp.body() != null ? resp.body().string() : "{}";
            @SuppressWarnings("unchecked")
            Map<String, Object> parsed = objectMapper.readValue(body, Map.class);
            Object items = parsed.get("top_products");
            int count = (items instanceof java.util.List) ? ((java.util.List<?>) items).size() : 0;
            log.debug("Gold top-products factory={} range={}..{} count={} order={} in {}ms",
                    factoryId, startDate, endDate, count, normalizedOrder, elapsed);
            return parsed;
        }
    }

    /**
     * Fetch order-type mix (dine-in vs takeout vs delivery breakdown) from Python Gold layer.
     *
     * @param factoryId factory (tenant) id — must match the caller's auth scope
     * @param startDate inclusive
     * @param endDate   inclusive; must be >= startDate
     * @return parsed JSON with order type breakdown; key fields vary by tenant
     *         but typically include order_type_breakdown (List), total_revenue, bill_count
     * @throws IOException if Gold is unreachable, returns non-2xx, or the body
     *                    doesn't parse. Caller should log + fall through to legacy.
     */
    public Map<String, Object> fetchOrderTypeMix(
            String factoryId,
            LocalDate startDate,
            LocalDate endDate
    ) throws IOException {
        if (factoryId == null || factoryId.isEmpty()) {
            throw new IllegalArgumentException("factoryId required");
        }
        if (startDate == null || endDate == null) {
            throw new IllegalArgumentException("startDate and endDate required");
        }
        if (startDate.isAfter(endDate)) {
            throw new IllegalArgumentException("startDate > endDate");
        }

        HttpUrl url = HttpUrl.parse(config.getUrl() + "/api/smartbi/gold/order-type-mix")
                .newBuilder()
                .addQueryParameter("factory_id", factoryId)
                .addQueryParameter("start_date", startDate.toString())
                .addQueryParameter("end_date", endDate.toString())
                .build();

        Request.Builder reqBuilder = new Request.Builder().url(url).get();
        if (!internalSecret.isEmpty()) {
            reqBuilder.addHeader("X-Internal-Secret", internalSecret);
            reqBuilder.addHeader("X-Factory-Id", factoryId);
            // Forward the request user's role so Python RBAC money-strip respects
            // price-view permission (else revenue fields get nulled).
            String userRole = currentUserRole();
            if (userRole != null && !userRole.isEmpty()) {
                reqBuilder.addHeader("X-User-Role", userRole);
            }
        }
        Request req = reqBuilder.build();

        long t0 = System.currentTimeMillis();
        try (Response resp = http.newCall(req).execute()) {
            long elapsed = System.currentTimeMillis() - t0;
            if (!resp.isSuccessful()) {
                String body = resp.body() != null ? resp.body().string() : "";
                throw new IOException(
                        "Gold order-type-mix HTTP " + resp.code() + " in " + elapsed
                                + "ms: " + body);
            }
            String body = resp.body() != null ? resp.body().string() : "{}";
            @SuppressWarnings("unchecked")
            Map<String, Object> parsed = objectMapper.readValue(body, Map.class);
            log.debug("Gold order-type-mix factory={} range={}..{} in {}ms",
                    factoryId, startDate, endDate, elapsed);
            return parsed;
        }
    }

    /**
     * Fetch staff performance ranking from Python Gold layer.
     *
     * @param factoryId factory (tenant) id — must match the caller's auth scope
     * @param startDate inclusive
     * @param endDate   inclusive; must be >= startDate
     * @param topN      max staff entries to return (1..50)
     * @return parsed JSON; key fields typically include staff_ranking
     *         (List of {staff_id, staff_name, revenue, bill_count, avg_bill_value})
     * @throws IOException if Gold is unreachable, returns non-2xx, or the body
     *                    doesn't parse. Caller should log + fall through to legacy.
     */
    public Map<String, Object> fetchStaffRanking(
            String factoryId,
            LocalDate startDate,
            LocalDate endDate,
            int topN
    ) throws IOException {
        if (factoryId == null || factoryId.isEmpty()) {
            throw new IllegalArgumentException("factoryId required");
        }
        if (startDate == null || endDate == null) {
            throw new IllegalArgumentException("startDate and endDate required");
        }
        if (startDate.isAfter(endDate)) {
            throw new IllegalArgumentException("startDate > endDate");
        }
        if (topN < 1 || topN > 50) {
            throw new IllegalArgumentException("topN must be 1..50");
        }

        HttpUrl url = HttpUrl.parse(config.getUrl() + "/api/smartbi/gold/staff-ranking")
                .newBuilder()
                .addQueryParameter("factory_id", factoryId)
                .addQueryParameter("start_date", startDate.toString())
                .addQueryParameter("end_date", endDate.toString())
                .addQueryParameter("top_n", String.valueOf(topN))
                .build();

        Request.Builder reqBuilder = new Request.Builder().url(url).get();
        if (!internalSecret.isEmpty()) {
            reqBuilder.addHeader("X-Internal-Secret", internalSecret);
            reqBuilder.addHeader("X-Factory-Id", factoryId);
            // Forward the request user's role so Python RBAC money-strip respects
            // price-view permission (else revenue fields get nulled).
            String userRole = currentUserRole();
            if (userRole != null && !userRole.isEmpty()) {
                reqBuilder.addHeader("X-User-Role", userRole);
            }
        }
        Request req = reqBuilder.build();

        long t0 = System.currentTimeMillis();
        try (Response resp = http.newCall(req).execute()) {
            long elapsed = System.currentTimeMillis() - t0;
            if (!resp.isSuccessful()) {
                String body = resp.body() != null ? resp.body().string() : "";
                throw new IOException(
                        "Gold staff-ranking HTTP " + resp.code() + " in " + elapsed
                                + "ms: " + body);
            }
            String body = resp.body() != null ? resp.body().string() : "{}";
            @SuppressWarnings("unchecked")
            Map<String, Object> parsed = objectMapper.readValue(body, Map.class);
            Object items = parsed.get("staff_ranking");
            int count = (items instanceof java.util.List) ? ((java.util.List<?>) items).size() : 0;
            log.debug("Gold staff-ranking factory={} range={}..{} count={} in {}ms",
                    factoryId, startDate, endDate, count, elapsed);
            return parsed;
        }
    }

    /**
     * Fetch the actual date range of a factory's Gold data (agg_daily).
     *
     * <p>Used by {@code GoldBackedRestaurantTool.resolveWindow} to default the
     * analysis window to the factory's real data span instead of an arbitrary
     * "last 7 days from today" window.
     *
     * <p>Response keys (Python Gold {@code data_range} function):
     * <ul>
     *   <li>{@code factory_id} — echoes input</li>
     *   <li>{@code min_date} — ISO {@code yyyy-MM-dd} string of the earliest row in
     *       {@code agg_daily}, or {@code null} when the factory has no Gold rows yet</li>
     *   <li>{@code max_date} — ISO {@code yyyy-MM-dd} string of the latest row, or
     *       {@code null} when no rows</li>
     *   <li>{@code day_count} — number of distinct dates with data (0 when no rows)</li>
     * </ul>
     *
     * <p>No money fields → no RBAC strip on the Python side → no X-User-Role needed;
     * still forwarded for consistency with other Gold calls.
     *
     * @param factoryId factory (tenant) id — must match the caller's auth scope
     * @return parsed JSON as a Map with keys described above
     * @throws IOException if Gold is unreachable, returns non-2xx, or body doesn't parse
     */
    public Map<String, Object> fetchDataRange(String factoryId) throws IOException {
        if (factoryId == null || factoryId.isEmpty()) {
            throw new IllegalArgumentException("factoryId required");
        }

        HttpUrl url = HttpUrl.parse(config.getUrl() + "/api/smartbi/gold/data-range")
                .newBuilder()
                .addQueryParameter("factory_id", factoryId)
                .build();

        Request.Builder reqBuilder = new Request.Builder().url(url).get();
        if (!internalSecret.isEmpty()) {
            reqBuilder.addHeader("X-Internal-Secret", internalSecret);
            reqBuilder.addHeader("X-Factory-Id", factoryId);
            String userRole = currentUserRole();
            if (userRole != null && !userRole.isEmpty()) {
                reqBuilder.addHeader("X-User-Role", userRole);
            }
        }
        Request req = reqBuilder.build();

        long t0 = System.currentTimeMillis();
        try (Response resp = http.newCall(req).execute()) {
            long elapsed = System.currentTimeMillis() - t0;
            if (!resp.isSuccessful()) {
                String body = resp.body() != null ? resp.body().string() : "";
                throw new IOException(
                        "Gold data-range HTTP " + resp.code() + " in " + elapsed
                                + "ms: " + body);
            }
            String body = resp.body() != null ? resp.body().string() : "{}";
            @SuppressWarnings("unchecked")
            Map<String, Object> parsed = objectMapper.readValue(body, Map.class);
            log.debug("Gold data-range factory={} min_date={} max_date={} day_count={} in {}ms",
                    factoryId, parsed.get("min_date"), parsed.get("max_date"),
                    parsed.get("day_count"), elapsed);
            return parsed;
        }
    }

    /**
     * Fetch discount/promotion breakdown from Python Gold layer.
     *
     * @param factoryId factory (tenant) id — must match the caller's auth scope
     * @param startDate inclusive
     * @param endDate   inclusive; must be >= startDate
     * @param topN      max discount categories to return (1..100)
     * @return parsed JSON; key fields typically include discount_breakdown
     *         (List of {discount_type, discount_amount, bill_count, revenue_impact})
     * @throws IOException if Gold is unreachable, returns non-2xx, or the body
     *                    doesn't parse. Caller should log + fall through to legacy.
     */
    public Map<String, Object> fetchDiscountBreakdown(
            String factoryId,
            LocalDate startDate,
            LocalDate endDate,
            int topN
    ) throws IOException {
        if (factoryId == null || factoryId.isEmpty()) {
            throw new IllegalArgumentException("factoryId required");
        }
        if (startDate == null || endDate == null) {
            throw new IllegalArgumentException("startDate and endDate required");
        }
        if (startDate.isAfter(endDate)) {
            throw new IllegalArgumentException("startDate > endDate");
        }
        if (topN < 1 || topN > 100) {
            throw new IllegalArgumentException("topN must be 1..100");
        }

        HttpUrl url = HttpUrl.parse(config.getUrl() + "/api/smartbi/gold/discount-breakdown")
                .newBuilder()
                .addQueryParameter("factory_id", factoryId)
                .addQueryParameter("start_date", startDate.toString())
                .addQueryParameter("end_date", endDate.toString())
                .addQueryParameter("top_n", String.valueOf(topN))
                .build();

        Request.Builder reqBuilder = new Request.Builder().url(url).get();
        if (!internalSecret.isEmpty()) {
            reqBuilder.addHeader("X-Internal-Secret", internalSecret);
            reqBuilder.addHeader("X-Factory-Id", factoryId);
            // Forward the request user's role so Python RBAC money-strip respects
            // price-view permission (else revenue/amount fields get nulled).
            String userRole = currentUserRole();
            if (userRole != null && !userRole.isEmpty()) {
                reqBuilder.addHeader("X-User-Role", userRole);
            }
        }
        Request req = reqBuilder.build();

        long t0 = System.currentTimeMillis();
        try (Response resp = http.newCall(req).execute()) {
            long elapsed = System.currentTimeMillis() - t0;
            if (!resp.isSuccessful()) {
                String body = resp.body() != null ? resp.body().string() : "";
                throw new IOException(
                        "Gold discount-breakdown HTTP " + resp.code() + " in " + elapsed
                                + "ms: " + body);
            }
            String body = resp.body() != null ? resp.body().string() : "{}";
            @SuppressWarnings("unchecked")
            Map<String, Object> parsed = objectMapper.readValue(body, Map.class);
            Object items = parsed.get("discount_breakdown");
            int count = (items instanceof java.util.List) ? ((java.util.List<?>) items).size() : 0;
            log.debug("Gold discount-breakdown factory={} range={}..{} count={} in {}ms",
                    factoryId, startDate, endDate, count, elapsed);
            return parsed;
        }
    }

    /**
     * Fetch P3 门店评分 × 营收 cross-dataset analysis from Python Gold layer.
     *
     * <p>Joins per-store review scores (大众点评 评价) with per-store POS revenue
     * (gold agg_daily) via the {@code dim_store_review_alias} bridge. The window
     * scopes the REVENUE half (review scores are over the full corpus).
     *
     * <p><b>RBAC</b>: the response's {@code linked_stores[].revenue} (+ bill_count
     * is a count, kept) is monetary → Python {@code _apply_rbac_strip} nulls it for
     * non-price-view roles. {@code avg_rating} / {@code review_count} are NOT money
     * and stay visible. We forward X-User-Role so price-view roles see revenue
     * (per feedback_java_python_rbac_role_forward — else revenue回 ¥0/null).
     *
     * @param factoryId     tenant id
     * @param startDate     inclusive (营收口径)
     * @param endDate       inclusive; must be ≥ startDate
     * @param minConfidence alias 进 join 的最低置信 (0..1); admin 行不受此限
     * @return parsed JSON; key fields: linked_stores (List of {store_id,
     *         gold_store_name, review_store_name, avg_rating, review_count, revenue,
     *         bill_count, alias_confidence, alias_decided_by}), linked_count,
     *         total_review_stores, total_gold_stores, unlinked_review_stores (List),
     *         unlinked_count, correlation ({metric, value, n, note} or null),
     *         honest_note, optional next_action
     * @throws IOException on transport / non-2xx / parse failure
     */
    public Map<String, Object> fetchStoreReviewRevenue(
            String factoryId,
            LocalDate startDate,
            LocalDate endDate,
            double minConfidence
    ) throws IOException {
        if (factoryId == null || factoryId.isEmpty()) {
            throw new IllegalArgumentException("factoryId required");
        }
        if (startDate == null || endDate == null) {
            throw new IllegalArgumentException("startDate and endDate required");
        }
        if (startDate.isAfter(endDate)) {
            throw new IllegalArgumentException("startDate > endDate");
        }
        if (minConfidence < 0.0 || minConfidence > 1.0) {
            throw new IllegalArgumentException("minConfidence must be 0..1");
        }

        HttpUrl url = HttpUrl.parse(config.getUrl() + "/api/smartbi/gold/store-review-revenue")
                .newBuilder()
                .addQueryParameter("factory_id", factoryId)
                .addQueryParameter("start_date", startDate.toString())
                .addQueryParameter("end_date", endDate.toString())
                .addQueryParameter("min_confidence", String.valueOf(minConfidence))
                .build();

        Request.Builder reqBuilder = new Request.Builder().url(url).get();
        if (!internalSecret.isEmpty()) {
            reqBuilder.addHeader("X-Internal-Secret", internalSecret);
            reqBuilder.addHeader("X-Factory-Id", factoryId);
            // Forward role so Python RBAC keeps revenue for price-view roles
            // (per feedback_java_python_rbac_role_forward).
            String userRole = currentUserRole();
            if (userRole != null && !userRole.isEmpty()) {
                reqBuilder.addHeader("X-User-Role", userRole);
            }
        }
        Request req = reqBuilder.build();

        long t0 = System.currentTimeMillis();
        try (Response resp = http.newCall(req).execute()) {
            long elapsed = System.currentTimeMillis() - t0;
            if (!resp.isSuccessful()) {
                String body = resp.body() != null ? resp.body().string() : "";
                throw new IOException(
                        "Gold store-review-revenue HTTP " + resp.code() + " in " + elapsed
                                + "ms: " + body);
            }
            String body = resp.body() != null ? resp.body().string() : "{}";
            @SuppressWarnings("unchecked")
            Map<String, Object> parsed = objectMapper.readValue(body, Map.class);
            log.debug("Gold store-review-revenue factory={} range={}..{} linked={} in {}ms",
                    factoryId, startDate, endDate, parsed.get("linked_count"), elapsed);
            return parsed;
        }
    }

    // =========================================================================
    // Review-analytics fetches (大众点评 评价下载 data)
    //
    // Reviews carry no money fields and no date dimension (aggregated over the
    // full review corpus), so these GET endpoints take only factory_id plus a
    // few shaping params. Shared via getReviewJson to avoid 6× okhttp
    // boilerplate. X-User-Role is still forwarded for consistency (no-op for
    // money-strip since reviews have no monetary keys).
    // =========================================================================

    private static void requireFactory(String factoryId) {
        if (factoryId == null || factoryId.isEmpty()) {
            throw new IllegalArgumentException("factoryId required");
        }
    }

    /**
     * Shared GET → parsed-JSON helper for the review endpoints.
     *
     * @param path        endpoint path under config.getUrl()
     * @param queryParams query params; null values are skipped. Must include
     *                    {@code factory_id} (used for the X-Factory-Id header)
     * @return parsed JSON as a Map; never null
     * @throws IOException on transport / non-2xx / parse failure
     */
    private Map<String, Object> getReviewJson(String path, Map<String, String> queryParams)
            throws IOException {
        HttpUrl.Builder ub = HttpUrl.parse(config.getUrl() + path).newBuilder();
        for (Map.Entry<String, String> e : queryParams.entrySet()) {
            if (e.getValue() != null) {
                ub.addQueryParameter(e.getKey(), e.getValue());
            }
        }
        Request.Builder reqBuilder = new Request.Builder().url(ub.build()).get();
        if (!internalSecret.isEmpty()) {
            reqBuilder.addHeader("X-Internal-Secret", internalSecret);
            reqBuilder.addHeader("X-Factory-Id", queryParams.getOrDefault("factory_id", ""));
            String userRole = currentUserRole();
            if (userRole != null && !userRole.isEmpty()) {
                reqBuilder.addHeader("X-User-Role", userRole);
            }
        }
        long t0 = System.currentTimeMillis();
        try (Response resp = http.newCall(reqBuilder.build()).execute()) {
            long elapsed = System.currentTimeMillis() - t0;
            if (!resp.isSuccessful()) {
                String body = resp.body() != null ? resp.body().string() : "";
                throw new IOException(
                        "Gold " + path + " HTTP " + resp.code() + " in " + elapsed + "ms: " + body);
            }
            String body = resp.body() != null ? resp.body().string() : "{}";
            @SuppressWarnings("unchecked")
            Map<String, Object> parsed = objectMapper.readValue(body, Map.class);
            log.debug("Gold {} factory={} in {}ms", path, queryParams.get("factory_id"), elapsed);
            return parsed;
        }
    }

    /** Overall review KPIs (avg 星级/服务/环境/口味, totals, VIP, store/city counts). */
    public Map<String, Object> fetchReviewSummary(String factoryId) throws IOException {
        requireFactory(factoryId);
        return getReviewJson("/api/smartbi/gold/review-summary",
                Map.of("factory_id", factoryId));
    }

    /**
     * Per-store review aggregates sorted by {@code dim}.
     *
     * @param dim        one of star|service|env|low_star
     * @param order      asc|desc
     * @param topN       max stores (1..50)
     * @param minReviews minimum review count per store to be included
     */
    public Map<String, Object> fetchReviewStoreRanking(
            String factoryId, String dim, String order, int topN, int minReviews)
            throws IOException {
        requireFactory(factoryId);
        return getReviewJson("/api/smartbi/gold/review-store-ranking", Map.of(
                "factory_id", factoryId,
                "dim", dim,
                "order", order,
                "top_n", String.valueOf(topN),
                "min_reviews", String.valueOf(minReviews)));
    }

    /** Per-city review averages (lowest-rated first). */
    public Map<String, Object> fetchReviewCityRanking(String factoryId) throws IOException {
        requireFactory(factoryId);
        return getReviewJson("/api/smartbi/gold/review-city-ranking",
                Map.of("factory_id", factoryId));
    }

    /** VIP vs 非VIP review comparison. */
    public Map<String, Object> fetchReviewVip(String factoryId) throws IOException {
        requireFactory(factoryId);
        return getReviewJson("/api/smartbi/gold/review-vip",
                Map.of("factory_id", factoryId));
    }

    /** Merchant review-dispute categories + the real 低星(<=3星) review count. */
    public Map<String, Object> fetchReviewComplaints(String factoryId, int topN) throws IOException {
        requireFactory(factoryId);
        return getReviewJson("/api/smartbi/gold/review-complaints", Map.of(
                "factory_id", factoryId,
                "top_n", String.valueOf(topN)));
    }

    /** High-frequency 口味/品质标签 in low-star reviews. */
    public Map<String, Object> fetchReviewDishIssues(
            String factoryId, int topN, int starThreshold) throws IOException {
        requireFactory(factoryId);
        return getReviewJson("/api/smartbi/gold/review-dish-issues", Map.of(
                "factory_id", factoryId,
                "top_n", String.valueOf(topN),
                "star_threshold", String.valueOf(starThreshold)));
    }

    // =========================================================================
    // P1 conversational-depth review fetches (2026-06-02)
    // within-review cross-dim (vip_tags / time_period / score_tags) +
    // more review questions (good_tags / platform / trend / reply_rate).
    // =========================================================================

    /** VIP vs 非VIP 各自高频好评/差评口味标签 (非菜名)。 */
    public Map<String, Object> fetchReviewVipTags(String factoryId, int topN) throws IOException {
        requireFactory(factoryId);
        return getReviewJson("/api/smartbi/gold/review-vip-tags", Map.of(
                "factory_id", factoryId,
                "top_n", String.valueOf(topN)));
    }

    /** 各时段 (早/午/下午/晚/夜) 评价量与平均星级。 */
    public Map<String, Object> fetchReviewTimePeriod(String factoryId) throws IOException {
        requireFactory(factoryId);
        return getReviewJson("/api/smartbi/gold/review-time-period",
                Map.of("factory_id", factoryId));
    }

    /**
     * 服务标签 / 环境标签 高频词 + 平均分。
     * @param dim service|env
     */
    public Map<String, Object> fetchReviewScoreTags(String factoryId, String dim, int topN)
            throws IOException {
        requireFactory(factoryId);
        return getReviewJson("/api/smartbi/gold/review-score-tags", Map.of(
                "factory_id", factoryId,
                "dim", dim,
                "top_n", String.valueOf(topN)));
    }

    /** 好评(>=4.5星)高频口味/品质标签 (非菜名)。 */
    public Map<String, Object> fetchReviewGoodTags(String factoryId, int topN) throws IOException {
        requireFactory(factoryId);
        return getReviewJson("/api/smartbi/gold/review-good-tags", Map.of(
                "factory_id", factoryId,
                "top_n", String.valueOf(topN)));
    }

    /** 各平台 (点评/美团) 评价量与平均星级对比。 */
    public Map<String, Object> fetchReviewPlatform(String factoryId) throws IOException {
        requireFactory(factoryId);
        return getReviewJson("/api/smartbi/gold/review-platform",
                Map.of("factory_id", factoryId));
    }

    /** 按 time_period 月份聚合评价量与平均星级 (时间序列)。 */
    public Map<String, Object> fetchReviewTrend(String factoryId) throws IOException {
        requireFactory(factoryId);
        return getReviewJson("/api/smartbi/gold/review-trend",
                Map.of("factory_id", factoryId));
    }

    /** 商家回复率 (已/未回复 + 未回复差评数)。 */
    public Map<String, Object> fetchReviewReplyRate(String factoryId) throws IOException {
        requireFactory(factoryId);
        return getReviewJson("/api/smartbi/gold/review-reply-rate",
                Map.of("factory_id", factoryId));
    }

    // =========================================================================
    // P2 综合分析 (multi-dim synthesis) — POST /api/smartbi/synthesis/comprehensive
    //
    // Unlike the GET review/finance fetches, synthesis is a POST: the Python
    // engine aggregates review + finance/sales into a FactBook, asks the LLM for
    // a grounded narrative (redacted at egress, restored on return), fact-checks
    // it, and returns answer + charts + plan + fact_check. The window is optional
    // — when start/end are null the Python side defaults to the factory's Gold
    // data span (so the tool can pass the resolved [start,end] or let Python
    // resolve). X-User-Role forwarded so the gold finance pulls' RBAC sees the
    // caller's price-view permission (else revenue gets nulled).
    // =========================================================================

    private static final MediaType JSON_MEDIA = MediaType.get("application/json; charset=utf-8");

    /**
     * Fetch a comprehensive multi-dimension synthesis from the Python engine.
     *
     * @param factoryId tenant id
     * @param question  the user's free-form question (e.g. "综合分析评价和经营")
     * @param startDate inclusive window start; nullable (Python defaults to Gold data range)
     * @param endDate   inclusive window end; nullable
     * @return parsed JSON: answer (markdown), charts (List of chart configs), plan,
     *         fact_check, source, tokens
     * @throws IOException on transport / non-2xx / parse failure
     */
    public Map<String, Object> fetchComprehensiveSynthesis(
            String factoryId,
            String question,
            LocalDate startDate,
            LocalDate endDate
    ) throws IOException {
        requireFactory(factoryId);
        if (question == null || question.trim().isEmpty()) {
            throw new IllegalArgumentException("question required");
        }

        java.util.Map<String, Object> bodyMap = new java.util.LinkedHashMap<>();
        bodyMap.put("question", question);
        bodyMap.put("factory_id", factoryId);
        if (startDate != null) {
            bodyMap.put("start_date", startDate.toString());
        }
        if (endDate != null) {
            bodyMap.put("end_date", endDate.toString());
        }
        String json = objectMapper.writeValueAsString(bodyMap);

        Request.Builder reqBuilder = new Request.Builder()
                .url(config.getUrl() + "/api/smartbi/synthesis/comprehensive")
                .post(RequestBody.create(json, JSON_MEDIA));
        if (!internalSecret.isEmpty()) {
            reqBuilder.addHeader("X-Internal-Secret", internalSecret);
            reqBuilder.addHeader("X-Factory-Id", factoryId);
            String userRole = currentUserRole();
            if (userRole != null && !userRole.isEmpty()) {
                reqBuilder.addHeader("X-User-Role", userRole);
            }
        }
        Request req = reqBuilder.build();

        long t0 = System.currentTimeMillis();
        try (Response resp = http.newCall(req).execute()) {
            long elapsed = System.currentTimeMillis() - t0;
            if (!resp.isSuccessful()) {
                String body = resp.body() != null ? resp.body().string() : "";
                throw new IOException(
                        "Gold synthesis HTTP " + resp.code() + " in " + elapsed + "ms: " + body);
            }
            String body = resp.body() != null ? resp.body().string() : "{}";
            @SuppressWarnings("unchecked")
            Map<String, Object> parsed = objectMapper.readValue(body, Map.class);
            log.debug("Gold synthesis factory={} source={} tokens={} in {}ms",
                    factoryId, parsed.get("source"), parsed.get("tokens"), elapsed);
            return parsed;
        }
    }

    // =========================================================================
    // G2 餐饮目标拆分 + 达成率预警 (2026-06-03)
    // GET /api/smartbi/restaurant-targets/achievement + /alerts
    // Same OkHttp + X-Internal-Secret + X-Factory-Id + X-User-Role pattern.
    // =========================================================================

    /**
     * Fetch daily achievement summary from Python
     * {@code /api/smartbi/restaurant-targets/achievement}.
     *
     * <p>Pattern mirrors {@link #fetchFinanceSummary}: OkHttp GET + internal
     * secret + role forward. Response is snake_case; the achievement
     * {@code target}/{@code actual} fields are nulled Python-side for non
     * price-view roles when {@code kpiKind=revenue}, so X-User-Role is forwarded.
     *
     * @param factoryId tenant id (required)
     * @param startDate inclusive start (required)
     * @param endDate   inclusive end (required, must be &gt;= startDate)
     * @param kpiKind   "revenue" | "bill_count"
     * @param level     "day" | "week" | "month" | "year"
     * @return parsed JSON; key shape: {factory_id, kpi_kind, level,
     *         points:[{period_key, target, actual, achievement_rate, data_missing}],
     *         period_without_target}
     * @throws IOException on transport / non-2xx / parse failure
     */
    public Map<String, Object> fetchAchievement(
            String factoryId,
            LocalDate startDate,
            LocalDate endDate,
            String kpiKind,
            String level
    ) throws IOException {
        if (factoryId == null || factoryId.isEmpty()) {
            throw new IllegalArgumentException("factoryId required");
        }
        if (startDate == null || endDate == null) {
            throw new IllegalArgumentException("startDate and endDate required");
        }
        if (startDate.isAfter(endDate)) {
            throw new IllegalArgumentException("startDate > endDate");
        }

        HttpUrl url = HttpUrl.parse(config.getUrl() + "/api/smartbi/restaurant-targets/achievement")
                .newBuilder()
                .addQueryParameter("start_date", startDate.toString())
                .addQueryParameter("end_date", endDate.toString())
                .addQueryParameter("kpi_kind", kpiKind != null ? kpiKind : "revenue")
                .addQueryParameter("level", level != null ? level : "day")
                .build();

        Request.Builder reqBuilder = new Request.Builder().url(url).get();
        if (!internalSecret.isEmpty()) {
            reqBuilder.addHeader("X-Internal-Secret", internalSecret);
            reqBuilder.addHeader("X-Factory-Id", factoryId);
            String userRole = currentUserRole();
            if (userRole != null && !userRole.isEmpty()) {
                reqBuilder.addHeader("X-User-Role", userRole);
            }
        }
        Request req = reqBuilder.build();

        long t0 = System.currentTimeMillis();
        try (Response resp = http.newCall(req).execute()) {
            long elapsed = System.currentTimeMillis() - t0;
            if (!resp.isSuccessful()) {
                String body = resp.body() != null ? resp.body().string() : "";
                throw new IOException(
                        "Gold achievement HTTP " + resp.code() + " in " + elapsed
                                + "ms: " + body);
            }
            String body = resp.body() != null ? resp.body().string() : "{}";
            @SuppressWarnings("unchecked")
            Map<String, Object> parsed = objectMapper.readValue(body, Map.class);
            log.debug("Gold achievement factory={} range={}..{} level={} in {}ms",
                    factoryId, startDate, endDate, level, elapsed);
            return parsed;
        }
    }

    /**
     * Fetch the N-day alert preview from
     * {@code /api/smartbi/restaurant-targets/alerts}.
     *
     * @param factoryId    tenant id (required)
     * @param lookbackDays 1..30 inclusive
     * @param kpiKind      "revenue" | "bill_count"
     * @return parsed JSON; key shape: {config_exists,
     *         timeline:[{date, status, achievement_rate, target, actual}],
     *         summary:{OK, WARN, CRITICAL, NO_TARGET, DATA_MISSING}}
     * @throws IOException on transport / non-2xx / parse failure
     */
    public Map<String, Object> fetchAlerts(
            String factoryId,
            int lookbackDays,
            String kpiKind
    ) throws IOException {
        if (factoryId == null || factoryId.isEmpty()) {
            throw new IllegalArgumentException("factoryId required");
        }
        if (lookbackDays < 1 || lookbackDays > 30) {
            throw new IllegalArgumentException("lookbackDays must be 1..30");
        }

        HttpUrl url = HttpUrl.parse(config.getUrl() + "/api/smartbi/restaurant-targets/alerts")
                .newBuilder()
                .addQueryParameter("lookback_days", String.valueOf(lookbackDays))
                .addQueryParameter("kpi_kind", kpiKind != null ? kpiKind : "revenue")
                .build();

        Request.Builder reqBuilder = new Request.Builder().url(url).get();
        if (!internalSecret.isEmpty()) {
            reqBuilder.addHeader("X-Internal-Secret", internalSecret);
            reqBuilder.addHeader("X-Factory-Id", factoryId);
            String userRole = currentUserRole();
            if (userRole != null && !userRole.isEmpty()) {
                reqBuilder.addHeader("X-User-Role", userRole);
            }
        }
        Request req = reqBuilder.build();

        long t0 = System.currentTimeMillis();
        try (Response resp = http.newCall(req).execute()) {
            long elapsed = System.currentTimeMillis() - t0;
            if (!resp.isSuccessful()) {
                String body = resp.body() != null ? resp.body().string() : "";
                throw new IOException(
                        "Gold alerts HTTP " + resp.code() + " in " + elapsed
                                + "ms: " + body);
            }
            String body = resp.body() != null ? resp.body().string() : "{}";
            @SuppressWarnings("unchecked")
            Map<String, Object> parsed = objectMapper.readValue(body, Map.class);
            log.debug("Gold alerts factory={} lookback={} kpi={} config_exists={} in {}ms",
                    factoryId, lookbackDays, kpiKind, parsed.get("config_exists"), elapsed);
            return parsed;
        }
    }

    // =========================================================================
    // 供应商价格预警 (邓总点名痛点) — GET /api/smartbi/gold/price-anomaly/detect
    //
    // Reuses Wave2 #53's detector (same agg_supplier_price history + ack/累计
    // 高风险 infra) via its baseline_mode=days mode — 邓总 locked the basis at
    // "自身 90 天移动均价". This client wraps that endpoint for the AI tool so
    // 老板 can ask "哪个供应商涨价了" in 智能问答, not only via the price-anomaly board.
    //
    // RBAC: deltaPct (率) + direction/riskLevel (威慑信号) stay visible; absolute
    // prices (oldPrice/newPrice/trailingAvg) are nulled Python-side for non
    // price-view roles. We forward X-User-Role so procurement roles see amounts
    // (per feedback_java_python_rbac_role_forward). The detect endpoint emits the
    // {success, data, message} envelope, so we unwrap to the data list here.
    // =========================================================================

    /**
     * Detect supplier price anomalies against a moving-average baseline.
     *
     * @param factoryId    tenant id (required)
     * @param baselineMode "days" (邓总 90-day moving avg) or "count" (legacy trailing-N)
     * @param windowDays   moving-average window in days for {@code days} mode (1..365);
     *                     ignored in {@code count} mode
     * @param epsilonPct   anomaly tolerance ε in percent (0..100); deviations within
     *                     ±ε of the baseline are NOT flagged
     * @return the list of anomaly rows (camelCase keys: normalizedName, ingredientName,
     *         supplierId, supplierName, unit, anomalyDeliveryDate, oldPrice, newPrice,
     *         trailingAvg, deltaPct, direction, consecutiveAnomalyCount, riskLevel);
     *         empty list when none / on a {@code success:false} envelope
     * @throws IOException on transport / non-2xx / parse failure
     */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> fetchPriceAnomalies(
            String factoryId,
            String baselineMode,
            int windowDays,
            double epsilonPct
    ) throws IOException {
        if (factoryId == null || factoryId.isEmpty()) {
            throw new IllegalArgumentException("factoryId required");
        }
        String mode = "count".equals(baselineMode) ? "count" : "days";
        if (windowDays < 1) windowDays = 1;
        if (windowDays > 365) windowDays = 365;
        if (epsilonPct < 0.0) epsilonPct = 0.0;
        if (epsilonPct > 100.0) epsilonPct = 100.0;

        HttpUrl url = HttpUrl.parse(config.getUrl() + "/api/smartbi/gold/price-anomaly/detect")
                .newBuilder()
                .addQueryParameter("baseline_mode", mode)
                .addQueryParameter("window_days", String.valueOf(windowDays))
                .addQueryParameter("epsilon_pct", String.valueOf(epsilonPct))
                .build();

        Request.Builder reqBuilder = new Request.Builder().url(url).get();
        if (!internalSecret.isEmpty()) {
            reqBuilder.addHeader("X-Internal-Secret", internalSecret);
            reqBuilder.addHeader("X-Factory-Id", factoryId);
            // Forward role so Python RBAC keeps absolute prices for price-view roles.
            String userRole = currentUserRole();
            if (userRole != null && !userRole.isEmpty()) {
                reqBuilder.addHeader("X-User-Role", userRole);
            }
        }
        Request req = reqBuilder.build();

        long t0 = System.currentTimeMillis();
        try (Response resp = http.newCall(req).execute()) {
            long elapsed = System.currentTimeMillis() - t0;
            if (!resp.isSuccessful()) {
                String body = resp.body() != null ? resp.body().string() : "";
                throw new IOException(
                        "Gold price-anomaly detect HTTP " + resp.code() + " in " + elapsed
                                + "ms: " + body);
            }
            String body = resp.body() != null ? resp.body().string() : "{}";
            Map<String, Object> envelope = objectMapper.readValue(body, Map.class);
            Object ok = envelope.get("success");
            if (Boolean.FALSE.equals(ok)) {
                Object msg = envelope.get("message");
                throw new IOException("Gold price-anomaly detect success=false: " + msg);
            }
            Object data = envelope.get("data");
            List<Map<String, Object>> rows = (data instanceof List)
                    ? (List<Map<String, Object>>) data
                    : java.util.Collections.emptyList();
            log.debug("Gold price-anomaly detect factory={} mode={} window={} eps={} rows={} in {}ms",
                    factoryId, mode, windowDays, epsilonPct, rows.size(), elapsed);
            return rows;
        }
    }

    /**
     * Ask Python's restaurant-ops Gold router through the same general-analysis
     * endpoint used by SmartBI chat. This keeps Java intent execution on the
     * curated Python templates instead of falling back to dynamic tool planning.
     */
    public Map<String, Object> fetchRestaurantOpsAnalysis(
            String factoryId,
            String question,
            String sessionId
    ) throws IOException {
        return fetchRestaurantOpsAnalysis(factoryId, question, sessionId, null);
    }

    public Map<String, Object> fetchRestaurantOpsAnalysis(
            String factoryId,
            String question,
            String sessionId,
            String expectedIntent
    ) throws IOException {
        return fetchRestaurantOpsAnalysis(
                factoryId, question, sessionId, expectedIntent, java.util.Collections.emptyMap());
    }

    public Map<String, Object> fetchRestaurantOpsAnalysis(
            String factoryId,
            String question,
            String sessionId,
            String expectedIntent,
            Map<String, Object> analysisContext
    ) throws IOException {
        requireFactory(factoryId);
        if (question == null || question.trim().isEmpty()) {
            throw new IllegalArgumentException("question required");
        }

        if (pythonSmartBIClient == null) {
            throw new IOException("Restaurant ops analysis transport is unavailable");
        }
        PythonSmartBIClient.GeneralAnalysisCall request =
                new PythonSmartBIClient.GeneralAnalysisCall(
                        question,
                        null,
                        "restaurant_ops",
                        expectedIntent,
                        sessionId != null && !sessionId.isBlank()
                                ? sessionId : "java-restaurant-ops",
                        false,
                        0,
                        false,
                        analysisContext == null || analysisContext.isEmpty()
                                ? null : analysisContext);
        PythonGeneralAnalysisResponse response = pythonSmartBIClient.analyzeGeneral(
                factoryId,
                currentUserId(),
                currentUserRole(),
                request);
        Map<String, Object> parsed = objectMapper.convertValue(
                response, new TypeReference<Map<String, Object>>() { });
        log.debug("Restaurant ops analysis factory={} charts={}",
                factoryId,
                response.getCharts() != null ? response.getCharts().size() : 0);
        return parsed;
    }

    // =========================================================================
    // Phase 2 Java-entry delegate gate (2026-07-07)
    // POST /api/smartbi/gold/restaurant/tiered-answer
    //
    // Design: docs/superpowers/specs/2026-07-07-restaurant-intent-phase2-java-entry-design.md
    //
    // Called from GoldBackedRestaurantTool.doExecute BEFORE its own
    // resolveWindow -> queryGold -> format flow runs, to ask Python's tiered
    // (T1/T2/T3) restaurant-intent router + Answer Contract whether THIS
    // query needs that path instead (profitability/margin verdicts and
    // relative-window ops summaries that the Java Gold Tool family cannot
    // produce). X-User-Role forwarded so the Python-side RBAC money-strip
    // sees the caller's price-view permission, same as every other fetch*
    // method here.
    // =========================================================================

    /** Per-call timeout for {@link #fetchTieredIntentAnswer} — T3 LLM parse
     * is capped at 5s Python-side; 10s leaves headroom for the vector/DB
     * resolve + network round-trip without holding the Tool call open too
     * long (design section 4: "timeout 10s（T3 LLM 最多 5s + resolve）"). */
    private static final long TIERED_ANSWER_TIMEOUT_MS = 10_000L;

    /**
     * Ask Python's tiered restaurant-intent router whether {@code userInput}
     * should be delegated to the Python path instead of this Tool's own Gold
     * flow.
     *
     * <p><b>Deviates from every other {@code fetchX} method in this class:
     * this method NEVER throws.</b> Per design section 4 ("任何非 200/异常返回
     * null"), any transport failure, non-2xx response, or parse error is
     * logged at WARN and swallowed, returning {@code null}. This keeps
     * {@code GoldBackedRestaurantTool.doExecute}'s delegate gate a simple
     * null-check — the gate itself still wraps the whole decision in a
     * defensive try/catch (design section 4: "catch Exception 必须 log.warn
     * 后 fall through，绝不向上抛"), belt-and-suspenders in case something
     * other than this HTTP call throws.
     *
     * @param factoryId    tenant id — the RAW factoryId, deliberately NOT
     *                     passed through {@code resolveGoldFactoryId}
     *                     (DEMO_REST → RES_3101_009 aliasing). The Python
     *                     restaurant-intent path handles DEMO_REST directly
     *                     against its own seeded Gold data (V20260706_01
     *                     migration), matching chat.py's existing
     *                     factory_id_hdr usage at its 3 SSE call sites —
     *                     aliasing here would look up the wrong tenant's
     *                     restaurant-ops rows (design section 4).
     * @param userInput    the user's free-form question; {@code null}/blank
     *                     short-circuits to {@code null} without a network
     *                     call (mirrors the Tool-side "userInput 空 → 跳过"
     *                     rule so this method is always safe to call
     *                     unconditionally)
     * @param javaToolName the calling Tool's {@code getToolName()} —
     *                     observability tag forwarded as {@code java_tool_name}
     *                     in the request body; not currently branched on by
     *                     the Python decision function (reserved for future
     *                     per-tool exceptions)
     * @return parsed response map: {@code {"delegate": false}} when Java
     *         should keep its own flow; {@code {"delegate": true, "kind":
     *         "clarification", "answer_text": ...}} for a clarifying
     *         question; or {@code {"delegate": true, "answer_text": ...,
     *         "charts": [...], "kpis": [...], "code": ..., "contract_pass":
     *         ...}} for a full answer. {@code null} on any failure.
     */
    public Map<String, Object> fetchTieredIntentAnswer(
            String factoryId,
            String userInput,
            String javaToolName
    ) {
        return fetchTieredIntentAnswer(factoryId, userInput, javaToolName, null);
    }

    /**
     * Overload of {@link #fetchTieredIntentAnswer(String, String, String)}
     * that also forwards the caller's chat session id (2026-07-08
     * clarification-loop v1), so a multi-turn answer to a PREVIOUS
     * clarification question on the Python side can be matched back to the
     * pending clarification it is answering instead of being parsed as a
     * brand-new, context-free query.
     *
     * @param sessionId caller's chat session id, or {@code null}/blank when
     *                  unavailable (e.g. this Tool was dispatched via a
     *                  context-building path that doesn't carry the raw
     *                  request — see {@code GoldBackedRestaurantTool}'s
     *                  {@code extractSessionId} javadoc). Omitted from the
     *                  request body entirely when null/blank — Python
     *                  treats a missing {@code session_id} exactly like an
     *                  absent one: continuation is simply never attempted
     *                  for that call, zero behavior change from before this
     *                  parameter existed.
     */
    public Map<String, Object> fetchTieredIntentAnswer(
            String factoryId,
            String userInput,
            String javaToolName,
            String sessionId
    ) {
        if (factoryId == null || factoryId.trim().isEmpty()) {
            log.debug("fetchTieredIntentAnswer: missing factoryId, skipping");
            return null;
        }
        if (userInput == null || userInput.trim().isEmpty()) {
            return null;
        }

        long t0 = System.currentTimeMillis();
        try {
            java.util.Map<String, Object> bodyMap = new java.util.LinkedHashMap<>();
            bodyMap.put("factory_id", factoryId);
            bodyMap.put("query", userInput);
            if (javaToolName != null && !javaToolName.isEmpty()) {
                bodyMap.put("java_tool_name", javaToolName);
            }
            if (sessionId != null && !sessionId.isBlank()) {
                bodyMap.put("session_id", sessionId);
            }
            String json = objectMapper.writeValueAsString(bodyMap);

            Request.Builder reqBuilder = new Request.Builder()
                    .url(config.getUrl() + "/api/smartbi/gold/restaurant/tiered-answer")
                    .post(RequestBody.create(json, JSON_MEDIA));
            if (!internalSecret.isEmpty()) {
                reqBuilder.addHeader("X-Internal-Secret", internalSecret);
                reqBuilder.addHeader("X-Factory-Id", factoryId);
                String userRole = currentUserRole();
                if (userRole != null && !userRole.isEmpty()) {
                    reqBuilder.addHeader("X-User-Role", userRole);
                }
                String userId = currentUserId();
                if (userId != null && !userId.isBlank()) {
                    reqBuilder.addHeader("X-User-Id", userId);
                }
            }
            Request req = reqBuilder.build();

            okhttp3.Call call = http.newCall(req);
            call.timeout().timeout(TIERED_ANSWER_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            try (Response resp = call.execute()) {
                long elapsed = System.currentTimeMillis() - t0;
                if (!resp.isSuccessful()) {
                    String body = resp.body() != null ? resp.body().string() : "";
                    log.warn("Tiered intent answer HTTP {} in {}ms factory={} tool={}: {}",
                            resp.code(), elapsed, factoryId, javaToolName, body);
                    return null;
                }
                String body = resp.body() != null ? resp.body().string() : "{}";
                @SuppressWarnings("unchecked")
                Map<String, Object> parsed = objectMapper.readValue(body, Map.class);
                log.debug("Tiered intent answer factory={} tool={} delegate={} in {}ms",
                        factoryId, javaToolName, parsed.get("delegate"), elapsed);
                return parsed;
            }
        } catch (Exception e) {
            long elapsed = System.currentTimeMillis() - t0;
            log.warn("Tiered intent answer failed factory={} tool={} in {}ms: {}",
                    factoryId, javaToolName, elapsed, e.getMessage());
            return null;
        }
    }
}
