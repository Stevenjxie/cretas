package com.cretas.aims.entity;

import com.cretas.aims.entity.enums.LabelQcObjectDecision;
import com.cretas.aims.entity.enums.LabelQcTrayCropStatus;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.Where;

import java.time.LocalDateTime;
import java.util.UUID;

@Data
@EqualsAndHashCode(callSuper = true)
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "label_qc_tray_crops",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_label_qc_tray_crop_spec",
                columnNames = {"factory_id", "crop_spec_sha256"}),
        indexes = {
                @Index(name = "idx_label_qc_tray_crop_queue", columnList = "factory_id,status,created_at"),
                @Index(name = "idx_label_qc_tray_crop_photo", columnList = "factory_id,photo_id,tray_index")
        })
@Where(clause = "deleted_at IS NULL")
public class LabelQcTrayCrop extends BaseEntity {

    @Id
    @Column(name = "id", nullable = false, length = 36)
    private String id;

    @Version
    @Column(name = "row_version", nullable = false)
    private Long rowVersion;

    @Column(name = "factory_id", nullable = false, length = 50)
    private String factoryId;

    @Column(name = "task_id", nullable = false, length = 36)
    private String taskId;

    @Column(name = "photo_id", nullable = false, length = 36)
    private String photoId;

    @Column(name = "source_attachment_id", nullable = false, length = 191)
    private String sourceAttachmentId;

    @Column(name = "source_image_sha256", length = 64)
    private String sourceImageSha256;

    @Column(name = "tray_index", nullable = false)
    private Integer trayIndex;

    @Column(name = "ai_tray_key", length = 100)
    private String aiTrayKey;

    @Enumerated(EnumType.STRING)
    @Column(name = "source_decision", nullable = false, length = 20)
    private LabelQcObjectDecision sourceDecision;

    @Column(name = "tray_x_min", nullable = false)
    private Double trayXMin;
    @Column(name = "tray_y_min", nullable = false)
    private Double trayYMin;
    @Column(name = "tray_x_max", nullable = false)
    private Double trayXMax;
    @Column(name = "tray_y_max", nullable = false)
    private Double trayYMax;

    @Column(name = "crop_x_min", nullable = false)
    private Double cropXMin;
    @Column(name = "crop_y_min", nullable = false)
    private Double cropYMin;
    @Column(name = "crop_x_max", nullable = false)
    private Double cropXMax;
    @Column(name = "crop_y_max", nullable = false)
    private Double cropYMax;

    @Column(name = "padding_ratio", nullable = false)
    private Double paddingRatio;

    @Column(name = "crop_algorithm_version", nullable = false, length = 40)
    private String cropAlgorithmVersion;

    @Column(name = "object_review_sha256", nullable = false, length = 64)
    private String objectReviewSha256;

    @Column(name = "crop_spec_sha256", nullable = false, length = 64)
    private String cropSpecSha256;

    @Column(name = "coordinate_transform", nullable = false, columnDefinition = "TEXT")
    private String coordinateTransform;

    @Column(name = "factory_label_proposals", nullable = false, columnDefinition = "TEXT")
    private String factoryLabelProposals;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private LabelQcTrayCropStatus status;

    @Column(name = "platform_review_detail", columnDefinition = "TEXT")
    private String platformReviewDetail;

    @Column(name = "reviewed_by")
    private Long reviewedBy;

    @Column(name = "reviewed_at")
    private LocalDateTime reviewedAt;

    @PrePersist
    void prepare() {
        if (id == null || id.isBlank()) id = UUID.randomUUID().toString();
        if (status == null) status = LabelQcTrayCropStatus.PENDING;
    }
}
