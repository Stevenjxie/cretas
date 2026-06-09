package com.cretas.aims.dto.pricing;

import com.cretas.aims.security.PriceSensitive;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * SP5: 提成预览 DTO.
 *
 * <p>用于销售订单确认时展示预计提成金额，支持前端感知层，帮助销售人员预估收益。
 * 字段均为 @PriceSensitive，仅有权限角色可见（PriceFieldResponseAdvice 自动剥离）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CommissionPreviewDTO {

    /** 预计提成金额 (税前). */
    @PriceSensitive
    private BigDecimal commissionAmount;

    /** 适用的提成率 (0-1, e.g. 0.05 = 5%). */
    @PriceSensitive
    private BigDecimal commissionRate;

    /** 订单金额 (本次计算基数). */
    @PriceSensitive
    private BigDecimal orderAmount;

    /** 适用的阶梯档位描述 (如 "满 10000 元适用 5% 阶梯"). */
    private String tierDescription;

    /** 是否有配置可用的提成规则. */
    private boolean hasRule;
}
