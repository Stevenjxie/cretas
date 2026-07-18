package com.cretas.aims.service.production;

import com.cretas.aims.entity.ProductType;
import com.cretas.aims.entity.ProductionBatch;
import com.cretas.aims.entity.ProductionPlan;
import com.cretas.aims.entity.enums.ProductionBatchStatus;
import com.cretas.aims.entity.enums.ProductionPlanStatus;
import com.cretas.aims.event.BatchCompletedEvent;
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
import com.cretas.aims.dto.production.ProductionPlanDTO;
import com.cretas.aims.service.BomService;
import com.cretas.aims.service.SchedulingService;
import com.cretas.aims.service.impl.ProductionPlanServiceImpl;
import com.cretas.aims.service.workprocess.WorkProcessTaskService;
import com.cretas.aims.utils.ExcelUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 完工链 3 缺口修复单测 (F006 — 2026-06-02):
 *
 * <ul>
 *   <li>GAP 3/4: {@code createBatchFromPlan} 转批次时 spawn 工序任务 + 批次置 IN_PROGRESS (逐道报工可见)</li>
 *   <li>GAP 6: 计划级 {@code completeProduction} 级联完成关联批次 + 发 BatchCompletedEvent (建成品)</li>
 * </ul>
 *
 * @author Cretas Team
 * @since 2026-06-02
 */
