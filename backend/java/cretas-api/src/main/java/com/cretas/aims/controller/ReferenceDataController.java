package com.cretas.aims.controller;

import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.entity.Customer;
import com.cretas.aims.entity.MaterialBatch;
import com.cretas.aims.entity.ProductType;
import com.cretas.aims.entity.RawMaterialType;
import com.cretas.aims.entity.Supplier;
import com.cretas.aims.entity.User;
import com.cretas.aims.entity.inventory.PurchaseOrder;
import com.cretas.aims.entity.inventory.SalesOrder;
import com.cretas.aims.entity.sales.OperationalQuote;
import com.cretas.aims.repository.CustomerRepository;
import com.cretas.aims.repository.MaterialBatchRepository;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.repository.RawMaterialTypeRepository;
import com.cretas.aims.repository.SupplierRepository;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.repository.inventory.PurchaseOrderRepository;
import com.cretas.aims.repository.inventory.SalesOrderRepository;
import com.cretas.aims.repository.sales.OperationalQuoteRepository;
import com.cretas.aims.security.PriceMaskResolver;
import com.cretas.aims.service.sales.impl.OperationalQuoteServiceImpl;
import com.cretas.aims.util.SqlLikeEscaper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Lightweight read-only lookup endpoints for Canvas DYNAMIC mode dropdowns.
 *
 * <p>Why this exists: SchemaFormRenderer.vue renders {@code type=reference} fields via
 * ReferenceSelector, which calls {@code apiEndpoint?keyword=...&page=&size=}. The existing
 * domain endpoints (e.g. {@code /users}, {@code /customers}) carry permission gates
 * ({@code hr:read}, {@code sales:read_write}) that exclude users who legitimately need to
 * pick a salesperson/customer/product when creating a sales order. These minimal lookups
 * return only display fields (id, label, secondary) and require no module permission beyond
 * authentication — sales-friendly without leaking sensitive HR data (salary, hire date).
 *
 * <p>Response shape: {@code { content: [{id, label, ...}], totalElements }} — matches what
 * ReferenceSelector and other PageResponse-aware components expect.
 */
@Slf4j
@RestController
@RequestMapping("/api/mobile/{factoryId}/reference-data")
@RequiredArgsConstructor
@Tag(name = "Reference Data Lookup", description = "Canvas dropdown 通用查找端点")
public class ReferenceDataController {

    private static final int MAX_PAGE_SIZE = 100;
    private static final int DEFAULT_PAGE_SIZE = 50;

    private final UserRepository userRepository;
    private final CustomerRepository customerRepository;
    private final SupplierRepository supplierRepository;
    private final ProductTypeRepository productTypeRepository;
    private final RawMaterialTypeRepository materialTypeRepository;
    private final OperationalQuoteRepository quoteRepository;
    private final SalesOrderRepository salesOrderRepository;
    private final PurchaseOrderRepository purchaseOrderRepository;
    private final MaterialBatchRepository materialBatchRepository;
    private final PriceMaskResolver priceMaskResolver;

    /** Salesperson-eligible roles (department name fallback also applied). */
    private static final Set<String> SALES_ROLES = Set.of(
            "salesperson", "sales_manager", "sales_director",
            "factory_super_admin", "permission_admin");

