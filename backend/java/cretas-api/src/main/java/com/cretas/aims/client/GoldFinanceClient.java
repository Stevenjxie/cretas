package com.cretas.aims.client;

import com.cretas.aims.config.smartbi.PythonSmartBIConfig;
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
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.time.LocalDate;
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

    /**
     * Read the same env var that JwtAuthInterceptor + PythonSmartBIClient use
     * so there's exactly one secret to rotate. Empty = run without the header
     * (Gold route will then require JWT instead).
     */
    private String internalSecret = System.getenv("INTERNAL_API_SECRET") != null
            ? System.getenv("INTERNAL_API_SECRET") : "";

    public GoldFinanceClient(PythonSmartBIConfig config) {
        this.config = config;
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
        try {
            var attrs = RequestContextHolder.getRequestAttributes();
            if (attrs instanceof ServletRequestAttributes servletAttrs) {
                HttpServletRequest req = servletAttrs.getRequest();
                Object role = req.getAttribute("role");
                return role != null ? role.toString() : null;
            }
            return null;
        } catch (Exception e) {
            log.debug("currentUserRole resolve failed: {}", e.getMessage());
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
}
