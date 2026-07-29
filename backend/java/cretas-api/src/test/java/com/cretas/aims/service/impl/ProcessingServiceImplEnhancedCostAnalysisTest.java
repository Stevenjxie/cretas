package com.cretas.aims.service.impl;

import com.cretas.aims.entity.MaterialConsumption;
import com.cretas.aims.entity.ProductionBatch;
import com.cretas.aims.entity.ProductionPlan;
import com.cretas.aims.entity.QualityInspection;
import com.cretas.aims.repository.BatchEquipmentUsageRepository;
import com.cretas.aims.repository.BatchWorkSessionRepository;
import com.cretas.aims.repository.EquipmentAlertRepository;
import com.cretas.aims.repository.EquipmentRepository;
import com.cretas.aims.repository.MaterialBatchRepository;
import com.cretas.aims.repository.MaterialConsumptionRepository;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.repository.ProductionAlertRepository;
import com.cretas.aims.repository.ProductionBatchRepository;
import com.cretas.aims.repository.ProductionPlanBatchUsageRepository;
import com.cretas.aims.repository.ProductionPlanRepository;
import com.cretas.aims.repository.QualityInspectionRepository;
import com.cretas.aims.repository.RawMaterialTypeRepository;
import com.cretas.aims.repository.SupplierRepository;
import com.cretas.aims.repository.SystemLogRepository;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.service.AIAnalysisService;
import com.cretas.aims.service.CacheService;
import com.cretas.aims.service.ProcessingStageRecordService;
import com.cretas.aims.service.QualityInspectionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageImpl;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProcessingServiceImplEnhancedCostAnalysisTest {

    @Mock private ProductionBatchRepository productionBatchRepository;
    @Mock private MaterialBatchRepository materialBatchRepository;
    @Mock private MaterialConsumptionRepository materialConsumptionRepository;
    @Mock private QualityInspectionRepository qualityInspectionRepository;
    @Mock private EquipmentRepository equipmentRepository;
    @Mock private EquipmentAlertRepository equipmentAlertRepository;
    @Mock private BatchEquipmentUsageRepository batchEquipmentUsageRepository;
    @Mock private ProductionPlanRepository productionPlanRepository;
    @Mock private SystemLogRepository systemLogRepository;
    @Mock private RawMaterialTypeRepository rawMaterialTypeRepository;
    @Mock private BatchWorkSessionRepository batchWorkSessionRepository;
    @Mock private UserRepository userRepository;
    @Mock private SupplierRepository supplierRepository;
    @Mock private ProductTypeRepository productTypeRepository;
    @Mock private AIAnalysisService aiAnalysisService;
    @Mock private CacheService cacheService;
    @Mock private ProcessingStageRecordService processingStageRecordService;
    @Mock private ApplicationEventPublisher applicationEventPublisher;
    @Mock private QualityInspectionService qualityInspectionService;
    @Mock private ProductionAlertRepository productionAlertRepository;
    @Mock private ProductionPlanBatchUsageRepository productionPlanBatchUsageRepository;

    @InjectMocks
    private ProcessingServiceImpl service;

    private static final String FACTORY = "F006";
    private static final Long BATCH_ID = 9001L;

    @BeforeEach
    void commonStubs() {
        when(equipmentRepository.findByIdIn(any())).thenReturn(List.of());
        when(batchWorkSessionRepository.findByBatchId(BATCH_ID)).thenReturn(List.of());
        lenient().when(qualityInspectionRepository.findByFactoryIdAndProductionBatchId(eq(FACTORY), eq(BATCH_ID), any()))
                .thenReturn(new PageImpl<>(List.of()));
        when(processingStageRecordService.getByBatchIdWithComparison(FACTORY, BATCH_ID)).thenReturn(List.of());
    }

    @Test
    @DisplayName("enhanced cost falls back to production batch summary costs when detail rows are absent")
    void enhancedCost_usesBatchSummaryCosts_whenDetailsAbsent() {
        ProductionBatch batch = batch();
        batch.setMaterialCost(new BigDecimal("120.50"));
        batch.setLaborCost(new BigDecimal("30.25"));
        batch.setEquipmentCost(new BigDecimal("10.00"));
        batch.setOtherCost(new BigDecimal("5.00"));

        when(productionBatchRepository.findByIdAndFactoryId(BATCH_ID, FACTORY)).thenReturn(Optional.of(batch));
        when(materialConsumptionRepository.findByProductionBatchId(BATCH_ID)).thenReturn(List.of());
        when(batchEquipmentUsageRepository.findByBatchId(BATCH_ID)).thenReturn(List.of());

        Map<String, Object> result = service.getEnhancedBatchCostAnalysis(FACTORY, BATCH_ID.toString());

        Map<String, Object> costSummary = asMap(result.get("costSummary"));
        assertThat((BigDecimal) costSummary.get("materialCost")).isEqualByComparingTo("120.50");
        assertThat((BigDecimal) costSummary.get("laborCost")).isEqualByComparingTo("30.25");
        assertThat((BigDecimal) costSummary.get("equipmentCost")).isEqualByComparingTo("10.00");
        assertThat((BigDecimal) costSummary.get("totalCost")).isEqualByComparingTo("165.75");
        assertThat(result.get("materialConsumptionCount")).isEqualTo(1);
        assertThat(result.get("equipmentUsageCount")).isEqualTo(1);
    }

    @Test
    @DisplayName("enhanced cost is null-safe when material consumption has no joined material batch")
    void enhancedCost_handlesConsumptionWithoutJoinedMaterialBatch() {
        ProductionBatch batch = batch();
        batch.setLaborCost(BigDecimal.ZERO);
        batch.setOtherCost(BigDecimal.ZERO);

        MaterialConsumption consumption = new MaterialConsumption();
        consumption.setId(7);
        consumption.setFactoryId(FACTORY);
        consumption.setProductionBatchId(BATCH_ID);
        consumption.setBatchId("MB-MISSING");
        consumption.setQuantity(new BigDecimal("2.00"));
        consumption.setUnitPrice(new BigDecimal("3.00"));
        consumption.setMaterialTypeId("RAW-001");

        when(productionBatchRepository.findByIdAndFactoryId(BATCH_ID, FACTORY)).thenReturn(Optional.of(batch));
        when(materialConsumptionRepository.findByProductionBatchId(BATCH_ID)).thenReturn(List.of(consumption));
        when(batchEquipmentUsageRepository.findByBatchId(BATCH_ID)).thenReturn(List.of());

        Map<String, Object> result = service.getEnhancedBatchCostAnalysis(FACTORY, BATCH_ID.toString());

        assertThat((BigDecimal) result.get("totalMaterialCost")).isEqualByComparingTo("6.00");
        Map<String, Object> costSummary = asMap(result.get("costSummary"));
        assertThat((BigDecimal) costSummary.get("totalCost")).isEqualByComparingTo("6.00");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> materialConsumptions = (List<Map<String, Object>>) result.get("materialConsumptions");
        assertThat(materialConsumptions).hasSize(1);
        assertThat(materialConsumptions.get(0).get("batchNumber")).isEqualTo("MB-MISSING");
    }

    @Test
    @DisplayName("enhanced cost skips quality inspections with null pass rate")
    void enhancedCost_skipsQualityInspectionWithoutPassRate() {
        ProductionBatch batch = batch();
        batch.setLaborCost(BigDecimal.ZERO);
        batch.setOtherCost(BigDecimal.ZERO);

        QualityInspection pendingInspection = new QualityInspection();
        pendingInspection.setId("QI-PENDING");
        pendingInspection.setFactoryId(FACTORY);
        pendingInspection.setProductionBatchId(BATCH_ID);
        pendingInspection.setSampleSize(new BigDecimal("10.00"));
        pendingInspection.setPassCount(BigDecimal.ZERO);
        pendingInspection.setFailCount(BigDecimal.ZERO);
        pendingInspection.setPassRate(null);

        when(productionBatchRepository.findByIdAndFactoryId(BATCH_ID, FACTORY)).thenReturn(Optional.of(batch));
        when(materialConsumptionRepository.findByProductionBatchId(BATCH_ID)).thenReturn(List.of());
        when(batchEquipmentUsageRepository.findByBatchId(BATCH_ID)).thenReturn(List.of());
        when(qualityInspectionRepository.findByFactoryIdAndProductionBatchId(eq(FACTORY), eq(BATCH_ID), any()))
                .thenReturn(new PageImpl<>(List.of(pendingInspection)));

        Map<String, Object> result = service.getEnhancedBatchCostAnalysis(FACTORY, BATCH_ID.toString());

        assertThat(result.get("qualityInspections")).asList().hasSize(1);
        assertThat(result).doesNotContainKey("averagePassRate");
    }

    @Test
    @DisplayName("库存生产无计划数量时不计算完成率")
    void enhancedCost_openQuantityPlan_omitsCompletionRate() {
        ProductionBatch batch = batch();
        batch.setProductionPlanId("PLAN-OPEN-QTY");
        batch.setLaborCost(BigDecimal.ZERO);
        batch.setOtherCost(BigDecimal.ZERO);

        ProductionPlan plan = new ProductionPlan();
        plan.setId("PLAN-OPEN-QTY");
        plan.setPlanNumber("PLAN-OPEN-QTY-001");
        plan.setPlannedQuantity(BigDecimal.ZERO);

        when(productionBatchRepository.findByIdAndFactoryId(BATCH_ID, FACTORY)).thenReturn(Optional.of(batch));
        when(productionPlanRepository.findById("PLAN-OPEN-QTY")).thenReturn(Optional.of(plan));
        when(materialConsumptionRepository.findByProductionBatchId(BATCH_ID)).thenReturn(List.of());
        when(batchEquipmentUsageRepository.findByBatchId(BATCH_ID)).thenReturn(List.of());

        Map<String, Object> result = service.getEnhancedBatchCostAnalysis(FACTORY, BATCH_ID.toString());

        Map<String, Object> comparison = asMap(result.get("productionPlanComparison"));
        assertThat(comparison.get("plannedQuantity")).isEqualTo(BigDecimal.ZERO);
        assertThat(comparison).doesNotContainKey("completionRate");
    }

    private ProductionBatch batch() {
        ProductionBatch batch = new ProductionBatch();
        batch.setId(BATCH_ID);
        batch.setFactoryId(FACTORY);
        batch.setBatchNumber("E2E-PC-001");
        batch.setActualQuantity(new BigDecimal("12.00"));
        batch.setGoodQuantity(new BigDecimal("10.00"));
        batch.setWorkerCount(2);
        return batch;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> asMap(Object value) {
        return (Map<String, Object>) value;
    }
}
