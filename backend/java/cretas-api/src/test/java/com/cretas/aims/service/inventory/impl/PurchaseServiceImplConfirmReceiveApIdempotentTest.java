package com.cretas.aims.service.inventory.impl;

import com.cretas.aims.entity.MaterialBatch;
import com.cretas.aims.entity.RawMaterialType;
import com.cretas.aims.entity.enums.PurchaseOrderStatus;
import com.cretas.aims.entity.enums.PurchaseReceiveStatus;
import com.cretas.aims.entity.finance.ArApTransaction;
import com.cretas.aims.entity.inventory.PurchaseOrder;
import com.cretas.aims.entity.inventory.PurchaseOrderItem;
import com.cretas.aims.entity.inventory.PurchaseReceiveItem;
import com.cretas.aims.entity.inventory.PurchaseReceiveRecord;
import com.cretas.aims.repository.MaterialBatchRepository;
import com.cretas.aims.repository.RawMaterialTypeRepository;
import com.cretas.aims.repository.SupplierRepository;
import com.cretas.aims.repository.bom.BomItemRepository;
import com.cretas.aims.repository.inventory.PurchaseOrderItemRepository;
import com.cretas.aims.repository.inventory.PurchaseOrderRepository;
import com.cretas.aims.repository.inventory.PurchaseReceiveRecordRepository;
import com.cretas.aims.service.MaterialBatchService;
import com.cretas.aims.service.finance.ArApService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * 🔒 doomed-tx 回归测试 (2026-07-02): 采购入库 confirmReceive 对同一 PO 分批入库时的自动挂账。
 *
 * <p>Bug: PO 首次入库时 confirmReceive 已自动挂账(AP_INVOICE)。第 2 次分批入库时 confirmReceive
 * 在同一 {@code @Transactional} 内调 {@code arApService.recordPayable}, 后者对已挂账的 PO 抛
 * 409, 事务被标记 rollback-only → 外层 commit 抛 {@code UnexpectedRollbackException} → doomed-tx
 * 兜底转通用 409 → 第 2 次入库<b>永久无法确认</b>(即使数据一致)。
 *
 * <p>Fix: confirmReceive 改调 {@code arApService.recordPayableIfAbsent} —— 幂等、不抛异常。
 *
 * <p>核心断言: PO 已有应付时, 第 2 次 confirmReceive <b>成功完成</b>(状态 CONFIRMED, MaterialBatch
 * 创建, 无异常), 且用的是<b>不抛异常</b>的 recordPayableIfAbsent (never 调 recordPayable)。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PurchaseServiceImpl confirmReceive 应付幂等 (doomed-tx 修复)")
class PurchaseServiceImplConfirmReceiveApIdempotentTest {

    @Mock private PurchaseOrderRepository purchaseOrderRepository;
    @Mock private PurchaseOrderItemRepository purchaseOrderItemRepository;
    @Mock private PurchaseReceiveRecordRepository receiveRecordRepository;
    @Mock private SupplierRepository supplierRepository;
    @Mock private RawMaterialTypeRepository materialTypeRepository;
    @Mock private MaterialBatchRepository materialBatchRepository;
    @Mock private BomItemRepository bomItemRepository;
    @Mock private ArApService arApService;
    @Mock private ApplicationEventPublisher applicationEventPublisher;
    @Mock private MaterialBatchService materialBatchService;

    private PurchaseServiceImpl service;

    private static final String FACTORY = "F006";
    private static final String RECEIVE_ID = "RCV-002";
    private static final String PO_ID = "PO-2026-0001";
    private static final String SUPPLIER_ID = "SUP-001";
    private static final String MATERIAL_ID = "MAT-PIG-TROTTER";

    @BeforeEach
    void setUp() {
        service = new PurchaseServiceImpl(
                purchaseOrderRepository,
                purchaseOrderItemRepository,
                receiveRecordRepository,
                supplierRepository,
                materialTypeRepository,
                materialBatchRepository,
                bomItemRepository,
                arApService,
                applicationEventPublisher,
                materialBatchService);
        ReflectionTestUtils.setField(service, "overReceiveRate", new BigDecimal("0.30"));
    }

