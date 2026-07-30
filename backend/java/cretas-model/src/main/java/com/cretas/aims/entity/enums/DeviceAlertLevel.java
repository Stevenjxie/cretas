package com.cretas.aims.entity.enums;

/**
 * 设备告警级别枚举
 *
 * 用于设备/IoT 告警的严重程度分类。
 * 注意: 业务指标健康度使用 smartbi.enums.AlertLevel (GREEN/YELLOW/RED/CRITICAL)
 *
 * @author Cretas Team
 * @version 1.1.0
 * @since 2025-11-19
 */
public enum DeviceAlertLevel {
    /**
     * 严重
     */
    CRITICAL,

    /**
     * 警告
     */
    WARNING,

    /**
     * 提示
     */
    INFO
}
