package com.cretas.aims.entity.common;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 统一设备类型枚举
 * 整合所有设备类型到一个统一的枚举体系
 *
 * 替代以下旧枚举:
 * - {@link com.cretas.aims.entity.enums.DeviceCategory} (TRADITIONAL, IOT_SCALE, IOT_CAMERA, IOT_SENSOR)
 * - {@link com.cretas.aims.entity.iot.DeviceType} (SCALE, CAMERA, SENSOR, GATEWAY)
 * - {@link com.cretas.aims.entity.isapi.IsapiDevice.DeviceType} (IPC, NVR, DVR, ENCODER)
 * - {@link com.cretas.aims.entity.dahua.DahuaDevice.DeviceType} (IPC, NVR, DVR, XVR)
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-01-05
 */
public enum UnifiedDeviceType {

    // ==================== 传统设备 ====================
    TRADITIONAL("传统设备", DeviceTypeCategory.TRADITIONAL, "traditional"),

    // ==================== IoT 电子秤 ====================
    SCALE("电子秤", DeviceTypeCategory.IOT, "scale"),

    // ==================== IoT 摄像头类型 ====================
    CAMERA_IPC("网络摄像机", DeviceTypeCategory.IOT, "camera_ipc"),
    CAMERA_NVR("网络硬盘录像机", DeviceTypeCategory.IOT, "camera_nvr"),
    CAMERA_DVR("硬盘录像机", DeviceTypeCategory.IOT, "camera_dvr"),
    CAMERA_ENCODER("编码器", DeviceTypeCategory.IOT, "camera_encoder"),
    CAMERA_XVR("混合硬盘录像机", DeviceTypeCategory.IOT, "camera_xvr"),
    CAMERA_GENERIC("通用摄像头", DeviceTypeCategory.IOT, "camera"),

    // ==================== IoT 传感器 ====================
    SENSOR("传感器", DeviceTypeCategory.IOT, "sensor"),

    // ==================== IoT 网关 ====================
    GATEWAY("边缘网关", DeviceTypeCategory.IOT, "gateway");

    private final String displayName;
    private final DeviceTypeCategory category;
    private final String frontendValue;

    UnifiedDeviceType(String displayName, DeviceTypeCategory category, String frontendValue) {
        this.displayName = displayName;
        this.category = category;
        this.frontendValue = frontendValue;
    }

    /**
     * 获取显示名称
     */
    public String getDisplayName() {
        return displayName;
    }

    /**
     * 获取设备类别 (传统/IoT)
     */
    public DeviceTypeCategory getCategory() {
        return category;
    }

    /**
     * 序列化时返回前端期望的值
     */
    @JsonValue
    public String getFrontendValue() {
        return frontendValue;
    }

    /**
     * 是否是IoT设备
     */
    public boolean isIotDevice() {
        return category == DeviceTypeCategory.IOT;
    }

    /**
     * 是否是摄像头类设备
     */
    public boolean isCameraDevice() {
        return this == CAMERA_IPC || this == CAMERA_NVR || this == CAMERA_DVR
                || this == CAMERA_ENCODER || this == CAMERA_XVR || this == CAMERA_GENERIC;
    }

    /**
     * 是否是ISAPI设备 (海康威视)
     */
    public boolean isIsapiDevice() {
        return this == CAMERA_IPC || this == CAMERA_NVR || this == CAMERA_DVR || this == CAMERA_ENCODER;
    }

    /**
     * 是否是大华设备
     */
    public boolean isDahuaDevice() {
        return this == CAMERA_IPC || this == CAMERA_NVR || this == CAMERA_DVR || this == CAMERA_XVR;
    }

    // ==================== 从旧枚举转换的静态方法 ====================

    /**
     * 从 DeviceCategory 枚举转换
     *
     * @param deviceCategory 旧的设备类别枚举
     * @return 统一设备类型
     */
    public static UnifiedDeviceType fromDeviceCategory(com.cretas.aims.entity.enums.DeviceCategory deviceCategory) {
        if (deviceCategory == null) {
            return null;
        }
        switch (deviceCategory) {
            case TRADITIONAL:
                return TRADITIONAL;
            case IOT_SCALE:
                return SCALE;
            case IOT_CAMERA:
                return CAMERA_GENERIC;
            case IOT_SENSOR:
                return SENSOR;
            default:
                throw new IllegalArgumentException("Unknown DeviceCategory: " + deviceCategory);
        }
    }

    /**
     * 从 IotDevice.DeviceType 枚举转换
     *
     * @param deviceType IoT设备类型枚举
     * @return 统一设备类型
     */
    public static UnifiedDeviceType fromIotDeviceType(com.cretas.aims.entity.iot.DeviceType deviceType) {
        if (deviceType == null) {
            return null;
        }
        switch (deviceType) {
            case SCALE:
                return SCALE;
            case CAMERA:
                return CAMERA_GENERIC;
            case SENSOR:
                return SENSOR;
            case GATEWAY:
                return GATEWAY;
            default:
                throw new IllegalArgumentException("Unknown IotDevice.DeviceType: " + deviceType);
        }
    }

    /**
     * 从 IsapiDevice.DeviceType 枚举转换
     *
     * @param deviceType ISAPI设备类型枚举
     * @return 统一设备类型
     */
    public static UnifiedDeviceType fromIsapiDeviceType(com.cretas.aims.entity.isapi.IsapiDevice.DeviceType deviceType) {
        if (deviceType == null) {
            return null;
        }
        switch (deviceType) {
            case IPC:
                return CAMERA_IPC;
            case NVR:
                return CAMERA_NVR;
            case DVR:
                return CAMERA_DVR;
            case ENCODER:
                return CAMERA_ENCODER;
            default:
                throw new IllegalArgumentException("Unknown IsapiDevice.DeviceType: " + deviceType);
        }
    }

