package com.cretas.aims.security;

import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.dto.common.PageResponse;
import com.cretas.aims.entity.User;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.service.PermissionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

import jakarta.servlet.http.HttpServletRequest;
import java.lang.reflect.Field;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Deque;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Strips fields annotated with {@link PriceSensitive} from response bodies
 * for users lacking the {@code procurement:price:view} permission.
 *
 * <h2>Behavior</h2>
 * <ul>
 *   <li>Runs <b>after</b> the controller returns, <b>before</b> Jackson serialization.</li>
 *   <li>Recursively walks the response body (including {@link ApiResponse#getData()},
 *       {@link PageResponse#getContent()}, nested entities, lists, maps).</li>
 *   <li>Reflectively sets each {@code @PriceSensitive} field to {@code null}.</li>
 *   <li>If the current request has no authenticated user (e.g. health checks,
 *       login endpoints), the response is passed through unchanged — fail-safe
 *       behavior delegates to existing {@link com.cretas.aims.config.JwtAuthInterceptor}
 *       which already blocks unauthenticated traffic at the auth layer.</li>
 *   <li>Users with the permission (factory_super_admin, finance_manager,
 *       procurement_manager) see all price fields. Roles without the permission
 *       (warehouse_manager, warehouse_worker, quality_inspector, operator, etc.)
 *       see {@code null} in those fields.</li>
 * </ul>
 *
 * <h2>Field cache</h2>
 * <p>Reflective field lookup is cached per class to keep overhead negligible
 * (a single {@link Class#getDeclaredFields()} walk per type per JVM, then
 * O(1) hash lookups). Per-request CPU cost is dominated by traversal of the
 * payload graph, not reflection.
 *
 * <h2>Cycle protection</h2>
 * <p>An {@link IdentityHashMap}-backed visited set prevents stack-overflow on
 * entities with bidirectional JPA associations (e.g. PurchaseOrder ↔ items).
 *
 * <p>RBAC isolation source-of-truth: PR #415 Option B (2026-05-12).
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-05-12
 */
@ControllerAdvice
public class PriceFieldResponseAdvice implements ResponseBodyAdvice<Object> {

    private static final Logger log = LoggerFactory.getLogger(PriceFieldResponseAdvice.class);

    /** Default permission gate for price field visibility. */
    public static final String PRICE_VIEW_PERMISSION = "procurement:price:view";

    /** Per-class cache: Class → list of @PriceSensitive fields. Made accessible. */
    private static final Map<Class<?>, List<Field>> PRICE_FIELD_CACHE = new ConcurrentHashMap<>();

    /** Classes known to contain zero @PriceSensitive fields (terminate traversal early). */
    private static final Set<Class<?>> CLEAN_CLASSES = ConcurrentHashMap.newKeySet();

    /** Package prefix of project-owned classes — only descend into these to avoid jdk/lib introspection. */
    private static final String PROJECT_PACKAGE = "com.cretas.aims";

    // ───────── Nested-Map key-pattern stripping (P0 PR #470 root-cause fix, 2026-05-12) ─────────
    //
    // SmartBI analysis endpoints return Map<String, Object> payloads (rankings,
    // charts, heatmap, aiInsights, kpiCards, …) where price-bearing values live
    // under semantic key names rather than annotated entity fields. The reflective
    // walk above only nulls @PriceSensitive fields — Map entries have no
    // annotations, so they slipped through. PR #470 (chat1 R3 finance-l4-deep audit)
    // documents 8+ leaking endpoints.
    //
    // This pass complements (does not replace) the field-stripping above:
    //   1. While walking a Map<String, Object>, we track the ancestor key path.
    //   2. If the current key is a known "price value key" AND the path contains
    //      a recognized "price container" segment, replace the value with null.
    //   3. The key 'formattedValue' is treated as price data anywhere (never
    //      occurs as a non-monetary identifier in our codebase) — without this
    //      bypass, sibling fields like {value: null, formattedValue: "23M"} keep
    //      leaking after value-stripping.
    //   4. For aiInsights[].message-like text, amount-shaped substrings
    //      (e.g. "23,075,969.60", "¥2,440,637.80", "1.2万") are replaced with
    //      "[REDACTED]" so embedded numbers don't slip through prose.
    //   5. Under "dynamic-key" containers (trendComparison.data[]) where the
    //      Map key IS the department / region name, numeric values are nulled.
    //
    // Precision guard: outside a price-container path we do NOT touch keys like
    // 'value' or 'amount' — they're too ambiguous (could be a count, enum, etc.).

    /** Keys whose value is price data when nested under a price-container path. */
    private static final Set<String> PRICE_VALUE_KEYS = Set.of(
            "value",
            "target",
            "currentSales",
            "previousSales",
            "currentRevenue",
            "previousRevenue",
            "completionRate",
            "grossMargin",
            "netMargin",
            "grossProfit",
            "netProfit",
            "amount",
            "revenue",
            "profit",
            "sales",
            "totalAmount",
            "totalValue",
            "growth",
            "growthRate"
    );

    /**
     * Keys that always carry price data, regardless of ancestor path. The
     * 'formattedValue' name is a Cretas SmartBI convention for the
     * display-formatted twin of a numeric metric, so it is never a non-monetary
     * field.
     */
    private static final Set<String> ALWAYS_PRICE_KEYS = Set.of(
            "formattedValue"
    );

    /**
     * Path segments that indicate the current sub-graph contains price-bearing
     * values. Match is case-insensitive and supports the {@code *Ranking}
     * suffix family (salespersonRanking, customerRanking, productRanking,
     * supplierRanking, regionRanking, …) plus singular {@code ranking}.
     *
     */
    private static final Pattern PRICE_CONTAINER_PATH_REGEX = Pattern.compile(
            "(?i)^(" +
                    "rankings?" +
                    "|.*Ranking" +
                    "|charts?" +
                    "|heatmap" +
                    "|opportunityScores" +
                    "|targetCompletion" +
                    "|trendComparison" +
                    "|trendChart" +
                    "|trendData" +
                    "|kpiCards?" +
                    "|metrics" +
                    "|aiInsights" +
                    "|insights" +
                    "|roi|ROI" +
                    "|performance" +
                    "|categoryDistribution" +
                    "|productCategoryDistribution" +
                    "|agingBuckets?" +
                    "|inventoryValuation" +
                    ")$"
    );

    /**
     * BUG-01-RBAC (2026-06-09): Finance column name pattern for SmartBI
     * upload raw data rows ({@code GET /smart-bi/uploads/{id}/data} and
     * {@code /fields}).
     *
     * <p>When a Map entry key matches this pattern AND the value is a numeric
     * scalar, the value is redacted to null. This prevents finance data sheets
     * (containing labor cost 3200, rent 8500, etc.) from being read by
     * {@code sales_manager} who has {@code analytics:read} but not
     * {@code finance:read_write}.
     *
     * <p>The pattern matches common Chinese finance column header names used in
     * SmartBI Excel uploads. Non-finance columns (quantity, product name, date,
     * store ID, etc.) are preserved so POS/restaurant data stays readable by the
     * analytics module.
     *
     * <p>Applies: any ancestor path segment == "uploadData" (see
     * PRICE_CONTAINER_PATH_REGEX above) OR the key itself matches here AND
     * the value is numeric (leaf scalar).
     */
    private static final Pattern FINANCE_COLUMN_KEY_REGEX = Pattern.compile(
            "(?i).*(" +
                    "金额|amount|价格|单价|售价|成本|利润|收入|营收|营业" +
                    "|费用|支出|租金|工资|薪酬|薪资|奖金|补贴|社保" +
                    "|税|增值税|税额|发票|报销|预算|实际额" +
                    "|revenue|profit|cost|expense|salary|wage|bonus|rent|tax" +
                    "|price|labor|labour" +
                    ").*"
    );

    /** Path segments where dynamic Map keys (e.g. department name) are the column header for numeric price data. */
    private static final Pattern DYNAMIC_KEY_PRICE_CONTAINER_REGEX = Pattern.compile(
            "(?i)^(trendComparison|trendChart|crosstab|pivot)$"
    );

    /** Path segments where text values may embed monetary amounts that need redaction. */
    private static final Pattern AI_INSIGHT_PATH_REGEX = Pattern.compile(
            "(?i)^(aiInsights|insights|llmInsights|narrative|narratives)$"
    );

    /** Keys whose String value should have amount substrings redacted under AI_INSIGHT path. */
    private static final Set<String> AI_INSIGHT_TEXT_KEYS = Set.of(
            "message",
            "description",
            "summary",
            "narrative",
            "explanation",
            "detail",
            "content"
    );

    /** Keys we preserve under dynamic-key containers (timestamps, identifiers, counts). */
    private static final Set<String> DYNAMIC_KEY_PRESERVE_KEYS = Set.of(
            "rank", "count", "date", "name", "id", "label", "key", "type",
            "status", "category", "period", "groupBy", "startDate", "endDate",
            "factoryId", "year", "month", "week", "day", "quarter", "x", "y"
    );

    /**
     * Amount-shaped substring detector for AI-insight text redaction. Matches:
     *   • Currency-prefixed: ¥1,234.56 / ￥123 / $1,000
     *   • Comma-grouped: 1,234,567.89 (≥1 comma group required to avoid plain "123")
     *   • Suffix-units: 1.2万 / 23亿 / 5000 元 / 1234 RMB / 999 CNY
     */
    private static final Pattern AMOUNT_PATTERN = Pattern.compile(
            "[¥￥$]\\s*[0-9]+(?:[,.][0-9]+)*"
                    + "|[0-9]{1,3}(?:,[0-9]{3})+(?:\\.[0-9]+)?"
                    + "|[0-9]+(?:\\.[0-9]+)?\\s*(?:元|万|亿|RMB|CNY)"
    );

    /** Sentinel inserted in AI-insight text to replace matched amount substrings. */
    private static final String REDACTED_AMOUNT_PLACEHOLDER = "[REDACTED]";

    @Autowired
    private PermissionService permissionService;

    @Autowired
    private UserRepository userRepository;

    @Override
    public boolean supports(MethodParameter returnType, Class<? extends HttpMessageConverter<?>> converterType) {
        // Only intercept JSON-serializable responses (skip binary streams, file uploads).
        // Returning true here lets beforeBodyWrite filter by content type / class.
        return true;
    }

    @Override
    public Object beforeBodyWrite(Object body,
                                   MethodParameter returnType,
                                   org.springframework.http.MediaType selectedContentType,
                                   Class<? extends HttpMessageConverter<?>> selectedConverterType,
                                   ServerHttpRequest request,
                                   ServerHttpResponse response) {
        if (body == null) {
            return null;
        }

        // Restrict to JSON responses — binary / streaming / form responses pass through
        if (selectedContentType != null
                && !MediaType.APPLICATION_JSON.includes(selectedContentType)
                && !MediaType.parseMediaType("application/*+json").includes(selectedContentType)) {
            return body;
        }

        // Resolve current user from request attributes (set by JwtAuthInterceptor).
        User currentUser = resolveCurrentUser(request);
        if (currentUser == null) {
            // No authenticated user (login, health checks, public endpoints).
            // Public endpoints are filtered by JwtAuthInterceptor — if we reach
            // here without a user, the endpoint is intentionally unauthenticated.
            return body;
        }

        // Check permission once — short-circuit for users with full access.
        // Wrapped to fail-CLOSED on PermissionService faults (DB outage,
        // cache misconfig, NPE). An unhandled exception here would
        // propagate to a 500 response on every request — bad UX and brittle.
        // On exception we treat the user as NOT permitted (strip prices),
        // which preserves data confidentiality.
        boolean canViewPrices;
        try {
            canViewPrices = permissionService.hasPermission(currentUser, PRICE_VIEW_PERMISSION);
        } catch (Exception e) {
            log.warn("PriceFieldResponseAdvice: permission check failed for userId={}, defaulting fail-CLOSED (strip): {}",
                    currentUser.getId(), e.getMessage());
            canViewPrices = false;
        }
        if (canViewPrices) {
            return body;
        }

        // User lacks price view permission — strip @PriceSensitive fields recursively.
        try {
            stripPriceFields(body, new IdentityHashMap<>(), new ArrayDeque<>());
        } catch (Exception e) {
            // Defensive: never fail the response on a stripping error. Log and
            // pass through the body — admin-role parity preserved (no regression).
            log.warn("PriceFieldResponseAdvice strip failed for userId={}, role={}: {}",
                    currentUser.getId(),
                    currentUser.getRoleEnum() != null ? currentUser.getRoleEnum().name() : "null",
                    e.getMessage());
        }
        // Signal to Jackson serialization layer (PriceSensitiveSerializerModifier)
        // that @PriceSensitive methods/getters should also be short-circuited.
        // This catches computed @Transient getters (e.g. SalesOrder.getPayableAmount())
        // that would otherwise dereference already-nulled price fields → NPE.
        //
        // The ThreadLocal is cleared at the end of serialization via a Servlet
        // filter ({@link com.cretas.aims.config.PriceSensitiveContextFilter}) to
        // guarantee no leakage across requests on pooled threads.
        PriceSensitiveContext.hide();
        return body;
    }

    /**
     * Recursively visit the response body graph and null-out any field annotated
     * with {@link PriceSensitive}. Visits collections (List/Set), maps and
     * nested project objects. Skips JDK types (String, Number, Date, etc).
     *
     * <p>The {@code pathStack} parameter tracks ancestor Map keys / collection
     * descents so the Map-walking branch can apply path-aware key-pattern
     * stripping for SmartBI analysis payloads (PR #470 root-cause fix).
     */
    private void stripPriceFields(Object obj,
                                   IdentityHashMap<Object, Boolean> visited,
                                   Deque<String> pathStack) {
        if (obj == null) {
            return;
        }
        if (visited.containsKey(obj)) {
            return;
        }

        Class<?> clazz = obj.getClass();

        // Collection — recurse into elements (containers may be JDK types but
        // their contents are what we care about).
        if (obj instanceof Collection<?>) {
            for (Object item : (Collection<?>) obj) {
                stripPriceFields(item, visited, pathStack);
            }
            return;
        }

        // Map — walk entries with path-aware key-pattern stripping. Each entry's
        // key is pushed onto pathStack so descendants can detect their ancestor
        // context (e.g. "we're inside salespersonRanking"). Falls back to plain
        // value-recursion when the key/path combination doesn't match a
        // price-pattern.
        if (obj instanceof Map<?, ?>) {
            walkMapForKeyPatternStripping((Map<?, ?>) obj, visited, pathStack);
            return;
        }

        // Array
        if (clazz.isArray() && !clazz.getComponentType().isPrimitive()) {
            Object[] arr = (Object[]) obj;
            for (Object item : arr) {
                stripPriceFields(item, visited, pathStack);
            }
            return;
        }

        // Skip JDK leaf values — they cannot have @PriceSensitive annotations
        if (isJdkType(clazz)) {
            return;
        }

        // Mark visited for project objects (post-container check)
        if (clazz.getName().startsWith(PROJECT_PACKAGE)) {
            visited.put(obj, Boolean.TRUE);
        }

        // Project object — locate & null @PriceSensitive fields, then descend
        if (!clazz.getName().startsWith(PROJECT_PACKAGE)) {
            return;
        }

        if (CLEAN_CLASSES.contains(clazz)) {
            // Class has no @PriceSensitive fields anywhere — still descend
            // into non-primitive fields (graph may contain dirty children)
            descendChildren(obj, clazz, visited, pathStack);
            return;
        }

        List<Field> priceFields = PRICE_FIELD_CACHE.computeIfAbsent(clazz, this::scanPriceFields);
        for (Field f : priceFields) {
            try {
                f.set(obj, null);
            } catch (IllegalAccessException e) {
                log.debug("Failed to null @PriceSensitive field {}.{}: {}", clazz.getSimpleName(), f.getName(), e.getMessage());
            }
        }

        descendChildren(obj, clazz, visited, pathStack);
    }

    /**
     * Iterate a Map's entries and apply PR #470 key-pattern stripping rules.
     * When a Map is immutable, individual entry mutations are silently skipped
     * (graceful degrade — better to leak than to 500 the entire response).
     */
    @SuppressWarnings("unchecked")
    private void walkMapForKeyPatternStripping(Map<?, ?> rawMap,
                                                IdentityHashMap<Object, Boolean> visited,
                                                Deque<String> pathStack) {
        boolean inPriceContainer = pathContainsPriceContainer(pathStack);
        boolean inAIInsight = pathContainsAIInsight(pathStack);
        boolean inDynamicContainer = pathContainsDynamicPriceContainer(pathStack);

        // Iterate via entrySet so we can mutate values in-place.
        for (Map.Entry<?, ?> entry : rawMap.entrySet()) {
            String key = entry.getKey() == null ? "" : String.valueOf(entry.getKey());
            Object value = entry.getValue();

            pathStack.push(key);
            try {
                // Rule 1 — keys that are always price-data regardless of path
                // (formattedValue is Cretas-specific naming, never non-monetary).
                if (ALWAYS_PRICE_KEYS.contains(key) && isLeafScalar(value)) {
                    trySetEntryNull((Map.Entry<Object, Object>) entry, "ALWAYS_PRICE_KEY");
                    continue;
                }

                // Rule 2 — known price-value keys inside a price-container path.
                if (inPriceContainer && PRICE_VALUE_KEYS.contains(key) && isLeafScalar(value)) {
                    trySetEntryNull((Map.Entry<Object, Object>) entry, "PRICE_VALUE_KEY in container");
                    continue;
                }

                // Rule 2b — BUG-01-RBAC (2026-06-09): finance column name keys anywhere
                // in the response graph. This catches Chinese finance column headers (金额,
                // 劳动力成本, 租金, 工资, etc.) that appear as Map keys in SmartBI upload raw
                // data rows (GET /uploads/{id}/data). The rule is path-independent because
                // (a) these Chinese-language finance terms are sufficiently specific to not
                //     appear in non-financial contexts, and
                // (b) only fires on Number leaf scalars — product names, dates, and
                //     quantity columns (which have different column header names) are
                //     preserved, so POS/restaurant dish-count data is unaffected.
                if (value instanceof Number
                        && FINANCE_COLUMN_KEY_REGEX.matcher(key).matches()) {
                    trySetEntryNull((Map.Entry<Object, Object>) entry, "FINANCE_COLUMN_KEY numeric leaf");
                    continue;
                }

                // Rule 3 — AI-insight prose: redact amount-shaped substrings.
                if (inAIInsight && AI_INSIGHT_TEXT_KEYS.contains(key) && value instanceof String) {
                    String redacted = redactAmounts((String) value);
                    if (!redacted.equals(value)) {
                        trySetEntryValue((Map.Entry<Object, Object>) entry, redacted, "AI_INSIGHT_REDACT");
                    }
                    continue;
                }

                // Rule 4 — dynamic-key containers (trendComparison.data[].deptName).
                // Null any numeric value whose key isn't an obvious identifier/timestamp.
                if (inDynamicContainer && value instanceof Number
                        && !DYNAMIC_KEY_PRESERVE_KEYS.contains(key)) {
                    trySetEntryNull((Map.Entry<Object, Object>) entry, "DYNAMIC_KEY_NUMERIC");
                    continue;
                }

                // Default — recurse normally with the updated path.
                stripPriceFields(value, visited, pathStack);
            } finally {
                pathStack.pop();
            }
        }
    }

    /** Returns true when any ancestor key path segment matches a price-container token. */
    private boolean pathContainsPriceContainer(Deque<String> pathStack) {
        for (String seg : pathStack) {
            if (seg != null && PRICE_CONTAINER_PATH_REGEX.matcher(seg).matches()) {
                return true;
            }
        }
        return false;
    }

    /** Returns true when any ancestor segment is an AI-insight container. */
    private boolean pathContainsAIInsight(Deque<String> pathStack) {
        for (String seg : pathStack) {
            if (seg != null && AI_INSIGHT_PATH_REGEX.matcher(seg).matches()) {
                return true;
            }
        }
        return false;
    }

    /** Returns true when any ancestor segment is a dynamic-key price container. */
    private boolean pathContainsDynamicPriceContainer(Deque<String> pathStack) {
        for (String seg : pathStack) {
            if (seg != null && DYNAMIC_KEY_PRICE_CONTAINER_REGEX.matcher(seg).matches()) {
                return true;
            }
        }
        return false;
    }

    /** Replace amount-shaped substrings with the redaction placeholder. */
    private String redactAmounts(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        return AMOUNT_PATTERN.matcher(text).replaceAll(REDACTED_AMOUNT_PLACEHOLDER);
    }

    /** Set entry value to null; swallow {@link UnsupportedOperationException} for immutable maps. */
    private void trySetEntryNull(Map.Entry<Object, Object> entry, String reason) {
        trySetEntryValue(entry, null, reason);
    }

    /** Set entry value; swallow {@link UnsupportedOperationException} for immutable maps. */
    private void trySetEntryValue(Map.Entry<Object, Object> entry, Object newValue, String reason) {
        try {
            entry.setValue(newValue);
        } catch (UnsupportedOperationException e) {
            log.debug("Cannot redact key '{}' (immutable map, reason={}): {}",
                    entry.getKey(), reason, e.getMessage());
        }
    }

    /** Returns true for values where in-place null replacement is safe — strings, numbers, booleans (not maps/lists/objects). */
    private boolean isLeafScalar(Object v) {
        if (v == null) {
            return true; // already null — setting to null is a no-op, but harmless
        }
        return v instanceof Number || v instanceof CharSequence || v instanceof Boolean;
    }

    /** Walk reference fields of a project class and recurse into each non-null value. */
    private void descendChildren(Object obj, Class<?> clazz,
                                  IdentityHashMap<Object, Boolean> visited,
                                  Deque<String> pathStack) {
        // Walk all fields (including inherited up to Object). Recurse into project objects + containers.
        Class<?> walk = clazz;
        while (walk != null && walk != Object.class) {
            for (Field f : walk.getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                if (java.lang.reflect.Modifier.isTransient(f.getModifiers())) continue;
                Class<?> type = f.getType();
                if (type.isPrimitive()) continue;
                // Skip declared leaf types (String / BigDecimal / dates / etc).
                // Generic Object fields (T data in ApiResponse) are NOT skipped here —
                // the runtime check inside stripPriceFields handles them.
                if (type != Object.class && isLeafValue(type)) continue;
                f.setAccessible(true);
                try {
                    Object child = f.get(obj);
                    if (child != null) {
                        pathStack.push(f.getName());
                        try {
                            stripPriceFields(child, visited, pathStack);
                        } finally {
                            pathStack.pop();
                        }
                    }
                } catch (IllegalAccessException e) {
                    log.debug("Failed to access field {}.{}: {}", walk.getSimpleName(), f.getName(), e.getMessage());
                }
            }
            walk = walk.getSuperclass();
        }
    }

    /**
     * Returns {@code true} for types that never contain @PriceSensitive — primitives,
     * String, Number, java.time, UUID, Boolean. Collections, Maps and Object[] are NOT
     * leaf — they're containers we must descend into.
     */
    private boolean isLeafValue(Class<?> type) {
        if (type.isPrimitive()) return true;
        if (Collection.class.isAssignableFrom(type)) return false;
        if (Map.class.isAssignableFrom(type)) return false;
        if (type.isArray()) {
            Class<?> comp = type.getComponentType();
            return comp.isPrimitive() || isLeafValue(comp);
        }
        String name = type.getName();
        // Primitive wrappers, String, Number subtypes, dates, UUIDs etc — leaf.
        if (name.startsWith("java.lang.")) return true;
        if (name.startsWith("java.math.")) return true;
        if (name.startsWith("java.time.")) return true;
        if (name.startsWith("java.util.UUID")) return true;
        if (name.equals("java.util.Date")) return true;
        // Other java.* are containers/wrappers that may hold project objects (Optional, etc.)
        // but for safety we treat unknown JDK types as leaf to avoid heavy reflection.
        if (name.startsWith("java.")
                || name.startsWith("javax.")
                || name.startsWith("jakarta.")
                || name.startsWith("sun.")
                || name.startsWith("com.fasterxml.jackson")
                || name.startsWith("org.hibernate.")
                || name.startsWith("org.springframework.")) {
            return true;
        }
        return false;
    }

    /**
     * Scan all fields of the class (including inherited) for {@link PriceSensitive}.
     * Returns an immutable list. Marks class as clean when no fields are found.
     */
    private List<Field> scanPriceFields(Class<?> clazz) {
        List<Field> result = new java.util.ArrayList<>();
        Class<?> walk = clazz;
        while (walk != null && walk != Object.class) {
            for (Field f : walk.getDeclaredFields()) {
                if (f.isAnnotationPresent(PriceSensitive.class)) {
                    // Always ensure accessible for instance/private fields (canAccess
                    // requires a concrete target — calling it with null on an instance
                    // field throws IllegalArgumentException).
                    f.setAccessible(true);
                    result.add(f);
                }
            }
            walk = walk.getSuperclass();
        }
        if (result.isEmpty()) {
            CLEAN_CLASSES.add(clazz);
        }
        return java.util.Collections.unmodifiableList(result);
    }

    /**
     * Returns {@code true} for types we don't want to introspect (JDK / framework primitives).
     */
    private boolean isJdkType(Class<?> clazz) {
        if (clazz.isPrimitive()) return true;
        String name = clazz.getName();
        // Strings, numbers, dates, UUIDs etc never contain @PriceSensitive
        return name.startsWith("java.")
                || name.startsWith("javax.")
                || name.startsWith("jakarta.")
                || name.startsWith("sun.")
                || name.startsWith("com.fasterxml.jackson")
                || name.startsWith("org.hibernate.")
                || name.startsWith("org.springframework.");
    }

    /** Resolve the current user from request attributes set by {@link com.cretas.aims.config.JwtAuthInterceptor}. */
    private User resolveCurrentUser(ServerHttpRequest request) {
        if (!(request instanceof ServletServerHttpRequest)) {
            return null;
        }
        HttpServletRequest servletReq = ((ServletServerHttpRequest) request).getServletRequest();
        Object userIdObj = servletReq.getAttribute("userId");
        if (userIdObj == null) {
            return null;
        }
        Long userId = null;
        if (userIdObj instanceof Long) {
            userId = (Long) userIdObj;
        } else if (userIdObj instanceof Integer) {
            userId = ((Integer) userIdObj).longValue();
        } else if (userIdObj instanceof String) {
            try {
                userId = Long.parseLong((String) userIdObj);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        if (userId == null) {
            return null;
        }
        try {
            Optional<User> userOpt = userRepository.findById(userId);
            return userOpt.orElse(null);
        } catch (Exception e) {
            log.debug("Failed to resolve user for userId={}: {}", userId, e.getMessage());
            return null;
        }
    }
}
