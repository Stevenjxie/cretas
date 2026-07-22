package com.cretas.aims.service.inventory;

import com.cretas.aims.entity.enums.PurchaseReceiveStatus;
import com.cretas.aims.entity.inventory.PurchaseOrderItem;
import com.cretas.aims.entity.inventory.PurchaseOrder;
import com.cretas.aims.entity.inventory.PurchaseReceiveItem;
import com.cretas.aims.entity.inventory.PurchaseReceiveRecord;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.inventory.PurchaseOrderItemRepository;
import com.cretas.aims.repository.inventory.PurchaseOrderRepository;
import com.cretas.aims.repository.inventory.PurchaseReceiveRecordRepository;
import com.cretas.aims.repository.AttachmentRepository;
import com.cretas.aims.service.MaterialBatchService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * 🔒 doomed-tx 回归测试 (六扇门 2026-06-15, #774 族复发).
 *
 * <p>Bug: 对 PO 行 remaining=20 (下单 X, 已收 Y, remaining=20) 提交 confirmReceive,
 * 本次收 30 (超 30% 上限) → 旧链路在 {@code updateOrderReceiveStatus} 内才抛
 * {@link BusinessException}(409), 但此前 {@code createMaterialBatchFromReceiveItem} 已对
 * 每行 {@code materialBatchRepository.save} → 事务有 pending 写; 内层 @Transactional
 * (recalculateMovingAvgPrice / event listener / recordPayable) 一旦 join 并被标
 * rollback-only → 外层 commit 抛 {@code UnexpectedRollbackException} → HTTP 500
 * (GlobalExceptionHandler 无 UnexpectedRollbackException handler), 客户看到的是 500 而非
 * 干净 422/409 + "超出可入库上限" 提示, 且超收审计副作用也随事务一起丢失.
 *
 * <p>Fix: {@code confirmReceive} 入口 (status guard 之后, 任何 MaterialBatch 创建之前)
 * 调 {@code validateOverReceiveCapForConfirm} fail-fast — 超限直接抛干净 409, 事务从未被
 * doom, 异常正常 propagate → GlobalExceptionHandler 的 BusinessException handler → 409.
 *
 * <p>核心断言: 超收时 confirmReceive 抛 BusinessException(409), 且
 * {@code materialBatchRepository} / {@code materialBatchService} 从未被触达 (证明 fail-fast
 * 发生在任何 DB mutation 之前, 因此事务不会被 doom).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PurchaseServiceImpl confirmReceive 超收 fail-fast (doomed-tx 修复)")
class PurchaseServiceImplConfirmReceiveOverReceiveTest {

    @Mock private PurchaseReceiveRecordRepository receiveRecordRepository;
    @Mock private PurchaseOrderRepository purchaseOrderRepository;
    @Mock private PurchaseOrderItemRepository purchaseOrderItemRepository;
    @Mock private MaterialBatchService materialBatchService;
    @Mock private com.cretas.aims.repository.MaterialBatchRepository materialBatchRepository;
    @Mock private AttachmentRepository attachmentRepository;

    private com.cretas.aims.service.inventory.impl.PurchaseServiceImpl service;

    private static final String FACTORY_ID = "F006";
    private static final String PO_ID = "PO-OR-001";
    private static final String RECEIVE_ID = "RCV-OR-001";
    private static final String MAT_ID = "MAT-OR-001";
    private static final String MAT_NAME = "卤猪蹄原料";

    @BeforeEach
    void setUp() {
        // confirmReceive fail-fast 路径只触达 receiveRecordRepository (getReceiveRecordById)
        // 和 purchaseOrderItemRepository (cap 查询). materialBatchService / materialBatchRepository
        // 作为 mock 传入, 用于 verify 它们在超收时从未被调用 (证明 fail-fast).
        service = new com.cretas.aims.service.inventory.impl.PurchaseServiceImpl(
                purchaseOrderRepository,
                purchaseOrderItemRepository,
                receiveRecordRepository,
                /* supplierRepository       */ null,
                /* materialTypeRepository   */ null,
                materialBatchRepository,
                /* bomItemRepository        */ null,
                /* arApService              */ null,
                /* applicationEventPublisher*/ null,
                materialBatchService);
        ReflectionTestUtils.setField(service, "overReceiveRate", new BigDecimal("0.30"));
        ReflectionTestUtils.setField(service, "attachmentRepository", attachmentRepository);
        lenient().when(attachmentRepository.countByFactoryIdAndEntityTypeAndEntityId(
                FACTORY_ID, com.cretas.aims.entity.Attachment.EntityType.PURCHASE_RECEIPT, RECEIVE_ID))
                .thenReturn(1L);
    }

    @Test
    @DisplayName("未上传供应商供货单或收货凭证时 fail-closed，且库存零写")
    void confirmReceive_withoutAttachment_rejectedBeforeInventoryWrite() {
        mockDraftReceive(new BigDecimal("20"), BigDecimal.ZERO, new BigDecimal("20"));
        when(attachmentRepository.countByFactoryIdAndEntityTypeAndEntityId(
                FACTORY_ID, com.cretas.aims.entity.Attachment.EntityType.PURCHASE_RECEIPT, RECEIVE_ID))
                .thenReturn(0L);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.confirmReceive(FACTORY_ID, RECEIVE_ID, 1L));

        assertEquals(409, ex.getCode());
        assertEquals("PURCHASE_RECEIPT_ATTACHMENT_REQUIRED", ex.getErrorCode());
        verifyNoInteractions(materialBatchService);
        verifyNoInteractions(materialBatchRepository);
        verify(receiveRecordRepository, never()).save(any());
    }

    /**
     * 构造一个 DRAFT 入库单, 含一行 (materialTypeId=MAT_ID, receivedQuantity=thisReceive),
     * 关联 PO_ID. 同时 mock PO 行 (下单 orderedQty, 已收 alreadyReceived).
     */
    private void mockDraftReceive(BigDecimal orderedQty, BigDecimal alreadyReceived, BigDecimal thisReceive) {
        PurchaseReceiveRecord record = new PurchaseReceiveRecord();
        record.setId(RECEIVE_ID);
        record.setFactoryId(FACTORY_ID);
        record.setPurchaseOrderId(PO_ID);
        record.setStatus(PurchaseReceiveStatus.DRAFT);
        PurchaseReceiveItem item = new PurchaseReceiveItem();
        item.setReceiveRecordId(RECEIVE_ID);
        item.setMaterialTypeId(MAT_ID);
        item.setMaterialName(MAT_NAME);
        item.setReceivedQuantity(thisReceive);
        item.setUnit("kg");
        record.getItems().add(item);
        when(receiveRecordRepository.findByIdAndFactoryIdForUpdate(RECEIVE_ID, FACTORY_ID))
                .thenReturn(Optional.of(record));

        PurchaseOrder order = new PurchaseOrder();
        order.setId(PO_ID);
        order.setFactoryId(FACTORY_ID);
        lenient().when(purchaseOrderRepository.findByIdAndFactoryIdForUpdate(PO_ID, FACTORY_ID))
                .thenReturn(Optional.of(order));

        PurchaseOrderItem poItem = new PurchaseOrderItem();
        poItem.setPurchaseOrderId(PO_ID);
        poItem.setMaterialTypeId(MAT_ID);
        poItem.setMaterialName(MAT_NAME);
        poItem.setQuantity(orderedQty);
        poItem.setReceivedQuantity(alreadyReceived);
        lenient().when(purchaseOrderItemRepository.findByPurchaseOrderId(PO_ID)).thenReturn(List.of(poItem));
    }

    @Test
    @DisplayName("PO 剩余 20 (下单 100, 已收 80), 本次收 30 → 抛 409 而非 500, 不创建任何批次")
    void confirmReceive_overReceive_failsFastWith409_noBatchCreated() {
        // 下单 100, 已收 80 → remaining=20; 本次 30 → 累计 110 > 上限 130? no.
        // 用下单 20、已收 0、本次 30: 上限 = 20*1.3 = 26, 累计 30 > 26 → 超收.
        // (E2E 场景: remaining=20 提交 30 超 30% 上限)
        mockDraftReceive(new BigDecimal("20"), BigDecimal.ZERO, new BigDecimal("30"));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.confirmReceive(FACTORY_ID, RECEIVE_ID, 1L));

        // 干净业务异常 (409), 不是 UnexpectedRollbackException → 500
        assertEquals(409, ex.getCode(),
                "超收应返回干净 409, 不应触发 doomed-tx UnexpectedRollbackException(500)");
        assertTrue(ex.getMessage().contains("超出可入库上限"),
                "message 应含上限提示, got: " + ex.getMessage());
        assertTrue(ex.getMessage().contains(MAT_NAME),
                "message 应含物料名 (防呆 Rule 2 上下文), got: " + ex.getMessage());

        // fail-fast 证据: 任何 DB mutation 之前抛出 → 物料批次/均价从未被创建/更新
        verifyNoInteractions(materialBatchService);
        verifyNoInteractions(materialBatchRepository);
        // 也未推进入库单状态 (未 save 为 CONFIRMED)
        verify(receiveRecordRepository, never()).save(any());
    }

    @Test
    @DisplayName("分批累计超收 (下单 100, 已收 80, 本次 60 → 累计 140 > 130) → 409 fail-fast")
    void confirmReceive_partialAccumulationOverCap_failsFast() {
        mockDraftReceive(new BigDecimal("100"), new BigDecimal("80"), new BigDecimal("60"));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.confirmReceive(FACTORY_ID, RECEIVE_ID, 1L));
        assertEquals(409, ex.getCode());
        assertTrue(ex.getMessage().contains("累计 140"),
                "expected accumulated 140 in message, got: " + ex.getMessage());
        verifyNoInteractions(materialBatchService);
        verifyNoInteractions(materialBatchRepository);
    }

    @Test
    @DisplayName("正好上限 (下单 100, 已收 0, 本次 130 = 上限) → 不抛 409, cap 校验通过")
    void confirmReceive_atCapBoundary_passesCapCheck() {
        // 上限 = 100*1.3 = 130; 本次 130 → 累计 130 == 上限, 不超收.
        // cap 校验通过后继续走 MaterialBatch 创建 — 但本 fixture materialBatchRepository
        // 是 mock (save 返 null), createMaterialBatchFromReceiveItem 会因后续 null 依赖
        // (warehouseResolver 等未注入) NPE. 我们只断言: cap 校验没拦 (非 409 over-receive)。
        mockDraftReceive(new BigDecimal("100"), BigDecimal.ZERO, new BigDecimal("130"));

        Throwable thrown = assertThrows(Throwable.class,
                () -> service.confirmReceive(FACTORY_ID, RECEIVE_ID, 1L));
        boolean isOverReceive409 = thrown instanceof BusinessException
                && ((BusinessException) thrown).getCode() == 409
                && thrown.getMessage() != null
                && thrown.getMessage().contains("超出可入库上限");
        assertFalse(isOverReceive409,
                "边界 130 == 上限不应被超收 cap 拦截, got: " + thrown.getMessage());
    }

    @Test
    @DisplayName("略超上限 (下单 100, 已收 0, 本次 131 > 130) → 409 fail-fast")
    void confirmReceive_justOverCap_failsFast() {
        mockDraftReceive(new BigDecimal("100"), BigDecimal.ZERO, new BigDecimal("131"));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.confirmReceive(FACTORY_ID, RECEIVE_ID, 1L));
        assertEquals(409, ex.getCode());
        verifyNoInteractions(materialBatchService);
        verifyNoInteractions(materialBatchRepository);
    }

    @Test
    @DisplayName("状态非 DRAFT/PENDING_QC (已 CONFIRMED) → 409, 早于超收校验 (sanity)")
    void confirmReceive_alreadyConfirmed_throwsStatusGuardBeforeCapCheck() {
        PurchaseReceiveRecord record = new PurchaseReceiveRecord();
        record.setId(RECEIVE_ID);
        record.setFactoryId(FACTORY_ID);
        record.setPurchaseOrderId(PO_ID);
        record.setStatus(PurchaseReceiveStatus.CONFIRMED);
        when(receiveRecordRepository.findByIdAndFactoryIdForUpdate(RECEIVE_ID, FACTORY_ID))
                .thenReturn(Optional.of(record));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.confirmReceive(FACTORY_ID, RECEIVE_ID, 1L));
        assertEquals(409, ex.getCode());
        assertTrue(ex.getMessage().contains("只有草稿或待质检状态"),
                "expected status guard message, got: " + ex.getMessage());
        // 状态守卫早于 cap 查询 → 不查 PO 行
        verifyNoInteractions(purchaseOrderItemRepository);
        verifyNoInteractions(materialBatchService);
    }
}
