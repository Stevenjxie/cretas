package com.cretas.aims.controller.inventory;

import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.entity.User;
import com.cretas.aims.entity.config.ApprovalWorkflow;
import com.cretas.aims.entity.config.ApprovalWorkflowNode;
import com.cretas.aims.entity.inventory.SalesOrder;
import com.cretas.aims.entity.workflow.ApprovalWorkflowInstance;
import com.cretas.aims.repository.BusinessLinkRepository;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.repository.inventory.FinishedGoodsBatchRepository;
import com.cretas.aims.repository.inventory.SalesOrderRepository;
import com.cretas.aims.service.ApprovalWorkflowService;
import com.cretas.aims.service.MobileService;
import com.cretas.aims.service.PermissionService;
import com.cretas.aims.service.inventory.SalesPriceAdjustmentService;
import com.cretas.aims.service.inventory.SalesService;
import com.cretas.aims.service.pricing.EstimatePriceCheckService;
import com.cretas.aims.service.pricing.GrossMarginRedlineService;
import com.cretas.aims.service.product.ProductPackagingSpecService;
import com.cretas.aims.service.workflow.WorkflowEngineService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SalesControllerApprovalProgressTest {

    @Test
    @SuppressWarnings("unchecked")
    void returnsLatestSalesOaNodeRolesAssigneesAndDeepLink() {
        SalesService salesService = mock(SalesService.class);
        UserRepository userRepository = mock(UserRepository.class);
        WorkflowEngineService workflowEngine = mock(WorkflowEngineService.class);
        ApprovalWorkflowService workflowService = mock(ApprovalWorkflowService.class);
        SalesController controller = new SalesController(
                salesService,
                mock(MobileService.class),
                mock(PermissionService.class),
                userRepository,
                mock(SalesOrderRepository.class),
                mock(BusinessLinkRepository.class),
                mock(ProductTypeRepository.class),
                mock(FinishedGoodsBatchRepository.class),
                mock(GrossMarginRedlineService.class),
                mock(EstimatePriceCheckService.class),
                mock(SalesPriceAdjustmentService.class),
                mock(ProductPackagingSpecService.class));
        ReflectionTestUtils.setField(controller, "workflowEngine", workflowEngine);
        ReflectionTestUtils.setField(controller, "approvalWorkflowService", workflowService);

        SalesOrder order = new SalesOrder();
        order.setId("so-1");
        when(salesService.getSalesOrderById("F006", "so-1")).thenReturn(order);
        ApprovalWorkflowInstance instance = ApprovalWorkflowInstance.builder()
                .id("inst-sales-1")
                .factoryId("F006")
                .workflowId("wf-sales")
                .moduleCode("SALES_ORDER")
                .businessEntityId("so-1")
                .status(ApprovalWorkflowInstance.InstanceStatus.RUNNING)
                .currentNodeIds(new ArrayList<>(List.of("approval_finance")))
                .initiatedBy(901L)
                .initiatedAt(LocalDateTime.now().minusMinutes(12))
                .build();
        when(workflowEngine.getLatestInstance("F006", "SALES_ORDER", "so-1"))
                .thenReturn(Optional.of(instance));

        ApprovalWorkflow workflow = new ApprovalWorkflow();
        workflow.setId("wf-sales");
        workflow.setNodesJson("[]");
        when(workflowService.getById("F006", "wf-sales")).thenReturn(Optional.of(workflow));
        when(workflowService.deserializeNodes("[]")).thenReturn(List.of(
                ApprovalWorkflowNode.builder()
                        .id("approval_finance")
                        .type("approval")
                        .label("财务审批")
                        .config(Map.of("approverRoles", List.of("finance_manager")))
                        .build()));
        User finance = new User();
        finance.setUsername("f006_finance");
        finance.setIsActive(true);
        when(userRepository.findByFactoryIdAndRoleCode("F006", "finance_manager"))
                .thenReturn(List.of(finance));

        ApiResponse<Map<String, Object>> response = controller.getApprovalProgress("F006", "so-1");

        assertThat(response.getSuccess()).isTrue();
        assertThat(response.getData())
                .containsEntry("hasInstance", true)
                .containsEntry("instanceId", "inst-sales-1")
                .containsEntry("status", "RUNNING")
                .containsEntry("deepLink", "/workflow/my-created?instanceId=inst-sales-1");
        assertThat((Collection<String>) response.getData().get("currentNodeNames"))
                .containsExactly("财务审批");
        assertThat((Collection<String>) response.getData().get("approverRoles"))
                .containsExactly("finance_manager");
        assertThat((Collection<String>) response.getData().get("assignees"))
                .containsExactly("f006_finance");
    }
}
