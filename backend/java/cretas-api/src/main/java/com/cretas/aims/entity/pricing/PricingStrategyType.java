package com.cretas.aims.entity.pricing;

/**
 * 价格策略类型 (Canvas-Pricing Phase 4b).
 *
 * <p>5 种策略类型 (per docs/superpowers/specs/2026-05-18-canvas-pricing-phase4b-spec.md §1):
 * <ul>
 *   <li>{@link #TIERED} — 阶梯定价 (按数量分档)</li>
 *   <li>{@link #PROMOTION} — 促销 (满减 / 限时)</li>
 *   <li>{@link #MEMBER} — 会员折扣 (按客户分级)</li>
 *   <li>{@link #BUNDLE} — 套餐价 (多 SKU 组合)</li>
 *   <li>{@link #CYCLE} — 跨周期返点 (月底/季度结算)</li>
 * </ul>
 *
 * <p>Skeleton — sister chat fills apply logic in PricingEngineImpl.
 */
public enum PricingStrategyType {
    TIERED,
    PROMOTION,
    MEMBER,
    BUNDLE,
    CYCLE
}
