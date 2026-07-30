package com.cretas.aims.entity.enums;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 设备状态枚举
 * 兼容前端小写格式：active, inactive, maintenance, scrapped
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2025-01-09
 */
public enum EquipmentStatus {
    RUNNING("运行中", "active"),
    IDLE("空闲", "inactive"),
    MAINTENANCE("维护中", "maintenance"),
    FAULT("故障", "fault"),
    OFFLINE("离线", "scrapped");

    private final String description;
    private final String frontendValue;

    EquipmentStatus(String description, String frontendValue) {
        this.description = description;
        this.frontendValue = frontendValue;
    }

    public String getDescription() {
        return description;
    }

    /**
     * 序列化时返回前端期望的小写值
     */
    @JsonValue
    public String getFrontendValue() {
        return frontendValue;
    }

    /**
     * 从前端值或枚举名反序列化
     */
    @JsonCreator
    public static EquipmentStatus fromValue(String value) {
        if (value == null) {
            return null;
        }
        // 尝试匹配前端值
        for (EquipmentStatus status : values()) {
            if (status.frontendValue.equalsIgnoreCase(value)) {
                return status;
            }
        }
        // 尝试匹配枚举名
        for (EquipmentStatus status : values()) {
            if (status.name().equalsIgnoreCase(value)) {
                return status;
            }
        }
        throw new IllegalArgumentException("Unknown equipment status: " + value);
    }
}
