package com.cretas.aims.entity;

import com.cretas.aims.entity.enums.LabelQcPhotoStatus;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.Where;

import java.util.UUID;

@Data
@EqualsAndHashCode(callSuper = true)
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "label_qc_photos",
        uniqueConstraints = {
                @UniqueConstraint(name = "uq_label_qc_photo_attachment", columnNames = {"task_id", "attachment_id"}),
                @UniqueConstraint(name = "uq_label_qc_photo_order", columnNames = {"task_id", "order_index"})
        },
        indexes = {
                @Index(name = "idx_label_qc_photo_task", columnList = "factory_id,task_id,order_index")
        })
@Where(clause = "deleted_at IS NULL")
public class LabelQcPhoto extends BaseEntity {

    @Id
    @Column(name = "id", nullable = false, length = 36)
    private String id;

    @Column(name = "factory_id", nullable = false, length = 50)
    private String factoryId;

    @Column(name = "task_id", nullable = false, length = 36)
    private String taskId;

    @Column(name = "attachment_id", nullable = false, length = 191)
    private String attachmentId;

    @Column(name = "order_index", nullable = false)
    private Integer orderIndex;

    @Column(name = "image_width", nullable = false)
    private Integer imageWidth;

    @Column(name = "image_height", nullable = false)
    private Integer imageHeight;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    private LabelQcPhotoStatus status;

    @Column(name = "ai_model", length = 300)
    private String aiModel;

    @Column(name = "prompt_version", length = 100)
    private String promptVersion;

    @Column(name = "analysis_error", columnDefinition = "TEXT")
    private String analysisError;

    @PrePersist
    void prepare() {
        if (id == null || id.isBlank()) {
            id = UUID.randomUUID().toString();
        }
        if (status == null) status = LabelQcPhotoStatus.UPLOADED;
    }
}
