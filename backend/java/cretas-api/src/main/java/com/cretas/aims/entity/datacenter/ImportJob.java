package com.cretas.aims.entity.datacenter;

import com.cretas.aims.entity.BaseEntity;
import io.hypersistence.utils.hibernate.type.json.JsonBinaryType;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.Type;
import org.hibernate.annotations.Where;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 导入任务 — 跟踪 dryrun + commit 两步流程.
 *
 * <p>Sprint 4 Chat K C-IMPORT-CENTER-1. 上传 Excel 后先 dryrun 返 row-level errors,
 * 用户确认后再 commit 写 target_entity. 失败行通过 ExcelUtil 反向导出.
 */
@Data
@EqualsAndHashCode(callSuper = true)
@ToString
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
@Table(name = "import_jobs",
        indexes = {
                @Index(name = "idx_import_jobs_factory_status", columnList = "factory_id,status,created_at"),
                @Index(name = "idx_import_jobs_rule", columnList = "rule_id")
        })
@Where(clause = "deleted_at IS NULL")
public class ImportJob extends BaseEntity {

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

    /** PENDING | DRYRUN_DONE | COMMITTED | FAILED. */
    @Column(name = "status", nullable = false, length = 20)
    private String status;

    @Column(name = "source_filename", columnDefinition = "TEXT")
    private String sourceFilename;

    @Column(name = "source_file_path", columnDefinition = "TEXT")
    private String sourceFilePath;

    @Column(name = "total_rows")
    private Integer totalRows;

    @Column(name = "valid_rows")
    private Integer validRows;

    @Column(name = "error_rows")
    private Integer errorRows;

    @Column(name = "committed_rows")
    private Integer committedRows;

    /** Row-level errors: {@code [{"row":3,"col":"客户名","msg":"长度超过 100"},...]}. */
    @Type(JsonBinaryType.class)
    @Column(name = "errors", columnDefinition = "jsonb")
    private List<Map<String, Object>> errors;

    @Column(name = "error_file_path", columnDefinition = "TEXT")
    private String errorFilePath;

    @Column(name = "started_at")
    private LocalDateTime startedAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;
}
