package com.cretas.aims.service.production;

import com.cretas.aims.entity.ProductionPlan;
import com.cretas.aims.entity.enums.ProductionMode;
import com.cretas.aims.entity.enums.ProductionPlanStatus;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.exception.ResourceNotFoundException;
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
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * G3b 停产单元测试 — 验证 BY_STOCK 计划停产是纯状态关闭:
 * 无 BatchCompletedEvent 发布、无批次查询、无物料扣减。
 */
@DisplayName("ProductionPlan 停产 (G3b BY_STOCK 纯状态关闭)")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ProductionPlanStopProductionTest {

    private static final String FACTORY_ID = "F006";
    private static final String PLAN_ID   = "PP-STOP-1";

    @Mock private ProductionPlanRepository        productionPlanRepository;
    @Mock private ProductionBatchRepository        productionBatchRepository;
    @Mock private ProcessTaskRepository            processTaskRepository;
    @Mock private MaterialBatchRepository          materialBatchRepository;
    @Mock private MaterialConsumptionRepository    materialConsumptionRepository;
    @Mock private ProductionPlanBatchUsageRepository planBatchUsageRepository;
    @Mock private ProductTypeRepository            productTypeRepository;
    @Mock private ProductionPlanMapper             productionPlanMapper;
    @Mock private ConversionRepository             conversionRepository;
    @Mock private SchedulingService                schedulingService;
    @Mock private ProductionLineRepository         productionLineRepository;
    @Mock private UserRepository                   userRepository;
    @Mock private ExcelUtil                        excelUtil;
    @Mock private SalesOrderRepository             salesOrderRepository;
    @Mock private SalesOrderItemRepository         salesOrderItemRepository;
    @Mock private BomService                       bomService;
    @Mock private ApplicationEventPublisher        applicationEventPublisher;

    private ProductionPlanServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new ProductionPlanServiceImpl(
                productionPlanRepository, productionBatchRepository, processTaskRepository,
                materialBatchRepository, materialConsumptionRepository, planBatchUsageRepository,
                productTypeRepository, productionPlanMapper, conversionRepository, schedulingService,
                productionLineRepository, userRepository, excelUtil,
                salesOrderRepository, salesOrderItemRepository, bomService);
        ReflectionTestUtils.setField(service, "applicationEventPublisher", applicationEventPublisher);
        lenient().when(conversionRepository.findAll()).thenReturn(Collections.emptyList());
    }

    // ─── helpers ─────────────────────────────────────────────────────────────

    private ProductionPlan byStockPlan() {
        ProductionPlan p = new ProductionPlan();
        p.setId(PLAN_ID);
        p.setFactoryId(FACTORY_ID);
        p.setPlanNumber("PN-STOP-1");
        p.setProductTypeId("PT-1");
        p.setPlannedQuantity(new BigDecimal("1000"));
        p.setStatus(ProductionPlanStatus.IN_PROGRESS);
        p.setProductionMode(ProductionMode.BY_STOCK);
        p.setCreatedBy(1L);
        return p;
    }

    // ─── tests ───────────────────────────────────────────────────────────────

    @Test
    @DisplayName("BY_STOCK 计划停产 → COMPLETED, endTime 已设, repo.save 被调用")
    void stopProduction_byStock_setsCompletedAndSaves() {
        ProductionPlan plan = byStockPlan();
        plan.setStartTime(LocalDateTime.now().minusHours(2));

        when(productionPlanRepository.findByIdAndFactoryId(PLAN_ID, FACTORY_ID))
                .thenReturn(Optional.of(plan));
        when(productionPlanRepository.save(any(ProductionPlan.class))).thenAnswer(inv -> inv.getArgument(0));

        service.stopProduction(FACTORY_ID, PLAN_ID);

        assertEquals(ProductionPlanStatus.COMPLETED, plan.getStatus(), "状态应为 COMPLETED");
        assertNotNull(plan.getEndTime(), "endTime 必须被设置");
        verify(productionPlanRepository).save(plan);
    }

    @Test
    @DisplayName("停产不发布任何事件 — 确认无 BatchCompletedEvent 双重扣减")
    void stopProduction_publishesNoEvent() {
        ProductionPlan plan = byStockPlan();
        plan.setStartTime(LocalDateTime.now().minusHours(1));

        when(productionPlanRepository.findByIdAndFactoryId(PLAN_ID, FACTORY_ID))
                .thenReturn(Optional.of(plan));
        when(productionPlanRepository.save(any(ProductionPlan.class))).thenAnswer(inv -> inv.getArgument(0));

        service.stopProduction(FACTORY_ID, PLAN_ID);

        // 关键: 没有发布任何事件 (BatchCompletedEvent / ProductionCompletedEvent)
        verify(applicationEventPublisher, never()).publishEvent(any());
        // 关键: 没有查询批次 (completeProduction 才会查 findByFactoryIdAndProductionPlanId)
        verify(productionBatchRepository, never()).findByFactoryIdAndProductionPlanId(any(), any());
    }

    @Test
    @DisplayName("startTime 为 null 时停产自动补填 startTime")
    void stopProduction_noStartTime_backfillsStartTime() {
        ProductionPlan plan = byStockPlan();
        plan.setStartTime(null);

        when(productionPlanRepository.findByIdAndFactoryId(PLAN_ID, FACTORY_ID))
                .thenReturn(Optional.of(plan));
        when(productionPlanRepository.save(any(ProductionPlan.class))).thenAnswer(inv -> inv.getArgument(0));

        service.stopProduction(FACTORY_ID, PLAN_ID);

        assertNotNull(plan.getStartTime(), "startTime 未设时应补填");
        assertEquals(ProductionPlanStatus.COMPLETED, plan.getStatus());
    }

    @Test
    @DisplayName("BY_ORDER 计划停产 → BusinessException(400)")
    void stopProduction_byOrder_throws400() {
        ProductionPlan plan = byStockPlan();
        plan.setProductionMode(ProductionMode.BY_ORDER);

        when(productionPlanRepository.findByIdAndFactoryId(PLAN_ID, FACTORY_ID))
                .thenReturn(Optional.of(plan));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.stopProduction(FACTORY_ID, PLAN_ID));

        assertEquals(400, ex.getCode(), "非库存业态应抛 400");
        assertTrue(ex.getMessage().contains("仅库存生产计划可停产"));
        verify(productionPlanRepository, never()).save(any());
        verify(applicationEventPublisher, never()).publishEvent(any());
    }
}
