package com.cretas.aims.service.inventory.impl;

import com.cretas.aims.entity.MaterialBatch;
import com.cretas.aims.entity.RawMaterialType;
import com.cretas.aims.entity.enums.PurchaseReceiveStatus;
import com.cretas.aims.entity.inventory.PurchaseOrder;
import com.cretas.aims.entity.inventory.PurchaseOrderItem;
import com.cretas.aims.entity.inventory.PurchaseReceiveItem;
import com.cretas.aims.entity.inventory.PurchaseReceiveRecord;
import com.cretas.aims.repository.AttachmentRepository;
import com.cretas.aims.repository.MaterialBatchRepository;
import com.cretas.aims.repository.RawMaterialTypeRepository;
import com.cretas.aims.repository.SupplierRepository;
import com.cretas.aims.repository.bom.BomRecipeItemRepository;
import com.cretas.aims.repository.inventory.PurchaseOrderItemRepository;
import com.cretas.aims.repository.inventory.PurchaseOrderRepository;
import com.cretas.aims.repository.inventory.PurchaseReceiveRecordRepository;
import com.cretas.aims.service.MaterialBatchService;
import com.cretas.aims.service.factory.WarehouseResolver;
import com.cretas.aims.service.notification.NotificationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
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
 * 收货差异通知采购人 — 单元测试.
 *
 * <p>覆盖 {@code PurchaseServiceImpl.confirmReceive} 触发收货差异通知的三个核心场景:
 * <ol>
 *   <li>超收 (实收 &gt; 采购量) → 通知采购人</li>
 *   <li>少收 (实收 &lt; 采购量) → 通知采购人</li>
 *   <li>精确匹配 (实收 = 采购量) → 不发通知</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PurchaseServiceImpl — confirmReceive 收货差异通知采购人")
class PurchaseServiceImplReceiveVarianceNotifyTest {

    @Mock private PurchaseOrderRepository purchaseOrderRepository;
    @Mock private PurchaseOrderItemRepository purchaseOrderItemRepository;
    @Mock private PurchaseReceiveRecordRepository receiveRecordRepository;
    @Mock private SupplierRepository supplierRepository;
    @Mock private RawMaterialTypeRepository materialTypeRepository;
    @Mock private MaterialBatchRepository materialBatchRepository;
    @Mock private BomRecipeItemRepository bomItemRepository;
    @Mock private com.cretas.aims.service.finance.ArApService arApService;
    @Mock private ApplicationEventPublisher applicationEventPublisher;
    @Mock private MaterialBatchService materialBatchService;
    @Mock private WarehouseResolver warehouseResolver;
    @Mock private AttachmentRepository attachmentRepository;
    @Mock private NotificationService notificationService;

    private PurchaseServiceImpl service;

