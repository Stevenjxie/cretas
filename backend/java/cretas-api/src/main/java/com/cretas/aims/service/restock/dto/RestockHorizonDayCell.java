package com.cretas.aims.service.restock.dto;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

@Data
@Builder
public class RestockHorizonDayCell {
    private LocalDate deliveryDate;
    private BigDecimal demandQty;
    private BigDecimal availableBeforeDemandQty;
    private BigDecimal availableAfterDemandQty;
    private BigDecimal shortfallQty;
    private String warning;
    private String status;
}
