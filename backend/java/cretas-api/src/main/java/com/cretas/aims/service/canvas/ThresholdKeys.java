package com.cretas.aims.service.canvas;

/**
 * Canvas-Thresholds 阈值键常量 (Phase A P0-1).
 *
 * <p>类型安全的 key 引用 — service 调用 {@code thresholdResolverService.getXxx(factoryId, KEY, default)}
 * 必须使用此处声明的常量, 避免 typo 导致默认值静默 fallback。
 *
 * <p>命名规则: {category}.{subdomain}.{name} (lowercase + underscore).
 *
 * @since Canvas Phase A (2026-05-21)
 */
public final class ThresholdKeys {

    private ThresholdKeys() {
        // utility class
    }

    // ==================== 库存健康 (INVENTORY) ====================

    /** 周转率红色预警 (次/年). Hard-coded default = 6. */
    public static final String INVENTORY_TURNOVER_RED = "inventory.turnover.red";
    /** 周转率黄色预警 (次/年). Hard-coded default = 12. */
    public static final String INVENTORY_TURNOVER_YELLOW = "inventory.turnover.yellow";

    /** 临期风险率红色预警 (%). Hard-coded default = 15. */
    public static final String INVENTORY_EXPIRY_RED = "inventory.expiry.red";
    /** 临期风险率黄色预警 (%). Hard-coded default = 10. */
    public static final String INVENTORY_EXPIRY_YELLOW = "inventory.expiry.yellow";

    /** 损耗率红色预警 (%). Hard-coded default = 5. */
    public static final String INVENTORY_LOSS_RED = "inventory.loss.red";
    /** 损耗率黄色预警 (%). Hard-coded default = 2. */
    public static final String INVENTORY_LOSS_YELLOW = "inventory.loss.yellow";

    /** 库龄新鲜段 (天). Hard-coded default = 30. */
    public static final String INVENTORY_AGING_FRESH = "inventory.aging.fresh_days";
    /** 库龄正常段 (天). Hard-coded default = 60. */
    public static final String INVENTORY_AGING_NORMAL = "inventory.aging.normal_days";
    /** 库龄预警段 (天). Hard-coded default = 90. */
    public static final String INVENTORY_AGING_WARNING = "inventory.aging.warning_days";

    /** 临期预警天数 (天). Hard-coded default = 30. */
    public static final String INVENTORY_EXPIRY_WARNING_DAYS = "inventory.expiry.warning_days";
    /** 高风险临期阈值 (天). Hard-coded default = 7. */
    public static final String INVENTORY_HIGH_RISK_EXPIRY_DAYS = "inventory.expiry.high_risk_days";

    // ==================== IoT 冷链 / 环境 (IOT) ====================

    /** 冷链温度上限 (°C). Hard-coded default = -18.0. */
    public static final String IOT_COLD_CHAIN_TEMP_MAX = "iot.cold_chain.temp_max";
    /** 常温温度下限 (°C). Hard-coded default = 0.0. */
    public static final String IOT_NORMAL_TEMP_MIN = "iot.normal.temp_min";
    /** 常温温度上限 (°C). Hard-coded default = 25.0. */
    public static final String IOT_NORMAL_TEMP_MAX = "iot.normal.temp_max";
    /** 湿度下限 (%). Hard-coded default = 40.0. */
    public static final String IOT_HUMIDITY_MIN = "iot.humidity.min";
    /** 湿度上限 (%). Hard-coded default = 70.0. */
    public static final String IOT_HUMIDITY_MAX = "iot.humidity.max";

    // ==================== 客户信用 (CREDIT) ====================

    /** 信用额度接近预警比 (used / limit). Hard-coded default = 0.80. */
    public static final String CREDIT_WARNING_RATIO = "credit.warning_ratio";

    // ==================== BOM (BOM) ====================

    /** BOM 递归展开最大深度. Hard-coded default = 10. */
    public static final String BOM_MAX_DEPTH = "bom.max_depth";

