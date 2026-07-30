package com.cretas.aims.entity.isapi;

import com.cretas.aims.entity.BaseEntity;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.*;
import lombok.experimental.SuperBuilder;
import lombok.extern.slf4j.Slf4j;
import org.hibernate.annotations.SQLDelete;
import org.hibernate.annotations.Where;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;

/**
 * 标签识别记录实体
 * 记录每次标签识别的结果
 *
 * @author Cretas Team
 * @since 2026-01-13
 */
@Entity
@Table(name = "label_recognition_records",
        indexes = {
                @Index(name = "idx_lrr_factory_id", columnList = "factory_id"),
                @Index(name = "idx_lrr_config_id", columnList = "config_id"),
                @Index(name = "idx_lrr_status", columnList = "status"),
                @Index(name = "idx_lrr_recognition_time", columnList = "recognition_time"),
                @Index(name = "idx_lrr_batch_number", columnList = "recognized_batch_number")
        })
@SQLDelete(sql = "UPDATE label_recognition_records SET deleted_at = NOW() WHERE id = ?")
@Where(clause = "deleted_at IS NULL")
@Slf4j
@Data
@EqualsAndHashCode(callSuper = true)
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class LabelRecognitionRecord extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "factory_id", nullable = false, length = 50)
    private String factoryId;

    @Column(name = "config_id")
    private Long configId;

    @Column(name = "device_id", length = 36)
    private String deviceId;

    // ==================== 触发信息 ====================

    @Enumerated(EnumType.STRING)
    @Column(name = "trigger_type", nullable = false, length = 20)
    private TriggerType triggerType;

    @Column(name = "trigger_event_id", length = 100)
    private String triggerEventId;

    // ==================== 识别结果 ====================

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private RecognitionStatus status;

    @Column(name = "recognized_batch_number", length = 100)
    private String recognizedBatchNumber;

    @Column(name = "expected_batch_number", length = 100)
    private String expectedBatchNumber;

    @Column(name = "batch_match")
    private Boolean batchMatch;

    // ==================== 标签质量 ====================

    @Enumerated(EnumType.STRING)
    @Column(name = "print_quality", length = 20)
    private PrintQuality printQuality;

    @Column(name = "confidence")
    private Double confidence;

    @Column(name = "quality_score")
    private Double qualityScore;

    @Column(name = "quality_issues", columnDefinition = "TEXT")
    private String qualityIssuesJson;

    // ==================== 图片数据 ====================

    @Column(name = "captured_image", columnDefinition = "bytea")
    private byte[] capturedImage;

    @Column(name = "captured_image_url", length = 500)
    private String capturedImageUrl;

    // ==================== 时间 ====================

    @Column(name = "recognition_time", nullable = false)
    private LocalDateTime recognitionTime;

    @Column(name = "processing_duration_ms")
    private Integer processingDurationMs;

    // ==================== AI响应 ====================

    @Column(name = "ai_response", columnDefinition = "TEXT")
    private String aiResponse;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    // ==================== 枚举定义 ====================

    /**
     * 触发类型
     */
    public enum TriggerType {
        VMD,              // 移动侦测触发
        FIELD_DETECTION,  // 区域入侵触发
        MANUAL            // 手动触发
    }

    /**
     * 识别状态
     */
    public enum RecognitionStatus {
        SUCCESS,       // 识别成功
        FAILED,        // 识别失败
        NO_LABEL,      // 未检测到标签
        LOW_CONFIDENCE // 置信度不足
    }

    /**
     * 打印质量
     */
    public enum PrintQuality {
        GOOD,         // 良好
        ACCEPTABLE,   // 可接受
        POOR,         // 较差
        UNREADABLE    // 无法识别
    }

    // ==================== JSON 辅助方法 ====================

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    /**
     * 获取质量问题列表
     */
    @Transient
    public List<String> getQualityIssues() {
        if (qualityIssuesJson == null || qualityIssuesJson.isEmpty()) {
            return Collections.emptyList();
        }
        try {
            return OBJECT_MAPPER.readValue(qualityIssuesJson, new TypeReference<List<String>>() {});
        } catch (JsonProcessingException e) {
            log.warn("解析质量问题JSON失败: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * 设置质量问题列表
     */
    public void setQualityIssues(List<String> issues) {
        if (issues == null || issues.isEmpty()) {
            this.qualityIssuesJson = null;
            return;
        }
        try {
            this.qualityIssuesJson = OBJECT_MAPPER.writeValueAsString(issues);
        } catch (JsonProcessingException e) {
            log.warn("序列化质量问题JSON失败: {}", e.getMessage());
        }
    }

    // ==================== 便捷方法 ====================

    /**
     * 是否识别成功
     */
    public boolean isSuccess() {
        return status == RecognitionStatus.SUCCESS;
    }

    /**
     * 是否需要告警
     */
    public boolean requiresAlert() {
        if (status == RecognitionStatus.FAILED) return true;
        if (Boolean.FALSE.equals(batchMatch)) return true;
        if (printQuality == PrintQuality.POOR || printQuality == PrintQuality.UNREADABLE) return true;
        return false;
    }

    /**
     * 获取状态显示名称
     */
    @Transient
    public String getStatusDisplayName() {
        if (status == null) return "未知";
        return switch (status) {
            case SUCCESS -> "识别成功";
            case FAILED -> "识别失败";
            case NO_LABEL -> "未检测到标签";
            case LOW_CONFIDENCE -> "置信度不足";
        };
    }
}
