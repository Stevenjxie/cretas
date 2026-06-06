package com.cretas.aims.service.inventory.impl;

import com.cretas.aims.dto.inventory.CreatePurchaseOrderRequest;
import com.cretas.aims.entity.Supplier;
import com.cretas.aims.entity.inventory.PurchaseOrder;
import com.cretas.aims.entity.inventory.PurchaseOrderItem;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("PurchaseServiceImpl createPurchaseOrder nullable unitPrice")
class PurchaseServiceImplCreateOrderNullablePriceTest {

    @Mock private PurchaseOrderRepository purchaseOrderRepository;
    @Mock private PurchaseOrderItemRepository purchaseOrderItemRepository;
    @Mock private SupplierRepository supplierRepository;
    @Mock private RawMaterialTypeRepository materialTypeRepository;

    private PurchaseServiceImpl service;

    private static final String FACTORY = "RES_3101_009";
    private static final String SUPPLIER_ID = "SUP-001";

    @BeforeEach
    void setUp() {
        service = new PurchaseServiceImpl(
                purchaseOrderRepository,
                purchaseOrderItemRepository,
                null,
                supplierRepository,
                materialTypeRepository,
                null,
                null,
                null,
                null,
                null);
    }

    @Test
    @DisplayName("chef requisition converted before pricing: null unitPrice should create draft PO, not NPE")
    void createPurchaseOrder_nullUnitPrice_skipsAmountAggregation() {
        Supplier supplier = new Supplier();
        supplier.setId(SUPPLIER_ID);
        supplier.setFactoryId(FACTORY);
        when(supplierRepository.findByIdAndFactoryId(SUPPLIER_ID, FACTORY))
                .thenReturn(Optional.of(supplier));
        when(purchaseOrderRepository.countByFactoryIdAndDate(any(), any(LocalDate.class)))
                .thenReturn(0L);
        when(purchaseOrderRepository.save(any(PurchaseOrder.class))).thenAnswer(inv -> {
            PurchaseOrder order = inv.getArgument(0);
            if (order.getId() == null) order.setId("PO-uuid-001");
            return order;
        });
        when(purchaseOrderItemRepository.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));

        CreatePurchaseOrderRequest.PurchaseOrderItemDTO item = new CreatePurchaseOrderRequest.PurchaseOrderItemDTO();
        item.setMaterialTypeId("rm_qhj_002");
        item.setMaterialName("青花椒");
        item.setQuantity(new BigDecimal("0.75"));
        item.setUnit("kg");
        item.setUnitPrice(null);

        CreatePurchaseOrderRequest request = new CreatePurchaseOrderRequest();
        request.setSupplierId(SUPPLIER_ID);
        request.setOrderDate(LocalDate.of(2026, 6, 6));
        request.setItems(List.of(item));

        PurchaseOrder order = service.createPurchaseOrder(FACTORY, request, 1610L);

        assertEquals(BigDecimal.ZERO, order.getTotalAmount());
        assertEquals(BigDecimal.ZERO, order.getTaxAmount());

        ArgumentCaptor<Iterable<PurchaseOrderItem>> itemsCaptor = ArgumentCaptor.forClass(Iterable.class);
        verify(purchaseOrderItemRepository).saveAll(itemsCaptor.capture());
        PurchaseOrderItem savedItem = itemsCaptor.getValue().iterator().next();
        assertEquals("rm_qhj_002", savedItem.getMaterialTypeId());
        assertEquals(new BigDecimal("0.75"), savedItem.getQuantity());
        assertNull(savedItem.getUnitPrice(), "unknown purchase price must remain null, not fake zero");
        assertNull(savedItem.getLineAmount(), "line amount remains unknown until采购补价");
    }
}
