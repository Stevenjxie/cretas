package com.cretas.aims.service.workflow;

import com.cretas.aims.entity.config.ApprovalWorkflow;
import com.cretas.aims.entity.config.ApprovalWorkflowNode;
import com.cretas.aims.entity.workflow.ApprovalWorkflowInstance;
import com.cretas.aims.service.ApprovalWorkflowService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 「发起人能否审批自己的单」的判定语义。
 *
 * <p>背景: 六膳门(单人工厂)的 OA 节点把唯一的 factory_super_admin(1638) 同时配成
 * approverUserIds 和唯一授权角色, 而他正是发起人 —— 旧实现无条件禁止自审, 导致该单
 * 结构性无法审批, 且提示让用户"找其他审批人"而那个人不存在。
 */
class SelfApprovalPolicyTest {

    private ApprovalWorkflowService approvalWorkflowService;
    private SelfApprovalPolicy policy;

    @BeforeEach
    void setUp() {
        approvalWorkflowService = mock(ApprovalWorkflowService.class);
        policy = new SelfApprovalPolicy(approvalWorkflowService);
    }

    private ApprovalWorkflowInstance instanceAtNode(String nodeId) {
        ApprovalWorkflowInstance instance = new ApprovalWorkflowInstance();
        instance.setWorkflowId("wf-1");
        instance.setCurrentNodeIds(List.of(nodeId));
        return instance;
    }

    private void givenNodeApprovers(String nodeId, List<Object> approverUserIds) {
        ApprovalWorkflow workflow = new ApprovalWorkflow();
        workflow.setNodesJson("[]");
        when(approvalWorkflowService.getById(anyString(), anyString()))
                .thenReturn(Optional.of(workflow));
        ApprovalWorkflowNode node = ApprovalWorkflowNode.builder()
                .id(nodeId)
                .type("approval")
                .config(Map.of("approverUserIds", approverUserIds))
                .build();
        when(approvalWorkflowService.deserializeNodes(any())).thenReturn(List.of(node));
    }

    @Test
    @DisplayName("节点显式点名发起人时允许自审")
    void explicitlyNamedInitiatorMayApprove() {
        givenNodeApprovers("admin_approval", List.of(1638));
        assertThat(policy.allowsSelfApproval(
                "LIUSHANMEN", instanceAtNode("admin_approval"), 1638L, "sales_manager"))
                .isTrue();
    }

    @Test
    @DisplayName("工厂超管即使没被点名也允许自审")
    void factorySuperAdminMayApproveEvenWhenNotNamed() {
        givenNodeApprovers("admin_approval", List.of(9999));
        assertThat(policy.allowsSelfApproval(
                "LIUSHANMEN", instanceAtNode("admin_approval"), 1638L, "factory_super_admin"))
                .isTrue();
    }

    @Test
    @DisplayName("既未点名又非超管则不允许自审")
    void neitherNamedNorSuperAdminIsRejected() {
        givenNodeApprovers("admin_approval", List.of(9999));
        assertThat(policy.allowsSelfApproval(
                "LIUSHANMEN", instanceAtNode("admin_approval"), 1638L, "sales_manager"))
                .isFalse();
    }

    @Test
    @DisplayName("点名以字符串形式配置时同样识别")
    void namedApproverAsStringIsRecognised() {
        givenNodeApprovers("admin_approval", List.of("1638"));
        assertThat(policy.allowsSelfApproval(
                "LIUSHANMEN", instanceAtNode("admin_approval"), 1638L, "sales_manager"))
                .isTrue();
    }

    @Test
    @DisplayName("实例没有当前节点时不允许自审")
    void instanceWithoutCurrentNodeIsRejected() {
        ApprovalWorkflowInstance instance = new ApprovalWorkflowInstance();
        instance.setWorkflowId("wf-1");
        instance.setCurrentNodeIds(List.of());
        assertThat(policy.allowsSelfApproval("LIUSHANMEN", instance, 1638L, "sales_manager"))
                .isFalse();
    }

    @Test
    @DisplayName("非 approval 类型的节点不构成点名")
    void nonApprovalNodeIsNotAnExplicitNaming() {
        ApprovalWorkflow workflow = new ApprovalWorkflow();
        workflow.setNodesJson("[]");
        when(approvalWorkflowService.getById(anyString(), anyString()))
                .thenReturn(Optional.of(workflow));
        ApprovalWorkflowNode node = ApprovalWorkflowNode.builder()
                .id("start")
                .type("start")
                .config(Map.of("approverUserIds", List.of(1638)))
                .build();
        when(approvalWorkflowService.deserializeNodes(any())).thenReturn(List.of(node));

        assertThat(policy.allowsSelfApproval(
                "LIUSHANMEN", instanceAtNode("start"), 1638L, "sales_manager"))
                .isFalse();
    }

    @Test
    @DisplayName("超管判定不依赖工作流服务 —— 服务缺席时仍放行超管")
    void superAdminDecisionDoesNotNeedWorkflowService() {
        SelfApprovalPolicy noServicePolicy = new SelfApprovalPolicy(null);
        assertThat(noServicePolicy.allowsSelfApproval(
                "LIUSHANMEN", instanceAtNode("admin_approval"), 1638L, "factory_super_admin"))
                .isTrue();
    }

    @Test
    @DisplayName("服务缺席且非超管时保守拒绝")
    void missingWorkflowServiceFallsBackToRejection() {
        SelfApprovalPolicy noServicePolicy = new SelfApprovalPolicy(null);
        assertThat(noServicePolicy.allowsSelfApproval(
                "LIUSHANMEN", instanceAtNode("admin_approval"), 1638L, "sales_manager"))
                .isFalse();
    }
}
