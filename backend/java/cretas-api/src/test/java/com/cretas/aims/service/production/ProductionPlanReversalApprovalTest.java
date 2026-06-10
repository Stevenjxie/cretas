package com.cretas.aims.service.production;

import com.cretas.aims.entity.ProductionPlan;
import com.cretas.aims.entity.enums.ProcessTaskStatus;
import com.cretas.aims.entity.enums.ProductionPlanStatus;
import com.cretas.aims.entity.workflow.ApprovalWorkflowInstance;
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
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.repository.inventory.SalesOrderItemRepository;
import com.cretas.aims.repository.inventory.SalesOrderRepository;
import com.cretas.aims.service.BomService;
import com.cretas.aims.service.SchedulingService;
import com.cretas.aims.service.impl.ProductionPlanServiceImpl;
import com.cretas.aims.service.workflow.WorkflowEngineService;
import com.cretas.aims.entity.ProcessTask;
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
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * SP12 T3: 生产计划撤回审批流单测.
 *
 * <ul>
 *   <li>UT-PP-RCA-01: requestCancelWithApproval → 设置 PENDING_APPROVAL + 返回 instanceId</li>
 *   <li>UT-PP-RCA-02: requestCancelWithApproval 计划非 COMPLETED → 409</li>
 *   <li>UT-PP-RCA-03: requestCancelWithApproval workflow 未配置 → 409</li>
 *   <li>UT-PP-RCA-04: executeCancelApproved → 设置 CANCELLED + 级联关闭工序任务</li>
 *   <li>UT-PP-RCA-05: executeCancelApproved 计划非 PENDING_APPROVAL → 409</li>
 *   <li>UT-PP-RCA-06: requestCancelWithApproval workflowEngine null → 409</li>
 * </ul>
 *
 * @author Cretas Team (SP12 T3)
 * @since 2026-06-10
 */
