package com.cretas.aims.security;

import com.cretas.aims.controller.finance.FinanceReportController;
import com.cretas.aims.dto.restaurant.SupplierDeliveryNoteDto;
import com.cretas.aims.dto.smartbi.TableDataResponse;
import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.entity.User;
import com.cretas.aims.entity.enums.FactoryUserRole;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.service.PermissionService;
import com.cretas.aims.service.impl.PermissionServiceImpl;
import com.cretas.aims.annotation.RequirePermission;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.mock.web.MockHttpServletRequest;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * TDD tests for the three RBAC security fixes from QA 組3:
 * <ul>
 *   <li><b>BUG-01-RBAC (HIGH)</b>: SmartBI upload data rows with Chinese finance
 *       column names (金额, 租金, 工资, etc.) must be masked for roles without
 *       finance permission. Non-financial columns preserved.</li>
 *   <li><b>BUG-02-RBAC (MED)</b>: {@code SupplierDeliveryNoteDto.totalAmount} and
 *       line-level price fields annotated with {@code @PriceSensitive} must be
 *       nulled for roles without finance permission.</li>
 *   <li><b>GAP-05-C (HIGH)</b>: {@code finance_manager} must be able to access the
 *       三表 APIs (资产负债表/利润表/现金流量表). Tests verify both the controller
 *       annotation allows finance roles AND the {@code PermissionServiceImpl} code
 *       override ({@code CRITICAL_PERMISSION_OVERRIDES}) bypasses any stale DB row.
 *   </li>
 * </ul>
 *
 * <p>Follows existing project test patterns from {@link PriceFieldResponseAdviceTest}.
 *
 * @author Cretas Team
 * @since 2026-06-09 (QA 組3 fix)
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("QA 組3 Finance RBAC Fix — BUG-01-RBAC / BUG-02-RBAC / GAP-05-C")
class FinanceRbacBugFixTest {

    @Mock private PermissionService permissionService;
    @Mock private UserRepository userRepository;
    @InjectMocks private PriceFieldResponseAdvice advice;

    private MockHttpServletRequest httpRequest;
    private ServerHttpRequest serverRequest;

    @BeforeEach
    void setup() {
        httpRequest = new MockHttpServletRequest();
        serverRequest = new ServletServerHttpRequest(httpRequest);
        PriceSensitiveContext.clear();
    }

    @AfterEach
    void teardown() {
        PriceSensitiveContext.clear();
    }

    // ───────── Shared helpers ─────────

    private void asUserWithPricePermission(Long userId, boolean canViewPrice) {
        httpRequest.setAttribute("userId", userId);
        User user = new User();
        user.setId(userId);
        lenient().when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        lenient().when(permissionService.hasPermission(eq(user),
                eq(PriceFieldResponseAdvice.PRICE_VIEW_PERMISSION))).thenReturn(canViewPrice);
    }

