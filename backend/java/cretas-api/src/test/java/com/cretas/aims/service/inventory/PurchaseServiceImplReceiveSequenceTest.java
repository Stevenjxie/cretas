package com.cretas.aims.service.inventory;

import com.cretas.aims.entity.User;
import com.cretas.aims.entity.enums.PurchaseReceiveStatus;
import com.cretas.aims.entity.inventory.PurchaseOrder;
import com.cretas.aims.entity.inventory.PurchaseReceiveItem;
import com.cretas.aims.entity.inventory.PurchaseReceiveRecord;
import com.cretas.aims.entity.enums.PurchaseOrderStatus;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.inventory.PurchaseOrderRepository;
import com.cretas.aims.repository.inventory.PurchaseReceiveRecordRepository;
import com.cretas.aims.service.inventory.impl.PurchaseServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * 单元 G (F006 R-B3) — 收货分次时序明细 endpoint regression test.
 *
 * <p>客户张权 (5/8 system review): "收货数量要显示出来 (第一次收了多少第二次收了多少更直观)".
 * Verifies PurchaseServiceImpl.getOrderReceiveSequence assigns 1-based seq in createdAt
 * ascending order, sums per-record totalQuantity, enforces factory isolation, and returns
 * an honest empty list when no receive records exist.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PurchaseServiceImpl 收货分次时序明细测试 (单元G F006 R-B3)")
class PurchaseServiceImplReceiveSequenceTest {

    @Mock private PurchaseOrderRepository purchaseOrderRepository;
    @Mock private PurchaseReceiveRecordRepository receiveRecordRepository;

    private PurchaseServiceImpl service;

    private static final String FACTORY_ID = "F001";
    private static final String OTHER_FACTORY = "F999";
    private static final String PO_ID = "PO-001";

    @BeforeEach
    void setUp() {
        service = new PurchaseServiceImpl(
                purchaseOrderRepository,
                /* purchaseOrderItemRepository */ null,
                receiveRecordRepository,
                /* supplierRepository */ null,
                /* materialTypeRepository */ null,
                /* materialBatchRepository */ null,
                /* bomItemRepository */ null,
                /* arApService */ null,
                /* applicationEventPublisher */ null,
                /* materialBatchService */ null);
    }

    private PurchaseOrder buildOrder(String factoryId) {
        PurchaseOrder order = new PurchaseOrder();
        order.setId(PO_ID);
        order.setFactoryId(factoryId);
        order.setOrderNumber("PO-20260517-001");
        order.setStatus(PurchaseOrderStatus.APPROVED);
        return order;
    }

    private PurchaseReceiveItem buildItem(String name, BigDecimal qty, String unit) {
        PurchaseReceiveItem item = new PurchaseReceiveItem();
        item.setMaterialName(name);
        item.setReceivedQuantity(qty);
        item.setUnit(unit);
        return item;
    }

    private PurchaseReceiveRecord buildRecord(String id, String number, LocalDate date,
                                              LocalDateTime createdAt, User user,
                                              List<PurchaseReceiveItem> items) {
        PurchaseReceiveRecord record = new PurchaseReceiveRecord();
        record.setId(id);
        record.setFactoryId(FACTORY_ID);
        record.setPurchaseOrderId(PO_ID);
        record.setReceiveNumber(number);
        record.setReceiveDate(date);
        record.setStatus(PurchaseReceiveStatus.CONFIRMED);
        record.setCreatedAt(createdAt);
        record.setReceivedByUser(user);
        record.setItems(items);
        return record;
    }