    // ==================== 缺料分析 (SHORTAGE) ====================

    /** 生产建议默认 lead time (天). Hard-coded default = 7. */
    public static final String SHORTAGE_DEFAULT_PRODUCTION_LEAD_DAYS = "shortage.production_lead_days";

    // ==================== AI 复杂度路由 (AI) ====================

    /** Fast mode 阈值. Hard-coded default = 0.3. */
    public static final String AI_COMPLEXITY_FAST_THRESHOLD = "ai.complexity.fast_threshold";
    /** Analysis mode 阈值. Hard-coded default = 0.6. */
    public static final String AI_COMPLEXITY_ANALYSIS_THRESHOLD = "ai.complexity.analysis_threshold";
    /** Multi-agent mode 阈值. Hard-coded default = 0.8. */
    public static final String AI_COMPLEXITY_MULTI_AGENT_THRESHOLD = "ai.complexity.multi_agent_threshold";

    // ==================== 价格策略 (PRICING) ====================

    /** Fool-proof rule warning threshold: totalDiscount / originalPrice. Hard-coded default = 0.50 (50%). */
    public static final String PRICING_DEEP_DISCOUNT_RATIO = "pricing.warning.deep_discount_ratio";

    // ==================== 生产报工 (PRODUCTION) ====================

    /** Quantity overshoot tolerance: 累计完工 / plannedQuantity 上限. Hard-coded default = 1.10 (110%). */
    public static final String PRODUCTION_OVERSHOOT_TOLERANCE = "production.quantity.overshoot_tolerance";

    // ==================== 采购分析 (PROCUREMENT) ====================

    /** 质量合格率红色预警阈值 (%). Hard-coded default = 90. */
    public static final String PROCUREMENT_QUALITY_RED = "procurement.quality.red";
    /** 质量合格率黄色预警阈值 (%). Hard-coded default = 95. */
    public static final String PROCUREMENT_QUALITY_YELLOW = "procurement.quality.yellow";
    /** 供应商集中度红色预警阈值 (%). Hard-coded default = 60. */
    public static final String PROCUREMENT_CONCENTRATION_RED = "procurement.concentration.red";
    /** 供应商集中度黄色预警阈值 (%). Hard-coded default = 40. */
    public static final String PROCUREMENT_CONCENTRATION_YELLOW = "procurement.concentration.yellow";

    // ==================== 生产分析 (PRODUCTION_ANALYSIS) ====================

    /** OEE 红色预警阈值 (%). Hard-coded default = 65. */
    public static final String PRODUCTION_OEE_RED = "production.oee.red";
    /** OEE 黄色预警阈值 (%). Hard-coded default = 85. */
    public static final String PRODUCTION_OEE_YELLOW = "production.oee.yellow";
    /** 可用性红色预警阈值 (%). Hard-coded default = 80. */
    public static final String PRODUCTION_AVAILABILITY_RED = "production.availability.red";
    /** 可用性黄色预警阈值 (%). Hard-coded default = 90. */
    public static final String PRODUCTION_AVAILABILITY_YELLOW = "production.availability.yellow";
    /** 性能红色预警阈值 (%). Hard-coded default = 75. */
    public static final String PRODUCTION_PERFORMANCE_RED = "production.performance.red";
    /** 性能黄色预警阈值 (%). Hard-coded default = 90. */
    public static final String PRODUCTION_PERFORMANCE_YELLOW = "production.performance.yellow";
    /** 生产质量红色预警阈值 (%). Hard-coded default = 95. */
    public static final String PRODUCTION_QUALITY_RED = "production.quality.red";
    /** 生产质量黄色预警阈值 (%). Hard-coded default = 98. */
    public static final String PRODUCTION_QUALITY_YELLOW = "production.quality.yellow";

    // ==================== 质量分析 (QUALITY_ANALYSIS) ====================

