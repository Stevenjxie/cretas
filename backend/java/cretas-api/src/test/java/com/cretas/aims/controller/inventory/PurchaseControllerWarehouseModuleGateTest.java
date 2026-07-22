package com.cretas.aims.controller.inventory;

import com.cretas.aims.annotation.RequireModule;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PurchaseControllerWarehouseModuleGateTest {

    @Test
    void allWarehouseReceivingEndpointsRequireWarehouseModule() {
        List<String> receivingMethods = List.of(
                "getReceivingTasks",
                "createReceive",
                "getDefaultReceiveWarehouse",
                "listReceives",
                "getReceive",
                "confirmReceive",
                "getReceivesByOrder");

        for (String methodName : receivingMethods) {
            Method method = List.of(PurchaseController.class.getDeclaredMethods()).stream()
                    .filter(candidate -> candidate.getName().equals(methodName))
                    .findFirst()
                    .orElseThrow();
            RequireModule gate = method.getAnnotation(RequireModule.class);
            assertThat(gate)
                    .as("%s must require the warehouse module", methodName)
                    .isNotNull();
            assertThat(gate.value()).isEqualTo("warehouse");
        }
    }
}
