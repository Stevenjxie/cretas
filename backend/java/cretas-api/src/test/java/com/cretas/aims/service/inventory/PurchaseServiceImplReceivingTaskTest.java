package com.cretas.aims.service.inventory;

import com.cretas.aims.dto.inventory.CreateReceiveRecordRequest;
import com.cretas.aims.dto.inventory.PurchaseReceivingTaskResponse;
import com.cretas.aims.entity.Supplier;
import com.cretas.aims.entity.enums.PurchaseOrderStatus;
import com.cretas.aims.entity.enums.PurchaseReceiveStatus;
import com.cretas.aims.entity.inventory.PurchaseOrder;
import com.cretas.aims.entity.inventory.PurchaseOrderItem;
import com.cretas.aims.entity.inventory.PurchaseReceiveItem;
import com.cretas.aims.entity.inventory.PurchaseReceiveRecord;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.SupplierRepository;
import com.cretas.aims.repository.inventory.PurchaseOrderItemRepository;
import com.cretas.aims.repository.inventory.PurchaseOrderRepository;
import com.cretas.aims.repository.inventory.PurchaseReceiveRecordRepository;
import com.cretas.aims.service.inventory.impl.PurchaseServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PurchaseServiceImplReceivingTaskTest {

    private static final String FACTORY = "F006";
    private static final String PO_ID = "po-r3";
    private static final String SUPPLIER_ID = "supplier-r3";
    private static final String MATERIAL_ID = "material-r3";

    @Mock private PurchaseOrderRepository orderRepository;
    @Mock private PurchaseOrderItemRepository itemRepository;
    @Mock private PurchaseReceiveRecordRepository receiveRepository;
    @Mock private SupplierRepository supplierRepository;

    private PurchaseServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new PurchaseServiceImpl(orderRepository, itemRepository, receiveRepository,
                supplierRepository, null, null, null, null, null, null);
        ReflectionTestUtils.setField(service, "overReceiveRate", new BigDecimal("0.30"));
    }

    @Test
    void approvedOrderIsDerivedAsOneTaskWithoutWriting() {
        PurchaseOrder order = order(PurchaseOrderStatus.FINANCE_APPROVED);
        when(orderRepository.findByIdAndFactoryId(PO_ID, FACTORY)).thenReturn(Optional.of(order));
        when(itemRepository.findByPurchaseOrderId(PO_ID)).thenReturn(List.of(item("10", "0")));
        when(receiveRepository.findByFactoryIdAndPurchaseOrderIdOrderByCreatedAtAsc(FACTORY, PO_ID))
                .thenReturn(List.of());

        List<PurchaseReceivingTaskResponse> tasks = service.getPendingReceivingTasks(FACTORY, PO_ID, null);

        assertEquals(1, tasks.size());
        assertEquals("WAITING_RECEIVE", tasks.get(0).getStatus());
        assertEquals(new BigDecimal("10"), tasks.get(0).getItems().get(0).getRemainingReceivableQuantity());
        verify(orderRepository, never()).save(any());
        verify(receiveRepository, never()).save(any());
    }

    @Test
    void activeDraftIsResumedAndExcludedFromRemaining() {
        PurchaseOrder order = order(PurchaseOrderStatus.FINANCE_APPROVED);
        PurchaseReceiveRecord draft = receipt("RCV-R3", PurchaseReceiveStatus.DRAFT, "5");
        when(orderRepository.findByIdAndFactoryId(PO_ID, FACTORY)).thenReturn(Optional.of(order));
        when(itemRepository.findByPurchaseOrderId(PO_ID)).thenReturn(List.of(item("10", "3")));
        when(receiveRepository.findByFactoryIdAndPurchaseOrderIdOrderByCreatedAtAsc(FACTORY, PO_ID))
                .thenReturn(List.of(draft));

        PurchaseReceivingTaskResponse task = service.getPendingReceivingTasks(FACTORY, PO_ID, null).get(0);

        assertEquals("RECEIVING", task.getStatus());
        assertEquals("RCV-R3", task.getActiveReceiptNumber());
        assertEquals(new BigDecimal("5"), task.getItems().get(0).getActiveDraftAllocatedQuantity());
        assertEquals(new BigDecimal("2"), task.getItems().get(0).getRemainingReceivableQuantity());
    }

    @Test
    void activeDraftAllocationIsNotDoubleCountedAcrossDuplicateMaterialLines() {
        PurchaseOrder order = order(PurchaseOrderStatus.FINANCE_APPROVED);
        PurchaseOrderItem first = item("4", "0");
        PurchaseOrderItem second = item("6", "0");
        second.setId(2L);
        PurchaseReceiveRecord draft = receipt("RCV-R3", PurchaseReceiveStatus.DRAFT, "5");
        draft.getItems().get(0).setPurchaseOrderItemId(2L);
        when(orderRepository.findByIdAndFactoryId(PO_ID, FACTORY)).thenReturn(Optional.of(order));
        when(itemRepository.findByPurchaseOrderId(PO_ID)).thenReturn(List.of(first, second));
        when(receiveRepository.findByFactoryIdAndPurchaseOrderIdOrderByCreatedAtAsc(FACTORY, PO_ID))
                .thenReturn(List.of(draft));

        PurchaseReceivingTaskResponse task = service.getPendingReceivingTasks(FACTORY, PO_ID, null).get(0);

        assertEquals(BigDecimal.ZERO, task.getItems().get(0).getActiveDraftAllocatedQuantity());
        assertEquals(new BigDecimal("5"), task.getItems().get(1).getActiveDraftAllocatedQuantity());
        BigDecimal allocatedTotal = task.getItems().stream()
                .map(PurchaseReceivingTaskResponse.Item::getActiveDraftAllocatedQuantity)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertEquals(new BigDecimal("5"), allocatedTotal);
    }

    @Test
    void legacyDraftWithoutOrderLineIdentityIsConflictForDuplicateMaterialLines() {
        PurchaseOrder order = order(PurchaseOrderStatus.FINANCE_APPROVED);
        PurchaseOrderItem first = item("4", "0");
        PurchaseOrderItem second = item("6", "0");
        second.setId(2L);
        PurchaseReceiveRecord legacyDraft = receipt("RCV-LEGACY", PurchaseReceiveStatus.DRAFT, "5");
        when(orderRepository.findByIdAndFactoryId(PO_ID, FACTORY)).thenReturn(Optional.of(order));
        when(itemRepository.findByPurchaseOrderId(PO_ID)).thenReturn(List.of(first, second));
        when(receiveRepository.findByFactoryIdAndPurchaseOrderIdOrderByCreatedAtAsc(FACTORY, PO_ID))
                .thenReturn(List.of(legacyDraft));

        PurchaseReceivingTaskResponse task = service
                .getPendingReceivingTasks(FACTORY, PO_ID, null).get(0);

        assertTrue(task.isReceiptConflict());
        assertEquals(1, task.getActiveReceiptCount());
    }

    @Test
    void multipleHistoricalActiveReceiptsAreVisibleAsConflict() {
        PurchaseOrder order = order(PurchaseOrderStatus.FINANCE_APPROVED);
        when(orderRepository.findByIdAndFactoryId(PO_ID, FACTORY)).thenReturn(Optional.of(order));
        when(itemRepository.findByPurchaseOrderId(PO_ID)).thenReturn(List.of(item("10", "0")));
        when(receiveRepository.findByFactoryIdAndPurchaseOrderIdOrderByCreatedAtAsc(FACTORY, PO_ID))
                .thenReturn(List.of(
                        receipt("RCV-FIRST", PurchaseReceiveStatus.DRAFT, "5"),
                        receipt("RCV-SECOND", PurchaseReceiveStatus.DRAFT, "5")));

        PurchaseReceivingTaskResponse task = service.getPendingReceivingTasks(FACTORY, PO_ID, null).get(0);

        assertTrue(task.isReceiptConflict());
        assertEquals(2, task.getActiveReceiptCount());
        assertEquals("RCV-FIRST", task.getActiveReceiptNumber());
        assertEquals(new BigDecimal("10"), task.getItems().get(0).getActiveDraftAllocatedQuantity());
        assertEquals(BigDecimal.ZERO, task.getItems().get(0).getRemainingReceivableQuantity());
    }

    @Test
    void fullyReceivedOrderDoesNotReappear() {
        PurchaseOrder order = order(PurchaseOrderStatus.FINANCE_APPROVED);
        when(orderRepository.findByIdAndFactoryId(PO_ID, FACTORY)).thenReturn(Optional.of(order));
        when(itemRepository.findByPurchaseOrderId(PO_ID)).thenReturn(List.of(item("10", "10")));
        when(receiveRepository.findByFactoryIdAndPurchaseOrderIdOrderByCreatedAtAsc(FACTORY, PO_ID))
                .thenReturn(List.of());

        assertTrue(service.getPendingReceivingTasks(FACTORY, PO_ID, null).isEmpty());
    }

    @Test
    void secondActiveDraftIsRejectedBeforeAnyWrite() {
        PurchaseOrder order = order(PurchaseOrderStatus.FINANCE_APPROVED);
        Supplier supplier = new Supplier();
        supplier.setId(SUPPLIER_ID);
        supplier.setFactoryId(FACTORY);
        when(supplierRepository.findByIdAndFactoryId(SUPPLIER_ID, FACTORY)).thenReturn(Optional.of(supplier));
        when(orderRepository.findByIdAndFactoryIdForUpdate(PO_ID, FACTORY)).thenReturn(Optional.of(order));
        when(itemRepository.findByPurchaseOrderId(PO_ID)).thenReturn(List.of(item("10", "0")));
        when(receiveRepository.findByFactoryIdAndPurchaseOrderIdOrderByCreatedAtAsc(FACTORY, PO_ID))
                .thenReturn(List.of(receipt("RCV-EXISTING", PurchaseReceiveStatus.DRAFT, "5")));

        BusinessException error = assertThrows(BusinessException.class,
                () -> service.createReceiveRecord(FACTORY, request("5"), 1309L));

        assertEquals(409, error.getCode());
        assertTrue(error.getMessage().contains("RCV-EXISTING"));
        verify(receiveRepository, never()).save(any());
    }

    @Test
    void receiveHistoryIsScopedByFactoryAndOrder() {
        PurchaseOrder order = order(PurchaseOrderStatus.FINANCE_APPROVED);
        PurchaseReceiveRecord record = receipt("RCV-SCOPED", PurchaseReceiveStatus.CONFIRMED, "10");
        when(orderRepository.findById(PO_ID)).thenReturn(Optional.of(order));
        when(receiveRepository.findByFactoryIdAndPurchaseOrderIdOrderByCreatedAtAsc(FACTORY, PO_ID))
                .thenReturn(List.of(record));

        assertEquals(List.of(record), service.getReceiveRecordsByOrder(FACTORY, PO_ID));
        verify(receiveRepository).findByFactoryIdAndPurchaseOrderIdOrderByCreatedAtAsc(FACTORY, PO_ID);
        verify(receiveRepository, never()).findByPurchaseOrderId(anyString());
    }

    @Test
    void receiveHistoryRejectsCrossFactoryOrderBeforeReadingRecords() {
        PurchaseOrder otherFactoryOrder = order(PurchaseOrderStatus.FINANCE_APPROVED);
        otherFactoryOrder.setFactoryId("OTHER");
        when(orderRepository.findById(PO_ID)).thenReturn(Optional.of(otherFactoryOrder));

        BusinessException error = assertThrows(BusinessException.class,
                () -> service.getReceiveRecordsByOrder(FACTORY, PO_ID));

        assertEquals(403, error.getCode());
        verifyNoInteractions(receiveRepository);
    }

    private PurchaseOrder order(PurchaseOrderStatus status) {
        PurchaseOrder order = new PurchaseOrder();
        order.setId(PO_ID);
        order.setFactoryId(FACTORY);
        order.setOrderNumber("PO-20260721-0001");
        order.setSupplierId(SUPPLIER_ID);
        order.setSupplierName("R3供应商");
        order.setExpectedDeliveryDate(LocalDate.of(2026, 7, 22));
        order.setStatus(status);
        return order;
    }

    private PurchaseOrderItem item(String ordered, String received) {
        PurchaseOrderItem item = new PurchaseOrderItem();
        item.setId(1L);
        item.setPurchaseOrderId(PO_ID);
        item.setMaterialTypeId(MATERIAL_ID);
        item.setMaterialName("R3原料A");
        item.setQuantity(new BigDecimal(ordered));
        item.setReceivedQuantity(new BigDecimal(received));
        item.setUnit("kg");
        return item;
    }

    private PurchaseReceiveRecord receipt(String number, PurchaseReceiveStatus status, String quantity) {
        PurchaseReceiveRecord record = new PurchaseReceiveRecord();
        record.setId("receipt-" + number);
        record.setFactoryId(FACTORY);
        record.setPurchaseOrderId(PO_ID);
        record.setReceiveNumber(number);
        record.setStatus(status);
        PurchaseReceiveItem item = new PurchaseReceiveItem();
        item.setMaterialTypeId(MATERIAL_ID);
        item.setMaterialName("R3原料A");
        item.setReceivedQuantity(new BigDecimal(quantity));
        item.setUnit("kg");
        record.setItems(List.of(item));
        return record;
    }

    private CreateReceiveRecordRequest request(String quantity) {
        CreateReceiveRecordRequest request = new CreateReceiveRecordRequest();
        request.setPurchaseOrderId(PO_ID);
        request.setSupplierId(SUPPLIER_ID);
        request.setReceiveDate(LocalDate.of(2026, 7, 22));
        CreateReceiveRecordRequest.ReceiveItemDTO line = new CreateReceiveRecordRequest.ReceiveItemDTO();
        line.setMaterialTypeId(MATERIAL_ID);
        line.setMaterialName("R3原料A");
        line.setReceivedQuantity(new BigDecimal(quantity));
        line.setUnit("kg");
        request.setItems(List.of(line));
        return request;
    }
}
