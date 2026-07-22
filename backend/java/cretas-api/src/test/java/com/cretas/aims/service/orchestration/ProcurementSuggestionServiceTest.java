package com.cretas.aims.service.orchestration;

import com.cretas.aims.dto.orchestration.MaterialShortfall;
import com.cretas.aims.entity.RawMaterialType;
import com.cretas.aims.entity.inventory.PurchaseOrder;
import com.cretas.aims.entity.inventory.PurchaseOrderItem;
import com.cretas.aims.repository.RawMaterialTypeRepository;
import com.cretas.aims.repository.inventory.PurchaseOrderItemRepository;
import com.cretas.aims.repository.inventory.PurchaseOrderRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProcurementSuggestionServiceTest {

    @Mock private PurchaseOrderRepository purchaseOrderRepository;
    @Mock private PurchaseOrderItemRepository purchaseOrderItemRepository;
    @Mock private RawMaterialTypeRepository rawMaterialTypeRepository;
    @InjectMocks private ProcurementSuggestionService service;

    @Test
    void generatedDraftCarriesExplicitQuantityAndPriceUnitContract() {
        PurchaseOrder savedOrder = new PurchaseOrder();
        savedOrder.setId("PO-1");
        when(purchaseOrderRepository.save(any(PurchaseOrder.class))).thenReturn(savedOrder);

        RawMaterialType material = new RawMaterialType();
        material.setName("鸡肉");
        material.setUnit("kg");
        when(rawMaterialTypeRepository.findById("MAT-1")).thenReturn(Optional.of(material));

        MaterialShortfall shortfall = new MaterialShortfall(
                "MAT-1", "鸡肉", new BigDecimal("12"), new BigDecimal("2"), new BigDecimal("10"));

        service.generateSuggestions("F006", "PLAN-1", List.of(shortfall));

        ArgumentCaptor<PurchaseOrderItem> itemCaptor = ArgumentCaptor.forClass(PurchaseOrderItem.class);
        verify(purchaseOrderItemRepository).save(itemCaptor.capture());
        PurchaseOrderItem item = itemCaptor.getValue();
        assertEquals("kg", item.getUnit());
        assertEquals("kg", item.getPriceUnit());
        assertEquals(BigDecimal.ONE, item.getQuantityToPriceFactor());
    }
}