    /** FPY (First Pass Yield) 红色预警阈值 (%). Hard-coded default = 95. */
    public static final String QUALITY_FPY_RED = "quality.fpy.red";
    /** FPY 黄色预警阈值 (%). Hard-coded default = 98. */
    public static final String QUALITY_FPY_YELLOW = "quality.fpy.yellow";
    /** 缺陷率红色预警阈值 (%). Hard-coded default = 5. */
    public static final String QUALITY_DEFECT_RATE_RED = "quality.defect_rate.red";
    /** 缺陷率黄色预警阈值 (%). Hard-coded default = 2. */
    public static final String QUALITY_DEFECT_RATE_YELLOW = "quality.defect_rate.yellow";
    /** 质量成本占比红色预警阈值 (%). Hard-coded default = 3. */
    public static final String QUALITY_COST_RED = "quality.cost.red";
    /** 质量成本占比黄色预警阈值 (%). Hard-coded default = 1.5. */
    public static final String QUALITY_COST_YELLOW = "quality.cost.yellow";
    /** 返工率红色预警阈值 (%). Hard-coded default = 20. */
    public static final String QUALITY_REWORK_RATE_RED = "quality.rework_rate.red";
    /** 返工率黄色预警阈值 (%). Hard-coded default = 10. */
    public static final String QUALITY_REWORK_RATE_YELLOW = "quality.rework_rate.yellow";

    // ==================== 销售分析 (SALES) ====================

    /** 销售目标达成率红色预警阈值 (%). Hard-coded default = 60. */
    public static final String SALES_TARGET_RED = "sales.target.red";
    /** 销售目标达成率黄色预警阈值 (%). Hard-coded default = 85. */
    public static final String SALES_TARGET_YELLOW = "sales.target.yellow";
    /** 销售毛利率红色预警阈值 (%). Hard-coded default = 15. */
    public static final String SALES_MARGIN_RED = "sales.margin.red";
    /** 销售毛利率黄色预警阈值 (%). Hard-coded default = 25. */
    public static final String SALES_MARGIN_YELLOW = "sales.margin.yellow";
    /** 销售增长率红色预警阈值 (%). Hard-coded default = -20. */
    public static final String SALES_GROWTH_RED = "sales.growth.red";
    /** 销售增长率黄色预警阈值 (%). Hard-coded default = -5. */
    public static final String SALES_GROWTH_YELLOW = "sales.growth.yellow";

    // ==================== 财务分析 (FINANCE) ====================

    /** 账龄 90+ 天占比红色预警阈值 (%). Hard-coded default = 20. */
    public static final String FINANCE_AGING_90_RED = "finance.aging_90.red";
    /** 账龄 90+ 天占比黄色预警阈值 (%). Hard-coded default = 10. */
    public static final String FINANCE_AGING_90_YELLOW = "finance.aging_90.yellow";
    /** 预算执行率红色预警阈值 (%). Hard-coded default = 120. */
    public static final String FINANCE_BUDGET_EXECUTION_RED = "finance.budget_execution.red";
    /** 预算执行率黄色预警阈值 (%). Hard-coded default = 100. */
    public static final String FINANCE_BUDGET_EXECUTION_YELLOW = "finance.budget_execution.yellow";

    // ==================== 加工分析 (PROCESSING) ====================

    /** 加工合格率 PASS 阈值 (%). Hard-coded default = 95. */
    public static final String PROCESSING_PASS_THRESHOLD = "processing.quality.pass_threshold";
    /** 加工合格率 FAIL 阈值 (%). Hard-coded default = 70. */
    public static final String PROCESSING_FAIL_THRESHOLD = "processing.quality.fail_threshold";

    // ==================== 个人效率 (EFFICIENCY) ====================

    /** 个人效率默认值. Hard-coded default = 1.0. */
    public static final String EFFICIENCY_DEFAULT = "efficiency.individual.default";
    /** 个人效率下限. Hard-coded default = 0.1. */
    public static final String EFFICIENCY_MIN = "efficiency.individual.min";
    /** 个人效率上限. Hard-coded default = 2.0. */
    public static final String EFFICIENCY_MAX = "efficiency.individual.max";
}
