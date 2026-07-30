package com.cretas.aims.entity.enums;

/**
 * 混批类型枚举
 * 用于标识混批排产的合并类型
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2025-12-28
 */
public enum MixedBatchType {
    /**
     * 同原料不同客户
     * 同一批原料分切给不同客户
     */
    SAME_MATERIAL("同原料不同客户"),

    /**
     * 同工艺不同产品
     * 相似加工工艺的产品合并减少换型
     */
    SAME_PROCESS("同工艺不同产品");

    private final String displayName;

    MixedBatchType(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }

    /**
     * 从字符串解析枚举
     */
    public static MixedBatchType fromString(String value) {
        if (value == null || value.isEmpty()) {
            return SAME_MATERIAL;
        }
        try {
            return MixedBatchType.valueOf(value.toUpperCase());
        } catch (IllegalArgumentException e) {
            return SAME_MATERIAL;
        }
    }
}
