package com.cretas.aims.service.inventory.impl;

import com.cretas.aims.entity.MaterialBatch;
import com.cretas.aims.entity.MaterialBatchAdjustment;
import com.cretas.aims.entity.enums.MaterialBatchStatus;
import com.cretas.aims.entity.enums.ReturnOrderStatus;
import com.cretas.aims.entity.enums.ReturnType;
import com.cretas.aims.entity.inventory.ReturnOrder;
import com.cretas.aims.entity.inventory.ReturnOrderItem;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.MaterialBatchAdjustmentRepository;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * BLOCKER 2 (取代 issue #795 反向逻辑): PURCHASE_RETURN with-goods 完成 = 从现有库存【扣减】
 * (货退回供应商, 物理离厂), 而非创建新 DEFECTIVE 批次 (那是 SALES_RETURN 加库存的语义)。
 *
 * <p>背景: 超收量在采购收货时已加进库存 (createMaterialBatchFromReceiveItem 用全部收货量),
 * 退货完成时若再创建一个新批次 = 账面双计 (130 + 30 = 160)。正确应扣回 30 → 100。
 *
 * <p>扣减镜像 {@code DisposalRecordService}: 增 usedQuantity (不减 receiptQuantity),
 * 写 {@link MaterialBatchAdjustment} 负数留痕。库存不足 → 409 阻断完成 (fail-loud, 不扣成负)。
 *
 * <p>Verifies:
 * <ol>
 *   <li>PURCHASE_RETURN + withGoods=true + FINANCE_APPROVED → 从 FEFO 可用批次扣减,
 *       净扣 = 退货量 (usedQuantity += qty), 写负数 MaterialBatchAdjustment, 不创建新批次。</li>
 *   <li>库存不足 → 409, 退货单不 COMPLETED (fail-loud)。</li>
 *   <li>跨批次 FEFO 扣减: 第一批扣空标 USED_UP, 余量从第二批扣。</li>
 *   <li>PURCHASE_RETURN + withGoods=false → 无库存动作 (refund-only)。</li>
 *   <li>SALES_RETURN regression: 仍走 FinishedGoodsBatch 加库存, 不碰 MaterialBatch 扣减。</li>
 * </ol>
 *
 * @since 2026-06-23 BLOCKER 2 (库存方向修复)
 */
@DisplayName("BLOCKER 2: PURCHASE_RETURN with-goods 库存扣减 (货退回供应商, 非加库存)")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ReturnOrderPurchaseReturnDefectiveTest {

    @Mock private ReturnOrderRepository returnOrderRepository;
    @Mock private ReturnOrderItemRepository returnOrderItemRepository;
    @Mock private ArApService arApService;
    @Mock private ApplicationEventPublisher applicationEventPublisher;
    @Mock private MaterialBatchRepository materialBatchRepository;
    @Mock private MaterialBatchAdjustmentRepository materialBatchAdjustmentRepository;
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
        ReflectionTestUtils.setField(service, "materialBatchAdjustmentRepository", materialBatchAdjustmentRepository);
        ReflectionTestUtils.setField(service, "warehouseResolver", warehouseResolver);
        ReflectionTestUtils.setField(service, "finishedGoodsBatchRepository", finishedGoodsBatchRepository);
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
        it.setItemName("猪大肠");
        it.setQuantity(qty);
        it.setUnitPrice(new BigDecimal("50.00"));
        it.setReason(reason);
        return it;
    }

    /** 构造一个可用批次 (receiptQuantity, usedQuantity=0, reservedQuantity=0 → currentQuantity=receipt)。 */
    private MaterialBatch availableBatch(String id, String materialTypeId, BigDecimal receiptQty) {
        MaterialBatch b = new MaterialBatch();
        b.setId(id);
        b.setFactoryId(FACTORY);
        b.setBatchNumber("MT-" + id);
        b.setMaterialTypeId(materialTypeId);
        b.setReceiptQuantity(receiptQty);
        b.setUsedQuantity(BigDecimal.ZERO);
        b.setReservedQuantity(BigDecimal.ZERO);
        b.setQuantityUnit("kg");
        b.setUnitPrice(new BigDecimal("10.00"));
        b.setWarehouseId(WH_LOG);
        b.setStatus(MaterialBatchStatus.AVAILABLE);
        return b;
    }

    private void stubFindAndSave(ReturnOrder order) {
        when(returnOrderRepository.findById(order.getId())).thenReturn(Optional.of(order));
        when(returnOrderRepository.save(order)).thenReturn(order);
    }

    // ===== tests =====

    @Test
    @DisplayName("PURCHASE_RETURN + withGoods=true: 从 FEFO 批次扣减退货量 (usedQuantity += qty), 写负数留痕, 不创建新批次")
    void purchaseReturnWithGoods_deductsFromExistingBatch_netDeduct() {
        // 收货时已入库 130 (含超收 30), 退货 30 → 净扣 30 → currentQuantity 130→100。
        MaterialBatch batch = availableBatch("B1", MATERIAL_TYPE, new BigDecimal("130.00"));
        when(materialBatchRepository.findAvailableBatchesFEFO(FACTORY, MATERIAL_TYPE))
                .thenReturn(new ArrayList<>(List.of(batch)));

        List<ReturnOrderItem> items = List.of(item(MATERIAL_TYPE, new BigDecimal("30.00"), "超收退回"));
        ReturnOrder order = buildOrder(ReturnType.PURCHASE_RETURN, true, ReturnOrderStatus.FINANCE_APPROVED, items);
        stubFindAndSave(order);

        ReturnOrder result = service.completeReturnOrder(FACTORY, order.getId());

        assertThat(result.getStatus()).isEqualTo(ReturnOrderStatus.COMPLETED);

        // 净扣 30: usedQuantity 0 → 30, currentQuantity 130 → 100。
        ArgumentCaptor<MaterialBatch> batchCaptor = ArgumentCaptor.forClass(MaterialBatch.class);
        verify(materialBatchRepository, times(1)).save(batchCaptor.capture());
        MaterialBatch savedBatch = batchCaptor.getValue();
        assertThat(savedBatch.getId()).isEqualTo("B1");
        assertThat(savedBatch.getUsedQuantity()).isEqualByComparingTo(new BigDecimal("30.00"));
        assertThat(savedBatch.getCurrentQuantity()).isEqualByComparingTo(new BigDecimal("100.00"));
        // 批次未扣空 → 仍 AVAILABLE。
        assertThat(savedBatch.getStatus()).isEqualTo(MaterialBatchStatus.AVAILABLE);

        // 写一条负数 MaterialBatchAdjustment 留痕。
        ArgumentCaptor<MaterialBatchAdjustment> adjCaptor = ArgumentCaptor.forClass(MaterialBatchAdjustment.class);
        verify(materialBatchAdjustmentRepository, times(1)).save(adjCaptor.capture());
        MaterialBatchAdjustment adj = adjCaptor.getValue();
        assertThat(adj.getAdjustmentType()).isEqualTo("PURCHASE_RETURN");
        assertThat(adj.getAdjustmentQuantity()).isEqualByComparingTo(new BigDecimal("-30.00"));
        assertThat(adj.getQuantityBefore()).isEqualByComparingTo(new BigDecimal("130.00"));
        assertThat(adj.getQuantityAfter()).isEqualByComparingTo(new BigDecimal("100.00"));
        assertThat(adj.getMaterialBatchId()).isEqualTo("B1");

        // AP 冲减 (deferred from approve) 触发一次。
        verify(arApService, times(1)).recordAdjustment(
                eqStr(FACTORY),
                org.mockito.ArgumentMatchers.eq(com.cretas.aims.entity.enums.CounterpartyType.SUPPLIER),
                org.mockito.ArgumentMatchers.eq("SUP-001"),
                org.mockito.ArgumentMatchers.any(BigDecimal.class),
                org.mockito.ArgumentMatchers.eq(APPROVER),
                org.mockito.ArgumentMatchers.contains("采购退货冲减(实物已入库)"));
    }

    @Test
    @DisplayName("BLOCKER 2: 库存不足 → 409, 退货单不 COMPLETED (fail-loud, 不扣成负)")
    void purchaseReturnWithGoods_insufficientStock_throws409_notCompleted() {
        // 可用仅 10, 退货要 30 → 不足。
        MaterialBatch batch = availableBatch("B1", MATERIAL_TYPE, new BigDecimal("10.00"));
        when(materialBatchRepository.findAvailableBatchesFEFO(FACTORY, MATERIAL_TYPE))
                .thenReturn(new ArrayList<>(List.of(batch)));

        List<ReturnOrderItem> items = List.of(item(MATERIAL_TYPE, new BigDecimal("30.00"), "超收退回"));
        ReturnOrder order = buildOrder(ReturnType.PURCHASE_RETURN, true, ReturnOrderStatus.FINANCE_APPROVED, items);
        stubFindAndSave(order);

        assertThatThrownBy(() -> service.completeReturnOrder(FACTORY, order.getId()))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getCode()).isEqualTo(409))
                .hasMessageContaining("不足");

        // 不扣库存, 不写留痕, 退货单状态保持 (未翻 COMPLETED)。
        verify(materialBatchRepository, never()).save(org.mockito.ArgumentMatchers.any());
        verify(materialBatchAdjustmentRepository, never()).save(org.mockito.ArgumentMatchers.any());
        assertThat(order.getStatus()).isEqualTo(ReturnOrderStatus.FINANCE_APPROVED);
    }

    @Test
    @DisplayName("BLOCKER 2: 跨批次 FEFO 扣减 — 第一批扣空标 USED_UP, 余量从第二批扣")
    void purchaseReturnWithGoods_deductsAcrossBatchesFEFO() {
        // 退 30: 批1 仅 20 (扣空→USED_UP), 批2 100 (扣 10)。
        MaterialBatch b1 = availableBatch("B1", MATERIAL_TYPE, new BigDecimal("20.00"));
        MaterialBatch b2 = availableBatch("B2", MATERIAL_TYPE, new BigDecimal("100.00"));
        when(materialBatchRepository.findAvailableBatchesFEFO(FACTORY, MATERIAL_TYPE))
                .thenReturn(new ArrayList<>(List.of(b1, b2)));

        List<ReturnOrderItem> items = List.of(item(MATERIAL_TYPE, new BigDecimal("30.00"), "超收退回"));
        ReturnOrder order = buildOrder(ReturnType.PURCHASE_RETURN, true, ReturnOrderStatus.FINANCE_APPROVED, items);
        stubFindAndSave(order);

        ReturnOrder result = service.completeReturnOrder(FACTORY, order.getId());
        assertThat(result.getStatus()).isEqualTo(ReturnOrderStatus.COMPLETED);

        // 批1 扣空: usedQuantity 0→20, currentQuantity 0, status USED_UP。
        assertThat(b1.getUsedQuantity()).isEqualByComparingTo(new BigDecimal("20.00"));
        assertThat(b1.getCurrentQuantity()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(b1.getStatus()).isEqualTo(MaterialBatchStatus.USED_UP);
        // 批2 扣 10: usedQuantity 0→10, currentQuantity 90, 仍 AVAILABLE。
        assertThat(b2.getUsedQuantity()).isEqualByComparingTo(new BigDecimal("10.00"));
        assertThat(b2.getCurrentQuantity()).isEqualByComparingTo(new BigDecimal("90.00"));
        assertThat(b2.getStatus()).isEqualTo(MaterialBatchStatus.AVAILABLE);

        // 两条 adjustment (每批一条)。
        verify(materialBatchAdjustmentRepository, times(2)).save(org.mockito.ArgumentMatchers.any());
        verify(materialBatchRepository, times(2)).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("PURCHASE_RETURN + withGoods=false: 无库存动作 (refund-only path)")
    void purchaseReturnWithoutGoods_noInventoryAction() {
        List<ReturnOrderItem> items = List.of(item(MATERIAL_TYPE, new BigDecimal("10.00"), "x"));
        ReturnOrder order = buildOrder(ReturnType.PURCHASE_RETURN, false, ReturnOrderStatus.FINANCE_APPROVED, items);
        stubFindAndSave(order);

        ReturnOrder result = service.completeReturnOrder(FACTORY, order.getId());

        assertThat(result.getStatus()).isEqualTo(ReturnOrderStatus.COMPLETED);
        // No-goods path — 不扣库存, 不查 FEFO, AR/AP 已在 approve 触发。
        verify(materialBatchRepository, never()).save(org.mockito.ArgumentMatchers.any());
        verify(materialBatchRepository, never()).findAvailableBatchesFEFO(anyString(), anyString());
        verify(materialBatchAdjustmentRepository, never()).save(org.mockito.ArgumentMatchers.any());
        verify(arApService, never()).recordAdjustment(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(BigDecimal.class),
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    @DisplayName("SALES_RETURN regression: 仍走 FinishedGoodsBatch 加库存, 不触发 PURCHASE_RETURN 扣减")
    void salesReturnWithGoods_addsFinishedGoods_doesNotDeductMaterial() {
        when(warehouseResolver.resolveLogisticsId(FACTORY)).thenReturn(WH_LOG);
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
        // SALES_RETURN 加库存到 FinishedGoodsBatch, 不触发 MaterialBatch 扣减。
        verify(finishedGoodsBatchRepository, times(1)).save(org.mockito.ArgumentMatchers.any());
        verify(materialBatchRepository, never()).save(org.mockito.ArgumentMatchers.any());
        verify(materialBatchRepository, never()).findAvailableBatchesFEFO(anyString(), anyString());
    }

    @Test
    @DisplayName("PURCHASE_RETURN with-goods: skip 行缺 materialTypeId / 零数量, 只扣 valid 行")
    void purchaseReturnWithGoods_skipsInvalidItems() {
        MaterialBatch batch = availableBatch("B1", MATERIAL_TYPE, new BigDecimal("100.00"));
        when(materialBatchRepository.findAvailableBatchesFEFO(FACTORY, MATERIAL_TYPE))
                .thenReturn(new ArrayList<>(List.of(batch)));

        List<ReturnOrderItem> items = new ArrayList<>();
        items.add(item(MATERIAL_TYPE, new BigDecimal("5.00"), "valid"));   // valid → 扣
        items.add(item(null, new BigDecimal("99.00"), "no material"));     // null materialTypeId → skip
        items.add(item("MT-009", BigDecimal.ZERO, "zero qty"));            // zero qty → skip
        items.add(item("MT-010", null, "null qty"));                        // null qty → skip
        ReturnOrder order = buildOrder(ReturnType.PURCHASE_RETURN, true, ReturnOrderStatus.FINANCE_APPROVED, items);
        stubFindAndSave(order);

        ReturnOrder result = service.completeReturnOrder(FACTORY, order.getId());
        assertThat(result.getStatus()).isEqualTo(ReturnOrderStatus.COMPLETED);

        // 只有 valid 行触发扣减 (1 批次 save + 1 adjustment)。
        verify(materialBatchRepository, times(1)).save(org.mockito.ArgumentMatchers.any());
        verify(materialBatchAdjustmentRepository, times(1)).save(org.mockito.ArgumentMatchers.any());
        assertThat(batch.getUsedQuantity()).isEqualByComparingTo(new BigDecimal("5.00"));
    }

    // Mockito eq() overload helper for String to avoid varargs clash with anyString().
    private static String eqStr(String s) {
        return org.mockito.ArgumentMatchers.eq(s);
    }
}
