package com.cretas.aims.service.workflow.impl;

import com.cretas.aims.entity.User;
import com.cretas.aims.entity.config.ApprovalWorkflow;
import com.cretas.aims.entity.config.ApprovalWorkflowNode;
import com.cretas.aims.entity.workflow.ApprovalWorkflowInstance;
import com.cretas.aims.entity.workflow.ApprovalWorkflowInstance.InstanceStatus;
import com.cretas.aims.repository.workflow.ApprovalHistoryRepository;
import com.cretas.aims.repository.workflow.ApprovalWorkflowInstanceRepository;
import com.cretas.aims.service.ApprovalWorkflowService;
import com.cretas.aims.service.workflow.SandboxedSpelEvaluator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link WorkflowEngineServiceImpl#canTransition} — Phase 2 issue #13.
 *
 * <p>验证 workflow-scoped RBAC 替代静态 {@code procurement:read_write} permission:
 * <ul>
 *   <li>finance_manager 在 approverRoles=["finance_manager"] 节点 → 放行</li>
 *   <li>quality_manager 无关角色 → 拒绝</li>
 *   <li>factory_super_admin → 总是放行 (兜底)</li>
 *   <li>非 RUNNING / 空 active nodes / non-approval node → 拒绝</li>
 *   <li>workflow config 缺失 → fail-closed (返 false)</li>
 * </ul>
 *
 * @since 2026-05-18 (Phase 2 issue #13)
 */
@DisplayName("WorkflowEngineServiceImpl#canTransition — issue #13 workflow-scoped RBAC")
@ExtendWith(MockitoExtension.class)
class WorkflowEngineServiceImplCanTransitionTest {

    private static final String FACTORY_ID = "F006";
    private static final String WORKFLOW_ID = "wf-test-001";
    private static final String INSTANCE_ID = "inst-001";
    private static final String ORDER_ID = "po-001";
    private static final String APPROVAL_NODE_ID = "approval_finance";

    @Mock private ApprovalWorkflowInstanceRepository instanceRepository;
    @Mock private ApprovalHistoryRepository historyRepository;
    @Mock private ApprovalWorkflowService workflowService;

    private final SandboxedSpelEvaluator spelEvaluator = new SandboxedSpelEvaluator();
    private WorkflowEngineServiceImpl engine;

    @BeforeEach
    void setUp() {
        engine = new WorkflowEngineServiceImpl(
                instanceRepository,
                historyRepository,
                workflowService,
                spelEvaluator,
                /* redisTemplate = */ null);
    }

    // ==================== fixtures ====================

    private User buildUser(Long id, String roleCode) {
        User u = new User();
        u.setId(id);
        u.setUsername("u" + id);
        u.setRoleCode(roleCode);
        return u;
    }

    private ApprovalWorkflowInstance buildRunningInstance(List<String> currentNodes) {
        return ApprovalWorkflowInstance.builder()
                .id(INSTANCE_ID)
                .factoryId(FACTORY_ID)
                .workflowId(WORKFLOW_ID)
                .moduleCode("PURCHASE_ORDER")
                .businessEntityId(ORDER_ID)
                .status(InstanceStatus.RUNNING)
                .currentNodeIds(new ArrayList<>(currentNodes))
                .build();
    }

    /**
     * Mock {@code workflowService.getById + deserializeNodes} to return one approval node
     * whose {@code config.approverRoles} = supplied list.
     */
    private void mockWorkflowWithApprovalNode(String nodeId, List<String> approverRoles) {
        ApprovalWorkflow wf = new ApprovalWorkflow();
        wf.setId(WORKFLOW_ID);
        wf.setFactoryId(FACTORY_ID);
        wf.setNodesJson("[]"); // body 不重要 — deserializeNodes 被 mock.
        when(workflowService.getById(FACTORY_ID, WORKFLOW_ID)).thenReturn(Optional.of(wf));

        ApprovalWorkflowNode node = ApprovalWorkflowNode.builder()
                .id(nodeId)
                .type("approval")
                .label("财务审批")
                .config(Map.of("approverRoles", approverRoles))
                .build();
        when(workflowService.deserializeNodes(anyString())).thenReturn(List.of(node));
    }

    // ==================== tests ====================

    @Test
    @DisplayName("finance_manager 在 approverRoles=[finance_manager] approval 节点 → 放行")
    void financeManager_inApproverRoles_allowed() {
        mockWorkflowWithApprovalNode(APPROVAL_NODE_ID, List.of("finance_manager"));
        ApprovalWorkflowInstance instance = buildRunningInstance(List.of(APPROVAL_NODE_ID));
        User user = buildUser(10L, "finance_manager");

        assertTrue(engine.canTransition(instance, user),
                "finance_manager 在 approverRoles 中应允许 transition");
    }

    @Test
    @DisplayName("quality_manager 不在 approverRoles → 拒绝")
    void unrelatedRole_notInApproverRoles_denied() {
        mockWorkflowWithApprovalNode(APPROVAL_NODE_ID, List.of("finance_manager"));
        ApprovalWorkflowInstance instance = buildRunningInstance(List.of(APPROVAL_NODE_ID));
        User user = buildUser(20L, "quality_manager");

        assertFalse(engine.canTransition(instance, user),
                "无关角色 quality_manager 不在 approverRoles 应拒绝");
    }

    @Test
    @DisplayName("factory_super_admin 总是放行 (无需查 approverRoles)")
    void superAdmin_alwaysAllowed() {
        // 不需要 mock workflow — super_admin 在第一步就 short-circuit.
        ApprovalWorkflowInstance instance = buildRunningInstance(List.of(APPROVAL_NODE_ID));
        User user = buildUser(1L, "factory_super_admin");

        assertTrue(engine.canTransition(instance, user),
                "factory_super_admin 应总是放行");

        // 验证 short-circuit — 不应触发 workflowService 查询.
        verify(workflowService, never()).getById(anyString(), anyString());
    }

    @Test
    @DisplayName("super_admin 跨 instance 状态都放行 (RUNNING 不是前置条件)")
    void superAdmin_allowedEvenIfInstanceNotRunning() {
        // super_admin 即使 instance APPROVED 也放行 — 行为是 short-circuit.
        // 实际 caller (controller) 不会在 APPROVED 状态走 canTransition (会先 short-circuit),
        // 但本 method 单独使用时这是 RBAC 短路语义, mirror legacy super_admin bypass.
        ApprovalWorkflowInstance instance = buildRunningInstance(List.of(APPROVAL_NODE_ID));
        instance.setStatus(InstanceStatus.APPROVED);
        User user = buildUser(1L, "factory_super_admin");

        assertTrue(engine.canTransition(instance, user));
    }

    @Test
    @DisplayName("非 RUNNING 实例 (APPROVED) → 拒绝 (普通角色)")
    void nonRunningInstance_denied() {
        ApprovalWorkflowInstance instance = buildRunningInstance(List.of(APPROVAL_NODE_ID));
        instance.setStatus(InstanceStatus.APPROVED);
        User user = buildUser(10L, "finance_manager");

        assertFalse(engine.canTransition(instance, user),
                "APPROVED 终态实例不应允许 transition");
    }

    @Test
    @DisplayName("currentNodeIds 为空 → 拒绝")
    void noActiveNode_denied() {
        ApprovalWorkflowInstance instance = buildRunningInstance(List.of());
        User user = buildUser(10L, "finance_manager");

        assertFalse(engine.canTransition(instance, user),
                "无 active node 不应允许 transition");
    }

    @Test
    @DisplayName("active node type != approval (e.g. condition) → 拒绝")
    void nonApprovalNode_denied() {
        ApprovalWorkflow wf = new ApprovalWorkflow();
        wf.setId(WORKFLOW_ID);
        wf.setFactoryId(FACTORY_ID);
        wf.setNodesJson("[]");
        when(workflowService.getById(FACTORY_ID, WORKFLOW_ID)).thenReturn(Optional.of(wf));
        // condition 节点 — 自动节点, 不接受人工 transition.
        when(workflowService.deserializeNodes(anyString())).thenReturn(List.of(
                ApprovalWorkflowNode.builder()
                        .id(APPROVAL_NODE_ID)
                        .type("condition")
                        .config(Map.of("approverRoles", List.of("finance_manager")))
                        .build()));

        ApprovalWorkflowInstance instance = buildRunningInstance(List.of(APPROVAL_NODE_ID));
        User user = buildUser(10L, "finance_manager");

        assertFalse(engine.canTransition(instance, user),
                "type=condition 不是 approval 节点, 应拒绝人工 transition");
    }

    @Test
    @DisplayName("approverRoles 含多角色 → 任一匹配即放行")
    void multipleApproverRoles_anyMatchAllows() {
        mockWorkflowWithApprovalNode(APPROVAL_NODE_ID,
                List.of("finance_manager", "operations_manager"));
        ApprovalWorkflowInstance instance = buildRunningInstance(List.of(APPROVAL_NODE_ID));

        assertTrue(engine.canTransition(instance, buildUser(10L, "finance_manager")));
        assertTrue(engine.canTransition(instance, buildUser(11L, "operations_manager")));
        assertFalse(engine.canTransition(instance, buildUser(12L, "quality_manager")));
    }

    @Test
    @DisplayName("workflow config 缺失 → fail-closed (false)")
    void workflowMissing_failClosed() {
        when(workflowService.getById(FACTORY_ID, WORKFLOW_ID)).thenReturn(Optional.empty());
        ApprovalWorkflowInstance instance = buildRunningInstance(List.of(APPROVAL_NODE_ID));
        User user = buildUser(10L, "finance_manager");

        assertFalse(engine.canTransition(instance, user),
                "workflow config 不存在时应 fail-closed 返 false");
    }

    @Test
    @DisplayName("null instance / null user / null roleCode → false (defensive)")
    void nullInputs_returnFalse() {
        assertFalse(engine.canTransition(null, buildUser(1L, "finance_manager")));
        assertFalse(engine.canTransition(buildRunningInstance(List.of(APPROVAL_NODE_ID)), null));
        // null roleCode user
        User userNoRole = new User();
        userNoRole.setId(99L);
        userNoRole.setUsername("noRole");
        assertFalse(engine.canTransition(buildRunningInstance(List.of(APPROVAL_NODE_ID)), userNoRole));
    }
}
