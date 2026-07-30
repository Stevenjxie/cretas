package com.cretas.aims.entity.isapi;

import com.cretas.aims.entity.BaseEntity;
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
import java.util.Collections;
import java.util.List;

/**
 * ISAPI 设备通道实体
 * 用于管理 NVR 多通道
 *
 * @author Cretas Team
 * @since 2026-01-05
 */
@Entity
@Table(name = "isapi_device_channels",
        indexes = {
                @Index(name = "idx_device_channel", columnList = "device_id, channel_id"),
                @Index(name = "idx_factory_id", columnList = "factory_id")
        },
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_device_channel",
                        columnNames = {"device_id", "channel_id", "deleted_at"})
        })
@SQLDelete(sql = "UPDATE isapi_device_channels SET deleted_at = NOW() WHERE id = ?")
@Where(clause = "deleted_at IS NULL")
@Slf4j
@Data
@EqualsAndHashCode(callSuper = true)
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class IsapiDeviceChannel extends BaseEntity {

    @Id
    @GeneratedValue(generator = "uuid2")
    @GenericGenerator(name = "uuid2", strategy = "uuid2")
    @Column(name = "id", length = 36)
    private String id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "device_id", nullable = false)
    private IsapiDevice device;

    @Column(name = "factory_id", nullable = false, length = 50)
    private String factoryId;

    // ==================== 通道信息 ====================

    @Column(name = "channel_id", nullable = false)
    private Integer channelId;

    @Column(name = "channel_name", length = 100)
    private String channelName;

    @Enumerated(EnumType.STRING)
    @Column(name = "channel_type")
    @Builder.Default
    private ChannelType channelType = ChannelType.IP;

    // ==================== 关联的IPC设备 ====================

    @Column(name = "source_ip", length = 45)
    private String sourceIp;

    @Column(name = "source_port")
    private Integer sourcePort;

    // ==================== 流信息 ====================

    @Column(name = "main_stream_url", length = 500)
    private String mainStreamUrl;

    @Column(name = "sub_stream_url", length = 500)
    private String subStreamUrl;

    // ==================== 状态 ====================

    @Enumerated(EnumType.STRING)
    @Column(name = "status")
    @Builder.Default
    private ChannelStatus status = ChannelStatus.OFFLINE;

    // ==================== 配置 ====================

    @Column(name = "recording_enabled")
    @Builder.Default
    private Boolean recordingEnabled = false;

    @Column(name = "smart_enabled")
    @Builder.Default
    private Boolean smartEnabled = false;

    @Column(name = "enabled_events", columnDefinition = "JSON")
    private String enabledEventsJson;

    // ==================== 枚举定义 ====================

    public enum ChannelType {
        ANALOG,  // 模拟通道
        IP,      // IP通道
        VIRTUAL  // 虚拟通道
    }

    public enum ChannelStatus {
        ONLINE,   // 在线
        OFFLINE,  // 离线
        NO_VIDEO  // 无视频信号
    }

    // ==================== 便捷方法 ====================

    /**
     * 获取通道显示名称
     */
    public String getDisplayName() {
        if (channelName != null && !channelName.isEmpty()) {
            return channelName;
        }
        return "Channel " + channelId;
    }

    /**
     * 构建主码流 RTSP URL
     */
    public String buildMainStreamUrl(String username, String password) {
        if (mainStreamUrl != null) {
            return mainStreamUrl;
        }
        if (device != null) {
            return String.format("rtsp://%s:%s@%s:%d/Streaming/Channels/%d01",
                    username, password,
                    device.getIpAddress(), device.getRtspPort(),
                    channelId);
        }
        return null;
    }

    /**
     * 构建子码流 RTSP URL
     */
    public String buildSubStreamUrl(String username, String password) {
        if (subStreamUrl != null) {
            return subStreamUrl;
        }
        if (device != null) {
            return String.format("rtsp://%s:%s@%s:%d/Streaming/Channels/%d02",
                    username, password,
                    device.getIpAddress(), device.getRtspPort(),
                    channelId);
        }
        return null;
    }

    // ==================== JSON 辅助方法 ====================

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    /**
     * 获取启用的事件列表 (反序列化)
     */
    @Transient
    public List<String> getEnabledEvents() {
        if (enabledEventsJson == null || enabledEventsJson.isEmpty()) {
            return Collections.emptyList();
        }
        try {
            return OBJECT_MAPPER.readValue(enabledEventsJson,
                    new TypeReference<List<String>>() {});
        } catch (JsonProcessingException e) {
            log.warn("解析启用事件JSON失败: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * 设置启用的事件列表 (序列化)
     */
    public void setEnabledEvents(List<String> events) {
        if (events == null || events.isEmpty()) {
            this.enabledEventsJson = null;
            return;
        }
        try {
            this.enabledEventsJson = OBJECT_MAPPER.writeValueAsString(events);
        } catch (JsonProcessingException e) {
            log.warn("序列化启用事件JSON失败: {}", e.getMessage());
        }
    }
}
