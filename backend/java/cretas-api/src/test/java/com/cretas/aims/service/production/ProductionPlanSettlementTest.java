package com.cretas.aims.service.production;

import com.cretas.aims.dto.production.ProductionSettlementRequest;
import com.cretas.aims.dto.production.ProductionSettlementResponse;
import com.cretas.aims.entity.MaterialBatch;
import com.cretas.aims.entity.ProductionPlan;
import com.cretas.aims.entity.ProductionSettlement;
import com.cretas.aims.entity.SemiFinishedInventory;
import com.cretas.aims.entity.enums.MaterialBatchStatus;
import com.cretas.aims.entity.enums.ProductionPlanStatus;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.mapper.ProductionPlanMapper;
import com.cretas.aims.repository.ConversionRepository;
import com.cretas.aims.repository.MaterialBatchRepository;
import com.cretas.aims.repository.MaterialConsumptionRepository;
import com.cretas.aims.repository.ProcessTaskRepository;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.repository.ProductionBatchRepository;
import com.cretas.aims.repository.ProductionLineRepository;
import com.cretas.aims.repository.ProductionPlanBatchUsageRepository;
import com.cretas.aims.repository.ProductionPlanRepository;
import com.cretas.aims.repository.ProductionSettlementConsumptionRepository;
import com.cretas.aims.repository.ProductionSettlementLaborRepository;
import com.cretas.aims.repository.ProductionSettlementRepository;
import com.cretas.aims.repository.SemiFinishedInventoryRepository;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.repository.inventory.SalesOrderItemRepository;
import com.cretas.aims.repository.inventory.SalesOrderRepository;
import com.cretas.aims.service.BomService;
import com.cretas.aims.service.SchedulingService;
import com.cretas.aims.service.impl.ProductionPlanServiceImpl;
import com.cretas.aims.utils.ExcelUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("ProductionPlan 六扇门结构化结单")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ProductionPlanSettlementTest {

    private static final String FACTORY_ID = "F006";
    private static final String PLAN_ID = "PP-SETTLE-1";

    @Mock private ProductionPlanRepository productionPlanRepository;
    @Mock private ProductionBatchRepository productionBatchRepository;
    @Mock private ProcessTaskRepository processTaskRepository;
    @Mock private MaterialBatchRepository materialBatchRepository;
    @Mock private MaterialConsumptionRepository materialConsumptionRepository;
    @Mock private ProductionPlanBatchUsageRepository planBatchUsageRepository;
    @Mock private ProductTypeRepository productTypeRepository;
    @Mock private ProductionPlanMapper productionPlanMapper;
    @Mock private ConversionRepository conversionRepository;
    @Mock private SchedulingService schedulingService;
    @Mock private ProductionLineRepository productionLineRepository;
    @Mock private UserRepository userRepository;
    @Mock private ExcelUtil excelUtil;
    @Mock private SalesOrderRepository salesOrderRepository;
    @Mock private SalesOrderItemRepository salesOrderItemRepository;
    @Mock private BomService bomService;
    @Mock private ProductionSettlementRepository productionSettlementRepository;
    @Mock private ProductionSettlementConsumptionRepository productionSettlementConsumptionRepository;
    @Mock private ProductionSettlementLaborRepository productionSettlementLaborRepository;
    @Mock private SemiFinishedInventoryRepository semiFinishedInventoryRepository;

    private ProductionPlanServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new ProductionPlanServiceImpl(
                productionPlanRepository, productionBatchRepository, processTaskRepository,
                materialBatchRepository, materialConsumptionRepository, planBatchUsageRepository,
                productTypeRepository, productionPlanMapper, conversionRepository, schedulingService,
                productionLineRepository, userRepository, excelUtil,
                salesOrderRepository, salesOrderItemRepository, bomService);
        ReflectionTestUtils.setField(service, "productionSettlementRepository", productionSettlementRepository);
        ReflectionTestUtils.setField(service, "productionSettlementConsumptionRepository", productionSettlementConsumptionRepository);
        ReflectionTestUtils.setField(service, "productionSettlementLaborRepository", productionSettlementLaborRepository);
        ReflectionTestUtils.setField(service, "semiFinishedInventoryRepository", semiFinishedInventoryRepository);
        lenient().when(conversionRepository.findAll()).thenReturn(Collections.emptyList());
    }

    @Test
    @DisplayName("F006 旧 complete 入口必须提示走核对结单")
    void completeProduction_f006RequiresSettlement() {
        when(productionPlanRepository.findById(PLAN_ID)).thenReturn(Optional.of(plan()));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.completeProduction(FACTORY_ID, PLAN_ID, new BigDecimal("90")));

        assertEquals(409, ex.getCode());
        assertEquals("PRODUCTION_SETTLEMENT_REQUIRED", ex.getErrorCode());
        verify(productionBatchRepository, never()).findByFactoryIdAndProductionPlanId(any(), any());
    }

    @Test
    @DisplayName("超计划产量未选择原因时拒绝结单")
    void settleProduction_overPlanWithoutReason_rejected() {
        when(productionPlanRepository.findByIdAndFactoryId(PLAN_ID, FACTORY_ID)).thenReturn(Optional.of(plan()));
        when(productionSettlementRepository.findByFactoryIdAndProductionPlanIdAndIdempotencyKeyAndDeletedAtIsNull(
                FACTORY_ID, PLAN_ID, "idem-1")).thenReturn(Optional.empty());
        when(productionSettlementRepository.findByFactoryIdAndProductionPlanIdAndDeletedAtIsNull(
                FACTORY_ID, PLAN_ID)).thenReturn(Optional.empty());

        ProductionSettlementRequest request = baseRequest();
        request.setActualFinishedQuantity(new BigDecimal("120"));
        request.setQuantityVarianceReason(null);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.settleProduction(FACTORY_ID, PLAN_ID, request, 10L));

        assertEquals("PRODUCTION_OVER_PLAN_REASON_REQUIRED", ex.getErrorCode());
        verify(productionSettlementRepository, never()).save(any());
    }

    @Test
    @DisplayName("结单保存原料+半成品+人效并完成计划")
    void settleProduction_validRequest_savesSettlementAndCompletesPlan() {
        ProductionPlan plan = plan();
        when(productionPlanRepository.findByIdAndFactoryId(PLAN_ID, FACTORY_ID)).thenReturn(Optional.of(plan));
        when(productionSettlementRepository.findByFactoryIdAndProductionPlanIdAndIdempotencyKeyAndDeletedAtIsNull(
                FACTORY_ID, PLAN_ID, "idem-1")).thenReturn(Optional.empty());
        when(productionSettlementRepository.findByFactoryIdAndProductionPlanIdAndDeletedAtIsNull(
                FACTORY_ID, PLAN_ID)).thenReturn(Optional.empty());
        when(materialBatchRepository.findByIdAndFactoryId("MB-1", FACTORY_ID)).thenReturn(Optional.of(materialBatch()));
        when(semiFinishedInventoryRepository.findByIdForUpdate(7L)).thenReturn(Optional.of(wip()));
        when(productionSettlementRepository.save(any(ProductionSettlement.class))).thenAnswer(inv -> inv.getArgument(0));
        when(productionPlanRepository.save(any(ProductionPlan.class))).thenAnswer(inv -> inv.getArgument(0));
        when(productionSettlementConsumptionRepository.saveAll(anyList())).thenAnswer(inv -> inv.getArgument(0));
        when(productionSettlementLaborRepository.saveAll(anyList())).thenAnswer(inv -> inv.getArgument(0));

        ProductionSettlementResponse response = service.settleProduction(FACTORY_ID, PLAN_ID, baseRequest(), 10L);

        assertEquals(ProductionPlanStatus.COMPLETED, plan.getStatus());
        assertEquals(new BigDecimal("90"), plan.getActualQuantity());
        assertEquals("PENDING_POSTING", response.getPostingStatus());
        assertEquals(new BigDecimal("90"), response.getActualFinishedQuantity());
        assertTrue(response.getWarnings().get(0).contains("尚未过账"));
        verify(productionSettlementConsumptionRepository).saveAll(anyList());
        verify(productionSettlementLaborRepository).saveAll(anyList());
    }

    @Test
    @DisplayName("同一幂等键重复提交返回原结单")
    void settleProduction_sameIdempotency_returnsExistingSettlement() {
        when(productionPlanRepository.findByIdAndFactoryId(PLAN_ID, FACTORY_ID)).thenReturn(Optional.of(plan()));
        ProductionSettlement existing = new ProductionSettlement();
        existing.setId("settlement-1");
        existing.setFactoryId(FACTORY_ID);
        existing.setProductionPlanId(PLAN_ID);
        existing.setPlanNumber("P-001");
        existing.setPlannedQuantity(new BigDecimal("100"));
        existing.setActualFinishedQuantity(new BigDecimal("90"));
        existing.setActualSemiFinishedQuantity(BigDecimal.ZERO);
        existing.setPlanStatusAfter(ProductionPlanStatus.COMPLETED);
        existing.setPostingStatus("PENDING_POSTING");
        when(productionSettlementRepository.findByFactoryIdAndProductionPlanIdAndIdempotencyKeyAndDeletedAtIsNull(
                FACTORY_ID, PLAN_ID, "idem-1")).thenReturn(Optional.of(existing));

        ProductionSettlementResponse response = service.settleProduction(FACTORY_ID, PLAN_ID, baseRequest(), 10L);

        assertEquals("settlement-1", response.getSettlementId());
        assertTrue(response.getWarnings().get(0).contains("已提交过"));
        verify(productionSettlementRepository, never()).save(any());
    }

    private ProductionPlan plan() {
        ProductionPlan plan = new ProductionPlan();
        plan.setId(PLAN_ID);
        plan.setFactoryId(FACTORY_ID);
        plan.setPlanNumber("P-001");
        plan.setProductTypeId("PT-1");
        plan.setPlannedQuantity(new BigDecimal("100"));
        plan.setStatus(ProductionPlanStatus.IN_PROGRESS);
        return plan;
    }

    private ProductionSettlementRequest baseRequest() {
        return ProductionSettlementRequest.builder()
                .idempotencyKey("idem-1")
                .actualFinishedQuantity(new BigDecimal("90"))
                .actualSemiFinishedQuantity(BigDecimal.ZERO)
                .rawMaterialConsumptions(List.of(ProductionSettlementRequest.ConsumptionLine.builder()
                        .materialBatchId("MB-1")
                        .quantity(new BigDecimal("12"))
                        .unit("kg")
                        .build()))
                .semiFinishedConsumptions(List.of(ProductionSettlementRequest.ConsumptionLine.builder()
                        .semiFinishedInventoryId(7L)
                        .quantity(new BigDecimal("5"))
                        .unit("kg")
                        .build()))
                .laborSegments(List.of(ProductionSettlementRequest.LaborSegment.builder()
                        .workerName("operator")
                        .minutes(120)
                        .headcount(2)
                        .build()))
                .build();
    }

    private MaterialBatch materialBatch() {
        MaterialBatch batch = new MaterialBatch();
        batch.setId("MB-1");
        batch.setFactoryId(FACTORY_ID);
        batch.setBatchNumber("B-001");
        batch.setMaterialTypeId("RM-1");
        batch.setReceiptQuantity(new BigDecimal("30"));
        batch.setUsedQuantity(new BigDecimal("4"));
        batch.setReservedQuantity(new BigDecimal("1"));
        batch.setQuantityUnit("kg");
        batch.setWarehouseId("WH-WKS");
        batch.setStatus(MaterialBatchStatus.AVAILABLE);
        return batch;
    }

    private SemiFinishedInventory wip() {
        return SemiFinishedInventory.builder()
                .id(7L)
                .factoryId(FACTORY_ID)
                .intermediateBatchNo("WIP-001")
                .availableQuantity(new BigDecimal("8"))
                .status(SemiFinishedInventory.Status.AVAILABLE)
                .build();
    }
}
