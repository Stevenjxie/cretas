package com.cretas.aims.service.orchestration;

import com.cretas.aims.entity.enums.MaterialSupplyMode;
import com.cretas.aims.entity.inventory.SalesOrder;
import com.cretas.aims.event.SalesOrderFinanceApprovedEvent;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.repository.ProductionBatchRepository;
import com.cretas.aims.repository.ProductionPlanRepository;
import com.cretas.aims.repository.inventory.FinishedGoodsBatchRepository;
import com.cretas.aims.repository.inventory.SalesOrderRepository;
import com.cretas.aims.service.BatchConsumptionService;
import com.cretas.aims.service.QualityInspectionService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.util.Optional;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SupplyChainOrchestratorCustomerSuppliedGuardTest {

    @Mock private InventoryMatchingService inventoryMatchingService;
    @Mock private BomExpansionService bomExpansionService;
    @Mock private ProcurementSuggestionService procurementSuggestionService;
    @Mock private ProductionPlanRepository productionPlanRepository;
    @Mock private ProductionBatchRepository productionBatchRepository;
    @Mock private FinishedGoodsBatchRepository finishedGoodsBatchRepository;
    @Mock private ApplicationEventPublisher applicationEventPublisher;
    @Mock private QualityInspectionService qualityInspectionService;
    @Mock private BatchConsumptionService batchConsumptionService;
    @Mock private ProductTypeRepository productTypeRepository;
    @Mock private SalesOrderRepository salesOrderRepository;

    @InjectMocks
    private SupplyChainOrchestrator orchestrator;

    @Test
    void customerSuppliedOrderDoesNotReserveCompanyStockOrGenerateProcurement() {
        SalesOrder order = new SalesOrder();
        order.setId("SO-CUSTOMER-SUPPLIED");
        order.setFactoryId("F006");
        order.setMaterialSupplyMode(MaterialSupplyMode.CUSTOMER_SUPPLIED);
        when(salesOrderRepository.findById(order.getId())).thenReturn(Optional.of(order));

        orchestrator.onSalesOrderFinanceApproved(
                new SalesOrderFinanceApprovedEvent(this, "F006", order.getId(), 1309L));

        verify(salesOrderRepository).findById(order.getId());
        verifyNoInteractions(inventoryMatchingService, bomExpansionService, procurementSuggestionService);
        verify(productionPlanRepository, never()).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void crossFactoryOrderDoesNotEnterSupplyChain() {
        SalesOrder order = new SalesOrder();
        order.setId("SO-OTHER-FACTORY");
        order.setFactoryId("LIUSHANMEN");
        order.setMaterialSupplyMode(MaterialSupplyMode.FACTORY_SUPPLIED);
        when(salesOrderRepository.findById(order.getId())).thenReturn(Optional.of(order));

        orchestrator.onSalesOrderFinanceApproved(
                new SalesOrderFinanceApprovedEvent(this, "F006", order.getId(), 1309L));

        verifyNoInteractions(inventoryMatchingService, procurementSuggestionService);
    }
}
