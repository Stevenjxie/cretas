package com.cretas.aims.dto.sales;

import java.math.BigDecimal;

/**
 * 销售订单行改价响应 DTO
 */
public record AdjustPriceResponse(
        String adjustmentRecordId,
        String salesOrderId,
        Long lineId,
        BigDecimal oldPrice,
        BigDecimal newPrice,
        boolean approvalRequired,
        /** 审批状态 (NOT_REQUIRED / PENDING / APPROVED / REJECTED) */
        String approvalStatus,
        /** 审批链 ID (approvalRequired=true 时有值) */
        String approvalChainId,
        /** 改价是否已立即生效 (approvalRequired=false 时 true) */
        boolean effectiveImmediately
) {}
