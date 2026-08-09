package com.cretas.aims.controller.operations;

import com.cretas.aims.annotation.RequireModule;
import com.cretas.aims.annotation.RequirePermission;
import com.cretas.aims.dto.inventory.ReviewCustomerMaterialArrivalNoticeRequest;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

class CustomerMaterialArrivalNoticeControllerTest {

    @Test
    void requestWritesStayOperationsOwnedAndReviewIsWarehouseOwned() throws Exception {
        Method create = CustomerMaterialArrivalNoticeController.class.getMethod(
                "create", String.class, String.class,
                com.cretas.aims.dto.inventory.CreateCustomerMaterialArrivalNoticeRequest.class);
        Method approve = CustomerMaterialArrivalNoticeController.class.getMethod(
                "approve", String.class, String.class, String.class,
                ReviewCustomerMaterialArrivalNoticeRequest.class);
        Method reject = CustomerMaterialArrivalNoticeController.class.getMethod(
                "reject", String.class, String.class, String.class,
                ReviewCustomerMaterialArrivalNoticeRequest.class);

        assertThat(create.getAnnotation(RequireModule.class).value()).isEqualTo("operations");
        assertThat(Arrays.asList(create.getAnnotation(RequirePermission.class).value()))
                .contains("operations:read_write");
        for (Method review : new Method[]{approve, reject}) {
            assertThat(review.getAnnotation(RequireModule.class).value()).isEqualTo("warehouse");
            assertThat(Arrays.asList(review.getAnnotation(RequirePermission.class).value()))
                    .contains("warehouse:read_write");
        }
    }

    @Test
    void applicationListCanBeReadByOperationsOrWarehouse() throws Exception {
        Method list = CustomerMaterialArrivalNoticeController.class.getMethod(
                "list", String.class, boolean.class);

        assertThat(Arrays.asList(list.getAnnotation(RequirePermission.class).value()))
                .contains("operations:read", "operations:read_write",
                        "warehouse:read", "warehouse:read_write");
    }
}
