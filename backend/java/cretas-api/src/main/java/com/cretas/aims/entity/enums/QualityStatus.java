package com.cretas.aims.entity.enums;

/**
 * 质量状态枚举
 * 用于标识生产批次和物料批次的质量检验状态
 *
 * @author Cretas Team
 * @version 2.0.0
 * @since 2025-11-05
 */
public enum QualityStatus {
    /**
     * 待质检 - 批次已完成生产，等待质量检验
     */
    PENDING_INSPECTION("待质检"),

    /**
     * 质检中 - 正在进行质量检验
     */
    INSPECTING("质检中"),

    /**
     * 待审核 - 质检已完成，等待审核确认
     */
    NEEDS_REVIEW("待审核"),

    /**
     * 已通过 - 质量检验合格，可以进入下一流程
     */
    PASSED("已通过"),

    /**
     * 合格 - 质量检验合格（等同于 PASSED，用于兼容数据库已有数据）
     */
    QUALIFIED("合格"),

    /**
     * 未通过 - 质量检验不合格，需要处理
     */
    FAILED("未通过"),

    /**
     * 部分通过 - 批次中部分产品合格，部分不合格
     */
    PARTIAL_PASS("部分通过"),

    /**
     * 需返工 - 检验不合格但可通过返工修复
     */
    REWORK_REQUIRED("需返工"),

    /**
     * 返工中 - 正在进行返工处理
     */
    REWORKING("返工中"),

    /**
     * 返工完成 - 返工处理已完成，等待复检
     */
    REWORK_COMPLETED("返工完成"),

    /**
     * 已报废 - 质量问题严重，无法修复，已报废处理
     */
    SCRAPPED("已报废");

    private final String description;

    QualityStatus(String description) {
        this.description = description;
    }

    public String getDescription() {
        return description;
    }

    /**
     * 检查是否为合格状态
     */
    public boolean isPassed() {
        return this == PASSED || this == QUALIFIED || this == PARTIAL_PASS;
    }

    /**
     * 检查是否需要返工
     */
    public boolean needsRework() {
        return this == REWORK_REQUIRED || this == REWORKING;
    }

    /**
     * 检查是否已完成（终态）
     */
    public boolean isFinalized() {
        return this == PASSED || this == QUALIFIED || this == SCRAPPED || this == REWORK_COMPLETED;
    }
}