    private Object run(Object body) {
        return advice.beforeBodyWrite(body, null, MediaType.APPLICATION_JSON, null, serverRequest, null);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // BUG-01-RBAC — SmartBI upload data row Chinese finance column masking
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("BUG-01-RBAC — SmartBI upload data rows: finance columns masked for sales_manager")
    class Bug01SmartBIUploadDataRowMasking {

        /**
         * Builds a sample finance-sheet row as returned by
         * {@code GET /smart-bi/uploads/{id}/data}.
         * Keys are Chinese column headers from typical finance Excel uploads.
         */
        private Map<String, Object> sampleFinanceRow() {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("日期", "2026-05-01");              // date — should be preserved
            row.put("门店", "昆山总店");                  // store name — should be preserved
            row.put("项目", "人工成本");                  // category — should be preserved
            row.put("金额", 3200.00);                   // amount — must be masked
            row.put("租金", 8500.00);                   // rent — must be masked
            row.put("工资", 12000.00);                  // salary — must be masked
            row.put("利润", 5600.00);                   // profit — must be masked
            row.put("收入", 45000.00);                  // revenue — must be masked
            row.put("成本", 32000.00);                  // cost — must be masked
            row.put("费用", 1200.00);                   // expense — must be masked
            return row;
        }

        /**
         * Builds a POS/restaurant-style row that should NOT be masked.
         * Non-financial data from store operations data.
         */
        private Map<String, Object> samplePosRow() {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("日期", "2026-05-01");
            row.put("门店", "昆山总店");
            row.put("菜品名称", "叮咚猪蹄");             // dish name — preserved
            row.put("订单数量", 42);                    // order count — preserved (not finance)
            row.put("评分", 4.8);                      // rating — preserved
            return row;
        }

        private TableDataResponse buildUploadDataResponse(List<Map<String, Object>> rows) {
            return TableDataResponse.builder()
                    .headers(List.of("日期", "门店", "金额"))
                    .data(rows)
                    .total(rows.size())
                    .page(0)
                    .size(50)
                    .totalPages(1)
                    .build();
        }

        @Test
        @DisplayName("sales_manager (no finance permission): finance column values in upload rows are nulled")
        void salesManager_financeRows_financeValuesNulled() {
            asUserWithPricePermission(101L, false); // sales_manager: no price view

            Map<String, Object> row = sampleFinanceRow();
            TableDataResponse response = buildUploadDataResponse(List.of(row));
            ApiResponse<TableDataResponse> body = ApiResponse.success(response);

            run(body);

            // Finance-bearing numeric columns must be null
            assertNull(row.get("金额"),  "金额 (amount) must be masked for sales_manager");
            assertNull(row.get("租金"),  "租金 (rent) must be masked for sales_manager");
            assertNull(row.get("工资"),  "工资 (salary) must be masked for sales_manager");
            assertNull(row.get("利润"),  "利润 (profit) must be masked for sales_manager");
            assertNull(row.get("收入"),  "收入 (revenue) must be masked for sales_manager");
            assertNull(row.get("成本"),  "成本 (cost) must be masked for sales_manager");
            assertNull(row.get("费用"),  "费用 (expense) must be masked for sales_manager");

            // Non-financial columns preserved (POS/restaurant data)
            assertEquals("2026-05-01", row.get("日期"),  "date column preserved");
            assertEquals("昆山总店",    row.get("门店"),  "store name preserved");
            assertEquals("人工成本",    row.get("项目"),  "category label preserved");
        }

        @Test
        @DisplayName("finance_manager (has finance permission): upload rows returned unmasked")
        void financeManager_financeRows_unmasked() {
            asUserWithPricePermission(201L, true); // finance_manager: has price view

            Map<String, Object> row = sampleFinanceRow();
            TableDataResponse response = buildUploadDataResponse(List.of(row));
            ApiResponse<TableDataResponse> body = ApiResponse.success(response);

            run(body);

            // Finance values must be preserved for finance role
            assertEquals(3200.00,  row.get("金额"),  "金额 must be visible for finance_manager");
            assertEquals(8500.00,  row.get("租金"),  "租金 must be visible for finance_manager");
            assertEquals(12000.00, row.get("工资"),  "工资 must be visible for finance_manager");
        }

        @Test
        @DisplayName("sales_manager: non-financial POS columns in upload rows are NOT masked")
        void salesManager_posRows_nonFinancialColumnsPreserved() {
            asUserWithPricePermission(101L, false);

            Map<String, Object> row = samplePosRow();
            TableDataResponse response = buildUploadDataResponse(List.of(row));
            ApiResponse<TableDataResponse> body = ApiResponse.success(response);

            run(body);

            // POS data that analytics module legitimately needs — must not be masked
            assertEquals("叮咚猪蹄", row.get("菜品名称"), "dish name preserved");
            assertEquals(42,         row.get("订单数量"), "order count preserved (not finance)");
            assertEquals(4.8,        row.get("评分"),    "rating preserved (not finance)");
        }

        @Test
        @DisplayName("FINANCE_COLUMN_KEY_REGEX: English finance keys also masked")
        void salesManager_englishFinanceColumnKeys_masked() {
            asUserWithPricePermission(101L, false);

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("date", "2026-05-01");
            row.put("amount", 5000.00);         // English 'amount' key
            row.put("revenue", 45000.00);       // English 'revenue' key
            row.put("labor_cost", 12000.00);    // English 'labor' key
            row.put("store_name", "Kunshan");   // non-finance string — preserved

            TableDataResponse response = buildUploadDataResponse(List.of(row));
            ApiResponse<TableDataResponse> body = ApiResponse.success(response);

            run(body);

            // BUG-01 fix: english column headers also match FINANCE_COLUMN_KEY_REGEX
            // Note: 'amount' and 'revenue' are in PRICE_VALUE_KEYS and FINANCE_COLUMN_KEY_REGEX
            assertNull(row.get("amount"),     "English 'amount' column must be masked");
            assertNull(row.get("revenue"),    "English 'revenue' column must be masked");
            assertNull(row.get("labor_cost"), "English 'labor_cost' column must be masked");
            assertEquals("Kunshan",  row.get("store_name"),  "store_name string preserved");
            assertEquals("2026-05-01", row.get("date"),      "date string preserved");
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // BUG-02-RBAC — SupplierDeliveryNoteDto finance fields annotation contract
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("BUG-02-RBAC — SupplierDeliveryNoteDto: @PriceSensitive annotations present")
    class Bug02SupplierDeliveryNoteDtoAnnotations {

        @Test
        @DisplayName("SupplierDeliveryNoteDto.totalAmount is annotated @PriceSensitive")
        void totalAmount_isAnnotatedPriceSensitive() throws Exception {
            var field = SupplierDeliveryNoteDto.class.getDeclaredField("totalAmount");
            assertTrue(field.isAnnotationPresent(PriceSensitive.class),
                    "BUG-02-RBAC: SupplierDeliveryNoteDto.totalAmount must be @PriceSensitive — " +
                    "it leaked ¥470 to sales_manager in QA org3 verification");
        }

        @Test
        @DisplayName("SupplierDeliveryNoteDto.LineDto.unitPrice is annotated @PriceSensitive")
        void lineDto_unitPrice_isAnnotatedPriceSensitive() throws Exception {
            var field = SupplierDeliveryNoteDto.LineDto.class.getDeclaredField("unitPrice");
            assertTrue(field.isAnnotationPresent(PriceSensitive.class),
                    "LineDto.unitPrice must be @PriceSensitive — per-item price leaked to sales_manager");
        }

        @Test
        @DisplayName("SupplierDeliveryNoteDto.LineDto.baselineUnitPrice is annotated @PriceSensitive")
        void lineDto_baselineUnitPrice_isAnnotatedPriceSensitive() throws Exception {
            var field = SupplierDeliveryNoteDto.LineDto.class.getDeclaredField("baselineUnitPrice");
            assertTrue(field.isAnnotationPresent(PriceSensitive.class),
                    "LineDto.baselineUnitPrice must be @PriceSensitive — baseline price leaked to sales_manager");
        }

        @Test
        @DisplayName("SupplierDeliveryNoteDto.LineDto.lineAmount is annotated @PriceSensitive")
        void lineDto_lineAmount_isAnnotatedPriceSensitive() throws Exception {
            var field = SupplierDeliveryNoteDto.LineDto.class.getDeclaredField("lineAmount");
            assertTrue(field.isAnnotationPresent(PriceSensitive.class),
                    "LineDto.lineAmount must be @PriceSensitive — line total leaked to sales_manager");
        }

        @Test
        @DisplayName("SupplierDeliveryNoteDto.LineDto.priceVarianceRate is annotated @PriceSensitive")
        void lineDto_priceVarianceRate_isAnnotatedPriceSensitive() throws Exception {
            var field = SupplierDeliveryNoteDto.LineDto.class.getDeclaredField("priceVarianceRate");
            assertTrue(field.isAnnotationPresent(PriceSensitive.class),
                    "LineDto.priceVarianceRate must be @PriceSensitive — price deviation rate leaked to sales_manager");
        }
    }

    @Nested
    @DisplayName("BUG-02-RBAC — PriceFieldResponseAdvice strips delivery note amounts at runtime")
    class Bug02DeliveryNoteRuntimeMasking {

        private SupplierDeliveryNoteDto sampleNote() {
            SupplierDeliveryNoteDto note = SupplierDeliveryNoteDto.builder()
                    .id("dn-001")
                    .supplierName("昆山蔬菜供应商")
                    .noteNumber("DN-20260601-001")
                    .totalAmount(new BigDecimal("470.00"))
                    .build();

            SupplierDeliveryNoteDto.LineDto line = SupplierDeliveryNoteDto.LineDto.builder()
                    .id(1L)
                    .ingredientName("青花椒")
                    .quantity(new BigDecimal("10.0"))
                    .unit("kg")
                    .unitPrice(new BigDecimal("47.00"))
                    .baselineUnitPrice(new BigDecimal("45.00"))
                    .priceVarianceRate(new BigDecimal("4.44"))
                    .lineAmount(new BigDecimal("470.00"))
                    .build();

            note.setLines(List.of(line));
            return note;
        }

        @Test
        @DisplayName("sales_manager (no price perm): totalAmount and all line price fields nulled")
        void salesManager_deliveryNote_priceFieldsNulled() {
            asUserWithPricePermission(101L, false);

            SupplierDeliveryNoteDto note = sampleNote();
            ApiResponse<SupplierDeliveryNoteDto> body = ApiResponse.success(note);

            run(body);

            assertNull(note.getTotalAmount(),
                    "BUG-02: totalAmount must be null for sales_manager");
            SupplierDeliveryNoteDto.LineDto line = note.getLines().get(0);
            assertNull(line.getUnitPrice(),           "unitPrice null for sales_manager");
            assertNull(line.getBaselineUnitPrice(),   "baselineUnitPrice null for sales_manager");
            assertNull(line.getPriceVarianceRate(),   "priceVarianceRate null for sales_manager");
            assertNull(line.getLineAmount(),          "lineAmount null for sales_manager");

            // Non-finance fields preserved
            assertEquals("DN-20260601-001", note.getNoteNumber(), "noteNumber preserved");
            assertEquals("昆山蔬菜供应商",   note.getSupplierName(), "supplierName preserved");
            assertEquals("青花椒",          line.getIngredientName(), "ingredientName preserved");
            assertEquals(new BigDecimal("10.0"), line.getQuantity(), "quantity preserved");
        }

        @Test
        @DisplayName("finance_manager (has price perm): all delivery note amounts visible")
        void financeManager_deliveryNote_pricesVisible() {
            asUserWithPricePermission(201L, true);

            SupplierDeliveryNoteDto note = sampleNote();
            ApiResponse<SupplierDeliveryNoteDto> body = ApiResponse.success(note);

            run(body);

            assertEquals(new BigDecimal("470.00"), note.getTotalAmount(),
                    "totalAmount visible for finance_manager");
            SupplierDeliveryNoteDto.LineDto line = note.getLines().get(0);
            assertEquals(new BigDecimal("47.00"), line.getUnitPrice(),
                    "unitPrice visible for finance_manager");
            assertEquals(new BigDecimal("470.00"), line.getLineAmount(),
                    "lineAmount visible for finance_manager");
        }

        @Test
        @DisplayName("restaurant_owner (has price perm): delivery note amounts visible")
        void restaurantOwner_deliveryNote_pricesVisible() {
            asUserWithPricePermission(301L, true);

            SupplierDeliveryNoteDto note = sampleNote();
            run(ApiResponse.success(note));

            assertNotNull(note.getTotalAmount(), "restaurant_owner can see totalAmount");
            assertNotNull(note.getLines().get(0).getUnitPrice(),
                    "restaurant_owner can see unitPrice");
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // GAP-05-C — FinanceReportController: finance_manager must access 三表
    //
    // INVESTIGATION RESULT (2026-06-09):
    //   prod verification confirmed GAP-05-C is NOT a real bug.
    //   - platform_role_permissions: finance_manager:finance = "rw" (correct, present)
    //   - curl prod qhj_finance_mgr → all three endpoints return HTTP 200
    //   - Prior diagnosis ("stale DB row") was wrong; CRITICAL_PERMISSION_OVERRIDES
    //     L0 layer was a redundant no-op AND a latent foot-gun (code overriding DB).
    //     It has been removed.
    //
    // Tests below verify the CORRECT gate: controller annotation allows finance roles,
    // and the hardcoded PERMISSION_MATRIX fallback grants finance_manager finance:rw
    // (which activates when repos are not wired, e.g. in unit-test context).
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("GAP-05-C — FinanceReportController: correct @RequirePermission gate")
    class Gap05cFinanceReportControllerAccess {

        private static final String FINANCE_READ       = "finance:read";
        private static final String FINANCE_READ_WRITE = "finance:read_write";

        @Test
        @DisplayName("FinanceReportController class-level @RequirePermission includes finance:read")
        void financeReportController_classAnnotation_includesFinanceRead() {
            RequirePermission anno = FinanceReportController.class
                    .getAnnotation(RequirePermission.class);
            assertNotNull(anno,
                    "FinanceReportController must have @RequirePermission at class level");
            Set<String> perms = Set.of(anno.value());
            assertTrue(perms.contains(FINANCE_READ) || perms.contains(FINANCE_READ_WRITE),
                    "Class annotation must include at least finance:read or finance:read_write");
            assertFalse(anno.requireAll(),
                    "requireAll must be false — any one of the listed perms is sufficient");
        }

        @Test
        @DisplayName("balanceSheet has no method-level override that blocks finance_manager")
        void balanceSheet_noMethodOverrideThatBlocksFinanceManager() throws Exception {
            Method method = FinanceReportController.class.getDeclaredMethod(
                    "balanceSheet", String.class, Integer.class, Integer.class);
            RequirePermission methodAnno = method.getAnnotation(RequirePermission.class);
            // If there's a method-level annotation it must still include finance:read[_write]
            if (methodAnno != null) {
                Set<String> perms = Set.of(methodAnno.value());
                assertTrue(perms.contains(FINANCE_READ) || perms.contains(FINANCE_READ_WRITE),
                        "balanceSheet method annotation (if present) must allow finance roles");
            }
            // If null: class-level annotation applies → fine (checked above)
        }

        @Test
        @DisplayName("incomeStatement has no method-level override that blocks finance_manager")
        void incomeStatement_noMethodOverrideThatBlocksFinanceManager() throws Exception {
            Method method = FinanceReportController.class.getDeclaredMethod(
                    "incomeStatement",
                    String.class, Integer.class, Integer.class, Integer.class, Integer.class);
            RequirePermission methodAnno = method.getAnnotation(RequirePermission.class);
            if (methodAnno != null) {
                Set<String> perms = Set.of(methodAnno.value());
                assertTrue(perms.contains(FINANCE_READ) || perms.contains(FINANCE_READ_WRITE),
                        "incomeStatement method annotation (if present) must allow finance roles");
            }
        }

        @Test
        @DisplayName("cashFlow has no method-level override that blocks finance_manager")
        void cashFlow_noMethodOverrideThatBlocksFinanceManager() throws Exception {
            Method method = FinanceReportController.class.getDeclaredMethod(
                    "cashFlow",
                    String.class, Integer.class, Integer.class, Integer.class, Integer.class);
            RequirePermission methodAnno = method.getAnnotation(RequirePermission.class);
            if (methodAnno != null) {
                Set<String> perms = Set.of(methodAnno.value());
                assertTrue(perms.contains(FINANCE_READ) || perms.contains(FINANCE_READ_WRITE),
                        "cashFlow method annotation (if present) must allow finance roles");
            }
        }
    }

    @Nested
    @DisplayName("GAP-05-C — PermissionServiceImpl fallback matrix: finance_manager has finance access")
    class Gap05cPermissionServiceFallbackMatrix {

        /**
         * Instantiates PermissionServiceImpl without DB repos (both null).
         * hasPermission() falls through L2(skip) → L1(skip) → PERMISSION_MATRIX fallback,
         * which is the hardcoded safety net and gives the same result as L1 DB.
         * This mirrors what would happen on a fresh DB where L1 has no row for a role.
         */
        private PermissionServiceImpl permissionServiceImpl;

        @BeforeEach
        void setup() {
            permissionServiceImpl = new PermissionServiceImpl();
        }

        private User userWithRole(FactoryUserRole role, String factoryId) {
            User u = new User();
            u.setId(999L);
            u.setRoleCode(role.name());
            u.setFactoryId(factoryId);
            return u;
        }

        @Test
        @DisplayName("finance_manager: finance:read → true (PERMISSION_MATRIX fallback)")
        void financeManager_financeRead_allowedViaMatrix() {
            User fm = userWithRole(FactoryUserRole.finance_manager, "F006");
            // No DB repos → falls through to PERMISSION_MATRIX which has finance:read_write
            assertTrue(permissionServiceImpl.hasPermission(fm, "finance:read"),
                    "finance_manager must have finance:read via PERMISSION_MATRIX fallback");
        }

        @Test
        @DisplayName("finance_manager: finance:read_write → true (PERMISSION_MATRIX fallback)")
        void financeManager_financeReadWrite_allowedViaMatrix() {
            User fm = userWithRole(FactoryUserRole.finance_manager, "F006");
            assertTrue(permissionServiceImpl.hasPermission(fm, "finance:read_write"),
                    "finance_manager must have finance:read_write via PERMISSION_MATRIX fallback");
        }

        @Test
        @DisplayName("restaurant_owner: finance:read → true (PERMISSION_MATRIX fallback)")
        void restaurantOwner_financeRead_allowed() {
            User owner = userWithRole(FactoryUserRole.restaurant_owner, "R001");
            assertTrue(permissionServiceImpl.hasPermission(owner, "finance:read"),
                    "restaurant_owner must have finance:read (月对账确认 requirement)");
        }

        @Test
        @DisplayName("factory_super_admin: finance:read_write → true (short-circuited at top)")
        void superAdmin_financeReadWrite_allowed() {
            User admin = userWithRole(FactoryUserRole.factory_super_admin, "F006");
            assertTrue(permissionServiceImpl.hasPermission(admin, "finance:read_write"),
                    "factory_super_admin must always have all permissions");
        }

        @Test
        @DisplayName("sales_manager: finance:read_write → false (matrix only grants finance:read)")
        void salesManager_financeReadWrite_denied() {
            User sm = userWithRole(FactoryUserRole.sales_manager, "F006");
            // sales_manager has finance:read in matrix but NOT finance:read_write
            assertFalse(permissionServiceImpl.hasPermission(sm, "finance:read_write"),
                    "sales_manager must NOT have finance:read_write");
        }

        @Test
        @DisplayName("warehouse_manager: finance:read → false (no finance in matrix)")
        void warehouseManager_financeRead_denied() {
            User wm = userWithRole(FactoryUserRole.warehouse_manager, "F006");
            assertFalse(permissionServiceImpl.hasPermission(wm, "finance:read"),
                    "warehouse_manager must NOT have finance:read");
        }

        @Test
        @DisplayName("operator: finance:read → false")
        void operator_financeRead_denied() {
            User op = userWithRole(FactoryUserRole.operator, "F006");
            assertFalse(permissionServiceImpl.hasPermission(op, "finance:read"),
                    "operator must NOT have finance:read");
        }

        @Test
        @DisplayName("finance_manager: warehouse:read → false (not in matrix for finance_manager)")
        void financeManager_warehouseRead_denied() {
            User fm = userWithRole(FactoryUserRole.finance_manager, "F006");
            assertFalse(permissionServiceImpl.hasPermission(fm, "warehouse:read"),
                    "finance_manager has no warehouse permission in matrix");
        }
    }
}