    @Test
    @DisplayName("🔒 第 2 次入库 — PO 已挂账 → confirmReceive 成功 (批次创建/无 409/不重复挂账)")
    void secondReceive_withExistingAp_confirmSucceeds() {
        PurchaseReceiveRecord record = draftPoRecord();
        PurchaseOrder order = purchaseOrder(new BigDecimal("500.00"));
        // PO 行: 下单 100, 已收 20 → 本次 12.5 在 30% 抄收上限内, 不触发超收 fail-fast。
        PurchaseOrderItem poItem = purchaseOrderItem(new BigDecimal("100.00"), new BigDecimal("20.00"));

        when(receiveRecordRepository.findById(RECEIVE_ID)).thenReturn(Optional.of(record));
        when(purchaseOrderItemRepository.findByPurchaseOrderId(PO_ID)).thenReturn(List.of(poItem));
        when(purchaseOrderRepository.findById(PO_ID)).thenReturn(Optional.of(order));
        when(materialTypeRepository.findById(MATERIAL_ID)).thenReturn(Optional.of(rawMaterial()));
        when(materialBatchRepository.save(any(MaterialBatch.class))).thenAnswer(inv -> {
            MaterialBatch b = inv.getArgument(0);
            b.setId("BATCH-002");
            return b;
        });
        when(receiveRecordRepository.save(any(PurchaseReceiveRecord.class))).thenAnswer(inv -> inv.getArgument(0));

        // 幂等挂账: 该入库单已有应付 → 返回已存在记录, 不抛异常 (模拟修复后的服务行为)。
        ArApTransaction existingAp = new ArApTransaction();
        existingAp.setId("AP-EXISTING");
        when(arApService.recordPayableIfAbsent(eq(FACTORY), eq(SUPPLIER_ID), eq(PO_ID),
                eq("PURCHASE_RECEIVE"), eq(RECEIVE_ID),
                any(BigDecimal.class), any(LocalDate.class), any(), anyString()))
                .thenReturn(existingAp);

        PurchaseReceiveRecord result = assertDoesNotThrow(
                () -> service.confirmReceive(FACTORY, RECEIVE_ID, 9L));

        // 收货入库成功: 状态 CONFIRMED, 批次已创建并回写 materialBatchId。
        assertEquals(PurchaseReceiveStatus.CONFIRMED, result.getStatus());
        assertEquals("BATCH-002", result.getItems().get(0).getMaterialBatchId());
        verify(materialBatchRepository).save(any(MaterialBatch.class));

        // 用的是不抛异常的幂等方法, 绝不调用会抛 409 doom 事务的 recordPayable。
        // per-receive 幂等键 = (PURCHASE_RECEIVE, receiveId), 金额 = 入库单实收值。
        verify(arApService).recordPayableIfAbsent(eq(FACTORY), eq(SUPPLIER_ID), eq(PO_ID),
                eq("PURCHASE_RECEIVE"), eq(RECEIVE_ID),
                any(BigDecimal.class), any(LocalDate.class), any(), anyString());
        verify(arApService, never()).recordPayable(anyString(), anyString(), anyString(),
                any(BigDecimal.class), any(LocalDate.class), any(), anyString());
    }

    private PurchaseReceiveRecord draftPoRecord() {
        PurchaseReceiveRecord record = new PurchaseReceiveRecord();
        record.setId(RECEIVE_ID);
        record.setFactoryId(FACTORY);
        record.setReceiveNumber("RCV-20260702-002");
        record.setPurchaseOrderId(PO_ID);
        record.setSupplierId(SUPPLIER_ID);
        record.setReceiveDate(LocalDate.of(2026, 7, 2));
        record.setWarehouseId("WH-LOG");
        record.setStatus(PurchaseReceiveStatus.DRAFT);
        record.setReceivedBy(9L);

        PurchaseReceiveItem item = new PurchaseReceiveItem();
        item.setMaterialTypeId(MATERIAL_ID);
        item.setMaterialName("猪蹄原料");
        item.setReceivedQuantity(new BigDecimal("12.5000"));
        item.setUnit("kg");
        item.setUnitPrice(new BigDecimal("8.20"));
        record.getItems().add(item);
        // 实收值 = 12.5 × 8.20 = 102.50 (per-receive 挂账用此金额, 非 PO 计划总额)。
        record.setTotalAmount(new BigDecimal("102.50"));
        return record;
    }

    private PurchaseOrder purchaseOrder(BigDecimal totalAmount) {
        PurchaseOrder order = new PurchaseOrder();
        order.setId(PO_ID);
        order.setFactoryId(FACTORY);
        order.setSupplierId(SUPPLIER_ID);
        order.setStatus(PurchaseOrderStatus.PARTIAL_RECEIVED);
        order.setTotalAmount(totalAmount);
        return order;
    }

    private PurchaseOrderItem purchaseOrderItem(BigDecimal ordered, BigDecimal alreadyReceived) {
        PurchaseOrderItem item = new PurchaseOrderItem();
        item.setMaterialTypeId(MATERIAL_ID);
        item.setQuantity(ordered);
        item.setReceivedQuantity(alreadyReceived);
        return item;
    }

    private RawMaterialType rawMaterial() {
        RawMaterialType material = new RawMaterialType();
        material.setId(MATERIAL_ID);
        material.setFactoryId(FACTORY);
        material.setName("猪蹄原料");
        return material;
    }
}
