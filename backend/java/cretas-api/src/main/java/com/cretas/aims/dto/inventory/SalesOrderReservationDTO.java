package com.cretas.aims.dto.inventory;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * 销售订单当前生效的成品预留明细。
 *
 * <p>只暴露销售和仓储判断所需的批次身份及数量，不返回库存成本。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SalesOrderReservationDTO {

    private String reservationId;
    private String salesOrderItemId;
    private String finishedGoodsBatchId;
    private String batchNumber;
    private String productTypeId;
    private String productName;
    private BigDecimal reservedQuantity;
    private String unit;
    private String status;
}
