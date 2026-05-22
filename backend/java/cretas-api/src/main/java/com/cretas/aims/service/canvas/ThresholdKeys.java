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
}
