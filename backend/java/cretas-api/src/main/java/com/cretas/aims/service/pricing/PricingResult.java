package com.cretas.aims.service.pricing;

import lombok.Builder;
import lombok.Value;

import java.math.BigDecimal;
import java.util.List;

/**
 * 价格策略计算结果 — Canvas-Pricing Phase 4b.
 *
 * <p>{@code warnings} 字段实现 fool-proof rule (per spec §4):
 * <ul>
 *   <li>final price &lt; cost → warning "final price ¥X below cost ¥Y"</li>
 *   <li>total discount &gt; 50% → warning "deep discount"</li>
 * </ul>
 * 注意: warnings 永远 NOT BLOCK — sales manager 可以覆盖 (per 客户原话).
 *
 * <p>{@code totalDiscount = originalPrice - finalPrice} (positive 表示有折扣).
 */
@Value
@Builder
public class PricingResult {
    BigDecimal originalPrice;
    BigDecimal finalPrice;
    BigDecimal totalDiscount;
    List<AppliedStrategy> appliedStrategies;
    List<String> warnings;
}
