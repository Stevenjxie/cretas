package com.cretas.aims.entity;

import com.cretas.aims.entity.enums.LabelQcTaskStatus;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.Where;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

@Data
@EqualsAndHashCode(callSuper = true)
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "label_qc_tasks",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_label_qc_task_idempotency",
                columnNames = {"factory_id", "created_by", "idempotency_key"}),
        indexes = {
                @Index(name = "idx_label_qc_task_factory_status", columnList = "factory_id,status,created_at"),
                @Index(name = "idx_label_qc_task_reviewed", columnList = "factory_id,reviewed_at")
        })
@Where(clause = "deleted_at IS NULL")
public class LabelQcTask extends BaseEntity {

    @Id
    @Column(name = "id", nullable = false, length = 36)
    private String id;

    @Column(name = "factory_id", nullable = false, length = 50)
    private String factoryId;

    @Column(name = "product_type_id", nullable = false, length = 100)
    private String productTypeId;

    @Column(name = "sku_code", nullable = false, length = 50)
    private String skuCode;

    @Column(name = "sku_name", nullable = false, length = 200)
    private String skuName;

    @Column(name = "batch_number", nullable = false, length = 100)
    private String batchNumber;

    @Column(name = "production_date", nullable = false)
    private LocalDate productionDate;

    @Column(name = "created_by", nullable = false)
    private Long createdBy;

    @Column(name = "idempotency_key", nullable = false, length = 100)
    private String idempotencyKey;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    private LabelQcTaskStatus status;

    @Column(name = "photo_count", nullable = false)
    private Integer photoCount;

    @Column(name = "ai_candidate_count", nullable = false)
    private Integer aiCandidateCount;

    @Column(name = "final_defect_count", nullable = false)
    private Integer finalDefectCount;

    @Column(name = "reviewed_by")
    private Long reviewedBy;

    @Column(name = "reviewed_at")
    private LocalDateTime reviewedAt;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    @PrePersist
    void prepare() {
        if (id == null || id.isBlank()) {
            id = UUID.randomUUID().toString();
        }
        if (status == null) status = LabelQcTaskStatus.DRAFT;
        if (photoCount == null) photoCount = 0;
        if (aiCandidateCount == null) aiCandidateCount = 0;
        if (finalDefectCount == null) finalDefectCount = 0;
    }
}
