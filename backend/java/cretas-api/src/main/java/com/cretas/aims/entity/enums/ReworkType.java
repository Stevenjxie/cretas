package com.cretas.aims.entity.enums;

/**
 * 返工类型枚举
 * 定义不同类型的返工处理方式
 *
 * @author Cretas Team
 * @version 2.0.0
 * @since 2025-11-05
 */
public enum ReworkType {
    /**
     * 生产返工 - 成品质量不合格，需要重新加工
     */
    PRODUCTION_REWORK("生产返工"),

    /**
     * 原材料返工 - 原材料质量问题，需要重新处理
     */
    MATERIAL_REWORK("原材料返工"),

    /**
     * 质量返工 - 质量检验发现的可修复问题
     */
    QUALITY_REWORK("质量返工"),

    /**
     * 包装返工 - 包装不合格，需要重新包装
     */
    PACKAGING_REWORK("包装返工"),

    /**
     * 规格调整 - 规格不符，需要调整
     */
    SPECIFICATION_ADJUSTMENT("规格调整");

    private final String description;

    ReworkType(String description) {
        this.description = description;
    }

    public String getDescription() {
        return description;
    }
}
