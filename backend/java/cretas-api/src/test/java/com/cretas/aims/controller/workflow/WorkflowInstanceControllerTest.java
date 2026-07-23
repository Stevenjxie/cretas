package com.cretas.aims.controller.workflow;

import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.dto.common.PageResponse;
import com.cretas.aims.dto.user.UserDTO;
import com.cretas.aims.dto.workflow.WorkflowInstancePendingDTO;
import com.cretas.aims.entity.User;
import com.cretas.aims.entity.config.ApprovalWorkflow;
import com.cretas.aims.entity.config.ApprovalWorkflowNode;
import com.cretas.aims.entity.inventory.PurchaseOrder;
import com.cretas.aims.entity.inventory.SalesOrder;
import com.cretas.aims.entity.inventory.InternalTransfer;
import com.cretas.aims.entity.enums.TransferStatus;
import com.cretas.aims.entity.enums.SalesOrderStatus;
import com.cretas.aims.entity.workflow.ApprovalHistory.HistoryAction;
import com.cretas.aims.entity.workflow.ApprovalWorkflowInstance;
import com.cretas.aims.entity.workflow.ApprovalWorkflowInstance.InstanceStatus;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.repository.inventory.PurchaseOrderRepository;
import com.cretas.aims.repository.inventory.SalesOrderRepository;
import com.cretas.aims.service.ApprovalWorkflowService;
import com.cretas.aims.service.MobileService;
import com.cretas.aims.service.workflow.WorkflowEngineService;
import com.cretas.aims.service.workflow.OaActionIdempotencyService;
import com.cretas.aims.service.inventory.PurchaseService;
import com.cretas.aims.service.inventory.SalesService;
import com.cretas.aims.service.inventory.TransferService;
import com.cretas.aims.service.factory.FactoryStocktakeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.doReturn;

/**
 * Tests for {@link WorkflowInstanceController} — issue #20 Phase 1 closure for ADR-001 AC-3.
 *
 * <p>Strategy: 不启动 Spring context, 用 Mockito 替换 WorkflowEngineService /
 * ApprovalWorkflowService / Repos / MobileService. Controller 字段注入的
 * optional {@code purchaseOrderRepository} 通过 {@link ReflectionTestUtils} 灌入.
 *
 * <p>Scenarios:
 * <ol>
 *   <li>{@link #finance_mgr_sees_pending_with_finance_node} —
 *     finance_manager 角色看到含财务审批节点的 PO</li>
 *   <li>{@link #quality_mgr_sees_zero_pending} —
 *     quality_manager 不应看到 finance-only workflow</li>
 *   <li>{@link #factory_super_admin_sees_all} —
 *     super_admin 看全部 RUNNING 实例 (兜底)</li>
 *   <li>{@link #pagination_works} —
 *     分页参数正确传给 service 并返回 PageResponse</li>
 * </ol>
 *
 * @since 2026-05-18 (issue #20)
 */
@DisplayName("WorkflowInstanceController — issue #20 我待审 widget")
@ExtendWith(MockitoExtension.class)
class WorkflowInstanceControllerTest {

    private static final String FACTORY_ID = "F006";
    private static final String AUTH_HEADER = "Bearer fake-token";

    @Mock private WorkflowEngineService workflowEngine;
    @Mock private ApprovalWorkflowService approvalWorkflowService;
    @Mock private UserRepository userRepository;
    @Mock private MobileService mobileService;
    @Mock private PurchaseOrderRepository purchaseOrderRepository;
    @Mock private SalesOrderRepository salesOrderRepository;
    @Mock private OaActionIdempotencyService oaActionIdempotencyService;
    @Mock private PurchaseService purchaseService;
    @Mock private SalesService salesService;
    @Mock private TransferService transferService;
    @Mock private FactoryStocktakeService stocktakeService;

    @InjectMocks private WorkflowInstanceController controller;

