package com.cretas.aims.service.orchestration;

import com.cretas.aims.entity.ProductionBatch;
import com.cretas.aims.entity.ProductType;
import com.cretas.aims.entity.enums.ProductionBatchStatus;
import com.cretas.aims.entity.factory.WarehouseCodes;
import com.cretas.aims.entity.inventory.FinishedGoodsBatch;
import com.cretas.aims.event.BatchCompletedEvent;
import com.cretas.aims.repository.FactorySettingsRepository;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.repository.ProductionBatchRepository;
import com.cretas.aims.repository.ProductionPlanRepository;
import com.cretas.aims.repository.inventory.FinishedGoodsBatchRepository;
import com.cretas.aims.service.BatchConsumptionService;
import com.cretas.aims.service.LinkArrayService;
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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * SP10 §RD-1: 研发/中试库 — 试制批次完工产出进 WH-RD，不进可售车间仓 WH-WKS。
 *
 * <p>覆盖 3 个核心分支:
 * <ol>
 *   <li>is_trial=false → 常规批次进 WH-WKS（回归：不破坏现有行为）</li>
 *   <li>is_trial=true  → 试制批次进 WH-RD（主路径）</li>
 *   <li>is_trial=true  → 研发库 remark 含 [RD-1] 标识</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("SP10 §RD-1 SupplyChainOrchestrator 研发/中试库路由")
class SupplyChainOrchestratorRdTrialWarehouseTest {

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

    // ---------- helpers ----------

    private ProductionBatch makeBatch(long batchId, boolean isTrial) {
        ProductionBatch b = new ProductionBatch();
        b.setId(batchId);
        b.setFactoryId("F006");
        b.setProductTypeId("PT-TONGUE");
        b.setProductName("猪舌120g");
        b.setGoodQuantity(new BigDecimal("100"));
        b.setUnit("kg");
        b.setIsTrial(isTrial);
        b.setStatus(ProductionBatchStatus.COMPLETED);
        return b;
    }

    private void stubCommon(ProductionBatch batch) {
        when(factorySettingsRepository.findSkipProcessReportingDefaultByFactoryId(batch.getFactoryId()))
                .thenReturn(false);
        when(finishedGoodsBatchRepository
                .findByFactoryIdAndBatchNumber(anyString(), anyString()))
                .thenReturn(Optional.empty());
        when(productTypeRepository.findById(batch.getProductTypeId()))
                .thenReturn(Optional.empty());   // kg 产品, 不换算
        when(finishedGoodsBatchRepository.save(any(FinishedGoodsBatch.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        when(warehouseResolver.resolveWorkshopId(anyString())).thenReturn("WH-WKS-F006-ID");
        when(warehouseResolver.resolveRdId(anyString())).thenReturn("WH-RD-F006-ID");
        when(productionBatchRepository.countByProductionPlanIdAndStatusNot(any(), any()))
                .thenReturn(0L);
    }

    // ---------- tests ----------

    @Test
    @DisplayName("T-RD-1: is_trial=false → 常规批次进 WH-WKS (回归)")
    void regularBatchGoesToWorkshop() {
        ProductionBatch batch = makeBatch(1L, false);
        stubCommon(batch);

        orchestrator.onBatchCompleted(new BatchCompletedEvent(this, batch));

        ArgumentCaptor<FinishedGoodsBatch> cap = ArgumentCaptor.forClass(FinishedGoodsBatch.class);
        verify(finishedGoodsBatchRepository).save(cap.capture());
        FinishedGoodsBatch fg = cap.getValue();

        assertThat(fg.getWarehouseId())
                .as("常规批次必须进 WH-WKS 车间仓, 不进研发库")
                .isEqualTo("WH-WKS-F006-ID");
        assertThat(fg.getRemark())
                .as("常规批次 remark 不含 [RD-1]")
                .isNullOrEmpty();
    }

    @Test
    @DisplayName("T-RD-2: is_trial=true → 试制批次进 WH-RD 研发库")
    void trialBatchGoesToRdWarehouse() {
        ProductionBatch batch = makeBatch(2L, true);
        stubCommon(batch);

        orchestrator.onBatchCompleted(new BatchCompletedEvent(this, batch));

        ArgumentCaptor<FinishedGoodsBatch> cap = ArgumentCaptor.forClass(FinishedGoodsBatch.class);
        verify(finishedGoodsBatchRepository).save(cap.capture());
        FinishedGoodsBatch fg = cap.getValue();

        assertThat(fg.getWarehouseId())
                .as("试制批次必须进 WH-RD 研发/中试库，不进 WH-WKS 可售车间仓")
                .isEqualTo("WH-RD-F006-ID");
    }

    @Test
    @DisplayName("T-RD-3: is_trial=true → FG remark 含 [RD-1] 防呆标识")
    void trialBatchFgRemarkContainsRdTag() {
        ProductionBatch batch = makeBatch(3L, true);
        stubCommon(batch);

        orchestrator.onBatchCompleted(new BatchCompletedEvent(this, batch));

        ArgumentCaptor<FinishedGoodsBatch> cap = ArgumentCaptor.forClass(FinishedGoodsBatch.class);
        verify(finishedGoodsBatchRepository).save(cap.capture());
        FinishedGoodsBatch fg = cap.getValue();

        assertThat(fg.getRemark())
                .as("试制批次 FG remark 须含 [RD-1] 防呆标识，明示研发库来源")
                .contains("[RD-1]");
        assertThat(fg.getRemark())
                .contains("试制批次");
    }
}
