package com.cretas.aims.service.orchestration;

import com.cretas.aims.dto.orchestration.MaterialShortfall;
import com.cretas.aims.entity.ProductionPlan;
import com.cretas.aims.entity.RawMaterialType;
import com.cretas.aims.entity.enums.MaterialSupplyMode;
import com.cretas.aims.entity.inventory.PurchaseRequisition;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.ProductionPlanRepository;
import com.cretas.aims.repository.RawMaterialTypeRepository;
import com.cretas.aims.repository.inventory.PurchaseRequisitionRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProcurementSuggestionServiceTest {

    @Mock private PurchaseRequisitionRepository requisitionRepository;
    @Mock private RawMaterialTypeRepository rawMaterialTypeRepository;
    @Mock private ProductionPlanRepository productionPlanRepository;
    @InjectMocks private ProcurementSuggestionService service;

    @Test
    void shortageCreatesReviewableRequisitionInsteadOfPlaceholderPurchaseOrder() {
        ProductionPlan plan = plan();
        when(productionPlanRepository.findByIdForUpdate("PLAN-1")).thenReturn(Optional.of(plan));
        when(requisitionRepository.findByFactoryIdAndSourceTypeAndSourceId(
                "F006", ProcurementSuggestionService.SOURCE_TYPE, "PLAN-1"))
                .thenReturn(Optional.empty());
        when(requisitionRepository.countByFactoryIdAndDate(any(), any(LocalDate.class))).thenReturn(0L);

        RawMaterialType material = new RawMaterialType();
        material.setId("MAT-1");
        material.setFactoryId("F006");
        material.setName("鸡肉");
        material.setUnit("kg");
        when(rawMaterialTypeRepository.findById("MAT-1")).thenReturn(Optional.of(material));
        when(requisitionRepository.save(any(PurchaseRequisition.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        MaterialShortfall shortfall = new MaterialShortfall(
                "MAT-1", "鸡肉", new BigDecimal("12"), new BigDecimal("2"), new BigDecimal("10"));

        service.generateSuggestions("F006", "PLAN-1", List.of(shortfall));

        ArgumentCaptor<PurchaseRequisition> captor = ArgumentCaptor.forClass(PurchaseRequisition.class);
        verify(requisitionRepository).save(captor.capture());
        PurchaseRequisition requisition = captor.getValue();
        assertEquals("PRODUCTION_PLAN_SHORTAGE", requisition.getSourceType());
        assertEquals("PLAN-1", requisition.getSourceId());
        assertEquals(1, requisition.getRequestedItems().size());
        Map<String, Object> item = requisition.getRequestedItems().get(0);
        assertEquals(new BigDecimal("10"), item.get("shortfallQuantity"));
        assertEquals("kg", item.get("unit"));
        assertEquals("SO-1", item.get("sourceSalesOrderId"));
    }

    @Test
    void repeatedGenerationReturnsSameRequisitionWithoutDuplicateWrite() {
        ProductionPlan plan = plan();
        PurchaseRequisition existing = new PurchaseRequisition();
        existing.setId("REQ-1");
        when(productionPlanRepository.findByIdForUpdate("PLAN-1")).thenReturn(Optional.of(plan));
        when(requisitionRepository.findByFactoryIdAndSourceTypeAndSourceId(
                "F006", ProcurementSuggestionService.SOURCE_TYPE, "PLAN-1"))
                .thenReturn(Optional.of(existing));

        PurchaseRequisition result = service.generateSuggestions("F006", "PLAN-1", List.of(
                new MaterialShortfall("MAT-1", "鸡肉", BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ONE)));

        assertSame(existing, result);
        verify(requisitionRepository, never()).save(any(PurchaseRequisition.class));
    }

    @Test
    void customerSuppliedShortageNeverCreatesFactoryPurchaseDemand() {
        ProductionPlan plan = plan();
        plan.setMaterialSupplyMode(MaterialSupplyMode.CUSTOMER_SUPPLIED);
        when(productionPlanRepository.findByIdForUpdate("PLAN-1")).thenReturn(Optional.of(plan));

        BusinessException error = assertThrows(BusinessException.class, () ->
                service.generateSuggestions("F006", "PLAN-1", List.of(
                        new MaterialShortfall("MAT-1", "客供原料", BigDecimal.ONE,
                                BigDecimal.ZERO, BigDecimal.ONE))));

        assertEquals("CUSTOMER_SUPPLIED_SHORTAGE_PURCHASE_FORBIDDEN", error.getErrorCode());
        verify(requisitionRepository, never()).save(any(PurchaseRequisition.class));
    }

    private ProductionPlan plan() {
        ProductionPlan plan = new ProductionPlan();
        plan.setId("PLAN-1");
        plan.setFactoryId("F006");
        plan.setPlanNumber("PLAN-001");
        plan.setSourceOrderId("SO-1");
        plan.setSourceOrderItemId("ITEM-1");
        plan.setPlannedDate(LocalDate.of(2026, 7, 23));
        return plan;
    }
}
