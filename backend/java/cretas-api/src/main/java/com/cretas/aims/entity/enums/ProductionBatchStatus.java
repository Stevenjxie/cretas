package com.cretas.aims.entity.enums;

/**
 * 生产批次状态枚举
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2025-01-09
 */
public enum ProductionBatchStatus {
    PLANNED("计划中"),
    PLANNING("计划中"),   // 兼容旧数据，与PLANNED含义相同
    IN_PROGRESS("生产中"),
    PRODUCING("生产中"),  // 兼容旧数据，与IN_PROGRESS含义相同
    PAUSED("已暂停"),
    COMPLETED("已完成"),
    CANCELLED("已取消");
    private final String description;
    ProductionBatchStatus(String description) {
        this.description = description;
    }
    public String getDescription() {
        return description;
}
}
