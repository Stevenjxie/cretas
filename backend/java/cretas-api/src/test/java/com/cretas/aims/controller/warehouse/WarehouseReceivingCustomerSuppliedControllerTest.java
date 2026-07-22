package com.cretas.aims.controller.warehouse;

import com.cretas.aims.annotation.RequirePermission;
import com.cretas.aims.dto.inventory.CustomerSuppliedMaterialReceiptRequest;
import com.cretas.aims.dto.inventory.CustomerSuppliedMaterialReceivingTaskResponse;
import com.cretas.aims.dto.inventory.PurchaseReceivingTaskResponse;
import com.cretas.aims.dto.material.MaterialBatchDTO;
import com.cretas.aims.dto.user.UserDTO;
import com.cretas.aims.entity.enums.SalesOrderSuppliedMaterialRequirementStatus;
import com.cretas.aims.repository.factory.FactoryWarehouseRepository;
import com.cretas.aims.service.MobileService;
import com.cretas.aims.service.factory.WarehouseResolver;
import com.cretas.aims.service.inventory.PurchaseService;
import com.cretas.aims.service.inventory.SalesOrderSuppliedMaterialRequirementService;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WarehouseReceivingCustomerSuppliedControllerTest {

    @Test
    void unifiedTasksPreservePurchaseAndAddCustomerSuppliedProjection() {
        PurchaseService purchaseService = mock(PurchaseService.class);
        SalesOrderSuppliedMaterialRequirementService requirementService =
                mock(SalesOrderSuppliedMaterialRequirementService.class);
        WarehouseReceivingController controller = controller(
                purchaseService, requirementService, mock(MobileService.class));

        PurchaseReceivingTaskResponse purchase = PurchaseReceivingTaskResponse.builder()
                .taskId("PO-1")
                .sourceType("PURCHASE")
                .purchaseOrderId("PO-1")
                .orderNumber("PO-NO-1")
                .supplierId("SUP-1")
                .supplierName("供应商一")
                .build();
        when(purchaseService.getPendingReceivingTasks("F006", null, null))
                .thenReturn(List.of(purchase));

        CustomerSuppliedMaterialReceivingTaskResponse customer =
                CustomerSuppliedMaterialReceivingTaskResponse.builder()
                        .taskId("REQ-1")
                        .sourceType(CustomerSuppliedMaterialReceivingTaskResponse.SOURCE)
                        .status(SalesOrderSuppliedMaterialRequirementStatus.PENDING)
                        .customerId("CUS-1")
                        .customerName("客户一")
                        .salesOrderId("SO-1")
                        .salesOrderNumber("SO-NO-1")
                        .salesOrderItemId(726L)
                        .materialTypeId("RM-1")
                        .materialName("客供原料一")
                        .expectedQuantity(new BigDecimal("10"))
                        .receivedQuantity(BigDecimal.ZERO)
                        .remainingQuantity(new BigDecimal("10"))
                        .unit("kg")
                        .expectedArrivalAt(LocalDateTime.of(2026, 7, 23, 8, 0))
                        .targetWarehouseId("WH-RAW")
                        .targetWarehouseName("原料仓")
                        .build();
        when(requirementService.getPendingReceivingTasks("F006", null, null))
                .thenReturn(List.of(customer));

        List<PurchaseReceivingTaskResponse> tasks =
                controller.getTasks("F006", null, null, null, null, null).getData();

        assertThat(tasks).hasSize(2);
        assertThat(tasks.get(0))
                .extracting(PurchaseReceivingTaskResponse::getSourceId,
                        PurchaseReceivingTaskResponse::getSourceNumber,
                        PurchaseReceivingTaskResponse::getCounterpartyType,
                        PurchaseReceivingTaskResponse::getCounterpartyName)
                .containsExactly("PO-1", "PO-NO-1", "SUPPLIER", "供应商一");
        assertThat(tasks.get(1)).satisfies(task -> {
            assertThat(task.getSourceType())
                    .isEqualTo("SALES_ORDER_CUSTOMER_SUPPLIED");
            assertThat(task.getSourceId()).isEqualTo("SO-1");
            assertThat(task.getCounterpartyType()).isEqualTo("CUSTOMER");
            assertThat(task.getItems()).singleElement().satisfies(line -> {
                assertThat(line.getSalesOrderItemId()).isEqualTo(726L);
                assertThat(line.getRemainingReceivableQuantity())
                        .isEqualByComparingTo("10");
            });
        });
    }

    @Test
    void customerReceiptUsesAuthenticatedWarehouseBoundary() throws Exception {
        PurchaseService purchaseService = mock(PurchaseService.class);
        SalesOrderSuppliedMaterialRequirementService requirementService =
                mock(SalesOrderSuppliedMaterialRequirementService.class);
        MobileService mobileService = mock(MobileService.class);
        WarehouseReceivingController controller =
                controller(purchaseService, requirementService, mobileService);
        UserDTO user = new UserDTO();
        user.setId(1309L);
        when(mobileService.getUserFromToken("token-1")).thenReturn(user);
        CustomerSuppliedMaterialReceiptRequest request =
                new CustomerSuppliedMaterialReceiptRequest();
        request.setIdempotencyKey("key-1");
        request.setReceivedQuantity(BigDecimal.ONE);
        MaterialBatchDTO batch = new MaterialBatchDTO();
        when(requirementService.receive("F006", "REQ-1", request, 1309L))
                .thenReturn(batch);

        assertThat(controller.receiveCustomerSuppliedMaterial(
                "F006", "REQ-1", "Bearer token-1", request).getData())
                .isSameAs(batch);
        verify(requirementService).receive("F006", "REQ-1", request, 1309L);

        RequirePermission permission = WarehouseReceivingController.class
                .getMethod("receiveCustomerSuppliedMaterial",
                        String.class, String.class, String.class,
                        CustomerSuppliedMaterialReceiptRequest.class)
                .getAnnotation(RequirePermission.class);
        assertThat(permission).isNotNull();
        assertThat(Arrays.asList(permission.value()))
                .containsExactlyInAnyOrder("warehouse:read_write", "inventory:write");
    }

    private WarehouseReceivingController controller(
            PurchaseService purchaseService,
            SalesOrderSuppliedMaterialRequirementService requirementService,
            MobileService mobileService) {
        return new WarehouseReceivingController(
                purchaseService,
                mobileService,
                mock(WarehouseResolver.class),
                mock(FactoryWarehouseRepository.class),
                requirementService);
    }
}

