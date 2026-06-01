package com.cretas.aims.client;

import com.cretas.aims.config.smartbi.PythonSmartBIConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import lombok.extern.slf4j.Slf4j;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
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
}
