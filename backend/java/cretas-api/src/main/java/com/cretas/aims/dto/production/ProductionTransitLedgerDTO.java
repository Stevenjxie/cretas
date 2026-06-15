package com.cretas.aims.dto.production;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Read-only DTO for the web-admin transit ledger reconciliation page.
 * Derived from {@code ProductionTransitLedger} + denormalised plan number.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProductionTransitLedgerDTO {

    private String id;
    private String factoryId;
    private String settlementId;
    private String productionPlanId;
    private String planNumber;
    private String ledgerType;

    private BigDecimal reportedQuantity;
    private BigDecimal confirmedQuantity;
    private BigDecimal varianceQuantity;
    private BigDecimal toleranceQuantity;
    private String quantityUnit;

    private String varianceReason;
    private String responsibilitySide;
    private String status;
    private String note;

    private Long createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
