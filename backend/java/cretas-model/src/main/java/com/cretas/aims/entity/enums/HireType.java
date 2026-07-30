package com.cretas.aims.entity.enums;

/**
 * 雇用类型枚举
 * 用于区分员工的雇用形式
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2025-12-28
 */
public enum HireType {
    /**
     * 正式工
     */
    FULL_TIME("正式工", true),

    /**
     * 兼职
     */
    PART_TIME("兼职", false),

    /**
     * 派遣工
     */
    DISPATCH("派遣工", false),

    /**
     * 实习生
     */
    INTERN("实习生", false),

    /**
     * 临时工
     */
    TEMPORARY("临时工", false);

    private final String displayName;
    private final boolean permanent;

    HireType(String displayName, boolean permanent) {
        this.displayName = displayName;
        this.permanent = permanent;
    }

    public String getDisplayName() {
        return displayName;
    }

    /**
     * 是否为长期雇员（正式工）
     */
    public boolean isPermanent() {
        return permanent;
    }

    /**
     * 是否为临时性质（临时工、派遣、兼职、实习）
     */
    public boolean isTemporary() {
        return !permanent;
    }

    /**
     * 从字符串解析枚举
     */
    public static HireType fromString(String value) {
        if (value == null || value.isEmpty()) {
            return FULL_TIME; // 默认正式工
        }
        try {
            return HireType.valueOf(value.toUpperCase());
        } catch (IllegalArgumentException e) {
            return FULL_TIME;
        }
    }
}
