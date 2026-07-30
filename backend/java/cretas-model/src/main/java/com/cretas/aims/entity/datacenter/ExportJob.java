package com.cretas.aims.entity.datacenter;

import com.cretas.aims.entity.BaseEntity;
import io.hypersistence.utils.hibernate.type.json.JsonBinaryType;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.Type;
import org.hibernate.annotations.Where;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

/**
 * 异步导出任务 — 用户触发 ExportRule 时若估算行数 > rowThreshold 入 queue.
 *
 * <p>Sprint 4 Chat K C-EXPORT-CENTER-1. Spring @Async + ThreadPoolTaskExecutor 执行,
 * 完成后落 /tmp/export/{jobId}.xlsx, Vue 轮询 status=SUCCESS 后 download.
 */
@Data
@EqualsAndHashCode(callSuper = true)
@ToString
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
@Table(name = "export_jobs",
        indexes = {
                @Index(name = "idx_export_jobs_factory_status", columnList = "factory_id,status,created_at"),
                @Index(name = "idx_export_jobs_rule", columnList = "rule_id")
        })
@Where(clause = "deleted_at IS NULL")
public class ExportJob extends BaseEntity {

    @Id
    @Column(name = "id", nullable = false, length = 64)
    private String id;

    @PrePersist
    void assignUUID() {
        if (id == null) id = UUID.randomUUID().toString();
    }

    @Column(name = "factory_id", nullable = false, length = 64)
    private String factoryId;

    @Column(name = "rule_id", nullable = false)
    private Long ruleId;

    @Column(name = "triggered_by", nullable = false)
    private Long triggeredBy;

    /** PENDING | RUNNING | SUCCESS | FAILED. */
    @Column(name = "status", nullable = false, length = 20)
    private String status;

    /** SpEL filter 输入变量, e.g. {@code {"startDate":"2026-01-01","endDate":"2026-05-31"}}. */
    @Type(JsonBinaryType.class)
    @Column(name = "runtime_params", columnDefinition = "jsonb")
    private Map<String, Object> runtimeParams;

    @Column(name = "file_path", columnDefinition = "TEXT")
    private String filePath;

    @Column(name = "file_size_bytes")
    private Long fileSizeBytes;

    @Column(name = "row_count")
    private Integer rowCount;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "started_at")
    private LocalDateTime startedAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;
}
