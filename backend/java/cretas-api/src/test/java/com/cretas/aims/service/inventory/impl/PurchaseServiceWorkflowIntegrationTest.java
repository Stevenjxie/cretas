package com.cretas.aims.service.inventory.impl;

import com.cretas.aims.entity.config.ApprovalWorkflow;
import com.cretas.aims.entity.enums.PurchaseOrderStatus;
import com.cretas.aims.entity.inventory.PurchaseOrder;
import com.cretas.aims.entity.inventory.PurchaseOrderApprovalRule;
import com.cretas.aims.entity.workflow.ApprovalHistory.HistoryAction;
import com.cretas.aims.entity.workflow.ApprovalWorkflowInstance;
import com.cretas.aims.entity.workflow.ApprovalWorkflowInstance.InstanceStatus;
import com.cretas.aims.repository.MaterialBatchRepository;
import com.cretas.aims.repository.RawMaterialTypeRepository;
import com.cretas.aims.repository.SupplierRepository;
import com.cretas.aims.repository.bom.BomRecipeItemRepository;
import com.cretas.aims.repository.inventory.PurchaseOrderApprovalRuleRepository;
import com.cretas.aims.repository.inventory.PurchaseOrderItemRepository;
import com.cretas.aims.repository.inventory.PurchaseOrderRepository;
import com.cretas.aims.repository.inventory.PurchaseReceiveRecordRepository;
import com.cretas.aims.service.ApprovalWorkflowService;
import com.cretas.aims.service.MaterialBatchService;
import com.cretas.aims.service.notification.NotificationService;
import com.cretas.aims.service.workflow.WorkflowEngineService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.cretas.aims.exception.BusinessException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Integration test for Phase 1 Canvas-Workflow B.6 — PurchaseServiceImpl ↔ WorkflowEngineService.
 *
 * <p>Covers the 3 critical scenarios:
 * <ol>
 *   <li>{@link #approve_without_workflow_uses_legacy_fallback} —
 *     factory 无 active workflow → legacy {@code evaluateApprovalTrigger} 路径</li>
 *   <li>{@link #approve_with_workflow_routes_to_workflow_running} —
 *     workflow 包含 approval node → PO status=WORKFLOW_RUNNING + instance 创建</li>
 *   <li>{@link #approve_with_low_amount_auto_completes} —
 *     condition skip 财务节点 → instance APPROVED → PO APPROVED</li>
 * </ol>
 *
 * <p>策略: 不启动 Spring context, 用 Mockito 替换 {@code WorkflowEngineService} + repos + notification.
 * 字段注入的 dependencies 通过 {@link ReflectionTestUtils#setField} 灌入.
 *
 * @since 2026-05-18 (Phase 1 B.6)
 */
@DisplayName("PurchaseServiceImpl Workflow Integration — Phase 1 B.6")
@ExtendWith(MockitoExtension.class)
class PurchaseServiceWorkflowIntegrationTest {

    private static final String FACTORY_ID = "F006";
    private static final Long APPROVER_ID = 999L;

    @Mock private PurchaseOrderRepository purchaseOrderRepository;
    @Mock private PurchaseOrderItemRepository purchaseOrderItemRepository;
    @Mock private PurchaseReceiveRecordRepository receiveRecordRepository;
    @Mock private SupplierRepository supplierRepository;
    @Mock private RawMaterialTypeRepository materialTypeRepository;
    @Mock private MaterialBatchRepository materialBatchRepository;
    @Mock private BomRecipeItemRepository bomItemRepository;
    @Mock private com.cretas.aims.service.finance.ArApService arApService;
    @Mock private ApplicationEventPublisher applicationEventPublisher;
    @Mock private MaterialBatchService materialBatchService;
    @Mock private PurchaseOrderApprovalRuleRepository approvalRuleRepository;
    @Mock private NotificationService notificationService;
    @Mock private WorkflowEngineService workflowEngine;
    @Mock private ApprovalWorkflowService approvalWorkflowService;

    private PurchaseServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new PurchaseServiceImpl(
                purchaseOrderRepository,
                purchaseOrderItemRepository,
                receiveRecordRepository,
                supplierRepository,
                materialTypeRepository,
                materialBatchRepository,
                bomItemRepository,
                arApService,
                applicationEventPublisher,
                materialBatchService);

        // Field-injected optional dependencies (per Spring @Autowired(required=false) pattern in production).
        ReflectionTestUtils.setField(service, "approvalRuleRepository", approvalRuleRepository);
        ReflectionTestUtils.setField(service, "notificationService", notificationService);
        ReflectionTestUtils.setField(service, "workflowEngine", workflowEngine);
        ReflectionTestUtils.setField(service, "approvalWorkflowService", approvalWorkflowService);

        // Default overReceiveRate (from @Value default) — set explicitly.
        ReflectionTestUtils.setField(service, "overReceiveRate", new BigDecimal("0.30"));

        // Repos default behavior
        lenient().when(purchaseOrderRepository.save(any(PurchaseOrder.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        // Default: no items (countPriceAlertItems returns 0, no legacy trigger)
        lenient().when(purchaseOrderItemRepository.findByPurchaseOrderId(anyString()))
                .thenReturn(List.of());
        // Default: no approval rule (legacy fallback uses code defaults)
        lenient().when(approvalRuleRepository.findByFactoryIdAndEnabledTrueOrderByCreatedAtDesc(anyString()))
                .thenReturn(List.<PurchaseOrderApprovalRule>of());
    }

    // ==================== Fixtures ====================

    private PurchaseOrder buildPo(BigDecimal totalAmount) {
        PurchaseOrder po = new PurchaseOrder();
        po.setId("po-" + UUID.randomUUID());
        po.setFactoryId(FACTORY_ID);
        po.setOrderNumber("PO-TEST-001");
        po.setSupplierId("supp-1");
        po.setStatus(PurchaseOrderStatus.SUBMITTED);
        po.setTotalAmount(totalAmount);
        return po;
    }

    private ApprovalWorkflowInstance buildInstance(String id, InstanceStatus status, List<String> currentNodes) {
        return ApprovalWorkflowInstance.builder()
                .id(id)
                .factoryId(FACTORY_ID)
                .workflowId("wf-test")
                .moduleCode("PURCHASE_ORDER")
                .businessEntityId("po-x")
                .status(status)
                .currentNodeIds(new ArrayList<>(currentNodes))
                .contextJson(new java.util.HashMap<>())
                .build();
    }

    // ==================== Tests ====================

    @Test
    @DisplayName("approve_without_workflow_uses_legacy_fallback: factory 无 active workflow → legacy 路径 → APPROVED")
    void approve_without_workflow_uses_legacy_fallback() {
        PurchaseOrder po = buildPo(new BigDecimal("5000"));
        when(purchaseOrderRepository.findById(po.getId())).thenReturn(Optional.of(po));
        // Phase 1 hotfix 2026-05-18: pre-check via hasActiveWorkflow 替代 startWorkflow throw 路径
        // (旧 throw 路径会触发 Spring rollback-only 陷阱).
        when(workflowEngine.hasActiveWorkflow(FACTORY_ID, "PURCHASE_ORDER")).thenReturn(false);

        PurchaseOrder result = service.approveOrder(FACTORY_ID, po.getId(), APPROVER_ID);

        // Legacy path: no items, no rule, no trigger → APPROVED (not PENDING_FINANCE_REVIEW)
        assertEquals(PurchaseOrderStatus.APPROVED, result.getStatus(),
                "legacy 无规则 + 无三价告警 → 直接 APPROVED");
        assertEquals(APPROVER_ID, result.getApprovedBy());
        assertNotNull(result.getApprovedAt());

        // Notification should NOT be triggered (no PENDING_FINANCE_REVIEW transition).
        verify(notificationService, never()).notifyRole(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("approve_with_workflow_routes_to_workflow_running: workflow RUNNING → PO=WORKFLOW_RUNNING + 通知 next-stage")
    void approve_with_workflow_routes_to_workflow_running() {
        PurchaseOrder po = buildPo(new BigDecimal("50000"));
        when(purchaseOrderRepository.findById(po.getId())).thenReturn(Optional.of(po));

        when(workflowEngine.hasActiveWorkflow(FACTORY_ID, "PURCHASE_ORDER")).thenReturn(true);

        // 2026-08-15 改前提: approveOrder 已【不再】从 approve 端点起实例 ——
        // 源码 :1095 写明「唯一合法的创建边界是 submitOrder, 从 approve 起实例会重造
        // 『已提交但 OA 里看不见』的 split-brain」。本用例原本桩 startWorkflow,
        // 那条路已被刻意删除, 所以改成「已有 RUNNING 实例, transitionNode 后仍 RUNNING」。
        // 用例要守的东西不变: PO 落到 WORKFLOW_RUNNING 且通知下一节点审批人。
        ApprovalWorkflowInstance running = buildInstance(
                "inst-abc", InstanceStatus.RUNNING, List.of("approval_finance"));
        when(workflowEngine.getCurrentInstance(FACTORY_ID, "PURCHASE_ORDER", po.getId()))
                .thenReturn(Optional.of(running));
        when(workflowEngine.transitionNode(eq("inst-abc"), eq(APPROVER_ID), anyString(),
                eq(HistoryAction.APPROVE), anyString()))
                .thenReturn(running);

        // approvalWorkflowService.getById returns a workflow with a JSON node defining approverRoles
        ApprovalWorkflow wf = new ApprovalWorkflow();
        wf.setId("wf-test");
        wf.setFactoryId(FACTORY_ID);
        wf.setNodesJson("[]"); // Body doesn't matter since deserializeNodes is mocked.
        when(approvalWorkflowService.getById(FACTORY_ID, "wf-test")).thenReturn(Optional.of(wf));
        when(approvalWorkflowService.deserializeNodes(anyString()))
                .thenReturn(List.of(
                        com.cretas.aims.entity.config.ApprovalWorkflowNode.builder()
                                .id("approval_finance").type("approval").label("财务审批")
                                .config(Map.of("approverRoles", List.of("finance_manager")))
                                .build()));

        PurchaseOrder result = service.approveOrder(FACTORY_ID, po.getId(), APPROVER_ID);

        assertEquals(PurchaseOrderStatus.WORKFLOW_RUNNING, result.getStatus(),
                "workflow RUNNING → PO status WORKFLOW_RUNNING");
        assertEquals(APPROVER_ID, result.getApprovedBy());

        // notifyRole 应被调用 1 次 — finance_manager
        ArgumentCaptor<String> roleCaptor = ArgumentCaptor.forClass(String.class);
        verify(notificationService, times(1))
                .notifyRole(eq(FACTORY_ID), roleCaptor.capture(), anyString(), anyString());
        assertEquals("finance_manager", roleCaptor.getValue());

        // 走的是 resume(transitionNode), 且绝不从 approve 端点起实例
        verify(workflowEngine).transitionNode(eq("inst-abc"), eq(APPROVER_ID), anyString(),
                eq(HistoryAction.APPROVE), anyString());
        verify(workflowEngine, never()).startWorkflow(anyString(), anyString(), anyString(), anyMap(), any());
    }

    @Test
    @DisplayName("approve_without_instance_is_rejected: 无 RUNNING 实例 → 409, 禁止从 approve 端点重造实例")
    void approve_without_instance_is_rejected() {
        // 2026-08-15 重写。原用例名 approve_with_low_amount_auto_completes, 桩的是
        // 「getCurrentInstance 空 → approveOrder 调 startWorkflow 起实例 → 低额单 end_auto 自动完成」。
        // 那条路已被**刻意删除**(源码 :1094-1099): 从 approve 端点起实例会重造
        // 「已提交但 OA 里看不见」的 split-brain, 并绕过 initiator/审计契约。
        //
        // ⚠️ 所以这不是「断言写错了」, 是**需求变了** —— 断言守的是历史而不是需求。
        // 改成守【新的那条保护】: 没有 RUNNING 实例时必须 409, 而不是偷偷补一个实例。
        // 实测全仓此前**没有任何测试**守着 PURCHASE_APPROVAL_INSTANCE_MISSING。
        //
        // 「低额单自动跳过财务节点」那部分语义属于创建侧(submitOrder), 由
        // PurchaseServiceOaSubmissionTest 覆盖, 本次改写没有丢覆盖。
        PurchaseOrder po = buildPo(new BigDecimal("2000"));
        when(purchaseOrderRepository.findById(po.getId())).thenReturn(Optional.of(po));

        when(workflowEngine.hasActiveWorkflow(FACTORY_ID, "PURCHASE_ORDER")).thenReturn(true);
        when(workflowEngine.getCurrentInstance(FACTORY_ID, "PURCHASE_ORDER", po.getId()))
                .thenReturn(Optional.empty());

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.approveOrder(FACTORY_ID, po.getId(), APPROVER_ID));
        assertEquals("PURCHASE_APPROVAL_INSTANCE_MISSING", ex.getErrorCode());

        // 阴性对照: 绝不能顺手起一个实例
        verify(workflowEngine, never()).startWorkflow(anyString(), anyString(), anyString(), anyMap(), any());
    }

    @Test
    @DisplayName("approve_resumes_running_instance: 同一 PO 已有 RUNNING workflow → 走 transitionNode (resume)")
    void approve_resumes_running_instance() {
        PurchaseOrder po = buildPo(new BigDecimal("80000"));
        po.setStatus(PurchaseOrderStatus.WORKFLOW_RUNNING); // 已经在审批中
        when(purchaseOrderRepository.findById(po.getId())).thenReturn(Optional.of(po));

        when(workflowEngine.hasActiveWorkflow(FACTORY_ID, "PURCHASE_ORDER")).thenReturn(true);

        ApprovalWorkflowInstance existing = buildInstance(
                "inst-exists", InstanceStatus.RUNNING, List.of("approval_finance"));
        when(workflowEngine.getCurrentInstance(FACTORY_ID, "PURCHASE_ORDER", po.getId()))
                .thenReturn(Optional.of(existing));

        // transitionNode 推进到终态
        ApprovalWorkflowInstance afterTransition = buildInstance(
                "inst-exists", InstanceStatus.APPROVED, List.of());
        afterTransition.setCompletedAt(java.time.LocalDateTime.now());
        when(workflowEngine.transitionNode(eq("inst-exists"), eq(APPROVER_ID), anyString(),
                eq(HistoryAction.APPROVE), anyString()))
                .thenReturn(afterTransition);

        PurchaseOrder result = service.approveOrder(FACTORY_ID, po.getId(), APPROVER_ID);

        assertEquals(PurchaseOrderStatus.APPROVED, result.getStatus(),
                "resume 后 instance APPROVED → PO APPROVED");

        // 走 transitionNode 不应走 startWorkflow
        verify(workflowEngine, never()).startWorkflow(anyString(), anyString(), anyString(), anyMap(), any());
        verify(workflowEngine).transitionNode(eq("inst-exists"), eq(APPROVER_ID), anyString(),
                eq(HistoryAction.APPROVE), anyString());
    }
}
