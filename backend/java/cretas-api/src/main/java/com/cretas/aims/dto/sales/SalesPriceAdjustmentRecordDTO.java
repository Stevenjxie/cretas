package com.cretas.aims.dto.sales;

import com.cretas.aims.entity.inventory.SalesPriceAdjustmentRecord;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 销售订单改价历史记录 DTO (只读展示)
 */
public record SalesPriceAdjustmentRecordDTO(
        String id,
        Long salesOrderLineId,
        String salesOrderId,
        BigDecimal oldUnitPrice,
        BigDecimal newUnitPrice,
        /** 价格变动百分比, 正数=涨价, 负数=降价 */
        BigDecimal changePct,
        SalesPriceAdjustmentRecord.ReasonType adjustmentReasonType,
        String adjustmentReasonDetail,
        Long adjustedBy,
        String adjustedByName,
        boolean approvalRequired,
        SalesPriceAdjustmentRecord.ApprovalStatus approvalStatus,
        String approvalChainId,
        LocalDateTime createdAt
) {
    public static SalesPriceAdjustmentRecordDTO from(SalesPriceAdjustmentRecord r) {
        BigDecimal changePct = null;
        if (r.getOldUnitPrice() != null && r.getOldUnitPrice().compareTo(BigDecimal.ZERO) != 0
                && r.getNewUnitPrice() != null) {
            changePct = r.getNewUnitPrice()
                    .subtract(r.getOldUnitPrice())
                    .divide(r.getOldUnitPrice(), 4, java.math.RoundingMode.HALF_UP)
                    .multiply(new BigDecimal("100"))
                    .setScale(2, java.math.RoundingMode.HALF_UP);
        }
        return new SalesPriceAdjustmentRecordDTO(
                r.getId(),
                r.getSalesOrderLineId(),
                r.getSalesOrderId(),
                r.getOldUnitPrice(),
                r.getNewUnitPrice(),
                changePct,
                r.getAdjustmentReasonType(),
                r.getAdjustmentReasonDetail(),
                r.getAdjustedBy(),
                r.getAdjustedByName(),
                r.isApprovalRequired(),
                r.getApprovalStatus(),
                r.getApprovalChainId(),
                r.getCreatedAt()
        );
    }
}
