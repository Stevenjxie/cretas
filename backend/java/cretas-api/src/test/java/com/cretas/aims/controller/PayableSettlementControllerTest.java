package com.cretas.aims.controller;

import com.cretas.aims.controller.finance.PayableSettlementController;
import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.dto.finance.PayableSettlementRequest;
import com.cretas.aims.dto.finance.PayableSettlementResult;
import com.cretas.aims.entity.enums.PayablePaymentStatus;
import com.cretas.aims.entity.enums.PaymentMethod;
import com.cretas.aims.service.MobileService;
import com.cretas.aims.service.finance.PayableSettlementService;
import com.cretas.aims.dto.user.UserDTO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("PayableSettlementController")
class PayableSettlementControllerTest {

    @Mock PayableSettlementService settlementService;
    @Mock MobileService mobileService;
    @InjectMocks PayableSettlementController controller;

    @Test
    @DisplayName("finance endpoint binds authenticated operator and returns settlement result")
    void settleUsesAuthenticatedOperator() {
        UserDTO user = new UserDTO();
        user.setId(99L);
        when(mobileService.getUserFromToken("token")).thenReturn(user);
        PayableSettlementRequest request = new PayableSettlementRequest();
        request.setSupplierId("SUP-1");
        request.setAmount(new BigDecimal("20.00"));
        request.setCurrencyCode("CNY");
        request.setPaymentMethod(PaymentMethod.BANK_TRANSFER);
        request.setPaymentReference("BANK-1");
        request.setIdempotencyKey("idem-1");
        PayableSettlementResult result = PayableSettlementResult.builder()
                .payableTransactionId("AP-1")
                .paymentTransactionId("PAY-1")
                .allocatedAmount(new BigDecimal("20.00"))
                .settledAmount(new BigDecimal("20.00"))
                .outstandingAmount(new BigDecimal("80.00"))
                .currencyCode("CNY")
                .paymentStatus(PayablePaymentStatus.PARTIALLY_PAID)
                .build();
        when(settlementService.settle("F006", "AP-1", request, 99L)).thenReturn(result);

        ApiResponse<PayableSettlementResult> response = controller.settle(
                "F006", "AP-1", "Bearer token", request);

        assertEquals(200, response.getCode());
        assertEquals("PAY-1", response.getData().getPaymentTransactionId());
        verify(settlementService).settle("F006", "AP-1", request, 99L);
    }
}
