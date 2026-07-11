package com.cretas.aims.service.production;

import com.cretas.aims.entity.ProductionBatch;
import com.cretas.aims.entity.ProductionPlan;
import com.cretas.aims.entity.bom.BomItem;
import com.cretas.aims.entity.enums.ProductionPlanStatus;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.ConversionRepository;
import com.cretas.aims.repository.MaterialBatchRepository;
import com.cretas.aims.repository.MaterialConsumptionRepository;
import com.cretas.aims.repository.ProcessTaskRepository;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.repository.ProductionBatchRepository;
import com.cretas.aims.repository.ProductionLineRepository;
import com.cretas.aims.repository.ProductionPlanBatchUsageRepository;
import com.cretas.aims.repository.ProductionPlanRepository;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.repository.inventory.SalesOrderItemRepository;
import com.cretas.aims.repository.inventory.SalesOrderRepository;
import com.cretas.aims.mapper.ProductionPlanMapper;
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

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * N1 (2026-06-12) — createBatchFromPlan(转为批次=开工) 不再被原料库存预检阻塞.
 *
 * <p>行为变更: 转为批次之前仍会跑 {@code validateMaterialStockSufficient} 记录预警,
 * 但原料不足不再阻断批次创建, 与"开始"路径一致.
 *
 * <p>验证:
 * <ul>
 *   <li>库存充足 → 批次创建, 计划→IN_PROGRESS</li>
 *   <li>库存不足 → 仍创建批次, 计划状态推进</li>
 *   <li>无 BOM 配置 → skip 校验, 仍可建批次</li>
 * </ul>
 *
 * @since 2026-06-08
 */
