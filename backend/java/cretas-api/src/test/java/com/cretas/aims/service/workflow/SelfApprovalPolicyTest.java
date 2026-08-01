package com.cretas.aims.service.workflow;

import com.cretas.aims.entity.config.ApprovalWorkflow;
import com.cretas.aims.entity.config.ApprovalWorkflowNode;
import com.cretas.aims.entity.User;
import com.cretas.aims.entity.workflow.ApprovalWorkflowInstance;
import com.cretas.aims.repository.UserRepository;
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
    private UserRepository userRepository;
    private SelfApprovalPolicy policy;

    @BeforeEach
    void setUp() {
        approvalWorkflowService = mock(ApprovalWorkflowService.class);
        userRepository = mock(UserRepository.class);
        when(userRepository.findByFactoryIdAndRoleCode(anyString(), anyString()))
                .thenReturn(List.of());
        policy = new SelfApprovalPolicy(approvalWorkflowService, userRepository);
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
        SelfApprovalPolicy noServicePolicy = new SelfApprovalPolicy(null, userRepository);
        assertThat(noServicePolicy.allowsSelfApproval(
                "LIUSHANMEN", instanceAtNode("admin_approval"), 1638L, "factory_super_admin"))
                .isTrue();
    }

    @Test
    @DisplayName("两个服务都缺席且非超管时保守拒绝")
    void missingCollaboratorsFallBackToRejection() {
        SelfApprovalPolicy noServicePolicy = new SelfApprovalPolicy(null, null);
        assertThat(noServicePolicy.allowsSelfApproval(
                "LIUSHANMEN", instanceAtNode("admin_approval"), 1638L, "sales_manager"))
                .isFalse();
    }

    @Test
    @DisplayName("角色码大小写不同也认 —— 沿用调拨单原有的 equalsIgnoreCase")
    void roleCodeIsCaseInsensitive() {
        givenNodeApprovers("admin_approval", List.of(9999));
        assertThat(policy.allowsSelfApproval(
                "LIUSHANMEN", instanceAtNode("admin_approval"), 1638L, "FACTORY_SUPER_ADMIN"))
                .isTrue();
    }

    @Test
    @DisplayName("actorRole 缺失时回落查库, 不让缺失的入参把例外吞掉")
    void missingActorRoleFallsBackToDatabase() {
        givenNodeApprovers("admin_approval", List.of(9999));
        User superAdmin = new User();
        superAdmin.setId(1638L);
        superAdmin.setIsActive(true);
        when(userRepository.findByFactoryIdAndRoleCode("LIUSHANMEN", "factory_super_admin"))
                .thenReturn(List.of(superAdmin));

        assertThat(policy.allowsSelfApproval(
                "LIUSHANMEN", instanceAtNode("admin_approval"), 1638L, null))
                .isTrue();
    }

    @Test
    @DisplayName("非超管且 actorRole 缺失时查库也找不到 —— 不放行")
    void nonSuperAdminWithMissingRoleIsRejected() {
        givenNodeApprovers("admin_approval", List.of(9999));
        User superAdmin = new User();
        superAdmin.setId(1638L);
        superAdmin.setIsActive(true);
        when(userRepository.findByFactoryIdAndRoleCode("LIUSHANMEN", "factory_super_admin"))
                .thenReturn(List.of(superAdmin));

        assertThat(policy.allowsSelfApproval(
                "LIUSHANMEN", instanceAtNode("admin_approval"), 2001L, null))
                .isFalse();
    }

    @Test
    @DisplayName("匿名 actor 永远不被当作超管")
    void anonymousActorIsNeverSuperAdmin() {
        givenNodeApprovers("admin_approval", List.of(1638));
        assertThat(policy.allowsSelfApproval(
                "LIUSHANMEN", instanceAtNode("admin_approval"), null, "factory_super_admin"))
                .isFalse();
    }

    @Test
    @DisplayName("库里的超管账号已停用则不放行")
    void inactiveSuperAdminIsNotHonoured() {
        givenNodeApprovers("admin_approval", List.of(9999));
        User disabled = new User();
        disabled.setId(1638L);
        disabled.setIsActive(false);
        when(userRepository.findByFactoryIdAndRoleCode("LIUSHANMEN", "factory_super_admin"))
                .thenReturn(List.of(disabled));

        assertThat(policy.allowsSelfApproval(
                "LIUSHANMEN", instanceAtNode("admin_approval"), 1638L, null))
                .isFalse();
    }
}
