package com.cretas.aims.controller.warehouse;

import com.cretas.aims.annotation.RequirePermission;
import com.cretas.aims.dto.inventory.CustomerSuppliedMaterialReceiptRequest;
import com.cretas.aims.dto.material.MaterialBatchDTO;
import com.cretas.aims.dto.user.UserDTO;
import com.cretas.aims.service.MobileService;
import com.cretas.aims.service.inventory.SalesOrderSuppliedMaterialRequirementService;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CustomerSuppliedMaterialReceivingTaskControllerTest {

    @Test
    void receiptMutationUsesAuthenticatedUserAndWarehouseWriteBoundary() throws Exception {
        SalesOrderSuppliedMaterialRequirementService service =
                mock(SalesOrderSuppliedMaterialRequirementService.class);
        MobileService mobileService = mock(MobileService.class);
        CustomerSuppliedMaterialReceivingTaskController controller =
                new CustomerSuppliedMaterialReceivingTaskController(service, mobileService);
        UserDTO user = new UserDTO();
        user.setId(1309L);
        when(mobileService.getUserFromToken("token-1")).thenReturn(user);
        CustomerSuppliedMaterialReceiptRequest request = new CustomerSuppliedMaterialReceiptRequest();
        request.setIdempotencyKey("receipt-key");
        request.setReceivedQuantity(BigDecimal.ONE);
        MaterialBatchDTO batch = new MaterialBatchDTO();
        when(service.receive("F006", "task-1", request, 1309L)).thenReturn(batch);

        assertThat(controller.receive("F006", "task-1", "Bearer token-1", request).getData())
                .isSameAs(batch);
        verify(service).receive("F006", "task-1", request, 1309L);

        RequirePermission permission = CustomerSuppliedMaterialReceivingTaskController.class
                .getMethod("receive", String.class, String.class, String.class,
                        CustomerSuppliedMaterialReceiptRequest.class)
                .getAnnotation(RequirePermission.class);
        assertThat(permission).isNotNull();
        assertThat(Arrays.asList(permission.value()))
                .containsExactlyInAnyOrder("warehouse:read_write", "inventory:write");
    }
}
