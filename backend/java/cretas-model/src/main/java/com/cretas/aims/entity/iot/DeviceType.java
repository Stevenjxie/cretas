package com.cretas.aims.entity.iot;

import com.cretas.aims.entity.common.UnifiedDeviceType;

/**
 * IoT设备类型枚举
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2025-01-04
 * @deprecated 使用 {@link UnifiedDeviceType} 代替此枚举
 */
@Deprecated
public enum DeviceType {
    SCALE,      // 电子秤
    SENSOR,     // 传感器(温度/湿度)
    CAMERA,     // 摄像头
    GATEWAY;    // 边缘网关

    /**
     * 转换为统一设备类型
     *
     * @return 对应的 UnifiedDeviceType
     */
    public UnifiedDeviceType toUnifiedDeviceType() {
        return UnifiedDeviceType.fromIotDeviceType(this);
    }
}
