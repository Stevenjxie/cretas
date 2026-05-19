package com.cretas.aims.service.pricing;

import com.cretas.aims.entity.pricing.PricingStrategyType;
import lombok.Builder;
import lombok.Value;

import java.math.BigDecimal;

/**
 * 已应用的价格策略 (单条) — Canvas-Pricing Phase 4b.
 *
 * <p>{@link PricingResult#appliedStrategies} 用于审计 + UI 显示.
 * 写入 {@code pricing_application_logs.applied_strategies} JSONB 数组的 1 个 element.
 */
@Value
@Builder
public class AppliedStrategy {
    String strategyId;
    String strategyCode;
    PricingStrategyType strategyType;
    /** Discount amount applied by this strategy (per unit, positive value). */
    BigDecimal discountApplied;
}
