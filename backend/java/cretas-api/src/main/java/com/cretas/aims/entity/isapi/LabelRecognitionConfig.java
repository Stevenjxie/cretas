package com.cretas.aims.entity.isapi;

import com.cretas.aims.entity.BaseEntity;
import lombok.*;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.SQLDelete;
import org.hibernate.annotations.Where;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * 标签识别配置实体
 * 配置哪个摄像头用于标签识别，以及触发条件
 *
 * @author Cretas Team
 * @since 2026-01-13
 */
@Entity
@Table(name = "label_recognition_configs",
        indexes = {
                @Index(name = "idx_lrc_factory_id", columnList = "factory_id"),
                @Index(name = "idx_lrc_device_id", columnList = "device_id"),
                @Index(name = "idx_lrc_enabled", columnList = "enabled")
        })
@SQLDelete(sql = "UPDATE label_recognition_configs SET deleted_at = NOW() WHERE id = ?")
@Where(clause = "deleted_at IS NULL")
@Data
@EqualsAndHashCode(callSuper = true)
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class LabelRecognitionConfig extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "factory_id", nullable = false, length = 50)
    private String factoryId;

    @Column(name = "device_id", nullable = false, length = 36)
    private String deviceId;

    @Column(name = "channel_id")
    @Builder.Default
    private Integer channelId = 1;

    @Column(name = "config_name", length = 100)
    private String configName;

    // ==================== 触发配置 ====================

    @Column(name = "trigger_on_vmd")
    @Builder.Default
    private Boolean triggerOnVmd = true;

    @Column(name = "trigger_on_field_detection")
    @Builder.Default
    private Boolean triggerOnFieldDetection = false;

    @Column(name = "cooldown_seconds")
    @Builder.Default
    private Integer cooldownSeconds = 3;

    // ==================== 识别配置 ====================

    @Column(name = "min_confidence")
    @Builder.Default
    private Double minConfidence = 0.7;

    @Column(name = "default_batch_id", length = 100)
    private String defaultBatchId;

    // ==================== 状态 ====================

    @Column(name = "enabled")
    @Builder.Default
    private Boolean enabled = true;

    @Column(name = "last_trigger_time")
    private LocalDateTime lastTriggerTime;

    // ==================== 便捷方法 ====================

    /**
     * 检查是否在冷却期内
     */
    public boolean isInCooldown() {
        if (lastTriggerTime == null) {
            return false;
        }
        LocalDateTime cooldownEnd = lastTriggerTime.plusSeconds(cooldownSeconds);
        return LocalDateTime.now().isBefore(cooldownEnd);
    }

    /**
     * 更新最后触发时间
     */
    public void updateTriggerTime() {
        this.lastTriggerTime = LocalDateTime.now();
    }

    /**
     * 检查是否应该触发识别
     */
    public boolean shouldTrigger(String eventType) {
        if (!Boolean.TRUE.equals(enabled)) {
            return false;
        }
        if (isInCooldown()) {
            return false;
        }
        if ("VMD".equalsIgnoreCase(eventType) && Boolean.TRUE.equals(triggerOnVmd)) {
            return true;
        }
        if ("fielddetection".equalsIgnoreCase(eventType) && Boolean.TRUE.equals(triggerOnFieldDetection)) {
            return true;
        }
        return false;
    }
}
