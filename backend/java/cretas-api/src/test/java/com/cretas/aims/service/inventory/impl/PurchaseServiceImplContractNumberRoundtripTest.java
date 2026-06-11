package com.cretas.aims.service.inventory.impl;

import com.cretas.aims.dto.inventory.CreatePurchaseOrderRequest;
import com.cretas.aims.dto.inventory.UpdatePurchaseOrderRequest;
import com.cretas.aims.entity.Supplier;
import com.cretas.aims.entity.enums.PurchaseOrderStatus;
import com.cretas.aims.entity.enums.SettlementType;
import com.cretas.aims.entity.inventory.PurchaseOrder;
import com.cretas.aims.repository.RawMaterialTypeRepository;
import com.cretas.aims.repository.SupplierRepository;
import com.cretas.aims.repository.inventory.PurchaseOrderItemRepository;
import com.cretas.aims.repository.inventory.PurchaseOrderRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * DTO-roundtrip 全链接通测试 (per memory feedback_dto_roundtrip_silent_drop):
 *
 * <p>验证 contractNumber / settlementType / invoiceReminderDays 3 个字段在以下 4 处都正确处理:
 * <ol>
 *   <li>CreatePurchaseOrderRequest 声明 → service.create 写入 entity</li>
 *   <li>UpdatePurchaseOrderRequest 声明 → service.update null-guard 写入 entity</li>
 *   <li>service.update 的 contractNumber="" 清除语义</li>
 *   <li>service.update 的 null 不更新语义（保留原值）</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PurchaseServiceImpl contractNumber/settlementType/invoiceReminderDays DTO roundtrip")
class PurchaseServiceImplContractNumberRoundtripTest {

    @Mock private PurchaseOrderRepository purchaseOrderRepository;
    @Mock private PurchaseOrderItemRepository purchaseOrderItemRepository;
    @Mock private SupplierRepository supplierRepository;
    @Mock private RawMaterialTypeRepository materialTypeRepository;

    private PurchaseServiceImpl service;

    private static final String FACTORY = "F006";
    private static final String SUPPLIER_ID = "SUP-001";
    private static final String ORDER_ID = "PO-TEST-001";

    @BeforeEach
    void setUp() {
        service = new PurchaseServiceImpl(
                purchaseOrderRepository,
                purchaseOrderItemRepository,
                null,  // receiveRecordRepository
                supplierRepository,
                materialTypeRepository,
                null,  // materialBatchRepository
                null,  // bomItemRepository
                null,  // arApService
                null,  // applicationEventPublisher
                null   // materialBatchService
        );
    }

    // ===== CREATE tests =====

    @Test
    @DisplayName("create: contractNumber persisted when provided")
    void create_contractNumberPersisted() {
        // Arrange
        Supplier supplier = new Supplier();
        supplier.setId(SUPPLIER_ID);
        when(supplierRepository.findByIdAndFactoryId(eq(SUPPLIER_ID), eq(FACTORY)))
                .thenReturn(Optional.of(supplier));

        PurchaseOrder saved = new PurchaseOrder();
        saved.setId(ORDER_ID);
        when(purchaseOrderRepository.save(any(PurchaseOrder.class))).thenReturn(saved);
        when(purchaseOrderItemRepository.saveAll(any())).thenReturn(List.of());

        CreatePurchaseOrderRequest req = buildCreateRequest();
        req.setContractNumber("HT-2026-001");
        req.setSettlementType("MONTHLY");
        req.setInvoiceReminderDays(30);

        // Act
        service.createPurchaseOrder(FACTORY, req, 1L);

        // Assert — service calls save() twice (first to get ID, second to set totalAmount).
        // Check the first capture which has contractNumber/settlementType/invoiceReminderDays set.
        ArgumentCaptor<PurchaseOrder> captor = ArgumentCaptor.forClass(PurchaseOrder.class);
        verify(purchaseOrderRepository, times(2)).save(captor.capture());
        PurchaseOrder captured = captor.getAllValues().get(0);

        assertEquals("HT-2026-001", captured.getContractNumber(),
                "contractNumber must be persisted from CreateRequest");
        assertEquals(SettlementType.MONTHLY, captured.getSettlementType(),
                "settlementType must be parsed and persisted from CreateRequest");
        assertEquals(30, captured.getInvoiceReminderDays(),
                "invoiceReminderDays must be persisted from CreateRequest");
    }

    @Test
    @DisplayName("create: contractNumber null when not provided (no silent default)")
    void create_contractNumberNullWhenAbsent() {
        Supplier supplier = new Supplier();
        supplier.setId(SUPPLIER_ID);
        when(supplierRepository.findByIdAndFactoryId(eq(SUPPLIER_ID), eq(FACTORY)))
                .thenReturn(Optional.of(supplier));
        PurchaseOrder saved = new PurchaseOrder();
        saved.setId(ORDER_ID);
        when(purchaseOrderRepository.save(any())).thenReturn(saved);
        when(purchaseOrderItemRepository.saveAll(any())).thenReturn(List.of());

        CreatePurchaseOrderRequest req = buildCreateRequest();
        // contractNumber, settlementType, invoiceReminderDays all left null

        service.createPurchaseOrder(FACTORY, req, 1L);

        ArgumentCaptor<PurchaseOrder> captor = ArgumentCaptor.forClass(PurchaseOrder.class);
        verify(purchaseOrderRepository, times(2)).save(captor.capture());
        PurchaseOrder captured = captor.getAllValues().get(0);

        assertNull(captured.getContractNumber(), "contractNumber must be null when not provided");
        assertNull(captured.getSettlementType(), "settlementType must be null when not provided");
        assertNull(captured.getInvoiceReminderDays(), "invoiceReminderDays must be null when not provided");
    }

