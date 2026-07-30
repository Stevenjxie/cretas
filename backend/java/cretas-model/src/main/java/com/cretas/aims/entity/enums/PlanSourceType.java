package com.cretas.aims.entity.enums;

/**
 * 生产计划来源类型枚举
 * 用于标识生产计划的创建来源
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2025-12-28
 */
public enum PlanSourceType {
    /**
     * 客户订单
     * 优先级: 8-10
     */
    CUSTOMER_ORDER("客户订单", 8, 10),

    /**
     * AI市场预测
     * 优先级: 4-7
     */
    AI_FORECAST("AI预测", 4, 7),

    /**
     * 安全库存补货
     * 优先级: 2-4
     */
    SAFETY_STOCK("安全库存", 2, 4),

    /**
     * 手动创建
     * 优先级: 5-8
     */
    MANUAL("手动创建", 5, 8),

    /**
     * 紧急插单
     * 优先级: 9-10
     */
    URGENT_INSERT("紧急插单", 9, 10),

    /**
     * Excel批量导入
     * 优先级: 5-8
     */
    EXCEL_IMPORT("Excel导入", 5, 8),

    /**
     * AI对话创建
     * 优先级: 5-8
     */
    AI_CHAT("AI对话创建", 5, 8);

    private final String displayName;
    private final int minPriority;
    private final int maxPriority;

    PlanSourceType(String displayName, int minPriority, int maxPriority) {
        this.displayName = displayName;
        this.minPriority = minPriority;
        this.maxPriority = maxPriority;
    }

    public String getDisplayName() {
        return displayName;
    }

    public int getMinPriority() {
        return minPriority;
    }

    public int getMaxPriority() {
        return maxPriority;
    }

    /**
     * 获取默认优先级 (范围中间值)
     */
    public int getDefaultPriority() {
        return (minPriority + maxPriority) / 2;
    }

    /**
     * 检查优先级是否在有效范围内
     */
    public boolean isValidPriority(int priority) {
        return priority >= minPriority && priority <= maxPriority;
    }

    /**
     * 从字符串解析枚举
     */
    public static PlanSourceType fromString(String value) {
        if (value == null || value.isEmpty()) {
            return MANUAL;
        }
        try {
            return PlanSourceType.valueOf(value.toUpperCase());
        } catch (IllegalArgumentException e) {
            return MANUAL;
        }
    }
}
