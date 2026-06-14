package com.cretas.aims.service.orchestration;

import com.cretas.aims.entity.ProductionBatch;
import com.cretas.aims.entity.enums.ProductionBatchStatus;
import com.cretas.aims.entity.inventory.FinishedGoodsBatch;
import com.cretas.aims.event.BatchCompletedEvent;
import com.cretas.aims.repository.FactorySettingsRepository;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.repository.ProductionBatchRepository;
import com.cretas.aims.repository.ProductionPlanRepository;
import com.cretas.aims.repository.inventory.FinishedGoodsBatchRepository;
import com.cretas.aims.service.BatchConsumptionService;
import com.cretas.aims.service.QualityInspectionService;
import com.cretas.aims.service.factory.WarehouseResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("SupplyChainOrchestrator production settlement FG guard")
class SupplyChainOrchestratorSettlementGuardTest {

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
    @Mock private WarehouseResolver warehouseResolver;
    @Mock private FactorySettingsRepository factorySettingsRepository;

    @InjectMocks
    private SupplyChainOrchestrator orchestrator;

    @BeforeEach
    void injectFieldDependencies() {
        ReflectionTestUtils.setField(orchestrator, "warehouseResolver", warehouseResolver);
        ReflectionTestUtils.setField(orchestrator, "factorySettingsRepository", factorySettingsRepository);
    }

    @Test
    @DisplayName("F006 batch completion with settlement mode consumes materials but skips automatic FG")
    void batchCompleted_settlementFactorySkipsAutomaticFinishedGoodsButKeepsConsumption() {
        ProductionBatch batch = completedBatch(101L, "F006");
        when(factorySettingsRepository.findSkipProcessReportingDefaultByFactoryId("F006")).thenReturn(true);

        orchestrator.onBatchCompleted(new BatchCompletedEvent(this, batch));

        verify(batchConsumptionService).autoConsumeForBatch(batch);
        verify(finishedGoodsBatchRepository, never()).findByFactoryIdAndBatchNumber(anyString(), anyString());
        verify(finishedGoodsBatchRepository, never()).save(any(FinishedGoodsBatch.class));
    }

    @Test
    @DisplayName("Non-settlement factory batch completion still creates automatic FG")
    void batchCompleted_nonSettlementFactoryStillCreatesAutomaticFinishedGoods() {
        ProductionBatch batch = completedBatch(102L, "F001");
        when(factorySettingsRepository.findSkipProcessReportingDefaultByFactoryId("F001")).thenReturn(false);
        when(finishedGoodsBatchRepository.findByFactoryIdAndBatchNumber(anyString(), anyString()))
                .thenReturn(Optional.empty());
        when(productTypeRepository.findById(batch.getProductTypeId())).thenReturn(Optional.empty());
        when(warehouseResolver.resolveWorkshopId("F001")).thenReturn("WH-WKS-F001");
        when(finishedGoodsBatchRepository.save(any(FinishedGoodsBatch.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        orchestrator.onBatchCompleted(new BatchCompletedEvent(this, batch));

        verify(batchConsumptionService).autoConsumeForBatch(batch);
        ArgumentCaptor<FinishedGoodsBatch> cap = ArgumentCaptor.forClass(FinishedGoodsBatch.class);
        verify(finishedGoodsBatchRepository).save(cap.capture());
        assertThat(cap.getValue().getFactoryId()).isEqualTo("F001");
        assertThat(cap.getValue().getProducedQuantity()).isEqualByComparingTo("25");
    }

    private ProductionBatch completedBatch(long batchId, String factoryId) {
        ProductionBatch batch = new ProductionBatch();
        batch.setId(batchId);
        batch.setFactoryId(factoryId);
        batch.setProductTypeId("PT-SETTLEMENT-GUARD");
        batch.setProductName("Settlement Guard Product");
        batch.setGoodQuantity(new BigDecimal("25"));
        batch.setActualQuantity(new BigDecimal("25"));
        batch.setUnit("kg");
        batch.setStatus(ProductionBatchStatus.COMPLETED);
        return batch;
    }
}