    /**
     * 从 DahuaDevice.DeviceType 枚举转换
     *
     * @param deviceType 大华设备类型枚举
     * @return 统一设备类型
     */
    public static UnifiedDeviceType fromDahuaDeviceType(com.cretas.aims.entity.dahua.DahuaDevice.DeviceType deviceType) {
        if (deviceType == null) {
            return null;
        }
        switch (deviceType) {
            case IPC:
                return CAMERA_IPC;
            case NVR:
                return CAMERA_NVR;
            case DVR:
                return CAMERA_DVR;
            case XVR:
                return CAMERA_XVR;
            default:
                throw new IllegalArgumentException("Unknown DahuaDevice.DeviceType: " + deviceType);
        }
    }

    /**
     * 从前端值或枚举名反序列化
     */
    @JsonCreator
    public static UnifiedDeviceType fromValue(String value) {
        if (value == null) {
            return null;
        }
        // 尝试匹配前端值
        for (UnifiedDeviceType type : values()) {
            if (type.frontendValue.equalsIgnoreCase(value)) {
                return type;
            }
        }
        // 尝试匹配枚举名
        for (UnifiedDeviceType type : values()) {
            if (type.name().equalsIgnoreCase(value)) {
                return type;
            }
        }
        throw new IllegalArgumentException("Unknown UnifiedDeviceType: " + value);
    }

    // ==================== 转换回旧枚举的方法 ====================

    /**
     * 转换为 DeviceCategory (向后兼容)
     *
     * @return 对应的 DeviceCategory，如果无法映射则返回 null
     */
    public com.cretas.aims.entity.enums.DeviceCategory toDeviceCategory() {
        switch (this) {
            case TRADITIONAL:
                return com.cretas.aims.entity.enums.DeviceCategory.TRADITIONAL;
            case SCALE:
                return com.cretas.aims.entity.enums.DeviceCategory.IOT_SCALE;
            case CAMERA_IPC:
            case CAMERA_NVR:
            case CAMERA_DVR:
            case CAMERA_ENCODER:
            case CAMERA_XVR:
            case CAMERA_GENERIC:
                return com.cretas.aims.entity.enums.DeviceCategory.IOT_CAMERA;
            case SENSOR:
                return com.cretas.aims.entity.enums.DeviceCategory.IOT_SENSOR;
            case GATEWAY:
                // Gateway 没有对应的 DeviceCategory，返回 null
                return null;
            default:
                return null;
        }
    }

    /**
     * 转换为 IotDevice.DeviceType (向后兼容)
     *
     * @return 对应的 DeviceType，如果无法映射则返回 null
     */
    public com.cretas.aims.entity.iot.DeviceType toIotDeviceType() {
        switch (this) {
            case SCALE:
                return com.cretas.aims.entity.iot.DeviceType.SCALE;
            case CAMERA_IPC:
            case CAMERA_NVR:
            case CAMERA_DVR:
            case CAMERA_ENCODER:
            case CAMERA_XVR:
            case CAMERA_GENERIC:
                return com.cretas.aims.entity.iot.DeviceType.CAMERA;
            case SENSOR:
                return com.cretas.aims.entity.iot.DeviceType.SENSOR;
            case GATEWAY:
                return com.cretas.aims.entity.iot.DeviceType.GATEWAY;
            default:
                return null;
        }
    }

    /**
     * 转换为 IsapiDevice.DeviceType (向后兼容)
     *
     * @return 对应的 DeviceType，如果无法映射则返回 null
     */
    public com.cretas.aims.entity.isapi.IsapiDevice.DeviceType toIsapiDeviceType() {
        switch (this) {
            case CAMERA_IPC:
                return com.cretas.aims.entity.isapi.IsapiDevice.DeviceType.IPC;
            case CAMERA_NVR:
                return com.cretas.aims.entity.isapi.IsapiDevice.DeviceType.NVR;
            case CAMERA_DVR:
                return com.cretas.aims.entity.isapi.IsapiDevice.DeviceType.DVR;
            case CAMERA_ENCODER:
                return com.cretas.aims.entity.isapi.IsapiDevice.DeviceType.ENCODER;
            default:
                return null;
        }
    }

    /**
     * 转换为 DahuaDevice.DeviceType (向后兼容)
     *
     * @return 对应的 DeviceType，如果无法映射则返回 null
     */
    public com.cretas.aims.entity.dahua.DahuaDevice.DeviceType toDahuaDeviceType() {
        switch (this) {
            case CAMERA_IPC:
                return com.cretas.aims.entity.dahua.DahuaDevice.DeviceType.IPC;
            case CAMERA_NVR:
                return com.cretas.aims.entity.dahua.DahuaDevice.DeviceType.NVR;
            case CAMERA_DVR:
                return com.cretas.aims.entity.dahua.DahuaDevice.DeviceType.DVR;
            case CAMERA_XVR:
                return com.cretas.aims.entity.dahua.DahuaDevice.DeviceType.XVR;
            default:
                return null;
        }
    }

    /**
     * 设备类型大类
     */
    public enum DeviceTypeCategory {
        TRADITIONAL("传统设备"),
        IOT("IoT设备");

        private final String displayName;

        DeviceTypeCategory(String displayName) {
            this.displayName = displayName;
        }

        public String getDisplayName() {
            return displayName;
        }
    }
}