@DisplayName("N1: createBatchFromPlan 库存预警不阻塞")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ProductionPlanCreateBatchValidationTest {

    private static final String FACTORY_ID = "F006";
    private static final String PLAN_ID = "PP-2026-138";
    private static final String PRODUCT_TYPE_ID = "PT-138";

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
    @Mock private com.cretas.aims.service.workprocess.WorkProcessTaskService workProcessTaskService;

    private ProductionPlanServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new ProductionPlanServiceImpl(
                productionPlanRepository, productionBatchRepository, processTaskRepository,
                materialBatchRepository, materialConsumptionRepository, planBatchUsageRepository,
                productTypeRepository, productionPlanMapper, conversionRepository, schedulingService,
                productionLineRepository, userRepository, excelUtil,
                salesOrderRepository, salesOrderItemRepository, bomService);
        org.springframework.test.util.ReflectionTestUtils.setField(
                service, "workProcessTaskService", workProcessTaskService);
        // 产品名查询默认 stub (成功 path 才会用上)
        lenient().when(productTypeRepository.findById(PRODUCT_TYPE_ID)).thenReturn(Optional.empty());
        lenient().when(productionBatchRepository.existsByFactoryIdAndBatchNumber(any(), any()))
                .thenReturn(false);
    }

    private ProductionPlan pendingPlan(BigDecimal plannedQuantity) {
        ProductionPlan plan = new ProductionPlan();
        plan.setId(PLAN_ID);
        plan.setFactoryId(FACTORY_ID);
        plan.setProductTypeId(PRODUCT_TYPE_ID);
        plan.setPlanNumber("PP-2026-138");
        plan.setPlannedQuantity(plannedQuantity);
        plan.setStatus(ProductionPlanStatus.PENDING);
        return plan;
    }

    private BomItem bomItem(String materialTypeId, String materialName,
                            BigDecimal standardQuantity, String unit) {
        return BomItem.builder()
                .factoryId(FACTORY_ID)
                .productTypeId(PRODUCT_TYPE_ID)
                .materialTypeId(materialTypeId)
                .materialName(materialName)
                .standardQuantity(standardQuantity)
                .yieldRate(new BigDecimal("100.00"))
                .unit(unit)
                .build();
    }

    @Test
    @DisplayName("N1: 库存不足 → 仅预警, 仍创建批次并推进计划")
    void createBatch_stockShort_stillCreatesBatch() {
        ProductionPlan plan = pendingPlan(new BigDecimal("100"));
        when(productionPlanRepository.findByIdForUpdate(PLAN_ID)).thenReturn(Optional.of(plan));
        when(productionPlanRepository.save(any(ProductionPlan.class))).thenAnswer(inv -> inv.getArgument(0));
        when(productionBatchRepository.save(any(ProductionBatch.class)))
                .thenAnswer(inv -> {
                    ProductionBatch b = inv.getArgument(0);
                    if (b.getId() == null) {
                        b.setId(10L);
                    }
                    return b;
                });

        // 需求 200, 可用 50 → 缺口 150
        when(bomService.getBomItemsByProduct(FACTORY_ID, PRODUCT_TYPE_ID))
                .thenReturn(List.of(bomItem("MT-A", "面粉", new BigDecimal("2"), "kg")));
        when(materialBatchRepository.sumAvailableQuantityByMaterialType(FACTORY_ID, "MT-A"))
                .thenReturn(new BigDecimal("50"));

        ProductionBatch batch = assertDoesNotThrow(() -> service.createBatchFromPlan(FACTORY_ID, PLAN_ID));
        assertNotNull(batch);
        verify(productionBatchRepository, times(1)).save(any(ProductionBatch.class));
        assertEquals(ProductionPlanStatus.IN_PROGRESS, plan.getStatus());
        assertNotNull(plan.getStartTime());
    }

    @Test
    @DisplayName("库存充足 → 批次创建, 计划→IN_PROGRESS")
    void createBatch_stockSufficient_createsBatch() {
        ProductionPlan plan = pendingPlan(new BigDecimal("100"));
        when(productionPlanRepository.findByIdForUpdate(PLAN_ID)).thenReturn(Optional.of(plan));
        when(productionPlanRepository.save(any(ProductionPlan.class))).thenAnswer(inv -> inv.getArgument(0));
        when(productionBatchRepository.save(any(ProductionBatch.class)))
                .thenAnswer(inv -> {
                    ProductionBatch b = inv.getArgument(0);
                    if (b.getId() == null) {
                        b.setId(1L);
                    }
                    return b;
                });

        // 需求 200, 可用 500 → 充足
        when(bomService.getBomItemsByProduct(FACTORY_ID, PRODUCT_TYPE_ID))
                .thenReturn(List.of(bomItem("MT-A", "面粉", new BigDecimal("2"), "kg")));
        when(materialBatchRepository.sumAvailableQuantityByMaterialType(FACTORY_ID, "MT-A"))
                .thenReturn(new BigDecimal("500"));

        ProductionBatch batch = service.createBatchFromPlan(FACTORY_ID, PLAN_ID);
        assertNotNull(batch);
        verify(productionBatchRepository, times(1)).save(any(ProductionBatch.class));
        assertEquals(ProductionPlanStatus.IN_PROGRESS, plan.getStatus());
        assertNotNull(plan.getStartTime());
    }

    @Test
    @DisplayName("无 BOM 配置 → skip 校验, 仍可建批次")
    void createBatch_noBom_skipsValidationStillCreates() {
        ProductionPlan plan = pendingPlan(new BigDecimal("100"));
        when(productionPlanRepository.findByIdForUpdate(PLAN_ID)).thenReturn(Optional.of(plan));
        when(productionPlanRepository.save(any(ProductionPlan.class))).thenAnswer(inv -> inv.getArgument(0));
        when(productionBatchRepository.save(any(ProductionBatch.class)))
                .thenAnswer(inv -> {
                    ProductionBatch b = inv.getArgument(0);
                    if (b.getId() == null) {
                        b.setId(2L);
                    }
                    return b;
                });
        when(bomService.getBomItemsByProduct(FACTORY_ID, PRODUCT_TYPE_ID))
                .thenReturn(Collections.emptyList());

        ProductionBatch batch = service.createBatchFromPlan(FACTORY_ID, PLAN_ID);
        assertNotNull(batch);
        verify(productionBatchRepository, times(1)).save(any(ProductionBatch.class));
        assertEquals(ProductionPlanStatus.IN_PROGRESS, plan.getStatus());
    }

    @Test
    @DisplayName("非 PENDING 状态 → 状态校验先报错 (不进入库存校验, 不建批次)")
    void createBatch_alreadyInProgress_statusCheckThrowsFirst() {
        ProductionPlan plan = pendingPlan(new BigDecimal("100"));
        plan.setStatus(ProductionPlanStatus.IN_PROGRESS);
        when(productionPlanRepository.findByIdForUpdate(PLAN_ID)).thenReturn(Optional.of(plan));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.createBatchFromPlan(FACTORY_ID, PLAN_ID));
        assertEquals(409, ex.getCode());
        assertTrue(ex.getMessage().contains("待处理"), "状态错误优先: " + ex.getMessage());
        verify(productionBatchRepository, never()).save(any(ProductionBatch.class));
    }
}
