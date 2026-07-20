package com.cretas.aims.dto.finance;

import lombok.Builder;
import lombok.Value;

import java.math.BigDecimal;
import java.time.LocalDate;

@Value
@Builder
public class UnallocatedApPaymentDTO {
    String paymentTransactionId;
    String paymentTransactionNumber;
    String supplierId;
    String supplierName;
    String purchaseOrderId;
    BigDecimal amount;
    String currencyCode;
    String paymentReference;
    LocalDate transactionDate;
    String anomalyCode;
}
