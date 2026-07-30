package com.cretas.aims.entity.isapi;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.*;
import lombok.extern.slf4j.Slf4j;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * ISAPI 事件日志实体
 * 记录设备上报的所有告警事件
 *
 * @author Cretas Team
 * @since 2026-01-05
 */
@Entity
@Table(name = "isapi_event_logs",
        indexes = {
                @Index(name = "idx_factory_device", columnList = "factory_id, device_id"),
                @Index(name = "idx_event_type", columnList = "event_type"),
                @Index(name = "idx_event_time", columnList = "event_time"),
                @Index(name = "idx_processed", columnList = "processed"),
                @Index(name = "idx_received_time", columnList = "received_time")
        })
@Slf4j
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class IsapiEventLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "factory_id", nullable = false, length = 50)
    private String factoryId;

    @Column(name = "device_id", nullable = false, length = 36)
    private String deviceId;

    // ==================== 事件信息 ====================

    @Column(name = "event_type", nullable = false, length = 50)
    private String eventType;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_state", nullable = false)
    private EventState eventState;

    @Column(name = "event_description", length = 500)
    private String eventDescription;

    // ==================== 通道信息 ====================

    @Column(name = "channel_id")
    private Integer channelId;

    @Column(name = "channel_name", length = 100)
    private String channelName;

    // ==================== 事件详情 ====================

    @Column(name = "event_data", columnDefinition = "JSON")
    private String eventDataJson;

    @Column(name = "detection_region", columnDefinition = "JSON")
    private String detectionRegionJson;

    // ==================== 图片证据 ====================

    @Column(name = "picture_url", length = 500)
    private String pictureUrl;

    @Column(name = "picture_data", columnDefinition = "bytea")
    private byte[] pictureData;

    // ==================== 时间戳 ====================

    @Column(name = "event_time", nullable = false)
    private LocalDateTime eventTime;

    @Column(name = "received_time", nullable = false)
    @Builder.Default
    private LocalDateTime receivedTime = LocalDateTime.now();

    // ==================== 处理状态 ====================

    @Column(name = "processed")
    @Builder.Default
    private Boolean processed = false;

    @Column(name = "processed_at")
    private LocalDateTime processedAt;

    @Column(name = "processed_by", length = 50)
    private String processedBy;

    @Column(name = "process_result", length = 255)
    private String processResult;

    // ==================== 关联告警 ====================

    @Column(name = "alert_id", length = 36)
    private String alertId;

    // ==================== AI 分析结果 ====================

    @Column(name = "ai_analyzed")
    @Builder.Default
    private Boolean aiAnalyzed = false;

    @Column(name = "ai_analyzed_at")
    private LocalDateTime aiAnalyzedAt;

    @Column(name = "ai_threat_level", length = 20)
    private String aiThreatLevel;  // HIGH, MEDIUM, LOW, NONE

    @Column(name = "ai_detected_objects", length = 500)
    private String aiDetectedObjects;  // JSON array

    @Column(name = "ai_object_count")
    private Integer aiObjectCount;

    @Column(name = "ai_scene_description", length = 500)
    private String aiSceneDescription;

    @Column(name = "ai_risk_assessment", length = 1000)
    private String aiRiskAssessment;

    @Column(name = "ai_recommended_actions", length = 1000)
    private String aiRecommendedActions;  // JSON array

    @Column(name = "ai_production_impact", length = 500)
    private String aiProductionImpact;

    @Column(name = "ai_hygiene_concern")
    @Builder.Default
    private Boolean aiHygieneConcern = false;

    @Column(name = "ai_safety_concern")
    @Builder.Default
    private Boolean aiSafetyConcern = false;

    @Column(name = "ai_requires_action")
    @Builder.Default
    private Boolean aiRequiresAction = false;

    // ==================== 关联设备 ====================

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "device_id", insertable = false, updatable = false)
    private IsapiDevice device;

    // ==================== 枚举定义 ====================

    public enum EventState {
        ACTIVE,   // 事件开始/触发
        INACTIVE  // 事件结束/心跳
    }

    // ==================== 便捷方法 ====================

    /**
     * 标记为已处理
     */
    public void markProcessed(String processedBy, String result) {
        this.processed = true;
        this.processedAt = LocalDateTime.now();
        this.processedBy = processedBy;
        this.processResult = result;
    }

    /**
     * 设置 AI 分析结果
     */
    public void setAiAnalysisResult(String threatLevel, List<String> detectedObjects, int objectCount,
                                     String sceneDescription, String riskAssessment,
                                     List<String> recommendedActions, String productionImpact,
                                     boolean hygieneConcern, boolean safetyConcern) {
        this.aiAnalyzed = true;
        this.aiAnalyzedAt = LocalDateTime.now();
        this.aiThreatLevel = threatLevel;
        this.aiObjectCount = objectCount;
        this.aiSceneDescription = sceneDescription;
        this.aiRiskAssessment = riskAssessment;
        this.aiProductionImpact = productionImpact;
        this.aiHygieneConcern = hygieneConcern;
        this.aiSafetyConcern = safetyConcern;
        this.aiRequiresAction = "HIGH".equalsIgnoreCase(threatLevel) || hygieneConcern || safetyConcern;

        // 序列化列表为 JSON
        try {
            if (detectedObjects != null && !detectedObjects.isEmpty()) {
                this.aiDetectedObjects = OBJECT_MAPPER.writeValueAsString(detectedObjects);
            }
            if (recommendedActions != null && !recommendedActions.isEmpty()) {
                this.aiRecommendedActions = OBJECT_MAPPER.writeValueAsString(recommendedActions);
            }
        } catch (JsonProcessingException e) {
            log.warn("序列化 AI 分析结果失败: {}", e.getMessage());
        }
    }

    /**
     * 获取 AI 检测对象列表
     */
    @Transient
    public List<String> getAiDetectedObjectsList() {
        if (aiDetectedObjects == null || aiDetectedObjects.isEmpty()) {
            return Collections.emptyList();
        }
        try {
            return OBJECT_MAPPER.readValue(aiDetectedObjects, new TypeReference<List<String>>() {});
        } catch (JsonProcessingException e) {
            log.warn("解析 AI 检测对象失败: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * 获取 AI 建议措施列表
     */
    @Transient
    public List<String> getAiRecommendedActionsList() {
        if (aiRecommendedActions == null || aiRecommendedActions.isEmpty()) {
            return Collections.emptyList();
        }
        try {
            return OBJECT_MAPPER.readValue(aiRecommendedActions, new TypeReference<List<String>>() {});
        } catch (JsonProcessingException e) {
            log.warn("解析 AI 建议措施失败: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * 判断是否为心跳事件
     * 海康设备通过 videoloss + inactive 作为心跳
     */
    public boolean isHeartbeat() {
        return "videoloss".equalsIgnoreCase(eventType)
                && eventState == EventState.INACTIVE;
    }

    /**
     * 判断是否需要告警
     */
    public boolean shouldAlert() {
        // 心跳事件不告警
        if (isHeartbeat()) {
            return false;
        }
        // ACTIVE 状态的事件需要告警
        return eventState == EventState.ACTIVE;
    }

    /**
     * 获取事件类型中文名
     */
    public String getEventTypeName() {
        switch (eventType.toLowerCase()) {
            case "vmd":
                return "移动侦测";
            case "linedetection":
                return "越界检测";
            case "fielddetection":
                return "区域入侵";
            case "facedetection":
                return "人脸检测";
            case "videoloss":
                return "视频丢失";
            case "shelteralarm":
                return "遮挡报警";
            case "diskfull":
                return "硬盘满";
            case "diskerror":
                return "硬盘错误";
            case "illaccess":
                return "非法访问";
            case "ipconflict":
                return "IP冲突";
            case "netabort":
                return "网络异常";
            default:
                return eventType;
        }
    }

    // ==================== JSON 辅助方法 ====================

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    /**
     * 获取事件数据 (反序列化)
     */
    @Transient
    public Map<String, Object> getEventData() {
        if (eventDataJson == null || eventDataJson.isEmpty()) {
            return Collections.emptyMap();
        }
        try {
            return OBJECT_MAPPER.readValue(eventDataJson,
                    new TypeReference<Map<String, Object>>() {});
        } catch (JsonProcessingException e) {
            log.warn("解析事件数据JSON失败: {}", e.getMessage());
            return Collections.emptyMap();
        }
    }

    /**
     * 设置事件数据 (序列化)
     */
    public void setEventData(Map<String, Object> data) {
        if (data == null || data.isEmpty()) {
            this.eventDataJson = null;
            return;
        }
        try {
            this.eventDataJson = OBJECT_MAPPER.writeValueAsString(data);
        } catch (JsonProcessingException e) {
            log.warn("序列化事件数据JSON失败: {}", e.getMessage());
        }
    }

    /**
     * 获取检测区域 (反序列化)
     */
    @Transient
    public Map<String, Object> getDetectionRegion() {
        if (detectionRegionJson == null || detectionRegionJson.isEmpty()) {
            return Collections.emptyMap();
        }
        try {
            return OBJECT_MAPPER.readValue(detectionRegionJson,
                    new TypeReference<Map<String, Object>>() {});
        } catch (JsonProcessingException e) {
            log.warn("解析检测区域JSON失败: {}", e.getMessage());
            return Collections.emptyMap();
        }
    }

    /**
     * 设置检测区域 (序列化)
     */
    public void setDetectionRegion(Map<String, Object> region) {
        if (region == null || region.isEmpty()) {
            this.detectionRegionJson = null;
            return;
        }
        try {
            this.detectionRegionJson = OBJECT_MAPPER.writeValueAsString(region);
        } catch (JsonProcessingException e) {
            log.warn("序列化检测区域JSON失败: {}", e.getMessage());
        }
    }
}