@DisplayName("SP12 T3 — 生产计划撤回审批流")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ProductionPlanReversalApprovalTest {

    private static final String FACTORY_ID = "F006";
    private static final String PLAN_ID = "PP-SP12-T3-001";
    private static final String PRODUCT_TYPE_ID = "PT-F006-PORK";
    private static final Long USER_ID = 42L;
    private static final String REASON = "客户取消订单，需要撤回完成计划";
    private static final String INSTANCE_ID = "WF-INST-SP12-001";

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
    @Mock private WorkflowEngineService workflowEngine;

    private ProductionPlanServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new ProductionPlanServiceImpl(
                productionPlanRepository, productionBatchRepository, processTaskRepository,
                materialBatchRepository, materialConsumptionRepository, planBatchUsageRepository,
                productTypeRepository, productionPlanMapper, conversionRepository, schedulingService,
                productionLineRepository, userRepository, excelUtil,
                salesOrderRepository, salesOrderItemRepository, bomService);

        // 注入可选依赖 (required = false)
        ReflectionTestUtils.setField(service, "workflowEngine", workflowEngine);
    }

    // -----------------------------------------------------------------------
    // helpers

    private ProductionPlan completedPlan() {
        ProductionPlan plan = new ProductionPlan();
        plan.setId(PLAN_ID);
        plan.setFactoryId(FACTORY_ID);
        plan.setProductTypeId(PRODUCT_TYPE_ID);
        plan.setStatus(ProductionPlanStatus.COMPLETED);
        return plan;
    }

    private ApprovalWorkflowInstance stubInstance() {
        ApprovalWorkflowInstance inst = new ApprovalWorkflowInstance();
        inst.setId(INSTANCE_ID);
        return inst;
    }

    // -----------------------------------------------------------------------
    // UT-PP-RCA-01

    @Test
    @DisplayName("UT-PP-RCA-01: requestCancelWithApproval — 正常路径: COMPLETED → PENDING_APPROVAL + 返回 instanceId")
    void requestCancelWithApproval_happyPath_returnInstanceId() {
        ProductionPlan plan = completedPlan();
        when(productionPlanRepository.findById(PLAN_ID)).thenReturn(Optional.of(plan));
        when(workflowEngine.hasActiveWorkflow(FACTORY_ID, "PRODUCTION_REVERSAL")).thenReturn(true);
        when(workflowEngine.startWorkflow(eq(FACTORY_ID), eq("PRODUCTION_REVERSAL"),
                eq(PLAN_ID), anyMap(), eq(USER_ID))).thenReturn(stubInstance());
        when(productionPlanRepository.save(any(ProductionPlan.class))).thenAnswer(inv -> inv.getArgument(0));

        String result = service.requestCancelWithApproval(FACTORY_ID, PLAN_ID, REASON, USER_ID);

        assertEquals(INSTANCE_ID, result);
        // 计划状态应被设为 PENDING_APPROVAL
        ArgumentCaptor<ProductionPlan> savedPlan = ArgumentCaptor.forClass(ProductionPlan.class);
        verify(productionPlanRepository).save(savedPlan.capture());
        assertEquals(ProductionPlanStatus.PENDING_APPROVAL, savedPlan.getValue().getStatus());
        // notes 中应包含撤回原因
        assertTrue(savedPlan.getValue().getNotes() != null
                && savedPlan.getValue().getNotes().contains(REASON));
    }

    // -----------------------------------------------------------------------
    // UT-PP-RCA-02

    @Test
    @DisplayName("UT-PP-RCA-02: requestCancelWithApproval — 计划非 COMPLETED → 409 BusinessException")
    void requestCancelWithApproval_nonCompleted_throws409() {
        ProductionPlan plan = completedPlan();
        plan.setStatus(ProductionPlanStatus.IN_PROGRESS);
        when(productionPlanRepository.findById(PLAN_ID)).thenReturn(Optional.of(plan));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.requestCancelWithApproval(FACTORY_ID, PLAN_ID, REASON, USER_ID));
        assertEquals(409, ex.getCode());
        // workflow 不应被调用
        verify(workflowEngine, never()).startWorkflow(anyString(), anyString(), anyString(), anyMap(), anyLong());
    }

    // -----------------------------------------------------------------------
    // UT-PP-RCA-03

    @Test
    @DisplayName("UT-PP-RCA-03: requestCancelWithApproval — workflow 未配置 (hasActiveWorkflow=false) → 409")
    void requestCancelWithApproval_noActiveWorkflow_throws409() {
        ProductionPlan plan = completedPlan();
        when(productionPlanRepository.findById(PLAN_ID)).thenReturn(Optional.of(plan));
        when(workflowEngine.hasActiveWorkflow(FACTORY_ID, "PRODUCTION_REVERSAL")).thenReturn(false);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.requestCancelWithApproval(FACTORY_ID, PLAN_ID, REASON, USER_ID));
        assertEquals(409, ex.getCode());
        verify(workflowEngine, never()).startWorkflow(anyString(), anyString(), anyString(), anyMap(), anyLong());
    }

    // -----------------------------------------------------------------------
    // UT-PP-RCA-04

    @Test
    @DisplayName("UT-PP-RCA-04: executeCancelApproved — PENDING_APPROVAL → CANCELLED + 级联关闭工序任务")
    void executeCancelApproved_happyPath_cascadeClosesTasks() {
        ProductionPlan plan = completedPlan();
        plan.setStatus(ProductionPlanStatus.PENDING_APPROVAL);
        when(productionPlanRepository.findById(PLAN_ID)).thenReturn(Optional.of(plan));
        when(productionPlanRepository.save(any(ProductionPlan.class))).thenAnswer(inv -> inv.getArgument(0));

        // 两个 IN_PROGRESS 工序任务
        ProcessTask t1 = new ProcessTask();
        t1.setStatus(ProcessTaskStatus.IN_PROGRESS);
        ProcessTask t2 = new ProcessTask();
        t2.setStatus(ProcessTaskStatus.IN_PROGRESS);
        // 一个已完成任务（不应被关闭）
        ProcessTask t3 = new ProcessTask();
        t3.setStatus(ProcessTaskStatus.COMPLETED);
        when(processTaskRepository.findByFactoryIdAndProductTypeId(FACTORY_ID, PRODUCT_TYPE_ID))
                .thenReturn(List.of(t1, t2, t3));

        assertDoesNotThrow(() -> service.executeCancelApproved(PLAN_ID));

        ArgumentCaptor<ProductionPlan> planCaptor = ArgumentCaptor.forClass(ProductionPlan.class);
        verify(productionPlanRepository).save(planCaptor.capture());
        assertEquals(ProductionPlanStatus.CANCELLED, planCaptor.getValue().getStatus());

        // t1, t2 should be CLOSED; t3 should remain COMPLETED
        assertEquals(ProcessTaskStatus.CLOSED, t1.getStatus());
        assertEquals(ProcessTaskStatus.CLOSED, t2.getStatus());
        assertEquals(ProcessTaskStatus.COMPLETED, t3.getStatus());
        verify(processTaskRepository, times(2)).save(any(ProcessTask.class));
    }

    // -----------------------------------------------------------------------
    // UT-PP-RCA-05

    @Test
    @DisplayName("UT-PP-RCA-05: executeCancelApproved — 计划非 PENDING_APPROVAL → 409 BusinessException")
    void executeCancelApproved_nonPendingApproval_throws409() {
        ProductionPlan plan = completedPlan();
        plan.setStatus(ProductionPlanStatus.COMPLETED);
        when(productionPlanRepository.findById(PLAN_ID)).thenReturn(Optional.of(plan));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.executeCancelApproved(PLAN_ID));
        assertEquals(409, ex.getCode());
        verify(productionPlanRepository, never()).save(any());
    }

    // -----------------------------------------------------------------------
    // UT-PP-RCA-06

    @Test
    @DisplayName("UT-PP-RCA-06: requestCancelWithApproval — workflowEngine null → 409")
    void requestCancelWithApproval_nullEngine_throws409() {
        // 覆盖 workflowEngine 为 null（模拟未注入场景）
        ReflectionTestUtils.setField(service, "workflowEngine", null);

        ProductionPlan plan = completedPlan();
        when(productionPlanRepository.findById(PLAN_ID)).thenReturn(Optional.of(plan));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.requestCancelWithApproval(FACTORY_ID, PLAN_ID, REASON, USER_ID));
        assertEquals(409, ex.getCode());
    }

    // -----------------------------------------------------------------------
    // UT-PP-RCA-07 (审计 Tier0 #01: 旧 cancel 绕过审批红线)

    @Test
    @DisplayName("UT-PP-RCA-07: cancelProductionPlan — PENDING_APPROVAL (审批流中) → 409, 不允许旧接口直接取消绕过审批")
    void cancelProductionPlan_pendingApproval_throws409_noBypass() {
        ProductionPlan plan = completedPlan();
        plan.setStatus(ProductionPlanStatus.PENDING_APPROVAL);
        when(productionPlanRepository.findById(PLAN_ID)).thenReturn(Optional.of(plan));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.cancelProductionPlan(FACTORY_ID, PLAN_ID, REASON));
        assertEquals(409, ex.getCode());
        // 计划不应被改成 CANCELLED — 状态必须保持 PENDING_APPROVAL, 等审批流处理
        assertEquals(ProductionPlanStatus.PENDING_APPROVAL, plan.getStatus());
        verify(productionPlanRepository, never()).save(any());
    }

    // -----------------------------------------------------------------------
    // UT-PP-RCA-08: 合法直接取消 (非 COMPLETED / 非 PENDING_APPROVAL / 未锁定) 仍可走旧接口

    @Test
    @DisplayName("UT-PP-RCA-08: cancelProductionPlan — IN_PROGRESS 计划仍可直接取消 (合法操作员路径不被破坏)")
    void cancelProductionPlan_inProgress_allowedDirectCancel() {
        ProductionPlan plan = completedPlan();
        plan.setStatus(ProductionPlanStatus.IN_PROGRESS);
        plan.setIsLocked(false);
        when(productionPlanRepository.findById(PLAN_ID)).thenReturn(Optional.of(plan));
        when(productionPlanRepository.save(any(ProductionPlan.class))).thenAnswer(inv -> inv.getArgument(0));
        when(processTaskRepository.findByFactoryIdAndProductTypeId(FACTORY_ID, PRODUCT_TYPE_ID))
                .thenReturn(Collections.emptyList());

        assertDoesNotThrow(() -> service.cancelProductionPlan(FACTORY_ID, PLAN_ID, REASON));
        assertEquals(ProductionPlanStatus.CANCELLED, plan.getStatus());
        verify(productionPlanRepository).save(any(ProductionPlan.class));
    }
}