@DisplayName("ProductionPlan 完工链 — 转批次 spawn 任务 / 计划完成级联批次")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ProductionPlanBatchCompletionChainTest {

    private static final String FACTORY_ID = "F001";
    private static final String PLAN_ID = "PP-2026-100";
    private static final String PRODUCT_TYPE_ID = "PT-001";

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

    @Mock private WorkProcessTaskService workProcessTaskService;
    @Mock private ApplicationEventPublisher applicationEventPublisher;

    private ProductionPlanServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new ProductionPlanServiceImpl(
                productionPlanRepository, productionBatchRepository, processTaskRepository,
                materialBatchRepository, materialConsumptionRepository, planBatchUsageRepository,
                productTypeRepository, productionPlanMapper, conversionRepository, schedulingService,
                productionLineRepository, userRepository, excelUtil,
                salesOrderRepository, salesOrderItemRepository);

        // 字段注入的依赖 (@Autowired(required = false)) — 单测用反射注入 mock
        ReflectionTestUtils.setField(service, "workProcessTaskService", workProcessTaskService);
        ReflectionTestUtils.setField(service, "applicationEventPublisher", applicationEventPublisher);

        // toDTOWithConversionInfo 依赖默认 stub
        ProductionPlanDTO emptyDto = new ProductionPlanDTO();
        lenient().when(productionPlanMapper.toDTO(any(ProductionPlan.class))).thenReturn(emptyDto);
        lenient().when(conversionRepository.findAll()).thenReturn(Collections.emptyList());
    }

    private ProductionPlan pendingPlan() {
        ProductionPlan plan = new ProductionPlan();
        plan.setId(PLAN_ID);
        plan.setFactoryId(FACTORY_ID);
        plan.setProductTypeId(PRODUCT_TYPE_ID);
        plan.setPlanNumber("PP-2026-100");
        plan.setPlannedQuantity(new BigDecimal("200"));
        plan.setPlannedUnit("kg");
        plan.setStatus(ProductionPlanStatus.PENDING);
        return plan;
    }

    private ProductionPlan inProgressPlan() {
        ProductionPlan plan = pendingPlan();
        plan.setStatus(ProductionPlanStatus.IN_PROGRESS);
        return plan;
    }

    // ==================== GAP 3/4: createBatchFromPlan ====================

    @Test
    @DisplayName("转批次: 批次置 IN_PROGRESS + 设 startTime + spawnTasks(factoryId, batchId, productTypeId)")
    void createBatchFromPlan_spawnsTasks_andBatchInProgress() {
        ProductionPlan plan = pendingPlan();
        plan.setWorkflowSelectionMode(ProductionBatch.WorkflowSelectionMode.WORKFLOW);
        plan.setSelectedWorkflowId(44L);
        plan.setSelectedWorkflowVersion(3);
        // R6 (2026-06-14): createBatchFromPlan 改用悲观锁 findByIdForUpdate 取计划。
        when(productionPlanRepository.findByIdForUpdate(PLAN_ID)).thenReturn(Optional.of(plan));
        when(productionPlanRepository.save(any(ProductionPlan.class))).thenAnswer(inv -> inv.getArgument(0));

        ProductType pt = new ProductType();
        pt.setName("白卤猪舌");
        when(productTypeRepository.findById(PRODUCT_TYPE_ID)).thenReturn(Optional.of(pt));
        when(productionBatchRepository.existsByFactoryIdAndBatchNumber(eq(FACTORY_ID), any())).thenReturn(false);
        // save 回填 id, 模拟数据库生成主键
        when(productionBatchRepository.save(any(ProductionBatch.class))).thenAnswer(inv -> {
            ProductionBatch b = inv.getArgument(0);
            b.setId(777L);
            return b;
        });

        ProductionBatch saved = service.createBatchFromPlan(FACTORY_ID, PLAN_ID);

        assertNotNull(saved);
        assertEquals(ProductionBatchStatus.IN_PROGRESS, saved.getStatus(),
                "转批次=开始生产, 批次应为 IN_PROGRESS 使逐道报工可见");
        assertNotNull(saved.getStartTime(), "转批次应设置 startTime");
        assertEquals(ProductionPlanStatus.IN_PROGRESS, plan.getStatus());
        assertEquals(ProductionBatch.WorkflowSelectionMode.WORKFLOW,
                saved.getWorkflowSelectionMode());
        assertEquals(44L, saved.getSelectedWorkflowId());
        assertEquals(3, saved.getSelectedWorkflowVersion());

        // V20261017_01: createBatchFromPlan 改调 6-arg overload (skip + 头尾责任人).
        // pendingPlan 未设 skipProcessReporting → 实体默认 false; 无 supervisor → null/null.
        verify(workProcessTaskService, times(1))
                .spawnTasks(FACTORY_ID, 777L, PRODUCT_TYPE_ID, Boolean.FALSE, null, null);
    }

    @Test
    @DisplayName("createBatchFromPlan: spawn/runtime failure is fail-closed so retry cannot select a newer activation")
    void createBatchFromPlan_spawnTasksThrows_failClosed() {
        ProductionPlan plan = pendingPlan();
        // R6 (2026-06-14): createBatchFromPlan 改用悲观锁 findByIdForUpdate 取计划。
        when(productionPlanRepository.findByIdForUpdate(PLAN_ID)).thenReturn(Optional.of(plan));
        when(productionPlanRepository.save(any(ProductionPlan.class))).thenAnswer(inv -> inv.getArgument(0));

        ProductType pt = new ProductType();
        pt.setName("白卤猪舌");
        when(productTypeRepository.findById(PRODUCT_TYPE_ID)).thenReturn(Optional.of(pt));
        when(productionBatchRepository.existsByFactoryIdAndBatchNumber(eq(FACTORY_ID), any())).thenReturn(false);
        when(productionBatchRepository.save(any(ProductionBatch.class))).thenAnswer(inv -> {
            ProductionBatch b = inv.getArgument(0);
            b.setId(888L);
            return b;
        });
        // 产品无 product_work_processes 配置 → spawnTasks 抛 BusinessException (6-arg overload)
        doThrow(new com.cretas.aims.exception.BusinessException(404, "无工序模板"))
                .when(workProcessTaskService).spawnTasks(any(), any(), any(), any(), any(), any());

        assertThrows(com.cretas.aims.exception.BusinessException.class,
                () -> service.createBatchFromPlan(FACTORY_ID, PLAN_ID));
        verify(workProcessTaskService, times(1))
                .spawnTasks(FACTORY_ID, 888L, PRODUCT_TYPE_ID, Boolean.FALSE, null, null);
    }

    // ==================== GAP 6: completeProduction 级联批次 ====================

    @Test
    @DisplayName("计划完成: 关联 IN_PROGRESS 批次 → COMPLETED + goodQuantity=actualQuantity + 发 BatchCompletedEvent")
    void completeProduction_linkedBatch_completesAndPublishesEvent() {
        ProductionPlan plan = inProgressPlan();
        when(productionPlanRepository.findById(PLAN_ID)).thenReturn(Optional.of(plan));
        when(productionPlanRepository.save(any(ProductionPlan.class))).thenAnswer(inv -> inv.getArgument(0));

        ProductionBatch batch = ProductionBatch.builder()
                .factoryId(FACTORY_ID)
                .productionPlanId(PLAN_ID)
                .productTypeId(PRODUCT_TYPE_ID)
                .status(ProductionBatchStatus.IN_PROGRESS)
                .build();
        batch.setId(555L);
        when(productionBatchRepository.findByFactoryIdAndProductionPlanId(FACTORY_ID, PLAN_ID))
                .thenReturn(List.of(batch));
        when(productionBatchRepository.save(any(ProductionBatch.class))).thenAnswer(inv -> inv.getArgument(0));

        BigDecimal actual = new BigDecimal("180");
        service.completeProduction(FACTORY_ID, PLAN_ID, actual);

        assertEquals(ProductionPlanStatus.COMPLETED, plan.getStatus());
        assertEquals(ProductionBatchStatus.COMPLETED, batch.getStatus(), "关联批次应被级联完成");
        assertNotNull(batch.getEndTime());
        assertEquals(actual, batch.getActualQuantity());
        assertEquals(actual, batch.getGoodQuantity(),
                "计划级无良/次品拆分 → goodQuantity=actualQuantity (FG 创建需 goodQuantity>0)");

        ArgumentCaptor<org.springframework.context.ApplicationEvent> captor =
                ArgumentCaptor.forClass(org.springframework.context.ApplicationEvent.class);
        verify(applicationEventPublisher, org.mockito.Mockito.atLeastOnce())
                .publishEvent(captor.capture());
        boolean batchEventFired = captor.getAllValues().stream()
                .anyMatch(e -> e instanceof BatchCompletedEvent
                        && ((BatchCompletedEvent) e).getBatchId().equals(555L));
        org.junit.jupiter.api.Assertions.assertTrue(batchEventFired,
                "应发布 batchId=555 的 BatchCompletedEvent 以触发成品创建");
    }

    @Test
    @DisplayName("计划完成: 无关联批次 → 计划仍 COMPLETED, 无异常, 不发 BatchCompletedEvent")
    void completeProduction_noLinkedBatch_planStillCompleted() {
        ProductionPlan plan = inProgressPlan();
        when(productionPlanRepository.findById(PLAN_ID)).thenReturn(Optional.of(plan));
        when(productionPlanRepository.save(any(ProductionPlan.class))).thenAnswer(inv -> inv.getArgument(0));
        when(productionBatchRepository.findByFactoryIdAndProductionPlanId(FACTORY_ID, PLAN_ID))
                .thenReturn(Collections.emptyList());

        assertDoesNotThrow(() -> service.completeProduction(FACTORY_ID, PLAN_ID, new BigDecimal("180")));
        assertEquals(ProductionPlanStatus.COMPLETED, plan.getStatus());

        verify(productionBatchRepository, never()).save(any(ProductionBatch.class));
        // 计划完成事件仍发, 但不应发 BatchCompletedEvent
        ArgumentCaptor<org.springframework.context.ApplicationEvent> captor =
                ArgumentCaptor.forClass(org.springframework.context.ApplicationEvent.class);
        verify(applicationEventPublisher, org.mockito.Mockito.atLeastOnce())
                .publishEvent(captor.capture());
        boolean anyBatchEvent = captor.getAllValues().stream()
                .anyMatch(e -> e instanceof BatchCompletedEvent);
        org.junit.jupiter.api.Assertions.assertFalse(anyBatchEvent,
                "无关联批次不应发 BatchCompletedEvent");
    }
    @Test
    @DisplayName("N1b: PENDING plan can be completed directly from unfinished list")
    void completeProduction_pendingPlan_directCompletesAndSetsStartTime() {
        ProductionPlan plan = pendingPlan();
        when(productionPlanRepository.findById(PLAN_ID)).thenReturn(Optional.of(plan));
        when(productionPlanRepository.save(any(ProductionPlan.class))).thenAnswer(inv -> inv.getArgument(0));
        when(productionBatchRepository.findByFactoryIdAndProductionPlanId(FACTORY_ID, PLAN_ID))
                .thenReturn(Collections.emptyList());

        BigDecimal actual = new BigDecimal("180");
        assertDoesNotThrow(() -> service.completeProduction(FACTORY_ID, PLAN_ID, actual));

        assertEquals(ProductionPlanStatus.COMPLETED, plan.getStatus());
        assertEquals(actual, plan.getActualQuantity());
        assertNotNull(plan.getStartTime(), "Direct completion should set startTime when a PENDING plan was never started");
        assertNotNull(plan.getEndTime());
    }
}
