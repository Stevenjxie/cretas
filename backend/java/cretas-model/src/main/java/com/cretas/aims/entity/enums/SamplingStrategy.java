package com.cretas.aims.entity.enums;

/**
 * 抽样策略枚举
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2025-12-29
 */
public enum SamplingStrategy {
    /**
     * 首件检验 - 每批次开始时检验首件
     */
    FIRST_PIECE("首件检验"),

    /**
     * 随机抽样 - 按比例随机抽取样本
     */
    RANDOM("随机抽样"),

    /**
     * 批次末检验 - 批次完成后检验
     */
    BATCH_END("批次末检验"),

    /**
     * 全检 - 100% 检验
     */
    FULL_INSPECTION("全检"),

    /**
     * 定时抽检 - 按时间间隔抽检
     */
    PERIODIC("定时抽检"),

    /**
     * AQL抽样 - 按可接受质量水平抽样
     */
    AQL("AQL抽样");

    private final String description;

    SamplingStrategy(String description) {
        this.description = description;
    }

    public String getDescription() {
        return description;
    }
}
