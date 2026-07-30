package com.cretas.aims.entity.enums;

/**
 * 部门枚举
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2025-01-09
 */
public enum Department {
    /**
     * 养殖部门
     */
    FARMING("养殖部门", "负责原料养殖和采购"),

    /**
     * 加工部门
     */
    PROCESSING("加工部门", "负责产品加工生产"),

    /**
     * 物流部门
     */
    LOGISTICS("物流部门", "负责运输和配送"),

    /**
     * 质量部门
     */
    QUALITY("质量部门", "负责质量控制和检验"),

    /**
     * 管理部门
     */
    MANAGEMENT("管理部门", "负责企业管理和运营");
    private final String displayName;
    private final String description;
    Department(String displayName, String description) {
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
