package com.cretas.aims.controller.restaurant;

import com.cretas.aims.config.RequireRole;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Restaurant P2P finance RBAC contract")
class RestaurantP2pFinanceRbacContractTest {

    @Test
    @DisplayName("supplier reconciliation controller is limited to finance/restaurant owner roles")
    void supplierReconciliationHasRoleGate() {
        RequireRole role = SupplierMonthlyReconciliationController.class.getAnnotation(RequireRole.class);
        assertP2pFinanceRoles(role);
    }

    @Test
    @DisplayName("cost attribution summary excludes sales_manager direct API access")
    void costAttributionSummaryHasRoleGate() throws Exception {
        Method summary = RestaurantCostAttributionController.class.getDeclaredMethod(
                "summary", String.class, java.time.LocalDate.class, java.time.LocalDate.class);
        RequireRole role = summary.getAnnotation(RequireRole.class);
        assertP2pFinanceRoles(role);
    }

    private void assertP2pFinanceRoles(RequireRole role) {
        assertNotNull(role);
        Set<String> roles = Set.copyOf(Arrays.asList(role.value()));
        assertTrue(roles.contains("factory_super_admin"));
        assertTrue(roles.contains("platform_admin"));
        assertTrue(roles.contains("permission_admin"));
        assertTrue(roles.contains("restaurant_manager"));
        assertTrue(roles.contains("finance_manager"));
        assertFalse(roles.contains("sales_manager"), "sales_manager must not access supplier finance data directly");
        assertFalse(roles.contains("warehouse_manager"), "warehouse_manager records inbound but must not access finance reconciliation");
    }
}
