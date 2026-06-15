package com.cretas.aims.dto.sales;

import java.math.BigDecimal;

/**
 * 销售订单行改价响应 DTO — warn-not-block 模式
 *
 * <p>改价永远立即生效 (effectiveImmediately=true).
 * 超阈值 (降价>10%/涨价>20%) 时 priceWarning=true + priceWarningMessage 非空，
 * 但价格已应用，不阻塞流程。
 */
public record AdjustPriceResponse(
        String adjustmentRecordId,
        String salesOrderId,
        Long lineId,
        BigDecimal oldPrice,
        BigDecimal newPrice,
        /** 改价是否已立即生效 (warn-not-block: 永远 true) */
        boolean effectiveImmediately,
        /** 是否有价格预警 (超阈值时 true, 改价仍生效) */
        boolean priceWarning,
        /** 预警说明文案 (priceWarning=false 时 null) */
        String priceWarningMessage
) {}
