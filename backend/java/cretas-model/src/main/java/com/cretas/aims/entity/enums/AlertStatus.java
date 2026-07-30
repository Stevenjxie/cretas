package com.cretas.aims.entity.enums;

/**
 * 告警状态枚举
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2025-11-19
 */
public enum AlertStatus {
    /**
     * 活动中（未处理）
     */
    ACTIVE,

    /**
     * 已确认（已知晓但未解决）
     */
    ACKNOWLEDGED,

    /**
     * 已解决
     */
    RESOLVED,

    /**
     * 已忽略（不需要处理）
     */
    IGNORED
}
