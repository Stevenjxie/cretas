package com.cretas.aims.controller.warehouse;

import com.cretas.aims.annotation.RequireModule;
import com.cretas.aims.dto.inventory.CreateReceiveRecordRequest;
import com.cretas.aims.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.RequestMapping;

import java.lang.reflect.Method;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class WarehouseReceivingControllerTest {

    @Test
    void canonicalNamespaceAndEveryEndpointAreWarehouseOwned() {
        RequestMapping mapping = WarehouseReceivingController.class.getAnnotation(RequestMapping.class);
        assertThat(mapping.value()).containsExactly("/api/mobile/{factoryId}/warehouse/receiving");

        for (String name : List.of(
                "getTasks", "receiveCustomerSuppliedMaterial",
                "getDefaultWarehouse", "createReceipt",
                "getReceipt", "getReceiptsByOrder", "confirmReceipt",
                "closePurchaseTaskShort")) {
            Method method = List.of(WarehouseReceivingController.class.getDeclaredMethods()).stream()
                    .filter(candidate -> candidate.getName().equals(name))
                    .findFirst()
                    .orElseThrow();
            RequireModule module = method.getAnnotation(RequireModule.class);
            assertThat(module).as(name).isNotNull();
            assertThat(module.value()).isEqualTo("warehouse");
        }
    }

    @Test
    void creatingReceiptWithoutApprovedPurchaseSourceFailsBeforeAnyWrite() {
        WarehouseReceivingController controller = new WarehouseReceivingController(
                null, null, null, null, null);
        CreateReceiveRecordRequest request = new CreateReceiveRecordRequest();

        BusinessException error = assertThrows(BusinessException.class,
                () -> controller.createReceipt("F006", "Bearer test", request));

        assertThat(error.getErrorCode()).isEqualTo("PURCHASE_RECEIPT_SOURCE_REQUIRED");
    }
}
