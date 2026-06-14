package com.cretas.aims.security;

import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.dto.common.PageResponse;
import com.cretas.aims.dto.material.MaterialBatchDTO;
import com.cretas.aims.dto.sales.ExtraFeeItem;
import com.cretas.aims.entity.MaterialBatch;
import com.cretas.aims.entity.User;
import com.cretas.aims.entity.inventory.InternalTransfer;
import com.cretas.aims.entity.inventory.InternalTransferItem;
import com.cretas.aims.entity.inventory.PurchaseOrder;
import com.cretas.aims.entity.finance.InvoiceRecord;
import com.cretas.aims.entity.inventory.PurchaseOrderItem;
import com.cretas.aims.entity.inventory.PurchaseReceiveItem;
import com.cretas.aims.entity.inventory.PurchaseReceiveRecord;
import com.cretas.aims.entity.inventory.SalesOrder;
import com.cretas.aims.entity.inventory.SalesOrderItem;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.service.PermissionService;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.mock.web.MockHttpServletRequest;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link PriceFieldResponseAdvice}.
 *
 * <p>Covers:
 * <ul>
 *   <li>Strip price fields when current user lacks {@code procurement:price:view}.</li>
 *   <li>Pass through unchanged when current user has the permission.</li>
 *   <li>Pass through when no authenticated user (e.g. public endpoints).</li>
 *   <li>Handle nested entities (PurchaseOrder → items → unitPrice).</li>
 *   <li>Handle collections (PageResponse.content list).</li>
 *   <li>Handle ApiResponse envelope.</li>
 *   <li>Handle null body.</li>
 *   <li>Bidirectional JPA cycle resilience (PurchaseOrderItem.purchaseOrder cycle).</li>
 * </ul>
 *
 * @author Cretas Team
 * @since 2026-05-12
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PriceFieldResponseAdvice — RBAC price isolation (PR #415 Option B)")
class PriceFieldResponseAdviceTest {

    @Mock private PermissionService permissionService;
    @Mock private UserRepository userRepository;

    @InjectMocks private PriceFieldResponseAdvice advice;

    private MockHttpServletRequest httpRequest;
    private ServerHttpRequest serverRequest;

    @BeforeEach
    void setup() {
        httpRequest = new MockHttpServletRequest();
        serverRequest = new ServletServerHttpRequest(httpRequest);
        // Defensive: clear any ThreadLocal leakage from prior tests in the same JVM.
        // In prod, PriceSensitiveContextFilter handles this — tests have no such filter.
        PriceSensitiveContext.clear();
    }

    @org.junit.jupiter.api.AfterEach
    void teardown() {
        // Always clear after — mirrors prod PriceSensitiveContextFilter finally-block contract.
        PriceSensitiveContext.clear();
    }

    // ───────── Helpers ─────────

    /**
     * Convenience: both price and finance gates share the same bool value.
     * Backward-compat for existing tests where the two gates were not split.
     */
    private void asUser(Long userId, boolean canViewBoth) {
        asUser(userId, canViewBoth, canViewBoth);
    }

    /**
     * Full two-gate stub: independent {@code canViewPrice} and {@code canViewFinance}.
     * PR #547/#615(A) split: sales_manager has canViewPrice=T but canViewFinance=F
     * (finance:read_write gate — sales_manager has only finance:read, NOT rw).
     */
    private void asUser(Long userId, boolean canViewPrice, boolean canViewFinance) {
        httpRequest.setAttribute("userId", userId);
        User user = new User();
        user.setId(userId);
        lenient().when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        lenient().when(permissionService.hasPermission(eq(user),
                eq(PriceFieldResponseAdvice.PRICE_VIEW_PERMISSION))).thenReturn(canViewPrice);
        lenient().when(permissionService.hasPermission(eq(user),
                eq(PriceFieldResponseAdvice.FINANCE_READ_PERMISSION))).thenReturn(canViewFinance);
    }

    private Object run(Object body) {
        return advice.beforeBodyWrite(body, null, MediaType.APPLICATION_JSON, null, serverRequest, null);
    }

    private PurchaseOrder samplePurchaseOrder() {
        PurchaseOrder order = new PurchaseOrder();
        order.setId("po-1");
        order.setFactoryId("F006");
        order.setOrderNumber("PO-2026-001");
        order.setSupplierId("sup-1");
        order.setTotalAmount(new BigDecimal("12345.67"));
        order.setTaxAmount(new BigDecimal("1604.94"));

        PurchaseOrderItem item = new PurchaseOrderItem();
        item.setId(1L);
        item.setMaterialName("青花椒");
        item.setQuantity(new BigDecimal("100.0000"));
        item.setUnitPrice(new BigDecimal("123.4567"));
        item.setTaxRate(new BigDecimal("13.00"));
        order.setItems(Arrays.asList(item));
        return order;
    }

    // ───────── Tests ─────────

    @Test
    @DisplayName("mgr role: PurchaseOrder.totalAmount / taxAmount / item.unitPrice / item.taxRate all nulled")
    void warehouseManager_purchaseOrder_pricesStripped() {
        asUser(10L, false);

        PurchaseOrder order = samplePurchaseOrder();
        run(order);

        assertNull(order.getTotalAmount(), "totalAmount stripped");
        assertNull(order.getTaxAmount(), "taxAmount stripped");
        assertEquals(1, order.getItems().size());
        PurchaseOrderItem item = order.getItems().get(0);
        assertNull(item.getUnitPrice(), "items[0].unitPrice stripped");
        assertNull(item.getTaxRate(), "items[0].taxRate stripped");

        // Non-price fields preserved
        assertEquals("PO-2026-001", order.getOrderNumber());
        assertEquals("青花椒", item.getMaterialName());
        assertEquals(new BigDecimal("100.0000"), item.getQuantity(), "quantity preserved");
    }

    @Test
    @DisplayName("finance role: PurchaseOrder.totalAmount + item.unitPrice unchanged")
    void financeManager_purchaseOrder_pricesUnchanged() {
        asUser(20L, true);

        PurchaseOrder order = samplePurchaseOrder();
        run(order);

        assertEquals(new BigDecimal("12345.67"), order.getTotalAmount());
        assertEquals(new BigDecimal("1604.94"), order.getTaxAmount());
        assertEquals(new BigDecimal("123.4567"), order.getItems().get(0).getUnitPrice());
        assertEquals(new BigDecimal("13.00"), order.getItems().get(0).getTaxRate());
    }

    @Test
    @DisplayName("procurement role: full price visibility")
    void procurementManager_purchaseOrder_pricesUnchanged() {
        asUser(30L, true);

        PurchaseOrder order = samplePurchaseOrder();
        run(order);
        assertNotNull(order.getTotalAmount());
        assertNotNull(order.getItems().get(0).getUnitPrice());
    }

    @Test
    @DisplayName("admin (super) role: full price visibility (whitelist short-circuit)")
    void admin_purchaseOrder_pricesUnchanged() {
        asUser(1L, true);
        PurchaseOrder order = samplePurchaseOrder();
        run(order);
        assertNotNull(order.getTotalAmount());
    }

    @Test
    @DisplayName("mgr role: PurchaseReceiveRecord + nested PurchaseReceiveItem stripped")
    void warehouseManager_receiveRecord_pricesStripped() {
        asUser(10L, false);

        PurchaseReceiveRecord rec = new PurchaseReceiveRecord();
        rec.setId("rec-1");
        rec.setReceiveNumber("PR-2026-001");
        rec.setTotalAmount(new BigDecimal("9999.99"));

        PurchaseReceiveItem item = new PurchaseReceiveItem();
        item.setId(1L);
        item.setMaterialName("青花椒");
        item.setReceivedQuantity(new BigDecimal("99.0000"));
        item.setUnitPrice(new BigDecimal("99.9999"));
        rec.setItems(Arrays.asList(item));

        run(rec);
        assertNull(rec.getTotalAmount());
        assertNull(rec.getItems().get(0).getUnitPrice());
        assertEquals("PR-2026-001", rec.getReceiveNumber());
        assertEquals(new BigDecimal("99.0000"), rec.getItems().get(0).getReceivedQuantity());
    }

    @Test
    @DisplayName("mgr role: MaterialBatch entity (unitPrice nulled)")
    void warehouseManager_materialBatch_pricesStripped() {
        asUser(10L, false);

        MaterialBatch batch = new MaterialBatch();
        batch.setId("batch-1");
        batch.setBatchNumber("BATCH-001");
        batch.setUnitPrice(new BigDecimal("88.88"));

        run(batch);
        assertNull(batch.getUnitPrice());
        assertEquals("BATCH-001", batch.getBatchNumber());
    }

    @Test
    @DisplayName("mgr role: MaterialBatchDTO (totalValue / unitPrice / totalPrice all nulled)")
    void warehouseManager_materialBatchDTO_pricesStripped() {
        asUser(10L, false);

        MaterialBatchDTO dto = MaterialBatchDTO.builder()
                .id("batch-1")
                .batchNumber("BATCH-001")
                .totalValue(new BigDecimal("1000.00"))
                .unitPrice(new BigDecimal("10.00"))
                .totalPrice(new BigDecimal("1000.00"))
                .build();
        run(dto);
        assertNull(dto.getTotalValue());
        assertNull(dto.getUnitPrice());
        assertNull(dto.getTotalPrice());
        assertEquals("BATCH-001", dto.getBatchNumber());
    }

    @Test
    @DisplayName("mgr role: SalesOrder + SalesOrderItem 三价对比 all stripped")
    void warehouseManager_salesOrder_pricesStripped() {
        asUser(10L, false);

        SalesOrder so = new SalesOrder();
        so.setId("so-1");
        so.setOrderNumber("SO-2026-001");
        so.setTotalAmount(new BigDecimal("50000.00"));
        so.setDiscountAmount(new BigDecimal("1000.00"));
        so.setTaxAmount(new BigDecimal("6500.00"));

        SalesOrderItem item = new SalesOrderItem();
        item.setId(1L);
        item.setProductName("青花椒成品");
        item.setQuantity(new BigDecimal("500.0000"));
        item.setUnitPrice(new BigDecimal("100.0000"));
        item.setCostUnitPrice(new BigDecimal("80.0000"));
        item.setTaxRate(new BigDecimal("13.00"));
        item.setDiscountRate(new BigDecimal("5.00"));
        so.setItems(Arrays.asList(item));

        run(so);
        assertNull(so.getTotalAmount());
        assertNull(so.getDiscountAmount());
        assertNull(so.getTaxAmount());
        assertNull(item.getUnitPrice());
        assertNull(item.getCostUnitPrice());
        assertNull(item.getTaxRate());
        assertNull(item.getDiscountRate());
        // Non-price preserved
        assertEquals("SO-2026-001", so.getOrderNumber());
        assertEquals("青花椒成品", item.getProductName());
        assertEquals(new BigDecimal("500.0000"), item.getQuantity());
    }

    @Test
    @DisplayName("mgr role: ApiResponse<PurchaseOrder> envelope — recurses into data")
    void warehouseManager_apiResponse_recurses() {
        asUser(10L, false);

        PurchaseOrder order = samplePurchaseOrder();
        ApiResponse<PurchaseOrder> response = ApiResponse.success("查询成功", order);

        run(response);
        assertNull(response.getData().getTotalAmount(), "ApiResponse.data.totalAmount stripped");
        assertNull(response.getData().getItems().get(0).getUnitPrice());
        // Envelope fields preserved
        assertEquals("查询成功", response.getMessage());
        assertEquals(Boolean.TRUE, response.getSuccess());
    }

    @Test
    @DisplayName("mgr role: PageResponse<PurchaseOrder> — recurses into content list")
    void warehouseManager_pageResponse_recurses() {
        asUser(10L, false);

        PurchaseOrder p1 = samplePurchaseOrder();
        PurchaseOrder p2 = samplePurchaseOrder();
        p2.setId("po-2");
        p2.setOrderNumber("PO-2026-002");

        PageResponse<PurchaseOrder> page = new PageResponse<>();
        page.setContent(Arrays.asList(p1, p2));
        page.setTotalElements(2L);

        ApiResponse<PageResponse<PurchaseOrder>> response = ApiResponse.success("ok", page);

        run(response);
        assertNull(response.getData().getContent().get(0).getTotalAmount());
        assertNull(response.getData().getContent().get(1).getTotalAmount());
        assertNull(response.getData().getContent().get(0).getItems().get(0).getUnitPrice());
        // Counts preserved
        assertEquals(2L, response.getData().getTotalElements());
    }

    @Test
    @DisplayName("Null body — pass through")
    void nullBody_passThrough() {
        // No need to stub user for null body — early return
        Object result = run(null);
        assertNull(result);
    }

    @Test
    @DisplayName("No authenticated user (e.g. /api/mobile/auth/login) — pass through unchanged")
    void noAuthUser_passThrough() {
        // No userId attribute, no user lookup, no permission check
        PurchaseOrder order = samplePurchaseOrder();
        run(order);
        // Untouched — auth layer (JwtAuthInterceptor) handles public endpoints
        assertEquals(new BigDecimal("12345.67"), order.getTotalAmount());
        assertNotNull(order.getItems().get(0).getUnitPrice());
    }

    @Test
    @DisplayName("Bidirectional JPA cycle — does not stack-overflow")
    void cycleProtection() {
        asUser(10L, false);

        PurchaseOrder order = samplePurchaseOrder();
        // Manually wire bidirectional ref (JPA usually does this; mimic post-load state)
        order.getItems().get(0).setPurchaseOrder(order);

        // Should complete without StackOverflowError
        assertDoesNotThrow(() -> run(order));
        assertNull(order.getTotalAmount());
        assertNull(order.getItems().get(0).getUnitPrice());
    }

    @Test
    @DisplayName("Non-JSON content type — pass through")
    void nonJsonContentType_passThrough() {
        // Don't stub user/permission — the advice should short-circuit on content type
        PurchaseOrder order = samplePurchaseOrder();
        Object result = advice.beforeBodyWrite(order, null,
                MediaType.APPLICATION_OCTET_STREAM, null, serverRequest, null);
        // Untouched
        assertEquals(new BigDecimal("12345.67"), order.getTotalAmount());
    }

    @Test
    @DisplayName("Plain List<PurchaseOrder> (no wrapper) — strips each element")
    void plainList_recurses() {
        asUser(10L, false);

        List<PurchaseOrder> list = Arrays.asList(samplePurchaseOrder(), samplePurchaseOrder());
        run(list);
        for (PurchaseOrder o : list) {
            assertNull(o.getTotalAmount());
            assertNull(o.getItems().get(0).getUnitPrice());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // P0 hotfix 2026-05-12 — @PriceSensitive METHOD target + defensive null guards
    // Regression test suite: ensures stripped fields don't trigger NPE in
    // @Transient computed getters during Jackson serialization.
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("P0: SalesOrder.getPayableAmount() returns null safely after totalAmount stripped (no NPE)")
    void p0_salesOrder_getPayableAmount_noNPE_afterStrip() {
        asUser(10L, false);

        SalesOrder so = new SalesOrder();
        so.setId("so-1");
        so.setOrderNumber("SO-2026-001");
        so.setTotalAmount(new BigDecimal("5000.00"));
        so.setDiscountAmount(new BigDecimal("100.00"));
        so.setTaxAmount(new BigDecimal("650.00"));

        // Run strip
        run(so);

        // Field strip should have nulled totalAmount/discountAmount/taxAmount
        assertNull(so.getTotalAmount(), "totalAmount stripped");
        assertNull(so.getDiscountAmount(), "discountAmount stripped");
        assertNull(so.getTaxAmount(), "taxAmount stripped");

        // KEY ASSERTION: getPayableAmount() with stripped totalAmount must NOT throw NPE.
        // This is the exact regression that broke /api/mobile/F006/sales/orders.
        assertDoesNotThrow(() -> so.getPayableAmount(),
                "getPayableAmount() must not NPE when totalAmount is null (defensive guard)");
        assertNull(so.getPayableAmount(),
                "getPayableAmount() should return null when totalAmount stripped");

        // Also verify ThreadLocal hide flag was set so Jackson modifier would short-circuit.
        assertTrue(PriceSensitiveContext.shouldHide("procurement:price:view"),
                "ThreadLocal hide flag set for warehouse_manager scenario");
        PriceSensitiveContext.clear(); // explicit cleanup for test isolation
    }

    @Test
    @DisplayName("P0: SalesOrderItem.getCostTotal()/getLineAmount() return null safely after prices stripped")
    void p0_salesOrderItem_getters_noNPE_afterStrip() {
        asUser(10L, false);

        SalesOrderItem item = new SalesOrderItem();
        item.setId(1L);
        item.setProductName("青花椒成品");
        item.setQuantity(new BigDecimal("500.0000"));
        item.setUnitPrice(new BigDecimal("100.0000"));
        item.setCostUnitPrice(new BigDecimal("80.0000"));
        item.setDiscountRate(new BigDecimal("5.00"));
        item.setTaxRate(new BigDecimal("13.00"));

        run(item);

        assertNull(item.getUnitPrice(), "unitPrice stripped");
        assertNull(item.getCostUnitPrice(), "costUnitPrice stripped");

        // KEY ASSERTIONS: getters must NOT throw, must return null safely
        assertDoesNotThrow(() -> item.getLineAmount(),
                "getLineAmount() must not NPE when unitPrice null");
        assertDoesNotThrow(() -> item.getCostTotal(),
                "getCostTotal() must not NPE when costUnitPrice null");

        assertNull(item.getLineAmount(),
                "getLineAmount() returns null when unitPrice stripped");
        assertNull(item.getCostTotal(),
                "getCostTotal() returns null when costUnitPrice stripped");

        // Non-price preserved
        assertEquals(new BigDecimal("500.0000"), item.getQuantity());
        PriceSensitiveContext.clear();
    }

    @Test
    @DisplayName("P0: PurchaseOrderItem.getLineAmount()/getLineAmountWithTax() safe after unitPrice stripped")
    void p0_purchaseOrderItem_getters_noNPE_afterStrip() {
        asUser(10L, false);

        PurchaseOrderItem item = new PurchaseOrderItem();
        item.setId(1L);
        item.setMaterialName("青花椒");
        item.setQuantity(new BigDecimal("100.0000"));
        item.setUnitPrice(new BigDecimal("123.45"));
        item.setTaxRate(new BigDecimal("13.00"));

        run(item);

        assertNull(item.getUnitPrice());
        assertNull(item.getTaxRate());

        assertDoesNotThrow(() -> item.getLineAmount());
        assertDoesNotThrow(() -> item.getLineAmountWithTax());
        assertNull(item.getLineAmount());
        assertNull(item.getLineAmountWithTax());

        // Non-price getPendingQuantity also defended against null quantity
        item.setReceivedQuantity(new BigDecimal("10.0000"));
        assertDoesNotThrow(() -> item.getPendingQuantity());
        PriceSensitiveContext.clear();
    }

    @Test
    @DisplayName("P0: MaterialBatch.getTotalPrice()/getTotalValue() return null after unitPrice stripped")
    void p0_materialBatch_getters_noNPE_afterStrip() {
        asUser(10L, false);

        MaterialBatch batch = new MaterialBatch();
        batch.setId("batch-1");
        batch.setBatchNumber("BATCH-001");
        batch.setUnitPrice(new BigDecimal("88.88"));
        batch.setReceiptQuantity(new BigDecimal("10.00"));

        run(batch);

        assertNull(batch.getUnitPrice());
        assertDoesNotThrow(() -> batch.getTotalPrice());
        assertDoesNotThrow(() -> batch.getTotalValue());
        assertNull(batch.getTotalPrice());
        assertNull(batch.getTotalValue());
        PriceSensitiveContext.clear();
    }

    @Test
    @DisplayName("P0: PageResponse with SalesOrders + items — full E2E NPE-free path")
    void p0_pageResponse_salesOrders_noNPE() {
        asUser(10L, false);

        // Build a SalesOrder with items, like prod /sales/orders response
        SalesOrder so = new SalesOrder();
        so.setId("so-prod-1");
        so.setOrderNumber("SO-PROD-001");
        so.setTotalAmount(new BigDecimal("5000.00"));
        so.setDiscountAmount(new BigDecimal("0.00"));
        so.setTaxAmount(new BigDecimal("0.00"));

        SalesOrderItem item = new SalesOrderItem();
        item.setId(1L);
        item.setProductName("青花椒成品");
        item.setQuantity(new BigDecimal("50.0000"));
        item.setUnitPrice(new BigDecimal("100.0000"));
        item.setCostUnitPrice(new BigDecimal("80.0000"));
        so.setItems(Arrays.asList(item));

        PageResponse<SalesOrder> page = new PageResponse<>();
        page.setContent(Arrays.asList(so));
        page.setTotalElements(1L);

        ApiResponse<PageResponse<SalesOrder>> response = ApiResponse.success("ok", page);

        // Run the full advice — including ThreadLocal setup
        run(response);

        SalesOrder stripped = response.getData().getContent().get(0);
        assertNull(stripped.getTotalAmount(), "totalAmount stripped");
        assertNull(stripped.getItems().get(0).getUnitPrice(), "item unitPrice stripped");

        // Critical: computed getters must NOT NPE — this is the exact prod regression scenario
        assertDoesNotThrow(() -> stripped.getPayableAmount(),
                "SalesOrder.getPayableAmount() must not NPE in PageResponse path (PROD regression)");
        assertDoesNotThrow(() -> stripped.getItems().get(0).getLineAmount());
        assertDoesNotThrow(() -> stripped.getItems().get(0).getCostTotal());

        assertNull(stripped.getPayableAmount());
        PriceSensitiveContext.clear();
    }

    @Test
    @DisplayName("P0: admin role — getter returns correct value (not null), ThreadLocal not set")
    void p0_adminRole_getters_returnRealValue() {
        asUser(1L, true);  // can view prices

        SalesOrder so = new SalesOrder();
        so.setTotalAmount(new BigDecimal("5000.00"));
        so.setDiscountAmount(new BigDecimal("100.00"));
        so.setTaxAmount(new BigDecimal("650.00"));

        run(so);

        // Values preserved (no strip)
        assertEquals(new BigDecimal("5000.00"), so.getTotalAmount());
        // Computed getter returns real value: 5000 - 100 + 650 = 5550
        assertEquals(new BigDecimal("5550.00"), so.getPayableAmount());

        // ThreadLocal NOT set for admin
        assertFalse(PriceSensitiveContext.shouldHide("procurement:price:view"),
                "ThreadLocal NOT set when user has permission (short-circuit branch)");
    }

    @Test
    @DisplayName("sales role: finance-upload masking must not hide @PriceSensitive invoice fields")
    void salesManager_priceVisible_financeMasked_threadLocalNotSet() {
        asUser(2L, true, false);

        InvoiceRecord record = new InvoiceRecord();
        record.setAmount(new BigDecimal("2500.00"));
        record.setTaxAmount(new BigDecimal("225.00"));
        record.setTotalAmount(new BigDecimal("2725.00"));
        record.setTaxBreakdown(List.of(
                new InvoiceRecord.TaxBreakdownEntry(
                        new BigDecimal("9.00"),
                        new BigDecimal("2500.00"),
                        new BigDecimal("225.00"),
                        1)
        ));

        run(record);

        assertEquals(new BigDecimal("2500.00"), record.getAmount());
        assertEquals(new BigDecimal("225.00"), record.getTaxBreakdown().get(0).getTaxAmount());
        assertFalse(PriceSensitiveContext.shouldHide("procurement:price:view"),
                "ThreadLocal must not hide price fields when only finance-upload masking applies");
    }

    @Test
    @DisplayName("P0: PriceSensitiveContext lifecycle - hide/clear works correctly")
    void p0_threadLocal_lifecycle() {
        // Initially clean
        assertFalse(PriceSensitiveContext.shouldHide("procurement:price:view"));

        // hide() sets flag
        PriceSensitiveContext.hide();
        assertTrue(PriceSensitiveContext.shouldHide("procurement:price:view"));
        assertTrue(PriceSensitiveContext.shouldHide("any:other:permission"),
                "hide flag is global for current iteration (single-flag impl)");

        // clear() removes flag
        PriceSensitiveContext.clear();
        assertFalse(PriceSensitiveContext.shouldHide("procurement:price:view"));
    }

    // ═══════════════════════════════════════════════════════════════════════
    // BUG-6 follow-up 2026-05-12 — Sister-sweep coverage:
    //   8 HIGH persisted price fields + InternalTransfer scope.
    //   Reference: docs/qa-audits/2026-05-12-bug-6-price-sensitive-sister-sweep.md
    //   PR #443 covered: 13 PR #423 baseline fields + 7 computed getters + @Target METHOD.
    //   This sweep adds: SalesOrder shipping/extra/cost/profit/invoiced/paid
    //                  + ExtraFeeItem.amount + InternalTransfer.totalAmount + InternalTransferItem.unitPrice.
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("BUG-6: mgr role — SalesOrder.shippingFee / actualShippedAmount stripped")
    void bug6_salesOrder_shippingFields_stripped() {
        asUser(10L, false);

        SalesOrder so = new SalesOrder();
        so.setId("so-bug6-1");
        so.setOrderNumber("SO-BUG6-001");
        so.setShippingFee(new BigDecimal("250.00"));
        so.setActualShippedAmount(new BigDecimal("5250.00"));

        run(so);

        assertNull(so.getShippingFee(), "shippingFee stripped (运费)");
        assertNull(so.getActualShippedAmount(), "actualShippedAmount stripped (实际发货金额)");
        // Non-price preserved
        assertEquals("SO-BUG6-001", so.getOrderNumber());
    }

    @Test
    @DisplayName("BUG-6: mgr role — SalesOrder.estimatedCost / estimatedProfit stripped (internal financial)")
    void bug6_salesOrder_estimatedFinancials_stripped() {
        asUser(10L, false);

        SalesOrder so = new SalesOrder();
        so.setId("so-bug6-2");
        so.setOrderNumber("SO-BUG6-002");
        so.setEstimatedCost(new BigDecimal("3800.50"));
        so.setEstimatedProfit(new BigDecimal("1199.50"));

        run(so);

        assertNull(so.getEstimatedCost(), "estimatedCost stripped (预估BOM成本 - 内部财务)");
        assertNull(so.getEstimatedProfit(), "estimatedProfit stripped (预估利润 - 内部财务)");
    }

    @Test
    @DisplayName("BUG-6: mgr role — SalesOrder.invoicedAmount / paidAmount stripped (回款数据)")
    void bug6_salesOrder_paymentFields_stripped() {
        asUser(10L, false);

        SalesOrder so = new SalesOrder();
        so.setId("so-bug6-3");
        so.setOrderNumber("SO-BUG6-003");
        so.setInvoicedAmount(new BigDecimal("4500.00"));
        so.setPaidAmount(new BigDecimal("3000.00"));

        run(so);

        assertNull(so.getInvoicedAmount(), "invoicedAmount stripped (已开票金额)");
        assertNull(so.getPaidAmount(), "paidAmount stripped (已收款金额)");
    }

    @Test
    @DisplayName("BUG-6: mgr role — SalesOrder.extraFees list ExtraFeeItem.amount stripped per item")
    void bug6_salesOrder_extraFees_amountStripped() {
        asUser(10L, false);

        SalesOrder so = new SalesOrder();
        so.setId("so-bug6-4");
        so.setOrderNumber("SO-BUG6-004");

        ExtraFeeItem fee1 = new ExtraFeeItem("装卸费", new BigDecimal("100.00"), "搬运费用");
        ExtraFeeItem fee2 = new ExtraFeeItem("包装费", new BigDecimal("50.00"), null);
        ExtraFeeItem fee3 = new ExtraFeeItem("加急费", new BigDecimal("200.00"), "次日达");
        so.setExtraFees(Arrays.asList(fee1, fee2, fee3));

        run(so);

        // Each ExtraFeeItem.amount stripped recursively
        assertEquals(3, so.getExtraFees().size(), "list size preserved");
        assertNull(so.getExtraFees().get(0).getAmount(), "extraFees[0].amount stripped (装卸费)");
        assertNull(so.getExtraFees().get(1).getAmount(), "extraFees[1].amount stripped (包装费)");
        assertNull(so.getExtraFees().get(2).getAmount(), "extraFees[2].amount stripped (加急费)");
        // Non-price preserved per item
        assertEquals("装卸费", so.getExtraFees().get(0).getName());
        assertEquals("包装费", so.getExtraFees().get(1).getName());
        assertEquals("搬运费用", so.getExtraFees().get(0).getRemark());
    }

    @Test
    @DisplayName("BUG-6: mgr role — InternalTransfer.totalAmount stripped")
    void bug6_internalTransfer_totalAmount_stripped() {
        asUser(10L, false);

        InternalTransfer transfer = new InternalTransfer();
        transfer.setId("trf-bug6-1");
        transfer.setTransferNumber("TRF-BUG6-001");
        transfer.setSourceFactoryId("F006");
        transfer.setTargetFactoryId("F006");
        transfer.setTotalAmount(new BigDecimal("8888.00"));

        run(transfer);

        assertNull(transfer.getTotalAmount(), "InternalTransfer.totalAmount stripped");
        // Non-price preserved
        assertEquals("TRF-BUG6-001", transfer.getTransferNumber());
        assertEquals("F006", transfer.getSourceFactoryId());
    }

    @Test
    @DisplayName("BUG-6: mgr role — InternalTransferItem.unitPrice stripped (nested in InternalTransfer)")
    void bug6_internalTransferItem_unitPrice_stripped() {
        asUser(10L, false);

        InternalTransfer transfer = new InternalTransfer();
        transfer.setId("trf-bug6-2");
        transfer.setTransferNumber("TRF-BUG6-002");
        transfer.setSourceFactoryId("F006");
        transfer.setTargetFactoryId("F006");
        transfer.setTotalAmount(new BigDecimal("500.00"));

        InternalTransferItem item = new InternalTransferItem();
        item.setId(1L);
        item.setTransferId("trf-bug6-2");
        item.setItemName("冻猪蹄");
        item.setQuantity(new BigDecimal("50.0000"));
        item.setUnitPrice(new BigDecimal("10.00"));
        item.setUnit("kg");
        transfer.setItems(java.util.Arrays.asList(item));

        run(transfer);

        assertNull(transfer.getTotalAmount(), "parent totalAmount stripped");
        assertNull(transfer.getItems().get(0).getUnitPrice(),
                "InternalTransferItem.unitPrice stripped (调拨单价)");
        // Computed getter from PR #443 also returns null (no NPE)
        assertDoesNotThrow(() -> transfer.getItems().get(0).getLineAmount());
        assertNull(transfer.getItems().get(0).getLineAmount(),
                "InternalTransferItem.getLineAmount() returns null when unitPrice stripped");
        // Non-price preserved
        assertEquals("冻猪蹄", transfer.getItems().get(0).getItemName());
        assertEquals(new BigDecimal("50.0000"), transfer.getItems().get(0).getQuantity());
    }

    @Test
    @DisplayName("BUG-6: admin role — all 8 HIGH fields + InternalTransfer values preserved (regression)")
    void bug6_adminRole_allFieldsPreserved() {
        asUser(1L, true);  // can view prices

        SalesOrder so = new SalesOrder();
        so.setShippingFee(new BigDecimal("250.00"));
        so.setActualShippedAmount(new BigDecimal("5250.00"));
        so.setEstimatedCost(new BigDecimal("3800.50"));
        so.setEstimatedProfit(new BigDecimal("1199.50"));
        so.setInvoicedAmount(new BigDecimal("4500.00"));
        so.setPaidAmount(new BigDecimal("3000.00"));
        so.setExtraFees(java.util.Arrays.asList(
                new ExtraFeeItem("装卸费", new BigDecimal("100.00"), null)));

        run(so);

        assertEquals(new BigDecimal("250.00"), so.getShippingFee());
        assertEquals(new BigDecimal("5250.00"), so.getActualShippedAmount());
        assertEquals(new BigDecimal("3800.50"), so.getEstimatedCost());
        assertEquals(new BigDecimal("1199.50"), so.getEstimatedProfit());
        assertEquals(new BigDecimal("4500.00"), so.getInvoicedAmount());
        assertEquals(new BigDecimal("3000.00"), so.getPaidAmount());
        assertEquals(new BigDecimal("100.00"), so.getExtraFees().get(0).getAmount(),
                "ExtraFeeItem.amount preserved for admin");

        InternalTransfer transfer = new InternalTransfer();
        transfer.setTotalAmount(new BigDecimal("8888.00"));
        InternalTransferItem item = new InternalTransferItem();
        item.setUnitPrice(new BigDecimal("10.00"));
        item.setQuantity(new BigDecimal("50.0000"));
        transfer.setItems(java.util.Arrays.asList(item));
        run(transfer);
        assertEquals(new BigDecimal("8888.00"), transfer.getTotalAmount());
        assertEquals(new BigDecimal("10.00"), transfer.getItems().get(0).getUnitPrice());
    }

    // ═══════════════════════════════════════════════════════════════════════
    // PR #443 follow-up — F3 (MaterialBatch.getTotalCost annotation)
    //                     F5 (permissionService exception → fail-CLOSED)
    // 2026-05-12
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("F3: MaterialBatch.getTotalCost() carries @PriceSensitive after PR #443 follow-up")
    void f3_materialBatch_getTotalCost_annotationPresent() throws Exception {
        java.lang.reflect.Method getter = MaterialBatch.class.getMethod("getTotalCost");
        assertNotNull(getter.getAnnotation(PriceSensitive.class),
                "getTotalCost must carry @PriceSensitive so Jackson modifier short-circuits "
                        + "(defense-in-depth, not relying solely on getTotalPrice's null guard)");
    }

    @Test
    @DisplayName("F3: MaterialBatch.getTotalCost() returns null safely after unitPrice stripped")
    void f3_materialBatch_getTotalCost_returnsNullAfterStrip() {
        asUser(10L, false);

        MaterialBatch batch = new MaterialBatch();
        batch.setId("batch-1");
        batch.setBatchNumber("BATCH-001");
        batch.setUnitPrice(new BigDecimal("88.88"));
        batch.setReceiptQuantity(new BigDecimal("10.00"));

        // Precondition — getTotalCost computes a real value before strip.
        // BigDecimal multiplication scale = sum of operand scales (2 + 2 = 4).
        assertEquals(0, batch.getTotalCost().compareTo(new BigDecimal("888.80")),
                "precondition: getTotalCost ≈ unitPrice × receiptQuantity (88.88 × 10.00)");

        run(batch);

        assertNull(batch.getUnitPrice(), "unitPrice stripped");
        assertDoesNotThrow(() -> batch.getTotalCost(),
                "getTotalCost must not NPE after unitPrice stripped");
        assertNull(batch.getTotalCost(),
                "getTotalCost returns null via getTotalPrice's defensive guard");
    }

    @Test
    @DisplayName("F5: permissionService.hasPermission throws → fail-CLOSED (prices stripped)")
    void f5_permissionService_throws_failClosed_strips() {
        // Stub: user resolves OK, but permission check throws (DB outage / cache misconfig).
        Long userId = 42L;
        httpRequest.setAttribute("userId", userId);
        User user = new User();
        user.setId(userId);
        lenient().when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        lenient().when(permissionService.hasPermission(any(User.class), any(String.class)))
                .thenThrow(new RuntimeException("simulated PermissionService failure (DB outage)"));

        PurchaseOrder order = samplePurchaseOrder();

        // Critical: advice MUST NOT propagate the exception (would 500 every request).
        assertDoesNotThrow(() -> run(order),
                "advice must not propagate permission-check exception (F5)");

        // Fail-CLOSED: prices stripped even though we couldn't confirm permission denial.
        assertNull(order.getTotalAmount(), "F5 fail-CLOSED: totalAmount stripped");
        assertNull(order.getTaxAmount(), "F5 fail-CLOSED: taxAmount stripped");
        assertNull(order.getItems().get(0).getUnitPrice(),
                "F5 fail-CLOSED: nested item.unitPrice stripped");
    }

    @Test
    @DisplayName("F5: permissionService throws + hide flag set so Jackson layer also short-circuits")
    void f5_permissionService_throws_hideFlagAlsoSet() {
        Long userId = 43L;
        httpRequest.setAttribute("userId", userId);
        User user = new User();
        user.setId(userId);
        lenient().when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        lenient().when(permissionService.hasPermission(any(User.class), any(String.class)))
                .thenThrow(new RuntimeException("boom"));

        SalesOrder so = new SalesOrder();
        so.setTotalAmount(new BigDecimal("5000.00"));

        run(so);

        // Belt: field-strip path ran
        assertNull(so.getTotalAmount(), "field stripped on fail-CLOSED path");
        // Suspenders: ThreadLocal hide flag set, so Jackson layer ALSO short-circuits
        // any @PriceSensitive methods downstream.
        assertTrue(PriceSensitiveContext.shouldHide("procurement:price:view"),
                "ThreadLocal hide flag set even when permission check throws (defense-in-depth)");
    }

    // ═══════════════════════════════════════════════════════════════════════
    // PR #547 / #615(A) — Finance gate corrected to finance:read_write (2026-06-09)
    //
    // Prod permission facts (verified via platform_role_permissions + hardcoded matrix):
    //   sales_manager     finance=r      → canViewFinance=FALSE  → finance P&L MASKED   ✓
    //   finance_manager   finance=rw     → canViewFinance=TRUE   → finance P&L visible   ✓
    //   restaurant_owner  finance=rw (hardcoded fallback, no DB row) → visible           ✓
    //   operator          finance=-      → canViewFinance=FALSE  → finance P&L MASKED   ✓
    //   warehouse_manager finance=-      → canViewFinance=FALSE  → finance P&L MASKED   ✓
    //
    // Original #615 bug: gate was finance:read → sales_manager (finance=r) passed the
    // gate → finance P&L NOT masked. Fix: raise gate to finance:read_write.
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("#615A: sales_manager (price:view=T, finance=r→rw-gate=F) — @PriceSensitive fields NOT stripped (sees procurement prices)")
    void pr615a_salesManager_priceSensitiveFields_notStripped() {
        // sales_manager: procurement:price:view=T, finance:read_write=F (has only finance:read)
        asUser(50L, true, false);

        PurchaseOrder order = samplePurchaseOrder();
        run(order);

        // @PriceSensitive fields MUST be preserved for sales_manager (price gate still passes)
        assertEquals(new BigDecimal("12345.67"), order.getTotalAmount(),
                "sales_manager should see totalAmount (procurement price, price:view=T)");
        assertEquals(new BigDecimal("123.4567"), order.getItems().get(0).getUnitPrice(),
                "sales_manager should see item unitPrice (procurement price)");
        // Non-price fields also preserved
        assertEquals("PO-2026-001", order.getOrderNumber());
    }

    @Test
    @DisplayName("#615A: sales_manager (finance=r → rw-gate=FALSE) — finance upload Map columns ARE stripped")
    void pr615a_salesManager_financeUploadColumns_stripped() {
        // sales_manager: canViewPrice=T (price:view whitelist), canViewFinance=F (finance=r, NOT rw)
        // This is the #615 bug scenario: with the old finance:read gate sales_manager passed →
        // finance columns were NOT masked. With finance:read_write gate they ARE masked.
        asUser(50L, true, false);

        java.util.Map<String, Object> uploadRow = new java.util.LinkedHashMap<>();
        uploadRow.put("产品名称", "叮咚猪蹄");   // non-finance — preserved
        uploadRow.put("数量", 200);               // non-finance — preserved
        uploadRow.put("金额", 32000.00);           // finance P&L column → MUST be masked
        uploadRow.put("工资", 8500.00);            // finance P&L column → MUST be masked
        uploadRow.put("租金", 12000.00);           // finance P&L column → MUST be masked
        uploadRow.put("利润", 5000.00);            // finance P&L column → MUST be masked
        uploadRow.put("营收", 45000.00);           // finance P&L column → MUST be masked

        run(uploadRow);

        // Finance P&L columns stripped (sales_manager has finance=r, gate requires rw)
        assertNull(uploadRow.get("金额"),  "#615A: 金额 (revenue amount) masked for sales_manager");
        assertNull(uploadRow.get("工资"),  "#615A: 工资 (salary) masked for sales_manager");
        assertNull(uploadRow.get("租金"),  "#615A: 租金 (rent) masked for sales_manager");
        assertNull(uploadRow.get("利润"),  "#615A: 利润 (profit) masked for sales_manager");
        assertNull(uploadRow.get("营收"),  "#615A: 营收 (revenue) masked for sales_manager");

        // Non-finance columns preserved
        assertEquals("叮咚猪蹄", uploadRow.get("产品名称"), "product name preserved");
        assertEquals(200, uploadRow.get("数量"), "quantity preserved");
    }

    @Test
    @DisplayName("#615A: finance_manager (price=T, finance:rw=T) — finance upload columns NOT stripped (full access)")
    void pr615a_financeManager_financeUploadColumns_visible() {
        // finance_manager: canViewPrice=T, canViewFinance=T (finance=rw → gate passes)
        asUser(60L, true, true);

        java.util.Map<String, Object> uploadRow = new java.util.LinkedHashMap<>();
        uploadRow.put("金额", 32000.00);
        uploadRow.put("工资", 8500.00);
        uploadRow.put("利润", 5000.00);

        run(uploadRow);

        // Full access — finance columns preserved
        assertEquals(32000.00, uploadRow.get("金额"), "finance_manager sees 金额 (full access, finance=rw)");
        assertEquals(8500.00,  uploadRow.get("工资"), "finance_manager sees 工资 (full access)");
        assertEquals(5000.00,  uploadRow.get("利润"), "finance_manager sees 利润 (full access)");
    }

    @Test
    @DisplayName("#615A: restaurant_owner (finance=rw via hardcoded matrix fallback) — finance P&L visible")
    void pr615a_restaurantOwner_financeVisible() {
        // restaurant_owner has NO row in platform_role_permissions (prod verified 2026-06-09).
        // Falls back to hardcoded PERMISSION_MATRIX: finance="read_write".
        // Gate is finance:read_write → checkAction("read_write","read_write") = TRUE → visible.
        // canViewPrice=T (in PRICE_VIEW_ROLES), canViewFinance=T (finance=rw)
        asUser(80L, true, true);

        java.util.Map<String, Object> uploadRow = new java.util.LinkedHashMap<>();
        uploadRow.put("金额", 78700000.00);  // qhj_prod total revenue scale
        uploadRow.put("工资", 15000.00);
        uploadRow.put("利润", 12000000.00);

        run(uploadRow);

        // Owner MUST see finance P&L (his own business data)
        assertEquals(78700000.00, uploadRow.get("金额"),  "restaurant_owner sees 金额 (finance=rw via matrix fallback)");
        assertEquals(15000.00,    uploadRow.get("工资"),  "restaurant_owner sees 工资");
        assertEquals(12000000.00, uploadRow.get("利润"),  "restaurant_owner sees 利润");
    }

    @Test
    @DisplayName("#615A: operator (price=F, finance=F) — both @PriceSensitive AND finance columns stripped")
    void pr615a_operator_allStripped() {
        // operator: no price:view, no finance:rw → both gates fail
        asUser(70L, false, false);

        PurchaseOrder order = samplePurchaseOrder();

        java.util.Map<String, Object> uploadRow = new java.util.LinkedHashMap<>();
        uploadRow.put("数量", 200);
        uploadRow.put("金额", 32000.00);

        run(order);
        run(uploadRow);

        // @PriceSensitive stripped
        assertNull(order.getTotalAmount(), "operator: totalAmount stripped");
        assertNull(order.getItems().get(0).getUnitPrice(), "operator: unitPrice stripped");
        // Finance columns also stripped
        assertNull(uploadRow.get("金额"), "operator: 金额 stripped");
        // Non-price preserved
        assertEquals(200, uploadRow.get("数量"), "quantity preserved");
    }

    @Test
    @DisplayName("#615A: warehouse_manager (price=F, finance=-) — finance columns stripped")
    void pr615a_warehouseManager_financeStripped() {
        // warehouse_manager: finance=- (no finance access at all) → canViewFinance=FALSE
        asUser(90L, false, false);

        java.util.Map<String, Object> uploadRow = new java.util.LinkedHashMap<>();
        uploadRow.put("数量", 500);
        uploadRow.put("金额", 55000.00);
        uploadRow.put("成本", 40000.00);

        run(uploadRow);

        assertNull(uploadRow.get("金额"), "warehouse_manager: 金额 stripped (finance=-)");
        assertNull(uploadRow.get("成本"), "warehouse_manager: 成本 stripped (finance=-)");
        assertEquals(500, uploadRow.get("数量"), "quantity preserved");
    }

    @Test
    @DisplayName("#615A: FINANCE_READ_PERMISSION constant is 'finance:read_write' — gate requires rw not just r")
    void pr615a_financeReadWritePermissionConstant() {
        assertEquals("finance:read_write", PriceFieldResponseAdvice.FINANCE_READ_PERMISSION,
                "FINANCE_READ_PERMISSION must be 'finance:read_write' (not 'finance:read') — " +
                "sales_manager has finance=r and must NOT pass this gate (#615 fix)");
    }
}
