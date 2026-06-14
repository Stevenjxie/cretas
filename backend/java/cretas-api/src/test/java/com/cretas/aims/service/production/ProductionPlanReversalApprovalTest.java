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
    // R1/R2 (2026-06-14): 级联改批次定向 (WorkProcessTask) + IN_PROGRESS 有数据检测 (ProductionReport)。
    @Mock private com.cretas.aims.repository.workprocess.WorkProcessTaskRepository workProcessTaskRepository;
    @Mock private com.cretas.aims.repository.ProductionReportRepository productionReportRepository;
    // R2 v2 (2026-06-14): 审批通过后真正恢复库存 — 报工撤回服务 (executeReversal)。
    @Mock private com.cretas.aims.service.reversal.ReportReversalService reportReversalService;

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
        ReflectionTestUtils.setField(service, "workProcessTaskRepository", workProcessTaskRepository);
        ReflectionTestUtils.setField(service, "productionReportRepository", productionReportRepository);
        ReflectionTestUtils.setField(service, "reportReversalService", reportReversalService);
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
    @DisplayName("UT-PP-RCA-04: executeCancelApproved — PENDING_APPROVAL → CANCELLED + 按批次级联关闭 WorkProcessTask (R1)")
    void executeCancelApproved_happyPath_cascadeClosesTasks() {
        ProductionPlan plan = completedPlan();
        plan.setStatus(ProductionPlanStatus.PENDING_APPROVAL);
        when(productionPlanRepository.findById(PLAN_ID)).thenReturn(Optional.of(plan));
        when(productionPlanRepository.save(any(ProductionPlan.class))).thenAnswer(inv -> inv.getArgument(0));

        // R1: 本计划的批次
        com.cretas.aims.entity.ProductionBatch batch =
                com.cretas.aims.entity.ProductionBatch.builder()
                        .factoryId(FACTORY_ID)
                        .productionPlanId(PLAN_ID)
                        .productTypeId(PRODUCT_TYPE_ID)
                        .status(com.cretas.aims.entity.enums.ProductionBatchStatus.IN_PROGRESS)
                        .build();
        batch.setId(70L);
        when(productionBatchRepository.findByFactoryIdAndProductionPlanId(FACTORY_ID, PLAN_ID))
                .thenReturn(List.of(batch));
        when(productionBatchRepository.save(any(com.cretas.aims.entity.ProductionBatch.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        // 两个活跃 WorkProcessTask + 一个 COMPLETED (终态不动)
        com.cretas.aims.entity.workprocess.WorkProcessTask t1 = new com.cretas.aims.entity.workprocess.WorkProcessTask();
        t1.setStatus(com.cretas.aims.entity.workprocess.WorkProcessTask.Status.IN_PROGRESS);
        com.cretas.aims.entity.workprocess.WorkProcessTask t2 = new com.cretas.aims.entity.workprocess.WorkProcessTask();
        t2.setStatus(com.cretas.aims.entity.workprocess.WorkProcessTask.Status.PENDING);
        com.cretas.aims.entity.workprocess.WorkProcessTask t3 = new com.cretas.aims.entity.workprocess.WorkProcessTask();
        t3.setStatus(com.cretas.aims.entity.workprocess.WorkProcessTask.Status.COMPLETED);
        when(workProcessTaskRepository.findByFactoryIdAndProductionBatchIdOrderByProcessOrderAsc(FACTORY_ID, 70L))
                .thenReturn(new java.util.ArrayList<>(List.of(t1, t2, t3)));
        when(workProcessTaskRepository.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));

        assertDoesNotThrow(() -> service.executeCancelApproved(PLAN_ID));

        ArgumentCaptor<ProductionPlan> planCaptor = ArgumentCaptor.forClass(ProductionPlan.class);
        verify(productionPlanRepository).save(planCaptor.capture());
        assertEquals(ProductionPlanStatus.CANCELLED, planCaptor.getValue().getStatus());

        // t1, t2 → CANCELLED; t3 保持 COMPLETED
        assertEquals(com.cretas.aims.entity.workprocess.WorkProcessTask.Status.CANCELLED, t1.getStatus());
        assertEquals(com.cretas.aims.entity.workprocess.WorkProcessTask.Status.CANCELLED, t2.getStatus());
        assertEquals(com.cretas.aims.entity.workprocess.WorkProcessTask.Status.COMPLETED, t3.getStatus());
        // R1: 仅查询本批次任务, 绝不走旧的"按产品类型全关 ProcessTask"
        verify(workProcessTaskRepository, times(1))
                .findByFactoryIdAndProductionBatchIdOrderByProcessOrderAsc(FACTORY_ID, 70L);
        verify(processTaskRepository, never()).findByFactoryIdAndProductTypeId(any(), any());
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
    @DisplayName("UT-PP-RCA-08: cancelProductionPlan — IN_PROGRESS 无报工无 WIP 仍可直接取消 (合法操作员路径不被破坏)")
    void cancelProductionPlan_inProgress_allowedDirectCancel() {
        ProductionPlan plan = completedPlan();
        plan.setStatus(ProductionPlanStatus.IN_PROGRESS);
        plan.setIsLocked(false);
        when(productionPlanRepository.findById(PLAN_ID)).thenReturn(Optional.of(plan));
        when(productionPlanRepository.save(any(ProductionPlan.class))).thenAnswer(inv -> inv.getArgument(0));
        // R2: 无批次 → 无报工无 WIP → 安全直接取消
        when(productionBatchRepository.findByFactoryIdAndProductionPlanId(FACTORY_ID, PLAN_ID))
                .thenReturn(Collections.emptyList());

        assertDoesNotThrow(() -> service.cancelProductionPlan(FACTORY_ID, PLAN_ID, REASON));
        assertEquals(ProductionPlanStatus.CANCELLED, plan.getStatus());
        verify(productionPlanRepository).save(any(ProductionPlan.class));
    }

    // -----------------------------------------------------------------------
    // UT-PP-RCA-09: R2 v2 — IN_PROGRESS 有报工 → 拒绝直接取消, 导向「报工撤回」流 (非 PRODUCTION_REVERSAL 死路)

    @Test
    @DisplayName("UT-PP-RCA-09: cancelProductionPlan — IN_PROGRESS 有报工 → 409 导向报工撤回 (含批次号 + 不置 CANCELLED)")
    void cancelProductionPlan_inProgressWithReports_redirectsToReportReversal() {
        ProductionPlan plan = completedPlan();
        plan.setStatus(ProductionPlanStatus.IN_PROGRESS);
        plan.setIsLocked(false);
        when(productionPlanRepository.findById(PLAN_ID)).thenReturn(Optional.of(plan));

        com.cretas.aims.entity.ProductionBatch batch =
                com.cretas.aims.entity.ProductionBatch.builder()
                        .factoryId(FACTORY_ID)
                        .productionPlanId(PLAN_ID)
                        .batchNumber("B-F006-0612-01")
                        .status(com.cretas.aims.entity.enums.ProductionBatchStatus.IN_PROGRESS)
                        .build();
        batch.setId(88L);
        when(productionBatchRepository.findByFactoryIdAndProductionPlanId(FACTORY_ID, PLAN_ID))
                .thenReturn(List.of(batch));
        // 该批次有 YIELD 报工 → hasProductionData = true
        com.cretas.aims.entity.ProductionReport yield = new com.cretas.aims.entity.ProductionReport();
        when(productionReportRepository.findYieldReportsByBatch(FACTORY_ID, 88L))
                .thenReturn(List.of(yield));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.cancelProductionPlan(FACTORY_ID, PLAN_ID, REASON));
        assertEquals(409, ex.getCode());
        assertEquals("PLAN_HAS_PRODUCTION_DATA", ex.getErrorCode());
        // 防呆: message 含批次号 (Rule 2 context), hint 指向报工撤回 (Rule 5 next action)
        assertTrue(ex.getMessage().contains("B-F006-0612-01"),
                "message 应列出涉及批次号, 实际: " + ex.getMessage());
        assertTrue(ex.getActionHint() != null && ex.getActionHint().contains("报工撤回"),
                "hint 应导向报工撤回, 实际: " + ex.getActionHint());
        assertEquals("报工撤回", ex.getHintTarget());
        // 计划不被错误 CANCELLED, 也不启动 PRODUCTION_REVERSAL 死路工作流
        assertEquals(ProductionPlanStatus.IN_PROGRESS, plan.getStatus());
        verify(productionPlanRepository, never()).save(any());
        verify(workflowEngine, never()).startWorkflow(anyString(), anyString(), anyString(), anyMap(), anyLong());
    }

    // -----------------------------------------------------------------------
    // UT-PP-RCA-10: R2 v2 — executeCancelApproved 审批通过后真正调 executeReversal 恢复库存 (有报工批次)

    @Test
    @DisplayName("UT-PP-RCA-10: executeCancelApproved — 审批通过 → 对有报工批次调 submitReversal+executeReversal 恢复库存 → CANCELLED")
    void executeCancelApproved_invokesReportReversalToRestoreInventory() {
        ProductionPlan plan = completedPlan();
        plan.setStatus(ProductionPlanStatus.PENDING_APPROVAL);
        when(productionPlanRepository.findById(PLAN_ID)).thenReturn(Optional.of(plan));
        when(productionPlanRepository.save(any(ProductionPlan.class))).thenAnswer(inv -> inv.getArgument(0));

        com.cretas.aims.entity.ProductionBatch batch =
                com.cretas.aims.entity.ProductionBatch.builder()
                        .factoryId(FACTORY_ID)
                        .productionPlanId(PLAN_ID)
                        .status(com.cretas.aims.entity.enums.ProductionBatchStatus.IN_PROGRESS)
                        .build();
        batch.setId(91L);
        when(productionBatchRepository.findByFactoryIdAndProductionPlanId(FACTORY_ID, PLAN_ID))
                .thenReturn(List.of(batch));
        when(productionBatchRepository.save(any(com.cretas.aims.entity.ProductionBatch.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        // 批次有 YIELD 报工 → 触发撤回
        com.cretas.aims.entity.ProductionReport yield = new com.cretas.aims.entity.ProductionReport();
        when(productionReportRepository.findYieldReportsByBatch(FACTORY_ID, 91L))
                .thenReturn(List.of(yield));
        // submitReversal 返回 PENDING log (有报工无快速撤回) → 显式 executeReversal 落地恢复
        com.cretas.aims.entity.ReportReversalLog rlog = com.cretas.aims.entity.ReportReversalLog.builder()
                .factoryId(FACTORY_ID)
                .batchId(91L)
                .status(com.cretas.aims.entity.ReportReversalLog.ReversalStatus.PENDING)
                .build();
        rlog.setId(555L);
        when(reportReversalService.submitReversal(eq(FACTORY_ID), eq(91L), any(), anyString()))
                .thenReturn(rlog);
        // 任务级联 mock
        when(workProcessTaskRepository.findByFactoryIdAndProductionBatchIdOrderByProcessOrderAsc(FACTORY_ID, 91L))
                .thenReturn(new java.util.ArrayList<>());

        assertDoesNotThrow(() -> service.executeCancelApproved(PLAN_ID));

        // 验证真正调了恢复库存 (submitReversal + executeReversal), 不是只置 CANCELLED
        verify(reportReversalService).submitReversal(eq(FACTORY_ID), eq(91L), any(), anyString());
        verify(reportReversalService).executeReversal(555L, FACTORY_ID);
        assertEquals(ProductionPlanStatus.CANCELLED, plan.getStatus());
    }
}
