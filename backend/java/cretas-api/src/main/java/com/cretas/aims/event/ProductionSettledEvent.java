package com.cretas.aims.event;

import lombok.Getter;
import org.springframework.context.ApplicationEvent;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Getter
public class ProductionSettledEvent extends ApplicationEvent {
    private final String factoryId;
    private final String planId;
    private final String planNumber;
    private final String productTypeId;
    private final String settlementId;
    private final BigDecimal actualFinishedQuantity;
    private final LocalDateTime createdAt;

    public ProductionSettledEvent(Object source, String factoryId, String planId, String planNumber,
                                  String productTypeId, String settlementId, BigDecimal actualFinishedQuantity) {
        super(source);
        this.factoryId = factoryId;
        this.planId = planId;
        this.planNumber = planNumber;
        this.productTypeId = productTypeId;
        this.settlementId = settlementId;
        this.actualFinishedQuantity = actualFinishedQuantity;
        this.createdAt = LocalDateTime.now();
    }
}
