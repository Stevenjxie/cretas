package com.cretas.aims.entity.enums;

/**
 * Sprint4-H Q-RETURN-1: 质检退回单状态.
 *
 * <p>状态机:
 * <ul>
 *   <li>{@link #DRAFT}: 草稿, 质检员创建后未确认 (可编辑)</li>
 *   <li>{@link #CONFIRMED}: 已确认, 等待物流发出 (供应商/加工厂已通知)</li>
 *   <li>{@link #SHIPPED}: 已发出 (物流确认, 退回完成)</li>
 * </ul>
 */
public enum QualityReturnStatus {
    DRAFT("草稿", "质检员创建, 可编辑"),
    CONFIRMED("已确认", "已通知接收方, 等待发出"),
    SHIPPED("已发出", "物流确认发出, 流程完成");

    private final String displayName;
    private final String description;

    QualityReturnStatus(String displayName, String description) {
        this.displayName = displayName;
        this.description = description;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getDescription() {
        return description;
    }
}
