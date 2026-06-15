package com.cretas.aims.dto.sales;

import com.cretas.aims.entity.inventory.SalesPriceAdjustmentRecord.ReasonType;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;

/**
 * 销售订单行改价请求 DTO
 *
 * <p>fool-proof Rule 3: 原因类型用 enum (dropdown, 标准化选项),
 * 仅当 reasonType=OTHER 时才需填 reasonDetail。
 */
public record AdjustPriceRequest(
        /** 新单价 (必须为正数) */
        @NotNull @Positive BigDecimal newUnitPrice,

        /** 改价原因类型 (dropdown 标准化, fool-proof Rule 3) */
        @NotNull ReasonType reasonType,

        /** 原因明细 (reasonType=OTHER 时必填, 其余可选) */
        String reasonDetail
) {}
