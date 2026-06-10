package com.cretas.aims.service.impl;

import com.cretas.aims.entity.ProductionBatch;
import com.cretas.aims.entity.ProductionPlan;
import com.cretas.aims.exception.BusinessException;
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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * BUG-2 批次详情 DTO 加 batchSourceType — 单元测试。
 *
 * <p>getBatchById 返回批次时，如果该批次关联生产计划有 planSourceType，
 * 则 batch.batchSourceType = plan.planSourceType（@Transient 字段）。
 * 计划不存在时，batchSourceType 为 null（null-safe）。
 */
@ExtendWith(MockitoExtension.class)
class ProcessingServiceImplBatchSourceTypeTest {

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

    @Test
    @DisplayName("批次关联计划 planSourceType=SECONDARY → batchSourceType=SECONDARY")
    void getBatchById_populatesBatchSourceType_fromSecondaryPlan() {
        ProductionBatch batch = new ProductionBatch();
        batch.setId(100L);
        batch.setFactoryId(FACTORY);
        batch.setBatchNumber("BATCH-001");
        batch.setProductionPlanId("PLAN-SECONDARY-1");

        ProductionPlan plan = new ProductionPlan();
        plan.setId("PLAN-SECONDARY-1");
        plan.setFactoryId(FACTORY);
        plan.setPlanSourceType("SECONDARY");

        when(productionBatchRepository.findByIdAndFactoryId(100L, FACTORY)).thenReturn(Optional.of(batch));
        when(productionPlanRepository.findById("PLAN-SECONDARY-1")).thenReturn(Optional.of(plan));

        ProductionBatch result = service.getBatchById(FACTORY, "100");

        assertThat(result.getBatchSourceType())
                .as("SECONDARY 计划的批次 batchSourceType 应为 SECONDARY")
                .isEqualTo("SECONDARY");
    }

    @Test
    @DisplayName("批次关联计划 planSourceType=NORMAL → batchSourceType=NORMAL")
    void getBatchById_populatesBatchSourceType_fromNormalPlan() {
        ProductionBatch batch = new ProductionBatch();
        batch.setId(101L);
        batch.setFactoryId(FACTORY);
        batch.setBatchNumber("BATCH-002");
        batch.setProductionPlanId("PLAN-NORMAL-1");

        ProductionPlan plan = new ProductionPlan();
        plan.setId("PLAN-NORMAL-1");
        plan.setFactoryId(FACTORY);
        plan.setPlanSourceType("NORMAL");

        when(productionBatchRepository.findByIdAndFactoryId(101L, FACTORY)).thenReturn(Optional.of(batch));
        when(productionPlanRepository.findById("PLAN-NORMAL-1")).thenReturn(Optional.of(plan));

        ProductionBatch result = service.getBatchById(FACTORY, "101");

        assertThat(result.getBatchSourceType())
                .as("NORMAL 计划的批次 batchSourceType 应为 NORMAL")
                .isEqualTo("NORMAL");
    }

    @Test
    @DisplayName("批次无关联计划 (productionPlanId=null) → batchSourceType=null (null-safe)")
    void getBatchById_noPlanId_batchSourceTypeIsNull() {
        ProductionBatch batch = new ProductionBatch();
        batch.setId(102L);
        batch.setFactoryId(FACTORY);
        batch.setBatchNumber("BATCH-003");
        batch.setProductionPlanId(null); // no plan

        when(productionBatchRepository.findByIdAndFactoryId(102L, FACTORY)).thenReturn(Optional.of(batch));

        ProductionBatch result = service.getBatchById(FACTORY, "102");

        assertThat(result.getBatchSourceType())
                .as("无计划批次 batchSourceType 应为 null")
                .isNull();
    }

    @Test
    @DisplayName("批次有 planId 但计划已删除/不存在 → batchSourceType=null (null-safe)")
    void getBatchById_planNotFound_batchSourceTypeIsNull() {
        ProductionBatch batch = new ProductionBatch();
        batch.setId(103L);
        batch.setFactoryId(FACTORY);
        batch.setBatchNumber("BATCH-004");
        batch.setProductionPlanId("PLAN-DELETED");

        when(productionBatchRepository.findByIdAndFactoryId(103L, FACTORY)).thenReturn(Optional.of(batch));
        when(productionPlanRepository.findById("PLAN-DELETED")).thenReturn(Optional.empty());

        ProductionBatch result = service.getBatchById(FACTORY, "103");

        assertThat(result.getBatchSourceType())
                .as("计划不存在时 batchSourceType 应为 null")
                .isNull();
    }

    @Test
    @DisplayName("批次不存在 → 抛 BusinessException 404")
    void getBatchById_batchNotFound_throws() {
        when(productionBatchRepository.findByIdAndFactoryId(999L, FACTORY)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getBatchById(FACTORY, "999"))
                .isInstanceOf(RuntimeException.class); // ResourceNotFoundException extends RuntimeException
    }
}
