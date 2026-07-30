package com.cretas.aims.entity;

import com.cretas.aims.entity.enums.LabelQcAnnotationSource;
import com.cretas.aims.entity.enums.LabelQcLabel;
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
@Table(name = "label_qc_annotations",
        indexes = {
                @Index(name = "idx_label_qc_annotation_photo", columnList = "factory_id,photo_id"),
                @Index(name = "idx_label_qc_annotation_task", columnList = "factory_id,task_id")
        })
@Where(clause = "deleted_at IS NULL")
public class LabelQcAnnotation extends BaseEntity {

    @Id
    @Column(name = "id", nullable = false, length = 36)
    private String id;

    @Column(name = "factory_id", nullable = false, length = 50)
    private String factoryId;

    @Column(name = "task_id", nullable = false, length = 36)
    private String taskId;

    @Column(name = "photo_id", nullable = false, length = 36)
    private String photoId;

    @Enumerated(EnumType.STRING)
    @Column(name = "source", nullable = false, length = 10)
    private LabelQcAnnotationSource source;

    @Column(name = "ai_candidate_id", length = 100)
    private String aiCandidateId;

    @Enumerated(EnumType.STRING)
    @Column(name = "ai_label", length = 40)
    private LabelQcLabel aiLabel;

    @Column(name = "ai_confidence")
    private Double aiConfidence;

    @Column(name = "ai_evidence", length = 500)
    private String aiEvidence;

    @Enumerated(EnumType.STRING)
    @Column(name = "human_label", length = 40)
    private LabelQcLabel humanLabel;

    @Column(name = "x_min")
    private Double xMin;

    @Column(name = "y_min")
    private Double yMin;

    @Column(name = "x_max")
    private Double xMax;

    @Column(name = "y_max")
    private Double yMax;

    @Column(name = "reviewer_notes", length = 500)
    private String reviewerNotes;

    @Column(name = "reviewed_by")
    private Long reviewedBy;

    @PrePersist
    void prepare() {
        if (id == null || id.isBlank()) {
            id = UUID.randomUUID().toString();
        }
    }
}