    @Test
    @DisplayName("2 次收货 → seq 1,2 按 createdAt 升序 + per-record totalQuantity 正确")
    void getOrderReceiveSequence_assignsSeqAndTotals() {
        User zhang = new User();
        zhang.setFullName("张权");

        // repository contract: ordered by createdAt asc (1st receive, then 2nd)
        PurchaseReceiveRecord r1 = buildRecord("RCV-1", "RCV-20260517-001",
                LocalDate.of(2026, 5, 17), LocalDateTime.of(2026, 5, 17, 9, 0), zhang,
                List.of(buildItem("辣椒", new BigDecimal("100"), "kg")));
        PurchaseReceiveRecord r2 = buildRecord("RCV-2", "RCV-20260518-001",
                LocalDate.of(2026, 5, 18), LocalDateTime.of(2026, 5, 18, 10, 30), zhang,
                List.of(buildItem("辣椒", new BigDecimal("80"), "kg"),
                        buildItem("辣椒", new BigDecimal("20"), "kg")));

        when(purchaseOrderRepository.findById(PO_ID))
                .thenReturn(Optional.of(buildOrder(FACTORY_ID)));
        when(receiveRecordRepository
                .findByFactoryIdAndPurchaseOrderIdOrderByCreatedAtAsc(FACTORY_ID, PO_ID))
                .thenReturn(List.of(r1, r2));

        List<Map<String, Object>> seq = service.getOrderReceiveSequence(FACTORY_ID, PO_ID);

        assertEquals(2, seq.size());

        Map<String, Object> first = seq.get(0);
        assertEquals(1, first.get("seq"));
        assertEquals("RCV-1", first.get("receiveId"));
        assertEquals("RCV-20260517-001", first.get("receiveNumber"));
        assertEquals("张权", first.get("createdByName"));
        assertEquals(new BigDecimal("100"), first.get("totalQuantity"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> firstItems = (List<Map<String, Object>>) first.get("items");
        assertEquals(1, firstItems.size());
        assertEquals("辣椒", firstItems.get(0).get("materialName"));
        assertEquals("kg", firstItems.get(0).get("unit"));

        Map<String, Object> second = seq.get(1);
        assertEquals(2, second.get("seq"));
        assertEquals("RCV-2", second.get("receiveId"));
        assertEquals(new BigDecimal("100"), second.get("totalQuantity"),
                "2 items 80+20 should sum to 100");
    }

    @Test
    @DisplayName("无收货记录 → honest 空列表 (非 null)")
    void getOrderReceiveSequence_noRecords_returnsEmptyList() {
        when(purchaseOrderRepository.findById(PO_ID))
                .thenReturn(Optional.of(buildOrder(FACTORY_ID)));
        when(receiveRecordRepository
                .findByFactoryIdAndPurchaseOrderIdOrderByCreatedAtAsc(FACTORY_ID, PO_ID))
                .thenReturn(new ArrayList<>());

        List<Map<String, Object>> seq = service.getOrderReceiveSequence(FACTORY_ID, PO_ID);
        assertNotNull(seq);
        assertTrue(seq.isEmpty());
    }

    @Test
    @DisplayName("跨工厂访问 → throw BusinessException 403")
    void getOrderReceiveSequence_crossFactory_throwsForbidden() {
        when(purchaseOrderRepository.findById(PO_ID))
                .thenReturn(Optional.of(buildOrder(OTHER_FACTORY)));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.getOrderReceiveSequence(FACTORY_ID, PO_ID));
        assertEquals(403, ex.getCode());
    }

    @Test
    @DisplayName("null receivedByUser / null item qty → 不抛 NPE, totalQuantity 视为 0")
    void getOrderReceiveSequence_nullSafe() {
        PurchaseReceiveItem nullQty = new PurchaseReceiveItem();
        nullQty.setMaterialName("盐");
        nullQty.setReceivedQuantity(null);
        nullQty.setUnit("kg");

        PurchaseReceiveRecord r1 = buildRecord("RCV-1", "RCV-20260517-001",
                LocalDate.of(2026, 5, 17), LocalDateTime.of(2026, 5, 17, 9, 0), null,
                List.of(nullQty));

        when(purchaseOrderRepository.findById(PO_ID))
                .thenReturn(Optional.of(buildOrder(FACTORY_ID)));
        when(receiveRecordRepository
                .findByFactoryIdAndPurchaseOrderIdOrderByCreatedAtAsc(FACTORY_ID, PO_ID))
                .thenReturn(List.of(r1));

        List<Map<String, Object>> seq = service.getOrderReceiveSequence(FACTORY_ID, PO_ID);
        assertEquals(1, seq.size());
        assertEquals(BigDecimal.ZERO, seq.get(0).get("totalQuantity"));
        assertNull(seq.get(0).get("createdByName"));
    }
}