    // ===== UPDATE tests =====

    @Test
    @DisplayName("update: contractNumber updated when provided")
    void update_contractNumberUpdated() {
        PurchaseOrder existingOrder = buildExistingOrder();
        existingOrder.setContractNumber("OLD-HT-001");
        when(purchaseOrderRepository.findById(eq(ORDER_ID))).thenReturn(Optional.of(existingOrder));
        when(purchaseOrderRepository.save(any())).thenReturn(existingOrder);

        UpdatePurchaseOrderRequest req = new UpdatePurchaseOrderRequest();
        req.setContractNumber("NEW-HT-2026-002");
        req.setSettlementType("CREDIT_PERIOD");
        req.setInvoiceReminderDays(15);

        service.updateDraftOrder(FACTORY, ORDER_ID, req);

        ArgumentCaptor<PurchaseOrder> captor = ArgumentCaptor.forClass(PurchaseOrder.class);
        verify(purchaseOrderRepository).save(captor.capture());
        PurchaseOrder saved = captor.getValue();

        assertEquals("NEW-HT-2026-002", saved.getContractNumber(),
                "contractNumber must be updated");
        assertEquals(SettlementType.CREDIT_PERIOD, saved.getSettlementType(),
                "settlementType must be updated");
        assertEquals(15, saved.getInvoiceReminderDays(),
                "invoiceReminderDays must be updated");
    }

    @Test
    @DisplayName("update: contractNumber null in request = no update (existing value preserved)")
    void update_contractNumberNullMeansNoUpdate() {
        PurchaseOrder existingOrder = buildExistingOrder();
        existingOrder.setContractNumber("KEEP-ME");
        existingOrder.setSettlementType(SettlementType.PREPAID);
        existingOrder.setInvoiceReminderDays(7);
        when(purchaseOrderRepository.findById(eq(ORDER_ID))).thenReturn(Optional.of(existingOrder));
        when(purchaseOrderRepository.save(any())).thenReturn(existingOrder);

        UpdatePurchaseOrderRequest req = new UpdatePurchaseOrderRequest();
        // contractNumber, settlementType, invoiceReminderDays all left null → no update

        service.updateDraftOrder(FACTORY, ORDER_ID, req);

        ArgumentCaptor<PurchaseOrder> captor = ArgumentCaptor.forClass(PurchaseOrder.class);
        verify(purchaseOrderRepository).save(captor.capture());
        PurchaseOrder saved = captor.getValue();

        assertEquals("KEEP-ME", saved.getContractNumber(),
                "contractNumber must be preserved when not in request");
        assertEquals(SettlementType.PREPAID, saved.getSettlementType(),
                "settlementType must be preserved when not in request");
        assertEquals(7, saved.getInvoiceReminderDays(),
                "invoiceReminderDays must be preserved when not in request");
    }

    @Test
    @DisplayName("update: contractNumber empty string = clear (set to null)")
    void update_contractNumberEmptyStringClears() {
        PurchaseOrder existingOrder = buildExistingOrder();
        existingOrder.setContractNumber("OLD-CONTRACT");
        when(purchaseOrderRepository.findById(eq(ORDER_ID))).thenReturn(Optional.of(existingOrder));
        when(purchaseOrderRepository.save(any())).thenReturn(existingOrder);

        UpdatePurchaseOrderRequest req = new UpdatePurchaseOrderRequest();
        req.setContractNumber("");  // explicit clear

        service.updateDraftOrder(FACTORY, ORDER_ID, req);

        ArgumentCaptor<PurchaseOrder> captor = ArgumentCaptor.forClass(PurchaseOrder.class);
        verify(purchaseOrderRepository).save(captor.capture());
        PurchaseOrder saved = captor.getValue();

        assertNull(saved.getContractNumber(),
                "empty-string contractNumber must clear the field (set to null)");
    }

    // ===== helpers =====

    private CreatePurchaseOrderRequest buildCreateRequest() {
        CreatePurchaseOrderRequest req = new CreatePurchaseOrderRequest();
        req.setSupplierId(SUPPLIER_ID);
        req.setPurchaseType("DIRECT");
        req.setOrderDate(LocalDate.now());
        CreatePurchaseOrderRequest.PurchaseOrderItemDTO item = new CreatePurchaseOrderRequest.PurchaseOrderItemDTO();
        item.setMaterialTypeId("MAT-001");
        item.setQuantity(BigDecimal.TEN);
        item.setUnit("kg");
        req.setItems(List.of(item));
        return req;
    }

    private PurchaseOrder buildExistingOrder() {
        PurchaseOrder order = new PurchaseOrder();
        order.setId(ORDER_ID);
        order.setFactoryId(FACTORY);
        order.setSupplierId(SUPPLIER_ID);
        order.setPurchaseType(com.cretas.aims.entity.enums.PurchaseType.DIRECT);
        order.setOrderDate(LocalDate.now());
        order.setStatus(PurchaseOrderStatus.DRAFT);
        order.setCreatedBy(1L);
        order.setVersion(0L);
        order.setTotalAmount(BigDecimal.ZERO);
        order.setTaxAmount(BigDecimal.ZERO);
        return order;
    }
}