    @BeforeEach
    void setUp() {
        // purchaseOrderRepository 在 controller 是 @Autowired(required=false) 字段注入,
        // @InjectMocks 不会自动注入, 用 ReflectionTestUtils.
        ReflectionTestUtils.setField(controller, "purchaseOrderRepository", purchaseOrderRepository);
        ReflectionTestUtils.setField(controller, "salesOrderRepository", salesOrderRepository);
        ReflectionTestUtils.setField(controller, "oaActionIdempotencyService", oaActionIdempotencyService);
        lenient().when(oaActionIdempotencyService.execute(any(), any())).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            java.util.function.Supplier<Map<String, Object>> action = invocation.getArgument(1);
            return action.get();
        });
    }

    // ==================== Fixtures ====================

    private User buildUser(Long id, String username, String roleCode) {
        User u = new User();
        u.setId(id);
        u.setUsername(username);
        u.setRoleCode(roleCode);
        u.setIsActive(true);
        return u;
    }

    private ApprovalWorkflowInstance buildInstance(String instanceId, String workflowId,
                                                   String moduleCode, String bizId,
                                                   List<String> activeNodes, Long initiator) {
        ApprovalWorkflowInstance inst = ApprovalWorkflowInstance.builder()
                .id(instanceId)
                .factoryId(FACTORY_ID)
                .workflowId(workflowId)
                .moduleCode(moduleCode)
                .businessEntityId(bizId)
                .status(InstanceStatus.RUNNING)
                .currentNodeIds(new ArrayList<>(activeNodes))
                .contextJson(new HashMap<>())
                .initiatedBy(initiator)
                .initiatedAt(LocalDateTime.now())
                .build();
        return inst;
    }

    private PurchaseOrder buildPo(String id, String orderNum, BigDecimal amount, String supplierName) {
        PurchaseOrder po = new PurchaseOrder();
        po.setId(id);
        po.setFactoryId(FACTORY_ID);
        po.setOrderNumber(orderNum);
        po.setTotalAmount(amount);
        // supplierName 是 @Formula 字段, 测试里用 reflection 直接 set.
        ReflectionTestUtils.setField(po, "supplierName", supplierName);
        return po;
    }

    private void mockAuth(Long userId, String username, String roleCode) {
        UserDTO dto = new UserDTO();
        dto.setId(userId);
        dto.setUsername(username);
        lenient().when(mobileService.getUserFromToken(anyString())).thenReturn(dto);
        User user = buildUser(userId, username, roleCode);
        lenient().when(userRepository.findById(userId)).thenReturn(Optional.of(user));
    }

    private ApprovalWorkflow buildWorkflow(String id, String label, String approverRole) {
        ApprovalWorkflow wf = new ApprovalWorkflow();
        wf.setId(id);
        wf.setFactoryId(FACTORY_ID);
        wf.setNodesJson("[]"); // body irrelevant; deserializeNodes mocked
        // mock per-call return
        lenient().when(approvalWorkflowService.getById(FACTORY_ID, id))
                .thenReturn(Optional.of(wf));
        lenient().when(approvalWorkflowService.deserializeNodes(anyString()))
                .thenReturn(List.of(
                        ApprovalWorkflowNode.builder()
                                .id("approval_finance")
                                .type("approval")
                                .label(label)
                                .config(Map.of("approverRoles", List.of(approverRole)))
                                .build()));
        return wf;
    }

    // ==================== Tests ====================

    @Test
    @DisplayName("finance_mgr_sees_pending_with_finance_node: finance_manager 看到财务节点 pending PO")
    void finance_mgr_sees_pending_with_finance_node() {
        // user
        mockAuth(100L, "f006_finance_mgr", "finance_manager");

        // 1 RUNNING instance with finance approval node
        String poId = "po-" + UUID.randomUUID();
        ApprovalWorkflowInstance inst = buildInstance(
                "inst-1", "wf-1", "PURCHASE_ORDER", poId,
                List.of("approval_finance"), 200L);

        Pageable expectedPageable = PageRequest.of(0, 20);
        Page<ApprovalWorkflowInstance> page = new PageImpl<>(List.of(inst), expectedPageable, 1L);
        when(workflowEngine.findPendingForRole(eq(FACTORY_ID), eq("finance_manager"),
                eq(null), any(Pageable.class))).thenReturn(page);

        // workflow + nodes hydration
        buildWorkflow("wf-1", "财务审批", "finance_manager");

        // PO hydration
        PurchaseOrder po = buildPo(poId, "PO-20260518-0001",
                new BigDecimal("40000"), "供应商甲");
        when(purchaseOrderRepository.findAllById(any())).thenReturn(List.of(po));

        // initiator username hydration
        User initiator = buildUser(200L, "f006_procurement_mgr", "procurement_manager");
        when(userRepository.findAllById(any())).thenReturn(List.of(initiator));

        // exec
        ApiResponse<PageResponse<WorkflowInstancePendingDTO>> resp =
                controller.getPendingForUser(FACTORY_ID, AUTH_HEADER, null, 1, 20);

        // assert
        assertTrue(resp.getSuccess());
        assertNotNull(resp.getData());
        assertEquals(1, resp.getData().getContent().size());
        WorkflowInstancePendingDTO dto = resp.getData().getContent().get(0);
        assertEquals("inst-1", dto.getInstanceId());
        assertEquals("PURCHASE_ORDER", dto.getModuleCode());
        assertEquals(poId, dto.getBusinessEntityId());
        assertEquals("approval_finance", dto.getCurrentNodeId());
        assertEquals("财务审批", dto.getCurrentNodeLabel());
        assertEquals(List.of("finance_manager"), dto.getApproverRoles());
        assertEquals("f006_procurement_mgr", dto.getInitiatedByUsername());
        // businessSummary 应含订单号 + 金额 + 供应商名
        assertNotNull(dto.getBusinessSummary());
        assertTrue(dto.getBusinessSummary().contains("PO-20260518-0001"));
        assertTrue(dto.getBusinessSummary().contains("40000"));
        assertTrue(dto.getBusinessSummary().contains("供应商甲"));

        // 验证 findPendingForRole 调用参数
        ArgumentCaptor<Pageable> pageCap = ArgumentCaptor.forClass(Pageable.class);
        verify(workflowEngine).findPendingForRole(eq(FACTORY_ID), eq("finance_manager"),
                eq(null), pageCap.capture());
        assertEquals(0, pageCap.getValue().getPageNumber(), "page 1 → spring 0-based");
        assertEquals(20, pageCap.getValue().getPageSize());
    }

    @Test
    @DisplayName("quality_mgr_sees_zero_pending: 无关角色返空")
    void quality_mgr_sees_zero_pending() {
        mockAuth(101L, "f006_quality_mgr", "quality_manager");
        // service 在它 in-memory filter 阶段把 finance-only instance 过滤掉, 返空 page
        when(workflowEngine.findPendingForRole(eq(FACTORY_ID), eq("quality_manager"),
                eq(null), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 20), 0L));

        ApiResponse<PageResponse<WorkflowInstancePendingDTO>> resp =
                controller.getPendingForUser(FACTORY_ID, AUTH_HEADER, null, 1, 20);

        assertTrue(resp.getSuccess());
        assertEquals(0, resp.getData().getContent().size());
        assertEquals(0L, resp.getData().getTotalElements());
    }

    @Test
    @DisplayName("factory_super_admin_sees_all: super_admin 看全部 (兜底)")
    void factory_super_admin_sees_all() {
        mockAuth(102L, "f006_admin", "factory_super_admin");

        // mock returning 2 instances regardless of role (service 内部 super_admin 兜底逻辑)
        ApprovalWorkflowInstance inst1 = buildInstance(
                "inst-finance", "wf-1", "PURCHASE_ORDER", "po-A",
                List.of("approval_finance"), 200L);
        ApprovalWorkflowInstance inst2 = buildInstance(
                "inst-quality", "wf-2", "PURCHASE_ORDER", "po-B",
                List.of("approval_quality"), 201L);

        when(workflowEngine.findPendingForRole(eq(FACTORY_ID), eq("factory_super_admin"),
                eq(null), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(inst1, inst2),
                        PageRequest.of(0, 20), 2L));

        // workflow nodes hydration for both (mock returns same nodes for any workflowId)
        ApprovalWorkflow wf = new ApprovalWorkflow();
        wf.setId("wf-x");
        wf.setNodesJson("[]");
        when(approvalWorkflowService.getById(eq(FACTORY_ID), anyString()))
                .thenReturn(Optional.of(wf));
        when(approvalWorkflowService.deserializeNodes(anyString()))
                .thenReturn(List.of(
                        ApprovalWorkflowNode.builder()
                                .id("approval_finance").type("approval").label("财务审批").build(),
                        ApprovalWorkflowNode.builder()
                                .id("approval_quality").type("approval").label("质检审批").build()));

        // No PO hydration available — fallback summary used
        when(purchaseOrderRepository.findAllById(any())).thenReturn(List.of());
        when(userRepository.findAllById(any())).thenReturn(List.of());

        ApiResponse<PageResponse<WorkflowInstancePendingDTO>> resp =
                controller.getPendingForUser(FACTORY_ID, AUTH_HEADER, null, 1, 20);

        assertTrue(resp.getSuccess());
        assertEquals(2, resp.getData().getContent().size());
        // fallback summary still set
        assertNotNull(resp.getData().getContent().get(0).getBusinessSummary());
    }

    @Test
    @DisplayName("pagination_works: page=2 size=5 → Pageable 0-based offset 5 size 5")
    void pagination_works() {
        mockAuth(100L, "f006_finance_mgr", "finance_manager");

        when(workflowEngine.findPendingForRole(eq(FACTORY_ID), eq("finance_manager"),
                eq("PURCHASE_ORDER"), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(1, 5), 12L));

        ApiResponse<PageResponse<WorkflowInstancePendingDTO>> resp =
                controller.getPendingForUser(FACTORY_ID, AUTH_HEADER, "PURCHASE_ORDER", 2, 5);

        assertTrue(resp.getSuccess());
        assertEquals(12L, resp.getData().getTotalElements());
        assertEquals(2, resp.getData().getPage());
        assertEquals(5, resp.getData().getSize());

        ArgumentCaptor<Pageable> pageCap = ArgumentCaptor.forClass(Pageable.class);
        verify(workflowEngine, times(1)).findPendingForRole(eq(FACTORY_ID),
                eq("finance_manager"), eq("PURCHASE_ORDER"), pageCap.capture());
        assertEquals(1, pageCap.getValue().getPageNumber(), "page 2 → spring 1-based");
        assertEquals(5, pageCap.getValue().getPageSize());
    }

    @Test
    @DisplayName("personal OA acted and copied endpoints use current user identity and role")
    void personal_oa_acted_and_copied_use_current_user() {
        mockAuth(108L, "f006_finance", "finance_manager");
        Pageable pageable = PageRequest.of(0, 20);
        ApprovalWorkflowInstance instance = buildInstance(
                "inst-personal", "wf-personal", "PURCHASE_ORDER", "po-personal",
                List.of("approval_finance"), 200L);
        Page<ApprovalWorkflowInstance> result =
                new PageImpl<>(List.of(instance), pageable, 1L);

        when(workflowEngine.findActedBy(
                eq(FACTORY_ID), eq(108L), any(Pageable.class))).thenReturn(result);
        when(workflowEngine.findCopiedTo(
                eq(FACTORY_ID), eq(108L), eq("finance_manager"), any(Pageable.class)))
                .thenReturn(result);
        buildWorkflow("wf-personal", "财务审批", "finance_manager");
        when(purchaseOrderRepository.findAllById(any())).thenReturn(List.of());
        when(userRepository.findAllById(any())).thenReturn(List.of());

        ApiResponse<PageResponse<WorkflowInstancePendingDTO>> acted =
                controller.getActed(FACTORY_ID, AUTH_HEADER, 1, 20);
        ApiResponse<PageResponse<WorkflowInstancePendingDTO>> copied =
                controller.getCopied(FACTORY_ID, AUTH_HEADER, 1, 20);

        assertTrue(acted.getSuccess());
        assertEquals(1, acted.getData().getTotalElements());
        assertTrue(copied.getSuccess());
        assertEquals(1, copied.getData().getTotalElements());
        verify(workflowEngine).findActedBy(
                eq(FACTORY_ID), eq(108L), any(Pageable.class));
        verify(workflowEngine).findCopiedTo(
                eq(FACTORY_ID), eq(108L), eq("finance_manager"), any(Pageable.class));
    }

    @Test
    @DisplayName("sales OA action delegates to sales domain adapter and returns projected status")
    void sales_oa_action_delegates_to_sales_domain_adapter() {
        mockAuth(108L, "f006_finance", "finance_manager");
        ApprovalWorkflowInstance running = buildInstance(
                "inst-sales", "wf-sales", "SALES_ORDER", "so-1",
                List.of("approval_finance"), 200L);
        when(workflowEngine.getInstance(FACTORY_ID, "inst-sales")).thenReturn(Optional.of(running));
        when(workflowEngine.canTransition(eq(running), any(User.class)))
                .thenReturn(true);

        SalesOrder order = new SalesOrder();
        order.setId("so-1");
        order.setStatus(SalesOrderStatus.FINANCE_APPROVED);
        when(salesService.applyWorkflowAction(
                eq(FACTORY_ID), eq("so-1"), eq("inst-sales"), eq(108L),
                eq("finance_manager"), eq(HistoryAction.APPROVE), eq("approved")))
                .thenReturn(order);
        ApprovalWorkflowInstance approved = buildInstance(
                "inst-sales", "wf-sales", "SALES_ORDER", "so-1", List.of(), 200L);
        approved.setStatus(InstanceStatus.APPROVED);
        when(workflowEngine.getLatestInstance(FACTORY_ID, "SALES_ORDER", "so-1"))
                .thenReturn(Optional.of(approved));

        ApiResponse<Map<String, Object>> response = controller.executeAction(
                FACTORY_ID,
                "inst-sales",
                AUTH_HEADER,
                new WorkflowInstanceController.WorkflowActionRequest(
                        "APPROVE", "approved", "idem-sales-1", "approval_finance"));

        assertTrue(response.getSuccess());
        assertEquals("APPROVED", response.getData().get("workflowStatus"));
        assertEquals("FINANCE_APPROVED", response.getData().get("businessStatus"));
        verify(salesService).applyWorkflowAction(
                FACTORY_ID, "so-1", "inst-sales", 108L, "finance_manager",
                HistoryAction.APPROVE, "approved");
    }

    @Test
    void pending_sales_order_summary_is_batch_hydrated() {
        mockAuth(108L, "f006_finance", "finance_manager");
        ApprovalWorkflowInstance instance = buildInstance(
                "inst-sales-summary", "wf-sales", "SALES_ORDER", "so-summary",
                List.of("approval_finance"), 200L);
        when(workflowEngine.findPendingForRole(
                eq(FACTORY_ID), eq("finance_manager"), eq("SALES_ORDER"), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(instance), PageRequest.of(0, 20), 1L));
        buildWorkflow("wf-sales", "财务审批", "finance_manager");
        SalesOrder order = new SalesOrder();
        order.setId("so-summary");
        order.setOrderNumber("SO-20260722-0099");
        order.setCustomerName("测试客户");
        order.setTotalAmount(new BigDecimal("1440.00"));
        when(salesOrderRepository.findAllById(any())).thenReturn(List.of(order));
        when(userRepository.findAllById(any())).thenReturn(List.of());

        ApiResponse<PageResponse<WorkflowInstancePendingDTO>> response =
                controller.getPendingForUser(
                        FACTORY_ID, AUTH_HEADER, "SALES_ORDER", 1, 20);

        assertEquals("SO-20260722-0099 ¥1440.00 (测试客户)",
                response.getData().getContent().get(0).getBusinessSummary());
        verify(salesOrderRepository).findAllById(any());
    }

    @Test
    void completed_action_replay_returns_durable_result_without_domain_reexecution() {
        mockAuth(108L, "f006_finance", "finance_manager");
        ApprovalWorkflowInstance approved = buildInstance(
                "inst-replay", "wf-sales", "SALES_ORDER", "so-replay", List.of(), 200L);
        approved.setStatus(InstanceStatus.APPROVED);
        when(workflowEngine.getInstance(FACTORY_ID, "inst-replay")).thenReturn(Optional.of(approved));
        doReturn(Map.of(
                "instanceId", "inst-replay",
                "workflowStatus", "APPROVED",
                "businessEntityId", "so-replay",
                "businessStatus", "FINANCE_APPROVED"))
                .when(oaActionIdempotencyService).execute(any(), any());

        ApiResponse<Map<String, Object>> response = controller.executeAction(
                FACTORY_ID,
                "inst-replay",
                AUTH_HEADER,
                new WorkflowInstanceController.WorkflowActionRequest(
                        "APPROVE", "approved", "same-key", "approval_finance"));

        assertEquals("APPROVED", response.getData().get("workflowStatus"));
        verify(salesService, never()).applyWorkflowAction(
                anyString(), anyString(), anyString(), any(), anyString(), any(), any());
    }

    @Test
    @DisplayName("inventory transfer OA action delegates to transfer adapter")
    void inventory_transfer_action_delegates_to_transfer_adapter() {
        mockAuth(109L, "f006_warehouse_manager", "warehouse_manager");
        ApprovalWorkflowInstance running = buildInstance(
                "inst-transfer", "wf-transfer", "INVENTORY_TRANSFER", "trf-1",
                List.of("approval_warehouse"), 200L);
        when(workflowEngine.getInstance(FACTORY_ID, "inst-transfer"))
                .thenReturn(Optional.of(running));
        when(workflowEngine.canTransition(eq(running), any(User.class))).thenReturn(true);

        InternalTransfer transfer = new InternalTransfer();
        transfer.setId("trf-1");
        transfer.setStatus(TransferStatus.APPROVED);
        when(transferService.applyWorkflowAction(
                eq(FACTORY_ID), eq("trf-1"), eq("inst-transfer"), eq(109L),
                eq("warehouse_manager"), eq(HistoryAction.APPROVE), eq("同意")))
                .thenReturn(transfer);
        ApprovalWorkflowInstance approved = buildInstance(
                "inst-transfer", "wf-transfer", "INVENTORY_TRANSFER", "trf-1",
                List.of(), 200L);
        approved.setStatus(InstanceStatus.APPROVED);
        when(workflowEngine.getLatestInstance(
                FACTORY_ID, "INVENTORY_TRANSFER", "trf-1"))
                .thenReturn(Optional.of(approved));

        ApiResponse<Map<String, Object>> response = controller.executeAction(
                FACTORY_ID,
                "inst-transfer",
                AUTH_HEADER,
                new WorkflowInstanceController.WorkflowActionRequest(
                        "APPROVE", "同意", "idem-transfer-1", "approval_warehouse"));

        assertEquals("APPROVED", response.getData().get("workflowStatus"));
        assertEquals("APPROVED", response.getData().get("businessStatus"));
        verify(transferService).applyWorkflowAction(
                FACTORY_ID, "trf-1", "inst-transfer", 109L,
                "warehouse_manager", HistoryAction.APPROVE, "同意");
    }
}
