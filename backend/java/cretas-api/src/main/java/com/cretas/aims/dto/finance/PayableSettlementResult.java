package com.cretas.aims.dto.finance;

import com.cretas.aims.entity.enums.PayablePaymentStatus;
import lombok.Builder;
import lombok.Value;

import java.math.BigDecimal;

@Value
@Builder
public class PayableSettlementResult {
    String payableTransactionId;
    String paymentTransactionId;
    String paymentTransactionNumber;
    String allocationId;
    String supplierId;
    BigDecimal allocatedAmount;
    BigDecimal settledAmount;
    BigDecimal outstandingAmount;
    String currencyCode;
    PayablePaymentStatus paymentStatus;
    boolean replayed;
}
