package com.cretas.aims.entity.enums;

/**
 * 质检项严重程度枚举
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2025-12-29
 */
public enum QualitySeverity {
    /**
     * 关键项 - 不合格直接判定整批不合格
     */
    CRITICAL("关键项", 3),

    /**
     * 主要项 - 不合格需要返工或特批
     */
    MAJOR("主要项", 2),

    /**
     * 次要项 - 不合格可记录观察
     */
    MINOR("次要项", 1);

    private final String description;
    private final int weight;

    QualitySeverity(String description, int weight) {
        this.description = description;
        this.weight = weight;
    }

    public String getDescription() {
        return description;
    }

    public int getWeight() {
        return weight;
    }
}
