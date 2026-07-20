package com.cretas.aims.controller;

import com.cretas.aims.controller.inventory.PaymentRequestController;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.service.MobileService;
import com.cretas.aims.service.inventory.PaymentRequestService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.function.Executable;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
@DisplayName("Legacy payment-request write boundary")
class PaymentRequestControllerLegacyWriteBoundaryTest {

    @Mock PaymentRequestService paymentRequestService;
    @Mock MobileService mobileService;
    @InjectMocks PaymentRequestController controller;

    @Test
    @DisplayName("purchase and business-page payment writes are tombstoned without mutations")
    void legacyWriteEndpointsAreFailClosed() {
        assertDisabled(() -> controller.createPaymentRequest(
                "F006", "Bearer stale-token", Map.of("purchaseOrderId", "PO-1")));
        assertDisabled(() -> controller.submit("F006", "PR-1", "Bearer stale-token"));
        assertDisabled(() -> controller.financeApprove(
                "F006", "PR-1", "Bearer stale-token", Map.of("reviewNote", "legacy")));
        assertDisabled(() -> controller.reject(
                "F006", "PR-1", "Bearer stale-token", Map.of("rejectReason", "legacy")));
        assertDisabled(() -> controller.markPaid(
                "F006", "PR-1", "Bearer stale-token", Map.of("evidence", "legacy")));

        verifyNoInteractions(paymentRequestService, mobileService);
    }

    private static void assertDisabled(Executable executable) {
        BusinessException error = assertThrows(BusinessException.class, executable);
        assertEquals(410, error.getCode());
        assertEquals("PAYMENT_REQUEST_LEGACY_WRITE_DISABLED", error.getErrorCode());
        assertEquals("BLOCKING", error.getSeverity());
    }
}
