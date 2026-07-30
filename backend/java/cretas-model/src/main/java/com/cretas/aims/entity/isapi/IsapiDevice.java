package com.cretas.aims.entity.isapi;

import com.cretas.aims.entity.BaseEntity;
import com.cretas.aims.entity.common.UnifiedDeviceType;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.*;
import lombok.experimental.SuperBuilder;
import lombok.extern.slf4j.Slf4j;
import org.hibernate.annotations.GenericGenerator;
import org.hibernate.annotations.SQLDelete;
import org.hibernate.annotations.Where;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * ISAPI 设备实体
 * 管理海康威视 IPC/NVR/DVR 设备
 *
 * @author Cretas Team
 * @since 2026-01-05
 */
@Entity
@Table(name = "isapi_devices",
        indexes = {
                @Index(name = "idx_factory_status", columnList = "factory_id, status"),
                @Index(name = "idx_ip_port", columnList = "ip_address, port")
        },
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_factory_ip_port",
                        columnNames = {"factory_id", "ip_address", "port", "deleted_at"})
        })
@SQLDelete(sql = "UPDATE isapi_devices SET deleted_at = NOW() WHERE id = ?")
@Where(clause = "deleted_at IS NULL")
@Slf4j
@Data
@EqualsAndHashCode(callSuper = true)
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class IsapiDevice extends BaseEntity {

    @Id
    @GeneratedValue(generator = "uuid2")
    @GenericGenerator(name = "uuid2", strategy = "uuid2")
    @Column(name = "id", length = 36)
    private String id;

    @Column(name = "factory_id", nullable = false, length = 50)
    private String factoryId;

    // ==================== 设备基本信息 ====================

    @Column(name = "device_name", nullable = false, length = 100)
    private String deviceName;

    /**
     * 设备类型
     * @deprecated 使用 {@link #getUnifiedDeviceType()} 获取统一设备类型
     */
    @Deprecated
    @Enumerated(EnumType.STRING)
    @Column(name = "device_type", nullable = false)
    private DeviceType deviceType;

    @Column(name = "device_model", length = 100)
    private String deviceModel;

    @Column(name = "serial_number", length = 100)
    private String serialNumber;

    @Column(name = "mac_address", length = 17)
    private String macAddress;

    @Column(name = "firmware_version", length = 50)
    private String firmwareVersion;

    // ==================== 网络配置 ====================

    @Column(name = "ip_address", nullable = false, length = 45)
    private String ipAddress;

    @Column(name = "port", nullable = false)
    @Builder.Default
    private Integer port = 80;

    @Column(name = "rtsp_port")
    @Builder.Default
    private Integer rtspPort = 554;

    @Column(name = "https_port")
    @Builder.Default
    private Integer httpsPort = 443;

    @Enumerated(EnumType.STRING)
    @Column(name = "protocol", nullable = false)
    @Builder.Default
    private Protocol protocol = Protocol.HTTP;

    // ==================== 认证信息 ====================

    @Column(name = "username", nullable = false, length = 50)
    private String username;

    @Column(name = "password_encrypted", nullable = false, length = 255)
    private String passwordEncrypted;

    // ==================== 设备能力 ====================

    @Column(name = "channel_count", nullable = false)
    @Builder.Default
    private Integer channelCount = 1;

    @Column(name = "supports_ptz")
    @Builder.Default
    private Boolean supportsPtz = false;

    @Column(name = "supports_audio")
    @Builder.Default
    private Boolean supportsAudio = false;

    @Column(name = "supports_smart")
    @Builder.Default
    private Boolean supportsSmart = false;

    @Column(name = "device_capabilities", columnDefinition = "JSON")
    private String deviceCapabilitiesJson;

    // ==================== 状态信息 ====================

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    @Builder.Default
    private DeviceStatus status = DeviceStatus.UNKNOWN;

    @Column(name = "last_error", length = 500)
    private String lastError;

    @Column(name = "last_heartbeat_at")
    private LocalDateTime lastHeartbeatAt;

    @Column(name = "last_event_at")
    private LocalDateTime lastEventAt;

    // ==================== 订阅状态 ====================

    @Column(name = "alert_subscribed")
    @Builder.Default
    private Boolean alertSubscribed = false;

    @Column(name = "subscribed_events", columnDefinition = "JSON")
    private String subscribedEventsJson;

    // ==================== 位置信息 ====================

    @Column(name = "location_description", length = 255)
    private String locationDescription;

    @Column(name = "latitude")
    private Double latitude;

    @Column(name = "longitude")
    private Double longitude;

    // ==================== 关联信息 ====================

    @Column(name = "department_id", length = 36)
    private String departmentId;

    @Column(name = "equipment_id")
    private Long equipmentId;

    // ==================== 通道关系 ====================

    @OneToMany(mappedBy = "device", cascade = CascadeType.ALL, orphanRemoval = true)
    @com.fasterxml.jackson.annotation.JsonIgnore
    private List<IsapiDeviceChannel> channels;

    // ==================== 统一设备类型方法 ====================

    /**
     * 获取统一设备类型
     * 将旧的 deviceType 映射到新的 UnifiedDeviceType
     *
     * @return 统一设备类型
     */
    @Transient
    public UnifiedDeviceType getUnifiedDeviceType() {
        return UnifiedDeviceType.fromIsapiDeviceType(this.deviceType);
    }

    /**
     * 设置统一设备类型
     * 自动转换为旧的 deviceType 以保持兼容
     *
     * @param unifiedType 统一设备类型
     */
    public void setUnifiedDeviceType(UnifiedDeviceType unifiedType) {
        this.deviceType = unifiedType != null ? unifiedType.toIsapiDeviceType() : null;
    }

    // ==================== 枚举定义 ====================

    /**
     * ISAPI 设备类型枚举
     * @deprecated 使用 {@link UnifiedDeviceType} 代替
     */
    @Deprecated
    public enum DeviceType {
        IPC,    // 网络摄像机
        NVR,    // 网络硬盘录像机
        DVR,    // 硬盘录像机
        ENCODER // 编码器
    }

    public enum Protocol {
        HTTP,
        HTTPS
    }

    public enum DeviceStatus {
        ONLINE,      // 在线
        OFFLINE,     // 离线
        CONNECTING,  // 连接中
        ERROR,       // 错误
        UNKNOWN      // 未知
    }

    // ==================== 便捷方法 ====================

    /**
     * 获取设备基础URL
     */
    public String getBaseUrl() {
        return String.format("%s://%s:%d",
                protocol.name().toLowerCase(), ipAddress, port);
    }

    /**
     * 获取 RTSP 基础 URL
     */
    public String getRtspBaseUrl() {
        return String.format("rtsp://%s:%d", ipAddress, rtspPort);
    }

    /**
     * 更新心跳时间并设为在线
     */
    public void heartbeat() {
        this.lastHeartbeatAt = LocalDateTime.now();
        this.status = DeviceStatus.ONLINE;
        this.lastError = null;
    }

    /**
     * 设置为离线状态
     */
    public void markOffline(String reason) {
        this.status = DeviceStatus.OFFLINE;
        this.lastError = reason;
    }

    /**
     * 设置为错误状态
     */
    public void markError(String errorMessage) {
        this.status = DeviceStatus.ERROR;
        this.lastError = errorMessage;
    }

    // ==================== JSON 辅助方法 ====================

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    /**
     * 获取设备能力 (反序列化)
     */
    @Transient
    public Map<String, Object> getDeviceCapabilities() {
        if (deviceCapabilitiesJson == null || deviceCapabilitiesJson.isEmpty()) {
            return Collections.emptyMap();
        }
        try {
            return OBJECT_MAPPER.readValue(deviceCapabilitiesJson,
                    new TypeReference<Map<String, Object>>() {});
        } catch (JsonProcessingException e) {
            log.warn("解析设备能力JSON失败: {}", e.getMessage());
            return Collections.emptyMap();
        }
    }

    /**
     * 设置设备能力 (序列化)
     */
    public void setDeviceCapabilities(Map<String, Object> capabilities) {
        if (capabilities == null || capabilities.isEmpty()) {
            this.deviceCapabilitiesJson = null;
            return;
        }
        try {
            this.deviceCapabilitiesJson = OBJECT_MAPPER.writeValueAsString(capabilities);
        } catch (JsonProcessingException e) {
            log.warn("序列化设备能力JSON失败: {}", e.getMessage());
        }
    }

    /**
     * 获取订阅事件列表 (反序列化)
     */
    @Transient
    public List<String> getSubscribedEvents() {
        if (subscribedEventsJson == null || subscribedEventsJson.isEmpty()) {
            return Collections.emptyList();
        }
        try {
            return OBJECT_MAPPER.readValue(subscribedEventsJson,
                    new TypeReference<List<String>>() {});
        } catch (JsonProcessingException e) {
            log.warn("解析订阅事件JSON失败: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * 设置订阅事件列表 (序列化)
     */
    public void setSubscribedEvents(List<String> events) {
        if (events == null || events.isEmpty()) {
            this.subscribedEventsJson = null;
            return;
        }
        try {
            this.subscribedEventsJson = OBJECT_MAPPER.writeValueAsString(events);
        } catch (JsonProcessingException e) {
            log.warn("序列化订阅事件JSON失败: {}", e.getMessage());
        }
    }
}
