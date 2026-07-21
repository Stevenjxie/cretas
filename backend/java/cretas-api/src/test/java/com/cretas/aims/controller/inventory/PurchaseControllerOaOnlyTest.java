package com.cretas.aims.controller.inventory;

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

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class PurchaseControllerOaOnlyTest {

    private PurchaseController controller;

    @BeforeEach
    void setUp() {
        controller = new PurchaseController(
                mock(PurchaseService.class),
                mock(PurchaseOrderPdfService.class),
                mock(MobileService.class),
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

    private void assertOaOnly(Runnable call) {
        assertThatThrownBy(call::run)
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo("PURCHASE_APPROVAL_OA_ONLY");
    }
}
