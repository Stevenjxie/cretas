package com.cretas.aims.entity.voice;

import com.cretas.aims.entity.BaseEntity;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;
import lombok.Builder;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import org.hibernate.annotations.Where;

/**
 * 批量语音识别任务实体
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2025-12-31
 */
@Entity
@Table(name = "batch_voice_task", indexes = {
    @Index(name = "idx_bvt_factory_id", columnList = "factory_id"),
    @Index(name = "idx_bvt_user_id", columnList = "user_id"),
    @Index(name = "idx_bvt_status", columnList = "status"),
    @Index(name = "idx_bvt_created_at", columnList = "created_at")
})
@Data
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@Where(clause = "deleted_at IS NULL")
public class BatchVoiceTask extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * 任务编号
     */
    @Column(name = "task_number", length = 50, unique = true)
    private String taskNumber;

    /**
     * 工厂ID
     */
    @Column(name = "factory_id", length = 50)
    private String factoryId;

    /**
     * 创建用户ID
     */
    @Column(name = "user_id")
    private Long userId;

    /**
     * 创建用户名
     */
    @Column(name = "username", length = 100)
    private String username;

    /**
     * 任务状态: PENDING-待处理, PROCESSING-处理中, COMPLETED-已完成, FAILED-失败, CANCELLED-已取消
     */
    @Column(name = "status", length = 20)
    @Builder.Default
    private String status = "PENDING";

    /**
     * 总文件数
     */
    @Column(name = "total_files")
    @Builder.Default
    private Integer totalFiles = 0;

    /**
     * 已处理文件数
     */
    @Column(name = "processed_files")
    @Builder.Default
    private Integer processedFiles = 0;

    /**
     * 成功识别数
     */
    @Column(name = "success_count")
    @Builder.Default
    private Integer successCount = 0;

    /**
     * 失败数
     */
    @Column(name = "failure_count")
    @Builder.Default
    private Integer failureCount = 0;

    /**
     * 进度百分比 (0-100)
     */
    @Column(name = "progress")
    @Builder.Default
    private Integer progress = 0;

    /**
     * 音频格式
     */
    @Column(name = "audio_format", length = 20)
    @Builder.Default
    private String audioFormat = "raw";

    /**
     * 音频编码
     */
    @Column(name = "audio_encoding", length = 20)
    @Builder.Default
    private String audioEncoding = "raw";

    /**
     * 采样率
     */
    @Column(name = "sample_rate")
    @Builder.Default
    private Integer sampleRate = 16000;

    /**
     * 语言
     */
    @Column(name = "language", length = 10)
    @Builder.Default
    private String language = "zh_cn";

    /**
     * 业务场景
     */
    @Column(name = "business_scene", length = 50)
    @Builder.Default
    private String businessScene = "BATCH_PROCESS";

    /**
     * 任务开始时间
     */
    @Column(name = "started_at")
    private LocalDateTime startedAt;

    /**
     * 任务完成时间
     */
    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    /**
     * 总处理耗时(毫秒)
     */
    @Column(name = "total_duration_ms")
    private Long totalDurationMs;

    /**
     * 错误信息
     */
    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    /**
     * 结果JSON (包含每个文件的识别结果)
     */
    // columnDefinition was "LONGTEXT" (MySQL-only) — PG-equivalent is TEXT
    // (PG TEXT has no size limit, equivalent capacity). Prod table already
    // uses TEXT via db/migration-pg-converted/
    // V2025_12_31_8__voice_recognition_tables.sql. See
    // LearnedExpression.embeddingVector comment for full context.
    @Column(name = "result_json", columnDefinition = "TEXT")
    private String resultJson;

    /**
     * 备注
     */
    @Column(name = "notes", length = 500)
    private String notes;
}