    private static final String FACTORY      = "F006";
    private static final String PO_ID        = "PO-LSM-001";
    private static final String RECEIVE_ID   = "RCV-LSM-001";
    private static final String MATERIAL_ID  = "RMT-PORK-KNUCKLE";
    private static final String MATERIAL_NAME = "猪膝软骨";
    private static final Long   PURCHASER_ID  = 42L;

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
        ReflectionTestUtils.setField(service, "warehouseResolver", warehouseResolver);
        ReflectionTestUtils.setField(service, "notificationService", notificationService);
        // PR #1577 起 confirmReceive 前置「收货凭证门」: attachmentRepository 为 null 直接 503
        // (fail-closed, 无凭证服务不写库存), 有服务但零附件则 409。本用例验证的是 QC/差异逻辑,
        // 所以把门开着 — 门本身另有 PurchaseServiceImplConfirmReceiveOverReceiveTest 专门覆盖。
        ReflectionTestUtils.setField(service, "attachmentRepository", attachmentRepository);
        lenient().when(attachmentRepository.countByFactoryIdAndEntityTypeAndEntityId(
                FACTORY, com.cretas.aims.entity.Attachment.EntityType.PURCHASE_RECEIPT, RECEIVE_ID))
                .thenReturn(1L);
    }

    // ==================== 测试用例 ====================

    @Test
    @DisplayName("超收: 实收 130kg > 采购量 100kg → notifyUser 被调用, 标题含\"超收\"")
    void confirmReceive_overReceipt_notifiesPurchaser() {
        BigDecimal ordered  = new BigDecimal("100.0000");
        BigDecimal received = new BigDecimal("130.0000");

        stubConfirmFlow(ordered, received);

        service.confirmReceive(FACTORY, RECEIVE_ID, PURCHASER_ID);

        ArgumentCaptor<String> titleCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> bodyCaptor  = ArgumentCaptor.forClass(String.class);
        verify(notificationService).notifyUser(
                eq(FACTORY), eq(PURCHASER_ID), titleCaptor.capture(), bodyCaptor.capture());

        assertTrue(titleCaptor.getValue().contains("超收"),
                "通知标题应包含\"超收\", 实际: " + titleCaptor.getValue());
        assertTrue(bodyCaptor.getValue().contains(MATERIAL_NAME),
                "通知正文应包含品名, 实际: " + bodyCaptor.getValue());
        assertTrue(bodyCaptor.getValue().contains("100"),
                "通知正文应包含采购量 100, 实际: " + bodyCaptor.getValue());
        assertTrue(bodyCaptor.getValue().contains("130"),
                "通知正文应包含实收量 130, 实际: " + bodyCaptor.getValue());
    }

    @Test
    @DisplayName("少收: 实收 80kg < 采购量 100kg → notifyUser 被调用, 标题含\"少收\"")
    void confirmReceive_underReceipt_notifiesPurchaser() {
        BigDecimal ordered  = new BigDecimal("100.0000");
        BigDecimal received = new BigDecimal("80.0000");

        stubConfirmFlow(ordered, received);

        service.confirmReceive(FACTORY, RECEIVE_ID, PURCHASER_ID);

        ArgumentCaptor<String> titleCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> bodyCaptor  = ArgumentCaptor.forClass(String.class);
        verify(notificationService).notifyUser(
                eq(FACTORY), eq(PURCHASER_ID), titleCaptor.capture(), bodyCaptor.capture());

        assertTrue(titleCaptor.getValue().contains("少收"),
                "通知标题应包含\"少收\", 实际: " + titleCaptor.getValue());
        assertTrue(bodyCaptor.getValue().contains(MATERIAL_NAME),
                "通知正文应包含品名, 实际: " + bodyCaptor.getValue());
        assertTrue(bodyCaptor.getValue().contains("100"),
                "通知正文应包含采购量 100, 实际: " + bodyCaptor.getValue());
        assertTrue(bodyCaptor.getValue().contains("80"),
                "通知正文应包含实收量 80, 实际: " + bodyCaptor.getValue());
    }

    @Test
    @DisplayName("精确匹配: 实收 100kg = 采购量 100kg → notifyUser 不被调用")
    void confirmReceive_exactMatch_noNotification() {
        BigDecimal ordered  = new BigDecimal("100.0000");
        BigDecimal received = new BigDecimal("100.0000");

        stubConfirmFlow(ordered, received);

        service.confirmReceive(FACTORY, RECEIVE_ID, PURCHASER_ID);

        verify(notificationService, never()).notifyUser(anyString(), any(), anyString(), anyString());
    }

    // ==================== 工具方法 ====================

    /**
     * 为 confirmReceive 流程搭建最小 stub.
     *
     * <p>入库单关联采购订单 {@value PO_ID}, PO 创建人为 {@value PURCHASER_ID},
     * 单行原料 {@value MATERIAL_NAME}, PO 计划量 {@code ordered}, 实收量 {@code received}.
     */
    private void stubConfirmFlow(BigDecimal ordered, BigDecimal received) {
        // 1. 入库单 (DRAFT)
        PurchaseReceiveRecord record = buildReceiveRecord(received);
        // PR #1577 起 confirmReceive 改用 findByIdAndFactoryIdForUpdate(receiveId, factoryId):
        // 租户过滤下沉进 SQL 并同时取 PESSIMISTIC_WRITE 锁 (堵并发重复确认), 取代旧的
        // findById + 手工 factoryId 比对。租户隔离只增不减, 这里只是 mock 没跟上换掉的 finder。
        when(receiveRecordRepository.findByIdAndFactoryIdForUpdate(RECEIVE_ID, FACTORY))
                .thenReturn(Optional.of(record));

        // 2. 原料类型 (createMaterialBatchFromReceiveItem 需要)
        RawMaterialType material = new RawMaterialType();
        material.setId(MATERIAL_ID);
        material.setFactoryId(FACTORY);
        material.setName(MATERIAL_NAME);
        when(materialTypeRepository.findById(MATERIAL_ID)).thenReturn(Optional.of(material));

        // 3. materialBatchRepository.save 返回带 id 的 batch
        when(materialBatchRepository.save(any(MaterialBatch.class))).thenAnswer(inv -> {
            MaterialBatch b = inv.getArgument(0);
            b.setId("BATCH-LSM-001");
            return b;
        });

        // 4. 入库单保存
        when(receiveRecordRepository.save(any(PurchaseReceiveRecord.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        // 5. 采购订单 (notifyPurchaserOnReceiveVariance 需要)
        PurchaseOrder po = new PurchaseOrder();
        po.setId(PO_ID);
        po.setFactoryId(FACTORY);
        po.setOrderNumber("PO-20260615-001");
        po.setCreatedBy(PURCHASER_ID);
        when(purchaseOrderRepository.findById(PO_ID)).thenReturn(Optional.of(po));
        // confirmReceive 的超收 fail-fast 分支另走租户内 + 悲观锁的 finder (PR #1577)。
        // 后续的 notifyPurchaserOnReceiveVariance 等仍用裸 findById, 所以两个 stub 都要留。
        when(purchaseOrderRepository.findByIdAndFactoryIdForUpdate(PO_ID, FACTORY))
                .thenReturn(Optional.of(po));

        // 6. 采购订单行 (notifyPurchaserOnReceiveVariance + SP6 exception block 需要)
        PurchaseOrderItem poItem = new PurchaseOrderItem();
        poItem.setPurchaseOrderId(PO_ID);
        poItem.setMaterialTypeId(MATERIAL_ID);
        poItem.setMaterialName(MATERIAL_NAME);
        poItem.setQuantity(ordered);
        // Previously-received = 0: this is the first (and only) receipt, so cumulative = received qty.
        poItem.setReceivedQuantity(BigDecimal.ZERO);
        poItem.setUnit("kg");
        when(purchaseOrderItemRepository.findByPurchaseOrderId(PO_ID))
                .thenReturn(List.of(poItem));
    }

    private PurchaseReceiveRecord buildReceiveRecord(BigDecimal receivedQty) {
        PurchaseReceiveRecord record = new PurchaseReceiveRecord();
        record.setId(RECEIVE_ID);
        record.setFactoryId(FACTORY);
        record.setReceiveNumber("RCV-LSM-20260615-001");
        record.setPurchaseOrderId(PO_ID);
        record.setSupplierId("SUP-LSM");
        record.setReceiveDate(LocalDate.of(2026, 6, 15));
        record.setWarehouseId("WH-LSM-01");
        record.setStatus(PurchaseReceiveStatus.DRAFT);
        record.setReceivedBy(PURCHASER_ID);

        PurchaseReceiveItem item = new PurchaseReceiveItem();
        item.setMaterialTypeId(MATERIAL_ID);
        item.setMaterialName(MATERIAL_NAME);
        item.setReceivedQuantity(receivedQty);
        item.setUnit("kg");
        item.setUnitPrice(new BigDecimal("25.00"));
        record.getItems().add(item);
        return record;
    }
}
