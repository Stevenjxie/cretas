package com.cretas.aims.entity.enums;

/**
 * 返工状态枚举
 * 用于追踪不合格品的返工处理状态
 *
 * @author Cretas Team
 * @version 2.0.0
 * @since 2025-11-05
 */
public enum ReworkStatus {
    /**
     * 待返工 - 已确定需要返工，等待开始处理
     */
    PENDING("待返工"),

    /**
     * 返工中 - 正在进行返工处理
     */
    IN_PROGRESS("返工中"),

    /**
     * 已完成 - 返工处理已完成，等待复检
     */
    COMPLETED("已完成"),

    /**
     * 返工失败 - 返工后仍不合格，需要进一步处理
     */
    FAILED("返工失败"),

    /**
     * 已取消 - 返工计划被取消（改为报废等）
     */
    CANCELLED("已取消");

    private final String description;

    ReworkStatus(String description) {
        this.description = description;
    }

    public String getDescription() {
        return description;
    }

    /**
     * 检查是否为终态（不会再变化）
     */
    public boolean isFinalized() {
        return this == COMPLETED || this == FAILED || this == CANCELLED;
    }

    /**
     * 检查是否正在处理中
     */
    public boolean isActive() {
        return this == PENDING || this == IN_PROGRESS;
    }
}
