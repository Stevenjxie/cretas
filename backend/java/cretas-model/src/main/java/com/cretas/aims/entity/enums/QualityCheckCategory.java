package com.cretas.aims.entity.enums;

/**
 * 质检项目类别枚举
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2025-12-29
 */
public enum QualityCheckCategory {
    /**
     * 感官检测 - 外观、色泽、气味、口感等
     */
    SENSORY("感官检测"),

    /**
     * 物理检测 - 温度、重量、尺寸、硬度等
     */
    PHYSICAL("物理检测"),

    /**
     * 化学检测 - pH值、盐度、水分含量等
     */
    CHEMICAL("化学检测"),

    /**
     * 微生物检测 - 菌落总数、大肠杆菌等
     */
    MICROBIOLOGICAL("微生物检测"),

    /**
     * 包装检测 - 密封性、标签、重量等
     */
    PACKAGING("包装检测");

    private final String description;

    QualityCheckCategory(String description) {
        this.description = description;
    }

    public String getDescription() {
        return description;
    }
}