    /**
     * 业务员/员工查找. salesOnly defaults to true (Canvas SO dropdown's intended use case);
     * pass {@code ?salesOnly=false} to fetch all active users (e.g. for assignee pickers).
     * 返回 fullName 作为 label (sales_orders.salesperson 列存的是 name 字符串, 不是 user_id).
     *
     * <p>Reviewer Issue #3+#5+#6 fixes (Apr 25 2026):
     * <ul>
     *   <li>Pull active+sales filter into JPQL via per-page over-fetch + filter, raise total to honest sum (was lying about totalElements when salesOnly=true).
     *   <li>Drop {@code roleCode} and {@code department} from response — was leaking org chart to all authenticated users (e.g. workers could enumerate who is factory_super_admin).
     * </ul>
     * Better: dedicated repo method with WHERE active AND role IN (...). Done as Issue #3 follow-up if traffic grows.
     */
    @GetMapping("/employees")
    @Operation(summary = "员工查找 (业务员下拉)")
    public ApiResponse<Map<String, Object>> findEmployees(
            @PathVariable String factoryId,
            @RequestParam(required = false, defaultValue = "") String keyword,
            @RequestParam(required = false, defaultValue = "true") boolean salesOnly,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "50") int size) {
        // Over-fetch: take size*4 from DB so post-filter rarely returns < requested page-size.
        // Cheap because user table is small (typical < 200 per factory).
        int pageSize = clampSize(size);
        org.springframework.data.domain.PageRequest pageable = PageRequest.of(
                Math.max(page - 1, 0), Math.min(pageSize * 4, 200),
                Sort.by(Sort.Direction.ASC, "fullName"));
        Page<User> result = userRepository.searchUsers(
                factoryId, SqlLikeEscaper.escape(keyword), pageable);
        List<User> filtered = result.getContent().stream()
                .filter(u -> Boolean.TRUE.equals(u.getIsActive()))
                .filter(u -> !salesOnly || isSalesEligible(u))
                .collect(Collectors.toList());
        List<Map<String, Object>> content = filtered.stream()
                .limit(pageSize)
                .map(u -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", u.getId());
                    m.put("fullName", u.getFullName());
                    m.put("employeeCode", u.getEmployeeCode());
                    return m;
                })
                .collect(Collectors.toList());
        return wrap(content, filtered.size());
    }

    /** GET-by-id for employees — required by ReferenceSelector.fetchById on edit-mode display.
     *  Reviewer Issue #1 fix: without this, ReferenceSelector falls back to displaying raw ID.
     *  Audit Apr 25 2026: removed input-echo on miss — was hiding stale/deleted user references.
     *  ReferenceSelector itself handles the "render raw value" fallback for true legacy strings. */
    @GetMapping("/employees/{idOrName}")
    @Operation(summary = "按 ID 或 fullName 查单个员工")
    public ApiResponse<Map<String, Object>> getEmployee(@PathVariable String factoryId,
                                                       @PathVariable String idOrName) {
        // Try numeric ID first
        try {
            Long id = Long.parseLong(idOrName);
            return userRepository.findById(id)
                    .filter(u -> factoryId.equals(u.getFactoryId()))
                    .map(this::toEmployeeMap)
                    .map(ApiResponse::success)
                    .orElseGet(() -> ApiResponse.success(null));
        } catch (NumberFormatException ignored) { /* fallthrough — try name */ }
        // Fallback: legacy salesperson string is fullName, look up by exact name
        return userRepository.searchUsers(factoryId, SqlLikeEscaper.escape(idOrName),
                        PageRequest.of(0, 5))
                .stream()
                .filter(u -> idOrName.equals(u.getFullName()))
                .findFirst()
                .map(this::toEmployeeMap)
                .map(ApiResponse::success)
                .orElseGet(() -> ApiResponse.success(null));
    }

    private Map<String, Object> toEmployeeMap(User u) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", u.getId());
        m.put("fullName", u.getFullName());
        m.put("employeeCode", u.getEmployeeCode());
        return m;
    }

    private boolean isSalesEligible(User u) {
        if (SALES_ROLES.contains(String.valueOf(u.getRoleCode()))) return true;
        String dept = u.getDepartment();
        return dept != null && dept.contains("销售");
    }

    /** GET-by-id for customer. Reviewer Issue #1 fix. */
    @GetMapping("/customers/{id}")
    @Operation(summary = "按 ID 查单个客户")
    public ApiResponse<Map<String, Object>> getCustomer(@PathVariable String factoryId,
                                                        @PathVariable String id) {
        return customerRepository.findById(id)
                .filter(c -> factoryId.equals(c.getFactoryId()))
                .map(c -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", c.getId());
                    m.put("name", c.getName());
                    m.put("customerCode", c.getCustomerCode());
                    m.put("contactPerson", c.getContactPerson());
                    return m;
                })
                .map(ApiResponse::success)
                .orElseGet(() -> ApiResponse.success(null));
    }

    /** GET-by-id for supplier. Reviewer Issue #1 fix. */
    @GetMapping("/suppliers/{id}")
    @Operation(summary = "按 ID 查单个供应商")
    public ApiResponse<Map<String, Object>> getSupplier(@PathVariable String factoryId,
                                                        @PathVariable String id) {
        return supplierRepository.findById(id)
                .filter(s -> factoryId.equals(s.getFactoryId()))
                .map(s -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", s.getId());
                    m.put("name", s.getName());
                    // 简称 + displayName: Canvas ReferenceSelector 的供应商下拉走这个投影,
                    // 不带上简称的话「下拉里好认」在所有动态表单上都不生效。
                    m.put("shortName", s.getShortName());
                    m.put("displayName", s.getShortName() != null && !s.getShortName().isBlank()
                            ? s.getShortName() : s.getName());
                    m.put("supplierCode", s.getSupplierCode());
                    m.put("contactPerson", s.getContactPerson());
                    return m;
                })
                .map(ApiResponse::success)
                .orElseGet(() -> ApiResponse.success(null));
    }

    /** GET-by-id for product. Reviewer Issue #1 fix.
     * C-6 (2026-05-09): 加 boxConversionCoefficient 给 sales_order DYNAMIC 用的 P1-2 箱数自动算.
     * spec: docs/superpowers/specs/2026-05-09-canvas-c6-sales-order-dynamic-migration.md
     * frontend ReferenceSelector projectFields 把它写到 row._boxConversionCoefficient,
     * boxQuantity computed 表达式 quantity / _boxConversionCoefficient 用.
     *
     * <p>RBAC (PR #488 §F2, 2026-05-12): {@code unitPrice} is gated via
     * {@link PriceMaskResolver#shouldMaskPrice(String)} — callers without
     * {@code procurement:price:view} get a response without the {@code unitPrice} key
     * (omit, NOT null — preserves Canvas DYNAMIC dropdown behavior). Hand-built
     * {@code Map<String, Object>} responses bypass {@code PriceFieldResponseAdvice} reflective
     * field-strip (Map keys aren't in {@code PRICE_VALUE_KEYS} fallback). Any future
     * price-bearing field added here MUST be gated by {@code maskPrice}. */
    @GetMapping("/products/{id}")
    @Operation(summary = "按 ID 查单个产品")
    public ApiResponse<Map<String, Object>> getProduct(
            @PathVariable String factoryId,
            @PathVariable String id,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        boolean maskPrice = priceMaskResolver.shouldMaskPrice(authorization);
        return productTypeRepository.findById(id)
                .filter(p -> factoryId.equals(p.getFactoryId()))
                .map(p -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", p.getId());
                    m.put("name", p.getName());
                    m.put("code", p.getCode());
                    m.put("specification", p.getSpecification());
                    m.put("unit", p.getUnit());
                    if (!maskPrice) {
                        m.put("unitPrice", p.getUnitPrice());
                    }
                    m.put("boxConversionCoefficient", p.getBoxConversionCoefficient());
                    return m;
                })
                .map(ApiResponse::success)
                .orElseGet(() -> ApiResponse.success(null));
    }

    /** 客户查找 (sales_order.customerId 字段用此). */
    @GetMapping("/customers")
    @Operation(summary = "客户查找")
    public ApiResponse<Map<String, Object>> findCustomers(
            @PathVariable String factoryId,
            @RequestParam(required = false, defaultValue = "") String keyword,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "50") int size) {
        // R10 CRIT-2 fix: JPQL push-down isActive filter via findByFactoryIdAndIsActiveTrue
        // / searchActiveByNamePaged. Replaces R8's probabilistic post-page filter that
        // starved on factories with concentrated inactive blocks (e.g. bulk-deactivated
        // import). totalElements now correct, no over-fetch needed.
        org.springframework.data.domain.PageRequest pageable = PageRequest.of(
                Math.max(page - 1, 0), clampSize(size),
                Sort.by(Sort.Direction.ASC, "name"));
        String esc = SqlLikeEscaper.escape(keyword);
        Page<Customer> result = (esc == null || esc.isBlank())
                ? customerRepository.findByFactoryIdAndIsActiveTrue(factoryId, pageable)
                : customerRepository.searchActiveByNamePaged(factoryId, esc, pageable);
        List<Map<String, Object>> content = result.getContent().stream()
                .map(c -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", c.getId());
                    m.put("name", c.getName());
                    m.put("customerCode", c.getCustomerCode());
                    m.put("contactPerson", c.getContactPerson());
                    return m;
                })
                .collect(Collectors.toList());
        return wrap(content, result.getTotalElements());
    }

    /** 供应商查找. */
    @GetMapping("/suppliers")
    @Operation(summary = "供应商查找")
    public ApiResponse<Map<String, Object>> findSuppliers(
            @PathVariable String factoryId,
            @RequestParam(required = false, defaultValue = "") String keyword,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "50") int size) {
        // R10 CRIT-2 fix: JPQL push-down (see findCustomers).
        org.springframework.data.domain.PageRequest pageable = PageRequest.of(
                Math.max(page - 1, 0), clampSize(size),
                Sort.by(Sort.Direction.ASC, "name"));
        String esc = SqlLikeEscaper.escape(keyword);
        Page<Supplier> result = (esc == null || esc.isBlank())
                ? supplierRepository.findByFactoryIdAndIsActiveTrue(factoryId, pageable)
                : supplierRepository.searchActiveByNamePaged(factoryId, esc, pageable);
        List<Map<String, Object>> content = result.getContent().stream()
                .map(s -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", s.getId());
                    m.put("name", s.getName());
                    // 简称 + displayName: Canvas ReferenceSelector 的供应商下拉走这个投影,
                    // 不带上简称的话「下拉里好认」在所有动态表单上都不生效。
                    m.put("shortName", s.getShortName());
                    m.put("displayName", s.getShortName() != null && !s.getShortName().isBlank()
                            ? s.getShortName() : s.getName());
                    m.put("supplierCode", s.getSupplierCode());
                    m.put("contactPerson", s.getContactPerson());
                    return m;
                })
                .collect(Collectors.toList());
        return wrap(content, result.getTotalElements());
    }

    /** 产品/SKU 查找 (订单明细 productTypeId).
     *
     * <p>RBAC (PR #488 §F2, 2026-05-12): {@code unitPrice} is gated via
     * {@link PriceMaskResolver#shouldMaskPrice(String)} — callers without
     * {@code procurement:price:view} get a response without the {@code unitPrice} key
     * (omit, NOT null — preserves Canvas DYNAMIC dropdown behavior). Hand-built
     * {@code Map<String, Object>} responses bypass {@code PriceFieldResponseAdvice} reflective
     * field-strip (Map keys aren't in {@code PRICE_VALUE_KEYS} fallback). Any future
     * price-bearing field added here MUST be gated by {@code maskPrice}. */
    @GetMapping("/products")
    @Operation(summary = "产品查找")
    public ApiResponse<Map<String, Object>> findProducts(
            @PathVariable String factoryId,
            @RequestParam(required = false, defaultValue = "") String keyword,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "50") int size,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        boolean maskPrice = priceMaskResolver.shouldMaskPrice(authorization);
        // R10 CRIT-2 fix: JPQL push-down (see findCustomers).
        org.springframework.data.domain.PageRequest pageable = PageRequest.of(
                Math.max(page - 1, 0), clampSize(size),
                Sort.by(Sort.Direction.ASC, "name"));
        String esc = SqlLikeEscaper.escape(keyword);
        Page<ProductType> result = (esc == null || esc.isBlank())
                ? productTypeRepository.findByFactoryIdAndIsActiveTrue(factoryId, pageable)
                : productTypeRepository.searchActiveProductTypes(factoryId, esc, pageable);
        List<Map<String, Object>> content = result.getContent().stream()
                .map(p -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", p.getId());
                    m.put("name", p.getName());
                    m.put("code", p.getCode());
                    m.put("specification", p.getSpecification());
                    m.put("unit", p.getUnit());
                    if (!maskPrice) {
                        m.put("unitPrice", p.getUnitPrice());
                    }
                    // C-6 (2026-05-09): 加 boxConversionCoefficient 给 sales_order DYNAMIC P1-2.
                    // spec: docs/superpowers/specs/2026-05-09-canvas-c6-sales-order-dynamic-migration.md
                    m.put("boxConversionCoefficient", p.getBoxConversionCoefficient());
                    return m;
                })
                .collect(Collectors.toList());
        return wrap(content, result.getTotalElements());
    }

    /** 原材料查找 (bom.materialTypeId 等). Apr 25 2026 audit: bom DYNAMIC mode active vulnerable
     *  — old schema pointed at /material-types (404, real path is /raw-material-types).
     *
     *  T4-B4 (issue #540 backend prerequisite for #532, 2026-05-13): bulk-load currentStock per
     *  material via MaterialBatchRepository.sumQuantityByMaterialType so the transfer/list.vue
     *  manual-create dialog can show "现有库存" inline next to 调拨数量. Single query (GROUP BY) —
     *  no N+1. NULL → emit 0 to keep frontend display deterministic. */
    @GetMapping("/materials")
    @Operation(summary = "原材料查找")
    public ApiResponse<Map<String, Object>> findMaterials(
            @PathVariable String factoryId,
            @RequestParam(required = false, defaultValue = "") String keyword,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "50") int size) {
        // R10 CRIT-2 fix: JPQL push-down (see findCustomers).
        org.springframework.data.domain.PageRequest pageable = PageRequest.of(
                Math.max(page - 1, 0), clampSize(size),
                Sort.by(Sort.Direction.ASC, "name"));
        String esc = SqlLikeEscaper.escape(keyword);
        Page<RawMaterialType> result = (esc == null || esc.isBlank())
                ? materialTypeRepository.findByFactoryIdAndIsActiveTrue(factoryId, pageable)
                : materialTypeRepository.searchActiveMaterialTypes(factoryId, esc, pageable);

        // T4-B4 (issue #540): single bulk query for per-material currentStock (avoids N+1).
        // sumQuantityByMaterialType returns List<Object[]>{materialTypeId, sum}.
        Map<String, BigDecimal> stockMap = new HashMap<>();
        for (Object[] row : materialBatchRepository.sumQuantityByMaterialType(factoryId)) {
            if (row[0] != null && row[1] != null) {
                stockMap.put((String) row[0], (BigDecimal) row[1]);
            }
        }

        List<Map<String, Object>> content = result.getContent().stream()
                .map(m -> {
                    Map<String, Object> mp = new LinkedHashMap<>();
                    mp.put("id", m.getId());
                    mp.put("name", m.getName());
                    mp.put("code", m.getCode());
                    mp.put("unit", m.getUnit());
                    mp.put("category", m.getCategory());
                    // T4-B4: deterministic 0 fallback when no AVAILABLE batches (rather than null,
                    // so frontend can always render number — empty inventory is meaningful).
                    mp.put("currentStock", stockMap.getOrDefault(m.getId(), BigDecimal.ZERO));
                    return mp;
                })
                .collect(Collectors.toList());
        return wrap(content, result.getTotalElements());
    }

    /** GET-by-id for material. Reviewer Issue #1 same pattern. */
    @GetMapping("/materials/{id}")
    @Operation(summary = "按 ID 查单个原材料")
    public ApiResponse<Map<String, Object>> getMaterial(@PathVariable String factoryId,
                                                        @PathVariable String id) {
        return materialTypeRepository.findById(id)
                .filter(m -> factoryId.equals(m.getFactoryId()))
                .map(m -> {
                    Map<String, Object> mp = new LinkedHashMap<>();
                    mp.put("id", m.getId());
                    mp.put("name", m.getName());
                    mp.put("code", m.getCode());
                    mp.put("unit", m.getUnit());
                    mp.put("category", m.getCategory());
                    return mp;
                })
                .map(ApiResponse::success)
                .orElseGet(() -> ApiResponse.success(null));
    }

    /** 报价单查找 (sales_order.quoteId 字段). R6: adds keyword search.
     *  R8 audit S1: filter to APPROVED + not-expired so users can't pick DRAFT/REJECTED/EXPIRED quotes. */
    @GetMapping("/quotes")
    @Operation(summary = "报价单查找 (仅有效报价)")
    public ApiResponse<Map<String, Object>> findQuotes(
            @PathVariable String factoryId,
            @RequestParam(required = false, defaultValue = "") String keyword,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "50") int size) {
        // R8 C2 + S2: over-fetch + post-filter to avoid empty dropdowns when page 1 has many
        // ineligible quotes (DRAFT/REJECTED/EXPIRED crowding the recent-first slot).
        int pageSize = clampSize(size);
        org.springframework.data.domain.PageRequest pageable = PageRequest.of(
                Math.max(page - 1, 0), Math.min(pageSize * 4, 200),
                Sort.by(Sort.Direction.DESC, "createdAt"));
        Page<OperationalQuote> result = quoteRepository.findByFactoryIdAndDeletedAtIsNullOrderByCreatedAtDesc(
                factoryId, pageable);
        String esc = keyword == null ? "" : keyword.trim();
        java.time.LocalDate today = java.time.LocalDate.now();
        List<OperationalQuote> filtered = result.getContent().stream()
                .filter(q -> OperationalQuoteServiceImpl.STATUS_APPROVED.equals(q.getStatus()))
                .filter(q -> q.getValidUntil() == null || !q.getValidUntil().isBefore(today))
                .filter(q -> esc.isEmpty() || (q.getQuoteNo() != null && q.getQuoteNo().contains(esc)))
                .collect(Collectors.toList());
        List<Map<String, Object>> content = filtered.stream()
                .limit(pageSize)
                .map(q -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", q.getId());
                    m.put("quoteNo", q.getQuoteNo());
                    m.put("status", q.getStatus());
                    m.put("customerId", q.getCustomerId());
                    return m;
                })
                .collect(Collectors.toList());
        return wrap(content, filtered.size());
    }

    // R21 audit C2: whitelist Sets moved to OrderUsageWhitelists (single source of truth).
    // Was 5 copies scattered across InvoiceServiceImpl, ArApServiceImpl, ReferenceDataController,
    // ProductionPlanController, SalesServiceImpl — drift waiting to happen.

    /** R12 audit S2 meta: 销售订单 dropdown for outbound/finance_ar/production_plan.
     *  R19 audit CRIT-A: per-usage whitelist via ?usage param (V14 migration sets
     *  per-module schema apiEndpoint to include the right usage). Default=all is broad
     *  inclusive (only excludes DRAFT/CANCELLED/FINANCE_REJECTED) — safe legacy fallback. */
    @GetMapping("/sales-orders")
    @Operation(summary = "销售订单查找 (per-usage whitelist)")
    public ApiResponse<Map<String, Object>> findSalesOrders(
            @PathVariable String factoryId,
            @RequestParam(required = false, defaultValue = "") String keyword,
            @RequestParam(required = false, defaultValue = "invoiceable") String usage,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "50") int size) {
        int pageSize = clampSize(size);
        org.springframework.data.domain.PageRequest pageable = PageRequest.of(
                Math.max(page - 1, 0), Math.min(pageSize * 4, 200));
        Page<SalesOrder> result = salesOrderRepository.findByFactoryIdOrderByCreatedAtDesc(factoryId, pageable);
        String esc = keyword == null ? "" : keyword.trim();
        // R20 audit S1 fix: typo in usage falls back to STRICTEST (invoiceable), not 'all'
        java.util.Set<com.cretas.aims.entity.enums.SalesOrderStatus> whitelist =
                com.cretas.aims.domain.OrderUsageWhitelists.resolveSO(usage);
        List<SalesOrder> filtered = result.getContent().stream()
                .filter(so -> whitelist.contains(so.getStatus()))
                .filter(so -> esc.isEmpty() || (so.getOrderNumber() != null && so.getOrderNumber().contains(esc)))
                .collect(Collectors.toList());
        List<Map<String, Object>> content = filtered.stream()
                .limit(pageSize)
                .map(so -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", so.getId());
                    m.put("orderNumber", so.getOrderNumber());
                    m.put("customerId", so.getCustomerId());
                    return m;
                })
                .collect(Collectors.toList());
        return wrap(content, filtered.size());
    }

    @GetMapping("/sales-orders/{id}")
    public ApiResponse<Map<String, Object>> getSalesOrder(@PathVariable String factoryId,
                                                          @PathVariable String id) {
        return salesOrderRepository.findById(id)
                .filter(so -> factoryId.equals(so.getFactoryId()))
                .map(so -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", so.getId());
                    m.put("orderNumber", so.getOrderNumber());
                    m.put("customerId", so.getCustomerId());
                    return m;
                })
                .map(ApiResponse::success)
                .orElseGet(() -> ApiResponse.success(null));
    }

    // R21: PO whitelists also moved to OrderUsageWhitelists.

    /** R12 audit S2 meta: 采购订单 dropdown for inbound/finance_ap.
     *  R19 audit CRIT-B: per-usage whitelist via ?usage. inbound default=receivable. */
    @GetMapping("/purchase-orders")
    @Operation(summary = "采购订单查找 (per-usage whitelist)")
    public ApiResponse<Map<String, Object>> findPurchaseOrders(
            @PathVariable String factoryId,
            @RequestParam(required = false, defaultValue = "") String keyword,
            @RequestParam(required = false, defaultValue = "invoiceable") String usage,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "50") int size) {
        int pageSize = clampSize(size);
        org.springframework.data.domain.PageRequest pageable = PageRequest.of(
                Math.max(page - 1, 0), Math.min(pageSize * 4, 200));
        Page<PurchaseOrder> result = purchaseOrderRepository.findByFactoryIdOrderByCreatedAtDesc(factoryId, pageable);
        String esc = keyword == null ? "" : keyword.trim();
        // R20 audit S1 fix: typo in usage falls back to STRICTEST (invoiceable), not 'all'
        java.util.Set<com.cretas.aims.entity.enums.PurchaseOrderStatus> whitelist =
                com.cretas.aims.domain.OrderUsageWhitelists.resolvePO(usage);
        List<PurchaseOrder> filtered = result.getContent().stream()
                .filter(po -> whitelist.contains(po.getStatus()))
                .filter(po -> esc.isEmpty() || (po.getOrderNumber() != null && po.getOrderNumber().contains(esc)))
                .collect(Collectors.toList());
        List<Map<String, Object>> content = filtered.stream()
                .limit(pageSize)
                .map(po -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", po.getId());
                    m.put("poNumber", po.getOrderNumber());  // schema displayField=poNumber per V13
                    m.put("supplierId", po.getSupplierId());
                    return m;
                })
                .collect(Collectors.toList());
        return wrap(content, filtered.size());
    }

    @GetMapping("/purchase-orders/{id}")
    public ApiResponse<Map<String, Object>> getPurchaseOrder(@PathVariable String factoryId,
                                                             @PathVariable String id) {
        return purchaseOrderRepository.findById(id)
                .filter(po -> factoryId.equals(po.getFactoryId()))
                .map(po -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", po.getId());
                    m.put("poNumber", po.getOrderNumber());
                    m.put("supplierId", po.getSupplierId());
                    return m;
                })
                .map(ApiResponse::success)
                .orElseGet(() -> ApiResponse.success(null));
    }

    /** R13 (R12 audit Q6 fix): material batch dropdown for quality_inspection.batchId,
     *  production_report.batchId, traceability.productBatchId. Replaces lossy /materials
     *  fallback that V11 used (returned RawMaterialType, not MaterialBatch — wrong entity). */
    @GetMapping("/batches")
    @Operation(summary = "物料批次查找")
    public ApiResponse<Map<String, Object>> findBatches(
            @PathVariable String factoryId,
            @RequestParam(required = false, defaultValue = "") String keyword,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "50") int size) {
        // R15 self-check Q5: explicit ORDER BY createdAt DESC — repo's findByFactoryId
        // has no built-in sort, dropdown order would be undefined (DB insertion-order).
        // For batch dropdowns most-recent-first is the expected UX.
        org.springframework.data.domain.PageRequest pageable = PageRequest.of(
                Math.max(page - 1, 0), clampSize(size),
                Sort.by(Sort.Direction.DESC, "createdAt"));
        String esc = SqlLikeEscaper.escape(keyword);
        Page<MaterialBatch> result = (esc == null || esc.isBlank())
                ? materialBatchRepository.findByFactoryId(factoryId, pageable)
                : materialBatchRepository.searchByKeyword(factoryId, esc, pageable);
        List<Map<String, Object>> content = result.getContent().stream()
                .map(b -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", b.getId());
                    m.put("batchNumber", b.getBatchNumber());
                    return m;
                })
                .collect(Collectors.toList());
        return wrap(content, result.getTotalElements());
    }

    @GetMapping("/batches/{id}")
    @Operation(summary = "按 ID 查单个物料批次")
    public ApiResponse<Map<String, Object>> getBatch(@PathVariable String factoryId,
                                                     @PathVariable String id) {
        return materialBatchRepository.findById(id)
                .filter(b -> factoryId.equals(b.getFactoryId()))
                .map(b -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", b.getId());
                    m.put("batchNumber", b.getBatchNumber());
                    return m;
                })
                .map(ApiResponse::success)
                .orElseGet(() -> ApiResponse.success(null));
    }

    /** GET-by-id for quote. */
    @GetMapping("/quotes/{id}")
    @Operation(summary = "按 ID 查单个报价单")
    public ApiResponse<Map<String, Object>> getQuote(@PathVariable String factoryId,
                                                     @PathVariable String id) {
        return quoteRepository.findByIdAndFactoryIdAndDeletedAtIsNull(id, factoryId)
                .map(q -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", q.getId());
                    m.put("quoteNo", q.getQuoteNo());
                    m.put("status", q.getStatus());
                    m.put("customerId", q.getCustomerId());
                    return m;
                })
                .map(ApiResponse::success)
                .orElseGet(() -> ApiResponse.success(null));
    }

    private int clampSize(int requested) {
        if (requested <= 0) return DEFAULT_PAGE_SIZE;
        return Math.min(requested, MAX_PAGE_SIZE);
    }

    private ApiResponse<Map<String, Object>> wrap(List<Map<String, Object>> content, long total) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("content", content);
        body.put("totalElements", total);
        return ApiResponse.success(body);
    }
}
