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
        when(workflowEngine.getCurrentInstance(FACTORY_ID, "PURCHASE_ORDER", po.getId()))
                .thenReturn(Optional.empty());

        ApprovalWorkflowInstance running = buildInstance(
                "inst-abc", InstanceStatus.RUNNING, List.of("approval_finance"));
        when(workflowEngine.startWorkflow(eq(FACTORY_ID), eq("PURCHASE_ORDER"), eq(po.getId()), anyMap(), eq(APPROVER_ID)))
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

        // startWorkflow context 应含 amount / decision
        ArgumentCaptor<Map<String, Object>> ctxCap = ArgumentCaptor.forClass(Map.class);
        verify(workflowEngine).startWorkflow(eq(FACTORY_ID), eq("PURCHASE_ORDER"), eq(po.getId()),
                ctxCap.capture(), eq(APPROVER_ID));
        Map<String, Object> ctx = ctxCap.getValue();
        assertEquals(new BigDecimal("50000"), ctx.get("amount"));
        assertEquals("APPROVE", ctx.get("decision"));
        assertEquals(0, ctx.get("priceVarianceItemCount"));
    }

    @Test
    @DisplayName("approve_with_low_amount_auto_completes: condition skip 财务 → instance APPROVED → PO APPROVED")
    void approve_with_low_amount_auto_completes() {
        PurchaseOrder po = buildPo(new BigDecimal("2000")); // 远低于 30000 阈值
        when(purchaseOrderRepository.findById(po.getId())).thenReturn(Optional.of(po));

        when(workflowEngine.hasActiveWorkflow(FACTORY_ID, "PURCHASE_ORDER")).thenReturn(true);
        when(workflowEngine.getCurrentInstance(FACTORY_ID, "PURCHASE_ORDER", po.getId()))
                .thenReturn(Optional.empty());

        // 工程师配的 workflow 让低额单走 end_auto → APPROVED 终态
        ApprovalWorkflowInstance terminated = buildInstance(
                "inst-low", InstanceStatus.APPROVED, List.of());
        terminated.setCompletedAt(java.time.LocalDateTime.now());
        when(workflowEngine.startWorkflow(eq(FACTORY_ID), eq("PURCHASE_ORDER"), eq(po.getId()), anyMap(), eq(APPROVER_ID)))
                .thenReturn(terminated);

        PurchaseOrder result = service.approveOrder(FACTORY_ID, po.getId(), APPROVER_ID);

        assertEquals(PurchaseOrderStatus.APPROVED, result.getStatus(),
                "instance APPROVED → PO APPROVED (自动跳过财务节点)");
        assertEquals(APPROVER_ID, result.getApprovedBy());
        assertNotNull(result.getApprovedAt());

        // 没有 active approval 节点, 无需 notifyRole.
        verify(notificationService, never()).notifyRole(anyString(), anyString(), anyString(), anyString());
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
