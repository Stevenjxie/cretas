package com.cretas.aims.service.inventory.impl;

import com.cretas.aims.dto.inventory.CreatePurchaseOrderRequest;
import com.cretas.aims.entity.enums.PurchaseOrderStatus;
import com.cretas.aims.entity.inventory.PurchaseOrder;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.MaterialBatchRepository;
import com.cretas.aims.repository.RawMaterialTypeRepository;
import com.cretas.aims.repository.SupplierRepository;
import com.cretas.aims.repository.bom.BomRecipeItemRepository;
import com.cretas.aims.repository.inventory.PurchaseOrderItemRepository;
import com.cretas.aims.repository.inventory.PurchaseOrderRepository;
import com.cretas.aims.repository.inventory.PurchaseReceiveRecordRepository;
import com.cretas.aims.service.MaterialBatchService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PurchaseOrderDraftSupplierTest {

    private PurchaseOrderRepository orderRepository;
    private SupplierRepository supplierRepository;
    private PurchaseServiceImpl service;

    @BeforeEach
    void setUp() {
        orderRepository = mock(PurchaseOrderRepository.class);
        supplierRepository = mock(SupplierRepository.class);
        service = new PurchaseServiceImpl(
                orderRepository,
                mock(PurchaseOrderItemRepository.class),
                mock(PurchaseReceiveRecordRepository.class),
                supplierRepository,
                mock(RawMaterialTypeRepository.class),
                mock(MaterialBatchRepository.class),
                mock(BomRecipeItemRepository.class),
                mock(com.cretas.aims.service.finance.ArApService.class),
                mock(ApplicationEventPublisher.class),
                mock(MaterialBatchService.class));
        ReflectionTestUtils.setField(service, "self", service);
    }

    @Test
    void createsDraftWithoutSupplierAndDefersSupplierValidation() {
        when(orderRepository.save(any(PurchaseOrder.class))).thenAnswer(invocation -> invocation.getArgument(0));
        CreatePurchaseOrderRequest request = requestWithoutSupplier();

        PurchaseOrder result = service.createPurchaseOrder("F006", request, 9L);

        assertThat(result.getStatus()).isEqualTo(PurchaseOrderStatus.DRAFT);
        assertThat(result.getSupplierId()).isNull();
        verify(supplierRepository, never()).findByIdAndFactoryId(any(), any());
    }

    @Test
    void rejectsSubmittingDraftUntilSupplierIsSelected() {
        PurchaseOrder order = new PurchaseOrder();
        order.setId("PO-1");
        order.setFactoryId("F006");
        order.setStatus(PurchaseOrderStatus.DRAFT);
        when(orderRepository.findById("PO-1")).thenReturn(Optional.of(order));

        assertThatThrownBy(() -> service.submitOrder("F006", "PO-1"))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo("PURCHASE_SUPPLIER_REQUIRED");
    }

    private static CreatePurchaseOrderRequest requestWithoutSupplier() {
        CreatePurchaseOrderRequest.PurchaseOrderItemDTO item = new CreatePurchaseOrderRequest.PurchaseOrderItemDTO();
        item.setMaterialTypeId("MAT-1");
        item.setMaterialName("salt");
        item.setQuantity(BigDecimal.ONE);
        item.setUnit("kg");
        item.setUnitPrice(BigDecimal.ONE);

        CreatePurchaseOrderRequest request = new CreatePurchaseOrderRequest();
        request.setSupplierId(null);
        request.setPurchaseType("DIRECT");
        request.setOrderDate(LocalDate.of(2026, 7, 16));
        request.setItems(List.of(item));
        return request;
    }
}
