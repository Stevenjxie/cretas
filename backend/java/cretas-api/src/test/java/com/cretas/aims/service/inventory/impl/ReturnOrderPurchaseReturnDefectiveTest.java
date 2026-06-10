package com.cretas.aims.service.inventory.impl;

import com.cretas.aims.entity.MaterialBatch;
import com.cretas.aims.entity.enums.InboundType;
import com.cretas.aims.entity.enums.MaterialBatchStatus;
import com.cretas.aims.entity.enums.ReturnOrderStatus;
import com.cretas.aims.entity.enums.ReturnType;
import com.cretas.aims.entity.inventory.ReturnOrder;
import com.cretas.aims.entity.inventory.ReturnOrderItem;
import com.cretas.aims.repository.MaterialBatchRepository;
import com.cretas.aims.repository.inventory.FinishedGoodsBatchRepository;
import com.cretas.aims.repository.inventory.ReturnOrderItemRepository;
import com.cretas.aims.repository.inventory.ReturnOrderRepository;
import com.cretas.aims.service.factory.WarehouseResolver;
import com.cretas.aims.service.finance.ArApService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Issue #795 — PURCHASE_RETURN with-goods 不良品入库.
 *
 * <p>Mirrors SALES_RETURN with-goods test scope (Phase C, issue #571). Verifies:
 * <ol>
 *   <li>PURCHASE_RETURN + withGoods=true + FINANCE_APPROVED → completeReturnOrder creates
 *       a {@link MaterialBatch} with status=DEFECTIVE for each item that has
 *       materialTypeId + quantity&gt;0.</li>
 *   <li>PURCHASE_RETURN + withGoods=false → no MaterialBatch created (refund-only
 *       path; AR/AP 冲减 already happened at approve, completion is a no-op for
 *       inventory).</li>
 *   <li>Rows missing materialTypeId or with zero quantity are skipped (no batch
 *       created) but other valid rows still create batches (defensive partial-write).</li>
 *   <li>SALES_RETURN regression — PURCHASE_RETURN handler does NOT fire for
 *       SALES_RETURN orders (mutual exclusion via else-if).</li>
 *   <li>materialBatchRepository null (legacy DI gap) → completion still succeeds,
 *       status flips to COMPLETED, only inventory creation is skipped.</li>
 * </ol>
 *
 * <p>Test pattern mirrors {@code SalesOrderFulfillmentWarehouseTest} — 4-arg
 * constructor with null + reflective injection of optional @Autowired fields
 * ({@code materialBatchRepository}, {@code warehouseResolver}).
 *
 * @since 2026-05-17 issue #795
 */
@DisplayName("Issue #795: PURCHASE_RETURN with-goods 不良品入库 (MaterialBatch DEFECTIVE)")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ReturnOrderPurchaseReturnDefectiveTest {

    @Mock private ReturnOrderRepository returnOrderRepository;
    @Mock private ReturnOrderItemRepository returnOrderItemRepository;
    @Mock private ArApService arApService;
    @Mock private ApplicationEventPublisher applicationEventPublisher;
    @Mock private MaterialBatchRepository materialBatchRepository;
    @Mock private WarehouseResolver warehouseResolver;
    @Mock private FinishedGoodsBatchRepository finishedGoodsBatchRepository;

    private ReturnOrderServiceImpl service;

    private static final String FACTORY = "F001";
    private static final String WH_LOG = "wh-log-f001";
    private static final String MATERIAL_TYPE = "MT-001";
    private static final Long APPROVER = 22L;

    @BeforeEach
    void setUp() {
        service = new ReturnOrderServiceImpl(
                returnOrderRepository,
                returnOrderItemRepository,
                arApService,
                applicationEventPublisher);
        ReflectionTestUtils.setField(service, "materialBatchRepository", materialBatchRepository);
        ReflectionTestUtils.setField(service, "warehouseResolver", warehouseResolver);
        ReflectionTestUtils.setField(service, "finishedGoodsBatchRepository", finishedGoodsBatchRepository);
        when(warehouseResolver.resolveLogisticsId(FACTORY)).thenReturn(WH_LOG);
    }

    // ===== helpers =====

    private ReturnOrder buildOrder(ReturnType type, boolean withGoods,
                                    ReturnOrderStatus status, List<ReturnOrderItem> items) {
        ReturnOrder o = new ReturnOrder();
        o.setId("RO-" + System.nanoTime());
        o.setFactoryId(FACTORY);
        o.setReturnNumber("RT-PUR-20260517-0001");
        o.setReturnType(type);
        o.setWithGoods(withGoods);
        o.setStatus(status);
        o.setCounterpartyId("SUP-001");
        o.setTotalAmount(new BigDecimal("500.00"));
        o.setReason("质量不合格");
        o.setApprovedBy(APPROVER);
        o.setItems(items);
        return o;
    }

    private ReturnOrderItem item(String materialTypeId, BigDecimal qty, String reason) {
        ReturnOrderItem it = new ReturnOrderItem();
        it.setMaterialTypeId(materialTypeId);
        it.setQuantity(qty);
        it.setUnitPrice(new BigDecimal("50.00"));
        it.setReason(reason);
        return it;
    }

    private void stubFindAndSave(ReturnOrder order) {
        when(returnOrderRepository.findById(order.getId())).thenReturn(Optional.of(order));
        when(returnOrderRepository.save(order)).thenReturn(order);
    }

    // ===== tests =====

    @Test
    @DisplayName("PURCHASE_RETURN + withGoods=true: 创建 MaterialBatch status=DEFECTIVE in WH-LOG")
    void purchaseReturnWithGoods_createsDefectiveMaterialBatch() {
        List<ReturnOrderItem> items = new ArrayList<>();
        items.add(item(MATERIAL_TYPE, new BigDecimal("10.0000"), "包装破损"));
        items.add(item("MT-002", new BigDecimal("5.0000"), null));  // null reason → fall back to order.reason
        ReturnOrder order = buildOrder(ReturnType.PURCHASE_RETURN, true, ReturnOrderStatus.FINANCE_APPROVED, items);
        stubFindAndSave(order);

        ReturnOrder result = service.completeReturnOrder(FACTORY, order.getId());

        assertThat(result.getStatus()).isEqualTo(ReturnOrderStatus.COMPLETED);

        ArgumentCaptor<MaterialBatch> captor = ArgumentCaptor.forClass(MaterialBatch.class);
        verify(materialBatchRepository, times(2)).save(captor.capture());
        List<MaterialBatch> saved = captor.getAllValues();

        assertThat(saved).hasSize(2);
        // Both batches: status=DEFECTIVE, warehouse=WH-LOG, inboundType=SUPPLIER_RETURN
        assertThat(saved).allSatisfy(b -> {
            assertThat(b.getStatus()).isEqualTo(MaterialBatchStatus.DEFECTIVE);
            assertThat(b.getWarehouseId()).isEqualTo(WH_LOG);
            assertThat(b.getFactoryId()).isEqualTo(FACTORY);
            assertThat(b.getInboundType()).isEqualTo(InboundType.SUPPLIER_RETURN);
            assertThat(b.getSourceDocType()).isEqualTo("PURCHASE_RETURN");
            assertThat(b.getSourceDocId()).isEqualTo(order.getId());
            assertThat(b.getCreatedBy()).isEqualTo(APPROVER);
            assertThat(b.getUsedQuantity()).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(b.getReservedQuantity()).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(b.getBatchNumber()).startsWith("RTN-RT-PUR-");
        });
        // First item carries item-level reason
        assertThat(saved.get(0).getNotes()).contains("包装破损").contains(order.getReturnNumber());
        // Second item null reason → falls back to order-level reason
        assertThat(saved.get(1).getNotes()).contains("质量不合格");
        // Material type IDs preserved
        assertThat(saved.get(0).getMaterialTypeId()).isEqualTo(MATERIAL_TYPE);
        assertThat(saved.get(1).getMaterialTypeId()).isEqualTo("MT-002");
        // Quantities preserved
        assertThat(saved.get(0).getReceiptQuantity()).isEqualByComparingTo(new BigDecimal("10.0000"));
        assertThat(saved.get(1).getReceiptQuantity()).isEqualByComparingTo(new BigDecimal("5.0000"));

        // AP 冲减 (deferred from approve) triggered exactly once
        verify(arApService, times(1)).recordAdjustment(
                eqStr(FACTORY),
                org.mockito.ArgumentMatchers.eq(com.cretas.aims.entity.enums.CounterpartyType.SUPPLIER),
                org.mockito.ArgumentMatchers.eq("SUP-001"),
                org.mockito.ArgumentMatchers.any(BigDecimal.class),
                org.mockito.ArgumentMatchers.eq(APPROVER),
                org.mockito.ArgumentMatchers.contains("采购退货冲减(实物已入库)"));
    }

    @Test
    @DisplayName("PURCHASE_RETURN + withGoods=false: 不创建 MaterialBatch (refund-only path)")
    void purchaseReturnWithoutGoods_noMaterialBatch() {
        List<ReturnOrderItem> items = List.of(item(MATERIAL_TYPE, new BigDecimal("10.0000"), "x"));
        ReturnOrder order = buildOrder(ReturnType.PURCHASE_RETURN, false, ReturnOrderStatus.FINANCE_APPROVED, items);
        stubFindAndSave(order);

        ReturnOrder result = service.completeReturnOrder(FACTORY, order.getId());

        assertThat(result.getStatus()).isEqualTo(ReturnOrderStatus.COMPLETED);
        // No-goods path — completion is no-op for AR/AP (already triggered at approve)
        // and zero inventory writes.
        verify(materialBatchRepository, never()).save(org.mockito.ArgumentMatchers.any());
        verify(arApService, never()).recordAdjustment(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(BigDecimal.class),
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    @DisplayName("PURCHASE_RETURN with-goods: skip 行缺 materialTypeId / 零数量, 但保留 valid rows")
    void purchaseReturnWithGoods_skipsInvalidItems_keepsValid() {
        List<ReturnOrderItem> items = new ArrayList<>();
        items.add(item(MATERIAL_TYPE, new BigDecimal("3.0000"), "valid"));   // valid → save
        items.add(item(null, new BigDecimal("99.0000"), "no material"));     // null materialTypeId → skip
        items.add(item("MT-009", BigDecimal.ZERO, "zero qty"));              // zero qty → skip
        items.add(item("MT-010", null, "null qty"));                          // null qty → skip
        items.add(item("MT-011", new BigDecimal("7.0000"), "valid 2"));      // valid → save
        ReturnOrder order = buildOrder(ReturnType.PURCHASE_RETURN, true, ReturnOrderStatus.FINANCE_APPROVED, items);
        stubFindAndSave(order);

        ReturnOrder result = service.completeReturnOrder(FACTORY, order.getId());

        assertThat(result.getStatus()).isEqualTo(ReturnOrderStatus.COMPLETED);
        ArgumentCaptor<MaterialBatch> captor = ArgumentCaptor.forClass(MaterialBatch.class);
        verify(materialBatchRepository, times(2)).save(captor.capture());
        // Saved batches correspond to row 1 (MT-001 qty 3) and row 5 (MT-011 qty 7).
        assertThat(captor.getAllValues())
                .extracting(MaterialBatch::getMaterialTypeId)
                .containsExactly(MATERIAL_TYPE, "MT-011");
        assertThat(captor.getAllValues())
                .extracting(MaterialBatch::getReceiptQuantity)
                .containsExactly(new BigDecimal("3.0000"), new BigDecimal("7.0000"));
    }

    @Test
    @DisplayName("SALES_RETURN regression: PURCHASE_RETURN 分支不触发 (不创建 MaterialBatch)")
    void salesReturnWithGoods_doesNotTriggerPurchaseBranch() {
        ReturnOrderItem it = new ReturnOrderItem();
        it.setProductTypeId("PT-001");  // SALES_RETURN uses productTypeId, not materialTypeId
        it.setQuantity(new BigDecimal("2.0000"));
        it.setUnitPrice(new BigDecimal("100.00"));
        List<ReturnOrderItem> items = List.of(it);
        ReturnOrder order = buildOrder(ReturnType.SALES_RETURN, true, ReturnOrderStatus.FINANCE_APPROVED, items);
        order.setCounterpartyId("CUST-001");  // SALES_RETURN counterparty is customer
        stubFindAndSave(order);

        ReturnOrder result = service.completeReturnOrder(FACTORY, order.getId());

        assertThat(result.getStatus()).isEqualTo(ReturnOrderStatus.COMPLETED);
        // MaterialBatchRepository must NOT be invoked for SALES_RETURN —
        // FinishedGoodsBatchRepository handles it (different code path).
        verify(materialBatchRepository, never()).save(org.mockito.ArgumentMatchers.any());
        verify(finishedGoodsBatchRepository, times(1)).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("materialBatchRepository null (legacy DI gap): status flip + AP 冲减 仍生效, 仅跳过库存创建")
    void materialBatchRepositoryNull_completionStillSucceeds() {
        ReflectionTestUtils.setField(service, "materialBatchRepository", null);
        List<ReturnOrderItem> items = List.of(item(MATERIAL_TYPE, new BigDecimal("4.0000"), "x"));
        ReturnOrder order = buildOrder(ReturnType.PURCHASE_RETURN, true, ReturnOrderStatus.FINANCE_APPROVED, items);
        stubFindAndSave(order);

        ReturnOrder result = service.completeReturnOrder(FACTORY, order.getId());

        assertThat(result.getStatus()).isEqualTo(ReturnOrderStatus.COMPLETED);
        // No NPE; status flip + AP 冲减 都已生效.
        verify(arApService, times(1)).recordAdjustment(
                eqStr(FACTORY),
                org.mockito.ArgumentMatchers.eq(com.cretas.aims.entity.enums.CounterpartyType.SUPPLIER),
                org.mockito.ArgumentMatchers.eq("SUP-001"),
                org.mockito.ArgumentMatchers.any(BigDecimal.class),
                org.mockito.ArgumentMatchers.eq(APPROVER),
                org.mockito.ArgumentMatchers.contains("采购退货冲减(实物已入库)"));
    }

    // Mockito eq() overload helper for String to avoid varargs clash with anyString().
    private static String eqStr(String s) {
        return org.mockito.ArgumentMatchers.eq(s);
    }
}
