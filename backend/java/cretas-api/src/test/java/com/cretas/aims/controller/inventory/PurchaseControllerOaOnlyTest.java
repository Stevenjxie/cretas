package com.cretas.aims.controller.inventory;

import com.cretas.aims.config.RequireRole;
import com.cretas.aims.dto.inventory.PurchaseApprovalRecoveryResponse;
import com.cretas.aims.dto.inventory.RecoverPurchaseApprovalRequest;
import com.cretas.aims.dto.user.UserDTO;
import com.cretas.aims.entity.enums.PurchaseOrderStatus;
import com.cretas.aims.entity.workflow.ApprovalWorkflowInstance;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.repository.factory.FactoryWarehouseRepository;
import com.cretas.aims.service.MobileService;
import com.cretas.aims.service.PermissionService;
import com.cretas.aims.service.factory.WarehouseResolver;
import com.cretas.aims.service.inventory.PurchaseOrderPdfService;
import com.cretas.aims.service.inventory.PurchaseService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PurchaseControllerOaOnlyTest {

    private PurchaseController controller;
    private PurchaseService purchaseService;
    private MobileService mobileService;

    @BeforeEach
    void setUp() {
        purchaseService = mock(PurchaseService.class);
        mobileService = mock(MobileService.class);
        controller = new PurchaseController(
                purchaseService,
                mock(PurchaseOrderPdfService.class),
                mobileService,
                mock(PermissionService.class),
                mock(UserRepository.class),
                mock(WarehouseResolver.class),
                mock(FactoryWarehouseRepository.class));
    }

    @Test
    void legacyBusinessAndFinanceApprovalEndpointsAreFailClosed() {
        assertOaOnly(() -> controller.approveOrder("F006", "po-1", "Bearer token"));
        assertOaOnly(() -> controller.submitForFinanceReview("F006", "po-1"));
        assertOaOnly(() -> controller.financeApprove(
                "F006", "po-1", "Bearer token", Map.of("notes", "ok")));
        assertOaOnly(() -> controller.financeReject(
                "F006", "po-1", "Bearer token", Map.of("notes", "reject")));
    }

    @Test
    void restrictedRecoveryUsesAuthenticatedOperatorAndReturnsWorkflowTruth() {
        UserDTO operator = new UserDTO();
        operator.setId(1309L);
        when(mobileService.getUserFromToken("token")).thenReturn(operator);
        PurchaseApprovalRecoveryResponse serviceResult = PurchaseApprovalRecoveryResponse.builder()
                .orderId("po-1")
                .orderNumber("PO-TEST-1")
                .orderStatus(PurchaseOrderStatus.WORKFLOW_RUNNING)
                .workflowInstanceId("instance-1")
                .workflowStatus(ApprovalWorkflowInstance.InstanceStatus.RUNNING)
                .currentNodeIds(List.of("finance-approval"))
                .recovered(true)
                .build();
        when(purchaseService.recoverMissingApprovalInstance(
                eq("F006"), eq("po-1"), eq(1309L), eq("PO-TEST-1"),
                eq("recovery:F006:po-1:1"), eq("修复历史提交缺少 OA 实例"), eq(true)))
                .thenReturn(serviceResult);
        RecoverPurchaseApprovalRequest request = new RecoverPurchaseApprovalRequest();
        request.setExpectedOrderNumber("PO-TEST-1");
        request.setIdempotencyKey("recovery:F006:po-1:1");
        request.setReason("修复历史提交缺少 OA 实例");
        request.setConfirm(true);

        var response = controller.recoverApprovalInstance(
                "F006", "po-1", "Bearer token", request);

        assertThat(response.getData().getWorkflowInstanceId()).isEqualTo("instance-1");
        assertThat(response.getData().isRecovered()).isTrue();
        verify(purchaseService).recoverMissingApprovalInstance(
                "F006", "po-1", 1309L, "PO-TEST-1",
                "recovery:F006:po-1:1", "修复历史提交缺少 OA 实例", true);
    }

    @Test
    void recoveryEndpointRequiresFactorySuperAdminRole() throws Exception {
        RequireRole requireRole = PurchaseController.class
                .getMethod("recoverApprovalInstance", String.class, String.class,
                        String.class, RecoverPurchaseApprovalRequest.class)
                .getAnnotation(RequireRole.class);

        assertThat(requireRole).isNotNull();
        assertThat(requireRole.value()).containsExactly("factory_super_admin");
    }

    private void assertOaOnly(Runnable call) {
        assertThatThrownBy(call::run)
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo("PURCHASE_APPROVAL_OA_ONLY");
    }
}
